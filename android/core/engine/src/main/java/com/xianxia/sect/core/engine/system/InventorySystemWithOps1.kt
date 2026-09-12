package com.xianxia.sect.core.engine.system

import com.xianxia.sect.core.util.DomainLog
import com.xianxia.sect.core.util.DomainResult
import com.xianxia.sect.core.model.EquipmentInstance
import com.xianxia.sect.core.model.EquipmentStack
import com.xianxia.sect.core.model.HasId
import com.xianxia.sect.core.util.StackableItem
import com.xianxia.sect.core.model.ManualInstance
import com.xianxia.sect.core.model.ManualStack
import com.xianxia.sect.core.util.AppError
import com.xianxia.sect.core.state.MutableGameState
import com.xianxia.sect.core.state.EntityStore
import com.xianxia.sect.core.state.StackKey
import com.xianxia.sect.core.state.StackKeys

/**
 * 单用户定向补偿邮件（MailService 扩展，独立文件）。
 *
 * 拆分原因：MailService 类主体接近 detekt LargeClass（800 行）阈值，
 * 补偿邮件属独立运营配置，放独立文件保持 MailService 规模稳定；
 * stateStore/mailRepo 已放宽为 internal 供本扩展读取（三重防护）。
 */
// ── InventorySystem 拆分域 1/7（行为零变更） ──

private val TAG = InventorySystem.TAG

/**
 * 在抑制溢出转邮件的上下文中执行 block。
 *
 * 仅用于"发放失败时整个事务回滚"的路径（如 MailService 领取：Partial 抛异常
 * 回滚，若此时已入队邮件草稿会造成"物品回滚但邮件已发"的双重发放）。
 */

/**
 * addXxx 的统一溢出收尾（事务外调用）：把未入仓部分转为邮件草稿。
 *
 * 转换规则：
 * - [DomainResult.Partial]：溢出量转邮件（发放类路径——战斗/灵田/开袋等，
 *   物品已生成无凭据可重试，不转即丢失）
 * - [DomainResult.Failure]（仓库满 Full）：全部数量转邮件（同上，
 *   零合并且无空槽时物品全部无法入仓，不转即丢失）
 * - [withOverflowMailSuppressed] 内（凭据类路径——兑换码/宗门等级/
 *   引导/邮件领取）：不转邮件，由调用方拒绝并保留凭据，玩家清理后可重试补齐
 *
 * @param itemType 与 MailAttachment.type 对齐（equipment/manual/pill/...）
 */

internal fun <T> InventorySystem.handleOverflowResult(result: DomainResult<T>, itemType: String, item: T) {
    if (overflowMailSuppressed) return
    val stackable = item as? StackableItem ?: return
    val overflowQty = when (result) {
        is DomainResult.Partial -> result.overflow
        is DomainResult.Failure -> {
            if (result.error !is AppError.Domain.Inventory.Full) return
            stackable.quantity
        }
        else -> return
    }
    sendOverflowMail(
        trackingSource, itemType, stackable.name, stackable.rarity, overflowQty,
        itemId = resolveOverflowItemId(itemType, stackable)
    )
}

/**
 * 解析溢出物品的**模板 id**（非实例 UUID），供溢出邮件领取时精确还原物品——
 * 否则领取方只能按稀有度随机生成（"回气丹"溢出邮件可能领到同稀有度的其它丹药）。
 *
 * 各类型按模板属性（名称/稀有度/品阶/分类等）反查数据库模板；未命中返回空串，
 * 领取方回退既有随机生成逻辑（仅不精确，不丢失资产）。各类型解析见
 * [resolvePillTemplateId] 等顶层私有函数（按类型拆分，单函数圈复杂度 ≤15）。
 */

internal fun InventorySystem.resolveOverflowItemId(itemType: String, item: StackableItem): String = when (itemType) {
    "pill" -> resolvePillTemplateId(item)
    "material" -> resolveMaterialTemplateId(item)
    "herb" -> resolveHerbTemplateId(item)
    "seed" -> resolveSeedTemplateId(item)
    "equipment" -> resolveEquipmentTemplateId(item)
    "manual" -> resolveManualTemplateId(item)
    else -> ""
}

/**
 * 公开溢出转邮件入口（供"state 参数直传"路径使用——如灵田收获直接操作
 * 事务缓冲 state，不走本类 addXxx 时自行处理 Partial/Failure 溢出）。
 *
 * @param source 物品来源（与 withTrackingSource 的 source 值一致）
 * @param itemType 与 MailAttachment.type 对齐
 * @param itemName 物品名称
 * @param rarity 稀有度
 * @param quantity 溢出数量（>0 才发送）
 * @param itemId 物品模板 id（精确还原用；调用方有模板时传，缺省空串）
 */

internal fun InventorySystem.validateQuantity(quantity: Int, name: String = "quantity"): Boolean {
    if (quantity <= 0) {
        DomainLog.w(TAG, "Invalid $name: $quantity, must be positive")
        return false
    }
    return true
}

internal fun InventorySystem.logWarning(msg: String) = DomainLog.w(TAG, msg)

internal fun InventorySystem.getMaxStackForType(type: String): Int = inventoryConfig.getMaxStackSize(type)

