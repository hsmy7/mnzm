package com.xianxia.sect.ui.game.sect

import android.graphics.Bitmap
import com.xianxia.sect.core.nativebridge.NativeBridge
import com.xianxia.sect.core.render.RenderMetrics
import java.nio.ByteBuffer

/** 每像素字节数（RGBA_8888） */
private const val RGBA_BYTES_PER_PIXEL = 4

/**
 * 图集异步流水线（图集拼装移出主线程）。
 *
 * 独立类而非 [NativeSurfaceView] 成员——两点理由：
 * 1. 渲染宿主已贴 detekt TooManyFunctions 阈值（thresholdInClasses=20）；
 * 2. "两段式流水线"自成一套状态机（纪元守卫 / ASTC 上传失败回退 / 软渲分流），
 *    内聚到一个类比塞进渲染宿主更清晰。
 *
 * ## 为什么要拆两段
 * C++ `NativeBridge` 的 `g_renderer` 是**无锁裸指针**，渲染线程每帧
 * beginFrame/draw/submit 并发进入——因此"上传"不能搬到后台线程。拆分后
 * 各段都在正确的线程上：
 *
 * 1. **后台线程** [prepareAtlas]：ASTC 资产读取 → **离线 RGBA 产物读取**
 *    （assets 裸像素 + mip 链）。图集在构建期已降采样完毕，这段只剩
 *    `AssetManager` 读取 + 一次性解码/映射。
 * 2. **主线程**（经 `post`）[uploadAtlas]：只做一次 GPU 上传调用
 *    （staging memcpy + vkCmdCopy / GL 入队），随后回调 [start] 的 onReady。
 *
 * 线程模型：[start] 在主线程调用并捕获参数；[buildThread] 承载全部重活；
 * 结果经 `mainHandler.post` 回主线程，post 的 happens-before 保证
 * [AtlasPayload] 字段对主线程可见，无需额外同步。
 *
 * ## B15 / R6.1：Canvas 运行时拼装的退役
 *
 * 本类历史上走 `SectAtlasAssembler.buildAtlasBitmap()`——在设备上逐精灵解码 +
 * Canvas 画布拼装 2048² 位图（启动期 Canvas 依赖 + 数百毫秒 + 16MB 位图与逐
 * 精灵解码中间缓冲的内存尖峰），服务 RGBA 回退臂 / mip 链源 / Canvas 软渲染
 * 三条支路。B15 起三条支路统一改为消费 `build-atlas.mjs` 同源产出的**离线产物**
 * （`assets/atlas/atlas-rgba-raw.bin` / `atlas-rgba-mips.bin`）：
 * **零 Canvas、零逐精灵循环、零运行时降采样**。ASTC 直传路径不变。
 */
internal class AtlasAsyncPipeline(private val view: NativeSurfaceView) {

    /** 主线程 Handler（上传段专用；不用 View.post——未 attach 的 View 会把 runnable 塞进 run queue 永不执行） */
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    /** 在飞的拼装线程（surface 销毁时由 [cancel] 打断） */
    @Volatile
    private var buildThread: Thread? = null

    /**
     * 启动一轮流水线：后台拼装 → 主线程上传。
     *
     * @param context 资源上下文
     * @param gen 发起时的 surface 纪元（stale 守卫；发起线程读取后传入）
     * @param software 是否软渲染路径（软渲不上传 GPU，只交 Bitmap）
     * @param allowCompressed 是否允许尝试 ASTC 压缩图集
     * @param onReady 完成回调（主线程；参数 = 纹理 ID，0 = 软渲染路径或失败）
     */
    fun start(
        context: android.content.Context,
        gen: Int,
        software: Boolean,
        allowCompressed: Boolean,
        onReady: (Int) -> Unit
    ) {
        buildThread = kotlin.concurrent.thread(name = "AtlasBuild", isDaemon = true) {
            val payload = prepareAtlas(context, software, allowCompressed)
            if (gen != view.surfaceProvider.generation) return@thread
            mainHandler.post {
                // 二次纪元守卫：排队期间 surface 可能已销毁
                if (gen != view.surfaceProvider.generation) return@post
                buildThread = null
                val texId = uploadAtlas(context, payload)
                // ASTC 上传失败 → 强制 RGBA 重跑一轮（第二轮 allowCompressed=false，
                // 不会再递归——其产物 ktx 恒为 null）
                if (texId == 0 && payload.ktx != null) {
                    android.util.Log.i(
                        NativeSurfaceView.LOG_TAG,
                        "buildAtlas: ASTC upload rejected, re-running RGBA assembly"
                    )
                    start(context, gen, software, allowCompressed = false, onReady)
                    return@post
                }
                // 图集真正可用才开始淡入（异步拼装耗时期间保持纯黑，
                //   避免"地图未就绪却已淡入完成"的空白窗口）
                if (!payload.failed) view.fadeIn()
                onReady(texId)
            }
        }
    }

