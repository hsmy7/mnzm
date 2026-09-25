# M1 已完成工作 · 独立验收报告（G02/G05/G06/G03/G04/G15/G16/G08 共 8 批）

> **验收对象**：`HANDOVER-m1-remaining-3.md` §1.1/§2.2 的 8 批声称（G02、G05、G06、G03、G04、G15、G16、G08）＋
> 各自 `report-Gxx.md`。**验收方式**：本会话在**当前树**上独立复跑门禁与静态对账，
> **不采信任何报告自述**（G04 的教训：实施会话把三项记为绿而实测四处失实，见 `HANDOVER-3` §1.1 ②）。
> **验收时点**：2026-09-25，HEAD `6c5e0daa1`（G08 收官回写），分支 `feat/gacha-m0-m1`，远端未推送。
> **验收边界（如实声明）**：只读复跑 + 静态对账；**未做**真机验证、未构建 APK、未跑 `assembleRelease`、
> 未验证后端兑换码下发链路（这三项与「`pending-device` 批」同批，历史报告亦已自述未做）。

---

## 1. 验收结论（先说结果）

| # | 结论 | 证据位置 |
|---|---|---|
| 1 | ✅ **8 批的门禁数字全部可复现**，与 `HANDOVER-3` §2.2 逐值吻合（见 §3） | §3 对账表 |
| 2 | ✅ **8 个提交全部在库**且规模与各批报告一致（`--shortstat` 逐笔核对） | §4 |
| 3 | ✅ **7 批（G16 无删除清单）的删除面在当前生产代码面归零成立**；命中项全部落在合法残留分区（历史迁移 / proto `reserved` / 退役 ActionId 标注 / 报告保留清单） | §6 |
| 4 | ❌ **2 条未归零残留**（均属 G02 删除面的连带台账，非生产代码）：`baseline-prof/baseline.prof:93` 仍列已删类 `LawEnforcementProcessor`；`scripts/split_engine/budget.py:23` ＋ `specs/law_enforcement.json:5` 台账仍指向该已删文件 | §6.1 |
| 5 | ⚠️ **3 类注释面违规残留**（按 `rules/code-comment.md` 属历史性表述，只登记不修）：G03 1 处、G04 2 类（其中「血炼」实际约 25 处生产注释，report-G04 §八-10 只登记 6 处） | §6.2 |
| 6 | ⚠️ **4 处报告自述与实测偏差**（计数/范围口径，均不改变批次结论） | §6.3 |
| 7 | ⚠️ **一处门禁复跑的环境降级已在 §7.2 明写**（JNI `.so` 未在本会话重建，判据见该节） | §7.2 |
| 8 | ✅ **Kotlin 侧四门全部复现**：组合门 `BUILD SUCCESSFUL in 26m 20s`（339 任务全执行）、JUnit 7391/0/0/18 逐模块一致、detekt 六模块 0 error、lint 36 warnings / 0 errors | §3 |

**总判**：8 批的「已完成」声称**成立**——门禁可复现、删除面在生产面归零、新增项在位、纪律（单次提交 / 双 changelog / 报告）齐备。
未归零与本报告发现的偏差**均不影响任何一批的结论**，但构成 G10 的收口清单（§8）。

---

## 2. 验收方法与判据

