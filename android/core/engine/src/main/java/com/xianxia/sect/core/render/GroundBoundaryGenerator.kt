package com.xianxia.sect.core.render

import kotlin.math.sqrt

/**
 * GroundBoundaryGenerator — 弯曲地皮轮廓合成器 Kotlin 降级实现。
 *
 * C++ `gamecore/map/ground_boundary.h`（合成单一权威）的忠实移植，仅作为
 * JVM 测试基线 / native 通道不可用时的降级路径（对拍守护：DiffGroundBoundaryTest，
 * 浮点容差 0.05px——折线采样允许两端 FP 求值序的微小差异，几何/掩码/布局
 * 语义逐位一致）。算法、控制点表、振幅不变式、复合输出布局均以 C++ 头文件
 * 注释为单一权威文档，本文件不再复述。
 *
 * 纯函数、无 Android 依赖、任意线程可调（生产调用方 = Compose remember，低频）。
 */
object GroundBoundaryGenerator {

    // ── 跨语言常量（与 C++ 一一对应；漂移由 DiffGroundBoundaryTest 即红）──

    const val VERSION = 1
    const val HEADER_FLOATS = 11
    const val MAX_OUTSET = 0.0160f
    const val MAX_INSET = 0.0160f
    const val GATE_APRON_Y = 1.004f
    const val GATE_APRON_X0 = 0.440f
    const val GATE_APRON_X1 = 0.560f
    const val BOTTOM_TUCK_PX = 2.0f
    const val BOTTOM_NORMAL_MIN_Y = 0.0f
    const val SAMPLES_PER_SEGMENT = 20

    /** 复合输出字段下标（头部段） */
    object Field {
        const val VERSION = 0
        const val POLY_COUNT = 1
        const val COLS = 2
        const val ROWS = 3
        const val TILE_SIZE = 4
        const val BOTTOM_DEPTH = 5
        const val MASK_OFFSET = 6
        const val GROUND_MESH_OFFSET = 7
        const val GROUND_MESH_COUNT = 8
        const val BOTTOM_MESH_OFFSET = 9
        const val BOTTOM_MESH_COUNT = 10
    }

    /** 掩码位：bit0 = 格四角在轮廓内（地面变体/平铺装饰可画）；bit1 = 树锚点在内 */
    const val MASK_BIT_QUAD = 1
    const val MASK_BIT_TREE = 2

    // ── 第一阶段固定控制点（归一化，屏幕 Y 向下顺时针；序 = C++ 表）──
    private val CONTROL_POINTS = floatArrayOf(
        // 上边（左→右）
        0.020f, -0.006f, 0.180f, 0.012f, 0.340f, -0.010f, 0.520f, 0.008f,
        0.700f, -0.008f, 0.860f, 0.010f,
        // 右上角贴角点
        0.992f, 0.012f,
        // 右边（上→下）
        1.010f, 0.220f, 0.990f, 0.400f, 1.008f, 0.580f, 0.992f, 0.750f,
        1.004f, 0.900f,
        // 右下角贴角点
        0.992f, 0.990f,
        // 下边（右→左）+ 门楼缓冲带三点
        0.800f, 1.010f, 0.680f, 0.992f,
        0.560f, GATE_APRON_Y, 0.500f, GATE_APRON_Y, 0.440f, GATE_APRON_Y,
        0.360f, 0.996f, 0.240f, 1.010f,
        // 左下角贴角点
        0.010f, 0.992f,
        // 左边（下→上）
        -0.006f, 0.920f, 0.012f, 0.720f, -0.008f, 0.540f, 0.010f, 0.360f,
        -0.006f, 0.180f,
    )

    private val CONTROL_POINT_COUNT = CONTROL_POINTS.size / 2

    // ── 闭合 Catmull-Rom 采样 ────────────────────────────────────

