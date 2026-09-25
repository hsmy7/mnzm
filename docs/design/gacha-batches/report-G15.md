# report-G15 · 师徒系统整体下线

> 批次：G15　分支：`feat/gacha-m0-m1`　开工基线：HEAD `a055bf9fe`（工作树非未跟踪残留 0）
> 派工细则唯一真源：[`TASKBOOK-G15.md`](TASKBOOK-G15.md)　上位交接：[`HANDOVER-m1-remaining-3.md`](HANDOVER-m1-remaining-3.md) §10
> 产品依据：P-1 拍板（2026-09-24）「不只是删『关系』对话框 UI，而是连根拆掉整个师徒玩法」
> 执行协议：[`EXECUTION-PROTOCOL.md`](EXECUTION-PROTOCOL.md)
> **本报告全部数字为同轮命令实测输出**（HANDOVER-3 §2.3 坑 9：「报告声称绿」不作证据）。

---

## 一、做了什么（分类表）

### ① 删除 / 只读化（主体）

| 面 | 处置 | 规模与证据 |
|---|---|---|
| 关系列三端链 | `masterId` / `masterIds` / `social_masterId` / proto `93` / 镜像 `85` 全链删 | 15 环逐一核对，见 TASKBOOK §2.3 |
| `SocialData` 组件 | **整类拆除**（不留空数据类）+ `@Embedded(prefix="social_")` + `AssembleGroup.SOCIAL` 组 + `assembleSocial` + `writeSocialFields`（含唯一调用点） | 组装位图按 `ordinal`、纯进程内不落盘 ⇒ 枚举项删除安全（实测全仓 `AssembleGroup` 仅 3 处引用点） |
| 拜师事务 | ActionId **1591 保号退役**（catalog 留条目 + desc 标废弃）+ dispatch case 删 + 区间起点 `1591`→`1594` | retired 集 **21→22**；`disciple_lifecycle_tx.h` **207→51 行** |
| 师徒双乘区 | `masterDiscipleBonus` **形参整链拆除**（`DiscipleStatsProvider` 4 方法 + 三个数据类字段 + 三常数 + 三扩展函数 + C++ 两 input struct + 两处聚合式 + JNI 对拍端口） | 见 §三 确定性红线 |
| 赠礼系统 | `relative_gift.h`（384）+ `RelativeGiftHandler.kt`（309）+ `GiftRelationshipType.kt`（9）**三文件整删** + `relative_gift_test.cpp`（393）整删 + **两个**消费方接线摘除 | 🔴 消费方含 TASKBOOK §2.1 抓出的 `battle_residual_tx.h`（上位交接只登记了 `phase_settlement.h`） |
| UI | `MasterApprenticeSelectDialog.kt`（154）+ `DetailActionButtons.kt`（127）**整删**、「关系」/「拜师」两按钮与两处对话框态、`discipleMasterBonus()`、「师徒加成」明细行、`DiscipleDelegate.apprenticeToMaster` | 实测**无 `DialogType` 注册面**（内联 `if (state.show…)` 形态） |
| 死亡解绑 / 日志重建 | `unbindMasterColumns`、`initializeLifeEvents` 的「拜X为师」段 | `DiscipleLifecycleProcessor` 顺带删失去唯一引用者的 `MutableGameState` import |
| 死配置 | `GameConfigData.RelativeGiftSection`（全仓零消费者 + `game_config.json` 无该键，实测双证） | 属预存死配置，本批一并清 |
| 系统注册 | `GameSystemRegistryDefaults` 去 `RelativeGiftHandler` 行 | 守 `GameSystemRegistryCoverageTest` 一一对应契约 |

### ② 命令进 C++ / 回执出 C++

无新增通道。本批为**纯删除**：C++ 侧 `apprenticeTransaction` 与其 dispatch case 消失，Kotlin 侧 `tryNativeApprenticeToMaster` native 臂与回退臂**成对**拆除（未出现单侧删除 ⇒ 无 A 类协议漂移，见 §三）。

### ③ 更名（消歧，非功能变更）

- `GameViewDiscipleRows.fillEquipmentSocialRowFields` → `fillEquipmentRowFields`（社交段已拆，名字不得再指它）
- `CultivationRateCalculator.preachingMastersBonusColumn` 形参 `masterIds` → `preachingMasterIds`
  （它是讲道师父槽位、与师徒无关，同名易误读为已删列）