    /** 打断在飞的拼装并清引用（surface 销毁路径）。不 join——拼装是 CPU 密集循环，主线程 join 会退化成"换了个地方阻塞"。 */
    fun cancel() {
        buildThread?.interrupt()
        buildThread = null
    }

    /**
     * 准备图集载荷（**重活**，只能由 [start] 在后台线程驱动）。
     *
     * 段内顺序：ASTC 资产读取（IO）→ 失败/不启用则读取**离线 RGBA 产物**
     * （B15：assets 裸像素 + mip 链，一次性映射，零解码循环）。全程不触碰
     * C++ renderer，也不读取 View 可变状态（渲染模式由调用方捕获后传入）。
     *
     * @param software true = 软渲染路径（产出 Bitmap，不转换像素）
     * @param allowCompressed 是否允许尝试 ASTC 压缩图集
     */
    internal fun prepareAtlas(
        context: android.content.Context,
        software: Boolean,
        allowCompressed: Boolean
    ): AtlasPayload {
        val payload = if (allowCompressed) {
            compressedAtlasReader(context)
                ?.let { AtlasPayload(ktx = it) }
                ?: prepareOfflineRgbaAtlas(context, software)
        } else {
            prepareOfflineRgbaAtlas(context, software)
        }
        // 地面纹理（map_grass_1 64²）解码 + RGBA 编码在后台线程执行，
        //   不与图集上传叠加阻塞主线程。软渲路径无 GPU 地面纹理（走 atlasBitmap），跳过。
        // 解码失败模式无稳定异常契约（资源损坏/ROM 差异），上传路径有主线程现场
        // 解码兜底，此处按非关键路径全捕获处理（与文件内其余段一致）。
        if (!software && !payload.failed) {
            @Suppress("TooGenericExceptionCaught")
            try {
                val opts = android.graphics.BitmapFactory.Options().apply { inScaled = false }
                android.graphics.BitmapFactory.decodeResource(
                    context.resources, com.xianxia.sect.feature.game.R.drawable.map_grass_1, opts
                )?.let { bmp ->
                    payload.groundPixels = encodeBitmapToRgbaBuffer(bmp)
                    payload.groundWidth = bmp.width
                    payload.groundHeight = bmp.height
                }
            } catch (t: Throwable) {
                android.util.Log.e(NativeSurfaceView.LOG_TAG, "prepareAtlas: ground texture decode failed", t)
            }
        }
        return payload
    }

    /** ASTC 资产读取 → 委托 View 的注入点（测试可换 Fake；默认读 assets KTX，null = 缺失/IO 异常） */
    private fun compressedAtlasReader(context: android.content.Context): ByteArray? =
        view.compressedAtlasReader(context)

    /**
     * 离线 RGBA 产物消费段（B15：软渲分流 + 零解码映射）。
     *
     * 两条支路都只读 assets，**不做**逐精灵解码 / Canvas 拼装 / 运行时降采样：
     * - `software = true` → 裸像素 2048² 直接填 ARGB_8888 Bitmap（`copyPixelsFromBuffer`，
     *   一次 native memcpy）；
     * - `software = false` → mip 链裸像素包成 direct [ByteBuffer]（`map()` 零拷贝）
     *   交主线程多级上传。
     *
     * 任一环失败（资产缺失/IO/尺寸不符）记指标并返回 [AtlasPayload.failed]
     * ——不静默降级回运行时拼装（该路径已随 B15 退役）。
     */
    private fun prepareOfflineRgbaAtlas(
        context: android.content.Context,
        software: Boolean
    ): AtlasPayload = if (software) {
        decodeOfflineSoftwareBitmap(context)?.let { AtlasPayload(softwareBitmap = it) }
            ?: AtlasPayload(failed = true)
    } else {
        openOfflineMipChain(context)?.let {
            AtlasPayload(
                rgbaMipPixels = it.buffer,
                mipCount = it.mipCount,
                width = it.widths.first(),
                height = it.heights.first()
            )
        } ?: AtlasPayload(failed = true)
    }

