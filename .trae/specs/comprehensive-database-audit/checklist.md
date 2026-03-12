# 数据库审计检查清单

## 一、实体字段检查

### GameData 实体
| 检查项 | 状态 | 问题 | 优先级 |
|--------|------|------|--------|
| sectName 字段使用 | ⬜ | | |
| spiritStones 字段使用 | ⬜ | | |
| spiritStonesPerMonth 字段使用 | ⬜ | | |
| reputation 字段使用 | ⬜ | | |
| month 字段使用 | ⬜ | | |
| year 字段使用 | ⬜ | | |
| monthlySalary 字段使用 | ⬜ | | |
| warTeams 字段使用 | ⬜ | | |
| worldMapSects 字段使用 | ⬜ | | |
| cultivatorCaves 字段使用 | ⬜ | | |
| alliances 字段使用 | ⬜ | | |
| unlockedRecipes 字段使用 | ⬜ | | |
| exploredDungeons 字段使用 | ⬜ | | |

### Disciple 实体
| 检查项 | 状态 | 问题 | 优先级 |
|--------|------|------|--------|
| id 字段使用 | ⬜ | | |
| name 字段使用 | ⬜ | | |
| realm 字段使用 | ⬜ | | |
| cultivation 字段使用 | ⬜ | | |
| maxCultivation 字段使用 | ⬜ | | |
| spiritRoot 字段使用 | ⬜ | | |
| talents 字段使用 | ⬜ | | |
| status 字段使用 | ⬜ | | |
| equipmentSlots 字段使用 | ⬜ | | |
| pillBonuses 字段使用 | ⬜ | | |
| personalityTraits 字段使用 | ⬜ | | |
| intelligence 字段使用 | ⬜ | | |
| charm 字段使用 | ⬜ | | |
| loyalty 字段使用 | ⬜ | | |
| potential 字段使用 | ⬜ | | |
| comprehension 字段使用 | ⬜ | | |
| luck 字段使用 | ⬜ | | |
| age 字段使用 | ⬜ | | |

### Equipment 实体
| 检查项 | 状态 | 问题 | 优先级 |
|--------|------|------|--------|
| id 字段使用 | ⬜ | | |
| name 字段使用 | ⬜ | | |
| type 字段使用 | ⬜ | | |
| rarity 字段使用 | ⬜ | | |
| level 字段使用 | ⬜ | | |
| attributes 字段使用 | ⬜ | | |
| ownerId 字段使用 | ⬜ | | |
| enhanceLevel 字段（已移除？） | ⬜ | | |

### Manual 实体
| 检查项 | 状态 | 问题 | 优先级 |
|--------|------|------|--------|
| id 字段使用 | ⬜ | | |
| name 字段使用 | ⬜ | | |
| type 字段使用 | ⬜ | | |
| rarity 字段使用 | ⬜ | | |
| level 字段使用 | ⬜ | | |
| effects 字段使用 | ⬜ | | |
| ownerId 字段使用 | ⬜ | | |

### Pill 实体
| 检查项 | 状态 | 问题 | 优先级 |
|--------|------|------|--------|
| id 字段使用 | ⬜ | | |
| name 字段使用 | ⬜ | | |
| type 字段使用 | ⬜ | | |
| rarity 字段使用 | ⬜ | | |
| effects 字段使用 | ⬜ | | |
| quantity 字段使用 | ⬜ | | |

### Material 实体
| 检查项 | 状态 | 问题 | 优先级 |
|--------|------|------|--------|
| id 字段使用 | ⬜ | | |
| name 字段使用 | ⬜ | | |
| type 字段使用 | ⬜ | | |
| rarity 字段使用 | ⬜ | | |
| quantity 字段使用 | ⬜ | | |

### Seed 实体
| 检查项 | 状态 | 问题 | 优先级 |
|--------|------|------|--------|
| id 字段使用 | ⬜ | | |
| name 字段使用 | ⬜ | | |
| rarity 字段使用 | ⬜ | | |
| growthTime 字段使用 | ⬜ | | |
| quantity 字段使用 | ⬜ | | |

### Herb 实体
| 检查项 | 状态 | 问题 | 优先级 |
|--------|------|------|--------|
| id 字段使用 | ⬜ | | |
| name 字段使用 | ⬜ | | |
| rarity 字段使用 | ⬜ | | |
| age 字段使用 | ⬜ | | |
| quantity 字段使用 | ⬜ | | |

## 二、迁移完整性检查

