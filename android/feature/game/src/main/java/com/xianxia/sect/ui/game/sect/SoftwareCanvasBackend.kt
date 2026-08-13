package com.xianxia.sect.ui.game.sect

import android.graphics.*
import com.xianxia.sect.core.render.BuildingRenderGeometry
import com.xianxia.sect.core.render.DemolishHighlightMark
import com.xianxia.sect.core.render.NativeRenderConfig
import com.xianxia.sect.core.render.RenderFrame
import com.xianxia.sect.core.render.RenderLodPolicy
import com.xianxia.sect.core.render.SpiritCropRender
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

        // ── 缩放保护常量 ──
        private const val MIN_SCALE = 0.1f
        private const val MAX_SCALE = 3.0f

        /** 热控降质阈值：qualityFactor < 0.6 时装饰层跳过 + 帧缓冲降为 RGB_565（与 C++ skipDecor 同常量双端对齐） */

        /** 阴影填充色（ARGB 半透明黑；alpha 与 BuildingRenderGeometry.SHADOW_ALPHA 同值） */
        private val shadowPaintColor = android.graphics.Color.argb(
            (BuildingRenderGeometry.SHADOW_ALPHA * 255).toInt(), 0, 0, 0
        )

        /** 绘制阴影矩形（屏幕坐标，半透明黑；与视口相交才绘制） */
        private fun drawShadowRect(
            canvas: Canvas,
            paint: Paint,
            left: Int,
            top: Int,
            right: Int,
            bottom: Int
        ) {
            val inViewX = left < canvas.width && right > 0
            val inViewY = top < canvas.height && bottom > 0
            if (!inViewX || !inViewY) return
            paint.color = shadowPaintColor
            canvas.drawRect(left.toFloat(), top.toFloat(), right.toFloat(), bottom.toFloat(), paint)
        }

        /** 建筑数据单条步长（[gx, gy, sw, sh, nameIdx]） */
        private const val SELECTED_DATA_STRIDE = 5

        /** 灵田作物数据单条步长（[gx, gy, progress01]） */
        private const val CROP_DATA_STRIDE = 3

        /** 高亮线宽（格数）：max(2px, tileSize×0.06) 的格数分量 */
        private const val HIGHLIGHT_LINE_WIDTH_TILES = 0.06f

        /** 金色 #FFD700 */
        private val HIGHLIGHT_FILL_COLOR = android.graphics.Color.argb(
            (0.15f * 255).toInt(), 0xFF, 0xD7, 0x00
        )
        private val HIGHLIGHT_EDGE_COLOR = android.graphics.Color.argb(
            (0.9f * 255).toInt(), 0xFF, 0xD7, 0x00
        )

        // ── 拆除模式占地高亮（与旧 Compose 覆盖层同色：0x66 = 40% 半透明） ──
        /** 未选中占地填充：半透明绿 #4CAF50 */
        private val DEMOLISH_GREEN_FILL_COLOR = android.graphics.Color.argb(0x66, 0x4C, 0xAF, 0x50)
        /** 选中占地填充：半透明红 #F44336 */
        private val DEMOLISH_RED_FILL_COLOR = android.graphics.Color.argb(0x66, 0xF4, 0x43, 0x36)
        /** 选中建筑描边：不透明红 #F44336 */
        private val DEMOLISH_RED_EDGE_COLOR = android.graphics.Color.argb(0xFF, 0xF4, 0x43, 0x36)

        /** 放置/移动模式网格线色（与旧 Compose GridOverlay 同色 #E4DDD0） */
        private val GRID_OVERLAY_COLOR = android.graphics.Color.argb(0xFF, 0xE4, 0xDD, 0xD0)

        // ── 图集索引常量 ──
        private const val SPIRIT_FIELD_ATLAS_INDEX = 2
        private const val SPIRIT_MINE_ATLAS_INDEX = 0
        private const val SPIRIT_MINE_GROUND_FT_INDEX = 4
        private const val GROUND_V2_SRC_INDEX = 7

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
    /** 上一次渲染的 tileData 引用（用于跳过 contentHashCode O(n) 遍历） */
    private var cachedTileData: IntArray? = null
    /** 上一次渲染的 building hash */
    private var chunkBuildingHash: Int = 0
    /** 上一次渲染的 buildingData 引用（用于跳过 contentHashCode O(n) 遍历） */
    private var cachedBuildingData: FloatArray? = null
    /** 测试观测：chunk 重建累计次数（WP5 LOD 档内无重建防抖断言用） */
    internal var chunkRebuildCount: Int = 0
        private set

    // ── Chunk 缓存 ──

    /** chunk 重建共享工具集（图集源矩形 + 格尺寸——跨 chunk 不变，单次构建） */
    private class ChunkDrawKit(
        val tileSize: Int,
        val tileSrcRects: Array<Rect>,
        val buildingSrcRects: Array<Rect>,
        val floorTileSrcRects: Array<Rect>
    )

    /** 相机-屏幕变换（chunk 烘焙用固定 viewport = chunk 像素尺寸） */
    private class ViewTransform(
        val camX: Float,
        val camY: Float,
        val scale: Float,
        val vpW: Int,
        val vpH: Int
    )

    /** chunk 内一行地面的绘制范围（drawGroundRow 参数分组） */
    private class GroundRowRange(
        val r: Int,
        val startRow: Int,
        val startCol: Int,
        val endCol: Int
    )

    private class ChunkTile(
        val col: Int,
        val row: Int,
        private val kit: ChunkDrawKit
    ) {
        /** 1024×1024, RGB_565, 惰性创建。仅宗门地图可见时占用内存，切 Tab 时释放 */
        var bitmap: Bitmap? = null
        var isValid: Boolean = false

        /**
         * 重建用 Paint（实例字段而非共享 [SoftwareCanvasBackend.paint]——
         * ★ 对抗性审查修复：未来若引入异步 chunk 重建，多线程不会竞争同一 Paint 对象）
         */
        private val rebuildPaint = Paint(Paint.FILTER_BITMAP_FLAG).apply {
            isFilterBitmap = false
            isAntiAlias = false
            isDither = false
        }

        /**
         * 建筑阴影 Paint（WP7 独立实例——drawShadowRect 会写入半透明黑 color
         * 且不恢复；Paint.setColor 更新 alpha，共用 rebuildPaint 会把 20% alpha
         * 泄漏给后续地砖/精灵/地面绘制 → 建筑虚影 + 地砖透出。与 highlight/
         * crop/preview 独立 Paint 惯例一致）
         */
        private val shadowPaint = Paint().apply {
            color = shadowPaintColor
        }

        /**
         * 重建 chunk 位图。
         *
         * @param decorSkip 装饰层跳过判定（WP5：由 RenderLodPolicy 在 renderFrame
         * 层合并 scale/热控质量/显式关闭三条件——此处只消费最终布尔值，
         * 保证"判定一处、失效一处"，与 C++ skipDecor 双端对齐）
         */
        fun rebuild(
            atlas: Bitmap,
            tileData: IntArray,
            cols: Int,
            buildingArray: FloatArray?,
            buildingCount: Int,
            decorSkip: Boolean,
            buildingShadows: Boolean
        ) {
            val bmp = bitmap ?: Bitmap.createBitmap(CHUNK_PIXEL, CHUNK_PIXEL, Bitmap.Config.RGB_565).also { bitmap = it }
            val canvas = Canvas(bmp)
            canvas.drawColor(Color.rgb(0xF2, 0xED, 0xE4))

            drawGroundAndDecor(canvas, atlas, tileData, cols, decorSkip)

            // 绘制建筑（使用相对相机 (camX=chunk左上角, scale=1) 达到精确对齐）
            if (buildingArray != null && buildingCount > 0) {
                drawBuildingsToCanvas(
                    canvas = canvas,
                    atlas = atlas,
                    buildingArray = buildingArray,
                    buildingCount = buildingCount,
                    buildingShadows = buildingShadows,
                    view = ViewTransform(
                        camX = (col * CHUNK_SIZE_TILES * kit.tileSize).toFloat(),
                        camY = (row * CHUNK_SIZE_TILES * kit.tileSize).toFloat(),
                        scale = 1f,
                        vpW = CHUNK_PIXEL,
                        vpH = CHUNK_PIXEL
                    ),
                    reuseRect = Rect()
                )
            }

            isValid = true
        }

        /**
         * 绘制地面 + 装饰层（A1 地面底图 + A2 装饰叠加）。
         *
         * A2 的 LOD 合并判定（scale/热控/显式关闭）在 renderFrame 层经
         * [RenderLodPolicy] 收敛为 decorSkip——此处只消费最终布尔值，
         * 与 C++ drawAllTiles skipDecor 同语义双端对齐。
         */
        private fun drawGroundAndDecor(
            canvas: Canvas,
            atlas: Bitmap,
            tileData: IntArray,
            cols: Int,
            decorSkip: Boolean
        ) {
            val rows = tileData.size / cols
            val startCol = col * CHUNK_SIZE_TILES
            val startRow = row * CHUNK_SIZE_TILES
            val endCol = (startCol + CHUNK_SIZE_TILES).coerceAtMost(cols)
            val endRow = (startRow + CHUNK_SIZE_TILES).coerceAtMost(rows)
            for (r in startRow until endRow) {
                drawGroundRow(
                    canvas, atlas, tileData, cols, decorSkip,
                    GroundRowRange(r, startRow, startCol, endCol)
                )
            }
        }

        /**
         * 单行地面 + 装饰（从 drawGroundAndDecor 提取，消除嵌套深度 4 → 3）。
         * A1/A2 两段为兄弟 if，同深不叠加。
         */
        private fun drawGroundRow(
            canvas: Canvas,
            atlas: Bitmap,
            tileData: IntArray,
            cols: Int,
            decorSkip: Boolean,
            range: GroundRowRange
        ) {
            val rowBase = range.r * cols
            val reuseRect = Rect()
            val tileSize = kit.tileSize
            for (c in range.startCol until range.endCol) {
                val tile = tileData[rowBase + c]
                val chunkOffX = c * tileSize - range.startCol * tileSize
                val chunkOffY = range.r * tileSize - range.startRow * tileSize

                // A1: 地面底图
                val gIdx = if (tile == GROUND_V2_SRC_INDEX) GROUND_V2_SRC_INDEX else 0
                val groundSrc = kit.tileSrcRects.getOrNull(gIdx) ?: continue
                reuseRect.set(chunkOffX, chunkOffY, chunkOffX + tileSize, chunkOffY + tileSize)
                canvas.drawBitmap(atlas, groundSrc, reuseRect, rebuildPaint)

                // A2: 装饰叠加（树 2×2 格、草 1×1 格——if 表达式替代 if/else 嵌套）
                if (!decorSkip && tile in 1..5) {
                    val decorSrc = kit.tileSrcRects.getOrNull(tile) ?: continue
                    val decoLeft = if (tile >= 4) chunkOffX - tileSize else chunkOffX
                    val decoTop = if (tile >= 4) chunkOffY - tileSize else chunkOffY
                    val decoSpan = if (tile >= 4) 2 * tileSize else tileSize
                    reuseRect.set(decoLeft, decoTop, decoLeft + decoSpan, decoTop + decoSpan)
                    canvas.drawBitmap(atlas, decorSrc, reuseRect, rebuildPaint)
                }
            }
        }

        /** 视锥剔除（屏幕矩形与 chunk 视口相交判定——4 条件拆两半规避复杂条件） */
        private fun isOffScreen(left: Int, top: Int, right: Int, bottom: Int, view: ViewTransform): Boolean {
            val pastRightOrBottom = left >= view.vpW || bottom <= 0
            val beforeLeftOrTop = right <= 0 || top >= view.vpH
            return pastRightOrBottom || beforeLeftOrTop
        }

        /**
         * 工具方法：绘制建筑列表到 chunk 位图（地砖 → 阴影 → 精灵）。
         *
         * @param view 相对相机（camX/camY = chunk 左上角世界坐标，scale=1）——
         * 与 C++ drawAllTiles 同数学，双端像素级对齐
         */
        private fun drawBuildingsToCanvas(
            canvas: Canvas,
            atlas: Bitmap,
            buildingArray: FloatArray,
            buildingCount: Int,
            buildingShadows: Boolean,
            view: ViewTransform,
            reuseRect: Rect
        ) {
            val count = buildingCount.coerceAtMost(buildingArray.size / 5)
            val tileSize = kit.tileSize
            for (i in 0 until count) {
                val idx = i * 5
                val gx = buildingArray[idx].toInt()
                val gy = buildingArray[idx + 1].toInt()
                val bw = buildingArray[idx + 2].toInt()
                val bh = buildingArray[idx + 3].toInt()
                val nameIdx = buildingArray[idx + 4].toInt()

                val (fpW, fpH) = SpriteAtlasDef.FOOTPRINT_BY_NAME_INDEX.getOrElse(nameIdx) { 2 to 2 }
                val offsetX = (fpW - bw) * tileSize * 0.5f
                val offsetY = (fpH - bh) * tileSize.toFloat()
                val bWorldX = gx * tileSize + offsetX
                val bWorldY = gy * tileSize + offsetY
                val bWorldW = bw * tileSize
                val bWorldH = bh * tileSize
                val bCamOffX = bWorldX - view.camX
                val bCamOffY = bWorldY - view.camY
                val bDstLeft = (bCamOffX * view.scale).roundToInt()
                val bDstTop = (bCamOffY * view.scale).roundToInt()
                val bDstRight = ((bCamOffX + bWorldW) * view.scale).roundToInt()
                val bDstBottom = ((bCamOffY + bWorldH) * view.scale).roundToInt()

                val ftCamOffX = gx * tileSize - view.camX
                val ftCamOffY = gy * tileSize - view.camY
                val ftDstLeft = (ftCamOffX * view.scale).roundToInt()
                val ftDstTop = (ftCamOffY * view.scale).roundToInt()
                val ftDstRight = ((ftCamOffX + fpW * tileSize) * view.scale).roundToInt()
                val ftDstBottom = ((ftCamOffY + fpH * tileSize) * view.scale).roundToInt()

                // 视锥剔除（提取纯函数，主循环复杂度收敛）
                if (isOffScreen(bDstLeft, bDstTop, bDstRight, bDstBottom, view)) continue

                // 地砖（灵田专属地皮 / 通用占地地砖）——if/else 链收敛为表达式，消除深嵌套
                val ftIdx = if (nameIdx == SPIRIT_MINE_ATLAS_INDEX) {
                    SPIRIT_MINE_GROUND_FT_INDEX
                } else if (nameIdx != SPIRIT_FIELD_ATLAS_INDEX) {
                    SpriteAtlasDef.floorTileIndex(fpW, fpH)
                } else {
                    -1
                }
                val ftSrc = if (ftIdx >= 0) kit.floorTileSrcRects.getOrNull(ftIdx) else null
                if (ftSrc != null) {
                    reuseRect.set(ftDstLeft, ftDstTop, ftDstRight, ftDstBottom)
                    canvas.drawBitmap(atlas, ftSrc, reuseRect, rebuildPaint)
                }

                // ★ 建筑投影阴影（地砖之上、精灵之下；与 C++ drawAllTiles (A2) 段同数学）
                if (buildingShadows) {
                    val offset = tileSize * BuildingRenderGeometry.SHADOW_OFFSET_TILES
                    drawShadowRect(canvas, shadowPaint,
                        ((gx * tileSize + offset - view.camX) * view.scale).roundToInt(),
                        ((gy * tileSize + offset - view.camY) * view.scale).roundToInt(),
                        (((gx + fpW) * tileSize + offset - view.camX) * view.scale).roundToInt(),
                        (((gy + fpH) * tileSize + offset - view.camY) * view.scale).roundToInt())
                }

                val srcRect = kit.buildingSrcRects.getOrNull(nameIdx) ?: continue
                reuseRect.set(bDstLeft, bDstTop, bDstRight, bDstBottom)
                canvas.drawBitmap(atlas, srcRect, reuseRect, rebuildPaint)
            }
        }
    }

    /**
     * chunk 重建共享工具集（图集源矩形 + 格尺寸——跨 chunk 不变，单次构建；
     * 精灵源矩形随 SpriteAtlasDef 静态数据生成，无 Android 依赖）
     */
    private val chunkKit: ChunkDrawKit by lazy {
        ChunkDrawKit(config.tileSize, tileSrcRects, buildingSrcRects, floorTileSrcRects)
    }

    /**
     * chunk 缓存网格。**必须 lazy**：构造期若立即构建会触发 chunkKit → tileSrcRects
     * 的 lazy 委托链，而 tileSrcRects 声明在下方（delegate 字段按声明顺序初始化，
     * 构造到 chunkCaches 时仍为 null → NPE）。延迟到首次渲染（构造已完成）再构建。
     */
    private val chunkCaches: Array<Array<ChunkTile>> by lazy {
        Array(NUM_CHUNKS_COL) { col ->
            Array(NUM_CHUNKS_ROW) { row ->
                ChunkTile(col, row, chunkKit)
            }
        }
    }

    /** BooleanArray 替代 mutableSetOf 追踪建筑变化需要失效的 chunk（P1.2 优化） */
    private val chunkInvalidationFlags = BooleanArray(NUM_CHUNKS_COL * NUM_CHUNKS_ROW)

    /** decorationsDisabled 版本号，变化时失效所有 chunk */
    private var chunkDecorVersion: Int = 0
    /** 上一次记录的 decorationsDisabled 值 */
    private var lastDecorationsDisabled: Boolean = false
    /** 上一次的 qualityFactor 值，变化时重建帧缓冲区 */
    private var lastQualityFactor: Float = 1.0f

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

    /** 选中高亮 Paint（动态叠加层，抗锯齿线条视觉更佳；静态 chunk 不共用） */
    private val highlightPaint = Paint().apply {
        isAntiAlias = true
    }

    /** 拆除模式占地高亮 Paint（独立实例——逐帧 setColor 不得污染共享 paint） */
    private val demolishPaint = Paint().apply {
        isAntiAlias = true
    }

    /** 放置/移动模式网格线 Paint（独立实例；逐帧绘制复用同色） */
    private val gridPaint = Paint().apply {
        isAntiAlias = true
        strokeWidth = 1f
    }

    /** 灵田作物 Paint（WP6 独立实例——逐帧改 alpha 不得污染共享 paint） */
    private val cropPaint = Paint(Paint.FILTER_BITMAP_FLAG).apply {
        isFilterBitmap = false
        isAntiAlias = false
        isDither = false
    }

    /** 批次 3 插值消费链：上一帧作物原始进度（key=gx/gy 编码，见 [cropProgressKey]） */
    private val lastCropProgress = HashMap<Long, Float>()

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

    /** 灵田作物三阶段图源矩形（WP6，与 C++ TextureAtlas.h crop_* 同步） */
    private val cropSrcRects: Array<Rect> by lazy {
        val rects = arrayOfNulls<Rect>(SpriteAtlasDef.CropStage.values().size)
        for (stage in SpriteAtlasDef.CropStage.values()) {
            val sr = stage.rect
            rects[stage.ordinal] = Rect(sr.x, sr.y, sr.x + sr.w, sr.y + sr.h)
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

    /**
     * 渲染一帧到帧缓冲区。
     *
     * 渲染策略优先级：
     * 1. Scroll-Frame Compositing（数据未变 + 小偏移）→ 平移 + 边缘填充
     * 2. Chunk 缓存完整渲染（chunk 有效时）→ ~4 次 drawBitmap
     *
     * @param fadeAlpha 地图淡入 alpha（0-1，默认 1 不透明；WP4）——作用于 chunk
     * 合成（共享 [paint] alpha，每帧设回 255），预览/高亮用独立 Paint 不受影响，
     * 与 Vulkan 路径（drawRect/drawSprite 不受 g_fadeAlpha 影响）行为双端一致。
     * 纯每帧乘数，不触发任何 chunk 重建。
     */
    fun renderFrame(
        frame: RenderFrame,
        atlas: Bitmap,
        vpW: Int,
        vpH: Int,
        fadeAlpha: Float = 1f
    ): Bitmap? {
        // ★ 装饰层 LOD 最终判定（WP5）：scale/热控/显式关闭三条件收敛于
        // RenderLodPolicy 纯函数（与 C++ skipDecor 同阈值双端对齐）。
        // 在 ensureFrameBuffer 前计算——帧缓冲重建时按合并值重置 chunk 失效基准，
        // 防"此处比较一处、别处判断另一处"的漂移
        val decorSkip = computeDecorSkip(frame)
        ensureFrameBuffer(vpW, vpH, decorSkip)
        val canvas = frameCanvas
        val fb = frameBuffer
        val scale = sanitizeScale(frame)
        // 三守卫合并（canvas/fb 生命周期绑定；scale 非法时返回旧帧缓冲不渲染）
        if (canvas == null || fb == null || scale == null) return fb
        val tileSize = config.tileSize
        val td = frame.tileData
        val buildingArray = frame.buildingData

        // 引用缓存优化：跳过 contentHashCode O(n) 遍历当数据引用未变化
        val tileHash = if (td === cachedTileData) chunkTileHash else td.contentHashCode().also { cachedTileData = td }
        val buildingHash = if (buildingArray === cachedBuildingData) chunkBuildingHash else (buildingArray?.contentHashCode() ?: 0).also { cachedBuildingData = buildingArray }

        // ═══════════════════════════════════════════════════════
        // Chunk 缓存完整渲染（Scroll Compositing 已废弃）
        // ═══════════════════════════════════════════════════════

        // Chunk 失效检查 + 重建（WP5：装饰判定用 LOD 合并值——档位内浮点微动不触发重建防抖动）
        if (invalidateChunksForChanges(tileHash, buildingHash, decorSkip, buildingArray, frame.buildingCount)) {
            rebuildInvalidChunks(atlas, frame, decorSkip)
        }

        // 合成可见 chunk → 灵田作物层 → 选中高亮 → 拆除高亮 → 预览精灵 → 网格线
        composeVisibleChunks(canvas, frame, tileSize, scale, vpW, vpH, fadeAlpha)
        drawCrops(canvas, atlas, frame, fadeAlpha)
        if (config.renderFlags.selectionHighlight) {
            drawSelectionHighlight(canvas, frame)
        }
        drawDemolishHighlight(canvas, frame)
        if (frame.showPreview) {
            drawPreview(canvas, atlas, frame)
        }
        drawGridOverlay(canvas, frame, vpW, vpH)

        return fb
    }

    /**
     * 装饰层 LOD 最终判定（WP5）。
     *
     * decorLod 关闭时忽略 scale 条件（行为 = 特性未实现前现状）。
     * 返回值同时供帧缓冲重建重置失效基准与 chunk 失效比较——同源杜绝漂移。
     */
    private fun computeDecorSkip(frame: RenderFrame): Boolean {
        return if (config.renderFlags.decorLod) {
            !RenderLodPolicy.decorationsEnabled(frame.scale, decorationsDisabled, qualityFactor)
        } else {
            decorationsDisabled || qualityFactor < RenderLodPolicy.DECOR_QUALITY_THRESHOLD
        }
    }

    /**
     * 相机/缩放合法性检查 + 缩放钳制。
     *
     * @return 合法 → 钳制后的 scale；NaN/Inf → null（调用方返回当前帧缓冲不渲染）
     */
    private fun sanitizeScale(frame: RenderFrame): Float? {
        if (frame.camX.isNaN() || frame.camX.isInfinite() ||
            frame.camY.isNaN() || frame.camY.isInfinite() ||
            frame.scale.isNaN() || frame.scale.isInfinite()
        ) {
            android.util.Log.w(TAG, "renderFrame: NaN/Inf in camera/scale")
            return null
        }
        return frame.scale.coerceIn(MIN_SCALE, MAX_SCALE)
    }

    /** 全部 chunk 失效（瓦片/装饰/建筑清空路径统一入口） */
    private fun invalidateAllChunks() {
        for (col in 0 until NUM_CHUNKS_COL) {
            for (row in 0 until NUM_CHUNKS_ROW) {
                chunkCaches[col][row].isValid = false
            }
        }
    }

    /**
     * Chunk 失效检查（WP5：装饰判定用 LOD 合并值——档位内浮点微动不触发重建防抖动）。
     *
     * @return 是否有 chunk 需要重建
     */
    private fun invalidateChunksForChanges(
        tileHash: Int,
        buildingHash: Int,
        decorSkip: Boolean,
        buildingArray: FloatArray?,
        buildingCount: Int
    ): Boolean {
        val chunkTileChanged = tileHash != chunkTileHash
        val chunkBuildingChanged = buildingHash != chunkBuildingHash
        val chunkDecorChanged = decorSkip != lastDecorationsDisabled

        if (chunkDecorChanged) {
            lastDecorationsDisabled = decorSkip
        }
        if (chunkTileChanged || chunkDecorChanged) {
            invalidateAllChunks()
            if (chunkTileChanged) chunkTileHash = tileHash
        }
        if (chunkBuildingChanged) {
            chunkBuildingHash = buildingHash
            if (buildingArray != null) {
                val count = buildingCount.coerceAtMost(buildingArray.size / 5)
                for (i in 0 until count) {
                    val idx = i * 5
                    val gx = buildingArray[idx].toInt()
                    val gy = buildingArray[idx + 1].toInt()
                    val nameIdx = buildingArray[idx + 4].toInt()
                    // 使用占地尺寸（footprint）计算建筑覆盖的 chunk 范围
                    val (fpW, fpH) = SpriteAtlasDef.FOOTPRINT_BY_NAME_INDEX
                        .getOrElse(nameIdx) { 2 to 2 }
                    markBuildingChunk(gx, gy, fpW, fpH)
                }
                consumeInvalidationFlags()
            } else {
                invalidateAllChunks()
            }
        }
        return chunkTileChanged || chunkBuildingChanged || chunkDecorChanged
    }

    /** 消费建筑失效标记：flagged chunk 置无效并复位标记（建筑变更专用） */
    private fun consumeInvalidationFlags() {
        for (col in 0 until NUM_CHUNKS_COL) {
            for (row in 0 until NUM_CHUNKS_ROW) {
                if (chunkInvalidationFlags[row * NUM_CHUNKS_COL + col]) {
                    chunkCaches[col][row].isValid = false
                    chunkInvalidationFlags[row * NUM_CHUNKS_COL + col] = false
                }
            }
        }
    }

    /** 标记建筑覆盖的 chunk 范围失效（footprint 可能跨 chunk 边界） */
    private fun markBuildingChunk(gx: Int, gy: Int, fpW: Int, fpH: Int) {
        val firstCol = (gx / CHUNK_SIZE_TILES).coerceIn(0, NUM_CHUNKS_COL - 1)
        val lastCol = ((gx + fpW - 1) / CHUNK_SIZE_TILES).coerceIn(0, NUM_CHUNKS_COL - 1)
        val firstRow = (gy / CHUNK_SIZE_TILES).coerceIn(0, NUM_CHUNKS_ROW - 1)
        val lastRow = ((gy + fpH - 1) / CHUNK_SIZE_TILES).coerceIn(0, NUM_CHUNKS_ROW - 1)
        for (c in firstCol..lastCol) {
            for (r in firstRow..lastRow) {
                chunkInvalidationFlags[r * NUM_CHUNKS_COL + c] = true
            }
        }
    }

    /** 重建全部失效 chunk（失效检查完成后统一执行，防半失效窗口） */
    private fun rebuildInvalidChunks(atlas: Bitmap, frame: RenderFrame, decorSkip: Boolean) {
        for (col in 0 until NUM_CHUNKS_COL) {
            for (row in 0 until NUM_CHUNKS_ROW) {
                val chunk = chunkCaches[col][row]
                if (!chunk.isValid) {
                    chunkRebuildCount++
                    chunk.rebuild(
                        atlas = atlas,
                        tileData = frame.tileData,
                        cols = frame.cols,
                        buildingArray = frame.buildingData,
                        buildingCount = frame.buildingCount,
                        decorSkip = decorSkip,
                        buildingShadows = config.renderFlags.buildingShadows
                    )
                }
            }
        }
    }

    /**
     * 合成可见 chunk 到帧缓冲区。
     *
     * ★ 以首个可见 Chunk 为基准计算屏幕位置，后续 Chunk 递推——独立计算每个
     * Chunk 时 roundToInt 可能产生 ±1px 偏差，导致相邻 Chunk 之间出现
     * 1px 背景色裂缝（白线闪烁）。
     *
     * ★ 地图淡入（WP4）：fadeAlpha 作用于共享 [paint].alpha（每帧设回 255——
     * paint 被 chunk 烘焙复用，残留 alpha 会导致后续 rebuild 输出半透明）
     */
    private fun composeVisibleChunks(
        canvas: Canvas,
        frame: RenderFrame,
        tileSize: Int,
        scale: Float,
        vpW: Int,
        vpH: Int,
        fadeAlpha: Float
    ) {
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
        paint.alpha = (fadeAlpha.coerceIn(0f, 1f) * 255).toInt()
        val reuseRect = Rect()
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
        paint.alpha = 255 // 恢复：paint 供 chunk 烘焙复用，禁止残留半透明
    }

    /**
     * 绘制选中建筑高亮（金色描边 + 半透明填充，逐帧叠加）。
     *
     * 框选**占地矩形**（与点击命中判定 [BuildingRenderGeometry.findBuildingIndex]
     * 同一几何来源）。线宽按相机缩放折算：屏幕线宽恒定 max(2px, tileSize×0.06)。
     * 数据源与瓦片/建筑绘制同一份 frame.buildingData（总线脏帧时索引已被
     * SoftwareRenderBackend 重置为 -1，此处只需防御性校验）。
     */
    private fun drawSelectionHighlight(canvas: Canvas, frame: RenderFrame) {
        val buildingData = frame.buildingData
        val index = frame.selectedBuildingIndex
        val base = index * SELECTED_DATA_STRIDE
        val validIndex = buildingData != null && index in 0 until frame.buildingCount &&
            base + SELECTED_DATA_STRIDE - 1 < buildingData.size // 防御：数组截断
        if (!validIndex) return

        val tileSize = config.tileSize
        val scale = frame.scale
        val gx = buildingData[base].toInt()
        val gy = buildingData[base + 1].toInt()
        val nameIdx = buildingData[base + 4].toInt()
        val (fpW, fpH) = SpriteAtlasDef.FOOTPRINT_BY_NAME_INDEX
            .getOrElse(nameIdx) { 2 to 2 }

        val left = ((gx * tileSize - frame.camX) * scale).roundToInt()
        val top = ((gy * tileSize - frame.camY) * scale).roundToInt()
        val right = (((gx + fpW) * tileSize - frame.camX) * scale).roundToInt()
        val bottom = (((gy + fpH) * tileSize - frame.camY) * scale).roundToInt()
        val offScreenX = right <= 0 || left >= canvas.width
        val offScreenY = bottom <= 0 || top >= canvas.height
        val degenerate = right - left <= 0 || bottom - top <= 0
        if (offScreenX || offScreenY || degenerate) return

        // 目标屏幕线宽 max(2px, tileSize×0.06×scale)（Canvas 坐标 = 屏幕像素，无需换算）
        val lineWidth = maxOf(2f, tileSize * HIGHLIGHT_LINE_WIDTH_TILES * scale)

        // 填充 → 上边 → 下边 → 左边 → 右边（描边盖住填充边缘）
        highlightPaint.color = HIGHLIGHT_FILL_COLOR
        canvas.drawRect(left.toFloat(), top.toFloat(), right.toFloat(), bottom.toFloat(), highlightPaint)
        highlightPaint.color = HIGHLIGHT_EDGE_COLOR
        canvas.drawRect(left.toFloat(), top.toFloat(), right.toFloat(), top + lineWidth, highlightPaint)
        canvas.drawRect(left.toFloat(), bottom - lineWidth, right.toFloat(), bottom.toFloat(), highlightPaint)
        canvas.drawRect(left.toFloat(), top.toFloat(), left + lineWidth, bottom.toFloat(), highlightPaint)
        canvas.drawRect(right - lineWidth, top.toFloat(), right.toFloat(), bottom.toFloat(), highlightPaint)
    }

    /**
     * 绘制一键拆除模式占地高亮（逐帧动态叠加，与精灵同帧同相机）。
     *
     * 数据驱动：markers 与 frame.buildingData **同序同长**（Compose 侧
     * [buildDemolishHighlightData] 按与 buildBuildingDataArray 同一排序构建），
     * 每建筑 1 字节：[DemolishHighlightMark.NONE] 跳过、GREEN 绿填充、
     * SELECTED 红填充 + 红描边。footprint 复用
     * [SpriteAtlasDef.FOOTPRINT_BY_NAME_INDEX]（与选中高亮同一几何来源）。
     * 总线脏帧时 markers 已被 SoftwareRenderBackend 置 null（下帧随重组恢复），
     * 此处只需防御性 clamp。
     */
    private fun drawDemolishHighlight(canvas: Canvas, frame: RenderFrame) {
        val markers = frame.demolishHighlightData
        val buildingData = frame.buildingData
        if (markers == null || buildingData == null) return
        val count = minOf(frame.buildingCount, markers.size)
            .coerceAtMost(buildingData.size / SELECTED_DATA_STRIDE)

        for (i in 0 until count) {
            val base = i * SELECTED_DATA_STRIDE
            if (base + SELECTED_DATA_STRIDE - 1 >= buildingData.size) return // 截断防御
            drawDemolishMarker(canvas, frame, buildingData, base, markers[i])
        }
    }

    /**
     * 绘制单个建筑的高亮矩形（NONE 跳过 / GREEN 绿填充 / SELECTED 红填充 + 红描边）。
     * footprint 复用 [SpriteAtlasDef.FOOTPRINT_BY_NAME_INDEX]（与选中高亮同一几何来源）。
     */
    private fun drawDemolishMarker(
        canvas: Canvas,
        frame: RenderFrame,
        buildingData: FloatArray,
        base: Int,
        marker: Byte
    ) {
        if (marker == DemolishHighlightMark.NONE.toByte()) return
        val tileSize = config.tileSize
        val scale = frame.scale
        val gx = buildingData[base].toInt()
        val gy = buildingData[base + 1].toInt()
        val nameIdx = buildingData[base + 4].toInt()
        val (fpW, fpH) = SpriteAtlasDef.FOOTPRINT_BY_NAME_INDEX
            .getOrElse(nameIdx) { 2 to 2 }

        val left = ((gx * tileSize - frame.camX) * scale).roundToInt()
        val top = ((gy * tileSize - frame.camY) * scale).roundToInt()
        val right = (((gx + fpW) * tileSize - frame.camX) * scale).roundToInt()
        val bottom = (((gy + fpH) * tileSize - frame.camY) * scale).roundToInt()
        val offScreenX = right <= 0 || left >= canvas.width
        val offScreenY = bottom <= 0 || top >= canvas.height
        val degenerate = right - left <= 0 || bottom - top <= 0
        if (offScreenX || offScreenY || degenerate) return

        val lineWidth = maxOf(2f, tileSize * HIGHLIGHT_LINE_WIDTH_TILES * scale)
        demolishPaint.color = if (marker == DemolishHighlightMark.SELECTED.toByte()) {
            DEMOLISH_RED_FILL_COLOR
        } else {
            DEMOLISH_GREEN_FILL_COLOR
        }
        canvas.drawRect(left.toFloat(), top.toFloat(), right.toFloat(), bottom.toFloat(), demolishPaint)
        if (marker == DemolishHighlightMark.SELECTED.toByte()) {
            // 填充 → 上边 → 下边 → 左边 → 右边（描边盖住填充边缘）
            demolishPaint.color = DEMOLISH_RED_EDGE_COLOR
            canvas.drawRect(left.toFloat(), top.toFloat(), right.toFloat(), top + lineWidth, demolishPaint)
            canvas.drawRect(left.toFloat(), bottom - lineWidth, right.toFloat(), bottom.toFloat(), demolishPaint)
            canvas.drawRect(left.toFloat(), top.toFloat(), left + lineWidth, bottom.toFloat(), demolishPaint)
            canvas.drawRect(right - lineWidth, top.toFloat(), right.toFloat(), bottom.toFloat(), demolishPaint)
        }
    }

    /**
     * 绘制放置/移动模式全视口网格线（逐帧动态叠加，与地图同帧同相机）。
     *
     * 范围数学与旧 Compose GridOverlay.drawFullGrid 同式：按 frame.camX/Y 计算
     * 视口内行列区间并钳制到世界边界（frame.camX 来自 mergeCameraAndBuildingData
     * 合并后的最新相机，与 chunk 同帧同相机——消除 Compose 覆盖层相位差）。
     */
    private fun drawGridOverlay(canvas: Canvas, frame: RenderFrame, vpW: Int, vpH: Int) {
        if (!frame.gridOverlayVisible) return
        val tileSize = config.tileSize
        val scale = frame.scale
        if (vpW <= 0 || vpH <= 0) return

        val firstCol = (frame.camX / tileSize).toInt().coerceAtLeast(0)
        val lastCol = ((frame.camX + vpW / scale) / tileSize).toInt()
            .coerceAtMost(frame.cols)
        val firstRow = (frame.camY / tileSize).toInt().coerceAtLeast(0)
        val lastRow = ((frame.camY + vpH / scale) / tileSize).toInt()
            .coerceAtMost(frame.rows)

        gridPaint.color = GRID_OVERLAY_COLOR
        for (col in firstCol..lastCol) {
            val sx = (col * tileSize - frame.camX) * scale
            canvas.drawLine(sx, 0f, sx, vpH.toFloat(), gridPaint)
        }
        for (row in firstRow..lastRow) {
            val sy = (row * tileSize - frame.camY) * scale
            canvas.drawLine(0f, sy, vpW.toFloat(), sy, gridPaint)
        }
    }

    // ============================================================
    // 灵田作物层绘制（WP6）
    // ============================================================

    /**
     * 绘制灵田作物层（逐帧动态叠加——不烘焙 chunk，生长进度变化零重建成本）。
     *
     * 数据源 [RenderFrame.spiritCropData]（[gx, gy, progress01] × N，MainGameScreen
     * 按灵田建筑 ↔ 种植记录映射派生，低频变化走帧率门控 RenderFrame）。
     * 三阶段精灵 + 阶段内交叉淡化：alpha = crossfade × 全局淡入，
     * 与 Vulkan 侧（C++ 作物段，`alpha * fadeAlpha`）同数学双端对齐。
     *
     * 防御：progress NaN/Inf/越界跳过（与 C++ `progress != progress` 判定同语义）；
     * 视口剔除与 [drawSelectionHighlight] 同风格（屏幕坐标整型化）。
     */
    private fun drawCrops(canvas: Canvas, atlas: Bitmap, frame: RenderFrame, fadeAlpha: Float) {
        val cropData = frame.spiritCropData ?: return
        val count = cropData.size / CROP_DATA_STRIDE
        val fade = fadeAlpha.coerceIn(0f, 1f)
        val alpha = frame.currentAlpha
        val activeKeys = HashSet<Long>(count * 2)
        for (i in 0 until count) {
            val idx = i * CROP_DATA_STRIDE
            val gx = cropData[idx]
            val gy = cropData[idx + 1]
            val progress = cropData[idx + 2]
            if (progress.isNaN() || progress.isInfinite()) continue // NaN/Inf 防御
            if (progress < 0f || progress > 1f) continue // 越界防御（生成侧漏网兜底）

            // 视口剔除（屏幕坐标，与 drawSelectionHighlight 同风格）
            val rect = cropScreenRect(frame, idx, canvas.width, canvas.height) ?: continue

            // 批次 3 插值消费链：draw = prev + (cur - prev) × frameAlpha——
            // 与 C++ 作物段同数学（SpiritCropRender.smoothedProgress）；
            // 插值基准存原始逻辑值（存平滑值会累积漂移），平滑值仅用于绘制
            val key = SpiritCropRender.cropProgressKey(gx, gy)
            activeKeys += key
            val prev = lastCropProgress[key]
            val drawProgress = if (prev != null && alpha > 0f) {
                SpiritCropRender.smoothedProgress(prev, progress, alpha)
            } else {
                progress
            }
            lastCropProgress[key] = progress

            // computeStage 恒返回 [0, CROP_STAGES)（内部 NaN/Inf/clamp 防御）→ 索引安全
            val stage = SpiritCropRender.computeStage(drawProgress)
            cropPaint.alpha = (SpiritCropRender.crossfade(drawProgress) * fade * 255).toInt()
            canvas.drawBitmap(atlas, cropSrcRects[stage], rect, cropPaint)
        }
        // 帧末裁剪：收获/拆除后清除残留进度条目（作物数量少，O(n) 可接受）
        lastCropProgress.keys.retainAll(activeKeys)
        cropPaint.alpha = 255 // 防御性恢复（cropPaint 仅本方法使用，保持惯例防未来共享）
    }

    /**
     * 作物屏幕矩形（视口剔除：视口外/退化尺寸 → null）。
     *
     * @param idx cropData 内的条目起始下标（gx/gy 读取自 [RenderFrame.spiritCropData]）
     */
    private fun cropScreenRect(frame: RenderFrame, idx: Int, vpW: Int, vpH: Int): Rect? {
        val cropData = frame.spiritCropData
        if (cropData == null) return null
        val gx = cropData[idx]
        val gy = cropData[idx + 1]
        val tileSize = config.tileSize
        val scale = frame.scale
        val left = ((gx * tileSize - frame.camX) * scale).roundToInt()
        val top = ((gy * tileSize - frame.camY) * scale).roundToInt()
        val right = (((gx + 1) * tileSize - frame.camX) * scale).roundToInt()
        val bottom = (((gy + 1) * tileSize - frame.camY) * scale).roundToInt()
        val offScreenX = right <= 0 || left >= vpW
        val offScreenY = bottom <= 0 || top >= vpH
        val degenerate = right - left <= 0 || bottom - top <= 0
        val visible = !offScreenX && !offScreenY && !degenerate
        return if (visible) Rect(left, top, right, bottom) else null
    }

    // ============================================================
    // 帧缓冲区管理
    // ============================================================

    private fun ensureFrameBuffer(vpWIn: Int, vpHIn: Int, decorSkip: Boolean) {
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
            // 帧缓冲降级阈值与 RenderLodPolicy 同源（SpriteAtlasDef 生成常量）
            val bmpConfig = if (qualityFactor < RenderLodPolicy.DECOR_QUALITY_THRESHOLD) {
                Bitmap.Config.RGB_565
            } else {
                Bitmap.Config.ARGB_8888
            }
            // ★ 不调 recycle() — 见 release() 注释。GC + NativeAllocationRegistry
            //   自然回收即可避免 #11008 国产 ROM double-free SIGABRT
            frameBuffer = Bitmap.createBitmap(vpW.coerceAtLeast(1), vpH.coerceAtLeast(1), bmpConfig)
            frameCanvas = Canvas(frameBuffer ?: return)
            // resize 时清除缓存
            chunkCaches.forEach { col -> col.forEach { it.isValid = false } }
            chunkTileHash = 0
            chunkBuildingHash = 0
            chunkDecorVersion = 0
            // WP5：chunk 失效基准用 LOD 合并值（scale/热控/显式关闭），
            // 与 renderFrame 的比较值同源，杜绝基准漂移
            lastDecorationsDisabled = decorSkip
            lastQualityFactor = qualityFactor
        }
    }

    // ============================================================
    // 预览精灵绘制
    // ============================================================

    private fun drawPreview(canvas: Canvas, atlas: Bitmap, frame: RenderFrame) {
        val scale = frame.scale
        val pOffX = frame.previewX - frame.camX
        val pOffY = frame.previewY - frame.camY
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

        // tint 乘法（texture × tint，与 C++ drawSprite 顶点色乘算双端对齐）：
        // 白色纹理 × tint = tint 色；非白色纹理 = 逐通道相乘。
        // 默认 tint (1,1,1) 时不装 ColorFilter，零开销。
        val tintR = frame.previewTintRed.coerceIn(0f, 1f)
        val tintG = frame.previewTintGreen.coerceIn(0f, 1f)
        val tintB = frame.previewTintBlue.coerceIn(0f, 1f)
        val tintFilter = if (tintR == 1f && tintG == 1f && tintB == 1f) {
            null
        } else {
            ColorMatrixColorFilter(ColorMatrix(floatArrayOf(
                tintR, 0f, 0f, 0f, 0f,
                0f, tintG, 0f, 0f, 0f,
                0f, 0f, tintB, 0f, 0f,
                0f, 0f, 0f, 1f, 0f
            )))
        }
        previewPaint.colorFilter = tintFilter

        canvas.drawBitmap(atlas,
            Rect(srcLeft, srcTop, srcRight, srcBottom),
            Rect(dstLeft, dstTop, dstRight, dstBottom),
            previewPaint)

        previewPaint.alpha = 255
        previewPaint.colorFilter = null
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
        // ★ 不调 Bitmap.recycle() — 国产 ROM (鸿蒙/澎湃OS/ColorOS) 的
        //   NativeAllocationRegistry CleanerThunk 在 recycle() 后仍会尝试
        //   二次释放原生内存导致 SIGABRT。直接置 null 让 GC 自然回收，
        //   NativeAllocationRegistry 的单次释放流程是安全的。
        //   参考: Bugly #11008 SIGABRT 根因分析
        chunkCaches.forEach { col -> col.forEach { it.bitmap = null } }
        frameBuffer = null
        frameCanvas = null
    }

}
