# 随机源逐处分类表（随机源治理 ADR 阶段 0 产物）

| 项 | 内容 |
|---|---|
| 文档性质 | **工作清单**：ADR 阶段 0 要求的"四类随机源入口逐处分类表"，即阶段 3 的分批依据与 `RngSourceGuardTest` 白名单的事实来源 |
| 依据 | [ADR rng-determinism-remediation.md](adr/rng-determinism-remediation.md) §4 阶段 0 / §11 盲区 1；[cpp-migration-handover-m0.md](cpp-migration-handover-m0.md) §6 |
| 扫描口径 | 生产主源：`android/{core/domain,core/engine,core/data,core/ui,feature/game,app}/src/main`（**`src/test` 显式排除**——测试裸用 `Random` 是常规做法，ADR §11 盲区 9） |
| 治理目标 | `GameRngManager.getRng(RngPartition.XXX)`（唯一合法随机源） |
| 生成日期 | 2026-09-14（阶段 0 实跑生成） |

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

## 2. 汇总计数（**阶段 2 收口后实测**，注释剔除口径；2026-09-14）

> **口径纪律（重要，曾踩坑）**：计数**必须剔除注释**（行注释 + 块注释/KDoc）。否则 KDoc 里对被禁字面量的**引用**（如"原默认实参回落 `Random.Default` 已消除"）会被计入债务，导致：① 登记值被注释噪音撑大、真实债务被淹没；② "改注释即改守卫"。
> 另一条纪律：**同一行可命中多类**（如 `CloudLayerAnimator.kt:30` 的 `private val random: Random = Random.Default` 同时命中 ②④⑤），故"逐规则命中合计" > "涉及代码行数"。
> 本表数值 = `RngSourceGuardTest` 的登记上限（守卫自己报数，为**唯一权威**）。

| 模块 | ② `.random()`/`Random.Default`/`Math.random` | ③ `GameRandom` | ④ 自持 RNG | ⑤ 默认值陷阱 | 逐规则合计 |
|---|---|---|---|---|---|
| `core/domain` | **5**（阶段 0 为 7） | **0** | 0 | 19 | 24 |
| `core/engine` | 14 | **0** | 2 | 7 | 23 |
| `core/data` | 1 | 0 | 0 | 0 | 1 |
| `core/ui` | 0 | 0 | 0 | 0 | 0 |
| `feature/game` | **1**（阶段 0 为 2） | **0** | **0**（阶段 0 为 1） | 1 | 2 |
| `app` | 0 | 0 | 0 | 0 | 0 |
| **合计** | **21** | **0** | **2** | **27** | **50** |

> **③ 归零**：`GameRandom` 已物理删除，残留调用为编译期报错（**编译即守卫**）。
> **④ 由 3 → 2**（阶段 2，2026-09-14）：`CloudLayerAnimator.kt` 的 `private val random: Random = Random.Default` **默认值摘除**（`NativeSurfaceView` 传 `Random(cloudLayerSeed(宽,高))` 固定种子）。余 2 处 = `WorldMapGenerator.kt:15`、`CaveExplorationSystem.kt:39`（均 `fromSeed(System.nanoTime())` 挂钟种子，**阶段 3**）。
> **② 由 24 → 21**（阶段 2 迁 3 处）：`SectResponseTexts` 2 处（`responses.random()` → **形参必传** `random.nextInt(size)`；`core:domain` 不能依赖 `:core:engine` 的 `PresentationRandom`，故只去默认值陷阱）+ `LoadingTips` 1 处（`tips.random()` → `PresentationRandom.pick`）。
> **与旧口径「114 处」的差异**：旧数字**含注释**、且只统计 ②③ 两类字面量（漏 ④⑤）。剔除注释后 ② 类现值 21 处；⑤ 的 27 处中有相当比例是**死默认分支**（默认值从不被触发，如 `TalentRegistry` 无调用方），真实"生产省略实参"的活点约 19 处（明细见 §3 各表的处置列）。
> **阶段 2 收口批新增守卫两道**：`RngEngineIsolationGuardTest`（禁止 `object`/单例持有可变 `GameRngManager` 字段——`MissionSystem` 事故）+ `DiffAiRngSeedingTest`（AI 分区播种态跨语言等价性——`initForSlot` 裸种子事故）。

