# engine 双任务拆分（testJvmRelease / testRobolectricRelease）（2026-08-14）

> 阶段 2.5 产物。core:engine 模块 Robolectric 沙箱测试与纯 JUnit 测试拆分，
> 收益是 **up-to-date 隔离**（改纯 JUnit 类不触发沙箱测试重跑），**不增加任何并行度**。

## 背景与门控

- 阶段 0 基线：engine 191 类，Robolectric 特征类 64 个（类数 34%），
  **按测试时长占比 58%**（34.8s / 60s）——超过 50% 门控阈值，实施拆分
- 串行约束不变：两个任务各自 `maxParallelForks = 1`，`--max-workers=1` 下永不并发

## 用法

```bash
# 改纯 JUnit 类（engine 129 类）→ 快速回归，实测 18s 左右
cd android && ./gradlew.bat :core:engine:testJvmRelease --max-workers=1

# 改 Robolectric 沙箱测试（engine 64 类）→ 实测 25s 左右
cd android && ./gradlew.bat :core:engine:testRobolectricRelease --max-workers=1

# 全量（默认任务，CI / kover / 提交前回归）——语义零变化
cd android && ./gradlew.bat testReleaseUnitTest --max-workers=1
```

`testJvmRelease` / `testRobolectricRelease` 的 testClassesDirs / classpath 惰性取自
`testReleaseUnitTest`（AGP 延迟注册任务，配置期 `tasks.named()` 会报 not found，
用闭包在输入固化时解析）。

## 机制与标注规则

- 标记接口：`core/engine/src/test/java/com/xianxia/sect/core/RobolectricTests.kt`
- **新增 Robolectric 测试类必须标注**：
  ```kotlin
  @org.junit.experimental.categories.Category(com.xianxia.sect.core.RobolectricTests::class)
  @RunWith(RobolectricTestRunner::class)
  class XxxTest {
  ```
- **`@Category` 必须放在 `@RunWith` 之前**：实测注解顺序对 JUnit filter 的类级排除
  生效有影响（GameEngineCoreFpsPolicyTest：@RunWith 在前时类级排除失败，交换后生效）。
  全模块统一顺序，勿变更。
- 不标注的后果：该类不会被 `testJvmRelease` 排除（纯 JUnit 任务里仍跑沙箱，
  慢但正确），也不会被 `testRobolectricRelease` 包含（单跑 Robo 任务时被漏掉）。

## 验证结果（2026-08-14，--no-build-cache 真实执行）

| 项 | 值 |
|---|---|
| testJvmRelease | 128 个 XML（127 纯 JUnit 执行 + 1 空沙箱），18.1s |
| testRobolectricRelease | 64 类（全部标注类），24.5s |
| 并集（去重） | 191 = testReleaseUnitTest 全集 ✓ |
| testReleaseUnitTest | 仍 191 类全量执行（45.1s），CI/kover 语义零变化 ✓ |

## 已知限制（诚实披露）

1. **DeviceCapabilityProfilerTest 空沙箱启动**：该类的类级 filter 排除后仍会启动
   Robolectric runner 产生空 XML（tests="0"，无测试执行，~1.4s 沙箱启动开销）。
   机制：JUnit4 Categories filter 对该类只做到方法级过滤（类级 Description 未被排除），
   **具体差异机制未完全解释**（与同结构的 GameEngineCoreFpsPolicyTest 行为不同）。
   影响：testJvmRelease 每次多 ~1.4s 空启动，正确性零影响。
2. **依赖 Kotlin 增量编译的注解感知**：新增/调整 @Category 后若出现归类异常，
   先删 `build/tmp/kotlin-classes/releaseUnitTest` 强制全量重编译再验证
   （Kotlin 增量对纯注解变更的感知存在盲区，2026-08-14 实测踩坑）。
3. **仅 core:engine 模块实施**：app / feature/game 的 Robolectric 占比未过门控
   （41% / 39%）且为渲染/集成测试混合，拆分收益低，未实施。
