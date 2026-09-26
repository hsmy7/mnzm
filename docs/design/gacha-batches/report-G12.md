# report-G12 · 体验完成（历史·公示·图鉴完整态·引导·死文案清零·连抽打磨·Q31 色板对齐）

> 派工真源：[`TASKBOOK-G12.md`](TASKBOOK-G12.md)；开工时点 2026-09-27，G10 已 accepted（`77ed62af3` 工作树）。
> 本报告的每个数字都取自**本会话同轮命令输出**，未跑的一律显式登记为未做（§9）。
> 会话纪律遵守：只实施本批、不自登记 accepted、`git add` 一律明确文件名。

---

## 1. 结论一页速览

| 项 | 结论 |
|---|---|
| 状态 | **七任务（A…G）全部落地 + 本机可得的门禁全绿**；真机面未做（本机无设备，§9 pending-device 扩到 18 项） |
| 任务 A | 历史行 `isPity` 进模型（与出货格同一条派生链）+ 历史页「保底」标注 + 页首 x/threshold 进度行（读同一 `gachaPityCounters` 流） |
| 任务 B | 公示页逐物品类别追加「（最高 N 阶）」，数据源 = `GachaCategorySpec.maxRarity`，UI 零字面量 |
| 任务 C | 图鉴已解锁格展示战斗/修炼收益预览（与升星层共用口径 A 派生链）+「全部角色均通过寻访获得」来源说明；立绘大图经复核 **G11 已交付**（§4-2），`finalStats` 属性预览按 D-4 不做只登记 |
| 任务 D | 引导任务 **id=26「初次寻访」**（空号 24 不复用）+ `GuideCounterKeys.GACHA_OPENED` + 写点 `GuideDelegate.notifyGachaOpened()`（引擎线程）+ `renderRecruit` 的 `LaunchedEffect(Unit)`——**零新增 `GameData` 字段、零迁移、零 C++ 变更** |
| 任务 E | 死文案 4 处按 D-1/D-5 处置：主界面按钮改「寻访一次/十次」、纳徒长老说明改真实效果、结果层「招募」豁免（产品 `:187`）、「招募失败」G10 已清（交叉引用）；清零表见 §5 |
| 任务 F | 保底格「更亮一档」三件套落地（底色提亮 + 描边加重 + 扫光渐隐端提亮）；`×n` 角标补细描边；新增叠轮守卫渲染测试；Q39 用例补「不触发新抽」断言 |
| 任务 G | Q31 单源化：`Color.kt` 六常量改 Q31 别名、`GameColors.getRarityColor` 委托单源、`Rarity.CONFIGS[].color` 六值改引 `Gacha.rarityColor(n)`、丹药品质三档改引 Q31 一/三/五阶、零消费 `RarityText*` 六常量删除（删前零命中贴证）；守卫扩两条 + 判别力红证实测 |
| 硬门 | 零 Room 迁移（仍 v59）、零 `@ProtoNumber`、零 ActionId 变更（201/1872）、零新 JNI 面（86/86）、**零 C++ 改动**（ctest/JNI 重建不适用，`.so` 三件套 = G10 交付值逐字一致）、零新镜像字段、零反向通道 |
| 🔴 唯一拍板项 | 星级乘区是否进 `finalStats` 展示链（图鉴属性预览的前置，`report-G09.md` §六-1 原登记有效） |

---

## 2. 验收逐条对照（任务书 §1）