### 判定汇总（按 ADR §8 "是否写入 GameData / 实体表 / 影响数值"口径）

| 判定 | 条数 | 说明 |
|---|---|---|
| `DECISION`（影响状态） | **38** | 其中**数值型 25**（真实玩法风险）+ **文本持久化 12**（`BattleDescriptionGenerator` 写 Room `battle_logs` 实体）+ **持久标识 1**（归档批次 ID） |
| `PRESENTATION`（纯表现） | **13** | 文案/提示/立绘/装饰；**不写任何持久字段** |
| `IRRELEVANT`（无关） | **43** | 死代码或默认分支无生产调用方（20 条连测试都不调用） |

---

## 3. 逐处分类明细

判定图例：🔴 `DECISION` ｜ 🟡 `PRESENTATION` ｜ ⚪ `IRRELEVANT`

### 3.1 `core:domain` — ②（12 行命中）

| 文件:行 | 封闭函数 | 用途 | 判定 | 处置 |
|---|---|---|---|---|
| `registry/BaseTemplateRegistry.kt:70` | `getRandom` | 按稀有度区间抽模板 | ⚪ | 死代码（唯一调用方 `GameDataManager.getRandomSetByRarity` 无调用方） |
| `registry/BaseTemplateRegistry.kt:143` | `pickWeightedRandom` | 权重抽样 | ⚪ | 死代码（调用方 `BeastMaterialRegistry` 无生产调用方） |
| `registry/BaseTemplateRegistry.kt:165` | `generateWeightedRarity` | 稀有度分布 | ⚪ | 死代码（仅测试） |
| `registry/BaseTemplateRegistry.kt:189` | `generateTieredRarity` | 阶梯稀有度 | ⚪ | 死代码（仅测试） |
| `registry/BeastMaterialDatabase.kt:336` | `getRandomMaterialByRealm` | 按境界加权掉落 | ⚪ | 死代码（仅测试） |
| `registry/BeastMaterialDatabase.kt:351` | `getRandomMaterialByBeastType` | 按妖兽类型加权掉落 | 🔴 | **阶段 3**（生产三直调：`ExplorationService:357` / `PatrolBattleSystem:595` / `GameEngineWorldBattleOps:299`；材料入仓库） |
| `registry/EquipmentRegistry.kt:313` | `generateRandomBySlot` | 槽位内模板抽取 | ⚪ | 死代码 |
| `config/SectResponseTexts.kt:104` | `getAcceptResponse` | 接受送礼文案 | 🟡 | **阶段 2**（`GiftService:150/366` → `GiftResult.message` → 仅聊天展示） |
| `config/SectResponseTexts.kt:123` | `getRejectResponse` | 拒绝送礼文案 | 🟡 | **阶段 2**（`GiftService:109/376`） |
| `model/AISectPersonality.kt:65` | `AISectPersonality.random` | 按权重抽 AI 个性 | ⚪ | 死代码（仅测试；生产只读 `aiSectPersonalities`） |
| `model/AISectPersonality.kt:72` | `randomDenounceInterval` | 谴责间隔月数 | ⚪ | 死代码（仅测试） |
| `state/EntityStore.kt:83` | `random` | 抽实体 | ⚪ | 死代码（全仓无调用方） |

### 3.2 `core:domain` — ⑤（17 处默认值陷阱）