| 项 | 做法 |
|---|---|
| 独立性 | 全部命令在**当前树**上重跑，对照物是 `HANDOVER-3` §2.2 的声称值；报告只用来**取声称**，不用来当证据 |
| 判据来源 | ① 命令自己的输出原文（不看复合命令退出码——G03 坑 5）；② 产物 mtime/体积（G15 坑 11）；③ `git` 层面的可复现性（提交链、生成物幂等） |
| 门禁复跑 | node 4 门 ／ 桌面 C++ 重建 ＋ `ctest`（含 `-R SceneEquivalence`、`-R Determinism` 两个定性子集）／ JNI ＋ Gradle 组合门（`compileReleaseKotlin ＋ testReleaseUnitTest ＋ detekt ＋ lintRelease`，`--rerun-tasks`） |
| 静态对账 | 提交链与 `--shortstat`；ActionId 三面（catalog / 生成物 / 退役集）；Room 版本与列数；图集不变量；精灵四方一致性；四本抽卡账本的三端环 |
| 删除面复核 | 只读子代理按批抽取报告清单后在排除区（`.git`/`node_modules`/`build`/`docs/design`/`docs/research`）外全仓 ripgrep，主线程对 5 条关键结论逐条直读抽验 |
| 串行纪律 | 同机只跑一路 Gradle 组合门（G03 坑 8）；C++ 构建与 Gradle 分目录、不互锁 |

---

## 3. 门禁对账表（声称 vs 本会话实测）

| 门 | `HANDOVER-3` §2.2 声称（G08 终树） | 本会话实测 | 判定 |
|---|---|---|---|
| 桌面 C++ 构建 | `[9/9] Linking …`（G08 新增 `gacha_fragment.h`/`dispatch_gacha.cpp`） | `[36/36] Linking CXX executable test\game-core-tests.exe`（48 warnings，全预存） | ✅ 可构建 |
| 桌面 `ctest` | **1401 总 / 1398 过 / 3 败** | **1401 / 1398 / 3**，`Total Test time (real) = 83.94 sec` | ✅ 逐值一致 |
| 3 条失败集 | `DiscipleFactory.GoldenSequenceSeed42`／`…Seed987654321Female`／`DeterminismProbeTest.DigestMatchesGoldenBaseline` | **逐条同名**：808 / 809 / 1042 | ✅ 零新增 B 类 |
| 失败定性（A/B 判据） | 断言原文为金序列常量 ⇒ B 类 | 实测断言原文：`Which is: "male_disciple_15"` / `56`、`"female_disciple_9"` / `72`、`kGoldenDigest 5264300892275943713 vs 13038983672811222423` ＋ `FP determinism transcript diverged … actual=0xb4f3c6912207f597 golden=0x490e8dc522e12921` | ✅ **B 类**（非协议漂移） |
| `DeterminismProbe.actual` | `0xb4f3c6912207f597`（G15 起未变） | `actual=0xb4f3c6912207f597` | ✅ 逐字符一致 |
| `DigestIsStableAcrossRepeatedRuns` | Passed | **Passed**（`ctest -R Determinism` 2 例：1 红 1 绿） | ✅ 确定性未坏 |
| `SceneEquivalenceTest` | 13/13 全绿 | **13/13 全绿**（`ctest -R SceneEquivalence`，0.50 s） | ✅ |
| ActionId | 199 动作 / maxId=1870；regen 幂等 | `gen-action-ids: 199 actions (maxId=1870)`；两份产物 SHA256 **regen 前后不变（IDEMPOTENT=yes）**；`git diff --exit-code` **EXIT=0** | ✅ 零漂移 |
| catalog ↔ guard 退役集 | catalog 24 == guard 24 双向零差集（G15 22） | catalog 24 条 `已退役` 条目（另 2 行为注释）== `dispatch_guard_test.cpp` 的 `retired` 块 **24 项**，逐名对上 | ✅ 双向零差 |
| `game-data.json` | sha256 `035066cb…94ef` | `校验通过：… sha256 035066cbcb891aa124397667e58879d51d4dd4124177ae38b550e1b2c22094ef` | ✅ 逐字符一致 |
| JNI 面计数 | 86 / 86 | `✓ JNI 面计数在基线内：total=86/86，双桥无扩散` | ✅ |
| 规范分发门禁 | EXIT=0；预存告警 2 条（规则③ 1 处、规则⑤ 37890 字节） | `✓ 规范分发架构门禁全部通过`；规则③ **1 处**（`rules/static-resources.md:293` 裸文件名）；规则⑤ 最坏链路 **37890 字节** | ✅ 数量未增长 |

