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
- [ ] 实时数据：焦点域已移除（CLAUDE.md 6.5），需要实时数据的界面在 ViewModel 中订阅 engine StateFlow 派生，不注册任何焦点域
- [ ] 点击屏幕外可关闭：`onDismissRequest` 已设置，非阻塞交互不得阻止点外关闭

## 活动/排行/社交类界面分组（2026-08-04 起，扩展预留）

未来扩展活动日历/排行榜/好友社交时，除上述标准流程外，还需逐项检查：

### 活动类（历战入口/活动日历/限时活动）

- [ ] 历战入口（`LizhanDialog`）轮转卡片已注册：卡片列表 + 图标 + 描述
- [ ] 活动时间窗三态完整：**未开启 / 开启中 / 已结束**（红色"未开启"文案 + 点击无反应等，参照远古秘境 50 年一现先例）
- [ ] 活动结束后入口状态正确处理（不残留可点击的死入口）

### 排行类（排行榜/段位榜）

- [ ] 赛季/周期状态展示（当前赛季、剩余时间、重置时间）
- [ ] 分层奖励预览（Top1-10 / Top11-100 / 参与奖，避免头部通吃）
- [ ] 数据刷新 + 加载失败重试态（异步数据三态）

### 社交类（好友/异步消息）

- [ ] 异步数据三态：**Loading / Error / Empty** + 重试按钮
- [ ] 输入框防抖（搜索框/备注框，参照 `DialogSoftInputGuard` 规则）
- [ ] 离线时展示上次快照，不空白

### 通用（含网络数据的界面）

- [ ] 数据走 ViewModel StateFlow + 错误走 `BaseViewModel.showError()`（CLAUDE.md 8.3）
- [ ] **禁止在 Composable 内直接发网络请求**（网络层在 UseCase/Facade，UI 只订阅状态）

## 原理

```
之前：DialogType → toDialogRoute → DialogRoute → StateFlow 桥接 → UI（4 步，易漏）
现在：DialogType ───────────────────────────────────────────────→ UI（2 步，编译安全）
```

`DialogType` 位于 `core:domain` 模块，零 Android 依赖。`DialogManager.currentDialog` 是唯一的 StateFlow 真相源。不存在需要同步的第二套类型层级。
