package com.xianxia.sect.core.engine.domain.disciple

import com.xianxia.sect.core.model.Disciple

// ── DiscipleStatCalculator 拆分域 7/7（行为零变更） ──
fun DiscipleStatCalculator.calculateLifespanCultivationPenalty(age: Int, lifespan: Int): Double {
    val remaining = calculateLifespanRemainingPercent(age, lifespan)
    if (remaining >= LIFESPAN_PENALTY_THRESHOLD) return 0.0
    val deficitPercent = (LIFESPAN_PENALTY_THRESHOLD - remaining) * 100
    return deficitPercent * LIFESPAN_CULTIVATION_PENALTY_PER_PCT
}

/**
 * 计算寿命将尽对突破率的惩罚值
 * 剩余寿命低于20%时，每少1个百分点降低2%突破率
 * @return 惩罚值（非负数），可直接从 totalBonus 中扣除
 */

fun DiscipleStatCalculator.calculateLifespanBreakthroughPenalty(age: Int, lifespan: Int): Double {
    val remaining = calculateLifespanRemainingPercent(age, lifespan)
    if (remaining >= LIFESPAN_PENALTY_THRESHOLD) return 0.0
    val deficitPercent = (LIFESPAN_PENALTY_THRESHOLD - remaining) * 100
    return deficitPercent * LIFESPAN_BREAKTHROUGH_PENALTY_PER_PCT
}

/**
 * 判断弟子是否处于丧亲悲痛期
 * @param griefEndYear 悲痛结束年份，null表示未处于悲痛期
 * @param currentYear 当前游戏年份
 */

fun DiscipleStatCalculator.isGrieving(griefEndYear: Int?, currentYear: Int): Boolean {
    return griefEndYear != null && currentYear < griefEndYear
}

// ==================== 亲属关系判定 ====================

/**
 * 判断两个弟子是否为亲属关系（道侣、父母/子嗣、兄弟姐妹）
 * 用于丧亲悲痛系统
 */

fun DiscipleStatCalculator.areRelatives(a: Disciple, b: Disciple): Boolean {
    // 道侣关系
    if (a.social.partnerId == b.id || b.social.partnerId == a.id) return true

    // 父母-子女关系：a是b的父母 或 b是a的父母
    if (a.id == b.social.parentId1 || a.id == b.social.parentId2) return true
    if (b.id == a.social.parentId1 || b.id == a.social.parentId2) return true

    // 兄弟姐妹关系：有共同父母（支持单亲匹配）
    val aParents = setOfNotNull(a.social.parentId1, a.social.parentId2)
    val bParents = setOfNotNull(b.social.parentId1, b.social.parentId2)
    if (aParents.isNotEmpty() && aParents.intersect(bParents).isNotEmpty()) return true

    return false
}

/**
 * 为所有存活亲属设置悲痛期（持续1年），支持多个逝者批量处理
 * @param disciples 当前弟子列表
 * @param deceasedList 阵亡/逝世的弟子列表
 * @param currentYear 当前游戏年份
 * @return 更新后的弟子列表
 */

fun DiscipleStatCalculator.applyGriefToRelatives(
    disciples: List<Disciple>,
    deceasedList: List<Disciple>,
    currentYear: Int
): List<Disciple> {
    val griefEndYear = currentYear + 1
    var updated = disciples
    for (deceased in deceasedList) {
        updated = updated.map { d ->
            if (!d.isAlive || d.id == deceased.id) return@map d
            if (areRelatives(d, deceased)) {
                val existingGriefEnd = d.social.griefEndYear
                val newGriefEnd = if (existingGriefEnd != null && existingGriefEnd >
                    griefEndYear) existingGriefEnd else griefEndYear
                d.copy(social = d.social.copy(griefEndYear = newGriefEnd))
            } else {
                d
            }
        }
    }
    return updated
}

/**
 * 计算所有存活亲属的悲痛结束年份映射。
 *
 * 与 [applyGriefToRelatives] 逻辑相同，但返回 `Map<Int, Int>`
 * 而非全量 `List<Disciple>`，便于直接列写入 griefEndYears。
 *
 * @param disciples 当前弟子列表
 * @param deceasedList 阵亡/逝世的弟子列表
 * @param currentYear 当前游戏年份
 * @return Map<Int, Int> 弟子ID → griefEndYear（仅包含需更新的弟子）
 */

fun DiscipleStatCalculator.computeGriefEndYearMap(
    disciples: List<Disciple>,
    deceasedList: List<Disciple>,
    currentYear: Int
): Map<Int, Int> {
    val griefEndYear = currentYear + 1
    val result = mutableMapOf<Int, Int>()
    for (deceased in deceasedList) {
        for (d in disciples) {
            if (!d.isAlive || d.id == deceased.id) continue
            if (areRelatives(d, deceased)) {
                recordGriefEndIfLonger(result, d, griefEndYear)
            }
        }
    }
    return result
}

/**
 * 记录单个亲属的悲痛结束年：现值与新值取较大者
 * （多个逝者时取最长的悲痛期）；非数字 id 跳过。
 */

internal fun DiscipleStatCalculator.recordGriefEndIfLonger(
    result: MutableMap<Int, Int>,
    relative: Disciple,
    griefEndYear: Int
) {
    val idInt = relative.id.toIntOrNull() ?: return
    val existingGriefEnd = relative.social.griefEndYear
    val newGriefEnd = if (existingGriefEnd != null && existingGriefEnd >
        griefEndYear) existingGriefEnd else griefEndYear
    // 取最大值（多个逝者时取最长的悲痛期）
    val currentMax = result[idInt]
    if (currentMax == null || newGriefEnd > currentMax) {
        result[idInt] = newGriefEnd
    }
}
