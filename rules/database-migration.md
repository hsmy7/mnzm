# 规则：数据库迁移与旧存档兼容

## 核心原则

**任何 GameData Entity 字段变更（新增/删除/重命名/修改类型/添加 @Ignore）都会触发 Room schema 变更。**

**测试期（当前）**：schema 变更走 `fallbackToDestructiveMigration(dropAllTables = true)` **全量重建**——同步义务只有两处（版本常量 + 实体基线），**不写迁移**；老档被重建清空属预期（测试期数据可弃）。

🔴 **正式上线前必须切回完整迁移纪律**：写 `MIGRATION_(N-1)_N` 保护老库数据。**未切回就上线 = 一次 schema 变更静默清空全部真实玩家档。**

## 必须遵守的规则

1. **数据库版本升级**：每次 schema 变更必须：
   - 递增 `@Database(version = N)`（唯一来源 `GameDatabaseConfig.DATABASE_VERSION`）
   - 更新 `MigrationRequiredGuardTest.BASELINE_ENTITIES` 实体基线
   - **测试期同 commit 两处即可，不写迁移**（`DeadCompatRemovalGuardTest` 禁 `MIGRATION_N_M` 常量回流）
   - 🔴 **上线后追加两处**：编写 `MIGRATION_(N-1)_N` + 在 `build()` 链中注册

2. **列变更（分时态）**：
   - **测试期（当前）**：可直接在 Entity 增删字段——老库由 destructive 全量重建，无需迁移 SQL
   - **上线后**：添加列用 `ALTER TABLE table_name ADD COLUMN col_name TYPE DEFAULT val`；删除列**禁止直接写 `ALTER TABLE DROP COLUMN`**（需要 SQLite 3.35.0+，所有 Android 版本均不保证支持），两种合法手段：
     - **保留旧列不删除**，在 Entity 中使用 `@Ignore` 标记字段（优先）
     - 确需删列时自写 **create-copy-drop-rename 重建**（`PRAGMA table_info` 读列定义 → 剔除待删列建新表 → `INSERT SELECT` 复制 → 删旧表 → 改名 → 重建索引）

3. **@Ignore 的正确用法**：
   - 添加新字段 + `@Ignore` → 无需 Migration（Room 不创建列）
   - **测试期**：`@Ignore` 与直接在 Entity 删字段都不需要迁移（老库整体重建）
   - **上线后**：将旧字段标记为 `@Ignore` 或从 Entity 移除 → **必须 Migration 处理旧列**（保留旧列，或 create-copy-drop-rename 重建）

4. **版本号唯一来源**：`@Database(version = N)` 必须引用 `GameDatabaseConfig.DATABASE_VERSION` 常量，禁止在任何位置硬编码版本号。升级数据库版本时同步递增此常量，并更新 `MigrationRequiredGuardTest.BASELINE_ENTITIES` 实体基线。**测试期同 commit 两处**（版本常量 / 基线清单）；**上线后三处**（追加 `MIGRATION_(N-1)_N`）

5. **destructive 重建语义（SS0 起）**：
   - 当前实现：`fallbackToDestructiveMigration(dropAllTables = true)`——任何缺迁移路径（含降级）一律毁灭重建（dropAllTables 连 Room schema 外历史残留表一并清）
   - 迁移的职责是**保护老库数据不被重建**。**测试期**：无迁移，老档被重建属预期（数据可弃）；**上线后**：忘写迁移 = 真实玩家档被静默清空——由 `MigrationRequiredGuardTest` 实体基线守卫 + 迁移链完整性守卫在 CI 面拦截
   - 重建前的抢救面 = 启动前快照（`snapshotDatabaseBeforeUpgrade`，版本落后即落 `{db}.pre_migrate_backup.v{N}`）

