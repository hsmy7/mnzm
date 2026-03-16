# 游戏切换应用后状态重置问题修复

## Why
用户反馈切换应用后再回到游戏，游戏会变成第一年。这是因为Activity被系统回收后重新创建时，ViewModel和GameEngine也被重新初始化，导致游戏数据恢复为默认值（第一年），而不是从存档中加载。

## What Changes
- 在GameActivity中使用`savedInstanceState`保存当前存档槽位
- Activity重建时优先使用保存的槽位从存档重新加载游戏
- 在GameViewModel中添加`isGameAlreadyLoaded()`方法判断游戏是否已加载
- 简化加载逻辑：如果ViewModel数据未加载，则从存档加载

## Impact
- Affected specs: 游戏状态持久化
- Affected code: 
  - `GameActivity.kt` - 添加状态保存和恢复逻辑
  - `GameViewModel.kt` - 添加游戏加载状态管理

## ADDED Requirements

### Requirement: 游戏状态持久化
系统应当在Activity生命周期变化时正确保存和恢复游戏状态。

#### Scenario: Activity被系统回收后恢复
- **WHEN** 用户切换到其他应用，系统因内存不足回收GameActivity
- **AND** 用户返回游戏，Activity被重新创建
- **THEN** 游戏应从存档中正确加载，而不是重置为第一年

#### Scenario: 正常新游戏启动
- **WHEN** 用户从存档选择界面选择新游戏
- **THEN** 游戏应正常初始化为第一年

#### Scenario: 正常加载存档
- **WHEN** 用户从存档选择界面选择已有存档
- **THEN** 游戏应正确加载存档中的年份和状态

## Root Cause Analysis

问题的根本原因：

1. `GameActivity` 使用 `repeatOnLifecycle(Lifecycle.State.CREATED)` 触发游戏加载
2. 当 Activity 被系统销毁并重新创建时，`onCreate` 会重新执行
3. `viewModels()` 在 Activity 重新创建时会返回新的 ViewModel 实例
4. `GameEngine` 被重新初始化，`gameData` 恢复为默认值（`gameYear = 1`）
5. 之前的修复方案只是跳过了加载，但没有从存档恢复数据

## Solution

正确的修复方案：

1. 在 `onSaveInstanceState` 中保存当前存档槽位 `currentSlot`
2. Activity 重建时，优先使用 `savedInstanceState` 中的槽位
3. 检查 `viewModel.isGameAlreadyLoaded()`，如果未加载则从存档重新加载
4. 这样无论 ViewModel 是否存活，都能正确恢复游戏状态

### 流程图

```
Activity 重建时：
├── 获取 savedSlot = savedInstanceState.getInt(KEY_CURRENT_SLOT)
├── 获取 intentSlot = intent.getIntExtra(EXTRA_SLOT)
├── slot = savedSlot > 0 ? savedSlot : intentSlot
├── 检查 viewModel.isGameAlreadyLoaded()
│   ├── true: 跳过加载（ViewModel 数据仍有效）
│   └── false: 调用 viewModel.loadGameFromSlot(slot) 从存档恢复
└── 游戏正确显示存档中的年份
```
