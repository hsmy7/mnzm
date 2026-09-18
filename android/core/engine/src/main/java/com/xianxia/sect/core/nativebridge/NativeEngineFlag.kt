package com.xianxia.sect.core.nativebridge

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
     * 镜像通道传输编码灰度开关（重构方案 R2.2：JSON 镜像 → protobuf 视图契约）。
     *
     * - **true（换轨后生产默认）**：`nativeExportDirty` 产出 GameView protobuf
     *   信封，[StateSyncService] 走 protobuf 解码分支；
     * - false：旧 JSON 变更集文本路径（新旧共存一个版本周期的回滚臂，R2.2
     *   红线——UI 消费面本批不动，仅换传输编码）。
     *
     * 语义：仅影响镜像通道的**字节载荷编码**（全量镜像内容、版本号、基线消费、
     * 存档格式零变更）；由 native 初始化后经
     * [GameCoreBridge.nativeSetDirtyExportProtobuf] 推送 C++ 分发模式，解码侧
     * 据此选分支。protobuf↔JSON 逐值等价由 DiffDirtyEnvelopeEquivalenceTest 守卫。
     */
    @Volatile
    var mirrorProtobufTransport: Boolean = true

    /**
     * 镜像瘦身灰度开关（重构方案 R2.3 第二波：GameViewStore 投影态 + 全量重建退场）。
     *
     * - **true（本批生产默认）**：镜像消费面走第二波形态——
     *   ① [com.xianxia.sect.core.gameview.GameDataFieldPatch] 字段级应用 gameData
     *   变更（替代整份 GameData JSON 往返的每旬级全量重建）；
     *   ② [com.xianxia.sect.core.gameview.GameViewStore] 投影态承载已迁 UI 消费块
     *   （资源头部/配置回声/事件流），[com.xianxia.sect.core.engine.GameEngine]
     *   对应转发面以投影为入口；
     *   ③ 弟子行走 typed 直读（`DiscipleRow → Disciple`，去每行 JSON 树重建）。
     * - **false（回滚臂，共存一个版本周期）**：第一波形态——整份 GameData JSON
     *   往返 + DiscipleRow→JsonObject→kotlinx 重建 + UI 只读 GameStateStore 全量流。
     *
     * 语义边界：仅改**消费侧应用形状**，不改协议、不改镜像内容、不改存档；
     * 两臂逐值等价由 `GameDataFieldPatchEquivalenceTest` /
     * `DiscipleRowTypedProjectionTest` / `GameViewProjectionFeedEquivalenceTest` 守卫。
     */
    @Volatile
    var gameViewProjection: Boolean = true

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
