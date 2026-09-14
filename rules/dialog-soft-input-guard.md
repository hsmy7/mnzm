# 规则：Dialog 键盘防频闪保护（IME 状态机根治版）

**核心法则（2026-09 根治后）：单一 insets 真相源 + 一个 IME 状态机 + 键盘动画期系统栏零切换 + 事件驱动（禁 debounce）+ 数字输入自绘。全 App 输入场景收敛为同一条路径，禁止任何机型/渲染模式特判分支。**

> 依据：两份行业调研报告归档于 `docs/ime-keyboard-industry-research.md`（游戏行业做法，31 条来源）与 `docs/ime-android-system-research.md`（Android 系统级机制，27 条来源）。本章节为调研结论的工程落地。

---

## 第六根因（2026-09 根治新增，替代第一~第五根因的补丁式机制）

**根因：IME insets 状态分叉 + 启发式依赖。** 历史五轮补丁（pan+padding 双重位移 / hide 放大器 / Dialog 窗口键盘盲区 / 冻结作用域 / 软件渲染双路径）全部建立在"系统 IME 窗口交互"的协调/对抗上：3 条避让路径 + 6 个守卫组件 + 350ms/800ms 固定延时 + 机型特判分支。机制越堆越复杂，每台新机型都是新分支组合（12+ 轮修复史已证明不可收敛）。行业级根因证据（Flutter P1 #191156/#191228，2026-08）：**键盘动画取消（PHASE_CLIENT_ANIMATION_CANCEL）后陈旧 insets 被重放 → 状态分叉 → 布局反复跳动/下拉**——补丁式收敛（防抖/双路径/冻结）治标不治本。

**根治范式（行业共识）：**
1. **单一 insets 真相源**：全 App（Activity + Dialog 窗口）只保留一条 ime insets 来源（Compose `WindowInsets.ime` / `imePadding` + `ImeVisibilityTracker` 多窗口采集）；窗口统一 `ADJUST_RESIZE`（API 30+ 语义 = insets 派发兼容模式）+ `decorFitsSystemWindows=false`——Dialog 窗口显式进入 insets 管线（`docs/ime-android-system-research.md` M5：Dialog 收不到 insets 是 Compose 默认配置产物，非平台定律）
2. **一个 IME 状态机**（`ImeStateMachine`，core/ui）：聚合键盘可见性（**`isVisible(ime)` 为唯一真值**，禁 `bottom>0` 误判）、键盘动画状态（`ImeAnimationTracker`，onEnd/onCancel 回调）、输入对话框冻结（`SystemBarFreezeScope`，**泄漏自愈**）；系统栏冻结判定、恢复链路（动画回调驱动 + 350ms 仅兜底）、切换时机（键盘不可见且无动画）全部收敛于此
3. **键盘动画期间系统栏零切换**：`hide(systemBars)` 与 IME 同 `InsetsController` 对抗（系统日志实证 `hide(ime())`），动画期/可见期零 hide/show
4. **事件驱动，禁 debounce**：键盘动画回调（`WindowInsetsAnimationCompat`）驱动一切；不猜动画时长（无固定延时主路径）、不按焦点过滤状态
5. **数字输入自绘**：数量输入场景（商人/交易/仓库出售/种植/巡逻塔/自动管理）用 `NumberInputPanel` 自绘数字键盘，**完全不弹系统 IME**——彻底绕开"系统键盘 × 窗口 × 系统栏"交互面（行业主流"自绘 UI + 事件流"范式：微信小游戏/抖音/小米快游戏官方 API 同款）

---

## 统一机制（2026-09 根治后唯一路径）

