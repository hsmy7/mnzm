# report-SS9 · 玉符账本（C++ 真源）

> 批次真源：[`TASKBOOK-SS9.md`](TASKBOOK-SS9.md)（验收①–⑩）+ 上位方案 [`single-save-and-persistence-consolidation-plan-2026-10-01.md`](../single-save-and-persistence-consolidation-plan-2026-10-01.md) §2.4。
> 工作区：`C:\Mnzm\XianxiaSectNative-SS9`（`feat/single-save-SS9`，基线 `67aa10570` = main 含 SS1）。
> 状态：实施完成，全部验收项落地，门禁全绿（数值见 §4），停手待验收。

---

## 1. 做了什么

### 1.1 C++ 侧（账本真源，T-SS9a/b/c）

| 文件 | 改动 |
|---|---|
| `models.h` | 新增 `JadeLedgerEntry`（`atEpochMs`/`delta`/`reason`/`balanceAfter`）+ `GameData.jadeLedger`（`std::vector`，append-only）；`jadeSymbols` 注释降级为「派生缓存」语义注记 |
| `jade_tx.h` | 账本核心四函数：`kJadeReason*` 五常量、`jadeLedgerBalance`（O(1) 读末条冗余，空账本兜底读缓存）、`verifyDerivedBalance`（漂移重锚）、`appendLedgerEntry`（append 条目 + 派生缓存同事务双写 + drift 标记）、`openJadeLedger`（期初开账，delta = balanceAfter = 期初余额自含锚点）。头注从「绝对值覆盖写模型」改写为账本模型 |
| `jade_tx.h` 六事务 | 见 §2 验收②逐事务表 |
| `json_codec.cpp` | `JadeLedgerEntry` 的 `to_json`/`from_json` + GameData 面 `GC_TO/GC_FROM(jadeLedger)` 双向编解码 |
| `dispatch_w4b.cpp` | 事务 5–8 分派参数改（settle 删 `total` 加 `nowMs`；checkpoint 删 `total`；grantAd 删 `totalBefore` 加 `nowMs`）+ 回执增 `drift` |
| `execute_dispatch.cpp` | 事务 3/4 分派加 `nowMs` 参数 + 回执增 `drift` |

六事务改造明细（验收②）：

| 事务 | 原语义（绝对值） | 新语义（账本） |
|---|---|---|
| `purchaseMerchantRefreshTx`（1692） | `jadeSymbols -= cost` | 余额判定读 `jadeLedgerBalance`（O(1)）→ `appendLedgerEntry(-cost, SPEND_MERCHANT_REFRESH)` → 缓存双写；签名加 `nowMs` |
| `purchaseBreakthroughBonusTx`（1693） | `jadeSymbols -= cost` | 同上（`SPEND_BREAKTHROUGH_BONUS`）；签名加 `nowMs` |
| `settleJadeGrantsTx`（1766） | `jadeSymbols = total + toGrant`（运行时绝对值覆盖） | `appendLedgerEntry(+toGrant, GRANT_TIME)`；**删 `total` 参数**（运行时计数退役，无值可传）；签名加 `nowMs`；回执 `total` = 账本余额 |
| `jadeDayResetTx`（1767） | 不动余额 | 余额零变化不落账；成功路径末尾 `verifyDerivedBalance` 派生校验（回执 `drift`） |
| `jadeCheckpointTx`（1768） | 四字段绝对值覆盖写 | **不再写 `jadeSymbols`**（覆盖写通道结构性删除）；继续写 today/accum/anchor；末尾派生校验；**删 `total` 参数** |
| `grantJadeFromAdTx`（1769） | `jadeSymbols = totalBefore + amount` | `appendLedgerEntry(+amount, GRANT_AD)`（白名单直发同 reason 如实记录来源，特权无上限语义保持）；**删 `totalBefore` 参数**；签名加 `nowMs` |

性能护栏（验收④）：扣费/发放路径余额检查 = `jadeLedgerBalance` 读账本末条冗余 `balanceAfter`（O(1)），全程不遍历账本；落账 `push_back` O(1) amortized。无「每扣费遍历账本」O(n) 退化，无 tick 滞后窗口双花复活。

### 1.2 Kotlin 侧（只读镜像 + 服务收敛，K-SS9a/b）

