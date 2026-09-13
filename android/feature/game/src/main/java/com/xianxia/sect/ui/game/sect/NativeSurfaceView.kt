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
import com.xianxia.sect.core.render.RenderFallbackReporter
import com.xianxia.sect.core.render.RenderFrame
import com.xianxia.sect.core.render.RenderMetrics
import com.xianxia.sect.core.render.RenderScalePolicy
import com.xianxia.sect.core.render.SkyBackgroundConfig
import com.xianxia.sect.core.render.VulkanPrewarmState
import com.xianxia.sect.core.perf.GpuTier
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
 * 1. [RenderStrategy] 预判（API≥31 模拟器走 Vulkan；云游戏/安全模式走 SOFTWARE）
 * 2. Vulkan init 失败时自动降级到 SOFTWARE
 *
 * 在 Compose UI 中以 AndroidView 方式嵌入，作为宗门地图的渲染目标。
 *
 * ## 平台解耦
 * surface 生命周期事件经 [SurfaceProvider] 消费（默认 [AndroidSurfaceProvider]）：
 * 宿主不再直接实现 SurfaceHolder.Callback——平台回调翻译（创建+初始尺寸合并 /
 * 尺寸变化 / 销毁）与生命周期防御（纪元防 stale、首帧清除、10s 初始化超时安全网）
 * 全部下沉到 AndroidSurfaceProvider。本类仅剩：Compose 桥接 + 渲染线程编排 + 帧通道。
 * iOS 化时替换 SurfaceProvider 实现（Metal 等价物）即可，宿主零改动。
 *
 * 渲染线程模型不变：仍共用同一 RenderThread、VsyncGate、RenderCommandBus。
 */
