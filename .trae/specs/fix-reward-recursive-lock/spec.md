# 修复赏赐系统递归锁导致的闪退问题 Spec

## Why
经过深入代码分析，发现赏赐系统存在一个严重的递归锁问题：
1. `rewardItemsToDisciple` 函数使用 `transactionMutex.withLock` 保护赏赐操作
2. 在锁内部，当赏赐包含丹药、装备或功法时，会调用 `processDiscipleItemInstantly`
3. `processDiscipleItemInstantly` 也使用 `transactionMutex.withLock`，导致**同一线程尝试获取已持有的锁**
4. Kotlin 的 Mutex 不是可重入锁，这会导致协程永远挂起，最终表现为应用闪退或ANR

## What Changes
- 修复 `processDiscipleItemInstantly` 中的递归锁问题
- 将 `processDiscipleItemInstantly` 改为无锁版本，依赖调用方已经持有的锁
- 或者将自动处理逻辑移到锁外部异步执行
- 确保赏赐操作的原子性和线程安全性

## Impact
- Affected specs: 赏赐道具、自动使用道具
- Affected code:
  - `GameEngine.kt` - `rewardItemsToDisciple` 函数
  - `GameEngine.kt` - `processDiscipleItemInstantly` 函数

## ADDED Requirements

### Requirement: 修复递归锁问题
系统应确保赏赐操作时不会发生递归锁导致的协程挂起。

#### Scenario: 避免递归锁
- **WHEN** `rewardItemsToDisciple` 持有 transactionMutex 锁时
- **THEN** 内部调用的 `processDiscipleItemInstantly` 不应再次尝试获取该锁

#### Scenario: 赏赐后自动处理正常执行
- **WHEN** 赏赐包含丹药、装备或功法给弟子
- **THEN** 自动使用/装备/学习逻辑应正常执行，不会导致应用挂起或闪退

#### Scenario: 连续赏赐不闪退
- **WHEN** 用户赏赐一次后再次赏赐
- **THEN** 应用不应闪退，每次赏赐都应正常完成

### Requirement: 保持线程安全性
系统应确保修复后赏赐操作仍然是线程安全的。

#### Scenario: 原子性操作
- **WHEN** 执行赏赐操作时
- **THEN** 所有数据修改应在锁保护下完成，保持原子性

#### Scenario: 数据一致性
- **WHEN** 赏赐后自动处理道具时
- **THEN** 应使用与赏赐操作相同的数据快照，确保数据一致性