6. **数据库自恢复（当前三层防御）**：
   - **启动前快照**：`GameDatabase.create()` 在打开前自动执行 `snapshotDatabaseBeforeUpgrade`——版本落后即 WAL checkpoint 后复制为 `{db}.pre_migrate_backup.v{N}`（destructive 重建前唯一的抢救副本）
   - **启动验证恢复**：`restoreFromBackupIfNeeded` 在 Room 构建前检查——当前库打不开 / 无数据 / 版本升级待完成时用快照覆盖恢复；恢复前先清理残留 `-wal/-shm`，恢复-重建死循环由 `.restore_attempted` marker 防护
   - **纪律守卫**：`MigrationRequiredGuardTest` 实体清单基线——`@Database` 实体变更而未同批递增版本并更新基线即 CI 红；`DeadCompatRemovalGuardTest` 防旧兼容符号与低于当期版本的孤儿 schema 回流；行为面由 `DestructiveRebuildBaselineTest`（旧版本库在新版本打开 = 重建而非崩溃 + 重建前落快照）与 `DatabaseRecoveryTest`（`shouldRestoreFromBackup` 分支谓词 + 快照恢复端到端）锁定

7. **测试路径**：**测试期**——用真实旧版本库文件走 `GameDatabase.create` 全流程验证（参照 `DestructiveRebuildBaselineTest` 的造库-打开-断言结构）；**上线后**——每条 `MIGRATION_(N-1)_N` 必须验证旧数据完整迁移

## 反面案例（已发生多次）

| 版本 | 问题 | 影响 |
|------|------|------|
| v19→v20 (本次) | battleTeam 列从 Entity 移除但 Migration 未处理 | 旧存档全部为空 |
| v17→v18 (MIGRATION_15_16) | game_data_core 遗漏 FK 约束 | Room schema 校验失败 |
| v18→v19 (MIGRATION_18_19) | pills 表 miningAdd 列遗漏 | 存档全部为空 |

**每次都是同样的错误：Entity 改了但 Migration 没跟上。**

## v3.2.01 数据库变更记录

### v26 → v27

**变更内容：**
- 新增 `DiscipleCompact` Entity（`disciple_compact` 表，14 字段 + 2 索引）
- 合并 v1→v26 顺序迁移链为 `MIGRATION_1_26`（单一合并迁移），减少冷启动开销
- 新增 `MIGRATION_26_27`：创建 `disciple_compact` 表

**迁移 SQL（MIGRATION_26_27）：**
```sql
CREATE TABLE IF NOT EXISTS disciple_compact (
    id TEXT NOT NULL,
    slot_id INTEGER NOT NULL DEFAULT 0,
    name TEXT NOT NULL DEFAULT '',
    cultivation REAL NOT NULL DEFAULT 0.0,
    realm INTEGER NOT NULL DEFAULT 0,
    realmLayer INTEGER NOT NULL DEFAULT 0,
    lifespan INTEGER NOT NULL DEFAULT 0,
    maxLifespan INTEGER NOT NULL DEFAULT 0,
    isAlive INTEGER NOT NULL DEFAULT 1,
    spiritRoot INTEGER NOT NULL DEFAULT 0,
    combatPower INTEGER NOT NULL DEFAULT 0,
    cultivationSpeed REAL NOT NULL DEFAULT 1.0,
    cultivationSpeedBonus REAL NOT NULL DEFAULT 0.0,
    cultivationSpeedDuration INTEGER NOT NULL DEFAULT 0,
    status INTEGER NOT NULL DEFAULT 0,
    age INTEGER NOT NULL DEFAULT 0,
    PRIMARY KEY(id)
);
CREATE INDEX IF NOT EXISTS index_disciple_compact_slot_id ON disciple_compact(slot_id);
CREATE INDEX IF NOT EXISTS index_disciple_compact_slot_id_isAlive ON disciple_compact(slot_id, isAlive);
```

**MIGRATION_1_26（合并迁移）：**
- 用途：新安装 / 从极旧版本升级时跳过 24 次顺序迁移，直接执行合并 DDL
- 使用 `columnExists()` 辅助函数检查列是否已存在，实现幂等迁移
- 覆盖：v1→v26 所有 ALTER TABLE ADD COLUMN / CREATE TABLE 操作

