# 测试规范（Testing Rules）

> 2026-08 建立（源自 docs/architecture.md 待办 D-27）。本文档固化测试 mock 与 stub 的统一约定，
> 是 AGENTS.md 第 9 节测试规范在"依赖注入与 mock 风格"维度的补充。

---

## 1. final 具体类禁止裸 mock（🔴）

**规则**：测试中不得对 final 具体类直接 `Mockito.mock(X::class.java)` / `mock(X::class.java)`。

**原因**（2026-08-10 测试 mock 模式根治批次实测教训）：ByteBuddy 对 final 类做 mock 拦截依赖类加载时机，
在 Robolectric 沙箱下顺序敏感 flaky——stub 注册后的第一次调用可能**真实执行方法体**
（先例：`ProductionSlotRepository.getSlots()` 内 `_slots.value` 抛 ClassCastException，
同一代码上一轮全绿、下一轮全红，显式 stub 也救不了）。

**治理方向（二选一，按可行性排序）**：

1. **真实实例 + 接口端口**（首选）——构造依赖若是接口，用真实实例 + `mockSmart` 端口，
   参照 `com.xianxia.sect.core.engine.testProductionSlotRepository()` 工厂模式
2. **共享工厂 + 智能空值**——构造依赖若为 Room 等不可轻量构造的具体类，用共享测试工厂
   返回 `Mockito.mock(X::class.java, RETURNS_SMART_NULLS)`，参照
   `com.xianxia.sect.core.state.testGameStateRepository()`（app 测试）

**已建立的共享工厂**：

| 工厂 | 位置 | 适用 |
|------|------|------|
| `testProductionSlotRepository()` | `core/engine/src/test/.../TestMockSupport.kt` | engine 模块测试 |
| `testGameStateRepository()` | `app/src/test/.../core/state/TestStateStoreSupport.kt` | app 模块 GameStateStoreImpl 测试 |

## 2. mockSmart 统一入口（🔴）

**规则**：除上述共享工厂外，其余需要 mock 的类型一律 `mockSmart(X::class.java)`
（`RETURNS_SMART_NULLS`），禁止裸 `Mockito.mock`。集合返回类型返回空集合；未 stub 的
对象方法调用抛 `SmartNullPointerException`（带调用堆栈）——静默 null 变显式失败。

`FakeAtomicStateStore` 等 Fake 能用的场景优先 Fake 而非 mock。

## 3. sealed 类型 stub 必须 doReturn 风格（🔴）

**规则**：返回 sealed 类型（如 `DomainResult` / `DeductResult`）的方法 stub 一律
`Mockito.doReturn(x).when(mock).sealedMethod()` 风格。

**原因**：`when(mock.sealedMethod())` 的第一次调用会触发 smart nulls 创建——ByteBuddy
无法为 sealed interface 生成代理（MockitoException "Unsupported settings"）。doReturn
风格 stub 先注册直接返回，不触发默认 answer。

## 4. 新 Service 测试的 Repository 依赖

**规则**：新 Service 测试若需 `ProductionSlotRepository`，必须走共享工厂
`com.xianxia.sect.core.engine.testProductionSlotRepository()`（真实实例 + mockSmart 端口 +
`loadSlots` 预填充），禁止自行 `ProductionSlotRepository(dao = mock(), ...)` 内联构造。

## 5. 编译器选项约定（提醒）

- Robolectric 测试保持 `@RunWith(RobolectricTestRunner::class)` + `@Config(sdk = [34])`
  （Robolectric 4.13 卡死，SDK 35 需 4.14+，见 docs/build-perf/robolectric-4.16-evaluation.md）
- Compose 测试规则迁移 `androidx.compose.ui.test.junit4.v2`（见 docs/architecture.md D-41 实施记录）
