# 随机源逐处分类表（随机源治理 ADR 阶段 0 产物）

| 项 | 内容 |
|---|---|
| 文档性质 | **工作清单**：ADR 阶段 0 要求的"四类随机源入口逐处分类表"，即阶段 3 的分批依据与 `RngSourceGuardTest` 白名单的事实来源 |
| 依据 | [ADR rng-determinism-remediation.md](adr/rng-determinism-remediation.md) §4 阶段 0 / §11 盲区 1；[cpp-migration-handover-m0.md](cpp-migration-handover-m0.md) §6 |
| 扫描口径 | 生产主源：`android/{core/domain,core/engine,core/data,core/ui,feature/game,app}/src/main`（**`src/test` 显式排除**——测试裸用 `Random` 是常规做法，ADR §11 盲区 9） |
| 治理目标 | `GameRngManager.getRng(RngPartition.XXX)`（唯一合法随机源） |
| 生成日期 | 2026-09-14（阶段 0 实跑生成）；**最近全表重盘：2026-09-26（G10，守卫同口径实跑）** |

---

## 1. 五类入口定义（守卫的扫描锚点）

| 入口 | 名称 | 扫描正则 | 性质 |
|---|---|---|---|
| ① | `GameRngManager` 分区 | `getRng\s*\(` | ✅ **唯一合法** |
| ② | `kotlin.random.Random.Default`（含 `.random()` / `Math.random()`） | `\.random\(\)` / `Random\.Default` / `Math\.random` | ❌ 未治理 |
| ③ | `GameRandom`（自建 `object`，XorShift128Plus） | `GameRandom` | ✅ **已摘除**（阶段 1③ 物理删除；残留调用变编译期报错） |
| ④ | 对象/单例自持 RNG | `fromSeed\(System\.` / `ThreadLocalRandom` / `private (val\|var) <名>: Random =` | ❌ 未治理 |
| ⑤ | **默认值陷阱**（ADR §5 认定的"漏洞真正入口"） | `random: *kotlin\.random\.Random *=` / `random: *Random *=` / `rng: *kotlin\.random\.Random *=` | ❌ 未治理（默认值回落 `Random.Default`） |

> **⑤ 的判据**：默认值本身不产生随机，但**任何调用方省略实参即静默消费 `Random.Default`**——这正是 `RedeemCodeManager.kt:423`（姓名）与 `GameEngineWorldBattleOps.kt:356/370/384`（稀有度上界）两处真实缺陷的成因。项目内的正确范式是 `DiscipleFactory.kt:113`（`val random: kotlin.random.Random`，**故意无默认值**）。

---

## 2. 汇总计数（**G10 全表重盘实测**，注释剔除口径；2026-09-26）

> **口径纪律（重要，曾踩坑）**：计数**必须剔除注释**（行注释 + 块注释/KDoc）。否则 KDoc 里对被禁字面量的**引用**（如"原默认实参回落 `Random.Default` 已消除"）会被计入债务，导致：① 登记值被注释噪音撑大、真实债务被淹没；② "改注释即改守卫"。
> 另一条纪律：**同一行可命中多类**（如 `NameService.kt:92` 的 `rng: kotlin.random.Random = Random.Default` 同时命中 ②⑤），故"逐规则命中合计" > "涉及代码行数"。
> 本表数值 = `RngSourceGuardTest` 的登记上限（守卫自己报数，为**唯一权威**）。
>
> **G10 重盘（2026-09-26）**：G02–G09 删除面只删不增而登记上限未随之下调，守卫一度恒假绿
> （TASKBOOK-G10 §2-5）。本批按守卫同口径实跑全表并把上限对齐实值；差额去向逐条见 §3 处置列。

| 模块 | ② `.random()`/`Random.Default`/`Math.random` | ③ `GameRandom` | ④ 自持 RNG | ⑤ 默认值陷阱 | 逐规则合计 |
|---|---|---|---|---|---|
| `core/domain` | 6 | **0** | 0 | **12**（G10 前登记 19） | 18 |
| `core/engine` | **1**（G10 前登记 14） | **0** | 2 | 5 | 8 |
| `core/data` | 1 | 0 | 0 | 0 | 1 |
| `core/ui` | 0 | 0 | 0 | 0 | 0 |
| `feature/game` | **0**（锁死） | **0** | **0** | **0**（锁死） | 0 |
| `app` | 0 | 0 | 0 | 0 | 0 |
| **合计** | **8** | **0** | **2** | **17** | **27** |

> **③ 归零**：`GameRandom` 已物理删除，残留调用为编译期报错（**编译即守卫**）。
> **④ 由 3 → 2**（阶段 2，2026-09-14）：`CloudLayerAnimator.kt` 的 `private val random: Random = Random.Default` **默认值摘除**（`NativeSurfaceView` 传 `Random(cloudLayerSeed(宽,高))` 固定种子）。余 2 处 = `WorldMapGenerator.kt:15`、`CaveExplorationSystem.kt:39`（均 `fromSeed(System.nanoTime())` 挂钟种子，**阶段 3**）。
> **② 由 24 → 21**（阶段 2 迁 3 处）：`SectResponseTexts` 2 处（`responses.random()` → **形参必传** `random.nextInt(size)`；`core:domain` 不能依赖 `:core:engine` 的 `PresentationRandom`，故只去默认值陷阱）+ `LoadingTips` 1 处（`tips.random()` → `PresentationRandom.pick`）。
> **与旧口径「114 处」的差异**：旧数字**含注释**、且只统计 ②③ 两类字面量（漏 ④⑤）。剔除注释后 ② 类现值 21 处；⑤ 的 27 处中有相当比例是**死默认分支**（默认值从不被触发，如 `TalentRegistry` 无调用方），真实"生产省略实参"的活点约 19 处（明细见 §3 各表的处置列）。
> **阶段 2 收口批新增守卫两道**：`RngEngineIsolationGuardTest`（禁止 `object`/单例持有可变 `GameRngManager` 字段——`MissionSystem` 事故）+ `DiffAiRngSeedingTest`（AI 分区播种态跨语言等价性——`initForSlot` 裸种子事故）。