| 文件 | 改动 |
|---|---|
| `GameData.kt` | 新增 `JadeLedgerEntry`（@ProtoNumber 1–4）+ `JadeLedgerReasons` 协议常量对象 + `GameData.jadeLedger`（**@ProtoNumber(240)**，`@ColumnInfo(name = "jade_ledger")`，`PRESERVE_OLD`）；现有玉符四字段编号 220–223 保留不动 |
| `CollectionConverters.kt` | `JadeLedgerEntry` 列表 ProtoBuf+Base64 TypeConverter 一对 |
| `GameDatabase.kt` | `DATABASE_VERSION` 67 → **68**（迁移链已随 SS0 退役，列变更走 destructive 重建，与 SS1 v66→67 同口径） |
| `MigrationRequiredGuardTest.kt` | 基线注记 → v68（实体清单不变） |
| `GameDataFieldPatch.kt` | `f("jadeLedger", ...)` 镜像投影登记（coveredFields 双射面） |
| `JadeSymbolService.kt` | **`@Volatile totalCount` 退役删除**；`syncBalanceFromSnapshot()` 整方法退役删除（绝对值重锚通道消失）；`runtimeState` 删 `total` 字段（`JadeSymbolRuntimeState(today, remainingMs, capped)`）；`deduct(state, amount, reason)` 重写为账本落账（回退臂）；`grantFromAd`/`settleGrants` 回退臂走 `appendLedger`（append + 缓存跟随）；`checkpointNow` 回退臂不再写 `jadeSymbols`；native 臂回执 `drift` → `DomainLog.w` 上报（计数通道过渡，见 §5 登记）；新增 `wallClockNowMs()` 统一取值点 |
| `GameEngineJadePurchaseOps.kt` | 两 native 臂删 `syncBalanceFromSnapshot()` 重锚调用、加 `nowMs` 参数；回退臂 `deduct` 传各自 reason（`SPEND_MERCHANT_REFRESH`/`SPEND_BREAKTHROUGH_BONUS`）；头注改账本模型 |
| `GameEngineLoadDataOps.kt` | `withStartupLedger`（开局三臂单点）写 `OPENING_BALANCE` 期初条目（delta = 期初余额 0、余额不变，落账墙钟 = 开局时刻经注入 `WallClock` 取值） |
| `GameViewModel.kt` | 运行时初始值删 `total`；KDoc 注明余额读数统一走镜像 |

### 1.3 UI 三读数收敛为一（验收⑨）

枚举收口前现状与终态：

| 读数点 | 收敛前 | 收敛后 |
|---|---|---|
| 主界面徽章（`MainGameScreen` → `JadeSymbolBadge`） | 读镜像 `data.derived.gameData.jadeSymbols` | **不变**（本就单一读数） |
| 消耗弹窗红字（`MerchantDialog:154` / `DiscipleDetailScreen:434` → `JadePurchaseFlow`） | 读镜像 `gameData?.jadeSymbols` | **不变** |
| 说明框倒计时（`JadeSymbolDialog`） | 订阅 `runtimeState` 流（只消费 `capped`/`remainingMs`，**未消费 total**） | 不变（消费面本来就是非余额字段） |
| 服务运行时 `@Volatile totalCount`（第三份真相） | `onLoopStart` 恢复 / `settleGrants`·`grantFromAd`·`deduct`·`syncBalanceFromSnapshot` 多点维护 | **退役删除**——余额无内存态，任何时点读账本/镜像 |
| `runtimeState.total` 字段 | 无 UI 消费者（枚举核实），仅测试消费 | **删除**；测试改为读镜像 `jadeSymbols` |

### 1.4 守卫扩面（验收⑦）

`JadeSymbolConsumptionGuardTest` 改写：
- 正则从 2 条扩为 3 条：`copy(jadeSymbols`（原有）+ **`copy(jadeLedger`**（新增，整表替换拦截）+ `.jadeSymbols =`/`.jadeLedger =`（合并为双字段单正则）；
- 白名单分界重划：`JadeSymbolService.kt`（玩法写唯一入口——回退臂 appendLedger）+ `GameDataFieldPatch.kt`（镜像协议面），两白名单均有存在性断言；
- KDoc 全量改写为账本模型（「玉符回涨」根因描述 → 「账本条目缺失 = 黑洞 / 伪造条目 = 凭空发放 / 缓存漂移以账本为准重锚」）。

### 1.5 测试改写（验收⑧，旧语义断言零保留）