### 保留面（未动，实测双向贴证见 §四）

`viceSectMaster`（副宗主）/ `preachingMasters`+`qingyunPreachingMasters`+`MASTER_TEACHING_*`+`mastersBonus`（讲道）
/ `manualMasteries`+`MasteryLevel`（功法精通）/ `comprehension` 全链（口径 15）/ `GiftService`+`diplomacy_tx`（外交赠礼）
/ `FAVOR_GIFT` 1501 / 好感与 `DiscipleChatDialog` / `LifeEventDraft`+`lifeEvents` / `disciples` 5 个 `Index`
/ 全部历史 Migration 与历史 schema JSON。

---

## 二、门禁（终树同轮实测）

| 门 | 命令 | 实测值 | 判定 |
|---|---|---|---|
| 桌面 C++ 编译 | `cmake --build .` | `[5/5] Linking CXX executable test\game-core-tests.exe`，EXIT=0 | ✅ |
| 桌面 ctest | `ctest` | **1394 总 / 1391 过 / 3 败**；败 = `DiscipleFactory.GoldenSequenceSeed42` / `…Seed987654321Female` / `DeterminismProbeTest.DigestMatchesGoldenBaseline` | ✅ 失败集 ⊆ B 类登记集，**零新增 B 类** |
| 三条红定性 | `ctest -R … --output-on-failure` + `LastTest.log` | `disciple_factory_test.cpp:42/51/52/53/54` 断言固定姓名与属性常量；`determinism_probe_test.cpp:20` 断言 `kGoldenDigest`（`actual=0xb4f3c6912207f597 golden=0x490e8dc522e12921`） | ✅ **B 类**（金序列/摘要），非 A 类 |
| ctest 复跑 | 注释修订后重编重测 | 同样 **1394/1391/3 同三条** | ✅ 可复现 |
| Kotlin 编译 | `compileReleaseKotlin` | `BUILD SUCCESSFUL in 1m 57s` | ✅ |
| 测试源编译 | 五模块 + `:app` `compileReleaseUnitTestKotlin --max-workers=1 --continue` | `BUILD SUCCESSFUL`，**0 错误** | ✅ |
| 跨语言对拍库 | `pwsh -File scripts/build-desktop-jni.ps1` | EXIT=0，`.so` 8526336 B（旧 8560640 B → 确认真重编） | ✅ |
| JUnit 全量 | `testReleaseUnitTest --max-workers=1 --rerun-tasks --continue -Dgamecore.jni.path=…`，**按 XML 逐模块汇总** | **7336 总 / 0 败 / 0 错 / 18 skip**<br>app 995(2skip) / domain 1575 / data 806(15skip) / engine 2866(1skip) / ui 146 / feature:game 948 | ✅ |
| detekt | 六模块 | `BUILD SUCCESSFUL`；`detekt-baseline.xml` numstat **空**（只缩不增，本批未变） | ✅ |
| lint | `lintRelease` | `BUILD SUCCESSFUL in 6m 3s`；`Lint found 36 warnings`（本批文件零命中，全为预存量）；两 baseline 零改动 | ✅ 详见 §七 |
| JNI 计数 | `check-jni-count.mjs` | **total=86/86，双桥无扩散** | ✅ |
| ActionId | `gen-action-ids.mjs` + 工作树自比 | **198 动作 / maxId=1861**；双产物 diff 零漂移（幂等） | ✅ |
| catalog↔guard 退役集 | 脚本扫 6 个 catalog 文件 + `dispatch_guard_test.cpp` | catalog 解析 198 = 生成器 198；标退役 **22** == guard **22**；两个方向差集均 `[]` | ✅ 双向一致 |
| 游戏数据 | `gen-game-data.mjs --check` | `校验通过`，sha256 `035066cbcb891aa124397667e58879d51d4dd4124177ae38b550e1b2c22094ef`（**与 G04 基线逐字符相同** ⇒ 本批未触碰配置源） | ✅ |
| 规范门禁 | `check-agent-instructions.mjs` | `✓ 规范分发架构门禁全部通过`；预存告警 2 条（规则③ basename 不精确 1 处、规则⑤ 最坏链路 37890 B）——与 HANDOVER-3 §2.2 登记的「预存告警 2 条属正常」一致 | ✅ |
| Room | `DATABASE_VERSION` / `59.json` / `disciples` 列数 | **59**；`59.json` 新增；`disciples` **90 列**、`game_data` 128 列；索引 5+5；`social_masterId` 键不存在 | ✅ |
| KSP 历史 schema 零改写 | `git status --porcelain -- schemas/` | 仅 `?? 59.json`，历史 `54…58.json` 无 `M` | ✅ 坑 1 达成 |
| 构建副产物还原 | `git status --porcelain \| grep manifest` | `atlas-rgba-manifest.json` 曾被构建改脏 → `git checkout --` 还原，现空 | ✅ 坑 2 达成 |

