# TASKBOOK-G10 · RNG 对拍基线重录 + 全量回归收口 + 死代码 grep 清零 + 文档收口

> **本文件是 G10 的派工真源**，取代 `HANDOVER-m1-remaining-3.md` §6.3 的转述（该节实测 **4 处计数错**，见 §2）
> 与 `docs/design/character-gacha-implementation.md` §G10（`:187-194`）的粗口径。
> **时点**：2026-09-26，M1 已完成 10/11（G11 收官 `a2923bced`）；本批 = **M1 末批**。
> **侦察方式**：1 个只读子代理穷举 9 份报告的登记节 + RNG 重录面 + 死代码面 + 文档面（逐条 file:line 实测）。
> **本批纪律（两条新增，因 G11 任务书的失误而定）**：① 引用一律写**仓库根相对完整路径**；
> ② 任务书内出现的任何代码片段必须已过项目红线（禁 `!!`／`runBlocking`／`Random`／直写 Store／硬编码数值）。

---

## 0. 位置、依赖与风险等级

| 项 | 内容 |
|---|---|
| 依赖 | G02–G09、G11 **全部已合入**（✅ 已满足）；G12/G13 依赖本批的基线窗口 |
| 出口判据 | ctest **全绿**（金黄已重录）＋ 全量 JUnit 绿 ＋ 死代码/死符号清零表归零 ＋ 文档改写清单归零 ＋ `report-G10.md` |
| 🔴 风险等级 | **本批是全项目唯一一次允许重录金黄/对拍基线的窗口**。任何「顺手改随机消费序」都会把这一窗口污染成两次（§7） |
| 不做 | 不做新功能、不动 `gacha_tx.h` 抽卡算法、不动 `seed + 12` 播种式、不动 `advancePhaseBaseline` 冻结基准、不动 `kProbeVersion`、不删列/不改 Room/不改 `@ProtoNumber`、不改版本号 |

---

## 1. 目标与验收判据

| 项 | 内容 |
|---|---|
| 目标 | ① 把 G02–G09 删除面造成的 RNG 平移**一次性**重录到位；② 全量回归跑绿；③ 把 9 批累积的死代码/死符号清零；④ 把活文档里"仍描述已删玩法"的段落改掉 |
| 验收① | 🔴 **金黄两处同步重录**：`determinism_probe.h:37` 的 `kGoldenDigest` **与** `android/app/src/androidTest/java/com/xianxia/sect/NativeFpDeterminismTest.kt:25` 的字符串常量**同批改成同一个新值**；改完 `ctest -R Determinism` 全绿（含 `DigestIsStableAcrossRepeatedRuns`） |
| 验收② | 三条红清零：`DiscipleFactory.GoldenSequenceSeed42`、`DiscipleFactory.GoldenSequenceSeed987654321Female`（字面量在 `test/disciple_factory_test.cpp:85-108` / `:122-145`）、`DeterminismProbeTest.DigestMatchesGoldenBaseline` ⇒ `ctest` **全绿** |
| 验收③ | `RngSourceGuardTest` 的**上限型登记值按实值下调**（假绿消除，见 §2-5）；`docs/rng-source-inventory.md` §2/§3 整行按 `grep` 重跑盘点 |
| 验收④ | 死代码/死符号清零表（§5）**逐条归零或改为"保留并给理由"**；新增/修改的守卫必须有判别力（退回旧态判红） |
| 验收⑤ | 文档改写清单（§6）逐条完成；`node scripts/check-agent-instructions.mjs` = ✓ 全部通过且**引用不精确计数不增长** |
| 验收⑥ | 全量 JUnit（`--rerun-tasks`）＋ detekt 六模块 0 ＋ lint 36 警告 0 error；`git status` 只留本批改动 |
| 验收⑦ | 双 changelog ＋ `report-G10.md` ＋ 单次提交（**允许拆多笔但必须都在本批内且报告写明**） |

---

## 2. 🔴 上位失真与实测修正（执行者必读）