### 判定汇总（按 ADR §8 "是否写入 GameData / 实体表 / 影响数值"口径；G10 重盘版）

| 判定 | 条数（按规则命中计） | 明细 |
|---|---|---|
| 🔴 `DECISION`（影响状态） | **9** | `BeastMaterialDatabase.getRandomMaterialByBeastType:336`（妖兽材料掉落）＋ ⑤ 注册表家族 5 处（`EquipmentDatabase:224` / `HerbDatabase:235/:245` / `ItemDatabase:729/:736`，含 `GameEngineWorldBattleOps:315/329/343` 位置实参缺陷）＋ `ManualDatabase:655` ＋ ④ 挂钟播种 2 处（`WorldMapGenerator:15` / `CaveExplorationSystem:38`） |
| 🟡 `PRESENTATION`（纯表现） | **0** | 阶段 1③/2 已全部迁入表现流（`scene(key)` 派生，§7） |
| ⚪ `IRRELEVANT`（无关） | **18** | 死代码或默认分支无生产调用方（明细见 §3 处置列） |

---

## 3. 逐处分类明细（G10 重盘版，2026-09-26 实跑；G02–G09 时代的明细表随删除面废止，历史处置线索见 git 历史与 §4/§6/§8 的 dated 登记）

判定图例：🔴 `DECISION` ｜ 🟡 `PRESENTATION` ｜ ⚪ `IRRELEVANT`

### 3.1 `core:domain` — ②（6 处）

| 文件:行 | 封闭函数 | 用途 | 判定 | 处置 |
|---|---|---|---|---|
| `registry/BaseTemplateRegistry.kt:69` | `getRandom` | 按稀有度区间抽模板 | ⚪ | 死代码（`getRandomSetByRarity`/`generateRandomBySlot` 主源零调用方，G10 复核） |
| `registry/BeastMaterialDatabase.kt:336` | `getRandomMaterialByBeastType` | 按妖兽类型加权掉落 | 🔴 | **阶段 3**（生产三直调：`GameEngineWorldBattleOps:322` / `ExplorationService:364` / `PatrolBattleSystem:595`；材料入仓库，§8 已核） |
| `registry/EquipmentRegistry.kt:313` | `generateRandomBySlot` | 槽位内模板抽取 | ⚪ | 死代码（主源零调用方，G10 复核） |
| `state/EntityStore.kt:83` | `random` | 抽实体 | ⚪ | 死代码（主源零调用方，G10 复核） |
| `util/NameService.kt:92` | `generateName`（`rng = Random.Default`） | 弟子命名 | ⚪ | ②⑤ 同行；生产零调用（`RedeemCodeManager.generateDisciple` 已随 G08 删除，唯一生产省略实参方消失） |
| `util/NameService.kt:129` | `inheritName`（`rng = Random.Default`） | 继承命名 | ⚪ | ②⑤ 同行；生产零调用（原显式调用方 `ChildBirthSystem` 已删除） |

### 3.2 `core:domain` — ⑤（12 处）

| 文件:行 | 签名（节选） | 判定 | 处置 |
|---|---|---|---|
| `registry/EquipmentDatabase.kt:224` | `generateRandom(minRarity = 1, maxRarity = 6, random = Random)` | 🔴 | **阶段 3**（`GameEngineWorldBattleOps:329` 位置实参省略 ⇒ maxRarity 吃默认 6，§3.10 缺陷行） |
| `registry/EquipmentDatabase.kt:236` | `generateRandomBySlot(slot, rarity, random = Random)` | ⚪ | 默认分支死（函数本体零生产调用方） |
| `registry/EquipmentDatabase.kt:261` | `generateRarity(min, max, random = Random)`（private） | ⚪ | 仅内部显式传递 |
| `registry/HerbDatabase.kt:235` | `generateRandomHerb(minRarity = 1, maxRarity = 6, random = Random)` | 🔴 | **阶段 3**（注册表随机家族，生产省略实参） |
| `registry/HerbDatabase.kt:245` | `generateRandomSeed(minRarity = 1, maxRarity = 6, random = Random)` | 🔴 | **阶段 3**（注册表随机家族，生产省略实参） |
| `registry/ItemDatabase.kt:729` | `generateRandomPill(minRarity = 1, maxRarity = 6, random = Random)` | 🔴 | **阶段 3**（`GameEngineWorldBattleOps:343` 位置实参省略 ⇒ maxRarity 吃默认 6） |
| `registry/ItemDatabase.kt:736` | `generateRandomMaterial(minRarity = 1, maxRarity = 6, random = Random)` | 🔴 | **阶段 3**（注册表随机家族，生产省略实参） |
| `util/GameUtils.kt:70` | `applyPriceFluctuation(basePrice, random = Random)`（Int） | ⚪ | 默认分支死（生产调用全显式） |
| `util/GameUtils.kt:77` | `applyPriceFluctuation(basePrice, random = Random)`（Long） | ⚪ | 默认分支死（生产调用全显式） |
| `util/NameService.kt:92` | `generateName(..., rng = Random.Default)` | ⚪ | ②⑤ 同行（见 §3.1） |
| `util/NameService.kt:129` | `inheritName(..., rng = Random.Default)` | ⚪ | ②⑤ 同行（见 §3.1） |
| `util/SpiritRootGenerator.kt:10` | `generate(random = Random)` | ⚪ | 生产零调用（仅测试 18 处引用；G10 复核）——本体删除与否归死码清零批复盘 |