    fun sampleControlPolygon(cols: Int, rows: Int, tileSize: Int): FloatArray {
        val w = (cols * tileSize).toFloat()
        val h = (rows * tileSize).toFloat()
        val n = CONTROL_POINT_COUNT
        val subdiv = SAMPLES_PER_SEGMENT
        val out = FloatArray(n * subdiv * 2)
        var o = 0
        fun at(i: Int): Int {
            val k = ((i % n) + n) % n
            return k * 2
        }
        for (i in 0 until n) {
            val p0 = at(i - 1)
            val p1 = at(i)
            val p2 = at(i + 1)
            val p3 = at(i + 2)
            for (s in 0 until subdiv) {
                val t = s.toFloat() / subdiv.toFloat()
                val t2 = t * t
                val t3 = t2 * t
                for (axis in 0..1) {
                    val v = 0.5f * ((2.0f * CONTROL_POINTS[p1 + axis]) +
                        (-CONTROL_POINTS[p0 + axis] + CONTROL_POINTS[p2 + axis]) * t +
                        (2.0f * CONTROL_POINTS[p0 + axis] - 5.0f * CONTROL_POINTS[p1 + axis] +
                            4.0f * CONTROL_POINTS[p2 + axis] - CONTROL_POINTS[p3 + axis]) * t2 +
                        (-CONTROL_POINTS[p0 + axis] + 3.0f * CONTROL_POINTS[p1 + axis] -
                            3.0f * CONTROL_POINTS[p2 + axis] + CONTROL_POINTS[p3 + axis]) * t3)
                    out[o++] = v * (if (axis == 0) w else h)
                }
            }
        }
        return out
    }

    // ── 偶奇规则点在多边形内 ─────────────────────────────────────

    fun pointInPolygon(x: Float, y: Float, poly: FloatArray): Boolean {
        val n = poly.size / 2
        var inside = false
        var j = n - 1
        for (i in 0 until n) {
            val xi = poly[i * 2]
            val yi = poly[i * 2 + 1]
            val xj = poly[j * 2]
            val yj = poly[j * 2 + 1]
            if ((yi > y) != (yj > y) && x < (xj - xi) * (y - yi) / (yj - yi) + xi) {
                inside = !inside
            }
            j = i
        }
        return inside
    }

    // ── 逐格掩码（安全带加速口径同 C++）──────────────────────────

    fun buildTileMask(cols: Int, rows: Int, tileSize: Int, poly: FloatArray): ByteArray {
        val out = ByteArray(cols * rows)
        val t = tileSize.toFloat()
        val w = cols.toFloat() * t
        val h = rows.toFloat() * t
        val safeX0 = MAX_INSET * w
        val safeY0 = MAX_INSET * h
        val safeX1 = w - safeX0
        val safeY1 = h - safeY0
        for (row in 0 until rows) {
            val y0 = row * t
            val y1 = y0 + t
            val rowSafe = y0 >= safeY0 && y1 <= safeY1
            for (col in 0 until cols) {
                val x0 = col * t
                val x1 = x0 + t
                val m: Int
                if (rowSafe && x0 >= safeX0 && x1 <= safeX1) {
                    m = MASK_BIT_QUAD or MASK_BIT_TREE
                } else {
                    val quadFull = pointInPolygon(x0, y0, poly) &&
                        pointInPolygon(x1, y0, poly) &&
                        pointInPolygon(x0, y1, poly) &&
                        pointInPolygon(x1, y1, poly)
                    val treeFoot = pointInPolygon(x0 + t * 0.5f, y1, poly)
                    m = (if (quadFull) MASK_BIT_QUAD else 0) or
                        (if (treeFoot) MASK_BIT_TREE else 0)
                }
                out[row * cols + col] = m.toByte()
            }
        }
        return out
    }

    // ── 耳切三角化（手性/兜底口径同 C++）─────────────────────────

    private fun area2(poly: FloatArray): Float {
        var a = 0.0f
        val n = poly.size / 2
        for (i in 0 until n) {
            val j = (i + 1) % n
            a += poly[i * 2] * poly[j * 2 + 1] - poly[j * 2] * poly[i * 2 + 1]
        }
        return a
    }

    private fun cross2(ax: Float, ay: Float, bx: Float, by: Float, cx: Float, cy: Float) =
        (bx - ax) * (cy - by) - (by - ay) * (cx - bx)

