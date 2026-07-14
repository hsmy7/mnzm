# 新增对话框检查清单

从 v4.0.49 起，`DialogRoute` 已删除，对话框由 `DialogType` 单一类型层级驱动。新增对话框的标准流程如下。

## 标准流程

### 1. 注册类型

**文件：** `core/domain/.../dialog/DialogType.kt`

```kotlin
sealed interface DialogType {
    data object MyNewDialog : DialogType                              // 无参数
    data class MyParamDialog(val buildingInstanceId: String) : DialogType // 带参数
}
```

### 2. 渲染界面

**文件：** `feature/game/.../components/GameOverlayHost.kt`

在 `when (val type = currentDialogType)` 穷举分支中添加新分支：

```kotlin
is DialogType.MyNewDialog -> {
    val data by viewModel.xxx.collectAsStateWithLifecycle()
    MyNewDialog(data = data, onDismiss = onDismiss)
}
```

编译期穷举保证不会漏掉。

### 3. 额外情况

| 场景 | 还要改的地方 |
|------|-------------|
| 从建筑点击打开 | `MainGameScreen.kt` 的 `when (def.key)` 加一条 |
| 从 `NavigationDelegate` 导航事件打开 | `GameRoute.toDialogType()` 映射加一条 |
| 从 `SectDelegate` 打开 | 直接调 `viewModel.navigateToDialog(DialogType.XXX)` |

## 检查清单

- [ ] `DialogType.kt` 已注册新类型
- [ ] `GameOverlayHost.kt` when 分支已添加（exhaustive 保证）
- [ ] 使用了 `UnifiedGameDialog(mode = Half/Full/Auto)` 容器（自带 `DialogSystemBarGuard` + `DialogSoftInputGuard` + 60% 遮罩）
- [ ] 聊天/对话类使用了 `UnifiedGameDialog(mode = Full)`（chat-dialog-design.md）
- [ ] 精灵图已在 SpriteResRegistry 注册
- [ ] 聚焦域：若显示实时数据（进度条/倒计时），更新 FocusDomain

## 原理

```
之前：DialogType → toDialogRoute → DialogRoute → StateFlow 桥接 → UI（4 步，易漏）
现在：DialogType ───────────────────────────────────────────────→ UI（2 步，编译安全）
```

`DialogType` 位于 `core:domain` 模块，零 Android 依赖。`DialogManager.currentDialog` 是唯一的 StateFlow 真相源。不存在需要同步的第二套类型层级。