| 文件 | 处置 |
|---|---|
| `jade_tx_test.cpp`（C++） | 17 → **20 例**：六处玉符断言改写为 append+派生语义（账本条目逐值断言 reason/delta/balanceAfter/atEpochMs）；新增账本专项 4 例：期初开账锚点、漂移重锚+drift 上报、空账本兜底基准、账本不变式跨事务族 |
| `jade_runtime_tx_test.cpp`（C++） | 18 → **21 例**：settle/checkpoint/grantAd 断言改写为账本语义（删 total/totalBefore 参数链）；checkpoint 拆为「写运行时字段不写余额」+「漂移重锚」两例；新增 `LedgerSumInvariantAcrossRuntimeFamily`（验收⑤守卫断言：派生 == 期初 + Σdelta 跨发放/checkpoint 序列） |
| `JadeSymbolServiceTest.kt`（JUnit） | `runtimeState.total` 消费全改镜像读数；播种改账本期初（`seedJade` helper）；deduct 加 reason；checkpoint 断言语义改写 |
| `JadeNativeTxGateTest.kt`（JUnit） | totalCount 同步断言 → 账本条目断言（SPEND_* reason/余额逐值）；seedJade 账本播种 |
| `JadeRuntimeNativeTxGateTest.kt`（JUnit） | checkpoint「绝对值覆盖写两臂一致」→「不写余额两臂一致」；seedJade 账本播种 |
| `GameEngineJadePurchaseTest.kt`（JUnit） | 同构改写（账本落账断言 + checkpoint 不回涨断言语义更新） |
| `GameEngineCoreJadeReloadInterleavingTest.kt`（JUnit） | 「旧循环 finally 覆盖新档玉符」复现测试**断言反转为守护**：非等待 stop 后 finally **不得**覆盖新档余额（SS9 账本模型下该历史缺陷的写通道结构性消失，`CHANGELOG.md:4519` 级缺陷不可复发）；冷启动窗口断言改账本落账语义 |

### 1.6 文档同步

- `docs/knowledge-base.md`：玉符段「绝对值覆盖写模型 → 账本模型」全量改写（消耗统一通道 5 条 + reason 协议值登记——D-6：不对接 `OverflowMailSender.SOURCE_DISPLAY_NAMES`）；经济基线表「耗（汇）」行更新落账入口。

---

## 2. 验收对照（①–⑩）

| # | 验收项 | 状态 | 证据 |
|---|---|---|---|
| ① | `jadeLedger` append-only；全仓零绕过账本的 `jadeSymbols` 独立赋值 | ✅ | 全仓 grep：`jadeSymbols` 写点仅剩 `GameDataFieldPatch`（镜像白名单）+ `JadeSymbolNonNegativeRule`（core/data 存档自愈，既有设计不在 engine 守卫范围，见 §5）；C++ 写点仅 `appendLedgerEntry`/`openJadeLedger`/`verifyDerivedBalance`；守卫测试 3 正则零容忍 |
| ② | 六事务全部改造 | ✅ | §1.1 明细表；签名删 `total`/`totalBefore`（运行时计数退役后无值可传） |
| ③ | 条目冗余 `balanceAfter` + 派生缓存同事务双写 + 不一致以账本为准并计数 | ✅ | `appendLedgerEntry` 单点；drift 经回执 → Kotlin `DomainLog.w`（StorageMetrics getter 归 SS3，§5 登记） |
| ④ | O(1) 余额检查保持；双花不复活 | ✅ | `jadeLedgerBalance` 读末条冗余 O(1)；ctest 全绿含购买/发放路径断言 |
| ⑤ | 期初开账（v2 适配）+ 派生 == 期初 + Σdelta 守卫断言 | ✅ | 新档 `withStartupLedger` 写 OPENING_BALANCE（三臂单点）；守卫断言 = C++ `LedgerOpeningBalanceEntryAnchorsDerivedBalance` + `LedgerSumInvariantAcrossRuntimeFamily` + `expectLedgerInvariant`（Σdelta == 末条 == 缓存，逐事务断言）；设计差异说明见 §5「期初开账落点」 |
| ⑥ | 协议字段 240+ 段 + 双向编解码 + coveredFields 登记 | ✅ | `GameData.jadeLedger` = @ProtoNumber(240)；条目 1–4；`GC_TO/GC_FROM` 双向；`GameDataFieldPatch.f("jadeLedger")`；220–223 未动；编译期 `GameDataFieldPatch` fail-fast 红线（缺登记即抛错）通过 |
| ⑦ | 守卫正则扩 jadeLedger + 白名单重划 + GameDataFieldPatchGuardTest 绿 | ✅ | §1.4；engine JVM 面全绿（含 `GameDataFieldPatchGuardTest`） |
| ⑧ | 两侧对拍齐备（GTest 35 例改写 + JUnit Diff* 全绿） | ✅ | GTest 20+21=41 例新语义；JUnit Diff*Test 跨语言对拍全绿（§4）；旧语义断言零保留 |
| ⑨ | UI 三读数收敛为一 | ✅ | §1.3 枚举表；`totalCount`/`runtimeState.total`/`syncBalanceFromSnapshot` 全部退役 |
| ⑩ | 正式重录窗口（全局唯一一次） | ✅ | 已执行：`ctest -R Determinism` 2/2 Passed，实际 digest 与 golden **逐位一致**——重录产物 hash = `0x8877d164f6bfe1fc`（**无平移，基线维持**）。SS1 去槽与 SS9 账本两次导出形状变更均不触及 FP 确定性转录面（探针转录哈希弟子列/战斗/RNG 序列，不含存档 JSON 形状；`kProbeVersion` 未动）。G 批 `disciple_factory_test` 金序列两条随 ctest 全绿确认无平移。**此后任何批次禁止再重录** |