> G10 前登记 19 → 实测 12：Affix/Physique/Talent 三库的 7 处默认陷阱随天赋/体质/词条系统下线（G02/G04 删除面）。

### 3.3 `core:engine` — ②（1 处）＋ ③（0 处）

| 文件:行 | 封闭函数 | 用途 | 判定 | 处置 |
|---|---|---|---|---|
| `service/MerchantAndRecruitService.kt:332` | `createMerchantItem`（`random: kotlin.random.Random = Random.Default`） | 商人品阶/条目抽取 | ⚪ | ②⑤ 同行；默认分支死（生产 4 调用方全显式传 `rng.asKotlinRandom()`，G10 复核 `:168/:204/:408/:510`） |

> ② 类 G10 前登记 14 → 实测 1：`BattleDescriptionGenerator` 14 处措辞抽取已随 W4-C/C7 改 ID 散列确定性选词归零；`InventoryFacadeImpl` 开袋奖励族随删除面下线；`GameEngineSectLevelOps:210` / `RedeemCodeManager:423` 已治理/删除。
> ③ 类保持归零（`GameRandom` 物理删除，编译即守卫）。

### 3.4 `core:engine` — ④（2 处）与 ⑤（5 处）

| 文件:行 | 载体 | 用途 | 判定 | 处置 |
|---|---|---|---|---|
| `WorldMapGenerator.kt:15` | `private val rng by lazy { DeterministicRng.fromSeed(System.nanoTime()) }` | 世界宗门生成/关系/命名 → `worldMapSects` | 🔴 | **阶段 3 登记**（挂钟种子、不入档、不可复现；在 `SaveFacadeImpl` / `GameEngineLoadDataOps` / `GameEngineLifecycleOps` 生产可达） |
| `domain/exploration/CaveExplorationSystem.kt:38` | 同款 `nanoTime` 播种 | 守卫战构成 + 洞府奖励 | 🔴 | **阶段 3 登记**（`CaveExplorationProcessor:193`；读档后奖励不可复现） |

| ⑤ 位置 | 签名（节选） | 判定 | 处置 |
|---|---|---|---|
| `registry/ManualDatabase.kt:655` | `generateRandom(minRarity = 1, maxRarity = 6, type = null, random = Random)` | 🔴 | **阶段 3**（生产省略实参，含 `GameEngineWorldBattleOps:315` 位置实参缺陷） |
| `registry/ManualDatabase.kt:674` | `generateRarity(minRarity, maxRarity, random = Random)`（private） | ⚪ | 仅内部显式 |
| `RedeemCodeManager.kt:343` | `generateReward(..., random = Random)` | ⚪ | 默认分支死（`RedeemCodeService` 显式 MAIL） |
| `RedeemCodeRewardOps.kt:116` | `generateRandomEquipment(rarity, random = Random)` | ⚪ | 默认分支死（调用方显式） |
| `service/MerchantAndRecruitService.kt:332` | `createMerchantItem(..., random = Random.Default)` | ⚪ | ②⑤ 同行（见 §3.3） |

> ⑤ 类 G08 曾下调 7 → 5（删 `generateDisciple` 默认实参 + 补记 G04 漏删的 `generateRandomTalents`）；G10 实测仍 5（持平）。

### 3.5 `core:data`（1 处）

| 文件:行 | 封闭函数 | 用途 | 判定 | 处置 |
|---|---|---|---|---|
| `archive/DataArchiver.kt:572` | `generateBatchId` | `Math.random()` 生成归档批次 4 位随机序（文件名 + 完整性索引键） | 🔴 | **白名单保留**（基础设施，与游戏状态无关；非玩法数值，不参与存档确定性） |

### 3.6 `core:ui` / `app` / `feature/game`（0 命中，G10 起锁死为 0）

三模块主源四类正则零命中。`app` 仅 `RequestSigner.kt:311` `UUID.randomUUID()`——不属五类入口（UUID 亦广泛用于物品实例 ID，属另一维度议题）。
`feature/game` 的 ②/⑤ 末两处命中随删除面下线，守卫登记**锁死为 0**——新增即红。

### 3.9 已核对为"受治理"的边界项（**不计入**上表）

