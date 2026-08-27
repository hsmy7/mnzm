# 规则：Dialog 键盘防频闪保护

**核心法则：每个包含输入框的容器必须恰好使用一种键盘避让机制，禁止 `ADJUST_PAN` 与 `imePadding` 叠加（pan + padding 双重位移 = 国产 ROM 键盘振荡频闪的历史根因配方）。**

## 根因

Xiaomi HyperOS / OPPO ColorOS / Vivo FuntouchOS 等国产 ROM 上，键盘弹出期间 IME insets 每帧变化；若同一窗口同时存在**窗口级平移（ADJUST_PAN）**与 **Compose 级布局压缩（imePadding）**，内容发生双重位移，触发 IME 状态误报的竞态条件，形成"键盘弹出 → 收起 → 再弹出"的振荡回路（界面闪屏）。

佐证（2026-07 行业调研）：
- Google IssueTracker #229378542：imePadding 在 Dialog 窗口内不可靠
- Xiaomi MIUI/HyperOS 已知缺陷：imePadding 在 Dialog 窗口上无法正确处理 keyboard insets
- StackOverflow 社区共识：adjustPan 是 Compose Dialog 输入框的最佳实践（但必须与 imePadding 二选一）

## 第二根因（2026-08 荣耀 X70 根治新增）

**Activity 层窗口操作放大器**：`onWindowFocusChanged → hideSystemBars()` 无条件调用 `WindowInsetsControllerCompat.hide()`。荣耀 MagicOS 键盘弹出/收起期间存在窗口焦点抖动，每次抖动都触发 hide()；Android 15 强制 edge-to-edge 下 IME 可见期间系统接管导航栏，hide() 与其对抗 → insets 翻转 → 键盘收起再弹出，形成独立于对话框避让机制的**第二条振荡回路**。此前三轮修复（小米/OPPO/Vivo）均未触及该放大器，荣耀 MagicOS 9 + Android 15 是首个打爆组合。

**第二根因防御法则：输入对话框挂载期间冻结宿主窗口系统栏操作。**
- 含输入框的对话框统一通过 `SystemBarFreezeScope`（core/ui）冻结：`InlineStandardPromptDialog` / `UnifiedGameDialog` 传 `freezeSystemBars = true`；自定义容器（如 `PlantingDialog`）直接 `DisposableEffect { enterFreeze(); onDispose { exitFreeze() } }`
- Activity 侧 `hideSystemBars()` 必须接入双守卫 `SystemBarHidePolicy.shouldSkipHide()`（输入对话框冻结期间 或 `ImeVisibilityTracker.isImeVisible` 键盘可见期间 → 跳过），并注册 `SystemBarFreezeScope.addOnUnfreezeListener` 在解冻后恢复隐藏
- `MainActivity` 与 `GameActivity` 两个入口都受此法则约束（游戏内改名/兑换码/数量输入同模式）

## 第三根因（2026-08 荣耀 GT 系列根治新增）

**API<35 传统 systemUiVisibility flags 路径 + Dialog 窗口键盘盲区**：荣耀 X70（Android 15/API 35）上 `hideSystemBars()` 走纯 `WindowInsetsControllerCompat` 路径且 IME 期间系统接管导航栏，应用 hide() 被忽略，冻结机制即根治；荣耀 GT 系列（荣耀 GT AMG-AN00 / 80 GT / 90 GT，Android 12-14/API 32-34 + MagicOS 7.x）上传统 `SYSTEM_UI_FLAG_*` 被 SystemUI 完整执行，且 `ImeVisibilityTracker` 旧实现只跟踪 Activity 窗口——键盘在平台 Dialog 窗口内弹出时 IME insets 只派发给**获得输入焦点的窗口**（Dialog 窗口），Activity 收不到，`isImeVisible` 恒 false，双守卫第二条件失效，残留三环放大器：

1. **放大器 A（跟踪盲区）**：Dialog 窗口内的键盘无法被全局跟踪
2. **放大器 B（Dialog 窗口自身 hide 对抗）**：`DialogSystemBarGuard` 挂载时对 Dialog 窗口应用 `HIDE_NAVIGATION` 等传统 flags（API<35 生效），键盘弹出期间与 IME 所需导航栏区域冲突 → insets 翻转；冻结机制只管宿主 Activity 的 hideSystemBars()，管不到 Dialog 窗口自身的系统栏标志
3. **放大器 C（解冻恢复立即 hide）**：对话框关闭 → 解冻监听器立即 `hideSystemBars()` → 键盘收起动画期间 hide() + 传统 flags 真执行 → 与 IME 对抗 → 叠加 MagicOS 焦点抖动 → 振荡回路

