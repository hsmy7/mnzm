package com.xianxia.sect.ui.game.sect

import androidx.core.graphics.createBitmap

import android.graphics.*
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
import com.xianxia.sect.core.render.SkyColor
import com.xianxia.sect.core.render.SkyBackgroundConfig
import kotlin.math.roundToInt

/**
 * 俯视纵向压缩系数（统一俯视视角——与 BaseCameraState.worldYScale /
 * C++ TextureAtlas.h TOPDOWN_Y_SCALE 同源生成，禁止各处硬编码）。世界→屏幕的
 * Y 全部经此压缩：正交、无近大远小、网格仍为规整矩形，仅整屏 Y 轻微压缩模拟
 * 俯角（chunk 位图保持世界空间不预压，压缩发生在帧内 Y 目标矩形）。
 */
private const val TOPDOWN_Y_SCALE = SpriteAtlasDef.TOPDOWN_Y_SCALE

// 顶层常量（供顶层 sanitizeRenderScale 使用——避免类方法数超 TooManyFunctions 阈值）
private const val SOFTWARE_CANVAS_TAG = "SoftwareCanvasBackend"
private const val SOFTWARE_MIN_SCALE = 0.1f
private const val SOFTWARE_MAX_SCALE = 3.0f

/** 轮廓掩码位（地图边缘 v2；值 = GroundBoundaryBridge.MASK_BIT_*，跨语言常量） */
private const val MASK_BIT_QUAD = 1
private const val MASK_BIT_TREE = 2

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
// TooManyFunctions：SoftwareCanvasBackend 为单一职责的大型软件渲染器（单文件的渲染主干），
// 辅助逻辑（SkyBackground 渐变/精灵合成器等含成员缓存的绘制块）已下沉为独立类（SkyCanvasRenderer）
// 或顶层函数（footprintOf/sanitizeRenderScale/buildScaledRects），此处类内方法数仍达阈值——
// 为了不掩盖后续新增方法的"应提取"信号，仅对本类抑制并保留上述提取职责。
@Suppress("TooManyFunctions")
class SoftwareCanvasBackend(
    private val config: NativeRenderConfig
) {
    companion object {
        // ── Chunk 化常量 ──
        // chunk 网格维度/位图边长由 config 派生（见实例 numChunksCol/Row/chunkPixel）
        private const val CHUNK_SIZE_TILES = 32

        /** 瓦片 diff 全量失效阈值：变化格超此值（大范围地形变化）放弃局部失效 */
        private const val TILE_DIFF_FULL_INVALIDATE_THRESHOLD = 1024

        /**
         * 运行期 chunk 重建单帧预算。
         *
         * 缩放跨装饰 LOD 阈值/道路变更触发的全量失效按预算分帧：每帧最多
         * 重烘 2 块，未重建 chunk 用旧位图合成（invalidation 只清 isValid 不清
         * bitmap——stale-but-complete 渐进刷新，无纯色窗口/无冻结）。
         * 首帧构建不受预算约束（首帧必须整帧完整，由淡入遮蔽）。
         */
        private const val CHUNK_REBUILD_PER_FRAME = 2

        // ── 缩放保护常量（MIN/MAX 已上移为文件级常量，供顶层 sanitizeRenderScale 使用） ──

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

        // ── 拆除模式占地高亮（0x66 = 40% 半透明） ──
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

    // ── 渲染分辨率缩放（平板省电：帧缓冲降采样 + 上采样提交） ──

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
    /** 测试观测：chunk 重建累计次数（LOD 档内无重建防抖断言用） */
    internal var chunkRebuildCount: Int = 0
        private set

    /**
     * 是否已成功合成过至少一帧（分帧预算门控）。
     * false = 首帧构建期（重建不受预算，整帧完整后合成）；true = 运行期
     * （重建按 [CHUNK_REBUILD_PER_FRAME] 分帧，未重建 chunk 用旧位图合成）。
     */
    private var hasEverComposedFrame = false


    /** 本帧图集引用（renderFrame 入口快照——供 composeVisibleChunks 内图集类图层消费；
     *  渲染线程单消费者，无并发） */
    private var composeAtlas: Bitmap? = null
    private var composeRockBitmap: Bitmap? = null

    /** 弯曲地皮轮廓消费器（地图边缘 v2；解析/岩石带/裁切/掩码下沉独立类） */
    private val groundBoundary = SoftwareGroundBoundary(config)



    // ── Chunk 缓存 ──

    /**
     * chunk 重建共享工具集（图集源矩形 + 格尺寸——跨 chunk 不变，单次构建；
     * 精灵源矩形随 SpriteAtlasDef 静态数据生成，无 Android 依赖。
     * chunk 几何随 kit 下发——ChunkTile 为静态嵌套类不可读外类实例字段；
     * chunk 位图边长 = 32 格 × config.tileSize（必须取注入 config.tileSize，
     * 不得用全局常量替代，避免错位隐患））
     */
    private class ChunkDrawKit(
        val tileSize: Int,
        val tileSrcRects: Array<Rect>,
        val buildingSrcRects: Array<Rect>,
        val roadSrcRects: Map<String, Rect>,
        val chunkSizeTiles: Int,
        val chunkPixel: Int
    )

    /** 相机-屏幕变换（chunk 烘焙用固定 viewport = chunk 像素尺寸） */
    private data class FrameRenderState(
        val fbW: Int,
        val fbH: Int,
        val renderScale: Float,
        val tileHash: Int,
        val buildingHash: Int,
        val roadHash: Int,
        /** 上一次建筑数据引用（局部 chunk 失效 diff 基准——移动建筑拿起/放下只重建相关 chunk） */
        val prevBuildingData: FloatArray?,
        /** 上一次瓦片数据引用（局部 chunk 失效 diff 基准——拿起建筑占位格变化只重建相关 chunk） */
        val prevTileData: IntArray?
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
         * 立体层装饰收集缓冲（树——与建筑同序归并绘制）。
         *
         * 容量 = 外扩装饰遍历范围上界（本 chunk 格 + 越界余量）；
         * 收集/几何逻辑见 [ChunkObjectDecorLayer]（独立文件：控制本文件长度）。
         */
        private val objectDecor = ChunkObjectDecorLayer(
            (kit.chunkSizeTiles + 2 * SpriteAtlasDef.DECOR_MARGIN_COLS) *
                (kit.chunkSizeTiles + SpriteAtlasDef.DECOR_MARGIN_ROWS)
        )

        /**
         * 重建用 Paint（实例字段而非共享 [SoftwareCanvasBackend.paint]——
         * 未来若引入异步 chunk 重建，多线程不会竞争同一 Paint 对象）
         *
         * isFilterBitmap = **true**：chunk 烘焙降采样走双线性，与 Vulkan LINEAR
         * sampler 观感一致，消除建筑 256→96 等深降采样的点采样摩尔纹/斑点。
         * 该 paint 同时服务瓦片/装饰/建筑/道路四层，一次修改全层生效；
         * 保留 isAntiAlias/isDither=false。
         */
        private val rebuildPaint = Paint(Paint.FILTER_BITMAP_FLAG).apply {
            isFilterBitmap = true
            isAntiAlias = false
            isDither = false
        }

        /**
         * 建筑阴影 Paint（独立实例——drawShadowRect 会写入半透明黑 color
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
         * @param decorSkip 装饰层跳过判定（由 RenderLodPolicy 在 renderFrame
         * 层合并 scale/热控质量/显式关闭三条件——此处只消费最终布尔值，
         * 保证"判定一处、失效一处"，与 C++ skipDecor 双端对齐）
         */
        fun rebuild(
            frame: RenderFrame,
            atlas: Bitmap,
            groundSrc: Bitmap,
            decorSkip: Boolean,
            buildingShadows: Boolean,
            boundary: SoftwareGroundBoundary?
        ) {
            val bmp = bitmap ?: createBitmap(kit.chunkPixel, kit.chunkPixel, Bitmap.Config.RGB_565).also { bitmap = it }
            val canvas = Canvas(bmp)
            canvas.drawColor(Color.rgb(0xF2, 0xED, 0xE4))

            objectDecor.clear()
            drawGroundAndDecor(canvas, atlas, groundSrc, frame.tileData, frame.cols, decorSkip, boundary)

            // 局部值：RenderFrame 属性跨模块公开 API，smart cast 不可用
            val roadData = frame.roadData
            val buildingArray = frame.buildingData

            // 石板道路层（装饰之上、建筑之下，烘焙进 chunk——与建筑层级一致）
            if (roadData != null) {
                drawRoadsToCanvas(canvas, atlas, roadData, frame.cols)
            }

            // 绘制建筑（使用相对相机 (camX=chunk左上角, scale=1) 达到精确对齐）；
            // 立体层装饰与建筑按地面接触点归并绘制（无建筑时单独成序绘制装饰）
            val view = ViewTransform(
                camX = (col * kit.chunkSizeTiles * kit.tileSize).toFloat(),
                camY = (row * kit.chunkSizeTiles * kit.tileSize).toFloat(),
                scale = 1f,
                vpW = kit.chunkPixel,
                vpH = kit.chunkPixel
            )
            if (buildingArray != null && frame.buildingCount > 0) {
                drawBuildingsToCanvas(
                    canvas = canvas,
                    atlas = atlas,
                    buildingArray = buildingArray,
                    buildingCount = frame.buildingCount,
                    buildingShadows = buildingShadows,
                    view = view,
                    reuseRect = Rect()
                )
            } else {
                drawRemainingObjectDecor(canvas, atlas, view, Rect())
            }

            isValid = true
        }

        /** 归并结束后绘制剩余立体层装饰（无建筑/建筑全被剔除时的收尾） */
        private fun drawRemainingObjectDecor(
            canvas: Canvas,
            atlas: Bitmap,
            view: ViewTransform,
            reuseRect: Rect,
            from: Int = 0
        ) {
            for (i in from until objectDecor.count) {
                drawObjectDecorItem(canvas, atlas, i, view, reuseRect)
            }
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
            decorSkip: Boolean,
            boundary: SoftwareGroundBoundary?
        ) {
            val rows = tileData.size / cols
            val startCol = col * kit.chunkSizeTiles
            val startRow = row * kit.chunkSizeTiles
            val endCol = (startCol + kit.chunkSizeTiles).coerceAtMost(cols)
            val endRow = (startRow + kit.chunkSizeTiles).coerceAtMost(rows)
            if (endCol > startCol && endRow > startRow) {
                drawGroundFill(canvas, groundSrc, startCol, startRow, endCol, endRow)
            }
            // 装饰遍历范围**外扩**（越界余量 = SpriteAtlasDef.DECOR_MARGIN_COLS/ROWS）：
            // 装饰精灵底边居中锚定在自己格上、树冠向上伸出 2.29 格——若只遍历本 chunk 的格，
            // 相邻 chunk 不会绘制这些越界段，树冠会在 chunk 缝处被整块裁掉（每 chunk 只保留
            // 落在自己位图内的像素，故外扩遍历不会重复绘制）。
            val decoStartCol = (startCol - SpriteAtlasDef.DECOR_MARGIN_COLS).coerceAtLeast(0)
            val decoEndCol = (endCol + SpriteAtlasDef.DECOR_MARGIN_COLS).coerceAtMost(cols)
            val decoEndRow = (endRow + SpriteAtlasDef.DECOR_MARGIN_ROWS).coerceAtMost(rows)
            for (r in startRow until decoEndRow) {
                drawGroundRow(
                    canvas, atlas, tileData, cols, decorSkip,
                    GroundRowRange(r, startRow, decoStartCol, decoEndCol), boundary
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
         * 地面已由 A0 整图铺覆盖，此处绘制草/石/树装饰：
         * - 地面层（草/石）：即时绘制（越界 ≤0.42 格，被后绘建筑压住是预期观感）
         * - 立体层（树）：收集进 [objectDecor]，由建筑层按地面接触点归并绘制
         *   （与 C++ drawAllTiles 同序；契约见 gamecore/map/draw_order.h）
         */
        private fun drawGroundRow(
            canvas: Canvas,
            atlas: Bitmap,
            tileData: IntArray,
            cols: Int,
            decorSkip: Boolean,
            range: GroundRowRange,
            boundary: SoftwareGroundBoundary?
        ) {
            val rowBase = range.r * cols
            val reuseRect = Rect()
            val tileSize = kit.tileSize
            for (c in range.startCol until range.endCol) {
                val tile = tileData[rowBase + c]

                // A2: 装饰叠加（草/石/树——显示尺寸/绘制层取自 SpriteAtlasDef 生成
                // 常量，与 C++ drawAllTiles 的 TILE_SPRITE_W/H + TILE_OBJECT_LAYER 同源；
                // 锚点 = 格底边居中：x = 格左 + (格宽−显示宽)/2，y = 格底 − 显示高）
                // 地图边缘 v2：掩码门控（bit0 = 平铺装饰，格四角须在轮廓内；
                // bit1 = 树，锚点须在轮廓内）——边界带装饰不越过平滑轮廓
                val decorEligible = !decorSkip && SpriteAtlasDef.isDecorTile(tile)
                val decorSrc = if (decorEligible) kit.tileSrcRects.getOrNull(tile) else null
                if (decorSrc == null) continue
                val mask = boundary?.maskAt(range.r, c) ?: 3
                val allowFlat = mask and MASK_BIT_QUAD != 0
                val allowTree = mask and MASK_BIT_TREE != 0
                val decoW = (SpriteAtlasDef.tileSpriteWidth(tile) * tileSize).roundToInt()
                val decoH = (SpriteAtlasDef.tileSpriteHeight(tile) * tileSize).roundToInt()
                val worldLeft = c * tileSize + (tileSize - decoW) / 2
                val worldTop = (range.r + 1) * tileSize - decoH
                if (SpriteAtlasDef.isObjectDecorTile(tile)) {
                    if (allowTree) objectDecor.collect(worldLeft, worldTop, decoW, decoH, tile)
                } else if (allowFlat) {
                    val offX = worldLeft - range.startCol * tileSize
                    val offY = worldTop - range.startRow * tileSize
                    reuseRect.set(offX, offY, offX + decoW, offY + decoH)
                    drawAtlasSprite(canvas, atlas, decorSrc, reuseRect)
                }
            }
        }

        /** 立体层装饰单格绘制（世界坐标 → chunk 位图坐标；几何见 [ChunkObjectDecorLayer]） */
        private fun drawObjectDecorItem(
            canvas: Canvas,
            atlas: Bitmap,
            index: Int,
            view: ViewTransform,
            reuseRect: Rect
        ) {
            val src = kit.tileSrcRects.getOrNull(objectDecor.tileAt(index)) ?: return
            objectDecor.dstRect(index, view.camX, view.camY, view.scale, reuseRect)
            drawAtlasSprite(canvas, atlas, src, reuseRect)
        }

        /**
         * 石板道路层（装饰之上、建筑之下，烘焙进 chunk）。
         *
         * 逐格合成操作序列由 C++ 单一权威 `gamecore/map/road_compositor.h`
         * 产出（[RoadCompositorBridge.compose]：主体→边缘条，直路按方向出侧边缘、
         * T 中心单侧、转角两开放侧且在格内、十字中心无边缘、格内局部整型几何）——
         * 本方法仅做数据装配：RoadSprite 枚举序 → 图集精灵名 → 源矩形，按序绘制
         * （合成器物理下沉）。并行道路内部不重复描边由合成器
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
            val startCol = col * kit.chunkSizeTiles
            val startRow = row * kit.chunkSizeTiles
            val endCol = (startCol + kit.chunkSizeTiles).coerceAtMost(cols)
            val endRow = (startRow + kit.chunkSizeTiles).coerceAtMost(rows)
            val tileSize = kit.tileSize
            val reuseRect = Rect()

            for (r in startRow until endRow) {
                for (c in startCol until endCol) {
                    val idx = r * cols + c
                    // 数组为 1-based（0=非道路，1=单格道路原掩码 0，
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
                        // flip = 末位字段（2.4 道路边缘条 UV 翻转：左/上缘深色边朝外）
                        val flip = ops[i + 5] != 0
                        reuseRect.set(
                            opX, opY,
                            opX + ops[i + 3], opY + ops[i + 4]
                        )
                        drawRoadSprite(canvas, atlas, key, reuseRect, flip)
                        i += RoadCompositorBridge.OP_STRIDE
                    }
                }
            }
        }

        /** 绘制单个道路精灵（源矩形由 [kit] 查询，目标矩形 [dst] 已由调用方装配）。
         *
         * [flipU]（2.4）：合成器对左/上缘条产出 flip=true——深色描边边在纹理
         * 右/下侧，左/上缘须水平镜像使深色边朝外（v 不变）。
         */
        private fun drawRoadSprite(
            canvas: Canvas,
            atlas: Bitmap,
            key: String,
            dst: Rect,
            flipU: Boolean
        ) {
            val src = kit.roadSrcRects[key] ?: return
            drawAtlasSprite(canvas, atlas, src, dst, flipU)
        }

        /**
         * Chunk 烘焙精灵统一绘制。
         *
         * 1. **链式预降采样**：源矩形宽高 > 目标 2 倍（深度降采样场景）时，先从图集
         *    裁出源区，经 [SectAtlasAssembler.downscaleWithBilinearChain]（同模块
         *    internal 复用，单一实现）逐级 2:1 双线性预降采样（软件 mip 近似，抑制
         *    一步到位降采样的摩尔纹），再绘制到目标矩形。生产图集的精灵内容已由
         *    `scripts/atlas-offline-rgba.mjs` 在构建期按槽位尺寸降采样（B15 / R6.1），
         *    本分支为 **LOD 跨级深缩放兜底**（放远时目标 < 源 1/2）；中间位图生命
         *    周期随绘制结束即废（普通局部变量，GC 回收，不 recycle——遵循既有
         *    double-free 规避惯例）。
         * 2. **flipU 水平镜像**：绕目标矩形中心 scale(-1,1) 后按原源矩形绘制——
         *    等价于交换源矩形左右（v 不变），采用矩阵实现保证跨 Skia 实现确定性
         *    （逆序 src 矩形行为未定义）。
         */
        private fun drawAtlasSprite(
            canvas: Canvas,
            atlas: Bitmap,
            src: Rect,
            dst: Rect,
            flipU: Boolean = false
        ) {
            if (flipU) {
                canvas.save()
                canvas.scale(-1f, 1f, dst.exactCenterX(), dst.exactCenterY())
                drawPreScaled(canvas, atlas, src, dst)
                canvas.restore()
            } else {
                drawPreScaled(canvas, atlas, src, dst)
            }
        }

        /** [drawAtlasSprite] 的绘制主体：深降采样预链（>2:1×）+ 双线性 paint 绘制。 */
        private fun drawPreScaled(canvas: Canvas, atlas: Bitmap, src: Rect, dst: Rect) {
            if (src.width() > dst.width() * 2 || src.height() > dst.height() * 2) {
                val pre = SectAtlasAssembler.downscaleWithBilinearChain(
                    Bitmap.createBitmap(atlas, src.left, src.top, src.width(), src.height()),
                    dst.width(), dst.height()
                )
                canvas.drawBitmap(pre, null, dst, rebuildPaint)
            } else {
                canvas.drawBitmap(atlas, src, dst, rebuildPaint)
            }
        }

        /** 视锥剔除（屏幕矩形与 chunk 视口相交判定——4 条件拆两半规避复杂条件） */
        private fun isOffScreen(left: Int, top: Int, right: Int, bottom: Int, view: ViewTransform): Boolean {
            val pastRightOrBottom = left >= view.vpW || bottom <= 0
            val beforeLeftOrTop = right <= 0 || top >= view.vpH
            return pastRightOrBottom || beforeLeftOrTop
        }

        /**
         * 工具方法：绘制建筑列表到 chunk 位图（阴影 → 精灵），并与**立体层装饰**
         * 按地面接触点归并绘制。
         *
         * 归并契约 = C++ `gamecore/map/draw_order.h` mergeObjectLayerOrder：
         * 两组各自已按底边 Y 升序（建筑数组由 Kotlin buildBuildingDataArray Y-sorting
         * 保证；装饰按行序收集），归并时**同键装饰在先、建筑覆盖**
         * （否则北侧建筑会把前置的树冠无脑压掉——层序错误）。
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
            var decorIndex = 0
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

                // 归并：先绘制地面接触点在本建筑之前（含同键）的立体层装饰
                val buildingBottomY = (gy + fpH) * tileSize.toFloat()
                while (decorIndex < objectDecor.count &&
                    objectDecor.bottomY(decorIndex) <= buildingBottomY
                ) {
                    drawObjectDecorItem(canvas, atlas, decorIndex, view, reuseRect)
                    decorIndex++
                }

                // 视锥剔除（提取纯函数，主循环复杂度收敛）
                if (isOffScreen(bDstLeft, bDstTop, bDstRight, bDstBottom, view)) continue

                // 建筑投影阴影（精灵之下；与 C++ drawAllTiles 同数学）
                // 固定结构（门楼/阶梯）不投影——避免阴影压到阶梯/地图底边外
                if (buildingShadows && !isStructure) {
                    val offset = tileSize * BuildingRenderGeometry.SHADOW_OFFSET_TILES
                    drawShadowRect(canvas, shadowPaint,
                        ((gx * tileSize + offset - view.camX) * view.scale).roundToInt(),
                        ((gy * tileSize + offset - view.camY) * view.scale).roundToInt(),
                        (((gx + fpW) * tileSize + offset - view.camX) * view.scale).roundToInt(),
                        (((gy + fpH) * tileSize + offset - view.camY) * view.scale).roundToInt())
                }

                val srcRect = kit.buildingSrcRects.getOrNull(nameIdx)
                if (srcRect != null) {
                    reuseRect.set(bDstLeft, bDstTop, bDstRight, bDstBottom)
                    drawAtlasSprite(canvas, atlas, srcRect, reuseRect)
                }
            }
            // 剩余立体层装饰（底边 Y 大于所有建筑——在最后绘制，最靠近观察者）
            drawRemainingObjectDecor(canvas, atlas, view, reuseRect, decorIndex)
        }
    }

    // ── chunk 网格几何（config 派生，构造期常量）──
    // chunk 网格维度与位图边长只依赖注入 config（不得读全局常量，避免与
    // 注入 tileSize 不一致的错位）
    private val numChunksCol =
        (config.worldWidthCells + CHUNK_SIZE_TILES - 1) / CHUNK_SIZE_TILES
    private val numChunksRow =
        (config.worldHeightCells + CHUNK_SIZE_TILES - 1) / CHUNK_SIZE_TILES
    private val chunkPixel = CHUNK_SIZE_TILES * config.tileSize

    /**
     * chunk 重建共享工具集（图集源矩形 + 格尺寸——跨 chunk 不变，单次构建；
     * 精灵源矩形随 SpriteAtlasDef 静态数据生成，无 Android 依赖）。
     * chunk 几何随 kit 下发（见上方 ChunkDrawKit——ChunkTile 为静态嵌套类
     * 不可读外类实例字段）
     */
    private val chunkKit: ChunkDrawKit by lazy {
        ChunkDrawKit(
            config.tileSize, tileSrcRects, buildingSrcRects, roadSrcRects,
            CHUNK_SIZE_TILES, chunkPixel
        )
    }

    /**
     * chunk 缓存网格。**必须 lazy**：构造期若立即构建会触发 chunkKit → tileSrcRects
     * 的 lazy 委托链，而 tileSrcRects 声明在下方（delegate 字段按声明顺序初始化，
     * 构造到 chunkCaches 时仍为 null → NPE）。延迟到首次渲染（构造已完成）再构建。
     */
    private val chunkCaches: Array<Array<ChunkTile>> by lazy {
        Array(numChunksCol) { col ->
            Array(numChunksRow) { row ->
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
        // 图集建筑槽位 128→256 后放大仍可达 1.5x：双线性过滤消除 NEAREST 颗粒感，
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

    /** 放置/移动模式占地框（预览框）Paint（独立实例——逐帧改颜色不得污染共享 paint，仿 demolishPaint 惯例） */
    private val previewBoxPaint = Paint().apply {
        isAntiAlias = true
    }

    /** 灵田作物 Paint（独立实例——逐帧改 alpha 不得污染共享 paint） */
    private val cropPaint = Paint(Paint.FILTER_BITMAP_FLAG).apply {
        isFilterBitmap = false
        isAntiAlias = false
        isDither = false
    }

    // ── SkyBackground 屏幕空间渐变绘制助手（缓存 Paint，配置/尺寸变化才重建） ──
    private val skyRenderer = SkyCanvasRenderer()
    /** 本帧天空配置（renderFrame 设置，composeVisibleChunks 读取——避免 LongParameterList） */
    private var currentSkyConfig: SkyBackgroundConfig = SkyBackgroundConfig.DEFAULT

    /** 云层 Paint（独立实例——逐帧改 alpha 不得污染共享 paint，仿 cropPaint 惯例） */
    private val cloudPaint = Paint(Paint.FILTER_BITMAP_FLAG).apply {
        isFilterBitmap = false
        isAntiAlias = false
        isDither = false
    }

    /** 上一帧作物原始进度（key=gx/gy 编码，见 [cropProgressKey]） */
    private val lastCropProgress = HashMap<Long, Float>()

    /**
     * 作物层本帧活跃 key 集合（成员复用——拖动视角时逐帧 clear 复用，
     * 消除每帧 HashSet 分配导致的 GC 抖动；渲染线程独占，无需同步）。
     */
    private val cropActiveKeys = HashSet<Long>()

    /**
     * 预渲染地面源位图缓存：`Bitmap.createBitmap(atlas, GROUND.rect)` 的**单一共享副本**——
     * 避免每次 chunk 重建都从图集复制一份。atlas 引用变化才重建。
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
     * 离线图集产物（B15 / R6.1 起由 scripts/atlas-offline-rgba.mjs 产出）为防低端机
     * OOM 把 4096 图集封顶缩放到 2048（scale = 2048/4096 = 0.5），此封顶语义与 B15 前
     * 的 SectAtlasAssembler.buildAtlasBitmap 一致（ATLAS_BITMAP_MAX_EDGE / ATLAS_W），但
     * SpriteAtlasDef 的图集源矩形仍是 4096 坐标系。若直接用 4096 坐标在 2048 位图上
     * 采样，会越界/取样到相邻槽位——地面 REPEAT 源裁出「绿块+透明」导致整图铺出米色
     * 空隙，装饰/建筑错位。故所有源矩形与地面源裁切必须按实际图集宽度缩放。
     *
     * 值由渲染帧传入的 atlas 实际宽度推导（atlas.width / SpriteAtlasDef.ATLAS_W），
     * 生产图集恒正方形（2048/4096=0.5），测试用任意宽位图亦自动适配。
     * B15 起像素源改为离线产物，**封顶语义与缩放系数零变化**（产物恒 2048²）。
     */
    private var sourceScale: Float = 1f

    private val tileSrcRects: Array<Rect> by lazy {
        @Suppress("UNCHECKED_CAST")
        buildScaledRects(sourceScale, SpriteAtlasDef.TileType.values().toList()) { it.rect } as Array<Rect>
    }

    /** 灵田作物三阶段图源矩形（与 C++ TextureAtlas.h crop_* 同步） */
    private val cropSrcRects: Array<Rect> by lazy {
        @Suppress("UNCHECKED_CAST")
        buildScaledRects(sourceScale, SpriteAtlasDef.CropStage.values().toList()) { it.rect } as Array<Rect>
    }

    /** 云层精灵图源矩形（按 SpriteAtlasDef.CLOUD_RECTS 声明顺序，与 C++ CLOUD_UV_MAP 同源） */
    private val cloudSrcRects: Array<Rect> by lazy {
        @Suppress("UNCHECKED_CAST")
        buildScaledRects(sourceScale, SpriteAtlasDef.CLOUD_RECTS) { it.second } as Array<Rect>
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
     * @param fadeAlpha 地图淡入 alpha（0-1，默认 1 不透明）——作用于 chunk
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
        cloudData: FloatArray? = null,
        skyConfig: SkyBackgroundConfig = SkyBackgroundConfig.DEFAULT,
        rockBitmap: Bitmap? = null
    ): Bitmap? {
        // 源矩形坐标缩放比：软件路径图集（B15 起为离线产物，此前由 SectAtlasAssembler
        // 拼装）按 0.5× 缩到 2048，而 SpriteAtlasDef 源矩形是 4096 坐标系——
        // 直接采样会越界/取到相邻槽位，
        // 导致地面 REPEAT 源裁出「绿块+透明」铺出米色空隙、装饰/建筑错位。
        // 渲染帧实际 atlas 宽度推导（图集恒正方形，生产 2048/4096=0.5；测试任意宽亦适配）。
        val aw = atlas.width
        sourceScale = if (aw > 0) aw.toFloat() / SpriteAtlasDef.ATLAS_W else 1f

        // 装饰层 LOD 最终判定：scale/热控/显式关闭三条件收敛于
        // RenderLodPolicy 纯函数（与 C++ skipDecor 同阈值双端对齐）。
        // 在 ensureFrameBuffer 前计算——帧缓冲重建时按合并值重置 chunk 失效基准，
        // 防"此处比较一处、别处判断另一处"的漂移
        val decorSkip = computeDecorSkip(frame)

        // render scale：物理视口 → 降采样帧缓冲尺寸（含
        // resizeRequested 消费，见 [prepareFrameRenderState]）
        val frameState = prepareFrameRenderState(vpW, vpH, frame)
        val fbW = frameState.fbW
        val fbH = frameState.fbH
        ensureFrameBuffer(fbW, fbH, decorSkip)
        val canvas = frameCanvas
        val fb = frameBuffer
        val scale = sanitizeRenderScale(frame)
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

        // 弯曲地皮轮廓（地图边缘 v2）：帧数据引用变化才重建缓存并整体失效 chunk
        //（新轮廓改变 clip 与装饰掩码——所有 chunk 必须重烘）
        if (groundBoundary.update(frame.groundBoundaryData) && hasEverComposedFrame) {
            invalidateAllChunks()
        }

        // Chunk 失效检查（装饰判定用 LOD 合并值——档位内浮点微动不触发重建防抖动）
        invalidateChunksForChanges(
            ChunkInvalidationInput(
                tileHash, buildingHash, roadHash, decorSkip,
                frame.buildingData, frameState.prevBuildingData,
                frame.tileData, frameState.prevTileData
            )
        )

        // chunk 重建分帧预算——失效帧内不重烘全部失效 chunk（缩放跨 LOD 阈值/
        //   道路变更 = 16 块全烘会冻结渲染线程约 1 秒）。
        //   首帧构建保持全量同步（首帧必须整帧完整）；运行期按预算分帧，未重建
        //   chunk 沿用旧位图合成（渐进刷新）。每帧扫描 16 个布尔位开销可忽略。
        rebuildInvalidChunks(
            atlas, frame, decorSkip,
            budget = if (hasEverComposedFrame) CHUNK_REBUILD_PER_FRAME else null
        )

        // 本帧天空配置（composeVisibleChunks 读取——避免长参数表）
        currentSkyConfig = skyConfig

        // 本帧图集引用快照（composeVisibleChunks 内图集类图层消费；渲染线程单消费者）
        composeAtlas = atlas

        // 本帧崖壁独立纹理快照（同图集纪律：帧首取一次，绘制期只读）
        // 本帧底部岩石位图快照（地图边缘 v2 软渲染材质；null = 岩石带整层跳过）
        composeRockBitmap = rockBitmap

        // 底部岩石带（z 序：天空 → 底部岩石 → 地皮 chunk——带顶边藏进草皮之下，
        // 与 GPU 路径的「岩石先绘、草皮后绘覆盖」同构）
        groundBoundary.drawBand(canvas, frame.camX, frame.camY, drawScale, fadeAlpha,
            composeRockBitmap, TOPDOWN_Y_SCALE)

        // 合成可见 chunk → 灵田作物层 → 云层 → 选中高亮 → 拆除高亮 → 预览精灵 → 网格线
        composeVisibleChunks(canvas, frame, tileSize, drawScale, fbW, fbH, fadeAlpha)
        // 分帧预算门控：本帧已完整合成——后续运行期重建启用分帧预算
        hasEverComposedFrame = true
        drawCrops(canvas, atlas, frame, fadeAlpha, drawScale)
        drawClouds(canvas, atlas, frame, cloudData, decorSkip, fadeAlpha, drawScale)
        if (config.renderFlags.selectionHighlight) {
            drawSelectionHighlight(canvas, frame, drawScale)
        }
        drawDemolishHighlight(canvas, frame, drawScale)
        if (frame.showPreview) {
            // 绘制顺序：精灵先画、占地框（填充+描边）后画——填充绿纱罩于
            //   精灵之上（标准放置 UI），与 Vulkan 路径顺序一致
            drawPreview(canvas, atlas, frame, drawScale)
            if (frame.previewBoxVisible) {
                drawPreviewHighlight(canvas, frame, drawScale, config.tileSize, previewBoxPaint)
            }
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
        // 局部失效 diff 基准：保存上一次引用后再更新缓存——tile/building 变化
        //   仅失效相关 chunk（全量重烘会冻结渲染线程约 1 秒）
        val prevTileData = cachedTileData
        val tileHash = if (td === cachedTileData) chunkTileHash else td.contentHashCode().also { cachedTileData = td }
        val prevBuildingData = cachedBuildingData
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
            roadHash = roadHash,
            prevBuildingData = prevBuildingData,
            prevTileData = prevTileData
        )
    }

    /**
     * 装饰层 LOD 最终判定。
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
    // sanitizeScale 已下沉为顶层 sanitizeRenderScale（减少类方法数——TooManyFunctions 守卫）

    /** 全部 chunk 失效（瓦片/装饰/建筑清空路径统一入口） */
    private fun invalidateAllChunks() {
        for (col in 0 until numChunksCol) {
            for (row in 0 until numChunksRow) {
                chunkCaches[col][row].isValid = false
            }
        }
    }

    /**
     * Chunk 失效检查（装饰判定用 LOD 合并值——档位内浮点微动不触发重建防抖动）。
     *
     * @param input 失效判定输入（分组长参数表——LongParameterList 守卫）
     * @return 是否有 chunk 需要重建
     */
    private fun invalidateChunksForChanges(input: ChunkInvalidationInput): Boolean {
        val chunkTileChanged = input.tileHash != chunkTileHash
        val chunkBuildingChanged = input.buildingHash != chunkBuildingHash
        val chunkRoadChanged = input.roadHash != chunkRoadHash
        val chunkDecorChanged = input.decorSkip != lastDecorationsDisabled

        if (chunkDecorChanged) {
            lastDecorationsDisabled = input.decorSkip
        }
        if (chunkTileChanged || chunkDecorChanged) {
            // 瓦片变化不做全量重建（拿起建筑占位格清除 → 全重建约 1 秒）——
            //   diff 变化格仅失效相关 chunk；变化格超阈值（大范围地形变化）才全失效
            if (chunkDecorChanged) {
                invalidateAllChunks()
            } else {
                invalidateChunksForTileDiff(input.prevTileData, input.tileData)
            }
            if (chunkTileChanged) chunkTileHash = input.tileHash
        }
        if (chunkBuildingChanged) {
            chunkBuildingHash = input.buildingHash
            // 建筑数据变化不做全量失效（移动建筑拿起/放下各触发一次全量 CPU
            //   重绘，方格/预览会迟到约 1 秒）——局部失效：仅重建旧/新建筑位置
            //   涉及的 chunk。空数组（进入无建筑宗门）仍全失效防残留
            if (input.buildingArray == null || input.buildingArray.isEmpty()) {
                invalidateAllChunks()
            } else {
                invalidateChunksForBuildings(input.prevBuildingData)  // 旧位置（拿起/移动离开）
                invalidateChunksForBuildings(input.buildingArray)     // 新位置（放下/新建）
            }
        }
        if (chunkRoadChanged) {
            // 道路变化：失效全部 chunk（道路可能横跨多个 chunk，局部失效受 32×32 网格限制，
            // 全失效 16 块重建成本低——道路放置/删除低频，满足"只更新受影响区域"）。
            chunkRoadHash = input.roadHash
            invalidateAllChunks()
        }
        return chunkTileChanged || chunkBuildingChanged || chunkRoadChanged || chunkDecorChanged
    }

    /** 失效建筑（含精灵尺寸范围，保守跨 chunk）覆盖的 chunk——建筑变化局部重建 */
    private fun invalidateChunksForBuildings(buildings: FloatArray?) {
        if (buildings == null || buildings.isEmpty()) return
        var i = 0
        while (i + 4 < buildings.size) {
            val gx = buildings[i].toInt().coerceAtLeast(0)
            val gy = buildings[i + 1].toInt().coerceAtLeast(0)
            val spanW = buildings[i + 2].toInt().coerceAtLeast(1)
            val spanH = buildings[i + 3].toInt().coerceAtLeast(1)
            val minCol = (gx / CHUNK_SIZE_TILES).coerceIn(0, numChunksCol - 1)
            val maxCol = ((gx + spanW) / CHUNK_SIZE_TILES).coerceIn(0, numChunksCol - 1)
            val minRow = (gy / CHUNK_SIZE_TILES).coerceIn(0, numChunksRow - 1)
            val maxRow = ((gy + spanH) / CHUNK_SIZE_TILES).coerceIn(0, numChunksRow - 1)
            for (c in minCol..maxCol) {
                for (r in minRow..maxRow) {
                    chunkCaches[c][r].isValid = false
                }
            }
            i += 5
        }
    }

    /**
     * 瓦片变化局部失效：diff 新旧数组找出变化格 → 仅失效覆盖的 chunk；
     * 变化格超 [TILE_DIFF_FULL_INVALIDATE_THRESHOLD]（大范围地形变化）或
     * 数组不兼容 → 全量失效。
     */
    private fun invalidateChunksForTileDiff(prev: IntArray?, cur: IntArray) {
        if (prev == null || prev.size != cur.size) {
            invalidateAllChunks()
            return
        }
        val cols = config.worldWidthCells
        var changed = 0
        for (i in cur.indices) {
            if (prev[i] == cur[i]) continue
            changed++
            if (changed > TILE_DIFF_FULL_INVALIDATE_THRESHOLD) {
                invalidateAllChunks()
                return
            }
            val c = (i % cols) / CHUNK_SIZE_TILES
            val r = (i / cols) / CHUNK_SIZE_TILES
            if (c in 0 until numChunksCol && r in 0 until numChunksRow) {
                chunkCaches[c][r].isValid = false
            }
        }
    }

    /**
     * 重建失效 chunk（失效检查完成后执行，防半失效窗口）。
     *
     * 支持分帧预算——[budget] = 单帧最多重烘块数；
     * null = 不限（首帧构建，必须整帧完整）。未重建的 chunk 保持 isValid=false
     * 但**旧 bitmap 仍在**，合成层照常绘制旧内容（stale-but-complete）——
     * 缩放跨 LOD 阈值等全量失效场景逐帧渐进刷新，不冻结渲染线程。
     * 调用方每帧调用（无失效时仅 16 次布尔扫描，开销可忽略）。
     *
     * @return 重建后仍处于失效状态的 chunk 数（0 = 全部就绪）
     */
    private fun rebuildInvalidChunks(
        atlas: Bitmap,
        frame: RenderFrame,
        decorSkip: Boolean,
        budget: Int? = null
    ): Int {
        // 单一地面源位图（跨 chunk 共享；atlas 引用变化才重复制——避免每 chunk 各复制一次）
        // 按 sourceScale 缩放 GROUND 源矩形：软件路径图集是 4096 槽位的 0.5× 位图，
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
        var rebuilt = 0
        var remaining = 0
        for (col in 0 until numChunksCol) {
            for (row in 0 until numChunksRow) {
                val chunk = chunkCaches[col][row]
                if (chunk.isValid) continue
                // 预算耗尽：剩余失效块计入 remaining，留给后续帧继续（stale 合成）
                val hasBudget = budget == null || rebuilt < budget
                if (hasBudget) {
                    chunkRebuildCount++
                    chunk.rebuild(
                        frame = frame,
                        atlas = atlas,
                        groundSrc = groundSrc,
                        decorSkip = decorSkip,
                        buildingShadows = config.renderFlags.buildingShadows,
                        boundary = groundBoundary
                    )
                    rebuilt++
                } else {
                    remaining++
                }
            }
        }
        return remaining
    }

    /**
     * 合成可见 chunk 到帧缓冲区。
     *
     * 以首个可见 Chunk 为基准计算屏幕位置，后续 Chunk 递推——独立计算每个
     * Chunk 时 roundToInt 可能产生 ±1px 偏差，导致相邻 Chunk 之间出现
     * 1px 背景色裂缝（白线闪烁）。
     *
     * 地图淡入：fadeAlpha 作用于共享 [paint].alpha（每帧设回 255——
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
        // Y 可视范围按俯视压缩系数扩大（与投影矩阵可见带严格一致）
        val viewBottom = frame.camY + fbH / (drawScale * TOPDOWN_Y_SCALE)

        val firstChunkCol = ((viewLeft / tileSize) / CHUNK_SIZE_TILES).toInt()
            .coerceIn(0, numChunksCol - 1)
        val firstChunkRow = ((viewTop / tileSize) / CHUNK_SIZE_TILES).toInt()
            .coerceIn(0, numChunksRow - 1)
        val lastChunkCol = ((viewRight / tileSize) / CHUNK_SIZE_TILES).toInt()
            .coerceIn(0, numChunksCol - 1)
        val lastChunkRow = ((viewBottom / tileSize) / CHUNK_SIZE_TILES).toInt()
            .coerceIn(0, numChunksRow - 1)

        // SkyBackground 屏幕空间渐变背景（最底图层——在 chunk 绘制之前、纯屏幕/帧缓冲
        // 坐标，不受相机平移缩放影响；以全帧矩形绘制，无黑边/透明/未覆盖区）。
        canvas.drawRect(0f, 0f, fbW.toFloat(), fbH.toFloat(), skyRenderer.paintFor(currentSkyConfig, fbH))
        paint.alpha = (fadeAlpha.coerceIn(0f, 1f) * 255).toInt()
        val reuseRect = Rect()
        val firstChunkWorldX = (firstChunkCol * CHUNK_SIZE_TILES * tileSize).toFloat()
        val firstChunkWorldY = (firstChunkRow * CHUNK_SIZE_TILES * tileSize).toFloat()
        val baseScreenX = ((firstChunkWorldX - frame.camX) * drawScale).roundToInt()
        val baseScreenY = ((firstChunkWorldY - frame.camY) * drawScale * TOPDOWN_Y_SCALE).roundToInt()
        val scaledW = (chunkPixel * drawScale).roundToInt().coerceAtLeast(1)
        val scaledH = (chunkPixel * drawScale * TOPDOWN_Y_SCALE).roundToInt().coerceAtLeast(1)
        // 弯曲地皮轮廓（地图边缘 v2）：chunk 位图仍是**矩形不透明**烘焙——轮廓外的
        // chunk 像素（曲线内缩露出的天空侧）在合成期按轮廓 clip 掉。仅边缘带 chunk
        // 需要 clip（完全落在内缩安全带的 chunk 恒在轮廓内，免逐帧路径裁切）。
        val screenBoundaryClip = groundBoundary.screenClipPath(
            frame.camX, frame.camY, drawScale, TOPDOWN_Y_SCALE
        )
        for (chunkCol in firstChunkCol..lastChunkCol) {
            for (chunkRow in firstChunkRow..lastChunkRow) {
                val chunk = chunkCaches[chunkCol][chunkRow]
                val screenX = baseScreenX + (chunkCol - firstChunkCol) * scaledW
                val screenY = baseScreenY + (chunkRow - firstChunkRow) * scaledH
                // 越界判定拆两半（detekt ComplexCondition ≤4）；越界或未烘焙块跳过
                val offLeftOrTop = screenX + scaledW < 0 || screenY + scaledH < 0
                val offRightOrBottom = screenX > fbW || screenY > fbH
                val chunkBmp = if (offLeftOrTop || offRightOrBottom) null else chunk.bitmap
                if (chunkBmp == null) continue
                // 边缘带 chunk：先按轮廓裁切再合成（内部安全带 chunk 免 clip）
                val fullyInsideSafeBand =
                    groundBoundary.chunkInsideSafeBand(chunkCol, chunkRow, CHUNK_SIZE_TILES, chunkPixel)
                val needClip = screenBoundaryClip != null && !fullyInsideSafeBand
                if (needClip) {
                    canvas.save()
                    canvas.clipPath(screenBoundaryClip)
                }
                reuseRect.set(screenX, screenY, screenX + scaledW, screenY + scaledH)
                canvas.drawBitmap(chunkBmp, null, reuseRect, paint)
                if (needClip) canvas.restore()
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
        val top = ((gy * tileSize - frame.camY) * drawScale * TOPDOWN_Y_SCALE).roundToInt()
        val right = (((gx + fpW) * tileSize - frame.camX) * drawScale).roundToInt()
        val bottom = (((gy + fpH) * tileSize - frame.camY) * drawScale * TOPDOWN_Y_SCALE).roundToInt()
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
        val top = ((gy * tileSize - frame.camY) * drawScale * TOPDOWN_Y_SCALE).roundToInt()
        val right = (((gx + fpW) * tileSize - frame.camX) * drawScale).roundToInt()
        val bottom = (((gy + fpH) * tileSize - frame.camY) * drawScale * TOPDOWN_Y_SCALE).roundToInt()
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
        val lastRow = ((frame.camY + fbH / (drawScale * TOPDOWN_Y_SCALE)) / tileSize).toInt()
            .coerceAtMost(frame.rows)

        gridPaint.color = GRID_OVERLAY_COLOR
        for (col in firstCol..lastCol) {
            val sx = (col * tileSize - frame.camX) * drawScale
            canvas.drawLine(sx, 0f, sx, fbH.toFloat(), gridPaint)
        }
        for (row in firstRow..lastRow) {
            val sy = (row * tileSize - frame.camY) * drawScale * TOPDOWN_Y_SCALE
            canvas.drawLine(0f, sy, fbW.toFloat(), sy, gridPaint)
        }
    }

    // ============================================================
    // 灵田作物层绘制
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
            // NaN/Inf/越界防御（isFinite 与 C++ `progress != progress` 防御同语义）
            // + 视口剔除 + gx/gy 合法性：NaN 坐标经 roundToInt 收敛到 (0,0) 漂浮 +
            // key 碰撞串扰——与 C++ 侧 gx != gx 防御同语义，收敛于
            // cropScreenRect 单一出口
            val rect = if (progress.isFinite() && progress in 0f..1f) {
                cropScreenRect(frame, idx, drawScale, canvas.width, canvas.height)
            } else null
            if (rect == null) continue

            // 帧间插值：draw = prev + (cur - prev) × frameAlpha——
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
        // NaN/Inf 坐标防御：
        // 非法坐标既不参与绘制也不进入插值状态表（共享防御入口 SpiritCropRender）
        val coordValid = cropData != null &&
            SpiritCropRender.isValidCropCoord(cropData[idx]) &&
            SpiritCropRender.isValidCropCoord(cropData[idx + 1])
        if (!coordValid) return null
        val gx = cropData[idx]
        val gy = cropData[idx + 1]
        val tileSize = config.tileSize
        val left = ((gx * tileSize - frame.camX) * drawScale).roundToInt()
        val top = ((gy * tileSize - frame.camY) * drawScale * TOPDOWN_Y_SCALE).roundToInt()
        val right = (((gx + 1) * tileSize - frame.camX) * drawScale).roundToInt()
        val bottom = (((gy + 1) * tileSize - frame.camY) * drawScale * TOPDOWN_Y_SCALE).roundToInt()
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
            // NaN/非法值防御（与 C++ 段同语义——非法实例不画任何像素）→
            // 精灵槽位有效性 → 视口剔除（世界坐标 → 帧缓冲像素；与 cropScreenRect 同风格）
            val src = if (isValidCloud(x, y, w, h, alpha)) {
                cloudSrcRects.getOrNull(spriteIndex.toInt())
            } else null
            val dst = src?.let { cloudScreenRect(frame, x, y, w, h, drawScale, canvas) }
            if (src == null || dst == null) continue
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
        val needsRebuild = fb == null || fb.width != vpW || fb.height != vpH || qualityChanged
        if (needsRebuild) {
            // 帧缓冲降级阈值与 RenderLodPolicy 同源（SpriteAtlasDef 生成常量）
            val bmpConfig = if (qualityFactor < RenderLodPolicy.DECOR_QUALITY_THRESHOLD) {
                Bitmap.Config.RGB_565
            } else {
                Bitmap.Config.ARGB_8888
            }
            // 不调 recycle() — 见 release() 注释。GC + NativeAllocationRegistry
            //   自然回收即可避免国产 ROM double-free SIGABRT
            frameBuffer = createBitmap(vpW.coerceAtLeast(1), vpH.coerceAtLeast(1), bmpConfig)
            frameCanvas = Canvas(frameBuffer ?: return)
            // resize 时清除缓存
            chunkCaches.forEach { col -> col.forEach { it.isValid = false } }
            chunkTileHash = 0
            chunkBuildingHash = 0
            chunkRoadHash = 0
            chunkDecorVersion = 0
            // chunk 失效基准用 LOD 合并值（scale/热控/显式关闭），
            // 与 renderFrame 的比较值同源，杜绝基准漂移
            lastDecorationsDisabled = decorSkip
            lastQualityFactor = qualityFactor
        }
    }

    // ============================================================
    // 预览精灵绘制
    // ============================================================
    // 占地框（预览框）绘制已拆为顶层函数 drawPreviewHighlight
    // （SoftwareCanvasBackend 类内函数数收敛——TooManyFunctions 守卫）。

    private fun drawPreview(canvas: Canvas, atlas: Bitmap, frame: RenderFrame, drawScale: Float) {
        val pOffX = frame.previewX - frame.camX
        val pOffY = frame.previewY - frame.camY
        val dstLeft = (pOffX * drawScale).roundToInt()
        val dstTop = (pOffY * drawScale * TOPDOWN_Y_SCALE).roundToInt()
        val dstRight = ((pOffX + frame.previewW) * drawScale).roundToInt()
        val dstBottom = ((pOffY + frame.previewH) * drawScale * TOPDOWN_Y_SCALE).roundToInt()
        val pw = dstRight - dstLeft
        val ph = dstBottom - dstTop
        val isOffscreen = dstLeft >= canvas.width || dstTop >= canvas.height ||
            dstRight <= 0 || dstBottom <= 0
        if (pw <= 0 || ph <= 0) return
        if (isOffscreen) return

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
        // 不调 Bitmap.recycle() — 国产 ROM (鸿蒙/澎湃OS/ColorOS) 的
        //   NativeAllocationRegistry CleanerThunk 在 recycle() 后仍会尝试
        //   二次释放原生内存导致 SIGABRT。直接置 null 让 GC 自然回收，
        //   NativeAllocationRegistry 的单次释放流程是安全的。
        chunkCaches.forEach { col -> col.forEach { it.bitmap = null } }
        frameBuffer = null
        frameCanvas = null
        // 分帧预算门控复位：release 后重建的 backend 从首帧构建语义重新开始
        hasEverComposedFrame = false
    }

}

// ── 放置/移动模式占地框（预览框）颜色 & 线宽、云层几何 ──
// 见 SoftwareCanvasBackendOverlays.kt（顶层常量/绘制工具独立成文件：
// 控制本文件长度 ≤2000 行，同时不增加软件后端类函数数）

/**
 * 相机/缩放合法性检查 + 缩放钳制（原 SoftwareCanvasBackend.sanitizeScale 下沉为顶层函数——
 * 减少该类方法数，TooManyFunctions 守卫；行为不变）。
 *
 * @return 合法 → 钳制后的 scale；NaN/Inf → null（调用方返回当前帧缓冲不渲染）
 */
private fun sanitizeRenderScale(frame: RenderFrame): Float? {
    if (isNonFinite(frame.camX) || isNonFinite(frame.camY) || isNonFinite(frame.scale)) {
        android.util.Log.w(SOFTWARE_CANVAS_TAG, "renderFrame: NaN/Inf in camera/scale")
        return null
    }
    return frame.scale.coerceIn(SOFTWARE_MIN_SCALE, SOFTWARE_MAX_SCALE)
}

/** NaN/Inf 判定（拆分裂合条件——ComplexCondition 守卫） */
private fun isNonFinite(v: Float): Boolean = v.isNaN() || v.isInfinite()

/**
 * 建筑/固定结构占地尺寸解析：结构（nameIdx ≥ BUILDING_NAMES.size）走
 * SpriteAtlasDef.STRUCTURES，建筑走 FOOTPRINT_BY_NAME_INDEX，越界兜底 2×2。
 * 顶层函数（不增加 SoftwareCanvasBackend 类函数数——TooManyFunctions 守卫）。
 */
private fun footprintOf(nameIdx: Int): Pair<Int, Int> {
    if (nameIdx >= SpriteAtlasDef.BUILDING_NAMES.size) {
        val s = SpriteAtlasDef.STRUCTURES.getOrNull(nameIdx - SpriteAtlasDef.BUILDING_NAMES.size)
        return (s?.footprintW ?: 2) to (s?.footprintH ?: 2)
    }
    return SpriteAtlasDef.FOOTPRINT_BY_NAME_INDEX.getOrElse(nameIdx) { 2 to 2 }
}

/**
 * 按源图集缩放（sourceScale）换算一组精灵图源矩形到像素裁剪矩形。
 * 顶层函数（不增加 SoftwareCanvasBackend 类函数数——TooManyFunctions 守卫）。
 */
private fun <T> buildScaledRects(
    sourceScale: Float,
    source: List<T>,
    rectOf: (T) -> SpriteRect
): Array<Rect> =
    source.mapIndexed { _, item ->
        val sr = rectOf(item)
        Rect(
            (sr.x * sourceScale).roundToInt(),
            (sr.y * sourceScale).roundToInt(),
            ((sr.x + sr.w) * sourceScale).roundToInt(),
            ((sr.y + sr.h) * sourceScale).roundToInt()
        )
    }.toTypedArray()

/**
 * Chunk 失效检查输入（分组 [SoftwareCanvasBackend.invalidateChunksForChanges] 长参数表——
 * LongParameterList 守卫；数据类自动生成 component1..8 供解构复用）。
 */
private data class ChunkInvalidationInput(
    val tileHash: Int,
    val buildingHash: Int,
    val roadHash: Int,
    val decorSkip: Boolean,
    val buildingArray: FloatArray?,
    val prevBuildingData: FloatArray?,
    val tileData: IntArray,
    val prevTileData: IntArray?
)

/**
 * SkyCanvasRenderer — Canvas 软件路径的 SkyBackground 屏幕空间渐变绘制助手。
 *
 * 独立于 [SoftwareCanvasBackend]（不增加后者 TooManyFunctions 计数）。持有并缓存渐变
 * Paint（配置/帧缓冲尺寸变化才重建，避免每帧分配）；与 C++ SkyBackground 同公式
 * （四段 top→second→third→bottom + positions [0,secondT,thirdT,1] + strength 向顶色混合）。
 * Paint 仅用于全帧矩形（屏幕/帧缓冲坐标），不受相机平移缩放影响。
 */
private class SkyCanvasRenderer {

    private var skyPaint: Paint? = null
    private var skyPaintConfig: SkyBackgroundConfig? = null
    private var skyPaintFbH: Int = -1

    /** 取屏幕空间渐变 Paint（缓存在配置/帧缓冲尺寸变化才重建） */
    fun paintFor(config: SkyBackgroundConfig, fbH: Int): Paint {
        val sp = skyPaint
        if (sp == null || skyPaintConfig != config || skyPaintFbH != fbH) {
            val s = config.strength.coerceIn(0f, 1f)
            // 四段渐变：每段 = mix(顶色, 该段色, strength)，strength 向顶色混合
            val colors = intArrayOf(
                mix(config.topColor, config.topColor, s),
                mix(config.topColor, config.secondColor, s),
                mix(config.topColor, config.thirdColor, s),
                mix(config.topColor, config.bottomColor, s)
            )
            val shader = LinearGradient(
                0f, 0f, 0f, fbH.toFloat(),
                colors, normalizePositions(0f, config.secondT, config.thirdT, 1f),
                Shader.TileMode.CLAMP
            )
            skyPaint = Paint().apply {
                isAntiAlias = false
                isDither = false   // 禁止噪声/颗粒（清新柔和纯净渐变，不做抖动）
                this.shader = shader
            }
            skyPaintConfig = config
            skyPaintFbH = fbH
        }
        return skyPaint!!
    }

    /** 颜色插值并转 0..255 整型（t ∈ [0,1]，从 from → to） */
    private fun mix(from: SkyColor, to: SkyColor, t: Float): Int {
        val a = t.coerceIn(0f, 1f)
        val r = ((from.r + (to.r - from.r) * a) * 255).roundToInt().coerceIn(0, 255)
        val g = ((from.g + (to.g - from.g) * a) * 255).roundToInt().coerceIn(0, 255)
        val b = ((from.b + (to.b - from.b) * a) * 255).roundToInt().coerceIn(0, 255)
        return Color.rgb(r, g, b)
    }

    /**
     * 归一化并保证四段位置严格递增（LinearGradient 要求单调递增，否则 skia 抛异常）。
     * 退化段（相邻停靠点重合）用极小 epsilon 展开，视觉近似线性，防崩溃不闪黑。
     */
    private fun normalizePositions(a: Float, b: Float, c: Float, d: Float): FloatArray {
        val eps = 1e-4f
        var p0 = a.coerceIn(0f, 1f)
        var p1 = b.coerceIn(0f, 1f)
        var p2 = c.coerceIn(0f, 1f)
        var p3 = d.coerceIn(0f, 1f)
        if (p1 <= p0) p1 = p0 + eps
        if (p2 <= p1) p2 = p1 + eps
        if (p3 <= p2) p3 = p2 + eps
        p0 = p0.coerceAtLeast(0f)
        p1 = p1.coerceAtMost(1f)
        p2 = p2.coerceAtMost(1f)
        p3 = p3.coerceAtMost(1f)
        return floatArrayOf(p0, p1, p2, p3)
    }
}

