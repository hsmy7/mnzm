# 修复快捷操作板块战斗日志按钮缺失问题

## Why
`MainGameScreen.kt` 的快捷操作板块缺少"战斗日志"按钮，导致玩家无法查看战斗历史记录。同时 `SectMainScreen.kt` 文件包含了 `MainGameScreen.kt` 需要的多个组件，但该文件本身未被作为屏幕使用，造成代码组织混乱。

## What Changes
- 在 `MainGameScreen.kt` 的 `QuickActionPanel` 函数中添加"战斗日志"按钮
- 添加 `showBattleLogDialog` 状态监听和 `BattleLogListDialog` 对话框显示逻辑
- 将 `SectMainScreen.kt` 中的以下组件移动到 `MainGameScreen.kt`：
  - `SecretRealmDialog` 及其依赖组件
  - `DispatchTeamDialog`
  - `ExplorationTeamDialog`
  - `BattleLogListDialog` 及其依赖组件
  - `BattleLogDetailDialog` 及其依赖组件
- 删除 `SectMainScreen.kt` 文件

## Impact
- Affected code: 
  - `MainGameScreen.kt`：添加战斗日志按钮和相关对话框组件
  - 删除 `SectMainScreen.kt` 文件

## ADDED Requirements
### Requirement: 战斗日志按钮
系统应在快捷操作板块中提供"战斗日志"按钮，允许玩家查看历史战斗记录。

#### Scenario: 用户点击战斗日志按钮
- **WHEN** 用户在快捷操作板块点击"战斗日志"按钮
- **THEN** 系统打开战斗日志对话框，显示历史战斗记录列表

## REMOVED Requirements
### Requirement: SectMainScreen 屏幕组件
**Reason**: 该屏幕文件未被作为主屏幕使用，但其组件被 `MainGameScreen.kt` 引用。将组件移动到 `MainGameScreen.kt` 后删除该文件。
**Migration**: 将所需组件移动到 `MainGameScreen.kt`