- **实为注入的 `GameRngManager` 分区**（持有 `rng` 字段但源合法）：`AISectTeamComposer:9-11`（BATTLE）、`EnemyGenerator:22-24`（ENEMY_GEN）、`MissionSystem:43-47`（MISSION）、`BattleCalculator:90`、`DisciplePurchaseService:63`、`LootCalculator:38`。
- **`java.util.Random(...)` 派生构造但源已受治理**：`EnemyGenerator:90`（ENEMY_GEN）、`RedeemCodeRewardOps:342/348`（MAIL 经参数传入）、`AISectDiscipleManagerMisc.kt:94/122` 与 `Gear.kt:86`（源为 AI 分区，**随阶段 1② 归一并消灭**）、`CaveExplorationSystem.kt:230`（源为 ④ nanoTime 流，**已计入 3.5**）。
- **`mailRng` 形参无默认值、生产全传 MAIL 适配器**：`MailAttachmentDistributeOps:320/340/360/387`、`MailAttachmentVariantsOps:20/53/91`、`RedeemCodeService:216…456`。
- **正确范式（反默认参数陷阱）**：`DiscipleFactory.kt:113`（`val random: kotlin.random.Random`，故意无默认值）。
- **治理基建**：`GameRngManager.getRng(RngPartition.XXX)`（唯一入口）、`DeterministicRng.asKotlinRandom()` / `RngRandomAdapter`（治理桥）、`DeterministicRng.shuffled(rng)`（治理洗牌，替代 `Iterable.shuffled`）。

### 3.10 🔴 途中发现的真实数值缺陷（随机源失治的叠加后果）

`GameEngineWorldBattleOps.kt:356/370/384` 以**位置实参**调用
`ManualDatabase.generateRandom(rarity)` / `EquipmentDatabase.generateRandom(rarity)` /
`ItemDatabase.generateRandomPill(rarity)`——第一实参落到 `minRarity`，`maxRarity` 吃默认值 **6**
⇒ 洞府（世界妖兽关）奖励物品的**稀有度上界被放宽到 6**，实际产出高于设计品阶。
正确写法 = `generateRandom(minRarity = rarity, maxRarity = rarity, random = <分区适配器>)`。
**处置**：随阶段 3 该域批次一并根治（与本表同源，不单独立批）。

---

## 4. 阶段 1③ 收口后的计数变化（③ 类归零）

`GameRandom` 整对象删除后，③ 类计数由 **21（含注释）→ 0**；残留调用变**编译期报错**——编译即守卫，优于正则。
② 类中 `HeavenlyTrialComponents` 两处随阶段 1③ 迁入表现随机；`SectResponseTexts` / `DiplomacyGiftTexts` / `DiplomacyVassalTexts` 已随阶段 2 迁入。其余按阶段 2/3 分批下降。

### 阶段 2/3 剩余（**截至本文件更新时**）

| 待迁文件 | 处数 | 类别 | 归属阶段 |
|---|---|---|---|
| `BattleDescriptionGenerator.kt` | 12 | 文本持久化（写 Room `battle_logs`） | 阶段 2 |
| `SectResponseTexts.kt` | 2 | 表现（送礼文案） | 阶段 2 |
| `DiscipleChatDialog.kt` | 3 | **决策类**（写弟子 skills/cultivation）——**不得**走表现流 | 阶段 3 |
| `LoadingTips.kt` | 1 | 表现（加载提示） | 阶段 2 |
| `CloudLayerAnimator.kt` | 1 | 表现（云层装饰，自持 RNG） | 阶段 2 |
| `GameEngineSectLevelOps.kt:210` | 1 | 决策（兽血材料入库存） | 阶段 3 |
| `GameEngineWorldBattleOps.kt:356/370/384` | 3 | 决策 + **位置实参越界缺陷**（`maxRarity` 吃默认 6） | 阶段 3 |
| `BeastMaterialDatabase.kt:351` | 1 | 决策（妖兽材料掉落） | 阶段 3 |
| `RedeemCodeManager.kt:423` | 1 | 决策（姓名漏传 rng） | 阶段 3 |
| ⑤ 默认值陷阱（活点） | ~19 | 决策（注册表 `generateRandom*` 省略实参） | 阶段 3 |
| ④ `WorldMapGenerator` / `CaveExplorationSystem` | 2 | 决策（挂钟种子自持流） | 阶段 3 |

> 阶段 3 的完整入口清单见 §3 中标记处置为"阶段 3"的行；`RngSourceGuardTest` 全程开着，新增未登记即测试红。

---

## 5. 与守卫测试的关系

`RngSourceGuardTest`（`core:engine/src/test/.../architecture/`）按 §1 的五条正则扫描本表同一组主源目录：

| 断言 | 口径 |
|---|---|
| `R1`/`R3` 反向断言 | ②③④ 逐模块计数 **不得超过本表 §2 的登记值**（只缩不增）；清零后**锁死为 0** |
| `R4` 清单式 | `RngPartition` 枚举新增值必须在本表登记，否则测试红并列出待办 |
| `R5` 禁止自建随机源 | ④ 类不得新增；新增即红 |
| ⑤ 默认值陷阱 | 计数不得超过本表登记值（阶段 3 清零后锁死为 0） |

> 白名单与 `intentionallyExcluded` **只允许缩**（与 `detekt-baseline-count.guard` 同纪律，CLAUDE.md 13.2）：
> 新增一条必须在本文档写明理由与偿还触发条件。

## 6. W4-A·A5 扩面与 CHAT 分区登记（2026-09-15）

### 6.1 新增分区 `CHAT`（id=10，参与 rngStates 快照）

