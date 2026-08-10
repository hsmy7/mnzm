package com.xianxia.sect.core.engine

import com.xianxia.sect.core.config.BuildingConfigService
import com.xianxia.sect.core.repository.ProductionSlotDataPort
import com.xianxia.sect.core.repository.ProductionSlotRepository
import com.xianxia.sect.core.util.CoroutineScopeProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.mockito.Mockito

/**
 * 测试 mock 统一入口——替代裸 [Mockito.mock]。
 *
 * ## 为什么需要 mockSmart
 *
 * 裸 `mock()` 对未 stub 方法默认返回 null（引用类型）——服务重构新增依赖调用
 * （如 Repository 增加 `getSlots()`）时返回 null → 深处 NPE，堆栈不指向 mock
 * 调用点，静默失败难以定位（2026-08-10 DiscipleServiceCrudTest 教训）。
 *
 * `RETURNS_SMART_NULLS` 提供双层防护：
 * 1. **集合返回类型**（List/Set/Map 接口）返回空集合——不 NPE，服务正常遍历
 * 2. **未 stub 的对象属性/方法调用**抛 `SmartNullPointerException`（带调用堆栈，
 *    指出"哪次 mock 调用、从哪触发"）——静默失败变显式失败，可定位
 *
 * 对已显式 `when(...).thenReturn(...)` 的 stub 无任何影响（stub 优先）。
 *
 * ## 已知限制（2026-08-10 实测）
 *
 * **final 具体类（如 [ProductionSlotRepository]）在 Robolectric 下 mock 拦截依赖类加载时机，
 * 顺序敏感 flaky**——stub 注册的第一次调用可能真实执行方法体（实测 `getSlots()` 内
 * `_slots.value` 抛 ClassCastException，同一代码上一轮全绿、下一轮全红），
 * **显式 stub 也救不了**（stub 注册本身先触发真实执行）。**必须用真实实例**：
 * `testProductionSlotRepository()`（真实 repository + mockSmart 端口 + 无界 scope）。
 *
 * 接口属性（如 `repo.slots: StateFlow`）未 stub 访问时仍会显式 SmartNullPointerException——
 * 这是 mockSmart 相对裸 mock 的核心收益：**静默 null 变显式失败**。
 *
 * **返回 sealed/不可代理类型（如 `com.xianxia.sect.core.util.DomainResult`）的方法
 * 必须用 doReturn 风格 stub**：`when(mock.sealedMethod())` 的第一次调用会触发 smart nulls
 * 创建（ByteBuddy 无法为 sealed interface 生成代理 → MockitoException "Unsupported settings"），
 * 改用 `Mockito.doReturn(x).when(mock).sealedMethod()`——stub 已注册直接返回，不触发默认 answer。
 *
 * ## 使用约定
 *
 * - [FakeAtomicStateStore] 能用的场景（服务依赖 store）**不用 mock**，用 Fake
 * - Repository/Service 等辅助依赖需要 mock 时，一律 `mockSmart(X::class.java)`
 * - 服务会调用的具体类方法显式 stub；接口返回类型可靠 smart nulls 兜底
 *
 * @param type 要 mock 的类型（`mockSmart(ProductionSlotRepository::class.java)`）
 * @return 智能空值 mock 实例
 */
fun <T> mockSmart(type: Class<T>): T = Mockito.mock(type, Mockito.RETURNS_SMART_NULLS)

/**
 * reified 版本：`mockSmart<Foo>()` 按构造参数类型推断，替代 mockito-kotlin 的 `mock<Foo>()`。
 */
inline fun <reified T> mockSmart(): T = Mockito.mock(T::class.java, Mockito.RETURNS_SMART_NULLS)

/** 无界 CoroutineScopeProvider（真实 repository 的 stateIn scope 使用） */
private object UnconfinedScopeProvider : CoroutineScopeProvider {
    override val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    override val ioScope = scope
}

/**
 * 真实 [ProductionSlotRepository] 测试实例——final 具体类 mock 拦截不可靠（见类 KDoc 已知限制），
 * 用真实实例 + mockSmart 端口。`getSlots()` 真实返回空列表（`_slots` 初始 emptyList），
 * 需要预填充时在 runTest 内 `repo.loadSlots(listOf(...))`。
 */
fun testProductionSlotRepository(): ProductionSlotRepository = ProductionSlotRepository(
    dao = mockSmart(ProductionSlotDataPort::class.java),
    configService = mockSmart(BuildingConfigService::class.java),
    scopeProvider = UnconfinedScopeProvider
)
