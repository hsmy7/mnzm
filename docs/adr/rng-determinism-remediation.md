# ADR: 随机源治理（确定性可复现）专项整治

> 状态：✅ **已拍板（选项 2：根治）** 且 **阶段 0/1/2/4 已交付**（2026-09-14，见 [cpp-migration-handover-m0.md](../cpp-migration-handover-m0.md) §2.58）｜决策日期：2026-09-14｜关联：[cpp-migration-handover-m0.md](../cpp-migration-handover-m0.md) §4.1/§6 · [ui-read-surface.md](../ui-read-surface.md) §4.3 · [cpp-engine-migration.md](cpp-engine-migration.md)

## 0. 实施状态快照（2026-09-14 收口，后于本 ADR 正文）

| 阶段 | 状态 | 交付要点 |
|---|---|---|
| **阶段 0** | ✅ | 五类入口分类表（`docs/rng-source-inventory.md`）+ `RngSourceGuardTest`（注释感知 + 逐模块逐类登记上限只缩不增 + R2/R4/R5 断言）+ **CI 红线 step 落 `ci.yml`**（`RNG source red-line (four entry classes)`，**不以 grep 实现**——见 §1"三条失效守卫"） |
| **阶段 1** | ✅ | ① 开袋 → `storage_bag_tx.h`（ActionId 1734）+ 7 处抽签显式传 `EXPLORATION` 分区；② AI RNG 归一到 `GameRngManager` 分区（影子摘除 + 通道分区 9）+ **🔴 收口批新发现的播种态根因修复**：`initForSlot` 曾写**裸种子**而非 `fromSeed` 混种态 ⇒ 同一 `aiSeed` 两侧两条序列（新增 `DiffAiRngSeedingTest` 锁守）；③ `GameRandom` 物理删除（残留调用 = 编译期报错） |
| **阶段 2** | ✅（可归表现类者全迁） | 迁入 `PresentationRandom`：外交文案（`DiplomacyGiftTexts`/`DiplomacyVassalTexts`）+ 天劫立绘 + **`SectResponseTexts`（改形参必传）** + **`LoadingTips`** + **`CloudLayerAnimator`（摘 `Random.Default` 默认值）**。**`BattleDescriptionGenerator`(12) 与 `DiscipleChatDialog`(3) 判为决策类**（前者文本入 Room `battle_logs` 实体、后者写弟子 `skills`/`cultivation`）⇒ `R3` 明令表现流不得被决策路径调用，归**阶段 3** |
| **阶段 3** | ⛔ **未开工** | 决策类 ~19 处 ⑤ + 2 处 ④ 挂钟种子流 + 3 处位置实参稀有度缺陷 + 上述两文件。**ADR §8 首行风险已实测排除**：10k 抽取基准 kotlin 本地 PCG `14ns/op` vs native JNI 标量往返 `11ns/op`（**ratio 0.8，无成本障碍**）⇒ 可按"逐域按调用点下沉"原粒度推进（桌面 JVM ≠ ART，真机留观测余量） |
| **阶段 4** | ✅ | `R2` 分区往返 + `R4` 清单式/双射/通道语义断言 + **CI 红线 step** + **10k JNI 基准**；六模块 detekt baseline 恒 0 |

**收口批新增守卫两道**（防下一批再踩）：
- `DiffAiRngSeedingTest`——"`snapshot()` 是**状态**不是种子"落成可执行断言（混种态 × 3 档 seed + 前 8 抽与 C++ 镜像分区逐位一致 + 同 seed 幂等）；
- `RngEngineIsolationGuardTest`——禁止 `object`/单例持有可变 `GameRngManager` 字段（`MissionSystem` 事故：进程级单例被双引擎夹具互相覆写，侧 B 的月变消费了 C++ 的 MISSION 分区；修法 = **形参必传**，隔离靠构造期依赖）。首跑即抓出 3 处同族遗留（`EnemyGenerator`/`AISectAttackManager`/`AISectTeamComposer`，白名单登记 + 偿还触发条件）。

**仍未收敛（诚实口径）**：`DiffYearSettlementTest` 1 例 AI 招募逐字段分歧（分歧窗口已收窄到"第二名 AI 弟子的装备/功法段"，根因待专项）；`:feature:game` 两族 10 处预存夹具失败（见 handover §2.58.7）。

---

## 1. 背景与问题定性

