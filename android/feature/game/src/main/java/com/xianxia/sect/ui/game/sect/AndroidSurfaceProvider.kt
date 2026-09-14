package com.xianxia.sect.ui.game.sect

import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.SurfaceHolder
import com.xianxia.sect.core.platform.SurfaceEventListener
import com.xianxia.sect.core.platform.SurfaceProvider

/**
 * Android 平台表面提供者 — 封装 [SurfaceHolder.Callback]，把 Android surface
 * 事件翻译为 [SurfaceProvider] 契约事件，并承载 surface 生命周期防御。
 *
 * ## 职责
 * 1. **事件翻译**（状态机驱动）：
 *    - surfaceCreated + 首次 surfaceChanged → [SurfaceEventListener.onSurfaceAvailable]（含初始尺寸）
 *    - 后续 surfaceChanged → [SurfaceEventListener.onSurfaceSizeChanged]
 *    - surfaceDestroyed → [SurfaceEventListener.onSurfaceDestroyed]
 * 2. **生命周期防御**：
 *    - **纪元防 stale**：创建/销毁递增 [generation]，宿主异步回调据此丢弃
 *      跨 surface 纪元的残留回调；销毁后到达的旧 surfaceChanged 事件直接拒绝
 *    - **GPU 初始化前零 lockCanvas**：surfaceCreated 不执行清屏（buffer 未就绪时
 *      lockCanvas 失败会经 AOSP Surface::lock 失败路径泄漏 CPU API 连接，导致后续
 *      Vulkan/GLES 初始化被 "already connected to another API" 拒绝）；
 *      黑屏兜底由宿主在软件路径（surface 可用事件/渲染线程启动）执行
 *    - **10s 初始化超时安全网**：[startInitTimeout] 计时，超时且纪元未变 →
 *      [SurfaceEventListener.onSurfaceInitTimeout]（宿主降级软件渲染）；
 *      销毁/重创建自动取消（stale 超时不触发）
 *
 * 平台回调由 Android 保证在主线程派发；[listener] 同步回调。
 *
 * @param holder 渲染宿主（SurfaceView）的 surface 宿主句柄；构造即注册平台回调
 * @param handler 计时用 Handler（默认主线程；测试可注入控制）
 */
