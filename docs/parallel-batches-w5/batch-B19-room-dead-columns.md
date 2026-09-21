# 批次 B19 — Room 死列清理批（`game_data.battleTeam` 单数 / `game_data.aiBattleTeams`）

> 来源：`docs/parallel-batches-w5/b18-remaining-impl-2026-09-20.md` **附 B**（B19 卡骨架）；
> 收口批 C 组判归（`docs/parallel-batches-w5/handover-closing-batch-2026-09-20.md`）；
> 独立取证报告 `docs/save-system-audit-2026-09-21.md` §5（145/146 行）、§15（存疑项 7）。
> 台账行：`docs/parallel-batches-w5/dispatch-ledger.md` **B19**。
> 用户 2026-09-21 指令「直接实施 B19，完成后收尾」触发本批开工。
>
> ## 🔴 单独走批红线（本批最高优先级）
> **存档 schema 变更不得与任何其他批混装**；错误 migration = **不可逆用户数据丢失**。
> 本批只碰 `game_data` 表的两列删除与版本递增，不夹带任何其他改动。

---

## 0. 开工前置：死判复核 + 旧档数据窗口勘察（**已完成，结论如下**）

### 0.1 死判复核（任务 1）——两列均为**真死列**

| 列 | 生产写入者 | 生产读取者 | 判归 |
|---|---|---|---|
| `battleTeam`（**单数**，TEXT NULL） | **0** | **0** | **死列** |
| `aiBattleTeams`（TEXT NOT NULL） | **0** | **0**（详见下） | **死列** |

取证（`grep -rn --include=*.kt --include=*.cpp --include=*.h`，排除 `build/` 与 `schemas/`）：

**`battleTeam`（单数）全仓命中 11 处，逐类归口：**

| # | 位置 | 性质 |
|---|---|---|
| 1 | `android/core/domain/.../GameData.kt:491-494` | 实体声明（注释自陈「保留用于 Room schema 兼容旧存档，逻辑层使用 battleTeams」） |
| 2 | `GameDatabaseMigrationSupport.kt:70` | v29 历史基线 `GAME_DATA_CREATE_SQL`（**迁移链内列搬运引用，不属死判范围**） |
| 3-6 | `GameDatabaseMigrationsV11ToV20.kt:98/339/394/484/548` | v11→v20 历史重建/列清单（同上，不属死判范围） |
| 7 | `android/core/data/src/test/.../RoomMigrationSupport.kt:351` | 测试侧 v2 种子行列清单（种子写入，非生产） |
| 8 | `android/core/engine/src/test/.../DiscipleSlotCleanupTest.kt:78/97` | **同名局部变量** `val battleTeam = BattleTeam(...)`，与实体字段无关 |

**`aiBattleTeams` 全仓命中，逐类归口：**

| # | 位置 | 性质 |
|---|---|---|
| 1 | `GameData.kt:519-522` | 实体声明（`@kotlinx.serialization.Transient` + `@SettlementStrategy(USE_SHADOW)`） |
| 2 | `GameData.kt:958` | `organization` 聚合属性 → `SectOrganizationState(aiBattleTeams = …)` |
| 3 | `SectOrganizationState.kt:19` | DTO 字段 |
| 4 | `StateEntitiesTest.kt:16` | 唯一引用者（DTO 默认值断言） |
| 5 | 迁移链历史 SQL（同 `battleTeam` 的 2/3 类） | 不属死判范围 |

**关键取证**：`grep -rn "\.organization\b" --include=*.kt android`（排除 `build/`）= **0 命中**
⇒ `GameData.organization` 在生产**零消费者**，`SectOrganizationState` 是无人消费的 DTO，
其 `aiBattleTeams` 字段因此**读不到任何值**。

**C++ 侧零命中**：`models.h` / `json_codec.cpp` / 各 `*_tx.h` 只有**复数** `battleTeams`
（`models.h:1428`），单数 `battleTeam` 与 `aiBattleTeams` 在 C++ 全仓**零出现**
⇒ 本批**零 C++ 改动**。