**影响的文件：**
- `GameDatabase.kt`：`@Database(version = 27, entities = [...+ DiscipleCompact::class])`
- `DiscipleCompact.kt`：新增 Room Entity
- `Daos.kt`：新增 `DiscipleCompactDao`
- Schema JSON：`android/app/schemas/.../27.json`

---

## 新玩法系统建表规范（2026-08-04 起）

新增玩法系统（秘境/试炼/活动/排行榜等）需要持久化时，除遵守上述通用规则外，还必须：

1. **新表同样走版本与基线同步**：`@Database(version)` 递增 + 更新 `MigrationRequiredGuardTest` 实体基线（**测试期同 commit 两处，不写迁移**）。schema 导出目录（`android/{app,core/data}/schemas/`）由 `.gitignore` 管理**不入库**，历史基线已随 SS0 删档重置清除，`DeadCompatRemovalGuardTest` 防低于当期版本的孤儿回流。🔴 **正式上线前必须切回完整迁移纪律**（追加 `MIGRATION_(N-1)_N` + 注册）——缺迁移 = 真实玩家档被 destructive 重建，不可逆
2. **新列一律带 DEFAULT 零值**（`DEFAULT 0` / `DEFAULT ''` / `DEFAULT 1` 布尔）：保证迁移幂等与旧行兼容；迁移 SQL 自身的幂等写法参照 `DestructiveRebuildBaselineTest` 同批的造库-打开-断言结构与 `rules/database-migration.md` 规则 2 的 create-copy-drop-rename 模式
3. **存储选型标准**（防止每旬热点膨胀）：
   - **热路径高频更新**（每旬结算读写）→ EntityStore 列式（Component Table 模式，参照 `DiscipleTables`）
   - **低频独立生命周期**（一次性进度/活动状态）→ Row Entity（`DiscipleCompact` 模式）
   - 禁止新玩法默认堆 Row 表导致每旬热点膨胀（参照架构文档"每旬热点削减"原则）
4. **多张新表时评估合并迁移**：新表数量多或要跨大量版本时使用合并迁移（`MIGRATION_1_26` 模式），控制冷启动开销
5. **新玩法实体变更后必须同步**：SaveValidator 规则注册（`SaveValidationRuleRegistry.registerDefaults()` 加一行）——新实体的完整性校验规则与建表同步落地

## ProtoBuf 序列化字段预留（2026-08-04 起）

云存档/备份使用 ProtoBuf 序列化（`SaveData`），字段编号管理规则：

1. **新字段从预留段编号**：预留段（如 1000+）优先，避免与历史字段冲突；编号一旦发布**禁止复用**（删除的字段用 `reserved` 声明，防止旧存档字段编号错位）
2. **非零默认值必须 `@EncodeDefault(EncodeDefault.Mode.ALWAYS)`**：字段默认值不是该类型零值（`0`/`""`/`false`/`emptyList()`）时，`encodeDefaults = false` 下该字段不会被写入二进制，导致存档数据丢失（rules/pr-review-checklist.md 已有条目，此处为设计期用法）
3. **ProtoBuf 仅 `List`，禁止 `Set`/`Map`**（AGENTS.md 7.3）：需要去重语义在业务层 `.toSet()` 转换，忽略会导致序列化静默失败、存档变空
4. **新增持久化字段先评估双路径**：Room 列 + ProtoBuf 字段必须同步变更（Room 表与 `SaveData` 是两套存储，遗漏任一侧会导致云存档与本地存档数据不一致——参照 2026-08-01 堆叠字段 `@Transient` 导致备份/云恢复清空仓库的教训）

## 经济/货币字段变更流程（2026-08-04 起）

涉及货币（灵石及未来新货币）/经济资源的字段变更：