class AndroidSurfaceProvider(
    private val holder: SurfaceHolder,
    private val handler: Handler = Handler(Looper.getMainLooper())
) : SurfaceProvider, SurfaceHolder.Callback {

    /** 内部状态机（主线程回调驱动；其他线程只读 @Volatile） */
    private enum class SurfaceState { DESTROYED, CREATED, ACTIVE }

    @Volatile
    private var state: SurfaceState = SurfaceState.DESTROYED

    @Volatile
    private var listener: SurfaceEventListener? = null

    @Volatile
    private var width = 0

    @Volatile
    private var height = 0

    /** 纪元计数（主线程递增，其他线程只读） */
    @Volatile
    private var genCounter: Int = 0

    /** 未触发的初始化超时任务（10s 安全网；主线程读写） */
    private var initTimeoutRunnable: Runnable? = null

    init {
        holder.addCallback(this)
    }

    override fun setEventListener(listener: SurfaceEventListener?) {
        this.listener = listener
    }

    override val isSurfaceValid: Boolean
        get() = state != SurfaceState.DESTROYED

    override val surfaceWidth: Int
        get() = width

    override val surfaceHeight: Int
        get() = height

    override val generation: Int
        get() = genCounter

    // ============================================================
    // SurfaceProvider — surface 操作
    // ============================================================

    /**
     * 清除表面为指定颜色（ARGB）。无效 surface 时无操作；
     * lockCanvas 失败内部吞掉（非关键路径，原 NativeSurfaceView 同语义）。
     */
    // 平台绘制 API 的失败模式无稳定异常契约（Surface 销毁竞态可抛任意运行时异常），
    // 全捕获 + 日志是既有非关键路径语义（原 NativeSurfaceView 同款）
    @Suppress("TooGenericExceptionCaught")
    override fun clearSurface(colorArgb: Int) {
        if (!isSurfaceValid) return
        try {
            val canvas = holder.lockCanvas()
            if (canvas != null) {
                canvas.drawColor(colorArgb)
                holder.unlockCanvasAndPost(canvas)
            }
        } catch (e: Exception) {
            Log.w(TAG, "clearSurface failed (non-fatal)", e)
        }
    }

    override fun startInitTimeout(timeoutMs: Long) {
        cancelInitTimeout()
        val currentGen = genCounter
        val runnable = Runnable {
            initTimeoutRunnable = null
            // 防御：surfaceDestroyed 后残留的 stale 超时回调不触发
            //（防跨 surface 误置状态——原 NativeSurfaceView timeoutRunnable 同守卫）
            if (currentGen != genCounter) return@Runnable
            Log.w(TAG, "Vulkan init timed out (${timeoutMs}ms) — falling back")
            listener?.onSurfaceInitTimeout()
        }
        initTimeoutRunnable = runnable
        handler.postDelayed(runnable, timeoutMs)
    }

    override fun notifyInitCompleted() {
        cancelInitTimeout()
    }

    override fun unregister() {
        // 解除平台回调注册（防止双 provider 接收同一事件）
        holder.removeCallback(this)
        cancelInitTimeout()
        listener = null
        state = SurfaceState.DESTROYED
        width = 0
        height = 0
    }

    // ============================================================
    // SurfaceHolder.Callback — 事件翻译 + 生命周期防御
    // ============================================================

    override fun surfaceCreated(holder: SurfaceHolder) {
        // 新 surface 纪元开始（尺寸未知，待首次 surfaceChanged 合并派发）
        state = SurfaceState.CREATED
        // 此处不得 lockCanvas 清屏。surfaceCreated 时
        //   SurfaceView buffer 尚未就绪，lockCanvas 内部
        //   AOSP Surface::lock 先 connect(NATIVE_WINDOW_API_CPU) 后 dequeueBuffer，
        //   dequeueBuffer 失败路径不 disconnect → CPU API 连接永久残留。随后 GPU
        //   初始化（vkCreateAndroidSurfaceKHR / eglCreateWindowSurface）对同一窗口
        //   native_window_api_connect 被 BufferQueueProducer 以 "already connected
        //   to another API" 拒绝 → Vulkan/GLES 双双失败 → 被迫 CPU 软件渲染。
        //   黑屏兜底职责移交宿主：软件路径在 handleSurfaceAvailable/startSoftwareBackend/
        //   RenderThread 启动时清屏（此时 buffer 已就绪）；GPU 路径由窗口纯黑背景 +
        //   首帧渲染器绘制 + 地图淡入遮蔽覆盖。
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, w: Int, h: Int) {
        when (state) {
            SurfaceState.CREATED -> {
                // 首次尺寸事件：创建 + 初始尺寸合并为 onSurfaceAvailable（契约事件），
                // 并递增纪元开启新 surface 纪元（宿主在回调内捕获 currentGen）
                width = w
                height = h
                state = SurfaceState.ACTIVE
                genCounter++
                listener?.onSurfaceAvailable(w, h)
            }
            SurfaceState.ACTIVE -> {
                width = w
                height = h
                listener?.onSurfaceSizeChanged(w, h)
            }
            SurfaceState.DESTROYED -> {
                // 防御：销毁后到达的旧 surface 事件被拒绝（无新 surfaceCreated
                //   的 stale surfaceChanged——主线程消息队列交错时可能发生）；
                //   尺寸必须在分支内更新——拒绝的 stale 事件不得污染宽高
                Log.w(TAG, "surfaceChanged ignored: stale event after destroy (${w}x$h)")
            }
        }
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        // 纪元递增：所有捕获旧纪元的宿主异步回调立即失效（防 stale）
        genCounter++
        state = SurfaceState.DESTROYED
        width = 0
        height = 0
        // 取消未触发的超时安全网（stale 超时不触发；纪元守卫双保险）
        cancelInitTimeout()
        listener?.onSurfaceDestroyed()
    }

    /** 取消超时计时（幂等） */
    private fun cancelInitTimeout() {
        initTimeoutRunnable?.let { handler.removeCallbacks(it) }
        initTimeoutRunnable = null
    }

    companion object {
        private const val TAG = "AndroidSurfaceProvider"

        // 初始化超时基础预算（10s）已收敛至 SurfaceProvider.INIT_TIMEOUT_BASE_MS
        // startInitTimeout 带预算参数，prewarm 在途时宿主传延长值）
    }
}
