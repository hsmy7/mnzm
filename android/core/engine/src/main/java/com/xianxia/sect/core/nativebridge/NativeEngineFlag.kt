package com.xianxia.sect.core.nativebridge

/**
 * NativeEngineFlag — C++ 引擎集成 feature flag（批次 9 建立，T2.4 升级三态）。
 *
 * 三态语义（docs/cpp-engine.md 计划 v2 阶段 2d）：
 * - [Mode.OFF]（生产默认）：Kotlin 引擎照常运行（现状零变化）
 * - [Mode.SHADOW]：shadow 对拍——C++ 影子状态随 tick 推进（tickNativeShadow），
 *   不镜像覆盖 Kotlin；GameEngine 已迁移动作经 [GameCoreBridge.nativeExecute]
 *   转发，结果经 [StateSyncService] 镜像
 * - [Mode.AUTHORITATIVE]：过渡期真相源切换——每旬时间推进 + C++ 核心结算
 *   （步骤 1-5 零 RNG 批量）走标量通道 nativeSettlePhase；自动装备/丹药/突破/
 *   月变/年变由 Kotlin 残留执行器处理（行为零丢失）；Kotlin RNG 抽取经分区
 *   标量通道委托 C++ 单一真相源（跨语言序列逐位统一）
 *
 * 回退契约：任一模式切回 [Mode.OFF] 即回到纯 Kotlin 路径（代码全保留直至
 * 阶段 7 退役）；AUTHORITATIVE 灰度期 SHADOW 对拍可并行守护。
 *
 * 测试可经 [withMode] 临时设置（自动恢复）。
 */
object NativeEngineFlag {

    /** 引擎集成模式 */
    enum class Mode {
        /** 纯 Kotlin（生产默认） */
        OFF,
        /** shadow 对拍（双实现并行，Kotlin 为真相源） */
        SHADOW,
        /**
         * 过渡期真相源切换（C++ 时间推进+核心结算为真相源，
         * Kotlin 残留执行器 + 委托式 RNG）
         */
        AUTHORITATIVE,
    }

    @Volatile
    var mode: Mode = Mode.OFF

    /** 是否开启 C++ 引擎转发/集成（兼容批次 9 布尔语义：非 OFF 即开启） */
    val enabled: Boolean get() = mode != Mode.OFF

    /** 是否 AUTHORITATIVE 过渡模式（tick 路径分支依据） */
    val authoritative: Boolean get() = mode == Mode.AUTHORITATIVE

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
