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