| 项 | 内容 |
|---|---|
| 消费点 | `DiscipleChatDialog` 决策类抽取：交谈树选择 / 结果分支选择 / 效果增量随机化（`signRandom`×3 + `cultivationDelta` Double）——结果经 `updateDisciple` 写弟子 `cultivation`/`skills` ⇒ 决策类（ADR §8 口径） |
| 签发点 | `GameEngineConversationDraw.chatDraw/chatDrawDouble`（引擎上下文内 `getRng(RngPartition.CHAT)`——GameRngManager 线程契约要求，UI 线程不得直取分区句柄） |
| 为什么独立分区 | 交谈抽取插入时机由玩家行为决定，混入任何结算分区都会扰动该分区既有抽取序（红线 1） |
| 持久化 | `rngStates` 10 号键；旧档缺失按 `systemSeed + 10` 播种（MISSION 同款恢复语义）；C++ `rng_manager.h` 同步枚举 + `initSystemSeed` 播种 |
| 表现类文本变体（问候/回复/结束语） | 走 `PresentationRandom`（不落盘），UI 位实例化（LoadingScreen 同款） |

### 6.2 ② 类正则扩面显形的存量裸抽取（core/domain，8 处——**显形非新增**）

② 类正则原不匹配 `Random.nextInt/nextDouble` ⇒ 下列站点机器不可见（`DiscipleChatDialog`
的 5 个活决策点同因不可见，即 handover §12 债表"守卫盲区"项）。扩面后逐个核实，
8 处均为**死代码或仅测试调用方**（生产零调用）：

| 站点 | 事实 |
|---|---|
| `AISectPersonality.kt:65/:72` | `random()` 扩展仅测试调用 |
| `Items.kt:875`（`PillGrade.random`） | 唯一调用方 `ProductionProcessorAlchemyTest` |
| `BaseTemplateRegistry.kt:143/:165/:189` | `pickWeightedRandom/generateTieredRarity` 仅测试 |
| `BeastMaterialDatabase.kt:336/:351` | `getRandomMaterialByRealm` 仅测试 |

守卫登记上限 core/domain ② 5 → 13（一次性扩面登记）；**偿还触发条件 = W4-D/D5
死代码清零批**（先补"生产调用 0 / 测试引用 n"清单再删），清偿后同步下调。

---

## 8. R4.4/B14 新增分区 `RESIDUAL`（id=11）——残留执行器本地随机域（2026-09-19）

### 8.1 分区登记

| 项 | 内容 |
|---|---|
| 分区 | `RngPartition.RESIDUAL`（id=**11**，`inSnapshot=true`，`isLocal=true`） |
| 播种公式 | `DeterministicRng.fromSeed(systemSeed + 11)`（与既有分区**同式**，Kotlin `rebuildPartitions` / `reseedMissingPartitions` 与 C++ `RngManager::initSystemSeed` 三处同源） |
| 持久化 | `rngStates` **11 号键**（`Map<Int, Long>`，schema 零变更，仅新增键值）；旧档缺失该键 ⇒ 按 `systemSeed + 11` **确定性重种**（MISSION(8)/CHAT(10) 同款恢复语义，见 `GameRngManager.reseedMissingPartitions`） |
| C++ 同步 | `gamecore/rng/rng_manager.h`：枚举 `kResidual = 11` + `initSystemSeed` 播种 `seed + 11` + `kMaxPartitionId` 上界随之上移（原钉在 `kAiSectMirror`）。**C++ 侧无生产消费点**——登记与播种是为读档面/对拍面两侧枚举口径一致 |
| 为什么独立分区 | R4.4 前该域的随机**全部**经 `NativeBackedRng` 逐 roll 跨 JNI 取标量（`NativeBackedRng.kt:46`）。把该域独立成 `isLocal` 分区后，Kotlin 持有本地 PCG 实例，抽取/快照/恢复全在本地完成 ⇒ **零 per-roll JNI**。同时该域与既有分区彻底解耦：本分区抽取**不得**扰动任何既有分区的抽取序（红线 1） |

### 8.2 消费面枚举（B14 实施前——"残留执行器"的实际消费面）

本文档口径 = **以 `NativeBackedRng` 逐 roll 委托的实际消费面为准**（方案原文指认
"残留执行器"= B09 退化后的平台效应适配器；其随机消费点即下表）。枚举结果：
**生产源码中无任何 `RngPartition` 消费点需要迁移**——见 §8.3 的实测结论。

| 消费点（batch 文件点名） | 实际分区 | 频率 | 存档依赖 | 迁移处置 |
|---|---|---|---|---|
| `GameEngineCoreAuthoritativeOps` 月/年变编排 | **无**（本文件 `grep RngPartition` 零命中） | — | — | 无需迁移（编排本身不直接抽取；其下辖服务各自持分区） |
| `BattleExecutionRouter` 回退臂 | **无**（本文件 `grep RngPartition/GameRngManager` 零命中） | — | — | 无需迁移（路由只调 `nativeBattleExecute`；BATTLE 抽取在 C++ 侧，Kotlin 侧消费点为 `BattleSystem:31`，走 BATTLE 分区不变） |
| `AISectDiscipleManager` | `AI_SECT_MIRROR(9)` / `AI_SECT(6)`（按模式解析，`:115`） | 低频（AI 弟子生成/装备补全） | 通道型（9 号键归 C++ `aiRng_` 保管） | **不迁移**：AI 域与 C++ `aiRng_` 同源是**设计目标**（`DiffAiRngSeedingTest` 锁守），通道型语义属红线 |
| 其余 11 个既有分区消费面（BATTLE / BREAKTHROUGH / EXPLORATION / SYSTEM / ENEMY_GEN / MAIL / AI_SECT / SECRET_REALM / MISSION / CHAT） | 各自分区 | 见下表逐条 | 各分区持久化键 | **逐一不迁移**：红线 1 要求既有分区的委托关系与抽取序**逐位不变** |

