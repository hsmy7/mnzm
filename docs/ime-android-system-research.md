# Android IME（软键盘）× 窗口交互系统级调研报告

> 目标：为 Compose 手游在国产 ROM（小米 HyperOS、荣耀 MagicOS、OPPO ColorOS、Vivo、realme UI、红米）上"键盘反复弹出/收起、界面闪屏、界面反复下拉"问题提供系统级根治依据。
>
> - 调研窗口：2024–2026（优先）；核心系统机制以官方现行文档（API 30/35/36）为准
> - 访问日期：2026-08-31
> - 参考来源：27 条（S 级 11 + A 级 6 + B 级 10），全部含可核验日期；S/A 级 ≥ 8 条达标
> - 关联文件：`rules/dialog-soft-input-guard.md`（现行机制）、`docs/ime-keyboard-industry-research.md`（历史行业调研）

---

## 〇、调研范围与日期说明

1. **日期口径**：Google 官方文档页（developer.android.com）以**对应 Android/Compose 版本发布批次**标注日期（例如 behavior-changes-15 随 Android 15 稳定版 2024-09-03 发布）；个别页面"最后更新"时间戳因网络限制无法直接核验的，已按版本批次标注并如实说明。所有**持续更新类文档**（devsite 页面、AndroidX 发布说明）均标注访问日期 2026-08-31。
2. **Google IssueTracker 补充指针**：`#229378542`（imePadding doesn't work inside Dialog）、`#248529694`（DialogProperties.decorFitsSystemWindows 随机顶部边距）、`#328691355`（IME padding 动画期间含导航栏 padding）、`#249727298`（Scaffold 双重 padding）——本网络环境无法直接访问核验创建日期，按"无法确认日期的来源不得使用"规则**不计入正式参考清单**，仅作正文补充指针，建议在可访问时核对最新状态。
3. **项目侧关联**：本报告评审对象为 `rules/dialog-soft-input-guard.md` 记录的五轮根因机制（DialogSoftInputGuard / SystemBarFreezeScope / ImeVisibilityTracker / DialogSystemBarFreezeScope / 350ms 恢复延时 / 800ms 聚焦重试 / 渲染模式感知双路径）。

---

## 一、系统级关键机制清单（机制 | 官方立场/最佳实践 | 来源）

