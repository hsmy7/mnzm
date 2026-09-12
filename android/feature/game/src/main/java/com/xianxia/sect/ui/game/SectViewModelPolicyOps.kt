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

// ── 开发/生产类（灵矿/安保/炼丹/锻造/灵植/补贴/研功）政策开关扩展（自 SectViewModel 拆出，行为零变更）──────────────────
// batch-02 TooManyFunctions 收敛（类内 ≤19）外移为同包扩展，调用点语法不变。
fun SectViewModel.toggleSpiritMineBoost(): Boolean {
    if (gameEngine.gameData.value == null) return false
    viewModelScope.launch {
        val result = sectPolicyToggle.toggleSpiritMineBoost()
        if (result is SectPolicyToggleUseCase.ToggleResult.Error) showError(result.message)
    }
    return true
}

fun SectViewModel.isSpiritMineBoostEnabled(): Boolean = sectPolicyToggle.isSpiritMineBoostEnabled()
fun SectViewModel.toggleEnhancedSecurity(): Boolean {
    if (gameEngine.gameData.value == null) return false
    viewModelScope.launch {
        val result = sectPolicyToggle.toggleEnhancedSecurity()
        if (result is SectPolicyToggleUseCase.ToggleResult.Error) showError(result.message)
    }
    return true
}

fun SectViewModel.isEnhancedSecurityEnabled(): Boolean = sectPolicyToggle.isEnhancedSecurityEnabled()
fun SectViewModel.toggleAlchemyIncentive(): Boolean {
    if (gameEngine.gameData.value == null) return false
    viewModelScope.launch {
        val result = sectPolicyToggle.toggleAlchemyIncentive()
        if (result is SectPolicyToggleUseCase.ToggleResult.Error) showError(result.message)
    }
    return true
}

fun SectViewModel.isAlchemyIncentiveEnabled(): Boolean = sectPolicyToggle.isAlchemyIncentiveEnabled()
fun SectViewModel.toggleForgeIncentive(): Boolean {
    if (gameEngine.gameData.value == null) return false
    viewModelScope.launch {
        val result = sectPolicyToggle.toggleForgeIncentive()
        if (result is SectPolicyToggleUseCase.ToggleResult.Error) showError(result.message)
    }
    return true
}

fun SectViewModel.isForgeIncentiveEnabled(): Boolean = sectPolicyToggle.isForgeIncentiveEnabled()
fun SectViewModel.toggleHerbCultivation(): Boolean {
    if (gameEngine.gameData.value == null) return false
    viewModelScope.launch {
        val result = sectPolicyToggle.toggleHerbCultivation()
        if (result is SectPolicyToggleUseCase.ToggleResult.Error) showError(result.message)
    }
    return true
}

fun SectViewModel.isHerbCultivationEnabled(): Boolean = sectPolicyToggle.isHerbCultivationEnabled()
fun SectViewModel.toggleCultivationSubsidy(): Boolean {
    if (gameEngine.gameData.value == null) return false
    viewModelScope.launch {
        val result = sectPolicyToggle.toggleCultivationSubsidy()
        if (result is SectPolicyToggleUseCase.ToggleResult.Error) showError(result.message)
    }
    return true
}

fun SectViewModel.isCultivationSubsidyEnabled(): Boolean = sectPolicyToggle.isCultivationSubsidyEnabled()
fun SectViewModel.toggleManualResearch(): Boolean {
    if (gameEngine.gameData.value == null) return false
    viewModelScope.launch {
        val result = sectPolicyToggle.toggleManualResearch()
        if (result is SectPolicyToggleUseCase.ToggleResult.Error) showError(result.message)
    }
    return true
}

fun SectViewModel.isManualResearchEnabled(): Boolean = sectPolicyToggle.isManualResearchEnabled()
