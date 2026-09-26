# report-G11 · 最简寻访 UI（主界面 + 结果页 Q30/Q31 + 图鉴/公示/历史 + `GachaDelegate` 接线）

> 派工真源：[`TASKBOOK-G11.md`](TASKBOOK-G11.md)；开工时点 2026-09-26，G09 已收官（`15eef2b2d`）。
> 本报告的每个数字都取自**本会话同轮命令输出**，未跑的一律显式登记为未做（§7）。
> 会话内共跑 **四轮**组合门，各轮覆盖哪棵树见 §10（本仓多会话共用一棵工作树）。

---

## 1. 结论一页速览

| 项 | 结论 |
|---|---|
| 状态 | **代码面全部落地 + 本机可得的四道门全绿**；🔴 **真机未做**（本机无设备/模拟器可连，见 §7）⇒ **M1 的「真机通」判据尚未满足** |
| 入口 | `寻访` 按钮 → 真寻访界面；全仓 `src/main` 对 `"寻访功能尚未开放"` **0 命中**（§2 验收①） |
| 新增面 | 生产 12 文件（`:core:ui` 1 / `:feature:game` 11）＋ 测试 5 类 49 例 ＋ C++ 3 头 1 断言（色表对齐） |
| 硬门 | 零 Room 迁移（仍 v59）、零 `@ProtoNumber`、零新镜像字段、零反向通道、零新 `DialogType`、零预载清单变更、零生产 JNI 面变更（86/86）、**未动 native 渲染链**、金黄 `actual=0xb4f3c6912207f597` 逐字符不变 |
| 🔴 根因修复 | G08/G09 遗留：`GachaService` 两条 Kotlin 臂原地改 `GameData` 字段 ⇒ 四个订阅流**不发射**（先证伪再改 `copy()`），详见 §6-A |
| 🔴 上位漏项 | 灵根数色在 C++ 侧还有 **3 份同口径副本**（任务书只点了 1 张 Kotlin 表），只改 Kotlin 会同屏两套色，详见 §4-2 |

---

## 2. 验收逐条对照（任务书 §1）

