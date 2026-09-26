# report-G09 · 寻访抽卡核心（roll / 保底 / 碎片 / 升星 / 解锁 / 入库 / 星级乘区 / 独立随机分区）

> 派工真源：[`TASKBOOK-G09.md`](TASKBOOK-G09.md)（含上位失真清单、D-1…D-18 决策、六项产品拍板）。
> **本报告的「验证」节只记本会话同轮命令的原始输出数字**；未跑的门禁显式写在 §六「未完成」，
> 不以「应该通过」充当证据。

## 〇、交付状态一句话

**已收官**：C++ / JNI / Kotlin 全量门禁 + 四个 node 门 + 退役集 24==24 全部**同轮实测通过**
（§三 / §四），首轮实跑查出的五条真缺陷已按根因修复（§5.2）。剩余项只有 G10/G11/G12 承接与一条
产品口径待拍板（§六）。

> 本文件两度改写：实施会话（2026-09-26 凌晨，代码落地但 Kotlin 门禁未跑）→ 收官会话
> （2026-09-26 12:4x–14:2x，以复核身份重跑 §三/§四 全表并修复 §5.2 五条）。凡与本文件
> 初稿数字不一致处，以本会话实测为准（初稿失真本身已登记在 §5.2-E）。

## 一、做了什么（分类）

| 类 | 内容 |
|---|---|
| ① 新增（C++） | `data/gacha_pool_db.h`（GachaPoolTemplate/CharacterTemplate + 空容器无内联兜底）；`system/gacha_tx.h`（`checkPool`/`weightedPickIndex`/`pullTx`/`pullOnceTransaction`/`pullTenTransaction`）；`system/star_zone.h`（口径 A 单点：`kStarBattlePctPerStar=0.08` / `kStarCultPctPerStar=0.05` / `starZoneOf` / `resolveStar`）；`test/gacha_pull_test.cpp`（19 例）、`test/star_zone_test.cpp`（9 例）、`test/game_data_json.h`（数据文件定位收敛，替掉 `data_store_test.cpp` 里手抄的一份兜底） |
| ② 追加（C++） | `data_inject.h` 两段注入 + `AppliedCounts` 两字段；`data_json.h` 六个 `from_json/to_json`；`dispatch_gacha.cpp` 1871/1872 case + 溢出草稿/rows 信封编码；`rng_manager.h` `kGacha=12` + `kMaxPartitionId` 上移 + `seed+12` 播种；`data_store_test.cpp` 三层守卫扩到九表；`rng_test.cpp` 快照分区 11→12 + 五条 GACHA 用例 |
| ③ 复用未改 | `gacha_fragment.h::addFragment`（碎片/升星唯一写者）、`DiscipleService.instantiateTemplate`（唯一入册口）、`insertTemplateDisciple`（`annualNewDisciples` 单点，D-6 双计已规避）、`inventory.h::addXxx`（统一入库 + 溢出草稿）、泛化脏段镜像（零 proto 改动）、`execute_dispatch.cpp` 端口（已接线，仅 `SECT_POWER_DISCIPLE` 加 `star` 参数读取） |
| ④ 新增（Kotlin） | `core/domain/.../model/StarZone.kt`（与 C++ 同名同参）；`domain/gacha/GachaPoolConfig.kt`（解析与 C++ 注入**同一份** `game-data.json`，缺段即 null 不内置概率）；`domain/gacha/GachaPullLedger.kt`（纯函数回退臂 + `poolError` 全分支）；`GachaNativeTx.tryPullOnce/tryPullTen`；`GachaService.pullLocally`（单事务：扣费→逐抽→入库→历史环→保底）；`GachaFacadeImpl.pull`（双臂 + 溢出草稿投递 + 解锁入册）；`syncGachaUnlockedRoster`（D-7 幂等补齐，收官会话按 detekt 文件级函数数阈值下沉到 `domain/gacha/GachaRosterSync.kt`，读档调用点与语义不变） |
| ⑤ 收口（Kotlin） | `GachaPullResult.NotReady` 占位删除 → `Success(rows[], unlockedTemplateIds[], pityAfter, …)` + `GachaPullRow`（只带 id，KDoc 钉死 512 档 `avatarKey` 口径）；`RngPartition.GACHA(12)`；`SpiritStoneReason.Gacha` / `SpiritStoneSource.Gacha`；`CultivationSpeedZones` 第 5 乘区 `starBonus`（三版组装 + 两版列直读同序连乘）；`SectCombatPowerCalculator` 带星级入口 + 战力缓存改星级敏感；`SOURCE_DISPLAY_NAMES` 25→22（文案整改 + `gacha_pull→仙缘寻访`）与满仓邮件标题/正文模板；双 changelog |
| ⑥ 追加（收官会话，见 §5.2） | `jni/GameCoreJni.cpp` 桌面静态表注入端口 `DiffRngBridge_nativeCoreSetGameData` + `DiffRngBridge.kt` 声明 + `scripts/build-desktop-jni.ps1`/`-linux.sh` 各补 `src/data_store.cpp`；`GachaService.pullLocally` 抽取序/历史序分离；`GachaPullLedger` 池校验三段化；`GachaRosterSync.kt` 新文件；两处 `NotReady` 注释残留清理 |

