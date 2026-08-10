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
 * A6（2026-08-05）：主菜单云读档覆盖确认请求。
 *
 * 云读档目标槽位已有本地存档时不静默覆盖——展示确认框，玩家确认后才
 * 落盘+读档（拒绝则中止，本地存档原样保留）。
 */
data class CloudOverwriteRequest(
    val slot: Int,
    val cloudYear: Int,
    val cloudMonth: Int,
    val cloudSectName: String
)

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
    const val PROGRESS_ENGINE_INIT = 0.15f
    const val PROGRESS_DATA_LOAD = 0.25f
    const val PROGRESS_SAVE_COMPLETE = 0.40f
    const val PROGRESS_RESTART_DATA_LOAD = 0.50f
    const val PROGRESS_DATA_PRELOAD = 0.55f
    const val PROGRESS_SPRITE_PRELOAD = 0.70f
    const val PROGRESS_GAME_LOOP_START = 0.80f
    /** 地图瓦片数据生成（原 PROGRESS_MAP_PRELOAD 拆分为更细粒度） */
    const val PROGRESS_TILE_GEN = 0.90f
    /** 兼容别名，旧引用（GameActivity 等）使用 */
    const val PROGRESS_MAP_PRELOAD = PROGRESS_TILE_GEN
    const val PROGRESS_COMPLETE = 1f

    /** 预加载阶段标签 */
    const val PHASE_INIT = "正在初始化引擎..."
    const val PHASE_DATA_PRELOAD = "正在加载宗门数据..."
    const val PHASE_SPRITE_PRELOAD = "正在准备界面资源..."
    const val PHASE_CLOUD_SYNC = "正在同步云存档..."
    const val PHASE_READY = "即将进入宗门..."
}
