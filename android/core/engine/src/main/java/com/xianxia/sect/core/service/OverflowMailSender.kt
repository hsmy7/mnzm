package com.xianxia.sect.core.engine.service

import com.xianxia.sect.core.engine.annotation.GameService
import com.xianxia.sect.core.model.MailAttachment
import com.xianxia.sect.core.model.MailEntity
import com.xianxia.sect.core.overflow.OverflowMailDraft
import com.xianxia.sect.core.overflow.OverflowMailHandler
import com.xianxia.sect.core.repository.MailRepository
import com.xianxia.sect.core.state.GameStateStore
import com.xianxia.sect.core.state.GameStateStore.TransactionObserver
import com.xianxia.sect.core.util.CoroutineScopeProvider
import com.xianxia.sect.core.util.DomainLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.SerializationException
import kotlinx.serialization.serializer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import javax.inject.Inject
import javax.inject.Singleton
import com.xianxia.sect.core.overflow.PersistedDirectMailDraft
import com.xianxia.sect.core.overflow.PersistedOverflowDraft


/**
 * 溢出邮件发送器——仓库容量不足时，把未入仓物品转为邮件通知玩家。
 *
 * ## 解耦设计
 * 实现 [OverflowMailHandler] 接口（domain 定义），由 [InventorySystem] 注入调用；
 * 本类**不依赖 InventorySystem**，避免 InventorySystem → MailService → InventorySystem
 * 循环依赖。邮件直写 Room（MailRepository），MailService 通过 Room flow 收集器
 * 让 UI 立即可见新邮件。
 *
 * ## D-01 事务化根治（草稿入队即持久化 + 事务世代号）
 * 两支柱：
 * 1. **事务内入 staging、提交钩子同步落盘**——[sendOverflowMails] 读取
 *    [GameStateStore.currentTransactionGeneration]：事务内（gen>0）草稿入内存 staging
 *    并打上世代号；构造时注册自身为 [TransactionObserver]，事务提交回调中把该世代
 *    staging 转 [PersistedOverflowDraft] 阻塞落盘到 Room 表，回滚回调直接丢弃。
 *    落盘发生在事务锁外（observer 回调语义），状态提交不受影响。
 * 2. **drain 以 DB 草稿为准**——[drainPersistedDrafts]（崩溃恢复，启动时调用）与
 *    防抖 drain 均读取 Room 草稿表构建邮件，行删除与邮件写入同一原子事务。
 *
 * 核心不变量：**DB 中存在的草稿行 ⇒ 其来源事务已提交**——回滚/崩溃都不会
 * 出现"邮件已发但物品未扣"的复制，也不会"物品已扣但邮件没发"的丢失
 * （草稿先落盘，drain 前进程死亡由下次启动恢复）。
 *
 * 落盘失败（瞬时 I/O 异常）的批入 unpublished 队列由 drain 补落盘——宁可延迟
 * 发送不丢资产；DB 草稿行消费失败保留行，下次 drain 重试（幂等 mailId）。
 *
 * ## 防抖批组
 * 草稿入队后 300ms 防抖单飞 drain：同一 (slotId, source) 的草稿合并为一封邮件
 * （附件列表），一次战斗的多个溢出物品 = 1 封邮件，避免邮件轰炸。
 *
 * ## 幂等
 * 邮件 id = `UUID.nameUUIDFromBytes("overflow:$slotId:$source:${draftIds.sorted()}")`
 * ——同组草稿跨进程重放生成同 id，无重复邮件（REPLACE 覆盖）。
 */