| 迁移版本 | 检查项 | 状态 | 问题 |
|----------|--------|------|------|
| 5→6 | 迁移SQL正确性 | ⬜ | |
| 6→7 | 迁移SQL正确性 | ⬜ | |
| 7→8 | 迁移SQL正确性 | ⬜ | |
| 8→9 | 迁移SQL正确性 | ⬜ | |
| 9→10 | 迁移SQL正确性 | ⬜ | |
| 10→11 | 迁移SQL正确性 | ⬜ | |
| 11→12 | 迁移SQL正确性 | ⬜ | |
| 12→13 | 迁移SQL正确性 | ⬜ | |
| 13→14 | 迁移SQL正确性 | ⬜ | |
| 14→15 | 迁移SQL正确性 | ⬜ | |
| 15→16 | 迁移SQL正确性 | ⬜ | |
| 16→17 | 迁移SQL正确性 | ⬜ | |
| 17→18 | 迁移SQL正确性 | ⬜ | |
| 18→19 | 迁移SQL正确性 | ⬜ | |
| 19→20 | 迁移SQL正确性 | ⬜ | |
| 20→21 | 迁移SQL正确性 | ⬜ | |
| 21→22 | 迁移SQL正确性 | ⬜ | |
| 22→23 | 迁移SQL正确性 | ⬜ | |
| 23→24 | 迁移SQL正确性 | ⬜ | |
| 24→25 | 迁移SQL正确性 | ⬜ | |
| 25→26 | 迁移SQL正确性 | ⬜ | |
| 26→27 | 迁移SQL正确性 | ⬜ | |
| 27→28 | 迁移SQL正确性 | ⬜ | |
| 28→29 | 迁移SQL正确性 | ⬜ | |
| 29→30 | 迁移SQL正确性 | ⬜ | |
| 30→31 | 迁移SQL正确性 | ⬜ | |
| 31→32 | 迁移SQL正确性 | ⬜ | |
| 32→33 | 迁移SQL正确性 | ⬜ | |
| 33→34 | 迁移SQL正确性 | ⬜ | |

## 三、DAO使用检查

| DAO | 方法 | 使用状态 | 调用位置 |
|-----|------|----------|----------|
| GameDataDao | getGameData | ⬜ | |
| GameDataDao | insertGameData | ⬜ | |
| GameDataDao | updateGameData | ⬜ | |
| GameDataDao | deleteGameData | ⬜ | |
| DiscipleDao | getAllDisciples | ⬜ | |
| DiscipleDao | getDiscipleById | ⬜ | |
| DiscipleDao | insertDisciple | ⬜ | |
| DiscipleDao | updateDisciple | ⬜ | |
| DiscipleDao | deleteDisciple | ⬜ | |
| EquipmentDao | getAllEquipment | ⬜ | |
| EquipmentDao | getEquipmentById | ⬜ | |
| EquipmentDao | getEquipmentByOwner | ⬜ | |
| EquipmentDao | insertEquipment | ⬜ | |
| EquipmentDao | updateEquipment | ⬜ | |
| EquipmentDao | deleteEquipment | ⬜ | |

## 四、静态数据检查

### TalentDatabase
| 天赋ID | 使用状态 | 引用位置 |
|--------|----------|----------|
| 所有正向天赋 | ⬜ | |
| 所有负向天赋 | ⬜ | |

### ItemDatabase
| 物品类型 | 使用状态 | 引用位置 |
|----------|----------|----------|
| 突破类丹药 | ⬜ | |
| 修炼类丹药 | ⬜ | |
| 战斗类丹药 | ⬜ | |
| 治疗类丹药 | ⬜ | |
| 材料模板 | ⬜ | |

### PillRecipeDatabase
| 配方ID | 使用状态 | 引用位置 |
|--------|----------|----------|
| 所有配方 | ⬜ | |

### ManualDatabase
| 功法ID | 使用状态 | 引用位置 |
|--------|----------|----------|
| 所有功法 | ⬜ | |

### EquipmentDatabase
| 装备ID | 使用状态 | 引用位置 |
|--------|----------|----------|
| 所有装备 | ⬜ | |

## 五、TypeConverter检查

| 转换器 | 使用状态 | 问题 |
|--------|----------|------|
| StringListConverter | ⬜ | |
| IntListConverter | ⬜ | |
| EquipmentSlotListConverter | ⬜ | |
| PillBonusListConverter | ⬜ | |
| PersonalityTraitsConverter | ⬜ | |
| MonthlySalaryConverter | ⬜ | |
| WarTeamListConverter | ⬜ | |
| WorldMapSectListConverter | ⬜ | |
| CultivatorCaveListConverter | ⬜ | |
| AllianceListConverter | ⬜ | |
| StringSetConverter | ⬜ | |

## 六、问题汇总

| 序号 | 问题描述 | 类型 | 优先级 | 状态 |
|------|----------|------|--------|------|
| 1 | | | | ⬜ |
| 2 | | | | ⬜ |
| 3 | | | | ⬜ |

**图例说明**：
- ⬜ 待检查
- ✅ 无问题
- ⚠️ 有问题需关注
- ❌ 有严重问题
