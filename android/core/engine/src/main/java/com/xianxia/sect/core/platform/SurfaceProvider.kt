package com.xianxia.sect.core.platform

/**
 * 渲染表面提供者 — 渲染表面生命周期与平台解耦（对标 Godot DisplayServer / platform/ 目录）。
 *
 * 渲染宿主（如 NativeSurfaceView）**只面向本接口**消费 surface 事件，
 * 不直接依赖平台 surface 类型（Android SurfaceHolder / iOS CAMetalLayer）。
 * 平台实现（AndroidSurfaceProvider）负责把平台回调翻译为 [SurfaceEventListener]
 * 契约事件，并承载 surface 生命周期防御（纪元防 stale、首帧清除、初始化超时安全网）。
 *
 * ## 设计原则
 * - **零 Android 依赖**：接口与事件契约均为纯 Kotlin 类型（width/height 等）
 * - **事件驱动**：宿主经 [setEventListener] 注册监听器，平台回调翻译后同步派发
 * - **查询式能力**（DisplayServer 风格）：[isSurfaceValid]/[surfaceWidth]/[surfaceHeight]
 *   供宿主在提交帧/初始化前查询
 * - **异步回调防 stale**：宿主发起的异步操作（如初始化线程 post 回主线程）捕获
 *   [generation]，执行前比对——不一致即跨 surface 纪元的 stale 回调，必须丢弃
 * - **初始化超时安全网**：[startInitTimeout] 启动 10s 计时，宿主初始化完成调
 *   [notifyInitCompleted] 取消；超时且 surface 未变 → 回调
 *   [SurfaceEventListener.onSurfaceInitTimeout]（宿主降级渲染路径）
 *
 * ## 事件序列契约（由实现保证）
 * - 首次创建：`onSurfaceAvailable(width, height)`（创建 + 初始尺寸合并为单事件）
 * - 尺寸变化：`onSurfaceSizeChanged(width, height)`（仅可用后非首次变化）
 * - 销毁：`onSurfaceDestroyed()`（之后不再派发任何事件，直至下一次可用）
 * - 重创建：销毁后再次可用 = 全新纪元（重复上述序列；宿主的异步回调必须经
 *   [generation] 防 stale）
 *
 * @see SurfaceEventListener 事件契约详情
 */
interface SurfaceProvider {

    /**
     * 注册表面事件监听器（渲染宿主实现）。
     *
     * 重复调用覆盖旧监听器；传 null 解除注册（实现切换时先解除旧监听器，
     * 旧实现不再派发事件——平台回调注册由实现自身管理）。
     *
     * @param listener 事件监听器；null = 解除注册
     */
    fun setEventListener(listener: SurfaceEventListener?)

    /**
     * 表面当前是否有效（创建后至销毁前为 true）。
     *
     * 无效时宿主不得初始化渲染器；渲染线程在提交帧前可查询
     * （Android 上对应 SurfaceHolder 无有效 surface 的时段）。
     */
    val isSurfaceValid: Boolean

    /** 表面当前宽度（像素；无效时为 0） */
    val surfaceWidth: Int

    /** 表面当前高度（像素；无效时为 0） */
    val surfaceHeight: Int

    /**
     * 表面纪元计数器 — 每次表面创建/销毁递增。
     *
     * 宿主异步回调（post 到宿主线程的初始化完成/降级回调等）在发起时捕获
     * 当前值，执行前与最新值比对；不一致 = 跨 surface 纪元的 stale 回调，
     * 必须丢弃（防旧 surface 的残留回调误置新 surface 状态）。
     */
    val generation: Int

    /**
     * 清除表面为指定颜色（ARGB 像素格式：0xAARRGGBB）。
     *
     * 平台语义：surface 无效时无操作；实现内部捕获异常（非关键路径，
     * 失败不影响后续渲染）。用于首帧防合成器显示未初始化/残留缓冲
     * （Android SurfaceFlinger 合成透明/脏缓冲、模拟器残留内容）。
     *
     * @param colorArgb 清除颜色（ARGB）
     */
    fun clearSurface(colorArgb: Int)

    /**
     * 启动初始化超时安全网：10 秒内未调用 [notifyInitCompleted] 且 surface
     * 未销毁/未重创建 → 回调 [SurfaceEventListener.onSurfaceInitTimeout]。
     *
     * 幂等：重复调用重置计时；surface 销毁/重创建自动取消（stale 超时不触发）。
     * 宿主在发起可能卡死的初始化（如 Vulkan 设备初始化）前调用。
     */
    fun startInitTimeout()

    /**
     * 声明初始化完成（取消超时安全网）。
     *
     * 幂等：超时已触发或未启动计时时无操作。宿主初始化成功/失败回调均需调用。
     */
    fun notifyInitCompleted()

    /**
     * 解除平台回调注册（换绑 provider 时调用——对抗性审查 2026-08-13
     * 状态破坏者#6：旧实例残留 addCallback 注册，同事件被双 provider 接收）。
     *
     * 幂等：重复调用安全；调用后本实例不再派发事件、超时不再触发。
     */
    fun unregister()
}

/**
 * 渲染表面事件监听器 — 由渲染宿主实现，接收 [SurfaceProvider] 翻译后的生命周期事件。
 *
 * ## 事件序列契约
 * - 首次创建：`onSurfaceAvailable(width, height)`（创建 + 初始尺寸合并为单事件，
 *   宿主在此初始化渲染器——等价于 Android surfaceCreated + 首次 surfaceChanged）
 * - 尺寸变化：`onSurfaceSizeChanged(width, height)`（仅可用后非首次变化，
 *   宿主在此 resize 渲染后端）
 * - 销毁：`onSurfaceDestroyed()`（宿主停止渲染线程并释放后端；之后不再派发
 *   任何事件，直至下一次 [onSurfaceAvailable]）
 * - 重创建：销毁后再次可用 = 全新纪元（重复上述序列；宿主的异步回调须经
 *   [SurfaceProvider.generation] 防 stale——旧纪元回调一律丢弃）
 * - 初始化超时：`onSurfaceInitTimeout()`（宿主降级软件渲染路径；仅在
 *   [SurfaceProvider.startInitTimeout] 后 10 秒未完成且 surface 未变时触发）
 *
 * 所有回调在 surface 宿主线程（Android 主线程）同步派发，实现内不得阻塞。
 */
interface SurfaceEventListener {

    /**
     * 表面可用（含初始尺寸）——宿主初始化渲染器。
     *
     * @param width 表面宽度（像素）
     * @param height 表面高度（像素）
     */
    fun onSurfaceAvailable(width: Int, height: Int)

    /**
     * 表面尺寸变化（非首次）——宿主 resize 渲染后端。
     *
     * @param width 新宽度（像素）
     * @param height 新高度（像素）
     */
    fun onSurfaceSizeChanged(width: Int, height: Int)

    /** 表面销毁——宿主停止渲染线程、释放渲染后端并清空 surface 关联资源。 */
    fun onSurfaceDestroyed()

    /** 初始化超时（10s 未完成）——宿主降级软件渲染路径。 */
    fun onSurfaceInitTimeout()
}