**规模**（含本报告与双 changelog 落盘后的终树）：**117 跟改 + 5 新增，跟改侧 +426 / −3850**；整删 **9** 个文件。
新增 5 = `GameDatabaseMigrationsV59.kt`、`schemas/…/59.json`（4535 行 KSP 生成物）、
`RoomMigrationV58To59Test.kt`、`TASKBOOK-G15.md`、本报告。
（§二 表头的「115 跟改」是 lint 之前的中途读数，此处为提交时点终值。）

---

## 三、🔴 确定性红线与「为什么 `Diff*` 仍全绿」的因果链

删赠礼 = 删掉一处 **SYSTEM 分区**每亲属一次 `nextDouble` 的消费 ⇒ 直觉上旬/年序列应二次平移（TASKBOOK §10.6 原预期登记 B 类）。实测 **Kotlin `Diff*` 家族零红、C++ 零新增红**。因果链（每一环都有证据，非推测）：

1. **赠礼是门控消费**：Kotlin `RelativeGiftHandler.findRelatives(...) isEmpty → return`、C++
   `relative_gift.h` `mastersAndApprentices.empty() → return` ⇒ 弟子无 `masterId` 时**零次** `nextDouble`。
2. **对拍夹具里唯一播种 `masterId` 的只有 `DiffPhaseSettlementTest` 的 S1「突破后师徒赠送」用例**（该用例本批整删）。
   其余 `DiffAuthoritativeTickTest`（100 旬 AUTHORITATIVE）、`DiffYearSettlementTest`、`DiffMonthSettlementFixture`
   **从未播种过师徒关系** ⇒ 那些场景的序列本来就不含该消费，删除不产生平移。
3. **乘区删的是恒 0 项，且剩余项相加顺序一字未动**：
   `socialBonus`：`(preachingElder + preachingMasters) + md` → `preachingElder + preachingMasters`；
   `selfBonus`：`(pill + md) + comp` → `pill + comp`（删中间项）。
   `x + 0.0 == x`（IEEE754 有限值）⇒ 逐位相同。C++ 侧改后与 Kotlin 同形。
4. **双臂同删**：`Diff*` 断言的是「C++ == Kotlin legacy」，两侧同批删除同一消费 ⇒ 等价关系不变。
   这正是 HANDOVER-3 §3.3 推论「两侧同步删除时 `Diff*` 应当继续绿」的**首次正面实证**。

⇒ 结论登记 G10：**G10 重录工作量确实只集中在 C++ GTest 金序列 + `DeterminismProbe` 摘要**，
Kotlin `Diff*` 家族无需重录（本批为实测、不再是推论）。

---

## 四、独立 grep 终态核验（双向）

### 归零向（删除模式应为 0）

`android/{app,core/*,feature/*}/src/main` + `scripts/action-catalog` 上
`masterDiscipleBonus|getMasterDisciple|MAX_APPRENTICES|MASTER_DISCIPLE_|masterIds|apprenticeToMaster|DiscipleMasterApprenticeService|RelativeGiftHandler|GiftRelationshipType|MasterApprenticeSelectDialog|RelationsDialog|SocialData|unbindMasterColumns|relative_gift|masterBonusFor|\bmdb\b`
→ **仅剩 3 处合法命中**，全部是登记面而非代码面：

1. `GameDatabase.kt:210` —— v59 迁移的 changelog 注释行；
2. `GameDatabaseMigrationsV2ToV10.kt:164` —— 历史迁移注释（迁移链不可改写）；
3. `GameDatabaseMigrationsV59.kt:22` —— 本批迁移 KDoc 的删除面表格。