| # | 机制 | 官方立场 / 最佳实践 | 来源 |
|---|------|--------------------|------|
| M1 | **windowSoftInputMode 三模式语义与 API 30 后的"兼容模式"** | adjustResize=窗口 resize 避让；adjustPan=整窗平移避让；adjustNothing=不处理。**从 API 30 起**：官方 javadoc 将 `SOFT_INPUT_ADJUST_RESIZE` 标注 deprecated，窗口不再被系统"真正 resize"，改为向窗口**派发 IME WindowInsets**，由应用自行处理（兼容模式）；社区共识：API 30+ 应走 insets 管线，softInputMode 仅作兜底 | [S4][S5][B8] |
| M2 | **Android 15（targetSdk 35）强制 edge-to-edge** | 状态栏/导航栏强制透明、`decorFitsSystemWindows` 默认 false、应用必须自行处理 insets；**三键导航下系统接管导航栏**（自动加对比度 scrim），手势导航下为透明浮层条。官方 2024-04-11 首 Beta 预告 enforcement，2024-09-03 正式落地 | [S1][S9][S10] |
| M3 | **IME insets 动画与派发时序（WindowInsetsAnimation）** | 键盘显隐由 `WindowInsetsAnimation.Callback` 驱动：`onPrepare → onStart → onProgress（逐帧）→ onEnd`。IME insets 在动画期间逐帧变化；**Compose 侧 insets 值在"组合后、布局前"更新**。官方最佳实践：动画期间用 onProgress 驱动几何、以 onEnd 为稳定判定点，**不要用固定延时猜动画时长** | [S3][S5][S6][A4] |
| M4 | **imePadding 的前置条件** | 官方 Compose 文档：`imePadding()` 仅在 **edge-to-edge（enableEdgeToEdge/decorFitsSystemWindows=false）+ manifest `adjustResize`** 同时满足时才生效；缺任一项即"无效或行为异常"（如动画期间导航栏 padding 被重复计入 → 双重内边距 bug） | [S6][B6][B9] |
| M5 | **Dialog 窗口是独立 Window，不继承 Activity 的 insets/softInputMode/edge-to-edge** | Compose Dialog（DialogWindowProvider）窗口：IME insets 恒为 0、`DialogProperties.decorFitsSystemWindows` 无效、`enableEdgeToEdge()` 不传导——这是"imePadding 在 Dialog 内无效"的机制根因；**IME insets 只派发给持有输入焦点（IME target）的窗口**，Dialog 持焦点时 Activity 收不到 | [B4][B5][B7][S4] |
| M6 | **isVisible(ime) vs getInsets(ime).bottom** | 官方明确：判断键盘可见性**必须用 `insets.isVisible(Type.ime())`**，不能用 `getInsets(ime).bottom > 0`——键盘隐藏/动画/兼容模式下 bottom 仍可能非零（误判是振荡与误恢复的头号来源） | [S4][B3] |
| M7 | **insets 监听的正确写法（不消费透传）** | `ViewCompat.setOnApplyWindowInsetsListener` 应**返回传入的 insets 原值**（透传），需要继续分发时用 `dispatchPassThrough=true`；消费（返回 CONSUMED）会截断下游监听，是常见次生 bug。键盘高度跟随动画用 `WindowInsetsAnimationCompat.Callback.onProgress`，可见性用 isVisible | [S4][S5][B3] |
| M8 | **键盘显隐监听：GlobalLayout 的局限与正解** | `OnGlobalLayoutListener` 方案：无逐帧动画、多窗口/edge-to-edge 下不可靠、API 30+ 已被官方路线取代。正解：API 30+ 用 WindowInsets(Animation)；API<30 才回退 GlobalLayout+PopupWindow 测高 | [S4][B3][B10] |
| M9 | **hide(systemBars) 与 IME 的"同控制器对抗"** | `WindowInsetsController`/`InsetsController` 统一管理 ime 与 systemBars（同一 requestedVisibleTypes 位图）。系统日志实证：应用 hide(systemBars) 在 IME 场景会被路由成 `InsetsController: hide(ime())`、`Setting requestedVisibleTypes to 503 (was 511)`——**hide 调用与键盘 show 竞争即振荡回路**。官方沉浸式文档：隐藏系统栏用 `BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE`，**避免在 IME 生命周期内反复切换 hide/show** | [S8][A3] |
| M10 | **IME 动画被取消/后台化 → stale insets（陈旧 insets 重派发）** | 系统级 bug 家族（Google Flutter 团队 2026-08 实证，Pixel 上复现）：键盘 show 动画中途被 hide/后台/焦点切换取消（日志 `ImeTracker: onCancelled at PHASE_CLIENT_ANIMATION_CANCEL`），框架缓存了"键盘可见"快照，resume 后 onEnd 无条件重派发旧值 → **键盘已收但 insets 长期残留 → 布局反复跳动/下拉**。正确对策：以 isVisible 为真值、恢复时重新查询根 insets、用动画 onEnd/onCancel 而非缓存快照 | [A3][A4][A5] |
| M11 | **Android 16（API 36）：预测性返回与 IME** | API 36 目标应用默认启用 predictive back；`onBackPressed` 不再能完全拦截返回，**必须迁移 `OnBackInvokedDispatcher`**（SDL 2025-08 实证：即使拦截仍会退后台）；键盘场景新增 `ImeBackAnimationController`（键盘收起动画与返回手势联动）。API 36 无新的 IME insets API 大改 | [S2][B1][A3] |
| M12 | **国产 ROM 键盘振荡的"ROM 侧根因"真实存在** | 小米官方 HyperOS Vol-198 Bug Report（2025-05）亲自修复键盘动画缺陷：①冷启动后键盘弹出延迟；②**键盘区域先出现、键盘本体后动画（区域与键盘动画不同步）→ 视觉闪屏**；根因是系统动画时序。结论：**即使应用完全合规，ROM 侧动画/insets 派发时序缺陷本身即可制造"闪屏/反复弹收"**，应用层必须以动画回调 + isVisible 双保险，而不是靠猜 | [S11][B2][A6] |

---

## 二、对项目现行机制的逐条评审（6 组问题点）

