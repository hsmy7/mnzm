package com.xianxia.sect.core.engine

import com.xianxia.sect.core.engine.system.InventorySystem
import com.xianxia.sect.core.model.EquipmentSlot
import com.xianxia.sect.core.model.EquipmentStack
import com.xianxia.sect.core.model.Herb
import com.xianxia.sect.core.model.ManualStack
import com.xianxia.sect.core.model.ManualType
import com.xianxia.sect.core.model.Material
import com.xianxia.sect.core.model.MaterialCategory
import com.xianxia.sect.core.model.Pill
import com.xianxia.sect.core.model.PillCategory
import com.xianxia.sect.core.model.PillGrade
import com.xianxia.sect.core.model.Seed
import com.xianxia.sect.core.util.StackableItem
import com.xianxia.sect.core.nativebridge.GameEngineNativeOps
import com.xianxia.sect.core.nativebridge.GameEngineNativeOps.params
import com.xianxia.sect.core.nativebridge.GameEngineNativeOps.str
import com.xianxia.sect.core.nativebridge.NativeEngineFlag
import com.xianxia.sect.core.nativebridge.StateSyncService
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import com.xianxia.sect.core.engine.system.resolveOverflowItemId

/**
 * 库存动作 native 转发。
 *
 * 契约：
 * - C++ handleInventory 的 add/remove 家族与 Kotlin InventorySystem 全语义对拍
 *   （DiffInventoryTest 同源系统函数）；remove 全等价，add 唯一缺口是溢出邮件
 *   只计数不落库——由 [deliverOverflowDrafts] 补齐：C++ 信封回传 overflowDrafts
 *   数组，Kotlin 侧重建最小模型走 [InventorySystem.resolveOverflowItemId] 同一
 *   模板 id 解析路径 + sendOverflowMail 投递（精度与 Kotlin 原路径一致）。
 * - 仅 AUTHORITATIVE 模式转发：SHADOW 以 Kotlin 为执行真相源（C++ 仅 tick 影子
 *   对拍），操作若也走 native 会造成双头执行；OFF 保持纯 Kotlin。
 * - flag 关闭 / native 不可用 / 顶层失败信封 → 返回 null，调用方回退 Kotlin 原实现。
 * - data.status=partial（仓库溢出）不是失败：C++ 状态已变更并镜像回写，调用方
 *   不得回退（回退会二次入仓复制物品）。
 */
internal object InventoryNativeForward {

    /** 尝试经 C++ 执行库存动作；成功时投递溢出邮件草稿后返回 data。 */
    @Suppress("ReturnCount")  // 多 return 为降级契约（flag 关/镜像不可用/成功路径逐级返回）
    internal fun tryForward(
        gameEngine: GameEngine,
        actionId: Int,
        paramsBuilder: JsonObjectBuilder.() -> Unit
    ): JsonElement? {
        if (!NativeEngineFlag.authoritative) return null
        // 防御性空安全：生产恒非空，但测试 mock（未 stub stateSyncServiceRef）返回 null——
        // Kotlin 非空参数的内在检查在函数入口（早于 isLoaded 早退）即抛 NPE，必须先过滤
        val sync: StateSyncService? = gameEngine.stateSyncService
        if (sync == null) return null
        return GameEngineNativeOps.tryExecuteNative(
            stateSyncService = sync,
            actionId = actionId,
            paramsJson = params(paramsBuilder)
        )?.also { data -> deliverOverflowDrafts(gameEngine, data) }
    }

    /** 溢出邮件投递（战斗死亡袋物化/背包结算草稿 → Kotlin 同一投递通道）。 */
    // ── 溢出邮件投递（C++ 草稿 → Kotlin 同一解析/投递通道）──────────────

    private fun deliverOverflowDrafts(gameEngine: GameEngine, data: JsonElement) {
        val drafts = (data as? JsonObject)?.get("overflowDrafts") as? JsonArray ?: return
        val system = gameEngine.inventorySystem
        for (draft in drafts) {
            (draft as? JsonObject)?.let { deliverDraft(system, it) }
        }
    }

    /** 投递单条草稿（字段缺失即静默跳过——溢出草稿缺失只影响邮件精度，不影响仓库状态）。 */
    @Suppress("ReturnCount")  // 逐字段早退为防御性契约（草稿字段缺失仅损失邮件精度）
    internal fun deliverDraft(system: InventorySystem, obj: JsonObject) {
        val quantity = obj["quantity"]?.jsonPrimitive?.long?.toInt() ?: return
        val itemType = obj.str("itemType") ?: return
        val itemName = obj.str("itemName") ?: return
        val item = rebuildMinimalItem(itemType, obj) ?: return
        val rarity = obj["rarity"]?.jsonPrimitive?.long?.toInt() ?: 1
        val source = obj.str("source") ?: "unknown"
        system.sendOverflowMail(
            source = source,
            itemType = itemType,
            itemName = itemName,
            rarity = rarity,
            quantity = quantity,
            itemId = system.resolveOverflowItemId(itemType, item)
        )
    }

    /** 重建模板反查所需的最小模型（resolver 仅消费名称/稀有度/分类等区分字段）。 */
    private fun rebuildMinimalItem(itemType: String, obj: JsonObject): StackableItem? {
        val name = obj.str("itemName") ?: return null
        val rarity = obj["rarity"]?.jsonPrimitive?.long?.toInt() ?: 1
        return when (itemType) {
            "equipment" -> EquipmentStack(
                name = name, rarity = rarity,
                slot = enumOr(obj.str("slot"), EquipmentSlot.WEAPON)
            )
            "manual" -> ManualStack(
                name = name, rarity = rarity,
                type = enumOr(obj.str("type"), ManualType.MIND)
            )
            "pill" -> Pill(
                name = name, rarity = rarity,
                category = enumOr(obj.str("category"), PillCategory.CULTIVATION),
                grade = enumOr(obj.str("grade"), PillGrade.MEDIUM)
            )
            "material" -> Material(
                name = name, rarity = rarity,
                category = enumOr(obj.str("category"), MaterialCategory.BEAST_HIDE)
            )
            "herb" -> Herb(name = name, rarity = rarity, category = obj.str("category") ?: "")
            "seed" -> Seed(
                name = name, rarity = rarity,
                growTime = obj["growTime"]?.jsonPrimitive?.long?.toInt() ?: 3,
                yield = obj["yield"]?.jsonPrimitive?.long?.toInt() ?: 1
            )
            // storageBag：无模板反查字段（领取方按既有随机生成）
            else -> null
        }
    }

    private inline fun <reified T : Enum<T>> enumOr(name: String?, fallback: T): T =
        name?.let { runCatching { enumValueOf<T>(it) }.getOrNull() } ?: fallback
}
