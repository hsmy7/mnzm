package com.xianxia.sect.ui.game.sect

import androidx.core.graphics.createBitmap

import android.graphics.*
import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.render.BuildingRenderGeometry
import com.xianxia.sect.core.render.DemolishHighlightMark
import com.xianxia.sect.core.render.NativeRenderConfig
import com.xianxia.sect.core.render.RenderFrame
import com.xianxia.sect.core.render.RenderLodPolicy
import com.xianxia.sect.core.render.RenderScalePolicy
import com.xianxia.sect.core.render.RoadCompositorBridge
import com.xianxia.sect.core.render.SpiritCropRender
import com.xianxia.sect.core.render.SpriteAtlasDef
import com.xianxia.sect.core.render.SpriteRect
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
        private val CHUNK_PIXEL = CHUNK_SIZE_TILES * GameConfig.SectMap.TILE_SIZE  // 32格 × 48px = 1536px
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

        /** 云层实例数据单条步长（[x, y, w, h, spriteIndex, alpha]） */
        private const val CLOUD_DATA_STRIDE = 6

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

    }

    // ── 渲染质量控制（由 ThermalController 驱动） ──

    @Volatile
    var qualityFactor: Float = 1.0f

    @Volatile
    var decorationsDisabled: Boolean = false

    // ── 渲染分辨率缩放（2026-08-14 平板省电：帧缓冲降采样 + 上采样提交） ──

    @Volatile
    var renderScale: Float = 1.0f
        set(value) {
            // NaN/Inf/越界消毒为 1.0（直渲）；变化时帧缓冲尺寸随之变化，
            // ensureFrameBuffer 检测尺寸差自动重建
            val safe = if (value.isFinite()) {
                value.coerceIn(RenderScalePolicy.MIN_RENDER_SCALE, RenderScalePolicy.MAX_RENDER_SCALE)
            } else {
                1.0f
            }
            field = safe
        }

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
    /** 上一次渲染的 road hash（道路数据变化 → 失效 chunk 局部重建） */
    private var chunkRoadHash: Int = 0
    /** 上一次渲染的 roadData 引用（用于跳过 contentHashCode O(n) 遍历） */
    private var cachedRoadData: IntArray? = null
    /** 测试观测：chunk 重建累计次数（WP5 LOD 档内无重建防抖断言用） */
    internal var chunkRebuildCount: Int = 0
        private set

    // ── Chunk 缓存 ──

    /** chunk 重建共享工具集（图集源矩形 + 格尺寸——跨 chunk 不变，单次构建） */
    private class ChunkDrawKit(
        val tileSize: Int,
        val tileSrcRects: Array<Rect>,
        val buildingSrcRects: Array<Rect>,
        val roadSrcRects: Map<String, Rect>
    )

    /** 相机-屏幕变换（chunk 烘焙用固定 viewport = chunk 像素尺寸） */
    private data class FrameRenderState(
        val fbW: Int,
        val fbH: Int,
        val renderScale: Float,
        val tileHash: Int,
        val buildingHash: Int,
        val roadHash: Int
    )

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
        /** 1536×1536（32 格 × 48px）, RGB_565, 惰性创建。仅宗门地图可见时占用内存，切 Tab 时释放 */
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
         * 帧数据（瓦片/建筑/道路）统一取自 [frame]（RenderFrame 已聚合，避免 8 参
         * 长参数列表——CLAUDE.md 3.4 参数分组规范）
         *
         * @param decorSkip 装饰层跳过判定（WP5：由 RenderLodPolicy 在 renderFrame
         * 层合并 scale/热控质量/显式关闭三条件——此处只消费最终布尔值，
         * 保证"判定一处、失效一处"，与 C++ skipDecor 双端对齐）
         */
        fun rebuild(
            frame: RenderFrame,
            atlas: Bitmap,
            groundSrc: Bitmap,
            decorSkip: Boolean,
            buildingShadows: Boolean
        ) {
            val bmp = bitmap ?: createBitmap(CHUNK_PIXEL, CHUNK_PIXEL, Bitmap.Config.RGB_565).also { bitmap = it }
            val canvas = Canvas(bmp)
            canvas.drawColor(Color.rgb(0xF2, 0xED, 0xE4))

            drawGroundAndDecor(canvas, atlas, groundSrc, frame.tileData, frame.cols, decorSkip)

            // 局部值：RenderFrame 属性跨模块公开 API，smart cast 不可用
            val roadData = frame.roadData
            val buildingArray = frame.buildingData

            // 石板道路层（装饰之上、建筑之下，烘焙进 chunk——与建筑层级一致）
            if (roadData != null) {
                drawRoadsToCanvas(canvas, atlas, roadData, frame.cols)
            }

            // 绘制建筑（使用相对相机 (camX=chunk左上角, scale=1) 达到精确对齐）
            if (buildingArray != null && frame.buildingCount > 0) {
                drawBuildingsToCanvas(
                    canvas = canvas,
                    atlas = atlas,
                    buildingArray = buildingArray,
                    buildingCount = frame.buildingCount,
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
         * 绘制地面 + 装饰层（A0 整图无缝地面 + A2 装饰叠加）。
         *
         * A0：单一草皮纹理以 REPEAT BitmapShader 铺满本 chunk 有效区——整图连续无逐格接缝，
         * 与 C++ drawAllTiles 单 ground quad 双端对齐。
         * A2 的 LOD 合并判定（scale/热控/显式关闭）在 renderFrame 层经
         * [RenderLodPolicy] 收敛为 decorSkip——此处只消费最终布尔值。
         */
        private fun drawGroundAndDecor(
            canvas: Canvas,
            atlas: Bitmap,
            groundSrc: Bitmap,
            tileData: IntArray,
            cols: Int,
            decorSkip: Boolean
        ) {
            val rows = tileData.size / cols
            val startCol = col * CHUNK_SIZE_TILES
            val startRow = row * CHUNK_SIZE_TILES
            val endCol = (startCol + CHUNK_SIZE_TILES).coerceAtMost(cols)
            val endRow = (startRow + CHUNK_SIZE_TILES).coerceAtMost(rows)
            if (endCol > startCol && endRow > startRow) {
                drawGroundFill(canvas, groundSrc, startCol, startRow, endCol, endRow)
            }
            for (r in startRow until endRow) {
                drawGroundRow(
                    canvas, atlas, tileData, cols, decorSkip,
                    GroundRowRange(r, startRow, startCol, endCol)
                )
            }
        }

        /**
         * A0：整图无缝地面（BitmapShader REPEAT）。
         * 地面源 = 图集 GROUND rect（64×64）；shader 矩阵把 chunk 局部坐标映射到世界坐标，
         * scale = 地面宽/格宽（64/tileSize），使每个地面纹理恰好铺满一格，跨 chunk 连续。
         */
        private fun drawGroundFill(
            canvas: Canvas,
            groundSrc: Bitmap,
            startCol: Int,
            startRow: Int,
            endCol: Int,
            endRow: Int
        ) {
            val tileSize = kit.tileSize
            val shader = BitmapShader(groundSrc, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
            // 地面源 = 图集 GROUND rect 的共享副本；width 即精灵原宽（64），scale = 原宽/格宽
            val scale = groundSrc.width.toFloat() / tileSize
            val worldX = (startCol * tileSize).toFloat()
            val worldY = (startRow * tileSize).toFloat()
            shader.setLocalMatrix(Matrix().apply {
                setScale(scale, scale)
                postTranslate(worldX * scale, worldY * scale)
            })
            val paint = Paint().apply {
                this.shader = shader
                isFilterBitmap = true  // 双线性：环绕点插值过渡，消除暗接缝（与 Vulkan LINEAR 对齐）
            }
            canvas.drawRect(
                0f, 0f,
                ((endCol - startCol) * tileSize).toFloat(),
                ((endRow - startRow) * tileSize).toFloat(),
                paint
            )
        }

        /**
         * 单行装饰叠加（从 drawGroundAndDecor 提取，消除嵌套深度 4 → 3）。
         * 地面已由 A0 整图铺覆盖，此处仅绘制草/树装饰。
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

        /**
         * 石板道路层（装饰之上、建筑之下，烘焙进 chunk）。
         *
         * 逐格合成操作序列由 C++ 单一权威 `gamecore/map/road_compositor.h`
         * 产出（[RoadCompositorBridge.compose]：主体→边缘条，直路按方向出侧边缘、
         * T 中心单侧、转角两开放侧且在格内、十字中心无边缘、格内局部整型几何）——
         * 本方法仅做数据装配：RoadSprite 枚举序 → 图集精灵名 → 源矩形，按序绘制
         * （计划 v2 阶段 6 合成器物理下沉）。并行道路内部不重复描边由合成器
         * （roadBorderMask 掩码补集）保证。
         *
         * 降级契约：native 通道不可用（库加载失败，生产不触达）时跳过
         * 整个道路层，chunk 烘焙其余层不受影响。
         */
        private fun drawRoadsToCanvas(
            canvas: Canvas,
            atlas: Bitmap,
            roadData: IntArray,
            cols: Int
        ) {
            val rows = roadData.size / cols
            val startCol = col * CHUNK_SIZE_TILES
            val startRow = row * CHUNK_SIZE_TILES
            val endCol = (startCol + CHUNK_SIZE_TILES).coerceAtMost(cols)
            val endRow = (startRow + CHUNK_SIZE_TILES).coerceAtMost(rows)
            val tileSize = kit.tileSize
            val reuseRect = Rect()

            for (r in startRow until endRow) {
                for (c in startCol until endCol) {
                    val idx = r * cols + c
                    // ★ 2026-08-31 根因修复：数组为 1-based（0=非道路，1=单格道路原掩码 0，
                    // 2..16=原掩码 1..15）——直接存掩码会把单格道路（掩码 0）当非道路跳过
                    val raw = roadData[idx]
                    if (raw == 0) continue
                    val mask = raw - 1

                    val ops = RoadCompositorBridge.compose(mask, tileSize) ?: return
                    val chunkOffX = c * tileSize - startCol * tileSize
                    val chunkOffY = r * tileSize - startRow * tileSize

                    var i = 0
                    while (i + RoadCompositorBridge.OP_STRIDE <= ops.size) {
                        val key = RoadCompositorBridge.SPRITE_KEYS[ops[i]]
                        val opX = chunkOffX + ops[i + 1]
                        val opY = chunkOffY + ops[i + 2]
                        reuseRect.set(
                            opX, opY,
                            opX + ops[i + 3], opY + ops[i + 4]
                        )
                        drawRoadSprite(canvas, atlas, key, reuseRect)
                        i += RoadCompositorBridge.OP_STRIDE
                    }
                }
            }
        }

        /** 绘制单个道路精灵（源矩形由 [kit] 查询，目标矩形 [dst] 已由调用方装配）。 */
        private fun drawRoadSprite(
            canvas: Canvas,
            atlas: Bitmap,
            key: String,
            dst: Rect
        ) {
            val src = kit.roadSrcRects[key] ?: return
            canvas.drawBitmap(atlas, src, dst, rebuildPaint)
        }

        /** 视锥剔除（屏幕矩形与 chunk 视口相交判定——4 条件拆两半规避复杂条件） */
        private fun isOffScreen(left: Int, top: Int, right: Int, bottom: Int, view: ViewTransform): Boolean {
            val pastRightOrBottom = left >= view.vpW || bottom <= 0
            val beforeLeftOrTop = right <= 0 || top >= view.vpH
            return pastRightOrBottom || beforeLeftOrTop
        }

        /**
         * 建筑/固定结构占地尺寸解析：结构（nameIdx ≥ BUILDING_NAMES.size）走
         * SpriteAtlasDef.STRUCTURES，建筑走 FOOTPRINT_BY_NAME_INDEX，越界兜底 2×2。
         */
        private fun footprintOf(nameIdx: Int): Pair<Int, Int> {
            if (nameIdx >= SpriteAtlasDef.BUILDING_NAMES.size) {
                val s = SpriteAtlasDef.STRUCTURES.getOrNull(nameIdx - SpriteAtlasDef.BUILDING_NAMES.size)
                return (s?.footprintW ?: 2) to (s?.footprintH ?: 2)
            }
            return SpriteAtlasDef.FOOTPRINT_BY_NAME_INDEX.getOrElse(nameIdx) { 2 to 2 }
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

                // 固定结构（宗门入口门楼/阶梯）nameIdx ≥ BUILDING_NAMES.size，占地走 STRUCTURES 表；
                // 建筑走 FOOTPRINT_BY_NAME_INDEX
                val isStructure = nameIdx >= SpriteAtlasDef.BUILDING_NAMES.size
                val (fpW, fpH) = footprintOf(nameIdx)
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

                // 视锥剔除（提取纯函数，主循环复杂度收敛）
                if (isOffScreen(bDstLeft, bDstTop, bDstRight, bDstBottom, view)) continue

                // ★ 建筑投影阴影（精灵之下；与 C++ drawAllTiles (A2) 段同数学）
                // 固定结构（门楼/阶梯）不投影——避免阴影压到阶梯/地图底边外
                if (buildingShadows && !isStructure) {
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
        ChunkDrawKit(config.tileSize, tileSrcRects, buildingSrcRects, roadSrcRects)
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

    /** decorationsDisabled 版本号，变化时失效所有 chunk */
    private var chunkDecorVersion: Int = 0
    /** 上一次记录的 decorationsDisabled 值 */
    private var lastDecorationsDisabled: Boolean = false
    /** 上一次的 qualityFactor 值，变化时重建帧缓冲区 */
    private var lastQualityFactor: Float = 1.0f

    // ── 精灵绘制 Paint ──

    private val paint = Paint(Paint.FILTER_BITMAP_FLAG).apply {
        // 2026-08 图集建筑槽位 128→256 后放大仍可达 1.5x：双线性过滤消除 NEAREST 颗粒感，
        // 与 Vulkan 图集 sampler（LINEAR）双端观感一致
        isFilterBitmap = true
        isAntiAlias = false
        isDither = false
    }

    private val previewPaint = Paint(Paint.FILTER_BITMAP_FLAG).apply {
        isFilterBitmap = true
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

    /** 云层 Paint（独立实例——逐帧改 alpha 不得污染共享 paint，仿 cropPaint 惯例） */
    private val cloudPaint = Paint(Paint.FILTER_BITMAP_FLAG).apply {
        isFilterBitmap = false
        isAntiAlias = false
        isDither = false
    }

    /** 批次 3 插值消费链：上一帧作物原始进度（key=gx/gy 编码，见 [cropProgressKey]） */
    private val lastCropProgress = HashMap<Long, Float>()

    /**
     * 作物层本帧活跃 key 集合（成员复用——拖动视角时逐帧 clear 复用，
     * 消除每帧 HashSet 分配导致的 GC 抖动；渲染线程独占，无需同步）。
     */
    private val cropActiveKeys = HashSet<Long>()

    /**
     * 预渲染地面源位图缓存：`Bitmap.createBitmap(atlas, GROUND.rect)` 的**单一共享副本**——
     * 避免每次 chunk 重建都从图集复制一份（16 chunk 全重建曾各复制一次）。atlas 引用变化才重建。
     * （与 C++ 单 ground quad / Vulkan REPEAT 地面层对位，CPU 侧共享一份地面源。）
     */
    private var groundSourceBitmap: Bitmap? = null
    private var groundSourceAtlas: Bitmap? = null

    /** 每帧作物屏幕矩形复用（迭代中先算后用——渲染线程独占，成员复用消除逐实例 Rect 分配）。 */
    private val screenRectScratch = Rect()

    // ── 精灵图源矩形（延迟初始化） ──

    /**
     * 软件路径图集坐标缩放比。
     *
     * SectAtlasAssembler.buildAtlasBitmap 为防低端机 OOM 把 4096 图集封顶缩放到
     * 2048（canvasAtlasScale = CANVAS_ATLAS_MAX / ATLAS_W = 2048/4096 = 0.5），但
     * SpriteAtlasDef 的图集源矩形仍是 4096 坐标系。若直接用 4096 坐标在 2048 位图上
     * 采样，会越界/取样到相邻槽位——地面 REPEAT 源裁出「绿块+透明」导致整图铺出米色
     * 空隙，装饰/建筑错位。故所有源矩形与地面源裁切必须按实际图集宽度缩放。
     *
     * 值由渲染帧传入的 atlas 实际宽度推导（atlas.width / SpriteAtlasDef.ATLAS_W），
     * 生产图集恒正方形（2048/4096=0.5），测试用任意宽位图亦自动适配。
     */
    private var sourceScale: Float = 1f

    private fun <T> buildScaledRects(source: List<T>, rectOf: (T) -> SpriteRect): Array<Rect> =
        source.mapIndexed { _, item ->
            val sr = rectOf(item)
            Rect(
                (sr.x * sourceScale).roundToInt(),
                (sr.y * sourceScale).roundToInt(),
                ((sr.x + sr.w) * sourceScale).roundToInt(),
                ((sr.y + sr.h) * sourceScale).roundToInt()
            )
        }.toTypedArray()

    private val tileSrcRects: Array<Rect> by lazy {
        @Suppress("UNCHECKED_CAST")
        buildScaledRects(SpriteAtlasDef.TileType.values().toList()) { it.rect } as Array<Rect>
    }

    /** 灵田作物三阶段图源矩形（WP6，与 C++ TextureAtlas.h crop_* 同步） */
    private val cropSrcRects: Array<Rect> by lazy {
        @Suppress("UNCHECKED_CAST")
        buildScaledRects(SpriteAtlasDef.CropStage.values().toList()) { it.rect } as Array<Rect>
    }

    /** 云层精灵图源矩形（按 SpriteAtlasDef.CLOUD_RECTS 声明顺序，与 C++ CLOUD_UV_MAP 同源） */
    private val cloudSrcRects: Array<Rect> by lazy {
        @Suppress("UNCHECKED_CAST")
        buildScaledRects(SpriteAtlasDef.CLOUD_RECTS) { it.second } as Array<Rect>
    }

    private val buildingSrcRects: Array<Rect> by lazy {
        // 建筑 + 固定结构（结构 nameIdx = BUILDING_NAMES.size + index，尾部追加）
        val rects = arrayOfNulls<Rect>(SpriteAtlasDef.BUILDING_NAMES.size + SpriteAtlasDef.STRUCTURES.size)
        for (i in SpriteAtlasDef.BUILDING_NAMES.indices) {
            val sr = SpriteAtlasDef.buildingRect(i)
            rects[i] = Rect(
                (sr.x * sourceScale).roundToInt(),
                (sr.y * sourceScale).roundToInt(),
                ((sr.x + sr.w) * sourceScale).roundToInt(),
                ((sr.y + sr.h) * sourceScale).roundToInt()
            )
        }
        SpriteAtlasDef.STRUCTURES.forEachIndexed { i, s ->
            val idx = SpriteAtlasDef.BUILDING_NAMES.size + i
            val sr = s.rect
            rects[idx] = Rect(
                (sr.x * sourceScale).roundToInt(),
                (sr.y * sourceScale).roundToInt(),
                ((sr.x + sr.w) * sourceScale).roundToInt(),
                ((sr.y + sr.h) * sourceScale).roundToInt()
            )
        }
        @Suppress("UNCHECKED_CAST")
        rects as Array<Rect>
    }

    /** 石板道路精灵图源矩形（key → source rect，按 SpriteAtlasDef.ROAD_RECTS 生成） */
    private val roadSrcRects: Map<String, Rect> by lazy {
        SpriteAtlasDef.ROAD_RECTS.associate { (key, r) ->
            key to Rect(
                (r.x * sourceScale).roundToInt(),
                (r.y * sourceScale).roundToInt(),
                ((r.x + r.w) * sourceScale).roundToInt(),
                ((r.y + r.h) * sourceScale).roundToInt()
            )
        }
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
        fadeAlpha: Float = 1f,
        cloudData: FloatArray? = null
    ): Bitmap? {
        // ★ 源矩形坐标缩放比：软件路径图集由 SectAtlasAssembler 按 0.5× 缩到 2048，
        // 而 SpriteAtlasDef 源矩形是 4096 坐标系——直接采样会越界/取到相邻槽位，
        // 导致地面 REPEAT 源裁出「绿块+透明」铺出米色空隙、装饰/建筑错位。
        // 渲染帧实际 atlas 宽度推导（图集恒正方形，生产 2048/4096=0.5；测试任意宽亦适配）。
        val aw = atlas.width
        sourceScale = if (aw > 0) aw.toFloat() / SpriteAtlasDef.ATLAS_W else 1f

        // ★ 装饰层 LOD 最终判定（WP5）：scale/热控/显式关闭三条件收敛于
        // RenderLodPolicy 纯函数（与 C++ skipDecor 同阈值双端对齐）。
        // 在 ensureFrameBuffer 前计算——帧缓冲重建时按合并值重置 chunk 失效基准，
        // 防"此处比较一处、别处判断另一处"的漂移
        val decorSkip = computeDecorSkip(frame)

        // render scale（2026-08-14 平板省电）：物理视口 → 降采样帧缓冲尺寸（含
        // resizeRequested 消费，见 [prepareFrameRenderState]）
        val frameState = prepareFrameRenderState(vpW, vpH, frame)
        val fbW = frameState.fbW
        val fbH = frameState.fbH
        ensureFrameBuffer(fbW, fbH, decorSkip)
        val canvas = frameCanvas
        val fb = frameBuffer
        val scale = sanitizeScale(frame)
        // 三守卫合并（canvas/fb 生命周期绑定；scale 非法时返回旧帧缓冲不渲染）
        if (canvas == null || fb == null || scale == null) return fb
        // 世界→帧缓冲像素的生效比例（fbW/drawScale = physW/scale 比率自洽，
        // 可视世界范围与物理路径逐位一致——render scale 不改变视野）
        val drawScale = scale * frameState.renderScale
        val tileSize = config.tileSize
        val tileHash = frameState.tileHash
        val buildingHash = frameState.buildingHash
        val roadHash = frameState.roadHash

        // ═══════════════════════════════════════════════════════
        // Chunk 缓存完整渲染（Scroll Compositing 已废弃）
        // ═══════════════════════════════════════════════════════

        // Chunk 失效检查 + 重建（WP5：装饰判定用 LOD 合并值——档位内浮点微动不触发重建防抖动）
        if (invalidateChunksForChanges(tileHash, buildingHash, roadHash, decorSkip)) {
            rebuildInvalidChunks(atlas, frame, decorSkip)
        }

        // 合成可见 chunk → 灵田作物层 → 云层 → 选中高亮 → 拆除高亮 → 预览精灵 → 网格线
        composeVisibleChunks(canvas, frame, tileSize, drawScale, fbW, fbH, fadeAlpha)
        drawCrops(canvas, atlas, frame, fadeAlpha, drawScale)
        drawClouds(canvas, atlas, frame, cloudData, decorSkip, fadeAlpha, drawScale)
        if (config.renderFlags.selectionHighlight) {
            drawSelectionHighlight(canvas, frame, drawScale)
        }
        drawDemolishHighlight(canvas, frame, drawScale)
        if (frame.showPreview) {
            drawPreview(canvas, atlas, frame, drawScale)
        }
        drawGridOverlay(canvas, frame, drawScale, fbW, fbH)

        return fb
    }

    /**
     * renderFrame 帧前置状态计算（提取）：物理视口 → 帧缓冲尺寸（resizeRequested
     * 在此消费，帧缓冲尺寸 = round(物理 × renderScale)）+ chunk 哈希（引用缓存优化——
     * 数据引用未变化时跳过 contentHashCode O(n) 遍历）。
     *
     * @return [FrameRenderState]
     */
    private fun prepareFrameRenderState(vpW: Int, vpH: Int, frame: RenderFrame): FrameRenderState {
        var physW = vpW
        var physH = vpH
        if (resizeRequested) {
            resizeRequested = false
            physW = resizeRequestedW
            physH = resizeRequestedH
        }
        val rs = renderScale
        val td = frame.tileData
        val buildingArray = frame.buildingData
        val roadArray = frame.roadData
        val tileHash = if (td === cachedTileData) chunkTileHash else td.contentHashCode().also { cachedTileData = td }
        val buildingHash = if (buildingArray === cachedBuildingData) chunkBuildingHash
        else (buildingArray?.contentHashCode() ?: 0).also { cachedBuildingData = buildingArray }
        val roadHash = if (roadArray === cachedRoadData) chunkRoadHash
        else (roadArray?.contentHashCode() ?: 0).also { cachedRoadData = roadArray }
        return FrameRenderState(
            fbW = (physW * rs).roundToInt().coerceAtLeast(1),
            fbH = (physH * rs).roundToInt().coerceAtLeast(1),
            renderScale = rs,
            tileHash = tileHash,
            buildingHash = buildingHash,
            roadHash = roadHash
        )
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
        roadHash: Int,
        decorSkip: Boolean
    ): Boolean {
        val chunkTileChanged = tileHash != chunkTileHash
        val chunkBuildingChanged = buildingHash != chunkBuildingHash
        val chunkRoadChanged = roadHash != chunkRoadHash
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
            // 2026-08-16 修复（软件渲染残留根因）：建筑数据变化必须失效全部 chunk。
            // 旧实现只失效「新建筑覆盖」的 chunk——进入无建筑宗门时总线推空数组
            //（FloatArray(0)，非 null）：循环 0 次、不失效任何 chunk，上一宗门（主宗）
            // 建筑残留在 chunk 位图里 → 屏幕显示主宗建筑但点击索引已空 → 点不中；
            // 同理跨宗门切换时旧位置 chunk 不失效 → 旧建筑残留。空数组/非空列表统一
            // 失效全部 chunk（4×4 网格 16 块，建筑变化低频，重建成本可接受）。
            invalidateAllChunks()
        }
        if (chunkRoadChanged) {
            // 道路变化：失效全部 chunk（道路可能横跨多个 chunk，局部失效受 32×32 网格限制，
            // 全失效 16 块重建成本低——道路放置/删除低频，满足"只更新受影响区域"）。
            chunkRoadHash = roadHash
            invalidateAllChunks()
        }
        return chunkTileChanged || chunkBuildingChanged || chunkRoadChanged || chunkDecorChanged
    }

    /** 重建全部失效 chunk（失效检查完成后统一执行，防半失效窗口） */
    private fun rebuildInvalidChunks(atlas: Bitmap, frame: RenderFrame, decorSkip: Boolean) {
        // 单一地面源位图（跨 chunk 共享；atlas 引用变化才重复制——避免每 chunk 各复制一次）
        // ★ 按 sourceScale 缩放 GROUND 源矩形：软件路径图集是 4096 槽位的 0.5× 位图，
        //   直接用 4096 坐标裁切会裁到相邻槽位（含透明），REPEAT 平铺后出现米色空隙。
        val gRect = SpriteAtlasDef.TileType.GROUND.rect
        var groundSrc = groundSourceBitmap
        if (groundSrc == null || groundSourceAtlas !== atlas) {
            groundSrc = Bitmap.createBitmap(
                atlas,
                (gRect.x * sourceScale).roundToInt(),
                (gRect.y * sourceScale).roundToInt(),
                (gRect.w * sourceScale).roundToInt().coerceAtLeast(1),
                (gRect.h * sourceScale).roundToInt().coerceAtLeast(1)
            )
            groundSourceBitmap = groundSrc
            groundSourceAtlas = atlas
        }
        for (col in 0 until NUM_CHUNKS_COL) {
            for (row in 0 until NUM_CHUNKS_ROW) {
                val chunk = chunkCaches[col][row]
                if (!chunk.isValid) {
                    chunkRebuildCount++
                    chunk.rebuild(
                        frame = frame,
                        atlas = atlas,
                        groundSrc = groundSrc,
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
        drawScale: Float,
        fbW: Int,
        fbH: Int,
        fadeAlpha: Float
    ) {
        // 可视世界范围 = fbW/drawScale = physW/scale（比率自洽）——render scale
        // 不改变可见 chunk 集合，仅改变其屏幕像素尺寸
        val viewLeft = frame.camX
        val viewTop = frame.camY
        val viewRight = frame.camX + fbW / drawScale
        val viewBottom = frame.camY + fbH / drawScale

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
        val baseScreenX = ((firstChunkWorldX - frame.camX) * drawScale).roundToInt()
        val baseScreenY = ((firstChunkWorldY - frame.camY) * drawScale).roundToInt()
        val scaledW = (CHUNK_PIXEL * drawScale).roundToInt().coerceAtLeast(1)
        val scaledH = (CHUNK_PIXEL * drawScale).roundToInt().coerceAtLeast(1)
        for (chunkCol in firstChunkCol..lastChunkCol) {
            for (chunkRow in firstChunkRow..lastChunkRow) {
                val chunk = chunkCaches[chunkCol][chunkRow]
                val screenX = baseScreenX + (chunkCol - firstChunkCol) * scaledW
                val screenY = baseScreenY + (chunkRow - firstChunkRow) * scaledH
                // 越界判定拆两半（detekt ComplexCondition ≤4）
                val offLeftOrTop = screenX + scaledW < 0 || screenY + scaledH < 0
                val offRightOrBottom = screenX > fbW || screenY > fbH
                if (offLeftOrTop || offRightOrBottom) continue
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
    private fun drawSelectionHighlight(canvas: Canvas, frame: RenderFrame, drawScale: Float) {
        val buildingData = frame.buildingData
        val index = frame.selectedBuildingIndex
        val base = index * SELECTED_DATA_STRIDE
        val validIndex = buildingData != null && index in 0 until frame.buildingCount &&
            base + SELECTED_DATA_STRIDE - 1 < buildingData.size // 防御：数组截断
        if (!validIndex) return

        val tileSize = config.tileSize
        val gx = buildingData[base].toInt()
        val gy = buildingData[base + 1].toInt()
        val nameIdx = buildingData[base + 4].toInt()
        val (fpW, fpH) = SpriteAtlasDef.FOOTPRINT_BY_NAME_INDEX
            .getOrElse(nameIdx) { 2 to 2 }

        val left = ((gx * tileSize - frame.camX) * drawScale).roundToInt()
        val top = ((gy * tileSize - frame.camY) * drawScale).roundToInt()
        val right = (((gx + fpW) * tileSize - frame.camX) * drawScale).roundToInt()
        val bottom = (((gy + fpH) * tileSize - frame.camY) * drawScale).roundToInt()
        val offScreenX = right <= 0 || left >= canvas.width
        val offScreenY = bottom <= 0 || top >= canvas.height
        val degenerate = right - left <= 0 || bottom - top <= 0
        if (offScreenX || offScreenY || degenerate) return

        // 帧缓冲线宽（上采样后恢复物理屏幕线宽：物理线宽 × renderScale 已并入 drawScale）
        val lineWidth = maxOf(2f, tileSize * HIGHLIGHT_LINE_WIDTH_TILES * drawScale)

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
    private fun drawDemolishHighlight(canvas: Canvas, frame: RenderFrame, drawScale: Float) {
        val markers = frame.demolishHighlightData
        val buildingData = frame.buildingData
        if (markers == null || buildingData == null) return
        val count = minOf(frame.buildingCount, markers.size)
            .coerceAtMost(buildingData.size / SELECTED_DATA_STRIDE)

        for (i in 0 until count) {
            val base = i * SELECTED_DATA_STRIDE
            if (base + SELECTED_DATA_STRIDE - 1 >= buildingData.size) return // 截断防御
            drawDemolishMarker(canvas, frame, buildingData, base, markers[i], drawScale)
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
        marker: Byte,
        drawScale: Float
    ) {
        if (marker == DemolishHighlightMark.NONE.toByte()) return
        val tileSize = config.tileSize
        val gx = buildingData[base].toInt()
        val gy = buildingData[base + 1].toInt()
        val nameIdx = buildingData[base + 4].toInt()
        val (fpW, fpH) = SpriteAtlasDef.FOOTPRINT_BY_NAME_INDEX
            .getOrElse(nameIdx) { 2 to 2 }

        val left = ((gx * tileSize - frame.camX) * drawScale).roundToInt()
        val top = ((gy * tileSize - frame.camY) * drawScale).roundToInt()
        val right = (((gx + fpW) * tileSize - frame.camX) * drawScale).roundToInt()
        val bottom = (((gy + fpH) * tileSize - frame.camY) * drawScale).roundToInt()
        val offScreenX = right <= 0 || left >= canvas.width
        val offScreenY = bottom <= 0 || top >= canvas.height
        val degenerate = right - left <= 0 || bottom - top <= 0
        if (offScreenX || offScreenY || degenerate) return

        val lineWidth = maxOf(2f, tileSize * HIGHLIGHT_LINE_WIDTH_TILES * drawScale)
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
    private fun drawGridOverlay(canvas: Canvas, frame: RenderFrame, drawScale: Float, fbW: Int, fbH: Int) {
        if (!frame.gridOverlayVisible) return
        val tileSize = config.tileSize
        if (fbW <= 0 || fbH <= 0) return

        // 可视范围用 fbW/drawScale（= 物理 vpW/scale，比率自洽，网格集合与物理路径一致）
        val firstCol = (frame.camX / tileSize).toInt().coerceAtLeast(0)
        val lastCol = ((frame.camX + fbW / drawScale) / tileSize).toInt()
            .coerceAtMost(frame.cols)
        val firstRow = (frame.camY / tileSize).toInt().coerceAtLeast(0)
        val lastRow = ((frame.camY + fbH / drawScale) / tileSize).toInt()
            .coerceAtMost(frame.rows)

        gridPaint.color = GRID_OVERLAY_COLOR
        for (col in firstCol..lastCol) {
            val sx = (col * tileSize - frame.camX) * drawScale
            canvas.drawLine(sx, 0f, sx, fbH.toFloat(), gridPaint)
        }
        for (row in firstRow..lastRow) {
            val sy = (row * tileSize - frame.camY) * drawScale
            canvas.drawLine(0f, sy, fbW.toFloat(), sy, gridPaint)
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
    private fun drawCrops(canvas: Canvas, atlas: Bitmap, frame: RenderFrame, fadeAlpha: Float, drawScale: Float) {
        val cropData = frame.spiritCropData ?: return
        val count = cropData.size / CROP_DATA_STRIDE
        val fade = fadeAlpha.coerceIn(0f, 1f)
        val alpha = frame.currentAlpha
        cropActiveKeys.clear()
        for (i in 0 until count) {
            val idx = i * CROP_DATA_STRIDE
            val gx = cropData[idx]
            val gy = cropData[idx + 1]
            val progress = cropData[idx + 2]
            if (progress.isNaN() || progress.isInfinite()) continue // NaN/Inf 防御
            if (progress < 0f || progress > 1f) continue // 越界防御（生成侧漏网兜底）

            // 视口剔除 + gx/gy 合法性（对抗性审查 2026-08-13 数据篡改者#4：
            // NaN 坐标经 roundToInt 收敛到 (0,0) 漂浮 + key 碰撞串扰——与 C++
            // 侧 gx != gx 防御同语义，收敛于 cropScreenRect 单一出口）
            val rect = cropScreenRect(frame, idx, drawScale, canvas.width, canvas.height) ?: continue

            // 批次 3 插值消费链：draw = prev + (cur - prev) × frameAlpha——
            // 与 C++ 作物段同数学（SpiritCropRender.smoothedProgress）；
            // 插值基准存原始逻辑值（存平滑值会累积漂移），平滑值仅用于绘制
            val key = SpiritCropRender.cropProgressKey(gx, gy)
            cropActiveKeys += key
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
        lastCropProgress.keys.retainAll(cropActiveKeys)
        cropPaint.alpha = 255 // 防御性恢复（cropPaint 仅本方法使用，保持惯例防未来共享）
    }

    /**
     * 作物屏幕矩形（视口剔除：视口外/退化尺寸/非法坐标 → null）。
     *
     * @param idx cropData 内的条目起始下标（gx/gy 读取自 [RenderFrame.spiritCropData]）
     * @param drawScale 世界→帧缓冲像素比例（含 renderScale）
     * @param fbW 帧缓冲宽度（降采样后）
     * @param fbH 帧缓冲高度（降采样后）
     */
    private fun cropScreenRect(frame: RenderFrame, idx: Int, drawScale: Float, fbW: Int, fbH: Int): Rect? {
        val cropData = frame.spiritCropData
        // NaN/Inf 坐标防御（对抗性审查 2026-08-13 数据篡改者#4）：
        // 非法坐标既不参与绘制也不进入插值状态表（共享防御入口 SpiritCropRender）
        val coordValid = cropData != null &&
            SpiritCropRender.isValidCropCoord(cropData[idx]) &&
            SpiritCropRender.isValidCropCoord(cropData[idx + 1])
        if (!coordValid) return null
        val gx = cropData[idx]
        val gy = cropData[idx + 1]
        val tileSize = config.tileSize
        val left = ((gx * tileSize - frame.camX) * drawScale).roundToInt()
        val top = ((gy * tileSize - frame.camY) * drawScale).roundToInt()
        val right = (((gx + 1) * tileSize - frame.camX) * drawScale).roundToInt()
        val bottom = (((gy + 1) * tileSize - frame.camY) * drawScale).roundToInt()
        val offScreenX = right <= 0 || left >= fbW
        val offScreenY = bottom <= 0 || top >= fbH
        val degenerate = right - left <= 0 || bottom - top <= 0
        val visible = !offScreenX && !offScreenY && !degenerate
        // 复用成员矩形（渲染线程独占、先算后用）——消除逐作物 Rect 分配
        screenRectScratch.set(left, top, right, bottom)
        return if (visible) screenRectScratch else null
    }

    // ============================================================
    // 云层绘制（世界顶部动态云朵）
    // ============================================================

    /**
     * 绘制云层（逐帧动态叠加，不烘焙 chunk）。
     *
     * 数据源 [cloudData]（[x, y, w, h, spriteIndex, alpha] × N，渲染线程逐帧生成快照，
     * 与 C++ 侧同一份数据——双端像素级一致）。绘制在建筑/作物层之后（可遮挡建筑）、
     * 高亮/预览/网格线之前（不遮挡交互反馈）。alpha = 实例 alpha × 全局淡入，
     * 与 C++ 侧 `alpha * fadeAlpha` 同数学。
     *
     * 防御：NaN/非法值跳过（与 C++ 段同语义——非法实例不画任何像素）；视口剔除；
     * decorSkip（热控/装饰关闭/缩放 LOD）时整层跳过——与装饰层同判定，低端设备自动降级。
     */
    private fun drawClouds(
        canvas: Canvas,
        atlas: Bitmap,
        frame: RenderFrame,
        cloudData: FloatArray?,
        decorSkip: Boolean,
        fadeAlpha: Float,
        drawScale: Float
    ) {
        if (cloudData == null || decorSkip) return
        val count = cloudData.size / CLOUD_DATA_STRIDE
        if (count == 0) return
        val fade = fadeAlpha.coerceIn(0f, 1f)
        for (i in 0 until count) {
            val idx = i * CLOUD_DATA_STRIDE
            val x = cloudData[idx]
            val y = cloudData[idx + 1]
            val w = cloudData[idx + 2]
            val h = cloudData[idx + 3]
            val spriteIndex = cloudData[idx + 4]
            val alpha = cloudData[idx + 5]
            // NaN/非法值防御（与 C++ 段同语义——非法实例不画任何像素）
            if (!isValidCloud(x, y, w, h, alpha)) continue
            val src = cloudSrcRects.getOrNull(spriteIndex.toInt()) ?: continue
            // 视口剔除（世界坐标 → 帧缓冲像素；与 cropScreenRect 同风格）
            val dst = cloudScreenRect(frame, x, y, w, h, drawScale, canvas) ?: continue
            cloudPaint.alpha = (alpha * fade * 255).toInt().coerceIn(0, 255)
            canvas.drawBitmap(atlas, src, dst, cloudPaint)
        }
        cloudPaint.alpha = 255 // 防御性恢复（cloudPaint 仅本方法使用，保持惯例防未来共享）
    }

    // ============================================================
    // 帧缓冲区管理
    // ============================================================

    /**
     * 帧缓冲尺寸检查/重建。入参为目标缓冲尺寸（= round(物理视口 × renderScale)，
     * 在 renderFrame 入口计算）；尺寸变化由 renderScale/resize/旋转自动驱动。
     */
    private fun ensureFrameBuffer(vpWIn: Int, vpHIn: Int, decorSkip: Boolean) {
        val vpW = vpWIn
        val vpH = vpHIn
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
            frameBuffer = createBitmap(vpW.coerceAtLeast(1), vpH.coerceAtLeast(1), bmpConfig)
            frameCanvas = Canvas(frameBuffer ?: return)
            // resize 时清除缓存
            chunkCaches.forEach { col -> col.forEach { it.isValid = false } }
            chunkTileHash = 0
            chunkBuildingHash = 0
            chunkRoadHash = 0
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

    private fun drawPreview(canvas: Canvas, atlas: Bitmap, frame: RenderFrame, drawScale: Float) {
        val pOffX = frame.previewX - frame.camX
        val pOffY = frame.previewY - frame.camY
        val dstLeft = (pOffX * drawScale).roundToInt()
        val dstTop = (pOffY * drawScale).roundToInt()
        val dstRight = ((pOffX + frame.previewW) * drawScale).roundToInt()
        val dstBottom = ((pOffY + frame.previewH) * drawScale).roundToInt()
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

/**
 * 云层实例合法性（NaN/±Inf/非正宽高/透明度越界——与 C++ 段同语义拦截）。
 * 顶层函数（不增加 SoftwareCanvasBackend 类函数数——TooManyFunctions 守卫）。
 */
private fun isValidCloud(x: Float, y: Float, w: Float, h: Float, alpha: Float): Boolean {
    val coordsFinite = x.isFinite() && y.isFinite()
    // 两两组合拆开（ComplexCondition ≤3）：NaN 比较恒 false，`!in` 一并拦截透明度
    val sizePositive = (w.isFinite() && w > 0f) && (h.isFinite() && h > 0f)
    val alphaValid = alpha in 0f..1f
    return coordsFinite && sizePositive && alphaValid
}

/**
 * 云层屏幕矩形（视口剔除：视口外/退化尺寸 → null）。
 * 顶层函数（不增加 SoftwareCanvasBackend 类函数数——TooManyFunctions 守卫）。
 */
private fun cloudScreenRect(
    frame: RenderFrame,
    x: Float,
    y: Float,
    w: Float,
    h: Float,
    drawScale: Float,
    canvas: Canvas
): Rect? {
    val left = ((x - frame.camX) * drawScale).roundToInt()
    val top = ((y - frame.camY) * drawScale).roundToInt()
    val right = ((x + w - frame.camX) * drawScale).roundToInt()
    val bottom = ((y + h - frame.camY) * drawScale).roundToInt()
    val offRightOrLeft = right <= 0 || left >= canvas.width
    val offBottomOrTop = bottom <= 0 || top >= canvas.height
    val degenerate = right - left <= 0 || bottom - top <= 0
    val visible = !offRightOrLeft && !offBottomOrTop && !degenerate
    return if (visible) Rect(left, top, right, bottom) else null
}
