package com.xianxia.sect.core.engine.domain.inventory

import com.xianxia.sect.core.engine.GameEngineCore
import com.xianxia.sect.core.engine.InventoryNativeForward
import com.xianxia.sect.core.engine.system.InventorySystem
import com.xianxia.sect.core.nativebridge.ActionIds
import com.xianxia.sect.core.nativebridge.GameEngineNativeOps
import com.xianxia.sect.core.nativebridge.GameEngineNativeOps.long
import com.xianxia.sect.core.nativebridge.GameEngineNativeOps.params
import com.xianxia.sect.core.nativebridge.GameEngineNativeOps.str
import com.xianxia.sect.core.nativebridge.NativeEngineFlag
import com.xianxia.sect.core.nativebridge.StateSyncService
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/**
 * 商人购买 native 结果（batch-11——奖励卡片所需字段随信封回传，卡片构造
 * 留 Kotlin 与 S6 战报同口径；bought=false = 判定链静默拒绝，不产卡片）。
 */
internal data class MerchantBuyResult(
    val bought: Boolean,
    val itemName: String = "",
    val itemType: String = "",
    val rarity: Int = 0,
    val quantity: Int = 0
)

/**
 * 库存出售/上架域 native 事务转发臂（W2-a 写者下沉；BuildingNativeTx 同构）。
 *
 * AUTHORITATIVE 门控下把出售族五操作的 C++ 事务（inventory_tx.h——存在性/
 * 锁定/数量守卫 + 取价入账 + 堆叠扣减 + 商人收购项回写/上架登记）经
 * nativeExecute 转发；成功内含 applyDirtyFromNative 脏段回读（装备/功法/
 * 丹药/材料/草药/种子六集合 + gameData 的灵石/年度报告/商人条目镜像域，
 * 无 Room 回写）；失败信封/降级返回 null（调用方回退 Kotlin 原路径重执行
 * 校验链——双实现并行契约，用户可见文案由 Kotlin 臂产出）。
 *
 * 取价口径与 Kotlin 等价（见 inventory_tx.h 头注释）：装备模板价优先回退
 * 品阶基准价、功法/材料/草药/种子品阶基准价、丹药含品级倍率——不传参，
 * C++ 侧与 Kotlin GameConfig.Rarity / EquipmentDatabase 同源生成。
 */
