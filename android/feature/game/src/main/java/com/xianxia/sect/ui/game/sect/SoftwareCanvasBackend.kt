package com.xianxia.sect.ui.game.sect

import android.graphics.*
import com.xianxia.sect.core.render.SpriteAtlasDef
import com.xianxia.sect.core.render.SpriteRect
import kotlin.math.roundToInt

/**
 * SoftwareCanvasBackend — Canvas 软件回退渲染器（v2.1 缓存优化版）。
 *
 * 当 Vulkan 原生渲染不可用时（模拟器/MTK/华为等），使用 Android Canvas API
 * 在 CPU 端绘制宗门地图帧，通过 [NativeSurfaceView.RenderThread] 以
 * lockCanvas/unlockCanvasAndPost 输出到 Surface。
 *
 * ## 缓存策略（P2.2）
 * 1. 地面/装饰层 — 瓦片数据不变时使用 tileCache
 * 2. 建筑层 — 建筑物不变时使用 buildingCache（独立于地面层）
 * 3. 帧缓冲区 — 视口大小，resize 时重建
 *
 * ## 热控联动（P1.4）
 * - [qualityFactor]：由 ThermalController 驱动，降低时跳过装饰层绘制、使用低色深
 *
 * @param config NativeRenderConfig（tileSize, worldWidthCells 等）
 */