| 验收 | 判据 | 实测证据 |
|---|---|---|
| ① 历史按抽+保底 | 行可区分保底、页内 x/threshold 与主界面同源 | `GachaHistoryRow.isPity` 进模型（`historyRows` 从出货格派生，禁第二份口径）；`GachaHistoryPanel` 保底行挂金色「保底」标注 + 页首 `gachaPityProgressText(pityCount, threshold)`——与主界面同一句文案函数、同一 `pityCounters` 流（`GachaPanelContent` 从 `mainInputs` 直传）；`GachaRenderModelTest` 新增「历史行带保底标注」+「历史行序」扩断言（下标 0 最新 + 非保底行 `isPity=false`）；渲染测试「寻访记录」断言进度行与保底标注两条路径 |
| ② 公示 maxRarity | 每类别上限来自配置 | `GachaPoolReadModel.maxRarityPerKind`（角色类不进表）；`GachaOddsPanel` 类别行 `26%（最高 4 阶）` 式渲染；模型断言 `mapOf("herb" to 4)` + 渲染断言原文；`GachaConfigGuardTest`「最高品阶 ≤ 4」同轮保持绿 |
| ③ 图鉴完整态 | 立绘+星级+进度+收益预览，未解锁置灰 | `GachaCodexCellModel.battleBonusText/cultivationBonusText`（复用口径 A 派生链，逐星单测 1/2/5 星 = ×1.00/×1.08/×1.32 与 ×1.00/×1.05/×1.20）；面板仅已解锁格渲染两行收益；底部来源说明一句；立绘 1024 档按需解码（G11 已交付，本批零改动）；未解锁判据 `tid !in starMap` 未动 |
| ④ 引导不卡死 | 打开寻访即计数、可完成、不阻塞 | 任务 26 判据 = `CumulativeCounter(GACHA_OPENED, 1)`；`GuideTaskTest`「寻访任务 26」钉 打开前未完成/打开一次即完成；写点在 `LaunchedEffect(Unit)`（每次打开至多一次引擎写入，不改任何解锁顺序）；`GuideDelegateTest` 钉「调用瞬间不写 + 引擎块内 +键名/增量」 |
| ⑤ 死文案清零 | §7 表逐条归零或显式豁免 | §5 清零表（4 处真玩家可见全处置 + 三类豁免区写理由）；`刷新数量上限`/`招募失败` 全仓生产面 grep 零命中 |
| ⑥ 连抽打磨 4 项 | 全绿，保底「更亮一档」按 §4.4 落地 | ① 叠轮守卫测试新增（`key(resultToken)` 重建清游标假设钉死）；② Q39 用例补「不触发新抽」断言；③ 提亮三件套（`PITY_FILL_ALPHA=0.32` / `EMPHASIZED_STROKE_DP=3` / `EMPHASIZED_DIM_ALPHA=0.6`，历史页保底标注同口径）；④ `×n` 细描边 0.5dp（§4-1：半透明底 G11 已有，本批补描边） |
| ⑦ 色板对齐 | 旧表全部改引 Q31 单源 | `Color.kt` 六常量 = `GachaColors.parse(GameConfig.Gacha.rarityColor(n))`；`GameColors.getRarityColor` 委托；`ItemCard.getRarityColor` 经常量别名自动随源；`Rarity.CONFIGS[].color` 六值改引；丹药品质三档改引；`ItemCardColorSourceTest`（:core:ui 行为面）+ 守卫两条新用例（源码扫描 + 行为面）；消费点代码零改动（行为只换色源，D-6） |
| ⑧ 零越界 | 四守卫绿 + 读面登记 | `MirrorReadOnlyGuardTest`/`MirrorConsumerSurfaceGuardTest`/`ViewModelArchitectureTest`/`DialogTypeRenderCoverageTest` 全量轮内绿；`docs/ui-read-surface.md` 历史页保底进度行读面已回写（同一 `gachaPityCounters` 流，无新增镜像字段）；`check-agent-instructions` ✓（§8） |
| ⑨ 全量门禁 | JUnit --rerun-tasks + detekt 0 + lint 36w/0e + 规则③计数不增长 | §8 门禁表（终树同轮第二轮实测；第一轮仅 `:core:engine:detekt` 1 条 `VariableNaming` 红，真修后复跑整门） |
| ⑩ 交付物 | 双 changelog + 本报告 + 单次提交 | `changelog_entries.json` 追加 9 条玩家文案（`node JSON.parse` ✓）；`CHANGELOG.md` [4.01.16] 段内新增 G12 节；单次 `feat(gacha)` 提交 |
| 不做面 | 轮换池/UP、翻牌长动画、埋点上报、RNG 基线、finalStats 预览 | 全部遵守：零新池、零动画结构变化（只加静态描边档）、埋点只登记（§9-3）、`gacha_tx.h`/seed/金黄 digest 未动（ctest 基线无从平移）、`CharacterTemplateGuardTest` 未触碰（D-7） |

