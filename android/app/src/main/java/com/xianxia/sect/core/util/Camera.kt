package com.xianxia.sect.core.util

class Camera {
    var x: Float = 0f
    var y: Float = 0f

    fun screenToWorld(sx: Float, sy: Float, scale: Float = 1f): Pair<Float, Float> =
        ((sx + x) / scale) to ((sy + y) / scale)

    fun worldToScreen(wx: Float, wy: Float, scale: Float = 1f): Pair<Float, Float> =
        (wx * scale - x) to (wy * scale - y)

    fun gridToScreen(gx: Int, gy: Int, gridSizePx: Float, scale: Float = 1f): Pair<Float, Float> =
        (gx * gridSizePx * scale - x) to (gy * gridSizePx * scale - y)

    fun pan(dx: Float, dy: Float) {
        x += dx
        y += dy
    }

    fun clamp(minX: Float, maxX: Float, minY: Float, maxY: Float) {
        x = x.coerceIn(minX, maxX)
        y = y.coerceIn(minY, maxY)
    }
}
