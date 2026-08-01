package com.xianxia.sect.core.state

import org.junit.Assert.fail

/**
 * 轮询等待辅助（2026-08-01 时序测试抖动修复）。
 *
 * 固定 delay/Thread.sleep 在慢 CI 上可能不足 → flaky；本工具轮询目标状态而非
 * 固定时长，状态达成即返回，超时才 fail。
 *
 * 注意：阻塞式 Thread.sleep——仅适用于后台计算跑在独立单线程调度器
 * （如 GameStateStoreImpl 的 assembleDispatcher）的场景，阻塞测试主线程不影响其执行。
 * 参照 DerivedAggregationTest.awaitAggregation 的 20ms 轮询模式。
 */
object TestPolling {

    const val DEFAULT_TIMEOUT_MS = 5_000L
    const val POLL_INTERVAL_MS = 20L

    fun awaitCondition(
        description: String,
        stateSnapshot: () -> String = { "" },
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
        intervalMs: Long = POLL_INTERVAL_MS,
        condition: () -> Boolean
    ) {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000L
        while (System.nanoTime() < deadline) {
            if (condition()) return
            Thread.sleep(intervalMs)
        }
        val detail = stateSnapshot()
        fail(
            if (detail.isEmpty()) "等待超时(${timeoutMs}ms)：$description"
            else "等待超时(${timeoutMs}ms)：$description；当前实际：$detail"
        )
    }
}