**既有分区消费点逐条清单**（`grep -rn "RngPartition\." android/{core,feature,app}/src/main`，
G10 重盘 2026-09-26：共 **72** 命中，按分区归并；G02–G09 删除面使 `SYSTEM` 由 35 → 16、
`BREAKTHROUGH` 由 2 → 1、`BATTLE` 由 8 → 7）：

| 分区 | 主要消费点（G10 实测） |
|---|---|
| `SYSTEM(3)`（16 处） | `ProductionProcessor处理Ops1`（4，含 1 KDoc）/ `BuildingService`（3）/ `GiftService` / `VassalService` / `DiplomacyService` / `DiscipleService` / `DisciplePurchaseService` / `MerchantAndRecruitService` / `ProductionProcessorBatcOps4` / `ProductionTransactionManager` / `GameEngineGuideOps` |
| `EXPLORATION(2)`（8） | `LevelGenerator` / `AISectBeastAttackProcessor` / `BeastAttackDetector` / `LootCalculator` / `PatrolBattleSystem` / `WorldLevelManager`(2) / `InventoryFacadeImpl` |
| `BATTLE(0)`（7） | `BattleSystem` / `AISectAttackDecisionOps` / `AISectAttackManager`(2) / `ExplorationService` / `GameEngineBattleOps`(2) |
| `AI_SECT(6)`（7，含 KDoc 与注册点） | `AISectDiscipleManager`(6) / `GameRngManager`(注册点) |
| `GACHA(12)`（6） | `GachaPullLedger`(2) / `GachaFacade` / `GachaService`(3)（§9） |
| `EQUIPMENT(13)`（B3 新增，主源 15 处命中 = 9 代码消费点 + 6 KDoc 引用） | 代码消费：`EquipmentFactory` 调用方 `GameEngineWorldBattleOps` / `BuildingService` / `DiplomacyService` / `InventoryFacadeImpl` / `AutoBuyService` / `ProductionProcessor处理Ops1` / `ProductionProcessorBatcOps4` / `RedeemCodeService` / `EquipmentUpgradeService`(强化节点)（§10） |
| `CHAT(10)`（6） | `GameEngineConversationDraw`(5) / `GameEngineCoordination`(KDoc) |
| `MAIL(5)`（5） | `GameEngineSectLevelOps`(2) / `MailAttachmentDistributeOps` / `RedeemCodeService`(2) |
| `AI_SECT_MIRROR(9)`（4） | `AISectDiscipleManager`(3) / `GameRngManager`(注册点) |
| `SECRET_REALM(7)`（3） | `SecretRealmService`(3) |
| `MISSION(8)`（2） | `MissionSystem`(2) |
| `RESIDUAL(11)`（1） | `GameRngManager`(注册/恢复语义点) |
| `BREAKTHROUGH(1)`（1） | `DiscipleBreakthroughHandler` |
| `ENEMY_GEN(4)`（1） | `EnemyGenerator` |
| 枚举基建裸引用（6） | `RngPartition.entries`/守卫/恢复语义等非具体分区行 |

> G10 重盘核减说明：`LawEnforcement*`（9）/ `RecruitService` / `ChildBirthSystem` / `PartnerSystem` /
> `GameEngineSpiritRootOps` / `GameEngineTraitAddOps` / `GameEngineTraitWashOps` 消费方已随 G02–G09
> 删除面下线。

### 8.3 实测结论（B14 迁移面为零的诚实登记）

`grep -rn "RngPartition\.RESIDUAL"` 在**任何生产源码中零命中**——本批次
**没有把任何既有消费点切到新分区**。原因：batch-R4C.md 任务 1 要求"以
`NativeBackedRng` 逐 roll 委托的实际消费面为准"枚举每个消费点的分区/频率/存档
依赖；枚举后**全部命中点均已有明确归属**（见 §8.2），按红线 1「其余分区
（BATTLE/BREAKTHROUGH/…）的既有委托关系与序列不动」，**不得**把它们迁到新分区。

因此本批的交付形态是：**新建独立分区 + 本地 PCG 化机制 + 逐 roll 跨线行为级
守卫**，而"迁移量"这一维度为**零**。这是刻意且必要的结果——构造一个真实
消费面只会违反红线 1。

**残余登记（诚实）**：`RESIDUAL(11)` 当前无生产消费点。其价值有二：
1. **机制在位于被测**：`ResidualRngLocalityGuardTest` 7 用例实证"本地 PCG 分区
   在委托模式下零跨线""其余分区委托关系不受影响""老档无键确定性重种""快照
   恢复往返"——后续批次接入真实残留消费面时，只需把消费点指向该分区即获得
   零跨线 + 存取档确定性，无需再改基础设施；
2. **per-roll JNI 消除的判据已可复跑**：通道计数替身给出了可机器复跑的
   "零跨线"证明（对照臂证明 BATTLE 等仍逐 roll 委托）。

---