---

## 3. 实施内容（按任务书 §11 切片）

| 片 | 落地 |
|---|---|
| **A-12a** 历史页 | `GachaRenderModel.kt`（`GachaHistoryRow.isPity` + `historyRows` 填充）、`GachaHistoryPanel.kt`（保底标注 + 进度行，签名扩 `pityCount/pityThreshold`）、`GachaMainPanel.kt`（`gachaPityProgressText` 共享函数 + HISTORY 分支传参） |
| **A-12b** 公示页 | `GachaPoolReadModel.maxRarityPerKind` + `poolReadModel` 派生 + `GachaOddsPanel.categoryValueText`（角色类不显示上限） |
| **A-12c** 图鉴 | `GachaCodexCellModel` 两收益文案属性 + `starBattleBonusText/starCultivationBonusText` 从 `GachaStarUpModel` 私有 companion 提升为文件级共用；`GachaCodexPanel` 收益两行（仅已解锁）+ 来源说明 + `TEXT_STACK_DP` 48→76 |
| **A-12d** 引导 | `GuideCounterKeys.GACHA_OPENED`、`GuideTask.kt` id=26、`GuideDelegate.notifyGachaOpened`、`DialogFeatureRoutes.renderRecruit` 的 `LaunchedEffect`；native 臂 `BOUNDARY_GUIDE_COUNTER_INCREMENT_TX` 按 key 泛化 ⇒ **C++ 零改动** |
| **A-12e** 死文案 | `GachaMainPanel` 两常量改「寻访」、`ElderBonusInfoProvider.recruitingElderInfo` 改真实效果；结果层两常量按 D-1 显式不动 |
| **A-12f** 连抽打磨 | `GachaShimmer.kt`（`emphasized` 参数 + 提亮档位常量 + `gachaCellFillColor(color, isPity)`）、`GachaRewardCell.kt`（传入 `isPity` + `×n` 细描边 + KDoc 收口） |
| **A-12g** 色板 | `Color.kt`（六别名 + 委托 + 删 `RarityText*` 六常量）、`GameConfig.kt`（`Rarity.CONFIGS` 六色改引）、`ItemCard.kt`（品质三档改引 + 档位常量）、`GachaColors.kt`（KDoc 单源边界改写） |
| **c12-a** 测试 | `GachaRenderModelTest`（+2 例、2 例扩断言）、`GachaRecruitDialogTest`（+1 例、4 例扩断言、常量改「寻访」）、`GuideTaskTest`（+1 例、3 例断言更新）、`RarityConfigTest`（+1 例、6 例期望值对齐）、`GachaColorSingleSourceGuardTest`（+2 例、KDoc 收口段改写）、新 `GuideDelegateTest`（1 例）、新 `ItemCardColorSourceTest`（4 例） |
| **主线程** | `docs/ui-read-surface.md` 读面回写、双 changelog、本报告、两轮门禁、单次提交 |

---

## 4. 上位失真与漏项（本批实测 6 条）