**第三根因防御法则：键盘可见期间一切窗口级系统栏隐藏必须暂停。**
- `ImeVisibilityTracker`（core/ui）为**多窗口跟踪**：Activity 与各 Dialog 窗口各自独立跟踪（`attach(window, onFlip)` 幂等，同窗口重复 attach 仅追加回调），任一窗口键盘可见 → 全局 `isImeVisible` = true；`isImeVisibleFor(window)` 按窗口查询；`detach(window)` 窗口销毁前调用（复位状态防全局残留）
- `DialogSystemBarGuard` 为 **IME 感知**：挂载时经 `ImeVisibilityTracker.attach` 跟踪本窗口键盘；键盘可见 → `controller.show(navigationBars)` + 清除 legacy `HIDE_NAVIGATION`（FULLSCREEN 与键盘无冲突保留）；键盘收起 → 恢复隐藏。API 35+ legacy 标志为 no-op，逻辑零副作用
- **解冻恢复延迟**：`MainActivity`/`GameActivity` 的 `systemBarRestoreListener` 必须 `postDelayed(SYSTEM_BAR_RESTORE_DELAY_MS=350)` 后再次经 `SystemBarHidePolicy.shouldSkipHide()` 校验再 `hideSystemBars()`——等待键盘收起动画结束、IME 状态落定（两 Activity 常量一致；`onDestroy` 清理延迟回调）
- `SystemBarHidePolicy`/`ImeVisibilityTracker` 均位于 core/ui（`com.xianxia.sect.ui.components`），新增窗口守卫一律通过它们，禁止自行操作 WindowInsetsController/legacy flags

## 第四根因（2026-08 小米15/荣耀500 Pro/荣耀200 Pro/红米K70 根治新增）

**freeze 作用域缺陷 + targetSdk 35 强制 edge-to-edge 的叠加**：`SystemBarFreezeScope` 是全局单例，只经 `SystemBarHidePolicy` 冻结**宿主 Activity** 的 `hideSystemBars()`；平台 Dialog 窗口自身的系统栏隐藏（`DialogSystemBarGuard` 的 HIDE_NAVIGATION / `WindowInsetsControllerCompat.hide()`）不经 `SystemBarHidePolicy`——**输入对话框的 `freezeSystemBars = true` 对 Dialog 窗口零约束**。targetSdk=35 在 Android 15 上强制 edge-to-edge，系统在 IME 期间接管导航栏，此时 Dialog 窗口仍执行系统栏隐藏/切换（荣耀 GT 修复的"IME 感知切换"在 HyperOS 2/MagicOS 8/9 的键盘转场动画上自身成为放大器）→ 键盘弹出→收起→再弹出振荡回路 + ADJUST_PAN 平移表现为"界面反复下拉"。

**第四根因防御法则：输入期间 Dialog 窗口对系统栏零操作，冻结语义按窗口传导。**
- `DialogSystemBarFreezeScope`（core/ui）按 Window 跟踪冻结计数：含输入框的容器（`freezeSystemBars = true`）挂载时在 Dialog{} 块内经 `DialogSystemBarFreezeEffect` 对**本 Dialog 窗口** `enterFreeze`——`DialogSystemBarGuard` 据此**只隐藏状态栏、不隐藏导航栏**（切断 HIDE_NAVIGATION×IME 冲突面，API<35 与 API 35 双路径同时根治）
- `DialogSystemBarGuard` 删除 IME 感知的 applyShow/applyHide 切换逻辑：键盘可见期间对系统栏**零操作**（切换动作本身在 HyperOS 2/MagicOS 8/9 上即为振荡放大器，移除即根治）；冻结进入（嵌套输入框挂载）恢复导航栏显示，解冻后延迟 350ms + 二次校验（窗口未冻结且键盘不可见）恢复隐藏
- **嵌套自动传导**：`InlineStandardPromptDialog` 渲染于平台 Dialog 窗口内（`isInsideDialogWindow`）且 `freezeSystemBars = true` 时，自动 `enterFreeze(外层窗口)`——freeze 语义从宿主 Activity 传导到外层 Dialog 窗口（如仓库出售：SmallScreenDialog overlay 槽位内嵌 SellConfirmDialog），调用方无需传参
- `StandardPromptDialog`/`SmallScreenDialog` 新增 `freezeSystemBars: Boolean = false` 参数（默认 false 向后兼容）；平台 Dialog 窗口直接持输入框的新场景必须显式传 true
- 输入对话框的导航栏可见为**有意取舍**（行业主流"输入时退出沉浸"）：键盘弹出时导航栏本就应可见（Android 15 系统接管语义），edge-to-edge 下为浮层条，视觉影响最小；非输入对话框沉浸不变

