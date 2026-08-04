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

**扩展枚举/注册表/配置项的额外检查（2026-08-04 起）：** 新增枚举或注册表项（`AdPurpose`、`DialogType`、`SpriteCategory`、`RngPartition`、`GuideTask`、`SlotCategory`、建筑注册表、`SOURCE_DISPLAY_NAMES` 等）的扩展任务，构建检查之外**必须运行对应守卫测试**（CLAUDE.md 9.5：`SlotCategoryCoverageTest` / `InventoryAddPathGuardTest` / 渲染覆盖守卫等）——守卫测试失败即任务未完成，不得跳过。
