# 修复更换功法后功法槽位显示问题 Spec

## Why
用户在弟子详情界面更换功法后，功法槽位显示的仍然是原功法，而不是新更换的功法。这导致用户界面与实际数据不一致，影响用户体验。

## What Changes
- 修复 `DiscipleDetailDialog` 中功法更换后对话框状态未正确更新的问题
- 确保功法更换后，`ManualDetailDialog` 显示的功法信息与实际数据同步

## Impact
- Affected code: 
  - `DiscipleDetailScreen.kt` - 功法详情对话框状态管理
  - `ManualDetailDialog` 组件

## ADDED Requirements

### Requirement: 功法更换后状态同步
系统应在功法更换成功后，正确更新对话框状态，确保显示的功法信息与实际数据一致。

#### Scenario: 功法更换成功
- **WHEN** 用户在功法详情对话框中选择新功法并确认更换
- **THEN** 系统应关闭当前对话框，并在功法槽位中显示新功法

#### Scenario: 功法更换后重新打开对话框
- **WHEN** 用户更换功法后，再次点击该功法槽位
- **THEN** 系统应显示新功法的详情信息

## Root Cause Analysis

经过代码分析，发现问题可能出在以下位置：

1. **`ManualDetailDialog` 中的 `manual` 参数问题**：
   - `manual` 参数来自 `showManualDetailDialog` 状态变量
   - 当功法更换后，`showManualDetailDialog` 仍然保持旧功法对象
   - 虽然对话框会关闭（`showManualDetailDialog = null`），但如果存在任何状态同步延迟，可能导致显示问题

2. **`availableManuals` 的计算**：
   - 使用 `remember` 缓存，依赖 `allManuals` 和 `currentManualIds`
   - `currentManualIds` 是 `disciple.manualIds`，会随着功法更换而更新
   - 但 `manual` 对象本身不会更新

## Solution

在 `ManualDetailDialog` 中，当功法更换成功后，确保对话框正确关闭，避免任何状态不一致的情况。
