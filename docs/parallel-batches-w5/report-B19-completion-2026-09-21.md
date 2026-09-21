# B19 批完成报告 —— Room 死列清理（`game_data.battleTeam` 单数 / `aiBattleTeams`）

> 批次号：**B19** ｜ 范围：Room `game_data` 表 **v51 → v52**，删除 **2 列死列**
> 批次文件（施工卡）：`docs/parallel-batches-w5/batch-B19-room-dead-columns.md`
> 卡骨架来源：`docs/parallel-batches-w5/b18-remaining-impl-2026-09-20.md` 附 B
> 独立取证：`docs/save-system-audit-2026-09-21.md` §5（145/146 行）、§15（存疑项 7）
> 实施日期：2026-09-21 ｜ 渠道：桌面 WorkBuddy AI ｜ 用户指令：「直接实施 b19，完成后收尾」
> 🔴 **存档 schema 单独走批**：本批不与任何其他改动混装。

---

## 0. 结论速览

| 项 | 结果 |
|---|---|
| 死判复核 | ✅ 两列**真死**（全仓零生产者/零消费者，C++ 零出现） |
| 旧档数据窗口勘察 | ✅ 判归 **业务上可弃，直接删列不搬运**（v40 起产品路径已放弃该列） |
| schema 变更 | ✅ v51 → v52；`52.json` game_data **141 → 139** 列 |
| 迁移实现 | ✅ create-copy-drop-rename（多列通用单实现）+ 5 索引重建 + 幂等 |
| 存档回归 | ✅ 逐字段等价（含非空单数 `battleTeam` 旧档样本 + NULL 形态 + v39 全链） |
| 零行为变更 | ✅ 不做单数→复数搬运；C++/proto/`.sav`/`Diff*` 零改动 |
| 门禁 | 见 §4（逐门实测证据） |
| 残余 | 见 §6（诚实登记） |

---

## 1. 死判复核（任务 1）

`grep -rn --include=*.kt --include=*.cpp --include=*.h`（排除 `build/`、`schemas/`）逐类归口：

**`battleTeam`（单数）**：生产读写点 **0**。命中仅四类——
① 实体声明 `GameData.kt:491-494`（注释自陈「保留用于 Room schema 兼容旧存档」）；
② 迁移链历史基线 SQL（`GameDatabaseMigrationSupport.kt:70`、`GameDatabaseMigrationsV11ToV20.kt:98/339/394/484/548`，**不属死判范围**）；
③ 测试侧 v2 种子列清单（`RoomMigrationSupport.kt:351`）；
④ **同名局部变量**（`DiscipleSlotCleanupTest.kt:78/97` 的 `val battleTeam = BattleTeam(...)`，与实体字段无关）。

**`aiBattleTeams`**：生产写入点 **0**；唯一读取链
`GameData.kt:958 organization` → `SectOrganizationState.kt:19` → 该 DTO **生产零消费者**
（`grep -rn "\.organization\b" --include=*.kt` = **0 命中**，唯一引用者是 `StateEntitiesTest`）。

**C++ 侧零出现**：`models.h` / `json_codec.cpp` / 各 `*_tx.h` 只有复数 `battleTeams`
（`models.h:1428`）⇒ **本批零 C++ 改动**。

**辅助证据**：两列均带 `@kotlinx.serialization.Transient` ⇒ 不进 kotlinx descriptor
（`GameDataTransientFace` 差集面成员）⇒ 不进 `.sav`/proto/镜像信封，**只存在于 Room 列**；
`@SettlementStrategy` 为纯标记注解（生产零消费者）。

## 2. 旧档数据窗口勘察（任务 2）——判归：业务上可弃

