# 规则：数据库迁移与旧存档兼容

执行任何涉及数据模型变更的任务时（新增字段、修改 Entity、调整表结构等），**必须在设计阶段就考虑以下问题**：

1. **数据库版本升级**：每次 schema 变更必须编写对应的 `MIGRATION_X_Y`，在 `GameDatabase.kt` 中注册并递增 `version`
2. **旧存档兼容**：新版本必须能正确读取旧版本数据库中的所有数据，不能依赖 `fallbackToDestructiveMigration`
3. **Schema 校验**：迁移 SQL 必须与 Room `@Entity` 定义完全一致（包括 FK 约束、索引、列类型），否则 Room 会抛出 `IllegalStateException`
4. **测试路径**：确认从近3个大版本升级时，迁移能成功执行且数据完整
5. **决策优先**：能用 `ALTER TABLE ADD COLUMN` 只加列解决的不拆表；拆表重构必须完成全链路（Entity → Migration → DAO → DI → StorageEngine 读写 → delete 清理）

**反例**：MIGRATION_15_16 创建了子表但应用代码从未使用，且 `game_data_core` 遗漏了 FK 约束导致 Room schema 校验失败。
