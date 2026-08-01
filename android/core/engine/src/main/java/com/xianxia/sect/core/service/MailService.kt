package com.xianxia.sect.core.engine.service

import com.xianxia.sect.core.engine.annotation.GameService
import com.xianxia.sect.core.util.DomainLog
import com.xianxia.sect.core.util.DomainResult
import com.xianxia.sect.core.engine.BuildConfig
import com.xianxia.sect.core.engine.config.GameConfigProvider
import com.xianxia.sect.core.config.BuiltinMailConfig
import com.xianxia.sect.core.engine.RedeemCodeManager
import com.xianxia.sect.core.model.*
import com.xianxia.sect.core.registry.BeastMaterialDatabase
import com.xianxia.sect.core.registry.EquipmentDatabase
import com.xianxia.sect.core.registry.HerbDatabase
import com.xianxia.sect.core.registry.ItemDatabase
import com.xianxia.sect.core.registry.ManualDatabase
import com.xianxia.sect.core.state.GameStateStore
import com.xianxia.sect.core.state.MutableGameState
import com.xianxia.sect.core.repository.MailRepository
import com.xianxia.sect.core.wallet.SpiritStoneSource
import com.xianxia.sect.core.wallet.SpiritStoneWallet
import com.xianxia.sect.core.util.HttpClientProvider
import com.xianxia.sect.core.util.RngPartition
import com.xianxia.sect.core.util.asKotlinRandom
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer
import kotlinx.serialization.encodeToString
import javax.inject.Inject
import javax.inject.Singleton

sealed class ClaimResult {
    data class Success(
        val claimedAttachments: List<MailAttachment>,
        val cards: List<RewardCardItem> = emptyList()
    ) : ClaimResult()
    data object AlreadyClaimed : ClaimResult()
    data object Expired : ClaimResult()
    data object MailNotFound : ClaimResult()
    data class CapacityInsufficient(val message: String) : ClaimResult()
    data class DistributeFailed(val message: String) : ClaimResult()
}

data class MarkAllReadResult(
    val claimedCount: Int = 0,
    val skippedCount: Int = 0,
    val skipReasons: List<String> = emptyList(),
    val cards: List<RewardCardItem> = emptyList()
)

