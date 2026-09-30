package com.xianxia.sect.core.registry

import com.xianxia.sect.core.model.EquipStat
import com.xianxia.sect.core.model.EquipStatValue

/** 套装流派（R8：首期物理套 + 法术套各一套） */
enum class EquipSchool { PHYSICAL, MAGIC;

    val displayName: String get() = when (this) {
        PHYSICAL -> "物理"
        MAGIC -> "法术"
    }
}

/** 单档套装效果（与词条共用同一加成模型 [EquipStatValue]，不做第二套加成体系） */
data class SetBonus(val entries: List<EquipStatValue>)

/** 套装定义（2/4/6 三档；件数达档即生效、不叠加不越级、穿满 6 件三档同时生效） */
data class EquipmentSetDef(
    val id: String,
    val name: String,
    val school: EquipSchool,
    val bonus2: SetBonus,
    val bonus4: SetBonus,
    val bonus6: SetBonus
) {
    /** 按穿戴件数取当前生效档位集合（升序；[count] 不足 2 返回空） */
    fun activeBonuses(count: Int): List<SetBonus> = buildList {
        if (count >= 2) add(bonus2)
        if (count >= 4) add(bonus4)
        if (count >= 6) add(bonus6)
    }
}

/**
 * 套装注册表（装备重构 B3，方案 §3.3/§3.7）：首期两套——物理套「裂天罡煞」、
 * 法术套「紫府玄冥」，各 6 部位；2 件套 = 流派类型伤害 +10%（两套差异化，
 * §15.7 Q9）、4 件套 = 暴击率/暴击伤害、6 件套 = 流派类型伤害 +20%。
 * 穿满一套 = 该流派 +30% 类型伤害。
 */
object EquipmentSetDatabase {

    val sets: List<EquipmentSetDef> = listOf(
        EquipmentSetDef(
            id = "lietian",
            name = "裂天罡煞",
            school = EquipSchool.PHYSICAL,
            bonus2 = SetBonus(listOf(EquipStatValue(EquipStat.PHYSICAL_DAMAGE_PCT, 0.10))),
            bonus4 = SetBonus(listOf(EquipStatValue(EquipStat.CRIT_RATE, 0.12))),
            bonus6 = SetBonus(listOf(EquipStatValue(EquipStat.PHYSICAL_DAMAGE_PCT, 0.20)))
        ),
        EquipmentSetDef(
            id = "zifu",
            name = "紫府玄冥",
            school = EquipSchool.MAGIC,
            bonus2 = SetBonus(listOf(EquipStatValue(EquipStat.MAGIC_DAMAGE_PCT, 0.10))),
            bonus4 = SetBonus(listOf(EquipStatValue(EquipStat.CRIT_DAMAGE, 0.25))),
            bonus6 = SetBonus(listOf(EquipStatValue(EquipStat.MAGIC_DAMAGE_PCT, 0.20)))
        )
    )

    private val byId: Map<String, EquipmentSetDef> = sets.associateBy { it.id }

    fun getById(setId: String): EquipmentSetDef? = byId[setId]

    fun getByName(name: String): EquipmentSetDef? = sets.find { it.name == name }
}
