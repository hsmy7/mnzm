package com.xianxia.sect.ui.game.sect

import android.content.Context
import android.graphics.PixelFormat
import kotlin.concurrent.thread
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import com.xianxia.sect.core.nativebridge.NativeBridge
import com.xianxia.sect.core.touch.SectMapTouchEngine
import com.xianxia.sect.core.touch.TouchAction
import com.xianxia.sect.core.touch.TouchData

/**
 * NativeSurfaceView — 承载地图渲染的表面，支持 Vulkan 原生渲染和 Canvas 软件渲染双模式。
 *
 * [RenderMode] 决定使用哪种后端：
 * - VULKAN: 通过 C++ VulkanBackend 在 RenderThread 中 GPU 加速渲染（默认首选）
 * - SOFTWARE: 通过 [SoftwareCanvasBackend] 在 RenderThread 中 CPU 软件渲染（回退）
 *
 * 渲染模式选择链：
 * 1. [RenderStrategy] 预判（模拟器直接走 SOFTWARE）
 * 2. Vulkan init 失败时自动降级到 SOFTWARE
 *
 * 在 Compose UI 中以 AndroidView 方式嵌入，作为宗门地图的渲染目标。
 * 生命周期与 Surface 绑定：surfaceCreated → 初始化渲染器 → 每帧渲染 → surfaceDestroyed → 关闭。
 */
