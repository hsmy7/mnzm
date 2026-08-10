package com.xianxia.sect.core.domain.disciple

import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.model.Disciple
import com.xianxia.sect.core.registry.AffixDatabase
import com.xianxia.sect.core.registry.TalentDatabase

/**
 * 计算弟子的最大寿元。
 *
 * 取三者最大值（上限 [ABSOLUTE_MAX_AGE_CEILING] 防止数据损坏导致永生）：
 * 1. [Disciple.lifespan] — 个人寿命上限（突破时累加）
 * 2. [GameConfig.Realm.Config.maxAge] — 当前境界对应的寿元上限
 * 3. [GameConfig.Realm.Config.maxAge] × (1 + 天赋寿命加成 + 词条寿命加成) — 特质加成后的寿元
 *
 * 第 3 项含词条（"延年" aff_lifespan）——洗炼天赋/词条（洗入/洗掉寿命加成词条）后，
 * 寿元派生与当前特质保持一致（对抗性审查 2026-08-09：原实现只派生天赋，
 * 洗入"延年"后寿元不涨；配合洗炼 confirm 的 lifespan 字段同步，双保险）。
 *
 * **寿元上限计算唯一来源**（2026-08-10 下沉 domain）：引擎死亡判定（DiscipleLifecycleProcessor）、
 * AI 老化（AISectDiscipleManager）、招募老化（RecruitService）、存档校验（AgeLifespanRule）
 * 均以此为准。禁止在其他模块复制/改写本公式——引擎与存档校验口径分歧曾导致
 * "工作槽弟子永生"Bug（规则截断回 lifespan 而引擎允许活到本函数上限）。
 */
fun Disciple.computeMaxAge(): Int {
    val realmMaxAge = GameConfig.Realm.get(realm).maxAge
    val talentEffects = TalentDatabase.calculateTalentEffects(talentIds)
    val affixEffects = AffixDatabase.calculateAffixEffects(affixIds)
    val lifespanBonus =
        (talentEffects["lifespan"] ?: 0.0) + (affixEffects["lifespan"] ?: 0.0)
    val traitLifespan = (realmMaxAge * (1.0 + lifespanBonus))
        .toInt().coerceAtLeast(1)
    return maxOf(lifespan, realmMaxAge, traitLifespan)
        .coerceAtMost(ABSOLUTE_MAX_AGE_CEILING)
}

/** 寿元计算绝对硬上限（防止数据损坏导致弟子永生） */
const val ABSOLUTE_MAX_AGE_CEILING = 20000
