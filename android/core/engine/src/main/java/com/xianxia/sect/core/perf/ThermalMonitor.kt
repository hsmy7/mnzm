package com.xianxia.sect.core.perf

import com.xianxia.sect.core.util.DomainLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton


/**
 * 热状态枚举 — 对应 Android PowerManager 热状态级别
 */
enum class ThermalState {
    NORMAL, LIGHT, MODERATE, SEVERE, EMERGENCY
}

/**
 * ADPF Thermal API 集成 — 监控设备热状态，在过热时降低负载
 * 行业依据: https://developer.android.com/games/optimize/adpf
 *
 * 平台能力接口化（计划 v2 批 8-1）：Android API（PowerManager/PerformanceHintManager）
 * 经 [ThermalStatusReader]/[PerformanceHintPort] 端口注入，实现移 app 层；
 * 全部轮询、映射与会话线程绑定守卫逻辑保留在本类（引擎侧，零 Android 依赖）。
 */
@Singleton
class ThermalMonitor @Inject constructor(
    private val thermalStatusReader: ThermalStatusReader,
    private val hintPort: PerformanceHintPort
) : ThermalStatusProvider {

    // D-09 internal 测试接缝（2026-08-08）：端口注入 fake 即可控异常/null 场景
    //（hintManager 接缝随端口化消失——测试注入 PerformanceHintPort fake）。
    // Session 为不透明句柄（Any），API 类型仅 app 实现层知晓。
    @Volatile
    internal var hintSession: Any? = null

    /** 创建 hintSession 的线程。Session 非线程安全，close/report 必须发生在属主线程。 */
    @Volatile
    internal var sessionOwnerThread: Thread? = null

    /**
     * create/close/report 共用锁。
     * 守卫本质是"读-检查-用"非原子序列——若不加锁，旧线程 T1 在守卫通过后、读字段前
     * 被抢占（抢占窗口无上限），新循环 T2 在此期间 create 覆盖字段，T1 恢复后仍会
     * 跨线程 close 新 session（Bugly #3114 同类 abort）。锁使检查与使用原子。
     * 三个方法均为低频调用（循环生命周期一次或每 tick 一次，无竞争锁开销可忽略）。
     */
    private val sessionLock = Any()

    private var monitorJob: Job? = null

    private val _thermalState = MutableStateFlow(ThermalState.NORMAL)
    /** 当前热状态，以 StateFlow 形式暴露，供 UI 层收集 */
    val thermalState: StateFlow<ThermalState> = _thermalState.asStateFlow()

    /**
     * 启动热状态监控。绑定到引擎作用域，引擎关闭时自动取消。
     * 由 GameEngineCore.startGameLoop() 调用。
     *
     * D-08 重建语义（2026-08-08）：emergencyRestartGameLoop 重建 engineScope 后
     * 再次调用 start——旧 job 若仍 active（旧 scope 子树未被 cancel，仅失去引用）
     * 而直接 return，热监控将永久绑定旧 scope（轮询残留 + 新 scope 无监控）。
     * 无条件重建：先取消旧 job 再绑定新 scope，幂等安全。
     */
    fun start(engineScope: CoroutineScope) {
        monitorJob?.cancel()
        monitorJob = engineScope.launch {
            // 立即读取一次初始状态
            _thermalState.value = mapStatusToState(currentThermalStatus)
            // 每 2 秒轮询一次热状态，避免高频查询
            while (isActive) {
                _thermalState.value = mapStatusToState(currentThermalStatus)
                delay(2000L)
            }
        }
    }

    /**
     * 停止热状态监控。由 GameEngineCore.shutdown() 调用。
     */
    fun stop() {
        monitorJob?.cancel()
        monitorJob = null
    }

    /** 当前热状态等级（0=NONE … 4=EMERGENCY，PowerManager 语义），由平台端口读取 */
    val currentThermalStatus: Int
        get() = thermalStatusReader.currentThermalStatus

    /** 是否应降低非关键计算负载 (MODERATE 及以上)。
     *  使用 _thermalState 缓存（每 2s 轮询更新），避免热路径上每 tick 触发 binder 调用。 */
    override fun shouldReduceWorkload(): Boolean =
        _thermalState.value >= ThermalState.MODERATE

    /** 是否应紧急保存并暂停 (SEVERE 及以上)。
     *  使用 _thermalState 缓存，理由同 [shouldReduceWorkload]。 */
    override fun shouldEmergencySave(): Boolean =
        _thermalState.value >= ThermalState.SEVERE

    /** 是否处于轻度过热状态 (LIGHT)。
     *  使用 _thermalState 缓存，理由同 [shouldReduceWorkload]。 */
    fun isLightThrottle(): Boolean =
        _thermalState.value == ThermalState.LIGHT

    // PowerManager.THERMAL_STATUS_* 常量值（0-4）；字面量随端口化固化，
    // 与 app 层 AndroidThermalStatusReader 的 PowerManager 语义对齐
    private fun mapStatusToState(status: Int): ThermalState = when {
        status >= THERMAL_STATUS_EMERGENCY -> ThermalState.EMERGENCY
        status >= THERMAL_STATUS_SEVERE -> ThermalState.SEVERE
        status >= THERMAL_STATUS_MODERATE -> ThermalState.MODERATE
        status >= THERMAL_STATUS_LIGHT -> ThermalState.LIGHT
        else -> ThermalState.NORMAL
    }

    /**
     * 创建 Performance Hint Session。
     * 在游戏引擎启动时调用。记录属主线程，供 [closeHintSession]/[reportActualWorkDuration] 线程守卫使用。
     * synchronized([sessionLock])：与 close 互斥，杜绝"close 守卫通过后被本方法覆盖字段"的交错。
     */
    fun createHintSession(targetDurationNanos: Long) {
        if (!hintPort.isSupported) return
        synchronized(sessionLock) {
            try {
                hintSession = hintPort.acquire(
                    intArrayOf(hintPort.currentThreadId()),
                    targetDurationNanos
                )
                sessionOwnerThread = Thread.currentThread()
            } catch (e: Exception) {
                DomainLog.w(TAG, "createHintSession failed", e)
                hintSession = null
                sessionOwnerThread = null
            }
        }
    }

    /**
     * 报告当前帧的工作持续时间。
     * 在每个 tick 完成后调用。仅属主线程上报，防御跨线程访问。
     * synchronized([sessionLock])：与 create/close 互斥，防止守卫与读取之间被覆盖。
     */
    fun reportActualWorkDuration(durationNanos: Long) {
        if (!hintPort.isSupported) return
        synchronized(sessionLock) {
            if (Thread.currentThread() !== sessionOwnerThread) return
            try {
                hintSession?.let { hintPort.reportWorkDuration(it, durationNanos) }
            } catch (e: Exception) {
                DomainLog.w(TAG, "reportActualWorkDuration failed", e)
            }
        }
    }

    /**
     * 动态更新 ADPF 目标帧时长（2026-08-14 平板省电：实际帧率 30/10fps 时向系统
     * 声明真实预算，替代硬编码 60fps 目标——系统按需调度大核，不再为 60fps 保留性能）。
     *
     * 由 GameEngineCore 收集 renderFrameRate StateFlow 联动（场景/模式/热控/电量
     * 任何一次重算自动生效）。session 未创建时 no-op（仅记录日志）。
     * synchronized([sessionLock])：与 create/close 互斥；updateTargetWorkDuration
     * 为 Session 线程安全 API（官方文档标注），与 owner 线程无关。
     */
    fun setTargetWorkDuration(targetDurationNanos: Long) {
        if (!hintPort.isSupported) return
        synchronized(sessionLock) {
            try {
                hintSession?.let { hintPort.updateTargetDuration(it, targetDurationNanos) }
            } catch (e: Exception) {
                DomainLog.w(TAG, "setTargetWorkDuration failed", e)
            }
        }
    }

    /**
     * 关闭 Hint Session。
     * 在游戏引擎关闭时调用。
     *
     * 线程绑定守卫：Session 非线程安全，跨线程 close 会触发 nativeCloseSession SIGABRT
     * （Bugly #3114：看门狗 emergencyRestartGameLoop 换线程重启游戏循环后，旧线程的
     * finally 曾关闭新循环刚创建的 Session → 原生 abort，try/catch 拦不住）。
     * synchronized([sessionLock])：守卫的"读 owner → 读 hintSession → close → 条件复位"
     * 全程原子，T2 的 create 只能在 close 之前或之后完成，不可能插入守卫与读取之间。
     * 泄漏语义：换线程重启后旧循环的 session 因线程守卫跳过 close 而泄漏（无论旧线程
     * 是否被 OEM 挂起——守卫拒绝跨线程 close 时旧 session 即永久失去释放路径）。
     * 每次换线程重启泄漏 1 个，看门狗 60s 限频下有界；进程级 binder 资源，进程死亡时
     * 由系统回收，无 double-free 风险。
     */
    fun closeHintSession() {
        if (!hintPort.isSupported) return
        synchronized(sessionLock) {
            if (Thread.currentThread() !== sessionOwnerThread) {
                DomainLog.w(
                    TAG, "closeHintSession skipped: cross-thread close attempt " +
                        "(caller=${Thread.currentThread().name}, owner=$sessionOwnerThread)"
                )
                return
            }
            val closingSession = hintSession
            try {
                closingSession?.let { hintPort.release(it) }
            } catch (e: Exception) {
                DomainLog.w(TAG, "closeHintSession failed", e)
            }
            // 条件复位：仅当字段仍指向本次关闭的 session 才置空，
            // 防止并发 create（新循环覆盖字段）后把新 session 从字段中抹掉
            if (hintSession === closingSession) {
                hintSession = null
                sessionOwnerThread = null
            }
        }
    }

    private companion object {
        const val TAG = "ThermalMonitor"

        // PowerManager.THERMAL_STATUS_* 数值（PowerManager 语义，随端口化固化）
        const val THERMAL_STATUS_LIGHT = 1
        const val THERMAL_STATUS_MODERATE = 2
        const val THERMAL_STATUS_SEVERE = 3
        const val THERMAL_STATUS_EMERGENCY = 4
    }
}
