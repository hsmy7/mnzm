# 模拟宗门 (XianxiaSectNative) - CLAUDE.md

## 项目概况

Android 修仙宗门模拟经营游戏，使用 Kotlin + Jetpack Compose + Room + Hilt 构建。
- 最低 SDK: 24，目标 SDK: 35
- 数据库: Room (SQLite WAL 模式)，版本 18
- 存储路径: `android/app/src/main/java/com/xianxia/sect/`

## 工作流程规则

### 规则 1: 数据库迁移与旧存档兼容

执行任何涉及数据模型变更的任务时（新增字段、修改 Entity、调整表结构等），**必须在设计阶段就考虑以下问题**：

1. **数据库版本升级**：每次 schema 变更必须编写对应的 `MIGRATION_X_Y`，在 `GameDatabase.kt` 中注册并递增 `version`
2. **旧存档兼容**：新版本必须能正确读取旧版本数据库中的所有数据，不能依赖 `fallbackToDestructiveMigration`
3. **Schema 校验**：迁移 SQL 必须与 Room `@Entity` 定义完全一致（包括 FK 约束、索引、列类型），否则 Room 会抛出 `IllegalStateException`
4. **测试路径**：确认从近3个大版本升级时，迁移能成功执行且数据完整
5. **决策优先**：能用 `ALTER TABLE ADD COLUMN` 只加列解决的不拆表；拆表重构必须完成全链路（Entity → Migration → DAO → DI → StorageEngine 读写 → delete 清理）

**反例**：MIGRATION_15_16 创建了子表但应用代码从未使用，且 `game_data_core` 遗漏了 FK 约束导致 Room schema 校验失败。

### 规则 2: 构建质量检查

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

### 规则 3: 版本更新与提交

**每次完成任务后执行以下版本发布流程**：

1. **更新版本号**：编辑 `android/app/build.gradle`
   - `versionCode` 递增 1（当前: 2144）
   - `versionName` 格式 `x.x.xx`，小版本号递增（当前: 2.5.77 → 2.5.78）
   
2. **更新 CHANGELOG.md**：在文件顶部添加新条目
   ```markdown
   ## [2.5.XX] - YYYY-MM-DD
   
   ### 修改简述
   - 修改内容1
   - 修改内容2
   - 修改内容3
   ```

3. **提交并推送**：
   ```bash
   git add -A
   git commit -m "v2.5.XX: <简短描述>"
   git push origin main
   ```

4. **确认推送成功**：`git log --oneline -3` 验证提交已推送

## 关键文件索引

| 用途 | 路径 |
|------|------|
| 数据库定义 + 迁移 | `.../data/local/GameDatabase.kt` |
| 存储引擎（读写删除） | `.../data/engine/StorageEngine.kt` |
| 存储门面（对外 API） | `.../data/facade/StorageFacade.kt` |
| GameData 主模型 | `.../core/model/GameData.kt` |
| 存档视图模型 | `.../ui/game/SaveLoadViewModel.kt` |
| 游戏引擎 | `.../core/engine/GameEngine.kt` |
| 版本配置 | `android/app/build.gradle` (versionName) |
| 更新日志 | `CHANGELOG.md` (项目根目录) |
| 构建设置 | `android/settings.gradle` |