**辅助证据（为什么不进 `.sav`/proto/镜像信封）**：两列均带 `@kotlinx.serialization.Transient`
⇒ 不进 kotlinx descriptor（`GameDataTransientFace` 的差集面成员）⇒ 不进 `.sav`、不进 proto、
不进 GameView 信封面，**只存在于 Room 列**。`@SettlementStrategy` 是纯标记注解
（`SettlementStrategy.kt` KDoc 自陈「此注解保留用于标记字段的合并语义」），生产零消费者。

### 0.2 旧档数据窗口勘察（任务 2）——**业务上可弃，直接删列、不做搬运**

| 事实 | 取证 |
|---|---|
| v40（`MIGRATION_39_40`）引入复数三列 `battle_teams` / `used_team_numbers` / `battle_teams_initialized`，**未搬运单数→复数** | `GameDatabaseMigrationsV40.kt:32-40` 只有 3 条 `ADD COLUMN`，无 `UPDATE` |
| 旧档队伍语义改由 `battleTeamsInitialized=false` 标记（旧档走默认队伍初始化） | `GameData.kt:511-517` 注释 + `GameDatabaseMigrationsV40.kt:14-16` |
| 单数 `battleTeam` 自 v40 起（**12 个 schema 版本**）已被产品路径放弃 | 上面两条 + 0.1 的零消费者结论 |
| 历史 v2 种子即把该列插为 `NULL`（`aiBattleTeams` 插 `'{}'`） | `RoomMigrationSupport.kt:351` 列清单 + `:358` 值行 `… '{}', NULL, '{}', …` |

**判归 = 直接删除列，不做单数→复数搬运。** 理由：
1. 搬运（`UPDATE game_data SET battle_teams = <由单数构造的兼容形状>`）会引入**行为变更**
   —— 旧档本应走「默认队伍初始化」，搬运会凭空多出一支队伍 ⇒ 违反本批零行为变更口径；
2. 该列在现版本**零消费者**，保留与删除对运行时**零差异**（只影响 DB 文件体积与 schema 面）；
3. `.sav` / 云档路径从来没有该列（`@Transient`）⇒ 删除**不影响任何备份/恢复路径**。

### 0.3 波及面勘察（删字段的连锁面，全部已定位）

| 触点 | 处置 |
|---|---|
| `GameData.kt` 两字段 | 删除 |
| `GameData.kt:954 organization` 聚合属性 | 去掉 `aiBattleTeams = aiBattleTeams` 实参 |
| `SectOrganizationState.kt:19` DTO 字段 | 删除（生产零消费者，保留即永久空字段） |
| `StateEntitiesTest.kt:16` | 删除对应断言 |
| `GameDataTransientFace` 差集面（9 字段） | 自动收缩为 7 字段（**差集法结构性自动纳管，无需改实现**）；守卫 `GameDataFieldPatchGuardTest` 用 `fieldNames` 动态遍历，无硬编码计数 |
| Room schema | `52.json` 由 KSP 导出；`51.json` 保留为历史快照 |
| `GameDatabaseMigrationSupport.kt:70` / `GameDatabaseMigrationsV11ToV20.kt` | **保留**（历史迁移基线，改动会让老档迁移路径失真） |
| `RoomMigrationSupport.kt:351` v2 种子 | **保留**（插的是 v2 形态表，迁移前） |
| C++ / proto / `.sav` / `Diff*` 对拍 | **零改动**（C++ 零出现；`@Transient` 不进协议面） |

---

## 1. 任务

1. **删除实体字段**：`GameData.battleTeam`、`GameData.aiBattleTeams` 及 `@SettlementStrategy` /
   `@Transient` 注解；同步 `organization` 聚合属性与 `SectOrganizationState`；
2. **版本递增**：`GameDatabaseConfig.DATABASE_VERSION` **51 → 52**（唯一版本常量，
   `@Database(version)` 与迁移前备份判据统一引用）；
3. **登记迁移**：新增 `MIGRATION_51_52` 并追加到 `ALL_MIGRATIONS` 末尾
   （`MigrationChainGuardTest` 纪律：漏登记即红）；
4. **删列走重建表模式**：SQLite < 3.35（API24 内置 3.9）不支持 `DROP COLUMN`
   ⇒ create-copy-drop-rename（沿 `MIGRATION_49_50` / `MIGRATION_38_39` 先例），
   PRAGMA 驱动逐列重建 + 逐列 `INSERT SELECT` + 重建 5 个索引；
   **不得复用 `GAME_DATA_CREATE_SQL`**（v29 基线，会丢 v29 之后的 21+ 列）；