## 二、验收判据逐条

| # | 判据 | 状态 |
|---|---|---|
| ① | pullOnce/pullTen 走 native 事务（1871/1872）+ 回读；`NotReady` 消失；`MirrorReadOnlyGuardTest` 零命中 | **实测绿**：`DiffGachaPullTest` 6/6（双臂逐位对拍）+ 全量 JUnit 7419/0/0/18；两处 `NotReady` 注释残留已清（§五-9） |
| ②③④⑤⑥ | 保底第 10 抽本身 / 碎片单点 / 解锁单点且计数一次 / 物品统一入口+满仓转邮件+品阶≤4 / 历史环 50 新在前 | **C++ GTest 全绿实测**（§三）+ 双臂 JUnit 绿；「历史环新在前」与「结果格抽取序」两口径已分离并各自钉死（§5.2-B） |
| ⑦ | 星级乘区双端同名同参（口径 A） | C++ `star_zone_test` **10 例**绿（初稿记 9 例，已纠）；Kotlin `StarZone.kt` + `CultivationSpeedZones.starBonus` + `SectCombatPowerCalculatorTest`/`DerivedAggregationTest` 随全量 JUnit 绿 |
| ⑧ | 零新增 A 类；三条 B 类同名且 `actual` 指纹不变；本批不重录 | **已实测**（§三）：`actual=0xb4f3c6912207f597` 与 G08/G16/G15 逐字符相同，金黄与对拍基线零重录 |
| ⑨ | 常量三向守卫扩到星级百分比/环大小 + 运行期 key 域守卫 | **实测绿**：`CharacterTemplateGuardTest`（三向臂逐值）/ `GachaConfigGuardTest` 7/7 / `GachaPullGuardTest` 9/9 |
| ⑩ | 双 changelog + 本报告 + 单次提交 | 双 changelog 已写（外部条目含 §5.2 的构建面）、本报告实测节已回写；提交为本批单次代码 commit（`report-G09.md` 随批入库） |

## 三、门禁实测（收官会话 2026-09-26 同轮原始输出）

> 实施会话（2026-09-26 凌晨）报的是 `1427 tests / 3 failed`、`star_zone_test.cpp` 9 例。
> 收官会话以复核身份重跑，**实测为 1437 / 3、`star_zone_test.cpp` 10 例**——实施会话那一轮
> 少计 10 条（其自身加法 `1401+19+9+5+2=1436` 也自相矛盾）。以下数字为复核轮，以复核轮为准。

