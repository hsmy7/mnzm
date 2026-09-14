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

    companion object {
        /** 事件通道容量（审计 P3-19）：有界防无界缓冲，余量防连续调用丢失 */
        private const val CHANNEL_CAPACITY = 64
    }

    protected val sharingStarted = SharingStarted.WhileSubscribed(5_000)

    // showError/showSuccess/showCapacityWarning/launchElderAction 为 internal（原 protected）：
    // batch-02 拆分把 ViewModel 操作流外移到同包扩展函数（§2.29 StorageEngine 先例），
    // 扩展不在子类继承链上无法访问 protected 成员；事件通道本体（Channel.trySend）
    // 线程安全且行为不变，仅模块内可见性放宽。

    // 审计 P3-19：UNLIMITED → 有界 64（trySend 满即丢——UI 提示事件可容忍
    // 丢弃，不可容忍无界缓冲）；容量留余量避免连续调用丢首事件
    private val _errorEvents = Channel<String>(CHANNEL_CAPACITY)
    val errorEvents = _errorEvents.receiveAsFlow()

    private val _successEvents = Channel<String>(CHANNEL_CAPACITY)
    val successEvents = _successEvents.receiveAsFlow()

    /**
     * 仓库容量不足提示事件——所有"手动操作获得物品"的途径（领取按钮/储物袋开启/
     * 商人购买等）容量不足时统一通过 [showCapacityWarning] 弹出提示框。
     * 未来新增领取按钮只需调用本方法即可获得统一提示框。
     */
    private val _capacityWarningEvents = Channel<String>(CHANNEL_CAPACITY)
    val capacityWarningEvents = _capacityWarningEvents.receiveAsFlow()

    internal fun showError(message: String) {
        _errorEvents.trySend(message)
    }

    internal fun showSuccess(message: String) {
        _successEvents.trySend(message)
    }

    /** 弹出统一"仓库容量不足"提示框（标题/知道了按钮/点屏幕外关闭由 GameOverlayHost 渲染） */
    internal open fun showCapacityWarning(message: String) {
        _capacityWarningEvents.trySend(message)
    }

    @Suppress("TooGenericExceptionCaught") // 防御兜底: 异常源不可枚举, 失败降级继续, 非静默吞噬
    internal fun launchElderAction(
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
