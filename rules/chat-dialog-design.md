# 规则：聊天对话框布局设计标准

**所有聊天/对话类对话框必须使用 `UnifiedGameDialog(mode=DialogMode.Full)` 作为容器**，禁止手写 `Box` + `CloseButton` 堆叠布局。

## 根因

手写 `Box` 布局中，`CloseButton` 和 `Row(fillMaxSize)` 在同一级堆叠，`fillMaxSize()` 的内容区声明在关闭按钮之后（z-order 更高）时会完全覆盖关闭按钮，导致按钮不可见且不可点击。

## 标准布局结构

```kotlin
UnifiedGameDialog(
    onDismissRequest = onDismiss,
    title = "对话框标题",           // 标题显示在 header 行中央
    mode = DialogMode.Full,        // 全屏模式
    scrollableContent = false,     // 内容区自身管理滚动
    backgroundRes = bgRes          // 背景图资源
) {
    Row(modifier = Modifier.fillMaxSize()) {
        // 左侧面板（可选，如弟子头像/宗门信息）
        LeftPanel(..., modifier = Modifier.weight(0.2f).fillMaxHeight())
        VerticalDivider(modifier = Modifier.fillMaxHeight(), ...)
        // 右侧面板（对话内容 + 选项按钮）
        RightPanel(..., modifier = Modifier.weight(0.8f).fillMaxHeight())
    }
}
```

## 层级结构

```
UnifiedGameDialog
  ├── 遮罩层 (dark overlay)
  ├── 内层 Box
  │   ├── 背景图 (matchParentSize)
  │   └── Column(fillMaxSize)          ← 关键：使用 Column 而非 Box 堆叠
  │       ├── header Row               ← 关闭按钮在这里！
  │       │   ├── Text(标题, 居中)
  │       │   └── Row(align=CenterEnd)
  │       │       ├── headerActions?()
  │       │       └── CloseButton       ← 与内容区垂直分离，不会重叠
  │       └── content Column(weight=1f) ← 内容区在 header 下方
  │           └── Row(fillMaxSize)
  │               ├── LeftPanel (20%)
  │               ├── VerticalDivider
  │               └── RightPanel (80%)
```

**与手写 Box 的关键区别：** `UnifiedGameDialog` 使用 `Column` 布局，关闭按钮在 header 行（第 0 行），内容区用 `weight(1f)` 占据剩余空间（第 1+ 行），二者**天然垂直分离**，杜绝 z-order 重叠。

## 实现要点

1. **关闭回调**——通过 `onDismissRequest` 传入，不手写 `BackHandler`/`CloseButton`
2. **标题**——`title` 参数显示在 header 中央，双人对话场景显示对方名称（如弟子姓名、宗门名）
3. **左右分栏**——左侧 20% 展示对方信息（头像/名称/境界），右侧 80% 展示对话内容 + 选项按钮
4. **背景**——`backgroundRes` 传入统一对话背景资源（`dialogue_bg`）
5. **聊天内容滚动**——右侧面板内部自管理 `verticalScroll`，`scrollableContent = false` 防止外层重复滚动
6. **聊天气泡**——使用 `dialogue_bubble_left`/`dialogue_bubble_right` 精灵图，最大宽度为屏幕 65%

## 参考实现

- ✅ `SectDiplomacyDialog.kt` — 外交聊天（标准实现）
- ✅ `DiscipleChatDialog.kt` — 弟子交谈（重构后遵循此标准）
- ❌ 禁止手写 `Box(fillMaxSize) + CloseButton(align=TopEnd) + Row(fillMaxSize)` 模式