| 事实 | 取证 |
|---|---|
| v40 引入复数三列时**只做 ADD COLUMN、未搬运单数数据** | `GameDatabaseMigrationsV40.kt:32-40` 无任何 `UPDATE` |
| 旧档队伍语义改由 `battleTeamsInitialized=false` 承担（走默认队伍初始化） | `GameData.kt:509-515` 注释 + V40 KDoc |
| 单数 `battleTeam` 自 v40 起（12 个 schema 版本）已被产品路径放弃 | 上面两条 + §1 零消费者 |
| 历史 v2 种子即把该列插为 `NULL` | `RoomMigrationSupport.kt:351/358` |

**结论**：直接删列，**不做单数→复数搬运**。搬运会让旧档凭空多出一支队伍（本应走默认
队伍初始化）⇒ 属行为变更，违反本批零行为变更口径；且该列在现版本零消费者，
保留与删除对运行时零差异。

## 3. 实施内容

### 3.1 改动面

| 文件 | 改动 |
|---|---|
| `core/domain/.../GameData.kt` | 删 `battleTeam` / `aiBattleTeams` 两字段及注解；`organization` 去 `aiBattleTeams` 实参；两处 tombstone 注释 |
| `core/domain/.../SectOrganizationState.kt` | 删 `aiBattleTeams` DTO 字段（保留即永久空字段） |
| `core/data/.../GameDatabase.kt` | `DATABASE_VERSION` **51→52**；`ALL_MIGRATIONS` 追加 `MIGRATION_51_52`；`@Database` 版本说明补 v52 行 |
| `core/data/.../GameDatabaseMigrationsV52.kt`（新） | `MIGRATION_51_52`：重建表删两列 + 5 索引重建 |
| `core/data/.../GameDatabaseMigrationSupport.kt` | 新增 `rebuildTableDroppingColumns`（**删列迁移唯一实现**：PRAGMA 多列通用 + 幂等 + 索引重建） |
| `core/data/.../GameDatabaseMigrationsV50.kt` | 私有单列实现**收敛为委托**（纯抽取，语义逐字等价） |
| `core/data/.../CollectionConverters.kt` | 删 `fromAIBattleTeamList`/`toAIBattleTeamList` + 孤儿 import |
| `core/engine/.../GameDataTransientFace.kt` | KDoc 口径同步（差集面 9 → **7**） |
| `core/data/schemas/.../52.json`（新，KSP） | game_data 141 → 139 列；identityHash `d61cc2cd…` → `f1b63926…` |
| `core/domain/src/test/.../StateEntitiesTest.kt` | 删 DTO 字段断言 |
| `core/data/src/test/.../RoomMigrationV51To52Test.kt`（新） | 5 例守卫 |

### 3.2 迁移实现（v51→v52）

SQLite < 3.35（API24 内置 3.9）无 `DROP COLUMN` ⇒ **create-copy-drop-rename**：
① `PRAGMA table_info` 读旧表全列定义（类型/NOT NULL/DEFAULT）→ ② 剔除两死列后逐列
`CREATE TABLE`（带回 `PRIMARY KEY(id, slot_id)`）→ ③ 逐列名 `INSERT SELECT` 复制 →
④ `DROP TABLE _old` → ⑤ 重建 5 索引。**幂等**：待删列一个都不存在即返回。
**回滚**：升级前 `backupDatabaseForMigration` 落 `.pre_migrate_backup.v51`（先
`wal_checkpoint(TRUNCATE)` 再文件级复制，保留 2 版）；降级依赖该备份（Room 不支持降级打开）。

## 4. 门禁实测证据

### 门 1 — `:core:data` 定向（Robolectric 真实 Room）

```
./gradlew.bat :core:data:testReleaseUnitTest --max-workers=1
BUILD SUCCESSFUL in 1m 35s   （56 actionable tasks: 9 executed, 47 up-to-date）
```
XML 汇总（`core/data/build/test-results/testReleaseUnitTest/`，timestamp 区间
`2026-09-21T07:22:25Z → 07:22:42Z`）：**721 例 / 0 失败 / 0 错误 / 15 既有跳过**
（= 既有 716 + 本批 5）。

