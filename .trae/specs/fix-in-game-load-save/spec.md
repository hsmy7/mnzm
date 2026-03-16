# 修复游戏内读档功能 Spec

## Why
用户在游戏内的设置界面点击"查看存档"按钮后，在弹出的存档对话框中选择一个存档并点击"读取"按钮时，没有任何效果，读档操作未成功执行。这是因为 `GameViewModel.loadGame` 方法中存在一个检查 `_isGameLoaded` 的逻辑，当游戏已经加载时（用户已经在游戏中），该检查会直接返回并忽略读档请求。

## What Changes
- 修改 `GameViewModel.loadGame` 方法，移除或调整 `_isGameLoaded` 检查逻辑，允许在游戏内读取其他存档
- 在读档前需要先停止当前游戏循环
- 读档成功后需要重置游戏状态并重新启动游戏循环

## Impact
- Affected specs: 存档系统
- Affected code: `GameViewModel.kt`

## ADDED Requirements
### Requirement: 游戏内读档功能
系统应允许用户在游戏进行中读取其他存档，而不是忽略读档请求。

#### Scenario: 用户在游戏内读取其他存档
- **WHEN** 用户在游戏内设置界面点击"查看存档"，选择一个非空存档并点击"读取"
- **THEN** 系统应停止当前游戏循环，加载选中的存档数据，并重新启动游戏循环

#### Scenario: 读档失败时显示错误信息
- **WHEN** 用户尝试读取一个损坏或不存在的存档
- **THEN** 系统应显示错误提示信息

## MODIFIED Requirements
### Requirement: loadGame 方法
`GameViewModel.loadGame(saveSlot: SaveSlot)` 方法应支持在游戏已加载的状态下重新加载存档：
1. 停止当前游戏循环
2. 重置 `_isGameLoaded` 标志
3. 加载新存档数据
4. 重新启动游戏循环
