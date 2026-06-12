package com.xianxia.sect.ui.game.map.infrastructure

object MapCoordTransformer {
    fun worldToNormalized(wx: Float, wy: Float, ww: Float, wh: Float): Pair<Float, Float> =
        (wx / ww).coerceIn(0f, 1f) to (wy / wh).coerceIn(0f, 1f)

    fun normalizedToWorld(nx: Float, ny: Float, ww: Float, wh: Float): Pair<Float, Float> =
        nx * ww to ny * wh
}