class NativeSurfaceView(
    context: Context,
    private val config: NativeRenderConfig
) : SurfaceView(context), SurfaceHolder.Callback {

    // ============================================================
    // 渲染模式
    // ============================================================

    /** 渲染后端模式 */
    enum class RenderMode {
        /** GPU Vulkan 原生渲染（默认） */
        VULKAN,
        /** CPU Canvas 软件渲染（回退） */
        SOFTWARE
    }

    /** 当前渲染模式（由 [useRenderMode] 或降级逻辑设置） */
    @Volatile
    private var renderMode: RenderMode = RenderMode.VULKAN

    /**
     * 强制指定渲染模式。在 [surfaceChanged] 前设置生效。
     * - 模拟器/Vulkan 问题设备：设置为 SOFTWARE 跳过 Vulkan 初始化
     * - 正常设备：保持 VULKAN（默认）
     */
    var useRenderMode: RenderMode = RenderMode.VULKAN

    /** 软件渲染后端（仅 [RenderMode.SOFTWARE] 时非空） */
    private var softwareBackend: SoftwareCanvasBackend? = null

    /** 渲染线程 */
    private var renderThread: RenderThread? = null

    /** 是否正在初始化（防止 surfaceChanged 重复调用导致并发 init） */
    @Volatile
    private var initInProgress: Boolean = false

    /** VulkanInit 后台线程引用，供 surfaceDestroyed 时中断取消 */
    @Volatile
    private var vulkanInitThread: Thread? = null

    /**
     * 目标帧率。0 = 跟随系统 VSYNC（不主动 sleep）。
     * 设置为正整数可固定帧率，节省电量。
     */
    var targetFps: Int = 10

    /** 渲染器是否已初始化 */
    var isReady: Boolean = false
        private set

    /** 渲染器就绪后的回调（用于触发纹理上传） */
    var onRendererReady: (() -> Unit)? = null

    /**
     * Vulkan 初始化生命周期监听器。
     * 由 GameActivity 实现，用于在 :feature:game 模块外驱动 CrashRecoveryEngine（在 :app 模块）。
     */
    var vulkanInitListener: VulkanInitListener? = null

    /** Vulkan 初始化生命周期回调接口（由 GameActivity 中的 CrashRecoveryEngine 驱动） */
    interface VulkanInitListener {
        /** 在 NativeBridge.initRenderer 调用前触发（写前日志入口） */
        fun onSurfaceInitStarted()
        /** initRenderer 返回 true 时触发（清除写前标记） */
        fun onSurfaceInitSucceeded()
        /** initRenderer 返回 false 或抛出异常时触发（记录 Vulkan 失败） */
        fun onSurfaceInitFailed()
    }

    // ============================================================
    // 纹理资源（由外部在 renderer 就绪后上传，统一走图集）
    // ============================================================

    /** 主图集纹理 GPU ID（包含地面/装饰/建筑）——Vulkan 路径使用 */
    @Volatile
    var atlasTextureId: Int = 0

    /** 主图集 Bitmap（包含地面/装饰/建筑）——Canvas 回退路径使用 */
    @Volatile
    var atlasBitmap: android.graphics.Bitmap? = null

    /**
     * 构建纹理图集 — 将所有装饰和建筑精灵合并到单张 2048×2048 纹理。
     * 布局与 C++ TextureAtlas.h 中的 MAP_SPRITES 定义一致。
     * 必须在渲染器就绪后调用。
     *
     * - Vulkan 路径：上传到 GPU 并返回纹理 ID
     * - Canvas 路径：保存 Bitmap 引用供软件渲染使用
     */
    fun buildAtlas(context: android.content.Context): Int {
        val atlas = buildAtlasBitmap(context)
        atlasBitmap = atlas  // 保存 Bitmap 引用（Canvas 回退路径使用）

        if (renderMode == RenderMode.SOFTWARE) {
            // Canvas 路径：不需要上传 GPU，返回 0
            android.util.Log.i("NativeSurfaceView", "buildAtlas: software mode, bitmap kept in memory")
            return 0
        }

        // Vulkan 路径：上传到 GPU
        val pixels = IntArray(atlas.width * atlas.height)
        atlas.getPixels(pixels, 0, atlas.width, 0, 0, atlas.width, atlas.height)
        val buffer = ByteArray(pixels.size * 4)
        for (i in pixels.indices) {
            val p = pixels[i]
            buffer[i * 4] = ((p shr 16) and 0xFF).toByte()
            buffer[i * 4 + 1] = ((p shr 8) and 0xFF).toByte()
            buffer[i * 4 + 2] = (p and 0xFF).toByte()
            buffer[i * 4 + 3] = ((p shr 24) and 0xFF).toByte()
        }
        val texId = NativeBridge.uploadTexture(buffer, atlas.width, atlas.height)
        atlas.recycle()
        return texId
    }

    /**
     * 构建纹理图集 Bitmap — 将所有装饰和建筑精灵合并到单张 2048×2048 Bitmap。
     * 布局与 C++ TextureAtlas.h 中的 MAP_SPRITES 定义一致。
     * 供 Canvas 回退渲染器使用（不上传 GPU）。
     */
    companion object {
        fun buildAtlasBitmap(context: android.content.Context): android.graphics.Bitmap {
            val ATLAS_W = 2048
            val ATLAS_H = 2048
            val atlas = android.graphics.Bitmap.createBitmap(
                ATLAS_W, ATLAS_H, android.graphics.Bitmap.Config.ARGB_8888
            )
            val canvas = android.graphics.Canvas(atlas)
            val paint = android.graphics.Paint().apply { isFilterBitmap = false }

            // 定义精灵在 atlas 中的位置（与 C++ TextureAtlas.h MAP_SPRITES 一致）
            data class SpriteSlot(val name: String, val x: Int, val y: Int, val w: Int, val h: Int, val resId: Int)

            // 运行时查找资源 ID
            val pkg = context.packageName
            fun res(name: String): Int = context.resources.getIdentifier(name, "drawable", pkg)
                .also { id -> if (id == 0) android.util.Log.w("NativeSurfaceView", "buildAtlas: res not found: $name") }

            val buildingMap = com.xianxia.sect.ui.game.building.BuildingRegistry.allDrawableMap()

            val slots = listOf(
                // 地面 + 装饰（64×64 tiles）
                SpriteSlot("ground_tile",   0,   0,   64, 64, res("map_tile")),
                SpriteSlot("grass_small",   64,  0,   64, 64, res("decoration_grass_small")),
                SpriteSlot("grass_medium",  128, 0,   64, 64, res("decoration_grass_medium")),
                SpriteSlot("grass_large",   192, 0,   64, 64, res("decoration_grass_large")),
                SpriteSlot("tree1",         256, 0,  128,128, res("decoration_tree1")),
                SpriteSlot("tree2",         384, 0,  128,128, res("decoration_tree2")),
                // 地面变体2（随机混用）
                SpriteSlot("ground_tile_v2", 512, 0,   64, 64, res("map_tile_v2")),
                // 建筑（128×128，每行4个，从行1开始）
                SpriteSlot("灵矿场",           0, 128, 128,128, buildingMap["灵矿场"] ?: 0),
                SpriteSlot("灵植阁",         128, 128, 128,128, buildingMap["灵植阁"] ?: 0),
                SpriteSlot("灵田",           256, 128, 128,128, buildingMap["灵田"] ?: 0),
                SpriteSlot("炼丹炉",         384, 128, 128,128, buildingMap["炼丹炉"] ?: 0),
                SpriteSlot("锻造坊",         512, 128, 128,128, buildingMap["锻造坊"] ?: 0),
                SpriteSlot("仓库",             0, 256, 128,128, buildingMap["仓库"] ?: 0),
                SpriteSlot("藏经阁",         128, 256, 128,128, buildingMap["藏经阁"] ?: 0),
                SpriteSlot("问道塔",         256, 256, 128,128, buildingMap["问道塔"] ?: 0),
                SpriteSlot("青云塔",         384, 256, 128,128, buildingMap["青云塔"] ?: 0),
                SpriteSlot("天枢殿",         512, 256, 128,128, buildingMap["天枢殿"] ?: 0),
                SpriteSlot("执法堂",           0, 384, 128,128, buildingMap["执法堂"] ?: 0),
                SpriteSlot("任务阁",         128, 384, 128,128, buildingMap["任务阁"] ?: 0),
                SpriteSlot("巡视楼",         256, 384, 128,128, buildingMap["巡视楼"] ?: 0),
                SpriteSlot("监牢",           384, 384, 128,128, buildingMap["监牢"] ?: 0),
                SpriteSlot("单人住所",       512, 384, 128,128, buildingMap["单人住所"] ?: 0),
                SpriteSlot("中级单人住所",     0, 512, 128,128, buildingMap["中级单人住所"] ?: 0),
                SpriteSlot("多人住所",       128, 512, 128,128, buildingMap["多人住所"] ?: 0),
                SpriteSlot("血炼池",         256, 512, 128,128, buildingMap["血炼池"] ?: 0),
            )

            // 绘制每个精灵到图集
            var loadedCount = 0
            for (slot in slots) {
                if (slot.resId == 0) continue
                try {
                    val bmp = android.graphics.BitmapFactory.decodeResource(
                        context.resources, slot.resId
                    )
                    if (bmp != null) {
                        canvas.drawBitmap(bmp, null,
                            android.graphics.Rect(slot.x, slot.y, slot.x + slot.w, slot.y + slot.h),
                            paint)
                        bmp.recycle()
                        loadedCount++
                    } else {
                        android.util.Log.w("NativeSurfaceView",
                            "buildAtlas: failed to decode '${slot.name}' (resId=$slot.resId)")
                    }
                } catch (e: Exception) {
                    android.util.Log.w("NativeSurfaceView",
                        "buildAtlas: error loading '${slot.name}': ${e.message}")
                }
            }

            android.util.Log.i("NativeSurfaceView",
                "buildAtlas: $loadedCount/${slots.size} sprites loaded, atlas=${ATLAS_W}x$ATLAS_H")
            return atlas
        }
    }

    /**
     * 每帧渲染状态 — 由 Compose 层通过 [updateRenderState] 写入，
     * 渲染线程通过原子快照 [currentRenderState] 读取。
     * 使用 immutable data class 原子替换，避免多字段撕裂读（白屏 Bug 根源）。
     */
    @Volatile
    var currentRenderState: FrameRenderState = FrameRenderState()

    /**
     * 相机脏标记 — 独立于 [currentRenderState]，由渲染线程读取后复位。
     * 与 FrameRenderState 分离是因为渲染线程需要复位它，而原子快照不可变。
     */
    @Volatile
    var cameraDirty: Boolean = false

    /** 从 Compose 层原子更新渲染状态 */
    fun updateRenderState(state: FrameRenderState) {
        currentRenderState = state
        cameraDirty = true
    }

    /** 跨平台手势引擎 */
    var touchEngine: SectMapTouchEngine? = null

    init {
        holder.apply {
            addCallback(this@NativeSurfaceView)
            setFormat(PixelFormat.RGBA_8888)
        }
        // 必须设置 clickable 才能接收触摸事件
        isClickable = true
        isFocusableInTouchMode = true
    }

    // ============================================================
    // SurfaceHolder.Callback
    // ============================================================

    override fun surfaceCreated(holder: SurfaceHolder) {
        NativeBridge.ensureLoaded()
        NativeBridge.initAtlas()
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, w: Int, h: Int) {
        if (!isReady && holder.surface == null) return

        if (!isReady) {
            if (initInProgress) return  // 防止重复调用
            initInProgress = true

            // ★ 渲染模式预判：若策略要求 SOFTWARE 则直接走软件渲染
            if (useRenderMode == RenderMode.SOFTWARE) {
                initInProgress = false
                android.util.Log.i("NativeSurfaceView",
                    "RenderMode.SOFTWARE (by policy) — starting software backend")
                post {
                    if (!isReady) {
                        // 先设置渲染模式和软件后端，再通知上层上传纹理
                        // 注意：buildAtlas() 依赖 renderMode 判断是否回收 Bitmap，
                        // 必须在 onRendererReady 之前设置，否则图集 Bitmap 被误回收
                        softwareBackend = SoftwareCanvasBackend(config)
                        renderMode = RenderMode.SOFTWARE
                        // 通知 Compose 层上传纹理（TextureAtlas 已在 surfaceCreated 中 init）
                        onRendererReady?.invoke()
                        isReady = true
                        renderThread = RenderThread().also { it.start() }
                    }
                }
                return
            }

            val surface = holder.surface ?: return

            // 初始化超时安全网（10 秒）
            val timeoutRunnable = Runnable {
                if (!isReady) {
                    android.util.Log.w("NativeSurfaceView",
                        "Vulkan init timed out (10s), forcing ready")
                    initInProgress = false
                    isReady = true
                }
            }
            postDelayed(timeoutRunnable, 10_000L)

            // Layer 4: 取消之前的初始化线程（如有），防止竞态
            vulkanInitThread?.interrupt()
            vulkanInitThread = null

            vulkanInitThread = kotlin.concurrent.thread(name = "VulkanInit") {
                try {
                    val initStart = System.currentTimeMillis()

                    // Layer 2: Phase 2 写前标记 — initRenderer 前写入
                    vulkanInitListener?.onSurfaceInitStarted()

                    val ok = NativeBridge.initRenderer(
                        viewportW = width,
                        viewportH = height,
                        worldW = config.worldPixelWidth,
                        worldH = config.worldPixelHeight,
                        tileSize = config.tileSize,
                        surface = surface
                    )

                    if (ok) {
                        // Layer 2: 成功清除写前标记 + 清除 Vulkan 失败标记
                        vulkanInitListener?.onSurfaceInitSucceeded()

                        android.util.Log.i("NativeSurfaceView",
                            "Vulkan init OK in ${System.currentTimeMillis() - initStart}ms")

                        post {
                            removeCallbacks(timeoutRunnable)
                            initInProgress = false
                            vulkanInitThread = null
                            if (isReady) return@post

                            // 先上传纹理（地面 + 图集），再启动渲染线程
                            onRendererReady?.invoke()

                            isReady = true
                            renderThread = RenderThread().also { it.start() }
                        }
                    } else {
                        // Layer 2: 失败 → 清除写前标记 + 记录持久化失败
                        vulkanInitListener?.onSurfaceInitFailed()

                        android.util.Log.e("NativeSurfaceView",
                            "Vulkan init failed after ${System.currentTimeMillis() - initStart}ms — " +
                            "falling back to software renderer")

                        post {
                            removeCallbacks(timeoutRunnable)
                            initInProgress = false
                            vulkanInitThread = null
                            if (!isReady) {
                                // 降级到软件渲染
                                softwareBackend = SoftwareCanvasBackend(config)
                                renderMode = RenderMode.SOFTWARE
                                onRendererReady?.invoke()
                                isReady = true
                                renderThread = RenderThread().also { it.start() }
                            }
                        }
                    }
                } catch (t: Throwable) {
                    // Layer 4: 线程被中断（surfaceDestroyed），不做降级
                    if (t is InterruptedException || Thread.interrupted()) {
                        android.util.Log.w("NativeSurfaceView",
                            "Vulkan init interrupted — surface was destroyed")
                        initInProgress = false
                        vulkanInitThread = null
                        return@thread
                    }
                    // 其他异常（如 OOM），记录并降级
                    android.util.Log.e("NativeSurfaceView",
                        "Vulkan init crashed: ${t.message}", t)
                    vulkanInitListener?.onSurfaceInitFailed()
                    post {
                        removeCallbacks(timeoutRunnable)
                        initInProgress = false
                        vulkanInitThread = null
                        if (!isReady) {
                            softwareBackend = SoftwareCanvasBackend(config)
                            renderMode = RenderMode.SOFTWARE
                            onRendererReady?.invoke()
                            isReady = true
                            renderThread = RenderThread().also { it.start() }
                        }
                    }
                }
            }
        } else {
            if (renderMode == RenderMode.SOFTWARE) {
                // 软件模式窗口变化：重建 SoftwareCanvasBackend 视口
                softwareBackend?.resize(w, h)
            } else {
                NativeBridge.resizeRenderer(w, h)
            }
        }
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        isReady = false
        // Layer 4: 中断正在执行的 VulkanInit 线程
        vulkanInitThread?.interrupt()
        vulkanInitThread = null
        renderThread?.running = false
        renderThread = null
        softwareBackend = null
        if (renderMode == RenderMode.VULKAN) {
            NativeBridge.shutdownRenderer()
        }
    }

    // ============================================================
    // 触摸事件 → 转换为 TouchData → 喂入跨平台手势引擎
    // ============================================================

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val engine = touchEngine ?: return false

        val touchData = TouchData(
            x = event.x,
            y = event.y,
            action = when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> TouchAction.DOWN
                MotionEvent.ACTION_MOVE -> TouchAction.MOVE
                MotionEvent.ACTION_UP -> TouchAction.UP
                MotionEvent.ACTION_CANCEL -> TouchAction.CANCEL
                else -> return false
            },
            timestamp = event.eventTime.toLong() * 1_000_000L,
            pointerId = event.getPointerId(event.actionIndex)
        )
        engine.onTouch(touchData)
        return true
    }

    override fun performClick(): Boolean {
        // 覆写以满足 View 契约（setClickable 后必须）
        return super.performClick()
    }

    // ============================================================
    // 渲染线程 — 双路径派遣
    // ============================================================

    inner class RenderThread : Thread("NativeRenderer") {
        @Volatile
        var running = true

        override fun run() {
            // 首帧快速清除 Surface 缓冲区，防止华为模拟器等设备上
            // SurfaceFlinger 未正确清除新分配缓冲区导致残留内容显示
            if (renderMode == RenderMode.SOFTWARE) {
                try {
                    val clearCanvas = holder.lockCanvas()
                    if (clearCanvas != null) {
                        clearCanvas.drawColor(android.graphics.Color.BLACK)
                        holder.unlockCanvasAndPost(clearCanvas)
                    }
                } catch (_: Exception) {
                    // 首帧清除非关键操作，失败不影响后续渲染
                }
            }

            val frameIntervalNs = 1_000_000_000L / targetFps
            var lastFrameNs = System.nanoTime()

            while (running && isReady) {
                val now = System.nanoTime()
                val elapsedNs = now - lastFrameNs

                if (elapsedNs < frameIntervalNs) {
                    val sleepMs = (frameIntervalNs - elapsedNs) / 1_000_000
                    if (sleepMs > 1) {
                        try { Thread.sleep(sleepMs) } catch (_: InterruptedException) { break }
                    }
                    continue
                }
                lastFrameNs = now

                when (renderMode) {
                    RenderMode.VULKAN -> renderVulkanFrame()
                    RenderMode.SOFTWARE -> renderSoftwareFrame()
                }
            }
        }

        /** Vulkan GPU 渲染路径 */
        private fun renderVulkanFrame() {
            val rs = currentRenderState

            if (cameraDirty) {
                NativeBridge.setCamera(rs.camX, rs.camY, rs.scale, width, height)
                cameraDirty = false
            }

            NativeBridge.beginFrame()

            val td = rs.tileData
            val uv = rs.uvMap
            val bd = rs.buildingData
            val buv = rs.buildingUVMap
            if (td != null && uv != null && atlasTextureId != 0) {
                NativeBridge.drawAllTiles(
                    tileData = td,
                    cols = config.worldWidthCells,
                    rows = config.worldHeightCells,
                    buildingData = bd,
                    buildingCount = rs.buildingCount,
                    buildingVisible = rs.buildingVisible,
                    tileSize = config.tileSize,
                    atlasTexId = atlasTextureId,
                    uvMap = uv,
                    buildingUVMap = buv
                )
            }

            if (rs.showPreview && atlasTextureId != 0) {
                NativeBridge.drawSprite(
                    rs.previewX, rs.previewY, rs.previewW, rs.previewH,
                    atlasTextureId,
                    rs.previewU0, rs.previewV0, rs.previewU1, rs.previewV1,
                    rs.previewTintRed, rs.previewTintGreen, rs.previewTintBlue, rs.previewAlpha
                )
            }

            NativeBridge.submitFrame()
        }

        /** Canvas 软件渲染路径（使用 [SoftwareCanvasBackend] + lockCanvas/unlockCanvasAndPost） */
        private fun renderSoftwareFrame() {
            val sb = softwareBackend ?: return
            val atlas = atlasBitmap ?: return
            val rs = currentRenderState

            // 渲染到帧缓冲区
            val frame = sb.renderFrame(
                rs = rs,
                atlas = atlas,
                cols = config.worldWidthCells,
                rows = config.worldHeightCells,
                pixelW = config.worldPixelWidth,
                pixelH = config.worldPixelHeight
            ) ?: return

            // 输出到 Surface（双缓冲自动处理）
            // Layer 6: lockCanvas 失败时重试最多 3 次
            var retries = 3
            while (retries > 0) {
                try {
                    val surfaceCanvas = holder.lockCanvas() ?: run {
                        retries--
                        continue
                    }
                    surfaceCanvas.drawBitmap(frame, 0f, 0f, null)
                    holder.unlockCanvasAndPost(surfaceCanvas)
                    break
                } catch (e: Exception) {
                    retries--
                    if (retries == 0) {
                        android.util.Log.w("NativeSurfaceView",
                            "Software render: lockCanvas failed after 3 retries: ${e.message}")
                    } else {
                        android.util.Log.d("NativeSurfaceView",
                            "Software render: lockCanvas retry $retries: ${e.message}")
                        try { Thread.sleep(5) } catch (_: InterruptedException) { break }
                    }
                }
            }
        }
    }
}