## 第五根因（2026-08 真我 neo7 turbo 键盘反复弹出/闪烁/闪退根治新增）

**软件渲染 × Android 15 edge-to-edge × Activity 层双避让的叠加**：真我 neo7 turbo（realme UI / Android 15+ / 天玑 9300+）为 MTK SoC——`VulkanPolicy.detectTier` 命中 `MEDIATEK_PREFIXES` 判定 `PROBLEMATIC`，Android 15+（API 35）下 `shouldDisableHardwareAcceleration` 为 true → `Theme.XianxiaSect.GameSafe`（`hardwareAccelerated="false"`）→ **全 UI 软件渲染**（第四根因四款机型均为高通 SoC → `WARNING` 档保持硬件加速；真我 neo7 turbo 是首个"MTK + Android 15 + 键盘输入场景"实测机型）。软件渲染下 Android 15 强制 edge-to-edge 的 IME insets 派发时序不稳定（跨框架佐证：Flutter `ImeSyncDeferringInsetsCallback`、Chromium `DeferredIMEWindowInsetApplicationCallback`、Slint #11344 "Android IME broken on some OEMs after WindowInsetsAnimation"），Activity 层输入框的 `adjustResize`（窗口 resize）+ `imePadding`（布局 padding）**双重位移反复触发** → IME 状态误报 → 键盘弹出→收起→再弹出振荡回路（第一根因"双重位移"在软件渲染路径上的新形态）；振荡 × 软件渲染 1.5K 高分屏每帧全屏 CPU 重绘 → 主线程过载 → **进程被杀（直接退出回桌面，无提示）**。辅助放大器：`hasTextInputFocus` 守卫对 Compose 文本字段失效（Compose 焦点在 FocusManager，Android 层 `findFocus()` 返回 AndroidComposeView，非 EditText/TextView）→ 检测信号不稳定时重试逻辑无法识别"已有焦点"→ 每 800ms 重复 `requestFocus` → ROM 智能输入法反复重弹键盘。

**第五根因防御法则：渲染模式感知双路径——软件渲染 Activity 层输入框走单一 ADJUST_PAN。**
- `InlineStandardPromptDialog` 键盘避让升级为**渲染模式感知双路径**（`shouldUsePanAvoidance` 纯函数判定：`!insideDialogWindow && !hardwareAccelerated`，`hardwareAccelerated` 读 `View.isHardwareAccelerated`，与 VulkanPolicy 决策同源）：
  - 平台 Dialog 窗口内：ADJUST_PAN（外层窗口已有，内层禁用 imePadding，不变）
  - **硬件加速 Activity 层：manifest adjustResize + imePadding 官方标准组合（Flutter/Unity 同款，荣耀 X70 等已验证稳定，不变）**
  - **软件渲染 Activity 层（新增）：挂载 `DialogSoftInputGuard()`（ADJUST_PAN，作用于 Activity 窗口）+ 禁用 imePadding**——窗口级系统平移，不依赖 IME insets 派发时序，切断双重位移振荡触发源；与平台 Dialog 窗口场景 / `PlantingDialog` 机制一致
