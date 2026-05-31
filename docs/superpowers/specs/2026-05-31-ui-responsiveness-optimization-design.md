# UI 响应速度优化设计：即时骨架屏 + 渐进式加载（代码核查修正版）

> 日期: 2026-05-31
> 状态: 待实现
> 影响模块: `ui/game/components/GameOverlayHost.kt`, `ui/game/GameViewModel.kt`

---

## 1. 背景与目标

### 1.1 当前问题

用户点击按钮后，弹出的界面（对话框/全屏覆盖）响应速度偏慢，体感上有明显延迟。原因链路：

```
按钮点击 → navigateToDialog() → when(route) 分支匹配
  → 同步收集 collectAsState()（Alchemy=5个, Forge=4个, 多数分支 0-2个）
  → gameDataUi 可能处于 400ms 采样间隔中（注：WhileSubscribed(5000) 使 5 秒内重开不受影响）
  → aliveDisciples 在 10/24 分支中重复进行 derivedStateOf 计算
  → 无入场动画，无即时视觉反馈
  → 用户感知延迟 = 数据准备 + 首帧组合
```

> **代码核查结论**：原方案声称"每个分支 3-6 个 collectAsState、每个分支都算 aliveDisciples"不准确。
> 实际仅 Alchemy(5) 和 Forge(4) 达此量级，aliveDisciples 仅 10/24 分支重复。瓶颈集中在重数据界面。

### 1.2 目标

| 指标 | 当前 | 目标 |
|------|------|------|
| 点击到视觉反馈 | 无反馈直到内容就绪 | < 50ms（框架出现） |
| 完整内容显示 | 取决于数据准备 | < 200ms |
| 感知体验 | "卡顿" | "即时且流畅" |

### 1.3 游戏行业依据

- **Nielsen 100ms 法则**：用户感知 100ms 内的响应为"即时"
- **原神/崩坏星穹铁道**：面板动画 120-180ms，Ease-Out 曲线为主。采用信息层级渐入（立绘→标签→列表项），而非所有元素同时出现
- **骨架屏**：移动端标准感知优化。注意——Viget 研究（136人）发现骨架屏在陌生界面感知延迟更长，在**熟悉界面**表现优秀。宗门管理面板属于高频熟悉界面，适用。低频对话框（如兑换码）可跳过
- **100-200ms 入场动画**既给用户即时反馈，又为数据准备争取时间