## 7. 表现流取用方式收口：`scene(key)` 场景派生（2026-09-15，W4 实施文档 §2.B 落地）

> **背景**：`PresentationRandom.seedFromWorld(mapSeed)` 自引入（`85498c4c3`）起**全仓零调用**
> ⇒ 表现流种子恒为编译期常量 `DEFAULT_SEED`，KDoc"按 mapSeed 派生"与实现不符（§2.68 复验登记）。

### 7.1 新口径（已实施）

| 项 | 内容 |
|---|---|
| 播种接线（全仓唯一） | `BootSequenceController.generateMapPreloadData()`：`if (mapSeed != 0) presentationRandom.seedFromWorld(mapSeed.toLong())`——boot 是新档/读档唯一汇合点，一处接线覆盖两端；守卫 = `PresentationRandomSceneTest.seedFromWorld has a production call site`（源码扫描） |
| 场景派生 | `scene(key)` = 独立实例，序列由 `(worldSeed, FNV-1a 64(key))` 唯一决定 ⇒ **同一存档 + 同一场景实例恒定（跨会话一致）**，异键无关，与根流零耦合；哈希跨平台锚点以字面量锁死（iOS 侧须复现） |
| 仍不入档 | 表现流零协议面（拍板口径：入档收益仅"第 N 次进入第 N 套文案"，不划算） |
| 键纪律 | 键必须含场景实例身份；常量键仅限"本就该固定轮播"场景（loading.tip） |

### 7.2 场景键登记表（实施时逐点接线，新增消费点照此登记）

| 场景 | 键 | 落点 |
|---|---|---|
| 天劫立绘（无立绘分支按性别取像） | `"trial.portrait." + combatant.id` | `HeavenlyTrialComponents.CombatantPortrait` |
| 弟子交谈文本变体 | `"chat." + disciple.id + "." + gameYear` | `DiscipleChatDialog`（`remember(disciple.id, gameYear)`；决策类抽取仍走 CHAT 分区不变） |
| 外交赠礼/附庸文案（8 抽取点） | `"diplomacy." + sectId + ".{gift.player,gift.aiAccept,gift.aiReject,gift.reply,vassal.request,vassal.reply,vassal.dissolvePlayer,vassal.dissolveAi}"` | `DiplomacyFlows.diplomacyScene`（builder 签名未动） |
| 送礼反馈文案（好感度） | `"favor." + sectId + ".gift.{accept,rejected}"` | `GiftService`（**native 臂与 Kotlin 臂同键** ⇒ flag 两侧文案一致） |
| 加载提示轮播 | `"loading.tip"`（常量键=刻意：固定轮播） | `LoadingScreen` |
| 装饰/动画（云层等） | **不改**（"持续变化"语义，config 维度固定种子） | `CloudLayerAnimator` / `NativeSurfaceView` |

### 7.3 测试面

`PresentationRandomSceneTest`（新增 7 用例）：同键逐位相同 / 异键不同 / 世界种子参与派生 /
与根流互不影响 / 零状态写入（FakeAtomicStateStore 快照前后相等）/ FNV-1a 字面量锚点 /
`seedFromWorld` 生产调用点守卫。

---

## 8. W4-D/D5 死代码清零 + 守卫面收口（2026-09-17，handover §2.83；§2.84 登记）

§6.2 登记的 8 处显形存量裸抽取**已清偿销账**——逐点核实仅 7 处为死代码，随 §2.83 B6–B10 删除；
**1 处存活保留**：`BeastMaterialDatabase.getRandomMaterialByBeastType`（`Random.nextDouble()` 加权抽取）
经核实有 **3 个生产调用方**（`GameEngineWorldBattleOps:322` / `ExplorationService:364` /
`PatrolBattleSystem:595`，妖兽材料掉落）⇒ **非死代码**，归 RNG 阶段 3 分区化清单（注册表随机家族同批）。

| 守卫面 | 变化 |
|---|---|
| `RngSourceGuardTest` core/domain ② 登记上限 | **13 → 6**（= 原基线 5 + 存活 1，附归属说明；只缩不增） |
| `RngEngineIsolationGuardTest` 白名单 | **4 → 1**（3 条陈旧"同族遗留"豁免随 W4-C C-③ 形参必传删除）+ **白名单条目数计数断言**（`expectedExclusionCount = 1`——新增豁免从此机器可拦） |

**阶段 3 已交付子项（指针）**：弟子侧 = §6 `CHAT` 分区（handover §2.62.1 W4-A/A5）；战斗侧 =
`BattleDescriptionGenerator` 14 处 `.random()` 归零——ID 散列确定性选词，零抽取零分区影响
（handover §2.64.1 W4-C/C7）。**阶段 3 余量登记** = 注册表随机家族（`MaterialTemplateRegistry.generateRandom`
家族 + 上列 `getRandomMaterialByBeastType`）+ §3 表两处挂钟播种（`WorldMapGenerator.kt:15` /
`CaveExplorationSystem.kt:39`——⚠️ 其证据行所引 `CaveExplorationRewardOps.kt:29` 已随 §2.63.B4
洞府死链删除**整文件移除**，`CaveExplorationSystem` 本体与挂钟播种仍在，登记行证据需换）+
⑤ 默认值陷阱清零（上限 27，未动）。

---

## 9. G09 新增分区 `GACHA`（id=12）——寻访抽卡独立随机域（2026-09-26）

