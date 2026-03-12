# 修复结盟错误提示闪退问题 Spec

## Why
当玩家向已有结盟的宗门派出游说弟子时，系统显示"该宗门已有结盟"的错误提示框，点击确认按钮后应用闪退。

## What Changes
- 修改 GameActivity.kt 中错误对话框的确认按钮行为，非致命错误不应关闭 Activity
- 区分致命错误和非致命错误的处理方式

## Impact
- Affected specs: 结盟系统
- Affected code: GameActivity.kt

## 问题分析

### 根本原因
在 GameActivity.kt 第 110-113 行，错误对话框的确认按钮点击时执行：
```kotlin
TextButton(onClick = {
    viewModel.clearErrorMessage()
    finish()  // 这会关闭整个Activity，导致闪退
}) {
    Text("确定")
}
```

当 `requestAlliance` 失败时（如"该宗门已有结盟"），ViewModel 设置 `_errorMessage.value = message`，触发显示错误对话框。用户点击"确定"后，`finish()` 被调用，导致整个 Activity 被销毁，应用闪退。

### 问题代码位置
- GameActivity.kt:104-118 - 错误对话框处理逻辑

## ADDED Requirements

### Requirement: 区分致命错误和非致命错误
系统应当区分致命错误（需要关闭游戏）和非致命错误（只需关闭对话框）。

#### Scenario: 非致命错误处理
- **WHEN** 发生非致命错误（如"该宗门已有结盟"、"灵石不足"等）
- **THEN** 显示错误对话框，点击确认后只关闭对话框，不关闭 Activity

#### Scenario: 致命错误处理
- **WHEN** 发生致命错误（如游戏数据加载失败、存档损坏等）
- **THEN** 显示错误对话框，点击确认后关闭 Activity

## MODIFIED Requirements

### Requirement: 错误消息处理
修改 GameActivity.kt 中的错误对话框逻辑：
- 移除 `finish()` 调用，让非致命错误只关闭对话框
- 或者添加一个标志区分致命/非致命错误
