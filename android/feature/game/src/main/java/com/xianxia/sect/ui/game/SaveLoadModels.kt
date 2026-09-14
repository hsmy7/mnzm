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

    /** 游戏循环停止等待超时（读档/重启前必须等待旧循环 finally 彻底完成，
     *  玉符 checkpointNow 绝对值覆盖写晚于快照替换会污染新档，见 performLoadToSlot） */
    const val GAME_LOOP_STOP_TIMEOUT_MS = 5_000L

    const val PROGRESS_START = 0f
    const val PROGRESS_SAVE_COMPLETE = 0.40f
    const val PROGRESS_DATA_PRELOAD = 0.55f
    const val PROGRESS_SPRITE_PRELOAD = 0.70f
    /** 地图瓦片数据生成（原 PROGRESS_MAP_PRELOAD 拆分为更细粒度） */
    const val PROGRESS_TILE_GEN = 0.90f
    /** 兼容别名，旧引用（GameActivity 等）使用 */
    const val PROGRESS_MAP_PRELOAD = PROGRESS_TILE_GEN
    const val PROGRESS_COMPLETE = 1f

    // 进度常量仅保留有真实推进点的档位（boot 期间进度由
    // BootSequenceController 回调线性映射）——防止误用无推进点的
    // 伪装阶段常量。

    /** 预加载阶段标签 */
    const val PHASE_INIT = "正在初始化引擎..."
    const val PHASE_DATA_PRELOAD = "正在加载宗门数据..."
    const val PHASE_SPRITE_PRELOAD = "正在准备界面资源..."
    const val PHASE_CLOUD_SYNC = "正在同步云存档..."
}