C++ 迁移的收尾批（batch-21：反向通道逐域关闭）必须以"确定性"为验收前提——通道关闭后，
Kotlin 侧不再有回导兜底，**每个影响状态的结果都必须可由"存档 + 种子"精确重放**。
在给该批做前置复核时，暴露出**同一架构漏洞的两个出口**：

| 出口 | 症状 | 直接后果 |
|---|---|---|
| 库存**开袋** | 件数与类型走 `EXPLORATION` 分区（可复现），但"抽到哪一件"走 `kotlin.random.Random.Default`（`templates.random()` / `generateRandomPill` / `generateRandomMaterial` 的默认参数） | `Random.Default` **不入存档、不随档走** ⇒ 同一存档两次开袋结果不同（不可复现） |
| **`aiSectDisciples` 读档/存档自愈** | `AISectDiscipleManager` 自持 `_rng`，绕过真源 | 真源 `GameRngManager.getRng(AI_SECT)` 在 AUTHORITATIVE 下**已委托到 C++ `kAiSect` 分区且该分区本就在 `rngStates` 协议面内**；影子流只在 `createNewGame`/`loadData` 两处 `initForSlot(mapSeed)` 重播，之后与真源各自漂移 ⇒ 自愈写入的是与真源不一致的序列 |

### 定性：**架构级问题，但不是"架构设计错了"**

问题的本质不是分区设计有问题，而是**"随机流"与"该流是否可复现/可归档"这两个概念在代码里没有绑死**——
任何一处新代码随手用 `Random.Default`、自持一个 RNG、或调用一个"看起来是有种子的"全局 RNG，
都不会有任何机制拦住它。

**实测规模（三类未受治理的流）**：

| 流 | 生产调用点 | 是否落盘 | 是否种子化 | 判据 |
|---|---|---|---|---|
| `kotlin.random.Random.Default`（`.random()` / `generateRandom*` 默认参数） | **114 处**（含表现层） | ❌ | ❌（进程启动随机） | 同一存档两次抽取结果不同 |
| `GameRandom`（`object`，XorShift128Plus） | **8 处**（见下） | ❌ | ❌（**`setSeed()` 生产零调用**，种子 = `System.currentTimeMillis()`，且 `@ThreadLocal` 每线程独立流） | 同档跨会话/跨线程结果不同 |
| `AISectDiscipleManager._rng` | 自愈 + 回退臂 AI 域 | ❌（影子） | ✅（`initForSlot(mapSeed)`） | 与真源 `kAiSect` 分区**各自漂移** |

**→ 三个流全部不落盘，只有第三个有种子。**

`.random()` / `Random.Default` 按文件集中度前列：`RedeemCodeRewardOps.kt` 13 /
`BattleDescriptionGenerator.kt` 12 / `RedeemCodeService.kt` 9 / `TalentDatabase.kt` 6 /
`DiplomacyVassalTexts.kt` 5 / `AffixDatabase.kt` 5 / `NameService.kt` 4 /
`DiplomacyGiftTexts.kt` 4 / `EquipmentDatabase.kt` 4 / `MailAttachmentDistributeOps.kt` 4 /
`GameEngineTraitWashRoll.kt` 4。

`GameRandom` 的 **8 处生产调用点（🔴 全部影响状态，无一为纯表现）**：

| 位置 | 用途 | 影响 |
|---|---|---|
| `GameEngineLoadDataOps.kt:262` / `:332` | **`mapSeed = GameRandom.nextInt()`**（`createNewGame` / `loadData`） | 🔴 **世界生成的根种子**——世界内容由挂钟时间与调用线程决定 |
| `Disciple.kt:228-234` | 弟子 HP/MP/物攻/法攻/物防/法防/速度 方差（7 抽） | 🔴 弟子属性 |
| `SpiritRootGenerator.kt:33` | 灵根洗牌（Fisher-Yates） | 🔴 弟子灵根 |
| `GameConfig.kt:421` | `GameRandom.nextDouble()` | 🔴 需逐点确认消费面 |
| `HeavenlyTrialComponents.kt:188-189` | 天劫对手性别/立绘抽取 | 🔴 且**在 feature:game（UI 层）**调用 |

### 🔴 本 ADR 的一处自我勘误（必须如实记录）

