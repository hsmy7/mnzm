# Dialog 遮罩标准

## 标准值

所有半屏界面、小屏界面、提示框的背景遮罩统一使用：

```kotlin
.background(Color(0x99000000)) // 60% 黑色半透明遮罩
```

`0x99000000` = ARGB(153, 0, 0, 0) = 60% 透明度黑色，与 Android 原生 `Dialog` 的 `backgroundDimAmount=0.6` 一致。

## 适用范围

| 界面类型 | 组件 | 状态 |
|---------|------|------|
| 半屏界面 (83%W × 78%H) | `UnifiedGameDialog(mode = Half/Auto)` | 已内置在 `GameDialog.kt` |
| 全屏界面 | `UnifiedGameDialog(mode = Full)` | 已内置（遮罩在 `bg_horizontal` 背景图下层） |
| 提示框 (50%W × 55%H) | `StandardPromptDialog` | 已内置在 `StandardPromptDialog.kt` |
| 小屏界面 (50%W × 55%H) | `SmallScreenDialog` | 使用平台 `Dialog`，自带系统遮罩，无需手动添加 |
| 通用全屏覆盖层 | `GameFullDialog`（已废弃） | 已内置 |
| 内联覆盖层（SettingsTab 子对话框） | 内联 Box overlay | 无遮罩（SettingsTab 本身已是全屏覆盖层，不需要额外遮罩） |

## 规则

1. **`UnifiedGameDialog` 是首选** — 新对话框优先使用 `UnifiedGameDialog`，它已自动包含 `0x99000000` 遮罩，无需手动添加
2. **禁止直接硬编码遮罩颜色** — 新增对话框不得在调用方写 `.background(Color(0x99000000))`，应使用共享组件
3. **内联覆盖层例外** — 如果对话框作为内联覆盖层出现在 `FullScreenOverlay` 内部（如 SettingsTab 的子对话框），不需要遮罩
4. **新增共享对话框组件** — 如果新增自定义对话框组件，必须在外层 Box 添加 `Color(0x99000000)` 遮罩

## 实现参考

```kotlin
// ✅ 正确 — 使用 UnifiedGameDialog（自带遮罩）
UnifiedGameDialog(
    onDismissRequest = onDismiss,
    title = "标题",
    mode = DialogMode.Half
) {
    // content
}

// ✅ 正确 — 使用 StandardPromptDialog（自带遮罩）
StandardPromptDialog(
    onDismissRequest = { },
    title = "提示",
    text = "消息内容",
    confirmLabel = "确定"
)

// ✅ 正确 — 新增自定义对话框时手动加遮罩
Box(
    modifier = Modifier
        .fillMaxSize()
        .background(Color(0x99000000)) // 60% 黑色遮罩
        .clickable(onClick = onDismiss),
    contentAlignment = Alignment.Center
) {
    // content
}
```

## 相关文件

- `core/ui/.../components/GameDialog.kt` — `UnifiedGameDialog` + `GameFullDialog`
- `core/ui/.../components/StandardPromptDialog.kt` — 标准提示框
- `core/ui/.../components/SmallScreenDialog.kt` — 小屏界面（平台 Dialog）