`masterId` 系残留 25 处逐条判定：`MIGRATION_58_59` 的列名与其 KDoc（本批新增，正确终态）、
`GameDatabase.kt:208/218` 逐版本 changelog 行、`V21ToV30/V2ToV10/V39/V57` 历史 schema 事实、
`DiscipleSerializer.kt:391` 与 `OldSerializableSaveData.kt:177` 的 `reserved 93` 登记、
`game_view.proto:109/112/113` 的 `reserved 78…85` + `reserved "masterId"` 登记 —— **零代码读点**。

C++ 面 `gamecore/{include,src,test,jni}` 上 `masterId|masterIds|MasterId|apprentice|relative_gift|getMasterDisciple|亲属赠送`
→ 仅剩 `action_ids.h` 生成物注释（已 regen 为退役 desc）与 `dispatch_guard_test.cpp` 的 retired 登记行。

### 保留向（每项命中数 > 0，实测）

| 符号 | 文件命中 | 符号 | 文件命中 |
|---|---|---|---|
| `preachingMasters` | 43 | `comprehension` | 89 |
| `qingyunPreachingMasters` | 35 | `comprehensionBreakthroughBonus` | 3 |
| `MASTER_TEACHING_RATE` | 3 | `viceSectMaster` | 43 |
| `manualMasteries` | 49 | `GiftService` / `diplomacy` | 7 / 47 |
| `lifeEvents` | 46 | `LifeEventDraft` | 2 |
| `DISCIPLE_LIFECYCLE_SALARY_TOGGLE` | 5 | `annualNewDisciples` | 12 |
| `DiscipleChatDialog` | 6 | | |

---

## 五、旧用例处置表

