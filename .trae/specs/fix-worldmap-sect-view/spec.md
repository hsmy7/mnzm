# 世界地图宗门查看功能修复 Spec

## Why
世界地图中点击宗门标记时，`onMarkerClick` 回调为空，没有弹出查看按钮和宗门详情界面，导致玩家无法在世界地图上直接查看宗门信息。

## What Changes
- 在 `WorldMapDialog` 中实现 `onMarkerClick` 回调，点击宗门时显示宗门详情对话框
- 创建 `SectDetailDialog` 组件，显示宗门的详细信息
- 宗门详情对话框包含：宗门名称、等级、好感度、弟子信息（如已侦查）、盟友状态、操作按钮

## Impact
- Affected specs: 世界地图、宗门外交
- Affected code: 
  - `MainGameScreen.kt` - `WorldMapDialog` 组件，新增 `SectDetailDialog` 组件
  - `WorldMapScreen.kt` - 无需修改

## ADDED Requirements

### Requirement: 世界地图宗门点击响应
系统应该在玩家点击世界地图上的宗门标记时，弹出宗门详情对话框。

#### Scenario: 点击宗门显示详情
- **WHEN** 玩家在世界地图上点击宗门标记
- **THEN** 系统应该弹出宗门详情对话框
- **AND** 对话框显示宗门的详细信息

### Requirement: 宗门详情对话框显示
宗门详情对话框应该显示宗门的完整信息。

#### Scenario: 宗门详情显示基本信息
- **WHEN** 宗门详情对话框打开时
- **THEN** 显示宗门名称
- **AND** 显示宗门等级（如：小型宗门、中型宗门等）
- **AND** 显示好感度数值和颜色（根据好感度范围显示不同颜色）
- **AND** 显示是否为盟友

#### Scenario: 宗门详情显示侦查信息
- **WHEN** 宗门已有侦查信息（scoutInfo 不为空）
- **THEN** 显示弟子总数
- **AND** 显示最高境界
- **AND** 显示弟子境界分布

#### Scenario: 宗门详情显示操作按钮
- **WHEN** 宗门详情对话框打开时
- **THEN** 显示"送礼"按钮
- **AND** 显示"结盟"按钮（好感度>=90或已是盟友时可用）
- **AND** 显示"交易"按钮
- **AND** 显示"关闭"按钮

### Requirement: 玩家宗门特殊处理
玩家自己的宗门应该有特殊的显示方式。

#### Scenario: 点击玩家宗门
- **WHEN** 玩家点击自己的宗门标记
- **THEN** 显示宗门详情对话框
- **AND** 不显示好感度和操作按钮
- **AND** 显示"这是您的宗门"标识

## MODIFIED Requirements
无

## REMOVED Requirements
无
