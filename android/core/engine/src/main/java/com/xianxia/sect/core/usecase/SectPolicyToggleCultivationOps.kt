package com.xianxia.sect.core.usecase

import com.xianxia.sect.core.GameConfig

// ── 修习类政策开关（自 SectPolicyToggleUseCase 拆出，行为零变更） ──
// `field` 为 C++ 侧成员指针映射名（batch-18b 政策开关事务 ActionId 1680）——
// 名称漂移即政策置位丢失（玩家可见），GTest 用真实名断言。

suspend fun SectPolicyToggleUseCase.toggleAlchemyIncentive() = toggle(
    field = "alchemyIncentive",
    getter = { it.alchemyIncentive }, setter = { p, v -> p.copy(alchemyIncentive = v) },
    monthlyCost = { GameConfig.PolicyConfig.ALCHEMY_INCENTIVE_MONTHLY }
)

fun SectPolicyToggleUseCase.isAlchemyIncentiveEnabled(): Boolean =
    gameEngine.gameData.value?.sectPolicies?.alchemyIncentive ?: false

suspend fun SectPolicyToggleUseCase.toggleForgeIncentive() = toggle(
    field = "forgeIncentive",
    getter = { it.forgeIncentive }, setter = { p, v -> p.copy(forgeIncentive = v) },
    monthlyCost = { GameConfig.PolicyConfig.FORGE_INCENTIVE_MONTHLY }
)

fun SectPolicyToggleUseCase.isForgeIncentiveEnabled(): Boolean =
    gameEngine.gameData.value?.sectPolicies?.forgeIncentive ?: false

suspend fun SectPolicyToggleUseCase.toggleHerbCultivation() = toggle(
    field = "herbCultivation",
    getter = { it.herbCultivation }, setter = { p, v -> p.copy(herbCultivation = v) },
    monthlyCost = { GameConfig.PolicyConfig.HERB_CULTIVATION_MONTHLY }
)

fun SectPolicyToggleUseCase.isHerbCultivationEnabled(): Boolean =
    gameEngine.gameData.value?.sectPolicies?.herbCultivation ?: false

suspend fun SectPolicyToggleUseCase.toggleManualResearch() = toggle(
    field = "manualResearch",
    getter = { it.manualResearch }, setter = { p, v -> p.copy(manualResearch = v) },
    monthlyCost = { GameConfig.PolicyConfig.MANUAL_RESEARCH_MONTHLY }
)

fun SectPolicyToggleUseCase.isManualResearchEnabled(): Boolean =
    gameEngine.gameData.value?.sectPolicies?.manualResearch ?: false

suspend fun SectPolicyToggleUseCase.toggleStrictTraining() = toggle(
    field = "strictTraining",
    getter = { it.strictTraining }, setter = { p, v -> p.copy(strictTraining = v) },
    monthlyCost = { GameConfig.PolicyConfig.STRICT_TRAINING_MONTHLY }
)

fun SectPolicyToggleUseCase.isStrictTrainingEnabled(): Boolean =
    gameEngine.gameData.value?.sectPolicies?.strictTraining ?: false

suspend fun SectPolicyToggleUseCase.toggleRelaxedMgmt() = toggle(
    field = "relaxedMgmt",
    getter = { it.relaxedMgmt }, setter = { p, v -> p.copy(relaxedMgmt = v) },
    monthlyCost = { GameConfig.PolicyConfig.RELAXED_MGMT_MONTHLY },
    affectsCultivationRate = true  // 修炼速度 -10%
)

fun SectPolicyToggleUseCase.isRelaxedMgmtEnabled(): Boolean =
    gameEngine.gameData.value?.sectPolicies?.relaxedMgmt ?: false
