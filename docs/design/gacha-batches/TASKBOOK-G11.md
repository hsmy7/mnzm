# TASKBOOK-G11 · 最简寻访 UI（主界面 + 结果页 Q30/Q31 + 图鉴最小 + `GachaDelegate` 接线）

> **本文件是 G11 的派工真源**，取代 `recon-G05-G06-G08-G09.md` §G11（`:578-746`）全部落点表
> 与 `HANDOVER-m1-remaining-3.md` §8.G 的内联清单。上位失真逐条列在 §2，**执行者只认本文件**。
> **开工时点**：2026-09-26，G09 已收官（`15eef2b2d`）；HEAD 与工作树以开工当轮 `git status` 为准。
> **侦察方式**：主线程直读 ＋ 3 个只读子代理穷举（G09 交付面 / UI 落点与色表 / 入口·引导·素材·守卫）。
> 行号为本轮实测；**标 ⚠️ 者未直读**，实施时按 `grep` 复核。

---

## 0. 剩余工作总纲（M1 剩余 = G11 → G10）

| 批次 | 内容 | 入口判据 | 出口判据 | 硬门 |
|---|---|---|---|---|
| **G11**（本批） | 最简寻访 UI：主界面（价签/保底 x/10/余额/概率公示/历史/图鉴入口）＋ 结果页 Q30/Q31 ＋ 图鉴最小 ＋ `GachaDelegate` 接线 | G09 单次提交（`15eef2b2d`）＋ DTO 冻结（`GachaPullResult.Success`/`GachaPullRow`，只带 id）＋ G16 的 12 键四方齐备 | **真机通**（M1 完成判据之一）：进寻访→单抽→十连→结果页显示头像→图鉴/历史/公示可开→关窗不软锁；四门全绿；`report-G11.md` 写清真机做到哪一步、未做项显式登记 | UI 不驱动 tick；不新增第二通知总线；**不动 native 渲染链**（流光走 Compose 层，见 D-6）；零 Room 迁移；零新镜像字段/反向通道 |
| **G10** | RNG 对拍基线**唯一一次**重录 ＋ 全量回归 ＋ 死代码 grep 清零 ＋ 文档收口 | G11 单次提交；工作树干净 | ctest 全绿（金序列已重录）＋ 全量 JUnit 绿 ＋ `HANDOVER-3` §6.3 累积登记逐条处置 ＋ 死代码归零 | 重录窗口唯一；含 G09 追加的两条必核面（`rngStates` 键集条数断言、`RngSourceGuardTest` 抽卡分区使用计数） |

**M1 完成判据**：G10 全绿 ＋ G11 最简 UI 真机通。

---

## 1. 目标与验收判据

