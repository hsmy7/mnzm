package com.xianxia.sect.core.engine.service

import com.xianxia.sect.core.engine.annotation.GameService
import com.xianxia.sect.core.util.DomainLog
import com.xianxia.sect.core.engine.BuildConfig
import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.config.BuiltinMailConfig
import com.xianxia.sect.core.config.InventoryConfig
import com.xianxia.sect.core.engine.RedeemCodeManager
import com.xianxia.sect.core.model.*
import com.xianxia.sect.core.registry.BeastMaterialDatabase
import com.xianxia.sect.core.registry.EquipmentDatabase
import com.xianxia.sect.core.registry.HerbDatabase
import com.xianxia.sect.core.registry.ItemDatabase
import com.xianxia.sect.core.state.GameStateStore
import com.xianxia.sect.core.state.MutableGameState
import com.xianxia.sect.core.repository.MailRepository
import com.xianxia.sect.core.util.HttpClientProvider
import dagger.hilt.android.qualifiers.ApplicationContext
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
    private val inventoryConfig: InventoryConfig,
    private val httpClient: HttpClientProvider,
    @ApplicationContext private val appContext: android.content.Context
) {
    companion object {
        private const val TAG = "MailService"
        const val MAX_MAILS_PER_SLOT = 1000
        private const val EXPIRE_DAYS = 30L
        private const val EXPIRE_MS = EXPIRE_DAYS * 24 * 60 * 60 * 1000L
        private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }
    }

    private val slotMutexes = mutableMapOf<Int, Mutex>()

    // 主动推送的邮件列表，避免 flatMapLatest 响应链失效
    private val _activeMails = MutableStateFlow<List<MailEntity>>(emptyList())
    val activeMails: StateFlow<List<MailEntity>> = _activeMails.asStateFlow()

    private val _unreadCount = MutableStateFlow(0)
    val unreadCount: StateFlow<Int> = _unreadCount.asStateFlow()

    private var currentSlot: Int = -1

    private suspend fun refreshActiveMails(slotId: Int) {
        currentSlot = slotId
        val now = System.currentTimeMillis()
        _activeMails.value = mailRepo.getActiveMails(slotId, now).first()
        _unreadCount.value = _activeMails.value.count { !it.isRead }
    }

    private fun getMutex(slotId: Int): Mutex {
        return slotMutexes.getOrPut(slotId) { Mutex() }
    }

    fun initialize() {
    }

    fun release() {
    }

    suspend fun clearForSlot(slotId: Int) {
        getMutex(slotId).withLock {
            mailRepo.deleteAllForSlot(slotId)
        }
    }

    suspend fun processMonthlyMails(state: MutableGameState) {
        val slotId = state.gameData.slotId
        try {
            fetchOnlineMails(slotId)
            cleanExpired(slotId)
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
                if (!mailRepo.existsByRemoteId(mailData.remoteId)) {
                    val now = System.currentTimeMillis()
                    val entity = MailEntity(
                        id = java.util.UUID.randomUUID().toString(),
                        slotId = slotId,
                        source = "online",
                        mailType = mailData.type,
                        title = mailData.title,
                        content = mailData.content,
                        senderName = "天道意志",
                        sendTime = mailData.sendTime,
                        expireTime = mailData.expireTime.coerceAtLeast(now + EXPIRE_MS),
                        hasAttachment = mailData.attachments.isNotEmpty(),
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
            // 限时邮件超过截止时间：仅停止发放，已存在的保留至正常过期
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
            val mail = mailRepo.getById(mailId) ?: return ClaimResult.MailNotFound
            val now = System.currentTimeMillis()
            if (mail.expireTime <= now) return ClaimResult.Expired
            if (mail.attachmentClaimed) return ClaimResult.AlreadyClaimed

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

            // 发放附件（卡片由 UI 在小屏界面确认后入队）
            val rewardCards = if (attachments.isNotEmpty()) {
                try {
                    distributeAttachments(attachments)
                    buildRewardCardsFromAttachments(attachments)
                } catch (e: Exception) {
                    DomainLog.e(TAG, "Failed to distribute attachments for mail $mailId", e)
                    emptyList()
                }
            } else {
                emptyList()
            }

            mailRepo.update(mail.copy(attachmentClaimed = true, isRead = true))
            // 记录领取状态到存档数据
            stateStore.update {
                gameData = gameData.copy(claimedMailIds = gameData.claimedMailIds + mail.id)
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

        var rewardCards = emptyList<RewardCardItem>()
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

            try {
                distributeAttachments(attachments)
                rewardCards = buildRewardCardsFromAttachments(attachments)
            } catch (e: Exception) {
                DomainLog.e(TAG, "Failed to distribute attachments for mail ${mail.id}", e)
                rewardCards = emptyList()
            }
        }

        mailRepo.update(mail.copy(attachmentClaimed = true, isRead = true))
        // 记录领取状态到存档数据
        stateStore.update {
            gameData = gameData.copy(claimedMailIds = gameData.claimedMailIds + mail.id)
        }
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
        val disciples = stateStore.disciples.value

        for (attachment in attachments) {
            when (attachment.type) {
                "spiritStones", "spiritHerbs", "storageBag" -> {}
                "equipment", "pill", "material", "beastMaterial", "herb", "seed" -> {
                    var totalItems = stateStore.equipmentStacks.value.size +
                            stateStore.manualStacks.value.size +
                            stateStore.pills.value.size +
                            stateStore.materials.value.size +
                            stateStore.herbs.value.size +
                            stateStore.seeds.value.size
                    val warehouseCount = data.placedBuildings.count { it.displayName == "仓库" }
                    val maxCap = GameConfig.Warehouse.BASE_CAPACITY +
                            warehouseCount * GameConfig.Warehouse.CAPACITY_PER_BUILDING

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
                    val aliveCount = disciples.count { it.isAlive }
                    if (aliveCount >= GameConfig.Disciple.MAX_DISCIPLES) {
                        return "宗门弟子已满，无法领取弟子"
                    }
                }
            }
        }
        return null
    }

    private suspend fun distributeAttachments(attachments: List<MailAttachment>) {
        stateStore.update {
            attachments.forEach { attachment ->
                when (attachment.type) {
                    "spiritStones" -> {
                        gameData = gameData.copy(
                            spiritStones = gameData.spiritStones + attachment.quantity
                        )
                    }
                    "spiritHerbs" -> {
                        gameData = gameData.copy(
                            spiritHerbs = gameData.spiritHerbs + attachment.quantity
                        )
                    }
                    "equipment" -> {
                        val qty = attachment.quantity.coerceAtLeast(1)
                        val newEquipment = EquipmentDatabase.generateRandom(
                            minRarity = attachment.rarity,
                            maxRarity = attachment.rarity
                        ).copy(quantity = qty)
                        val existing = equipmentStacks.find {
                            it.name == newEquipment.name && it.rarity == newEquipment.rarity && it.slot == newEquipment.slot
                        }
                        if (existing != null) {
                            val newQty = (existing.quantity + newEquipment.quantity)
                                .coerceAtMost(inventoryConfig.getMaxStackSize("equipment_stack"))
                            equipmentStacks = equipmentStacks.map {
                                if (it.id == existing.id) it.copy(quantity = newQty) else it
                            }
                        } else {
                            equipmentStacks = equipmentStacks + newEquipment
                        }
                    }
                    "pill" -> {
                        val qty = attachment.quantity.coerceAtLeast(1)
                        val pill = ItemDatabase.generateRandomPill(
                            minRarity = attachment.rarity,
                            maxRarity = attachment.rarity
                        ).copy(quantity = qty)
                        val existing = pills.find {
                            it.name == pill.name && it.rarity == pill.rarity && it.category == pill.category
                        }
                        if (existing != null) {
                            val newQty = (existing.quantity + pill.quantity)
                                .coerceAtMost(inventoryConfig.getMaxStackSize("pill"))
                            pills = pills.map { if (it.id == existing.id) it.copy(quantity = newQty) else it }
                        } else {
                            pills = pills + pill
                        }
                    }
                    "material" -> {
                        val qty = attachment.quantity.coerceAtLeast(1)
                        val material = ItemDatabase.generateRandomMaterial(
                            minRarity = attachment.rarity,
                            maxRarity = attachment.rarity
                        ).copy(quantity = qty)
                        val existing = materials.find {
                            it.name == material.name && it.rarity == material.rarity && it.category == material.category
                        }
                        if (existing != null) {
                            val newQty = (existing.quantity + material.quantity)
                                .coerceAtMost(inventoryConfig.getMaxStackSize("material"))
                            materials = materials.map {
                                if (it.id == existing.id) it.copy(quantity = newQty) else it
                            }
                        } else {
                            materials = materials + material
                        }
                    }
                    "beastMaterial" -> {
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
                            val existing = materials.find {
                                it.name == mat.name && it.rarity == mat.rarity && it.category == mat.category
                            }
                            if (existing != null) {
                                val newQty = (existing.quantity + mat.quantity)
                                    .coerceAtMost(inventoryConfig.getMaxStackSize("material"))
                                materials = materials.map {
                                    if (it.id == existing.id) it.copy(quantity = newQty) else it
                                }
                            } else {
                                materials = materials + mat
                            }
                        }
                    }
                    "herb" -> {
                        val qty = attachment.quantity.coerceAtLeast(1)
                        val herbTemplate = HerbDatabase.generateRandomHerb(
                            minRarity = attachment.rarity,
                            maxRarity = attachment.rarity
                        )
                        val herb = Herb(
                            id = java.util.UUID.randomUUID().toString(),
                            name = herbTemplate.name,
                            rarity = herbTemplate.rarity,
                            description = herbTemplate.description,
                            category = herbTemplate.category,
                            quantity = qty
                        )
                        val existing = herbs.find {
                            it.name == herb.name && it.rarity == herb.rarity && it.category == herb.category
                        }
                        if (existing != null) {
                            val newQty = (existing.quantity + herb.quantity)
                                .coerceAtMost(inventoryConfig.getMaxStackSize("herb"))
                            herbs = herbs.map { if (it.id == existing.id) it.copy(quantity = newQty) else it }
                        } else {
                            herbs = herbs + herb
                        }
                    }
                    "seed" -> {
                        val qty = attachment.quantity.coerceAtLeast(1)
                        val seedTemplate = HerbDatabase.generateRandomSeed(
                            minRarity = attachment.rarity,
                            maxRarity = attachment.rarity
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
                        val existing = seeds.find {
                            it.name == seed.name && it.rarity == seed.rarity && it.growTime == seed.growTime
                        }
                        if (existing != null) {
                            val newQty = (existing.quantity + seed.quantity)
                                .coerceAtMost(inventoryConfig.getMaxStackSize("seed"))
                            seeds = seeds.map { if (it.id == existing.id) it.copy(quantity = newQty) else it }
                        } else {
                            seeds = seeds + seed
                        }
                    }
                    "disciple" -> {
                        val currentMonthValue = gameData.gameYear * 12 + gameData.gameMonth
                        val usedNames = disciples.map { it.name }.toMutableSet()
                        repeat(attachment.quantity.coerceAtLeast(1)) {
                            val disciple = RedeemCodeManager.generateDisciple(null, usedNames)
                            disciple.usage.recruitedMonth = currentMonthValue
                            disciples = disciples + disciple
                            usedNames.add(disciple.name)
                        }
                    }
                    "storageBag" -> {
                        val qty = attachment.quantity.coerceAtLeast(1)
                        val rarity = attachment.rarity.coerceIn(1, 6)
                        val bagName = StorageBag.TIER_NAMES.getOrElse(rarity - 1) { "凡品储物袋" }
                        val existing = storageBags.find { it.rarity == rarity }
                        if (existing != null) {
                            val newQty = (existing.quantity + qty)
                                .coerceAtMost(inventoryConfig.getMaxStackSize("storageBag"))
                            storageBags = storageBags.map { if (it.id == existing.id) it.copy(quantity = newQty) else it }
                        } else {
                            storageBags = storageBags + StorageBag(
                                id = java.util.UUID.randomUUID().toString(),
                                name = bagName,
                                rarity = rarity,
                                quantity = qty
                            )
                        }
                    }
                }
            }
        }
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

    suspend fun markAsRead(mailId: String) {
        val mail = mailRepo.getById(mailId) ?: return
        if (!mail.isRead) {
            mailRepo.update(mail.copy(isRead = true))
        }
    }

    suspend fun deleteMail(mailId: String, slotId: Int) {
        val mail = mailRepo.getById(mailId) ?: return
        if (mail.hasAttachment && !mail.attachmentClaimed) return
        mailRepo.deleteById(mailId)
    }

    suspend fun deleteAllReadAndClaimed(slotId: Int) {
        mailRepo.deleteAllReadAndClaimed(slotId)
    }

    suspend fun cleanExpired(slotId: Int) {
        val now = System.currentTimeMillis()
        mailRepo.deleteExpired(slotId, now)
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
                val claimedIds = stateStore.gameData.value.claimedMailIds
                if (claimedIds.isNotEmpty()) {
                    val now = System.currentTimeMillis()
                    val mails = mailRepo.getActiveMails(slotId, now).first()
                    mails.filter { it.id in claimedIds }.forEach { mail ->
                        mailRepo.update(mail.copy(attachmentClaimed = true, isRead = true))
                    }
                }
                DomainLog.i(TAG, "resetAndInitSlot DONE for slot $slotId")
                refreshActiveMails(slotId)
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