| # | 上位记载 | 实测真值 | 后果 |
|---|---|---|---|
| 1 | `HANDOVER-3` §6.3「G02 保留项登记 **13** 条」 | 实为 **14 行**（`report-G02.md:69-84`，#1–#14） | 逐条处置会漏一条 |
| 2 | §6.3「G02 未完成/登记 **9** 条」 | 实为 **7 条顶层 bullet**（`report-G02.md:88-101`）＋ 11 行收口表（`:105-116`）＋ 2 条尾注 | 同上 |
| 3 | §6.3「G06 既有 ctest **4** 条」 | 实为 **3 条**（`report-G06.md:85`） | 计数对账错 |
| 4 | §6.3 **整表缺 G09 行** | `report-G09.md` §6.2 有 **9 条**登记需纳入 | 漏一整批 |
| 5 | 🔴 `RngSourceGuardTest` 被当作"计数守卫" | 它是**上限型**（`RngSourceGuardTest.kt:192 hits.size > limit`）⇒ G02–G11 只删不增时**恒假绿**；实测登记值与 `docs/rng-source-inventory.md` §2 **三处矛盾**（inventory 写 core/domain ②=5、core/engine ⑤=7、合计 21；守卫实值 core/domain ②=6 `:99`、⑤=19 `:102`、core/engine ②=14 `:105`、④=2 `:107`、⑤=5 `:110`、feature ②=1 `:125`、⑤=1 `:128`） | **死代码清零没有守卫背书**：必须同批把上限下调到实值 |
| 6 | 「`Diff*` 家族两侧同源 ⇒ 无需重录」（G03–G08 一路沿用） | **对两个新夹具不成立**：`DiffGachaPullTest`（`:492/:623` 黄金表字面量）与 `DiffGachaFragmentTest`（`:310/:387`）是**硬编码黄金值**，绑 `GACHA(12)` 新分区 + 固定种子 | 本批**不要动** `gacha_tx.h` / `seed + 12` 播种式，否则新增红并扩到 Kotlin 侧 |
| 7 | `report-G06.md:83`「`eraseDiscipleDerivedMaps` 生产消费方 = 0」 | **有生产调用**：`android/core/engine/src/main/java/com/xianxia/sect/core/engine/service/DiscipleLifecycleProcessor.kt:124`（`applyCombatInjury` 路径；定义在 `.../domain/disciple/DiscipleDerivedMapsCleanup.kt:14`） | 误删即破坏重伤链 |
| 8 | `report-G03.md:156`「`rootCount = 1;` 自赋值 2 处」 | **3 处**且行号漂移：`gamecore/include/gamecore/system/ai_sect_ops.h:164-165`、`.../disciple_stats.h:474-475`、`.../disciple_stats.h:523-524` | 漏清一处 |
| 9 | 待清符号 `clearAllDiscipleSlotsForRemoval` / `GameEngineLifecycleOps.initializeNewGameSuspend` / `FormulaService.calculateSuccessRateBonus` | **符号已不存在**（全仓 0 命中）⇒ 已由后续批解决，**不要再列** | 白跑 |
| 10 | `HANDOVER-3` §2.3「十八条坑」 | 现为 **26 条**；与本批强相关的 12 条：2 副产物／3 changelog 工具／5 读日志／9 手工复刻期望表／12 零漂移自证／13 detekt 口径／14 引用计数／16 死码（搬迁后旧位置同名成员）／17 退役集／18 类型不符／21+26 `.so` 判据 = mtime＋体积＋**sha256**／23 反例复跑必须 `--rerun-tasks` | 门禁漏项 |

---

## 3. 任务 A：RNG 基线重录（**本批唯一窗口**）

### 3.1 开工固证（先做，不可跳）
```powershell
git -C C:\Mnzm\XianxiaSectNative status --porcelain        # 树净（只允许 docs/research/ ×2）
git -C C:\Mnzm\XianxiaSectNative rev-parse HEAD
# 桌面 C++：<repo>\android\app\src\main\cpp\gamecore\build\desktop-test
ctest -N                                   # 用例总数（G11 基线 = 1437）
ctest -R Determinism --output-on-failure   # 抄下 actual= / golden= 原文
Get-ChildItem <repo>\android\core\engine\build\desktop-jni | Select Name,Length,LastWriteTime
(Get-FileHash <repo>\android\core\engine\build\desktop-jni\libgamecorejni.so -Algorithm SHA256).Hash
```