| 项 | 内容 |
|---|---|
| 业务目标 | 把 G09 已打通的抽卡核心接上界面：玩家从左栏入口进「寻访」，看到价签/保底进度/灵石余额，点一次或十次，看到 Q30 规格的结果页（Q31 色表），并能看图鉴、历史、概率公示 |
| 验收① | 入口改道：左栏按钮文案 `招募`→`寻访`，点开进**寻访主界面**（不是占位）；全仓 `src/main` 对 `"寻访功能尚未开放"` **零命中** |
| 验收② | 主界面显示：单抽价 / 十连价（均来自 `GachaPoolConfig.pool("standard")!!.pricePerPull`，**禁硬编码**）、保底进度 `x/threshold`、灵石余额；余额不足时按钮 `enabled=false` 且价格转红（照抄 `MerchantDialog` 三元组） |
| 验收③ | 结果页**格序 = 抽取序**（`Success.rows` 下标即格序，十连第 10 格在末位）；单抽 1 格**居中**、十连 2×5 铺满；**禁拿 `history` 排序当格序**（历史是「新在前」） |
| 验收④ | 结果页色表**强制 Q31 单一源**：物品=品阶色、角色碎片=灵根数色；`#ffd700`(六/单) `#f44336`(五/双) `#9c27b0`(四/三) `#2196f3`(三/四) `#4caf50`(二) `#b8b8b8`(一/五)；**禁引** `ItemCard.getRarityColor` / `GameColors.getRarityColor` / `GameConfig.Rarity` 旧表（§2.2 列了 8 张旧表） |
| 验收⑤ | 角色格读 **512 档 `avatarKey`**（`CharacterTemplateDb.byId(tid)!!.avatarKey`），**禁读 `portraitKey`**（1024 档，G08 移交必办）；数量角标为框内右下角白字 `×n` |
| 验收⑥ | 图鉴 6 格：未解锁（`tid !in starMap`，**稀疏账本语义**）置灰；已解锁显示 `★×star` 与「下一星 `fragmentCounts[tid]`/100」；满星（5）显示 MAX；全身立绘读 `portraitKey` |
| 验收⑦ | `GachaDelegate` 接线完成：`GameVmDelegateServices` 注入 `gachaFacade`、`GameViewModel` 暴露 `gacha`；三个 suspend 方法**在引擎线程**内调用（`gameEngine.launchOnEngine { }`） |
| 验收⑧ | 零新镜像字段 / 零反向通道 / UI 不直写 Store / 无新 tick：`MirrorReadOnlyGuardTest` ＋ `MirrorConsumerSurfaceGuardTest` ＋ `ViewModelArchitectureTest` ＋ `DialogTypeRenderCoverageTest` 全绿；`docs/ui-read-surface.md` §2.1 末句与 §3.2 已回写登记 |
| 验收⑨ | **真机通**：截图/logcat 证据 ＋ 未做项写进 `report-G11.md` 的 pending-device 节 |
| 验收⑩ | 双 changelog ＋ `report-G11.md` ＋ 单次提交 |
| **不做** | 不做轮换池/UP（M3）；不做十连逐格翻牌动画（§10 明写「不做长动画」）；不做埋点上报（§15.6 本次可不实现，只登记事件名）；不改动仓储/奖励弹窗的旧色表（色板对齐债登记 G12，§2.2）；**不动 native 渲染器**（D-6）；零 Room 迁移/零 `@ProtoNumber`；不改版本号 |

---

## 2. 🔴 上位失真与既有地雷（执行者必读）

### 2.1 上位交接失真

| # | 上位记载 | 实测真值 | 后果 |
|---|---|---|---|
| 1 | `HANDOVER-3` §8.G-3「流光/特效须 **Vulkan + Canvas 双路径可过**并更新 `android/docs/renderer-feature-checklist.md`」 | ❌ **不适用于本实现路径**：寻访界面是 Compose `Dialog` 独立窗口（`GameDialog.kt:116-134`），**不经过** `NativeSurfaceView` / `SoftwareCanvasBackend`；`SoftwareCanvasBackendTest` 物理上覆盖不到 Compose 窗口内的动画 | 照字面做要改 `NativeBridge.cpp` / `VulkanBackend.cpp` / `SoftwareCanvasBackend.kt` ＋ 清单四处，与「最简 UI」口径冲突。**D-6 决定走 Compose 层**，任务书显式声明「不适用该清单」并给理由，避免审计判漏更新 |
| 2 | §8.G-3「对话框按 `rules/new-dialog-checklist.md` 注册 `DialogType` + `GameOverlayHost` 穷举分支」 | ⚠️ **本批不必新增 `DialogType`**：`DialogType.Recruit` 已存在且已进 `OverlayDialogRouter` 穷尽 when（`:35-40`）与 `DialogFeatureRoutes`（`:53`），只换分支体即可 ⇒ `DialogType.kt` / `OverlayDialogRouter.kt` / `DialogTypeRenderCoverageTest.kt` / `GameRoute.kt` **全部零改**（D-1） | 新增 `DialogType.Gacha` 反而要同步 4 处 + 覆盖守卫 |
| 3 | `recon-G05-G06-G08-G09.md` §G11-2/§G11-3 引 `RecruitDialog.kt`、`ItemCardData.isDisciple`、`DiscipleComponents.kt` 位于 `ui/game/components/` | ❌ 均已失效：`RecruitDialog.kt` 随 G05 删除（仅存于 `.worktrees/`）、`ItemCardData.isDisciple` 字段不存在（幻觉）、`DiscipleComponents.kt` 实为 `ui/components/` | 行号/类名派工必扑空 |
| 4 | §G11-6「灵石余额/费用展示与禁用态的现有范式」 | ✅ 成立且已定位到逐行可抄的三元组：`MerchantDialog.kt:594-595 canAfford` / `:603` 单价 / `:624-625` 价格变红 / `:628 GameButton(enabled=canAfford …)` / `:250-261` 顶部余额栏 | 直接照抄，勿自创 |
| 5 | §G11-4「12 键四方齐备，UI 直接用 `SpriteImage("avatar_zhouming")`」 | ⚠️ 口径需精确化：结果格/图鉴**不得**写死键名，必须 `CharacterTemplateDb.byId(tid)!!.avatarKey`（`GachaPullRow` **只带 id**，D-10）；且大图类**不进预加载**（`ResourcePreloader.kt:266-269` 明文排除 `CHARACTER`）⇒ 只能按需 decode | 写死键名 = 第二真源；加预载 = 违反既有决策 |
| 6 | §8.G-4「真机通」 | ✅ 成立；**仓库无中央 pending-device 台账**（分散在各批报告），G11 登记在 `report-G11.md` 自己的节 | — |

