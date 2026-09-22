# AGENTS.md — UI 层（:feature:game）

> 本文件是 `android/feature/game/` 的追加规范。**通用规范见仓库根 `AGENTS.md`。**

## ViewModel

- 必须继承 `BaseViewModel`（统一 `showError()` / `showSuccess()` 事件通道）
- 只读 `StateFlow` 暴露状态，**禁止公开 `MutableStateFlow`**
- **禁止直接访问 `GameStateStore`** —— 状态变更一律走 `GameEngine` 方法；
  数据流单向：UI → ViewModel → GameEngine → Service → GameStateStore
- 一个 ViewModel 只驱动一个 Screen；大型 ViewModel 按领域拆 Delegate（`ui/game/delegate/`）
- 界面实时数据直接订阅 engine StateFlow 派生（`map + stateIn` 模式）——
  **焦点域已移除（`FocusDomain` / `InterfaceDomainMap` / `DomainMappingTest` 均不存在），UI 不驱动系统 tick，禁止按旧规则注册**

## 新增对话框

标准流程见 `rules/new-dialog-checklist.md`：注册 `DialogType` → 在 `GameOverlayHost` 的 `when` 添加穷举分支
（编译期保证不漏）。遮罩统一 `Color(0x99000000)`（见 `rules/dialog-scrim-standard.md`）；
聊天 / 对话类必须用 `UnifiedGameDialog` 容器（见 `rules/chat-dialog-design.md`）；
使用 Compose `Dialog()` 或 Material3 `AlertDialog` 的组件必须加 `DialogSystemBarGuard()`。

## 含输入框的界面

先读 `rules/dialog-soft-input-guard.md`，按判断法则二选一：

- **数字 / 数量输入** → `NumberInputPanel`（自绘数字键盘，完全不弹系统 IME）
- **文本输入** → `TextInputDialog`（独立平台 Dialog 窗口，已内置 `DialogSoftInputGuard` +
  `ImeAwareContainer` + `freezeSystemBars=true` + `rememberImeAwareAutoFocusRequester` + `InputSessionStateMachine`）

🔴 含游戏渲染 Surface 的 Activity 窗口内**禁止内联文本输入**。
🔴 **禁止新增任何机型 / 渲染模式特判分支** —— 该问题 2026-09 已根治，历史五轮补丁式机制（多避让路径、
固定延时主路径、机型特判）全部作废，回退即为缺陷。

## 精灵图与渲染

- 静态图片一律：无损 WebP + 双模块 `drawable-nodpi` 放置 + `SpriteResRegistry` 注册，
  显示用 `SpriteImage()` / Canvas `drawSprite()` / `SpriteResRegistry.resolve()`。
  **禁止直引 `painterResource(R.drawable.xxx)`**（注册代码除外），**禁止提交 PNG/JPG 游戏图片**（唯一例外 `ic_launcher-playstore.png`）
- 新增精灵全流程 7 步、建筑/装饰显示尺寸口径与图集 codegen 管线见 `rules/static-resources.md`
- 渲染特性变更必须 Vulkan + Canvas 两条路径同步实现，并回来更新 `android/docs/renderer-feature-checklist.md`

## Compose 性能

`@Immutable` 标注出现在 Compose State 中的数据类；禁止 Composition 内读 State（用 `derivedStateOf`）；
`LazyColumn` / `LazyRow` 用稳定 key（`key = { it.id }`，禁 index）；静态绘制用 `Modifier.drawBehind {}`。
按钮尺寸统一 `ButtonSizes.StandardWidth` (72dp) × `StandardHeight` (38dp)。