5. **Room schema 导出更新**：KSP 生成 `core/data/schemas/.../52.json`（139 列）；
6. **存档回归**：构造 v51 旧档样本（`battleTeam` **非空** + 全部列写可区分哨兵值）→ migrate →
   读回 → **逐字段等价断言**（所有存活列值全等）+ 被删列处置确认；
7. **回滚方案说明**：升级前自动备份判据（`backupDatabaseForMigration`，`.pre_migrate_backup.v51`）
   + 降级不支持声明。

## 2. 红线（违者验收打回）

- **存档 schema 单独走批**：本批不与任何其他改动混装；
- **逐字段等价**：迁移后除两死列外**所有列值全等**（含 `battle_teams` / `used_team_numbers` /
  `battle_teams_initialized` 三活跃列）；
- **零行为变更**：不做单数→复数搬运；不改任何读写路径；C++ / proto / `.sav` / `Diff*` 零改动；
- **迁移链历史基线零触碰**：`GAME_DATA_CREATE_SQL`、`GameDatabaseMigrationsV11ToV20.kt`、
  `RoomMigrationSupport` v2 种子行一律保留原样；
- **`MigrationChainGuardTest` 纪律**：版本递增与迁移登记必须同步；
- **每子项独立 commit**（实现一笔 / 守卫+测试一笔 / 文档一笔）。

## 3. 验收门（自检全绿才算完成；看护会亲自复跑，且**不采信自述**）

1. `:core:data:testReleaseUnitTest` 定向全绿（`RoomMigrationTest` / `MigrationChainGuardTest` /
   `RoomMigrationLegacyTest` / `RoomMigrationRecoveryTest` / `RoomMigrationV50To51Test` /
   `RoomMigrationV51To52Test`）；
2. **六模块组合门**：`testReleaseUnitTest --max-workers=1` + `detekt` + `compileReleaseKotlin` +
   `lintRelease`（须 `export JAVA_HOME=C:/Users/cp050/.jdks/jdk-21.0.12.1+1`；
   `-Dgamecore.jni.path` 用 Windows 原生路径）；判绿看 **executed 计数 + XML 时间戳**，
   不看 `BUILD SUCCESSFUL`；
3. **桌面 ctest 照跑**（预期 `ninja: no work to do.` + 基线 1558，纯 Kotlin 批免桥重建）；
4. **存档回归逐字段 diff 实证**（测试输出贴进完成报告）；
5. **文档三件套**：方案 §7.2 追加 B19 段、`CHANGELOG.md` 4.01.15 段内追加、
   `dispatch-ledger.md` B19 行状态更新。

## 4. 完成报告格式（最终消息必须含）

- 批次号 + 范围（v51→v52，删 2 列）+ 提交号列表（逐子项）；
- 死判复核与旧档数据窗口勘察结论（本文件 §0，实测取证）；
- 迁移实现说明（重建表模式、索引重建、幂等、回滚方案）；
- 逐门实测证据（命令 + 数字 + XML 时间戳）；
- 存档回归逐字段等价断言实跑输出；
- 改动文件清单；诚实登记未达标/残余项。

---

## 5. 实施记录（2026-09-21 落地，施工实况）

### 5.1 改动面（实际）

