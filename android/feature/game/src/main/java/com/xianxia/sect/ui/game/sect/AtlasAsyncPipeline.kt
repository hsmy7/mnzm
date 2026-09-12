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
 * 1. **后台线程** [prepareAtlas]：ASTC 资产读取 → 逐精灵解码 + Canvas 拼装
 *    → ARGB→RGBA 转换。2048² 图集在低端机上可达数百毫秒，这段就是原
 *    ANR/OOM 高危路径。
 * 2. **主线程**（经 `post`）[uploadAtlas]：只做一次 GPU 上传调用
 *    （staging memcpy + vkCmdCopy / GL 入队），随后回调 [start] 的 onReady。
 *
 * 线程模型：[start] 在主线程调用并捕获参数；[buildThread] 承载全部重活；
 * 结果经 `mainHandler.post` 回主线程，post 的 happens-before 保证
 * [AtlasPayload] 字段对主线程可见，无需额外同步。
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
     * 拼装图集（**重活**，只能由 [start] 在后台线程驱动）。
     *
     * 段内顺序：ASTC 资产读取（IO）→ 失败/不启用则运行时逐精灵解码 +
     * Canvas 拼装 + ARGB→RGBA 转换（CPU 密集）。全程不触碰 C++ renderer，
     * 也不读取 View 可变状态（渲染模式由调用方捕获后传入）。
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
                ?: prepareRgbaAtlas(context, software)
        } else {
            prepareRgbaAtlas(context, software)
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

    /** ASTC 上传 → 委托 View 的注入点（测试可换 Fake；默认 NativeBridge.uploadCompressedAtlas） */
    private fun compressedAtlasUploader(bytes: ByteArray): Int =
        view.compressedAtlasUploader(bytes)

    /** 运行时 RGBA 拼装段（软渲分流 + 像素编码，任一失败返回 [AtlasPayload.failed]） */
    private fun prepareRgbaAtlas(context: android.content.Context, software: Boolean): AtlasPayload {
        val atlas = assembleAtlasBitmap(context) ?: return AtlasPayload(failed = true)
        return when {
            software -> AtlasPayload(softwareBitmap = atlas)
            // 优先 mip 链；编码失败 → 单级回退
            else -> encodeMipChainOrNull(atlas)?.let {
                AtlasPayload(
                    rgbaMipPixels = it.buffer,
                    mipCount = it.mipCount,
                    width = atlas.width,
                    height = atlas.height
                )
            } ?: encodeAtlasPixels(atlas)?.let {
                AtlasPayload(rgbaPixels = it, width = atlas.width, height = atlas.height)
            } ?: AtlasPayload(failed = true)
        }
    }

    /** mip 链编码（失败记指标并返回 null——单级回退由调用方承接） */
    // 级联缩放/缓冲分配失败模式无稳定异常契约（OOM/ROM 差异），全捕获按非关键路径处理
    @Suppress("TooGenericExceptionCaught")
    private fun encodeMipChainOrNull(atlas: Bitmap): MipChainPayload? = try {
        encodeBitmapToRgbaMipChain(atlas)
    } catch (t: Throwable) {
        android.util.Log.e(NativeSurfaceView.LOG_TAG, "buildAtlas: RGBA mip chain encode failed", t)
        RenderMetrics.atlasBuildFailed.incrementAndGet()
        null
    }

    /** 逐精灵解码 + Canvas 拼装（失败记指标并返回 null） */
    // 子精灵/位图创建失败模式无稳定异常契约（资源损坏/OOM/ROM 差异），全捕获按非关键路径处理
    @Suppress("TooGenericExceptionCaught")
    private fun assembleAtlasBitmap(context: android.content.Context): Bitmap? = try {
        SectAtlasAssembler.buildAtlasBitmap(context)
    } catch (t: Throwable) {
        android.util.Log.e(NativeSurfaceView.LOG_TAG, "buildAtlas: assembly failed", t)
        RenderMetrics.atlasBuildFailed.incrementAndGet()
        null
    }

    /** ARGB→RGBA direct 缓冲区编码（失败记指标并返回 null） */
    // 像素读取/缓冲分配失败模式无稳定异常契约（OOM/ROM 差异），全捕获按非关键路径处理
    @Suppress("TooGenericExceptionCaught")
    private fun encodeAtlasPixels(atlas: Bitmap): ByteBuffer? = try {
        encodeBitmapToRgbaBuffer(atlas)
    } catch (t: Throwable) {
        android.util.Log.e(NativeSurfaceView.LOG_TAG, "buildAtlas: RGBA encode failed", t)
        RenderMetrics.atlasBuildFailed.incrementAndGet()
        null
    }

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
                NativeBridge.uploadGroundTextureDirect(
                    pixels, payload.groundWidth, payload.groundHeight
                )
                return
            }
            val opts = android.graphics.BitmapFactory.Options().apply { inScaled = false }
            val bmp = android.graphics.BitmapFactory.decodeResource(
                context.resources, com.xianxia.sect.feature.game.R.drawable.map_grass_1, opts
            ) ?: return
            NativeBridge.uploadGroundTextureDirect(encodeBitmapToRgbaBuffer(bmp), bmp.width, bmp.height)
        } catch (t: Throwable) {
            android.util.Log.e(NativeSurfaceView.LOG_TAG, "uploadGroundTexture failed", t)
        }
    }
}