### 2.2 🔴 色表地雷（本批最易踩）

**Q31 唯一正确源**：`core/domain/.../GameConfig.kt:244-263` 的 `RARITY_COLORS`(1..6) / `SPIRIT_ROOT_COUNT_COLORS`(1..5) / `rarityColor(r)` / `spiritRootCountColor(n)`（**返回 `String`**）。
**本仓现存 8 张色表**（下表 1–6 与寻访相关，**Q31 禁引**）：

| # | 定义点 | 与 Q31 的冲突 |
|---|---|---|
| 1 | `core/ui/.../theme/Color.kt:40-52` ＋ `:110-118 GameColors.getRarityColor` ＋ `:164` 顶层同名函数 | 六阶 `#E3A0A0` **粉红**（Q31 要**金**）、五阶 `#E7C67D` |
| 2 | `core/ui/.../components/ItemCard.kt:337-345 getRarityColor` | 与 #1 逐值重复的**第二份**定义；🔴 `ItemCard.kt:79` 硬编码走它，且**品阶色只进背景**（`:189`）、边框恒 `GameColors.Border`（`:152-153`）⇒ **直接复用 `UnifiedItemCard` 做奖励框即违反 Q31** |
| 3 | `GameConfig.kt:289-309 Rarity.CONFIGS[].color` ＋ `:319 getColor` | 第三份（消费 `Items.kt:26`、`RewardDialog.kt:57`、`SettingsTab.kt:145`） |
| 4 | `Color.kt:178-185 XianxiaColorScheme.rarityColors` | 第四份 map |
| 5 | `Color.kt:71-75 SingleRoot…PentaRoot` ＋ `:143-150 getSpiritRootCountColor` | 灵根数 **单=红/双=橙**，与 Q31 单=金/双=红 **完全相反** |
| 6 | 🔴 `core/domain/.../model/Disciple.kt:299-306 Disciple.spiritRoot.countColor` | **消费最广的灵根徽章源**（10 处消费点）——只改 #5 不生效 |
| 7 | `Color.kt:65-69 SpiritRoot{Metal..Earth}` ＋ `:134-141` | 元素色（非数量色），Q31 未覆盖 ⇒ 不动 |
| 8 | `ItemCard.kt:361-379 getQualityColor(String)/PillGrade` | 丹药品位色表 ⇒ 与本批无关 |

**处置（D-5）**：本批**新建单一换算源** + **只让寻访域与灵根徽章走 Q31**：
- `:core:ui` 新增 `GachaColors`（`String → Color` 换算，本仓此前只有 11 处内联 `parseColor`，**无共享 helper**）；
- 寻访结果页/图鉴 = 自建 `GachaRewardCell`（**不复用** `UnifiedItemCard`），色取 `GameConfig.Gacha.rarityColor / spiritRootCountColor`；
- `Disciple.spiritRoot.countColor`（`:core:domain`）**改为委托** `GameConfig.Gacha.spiritRootCountColor(size)` ⇒ 10 处消费者自动跟随（产品 §4.4 要求「角色碎片框 + 弟子卡/列表灵根徽章同一套」）；
- #1–#4、#8 旧表**本批不动**，登记为「仓储/奖励弹窗色板对齐债（G12）」（产品 `:218` 明文允许「至少寻访结果页 + 灵根徽章强制本表」）。

