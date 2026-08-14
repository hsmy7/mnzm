package com.xianxia.sect.ui.game.sect

/**
 * 脏帧跳过判定 — 渲染循环静止画面跳过渲染的纯函数（2026-08-14 平板省电）。
 *
 * ## 背景
 * 旧渲染循环画面静止也按节拍满负载渲染（无脏帧跳过）——放置类挂机场景
 * （用户盯着数字、地图静止）渲染负载本应为零。行业对标：Unity OnDemandRendering
 * 官方点名放置类低活动期降帧省电（renderFrameInterval）；Android Choreographer
 * 官方 30fps 节流样例（battery efficiency 最佳实践）——脏帧跳过是"帧率降到 0"的延伸。
 *
 * ## 守卫完备性（为什么不会漏画）
 * - 相机动 → [FrameSkipInputs.cameraDirty] = true → 不跳
 * - 地图数据/建筑/作物/拆除/预览/网格/淡入插值 alpha 任一变化 → RenderFrame
 *   经 updateRenderState 替换引用 → [FrameSkipInputs.frameChanged] = true → 不跳
 * - 命令总线建筑直达推送 → [FrameSkipInputs.buildingBusDirty] = true（跳过帧
 *   不消费总线，脏标记保持 true 直至渲染）→ 不跳
 * - 地图淡入中 → [FrameSkipInputs.fadeActive] = true → 不跳
 * - renderScale/qualityFactor 变化 → [FrameSkipInputs.scaleChanged] = true → 不跳
 *   （强制重渲染应用新缩放/帧缓冲重建）
 *
 * ## 与帧率阶梯的关系
 * 循环仍按帧率节拍唤醒（30 次/秒唤醒成本可忽略），仅跳过渲染与指标统计——
 * 挂机数字动画等 Compose 层照常；帧率阶梯（5s→30、30s→10）不动。
 * 跳帧不记录 EWMA 能力帧率、不上报 ObservedFps（防虚高误判降级）。
 */
object FrameSkipPolicy {

    /**
     * 判定本帧是否可跳过渲染。
     *
     * @return true = 跳过（无任何可见变化）；false = 必须渲染
     */
    fun shouldSkipFrame(inputs: FrameSkipInputs): Boolean =
        !inputs.cameraDirty &&
            !inputs.frameChanged &&
            !inputs.buildingBusDirty &&
            !inputs.fadeActive &&
            !inputs.scaleChanged
}

/**
 * 脏帧跳过判定输入（渲染循环每帧收集的纯信号快照）。
 */
data class FrameSkipInputs(
    /** 相机脏标记（AtomicBoolean 当前值） */
    val cameraDirty: Boolean,
    /** 帧引用变化（currentFrame !== 上次渲染帧） */
    val frameChanged: Boolean,
    /** 命令总线建筑数据脏标记（未消费的建筑推送） */
    val buildingBusDirty: Boolean,
    /** 地图淡入进行中（fadeAlpha < 1） */
    val fadeActive: Boolean,
    /** 渲染缩放/画质因子变化（需强制重渲染应用） */
    val scaleChanged: Boolean
)