| 渲染上下文 | 窗口 softInputMode | 避让机制 | 实现 |
|-----------|-------------------|---------|------|
| **文本输入（全部场景）** | `TextInputDialog`（独立平台 Dialog 窗口）：API 30+ `ADJUST_RESIZE`（insets 派发兼容模式）/ API < 30 `ADJUST_PAN`（官方 fallback，防经典 resize + 应用层位移双避让） | `ImeAwareContainer` 事件驱动（**按窗口真值**：仅本窗口键盘可见时位移；API<30 恒零位移） | **`TextInputDialog`（core/ui，2026-09 新增统一组件）**——内部复用 StandardPromptDialog 全部守卫（DialogSystemBarFreezeEffect / DialogSoftInputGuard / DialogSystemBarGuard / ImeAwareContainer / DialogFocusGuard）+ `rememberImeAwareAutoFocusRequester` + `InputSessionStateMachine`（**按 (Window 身份, sessionKey) 隔离**；CLOSED→OPENING→OPEN→CLOSING→CLOSED，open/close 幂等，禁 CLOSING→OPENING 跳转，CLOSING 中新开请求 pendingReopen 重放） |
| **含游戏渲染 Surface 的 Activity（GameActivity 等）** | **`adjustNothing`（manifest）**——游戏主窗口对 IME 完全透明 | 无（窗口内禁止任何文本输入，键盘零参与） | 文本输入一律平台 Dialog 窗口（上方行）；`adjustNothing` 后键盘出现/消失 → 游戏窗口零 resize、零 insets、surface 零重建——根除"界面闪烁/键盘反复弹收"的窗口级振荡回路 |
| **普通 Activity 无游戏 Surface（主菜单/存档页等）** | manifest `adjustResize` + edge-to-edge | Compose `imePadding`（官方标准组合） | `InlineStandardPromptDialog`（仅限无渲染 Surface 的窗口；游戏窗口禁用） |
| **平台 Dialog 窗口（无输入）** | `DialogSoftInputGuard` = `ADJUST_RESIZE` | `ImeAwareContainer`（按窗口真值；无键盘时恒零位移） | `UnifiedGameDialog`/`StandardPromptDialog`/`SmallScreenDialog` 三容器内容区统一挂载 |
| **数字输入**（`NumberInputPanel`：商人/交易/仓库出售/种植/巡逻塔/自动管理） | 不适用（**不弹系统 IME**） | 自绘数字键盘面板（0-9/退格/清空/确定），零 insets 依赖 | `QuantitySelector` 点击数字 → `NumberInputPanel`；阈值/数量输入同款 |

**统一后：**
- **删除**：`shouldUsePanAvoidance` / `PanAvoidanceGuard` / `hardwareAccelerated` 判定（渲染模式分支消亡）、`applyImePadding` 条件分支（Activity 层恒挂）、软件渲染切 ADJUST_PAN 路径、`isInsideDialogWindow` 的避让判定（仅保留嵌套冻结传导）
- **ADJUST_PAN 降级为 API<30 / ROM 特例兜底**（官方定位 fallback 且易错乱：edge-to-edge 下平移量随系统栏抖动 = "界面反复下拉"放大器）
- **保留**：`freezeSystemBars` 参数（语义不变）、嵌套冻结传导、`DialogSystemBarGuard` 零操作策略、自动聚焦重试（动画空闲期 + isVisible 终止）

---

## 系统栏策略（收敛至 ImeStateMachine 单一判定）

1. **键盘可见 或 键盘动画中 或 输入对话框冻结** → `SystemBarHidePolicy.shouldSkipHide()` = true（系统栏零 hide/show）
2. **恢复链路**：键盘动画 `onEnd`/`onCancel` 回调 → isVisible 复查 → 直接恢复隐藏；350ms 延时仅作"回调未触发"兜底（**替代历史固定 350ms 主路径**——ROM 键盘动画时长差异大，过早恢复与残余动画对抗）
3. **泄漏自愈**：`SystemBarFreezeScope` 冻结超时（10 分钟）强制归零 + 触发恢复——onDispose 未执行（异常/快速销毁）不会导致系统栏永久不隐藏
4. **可见性真值**：`ImeVisibilityTracker` 判定 = `isVisible(ime)`（API 30+），`bottom>0` 仅 API<30 兜底——`bottom>0` 在键盘隐藏/动画/兼容模式下误判是"界面反复下拉"头号来源（M6）

---

## 判断法则（新增输入场景时）

