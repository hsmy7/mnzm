package com.xianxia.sect.ui.game

import android.util.Log
import androidx.lifecycle.viewModelScope
import com.xianxia.sect.data.cloud.CloudSaveEntry
import com.xianxia.sect.data.cloud.SaveBackendMode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

// ── 存档管理扩展（游戏内存档管理弹窗 SaveSlotDialog 消费）─────────────────────
// 同包扩展模式（SaveLoadViewModel*Ops 同口径）：不增 ViewModel 类函数数，UI 经公开入口调用。

/**
 * 删除一个本地存档槽（slot 1..6；slot 0 云会话伪槽不归此入口——云存档有自己的管理面）。
 *
 * 删除后刷新槽位列表（含 slot 0 云摘要合流），失败如实报错不静默。
 * 调用面 = 删除确认弹窗的确认键（破坏性操作已在 UI 层显式确认）。
 */
// TooGenericExceptionCaught：防御兜底——删除链异常源跨 IO/DB 不可枚举，降级为错误提示+日志留痕
@Suppress("TooGenericExceptionCaught")
fun SaveLoadViewModel.deleteSlot(slot: Int) {
    if (slot < MIN_LOCAL_SLOT) {
        Log.w(SaveLoadViewModelConstants.TAG, "deleteSlot refused: slot=$slot 不是本地槽位")
        return
    }
    viewModelScope.launch(ioDispatcher.dispatcher) {
        try {
            when (val result = persistenceFacade.storageFacade.delete(slot)) {
                is com.xianxia.sect.data.unified.SaveResult.Failure -> {
                    Log.w(SaveLoadViewModelConstants.TAG, "deleteSlot refused: slot=$slot — ${result.message}")
                    showError("删除存档失败: ${result.message}")
                    return@launch
                }
                else -> Log.i(SaveLoadViewModelConstants.TAG, "save slot deleted: slot=$slot")
            }
            refreshSaveSlots()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(SaveLoadViewModelConstants.TAG, "deleteSlot failed: slot=$slot", e)
            showError("删除存档失败: ${e.message}")
        }
    }
}

/**
 * 查询云端槽位列表（SR-3：SaveBackend.list() → slot_N 映射 + 摘要）。
 *
 * **LEGACY 短路（硬红线）**：默认模式零查询零 UI——与原主菜单选档页行为
 * 逐行一致。查询失败降级空列表（弹窗其余功能不受影响，云槽位区不显示，如实日志留痕）。
 */
// 防御兜底: 列表查询异常源跨 IO/SDK 不可枚举, 降级空列表+日志留痕, 非静默吞噬
@Suppress("TooGenericExceptionCaught")
suspend fun SaveLoadViewModel.queryCloudSlotEntries(): List<CloudSaveEntry> {
    if (persistenceFacade.saveBackendModeProvider.current() == SaveBackendMode.LEGACY) {
        Log.d(SaveLoadViewModelConstants.TAG, "cloud slot list skipped: mode=LEGACY")
        return emptyList()
    }
    return try {
        when (val result = persistenceFacade.saveBackend.list()) {
            is com.xianxia.sect.data.cloud.SaveBackendResult.Success -> result.data
            is com.xianxia.sect.data.cloud.SaveBackendResult.Failure -> {
                Log.w(SaveLoadViewModelConstants.TAG, "cloud slot list failed: ${result.message}")
                emptyList()
            }
        }
    } catch (e: CancellationException) {
        throw e // 取消穿透: 弹窗关闭取消时上抛
    } catch (e: Exception) {
        Log.e(SaveLoadViewModelConstants.TAG, "cloud slot list error", e)
        emptyList()
    }
}

/** 本地槽位下界（slot 0 是云会话伪槽），与 app 面 AutoEntryResolver.MIN_LOCAL_SLOT 同口径 */
private const val MIN_LOCAL_SLOT = 1