// Lint 豁免：原生 SurfaceView 构造（ViewConstructor）——游戏渲染 surface 必须
// 直接持有 Android SurfaceView（Vulkan swapchain 载体），无 Compose 等价物
@Suppress("ViewConstructor")
class NativeSurfaceView(
    context: Context,
    /** internal：同包输入通道扩展（NativeSurfaceViewInputChannels）消费——TMF 收敛外移 */
    internal val config: NativeRenderConfig
) : SurfaceView(context) {

    // ============================================================
    // 渲染模式
    // ============================================================

    /** 渲染后端模式 */
    enum class RenderMode {
        /** GPU Vulkan 原生渲染（首选） */
        VULKAN,
        /** GPU OpenGL ES 中间层渲染（Vulkan 不可靠/失败时） */
        GLES,
        /** CPU Canvas 软件渲染（最终兜底） */
        SOFTWARE
    }

    /** 当前渲染模式（由 [useRenderMode] 或降级逻辑设置） */
    @Volatile
    private var renderMode: RenderMode = RenderMode.VULKAN

    /**
     * 强制指定渲染模式。在 surface 可用事件前设置生效。
     * - 模拟器/Vulkan 问题设备：设置为 SOFTWARE 跳过 Vulkan 初始化
     * - 正常设备：保持 VULKAN（默认）
     * - Vulkan 不可靠但 GPU 可用设备：设置为 GLES
     */
    var useRenderMode: RenderMode = RenderMode.VULKAN

    /** 本次初始化是否使用 GPU GLES 后端（runVulkanInitThread 前设定，成功回调读取）。
     *  internal：顶层 reportRenderFallback（同文件）需读取——TooManyFunctions 收敛纪律 */
    @Volatile
    internal var initBackendGles: Boolean = false

    /** Vulkan 初始化失败后是否已尝试 GPU GLES 中间层（防循环；优先 GLES 再软件兜底）。
     *  internal：顶层 reportRenderFallback（同文件）需读取——TooManyFunctions 收敛纪律 */
    @Volatile
    internal var glesTriedAfterVulkan: Boolean = false

    /** 本轮渲染初始化起点（回退事件 elapsedMs 用；runVulkanInitThread 每轮刷新）。internal 同上 */
    @Volatile
    internal var renderInitStartMs: Long = 0L

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
     * skip-release 纪元残留的 backend 引用：渲染线程 2s 截止
     * 未停时，不再只打日志跳过 release（旧实现注释自证的正是泄漏路径）——
     * 与 [stuckRenderThread] 成对保留，新纪元 init 派发前由
     * [resolveDeferredRelease] 统一收尾（join 成功补释放 / 截止降级 GLES）。
     */
    @Volatile
    private var deferredBackend: RenderBackend? = null

    /** skip-release 纪元卡住的渲染线程引用（与 [deferredBackend] 成对保留） */
    @Volatile
    private var stuckRenderThread: Thread? = null

    /**
     * 目标帧率。0 = 跟随系统 VSYNC（不主动 sleep）。
     * 设置为正整数可固定帧率，节省电量。
     * 初值 60（与 GameEngineCore StateFlow 默认 60 对齐）——避免渲染线程
     * 启动到外部目标帧率流到达之间以低帧率运行的窗口。
     */
    @Volatile
    var targetFps: Int = 60

    /**
     * 显示刷新率提供器（vsync 帧节奏；测试可注入固定值）。
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

    /** 等待防抖的帧率升档候选（0 = 无；30→60 回升 2s 稳定后执行，防频繁切刷新率黑屏） */
    private var declareCandidateFps = 0

    /** 防抖候选起始时间（System.currentTimeMillis） */
    private var declareCandidateSinceMs = 0L

    // ============================================================
    // 渲染分辨率缩放（RenderScalePolicy 决策 + 双端下发）
    // ============================================================

    /**
     * GPU 档位（由 GameViewModel 经 SectMapViewportParams 注入，
     * RenderScalePolicy.computeRenderScale 决策输入）。默认 MEDIUM（检测失败回退档）。
     */
    @Volatile
    var gpuTier: GpuTier = GpuTier.MEDIUM

    /**
     * 渲染分辨率缩放（RenderScalePolicy 决策值；Compose 线程写、渲染线程消费）。
     * 变化时：软件后端即时应用（帧缓冲尺寸在 renderFrame 自适应重建）；
     * Vulkan 经 pendingRenderScale 由渲染线程调 NativeBridge.setRenderScale
     * （重建离屏目标，语义同 resize）。
     */
    @Volatile
    var renderResolutionScale: Float = 1.0f
        set(value) {
            val safe = if (value.isFinite()) {
                value.coerceIn(RenderScalePolicy.MIN_RENDER_SCALE, RenderScalePolicy.MAX_RENDER_SCALE)
            } else {
                1.0f
            }
            if (safe == field) return
            field = safe
            softwareBackend?.renderScale = safe
            pendingRenderScale = safe
            softwareRenderScaleVersion++
        }

    /** Vulkan 待消费渲染缩放（渲染线程单消费者；= appliedRenderScale 表示已消费） */
    @Volatile
    private var pendingRenderScale: Float = 1.0f

    /** Vulkan 已应用的渲染缩放（渲染线程消费 pending 后写回） */
    @Volatile
    private var appliedRenderScale: Float = 1.0f

    /** 渲染缩放版本号（软件路径脏帧跳过信号：Compose 线程写、渲染线程对比） */
    @Volatile
    private var softwareRenderScaleVersion: Long = 0L

    /** 渲染线程已见的缩放版本（脏帧跳过对比基准） */
    private var lastRenderedScaleVersion: Long = 0L

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
     * ASTC 压缩图集**资产读取**目标（读取属 IO，在后台线程执行）。
     * 默认优先消费 [SectAtlasPrefetch] 预取缓存（GameActivity 进入时后台预读，
     * IO 已移出 surface 就绪后的关键路径）；无缓存时读
     * assets/atlas/atlas_astc.ktx 全字节；返回 null = 资产缺失/IO 异常
     * → 回退 RGBA 图集（不视为错误，不记失败指标）。
     */
    @Suppress("TooGenericExceptionCaught") // 资产读取失败即回退 RGBA 图集(不视为错误), 异常类型不可枚举
    var compressedAtlasReader: (android.content.Context) -> ByteArray? = { ctx ->
        SectAtlasPrefetch.consume() ?: try {
            ctx.assets.open(SectAtlasPrefetch.ASTC_ATLAS_ASSET_PATH).use { it.readBytes() }
        } catch (t: Throwable) {
            android.util.Log.i(
                "NativeSurfaceView",
                "ASTC atlas unavailable (fallback to RGBA): ${t.message}"
            )
            null
        }
    }

    /**
     * ASTC 压缩图集**上传**目标（上传触碰无锁的 C++ `g_renderer`，
     * 必须在主线程执行）。默认走 [NativeBridge.uploadCompressedAtlas]
     * （KtxLoader 全字段校验 + Vulkan ASTC 上传）；返回 0 = 校验失败/设备不支持。
     */
    @Suppress("TooGenericExceptionCaught") // 上传失败返回 0 由调用方单级回退, native/设备异常不可枚举
    var compressedAtlasUploader: (ByteArray) -> Int = { bytes ->
        try {
            NativeBridge.uploadCompressedAtlas(bytes)
        } catch (t: Throwable) {
            android.util.Log.e("NativeSurfaceView", "ASTC atlas upload failed", t)
            0
        }
    }

    /**
     * RGBA mip 链**上传**目标（2.3 真 mip；上传触碰无锁的 C++ `g_renderer`，
     * 必须在主线程执行）。默认走 [NativeBridge.uploadTextureMipChainDirect]
     * （level-major 紧凑布局逐级 VkBufferImageCopy）；返回 0 = 后端不支持/
     * 校验失败 → 调用方单级回退（[NativeBridge.uploadTextureDirect]）。
     * 注入点与 [compressedAtlasUploader] 同风格（测试 Fake 断言入参）。
     */
    @Suppress("TooGenericExceptionCaught") // 上传失败返回 0 由调用方单级回退, native/设备异常不可枚举
    internal var mipChainUploader: (java.nio.ByteBuffer, Int, Int, Int) -> Int = { pixels, w, h, mips ->
        try {
            NativeBridge.uploadTextureMipChainDirect(pixels, w, h, mips)
        } catch (t: Throwable) {
            android.util.Log.e("NativeSurfaceView", "mip chain upload failed", t)
            0
        }
    }

    /**
     * ASTC 加载+上传**同步组合**（测试注入点，锁定 [tryCompressedAtlas] 分支语义）。
     *
     * 生产走 [buildAtlasAsync] 的两段式（[compressedAtlasReader] 在后台 /
     * [compressedAtlasUploader] 在主线程）；本组合仅供测试与同步回退路径使用，
     * 返回 0 = 资产缺失/IO 异常/校验失败/设备不支持 → 回退 RGBA 图集。
     */
    var compressedAtlasLoader: (android.content.Context) -> Int = { ctx ->
        compressedAtlasReader(ctx)?.let { compressedAtlasUploader(it) } ?: 0
    }

    /**
     * 渲染质量因子转发（MainGameScreen 接线；backend 创建后立即应用，防初始发射丢失）。
     * Compose 线程写、渲染线程读，@Volatile 保证可见性。
     * 转发时携带当前装饰标志（单边 setter 触发时 C++ 状态保持完整）。
     * 同时重算渲染缩放（qualityFactor 是 RenderScalePolicy 决策输入之一）。
     */
    @Volatile
    var renderQualityFactor: Float = 1.0f
        set(value) {
            field = value
            softwareBackend?.qualityFactor = value
            renderQualitySink(value, renderDecorationsDisabled)
            initCoordinator.recomputeRenderScale()
        }

    /** 装饰层关闭标志转发（语义同上，转发时携带当前质量因子） */
    @Volatile
    var renderDecorationsDisabled: Boolean = false
        set(value) {
            field = value
            softwareBackend?.decorationsDisabled = value
            renderQualitySink(renderQualityFactor, value)
        }

    /** 自选清晰度目标渲染缩放（[ClarityMode.renderScaleCap]）；变化时重算渲染缩放 */
    @Volatile
    var clarityRenderScale: Float = 1.0f
        set(value) {
            field = value
            initCoordinator.recomputeRenderScale()
        }

    /**
     * 自选清晰度纹理采样各向异性倍率（[ClarityMode.anisotropy].level；0 = 关闭）。
     * Compose 线程写、渲染线程消费——渲染线程每节拍读 [clarityMipmap] 后比对
     * [appliedTextureAniso]，变化即调 NativeBridge.setTextureQuality 重建图集采样器。
     */
    @Volatile
    var clarityAnisotropy: Float = 2.0f

    /** 自选清晰度纹理采样 mipmap 开关（[ClarityMode.mipmap]），同上通道。 */
    @Volatile
    var clarityMipmap: Boolean = true

    /** 渲染线程已应用的各向异性倍率（consumePendingTextureQuality 消费比对基准） */
    @Volatile
    private var appliedTextureAniso: Float = 2.0f

    /** 渲染线程已应用的 mipmap 开关（同上） */
    @Volatile
    private var appliedTextureMipmap: Boolean = true

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
        /** initRenderer 返回 false 或抛出异常时触发（记录失败台账） */
        fun onSurfaceInitFailed()
        /**
         * Vulkan 建链成功（Task 2.3：仅 VULKAN 后端回调，GLES 会话不回调）。
         * 宿主据此做台账成功回写（清零计数 + GPU 设备信息落盘，量化阈值输入）。
         * 默认空实现——历史宿主实现无需同步修改。
         */
        fun onVulkanChainSucceeded() = Unit
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
     * 浮空岛崖壁纹理加载状态持有者（Compose 可观察掩码 + Canvas 路径位图集）。
     *
     * 崖壁素材单张超出图集容量，走**独立纹理**而非图集；加载在 [onRendererReady]
     * 回调内触发（与图集同纪律），掩码经 [IslandCliffTextureHolder.textureMask]
     * 暴露给 Compose 层——布局合成据此排除未上传成功的条目（部分降级）。
     * 渲染端经 [hasAnyCliffTexture] / [cliffTextureCount] 读同一份状态。
     */
    internal val islandCliffTextures: IslandCliffTextureHolder by lazy {
        IslandCliffTextureHolder(context)
    }

    /** 是否已有任一张崖壁纹理可用（全不可用则整层跳过，不画白） */
    internal val hasAnyCliffTexture: Boolean get() = islandCliffTextures.hasAnyTexture

    /** 已成功上传的崖壁纹理张数（观测锚点用） */
    internal val cliffTextureCount: Int get() = islandCliffTextures.availableCount

    /**
     * 是否应尝试 ASTC 压缩图集（分支决策：Vulkan 路径且开关开启）。
     * 独立纯函数供守卫测试锁定分支逻辑（完整 buildAtlas 的 RGBA 上传为 native 调用，
     * JVM 测试无法覆盖——由真机验证）。
     */
    internal fun shouldTryCompressedAtlas(): Boolean =
        renderMode == RenderMode.VULKAN && config.renderFlags.textureCompression

    /**
     * ASTC 压缩图集尝试入口：分支决策 + 加载上传（返回 0 = 回退 RGBA 信号）。
     * 独立供守卫测试注入 loader 断言调用与返回值语义。
     */
    internal fun tryCompressedAtlas(context: android.content.Context): Int =
        if (shouldTryCompressedAtlas()) compressedAtlasLoader(context) else 0

    /**
     * 图集异步流水线（拼装移出主线程）。
     *
     * 独立类承载——渲染宿主已贴 detekt TooManyFunctions 阈值，且"两段式流水线"
     * 自成一套状态机（纪元守卫 / ASTC 回退 / 软渲分流）。[start] 内部：
     * 后台线程跑 [com.xianxia.sect.ui.game.sect.AtlasPayload] 的拼装重活
     * （ASTC 资产读取 → 逐精灵解码 + Canvas 拼装 → ARGB→RGBA 转换），
     * 主线程只收一次轻量 GPU 上传调用——上传不能搬后台（C++ g_renderer
     * 无锁，渲染线程每帧并发进入），要消灭的是拼装不是上传。
     */
    internal val atlasPipeline = AtlasAsyncPipeline(this)

    /**
     * 异步构建并上传图集（图集拼装移出主线程）。
     *
     * ## 生命周期
     * 发起时在**当前线程**捕获软渲染标志 / ASTC 开关 / surface 纪元并交给
     * [atlasPipeline]——后台线程不得再读 View 可变状态；surface 已销毁
     * （旋转/切后台重建）时结果由纪元守卫直接丢弃，由新 surface 重新发起。
     *
     * @param context 资源上下文
     * @param onReady 上传完成回调（主线程；参数 = 纹理 ID，0 = 软渲染路径或失败）
     */
    /**
     * 浮空岛崖壁纹理加载入口（在 [onRendererReady] 回调内触发，与图集同纪律）。
     *
     * 线程纪律由 [IslandCliffTextureHolder.load] 承载：后台线程跑重活
     * （KTX 资产读取 / 逐张解码 + RGBA 与 mip 链编码 / Canvas 位图解码），
     * 主线程只做 native 上传（C++ `g_renderer` 无锁，上传不可与渲染线程并发）。
     *
     * Canvas 软渲染路径保留位图（[IslandCliffTextureHolder.bitmaps] 供
     * [SoftwareCanvasBackend] 绘制崖壁层）；Vulkan/GLES 路径不保留（省内存）。
     */
    internal fun loadIslandCliffTextures() {
        islandCliffTextures.load(
            astcSupported = renderMode == RenderMode.VULKAN && NativeBridge.isAstcSupported(),
            keepBitmaps = renderMode == RenderMode.SOFTWARE
        ) { ids ->
            islandCliffTextures.textureMask.value = IslandCliffTextureSet.maskFor(ids)
        }
    }

    fun buildAtlasAsync(context: android.content.Context, onReady: (Int) -> Unit) {
        // 渲染模式在发起时捕获（后台线程不得再读 View 可变状态）
        val software = renderMode == RenderMode.SOFTWARE
        val tryCompressed = shouldTryCompressedAtlas()
        if (!tryCompressed) {
            // 本 surface 不会走 ASTC：丢弃 GameActivity 预取的 KTX 字节（21MB 级，
            // 不丢弃将驻留至进程结束——RGBA 回退路径永不消费它）
            SectAtlasPrefetch.clear()
        }
        // 同 surface 重复发起（降级链重试）：打断上一轮，旧结果已无消费者
        atlasPipeline.cancel()
        atlasPipeline.start(context, surfaceProvider.generation, software, tryCompressed, onReady)
    }

    companion object {
        /** 日志标签（本类 + 图集异步流水线 [AtlasAsyncPipeline] 共用） */
        internal const val LOG_TAG = "NativeSurfaceView"
        /** 每秒纳秒数 */
        private const val NANOS_PER_SECOND = 1_000_000_000L
        /** 帧率下限保护（防除零与非法值） */
        private const val MIN_FPS_VALUE = 1
        /** 显示刷新率兜底（provider 未知/异常返回 0 时按 60Hz 兜底，防 1Hz 慢渲染） */
        private const val DEFAULT_DISPLAY_FPS = 60
        /** 高刷面板升档声明防抖窗口（毫秒）：30→60 回升稳定 2s 才声明（防切刷新率黑屏） */
        private const val UPSHIFT_DEBOUNCE_MS = 2_000L
        // ASTC 图集资产路径常量统一由 SectAtlasPrefetch.ASTC_ATLAS_ASSET_PATH 定义
        //（预取与读取共用单一真相源）
        /** 渲染线程停止等待截止（纳秒）：2s 绝对截止轮询（防 vk 调用阻塞时资源释放竞态） */
        private const val JOIN_DEADLINE_NS = 2_000_000_000L
        /** skip-release 纪元延迟回收截止（纳秒，）：resolveDeferredRelease
         *  轮询 join 卡住渲染线程的 5s 上限——C++ 侧 vk 调用有界化后理论不可达，
         *  截止后本纪元降级 GLES（绝不并发触碰该 Vulkan 实例） */
        private const val DEFERRED_RELEASE_DEADLINE_NS = 5_000_000_000L
        /** 软件渲染分辨率上限（CPU 逐像素路径兜底：即使策略异常也不超过此值，避免全分辨率卡顿；
         *  低值换取拿起/移动预览的跟随流畅度） */
        private const val SOFTWARE_RENDER_SCALE_CAP = 0.5f
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

    // ── 云层动画（世界顶部动态云朵） ──

    /**
     * 云层动画引擎（渲染线程驱动——只在世界外生成/穿越/出界消失，速度 3 格/秒）。
     * 构造期初始化（世界尺寸来自 [config]）；跳帧期间仍随节拍唤醒推进生成定时器。
     */
    val cloudAnimator = CloudLayerAnimator(
        worldWidthPx = config.worldPixelWidth.toFloat()
    )

    /**
     * 当前帧云层实例数据快照（[CloudLayerAnimator.snapshot] 输出；
     * 渲染线程写、双后端读——同一份快照保证 Vulkan/Canvas 像素级一致）。
     */
    @Volatile
    var cloudData: FloatArray? = null

    /**
     * 当前天空渐变配置（渲染侧单一真相源）。
     * Vulkan/GLES 路径由 [com.xianxia.sect.ui.game.sect.VulkanRenderBackend] 经
     * NativeBridge.setSkyConfig 推送到 C++；Canvas 路径由 [SoftwareCanvasBackend] 直接读取。
     * 未来天气/时间系统改此值即可切换晴天/傍晚/夜晚/阴天。
     */
    @Volatile
    var skyConfig: SkyBackgroundConfig = SkyBackgroundConfig.DEFAULT

    /** 上次已推送到 C++ 的天空配置（Vulkan/GLES 路径进帧时比较，仅变化时 setSkyConfig） */
    var lastPushedSkyConfig: SkyBackgroundConfig? = null

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

    // ── 预览独立快通道（仿相机通道） ──
    // 实现见 FastPreviewChannel.kt（独立类承载快照+版本，触控回调直接写、
    // 渲染线程按版本合成进帧，不经 Compose 重组/帧率门控）。

    /** 建筑放置/移动预览快通道（触控回调直写；渲染线程 renderTick 合成进帧） */
    internal val fastPreviewChannel = FastPreviewChannel()

    // ── 地图淡入过渡（仿独立相机通道：渲染线程每帧计算） ──

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

    /** 跨平台手势引擎 */
    var touchEngine: SectMapTouchEngine? = null

    // ============================================================
    // 平台 surface 事件（SurfaceProvider 抽象 — iOS 迁移点）
    // ============================================================

    /**
     * 平台 surface 事件监听器 — 由 [surfaceProvider] 派发（主线程同步）。
     * 各事件处理逻辑承担生命周期防御：纪元防 stale、首帧清除、10s 初始化超时安全网。
     */
    private val surfaceEventListener: SurfaceEventListener = HostSurfaceEventListener()

    /** 渲染初始化协调器（Vulkan/软件启动三函数内聚） */
    private val initCoordinator = InitCoordinator()

    /**
     * 平台 surface 事件监听实现（具名内类，宿主函数数收敛）。
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
            // 解除旧 provider 的平台回调注册（旧实例残留 addCallback 会导致
            // 同事件被双 provider 接收、genCounter 空转）
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
    // Surface 事件处理（经 SurfaceProvider 派发）
    // ============================================================

    /**
     * 表面可用（含初始尺寸）— 初始化渲染器。
     *
     * 注意：初始化使用 View 布局尺寸（[width]/[height]）；
     * provider 传入的 surface 尺寸仅供 resize 路径（[handleSurfaceSizeChanged]）。
     */
    private fun handleSurfaceAvailable() {
        // 无有效 surface 句柄不初始化——某些 ROM/时序下可用事件可能早于
        // 物理 surface 就绪；Robolectric 下 holder.surface 恒 null → 安全 no-op 路径。
        // provider.isSurfaceValid 为第二道守卫（provider 仅 ACTIVE 状态派发本事件，双保险）；
        // isReady/initInProgress 防重复初始化（Vulkan init 成功/超时降级回调均经 isReady 守卫拦截）
        val canInit = holder.surface != null && surfaceProvider.isSurfaceValid && !isReady && !initInProgress
        if (!canInit) return
        initInProgress = true
        // 新 surface 纪元：复位同尺寸去抖基准（防新旧 surface 尺寸相同导致 resize 被误跳）
        lastSurfaceWidth = -1
        lastSurfaceHeight = -1

        // 初始化窗口期 Surface 黑色兜底：Vulkan 异步初始化（0.5~3s）期间
        // surface 必须为纯黑，否则 RGBA_8888 半透明 surface 透出白色窗口背景
        //（"进入游戏白屏"来源之一）。
        //
        // GPU 路径不得提前 lockCanvas 清屏：lockCanvas 会把 ANativeWindow 连到
        // Canvas API，随后 GPU 后端 eglCreateWindowSurface / vkCreateAndroidSurface
        // 对同一窗口再次 api_connect 会报
        // "native_window_api_connect failed (already connected to another API?)"
        // → GPU 初始化失败退回 CPU 软件渲染。
        // GPU 路径首帧由渲染器绘制 + 地图淡入遮蔽；仅软件路径保留清屏
        //（软件本身即 Canvas，lockCanvas 无冲突）。
        if (useRenderMode == RenderMode.SOFTWARE) {
            surfaceProvider.clearSurface(android.graphics.Color.BLACK)
        }

        // 新 surface 重置帧率声明与 EWMA 状态——残留的 lastDeclaredFrameRate
        // 会阻止新 surface 降频声明，旧 EWMA 残留会导致新渲染线程首帧被误判低帧率
        lastDeclaredFrameRate = 0
        // 防抖候选与渲染缩放消费状态同步重置
        //（跨 surface 残留会让新会话跳过首帧 60Hz 声明 / 误判缩放已应用）
        declareCandidateFps = 0
        declareCandidateSinceMs = 0L
        appliedRenderScale = 1.0f
        pendingRenderScale = renderResolutionScale
        softwareRenderScaleVersion++
        adaptiveFpsTracker.reset()

        // 捕获纪元：所有异步回调（post）通过此值检测跨 surface stale
        val currentGen = surfaceProvider.generation

        // ★ skip-release 纪元残留先收尾：必须在任何 init 派发
        //   之前——主线程串行保证 join/补释放与旧渲染线程不并发；5s 截止则本纪元
        //   强制 GLES（下方分流读取 initBackendGles）
        resolveDeferredRelease()

        // 渲染模式预判：SOFTWARE 直接走软件渲染；GLES 走 GPU GLES 中间层；
        //    VULKAN 走 Vulkan（失败再降级 GLES→软件）。降级链：Vulkan→GPU GLES→CPU Canvas。
        when (useRenderMode) {
            RenderMode.SOFTWARE -> initCoordinator.startSoftwareBackend(currentGen)
            RenderMode.GLES -> { initBackendGles = true; initCoordinator.startVulkanInit(currentGen) }
            RenderMode.VULKAN -> { initBackendGles = false; initCoordinator.startVulkanInit(currentGen) }
        }
    }

    /**
     * 尺寸变化（可用后非首次）— 统一由后端适配器处理
     * （Vulkan=resizeRenderer / Canvas=视口重建）。
     *
     * 同尺寸去抖：键盘/insets 噪声可能派发与当前
     * 尺寸相同的 surfaceChanged 事件——真实 resize 会触发 Vulkan swapchain+
     * 管线全量重建（m_ready=false 黑帧窗口期），同尺寸事件直接丢弃，
     * 杜绝"界面短暂闪烁"。跨 surface 纪元由 [handleSurfaceAvailable]/
     * [handleSurfaceDestroyed] 复位，防陈旧尺寸污染。
     *
     * @param width 新宽度（像素）
     * @param height 新高度（像素）
     */
    private fun handleSurfaceSizeChanged(width: Int, height: Int) {
        // 诊断上下文：记录事件时刻是否有活跃输入会话——键盘出现/消失期间若出现
        // 本事件，说明 Window/Compose/Insets 链路仍有 IME 驱动的尺寸变化
        //（logcat 反向验证锚点，不得静默忽略）
        val inputSessionActive = com.xianxia.sect.ui.components.InputSessionStateMachine.hasActiveSessions()
        if (shouldSkipSurfaceResize(lastSurfaceWidth, lastSurfaceHeight, width, height)) {
            android.util.Log.d(
                "NativeSurfaceView",
                "surfaceChanged 同尺寸(${width}x$height) 跳过 resize" +
                    "（insets/键盘噪声防护, inputSessionActive=$inputSessionActive）"
            )
            return
        }
        lastSurfaceWidth = width
        lastSurfaceHeight = height
        android.util.Log.i(
            "NativeSurfaceView",
            "surfaceChanged ${width}x$height → resize（inputSessionActive=$inputSessionActive）"
        )
        // 面积档位随视口变化（旋转/分屏/折叠）——重算后 resize
        initCoordinator.recomputeRenderScale()
        activeBackend?.resize(width, height)
    }

    /** 最近一次实际派发过 resize 的 surface 尺寸（同尺寸去抖；初始化/销毁时复位） */
    @Volatile
    private var lastSurfaceWidth: Int = -1

    @Volatile
    private var lastSurfaceHeight: Int = -1

    /**
     * 表面销毁 — 停止渲染线程、释放后端并清空 surface 关联资源
     * （= 原 surfaceDestroyed；纪元递增由 provider 完成）。
     */
    private fun handleSurfaceDestroyed() {
        isReady = false
        initInProgress = false
        pendingInit = false
        // 同尺寸去抖基准复位（跨 surface 纪元不残留）
        lastSurfaceWidth = -1
        lastSurfaceHeight = -1
        // Layer 4: 中断正在执行的 VulkanInit 线程
        vulkanInitThread?.interrupt()
        vulkanInitThread = null
        // 中断在飞的图集拼装线程。不 join——拼装是 CPU 密集循环，
        // 主线程 join 会退化成"换了个地方阻塞"；结果由纪元守卫丢弃。
        atlasPipeline.cancel()
        // 中断渲染线程，加速从 Thread.sleep() 中退出
        renderThread?.interrupt()
        // 等待渲染线程安全停止后再释放资源
        stopRenderThread()
        renderThread = null
        activeBackend = null
        softwareBackend = null
        // surface 销毁后必须清纹理引用——重建后 buildAtlas 若失败
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
        while (!joined && System.nanoTime() < deadlineNs) {
            try {
                thread.join(200)
                joined = !thread.isAlive
            } catch (_: InterruptedException) {
                break
            }
        }
        if (!joined) {
            // 只打日志跳过 release 会泄漏 backend——保留引用，
            // 泄漏路径）——保留 backend 与卡住线程引用，新纪元 init 派发前
            // resolveDeferredRelease 补释放（join 成功）或降级 GLES（5s 截止）
            deferredBackend = activeBackend
            stuckRenderThread = thread
            android.util.Log.w(
                LOG_TAG,
                "RenderThread did not stop after 2s deadline — " +
                    "backend release deferred to next epoch (resolveDeferredRelease)"
            )
        } else {
            // 仅在渲染线程确认停止后释放 backend 资源。
            //   统一经 RenderBackend 适配器释放（Vulkan=shutdownRenderer / Canvas=canvas release）
            activeBackend?.release()
        }
    }

    /**
     * skip-release 纪元收尾（主线程、新纪元 init 派发前调用）：
     * 轮询 join 卡住的渲染线程（[DEFERRED_RELEASE_DEADLINE_NS] 5s 截止）——
     * join 成功即补 release（线程已死，删除 g_renderer 不再有 UAF）；截止仍未退出
     * （C++ 侧 vk 调用全面有界化后理论上不可达）则本纪元降级 GLES 并上报结构化
     * 事件，绝不并发触碰该 Vulkan 实例——把「UAF 或泄漏」的二选一变成
     * 「安全降级」的第三选项。
     */
    private fun resolveDeferredRelease() {
        val backend = deferredBackend ?: return
        val thread = stuckRenderThread ?: return
        val deadlineNs = System.nanoTime() + DEFERRED_RELEASE_DEADLINE_NS
        while (thread.isAlive && System.nanoTime() < deadlineNs) {
            try {
                thread.join(200)
            } catch (_: InterruptedException) {
                break
            }
        }
        deferredBackend = null
        stuckRenderThread = null
        if (!thread.isAlive) {
            android.util.Log.i(
                LOG_TAG,
                "Deferred render backend release: stuck thread exited — releasing now"
            )
            backend.release()
        } else {
            android.util.Log.e(
                LOG_TAG,
                "RenderThread still alive after deferred deadline — forcing GLES for this epoch"
            )
            reportRenderFallback(this, "STUCK_RENDER_THREAD")
            forceGlesForEpoch()
        }
    }

    /**
     * 本纪元强制 GPU GLES（降级阀）：glesTriedAfterVulkan 同步置位，
     * GLES 失败直接降软件——禁止回 Vulkan（该实例本纪元不可触碰）。
     * 下一 surface 纪元 useRenderMode 分流照常（Vulkan 失败计数由 C++ 侧
     * 幂等析构 + 有界化兜底，重新尝试是安全的）。
     */
    private fun forceGlesForEpoch() {
        initBackendGles = true
        glesTriedAfterVulkan = true
    }

    /**
     * 渲染初始化协调器——Vulkan/软件初始化启动三函数
     * 职责内聚，NativeSurfaceView 顶层函数数收敛。
     */
    private inner class InitCoordinator {

        /** surface 重建（shutdownRenderer 重置 C++ 全局量）后重放当前热控状态 */
        fun pushRenderQuality() {
            renderQualitySink(renderQualityFactor, renderDecorationsDisabled)
        }

        /**
         * 重算渲染缩放（surface 初始化/resize/画质因子/GPU 档位变化时调用）。
         * 决策输入：面积分级 × GPU 档 cap × 软件路径因子 × 引擎画质因子；
         * RenderFlags.renderScaleEnabled 关闭 → 恒 1.0（行为 = 特性未实现前现状）。
         */
        fun recomputeRenderScale() {
            val newScale = if (config.renderFlags.renderScaleEnabled) {
                RenderScalePolicy.computeRenderScale(
                    gpuTier = gpuTier,
                    softwarePath = renderMode == RenderMode.SOFTWARE,
                    screenWidth = width,
                    screenHeight = height,
                    qualityFactor = renderQualityFactor,
                    clarityRenderScale = clarityRenderScale
                )
            } else {
                1.0f
            }
            // 软件路径兜底：CPU 逐像素路径按全分辨率（1.0）渲染极慢，必须钳制到
            // SOFTWARE_RENDER_SCALE_CAP。软件是兜底低画质模式，降分辨率换流畅。
            val applied = if (renderMode == RenderMode.SOFTWARE) {
                minOf(newScale, SOFTWARE_RENDER_SCALE_CAP)
            } else {
                newScale
            }
            renderResolutionScale = applied
        }

        /**
         * SOFTWARE 策略路径 — 直接启动软件渲染。
         *
         * @param currentGen 发起时的 surface 纪元（post 回调 stale 守卫）
         */
        fun startSoftwareBackend(currentGen: Int) {
            android.util.Log.i("NativeSurfaceView",
                "RenderMode.SOFTWARE (by policy) — starting software backend")

            // 同步清除 Surface，防止 emulator 上 Activity 切换导致的残留内容闪烁
            surfaceProvider.clearSurface(android.graphics.Color.DKGRAY)

            pendingInit = true
            post {
                // surfaceDestroyed 后 stale post 不执行
                if (currentGen != surfaceProvider.generation) return@post
                if (pendingInit && !isReady) {
                    // 先设置渲染模式和软件后端，再通知上层上传纹理
                    // 注意：buildAtlas() 依赖 renderMode 判断是否回收 Bitmap，
                    // 必须在 onRendererReady 之前设置，否则图集 Bitmap 被误回收
                    softwareBackend = createSoftwareBackend()
                    activeBackend = SoftwareRenderBackend(this@NativeSurfaceView)
                    renderMode = RenderMode.SOFTWARE
                    // 软件路径确定后重算渲染缩放（pathFactor 0.8 生效）
                    initCoordinator.recomputeRenderScale()
                    // 通知 Compose 层上传纹理（TextureAtlas 已在 surface 可用事件中 init）
                    onRendererReady?.invoke()
                    isReady = true
                    renderThread = RenderThread().also { it.start() }
                }
                initInProgress = false
            }
        }

        /**
         * VULKAN 路径 — 启动异步初始化。
         * 初始化在独立线程执行，成功/失败/异常均 post 回主线程（经纪元守卫）。
         *
         * @param currentGen 发起时的 surface 纪元（post 回调 stale 守卫）
         */
        fun startVulkanInit(currentGen: Int) {
            // GPU 模式加载 native 库与纹理图集
            //（SOFTWARE 模式完全使用 Canvas 渲染，不加载 native 库——
            // 策略预判路径在 handleSurfaceAvailable 已分流，不会到达此处）
            NativeBridge.ensureLoaded()
            NativeBridge.initAtlas()

            // 渲染后端选择：initRenderer 前按 initBackendGles 设定，决定
            // NativeBridge.cpp 创建 VulkanBackend 还是 GlesBackend
            //（降级链 Vulkan→GPU GLES→CPU Canvas）。
            NativeBridge.setRenderBackend(
                if (initBackendGles) NativeBridge.BACKEND_GLES else NativeBridge.BACKEND_VULKAN
            )

            val surface = holder.surface ?: return

            // 初始化超时安全网：基础 10s；prewarm 在途时
            // initRenderer 正阻塞在生命周期锁上等 prewarm——属健康慢而非卡死，
            // 预算延长为 prewarm 起点 + 8s + 10s，健康但慢的设备不再被误降 GLES。
            val budgetMs = if (VulkanPrewarmState.inFlight()) {
                (VulkanPrewarmState.startedAt() + VulkanPrewarmState.PREWARM_BUDGET_MS +
                    SurfaceProvider.INIT_TIMEOUT_BASE_MS) - System.currentTimeMillis()
            } else {
                SurfaceProvider.INIT_TIMEOUT_BASE_MS
            }
            surfaceProvider.startInitTimeout(budgetMs.coerceAtLeast(1_000L))

            // Layer 4: 取消之前的初始化线程（如有），防止竞态
            vulkanInitThread?.interrupt()
            vulkanInitThread = null

            // 捕获视口尺寸传入线程体（后台线程读主线程维护的 View 字段属数据竞争
            // ——与 currentGen 同模式）
            val viewportW = width
            val viewportH = height
            // 渲染缩放同捕获（RenderScalePolicy 决策值，线程体内传给 initRenderer）
            val initRenderScale = renderResolutionScale
            vulkanInitThread = kotlin.concurrent.thread(name = "VulkanInit") {
                runVulkanInitThread(currentGen, surface, viewportW, viewportH, initRenderScale)
            }
        }

        /** Vulkan 初始化线程体（独立线程；post 回主线程前先做纪元守卫） */
        // 本函数是原生初始化崩溃的唯一归因入口：Throwable 全捕获 + 分型降级
        //（中断=surface 销毁不降级/其他异常降级）是崩溃防御设计本身
        @Suppress("TooGenericExceptionCaught")
        private fun runVulkanInitThread(
            currentGen: Int, surface: Surface, viewportW: Int, viewportH: Int, initRenderScale: Float
        ) {
            try {
                val initStart = System.currentTimeMillis()
                renderInitStartMs = initStart

                // Layer 2: Phase 2 写前标记 — initRenderer 前写入
                vulkanInitListener?.onSurfaceInitStarted()

                val ok = NativeBridge.initRenderer(
                    viewportW = viewportW,
                    viewportH = viewportH,
                    worldW = config.worldPixelWidth,
                    worldH = config.worldPixelHeight,
                    tileSize = config.tileSize,
                    renderScale = initRenderScale,
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

        // 渲染模式状态（GLES 时置 GLES，供 onRendererReady 内的 buildAtlas 的
        //   shouldTryCompressedAtlas（ASTC 仅 Vulkan）正确分流）
        renderMode = if (initBackendGles) RenderMode.GLES else RenderMode.VULKAN

        // 台账写前标记清除：成功会话必须自清 surface_init_started 标记，
        // 否则台账增量消费语义下下次启动被误计一次 kill。
        vulkanInitListener?.onSurfaceInitSucceeded()
        // Vulkan 建链成功回写：清零失败计数 + GPU 设备信息落台账。
        // 仅 VULKAN 后端回调——GLES 会话的 Vulkan 静态值为空/陈旧，不得污染台账。
        if (!initBackendGles) {
            vulkanInitListener?.onVulkanChainSucceeded()
        }

        // 先上传纹理（地面 + 图集），再启动渲染线程
        onRendererReady?.invoke()

        isReady = true
        // 热控状态补发：shutdownRenderer 重置了 C++ 全局量，
        //   此处把 Kotlin 侧当前值（可能已热控降级）重放到 C++
        initCoordinator.pushRenderQuality()
        // 渲染后端：按 initBackendGles 选择适配器。VulkanRenderBackend 与
        // GlesRenderBackend 共用同一渲染逻辑（经 NativeBridge Rhi 虚函数），
        // 仅对应不同 C++ 后端实现。
        activeBackend =
            if (initBackendGles) GlesRenderBackend(this) else VulkanRenderBackend(this)
        renderThread = RenderThread().also { it.start() }
    }

    /** Vulkan 初始化失败（initRenderer 返回 false；线程内记录，post 回主线程降级） */
    private fun handleVulkanInitFailure(initStart: Long, currentGen: Int) {
        // Layer 2: 失败 → 清除写前标记 + 记录失败台账（宿主实现）
        vulkanInitListener?.onSurfaceInitFailed()

        android.util.Log.e("NativeSurfaceView",
            "Vulkan init failed after ${System.currentTimeMillis() - initStart}ms — " +
            "falling back to GPU GLES then software renderer")

        // 结构化回退事件：stage = C++ 收割的初始化错误码。
        reportRenderFallback(this, RenderFallbackReporter.errorName(NativeBridge.getLastInitError()))

        post { handleVulkanInitFailurePost(currentGen) }
    }

    /**
     * Vulkan 初始化失败/异常后的主线程降级（两路径共用同一降级语义）。
     */
    private fun handleVulkanInitFailurePost(currentGen: Int) {
        if (currentGen != surfaceProvider.generation) return
        surfaceProvider.notifyInitCompleted()
        initInProgress = false
        vulkanInitThread = null
        if (!isReady) {
            // 降级链 Vulkan→GPU GLES→CPU Canvas：
            //   Vulkan 首次失败 → 先尝试 GPU GLES；GLES 失败才最终软件兜底。
            if (!initBackendGles && !glesTriedAfterVulkan) {
                glesTriedAfterVulkan = true
                initBackendGles = true
                initCoordinator.startVulkanInit(currentGen)
            } else {
                // 降级到软件渲染（失败线程已自行返回，无需 join——
                // 需 join 的并发窗口在超时降级路径，见 handleSurfaceInitTimeout）
                fallbackToSoftwareRenderer()
            }
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
            // gen 守卫：旧纪元线程不得清空新纪元状态——destroy 后立即重建时，
            // 旧线程的中断处理迟到执行会清掉新 surface 的 vulkanInitThread 引用与 initInProgress
            if (currentGen != surfaceProvider.generation) return
            initInProgress = false
            vulkanInitThread = null
            return
        }
        // 其他异常（如 OOM），记录并降级
        android.util.Log.e("NativeSurfaceView",
            "Vulkan init crashed: ${t.message}", t)
        vulkanInitListener?.onSurfaceInitFailed()
        // 结构化回退事件：异常路径 stage 携带异常类型。
        reportRenderFallback(this, "crash_${t.javaClass.simpleName}")
        post { handleVulkanInitFailurePost(currentGen) }
    }

    /**
     * 完整初始化软件渲染（对齐降级路径语义）：后端 + 渲染线程 + isReady，
     * 后续 Vulkan init 成功/失败回调均被 isReady 守卫拦截。
     *
     * 回退事件：本函数是失败/超时降级的统一出口——事件已由各调用点上报
     *（handleVulkanInitFailure/handleVulkanInitCrash 的 →SOFTWARE、
     * handleSurfaceInitTimeout 的 init_timeout），此处不再重复上报。
     */
    private fun fallbackToSoftwareRenderer() {
        softwareBackend = createSoftwareBackend()
        activeBackend = SoftwareRenderBackend(this)
        renderMode = RenderMode.SOFTWARE
        // 软件路径确定后重算渲染缩放（pathFactor 0.8 生效）
        initCoordinator.recomputeRenderScale()
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
            // 结构化回退事件：stage=init_timeout + prewarm_inflight 标记区分
            // 「真卡死」与「预热挤占」（预算延长后仍超时即为真挂死信号）。
            reportRenderFallback(
                this,
                "init_timeout",
                extra = "prewarm_inflight=${VulkanPrewarmState.inFlight()}"
            )
            // 超时降级时旧 Vulkan init 线程仍在阻塞（超时正是因为 initRenderer 卡住）
            // ——必须 interrupt + 短 join，否则该线程与新 surface 的 init 线程
            // 并发操作 C++ 无锁裸指针 g_renderer（SIGSEGV）
            initCoordinator.interruptAndJoinVulkanInitThread()
            // 降级链 Vulkan→GPU GLES→CPU Canvas：
            //   Vulkan 超时 → 先尝试 GPU GLES；GLES 失败才最终软件兜底。
            if (!initBackendGles && !glesTriedAfterVulkan) {
                glesTriedAfterVulkan = true
                initBackendGles = true
                initCoordinator.startVulkanInit(0)
            } else {
                // 完整初始化（对齐降级路径语义）：后端 + 渲染线程 + isReady，
                // 后续 Vulkan init 成功/失败回调均被 isReady 守卫拦截
                fallbackToSoftwareRenderer()
            }
        }
    }

    // ============================================================
    // 触摸事件 → 转换为 TouchData → 喂入跨平台手势引擎
    // ============================================================

    // Lint 豁免（ClickableViewAccessibility）：本视图是原生游戏画布，必须直接
    // 拦截触摸流转换为跨平台 TouchData（Compose pointerInput 无法与 Vulkan 帧循环解耦）
    @Suppress("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val engine = touchEngine ?: return false
        var fed = false
        // MOVE 事件可能携带多个历史采样（getHistorySize），逐个喂入引擎——
        // 提高有效输入率与速度追踪精度（拖动视角更平滑，fling 速度更准）
        toTouchData(event)?.forEach { data ->
            engine.onTouch(data)
            fed = true
        }
        return fed
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

        /** 淡入完成后兜底帧已提交标记（防"进入游戏半透明白色定格"） */
        private var lastRenderedFadeAlpha = 1f

        /** 渲染健康诊断日志限频时间戳（每秒一条，渲染线程常驻可观测性） */
        private var lastDiagLogNs = 0L

        /** 诊断日志窗口内渲染帧数 */
        private var diagRenderCount = 0

        /** 诊断日志窗口内跳帧数 */
        private var diagSkipCount = 0

        /** 预览快通道已消费版本（渲染线程单消费者；= fastPreviewVersion 表示已合成） */
        private var lastConsumedFastPreviewVersion: Long = 0L

        /** 上次实际渲染的天空配置（渲染线程单消费者；!= host.skyConfig 表示天空配置变化须渲染） */
        private var lastRenderedSkyConfig: SkyBackgroundConfig = SkyBackgroundConfig.DEFAULT

        override fun run() {
            // 地图淡入：渲染线程每次启动（= 每次 surface 初始化：首次进入/
            // 重入/降级路径）触发——覆盖所有初始化路径，天然幂等。
            // surface 可用后 C++ g_fadeAlpha 已由 shutdownRenderer 重置为 1，
            // 此处 fadeIn 重置起始时间戳，本帧起 alpha 从 0 淡入
            fadeIn()

            // 首帧快速清除 Surface 缓冲区，防止华为模拟器等设备上
            // SurfaceFlinger 未正确清除新分配缓冲区导致残留内容显示
            //（clearSurface 内部吞异常——非关键操作，失败不影响后续渲染）
            // 仅软件路径执行：软件渲染本身即 Canvas，lockCanvas 无 API 冲突。
            // GPU（Vulkan/GLES）模式下后端已 connect(NATIVE_WINDOW_API_GPU/EGL)，
            // 此时 lockCanvas 必然被 "already connected to another API" 拒绝
            //（返回 null）——GPU 路径窗口期黑屏由窗口纯黑背景 +
            // 首帧渲染器绘制 + 地图淡入遮蔽覆盖。
            if (renderMode == RenderMode.SOFTWARE) {
                surfaceProvider.clearSurface(android.graphics.Color.BLACK)
            }

            android.util.Log.i(
                "NativeSurfaceView",
                "RenderThread started: mode=${renderMode} fadeStart set"
            )

            // vsync 帧节奏：Canvas 路径用 VsyncGate 对齐显示刷新率；
            // 初始化失败时 awaitTick 恒超时 → 循环回退 sleep 节拍。
            // vsyncPacing=false（RenderFlags 开关）→ 不启用 VsyncGate
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
         * 渲染主循环。
         *
         * ## 帧节奏
         * - vsyncPacing=true：以显示刷新率为节拍 + [FrameDropPolicy] 帧跳过——
         *   全速档（step=1）Canvas 走 VsyncGate vsync 对齐 / Vulkan 走节拍 sleep
         *   （FIFO 交换链天然 vsync 对齐提交）；降帧档（step>1）整体 sleep 到
         *   渲染间隔（省电：低帧率时 vsync 对齐收益小，减少唤醒次数）
         * - vsyncPacing=false：sleep 限速路径
         *
         * 每次迭代重算 step/interval——热控升降帧即时生效，无状态累积。
         */
        private fun renderLoop(vsyncPacing: Boolean, vsyncGate: VsyncGate?) {
            var lastFrameNs = System.nanoTime()
            // 有效帧率 = min(外部目标帧率, EWMA 渲染能力帧率)；
            // 起始用 targetFps（外部流尚未到达时的默认 10）
            var effectiveFps = targetFps.coerceAtLeast(MIN_FPS_VALUE)

            var tick = 0L
            // 脏帧跳过基准（引用比较 + 缩放版本，零每帧分配）
            var lastRenderedFrame: RenderFrame? = null

            while (running && isReady) {
                val now = System.nanoTime()
                val elapsedNs = now - lastFrameNs
                // 每次迭代重算帧节奏——热控升降帧即时生效，无状态累积
                val pacing = computeFramePacing(vsyncPacing, effectiveFps)

                // 渲染健康诊断（限频每秒一条，跳帧/定格时日志停更即诊断信号）
                maybeLogRenderHealth(now)

                // 双门判定：未到帧间隔 → 等待下一拍（等待失败即线程退出）后重入循环；
                // 节拍步进门：非渲染拍跳过。两门任一未过则不进入渲染段（门序不变）
                val isRenderTick = if (elapsedNs < pacing.intervalNs) {
                    if (!waitForNextTick(pacing.intervalNs - elapsedNs, pacing.step, vsyncGate)) return
                    false
                } else {
                    lastFrameNs = now
                    tick++
                    tick % pacing.step.toLong() == 0L
                }
                if (!isRenderTick) continue

                // Vulkan 渲染缩放消费（渲染线程独占；重建离屏目标语义同 resize）
                consumePendingRenderScale()

                // pending resize 消费：真实 swapchain 重建收敛到渲染线程
                // 帧边界执行——acquire 与 destroy 并发 UB 按构造消除（与 pendingRenderScale
                // 同消费模式；SOFTWARE 模式不触 JNI）
                if (shouldConsumeNativeResize(isReady, renderMode)) {
                    NativeBridge.consumePendingResize()
                }

                // Vulkan 清晰度纹理采样质量消费（mipmap + 各向异性）
                consumePendingTextureQuality()

                // 脏帧跳过：静止画面跳过渲染与指标——
                //   相机不动、帧引用未变、总线不脏、淡入完成、缩放未变 五守卫全通过
                //   才跳过（FrameSkipPolicy 纯函数）。跳帧不 recordFrameTime/不上报
                //   ObservedFps（防 EWMA 虚高误降级）；相机脏标记不消费（保持 true
                //   直至真实渲染，防相机移动丢失）
                val frame = currentFrame
                val fade = fadeAlpha
                // 云层动画推进（渲染线程每节拍调用——跳帧期间也随节拍唤醒推进生成
                //   定时器，空天空不会卡死；返回值作为脏帧信号，云朵运动不被跳帧定格）
                val cloudDirty = advanceClouds(this@NativeSurfaceView, System.nanoTime())
                // 淡入完成兜底：淡入已结束但最后一帧仍以淡入中 alpha 渲染时强制
                // 补渲一帧完整不透明地图——防脏帧跳过把"半透明瓦片 +
                // 米白清屏色 #F2EDE4"帧永久定格（"进入游戏全屏半透明白色覆盖"）
                if (shouldSkipFrame(frame, lastRenderedFrame, cloudDirty,
                        skyDirty = skyConfig != lastRenderedSkyConfig) &&
                    !needsFadeCompletionFrame(lastRenderedFadeAlpha, fade)
                ) {
                    diagSkipCount++
                } else {
                // 统一渲染入口：RenderBackend 抽象（VULKAN/SOFTWARE 分支收敛到
                //   surface 初始化创建处），渲染循环只面向接口——iOS Metal 后端
                //   实现同一接口即可接入，循环零改动
                val renderElapsedNs = renderTick()
                lastRenderedFrame = frame
                lastRenderedFadeAlpha = fade
                lastRenderedScaleVersion = softwareRenderScaleVersion
                lastRenderedSkyConfig = skyConfig
                diagRenderCount++

                // 统一 EWMA 渲染能力追踪（VULKAN/SOFTWARE 双路径一致）。
                // 关键设计：**不写回 targetFps**——渲染线程内部维护 effectiveFps =
                // min(targetFps, ewmaFps)，避免"只降不升 + StateFlow 不重发"钉死竞态；
                // 外部升帧（场景/模式/热控变化）始终即时生效，EWMA 能力恢复自动回升。
                val ewmaFps = adaptiveFpsTracker.recordFrameTime(renderElapsedNs, System.currentTimeMillis())
                effectiveFps = minOf(targetFps.coerceAtLeast(MIN_FPS_VALUE), ewmaFps)

                // 上报渲染能力帧率（供热控帧率驱动降级；能力帧率 ≠ 墙钟帧率，
                // 主动省电降帧（IDLE 10fps）不误判为渲染能力不足）
                reportObservedFps(now, ewmaFps)

                // 帧率变化后向系统声明（高刷面板声明 60/30 省屏耗 + 升档防抖）
                maybeDeclareFrameRate(effectiveFps, pacing.displayFps, System.currentTimeMillis())
                }
            }
        }

        /** 脏帧跳过判定（信号收集 + FrameSkipPolicy 纯函数） */
        private fun shouldSkipFrame(
            frame: RenderFrame?,
            lastRenderedFrame: RenderFrame?,
            cloudDirty: Boolean,
            skyDirty: Boolean
        ): Boolean {
            return FrameSkipPolicy.shouldSkipFrame(
                FrameSkipInputs(
                    cameraDirty = cameraDirty.get(),
                    frameChanged = frame !== lastRenderedFrame,
                    buildingBusDirty = commandBus?.buildingDirty?.get() ?: false,
                    fadeActive = fadeAlpha < 1f,
                    scaleChanged = softwareRenderScaleVersion != lastRenderedScaleVersion,
                    cloudDirty = cloudDirty,
                    // 预览快通道自唤醒：版本未消费即强制渲染（跳过会漏画预览帧——拖拽延迟根因）
                    previewDirty = fastPreviewChannel.version != lastConsumedFastPreviewVersion,
                    // 天空配置变化（天气/时间系统）——静止画面也须更新天空配色
                    skyDirty = skyDirty
                )
            )
        }

        /**
         * 渲染健康诊断日志（限频每秒一条）。
         *
         * 输出渲染/跳帧计数与当前淡入 alpha——排查"进入游戏全屏半透明白色覆盖"时，
         * 若日志显示 fade 长期 < 1 或 rendered 停更（画面定格），即确认对应机制。
         * 跳帧/定格时本方法仍在循环顶部执行，日志不会因无渲染而消失。
         */
        private fun maybeLogRenderHealth(nowNs: Long) {
            if (nowNs - lastDiagLogNs < NANOS_PER_SECOND) return
            lastDiagLogNs = nowNs
            android.util.Log.i(
                "NativeSurfaceView",
                "RenderHealth: rendered=$diagRenderCount skipped=$diagSkipCount " +
                    "fade=$fadeAlpha mode=$renderMode"
            )
            diagRenderCount = 0
            diagSkipCount = 0
        }

        /** 渲染线程消费 pending 渲染缩放（Vulkan：setRenderScale 重建离屏目标，仅渲染线程调用） */
        private fun consumePendingRenderScale() {
            val pending = pendingRenderScale
            if (pending != appliedRenderScale && renderMode == RenderMode.VULKAN && isReady) {
                appliedRenderScale = NativeBridge.setRenderScale(pending)
            }
        }

        /**
         * 渲染线程消费清晰度纹理采样质量（mipmap + 各向异性，Vulkan 图集采样器）。
         * Compose 线程只写 [clarityAnisotropy]/[clarityMipmap]（@Volatile），渲染线程
         * 每节拍比对 [appliedTextureAniso]/[appliedTextureMipmap] 后调 setTextureQuality。
         * 仅 Vulkan 生效（GLES/Canvas 无采样质量通道，setTextureQuality 内部 no-op）。
         */
        private fun consumePendingTextureQuality() {
            val aniso = clarityAnisotropy
            val mip = clarityMipmap
            if (renderMode != RenderMode.VULKAN || !isReady) return
            val qualityChanged = aniso != appliedTextureAniso || mip != appliedTextureMipmap
            if (!qualityChanged) return
            appliedTextureAniso = aniso
            appliedTextureMipmap = mip
            NativeBridge.setTextureQuality(aniso, mip)
        }

        /**
         * 本迭代帧节奏参数。
         *
         * - vsyncPacing=true：以显示刷新率为节拍 + [FrameDropPolicy] 帧跳过——
         *   全速档（step=1）Canvas 走 VsyncGate vsync 对齐 / Vulkan 走节拍 sleep
         *   （FIFO 交换链天然 vsync 对齐提交）；降帧档（step>1）整体 sleep 到
         *   渲染间隔（省电：低帧率时 vsync 对齐收益小，减少唤醒次数）
         * - vsyncPacing=false：sleep 限速路径
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
            // 帧间隔（防 step×interval 双重计数）：
            //   vsyncPacing：节拍 = 显示刷新率（1/displayFps），再经 step 跳帧 → 实际渲染率=effectiveFps；
            //   非 vsyncPacing（sleep 路径）：节拍 = 有效帧率（1/effectiveFps），step 恒 1，每节拍渲染。
            //   若 step>1 时用 1/effectiveFps 又叠加 step 跳帧，实际渲染率会变成
            //   effectiveFps²/displayFps（30fps→15fps），低帧档位帧延迟放大数倍。
            val intervalNs = if (vsyncPacing) {
                NANOS_PER_SECOND / displayFps.coerceAtLeast(MIN_FPS_VALUE)
            } else {
                NANOS_PER_SECOND / effectiveFps.coerceAtLeast(MIN_FPS_VALUE)
            }
            return FramePacing(displayFps = displayFps, step = step, intervalNs = intervalNs)
        }

        /**
         * 节拍等待（vsync 对齐 / sleep 兜底）。
         *
         * @return false = 线程中断（调用方退出循环）
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
         * 单帧渲染：相机脏标记推送 + 预览快通道合成 + 后端渲染 + 异常统一捕获。
         *
         * @return 渲染耗时（纳秒，EWMA 能力帧率追踪用）
         */
        // 前者: 异常源不可枚举降级继续; 后者: OOM 后显式 gc 是渲染线程内存急救的刻意兜底
        @Suppress("TooGenericExceptionCaught", "ExplicitGarbageCollectionCall")
        private fun renderTick(): Long {
            val frameStartNs = System.nanoTime()
            val backend = activeBackend
            val frame = mergeFastPreview(currentFrame)
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
         * 预览快通道合成：版本变化时用最新预览快照覆盖帧——
         * 双后端只读 frame 字段，零后端改动；版本不变或无快照则原帧直通（无分配）。
         */
        private fun mergeFastPreview(frame: RenderFrame?): RenderFrame? {
            val f = frame ?: return null
            val version = fastPreviewChannel.version
            val snapshot = if (version != lastConsumedFastPreviewVersion) {
                lastConsumedFastPreviewVersion = version
                fastPreviewChannel.state
            } else {
                null
            }
            return if (snapshot != null) mergeFastPreviewInto(f, snapshot) else f
        }

        /**
         * 每秒上报渲染能力帧率（EWMA 反推，非墙钟帧率——挂机主动降帧时
         * 渲染能力仍高，不应触发热控降级）。回调异常吞掉（渲染线程任何异常都会杀死渲染）。
         */
        @Suppress("TooGenericExceptionCaught") // 防御兜底: 异常源跨IO/SDK不可枚举, 降级继续+日志留痕, 非静默吞噬
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
         * 决策由 [FrameRateDeclarationPolicy] 纯函数给出：
         * - ≤60Hz 面板：≤30fps 声明 + 回升恢复声明（防 OEM 面板粘滞）
         * - >60Hz 面板：会话首帧声明 60（恰逢地图淡入遮罩）、≤30 声明 30、升档
         *   2s 防抖（FIXED_SOURCE 切刷新率在部分 OEM 触发 ~1s 黑屏，仅闲置周期
         *   回收时一次，可接受）
         * - [RenderFlags.refreshRateDeclaration] 关闭 → 按 60Hz 面板语义
         *
         * 渲染线程调用（surface 生命周期有效）；任何异常吞掉不杀死渲染线程。
         *
         * **iOS 对等**：`CADisplayLink.preferredFrameRateRange`（ProMotion 屏按
         * 内容帧率降刷新率）——无黑屏切换问题，直接声明目标帧率即可。
         */
        private fun maybeDeclareFrameRate(effectiveFps: Int, displayFps: Int, nowMs: Long) {
            // 开关关闭 → 按 60Hz 面板语义
            val effectiveDisplayFps = if (config.renderFlags.refreshRateDeclaration) {
                displayFps
            } else {
                FrameRateDeclarationPolicy.DISPLAY_FPS_NORMAL_MAX
            }
            val target = resolveDeclarationTarget(effectiveFps, effectiveDisplayFps, nowMs) ?: return
            doDeclareFrameRate(target, effectiveDisplayFps)
        }

        /**
         * 解析帧率声明目标（防抖窗口 / 策略决策 / 升档防抖三阶段——null = 本帧不声明）。
         * 仅高刷面板升档防抖（FIXED_SOURCE 切刷新率黑屏风险；60Hz DEFAULT
         * 回升必须立即声明防 OEM 面板粘滞）。
         */
        private fun resolveDeclarationTarget(effectiveFps: Int, displayFps: Int, nowMs: Long): Int? {
            return when {
                declareCandidateFps > 0 -> resolveDebounceCandidate(nowMs)
                else -> resolvePolicyTarget(effectiveFps, displayFps, nowMs)
            }
        }

        /** 防抖候选：稳定满 2s 执行候选并清除；否则继续等待（null = 本帧不声明） */
        private fun resolveDebounceCandidate(nowMs: Long): Int? {
            return if (nowMs - declareCandidateSinceMs >= UPSHIFT_DEBOUNCE_MS) {
                declareCandidateFps.also { declareCandidateFps = 0 }
            } else {
                null
            }
        }

        /** 策略决策 + 升档防抖：升档目标进防抖队列（null），降档/首帧立即声明 */
        private fun resolvePolicyTarget(effectiveFps: Int, displayFps: Int, nowMs: Long): Int? {
            val target = FrameRateDeclarationPolicy.targetDeclareFps(
                displayFps, effectiveFps, lastDeclaredFrameRate
            ) ?: return null

            val isHighRefreshUpshift = target > lastDeclaredFrameRate && lastDeclaredFrameRate > 0 &&
                FrameRateDeclarationPolicy.useFixedSource(displayFps)
            return if (isHighRefreshUpshift) {
                declareCandidateFps = target
                declareCandidateSinceMs = nowMs
                null
            } else {
                target
            }
        }

        /** 执行帧率声明（API 30 守卫在本函数内——lint 数据流可追踪；
         *  异常吞掉不杀死渲染线程；FIXED_SOURCE 仅高刷面板） */
        @Suppress("TooGenericExceptionCaught") // 防御兜底: 异常源跨IO/SDK不可枚举, 降级继续+日志留痕, 非静默吞噬
        private fun doDeclareFrameRate(fps: Int, displayFps: Int) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
                return
            }
            lastDeclaredFrameRate = fps
            try {
                val compat = if (FrameRateDeclarationPolicy.useFixedSource(displayFps)) {
                    Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE
                } else {
                    Surface.FRAME_RATE_COMPATIBILITY_DEFAULT
                }
                holder.surface.setFrameRate(fps.toFloat(), compat)
            } catch (e: Exception) {
                android.util.Log.w("NativeSurfaceView", "setFrameRate failed: ${e.message}", e)
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

/**
 * MotionEvent → 跨平台 [TouchData] 映射（含双指缩放多点触控 + MOVE 历史采样展开）。
 * 仅支持 DOWN/POINTER_DOWN/MOVE/POINTER_UP/UP/CANCEL，其余动作返回 null。
 * MOVE 事件的历史采样（getHistorySize）按时间顺序展开为多个 TouchData，
 * 供手势引擎逐条消费（增量语义正确、速度追踪更密）。
 * internal 供单测（Mockito 注入 MotionEvent 验证展开序列）。
 */
internal fun toTouchData(event: MotionEvent): List<TouchData>? {
    val pointerCount = event.pointerCount
    val timestamp = event.eventTime.toLong() * 1_000_000L
    return when (event.actionMasked) {
        MotionEvent.ACTION_DOWN -> listOf(
            TouchData(
                x = event.x,
                y = event.y,
                action = TouchAction.DOWN,
                timestamp = timestamp,
                pointerId = event.getPointerId(0),
                pointerCount = pointerCount
            )
        )

        // 第二根手指按下 → 进入双指缩放：x/y 为已按下主指针，x2/y2 为新增指针
        MotionEvent.ACTION_POINTER_DOWN -> listOf(
            TouchData(
                x = event.getX(0),
                y = event.getY(0),
                action = TouchAction.DOWN,
                timestamp = timestamp,
                pointerId = event.getPointerId(0),
                pointerCount = pointerCount,
                pointer2X = event.getX(event.actionIndex),
                pointer2Y = event.getY(event.actionIndex)
            )
        )

        MotionEvent.ACTION_MOVE -> toMoveTouchData(event, pointerCount, timestamp)

        // 一根手指抬起：x/y 传剩余仍在屏幕上的手指位置，供引擎恢复平移不跳变
        MotionEvent.ACTION_POINTER_UP -> listOf(
            toPointerUpTouchData(event, pointerCount, timestamp)
        )

        MotionEvent.ACTION_UP -> listOf(
            TouchData(
                x = event.x,
                y = event.y,
                action = TouchAction.UP,
                timestamp = timestamp,
                pointerId = event.getPointerId(0),
                pointerCount = pointerCount
            )
        )

        MotionEvent.ACTION_CANCEL -> listOf(
            TouchData(
                x = event.x,
                y = event.y,
                action = TouchAction.CANCEL,
                timestamp = timestamp,
                pointerId = event.getPointerId(0),
                pointerCount = pointerCount
            )
        )

        else -> null
    }
}

/**
 * MOVE 事件映射：双指时携带第二指坐标，单指保持原逻辑。
 * 历史采样（[MotionEvent.getHistorySize]）按时间顺序展开在前，当前采样收尾——
 * 事件合并（batch）不再丢失中间位置，拖动视角更平滑、速度追踪更准。
 * internal 供单测。
 */
internal fun toMoveTouchData(event: MotionEvent, pointerCount: Int, timestamp: Long): List<TouchData> {
    val historySize = event.historySize
    val result = ArrayList<TouchData>(historySize + 1)
    val pointerId = event.getPointerId(event.actionIndex)
    if (pointerCount >= 2) {
        for (i in 0 until historySize) {
            result.add(
                TouchData(
                    x = event.getHistoricalX(0, i),
                    y = event.getHistoricalY(0, i),
                    action = TouchAction.MOVE,
                    timestamp = event.getHistoricalEventTime(i) * 1_000_000L,
                    pointerId = pointerId,
                    pointerCount = pointerCount,
                    pointer2X = event.getHistoricalX(1, i),
                    pointer2Y = event.getHistoricalY(1, i)
                )
            )
        }
        result.add(
            TouchData(
                x = event.getX(0),
                y = event.getY(0),
                action = TouchAction.MOVE,
                timestamp = timestamp,
                pointerId = pointerId,
                pointerCount = pointerCount,
                pointer2X = event.getX(1),
                pointer2Y = event.getY(1)
            )
        )
    } else {
        for (i in 0 until historySize) {
            result.add(
                TouchData(
                    x = event.getHistoricalX(0, i),
                    y = event.getHistoricalY(0, i),
                    action = TouchAction.MOVE,
                    timestamp = event.getHistoricalEventTime(i) * 1_000_000L,
                    pointerId = pointerId,
                    pointerCount = pointerCount
                )
            )
        }
        result.add(
            TouchData(
                x = event.x,
                y = event.y,
                action = TouchAction.MOVE,
                timestamp = timestamp,
                pointerId = pointerId,
                pointerCount = pointerCount
            )
        )
    }
    return result
}

/** POINTER_UP 事件映射：上报仍在屏幕上的剩余手指位置（引擎据此恢复平移不跳变） */
private fun toPointerUpTouchData(event: MotionEvent, pointerCount: Int, timestamp: Long): TouchData {
    val remainingIndex = if (event.actionIndex == 0) 1 else 0
    return TouchData(
        x = event.getX(remainingIndex),
        y = event.getY(remainingIndex),
        action = TouchAction.UP,
        timestamp = timestamp,
        pointerId = event.getPointerId(remainingIndex),
        pointerCount = pointerCount
    )
}

/**
 * 推进云层动画并刷新 [NativeSurfaceView.cloudData]（渲染线程每节拍调用）。
 *
 * 顶层函数而非 [NativeSurfaceView] 成员——保持类函数数低于 TooManyFunctions
 * 阈值（thresholdInClasses=20，本类已 19 个成员函数）。
 *
 * @param host 渲染宿主（读云层动画引擎、写实例快照）
 * @param nowNs 当前时间戳（纳秒）
 * @return 本帧是否必须渲染（云朵移动/生成/销毁）
 */
private fun advanceClouds(host: NativeSurfaceView, nowNs: Long): Boolean {
    val dirty = host.cloudAnimator.update(nowNs)
    if (dirty) {
        host.cloudData = host.cloudAnimator.snapshot()
    }
    return dirty
}

/**
 * surfaceChanged 同尺寸去抖判定：
 * 键盘/insets 噪声可能派发与上次相同尺寸的 surfaceChanged——真实 resize 会触发
 * Vulkan swapchain+管线全量重建（黑帧窗口期），同尺寸事件直接丢弃。
 * internal 供单测。
 */
internal fun shouldSkipSurfaceResize(lastWidth: Int, lastHeight: Int, width: Int, height: Int): Boolean =
    lastWidth == width && lastHeight == height

/**
 * pending resize 消费守卫：仅 GPU 后端且渲染器就绪时触 JNI——
 * SOFTWARE 模式无 C++ swapchain 语义（Canvas 自适应视口），消费调用是纯开销。
 * 顶层纯函数供单测锁定（JVM 测试无法触真 JNI）。
 */
internal fun shouldConsumeNativeResize(isReady: Boolean, mode: NativeSurfaceView.RenderMode): Boolean =
    isReady && mode != NativeSurfaceView.RenderMode.SOFTWARE

/**
 * 结构化回退事件组装与上报：from = 本轮尝试的后端，
 * to = 下一个降级目标（GLES 未试过 → GLES，否则 SOFTWARE）；
 * gpu/driver/api 取 Vulkan 物理设备探测缓存（prewarm/上次会话遗留，可能为空——
 * 空值本身即「未探测到 Vulkan 设备」的有效信号）。
 *
 * 顶层函数而非 [NativeSurfaceView] 成员——保持类函数数低于 TooManyFunctions
 * 阈值（thresholdInClasses=20，同 advanceClouds 纪律）。
 */
private fun reportRenderFallback(
    host: NativeSurfaceView,
    stage: String,
    extra: String? = null,
) {
    val deviceName = NativeBridge.getVulkanDeviceName()
    RenderFallbackReporter.report(
        RenderFallbackReporter.Event(
            from = if (host.initBackendGles) "GLES" else "VULKAN",
            to = if (!host.initBackendGles && !host.glesTriedAfterVulkan) "GLES" else "SOFTWARE",
            stage = stage,
            elapsedMs = if (host.renderInitStartMs > 0) {
                System.currentTimeMillis() - host.renderInitStartMs
            } else {
                0L
            },
            gpu = deviceName.ifEmpty { null },
            driverVersion = NativeBridge.getVulkanDriverVersion(),
            apiVersion = NativeBridge.getVulkanApiVersion(),
            extra = extra,
        )
    )
}
