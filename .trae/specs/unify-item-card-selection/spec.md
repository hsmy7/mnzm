# 统一道具卡片选择界面 Spec

## Why
弟子选择武器界面和选择功法界面使用了自定义的列表样式，与仓库的道具卡片样式不一致，导致用户体验不统一。同时，这些界面在处理大量道具时响应速度较慢，需要优化。装备槽位和功法槽位的渲染也需要优化以提高响应速度。

## What Changes
- 将 `EquipmentSelectionDialog`（装备选择对话框）中的道具卡片改为使用仓库的卡片样式
- 将 `ManualSelectionDialog`（功法选择对话框）中的道具卡片改为使用仓库的卡片样式
- 统一使用 `WarehouseEquipmentCard` 和 `WarehouseManualCard` 组件
- 优化列表渲染性能，使用 `LazyVerticalGrid` 替代 `Column` + `verticalScroll` + `forEach`
- 选择对话框使用 4 列网格布局显示道具
- 统一查看按钮的位置和样式
- 优化 `EquipmentSlot` 和 `ManualSlot` 组件的渲染性能

## Impact
- Affected specs: 弟子装备管理、功法学习
- Affected code: 
  - `DiscipleDetailScreen.kt` - `EquipmentSelectionDialog`、`ManualSelectionDialog`、`EquipmentSlot`、`ManualSlot` 组件
  - 可能需要新增通用的道具卡片组件或扩展现有组件

## ADDED Requirements

### Requirement: 统一道具卡片样式
系统应确保弟子选择装备界面和选择功法界面的道具卡片样式与仓库界面一致。

#### Scenario: 选择装备时卡片样式一致
- **WHEN** 用户在弟子详情界面点击装备槽位
- **THEN** 显示的装备选择对话框中的卡片样式应与仓库中的装备卡片样式一致（正方形、稀有度边框、查看按钮位置等）

#### Scenario: 选择功法时卡片样式一致
- **WHEN** 用户在弟子详情界面点击学习功法
- **THEN** 显示的功法选择对话框中的卡片样式应与仓库中的功法卡片样式一致

### Requirement: 优化响应速度
系统应优化道具选择界面的渲染性能，确保在大量道具时依然流畅。

#### Scenario: 大量装备时快速响应
- **WHEN** 用户打开装备选择对话框且有大量装备
- **THEN** 列表应使用懒加载方式渲染，确保界面流畅

#### Scenario: 大量功法时快速响应
- **WHEN** 用户打开功法选择对话框且有大量功法
- **THEN** 列表应使用懒加载方式渲染，确保界面流畅

### Requirement: 统一查看按钮功能
系统应确保查看按钮的位置和功能与仓库卡片一致。

#### Scenario: 查看按钮位置一致
- **WHEN** 用户选中一个道具卡片
- **THEN** 查看按钮应显示在卡片右上角，样式与仓库卡片一致

#### Scenario: 查看按钮功能一致
- **WHEN** 用户点击查看按钮
- **THEN** 应显示道具详情对话框，功能与仓库中的查看功能一致

### Requirement: 优化槽位响应速度
系统应优化装备槽位和功法槽位的渲染性能，确保界面响应迅速。

#### Scenario: 装备槽位快速响应
- **WHEN** 用户查看弟子详情界面
- **THEN** 装备槽位应快速渲染，无卡顿

#### Scenario: 功法槽位快速响应
- **WHEN** 用户查看弟子详情界面
- **THEN** 功法槽位应快速渲染，无卡顿

### Requirement: 4列网格布局
系统应在选择对话框中使用4列网格布局显示道具。

#### Scenario: 装备选择对话框4列显示
- **WHEN** 用户打开装备选择对话框
- **THEN** 道具应以4列网格布局显示

#### Scenario: 功法选择对话框4列显示
- **WHEN** 用户打开功法选择对话框
- **THEN** 功法应以4列网格布局显示
