# 修复多次赏赐道具闪退问题 Spec

## Why
用户在多次赏赐道具时，应用会发生闪退。经过代码分析，发现`rewardItemsToDisciple`函数存在并发修改StateFlow的问题。当连续快速赏赐时，多个协程同时修改`_disciples.value`和其他StateFlow，导致数据不一致或竞态条件，从而引发崩溃。

## What Changes
- 修复`rewardItemsToDisciple`函数中的并发修改问题
- 使用原子性操作确保 disciple 数据的一致性
- 避免在循环中多次更新 StateFlow，改为单次批量更新
- 修复`processDiscipleItemInstantly`中可能的数据不一致问题
- **BREAKING**: 赏赐后道具自动处理逻辑改为异步执行，避免阻塞主线程

## Impact
- Affected specs: 赏赐道具、弟子储物袋
- Affected code:
  - `GameEngine.kt` - `rewardItemsToDisciple` 函数
  - `GameEngine.kt` - `processDiscipleItemInstantly` 函数
  - `DiscipleDetailScreen.kt` - `RewardItemsDialog` 赏赐按钮点击处理

## ADDED Requirements

### Requirement: 修复并发修改问题
系统应确保赏赐道具时不会发生并发修改StateFlow导致的崩溃。

#### Scenario: 单次赏赐原子性操作
- **WHEN** 用户赏赐道具给弟子
- **THEN** 所有 disciple 数据的修改应在单次 StateFlow 更新中完成

#### Scenario: 避免循环中多次更新
- **WHEN** 赏赐多个道具时
- **THEN** 不应在循环中多次更新 `_disciples.value`，而是收集所有变更后一次性更新

#### Scenario: 连续赏赐不崩溃
- **WHEN** 用户快速连续点击赏赐按钮
- **THEN** 应用不应闪退，每次赏赐都应正常完成

### Requirement: 修复数据不一致问题
系统应确保赏赐过程中使用的数据快照保持一致。

#### Scenario: 使用一致的数据快照
- **WHEN** 执行赏赐操作时
- **THEN** 应使用同一份 disciple 数据快照完成整个操作，避免中途数据被其他协程修改

#### Scenario: 自动处理道具异步执行
- **WHEN** 赏赐包含丹药、装备或功法时
- **THEN** 自动处理逻辑应在赏赐完成后异步执行，避免阻塞赏赐流程

### Requirement: 添加线程安全保护
系统应为赏赐操作添加线程安全保护。

#### Scenario: 使用同步锁保护
- **WHEN** 执行赏赐操作时
- **THEN** 应使用同步机制（如 Mutex 或 synchronized）防止并发冲突

## MODIFIED Requirements
无

## REMOVED Requirements
无