    /**
     * 裸像素 → ARGB_8888 位图（软渲染路径像素源）。
     *
     * 释放语义：`copyPixelsFromBuffer` 后直接缓冲即可被 GC 回收，Bitmap 独立持有
     * 一份像素——`assets` 的 16MB 缓冲不驻留（软渲仅需一份）。
     * 传入缓冲为**直通 alpha** RGBA8888（与 ARGB_8888 在小端内存布局上等值：
     * 逐字节 R,G,B,A），无需 swizzle。
     *
     * @return 2048² 位图；资产缺失/尺寸不符时 null（指标已记）
     */
    // 资产读取/位图分配失败模式无稳定异常契约（IO/OOM/ROM 差异），全捕获按非关键路径处理
    @Suppress("TooGenericExceptionCaught")
    private fun decodeOfflineSoftwareBitmap(context: android.content.Context): Bitmap? = try {
        val spec = OfflineAtlasAssets.readRawSpec(context)
        val buf = readRawPixels(context, spec.rawPath, spec.width, spec.height)
        if (buf == null) {
            RenderMetrics.atlasBuildFailed.incrementAndGet()
            null
        } else {
            val bmp = Bitmap.createBitmap(spec.width, spec.height, Bitmap.Config.ARGB_8888)
            bmp.copyPixelsFromBuffer(buf)
            android.util.Log.i(
                NativeSurfaceView.LOG_TAG,
                "buildAtlas: offline RGBA bitmap ${spec.width}x${spec.height} (zero canvas, zero per-sprite)"
            )
            bmp
        }
    } catch (t: Throwable) {
        android.util.Log.e(NativeSurfaceView.LOG_TAG, "buildAtlas: offline RGBA bitmap failed", t)
        RenderMetrics.atlasBuildFailed.incrementAndGet()
        null
    }

    /**
     * mip 链裸像素 → direct [ByteBuffer]（GPU 多级上传源）。
     *
     * 用 `FileChannel.map(READ_ONLY)` 直接映射 assets 文件——**零拷贝**，不占用
     * 堆/直接内存（历史上此处要级联缩放 11 级、峰值 ~22MB）。native 侧
     * `GetDirectBufferAddress` 可直接读。
     * level-major 紧凑布局，首级 = 完整 2048² 图集，天然兼容单级回退。
     *
     * @return mip 链载荷；资产缺失/尺寸不符时 null（指标已记）
     */
    // 资产映射失败模式无稳定异常契约（IO/映射失败/ROM 差异），全捕获按非关键路径处理
    @Suppress("TooGenericExceptionCaught")
    private fun openOfflineMipChain(context: android.content.Context): MipChainPayload? = try {
        val spec = OfflineAtlasAssets.readRawSpec(context)
        val levelCount = spec.mipWidths.size
        val mapped = mapAsset(context, spec.mipPath)
        val expected = spec.mipWidths.indices.sumOf { i ->
            val w = spec.mipWidths[i]
            val h = spec.mipHeights[i]
            w.toLong() * h * RGBA_BYTES_PER_PIXEL
        }
        if (mapped == null || mapped.capacity().toLong() < expected) {
            android.util.Log.w(
                NativeSurfaceView.LOG_TAG,
                "buildAtlas: offline mip chain missing/short (need ${expected}B)"
            )
            RenderMetrics.atlasBuildFailed.incrementAndGet()
            null
        } else {
            android.util.Log.i(
                NativeSurfaceView.LOG_TAG,
                "buildAtlas: offline mip chain mapped ${mapped.capacity()}B / $levelCount levels"
            )
            MipChainPayload(
                mapped,
                spec.mipWidths.toIntArray(),
                spec.mipHeights.toIntArray()
            )
        }
    } catch (t: Throwable) {
        android.util.Log.e(NativeSurfaceView.LOG_TAG, "buildAtlas: offline mip chain failed", t)
        RenderMetrics.atlasBuildFailed.incrementAndGet()
        null
    }

