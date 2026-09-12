package com.xianxia.sect.ui.game.sect

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import com.xianxia.sect.core.render.RenderFrame
import kotlin.math.roundToInt

// ── 放置/移动模式占地框（预览框）与云层几何：顶层常量/函数 ──
//
// 纯绘制工具（无状态、只读 RenderFrame + Canvas），与 SoftwareCanvasBackend 的
// chunk/建筑绘制解耦——独立文件避免主文件超 2000 行（coding standards 3.1），
// 同时不让软件后端类函数数逼近 TooManyFunctions 阈值。

/** 可放置占地填充：半透明绿 #4CAF50 */
private val PREVIEW_GREEN_FILL_COLOR = android.graphics.Color.argb(0x59, 0x4C, 0xAF, 0x50)

/** 可放置占地描边：较高不透明绿 #4CAF50 */
private val PREVIEW_GREEN_EDGE_COLOR = android.graphics.Color.argb(0xE6, 0x4C, 0xAF, 0x50)

/** 不可放置占地填充：半透明红 #F44336 */
private val PREVIEW_RED_FILL_COLOR = android.graphics.Color.argb(0x59, 0xF4, 0x43, 0x36)

/** 不可放置占地描边：较高不透明红 #F44336 */
private val PREVIEW_RED_EDGE_COLOR = android.graphics.Color.argb(0xE6, 0xF4, 0x43, 0x36)

/** 占地框线宽（格数）：max(2px, tileSize×0.06) 的格数分量 */
private const val PREVIEW_BOX_HIGHLIGHT_LINE_WIDTH_TILES = 0.06f

/** 世界→屏幕纵向压缩系数（与 SpriteAtlasDef 双端共享常量同源） */
private val OVERLAY_TOPDOWN_Y_SCALE = com.xianxia.sect.core.render.SpriteAtlasDef.TOPDOWN_Y_SCALE

/**
 * 绘制占地框（预览框）：与建筑精灵同帧同源（绿=可放置 / 红=不可放置提示）。
 * 框几何为网格对齐的占地矩形（[RenderFrame.previewBoxX/Y/W/H]），
 * 精灵居中+底部对齐绘于其内——两者共享同一份预览快照，物理上永不同步脱节。
 *
 * @param previewBoxPaint 由调用方传入（渲染线程独占，逐帧改颜色不污染共享 paint）
 */
internal fun drawPreviewHighlight(
    canvas: Canvas,
    frame: RenderFrame,
    drawScale: Float,
    tileSize: Int,
    previewBoxPaint: Paint
) {
    val offX = frame.previewBoxX - frame.camX
    val offY = frame.previewBoxY - frame.camY
    val left = (offX * drawScale).roundToInt()
    val top = (offY * drawScale * OVERLAY_TOPDOWN_Y_SCALE).roundToInt()
    val right = ((offX + frame.previewBoxW) * drawScale).roundToInt()
    val bottom = ((offY + frame.previewBoxH) * drawScale * OVERLAY_TOPDOWN_Y_SCALE).roundToInt()
    val offScreenX = right <= 0 || left >= canvas.width
    val offScreenY = bottom <= 0 || top >= canvas.height
    val degenerate = right - left <= 0 || bottom - top <= 0
    if (offScreenX || offScreenY || degenerate) return

    val lineWidth = maxOf(2f, tileSize * PREVIEW_BOX_HIGHLIGHT_LINE_WIDTH_TILES * drawScale)
    // 填充（绿/红半透明，可放置提示）→ 描边盖住填充边缘：上 → 下 → 左 → 右
    previewBoxPaint.color = if (frame.previewBoxValid) PREVIEW_GREEN_FILL_COLOR else PREVIEW_RED_FILL_COLOR
    canvas.drawRect(left.toFloat(), top.toFloat(), right.toFloat(), bottom.toFloat(), previewBoxPaint)
    previewBoxPaint.color = if (frame.previewBoxValid) PREVIEW_GREEN_EDGE_COLOR else PREVIEW_RED_EDGE_COLOR
    canvas.drawRect(left.toFloat(), top.toFloat(), right.toFloat(), top + lineWidth, previewBoxPaint)
    canvas.drawRect(left.toFloat(), bottom - lineWidth, right.toFloat(), bottom.toFloat(), previewBoxPaint)
    canvas.drawRect(left.toFloat(), top.toFloat(), left + lineWidth, bottom.toFloat(), previewBoxPaint)
    canvas.drawRect(right - lineWidth, top.toFloat(), right.toFloat(), bottom.toFloat(), previewBoxPaint)
}

/**
 * 云层实例合法性（NaN/±Inf/非正宽高/透明度越界——与 C++ 段同语义拦截）。
 */
internal fun isValidCloud(x: Float, y: Float, w: Float, h: Float, alpha: Float): Boolean {
    val coordsFinite = x.isFinite() && y.isFinite()
    // 两两组合拆开（ComplexCondition ≤3）：NaN 比较恒 false，`!in` 一并拦截透明度
    val sizePositive = (w.isFinite() && w > 0f) && (h.isFinite() && h > 0f)
    val alphaValid = alpha in 0f..1f
    return coordsFinite && sizePositive && alphaValid
}

/**
 * 云层屏幕矩形（视口剔除：视口外/退化尺寸 → null）。
 */
internal fun cloudScreenRect(
    frame: RenderFrame,
    x: Float,
    y: Float,
    w: Float,
    h: Float,
    drawScale: Float,
    canvas: Canvas
): Rect? {
    val left = ((x - frame.camX) * drawScale).roundToInt()
    val top = ((y - frame.camY) * drawScale * OVERLAY_TOPDOWN_Y_SCALE).roundToInt()
    val right = ((x + w - frame.camX) * drawScale).roundToInt()
    val bottom = ((y + h - frame.camY) * drawScale * OVERLAY_TOPDOWN_Y_SCALE).roundToInt()
    val offRightOrLeft = right <= 0 || left >= canvas.width
    val offBottomOrTop = bottom <= 0 || top >= canvas.height
    val degenerate = right - left <= 0 || bottom - top <= 0
    val visible = !offRightOrLeft && !offBottomOrTop && !degenerate
    return if (visible) Rect(left, top, right, bottom) else null
}
