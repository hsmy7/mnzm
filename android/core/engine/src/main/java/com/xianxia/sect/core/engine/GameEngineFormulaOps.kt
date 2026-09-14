package com.xianxia.sect.core.engine

import com.xianxia.sect.core.model.Disciple


/**
 * 计算生产成功率加成
 *
 * @param弟子 执行生产的弟子（可为空）
 * @param buildingId 建筑ID
 * @return 总成功率加成（0.0-1.0）
 */
fun GameEngine.calculateSuccessRateBonus(disciple: Disciple?,
    buildingId: String): Double = formulaService.calculateSuccessRateBonus(disciple, buildingId)
/**
 * 计算所有弟子的工作持续时间加成
 *
 * @param baseDuration 基础持续时间（月）
 * @param buildingId 建筑ID
 * @return 实际持续时间（月）
 */
fun GameEngine.calculateWorkDurationWithAllDisciples(baseDuration: Int,
    buildingId: String): Int = formulaService.calculateWorkDurationWithAllDisciples(baseDuration, buildingId)
/**
 * 计算长老和亲传弟子的综合加成
 *
 * @param buildingType 建筑类型
 * @return 长老加成数据（包含速度、成功率和产量加成）
 */
fun GameEngine.calculateElderAndDisciplesBonus(buildingType: String): ElderBonusData = formulaService
    .calculateElderAndDisciplesBonus(buildingType)
