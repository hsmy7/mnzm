# 规则：Dialog 键盘防频闪保护

**核心法则：每个包含输入框的容器必须恰好使用一种键盘避让机制，禁止 `ADJUST_PAN` 与 `imePadding` 叠加（pan + padding 双重位移 = 国产 ROM 键盘振荡频闪的历史根因配方）。**

## 根因

Xiaomi HyperOS / OPPO ColorOS / Vivo FuntouchOS 等国产 ROM 上，键盘弹出期间 IME insets 每帧变化；若同一窗口同时存在**窗口级平移（ADJUST_PAN）**与 **Compose 级布局压缩（imePadding）**，内容发生双重位移，触发 IME 状态误报的竞态条件，形成"键盘弹出 → 收起 → 再弹出"的振荡回路（界面闪屏）。

佐证（2026-07 行业调研）：
- Google IssueTracker #229378542：imePadding 在 Dialog 窗口内不可靠
- Xiaomi MIUI/HyperOS 已知缺陷：imePadding 在 Dialog 窗口上无法正确处理 keyboard insets
- StackOverflow 社区共识：adjustPan 是 Compose Dialog 输入框的最佳实践（但必须与 imePadding 二选一）

## 双机制避让（2026-08-06 根治后规则）

| 渲染上下文 | 唯一避让机制 | 实现 |
|-----------|------------|------|
| **平台 Dialog 窗口内**（Compose `Dialog()` 创建的独立 Window，如 `UnifiedGameDialog`/`StandardPromptDialog`/`SmallScreenDialog`） | 窗口级 `SOFT_INPUT_ADJUST_PAN`（不做 resize，仅平移，切断振荡回路） | 容器内置 `DialogSoftInputGuard()`（默认 ADJUST_PAN）；**窗口内严禁再加 `imePadding`** |
| **Activity 窗口内**（内联 Box 覆盖层，如 `InlineStandardPromptDialog`/`PlantingDialog`） | `adjustResize`（manifest 已配置）+ Compose `imePadding` = Google 官方标准组合 | 无需 `DialogSoftInputGuard`（保持 manifest 默认）；覆盖层挂 `.imePadding()` |

`InlineStandardPromptDialog`（含文本输入的对话框统一使用）已内置**双上下文自动检测**：通过 `isInsideDialogWindow(LocalView.current)` 判断是否处于平台 Dialog 窗口内——Activity 层应用 `imePadding`，Dialog 窗口内自动禁用（外层窗口已有 ADJUST_PAN）。**调用方无需关心，也不要手动叠加任何避让。**

## 哪些容器已自带正确避让

| 容器 | 形态 | 避让机制 | 备注 |
|------|------|---------|------|
| `UnifiedGameDialog` | 平台 Dialog 窗口 | `DialogSoftInputGuard(ADJUST_PAN)` | 无输入框对话框直接使用；含输入框时内层禁用 imePadding |
| `StandardPromptDialog` | 平台 Dialog 窗口 | `DialogSoftInputGuard(ADJUST_PAN)` | 同上 |
| `SmallScreenDialog` | 平台 Dialog 窗口 | `DialogSoftInputGuard(ADJUST_PAN)` | 同上 |
| `InlineStandardPromptDialog` | 内联 Box 覆盖层（无平台窗口） | 双上下文自动：Activity 层 `imePadding` / Dialog 窗口内无（外层 ADJUST_PAN） | **含文本输入的对话框（创建宗门/改名/兑换码/出售数量）统一使用此组件** |
| `PlantingDialog` | 内联全屏覆盖层 | Activity 窗口 ADJUST_PAN 单一避让（容器内置 guard） | 已内联 + 单一避让，稳定 |

## 判断法则

新增输入框时，按此规则判断：

```
新增的输入框放在哪里？
  ├─ InlineStandardPromptDialog（文本输入）
  │  → ✅ 不处理，组件已自动双上下文避让
  ├─ StandardPromptDialog / UnifiedGameDialog / SmallScreenDialog（平台 Dialog 窗口）
  │  → ✅ 不处理，窗口已有 ADJUST_PAN；内层严禁 imePadding
  ├─ PlantingDialog
  │  → ✅ 同上
  └─ 新创建的自定义 Dialog { } 或 Box overlay
     → 🔴 二选一：
        ├─ 平台 Dialog 窗口 → 顶部调用 DialogSoftInputGuard()（ADJUST_PAN），
        │    禁止 imePadding
        └─ Activity 层 Box 覆盖层 → 挂 imePadding()（保持 manifest adjustResize），
             不要调用 DialogSoftInputGuard()
```

**社交扩展（2026-08-04 起）：** 未来社交/排行界面的搜索框、好友备注输入框同样按此法则判断——优先放入 `InlineStandardPromptDialog`；自定义容器必须严格二选一。

## 违规后果

- 平台 Dialog 窗口内叠加 `imePadding`（pan + padding 双重位移）→ 国产 ROM 键盘反复弹出/收起，界面闪屏，输入无法正常使用
- 无任何避让的 Dialog + 输入框 → 键盘遮挡输入框或触发 adjustResize 振荡
- 复现条件：HyperOS / ColorOS / FuntouchOS + 含输入框对话框 + 输入框获得焦点

## 注意点

- `DialogSoftInputGuard` 支持两种窗口类型：平台 `DialogWindowProvider`（Compose `Dialog`）和 `Activity.window`（Box overlay 覆盖层），自动检测无需区分；如果找不到目标窗口（极少见边缘情况）会 `Log.w` 后返回，不影响功能
- 保护的是**容器存在期间**的窗口 softInputMode，容器销毁后自动恢复，无副作用
- 含输入框的对话框应使用 `InlineStandardPromptDialog` 而非平台 Dialog 容器（2026-08-06 根治决策：平台 Dialog 窗口与 IME 的交互在国产 ROM 上不可靠，历史上 OPPO/Vivo/HyperOS 三系均复现，见 `docs/adr/dialog-system-refactoring.md`）
