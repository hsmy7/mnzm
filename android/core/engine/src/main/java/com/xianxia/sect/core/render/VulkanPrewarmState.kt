package com.xianxia.sect.core.render

/**
 * Vulkan prewarm 在途状态（prewarm 与 init 超时预算协同）。
 *
 * 背景：prewarm 的 JNI 调用不可取消——放弃等待后 native 侧仍持生命周期锁运行，
 * surface 期 initRenderer 阻塞在同一锁上。此时 10s 安全网到期会把「Vulkan 健康
 * 但慢（预热挤占）」误降级为 GLES 一整个会话。
 *
 * 预算语义：prewarm 在途时，初始化安全网总预算 = prewarm 起点 + [PREWARM_BUDGET_MS]
 * + 基础 10s——initRenderer 正阻塞等 prewarm 属健康慢而非卡死，不降级；
 * prewarm 真挂死则由延长后的预算到期降级 + 写前标记残留接住。
 *
 * 状态只写一次 started（prewarm 线程）/一次 finished——@Volatile 足够，
 * 无需锁。宿主（GameActivity）驱动，渲染宿主（NativeSurfaceView）只读。
 */
object VulkanPrewarmState {

    /** prewarm 独立预算（毫秒）：超过视为真挂死，不再为 initRenderer 延长安全网 */
    const val PREWARM_BUDGET_MS = 8_000L

    @Volatile private var startedAtMs: Long = 0
    @Volatile private var finished: Boolean = true

    /** prewarm 线程在调 JNI 前调用（进程内单次——GameActivity 单次守卫保证） */
    fun markStarted() {
        startedAtMs = System.currentTimeMillis()
        finished = false
    }

    /** prewarm 线程在 JNI 返回（无论成败）后调用 */
    fun markFinished() {
        finished = true
    }

    /** prewarm 是否仍在途（JNI 未返回） */
    fun inFlight(): Boolean = !finished

    /** prewarm 开始时刻（System.currentTimeMillis；未 started 过返回 0） */
    fun startedAt(): Long = startedAtMs
}