### 2.1 平台 Dialog 窗口输入框：`DialogSoftInputGuard` 设 ADJUST_PAN（第一/三/四根因方案）

- **问题点 1：ADJUST_PAN 自 API 30 起官方 deprecated，且在 Android 15 强制 edge-to-edge 下"平移量"包含/不包含导航栏区域不确定**（透明导航栏 + 键盘 → pan 计算随系统栏状态抖动），这是"界面反复下拉"的机制级放大器之一 [S1][B8]。
- **问题点 2：pan 是"整窗瞬移"，不与键盘动画逐帧同步**；国产 ROM（尤其 HyperOS，见 M12）键盘动画时序本身不稳，pan + 动画不同步 = 闪屏观感 [S11][B2]。
- **问题点 3：项目内"Dialog 收不到 IME insets → 只能 pan"的前提，一半是平台事实、一半是 Compose 窗口默认配置的产物**：evant 复现表明 Compose Dialog 默认 `decorFitsSystemWindows` 生效导致 insets 恒 0；但 **API 30+ 若对 Dialog 窗口显式 `setDecorFitsSystemWindows(false)` + `setSoftInputMode(ADJUST_RESIZE)`，Dialog 是可收到 ime insets 的**（IME insets 派发给持焦窗口，M5）。"Dialog 只能用 pan"是全行业对 Compose 1.x 缺陷的妥协结论，不是平台定律 [B4][B5][B7]。
- **结论**：ADJUST_PAN 作为"唯一避让"在短中期可用，但它是**过渡性方案**；根治方向是 Dialog 窗口显式进入 insets 管线（配置窗口 + onProgress 驱动），并保持"键盘可见期间窗口零系统栏操作"。

### 2.2 Activity 覆盖层：硬件加速 `adjustResize + imePadding`，软件渲染切 ADJUST_PAN（第五根因双路径）

- **问题点 1（方向正确，条件要查）**：`adjustResize + imePadding` 是官方标准组合（M4），但**前置条件必须是 edge-to-edge 且 decorFitsSystemWindows=false**。请核对：硬件加速路径是否确认 `WindowCompat.setDecorFitsSystemWindows(window,false)/enableEdgeToEdge()` 已调用——缺失时 imePadding 静默失效，退回"窗口半 resize 半 padding"的双重位移 [S6][B9]。
- **问题点 2（软件渲染切 ADJUST_PAN 的运行期切换）**：`shouldUsePanAvoidance` 在**容器挂载时**切换窗口 softInputMode——若切换发生在键盘可见/动画进行中，`setSoftInputMode` 本身触发 insets 重派发，与键盘动画竞争 → 振荡。**切换必须在键盘不可见且无 IME 动画时执行**，且不可与上一避让机制在同帧叠加（项目规则已禁叠加，正确）[S5][A4]。
- **问题点 3（软件渲染路径的真实病根）**：软件渲染下"insets 派发时序不稳定"的真实机制是 **insets 到达与布局应用不同帧**（M3/M10，Flutter/Chromium 同款 DeferredIMEWindowInsetApplication 问题）。切 ADJUST_PAN 是绕开，不是根治；API 30+ 上 insets 管线与渲染后端无关（派发在 View 层），**根治是动画回调驱动布局 + isVisible 判定**，可在保留 ADJUST_PAN 兜底的同时逐步收敛 [A4][A5][B3]。

### 2.3 `WindowInsetsControllerCompat.hide` 驱动的系统栏隐藏（第二/三/四根因放大器）

- **问题点：hide 与 IME 同控制器对抗（M9）**。系统日志实证：键盘可见期间 hide(systemBars) 会被路由成 `hide(ime())`/requestedVisibleTypes 变更，与键盘 show 竞争 → insets 翻转 → 键盘弹出→收起→再弹出。Android 15 下三键导航时系统接管导航栏，应用 hide() 被系统忽略/对抗。
- **现行防御（freeze 计数 + shouldSkipHide 双守卫 + 键盘可见期间零系统栏操作）方向正确，与官方语义一致** [S8]。两个细化点：
  1. 解冻后恢复的 **350ms 固定延时是启发式**：ROM 键盘动画时长差异大（HyperOS 冷启动延迟键盘问题 2025-05 才修，见 M12），350ms 在动画 >350ms 的 ROM 上会"过早恢复 → 与残余动画对抗 → 再次振荡"。**官方等价物是 `WindowInsetsAnimationCompat.Callback.onEnd/onCancel` + isVisible 复查**，建议替换固定延时（保留延时仅作 onEnd 未触发的兜底）。
  2. hide/show 使用 `BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE`，避免每次 hide 触发 transient 再回落的双跳。