> **时点说明（诚实标注）**：上表「规范分发门禁」一行是**验收时点（HEAD `6c5e0daa1`）**的实测值。
> 验收之后，本仓库被**另一个并发会话**追加了两笔**纯文档**提交（`a626bc42e` + `b052eeac5`：
> 根 `AGENTS.md` 压回 32768 字节预算、细节下沉 `rules/build-quality.md` / `rules/code-quality.md`、
> 并修掉 `rules/static-resources.md:293` 的裸文件名引用）。因此**现在**再跑该门禁会看到
> `444 条内部引用全部可解析`、**0 条告警**、最坏链路 **32180 字节**——这是那次文档重构的结果，不是本批改动。
> **对验收结论的影响：无。** `git diff --name-only 6c5e0daa1..b052eeac5 -- android/ scripts/` = **空** ⇒
> 两笔提交**零代码/零配置/零生成物**改动，本报告 §3 的代码面复跑值对当前 HEAD 依然成立。| Room | `DATABASE_VERSION = 59`；`disciples` 90 列、`game_data` 128 列；索引 5+5 | `59`；`59.json`：`disciples` **90 列 / 5 索引**、`game_data` **128 列 / 5 索引**；schema JSON 54–59 存在（无历史改写） | ✅ |
| 图集不变量 | `sprites=41 / ASTC_4x4_LDR / layoutHash=6a122ed22d66bee5` | `sprites=41 format=ASTC_4x4_LDR layoutHash=6a122ed22d66bee5` | ✅ |
| 精灵分类数 | 15（含 `CHARACTER`） | **15** 类，含 `CHARACTER` | ✅ |
| G16 十二键四方齐备 | registry / 映射 / 双模块 WebP / UID 493–504 | 12 键在 `resource-registry.json` ✓、`sprite-uid-map.json` ✓、`:app/src/main/res/drawable-nodpi` **6+6** ✓、`:feature/game/…/drawable-nodpi` **6+6** ✓；UID **493–504** 逐键对上 | ✅（🔴 修正：这里的「双模块」是 `:app` + `:feature:game`，**不是** `:core:ui`） |
| 抽卡四账本三端环 | G01 已落，G08 起有写者 | `models.h:1317-1320` → `json_codec.cpp:1247-1248 / 1333-1334` → `GameData.kt` `@ProtoNumber(164..167)` → `59.json` 四列 → 镜像补丁 `GameDataFieldPatch.kt:327-337`，**环完整**；`GachaHistoryEntry` 双端 8 字段同名（Kotlin `1..8`） | ✅ |
| Kotlin 编译 | 含在组合门内 `BUILD SUCCESSFUL in 26m39s` | 组合门 `BUILD SUCCESSFUL in **26m 20s**`；`339 actionable tasks: 339 executed`（`--rerun-tasks` 全量重执行） | ✅ |
| 全量 JUnit | **7391 / 0 / 0 / 18 skip**（app 1010 ／ domain 1571 ／ data 814 ／ engine 2903 ／ ui 146 ／ feature:game 947；**683** 个 XML） | **7391 / 0 fail / 0 err / 18 skip**，逐模块 **app 1010（skip 2）／domain 1571／data 814（skip 15）／engine 2903（skip 1）／ui 146／feature:game 947**，XML **683** | ✅ **逐模块逐值一致** |
| detekt | `BUILD SUCCESSFUL`；六份 `detekt.xml` 的 `<error>` 计数全 0；两 baseline 零改动 | 六模块 `detekt.xml` 各 **79 字节**（`<checkstyle>` 空体）⇒ `<error>` **0**、`<warning>` **0**；两 baseline `git status` 零命中 | ✅ |
| lint | `Lint found 36 warnings (and 3 warnings filtered by baseline lint-baseline.xml)`、**0 errors** | 组合门输出原文：`Lint found 36 warnings (and 3 warnings filtered by baseline lint-baseline.xml)`；`BUILD SUCCESSFUL`（lint 有 error 会中断构建）⇒ **0 errors**；36 与 G16/G15 同值 ⇒ 全预存 | ✅ |

