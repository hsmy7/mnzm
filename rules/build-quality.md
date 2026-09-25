# 规则：构建质量检查

**测试必须串行运行（2026-08-04 起强制）：** 所有测试命令（全量/单类/CI/本地）一律加 `--max-workers=1`——并行执行会因共享静态状态跨类污染而出错（`./gradlew.bat test --max-workers=1`）。禁止省略该参数。

**每次完成任务后必须执行以下检查，不可跳过**：

```bash
# 1. Kotlin 编译检查
cd android && ./gradlew.bat compileReleaseKotlin

# 2. 检查是否有新增警告
./gradlew.bat assembleRelease 2>&1 | grep -E "^w:" | wc -l
```

需要检查的项目：
- **编译错误**：`compileReleaseKotlin` 必须 BUILD SUCCESSFUL
- **Lint 警告**：`./gradlew.bat lintRelease` 检查是否有新增严重问题
- **Kotlin 警告**：关注 deprecation、unused variable、unchecked cast 等
- **KSP 增量编译缓存**：如遇到 `NoSuchFileException: *_Impl.java`，执行 `./gradlew.bat clean` 后重试

如果发现**构建错误或编译警告**，必须先修复再视为任务完成。已有警告（如 `VerificationResult deprecated`）不需要修复，但不应引入新的同类警告。

**扩展枚举/注册表/配置项的额外检查（2026-08-04 起）：** 新增枚举或注册表项（`AdPurpose`、`DialogType`、`SpriteCategory`、`RngPartition`、`GuideTask`、`SlotCategory`、建筑注册表、`SOURCE_DISPLAY_NAMES` 等）的扩展任务，构建检查之外**必须运行对应守卫测试**（AGENTS.md 9.5：`SlotCategoryCoverageTest` / `InventoryAddPathGuardTest` / 渲染覆盖守卫等）——守卫测试失败即任务未完成，不得跳过。

---

## 完整命令清单（原根 `AGENTS.md` §2 下沉）

命令在 `android/` 目录下用 Gradle wrapper 执行：

```bash
# 构建 release / debug APK
cd android && ./gradlew.bat assembleRelease
cd android && ./gradlew.bat assembleDebug

# 跑单个测试类 — 必须串行，且必须模块限定写法（裸 `test --tests` 会报 Unknown command-line option；
# 未模块限定时过滤会波及 core:data 等模块触发 No tests found）
cd android && ./gradlew.bat :app:testReleaseUnitTest --tests "com.xianxia.sect.core.engine.BattleSystemTest" --max-workers=1

# 清理（KSP 增量缓存炸出 NoSuchFileException *_Impl.java 时用）
cd android && ./gradlew.bat clean
```

```bash
# 覆盖率（Kover）— 本地默认关闭（消除插桩开销），必须显式开开关，否则覆盖率为 0
cd android && ./gradlew.bat koverHtmlReport --max-workers=1 -Pkover.enabled=true

# 完整 CI 检查（编译 + 测试 + detekt + 覆盖率 + RNG 守卫）— 测试必须串行
# RNG 红线是守卫测试而非 grep（`.random()` 是 stdlib 扩展、自建 RNG 是 object，都不带
# `import kotlin.random.Random`，grep 匹配不到）——见 docs/adr/rng-determinism-remediation.md §1
cd android && ./gradlew.bat compileReleaseKotlin testReleaseUnitTest --max-workers=1 -Pkover.enabled=true detekt koverHtmlReport -Pkover.enabled=true && ./gradlew.bat :core:engine:testReleaseUnitTest --tests "com.xianxia.sect.core.architecture.RngSourceGuardTest" --tests "com.xianxia.sect.core.architecture.RngEngineIsolationGuardTest" --max-workers=1
```

Robolectric 测试需要 `includeAndroidResources = true`；mock/stub 约定见 `rules/testing.md`。

**规范分发架构门禁**（改根 `AGENTS.md`、`rules/`、`docs/` 或任何 `AGENTS.md` 之后必跑）：`node scripts/check-agent-instructions.mjs`