本批 5 例（`TEST-...RoomMigrationV51To52Test.xml`，0 失败 0 错误 0 跳过）：

| 用例 | 覆盖 |
|---|---|
| `MIGRATION_51_TO_52 passes real Room schema validation` | v51 库经 `Room.databaseBuilder` 升 v52，触发 `onValidateSchema` 列/索引/主键全等比较 |
| `MIGRATION_51_TO_52 drops dead columns and preserves every remaining column` | 删列 + **逐字段等价**（两行样本：非空单数 `battleTeam` / NULL；活跃复数列三列逐字保留；列数恰少 2；5 索引重建） |
| `V39 legacy save migrates to v52 with exact dropped set and zero field drift` | v39 全链 40→52：被删列集**精确等于** `{autoSaveIntervalMonths, battleTeam, aiBattleTeams}`；`battle_teams` 取 v40 DEFAULT `''`（**证明未搬运**）；交集列零漂移 |
| `MIGRATION_51_TO_52 is idempotent when dead columns already absent` | 幂等重放零变化 |
| `schema 52 excludes dead columns while 51 keeps them as historical snapshot` | 静态防回流（52.json 无两列 ∧ 51.json 有 = 对照面非空转；列数恰少 2） |

### 门 2 — 六模块组合门

```
cd android && export JAVA_HOME=C:/Users/cp050/.jdks/jdk-21.0.12.1+1
./gradlew.bat testReleaseUnitTest --max-workers=1 \
  "-Dgamecore.jni.path=C:/Mnzm/XianxiaSectNative/android/core/engine/build/desktop-jni/libgamecorejni.so" \
  detekt compileReleaseKotlin lintRelease
```

**段一 · 组合门**（`/tmp/b19-gate4.log`）：
`BUILD SUCCESSFUL in 3m 58s` / **339 actionable tasks: 23 executed, 316 up-to-date** /
**FAILED 计数 = 0**。其中 `detekt`（`core:domain` 实跑，其余 UP-TO-DATE=本轮已绿）、
`lintRelease` 六模块**全部实跑**、`compileReleaseKotlin` 六模块全绿。

**段二 · 补强重跑**（`/tmp/b19-gate5-tests.log`，六个 test 任务在本轮为 UP-TO-DATE ⇒ 按纪律
强制重跑取本轮实证）：
```
./gradlew.bat testReleaseUnitTest --max-workers=1 --rerun-tasks \
  "-Dgamecore.jni.path=C:/Mnzm/.../libgamecorejni.so"
→ BUILD SUCCESSFUL in 12m 58s / 229 actionable tasks: 229 executed
```
XML 汇总（`build/test-results/testReleaseUnitTest/`，timestamp 区间
`2026-09-21T11:07:47Z → 11:14:43Z`，全部为本轮）：

| 模块 | 用例 | 失败 | 错误 | 跳过 |
|---|---|---|---|---|
| `:core:domain` | 1743 | 0 | 0 | 0 |
| `:core:data` | **721** | 0 | 0 | 15 |
| `:core:engine` | 3381 | 0 | 0 | 0 |
| `:core:ui` | 146 | 0 | 0 | 0 |
| `:feature:game` | 887 | 0 | 0 | 0 |
| `:app` | 1010 | 0 | 0 | 2 |
| **TOTAL** | **7888** | **0** | **0** | **17**（既有） |

`Diff*` **50 类 / 273 例 / 0 skip**；`SoftwareCanvasBackend*` **10 类 / 106 例 / 0 skip**
（Canvas 兜底零改动实证）。相对 B18 收口基线 7882：**+5（本批守卫）+1（B18 补遗
`TypedEnvelopeSizeBenchTest` 于 `13221fbda` 入库）= 7888**。

### 门 3 — 桌面 GTest（照跑，纯 Kotlin 批）

```
cd android/app/src/main/cpp/gamecore && ninja -C build/desktop-test   → ninja: no work to do.
ctest --test-dir build/desktop-test                                   → 100% tests passed, 0 tests failed out of 1558
Total Test time (real) = 385.27 sec
```
**1558/1558**，与 B18 基线持平（零 C++ 改动，用例数不变）。