---

## 4. 提交链条与规模对账

| 批次 | commit | `--shortstat` 实测 | 报告声称 | 判定 |
|---|---|---|---|---|
| G00 ＋ G01 | `89281bcc9` | 45 文件 +6262/−110 | —（合并提交） | ✅ |
| G07 | `19dffb6d2` | 38 文件 +715/−957 | — | ✅ |
| 执行协议 | `d38e07099` | docs | — | ✅ |
| 落点侦察 | `112e2cbd7` | docs | — | ✅ |
| 第 1 份交接 | `56a18019d` | docs | — | ✅ |
| **G02** | `5dbaac1e3` | **443 文件** +10775/−26845 | 443 文件 +10775/−26845 | ✅ 逐值一致 |
| **G05** | `8e593a71b` | **143 文件** +5624/−8401 | 143 文件 +5624/−8401 | ✅ |
| **G06** | `e1a69d8e9` | **42 文件** +179/−1309 | 42 文件 +179/−1309 | ✅ |
| **G03** | `cc66d7918` | **171 文件** +6084/−5186 | 171 文件 +6084/−5186 | ✅ |
| **G04** | `4078f12c9` | **364 文件** +6448/−24737 | 364 文件 +6448/−24737 | ✅ |
| **G15** | `325da9d5a` | **122 文件** +6092/−3893 | 122 文件（117 跟改 + 5 新增）+426/−3850 **跟改侧** | ⚠️ 见下注 |
| **G16** | `4ba6c1bdd` | **42 文件** +1195/−62 | 42 文件（14 改 + 28 新增）+1195/−62 | ✅ |
| **G08** | `8b3c10578` | **89 文件** +5386/−1038 | 89 文件（72 改 + 17 新增）+5386/−1038 | ✅ |

> ⚠️ **G15 的 +6092 与报告的 +426 不是同一口径**：报告的 `+426/−3850` 明确写「**跟改侧**」（整删 9 个文件的行数不计入），
> 而 `--shortstat` 含新增文件（V59 迁移、`59.json`、`RoomMigrationV58To59Test`、TASKBOOK、report）。**不是失实，是口径差**；已登记为报告可读性改进项。

---

## 5. 静态对账（关键声称的直读抽验）

| 声称 | 实测证据 | 判定 |
|---|---|---|
| G04：`talents`/`physiques`/`affixes` 三键**整个消失** | `game-data.json` 中 `"talents"`/`"physiques"`/`"affixes"`/`"bloodPool"`/`"bloodRefinement"` 命中 **0** | ✅ |
| G08：`templateId` 自 v54 在库 ⇒ 零迁移 | `59.json` 有 `disciples.templateId`（`notNull=true`）；`DATABASE_VERSION=59`；迁移文件 V50–V59 齐备，**无 V60** | ✅ 上位「→v60」确为失真 |
| G08：碎片入账单点 | `gacha_fragment.h:63-97` `addFragment` 是唯一 C++ 写者；`GachaFragmentLedger` 为 Kotlin 逐字同式臂；ActionId `GACHA_FRAGMENT_GRANT_TX=1870` | ✅ |
| G08：开局＝周明 ＋ 5 万 ＋ star=1 | `db.gachaPools`(1) / `db.characterTemplates`(6) 配置在位；`GameConfig.Gacha`（`GameConfig.kt:220-239`）常量 `HISTORY_RING_SIZE=50`/`FRAGMENTS_PER_STAR=100`/`MAX_STAR=5`/`PITY_PULL_THRESHOLD=10`/`PITY_FRAGMENT_COUNT=5`/`PRICE_PER_PULL=5000`/`STAR_BATTLE_PCT_PER_STAR=0.08`/`STAR_CULT_PCT_PER_STAR=0.05` | ✅（G09 可直接消费） |
| G16：素材管线硬依赖源目录 | `android/scripts/art-source.mjs` 经 `MNZM_ART_SOURCE` 解析；`模拟宗门美术素材/` 与 `模拟宗门音乐音效/` 已入 `.gitignore` | ✅ |
| 工作树干净 | `git status --porcelain` = 唯一非本批项 = `docs/research/*.md`×2 ＋ 构建副产物 `atlas-rgba-manifest.json`（本会话 Gradle 组合门写脏，收尾还原） | ✅（收尾处理） |

