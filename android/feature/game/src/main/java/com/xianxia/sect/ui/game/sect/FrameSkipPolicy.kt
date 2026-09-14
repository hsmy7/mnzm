package com.xianxia.sect.ui.game.sect

/**
 * 脏帧跳过判定 — 渲染循环静止画面跳过渲染的纯函数（挂机省电）。
 *
 * ## 动机
 * 放置类挂机场景（用户盯着数字、地图静止）画面静止，渲染负载应为零。
 * 行业对标：Unity OnDemandRendering
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
 * - 云朵运动/生成/销毁 → [FrameSkipInputs.cloudDirty] = true → 不跳
 *   （云层动画由渲染线程逐节拍推进——跳帧期间仍推进生成定时器，云活跃时画面
 *   持续变化必须渲染；无云静止时恢复跳帧省电）
 * - 天空渐变配置变化 → [FrameSkipInputs.skyDirty] = true → 不跳
 *   （SkyBackgroundConfig 由天气/时间系统更新——静止画面若跳过，天空配色不会更新，
 *   直到下一次相机/数据变化强制渲染；故配置变更必须强制渲染一帧）
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
            !inputs.scaleChanged &&
            !inputs.cloudDirty &&
            !inputs.previewDirty &&
            !inputs.skyDirty
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
    val scaleChanged: Boolean,
    /** 云层动画变化（云朵移动/生成/销毁——云活跃时画面持续变化必须渲染） */
    val cloudDirty: Boolean,
    /** 预览快通道版本变化（拖动/放置预览更新——快通道自唤醒，与 Compose 重组解耦） */
    val previewDirty: Boolean,
    /** 天空渐变配置变化（天气/时间系统更新 SkyBackgroundConfig——静止画面也须更新天空配色） */
    val skyDirty: Boolean
)

/**
 * 淡入完成兜底帧判定（防"进入游戏全屏半透明白色覆盖"定格）。
 *
 * 地图淡入期间瓦片/地面以 `fadeAlpha`（0→1）半透明绘制，背后是
 * 双后端每帧清屏的米白色 #F2EDE4——淡入早期画面 = "半透明白色"。若脏帧跳过
 * 在淡入结束后把画面定格在**淡入早期提交的帧**（fadeAlpha < 1），用户会看到
 * 全屏半透明白色持续，直到操作（拖动视角触发 cameraDirty）强制重绘才恢复。
 *
 * 本函数在淡入结束后（currentFadeAlpha ≥ 1）且**最后一帧仍以淡入中 alpha 渲染**
 * （lastRenderedFadeAlpha < 1）时返回 true，渲染循环据此强制补渲一帧完整
 * 不透明地图，保证定格帧永远是正常画面。
 *
 * @param lastRenderedFadeAlpha 最近一次实际渲染帧的 fadeAlpha（1 = 已渲染完整帧）
 * @param currentFadeAlpha 当前时钟驱动的 fadeAlpha（≥1 表示淡入已结束）
 * @return true = 必须强制渲染一帧完整 alpha（消除半透明帧定格）
 */
fun needsFadeCompletionFrame(
    lastRenderedFadeAlpha: Float,
    currentFadeAlpha: Float
): Boolean = lastRenderedFadeAlpha < 1f && currentFadeAlpha >= 1f
