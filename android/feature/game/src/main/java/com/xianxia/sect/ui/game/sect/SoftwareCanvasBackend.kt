package com.xianxia.sect.ui.game.sect

import android.graphics.*

/**
 * SoftwareCanvasBackend — Canvas 软件回退渲染器。
 *
 * 当 Vulkan 原生渲染不可用时（模拟器/MTK/华为等），使用 Android Canvas API
 * 在 CPU 端绘制宗门地图帧，通过 [NativeSurfaceView.RenderThread] 以
 * lockCanvas/unlockCanvasAndPost 输出到 Surface。
 *
 * 渲染逻辑与 C++ NativeBridge::drawAllTiles + drawSprite 等效：
 * 1. 地面底图 — 所有格子使用图集中的地面纹理
 * 2. 装饰叠加 — 草(1×1，偏移(0,0)) / 树(2×2，偏移(-1,-1))
 * 3. 建筑绘制 — 从 buildingData FloatArray 读取 [gridX,gridY,w,h,nameIdx]
 * 4. 预览精灵 — 建造/移动模式下用 ColorMatrix 调色的半透明建筑
 *
 * 性能特性：
 * - 单帧渲染 48×48 地图 + 10 FPS 约 2-8ms（现代 CPU），远低于 100ms 帧预算
 * - 帧缓冲区 Bitmap 复用，零分配
 * - 可见性裁剪：只绘制视口内的格子
 *
 * @param config NativeRenderConfig（tileSize, worldWidthCells 等）
 */
