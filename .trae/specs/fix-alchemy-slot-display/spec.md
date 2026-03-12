# 修复炼制丹药后相应炼丹槽位不显示丹药的问题 Spec

## Why
当用户开始炼制丹药后，UI 上的炼丹槽位不显示正在炼制的丹药信息，导致用户无法看到炼丹进度。

## What Changes
- 在 `startAlchemy` 方法中同步更新 `_alchemySlots` 状态，使其与 `_buildingSlots` 保持一致

## Impact
- Affected specs: 炼丹系统 UI 显示
- Affected code: `GameEngine.kt` 中的 `startAlchemy` 方法

## ADDED Requirements
### Requirement: 炼丹槽位状态同步
当用户开始炼制丹药时，系统 SHALL 同时更新 `_alchemySlots` 和 `_buildingSlots`，确保 UI 能正确显示炼丹进度。

#### Scenario: 开始炼丹后槽位显示正确
- **WHEN** 用户选择丹药配方并开始炼制
- **THEN** 炼丹槽位应立即显示正在炼制的丹药名称和剩余时间

## Root Cause Analysis
游戏中存在两套独立的槽位系统：
1. `_alchemySlots` (AlchemySlot) - 用于 UI 显示炼丹状态
2. `_buildingSlots` (BuildingSlot) - 通用的建筑槽位系统，用于实际工作处理

当前 `startAlchemy(slotIndex: Int, recipeId: String)` 方法只更新了 `_buildingSlots`，没有同步更新 `_alchemySlots`，导致 UI 无法获取到正确的炼丹状态。

## Solution
在 `startAlchemy` 方法中，调用 `startBuildingWork` 后，同步创建或更新对应的 `AlchemySlot` 对象到 `_alchemySlots` 中。