/**
 * 原生渲染器配置（由 Compose 层创建，渲染线程只读）
 */
data class NativeRenderConfig(
    val tileSize: Int,
    val worldWidthCells: Int,
    val worldHeightCells: Int,
    val worldPixelWidth: Int,
    val worldPixelHeight: Int
)

/**
 * 每帧渲染状态（由 Compose 层通过 [NativeSurfaceView.updateRenderState] 批量写入）
 *
 * 架构 v2：统一瓦片层（地面+装饰+建筑合并到单张图集，单次 draw call）
 */
data class FrameRenderState(
    val camX: Float = 0f,
    val camY: Float = 0f,
    val scale: Float = 1f,
    val cameraDirty: Boolean = false,
    val buildingVisible: Boolean = true,
    val tileData: IntArray? = null,
    val uvMap: FloatArray? = null,
    val firstCol: Int = 0,
    val lastCol: Int = 0,
    val firstRow: Int = 0,
    val lastRow: Int = 0,
    val buildingData: FloatArray? = null,
    val buildingCount: Int = 0,
    val buildingUVMap: FloatArray? = null,
    // — 建筑精灵预览（建造/移动模式） —
    val showPreview: Boolean = false,
    val previewX: Float = 0f,
    val previewY: Float = 0f,
    val previewW: Float = 0f,
    val previewH: Float = 0f,
    val previewU0: Float = 0f,
    val previewV0: Float = 0f,
    val previewU1: Float = 0f,
    val previewV1: Float = 0f,
    val previewTintRed: Float = 0.25f,
    val previewTintGreen: Float = 1.0f,
    val previewTintBlue: Float = 0.25f,
    val previewAlpha: Float = 0.5f
)