### 2.3 接线地雷

| # | 事实 | 后果 |
|---|---|---|
| 1 | `GameVmDelegateServices`（`GameVmServices.kt:41-49`）**不含 `GachaFacade`** | 加参数会**同时打破 4 个测试构造点**：`GameViewModelTest.kt:216`、`GameViewModelRoadFeedbackTest.kt:133`、`GameViewModelMovingBuildingBusTest.kt:136`、`GameViewModelSectMapTest.kt:131` ⇒ 必须同片改，别当"顺手改测试" |
| 2 | `DialogType` 在 `:core:domain`，`GachaPullRow` 在 `:core:engine`；依赖方向 `core:domain ← core:engine` | **结果 DTO 不能塞进 `DialogType` 参数**；数据只能走 VM `StateFlow` 或 `UnifiedGameDialog(overlay=…)` |
| 3 | `GameNotification` 仅 1 个变体（`RecruitFailed`），`pendingNotification` 是**单槽** `StateFlow`，且**无公开写入口** | 十连一次解锁多人时单槽放不下 ⇒ D-4：**升星层做在寻访窗口内的本地状态**，不写 `pendingNotification`、不新建总线 |
| 4 | `itemState`：`GachaService.grantFragmentsLocally` 就地改 map 不 `copy()`（report-G09 §6.2-6 登记） | 🔴 **接 UI 前必核**：四个 StateFlow 是 `gameData.map{}.stateIn`，若 map 引用不变则订阅可能不发射 → 结果页/图鉴不刷新。→ **D-7 必须先写一条最小复现测试**（订阅 `starMap`，调 `pullOnce`，断言发射），不过则改为 `copy()` 或 `updateAndReturn` 内新建实例 |
| 5 | `GuideTaskRegistry.ALL_TASKS` 实测 id 集 1–23、25（**无 24**），`GuideCondition` 无跳转能力 | ✅ **G02 式引导卡死在 G11 不存在**（历史 id 24 是血炼强化，随 G04 整拆）⇒ 无需改判据 |
| 6 | `GameOverlayHost.kt:385` 用 `key(currentDialogType)` 重组 | 同一 `DialogType` 内换内容（下一轮结果）**不会**自动重组 ⇒ 结果层需自行 `key(resultToken)` |

---

## 3. 需求口径（Q30/Q31 规格 → 实现）

### 3.1 主界面（`DialogFeatureRoutes.kt:68-92` 占位体整段替换）

- 容器：`UnifiedGameDialog(mode = Full, titleColor = GameColors.Gold, scrollableContent = false, headerActions = { 灵石余额栏 })`
- 内容：池名（「常驻寻访」）、单抽价 `pricePerPull`、十连价 `pricePerPull × 10`、保底进度 `pityCounters["standard"] ?: 0` / `pity.pullThreshold`、双按钮（`ButtonSizes.StandardWidth/Height`；余额不足 `enabled=false` ＋ 价格转红）、三个入口（概率公示 / 历史 / 图鉴）
- 数据来源：`GachaPoolConfig.pool("standard")`（**唯一**价格与概率读面，禁硬编码）+ `GachaService` 四流（或经 `gameDataUi` 派生）
- ⚠️ 引导无「打开寻访」步骤（§2.3-5）：**本批不新增引导步骤**（产品 §4.5 的引导项登记 G12 产品口径）

### 3.2 结果页（Q30，`docs/character-gacha-redesign-2026-09-23.md:152-227`）