```
树指纹：branch=feat/gacha-m0-m1  HEAD=c4df53253  git status --porcelain = 64 行
桌面 C++（build/desktop-test，GAMECORE_BUILD_BENCH=ON）：
  cmake --build .        → [4/4] Linking game-core-tests.exe（0 error，48 条预存 warning）
  ctest                  → 1437 tests / 3 failed / Total 55.07 sec
    失败集（与 G08/G16/G15 逐条同名）：
      837 DiscipleFactory.GoldenSequenceSeed42
      838 DiscipleFactory.GoldenSequenceSeed987654321Female
      1071 DeterminismProbeTest.DigestMatchesGoldenBaseline
  ctest -R "GachaPull|DataStore"  → 32/32 passed
  ctest -R "StarZone|Gacha"       → 43/43 passed
  ctest -R Determinism            → actual=0xb4f3c6912207f597 golden=0x490e8dc522e12921
                                    （actual 与 G08/G16/G15 逐字符相同 ⇒ 独立分区 + 星级乘区
                                      未平移任何既有掷点；DigestIsStableAcrossRepeatedRuns Passed）
  ctest -R SceneEquivalence       → 13/13 passed
桌面 JNI：pwsh -File scripts/build-desktop-jni.ps1 → libgamecorejni.so
  8542208 → 8656384 字节（mtime 2026-09-26 12:56:59，sha256 前缀 2625abfe6847d132）
node scripts/gen-action-ids.mjs   → 201 actions (maxId=1872)   ← 与 D-17 目标一致
  零漂移自证：regen 前 cp 两份生成物 → regen 后 diff 双双无输出
node scripts/gen-game-data.mjs --check → sha256 035066cbcb891aa124397667e58879d51d4dd4124177ae38b550e1b2c22094ef（未变）
node scripts/check-jni-count.mjs  → total=86/86，双桥无扩散
node scripts/check-agent-instructions.mjs → 规则①–⑤ 全 ✓，EXIT=0（无新增告警）
catalog↔guard 退役集 → 24 == 24，双向零差集，且 24 个 id 的名称两侧逐条相同
```

用例数 1401（G08 基线）→ 1437 全部可归因，增量 36 = `gacha_pull_test.cpp` 19 +
`star_zone_test.cpp` 10 + `rng_test.cpp` GACHA 5 + `data_store_test.cpp` 卡池专项 2。
**A 类新增 0 条**（36 条新用例全部 Passed，3 条红点为同名 B 类）。

### 3.1 首轮 Kotlin 实跑（13:0x，暴露 §5.2 五条缺陷）

```
:core:engine:testReleaseUnitTest → 失败 5 条（全在 DiffGachaPullTest，逐条根因见 §5.2 A/B/C）
  按模块：app 1011/2skip、core:domain 1571、core:data 814/15skip、core:engine 2930/1skip/**5 fail**、
          core:ui 146、feature:game 947 ⇒ 合计 7419 / 18 skip / 5 fail / 0 error
:core:engine:detekt            → 6 条违规（§5.2-D 逐条）；其余五模块 0
:app:lintRelease               → 36 warnings（与基线逐值相同、0 error）⇒ lint 不受本轮红点影响
```

### 3.2 修复后第二轮全量组合门（13:49–14:12，同轮重跑）

