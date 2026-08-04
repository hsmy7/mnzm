# 规则：数据库迁移与旧存档兼容

## 核心原则

**任何 GameData Entity 字段变更（新增/删除/重命名/修改类型/添加 @Ignore）都会触发 Room schema 变更。如果不编写对应的 Migration，旧数据库将无法打开，表现为：存档列表全部为空、新建游戏后不运行。**

## 必须遵守的规则

1. **数据库版本升级**：每次 schema 变更必须：
   - 递增 `@Database(version = N)` 
   - 编写对应的 `MIGRATION_(N-1)_N`
   - 在 `build()` 链中注册迁移
   - **这是强制要求，不可跳过**

2. **列变更的迁移 SQL**：
   - 添加列：`ALTER TABLE table_name ADD COLUMN col_name TYPE DEFAULT val`
   - 删除列：**使用 `db.safeDropColumns("table", "col1", "col2")`**（定义在 `GameDatabase.kt`），禁止直接写 `ALTER TABLE DROP COLUMN`（需要 SQLite 3.35.0+，所有 Android 版本均不保证支持）。内部通过 PRAGMA 表重建实现：
     ```kotlin
     db.safeDropColumns("game_data", "oldColumn1", "oldColumn2")
     ```
   - 或更简单：**保留旧列不删除**，在 Entity 中使用 `@Ignore` 标记新字段

3. **@Ignore 的正确用法**：
   - 添加新字段 + `@Ignore` → 无需 Migration（Room 不创建列）
   - 将旧字段标记为 `@Ignore` → **需要 Migration 删除列**，或保留旧字段在 Entity 中
   - **从 Entity 移除字段（不管是否 @Ignore）→ 必须 Migration 处理旧列**

4. **版本号唯一来源**：`@Database(version = N)` 必须引用 `GameDatabaseConfig.DATABASE_VERSION` 常量（2026-08-04 起），禁止在 `backupDatabaseForMigration` 等位置硬编码版本号——历史教训：硬编码 38 与 v39 脱节导致 v38 用户升级 v39 不触发迁移前备份。升级数据库版本时同步递增此常量

5. **fallbackToDestructiveMigration**：
   - 当前实现：`fallbackToDestructiveMigrationFrom(1)`——v1 及更低版本毁灭重建；**v2+ 禁止毁灭回退**（迁移链断裂时崩溃而非删数据，由迁移前备份恢复兜底）
   - 此机制**仅作安全网**，不应依赖它处理日常变更
   - 每次 schema 变更仍需编写显式 Migration

6. **迁移崩溃自恢复（2026-08-04 起三层防御）**：
   - **迁移前备份**：`GameDatabase.create()` 在 Room 构建前自动执行 `backupDatabaseForMigration`（WAL checkpoint 后文件复制为 `xianxia_sect.db.pre_migrate_backup`）
   - **启动验证恢复**：`restoreFromBackupIfNeeded` 在 Room 构建前检查——当前库打不开 / 无数据 / **迁移待完成**（user_version 低于 `DATABASE_VERSION` 且备份同版本）时用备份覆盖恢复；恢复前先清理残留 `-wal/-shm`
   - **迁移链完整性守卫**：`RoomMigrationTest` 的"full migration from v2 to v39"全链测试 + 迁移注册守卫——任何一步缺失即测试失败，防止跨版本升级崩溃复发

7. **测试路径**：确认从近3个大版本升级时，迁移能成功执行且数据完整

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

1. **新表同样走完整迁移链**：`@Database(version)` 递增 + `MIGRATION_N_M` + `build()` 注册 + **schema JSON 提交**（`android/app/schemas/`，参照 v26→v27 `DiscipleCompact` 完整案例）。禁止"先不加迁移，等上线后再补"——迁移缺失的存档损坏是不可逆的
2. **新列一律带 DEFAULT 零值**（`DEFAULT 0` / `DEFAULT ''` / `DEFAULT 1` 布尔）：保证迁移幂等与旧行兼容，参照 `columnExists()` 幂等辅助模式（MIGRATION_1_26 先例）
3. **存储选型标准**（防止每旬热点膨胀）：
   - **热路径高频更新**（每旬结算读写）→ EntityStore 列式（Component Table 模式，参照 `DiscipleTables`）
   - **低频独立生命周期**（一次性进度/活动状态）→ Row Entity（`DiscipleCompact` 模式）
   - 禁止新玩法默认堆 Row 表导致每旬热点膨胀（参照架构文档"每旬热点削减"原则）
4. **多张新表时评估合并迁移**：新表数量多或要跨大量版本时使用合并迁移（`MIGRATION_1_26` 模式），控制冷启动开销
5. **新玩法实体变更后必须同步**：SaveValidator 规则注册（`SaveValidationRuleRegistry.registerDefaults()` 加一行）——新实体的完整性校验规则与建表同步落地

## ProtoBuf 序列化字段预留（2026-08-04 起）

云存档/备份使用 ProtoBuf 序列化（`SaveData`），字段编号管理规则：

1. **新字段从预留段编号**：预留段（如 1000+）优先，避免与历史字段冲突；编号一旦发布**禁止复用**（删除的字段用 `reserved` 声明，防止旧存档字段编号错位）
2. **非零默认值必须 `@EncodeDefault(EncodeDefault.Mode.ALWAYS)`**：字段默认值不是该类型零值（`0`/`""`/`false`/`emptyList()`）时，`encodeDefaults = false` 下该字段不会被写入二进制，导致存档数据丢失（CLAUDE.md 13.3 已有条目，此处为设计期用法）
3. **ProtoBuf 仅 `List`，禁止 `Set`/`Map`**（CLAUDE.md 7.3）：需要去重语义在业务层 `.toSet()` 转换，忽略会导致序列化静默失败、存档变空
4. **新增持久化字段先评估双路径**：Room 列 + ProtoBuf 字段必须同步变更（Room 表与 `SaveData` 是两套存储，遗漏任一侧会导致云存档与本地存档数据不一致——参照 2026-08-01 堆叠字段 `@Transient` 导致备份/云恢复清空仓库的教训）

## 经济/货币字段变更流程（2026-08-04 起）

涉及货币（灵石及未来新货币）/经济资源的字段变更：

1. **货币字段变更同走完整 Migration**（本文件全部规则适用），禁止"先改代码后补迁移"
2. **新货币上线前必须过 `rules/economy-design.md` 审计**：持有上限 + 源汇闭环 + 通胀防控（本规则只约束存储层，经济设计审计见 economy-design.md）
3. **发放/消耗入口必须注册进"来源字典"**：新增灵石/货币发放或消耗代码必须包裹 `withTrackingSource("来源名")`（来源名加入 `OverflowMailSender.SOURCE_DISPLAY_NAMES` 映射），确保年度报告（`YearlyReport`）与经济基线表（`docs/knowledge-base.md` 扩展性现状盘点）可审计
4. **溢出语义类别判定**：凭据类（可重试领取）包 `withOverflowMailSuppressed`，发放类（自动入库）自动转邮件——选错类别会导致货币重复发放或丢失（CLAUDE.md 13.3 已有条目，此处为设计期流程）