| 验收 | 判据 | 实测证据 |
|---|---|---|
| ① 入口改道 | 左栏文案 `招募`→`寻访`，进真界面；占位文案零命中 | `GameActionButtons.kt:103` 现为 `text = "寻访"`；`grep -rn "寻访功能尚未开放" --include=*.kt` = **0**；`GachaRecruitDialogTest` 的「主界面渲染」用例真实渲染出池名/价格/保底/三入口 |
| ② 主界面显示 | 单抽价/十连价/保底 `x/threshold`/余额；余额不足 `enabled=false` ＋ 价格转红 | 价格与阈值只读 `GachaPoolConfig.pool("standard")`（VM 在 IO 线程装载，UI 零字面量）；`GachaMainInputs.canAffordOnce/canAffordTen` 为唯一判据，价格串 `Color.Red` 分支与 `MerchantDialog:624` 同形；禁用态由 `GachaRecruitDialogTest`「余额不足 - 两个招募按钮都禁用」用例 `assertIsNotEnabled()` 钉住 |
| ③ 格序 = 抽取序 | `Success.rows` 下标即格序，单抽居中、十连 2×5；禁拿 `history` 排序当格序 | `GachaRenderModel.resultCells` 保序；`GachaRenderModelTest`「结果格序必须等于抽取序」用逐格可辨的 `count=1..10` 断言序列，且**判别力自证**（改成 `asReversed()` 即红，§5-3）；居中分支由 `SINGLE_CELL_COUNT` 走独立布局路径 |
| ④ Q31 单一色源 | 物品=品阶色、碎片=灵根数色；禁引 8 张旧表 | `GachaColors`（`:core:ui` 唯一 `String→Color` 换算口）+ `GameConfig.Gacha.rarityColor/spiritRootCountColor`；`GachaColorSingleSourceGuardTest` 扫遍寻访域源文件断言零引用 `getRarityColor/getQualityColor/Rarity.CONFIGS/Rarity.getColor/XianxiaColorScheme/UnifiedItemCard` |
| ⑤ 512 档 `avatarKey` | 角色格读 `avatarKey`，禁读 `portraitKey`；角标右下角白字 `×n` | `GachaRenderModel.characterCell` 取 `template.avatarKey`；`GachaRenderModelTest`「角色格取键必须是 512 档 avatarKey」同时断言 `spriteKey != portraitKey`；`GachaRewardCell.QuantityBadge` 输出 `×n`（`ItemCard.kt:245` 的角标无 `×`，故自建） |
| ⑥ 图鉴六格 | 未解锁（`tid !in starMap`）置灰；已解锁 `★×star` + 「下一星 x/100」；满星 MAX；立绘读 `portraitKey` | `codexCells` 用 `starMap.containsKey(tid)` 判解锁（稀疏账本，0 星无键）；`GachaRenderModelTest` 两条用例 + `GachaRecruitDialogTest`「图鉴 - 未解锁压暗条数与满星与下一星进度」实渲染断言（`useUnmergedTree`，见 §5-4） |
| ⑦ `GachaDelegate` 接线 | `GameVmDelegateServices` 注入 `gachaFacade`、`GameViewModel` 暴露 `gacha`、suspend 方法在引擎线程内调用 | 五文件同片改（`GameVmServices.kt` / `GameViewModel.kt:184` / 4 个测试构造点）；`GachaDelegate` 两个入口统一 `gameEngine.launchOnEngine { }`；`GachaDelegateTest` 钉「调用瞬间不碰门面、只在派发块内执行」「取消异常原样重抛」「异常仍回调（防按钮软锁）」 |
| ⑧ 零越界 | 四守卫绿 + `ui-read-surface.md` 回写 | §9 门禁表内 `MirrorReadOnlyGuardTest` / `MirrorConsumerSurfaceGuardTest` / `ViewModelArchitectureTest` / `DialogTypeRenderCoverageTest` 均在全量 JUnit 内绿；`docs/ui-read-surface.md` §2.1 末句改为「已接入 + 四个面各读哪本账」，§3.2 增加一行登记（格式照 `recruitListAggregates` 行）；`node scripts/check-agent-instructions.mjs` = ✓ 全部通过（§9） |
| ⑨ 真机 | 截图/logcat 证据 | 🔴 **未做**——本机 `adb devices` 为空、`127.0.0.1:16416`（batch-10 用过的 MuMu 端口）拒绝连接、未在 `Program Files` 找到 MuMu 安装位。清单与替代证据见 §7 |
| ⑩ 交付物 | 双 changelog + 本报告 + 单次提交 | `CHANGELOG.md` G11 节（+62 行）、`changelog_entries.json` 追加 8 条玩家文案并把 G02 遗留的「点击后提示寻访功能尚未开放」改写为实况（`node -e JSON.parse` 校验通过，diff 为 +9/−2 无整文件重排）；提交为本批单次 `feat(gacha)` |
| 不做面 | 轮换池/UP、翻牌动画、埋点上报、旧色板、native 渲染器、Room 迁移、版本号 | 全部遵守：未新增池、结果页无长动画（只有描边扫光循环）、埋点只登记（§8-3）、旧 4 张物品色表未动（G12 债）、`renderer-feature-checklist.md` 未动且理由见 §4-1、Room 仍 v59、`version.properties` 未碰 |

---

## 3. 实施内容（按任务书 §5 切片）