class SoftwareCanvasBackend(
    private val config: NativeRenderConfig
) {
    companion object {
        private const val TAG = "SoftwareCanvasBackend"
        /** 灵田在图集中的索引（BUILDING_NAMES 中排第3，index=2） */
        private const val SPIRIT_FIELD_ATLAS_INDEX = 2
        /** 灵矿场在图集中的索引（BUILDING_NAMES 中排第1，index=0） */
        private const val SPIRIT_MINE_ATLAS_INDEX = 0
        /** 灵矿场地皮覆盖在 FloorTileType 中的 ordinal（第5个，index=4） */
        private const val SPIRIT_MINE_GROUND_FT_INDEX = 4
        /** GROUND_V2 在图集 TILE_SRC_RECTS 中的索引 */
        private const val GROUND_V2_SRC_INDEX = 7
    }

    /** 渲染质量因子（0.0~1.0），由 ThermalController 设置 */
    @Volatile
    var qualityFactor: Float = 1.0f

    /** 是否关闭装饰层（热控降级时跳过草/树绘制） */
    @Volatile
    var decorationsDisabled: Boolean = false

    @Volatile
    private var frameBuffer: Bitmap? = null
    @Volatile
    private var frameCanvas: Canvas? = null
    @Volatile
    private var currentViewportW: Int = 0
    @Volatile
    private var currentViewportH: Int = 0

    /** resize 请求（UI 线程写，RenderThread 消费），避免跨线程 bitmap 操作 */
    @Volatile
    private var resizeRequested: Boolean = false
    @Volatile
    private var resizeRequestedW: Int = 0
    @Volatile
    private var resizeRequestedH: Int = 0

    private var lastBuildingCamX: Float = 0f
    private var lastBuildingCamY: Float = 0f
    private var lastBuildingScale: Float = 1f
    private var lastDecorationsDisabled: Boolean = false
    /** 上一帧的预览状态 — 变化时强制清屏，防止绿色残影残留 */
    private var lastShowPreview: Boolean = false

    /**
     * 确保帧缓冲区是视口大小。当窗口 resize 时自动重建。
     *
     * 线程安全：仅在 RenderThread 中调用，bitmap 创建/回收均在此方法内完成。
     * [resize] 仅设信号量，不触碰 bitmap，杜绝跨线程回收竞态。
     */
    private fun ensureFrameBuffer(vpWIn: Int, vpHIn: Int) {
        var vpW = vpWIn
        var vpH = vpHIn
        // 消费 resize 请求（UI 线程 → RenderThread 信号）
        if (resizeRequested) {
            resizeRequested = false
            vpW = resizeRequestedW
            vpH = resizeRequestedH
        }
        if (vpW <= 0 || vpH <= 0) return
        val fb = frameBuffer
        if (fb == null || fb.width != vpW || fb.height != vpH) {
            val config = if (qualityFactor < 0.6f) Bitmap.Config.RGB_565 else Bitmap.Config.ARGB_8888
            frameBuffer?.recycle()
            frameBuffer = Bitmap.createBitmap(vpW.coerceAtLeast(1), vpH.coerceAtLeast(1), config)
            frameCanvas = Canvas(frameBuffer ?: return)
            currentViewportW = vpW
            currentViewportH = vpH
            tileCacheValid = false
        }
    }

    /** 精灵绘制 Paint（邻近滤波，保持像素风格） */
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG).apply {
        isFilterBitmap = false
        isAntiAlias = false
        isDither = false
    }

    /** 预览精灵用 Paint（带色彩矩阵调色） */
    private val previewPaint = Paint(Paint.FILTER_BITMAP_FLAG).apply {
        isFilterBitmap = false
        isAntiAlias = false
    }

    /** 复用 Rect 对象减少分配 */
    private val reuseRect = Rect()

    // ============================================================
    // 精灵图源矩形（与 NativeBridge::drawAllTiles 的 UV 映射一致）
    // ============================================================

    /** 瓦片精灵在图集中的源矩形（索引 = tile 类型，来自 SpriteAtlasDef） */
    private val TILE_SRC_RECTS: Array<Rect> by lazy {
        val rects = arrayOfNulls<Rect>(SpriteAtlasDef.TileType.values().size)
        for (tile in SpriteAtlasDef.TileType.values()) {
            val sr = tile.rect
            rects[tile.index] = Rect(sr.x, sr.y, sr.x + sr.w, sr.y + sr.h)
        }
        @Suppress("UNCHECKED_CAST")
        rects as Array<Rect>
    }

    /** 建筑精灵在图集中的源矩形（来自 SpriteAtlasDef BUILDING_NAMES 顺序） */
    private val BUILDING_SRC_RECTS: Array<Rect> by lazy {
        val rects = arrayOfNulls<Rect>(SpriteAtlasDef.BUILDING_NAMES.size)
        for (i in rects.indices) {
            val sr = SpriteAtlasDef.buildingRect(i)
            rects[i] = Rect(sr.x, sr.y, sr.x + sr.w, sr.y + sr.h)
        }
        @Suppress("UNCHECKED_CAST")
        rects as Array<Rect>
    }

    /** 地砖精灵在图集中的源矩形（索引 = FloorTileType ordinal） */
    private val FLOOR_TILE_SRC_RECTS: Array<Rect> by lazy {
        val rects = arrayOfNulls<Rect>(SpriteAtlasDef.FloorTileType.values().size)
        for (ft in SpriteAtlasDef.FloorTileType.values()) {
            val r = ft.pixelRect
            rects[ft.ordinal] = Rect(r.x, r.y, r.x + r.w, r.y + r.h)
        }
        @Suppress("UNCHECKED_CAST")
        rects as Array<Rect>
    }

    // ============================================================
    // 帧缓存（地面层 + 建筑层分离缓存）
    // ============================================================

    /** 上次渲染的 tileData 版本（不变时跳过地面/装饰重绘） */
    private var lastTileDataHash: Int = 0
    /** 上次渲染的建筑数据 hash */
    private var lastBuildingHash: Int = 0
    @Volatile
    private var tileCache: Bitmap? = null
    @Volatile
    private var tileCacheValid: Boolean = false

    /**
     * 渲染一帧到帧缓冲区。
     *
     * @param frame  当前帧渲染数据（RenderFrame），两后端统一消费
     * @param atlas  2048×2048 纹理图集 Bitmap
     * @param vpW    视口宽度（屏幕像素）
     * @param vpH    视口高度（屏幕像素）
     * @return 渲染好的帧缓冲区 Bitmap
     */
    fun renderFrame(
        frame: RenderFrame,
        atlas: Bitmap,
        vpW: Int,
        vpH: Int
    ): Bitmap? {
        ensureFrameBuffer(vpW, vpH)
        val canvas = frameCanvas ?: return null
        val fb = frameBuffer ?: return null
        val tileSize = config.tileSize
        val cols = frame.cols
        val rows = frame.rows
        val td = frame.tileData
        val buildingDataArray = frame.buildingData
        // NaN/Infinity 防御 — roundToInt 对 NaN 抛异常，直接返回 null
        if (frame.camX.isNaN() || frame.camX.isInfinite() ||
            frame.camY.isNaN() || frame.camY.isInfinite() ||
            frame.scale.isNaN() || frame.scale.isInfinite()) {
            android.util.Log.w(TAG, "renderFrame: NaN/Inf in camera/scale")
            return fb
        }
        val scale = frame.scale.coerceAtLeast(0.1f)

        // 视锥剔除计算
        val viewLeft = frame.camX
        val viewTop = frame.camY
        val viewRight = frame.camX + vpW / scale
        val viewBottom = frame.camY + vpH / scale

        val firstCol = (viewLeft / tileSize).toInt().coerceIn(0, cols - 1)
        val firstRow = (viewTop / tileSize).toInt().coerceIn(0, rows - 1)
        val lastCol = ((viewRight / tileSize).toInt() + 1).coerceIn(0, cols - 1)
        val lastRow = ((viewBottom / tileSize).toInt() + 1).coerceIn(0, rows - 1)

        val tileHash = td.contentHashCode()
        val buildingHash = buildingDataArray?.contentHashCode() ?: 0

        // 相机/缩放变化（影响地面/建筑缓存有效性）
        val cameraChanged = frame.camX != lastBuildingCamX ||
            frame.camY != lastBuildingCamY || scale != lastBuildingScale

        val decorChanged = decorationsDisabled != lastDecorationsDisabled
        val needRebuildTiles = tileHash != lastTileDataHash
            || !tileCacheValid || cameraChanged || decorChanged
            || lastShowPreview != frame.showPreview

        val needRebuildBuildings = buildingHash != lastBuildingHash
            || cameraChanged || needRebuildTiles

        // ============================
        // Step A: 地面 + 装饰层
        // ============================
        if (needRebuildTiles) {
            canvas.drawColor(Color.rgb(0xF2, 0xED, 0xE4))
            lastTileDataHash = tileHash
            lastShowPreview = frame.showPreview

            for (row in firstRow..lastRow) {
                val rowBase = row * cols
                for (col in firstCol..lastCol) {
                    val tile = td[rowBase + col]
                    val wx = col * tileSize
                    val wy = row * tileSize

                    val camOffX = wx - frame.camX
                    val camOffY = wy - frame.camY
                    val dstLeft = (camOffX * scale).roundToInt()
                    val dstTop = (camOffY * scale).roundToInt()
                    val dstRight = ((camOffX + tileSize) * scale).roundToInt()
                    val dstBottom = ((camOffY + tileSize) * scale).roundToInt()

                    if (dstLeft >= vpW || dstTop >= vpH
                        || dstRight <= 0 || dstBottom <= 0) continue

                    // A1: 地面底图
                    val gIdx = if (tile == GROUND_V2_SRC_INDEX)
                        GROUND_V2_SRC_INDEX else 0
                    val groundSrc = TILE_SRC_RECTS[gIdx]
                    drawTile(canvas, atlas, groundSrc,
                        dstLeft, dstTop,
                        dstRight - dstLeft, dstBottom - dstTop)

                    // A2: 装饰叠加（热控降级时跳过）
                    if (!decorationsDisabled && tile in 1..5) {
                        val decorSrc = TILE_SRC_RECTS[tile]
                        if (tile >= 4) {
                            // 树（2×2 格，偏移 (-tileSize, -tileSize)）
                            val tCamOffX = wx - tileSize - frame.camX
                            val tCamOffY = wy - tileSize - frame.camY
                            val treeLeft = (tCamOffX * scale).roundToInt()
                            val treeTop = (tCamOffY * scale).roundToInt()
                            val treeRight = ((tCamOffX + 2 * tileSize) * scale)
                                .roundToInt()
                            val treeBottom =
                                ((tCamOffY + 2 * tileSize) * scale)
                                .roundToInt()
                            drawTile(canvas, atlas, decorSrc,
                                treeLeft, treeTop,
                                treeRight - treeLeft, treeBottom - treeTop)
                        } else {
                            // 草（1×1 格）— 复用地面边界
                            drawTile(canvas, atlas, decorSrc,
                                dstLeft, dstTop,
                                dstRight - dstLeft, dstBottom - dstTop)
                        }
                    }
                }
            }
            buildTileCache(vpW, vpH)
            lastDecorationsDisabled = decorationsDisabled
        }

        // ============================
        // Step B: 建筑层（独立缓存）
        // ============================
        if (needRebuildBuildings) {
            if (!needRebuildTiles) {
                canvas.drawColor(Color.rgb(0xF2, 0xED, 0xE4))
                restoreGroundFromCache()
            }

            if (buildingDataArray != null && frame.buildingVisible) {
                val buildingCount = frame.buildingCount.coerceAtMost(buildingDataArray.size / 5)
                for (i in 0 until buildingCount) {
                    val idx = i * 5
                    val gx = buildingDataArray[idx].toInt()
                    val gy = buildingDataArray[idx + 1].toInt()
                    val bw = buildingDataArray[idx + 2].toInt()
                    val bh = buildingDataArray[idx + 3].toInt()
                    val nameIdx = buildingDataArray[idx + 4].toInt()

                    // 使用占地尺寸居中偏移精灵：精灵居中于占地网格
                    val (fpW, fpH) = SpriteAtlasDef.FOOTPRINT_BY_NAME_INDEX
                        .getOrElse(nameIdx) { 2 to 2 }
                    val offsetX = (fpW - bw) * tileSize * 0.5f
                    val offsetY = (fpH - bh) * tileSize.toFloat()
                    val bWorldX = (gx * tileSize).toFloat() + offsetX
                    val bWorldY = (gy * tileSize).toFloat() + offsetY
                    val bWorldW = (bw * tileSize).toFloat()
                    val bWorldH = (bh * tileSize).toFloat()
                    val bCamOffX = bWorldX - frame.camX
                    val bCamOffY = bWorldY - frame.camY
                    val bDstLeft = (bCamOffX * scale).roundToInt()
                    val bDstTop = (bCamOffY * scale).roundToInt()
                    val bDstRight =
                        ((bCamOffX + bWorldW) * scale).roundToInt()
                    val bDstBottom =
                        ((bCamOffY + bWorldH) * scale).roundToInt()

                    // 地砖使用占地尺寸（而非精灵尺寸）
                    val ftCamOffX = gx * tileSize - frame.camX
                    val ftCamOffY = gy * tileSize - frame.camY
                    val ftDstLeft = (ftCamOffX * scale).roundToInt()
                    val ftDstTop = (ftCamOffY * scale).roundToInt()
                    val ftDstRight =
                        ((ftCamOffX + fpW * tileSize) * scale).roundToInt()
                    val ftDstBottom =
                        ((ftCamOffY + fpH * tileSize) * scale).roundToInt()

                    if (bDstLeft >= vpW || bDstTop >= vpH
                        || bDstRight <= 0 || bDstBottom <= 0) continue

                    // A) 地砖底座（灵田除外），按占地尺寸绘制。
                    //    灵矿场使用专属地皮覆盖纹理，其他建筑使用通用地砖。
                    if (nameIdx == SPIRIT_MINE_ATLAS_INDEX) {
                        val ftIdx = SPIRIT_MINE_GROUND_FT_INDEX
                        val ftSrc = FLOOR_TILE_SRC_RECTS.getOrNull(ftIdx)
                        if (ftSrc != null) {
                            drawTile(canvas, atlas, ftSrc,
                                ftDstLeft, ftDstTop,
                                ftDstRight - ftDstLeft, ftDstBottom - ftDstTop)
                        }
                    } else if (nameIdx != SPIRIT_FIELD_ATLAS_INDEX) {
                        val ftIdx = SpriteAtlasDef.floorTileIndex(fpW, fpH)
                        if (ftIdx >= 0) {
                            val ftSrc = FLOOR_TILE_SRC_RECTS.getOrNull(ftIdx)
                            if (ftSrc != null) {
                                drawTile(canvas, atlas, ftSrc,
                                    ftDstLeft, ftDstTop,
                                    ftDstRight - ftDstLeft, ftDstBottom - ftDstTop)
                            }
                        }
                    }

                    val srcRect = BUILDING_SRC_RECTS.getOrNull(nameIdx) ?: continue
                    drawTile(canvas, atlas, srcRect,
                        bDstLeft, bDstTop,
                        bDstRight - bDstLeft, bDstBottom - bDstTop)
                }

                lastBuildingCamX = frame.camX
                lastBuildingCamY = frame.camY
                lastBuildingScale = scale
            }

            lastBuildingHash = buildingHash
        }

        // ============================
        // Step C: 预览精灵（建造/移动模式）
        // ============================
        if (frame.showPreview) {
            drawPreview(canvas, atlas, frame, tileSize, scale, frame.camX, frame.camY)
        }

        return fb
    }

    /** 从地面缓存恢复地面层（当仅建筑变化时使用） */
    private fun restoreGroundFromCache() {
        val cache = tileCache
        val fc = frameCanvas ?: return
        if (cache != null && tileCacheValid) {
            fc.drawBitmap(cache, 0f, 0f, paint)
        }
    }

    /**
     * 构建地面/装饰缓存 — 将当前帧缓冲区地面层快照保存到
     * tileCache。
     *
     * 当后续帧仅建筑变化（如建造预览）时，
     * [restoreGroundFromCache] 从此缓存恢复，避免重建地面层。
     */
    private fun buildTileCache(vpW: Int, vpH: Int) {
        val fb = frameBuffer ?: return
        if (vpW <= 0 || vpH <= 0) return
        var cache = tileCache
        if (cache == null || cache.width != vpW || cache.height != vpH) {
            tileCache?.recycle()
            val cfg = if (qualityFactor < 0.6f)
                Bitmap.Config.RGB_565 else Bitmap.Config.ARGB_8888
            cache = Bitmap.createBitmap(vpW, vpH, cfg)
            tileCache = cache
        }
        val cacheCanvas = Canvas(cache)
        cacheCanvas.drawColor(Color.rgb(0xF2, 0xED, 0xE4))
        cacheCanvas.drawBitmap(fb, 0f, 0f, paint)
        tileCacheValid = true
    }

    /**
     * 在 [canvas] 上绘制一个精灵（复用 Rect 减少分配）。
     */
    private fun drawTile(
        canvas: Canvas,
        atlas: Bitmap,
        srcRect: Rect,
        dstX: Int, dstY: Int,
        dstW: Int, dstH: Int
    ) {
        reuseRect.set(dstX, dstY, dstX + dstW, dstY + dstH)
        canvas.drawBitmap(atlas, srcRect, reuseRect, paint)
    }

    /**
     * 绘制半透明建筑预览（建造/移动模式）。
     * 对应 C++ NativeBridge::drawSprite + vertexColor 调色。
     *
     * ★ 修复：应用相机偏移 (camX, camY) 和缩放 (scale)。
     */
    private fun drawPreview(
        canvas: Canvas,
        atlas: Bitmap,
        frame: RenderFrame,
        tileSize: Int,
        scale: Float,
        camX: Float,
        camY: Float
    ) {
        val pOffX = frame.previewX - camX
        val pOffY = frame.previewY - camY
        val dstLeft = (pOffX * scale).roundToInt()
        val dstTop = (pOffY * scale).roundToInt()
        val dstRight = ((pOffX + frame.previewW) * scale).roundToInt()
        val dstBottom = ((pOffY + frame.previewH) * scale).roundToInt()
        val pw = dstRight - dstLeft
        val ph = dstBottom - dstTop
        if (pw <= 0 || ph <= 0) return
        // 视口剔除：预览精灵完全在屏幕外时不绘制
        if (dstLeft >= canvas.width || dstTop >= canvas.height
            || dstRight <= 0 || dstBottom <= 0) return

        val u0 = frame.previewU0
        val v0 = frame.previewV0
        val u1 = frame.previewU1
        val v1 = frame.previewV1

        val atlasW = atlas.width
        val atlasH = atlas.height
        val srcLeft = (u0 * atlasW).toInt()
        val srcTop = (v0 * atlasH).toInt()
        val srcRight = (u1 * atlasW).toInt()
        val srcBottom = (v1 * atlasH).toInt()

        val alpha = frame.previewAlpha.coerceIn(0f, 1f)
        previewPaint.alpha = (alpha * 255).toInt()
        previewPaint.colorFilter = ColorMatrixColorFilter(
            ColorMatrix(floatArrayOf(
                frame.previewTintRed, 0f, 0f, 0f, 0f,
                0f, frame.previewTintGreen, 0f, 0f, 0f,
                0f, 0f, frame.previewTintBlue, 0f, 0f,
                0f, 0f, 0f, 1f, 0f
            ))
        )

        canvas.drawBitmap(atlas,
            Rect(srcLeft, srcTop, srcRight, srcBottom),
            Rect(dstLeft, dstTop, dstRight, dstBottom),
            previewPaint)

        previewPaint.colorFilter = null
        previewPaint.alpha = 255
    }

    /** 调整视口尺寸（resize 时调用）。仅设信号量，不触碰 bitmap，杜绝跨线程回收竞态。 */
    fun resize(width: Int, height: Int) {
        if (width > 0 && height > 0) {
            resizeRequestedW = width
            resizeRequestedH = height
            resizeRequested = true
        }
    }

    /** 释放资源。仅在 RenderThread 停止后调用（[NativeSurfaceView.surfaceDestroyed] 中已 join） */
    fun release() {
        tileCache?.recycle()
        tileCache = null
        tileCacheValid = false
        frameBuffer?.recycle()
        frameBuffer = null
        frameCanvas = null
    }
}
