# 修复亲传弟子选择列表显示未空闲弟子的问题

## Why
在选择亲传弟子时，弟子列表会显示所有存活的弟子，包括那些正在忙碌（炼丹、炼器、种植、探索等）的弟子。这导致玩家可能选择一个已经分配了其他任务的弟子作为亲传弟子，造成任务冲突和逻辑混乱。

## What Changes
- 在 `DirectDiscipleSelectionDialog` 中添加弟子状态过滤，只显示状态为 `IDLE`（空闲）的弟子
- 在 `ElderDiscipleSelectionDialog` 中同样添加弟子状态过滤，保持一致性

## Impact
- Affected code: `MainGameScreen.kt` 中的 `DirectDiscipleSelectionDialog` 和 `ElderDiscipleSelectionDialog` 函数

## ADDED Requirements
### Requirement: 亲传弟子选择过滤
系统在选择亲传弟子时，应当只显示状态为空闲（IDLE）的弟子，排除正在执行其他任务的弟子。

#### Scenario: 选择亲传弟子时过滤忙碌弟子
- **WHEN** 玩家点击亲传弟子槽位进行选择
- **THEN** 系统只显示状态为 IDLE 的弟子，不显示正在炼丹、炼器、种植、探索等忙碌状态的弟子

#### Scenario: 选择长老时过滤忙碌弟子
- **WHEN** 玩家点击长老槽位进行选择
- **THEN** 系统只显示状态为 IDLE 的弟子，不显示正在忙碌的弟子

## MODIFIED Requirements
无

## REMOVED Requirements
无