internal class InventoryNativeTx(
    private val gameEngineCore: GameEngineCore
) {

    /**
     * 单类出售事务。
     * @return native 已处理时返回成交与否；未转发（门控关/降级）返回 null
     */
    fun sellItem(itemType: String, itemId: String, quantity: Int): Boolean? {
        val data = tx(ActionIds.INV_SELL_ITEM) {
            put("itemType", itemType)
            put("itemId", itemId)
            put("quantity", quantity)
        } ?: return null
        return data.str("sold") == "true"
    }

    /** 批量出售事务。@return native 已处理时返回结果；未转发返回 null */
    fun bulkSell(
        operations: List<InventoryFacade.BulkSellOperation>
    ): InventoryFacade.BulkSellResult? {
        val ops = operations.map { op ->
            buildJsonObject {
                put("id", op.id)
                put("name", op.name)
                put("itemType", op.itemType)
                put("quantity", op.quantity)
            }
        }
        val data = tx(ActionIds.INV_BULK_SELL) { put("operations", JsonArray(ops)) } ?: return null
        return InventoryFacade.BulkSellResult(
            soldCount = (data.long("soldCount") ?: 0L).toInt(),
            totalEarned = data.long("totalEarned") ?: 0L,
            soldItemNames = stringList(data, "soldItemNames"),
            failedItemNames = stringList(data, "failedItemNames")
        )
    }

    /** 商人收购事务。@return native 已处理返回 true；未转发返回 null */
    fun sellToMerchant(acquisitionItemId: String, quantity: Int): Boolean? =
        tx(ActionIds.MERCHANT_SELL_ACQUISITION) {
            put("acquisitionItemId", acquisitionItemId)
            put("quantity", quantity)
        }?.let { true }

    /** 玩家上架事务。@return native 已处理返回 true；未转发返回 null */
    fun listItemsToMerchant(items: List<Pair<String, Int>>): Boolean? {
        val entries = items.map { (itemId, quantity) ->
            buildJsonObject {
                put("itemId", itemId)
                put("quantity", quantity)
            }
        }
        return tx(ActionIds.MERCHANT_LIST_ITEMS) { put("items", JsonArray(entries)) }?.let { true }
    }

    /** 撤下上架项事务。@return native 已处理返回 true；未转发返回 null */
    fun removePlayerListedItem(itemId: String): Boolean? =
        tx(ActionIds.MERCHANT_REMOVE_LISTED) { put("itemId", itemId) }?.let { true }

    /** 按名称+品阶消耗材料。@return native 已处理返回是否恰好扣满；未转发返回 null */
    fun consumeMaterialByName(name: String, rarity: Int, quantity: Int): Boolean? {
        val data = tx(ActionIds.INV_CONSUME_MATERIAL) {
            put("name", name)
            put("rarity", rarity)
            put("quantity", quantity)
        } ?: return null
        return data.str("consumed") == "true"
    }

    /**
     * 商人购买事务（batch-11 库存收官）。
     * 判定链静默拒绝以 bought=false 回传（Kotlin 臂同语义零写入，不产卡片）；
     * 溢出草稿（Partial 溢出转邮件）经 [InventoryNativeForward.deliverDraft]
     * 同一投递通道落库。@return native 已处理返回结果；未转发返回 null
     */
    fun buyMerchantItem(
        itemId: String,
        quantity: Int,
        inventorySystem: InventorySystem
    ): MerchantBuyResult? {
        val data = tx(ActionIds.INV_BUY_MERCHANT_ITEM) {
            put("itemId", itemId)
            put("quantity", quantity)
        } ?: return null
        deliverOverflowDrafts(inventorySystem, data)
        if (data.str("bought") != "true") return MerchantBuyResult(bought = false)
        return MerchantBuyResult(
            bought = true,
            itemName = data.str("itemName") ?: "",
            itemType = data.str("itemType") ?: "",
            rarity = (data.long("rarity") ?: 0L).toInt(),
            quantity = (data.long("quantity") ?: 0L).toInt()
        )
    }

    /**
     * 充公事务（batch-11 库存收官）。幂等探测以袋内当前条目为准（C++ 侧），
     * 幂等 no-op/数量非法/模板缺失/Partial/Failure 均以 confiscated=false
     * 回传（袋条目保留）。@return native 已处理返回是否移除袋条目；
     * 未转发返回 null
     */
    fun confiscateStorageBagItem(discipleId: String, itemId: String): Boolean? {
        val data = tx(ActionIds.INV_CONFISCATE_BAG_ITEM) {
            put("discipleId", discipleId)
            put("itemId", itemId)
        } ?: return null
        return data.str("confiscated") == "true"
    }

    /** 溢出草稿投递（信封 overflowDrafts → InventoryNativeForward 同一解析/投递通道）。 */
    private fun deliverOverflowDrafts(system: InventorySystem, data: JsonElement) {
        val drafts = (data as? JsonObject)?.get("overflowDrafts") as? JsonArray ?: return
        for (draft in drafts) {
            (draft as? JsonObject)?.let { InventoryNativeForward.deliverDraft(system, it) }
        }
    }

    // ── 内部 ────────────────────────────────────────────────────

    /** native 事务转发：AUTHORITATIVE 门控 + tryExecuteNative；失败信封/降级返回 null。 */
    private fun tx(actionId: Int, build: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit) =
        if (!NativeEngineFlag.authoritative) {
            null
        } else {
            // 防御性空安全：生产恒非空，但测试 mock（未 stub stateSyncServiceRef）
            // 返回 null——非空声明的内在检查会在调用点即抛 NPE，须先经可空局部过滤
            val sync: StateSyncService? = gameEngineCore.stateSyncServiceRef
            if (sync == null) {
                null
            } else {
                GameEngineNativeOps.tryExecuteNative(
                    stateSyncService = sync,
                    actionId = actionId,
                    paramsJson = params(build)
                )
            }
        }

    /** JSON 字符串数组字段读取（缺字段/非数组按空列表）。 */
    private fun stringList(data: JsonElement, key: String): List<String> =
        ((data as? JsonObject)?.get(key) as? JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            ?: emptyList()
}
