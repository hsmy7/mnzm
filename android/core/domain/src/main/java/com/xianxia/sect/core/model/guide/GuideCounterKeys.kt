package com.xianxia.sect.core.model.guide

/**
 * 引导系统计数器 Key 常量 — 增量计数器与条件检查的唯一真相源。
 *
 * 所有引擎层和 UI 层通过此类引用计数器 Key，拼写错误会在编译期暴露。
 */
object GuideCounterKeys {
    const val MINING_OUTPUT = "miningOutput"
    const val ALCHEMY_COMPLETED = "alchemyCompleted"
    const val FORGE_COMPLETED = "forgeCompleted"
    const val HERBS_HARVESTED = "herbsHarvested"
    const val MISSIONS_COMPLETED = "missionsCompleted"
    const val DISCIPLES_RECRUITED = "disciplesRecruited"
    const val PATROL_BEAST_DEFEATED = "patrolBeastDefeated"
    const val POLICY_ACTIVATED = "policyActivated"
    const val AUTO_MINE_ACTIVATED = "autoMineActivated"
    const val AUTO_PLANT_ACTIVATED = "autoPlantActivated"
    const val AUTO_PRODUCTION_ACTIVATED = "autoProductionActivated"
    const val BREAKTHROUGHS = "breakthroughs"
    const val DISCIPLE_IMPRISONED = "discipleImprisoned"
    const val CULTIVATION_YEARS = "cultivationYears"
}
