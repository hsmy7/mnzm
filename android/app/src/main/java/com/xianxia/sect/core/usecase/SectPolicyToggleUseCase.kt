package com.xianxia.sect.core.usecase

import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.engine.GameEngine
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 宗门政策切换用例
 *
 * 整合了 SectViewModel 和 ProductionViewModel 中重复的政策切换逻辑，
 * 包括7个 toggle 方法、7个 isEnabled 查询方法和效果计算方法。
 */
@Singleton
class SectPolicyToggleUseCase @Inject constructor(
    private val gameEngine: GameEngine
) {
    sealed class ToggleResult {
        data object Success : ToggleResult()
        data class Error(val message: String) : ToggleResult()
    }

    // ==================== 灵矿加速 ====================

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

    // ==================== 增强治安 ====================

    suspend fun toggleEnhancedSecurity(): ToggleResult {
        val currentGameData = gameEngine.gameData.value ?: return ToggleResult.Error("数据不可用")
        val currentPolicies = currentGameData.sectPolicies
        val requiredStones = GameConfig.PolicyConfig.ENHANCED_SECURITY_COST

        if (!currentPolicies.enhancedSecurity) {
            if (currentGameData.spiritStones < requiredStones) {
                return ToggleResult.Error("灵石不足${requiredStones}，无法开启增强治安政策")
            }
            gameEngine.updateGameData {
                it.copy(
                    spiritStones = it.spiritStones - requiredStones,
                    sectPolicies = it.sectPolicies.copy(enhancedSecurity = true)
                )
            }
        } else {
            gameEngine.updateGameData {
                it.copy(sectPolicies = it.sectPolicies.copy(enhancedSecurity = false))
            }
        }
        return ToggleResult.Success
    }

    fun isEnhancedSecurityEnabled(): Boolean {
        return gameEngine.gameData.value?.sectPolicies?.enhancedSecurity ?: false
    }

    fun getEnhancedSecurityBaseBonus(): Double = GameConfig.PolicyConfig.ENHANCED_SECURITY_BASE_EFFECT

    // ==================== 丹道激励 ====================

    suspend fun toggleAlchemyIncentive(): ToggleResult {
        val currentGameData = gameEngine.gameData.value ?: return ToggleResult.Error("数据不可用")
        val currentPolicies = currentGameData.sectPolicies
        val requiredStones = GameConfig.PolicyConfig.ALCHEMY_INCENTIVE_COST

        if (!currentPolicies.alchemyIncentive) {
            if (currentGameData.spiritStones < requiredStones) {
                return ToggleResult.Error("灵石不足${requiredStones}，无法开启丹道激励政策")
            }
            gameEngine.updateGameData {
                it.copy(
                    spiritStones = it.spiritStones - requiredStones,
                    sectPolicies = it.sectPolicies.copy(alchemyIncentive = true)
                )
            }
        } else {
            gameEngine.updateGameData {
                it.copy(sectPolicies = it.sectPolicies.copy(alchemyIncentive = false))
            }
        }
        return ToggleResult.Success
    }

    fun isAlchemyIncentiveEnabled(): Boolean {
        return gameEngine.gameData.value?.sectPolicies?.alchemyIncentive ?: false
    }

    // ==================== 锻造激励 ====================

    suspend fun toggleForgeIncentive(): ToggleResult {
        val currentGameData = gameEngine.gameData.value ?: return ToggleResult.Error("数据不可用")
        val currentPolicies = currentGameData.sectPolicies
        val requiredStones = GameConfig.PolicyConfig.FORGE_INCENTIVE_COST

        if (!currentPolicies.forgeIncentive) {
            if (currentGameData.spiritStones < requiredStones) {
                return ToggleResult.Error("灵石不足${requiredStones}，无法开启锻造激励政策")
            }
            gameEngine.updateGameData {
                it.copy(
                    spiritStones = it.spiritStones - requiredStones,
                    sectPolicies = it.sectPolicies.copy(forgeIncentive = true)
                )
            }
        } else {
            gameEngine.updateGameData {
                it.copy(sectPolicies = it.sectPolicies.copy(forgeIncentive = false))
            }
        }
        return ToggleResult.Success
    }

    fun isForgeIncentiveEnabled(): Boolean {
        return gameEngine.gameData.value?.sectPolicies?.forgeIncentive ?: false
    }

    // ==================== 灵药培育 ====================

    suspend fun toggleHerbCultivation(): ToggleResult {
        val currentGameData = gameEngine.gameData.value ?: return ToggleResult.Error("数据不可用")
        val currentPolicies = currentGameData.sectPolicies
        val requiredStones = GameConfig.PolicyConfig.HERB_CULTIVATION_COST

        if (!currentPolicies.herbCultivation) {
            if (currentGameData.spiritStones < requiredStones) {
                return ToggleResult.Error("灵石不足${requiredStones}，无法开启灵药培育政策")
            }
            gameEngine.updateGameData {
                it.copy(
                    spiritStones = it.spiritStones - requiredStones,
                    sectPolicies = it.sectPolicies.copy(herbCultivation = true)
                )
            }
        } else {
            gameEngine.updateGameData {
                it.copy(sectPolicies = it.sectPolicies.copy(herbCultivation = false))
            }
        }
        return ToggleResult.Success
    }

    fun isHerbCultivationEnabled(): Boolean {
        return gameEngine.gameData.value?.sectPolicies?.herbCultivation ?: false
    }

    // ==================== 修行津贴 ====================

    suspend fun toggleCultivationSubsidy(): ToggleResult {
        val currentGameData = gameEngine.gameData.value ?: return ToggleResult.Error("数据不可用")
        val currentPolicies = currentGameData.sectPolicies
        val requiredStones = GameConfig.PolicyConfig.CULTIVATION_SUBSIDY_COST

        if (!currentPolicies.cultivationSubsidy) {
            if (currentGameData.spiritStones < requiredStones) {
                return ToggleResult.Error("灵石不足${requiredStones}，无法开启修行津贴政策")
            }
            gameEngine.updateGameData {
                it.copy(
                    spiritStones = it.spiritStones - requiredStones,
                    sectPolicies = it.sectPolicies.copy(cultivationSubsidy = true)
                )
            }
        } else {
            gameEngine.updateGameData {
                it.copy(sectPolicies = it.sectPolicies.copy(cultivationSubsidy = false))
            }
        }
        return ToggleResult.Success
    }

    fun isCultivationSubsidyEnabled(): Boolean {
        return gameEngine.gameData.value?.sectPolicies?.cultivationSubsidy ?: false
    }

    // ==================== 功法研习 ====================

    suspend fun toggleManualResearch(): ToggleResult {
        val currentGameData = gameEngine.gameData.value ?: return ToggleResult.Error("数据不可用")
        val currentPolicies = currentGameData.sectPolicies
        val requiredStones = GameConfig.PolicyConfig.MANUAL_RESEARCH_COST

        if (!currentPolicies.manualResearch) {
            if (currentGameData.spiritStones < requiredStones) {
                return ToggleResult.Error("灵石不足${requiredStones}，无法开启功法研习政策")
            }
            gameEngine.updateGameData {
                it.copy(
                    spiritStones = it.spiritStones - requiredStones,
                    sectPolicies = it.sectPolicies.copy(manualResearch = true)
                )
            }
        } else {
            gameEngine.updateGameData {
                it.copy(sectPolicies = it.sectPolicies.copy(manualResearch = false))
            }
        }
        return ToggleResult.Success
    }

    fun isManualResearchEnabled(): Boolean {
        return gameEngine.gameData.value?.sectPolicies?.manualResearch ?: false
    }

    // ==================== 副宗主智力加成 ====================

    fun getViceSectMasterIntelligenceBonus(viceSectMasterIntelligence: Int): Double {
        val baseIntelligence = GameConfig.PolicyConfig.VICE_SECT_MASTER_INTELLIGENCE_BASE
        val step = GameConfig.PolicyConfig.VICE_SECT_MASTER_INTELLIGENCE_STEP
        val bonusPerStep = GameConfig.PolicyConfig.VICE_SECT_MASTER_INTELLIGENCE_BONUS_PER_STEP
        return ((viceSectMasterIntelligence - baseIntelligence) / step.toDouble() * bonusPerStep).coerceAtLeast(0.0)
    }
}
