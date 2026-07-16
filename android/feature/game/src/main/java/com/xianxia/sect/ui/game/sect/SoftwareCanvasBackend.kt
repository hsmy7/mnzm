package com.xianxia.sect.ui.game.sect

import android.graphics.*
import com.xianxia.sect.core.render.SpriteAtlasDef
import kotlin.math.roundToInt

/**
 * SoftwareCanvasBackend — Canvas 软件回退渲染器 v3（Chunk 缓存版）。
 *
 * ## 渲染策略
 * 1. Chunk 缓存（32×32 tiles）：相机移动不失效，~4 次 drawBitmap/帧
 * 2. EWMA 帧时间追踪：动态切换 60/45/30/20fps，消除帧间隔抖动
 *
 * ## 行业参考
 * - CoC 分层 tile 组装：offscreen buffer 大 1 tile
 * - Godot batching：`renderingQuadrantSize=16`，预光栅化 CPU 100%→10%
 * - Mozilla SW-WR：scissored clear + 帧时间追踪自适应帧率
 *
 * @param config NativeRenderConfig（tileSize, worldWidthCells 等）
 */
class SoftwareCanvasBackend(
    private val config: NativeRenderConfig
) {
    companion object {
        private const val TAG = "SoftwareCanvasBackend"

        // ── Chunk 化常量 ──
        private const val CHUNK_SIZE_TILES = 32
        private val CHUNK_PIXEL = CHUNK_SIZE_TILES * 32  // 1024px
        private val NUM_CHUNKS_COL = 128 / CHUNK_SIZE_TILES  // 4
        private val NUM_CHUNKS_ROW = 128 / CHUNK_SIZE_TILES  // 4

        // ── EWMA 帧率自适应常量 ──
        private const val EWMA_ALPHA = 0.3f
        private const val FPS_HYSTERESIS_MS = 1000L

        // ── 缩放保护常量 ──
        private const val MIN_SCALE = 0.1f
        private const val MAX_SCALE = 3.0f

        // ── 图集索引常量 ──
        private const val SPIRIT_FIELD_ATLAS_INDEX = 2
        private const val SPIRIT_MINE_ATLAS_INDEX = 0
        private const val SPIRIT_MINE_GROUND_FT_INDEX = 4
        private const val GROUND_V2_SRC_INDEX = 7

        /**
         * 工具方法：绘制建筑列表到指定 Canvas。
         * 被 [ChunkTile.rebuild] 调用。
         */
        private fun drawBuildingsToCanvas(
            canvas: Canvas,
            atlas: Bitmap,
            buildingArray: FloatArray,
            buildingCount: Int,
            tileSize: Int,
            buildingSrcRects: Array<Rect>,
            floorTileSrcRects: Array<Rect>,
            paint: Paint,
            reuseRect: Rect,
            camX: Float,
            camY: Float,
            scale: Float,
            vpW: Int,
            vpH: Int
        ) {
            val count = buildingCount.coerceAtMost(buildingArray.size / 5)
            for (i in 0 until count) {
                val idx = i * 5
                val gx = buildingArray[idx].toInt()
                val gy = buildingArray[idx + 1].toInt()
                val bw = buildingArray[idx + 2].toInt()
                val bh = buildingArray[idx + 3].toInt()
                val nameIdx = buildingArray[idx + 4].toInt()

                val (fpW, fpH) = SpriteAtlasDef.FOOTPRINT_BY_NAME_INDEX
                    .getOrElse(nameIdx) { 2 to 2 }
                val offsetX = (fpW - bw) * tileSize * 0.5f
                val offsetY = (fpH - bh) * tileSize.toFloat()
                val bWorldX = gx * tileSize + offsetX
                val bWorldY = gy * tileSize + offsetY
                val bWorldW = bw * tileSize
                val bWorldH = bh * tileSize
                val bCamOffX = bWorldX - camX
                val bCamOffY = bWorldY - camY
                val bDstLeft = (bCamOffX * scale).roundToInt()
                val bDstTop = (bCamOffY * scale).roundToInt()
                val bDstRight = ((bCamOffX + bWorldW) * scale).roundToInt()
                val bDstBottom = ((bCamOffY + bWorldH) * scale).roundToInt()

                val ftCamOffX = gx * tileSize - camX
                val ftCamOffY = gy * tileSize - camY
                val ftDstLeft = (ftCamOffX * scale).roundToInt()
                val ftDstTop = (ftCamOffY * scale).roundToInt()
                val ftDstRight = ((ftCamOffX + fpW * tileSize) * scale).roundToInt()
                val ftDstBottom = ((ftCamOffY + fpH * tileSize) * scale).roundToInt()

                if (bDstLeft >= vpW || bDstTop >= vpH || bDstRight <= 0 || bDstBottom <= 0) continue

                if (nameIdx == SPIRIT_MINE_ATLAS_INDEX) {
                    val ftIdx = SPIRIT_MINE_GROUND_FT_INDEX
                    val ftSrc = floorTileSrcRects.getOrNull(ftIdx)
                    if (ftSrc != null) {
                        reuseRect.set(ftDstLeft, ftDstTop, ftDstRight, ftDstBottom)
                        canvas.drawBitmap(atlas, ftSrc, reuseRect, paint)
                    }
                } else if (nameIdx != SPIRIT_FIELD_ATLAS_INDEX) {
                    val ftIdx = SpriteAtlasDef.floorTileIndex(fpW, fpH)
                    if (ftIdx >= 0) {
                        val ftSrc = floorTileSrcRects.getOrNull(ftIdx)
                        if (ftSrc != null) {
                            reuseRect.set(ftDstLeft, ftDstTop, ftDstRight, ftDstBottom)
                            canvas.drawBitmap(atlas, ftSrc, reuseRect, paint)
                        }
                    }
                }

                val srcRect = buildingSrcRects.getOrNull(nameIdx) ?: continue
                reuseRect.set(bDstLeft, bDstTop, bDstRight, bDstBottom)
                canvas.drawBitmap(atlas, srcRect, reuseRect, paint)
            }
        }
    }

    // ── 渲染质量控制（由 ThermalController 驱动） ──

    @Volatile
    var qualityFactor: Float = 1.0f

    @Volatile
    var decorationsDisabled: Boolean = false

    // ── 帧缓冲区 ──

    @Volatile
    private var frameBuffer: Bitmap? = null
    @Volatile
    private var frameCanvas: Canvas? = null
    @Volatile
    private var resizeRequested: Boolean = false
    @Volatile
    private var resizeRequestedW: Int = 0
    @Volatile
    private var resizeRequestedH: Int = 0

    // ── Chunk 缓存追踪 ──

    /** 上一次渲染的 tile hash（用于 chunk 失效检测） */
    private var chunkTileHash: Int = 0
    /** 上一次渲染的 building hash */
    private var chunkBuildingHash: Int = 0
    /** 上一次的 preview 状态 */
    private var lastShowPreview: Boolean = false

    // ── Chunk 缓存 ──

    private class ChunkTile(
        val col: Int,
        val row: Int
    ) {
        /** 1024×1024, RGB_565, 惰性创建。仅宗门地图可见时占用内存，切 Tab 时释放 */
        var bitmap: Bitmap? = null
        var isValid: Boolean = false

        fun rebuild(
            atlas: Bitmap,
            tileData: IntArray,
            rows: Int,
            cols: Int,
            buildingArray: FloatArray?,
            buildingCount: Int,
            tileSize: Int,
            decorationsDisabled: Boolean,
            tileSrcRects: Array<Rect>,
            buildingSrcRects: Array<Rect>,
            floorTileSrcRects: Array<Rect>
        ) {
            // ★ 对抗性审查修复：使用局部 Paint 而非共享 paint 实例
            // 未来若引入异步 chunk 重建，多线程不会竞争同一 Paint 对象
            val localPaint = Paint(Paint.FILTER_BITMAP_FLAG).apply {
                isFilterBitmap = false
                isAntiAlias = false
                isDither = false
            }
            val bmp = bitmap ?: Bitmap.createBitmap(CHUNK_PIXEL, CHUNK_PIXEL, Bitmap.Config.RGB_565).also { bitmap = it }
            val canvas = Canvas(bmp)
            canvas.drawColor(Color.rgb(0xF2, 0xED, 0xE4))

            val startCol = col * CHUNK_SIZE_TILES
            val startRow = row * CHUNK_SIZE_TILES
            val endCol = (startCol + CHUNK_SIZE_TILES).coerceAtMost(cols)
            val endRow = (startRow + CHUNK_SIZE_TILES).coerceAtMost(rows)

            val reuseRect = Rect()

            // 绘制地面 + 装饰
            for (r in startRow until endRow) {
                val rowBase = r * cols
                for (c in startCol until endCol) {
                    val tile = tileData[rowBase + c]
                    val wx = c * tileSize
                    val wy = r * tileSize
                    val chunkOffX = wx - startCol * tileSize
                    val chunkOffY = wy - startRow * tileSize

                    // A1: 地面底图
                    val gIdx = if (tile == GROUND_V2_SRC_INDEX) GROUND_V2_SRC_INDEX else 0
                    val groundSrc = tileSrcRects.getOrNull(gIdx) ?: continue
                    reuseRect.set(chunkOffX, chunkOffY, chunkOffX + tileSize, chunkOffY + tileSize)
                    canvas.drawBitmap(atlas, groundSrc, reuseRect, localPaint)

                    // A2: 装饰叠加
                    if (!decorationsDisabled && tile in 1..5) {
                        val decorSrc = tileSrcRects.getOrNull(tile) ?: continue
                        if (tile >= 4) {
                            val treeLeft = chunkOffX - tileSize
                            val treeTop = chunkOffY - tileSize
                            reuseRect.set(treeLeft, treeTop, treeLeft + 2 * tileSize, treeTop + 2 * tileSize)
                        } else {
                            reuseRect.set(chunkOffX, chunkOffY, chunkOffX + tileSize, chunkOffY + tileSize)
                        }
                        canvas.drawBitmap(atlas, decorSrc, reuseRect, localPaint)
                    }
                }
            }

            // 绘制建筑（使用相对相机 (camX=chunk左上角, scale=1) 达到精确对齐）
            if (buildingArray != null && buildingCount > 0) {
                drawBuildingsToCanvas(
                    canvas = canvas,
                    atlas = atlas,
                    buildingArray = buildingArray,
                    buildingCount = buildingCount,
                    tileSize = tileSize,
                    buildingSrcRects = buildingSrcRects,
                    floorTileSrcRects = floorTileSrcRects,
                    paint = localPaint,
                    reuseRect = reuseRect,
                    camX = (startCol * tileSize).toFloat(),
                    camY = (startRow * tileSize).toFloat(),
                    scale = 1f,
                    vpW = CHUNK_PIXEL,
                    vpH = CHUNK_PIXEL
                )
            }

            isValid = true
        }
    }

    private val chunkCaches = Array(NUM_CHUNKS_COL) { col ->
        Array(NUM_CHUNKS_ROW) { row ->
            ChunkTile(col, row)
        }
    }

    /** decorationsDisabled 版本号，变化时失效所有 chunk */
    private var chunkDecorVersion: Int = 0
    /** 上一次记录的 decorationsDisabled 值 */
    private var lastDecorationsDisabled: Boolean = false
    /** 上一次的 qualityFactor 值，变化时重建帧缓冲区 */
    private var lastQualityFactor: Float = 1.0f

    // ── EWMA 帧时间追踪 ──

    private var ewmaFrameTimeNs: Long = 0L
    private var lastFpsSwitchMs: Long = 0L
    private var currentCalculatedFps: Int = 60

    // ── 精灵绘制 Paint ──

    private val paint = Paint(Paint.FILTER_BITMAP_FLAG).apply {
        isFilterBitmap = false
        isAntiAlias = false
        isDither = false
    }

    private val previewPaint = Paint(Paint.FILTER_BITMAP_FLAG).apply {
        isFilterBitmap = false
        isAntiAlias = false
    }

    // ── 精灵图源矩形（延迟初始化） ──

    private val tileSrcRects: Array<Rect> by lazy {
        val rects = arrayOfNulls<Rect>(SpriteAtlasDef.TileType.values().size)
        for (tile in SpriteAtlasDef.TileType.values()) {
            val sr = tile.rect
            rects[tile.index] = Rect(sr.x, sr.y, sr.x + sr.w, sr.y + sr.h)
        }
        @Suppress("UNCHECKED_CAST")
        rects as Array<Rect>
    }

    private val buildingSrcRects: Array<Rect> by lazy {
        val rects = arrayOfNulls<Rect>(SpriteAtlasDef.BUILDING_NAMES.size)
        for (i in rects.indices) {
            val sr = SpriteAtlasDef.buildingRect(i)
            rects[i] = Rect(sr.x, sr.y, sr.x + sr.w, sr.y + sr.h)
        }
        @Suppress("UNCHECKED_CAST")
        rects as Array<Rect>
    }

    private val floorTileSrcRects: Array<Rect> by lazy {
        val rects = arrayOfNulls<Rect>(SpriteAtlasDef.FloorTileType.values().size)
        for (ft in SpriteAtlasDef.FloorTileType.values()) {
            val r = ft.pixelRect
            rects[ft.ordinal] = Rect(r.x, r.y, r.x + r.w, r.y + r.h)
        }
        @Suppress("UNCHECKED_CAST")
        rects as Array<Rect>
    }

    // ============================================================
    // 公共 API
    // ============================================================

    /** 记录帧渲染时间，返回建议目标帧率（EWMA 平滑 + 1 秒防抖） */
    fun recordFrameTime(actualNs: Long, nowMs: Long): Int {
        if (ewmaFrameTimeNs == 0L) {
            // ★ 对抗性审查修复：首帧使用实际耗时但上限 22ms（45fps 档），
            // 防止 JIT 预热/Bitmap 分配等首帧异常耗时导致 ~10 帧低帧率恢复期
            ewmaFrameTimeNs = actualNs.coerceAtMost(22_000_000L)
            return 60
        }
        ewmaFrameTimeNs = (EWMA_ALPHA * actualNs + (1 - EWMA_ALPHA) * ewmaFrameTimeNs).toLong()

        if (nowMs - lastFpsSwitchMs < FPS_HYSTERESIS_MS) {
            return currentCalculatedFps
        }

        val fps = when {
            ewmaFrameTimeNs <= 22_000_000L -> 60
            ewmaFrameTimeNs <= 33_000_000L -> 45
            ewmaFrameTimeNs <= 50_000_000L -> 30
            else -> 20
        }

        if (fps != currentCalculatedFps) {
            currentCalculatedFps = fps
            lastFpsSwitchMs = nowMs
        }
        return fps
    }

    /**
     * 渲染一帧到帧缓冲区。
     *
     * 渲染策略优先级：
     * 1. Scroll-Frame Compositing（数据未变 + 小偏移）→ 平移 + 边缘填充
     * 2. Chunk 缓存完整渲染（chunk 有效时）→ ~4 次 drawBitmap
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
        val buildingArray = frame.buildingData
        val scale = if (frame.camX.isNaN() || frame.camX.isInfinite() ||
            frame.camY.isNaN() || frame.camY.isInfinite() ||
            frame.scale.isNaN() || frame.scale.isInfinite()
        ) {
            android.util.Log.w(TAG, "renderFrame: NaN/Inf in camera/scale")
            return fb
        } else frame.scale.coerceIn(MIN_SCALE, MAX_SCALE)

        val tileHash = td.contentHashCode()
        val buildingHash = buildingArray?.contentHashCode() ?: 0
        val previewActive = frame.showPreview

        // ═══════════════════════════════════════════════════════
        // Chunk 缓存完整渲染（Scroll Compositing 已废弃）
        // ═══════════════════════════════════════════════════════
        lastShowPreview = previewActive

        // Chunk 失效检查
        val chunkTileChanged = tileHash != chunkTileHash
        val chunkBuildingChanged = buildingHash != chunkBuildingHash
        val chunkDecorChanged = decorationsDisabled != lastDecorationsDisabled

        if (chunkDecorChanged) {
            lastDecorationsDisabled = decorationsDisabled
        }

        if (chunkTileChanged) {
            for (col in 0 until NUM_CHUNKS_COL) {
                for (row in 0 until NUM_CHUNKS_ROW) {
                    chunkCaches[col][row].isValid = false
                }
            }
            chunkTileHash = tileHash
        }

        if (chunkDecorChanged) {
            // decorationsDisabled 变化 → 所有 chunk 失效（装饰绘制在 tile 层）
            for (col in 0 until NUM_CHUNKS_COL) {
                for (row in 0 until NUM_CHUNKS_ROW) {
                    chunkCaches[col][row].isValid = false
                }
            }
        }

        if (chunkBuildingChanged) {
            chunkBuildingHash = buildingHash
            if (buildingArray != null) {
                val chunksToInvalidate = mutableSetOf<Pair<Int, Int>>()
                val count = frame.buildingCount.coerceAtMost(buildingArray.size / 5)
                for (i in 0 until count) {
                    val idx = i * 5
                    val gx = buildingArray[idx].toInt()
                    val gy = buildingArray[idx + 1].toInt()
                    val nameIdx = buildingArray[idx + 4].toInt()
                    // 使用占地尺寸（footprint）计算建筑覆盖的 chunk 范围
                    val (fpW, fpH) = SpriteAtlasDef.FOOTPRINT_BY_NAME_INDEX
                        .getOrElse(nameIdx) { 2 to 2 }
                    val firstCol = (gx / CHUNK_SIZE_TILES).coerceIn(0, NUM_CHUNKS_COL - 1)
                    // 建筑可能跨 chunk 边界，取 footprint 的末端
                    val lastChunkCol = ((gx + fpW - 1) / CHUNK_SIZE_TILES)
                        .coerceIn(0, NUM_CHUNKS_COL - 1)
                    val firstRow = (gy / CHUNK_SIZE_TILES).coerceIn(0, NUM_CHUNKS_ROW - 1)
                    val lastChunkRow = ((gy + fpH - 1) / CHUNK_SIZE_TILES)
                        .coerceIn(0, NUM_CHUNKS_ROW - 1)
                    for (c in firstCol..lastChunkCol) {
                        for (r in firstRow..lastChunkRow) {
                            chunksToInvalidate.add(c to r)
                        }
                    }
                }
                for ((col, row) in chunksToInvalidate) {
                    chunkCaches[col][row].isValid = false
                }
            } else {
                for (col in 0 until NUM_CHUNKS_COL) {
                    for (row in 0 until NUM_CHUNKS_ROW) {
                        chunkCaches[col][row].isValid = false
                    }
                }
            }
        }

        // 重建失效 chunk
        if (chunkTileChanged || chunkBuildingChanged || chunkDecorChanged) {
            for (col in 0 until NUM_CHUNKS_COL) {
                for (row in 0 until NUM_CHUNKS_ROW) {
                    if (!chunkCaches[col][row].isValid) {
                        chunkCaches[col][row].rebuild(
                            atlas = atlas,
                            tileData = td,
                            rows = rows,
                            cols = cols,
                            buildingArray = buildingArray,
                            buildingCount = frame.buildingCount,
                            tileSize = tileSize,
                            decorationsDisabled = decorationsDisabled,
                            tileSrcRects = tileSrcRects,
                            buildingSrcRects = buildingSrcRects,
                            floorTileSrcRects = floorTileSrcRects
                        )
                    }
                }
            }
        }

        // 合成可见 chunk 到帧缓冲区
        val viewLeft = frame.camX
        val viewTop = frame.camY
        val viewRight = frame.camX + vpW / scale
        val viewBottom = frame.camY + vpH / scale

        val firstChunkCol = ((viewLeft / tileSize) / CHUNK_SIZE_TILES).toInt()
            .coerceIn(0, NUM_CHUNKS_COL - 1)
        val firstChunkRow = ((viewTop / tileSize) / CHUNK_SIZE_TILES).toInt()
            .coerceIn(0, NUM_CHUNKS_ROW - 1)
        val lastChunkCol = ((viewRight / tileSize) / CHUNK_SIZE_TILES).toInt()
            .coerceIn(0, NUM_CHUNKS_COL - 1)
        val lastChunkRow = ((viewBottom / tileSize) / CHUNK_SIZE_TILES).toInt()
            .coerceIn(0, NUM_CHUNKS_ROW - 1)

        canvas.drawColor(Color.rgb(0xF2, 0xED, 0xE4))
        val reuseRect = Rect()
        // ★ 修复：以首个可见 Chunk 为基准计算屏幕位置，后续 Chunk 递推。
        // 独立计算每个 Chunk 时 roundToInt 可能产生 ±1px 偏差，
        // 导致相邻 Chunk 之间出现 1px 背景色裂缝（白线闪烁）。
        val firstChunkWorldX = (firstChunkCol * CHUNK_SIZE_TILES * tileSize).toFloat()
        val firstChunkWorldY = (firstChunkRow * CHUNK_SIZE_TILES * tileSize).toFloat()
        val baseScreenX = ((firstChunkWorldX - frame.camX) * scale).roundToInt()
        val baseScreenY = ((firstChunkWorldY - frame.camY) * scale).roundToInt()
        val scaledW = (CHUNK_PIXEL * scale).roundToInt().coerceAtLeast(1)
        val scaledH = (CHUNK_PIXEL * scale).roundToInt().coerceAtLeast(1)
        for (chunkCol in firstChunkCol..lastChunkCol) {
            for (chunkRow in firstChunkRow..lastChunkRow) {
                val chunk = chunkCaches[chunkCol][chunkRow]
                val screenX = baseScreenX + (chunkCol - firstChunkCol) * scaledW
                val screenY = baseScreenY + (chunkRow - firstChunkRow) * scaledH
                if (screenX + scaledW < 0 || screenY + scaledH < 0 ||
                    screenX > vpW || screenY > vpH) continue
                reuseRect.set(screenX, screenY, screenX + scaledW, screenY + scaledH)
                val chunkBmp = chunk.bitmap ?: continue
                canvas.drawBitmap(chunkBmp, null, reuseRect, paint)
            }
        }

        // 预览精灵（建筑放置/移动模式）
        if (previewActive) {
            drawPreview(canvas, atlas, frame, tileSize, scale, frame.camX, frame.camY)
        }

        return fb
    }

    // ============================================================
    // 帧缓冲区管理
    // ============================================================

    private fun ensureFrameBuffer(vpWIn: Int, vpHIn: Int) {
        var vpW = vpWIn
        var vpH = vpHIn
        if (resizeRequested) {
            resizeRequested = false
            vpW = resizeRequestedW
            vpH = resizeRequestedH
        }
        if (vpW <= 0 || vpH <= 0) return
        val qualityChanged = qualityFactor != lastQualityFactor
        val fb = frameBuffer
        if (fb == null || fb.width != vpW || fb.height != vpH || qualityChanged) {
            val bmpConfig = if (qualityFactor < 0.6f) Bitmap.Config.RGB_565 else Bitmap.Config.ARGB_8888
            frameBuffer?.recycle()
            frameBuffer = Bitmap.createBitmap(vpW.coerceAtLeast(1), vpH.coerceAtLeast(1), bmpConfig)
            frameCanvas = Canvas(frameBuffer ?: return)
            // resize 时清除缓存
            chunkCaches.forEach { col -> col.forEach { it.isValid = false } }
            chunkTileHash = 0
            chunkBuildingHash = 0
            chunkDecorVersion = 0
            lastDecorationsDisabled = this.decorationsDisabled
            lastQualityFactor = qualityFactor
        }
    }

    // ============================================================
    // 预览精灵绘制
    // ============================================================

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
        if (dstLeft >= canvas.width || dstTop >= canvas.height ||
            dstRight <= 0 || dstBottom <= 0) return

        val atlasW = atlas.width
        val atlasH = atlas.height
        val srcLeft = (frame.previewU0 * atlasW).roundToInt()
        val srcTop = (frame.previewV0 * atlasH).roundToInt()
        val srcRight = (frame.previewU1 * atlasW).roundToInt()
        val srcBottom = (frame.previewV1 * atlasH).roundToInt()

        val alpha = frame.previewAlpha.coerceIn(0f, 1f)
        previewPaint.alpha = (alpha * 255).toInt()

        canvas.drawBitmap(atlas,
            Rect(srcLeft, srcTop, srcRight, srcBottom),
            Rect(dstLeft, dstTop, dstRight, dstBottom),
            previewPaint)

        previewPaint.alpha = 255
    }

    // ============================================================
    // resize / release
    // ============================================================

    fun resize(width: Int, height: Int) {
        if (width > 0 && height > 0) {
            resizeRequestedW = width
            resizeRequestedH = height
            resizeRequested = true
        }
    }

    fun release() {
        chunkCaches.forEach { col -> col.forEach { it.bitmap?.recycle() } }
        frameBuffer?.recycle()
        frameBuffer = null
        frameCanvas = null
    }

}