/**
 * 图集后台拼装产物（主线程拿到本对象时重活已完成，只剩一次上传调用）。
 *
 * 三态互斥，由 [AtlasAsyncPipeline.start] 的两段式流水线消费：
 * - [ktx]：ASTC 压缩图集资产字节（主线程上传）
 * - [rgbaMipPixels]：运行时拼装的 RGBA **mip 链**（2.3，level-major 紧凑布局，
 *   首级 = 完整图集；主线程 [NativeBridge.uploadTextureMipChainDirect] 上传）
 * - [rgbaPixels]：单级 RGBA direct 缓冲区（mip 链编码失败时的单级回退路径）
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
 * RGBA mip 链编码产物（[encodeBitmapToRgbaMipChain] 输出）。
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
 * ARGB [Bitmap] → RGBA **mip 链** direct 字节缓冲区（2.3：RGBA 回退路径真 mip）。
 *
 * 从拼装位图（2048²）逐级 50% 双线性缩放（[Bitmap.createScaledBitmap] filter=true，
 * 级联生成；2048→2 共 11 级），各级像素经 ARGB→RGBA swizzle 后按 **level-major**
 * 顺序写入单一 direct [ByteBuffer]——与 C++ `uploadTextureMipChainDirect` 的
 * 逐级 VkBufferImageCopy 布局约定一致（RGBA8 每级尺寸 4 字节倍数，bufferOffset
 * 累积恒 4 字节对齐，满足 VUID）。
 *
 * 峰值内存：约 22MB（Σ level² × 4 字节 = 2048²·4/3·4），后台线程一次性，可接受。
 * 中间缩放位图生命周期随编码结束即废（不 recycle，遵循既有 double-free 规避惯例）。
 *
 * 调用线程：后台（拼装线程）——纯 CPU，不触碰任何 View/C++ 状态。
 *
 * @param source 源位图（ARGB_8888）
 * @return mip 链（最少 1 级——源 ≤2×2 时即单级）
 */
internal fun encodeBitmapToRgbaMipChain(source: Bitmap): MipChainPayload {
    // 级联 50% 双线性缩放：2048, 1024, ..., 2（>2 才继续，11 级止）
    val levels = ArrayList<Bitmap>(12)
    var current: Bitmap = source
    levels.add(current)
    while (current.width > 2 && current.height > 2) {
        current = Bitmap.createScaledBitmap(current, current.width / 2, current.height / 2, true)
        levels.add(current)
    }

    val widths = IntArray(levels.size)
    val heights = IntArray(levels.size)
    var totalBytes = 0L
    for ((i, b) in levels.withIndex()) {
        widths[i] = b.width
        heights[i] = b.height
        totalBytes += b.width.toLong() * b.height * RGBA_BYTES_PER_PIXEL
    }
    val buffer = ByteBuffer.allocateDirect(totalBytes.toInt())
        .order(java.nio.ByteOrder.nativeOrder())
    val intView = buffer.asIntBuffer()
    val row = IntArray(widths.max())
    // level-major 顺序逐级编码（首级 = 完整图集，单级回退可直接复用本缓冲）
    for (b in levels) {
        val w = b.width
        val h = b.height
        for (y in 0 until h) {
            b.getPixels(row, 0, w, 0, y, w, 1)
            for (x in 0 until w) {
                val argb = row[x]
                // 0xAARRGGBB → 0xAABBGGRR（小端内存布局即 R,G,B,A）
                row[x] = ((argb and 0xFF) shl 16) or
                    (argb and 0x0000_FF00) or
                    ((argb ushr 16) and 0xFF) or
                    (argb and 0xFF00_0000.toInt())
            }
            intView.put(row, 0, w)
        }
    }
    return MipChainPayload(buffer, widths, heights)
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