```
新增输入框放在哪里？
  ├─ 数字/数量输入（数量、阈值、上限等）
  │  → ✅ 使用 NumberInputPanel（自绘数字键盘，不弹系统 IME）
  │     QuantitySelector 已内置（点击数字 → 面板）；裸数字框改"显示框 + 点击弹面板"
  ├─ 文本输入（改名/宗门名/兑换码等）
  │  → ✅ 统一使用 TextInputDialog（core/ui，独立平台 Dialog 窗口）
  │     内部已内置：DialogSoftInputGuard（per-API RESIZE/PAN）+ ImeAwareContainer（按窗口真值）
  │     + freezeSystemBars=true + rememberImeAwareAutoFocusRequester + InputSessionStateMachine
  │     🔴 禁止在含游戏渲染 Surface 的 Activity 窗口内联文本输入（InlineStandardPromptDialog）
  │     🔴 禁止在自定义容器内直接 OutlinedTextField + 裸 requestFocus（自动聚焦必须走
  │        rememberImeAwareAutoFocusRequester；打开/关闭必须走 InputSessionStateMachine）
  │     🔴 禁止任何 toggleSoftInput / showSoftInput 无限重试 / 固定延时主路径
  └─ 新创建的自定义 Dialog { } 或 Box overlay（无文本输入）
     → 复用统一容器（StandardPromptDialog/UnifiedGameDialog/SmallScreenDialog 或
       InlineStandardPromptDialog——仅限无游戏 Surface 的窗口）
```

**游戏窗口特殊规则（2026-09 新增）**：`GameActivity` 等含游戏渲染 Surface 的 Activity
manifest 恒 `adjustNothing`；其窗口内禁止组合任何文本输入框。文本输入的需求一律
由 `TextInputDialog`（独立平台 Dialog 窗口）承接——键盘的 resize/insets/焦点抖动
只影响该 Dialog 窗口，游戏窗口与渲染 Surface 全程零参与（根除"界面闪烁 + 键盘
反复弹收"的窗口级振荡回路）。

**社交扩展（2026-08-04 起）：** 未来社交/排行界面的搜索框、好友备注输入框同样按此法则判断——优先放入 `InlineStandardPromptDialog`；自定义容器必须接入统一机制。

---

## 违规后果（统一后）

- 平台 Dialog 窗口叠加 imePadding（insets 管线 + 布局 padding 双源）→ 布局双位移
- 重新引入 ADJUST_PAN 主路径 → edge-to-edge 下平移量随系统栏抖动，"界面反复下拉"复发
- 新增机型/渲染模式特判分支 → 机制复杂度回升，新机型再爆（12+ 轮修复史教训）
- 恢复链路用固定延时主路径 → 动画 >350ms 的 ROM 上过早恢复与残余动画对抗，再振荡
- 可见性判定用 bottom>0 → 键盘隐藏/动画期误判 → 错误冻结/恢复
- 自动聚焦在键盘动画中重试 → PHASE_CLIENT_ANIMATION_CANCEL 反复取消，键盘反复重弹
- 数字输入弹系统 IME → 重新进入"系统键盘 × 窗口 × 系统栏"交互面（已自绘绕开）

---

## 注意点

- `ImeStateMachine` / `ImeAnimationTracker` / `ImeAwareContainer` / `NumberInputPanel` 均为新增统一组件（core/ui 与 feature/game），所有输入场景必须复用，禁止另起炉灶
- `DialogSoftInputGuard` 保护的是**容器存在期间**的窗口 softInputMode，销毁自动恢复；ADJUST_RESIZE 与 manifest 一致时幂等
- `MainActivity`/`GameActivity` 必备：`ImeVisibilityTracker.attach` + `ImeAnimationTracker.attach` + 解冻/动画结束恢复监听器 + `onDestroy` 对称 detach——缺一即遗留状态残留
- 自动聚焦一律 `rememberImeAwareAutoFocusRequester()`（IME 弹出确认 + 动画空闲期有限重试 + isVisible 终止），禁止裸 `LaunchedEffect { requestFocus() }`
- 真机验证闭环：logcat `ImeGuard` 日志（`IME 可见性翻转` / `ImeAnimationTracker: 键盘动画开始/结束` / `hideSystemBars 跳过（IME 守卫）` / `泄漏自愈`）
