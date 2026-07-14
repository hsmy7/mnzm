package com.xianxia.sect.core.usecase

import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.engine.*
import com.xianxia.sect.core.model.SpiritStoneGrade
import com.xianxia.sect.core.wallet.SpiritStoneReason
import com.xianxia.sect.core.wallet.SpiritStoneSource
import com.xianxia.sect.core.wallet.DeductResult
import com.xianxia.sect.core.wallet.SpiritStoneWallet
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SectPolicyToggleUseCase @Inject constructor(
    private val gameEngine: GameEngine,
    private val spiritStoneWallet: SpiritStoneWallet
) {
    sealed class ToggleResult {
        data object Success : ToggleResult()
        data class Error(val message: String) : ToggleResult()
    }

    suspend fun toggleSpiritMineBoost(): ToggleResult {
        gameEngine.updateGameData {
            it.copy(sectPolicies = it.sectPolicies.copy(spiritMineBoost = !it.sectPolicies.spiritMineBoost))
        }
        // Checkpoint：灵矿政策变化后重设结算时间戳，防止政策追溯应用到未结算区间
        // （fix P1-2/P1-4：若不重置，toggle 后整个未结算区间都用新速率计算，可被利用反复获利）
        gameEngine.stateStore.update {
            val data = gameData
            val currentMonth = data.gameYear * 12 + data.gameMonth
            if (currentMonth > data.spiritMineLastSettledMonth) {
                gameData = data.copy(spiritMineLastSettledMonth = currentMonth)
            }
        }
        return ToggleResult.Success
    }

    fun isSpiritMineBoostEnabled(): Boolean {
        return gameEngine.gameData.value?.sectPolicies?.spiritMineBoost ?: false
    }

    fun getSpiritMineBoostEffect(): Double = GameConfig.PolicyConfig.SPIRIT_MINE_BOOST_BASE_EFFECT

    suspend fun toggleEnhancedSecurity(): ToggleResult {
        val requiredStones = GameConfig.PolicyConfig.ENHANCED_SECURITY_COST
        val gd = gameEngine.gameData.value ?: return ToggleResult.Error("游戏数据不可用")
        if (!gd.sectPolicies.enhancedSecurity) {
            if (!spiritStoneWallet.canAfford(requiredStones.toLong())) {
                return ToggleResult.Error("灵石不足${requiredStones}，无法开启增强治安政策")
            }
            var deductFailed = false
            gameEngine.stateStore.update {
                val result = spiritStoneWallet.deduct(this, requiredStones.toLong(), SpiritStoneGrade.LOW, SpiritStoneReason.PolicyCost, SpiritStoneSource.Internal)
                if (result !is DeductResult.Success) { deductFailed = true; return@update }
                gameData = gameData.copy(sectPolicies = gameData.sectPolicies.copy(enhancedSecurity = true))
            }
            if (deductFailed) return ToggleResult.Error("灵石不足${requiredStones}，无法开启增强治安政策")
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

    suspend fun toggleAlchemyIncentive(): ToggleResult {
        val requiredStones = GameConfig.PolicyConfig.ALCHEMY_INCENTIVE_COST
        val gd = gameEngine.gameData.value ?: return ToggleResult.Error("游戏数据不可用")
        if (!gd.sectPolicies.alchemyIncentive) {
            if (!spiritStoneWallet.canAfford(requiredStones.toLong())) {
                return ToggleResult.Error("灵石不足${requiredStones}，无法开启丹道激励政策")
            }
            var deductFailed = false
            gameEngine.stateStore.update {
                val result = spiritStoneWallet.deduct(this, requiredStones.toLong(), SpiritStoneGrade.LOW, SpiritStoneReason.PolicyCost, SpiritStoneSource.Internal)
                if (result !is DeductResult.Success) { deductFailed = true; return@update }
                gameData = gameData.copy(sectPolicies = gameData.sectPolicies.copy(alchemyIncentive = true))
            }
            if (deductFailed) return ToggleResult.Error("灵石不足${requiredStones}，无法开启丹道激励政策")
        } else {
            gameEngine.updateGameData {
                it.copy(sectPolicies = it.sectPolicies.copy(alchemyIncentive = false))
            }
        }
        // Checkpoint：政策变化后重算炼丹 duration
        gameEngine.checkpointAllProduction()
        return ToggleResult.Success
    }

    fun isAlchemyIncentiveEnabled(): Boolean {
        return gameEngine.gameData.value?.sectPolicies?.alchemyIncentive ?: false
    }

    suspend fun toggleForgeIncentive(): ToggleResult {
        val requiredStones = GameConfig.PolicyConfig.FORGE_INCENTIVE_COST
        val gd = gameEngine.gameData.value ?: return ToggleResult.Error("游戏数据不可用")
        if (!gd.sectPolicies.forgeIncentive) {
            if (!spiritStoneWallet.canAfford(requiredStones.toLong())) {
                return ToggleResult.Error("灵石不足${requiredStones}，无法开启锻造激励政策")
            }
            var deductFailed = false
            gameEngine.stateStore.update {
                val result = spiritStoneWallet.deduct(this, requiredStones.toLong(), SpiritStoneGrade.LOW, SpiritStoneReason.PolicyCost, SpiritStoneSource.Internal)
                if (result !is DeductResult.Success) { deductFailed = true; return@update }
                gameData = gameData.copy(sectPolicies = gameData.sectPolicies.copy(forgeIncentive = true))
            }
            if (deductFailed) return ToggleResult.Error("灵石不足${requiredStones}，无法开启锻造激励政策")
        } else {
            gameEngine.updateGameData {
                it.copy(sectPolicies = it.sectPolicies.copy(forgeIncentive = false))
            }
        }
        // Checkpoint：政策变化后重算锻造 duration
        gameEngine.checkpointAllProduction()
        return ToggleResult.Success
    }

    fun isForgeIncentiveEnabled(): Boolean {
        return gameEngine.gameData.value?.sectPolicies?.forgeIncentive ?: false
    }

    suspend fun toggleHerbCultivation(): ToggleResult {
        val requiredStones = GameConfig.PolicyConfig.HERB_CULTIVATION_COST
        val gd = gameEngine.gameData.value ?: return ToggleResult.Error("游戏数据不可用")
        if (!gd.sectPolicies.herbCultivation) {
            if (!spiritStoneWallet.canAfford(requiredStones.toLong())) {
                return ToggleResult.Error("灵石不足${requiredStones}，无法开启灵药培育政策")
            }
            var deductFailed = false
            gameEngine.stateStore.update {
                val result = spiritStoneWallet.deduct(this, requiredStones.toLong(), SpiritStoneGrade.LOW, SpiritStoneReason.PolicyCost, SpiritStoneSource.Internal)
                if (result !is DeductResult.Success) { deductFailed = true; return@update }
                gameData = gameData.copy(sectPolicies = gameData.sectPolicies.copy(herbCultivation = true))
            }
            if (deductFailed) return ToggleResult.Error("灵石不足${requiredStones}，无法开启灵药培育政策")
        } else {
            gameEngine.updateGameData {
                it.copy(sectPolicies = it.sectPolicies.copy(herbCultivation = false))
            }
        }
        // Checkpoint：政策变化后重算灵田/灵植 duration
        gameEngine.checkpointAllProduction()
        return ToggleResult.Success
    }

    fun isHerbCultivationEnabled(): Boolean {
        return gameEngine.gameData.value?.sectPolicies?.herbCultivation ?: false
    }

    suspend fun toggleCultivationSubsidy(): ToggleResult {
        val requiredStones = GameConfig.PolicyConfig.CULTIVATION_SUBSIDY_COST
        val gd = gameEngine.gameData.value ?: return ToggleResult.Error("游戏数据不可用")
        if (!gd.sectPolicies.cultivationSubsidy) {
            if (!spiritStoneWallet.canAfford(requiredStones.toLong())) {
                return ToggleResult.Error("灵石不足${requiredStones}，无法开启修行津贴政策")
            }
            var deductFailed = false
            gameEngine.stateStore.update {
                val result = spiritStoneWallet.deduct(this, requiredStones.toLong(), SpiritStoneGrade.LOW, SpiritStoneReason.PolicyCost, SpiritStoneSource.Internal)
                if (result !is DeductResult.Success) { deductFailed = true; return@update }
                gameData = gameData.copy(sectPolicies = gameData.sectPolicies.copy(cultivationSubsidy = true))
            }
            if (deductFailed) return ToggleResult.Error("灵石不足${requiredStones}，无法开启修行津贴政策")
        } else {
            gameEngine.updateGameData {
                it.copy(sectPolicies = it.sectPolicies.copy(cultivationSubsidy = false))
            }
        }
        // Checkpoint：修行津贴影响修炼速度，同步全部弟子检查点
        gameEngine.checkpointAllDisciples()
        return ToggleResult.Success
    }

    fun isCultivationSubsidyEnabled(): Boolean {
        return gameEngine.gameData.value?.sectPolicies?.cultivationSubsidy ?: false
    }

    suspend fun toggleManualResearch(): ToggleResult {
        val requiredStones = GameConfig.PolicyConfig.MANUAL_RESEARCH_COST
        val gd = gameEngine.gameData.value ?: return ToggleResult.Error("游戏数据不可用")
        if (!gd.sectPolicies.manualResearch) {
            if (!spiritStoneWallet.canAfford(requiredStones.toLong())) {
                return ToggleResult.Error("灵石不足${requiredStones}，无法开启功法研习政策")
            }
            var deductFailed = false
            gameEngine.stateStore.update {
                val result = spiritStoneWallet.deduct(this, requiredStones.toLong(), SpiritStoneGrade.LOW, SpiritStoneReason.PolicyCost, SpiritStoneSource.Internal)
                if (result !is DeductResult.Success) { deductFailed = true; return@update }
                gameData = gameData.copy(sectPolicies = gameData.sectPolicies.copy(manualResearch = true))
            }
            if (deductFailed) return ToggleResult.Error("灵石不足${requiredStones}，无法开启功法研习政策")
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

    fun getViceSectMasterIntelligenceBonus(viceSectMasterIntelligence: Int): Double {
        val baseIntelligence = GameConfig.PolicyConfig.VICE_SECT_MASTER_INTELLIGENCE_BASE
        val step = GameConfig.PolicyConfig.VICE_SECT_MASTER_INTELLIGENCE_STEP
        val bonusPerStep = GameConfig.PolicyConfig.VICE_SECT_MASTER_INTELLIGENCE_BONUS_PER_STEP
        return ((viceSectMasterIntelligence - baseIntelligence) / step.toDouble() * bonusPerStep).coerceAtLeast(0.0)
    }
}
