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
   - 删除列（SQLite 不支持 DROP COLUMN，需重建表）：
     ```sql
     ALTER TABLE table_name RENAME TO table_name_old;
     CREATE TABLE table_name (... 不含被删列);
     INSERT INTO table_name SELECT col1, col2, ... FROM table_name_old;
     DROP TABLE table_name_old;
     ```
   - 或更简单：**保留旧列不删除**，在 Entity 中使用 `@Ignore` 标记新字段

3. **@Ignore 的正确用法**：
   - 添加新字段 + `@Ignore` → 无需 Migration（Room 不创建列）
   - 将旧字段标记为 `@Ignore` → **需要 Migration 删除列**，或保留旧字段在 Entity 中
   - **从 Entity 移除字段（不管是否 @Ignore）→ 必须 Migration 处理旧列**

4. **fallbackToDestructiveMigration**：
   - 当前覆盖：ALL versions (1-19)，当迁移链断裂时 Room 重建数据库
   - 此机制**仅作安全网**，不应依赖它处理日常变更
   - 每次 schema 变更仍需编写显式 Migration

5. **测试路径**：确认从近3个大版本升级时，迁移能成功执行且数据完整

## 反面案例（已发生多次）

| 版本 | 问题 | 影响 |
|------|------|------|
| v19→v20 (本次) | battleTeam 列从 Entity 移除但 Migration 未处理 | 旧存档全部为空 |
| v17→v18 (MIGRATION_15_16) | game_data_core 遗漏 FK 约束 | Room schema 校验失败 |
| v18→v19 (MIGRATION_18_19) | pills 表 miningAdd 列遗漏 | 存档全部为空 |

**每次都是同样的错误：Entity 改了但 Migration 没跟上。**