**不做项核实**：白名单发奖语义零改动（`grantFromAd` 入口与无上限语义保持，账本仅如实记录 `GRANT_AD` 来源——B2 拍板）；无付费通道；玉符不接 `withTrackingSource`/`OverflowMailSender`；`jadeSymbolsToday`/`jadeDayAnchorMs`/`jadeAccumMs` 保持独立墙钟日闸语义未并入账本（D-7）。

---

## 3. 决策落点（D-1~D-7）

| # | 决策 | 落点 |
|---|---|---|
| D-1 | 账本真源在 C++ | `state.gameData.jadeLedger`（镜像链：`GameDataFieldPatch` 投影 ↔ `json_codec` 双向 ↔ `StateBaseline` 块级 diff 同形 JSON 自动纳入基线比对面） |
| D-2 | 条目 + 派生缓存同事务双写 | `appendLedgerEntry` 单点；`balanceAfter` 冗余 |
| D-3 | 期初开账 = 新档初始化 | `withStartupLedger` 三臂单点（v2 删档适配，「老档迁移期初」语义随 SS0 失效）；设计差异见 §5 |
| D-4 | 重录窗口本批执行 | §2 验收⑩；digest 无平移，基线冻结 |
| D-5 | 白名单 = 特权不是缺陷 | `GRANT_AD` 如实记录来源，语义零改动 |
| D-6 | reason 枚举登记 knowledge-base | 已登记（不对接邮件来源命名表） |
| D-7 | 今日计数/日锚/周期累计不并入账本 | 三字段保持独立语义，六事务内照常维护 |

---

## 4. 验证（门禁实跑数值，终树实测）

| 门禁 | 结果 |
|---|---|
| 桌面 C++（cmake + ctest，llvm-mingw，含 bench） | **1538/1538 全绿**（基线 1532 + jade 净增 7 − 兜底守护测试退役 1 = 1538，逐项对账见 §5） |
| 桌面 JNI 重建（`build-desktop-jni.ps1`） | ✅ 259 源文件同源指纹 |
| JVM 五模块（`--max-workers=1 --rerun-tasks`，指向本树 .so） | **7271 tests / 0 failures / 0 errors / 22 skipped 全绿**（domain 1594 / engine 3023 / data 666 / game 972 / app 1016；含全部 `Diff*Test` 跨语言对拍）BUILD SUCCESSFUL 10m54s |
| `compileReleaseKotlin` / 测试源编译 | ✅ BUILD SUCCESSFUL |
| detekt 五模块（domain/engine/data/game/app） | ✅ BUILD SUCCESSFUL（baseline 零新增） |
| `gen-action-ids.mjs` 零漂移 | ✅ `git diff --exit-code`（action_ids.h + ActionIds.kt）——本批无新 ActionId（六事务复用 1692/1693/1766–1769） |
| `check-jni-count.mjs` | ✅ 87/87，双桥无扩散（无新增导出，JSON 事务通道复用） |
| `check-agent-instructions.mjs` | ✅ EXIT=0（改 `knowledge-base.md` 后必跑项） |
| 重录窗口 | `ctest -R Determinism` 2/2 Passed，digest `0x8877d164f6bfe1fc` 无平移（基线冻结） |

