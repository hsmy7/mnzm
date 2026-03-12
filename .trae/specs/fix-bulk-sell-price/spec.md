# 修复一键出售价格计算问题 Spec

## Why
一键出售功能存在以下问题：
1. `calculateBulkSellValue` 函数缺少种子的计算逻辑
2. `ItemType` 枚举缺少 `SEED` 类型
3. 数据库中的 `price` 字段需要更新为新的价格配置
4. 商人价格需要有30%上下浮动

## 价格配置

### GameConfig.Rarity basePrice（装备/功法/丹药）
| 稀有度 | 名称 | basePrice |
|--------|------|-----------|
| 1 | 凡品 | 3000 |
| 2 | 灵品 | 60000 |
| 3 | 宝品 | 220000 |
| 4 | 玄品 | 800000 |
| 5 | 地品 | 4000000 |
| 6 | 天品 | 12000000 |

### 种子/材料/草药价格（低于 basePrice 的 40%）
| 稀有度 | 名称 | basePrice | 种子/材料/草药价格 |
|--------|------|-----------|-------------------|
| 1 | 凡品 | 3000 | 1200 |
| 2 | 灵品 | 60000 | 24000 |
| 3 | 宝品 | 220000 | 88000 |
| 4 | 玄品 | 800000 | 320000 |
| 5 | 地品 | 4000000 | 1600000 |
| 6 | 天品 | 12000000 | 4800000 |

## 价格计算规则

### 一键出售价格
- 出售价格 = 数据库价格 * 0.8（即原价的80%）

### 商人价格
- 商人价格 = 数据库价格 * (0.7 ~ 1.3) 随机浮动（即30%上下浮动）

## What Changes
- 更新 GameConfig.Rarity 中的 basePrice
- 在 `ItemType` 枚举中添加 `SEED("种子")` 类型
- 在 `calculateBulkSellValue` 函数中添加种子参数和计算逻辑
- 更新 HerbDatabase 中所有 Herb 和 Seed 的 price
- 更新 BeastMaterialDatabase 中所有 BeastMaterial 的 price
- 更新 ItemDatabase 中 MaterialTemplate 的 price
- 种子/材料/草药价格 = 对应稀有度 basePrice * 0.4
- 确保商人价格有30%上下浮动

## Impact
- Affected code: 
  - `GameConfig.kt` - Rarity 配置
  - `InventoryScreen.kt` - ItemType 枚举、calculateBulkSellValue 函数
  - `HerbDatabase.kt` - 所有 Herb 和 Seed 的 price
  - `BeastMaterialDatabase.kt` - 所有 BeastMaterial 的 price
  - `ItemDatabase.kt` - MaterialTemplate 的 price
  - 商人相关代码 - 价格浮动逻辑

## ADDED Requirements
### Requirement: 一键出售价格计算
系统应确保一键出售功能对所有道具类型都使用统一的价格计算方式：`数据库价格 * 0.8`

### Requirement: 商人价格浮动
商人出售的道具价格应有30%上下浮动：`数据库价格 * (0.7 ~ 1.3)`

### Requirement: 数据库价格统一
- 装备/功法/丹药价格 = GameConfig.Rarity.basePrice
- 种子/材料/草药价格 = GameConfig.Rarity.basePrice * 0.4
