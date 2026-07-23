package com.xianxia.sect.ui.game.sect

import android.content.Context
import android.graphics.PixelFormat
import kotlin.concurrent.thread
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import com.xianxia.sect.feature.game.R
import com.xianxia.sect.core.nativebridge.NativeBridge
import com.xianxia.sect.core.render.RenderMetrics
import com.xianxia.sect.core.render.SpriteAtlasDef
import com.xianxia.sect.core.touch.SectMapTouchEngine
import com.xianxia.sect.core.touch.TouchAction
import com.xianxia.sect.core.touch.TouchData
import java.util.concurrent.atomic.AtomicBoolean
import com.xianxia.sect.ui.game.sect.RenderCommandBus

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

    /**
     * 是否有等待中的 post 初始化。
     * 防止 surfaceDestroyed 后 stale post 回调执行导致竞态。
     */
    @Volatile
    private var pendingInit: Boolean = false

    /**
     * Surface 版本计数器，每次 surfaceChanged 递增。
     * 用于检测 VulkanInit 线程的 post Runnable 是否在 surface 已被销毁后执行。
     * post Runnable 在捕获时的 generation 与当前值不匹配时跳过执行。
     */
    @Volatile
    private var surfaceGeneration: Int = 0

    /** VulkanInit 后台线程引用，供 surfaceDestroyed 时中断取消 */
    @Volatile
    private var vulkanInitThread: Thread? = null

    /**
     * 目标帧率。0 = 跟随系统 VSYNC（不主动 sleep）。
     * 设置为正整数可固定帧率，节省电量。
     */
    @Volatile
    var targetFps: Int = 10

    /** 渲染器是否已初始化 */
    @Volatile
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
        val atlas: android.graphics.Bitmap
        try {
            atlas = buildAtlasBitmap(context)
            atlasBitmap = atlas
        } catch (t: Throwable) {
            android.util.Log.e("NativeSurfaceView", "buildAtlas failed", t)
            RenderMetrics.atlasBuildFailed.incrementAndGet()
            return 0
        }

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
     * 精灵位置来自 [SpriteAtlasDef]（唯一来源），资源 ID 运行时查找。
     * 供 Canvas 回退渲染器使用（不上传 GPU）。
     */
    companion object {
        fun buildAtlasBitmap(context: android.content.Context): android.graphics.Bitmap {
            val atlas = android.graphics.Bitmap.createBitmap(
                SpriteAtlasDef.ATLAS_W, SpriteAtlasDef.ATLAS_H,
                android.graphics.Bitmap.Config.ARGB_8888
            )
            val canvas = android.graphics.Canvas(atlas)
            val paint = android.graphics.Paint().apply { isFilterBitmap = false }

            data class SpriteSlot(
                val name: String,
                val x: Int, val y: Int, val w: Int, val h: Int,
                val resId: Int
            )

            // 瓦片/装饰精灵 R.drawable 预建映射（替代 getIdentifier
            // 运行时查找，避免华为 HarmonyOS 资源表分片返回 0
            // 导致精灵图加载为空白）
            val tileDrawableMap = mapOf(
                "map_tile" to R.drawable.map_tile,
                "map_tile_v2" to R.drawable.map_tile_v2,
                "decoration_grass_small" to R.drawable.decoration_grass_small,
                "decoration_grass_medium" to R.drawable.decoration_grass_medium,
                "decoration_grass_large" to R.drawable.decoration_grass_large,
                "decoration_tree1" to R.drawable.decoration_tree1,
                "decoration_tree2" to R.drawable.decoration_tree2,
            )

            // 地砖精灵 R.drawable 映射
            val floorTileDrawableMap = mapOf(
                "floor_tile_2x2" to R.drawable.floor_tile_2x2,
                "floor_tile_2x3" to R.drawable.floor_tile_2x3,
                "floor_tile_3x2" to R.drawable.floor_tile_3x2,
                "floor_tile_3x3" to R.drawable.floor_tile_3x3,
                "spirit_mine_ground" to R.drawable.spirit_mine_ground,
            )
            fun res(name: String): Int {
                val id = tileDrawableMap[name] ?: 0
                if (id == 0) android.util.Log.w("NativeSurfaceView",
                    "buildAtlas: res not found: $name")
                return id
            }

            val buildingMap = com.xianxia.sect.core.engine.domain.building.BuildingFeatureRegistry.all.associate { it.displayName to it.drawableRes }

            // 瓦片精灵：来自 SpriteAtlasDef.TileType
            val tileSlots = mutableListOf<SpriteSlot>()
            for (tile in SpriteAtlasDef.TileType.values()) {
                val name = when (tile) {
                    SpriteAtlasDef.TileType.GROUND -> "map_tile"
                    SpriteAtlasDef.TileType.GRASS_SMALL -> "decoration_grass_small"
                    SpriteAtlasDef.TileType.GRASS_MEDIUM -> "decoration_grass_medium"
                    SpriteAtlasDef.TileType.GRASS_LARGE -> "decoration_grass_large"
                    SpriteAtlasDef.TileType.TREE1 -> "decoration_tree1"
                    SpriteAtlasDef.TileType.TREE2 -> "decoration_tree2"
                    SpriteAtlasDef.TileType.TILE_BUILDING -> ""
                    SpriteAtlasDef.TileType.GROUND_V2 -> "map_tile_v2"
                    else -> ""
                }
                val sr = tile.rect
                val id = if (name.isEmpty()) 0 else res(name)
                tileSlots.add(SpriteSlot(name, sr.x, sr.y, sr.w, sr.h, id))
            }

            // 建筑精灵：来自 SpriteAtlasDef.BUILDING_NAMES
            val buildingSlots = mutableListOf<SpriteSlot>()
            for (idx in SpriteAtlasDef.BUILDING_NAMES.indices) {
                val name = SpriteAtlasDef.BUILDING_NAMES[idx]
                val sr = SpriteAtlasDef.buildingRect(idx)
                buildingSlots.add(SpriteSlot(name, sr.x, sr.y, sr.w, sr.h,
                    buildingMap[name] ?: 0))
            }

            // 地砖精灵：来自 SpriteAtlasDef.FloorTileType
            val floorTileSlots = mutableListOf<SpriteSlot>()
            for (ft in SpriteAtlasDef.FloorTileType.values()) {
                val r = ft.pixelRect
                floorTileSlots.add(SpriteSlot(ft.key, r.x, r.y, r.w, r.h,
                    floorTileDrawableMap[ft.key] ?: 0))
            }

            val slots = tileSlots + buildingSlots + floorTileSlots

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
                            android.graphics.Rect(slot.x, slot.y,
                                slot.x + slot.w, slot.y + slot.h),
                            paint)
                        bmp.recycle()
                        loadedCount++
                    } else {
                        android.util.Log.w("NativeSurfaceView",
                            "buildAtlas: null bitmap for '${slot.name}'")
                        com.xianxia.sect.core.render.RenderMetrics.atlasLoadSpriteFailed.incrementAndGet()
                    }
                } catch (e: Exception) {
                    android.util.Log.w("NativeSurfaceView",
                        "buildAtlas: error loading '${slot.name}': ${e.message}")
                    com.xianxia.sect.core.render.RenderMetrics.atlasLoadSpriteFailed.incrementAndGet()
                }
            }

            android.util.Log.i("NativeSurfaceView",
                "buildAtlas: $loadedCount/${slots.size} sprites loaded")
            return atlas
        }
    }

    /**
     * 每帧渲染帧 — 由 Compose 层通过 [updateRenderState] 写入，
     * 渲染线程通过原子快照 [currentFrame] 读取。
     * 使用 immutable data class 原子替换，避免多字段撕裂读（白屏 Bug 根源）。
     * 两后端（Vulkan/Canvas）均消费同一份数据，杜绝不同步。
     */
    @Volatile
    var currentFrame: RenderFrame? = null

    /**
     * 渲染命令总线 — 游戏逻辑线程→渲染线程的直达建筑数据通道。
     * 由 MainGameScreen 在 `AndroidView.update` 门控外注入。
     * 如果为 null（未设置），回退到 [currentFrame] 中的 buildingData。
     */
    @Volatile
    var commandBus: RenderCommandBus? = null

    /**
     * 相机脏标记 — [currentFrame] 更新时置 true，渲染线程读取后复位。
     * 使用 [AtomicBoolean] 防止 Compose 线程与 RenderThread 之间的
     * read-then-write 竞态导致相机更新丢失。
     */
    val cameraDirty = AtomicBoolean(false)

    // ── 独立相机通道（不经过 RenderFrame 帧率门控） ──

    /** 相机 X 位置（最新值，渲染线程原子读取） */
    @Volatile
    var renderCamX: Float = 0f

    /** 相机 Y 位置（最新值，渲染线程原子读取） */
    @Volatile
    var renderCamY: Float = 0f

    /** 相机缩放（最新值，渲染线程原子读取） */
    @Volatile
    var renderScale: Float = 1f

    /**
     * 从 Compose 层独立推送相机状态（不经过 [updateRenderState] 的帧率门控）。
     * 触摸拖拽时每帧调用，确保相机响应不延迟。
     */
    fun setCamera(camX: Float, camY: Float, scale: Float) {
        renderCamX = camX
        renderCamY = camY
        renderScale = scale
        cameraDirty.set(true)
    }

    /** 从 Compose 层原子更新渲染帧数据 */
    fun updateRenderState(frame: RenderFrame) {
        // 仅当 tileData/buildingData 引用变化时拷贝（防止 Compose 线程后续修改）。
        // flatTileData 使用 remember() 缓存同一引用直至数据变化，稳态帧无需重复复制。
        val prevTileData = currentFrame?.tileData
        val safeTileData = if (frame.tileData === prevTileData) prevTileData else frame.tileData.copyOf()
        val prevBuildingData = currentFrame?.buildingData
        val safeBuildingData = if (frame.buildingData != null && frame.buildingData === prevBuildingData) prevBuildingData else frame.buildingData?.copyOf()
        currentFrame = frame.copy(
            tileData = safeTileData,
            buildingData = safeBuildingData
        )
        cameraDirty.set(true)

        // 断言：debug build 时验证 tileData 完整性
        if (frame.tileData.size != config.worldWidthCells * config.worldHeightCells) {
            android.util.Log.e("NativeSurfaceView",
                "RenderFrame tileData size mismatch: ${frame.tileData.size} " +
                "vs expected ${config.worldWidthCells * config.worldHeightCells}")

            // ★ 修复：尺寸不匹配时不更新 currentFrame，防止 SoftwareCanvasBackend
            // ChunkTile.rebuild 中 ArrayIndexOutOfBoundsException 被 catch 吞掉后永久黑屏
            return
        }
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
        // VULKAN/HYBRID 模式才需要加载 native 库和纹理图集，
        // SOFTWARE 模式完全使用 Canvas 渲染，不加载 native 库
        if (useRenderMode != RenderMode.SOFTWARE) {
            NativeBridge.ensureLoaded()
            NativeBridge.initAtlas()
        }

        // 首帧绘制：在 surface 刚创建时立即画一帧纯黑背景，防止 GPU surface
        // 分配延迟期间（100-500ms）SurfaceFlinger 合成未初始化的透明/脏缓冲区。
        // 此处的 lockCanvas 同步等待 buffer queue 就绪，完成后即使后续渲染线程
        // 尚未启动，surface 也始终显示有效内容而非黑框。
        try {
            val clearCanvas = holder.lockCanvas()
            if (clearCanvas != null) {
                clearCanvas.drawColor(android.graphics.Color.BLACK)
                holder.unlockCanvasAndPost(clearCanvas)
            }
        } catch (e: Exception) {
            android.util.Log.w("NativeSurfaceView",
                "surfaceCreated: clear failed (non-fatal)", e)
        }
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, w: Int, h: Int) {
        if (!isReady && holder.surface == null) return

        if (!isReady) {
            if (initInProgress) return  // 防止重复调用
            initInProgress = true

            // 递增 Surface 版本，所有 post Runnable 通过此检测 stale 回调
            surfaceGeneration++
            val currentGen = surfaceGeneration

            // ★ 渲染模式预判：若策略要求 SOFTWARE 则直接走软件渲染
            if (useRenderMode == RenderMode.SOFTWARE) {
                android.util.Log.i("NativeSurfaceView",
                    "RenderMode.SOFTWARE (by policy) — starting software backend")

                // ★ 修复：同步清除 Surface，防止 emulator 上 Activity 切换导致的残留内容闪烁
                try {
                    val clearCanvas = holder.lockCanvas()
                    if (clearCanvas != null) {
                        clearCanvas.drawColor(android.graphics.Color.DKGRAY)
                        holder.unlockCanvasAndPost(clearCanvas)
                    }
                } catch (e: Exception) {
                    android.util.Log.w("NativeSurfaceView",
                        "SOFTWARE init: surface clear failed (non-fatal)", e)
                }

                pendingInit = true
                post {
                    // ★ 修复：检查 surfaceDestroyed 后 stale post 不执行
                    if (currentGen != surfaceGeneration) return@post
                    if (pendingInit && !isReady) {
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
                    initInProgress = false
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
                        post {
                            if (currentGen != surfaceGeneration) return@post
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
                            if (currentGen != surfaceGeneration) return@post
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
                        if (currentGen != surfaceGeneration) return@post
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
        // ★ 修复：递增 surfaceGeneration，使所有已 post 但未执行的
        // Vulkan init 回调被 currentGen != surfaceGeneration 守卫拦截
        surfaceGeneration++
        isReady = false
        initInProgress = false
        pendingInit = false
        // Layer 4: 中断正在执行的 VulkanInit 线程
        vulkanInitThread?.interrupt()
        vulkanInitThread = null
        // 中断渲染线程，加速从 Thread.sleep() 中退出
        renderThread?.interrupt()
        // 等待渲染线程安全停止后再释放资源
        // 使用绝对截止时间（2s）的轮询等待，而非固定 3x500ms 循环，
        // 防止 vkWaitForFences/vkAcquireNextImageKHR 阻塞超过预期时间时
        // Vulkan 资源在 RenderThread 仍在执行时被销毁 → use-after-free
        renderThread?.let { thread ->
            thread.running = false
            val deadlineNs = System.nanoTime() + 2_000_000_000L // 2s 截止时间
            var joined = false
            while (System.nanoTime() < deadlineNs) {
                try {
                    thread.join(200)
                    if (!thread.isAlive) { joined = true; break }
                } catch (_: InterruptedException) { break }
            }
            if (!joined) {
                android.util.Log.w("NativeSurfaceView",
                    "RenderThread did not stop after 2s deadline — proceeding with release")
            }
        }
        renderThread = null
        // ★ 修复：在 RenderThread 停止后显式释放 backend 资源
        softwareBackend?.release()
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

            var lastFrameNs = System.nanoTime()

            while (running && isReady) {
                val now = System.nanoTime()
                val elapsedNs = now - lastFrameNs

                // 每次迭代重算，支持 targetFps 运行时动态变化
                val fiNs = 1_000_000_000L / targetFps.coerceAtLeast(1)

                if (elapsedNs < fiNs) {
                    val sleepMs = (fiNs - elapsedNs) / 1_000_000
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
            val frame = currentFrame ?: return

            if (cameraDirty.compareAndSet(true, false)) {
                // ★ 使用独立相机通道，不依赖 frame.camX/Y（可能被帧率门控延迟）
                NativeBridge.setCamera(
                    renderCamX, renderCamY, renderScale, width, height)
            }

            NativeBridge.beginFrame()

            // ★ 从命令总线读取建筑数据快照（一次性读取，消除 TOCTOU 竞态）
            // 对标 UE ENQUEUE_RENDER_COMMAND：建筑变更即时送达，不依赖 Compose 重组时序
            val busSnapshot = commandBus?.consumeBuildingData()
            val effectiveBuildingData = busSnapshot?.data ?: frame.buildingData
            val effectiveBuildingCount = if (busSnapshot != null) {
                busSnapshot.count.coerceAtMost((busSnapshot.data?.size ?: 0) / 5)
            } else {
                frame.buildingCount
            }

            // 从 RenderFrame 读取瓦片数据 + SpriteAtlasDef 编译时常量
            if (atlasTextureId != 0) {
                NativeBridge.drawAllTiles(
                    tileData = frame.tileData,
                    cols = config.worldWidthCells,
                    rows = config.worldHeightCells,
                    buildingData = effectiveBuildingData,
                    buildingCount = effectiveBuildingCount,
                    buildingVisible = frame.buildingVisible,
                    tileSize = config.tileSize,
                    atlasTexId = atlasTextureId,
                    uvMap = SpriteAtlasDef.TILE_UV_MAP,
                    buildingUVMap = SpriteAtlasDef.BUILDING_UV_MAP,
                    floorTileUVMap = SpriteAtlasDef.FLOOR_TILE_UV_MAP
                )
            }

            if (frame.showPreview && atlasTextureId != 0) {
                NativeBridge.drawSprite(
                    frame.previewX, frame.previewY,
                    frame.previewW, frame.previewH,
                    atlasTextureId,
                    frame.previewU0, frame.previewV0,
                    frame.previewU1, frame.previewV1,
                    frame.previewTintRed, frame.previewTintGreen,
                    frame.previewTintBlue, frame.previewAlpha
                )
            }

            RenderMetrics.vulkanFrames.incrementAndGet()
            RenderMetrics.totalFrames.incrementAndGet()
            RenderMetrics.recordFrame()
            NativeBridge.submitFrame()
        }

        /** Canvas 软件渲染路径 */
        private fun renderSoftwareFrame() {
            val sb = softwareBackend ?: return
            val atlas = atlasBitmap ?: return
            val frame = currentFrame ?: return

            val startNs = System.nanoTime()

            // ★ 从命令总线读取建筑数据快照（与 Vulkan 路径一致，消除 TOCTOU 竞态）
            val busSnapshot = commandBus?.consumeBuildingData()
            val effectiveBuildingData = busSnapshot?.data ?: frame.buildingData
            val effectiveBuildingCount = if (busSnapshot != null) {
                busSnapshot.count.coerceAtMost((busSnapshot.data?.size ?: 0) / 5)
            } else {
                frame.buildingCount
            }

            // ★ 使用独立相机通道 + 建筑数据总线，合并覆盖 frame 中的值
            val mergedFrame = frame.copy(
                camX = renderCamX, camY = renderCamY, scale = renderScale,
                buildingData = effectiveBuildingData,
                buildingCount = effectiveBuildingCount
            )

            val rendered = try {
                sb.renderFrame(
                    frame = mergedFrame,
                    atlas = atlas,
                    vpW = this@NativeSurfaceView.width.coerceAtLeast(1),
                    vpH = this@NativeSurfaceView.height.coerceAtLeast(1)
                )
            } catch (e: Exception) {
                android.util.Log.e("NativeSurfaceView",
                    "renderSoftwareFrame failed: ${e.message}", e)
                RenderMetrics.renderFrameNull.incrementAndGet()
                null
            } catch (e: OutOfMemoryError) {
                android.util.Log.e("NativeSurfaceView",
                    "renderSoftwareFrame OOM: ${e.message}", e)
                RenderMetrics.renderFrameNull.incrementAndGet()
                Runtime.getRuntime().gc()
                null
            }

            if (rendered == null) {
                RenderMetrics.renderFrameNull.incrementAndGet()
                return
            }

            // ★ 优化：EWMA 帧时间追踪 + 动态帧率自适应
            // SOFTWARE 路径根据实际渲染能力动态调整 targetFps。
            // 注意：仅做降级（绝不提升），升帧由外部场景/热控 Flow 控制。
            // 使用 MIN + 严格小于语义避免写写竞争覆盖热控/场景帧率。
            val elapsedNs = System.nanoTime() - startNs
            val ewmaFps = sb.recordFrameTime(elapsedNs, System.currentTimeMillis())
            val capped = ewmaFps.coerceAtMost(targetFps)
            if (capped < targetFps && running) {
                targetFps = capped
            }

            RenderMetrics.softwareFrames.incrementAndGet()
            RenderMetrics.totalFrames.incrementAndGet()
            RenderMetrics.recordFrame()

            var retries = 3
            while (retries > 0) {
                if (!running || !isReady) break
                if (Thread.interrupted()) break
                try {
                    val surfaceCanvas = holder.lockCanvas() ?: run {
                        RenderMetrics.lockCanvasRetries.incrementAndGet()
                        retries--
                        // ★ 对抗性审查修复：continue 前检查退出标志，防止中断检测时序竞争
                        if (!running || !isReady || Thread.interrupted()) break
                        continue
                    }
                    surfaceCanvas.drawBitmap(rendered, 0f, 0f, null)
                    holder.unlockCanvasAndPost(surfaceCanvas)
                    break
                } catch (e: Exception) {
                    if (!running || !isReady) break
                    retries--
                    if (retries == 0) {
                        RenderMetrics.lockCanvasFailed.incrementAndGet()
                        android.util.Log.w("NativeSurfaceView",
                            "Software render: lockCanvas failed " +
                            "after 3 retries: ${e.message}")
                    } else {
                        android.util.Log.d("NativeSurfaceView",
                            "Software render: lockCanvas retry " +
                            "$retries: ${e.message}")
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
 * RenderFrame — 渲染管线唯一数据契约。
 *
 * 由 Compose 层通过 [NativeSurfaceView.updateRenderState] 批量写入，
 * Vulkan 和 Canvas 两路径均消费同一份 [RenderFrame]，杜绝数据不同步。
 *
 * ## 设计原则
 * - [tileData] 非 null：编译期强制调用方传入，NullPointerException 将
 *   在 [updateRenderState] 入口尽早抛出，而非等到渲染线程静默画 DKGRAY
 * - [cols]/[rows]：瓦片矩阵尺寸，用于 [tileData] 完整性验证
 * - uvMap/buildingUVMap 不在此处传递：Vulkan 和 Canvas 两后端均从
 *   [SpriteAtlasDef] 编译时常量读取，无需帧级数据传递
 */
data class RenderFrame(
    /** 瓦片类型数据（展平一维，index = row * cols + col）非 null */
    val tileData: IntArray,
    /** 地图列数（世界格数） */
    val cols: Int,
    /** 地图行数（世界格数） */
    val rows: Int,

    /** 建筑数据 [gx, gy, w, h, nameIdx] × N（可选，无建筑时为 null） */
    val buildingData: FloatArray? = null,
    val buildingCount: Int = 0,
    val buildingVisible: Boolean = true,

    // 相机
    val camX: Float = 0f,
    val camY: Float = 0f,
    val scale: Float = 1f,

    // 预览覆盖层（建造/移动模式）
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
