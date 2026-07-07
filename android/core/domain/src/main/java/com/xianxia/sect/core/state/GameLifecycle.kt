package com.xianxia.sect.core.state

/**
 * 游戏生命周期状态机。
 *
 * 纯运行时状态，不随存档保存。
 * 状态转移方向固定：每个状态只能向后转移（ordinal +1），不自反。
 * 用于驱动 UI 层（LoadingScreen → MainGameScreen 过渡）
 * 和 SaveFacade 等需查询游戏是否已运行的模块。
 *
 * 转移规则：
 * UNINITIALIZED ──loadData()──→ DATA_READY
 * DATA_READY ──preloadResources()+startGameLoop()──→ SYSTEMS_READY
 * SYSTEMS_READY ──generateMapData()──→ MAP_READY
 * MAP_READY ──setGameLifecycle(PLAYING)──→ PLAYING
 */
enum class GameLifecycle {
    /** 初始状态：引擎未初始化，无存档数据 */
    UNINITIALIZED,

    /** 存档数据已加载完毕（gameData、弟子、物品等已就绪） */
    DATA_READY,

    /** 子系统初始化完毕（资源预加载、缓存预热、游戏循环启动） */
    SYSTEMS_READY,

    /** 地图瓦片数据已生成，UI 可以安全地从 LoadingScreen 过渡到 MainGameScreen */
    MAP_READY,

    /** 游戏正式开始，玩家可交互，游戏循环正常 tick */
    PLAYING
}
