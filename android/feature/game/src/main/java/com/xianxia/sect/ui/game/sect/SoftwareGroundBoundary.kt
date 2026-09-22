package com.xianxia.sect.ui.game.sect

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import com.xianxia.sect.core.render.GroundBoundaryBridge
import com.xianxia.sect.core.render.GroundBoundaryGenerator
import com.xianxia.sect.core.render.NativeRenderConfig

/**
 * SoftwareGroundBoundary — Canvas 软渲染路径的弯曲地皮轮廓消费器（地图边缘 v2）。
 *
 * 从 [SoftwareCanvasBackend] 下沉的独立职责块（该类已贴 FileLength/LargeClass
 * 阈值）：轮廓复合数据解析缓存（帧数据身份变更才重建）、chunk 合成期轮廓裁切、
 * 底部岩石带填充（独立 REPEAT 岩石材质，tuck 藏缝同 GPU 路径）、chunk 烘焙期
 * 逐格装饰掩码。几何与 C++ buildBottomRockLayer/buildGroundMeshLayer 同源同一
 * 折线（ground_boundary.h 单一权威）。
 *
 * 渲染线程单消费者（与 [SoftwareCanvasBackend] 其余成员同纪律）。
 */
internal class SoftwareGroundBoundary(private val config: NativeRenderConfig) {

    /** 上一次消费的边界复合数据引用（身份比较——与 SceneUpdateChannel 同纪律） */
    private var cachedSource: FloatArray? = null

    private var worldPath: Path? = null
    private var bandPath: Path? = null
    private var maskSource: FloatArray? = null
    private var maskOffset = 0
    private var maskCols = 0

    /** 岩石带 Paint（shader 矩阵按岩石位图身份懒重建） */
    private val bandPaint = Paint().apply {
        isFilterBitmap = true
        isAntiAlias = true
    }
    private var bandShaderSource: Bitmap? = null

    /**
     * 导入帧边界数据（数组身份变化才重新解析）。
     * @return true = 数据发生变化（调用方应整体失效 chunk 缓存——新轮廓改变
     * clip 与装饰掩码）；false = 未变化 / 无边界。
     */
    fun update(source: FloatArray?): Boolean {
        if (source === cachedSource) return false
        cachedSource = source
        val parsed = parse(source)
        worldPath = parsed?.first
        bandPath = parsed?.second
        return true
    }

    /** 是否有可用轮廓数据 */
    fun hasData(): Boolean = worldPath != null

    /**
     * 掩码位（bit0 = 格四角在轮廓内——平铺装饰可画；bit1 = 树锚点在内）。
     * 无数据 = 3（全放行——与 GPU 路径 tileMask=nullptr 同降级口径）。
     */
    fun maskAt(row: Int, col: Int): Int {
        val src = maskSource ?: return MASK_ALL
        val idx = maskOffset + row * maskCols + col
        if (idx >= src.size) return MASK_ALL
        return src[idx].toInt()
    }

