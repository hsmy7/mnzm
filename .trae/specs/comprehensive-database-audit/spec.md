# 数据库全面审计规范

## 1. 审计目标

对 XianxiaSectNative 项目的数据库进行全面深入检查，识别以下问题：
- 旧数据/废弃字段
- 旧版本遗留问题
- 未被使用的数据
- 迁移不一致问题
- 数据完整性问题
- 静态数据与动态数据的一致性

## 2. 数据库架构概览

### 2.1 Room 数据库 (GameDatabase)
- **当前版本**: 34
- **实体数量**: 16个
- **实体列表**:
  1. GameData - 主游戏状态
  2. Disciple - 弟子数据
  3. Equipment - 装备
  4. Manual - 功法
  5. Pill - 丹药
  6. Material - 材料
  7. Seed - 种子
  8. Herb - 灵草
  9. ExplorationTeam - 探索队伍
  10. BuildingSlot - 建筑槽位
  11. GameEvent - 游戏事件
  12. Dungeon - 秘境
  13. Recipe - 配方
  14. BattleLog - 战斗日志
  15. WarTeam - 战争队伍
  16. ForgeSlot - 锻造槽位

### 2.2 静态数据数据库
- TalentDatabase - 天赋定义
- ItemDatabase - 物品模板（丹药、材料）
- PillRecipeDatabase - 炼丹配方
- ManualDatabase - 功法模板
- EquipmentDatabase - 装备模板

### 2.3 迁移历史
从版本5到版本34，共29个迁移版本

## 3. 审计范围

### 3.1 实体字段审计
检查每个实体的所有字段：
- 是否有废弃字段（已从代码移除但数据库保留）
- 是否有未使用的字段（定义但从未读取/写入）
- 字段类型是否与实际使用一致

### 3.2 迁移完整性审计
- 检查迁移链是否完整（5→6→...→34）
- 检查迁移SQL是否正确执行
- 检查是否有遗漏的迁移步骤

### 3.3 DAO使用审计
- 检查DAO方法是否都被使用
- 检查是否有未实现的DAO方法
- 检查查询效率问题

### 3.4 静态数据审计
- 检查静态数据定义是否被使用
- 检查静态数据与动态数据的关联完整性
- 检查是否有重复或冗余定义

### 3.5 TypeConverter审计
- 检查所有TypeConverter是否被正确使用
- 检查JSON序列化/反序列化的一致性
- 检查是否有数据丢失风险

## 4. 已知问题追踪

### 4.1 迁移历史中的字段变更
根据DatabaseMigrations.kt分析：
- `spiritRootQuality` 字段已被移除
- `enhanceLevel` 从Equipment移除
- 多个新字段添加：intelligence, charm, loyalty, potential, comprehension等

### 4.2 嵌套数据结构
GameData包含多个嵌套数据类：
- SectResource
- MonthlySalary
- WorldMapSect
- CultivatorCave
- Alliance
这些通过TypeConverter以JSON形式存储

## 5. 审计方法

### 5.1 静态分析
- 代码扫描：搜索每个字段的读写位置
- 迁移对比：对比迁移SQL与当前实体定义
- DAO追踪：追踪每个DAO方法的调用链

### 5.2 动态分析
- 模拟数据流：追踪数据从创建到使用的完整路径
- 边界检查：检查数据边界条件处理

## 6. 预期输出

1. **问题清单**: 列出所有发现的问题
2. **风险评估**: 每个问题的严重程度
3. **修复建议**: 针对每个问题的解决方案
4. **优化建议**: 数据库优化建议

## 7. 优先级定义

- **P0 (严重)**: 数据丢失、崩溃、核心功能失效
- **P1 (高)**: 数据不一致、性能问题
- **P2 (中)**: 废弃字段、冗余代码
- **P3 (低)**: 代码风格、潜在风险