### 2.4 全局冻结计数 + 多窗口 IME 可见性跟踪（`ImeVisibilityTracker`）+ 不消费透传

- **方向正确**：多窗口跟踪（IME insets 只派发给持焦窗口，M5）与"任一窗口可见即全局冻结"是行业正解；**不消费透传正确**（M7，消费会截断下游监听）。
- **两个校验点**：
  1. **可见性判定必须用 `isVisible(ime())`（M6）**。若 tracker 内部用 `getInsets(ime).bottom > 0`，在动画收起/兼容模式下会**误判可见 → 冻结不解除/隐藏不恢复**，这是"界面反复下拉"的另一候选根因，**请核查 `ImeVisibilityTracker` 当前实现**（若确用 bottom>0，按 M6 立即改为 isVisible）[S4]。
  2. Compose 1.7/1.8 的 `WindowInsets.ime` 在 **Dialog 窗口上仍不可靠**（M5/M9 主题，IssueTracker 229378542 长期存在），多窗口跟踪需以"持焦窗口"为锚点（项目已按窗口 attach，正确）[B7]。

### 2.5 自动聚焦重试（`rememberImeAwareAutoFocusRequester`，800ms 重试 + hasTextInputFocus）

- **问题点：重试式 requestFocus 与 IME 动画取消的耦合**（M10）。系统实证：show/hide 请求在动画中发出会被 `PHASE_CLIENT_ANIMATION_CANCEL` 取消，且取消路径本身会触发 stale insets 重派发。**在检测信号不稳的 ROM 上反复 requestFocus，等于反复制造动画取消 → 键盘反复重弹**（项目第五根因辅助放大器已部分处理：已有焦点则跳过）。
- **官方/业界正解**：请求一次 `requestShowSoftInput`，用 `InputMethodManager` 回调/`InsetsListener` 确认结果；失败才有限重试，**绝不在 IME 动画运行中重试**；配合 `ViewCompat.setOnApplyWindowInsetsListener` 的 isVisible 作为"键盘已弹起"真值（重试以 isVisible 为终止条件）[S4][A3]。

### 2.6 症状-根因链路总评（"反复弹出/收起 + 闪屏 + 界面反复下拉"）

综合五轮根因与系统级机制，振荡回路可归纳为三类系统级循环，现行方案各覆盖一部分、但均依赖启发式：

1. **避让机制切换/叠加循环**（M1/M4/M5）：pan ↔ padding 叠加或运行期切换 softInputMode → insets 翻转。→ 已用"二选一 + 渲染模式感知"覆盖，但**切换时机未约束在"键盘不可见"**。
2. **hide 与 IME 对抗循环**（M9/M10）：hide(systemBars) ↔ IME show 动画取消 → stale insets 残留 → 恢复延迟猜错 → 再 hide。→ freeze 机制已覆盖主要路径，**350ms 延时是薄弱点**。
3. **可见性误判循环**（M6）：bottom>0 误判可见/不可见 → 错误恢复/错误冻结 → 反向操作。→ 需核查判定是否用 isVisible(ime)。

---

## 三、架构级判断与两方案

### 3.1 架构级判断

**这是架构级问题**：五轮补丁（每轮一个"根因"+防抖）已在机制层面相互缠绕（避让二选一 × 冻结 × 延时 × 重试），下一台 ROM/机型仍可能以新组合复发。所有现行防御建立在四类**启发式**上：

- 350ms 恢复延时（猜动画时长）
- 800ms 聚焦重试（猜信号不稳）
- ADJUST_PAN 平移（绕开 insets，而非消费动画）
- 冻结计数（猜窗口/键盘关系）

官方机制（M3/M6/M9/M10）提供了全部对应的**确定性等价物**：动画回调（onEnd/onCancel）、isVisible(ime) 真值、InsetsController 单控制器语义、IME 动画取消的 stale-insets 处理。

