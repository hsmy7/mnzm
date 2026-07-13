package com.xianxia.sect.core.state

/**
 * 游戏启动序列 — 单向 forward-only，启动完成后凝固。
 *
 * 供 UI 层观察过渡动画和 LoadingScreen 阶段展示。
 * 由 [BootSequenceController] 内部驱动，外部只读。
 *
 * 转移规则：
 * UNINITIALIZED ──loadData()──→ DATA_READY
 * DATA_READY ──preloadResources()+startGameLoop()──→ SYSTEMS_READY
 * SYSTEMS_READY ──generateMapData()──→ MAP_READY
 * MAP_READY ──游戏完全就绪──→ BOOT_COMPLETE
 */
enum class BootPhase {
    /** 初始状态：引擎未初始化，无存档数据 */
    UNINITIALIZED,

    /** 存档数据已加载完毕（gameData、弟子、物品等已就绪） */
    DATA_READY,

    /** 子系统初始化完毕（资源预加载、缓存预热、游戏循环启动） */
    SYSTEMS_READY,

    /** 地图瓦片数据已生成，UI 可以安全地从 LoadingScreen 过渡到 MainGameScreen */
    MAP_READY,

    /** 启动完成终态，游戏完全就绪 */
    BOOT_COMPLETE
}

/**
 * 运行时状态 — 可循环，有明确的 reload 语义。
 *
 * 与 [BootPhase] 正交：
 * - BootPhase 表示"启动到哪一步了"
 * - RunState 表示"当前是否在游戏中"
 */
enum class RunState {
    /** 未加载任何存档/新游戏 */
    IDLE,

    /** 加载中（展示 LoadingScreen） */
    LOADING,

    /** 游戏中，正常 tick */
    PLAYING,

    /** 正在重新加载中（从 PLAYING 回退） */
    RELOADING
}

/**
 * 游戏生命周期状态机（兼容层）。
 *
 * 由 [BootPhase] + [RunState] 组合映射而来，保持现有代码兼容。
 * 新代码应优先使用 [BootPhase] 和 [RunState]。
 *
 * 映射规则：
 * - UNINITIALIZED ← BootPhase < DATA_READY
 * - DATA_READY ← BootPhase.DATA_READY
 * - SYSTEMS_READY ← BootPhase.SYSTEMS_READY
 * - MAP_READY ← BootPhase.MAP_READY
 * - PLAYING ← BootPhase.BOOT_COMPLETE + RunState.PLAYING
 */
@Deprecated("Use BootPhase/RunState instead. Kept for backward compatibility.")
enum class GameLifecycle {
    UNINITIALIZED,
    DATA_READY,
    SYSTEMS_READY,
    MAP_READY,
    PLAYING
}