| 用例 / 测试类 | 处置 | 理由（原守什么、由谁接管） |
|---|---|---|
| `DiscipleServiceApprenticeTest`（12 用例） | **整类删** | 三相校验链（存在性/同一性存活/名额 5）+ `masterIds` 落表。服务与列均已消失 ⇒ **无替代，玩法已下线** |
| `RelativeGiftHandlerTest`（27 用例） | **整类删** | 师徒赠礼分类与概率门（0.40/0.30）、选品优先级、袋裁剪约束。赠礼系统整体下线 |
| `relative_gift_test.cpp` | **整文件删** | 同上（C++ 对拍臂） |
| `disciple_lifecycle_tx_test.cpp` 拜师三分节（3 TEST） | 删；`SalaryToggleTx_盲写覆写` 原样保留 | 事务 1 下线、事务 2 存续 |
| `disciple_test.cpp` `MasterDiscipleTest.{RealmGap,CultivationBonus}` | 整删 | 境界差算术三函数已删，无对拍对象 |
| `DiscipleStatCalculatorCombatBonusTest`「师徒加成测试」段 | 删 16 用例；保留 19 用例（讲道 4 / 装备 1 / 内外门长老悟性 7 / `DiscipleStats plus` 1 / 自身悟性乘区 4 / 明细 1） | 「师徒加成不超过 1」承载的 `coerceIn(0,1)` 上限仍由 `DiscipleStatCalculatorTest:311`（`pillBonus=10.0`）守住 ⇒ 无覆盖缺口 |
| `CultivationRateEquivalenceTest` | 删 `masterFixtures()` 整函数（师父存活/已死 2 条 fixture），保留 31 条 | fixtures 33→31，`fixtures.size >= 20` 断言仍成立 |
| `DiscipleLifecycleNativeTxGateTest` | 删「回退臂 - 拜师 masterIds 落表与双侧 lifeEvents」1 用例；两转发臂用例名由「两入口」收窄为「年俸入口」 | 事务已退役 ⇒ 整删用例，**未改写成断言新行为凑绿**；年俸 null 断言语义未放宽 |
| `DiscipleLifecycleEventsTest` | 删 `generates master event with master name`；综合用例期望 `listOf("加入宗门","拜玄机真人为师")` → `listOf("加入宗门")`，**保持全量相等断言**（强于 `contains`） | 「不夹带其他合成事件」这一契约反而更强 |
| `DeathPipelineEquivalenceTest` | **只摘解绑面**：2 条 SocialData 种子、4 条 `masterIds` 解绑断言、战斗臂「不解绑」断言、2 条 KDoc；**死亡面全保留**（isAlive/status/deathYear 三元组、年度计数、SECT/DEATH 事件、重伤钳制、年报不动），并补 1 条「在世弟子 isAlive=1」 | 摘除边界 = 生产者消失，非行为覆盖收缩 |
| `DiscipleLifecycleProcessorTest` | 删 `master relationship unbound` 1 用例与 `social:` 形参；袋物化/幂等/isAlive 面保留 | 同上 |
| `GameViewDiscipleColumnApplyEquivalenceTest` | 删「师徒线路哨兵」1 用例 | 该列的 `""=null` 哨兵面随列消失 |
| `MirrorProtoFeedFixture` / `MirrorProtoFeedEquivalenceTest` | 删 `richSocial()` 与其 `assertEquals("ms1", …)` 单行；其余逐字段核对保留 | — |
| `DiffDiscipleTest` | 删「── 师徒 ──」段 `master disciple bonuses` 1 用例（`op="masterDiscipleBonus"` 端口已消失） | 无对拍对象 |
| `DiffPhaseSettlementTest` | 删 `relative gifts after breakthrough`(S1) 1 用例 + 其唯二私有辅助 `probeFirstDouble`/`herb`（删用例后零引用，detekt `UnusedPrivateMember` 会红）；4 处 provider override 收窄 | 突破 diff 覆盖仍由 `phase settlement` 用例的弟子3（每旬突破候选）承担 |
| `DiscipleModelsTest` | 删 SocialData 2 用例 + `discipleExtended_masterId_whenSet` + `defaultConstruction` 的 `assertNull(ext.masterId)` 行 | 字段本体已删，不改即编译不过，属同一删除面 |
| `AssemblePatchEquivalenceTest` | 删 3 处 `masterIds` 写列、2 处 `dirtyIndices` 硬编码 `"masterIds"`、1 条 `social 未脏应复用引用`；KDoc「六个组/7 组」→ 五/六组 | 组数已变，注释必须描述当前态 |
| `DiscipleFactoryTest` / `SalaryPlanColumnEquivalenceTest` / `DiffDiscipleFactoryTest` / `DiscipleServiceCrudTest` / `DiscipleLibrarySlotSwapTest` | 删 `social = SocialData()` 实参与夹具构造依赖；**用例行零增删** | 纯构造面收窄 |
| `BattleSystemTest` / `AISectAttackManagerTest` / `CultivationCoreTest` / `CultivationCoreProficiencyNurtureTest` / `SecretRealmRestAreaTest` / `CultivationCoreRealtimeAutoPillsTest` / `DisciplePillManagerAutoUseEnhancementTest` / `CultivationServiceIntegrationTest` / `DerivedAggregationTest` / `GameStateStoreRollbackTest` / `TransactionRngRollbackTest` / `Diff*` 四处 | provider override 签名各 4 处收窄（8/5 参）；**用例行零增删** | 它们只是碰巧实现了 `DiscipleStatsProvider`，本身无师徒用例 |
| `DiscipleBreakthroughHandlerTest` | 删两处 `relativeGiftHandler = mockSmart()` | 构造参数消失 |
| `DiscipleMergeCoverageTest` | 反射清单 `unchanging` 删 `"social"` 一条 | **判据面同步**：该清单与 `Disciple` 主构造 32 参双向核对（测试① 与 ④ 两个方向），删前 `social` 恰好命中方向④「分类清单中但字段不存在」而红 ⇒ 删后两侧空集自洽 |
| `ProtoNumberUniquenessTest` | `discipleRetired` 追加 93、用例更名 `G04 G15 retired proto numbers stay reserved instead of reused` | §9.5 跨域同步点：不追加则守卫对新退役号无判别力 |
| `RoomMigrationSupport.verifyDisciplesColumnsExist` | **保持 `assertTrue`（本批一度误改为 `assertFalse`，实测撞红后改回）** | 它由 `verifyFullChainColumns` 调用，**链尾是 v40 不是当前版本**；v40 端点该列在场。v59 端点判据落在 `RoomMigrationV58To59Test` 内自证 |
| `RoomMigrationLegacyTest` / `RoomMigrationTest` / `V43To46Test` / `V56To57Test` / `V57To58Test` 的 `social_masterId` | **一律不改** | 历史 schema 事实（v9→v10 加列、v43/v56/v57/v58 在场），迁移链不可改写 |