---

## 6. 删除面归零复核（8 批）

方法：按批从各 `report-Gxx.md` 抽取「删除模式 grep 清零」清单 → 排除 `build/`、`.git`、`node_modules`、
`docs/design/gacha-batches/**`、`docs/research/**` 后全仓搜索 → 命中项逐个归类。
**合法残留分区**＝历史 Room 迁移与 `schemas/*.json` 的旧列名（迁移链不可改写）／`@ProtoNumber` `reserved` 注释／
退役 ActionId 的 catalog 条目与 `dispatch_guard_test` 的 `retired` 集／报告明写的保留清单。

| 批次 | 删除面规模 | 生产面（`src/main` ＋ `gamecore`） | 结论 |
|---|---|---|---|
| G02 | 26 个符号（执法/盗案/老化/寿元/神魂/驻守） | 主清单 **全 0**；`loyalty*/soulPower/lifespan*/annualTheftCount/warehouseGarrison*` 仅落迁移与 `reserved` | ✅ |
| G05 | 15 个模式（招募链/自动招募/年刷新/净化） | **全 0**（裸 `RecruitService` 22 命中全为 `MerchantAndRecruitService` 子串） | ✅ |
| G06 | 17 个模式（逐出/改名/事件） | **全 0**；`annualDesertedDisciples` 写入点归零（`++/+=1` 命中 0），字段与年报保留（双保铁律） | ✅ |
| G03 | 33 个模式（生育/道侣/亲缘/赠礼） | **全 0**；7 列 ＋ `daoCompanion` 仅落迁移/`reserved`；`MARRY_*` 落退役 ActionId | ✅ |
| G04 | 24 个模式（洗炼/资质/三表/血炼/职位特质/战斗随机成长） | **全 0**；`talentIds/physiqueIds/affixIds/aptitude` 仅落 V46/V58 与 `reserved`；`bloodRefine` 36 命中全在迁移链 | ✅ |
| G15 | 17 个模式（师徒全链/赠礼/关系面板） | **全 0**；`masterId` 生产命中 **27** 处全部为迁移 KDoc、`reserved`、`game_view.proto` 退役位；报告原模式重放得 **n=3 且位置逐处吻合** | ✅（含 1 处计数偏差，见 §6.3） |
| G16 | —（纯新增素材批，报告无删除清单） | — | ⚠️ **无法对账**（如实标注，非缺陷） |
| G08 | 11 个模式（直造弟子三臂/兑换码弟子类型/开局三真相源） | **全 0**（裸 `generateDisciple`=3、`isDisciple`=42 均为子串） | ✅ |

### 6.1 ❌ 未归零（2 条，均为 G02 删除面的连带台账，不属生产代码）

| # | 位置 | 内容 | 影响评估 |
|---|---|---|---|
| 1 | `android/app/src/main/baseline-prof/baseline.prof:93` | `HSPLcom.xianxia.sect.core.service.LawEnforcementProcessor`——已删类仍在**已提交**的 Baseline Profile 里 | 运行期无害（ART 跳过不存在类），但属陈旧基线；**且无守卫**（没有「baseline profile 引用的类必须存在」的测试）⇒ 建议 G10 连带收口 ＋ 评估补一条守卫 |
| 2 | `scripts/split_engine/budget.py:23`、`scripts/split_engine/specs/law_enforcement.json:5` | 拆分台账仍把 `LawEnforcementProcessor.kt` 列为 33 行/拆出源 | 脚本再跑会按不存在的文件对账 ⇒ 台账失真；G02 报告未登记 |

