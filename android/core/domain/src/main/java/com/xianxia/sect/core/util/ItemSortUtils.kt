package com.xianxia.sect.core.util

import com.xianxia.sect.core.model.EquipmentInstance
import com.xianxia.sect.core.model.EquipmentStack
import com.xianxia.sect.core.model.GameItem
import com.xianxia.sect.core.model.Herb
import com.xianxia.sect.core.model.ManualInstance
import com.xianxia.sect.core.model.ManualStack
import com.xianxia.sect.core.model.Material
import com.xianxia.sect.core.model.Pill
import com.xianxia.sect.core.model.Seed

/**
 * 可关注物品类型注册表（与 MerchantItem.type / StorageBagItem.itemType 字符串体系一致）。
 * 未来新增物品类别（如符箓）时仅需在此追加并同步 [GameItem.watchKey] / feature 层
 * [com.xianxia.sect.ui.game.components.watchKeyOf]，存档结构与排序逻辑无需改动。
 */
val WATCHABLE_ITEM_TYPES: Set<String> =
    setOf("equipment", "manual", "pill", "material", "herb", "seed")

/** 构造关注键："type:name"（如 "pill:聚气丹"） */
fun watchKey(type: String, name: String): String = "$type:$name"

/**
 * 归一化物品类型别名：邮件/签到等场景的 "beastMaterial"（兽材）即仓库材料 "material"，
 * "manual_stack"/"manual_instance" 即功法 "manual"，"equipment_stack"/"equipment_instance"
 * 即装备 "equipment"（弟子储物袋路径使用该别名）。归一化后同一物品在各界面键一致。
 * 未命中别名时 lowercase 兜底，容忍 "Equipment" 等大小写变体。
 */
fun normalizeItemType(type: String): String = when (type) {
    "beastMaterial" -> "material"
    "manual_stack", "manual_instance" -> "manual"
    "equipment_stack", "equipment_instance" -> "equipment"
    else -> type.lowercase()
}

/** [GameItem] 的关注键（sealed 子类全覆盖，灵石/储物袋不属于 GameItem 不可关注） */
fun GameItem.watchKey(): String = when (this) {
    is EquipmentStack, is EquipmentInstance -> watchKey("equipment", name)
    is ManualStack, is ManualInstance -> watchKey("manual", name)
    is Pill -> watchKey("pill", name)
    is Material -> watchKey("material", name)
    is Herb -> watchKey("herb", name)
    is Seed -> watchKey("seed", name)
}

/**
 * 关注优先排序：已关注在前、未关注在后；两组内品阶降序，同品阶名称升序。
 * 与 [DiscipleUtils.sortedByFollowAndRealm] 风格一致。
 *
 * @param watchedKeys 已关注键集合
 * @param keyOf 物品 → 关注键（null 视为未关注）
 * @param rarityOf 物品 → 品阶（越大越靠前）
 * @param nameOf 物品 → 名称（同品阶升序）
 */
fun <T> List<T>.sortedByWatchedThenRarity(
    watchedKeys: Set<String>,
    keyOf: (T) -> String?,
    rarityOf: (T) -> Int,
    nameOf: (T) -> String = { "" }
): List<T> = this.sortedWith(
    compareByDescending<T> { keyOf(it)?.let { k -> k in watchedKeys } ?: false }
        .thenByDescending { rarityOf(it) }
        .thenBy { nameOf(it) }
)

/** [GameItem] 便捷重载：自动从 sealed 子类推导类型键 */
fun <T : GameItem> List<T>.sortedByWatchedThenRarity(
    watchedKeys: Set<String>
): List<T> = this.sortedByWatchedThenRarity(
    watchedKeys,
    keyOf = { it.watchKey() },
    rarityOf = { it.rarity },
    nameOf = { it.name }
)
