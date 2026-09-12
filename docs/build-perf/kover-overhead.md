# Kover 插桩开销实验 + 按需开关（2026-08-14）

> 阶段 2.1 产物。量化 Kover 常驻插桩的测试耗时开销，决定是否按需启用。

## 背景

6 模块（app / feature:game / core:data / core:domain / core:engine / core:ui）常驻
Kover 插桩（2026-08-01 为修复覆盖率盲区全量启用）。每个模块的测试 class 都经
`transformXxxClassesWithAsm` 插桩，串行测试（`--max-workers=1`）下插桩后的类
加载/执行更慢。本地开发（`testReleaseUnitTest`）并不需要覆盖率数据，只 CI 需要。

## 实验方法（避免缓存污染）

- **内容触发测量法**：对 `DiscipleRepository.kt` 追加注释行（内容变化）触发 Gradle
  重跑，避免 `clean`（Windows 文件锁）与 `--rerun-tasks`（与 `org.gradle.parallel=true` 冲突）
- **禁用 `--build-cache`**：否则任务 FROM-CACHE 不执行插桩，A/B 组数据无效
- A 组：kover 开（插桩）| B 组：kover 关（临时注释插件）
- 每组内容触发重跑 3 次取中位数，只测 `core:data:testReleaseUnitTest` 链路
- 实验探针（注释行）测后已全部还原

## 结果

| 组 | 插桩 | 耗时（内容触发重跑） |
|---|---|---|
| A（kover 开） | ✅ | 9.3s / 10.5s / 11.2s（中位数 ~10.5s） |
| B（kover 关） | ❌ | 6.8s / 6.9s / 6.9s（中位数 6.9s） |

**开销：34% ~ 65%（中位 ~52%）**，远超 10% 决策阈值。

第一次测量（A 13.2s vs B 15.9s）因 `--build-cache` 污染（任务 FROM-CACHE 未插桩）
被判无效，加 `--no-build-cache` + 内容触发后重测得到有效数据。

## 决策：按需开关

Kover 0.9.1 的 `KoverProjectExtension` 只有 `disable()` 方法（javap 反编译确认，
无 `isDisabled` Property）。6 模块 build.gradle 的 plugins 块后统一加：

```groovy
// 2026-08-14：Kover 按需开关——本地默认关闭（消除插桩开销），CI 传 -Pkover.enabled=true
kover {
    if (!providers.gradleProperty('kover.enabled').getOrElse('false').toBoolean()) {
        disable()
    }
}
```

- **本地默认**：无属性 → `disable()` → 插桩任务与报告任务 SKIPPED，测试无插桩
- **CI**：`testReleaseUnitTest` 与 `koverHtmlReport` 均传 `-Pkover.enabled=true`
  （插桩与报告必须同开关，否则覆盖率为 0）
- 验证结果：
  - 默认：`:core:data:koverHtmlReport` → `koverGenerateArtifact SKIPPED` / `koverHtmlReport SKIPPED`
  - 开启：`koverGenerateArtifact` + `koverHtmlReport` 正常执行并输出 HTML 报告

## 约束说明

- 覆盖率要求（`:core:engine` 80%+）不变——CI 的 `koverHtmlReport` 步骤仍然全量插桩，
  detekt/Kover 门禁语义零变化
- `kover.enabled` 属性只影响插桩与报告生成，不影响测试执行本身

## 回滚方式

任一模块删除 `kover { ... }` 块，或全局去掉 ci.yml/CLAUDE.md 中的
`-Pkover.enabled=true`（回退到常驻插桩，代价是测试慢 34~65%）。