**净变化**：JUnit 7397 → **7336**（−61）＝ 整删 39（拜师 12 + 赠礼 27）− 新增 3（`RoomMigrationV58To59Test`）+ 其余零散 19（C++ 侧 `MasterDiscipleTest` 2、拜师 tx 3、`DiffPhaseSettlementTest` S1 1 等计在 ctest 面：1413 → 1394 = −19）。

---

## 六、本批新增 B 类清单（交 G10）

**零新增。** ctest 失败集与 G04 收官**逐条同名**（`DiscipleFactory.GoldenSequenceSeed42` /
`…Seed987654321Female` / `DeterminismProbeTest.DigestMatchesGoldenBaseline`）。
§三 已给出「赠礼消费归零但序列未再平移」的完整因果链。
Kotlin 侧 B 类仍为 **0 条**。

> G10 注记：`DeterminismProbe` 的 golden 摘要自 G02 起未再变过（本批 `actual=0xb4f3c6912207f597`），
> 说明该指纹对师徒面删除不敏感；G10 重录时只需按最终态一次到位。

---

## 七、lint 终值（同轮实测）

`./gradlew.bat lintRelease` → **`BUILD SUCCESSFUL in 6m 3s`**（六模块 `lintAnalyzeRelease` + HTML 报告）。
`Lint found 36 warnings (and 3 warnings filtered by baseline lint-baseline.xml)`。

- 任何 `lint-baseline.xml` / `detekt-baseline.xml` 的 `git status` → **零改动**（baseline 只缩不增 ✅）。
- 日志按本批符号（`masterDisciple|RelativeGift|SocialData|masterId|Disciple.kt|DiscipleStatCalculator`）过滤 → **零命中**
  ⇒ 36 条告警全为预存量，本批未引入新告警。
- 耗时 6m3s vs HANDOVER-3 §2.2 记的「约 17m」：本次 Gradle 配置缓存 `entry reused` + 增量分析，非门禁口径变化。
- 坑 2 复验：`lintRelease` 跑完后 `android/app/src/main/assets/atlas/`、`assets/data/` 的
  `git status --porcelain` → **空**，副产物未被改脏（构建中途曾脏一次 `atlas-rgba-manifest.json`，已 `git checkout --` 还原）。

---

## 八、G10 / 后续登记（诚实发现，本批未做）

**工程方法与规程**

1. 🔴 **「按字面量 grep 定测试面」有系统性盲区**：`DiscipleStatsProvider` 的 18 个实现点里
   **6 个用缩写形参名 `mdb: Double`**，`masterDiscipleBonus` 字面量 grep 全部漏计，会在集成期编译爆红。
   ⇒ 建议写进 `rules/testing.md`/任务书规程：**接口形参删除类批次必须按「实现处枚举」定面**
   （`grep -rln "override fun <方法名>"`），不得只按符号名匹配。
   本批靠子代理反查发现，追加 c340-15d 片收口（全量 18 点自证无漏）。
2. 🔴 **分片文件面互斥但签名跨片互锁 ⇒ 分片期禁止任何编译门**：本批 9 片并行期整树必然不可编译，
   明确要求子代理「不跑 gradle/cmake，只精确编辑 + grep 自检」，主线程终树复跑。
   实测零片撞 150 轮上限（承 G04 的 ≤10 文件/片规程）。
3. ⚠️ **上位交接的文件面不可直接派工**：HANDOVER-3 §10.2 漏 13 项、误列 2 项（`viceSectMaster` 假阳性）。
   TASKBOOK §3.4 的两项前置回查第二次兑现价值，建议将其固化为「删列批派工前的强制步骤」。
4. ⚠️ **`pwsh` 与 `powershell` 不可互换**：用 `powershell -File scripts/build-desktop-jni.ps1` 会以
   PowerShell 5.1 解析失败（`-I (Join-Path …)` 报 UnexpectedToken），而外层仍回报 exit 0、`.so` 时间戳不变。
   本批靠读它自己的日志 + 比对 `.so` 体积（8560640 → 8526336）抓到，等于差点拿旧库跑 JUnit。
   ⇒ 坑 5 的加强版：**后台命令的 exit code 连「日志写了错」都盖不住，必须验产物时间戳/体积**。建议回写 HANDOVER-4。

