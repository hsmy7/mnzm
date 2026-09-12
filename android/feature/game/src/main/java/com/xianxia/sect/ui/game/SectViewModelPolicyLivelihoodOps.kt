package com.xianxia.sect.ui.game

import androidx.lifecycle.viewModelScope
import com.xianxia.sect.core.usecase.SectPolicyToggleUseCase
import kotlinx.coroutines.launch
import com.xianxia.sect.core.usecase.isFrugalityEnabled
import com.xianxia.sect.core.usecase.isSpiritSpringEnabled
import com.xianxia.sect.core.usecase.toggleFrugality
import com.xianxia.sect.core.usecase.toggleSpiritSpring

// ── 民生类（灵泉/节俭/德育/仁政）政策开关扩展（自 SectViewModel 拆出，行为零变更）──────────────────
// batch-02 TooManyFunctions 收敛（类内 ≤19）外移为同包扩展，调用点语法不变。
fun SectViewModel.toggleSpiritSpring(): Boolean {
    viewModelScope.launch {
        val result = sectPolicyToggle.toggleSpiritSpring()
        if (result is SectPolicyToggleUseCase.ToggleResult.Error) showError(result.message)
    }
    return true
}

fun SectViewModel.isSpiritSpringEnabled(): Boolean = sectPolicyToggle.isSpiritSpringEnabled()
fun SectViewModel.toggleFrugality(): Boolean {
    viewModelScope.launch {
        val result = sectPolicyToggle.toggleFrugality()
        if (result is SectPolicyToggleUseCase.ToggleResult.Error) showError(result.message)
    }
    return true
}

fun SectViewModel.isFrugalityEnabled(): Boolean = sectPolicyToggle.isFrugalityEnabled()
fun SectViewModel.toggleMoralEducation(): Boolean {
    viewModelScope.launch {
        val result = sectPolicyToggle.toggleMoralEducation()
        if (result is SectPolicyToggleUseCase.ToggleResult.Error) showError(result.message)
    }
    return true
}

fun SectViewModel.isMoralEducationEnabled(): Boolean = sectPolicyToggle.isMoralEducationEnabled()
fun SectViewModel.toggleBenevolentGovernance(): Boolean {
    viewModelScope.launch {
        val result = sectPolicyToggle.toggleBenevolentGovernance()
        if (result is SectPolicyToggleUseCase.ToggleResult.Error) showError(result.message)
    }
    return true
}

fun SectViewModel.isBenevolentGovernanceEnabled(): Boolean = sectPolicyToggle.isBenevolentGovernanceEnabled()
