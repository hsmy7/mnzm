package com.xianxia.sect.core.engine.domain.exploration

import kotlin.math.sqrt

object GeometryUtils {
    /**
     * 点到线段的最短距离检测
     */
    fun isPointNearLineSegment(
        px: Double, py: Double,
        x1: Double, y1: Double,
        x2: Double, y2: Double,
        threshold: Double
    ): Boolean {
        val lineLenSq = (x2 - x1) * (x2 - x1) + (y2 - y1) * (y2 - y1)
        if (lineLenSq == 0.0) {
            return sqrt((px - x1) * (px - x1) + (py - y1) * (py - y1)) < threshold
        }
        val t = ((px - x1) * (x2 - x1) + (py - y1) * (y2 - y1)) / lineLenSq
        val tClamped = t.coerceIn(0.0, 1.0)
        val nearestX = x1 + tClamped * (x2 - x1)
        val nearestY = y1 + tClamped * (y2 - y1)
        val dist = sqrt((px - nearestX) * (px - nearestX) + (py - nearestY) * (py - nearestY))
        return dist < threshold
    }

    /**
     * 点到 MST 边的距离检测
     */
    fun isPointNearCurvedPath(
        px: Int, py: Int, edge: MSTEdge, threshold: Double
    ): Boolean {
        val (from, to) = if (edge.sect1.id < edge.sect2.id) {
            edge.sect1 to edge.sect2
        } else {
            edge.sect2 to edge.sect1
        }
        return isPointNearLineSegment(
            px.toDouble(), py.toDouble(),
            from.x.toDouble(), from.y.toDouble(),
            to.x.toDouble(), to.y.toDouble(),
            threshold
        )
    }
}
