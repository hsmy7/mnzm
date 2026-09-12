package com.xianxia.sect.ui.game

import androidx.lifecycle.viewModelScope
import com.xianxia.sect.core.usecase.SectPolicyToggleUseCase
import kotlinx.coroutines.launch
import com.xianxia.sect.core.usecase.isAlchemyIncentiveEnabled
import com.xianxia.sect.core.usecase.isEnhancedSecurityEnabled
import com.xianxia.sect.core.usecase.isForgeIncentiveEnabled
import com.xianxia.sect.core.usecase.isHerbCultivationEnabled
import com.xianxia.sect.core.usecase.isManualResearchEnabled
import com.xianxia.sect.core.usecase.toggleAlchemyIncentive
import com.xianxia.sect.core.usecase.toggleEnhancedSecurity
import com.xianxia.sect.core.usecase.toggleForgeIncentive
import com.xianxia.sect.core.usecase.toggleHerbCultivation
import com.xianxia.sect.core.usecase.toggleManualResearch

// ── 开发/生产类（灵矿/安保/炼丹/锻造/灵植/补贴/研功）政策开关扩展（自 ProductionViewModel 拆出，行为零变更）──────────────
// batch-02 TooManyFunctions 收敛（类内 ≤19）外移为同包扩展，调用点语法不变。
fun ProductionViewModel.toggleSpiritMineBoost(): Boolean {
    if (gameEngine.gameDataSnapshot == null) return false
    viewModelScope.launch {
        val result = sectPolicyToggle.toggleSpiritMineBoost()
        if (result is SectPolicyToggleUseCase.ToggleResult.Error) showError(result.message)
    }
    return true
}

fun ProductionViewModel.isSpiritMineBoostEnabled(): Boolean = sectPolicyToggle.isSpiritMineBoostEnabled()
fun ProductionViewModel.toggleEnhancedSecurity(): Boolean {
    if (gameEngine.gameDataSnapshot == null) return false
    viewModelScope.launch {
        val result = sectPolicyToggle.toggleEnhancedSecurity()
        if (result is SectPolicyToggleUseCase.ToggleResult.Error) showError(result.message)
    }
    return true
}

fun ProductionViewModel.isEnhancedSecurityEnabled(): Boolean = sectPolicyToggle.isEnhancedSecurityEnabled()
fun ProductionViewModel.toggleAlchemyIncentive(): Boolean {
    if (gameEngine.gameDataSnapshot == null) return false
    viewModelScope.launch {
        val result = sectPolicyToggle.toggleAlchemyIncentive()
        if (result is SectPolicyToggleUseCase.ToggleResult.Error) showError(result.message)
    }
    return true
}

fun ProductionViewModel.isAlchemyIncentiveEnabled(): Boolean = sectPolicyToggle.isAlchemyIncentiveEnabled()
fun ProductionViewModel.toggleForgeIncentive(): Boolean {
    if (gameEngine.gameDataSnapshot == null) return false
    viewModelScope.launch {
        val result = sectPolicyToggle.toggleForgeIncentive()
        if (result is SectPolicyToggleUseCase.ToggleResult.Error) showError(result.message)
    }
    return true
}

fun ProductionViewModel.isForgeIncentiveEnabled(): Boolean = sectPolicyToggle.isForgeIncentiveEnabled()
fun ProductionViewModel.toggleHerbCultivation(): Boolean {
    if (gameEngine.gameDataSnapshot == null) return false
    viewModelScope.launch {
        val result = sectPolicyToggle.toggleHerbCultivation()
        if (result is SectPolicyToggleUseCase.ToggleResult.Error) showError(result.message)
    }
    return true
}

fun ProductionViewModel.isHerbCultivationEnabled(): Boolean = sectPolicyToggle.isHerbCultivationEnabled()
fun ProductionViewModel.toggleCultivationSubsidy(): Boolean {
    if (gameEngine.gameDataSnapshot == null) return false
    viewModelScope.launch {
        val result = sectPolicyToggle.toggleCultivationSubsidy()
        if (result is SectPolicyToggleUseCase.ToggleResult.Error) showError(result.message)
    }
    return true
}

fun ProductionViewModel.isCultivationSubsidyEnabled(): Boolean = sectPolicyToggle.isCultivationSubsidyEnabled()
fun ProductionViewModel.toggleManualResearch(): Boolean {
    if (gameEngine.gameDataSnapshot == null) return false
    viewModelScope.launch {
        val result = sectPolicyToggle.toggleManualResearch()
        if (result is SectPolicyToggleUseCase.ToggleResult.Error) showError(result.message)
    }
    return true
}

fun ProductionViewModel.isManualResearchEnabled(): Boolean = sectPolicyToggle.isManualResearchEnabled()