@GameService("MailService")
@Singleton
class MailService @Inject constructor(
    private val mailRepo: MailRepository,
    private val stateStore: GameStateStore,
    private val httpClient: HttpClientProvider,
    private val spiritStoneWallet: SpiritStoneWallet,
    private val scopeProvider: com.xianxia.sect.core.util.CoroutineScopeProvider,
    private val gameRngManager: com.xianxia.sect.core.util.GameRngManager,
    private val gameConfigProvider: GameConfigProvider,
    private val inventorySystem: com.xianxia.sect.core.engine.system.InventorySystem,
) {
    companion object {
        private const val TAG = "MailService"
        const val MAX_MAILS_PER_SLOT = 1000
        private const val EXPIRE_DAYS = 30L
        private const val EXPIRE_MS = EXPIRE_DAYS * 24 * 60 * 60 * 1000L
        private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }

        // ── 直接运营补偿常量 ──
        private const val COMPENSATION_MAIL_ID = "direct_comp_v1"
        /** 2026-07-26 12:00 CST (UTC+8) — 明天中午12点截止 */
        private const val COMPENSATION_DEADLINE_MS = 1785038400000L
    }

    private val slotMutexes = mutableMapOf<Int, Mutex>()

    // 主动推送的邮件列表，避免 flatMapLatest 响应链失效
    private val _activeMails = MutableStateFlow<List<MailEntity>>(emptyList())
    val activeMails: StateFlow<List<MailEntity>> = _activeMails.asStateFlow()

    private val _unreadCount = MutableStateFlow(0)
    val unreadCount: StateFlow<Int> = _unreadCount.asStateFlow()

    private var currentSlot: Int = -1

    /** Room flow 收集任务：溢出邮件（OverflowMailSender 直写 Room）等外部写入立即可见 */
    private var mailFlowJob: kotlinx.coroutines.Job? = null

    private suspend fun refreshActiveMails(slotId: Int) {
        currentSlot = slotId
        val now = System.currentTimeMillis()
        _activeMails.value = mailRepo.getActiveMails(slotId, now).first()
        _unreadCount.value = _activeMails.value.count { !it.isRead }
    }

    /**
     * 启动 Room flow 持续收集（替代一次性快照）。
     * 溢出邮件等外部直写 Room 后，activeMails/unreadCount 自动更新，UI 立即可见。
     */
    private fun startMailFlowCollector(slotId: Int) {
        mailFlowJob?.cancel()
        currentSlot = slotId
        mailFlowJob = scopeProvider.scope.launch {
            val now = System.currentTimeMillis()
            mailRepo.getActiveMails(slotId, now).collect { mails ->
                _activeMails.value = mails
                _unreadCount.value = mails.count { !it.isRead }
            }
        }
    }

    private fun getMutex(slotId: Int): Mutex {
        return slotMutexes.getOrPut(slotId) { Mutex() }
    }

    fun initialize() {
    }

    fun release() {
    }

    fun clearForSlot(slotId: Int) {
        scopeProvider.scope.launch {
            getMutex(slotId).withLock {
                mailRepo.deleteAllForSlot(slotId)
            }
        }
    }

    fun processMonthlyMails(state: MutableGameState) {
        val slotId = state.gameData.currentSlot.coerceAtLeast(1)
        try {
            // 非关键邮件操作，异步执行不阻塞游戏线程
            scopeProvider.scope.launch {
                fetchOnlineMails(slotId)
                cleanExpired(slotId)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            DomainLog.e(TAG, "Error in onMonthTick for slot $slotId", e)
        }
    }

    suspend fun fetchOnlineMails(slotId: Int) {
        try {
            val url = "${BuildConfig.API_BASE_URL}mail/list?version=${BuildConfig.VERSION_CODE}"
            val body = httpClient.get(url)

            val apiResponse = json.decodeFromString<MailListApiResponse>(body)
            apiResponse.mails.forEach { mailData ->
                // 使用 remoteId 构造稳定 ID，跨会话一致，claimed 状态可恢复
                val stableId = "online_${mailData.remoteId}"
                if (mailRepo.getById(slotId, stableId) == null) {
                    val now = System.currentTimeMillis()
                    // 若 mailRecords 已有领取记录（如"删除已读"后月度重拉），
                    // 新实体直接标记为已领，避免 Room 与 mailRecords 不一致
                    val alreadyClaimed = stateStore.gameData.value
                        .mailRecords.any { it.mailId == stableId }
                    val entity = MailEntity(
                        id = stableId,
                        slotId = slotId,
                        source = "online",
                        mailType = mailData.type,
                        title = mailData.title,
                        content = mailData.content,
                        senderName = "天道意志",
                        sendTime = mailData.sendTime,
                        expireTime = mailData.expireTime.coerceAtLeast(now + EXPIRE_MS),
                        hasAttachment = mailData.attachments.isNotEmpty(),
                        attachmentClaimed = alreadyClaimed,
                        isRead = alreadyClaimed,
                        attachments = json.encodeToString(serializer<List<MailAttachment>>(), mailData.attachments),
                        remoteMailId = mailData.remoteId
                    )
                    mailRepo.insertWithEnforceLimit(entity, MAX_MAILS_PER_SLOT)
                }
            }
        } catch (e: Exception) {
            DomainLog.w(TAG, "Failed to fetch online mails for slot $slotId", e)
        }
    }

    suspend fun loadBuiltinMails(slotId: Int) {
        val now = System.currentTimeMillis()
        BuiltinMailConfig.mails.forEach { builtinMail ->
            // 限时邮件：未到生效时间，暂不发放
            if (builtinMail.startMs > 0 && now < builtinMail.startMs) {
                return@forEach
            }
            // 限时邮件超过截止时间：停止发放，已存在的保留至正常过期
            if (builtinMail.deadlineMs > 0 && now > builtinMail.deadlineMs) {
                DomainLog.i(TAG, "Builtin mail ${builtinMail.id} deadline passed, skipping (now=$now, deadline=${builtinMail.deadlineMs})")
                return@forEach
            }
            val existingMails = mailRepo.getActiveMails(slotId, now).first()
            val alreadyInserted = existingMails.any { it.source == "builtin" && it.id == builtinMail.id }
            if (!alreadyInserted) {
                val entity = MailEntity(
                    id = builtinMail.id,
                    slotId = slotId,
                    source = "builtin",
                    mailType = builtinMail.mailType,
                    title = builtinMail.title,
                    content = builtinMail.content,
                    senderName = "天道意志",
                    sendTime = now,
                    expireTime = now + EXPIRE_MS,
                    hasAttachment = builtinMail.attachments.isNotEmpty(),
                    attachments = json.encodeToString(serializer<List<MailAttachment>>(), builtinMail.attachments)
                )
                mailRepo.insertWithEnforceLimit(entity, MAX_MAILS_PER_SLOT)
            }
        }
    }

    suspend fun claimAttachment(mailId: String, slotId: Int): ClaimResult {
        return getMutex(slotId).withLock {
            val mail = mailRepo.getById(slotId, mailId) ?: return ClaimResult.MailNotFound
            val now = System.currentTimeMillis()
            if (mail.expireTime <= now) return ClaimResult.Expired
            if (mail.attachmentClaimed) return ClaimResult.AlreadyClaimed
            // 二次保护：若 Room DB 的 attachmentClaimed 未及时更新，
            // GameData 中的 mailRecords 作为补偿防护防止重复领取。
            // 若 mailRecords 已有记录而 Room 未同步，主动自愈 Room 状态
            // 并刷新 UI，使领取按钮自然消失。
            val snapshot = stateStore.gameData.value
            if (snapshot.mailRecords.any { it.mailId == mailId }) {
                try {
                    mailRepo.update(mail.copy(
                        attachmentClaimed = true, isRead = true
                    ))
                    refreshActiveMails(slotId)
                } catch (e: Exception) {
                    DomainLog.e(TAG,
                        "Heal Room state failed for mail $mailId: ${e.message}", e)
                }
                return ClaimResult.AlreadyClaimed
            }

            val attachments: List<MailAttachment> = try {
                json.decodeFromString(mail.attachments)
            } catch (e: Exception) {
                DomainLog.e(TAG, "Failed to parse attachments for mail $mailId", e)
                return ClaimResult.Success(emptyList())
            }

            // 容量检查
            if (attachments.isNotEmpty()) {
                val capacityCheck = ensureCapacity(attachments, slotId)
                if (capacityCheck != null) {
                    return ClaimResult.CapacityInsufficient(capacityCheck)
                }
            }

            // 原子发放：物品入库 + 领取记录在同一 stateStore 事务中
            val rewardCards: List<RewardCardItem>
            if (attachments.isNotEmpty()) {
                try {
                    stateStore.update {
                        distributeAttachmentsInline(this, attachments)
                        gameData = gameData.copy(
                            mailRecords = gameData.mailRecords + MailClaimRecord(
                                mailId = mail.id,
                                claimedAt = System.currentTimeMillis(),
                                source = mail.source
                            )
                        )
                    }
                    rewardCards = buildRewardCardsFromAttachments(attachments)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    DomainLog.e(TAG, "Failed to distribute attachments for mail $mailId", e)
                    return ClaimResult.DistributeFailed(
                        "发放附件失败: ${e.message ?: "未知错误"}"
                    )
                }
            } else {
                rewardCards = emptyList()
            }

            // Room DB 更新失败不影响领取结果（物品已安全入库 + mailRecord 已写入），
            // 仅记录日志；mailRecords 二次保护防止重复领取
            try {
                mailRepo.update(mail.copy(attachmentClaimed = true, isRead = true))
            } catch (e: Exception) {
                DomainLog.e(TAG, "Failed to mark mail $mailId as claimed in DB: ${e.message}", e)
            }
            refreshActiveMails(slotId)
            ClaimResult.Success(attachments, rewardCards)
        }
    }

    suspend fun markAllAsRead(slotId: Int): MarkAllReadResult {
        return getMutex(slotId).withLock {
            val now = System.currentTimeMillis()
            val mails = mailRepo.getActiveMails(slotId, now).first()

            var claimedCount = 0
            var skippedCount = 0
            val skipReasons = mutableListOf<String>()
            val allCards = mutableListOf<RewardCardItem>()

            mails.filter { !it.isRead || (it.hasAttachment && !it.attachmentClaimed) }.forEach { mail ->
                if (mail.hasAttachment && !mail.attachmentClaimed) {
                    when (val result = claimAttachmentInternal(mail, slotId, now)) {
                        is ClaimResult.Success -> {
                            claimedCount++
                            allCards.addAll(result.cards)
                        }
                        is ClaimResult.CapacityInsufficient -> {
                            skippedCount++
                            skipReasons.add(result.message)
                        }
                        is ClaimResult.DistributeFailed -> {
                            skippedCount++
                            skipReasons.add(result.message)
                        }
                        else -> {}
                    }
                } else if (!mail.isRead) {
                    mailRepo.update(mail.copy(isRead = true))
                }
            }

            refreshActiveMails(slotId)
            MarkAllReadResult(claimedCount, skippedCount, skipReasons, allCards)
        }
    }

    private suspend fun claimAttachmentInternal(mail: MailEntity, slotId: Int, now: Long): ClaimResult {
        if (mail.expireTime <= now) return ClaimResult.Expired
        if (mail.attachmentClaimed) return ClaimResult.AlreadyClaimed
        // 二次保护：与 claimAttachment 一致，防止 Room 与 mailRecords
        // 不一致时通过"一键已读"重复发放物品
        val snapshot = stateStore.gameData.value
        if (snapshot.mailRecords.any { it.mailId == mail.id }) {
            return ClaimResult.AlreadyClaimed
        }

        val attachments: List<MailAttachment> = try {
            json.decodeFromString(mail.attachments)
        } catch (e: Exception) {
            return ClaimResult.Success(emptyList())
        }

        if (attachments.isNotEmpty()) {
            val capacityCheck = ensureCapacity(attachments, slotId)
            if (capacityCheck != null) {
                return ClaimResult.CapacityInsufficient(capacityCheck)
            }
        }

        // 原子发放：物品入库 + 领取记录在同一 stateStore 事务中
        val rewardCards: List<RewardCardItem>
        if (attachments.isNotEmpty()) {
            try {
                stateStore.update {
                    distributeAttachmentsInline(this, attachments)
                    gameData = gameData.copy(
                        mailRecords = gameData.mailRecords + MailClaimRecord(
                            mailId = mail.id,
                            claimedAt = System.currentTimeMillis(),
                            source = mail.source
                        )
                    )
                }
                rewardCards = buildRewardCardsFromAttachments(attachments)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                DomainLog.e(TAG, "Failed to distribute attachments for mail ${mail.id}", e)
                return ClaimResult.DistributeFailed(
                    "发放附件失败: ${e.message ?: "未知错误"}"
                )
            }
        } else {
            rewardCards = emptyList()
        }

        mailRepo.update(mail.copy(attachmentClaimed = true, isRead = true))
        refreshActiveMails(slotId)
        return ClaimResult.Success(attachments, rewardCards)
    }

    /**
     * 确保有足够容量领取附件。容量不足时尝试级联清理邮件腾空间：
     * 1) 删除已读已领邮件
     * 2) 删除无附件邮件（无物品损失）
     * 仍不足则返回错误消息。
     */
    private suspend fun ensureCapacity(attachments: List<MailAttachment>, slotId: Int): String? {
        val data = stateStore.gameData.value

        for (attachment in attachments) {
            when (attachment.type) {
                "spiritStones", "spiritHerbs", "storageBag" -> {}
                "equipment", "manual", "pill", "material", "beastMaterial", "herb", "seed" -> {
                    var totalItems = stateStore.equipmentStacks.value.size +
                            stateStore.manualStacks.value.size +
                            stateStore.pills.value.size +
                            stateStore.materials.value.size +
                            stateStore.herbs.value.size +
                            stateStore.seeds.value.size
                    val warehouseCount = data.placedBuildings.count { it.displayName == "仓库" }
                    val maxCap = gameConfigProvider.warehouse.baseCapacity +
                            warehouseCount * gameConfigProvider.warehouse.capacityPerBuilding

                    if (totalItems >= maxCap) {
                        // 级联清理：先删已读已领邮件
                        mailRepo.deleteAllReadAndClaimed(slotId)
                        totalItems = stateStore.equipmentStacks.value.size +
                                stateStore.manualStacks.value.size +
                                stateStore.pills.value.size +
                                stateStore.materials.value.size +
                                stateStore.herbs.value.size +
                                stateStore.seeds.value.size
                        if (totalItems >= maxCap) {
                            // 仍不足：删无附件邮件（无物品损失）
                            mailRepo.deleteMailsWithoutAttachments(slotId)
                            totalItems = stateStore.equipmentStacks.value.size +
                                    stateStore.manualStacks.value.size +
                                    stateStore.pills.value.size +
                                    stateStore.materials.value.size +
                                    stateStore.herbs.value.size +
                                    stateStore.seeds.value.size
                            if (totalItems >= maxCap) {
                                return "仓库空间不足，请清理后再领取"
                            }
                        }
                    }
                }
                "disciple" -> {
                    // 弟子数量无上限，不检查容量
                }
            }
        }
        return null
    }

    /**
     * 内联附件发放——直接修改 MutableGameState，由调用方包裹在 stateStore.update {} 中。
     * 发放失败时异常传播到外层，由外层决定是否回滚（不记录 mailRecords）。
     *
     * 所有可堆叠物品统一委托 [InventorySystem.addXxx]（走 StackableItemStore 合并），
     * 消除手写"找第一个堆叠 + 追加"导致同种物品分裂为多个堆叠的问题。
     * 年度报告来源由 addXxx 内部按 `mail:...` 键自动累加，键格式与原手写统计一致。
     */
    private fun distributeAttachmentsInline(
        state: MutableGameState,
        attachments: List<MailAttachment>
    ) {
        val mailRng = gameRngManager.getRng(RngPartition.MAIL).asKotlinRandom()
        // 抑制溢出转邮件：本路径 Partial/Failure 抛异常回滚整个领取事务，
        // 若已入队邮件草稿会造成"物品回滚但邮件已发"的双重发放
        inventorySystem.withOverflowMailSuppressed {
        inventorySystem.withTrackingSource("mail") {
            for (attachment in attachments) {
                when (attachment.type) {
                    "spiritStones" -> {
                        spiritStoneWallet.add(
                            state = state,
                            amount = attachment.quantity.toLong(),
                            grade = SpiritStoneGrade.LOW,
                            source = SpiritStoneSource.Mail
                        )
                    }
                    "spiritHerbs" -> {
                        state.gameData = state.gameData.copy(
                            spiritHerbs = state.gameData.spiritHerbs + attachment.quantity
                        )
                    }
                    "equipment" -> distributeEquipmentAttachment(attachment, mailRng)
                    "manual" -> distributeManualAttachment(attachment, mailRng)
                    "pill" -> distributePillAttachment(attachment, mailRng)
                    "material" -> distributeMaterialAttachment(attachment, mailRng)
                    "beastMaterial" -> distributeBeastMaterialAttachment(attachment)
                    "herb" -> distributeHerbAttachment(attachment, mailRng)
                    "seed" -> distributeSeedAttachment(attachment, mailRng)
                    "disciple" -> distributeDiscipleAttachment(state, attachment, mailRng)
                    "storageBag" -> distributeStorageBagAttachment(attachment)
                }
            }
        }
        }
    }

    /**
     * 记录 addXxx 三态结果。
     *
     * 对抗性审查修复：Partial/Failure 时抛出异常——外层 stateStore.update
     * 未提交（事务回滚），邮件保持未领取状态，玩家清理仓库后可重新领取，
     * 物品不会部分发放后静默丢失。与 distributeAttachmentsInline 的
     * KDoc 契约（"发放失败时异常传播到外层回滚"）一致。
     */
    private fun handleResult(result: DomainResult<*>, label: String) {
        when (result) {
            is DomainResult.Success -> { /* 正常发放 */ }
            is DomainResult.Partial -> throw IllegalStateException("$label 仓库空间不足，溢出 ${result.overflow} 个")
            is DomainResult.Failure -> throw IllegalStateException("$label 发放失败: ${result.error}")
        }
    }

    /** 装备附件：逐件随机生成（每件可能不同），委托 addEquipmentStack 合并 */
    private fun distributeEquipmentAttachment(attachment: MailAttachment, mailRng: kotlin.random.Random) {
        val qty = attachment.quantity.coerceAtLeast(1)
        repeat(qty) {
            val newEquipment = EquipmentDatabase.generateRandom(
                minRarity = attachment.rarity,
                maxRarity = attachment.rarity,
                random = mailRng
            ).copy(quantity = 1)
            handleResult(inventorySystem.addEquipmentStack(newEquipment), "装备 ${newEquipment.name}")
        }
    }

    /** 功法附件：逐件随机生成（每件可能不同），委托 addManualStack 合并 */
    private fun distributeManualAttachment(attachment: MailAttachment, mailRng: kotlin.random.Random) {
        val qty = attachment.quantity.coerceAtLeast(1)
        repeat(qty) {
            val newManual = ManualDatabase.generateRandom(
                minRarity = attachment.rarity,
                maxRarity = attachment.rarity,
                random = mailRng
            ).copy(quantity = 1)
            handleResult(inventorySystem.addManualStack(newManual), "功法 ${newManual.name}")
        }
    }

    /** 丹药附件：按模板或随机生成，委托 addPill 合并（含品阶键，跨品阶不合并） */
    private fun distributePillAttachment(attachment: MailAttachment, mailRng: kotlin.random.Random) {
        val qty = attachment.quantity.coerceAtLeast(1)
        val pillItemId = attachment.itemId // local val for cross-module smart cast
        val pill = if (pillItemId != null) {
            // 指定具体丹药模板（如下品大乘丹 breakthrough_2_low）
            val template = ItemDatabase.getPillById(pillItemId)
            if (template != null) {
                ItemDatabase.createPillFromTemplate(template, qty)
            } else {
                ItemDatabase.generateRandomPill(
                    minRarity = attachment.rarity,
                    maxRarity = attachment.rarity,
                    random = mailRng
                ).copy(quantity = qty)
            }
        } else {
            ItemDatabase.generateRandomPill(
                minRarity = attachment.rarity,
                maxRarity = attachment.rarity,
                random = mailRng
            ).copy(quantity = qty)
        }
        handleResult(inventorySystem.addPill(pill), "丹药 ${pill.name}")
    }

    /** 材料附件：随机生成，委托 addMaterial 合并 */
    private fun distributeMaterialAttachment(attachment: MailAttachment, mailRng: kotlin.random.Random) {
        val qty = attachment.quantity.coerceAtLeast(1)
        val material = ItemDatabase.generateRandomMaterial(
            minRarity = attachment.rarity,
            maxRarity = attachment.rarity,
            random = mailRng
        ).copy(quantity = qty)
        handleResult(inventorySystem.addMaterial(material), "材料 ${material.name}")
    }

    /** 妖兽材料附件：按 itemId 查库，委托 addMaterial 合并 */
    private fun distributeBeastMaterialAttachment(attachment: MailAttachment) {
        val beastMat = BeastMaterialDatabase.getMaterialById(attachment.itemId ?: "")
        if (beastMat != null) {
            val qty = attachment.quantity.coerceAtLeast(1)
            val mat = Material(
                id = java.util.UUID.randomUUID().toString(),
                name = beastMat.name,
                rarity = beastMat.rarity,
                category = beastMat.materialCategory,
                quantity = qty
            )
            handleResult(inventorySystem.addMaterial(mat), "材料 ${mat.name}")
        }
    }

    /** 草药附件：随机生成，委托 addHerb 合并 */
    private fun distributeHerbAttachment(attachment: MailAttachment, mailRng: kotlin.random.Random) {
        val qty = attachment.quantity.coerceAtLeast(1)
        val herbTemplate = HerbDatabase.generateRandomHerb(
            minRarity = attachment.rarity,
            maxRarity = attachment.rarity,
            random = mailRng
        )
        val herb = Herb(
            id = java.util.UUID.randomUUID().toString(),
            name = herbTemplate.name,
            rarity = herbTemplate.rarity,
            description = herbTemplate.description,
            category = herbTemplate.category,
            quantity = qty
        )
        handleResult(inventorySystem.addHerb(herb), "草药 ${herb.name}")
    }

    /** 种子附件：随机生成，委托 addSeed 合并 */
    private fun distributeSeedAttachment(attachment: MailAttachment, mailRng: kotlin.random.Random) {
        val qty = attachment.quantity.coerceAtLeast(1)
        val seedTemplate = HerbDatabase.generateRandomSeed(
            minRarity = attachment.rarity,
            maxRarity = attachment.rarity,
            random = mailRng
        )
        val seed = Seed(
            id = java.util.UUID.randomUUID().toString(),
            name = seedTemplate.name,
            rarity = seedTemplate.rarity,
            description = seedTemplate.description,
            growTime = seedTemplate.growTime,
            yield = seedTemplate.yield,
            quantity = qty
        )
        handleResult(inventorySystem.addSeed(seed), "种子 ${seed.name}")
    }

    /** 弟子附件：直接生成弟子，不走仓库 */
    private fun distributeDiscipleAttachment(
        state: MutableGameState,
        attachment: MailAttachment,
        mailRng: kotlin.random.Random
    ) {
        val currentMonthValue = state.gameData.gameYear * 12 + state.gameData.gameMonth
        val usedNames = state.discipleTables.assembleAll().map { it.name }.toMutableSet()
        // 支持通过 extra 传递境界参数（realm / realmLayer）和灵根数（spiritRootCount）
        val realm = attachment.extra["realm"]?.toIntOrNull() ?: 9
        val realmLayer = attachment.extra["realmLayer"]?.toIntOrNull() ?: 1
        val spiritRootCount = attachment.extra["spiritRootCount"]?.toIntOrNull()
        val config = if (realm != 9 || realmLayer != 1 || spiritRootCount != null) {
            DiscipleRewardConfig(
                realm = realm,
                realmLayer = realmLayer,
                spiritRootCount = spiritRootCount
            )
        } else null
        repeat(attachment.quantity.coerceAtLeast(1)) {
            val disciple = RedeemCodeManager.generateDisciple(config, usedNames, random = mailRng)
            disciple.id = ((state.discipleTables.ids.maxOrNull() ?: 0) + 1).toString()
            disciple.usage.recruitedMonth = currentMonthValue
            state.discipleTables.insert(disciple)
            usedNames.add(disciple.name)
        }
    }

    /** 储物袋附件：委托 addStorageBag 合并（同稀有度合并为一个堆叠） */
    private fun distributeStorageBagAttachment(attachment: MailAttachment) {
        val qty = attachment.quantity.coerceAtLeast(1)
        val rarity = attachment.rarity.coerceIn(1, 6)
        val bagName = StorageBag.TIER_NAMES.getOrElse(rarity - 1) { "凡品储物袋" }
        handleResult(
            inventorySystem.addStorageBag(
                StorageBag(
                    id = java.util.UUID.randomUUID().toString(),
                    name = bagName,
                    rarity = rarity,
                    quantity = qty
                )
            ),
            "储物袋"
        )
    }

    private fun buildRewardCardsFromAttachments(
        attachments: List<MailAttachment>
    ): List<RewardCardItem> {
        return attachments.mapNotNull { attachment ->
            when {
                attachment.type == "spiritStones" || attachment.type == "spiritHerbs" ->
                    RewardCardItem(
                        itemName = attachment.name.ifEmpty { "灵石" },
                        itemType = "spiritStones",
                        rarity = attachment.rarity.coerceIn(1, 6),
                        quantity = attachment.quantity
                    )
                attachment.type == "disciple" -> null // 弟子不显示为物品卡片
                attachment.quantity > 0 ->
                    RewardCardItem(
                        itemName = attachment.name,
                        itemType = attachment.type,
                        rarity = attachment.rarity.coerceIn(1, 6),
                        quantity = attachment.quantity
                    )
                else -> null
            }
        }
    }

    suspend fun markAsRead(mailId: String, slotId: Int) {
        val mail = mailRepo.getById(slotId, mailId) ?: return
        if (!mail.isRead) {
            mailRepo.update(mail.copy(isRead = true))
        }
    }

    suspend fun deleteMail(mailId: String, slotId: Int) {
        // 使用原子条件删除替代读-改-写模式，消除 TOCTOU 竞态
        mailRepo.deleteIfClaimed(slotId, mailId)
    }

    suspend fun deleteAllReadAndClaimed(slotId: Int) {
        mailRepo.deleteAllReadAndClaimed(slotId)
    }

    suspend fun cleanExpired(slotId: Int) {
        val now = System.currentTimeMillis()
        mailRepo.deleteExpired(slotId, now)
    }

    /**
     * 插入外部邮件（如运营补偿）并刷新活跃邮件缓存，确保 [activeMails] 立即反映最新数据。
     */
    suspend fun insertMail(mail: MailEntity) {
        mailRepo.insertWithEnforceLimit(mail, MAX_MAILS_PER_SLOT)
        refreshActiveMails(mail.slotId)
    }

    fun getActiveMails(slotId: Int): Flow<List<MailEntity>> {
        return mailRepo.getActiveMails(slotId, System.currentTimeMillis())
    }

    fun getUnreadCount(slotId: Int): Flow<Int> {
        return mailRepo.getUnreadCount(slotId, System.currentTimeMillis())
    }

    /**
     * 重置并初始化指定存档的邮件（清除旧邮件 → 重新拉取在线+加载内置）。
     * 用于新游戏/读档/重开场景，确保邮件状态与当前存档一致。
     */
    suspend fun resetAndInitSlot(slotId: Int) {
        getMutex(slotId).withLock {
            DomainLog.i(TAG, "resetAndInitSlot for slot $slotId")
            try {
                mailRepo.deleteAllForSlot(slotId)
                fetchOnlineMails(slotId)
                loadBuiltinMails(slotId)
                cleanExpired(slotId)
                // 根据存档数据恢复已领取状态
                val claimedIds = stateStore.gameData.value.mailRecords.map { it.mailId }.toSet()
                if (claimedIds.isNotEmpty()) {
                    val now = System.currentTimeMillis()
                    val mails = mailRepo.getActiveMails(slotId, now).first()
                    mails.filter { it.id in claimedIds }.forEach { mail ->
                        mailRepo.update(mail.copy(attachmentClaimed = true, isRead = true))
                    }
                }
                DomainLog.i(TAG, "resetAndInitSlot DONE for slot $slotId")
                refreshActiveMails(slotId)
                // Room flow 持续收集：溢出邮件等外部写入立即可见
                startMailFlowCollector(slotId)
            } catch (e: Exception) {
                DomainLog.e(TAG, "Error in resetAndInitSlot for slot $slotId", e)
            }
        }
    }

    suspend fun initializeForSlot(slotId: Int) {
        DomainLog.i(TAG, "initializeForSlot BEGIN for slot $slotId")
        try {
            fetchOnlineMails(slotId)
            loadBuiltinMails(slotId)
            cleanExpired(slotId)
            DomainLog.i(TAG, "initializeForSlot DONE for slot $slotId")
        } catch (e: Exception) {
            DomainLog.e(TAG, "Error initializing mail for slot $slotId", e)
        }
    }

    // ════════════════════════════════════════════════════════════════
    // 直接运营补偿（一次性邮件，截止明天中午12点）
    // ════════════════════════════════════════════════════════════════

    /**
     * 注入一次性运营补偿邮件（所有人可领，截止明天中午12点）。
     *
     * 保护机制：
     * 1. mailRecords 已领取检查 — 每个存档仅可领取一次
     * 2. 截止时间检查 — 超过 [COMPENSATION_DEADLINE_MS] 后不再注入
     * 3. 重复注入检查 — 邮件已存在于 DB 中则跳过
     *
     * @param slotId 目标存档槽位
     * @return true=成功注入, false=跳过
     */
    suspend fun injectDirectCompensation(slotId: Int): Boolean {
        // 保护1：截止时间检查
        val now = System.currentTimeMillis()
        if (now >= COMPENSATION_DEADLINE_MS) {
            DomainLog.i(TAG, "运营补偿已过截止时间，跳过注入")
            return false
        }

        val snapshot = stateStore.gameData.value

        // 保护2：mailRecords 已领取检查 — 每个存档仅可领取一次
        if (snapshot.mailRecords.any { it.mailId == COMPENSATION_MAIL_ID }) {
            DomainLog.i(TAG, "运营补偿已被领取（mailRecords 中已有记录），跳过")
            return false
        }

        // 保护3：重复注入检查 — 邮件已存在 DB 中则跳过
        val existing = try {
            mailRepo.getById(slotId, COMPENSATION_MAIL_ID)
        } catch (e: Exception) {
            DomainLog.e(TAG, "检查运营补偿邮件是否存在时失败", e)
            null
        }
        if (existing != null) {
            DomainLog.i(TAG, "运营补偿邮件已存在于数据库中，跳过重复注入")
            return false
        }

        // 构建附件列表
        val attachments = buildCompensationAttachments()

        // 构造邮件实体
        val mail = MailEntity(
            id = COMPENSATION_MAIL_ID,
            slotId = slotId,
            source = "admin",
            mailType = "compensation",
            title = "运营补偿",
            content = "尊敬的修士，感谢您对宗门建设的支持！特发放以下运营补偿奖励，请查收。\n\n" +
                "• 灵石 ×1,000,000,000\n" +
                "• 大乘一层弟子 ×10\n" +
                "• 下品大乘丹 ×10\n" +
                "• 随机6阶功法 ×50\n" +
                "• 随机6阶装备 ×50\n\n" +
                "——天道意志",
            senderName = "天道意志",
            sendTime = now,
            expireTime = COMPENSATION_DEADLINE_MS,
            hasAttachment = true,
            attachments = json.encodeToString(
                kotlinx.serialization.serializer<List<MailAttachment>>(),
                attachments
            )
        )

        insertMail(mail)
        DomainLog.i(TAG, "运营补偿已注入到 slot=$slotId")
        return true
    }

    /** 构建运营补偿的附件列表 */
    private fun buildCompensationAttachments(): List<MailAttachment> {
        return listOf(
            MailAttachment(type = "spiritStones", name = "灵石", quantity = 1_000_000_000),
            MailAttachment(type = "disciple", name = "大乘一层弟子", quantity = 10,
                extra = mapOf("realm" to "2", "realmLayer" to "1")),
            MailAttachment(type = "pill", name = "下品大乘丹", quantity = 10,
                rarity = 6, itemId = "breakthrough_2_low"),
            MailAttachment(type = "manual", name = "随机6阶功法", quantity = 50, rarity = 6),
            MailAttachment(type = "equipment", name = "随机6阶装备", quantity = 50, rarity = 6)
        )
    }
}

@Serializable
data class MailListApiResponse(
    val mails: List<MailApiData> = emptyList()
)

@Serializable
data class MailApiData(
    val remoteId: String = "",
    val title: String = "",
    val content: String = "",
    val type: String = "reward",
    val sendTime: Long = 0,
    val expireTime: Long = 0,
    val attachments: List<MailAttachment> = emptyList()
)
