# Robolectric 4.14+/4.16 升级评估（2026-08-14，结论：回滚保持 4.13）

> 阶段 2.4 产物。Robolectric 4.13 → 4.14+/4.16 升级尝试的完整调查与回滚决策。
> 目标：支持 SDK 35（消除 52 处 `@Config(sdk=34)` pin）。结果：**性能回退 + 字节码兼容，
> 保持 4.13**；同时发现并根治预存的 **Kover 开关 × TapTap SDK 字节码** 兼容问题（测试桩）。

## 尝试路径

1. `4.14.1`（libs.versions.toml 单行）→ app 模块 **254 个测试失败**
2. `4.14.2` → **Maven Central 不存在**（4.14 系列只有 4.14/4.14.1）
3. `4.16.1`（最新稳定版）→ 同样 254 个失败（相同根因）

## 根因 A：TapTap SDK 字节码缺 StackMapTable（与 Robolectric 版本无关的预存问题）

- 失败全部 `java.lang.VerifyError: Expecting a stackmap frame at branch target 21`
- 目标类：`com.byazt.td.Collector`（TapTap/TapADN SDK 的 manifest receiver，254 个失败 100% 同根因——首个 receiver 实例化失败阻塞整个应用安装，后续 receiver 根本没执行）
- **证据链**：
  1. **Robolectric 4.13 与 4.14+/4.16 一样**在应用安装时实例化 manifest receiver
     （4.13 的 `AndroidTestEnvironment` 反编译确认同样调用 `registerBroadcastReceivers`——"4.14 新增"是错误推断）
  2. 沙箱加载器加载 Collector → Robolectric 用 ASM 重写类
     （`ClassInstrumentor`，`ClassReader.accept(visitor, 0)` + `ClassWriter` 默认不计算 frames）
  3. Collector 原始字节码无 StackMapTable（dx/d8 老产物——ART 不要求 stackmap，JVM 验证器要求）
  4. ASM 透传无 frames 字节码 → JVM 类验证拒绝 → VerifyError
- **真正的触发变量：Kover 开关**（调查中意外发现，见"根因 C"）

## 根因 B：4.14+/4.16 测试执行显著回退（性能阻塞）

4.16.1 下全量测试执行时间（XML testsuite time 聚合，与基线同方法）：

| 模块 | 基线 4.13 | 4.16.1 | 变化 |
|---|---|---|---|
| app | 54.9s | 84.6s | +54% |
| core（engine+data+domain+ui） | 56.1s | 111.9s | +100% |
| feature/game | 28.8s | 79.4s | +176% |
| **总计（444 类）** | **139.8s** | **275.9s** | **+97%** |

Robolectric 4.14+ 的 ASM 插桩管线（每次类加载跑 ASM）比 4.13 的 javassist 管线慢近 2 倍。
feature/game（Robolectric 渲染测试，includeAndroidResources）受影响最重。

## 根因 C（调查中发现的预存问题）：Kover 开关决定 Collector 验证成败

- **现象**：回滚 4.13 后全量测试仍 254 失败（Collector VerifyError）——但**基线（4.13，kover 常驻启用）全绿**
- **对照实验**（决定性）：
  - `-Pkover.enabled=true`（transform 插桩执行）→ 单类/全量测试通过
  - 默认（kover `disable()`，transform 不执行）→ VerifyError
- **结论**：2.1 Kover 按需开关（默认关）**暴露了 TapTap SDK 字节码的预存兼容问题**；
  Kover 插桩开启时该问题被掩盖（插桩任务改变测试 classpath 结构后沙箱行为差异，
  **具体机制未完全解释**——诚实披露：尝试过 transform 输出目录检查、Robolectric
  类加载 dump、classpath 对比，均未找到决定性证据，仅确认行为事实）
- **修复（必要基建，不可删除）**：`app/src/test/java/com/byazt/td/Collector.kt` +
  `app/src/test/java/com/byazt/ru/DownloadReceiver.kt` 同名同包测试桩（空实现
  BroadcastReceiver）。沙箱 classpath 测试产物优先 → 桩字节码合法 → 验证通过。
  验证：kover 关 + 桩 = app 896 测试全绿（1m30s）
- **guard**：新增第三方 SDK 若带 manifest receiver，需同样检查字节码（`testReleaseUnitTest` 冒烟覆盖）

## 决策

- **保持 Robolectric 4.13**（libs.versions.toml 已回滚），52 处 `@Config(sdk=34)` pin 保留
- **测试桩保留**（4.13 + kover 关的必要基建；4.14+/4.16 同样需要）

## 技术债

| 债项 | 偿还触发条件 |
|---|---|
| Robolectric 4.13 卡死（SDK 35 需 4.14+，52 处 pin 无法删除） | TapTap SDK 字节码修复（升 SDK 验证 Collector 是否仍缺 stackmap）或 Robolectric 提供 COMPUTE_FRAMES 开关；升级后需重新评估性能（4.16 比 4.13 慢 ~2 倍） |
| 广告 SDK 升级时需重查 manifest receiver 字节码 | 任何第三方 SDK 升级后跑一遍 `testReleaseUnitTest` 冒烟 |
| Kover 插桩掩盖 Collector 问题的机制未完全解释 | 如未来要移除测试桩或改插桩方式，需先补完机制调查 |

## 回滚/恢复方式

```properties
# libs.versions.toml
robolectric = "4.13"   # 当前值
```
