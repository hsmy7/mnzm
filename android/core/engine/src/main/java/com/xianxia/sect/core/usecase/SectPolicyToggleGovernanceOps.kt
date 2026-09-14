package com.xianxia.sect.core.usecase

import com.xianxia.sect.core.GameConfig

// ── 治理类政策开关（自 SectPolicyToggleUseCase 拆出，行为零变更） ──
// `field` 为 C++ 侧成员指针映射名（batch-18b 政策开关事务 ActionId 1680）。

// ── 免费政策（开关无消耗） ──
suspend fun SectPolicyToggleUseCase.toggleFrugality() = toggle(
    field = "frugality",
    getter = { it.frugality }, setter = { p, v -> p.copy(frugality = v) }
)

fun SectPolicyToggleUseCase.isFrugalityEnabled(): Boolean =
    gameEngine.gameData.value?.sectPolicies?.frugality ?: false

// ── 固定月消耗政策 ──
suspend fun SectPolicyToggleUseCase.toggleEnhancedSecurity() = toggle(
    field = "enhancedSecurity",
    getter = { it.enhancedSecurity }, setter = { p, v -> p.copy(enhancedSecurity = v) },
    monthlyCost = { GameConfig.PolicyConfig.ENHANCED_SECURITY_MONTHLY }
)

fun SectPolicyToggleUseCase.isEnhancedSecurityEnabled(): Boolean =
    gameEngine.gameData.value?.sectPolicies?.enhancedSecurity ?: false

suspend fun SectPolicyToggleUseCase.toggleCurfew() = toggle(
    field = "curfew",
    getter = { it.curfew }, setter = { p, v -> p.copy(curfew = v) },
    monthlyCost = { GameConfig.PolicyConfig.CURFEW_MONTHLY }
)

fun SectPolicyToggleUseCase.isCurfewEnabled(): Boolean =
    gameEngine.gameData.value?.sectPolicies?.curfew ?: false

suspend fun SectPolicyToggleUseCase.toggleRewardPunish() = toggle(
    field = "rewardPunish",
    getter = { it.rewardPunish }, setter = { p, v -> p.copy(rewardPunish = v) },
    monthlyCost = { GameConfig.PolicyConfig.REWARD_PUNISH_MONTHLY }
)

fun SectPolicyToggleUseCase.isRewardPunishEnabled(): Boolean =
    gameEngine.gameData.value?.sectPolicies?.rewardPunish ?: false

suspend fun SectPolicyToggleUseCase.toggleSpiritSpring() = toggle(
    field = "spiritSpring",
    getter = { it.spiritSpring }, setter = { p, v -> p.copy(spiritSpring = v) },
    monthlyCost = { GameConfig.PolicyConfig.SPIRIT_SPRING_MONTHLY }
)

fun SectPolicyToggleUseCase.isSpiritSpringEnabled(): Boolean =
    gameEngine.gameData.value?.sectPolicies?.spiritSpring ?: false
