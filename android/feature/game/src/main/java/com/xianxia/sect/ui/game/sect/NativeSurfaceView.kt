package com.xianxia.sect.ui.game.sect

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import kotlin.concurrent.thread
import android.view.MotionEvent
import android.view.Surface
import android.view.SurfaceView
import com.xianxia.sect.feature.game.R
import com.xianxia.sect.core.animation.FadeTransition
import com.xianxia.sect.core.nativebridge.NativeBridge
import com.xianxia.sect.core.platform.SurfaceEventListener
import com.xianxia.sect.core.platform.SurfaceProvider
import com.xianxia.sect.core.render.FrameDropPolicy
import com.xianxia.sect.core.render.NativeRenderConfig
import com.xianxia.sect.core.render.RenderBackend
import com.xianxia.sect.core.render.RenderFrame
import com.xianxia.sect.core.render.RenderMetrics
import com.xianxia.sect.core.touch.SectMapTouchEngine
import com.xianxia.sect.core.touch.TouchAction
import com.xianxia.sect.core.touch.TouchData
import java.util.concurrent.atomic.AtomicBoolean

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
 *
 * ## 平台解耦（2026-08-13 重构）
 * surface 生命周期事件经 [SurfaceProvider] 消费（默认 [AndroidSurfaceProvider]）：
 * 宿主不再直接实现 SurfaceHolder.Callback——平台回调翻译（创建+初始尺寸合并 /
 * 尺寸变化 / 销毁）与生命周期防御（纪元防 stale、首帧清除、10s 初始化超时安全网）
 * 全部下沉到 AndroidSurfaceProvider。本类仅剩：Compose 桥接 + 渲染线程编排 + 帧通道。
 * iOS 化时替换 SurfaceProvider 实现（Metal 等价物）即可，宿主零改动。
 *
 * 渲染线程模型不变：仍共用同一 RenderThread、VsyncGate、RenderCommandBus。
 */
