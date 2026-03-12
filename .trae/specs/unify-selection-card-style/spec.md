# 统一选择界面卡片样式与移除详情确认按钮 Spec

## Why
选择种子界面的卡片样式与选择丹药/装备界面的卡片样式不一致，导致用户体验不统一。同时，选择丹药/装备/种子的详情对话框中存在确认按钮，增加了用户操作步骤。需要统一所有界面的道具卡片样式（以选择丹药界面的卡片样式为标准），并移除所有详情对话框中的确认按钮。另外，仓库界面需要改为5列显示，装备与功法槽位的大小需要与道具卡片一致。

## What Changes
- 将仓库界面（`WarehouseTab`）的网格布局从4列改为5列
- 将装备槽位（`EquipmentSlot`）的大小改为与道具卡片一致（正方形，aspectRatio(1f)）
- 将功法槽位（`ManualSlot`）的大小改为与道具卡片一致（正方形，aspectRatio(1f)）
- 将 `SeedSelectionDialog`（种子选择对话框）的卡片样式改为与选择丹药界面卡片样式一致
- 将 `SeedSelectionDialog` 的列表布局从 `LazyColumn` 改为 `LazyVerticalGrid`（4列）
- 统一所有选择界面卡片的查看按钮位置和样式（以选择丹药界面为标准）
- 移除 `PillDetailDialog`（丹药详情对话框）中的确认按钮
- 移除 `EquipmentSelectionDialog` 中装备详情对话框的确认按钮
- 移除 `ManualSelectionDialog` 中功法详情对话框的确认按钮

## Impact
- Affected specs: 仓库管理、灵药园种植、炼丹房炼丹、弟子装备管理、功法学习
- Affected code: 
  - `MainGameScreen.kt` - `WarehouseTab` 组件
  - `DiscipleDetailScreen.kt` - `EquipmentSlot`、`ManualSlot`、`EquipmentSelectionDialog`、`ManualSelectionDialog` 组件
  - `HerbGardenScreen.kt` - `SeedSelectionDialog` 组件
  - `AlchemyScreen.kt` - `PillDetailDialog` 组件、`PillSelectionDialog` 卡片样式

## ADDED Requirements

### Requirement: 仓库界面5列显示
系统应将仓库界面的道具显示改为5列网格布局。

#### Scenario: 仓库界面5列显示
- **WHEN** 用户打开仓库界面
- **THEN** 道具应以5列网格布局显示

### Requirement: 装备槽位大小与道具卡片一致
系统应确保装备槽位的大小与道具卡片一致。

#### Scenario: 装备槽位为正方形
- **WHEN** 用户查看弟子详情界面的装备槽位
- **THEN** 装备槽位应为正方形（aspectRatio(1f)），与道具卡片大小一致

### Requirement: 功法槽位大小与道具卡片一致
系统应确保功法槽位的大小与道具卡片一致。

#### Scenario: 功法槽位为正方形
- **WHEN** 用户查看弟子详情界面的功法槽位
- **THEN** 功法槽位应为正方形（aspectRatio(1f)），与道具卡片大小一致

### Requirement: 种子选择界面卡片样式统一
系统应确保种子选择界面的道具卡片样式与选择丹药界面的卡片样式一致。

#### Scenario: 种子卡片为正方形
- **WHEN** 用户打开种子选择对话框
- **THEN** 种子卡片应为正方形（aspectRatio(1f)），与选择丹药界面卡片大小一致

#### Scenario: 种子卡片使用4列网格布局
- **WHEN** 用户打开种子选择对话框
- **THEN** 种子应以4列网格布局显示

#### Scenario: 种子卡片有稀有度边框
- **WHEN** 用户查看种子卡片
- **THEN** 卡片应有对应稀有度颜色的边框

#### Scenario: 种子卡片查看按钮位置一致
- **WHEN** 用户选中一个种子卡片
- **THEN** 查看按钮应显示在卡片右上角，样式与选择丹药界面卡片一致

### Requirement: 统一所有选择界面卡片样式
系统应确保所有选择界面的道具卡片样式与选择丹药界面一致。

#### Scenario: 卡片内边距一致
- **WHEN** 用户查看任意选择界面的道具卡片
- **THEN** 卡片内边距应为 8.dp（与选择丹药界面一致）

#### Scenario: 查看按钮样式一致
- **WHEN** 用户选中任意选择界面的道具卡片
- **THEN** 查看按钮应使用 RoundedCornerShape(4.dp)、字体大小 8.sp、padding(horizontal = 6.dp, vertical = 2.dp)

#### Scenario: 查看按钮位置一致
- **WHEN** 用户选中任意选择界面的道具卡片
- **THEN** 查看按钮应在卡片右上角，使用 offset(x = (-2).dp, y = 2.dp)

### Requirement: 移除所有详情对话框确认按钮
系统应在所有选择界面的道具详情对话框中移除确认按钮，用户点击对话框外部即可关闭。

#### Scenario: 丹药详情无确认按钮
- **WHEN** 用户在丹药选择界面点击查看按钮
- **THEN** 显示的详情对话框应无确认按钮，用户可点击外部关闭

#### Scenario: 装备详情无确认按钮
- **WHEN** 用户在装备选择界面点击查看按钮
- **THEN** 显示的详情对话框应无确认按钮，用户可点击外部关闭

#### Scenario: 功法详情无确认按钮
- **WHEN** 用户在功法选择界面点击查看按钮
- **THEN** 显示的详情对话框应无确认按钮，用户可点击外部关闭

## MODIFIED Requirements
无

## REMOVED Requirements
无
