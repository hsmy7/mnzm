# TASKBOOK-G12 · 体验完成（历史·公示·图鉴完整态·流光降级·引导·死文案清零·连抽打磨）

> **本文件是 G12 的派工真源**，取代 `docs/design/character-gacha-implementation.md` §G12（`:204-208`）的粗口径。
> **时点**：2026-09-26，G11 已收官（`a2923bced`），G10 为**前置**（本批在其后派发）。
> **侦察方式**：1 个只读子代理穷举（现状 file:line / 缺口 / 死文案 14 词 × 4 区计数），
> 关键结论由主线程回核（**其中 1 条被推翻，见 §2-1**）。
> **纪律**：引用一律写仓库根相对完整路径；任务书内代码片段必须已过项目红线（禁 `!!`/`Random`/直写 Store/硬编码数值）。

---

## 0. 位置与边界（**先看这条，避免重做 G11**）

| G11 已交付（**G12 不要重做**） | G12 的真实增量 |
|---|---|
| 寻访主界面（价签/保底 x10/余额/禁用态/四入口切换）、结果页 Q30 主体（2×5、单格居中、`×n`、点外关闭、叠下一轮、升星层时序）、**历史页骨架**、**概率公示页**、**图鉴最小态（6 格）**、Compose 层描边扫光 + `GpuTier.LOW` 静态降级、`GachaDelegate` 接线、Q31 色表单源（`GachaColors`） | ① 历史页**按抽粒度 + 保底标注 + x/10 同屏**；② 公示页**补"最高四阶"的池口径依据**；③ 图鉴**完整态**（升星预览/属性预览/立绘大图/来源）；④ **引导「打开寻访」**（G11 未碰 `GuideTask.kt`，实测零命中）；⑤ **死文案清零**（4 处真玩家可见）；⑥ 连抽打磨 4 项残余；⑦ 色板对齐债（旧 4 张物品色表 → Q31） |

---

## 1. 目标与验收判据

| 项 | 内容 |
|---|---|
| 目标 | 把「最简可用」抬到「体验完整」：历史能看出**按抽与保底**、公示能解释**为什么只到四阶**、图鉴能看出**养成收益**、新手引导能**把人带到寻访**、界面**不再出现已下线玩法的死文案** |
| 验收① | 历史页每行可按抽区分，**保底抽带标注**；页内可见 `x/threshold` 保底进度（与主界面同源，禁第二份计数） |
| 验收② | 公示页显示**每个类别的 `maxRarity` 依据**（最高四阶），数据全部来自 `GachaPoolConfig`（禁硬编码） |
| 验收③ | 图鉴完整态：6 格含**立绘（`portraitKey`，按需 decode）**＋星级＋下一星进度＋**升星收益预览**（战斗/修炼）；未解锁仍置灰 |
| 验收④ | 新手引导新增「打开寻访」步骤：判据走既有 `CumulativeCounter` 机制（**不新增 `GameData` 字段**），打开寻访即计数，步骤可完成且不卡死 |
| 验收⑤ | 死文案清零表（§7）**逐条归零或显式豁免**；清零范围与"不可清零区"边界见 §7 |
| 验收⑥ | 连抽打磨 4 项（§8）全绿，其中「保底更亮一档」按 §4.4 `:185` 落地 |
| 验收⑦ | 色板对齐债：`Color.kt` 四份物品色表 + `GameConfig.Rarity.CONFIGS` + `ItemCard.getQualityColor` 全部改为**引用 Q31 单源**（不再各写一套值）；仓储/奖励弹窗视觉随之统一 |
| 验收⑧ | 零新镜像字段 / 零反向通道 / UI 不直写 Store / 无新 tick；`ui-read-surface.md` 如新增读面须登记 |
| 验收⑨ | 全量 JUnit（`--rerun-tasks`）＋ detekt 六模块 0 ＋ lint 36 警告 0 error；`check-agent-instructions` 通过且**规则③「写法不精确」计数不增长** |
| 验收⑩ | 双 changelog ＋ `report-G12.md` ＋ 单次提交 |
| 不做 | 不做轮换池/UP（M3）；不做翻牌长动画；不做埋点上报（只登记）；**不动 G10 已定的 RNG 基线**（本批若改 `CharacterTemplateGuardTest` 的共享断言须与 G13 串行，见 §10 D-7） |