| 文件:行 | 签名（节选） | 判定 | 处置 |
|---|---|---|---|
| `registry/AffixDatabase.kt:440` | `rollSingleAffix(random = Random, excludedTemplates = emptySet())` | ⚪ | 默认分支死（唯一调用方显式传 rng） |
| `registry/AffixDatabase.kt:449` | `generateForDisciple(random = Random)` | ⚪ | 默认分支死（4 处生产调用全显式） |
| `registry/EquipmentDatabase.kt:224` | `generateRandom(minRarity = 1, maxRarity = 6, random = Random)` | 🔴 | **阶段 3**（≥6 处生产省略实参） |
| `registry/EquipmentDatabase.kt:236` | `generateRandomBySlot(slot, rarity, random = Random)` | ⚪ | 默认分支死（`EnemyGenerator:101` 显式） |
| `registry/EquipmentDatabase.kt:261` | `generateRarity(min, max, random = Random)`（private） | ⚪ | 仅 `:228` 内部显式传递 |
| `registry/HerbDatabase.kt:235` | `generateRandomHerb(minRarity = 1, maxRarity = 6, random = Random)` | 🔴 | **阶段 3**（`AISectTeamComposer:194` / `MerchantItemConverter:200` / `HerbRegistry:133` 省略） |
| `registry/HerbDatabase.kt:245` | `generateRandomSeed(minRarity = 1, maxRarity = 6, random = Random)` | 🔴 | **阶段 3**（`AISectTeamComposer:208` / `MerchantItemConverter:224` / `HerbRegistry:144`） |
| `registry/ItemDatabase.kt:771` | `generateRandomPill(minRarity = 1, maxRarity = 6, random = Random)` | 🔴 | **阶段 3**（5 处省略） |
| `registry/ItemDatabase.kt:778` | `generateRandomMaterial(minRarity = 1, maxRarity = 6, random = Random)` | 🔴 | **阶段 3**（3 处省略） |
| `registry/PhysiqueDatabase.kt:327` | `rollSinglePhysique(random = Random, ...)` | ⚪ | 默认分支死 |
| `registry/PhysiqueDatabase.kt:336` | `generateForDisciple(random = Random)` | ⚪ | 默认分支死 |
| `registry/TalentDatabase.kt:570` | `generateRandomTalents(count, maxRarity = 3, random = Random)` | ⚪ | 死代码（省略方 `TalentRegistry:55` 无调用方） |
| `registry/TalentDatabase.kt:606` | `rollSingleTalent(random = Random, ...)` | ⚪ | 默认分支死 |
| `registry/TalentDatabase.kt:615` | `generateTalentsForDisciple(random = Random)` | ⚪ | 死代码（`TalentRegistry:64` 无调用方） |
| `util/NameService.kt:92` | `generateName(gender, style, existingNames, rng = Random.Default)` | 🔴 | **阶段 3**（`RedeemCodeManager:423` 生产省略 → 姓名消费非确定性流） |
| `util/NameService.kt:129` | `inheritName(parentSurname, gender, existingNames, rng = Random.Default)` | ⚪ | 默认分支死（`ChildBirthSystem:147` 显式） |
| `util/GameUtils.kt:70/77` | `applyPriceFluctuation(basePrice, random = Random)` | ⚪ | 默认分支死（生产调用全显式） |

### 3.3 `core:engine` — ②（27 行命中）

