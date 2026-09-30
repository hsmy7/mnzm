package com.xianxia.sect.core.registry

import com.xianxia.sect.core.model.EquipmentSlot

/**
 * 装备模板注册表（装备重构 B3 起**降级为 [EquipmentDatabase] 的纯转发层**——
 * D1 双表漂移收口）。
 *
 * 历史缺陷：本类曾持有第二份硬编码装备表（72 条），数值与 [EquipmentDatabase]
 * 长期漂移且无守卫（锻造走本表、掉落/价格走 Database，同名装备不同数值）。
 * B3 起数据面清零，全部查询转发单源 [EquipmentDatabase]；**禁止再写入任何
 * 模板字面量**（`EquipmentSingleSourceGuardTest` 源码扫描拦截）。
 */
class EquipmentRegistry : BaseTemplateRegistry<EquipmentDatabase.EquipPieceEntry>() {

    override fun loadTemplates(): Map<String, EquipmentDatabase.EquipPieceEntry> =
        EquipmentDatabase.entries

    override fun extractRarity(template: EquipmentDatabase.EquipPieceEntry): Int = template.rarity

    /** 按部位查展开条目（纯转发） */
    fun getBySlot(part: EquipmentSlot): List<EquipmentDatabase.EquipPieceEntry> =
        EquipmentDatabase.getBySlot(part)

    /** 按名称查展开条目（同名多品阶时返回品阶最低条目；展示用） */
    fun getByName(name: String): EquipmentDatabase.EquipPieceEntry? =
        EquipmentDatabase.entries.values.filter { it.name == name }.minByOrNull { it.rarity }
}
