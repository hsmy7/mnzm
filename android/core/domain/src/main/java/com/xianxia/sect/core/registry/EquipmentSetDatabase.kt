package com.xianxia.sect.core.registry

import com.xianxia.sect.core.model.EquipStat
import com.xianxia.sect.core.model.EquipStatValue

/** 套装流派：物理 + 五行（金/木/水/火/土各一系，五行属性伤害系统 §3.4） */
enum class EquipSchool { PHYSICAL, METAL, WOOD, WATER, FIRE, EARTH;

    val displayName: String get() = when (this) {
        PHYSICAL -> "物理"
        METAL -> "金"
        WOOD -> "木"
        WATER -> "水"
        FIRE -> "火"
        EARTH -> "土"
    }

    /** 流派对应的元素 key；物理为 null */
    val element: String? get() = when (this) {
        PHYSICAL -> null
        METAL -> "metal"
        WOOD -> "wood"
        WATER -> "water"
        FIRE -> "fire"
        EARTH -> "earth"
    }
}

/** 单档套装效果（与词条共用同一加成模型 [EquipStatValue]，不做第二套加成体系） */
data class SetBonus(val entries: List<EquipStatValue>)

/** 套装定义（2/4 两档，4 件 = 满套；件数达档即生效、不叠加不越级、穿满 4 件两档同时生效） */
data class EquipmentSetDef(
    val id: String,
    val name: String,
    val school: EquipSchool,
    val bonus2: SetBonus,
    val bonus4: SetBonus,
    /** 满套档（4 件触发；与 4 件套暴击档同件数叠加生效） */
    val bonusFull: SetBonus
) {
    /** 按穿戴件数取当前生效档位集合（升序；[count] 不足 2 返回空） */
    fun activeBonuses(count: Int): List<SetBonus> = buildList {
        if (count >= 2) add(bonus2)
        if (count >= 4) {
            add(bonus4)
            add(bonusFull)
        }
    }
}

/**
 * 套装注册表：六套——物理套「裂天罡煞」+ 五行套各一套（庚金白虎/青木长生/
 * 玄水寒渊/离火焚天/厚土镇岳），各 4 部位（头/身/手/脚）。
 * **同构骨架，仅本系元素不同**：2 件套 = 本系伤害 +10%、4 件套 = 暴击率 +12%
 * + 本系伤害 +20%。穿满一套 = 该系 +30% 类型伤害且暴击率 +12%。
 *
 * 套装效果经 [EquipStatValue] 落类型乘区；元素套效果在弟子侧受灵根 gate
 * （灵根含该元素才生效，方案 §3.3）。原法术套「紫府玄冥」随法术类型退役作废。
 */
object EquipmentSetDatabase {

    val sets: List<EquipmentSetDef> = listOf(
        EquipmentSetDef(
            id = "lietian",
            name = "裂天罡煞",
            school = EquipSchool.PHYSICAL,
            bonus2 = SetBonus(listOf(EquipStatValue(EquipStat.PHYSICAL_DAMAGE_PCT, 0.10))),
            bonus4 = SetBonus(listOf(EquipStatValue(EquipStat.CRIT_RATE, 0.12))),
            bonusFull = SetBonus(listOf(EquipStatValue(EquipStat.PHYSICAL_DAMAGE_PCT, 0.20)))
        ),
        EquipmentSetDef(
            id = "gengjin",
            name = "庚金白虎",
            school = EquipSchool.METAL,
            bonus2 = SetBonus(listOf(EquipStatValue(EquipStat.METAL_DAMAGE_PCT, 0.10))),
            bonus4 = SetBonus(listOf(EquipStatValue(EquipStat.CRIT_RATE, 0.12))),
            bonusFull = SetBonus(listOf(EquipStatValue(EquipStat.METAL_DAMAGE_PCT, 0.20)))
        ),
        EquipmentSetDef(
            id = "qingmu",
            name = "青木长生",
            school = EquipSchool.WOOD,
            bonus2 = SetBonus(listOf(EquipStatValue(EquipStat.WOOD_DAMAGE_PCT, 0.10))),
            bonus4 = SetBonus(listOf(EquipStatValue(EquipStat.CRIT_RATE, 0.12))),
            bonusFull = SetBonus(listOf(EquipStatValue(EquipStat.WOOD_DAMAGE_PCT, 0.20)))
        ),
        EquipmentSetDef(
            id = "xuanshui",
            name = "玄水寒渊",
            school = EquipSchool.WATER,
            bonus2 = SetBonus(listOf(EquipStatValue(EquipStat.WATER_DAMAGE_PCT, 0.10))),
            bonus4 = SetBonus(listOf(EquipStatValue(EquipStat.CRIT_RATE, 0.12))),
            bonusFull = SetBonus(listOf(EquipStatValue(EquipStat.WATER_DAMAGE_PCT, 0.20)))
        ),
        EquipmentSetDef(
            id = "lihuo",
            name = "离火焚天",
            school = EquipSchool.FIRE,
            bonus2 = SetBonus(listOf(EquipStatValue(EquipStat.FIRE_DAMAGE_PCT, 0.10))),
            bonus4 = SetBonus(listOf(EquipStatValue(EquipStat.CRIT_RATE, 0.12))),
            bonusFull = SetBonus(listOf(EquipStatValue(EquipStat.FIRE_DAMAGE_PCT, 0.20)))
        ),
        EquipmentSetDef(
            id = "houtu",
            name = "厚土镇岳",
            school = EquipSchool.EARTH,
            bonus2 = SetBonus(listOf(EquipStatValue(EquipStat.EARTH_DAMAGE_PCT, 0.10))),
            bonus4 = SetBonus(listOf(EquipStatValue(EquipStat.CRIT_RATE, 0.12))),
            bonusFull = SetBonus(listOf(EquipStatValue(EquipStat.EARTH_DAMAGE_PCT, 0.20)))
        )
    )

    /** 六套套装 id（装备供给线随机抽取的单一真源：掉落/兑换码/编队/敌人装备/任务/试炼） */
    val ALL_IDS: List<String> = sets.map { it.id }

    private val byId: Map<String, EquipmentSetDef> = sets.associateBy { it.id }

    fun getById(setId: String): EquipmentSetDef? = byId[setId]

    fun getByName(name: String): EquipmentSetDef? = sets.find { it.name == name }
}
