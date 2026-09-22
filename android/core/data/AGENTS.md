# AGENTS.md — 数据层（:core:data）

> 本文件是 `android/core/data/` 的追加规范。**通用规范见仓库根 `AGENTS.md`。**

## 改 `@Entity` 之前必读

**`rules/database-migration.md` 是「存档损坏头号根因」的防御规范 —— 改任何 Entity / 字段 / 表结构之前必须先读它。**

铁律速记：

- 任何 Entity 变更 → 递增 `@Database(version)` + 编写 `MIGRATION_N_M` + 注册到 `build()`；
  版本号唯一来源是 `GameDatabaseConfig.DATABASE_VERSION`，**禁止硬编码**
- **禁止 `ALTER TABLE DROP COLUMN`**（SQLite 3.35.0 才支持）→ 用 `db.safeDropColumns()` 或保留旧列 + `@Ignore`
- 拿不准时保留旧字段 + 新字段，**永远不要删列**
- 每条 Migration 必须有集成测试（旧版本插入种子数据 → 运行迁移 → 验证数据完整性）
- 迁移崩溃三层防御：迁移前备份 / 启动验证恢复 / 迁移链完整性守卫（`RoomMigrationTest` 全链测试）

## ProtoBuf 序列化

- **仅 `List`，禁止 `Set` / `Map`** —— 忽略会导致序列化静默失败、**存档变空**（需去重语义在业务层 `.toSet()` 转换）
- 字段默认值**不是**该类型零值（`0` / `""` / `false` / `emptyList()`）时，**必须标注
  `@EncodeDefault(EncodeDefault.Mode.ALWAYS)`** —— 否则 `encodeDefaults = false` 下该字段不写入二进制，数据丢失
- 字段编号禁复用，退役字段用 `reserved`
- Room 列与 ProtoBuf 字段必须**同步变更**

## 存档

- **存档为纯手动** —— 禁止重新实现自动保存，禁止 `autoSave*` 命名
- 槽位隔离：`(id, slot_id)` 复合主键 + `resetForSlot` + 强制 slotId 赋值
- 新增 `SaveValidator` 规则：`registerDefaults()` 加一行即注册（规则按 `order` 排序）
- 云存档读档管线三步：版本迁移 → 完整性校验 → 堆叠重建

## 物品与经济的持久化入口

- 发放物品**必须走 `InventorySystem.addXxx` 统一入口**（`StackableItemStore` 自动合并），
  禁止手写 `find`+追加 / `coerceAtMost` 截断 / 手写 `StackableItemStore(` —— 守卫 `InventoryAddPathGuardTest` 会拦截
- 发放须判定**溢出语义类别**：凭据类（可重试领取）包 `withOverflowMailSuppressed`；
  发放类（自动入库）不包（溢出转邮件）。详见 `rules/economy-design.md`

新增持久化玩法系统（建新表）的完整规范 —— 存储选型（每旬高频更新走 EntityStore 列式，禁默认堆 Row 表）、
schema JSON 必须提交、新列一律带 DEFAULT 零值、多新表评估合并迁移 —— 见 `rules/database-migration.md`。
