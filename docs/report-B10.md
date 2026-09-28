# B10 交付报告——文档与规范（实时结算线末批，结算改造 2026-09-27 §10 B10）

> 派发件：`docs/realtime-watch/batch-B10.md`；方案：`docs/realtime-settlement-plan-2026-09-27.md`
> §3.6（文档与规范清单）/ §4.6（既有规则冲突裁决表）/ §10 B10 行；台账：`docs/realtime-watch/DISPATCH-LEDGER.md`。
> **验收标准：规范与代码零冲突；门禁绿。**

## 一、交付内容（§3.6 条目逐项对照）

本批为**纯文档批**：Kotlin/C++/脚本零改动（`git diff --stat` 16 文件全为 `.md`/`.json` 文档面）。

| §3.6 条目 | 处置 | 落点 |
|---|---|---|
| `rules/expansion-playbook.md`（第 7 项 + 离线收益预留节） | ✅ 改写 | 第 7 项「禁止以现实时间为准」→「进度锚定唯一权威时间轴 `elapsedGameMs`，日历为投影，禁第二套时间真相源」（§4.6 裁决原文：改写表述不删约束，①单轴 ②禁另起循环 ③常量栈换算三防线全保留）；第 2 项四层名改双轨语义；「离线收益预留（🟡）」节转「离线收益（✅ 已定稿，B7 落地）」——口径/接入点/扩展纪律/经济审计四段 |
| `rules/economy-design.md` §4（离线收益数学） | ✅ 定稿 | 「🟡 预留」→「✅ 已定稿（B7 落地）」：时段计量（`lastSaveTime`→读档墙钟差）、≤12h 全额（1x 与在线速度档解耦）、12–24h 段 50%（整数分子制）、24h 硬顶（注入总量上限 18h 游戏时间 = 64_800_000 游戏毫秒）、floor 到旬、回拨按 0；收益内容边界（连续积分项重放 / 判定轨 0 次 / RNG 零消耗）；接入点（`GameEngineCoreOfflineOps` 两段式 → `GameCore::injectOfflineGameMs`）；留存设计（12h 回访节奏 + D4 校准档）；经济审计义务（折算改动必复跑 `OfflineInjectionTest` 注入≡分帧逐位对拍 + 新产出项登记经济基线表）。文件头「当前仅灵石单一货币」同步修正（玉符在册） |
| `docs/architecture.md:75-146`（惰性结算引擎章节） | ✅ 改写 | 章节改双轨时间模型：层级图 L0–L4 逐层新语义（L0 权威轴+投影 / L1 连续积分轨+判定轨 / L2 毫秒差分 / L3+L4 事件派发）+ 新增「时间语义不变量」五条（INV-1/2/3、离线注入、灰度旗标 `realtimeAccrual` 默认 false）；核心原则「时间戳懒惰计算」「每旬 5 项最小检查」两条按毫秒时间戳/判定轨窗口项更新；年变分帧小节语义不变保留 |
| `docs/knowledge-base.md:685,687,689-707` | ✅ 改写 | 留存手段清单三行：存档（现实墙钟节拍自动存档回归，§2.6）、离线收益（已落地全口径 + 代码位）、游戏时间流速（权威轴 + 常量栈双端锚点）；经济基线表灵矿场行补离线毫秒差分（B7 注入照常结算） |
| `docs/knowledge-base.md:722`（玉符墙钟豁免引用） | ✅ 更新 | 引用换锚：L22 条款已改写为第 7 项新表述，豁免论证实质不变仍成立（玉符非进度系统、单调时钟发放、与游戏内进度解耦） |
| `docs/cpp-engine.md`（C++ 结算入口清单） | ✅ 改写 | 基线块新增「实时结算改造收官（2026-09-29，B1–B10）」条目：**结算入口清单八项**（`advanceByGameMs`/`accrue`/`settlePhase`/`settleMonth`/`settleYear`/`injectOfflineGameMs`/`time_units.h` 常量栈/`time_system.h` 投影）+ 与旧 `advance(wallDeltaMs)` phaseCap 丢弃累积器的取代关系（B9 时基统一）；目录结构补 `time_units.h`；架构图 JNI 桥行补结算族五入口；§7「保持不动」行离线收益措辞更新。历史批次记录按文档既定惯例**不回改**（下沉时点归档） |
| `docs/ui-read-surface.md`（新增 UI 可读时间字段登记） | ✅ 登记 | §2.1 镜像合法面补 `elapsedGameMs`/`lastSettleGameMs`（C++ 协议面 `json_codec.cpp:1179/1273` 实证）；§3.2 派生 UI 流补三行：`sectClock`（B8 HUD 投影流）/ `monthProgressFraction`（B8 时间进度，`TimeProgressUtil` 合成、禁 UI 自算第二份）/ `offlineReturnReport`（B7 云游归来，非镜像运行态） |
| `docs/threading-contract.md`（离线注入跨线程路径） | ✅ 已登记（B7）+ 头部补记 | 表四「离线收益」行 B7 批已入库（先登记再实现纪律已履行）；本批把头部「更新日期」行补记 B7 登记说明，消除头/表日期不一致 |
| `docs/platform-abilities.md`（时间端口 iOS 对等） | ✅ 登记 | 「时间源」行扩为四端口：① `TimeSource` 接口 ② C++ `Clock` 端口（引擎内禁直取系统时间）③ 单调时钟 `SystemClock.elapsedRealtime`（权威轴计量基，防改墙钟加速）④ 现实墙钟 `currentTimeMillis`（仅离线计量与显示）；iOS 对等：③ `clock_gettime(CLOCK_MONOTONIC)`/`mach_absolute_time`、④ `NSDate.timeIntervalSince1970`——均平台标准 API |
| 双 CHANGELOG | ✅ 收口 | 外部 `CHANGELOG.md` 增「实时结算线 B10 批」小节（14 项文件级明细）；游戏内 `changelog_entries.json` 当前版本条目（4.01.14）末尾追加 1 行玩家文案（内部整理、玩法与数值无变化）；**版本号未动**（`version.properties` 4.01.14 原值，B 线惯例 = 并网后由用户拍板发版号） |