    private fun pointInTri(
        px: Float, py: Float,
        ax: Float, ay: Float, bx: Float, by: Float, cx: Float, cy: Float
    ): Boolean {
        val d1 = cross2(ax, ay, bx, by, px, py)
        val d2 = cross2(bx, by, cx, cy, px, py)
        val d3 = cross2(cx, cy, ax, ay, px, py)
        val hasNeg = d1 < 0.0f || d2 < 0.0f || d3 < 0.0f
        val hasPos = d1 > 0.0f || d2 > 0.0f || d3 > 0.0f
        return !(hasNeg && hasPos)
    }

    fun triangulatePolygon(poly: FloatArray): IntArray {
        val n = poly.size / 2
        if (n < 3) return IntArray(0)
        val ring = ArrayList<Int>(n)
        for (i in 0 until n) ring.add(i)
        if (area2(poly) < 0.0f) ring.reverse()
        val out = ArrayList<Int>(n * 3)
        var guard = 0
        val guardMax = ring.size * ring.size + 16
        while (ring.size > 3 && guard++ < guardMax) {
            val m = ring.size
            var clipped = false
            for (k in 0 until m) {
                val i0 = ring[(k + m - 1) % m]
                val i1 = ring[k]
                val i2 = ring[(k + 1) % m]
                val ax = poly[i0 * 2]; val ay = poly[i0 * 2 + 1]
                val bx = poly[i1 * 2]; val by = poly[i1 * 2 + 1]
                val cx = poly[i2 * 2]; val cy = poly[i2 * 2 + 1]
                if (cross2(ax, ay, bx, by, cx, cy) <= 0.0f) continue
                var contains = false
                for (q in 0 until m) {
                    val iq = ring[q]
                    if (iq == i0 || iq == i1 || iq == i2) continue
                    if (pointInTri(poly[iq * 2], poly[iq * 2 + 1], ax, ay, bx, by, cx, cy)) {
                        contains = true
                        break
                    }
                }
                if (contains) continue
                out.add(i0); out.add(i1); out.add(i2)
                ring.removeAt(k)
                clipped = true
                break
            }
            if (!clipped) {
                var best = 0
                var bestCross = -Float.MAX_VALUE
                for (k in ring.indices) {
                    val a = ring[(k + ring.size - 1) % ring.size]
                    val b = ring[k]
                    val c = ring[(k + 1) % ring.size]
                    val cr = cross2(
                        poly[a * 2], poly[a * 2 + 1],
                        poly[b * 2], poly[b * 2 + 1],
                        poly[c * 2], poly[c * 2 + 1]
                    )
                    if (cr > bestCross) { bestCross = cr; best = k }
                }
                out.add(ring[(best + ring.size - 1) % ring.size])
                out.add(ring[best])
                out.add(ring[(best + 1) % ring.size])
                ring.removeAt(best)
            }
        }
        if (ring.size == 3) {
            out.add(ring[0]); out.add(ring[1]); out.add(ring[2])
        }
        return out.toIntArray()
    }

    // ── 地皮 mesh（SpriteVertex 布局）────────────────────────────

    fun buildGroundMesh(tileSize: Int, poly: FloatArray): FloatArray {
        val idx = triangulatePolygon(poly)
        val out = FloatArray(idx.size * 8)
        val t = tileSize.toFloat()
        var o = 0
        for (i in idx) {
            val x = poly[i * 2]
            val y = poly[i * 2 + 1]
            out[o++] = x; out[o++] = y
            out[o++] = x / t; out[o++] = y / t
            out[o++] = 1.0f; out[o++] = 1.0f; out[o++] = 1.0f; out[o++] = 1.0f
        }
        return out
    }

    // ── 底部 mesh（外法线朝下段向下挤出；顶边 = 折线 − tuck）──────