// ── StackableItemStore 统一合并键（单一事实来源见 StackKeys，根除 6 套不一致）──

/** 通用 getById */

internal fun <T : HasId> InventorySystem.getById(items: List<T>, id: String): T? = items.find { it.id == id }

/** 通用 getQuantity */

internal fun <T> InventorySystem.getQuantity(items: List<T>, id: String): Int where T : StackableItem =
    (items.find { (it as HasId).id == id })?.quantity ?: 0

internal fun InventorySystem.currentEquipmentStacks(): List<EquipmentStack> = stateStore.equipmentStacks.value

internal fun InventorySystem.currentEquipmentInstances(): List<EquipmentInstance> = stateStore.equipmentInstances.value

internal fun InventorySystem.currentManualStacks(): List<ManualStack> = stateStore.manualStacks.value

internal fun InventorySystem.currentManualInstances(): List<ManualInstance> = stateStore.manualInstances.value

/**
 * 在 MutableGameState 事务内合并分散堆叠。
 * 由 [consolidateStacks] 和 [sortWarehouse] 共用。
 *
 * 单遍合并保证终止（见 consolidate 内注释）；锁定堆叠**允许作为合并目标**
 * （吸收数量、自身 ID 与 isLocked 不变），**禁止作为合并来源**
 * （锁定堆叠绝不被删除/减少，否则锁定标记与 ID 丢失）。
 * 与 [StackableItemStore.add]（本就合并进锁定堆叠）行为一致。
 */
internal fun InventorySystem.consolidateAllStacks(state: MutableGameState) {
    /**
     * 单遍合并分散堆叠：每组以第一个未满堆叠为合并目标，顺序吸收后续**未满**堆叠。
     *
     * 终止性保证：
     * - 单遍（无 while 循环）——每堆叠最多被处理一次，必然终止；
     * - 满堆叠（quantity >= maxStack）跳过——禁止"从满堆叠抽回"，
     *   否则 ≥3 个同键堆叠且总数 > maxStack 时会在"满/半满"之间无限振荡
     *   （如 [999,543,999] ↔ [999,999,543] 死循环）。
     *
     * 锁定策略：锁定堆叠**允许作为合并目标**（吸收数量、自身 ID 与 isLocked 不变），
     * **禁止作为合并来源**（锁定堆叠绝不被删除/减少）。
     */
    fun <T> consolidate(items: EntityStore<T>, keyOf: (T) -> StackKey, maxStack: Int)
            where T : HasId, T : StackableItem {
        /** 单来源合并：返回合并后的主堆叠 id——主堆叠已满时
         * 以来源堆叠为新合并目标（吸收数量转移）；来源不合格时原样返回。 */
        fun mergeSourceStack(
            currentPrimaryId: String,
            secondary: T
        ): String {
            if (secondary.id == currentPrimaryId || secondary.isLocked ||
                secondary.quantity >= maxStack
            ) {
                return currentPrimaryId
            }
            val primary = items.get(currentPrimaryId) ?: return currentPrimaryId
            // 主堆叠已满：切换合并目标为当前堆叠
            if (primary.quantity >= maxStack) {
                return secondary.id
            }
            val space = maxStack - primary.quantity
            val transfer = minOf(space, secondary.quantity)
            @Suppress("UNCHECKED_CAST")
            items.update(currentPrimaryId) {
                (it as StackableItem).withQuantity(it.quantity + transfer) as T
            }
            if (transfer >= secondary.quantity) items.remove(secondary.id)
            else {
                @Suppress("UNCHECKED_CAST")
                items.update(secondary.id) {
                    (it as StackableItem).withQuantity(it.quantity - transfer) as T
                }
            }
            return currentPrimaryId
        }

        val groups = items.all().groupBy { keyOf(it) }
        for ((_, list) in groups) {
            // 首选第一个未满堆叠（可含锁定）作为合并目标；
            // 单条目组或全部堆叠已满（无合并目标）无需整理
            val primaryTarget = list.firstOrNull { it.quantity < maxStack }
            if (list.size <= 1 || primaryTarget == null) continue
            var primaryId = primaryTarget.id
            for (secondary in list.drop(1)) {
                primaryId = mergeSourceStack(primaryId, secondary)
            }
        }
    }
    val maxEq = getMaxStackForType("equipment_stack")
    val maxMn = getMaxStackForType("manual_stack")
    val maxPill = getMaxStackForType("pill")
    val maxMat = getMaxStackForType("material")
    val maxHerb = getMaxStackForType("herb")
    val maxSeed = getMaxStackForType("seed")
    val maxBag = getMaxStackForType("storageBag")
    consolidate(state.equipmentStacks, StackKeys::equipment, maxEq)
    consolidate(state.manualStacks, StackKeys::manual, maxMn)
    consolidate(state.pills, StackKeys::pill, maxPill)
    consolidate(state.materials, StackKeys::material, maxMat)
    consolidate(state.herbs, StackKeys::herb, maxHerb)
    consolidate(state.seeds, StackKeys::seed, maxSeed)
    consolidate(state.storageBags, StackKeys::storageBag, maxBag)
}
