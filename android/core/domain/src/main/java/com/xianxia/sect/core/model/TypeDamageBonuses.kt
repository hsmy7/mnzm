package com.xianxia.sect.core.model

/**
 * 六路类型伤害加成（物理 + 金木水火土；乘区，战斗期字段）。
 *
 * 由装备/套装词条汇总（EquipStatResolver）经**灵根 gate 折算**而来
 * （[SpiritRoot.elementGate]：灵根含该元素 → 全额，不含 → 0.0；物理不受 gate）。
 * 消费点：Combatant 六路增伤桶装配（convertDiscipleToCombatant 等三条弟子线）。
 * 面板列不展示（与 critDamage 同口径的战斗期字段）。
 */
data class TypeDamageBonuses(
    val physical: Double = 0.0,
    val metal: Double = 0.0,
    val wood: Double = 0.0,
    val water: Double = 0.0,
    val fire: Double = 0.0,
    val earth: Double = 0.0
) {
    /** 按元素 key 取加成（非五行 key 归物理通道；与 DamageType.element 对应） */
    fun ofElement(element: String?): Double = when (element) {
        "metal" -> metal
        "wood" -> wood
        "water" -> water
        "fire" -> fire
        "earth" -> earth
        else -> physical
    }
}