    /**
     * 底部岩石带绘制（世界变换下填充 bandPath；岩石 BitmapShader REPEAT，
     * 局部矩阵 scale = 纹理宽/平铺周期——每 [GroundBoundaryBridge.BOTTOM_TEX_REPEAT_PX]
     * 世界像素一贴，与 GPU 路径 UV=世界/周期同口径）。
     * rock=null 或无边界 = 整层跳过（降级而非黑屏）。
     */
    fun drawBand(
        canvas: android.graphics.Canvas,
        camX: Float,
        camY: Float,
        drawScale: Float,
        fadeAlpha: Float,
        rock: Bitmap?,
        yScale: Float
    ) {
        val band = bandPath ?: return
        val bitmap = rock ?: return
        if (bitmap !== bandShaderSource) {
            bandShaderSource = bitmap
            val shader = BitmapShader(bitmap, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
            val scale = bitmap.width.toFloat() / GroundBoundaryBridge.BOTTOM_TEX_REPEAT_PX
            shader.setLocalMatrix(Matrix().apply { setScale(scale, scale) })
            bandPaint.shader = shader
        }
        bandPaint.alpha = (fadeAlpha.coerceIn(0f, 1f) * 255).toInt()
        canvas.save()
        canvas.translate(-camX * drawScale, -camY * drawScale * yScale)
        canvas.scale(drawScale, drawScale * yScale)
        canvas.drawPath(band, bandPaint)
        canvas.restore()
        bandPaint.alpha = 255
    }

    /**
     * 屏幕空间轮廓裁切路径（chunk 合成期用；null = 无边界免裁切）。
     * 世界折线 Path 经一次 世界→屏幕 变换（含俯视 Y 压缩）生成。
     */
    fun screenClipPath(camX: Float, camY: Float, drawScale: Float, yScale: Float): Path? {
        val world = worldPath ?: return null
        val m = Matrix().apply {
            setScale(drawScale, drawScale * yScale)
            postTranslate(-camX * drawScale, -camY * drawScale * yScale)
        }
        return Path(world).apply { transform(m) }
    }

    /**
     * chunk 是否完全落在内缩安全带内（恒在轮廓内 → 合成期免 clipPath）。
     * 安全带 = 地图矩形内缩 MAX_INSET——轮廓采样点受振幅不变式约束，不深于该带。
     */
    fun chunkInsideSafeBand(chunkCol: Int, chunkRow: Int, chunkSizeTiles: Int, chunkPixel: Int): Boolean {
        val t = config.tileSize.toFloat()
        val insetX = GroundBoundaryBridge.MAX_INSET * config.worldWidthCells * t
        val insetY = GroundBoundaryBridge.MAX_INSET * config.worldHeightCells * t
        val x0 = chunkCol * chunkSizeTiles * t
        val y0 = chunkRow * chunkSizeTiles * t
        val x1 = x0 + chunkPixel
        val y1 = y0 + chunkPixel
        return x0 >= insetX && y0 >= insetY &&
            x1 <= config.worldWidthCells * t - insetX &&
            y1 <= config.worldHeightCells * t - insetY
    }

    /** 复合数据 → (轮廓 Path, 岩石带 Path) + 掩码视图（非法/版本不符 = null） */
    @Suppress("ReturnCount") // 防御性解析早退合同（与 C++ groundBoundaryParse 同构）
    private fun parse(src: FloatArray?): Pair<Path, Path>? {
        if (src == null || src.size <= GroundBoundaryBridge.Header.FLOATS) return null
        val f = GroundBoundaryBridge.Header.Field
        if (src[f.VERSION].toInt() != GroundBoundaryBridge.Header.VERSION) return null
        val polyCount = src[f.POLY_COUNT].toInt()
        val cols = src[f.COLS].toInt()
        val rows = src[f.ROWS].toInt()
        if (polyCount < 3 || cols <= 0 || rows <= 0) return null
        val maskOffset = src[f.MASK_OFFSET].toInt()
        val groundMeshOffset = src[f.GROUND_MESH_OFFSET].toInt()
        if (maskOffset != GroundBoundaryBridge.Header.FLOATS + polyCount * 2) return null
        if (maskOffset + cols * rows != groundMeshOffset) return null

        val header = GroundBoundaryBridge.Header.FLOATS
        val path = Path()
        path.moveTo(src[header], src[header + 1])
        for (i in 1 until polyCount) {
            path.lineTo(src[header + i * 2], src[header + i * 2 + 1])
        }
        path.close()

        val band = buildBottomBandPath(src, header, polyCount, src[f.BOTTOM_DEPTH])
        maskSource = src
        this.maskOffset = maskOffset
        maskCols = cols
        return path to band
    }

    /**
     * 底部带 Path：连续「朝下」段为一条子路径（顶边前进 + 底边折返）。
     * 每折线点列深 = [GroundBoundaryGenerator.bottomColumnDepths]（与 GPU
     * bottom mesh 同源的确定性深度剖面）——底缘自然不规则，非平行带。
     */
    private fun buildBottomBandPath(src: FloatArray, header: Int, polyCount: Int, bottomDepth: Float): Path {
        val band = Path()
        if (bottomDepth <= 0f) return band
        val tuck = GroundBoundaryBridge.BOTTOM_TUCK_PX
        val poly = FloatArray(polyCount * 2)
        for (i in 0 until polyCount) {
            poly[i * 2] = src[header + i * 2]
            poly[i * 2 + 1] = src[header + i * 2 + 1]
        }
        val colDepth = GroundBoundaryGenerator.bottomColumnDepths(poly, bottomDepth)
        if (colDepth.isEmpty()) return band
        val cx: Float
        val cy: Float
        centroidOf(poly).let { cx = it.first; cy = it.second }
        val run = ArrayList<Int>(8)
        for (i in 0 until polyCount) {
            if (segmentFacesDown(poly, i, cx, cy)) {
                // 连续段共享端点只收一次（run.last()==i 时段 i−1 已收过该点）
                if (run.isEmpty() || run.last() != i) run.add(i)
                run.add((i + 1) % polyCount)
            } else {
                flushRun(band, poly, run, colDepth, tuck)
            }
        }
        flushRun(band, poly, run, colDepth, tuck)
        return band
    }

    /** 折线质心（底部带外法线朝向判定基准，与 C++/Generator 同式） */
    private fun centroidOf(poly: FloatArray): Pair<Float, Float> {
        val n = poly.size / 2
        var cx = 0f
        var cy = 0f
        for (i in 0 until n) {
            cx += poly[i * 2]
            cy += poly[i * 2 + 1]
        }
        return cx / n to cy / n
    }

    /** 段 i 的外法线（背离质心）y 分量是否朝屏幕下方（与 C++ buildBottomMesh 同式） */
    private fun segmentFacesDown(poly: FloatArray, i: Int, cx: Float, cy: Float): Boolean {
        val n = poly.size / 2
        val j = (i + 1) % n
        val ax = poly[i * 2]
        val ay = poly[i * 2 + 1]
        val bx = poly[j * 2]
        val by = poly[j * 2 + 1]
        val dx = bx - ax
        val dy = by - ay
        val len = kotlin.math.sqrt(dx * dx + dy * dy)
        if (len < 1.0e-6f) return false
        var nx = dy / len
        var ny = -dx / len
        val mx = (ax + bx) * 0.5f - cx
        val my = (ay + by) * 0.5f - cy
        if (nx * mx + ny * my < 0f) {
            nx = -nx
            ny = -ny
        }
        return ny > GroundBoundaryBridge.BOTTOM_NORMAL_MIN_Y
    }

    /** 收笔一条连续朝下段子路径：顶边前进（−tuck 藏缝）+ 锯齿底边折返 + 闭合 */
    private fun flushRun(
        band: Path,
        poly: FloatArray,
        run: MutableList<Int>,
        colDepth: FloatArray,
        tuck: Float
    ) {
        if (run.isEmpty()) return
        band.moveTo(poly[run[0] * 2], poly[run[0] * 2 + 1] - tuck)
        for (k in 1 until run.size) {
            band.lineTo(poly[run[k] * 2], poly[run[k] * 2 + 1] - tuck)
        }
        for (k in run.size - 1 downTo 0) {
            band.lineTo(poly[run[k] * 2], poly[run[k] * 2 + 1] + colDepth[run[k]] - tuck)
        }
        band.close()
        run.clear()
    }

    companion object {
        /** 掩码全放行（无数据降级口径） */
        private const val MASK_ALL = 3
    }
}
