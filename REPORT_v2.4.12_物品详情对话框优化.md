# 物品详情对话框优化报告

**版本**: v2.4.12
**日期**: 2026-04-24
**关联提交**: 24baee2 (v2.4.11中ItemDetailDialog.kt的修改) + e4e9399 (v2.4.12版本号与日志更新)

---

## 一、问题概述

本次优化针对游戏中物品详情对话框存在的5大类问题进行全面修复：

1. 部分功法详情对话框缺少技能描述
2. 部分详情对话框显示代码而非中文（如草药类别显示 grass/flower/fruit）
3. 一次性丹药错误显示持续时间
4. 不同界面的物品详情描述不一致（商人/仓库/储物袋等）
5. 缺少物品关联信息（草药可制丹药、种子成熟后草药、材料可锻装备等）

---

## 二、修改文件清单

| 文件 | 路径 | 修改类型 |
|------|------|---------|
| ItemDetailDialog.kt | `android/app/src/main/java/com/xianxia/sect/ui/game/components/ItemDetailDialog.kt` | 核心修改（+459/-155行） |
| HerbDatabase.kt | `android/app/src/main/java/com/xianxia/sect/core/data/HerbDatabase.kt` | 修复替换顺序 |
| build.gradle | `android/app/build.gradle` | 版本号更新 |
| CHANGELOG.md | `CHANGELOG.md` | 更新日志 |

---

## 三、详细修改内容

### 3.1 修复草药类别显示为英文代码

**问题**: 草药详情对话框中类别显示为 `grass`/`flower`/`fruit` 而非中文。

**修复**: 新增 `getHerbCategoryName()` 函数：

```kotlin
private fun getHerbCategoryName(category: String): String = when (category) {
    "grass" -> "灵草"
    "flower" -> "灵花"
    "fruit" -> "灵果"
    else -> if (category.isNotEmpty()) category else "灵药"
}
```

影响函数：`getHerbEffects()`、`getMerchantItemEffects()`（herb分支）、`getStorageBagItemEffects()`（herb分支）。

---

### 3.2 补充属性键中文映射

**问题**: `getStatDisplayName()` 缺少多个属性键映射，导致显示英文代码。

**修复**: 扩展映射表，新增12个属性：

| 新增属性键 | 中文显示 |
|-----------|---------|
| `skillExpSpeedPercent` | 功法熟练度速度 |
| `nurtureSpeedPercent` | 孕养速度 |
| `critEffect` | 暴击效果 |
| `intelligence` | 悟性 |
| `charm` | 魅力 |
| `loyalty` | 忠诚 |
| `comprehension` | 领悟 |
| `artifactRefining` | 炼器 |
| `pillRefining` | 炼丹 |
| `spiritPlanting` | 灵植 |
| `teaching` | 教导 |
| `morality` | 道德 |

---

### 3.3 修复一次性丹药持续时间显示

**问题**: 功能丹药（如增加属性的一次性丹药）详情错误显示"持续 X 月"。

**修复逻辑**:
- **一次性丹药**（功能类 / 突破类）：显示 `(一次性效果)`，不显示持续时间
- **持续效果丹药**（修炼类增益 / 战斗类增益）：显示 `持续 X 月`

```kotlin
val isInstant = item.category == PillCategory.FUNCTIONAL ||
    (item.category == PillCategory.CULTIVATION && item.pillType == "breakthrough")
```

影响位置：`getPillEffects()`、`getMerchantItemEffects()`（pill分支）、`getStorageBagItemEffects()`（pill分支）。

---

### 3.4 完善丹药效果描述

**问题**: 丹药详情仅显示突破概率，缺少大量效果属性。

**修复**: 按三大类别完整显示所有效果：

**功能丹药 (FUNCTIONAL)**:
- 突破概率、目标境界、渡劫标识
- 延寿、悟性、魅力、忠诚、领悟、炼器、炼丹、灵植、教导、道德
- 恢复生命/灵力（百分比）、复活、清除负面状态
- 生命、灵力、物理攻击、法术攻击、物理防御、法术防御、速度

