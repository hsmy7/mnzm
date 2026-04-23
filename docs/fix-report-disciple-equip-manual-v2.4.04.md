# 弟子更换界面修复报告 v2.4.04

## 1. 问题描述

弟子详情界面中的更换功能存在以下三个问题：

### 1.1 物品卡片样式不一致（无堆叠）

弟子更换界面的装备选择卡片（`EquipmentSelectionCard`）和功法选择卡片（`ManualSelectionCard`）使用了自定义实现，与项目其他界面（背包、商店、赏赐等）使用的 `UnifiedItemCard` 样式不一致：

| 对比项 | 自定义卡片 | UnifiedItemCard |
|--------|-----------|-----------------|
| 尺寸 | `aspectRatio(1f)` 自适应 | 固定 56dp |
| 名称颜色 | 稀有度颜色 | `GameColors.TextPrimary` |
| 品阶标签 | 无 | 左下角显示品阶 |
| 堆叠数量 | 无 | 右下角显示 xN |
| 锁定标记 | 无 | 左上角"锁定" |
| 网格列数 | `GridCells.Fixed(5)` | `GridCells.Adaptive(56.dp)` |

### 1.2 功法更换后功法消失

将功法更换后，被更换的功法和更换的功法都消失，功法槽位显示为空。

### 1.3 点击功法槽位后不显示宗门仓库功法

点击空的功法槽位后，功法选择对话框永远显示"暂无可学习的功法"，无法从宗门仓库选择功法学习。

---

## 2. 根因分析

### 2.1 核心Bug：数据类型不匹配

**功法选择/更换对话框使用的是 `ManualInstance`（已学功法实例），但引擎层期望接收 `ManualStack`（仓库功法堆叠）的 ID。**

调用链路：

```
UI 层: ManualSelectionDialog → GameViewModel.learnManual(discipleId, manualId)
                                                              ↓
引擎层: GameEngine.learnManual(discipleId, stackId)
        → manualStacks.find { it.id == stackId }  // 永远找不到！
        → return@update  // 静默失败
```

`ManualInstance` 的 ID 与 `ManualStack` 的 ID 是不同的，因此 `manualStacks.find { it.id == stackId }` 永远返回 null，`learnManual` 直接 `return@update` 退出。

### 2.2 更换操作的具体失败流程

`replaceManual` 的实现是"先遗忘再学习"：

```kotlin
fun replaceManual(discipleId, oldInstanceId, newStackId) {
    forgetManual(discipleId, oldInstanceId)  // 第1步：成功移除旧功法
    learnManual(discipleId, newStackId)       // 第2步：因ID不匹配而失败
}
```

1. `forgetManual` 成功执行：旧功法实例从 `manualInstances` 中删除，转回 `manualStacks`，弟子 `manualIds` 中移除
2. `learnManual` 失败：传入的 ID 是 `ManualInstance` 的 ID，在 `manualStacks` 中找不到，直接退出
3. **结果**：旧功法被移除，新功法未学习，槽位为空

### 2.3 功法选择对话框永远为空的原因

`ManualSelectionDialog` 的过滤逻辑：

```kotlin
allManuals.filter { manual ->
    manual.id !in currentManualIds &&  // ManualInstance 的 ID 不在 manualIds 中
    (manual.ownerId == null || manual.ownerId == currentDiscipleId) &&
    ...
}
```

`allManuals` 是 `List<ManualInstance>`，其中所有已学功法的 `ownerId` 都不为 null（已分配给弟子），且 `id` 在 `currentManualIds` 中。未学习的功法以 `ManualStack` 形式存储在仓库中，不在 `allManuals` 列表里，因此过滤后永远为空。

### 2.4 装备选择对话框的同类问题

`EquipmentSelectionDialog` 只接收 `allEquipment: List<EquipmentInstance>`，缺少仓库中的 `EquipmentStack`。虽然装备选择偶尔能工作（因为存在未装备的 `EquipmentInstance`），但仓库中堆叠状态的装备不会显示。

---

## 3. 修复方案

### 3.1 数据源修正

