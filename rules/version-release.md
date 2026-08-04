# 规则：版本更新与提交

**每次完成任务后执行以下版本发布流程**：

1. **更新版本号**：编辑 `version.properties`（项目根目录）
   - `versionCode` 递增 1
   - `versionName` 格式 **`X.XX.XX`**（主版本 1 位 + 次版本 2 位 + 构建 2 位，不足前补零）
   - 例：`4.00.86` → `4.00.87`，`4.00.99` → `4.01.00`，`4.99.99` → `5.00.00`
   - **禁止写成 `4.0.86` / `4.00.9`（缺少次版本或构建段的前补零）**
   - 此文件为唯一版本真源，所有模块（app、core:domain、core:engine）自动同步（`BuildConfig.VERSION_NAME`）

2. **同时更新两个更新日志（必须一起更新，漏一个视为任务未完成）**：
   
   **CHANGELOG.md**（项目根目录）— 在文件顶部添加新条目：
   ```markdown
   ## [4.00.XX] - YYYY-MM-DD

   ### 修改简述
   - 修改内容1
   - 修改内容2
   - 修改内容3
   ```

   **changelog_entries.json**（`android/app/src/main/assets/changelog_entries.json`）— 在当前版本条目的 `changes` 数组末尾追加一行：
   ```json
   {
       "version": "4.00.XX",
       "date": "YYYY-MM-DD",
       "changes": [
           "修改内容1",
           "修改内容2",
           "修改内容3"
       ]
   }
   ```
   两者版本号必须一致；外部日志内容可比游戏内详细（给开发者看），游戏内条目遵循下述**玩家视角规范**。

   > ⚠️ **注意**：`core/data/.../ChangelogData.kt` 是**解析器**（读取 `assets/changelog_entries.json`），不是编辑对象。禁止直接修改解析器中的 `entries` 列表——游戏内更新日志的编辑对象只有 `changelog_entries.json`。

   ### 游戏内更新日志规范（给玩家看的）

   游戏内 `changelog_entries.json` 的 `changes` 条目面向玩家展示，必须满足：

   - **通俗易懂、无专业术语** — 不出现"迁移""Entity""PRNG""存档协议"等技术词，玩家不理解的词一律换说法（如"修复了部分设备上游戏时间停止不动的问题"）
   - **不泄露数值细节** — 不写具体概率/数值/倍率/消耗（"突破概率提升 2%"❌ → "突破更容易成功了"✅；"每日签到奖励翻倍"✅ 可写）
   - **只能粗略描述** — 不写内部实现说明、不列文件名/类名，功能表述到"玩家能感知的变化"粒度即可

   正例：`"修复：部分机型游戏时间偶尔停止不动的问题已彻底解决"`、`"新增：远古秘境玩法——每 50 年开启一次，派遣弟子探索获得宝物"`
   反例：`"修复：MIGRATION_38_39 迁移校验失败导致启动崩溃"`、`"优化：每旬热点路径列直读提升 80% 性能"`

   ### 更新日志条目合并规则

   - **不得按日期分割为多个相同版本条目** — 同日/同版本多批次发布，一律合并到**同一条目**的 `changes` 数组末尾追加，禁止新建同版本号的第二条目
   - 版本条目的 `date` 取该版本首次发布日（同日多次发布不新建条目、不改日期）
   - 当前版本条目已存在时：直接向 `changes` 数组追加，不新建 `{ "version": ..., ... }` 对象

3. **提交并推送**：
   ```bash
   git add -A
   git commit -m "v2.5.XX: <简短描述>"
   git push origin main
   ```

4. **确认推送成功**：`git log --oneline -3` 验证提交已推送

> **纯规范/文档变更**（仅改 `rules/`、`docs/`、`CLAUDE.md`，不改代码与资源）：**不递增 version.properties**，但必须完成文档一致性检查（三方交叉引用 `CLAUDE.md ↔ rules/ ↔ docs/` 无死链、无过期路径、无矛盾事实）。若变更涉及玩家可见内容（如更新日志文案），仍按第 2 步同步双 changelog。

## 关键文件索引

| 用途 | 路径 |
|------|------|
| 数据库定义 + 迁移 | `.../data/local/GameDatabase.kt` |
| 存储引擎（读写删除） | `.../data/engine/StorageEngine.kt` |
| 存储门面（对外 API） | `.../data/facade/StorageFacade.kt` |
| GameData 主模型 | `.../core/model/GameData.kt` |
| 存档视图模型 | `.../ui/game/SaveLoadViewModel.kt` |
| 游戏引擎 | `.../core/engine/GameEngine.kt` |
| 版本配置 | `version.properties` (项目根目录，唯一版本真源) |
| 更新日志（项目） | `CHANGELOG.md` (项目根目录) |
| 更新日志（游戏内） | `android/app/src/main/assets/changelog_entries.json`（编辑对象） |
| 更新日志（解析器） | `core/data/.../ChangelogData.kt`（只读解析，非编辑对象） |
| 构建设置 | `android/settings.gradle` |
