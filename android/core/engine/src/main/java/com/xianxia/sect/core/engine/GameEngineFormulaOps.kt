package com.xianxia.sect.core.engine


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