| 对话框 | 修改前数据源 | 修改后数据源 |
|--------|------------|------------|
| 功法学习 | `allManuals: List<ManualInstance>` | `manualStacks: List<ManualStack>` |
| 功法更换 | `allManuals: List<ManualInstance>` | `manualStacks: List<ManualStack>` |
| 装备选择 | `allEquipment: List<EquipmentInstance>` | `equipmentStacks + allEquipment` 合并 |

### 3.2 样式统一

删除自定义卡片组件，统一使用 `UnifiedItemCard`：

- 删除 `EquipmentSelectionCard`（65行）
- 删除 `ManualSelectionCard`（65行）
- 删除 `ManualReplaceDialog`（164行，功能已由内联替换对话框替代）
- 删除 `getRarityText`（11行，改用 `getRarityName`）
- 删除装备详情弹窗内嵌代码（74行，已有 `ItemDetailDialog` 统一组件）

### 3.3 参数传递链路

```
GameViewModel
  ├── manualStacks: StateFlow<List<ManualStack>>
  └── equipmentStacks: StateFlow<List<EquipmentStack>>
        ↓
MainGameScreen
  ├── DisciplesTab(manualStacks, equipmentStacks)
  └── RealmExplorationDialog(manualStacks, equipmentStacks)
        ↓
DiscipleDetailDialog(manualStacks, equipmentStacks)
        ↓
EquipmentSelectionDialog(equipmentStacks, ...)
ManualSelectionDialog(manualStacks, ...)
功法更换内联对话框(manualStacks, ...)
```

---

## 4. 修改文件清单

### 4.1 DiscipleDetailScreen.kt

| 修改项 | 说明 |
|--------|------|
| `DiscipleDetailDialog` 签名 | 新增 `manualStacks` 和 `equipmentStacks` 参数 |
| `EquipmentSelectionDialog` | 重写：合并 Stack + Instance 数据源，使用 `UnifiedItemCard`，新增 `discipleRealm` 参数用于境界过滤 |
| `EquipmentSelectionCard` | 删除（65行） |
| `ManualSelectionDialog` | 重写：改用 `manualStacks` 数据源，使用 `UnifiedItemCard` |
| `ManualSelectionCard` | 删除（65行） |
| `ManualReplaceDialog` | 删除（164行，功能由内联替换对话框替代） |
| 功法更换内联对话框 | 重写：改用 `manualStacks` 数据源，使用 `UnifiedItemCard` |
| `getRarityText` | 删除（11行），改用 `getRarityName` |
| 装备详情弹窗内嵌代码 | 删除（74行），已有 `ItemDetailDialog` 统一组件 |
| `EquipmentSelectionItem` | 新增私有数据类，统一 Stack/Instance 的显示数据 |

净变化：**-575行 / +128行**

### 4.2 MainGameScreen.kt

| 修改项 | 说明 |
|--------|------|
| `DisciplesTab` 签名 | 新增 `manualStacks` 和 `equipmentStacks` 参数 |
| `DisciplesTab` 调用处 | 传入 `manualStacks` 和 `equipmentStacks` |
| `DiscipleDetailDialog` 调用处 ×2 | 传入 `manualStacks` 和 `equipmentStacks` |
| `RealmExplorationDialog` 内部 | 新增 `manualStacks` 和 `equipmentStacks` 局部变量 |

### 4.3 build.gradle

版本号更新：`2.4.03` → `2.4.04`，`versionCode` 2050 → 2051

---

## 5. 数据库兼容性

本次修改仅涉及 UI 层逻辑，不涉及数据模型变更，无需数据库迁移，旧存档完全兼容。

---

## 6. 测试要点

1. **功法学习**：点击空功法槽位 → 应显示宗门仓库中的功法列表 → 选择后成功学习
2. **功法更换**：点击已学功法 → 点击"更换" → 应显示仓库功法 → 选择后旧功法回仓、新功法学习
3. **功法遗忘**：点击已学功法 → 点击"遗忘" → 功法回仓、槽位清空
4. **装备选择**：点击空装备槽位 → 应同时显示仓库堆叠和未装备实例 → 选择后成功装备
5. **堆叠显示**：数量 >1 的物品应显示 xN 标记
6. **心法互斥**：已学心法时，不应再显示心法类功法
7. **境界过滤**：不满足境界要求的物品不应显示
8. **取消选中**：点击已选中的物品应能取消选中
