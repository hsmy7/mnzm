# W4 剩余工作实施文档（派工用）

| 项 | 内容 |
|---|---|
| 文档性质 | **实施文档（派工用）**：把 W4 三批集成后的**全部剩余工作**整理成可逐项照单执行的实施单，含技术方案、影响范围清单（`文件:行号`）、测试方案、验收判据、风险与兜底、盲区自查 |
| 依据 | [handover §2.68](../cpp-migration-handover-m0.md)（集成收口与 3 处复验缺陷）｜[handover §4.1/§5](../cpp-migration-handover-m0.md)（遗留与待拍板）｜本目录 [README](README.md) §8（汇流波 W4-D）/§9（非并行轨）/§12（技术债）｜[ADR reverse-channel-elimination](../adr/reverse-channel-elimination.md)｜[ADR rng-determinism-remediation](../adr/rng-determinism-remediation.md) |
| 当前基线 | `main` = `b961a356c`（W4-00 → 三批 → 集成收口，全部门禁绿）；门禁数值见 [handover §3](../cpp-migration-handover-m0.md) |
| 使用方式 | **按 §1 的顺序执行**；每项照 §2 的实施单落地，按 §3 命令自证，按 §4 门禁验收。**每项完成后回写 handover §2 对应小节 + 勾销 §4.1 条目** |
| 强制纪律 | ① 禁整仓 `git add`（逐 pathspec 提交）；② 生成物冲突**一律重生成**（禁手工解）；③ 每个里程碑 **tag + `git bundle`**（`.git` 曾两次被毁）；④ 测试**必须串行** `--max-workers=1`；⑤ 提交说明用中文，写明**根因/口径/实测数值** |

---

## 0. 一页速览

| # | 任务 | 类型 | 前置 | 主要面 | 验收一句话 |
|---|---|---|---|---|---|
| **D1** | `batch-22a` debug 埋点小批 | 串行（首项） | — | `StateSyncService`（反向信身体积/耗时）、`GameEngineCoreAuthoritativeOps`（每旬镜像分段计时） | debug 构建有埋点、release 零开销；真机可采"关闭前基线" |
| **D2** | `w3-11` 月年编排残差 + §2.3 宿主文件族调用点清理 | 串行 | D1 | `GameEngineCoreMonthOps/YearOps`、`CultivationEventMonthlyOps`、`MonthSettlementResidualExecutor`、`YearSettlementResidualExecutor`、`GameEngineCore` | 扇出项逐条判定落地；宿主族解冻后清理完成；引擎全量绿 —— **✅ 已实施（2026-09-15，§2.73，tag `w4-rem/03`）** |
| **③B** | `PresentationRandom` 按场景派生（跨会话一致） | **可并行**（文件面与 D 系列不重叠） | — | `PresentationRandom.kt` + 6 个场景调用点（见 §2.B） | 同一存档同一场景恒定、跨会话一致；决策流零扰动 |
| **②C** | `TimeSystem.onPhaseTick` 迁入测试源集 | 串行（**须在 D3 之前**） | — | `TimeSystem.kt` + 6 个 Diff 测试 | 生产面零死方法；对拍语义零变更（引擎全量用例数不变） |
| **D3** | `DiffAuthoritativeTickTest` harness 对齐生产 | 串行 | D2、C | `DiffAuthoritativeTickTest`、`DiffSurfaceAssertion`（含**地形 2 字段退出排除面**） | harness = 生产口径；4 个覆写字段重评；地形 2 字段参与全字段对拍 —— **✅ 已实施（2026-09-15，§2.74，tag `w4-rem/04`）** |
| **D4** | `w3-13` 反向通道删除（终局） | 串行 | D1–D3、B、C | `captureReverseDirty`、`ReverseDirtyAccumulator`、`applyDirtyToNative`、信封构建、`GameCore::applyReverseDirty`、`ReverseChannelPolicy` | 五步流程走完；关闭域写入检测零命中；状态指纹零差异 —— **⚠️ 阶段 A+B 已实施（2026-09-15，§2.75，tag `w4-rem/05`）：观察窗仪器落地 + 100 旬停发对拍绿；删除步判定 = 阻断（稳态写者未清零，完成路径见 handover §2.75）** |
| **D5** | 死代码清零 + 守卫面收口 | 串行 | D4（地形字段落定） | 见 §2.D 清单 | 三份零调用者清单产出并逐条删除；守卫只缩不增 |
| **D6** | 文档与版本收口 | 串行（末项） | D1–D5、B、C | handover §3/§4.1/§5/§6、`ui-read-surface.md` §4.4、`cpp-engine.md` §9、`CODE_WIKI.md`、双更新日志 | 全文档口径与实测一致；双日志同批更新 |
| **E** | 非并行轨（真机/WS-4/WS-1 已决策） | 不派工 | — | 见 §2.E | 需用户决策或硬件，**不得混进批次** |

> **最小串行链**：`D1 → D2 → C → D3 → D4 → D5 → D6`；**B 可插在任意空档**（须在 D6 前完成）。
> **为什么 C 必须在 D3 之前**：C 改的 6 个文件里有 4 个正是 D3 要重写的 harness 文件（`DiffAuthoritativeTickTest` / `DiffPhaseSettlementTest` / `DiffYearSettlementTest` / `DiffMonthSettlementFixture`）——先做 C（纯搬运、语义零变更）可让 D3 在"基准已就位"的树上改管线，避免两处同时改同一批文件。

---

## 1. 执行顺序与依赖图

```
D1 埋点 ──► D2 w3-11 残差 + 宿主族清理 ──► C onPhaseTick 迁测试源集 ──► D3 harness 对齐 ──► D4 通道删除 ──► D5 死代码 ──► D6 文档
                                                                    ▲
B PresentationRandom 按场景派生（独立文件面，任意空档插入，D6 前完成）
E 非并行轨：真机验证（需设备）｜WS-4（需玩法文档）｜WS-1 阶段 3（✅ 已决策不做）
```