### 3.2 重录步骤（顺序固定）
1. 跑 `ctest` 取三条红的**断言原文**（`:85-108`、`:122-145` 的实际值、探针的 `actual`）。
2. **判定每条红是"预期的删除平移"还是"真缺陷"**：预期 ⇒ 重录；若出现**非三条**的红 ⇒ 先按 §6.1 的 A/B 判据定性，**A 类必修，B 类才登记**。
3. 重录金序列：把 `test/disciple_factory_test.cpp` 的期望常量改为实测值（**只改期望，不改被测逻辑**）。
4. 🔴 重录探针摘要：**同时**改两处 —— `gamecore/include/gamecore/determinism_probe.h:37` 的
   `kGoldenDigest` 与 `android/app/src/androidTest/java/com/xianxia/sect/NativeFpDeterminismTest.kt:25` 的字符串常量。
   **`determinism_probe.h` 的 `kProbeVersion` 不得改动**（改它 = 第二次基线重定）。
5. 🔴 `advancePhaseBaseline`（Kotlin 侧冻结基准）**不得改**：4 个 `Diff*` 夹具（`DiffPhaseSettlementTest.kt:68/370/552/573`、`DiffAuthoritativeTickTest.kt:481`、`DiffTimeTest.kt:56`、`DiffMonthSettlementFixture.kt:508`）由它驱动、**无录制值**；一改它们会红且**不是 B 类**，极易误判。
6. 复跑 `ctest`（**全绿**）+ `ctest -R Determinism`（两条都 Passed）。
7. 提交说明必须写明：**根因**（哪些批次的删除平移了序列）＋ 新 `kGoldenDigest` 值 ＋ 「唯一重录窗口」字样。

### 3.3 判据
- `ctest` 全绿；`DeterminismProbeTest.DigestMatchesGoldenBaseline` 与 `DigestIsStableAcrossRepeatedRuns` 均 Passed；
- `report-G10.md` 里记：旧值 → 新值、旧红集 → 新红集（空）、以及**「本批未改任何 RNG 消费次数/顺序」**的证据（`git diff --stat` 里 `disciple_factory.h` / `phase_settlement.h` / `month_settlement.h` / `year_settlement.h` 的 RNG 相关行**零改动**）。

---

## 4. 任务 B：全量回归收口

1. 桌面：`cmake --build .` ＋ `ctest`（全绿，见任务 A）。
2. 桌面 JNI 重建：`pwsh -File scripts/build-desktop-jni.ps1`（**工作目录 = 仓库根**；判据 = mtime ＋ 体积 ＋ **sha256** 三件套，见 §2-10）。
3. Kotlin 组合门：`compileReleaseKotlin testReleaseUnitTest --max-workers=1 --rerun-tasks --continue "-Dgamecore.jni.path=<.so 绝对路径>" detekt lintRelease`
   - 🔴 凡"退回旧态判红"的判别力实验，复跑必须带 `--rerun-tasks`（C++ 头不是 Gradle 任务输入，否则 UP-TO-DATE 假绿）。
4. 基线对照（G11 终树）：ctest **1437**；JUnit **7471/0/0/18**（app 1011 / domain 1571 / data 814 / engine 2936 / ui 151 / feature 988；692 XML）；detekt 六模块 0；lint 36 警告 0 error；ActionId 201/1872；JNI 86/86；Room v59；`game-data.json` sha256 `035066cb…94ef`；图集 41 精灵 / `layoutHash 6a122ed22d66bee5`。
5. 复跑 node 四门 + `catalog↔guard` 退役集（应仍 **24 == 24** 双向零差）。

---

## 5. 任务 C：死代码 / 死符号 grep 清零