| 元素 | 规格 | 落点 |
|---|---|---|
| 顶部 | 「恭喜获得」金色标题；上下横线 | 结果层内 |
| 网格 | 2 行 × 5 列、正方形框；**单抽 1 格居中**（`rewardCount == 1` 居中分支）、十连铺满 | `LazyVerticalGrid(GridCells.Fixed(5))` 先例：`PatrolTowerDialog.kt:423-424` |
| 角色框 | 头像精灵（`avatarKey`，512 档） | `SpriteImage(name)` |
| 物品框 | 物品图标（居中）+ 数量角标 | 自建 `GachaRewardCell`（**不用** `UnifiedItemCard`，§2.2） |
| 数量角标 | 框内**右下角**、白字 `×n`（🔴 `ItemCard.kt:245` 的角标**无 `×`**，不可直接复用） | 自建 |
| 边框 | **持续旋转流光/描边扫光**，颜色 = 品阶色（物品）/ 灵根色（角色碎片）；框内半透明底色同色 | 见 D-6 |
| 保底格 | 更亮一档或附「保底」角标（`row.isPity`） | 自建 |
| 底部按钮 | 左「招募一次」/ 右「招募十次」；**点按钮 = 在当前结果页上叠下一轮**（直接替换网格 + 重播流光，不先关页） | `key(resultToken)` |
| 关闭手势 | **点击框外/按钮外任意处关闭**；**排除**：① 各奖励框（Q39：点框**不弹详情**，仅防误关）② 底部双按钮 | D-2 |
| 升星 | 本轮跨星 → 结果页之上弹**全屏升星层**（星级跳变 + 加成数值）；时序 = roll → 升星层 → 结果页 | D-4 |
| 不做 | 不做长动画/翻牌；概率公示与历史**不在结果页内嵌**（回主界面入口） | — |

### 3.3 图鉴最小（新增子面板）

- 6 格 = `CharacterTemplateDb.ALL`；**未解锁**判据 = `tid !in starMap`（稀疏账本：0 星不落键）→ 立绘置灰
- 已解锁：`portraitKey` 全身立绘（**按需 decode，禁加预载**）＋ `★×star` ＋ 「下一星 `fragmentCounts[tid]`/`FRAGMENTS_PER_STAR`」；满星显示 `MAX`
- 数据：`starMap` / `fragmentCounts`（经 `GachaFacade` 只读流）；`FRAGMENTS_PER_STAR` / `MAX_STAR` 读 `GameConfig.Gacha`
- **纯函数化渲染模型**（输入两个 map → 6 格状态），便于单测（D-8）

### 3.4 概率公示 / 历史（新增子面板）

- 公示：读 `GachaPoolConfig.pool("standard")` 渲染类别权重 + 品阶权重 + 保底（阈值/碎片量与 `pickMode`）；**禁在 Compose 内解析 JSON**（`GachaPoolConfig` 是 `@Singleton @Inject`，注入 VM）
- 历史：50 条环（`history`，下标 0 最新）；骨架可照 `BattleLogListDialog`（`LazyColumn` + 分段）
- 两项都**只读**，无写路径

---

## 4. 决策（D-1…D-12，含依据）

