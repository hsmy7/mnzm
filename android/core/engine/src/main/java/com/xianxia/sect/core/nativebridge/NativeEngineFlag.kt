package com.xianxia.sect.core.nativebridge

import com.xianxia.sect.core.engine.BuildConfig

/**
 * NativeEngineFlag — C++ 引擎集成 feature flag（双态：OFF / AUTHORITATIVE）。
 *
 * 双态语义：
 * - [Mode.OFF]：转发禁用——已接线动作（库存家族等）回退 Kotlin 原实现；
 *   引擎循环帧计划与看门狗判据走 Kotlin 侧。tick 结算
 *   恒走 native（单引擎终态无 Kotlin 路径），OFF 不影响 tick
 *  （仅逐动作/循环集成降级）
 * - [Mode.AUTHORITATIVE]（**生产默认**）：
 *   每旬时间推进 + C++ 核心结算（步骤 1-5 零 RNG 批量）走标量通道
 *   nativeSettlePhase；自动装备/丹药/突破/月变/年变由 Kotlin 残留执行器
 *   处理（行为零丢失）；Kotlin RNG 抽取经分区标量通道委托 C++ 单一真相源
 *   （跨语言序列逐位统一）
 *
 * 逐动作降级契约：native 链路不可用（.so 加载失败/初始化失败）时各转发
 * 分支自动回退 Kotlin 原实现（见 GameEngineCoreAuthoritativeOps /
 * InventoryNativeForward 降级契约）；tick 结算层 native 不可用则该旬跳过
 * 结算，由看门狗判据 → 紧急重启路径自愈。
 *
 * 测试可经 [withMode] 临时设置（自动恢复）。
 *
 * 与引擎集成模式正交的独立开关：[memorySubsystem]（内存子系统总开关，
 * memory-refactor 方案，不随 [Mode] 联动）。
 */
object NativeEngineFlag {

    /** 引擎集成模式 */
    enum class Mode {
        /** 转发禁用（已接线动作回退 Kotlin 原实现；非引擎级回退——tick 无 Kotlin 路径） */
        OFF,
        /**
         * 真相源切换（C++ 时间推进+核心结算为真相源，Kotlin 残留执行器 +
         * 委托式 RNG；**生产默认**）
         */
        AUTHORITATIVE,
    }

    @Volatile
    var mode: Mode = Mode.AUTHORITATIVE

    /** 是否开启 C++ 引擎转发/集成（非 OFF 即开启） */
    val enabled: Boolean get() = mode != Mode.OFF

    /** 是否 AUTHORITATIVE 过渡模式（tick 路径分支依据） */
    val authoritative: Boolean get() = mode == Mode.AUTHORITATIVE

    /**
     * 内存子系统总开关（memory-refactor 根治方案全局约束 8，D1–D7 新路径唯一分支依据）。
     *
     * - **false（当前默认，预发期）**：内存方案 P2–P4 新路径全部 no-op——GPU
     *   分配仍走既有站点、纹理仍走直传、trim 仍走既有消费者；P1.1–P1.5
     *   止血修复**不受本开关控制**（无双轨语义）。
     * - true（根治验收后翻默认）：GpuAllocator + VMA 单一分配入口、
     *   TextureCache 键控缓存、统一 trim 协议、基线去双 DOM 等新路径启用。
     *
     * 默认值经 `core/engine/build.gradle` 的 `MEMORY_SUBSYSTEM_DEFAULT`
     * （api.properties 可本地覆盖，**入库缺省 false**）编译期注入；本字段即
     * 运行时读取入口（@Volatile，P2–P4 代码路径以此为唯一分支依据）。
     * RemoteConfig 绑定前开关为编译期/本地——线上无法热关断（债表已登记，
     * 事故预案 = 发紧急版本切默认 false）。翻 true 的前置 = 根治验收
     * （实施方案附录 A 真机清单 + P4.5 门禁全绿），翻默认须同步改
     * [NativeEngineFlagMemorySubsystemTest] 守卫断言。
     */
    @Volatile
    var memorySubsystem: Boolean = BuildConfig.MEMORY_SUBSYSTEM_DEFAULT


    /**
     * 在 [block] 执行期间临时设置模式（对拍/转发测试用，自动恢复）。
     */
    inline fun <T> withMode(mode: Mode, block: () -> T): T {
        val previous = this.mode
        this.mode = mode
        try {
            return block()
        } finally {
            this.mode = previous
        }
    }
}
