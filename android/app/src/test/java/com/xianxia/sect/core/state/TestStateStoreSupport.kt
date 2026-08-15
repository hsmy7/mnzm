package com.xianxia.sect.core.state

import com.xianxia.sect.data.GameStateRepository
import org.mockito.Mockito

/**
 * 测试 mock 统一入口——替代裸 `Mockito.mock(GameStateRepository::class.java)`。
 *
 * ## 为什么禁止裸 mock final 具体类（docs/architecture.md 待办 D-26）
 *
 * [GameStateRepository] 是 final 具体类（core:data，非接口）。ByteBuddy 对 final 类做
 * mock 拦截依赖类加载时机，在 Robolectric 沙箱下顺序敏感 flaky：stub 注册后的第一次
 * 调用可能真实执行方法体（同款先例见 engine 模块 `TestMockSupport.testProductionSlotRepository()`
 * 的 KDoc——`getSlots()` 内 `_slots.value` 抛 ClassCastException，同一代码上一轮全绿、
 * 下一轮全红，显式 stub 也救不了）。
 *
 * 本项目 [GameStateStoreImpl] 对 repository 的调用面已收敛为 5 个纯状态方法
 * （`markDirty`/`setActiveSlot`/`markAllDirty`/`clearDirty`——真实执行也仅是
 * AtomicReference/volatile 操作，无 Room 依赖），因此统一入口只需满足两点：
 *
 * 1. **RETURNS_SMART_NULLS**——集合返回类型返回空集合；未 stub 的对象方法调用抛
 *    `SmartNullPointerException`（带调用堆栈），静默失败变显式失败
 * 2. **统一构造点**——未来若需要 stub（如 `setActiveSlot` 抛异常模拟读档失败），在
 *    调用点用 **doReturn/doThrow 风格**（`when(...)` 的第一次调用会触发 smart nulls
 *    创建，sealed/void 场景不可靠），禁止回退裸 mock
 *
 * ## 使用约定
 *
 * - 所有 `GameStateStoreImpl` 测试的 repository 依赖一律 `testGameStateRepository()`
 * - 需要行为 stub 时对返回的实例继续 `Mockito.doXxx().when(repo).method()`（风格不变）
 * - 新增对 core:data final 具体类的 mock 需求时，先评估"真实实例 + 接口端口"模式，
 *   再考虑本入口模式
 *
 * @return 智能空值 mock 实例（等价 `mock(RETURNS_SMART_NULLS)` 的共享构造点）
 */
fun testGameStateRepository(): GameStateRepository =
    Mockito.mock(GameStateRepository::class.java, Mockito.RETURNS_SMART_NULLS)
