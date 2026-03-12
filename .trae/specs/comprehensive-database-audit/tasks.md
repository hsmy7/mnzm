# 数据库审计任务清单

## 阶段一：实体字段审计

### 任务 1.1: GameData 实体审计
- [ ] 检查所有字段是否被使用
- [ ] 检查嵌套数据类（SectResource, MonthlySalary等）的完整性
- [ ] 验证TypeConverter序列化字段

### 任务 1.2: Disciple 实体审计
- [ ] 检查所有属性字段的使用情况
- [ ] 验证equipmentSlots字段的序列化
- [ ] 检查pillBonuses字段的使用
- [ ] 验证personalityTraits字段

### 任务 1.3: 物品实体审计（Equipment, Manual, Pill, Material, Seed, Herb）
- [ ] 检查Equipment所有字段
- [ ] 检查Manual所有字段
- [ ] 检查Pill所有字段
- [ ] 检查Material所有字段
- [ ] 检查Seed所有字段
- [ ] 检查Herb所有字段

### 任务 1.4: 其他实体审计
- [ ] ExplorationTeam实体审计
- [ ] BuildingSlot实体审计
- [ ] GameEvent实体审计
- [ ] Dungeon实体审计
- [ ] Recipe实体审计
- [ ] BattleLog实体审计
- [ ] WarTeam实体审计
- [ ] ForgeSlot实体审计

## 阶段二：迁移完整性审计

### 任务 2.1: 迁移链完整性检查
- [ ] 验证版本5到34的迁移链连续性
- [ ] 检查每个迁移版本的SQL语句
- [ ] 识别迁移中移除但代码仍引用的字段
- [ ] 识别迁移中添加但代码未使用的字段

### 任务 2.2: 迁移SQL验证
- [ ] 检查ALTER TABLE语句的正确性
- [ ] 检查数据迁移逻辑
- [ ] 验证默认值设置

## 阶段三：DAO使用审计

### 任务 3.1: DAO方法使用检查
- [ ] GameDataDao所有方法使用情况
- [ ] DiscipleDao所有方法使用情况
- [ ] EquipmentDao所有方法使用情况
- [ ] ManualDao所有方法使用情况
- [ ] PillDao所有方法使用情况
- [ ] MaterialDao所有方法使用情况
- [ ] SeedDao所有方法使用情况
- [ ] HerbDao所有方法使用情况
- [ ] ExplorationTeamDao所有方法使用情况
- [ ] BuildingSlotDao所有方法使用情况
- [ ] GameEventDao所有方法使用情况
- [ ] DungeonDao所有方法使用情况
- [ ] RecipeDao所有方法使用情况
- [ ] BattleLogDao所有方法使用情况
- [ ] WarTeamDao所有方法使用情况
- [ ] ForgeSlotDao所有方法使用情况

## 阶段四：静态数据审计

### 任务 4.1: TalentDatabase审计
- [ ] 检查所有天赋定义是否被使用
- [ ] 验证天赋ID的唯一性
- [ ] 检查天赋效果配置

### 任务 4.2: ItemDatabase审计
- [ ] 检查丹药模板使用情况
- [ ] 检查材料模板使用情况
- [ ] 验证物品ID与动态数据的关联

### 任务 4.3: PillRecipeDatabase审计
- [ ] 检查配方使用情况
- [ ] 验证配方材料关联

### 任务 4.4: ManualDatabase审计
- [ ] 检查功法模板使用情况
- [ ] 验证功法属性配置

### 任务 4.5: EquipmentDatabase审计
- [ ] 检查装备模板使用情况
- [ ] 验证装备属性配置

## 阶段五：TypeConverter审计

### 任务 5.1: ModelConverters检查
- [ ] 检查所有转换器是否被使用
- [ ] 验证JSON序列化/反序列化一致性
- [ ] 检查空值处理

## 阶段六：问题汇总与报告

### 任务 6.1: 问题分类
- [ ] 按优先级分类所有发现的问题
- [ ] 标注问题影响范围

### 任务 6.2: 生成审计报告
- [ ] 编写问题清单
- [ ] 提供修复建议
- [ ] 提供优化建议
