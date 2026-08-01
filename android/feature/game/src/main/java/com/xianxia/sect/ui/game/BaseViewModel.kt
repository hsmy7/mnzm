package com.xianxia.sect.ui.game

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xianxia.sect.core.usecase.ElderManagementUseCase
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

abstract class BaseViewModel : ViewModel() {

    protected val sharingStarted = SharingStarted.WhileSubscribed(5_000)

    // Channel.UNLIMITED 用于错误/成功事件队列，避免连续调用时丢失事件
    private val _errorEvents = Channel<String>(Channel.UNLIMITED)
    val errorEvents = _errorEvents.receiveAsFlow()

    private val _successEvents = Channel<String>(Channel.UNLIMITED)
    val successEvents = _successEvents.receiveAsFlow()

    /**
     * 仓库容量不足提示事件——所有"手动操作获得物品"的途径（领取按钮/储物袋开启/
     * 商人购买等）容量不足时统一通过 [showCapacityWarning] 弹出提示框。
     * 未来新增领取按钮只需调用本方法即可获得统一提示框。
     */
    private val _capacityWarningEvents = Channel<String>(Channel.UNLIMITED)
    val capacityWarningEvents = _capacityWarningEvents.receiveAsFlow()

    protected fun showError(message: String) {
        _errorEvents.trySend(message)
    }

    protected fun showSuccess(message: String) {
        _successEvents.trySend(message)
    }

    /** 弹出统一"仓库容量不足"提示框（标题/知道了按钮/点屏幕外关闭由 GameOverlayHost 渲染） */
    protected open fun showCapacityWarning(message: String) {
        _capacityWarningEvents.trySend(message)
    }

    protected fun launchElderAction(
        action: suspend () -> ElderManagementUseCase.ElderResult,
        errorMessage: String = "操作失败"
    ) {
        viewModelScope.launch(Dispatchers.Default) {
            try {
                when (val result = action()) {
                    is ElderManagementUseCase.ElderResult.Success -> showSuccess(result.message)
                    is ElderManagementUseCase.ElderResult.Error -> showError(result.message)
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                showError(e.message ?: errorMessage)
            }
        }
    }
}