| 文件:行 | 封闭函数 | 用途 | 判定 | 处置 |
|---|---|---|---|---|
| `domain/inventory/InventoryFacadeImpl.kt:693` | `generatePillReward` | 开袋抽丹药 | 🔴 | **阶段 1①** |
| `domain/inventory/InventoryFacadeImpl.kt:703` | `generateHerbReward` | 开袋抽灵草模板 | 🔴 | **阶段 1①** |
| `domain/inventory/InventoryFacadeImpl.kt:716` | `generateSeedReward` | 开袋抽种子模板 | 🔴 | **阶段 1①** |
| `domain/inventory/InventoryFacadeImplApplOps.kt:200` | `generateEquipmentReward` | 开袋抽装备 | 🔴 | **阶段 1①**（默认值陷阱调用点） |
| `domain/inventory/InventoryFacadeImplApplOps.kt:212` | `generateManualReward` | 开袋抽功法模板 | 🔴 | **阶段 1①** |
| `domain/inventory/InventoryFacadeImpl.kt:727` | `generateMaterialReward` | 开袋抽材料 | 🔴 | **阶段 1①** |
| `GameEngineSectLevelOps.kt:210` | `buildSectLevelRewardCards` | 抽兽血材料入库存 | 🔴 | **阶段 3**（`claimSectLevelReward:80` ← `SectDelegate:52`） |
| `domain/battle/BattleDescriptionGenerator.kt:95/103/105/113/123` | `generateAttackDescription` | 闪避/动词/暴击/击杀措辞（5） | 🔴 | **阶段 2**（文本持久化，见 §3.6 口径说明） |
| `domain/battle/BattleDescriptionGenerator.kt:140/144/158/173` | `generateSkillDescription` | 闪避/施法/暴击/击杀措辞（4） | 🔴 | **阶段 2** |
| `domain/battle/BattleDescriptionGenerator.kt:189` | `generateSupportSkillDescription` | 施法措辞 | 🔴 | **阶段 2** |
| `domain/battle/BattleDescriptionGenerator.kt:227/261` | `generateAoeSkillDescription` | 施法/击杀措辞（2） | 🔴 | **阶段 2** |
| `RedeemCodeManager.kt:423` | `generateDisciple` | 调 `NameService.generateName` **省略 rng** | 🔴 | **阶段 3**（同函数其余抽取均走 MAIL 分区，仅此一处漏出） |

### 3.4 `core:engine` — ③（4 行命中）

| 文件:行 | 封闭函数 | 用途 | 判定 | 处置 |
|---|---|---|---|---|
| `GameEngineLoadDataOps.kt:262` | `createNewGame` | `mapSeed` 生成（并播种 AI 分区 + 9 个 `GameRngManager` 分区） | 🔴 | **阶段 1③**（改显式熵源 `EngineEntropy`；理由见 ADR 登记） |
| `GameEngineLoadDataOps.kt:332` | `restartGameInternal` | 同上（重开档） | 🔴 | **阶段 1③** |
| `domain/disciple/DiscipleFactory.kt:90`、`DiscipleService.kt:132` 等 | — | **KDoc/注释提及**（历史溯源说明） | ⚪ | 注释文本，非调用点 |

### 3.5 `core:engine` — ④（3 行命中）与 ⑤（7 处）

| 文件:行 | 载体 | 用途 | 判定 | 处置 |
|---|---|---|---|---|
| `domain/diplomacy/AISectDiscipleManager.kt:64` | `object` 字段 `private var _rng: DeterministicRng?`（`:66` getter、`:84` `initForSlot`，种子 `systemSeed + AI_SECT.id*31337`） | AI 弟子全域随机 | 🔴 | **阶段 1②**（影子摘除 + 归一分区 9 镜像） |
| `WorldMapGenerator.kt:15` | `private val rng by lazy { DeterministicRng.fromSeed(System.nanoTime()) }` | 世界宗门生成/关系/命名 → `worldMapSects` | 🔴 | **阶段 3 登记**（挂钟种子、不入档、不可复现；在 `SaveFacadeImpl:53/54` / `GameEngineLoadDataOps:401/402` / `GameEngineLifecycleOps:104/105` 生产可达） |
| `domain/exploration/CaveExplorationSystem.kt:39` | 同款 `nanoTime` 播种 | 守卫战构成 + 洞府奖励 | 🔴 | **阶段 3 登记**（`CaveExplorationProcessor:193` / `CaveExplorationRewardOps:29`；读档后奖励不可复现） |

| ⑤ 位置 | 签名（节选） | 判定 | 处置 |
|---|---|---|---|
| `registry/ManualDatabase.kt:655` | `generateRandom(minRarity = 1, maxRarity = 6, type = null, random = Random)` | 🔴 | **阶段 3**（4 处生产省略实参） |
| `registry/ManualDatabase.kt:674` | `generateRarity(minRarity, maxRarity, random = Random)`（private） | ⚪ | 仅 `:658` 内部显式 |
| `RedeemCodeManager.kt:349` | `generateReward(..., random = Random)` | ⚪ | 默认分支死（`RedeemCodeService:394` 显式 MAIL） |
| `RedeemCodeManager.kt:414` | `generateDisciple(config, existingNames, random = Random)` | ⚪ | 默认分支死 |
| `RedeemCodeManager.kt:493` | `generateRandomTalents(random = Random)`（internal 供测试） | ⚪ | 默认分支死 |
| `RedeemCodeRewardOps.kt:186` | `generateRandomEquipment(rarity, random = Random)` | ⚪ | 默认分支死（`:202` 显式） |
| `service/MerchantAndRecruitService.kt:224` | `createMerchantItem(..., random = Random.Default)` | ⚪ | 默认分支死（`:88/:300/:371` 生产全传 `rng.asKotlinRandom()`） |

