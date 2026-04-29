# 规则：版本更新与提交

**每次完成任务后执行以下版本发布流程**：

1. **更新版本号**：编辑 `android/app/build.gradle`
   - `versionCode` 递增 1
   - `versionName` 格式 `x.x.xx`，小版本号递增

2. **同时更新两个更新日志**：
   
   **CHANGELOG.md**（项目根目录）— 在文件顶部添加新条目：
   ```markdown
   ## [2.6.XX] - YYYY-MM-DD

   ### 修改简述
   - 修改内容1
   - 修改内容2
   - 修改内容3
   ```

   **ChangelogData.kt**（`core/ChangelogData.kt`）— 在 `entries` 列表最前面添加新条目：
   ```kotlin
   ChangelogEntry(
       version = "2.6.XX",
       date = "YYYY-MM-DD",
       changes = listOf(
           "修改内容1",
           "修改内容2",
           "修改内容3"
       )
   ),
   ```
   两者版本号和内容必须一致。

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
| 更新日志（项目） | `CHANGELOG.md` (项目根目录) |
| 更新日志（游戏内） | `.../core/ChangelogData.kt` |
| 构建设置 | `android/settings.gradle` |