    /** 读 assets 裸像素为 direct 缓冲（软渲位图源；容量不足/资产缺失返回 null） */
    private fun readRawPixels(
        context: android.content.Context,
        assetPath: String,
        width: Int,
        height: Int
    ): ByteBuffer? {
        val mapped = mapAsset(context, assetPath) ?: return null
        val need = width.toLong() * height * RGBA_BYTES_PER_PIXEL
        return if (mapped.capacity().toLong() < need) null else mapped
    }

    /**
     * assets → 只读 direct [ByteBuffer]（`FileChannel.map`，零拷贝）。
     *
     * 失败（资产缺失/IO 异常）返回 null 并记日志——调用方按支路决定是否算失败。
     * 映射视图不持有 AssetFileDescriptor 生命周期（`openFd` 随函数退出关闭，
     * 映射在进程内保持有效——Android 上 assets 映射生命周期由 VM 管理）。
     */
    // 资产 IO 失败模式无稳定异常契约（缺失/损坏/ROM 差异），全捕获按非关键路径处理
    @Suppress("TooGenericExceptionCaught")
    private fun mapAsset(context: android.content.Context, assetPath: String): ByteBuffer? = try {
        context.assets.openFd(assetPath).use { afd ->
            java.io.FileInputStream(afd.fileDescriptor).channel.use { ch ->
                ch.map(
                    java.nio.channels.FileChannel.MapMode.READ_ONLY,
                    afd.startOffset,
                    afd.length
                )
            }
        }
    } catch (t: Throwable) {
        android.util.Log.w(NativeSurfaceView.LOG_TAG, "buildAtlas: asset map failed '$assetPath': ${t.message}")
        null
    }

    /** ASTC 上传 → 委托 View 的注入点（测试可换 Fake；默认 NativeBridge.uploadCompressedAtlas） */
    private fun compressedAtlasUploader(bytes: ByteArray): Int =
        view.compressedAtlasUploader(bytes)

    /**
     * 上传已拼装好的图集（**轻活**，[prepareAtlas] 的对偶；主线程调用）。
     *
     * 只做一次 native 上传调用 + 状态赋值——不得在此做任何解码/转换/IO。
     *
     * @return 纹理 ID；0 = 软渲染路径（Bitmap 已挂到 `atlasBitmap`）/ 拼装失败
     */
    internal fun uploadAtlas(context: android.content.Context, payload: AtlasPayload): Int = when {
        payload.failed -> 0
        payload.ktx != null -> uploadCompressedAtlas(context, payload)
        payload.softwareBitmap != null -> installSoftwareAtlas(payload.softwareBitmap)
        else -> uploadRgbaAtlas(context, payload)
    }

    /** ASTC 上传分支：成功后顺带上传地面纹理（返回 0 触发 [start] 的 RGBA 回退） */
    private fun uploadCompressedAtlas(context: android.content.Context, payload: AtlasPayload): Int {
        val id = compressedAtlasUploader(payload.ktx ?: return 0)
        if (id != 0) {
            android.util.Log.i(
                NativeSurfaceView.LOG_TAG,
                "buildAtlas: ASTC compressed atlas uploaded (id=$id)"
            )
            uploadGroundTexture(context, payload)
        }
        return id
    }

    /** 软渲染分支：不上传 GPU，Bitmap 交给 Canvas 后端（return 0 = "无 GPU 纹理"信号） */
    private fun installSoftwareAtlas(bitmap: Bitmap): Int {
        view.atlasBitmap = bitmap
        android.util.Log.i(
            NativeSurfaceView.LOG_TAG,
            "buildAtlas: software mode, bitmap kept in memory"
        )
        return 0
    }

    /** RGBA 上传分支：mip 链多级上传，native 拒绝时单级回退 */
    private fun uploadRgbaAtlas(context: android.content.Context, payload: AtlasPayload): Int {
        val texId = uploadMipChainOrFallback(payload)
        uploadGroundTexture(context, payload)
        // 不调 recycle()：避免国产 ROM NativeAllocationRegistry CleanerThunk
        //   double-free SIGABRT。Vulkan/GLES 模式下 atlasBitmap 不
        //   会被软渲染路径读取，置 null 让 GC 回收即可。
        view.atlasBitmap = null
        return texId
    }

