# 移除宗门管理功能 Spec

## Why
宗门管理功能目前处于不完整状态（SectMainScreen 中显示"功能开发中"），且其中的弟子槽位管理功能与其他建筑界面（如灵植园、炼丹室等）的功能重复。为简化界面和避免功能冗余，需要移除快捷操作板块中的宗门管理按钮及相关的宗门管理界面和功能。

## What Changes
- 移除快捷操作板块中的"宗门管理"按钮
- 移除宗门管理对话框（SectManagementDialog）及其相关组件
- 移除 ViewModel 中宗门管理对话框的状态和方法
- **注意**：保留 `elderSlots` 数据结构，因为其他建筑界面（灵植园、炼丹室、天工峰等）仍在使用这些数据

## Impact
- 受影响的界面：
  - `SectMainScreen.kt`：移除宗门管理按钮和对话框
  - `MainGameScreen.kt`：移除宗门管理按钮和对话框
  - `GameViewModel.kt`：移除宗门管理对话框状态和方法
- 不受影响：
  - `GameData.kt` 中的 `elderSlots` 数据结构（其他建筑界面仍在使用）
  - 其他建筑界面的长老和弟子槽位管理功能

## ADDED Requirements
### Requirement: 移除宗门管理入口
系统应从快捷操作板块中移除"宗门管理"按钮。

#### Scenario: 快捷操作板块布局变更
- **WHEN** 用户查看游戏主界面
- **THEN** 快捷操作板块不再显示"宗门管理"按钮
- **AND** 快捷操作板块的布局调整为其他按钮重新排列

### Requirement: 移除宗门管理对话框
系统应移除宗门管理对话框及其所有相关组件。

#### Scenario: 点击已移除的按钮
- **WHEN** 用户尝试通过任何方式打开宗门管理对话框
- **THEN** 系统不显示任何对话框

### Requirement: 移除ViewModel中的宗门管理状态
系统应移除 ViewModel 中与宗门管理对话框相关的状态和方法。

#### Scenario: 代码清理完成
- **WHEN** 代码修改完成
- **THEN** `showSectManagementDialog` 状态被移除
- **AND** `openSectManagementDialog()` 方法被移除
- **AND** `closeSectManagementDialog()` 方法被移除

## REMOVED Requirements
### Requirement: 宗门管理界面功能
**Reason**: 功能与其他建筑界面重复，且当前处于不完整状态
**Migration**: 用户可通过各建筑界面（灵植园、炼丹室、天工峰、藏经阁、灵矿、纳徒堂）管理对应的长老和弟子槽位