本 ADR 初稿称"实测规模 114 处"，并据此推断"RNG 治理主要是清理 `Random.Default`"。
**该口径不完整**：114 只统计了 `.random()` / `Random.Default` / `kotlin.random.Random`
三种字面量，**未包含 `GameRandom.`**（其标识符不同，正则漏配）。补查后新增第三类
未治理流（8 处，且**全部影响状态**）。

**教训（防复发）**：审计"随机流覆盖面"不能用单一正则；必须先枚举**所有随机源入口**
（本项目实为 4 类：`GameRngManager` 分区 / `kotlin.random.Random.Default` / `GameRandom` /
对象内自持 RNG），再逐入口统计消费点。阶段 0 的逐处分类表（§11 盲区 1）必须先产出此
四类入口清单，否则会像本 ADR 初稿一样漏掉整类。

**勘误二（2026-09-14 收口批新增，见 §0）**：阶段 1② 交付时把 **`snapshot()` 是 PRNG 状态**
这一点弄错——`AISectDiscipleManager.initForSlot` 写成 `restore(aiSeed)`（裸种子）。C++ `aiRng_`
与旧影子流都经 `fromSeed(aiSeed)` 走过一轮混种（`state = (seed shl 1) or 1` 后丢弃一次
`nextLong()`），故两侧由同一 `aiSeed` 得到**两条不同序列**（实测 2 处 `AISectDiscipleManagerTest`
红 + 1 处 `DiffYearSettlementTest` 弟子条数 3 vs 4）。

**教训（等价性必须成对落断言）**：`RngSourceGuardTest` 管的是"**覆盖完整性**"（五类入口计数、
分区登记、双射），管不了"**语义等价性**"（同种子→同序列 / 状态→快照 / 分区→导出跨语言一致）。
两者互补、缺一不可——本批补 `DiffAiRngSeedingTest` 把"种子→混种态→首抽序列"钉成可执行断言。
**推论**：任何"两侧看起来同一公式"的推断都不足以交付，必须有跨语言逐位断言。


### 现有守卫为何没拦住（三条都失效）

1. **CI 的 RNG 红线是 import 级 grep**（`grep "import kotlin.random.Random"`）——而 `templates.random()`
   是 stdlib 扩展函数、`GameRandom` 是自建 object，**两者都不带该 import**，永远匹配不到；
2. 实测该 grep 断言在当前 `android/.github/workflows/ci.yml` 中**已不存在**（红线事实上失守）；
3. 项目**没有**"新增影响状态的随机必须注册分区"的清单式守卫；也**没有**任何机制阻止
   新建一个自持 RNG 的 object（`GameRandom` 本身就是这么来的）。

### ✅ 阶段 0 的处置：**不以 grep 实现红线**（收口批定稿）

`ci.yml` 新增 step `RNG source red-line (four entry classes)`，显式点名跑
`RngSourceGuardTest` + `RngEngineIsolationGuardTest`。**为什么不恢复 grep**：上述三条失效原因
里，前两条对任何正则都成立（stdlib 扩展/自建 object 不带 import；断言会再次消失），第三条
（无法区分注释引用与真实调用）更会让"改注释即改守卫"。守卫测试是**注释感知 + 清单式 +
只缩不增**的强闸门，且失败信息直接给操作指引 ⇒ 用它替代 grep 是**更强约束**而非放松。

---

## 2. 行业对标（决策依据）