### 3.2 方案对比

| 维度 | 方案 (1)：只修眼前 | 方案 (2)：彻底重构（推荐） |
|------|------------------|--------------------------|
| 范围 | 替换启发式为确定性回调，不动架构 | 建立"单真相源键盘生命周期状态机 + 窗口级统一 insets 管线" |
| 具体动作 | ① 350ms 延时改 `WindowInsetsAnimationCompat.Callback.onEnd/onCancel` + isVisible 复查（延时仅兜底）；② 核查 `ImeVisibilityTracker` 判定改为 `isVisible(ime())`；③ 约束 softInputMode/hide 一切切换时机为"键盘不可见且无 IME 动画"；④ 聚焦重试改为"动画空闲期重试 + isVisible 终止" | ① 键盘生命周期状态机（isVisible + 动画回调为唯一真值，零固定延时）；② Activity/Dialog 窗口统一 insets 管线（Dialog 显式 `setDecorFitsSystemWindows(false)` + `ADJUST_RESIZE` + onProgress 驱动，ADJUST_PAN 降级为 API<30/ROM 特例兜底）；③ 系统栏操作统一收敛 InsetsControllerCompat + `BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE`；④ 全量回归国产 ROM 机型矩阵 |
| 风险 | 下个 ROM 动画时序不同仍可能复发 | 改动面大，需完整测试矩阵与灰度 |
| 成本倾向 | 低 | 高（按项目公约不计成本） |

> 按项目惯例（规则 11"退一步看全局"），如选 (2) 需按"设计方案规则"产出正式方案文档（背景与目标 / 技术方案 / 影响范围清单 / 兼容性分析 / 测试方案 / 风险评估与兜底 / 未来场景推演 / 技术债与偿还计划）。

---

## 四、完整参考清单（27 条：标题/URL/日期/等级/摘要）

> 访问日期：2026-08-31。持续更新类文档（devsite 页面、AndroidX 发布说明）均已在对应条目标注"持续更新"。

### S 级（Google/Android 官方，11 条）

| # | 标题 | URL | 日期 | 核心摘要 |
|---|------|-----|------|---------|
| S1 | Behavior changes: Apps targeting Android 15+（Edge-to-edge enforcement） | https://developer.android.com/about/versions/15/behavior-changes-15 | 2024（随 Android 15 稳定版发布，2024-09-03；持续更新） | targetSdk 35 强制 edge-to-edge：状态/导航栏透明、decorFitsSystemWindows 默认 false、三键导航系统接管导航栏加 scrim。M2 主依据 |
| S2 | Behavior changes: Apps targeting Android 16+（Predictive back） | https://developer.android.com/about/versions/16/behavior-changes-16 | 2025（随 Android 16 发布，2025-06；持续更新） | API 36 默认启用预测性返回，back 拦截语义变化。M11 主依据（经 SDL#13794 交叉确认含 #predictive-back） |
| S3 | Android 11 features（IME 动画同步 / WindowInsetsAnimation） | https://developer.android.com/about/versions/11/features | 2020-09-08（Android 11 发布） | WindowInsetsAnimation 机制诞生：onPrepare/onStart/onProgress/onEnd 与 IME 动画同步。M3 主依据 |
| S4 | Handle input method visibility | https://developer.android.com/develop/ui/views/touch-and-input/keyboard-input/visibility | 2024（Android 15 文档批次；持续更新） | 键盘可见性权威指南：三模式语义、兼容模式、**isVisible(ime) 判定**、透传写法、全屏/沉浸式注意事项。M1/M6/M7/M8 主依据 |
| S5 | Control and animate the software keyboard | https://developer.android.com/develop/ui/views/layout/sw-keyboard | 2020-06（Android 11 IME insets 文档；持续更新） | IME insets + WindowInsetsAnimation 完整教程，API<30 兼容路径。M3/M7 主依据 |
| S6 | 设置全屏显示（Compose edge-to-edge 指南，含 imePadding 前置条件/insets 更新时机） | https://developer.android.com/develop/ui/compose/system/setup-e2e | 2024（Compose 文档当前批次；持续更新） | Compose 侧 edge-to-edge 配置；imePadding 需 edge-to-edge+adjustResize；insets 在"组合后、布局前"更新。M4 主依据 |
| S7 | GameTextInput（AGDK） | https://developer.android.com/games/agdk/add-support-for-text-input | 2021（AGDK GameTextInput 发布；持续更新） | 游戏侧 IME 输入库：引擎自绘文本 + 系统 IME 输入连接，支持 IME insets，官方推荐游戏不用 EditText 而用 GameTextInput |
| S8 | Hide system bars for immersive mode | https://developer.android.com/develop/ui/views/layout/immersive | 2024（持续维护） | 沉浸式系统栏隐藏官方指南：BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE、避免频繁切换。M9 主依据 |
| S9 | Android Developers Blog: The First Beta of Android 15 | https://android-developers.googleblog.com/2024/04/the-first-beta-of-android-15.html | 2024-04-11 | 官方预告 Android 15 edge-to-edge enforcement 落地计划。M2 佐证 |
| S10 | Android Developers Blog: Android 15 is released to AOSP | https://android-developers.googleblog.com/2024/09/android-15-is-released-to-aosp.html | 2024-09-03 | Android 15 稳定版发布公告（edge-to-edge 正式生效）。M2 佐证 |
| S11 | 小米 HyperOS Weekly Bug Report Vol-198 | https://web.vip.miui.com/page/info/mio/mio/detail?isTop=0&postId=49908001 | 2025-05（Vol-198 周报） | 小米官方修复键盘动画缺陷：键盘区域与键盘本体动画不同步、冷启动键盘弹出延迟。M12 主依据 |