**修炼丹药 (CULTIVATION)**:
- 修炼速度、功法熟练度速度、孕养速度
- 修为、功法熟练度、孕养值
- 突破概率、目标境界、渡劫标识

**战斗丹药 (BATTLE)**:
- 物理攻击、法术攻击、物理防御、法术防御
- 生命、灵力、速度、暴击率、暴击效果

---

### 3.5 添加物品关联信息

#### 3.5.1 丹药 → 炼制所需草药

通过 `PillRecipeDatabase` 查询配方，显示所需草药及数量：

```kotlin
private fun MutableList<String>.addPillRecipeInfo(pillId: String, pillName: String) {
    val pillRecipe = PillRecipeDatabase.getRecipeById(pillId)
        ?: PillRecipeDatabase.getRecipeByName(pillName)
    if (pillRecipe != null && pillRecipe.materials.isNotEmpty()) {
        add("")
        add("炼制所需:")
        pillRecipe.materials.forEach { (herbId, count) ->
            val herbName = HerbDatabase.getHerbById(herbId)?.name ?: herbId
            add("  · $herbName x$count")
        }
    }
}
```

影响：所有丹药详情（Pill / MerchantItem pill / StorageBagItem pill）。

#### 3.5.2 装备 → 锻造所需材料

通过 `ForgeRecipeDatabase` 查询配方，显示所需材料及数量：

```kotlin
private fun MutableList<String>.addForgeMaterialsInfo(equipmentName: String) {
    val forgeRecipe = ForgeRecipeDatabase.getAllRecipes().find { it.name == equipmentName }
    if (forgeRecipe != null && forgeRecipe.materials.isNotEmpty()) {
        add("")
        add("锻造所需:")
        forgeRecipe.materials.forEach { (materialId, count) ->
            val materialName = BeastMaterialDatabase.getMaterialById(materialId)?.name ?: materialId
            add("  · $materialName x$count")
        }
    }
}
```

影响：所有装备详情（EquipmentStack / EquipmentInstance / MerchantItem equipment / StorageBagItem equipment）。

#### 3.5.3 草药 → 可炼丹药

通过 `PillRecipeDatabase.getRecipesByHerb()` 查询，显示最多5种可炼制丹药。

#### 3.5.4 材料 → 可锻装备

通过 `ForgeRecipeDatabase.getRecipesByMaterial()` 查询，显示最多5种可锻造装备。

#### 3.5.5 种子 → 长成后草药

通过 `HerbDatabase.getHerbFromSeedName()` / `getHerbFromSeed()` 查询，显示成熟后的草药名称、描述及可炼丹药。

---

### 3.6 统一不同界面的物品详情

#### 3.6.1 重写 `getMerchantItemEffects()`（商人界面）

按物品类型分发，每种类型都有完整信息：

| 物品类型 | 显示内容 |
|---------|---------|
| 装备 | 部位 + 属性 + 锻造材料 |
| 功法 | 类型 + 属性加成 + 技能详情 |
| 丹药 | 完整效果（按类别）+ 炼制配方 + 一次性标识 |
| 材料 | 可炼器装备列表 |
| 草药 | 类型（中文）+ 可炼丹药列表 |
| 种子 | 长成后草药 + 描述 + 可炼丹药 |

#### 3.6.2 重写 `getStorageBagItemEffects()`（储物袋界面）

同样按类型分发，与商人界面保持一致：

| 物品类型 | 显示内容 |
|---------|---------|
| 装备 | 属性 + 锻造材料（模板查询 + ItemEffect 回退） |
| 功法 | 类型 + 属性加成 + 技能详情 |
| 丹药 | 完整效果 + 炼制配方 + 一次性标识 |
| 材料 | 可炼器装备列表 |
| 草药 | 类型（中文）+ 可炼丹药列表 |
| 种子 | 长成后草药 + 描述 + 可炼丹药 |

#### 3.6.3 修复描述字段

`MerchantItem` 和 `StorageBagItem` 的 `description` 原为空字符串，现改为从对应模板获取：

