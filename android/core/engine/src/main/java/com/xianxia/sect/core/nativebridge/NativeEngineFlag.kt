package com.xianxia.sect.core.nativebridge

/**
 * NativeEngineFlag — C++ 引擎集成 feature flag（批次 9 建立，T2.4 升级三态，
 * 计划 v2 阶段 7 切换生产默认）。
 *
 * 三态语义（docs/cpp-engine.md 计划 v2 阶段 2d / 阶段 7）：
 * - [Mode.OFF]：纯 Kotlin 路径（阶段 1-6 灰度期生产默认；阶段 7 起降级为
 *   **回退契约**——运行时切回即回到纯 Kotlin 行为）
 * - [Mode.SHADOW]：shadow 对拍——C++ 影子状态随 tick 推进（tickNativeShadow），
 *   不镜像覆盖 Kotlin；GameEngine 已迁移动作经 [GameCoreBridge.nativeExecute]
 *   转发，结果经 [StateSyncService] 镜像
 * - [Mode.AUTHORITATIVE]（**生产默认**，计划 v2 阶段 7 真相源切换验收）：
 *   每旬时间推进 + C++ 核心结算（步骤 1-5 零 RNG 批量）走标量通道
 *   nativeSettlePhase；自动装备/丹药/突破/月变/年变由 Kotlin 残留执行器
 *   处理（行为零丢失）；Kotlin RNG 抽取经分区标量通道委托 C++ 单一真相源
 *   （跨语言序列逐位统一）
 *
 * 回退契约：任一时刻切回 [Mode.OFF] 即回到纯 Kotlin 路径（Kotlin 引擎代码
 * 全保留，直至 C-06 转发收尾完成后随阶段 7 续作退役）；native 链路不可用
 * （.so 加载失败/初始化失败）时 AUTHORITATIVE 各分支自动降级纯 Kotlin，
 * 与 [Mode.OFF] 行为一致（见 GameEngineCoreAuthoritativeOps 降级契约）。
 *
 * 测试可经 [withMode] 临时设置（自动恢复）。
 */
object NativeEngineFlag {

    /** 引擎集成模式 */
    enum class Mode {
        /** 纯 Kotlin（阶段 7 起为回退契约，运行时可切） */
        OFF,
        /** shadow 对拍（双实现并行，Kotlin 为真相源） */
        SHADOW,
        /**
         * 真相源切换（C++ 时间推进+核心结算为真相源，Kotlin 残留执行器 +
         * 委托式 RNG；**生产默认**——计划 v2 阶段 7 切换）
         */
        AUTHORITATIVE,
    }

    @Volatile
    var mode: Mode = Mode.AUTHORITATIVE

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