```
桌面 C++（补 jni/GameCoreJni.cpp + 两脚本 data_store.cpp 之后）：
  cmake --build . → [36/36] Linking game-core-tests.exe（0 error）
  ctest           → 1437 registered / 3 failed（同名三条 B 类，逐条与 §三首块一致）
桌面 JNI：pwsh -File scripts/build-desktop-jni.ps1
  第一次 rc=1（`ld.lld: undefined symbol: gamecore::data::loadFromJson`）⇒ 两脚本补登 src/data_store.cpp
  重跑 rc=0：8656384 → 9015808 字节，mtime 13:41:23（判据 = mtime + 体积 + 链接日志）
组合门（compileReleaseKotlin + testReleaseUnitTest --max-workers=1 --rerun-tasks + detekt + lintRelease）
  → BUILD SUCCESSFUL in 23m17s，GRADLE_EXIT=0，**零 FAILED 任务**
  JUnit 按模块（XML 汇总）：app 1011/2skip、core:domain 1571、core:data 814/15skip、
    core:engine 2930/1skip/**0 fail**、core:ui 146、feature:game 947
    ⇒ 合计 7419 tests / 18 skipped / 0 failures / 0 errors（基线 7391 + 本批新用例 28）
  detekt 六模块 XML：severity="Error" 与 "Warning" 计数**全 0**
  lintRelease：36 warnings（3 条被 lint-baseline 过滤）+ 0 error ⇒ 与基线逐值相同
node 四项 + 退役集（改 Kotlin/C++/脚本后复跑）：
  check-jni-count 86/86 · gen-game-data --check sha256 035066cb…（未变）·
  gen-action-ids 201/maxId 1872（cp→regen→diff 二次自证零漂移）·
  check-agent-instructions 规则①–⑤ 全 ✓ EXIT=0
```

## 四、Kotlin 编译与门禁结论

`compileReleaseKotlin` 首轮（实施会话）报 8 错（缺 `str`/`jsonPrimitive`/`onFailure`/`StarZone`
导入、`MaterialCategory` 与 String 的类型错配），已全部修正。本会话两轮组合门均**编译零错误**
（第二轮 `BUILD SUCCESSFUL`，含 `:core:engine`/`:app` 测试源集），JUnit/detekt/lint 逐值见 §3.2。

**判别力实证**（不需要构造反例就已自证）：§3.1 的五条红点里，A（注入通道缺失）与 B（回退臂
把历史序当抽取序）都是**代码真缺陷**、由新守卫点名抓获；修完 A/B/C 后同一批用例转绿，
且 C++ 臂与黄金表逐位吻合未受影响——即守卫有判别力，不是「跟着实现改断言」。


## 五、任务书之外发现的事实（上位失真 / 连带面）

1. 🔴 **抽卡扣灵石必须走 `SpiritStoneWallet::deduct(reason="Gacha", autoConvert=false)`，
   而 `SpiritStoneReason`/`SpiritStoneSource` 是 sealed class（key = 类名）** ⇒ 必须新增两个
   `object Gacha`，否则两条臂写进两套 `annualExpenditureByReason` 键（年报分裂）。
   任务书 §3.1「复用钱包扣减语义」未点出这一连带面。已补，并在 `YearlyReportHelpers` 的
   `SPIRIT_STONE_REASON_NAMES` 加「Gacha → 仙缘寻访」。
2. 🔴 **Kotlin 侧没有卡池运行时读面**：`db.gachaPools` 此前只被守卫测试以文本方式读过，
   生产零读取 ⇒ 回退臂无概率可用。新增 `GachaPoolConfig`（解析同一份资产）而非在 Kotlin
   再抄一份概率表，否则第二真源。
3. 🔴 **C++ 无法建邮件**（`state::GameState` 无邮件字段，`month_settlement.h` / `merchant_settlement.h`
   两处注释明写「草稿丢弃」）。溢出邮件的唯一通路是信封回传 + Kotlin `InventoryNativeForward`
   投递 ⇒ 回退臂必须接这一路，否则满仓丢件。任务书 §3.6 只写了「含溢出草稿」，未写这一半。
4. 🟠 **战力缓存指纹不含星级** ⇒ 单改公式不够，`GameStateStoreImpl.CachedPower` 必须带 `star`
   判据，否则升星不刷新显示战力。指纹公式刻意不改（改了会平移既有指纹、跨语言比对面无收益）。
5. 🟠 **`docs/rng-source-inventory.md`** 已加 §9 的 GACHA 登记行（守卫消息点名的同步点）；
   该文档 §2/§3 的整行盘点仍按任务书归 G10。