- `hasTextInputFocus` 补充 Compose 场景判定：`view.hasFocus() && focused === view`（Compose 内部聚焦时 ComposeView 自持 Android 焦点）——防检测信号不稳定时重试逻辑反复 requestFocus 触发键盘重弹（辅助放大器根治）
- **不改变 VulkanPolicy 本身**：MTK 一律软件渲染是既有保守策略（天玑 Vulkan 驱动历史问题），放宽风险大；双路径在软件渲染设备上以"系统级平移"规避 insets 依赖，机制正确且无需放宽渲染策略
- **升级路径（已登记技术债）**：若任一**硬件加速**设备实报键盘振荡复现 → 升级为全局统一 ADJUST_PAN 单一避让（废除 Activity 层 imePadding 路径）；修复后仍有闪退 → 按 Bugly 堆栈独立定位（native crash vs LMK，与 IME 交互可能无关）

## 双机制避让（2026-08-06 根治后规则）

| 渲染上下文 | 唯一避让机制 | 实现 |
|-----------|------------|------|
| **平台 Dialog 窗口内**（Compose `Dialog()` 创建的独立 Window，如 `UnifiedGameDialog`/`StandardPromptDialog`/`SmallScreenDialog`） | 窗口级 `SOFT_INPUT_ADJUST_PAN`（不做 resize，仅平移，切断振荡回路） | 容器内置 `DialogSoftInputGuard()`（默认 ADJUST_PAN）；**窗口内严禁再加 `imePadding`** |
| **Activity 窗口内（硬件加速）**（内联 Box 覆盖层，如 `InlineStandardPromptDialog`/`PlantingDialog`） | `adjustResize`（manifest 已配置）+ Compose `imePadding` = Google 官方标准组合 | 无需 `DialogSoftInputGuard`（保持 manifest 默认）；覆盖层挂 `.imePadding()` |
| **Activity 窗口内（软件渲染，第五根因）**（`View.isHardwareAccelerated == false`，如真我 neo7 turbo——MTK 被 VulkanPolicy 强制关闭 HW 加速） | 窗口级 `SOFT_INPUT_ADJUST_PAN` 单一避让（与平台 Dialog 窗口一致） | `InlineStandardPromptDialog` 自动经 `shouldUsePanAvoidance` 判定挂载 `DialogSoftInputGuard()`（作用于 Activity 窗口）+ **禁用 imePadding**——系统级平移不依赖 IME insets 派发时序，切断软件渲染下双重位移振荡 |

`InlineStandardPromptDialog`（含文本输入的对话框统一使用）已内置**双上下文自动检测**：通过 `isInsideDialogWindow(LocalView.current)` 判断是否处于平台 Dialog 窗口内——Activity 层应用 `imePadding`，Dialog 窗口内自动禁用（外层窗口已有 ADJUST_PAN）。**第五根因起升级为渲染模式感知**：软件渲染 Activity 层（`isHardwareAccelerated == false`）自动切换为单一 ADJUST_PAN 并禁用 imePadding。**调用方无需关心，也不要手动叠加任何避让。**

## 哪些容器已自带正确避让

| 容器 | 形态 | 避让机制 | 备注 |
|------|------|---------|------|
| `UnifiedGameDialog` | 平台 Dialog 窗口 | `DialogSoftInputGuard(ADJUST_PAN)` | 无输入框对话框直接使用；含输入框时内层禁用 imePadding + `freezeSystemBars = true`（冻结宿主 Activity + 本 Dialog 窗口双侧） |
| `StandardPromptDialog` | 平台 Dialog 窗口 | `DialogSoftInputGuard(ADJUST_PAN)` | 同上；含输入框时同样传 `freezeSystemBars = true`（2026-08 第四根因起新增参数） |
| `SmallScreenDialog` | 平台 Dialog 窗口 | `DialogSoftInputGuard(ADJUST_PAN)` | 同上；平台窗口直接持输入框的新场景传 `freezeSystemBars = true`（嵌套输入场景由内联组件自动传导，无需传参） |
| `InlineStandardPromptDialog` | 内联 Box 覆盖层（无平台窗口） | 双上下文自动：Activity 层 `imePadding` / Dialog 窗口内无（外层 ADJUST_PAN） | **含文本输入的对话框（创建宗门/改名/兑换码/出售数量）统一使用此组件**，含输入框时 `freezeSystemBars = true`；嵌套于平台 Dialog 窗口内时自动冻结外层窗口系统栏（第四根因传导） |
| `PlantingDialog` | 内联全屏覆盖层 | Activity 窗口 ADJUST_PAN 单一避让（容器内置 guard）+ 手动 `SystemBarFreezeScope` 冻结 | 已内联 + 单一避让 + 冻结，稳定 |

