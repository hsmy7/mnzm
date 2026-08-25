package com.xianxia.sect.core.nativebridge

/**
 * NativeEngineFlag — C++ 引擎转发层 feature flag（Kotlin→C++ 迁移批次 9）。
 *
 * 双实现并行契约（ADR Decision 7）：任何时刻可回退。
 * - 关闭（默认）：Kotlin 引擎照常运行（现状零变化）
 * - 开启：GameEngine 已迁移动作经 [GameCoreBridge.nativeExecute] 转发到
 *   C++ 计算，结果经 [StateSyncService] 镜像回 GameStateStore
 *
 * 生产默认关闭——转发层随批次推进逐动作灰度，避免一次性全量切换
 * 引入 UI 回归（文档登记：批次 9 剩余为超大工作量批次）。
 *
 * 测试可经 [withNativeEngine] 临时开启验证转发路径。
 */
object NativeEngineFlag {

    /** 是否开启 C++ 引擎转发（生产默认关闭；灰度时改为 true） */
    @Volatile
    var enabled: Boolean = false

    /**
     * 在 [block] 执行期间临时设置 flag（对拍/转发测试用，自动恢复）。
     */
    inline fun <T> withNativeEngine(enabled: Boolean = true, block: () -> T): T {
        val previous = this.enabled
        this.enabled = enabled
        try {
            return block()
        } finally {
            this.enabled = previous
        }
    }
}
