# 规则：Dialog 键盘防频闪保护

**所有包含输入框的对话框必须使用 `DialogSoftInputGuard()` 保护**，防止 Xiaomi HyperOS 上键盘反复弹出收起的频闪问题。

## 根因

Xiaomi HyperOS 上 `adjustResize` + 布局变化会触发 IME 状态误报的竞态条件，导致键盘震荡回路。

## 保护机制

`DialogSoftInputGuard` 在 Composable 挂载期间将目标窗口的 `windowSoftInputMode` 临时切换为 `SOFT_INPUT_ADJUST_NOTHING`，卸载时自动恢复。工作在**容器级别**——一个容器只需要一次 `DialogSoftInputGuard()` 调用，内部任意多个输入框均受保护。

```kotlin
@Composable
fun DialogSoftInputGuard(
    mode: Int = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING
) {
    val dialogWindow = generateSequence(LocalView.current) { it.parent as? View }
        .filterIsInstance<DialogWindowProvider>()
        .firstOrNull()?.window
    val targetWindow = dialogWindow
        ?: (LocalContext.current as? Activity)?.window
        ?: return
    val originalMode = remember { targetWindow.attributes.softInputMode }
    DisposableEffect(targetWindow) {
        targetWindow.setSoftInputMode(mode)
        onDispose { targetWindow.setSoftInputMode(originalMode) }
    }
}
```

## 哪些容器已自带保护

以下容器内部已有 `DialogSoftInputGuard()`，**新增输入框时不需要重复调用**：

| 容器 | 保护位置 | 说明 |
|------|---------|------|
| `StandardPromptDialog` | 第109行 | 平台 `Dialog` 窗口 |
| `InlineStandardPromptDialog` | 第246行 | Box overlay 覆盖层 |
| `UnifiedGameDialog` | 第67行 | 统一游戏对话框 |
| `PlantingDialog` | 第78行 | 灵田种植全屏对话框 |

**注意：** 在 SettingsTab.kt 中还有两处内联 Box overlay（兑换码对话框第86行、自动存档间隔对话框第375行），它们也手动调用了 `DialogSoftInputGuard()`。后续如需在 SettingsTab 新增输入框对话框，同样需要手动添加。

## 判断法则

新增输入框时，按此规则判断：

```
新增的输入框放在哪里？
  ├─ StandardPromptDialog / InlineStandardPromptDialog / UnifiedGameDialog
  │  → ✅ 不处理，容器已自带保护
  ├─ PlantingDialog
  │  → ✅ 同上
  ├─ 已受保护的 SettingsTab 内联覆盖层内
  │  → ✅ 同上
  └─ 新创建的自定义 Dialog { } 或 Box overlay
     → 🔴 必须在 Composable composition 顶部显式调用 DialogSoftInputGuard()
```

**社交扩展（2026-08-04 起）：** 未来社交/排行界面的搜索框、好友备注输入框同样按此法则判断——放入上述自带保护容器内则无需处理，自定义容器必须显式调用 `DialogSoftInputGuard()`。

## 违规后果

- 无保护的自定义 Dialog + 输入框在 Xiaomi HyperOS 设备上会触发键盘频闪
- 表现：键盘反复弹出/收起，导致输入无法正常使用
- 复现条件：HyperOS 系统 + `dialog` 窗口 + 输入框焦点

## 注意点

- `DialogSoftInputGuard` 支持两种窗口类型：平台 `DialogWindowProvider`（Compose `Dialog`）和 `Activity.window`（Box overlay 覆盖层）
- 两种路径都会自动检测，无需开发者区分
- 如果找不到目标窗口（极少见边缘情况），`DialogSoftInputGuard` 会静默返回，不影响功能
- 保护的是 **容器存在期间** 的窗口 softInputMode，容器销毁后自动恢复，无副作用
