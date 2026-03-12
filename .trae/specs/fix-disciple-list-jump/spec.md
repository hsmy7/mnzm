# 修复界面布局跳动问题 Spec

## Why
多个界面在进入时会突然向下移动，顶部出现空白区域。这是因为：
1. `GameActivity` 和 `MainActivity` 都已经隐藏了状态栏
2. 但多个界面仍然使用了 `statusBarsPadding()`
3. 这导致了布局冲突：界面先显示有 padding 的状态，然后状态栏隐藏后 padding 变为 0，造成界面跳动

## What Changes
1. **移除所有 statusBarsPadding**：由于 Activity 已经隐藏了状态栏，所有界面不应该再使用 `statusBarsPadding()`
2. **确保布局一致性**：所有界面的布局应该从屏幕顶部开始，不需要额外的状态栏 padding

## Impact
- Affected specs: 主游戏界面、所有 Tab 页面、主界面、存档选择界面
- Affected code: 
  - `MainGameScreen.kt` - 第105行
  - `SectMainScreen.kt` - 第73行
  - `MainActivity.kt` - 第245行和第530行
  - `SaveSelectScreen.kt` - 第41行

## ADDED Requirements

### Requirement: 界面布局稳定
所有界面在进入时应该保持稳定的布局位置，不应该出现突然的上下跳动。

#### Scenario: 进入弟子界面布局稳定
- **WHEN** 用户切换到弟子界面时
- **THEN** 界面应该从顶部开始显示
- **AND** 不应该出现顶部空白区域突然消失的情况

#### Scenario: 进入主界面布局稳定
- **WHEN** 用户进入主界面时
- **THEN** 界面应该从顶部开始显示
- **AND** 不应该出现顶部空白区域突然消失的情况

#### Scenario: 进入存档选择界面布局稳定
- **WHEN** 用户进入存档选择界面时
- **THEN** 界面应该从顶部开始显示
- **AND** 不应该出现顶部空白区域突然消失的情况

#### Scenario: 状态栏隐藏后布局正确
- **WHEN** 状态栏被隐藏时
- **THEN** 界面内容应该从屏幕最顶部开始
- **AND** 不应该有多余的 padding

## MODIFIED Requirements

### Requirement: 所有界面布局
移除所有 `statusBarsPadding()`，因为 Activity 已经隐藏了状态栏。

#### Scenario: 所有界面布局正确
- **WHEN** 用户进入任何界面时
- **THEN** 界面内容应该从屏幕最顶部开始
- **AND** 底部导航栏正常显示（如果有）

## REMOVED Requirements
无
