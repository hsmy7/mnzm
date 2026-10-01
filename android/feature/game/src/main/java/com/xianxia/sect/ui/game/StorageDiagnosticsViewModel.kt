package com.xianxia.sect.ui.game

import androidx.lifecycle.viewModelScope
import com.xianxia.sect.data.engine.StorageDiagnosticsFacade
import com.xianxia.sect.data.engine.StorageDiagnosticsReport
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 存档诊断 ViewModel（SS3-e）：设置页诊断入口的只读数据装载。
 *
 * 打开诊断对话框时 [refresh] 一次拉全快照（计数器 + 最近保存变更 + 归档概要），
 * 全程只读，不触碰存档主流程。
 */
@HiltViewModel
class StorageDiagnosticsViewModel @Inject constructor(
    private val diagnosticsFacade: StorageDiagnosticsFacade
) : BaseViewModel() {

    private val _report = MutableStateFlow<StorageDiagnosticsReport?>(null)
    val report: StateFlow<StorageDiagnosticsReport?> = _report.asStateFlow()

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    /** 拉取诊断快照；失败经统一错误通道提示，旧报告保留不清空 */
    @Suppress("TooGenericExceptionCaught") // 防御兜底: 诊断读取异常源跨 IO/DB 不可枚举, 降级提示+留痕
    fun refresh() {
        if (_loading.value) return
        viewModelScope.launch {
            _loading.value = true
            try {
                _report.value = diagnosticsFacade.snapshot()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                // 防御兜底: 诊断读取异常源跨 IO/DB 不可枚举, 降级为错误提示+旧报告保留
                showError("存档诊断读取失败：${e.message ?: "未知错误"}")
            } finally {
                _loading.value = false
            }
        }
    }
}