---

## 5. 未完成 / 登记

1. **跨批登记（SS3）**：账本↔派生缓存不一致计数的 `StorageMetrics` getter/上报面归 SS3——本批计数通道 = C++ 回执 `drift` 字段 + Kotlin 臂 `DomainLog.w`（服务 TAG `JadeSymbolService`），无持久计数器。
2. **跨批登记（SS10）**：玉符账本的玩家可见 changelog 并入 4.2.00 唯一条目（本批不动版本号、未写 changelog）。
3. **期初开账落点（设计差异说明，已核实）**：任务书验收⑤原文「新档创建（C++ 开局路径）即写一条」。工程现实 = 新档 GameData 由 Kotlin 构建后经 `importToNative` 全量导入（C++ 无独立开局业务路径），期初条目落在 Kotlin 开局资产单点 `withStartupLedger`（三臂共用，与起始灵石/星级账本同点，防臂间口径漂移——该单点由 `GameEngineCoordinationTest` 守护接线）。实施中曾在 C++ `importStateInternal` 加过导入态空账本兜底，实测发现它**改写导入态导致对拍 round-trip 不等价**（全部 Diff*Test 红）——C++ 导入协议必须对合法输入逐位保真（`normalizeLedgers` 等既有归一化的触发面均为超限数据，而空账本是合法形态，兜底必触发）。兜底已删除，期初开账职责完全归开局单点；未开账导入态的首次落账走 `jadeLedgerBalance` 空账本兜底（以缓存为基准，`LedgerEmptyFallbackBasesOnDerivedCache` 锁定该形态语义）。SS0 删档后生产链上所有新档必经 `withStartupLedger`（带期初锚点），空账本形态仅存在于测试种子。
4. **登记（不变式边界）**：空账本（无期初锚点）直接落账形成的账本满足「缓存 == 末条 balanceAfter」但不满足「Σdelta == 末条」（首条即增量条目）——这正是期初条目存在的意义；`expectLedgerInvariant` 守卫断言只在开账后形态断言 Σdelta 恒等式。
5. **登记（自愈规则与账本的交界）**：`JadeSymbolNonNegativeRule`（order=23）钳制的是派生缓存 `jadeSymbols`（SS9 后自动就是「钳制派生值」，方案要求满足，order 未动）；其修复若致缓存与账本不一致，由下一次账本事务以账本为准重锚——账本为准语义的正确表现，规则本身无需改。
6. **登记（账本增长治理）**：账本条目稳态约 20–65 条/天（时长 20 上限 + 广告 + 消耗），量级数年后单列 TEXT 可观；`jadeLedger` 现为 game_data 主表列（ProtoBuf+Base64 converter）。截断会破坏不变式（不可简单回缩），分块进 heavy_data 或归档治理留待 SS3 持久化面收口评估（本批不做，YAGNI）。
7. **风险（已核实）**：`GameEngineCoordinationTest` 断言三臂共用 `withStartupLedger`（「漏接即退回哨兵值」）——本批给它加了期初条目参数，三处调用点已同批接线（编译器背书）；期初落账时刻经引擎 `wallClock` 取值，该测试的 mock core 已同批补 `wallClock` stub（与 `JadeNativeTxGateTest` 既有先例同型，mock 未 stub 返回 null 是 Mockito 默认行为，生产链 `GameEngineCore.wallClock` 恒有注入值）。
8. **风险（推测，待真机）**： JNI 通道 `tryExecuteNative` 为 JSON 参数面，六事务参数形状变更（删 total/加 nowMs）已在两侧同批同步；真机 native 臂行为面建议随下一批真机验证点一并复验（与既有 pending-device 清单同置）。

## 6. 工作树卫生

- 构建副产物（`atlas-rgba-manifest.json`/`scene_uv_tables.h`/`sprite-uid-map.json`）提交前 `git checkout --` 还原；
- 一次性脚本零残留（实施中的临时 patch 脚本用毕即删）；
- Room schema `68.json` 由 `.gitignore:171` 忽略（SS0 删档纪律，schema json 不再入库），不入批。