### A 级（AndroidX 官方发布说明 / Google 团队工程 issue / 知名开源方案，6 条）

| # | 标题 | URL | 日期 | 核心摘要 |
|---|------|-----|------|---------|
| A1 | AndroidX Games 发布说明（GameTextInput 1.1，新增 IME insets 能力） | https://developer.android.com/jetpack/androidx/releases/games | 2022-01-26（Games-Text-Input 1.1 系列；持续更新） | GameTextInput 1.1 起支持 IME insets 查询，游戏侧键盘避让官方能力。M4 佐证 |
| A2 | AndroidX Compose Foundation / Compose UI 发布说明 | https://developer.android.com/jetpack/androidx/releases/compose-foundation | 1.7.0=2024-10、1.8.0=2025-01（版本日期；持续更新） | Compose 1.7/1.8 起持续修复 insets/IME/Dialog 相关问题（含自定义 Dialog/Popup 行为变更条目）。M5 佐证 |
| A3 | flutter/flutter#191156：[Android] viewInsets.bottom 在后台返回后残留键盘高度 | https://github.com/flutter/flutter/issues/191156 | 2026-08-15 | Google Flutter 团队 P1 issue：完整日志实证 `ImeTracker: onCancelled at PHASE_CLIENT_ANIMATION_CANCEL`、`InsetsController: hide(ime())`、requestedVisibleTypes 503/511——stale IME insets 重派发全链路。M9/M10 主依据 |
| A4 | flutter/flutter#191094：[Android] View resize 与 IME 动画不同步 | https://github.com/flutter/flutter/issues/191094 | 2026-08-14 | Flutter Android 团队 triaged P1：内容 snap（动画结束后单步跳变）+ 收起时空隙（blank gap）实证，adjustPan 平滑但保持全视口。M3 佐证 |
| A5 | flutter/flutter PR#191228：stale IME inset 回归测试 | https://github.com/flutter/flutter/pull/191228 | 2026-08-17 | `ImeSyncDeferringInsetsCallback` 缓存旧 insets 的根因分析 + 修复方向（onEnd 不应重派发旧于平台最新值的快照）。M10 佐证 |
| A6 | Jacksgong/JKeyboardPanelSwitch | https://github.com/Jacksgong/JKeyboardPanelSwitch | 2015-07-01（持续维护，4.1k star） | 键盘-面板切换闪动处理经典方案，记录魅族 SmartBar 等国产 ROM 特例；"键盘高度变化→切换闪动"机制最早的中文系统化梳理。M12 佐证 |

### B 级（高质社区/媒体，含中文高赞，10 条）