| 片 | 落地 |
|---|---|
| **A-11a** 接线 | `GameVmDelegateServices + gachaFacade`、`GameViewModel.val gacha = GachaDelegate(...)`、`GachaDelegate` 重写为引擎线程派发 + 回调；4 个测试构造点同片补齐（各加 `gachaFacade: GachaFacade = mockk(relaxed = true)`） |
| **A-11b** 主界面 + 入口 | `DialogFeatureRoutes.kt` 占位体整段替换（并删 `@Suppress("UnusedParameter")` 与随之失效的 13 个 import）、`GameActionButtons:103` 文案、`DialogType.kt` KDoc、新 `GachaViewModel.kt`、新 `GachaMainPanel.kt`（含 `GachaRecruitDialog` 容器 + 四面切换 + 结果层槽位 + 分层 `BackHandler`） |
| **A-11c** 结果页 | 新 `GachaResultLayer.kt`（Q30 版式 + 升星层 + 叠轮游标）、`GachaRewardCell.kt`（正方形框、`×n`、保底标记、未解析显式提示 + 共享的方格边长算式）、`GachaShimmer.kt`（Compose 层描边扫光 + 静态降级）、`core/ui/.../GachaColors.kt` |
| **A-11d** 三面 | 新 `GachaCodexPanel.kt` / `GachaOddsPanel.kt` / `GachaHistoryPanel.kt` |
| **A-11e** 灵根徽章单一源 | `Disciple.kt` `countColor` 委托 `GameConfig.Gacha.spiritRootCountColor`（11 处直接消费点自动跟随）＋ 🔴 补做 C++ 三份副本对齐（§4-2） |
| **c11-a** 新测试 | `GachaRenderModelTest`（18）、`GachaColorSingleSourceGuardTest`（4）、`GachaSubscribeEmissionTest`（2）、`UnifiedGameDialogOverlayLayerTest`（5，`:core:ui`）、`GachaViewModelTest`（8）、`GachaDelegateTest`（5）、`GachaRecruitDialogTest`（10，Robolectric 渲染/交互） |
| **c11-b** 既有测试适配 | 只需 A-11a 的 4 个构造点；`MainGameScreen` 类**无**「招募」文案断言（`grep '"招募"' src/test` = 0 命中）；`PortraitResolverTest` 读点台账**零漂移**（图鉴经 `SpriteImage` 注册面取图，不新增 `resolvePortraitResId` 读点，也不直引 `PortraitPool`） |
| 主线程 | `docs/ui-read-surface.md` 两处回写、双 changelog、本报告、四轮门禁、单次提交 |

**任务书 §5 之外多出的文件面**（三处，均为实测被迫，逐条理由）：
1. `gamecore/include/gamecore/system/{exploration_tx,year_settlement,sect_defense_battle}.h` ＋ `test/exploration_tx_test.cpp`：灵根数色 C++ 副本对齐（§4-2，不补就是同屏两套色的可见缺陷）；
2. `core/engine/.../GachaService.kt`：D-7 根因修复（任务书 §2.3-4 明写「接 UI 前必核」并授权改 `copy()`）；顺带拆 `rollRepeated` 消 `LongMethod`；
3. `DialogFeatureRoutes.kt` 的 import 收敛与 `GachaRecruitDialog` 的**依赖收窄**（不再吃整个 `GameViewModel`，改收 `GachaDelegate` + `shimmer` + `spiritStones`）——为的是能让渲染测试用手工依赖直接起面板，不搬 GameViewModel 全套替身。

---

## 4. 上位失真与漏项（本批实测新增 6 条）