### 5.1 ✅ 实测零调用 —— 可删（删前按名再 grep 一次，防 §2-7 式过期结论）
| 符号 | 定义位置 | 现状 |
|---|---|---|
| `NullSafeProtoBuf.relationIdToProto` / `relationIdFromProto` | `android/core/data/src/main/java/com/xianxia/sect/data/serialization/NullSafeProtoBuf.kt:376/378` | 仅自 KDoc（`:30/:34`）与自身测试引用 ⇒ **清零需同批改测试** |
| `NullableStringSerializer` / `NullableLongSerializer` | `.../data/serialization/Serializers.kt:14/28` | 全仓 0 引用 |
| `DiffMonthSettlementFixture.buildProductionProcessor` | `android/core/engine/src/test/java/com/xianxia/sect/core/nativebridge/DiffMonthSettlementFixture.kt:83` | 0 调用（`buildDiffService` 已不存在） |
| `BeastMaterialDatabase` 四个零消费者方法 | `android/core/domain/.../registry/BeastMaterialDatabase.kt:351/354/361/368` | 0 外部引用 |
| `DiscipleFacade.addDisciple` 全链 | `DiscipleFacade.kt:21` → `DiscipleFacadeImpl.kt:67` → `DiscipleService.kt:58` → `DiscipleLifecycleManager.kt:49`（另 `GameEngineDiscipleOps.kt:12`） | 唯一调用方是测试（`DiscipleServiceCrudTest.kt:103/115`）；守卫 `DiscipleCreationPathGuardTest.kt:109-110` 已把该口列为 `LEGACY_CRUD` ⇒ 删除需**同批改这两处测试** |
| `GameNotification.RecruitFailed` | `android/core/domain/src/main/java/com/xianxia/sect/core/state/GameNotification.kt:6` | **发布者 0**（消费分支 `android/feature/game/src/main/java/com/xianxia/sect/ui/game/components/GameOverlayHost.kt:466` 仍在）⇒ 删变体＋删分支，或改判保留（见 §7 D-3） |
| `MIN_AGE` | `android/core/domain/.../GameConfig.kt:126` | 仅 3 处测试引用 |
| `MailAttachment.extra` | `android/core/data/.../MailEntity.kt:21` | 仅测试引用（**删字段涉及 Room ⇒ 见 §7 D-4**） |
| `recruitListAggregates` | `android/feature/game/.../GameViewModel.kt:533` | 0 消费者 |
| `rootCount = 1;` 自赋值（**3 处**） | `.../system/ai_sect_ops.h:164-165`、`.../system/disciple_stats.h:474-475`、`:523-524` | 无效语句，直删 |
| 零调用色 helper | `android/core/ui/.../theme/Color.kt` 的 `getSpiritRootCountColor`、`SingleRoot…PentaRoot`、`XianxiaColorScheme.rarityColors`；转发壳 `WarehouseTab.kt:893`、`MerchantDialog.kt:679` 的 `getRarityColor` | G11 实测零调用方（G11 报告 §8-8） |

### 5.2 🔴 判为**不是**死码 —— 保留（防误删）
`eraseDiscipleDerivedMaps`（生产调用 `DiscipleLifecycleProcessor.kt:124`）、`recruitCountThisMonth`（多处生产读写 + C++ 契约）、
`kPermanentLife`（`gamecore/include/gamecore/system/pill_system.h:45/54/87/164`）、`notifyBreakthroughChanges`（`DiscipleBreakthroughHandler.kt:239`）、
`checkAndRepairMerchantAndRecruit`（`GameEngineLifecycleOps.kt:57/189`）。

### 5.3 🟠 需拍板后处置（不阻塞其余清零项）
- `prisonerSpiritRootFilter`：**字段仍活**（`GameData.kt:534`、`GameDataFieldPatch.kt:232`、`SectPolicyDomainState.kt:16/36/50`、`models.h:1251`、`lock_beast_tx.h:175/209/247`、`json_codec.cpp:1195/1286`），但 `AutoAssignDelegate.kt:88/93` 两个方法**零 UI 调用方** ⇒ **本批只删死 UI 入口**；字段删除需 Room 迁移 ⇒ 登记（§7 D-5）。
- `predefinedCodes`：`RedeemCodeManager.kt:87` 已是空 `mutableMapOf()`，仅 `:240` 由 `serverCode` 填充 ⇒ 编译期码表已空（运营口径，登记 G12）。