### §4.6 冲突裁决表的规范侧落地（清单外必改项，否则「零冲突」不成立）

| §4.6 行 | 落点 |
|---|---|
| 根 `AGENTS.md` §3「惰性结算四层」 | →「实时结算四层」双轨名 + 常量栈换算纪律 |
| 根 `AGENTS.md` §3「存档为纯手动 — 禁止重新实现自动保存，禁止 `autoSave*` 命名」 | →「存档入口纪律」：手动 + 云存档 + **现实墙钟节拍自动存档**（每 10 现实秒至多一次、三前置门控）；禁止的是复活旧月变触发式 `AutoSaveTrigger` 体系；命名统一 `realtimeAutoSave*` 前缀（§2.6 裁决：该例外早已存在，规范同批登记） |
| 根 `AGENTS.md` §3「扩展性预留」行 | 离线收益标注已落地（口径指向 economy-design §4） |
| `android/core/engine/AGENTS.md:19-23`（四层语义） | → 按 §4.6 裁决改写：四层结构保留、层内语义双轨化 + 常量栈 + 离线注入指引（压缩至 3174 字节，见 §三 门禁——规则⑤链路预算回归绿） |
| `android/core/data/AGENTS.md:28`（存档为纯手动） | → 存档入口三件套口径（与根 AGENTS.md 同步） |
| `CODE_WIKI.md:203`（存档为纯手动防复发护栏） | → 同口径修订（保留历史依据引用） |
| `docs/architecture.md:330-334`（离线收益预留） | → 「离线收益引擎（✅ 已落地 B7）」：口径/两段式注入/扩展纪律 |
| `docs/architecture.md:457`（存档为纯手动节） | → 「存档入口（§2.6 裁决修订）」：显式标注为产品决策修订、非旧自动存档体系复活（三前置门控/合并窗/失败口径沿用） |
| `rules/ad-cooldown.md`（离线收益频控预留） | → 精确化：当前为读档自动注入式（无领取动作无频控面），频控预留仅适用未来领取式形态 |
| `rules/pr-review-checklist.md:66` | 「进度锚定游戏时间」→「进度锚定权威时间轴」（与 playbook 第 7 项同步） |

### 口径核对（派发件附录 8 条逐项对齐）

1. **Room v61 现状**：本批文档表述全部按 v61 口径写（architecture/knowledge-base 存档行未写死版本号，迁移规则指向 `rules/database-migration.md`）；装备阶段 v62 起顺延仅在台账 §8，未写入任何规范文本（防跨线漂移）。
2. **死值退役勿复活**：全批文档零提及 `cultivationCompletionPhase`/`maxAge`/四死常量（改写面与其无交集）；economy-design §4 收益内容边界表述与 `accrueContinuous`/`accrueMonthlyContinuous` 现函数族一致。
3. **两套时基统一口径**：architecture.md 不变量 INV-2、cpp-engine.md 结算入口清单均按「`advanceByGameMs` 统一两臂、cap 只裁判定执行、时间零丢失」语义写（`settlement.h` 头注释为基准）。
4. **常量双端锚点**：文档涉常量处一律标注双端守卫（`GameTimeUnitsParityTest`/`time_units_test.cpp`/`game_config_parity_test.cpp`/`ConfigCppConstantsParityTest`），不另立第三份常量表。
5. **年贡字段退役口径**：本批文档无年贡表述面（不需涉及）。
6. **途中发现三项**：未顺手处置（见 §四 移交）。

## 二、验收标准对照（§10 B10 行）

