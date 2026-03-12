# 统一天枢殿与宗门外交按钮样式 Spec

## Why
天枢殿的按钮样式与宗门外交的交易按钮样式不一致，导致用户体验不统一。天枢殿按钮使用深棕色背景+白色文字，而宗门外交按钮使用米色背景+深棕色文字，高度和字体大小也不一致。需要统一为：米色背景+黑色文字+浅棕色边框。

## What Changes
- 统一天枢殿对话框中的按钮样式
- 使用米色背景（`Color(0xFFF5DEB3)`）
- 使用黑色文字（`Color.Black`）
- 使用浅棕色边框（`Color(0xFFD2B48C)`）
- 统一按钮高度为 `32.dp`
- 统一字体大小为 `10.sp`

## Impact
- Affected specs: 天枢殿界面
- Affected code: 
  - `MainGameScreen.kt` - `TianshuHallDialog` 组件中的按钮样式

## ADDED Requirements

### Requirement: 天枢殿按钮样式统一
系统应确保天枢殿对话框中的按钮样式与宗门外交按钮样式一致。

#### Scenario: 天枢殿按钮使用米色背景
- **WHEN** 用户打开天枢殿对话框
- **THEN** 按钮应使用米色背景（`Color(0xFFF5DEB3)`）

#### Scenario: 天枢殿按钮使用黑色文字
- **WHEN** 用户打开天枢殿对话框
- **THEN** 按钮文字应使用黑色（`Color.Black`）

#### Scenario: 天枢殿按钮使用浅棕色边框
- **WHEN** 用户打开天枢殿对话框
- **THEN** 按钮边框应使用浅棕色（`Color(0xFFD2B48C)`）

#### Scenario: 天枢殿按钮高度一致
- **WHEN** 用户打开天枢殿对话框
- **THEN** 按钮高度应为 `32.dp`

#### Scenario: 天枢殿按钮字体大小一致
- **WHEN** 用户打开天枢殿对话框
- **THEN** 按钮文字大小应为 `10.sp`

## MODIFIED Requirements
无

## REMOVED Requirements
无