6. 🟠 **产品文档仍写 `withTrackingSource("仙缘寻访")`（中文字面量）**：
   `docs/character-gacha-redesign-2026-09-23.md:113/444`、`docs/design/character-gacha-implementation.md:85`
   与已拍板的「ASCII 内部键 + 中文显示名」（D-9）冲突 ⇒ 需文档回写，属 G12 文档收口批。
7. 🟡 `GachaService.grantFragmentsLocally` 就地改 `gameData` 的 map 字段而不 `copy()`，
   `_gameDataFlow` 引用不变 ⇒ UI 侧 `starMap`/`fragmentCounts` 订阅可能不发射（G08 遗留，
   非本批造成）。抽卡路径同样使用该写法，需在 G10/G11 一并核。
8. 🟡 `OverflowMailSender` 的「部分奖励已转入邮件」提示条（约 `:308`）仍用「奖励」措辞，
   与本次标题/正文模板的语义修正同类，未随批改（登记）。
9. 🟠 **注释面残留（收官会话实测发现并修正，两处）**：`GachaFacadeImpl.kt` 类 KDoc 仍写
   「抽卡 roll/保底仍占位…当前恒返回 `[GachaPullResult.NotReady]`」——该分支本批已删除，
   链接指向已不存在的符号；`GachaDelegate.kt` 的类 KDoc 写「G01 空壳」、成员写
   「占位：G09 前恒 NotReady」。两处已改为当前状态表述（全仓复扫后仅剩
   `DialogFeatureRoutes.kt:68` 的「寻访占位对话框」——该占位属实，归 G11，不改）。
10. 🟠 **`DeterminismProbe` 计数与实施会话不一致**（见 §三 抬头）：本批用例增量按 ctest 注册表
    实测为 36 条，其中 `star_zone_test.cpp` 为 10 例而非报告初稿的 9 例。已按复核轮数字回写。
11. 🟡 **门禁操作事实**：`build-desktop-jni.ps1` 首次以分离会话调用时返回码 0 但零输出、`.so`
    mtime/体积均未变（未真正重建）；改为前台重跑后 `.so` 才更新。⇒ 该脚本「打印成功」不足以
    作证，必须用 mtime + 体积 + sha256 三个独立判据复核（已按此登记在 §三）。

### 5.2 🔴 收官会话（2026-09-26）实跑 Kotlin 全量门后新查出并修复的五条根因

初稿 §〇 写「代码面全部落地」，但**新增测试从未跑过一次**这一条把五处真缺陷全部盖住了。
以下每条都是「症状 → 根因 → 修复」有实据的因果链，且都改了生产或构建脚本面（非测试放宽）。

