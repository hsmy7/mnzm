package com.xianxia.sect.core.state

import com.xianxia.sect.core.model.EquipmentStack
import com.xianxia.sect.core.model.Herb
import com.xianxia.sect.core.model.ManualStack
import com.xianxia.sect.core.model.Material
import com.xianxia.sect.core.model.Pill
import com.xianxia.sect.core.model.Seed
import com.xianxia.sect.core.model.StorageBag

/**
 * 可堆叠物品合并键的单一事实来源。
 *
 * 替代散落在 InventorySystem / InventoryFacadeImpl / AutoBuyService /
 * DiscipleEquipmentService / StorageBagUtils 中的 6+ 处内联 `StackKey.of(...)`，
 * 消除合并键不一致导致的同种物品分裂为多个堆叠的问题。
 *
 * 注意：此处跨 Gradle 模块使用 public 可见性（2.3 条 internal 默认的正当例外），
 * domain 模块零依赖，engine 各消费者均可复用同一份键定义。
 */
object StackKeys {

    /** 装备堆叠合并键：名称 + 稀有度 + 装备槽位 */
    fun equipment(item: EquipmentStack): StackKey =
        StackKey.of(item.name, item.rarity, item.slot.name)

    /** 功法堆叠合并键：名称 + 稀有度 + 功法类型 */
    fun manual(item: ManualStack): StackKey =
        StackKey.of(item.name, item.rarity, item.type.name)

    /**
     * 丹药堆叠合并键：名称 + 稀有度 + 分类 + 品阶。
     * 品阶（下品/中品/上品）效果不同，属不同物品，故意包含 grade。
     */
    fun pill(item: Pill): StackKey =
        StackKey.of(item.name, item.rarity, item.category.name, item.grade.name)

    /** 材料堆叠合并键：名称 + 稀有度 + 材料分类 */
    fun material(item: Material): StackKey =
        StackKey.of(item.name, item.rarity, item.category.name)

    /** 草药堆叠合并键：名称 + 稀有度 + 分类（String） */
    fun herb(item: Herb): StackKey =
        StackKey.of(item.name, item.rarity, item.category)

    /** 种子堆叠合并键：名称 + 稀有度 + 生长时间 */
    fun seed(item: Seed): StackKey =
        StackKey.of(item.name, item.rarity, item.growTime)

    /**
     * 储物袋堆叠合并键：稀有度。
     * 储物袋名称由稀有度经 TIER_NAMES 唯一推导，按稀有度合并等价于按名称合并。
     */
    fun storageBag(item: StorageBag): StackKey =
        StackKey.of(item.rarity)
}