### 门 4 — detekt（本轮实打回一次，已修）

首轮组合门 `:core:data:detekt FAILED`：
`RoomMigrationV51To52Test.kt:86:9 ... is too long (80). The maximum length is 60. [LongMethod]`
⇒ **重构为「库生命周期夹具 + 断言助手」拆分**（最长方法 33 行），复验
`:core:data:detekt` 绿。（教训：**本仓库 detekt 扫描 test source set**，新测试方法同样受
60 行阈值约束。）

## 5. 提交清单

| # | commit | 内容 |
|---|---|---|
| 1 | `aa740d25c` | `feat(data): B19 Room 死列清理——game_data v51→v52 删除 battleTeam/aiBattleTeams 两死列`（9 文件 / +5799 −71） |
| 2 | `6852868b3` | `test(data): B19 迁移与存档回归守卫——RoomMigrationV51To52Test 5 例`（2 文件 / +427 −1） |
| 3 | `76d39b053` | `docs(B19): 施工卡 + 方案 §7.2 + CHANGELOG + 台账 + 完成报告 + 存档勘察报告入库`（6 文件 / +1023 −2） |

（第 1 笔含 KSP 生成的 `52.json`（约 5.6k 行 JSON）⇒ 行数占比大属正常。）

## 6. 诚实登记（未达标 / 残余 / 未验证）

1. **`AIBattleTeam` 模型类型本体保留**：删列后该 `@Serializable` 数据类零引用。
   删除它属领域模型清理，且触碰 proto/序列化守卫面（按类枚举的守卫），**超出本批范围** ⇒ 登记为后续。
2. **存档系统其余问题零处理**：`docs/save-system-audit-2026-09-21.md` §16 优先级建议
   1–10（归档删弟子主表 / UI 谎报保存成功 / heavy 先删后写 / 假 `.bak` /
   `@ProtoNumber(162)` 冲突 / 触发模型 / 删档残留 / 空函数恢复 / boot 覆盖存档值 /
   死代码清理）**全部未处理**——本批只做死列清理，不夹带。
3. **真机升级路径未实测**：无设备与截图回归基建；证据 = Robolectric 真实 Room 打开校验
   （`onValidateSchema` 全等比较）+ 存档回归逐字段等价 + v39 全链。**真机旧档实机迁移未验证**。
4. **并行会话环境事实**：组合门首跑在 1m34s 被外部 `gradlew --stop`（另一会话验收 SOP）中止
   （`Gradle build daemon has been stopped: stop command received`）；另实测**并发跑 ctest 会让
   `:core:engine:compileReleaseKotlin` 出现假失败**（`Detected multiple Kotlin daemon sessions`，
   单跑即绿）。⇒ 组合门须在对方空闲窗口**串行**跑，不与其他重活并发。
5. **`build-atlas.mjs` 类构建副产物**：`atlas-rgba-manifest.json` 的 `generatedAt` 漂移
   **未混入提交**（`git checkout --` 还原）。

## 7. 文档同步（收尾）

| 文档 | 状态 |
|---|---|
| `docs/native-engine-refactor-plan-2026-09-17.md` §7.2 | ✅ 追加 **B19 批** 段 |
| `CHANGELOG.md`（4.01.15 段内） | ✅ 追加 **B19 批** 段 |
| `docs/parallel-batches-w5/dispatch-ledger.md` | ✅ B19 行状态更新 |
| `docs/parallel-batches-w5/batch-B19-room-dead-columns.md` | ✅ 施工卡（含 §0 勘察结论 + §5 实施记录） |
| `android/docs/renderer-feature-checklist.md` | ➖ **无需更新**（本批零渲染面改动） |
| `docs/save-system-audit-2026-09-21.md` | ✅ 入库 + 补 B19 后续处置注 |