    fun buildBottomMesh(
        tileSize: Int, bottomDepth: Float, poly: FloatArray
    ): FloatArray {
        val n = poly.size / 2
        if (n < 3 || bottomDepth <= 0.0f) return FloatArray(0)
        val t = tileSize.toFloat()
        var cx = 0.0f; var cy = 0.0f
        for (i in 0 until n) {
            cx += poly[i * 2]; cy += poly[i * 2 + 1]
        }
        cx /= n.toFloat(); cy /= n.toFloat()
        val arc = FloatArray(n + 1)
        for (i in 1..n) {
            val a = i - 1
            val b = i % n
            val dx = poly[b * 2] - poly[a * 2]
            val dy = poly[b * 2 + 1] - poly[a * 2 + 1]
            arc[i % n] = arc[a] + sqrt(dx * dx + dy * dy)
        }
        val out = ArrayList<Float>(n * 16)
        for (i in 0 until n) {
            val j = (i + 1) % n
            val ax = poly[i * 2]; val ay = poly[i * 2 + 1]
            val bx = poly[j * 2]; val by = poly[j * 2 + 1]
            val dx = bx - ax; val dy = by - ay
            val len = sqrt(dx * dx + dy * dy)
            if (len < 1.0e-6f) continue
            var nx = dy / len
            var ny = -dx / len
            val mx = (ax + bx) * 0.5f - cx
            val my = (ay + by) * 0.5f - cy
            if (nx * mx + ny * my < 0.0f) { nx = -nx; ny = -ny }
            if (ny <= BOTTOM_NORMAL_MIN_Y) continue
            val topAy = ay - BOTTOM_TUCK_PX
            val topBy = by - BOTTOM_TUCK_PX
            val uA = arc[i] / t
            val uB = arc[j] / t
            val vTop = 0.0f
            val vBot = bottomDepth / t
            val botAy = ay + bottomDepth - BOTTOM_TUCK_PX
            val botBy = by + bottomDepth - BOTTOM_TUCK_PX
            // 与 SpriteBatcher.add 同手性两三角：(A_top,B_top,A_bot)(B_top,B_bot,A_bot)
            val quad = arrayOf(
                floatArrayOf(ax, topAy, uA, vTop, 1f, 1f, 1f, 1f),
                floatArrayOf(bx, topBy, uB, vTop, 1f, 1f, 1f, 1f),
                floatArrayOf(ax, botAy, uA, vBot, 1f, 1f, 1f, 1f),
                floatArrayOf(bx, topBy, uB, vTop, 1f, 1f, 1f, 1f),
                floatArrayOf(bx, botBy, uB, vBot, 1f, 1f, 1f, 1f),
                floatArrayOf(ax, botAy, uA, vBot, 1f, 1f, 1f, 1f)
            )
            for (v in quad) for (f in v) out.add(f)
        }
        return out.toFloatArray()
    }

    // ── 总装配（复合 FloatArray，布局 = GroundBoundaryBridge.Header）──

    fun generate(cols: Int, rows: Int, tileSize: Int, bottomDepth: Float): FloatArray {
        if (cols <= 0 || rows <= 0 || tileSize <= 0) return FloatArray(0)
        val poly = sampleControlPolygon(cols, rows, tileSize)
        val mask = buildTileMask(cols, rows, tileSize, poly)
        val groundMesh = buildGroundMesh(tileSize, poly)
        val bottomMesh = buildBottomMesh(tileSize, bottomDepth, poly)

        val polyCount = poly.size / 2
        var off = HEADER_FLOATS + poly.size
        val maskOffset = off
        off += mask.size
        val groundMeshOffset = off
        off += groundMesh.size
        val bottomMeshOffset = off

        val out = FloatArray(off + bottomMesh.size)
        out[Field.VERSION] = VERSION.toFloat()
        out[Field.POLY_COUNT] = polyCount.toFloat()
        out[Field.COLS] = cols.toFloat()
        out[Field.ROWS] = rows.toFloat()
        out[Field.TILE_SIZE] = tileSize.toFloat()
        out[Field.BOTTOM_DEPTH] = bottomDepth
        out[Field.MASK_OFFSET] = maskOffset.toFloat()
        out[Field.GROUND_MESH_OFFSET] = groundMeshOffset.toFloat()
        out[Field.GROUND_MESH_COUNT] = groundMesh.size.toFloat()
        out[Field.BOTTOM_MESH_OFFSET] = bottomMeshOffset.toFloat()
        out[Field.BOTTOM_MESH_COUNT] = bottomMesh.size.toFloat()
        poly.copyInto(out, HEADER_FLOATS)
        for (i in mask.indices) out[maskOffset + i] = mask[i].toFloat()
        groundMesh.copyInto(out, groundMeshOffset)
        bottomMesh.copyInto(out, bottomMeshOffset)
        return out
    }
}
