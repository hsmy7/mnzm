package com.xianxia.sect.ui.game

import androidx.lifecycle.viewModelScope
import com.xianxia.sect.core.usecase.SectPolicyToggleUseCase
import kotlinx.coroutines.launch
import com.xianxia.sect.core.usecase.isCurfewEnabled
import com.xianxia.sect.core.usecase.isRelaxedMgmtEnabled
import com.xianxia.sect.core.usecase.isStrictTrainingEnabled
import com.xianxia.sect.core.usecase.toggleCurfew
import com.xianxia.sect.core.usecase.toggleRelaxedMgmt
import com.xianxia.sect.core.usecase.toggleStrictTraining

// ── 治理类（苦修/宵禁/严训/宽管）政策开关扩展（自 ProductionViewModel 拆出，行为零变更）──────────────
// batch-02 TooManyFunctions 收敛（类内 ≤19）外移为同包扩展，调用点语法不变。
fun ProductionViewModel.toggleAsceticTraining(): Boolean {
    viewModelScope.launch {
        val result = sectPolicyToggle.toggleAsceticTraining()
        if (result is SectPolicyToggleUseCase.ToggleResult.Error) showError(result.message)
    }
    return true
}

fun ProductionViewModel.isAsceticTrainingEnabled(): Boolean = sectPolicyToggle.isAsceticTrainingEnabled()
fun ProductionViewModel.toggleCurfew(): Boolean {
    viewModelScope.launch {
        val result = sectPolicyToggle.toggleCurfew()
        if (result is SectPolicyToggleUseCase.ToggleResult.Error) showError(result.message)
    }
    return true
}

fun ProductionViewModel.isCurfewEnabled(): Boolean = sectPolicyToggle.isCurfewEnabled()
fun ProductionViewModel.toggleStrictTraining(): Boolean {
    viewModelScope.launch {
        val result = sectPolicyToggle.toggleStrictTraining()
        if (result is SectPolicyToggleUseCase.ToggleResult.Error) showError(result.message)
    }
    return true
}

fun ProductionViewModel.isStrictTrainingEnabled(): Boolean = sectPolicyToggle.isStrictTrainingEnabled()
fun ProductionViewModel.toggleRelaxedMgmt(): Boolean {
    viewModelScope.launch {
        val result = sectPolicyToggle.toggleRelaxedMgmt()
        if (result is SectPolicyToggleUseCase.ToggleResult.Error) showError(result.message)
    }
    return true
}

fun ProductionViewModel.isRelaxedMgmtEnabled(): Boolean = sectPolicyToggle.isRelaxedMgmtEnabled()