| # | 上位记载 | 实测真值 | 处置 |
|---|---|---|---|
| 1 | 任务书 D-6：「流光照 `LizhanDialog.kt:466-479` / `RewardCardHost.kt:90-99` 既有范式（`Animatable` 循环 + `sweepGradient`）」 | 那两段是**翻页位移**与**淡入上浮**（`graphicsLayer.translationY/alpha`），没有任何扫光；全仓 `sweepGradient` / `rotate(` / `rotationZ` / `InfiniteTransition` / `animateFloatAsState` **0 命中**；且本仓 Compose 版本的 `Brush.sweepGradient` 只有两个重载（`vararg Pair<Float,Color>` 与 `List<Color>`），**没有 `start/end` 角度入参**（编译期原文：`None of the following candidates is applicable`） | 取 Q30 允许的另一措辞「描边扫光」：`Animatable` 推相位 + `Brush.linearGradient(start/end 逐帧平移)` 让高光带沿对角反复扫过，首尾在框外 ⇒ 循环无断点；相位只在 `drawWithContent` 内读（重绘不重组）。旋转一支若要落地需 `colorStops` 逐帧采样建表（更贵），登记 §8-5 |
| 2 | 任务书 §2.2 处置（D-5）：只让 `Disciple.spiritRoot.countColor` 委托 Q31，「⇒ 10 处消费者自动跟随」 | 灵根数色另有 **3 份 C++ 字面量副本**（`exploration_tx.h:71-85` / `year_settlement.h:1022-1034` / `sect_defense_battle.h:88-101`，注释自陈「Kotlin SpiritRoot.countColor 同式」），它们写进**持久化**的 `GarrisonSlot.discipleSpiritRootColor`，被灵矿执事槽（`SpiritMineDialog:561`）、巅峰驻守槽（`PeakScreenComponents:182`）、世界地图驻守槽（`WorldMapSectDetailDialog:576`）消费；另有 C++ 断言 `exploration_tx_test.cpp:423 EXPECT_EQ("#F39C12", …)`。直接消费点是 **11 处**不是 10 处 | 四份一起对齐 Q31 五值 + 同批改 C++ 断言；新增 `GachaColorSingleSourceGuardTest` 把「三份 C++ 必须含新值且不含旧值」钉成守卫（判别力自证见 §5-2）。⚠️ 残留：旧档里已持久化的槽位色串仍是旧值，直到该槽下次被写入才刷新——属显示串缓存，登记 §8-6 |
| 3 | 验收⑤：`CharacterTemplateDb.byId(tid)!!.avatarKey` | `byId` 可空且其 KDoc 明写「未知 id 返回 null——调用方必须显式拒绝，禁止兜底到开局模板」；`!!` 违 AGENTS §5 1.1 红线 | 改安全调用 + 模型 `unresolved` 分支（格子显「未知」并禁猜图），行为与 `GachaPullLedger.poolError` 的「显式拒绝」口径一致 |
| 4 | §2.3-1：加 `gachaFacade` 会打破 4 个测试构造点 | ✅ 成立，逐名核对为 `GameViewModelTest:216`、`…RoadFeedbackTest:133`、`…MovingBuildingBusTest:136`、`…SectMapTest:131`（行号随批次漂移，实际按 `grep "GameVmDelegateServices("` 定位到 4 处） | 同片改完，编译/测试绿 |
| 5 | §5 A-11b：`DialogFeatureRoutes.kt:53` 需「改指向」 | :53 早已是 `DialogType.Recruit -> renderRecruit(...)`，**零改**即可；只替换了 `:68-92` 的分支体 | 记录以免下轮误判漏做 |
| 6 | 产品 §4.4 图鉴「满星显示 MAX」 | 我按字面同时给星级行与进度行都写 MAX ⇒ 一格出现两个「MAX」 | 进度行改显「碎片 x」（满星后碎片继续累加、不折算，本来就是账本真值），星级行保留 MAX |

---

## 5. 守卫判别力自证（退回旧实现判红，逐条实测）

| # | 构造反例（只改一处） | 实跑结果 |
|---|---|---|
| 1 | `GachaService.grantFragmentsLocally` 改回原地赋值 | `GachaSubscribeEmissionTest > 碎片入账后…订阅流必须发射` **FAILED**：`expected:<2> but was:<1>`（消息原文含「引用没变 ⇒ 状态存储不提交 ⇒ 结果页/图鉴刷不出来」+ 修法指引）；改 `copy()` 后同轮转绿 |
| 2 | 仅把 `year_settlement.h` 的 `case 1` 改回 `#E74C3C`（Kotlin 已复原） | `GachaColorSingleSourceGuardTest > 四份灵根数色副本同值` **FAILED**（点名该文件缺 `#ffd700`/仍留旧值）；⚠️ 第一次复跑曾出现 **UP-TO-DATE 假绿**——C++ 头不是 Gradle 测试任务的输入，`testReleaseUnitTest` 直接重放旧结论；加 `--rerun-tasks` 才拿到真判红（§9 的门禁命令因此一律带 `--rerun-tasks`） |
| 3 | `GachaRenderModel.resultCells` 改成 `rows.asReversed()` | `GachaRenderModelTest > 结果格序必须等于抽取序` **FAILED**（18 例中只红这一条，指认准确） |
| 4 | `Disciple.countColor` 改回旧 `when` 表 | 同时红 2 条：`四份灵根数色副本同值`（委托判据）＋ `countColor 逐档值就是 Q31` |
| 5 | 组合反例（1+2 同改、3 复原） | 上述红点可复现、互不掩盖；复原后 `:feature:game` `:core:engine` `:core:ui` `:core:domain` detekt 与全量 JUnit 均回到绿 |

**正向对照**（防「0 计数式假绿」）：`UnifiedGameDialogOverlayLayerTest` 里「遮罩关闭开关打开时点击遮罩会关整窗」证明测试**能**观察到窗口关闭；`GachaViewModelTest` 的「装载后池读面可用」证明 IO 派发那次装载真的发生；`GachaColorSingleSourceGuardTest` 断言寻访文件清单非空。