| 文件 | 改动 |
|---|---|
| `core/domain/.../GameData.kt` | 删 `battleTeam: BattleTeam?`（原 `:491-494`）与 `aiBattleTeams: List<AIBattleTeam>`（原 `:519-522`）两字段及其注解；`organization` 聚合属性去掉 `aiBattleTeams` 实参；两处 tombstone 注释登记判归 |
| `core/domain/.../SectOrganizationState.kt` | 删 `aiBattleTeams` DTO 字段（生产零消费者，保留即永久空字段） |
| `core/data/.../GameDatabase.kt` | `DATABASE_VERSION` **51 → 52**；`ALL_MIGRATIONS` 末尾追加 `MIGRATION_51_52`；`@Database` 上方追加 v52 版本说明 |
| `core/data/.../GameDatabaseMigrationsV52.kt`（新增） | `MIGRATION_51_52`：`rebuildTableDroppingColumns(game_data, [battleTeam, aiBattleTeams], pk=[id,slot_id], 5 索引)` |
| `core/data/.../GameDatabaseMigrationSupport.kt` | 新增 `internal fun rebuildTableDroppingColumns(...)`——**删列迁移的唯一实现**（PRAGMA 驱动多列通用 + 幂等 + 索引重建） |
| `core/data/.../GameDatabaseMigrationsV50.kt` | 私有单列实现**收敛为对该通用实现的委托**（纯抽取，语义逐字等价；由既有 `MIGRATION_49_50` 用例覆盖） |
| `core/data/.../CollectionConverters.kt` | 删 `fromAIBattleTeamList`/`toAIBattleTeamList`（仅服务被删列）+ 孤儿 import `AIBattleTeam` |
| `core/engine/.../GameDataTransientFace.kt` | KDoc 口径同步（差集面 9 → **7** 字段，自动收缩） |
| `core/domain/src/test/.../StateEntitiesTest.kt` | 删 DTO 字段断言 |
| `core/data/schemas/.../52.json`（新增，KSP 生成） | game_data **141 → 139** 列；`identityHash` `d61cc2cd…` → `f1b63926…` |
| `core/data/src/test/.../RoomMigrationV51To52Test.kt`（新增） | 5 例守卫（真实 Room 校验 / 删列 + 逐字段等价 / V39 全链 / 幂等 / schema 静态防回流） |

### 5.2 施工中发现（口径/工具坑，均已处置并登记）

1. **`SELECT *` 在 Robolectric 下会拿到过期列元数据**（本批实测）：
   重建表（RENAME + CREATE + DROP）后对同一 SQL 串 `SELECT * FROM game_data`，
   legacy cursor **缓存了列清单** ⇒ 迁移后读取报
   `IndexOutOfBoundsException: Index 139 out of bounds for length 139`，
   且"被删列集"算出**空集**（两种假象）。
   **处置**：测试侧 `readAllRows` 改为**显式按 `PRAGMA table_info` 列清单构造 SELECT**。
   （教训：删列迁移的等价断言**不要用 `SELECT *`**。）
2. **`ALL_MIGRATIONS` 与 `DATABASE_VERSION` 必须同笔改**（本批实测）：
   只递增版本未登记迁移 ⇒ **12 个既有"真实 Room 校验"用例全红**
   （`A migration from 51 to 52 was required but not found`）。
   本批初版即踩此坑，修后全绿——正是 `MigrationChainGuardTest` 想拦但**拦不住**的那类
   （该守卫只断言 `ALL_MIGRATIONS` 自身连续，不比对 `DATABASE_VERSION` 与实跑链尾；
   本批新增的 12 例红反而是更硬的实证防线）。
3. **并行会话会杀守护进程**：组合门跑到 1m34s 时被外部 `gradlew --stop`
   （另一会话的验收 SOP）中止，报 `Gradle build daemon has been stopped: stop command received`。
   ⇒ **环境事实登记**：本机存在并行会话时，组合门须在对方空闲窗口串行跑。

### 5.3 未做/残余（诚实登记）

- **`AIBattleTeam` 模型类型本体保留**：删列后该 `@Serializable` 数据类仅剩自身定义
  （零引用）。删除它属"领域模型清理"，超出"Room 死列清理"范围，且触碰
  proto/序列化守卫面（`ProtoNumberCoverageTest` 等按类枚举）⇒ **本批不动，登记为后续**。
- **存档系统其余问题零处理**：`docs/save-system-audit-2026-09-21.md` §16 优先级建议
  1–10（归档删弟子主表 / UI 谎报保存成功 / heavy 先删后写 / 假 `.bak` /
  `@ProtoNumber(162)` 冲突 / 触发模型 / 删档残留 / 空函数恢复 / boot 覆盖存档值 /
  死代码清理）**均未处理**，本批只做死列清理。
- **真机侧未验证**：无设备/无截图回归基建，本批为纯 schema 迁移，**以 Robolectric
  真实 Room 打开校验 + 存档回归逐字段等价**为证；真机升级路径（旧档实机迁移）未实测。

