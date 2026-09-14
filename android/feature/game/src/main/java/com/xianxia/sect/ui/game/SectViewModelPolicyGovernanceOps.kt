package com.xianxia.sect.ui.game

import androidx.lifecycle.viewModelScope
import com.xianxia.sect.core.usecase.SectPolicyToggleUseCase
import kotlinx.coroutines.launch
import com.xianxia.sect.core.usecase.isCurfewEnabled
import com.xianxia.sect.core.usecase.isRelaxedMgmtEnabled
import com.xianxia.sect.core.usecase.isRewardPunishEnabled
import com.xianxia.sect.core.usecase.isStrictTrainingEnabled
import com.xianxia.sect.core.usecase.toggleCurfew
import com.xianxia.sect.core.usecase.toggleRelaxedMgmt
import com.xianxia.sect.core.usecase.toggleRewardPunish
import com.xianxia.sect.core.usecase.toggleStrictTraining

// ── 治理类（纳新/苦修/宵禁/赏罚/严训/宽管）政策开关扩展（自 SectViewModel 拆出，行为零变更）──────────────────
// batch-02 TooManyFunctions 收敛（类内 ≤19）外移为同包扩展，调用点语法不变。
fun SectViewModel.toggleOpenRecruitment(): Boolean {
    viewModelScope.launch {
        val result = sectPolicyToggle.toggleOpenRecruitment()
        if (result is SectPolicyToggleUseCase.ToggleResult.Error) showError(result.message)
    }
    return true
}

fun SectViewModel.isOpenRecruitmentEnabled(): Boolean = sectPolicyToggle.isOpenRecruitmentEnabled()
fun SectViewModel.toggleAsceticTraining(): Boolean {
    viewModelScope.launch {
        val result = sectPolicyToggle.toggleAsceticTraining()
        if (result is SectPolicyToggleUseCase.ToggleResult.Error) showError(result.message)
    }
    return true
}

fun SectViewModel.isAsceticTrainingEnabled(): Boolean = sectPolicyToggle.isAsceticTrainingEnabled()
fun SectViewModel.toggleCurfew(): Boolean {
    viewModelScope.launch {
        val result = sectPolicyToggle.toggleCurfew()
        if (result is SectPolicyToggleUseCase.ToggleResult.Error) showError(result.message)
    }
    return true
}

fun SectViewModel.isCurfewEnabled(): Boolean = sectPolicyToggle.isCurfewEnabled()
fun SectViewModel.toggleRewardPunish(): Boolean {
    viewModelScope.launch {
        val result = sectPolicyToggle.toggleRewardPunish()
        if (result is SectPolicyToggleUseCase.ToggleResult.Error) showError(result.message)
    }
    return true
}

fun SectViewModel.isRewardPunishEnabled(): Boolean = sectPolicyToggle.isRewardPunishEnabled()
fun SectViewModel.toggleStrictTraining(): Boolean {
    viewModelScope.launch {
        val result = sectPolicyToggle.toggleStrictTraining()
        if (result is SectPolicyToggleUseCase.ToggleResult.Error) showError(result.message)
    }
    return true
}

fun SectViewModel.isStrictTrainingEnabled(): Boolean = sectPolicyToggle.isStrictTrainingEnabled()
fun SectViewModel.toggleRelaxedMgmt(): Boolean {
    viewModelScope.launch {
        val result = sectPolicyToggle.toggleRelaxedMgmt()
        if (result is SectPolicyToggleUseCase.ToggleResult.Error) showError(result.message)
    }
    return true
}

fun SectViewModel.isRelaxedMgmtEnabled(): Boolean = sectPolicyToggle.isRelaxedMgmtEnabled()