| # | 决策 | 依据 |
|---|---|---|
| **D-1** | **复用 `DialogType.Recruit`，只替换分支体**（不新增 `DialogType.Gacha`）；顺手把 `DialogType.kt:31` 的 KDoc「招募」改为「寻访」 | 零改 4 个注册/守卫文件（§2.1-2）；`GameRoute.Recruit` 映射与 `GameRouteDialogTypeMappingTest` 不受影响。代价：语义名略旧，KDoc 说明即可 |
| **D-2** | 🔴 **结果层用「同一窗口内最高 z 序层 + 自管层外点击」，容器设 `dismissOnClickOutside = false`**；若首片能力验证发现容器做不到「层外点击只关结果层、不关整个寻访窗」，则退到**结果页独立 `UnifiedGameDialog` 窗口**（独立 scrim，语义天然正确） | Q30 要求「点框外关闭结果页」而主界面应留在下层；⚠️ 现容器把关闭点击放在 `GameDialog.kt:140-145` 的**独立 scrim sibling 层**，框内 `clickable` **挡不住** scrim（子代理实测）⇒ 这是真实摩擦点。**首片先做 15 分钟能力验证再定 A/B**，不猜 |
| **D-3** | **升星层做在寻访窗口内的本地 UI 状态**（不进 `GameNotification`、不新建总线、不写 `pendingNotification`）；十连一次解锁/升星多名时**在窗口内逐条展示**（ViewModel 内队列） | `pendingNotification` 是单槽且无公开写入口（§2.3-3）；产品 §15.4 只要求「不新建第二通知总线」 |
| **D-4** | **失败文案在寻访 UI 内联展示**（`Failure.reason` → 中文），**不复用** `GameNotification.RecruitFailed`；该通知的生产者存废**只核实不删**，登记 G10 | 码全集 = `PoolNotFound`/`PoolDisabled`/`PoolMalformed`/`InsufficientSpiritStones`（+ `InvalidPullCount` 不可达）⇒ 文案表 4–5 键；避免混入无关重构 |
| **D-5** | **色表单一源落地**：`:core:ui` 新增 `GachaColors`（String→Color 唯一换算）＋ `Disciple.spiritRoot.countColor` 委托 `GameConfig.Gacha.spiritRootCountColor`；寻访域自建 `GachaRewardCell`（不复用 `UnifiedItemCard`）；旧 4 张物品色表本批不动（G12 债） | 产品 `:205/:218` 要求「碎片框 + 灵根徽章同一套」，并明文允许仓储色板延后对齐；§2.2 列出全部 8 张旧表与冲突值 |
| **D-6** | 🔴 **流光走 Compose 层**（`Animatable` 循环 + `sweepGradient`，照 `LizhanDialog.kt:466-479`/`RewardCardHost.kt:90-99` 既有范式），**不改 native 渲染链**，并在任务书/报告显式写「**不适用** `android/docs/renderer-feature-checklist.md`」（理由：Compose `Dialog` 独立窗口不在 native surface 上） | 上位 §8.G-3 字面只对 native 路径成立（§2.1-1）；本仓 `InfiniteTransition` 零命中，`Animatable` 是既有范式。**低端降级**：`GpuTierDetector`（`GameVmServices.kt:48` 已注入）`LOW` ⇒ 流光降为静态金边/呼吸 |
| **D-7** | 🔴 **接线后第一件事**：写最小复现测试验证 `starMap`/`fragmentCounts` 订阅**会发射**（`GachaService.grantFragmentsLocally` 就地改 map 的 G08 遗留，report-G09 §6.2-6） | 不发射 ⇒ 结果页/图鉴刷新不出来；必须先证伪再决定是否改 `copy()` |
| **D-8** | **图鉴/结果格的「渲染模型」纯函数化**（输入 `starMap`/`fragmentCounts`/`rows` → 输出格状态），Composable 只做展示 | 让 6 格语义（未解锁/星级/下一星/满星）与色表口径可被单测钉死，避免只能靠真机看 |
| **D-9** | **不改 `DialogTypeRenderCoverageTest` / 不新增 `SpriteCategory`**；大图类**不加进 `ResourcePreloader`** | 12 键已登记为 `CHARACTER`（`resource-registry.json:374-387`），档位策略 512/1024 已定（`SpriteSourceMappingGuardTest.kt:155-168`）；`ResourcePreloader.kt:266-269` 明文排除 |
| **D-10** | **不动 `GameConfig`/Room/Proto**；新 ViewModel 只持 `GachaFacade`（继承 `BaseViewModel`） | `ViewModelArchitectureTest` 明禁非核心 VM import `GameStateStore`；零迁移是 G09 已定口径 |
| **D-11** | **埋点不上报**，只登记事件名 `gacha_pull` / `gacha_unlock` | 产品 §15.6「本次可不实现上报」 |
| **D-12** | **`ui-read-surface.md` 必须回写两处**：§2.1 末句（现写「UI 消费面尚未接入」）+ §3.2 登记一行（格式照 `:94 recruitListAggregates` 行） | 该文档 §2 纪律是「先确认在合法面内再用」；四字段已在 §2.1（`:44-54`），但**登记行必须与实际接入同步**，否则下轮审计判未登记读面 |

---

## 5. 文件面与切片（≤10 文件/片）