### 3.6 `core:data`（1 行命中）

| 文件:行 | 封闭函数 | 用途 | 判定 | 处置 |
|---|---|---|---|---|
| `archive/DataArchiver.kt:572` | `generateBatchId` | `Math.random()` 生成归档批次 4 位随机序（文件名 + 完整性索引键） | 🔴 | **白名单保留**（基础设施，与游戏状态无关；非玩法数值，不参与存档确定性） |

### 3.7 `core:ui` / `app`（0 命中）

两模块主源零命中。`app` 仅 `RequestSigner.kt:311` `UUID.randomUUID()`——不属五类入口（UUID 亦广泛用于物品实例 ID，属另一维度议题）。

### 3.8 `feature/game` — ②（11 行）+ ③（3 行）+ ④⑤（各 1）

| 文件:行 | 封闭函数 | 用途 | 判定 | 处置 |
|---|---|---|---|---|
| `ui/game/LoadingTips.kt:29` | `randomTip` | 加载提示轮播 | 🟡 | **阶段 2**（`LoadingScreen:161/165`） |
| `ui/game/dialogs/DiplomacyGiftTexts.kt:86/94/102/110` | 送礼/AI 接受/AI 拒绝/玩家回应文案（4） | 聊天文案 | 🟡 | **阶段 2**（`DiplomacyFlows:24/27/29/31`） |
| `ui/game/dialogs/DiplomacyVassalTexts.kt:22/62/68/80/91` | 附属请求/回应成功/回应失败/解散/AI 告别文案（5） | 聊天文案 | 🟡 | **阶段 2**（`DiplomacyFlows:120/122/137/138`） |
| `ui/game/dialogs/heavenlytrial/HeavenlyTrialComponents.kt:188/189` | `CombatantPortrait` | 天劫对手性别/立绘抽取 | 🟡 | **阶段 1③ + 阶段 2**（**UI 层调随机属架构违规**；结果仅用于立绘选择，不写状态） |
| `ui/game/dialogs/DiscipleChatDialog.kt:200` | `List<T>.randomOne` | 抽会话树/结局分支/回复/结束语 | 🔴 | **阶段 2**（`:289/298` 分支经 `randomizeEffect` → `DiscipleDelegate.applyConversationEffects` **写弟子 skills/cultivation**——须走决策源，不得计入表现白名单） |
| `ui/game/dialogs/DiscipleChatDialog.kt:204/209/210` | `randomizeEffect` | 忠诚/道德/智力增量幅值 + 修为增量 | 🔴 | **阶段 2**（`DiscipleDelegate:245-253` 写 `disciple.skills.*` / `disciple.cultivation`） |
| `ui/game/sect/CloudLayerAnimator.kt:30` | 构造形参/字段 `private val random: Random = Random.Default` | 云层类型/方向/Y/缩放/间隔 | 🟡 | **阶段 2**（`NativeSurfaceView:507` 生产实例化未传 seed → 实际走 Default；云朵纯装饰，类注释明示不进存档） |
| `ui/game/saveLoad/SaveLoadViewModelLoadOps.kt:190`、`CloudLoadOps.kt:168` | `applyLoadedSaveToEngine` / `applyCloudSaveToEngine` | `AISectDiscipleManager.initForSlot(...)` | 🔴 | **阶段 1②**（语义由"重播影子"改为"回灌分区 9 镜像"；`RngConsumptionGuardTest` 白名单登记） |

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