> 两条均为**主线程直读复核**（非仅子代理自述）：`baseline.prof` 该行、`budget.py` 该行、`specs` 该行均已逐字确认。

### 6.2 ⚠️ 注释面违规残留（按 `rules/code-comment.md`「只描述当前状态」属违规，本次只登记）

| # | 位置 | 内容 | 已登记？ |
|---|---|---|---|
| 1 | `android/core/engine/.../GameEngineResidualNativeOps.kt:30`（⚠️ 路径子代理给出） | 「道侣提议清理等」历史性表述 | ❌ 未登记 |
| 2 | `gamecore/system/redeem_code.h:30`（主线程直读） | 头注释仍列「体质/词条/天赋数据库（`PhysiqueDatabase/AffixDatabase/TalentDatabase`）」——三类已由 G04 整删 | ❌（report-G04 §八-10 未列） |
| 3 | G04「血炼」生产代码注释 | 全仓「血炼」命中 **49**（其中约 25 处在生产源注释：`DiscipleStatusData.kt:7`、`HpMpRecoveryService.kt:58-61`、`GameStateStoreImpl.kt:87/910`、`CombatService.kt:202`、`MonthSettlementResidualExecutor.kt:20`、`YearSettlementResidualExecutor.kt:20`、`year_settlement.h:94`、`sect_conquest.h:83`、`game_core.cpp:171` 等） | ⚠️ report-G04 §八-10 只登记 **6 处** |
| 4 | 大宗历史表述 | `CHANGELOG.md`、`assets/changelog_entries.json`、`docs/**`、根 `progress.md`/`task_plan.md`/`findings.md` | 发布的变更记录与过程档案，按 `docs/AGENTS.md` **不回改**（合法） |
| 5 | JNI「退休桩」 | `GameCoreJni.cpp:686-691` 的 `"retired op: lifespan*"` 等 | 有意防御桩，**不计违规**，登记备查 |

### 6.3 ⚠️ 报告自述与实测偏差（4 处，均不改批次结论）

| 批次 | 报告自述 | 实测 | 性质 |
|---|---|---|---|
| G06 | `eraseDiscipleDerivedMaps` 生产消费方 = 0 | 生产调用**存在**：`android/core/engine/src/main/java/com/xianxia/sect/core/engine/service/DiscipleLifecycleProcessor.kt:124`（函数本体在 `.../domain/disciple/DiscipleDerivedMapsCleanup.kt:14`）——**主线程直读复核** | 自述失实 |
| G02 | 「§验证 4：G02-7 生产面 grep 命中 = 0」 | 字面不成立：`isOutsideSect` 17、`processSectDisciplesAging` 3、`processReflectionRelease` 2 均在生产代码 | 属「**限定范围内归零**」——三项在报告 §未完成/§一-21 已分别登记为后续收口 |
| G15 | `masterId` 残留 25 处 | **27** 处 | 计数偏差（不影响结论） |
| G04 | 「血炼」注释面残留 6 处 | 49（约 25 处在生产注释） | 少登记约 19 处 |

---

## 7. 验收的降级、未验面与不确定性（诚实清单）

### 7.1 明确未验（与历史报告口径一致，非本次遗漏）
- **真机验证**：无视觉证据（G08 的「周明立绘在弟子卡上画出来」、G16 的「十连结果页真显示头像」都只有静态证据链）。
- **APK 包体**：未跑 `assembleRelease`；G16 的「+4.08 MB」是按单模块产物字节求和，不是 APK 实测。
- **后端兑换码下发**：需服务端配合，未验。

