# 规则：构建质量检查

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