```kotlin
// MerchantItem
description = when (item.type) {
    "equipment" -> EquipmentDatabase.getTemplateByName(item.name)?.description ?: item.description
    "manual" -> ManualDatabase.getByName(item.name)?.description ?: item.description
    "pill" -> ItemDatabase.getPillByName(item.name)?.description ?: item.description
    "herb" -> HerbDatabase.getHerbByName(item.name)?.description ?: item.description
    "seed" -> HerbDatabase.getSeedByName(item.name)?.description ?: item.description
    "material" -> BeastMaterialDatabase.getMaterialByName(item.name)?.description ?: item.description
    else -> item.description
}
```

---

### 3.7 提取共用函数减少重复

#### 3.7.1 `addManualSkillInfo()`

提取功法技能信息显示逻辑，供 `getManualStackEffects()` 和 `getMerchantItemEffects()` / `getStorageBagItemEffects()` 共用。

显示内容：技能描述、类型、作用范围、治疗、伤害类型/倍率、连击次数、冷却回合、灵力消耗、Buff效果。

#### 3.7.2 `addPillRecipeInfo()`

提取丹药炼制配方显示逻辑，供所有丹药详情共用。

#### 3.7.3 `addForgeMaterialsInfo()`

提取装备锻造材料显示逻辑，供所有装备详情共用。

---

### 3.8 其他修复

#### 3.8.1 `HerbDatabase.getHerbNameFromSeedName()` 替换顺序

**原问题**: `"果核"` 判断在 `"核"` 之后，导致 `"果核"` 分支永远不会执行。

**修复**: 将 `"果核"` 判断提前到 `"核"` 之前。

#### 3.8.2 统一恢复描述格式

所有恢复生命/灵力的描述统一加上 `"最大生命"` / `"最大灵力"` 后缀：
- 修复前：`恢复生命 50.0%`
- 修复后：`恢复生命 50.0% 最大生命`

#### 3.8.3 `StorageBagItem` 丹药 `isInstantPill` 判断

**复查修复**: 补充 `pillCategory == CULTIVATION` 约束，与 `getPillEffects()` 保持一致：

```kotlin
val isInstantPill = item.effect.pillCategory == PillCategory.FUNCTIONAL.name ||
    (item.effect.pillCategory == PillCategory.CULTIVATION.name && item.effect.pillType == "breakthrough")
```

---

## 四、代码质量

### 4.1 编译验证

所有修改已通过 `compileDebugKotlin` 编译验证，无错误。

### 4.2 代码复查

调用代码复查专家智能体进行全面复查，发现并修复以下问题：

| 问题 | 严重程度 | 状态 |
|------|---------|------|
| `isInstantPill` 判断条件缺少 `pillCategory == CULTIVATION` 约束 | 严重 | 已修复 |
| `getManualStackEffects` 技能显示代码与 `addManualSkillInfo` 重复 | 主要 | 已修复（替换为调用） |
| `HerbDatabase.getHerbNameFromSeedName` 替换顺序错误 | 主要 | 已修复 |
| `StorageBagItem` 描述获取缺少 `"material"` 类型 | 次要 | 已修复 |
| 恢复描述格式不一致 | 次要 | 已统一 |

### 4.3 测试覆盖

本次修改涉及以下物品类型的详情对话框：

- [x] EquipmentStack（宗门仓库装备）
- [x] EquipmentInstance（已装备装备）
- [x] ManualStack（宗门仓库功法）
- [x] ManualInstance（已学习功法）
- [x] Pill（宗门仓库丹药）
- [x] Material（宗门仓库材料）
- [x] Herb（宗门仓库草药）
- [x] Seed（宗门仓库种子）
- [x] MerchantItem（商人界面商品）
- [x] StorageBagItem（弟子储物袋物品）

---

## 五、版本更新

- **版本号**: 2.4.11 → **2.4.12**
- **versionCode**: 2058 → **2059**
- **提交**: 已推送至远程仓库 `origin/main`

---

## 六、兼容性说明

本次修改仅涉及 UI 层的物品详情展示逻辑，不涉及：
- 数据模型变更（无数据库迁移需求）
- 存档格式变更（无旧存档兼容问题）
- 游戏机制变更（纯展示优化）

因此**无需数据库迁移，完全兼容旧存档**。