class NativeSurfaceView(
    context: Context,
    private val config: NativeRenderConfig
) : SurfaceView(context) {

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
     * 强制指定渲染模式。在 surface 可用事件前设置生效。
     * - 模拟器/Vulkan 问题设备：设置为 SOFTWARE 跳过 Vulkan 初始化
     * - 正常设备：保持 VULKAN（默认）
     */
    var useRenderMode: RenderMode = RenderMode.VULKAN

    /** 软件渲染后端（仅 [RenderMode.SOFTWARE] 时非空） */
    private var softwareBackend: SoftwareCanvasBackend? = null

    /** 软件渲染后端访问器（质量/装饰降级接线用；仅 SOFTWARE 模式非空） */
    val softwareRenderer: SoftwareCanvasBackend? get() = softwareBackend

    /**
     * 当前激活的渲染后端（[RenderBackend] 统一入口，iOS Metal 迁移点）。
     * 由 surface 可用事件按渲染模式创建，渲染线程每帧调用；
     * 销毁事件中 release 并置空。
     */
    private var activeBackend: RenderBackend? = null

    /** 渲染配置访问器（供同包渲染后端适配器读取世界尺寸） */
    internal val renderConfig: NativeRenderConfig get() = config

    /** 渲染线程 */
    private var renderThread: RenderThread? = null

    /** 是否正在初始化（防止 surface 可用事件重复触发导致并发 init） */
    @Volatile
    private var initInProgress: Boolean = false

    /**
     * 是否有等待中的 post 初始化。
     * 防止 surface 销毁后 stale post 回调执行导致竞态。
     */
    @Volatile
    private var pendingInit: Boolean = false

    /** VulkanInit 后台线程引用，供 surface 销毁时中断取消 */
    @Volatile
    private var vulkanInitThread: Thread? = null

    /**
     * 目标帧率。0 = 跟随系统 VSYNC（不主动 sleep）。
     * 设置为正整数可固定帧率，节省电量。
     */
    @Volatile
    var targetFps: Int = 10

    /**
     * 显示刷新率提供器（WP5 vsync 帧节奏；测试可注入固定值）。
     * iOS 对等：CADisplayLink.maximumFramesPerSecond。
     */
    var displayFpsProvider: DisplayFpsProvider = SystemDisplayFpsProvider(context)

    /** 自适应帧率追踪器（EWMA，VULKAN/SOFTWARE 双路径统一挂载） */
    private val adaptiveFpsTracker = AdaptiveFpsTracker()

    /**
     * 实际达成帧率回调（渲染线程每秒回调一次）。
     * 供引擎热控帧率驱动降级使用；回调异常在渲染线程内吞掉（渲染线程任何异常都会杀死渲染）。
     */
    @Volatile
    var onObservedFps: ((Float) -> Unit)? = null

    /** 已声明给系统的帧率（[maybeDeclareFrameRate]，≤30fps 降频声明 + 回升恢复声明） */
    @Volatile
    private var lastDeclaredFrameRate = 0

    /**
     * 渲染质量转发目标（测试可注入 Fake 断言转发序列）。
     * 默认推送到 C++ 热控全局量（NativeBridge.setRenderQuality）；
     * nativeReady 守卫：仅 Vulkan 渲染器就绪后转发（native 库已加载）。
     * 就绪前触发的转发由 [pushRenderQuality] 在 surface 初始化完成后补发，
     * 防止 surface 重建（shutdownRenderer 重置 C++ 全局量）后热控状态丢失。
     */
    var renderQualitySink: (qualityFactor: Float, decorationsDisabled: Boolean) -> Unit =
        { qualityFactor, decorationsDisabled ->
            if (isReady && renderMode == RenderMode.VULKAN) {
                NativeBridge.setRenderQuality(qualityFactor, decorationsDisabled)
            }
        }

    /**
     * ASTC 压缩图集加载+上传目标（WP7，测试可注入 Fake 断言回退链）。
     * 默认：assets 读 KTX 字节 → [NativeBridge.uploadCompressedAtlas]（KtxLoader
     * 全字段校验 + Vulkan ASTC 上传，单次 readBytes 无二次拷贝）；
     * 返回 0 = 资产缺失/IO 异常/校验失败/设备不支持 → 回退 RGBA 图集。
     */
    var compressedAtlasLoader: (android.content.Context) -> Int = { ctx ->
        try {
            val bytes = ctx.assets.open(ASTC_ATLAS_ASSET_PATH).use { it.readBytes() }
            NativeBridge.uploadCompressedAtlas(bytes)
        } catch (t: Throwable) {
            android.util.Log.e("NativeSurfaceView", "ASTC atlas load failed, fallback to RGBA", t)
            0
        }
    }

    /**
     * 渲染质量因子转发（MainGameScreen 接线；backend 创建后立即应用，防初始发射丢失）。
     * Compose 线程写、渲染线程读，@Volatile 保证可见性。
     * 转发时携带当前装饰标志（单边 setter 触发时 C++ 状态保持完整）。
     */
    @Volatile
    var renderQualityFactor: Float = 1.0f
        set(value) {
            field = value
            softwareBackend?.qualityFactor = value
            renderQualitySink(value, renderDecorationsDisabled)
        }

    /** 装饰层关闭标志转发（语义同上，转发时携带当前质量因子） */
    @Volatile
    var renderDecorationsDisabled: Boolean = false
        set(value) {
            field = value
            softwareBackend?.decorationsDisabled = value
            renderQualitySink(renderQualityFactor, value)
        }

    /** 统一创建软件渲染后端（应用当前质量/装饰值，防 surface 重建后丢失降级状态） */
    private fun createSoftwareBackend(): SoftwareCanvasBackend =
        SoftwareCanvasBackend(config).apply {
            qualityFactor = renderQualityFactor
            decorationsDisabled = renderDecorationsDisabled
        }

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
     * 是否应尝试 ASTC 压缩图集（WP7 分支决策：Vulkan 路径且开关开启）。
     * 独立纯函数供守卫测试锁定分支逻辑（完整 buildAtlas 的 RGBA 上传为 native 调用，
     * JVM 测试无法覆盖——由真机验证）。
     */
    internal fun shouldTryCompressedAtlas(): Boolean =
        renderMode != RenderMode.SOFTWARE && config.renderFlags.textureCompression

    /**
     * ASTC 压缩图集尝试入口：分支决策 + 加载上传（返回 0 = 回退 RGBA 信号）。
     * 独立供守卫测试注入 loader 断言调用与返回值语义。
     */
    internal fun tryCompressedAtlas(context: android.content.Context): Int =
        if (shouldTryCompressedAtlas()) compressedAtlasLoader(context) else 0

    fun buildAtlas(context: android.content.Context): Int {
        // Vulkan 路径优先 ASTC 压缩图集（WP7，16MB RGBA → 4MB ASTC）——成功则跳过
        // 运行时逐精灵解码+拼装（启动更快）。开关关闭/设备不支持/资产损坏 → 回退
        // RGBA 路径，视觉零差异仅 GPU 显存差异。
        val compressedId = tryCompressedAtlas(context)
        if (compressedId != 0) {
            android.util.Log.i(
                "NativeSurfaceView",
                "buildAtlas: ASTC compressed atlas uploaded (id=$compressedId)"
            )
            return compressedId
        }

        val atlas: android.graphics.Bitmap
        try {
            atlas = SectAtlasAssembler.buildAtlasBitmap(context)
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

        // Vulkan 回退路径：上传到 GPU
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
        // ★ 不调 recycle()：atlas 仍在 atlasBitmap 字段引用，调 recycle()
        //   会导致国产 ROM (#11008) double-free。Vulkan 模式下 atlasBitmap
        //   不会被 SOFTWARE 路径读取（renderMode 非 SOFTWARE），置 null 即可
        atlasBitmap = null
        return texId
    }

    companion object {
        /** 每秒纳秒数 */
        private const val NANOS_PER_SECOND = 1_000_000_000L
        /** 面板降频声明阈值（fps）：≤30 声明省屏耗，>30 不主动降 */
        private const val FPS_DECLARE_THRESHOLD = 30
        /** 帧率下限保护（防除零与非法值） */
        private const val MIN_FPS_VALUE = 1
        /** 显示刷新率兜底（provider 未知/异常返回 0 时按 60Hz 兜底，防 1Hz 慢渲染） */
        private const val DEFAULT_DISPLAY_FPS = 60
        /** ASTC 压缩图集资产路径（WP7，scripts/build-atlas.mjs 产物） */
        private const val ASTC_ATLAS_ASSET_PATH = "atlas/atlas_astc.ktx"
        /** 渲染线程停止等待截止（纳秒）：2s 绝对截止轮询（防 vk 调用阻塞时资源释放竞态） */
        private const val JOIN_DEADLINE_NS = 2_000_000_000L
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

    // ── 地图淡入过渡（WP4，仿独立相机通道：渲染线程每帧计算） ──

    /** 淡入开始时间戳（System.nanoTime；由 [fadeIn] 重置） */
    @Volatile
    private var renderFadeStartNs: Long = 0L

    /** 淡入总时长（纳秒，[fadeIn] 设置） */
    @Volatile
    private var renderFadeDurationNs: Long = FadeTransition.DEFAULT_DURATION_MS * 1_000_000L

    /**
     * 触发地图淡入（幂等——仅重置起始时间戳，重复调用无害）。
     * 由 RenderThread 每次启动时调用：覆盖首次进入 / surface 重建重入 /
     * Vulkan 降级三条初始化路径，渲染线程每帧经 [fadeAlpha] 计算当前 alpha。
     */
    fun fadeIn(durationMs: Long = FadeTransition.DEFAULT_DURATION_MS) {
        renderFadeStartNs = System.nanoTime()
        renderFadeDurationNs = durationMs * 1_000_000L
    }

    /**
     * 当前淡入 alpha（0-1，EaseOutCubic）。
     * 纯时钟驱动纯函数（[FadeTransition.alphaAt]）——每帧独立计算，无累积误差，
     * 热控降帧（10fps 挂机档）下淡入时长按墙钟精确。
     * 渲染线程每帧读取并推送到双端（Vulkan=setFadeAlpha / Canvas=合成 paint.alpha）。
     */
    val fadeAlpha: Float
        get() = FadeTransition.alphaAt(
            System.nanoTime() - renderFadeStartNs, renderFadeDurationNs)

    /** 从 Compose 层原子更新渲染帧数据 */
    fun updateRenderState(frame: RenderFrame) {
        // 尺寸不匹配时不更新 currentFrame（先校验后赋值——校验前置防坏帧污染：
        // 渲染线程读取 currentFrame 后 SoftwareCanvasBackend ChunkTile.rebuild
        // 会 ArrayIndexOutOfBoundsException，被渲染循环 catch 吞掉后永久黑屏）
        if (frame.tileData.size != config.worldWidthCells * config.worldHeightCells) {
            android.util.Log.e("NativeSurfaceView",
                "RenderFrame tileData size mismatch: ${frame.tileData.size} " +
                "vs expected ${config.worldWidthCells * config.worldHeightCells}")
            return
        }

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
    }

    /** 跨平台手势引擎 */
    var touchEngine: SectMapTouchEngine? = null

    // ============================================================
    // 平台 surface 事件（SurfaceProvider 抽象 — iOS 迁移点）
    // ============================================================

    /**
     * 平台 surface 事件监听器 — 由 [surfaceProvider] 派发（主线程同步）。
     * 各事件处理逻辑 = 原 SurfaceHolder.Callback 实现（2026-08-13 平台抽象重构，
     * 防御语义逐条保留）。
     */
    private val surfaceEventListener: SurfaceEventListener = HostSurfaceEventListener()

    /** 渲染初始化协调器（Vulkan/软件启动三函数内聚，2026-08-13 内类化） */
    private val initCoordinator = InitCoordinator()

    /**
     * 平台 surface 事件监听实现（2026-08-13 具名内类化——
     * 宿主函数数收敛，事件语义与原匿名对象逐字一致）。
     */
    private inner class HostSurfaceEventListener : SurfaceEventListener {
        override fun onSurfaceAvailable(width: Int, height: Int) {
            handleSurfaceAvailable()
        }

        override fun onSurfaceSizeChanged(width: Int, height: Int) {
            handleSurfaceSizeChanged(width, height)
        }

        override fun onSurfaceDestroyed() {
            handleSurfaceDestroyed()
        }

        override fun onSurfaceInitTimeout() {
            handleSurfaceInitTimeout()
        }
    }

    /**
     * 平台 surface 事件提供者 — 渲染宿主经此消费 surface 生命周期事件，
     * 与 Android SurfaceHolder.Callback 直接耦合剥离（iOS 化替换点）。
     *
     * 默认 [AndroidSurfaceProvider]（构造即注册平台回调）；
     * 外部（SectMapViewport 经 Hilt 工厂）可替换——替换时自动解绑旧监听器并绑定新实例。
     */
    var surfaceProvider: SurfaceProvider = AndroidSurfaceProvider(holder)
        set(value) {
            field.setEventListener(null)
            // 解除旧 provider 的平台回调注册（对抗性审查 2026-08-13 状态破坏者#6：
            // 旧实例残留 addCallback，同事件被双 provider 接收、genCounter 空转）
            field.unregister()
            field = value
            value.setEventListener(surfaceEventListener)
        }

    init {
        holder.setFormat(PixelFormat.RGBA_8888)
        // 属性初始化器不走 setter——默认实例需手动绑定监听器
        surfaceProvider.setEventListener(surfaceEventListener)
        // 必须设置 clickable 才能接收触摸事件
        isClickable = true
        isFocusableInTouchMode = true
    }

    // ============================================================
    // Surface 事件处理（经 SurfaceProvider 派发；= 原 SurfaceHolder.Callback 逻辑）
    // ============================================================

    /**
     * 表面可用（含初始尺寸）— 初始化渲染器（= 原 surfaceChanged 初始化分支）。
     *
     * 注意：初始化使用 View 布局尺寸（[width]/[height]），与重构前一致；
     * provider 传入的 surface 尺寸仅供 resize 路径（[handleSurfaceSizeChanged]）。
     */
    private fun handleSurfaceAvailable() {
        // 防御保留：无有效 surface 句柄不初始化（原 `if (!isReady && holder.surface == null)
        // return` 语义——某些 ROM/时序下可用事件可能早于物理 surface 就绪；
        // Robolectric 下 holder.surface 恒 null → 安全 no-op 路径）。
        // provider.isSurfaceValid 为第二道守卫（provider 仅 ACTIVE 状态派发本事件，双保险）；
        // isReady/initInProgress 防重复初始化（Vulkan init 成功/超时降级回调均经 isReady 守卫拦截）
        val canInit = holder.surface != null && surfaceProvider.isSurfaceValid && !isReady && !initInProgress
        if (!canInit) return
        initInProgress = true

        // 对抗性审查修复：新 surface 重置帧率声明与 EWMA 状态——
        // 旋转/重建后 lastDeclaredFrameRate 残留会阻止新 surface 降频声明，
        // 旧 EWMA 残留会导致新渲染线程首帧即被误判低帧率
        lastDeclaredFrameRate = 0
        adaptiveFpsTracker.reset()

        // 捕获纪元：所有异步回调（post）通过此值检测跨 surface stale
        val currentGen = surfaceProvider.generation

        // ★ 渲染模式预判：若策略要求 SOFTWARE 则直接走软件渲染
        if (useRenderMode == RenderMode.SOFTWARE) {
            initCoordinator.startSoftwareBackend(currentGen)
        } else {
            initCoordinator.startVulkanInit(currentGen)
        }
    }

    /**
     * 尺寸变化（可用后非首次）— 统一由后端适配器处理
     * （Vulkan=resizeRenderer / Canvas=视口重建）。
     *
     * @param width 新宽度（像素）
     * @param height 新高度（像素）
     */
    private fun handleSurfaceSizeChanged(width: Int, height: Int) {
        activeBackend?.resize(width, height)
    }

    /**
     * 表面销毁 — 停止渲染线程、释放后端并清空 surface 关联资源
     * （= 原 surfaceDestroyed；纪元递增由 provider 完成）。
     */
    private fun handleSurfaceDestroyed() {
        isReady = false
        initInProgress = false
        pendingInit = false
        // Layer 4: 中断正在执行的 VulkanInit 线程
        vulkanInitThread?.interrupt()
        vulkanInitThread = null
        // 中断渲染线程，加速从 Thread.sleep() 中退出
        renderThread?.interrupt()
        // 等待渲染线程安全停止后再释放资源
        stopRenderThread()
        renderThread = null
        activeBackend = null
        softwareBackend = null
        // 对抗性审查修复：surface 销毁后清纹理引用——重建后 buildAtlas 若失败
        // （OOM/资产损坏）残留旧 GPU 纹理 ID 会提交已销毁纹理（C++ 查表未命中
        // 回退白纹 → 地图全白）；清零后 Vulkan 侧 atlasTextureId==0 守卫跳过瓦片层
        atlasTextureId = 0
        // 注：atlasBitmap 禁止 recycle()（国产 ROM double-free 教训），置 null 让 GC
        atlasBitmap = null
    }

    /**
     * 等待渲染线程安全停止（2s 绝对截止的轮询，而非固定 3x500ms 循环），
     * 防止 vkWaitForFences/vkAcquireNextImageKHR 阻塞超过预期时间时 Vulkan
     * 资源在 RenderThread 仍在执行时被销毁 → use-after-free。
     * 仅在确认停止后 release backend（Vulkan=shutdownRenderer / Canvas=canvas release）。
     */
    private fun stopRenderThread() {
        val thread = renderThread ?: return
        thread.running = false
        val deadlineNs = System.nanoTime() + JOIN_DEADLINE_NS
        var joined = false
        while (System.nanoTime() < deadlineNs) {
            try {
                thread.join(200)
                if (!thread.isAlive) { joined = true; break }
            } catch (_: InterruptedException) { break }
        }
        if (!joined) {
            android.util.Log.w("NativeSurfaceView",
                "RenderThread did not stop after 2s deadline — " +
                "skipping backend release (thread may be blocked in vk call; " +
                "releasing now would delete g_renderer under it → use-after-free). " +
                "Resources are rebuilt by next initRenderer (device-ready → initSurface)")
        } else {
            // ★ 修复：仅在渲染线程确认停止后释放 backend 资源。
            //   统一经 RenderBackend 适配器释放（Vulkan=shutdownRenderer / Canvas=canvas release）
            activeBackend?.release()
        }
    }

    /**
     * 渲染初始化协调器（2026-08-13 内类化——Vulkan/软件初始化启动三函数
     * 职责内聚，NativeSurfaceView 顶层函数数收敛；语义与原顶层函数逐字一致）。
     */
    private inner class InitCoordinator {

        /** surface 重建（shutdownRenderer 重置 C++ 全局量）后重放当前热控状态 */
        fun pushRenderQuality() {
            renderQualitySink(renderQualityFactor, renderDecorationsDisabled)
        }

        /**
         * SOFTWARE 策略路径 — 直接启动软件渲染（= 原 surfaceChanged SOFTWARE 分支）。
         *
         * @param currentGen 发起时的 surface 纪元（post 回调 stale 守卫）
         */
        fun startSoftwareBackend(currentGen: Int) {
            android.util.Log.i("NativeSurfaceView",
                "RenderMode.SOFTWARE (by policy) — starting software backend")

            // ★ 修复：同步清除 Surface，防止 emulator 上 Activity 切换导致的残留内容闪烁
            surfaceProvider.clearSurface(android.graphics.Color.DKGRAY)

            pendingInit = true
            post {
                // ★ 修复：检查 surfaceDestroyed 后 stale post 不执行
                if (currentGen != surfaceProvider.generation) return@post
                if (pendingInit && !isReady) {
                    // 先设置渲染模式和软件后端，再通知上层上传纹理
                    // 注意：buildAtlas() 依赖 renderMode 判断是否回收 Bitmap，
                    // 必须在 onRendererReady 之前设置，否则图集 Bitmap 被误回收
                    softwareBackend = createSoftwareBackend()
                    activeBackend = SoftwareRenderBackend(this@NativeSurfaceView)
                    renderMode = RenderMode.SOFTWARE
                    // 通知 Compose 层上传纹理（TextureAtlas 已在 surface 可用事件中 init）
                    onRendererReady?.invoke()
                    isReady = true
                    renderThread = RenderThread().also { it.start() }
                }
                initInProgress = false
            }
        }

        /**
         * VULKAN 路径 — 启动异步初始化（= 原 surfaceChanged VULKAN 分支）。
         * 初始化在独立线程执行，成功/失败/异常均 post 回主线程（经纪元守卫）。
         *
         * @param currentGen 发起时的 surface 纪元（post 回调 stale 守卫）
         */
        fun startVulkanInit(currentGen: Int) {
            // 原 surfaceCreated 语义：VULKAN 模式加载 native 库与纹理图集
            //（SOFTWARE 模式完全使用 Canvas 渲染，不加载 native 库——
            // 策略预判路径在 handleSurfaceAvailable 已分流，不会到达此处）
            NativeBridge.ensureLoaded()
            NativeBridge.initAtlas()

            val surface = holder.surface ?: return

            // 初始化超时安全网（10 秒）：超时降级完整初始化软件后端（而非只置
            // isReady——否则 Vulkan init 成功回调被 isReady 守卫拦截，渲染线程永不
            // 启动 → 永久黑屏）。纪元守卫在 provider 内部拦截 surfaceDestroyed 后
            // 残留的 stale 超时回调（防跨 surface 误置状态）。
            surfaceProvider.startInitTimeout()

            // Layer 4: 取消之前的初始化线程（如有），防止竞态
            vulkanInitThread?.interrupt()
            vulkanInitThread = null

            // 捕获视口尺寸传入线程体（对抗性审查 2026-08-13 状态破坏者#7：
            // 后台线程读主线程维护的 View 字段属数据竞争——与 currentGen 同模式）
            val viewportW = width
            val viewportH = height
            vulkanInitThread = kotlin.concurrent.thread(name = "VulkanInit") {
                runVulkanInitThread(currentGen, surface, viewportW, viewportH)
            }
        }

        /** Vulkan 初始化线程体（独立线程；post 回主线程前先做纪元守卫） */
        // 本函数是原生初始化崩溃的唯一归因入口：Throwable 全捕获 + 分型降级
        //（中断=surface 销毁不降级/其他异常降级）是崩溃防御设计本身
        @Suppress("TooGenericExceptionCaught")
        private fun runVulkanInitThread(currentGen: Int, surface: Surface, viewportW: Int, viewportH: Int) {
            try {
                val initStart = System.currentTimeMillis()

                // Layer 2: Phase 2 写前标记 — initRenderer 前写入
                vulkanInitListener?.onSurfaceInitStarted()

                val ok = NativeBridge.initRenderer(
                    viewportW = viewportW,
                    viewportH = viewportH,
                    worldW = config.worldPixelWidth,
                    worldH = config.worldPixelHeight,
                    tileSize = config.tileSize,
                    surface = surface
                )

                if (ok) {
                    post { handleVulkanInitSuccess(currentGen) }
                } else {
                    handleVulkanInitFailure(initStart, currentGen)
                }
            } catch (t: Throwable) {
                handleVulkanInitCrash(currentGen, t)
            }
        }

        /**
         * 中断并等待旧 Vulkan init 线程退出（带截止，仿 [stopRenderThread] 风格）。
         * 未在截止内退出时放弃等待（interrupt 已发出，C++ 侧 init 失败即返回）。
         */
        fun interruptAndJoinVulkanInitThread() {
            val thread = vulkanInitThread ?: return
            thread.interrupt()
            val deadlineNs = System.nanoTime() + JOIN_DEADLINE_NS
            var alive = thread.isAlive
            while (alive && System.nanoTime() < deadlineNs) {
                alive = try {
                    thread.join(200)
                    thread.isAlive
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    false
                }
            }
        }
    }

    /** Vulkan 初始化成功（post 回主线程；= 原成功分支） */
    private fun handleVulkanInitSuccess(currentGen: Int) {
        if (currentGen != surfaceProvider.generation) return
        surfaceProvider.notifyInitCompleted()
        initInProgress = false
        vulkanInitThread = null
        if (isReady) return

        // 先上传纹理（地面 + 图集），再启动渲染线程
        onRendererReady?.invoke()

        isReady = true
        // ★ 热控状态补发：shutdownRenderer 重置了 C++ 全局量，
        //   此处把 Kotlin 侧当前值（可能已热控降级）重放到 C++
        initCoordinator.pushRenderQuality()
        activeBackend = VulkanRenderBackend(this)
        renderThread = RenderThread().also { it.start() }
    }

    /** Vulkan 初始化失败（initRenderer 返回 false；线程内记录，post 回主线程降级） */
    private fun handleVulkanInitFailure(initStart: Long, currentGen: Int) {
        // Layer 2: 失败 → 清除写前标记 + 记录持久化失败
        vulkanInitListener?.onSurfaceInitFailed()

        android.util.Log.e("NativeSurfaceView",
            "Vulkan init failed after ${System.currentTimeMillis() - initStart}ms — " +
            "falling back to software renderer")

        post { handleVulkanInitFailurePost(currentGen) }
    }

    /**
     * Vulkan 初始化失败/异常后的主线程降级（= 原失败/异常分支 post 内容，
     * 两路径共用同一降级语义）。
     */
    private fun handleVulkanInitFailurePost(currentGen: Int) {
        if (currentGen != surfaceProvider.generation) return
        surfaceProvider.notifyInitCompleted()
        initInProgress = false
        vulkanInitThread = null
        if (!isReady) {
            // 降级到软件渲染（失败线程已自行返回，无需 join——
            // 状态破坏者#3 的并发窗口在超时降级路径，见 handleSurfaceInitTimeout）
            fallbackToSoftwareRenderer()
        }
    }

    /**
     * Vulkan 初始化异常（线程内捕获；中断 = surface 销毁不降级，其他异常降级）。
     */
    private fun handleVulkanInitCrash(currentGen: Int, t: Throwable) {
        // Layer 4: 线程被中断（surfaceDestroyed），不做降级
        if (t is InterruptedException || Thread.interrupted()) {
            android.util.Log.w("NativeSurfaceView",
                "Vulkan init interrupted — surface was destroyed")
            // ★ gen 守卫（对抗性审查 2026-08-13 状态破坏者补充发现）：旧纪元
            // 线程不得清空新纪元状态——destroy 后立即重建时，旧线程的中断处理
            // 迟到执行会清掉新 surface 的 vulkanInitThread 引用与 initInProgress
            if (currentGen != surfaceProvider.generation) return
            initInProgress = false
            vulkanInitThread = null
            return
        }
        // 其他异常（如 OOM），记录并降级
        android.util.Log.e("NativeSurfaceView",
            "Vulkan init crashed: ${t.message}", t)
        vulkanInitListener?.onSurfaceInitFailed()
        post { handleVulkanInitFailurePost(currentGen) }
    }

    /**
     * 完整初始化软件渲染（对齐降级路径语义）：后端 + 渲染线程 + isReady，
     * 后续 Vulkan init 成功/失败回调均被 isReady 守卫拦截。
     */
    private fun fallbackToSoftwareRenderer() {
        softwareBackend = createSoftwareBackend()
        activeBackend = SoftwareRenderBackend(this)
        renderMode = RenderMode.SOFTWARE
        onRendererReady?.invoke()
        isReady = true
        renderThread = RenderThread().also { it.start() }
    }

    /**
     * Vulkan 初始化超时（10s，provider 触发）— 降级软件渲染。
     * 纪元守卫在 provider 内部完成（跨 surface stale 超时不触发）。
     */
    private fun handleSurfaceInitTimeout() {
        if (!isReady) {
            initInProgress = false
            // 对抗性审查 2026-08-13 状态破坏者#3：超时降级时旧 Vulkan init
            // 线程仍在阻塞（超时正是因为 initRenderer 卡住）——必须 interrupt +
            // 短 join，否则该线程与新 surface 的 init 线程并发操作 C++ 无锁
            // 裸指针 g_renderer（SIGSEGV）
            initCoordinator.interruptAndJoinVulkanInitThread()
            // 完整初始化（对齐降级路径语义）：后端 + 渲染线程 + isReady，
            // 后续 Vulkan init 成功/失败回调均被 isReady 守卫拦截
            fallbackToSoftwareRenderer()
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

        /** 能力帧率上报限频时间戳（[reportObservedFps]） */
        private var lastFpsReportNs = 0L

        override fun run() {
            // ★ 地图淡入：渲染线程每次启动（= 每次 surface 初始化：首次进入/
            // 重入/降级路径）触发——覆盖所有初始化路径，天然幂等。
            // surface 可用后 C++ g_fadeAlpha 已由 shutdownRenderer 重置为 1，
            // 此处 fadeIn 重置起始时间戳，本帧起 alpha 从 0 淡入
            fadeIn()

            // 首帧快速清除 Surface 缓冲区，防止华为模拟器等设备上
            // SurfaceFlinger 未正确清除新分配缓冲区导致残留内容显示
            //（clearSurface 内部吞异常——非关键操作，失败不影响后续渲染）
            if (renderMode == RenderMode.SOFTWARE) {
                surfaceProvider.clearSurface(android.graphics.Color.BLACK)
            }

            // ★ WP5 vsync 帧节奏：Canvas 路径用 VsyncGate 对齐显示刷新率；
            // 初始化失败时 awaitTick 恒超时 → 循环回退 sleep 节拍（行为 = 现状）。
            // vsyncPacing=false（RenderFlags 开关）→ 完全旧路径
            val vsyncPacing = config.renderFlags.vsyncPacing
            val vsyncGate = if (vsyncPacing && renderMode == RenderMode.SOFTWARE) {
                VsyncGate()
            } else {
                null
            }
            try {
                renderLoop(vsyncPacing, vsyncGate)
            } finally {
                vsyncGate?.release()
            }
        }

        /**
         * 渲染主循环（WP5 重构）。
         *
         * ## 帧节奏
         * - vsyncPacing=true：以显示刷新率为节拍 + [FrameDropPolicy] 帧跳过——
         *   全速档（step=1）Canvas 走 VsyncGate vsync 对齐 / Vulkan 走节拍 sleep
         *   （FIFO 交换链天然 vsync 对齐提交）；降帧档（step>1）整体 sleep 到
         *   渲染间隔（省电：低帧率时 vsync 对齐收益小，减少唤醒次数）
         * - vsyncPacing=false：旧 sleep 限速路径（行为与改造前逐字节一致）
         *
         * 每次迭代重算 step/interval——热控升降帧即时生效，无状态累积。
         */
        private fun renderLoop(vsyncPacing: Boolean, vsyncGate: VsyncGate?) {
            var lastFrameNs = System.nanoTime()
            // 有效帧率 = min(外部目标帧率, EWMA 渲染能力帧率)；
            // 起始用 targetFps（外部流尚未到达时的默认 10）
            var effectiveFps = targetFps.coerceAtLeast(MIN_FPS_VALUE)

            var tick = 0L

            while (running && isReady) {
                val now = System.nanoTime()
                val elapsedNs = now - lastFrameNs
                // 每次迭代重算帧节奏——热控升降帧即时生效，无状态累积
                val pacing = computeFramePacing(vsyncPacing, effectiveFps)

                if (elapsedNs < pacing.intervalNs) {
                    if (!waitForNextTick(pacing.intervalNs - elapsedNs, pacing.step, vsyncGate)) return
                    continue
                }
                lastFrameNs = now
                tick++
                if (tick % pacing.step.toLong() != 0L) continue

                // ★ 统一渲染入口：RenderBackend 抽象（VULKAN/SOFTWARE 分支已收敛到
                //   surface 初始化创建处），渲染循环只面向接口——iOS Metal 后端
                //   实现同一接口即可接入，循环零改动
                val renderElapsedNs = renderTick()

                // ★ 统一 EWMA 渲染能力追踪（VULKAN/SOFTWARE 双路径一致）。
                // 关键设计：**不写回 targetFps**——渲染线程内部维护 effectiveFps =
                // min(targetFps, ewmaFps)，避免"只降不升 + StateFlow 不重发"钉死竞态；
                // 外部升帧（场景/模式/热控变化）始终即时生效，EWMA 能力恢复自动回升。
                val ewmaFps = adaptiveFpsTracker.recordFrameTime(renderElapsedNs, System.currentTimeMillis())
                effectiveFps = minOf(targetFps.coerceAtLeast(MIN_FPS_VALUE), ewmaFps)

                // 上报渲染能力帧率（供热控帧率驱动降级；能力帧率 ≠ 墙钟帧率，
                // 主动省电降帧（IDLE 10fps）不误判为渲染能力不足）
                reportObservedFps(now, ewmaFps)

                // 帧率变化后向系统声明（降频省屏耗 + 回升恢复，防面板粘滞）
                maybeDeclareFrameRate(effectiveFps)
            }
        }

        /**
         * 本迭代帧节奏参数（WP5 重构提取）。
         *
         * - vsyncPacing=true：以显示刷新率为节拍 + [FrameDropPolicy] 帧跳过——
         *   全速档（step=1）Canvas 走 VsyncGate vsync 对齐 / Vulkan 走节拍 sleep
         *   （FIFO 交换链天然 vsync 对齐提交）；降帧档（step>1）整体 sleep 到
         *   渲染间隔（省电：低帧率时 vsync 对齐收益小，减少唤醒次数）
         * - vsyncPacing=false：旧 sleep 限速路径（行为与改造前逐字节一致）
         */
        private fun computeFramePacing(vsyncPacing: Boolean, effectiveFps: Int): FramePacing {
            val displayFps = if (vsyncPacing) {
                // 未知/异常（provider 返回 0）→ 60Hz 兜底，防节拍稀化为 1Hz
                displayFpsProvider.displayFps().takeIf { it > 0 } ?: DEFAULT_DISPLAY_FPS
            } else {
                MIN_FPS_VALUE
            }
            val step = if (vsyncPacing) {
                FrameDropPolicy.tickStep(displayFps, effectiveFps)
            } else {
                1
            }
            // 全速档（step≤1）按显示节拍；降帧档按有效帧率间隔（省唤醒）
            val intervalNs = if (!vsyncPacing || step > 1) {
                NANOS_PER_SECOND / effectiveFps.coerceAtLeast(MIN_FPS_VALUE)
            } else {
                NANOS_PER_SECOND / displayFps
            }
            return FramePacing(displayFps = displayFps, step = step, intervalNs = intervalNs)
        }

        /**
         * 节拍等待（vsync 对齐 / sleep 兜底）。
         *
         * @return false = 线程中断（调用方退出循环；行为与改造前 catch 返回一致）
         */
        private fun waitForNextTick(waitNs: Long, step: Int, vsyncGate: VsyncGate?): Boolean {
            if (step <= 1 && vsyncGate != null) {
                // Canvas 全速档：vsync 对齐等待（超时 = 节拍 sleep 兜底，
                // 不会忙循环——awaitTick 内部阻塞 timeoutMs）
                vsyncGate.awaitTick(waitNs / 1_000_000 + 1)
                return true
            }
            return sleepSafely(waitNs / 1_000_000)
        }

        /** sleep 节拍兜底（vsync 不可用/降帧档）。@return false = 线程中断（调用方退出循环） */
        private fun sleepSafely(sleepMs: Long): Boolean {
            if (sleepMs <= 1) return true
            return try {
                Thread.sleep(sleepMs)
                true
            } catch (_: InterruptedException) {
                false
            }
        }

        /**
         * 单帧渲染：相机脏标记推送 + 后端渲染 + 异常统一捕获。
         *
         * @return 渲染耗时（纳秒，EWMA 能力帧率追踪用）
         */
        private fun renderTick(): Long {
            val frameStartNs = System.nanoTime()
            val backend = activeBackend
            val frame = currentFrame
            if (backend != null && frame != null) {
                if (cameraDirty.compareAndSet(true, false)) {
                    // 独立相机通道（不经过 RenderFrame 帧率门控）
                    backend.setCamera(
                        renderCamX, renderCamY, renderScale, width, height)
                }
                try {
                    backend.renderFrame(
                        frame, width.coerceAtLeast(1), height.coerceAtLeast(1))
                } catch (e: OutOfMemoryError) {
                    android.util.Log.e("NativeSurfaceView",
                        "renderFrame OOM: ${e.message}", e)
                    RenderMetrics.renderFrameNull.incrementAndGet()
                    Runtime.getRuntime().gc()
                } catch (e: Exception) {
                    android.util.Log.e("NativeSurfaceView",
                        "renderFrame failed: ${e.message}", e)
                    RenderMetrics.renderFrameNull.incrementAndGet()
                }
            }
            return System.nanoTime() - frameStartNs
        }

        /**
         * 每秒上报渲染能力帧率（EWMA 反推，非墙钟帧率——挂机主动降帧时
         * 渲染能力仍高，不应触发热控降级）。回调异常吞掉（渲染线程任何异常都会杀死渲染）。
         */
        private fun reportObservedFps(nowNs: Long, ewmaFps: Int) {
            if (nowNs - lastFpsReportNs < NANOS_PER_SECOND) {
                return
            }
            lastFpsReportNs = nowNs
            onObservedFps?.let { listener ->
                try {
                    listener(ewmaFps.toFloat())
                } catch (e: Exception) {
                    android.util.Log.w("NativeSurfaceView", "onObservedFps failed: ${e.message}", e)
                }
            }
        }

        /**
         * Surface.setFrameRate（API 30+）：有效帧率变化时向系统声明，让高刷面板
         * 匹配刷新率（屏幕功耗是持续大头，60Hz vs 120Hz 差约 50% 屏耗）。
         *
         * 降频（≤30fps）声明省屏耗；回升时**恢复声明**防部分 OEM 面板粘滞在低刷新率
         * 造成 judder（"让系统自然恢复"在华为/小米等 ROM 上不成立）。
         * 渲染线程调用（surface 生命周期有效）；任何异常吞掉不杀死渲染线程。
         *
         * **iOS 对等**：`CADisplayLink.preferredFrameRateRange`（ProMotion 屏按
         * 内容帧率降刷新率）——无黑屏切换问题，直接声明目标帧率即可。
         */
        private fun maybeDeclareFrameRate(effectiveFps: Int) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
                return
            }
            val fps = effectiveFps
            if (fps != lastDeclaredFrameRate &&
                (fps <= FPS_DECLARE_THRESHOLD || lastDeclaredFrameRate > 0)
            ) {
                lastDeclaredFrameRate = fps
                try {
                    holder.surface.setFrameRate(fps.toFloat(), Surface.FRAME_RATE_COMPATIBILITY_DEFAULT)
                } catch (e: Exception) {
                    android.util.Log.w("NativeSurfaceView", "setFrameRate failed: ${e.message}", e)
                }
            }
        }

    }
}

/**
 * 帧节奏参数（displayFps/step/intervalNs——一次迭代的调度数据）。
 *
 * 注意：必须为顶层声明——K2 编译器禁止在 `inner class` 内声明 `data class`
 * （"Class is prohibited here"），[RenderThread] 为 inner class。
 */
private data class FramePacing(
    val displayFps: Int,
    val step: Int,
    val intervalNs: Long
)
