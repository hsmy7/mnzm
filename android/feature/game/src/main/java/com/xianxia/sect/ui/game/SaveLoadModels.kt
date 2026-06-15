package com.xianxia.sect.ui.game

/**
 * SaveLoadViewModel 相关的模型与常量
 * 从 SaveLoadViewModel.kt 提取
 */

/**
 * 存档/加载组合状态
 */
data class SaveLoadState(
    val isSaving: Boolean = false,
    val isLoading: Boolean = false,
    val pendingSlot: Int? = null,
    val pendingAction: String? = null
) {
    val isBusy: Boolean get() = isSaving || isLoading
}

/**
 * SaveLoadViewModel 使用的加载进度常量与运行配置常量
 */
object SaveLoadViewModelConstants {
    const val TAG = "SaveLoadViewModel"
    const val MB = 1024 * 1024L
    const val MAX_CONSECUTIVE_SAVE_FAILURES = 3
    const val SAVE_LOCK_TIMEOUT_MS = 60_000L

    const val PROGRESS_START = 0f
    const val PROGRESS_ENGINE_INIT = 0.15f
    const val PROGRESS_DATA_LOAD = 0.25f
    const val PROGRESS_SAVE_COMPLETE = 0.40f
    const val PROGRESS_RESTART_DATA_LOAD = 0.50f
    const val PROGRESS_MANUAL_PRELOAD = 0.50f
    const val PROGRESS_RECIPE_PRELOAD = 0.60f
    const val PROGRESS_BITMAP_PRELOAD = 0.70f
    const val PROGRESS_GAME_LOOP_START = 0.80f
    const val PROGRESS_MAP_PRELOAD = 0.90f
    const val PROGRESS_COMPLETE = 1f
}
