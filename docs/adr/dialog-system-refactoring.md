# Dialog 系统重构：DialogManager + UnifiedGameDialog 统一渲染路径

*日期: 2026-07-13 | 来源数: 25+ | 置信度: 高*

## Context

用户报告三个对话框关闭按钮问题：
1. 部分关闭按钮不在右上角
2. 关闭按钮被导航栏/状态栏遮挡
3. 点击关闭按钮无响应

经排查，根因是对话框系统有三套并行渲染体系：平台 Dialog（正确）、Deprecated 组件（未清理）、内联覆盖层（无 `decorFitsSystemWindows` 保护）。内联覆盖层直接嵌入 Activity 视图树，在 `enableEdgeToEdge()` 下全屏填充包括系统栏区域，关闭按钮在状态栏后方，触摸落在系统 UI 上。

## Decision

1. **DialogManager 接口 + Hilt 实现** — 从 GameViewModel 拆出独立 DialogManager 接口（`core/domain` 零 Android 依赖），`DialogManagerImpl` 作为 Hilt `@Singleton`，以 `StateFlow<DialogEntry?>` 作为单一真相源
2. **统一渲染路径** — 所有全屏覆盖层（`FullScreenOverlay` → `UnifiedGameDialog(Full)`）改用 Compose `Dialog()` 窗口，确保 `decorFitsSystemWindows = false` 保护
3. **统一关闭路径** — 消除 `closeCurrentDialog()` → Channel → LaunchedEffect 的 Path B 间接跳转，全部走 `dialogManager.close()` 直接路径
4. **CloseButton 48dp 触摸目标** — 符合 Material Design 最小触摸标准
5. **对抗性审查** — 3 个 Agent（边界狂魔/状态破坏者/数据篡改者）共发现 20+ 项问题，全部修复

## Consequences

- 正面：所有对话框通过 Compose `Dialog()` 窗口渲染，inset 保护统一；单关闭路径降低维护成本；`CloseButton` 触摸合规
- 负面：`DialogManager` 是 `@Singleton`，跨 Activity 生命周期状态残留（修复：`onCleared()` 中 `dialogManager.close()`）
- 保留：`InlineStandardPromptDialog` 因 HyperOS IME 兼容性保持在覆盖层形态
- 保留：`_navigationEvents` Channel 仍用于 `NavigationDelegate` 的 `buildingsTab` → ViewModel 导航事件

## 更正记录（2026-08-06）：InlineStandardPromptDialog 恢复内联覆盖层形态

**回归**：2026-07-04 提交 2133597c 为修复"设置界面子对话框全屏居中"（SettingsTab 4 处**无输入**子对话框改用平台 Dialog 窗口），将 `InlineStandardPromptDialog` 也一并改回平台 Dialog 窗口——过度泛化：含文本输入的对话框（创建宗门/改名/兑换码/出售数量）被拖入平台 Dialog 窗口 + IME 的不可靠组合。此后 4.0.66（OPPO/Vivo）、4.0.82（小米 HyperOS）两轮补丁（Guard 移入 Dialog 内部、ADJUST_NOTHING→ADJUST_PAN、view.post 聚焦）均在新地基上打补丁，换一个 OEM 再爆频闪。

**根治（2026-08-06）**：`InlineStandardPromptDialog` 恢复为真正的内联 Box 覆盖层（对齐名称/KDoc/本 ADR 保留记录的设计），消除平台 Dialog 窗口与 IME 的全部交互：
- 移除 `DialogSoftInputGuard`/`DialogSystemBarGuard`（无新窗口）；保留 `DialogFocusGuard`（#3026 语义，Activity 与 Dialog 窗口上下文均适用）
- **条件 `imePadding`**（关键决策）：通过 `isInsideDialogWindow()` 自动检测渲染上下文——Activity 层应用 `imePadding`（manifest adjustResize + imePadding 官方标准组合）；嵌套在平台 Dialog 窗口内时**禁用**（外层窗口已有 ADJUST_PAN 单一避让，叠加即重现 pan + padding 双重位移频闪配方）
- 调用点适配：RenameDialog 聚焦 delay(100)→view.post + 补 keyboardActions(onDone)；RenameSectDialog 加 scrimEnabled=false（GameOverlayHost 单例遮罩）；RenameDiscipleDialog 移入 DiscipleDetailDialog 内容 lambda（否则内联后被 Dialog 窗口遮挡不可见）
- 含文本输入的对话框统一使用 `InlineStandardPromptDialog`；平台 Dialog 容器（UnifiedGameDialog 等）仅用于无文本输入场景
- 机制规则见 `rules/dialog-soft-input-guard.md`（双机制避让法则）
