package com.xianxia.sect.core.engine.domain.disciple

import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.model.Disciple
import com.xianxia.sect.core.registry.TalentDatabase

/**
 * 计算弟子的最大寿元。
 *
 * 取三者最大值（上限 [ABSOLUTE_MAX_AGE_CEILING] 防止数据损坏导致永生）：
 * 1. [Disciple.lifespan] — 个人寿命上限（突破时累加）
 * 2. [GameConfig.Realm.Config.maxAge] — 当前境界对应的寿元上限
 * 3. [GameConfig.Realm.Config.maxAge] × (1 + talent 寿命加成) — 天赋加成后的寿元
 */
fun Disciple.computeMaxAge(): Int {
    val realmMaxAge = GameConfig.Realm.get(realm).maxAge
    val talentEffects = TalentDatabase.calculateTalentEffects(talentIds)
    val lifespanBonus = talentEffects["lifespan"] ?: 0.0
    val talentLifespan = (realmMaxAge * (1.0 + lifespanBonus))
        .toInt().coerceAtLeast(1)
    return maxOf(lifespan, realmMaxAge, talentLifespan)
        .coerceAtMost(ABSOLUTE_MAX_AGE_CEILING)
}

/** 寿元计算绝对硬上限（防止数据损坏导致弟子永生） */
private const val ABSOLUTE_MAX_AGE_CEILING = 20000