| 片 | 允许改 / 新建 | 禁止改 | 自检项 |
|---|---|---|---|
| **A-11a** 接线 | `ui/game/GameVmServices.kt`（+`gachaFacade`）、`ui/game/GameViewModel.kt`（+`val gacha`）、4 个测试构造点（`GameViewModelTest.kt:216`、`GameViewModelRoadFeedbackTest.kt:133`、`GameViewModelMovingBuildingBusTest.kt:136`、`GameViewModelSectMapTest.kt:131`） | 寻访 UI 文件、C++ | 5 文件同改；`compileReleaseKotlin` 绿；D-7 的订阅发射测试 |
| **A-11b** 主界面 ＋ 入口 | `components/dialog/DialogFeatureRoutes.kt`（`:53` 改指向、`:68-92` 整段替换 + 删 `:69 @Suppress`）、`components/GameActionButtons.kt:103`（文案）、`DialogType.kt:31`（KDoc）、新 `ui/game/GachaViewModel.kt`、新 `ui/game/dialogs/GachaMainPanel.kt` | native 渲染、Room、`DialogTypeRenderCoverageTest` | 占位文案零命中；价格/保底/余额全取自配置与流 |
| **A-11c** 结果页 | 新 `ui/game/dialogs/GachaResultLayer.kt`、新 `ui/game/dialogs/GachaRewardCell.kt`（含 `×n` 角标 + Q31 边框 + 流光 + 保底角标）、新 `core/ui/.../GachaColors.kt`、新 `ui/game/dialogs/GachaShimmer.kt`（可并入 cell） | `ItemCard.kt`、`Color.kt` 旧表 | 2×5/单格居中；格序=抽取序；Q31 全表；流光降级 |
| **A-11d** 图鉴/公示/历史 | 新 `GachaCodexPanel.kt`、`GachaOddsPanel.kt`、`GachaHistoryPanel.kt` | 同上 | 稀疏账本语义；公示读 `GachaPoolConfig`；历史 50 条 |
| **A-11e** 灵根徽章单一源 | `core/domain/.../model/Disciple.kt:299-306`（`countColor` 委托 Q31） | `Color.kt`、`ItemCard.kt` | 10 处消费者自动跟随；旧口径（单红/双橙）清零 |
| **c11-a** 新测试 | 新 `GachaRenderModelTest.kt`（D-8 纯函数：未解锁/★/下一星/满星/格序）、新 `GachaColorSingleSourceGuardTest.kt`（Q31 全表值 + 旧表零引用的源码扫描守卫）、新 `GachaSubscribeEmissionTest.kt`（D-7） | 生产源 | 断言消息带操作指引；不用 `assumeTrue` |
| **c11-b** 既有测试适配 | 若 A-11a 未同片改完，剩余的 GameViewModel 构造点归此片；`MainGameScreenTest` 类若有「招募」文案断言同批更新 | 生产源 | 逐条登记「为什么改」 |
| **主线程** | `docs/ui-read-surface.md`（§2.1 末句 + §3.2 登记）、双 changelog、`report-G11.md`（含 pending-device 节）、全门禁、真机验证、单次提交 | — | 见 §6 |

---

## 6. 门禁清单（主线程终树同轮重跑，判据是命令自己的输出原文）

```powershell
# 0) 环境 + 桌面 C++（G11 零 C++ 改动 ⇒ 仍必跑一次确认未误改）
$env:PATH = "C:\Users\cp050\llvm-mingw\llvm-mingw-20260616-ucrt-x86_64\bin;" +
            "C:\Users\cp050\llvm-mingw\llvm-mingw-20260616-ucrt-x86_64\x86_64-w64-mingw32\bin;" +
            "$env:LOCALAPPDATA\Android\Sdk\cmake\3.22.1\bin;" + $env:PATH
cd android\app\src\main\cpp\gamecore\build\desktop-test ; cmake --build . ; ctest
#   G09 基线：1437 总 / 3 红（B 类同名）/ actual=0xb4f3c6912207f597 —— G11 应逐值不变

# 1) 桌面 JNI（若 C++ 零改动可跳过，但须用既有 .so 跑 Diff 族并记录体积）
pwsh -File scripts/build-desktop-jni.ps1          # 工作目录 = 仓库根；基线 9015808 字节

# 2) Kotlin 全门（工作目录 = android）
.\gradlew.bat compileReleaseKotlin testReleaseUnitTest --max-workers=1 --rerun-tasks --continue `
  "-Dgamecore.jni.path=C:\Mnzm\XianxiaSectNative\android\core\engine\build\desktop-jni\libgamecorejni.so" `
  detekt lintRelease --console=plain
#   G09 基线：JUnit 7419/0/0/18、detekt 六模块 0 error/warning、lint 36 警告 0 error

# 3) node 四门（工作目录 = 仓库根）
node scripts/gen-action-ids.mjs ; node scripts/gen-game-data.mjs --check
node scripts/check-jni-count.mjs ; node scripts/check-agent-instructions.mjs

# 4) 精灵门（若动过任何 drawable/注册表才需要；本批预计 0 改动）
#   ResourceManifestCompletenessTest / SpriteCodegenSyncTest / SpriteSourceMappingGuardTest / GachaCharacterSpriteGuardTest
```

