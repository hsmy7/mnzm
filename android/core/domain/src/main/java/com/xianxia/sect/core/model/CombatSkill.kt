package com.xianxia.sect.core.model

import com.xianxia.sect.core.BuffType
import com.xianxia.sect.core.DamageType
import com.xianxia.sect.core.HealType
import com.xianxia.sect.core.SkillType

data class CombatSkill(
    val name: String,
    val skillType: SkillType = SkillType.ATTACK,
    val damageType: DamageType,
    val damageMultiplier: Double,
    val mpCost: Int,
    val cooldown: Int,
    val hits: Int = 1,
    val healPercent: Double = 0.0,
    val healFixed: Int = 0,
    val healType: HealType = HealType.HP,
    val buffType: BuffType? = null,
    val buffValue: Double = 0.0,
    val buffDuration: Int = 0,
    val buffs: List<Triple<BuffType, Double, Int>> = emptyList(),
    var currentCooldown: Int = 0,
    val skillDescription: String = "",
    val manualName: String = "",
    val isAoe: Boolean = false,
    val targetScope: String = "self",
    val shieldPercent: Double = 0.0,
    val turnAdvancePercent: Double = 0.0,
    val damageSharePercent: Double = 0.0,
    val damageLinkPercent: Double = 0.0
)