---

## 6. 首轮缺陷与修复（全部本机可复现）

| 编号 | 症状 | 根因 | 修复与验证 |
|---|---|---|---|
| **A** 🔴 | 接 UI 后结果页/图鉴可能永不刷新（服务端自测全绿） | `GameStateStoreImpl.emitStateFlows` 的提交判据是 `reusableMutableState.gameData !== baseline.gameData`，而 `GachaService` 两条 Kotlin 臂在事务里**对同一 `GameData` 实例**原地写字段 ⇒ 引用不变 ⇒ 四个派生流不发射；`_updateVersion` 同样不递增。快照读（`gameDataSnapshot`）读到新值、流读不到，故「服务侧全绿、UI 陈旧」 | 先写 `GachaSubscribeEmissionTest` 证伪（§5-1），再把 `grantFragmentsLocally` 与 `pullLocally` 的写回改成 `gameData = gameData.copy(...)`（本仓既有范式：`SpiritStoneWallet.updateGrade`、`GameDataFieldPatch.apply`、`MutableGameState.recordEvent`）。顺带不再依赖「扣费臂恰好 copy 了新实例」这一偶发引用（`pullLocally` 原本因 `spiritStoneWallet.deduct` 而侥幸发射，兑换码/邮件/活动的 `grantFragments` 则必然不发射） |
| **B** | detekt 首轮 2 条红（`:core:engine`） | 新写守卫测试里两个 `val` 常量被 `MayBeConst` 判红（`detekt` 对 `src/test` 生效，只是免类型解析） | 改 `const val`；§9 终树轮 `detekt` 六模块 0 error 0 warning |
| **C** | `pullLocally` 触到 `LongMethod` 阈值 60 | 加两行注释即越线（detekt 只计函数体行） | 拆 `rollRepeated` + `RollOutcome`，把「逐抽推演」与「一次事务写回」分离（语义与掷点消费序零变化，`DiffGachaPullTest` 6 例与 `GachaPullGuardTest` 9 例同轮复跑绿） |
| **D** | 结果层两个按钮与下层主界面两个按钮**同名**「招募十次」 | 渲染测试 `onNodeWithText` 命中 2 节点 | 生产侧给结果层按钮加稳定 `testTag`（渲染测试与无障碍都按它定位），不靠树序 |
| **E** | 「点框外关闭」在测试里点标题无效 | 层的 `clickable` 把子树语义合并，`onNodeWithText("恭喜获得")` 实际拿到整窗合并节点，点击落在中心（正好是格子）⇒ 命中格子的防误关分支 | 测试改为在层背景空白角标 `performTouchInput { click }`；同时确认这正是 Q39 想要的行为（点框不关、点框外关） |
| **F** | 升星队列可能永久吞掉后到的条目 | 队列原本 `remember { mutableStateOf(inputs.starUps) }`，而 `starUps` 是 `starMap` 流的派生值，可能在首帧之后才补齐 | 游标改「已确认条数」（`inputs.starUps.drop(confirmed)`）；抽前锚点由 `GachaPullShowcase.anchorStarMap` 成对下发（在引擎线程回调里现读 `starMap.value` 会拿到未提交的旧值） |
| **G** | 招募按钮可能永久置灰（软锁） | 忙碌态只有回调能解除，而 `launchOnEngine` 块内抛异常时回调不发生 | 按 `AlchemyViewModel` 既有写法兜 `Exception` → 回 `Failure(EngineFault)`（`CancellationException` 原样重抛），`GachaDelegateTest` 两条用例钉住 |

---

## 7. 🔴 pending-device：真机验证清单（本批未做，逐项待验）

**未做原因**：本机无任何可连设备/模拟器（`adb devices` 空；`127.0.0.1:16416` 拒绝连接；未在 `Program Files` 找到 MuMu）。M1 完成判据之一「G11 最简 UI 真机通」**尚未满足**，需用户接设备或授权启动模拟器后另起一轮。

记录格式与表头按 `docs/parallel-batches/batch-10-device-verification.md` ＋ `batch-10-verification-record.md`（设备/构建/步骤/结果/证据逐项）。