### 7.2 本次复跑的两处降级
1. 🔴 **JNI `.so` 未重建**：`scripts/build-desktop-jni.ps1` 应于**仓库根**执行，本会话误在 `android/` 下调用 ⇒ 脚本未运行（**产物 mtime/体积未变**：`8542208` 字节、`2026-09-25 20:57:34`，正是 G08 记录值；G15 坑 11「验产物 mtime/体积」当场拦住了这个静默失败）。
   **未重跑的理由**：Gradle 组合门正在读同一 `.so`，重建会造成竞态（Windows 文件锁）。
   **等价性判据**：当前树 `gamecore/**` 对 HEAD 零改动（`git status` 中 `gamecore/` 命中 0），`.so` 字节与 mtime 均等于 G08 收官值 ⇒ 组合门用的就是与本树 C++ 源码对应的产物。**旁证（本轮实测）**：组合门以该 `.so` 跑完 7391 例，其中跨语言对拍 `Diff*` 族 **51 个类 / 265 例 / 0 失败 / 0 跳过**（含 `DiffGachaFragmentTest` 6/6）——若 `.so` 与源码不匹配，Diff 族会整片判红（G08 已实证：漏 `dispatch_gacha.cpp` 会让桌面对拍库 undefined symbol）。
2. `ctest` 总耗时 83.94 s（声称 58.64 s）——机器负载差异，不影响用例集合与通过集。

### 7.3 不确定性
- `report-G15.md` 自述「`GameViewDiscipleProjectionTest` 双射守卫的判别力自证**未做**」——本会话亦**未补做**（属判别力抽验，不是门禁红）。建议 G10 或独立复核批补。
- `report-G16.md` 自述未重跑的两门（`catalog↔guard` 退役集扫描、`cmake --build`）——本会话**已补跑**：退役集 24==24 ✅、桌面构建 `[36/36]` ✅。
- 子代理给出的个别行号未经主线程直读（已在本报告内逐处标 ⚠️），`§6.3` 第 1 条即为受影响者。

---

## 8. 验收判据与后续建议

### 8.1 判定标准（建议固化为后续批次的验收基线）
**「批次已完成」的判据 = 复核者在同一棵树上同轮跑出全部门禁绿（或红集 ⊆ B 类登记集）＋ 产物指纹（`.so` 体积、生成物幂等、图集 `layoutHash`、`game-data` sha256）＋ 单次提交 ＋ 双 changelog ＋ 报告。**
实施会话的自述、`BUILD SUCCESSFUL` 单行、复合命令退出码，**均不构成证据**（G04 四处失实 / G15 坑 5 / G15 坑 11 三次实证）。

### 8.2 交 G10 的收口项（在既有八份登记之外新增 4 条）
1. 🔴 删除 `baseline-prof/baseline.prof:93` 的 `LawEnforcementProcessor` 行，并评估补「baseline profile 类存在性」守卫。
2. 🔴 清理 `scripts/split_engine/budget.py:23` 与 `specs/law_enforcement.json:5` 的已删文件台账。
3. ⚠️ 注释面七项检查：G03 1 处（`GameEngineResidualNativeOps.kt:30`）、G04 `redeem_code.h:30` 三表名、「血炼」约 25 处生产注释。
4. ⚠️ 复核 `eraseDiscipleDerivedMaps` 的生产消费方（report-G06 §七-2 自述为 0，实测 `DiscipleLifecycleProcessor.kt:124` 有调用，已主线程直读确认）——需判定该 helper 是「逐出专用」还是仍服务其它路径（G02 报告曾登记 `clearAllDiscipleSlotsForRemoval` 零调用方）。

### 8.3 给 M1 剩余三批的输入
本验收同时产出了剩余工作的实施文档：**[`TASKBOOK-G09.md`](TASKBOOK-G09.md)**（G09 派出级任务书 ＋ §0 的 G09→G11→G10 总纲）
与 **[`recon-G09.md`](recon-G09.md)**（本轮 G09 实测侦察原始记录）。
其中 G09 的关键前置（`db.gachaPools` 需注入 C++）、三条上位失真作废（`TASKBOOK-G08` D-16 等）与两条运行期守卫缺口，均在本验收过程中直接发现。
