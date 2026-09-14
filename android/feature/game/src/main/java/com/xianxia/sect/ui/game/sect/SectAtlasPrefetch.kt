package com.xianxia.sect.ui.game.sect

import android.content.Context
import android.util.Log
import java.util.concurrent.atomic.AtomicReference

/**
 * ASTC 压缩图集资产预取缓存。
 *
 * ## 解决什么问题
 * 原链路中 21.3MB `atlas/atlas_astc.ktx` 的读取发生在 surface 就绪**之后**
 * （[AtlasAsyncPipeline.prepareAtlas] 后台线程，由 onRendererReady 触发）——即
 * Loading 已结束、玩家已进入地图画面，读文件的 IO 时间直接叠加在"图集未就绪、 * 只画天空"的窗口上。本对象让 GameActivity 在 boot 数据阶段并行预读该资产，
 * surface 就绪后 [AtlasAsyncPipeline] 直接消费缓存字节，立即进入上传。
 *
 * ## 生命周期
 * - `prefetch`：GameActivity onCreate（IO 线程）调用一次，读入后驻留内存；
 * - `consume`：[NativeSurfaceView] 的 compressedAtlasReader 默认实现优先取用，
 *   **取用即清空**（上传完成后字节不再需要，避免 21MB 级驻留）；
 * - `clear`：onTrimMemory(RUNNING_CRITICAL/COMPLETE) 或非 ASTC 分流时丢弃——
 *   未消费即丢弃是安全的，AtlasAsyncPipeline 会回退到现场读取。
 *
 * 线程模型：[AtomicReference] 保证 IO 线程写 / 主线程读的可见性与原子消费。
 */
object SectAtlasPrefetch {

    /** 日志标签（与 NativeSurfaceView 同源，便于按链路过滤） */
    private const val TAG = NativeSurfaceView.LOG_TAG

    /** ASTC 压缩图集资产路径（scripts/build-atlas.mjs 产物；原常量自 NativeSurfaceView 收敛至此） */
    const val ASTC_ATLAS_ASSET_PATH = "atlas/atlas_astc.ktx"

    private val cached = AtomicReference<ByteArray?>(null)

    /** 是否已有未消费的预取缓存（诊断用） */
    val isPrefetched: Boolean get() = cached.get() != null

    /**
     * 后台预读 ASTC 图集资产（幂等：已有缓存时跳过读取）。
     *
     * @return true = 预取成功；false = 资产缺失/IO 异常（回退 RGBA 路径，非错误）
     */
    // 资产缺失/IO 异常模式无稳定异常契约（assets.open 全捕获即"回退 RGBA"语义，
    // 与 NativeSurfaceView 原读取器一致）
    @Suppress("TooGenericExceptionCaught")
    fun prefetch(context: Context): Boolean {
        if (cached.get() != null) return true
        return try {
            val bytes = context.assets.open(ASTC_ATLAS_ASSET_PATH).use { it.readBytes() }
            cached.set(bytes)
            Log.i(TAG, "SectAtlasPrefetch: ${bytes.size} bytes prefetched")
            true
        } catch (t: Throwable) {
            Log.i(TAG, "SectAtlasPrefetch: unavailable (fallback to RGBA): ${t.message}")
            false
        }
    }

    /** 原子消费预取缓存（取用即清空）；null = 无缓存（调用方现场读取） */
    fun consume(): ByteArray? = cached.getAndSet(null)

    /** 丢弃未消费的预取缓存（内存压力 / 非 ASTC 分流） */
    fun clear() {
        cached.set(null)
    }
}