@GameService("OverflowMailSender")
@Singleton
class OverflowMailSender @Inject constructor(
    private val mailRepo: MailRepository,
    private val stateStore: GameStateStore,
    private val scopeProvider: CoroutineScopeProvider,
) : OverflowMailHandler, TransactionObserver {

    companion object {
        private const val TAG = "OverflowMailSender"

        /** 防抖窗口：合并同源溢出为单封邮件 */
        private const val DEBOUNCE_MS = 300L

        /** 邮件最大数量（复用 MailService 上限语义） */
        private const val MAX_MAILS_PER_SLOT = 1000

        /**
         * 溢出邮件有效期（天）。
         * 对抗性审查 M4 修复：溢出物品是玩家已获得的资产（部分路径已付灵石），
         * 不设 30 天过期——设为 10 年（过期删除仅清理真正的过期邮件，不吞资产）。
         */
        private const val MAIL_EXPIRE_DAYS = 3650L

        private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }

        /** 来源名映射表——新增 withTrackingSource 来源时必须同步添加（守卫测试强制） */
        val SOURCE_DISPLAY_NAMES: Map<String, String> = mapOf(
            "battle" to "宗门战",
            "beast_world" to "妖兽战",
            "cave_world" to "洞穴战",
            "cave" to "洞府探索",
            "beast_raid" to "妖兽侵袭",
            "patrol" to "巡视塔",
            "forge" to "锻造",
            "alchemy" to "炼丹",
            "spirit_field" to "灵田",
            "storage_bag" to "储物袋",
            "merchant" to "商人",
            "redeem" to "兑换码",
            "mail" to "邮件",
            "disciple_reward" to "弟子奖励",
            "disciple_unequip" to "弟子卸装",
            "trial" to "天道试炼",
            "sect_level" to "宗门等级",
            "sect_trade" to "宗门贸易",
            "quest" to "任务",
            "building" to "建筑",
            "confiscate" to "储物袋回收",
            "secret_realm" to "远古秘境",
            "disciple_death" to "弟子陨落归还",
            "disciple_expel" to "弟子逐出归还",
            "unknown" to "未知"
        )

        /** 来源名解析（未知来源降级为"未知"） */
        fun sourceDisplayName(source: String): String =
            SOURCE_DISPLAY_NAMES[source] ?: "未知"

        /**
         * 确定性邮件 id：同组草稿（按 id 排序）跨进程重放生成同 id —— 幂等，
         * 配合 mails 表 REPLACE 不产生重复邮件。
         */
        internal fun deterministicOverflowMailId(slotId: Int, source: String, draftIds: List<String>): String =
            java.util.UUID.nameUUIDFromBytes(
                "overflow:$slotId:$source:${draftIds.sorted().joinToString(",")}".toByteArray()
            ).toString()
    }

    /** 事务内 staging：世代号 → 该事务入队的溢出草稿（提交钩子转落盘，回滚丢弃） */
    private val stagingDrafts = ConcurrentHashMap<Long, MutableList<OverflowMailDraft>>()

    /** 事务内 staging：世代号 → 该事务入队的直发邮件（同上语义） */
    private val stagingDirectMails = ConcurrentHashMap<Long, MutableList<MailEntity>>()

    /** 落盘失败队列（瞬时 I/O 失败）：drain 时补落盘——宁可延迟发送不丢资产 */
    private val unpublishedOverflowDrafts = ConcurrentLinkedQueue<PersistedOverflowDraft>()

    /** 落盘失败队列（直发） */
    private val unpublishedDirectMails = ConcurrentLinkedQueue<PersistedDirectMailDraft>()

    /** 单飞调度标志——@Volatile 保证跨线程可见（对抗性审查 M1 修复） */
    @Volatile
    private var drainScheduled = false

    init {
        // 注册事务观察者：提交钩子落盘该世代 staging、回滚钩子丢弃（防复制）
        stateStore.registerTransactionObserver(this)
    }

    /** 入队草稿并调度防抖 drain（引擎线程安全：addXxx 收尾与 drain 均经此单飞标志） */
    override fun sendOverflowMails(drafts: List<OverflowMailDraft>) {
        if (drafts.isEmpty()) return
        val gen = stateStore.currentTransactionGeneration
        if (gen > 0L) {
            // 事务内：入 staging，由提交/回滚钩子决定落盘或丢弃
            stagingDrafts.compute(gen) { _, existing ->
                if (existing == null) drafts.toMutableList() else existing.apply { addAll(drafts) }
            }
        } else {
            // 事务外（测试/直调路径）：立即落盘，防崩溃丢失
            persistOverflowDraftsImmediately(drafts)
        }
        scheduleDrain()
    }

    /**
     * 直发自定义邮件（如秘境关闭返还邮件）：入队 → 与溢出草稿同 drain 异步落库；
     * 事务内入 staging 提交后落盘、回滚丢弃；写入失败回 unpublished 重试（不永久丢失）。
     * 非 suspend，stateStore.update 事务内安全。
     */
    fun sendDirectMail(mail: MailEntity) {
        if (mail.id.isBlank()) return
        val gen = stateStore.currentTransactionGeneration
        if (gen > 0L) {
            stagingDirectMails.compute(gen) { _, existing ->
                if (existing == null) mutableListOf(mail) else existing.apply { add(mail) }
            }
        } else {
            val draft = PersistedDirectMailDraft(
                id = mail.id, slotId = mail.slotId,
                payload = json.encodeToString(mail), createdAt = System.currentTimeMillis()
            )
            if (!mailRepo.insertDirectMailDraftBlocking(draft)) {
                unpublishedDirectMails.add(draft)
            }
        }
        scheduleDrain()
    }

    // ═══════════════════════════════════════════════════════════════
    // TransactionObserver（D-01 事务世代号钩子）
    // ═══════════════════════════════════════════════════════════════

    override fun onTransactionCommitted(transactionGeneration: Long) {
        // 事务提交：该世代 staging 草稿 → 阻塞落盘（锁外、事务线程，禁 suspend）。
        // 落盘失败批入 unpublished，由 drain 补落盘——不影响状态提交。
        val drafts = stagingDrafts.remove(transactionGeneration)
        if (!drafts.isNullOrEmpty()) {
            persistOverflowDraftsImmediately(drafts)
        }
        val directMails = stagingDirectMails.remove(transactionGeneration)
        if (!directMails.isNullOrEmpty()) {
            for (mail in directMails) {
                val draft = PersistedDirectMailDraft(
                    id = mail.id, slotId = mail.slotId,
                    payload = json.encodeToString(mail), createdAt = System.currentTimeMillis()
                )
                if (!mailRepo.insertDirectMailDraftBlocking(draft)) {
                    unpublishedDirectMails.add(draft)
                }
            }
        }
        scheduleDrain()
    }

    override fun onTransactionRolledBack(transactionGeneration: Long) {
        // 事务回滚：该世代 staging 直接丢弃——状态未提交、物品未入账，
        // 草稿也必须不落盘（防"邮件已发但物品被回滚"的复制）
        stagingDrafts.remove(transactionGeneration)
        stagingDirectMails.remove(transactionGeneration)
    }

    // ═══════════════════════════════════════════════════════════════
    // 落盘 + drain
    // ═══════════════════════════════════════════════════════════════

    /** 立即落盘（事务外路径 / 提交钩子路径）；失败批入 unpublished 待 drain 补 */
    private fun persistOverflowDraftsImmediately(drafts: List<OverflowMailDraft>) {
        val now = System.currentTimeMillis()
        val persisted = drafts.map { d ->
            PersistedOverflowDraft(
                id = java.util.UUID.randomUUID().toString(),
                slotId = d.slotId,
                source = d.source,
                itemType = d.itemType,
                itemName = d.itemName,
                rarity = d.rarity,
                quantity = d.quantity,
                createdAt = now
            )
        }
        val written = mailRepo.insertOverflowDraftsBlocking(persisted)
        if (written < persisted.size) {
            // 部分/全部失败 → 失败的入 unpublished（REPLACE 幂等，重复落盘无害）
            unpublishedOverflowDrafts.addAll(persisted.drop(written))
        }
    }

    /** 调度防抖 drain（单飞：drainScheduled 保证同时最多一个 drain 在途） */
    private fun scheduleDrain() {
        if (drainScheduled) return
        drainScheduled = true
        scopeProvider.scope.launch {
            try {
                delay(DEBOUNCE_MS)
                drain()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                DomainLog.e(TAG, "溢出邮件发送失败（草稿保留，下次发送重试）", e)
            } finally {
                drainScheduled = false
                // drain 期间（挂起窗口）新入队的草稿 → 再次调度，保持"一次战斗一封"的批组合并
                if (hasPendingWork()) scheduleDrain()
            }
        }
    }

    private fun hasPendingWork(): Boolean =
        stagingDrafts.isNotEmpty() || stagingDirectMails.isNotEmpty() ||
            unpublishedOverflowDrafts.isNotEmpty() || unpublishedDirectMails.isNotEmpty()

    /**
     * 崩溃恢复入口：启动/重启时调用（幂等——drain 读 DB 全量草稿，无草稿即空转）。
     * 非挂起：内部经 scope 异步执行，不阻塞启动路径。
     */
    override fun drainPersistedDrafts() {
        scheduleDrain()
    }

    /** 排空：补落盘 unpublished → 读 DB 草稿 → 分组构建邮件 → 每组合一事务写 mails+删行 */
    private suspend fun drain() {
        persistUnpublished()

        val persistedOverflow = mailRepo.getPersistedOverflowDraftsBlocking()
        val persistedDirect = mailRepo.getPersistedDirectMailDraftsBlocking()
        if (persistedOverflow.isEmpty() && persistedDirect.isEmpty()) return

        val anyWritten = drainPersistedOverflowDrafts(persistedOverflow, System.currentTimeMillis())
        drainPersistedDirectMails(persistedDirect)
        // 通知玩家（统一容量提示框由 UI 层消费）；仅在有邮件成功写入时提示
        if (anyWritten) {
            stateStore.warehouseFullEvent.tryEmit("仓库容量不足，部分奖励已转入邮件，请到邮件中查收")
        }
    }

    /** 补落盘 unpublished（落盘失败回队 break——防止同批死循环重试） */
    private fun persistUnpublished() {
        drainQueue(unpublishedOverflowDrafts) { d ->
            mailRepo.insertOverflowDraftsBlocking(listOf(d)) > 0
        }
        drainQueue(unpublishedDirectMails) { d ->
            mailRepo.insertDirectMailDraftBlocking(d)
        }
    }

    /** D-17 排空重试队列（persistUnpublished 拆分）：元素依次持久化，失败放回队首并停止 */
    private fun <T> drainQueue(
        queue: ConcurrentLinkedQueue<T>,
        persist: (T) -> Boolean
    ) {
        while (queue.peek() != null) {
            val d = queue.poll()
            if (!persist(d)) {
                queue.add(d)
                return
            }
        }
    }

    /** 按 (slotId, source) 分组构建溢出邮件；每组一个原子事务"写 mails + 删草稿行" */
    private suspend fun drainPersistedOverflowDrafts(
        drafts: List<PersistedOverflowDraft>,
        now: Long
    ): Boolean {
        var anyWritten = false
        val grouped = drafts.groupBy { it.slotId to it.source }
        for ((key, group) in grouped) {
            val (slotId, source) = key
            val draftIds = group.map { it.id }.sorted()
            try {
                val attachments = group.map { draft ->
                    MailAttachment(
                        type = draft.itemType,
                        name = draft.itemName,
                        quantity = draft.quantity,
                        rarity = draft.rarity
                    )
                }
                val mail = buildOverflowMail(
                    slotId, source, attachments, now,
                    mailId = deterministicOverflowMailId(slotId, source, draftIds)
                )
                // 原子：邮件写入 + 草稿行删除（崩溃只发生在事务前/后，重放不重复）
                mailRepo.insertWithEnforceLimitAndDeleteDrafts(
                    mail, MAX_MAILS_PER_SLOT, draftIds, emptyList()
                )
                anyWritten = true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // 失败组草稿行保留 DB，下次 drain 重试（幂等 mailId 保证不重复）
                DomainLog.e(TAG, "溢出邮件写入失败 slotId=$slotId source=$source（草稿行保留重试）", e)
            }
        }
        return anyWritten
    }

    /** 直发草稿逐封写入（标题/内容自定义，不与溢出邮件合并）；失败行保留重试 */
    private suspend fun drainPersistedDirectMails(drafts: List<PersistedDirectMailDraft>) {
        for (draft in drafts) {
            try {
                val mail = json.decodeFromString<MailEntity>(draft.payload)
                mailRepo.insertWithEnforceLimitAndDeleteDrafts(
                    mail, MAX_MAILS_PER_SLOT, emptyList(), listOf(draft.id)
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: SerializationException) {
                // payload 损坏（异常数据/旧格式）：邮件实体不可还原——删行防死循环重试
                DomainLog.e(TAG, "直发草稿 payload 损坏，删除防死循环 id=${draft.id}", e)
                mailRepo.deleteDirectMailDraftsBlocking(listOf(draft.id))
            } catch (e: Exception) {
                DomainLog.e(TAG, "直发草稿落库失败 id=${draft.id}（草稿行保留重试）", e)
            }
        }
    }

    /** 构建溢出邮件（标题/内容清晰说明来源与原因）——internal 供单元测试直测 */
    internal fun buildOverflowMail(
        slotId: Int,
        source: String,
        attachments: List<MailAttachment>,
        now: Long,
        mailId: String = java.util.UUID.randomUUID().toString()
    ): MailEntity {
        val sourceName = sourceDisplayName(source)
        val itemLines = attachments.joinToString("\n") {
            "• ${it.name} ×${it.quantity}"
        }
        val content = buildString {
            append("尊敬的修士：\n\n")
            append("因仓库容量不足，以下${sourceName}奖励未能存入仓库，已转入本邮件附件，请及时领取：\n\n")
            append(itemLines)
            append("\n\n（邮件自发送起 $MAIL_EXPIRE_DAYS 天内有效，逾期删除）\n——天道意志")
        }
        return MailEntity(
            id = mailId,
            slotId = slotId,
            source = "overflow",
            mailType = "overflow",
            title = "【仓库已满】${sourceName}奖励转入邮件",
            content = content,
            senderName = "天道意志",
            sendTime = now,
            expireTime = now + MAIL_EXPIRE_DAYS * 24 * 60 * 60 * 1000L,
            hasAttachment = true,
            attachments = json.encodeToString(serializer<List<MailAttachment>>(), attachments)
        )
    }
}