| # | 任务书记载 | 实测真值 | 处置 |
|---|---|---|---|
| 1 | §8-④：「×n 无细描边/**半透明底**保证」 | `QUANTITY_BACKING`（`Color(0x66000000)` 圆形半透明底）G11 已交付——缺口只剩细描边 | 补 0.5dp 深色低透明描边一层；报告如实登记 |
| 2 | §5-②：「图鉴 cell 补 `portraitKey`（立绘大图）」 | G11 已交付：`GachaCodexCellModel.portraitKey` + 面板 `SpriteImage` + 测试断言 `portrait_+tid` | 复核确认不重做；本批只加收益预览与来源说明 |
| 3 | §8-②：「Q39 点框不关**无测试**」 | `GachaRecruitDialogTest` 已有「点奖励框不关结果层 - Q39 防误关」用例 | 不重写；扩断言（点击后 `pullCalls` 不变 = 不弹详情也不触发新抽） |
| 4 | §9 行号锚（顶层函数 `:164`、`:40-52`） | G10 收口后行号漂移（顶层 `getRarityColor` 实测 `:149`）；`:40-52` 范围实含 `RarityText*` 六常量——全仓 grep **零消费**的死表 | `RarityText*` 六常量删除（属 A-12g 授权文件面 + 任务书点名范围，删前零命中贴证；G10 §3.1 纪律） |
| 5 | 验收⑦点名 `ItemCard.getQualityColor` | 该函数不是「品阶旧表」，是丹药品质三档自写字面量（下/中/上 = 灰/蓝/红） | 按验收字面执行：改引 Q31 一/三/五阶（色相与原同族、视觉几乎无感，映射登记 §5 表后注） |
| 6 | §7.1-2 仍列 `GameOverlayHost.kt:466`「招募失败」 | 全仓 `RecruitFailed`/`招募失败` 生产面 grep **零命中**（G10 已整链清除） | 只交叉引用，不重复处置 |

---

## 5. 死文案清零表（§7.3 判据格式）

| 关键词 | 区 | 清零前 | 清零后 | 处置 |
|---|---|---|---|---|
| 「招募一次/招募十次」 | feature/game 主界面 | `GachaMainPanel` 两常量 | 「寻访一次/寻访十次」 | **清零**（D-1，产品 `:461` 主界面口径） |
| 「招募一次/招募十次」 | feature/game 结果层 | `GachaResultLayer` 两常量 | 不变 | **豁免+理由**：产品 `:187` 结果页口径原文如此，两处口径不同不统一（§2-1 实测真值）；测试常量与 KDoc 同步标注 |
| 「招募失败」 | GameOverlayHost | （任务书 `:466`） | 全仓零命中 | **已清零**（G10 D-3，本批 grep 贴证零命中，交叉引用） |
| 「刷新数量上限」 | core/ui `ElderBonusInfoButton` | `recruitingElderInfo` 描述已随 G05 下线的效果 | 改当前真实效果（占用长老槽位/在岗状态语义，无额外数值加成） | **清零**（A-12e；渲染消费点 `TianshuHallDialog` 零改动自动跟随） |
| 迁移注释 240 处（`GameDatabaseMigrations*`/`ActionIds` 退役 desc/`GameConfig` 旧常量注释/`RecruitListCleanupRule` 读档提示/`WorldMapGenerator` AI 宗门名/宗门改名） | Kotlin src/main | 240 处 | 不变 | **豁免+理由**：历史迁移注释与退役 desc 属迁移链与「ActionId 只增不复用」红线内容（D-5）；读档提示与改名是存活功能 |
| C++ 51 处 | gamecore | 注释/退役 desc（唯一非注释命中 = `float_text.h` 浮字动画寿命，假阳性） | 不变 | **豁免+理由**：零玩家可见文案（任务书 §7.2 实测口径沿用） |
| changelog 101 处 | `changelog_entries.json` 历史版本条目 | 描述当时下线玩法 | 不变 | **豁免+理由**：只查当前版本条目（4.01.14 首条目本批新增 9 条均为活玩法描述） |

> 丹药品质映射注：下品→Q31 一阶灰 `#b8b8b8`、中品→三阶蓝 `#2196f3`、上品→五阶红 `#f44336`
> （原 `#95A5A6/#3498DB/#E74C3C` 同族色相，色值收束到单源；异常品质名回落一阶灰同口径）。

---

## 6. 判别力自证（退回旧态判红，逐条实测）

| # | 构造反例（只改一处） | 实跑结果 |
|---|---|---|
| 1 | `Rarity.CONFIGS[6].color` 临时改回字面量 `#e3a0a0` | `GachaColorSingleSourceGuardTest` **两条新用例同轮 FAILED**：`全仓生产面零旧品阶色字面量`（点名 GameConfig.kt 行号）+ `品阶色与丹药品质色全部委托 Q31`（`Rarity.getColor(6)` ≠ Q31）；复原改引后复跑 **BUILD SUCCESSFUL** |
| 2 | `GuideDelegate.notifyGachaOpened` 的键/增量改错 | `GuideDelegateTest` verify 参数匹配必红（键名/增量钉死在用例内；`exactly=0` 前置校验钉「调用瞬间不写」） |
| 3 | 叠轮假设（`key(resultToken)` 重建清游标） | 新增渲染测试按「第一轮确认 → 第二轮叠十连 → 升星层只显示本轮条目 → 确认后十格铺开」四步钉死；游标若被记住，第二轮升星条目被吞 ⇒ `secondTemplate.name` 不可见 + `firstTemplate.name` 不消失，双断言同红 |

---

## 7. 旧用例处置表

| 用例 | 处置 | 理由 |
|---|---|---|
| `RarityConfigTest` getColor×6 | **保留，期望值对齐 Q31**（2 绿 3 蓝 4 紫 5 红 6 金）+ 新增「全部委托 Q31」1 例 | 被测定义点改引单源，逐档期望值随之更新 |
| `GuideTaskTest` 数量（24→25）/ID（+26）/getTask(24 空号断言保留) | **保留，断言更新** | 任务 26 入表；空号 24 的「不得复活」断言保留（D-2 反向钉住） |
| `GuideTaskTest` 其余 | 保留 | 未触碰对应生产面 |
| `GachaRenderModelTest` 18 例 | **保留**；「公示读面」「历史行序」两例扩断言；新增 2 例（保底标注 / 收益预览） | A-12a/b/c 判据 |
| `GachaRecruitDialogTest` 10 例 | **保留**；主面板按钮常量改「寻访一次/十次」（D-1）；Q39/公示/历史/图鉴四例扩断言；新增 1 例（叠轮） | 同上 |
| `GachaColorSingleSourceGuardTest` 4 例 | **保留**；KDoc「故意不覆盖」段改写（旧表定义已收口，豁免面改为境界色/灵根元素色维度）；新增 2 例 | A-12g |
| 其余全仓用例 | 不动 | 本批未触碰对应生产面 |

---

## 8. 门禁表（终树同轮实测；判据 = 命令输出原文）

> 组合门共跑两轮：**第 1 轮** `BUILD FAILED in 22m 42s`（339 tasks 全执行：JUnit 全绿，唯一红 =
> `:core:engine:detekt` 1 条 `VariableNaming`——新守卫测试类体私有 val 命名，移入 companion 真修）；
> **第 2 轮 = 交付树**，`--rerun-tasks` 整门复跑，下表全部取自第 2 轮。

| 门 | 实测值 | 对照 G10 基线 |
|---|---|---|
| Kotlin 组合门 | `compileReleaseKotlin testReleaseUnitTest --max-workers=1 --rerun-tasks --continue -Dgamecore.jni.path=<G10 .so> detekt lintRelease` ⇒ **BUILD SUCCESSFUL in 23m 38s**（339 tasks 全执行） | 第 1 轮 22m42s FAILED（detekt 1 条）→ 真修后全绿 |
| JUnit 逐模块 | **7474 / 0 failures / 0 errors / 18 skipped**（694 份 XML；app **1011** / domain **1571** / data **809** / engine **2936** / ui **155** / feature:game **992**） | 7462 → 7474 = **+12**（domain +2 / engine +2 / ui +4 / feature:game +4，逐类账见 §7）；skipped 18 = app 2 + data 15 + engine 1（同基线） |
| detekt | 六模块 0 error 0 warning（`detekt FAILED` 计数 = 0；两份 baseline 零改动） | 同基线 |
| lint | `Lint found 36 warnings (and 3 warnings filtered by baseline lint-baseline.xml)`，0 errors | 36 与 G10/G11 同值 ⇒ 全预存 |
| 桌面 ctest / JNI 重建 | **不适用**——本批 C++ 零改动（任务书 §12「若碰了必跑」）；`.so` 三件套开工实值 = **9015808 字节 / mtime 2026-09-26 23:32** = G10 交付值逐字一致 | 零漂移 |
| ActionId | `gen-action-ids.mjs` = **201 actions (maxId=1872)**，regen 前后两产物零漂移（`git diff --exit-code` ✓） | 同值（本批零 catalog 变更） |
| 游戏数据 | `gen-game-data.mjs --check` ✓，sha256 `035066cb…94ef` 逐字符不变 | 同值 |
| JNI 计数 | `check-jni-count.mjs` ✓ **86/86 双桥无扩散** | 同值 |
| 规范门禁 | `check-agent-instructions.mjs` ✓ 五规则全过：路由闭包 42 篇 / 444 条引用零死链（本批改 `ui-read-surface.md` 后复跑；报告落盘后再复跑一次，见 §9-11） | 不精确引用计数**不增长**（验收⑨③） |
| Room | 零迁移、零 `@ProtoNumber` 变更（`DATABASE_VERSION` = 59） | 同值 |

---

## 9. pending-device / 未完成 / 登记

**真机 pending-device（G11 §7 D-1…D-12 十二项仍未消，本机无设备；本批新增 6 项，合计 18 项同轮验证）**：

| # | 步骤 | 通过标准 |
|---|---|---|
| D-13 | 历史页 | 页首保底进度行与主界面数值一致；保底抽行有金色「保底」标注 |
| D-14 | 公示页 | 物品类别行显示「（最高 N 阶）」，数值与公示权重段一致 |
| D-15 | 图鉴 | 已解锁格收益预览两行可读、未解锁格不显示；小屏不挤压立绘 |
| D-16 | 结果页 | 保底格明显更亮一档（底色+描边）；`×n` 在金/红亮底上可读 |
| D-17 | 引导 | 左栏点「寻访」→ 引导「初次寻访」进度 +1、可领奖；不卡后续步骤 |
| D-18 | 色板走查 | 仓储/商人/详情/奖励弹窗品阶色与寻访一致（六阶金五阶红），无同屏两套色 |

**登记**：

1. 🔴 **产品拍板**：星级乘区是否进 `finalStats` 展示链（图鉴「属性预览」不做的前置；`report-G09.md` §六-1 原登记有效；`CachedPower` 指纹刻意不含星级，改了会平移既有指纹）。
2. 🟠 引导触发时机按任务书 §13-2 **默认「打开寻访主界面即算」落地**（`LaunchedEffect` 写点）；若产品改「必须完成一次抽卡」，仅需把写点从 `renderRecruit` 迁到 `onPullResult` 成功臂，任务定义零改动。
3. 🟠 埋点 `gacha_pull` / `gacha_unlock` 仍只登记不上报（商业化批）。
4. 🟠 旋转式流光（`sweepGradient` colorStops 采样）与共享「按 resId 画注册精灵」composable 仍归精修批。
5. 🟠 `predefinedCodes` 置空后的运营下发通道（G10 §8-4 移交，商业化口径）。
6. 🟠 通知通道后端管线退役拍板（G10 §8-1 原登记有效）；`SpiritRootGenerator` 生产零调用（G10 §8-3，后续死码批，本批未顺手删）。
7. 🟠 `architecture.md` 乘区表两处失真（任务书 §2-6）归 G14；本批知情未动。
8. 🟠 守卫禁令清单不含 `666666`：该灰是通用色（MessageBar/聊天/年报等合法用途），非旧表唯一指纹——守卫按「唯一指纹字面量」设计，防误伤（`RarityTextCommon` 常量已删，其余五文字变体字面量在禁令清单内）。
9. 🟠 D-7 共享编辑面：本批**未触碰** `CharacterTemplateGuardTest`（无需触碰）——G13 并行无冲突。
10. 🟠 构建副产物 `atlas-rgba-manifest.json`（仅 generatedAt 一键差异）提交前 `git checkout --` 还原（G10 实证流程沿用）。
11. 🟠 本报告落盘后 `check-agent-instructions.mjs` 复跑一次（新增 `report-G12.md` 引用面），结果见 git 提交说明。

---

## 10. 树身份与证据边界

| 项 | 值 |
|---|---|
| 分支 / 开工 HEAD | `feat/gacha-m0-m1` / `77ed62af3` |
| 树况 | 开工 = `docs/research/`×2 未跟踪（G10 核验记录允许）＋ 看护台账零行尾改动；此后全部改动为本批文件面（生产 16 + 测试 7 + 文档 4 + 副产物 1 还原） |
| 门禁轮次 | 第 1 轮 22m42s（detekt 1 条命名红）→ 真修 → 第 2 轮交付树全绿；两轮之间除该测试文件一处常量移位外零代码改动 |
| 提交 | 单批内逐文件 `git add <file>`；`docs/research/`×2 不入库 |