**冲突矩阵（改前必须核对，避免两个执行者撞同一文件）**：

| 文件族 | D2 | C | D3 | D4 | B |
|---|---|---|---|---|---|
| `GameEngineCoreMonthOps/YearOps`、`CultivationEventMonthlyOps`、两个 `*ResidualExecutor`、`GameEngineCore` | ✅ 独占 | — | — | — | — |
| `DiffAuthoritativeTickTest` / `DiffPhaseSettlementTest` / `DiffYearSettlementTest` / `DiffMonthSettlementFixture` / `DiffTimeTest` / `SettlementTransactionMergeTest` | — | ✅ | ✅（**串行**） | — | — |
| `DiffSurfaceAssertion` | — | — | ✅ | — | — |
| `StateSyncService` / `GameStateStoreImpl` / `ReverseChannelPolicy` / `GameCore::applyReverseDirty` | — | — | — | ✅ 独占 | — |
| `PresentationRandom.kt` + 6 场景调用点 | — | — | — | — | ✅ 独占 |

---

## 2. 逐项实施单

### 2.A W4-D 汇流波（D1–D6）

**权威定义见 [README §8](README.md#8-汇流波-w4-d串行)**（六项的目标/前置/关键点已在方案内写全，本文档不复制，只补执行细节）：

| 序 | 补充执行细节（方案 §8 之外的操作性内容） |
|---|---|
| D1 | **✅ 已实施（2026-09-15，见 handover §2.72，tag `w4-rem/02`）**：两文件 `BuildConfig.DEBUG` 门控落地——`StateSyncService`（`logReverseEnvelopeProfile`：信封总体积 + 分段体积 + 构建/发送耗时）与 `GameEngineCoreAuthoritativeOps`（`PhaseSegmentTimer`：baseline/settle/mirror.inc·full/breakthrough/boundary/reverse，µs 精度每旬一行）。**release 无埋点已实证**（`assembleRelease` 后 4 DEX 字符串扫描零命中 + debug 产物对照命中）；engine 全量 3303/303/0/0 基线持平。真机 logcat 采样归 §2.E 非并行轨 |
| D2 | **✅ 已实施（2026-09-15，见 handover §2.73，tag `w4-rem/03`）**：①引导领奖下沉（`GUIDE_REWARD_CLAIM_TX=1830` + `guide_reward_tx.h`——batch-18a"Kotlin 注册表不可复刻"判定被推翻：25 任务条件全为结构化数据谓词，9 类条件逐字复刻；可行性预检 + SYSTEM 2×nextLong UUID 复刻 ⇒ 四象限抽取增量恒 +2，B3 通道预检同款）；②扇出项逐条判定收口（purchaseLogs/丧亲 = lifeEvents 非协议列留 Kotlin；秘境邮件 = DAO 通知；死亡链袋物化 = 平台效应链不迁——openStorageBag 逐件入库仍为两臂共用稳态写者，物化下沉无关闭收益；兑换码登记不下沉）；③B4 转入项实裁（附庸年贡/脱离 C++ 已在位——开臂即双重执行，不占号）；④宿主族解冻核对（16 月子事件 + 年 T1 全部已在位，零死调用点；`missionCheck` = AUTHORITATIVE 下防御性 no-op 保留；三处 KDoc 陈旧扇出描述修正）；⑤关闭动作：`guideClaimedRewardIds` 转入 W4-D closedUnits（BOUNDARY 域首单元），GuardTest 6 用例绿。门禁：桌面 **1417/1417**（+10）｜引擎 **3305/304/0/0**（+2）｜`:core:domain` 1758/0｜detekt 六绿｜NDK+lint 绿｜生成物零漂移（196 动作/maxId=1843） |
| D3 | **✅ 已实施（2026-09-15，见 handover §2.74，tag `w4-rem/04`）**：①harness Side A 边界改生产同款——桌面 JNI + `DiffRngBridge` 新增 `nativeCoreSettleMonth/Year`（与生产同协议）+ Kotlin 残留执行器装配；对齐红点逐条归因**全部为 harness 缺装配**（真实 `DiscipleLifecycleProcessor`/`MerchantAndRecruitService`+`ManualDatabase` 注入/cave Processor Provider 实例化/收购 id 排除面），零两端真分叉；②4 个覆写字段（`annualAlchemyCount`/`yearlyReports`/`availableMissions`/`spiritMineLastSettledMonth`）重评后全部转入 W4-D closedUnits（守卫 6/6 绿）；③地形 2 字段退出排除面——`mapSeed` 非零 + `backfillTerrainOnBoot`（生产同款）+ 删 `DiffSurfaceAssertion` 两分支，`terrainTiles`/`mapGenVersion` 参与全状态对拍。门禁：桌面 **1417/1417**｜引擎 **3305/304/0/0**（持平，47 `Diff*` 全绿）｜`:core:domain` 1758/0｜detekt 六绿｜NDK+lint 绿｜生成物零漂移（196 动作/maxId=1843）。**登记**：空世界兜底分支（worldMapSects 空，生产不可达）双臂招募差异不展开，`lastRecruitYear=4` 规避（DiffYearSettlementTest 同款）；harness 场景红点归因明细见 §2.74 |
| D4 | 严格按 ADR §4 五步：**先禁用（信封体积=0 但代码在）→ 跑完整业务周期（≥1 游戏年 + 离线结算 + 跨旬月 + 存档写读，期间关闭域写入检测零命中）→ 状态指纹零差异（同档同输入 ≥100 旬）→ 删前打归档 tag → 再删除**。删除面/保留面见 §8；**防复发**：镜像只读契约（结构性/编译期优先）+ "稳态零写入断言"进长期 CI —— **⚠️ 阶段 A+B 已实施（2026-09-15，§2.75，tag `w4-rem/05`）**：停发仪器（`ReverseChannelPolicy.reverseTransportEnabled`）+ 100 旬停发对拍用例进 CI（零发送/检测零命中/指纹零差异全绿）；**删除步判定 = 阻断**（稳态 Kotlin 写者未清零：交谈效果/任务派遣/存档前自愈等——硬前置"关闭清单 = 全部传输单元"不成立），逐写者完成路径见 handover §2.75④，收口后重启五步④⑤ —— **✅ 第 1 项已实施（2026-09-15，§2.76，tag `w4-rem/06`）：交谈效果写面下沉（`DISCIPLE_CHAT_EFFECT_TX=1860`，段 1860–1869 首用，零 RNG 参数化事务）；审计修正：通道关闭还依赖任务域收口（`startMission` 写槽位协议列）与 lifeEvents 投影检测裁决（§2.76 审计先行）——**✅ 均已实施（2026-09-15，§2.77，tag `w4-rem/07`）：`MISSION_START_TX=1861` + `DISCIPLE_CHANNEL` 关闭（守卫红线翻转）+ 投影转非捕获路径 + 检测 AUTHORITATIVE 门控；剩余开放面 = aiSectDisciples 段、9 类集合、retained 字段族（第 3/4 项） |
| D5 | 第一件事是**先补清单**（handover §5④ 的"18+11+5 处零调用者站点"仓库内**无分项清单**，无基准不可验收）：用 `git grep` 产出"生产调用 0 / 测试引用 n"三份清单再逐条删除；其余见 §2.D |
| D6 | 双更新日志**必须一起**更新（`CHANGELOG.md` + `changelog_entries.json`；同版本条目追加到 `changes` 末尾，**禁新建同版本第二条目**）；`version.properties` 是否递增**由用户决定** |

**D3 并入项（地形 2 字段退出对拍排除面）—— 完整实施单 —— ✅ 已实施（2026-09-15，§2.74）**

> **实施结果**：harness 补生产同款 boot 回填（`mapSeed=987654321` + `backfillTerrainOnBoot` 经 `SectTerrainBridge`）+ 删除 `DiffSurfaceAssertion` 两排除分支后，47 个 `Diff*` **直接全绿**（预期"先红"未出现——其余 45 个场景 mapSeed=0 恒无段，主场景两端经"存的地形恒优先"持有同段逐位一致）；键存在性断言（`:48`）对两字段生效。跨语言生成等价仍由 `terrain_freeze_test.cpp` + `DiffSectTerrainTest` 承担。

- **背景**：`DiffSurfaceAssertion.kt:76-77` 把 WS-5b 的 `terrainTiles` / `mapGenVersion` 列入 `diffIsMirrorGeneratedField` ⇒ 遍历到即 `continue`，**既不校验值一致、也不校验 Kotlin 侧存在**。原因：harness 的 Kotlin 侧不跑 boot 回填，Kotlin 期望快照恒无该段，比对必假红（当初以"排除"代替"对齐"）。
- **已覆盖的部分（不必重做）**：生成算法跨语言一致性由 `DiffSectTerrainTest`（多种子全数组逐位 + 生产配置 + `cellHash`/`smoothNoise` 探针）保障；冻结链路各环节由 C++ `terrain_freeze_test.cpp`（6 用例）+ `SaveDataTerrainFreezeTest` + `RoomMigrationV50To51Test` 保障。
- **要修的是**：让两字段回到"全状态逐字段对拍"面内 —— 使"跑完整业务周期后两端完全一致"这条整体性保证对它们生效。
- **实施步骤**：
  1. 在 harness 的 Kotlin 侧补上与生产同款的 **boot 回填**（生产路径：`GameEngineSaveOps.kt:23-27` 首次落段 + `BootSequenceController` 的 `ensureSectTerrainBackfilled`）；
  2. 删除 `DiffSurfaceAssertion.diffIsMirrorGeneratedField` 中 `k == "terrainTiles"` / `k == "mapGenVersion"` 两条分支及其 KDoc 段（`:72-77`）；
  3. 跑 47 个 `Diff*`：**预期先红**——红的位置即"harness 与生产口径差"的真实暴露点，逐条归因（是 harness 缺回填，还是两端真分叉）；
  4. 归因完成后两字段按普通字段参与对拍。
- **验收**：`DiffSurfaceAssertion` 不再含该两字段的排除分支；47 个 `Diff*` 全绿；`terrainTiles` 在 C++/Kotlin 两侧的**键存在性**也被断言（`:48` 的"C++ 导出而 Kotlin 缺失即红"生效）。
- **风险与兜底**：若发现真有分叉，**不得**把字段退回排除面（那就是把缺陷藏回去）——按"两端同步 + 记录根因"处理；无法当场定论时打 `wip/` 分支 + 在 handover 登记，**不合并**。

---

### 2.B ③ `PresentationRandom` 按场景派生（跨会话一致）——**✅ 已实施（2026-09-15，见 handover §2.69）**

> ~~**状态声明**：本轮仅完成**设计与接线点实测**（含一次探针式改动，已 `git checkout` 回退干净，当前树与 `HEAD=b961a356c` 一致）。实施从零开始。~~ **已照本单实施**：引擎全量 **3313/304/0/0**（= 3306 + `PresentationRandomSceneTest` 7 用例）、`SectDiplomacyDialogTest` 零改动（键化在 `DiplomacyFlows` 调用处，builder 签名未动）、外交构建函数实为 **8 个**（§B.8#2 估 9）已全键化；手工验证项（天劫立绘/交谈台词重进一致性）待真机人工确认。

#### B.1 背景与目标（实测证据）

| 事实 | 证据 |
|---|---|
| `seedFromWorld(mapSeed)` **全仓零调用**（生产与测试均无；自引入提交 `85498c4c3` 起即无调用者） | `PresentationRandom.kt:49` 定义；`git grep seedFromWorld` 全历史仅此一处 |
| ⇒ 表现流种子**恒为编译期常量** `DEFAULT_SEED` | `PresentationRandom.kt:112` |
| ⇒ 类 KDoc 所述"种子由 `mapSeed` 派生（同会话内可复现）"**与实现不符** | `PresentationRandom.kt:26` |
| 一条**共享可变流**被 90 处消费点共用 ⇒ 抽到什么取决于"玩家点过哪些界面" | 见 B.4 调用点清单 |
| 天劫立绘在 `remember(combatant.id, …)` 内抽取 ⇒ **同一天劫内稳定，但退出重进换一张** | `HeavenlyTrialComponents.kt:184-202` |
| Compose 内自行 `PresentationRandom()` 构造（绕过 DI 单例） | `DiscipleChatDialog.kt:322`、`LoadingScreen.kt:164` |

- **目标**：**同一存档 + 同一场景实例 ⇒ 每次进入结果恒定（跨会话一致）**；不同场景实例之间保留多样性；**不落盘、零协议面、不污染决策流**。
- **非目标**（明确不做）：把表现流状态写进存档（`rngStates` 或新字段）——那要新增协议面 + 存档迁移 + 47 对拍面 + iOS 侧同步，收益仅"第 N 次进入看到第 N 套文案"，**已判定不划算**。

#### B.2 技术方案

1. `PresentationRandom` 增补（**API 完全向后兼容**，既有 6 个公开方法签名不变）：
   ```kotlin
   @Volatile private var worldSeed: Long = DEFAULT_SEED          // 新增字段
   private constructor(worldSeed: Long) { … }                      // scene 专用（种子即派生结果）
   fun seedFromWorld(mapSeed: Long) { worldSeed = mapSeed; rng = DeterministicRng.fromSeed(mapSeed xor PRESENTATION_SALT) }
   fun scene(key: String): PresentationRandom =
       PresentationRandom(worldSeed xor PRESENTATION_SALT xor fnv1a64(key))
   ```
   - `scene()` 返回**独立实例**（各场景流零竞争，比共享流线程性更好），其 draw API 与根实例完全一致 ⇒ **调用点改造只需换"从哪拿实例"**。
   - **哈希必须用 FNV-1a 64（`encodeToByteArray()` 逐字节累积）**，**禁止 `String.hashCode()`**：后者是 JVM 实现约定而非跨平台契约，iOS（KMP/Native）侧取值可能不同 ⇒ 同存档两端立绘/文案不一致（本项目未来做 iOS 端，见 `rules/code-quality.md` 跨平台章节）。
2. **接线（关键：一处覆盖两端）**：`BootSequenceController.generateMapPreloadData()`（`BootSequenceController.kt:407`）内 `val mapSeed = …`（`:411`）之后：
   ```kotlin
   if (mapSeed != 0) presentationRandom.seedFromWorld(mapSeed)
   ```
   - 并给 `BootSequenceController` 构造增参 `private val presentationRandom: PresentationRandom`（`:42-48`，当前 5 参 → 6 参）。
   - **为什么是这里**：启动流程是**新档与读档的唯一汇合点**（`SaveLoadViewModelNewGameOps.kt:228` 明确"generateMapPreloadData removed — now handled by BootSequenceController internally"；`SaveLoadViewModel.kt:345` 新档亦走 boot 流程）⇒ 一处接线即两端覆盖。
   - **不要**改 `GameEngineLoadDataOps`：它是 `GameEngineCore` 的扩展文件且拿不到 `PresentonRandom`；`GameEngineCore.kt` 属 W4-D 冻结宿主族（见 §2.A 冲突矩阵）。
3. **场景键纪律（写进 KDoc）**：键**必须含场景实例身份**；若所有调用都用同一常量键 ⇒ 每次同一结果、**表现多样性归零**（比"重进会变"更糟）。

#### B.3 影响范围清单

| 文件 | 变更类型 | 说明 |
|---|---|---|
| `android/core/engine/.../core/util/PresentationRandom.kt` | 修改 | 新增 `worldSeed` / 私有构造 / `scene()` / `fnv1a64`；KDoc 增"两种取用方式 + 键纪律 + 接线事实" |
| `android/core/engine/.../engine/BootSequenceController.kt` | 修改 | 构造增参 + `generateMapPreloadData()` 内播种（2 处改动） |
| `android/feature/game/.../heavenlytrial/HeavenlyTrialComponents.kt` | 修改 | `CombatantPortrait` 的"无立绘"分支改用 `random.scene("trial.portrait.${combatant.id}")` |
| `android/feature/game/.../dialogs/DiscipleChatDialog.kt` | 修改 | 表现流改 `PresentationRandom().scene("chat.${disciple.id}.$gameYear")`（`remember(disciple.id, gameYear)`） |
| `android/feature/game/.../dialogs/DiplomacyGiftTexts.kt` / `DiplomacyVassalTexts.kt` / `DiplomacyFlows.kt` | 修改 | 文案构建处的随机源改按`宗门 id + 文案种类`派生的场景流 |
| `android/feature/game/.../WorldMapInteractionViewModel.kt` | 修改 | 外交文案场景流入口（同键规则） |
| `android/core/engine/.../domain/favor/GiftService.kt` | 修改 | 4 处 `presentationRandom.asKotlinRandom()`（`:121/:163/:373/:384`）改场景流 |
| `android/feature/game/.../LoadingScreen.kt` + `LoadingTips.kt` | 修改 | 提示轮播改 `scene("loading.tip")`（每次进入轮播序列一致） |
| `android/core/engine/src/test/.../util/PresentationRandomSceneTest.kt` | **新增** | 见 B.5 |
| `docs/rng-source-inventory.md` / `ui-read-surface.md` | 修改 | 登记"表现流取用方式"口径 |

**不动的面**：云层/装饰（`CloudLayerAnimator.kt:29`、`NativeSurfaceView.kt:548` 已刻意**不用**本类，走 `config` 维度固定种子）——保持"持续变化"语义。

#### B.4 场景键表（实施时照此填）

| 场景 | 键构成 | 为什么 | 备注 |
|---|---|---|---|
| 天劫立绘 | `"trial.portrait." + combatant.id` | 同一参战者**永远**同一张立绘（他就是他） | 键最稳定，收益最直观 |
| 弟子交谈 | `"chat." + disciple.id + "." + gameYear` | 同年重进同一套台词；跨年换新（保留多样性） | 当前只有 `gameYear` 入参；若要更细可加月/旬 |
| 外交赠礼/附庸文案 | `"diplomacy." + sectId + "." + 场景名` | 同一宗门同一类文案恒定（关系值等仍按原入参参与选择） | 场景名如 `gift.player` / `gift.aiAccept` / `vassal.dissolve` |
| 加载提示 | `"loading.tip"` | 轮播序列每次启动一致（**常量键是刻意的**） | 提示本就该固定轮播 |
| 战斗描述文案 | `"battle." + battleId`（若无可稳定 id 则保持共享流并登记） | 同一次战斗重看不该变 | 需在实施时确认是否存在稳定战斗 id |
| 装饰/动画（云层等） | **不改** | "持续变化更好" | 保持根实例共享流 |

#### B.5 测试方案

新增 `PresentationRandomSceneTest`（`core:engine` 单测，纯 JVM）：

1. `scene(key)` 同键 ⇒ 两次取流的前 N 次抽取**逐位相同**；
2. `scene(key)` 异键 ⇒ 序列**不同**（负向断言，防"键没参与哈希"的实现错误）；
3. `seedFromWorld(A)` vs `seedFromWorld(B)` ⇒ 同键序列**不同**（防"世界种子没参与派生"）；
4. 根实例与场景流**互不影响**（各抽 10 次后根实例序列不变——证明 `scene()` 返回独立实例）；
5. `scene()` 不改动 `GameData`/实体表（表现类边界：以 Fake store 断言零写入）；
6. FNV-1a 稳定性：对固定键断言**字面量期望值**（跨平台回归锚点——iOS 侧实现须复现同一常量）。

**守卫（可选但推荐）**：仿 `RngSourceGuardTest` 的源码扫描式守卫，断言生产源中 `seedFromWorld(` 存在真实调用点——防止本条缺陷（"定义了但从不调用"）复发。

#### B.6 验收判据

- 上述 6 个用例绿；`:core:engine` 全量用例数 **≥3306**（新增用例只增不减，既有全绿）；
- 六模块 detekt 绿 + baseline 全 0；
- 手工验证：同一天劫退出重进 3 次立绘一致；同一弟子同一年交谈开场白一致；跨年交谈变化；
- 决策类零扰动：`Diff*` 对拍全绿（表现流不参与协议 ⇒ 对拍本应零差异，任何差异都说明改错了地方）。

#### B.7 风险与兜底

| 风险 | 影响 | 兜底 |
|---|---|---|
| **表现面变更**（所有文案/立绘选择序列改变） | 玩家可见、无存档影响 | 属预期（口径修正）；写入双更新日志；不需版本号递增（由用户决定） |
| 误把决策类随机接到 `scene()` | **存档级确定性破坏** | 逐点自检 B.3 清单——所有改动点均已在 §BC.8 标注"不写状态"；`RngSourceGuardTest` ② 类正则仍拦裸 `Random.*` |
| 场景键退化为常量（多样性归零） | 表现重复、玩家察觉"文案总一样" | B.3 键表逐项填写；B.5 用例 2 的负向断言兜底 |
| `DiscipleChatDialog`/`LoadingScreen` 自建实例（不走 DI） | 这两处**不随存档变化**（仍随键稳定） | 已知取舍：先保"跨会话一致"；若要 save-specific，另立小项改为经 ViewModel/EntryPoint 注入单例 |
| 哈希跨平台不一致（iOS） | 两端立绘/文案不同 | 强制 FNV-1a（B.2 第 1 条）+ B.5 用例 6 的字面量锚点 |

#### B.8 盲区自查（实施者须逐条确认）

1. **`gameYear` 粒度是否够**？弟子交谈若同一年内多次重进，台词完全一致——玩家是否觉得"重复"？备选：键加月/旬（更细 ⇒ 更少重复但重进同旬仍稳定）。
2. **外交文案的场景名是否覆盖全**？`DiplomacyGiftTexts`/`DiplomacyVassalTexts` 共 9 个构建函数，实施时须逐个给键，**不得遗漏**（漏一个即回到"会变"）。
3. **是否存在其他 `PresentationRandom` 消费点**？本文清单按 2026-09-15 `git grep` 实测（90 处引用）；实施前须重跑一次 `git grep -n 'presentationRandom\|PresentationRandom'` 核对增量。
4. **表现流是否被写进任何日志/统计**？若有（如埋点上报文案），`scene()` 的稳定化会改变上报内容。
5. **是否有测试依赖旧的"每次不同"行为**？`SectDiplomacyDialogTest`（22 处引用，自建实例）断言的是"非空/多样性集合"——`scene()` 稳定化后 `(1..5).map{…}.toSet()` 这类断言**可能从多值退化为单值**（键相同则结果相同）⇒ **必须同步调整该测试的键或断言**（这是最容易漏的一处）。

---

### 2.C ② `TimeSystem.onPhaseTick` 迁入测试源集 —— **✅ 已实施（2026-09-15，见 handover §2.69）**

> ~~**状态声明**：本轮仅完成实测与方案，**未改任何代码**。~~ **已照本单实施**：`TimeAdvanceBaseline.advancePhaseBaseline`（逐字搬运 + 冻结基准 KDoc）+ 6 测试 9 调用点改写 + `TimeSystemPureLogicTest` 复刻改调基准；**反向验证已过**（基准 `>=`→`>` ⇒ `DiffTimeTest` 4 用例即红后还原）；引擎全量与搬运前**完全一致**（3306/303/0/0）＝语义零变更得证。

#### C.1 现状（实测）

| 事实 | 证据 |
|---|---|
| `onPhaseTick` **零生产调用** | 全仓 `onPhaseTick` 命中：定义处 `TimeSystem.kt:47` + `SystemManager.kt:22`（注释）+ 6 个测试文件 |
| **无接口声明它**（不存在多态可达路径） | 全仓无 `override fun onPhaseTick` |
| 是 6 个 `Diff*` 测试的 **Kotlin 跨语言对拍基准** | `DiffTimeTest:57`、`DiffPhaseSettlementTest:433/506/644`、`DiffMonthSettlementFixture:553`、`DiffYearSettlementTest:747`、`DiffAuthoritativeTickTest:396/523`、`SettlementTransactionMergeTest:34` |
| 测试**仅为调用它而构造 `TimeSystem`** | 各测试均在调用前 `val timeSystem = TimeSystem(store)`（`DiffPhaseSettlementTest:427` 等） |
| 实现体是**纯时间进位**、只依赖入参 `state` | `TimeSystem.kt:47-63`（用 `state.gameData` + `PHASES_PER_MONTH`/`MONTHS_PER_YEAR`，**不碰 `stateStore`**） |
| 已有第二份内联复刻 | `TimeSystemPureLogicTest.kt:20` 注释"复刻 TimeSystem.onPhaseTick 中的计算" |

#### C.2 方案（推荐执行）

**把基准实现原样移入测试源集**，作为**冻结的黄金基准**（frozen golden reference），并从生产类删除：

1. 新增 `android/core/engine/src/test/java/com/xianxia/sect/core/engine/system/TimeAdvanceBaseline.kt`：
   - 内容 = `onPhaseTick` 的**逐字搬运**（含 `@Suppress("UnusedParameter")` 与全部注释）；
   - 形态建议 `internal fun MutableGameState.advancePhaseBaseline(phasesToSettle: Int = 1)`（扩展函数，调用点更短）；
   - KDoc 必须写明：**"本文件是冻结的跨语言对拍基准（C-15 口径）——禁止'优化'、禁止改数值语义；改动即等于改对拍标准答案。"**
2. 删除 `TimeSystem.kt:39-63` 的 KDoc + 方法。
3. 6 个测试：把 `timeSystem.onPhaseTick(this, phasesToSettle = 1)` 改为 `advancePhaseBaseline(1)`；随后**删除仅为构造它而存在的 `TimeSystem(store)` 局部量**（确认该测试不再需要 `TimeSystem` 的其他方法；`DiffTimeTest` 须逐个核对，它可能还用 `getTotalPhases` 等）。
4. `TimeSystemPureLogicTest.kt` 的内联复刻**改为调用本基准**（消除"第二份复刻"）。

#### C.3 影响范围清单

| 文件 | 变更类型 |
|---|---|
| `android/core/engine/.../engine/system/TimeSystem.kt` | 删除 KDoc + `onPhaseTick`（`import` 若因此闲置须同步清理） |
| `android/core/engine/src/test/.../engine/system/TimeAdvanceBaseline.kt` | **新增**（冻结基准） |
| `DiffTimeTest.kt` / `DiffPhaseSettlementTest.kt` / `DiffMonthSettlementFixture.kt` / `DiffYearSettlementTest.kt` / `DiffAuthoritativeTickTest.kt` / `SettlementTransactionMergeTest.kt` | 改调用 + 清理局部 `TimeSystem` |
| `TimeSystemPureLogicTest.kt` | 内联复刻改调用基准 |
| `docs/parallel-batches-w4/README.md` §9 / handover §4.1·§4.2 | 勾销"待拍板"，登记结论 |

#### C.4 测试方案与验收

- **语义零变更的证明**：`TimeSystem.onPhaseTick` 是**逐字搬运**（非重写）⇒ 引擎全量用例数应与搬运前**完全一致**（基线 **3306 / 303 类 / 0 失败 / 0 跳过**，见 handover §3），47 个 `Diff*` 全绿即证明基准与 C++ 仍逐位一致；
- 桌面 C++ 门禁不涉及（零 C++ 改动）；
- 编译门禁：`:core:engine:compileReleaseKotlin` + `:core:engine:compileReleaseUnitTestKotlin`；
- **反向验证（必做）**：故意把基准里 `newPhase >= PHASES_PER_MONTH` 改成 `>`，跑 `DiffTimeTest` **必须变红**——证明基准仍在被真实使用（防"移完变成死代码"）。

#### C.5 风险与盲区自查

| 风险/盲点 | 说明与处置 |
|---|---|
| **"会失去独立 Kotlin 基准"的质疑** | 不成立：搬运后**仍是唯一一份** Kotlin 实现（不是复制成两份），且它从未被生产调用 ⇒ 移出生产面**不改变对拍强度**，只消除"生产类里躺着一个形似 API 的死方法"这一误用面（若被误接进 tick ⇒ 时间**双推进**） |
| 搬运时"顺手清理/优化" | **禁止**——C.2 第 1 条的 KDoc 必须写明，且 C.4 的反向验证须通过 |
| `SettlementTransactionMergeTest` 的 `settlePhase(timeSystem)` 签名 | 该方法签名里带 `TimeSystem`，改基准后**签名也要改**（去掉参数）——否则留下无意义依赖 |
| 同类项 `PhaseSettlementExecutor` | **本项不处理**：它是有生产注册的类（`GameEngineCore.kt:633` 懒构造 + `GameSystemRegistryDefaults.kt:48` 注册），搬运会触及 W4-D 冻结宿主族 `GameEngineCore` ⇒ 登记到 **D5** 一并复议（结论建议：同样移入测试源集，但须 D4 之后） |
| 若用户改变主意要"直接删除" | 那不是本方案：删除会**同时**删掉 6 个 `Diff*` 的期望来源 ⇒ 对拍退化为 C++ 自证，**不可采纳** |

---

### 2.D 顺带清偿清单（并入 D5）

| # | 项 | 证据 | 处置 |
|---|---|---|---|
| 1 | `maxDisciples = 1000` **误导性死配置** | `GameConfigData.kt:50`（引擎零引用）；`StorageConfig.kt:72` 接口属性无实现读取；全仓唯一读取点 `ConfigLoaderTest.kt:47` 断言默认值 | 删除或标注 deprecated + 同步清理测试断言；**同时**保留"性能规划以实际规模为输入"的口径纪律（已写入 handover §2.68） |
| 2 | 悬空 KDoc（引用不存在的类） | `CultivationEventAutoWarehouseOps.kt:19` 原引 `CultivationTickSystem.onPhaseTick` | ✅ **已修**（本轮改为真实调用者 `PhaseSettlementExecutor.execute`） |
| 3 | `GameSettingsData.autoSave` 孤儿模型 + 悬空 TypeConverter + `AudioConfig.updateFromSettings` | handover §4.2 / 方案 §8 D5④ | 按"清理"执行（已拍板）——须排 WS-5b 之后（同文件） |
| 4 | `GameData` 死辅助函数 | `totalSpiritStonesSellValue:887` / `withOrganization:992` / `withExploration:1003`；生产零调用的 `withWorldMap:967` / `withBuildings:974` / `withEconomy:980` | 同上（同步清理 `GameDataTest`） |
| 5 | "18+11+5 处零调用者站点" | handover §5④ **仓库内无分项清单** | D5 第一件事：`git grep` 产出三份可核对清单（生产调用 0 / 测试引用 n）**再**删除 |
| 6 | 洞府探索生命周期入口死链 | 双证（`CultivationService.kt:178` 零调用 + 主源 `CaveExplorationTeam(` 构造 0 处） | ⚠️ **只删该入口链**；`processSectDisciplesAging` / `processAISectOperations` / `currentAiThermalBatchSize` **是活路，禁删** |
| 7 | 守卫面收缩 | `RngSourceGuardTest` 登记上限（W4-A 扩面后 13）；`RngEngineIsolationGuardTest` 白名单（W4-C 收敛三处后应恰为 1 条） | 守卫**只缩不增**；并把白名单条目数落成**显式计数断言**（当前靠注释纪律，新豁免无法被机器拦下） |
| 8 | `GameEngineSectLevelOps.kt:210` 输入侧随机流入 C++ 事务 | `bloodMaterials.random()` 经 `prepared` 传入 `SECT_LEVEL_CLAIM_TX` ⇒ C++ 实际发放内容不可复现 | 与 Jade 域同批分区化（W4-B 已处置 Jade 环境缺陷；此项 W4-B 未覆盖⇒登记） |

---

### 2.E 非并行轨（**不派工**）

| 项 | 状态 | 需要什么 |
|---|---|---|
| 真机（物理设备）验证批 | 未做 | 物理设备；**串行约束**：E3/N5（信封体积/耗时真机绝对值）必须在 D4 关闭**之前**采基线 ⇒ D1 完成后即可采 |
| WS-4 NPC 移动系统 | 未做 | 用户补充**玩法设计文档**（数量上限/生成规则/与弟子系统关系/可行走语义） |
| WS-1 阶段 3 数据导向存储 | ✅ **已决策不做**（2026-09-15，按桌面 Release 实测 + 实际规模 ≈100 弟子） | **再评估阈值**：实际规模 **>400 弟子** 或 D1 埋点真机实测每旬镜像 **>100ms**（观测= D1 埋点，零额外成本） |
| `PresentationRandom` 一致性口径 | ✅ **已按 §2.B 实施（2026-09-15，handover §2.69）** | 场景键登记表见 [rng-source-inventory §7](../rng-source-inventory.md) |
| `TimeSystem.onPhaseTick` 删除 | ✅ **已按 §2.C 实施（2026-09-15，handover §2.69）** | `PhaseSettlementExecutor` 同类项登记 W4-D/D5 复议 |

---

## 3. 统一验证命令模板

> 工作目录 = `android/`；仓库根脚本写 `../scripts/...`；**测试必须串行** `--max-workers=1`；`-D` 单横线；JNI 路径必须**完整文件绝对路径**（传目录 ⇒ `UnsatisfiedLinkError` 假失败）；系统属性非任务输入 ⇒ 必须 `--rerun-tasks`（否则 `UP-TO-DATE` 假绿）；桌面套件以 `build/desktop-test/` 为准；GTest 运行时需 `llvm-mingw-<版本>-ucrt-x86_64\bin` 在 PATH。

```bash
# ① 生成物零漂移（每次提交前必跑；判据是 git diff，不是 git status）
node ../scripts/gen-action-ids.mjs && git diff --exit-code

# ② 桌面 C++ 全量（触碰 C++ 必跑）
cd app/src/main/cpp/gamecore/build/desktop-test && cmake --build . && ctest
./test/game-core-tests.exe                      # 单进程直跑复核（防 exe 被并行构建复写的假失败）

# ③ 重建桌面 JNI（对拍用；触碰 C++ 必跑）
pwsh -File ../scripts/build-desktop-jni.ps1

# ④ 引擎全量（对拍验收；路径必须指向【本工作树】）
./gradlew.bat :core:engine:testReleaseUnitTest --rerun-tasks --max-workers=1 \
  "-Dgamecore.jni.path=<工作树绝对路径>\android\core\engine\build\desktop-jni\libgamecorejni.so"

# ⑤ 六模块 detekt（baseline 全 0，禁增）
./gradlew.bat :app:detekt :core:data:detekt :core:domain:detekt :core:engine:detekt :feature:game:detekt :core:ui:detekt

# ⑥ 主源 + 测试源编译（改名/拆分批必须跑测试源）
./gradlew.bat :core:engine:compileReleaseKotlin :core:engine:compileReleaseUnitTestKotlin \
  :core:data:compileReleaseKotlin :core:data:compileReleaseUnitTestKotlin \
  :feature:game:compileReleaseKotlin :feature:game:compileReleaseUnitTestKotlin \
  :app:compileReleaseKotlin :app:compileReleaseUnitTestKotlin

# ⑦ 提交门（触碰 C++ 必跑）
./gradlew.bat :app:externalNativeBuildRelease :app:lintRelease

# ⑧ 模块回归（按触碰面）
./gradlew.bat :core:domain:testReleaseUnitTest --max-workers=1
./gradlew.bat :core:data:testReleaseUnitTest --max-workers=1
./gradlew.bat :feature:game:testReleaseUnitTest --max-workers=1
./gradlew.bat :app:testReleaseUnitTest --max-workers=1
```

**干净检出复验（合入 `main` 前必做）**：`git worktree add --detach <临时目录> <提交>` → 在隔离检出内跑 ①②④ → 完事 `git worktree remove`。
**已知坑（实测）**：① 重跑生成器后 `git status` 可能因 `core.autocrlf` 的 stat 缓存报 `M`，但 `git diff` 为空且 `git hash-object --no-filters` 与索引 blob **逐字节相同** ⇒ **以 `git diff` 为判据**；② `remove-worktrees.ps1` 会先探测 reparse point（`node_modules` 等联接**只删链接本身**）——**禁止**用 `Remove-Item -Recurse` 清理 worktree（历史真实事故：穿透联接删掉主仓 `node_modules`）。

---

## 4. 门禁清单（每项"完成"的定义，缺一不可）

| # | 门禁 |
|---|---|
| 1 | 桌面 C++ 全量绿（+ 单进程直跑复核）——**零 C++ 改动的项可免**，但须在交接说明中写明"本项零 C++ 改动" |
| 2 | `:core:engine` 全量绿（基线 **3306 / 303 类 / 0 失败 / 0 跳过**，含 **47** 个 `Diff*`）+ 本项新增用例绿 |
| 3 | `:core:domain` **1758/0**、`:core:data` **716/0/0（15 跳过）**、`:feature:game` **872/0/0**、`:app` **1020/0/0（2 跳过）**（按触碰面复跑，数值只增不减） |
| 4 | 六模块 detekt 绿 + baseline **全 0**（新违规必须修，禁入 baseline） |
| 5 | 主源 + 测试源编译绿 |
| 6 | `:app:externalNativeBuildRelease` + `:app:lintRelease` 绿（触碰 C++ / 资源 / Manifest 必跑） |
| 7 | `node scripts/gen-action-ids.mjs && git diff --exit-code` 空 |
| 8 | 干净检出复验通过（合入 `main` 前） |
| 9 | tag + `git bundle`（落 `C:\Mnzm\backups\`） |
| 10 | handover §2 新增小节 + §4.1 勾销 + 双更新日志（同批更新） |

---

## 5. 风险与兜底（汇总）

| 风险 | 概率 | 影响 | 兜底 |
|---|---|---|---|
| **批次自评不可信**（W4 实测两次为假：A 测试源编译、C 迁移回归） | **高** | 高（把破损交付当完成） | 收口人**独立复跑**门禁 + **干净检出复验**；每批交付必须附复验记录（建议固化为派工硬要求） |
| 交付无测试的代码 | 中 | 中（后续无法验证） | 门禁 2/3 强制；新增逻辑必须有对应用例（CLAUDE.md 0.1） |
| 表现流误接决策路径 | 低 | **极高**（存档级确定性破坏） | §2.B 的自检清单 + `RngSourceGuardTest` 正则闸门 + `Diff*` 对拍 |
| harness 对齐后暴露真实分叉 | 中 | 中（D3 工期） | 逐条归因；**禁止**把字段退回排除面；无法定论则打 `wip/` 分支不合并 |
| D4 删通道后仍有关闭域写入 | 中 | 高（丢数据） | ADR §4 五步：先禁用 → 完整业务周期 → 指纹零差异 → 归档 tag → 再删；关闭域写入检测零命中为硬门禁 |
| `.git` 对象库再次损坏（历史两次，不可恢复） | 低 | **极高** | 每里程碑 tag + bundle；push 到远端（远端现已有 20 个 tag 作异地备份） |
| 多会话共用同一工作区导致暂存区交叉污染 | **高**（本轮已实测到并行会话在同一树上改美术资源） | 中 | **逐 pathspec `git add`**（禁整仓 add）；提交前核对 `git show --stat`；必要时各自 worktree |

---

## 6. 盲区自查与完善建议

1. **"实际规模 ≈100 弟子"是用户口径而非实测** —— WS-1 决策建立在它之上。若实测分布与此不符（例如某玩法能把弟子推到数百），决策须按阈值重评（阈值与观测手段已写入 handover §2.68）。
2. **§2.B 的 6 个场景键是否覆盖全部 90 处引用** —— 实施前必须重跑 `git grep` 核对增量（本文件清单截取于 2026-09-15）。
3. **`SectDiplomacyDialogTest` 的多样性断言可能与 `scene()` 稳定化冲突** —— §2.B.8 第 5 条已点明，属最易漏项。
4. **D3 与 §2.B 都改 UI/测试面**，若并行执行须核对 B.3 与 D3 的文件交集（当前无交集，但实施中若有人顺手改 `Diff*` 测试则会撞车）。
5. **本文件不覆盖"发布"** —— `version.properties` 递增、商店文案、隐私政策同步（若新增 SDK/权限）由用户决定；双更新日志由 D6 统一收口。
6. **未验证假设**：D1 埋点的 release 零开销目前只有设计口径（`BuildConfig.DEBUG` 门控），实施后**必须**用 release 反编译/字符串扫描实证（门禁 6 的延伸）。