| # | 症状（首轮实跑） | 根因（实测） | 修复 |
|---|---|---|---|
| A | `DiffGachaPullTest` 5 条红，native 臂对真实池一律 `PoolNotFound`（`gacha_pool_db.h` 有测试自述消息点名两处） | 三重叠加：① D-1 刻意**不做内联兜底** ⇒ 桌面侧必须由注入器喂表；② 生产的注入端口 `nativeSetGameData` 在 `app/src/main/cpp/GameCoreBridge.cpp`，**桌面 .so 不编该文件**；③ `scripts/build-desktop-jni.ps1`/`-linux.sh` 是手写源清单，**长期漏登 `src/data_store.cpp`**（`injectFromJson` 依赖 `data::loadFromJson`）⇒ 一加端口就 `ld.lld: undefined symbol` 且 `.so` 被脚本先删后建（实测 rc=1、`.so` 消失） | 补**测试桥同语义端口** `DiffRngBridge.nativeCoreSetGameData`（`jni/GameCoreJni.cpp` 调 `inject::injectFromJson`，与生产 `nativeSetGameData` 同一入口、同一「仅初始化期一次」状态机；`check-jni-count` 只扫 `src/main` 两个生产桥 ⇒ 86/86 不扩散）＋ 两脚本各补 `src/data_store.cpp`；`DiffGachaPullTest` 在首次进 native 臂前注入 `FileAssetSource` 定位到的**同一份产物文件**（进程级注一次） |
| B | 三条双臂向量红：`十连…保底在下标 2` 实测下标 **7**；`Kotlin 回退臂与黄金表不符`（C++ 臂逐位吻合） | **回退臂真缺陷**：`GachaService.pullLocally` 用同一个 `rows.add(0, …)` 列表**同时**充当「历史环（新在前）」与「结果格 DTO（抽取序）」⇒ 十连格序整体倒置，违反 `GachaPullResult.Success.rows` 已写明的「顺序即抽取序，十连第 10 格在末位」（D-10/D-13 的格序口径） | `GachaService.kt:134-167` 改为 `drawn` 存抽取序、历史环写 `(drawn.asReversed() + 旧环).take(50)`（新在前不变），并给 `LocalPull.rows` 补 `@property` 钉死两个口径不得混用 |
| C | 2 条用例 `MockitoException: cannot mock this class: interface DomainResult` | `DomainResult` 是 `sealed interface`，`mockSmart`（`RETURNS_SMART_NULLS`）对**未 stub 的** `addHerb/addSeed/addMaterial` 生成默认答案时要代理该密封接口 ⇒ ByteBuddy 直接抛错；`withTrackingSource` 的 doAnswer 把 block 真实执行后才触到这条路径 | 按仓库既有惯例给三个 `add*` 显式 `doAnswer { DomainResult.Success(实参) }`（先例注释见 `ExplorationPatrolRouteTest:98`「sealed interface 无法代理」） |
| D | `:core:engine:detekt` 6 条违规（`baseline` 只缩不增，必须真修） | `GachaPullLedger.poolError` 圈复杂度 24 / 16 个 return（把 C++ `checkPool` 的九段判据平铺成一函数）、`historyEntry` 8 参触阈值、`GameEngineLoadDataOps.kt` 加一个函数即到文件级 15 函数阈值、测试面 2 条（`throw IllegalStateException`、KDoc 表格行 123 字符） | `poolError` 拆为 `when` + `headerError`/`weightsError`/`categoryError` 三段（object 函数 11/12 内，判定与结果码逐条不变）；`historyEntry` 的 `templateId/itemId/rarity` 改默认值（`ignoreDefaultParameters` 生效，计数 5）；`syncGachaUnlockedRoster` 移到新文件 `domain/gacha/GachaRosterSync.kt`（D-7 语义不变，读档调用点仍一行）；测试面两条按规则改写 |
| E | 初稿 §三 数字与本会话实测不一致（1427 vs 1437、9 例 vs 10 例） | 实施会话在补完 `star_zone_test.cpp` 第 10 例前抄的计数，且其自身加法 `1401+19+9+5+2` 已不自洽 | 按本会话 `ctest -N` = **1437**、增量 36 条逐文件归因回写（§三） |

**顺带查明的一条既有登记**：TASKBOOK §7.3-9 把「`.ps1`/`.sh` 源清单分歧」记为「⚠️ 子代理报 Linux 腿缺
`gameview_encode.cpp`，属预存问题，登记不改」——本轮实测**不止那一条**：两腿都缺 `data_store.cpp`，
且缺它只在此刻才暴露（此前无人从桌面桥引用注入器）。`gameview_encode.cpp` 仅 `.ps1` 有、`.sh` 无，
该分歧保持登记不改（iOS/Linux 腿不在 M1 判据内）。

## 六、收官状态与剩余项

### 6.1 初稿「未完成」六项的处置

