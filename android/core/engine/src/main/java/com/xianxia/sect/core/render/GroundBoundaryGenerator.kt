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
 * 几何/三角化原语为文件级私有函数（零状态纯函数——文件级组织使对象只保留
 * 管线入口）。
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
        val out = FloatArray(n * SAMPLES_PER_SEGMENT * 2)
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
            o = sampleSegment(CONTROL_POINTS, p0, p1, p2, p3, w, h, out, o)
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
                val m = if (rowSafe && x0 >= safeX0 && x1 <= safeX1) {
                    MASK_BIT_QUAD or MASK_BIT_TREE
                } else {
                    classifyFringeTile(x0, y0, x1, y1, t, poly)
                }
                out[row * cols + col] = m.toByte()
            }
        }
        return out
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

    fun buildBottomMesh(tileSize: Int, bottomDepth: Float, poly: FloatArray): FloatArray {
        val n = poly.size / 2
        if (n < 3 || bottomDepth <= 0.0f) return FloatArray(0)
        val centroid = polyCentroid(poly)
        val arc = cumulativeArc(poly)
        val out = ArrayList<Float>(n * 16)
        for (i in 0 until n) {
            val quad = bandSegmentQuad(tileSize, bottomDepth, centroid.first, centroid.second, arc, poly, i)
            if (quad != null) out.addAll(quad)
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

    // ── 对象内管线助手 ───────────────────────────────────────────

    /**
     * 单控制点段采样：subdiv 份等分，逐轴 CR 求值后乘地图尺寸写入 [out]。
     * @return 写入后的游标。
     */
    // CR 四控制点索引 + 地图尺寸 + 输出缓冲/游标 = 采样算法本身形状（C++ 同构），
    // 强行打包反而破坏与权威实现的逐行对照——声明性豁免
    @Suppress("LongParameterList")
    private fun sampleSegment(
        ctrl: FloatArray,
        p0: Int,
        p1: Int,
        p2: Int,
        p3: Int,
        w: Float,
        h: Float,
        out: FloatArray,
        o0: Int
    ): Int {
        var o = o0
        for (s in 0 until SAMPLES_PER_SEGMENT) {
            val t = s.toFloat() / SAMPLES_PER_SEGMENT.toFloat()
            for (axis in 0..1) {
                val v = catmullRom(ctrl[p0 + axis], ctrl[p1 + axis], ctrl[p2 + axis], ctrl[p3 + axis], t)
                out[o++] = v * (if (axis == 0) w else h)
            }
        }
        return o
    }

    /** 统一参数化 Catmull-Rom（张力 0.5）单轴求值——与 C++ sampleGroundControlPolygon 同式 */
    private fun catmullRom(p0: Float, p1: Float, p2: Float, p3: Float, t: Float): Float {
        val t2 = t * t
        val t3 = t2 * t
        return 0.5f * ((2.0f * p1) +
            (-p0 + p2) * t +
            (2.0f * p0 - 5.0f * p1 + 4.0f * p2 - p3) * t2 +
            (-p0 + 3.0f * p1 - 3.0f * p2 + p3) * t3)
    }

    /** 边缘带格分类：四角全在轮廓内 → bit0；树锚点（格底边中点）在内 → bit1 */
    private fun classifyFringeTile(
        x0: Float,
        y0: Float,
        x1: Float,
        y1: Float,
        t: Float,
        poly: FloatArray
    ): Int {
        val quadFull = pointInPolygon(x0, y0, poly) &&
            pointInPolygon(x1, y0, poly) &&
            pointInPolygon(x0, y1, poly) &&
            pointInPolygon(x1, y1, poly)
        val treeFoot = pointInPolygon(x0 + t * 0.5f, y1, poly)
        return (if (quadFull) MASK_BIT_QUAD else 0) or (if (treeFoot) MASK_BIT_TREE else 0)
    }
}

// ── 文件级几何/三角化原语（零状态纯函数；文件级组织 = 对象只留管线入口）──

/** 折线有向面积二倍（shoelace；屏幕坐标 Y 向下顺时针 ⇒ 正） */
private fun groundArea2(poly: FloatArray): Float {
    var a = 0.0f
    val n = poly.size / 2
    for (i in 0 until n) {
        val j = (i + 1) % n
        a += poly[i * 2] * poly[j * 2 + 1] - poly[j * 2] * poly[i * 2 + 1]
    }
    return a
}

private fun groundCross2(ax: Float, ay: Float, bx: Float, by: Float, cx: Float, cy: Float) =
    (bx - ax) * (cy - by) - (by - ay) * (cx - bx)

/** 三角形内点判定（三半平面同号；边界算内） */
@Suppress("LongParameterList") // 三顶点成对几何原语（C++ 同构，逐行对照红线）
private fun groundPointInTri(
    px: Float,
    py: Float,
    ax: Float,
    ay: Float,
    bx: Float,
    by: Float,
    cx: Float,
    cy: Float
): Boolean {
    val d1 = groundCross2(ax, ay, bx, by, px, py)
    val d2 = groundCross2(bx, by, cx, cy, px, py)
    val d3 = groundCross2(cx, cy, ax, ay, px, py)
    val hasNeg = d1 < 0.0f || d2 < 0.0f || d3 < 0.0f
    val hasPos = d1 > 0.0f || d2 > 0.0f || d3 > 0.0f
    return !(hasNeg && hasPos)
}

/** 折线质心（底部带外法线朝向判定基准） */
private fun polyCentroid(poly: FloatArray): Pair<Float, Float> {
    val n = poly.size / 2
    var cx = 0f
    var cy = 0f
    for (i in 0 until n) {
        cx += poly[i * 2]
        cy += poly[i * 2 + 1]
    }
    return cx / n to cy / n
}

/** 折线累计弧长（arc[i] = 折线起点到顶点 i 的长度；arc[0] 回写周长） */
private fun cumulativeArc(poly: FloatArray): FloatArray {
    val n = poly.size / 2
    val arc = FloatArray(n + 1)
    for (i in 1..n) {
        val a = i - 1
        val b = i % n
        val dx = poly[b * 2] - poly[a * 2]
        val dy = poly[b * 2 + 1] - poly[a * 2 + 1]
        arc[i % n] = arc[a] + sqrt(dx * dx + dy * dy)
    }
    return arc
}

/**
 * 单边界段 → 底部带 quad（展平 6 顶点 × 8 float）；段退化 / 外法线不朝下返回 null。
 * 外法线（背离质心）y 分量 > 阈值才朝下挤出；UV u = 端点累计弧长/tileSize
 * （相邻 quad 共享端点 ⇒ UV 全等 ⇒ 岩石纹理无缝）；顶边 = 折线 − tuck（藏缝）。
 */
private fun bandSegmentQuad(
    tileSize: Int,
    bottomDepth: Float,
    cx: Float,
    cy: Float,
    arc: FloatArray,
    poly: FloatArray,
    i: Int
): List<Float>? {
    val n = poly.size / 2
    val t = tileSize.toFloat()
    val j = (i + 1) % n
    val ax = poly[i * 2]; val ay = poly[i * 2 + 1]
    val bx = poly[j * 2]; val by = poly[j * 2 + 1]
    val dx = bx - ax; val dy = by - ay
    val len = sqrt(dx * dx + dy * dy)
    if (len < 1.0e-6f) return null
    var nx = dy / len
    var ny = -dx / len
    val mx = (ax + bx) * 0.5f - cx
    val my = (ay + by) * 0.5f - cy
    if (nx * mx + ny * my < 0.0f) {
        nx = -nx
        ny = -ny
    }
    if (ny <= GroundBoundaryGenerator.BOTTOM_NORMAL_MIN_Y) return null
    val uA = arc[i] / t
    val uB = arc[j] / t
    val vTop = 0.0f
    val vBot = bottomDepth / t
    val topAy = ay - GroundBoundaryGenerator.BOTTOM_TUCK_PX
    val topBy = by - GroundBoundaryGenerator.BOTTOM_TUCK_PX
    val botAy = ay + bottomDepth - GroundBoundaryGenerator.BOTTOM_TUCK_PX
    val botBy = by + bottomDepth - GroundBoundaryGenerator.BOTTOM_TUCK_PX
    // 与 SpriteBatcher.add 同手性两三角：(A_top,B_top,A_bot)(B_top,B_bot,A_bot)
    val verts = listOf(
        floatArrayOf(ax, topAy, uA, vTop, 1f, 1f, 1f, 1f),
        floatArrayOf(bx, topBy, uB, vTop, 1f, 1f, 1f, 1f),
        floatArrayOf(ax, botAy, uA, vBot, 1f, 1f, 1f, 1f),
        floatArrayOf(bx, topBy, uB, vTop, 1f, 1f, 1f, 1f),
        floatArrayOf(bx, botBy, uB, vBot, 1f, 1f, 1f, 1f),
        floatArrayOf(ax, botAy, uA, vBot, 1f, 1f, 1f, 1f)
    )
    return verts.flatMap { it.asIterable() }
}

/** 耳切三角化：输出索引三元组（指向折线顶点）。要求简单多边形。 */
fun triangulatePolygon(poly: FloatArray): IntArray {
    val n = poly.size / 2
    if (n < 3) return IntArray(0)
    val ring = ArrayList<Int>(n)
    for (i in 0 until n) ring.add(i)
    if (groundArea2(poly) < 0.0f) ring.reverse()
    val out = ArrayList<Int>(n * 3)
    var guard = 0
    val guardMax = ring.size * ring.size + 16
    while (ring.size > 3 && guard++ < guardMax) {
        val k = findEar(poly, ring) ?: mostConvexIndex(poly, ring)
        emitEar(ring, k, out)
    }
    if (ring.size == 3) {
        out.add(ring[0])
        out.add(ring[1])
        out.add(ring[2])
    }
    return out.toIntArray()
}

/** 在环上找第一个合法耳（凸顶点且环内无他点落入该耳三角形）；无耳返回 null */
private fun findEar(poly: FloatArray, ring: ArrayList<Int>): Int? {
    val m = ring.size
    for (k in 0 until m) {
        val i0 = ring[(k + m - 1) % m]
        val i1 = ring[k]
        val i2 = ring[(k + 1) % m]
        val ax = poly[i0 * 2]; val ay = poly[i0 * 2 + 1]
        val bx = poly[i1 * 2]; val by = poly[i1 * 2 + 1]
        val cx = poly[i2 * 2]; val cy = poly[i2 * 2 + 1]
        if (groundCross2(ax, ay, bx, by, cx, cy) <= 0.0f) continue
        val contains = (0 until m).any { q ->
            val iq = ring[q]
            iq != i0 && iq != i1 && iq != i2 &&
                groundPointInTri(poly[iq * 2], poly[iq * 2 + 1], ax, ay, bx, by, cx, cy)
        }
        if (!contains) return k
    }
    return null
}

/** 数值兜底：剪掉叉积最大的顶点（最凸），保证终止（理论不可达路径） */
private fun mostConvexIndex(poly: FloatArray, ring: ArrayList<Int>): Int {
    var best = 0
    var bestCross = -Float.MAX_VALUE
    for (k in ring.indices) {
        val a = ring[(k + ring.size - 1) % ring.size]
        val b = ring[k]
        val c = ring[(k + 1) % ring.size]
        val cr = groundCross2(
            poly[a * 2], poly[a * 2 + 1],
            poly[b * 2], poly[b * 2 + 1],
            poly[c * 2], poly[c * 2 + 1]
        )
        if (cr > bestCross) {
            bestCross = cr
            best = k
        }
    }
    return best
}

/** 剪掉 ring[k]：输出耳三角形三元组并从环中移除 */
private fun emitEar(ring: ArrayList<Int>, k: Int, out: ArrayList<Int>) {
    val m = ring.size
    out.add(ring[(k + m - 1) % m])
    out.add(ring[k])
    out.add(ring[(k + 1) % m])
    ring.removeAt(k)
}
