# ADR: SQLDelight 与 Room 选型评估（G4 闭环）

> 状态：✅ 已评估 | 日期：2026-08 | 关联：docs/platform-abilities.md G4

## 背景

`docs/platform-abilities.md` G4 登记待办：Room 无接口层，新数据层组件优先跨平台选型
（SQLDelight 评估中），存量不动。iOS 迁移视角下 Room 为 Android 独占（KMP 支持仅实验
性），SQLDelight 官方支持 KMP。

## 现状事实（2026-08 实测）

| 维度 | Room 现状 |
|------|----------|
| 数据库 | `GameDatabase`（core:data，DATABASE_VERSION 已迭代至 4x，Migration 链完整） |
| DAO | 18 个领域 DAO 文件 + GameStateDaos 聚合 |
| 实体 | core/domain 22 个实体类直接携带 Room 注解（@Entity/@Embedded/@ForeignKey） |
| 特性依赖 | withTransaction 单事务写入、复合主键 slot_id、safeDropColumns 迁移工具 |

## 评估结论

**决策：新数据层组件优先 SQLDelight，存量 Room 不动。**

理由：

1. **存量迁移成本不成比例**——18 DAO + 22 实体 + 40+ Migration 全量移植 SQLDelight 的
   收益（iOS 复用）在当前无 iOS 开发计划下为零，而迁移风险（存档损坏）为最高等级。
   项目规则 `rules/database-migration.md` 明确"存档损坏是最严重事故"。
2. **SQLDelight 定位准确**——生成类型安全 API + 编译期 SQL 校验 + KMP 官方支持，
   与 Room 的生成代码模式一致，新增模块（如未来 iOS 共享的数据层组件）用它无额外
   心智成本。
3. **Room 本身已收敛**——core/domain 的 Room 注解使用范围由 `DomainDependencyTest`
   守卫（仅 @Entity/@ColumnInfo/@PrimaryKey/@Embedded/@Ignore/@Index/@ForeignKey，
   不得引入运行时依赖），未来迁移点清晰。

## 迁移启动判据

**iOS 迁移立项**（平台扩张触发）时：

1. 数据层拆分为"跨平台核心（SQLDelight）+ Android 存量（Room 桥接）"双轨，
   新写入路径只走 SQLDelight
2. 桥接期用 `GameStateRepository` 门面隔离（已有），DAO 层替换对外不可见
3. 迁移顺序：先移植 GameData 主表 → 再分领域批次 → 最后删 Room 运行时依赖

## 后果

- 正向：新增数据层组件零 Android 依赖，iOS 复用面扩大
- 负向：双轨并存期内维护成本略增（受桥接门面限制，可控）