## 判断法则

新增输入框时，按此规则判断：

```
新增的输入框放在哪里？
  ├─ InlineStandardPromptDialog（文本输入）
  │  → ✅ 传 freezeSystemBars = true，自动聚焦用 rememberImeAwareAutoFocusRequester()；
  │     嵌套在平台 Dialog 窗口内时自动冻结外层窗口系统栏（无需传参）
  ├─ StandardPromptDialog / UnifiedGameDialog / SmallScreenDialog（平台 Dialog 窗口）
  │  → ✅ 不处理避让（窗口已有 ADJUST_PAN；内层严禁 imePadding）；
  │     含输入框时传 freezeSystemBars = true（冻结宿主 Activity + 本 Dialog 窗口双侧，
  │     DialogSystemBarGuard 据此不隐藏导航栏，切断 HIDE_NAVIGATION×IME 冲突面）
  ├─ PlantingDialog
  │  → ✅ 同上
  └─ 新创建的自定义 Dialog { } 或 Box overlay
     → 🔴 二选一避让 + 必接冻结（双侧）：
        ├─ 平台 Dialog 窗口 → 顶部调用 DialogSoftInputGuard()（ADJUST_PAN），
        │    禁止 imePadding；含输入框时 Dialog{} 块内接 DialogSystemBarFreezeEffect(true)
        │    （或 DisposableEffect 经 DialogSystemBarFreezeScope.enterFreeze(本窗口)）
        └─ Activity 层 Box 覆盖层 → 硬件加速挂 imePadding()（保持 manifest adjustResize）、
             软件渲染（isHardwareAccelerated == false）挂 DialogSoftInputGuard()（ADJUST_PAN）
             并禁用 imePadding——优先放入 InlineStandardPromptDialog（自动判定），
             自定义容器按此二选一；含输入框时同样接入 SystemBarFreezeScope
```

**社交扩展（2026-08-04 起）：** 未来社交/排行界面的搜索框、好友备注输入框同样按此法则判断——优先放入 `InlineStandardPromptDialog`；自定义容器必须严格二选一 + 输入框冻结。

## 违规后果

- 平台 Dialog 窗口内叠加 `imePadding`（pan + padding 双重位移）→ 国产 ROM 键盘反复弹出/收起，界面闪屏，输入无法正常使用
- 无任何避让的 Dialog + 输入框 → 键盘遮挡输入框或触发 adjustResize 振荡
- 含输入框但未冻结系统栏操作（缺 `freezeSystemBars` / `SystemBarFreezeScope`）→ 荣耀 MagicOS 9 + Android 15 上"键盘弹出→收起→再弹出"振荡回路（hideSystemBars 放大器）
- Dialog 窗口守卫非 IME 感知（`DialogSystemBarGuard` 无条件 hide）或 tracker 非多窗口 → 荣耀 GT 系列（API<35 传统 flags 路径）上 Dialog 窗口 HIDE_NAVIGATION 与 IME 对抗，键盘频闪复现（第三根因放大器 B）
- 解冻恢复无延迟（立即 hideSystemBars）→ API<35 上键盘收起动画期间 hide() 真执行与 IME 对抗，叠加 MagicOS 焦点抖动形成振荡（第三根因放大器 C）
- **平台 Dialog 窗口持输入框但只传 `freezeSystemBars = true` 未传导到 Dialog 窗口（缺 `DialogSystemBarFreezeEffect` / `DialogSystemBarFreezeScope`）→ HyperOS 2 / MagicOS 8/9 / Android 15 edge-to-edge 上 Dialog 窗口仍隐藏/切换导航栏与 IME 对抗，键盘反复弹出 + 界面反复下拉（第四根因，小米15/荣耀500 Pro/荣耀200 Pro/红米K70 实测复现）**
- 嵌套内联输入框（平台 Dialog 窗口 overlay 槽位内）未自动冻结外层窗口 → 同第四根因复现（仓库出售场景）
- 自动聚焦重试在输入框已有焦点时仍重复 requestFocus → 检测信号不稳定 ROM（HyperOS 2/MagicOS 8/9）上键盘反复重弹（第四根因放大器）
- **软件渲染设备（MTK 等被强制关闭 HW 加速）Activity 层输入框仍用 adjustResize + imePadding 双避让 → 软件渲染下 IME insets 派发时序不稳定，双重位移反复触发 → 键盘反复弹出 + 界面闪烁 + 主线程过载进程被杀闪退（第五根因，真我 neo7 turbo 实测复现）**
- 软件渲染 Activity 层输入框改走 ADJUST_PAN 但 `hasTextInputFocus` 未覆盖 Compose 场景 → 检测信号不稳定时自动聚焦重试每 800ms 重复 requestFocus，ROM 智能输入法反复重弹键盘（第五根因辅助放大器）
- 复现条件：HyperOS / ColorOS / FuntouchOS / MagicOS（含 Android 12-14 的 MagicOS 7.x）+ 含输入框对话框 + 输入框获得焦点；软件渲染 + Android 15 edge-to-edge（MTK/华为 Kirin 等被 VulkanPolicy 判 PROBLEMATIC 的设备）为第五根因专属组合