| 初稿条目 | 现状态 |
|---|---|
| Kotlin 全量门禁（含 JNI 重建 / JUnit / detekt / lint / node 门 / 退役集） | **已同轮实跑两轮**：首轮 5 红 + detekt 6 条（§3.1），修复后第二轮 `BUILD SUCCESSFUL 23m17s`、JUnit 7419/0/0/18、detekt 六模块 0 error 0 warning、lint 36 警告 0 error、node 四项与退役集 24==24 全绿（§3.2） |
| 新增测试的实测结论（`DiffGachaPullTest`/`GachaPullGuardTest`/扩后的两守卫/`RedeemCodeServiceTest`） | **已实测**：`DiffGachaPullTest` 6/6、`GachaPullGuardTest` 9/9、`GachaConfigGuardTest` 7/7、`CharacterTemplateGuardTest` 与 `RedeemCodeServiceTest` 在全量集内零失败；判别力实证见 §四 |
| `docs/ui-read-surface.md` §2.1 补登四字段 | **已登记**（含写者归属、稀疏性口径、协议面行号、UI 消费面尚未接入的说明） |
| 构建副产物还原 | 已 `git checkout --`（实测该文件仅 `generatedAt` 一键变化，属时间戳类副产物） |
| 单次提交 | 本批代码 + 测试 + 双 changelog + 本报告 + 文档登记为一次提交 |
| 星级乘区的展示链口径（弟子详情页是否显示加成） | **仍待产品拍板**（见 6.2-1），不阻塞本批判据 |

### 6.2 本批不做、须由后续批次承接（TASKBOOK §7.3 的登记面 + 本会话新增）

1. 🟠 **星级乘区是否进 `finalStats` 展示链**：产品 §15.5 有该表述、任务书 §3.8 只钉战斗+修炼两链。
   现状：属性面板不显示星级加成，且 `CachedPower` 指纹公式未改（改了会平移既有指纹）。需一句产品口径。
2. 🔴 **G10 唯一重录窗口**：三条 B 类红（`DiscipleFactory.GoldenSequence*`×2、
   `DeterminismProbeTest.DigestMatchesGoldenBaseline`，`actual=0xb4f3c6912207f597`）本批零重录。
3. 🔴 **G11**：寻访 UI / 结果页格序 / 图鉴 / `GachaDelegate` 接线（本批后 `GachaDelegate`
   仍零实例化 ⇒ 无生产入口，属预期）；头像位读 512 档 `avatarKey` 的口径已钉在 `GachaPullRow` KDoc。
4. 🟠 `docs/rng-source-inventory.md` §2/§3 的整行盘点仍归 G10（本批只加 §9 的 GACHA 登记行）。
5. 🟠 **文档债（G12）**：`m0-economic-whitepaper.md:143-144` 正文「采纳口径 B」与已拍板 A 矛盾；
   `docs/character-gacha-redesign-2026-09-23.md:113/444`、`docs/design/character-gacha-implementation.md:85`
   仍写 `withTrackingSource("仙缘寻访")` 中文字面量，与 D-9 的 ASCII 内部键冲突。
6. 🟡 `GachaService.grantFragmentsLocally` 就地改 `gameData` 的 map 字段而不 `copy()`，
   `_gameDataFlow` 引用不变 ⇒ UI 侧 `starMap`/`fragmentCounts` 订阅可能不发射（G08 遗留；
   抽卡路径同写法）。**G11 接 UI 前必须核**，否则结果页刷新不出来。
7. 🟡 `OverflowMailSender` 的「部分奖励已转入邮件」提示条（约 `:308`）仍用「奖励」措辞，
   与 §3.6.1 同类，未随批改（登记）。
8. 🟡 **测试面重复的 android 工程根定位器**：`OverflowMailSenderTest.androidRoot()`（本批新写）与
   `CharacterTemplateGuardTest.locate()`/`DiffGachaPullTest.gameDataFile()` 是三份同形实现。
   本批不动（避免把无关重构混进抽卡批），建议 G10 死代码清零时一并收敛为单一测试工具。
9. 🟡 **Linux 桌面腿源清单分歧**：`.sh` 缺 `gameview_encode.cpp`（预存，登记不改）；
   本批已把两腿共同缺失的 `data_store.cpp` 补齐。