---

## 2. 🔴 上位记载 vs 实测（含**推翻**的一条）

| # | 记载 | 实测真值 | 后果 |
|---|---|---|---|
| 1 | 子代理初判：「主界面与结果页的按钮文案都该由『招募一次/十次』改成『寻访一次/十次』」 | ❌ **推翻**。产品方案两处口径**不同**：`:461`（寻访主界面行）= **「寻访一次/十次」**；`:187`（§4.4 结果页布局表）= **「左：招募一次 · 右：招募十次」**。实测现状：`GachaMainPanel.kt:319` = `"招募一次"`（**该改**）；`GachaResultLayer.kt:225` = `"招募一次"`（**按 `:187` 正确，不该改**） | 照子代理结论改结果页会**反而偏离产品规格** |
| 2 | G11 报告称「历史页已交付」 | ✅ 骨架已交付；但 `GachaRenderModel.kt:260` 的 `GachaHistoryRow` **只有 `monthLabel/displayName/quantity/colorHex`** ⇒ **无 `isPity`/无类别/无 `x/10`** | 历史页看不出保底（Q40 要求「保底附着标注」） |
| 3 | 产品 §4.4 `:185`「保底碎片：流光/背景可用**更亮一档**或附加『保底』角标」 | G11 只落了**角标**（`GachaRewardCell.kt:65-75`），**未见「亮一档」** | 差一半 |
| 4 | 「引导需要改判据防卡死」（G02 先例） | ✅ 但 G11 **完全没碰引导**：`GuideTaskRegistry.ALL_TASKS` 实测 23 条任务、id = **1–23 ＋ 25（无 24）**；`GuideCondition` 现 10 种条件**全部读状态**，**没有「打开某界面」型条件** | 需自行选路（§6） |
| 5 | 「死机制文案全库 grep 清零」 | ⚠️ 14 词 × 4 区实测合计 **244（Kotlin src/main）/ 51（C++）/ 0（assets/config）/ 101（changelog 历史条目）**；其中 **C++ 51 处全是注释/退役 desc**、Kotlin 区 240 处是**历史迁移注释与 ActionId 退役 desc（不可清零）** | 盲扫会误删迁移链与「ActionId 只增不复用」红线内容 |
| 6 | `architecture.md` 乘区表 | `:213` 写「`CultivationSpeedZones`（**4 乘区**：资源/社交/状态/临时）」⇒ 实为 **5**（多 `starBonus`）；`:215` 写「`BreakthroughZones`（长老指导/自身加成/**状态惩罚**）」⇒ 实为 `{baseZone, elderGuidance, selfBonus, adFlatBonus}` | 归 G14；本批只需知情 |

---

## 3. 任务 A：历史页「按抽 + 保底标注 + x/10 同屏」

- 数据面已齐：`GachaHistoryEntry.isPity`（`android/core/domain/src/main/java/com/xianxia/sect/core/model/GachaHistoryEntry.kt`）、
  环容量 `GameConfig.Gacha.HISTORY_RING_SIZE = 50`、保底计数 `GachaFacade.pityCounters`、阈值 `GachaPoolConfig.pool("standard")!!.pity.pullThreshold`。
- 改点：
  1. `android/feature/game/src/main/java/com/xianxia/sect/ui/game/GachaRenderModel.kt` 的 `GachaHistoryRow` **加** `isPity: Boolean`（可选加 `category`/`starLabel`）；`historyRows` 填充处同步；
  2. `android/feature/game/src/main/java/com/xianxia/sect/ui/game/dialogs/GachaHistoryPanel.kt` 的 `HistoryLine` 渲染保底标注（角标或更亮一档，与 §8-③ 同口径）；
  3. 页内加一行 `x/10` 保底进度（**读同一 `pityCounters` 流，禁自算**）。
- 判据：`android/feature/game/src/test/java/com/xianxia/sect/ui/game/GachaRenderModelTest.kt` 扩断言（含「历史下标 0 = 最新」与「保底行 `isPity=true`」两条）；禁用 `assumeTrue`。

---

## 4. 任务 B：公示页补「最高四阶」的池口径依据

- 现状：`GachaOddsPanel.kt` 已渲染类别权重/品阶权重/保底/价格，全部读 `GachaPoolReadModel`（`GachaRenderModel.kt:273`）。
- 缺口：`GachaPoolReadModel` **未带 `maxRarity`**；页面不解释「为什么最高只到四阶」。
- 改点：`GachaPoolReadModel` 加 `maxRarityPerKind`（或整体上限），`GachaOddsPanel` 品阶段补一行「池内最高四阶」；
  数据源 = `GachaPoolConfig.GachaCategorySpec.maxRarity`（配置侧已是 4；C++ 侧 `gacha_tx.h` 的 `clampedRarity` 强制截断）。
- 判据：`GachaConfigGuardTest.kt:112` 的「最高品阶 ≤ 4」断言保持绿；新增渲染/模型断言各一条。

---

## 5. 任务 C：图鉴完整态（相对 G11 最小态的 4 项增量）

| 增量 | 落点 | 备注 |
|---|---|---|
| ① **升星收益预览** | `GachaRenderModel.kt` 的 `GachaCodexCellModel` 加 `battleBonusText/cultivationBonusText`（**复用** `GachaStarUpModel` 已有的纯函数文案） | 纯函数化，便于单测 |
| ② **立绘大图** | `android/feature/game/src/main/java/com/xianxia/sect/ui/game/dialogs/GachaCodexPanel.kt` 的 cell 补 `portraitKey`（1024 档，**按需 decode**，禁加预载） | 与结果格 512 档 `avatarKey` 口径区分 |
| ③ **属性预览（`finalStats` 级）** | 🔴 **依赖产品拍板**：`report-G09.md` §六-1 明确「星级乘区是否进 `finalStats` 展示链」未定，且 `CachedPower` 指纹刻意不含星级 | 未拍板前**不做**，只登记（§13-1） |
| ④ **来源说明** | 静态文案「寻访所得」（模板表无来源字段） | 一句话 |

- 未解锁判据保持 G11 口径：`tid !in starMap`（**稀疏账本语义**：0 星不落键）。

---

## 6. 任务 D：引导「打开寻访」（两条路，**默认选 a**）

| 路 | 做法 | 代价 |
|---|---|---|
| **a（默认）** | 复用 `GuideCondition.CumulativeCounter`：新增 `GuideCounterKeys` 常量 + 在「打开寻访」处自增（写点建议 `GachaViewModel` 装载成功处或 `DialogFeatureRoutes.kt` 的 `renderRecruit` 入口）；任务条目 id **用 24（空号）或追加 26** | **零新增 `GameData` 字段**、零迁移；与既有 23 条任务同形 |
| b | 新增 `GuideCondition.DialogOpened(dialogType)` | 需新写点与新条件类型，超出「最简」；**除非**产品要求「必须证明是点开寻访而非计数器」 |

- id 选择：实测 id 集合 **1–23 ＋ 25，24 为空号**（历史 24 是血炼强化，随 G04 整拆）。
  ⇒ **D-2 决策：追加 id=26 而非复用 24**——旧档若残留 id 24 的进度，复用会让新步骤**开局即完成**（静默失效）。
- 判据：新步骤在真机/渲染测试里可被打开寻访推进到完成；`GuideTaskRegistry` 的既有断言（若有数量断言）同批更新；**引导不卡死**（打开寻访后步骤可完成、且不阻塞后续步骤解锁）。

---

## 7. 任务 E：死文案清零（**范围必须先划清**）

### 7.1 🔴 真·玩家可见、必清零（4 处，实测）
| # | 位置 | 现状 | 目标 |
|---|---|---|---|
| 1 | `android/feature/game/src/main/java/com/xianxia/sect/ui/game/dialogs/GachaMainPanel.kt:319-320` | `PULL_ONCE_TEXT = "招募一次"` / `PULL_TEN_TEXT = "招募十次"` | **「寻访一次 / 寻访十次」**（产品 `:461` 主界面口径） |
| 2 | `GameNotification.RecruitFailed` 消费分支 | `android/feature/game/src/main/java/com/xianxia/sect/ui/game/components/GameOverlayHost.kt:466` 的「招募失败」 | **归 G10 死码清零**（发布者已零；G11 已登记）⇒ 本批**不重复处置**，只在清零表里交叉引用 |
| 3 | `android/core/ui/src/main/java/com/xianxia/sect/ui/components/ElderBonusInfoButton.kt:322-327` 的 `recruitingElderInfo` | 文案描述「提升每年待招募弟子的刷新数量上限」——该效果**已随 G05 删除**，但 UI 仍引用（`TianshuHallDialog.kt:268-269`、`:513-523`） | 改为**当前真实效果**（纳徒长老在位只影响既有槽位/状态语义）或移除该说明项；**不得保留无效玩法说明** |
| 4 | `GachaResultLayer.kt:225-226` 的按钮文案 | `"招募一次"/"招募十次"` | ✅ **正确（产品 `:187`），不动**——列入「复核后确认不改」清单 |

### 7.2 区分类说明（**不可清零，误删即破坏红线**）
- **Kotlin `src/main` 其余 240 处**：`GameDatabaseMigrations*.kt`（历史迁移注释）、`ActionIds.kt`（「【已退役，编号禁复用】」desc）、`GameConfig.kt` 的旧常量、`BloodPoolBuildingCleanupRule.kt`/`RecruitListCleanupRule.kt`（读档修复提示）、`WorldMapGenerator.kt`（AI 宗门名）、宗门改名（**保留**功能）⇒ **一律不动**。
- **C++ 51 处**：全部是注释/退役 desc（非注释命中仅 `scene/float_text.h` 的「浮字**动画寿命**」= 假阳性）⇒ 零玩家可见文案。
- **`assets/config` / `assets/data` = 0 命中** ⇒ 无需处理。
- **`changelog_entries.json` 101 处**：全在**历史版本条目**内（描述当时下线的玩法）⇒ **只查当前版本条目，不追历史**。

### 7.3 判据
清零表格式：`关键词 | 区 | 清零前 | 清零后 | 处置（清零/豁免+理由）`；
**豁免必须写理由**（如「历史迁移注释，迁移链不可改写」）。

---

## 8. 任务 F：连抽打磨 4 项残余

| # | 项 | 现状 | 目标 |
|---|---|---|---|
| 1 | 叠轮时升星层游标 | 由 `key(resultToken)` 重建而"实测安全"，但**无守卫钉死这条假设** | 加渲染测试：连抽两轮后升星层只显示本轮条目 |
| 2 | Q39「点奖励框不关、不弹详情」 | 实现已有（`GachaResultLayer` 渲染层 `clickable` + 框内 `clickable`），**无测试** | 补 `GachaRecruitDialogTest` 一条 |
| 3 | 保底「更亮一档」 | 只有角标（§2-3） | `GachaRewardCell` 的填充/描边色按 `isPity` 提亮（与历史页保底标注同口径） |
| 4 | 数量角标可读性 | `×n` 白字位置已有，**无细描边/半透明底保证** | 按 §4.4「可加细描边/半透明底保证在彩色背景上可读」补一层 |

---

## 9. 任务 G：色板对齐债（Q31 单源化）

- 现状（G11 只保证寻访域 + 灵根徽章）：`android/core/ui/src/main/java/com/xianxia/sect/ui/theme/Color.kt` 的
  `RarityCommon…RarityHeaven`（`:40-52`）、`GameColors.getRarityColor`（`:110-118`）、同名顶层函数（`:164`）、
  归零后仅存的旧表消费点。
- 目标：把这些定义**改为引用 Q31 单源**（`GameConfig.Gacha.RARITY_COLORS` / `SPIRIT_ROOT_COUNT_COLORS`），
  消灭"同一品阶两套色值"；`UnifiedItemCard` 等消费点**不改行为**（只换色源）。
- ⚠️ 视觉影响面：仓储/奖励弹窗/详情页的品阶色会**统一到 Q31**（六阶粉红→金、五阶→红）——这是产品 `:216-218` 的**明确要求**；
  但会改动多处既有截图预期 ⇒ 实施时逐处登记。
- 判据：`GachaColorSingleSourceGuardTest` 扩为「全仓不得存在与 Q31 冲突的品阶色字面量」（源码扫描）；
  该守卫必须有**判别力自证**（退回旧值判红）。

---

## 10. 决策（D-1…D-7）

| # | 决策 | 依据 |
|---|---|---|
| **D-1** | 主界面按钮改「寻访一次/十次」；**结果页保持「招募一次/十次」** | §2-1：产品 `:461` 与 `:187` 两处口径不同，按各自上下文执行 |
| **D-2** | 引导任务 **id = 26**（不复用空号 24） | 复用会让旧档残留 id 24 的进度把新步骤"开局即完成" |
| **D-3** | 引导判据走 `CumulativeCounter`（**不新增 `GameData` 字段**） | 零迁移、零协议面；与 `AUTO_MINE_ACTIVATED` 同形 |
| **D-4** | 图鉴「属性预览」**未拍板前不做**，只登记 | `report-G09.md` §六-1 未定；`CachedPower` 指纹刻意不含星级（改了会平移既有指纹） |
| **D-5** | 死文案清零**只做 4 处真玩家可见**；历史迁移注释/退役 desc/历史 changelog **显式豁免并写理由** | §7.2：误删破坏迁移链与 ActionId 红线 |
| **D-6** | 色板对齐**改定义点、不改消费点行为**；视觉变化逐处登记 | 产品 `:216-218` 要求单一源；减小回归面 |
| **D-7** | 🔴 **`CharacterTemplateGuardTest.kt` 是 G12/G13/G14 的共享编辑面** ⇒ 本批若需触碰，先确认 G13 未在途 | 三批并行会互撞（G13 也要改三向常量断言） |

---

## 11. 文件面与切片（≤10 文件/片；全路径）

| 片 | 允许改 | 自检项 |
|---|---|---|
| **A-12a** 历史页 | `android/feature/game/src/main/java/com/xianxia/sect/ui/game/GachaRenderModel.kt`、`.../ui/game/dialogs/GachaHistoryPanel.kt` | `isPity` 进模型；`x/10` 读同一流 |
| **A-12b** 公示页 | `.../ui/game/dialogs/GachaOddsPanel.kt`、`.../ui/game/GachaRenderModel.kt`（`GachaPoolReadModel`） | `maxRarity` 依据来自配置 |
| **A-12c** 图鉴完整态 | `.../ui/game/dialogs/GachaCodexPanel.kt`、`.../ui/game/GachaRenderModel.kt` | 立绘按需 decode；未解锁判据不变 |
| **A-12d** 引导 | `android/core/domain/src/main/java/com/xianxia/sect/core/model/guide/GuideTask.kt`、`android/core/domain/.../guide/GuideCounterKeys*.kt`（若存在）、打开寻访写点（`.../ui/game/GachaViewModel.kt` 或 `.../components/dialog/DialogFeatureRoutes.kt`） | id=26；零新 `GameData` 字段；不卡死 |
| **A-12e** 死文案 | `.../ui/game/dialogs/GachaMainPanel.kt`、`android/core/ui/src/main/java/com/xianxia/sect/ui/components/ElderBonusInfoButton.kt` | 4 处清零表 |
| **A-12f** 连抽打磨 | `.../ui/game/dialogs/GachaResultLayer.kt`、`.../ui/game/dialogs/GachaRewardCell.kt` | 4 项；保底提亮 |
| **A-12g** 色板对齐 | `android/core/ui/src/main/java/com/xianxia/sect/ui/theme/Color.kt`、（必要时）`.../ui/components/ItemCard.kt` | 定义点改引用；消费点行为不变 |
| **c12-a** 测试 | `android/feature/game/src/test/.../ui/game/GachaRenderModelTest.kt`、`.../ui/game/dialogs/GachaRecruitDialogTest.kt`、`android/core/engine/src/test/.../nativebridge/GachaColorSingleSourceGuardTest.kt`、引导相关既有测试 | 判别力自证；禁 `assumeTrue` |
| **主线程** | 双 changelog、`report-G12.md`、门禁、提交 | §12 |

---

## 12. 门禁清单（终树同轮重跑；判据 = 命令输出原文）

```powershell
# C++（本批预计只碰 C++ 显示串/注释；若碰了必跑）
# 工作目录 <repo>\android\app\src\main\cpp\gamecore\build\desktop-test
cmake --build . ; ctest               # G10 之后应为全绿；本批不允许新增红
ctest -R SceneEquivalence
# JNI（C++ 有实质改动才重建；判据 = mtime + 体积 + sha256）
pwsh -File scripts/build-desktop-jni.ps1
# Kotlin 组合门（工作目录 android）
.\gradlew.bat compileReleaseKotlin testReleaseUnitTest --max-workers=1 --rerun-tasks --continue `
  "-Dgamecore.jni.path=<repo>\android\core\engine\build\desktop-jni\libgamecorejni.so" detekt lintRelease --console=plain
# node 四门（工作目录 = 仓库根）
node scripts/gen-action-ids.mjs ; node scripts/gen-game-data.mjs --check
node scripts/check-jni-count.mjs ; node scripts/check-agent-instructions.mjs
```

**真机**：本批与 `report-G11.md` §7 的 D-1…D-12 **同一台设备同一轮**验证（M1 完成判据 + M2 验收"抽卡→解锁→养成全链真机走通"合并执行）；
未做项登记 `report-G12.md` 的 pending-device 节。

---

## 13. 登记 / 待拍板

1. 🔴 **产品拍板**：星级乘区**是否进 `finalStats` 展示链**（决定图鉴「属性预览」做不做；`report-G09.md` §六-1）。
2. 🟠 引导「打开寻访」的**触发时机**（进主界面即算 / 必须点过一次抽卡才算）——默认：**打开寻访主界面即算**。
3. 🟠 埋点事件名 `gacha_pull` / `gacha_unlock` 仍只登记不上报（商业化批处理）。
4. 🟠 旋转式流光（`Brush.sweepGradient` 的 `colorStops` 逐帧采样或升级 Compose）——精修批。
5. 🟠 `ItemCard`/`GachaRewardCell` 的共享"按 resId 画注册精灵"composable —— 精修批。

---

## 14. 一句话给执行者

**G12 = G11 的"完整态 delta"，不是重做**：历史页补「按抽 + 保底标注 + x/10」、公示页补「最高四阶」依据、图鉴补立绘与升星收益预览（属性预览待产品拍板）、
引导新增「打开寻访」步骤（id=26、走 `CumulativeCounter`、零新字段）、**死文案只清 4 处真玩家可见**（历史迁移注释与退役 desc 一律豁免并写理由）、
连抽补 4 项残余（含保底「更亮一档」）、色板把旧四张物品色表改成引用 Q31 单源；
🔴 主界面按钮改「寻访一次/十次」而**结果页保持「招募一次/十次」**（产品两处口径不同，别统一）。
