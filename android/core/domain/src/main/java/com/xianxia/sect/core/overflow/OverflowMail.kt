package com.xianxia.sect.core.overflow


/**
 * 溢出邮件草稿：由 [InventorySystem 各 addXxx] 的 Partial 分支产生，
 * 描述"仓库容量不足未入仓"的物品，供实现方组装为邮件通知玩家。
 *
 * @param slotId 存档槽位
 * @param source 物品来源（withTrackingSource 的 source 值，如 "battle"/"cave"）
 * @param itemType 物品类型（与 MailAttachment.type 对齐：equipment/manual/pill/
 *   material/herb/seed/storageBag）
 * @param itemName 物品名称
 * @param rarity 稀有度
 * @param quantity 溢出数量（未入仓数量）
 */
data class OverflowMailDraft(
    val slotId: Int,
    val source: String,
    val itemType: String,
    val itemName: String,
    val rarity: Int,
    val quantity: Int
)

/**
 * 持久化溢出草稿（D-01 事务化根治）：与 [OverflowMailDraft] 语义相同，
 * 但带主键与创建时间——对应 Room 表 `overflow_mail_drafts` 的行。
 *
 * 存在性不变量：**DB 中存在的草稿行 ⇒ 其来源事务已提交**（提交钩子落盘、
 * 回滚钩子丢弃，见 GameStateStore.TransactionObserver）。
 *
 * @param id 主键（UUID）
 * @param createdAt 入队时毫秒时间戳（drain 时排序用）
 */
data class PersistedOverflowDraft(
    val id: String,
    val slotId: Int,
    val source: String,
    val itemType: String,
    val itemName: String,
    val rarity: Int,
    val quantity: Int,
    val createdAt: Long
)

/**
 * 持久化直发草稿（D-01 事务化根治）：直接邮件（非溢出）的持久化行，
 * 对应 Room 表 `direct_mail_drafts`。payload 为 [com.xianxia.sect.core.model.MailEntity]
 * 的序列化文本（JSON），id 即邮件 id（天然幂等）。
 *
 * @param id 主键（= 邮件 id）
 * @param payload [com.xianxia.sect.core.model.MailEntity] 序列化文本
 * @param createdAt 入队时毫秒时间戳
 */
data class PersistedDirectMailDraft(
    val id: String,
    val slotId: Int,
    val payload: String,
    val createdAt: Long
)

/**
 * 溢出邮件处理接口。
 *
 * [InventorySystem] 构造注入本接口，实现方（engine 层）负责把草稿转为邮件写入
 * 玩家邮箱。**接口放 domain、实现不得依赖 InventorySystem**——避免
 * InventorySystem → 实现 → MailService → InventorySystem 循环依赖。
 */
interface OverflowMailHandler {

    /**
     * 发送溢出邮件草稿。
     *
     * 实现方应：按 (slotId, source) 分组合并为少量邮件、异步写入（不得在
     * stateStore.update 事务内执行 suspend/Room 操作）、并在发送后通过
     * 容量通知通道提示玩家。
     *
     * @param drafts 溢出草稿列表（可能包含多件物品，同一来源合并为一封邮件）
     */
    fun sendOverflowMails(drafts: List<OverflowMailDraft>)

    /**
     * 排空**持久化**草稿：把 DB 中遗留的草稿行（提交钩子已落盘但 drain
     * 前进程死亡的产物）转为邮件，并在同一事务内删除草稿行。
     *
     * 实现方应幂等（同组草稿重放生成同 id 邮件，不产生重复邮件），
     * 失败组保留行待下次重试。由宿主在启动/重启时调用（崩溃恢复）。
     */
    fun drainPersistedDrafts() {}
}

/** 无操作实现：测试或未接入时的默认值（不发送邮件，仅静默丢弃——由调用方日志兜底） */
object NoOpOverflowMailHandler : OverflowMailHandler {
    override fun sendOverflowMails(drafts: List<OverflowMailDraft>) {
        // 无操作：测试环境默认不接入邮件系统
    }
}