    /**
     * mip 链上传：native 拒绝（后端不支持/校验失败/部分驱动异常）
     * 时**单级回退**——mip 链为 level-major 紧凑布局，首级 = 完整图集像素，
     * [NativeBridge.uploadTextureDirect] 按 buffer 起始地址读取。
     */
    private fun uploadMipChainOrFallback(payload: AtlasPayload): Int =
        tryMipChainUpload(payload) ?: fallbackSingleLevelUpload(payload)

    /** mip 链上传尝试（返回 null = 无链/被拒绝，走单级回退并记日志） */
    private fun tryMipChainUpload(payload: AtlasPayload): Int? {
        val mip = payload.rgbaMipPixels?.takeIf { payload.mipCount > 1 } ?: return null
        return view.mipChainUploader(mip, payload.width, payload.height, payload.mipCount)
            .takeIf { it != 0 }
            .also {
                if (it == null) {
                    android.util.Log.w(
                        NativeSurfaceView.LOG_TAG,
                        "buildAtlas: mip chain upload rejected, falling back to single-level RGBA"
                    )
                }
            }
    }

    /** 单级回退：独立单级缓冲，或 mip 链首级（level-major 布局，起始即完整图集） */
    private fun fallbackSingleLevelUpload(payload: AtlasPayload): Int {
        val rgba = payload.rgbaPixels ?: payload.rgbaMipPixels ?: return 0
        return NativeBridge.uploadTextureDirect(rgba, payload.width, payload.height)
    }

    /**
     * 上传宗门地图单一无缝地面纹理（REPEAT 采样，整图铺）。
     * 独立于图集（KTX/RGBA 两路径共用），从 map_grass_1 解码 64×64 上传。
     * 调用线程：主线程（内部经 [NativeBridge] 触碰无锁的 C++ g_renderer）。
     * 解码/编码已在 [prepareAtlas] 后台完成（payload
     *   携带 groundPixels），此处仅剩一次 native 调用；后台解码失败时回退
     *   主线程现场解码兜底。
     * 解码失败模式无稳定异常契约（资源损坏/ROM 差异），全捕获按非关键路径处理。
     */
    @Suppress("TooGenericExceptionCaught")
    private fun uploadGroundTexture(context: android.content.Context, payload: AtlasPayload) {
        try {
            val pixels = payload.groundPixels
            if (pixels != null && payload.groundWidth > 0 && payload.groundHeight > 0) {
                publishGroundTextureId(
                    NativeBridge.uploadGroundTextureDirect(
                        pixels, payload.groundWidth, payload.groundHeight
                    )
                )
                return
            }
            val opts = android.graphics.BitmapFactory.Options().apply { inScaled = false }
            val bmp = android.graphics.BitmapFactory.decodeResource(
                context.resources, com.xianxia.sect.feature.game.R.drawable.map_grass_1, opts
            ) ?: return
            publishGroundTextureId(
                NativeBridge.uploadGroundTextureDirect(
                    encodeBitmapToRgbaBuffer(bmp), bmp.width, bmp.height
                )
            )
        } catch (t: Throwable) {
            android.util.Log.e(NativeSurfaceView.LOG_TAG, "uploadGroundTexture failed", t)
        }
    }

    /**
     * 回写整图地面纹理 ID 到宿主（R3.5 远景观看容量路径的就绪信号）。
     *
     * 上传失败（返回 0）时保持 0 ⇒ [FarViewGroundPolicy.groundQuadEnabled]
     * 的图集就绪门不满足 ⇒ 地面层恒走逐格绘制（降级而非黑屏）。
     */
    private fun publishGroundTextureId(texId: Int) {
        if (texId > 0) view.groundTextureId = texId
    }
}