### 5.4 守卫同步（本批必做）
1. `RngSourceGuardTest`：把 §2-5 的**上限值下调到实测值**（只缩不增），并给每条登记加一句「实测值来源」注释。
2. `docs/rng-source-inventory.md`：§2（`:35-41`）与 §3 按 `grep -rn "RngPartition\."` **重跑盘点**；删掉死引用（`:99` `ChildBirthSystem:147`、`:143` `MerchantAndRecruitService:224`）；`:286` 的 `SYSTEM(3)`（35 处）整行按实值重写（其中 `LawEnforcement*`(9)、`ChildBirthSystem`、`PartnerSystem`、`RecruitService`、`GameEngineSpiritRootOps`、`TraitAddOps`、`TraitWashOps` **全部已删**）。
3. 每条死码删除都要有**按名 grep 零命中**的贴证（双向：删除模式归零 + 保留清单命中贴证）。

---

## 6. 任务 D：文档收口（活文档只描述当前状态）

### 6.1 需改写（逐文件逐行；行号为本轮实测）
| 文件 | 行 | 要改什么 |
|---|---|---|
| `docs/cpp-engine.md` | `:211`（血炼三件套）、`:212`（天赋/体质/词条 204）、`:621`（逐出/拜师/婚姻批准 —— G15 报告点名**优先**）、`:622`（仓库驻守/洗炼）、`:623`（招募列表）、`:561`/`:563`（执法堂/执法域） | 删条目或改注「已删」表述 ⇒ 只描述当前状态 |
| `docs/architecture.md` | `:97`（L3 月变树 反动/执法/叛逃）、`:99`（伴侣配对＋忠诚衰减）、`:102`（T1 年龄不变量/招募三件套/驻军报告）、`:109`/`:114`/`:117`、`:410`/`:414`（`DiscipleAgePositiveRule`/`AgeLifespanRule`） | 同步现况 |
| `docs/knowledge-base.md` | `:17`、`:201-215`（**整节「偷盗系统年上限」**，含 `:209/:211/:215`）、`:182`、`:194`（技能含 loyalty）、`:306`/`:310`、`:475`/`:476`（`ChildBirthSystem`/`renameDisciple`）、`:643`（跨宗道侣配对）、`:721`（偷盗损失经济行） | 同步现况 |
| `CODE_WIKI.md` | `:94`（逐出/拜师/婚姻批准 —— 点名**优先**）、`:93`/`:95`/`:96`、`:299`（`DiscipleDelegate` 招募/驱逐/道侣）、`:327`（lifespan/age）、`:549`（邮件/生育/道侣）、`:1018`（传功，待核）、`:306`（宗门/改名，待核） | 同步现况；Facade/Delegate 列表按现状（第 8 个 Facade 为 `GachaFacade`） |
| `docs/ui-read-surface.md` | `:124`（血炼、婚姻审批）、`:137`（手动/一键招募） | 同步现况 |

### 6.2 有意保留（**不回改**，按 `report-G04.md:154` 纪律）
`docs/cpp-engine.md:10-23/46-55/204-205/223-227/237-245/310-378/388-456/480-541/615-635`；
`CODE_WIKI.md:103-106`（「已退役，编号禁复用…以 catalog 为准」批量注记）；
`docs/ui-read-surface.md:9` 与 `:120-135/:149-165/:227/:274-334`；`docs/architecture.md:480-544`
（以上为 dated 取证段或退役注记）。

### 6.3 判据
`node scripts/check-agent-instructions.mjs` = ✓ 全部通过，且**规则③「引用路径不精确」计数不得高于改前**（改前 0 处）；
引用一律写仓库根相对完整路径（`docs/AGENTS.md` 四条规范）。

---

## 7. 决策（D-1…D-6）

