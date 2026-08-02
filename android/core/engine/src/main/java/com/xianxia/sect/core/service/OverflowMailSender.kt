package com.xianxia.sect.core.engine.service

import com.xianxia.sect.core.engine.annotation.GameService
import com.xianxia.sect.core.model.MailAttachment
import com.xianxia.sect.core.model.MailEntity
import com.xianxia.sect.core.overflow.OverflowMailDraft
import com.xianxia.sect.core.overflow.OverflowMailHandler
import com.xianxia.sect.core.repository.MailRepository
import com.xianxia.sect.core.state.GameStateStore
import com.xianxia.sect.core.util.CoroutineScopeProvider
import com.xianxia.sect.core.util.DomainLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer
import java.util.concurrent.ConcurrentLinkedQueue
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 溢出邮件发送器——仓库容量不足时，把未入仓物品转为邮件通知玩家。
 *
 * ## 解耦设计
 * 实现 [OverflowMailHandler] 接口（domain 定义），由 [InventorySystem] 注入调用；
 * 本类**不依赖 InventorySystem**，避免 InventorySystem → MailService → InventorySystem
 * 循环依赖。邮件直写 Room（MailRepository），MailService 通过 Room flow 收集器
 * 让 UI 立即可见新邮件。
 *
 * ## 防抖批组
 * 溢出草稿入队后 300ms 防抖单飞 drain：同一 (slotId, source) 的草稿合并为一封邮件
 * （附件列表），一次战斗的多个溢出物品 = 1 封邮件，避免邮件轰炸。
 *
 * ## 事务安全
 * [sendOverflowMails] 非 suspend，内部 `scope.launch` 异步写入——调用方
 * （InventorySystem.addXxx 的 Partial 收尾）处于 stateStore.update 事务内时
 * 不会执行 suspend/Room 操作。
 */
@GameService("OverflowMailSender")
@Singleton
class OverflowMailSender @Inject constructor(
    private val mailRepo: MailRepository,
    private val stateStore: GameStateStore,
    private val scopeProvider: CoroutineScopeProvider,
) : OverflowMailHandler {

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
            "sign_in" to "签到",
            "trial" to "天道试炼",
            "sect_level" to "宗门等级",
            "sect_trade" to "宗门贸易",
            "quest" to "任务",
            "building" to "建筑",
            "confiscate" to "储物袋回收",
            "secret_realm" to "远古秘境",
            "unknown" to "未知"
        )

        /** 来源名解析（未知来源降级为"未知"） */
        fun sourceDisplayName(source: String): String =
            SOURCE_DISPLAY_NAMES[source] ?: "未知"
    }

    private val pendingDrafts = ConcurrentLinkedQueue<OverflowMailDraft>()

    /** 单飞调度标志——@Volatile 保证跨线程可见（对抗性审查 M1 修复） */
    @Volatile
    private var drainScheduled = false

    /** 入队草稿并调度防抖 drain（引擎线程安全：addXxx 收尾与 drain 均经此单飞标志） */
    override fun sendOverflowMails(drafts: List<OverflowMailDraft>) {
        if (drafts.isEmpty()) return
        pendingDrafts.addAll(drafts)
        scheduleDrain()
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
                if (pendingDrafts.isNotEmpty()) scheduleDrain()
            }
        }
    }

    /** 取出全部草稿，按 (slotId, source) 分组构建邮件并写入 Room */
    private suspend fun drain() {
        val drafts = mutableListOf<OverflowMailDraft>()
        while (true) {
            val draft = pendingDrafts.poll() ?: break
            drafts.add(draft)
        }
        if (drafts.isEmpty()) return

        val now = System.currentTimeMillis()
        val grouped = drafts.groupBy { it.slotId to it.source }
        var anyWritten = false
        for ((key, group) in grouped) {
            val (slotId, source) = key
            try {
                val attachments = group.map { draft ->
                    MailAttachment(
                        type = draft.itemType,
                        name = draft.itemName,
                        quantity = draft.quantity,
                        rarity = draft.rarity
                    )
                }
                val mail = buildOverflowMail(slotId, source, attachments, now)
                mailRepo.insertWithEnforceLimit(mail, MAX_MAILS_PER_SLOT)
                anyWritten = true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // 对抗性审查 MEDIUM-4 修复：写入失败 → 草稿回队，下次 drain 重试（不永久丢失）
                DomainLog.e(TAG, "溢出邮件写入失败 slotId=$slotId source=$source（草稿回队重试）", e)
                pendingDrafts.addAll(group)
            }
        }
        // 通知玩家（统一容量提示框由 UI 层消费）；仅在有邮件成功写入时提示
        if (anyWritten) {
            stateStore.warehouseFullEvent.tryEmit("仓库容量不足，部分奖励已转入邮件，请到邮件中查收")
        }
    }

    /** 构建溢出邮件（标题/内容清晰说明来源与原因）——internal 供单元测试直测 */
    internal fun buildOverflowMail(
        slotId: Int,
        source: String,
        attachments: List<MailAttachment>,
        now: Long
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
            id = java.util.UUID.randomUUID().toString(),
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