| # | 步骤 | 通过标准 | 证据形式 | 本机已得的替代证据 |
|---|---|---|---|---|
| D-1 | 左栏点「寻访」 | 进寻访主界面，无占位文案、无黑屏软锁 | 截图 | Robolectric 渲染用例（`GachaRecruitDialogTest` 主界面用例） |
| D-2 | 点「招募一次」 | 结果层叠出** 1 格且居中**，头像是该角色 512 档头像 | 截图 | 「单抽 - 结果层只出一格」用例 + 取键单测 |
| D-3 | 点「招募十次」 | **2×5 铺满**，第 10 格 = 第 10 抽（不倒序） | 截图 | 「十连 - 结果层铺满十格」+ 格序单测 |
| D-4 | 抽满保底（第 10 抽） | 保底格更亮一档且带「保底」角标；流光/底色按灵根色 | 截图 + logcat（`GachaFacadeImpl pull ok`） | 模型层 `isPity`/配色单测；边框本体需真机肉眼 |
| D-5 | 灵石不足时 | 两按钮置灰不可点、价格转红；不产生扣费 | 截图 + 账本前后比对 | `assertIsNotEnabled` 用例 + 余额判据单测 |
| D-6 | 点结果层框外 / 点奖励框 | 前者关结果层回主界面，后者**不关且不弹详情**（Q39） | 截图 | 「点框外关闭结果层」「点奖励框不关结果层」两条用例 |
| D-7 | 结果页上再点「招募十次」 | 直接替换网格 + 重播流光，不关页重开（§10 不做长动画） | 录屏/连拍 | 「叠下一轮 - 关不掉的两层结果直接替换」用例 |
| D-8 | 抽到跨星（含首次解锁） | 先弹全屏升星层（星级跳变 + 战斗/修炼乘区），确认后回结果页；十连多名逐条 | 截图 | `starJumps` 顺序/连升单测 + `GachaStarUpModel` 文案单测；层内逐条待真机 |
| D-9 | 图鉴 / 公示 / 历史三入口 | 可开可返；未解锁压暗、满星 MAX、下一星 x/100；公示数值与 `game-data.json` 一致；历史 50 条封顶新在前 | 截图 | 三条 Robolectric 用例 + 模型单测 |
| D-10 | 低端机（`GpuTier.LOW`） | 流光降为静态描边，不掉帧、不发热 | 帧率/热状态采样 | **无替代**——降级分支只有真机低档位可测（代码路径 `shimmer=false` 已被单测走通） |
| D-11 | 关窗/返回键 | 有结果层时返回键只退结果层，再按才关整窗；关窗后地图不软锁（`anyDialogVisible` 遮罩不残留） | 截图 + logcat | 容器能力测试（`UnifiedGameDialogOverlayLayerTest`）+ 分层 `BackHandler`；关窗后宿主状态待真机 |
| D-12 | 头像/立绘按需解码 | 打开寻访不引入长时间白帧（`CHARACTER` 类不进预载是既有决策） | 冷启动耗时采样 | **无替代**——解码耗时只有真机可测；VM 侧资产解析已移出主线程 |

---

## 8. 跨批登记与遗留（承接任务书 §7，本批新增 5 条）

| # | 事项 | 归属 |
|---|---|---|
| 1 | 色板对齐债：`Color.kt` 四份物品色表 + `GameConfig.Rarity.CONFIGS` + `ItemCard.getQualityColor` 未动；本批只保证寻访域 + 灵根徽章走 Q31 | G12 |
| 2 | `GameNotification.RecruitFailed` 生产者存废（疑死路） | G10 死代码清零 |
| 3 | 埋点事件名 `gacha_pull` / `gacha_unlock`（本批只登记不上报） | G12 商业化批 |
| 4 | 🔴 **本批新发现（测试基建债）**：`core/engine/src/test/.../FakeGameStateStore.gameData` 是「每次属性访问新建一次性 `MutableStateFlow`」的断线桩，用它测任何派生流刷新**必然假绿**；`FakeAtomicStateStore` 缺生产的 `!==` 提交守卫（本次恰好因 `MutableStateFlow` equals 去重而结果一致）。建议收敛为带生产语义的共享替身 | G10 |
| 5 | 旋转一支流光的落地路径（`Brush.sweepGradient` 的 `colorStops` 逐帧采样，或升级 Compose 版本拿 `start/end`）；本批用描边扫光 | 精修批 |
| 6 | 旧档 `GarrisonSlot.discipleSpiritRootColor` 里持久化的旧色串会在下次该槽被写入时才刷新（显示串，不入 RNG 面） | 观察项（无需动作） |
| 7 | `GachaRewardCell` 复刻了 `ItemCard` 的「预载位图命中 → 否则 `painterResource`」两分支；可提取共享的「按 resId 画注册精灵」composable | 精修批 |
| 8 | `Color.kt` 的 `getSpiritRootCountColor` / `SingleRoot…PentaRoot` / `XianxiaColorScheme.rarityColors`、`WarehouseTab:893` 与 `MerchantDialog:679` 两个 `getRarityColor` 转发壳：实测**零调用方** | G10 死代码清零 |

