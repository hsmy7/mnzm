package com.xianxia.sect.core

/**
 * JUnit4 类别标记：Robolectric 沙箱测试（2026-08-14，阶段 2.5）。
 *
 * 用于 `:core:engine:testRobolectricRelease` / `testJvmRelease` 双任务拆分：
 * - 标注本接口的测试类跑沙箱（慢，62 类占 engine 测试时长 58%）
 * - 未标注的纯 JUnit 类跑 `testJvmRelease`（快，改 JUnit 类时快速回归）
 *
 * 串行约束不变：两个任务各自 maxParallelForks = 1，`--max-workers=1` 下永不并发。
 * 默认任务 `testReleaseUnitTest`（CI / kover / CLAUDE.md 语义）不受影响。
 *
 * 新增 Robolectric 测试类时必须在类上标注
 * `@org.junit.experimental.categories.Category(com.xianxia.sect.core.RobolectricTests::class)`，否则该类的回归
 * 会被 `testJvmRelease` 漏掉或归类错误。
 */
interface RobolectricTests
