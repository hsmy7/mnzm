package com.xianxia.sect.core.engine.service

import com.xianxia.sect.core.util.ItemNames

import com.xianxia.sect.core.AdFreeWhitelist
import com.xianxia.sect.core.engine.annotation.GameService
import com.xianxia.sect.core.util.DomainLog
import com.xianxia.sect.core.engine.config.GameConfigProvider
import com.xianxia.sect.core.engine.rebaselineNativeMirror
import com.xianxia.sect.core.engine.system.SystemWallClock
import com.xianxia.sect.core.engine.system.WallClock
import com.xianxia.sect.core.config.BuiltinMailConfig
import com.xianxia.sect.core.model.MailAttachment
import com.xianxia.sect.core.model.MailEntity
import com.xianxia.sect.core.model.RewardCardItem
import com.xianxia.sect.core.state.GameStateStore
import com.xianxia.sect.core.repository.MailRepository
import com.xianxia.sect.core.wallet.SpiritStoneWallet
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
    /** 仓库容量不足：奖励未发放、领取记录未写入，清理后可重新领取 */
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
    // stateStore/mailRepo 为 internal：MailAttachmentDistributeOps.kt 扩展
    // 需要读取存档领取记录与 Room 邮件存在性（三重防护），同一模块内可见即可
    internal val mailRepo: MailRepository,
    internal val stateStore: GameStateStore,
    internal val spiritStoneWallet: SpiritStoneWallet,
    private val scopeProvider: com.xianxia.sect.core.util.CoroutineScopeProvider,
    /**
     * RNG 分区管理器（AUTHORITATIVE 委托通道挂载点；Hilt 单例与
     * GameEngine/存档链路同实例）。默认新建仅供测试直构——生产由 Hilt
     * 注入全局单例。
     */
    internal val gameRngManager: com.xianxia.sect.core.util.GameRngManager,
    internal val gameConfigProvider: GameConfigProvider,
    internal val inventorySystem: com.xianxia.sect.core.engine.system.InventorySystem,
    /**
     * C++ 引擎核心（w3-13 通道关闭配套 §2.80：附件领取写面——钱包/年度账/mailRecords
     * 已关闭回导——领取后经 [com.xianxia.sect.core.engine.rebaselineNativeMirror] 全量
     * 重建基线回导 C++）。默认 null 仅供测试直构。
     *
     * 经 [Provider] 注入（DiplomacyService 同款）：MailService 在 GameEngineCore 构造
     * 链下游，直接注入会成 Dagger 环；Provider 为惰性破环边。
     */
    private val gameEngineCoreProvider: javax.inject.Provider<com.xianxia.sect.core.engine.GameEngineCore>? = null,
    /**
     * 游戏语义墙钟（SR-5）：邮件 30 天有效期的唯一取时入口（生效判定、过期删除、
     * 发信时间戳共用）。默认 [SystemWallClock] 仅供测试直构——生产由 Hilt 注入
     * [com.xianxia.sect.core.engine.system.CalibratedWallClock]。
     *
     * 取代收敛前的 `@VisibleForTesting var timeSource: () -> Long`：该缝实测**无任何**
     * 写入者（KDoc 声称的 MailServiceTest PINNED_NOW_MS 在仓库内不存在），
     * 按 IN6 不保留无人使用的机制。internal 而非 private——扩展文件
     * `MailAttachmentDistributeOps` 的领取判据同源取时。
     */
    internal val wallClock: WallClock = SystemWallClock
) {
    /** 基线重建用引擎核心；无 Provider（测试直构）时为 null → 跳过（JVM 语义等价） */
    private val gameEngineCore: com.xianxia.sect.core.engine.GameEngineCore?
        get() = gameEngineCoreProvider?.get()

    companion object {
        internal const val TAG = "MailService"

        /**
         * mailRecords 幂等账本保留条数。
         * 与 C++ 导入侧归一常量同源：game_core.cpp normalizeLedgers
         * MAIL_RECORD_RETENTION = 500（改值须双端同步）。
         */
        internal const val MAIL_RECORD_RETENTION = 500
        /** 邮件最大数量（复用 MailService 上限语义） */
        const val MAX_MAILS_PER_SLOT = 1000
        private const val EXPIRE_DAYS = 30L
        private const val EXPIRE_MS = EXPIRE_DAYS * 24 * 60 * 60 * 1000L
        internal val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }

        // ── 白名单专属福利常量 ──
        private const val WHITELIST_BONUS_MAIL_ID = "whitelist_bonus_v1"
        /** 白名单福利灵石：1000 万 */
        private const val WHITELIST_BONUS_SPIRIT_STONES = 10_000_000
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

    internal suspend fun refreshActiveMails(slotId: Int) {
        currentSlot = slotId
        // 决策项②：打开邮件列表前先清过期邮件（Room 失效通知会让随后的
        // flow 首值即为删除后的列表）
        mailRepo.deleteExpiredMails(slotId, wallClock.currentTimeMillis())
        _activeMails.value = mailRepo.getActiveMails(slotId).first()
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
            // 决策项②：收集前先清一次过期（此后插入路径 insertWithEnforceLimit
            // 每次写入顺带清理，删除触发的 Room 失效会自动重发列表）
            mailRepo.deleteExpiredMails(slotId, wallClock.currentTimeMillis())
            mailRepo.getActiveMails(slotId).collect { mails ->
                _activeMails.value = mails
                _unreadCount.value = mails.count { !it.isRead }
            }
        }
    }

    private fun getMutex(slotId: Int): Mutex {
        return slotMutexes.getOrPut(slotId) { Mutex() }
    }

    suspend fun loadBuiltinMails(slotId: Int) {
        val now = wallClock.currentTimeMillis()
        BuiltinMailConfig.mails.forEach { builtinMail ->
            // 限时邮件：未到生效时间，暂不发放
            if (builtinMail.startMs > 0 && now < builtinMail.startMs) {
                return@forEach
            }
            // 限时邮件超过截止时间：停止发放，已存在的保留至正常过期
            if (builtinMail.deadlineMs > 0 && now > builtinMail.deadlineMs) {
                DomainLog.i(TAG, "Builtin mail ${builtinMail.id} deadline passed, skipping (now=$now, " +
                    "deadline=${builtinMail.deadlineMs})")
                return@forEach
            }
            val existingMails = mailRepo.getActiveMails(slotId).first()
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

    @Suppress("TooGenericExceptionCaught") // 防御兜底: 异常源跨IO/SDK不可枚举, 降级继续+日志留痕, 非静默吞噬
    suspend fun claimAttachment(mailId: String, slotId: Int): ClaimResult {
        return getMutex(slotId).withLock {
            val (guardFailure, mail) = findClaimableMail(mailId, slotId)
            if (guardFailure != null || mail == null) return@withLock guardFailure ?: ClaimResult.MailNotFound

            val (preFailure, attachments) = parseAndCheckAttachmentCapacity(mailId, mail.attachments)
            if (preFailure != null) return@withLock preFailure

            // 原子发放：物品入库 + 领取记录在同一 stateStore 事务中
            val (rewardCards, distributeError) = grantAttachments(
                mail = mail,
                attachments = attachments,
                slotId = slotId
            )
            if (distributeError != null) return@withLock distributeError
            // w3-13 通道关闭配套（§2.80）：发放事务为非捕获（updateMirror）——领取
            // 写面经基线重建回导 C++（低频用户动作，O(状态) 一次性成本可接受）
            gameEngineCore?.rebaselineNativeMirror("邮件附件领取")

            // Room DB 更新失败不影响领取结果（物品已安全入库 + mailRecord 已写入），
            // 仅记录日志；mailRecords 二次保护防止重复领取
            try {
                mailRepo.update(mail.copy(attachmentClaimed = true, isRead = true))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                DomainLog.e(TAG, "Failed to mark mail $mailId as claimed in DB: ${e.message}", e)
            }
            refreshActiveMails(slotId)
            ClaimResult.Success(attachments, rewardCards)
        }
    }

    suspend fun markAllAsRead(slotId: Int): MarkAllReadResult {
        return getMutex(slotId).withLock {
            val now = wallClock.currentTimeMillis()
            val mails = mailRepo.getActiveMails(slotId).first()

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
            // w3-13 通道关闭配套（§2.80）：发放事务为非捕获（updateMirror）——发生
            // 领取即基线重建回导 C++（与单个领取同口径）
            if (claimedCount > 0) gameEngineCore?.rebaselineNativeMirror("邮件一键领取")
            MarkAllReadResult(claimedCount, skippedCount, skipReasons, allCards)
        }
    }

    suspend fun markAsRead(mailId: String, slotId: Int) {
        val mail = mailRepo.getById(slotId, mailId) ?: return
        if (!mail.isRead) {
            mailRepo.update(mail.copy(isRead = true))
        }
    }

    /**
     * 插入外部邮件（如运营补偿）并刷新活跃邮件缓存，确保 [activeMails] 立即反映最新数据。
     */
    suspend fun insertMail(mail: MailEntity) {
        mailRepo.insertWithEnforceLimit(mail, MAX_MAILS_PER_SLOT)
        refreshActiveMails(mail.slotId)
    }

    fun getActiveMails(slotId: Int): Flow<List<MailEntity>> {
        return mailRepo.getActiveMails(slotId)
    }

    fun getUnreadCount(slotId: Int): Flow<Int> {
        return mailRepo.getUnreadCount(slotId)
    }

    /**
     * 重置并初始化指定存档的邮件（加载内置邮件，恢复已领取状态）。
     * 用于新游戏/读档/重开场景。
     *
     * **不删除任何已有邮件**——邮件永久保留，仅玩家手动"删除已读"清理；
     * 内置邮件的发放是幂等插入（已存在则跳过），溢出/直发邮件继续保留，
     * 未领取附件绝不因读档/切档/重开而丢失。
     */
    @Suppress("TooGenericExceptionCaught") // 防御兜底: 异常源跨IO/SDK不可枚举, 降级继续+日志留痕, 非静默吞噬
    suspend fun resetAndInitSlot(slotId: Int) {
        getMutex(slotId).withLock {
            DomainLog.i(TAG, "resetAndInitSlot for slot $slotId")
            try {
                loadBuiltinMails(slotId)
                // 根据存档数据恢复已领取状态
                val claimedIds = stateStore.gameData.value.mailRecords.map { it.mailId }.toSet()
                if (claimedIds.isNotEmpty()) {
                    val mails = mailRepo.getActiveMails(slotId).first()
                    mails.filter { it.id in claimedIds }.forEach { mail ->
                        mailRepo.update(mail.copy(attachmentClaimed = true, isRead = true))
                    }
                }
                DomainLog.i(TAG, "resetAndInitSlot DONE for slot $slotId")
                refreshActiveMails(slotId)
                // Room flow 持续收集：溢出邮件等外部写入立即可见
                startMailFlowCollector(slotId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                DomainLog.e(TAG, "Error in resetAndInitSlot for slot $slotId", e)
            }
        }
    }

    /**
     * 注入白名单用户专属福利邮件（永久有效，每个存档仅可领取一次）。
     *
     * 保护机制：
     * 1. 白名单检查 — 仅 [AdFreeWhitelist.isCurrentUserPrivileged] 可注入
     * 2. mailRecords 已领取检查 — 每个存档仅可领取一次
     * 3. 重复注入检查 — 邮件已存在 DB 中则跳过
     *
     * 注意：永久邮件 expireTime 必须为 [Long.MAX_VALUE]——claimAttachment 与
     * 邮件列表查询按 `expireTime <= now` 判过期，expireTime=0 会判为已过期。
     *
     * @param slotId 目标存档槽位
     * @return true=成功注入, false=跳过
     */
    // 三道独立保护守卫均为提前返回（白名单/已领取/已存在），守卫式出口为惯用法
    // 防御兜底: 异常源跨IO/SDK不可枚举, 降级继续+日志留痕, 非静默吞噬
    @Suppress("TooGenericExceptionCaught", "ReturnCount")
    suspend fun injectWhitelistBonus(slotId: Int): Boolean {
        // 保护1：白名单检查
        if (!AdFreeWhitelist.isCurrentUserPrivileged()) {
            DomainLog.i(TAG, "非白名单用户，跳过白名单福利注入")
            return false
        }

        val snapshot = stateStore.gameData.value

        // 保护2：mailRecords 已领取检查 — 每个存档仅可领取一次
        if (snapshot.mailRecords.any { it.mailId == WHITELIST_BONUS_MAIL_ID }) {
            DomainLog.i(TAG, "白名单福利已领取，跳过注入")
            return false
        }

        // 保护3：重复注入检查 — 邮件已存在 DB 中则跳过
        val existing = try {
            mailRepo.getById(slotId, WHITELIST_BONUS_MAIL_ID)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            DomainLog.e(TAG, "检查白名单福利邮件是否存在时失败", e)
            null
        }
        if (existing != null) {
            DomainLog.i(TAG, "白名单福利邮件已存在，跳过重复注入")
            return false
        }

        val attachments = listOf(
            MailAttachment(
                type = "spiritStones",
                name = ItemNames.SPIRIT_STONE,
                quantity = WHITELIST_BONUS_SPIRIT_STONES
            )
        )

        val mail = MailEntity(
            id = WHITELIST_BONUS_MAIL_ID,
            slotId = slotId,
            source = "admin",
            mailType = "reward",
            title = "白名单专属福利",
            content = "尊敬的修士，感谢您的长期支持！特赠白名单专属福利：灵石 ×10,000,000，" +
                "永久有效，每档仅可领取一次。\n\n——天道意志",
            senderName = "天道意志",
            sendTime = wallClock.currentTimeMillis(),
            expireTime = Long.MAX_VALUE,
            hasAttachment = true,
            attachments = json.encodeToString(
                serializer<List<MailAttachment>>(),
                attachments
            )
        )

        return try {
            insertMail(mail)
            DomainLog.i(TAG, "白名单福利已注入到 slot=$slotId")
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // 注入失败不应阻塞游戏启动（boot 已成功），下次启动自动重试
            DomainLog.e(TAG, "白名单福利邮件插入失败 slot=$slotId", e)
            false
        }
    }

}