---

## 9. 门禁表（终树 = 第 4 轮，同轮实测；判据是命令自己的输出原文）

> 组合门本批共跑 **四轮**（§10）；下表全部取自**第 4 轮（交付树）**，`BUILD SUCCESSFUL in 25m 10s`。
> 桌面 ctest 与 node 四门在第四轮之后于同一棵树复跑（ctest 53.18s、node 门见各行）。

| 门 | 命令 | 第 4 轮实测值 | 与 G09 基线对照 |
|---|---|---|---|
| 桌面 C++ 编译 | `cmake --build .`（`gamecore/build/desktop-test`） | 成功（无 error）；`ExplorationTx*` 族 `ctest -R ExplorationTx` = **14/14 全绿**（含本批改掉的驻守色断言） | 本批只改 3 个头里的显示串常量 + 1 条断言 |
| 桌面 ctest | `ctest` | **1437 总 / 3 败**，`Total Test time (real) = 53.18 sec`，三条为 `DiscipleFactory.GoldenSequenceSeed42` / `…Seed987654321Female` / `DeterminismProbeTest.DigestMatchesGoldenBaseline` | 条数与**逐条同名**（零新增 B 类） |
| 🔴 `DeterminismProbe` | `ctest -R Determinism --output-on-failure` | 原文 `actual=0xb4f3c6912207f597 golden=0x490e8dc522e12921`；同组 `DigestIsStableAcrossRepeatedRuns ... Passed` | `actual` **逐字符不变** ⇒ 色表改动未平移任何掷点（纯显示串，且金黄夹具不做抽卡/驻守） |
| JUnit 全量 | `testReleaseUnitTest --max-workers=1 --rerun-tasks --continue -Dgamecore.jni.path=…` | **7471 / 0 failures / 0 errors / 18 skip**（692 份 XML）；逐模块：app **1011** / domain **1571** / data **814** / engine **2936** / ui **151** / feature:game **988** | 对 G09 **7419** = **+52**，逐模块账闭合：engine +6（发射 2 + 色表守卫 4）、ui +5（overlay 能力 5）、feature:game +41（渲染模型 18 + VM 8 + 委托 5 + 面板渲染 10） |
| detekt | `detekt` | 六份 `detekt.xml` 的 `severity="Error"` 与 `"Warning"` 计数**六模块全 0**；两份 baseline 零改动 | 同基线（第 1 轮曾红 2 条 `MayBeConst`，已真修，见 §6-B） |
| lint | `lintRelease` | `Lint found 36 warnings (and 3 warnings filtered by baseline lint-baseline.xml)`、**0 errors** | 36 与 G09/G08/G16 同值 ⇒ 全预存 |
| 跨语言对拍库 | `pwsh -File scripts/build-desktop-jni.ps1` | `.so` **9015808 字节 / mtime 16:34 / sha256 `40de6b25b2e03512…`**（重建前为 `7e93532cefce61074ec2…`）⇒ 三件套里 **mtime+sha 变、体积不变**（只改内联字面量常量） | 体积与 G09 同值；本批由当前树重建（C++ 有实质改动，不复用旧库） |
| 对拍族 | 全量 JUnit 内 | `DiffGachaPullTest 6/0/0`、`DiffGachaFragmentTest 6/0/0`、`GachaPullGuardTest 9/0/0`、`GachaConfigGuardTest 7/0/0` 全绿 | 双臂与三向口径未被色表改动破坏 |
| 🔴 镜像/架构守卫 | 全量 JUnit 内 | `MirrorReadOnlyGuardTest 1/0/0`、`MirrorConsumerSurfaceGuardTest 6/0/0`、`ViewModelArchitectureTest 3/0/0`（新 `GachaViewModel` 继承 `BaseViewModel` 且不 import `GameStateStore`）、`DialogTypeRenderCoverageTest 2/0/0`（**未新增 DialogType**，零改即绿） | 验收⑧ 的四个判据 |
| 精灵/素材门 | 全量 JUnit 内 | `GachaCharacterSpriteGuardTest 6/0/0`、`PortraitResolverTest 7/0/0`（**读点台账零漂移**）、`SpriteCodegenSyncTest 6/0/0`、`SpriteSourceMappingGuardTest 5/0/0`、`ResourceManifestCompletenessTest 2/0/0` | 本批零 drawable 变更、零预载清单变更 |
| ActionId | `node scripts/gen-action-ids.mjs` | `gen-action-ids: 201 actions (maxId=1872)`；regen 前后 `ActionIds.kt` / `action_ids.h` **两份自比零漂移** | 与 G09 同值（本批不加号） |
| 游戏数据 | `node scripts/gen-game-data.mjs --check` | `sha256 035066cbcb891aa124397667e58879d51d4dd4124177ae38b550e1b2c22094ef` | **逐字符不变** |
| JNI 计数 | `node scripts/check-jni-count.mjs` | `✓ total=86/86，双桥无扩散` | 零新增生产 `external fun` |
| 规范门禁 | `node scripts/check-agent-instructions.mjs` | `✓ 规范分发架构门禁全部通过`（规则①–⑤ 全 ✓；③ 42 篇 / 444 条引用零死链；⑤ 最坏链路 32180 / 32768） | 本批改了 `docs/ui-read-surface.md` 与 `CHANGELOG.md` ⇒ 改后复跑 |
| Room | `DATABASE_VERSION` = **59** | 零迁移、零 `@ProtoNumber` 变更 | 与 G09 同值 |
| 图集 | `atlas-manifest.json` | `sprites=41 / format=ASTC_4x4_LDR / layoutHash 6a122ed22d66bee5` | 零变化（构建脏的 `atlas-rgba-manifest.json` 实测只有 `generatedAt` 一键 ⇒ 提交前还原） |