/**
 * 图集后台拼装产物（主线程拿到本对象时重活已完成，只剩一次上传调用）。
 *
 * 三态互斥，由 [AtlasAsyncPipeline.start] 的两段式流水线消费：
 * - [ktx]：ASTC 压缩图集资产字节（主线程上传）
 * - [rgbaMipPixels]：运行时拼装的 RGBA **mip 链**（2.3，level-major 紧凑布局，
 *   首级 = 完整图集；主线程 [NativeBridge.uploadTextureMipChainDirect] 上传）
 * - [rgbaPixels]：单级 RGBA direct 缓冲区（B15 前 mip 链编码失败时的单级回退路径；
 *   离线产物路径恒走 [rgbaMipPixels]，本字段保留给既有测试与未来单级注入）
 * - [softwareBitmap]：软渲染路径位图（不上传 GPU，直接交 Canvas 后端）
 * - [failed]：拼装失败（跳过上传，指标已记）
 */
internal class AtlasPayload(
    /** ASTC KTX 资产字节（压缩路径；与其余互斥） */
    val ktx: ByteArray? = null,
    /** RGBA mip 链 direct 缓冲区（level-major 紧凑；2.3） */
    val rgbaMipPixels: ByteBuffer? = null,
    /** [rgbaMipPixels] mip 层级数（含首级完整图集） */
    val mipCount: Int = 0,
    /** 单级 RGBA direct 缓冲区（免 ByteArray 中转；回退路径） */
    val rgbaPixels: ByteBuffer? = null,
    /** 图集宽（像素；mip 链首级/单级缓冲共用） */
    val width: Int = 0,
    /** 图集高（像素） */
    val height: Int = 0,
    /** 软渲染路径位图（不上传 GPU；与其余互斥） */
    val softwareBitmap: Bitmap? = null,
    /** 拼装是否失败（true = 跳过上传，指标已记） */
    val failed: Boolean = false
) {
    // ── 地面纹理（后台解码/编码，主线程仅上传）──
    // var 而非 val：payload 由 prepareAtlas 各分支构造后统一补填（map_grass_1
    // 与主图集产物互不依赖，无需进入每个分支的构造参数）。
    /** 地面纹理 RGBA 像素（后台编码；null = 解码失败，上传路径回退现场解码） */
    var groundPixels: ByteBuffer? = null
    /** 地面纹理宽（像素；groundPixels 有效时 > 0） */
    var groundWidth: Int = 0
    /** 地面纹理高（像素） */
    var groundHeight: Int = 0
}

/**
 * RGBA mip 链载荷（运行时**只承载**离线产物；B15 前的级联编码已退役）。
 *
 * @property buffer level-major 紧凑 direct 缓冲区（首级 = 完整图集，天然兼容单级回退）
 * @property widths 各级宽（mip0 在前）
 * @property heights 各级高
 */
internal class MipChainPayload(
    val buffer: ByteBuffer,
    val widths: IntArray,
    val heights: IntArray
) {
    /** mip 层级数（含首级） */
    val mipCount: Int get() = widths.size
}

/**
 * 离线图集资产清单（`assets/atlas/atlas-rgba-manifest.json` 的消费视图）。
 *
 * 单一职责：把构建期产物的元数据（裸像素尺寸 / mip 各级尺寸 / 资产文件名）
 * 从 assets 解析出来，供 [AtlasAsyncPipeline] 两条支路共用。**不含**布局数值
 * （槽位权威在 `SpriteAtlasDef`），只有产物自身的几何。
 *
 * @property rawPath 2048² 直通 alpha 裸像素资产路径
 * @property mipPath level-major mip 链裸像素资产路径
 * @property width 裸像素宽（= [SpriteAtlasDef.ATLAS_W] × manifest.scale）
 * @property height 裸像素高
 * @property mipWidths 各级 mip 宽（mip0 在前）
 * @property mipHeights 各级 mip 高
 */
internal class OfflineAtlasSpec(
    val rawPath: String,
    val mipPath: String,
    val width: Int,
    val height: Int,
    val mipWidths: List<Int>,
    val mipHeights: List<Int>
)

/**
 * 离线图集资产解析。
 *
 * 与 `scripts/atlas-offline-rgba.mjs` 的 manifest 契约一一对应：脚本写什么字段，
 * 这里读什么字段。**路径常量与 `SectAtlasPrefetch.ASTC_ATLAS_ASSET_PATH` 同目录**
 * （`assets/atlas/`），便于资产清单审查时一处看全。
 */
internal object OfflineAtlasAssets {