| # | 标题 | URL | 日期 | 核心摘要 |
|---|------|-----|------|---------|
| B1 | SDL#13794：Android API 36 back 无法再被拦截 | https://github.com/libsdl-org/sdl/issues/13794 | 2025-08-25 | SDL 维护者实证：API 36 即使拦截 back 仍退后台，需迁移 OnBackInvokedDispatcher；提及与 IME 相关。M11 佐证 |
| B2 | XimiTime: Xiaomi fixes HyperOS keyboard animations lag | https://ximitime.com/xiaomi-fixes-hyperos-keyboard-animations-lag-46512/ | 2025-05-20 | HyperOS Vol-198 键盘动画修复详情解读（冷启动延迟 + 区域/键盘动画不同步），引用官方 Bug Report。M12 佐证 |
| B3 | 掘金：Android 键盘高度监听方案探究 | https://juejin.cn/post/7473397122123202570 | 2025-02-20 | GlobalLayout/PopupWindow 局限与 R+ WindowInsetsAnimation 方案对比；明确"华为/三星等 ROM 上 compat 路径不可靠"。M6/M8 佐证 |
| B4 | 掘金：Android Compose Dialog 唤起键盘后…解决方法 | https://juejin.cn/post/7141286019365601310 | 2022-09-09 | Compose 1.2 实证：imePadding 在 Dialog 内无效、在 Activity 内有效；给出自定义 Dialog 与 Activity 内嵌两种方案。M5 主依据（中文） |
| B5 | StackOverflow：Jetpack Compose - imePadding() for AlertDialog | https://stackoverflow.com/questions/74276974/jetpack-compose-imepadding-for-alertdialog | 2022-11-01 | AlertDialog 忽略 imePadding 的社区共识问答（键盘遮挡列表）。M5 佐证 |
| B6 | StackOverflow：Compose ime padding + Scaffold + edge-to-edge + adjustResize | https://stackoverflow.com/questions/73894748 | 2022-09-29 | 35 分高赞：IME 动画期间导航栏 padding 被双重计入（链接 IssueTracker 249727298）。M4 佐证 |
| B7 | evant/compose-dialog-window-insets-issue | https://github.com/evant/compose-dialog-window-insets-issue | 2024-03-18 | 复现工程实证：Compose Dialog 窗口 insets 恒 0、decorFitsSystemWindows 无效、enableEdgeToEdge 不传导。M5 主依据 |
| B8 | StackOverflow：SOFT_INPUT_ADJUST_RESIZE deprecated starting android 30 | https://stackoverflow.com/questions/68003131/soft-input-adjust-resize-deprecated-starting-android-30 | 2021-06-16（问答持续至 2021-12） | 官方 javadoc 弃用说明 + setDecorFitsSystemWindows/insets 替代路径（社区高赞）。M1 佐证 |
| B9 | Habr：Взаимодействие с клавиатурой в Compose | https://habr.com/ru/articles/845124/ | 2024-09-21 | 实证：imePadding 在 Android 仅当 adjustResize + enableEdgeToEdge 双条件满足时生效；adjustResize 单独使用有动画空洞。M4 佐证 |
| B10 | CSDN：从窗口层级到现代 API：Android 软键盘适配的演进与最佳实践 | https://blog.csdn.net/fern8/article/details/155996714 | 2026-08-11 | Dialog 不继承 Activity softInputMode 的窗口层级解析；API 30+ 弃用说明与 WindowInsets 现代实践。M5/M8 佐证（中文） |

### 补充指针（日期未能直接核验，不计入清单）

- Google IssueTracker #229378542「imePadding doesn't work inside Dialog」——M5 主题（与 B4/B5/B7 互证）
- Google IssueTracker #248529694「DialogProperties.decorFitsSystemWindows adds random top margin to dialog」——M5 主题
- Google IssueTracker #328691355「IME padding includes navigationBars padding during keyboard animation」——M4/M9 主题（与 B6 互证）
- Google IssueTracker #249727298「Scaffold 双重 padding」——M4 主题（B6 中引用）

---

## 五、更新记录

| 日期 | 变更 |
|------|------|
| 2026-08-31 | 初版：12 条机制清单 + 6 组评审 + 架构级判断（两方案）+ 27 条参考清单 |