| 项 | 内容 |
|---|---|
| 分区 | `RngPartition.GACHA`（id=**12**，`inSnapshot=true`，`isLocal=false`）/ C++ `RngPartition::kGacha = 12` |
| 消费点 | **C++ 权威臂**：`gamecore/system/gacha_tx.h`（单抽/十连的类别权重、角色候选、品阶权重、物品候选四类掷点）。**Kotlin 回退臂**：`core/engine/domain/gacha/GachaPullLedger.kt`（非 AUTHORITATIVE 下逐字同式复刻，取同一分区）。两臂取的是同一条流的同一位置 ⇒ 双臂对拍可锁 |
| 播种公式 | `DeterministicRng.fromSeed(systemSeed + 12)`（Kotlin `rebuildPartitions` / `reseedMissingPartitions` 与 C++ `RngManager::initSystemSeed` 三处同式） |
| 持久化 | `rngStates` **12 号键**（schema 零变更，只多一键）；旧档缺该键 ⇒ 按 `systemSeed + 12` 确定性重种（MISSION(8)/CHAT(10)/RESIDUAL(11) 同款恢复语义） |
| C++ 同步 | `rng_manager.h`：枚举 `kGacha = 12` + `initSystemSeed` 播种 `seed + 12` + **`kMaxPartitionId` 由 `kResidual` 上移到 `kGacha`**（不上移则 JNI 合法分区守卫会静默拒绝抽卡掷点，MISSION(8) 前例） |
| 为什么独立分区 | 与 §6.1 `CHAT` 同因：抽卡的插入时机与次数完全由玩家点击决定（次数无上限），共用任何结算分区都会让玩家行为挪动该分区的既有抽取序（红线 1）。与 RESIDUAL 不同，本分区在 C++ 侧**有真实生产消费点**，因此 §`rng_test.cpp` 除登记/播种外另证「抽卡 50 次不外溢到 BATTLE/SYSTEM/CHAT/MAIL」 |
| 守卫面 | `RngSourceGuardTest`：`registeredPartitionIds`/`snapshotPartitionIds` +12、`expectedNames` 追加 `GACHA`、新增 `G09 抽卡分区必须是 12 号委托分区且参与快照` 用例；`rng_test.cpp`：快照分区数 11→12、`kMaxPartitionId` 11→12 + 五条 GACHA 用例；`ResidualRngLocalityGuardTest`：最大快照键 11→12 |
| 不扰动既有金黄的实证口径 | `DeterminismProbe` 哈希的是行为 transcript，**不含 `rngStates` 映射**，且既有金黄夹具不做抽卡 ⇒ 本分区追加不改变任何既有掷点序（详见 `docs/design/gacha-batches/BENCHMARK-gacha-rng-partition.md` 与 `report-G09.md`） |
| 本行未覆盖 | §2/§3 的**整行盘点仍属 G10**（本文件多处行号自 2026-09-14 起已随 G02–G08 的删除面失真，按 `grep -rn "RngPartition\."` 重跑，不做局部修补） |

---

## 10. B3 新增分区 `EQUIPMENT`（id=13）——装备生成与升级独立随机域（2026-09-30，EQ-B3）

| 项 | 内容 |
|---|---|
| 分区 | `RngPartition.EQUIPMENT`（id=**13**，`inSnapshot=true`，`isLocal=false`）/ C++ `RngPartition::kEquipment = 13` |
| 消费点 | **C++ 权威臂**：`gamecore/system/equipment_tx.h`（生成期主词条/副词条抽取）+ `execute_dispatch.cpp`（强化节点 roll）。**Kotlin 侧**：`EquipmentFactory.create`（主词条 → 副词条抽取序）及其调用方（`GameEngineWorldBattleOps` / `BuildingService` / `DiplomacyService` / `InventoryFacadeImpl` / `AutoBuyService`）与 `EquipmentUpgradeService`（每 3 级强化节点）取同一分区——抽取时机由锻造/掉落/购买/升级等**离散事务**驱动，与 GACHA 同因（玩家时序独立流，不与任何结算分区共用） |
| 播种公式 | `DeterministicRng.fromSeed(systemSeed + 13)`（Kotlin `rebuildPartitions` / `reseedMissingPartitions` 与 C++ `RngManager::initSystemSeed` 同式） |
| 持久化 | `rngStates` **13 号键**（schema 零变更，只多一键）；旧档缺该键 ⇒ 按 `systemSeed + 13` 确定性重种（MISSION(8)/CHAT(10)/RESIDUAL(11)/GACHA(12) 同款恢复语义） |
| C++ 同步 | `rng_manager.h`：枚举 `kEquipment = 13` + `initSystemSeed` 播种 `seed + 13`（EQ-B3 已落）。⚠️ **本批残留缺口（归 cpp 面，非本文件登记范围）**：`RngManager::kMaxPartitionId` 仍 = `kGacha`(12)，未随新最大 id 上移到 `kEquipment`——按 G09 前例（MISSION(8)）会导致 AUTHORITATIVE 下 13 号掷点被 JNI 合法性守卫拒绝，须由主会话同步上移 |
| 守卫面 | `RngSourceGuardTest`：`registeredPartitionIds`/`snapshotPartitionIds` +13、`expectedNames` 追加 `EQUIPMENT`；`ResidualRngLocalityGuardTest`：最大快照键 12→13 |