    /** 离线 RGBA 裸像素资产（2048² 直通 alpha） */
    const val RAW_ASSET_PATH = "atlas/atlas-rgba-raw.bin"

    /** 离线 RGBA mip 链资产（level-major 紧凑） */
    const val MIP_ASSET_PATH = "atlas/atlas-rgba-mips.bin"

    /** 离线产物清单（尺寸/mip 级几何/校验和） */
    const val MANIFEST_ASSET_PATH = "atlas/atlas-rgba-manifest.json"

    /**
     * 读取清单并解析产物几何。
     *
     * 用 `org.json`（Android 平台自带，零新增依赖）——字段缺失/类型不符直接抛
     * `JSONException`，由调用方按「产物不可用」处理（fail 而非静默用错尺寸）。
     *
     * @throws org.json.JSONException 清单缺失/字段非法
     * @throws java.io.IOException assets 读取失败
     */
    fun readRawSpec(context: android.content.Context): OfflineAtlasSpec {
        val text = context.assets.open(MANIFEST_ASSET_PATH).use { input ->
            input.readBytes().toString(Charsets.UTF_8)
        }
        val json = org.json.JSONObject(text)
        val width = json.getInt("width")
        val height = json.getInt("height")
        val mipWidths = json.getJSONArray("mipWidths").let { arr ->
            (0 until arr.length()).map { arr.getInt(it) }
        }
        // 清单只记各级宽度（产物恒为正方图集，宽高同源）——高按同值展开，
        // 与脚本的 mipWidths 由同一循环产出保持等价。
        val mipHeights = mipWidths
        return OfflineAtlasSpec(
            rawPath = json.optString("rawFile", "atlas-rgba-raw.bin").let { "atlas/$it" },
            mipPath = json.optString("mipFile", "atlas-rgba-mips.bin").let { "atlas/$it" },
            width = width,
            height = height,
            mipWidths = mipWidths,
            mipHeights = mipHeights
        )
    }
}

/**
 * ARGB [Bitmap] → RGBA **direct** 字节缓冲区（GPU 纹理上传用）。
 *
     * 输出 direct [ByteBuffer] 而非 ByteArray，JNI 侧走
     * `GetDirectBufferAddress` 零拷贝读取——经 ByteArray 中转会在
     * 2048² 图集上瞬时并存 Bitmap(16MB) + IntArray(16MB) + ByteArray(16MB)，
     * 低端机 OOM 高危。
 *
 * 实现要点：
 * - **逐行**读取（行缓冲仅 width×4 字节，约 8KB），不再分配整图 IntArray；
 * - 缓冲区按 `ByteOrder.nativeOrder()` 分配，配合 [java.nio.IntBuffer] 视图
 *   按 int 写入 → 内存字节序为 R,G,B,A（与 C++ 侧期望一致）；
 * - 逐像素 swizzle 为 ARGB(0xAARRGGBB) → RGBA(0xAABBGGRR)。
 *
 * 调用线程：后台（拼装线程）——纯 CPU，不触碰任何 View/C++ 状态。
 * B15 起仅地面纹理（64×64）走此函数；图集本体改走离线产物零拷贝映射。
 *
 * @param bitmap 源位图（ARGB_8888）
 * @return 容量为 width*height*4 的 direct 缓冲区，position 在末尾（可直接上传）
 */
internal fun encodeBitmapToRgbaBuffer(bitmap: Bitmap): ByteBuffer {
    val w = bitmap.width
    val h = bitmap.height
    val buffer = ByteBuffer.allocateDirect(w * h * RGBA_BYTES_PER_PIXEL)
        .order(java.nio.ByteOrder.nativeOrder())
    val intBuffer = buffer.asIntBuffer()
    val row = IntArray(w)
    for (y in 0 until h) {
        bitmap.getPixels(row, 0, w, 0, y, w, 1)
        for (x in 0 until w) {
            val argb = row[x]
            // 0xAARRGGBB → 0xAABBGGRR（小端内存布局即 R,G,B,A）
            row[x] = ((argb and 0xFF) shl 16) or
                (argb and 0x0000_FF00) or
                ((argb ushr 16) and 0xFF) or
                (argb and 0xFF00_0000.toInt())
        }
        intBuffer.put(row)
    }
    return buffer
}