**死代码与零消费者（G10「死代码 grep 清零」正牌范围）**

5. `NullSafeProtoBuf.relationIdToProto/relationIdFromProto` —— 实测 **G15 之前就已零生产调用方**
   （唯一"使用者"是它自己 KDoc 的示例，示例恰好拿 `masterId` 举例，所以看起来像本批造成的）。
   同族 `NullableStringSerializer` / `NullableLongSerializer`（`Serializers.kt:14/28`）同样零调用方。
   ⇒ 本批只改写了误导性 KDoc 示例，**未删**（删需连带 `NullSafeProtoBufTest`，且非本批因果）。归 G10。
6. `DiffMonthSettlementFixture.kt:83 buildProductionProcessor`、`:327 buildDiffService`
   —— HEAD 即零消费者的死 helper（与师徒无关）。归 G10。
7. `DiscipleLifecycleProcessor` 构造实为 9 参，其 `@Suppress("TooManyFunctions"/长参) ` 注释写「10 个」
   且阈值 10 下该抑制可能已多余 —— 预存失真，本批未擅改。归 G10 注释七项检查。
8. `DiscipleBreakthroughHandler.notifyBreakthroughChanges` 现仅记录突破日志，函数名语义偏宽；
   改名会波及测试与调用点 ⇒ 未擅改，登记。

**注释与活文档（G10 文档收口）**

9. `docs/rng-source-inventory.md` 的 `SYSTEM(3)`（35 处）整行**自 G02 起就是 stale 的**：本批只按因果
   摘掉 `RelativeGiftHandler`，但同一行仍列着 G02/G03/G05 已删的 `LawEnforcement*`(9) / `ChildBirthSystem` /
   `PartnerSystem` / `RecruitService` / `GameEngineSpiritRootOps` / `GameEngineTraitAddOps` / `GameEngineTraitWashOps`，
   且「35 处」计数从未复核。⇒ 整行需 G10 按 `grep -rn "RngPartition\."` 重跑盘点，不做局部修补。
10. 历史批次记录里仍以现况口吻提师徒（**均为 dated 过程记录，非现况真源，本批有意不回改**）：
    `CODE_WIKI.md:94`、`docs/cpp-engine.md:19/522/621`、`docs/ui-read-surface.md:133`、
    `docs/cpp-migration-handover-m0.md:48/52`、`docs/character-gacha-redesign-2026-09-23.md:301/865/1010`、
    `docs/character-system-audit-2026-09-22.md:110/255/259`。
    其中 `CODE_WIKI.md:94` 与 `cpp-engine.md:621` 的 batch-14 行**在 G03/G06 时就已 stale**（逐出/婚姻批准仍列），
    属既有惯性；若 G10 要做「代码级 Wiki 现况化」，这两处优先。
    本批**已改**的活文档：`docs/knowledge-base.md:239`（删对拍 fixture 的「师徒」维度描述，现况真源）。
11. `changelog_entries.json` 首块（**未发布的 4.01.14 版本块**）内 G03 写的两条已被本批证伪 ——
    「师门传承只保留师徒一脉」与「弟子详情的『关系』现在只列师父与徒弟…」。
    因该版本尚未发布、玩家读到即误导 ⇒ 按 HANDOVER-3 §5 口径就地改写（净结果保留），
    并追加 G15 两条。外部 `CHANGELOG.md` 的历史段落一律未回改。
12. `disciple_lifecycle_tx.h` 与 catalog 的退役编号列举长期漏登 **1593 释放思过**（其 desc 早标退役）。
    本批在该头文件顺手补齐（1590/1591/1592/**1593**/1740/1750）；catalog 侧注释未动（属 G02 遗留面）。

**待用户拍板**

13. 本次删列后 `disciples` 的 `social_` 前缀 @Embedded 组件**整体消失**，
    `Disciple` 只剩 combat/pillEffects/equipment/skills/usage 五个 @Embedded 段。
    旧档 `social_masterId` 数据被直接弃用（不做「师徒关系转成普通好感」之类的迁移补偿）——
    本批按 P-1「连根拆掉」执行并在迁移 KDoc 里给了结构性论证。
    若产品想要**补偿口径**（如按已建立师徒数折算灵石/好感），那是一次独立的经济设计，未包含在本批内。
