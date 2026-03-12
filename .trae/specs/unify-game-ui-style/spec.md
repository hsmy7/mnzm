# 统一游戏界面风格 Spec

## Why
游戏界面风格不统一，按钮样式各异，开关控件使用Switch而非Checkbox，道具卡片样式在不同界面存在差异。需要统一整个游戏的视觉风格，提升用户体验的一致性和专业性。

## What Changes
- 统一界面背景为白色
- 统一按钮样式：黑色字体 + 浅棕色边框 + 米色背景
- 统一开关控件为勾选框（Checkbox）
- 统一道具卡片为正方形样式（以仓库道具卡片为基准）
- 确保道具卡片包含选中状态、查看按钮、道具详情界面、数量显示

## Impact
- Affected specs: 所有游戏界面
- Affected code:
  - `Color.kt` - 添加统一的颜色常量
  - `ItemCard.kt` - 统一道具卡片组件
  - `MainGameScreen.kt` - 主界面按钮和开关
  - `TournamentScreen.kt` - 比武大会开关
  - `SalaryConfigScreen.kt` - 薪资配置开关
  - `CreateWarTeamDialog.kt` - 创建战队勾选框
  - 所有使用按钮和开关的界面文件

## ADDED Requirements

### Requirement: 统一界面背景色
系统应确保所有游戏界面使用白色背景。

#### Scenario: 对话框背景为白色
- **WHEN** 用户打开任意对话框
- **THEN** 对话框背景应为白色（`Color.White`）

#### Scenario: 卡片背景为白色
- **WHEN** 用户查看任意卡片组件
- **THEN** 卡片背景应为白色（`Color.White`）

### Requirement: 统一按钮样式
系统应确保所有按钮使用统一的样式：黑色字体、浅棕色边框、米色背景。

#### Scenario: 按钮使用米色背景
- **WHEN** 用户查看任意按钮
- **THEN** 按钮背景应为米色（`Color(0xFFF5DEB3)`）

#### Scenario: 按钮使用黑色字体
- **WHEN** 用户查看任意按钮
- **THEN** 按钮文字应为黑色（`Color.Black`）

#### Scenario: 按钮使用浅棕色边框
- **WHEN** 用户查看任意按钮
- **THEN** 按钮边框应为浅棕色（`Color(0xFFD2B48C)`）

#### Scenario: 按钮高度一致
- **WHEN** 用户查看任意按钮
- **THEN** 按钮高度应为 `32.dp`

#### Scenario: 按钮字体大小一致
- **WHEN** 用户查看任意按钮
- **THEN** 按钮文字大小应为 `10.sp`

### Requirement: 统一开关为勾选框
系统应将所有Switch开关控件替换为Checkbox勾选框。

#### Scenario: 宗门政策开关使用勾选框
- **WHEN** 用户查看宗门政策设置
- **THEN** 开关应为勾选框（Checkbox）而非Switch

#### Scenario: 比武大会开关使用勾选框
- **WHEN** 用户查看比武大会设置
- **THEN** 开关应为勾选框（Checkbox）而非Switch

#### Scenario: 薪资配置开关使用勾选框
- **WHEN** 用户查看薪资配置
- **THEN** 开关应为勾选框（Checkbox）而非Switch

### Requirement: 统一道具卡片样式
系统应确保所有道具卡片使用正方形样式，以仓库道具卡片为基准。

#### Scenario: 道具卡片为正方形
- **WHEN** 用户查看任意道具卡片
- **THEN** 卡片应为正方形（`aspectRatio(1f)` 或固定尺寸 `68.dp`）

#### Scenario: 道具卡片有选中状态
- **WHEN** 用户选中一个道具卡片
- **THEN** 卡片应显示金色边框（`Color(0xFFFFD700)`，宽度 `3.dp`）

#### Scenario: 道具卡片有查看按钮
- **WHEN** 用户选中一个道具卡片
- **THEN** 卡片右上角应显示查看按钮

#### Scenario: 查看按钮样式统一
- **WHEN** 用户查看选中卡片的查看按钮
- **THEN** 按钮应为金色背景（`Color(0xFFFFD700)`）、白色文字、字体大小 `7.sp`

#### Scenario: 道具卡片显示数量
- **WHEN** 道具数量大于1
- **THEN** 卡片右下角应显示数量（如 `x5`）

#### Scenario: 道具卡片有稀有度边框
- **WHEN** 用户查看道具卡片
- **THEN** 卡片应有对应稀有度颜色的边框（宽度 `2.dp`）

### Requirement: 统一道具详情界面
系统应确保道具详情界面样式统一。

#### Scenario: 详情对话框背景为白色
- **WHEN** 用户点击查看按钮查看道具详情
- **THEN** 详情对话框背景应为白色

#### Scenario: 详情对话框标题使用稀有度颜色
- **WHEN** 用户查看道具详情
- **THEN** 道具名称应使用对应稀有度颜色

#### Scenario: 详情对话框内容清晰展示
- **WHEN** 用户查看道具详情
- **THEN** 应清晰展示道具类型、属性、效果等信息

## MODIFIED Requirements
无

## REMOVED Requirements
无