**真机验证**（M1 判据）：照 `docs/parallel-batches/batch-10-device-verification.md` 的表头与
`batch-10-verification-record.md:5,28-30` 的记录格式（设备/构建/步骤/结果/截图或 logcat 证据逐项表），
清单至少覆盖：进寻访 → 单抽 → 结果页显示头像 → 十连 10 格 → 保底第 10 抽高亮 → 余额不足禁用 →
图鉴/历史/公示可开 → 关窗不软锁（`DialogTypeRenderCoverageTest` 绿）。

**提交前**：`git status` 只留本批改动；构建副产物（`atlas-rgba-manifest.json` / `sprite-uid-map.json`）`git checkout --` 还原；
双 changelog 用**编辑工具**改（脚本重排整文件会被审回），改完 `node -e "JSON.parse(...)"` 校验。

---

## 7. 剩余待决 / 登记

**待产品拍板（不阻塞开工）**
1. 🟠 **星级乘区是否进弟子详情属性面板**（G09 只钉了战斗+修炼两条链；`report-G09.md` §六-1 已登记）。当前口径：**不进**，本批不显示星级加成明细（图鉴/结果页除外）。
2. 🟠 **引导是否加「打开寻访」步骤**：本批**不加**（实测无卡死风险，§2.3-5）；产品 §4.5 有该表述，属产品口径。
3. 🟠 **十连一次解锁多人时的升星层展现**：D-3 取「窗口内逐条展示」；若产品要单条汇总，改文案即可。

**跨批登记（G10/G12 承接）**
4. 🔴 **色板对齐债（G12）**：`Color.kt` 四份物品色表 + `GameConfig.Rarity.CONFIGS` + `ItemCard.getQualityColor` 与 Q31 不一致（§2.2 #1–#4、#8）；本批只保证寻访域 + 灵根徽章走 Q31。
5. 🟠 **`GameNotification.RecruitFailed` 存废**：生产者待核实（疑死路），登记 G10 死代码清零。
6. 🟠 **埋点事件名 `gacha_pull` / `gacha_unlock`**：本批只登记不上报（产品 §15.6）。
7. 🟡 **`GachaService.grantFragmentsLocally` 就地改 map**（G08 遗留）：若 D-7 测试证明订阅不发射，改为 `copy()`/新建实例，并同步 G08 的碎片入账路径。
8. 🟡 **`OverflowMailSender` 提示条「部分奖励已转入邮件」仍用「奖励」措辞**（report-G09 §6.2-7）→ 文案收口批。
9. 🟡 **测试面 `androidRoot()` 三份同形实现**（report-G09 §6.2-8）→ G10 收敛为单一测试工具。

---

## 8. 一句话给执行者

**G11 是纯 UI 批：只换 `DialogFeatureRoutes.kt:68-92` 的占位体 + 接 `GachaDelegate`（含 4 个测试构造点）＋ 新建寻访域面板与单一色源；
不许新增 `DialogType`、不许改 native 渲染链（流光在 Compose 层且必须低端降级）、不许复用 `UnifiedItemCard` 做奖励框（它硬编码旧色表且品色只进背景）、
不许写死角色精灵键（一律 `CharacterTemplateDb.byId(tid)!!.avatarKey`）、不许新增镜像字段或第二通知总线；
开工第一步先验 D-7（订阅是否发射），第二步先验 D-2（结果层关闭语义的能力边界）。**