| 来源 | 等级 | 关键事实 | 对本项目的含义 |
|---|---|---|---|
| [Unity Scripting API — `Random.state`](https://docs.unity3d.com/ScriptReference/Random-state.html) | S（官方文档） | `Random.state` 存在的**唯一理由**就是存档与复现。原文："This property can be used to save and restore a previously saved state of the random number generator. Note that `state` is serializable, **so that determinism can be preserved across sessions**. Determinism is an important trait in many scenarios, such as multiplayer games, reproducible simulations, and unit testing." 文档给出 step3/4 → 存 state → step5/6 复现同样数值的示例；`InitState(seed)` 是**单向不可读**的 | **"一个不落盘的全局随机流"在 Unity 的模型里就不是合法用法**。我们的 `Random.Default` 正属此类 |
| [Unreal Engine — `FRandomStream`](https://dev.epicgames.com/documentation/unreal-engine/API/Runtime/Core/Math/FRandomStream) / [`Reset`](https://dev.epicgames.com/documentation/en-us/unreal-engine/API/Runtime/Core/Math/FRandomStream/Reset) | S（官方文档） | 值类型 + 显式 seed 构造，可 `Reset` 重播；API 形状本身即"每系统一条显式持有的流"，不鼓励全局随机源 | 与项目"每域一条分区"的设计同向，且要求**显式持有 + 可序列化** |
| [Godot `RandomNumberGenerator`](https://docs.godotengine.org/en/3.6/classes/class_randomnumbergenerator.html) + [引擎 PR #35764 "Fix RNG state not being restorable"](https://github.com/godotengine/godot/pull/35764) | S（官方文档 + 官方仓库） | 独立 `seed` / `state` 属性；"状态不可恢复"曾作为**引擎缺陷**被修复 | "状态可恢复"被引擎方视为**应当成立的性质**，不可恢复 = bug |
| [GDC Vault — Back to the Future! Working with deterministic simulation in 'For Honor'](https://gdcvault.com/play/1026322/Back-to-the-Future-Working) | S（GDC 顶会） | 确定性模拟的前提是**每条随机流的种子与状态都可精确还原**，否则客户端/服务器、回放、复现全部无从谈起 | 项目正处在"为确定性收口"的阶段，本项是**前置条件而非优化** |
| [Unity Discussions — Seedable/Deterministic RNG（Slay the Spire / Balatro / Spelunky 等）](https://discussions.unity.com/t/seedable-deterministic-rng-slay-the-spire-balatro-spelunky-etc/1583758) | B（社区） | 肉鸽/回合制普遍**按用途分离种子**（战斗 / 奖励 / 地图 / 事件各自独立流） | 佐证项目 8 分区思路正确，需要补的是"覆盖完整性" |
| [numpy 文档 — SeedSequence / stream split](https://numpy.org/doc/2.2/numpy-ref.pdf) | S（官方文档） | 随机流的**无协调派生**（每条子流从父流独立派生）是行业通用做法 | 项目 `seed + partitionId` 的分区派生即此模式 |

### 归纳出的行业惯例三条（本项目对照）

| # | 惯例 | 项目现状 |
|---|---|---|
| 1 | **按用途分流**（战斗/世界生成/NPC/事件各一条），互不偷取 | ✅ 已做到（8 分区 + `RngPartition` 枚举） |
| 2 | **每条流的状态都要能存取**（存档带状态，不是只带种子） | ✅ 已做到（`exportStates`/`restoreStates` ↔ `rngStates` 协议面） |
| 3 | **"表现"与"决策"严格分开**（改文案随机不得污染决策随机） | ❌ **缺**：114 处混在一起，且无机制区分 |

**结论：第 1、2 条项目已具备，漏洞在于"没有机制保证所有影响状态的随机都必须走分区"。**

---

## 3. 决策

**采纳选项 2（根治）：一次性补齐随机源治理，并把三道守卫落为可执行约束。**

理由：
1. batch-21 关闭反向通道后，"确定性"成为对玩家的**可观测承诺**（读档/回放一致），剩余
   `Random.Default` 决策点会变成**查不出来的不可复现来源**；
2. 第 1、3 条守卫与项目既有的清单式守卫测试（`SlotCategoryCoverageTest`）同款题型，**成本低**；
3. 只修两处（选项 1）无法阻止下一个批次再踩——114 处里还有 100+ 处同族。

### 3.1 五项验收不变量（batch-21 引用此节；简称 R1–R5）

| 编号 | 不变量 |
|---|---|
| **R1** | 任何**影响游戏状态**的随机数必须取自 `GameRngManager.getRng(RngPartition.*)`（禁止 `Random.Default` / `.random()` / `Math.random()`） |
| **R2** | 每个分区的状态必须可导出/恢复（现状已成立，补守卫测试锁死） |
| **R3** | 表现类随机（战斗描述文案、外交对话文本等，**不影响状态**）与决策类随机物理隔离，走独立的 `PresentationRandom`；该类**不落盘**且不得被决策路径调用 |
| **R4** | 新增影响状态的随机调用点必须注册进分区表（清单式守卫：未注册即测试红并列出待办） |
| **R5** | **禁止自建随机源**：不得新增 `object`/单例自持 RNG（`GameRandom` 是反面教材：自称支持确定性存档、`setSeed` 生产零调用）；所有随机必须经 `GameRngManager` 分区 |

> **R5 的由来**：`GameRandom` 的存在证明"设计正确"不足以防腐——只要允许自建随机源，
> 就一定会出现第三个、第四个不受治理的流。故守卫必须**从源头禁止新建**，而非事后审计。

---

## 4. 分阶段实施

> **状态标记**：`✅` = 已交付（2026-09-14 收口批，见 §0）；`⛔` = 未开工。

| 阶段 | 内容 | 规模 | 前置 | 状态 |
|---|---|---|---|---|
| **阶段 0** | 本 ADR 入档 + handover §6 + **产出四类随机源入口的逐处分类表**（决策 / 表现 / 无关；§11 盲区 1，**这是本项的工作清单**）+ **重写 CI 红线为可执行检查**（覆盖全部四类入口：`import kotlin.random.Random`、`.random()`、`Random.Default`、`GameRandom.`，扫描 `core:domain` / `core:engine` 主源） | 小 | 无 | ✅ 分类表 = `docs/rng-source-inventory.md`；红线 = `ci.yml` 的 `RNG source red-line` step（**守卫测试而非 grep**，理由见 §1 末节） |
| **阶段 1** | 三个已知缺陷批次：① 开袋 3 处 `Random.Default` 抽签改走 `EXPLORATION` 分区（ActionId 1734+，新 `storage_bag_tx.h`）；② `AISectDiscipleManager._rng` 归一到 `GameRngManager` 的 `AI_SECT` 分区 + `checkAndRepairAiSectDisciples` 下沉 C++（复用 `ai_sect_recruit.h` 既有 `generateRandomAiDisciple` / `applyGearToAiDisciple` / `truncateToAiLimit` / `aiEnsureDiscipleGear`；缺的仅编排三件：`initializeSectDisciples` / `fillDisciplesToTarget` / `isGearCompleteForLevel`）；③ **`GameRandom` 摘除**——8 处全部改走对应分区（`mapSeed` 生成改 `SYSTEM` 或显式 `GameRandom`→分区；弟子属性方差 → `SYSTEM`/`AI_SECT`；灵根洗牌 → `SYSTEM`；天劫 → `BATTLE`），并删除 `GameRandom` 对象（**它自称"支持种子以实现确定性存档"但 `setSeed` 生产零调用——该承诺未实现，属死抽象**） | 中 | 无 | ✅ ①`storage_bag_tx.h` + `STORAGE_BAG_OPEN_TX=1734`；②影子摘除 + 分区 9 通道 + **播种态混种根因修复**（§0 勘误二）；③`GameRandom` 物理删除。**② 的"自愈下沉 C++"经 RNG 归一后收益不明，改判为待拍板**（见 §10 债表） |
| **阶段 2** | `R3` 落地：`PresentationRandom` 引入，`BattleDescriptionGenerator`（12 处）/ `DiplomacyVassalTexts`（5）/ `DiplomacyGiftTexts`（4）/ `SectResponseTexts` 等纯表现消费点迁入 | 中 | 阶段 0 | ✅ **可归表现类者全迁**（外交文案 / 天劫立绘 / `SectResponseTexts` 形参必传 / `LoadingTips` / `CloudLayerAnimator` 摘默认值）。**`BattleDescriptionGenerator` 与 `DiscipleChatDialog` 改判为决策类**（前者文本入 Room `battle_logs` 实体、后者写弟子 `skills`/`cultivation`）⇒ 归阶段 3，`R3` 明令表现流不得被决策路径调用 |
| **阶段 3** | 决策类消费点按域逐批下沉（兑换码 13+9 / 邮件附件 4 / 天赋体质词条 roll 6+5 / `NameService` 4 / `EquipmentDatabase` 4 …），每批"域 → 写入者 → 批次"表进 handover | 大（分批） | 阶段 0；`R1` 守卫全程开着 | ⛔ **未开工**。**§8 首行 JNI 成本风险已实测排除**（10k 基准 ratio 0.8）⇒ 可按按调用点粒度推进；剩余入口清单见 `docs/rng-source-inventory.md` §4 |
| **阶段 4** | 守卫三件套收口：`R4` 清单式守卫测试 + `R2` 分区状态往返测试 + CI 红线门禁；**六模块 baseline 恒 0 不得新增** | 小 | 阶段 1–3 | ✅ 守卫三件套 + **10k JNI 基准**；另加两道收口批新守卫（`DiffAiRngSeedingTest` 等价性 / `RngEngineIsolationGuardTest` 全局禁令） |

**开工顺序**：阶段 0 → 阶段 1（**这两步做完 batch-21 即可开**）→ 阶段 2/3 与 batch-21 后续工作并行
→ 阶段 4 收口。

**收口批校正**：阶段 0/1/2/4 已交付，batch-21 的**关闭前置（阶段 1）达成**；但建议先收敛
`DiffYearSettlementTest` 1 例（"Kotlin 夹具与 C++ 生产编排之间的 AI 分区消费差"，通道关闭后
无兜底）。阶段 3 与 batch-21 后续域批并行推进即可。

---

## 5. 影响范围清单

| 文件/模块 | 变更类型 | 说明 |
|---|---|---|
| `android/core/engine/.../domain/inventory/InventoryFacadeImpl*.kt` | 改 | 3 处 `templates.random()` 改显式传分区 RNG；`generateRandomPill`/`generateRandomMaterial` 调用补 `random` 实参 |
| `android/core/domain/.../registry/*.kt` | 改 | `BaseTemplateRegistry.getRandom` / `EquipmentRegistry.generateRandom` 等默认参数从 `Random` 改为**必传**（消除默认值陷阱——这是漏洞的真正入口） |
| `android/core/engine/.../domain/diplomacy/AISectDiscipleManager.kt` | 改 | `_rng`/`initForSlot` 摘除，`rng` 访问器取 `GameRngManager.getRng(AI_SECT)` |
| `android/core/domain/.../util/GameRandom.kt` | **删** | 8 处生产调用点全部改走分区后整个对象删除（`setSeed` 生产零调用 = 死抽象；KDoc 的"确定性存档"承诺未实现） |
| `android/core/domain/.../model/Disciple.kt` | 改 | 7 处属性方差 `GameRandom.nextInt` → 分区 RNG（需按调用上下文定分区：玩家弟子生成 vs AI 弟子生成） |
| `android/core/domain/.../util/SpiritRootGenerator.kt` | 改 | 灵根洗牌 `GameRandom` → 分区 RNG（**洗牌顺序即抽取序，属 RNG 红线**） |
| `android/core/domain/.../GameConfig.kt:421` | 改 | `GameRandom.nextDouble()` 消费面待逐点确认后改分区 |
| `android/feature/game/.../dialogs/heavenlytrial/HeavenlyTrialComponents.kt` | 改 | 天劫对手性别/立绘抽取 → 分区（**当前在 UI 层调用随机，属架构违规**，需按 §2.6 口径移入引擎线程） |
| `android/core/engine/.../GameEngineLoadDataOps.kt` | 改 | 两处 `AISectDiscipleManager.initForSlot(mapSeed)` 调用点同步（该调用失去意义后删除） |
| `android/core/engine/.../GameEngineLifecycleOps.kt` | 改 | `checkAndRepairAiSectDisciples` 首行 native 臂 + Kotlin 回退臂保留 |
| `gamecore/include/gamecore/system/ai_sect_repair_tx.h`（新） | 新增 | AI 弟子池自愈事务（复用 `ai_sect_recruit.h` 原语 + 编排三件） |
| `gamecore/include/gamecore/system/storage_bag_tx.h`（新） | 新增 | 开袋奖励生成事务（`Random.Default` 三处改走 `EXPLORATION`） |
| `action_ids.h` / `ActionIds.kt` / `execute_dispatch.cpp` / `test/CMakeLists.txt` | 改 | ActionId 1734+（按 `gen-action-ids.mjs` 生成，原子变更集整组提交） |
| `android/.github/workflows/ci.yml` | 改 | 红线重写（阶段 0） |
| `docs/cpp-migration-handover-m0.md` | 改 | §6 引用本 ADR；§4.1 登记项随批次勾销 |
| `docs/ui-read-surface.md` | 改 | §4.3 关闭前置：开袋项由"待拍板"改"已拍板、待实施" |
| `CHANGELOG.md` + `changelog_entries.json` | 改 | 玩家向文案（开袋可复现、设置更稳定；**不出现分区/RNG/确定性等术语**） |

**经济影响**：无（本项不改变任何产出/消耗数值，只改抽取来源）。
**iOS 影响**：正面——`R1`–`R4` 全部落在纯 Kotlin/C++ 逻辑层，无平台 API；iOS 侧复用同一套分区与守卫。

---

## 6. 兼容性分析

| 维度 | 分析 |
|---|---|
| **存档格式** | **零改动**。`rngStates` 协议面本就含全部分区（`GameRngManager.exportStates()` 遍历 `RngPartition.values()`），本项不新增/删除协议字段 |
| **旧档可读性** | 不受影响（不读不写新字段） |
| **行为变化（唯一处）** | 开袋"抽到哪件"的随机来源改变 ⇒ **同一存档开袋产出表**变化。**不构成基线破坏**——`Random.Default` 本就未入档、未随档走，同一存档两次开袋结果**今天就已经不同**；改造只是把"每次不同"变为"可复现"。**对拍场景重锚**：若某 `Diff*Test` 固定断言了开袋产出，需重锚（实施时逐类核查） |
| **回退方案** | 各批独立可回退：新事务走"失败信封 → Kotlin 回退臂"既有契约；`AISectDiscipleManager` 归一若出问题可暂时保留影子（不阻塞其他批） |
| **不变量风险** | `R1` 落地会暴露一批"决策类随机"调用点，实施时**不得**为过守卫而给表现类随机打 `@Suppress` ——必须走 `R3` 迁入 `PresentationRandom` |

---

## 7. 测试方案

| 层 | 内容 |
|---|---|
| C++ GTest | 两事务黄金用例（happy / 校验链失败零写入 / 边界与篡改防御 / **零新增 RNG 抽取面** / 双运行逐位一致）+ AI 自愈：跳过判据、补全口径、齿轮达标、截断 |
| Kotlin 门控 | flag OFF / AUTHORITATIVE-桥未加载双降级；回退臂语义逐项不变；`stateSyncServiceRef` 未 stub 不 NPE |
| **R2 守卫** | 分区状态"导出 → 恢复 → 再抽取"序列逐位一致（覆盖全部 8 分区） |
| **R4 守卫** | 清单式：以 `RngPartition` 枚举 + 已登记消费点表为锚点，未注册的影响状态随机调用点即红并列出操作指引 |
| **R3 守卫** | 反向断言：`core:domain`/`core:engine` 主源中 `.random()` / `Random.Default` 字面量计数为 0（`PresentationRandom` 集中处除外，白名单显式声明） |
| 对拍 | 46 个 `Diff*` 类全绿；开袋断言若存在则重锚并在此登记 |
| 对抗性审查 | 逐批：① 是否被 `@Suppress` 绕过；② 是否把决策随机误迁入 `PresentationRandom`；③ 高频繁调用点（每帧/每次伤害）是否存在 JNI 跨语言成本 |

---

## 8. 风险评估与兜底

| 风险 | 等级 | 兜底 |
|---|---|---|
| **性能：把高频决策随机改走分区**（AUTHORITATIVE 下分区经 JNI 委托到 C++）可能引入跨语言调用开销 | 🔴 高 | **批量调用基准测试先行**（阶段 1 前做一次 10k 次抽取的 A/B 基准；若 JNI 开销显著，改"批量取种子 + Kotlin 本地展开"或按阶段 3 分批仅下沉中低频点）。**此项为阶段 3 的开工前置** |
| 阶段 3 规模大（100+ 处） | 🟡 中 | 按域分批、每批独立验收、`R1` 守卫全程开着（新代码无法再漏） |
| `R1` 落地时表现类随机被误判为决策类 | 🟡 中 | 判据成文：**该随机结果是否写入 GameData / 实体表 / 影响数值**；写入 = 决策类 |
| 开袋产出变化被玩家感知为"改动" | 🟢 低 | 玩家向文案只写"更稳定/可复现"，不写数值；开袋本就是随机事件 |
| 守卫规则误报阻塞正常开发 | 🟡 中 | 白名单显式声明（`PresentationRandom` 集中点 + 注册表），与 `SlotCategoryCoverageTest` 的 `intentionallyExcluded` 同款机制 |

---

## 9. 未来场景推演（≥6 个月）

| 维度 | 推演 |
|---|---|
| 规模增长 | 分区数增长 = 加 `RngPartition` 枚举值 + 清单表加一行，守卫自动覆盖；114 处随阶段 3 单调下降 |
| 生命周期 | `R4` 守卫使"新增决策随机"从"靠自觉"变为"编译/测试期强制"，漏洞不会重新长出来 |
| 平台扩张（iOS） | `R1`–`R4` 全在纯逻辑层；iOS 侧同一套分区 + 同一份 `rngStates` 协议，跨平台复现同结果 |
| 运营演进 | 商业化/活动若引入"抽奖"类随机，天然纳入分区治理（活动随机可复现 = 可审计 = 可申诉），优于现状 |
| 兼容回退 | 不改存档格式 ⇒ 前后版本互读无损；分批交付 ⇒ 任一阶段可单独回退 |
| 多人/云存档（若未来做） | 确定性是前提条件，本项为将来"回放/观战/服务端校验"铺路 |

---

## 10. 技术债与偿还计划

- **本项自身不新增债**：三阶段全部交付后 `R1`–`R4` 成立。
- **显式登记的"暂不偿还"项**（各附触发条件）：
  1. `PresentationRandom` **不落盘**（触发条件：若未来需要"回放整局表现（含文案/语音）"→ 需入档或改为可复现派生）
  2. 阶段 3 未覆盖的剩余调用点（触发条件：`R4` 守卫白名单中每一项的登记理由消失 → 逐项迁入）
  3. 高频点若因 JNI 成本暂缓（触发条件：协议 v2 列级/二进制通道落地后重测）

---

## 11. 盲区自查与完善建议

| # | 盲点 / 未验证假设 | 影响 | 完善建议 |
|---|---|---|---|
| 1 | **"114 处"口径不完整**（初稿只统计 `.random()` / `Random.Default` / `kotlin.random.Random`，漏掉 `GameRandom` 整类；见 §1 自我勘误） | 阶段 3 的分批清单会漏项 | 阶段 0 必须先枚举**四类随机源入口**（`GameRngManager` 分区 / `Random.Default` / `GameRandom` / 对象自持 RNG）再逐入口统计，产出**逐处分类表**（决策 / 表现 / 无关）并把表附进 handover §6；**表即工作清单，必须成文** |
| 2 | **`GameRandom` 是否还有别的生产消费者**（本次 grep 覆盖 `android/` 全树 `.kt`，共 34 处命中，其中 26 处在测试；但可能存在字符串/反射形式调用） | 摘除对象可能漏改 | 阶段 1③ 摘除前跑一次编译 + 全量测试；`GameRandom` 删除会让所有残留调用**编译期报错**（编译即守卫，优于正则） |
| 3 | **`GameRandom` 的 `@ThreadLocal` 语义是否有人依赖**（每线程独立流在多线程下"互不干扰"是它声称的性能设计） | 摘除后多线程调用面行为变化 | 分区 RNG 有引擎线程契约（`jniRequireEngineThread`）——摘除前须确认 8 处调用都在引擎线程；`HeavenlyTrialComponents`（UI 层）那处是明确的反例，必须先迁线程 |
| 4 | **JNI 成本假设未实测**（我未做基准） | 阶段 3 可能被迫改方案 | 阶段 1 前先跑基准（见 §8 首行），用数据决定阶段 3 的粒度 |
| 5 | `PresentationRandom` 是否需要**每局同构**（同一存档重进游戏，战斗描述文案是否应一致）未定义 | 影响 `R3` 的种子策略 | 建议：`PresentationRandom` 由 `mapSeed` 派生但不入档 ⇒ 同会话内可复现、跨会话重放；若需跨会话一致则必须入档。**需产品口径确认，登记为待拍板** |
| 6 | 阶段 1 的 AI RNG 归一是否会让**回退臂**的 AI 序列变化（原先走影子、归一走真源） | 回退路径行为变化 | 归一后回退臂与 AUTHORITATIVE 臂**同源**，属改善；但需在批次验证里显式比对回退臂 AI 演化结果并登记 |
| 7 | `NameService`（4 处）曾做过名字随机源分区化（batch-14b），是否还有残留未走分区 | 存档可复现性盲点 | 阶段 3 单列该文件，核对 batch-14b 覆盖范围 |
| 8 | 本治理与 **WS-1 阶段 3 数据导向存储**的相互影响 | 协议演进时可能重复动抽取面 | 已在 §10 触发条件中关联；实施阶段 3 前重读 WS-1 计划 |
| 9 | 守卫规则可能误伤**测试源** | 测试里裸用 `Random` 是常见做法 | `R1`/`R3` 守卫**只扫主源**（`src/main`），测试源显式排除并在守卫里声明理由 |