---

## 10. 树身份与证据边界

| 项 | 值 |
|---|---|
| 分支 / 开工 HEAD | `feat/gacha-m0-m1` / `0fe23b8d7` |
| 期间他方提交 | `19405358a`（`docs(memory-watch)`，`git show --stat` = 仅 `docs/memory-refactor-watch/dispatch-ledger.md` **+1 行、零代码**）⇒ 本批四轮绿灯的**代码面**边界一致 |
| 工作树 | 门禁前后各记一次 `git status --porcelain` 行数与指纹（§9 附）；构建副产物 `atlas-rgba-manifest.json` 实测只有 `generatedAt` 一键差异 ⇒ 提交前 `git checkout --`；`docs/research/`×2 与本批无关不入库 |
| 四轮组合门归属 | **第 1 轮** `BUILD FAILED`：JUnit 全绿 **7456/0/0/18**（按 690 份 XML 逐模块汇总），但 `:core:engine:detekt` 报 2 条 `MayBeConst`（§6-B）+ `pullLocally` 触 `LongMethod`（§6-C）；**第 2 轮** `BUILD SUCCESSFUL in 25m 17s`（四门全绿，其 XML 被后续轮覆盖故不单独报数）；**第 3 轮** `BUILD SUCCESSFUL in 25m 37s`，同轮 ctest = 1437 / 3 败同名 / 67.12s；**第 4 轮 = 交付树** `BUILD SUCCESSFUL in 25m 10s`，数字全在 §9 |
| 第 4 轮前后指纹 | 轮前 `git status --porcelain` = 38 行（21 改 + 17 未跟踪，含本批全部新文件）；轮后 = 40 行（多出的 2 行是本会话随后写的 `report-G11.md` 与还原前的 `atlas-rgba-manifest.json`，**非构建期改动**）；两轮之间**无第三方代码写入**（HEAD 仍 `19405358a`） |