**来源**: [Nielsen 响应法则](https://www.lukew.com/ff/entry.asp?1797), [HSR UI 设计案例研究](https://www.artstation.com/blogs/eyween/WBeAB/what-you-can-learn-from-hoyoverse-honkai-star-rail-and-mobile-gaming), [骨架屏研究(Viget)](https://www.viget.com/articles/a-bone-to-pick-with-skeleton-screens)

---

## 2. 瓶颈分析

### 瓶颈 1：点击后无即时视觉反馈

当前 `when(route)` 直接渲染完整对话框内容，没有任何过渡效果。用户从点击到看到内容，中间无任何视觉变化。

**位置**: `GameOverlayHost.kt` L124-L437
**影响**: 所有 24 个 DialogRoute 分支

### 瓶颈 2：重数据对话框首次组合时 StateFlow 收集密集

**实际分布**（代码核查结果）：

| 分支 | collectAsState() 数 | aliveDisciples? |
|------|---------------------|-----------------|
| **Alchemy** | **5** (`alchemySlots`, `materials`, `herbs`, `gameData`, `disciples`) | 是 |
| **Forge** | **4** (`forgeSlots`, `materials`, `gameData`, `disciples`) | 是 |
| Library/WenDaoPeak/QingyunPeak/TianshuHall/LawEnforcementHall/MissionHall/ReflectionCliff | 2-3 | 是 |
| HerbGarden | 2 | 是 |
| WorldMap | 3 | 否 |
| Recruit/Planting | 2 | 否 |
| Diplomacy/Merchant/SalaryConfig/BattleLog/PatrolTower/Residence/WarehouseBuilding | 1 | 否 |
| Disciples/Warehouse/Settings/Buildings/SpiritMine/GameOver/None | 0 | 否 |

瓶颈集中在 **Alchemy 和 Forge**。其余分支的 StateFlow 收集量不构成瓶颈。

### 瓶颈 3：gameDataUi 的 400ms 采样间隔

```kotlin
// GameViewModel.kt L245-L247
val gameDataUi: StateFlow<GameData> = gameEngine.gameData
    .sample(400)
    .stateIn(viewModelScope, sharingStarted, gameEngine.gameData.value ?: GameData())
```

实际影响需分情况：
- **首次打开对话框**（流未活跃）→ 最多等 400ms
- **5 秒内重开**（`WhileSubscribed(5000)` 保持流活跃）→ 不受影响，立即获取
- **5 秒后重开**（流已停止）→ 重新初始化，再次面临 400ms 最坏延迟

**额外问题**：`MainGameScreen.kt` L134 顶层也在订阅 `gameDataUi`。如果用 `flatMapLatest` 去采样，主界面也会每 tick 收到更新 —— 增加主界面重组频率。方案需对此做权衡。

### 瓶颈 4：aliveDisciples 在 10 个分支中重复计算

```kotlin
val aliveDisciples = remember(disciples) { derivedStateOf { disciples.filter { it.isAlive } } }
```

10 个分支各自维护一份 `derivedStateOf`，虽然 Compose 会缓存，但首次计算仍需遍历列表，且每次重组都会重新评估。提升到 ViewModel 层可消除此重复。

### 瓶颈 5：gameData 在 ~15 个分支中各自订阅

```kotlin
val gameData by viewModel.gameDataUi.collectAsState()
```

出现在 15+ 个分支中。虽不影响性能，但增加了 StateFlow 订阅数。提升到 `GameOverlayHost` 顶层共享一个订阅可减少开销。

### 瓶颈 6：无入场动画作为"感知缓冲"

当前 `when(route)` 直接渲染，没有 `AnimatedVisibility` 或过渡动画。缺少动画意味着：
- 用户无法区分"正在加载"和"应用卡死"
- 没有为数据准备争取时间窗口

---

## 3. 方案设计

### 3.1 入场动画层（P0 — 最优先）

**目标**: 点击按钮后 50ms 内出现视觉反馈，150ms 内完成入场动画。

**实施**: 在 `GameOverlayHost` 的 `when` 块外层包裹单个 `AnimatedVisibility`，由 `currentDialogRoute != None` 驱动可见性。避免每个分支单独包裹（代码重复）。

```kotlin
// GameOverlayHost.kt
@Composable
fun GameOverlayHost(viewModel: GameViewModel, ...) {
    val currentDialogRoute by viewModel.currentDialogRoute.collectAsState()
    
    AnimatedVisibility(
        visible = currentDialogRoute != DialogRoute.None,
        enter = fadeIn(animationSpec = tween(120)) +
                slideInVertically(
                    initialOffsetY = { it / 20 },
                    animationSpec = tween(150, easing = FastOutSlowInEasing)
                ),
        exit = fadeOut(animationSpec = tween(100))
    ) {
        when (currentDialogRoute) {
            // 原有对话框内容，无需额外包裹
            is DialogRoute.Alchemy -> { ... }
            is DialogRoute.Forge -> { ... }
            // ...
        }
    }
}
```

**参数选择依据**:

| 参数 | 值 | 理由 |
|------|----|------|
| fadeIn | 120ms | 比滑入稍短，避免"漂"感。原神面板 120-180ms 区间内 |
| slideIn | 150ms | 微妙位移(~5%屏高)，FastOutSlowInEasing 符合物理直觉 |
| fadeOut | 100ms | 快速退场，不阻塞后续操作 |

### 3.2 骨架分层（P1）

**目标**: 标题栏第一帧渲染，数据内容延迟一帧（16ms）。配合 150ms 入场动画，内容在动画完成前已就绪。

**覆盖范围**（修正：不仅 `FullScreenOverlay`）:

| 对话框容器 | 处理方式 |
|-----------|---------|
| `FullScreenOverlay` | 骨架分层内置（修改此组件） |
| `FullScreenOverlayWarehouse` | 继承 `FullScreenOverlay` 的修改 |
| RecruitDialog / DiplomacyDialog 等独立组件 | 各自包裹 `DeferredContent` |

**修改 `FullScreenOverlay`**:

```kotlin
@Composable
private fun FullScreenOverlay(
    title: String,
    onDismiss: () -> Unit,
    actions: @Composable (() -> Unit)? = null,
    content: @Composable () -> Unit
) {
    Surface(modifier = Modifier.fillMaxSize(), color = GameColors.PageBackground) {
        Box(modifier = Modifier.fillMaxSize()) {
            Image(painter = painterResource(id = R.drawable.bg_horizontal), ...)
            Column(modifier = Modifier.fillMaxSize().padding(horizontal = 32.dp)) {
                // 第一帧：标题栏同步渲染
                Row(...) {
                    Text(title, ...)
                    Spacer(modifier = Modifier.weight(1f))
                    actions?.invoke()
                    CloseButton(onClick = onDismiss)
                }
                // 数据内容：延迟一帧，避免阻塞首帧
                DeferredContent {
                    content()
                }
            }
        }
    }
}
```

**DeferredContent**（加防 flicker）:

```kotlin
@Composable
fun DeferredContent(content: @Composable () -> Unit) {
    var showContent by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        showContent = true
    }
    if (showContent) {
        content()
    } else {
        // 3 行灰色占位条，宽度递减
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            repeat(3) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(0.8f - it * 0.15f)
                        .height(16.dp)
                        .background(Color(0x1A000000), RoundedCornerShape(4.dp))
                )
            }
        }
    }
}
```

> **防 flicker**：`LaunchedEffect(Unit)` 确保只在首次组合时延迟，重组不重新触发。如需防极端场景，可加 `rememberSaveable` 持久化 `showContent` 状态。

> **骨架屏适用性**：对 Alchemy/Forge/Library 等高频界面使用骨架屏。Recruit/Diplomacy/Merchant 等低频对话框只靠入场动画即可，不加骨架——Viget 研究表明不熟悉界面中骨架屏可能适得其反。

### 3.3 数据流优化

#### 3.3.1 gameDataUi 采样策略优化（P2）

**当前**: `.sample(400)` 统一节流。`WhileSubscribed(5000)` 使 5 秒内重开不触发最坏延迟。

**优化**: 对话框打开时用 `snapshot` 立即获取当前值，不改变流本身。避免影响 `MainGameScreen` 的订阅。

```kotlin
// GameViewModel.kt
private val _dialogOpenTrigger = MutableSharedFlow<Unit>(replay = 0)

val gameDataUi: StateFlow<GameData> = gameEngine.gameData
    .sample(400)
    .stateIn(viewModelScope, sharingStarted, gameEngine.gameData.value ?: GameData())

fun navigateToDialog(route: DialogRoute) {
    _currentDialogRoute.value = route
    // 对话框打开时立即推送一次最新值
    viewModelScope.launch {
        _dialogOpenTrigger.emit(Unit)
    }
}
```

> **权衡**：不做 `flatMapLatest`（去采样），因为那会影响 MainGameScreen 主界面的重组频率。改为只解决"打开对话框时等 400ms"的问题——打开时主动 snapshot 一次即可。

#### 3.3.2 aliveDisciples 提升到 ViewModel（P1）

```kotlin
// GameViewModel.kt 新增
val aliveDisciples: StateFlow<List<DiscipleAggregate>> = disciples
    .map { it.filter { d -> d.isAlive } }
    .distinctUntilChanged()
    .stateIn(viewModelScope, sharingStarted, emptyList())
```

10 个分支从：
```kotlin
val disciples by viewModel.disciples.collectAsState()
val aliveDisciples = remember(disciples) { derivedStateOf { disciples.filter { it.isAlive } } }
```
改为：
```kotlin
val aliveDisciples by viewModel.aliveDisciples.collectAsState()
```

#### 3.3.3 gameData 提升到 GameOverlayHost 顶层共享（P2）

```kotlin
// GameOverlayHost.kt — 顶层收集一次
val gameData by viewModel.gameDataUi.collectAsState()

when (currentDialogRoute) {
    is DialogRoute.Alchemy -> AlchemyDialog(gameData = gameData, ...)
    is DialogRoute.Forge -> ForgeDialog(gameData = gameData, ...)
    // 各分支不再各自 collectAsState(gameData)
}
```

---

## 4. 改动汇总

| 改动 | 文件 | 优先级 | 效果 |
|------|------|--------|------|
| 入场动画 `AnimatedVisibility` 包裹 `when` 块 | `GameOverlayHost.kt` | **P0** | 点击后 <50ms 视觉反馈，150ms 动画完成 |
| 骨架分层修改 `FullScreenOverlay` | `GameOverlayHost.kt` | P1 | 标题栏首帧渲染，延迟数据一帧 |
| `aliveDisciples` 提升到 ViewModel | `GameViewModel.kt` + `GameOverlayHost.kt` | P1 | 消除 10 处重复 `derivedStateOf` |
| gameData 提升到 `GameOverlayHost` 顶层 | `GameOverlayHost.kt` | P2 | 减少 15+ 个 StateFlow 订阅 |
| gameDataUi 对话框 snapshoot | `GameViewModel.kt` | P2 | 消除 400ms 首次延迟，不影响主界面 |

---

## 5. 实施顺序

1. **P0: 入场动画** — 改动最小，一个 `AnimatedVisibility` 包裹，`when` 块内零修改
2. **P1: aliveDisciples 提升** — ViewModel 加一行 `.map{}`，10 分支各改一行
3. **P1: 骨架分层** — `FullScreenOverlay` 加 `DeferredContent`，Alchemy/Forge 等重界面优先，低频界面跳过
4. **P2: gameData 共享 + snapshot** — 可选，边际收益不如前三步明显

每步独立可测。

---

## 6. 风险与约束

| 风险 | 缓解措施 |
|------|----------|
| 动画掩盖真实慢查询 | 150ms 动画，不掩盖超过 200ms 的延迟 |
| `DeferredContent` 重组 flicker | `LaunchedEffect(Unit)` + 可选 `rememberSaveable` |
| 骨架屏在陌生界面适得其反 | 仅 Alchemy/Forge/Library 等高频界面启用骨架，低频界面跳过 |
| `MainGameScreen` 受去采样影响 | 不做 `flatMapLatest`，改为 open 时 snapshot |
| `aliveDisciples` 独立 StateFlow 增加内存 | 只多一个 List 引用，开销可忽略 |
| `when` 外层动画导致对话框切换时退场+入场 | `key(currentDialogRoute)` 确保路由切换时触发退场/入场对 |

---

## 7. 验证方式

1. **编译**: `./gradlew.bat compileReleaseKotlin`
2. **运行时**: 低端设备(minSdk 24)测试各对话框打开速度，尤其 Alchemy/Forge
3. **Layout Inspector**: 确认重组次数减少
4. **逐帧对比**: 录制优化前后屏幕，对比点击到首次视觉反馈的时间
5. **回归**: 所有对话框功能正常，关闭操作不受影响