| # | 决策 | 依据 |
|---|---|---|
| **D-1** | **重录只做"期望值"层**：只改测试期望常量 ＋ 探针两处 digest；**禁止**改被测逻辑、消费序、播种式 | 「唯一窗口」一旦掺入逻辑改动就变成两次重定（§0 风险） |
| **D-2** | **不动 `DiffGachaPullTest` / `DiffGachaFragmentTest` 的黄金表**（它们绑 `GACHA(12)` + 固定种子，G02–G11 未动抽卡算法 ⇒ 应继续绿）；**若出现新红 ⇒ 不进重录，先按 A/B 定性查因** | §2-6：这两张表是硬编码黄金值，不是同源对拍 |
| **D-3** | `GameNotification.RecruitFailed`：**发布者为 0 ⇒ 本批删除变体 + 删 `GameOverlayHost.kt:466` 分支**（若删分支触发渲染覆盖守卫，同批补齐） | 死事件清算是 G10 的职责；保留会造成"永远不可能发生的 UI 分支" |
| **D-4** | `MailAttachment.extra` / `prisonerSpiritRootFilter` **字段本体不动**（涉及 Room ⇒ Migration 成本与风险）⇒ 只删零调用 UI 入口，字段登记 | `rules/database-migration.md`：改 Entity 必须迁移；本批无迁移预算 |
| **D-5** | `MIN_AGE` 与零调用色 helper：**删**（纯 Kotlin 常量/函数，无协议面） | 无迁移风险 |
| **D-6** | 文档改写**只碰"活文档"**（`architecture`/`knowledge-base`/`cpp-engine`/`CODE_WIKI`/`ui-read-surface`），过程档案（`docs/report-*`、`docs/parallel-batches*`）不回改 | `docs/AGENTS.md` 目录性质表 |

---

## 8. 文件面与切片（≤10 文件/片；**一律写仓库根相对完整路径**）

| 片 | 允许改 | 自检项 |
|---|---|---|
| **T-10a** 探针与金序列重录 | `android/app/src/main/cpp/gamecore/include/gamecore/determinism_probe.h`、`android/app/src/main/cpp/gamecore/test/disciple_factory_test.cpp`、`android/app/src/androidTest/java/com/xianxia/sect/NativeFpDeterminismTest.kt` | 两处 digest 同值；`kProbeVersion` 未动；`ctest -R Determinism` 两条 Passed |
| **T-10b** 死码清零（C++） | `.../gamecore/include/gamecore/system/ai_sect_ops.h`、`.../system/disciple_stats.h` | `rootCount = 1;` 三处归零；`ctest` 不因删无效语句变红 |
| **A-10c** 死码清零（Kotlin 主源） | `android/core/data/.../serialization/NullSafeProtoBuf.kt`、`.../Serializers.kt`、`android/core/domain/.../GameConfig.kt`、`android/core/domain/.../state/GameNotification.kt`、`android/core/domain/.../registry/BeastMaterialDatabase.kt`、`android/core/ui/.../theme/Color.kt`、`android/feature/game/.../GameViewModel.kt`、`android/feature/game/.../components/GameOverlayHost.kt` | 每符号按名 grep 零命中；`GameOverlayHost` 删分支后 `DialogTypeRenderCoverageTest` 仍绿 |
| **A-10d** 死码清零（Facade 链） | `android/core/engine/.../domain/disciple/DiscipleFacade.kt`、`.../DiscipleFacadeImpl.kt`、`.../disciple/DiscipleService.kt`、`.../disciple/DiscipleLifecycleManager.kt`、`android/core/engine/.../GameEngineDiscipleOps.kt` | `DiscipleCreationPathGuardTest` 与 `DiscipleServiceCrudTest` 同批改；`LEGACY_CRUD` 白名单同步 |
| **c10-a** 测试面同步 | `android/core/data/src/test/.../NullSafeProtoBufTest.kt`、`android/core/engine/src/test/.../DiscipleServiceCrudTest.kt`、`android/core/engine/src/test/.../nativebridge/DiffMonthSettlementFixture.kt`、`android/core/engine/src/test/.../architecture/RngSourceGuardTest.kt` | 上限下调后**退回旧态必须判红**（判别力自证） |
| **D-10e** 文档收口 | `docs/cpp-engine.md`、`docs/architecture.md`、`docs/knowledge-base.md`、`CODE_WIKI.md`、`docs/ui-read-surface.md`、`docs/rng-source-inventory.md` | §6.1 清单归零；§6.2 保留清单未被动 |
| **主线程** | 双 changelog、`report-G10.md`、全门禁、单次提交（或本批内多笔且报告写明） | §9 |

