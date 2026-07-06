package com.xianxia.sect.core.touch

/**
 * 边缘自动平移检测器 — 纯 Kotlin 跨平台。
 *
 * 根据手指距屏幕边缘的距离计算自动平移速度。
 * 手指越靠近边缘，平移速度越大（线性插值 0 ~ maxPanSpeed）。
 *
 * 参考：Clash of Clans / Rise of Kingdoms 建筑拖拽时的自动边缘滚动机制。
 * 不同于旧版固定 8px 步长，采用距离比例连续速度曲线。
 */
class EdgePanDetector(
    /** 触发边缘平移的离边距（像素） */
    private val edgeThickness: Float = 100f,
    /** 最大自动平移速度 (px/s) */
    private val maxPanSpeed: Float = 600f
) {
    private var screenWidth = 0f
    private var screenHeight = 0f

    /** 更新视口尺寸 */
    fun updateViewport(w: Float, h: Float) {
        screenWidth = w
        screenHeight = h
    }

    /**
     * 根据手指屏幕位置计算边缘平移速度。
     * @param fingerX 手指 X 坐标（像素）
     * @param fingerY 手指 Y 坐标（像素）
     * @return 该帧的平移速度 (px/s)，Offset2D.Zero 表示不触发
     */
    fun computePanVelocity(fingerX: Float, fingerY: Float): Offset2D {
        val vx = when {
            fingerX < edgeThickness ->
                maxPanSpeed * (1f - fingerX / edgeThickness)
            fingerX > screenWidth - edgeThickness ->
                -maxPanSpeed * (1f - (screenWidth - fingerX) / edgeThickness)
            else -> 0f
        }
        val vy = when {
            fingerY < edgeThickness ->
                maxPanSpeed * (1f - fingerY / edgeThickness)
            fingerY > screenHeight - edgeThickness ->
                -maxPanSpeed * (1f - (screenHeight - fingerY) / edgeThickness)
            else -> 0f
        }
        return Offset2D(vx, vy)
    }

    /**
     * 手指是否在边缘区域内。
     */
    fun isInEdgeZone(fingerX: Float, fingerY: Float): Boolean {
        return fingerX < edgeThickness ||
                fingerX > screenWidth - edgeThickness ||
                fingerY < edgeThickness ||
                fingerY > screenHeight - edgeThickness
    }

    companion object {
        val NoPan = Offset2D(0f, 0f)
    }
}
