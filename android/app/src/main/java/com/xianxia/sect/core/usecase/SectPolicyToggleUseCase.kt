package com.xianxia.sect.core.usecase

import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.engine.GameEngine
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SectPolicyToggleUseCase @Inject constructor(
    private val gameEngine: GameEngine
) {
    sealed class ToggleResult {
        data object Success : ToggleResult()
        data class Error(val message: String) : ToggleResult()
    }

    suspend fun toggleSpiritMineBoost(): ToggleResult {
        gameEngine.updateGameData {
            it.copy(sectPolicies = it.sectPolicies.copy(spiritMineBoost = !it.sectPolicies.spiritMineBoost))
        }
        return ToggleResult.Success
    }

    fun isSpiritMineBoostEnabled(): Boolean {
        return gameEngine.gameData.value?.sectPolicies?.spiritMineBoost ?: false
    }

    fun getSpiritMineBoostEffect(): Double = GameConfig.PolicyConfig.SPIRIT_MINE_BOOST_BASE_EFFECT

    suspend fun toggleEnhancedSecurity(): ToggleResult {
        val requiredStones = GameConfig.PolicyConfig.ENHANCED_SECURITY_COST
        var result: ToggleResult = ToggleResult.Success
        gameEngine.updateGameData {
            if (!it.sectPolicies.enhancedSecurity) {
                if (it.spiritStones < requiredStones) {
                    result = ToggleResult.Error("灵石不足${requiredStones}，无法开启增强治安政策")
                    it
                } else {
                    it.copy(
                        spiritStones = it.spiritStones - requiredStones,
                        sectPolicies = it.sectPolicies.copy(enhancedSecurity = true)
                    )
                }
            } else {
                it.copy(sectPolicies = it.sectPolicies.copy(enhancedSecurity = false))
            }
        }
        return result
    }

    fun isEnhancedSecurityEnabled(): Boolean {
        return gameEngine.gameData.value?.sectPolicies?.enhancedSecurity ?: false
    }

    fun getEnhancedSecurityBaseBonus(): Double = GameConfig.PolicyConfig.ENHANCED_SECURITY_BASE_EFFECT

    suspend fun toggleAlchemyIncentive(): ToggleResult {
        val requiredStones = GameConfig.PolicyConfig.ALCHEMY_INCENTIVE_COST
        var result: ToggleResult = ToggleResult.Success
        gameEngine.updateGameData {
            if (!it.sectPolicies.alchemyIncentive) {
                if (it.spiritStones < requiredStones) {
                    result = ToggleResult.Error("灵石不足${requiredStones}，无法开启丹道激励政策")
                    it
                } else {
                    it.copy(
                        spiritStones = it.spiritStones - requiredStones,
                        sectPolicies = it.sectPolicies.copy(alchemyIncentive = true)
                    )
                }
            } else {
                it.copy(sectPolicies = it.sectPolicies.copy(alchemyIncentive = false))
            }
        }
        return result
    }

    fun isAlchemyIncentiveEnabled(): Boolean {
        return gameEngine.gameData.value?.sectPolicies?.alchemyIncentive ?: false
    }

    suspend fun toggleForgeIncentive(): ToggleResult {
        val requiredStones = GameConfig.PolicyConfig.FORGE_INCENTIVE_COST
        var result: ToggleResult = ToggleResult.Success
        gameEngine.updateGameData {
            if (!it.sectPolicies.forgeIncentive) {
                if (it.spiritStones < requiredStones) {
                    result = ToggleResult.Error("灵石不足${requiredStones}，无法开启锻造激励政策")
                    it
                } else {
                    it.copy(
                        spiritStones = it.spiritStones - requiredStones,
                        sectPolicies = it.sectPolicies.copy(forgeIncentive = true)
                    )
                }
            } else {
                it.copy(sectPolicies = it.sectPolicies.copy(forgeIncentive = false))
            }
        }
        return result
    }

    fun isForgeIncentiveEnabled(): Boolean {
        return gameEngine.gameData.value?.sectPolicies?.forgeIncentive ?: false
    }

    suspend fun toggleHerbCultivation(): ToggleResult {
        val requiredStones = GameConfig.PolicyConfig.HERB_CULTIVATION_COST
        var result: ToggleResult = ToggleResult.Success
        gameEngine.updateGameData {
            if (!it.sectPolicies.herbCultivation) {
                if (it.spiritStones < requiredStones) {
                    result = ToggleResult.Error("灵石不足${requiredStones}，无法开启灵药培育政策")
                    it
                } else {
                    it.copy(
                        spiritStones = it.spiritStones - requiredStones,
                        sectPolicies = it.sectPolicies.copy(herbCultivation = true)
                    )
                }
            } else {
                it.copy(sectPolicies = it.sectPolicies.copy(herbCultivation = false))
            }
        }
        return result
    }

    fun isHerbCultivationEnabled(): Boolean {
        return gameEngine.gameData.value?.sectPolicies?.herbCultivation ?: false
    }

    suspend fun toggleCultivationSubsidy(): ToggleResult {
        val requiredStones = GameConfig.PolicyConfig.CULTIVATION_SUBSIDY_COST
        var result: ToggleResult = ToggleResult.Success
        gameEngine.updateGameData {
            if (!it.sectPolicies.cultivationSubsidy) {
                if (it.spiritStones < requiredStones) {
                    result = ToggleResult.Error("灵石不足${requiredStones}，无法开启修行津贴政策")
                    it
                } else {
                    it.copy(
                        spiritStones = it.spiritStones - requiredStones,
                        sectPolicies = it.sectPolicies.copy(cultivationSubsidy = true)
                    )
                }
            } else {
                it.copy(sectPolicies = it.sectPolicies.copy(cultivationSubsidy = false))
            }
        }
        return result
    }

    fun isCultivationSubsidyEnabled(): Boolean {
        return gameEngine.gameData.value?.sectPolicies?.cultivationSubsidy ?: false
    }

    suspend fun toggleManualResearch(): ToggleResult {
        val requiredStones = GameConfig.PolicyConfig.MANUAL_RESEARCH_COST
        var result: ToggleResult = ToggleResult.Success
        gameEngine.updateGameData {
            if (!it.sectPolicies.manualResearch) {
                if (it.spiritStones < requiredStones) {
                    result = ToggleResult.Error("灵石不足${requiredStones}，无法开启功法研习政策")
                    it
                } else {
                    it.copy(
                        spiritStones = it.spiritStones - requiredStones,
                        sectPolicies = it.sectPolicies.copy(manualResearch = true)
                    )
                }
            } else {
                it.copy(sectPolicies = it.sectPolicies.copy(manualResearch = false))
            }
        }
        return result
    }

    fun isManualResearchEnabled(): Boolean {
        return gameEngine.gameData.value?.sectPolicies?.manualResearch ?: false
    }

    fun getViceSectMasterIntelligenceBonus(viceSectMasterIntelligence: Int): Double {
        val baseIntelligence = GameConfig.PolicyConfig.VICE_SECT_MASTER_INTELLIGENCE_BASE
        val step = GameConfig.PolicyConfig.VICE_SECT_MASTER_INTELLIGENCE_STEP
        val bonusPerStep = GameConfig.PolicyConfig.VICE_SECT_MASTER_INTELLIGENCE_BONUS_PER_STEP
        return ((viceSectMasterIntelligence - baseIntelligence) / step.toDouble() * bonusPerStep).coerceAtLeast(0.0)
    }
}