---

## 9. 门禁清单（终树同轮重跑；判据 = 命令自己的输出原文）

```powershell
# 0) 固证（§3.1）+ 树净
# 1) 桌面
$env:PATH = "C:\Users\cp050\llvm-mingw\llvm-mingw-20260616-ucrt-x86_64\bin;C:\Users\cp050\llvm-mingw\llvm-mingw-20260616-ucrt-x86_64\x86_64-w64-mingw32\bin;$env:LOCALAPPDATA\Android\Sdk\cmake\3.22.1\bin;$env:PATH"
#   工作目录 = <repo>\android\app\src\main\cpp\gamecore\build\desktop-test
cmake --build . ; ctest                       # 目标：全绿（0 失败）
ctest -R Determinism                          # 两条都 Passed
ctest -R SceneEquivalence                     # 13/13
# 2) JNI 重建（工作目录 = 仓库根）
pwsh -File scripts/build-desktop-jni.ps1      # 判据：mtime + 体积 + sha256 三件套
# 3) Kotlin 组合门（工作目录 = android）
.\gradlew.bat compileReleaseKotlin testReleaseUnitTest --max-workers=1 --rerun-tasks --continue `
  "-Dgamecore.jni.path=<repo>\android\core\engine\build\desktop-jni\libgamecorejni.so" detekt lintRelease --console=plain
# 4) node 四门 + 退役集（工作目录 = 仓库根）
node scripts/gen-action-ids.mjs ; node scripts/gen-game-data.mjs --check
node scripts/check-jni-count.mjs ; node scripts/check-agent-instructions.mjs
```

**提交前**：`git status` 只留本批改动；构建副产物（`atlas-rgba-manifest.json`、`sprite-uid-map.json`）`git checkout --` 还原；
双 changelog 用**编辑工具**改（禁脚本重排整文件），改完 `node -e "JSON.parse(...)"` 校验。

---

## 10. 登记 / 待拍板

1. 🟠 `prisonerSpiritRootFilter` / `MailAttachment.extra` 字段删除（需 Room 迁移）—— 交后续清理批或产品拍板。
2. 🟠 `predefinedCodes` 置空后的运营下发通道（G12/商业化口径）。
3. 🟠 🔴 **测试基建债（G11 报告 §8-4 新发现）**：`android/core/engine/src/test/.../FakeGameStateStore.gameData` 是「每次属性访问新建一次性 `MutableStateFlow`」的**断线桩** ⇒ 用它测任何派生流刷新**必然假绿**；`FakeAtomicStateStore` 缺生产的 `!==` 提交守卫。**本批要求：至少给这两个替身加显式注释 + 在 `rules/testing.md` 登记**，收敛为生产语义替身可拆到精修批。
4. 🟠 旋转式流光（`Brush.sweepGradient` 的 `colorStops` 逐帧采样）与 `ItemCard`/`GachaRewardCell` 的共享"按 resId 画注册精灵"composable —— 精修批。
5. 🟠 旧档 `GarrisonSlot.discipleSpiritRootColor` 里持久化的旧色串要等该槽下次写入才刷新（显示串，不入 RNG 面）—— 观察项。

---

## 11. 一句话给执行者

**G10 只做四件事：把三条金黄红一次性重录到位（探针 digest **两处同改** ＋ 金序列期望值 ＋ **`kProbeVersion` 与 `advancePhaseBaseline` 绝不动**）、全量回归跑绿、
把 §5 的死代码按"按名 grep 零命中"清零并**同批把 `RngSourceGuardTest` 的上限下调到实值**（否则守卫一路假绿）、把 §6.1 的活文档段落改到只描述当前状态；
🔴 绝不动 `gacha_tx.h`、`seed + 12` 播种式与 `DiscipleFactory` 的随机消费序——那会把唯一的基线重录窗口污染成两次。**
