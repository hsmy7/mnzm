# 模拟宗门 v2.3.31 任务完成报告

## 基本信息

| 项目 | 内容 |
|------|------|
| 版本号 | 2.3.31 |
| 版本代码 | 2044 |
| 日期 | 2026-04-24 |
| 分支 | main |
| 提交哈希 | 0b7ec40 |

---

## 一、任务概述

本次更新包含两个核心任务：

1. **所有物品价格减少10%**
2. **修复物品详情对话框锁定按钮状态不同步问题**

---

## 二、任务1：所有物品价格减少10%

### 2.1 实现方案

采用全局价格乘数常量 `GameConfig.Rarity.PRICE_MULTIPLIER = 0.9`，在价格计算的关键路径运行时应用，而非修改每个硬编码价格数值。

### 2.2 修改文件清单

| 文件 | 修改行数 | 修改内容 |
|------|---------|---------|
| `core/GameConfig.kt` | +1 | 添加 `PRICE_MULTIPLIER = 0.9` |
| `core/model/Items.kt` | 8处 | EquipmentStack/EquipmentInstance/ManualStack/ManualInstance/Pill/Material/Herb/Seed 的 basePrice 计算属性 |
| `core/engine/service/CultivationService.kt` | 7处 | buildMerchantItemPools() 中6种物品的 priceMap 设置 + createMerchantItem 的 fallback 价格 |
| `core/engine/service/EventService.kt` | 6处 | generateSectTradeItems() 中6种物品类型的 basePrice 计算 |
| `core/data/BeastMaterialDatabase.kt` | 1处 | 回退乘数应用，保持原始价格（使用点统一处理） |
| `core/data/HerbDatabase.kt` | 2处 | 回退乘数应用，保持原始价格（使用点统一处理） |

### 2.3 价格影响范围

| 物品类型 | 原价来源 | 影响方式 |
|----------|---------|---------|
| 装备 | EquipmentTemplate.price | basePrice * 0.9 |
| 功法 | ManualTemplate.price | basePrice * 0.9 |
| 丹药 | PillTemplate.price (pillBasePrice * grade乘数) | basePrice * 0.9 |
| 材料 | BeastMaterial.price (materialBasePrice) | price * 0.9 |
| 灵草 | Herb.price (materialBasePrice) | price * 0.9 |
| 种子 | Seed.price (materialBasePrice) | price * 0.9 |

### 2.4 复查发现的问题与修复

**问题**：代码复查发现 `BeastMaterialDatabase.price`、`HerbDatabase.Herb.price`、`HerbDatabase.Seed.price` 在数据库层应用了乘数，同时 `CultivationService` 的 priceMap 构建和 `EventService` 的商人物品创建中又再次应用乘数，导致材料/草药/种子价格被乘了两次 0.9（实际变为原价的81%）。

**修复**：回退 `BeastMaterialDatabase.kt` 和 `HerbDatabase.kt` 中的乘数应用，保持原始价格。乘数统一在使用点（`CultivationService.priceMap`、`EventService`）应用，与装备/功法/丹药的处理方式一致。

### 2.5 兼容性说明

- **无需数据库迁移**：价格乘数在运行时计算，不修改数据库结构
- **旧存档**：已存储的商人物品价格保持不变，新创建的物品使用新价格
- **售卖价格**：仍为 basePrice * 0.8，basePrice 已包含全局乘数

---

## 三、任务2：修复物品详情对话框锁定按钮状态不同步

### 3.1 问题描述

在物品详情对话框中点击"锁定"按钮后，按钮变为金色高亮并显示"已锁定"。再次点击时，虽然 `toggleItemLock()` 正确切换了状态，但对话框中的按钮未取消金色高亮，文字也未变回"锁定"。

### 3.2 根因分析

`isLocked` 从 `mutableStateOf` 捕获的 `item` 对象读取：

```kotlin
val isLocked = getWarehouseItemIsLocked(item)
```

`item` 是在 `selectedItem` 被赋值时捕获的不可变对象。当 `toggleItemLock()` 更新 StateFlow 中的列表时，`item` 对象本身不会变化，因此 composable 重组时 `isLocked` 仍读取旧值。

### 3.3 修复方案

改为从响应式 StateFlow 列表中查找当前锁定状态：

```kotlin
val isLocked = when (itemType) {
    "equipment" -> equipment.find { it.id == itemId }?.isLocked ?: false
    "manual" -> manuals.find { it.id == itemId }?.isLocked ?: false
    "pill" -> sortedPills.find { it.id == itemId }?.isLocked ?: false
    "material" -> sortedMaterials.find { it.id == itemId }?.isLocked ?: false
    "herb" -> sortedHerbs.find { it.id == itemId }?.isLocked ?: false
    "seed" -> sortedSeeds.find { it.id == itemId }?.isLocked ?: false
    else -> false
}
```

当 `toggleItemLock()` 更新 StateFlow 并发射新列表时，composable 重组会从新的列表中读取正确的锁定状态。

### 3.4 修改文件

| 文件 | 修改内容 |
|------|---------|
| `ui/game/MainGameScreen.kt` | 第4060-4068行，isLocked 读取方式替换 |

---

## 四、代码复查结果

| 检查项 | 结果 |
|--------|------|
| 价格乘数覆盖完整性 | 全部关键路径已覆盖 |
| 类型转换正确性 | Int * Double 均使用 roundToInt() |
| 双重乘数bug | 已修复（材料/草药/种子） |
| 锁定按钮修复正确性 | 从响应式列表读取，状态同步 |
| 数据库迁移需求 | 无需 |
| 旧存档兼容性 | 兼容 |
| 编译通过 | BUILD SUCCESSFUL |

---

## 五、版本更新

| 项目 | 旧值 | 新值 |
|------|------|------|
| versionCode | 2043 | 2044 |
| versionName | 2.3.30 | 2.3.31 |

---

## 六、远程仓库

- 提交已推送到 `origin/main`
- 提交哈希：`0b7ec40`
- 更新日志已同步更新至 `CHANGELOG.md`
