# 修复多次赏赐相同道具闪退问题 Spec

## Why
用户在多次赏赐相同道具时，应用会发生闪退。经过代码分析，发现`RewardItemsDialog`中的`isRewarding`状态管理存在问题：`isRewarding`在调用`viewModel.rewardItemsToDisciple`后立即被设为`false`，而不是等待协程实际完成。这导致用户可以快速连续点击赏赐按钮，可能引发以下问题：
1. 重复触发赏赐操作，导致数据竞争
2. `selectedItem`状态在协程执行期间发生变化，导致索引越界或空指针
3. `processDiscipleItemInstantlyInternal`中使用的数据快照在多次调用之间不一致

## What Changes
- 修复`RewardItemsDialog`中的`isRewarding`状态管理，确保在协程完成后才重置状态
- 在`GameViewModel.rewardItemsToDisciple`中添加防重复点击机制（类似`recruitDiscipleFromList`的实现）
- 确保赏赐操作的原子性，使用同步机制防止并发冲突
- 修复`processDiscipleItemInstantlyInternal`中可能存在的数据不一致问题

## Impact
- Affected specs: 赏赐道具、弟子详情界面
- Affected code:
  - `DiscipleDetailScreen.kt` - `RewardItemsDialog` 赏赐按钮点击处理
  - `GameViewModel.kt` - `rewardItemsToDisciple` 函数
  - `GameEngine.kt` - `processDiscipleItemInstantlyInternal` 函数

## ADDED Requirements

### Requirement: 修复 isRewarding 状态管理
系统应确保`isRewarding`状态在赏赐操作完成后才重置。

#### Scenario: 协程完成后重置状态
- **WHEN** 用户点击赏赐按钮
- **THEN** `isRewarding`应在`viewModel.rewardItemsToDisciple`协程完成后才设为`false`

#### Scenario: 使用 LaunchedEffect 管理异步状态
- **WHEN** 赏赐操作需要异步执行时
- **THEN** 应使用`LaunchedEffect`或类似机制确保状态正确管理

### Requirement: 添加防重复点击机制
系统应为赏赐操作添加防重复点击机制。

#### Scenario: 使用 Set 跟踪正在赏赐的弟子ID
- **WHEN** 用户赏赐道具给弟子
- **THEN** 应将弟子ID添加到`rewardingDiscipleIds`集合中，防止重复操作

#### Scenario: 完成后再移除ID
- **WHEN** 赏赐操作完成后（无论成功与否）
- **THEN** 应从`rewardingDiscipleIds`集合中移除弟子ID

#### Scenario: 重复点击被忽略
- **WHEN** 用户快速连续点击赏赐按钮
- **THEN** 如果该弟子已在`rewardingDiscipleIds`中，应直接返回不执行操作

### Requirement: 确保数据一致性
系统应确保赏赐过程中使用的数据保持一致。

#### Scenario: 使用快照数据
- **WHEN** 执行`processDiscipleItemInstantlyInternal`时
- **THEN** 应确保使用的disciple数据快照在整个操作过程中保持一致

## MODIFIED Requirements
无

## REMOVED Requirements
无