14. HANDOVER-3 §10.3 要求「开工先 grep 实际 `@ProtoNumber` 号，勿照抄 G03 清单」已兑现：
    实测 `masterId = 93`（新档 surrogate）与 `masterId = 93`（Old 档）同号，镜像 proto 独立为 `85`。

---

## 九、树指纹与提交身份（HANDOVER-3 §3.5 要求）

| 时点 | `git rev-parse HEAD` | `git status --porcelain` 行数 |
|---|---|---|
| 开工（门禁前） | `a055bf9fe12affa374a1ac00cfe174ddaf6af744` | 2（仅 `docs/research/`×2，与本批无关、不提交） |
| 暂存待提交 | `a055bf9fe…`（同一 HEAD） | **124** ＝ 117 跟改 `M` + 5 新增 `??` + 2 条 `docs/research/` `??`（后者不纳入提交） |
| 提交后 | **`325da9d5a`** | **2**（只剩 `docs/research/`×2）＝ 非未跟踪残留 0 ✅ |

⇒ 差集核对：`124 − 2 = 122` 条属本批，`git add` 逐条落在其中、零越界（暂存清单见 §十）。
> ⚠️ 本轮复核跑 lint 时 `atlas-rgba-manifest.json` 又被改脏（坑 2 复现），已 `git checkout --` 还原后才提交。

**CRLF 纪律核查**（坑 6）：`git diff --cached --numstat` 逐文件比对，
**无一个文件出现「增删双侧 ≈ 全文件行数」的行尾翻转**。
git 报的大量 "LF will be replaced by CRLF" 提示源于仓库 `core.autocrlf=true` 且无 `.gitattributes`，
属预存检出行为，非本批造成的行尾污染。
全程只用 Edit/Write 工具改源码，未使用任何 sed/perl/node 脚本改写（`feedback-kotlin-edit-tooling` 实事故纪律）。

## 十、提交状态

**✅ 已单次提交：`325da9d5a`**（122 文件：117 跟改 + 5 新增；9 文件删除模式、2 文件新建代码 + schema + 2 篇批次文档；
跟改侧 +426/−3850，含新文件后 `--shortstat` 为 +6092/−3893）。前驱 `a055bf9fe`。
`docs/research/`×2 与构建副产物**未纳入**（提交后 `git status --porcelain` 只剩那两条未跟踪）。

**提交前同轮重跑的全部门禁**（本会话以复核身份自跑，非引用实施阶段数字）：
`cmake --build .` EXIT=0 → `ctest` **1394/1391/3 同三条 B 类** → `compileReleaseKotlin` + 六模块测试源编译绿 →
`pwsh build-desktop-jni.ps1`（`.so` 15:48:49，8526336 B 与 G15 后 C++ 源码一致）→
`testReleaseUnitTest --rerun-tasks --continue` **BUILD SUCCESSFUL in 11m 43s / 7336/0/0/18 skip 逐模块 XML 汇总** →
detekt 六模块绿 → `lintRelease` BUILD SUCCESSFUL → 四个 node 门全部一致。

⚠️ **诚实标注两处复核轮的降级**：
① `lintRelease` 复核轮六模块 `lintAnalyzeRelease` 全为 **UP-TO-DATE**（输入未变），未重跑分析；
完整分析证据取本会话 15:5x 那轮的 `BUILD SUCCESSFUL in 6m 3s` + `Lint found 36 warnings`。
② `compileReleaseKotlin` / 测试源编译复核轮亦为增量（3s/4s）；全量编译证据在实施轮同会话的 1m57s / 47s 两轮。
③ 复核轮中 `atlas-rgba-manifest.json` 再次被 lint 改脏（坑 2 复现），已 `git checkout --` 还原后才提交。

**未做的抽验（留给后续或 G10）**：`GameViewDiscipleProjectionTest` 双射守卫的**判别力自证**
（把 `"masterId"` 加回 `requiredScalarFields` 看是否判红）未做——JUnit 全绿只证明「当前一致」，
不证明「漏删会红」。§九 的 ① 项结论仍是未核实。
