# 修复炼丹空槽位点击后显示错误问题 Spec

## Why
用户反馈炼丹槽位的空槽位有时候点击后会显示该炼丹位有了位置（正在炼制状态），这是一个槽位索引错位的 bug。

## What Changes
- 修复 `_alchemySlots` 的初始化逻辑，确保游戏开始时就包含正确数量的空闲槽位
- 修复 `startAlchemy` 方法中槽位更新的索引逻辑

## Impact
- Affected specs: 炼丹系统槽位管理
- Affected code: `GameEngine.kt` 中的 `_alchemySlots` 初始化和 `startAlchemy` 方法

## Root Cause Analysis
游戏中存在以下问题：

1. `_alchemySlots` 初始化为空列表，只有在占领"中型宗门"时才会添加槽位
2. UI 始终显示 3 个槽位，但 `_alchemySlots` 可能为空或大小不足
3. 在 `startAlchemy` 方法中，当 `slotIndex >= currentAlchemySlots.size` 时，新槽位被追加到列表末尾，而不是插入到正确的索引位置

**Bug 场景示例：**
- `_alchemySlots` 为空列表
- 用户点击 UI 中索引 2 的槽位
- `startAlchemy` 将新槽位追加到 `_alchemySlots` 的索引 0 位置
- UI 中索引 0 的槽位错误地显示为"正在炼制"状态
- 用户点击的索引 2 槽位仍然显示为空

## ADDED Requirements
### Requirement: 炼丹槽位初始化
游戏初始化时，系统 SHALL 为 `_alchemySlots` 创建正确数量的空闲槽位（默认 3 个）。

#### Scenario: 游戏开始时槽位初始化
- **WHEN** 游戏开始或重置
- **THEN** `_alchemySlots` 应包含 3 个空闲状态的 `AlchemySlot`，索引分别为 0、1、2

### Requirement: 槽位更新索引正确
当用户在某个槽位开始炼丹时，系统 SHALL 确保槽位更新到正确的索引位置。

#### Scenario: 空槽位开始炼丹
- **WHEN** 用户在索引 N 的空槽位开始炼丹
- **THEN** `_alchemySlots[N]` 应更新为炼制状态，而不是追加到列表末尾

## Solution
1. 在游戏初始化时（`initializeGame` 方法），为 `_alchemySlots` 创建 3 个空闲槽位
2. 在 `startAlchemy` 方法中，确保槽位更新到正确的索引位置（使用填充空闲槽位的方式）