| 验收标准 | 结果 |
|---|---|
| **规范与代码零冲突** | ✅ §3.6 十项 + §4.6 规范侧八处全部落地（§一 两表）；全文档口径与代码实证一致（旗标默认 false、离线四常量值、`offlineGameMs` 折算式、`advanceByGameMs` 语义、JNI 入口名、Room 字段面均逐项对照源码核验）；旧口径残留扫描（`禁止以现实时间为准`/`后台纯暂停`/`每旬检查`/`无自动存档`/`autoSave* 禁令`/`离线收益预留`）grep 清零（仅存历史归档记录，按各文档既定「不回改」惯例保留） |
| **门禁绿** | ✅ 见 §三 |

## 三、门禁实测（原数字）

在 worktree `android/` 下执行（ctest 在 `gamecore/build`；本批未触 C++，`.so` 复用 B9 产物 mtime 2026-09-28 22:51）：

| 门禁 | 结果 |
|---|---|
| `compileReleaseKotlin`（带 jni.path） | BUILD SUCCESSFUL（5s，112 tasks：2 executed / 110 up-to-date） |
| 六模块 `testReleaseUnitTest --max-workers=1 -Dgamecore.jni.path=…`（含 feature:game） | BUILD SUCCESSFUL（**3m 26s**，222 tasks：18 executed / 203 up-to-date）；结果 XML 汇总 **7550 / 0 失败 / 18 skipped**（app 1030/domain 1587/data 821/engine 2964/ui 155/feature:game 993——与 B9 基线逐位一致；因本批零代码改动，五模块 UP-TO-DATE 复用 B9 实跑结果，诚实登记非全部重执行） |
| engine Diff 门（定向 `--tests "*Diff*" --rerun-tasks` 打同一 `.so`） | BUILD SUCCESSFUL（**1m 59s**，53 tasks 全 executed 实跑）；**Diff\* 52 类 271 用例 / 0 失败 / 0 跳过** |
| `lintRelease detekt` | BUILD SUCCESSFUL（**1m 54s**）；detekt 六模块 0 违规、baseline 未动 |
| 桌面 ctest 全量（llvm-mingw PATH，GAMECORE_BUILD_BENCH=ON） | **1494/1494 全绿**（与 B9 基线一致；**首跑即绿**无 bench 噪声——安静窗口，49.74s；bench 三例含 `SegmentUnderBudgetAt5000` 全过） |
| `node scripts/check-jni-count.mjs` | **88/88**（total=88 在基线内，双桥无扩散） |
| `node scripts/check-agent-instructions.mjs` | 全绿：预算闸 27208/32768、单一真源、引用无死链（42 篇/462 引用——本批新增 18 条引用全部可解析）、路由表完整、**规则⑤链路最坏 android/core/engine/AGENTS.md = 32744/32768 回绿** |

门禁途中事项（诚实登记）：

1. **规则⑤链路超预算（已修）**：engine/AGENTS.md 结算层级节首版 +750 字节 ⇒ 根+android+engine 链路 32913 > 32768（⚠ 告警级，门禁输出从 ✗ 死链外多出一条 ⚠）。压缩措辞（语义零删）至 3174 字节 ⇒ 规则⑤恢复 ✓（最坏链 = engine 32744/32768，仍在预算内）。
2. **规则③死链自愈**：cpp-engine.md 新条目引用 `docs/report-B10.md` 时报告尚未落盘 ⇒ 死链 1 处；报告（本文件）落盘后复跑绿。
3. **atlas 幽灵 diff 再现**：lint/构建触碰 `atlas-rgba-manifest.json`（派发件预告的构建副作用）——`git checkout --` 还原，未混入收官笔。
4. **changelog JSON 手术**：游戏内 `changelog_entries.json` 采用文本级手术追加（首次 `json.dumps(indent=2)` 整文件重排 2547 行 diff 已 `git checkout` 还原重做）；终态 diff 恰 2 行（1 改 1 增），JSON 解析合法、data[0]（4.01.14）changes 52→53。

## 四、途中发现移交（不属本批清单，未顺手处置）

与派发件附录第 8 条一致，B9 移交的三项维持原状待后续批次裁决：`processAutoAlchemy` 生产零调用、bench `overBudgetCount==0` 断言噪声脆弱、C++ 年变 T1 缺 discipleAging（口径已登记 year_settlement.h 头注释）。本批途中**无新增**发现。

## 五、合并手术预登记（台账 §7 看护执行）

- 本批 16 文件全部为 `rules/`/`docs/`/双 `AGENTS.md`/`CODE_WIKI.md`/双 CHANGELOG——与主树装备方案文档（`docs/design/`）无交集；`SaveLoadViewModel*` 三冲突文件本批未触碰，§7 手术预期不变。
- **冲突预警**：根 `AGENTS.md` 本批改动 §3 四行（实时结算四层/线程契约行未动/存档入口纪律/扩展性预留）——若主树在并网前对该文件有其他线改动，合并时以**本线语义为准**手工合流（§4.6 裁决为准绳）；`docs/knowledge-base.md`/`docs/architecture.md` 同理。
- 版本号：`version.properties` 未动（4.01.14）；主树现值 4.01.16，并网后发版号由用户拍板。