1. **货币字段变更同走完整 Migration**（本文件全部规则适用），禁止"先改代码后补迁移"
2. **新货币上线前必须过 `rules/economy-design.md` 审计**：持有上限 + 源汇闭环 + 通胀防控（本规则只约束存储层，经济设计审计见 economy-design.md）
3. **发放/消耗入口必须注册进"来源字典"**：新增灵石/货币发放或消耗代码必须包裹 `withTrackingSource("来源名")`（来源名加入 `OverflowMailSender.SOURCE_DISPLAY_NAMES` 映射），确保年度报告（`YearlyReport`）与经济基线表（`docs/knowledge-base.md` 扩展性现状盘点）可审计
4. **溢出语义类别判定**：凭据类（可重试领取）包 `withOverflowMailSuppressed`，发放类（自动入库）自动转邮件——选错类别会导致货币重复发放或丢失（rules/pr-review-checklist.md 已有条目，此处为设计期流程）

## 协议版本戳字段迁移判定（2026-09-15 起，WS-5b 地图冻结批新增）

> 背景：WS-5b 地图冻结引入首个"协议版本戳 + 大段数据"字段
> （`GameData.mapGenVersion` + `GameData.terrainTiles`）。此类字段横跨
> C++ 快照协议与 Kotlin Room/ProtoBuf 存档，迁移判定与纯 Room 列不同。

### 判定口径："存的地形恒优先"

1. 版本戳字段（`mapGenVersion`）记录**产生该数据的生成器版本**，不是
   "存档格式版本"（那是 Room `DATABASE_VERSION` 与 `saveVersion` 的职责）。
2. 数据段（`terrainTiles`）**非空即直接采用**——读档路径绝不因"生成器版本
   更新"重算已有段（跨版本冻结：老档老地图、新档新地图）。
3. 仅"无段"（版本戳 = 0 / 段为空）才按种子 + 当前生成器版本生成并回填
   （一次性；幂等）。生成器演进时递增 `GameConfig.SectMap.MAP_GEN_VERSION`
   ⇒ 无需发版协调，无需数据迁移。

### C++/Kotlin 字段同步清单（新增此类字段时逐项核对）

| 面 | 要求 |
|---|---|
| C++ `state/models.h` | GameData 增版本戳（int32，默认 0）+ 数据段（flat 内存表示）；协议键 = Kotlin 字段名 |
| C++ `src/json_codec.cpp` | 双向编解码；**导出键按"非空/非零才导出"**（与 kotlinx encodeDefaults=false 缺省语义对称，先例 aiSectDisciples）；导入宽松（缺键保持默认） |
| C++ `game_core.{h,cpp}` | 生成/回填入口落 `importStateInternal` **归一化族**（先于 `resetBaseline` ⇒ 生成段计入导入基线，镜像零载荷）；生成参数由 Kotlin 经 `GameCoreConfig` 传值（单一数据源不落 C++） |
| Kotlin `GameData.kt` | `@ProtoNumber`（取 1000+ 预留段）+ `@ColumnInfo` + `@SettlementStrategy(PRESERVE_OLD)`；**禁止 @Transient**（云存档链无 heavy_data 补偿，会丢数据） |
| Room | **测试期**：改实体 + 递增 `DATABASE_VERSION` + 更新实体基线（老库 destructive 重建；schema JSON 由 `.gitignore` 管理不入库）；**上线后**：`ALTER TABLE ADD COLUMN ... DEFAULT`（禁 DROP COLUMN）+ `MIGRATION_XX_XX` + 注册 + 集成测试（v-1 库 → 迁移 → 旧行默认值正确、既有数据零丢失） |
| 反向通道 | 新字段必须显式归类（`ReverseChannelPolicyGuardTest` 穷尽分类守卫）；一次性回填写者 ⇒ 登记**在册保留**（CLOSED 会触发 `detectClosedFieldWrites` 误报） |
| 对拍面 | `DiffSurfaceAssertion` 按需登记镜像生成字段豁免（两端各自同源生成 ⇒ 内容逐位相同）；`Diff*Test` 全绿为门禁 |
| 内存/协议结构 | 保持单一 flat 表示；压缩（RLE 等）仅可存在于存档编码层，不入模型/协议 |
