package com.xianxia.sect.ui.game.sect

import com.xianxia.sect.core.render.RenderFrame

/**
 * 预览快通道快照（渲染线程合成输入；internal 供单测）。
 * 字段语义与 [RenderFrame] 预览字段一致（精灵水平居中 + 底部对齐的最终世界坐标）。
 */
internal data class FastPreviewSnapshot(
    val active: Boolean,
    val x: Float,
    val y: Float,
    val w: Float,
    val h: Float,
    val u0: Float,
    val v0: Float,
    val u1: Float,
    val v1: Float,
    val alpha: Float
)

/**
 * 预览独立快通道（2026-08-30 触控优化：仿相机独立通道）——
 * 触控回调直接写最新预览，渲染线程按版本号合成进帧，不经 Compose 重组/帧率门控
 * （软件渲染路径下预览从 RenderFrame 33ms 门控的 30fps 提升到渲染帧率）。
 *
 * 独立类承载：① 单一真相源（快照 + 版本原子更新）；② 保持 [NativeSurfaceView]
 * 类函数数低于 detekt TooManyFunctions 阈值（thresholdInClasses=20）。
 *
 * 线程契约：写（触控回调，主线程）/ 读（渲染线程）均单写单读 @Volatile，版本号
 * 先于快照写入（渲染线程读到新版本时快照必已完整——单 64 位引用写入原子）。
 */
internal class FastPreviewChannel {
    /** 最新预览快照（null = 无激活预览，回落 Compose 门控帧） */
    @Volatile
    var state: FastPreviewSnapshot? = null
        private set

    /** 版本号 — 每次写入自增，渲染线程据此判断是否需要重新合成 */
    @Volatile
    var version: Long = 0L
        private set

    /**
     * 写入最新建筑放置/移动预览（拖拽高频路径；[snapshot] 为 null 表示退出预览）。
     * 退出预览不立即清空——由 [MainGameScreen] 在编辑模式退出（确认/取消/切 Tab）时
     * 调用，避免拖拽结束后 33ms 门控窗口内的位置回跳。
     */
    fun set(snapshot: FastPreviewSnapshot?) {
        state = snapshot
        version++
    }
}

/**
 * 预览快通道合成纯函数（renderTick 调用；internal 供单测）：
 * 用快照覆盖帧的预览字段，其余字段（tileData/buildingData/roadData 等）引用不变。
 */
internal fun mergeFastPreviewInto(frame: RenderFrame, snapshot: FastPreviewSnapshot): RenderFrame =
    frame.copy(
        showPreview = snapshot.active,
        previewX = snapshot.x,
        previewY = snapshot.y,
        previewW = snapshot.w,
        previewH = snapshot.h,
        previewU0 = snapshot.u0,
        previewV0 = snapshot.v0,
        previewU1 = snapshot.u1,
        previewV1 = snapshot.v1,
        previewTintRed = 1.0f,
        previewTintGreen = 1.0f,
        previewTintBlue = 1.0f,
        previewAlpha = snapshot.alpha
    )