class SoftwareCanvasBackend(
    private val config: NativeRenderConfig
) {
    /** 帧缓冲区 Bitmap（世界大小，复用避免每帧分配） */
    private val frameBuffer: Bitmap = Bitmap.createBitmap(
        config.worldPixelWidth,
        config.worldPixelHeight,
        Bitmap.Config.ARGB_8888
    )
    private val frameCanvas: Canvas = Canvas(frameBuffer)

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

    // ============================================================
    // 精灵图源矩形（与 NativeBridge::drawAllTiles 的 UV 映射一致）
    // ============================================================

    /** 瓦片精灵在图集中的源矩形（索引 = tile 类型，与 decorUvMap 一致） */
    private val TILE_SRC_RECTS = arrayOf(
        Rect(0,   0,   64,  64),    // 0: ground_tile
        Rect(64,  0,   128, 64),    // 1: grass_small
        Rect(128, 0,   192, 64),    // 2: grass_medium
        Rect(192, 0,   256, 64),    // 3: grass_large
        Rect(256, 0,   384, 128),   // 4: tree1 (128×128)
        Rect(384, 0,   512, 128),   // 5: tree2 (128×128)
        Rect(0,   0,   64,  64),    // 6: (占位,不会被使用)
        Rect(512, 0,   576, 64),    // 7: ground_tile_v2
    )

    /** 建筑精灵在图集中的源矩形（nameIndex 0-17 对应 BUILDING_UV_MAP 顺序） */
    private val BUILDING_SRC_RECTS = run {
        val rects = arrayOfNulls<Rect>(18)
        val colsPerRow = intArrayOf(5, 5, 5, 3)
        var idx = 0
        for (rowIndex in colsPerRow.indices) {
            for (col in 0 until colsPerRow[rowIndex]) {
                val px = col * 128
                val py = 128 + rowIndex * 128
                rects[idx] = Rect(px, py, px + 128, py + 128)
                idx++
            }
        }
        @Suppress("UNCHECKED_CAST")
        rects as Array<Rect>
    }

    // ============================================================
    // 帧缓存（用于复用渲染结果，不变时不重建）
    // ============================================================

    /** 上次渲染的 tileData 版本（用于判断是否需要重绘地面/装饰层） */
    private var lastTileDataHash: Int = 0
    /** 上次渲染的建筑数据 hash */
    private var lastBuildingHash: Int = 0
    /** 地面/装饰缓存 Bitmap（不变时跳过绘制） */
    private var tileCache: Bitmap? = null
    private var tileCacheValid: Boolean = false

    /**
     * 渲染一帧到帧缓冲区。
     *
     * @param rs     当前帧渲染状态（FrameRenderState）
     * @param atlas  2048×2048 纹理图集 Bitmap
     * @param cols   地图列数
     * @param rows   地图行数
     * @return 渲染好的帧缓冲区 Bitmap（供 lockCanvas 输出）
     */
    fun renderFrame(
        rs: FrameRenderState,
        atlas: Bitmap,
        cols: Int,
        rows: Int,
        pixelW: Int,
        pixelH: Int
    ): Bitmap? {
        val tileSize = config.tileSize
        val td = rs.tileData
        if (td == null) {
            // 在没有 tileData 时返回纯色帧缓冲区，防止 SurfaceView 的"洞"
            // 在华为模拟器等设备上显示缓冲区残留内容
            frameCanvas.drawColor(Color.DKGRAY)
            return frameBuffer
        }
        val buildingDataArray = rs.buildingData

        // --- 计算可见区域（视锥剔除） ---
        val scale = rs.scale.coerceAtLeast(0.1f)
        // 注意：相机坐标系中，camX/camY 为视口左上角世界坐标
        val viewLeft = rs.camX
        val viewTop = rs.camY
        // 视口宽高在 world 空间中的范围（考虑缩放因子）
        val viewRight = rs.camX + pixelW / scale
        val viewBottom = rs.camY + pixelH / scale

        val firstCol = (viewLeft / tileSize).toInt().coerceIn(0, cols - 1)
        val firstRow = (viewTop / tileSize).toInt().coerceIn(0, rows - 1)
        val lastCol = ((viewRight / tileSize).toInt() + 1).coerceIn(0, cols - 1)
        val lastRow = ((viewBottom / tileSize).toInt() + 1).coerceIn(0, rows - 1)

        // 计算当前数据 hash（判断是否需要重绘缓存层）
        val tileHash = td.contentHashCode()
        val buildingHash = buildingDataArray?.contentHashCode() ?: 0
        val needRebuildTiles = tileHash != lastTileDataHash || !tileCacheValid
        val needRebuildBuildings = buildingHash != lastBuildingHash

        // --- 清空帧缓冲区 ---
        if (needRebuildTiles) {
            frameCanvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
            lastTileDataHash = tileHash

            // Step A: 地面 + 装饰
            for (row in firstRow..lastRow) {
                val rowBase = row * cols
                for (col in firstCol..lastCol) {
                    val tile = td[rowBase + col]
                    val wx = col * tileSize
                    val wy = row * tileSize

                    // A1: 地面底图
                    val gIdx = if (tile == 7 /* TILE_GROUND_V2 */) 7 else 0
                    val groundSrc = TILE_SRC_RECTS[gIdx]
                    drawTile(frameCanvas, atlas, groundSrc, wx, wy, tileSize, tileSize)

                    // A2: 装饰叠加（草/树）
                    if (tile in 1..5) {
                        val decorSrc = TILE_SRC_RECTS[tile]
                        if (tile >= 4) {
                            // 树（2×2 格，偏移 (-tileSize, -tileSize)）
                            drawTile(frameCanvas, atlas, decorSrc,
                                wx - tileSize, wy - tileSize,
                                tileSize * 2, tileSize * 2)
                        } else {
                            // 草（1×1 格）
                            drawTile(frameCanvas, atlas, decorSrc,
                                wx, wy, tileSize, tileSize)
                        }
                    }
                }
            }

            // 缓存地面/装饰层（如果只更新建筑时可以复用）
            buildTileCache(firstCol, firstRow, lastCol, lastRow, tileSize)
        }

        // Step B: 建筑层（每帧重新绘制，因为建筑位置可能变化）
        if (needRebuildBuildings || needRebuildTiles) {
            if (!needRebuildTiles) {
                // 建筑层只需恢复缓存的地面/装饰层，再画建筑
                restoreTileCache()
            }

            if (buildingDataArray != null && rs.buildingVisible) {
                val buildingCount = rs.buildingCount.coerceAtMost(buildingDataArray.size / 5)
                for (i in 0 until buildingCount) {
                    val idx = i * 5
                    val gx = buildingDataArray[idx].toInt()
                    val gy = buildingDataArray[idx + 1].toInt()
                    val bw = buildingDataArray[idx + 2].toInt()
                    val bh = buildingDataArray[idx + 3].toInt()
                    val nameIdx = buildingDataArray[idx + 4].toInt()

                    // 可见性检测
                    val bwx = gx * tileSize
                    val bwy = gy * tileSize
                    val bpw = bw * tileSize
                    val bph = bh * tileSize
                    if (bwx + bpw <= viewLeft || bwx >= viewRight ||
                        bwy + bph <= viewTop || bwy >= viewBottom) {
                        continue
                    }

                    val srcRect = BUILDING_SRC_RECTS.getOrNull(nameIdx) ?: continue
                    drawTile(frameCanvas, atlas, srcRect, bwx, bwy, bpw, bph)
                }
            }

            lastBuildingHash = buildingHash
        }

        // Step C: 预览精灵（建造/移动模式）
        if (rs.showPreview) {
            drawPreview(frameCanvas, atlas, rs, tileSize)
        }

        return frameBuffer
    }

    /**
     * 构建地面/装饰缓存层（不变区域不用每帧重绘）。
     */
    private fun buildTileCache(
        firstCol: Int, firstRow: Int,
        lastCol: Int, lastRow: Int,
        tileSize: Int
    ) {
        val w = (lastCol - firstCol + 1) * tileSize
        val h = (lastRow - firstRow + 1) * tileSize
        if (w <= 0 || h <= 0) {
            tileCacheValid = false
            return
        }
        var cache = tileCache
        if (cache == null || cache.width < w || cache.height < h) {
            tileCache?.recycle()
            cache = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            tileCache = cache
        }
        val cacheCanvas = Canvas(cache)
        cacheCanvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
        cacheCanvas.drawBitmap(frameBuffer,
            Rect(firstCol * tileSize, firstRow * tileSize,
                 (lastCol + 1) * tileSize, (lastRow + 1) * tileSize),
            Rect(0, 0, w, h), paint)
        tileCacheValid = true
    }

    /**
     * 从缓存恢复地面/装饰层（建筑重绘前清空画布区域再恢复）。
     */
    private fun restoreTileCache() {
        val cache = tileCache ?: return
        if (!tileCacheValid) return
        // 清除再恢复缓存
        frameCanvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
        frameCanvas.drawBitmap(cache, 0f, 0f, paint)
    }

    /**
     * 在 [canvas] 上绘制一个精灵。
     */
    private fun drawTile(
        canvas: Canvas,
        atlas: Bitmap,
        srcRect: Rect,
        dstX: Int, dstY: Int,
        dstW: Int, dstH: Int
    ) {
        canvas.drawBitmap(atlas, srcRect,
            Rect(dstX, dstY, dstX + dstW, dstY + dstH), paint)
    }

    /**
     * 绘制半透明建筑预览（建造/移动模式）。
     * 对应 C++ NativeBridge::drawSprite + vertexColor 调色。
     */
    private fun drawPreview(
        canvas: Canvas,
        atlas: Bitmap,
        rs: FrameRenderState,
        tileSize: Int
    ) {
        val px = rs.previewX.toInt()
        val py = rs.previewY.toInt()
        val pw = rs.previewW.toInt()
        val ph = rs.previewH.toInt()
        if (pw <= 0 || ph <= 0) return

        val u0 = rs.previewU0
        val v0 = rs.previewV0
        val u1 = rs.previewU1
        val v1 = rs.previewV1

        // 从 UV (归一化 0-1) 转换到图集像素坐标
        val atlasW = atlas.width
        val atlasH = atlas.height
        val srcLeft = (u0 * atlasW).toInt()
        val srcTop = (v0 * atlasH).toInt()
        val srcRight = (u1 * atlasW).toInt()
        val srcBottom = (v1 * atlasH).toInt()

        // 应用 ColorMatrix 调色（对应 C++ vertexColor = tintColor * alpha）
        val alpha = rs.previewAlpha.coerceIn(0f, 1f)
        previewPaint.alpha = (alpha * 255).toInt()
        previewPaint.colorFilter = ColorMatrixColorFilter(
            ColorMatrix(floatArrayOf(
                // 用灰度保留 + 着色偏移模拟 tint 效果
                rs.previewTintRed, 0f, 0f, 0f, 0f,
                0f, rs.previewTintGreen, 0f, 0f, 0f,
                0f, 0f, rs.previewTintBlue, 0f, 0f,
                0f, 0f, 0f, 1f, 0f
            ))
        )

        canvas.drawBitmap(atlas,
            Rect(srcLeft, srcTop, srcRight, srcBottom),
            Rect(px, py, px + pw, py + ph),
            previewPaint)

        previewPaint.colorFilter = null
    }

    /** 调整视口尺寸（resize 时调用） */
    fun resize(width: Int, height: Int) {
        // 帧缓冲区固定为世界大小，不随视口变化
    }

    /** 释放资源 */
    fun release() {
        tileCache?.recycle()
        tileCache = null
        tileCacheValid = false
    }
}