## 注意点

- `DialogSoftInputGuard` 支持两种窗口类型：平台 `DialogWindowProvider`（Compose `Dialog`）和 `Activity.window`（Box overlay 覆盖层），自动检测无需区分；如果找不到目标窗口（极少见边缘情况）会 `Log.w` 后返回，不影响功能
- 保护的是**容器存在期间**的窗口 softInputMode，容器销毁后自动恢复，无副作用
- 含输入框的对话框应使用 `InlineStandardPromptDialog` 而非平台 Dialog 容器（2026-08-06 根治决策：平台 Dialog 窗口与 IME 的交互在国产 ROM 上不可靠，历史上 OPPO/Vivo/HyperOS 三系均复现，见 `docs/adr/dialog-system-refactoring.md`；自动管理/进攻范围/商人买卖数量因产品形态保留平台 Dialog + 输入框，依赖第三根因防御法则）
- **Activity 侧必备**（2026-08 荣耀 X70 根治 + GT 系列升级）：`hideSystemBars()` 接入 `SystemBarHidePolicy.shouldSkipHide()` 双守卫 + `ImeVisibilityTracker.attach(window)`（多窗口）+ `SystemBarFreezeScope.addOnUnfreezeListener`（解冻恢复必须延迟 350ms + 二次守卫）；`MainActivity`/`GameActivity` 两个入口都必须具备，缺一即遗留放大器
- **Dialog 窗口侧必备**（2026-08 第四根因）：含输入框的容器（`freezeSystemBars = true`）必须在 Dialog{} 块内经 `DialogSystemBarFreezeEffect` 冻结本窗口（`DialogSystemBarFreezeScope` 按窗口）；`DialogSystemBarGuard` 冻结态只隐藏状态栏、不隐藏导航栏，键盘可见期间零系统栏切换，解冻延迟 350ms + 二次校验恢复；嵌套内联输入框经 `InlineStandardPromptDialog` 自动传导，无需调用方传参
- **渲染模式感知双路径**（2026-08 第五根因）：`InlineStandardPromptDialog` 的 Activity 层避让按 `View.isHardwareAccelerated` 自动二选一——硬件加速保持 adjustResize + imePadding（官方标准组合），软件渲染自动切换 ADJUST_PAN 单一避让（复用 `DialogSoftInputGuard` 的 Activity 窗口支持）+ 禁用 imePadding；`shouldUsePanAvoidance(insideDialogWindow, hardwareAccelerated)` 为纯函数判定（参数注入便于单测）；判定与 VulkanPolicy 决策同源（同受 GameSafe 主题控制），不改变 VulkanPolicy 本身
- 自动聚焦一律使用 `rememberImeAwareAutoFocusRequester()`（IME 弹出确认 + 有限重试），禁止裸 `LaunchedEffect { requestFocus() }` 单次聚焦（荣耀智慧输入法首次弹出失败场景无恢复）；重试在输入框已有焦点时自动跳过（第四根因：防检测信号不稳定时键盘反复重弹；第五根因起 `hasTextInputFocus` 覆盖 Compose 场景——ComposeView 自持 Android 焦点即视为文本焦点）
