package com.xianxia.sect.ui.game.map

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import com.xianxia.sect.core.camera.CameraState

/**
 * 相机状态抽象基类 — 提取 [SectCameraState] 与 [WorldCameraState] 的公共逻辑。
 *
 * 在基类中实现坐标转换、平移、缩放、居中、边界钳制等通用方法，
 * 子类只需覆盖 [computeDefaultScale] 定制初始缩放策略，并添加各自特有的方法。
 *
 * 使用 Compose [mutableFloatStateOf]/[mutableIntStateOf] 委托，
 * 使 Compose 在相机状态变化时自动触发重组。
 */
abstract class BaseCameraState(
    override val worldWidth: Float,
    override val worldHeight: Float
) : CameraState {

    // ── 状态字段（Compose 可观测） ──

    override var cameraX by mutableFloatStateOf(0f)
        protected set
    override var cameraY by mutableFloatStateOf(0f)
        protected set
    override var viewportWidth by mutableIntStateOf(0)
        protected set
    override var viewportHeight by mutableIntStateOf(0)
        protected set
    override var scale by mutableFloatStateOf(1f)
        protected set

    /** 用户是否已主动缩放。为 true 后 [updateViewport] 不再覆盖 scale。 */
    protected var userScale = false
        protected set

    // ── 子类可覆盖的默认缩放策略 ──

    /**
     * 计算默认缩放值。
     * 在首次初始化或屏幕旋转且用户未主动缩放时调用。
     * @param vpW 新视口宽度（像素）
     * @param vpH 新视口高度（像素）
     */
    protected open fun computeDefaultScale(vpW: Int, vpH: Int): Float = 1f

    // ── 坐标转换 ──

    override fun worldToScreenX(wx: Float): Float = (wx - cameraX) * scale
    override fun worldToScreenY(wy: Float): Float = (wy - cameraY) * scale
    override fun screenToWorldX(sx: Float): Float = sx / scale + cameraX
    override fun screenToWorldY(sy: Float): Float = sy / scale + cameraY

    // ── 相机控制 ──

    /**
     * 更新视口尺寸。
     *
     * - 用户未主动缩放时重新计算默认缩放（覆盖 [computeDefaultScale]）。
     * - 用户已缩放时保留当前缩放仅重新 clamp。
     * - 屏幕旋转/多窗口变化后，若用户未缩放，会自动适配新长宽比。
     */
    override fun updateViewport(w: Int, h: Int) {
        viewportWidth = w.coerceAtLeast(0)
        viewportHeight = h.coerceAtLeast(0)
        if (!userScale) {
            scale = computeDefaultScale(w, h)
        }
        clamp()
    }

    /**
     * 平移相机（拖拽响应）。
     * @param dx 屏幕坐标 X 方向偏移像素
     * @param dy 屏幕坐标 Y 方向偏移像素
     */
    override fun pan(dx: Float, dy: Float) {
        if (viewportWidth <= 0 || viewportHeight <= 0) return
        if (scale <= 0f || scale.isNaN()) return
        cameraX -= dx / scale
        cameraY -= dy / scale
        clamp()
    }

    /**
     * 以屏幕焦点为中心的锚定缩放。
     *
     * 缩放后焦点下的世界坐标保持不变（类似 Google Maps / LibGDX 标准做法）。
     * 调用后标记 [userScale]=true，viewport 变化不再覆盖 scale。
     *
     * @param delta 缩放倍数（>1 放大, <1 缩小）
     * @param focusX 缩放焦点屏幕 X 坐标
     * @param focusY 缩放焦点屏幕 Y 坐标
     */
    override fun zoom(delta: Float, focusX: Float, focusY: Float) {
        if (viewportWidth <= 0 || viewportHeight <= 0) return
        if (delta.isNaN() || delta <= 0f) return
        val worldBeforeX = screenToWorldX(focusX)
        val worldBeforeY = screenToWorldY(focusY)
        val newScale = (scale * delta).coerceIn(CameraState.MIN_ZOOM, CameraState.MAX_ZOOM)
        if (newScale == scale) return
        userScale = true
        scale = newScale
        cameraX = worldBeforeX - focusX / scale
        cameraY = worldBeforeY - focusY / scale
        clamp()
    }

    /**
     * 将相机中心移动到指定世界坐标。
     * @param wx 目标世界坐标 X
     * @param wy 目标世界坐标 Y
     */
    override fun centerOn(wx: Float, wy: Float) {
        if (viewportWidth <= 0 || viewportHeight <= 0) return
        cameraX = wx - viewportWidth / (2f * scale)
        cameraY = wy - viewportHeight / (2f * scale)
        clamp()
    }

    /**
     * 直接设置相机位置。
     * 值会被 [clamp] 限制在世界边界内。
     */
    override fun setPosition(x: Float, y: Float) {
        if (x.isNaN() || x.isInfinite() || y.isNaN() || y.isInfinite()) return
        if (viewportWidth <= 0 || viewportHeight <= 0) {
            cameraX = x
            cameraY = y
            return
        }
        cameraX = x
        cameraY = y
        clamp()
    }

    /**
     * 设置缩放值。
     * 钳制到合法范围并标记 [userScale]=true。
     */
    override fun applyScale(newScale: Float) {
        if (newScale.isNaN() || newScale <= 0f) return
        scale = newScale.coerceIn(CameraState.MIN_ZOOM, CameraState.MAX_ZOOM)
        userScale = true
        clamp()
    }

    /**
     * 判断世界坐标点是否在视口可见范围内。
     * @param margin 视口外扩检测边距（世界坐标单位）
     */
    override fun isVisible(wx: Float, wy: Float, margin: Float): Boolean {
        if (viewportWidth <= 0 || viewportHeight <= 0) return true
        val sx = worldToScreenX(wx)
        val sy = worldToScreenY(wy)
        val m = margin * scale
        return sx >= -m && sx <= viewportWidth + m &&
            sy >= -m && sy <= viewportHeight + m
    }

    /**
     * 重置相机到初始状态（位置归零，清除用户缩放标记）。
     */
    override fun reset() {
        userScale = false
        cameraX = 0f
        cameraY = 0f
    }

    // ── 边界管理 ──

    /**
     * 将相机位置限制在世界边界内。
     *
     * 可见世界尺寸 = 视口尺寸 / scale，比 scale=1 时看到更多世界区域。
     * scale ≤ 0 时直接返回，防止除以零。
     */
    protected fun clamp() {
        if (scale <= 0f || scale.isNaN()) return
        // NaN/Infinity 净化：coerceIn 不处理 NaN，NaN 会传播到渲染线程
        if (cameraX.isNaN() || cameraX.isInfinite()) cameraX = 0f
        if (cameraY.isNaN() || cameraY.isInfinite()) cameraY = 0f
        val ew = viewportWidth / scale
        val eh = viewportHeight / scale
        cameraX = cameraX.coerceIn(0f, (worldWidth - ew).coerceAtLeast(0f))
        cameraY = cameraY.coerceIn(0f, (worldHeight - eh).coerceAtLeast(0f))
    }
}
