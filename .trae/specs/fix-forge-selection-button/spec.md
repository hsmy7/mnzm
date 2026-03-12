# 修复选中装备后无法点击开始炼制制造装备的问题 Spec

## Why
在炼器室选择装备配方时，点击卡片后"开始炼制"按钮始终处于禁用状态，用户无法开始炼制装备。

## What Changes
- 修复 `EquipmentSelectionDialog` 中点击卡片后 `selectedRecipe` 未被设置的问题
- 按钮启用条件需同时检查是否有足够的锻造材料

## Impact
- Affected specs: 炼器室功能
- Affected code: `ForgeScreen.kt` 中的 `EquipmentSelectionDialog` 组件

## Root Cause Analysis
在 `EquipmentSelectionDialog` 中存在以下问题：

1. **状态变量未正确设置**：
   - `selectedRecipe` - 用于跟踪选中的配方，"开始炼制"按钮的启用条件依赖于它
   - `clickedRecipe` - 用于跟踪点击的配方，用于显示"查看"按钮
   - 当用户点击装备卡片时（第 224-226 行），只设置了 `clickedRecipe = recipe`，但没有设置 `selectedRecipe`

2. **按钮启用条件不完整**：
   - 当前按钮启用条件只检查 `selectedRecipe != null`（第 282 行）
   - 应该同时检查是否有足够的锻造材料

## Solution
1. 在点击卡片时同时设置 `selectedRecipe = recipe`
2. 按钮启用条件改为：`selectedRecipe != null` 且该配方有足够的材料

## ADDED Requirements

### Requirement: 装备选择后可开始炼制
当用户在炼器室选择装备配方后，系统应根据材料情况正确设置"开始炼制"按钮状态。

#### Scenario: 选中装备且有足够材料时按钮可点击
- **WHEN** 用户在炼器室点击一个装备配方卡片，且仓库中有足够的锻造材料
- **THEN** "开始炼制"按钮应变为可点击状态（橙色背景）

#### Scenario: 选中装备但材料不足时按钮禁用
- **WHEN** 用户在炼器室点击一个装备配方卡片，但仓库中没有足够的锻造材料
- **THEN** "开始炼制"按钮应保持禁用状态（灰色背景）

#### Scenario: 点击开始炼制成功
- **WHEN** 用户选中装备配方（有足够材料）后点击"开始炼制"按钮
- **THEN** 系统应开始炼制该装备，并关闭选择对话框

## MODIFIED Requirements
无

## REMOVED Requirements
无
