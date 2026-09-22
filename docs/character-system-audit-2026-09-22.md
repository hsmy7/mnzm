# 角色系统全面摸底勘察报告（Character System Audit）

> 日期：2026-09-22
> 勘察方式：只读代码取证，**未修改任何源文件**
> 取证范围：`android/` 全量（1190 Kotlin + 307 C/C++ 文件），排除 `build/` 产物；配置取证含 `app/src/main/assets/data/game-data.json`（1.6MB）、`assets/config/`、`assets/atlas/`、Room schema `52.json`
> 方法论：代码行为 > 实际调用链 > 实际数据结构 > 实际初始化/创建/销毁 > 实际存档 > 命名 > 注释 > 文档
> 勘察时点分支：`w5/ground-boundary-refactor`（HEAD `d60aff2e1`）

## 0. 证据分级

| 级 | 含义 |
|---|---|
| **A** | 主 agent 本人读到代码语句 / 文件实际内容（含实数统计、schema 解析、调用链逐跳核实） |
| **B** | 子代理读到的代码语句，附 `文件:行号`；主 agent 对全部**否定式结论**与承重结论做了复核 |
| **C** | 仅注释 / 文档 / 命名支撑——**不作结论依据**，只登记「注释与代码冲突」 |
| **D** | 当前代码无法确认，需运行时验证（集中见 §15） |

---

## 0.1 执行摘要

**这个项目没有"角色系统"，有一个"弟子（Disciple）群体模拟系统"。**

| 维度 | 实测结论 |
|---|---|
| 数据模型 | 一个 **109 字段扁平结构体**（`struct Disciple`），身份 + 基值 roll + 养成进度 + 装备 + 社交 + 丹药 buff + 遗物追踪同居一处 |
| 定义/实例分离 | **不成立**。无 `CharacterDefinition / CharacterInstance / OwnedCharacter` 任何一层 |
| 角色配置表 | **不存在**。`game-data.json` 的 `db` 实测 10 张表全是"物"（talents/physiques/affixes/manuals/equipment/seeds/herbs/pillRecipes/forgeRecipes/beastMaterials），**无 disciples** |
| 角色产生 | **纯 RNG 程序化生成**，`createDisciple(seed, rng)` 的 seed 只有 8 字段，立绘/天赋/属性/技能一律函数内随机、**无入参可覆盖** |
| 等级/经验 | **不存在** `level` / `exp`。成长轴 = `realm(0-9) × realmLayer(1-9) × cultivation` |
| 稀有度 | **弟子无 `rarity` 字段**。"稀有感"来自灵根个数（越少越好）+ 特质品阶 + 资质 roll |
| 抽卡/卡池/保底/碎片 | **全部不存在**。招募 = 每 3 年随机生成候选、拷贝转正、月度上限 30 人 |
| 战力 | 有统一公式但**仅 1 处**：`(物攻+法攻)×5 + 生命×4 + (物防+法防)×3 + 速度×2`，且**用 baseStats（不含装备/功法/丹药），不等于实战属性**；实时算、不落库 |
| 战斗关系 | 存在独立 `battle::Combatant`，**值拷贝**；战斗只回写 `currentHp/currentMp` + 死亡三元组，buff/冷却零回写 |
| 存档 | 一条弟子记录被复制进 **7 张 Room 表** + `.sav` protobuf；「未拥有」的候选角色也整份入档；配置与状态**确实混在同一记录** |
| 关键异常 | **7 个 `baseXxx` 字段不进入任何属性公式**（战斗/战力用境界表常量）——见 §11 末 |

---

## 1. 角色系统总体架构（实测）

```
                     弟子（Disciple）系统
                             │
    ┌────────────────────────┼─────────────────────────┐
    ↓                        ↓                         ↓
【静态配置层】            【运行态真相层 = C++】        【持久层/镜像层 = Kotlin】
 无"角色表"！             GameState                   GameStateStore
 game-data.json db        ├ disciples: DiscipleStore   ├ DiscipleTables(列式 SoA)
   10 张全是"物"表         │   (SoA 列存, 106 列)        ├ Room disciples(106 列)
   (talents/physiques/     ├ gameData.recruitList       ├ .sav protobuf
    affixes/manuals/        │   (vector<Disciple>)       └ DiscipleAggregate
    equipment/seeds/...)    ├ gameData.aiSectDisciples      (StateFlow, UI 唯一入口)
 + RealmConfig 境界表          (map<sectId,vector>)
    disciple.h:69-83       └ ECS: DiscipleRef{row}
    （唯一"基值表"）           （派生迭代域，非存储）
                             ↑
【生成层】createDisciple(seed, rng)  disciple_factory.h:411
   入参仅 8 字段（id/gender/fullName/surname/spiritRootType/age/realm/realmLayer）
   其余全部函数内 RNG roll —— 无"某个具体角色的定义位置"
```

### 1.1 权威与派生分层（A/B）

- **权威**：`DiscipleStore`（`android/app/src/main/cpp/gamecore/include/gamecore/state/disciple_store.h:158`）。文件头 `:22-31` 与 `:9-31` 声明"唯一权威 + 行序 = RNG 红线"，其实现（`src/disciple_store.cpp`）与 `column_dirty_test.cpp:68` 守卫测试支撑该声明。
- **派生**：ECS `DiscipleRef{row}`（`include/gamecore/ecs/disciple_component.h:37`）。`buildDiscipleEntities`（`:56`）把 row i ↔ entity i 映射，`syncDiscipleEntities`（`:92`）每次 View 迭代前校验"迭代序 == 行序"不变量、被破坏即全量重建。**ECS 不持有弟子数据，只是迭代域**。
- **NPC 不是弟子**：`include/gamecore/ecs/npc_component.h` 组件族仅 `NpcId/NpcPosition/NpcVelocity/NpcPath/NpcPathIndex/NpcSpriteId/NpcAnimState`，`spawnNpc`（`:92-103`）不挂任何 Disciple（B）。

### 1.2 三类"角色容器"（A，models.h / GameState）

| 容器 | 类型 | 位置 | 是否入档 |
|---|---|---|---|
| 已拥有弟子 | `DiscipleStore`（SoA 列存） | `models.h:1449` | 是（7 表 + .sav） |
| 招募候选（未拥有） | `GameData.recruitList : std::vector<Disciple>` | `models.h:1392` | **是**（.sav + game_heavy_data） |
| AI 宗门弟子 | `GameState.aiSectDisciples : map<sectId, vector<Disciple>>` | `models.h:1466` | 是（注释称 @Transient，实际仍写两处，见 §7.4） |

**同一 `Disciple` 类型横跨三者，无"已拥有/未拥有/敌对"的类型区分。**

---

## 2. 角色数据结构（实测 109 字段）

`struct Disciple`：`include/gamecore/state/models.h:300-427`。
**我实数字段数 = 109（A）**；SoA 协议列枚举 `DiscipleColumn` 项数 = **106（A）**，`disciple_store.h:38-154`。
Kotlin 侧：`core/domain/.../core/model/Disciple.kt` 是 Room `@Entity(tableName="disciples")`，**实测 106 列、PK = (`id`,`slot_id`)、7 个索引**（A，解析 `android/core/data/schemas/com.xianxia.sect.data.local.GameDatabase/52.json`）。

Kotlin 的 6 个 `@Embedded` 段（`DiscipleComponents.kt:15,73,100,128,148,182`）只是 Room 列展开手段，**C++ 侧全部平铺、存储层从未分段**（B）。

```
Disciple（109 字段，全平铺）
├── 身份      id(str) · name · surname · gender · portraitRes · age · lifespan · deathYear · isAlive
├── "等级"    ✗ 无 level 字段   ✗ 无 exp 字段
│             实际承载 = realm(0-9，越小越高) + realmLayer(1-9) + cultivation(double)
│                        + cultivationCheckpoint · cultivationCheckpointGameMonth
│                        + {cultivation,manual,equipmentNurturing}Completion{Month,Phase}
├── 灵根      spiritRootType ← 逗号分隔字符串 "fire,water,metal"，非枚举
├── 天赋三套  talentIds[] · physiques[] · affixIds[]（元素 = 模板 id 字符串）
├── 技能      manualIds[]（已学功法实例 id） · manualMasteries{manualId→exp}
├── 战斗基值  baseHp baseMp basePhysicalAttack baseMagicAttack
│             basePhysicalDefense baseMagicDefense baseSpeed
│             + hpVariance…speedVariance（7 个，-50..50 高斯 roll）
│             ⚠ 7 个 baseXxx 不进属性公式（§11 末）
├── 战斗现值  currentHp · currentMp（-1 = 满血哨兵语义）
├── 突破史    breakthroughCount · breakthroughFailCount · totalCultivation
├── 丹药      pill*Bonus 12 项 · pillEffectDuration · activePillTypes[] · activePillCategory(兼容)
├── 装备      weaponId armorId bootsId accessoryId
│             + {weapon,armor,boots,accessory}Nurture : EquipmentNurtureData
│               {equipmentId, rarity, nurtureLevel, nurtureProgress}（models.h:123）
├── 私人钱包  storageBagItems[] · storageBagSpiritStones · spiritStones
├── 社交      partnerId · partnerSectId · parentId1 · parentId2 · masterId
│             lastChildYear · childBirthMonth · griefEndYear（""/-1 = null 哨兵）
├── 生活技能  intelligence charm loyalty comprehension aptitude morality teaching
│             mining pillRefining artifactRefining spiritPlanting
│             + alchemyLevel · forgeLevel · *PromotionCount · salaryPaidCount · salaryMissedCount
├── 状态      status（"IDLE" 等 20 值）· statusData{k→v} · discipleType("outer")
│             soulPower · cultivationSpeedBonus · cultivationSpeedDuration
└── 遗物追踪  usedPermanentPillKeys[] · usedExtendLifePillTypes[] · usedFunctionalPillTypes[]
              usedExtendLifePillIds[] · recruitedMonth · hasReviveEffect · hasClearAllEffect
```

### 2.1 字段变化性分类（B）

- **创建期一次性 roll，此后基本不变**：7 个 variance、11 项生活技能初值、`spiritRootType`、`age`、`talent/physique/affixIds`、`portraitRes`、`surname/name`、`baseXxx`
- **随模拟持续变化**：`cultivation` · `realm` · `realmLayer` · `currentHp/Mp` · `status` · `loyalty` · `lifespan` · 装备四槽 · `manualIds` · 熟练度（在 GameData 侧）
- **永不变化（A）**：`aptitude` —— 创建后无写入方（仅入宗时 `rollHealedAptitude` 补算一次，`recruit_settlement.h:394-400`）

### 2.2 境界表（A，唯一的"角色基值定义"）

`include/gamecore/system/disciple.h:52-66`（`RealmConfig`）与 `:69-83`（10 条内联常量）：

```cpp
{9,"炼气",490,10,80,9, 203, 78, 16, 16, 13, 10, 15},  …
{0,"仙人",29250000,500,9999,9, 507000,195000,39000,39000,32500,26000,37500},
```

0=仙人 … 9=炼气，**数值越小境界越高**；未知 realm 回退 `:86-91`；`maxLayers = 9`（`battle.h:43 kLayersPerRealm`）。
Kotlin 同表另一份：`core/domain/.../GameConfig.kt:264-295`（**双份硬编码**）。

---

## 3. 角色生命周期（实测）

```
[无角色定义]
     │
     ├─ 年结刷新（每 3 年，year_settlement.h:1437 判定 / :1500-1519 生成）
     │     seed.id = ""（:1505）→ createDisciple(seed, rngSystem)（:1513）→ 追加 recruitList
     ├─ 月度生育（child_birth.h:135 / :152 processMonthlyBirth）→ 新生儿同样进 recruitList
     ├─ 占领 AI 宗（ai_sect_recruit.h:391-393）→ 另一套生成器 generateRandomAiDisciple
     └─ Kotlin 臂（RecruitService.kt:432）→ id = java.util.UUID.randomUUID().toString()
                    ↓
   候选态：Disciple 躺在 recruitList（AoS，非 DiscipleStore；id = UUID / 空串）
                    ↓ 玩家点招募
   转正（recruit_settlement.h）：
     :797  const Disciple disciple = *it;                  ← 按值拷贝候选条目
     :388  allocateAndInsert(DiscipleStore& ds, Disciple d, int32_t currentMonthIndex)
     :390  d.id = idStr                                    ← 无条件覆盖入参 id
     :391  d.recruitedMonth = currentMonthIndex
     :394-400  aptitude == 默认值 → rollHealedAptitude(新 id, 灵根数) 再 roll 一次
     :401  ds.appendDisciple(d)                            ← 列存末尾追加，行序 = 追加序
     :822-827  从 recruitList 按 id + 同人签名删除
                    ↓
   养成态：
     每旬 phase_settlement.h —— accumulateCultivation:333 / processBreakthroughs:1119
                                 processManualProficiency:407 / applyNurtureExp:471
                                 processAutoPills / runPhaseCoreBatch:1208
     每月 month_settlement.h —— 叛逃判定 :1211-1330 · 丹药时长扣减 :102,824-868 · 忠诚政策 :146-186
     每年 year_settlement.h   —— 老化 :1680-1758 · 俸禄 :274 · 欠俸 :1778 · 招募刷新 :1824
                    ↓
   战斗态：拷贝为 battle::Combatant → 只回写 currentHp/currentMp（§6）
                    ↓
   终止（四条不同路径）：
     自然死亡 year_settlement.h:1680-1758  清槽 → 丧亲传播 → 道侣/师徒解绑
             → eraseDiscipleDerivedMaps → 删装备/功法实例 → deathYears=年 → removeById【物理删行】
     战死     death_handler.h:57-70 markDead  只写 isAlive=0 + status="DEAD" + deathYear
             行【保留】（尸体新陈代谢窗口）；袋物/实例/槽清理由 battle_residual_tx.h:259-291
     叛逃     month_settlement.h:1241-1330  11 类槽清理 + 装备实例随人删除 + removeById
     逐出     disciple_lifecycle_tx.h:225-273  储物袋信封回传 + 12 槽清理 + removeById
                    ↓
   持久化：C++ 脏列 → Kotlin 镜像 → Room 7 表 + .sav（§7）
   重读：Room → NativeGameState → nativeImportState（全量 JSON）→ C++ 重建
         → ensureTerrainGenerated / normalizeAICorpseEntries(:626) 等运行期补算
```

- 删除语义 = `DiscipleStore::removeById`（`disciple_store.h:361`，原位 erase，其余行序保留）。
- **弟子身上不存槽位号**：所有槽位以 `discipleId` 字符串反向引用弟子（B，统一清理入口 `slot_cleanup.h:199 clearAllSlotsDataOnly`）。
- 状态派生唯一入口：`disciple_tx.h:1094-1115 deriveDiscipleStatus`（优先级序固定），全量同步 `syncAllDiscipleStatusesTx:1774`；状态枚举 20 值（`Disciple.kt:267-270`）。

---

## 4. 角色与抽卡关系

**抽卡系统不存在。** A 级复核：全仓 `android/app/src/main` + `android/core` grep `gacha|卡池|碎片|shard` → 仅 googletest 的 `is_in_another_shard_` 与本仓 `changelog_entries.json:660`（指物品堆叠碎片）命中，弟子/招募代码**零命中**。
`rarity_progression.h` 经核实是**商人/收购/宗门交易的物品品阶时间曲线**，与角色无关（B）。

### 4.1 真实"获得角色"链路

```
年结门：year - gameData.lastRecruitYear >= 3      （year_settlement.h:1437，kRecruitRefreshIntervalYears=3 于 :97）
  ↓ 人数：SectLevel.kt:74-80  小宗 1..4 / 中 1..6 / 大 1..10 / 顶 1..15
  ↓ 加成：RecruitService.kt:78-84  charm baseline 80 / divisor 4 / cap 20 / 兜底 nextInt(7)
createDisciple(seed, rngSystem)     ← 单次属性分布 roll，无权重表、无 UP、无池
  ↓ 写 gameData.recruitList（RecruitService.kt:444）；次年老化 :523-528
招募执行（recruit_settlement.h）
  手动：manualRecruitAll :855-870 跨表比对已拥有者剔除
  自动：processAutoRecruit :651-674
          std::set<int32_t> filter; for(v:rawFilter) if(v>=1&&v<=5) filter.insert(v);
          if (filter.count(nonBlankRootCount(d.spiritRootType))) autoRecruits.push_back(d);
        ← 筛选口径 = 灵根【段数】1..5，不看元素身份
  月限：kRecruitMonthlyLimit = 30（:53，检查 :786-790）
  ↓ 拷贝 + nextDiscipleId 改 id + appendDisciple
```

### 4.2 逐项确认

| 概念 | 结论 | 证据 |
|---|---|---|
| 稀有度 | **弟子无 rarity 字段**（A：models.h:300-427 内 grep `rarity` 零命中） | 派生稀有感：灵根个数 → 资质/悟性阶梯（`disciple_factory.h:436-440`）+ 修炼速度**除数**（`disciple_stats.h:763-766` `realmSpeedPerPhase(realm)/clampedRoots`）；灵根分布 1根1%/2根3%/3根26%/4根30%/5根40%（`child_birth.h:51-55`，与 `GameConfig.kt:386-392` 同值） |
| 概率 | 有，但为**单次属性分布**，非卡池抽取概率 | `disciple_factory.h:51-62` |
| 保底 | **弟子路径无保底**。唯一 `pity` 是灵根洗炼（`GameConfig.kt:399 WASH_PITY_THRESHOLD`）与特质洗炼（`appointment_tx.h:89 kWashPityThreshold=2`，:347-356/:913-916） | — |
| 重复角色 | **概念不成立**：已拥有 id = 自增数字（`recruit_settlement.h:374-383`），候选 id = UUID（`RecruitService.kt:432`），AI 弟子 = `"gc-ai-d-"+计数器`（`ai_sect_recruit.h:84-87` → `inventory.h:51`），三 id 空间互不相交 | — |
| 同人去重 | **存在**，目的是防重复候选，非碎片转换：`samePersonSignature = name+surname+gender+spiritRootType+sorted(talentIds)`（:124-135）、`isSamePerson` 加年龄容差 2（:139-146）、`dedupeRecruits` 三级去重（id → 全字段 `discipleContentEquals` :150-309 → 同人签名 :313-352） | — |
| 角色碎片 | **不存在** | — |
| 招募货币 | 非角色碎片，走宗门灵石/名额 | `recruit_tx.h`、`openRecruitmentLastPaidMonth`（models.h:1312） |

---

## 5. 角色与培养关系

六条独立养成线（B，公式与关键字段我已复核）：

| 线 | 真相字段 | 公式要点 | 配置来源 | 入档 |
|---|---|---|---|---|
| 修为 | `cultivation` | 5 乘区连乘：`base × (1+aptitude) × (1+资源) × (1+社交) × (1+状态) × (1+临时)`，`base = realmSpeedPerPhase(realm)/灵根数`（`disciple_stats.h:712-830`）；上限 `base + (layer-1)×(nextBase-base)/maxLayers`，`realm==0` 直接返回 → **仙人永不涨**（`cultivation.h:39-48`） | 住所倍率 1.40/1.20/1.10 硬编码（:63-71） | 是 |
| 境界 | `realm` / `realmLayer` | 前置：存活 + 非秘境 + `realm>0` + `cultivation>=max` + **HP/MP 满**（`phase_settlement.h:1149-1155`）；`chance = clamp(base × (1+elderGuidance+selfBonus) × max(1−penalty,0) + pillBonus, 0, 1)`（`disciple.h:324-345`、`disciple_stats.h:856-883`） | 突破率表 `disciple.h:104-116` | 是 |
| 功法 | **`GameData.manualProficiencies`**（非 `Disciple.manualMasteries`） | 每旬定量 `6.0 × (1+藏经阁0.5) × 2000/1000` = 12 或 18，上限 30000（`phase_settlement.h:407-453`、`nurture_constants.h:24-26`）；阈值 1000/10000/30000 → 倍率 1.5/2.0/3.0/4.0（`disciple_stats.h:289-304`）；**不受悟性影响**（`ManualProficiencySystem.kt:91`） | `manuals.json` + 生成的 `manual_db.h` | 是 |
| 装备 | `EquipmentInstance.nurtureLevel/Progress` | 每旬 +10（`kNurtureGainPerPhase`），升级需 `100×(L+1) × rarityMult(1/1.5/2/3/4.5/6)`，上限 `nurtureMaxLevel(rarity)=5/9/13/17/21/25`（`phase_settlement.h:471-484`）；进战斗乘区 `1 + L(L+1)/2 × 3/325`（`disciple_stats.h:261-269`） | 生成的 `equipment_db.h` / `forgeRecipes` | 是 |
| 特质 | `talentIds/physiqueIds/affixIds` | 洗炼替换（`appointment_tx.h:347-356`，保底直取 rarity==3 池，:986-1061 确认替换时按 `realmMaxAge × Δlifespan加成` 折算寿元）+ 血炼百分比（同乘区加算，`safeBrPct` clamp 10.0，`disciple_stats.h:91-99`） | `game-data.json` 三表 + `trait_db.h` 手写复刻 | 是 |
| 生活技能 | `SkillStats` 11 项 | **生产行为不涨技能**；增长只来自：① 永久属性丹（`pill_system.h:228-237`）② 战斗胜利 `winBattleRandomAttrPlus` 确定性 17 分支 +1（`battle_residual_tx.h:343-420`，12 分支还 `+1 basePhysicalAttack`、`:415 soulPower+1`）③ 聊天效果道德/忠诚/智力（`chat_effect_tx.h:83-85`）④ 忠诚：政策月度 / 满员住所+1 / 发俸+1 / 欠俸−1 / 连续挖矿满 3 月−1 | 硬编码区间 | 是 |

### 5.1 职业与任命（B）

`profession.h`：5 级，`maxCraftableTier = level+1`，晋升三门槛 次数 `{1,200,500,800,800}` / 境界 `{9,7,6,5,3}` / 技能 `{40,55,70,90,110}`，全满足才升级并清零计数；入口 `production.h completeSlot → settleDiscipleProduction`。
任命写 `elderSlots`（`appointment_tx.h:458/513`，10 个单值长老字段 + 7 个 `DirectDiscipleSlot`）与 `disciple_tx.h:707 assignSlotTransaction`（亲传/藏经阁槽）。

### 5.2 丹药语义（B）

`pill_system.h:86-100` 按 `pillType` 分派，优先级 4/3/2/1/0：
- **永久**：`extendLife`（`lifespan +=`，按 `usedExtendLifePillTypes` 去重）、基础属性丹（11 项技能直加，clamp 200 / loyalty 100，`applyPermanentBaseAttr:223-238`）
- **临时**：战斗属性/加速为**整体覆写**而非累加（`applyBattleAttrAndTemp:249-274`），`pillEffectDuration = max(old, e.duration)`，**单位是旬**，每月扣 3（`month_settlement.h:102, 824-868`），归零时清空全部 pill 加成
- 去重键 `usedPermanentPillKeys` = `"<tier>#<field>"`（`buildUsedKeys:103-121`，tier = pill.rarity）；`canUsePill:158-177` 任一 key 已存在即**整丹不可服**

### 5.3 社会关系效果（B）

`masterId` 师徒：修炼 +5%/大境界差、突破 +3%/大境界差（`disciple.h:373-389`），上限 5 名存活徒弟（`disciple_lifecycle_tx.h:281-353`）。
`partnerId/parentId` 生育：`child_birth.h:152`，灵根继承 30/30/40（:117-124），父母灵根数给子嗣 ±10% 修炼加成并反向给父母（`phase_settlement.h:301-312`）。
`griefEndYear` 丧亲：修炼 −50% / 突破 −20%（`year_settlement.h:1709`、`battle_residual_tx.h:253`）。
`loyalty` 唯一硬效果是叛逃：`clamp((30 − loyalty) × perPoint, 0, 0.90)`（`month_settlement.h:1211-1216`），前置平均忠诚 < 50（:1153-1166）；捕获率随 intelligence 与政策上升（:1176-1208）。
`relative_gift.h`：突破后按关系概率赠礼（道侣 .45 / 父母 .35 / 子嗣 .50 / 师 .40 / 徒 .30 / 兄弟姐妹 .25），选品优先级 空装备槽 > 空功法槽 > 突破丹 > 其他丹 > 材料。

### 5.4 【确认存在的双份真相】功法熟练度

`Disciple.manualMasteries`（`models.h:323`）与 `GameData.manualProficiencies`（`models.h:1204-1212 / 1418`）并存：
- 全部结算 / 战斗 / 修炼速率读**后者**（`phase_settlement.h:133/150/364/1022`、`battle_residual_tx.h:244`、`mission_completion.h:1147`）
- 功法经验丹写**前者**（`pill_system.h:205-210`、`disciple_tx.h:931-935`），且 cap **10000** 与阈值 30000 不一致
- `manualMasteries` 玩家侧唯一读取 = "是否浪费丹药"的门（`phase_settlement.h:620-626`）
- 跨域桥接仅入宗时一次：`recruit_settlement.h:546-554, 640`
- AI 宗门弟子另用 `manualMasteries` 月度涨（`ai_sect_ops.h:286-307`）

### 5.5 上限与约束（B）

**弟子总数无上限**：全仓 grep `MAX_DISCIPLE|discipleCap|maxDisciples` 仅命中存档 id 上界。A 级复核：`DiscipleIdBoundsRule.kt:27 MAX_DISCIPLE_ID_CAP = 200_000`（注释给的最坏内存推算 ≈144MB）。
实际约束：月招募上限 30 + 年度候选数按宗门等级 + `recruitBonusCap(charm)`。
AI 宗弟子硬上限 1000/宗（`ai_sect_recruit.h:56`，`truncateToAiLimit :324-340`）。

---

## 6. 角色与战斗关系

**存在独立战斗单位，值语义复制，只回写 HP/MP。**

```
Disciple（列存）
   ↓ mission_completion.h:513-596  discipleToCombatant(const Disciple&, …)
   │   :525  const auto stats = gamecore::stats::finalStats(d, equipmentMap, manualMap,
   │                              discipleProficiencies, bloodRefinementPct);
   │         ← 属性不读平铺 baseXxx，走聚合器
   │   :546  const int32_t effectiveHp = d.currentHp < 0 ? stats.maxHp
   │                                                     : std::min(d.currentHp, stats.maxHp);
   │   :568-576  c.physicalAttack/magicAttack/physicalDefense/magicDefense/speed/critRate
   │             c.realm = d.realm; c.realmLayer = d.realmLayer;
   │   :563  玩家侧默认 side = kDefender；:580-594 体质/词条乘算因子单独取
battle::Combatant（battle_calculator.h:242-323）
   字段：id/name/side/hp/maxHp/mp/maxMp/physicalAttack/magicAttack/physicalDefense
        /magicDefense/speed/critRate/skills/buffs/realm/realmLayer/element/isBeast/physique/affix
   自带战斗期方法 isDead()/hpPercent()/effectivePhysicalAttack()（:265-322，按 buff 分桶实时乘算）
   ↓ 伤害只改 Combatant.hp：battle_execution.h:280-297
     inline Combatant applyDamageToTarget(Combatant target, int32_t damage)  ← 按值入参
   ↓ 两套执行引擎并存：
     executeBattle(BattleState&, playerDamageModifier, rng, timeoutMs, clock)  :1180（kMaxTurns=25 :51）
     executeAiBattle(vector<Combatant> attackers, defenders, rng)              sect_battle.h:628（200 回合 :42，整表按值）
     AI 决策 battle_ai.h:518 decideAction(const Combatant&, …) → AIAction（目标以 targetId 字符串承载）
   ↓ 唯一回写点（只 2 字段）：
     sect_defense_battle.h:401-419  ds.currentHps[row] = std::min(std::max(c.hp,0), stats.maxHp)
                                    ds.currentMps[row] = …（回写前重算 finalStats 作钳制上限）
     battle_residual_tx.h:323-327   ds.currentHps[su.row] = su.hp（survivor 值预计算 :230-253，经 getMaxHpMp 钳制）
     + 死亡另写 ds.isAlive[row]=0 / statuses / deathYears（:270-272）
   ✗ buffs · 技能冷却 · physique/affix 无任何写回（纯临时）
   ✗ battle_residual_tx.h:223-225 记录 Kotlin 算出的 updatedStatus 从未写回状态列
   ✗ boundary_tx.h 与境界/突破无关（它是引导计数与自动分配策略事务 :87-96/:117-145/:161-192）
```

### 6.1 敌方单位

| 敌人 | 结构 | 来源 |
|---|---|---|
| AI 宗门弟子 | **同构**：复用 `discipleToCombatant` 后强制满血（`sect_conquest.h:241-251`：`c.hp=c.maxHp; c.mp=c.maxMp; c.side=side;`），数据载体 `state.aiSectDisciples`（:273） | — |
| 妖兽 | `createBeast(beastRealm,index,typeIndex)`（`mission_completion.h:454-509`），`beast.id = "beast_"+index`、`isBeast=true`，数值 = `beastRealmStats(realmIndex) × layerMult × type.*Mod`（:470-478），`critRate = 0.05 + realmIndex×0.01`（:479），技能来自 `type.skills` 表（:486-507） | — |
| 随机人类敌人 | `generateHumanEnemies`（`mission_completion.h:626+`，含 JavaRandom LCG 洗牌复刻 :203-229）；Kotlin `EnemyGenerator.kt:24-27 HumanEnemyData{combatant, equipmentInstances, manualInstances}`，`id = "human_enemy_$index"`，名字取自 `魔修/邪修/散修/山匪/暗杀者/邪道修士` | — |

### 6.2 技能在战斗中（**纯数据驱动，零按 id 硬编码**）

- `manualCombatSkill(const ManualBase&)`（`mission_completion.h:167-193`）逐字段查表拷贝：`s.damageMultiplier = manual.skillDamageMultiplier; s.cooldown = manual.skillCooldown; s.hits = manual.skillHits; s.buffs = parseBuffsJson(manual.skillBuffsJson); s.isAoe = manual.skillIsAoe; s.shieldPercent = manual.skillShieldPercent;`（:173-191）
- 字段声明：`models.h:78-115`（skillHits:90 / skillDamageMultiplier:91 / skillCooldown:92 / skillBuffsJson:100）
- **硬编码检查（B）**：`gamecore` 下 grep 功法 id 字面量（排除生成的 `manual_db.h`）→ **0 命中**；grep `manualId ==` 仅命中熟练度记录的 id 匹配（`disciple_stats.h:360/697`、`phase_settlement.h:382`），非效果分支
- 唯一分支 = 熟练度等级数值表：`masteryLevelBonus`（`disciple.h:540-548`）`switch 0..3 → 1.5/2.0/3.0/4.0`，乘在 `damageMultiplier` 上（`mission_completion.h:542`、`:196-198`）
- 注意：战斗读的是 `PlayerGameData.manualProficiencies`（`mission_completion.h:521-523`），而非 `Disciple.manualMasteries`（见 §5.4）

### 6.3 境界在战斗中的四处作用（B，我复核公式）

1. **属性基值唯一真值源**：`disciple.h:247-265`（§11 末贴出）
2. **境界压制三因子**（`battle.h:141-163`）：
```cpp
const int64_t majorGap = defenderRealmSafe - attackerRealmSafe;
const int64_t layerGap = majorGap * kLayersPerRealm + (safeLayer(attackerLayer) - safeLayer(defenderLayer));
out.damageAmplification            = (layerGap > 0) ? 0.30 * layerGap : 0.0;
out.damageReduction                = (layerGap < 0) ? std::min(1.0, 0.30 * -layerGap) : 0.0;
out.majorRealmDamageAmplification  = (majorGap > 0) ? 1.0 * majorGap : 0.0;
```
经 `buildDamageZones`（`battle_calculator.h:366-369`）注入，在 `calculateFinalDamage` 中作**独立乘算因子**（`battle.h:199-204`）
3. **跨境界斩杀**：`return gap > kInstantKillGap(1) * kLayersPerRealm(9);`（`battle.h:166-173`）
4. 篡改防护 `safeRealm` 钳 0..9 / `safeLayer` 钳 1..9（`battle.h:110-117`）；妖兽暴率随 realmIndex

### 6.4 突破对字段的实际改动（A/B）

`breakthrough.h:70-85` 成功：`cultivation = 0`；`realmLayer += 1` 或（层满）`realm -= 1; realmLayer = 1`；跨大境界 `lifespan += 寿元增益`（`disciple.h:411-423`：8→40 … 0→10000，再 × `(1 + 天赋/词条 lifespan 之和)`）；`breakthroughCount++`、`guideCounters["breakthroughs"]`。
`:90-108` 失败：`cultivation = 0`；`currentHp/currentMp = max((int)(cur × 0.1), 1)`；`breakthroughFailCount++`。
**两者都不触碰 `baseXxx` 平铺列。**

### 6.5 双实现并存（B）

Kotlin `BattleSystem.convertDiscipleToCombatant`（`core/engine/.../battle/BattleSystem.kt:245`）仍在，被 `GameEngineBattleOps.kt:175`、`SecretRealmService.kt:586`、`HeavenlyTrialViewModel.kt:104` 调用；`battle_execution.h:15-17` 注释称战斗组装"保持 Kotlin"；Combatant 以 JSON 过 JNI（`battle_json.h:135 combatantFromJson`）。等价性见 §15-3。

---

## 7. 角色与存档关系

```
                    ┌── 运行期真相 = C++ GameState（DiscipleStore SoA）
                    │   每 tick 产出列级脏数据（column_dirty.h:586-643）
                    ↓
Kotlin StateSyncService.kt:429 nativeExportDirty → :363 applyDirtyProto
   → :397 applyEnvelope → :404 stateStore.updateMirror → :647/:666 upsertMirrorRow(disciple)
   （Protobuf 列式信封："changed[disciples] = 脏行×脏列（每行恒带 id） + removed"；
     gameview_encode.cpp:121+ 编 GameView wire）
   ⚠ columnExportBlocked_（非结算路径写入，game_core.cpp:757 noteNonSettlementMutation）
     时回退全量树 diff
                    ↓
   GameStateStore → 保存 StorageEngineWriteOps.kt:33-62
     clearHeavyDataByPrefix + writeHeavyDataIncremental
     + 一事务内 clearOldSlotEntities（:134-139 先删该 slot 全部弟子行）
     + writeDisciples（:185-201）—— 每个弟子复制进 7 张表
     + writeDomainEntities（:275-283 含 aiSectDisciples）
     + 后置 .sav / .bak 文件镜像（StorageEngineSaveSupport.kt:194）
                    ↓
   Room 实测（A，schema 52.json）：
     disciples            106 列 / PK=(id, slot_id) / 7 索引
     disciples_core        17 列 / 6 索引
     disciples_combat      35 列
     disciples_equipment   14 列
     disciples_extended    23 列
     disciple_attributes   19 列 / 1 索引
     disciple_compact      16 列 / 2 索引
     archived_disciples     7 列 / 2 列
   + .sav / .bak protobuf 文件（SaveFileManager.kt:109 序列化 + 压缩 + 原子 rename；
     SaveData.kt:54-92 @ProtoNumber 手工编号，最大 56）
                    ↓
   读档：StorageEngine.load()（:286-296）先 Room，DB 无数据才 restoreFromBackup
   → SaveLoadViewModelLoadOps.kt:145 storageFacade.load(slot)
   → StorageEngineHeavyDataOps.kt:293 discipleDao().getAllSync(slot)
   → :175 gameEngine.loadData(disciples = saveData.disciples)
   → GameEngineLoadDataOps.kt:171 stateStore.loadFromSnapshot → GameStateStoreImpl.kt:1356-1357
   → :131 syncNativeBaselineAfterLoad → importToNative（全量 JSON 重建 C++）
```

### 7.1 权威归属：分裂且方向不对称

| 方向 | 机制 | 结论 |
|---|---|---|
| 运行期模拟真相 | C++ `GameState` | **C++ 权威**（行序 RNG 红线，`disciple_store.h:22-31`） |
| 存档持久化 | Kotlin Room + `.sav` | **Kotlin 权威**（保存时由 `GameStateStore` 写 Room，`StateSyncService.kt:521-525`） |
| Kotlin → C++ | **只有全量 `importToNative`**（A：调用点 `GameEngineCoreNativeOps.kt:24`、`AuthoritativeOps.kt:132/206`、`GameEngineCoreAuthoritativeOps.kt:132`，均为读档/新档/基线重建） | 无增量回写通道 |
| C++ → Kotlin | 列级增量脏镜像 `nativeExportDirty` | 单向持续前向 |

**推论（A）**：C++ 独有的非协议状态（`deathYears`、`lastTheftJudgementYears`）**不落地，读档即重算**。

### 7.2 序列化字段集（B）

`src/json_codec.cpp:233-363`：`to_json` 与 `from_json` 对 Disciple 的键集与 `struct Disciple` **逐字段全等**（脚本比对差集为空）——零排除。
未进协议的是 SoA 内部列：`numericIds` / `hasNumericIds`（`disciple_store.h:164-167`）、`deathYears`（`:184`）、`lastTheftJudgementYears`（`:185-187`）。
JSON 形态 = **每弟子一个平铺对象的数组，顺序保留、无排序**（`json_codec.cpp:1437-1441` `for(i=0;i<v.disciples.size();++i) disciplesArr.push_back(v.disciples.materialize(i));`）；列式只存在于内存。
守卫测试：`test/column_dirty_test.cpp:68 ColumnEnumBijectionWithProtocolFields`（A 确认存在）锁定 `DiscipleColumn` ↔ `discipleColumnName()`（`column_dirty.h:82-195`）↔ `to_json` 键集三者双射。

### 7.3 【注释与代码冲突】两处（B，登记）

1. **`DiscipleSerializer.kt:28`** 声称"`@Ignore` 字段（lifeEvents、运行时 Set 字段）不序列化" —— 实际 3 个 `@Ignore` 的 Set 字段**照进 protobuf**：`PillEffects.kt:88 activePillTypes → @ProtoNumber(89)`、`UsageTracking:185,188 → @ProtoNumber(87)(88)`。算术自洽：108 proto − 3 Set + 1 slot_id = 106 Room 列。真正未入 proto 的只有 `slotId`（`:194 slotId = 0`）与 `lifeEvents`（`Disciple.kt:141-142 @Ignore`）。
2. **`deathYear`**：`disciple_store.cpp:180 deathYears.push_back(0)` **无条件置 0，不读 `d.deathYear`**，`materialize()` 也不回填 → 入站的 `deathYear` 被丢弃、出站恒 0；真实死亡年份活在非协议列，靠 `game_core.cpp:626 normalizeAICorpseEntries` 导入时重算。`column_dirty_test.cpp:90-93 expected.erase("deathYear")` 是唯一豁免。Kotlin `Disciple` 无此字段（`game_view.proto:119-121` 亦承认）。

### 7.4 aiSectDisciples：注释半真半假（B）

`GameData.kt:213-215` 确为 `@kotlinx.serialization.Transient` → 不进 `game_data` blob、不进 `.sav`；
**但仍被持久化两次**：`StorageEngineWriteOps.kt:88-90 encodeDiscipleListMapIncremental(…, KEY_AI_SECT_DISCIPLES)` 写 `game_heavy_data`，`:275-283` 写 `world_map_state.aiSectDisciples`。
`CollectionConverters.kt:100-107` 的 TypeConverter 恒返 `""`（只避 OOM，不代表不存）。
反向通道仍在：`StateSyncService.kt:188 if (carriedAi != null) merged = merged.copy(aiSectDisciples = carriedAi)`，C++ 非空即导出（`json_codec.cpp:1421-1422`）。

### 7.5 recruitList 入档（B）

`GameData.kt:271-274 @ProtoNumber(24) var recruitList: List<Disciple>`（非 Transient）→ 既在 `.sav`，也拆写 `game_heavy_data`（`StorageEngineWriteOps.kt:108-110 KEY_RECRUIT_LIST`；Room `game_data` 列由 `buildLightGameData :128` 清空）。C++ 侧同入协议（`json_codec.cpp:1300/1386 GC_TO(v,j,recruitList)`）。
**结论：未拥有的角色实例被完整持久化。**

### 7.6 版本与旧档兼容

`GameData.saveVersion`（`GameData.kt:536-538 @ProtoNumber(100)` + Room 列 `save_version`）；保存前统一盖章 `SaveDataVersionMigrator.CURRENT`（`StorageEngine.kt:223-230`，`SaveVersion.CURRENT = 2`）。`@ProtoNumber` 只增不复用，由 `ProtoNumberUniquenessTest` 锁定（`SaveData.kt:88-90`）。
C++ 宽松导入靠宏 `GC_FROM = readField`（`json_codec.cpp:20`、`json_codec.h:22-27`）：`if (j.contains(key) && !is_null()) get_to(out)` —— 缺键即保留 struct 默认值。

### 7.7 「配置与玩家状态是否混在存档里」——是，三层混合

1. 单条 109 字段记录同时承载"身份/创建期 roll"与"运行期状态"，**无分区标记**；
2. **嵌套物品模板配置被复制进弟子记录**：`Disciple.kt:507-509` 袋条目内嵌 `EquipmentInstance` / `ManualInstance`；`StorageBagItem` 自带 `name/rarity/effect`，而 `ItemEffect`（`Disciple.kt:535-575`）是 **39 个 `@ProtoNumber` 的完整物品效果配置表**，逐条目冗余落盘；
3. 同一条扁平记录在 Room 里再被复制 7 份；存档格式（`.sav` 平铺 protobuf）与表结构（106 列单表）**同构** —— "分段"仅存在于 Kotlin 类型层，存储层从未拆开。

---

## 8. 角色与资源关系

```
Disciple.portraitRes（"male_disciple_7" ← 裸 drawable 名，无路径无扩展）
   ↓ XianxiaApplication.kt:151 PortraitPool.initialize(this)
PortraitPool.kt:14-15（A）
   private val malePortraits   = (1..20).map { "male_disciple_$it" }
   private val femalePortraits = (1..17).map { "female_disciple_$it" }
   ↓ :33  res.getIdentifier(name, "drawable", pkg)；:65-67 查不到 → 返回 0
DiscipleComponents.kt:183-192
   val preloaded = PortraitPool.getResourceId(disciple.portraitRes)
   if (preloaded != 0) preloaded else (SpriteResRegistry.resolve("disciple_portrait") ?: 0)
   ↓ PortraitImage.kt:29-44  resId==0 直接不绘制；否则 LocalPortraitCache 位图 / painterResource(resId)
```

| 资源类 | 实际情况 | 证据 |
|---|---|---|
| 头像 | 37 个 `*_disciple_*.webp` 在 `feature/game/src/main/res/drawable-nodpi/`（该目录 384 文件全 webp）+ 通用兜底 `disciple_portrait.webp`（在 `app/` 与 `feature/game/` **各一份**） | A（目录实测）+ B |
| 立绘 / Spine / Live2D / 骨骼 / 序列帧 | **不存在**（B：全量 grep `spine\|live2d\|skeleton\|dragonbones` 零命中） | — |
| 弟子专属音效/语音 | **不存在**（B：`voice\|cv_` 零命中；`res/raw/` 仅 `bgm_main.mp3` 等 2-3 个） | — |
| `assets/atlas/` | **与角色无关**：`atlas-rgba-manifest.json` 实测 `spriteCount:41`，条目全为 GROUND/GRASS/STONE/TREE/灵矿场/炼丹炉/藏经阁/天枢殿/cloud_*/road_body，**无一条弟子**；`resource-registry.json:367-372` 的 PORTRAIT 类只有 `disciple_portrait`，37 张弟子头像不在注册表内。生成器 `android/scripts/build-atlas.mjs` | B + A（manifest 解析） |
| 名字池 | `name_service.h:27-134` 硬编码：复姓 16 / 单姓 60 / 通用 40 / 仙侠 30 / 男双 71 / 女双 62 / 男单 20 / 女单 20；Kotlin 另一份 `core/domain/.../util/NameService.kt:9,14,23,30,37,54,66,71,76`。**`name_service` 不参与 id 生成**（`generateName:205-257` / `inheritName:158-196` 只返回 `NameResult{surname, fullName}`） | B |

**双份池、无一致性守卫**：C++ `disciple_factory.h:72-92`（`for i in 1..20` 字面量拼接）与 `ai_sect_recruit.h:155-158`（复用同一对）+ Kotlin `PortraitPool.kt:14-15`；AI 另走索引轮转 `GameEngineLoadDataOps.kt:413-416 allPortraitNames[index % size]`。唯一"同步依据"是注释（`disciple_factory.h:71`，C 级）——**未找到双端守卫测试**（`PortraitPoolTest.kt` 只测 Kotlin 侧；`disciple_factory_test.cpp:47,92` 只断言黄金值）。见 §15-4。

### 8.1 配置注入链与优先级（B）

`GameDataNativeBridge.kt:45,74 nativeSetGameData` → `GameCoreBridge.cpp:939` → `data_store.h:102 loadFromJson` → `data_inject.h:52-138 applyGameData` 逐段 `xxxTemplatesMutable() = std::move(rows)`。
兜底关系（已用代码验证，非仅注释）：`trait_db.h:714 static std::vector<TalentTemplate> kTemplates = detail::buildTalentTemplates();` 内联兜底，注入即整表替换；`data_store.h:42-49` 三态 `kUninitialized/kLoadedFromFile/kFallbackDefault`，`:89-91 isGameDataSealed`；`data_inject.h:60,67,79…` 段缺失或空数组 → `return false` → 整体落兜底。

代码生成物（第 1 行横幅"禁止手改"）：`manual_db.h` / `equipment_db.h` / `herb_db.h` / `beast_material_db.h`。
**手写等价复刻（非生成物）**：`trait_db.h:1-18`（"须同步更新本表与快照"）、`recipe_db.h:22-26`（"C++ 表须手动同步"）。
源数据：`scripts/data/{trait,equipment,herb,recipe,beast_material,manual}_db_sample.json`（6 个）；生成器 `gen-game-data.mjs`（→ `game-data.json` + `game-data.hash.txt`）、`gen-templates.mjs`、`gen-manual-db.mjs`、`gen-recipe-db.mjs`、`gen-trait-db.mjs`、`gen-beast-material-db.mjs`。
功法另有 Kotlin 运行时真相源 `assets/data/manuals.json`（`ManualDatabase.kt:424` 先试 `data/manuals.pb` —— 该文件仓库中不存在 → 实走 `:430` JSON 分支；见 §15-8）。

### 8.2 生成参数配置化程度（B + A）

| 参数 | 值 | 位置 | 是否配置化 |
|---|---|---|---|
| 月招募上限 | 30 | `GameConfig.kt:731` / `recruit_settlement.h:53` | 否（双端常量） |
| 刷新间隔 | 3 年（差值判据，非取模） | `CultivationEventProcessor.kt:91` / `recruit_settlement` 判定；`year_settlement.h:97` | 否 |
| 候选人数 | 小 1..4 / 中 1..6 / 大 1..10 / 顶 1..15 | `SectLevel.kt:74-80` | 否 |
| 魅力加成 | baseline 80 / divisor 4 / cap 20 / 兜底 `nextInt(7)` | `RecruitService.kt:78-84` | 否 |
| 年龄 | `16 + nextInt(14)` | `RecruitService.kt:74-75` / `recruit_tx.h:19` / `ai_sect_recruit.h:160` | 否 |
| 初始境界 | 固定 `realm=9, realmLayer=1` | `RecruitService.kt:437-438` / `ai_sect_recruit.h:189-190` | 否 |
| 灵根分布 | 1根1%/2根3%/3根26%/4根30%/5根40% | `GameConfig.kt:385-391` / `SpiritRootGenerator.kt:8-21` / `child_birth.h:51-55` | 否（三端同值） |
| 6 维方差 | `gaussianInt(0.0, 16.667, -50, 50)` ×7 | `disciple_factory.h:296-306`（Box-Muller `:108-120`） | 否 |
| 悟性/资质 | 80-100/60-80/40-60/20-40/1-20 按灵根数阶梯 | `disciple_factory.h:309-328` + `:436-440`（`spiritRootCount = 1 + count(',')`） | 否 |
| 技能初值 | `gaussianInt(50.5, 16.5, 1, 200)`，忠诚上界 100 | `disciple_factory.h:332-351` / `GameConfig.kt:127-132` | 否 |
| 特质数量 | 0:35% 1:35% 2:20% 3:6% 4:3% 5:1% | `disciple_factory.h:51-55` | 否 |
| 特质品阶 | 0(负):30% 1:50% 2:18% 3:2% | `disciple_factory.h:58-62` | 否 |
| 寿元 | `realmMaxAge(realm)` **switch 硬编码**（9→120，默认 80）× `(1 + 天赋/词条 lifespan 之和)` | `disciple_factory.h:129-142 / :372-391` | 否 |
| 基础属性 | `d.baseHp = (int)(120.0 × (1 + hpVariance/100))` 字面量 | `disciple_factory.h:356-368` | 否（且不进公式） |

`assets/config/game_config.json` 参与角色生成的只有 `disciple.*`（minLoyalty/maxLoyalty/minAge/maxAge/protectionMonths）、`battle.discipleSlots:8`、`ai.*` —— **无招募池字段**（A）。`game_config.h:15` 注释称注入前用同值默认（C 级，与常量实测同值一致）。

---

## 9. 角色与 UI 关系

UI **从不消费 `Disciple` 原始 data class**（B：`feature/game` + `core/ui` 内 grep `: Disciple\b` / `disciples: List<Disciple>` 零命中），只消费聚合只读视图 `DiscipleAggregate`：

```kotlin
// core/domain/.../core/model/DiscipleAggregate.kt:7-13（A）
@Immutable data class DiscipleAggregate(
    val core: DiscipleCore, val combatStats: DiscipleCombatStats?,
    val equipment: DiscipleEquipment?, val extended: DiscipleExtended?,
    val attributes: DiscipleAttributes?, val sourceRef: Disciple? = null)
```
其上带展示派生计算：`:34-40 spiritRoot / spiritRootName / realmName`（`age<5 \|\| realmLayer==0 → "无境界"`；`realm==0` 不显层数）、`:44-58 baseHp/maxHp(getBaseStats())/…` 带 `?: DEFAULT_*` 兜底。

```
C++ DiscipleStore ──列级脏镜像──→ Kotlin DiscipleTables（列式 SoA，GameStateStoreImpl.kt:136）
                                     │ :1182 dispatchAssemble → assembleAll{Patched,Incremental}
                                     │ :507 updateAggregates → map { it.toAggregate() }
                                     │ :512/:529 computeCombatPower（Kotlin 版公式）
                                     ↓ :616 discipleAggregates: StateFlow<List<DiscipleAggregate>>
                   GameEngine.kt:415 透传 → GameViewModel.kt:515 distinctBy{id} → :523 aliveDisciples
                                     ↓ collectAsStateWithLifecycle()
   DialogCommon.kt:165 aliveDisciples → DisciplesTab.kt:34/:97/:140（Grid/Card）
   DiscipleDetailScreen.kt:186/:238/:314/:461/:905 → DetailCombatSection.kt:19/:98-114
   DetailRightPanel.kt:112 DetailPortrait → PortraitPool · :89/:174-218 DetailTypeDropdown
   DiscipleComponents.kt:122/:178/:207/:269/:298 · DiscipleSlotComponents.kt:110/:177/:223/:271/:293
   招募 RecruitDialog.kt:31/:88/:155/:256 + DialogFeatureRoutes.kt:59 renderRecruit
   储物袋 DetailPillSection.kt:50/:211 · 功法 DetailManualSection.kt:32/:89/:161
   编队 AttackDiscipleDialog.kt:45/:249/:315/:385/:419（:489 十槽出战队）
   天罚 HeavenlyTrialDiscipleDialog.kt:23/:84/:112 + heavenlytrial/HeavenlyTrialComponents.kt:184
   任命/驻防 TianshuHallDialog.kt:171/:224/:257/:299/:373/:491/:513 · WenDaoPeakDialog.kt:182/:205
             QingyunPeakDialog.kt:190/:211 · LawEnforcementHallDialog.kt:231-436
   建筑选人 HerbGardenDialog.kt:105/:156 · MissionHallDialog.kt:460 · PatrolTowerDialog.kt:262
             LibraryDialog.kt:116 · ScoutDialog.kt:169 · SpiritMineDialog.kt:465
             ResidenceDialog.kt:283 · SecretRealmDetailDialog.kt:302
             WarehouseDiscipleSelectDialog.kt · shared/DiscipleSelectorDialog.kt:24
```

- **首帧来源 = Room（StorageEngine），非 Repository**（B）：`SaveLoadViewModelLoadOps.kt:145 → StorageEngineHeavyDataOps.kt:293 DiscipleDao.getAllSync → :175 gameEngine.loadData → GameEngineLoadDataOps.kt:171 loadFromSnapshot → GameStateStoreImpl.kt:1356-1357 discipleTables.insert`。`GameStateRepository.loadFullState`（`GameStateRepository.kt:45`）**无生产调用方**（仅测试）。
- **UI 直连 JNI 仅一处且非数据读取**：`ResourcePreloader.kt:129 GameCoreBridge.ensureLoaded()`。
- 详情刷新：点击时传入的对象只作 id 键，实时数据重解析（`GameOverlayHost.kt:576 sortedDisciples.find { it.id == req.disciple.id } ?: req.disciple`）。
- **无"招募结果弹窗"**：只有失败通知 `GameNotification.RecruitFailed`（`GameOverlayHost.kt:512`）+ `DiscipleDelegate.onRecruitBlocked`。

### 9.1 写路径：native 事务优先 + Kotlin 回退臂（B）

```kotlin
// GameEngineNativeOps.kt:61-88
val executed = GameCoreBridge.nativeExecute(actionId, paramsJson, nowMs)
if (obj["status"]?.toString() != "\"success\"") return null   // 失败信封 → 回退 Kotlin
stateSyncService?.applyDirtyFromNative() ... return obj["data"]
```
```kotlin
// GameEngineManualOps.kt:62-73（装备实证）
val data = tryDiscipleTxNative(ActionIds.DISCIPLE_TX_EQUIP) { put("discipleId", …) }
if (data?.str("equipped") == "true") { applyEquipLogDraft(…); return DomainResult.Success(Unit) }
return discipleService.equipEquipment(discipleId, equipmentId)   // Kotlin 回退臂
```
门控 `NativeEngineFlag.kt:38 var mode = Mode.AUTHORITATIVE`（生产默认）。

规模：**`action_ids.h` 共 199 个 ActionId，`execute_dispatch.cpp` 166 个 `case action::`（2799 行）**；弟子相关约 40 个，代表样本：`DISCIPLE_TX_EQUIP=1480 / UNEQUIP=1481 / LEARN_MANUAL=1482 / UNLEARN_MANUAL=1483 / ASSIGN_SLOT=1484 / UNASSIGN_SLOT=1485 / DISCIPLE_BREAKTHROUGH=1107 / DISCIPLE_BREAKTHROUGH_CHANCE=1102 / DISCIPLE_ACCUMULATE_CULTIVATION=1105 / ELDER_APPOINT_TX=1610 / DISMISS_TX=1611 / SPIRIT_ROOT_WASH_TX=1613 / TRAIT_ADD_ROLL_TX=1614 / CONFIRM_TX=1615 / TRAIT_WASH_SLOT_TX=1616 / RECRUIT_REMOVE_TX=1630 / REFRESH_TX=1631 / AGE_TX=1632 / DISCIPLE_OP_RENAME=1740 / CHANGE_TYPE=1741 / TOGGLE_FOLLOW=1742 / REWARD_ITEM=1743 / USE_PILL=1744 / DISCIPLE_LIFECYCLE_EXPEL=1590 / APPRENTICE=1591 / DISCIPLE_CHAT_EFFECT_TX=1860 / STORAGE_BAG_OPEN_TX=1734 / SECT_POWER_DISCIPLE=1422`。
Kotlin 同源副本 `core/engine/.../nativebridge/ActionIds.kt`（脚本 `gen-action-ids.mjs` 生成，禁手改）。

**已下沉 C++ 权威**：装备穿脱 · 功法学/忘 · 槽位指派 · 长老任命/罢免 · 灵根洗炼 · 特质加/洗 · 招募移除/刷新 · 逐出/拜师/交谈（`DiscipleDelegate.kt:50-262` 全经 engine 入口）。
**仍 Kotlin 权威写弟子**（B）：① `lifeEvents` 瞬态列（C++ 无该字段，`GameEngineManualOps.kt:45-50`）；② 执法/偷盗判定域（`DiscipleOpsNativeTxForward.kt:59-62` Kotlin 算完 → `:63 rebaselineNativeMirror("偷盗判定钩子")` 全量推回 C++）；③ 读档期迁移/净化（`GameEngineLoadDataOps.kt:183-206 migratePillTrackingFieldsAfterLoad / sanitizeRecruitListAfterLoad`）；④ 各回退臂分支（`DiscipleEquipmentService.kt:49/:224`、`GameEngineManualOps.kt:100-133`）。

### 9.2 战力/属性两侧各一份公式（A/B）

- UI 端现算，**不读引擎字段**：`DetailCombatSection.kt:98-99 remember { disciple.getFinalStats(equipmentMap, manualMap, discipleProficiencies, bloodRefinementPct) }` → `:114 StatItem("物攻", finalStats.physicalAttack, …)`
- 委派链：`DiscipleAggregate.kt:197 → statsProvider`，实现体启动时注入：`XianxiaApplication.kt:218 DiscipleAggregate.statsProvider = object : DiscipleStatsProvider { … :241/:250 DiscipleStatCalculator.getFinalStats(…) }`（8 个分部文件）；未注入时走 `DiscipleAggregate.kt:374-413` 的 **no-op 默认对象**（返回空 `DiscipleStats()`）
- C++ 同式：`disciple_stats.h:551-634 finalStats`
- 战力两份：Kotlin `GameStateStoreImpl.kt:529 computeCombatPower → SectCombatPowerCalculator.kt:30-33`；C++ `sect_power.h:26-33`
- **宗门总战力走的是 Kotlin 那份**（`GameStateStoreImpl.kt:512` 聚合写回点同步计算），非引擎返回值。

---

## 10. 角色依赖关系图（实测方向）

```
                         ┌────────────── 年结刷新（year_settlement.h）
                         │       ↓ createDisciple + RNG(SYSTEM 分区)
                         │  recruitList（候选 Disciple，入档）
                         │       │ 招募：拷贝 + 改 id + appendDisciple
                         ↓       ↓
   境界表 ────────→  【 Disciple / DiscipleStore 】 ←──────── 特质模板 talent/physique/affix
  disciple.h:69-83         │   ↑ 行序 = RNG 抽取序红线          │ (game-data.json + trait_db.h)
   │                       │   │ 列级脏镜像                      │
   │ 属性基值(唯一真值源)   │   │ (Kotlin→C++ 只有全量 import)     ↓
   ↓                       │  ┌┴──────────────┐            功法表 manuals(540)
 finalStats 链 ────────────┼──┤ ECS DiscipleRef│            装备表 equipment(72)
 (base→装备→功法→丹药)      │  │ (派生迭代域)   │            丹方 pillRecipes(732)
   ↓                       │  └───────────────┘            锻造 forgeRecipes(72)
 battle::Combatant ──值拷贝┘                                        │
   │ 只回写 currentHp/currentMp + 死亡三元组                         ↓
   ↓                                                        仓库/实例表（Equipment/Manual
 秘境·驻防·巡逻·探索·任务·生产·炼丹·锻器·讲道·执法·叛逃·生育·师徒·俸禄  Instance / StorageBag）
   ↓ 全部以 discipleId 字符串反向引用（弟子侧不存槽位号）
   ├─ 槽位：elderSlots / directDiscipleSlots / residenceSlots / librarySlots
   │         patrolSlots / warehouseGarrisons / worldMapSects[].garrisonSlots
   │         battleTeams(MAX_TEAM_SIZE=7) / caveExplorationTeams / activeMissions
   │         productionSlots.assignedDiscipleId / spiritMineSlots
   └─ 派生 map：manualProficiencies / bloodRefinement{Bonus,Pct}Totals / bloodRefinements
                （唯一收口点 blood_refinement.h:82-88 eraseDiscipleDerivedMaps）

 宗门间外交/征伐：读同一 Disciple 结构的 aiSectDisciples（AI 宗；@Transient 但仍入档）
 兑换码/引导：RedeemCodeRewardOps.kt:249 另起一条独立弟子构造路径
```

| 被依赖系统 | 关系 | 关键证据 |
|---|---|---|
| 存档 | **直接** | 7 张 Room 表 + `.sav` + 列级脏镜像 |
| 战斗 | **直接**（值拷贝 + 2 字段回写） | `mission_completion.h:513` / `sect_defense_battle.h:401-419` |
| 境界表 | **直接**（属性基值唯一来源） | `disciple.h:69-83` → `computeBaseStats:225` |
| 特质/功法/装备模板 | **直接**（id 引用 + effects 聚合） | `disciple_stats.h:141-170 / 345-367` |
| 生产/经济 | **直接**（技能值消费） | `production.h:98-110/:127-134`、`profession.h` |
| 任务/秘境/探索/驻防/巡逻/洞府 | **直接**（槽位反向引用） | `slot_cleanup.h:199` |
| UI | **直接**（Aggregate 投影） | `GameStateStoreImpl.kt:616` |
| 资源 | **直接但仅字符串名** | `PortraitPool.kt:14-15` |
| RNG | **直接**（行序即抽取序契约） | `disciple_store.h:22-31` |
| ECS | **间接**（派生迭代域） | `disciple_component.h:37-111` |
| 抽卡/卡池 | **无关系**（系统不存在） | §4 |
| 角色碎片 | **无关系**（不存在） | §4.2 |
| 地图图集 | **无关系**（41 sprite 无弟子） | §8 |
| 平台/账号（TapTap 实名、广告） | **无关系**（与弟子零耦合） | 未发现引用 |

---

## 11. 新增一个角色（id=9999）实际涉及的修改点

**前置事实（A，本报告最重要结论）**：`DiscipleCreationSeed` 只有 8 字段（`disciple_factory.h:399-408`：`id / gender / fullName / surname / spiritRootType / age / realm / realmLayer`）。`createDisciple`（`:411-479`）对 `portraitRes`（`:455-457`）、`talent/physique/affixIds`（`:443-452`）、11 项技能（`:459-471`）、7 项 variance（`:296-306`）、`lifespan`（`:477`）**一律函数内随机，无任何入参可覆盖**。
**→ 当前代码里不存在"某个具体角色的定义位置"。加"角色"只能加"随机种子约束"或"生成后改写"。**

| # | 面 | 实际动作 | 证据 |
|---|---|---|---|
| ① | 配置 | **无处可写**：`game-data.json` 的 `db` 实测 10 键无 disciples（A）；`game_config.json` 无招募池字段（A） | 需自增一个 asset JSON + 仿 `GameDataNativeBridge`/`data_inject` 加注入通道 |
| ② | C++ | 扩肖像池 `disciple_factory.h:75/86` 的 `for i in 1..20/1..17` 字面量；若加字段须同步 5 处链路：`models.h:300` → `disciple_store.h:172`（列）→ `disciple_store.cpp:30/168/347/465/601`（materialize/append/load）→ `column_dirty.h:97 枚举 + :223 列名`（否则 bijection 守卫判红）→ `DiscipleColumn` 枚举；另 `determinism_probe.h:185`、`jni/GameCoreJni.cpp:217-219` 对拍字段 | 已复核 |
| ③ | 数据表 | 天赋/体质/词条：**JSON 与 C++ 手写复刻两处都要动**（`trait_db.h:1-18` 自陈手写；漂移由 `DataStoreGuardTest` / `StaticDataSingleSourceGuardTest` 判红）；专属功法：`manuals.json` → `gen-manual-db.mjs` 重生成 `manual_db.h`（生成物禁手改）；专属装备：`scripts/data/equipment_db_sample.json` → `gen-templates.mjs` → `gen-game-data.mjs` | B |
| ④ | UI | **必须改** `PortraitPool.kt:14-15` 字面区间，否则 `getResourceId` 返 0 → 落兜底图；`ResourcePreloader.kt:205` 随 `allPortraitNames()` 自动；`DiscipleComponents.kt:184`、`DiscipleSlotComponents.kt:64/245`、`DetailRightPanel.kt:113`、`DiscipleChatDialog.kt:401` 无需单独改；**无"专属立绘/剧情头像"位** | A/B |
| ⑤ | 战斗 | **无需改**：`discipleToCombatant` 通用装配，`portraitRes` 透传（`battle_calculator.h`、`models.h:438/897/1005/1034`、`sect_defense_battle.h:136`、`secret_realm_settlement.h:129`、`year_settlement.h:1369`） | B |
| ⑥ | 技能 | **无角色专属技能位**：技能只来自 ManualTemplate 字段（`auto_gear.h:556-557`、`disciple_tx.h:192-193`、`data_json.h:150-151`）；弟子侧只有 `manualIds` 字符串数组 → 指定功法 = 生成后改写 | B |
| ⑦ | 资源 | `feature/game/src/main/res/drawable-nodpi/xxx.webp` 放图 + **两端池字面量**；图集/`resource-registry.json` 不涉及（弟子头像不在其中） | A/B |
| ⑧ | 卡池 | **无卡池可改**。要进招募列表只能改生成循环（`RecruitService.kt:395-448` / `year_settlement.h:1428-1513`）或直插 `gameData.recruitList`；注意 `RecruitIntegrity.kt:180` 同人签名与 `recruit_settlement.h:158` 会把 `portraitRes` 纳入查重 | B |
| ⑨ | 存档 | 新字段需：Room migration（`GameDatabaseMigrationsV39.kt:87,183` / `V21ToV30.kt:230` 族）+ `DiscipleSerializer` 新 `@ProtoNumber`（只增不复用，`ProtoNumberUniquenessTest`）+ 7 张弟子表 + `DiscipleAggregate` 对应组件表 + C++ `json_codec` 双向键 + `DiscipleColumn` 枚举 + bijection 守卫；`portraitRes` 现状参考：`DiscipleSerializer.kt:58/207/362 @ProtoNumber(90)`，旧档缺省空串 | B |
| ⑩ | 其他 | `AISectDiscipleManager.kt:263`、`RedeemCodeRewardOps.kt:249` 是另两条独立构造路径，**各自重复同一随机逻辑**，需同改；`DiscipleIdBoundsRule.kt:27` id 上界 200000（A），9999 合法 | B |

### 11.1 【关键发现 · A】7 个 `baseXxx` 字段不进入任何属性公式

`stats::baseStatsWithBr`（`disciple_stats.h:504-536`）构造 `BaseStatsInput` 时填的是：`realm` `realmLayer`、7 个 variance、11 项技能、`mergeEffects(talentEffectsFor(d.talentIds), affixEffectsFor(d.affixIds))`、6 个血炼百分比 —— **从不拷贝 `d.baseHp` / `d.basePhysicalAttack` 等**。

```cpp
// disciple.h:225-265 computeBaseStats —— 基值来自境界表常量 rc，不是弟子字段
inline DiscipleStats computeBaseStats(const BaseStatsInput& in) {
    const auto& rc = realmConfig(in.realm);
    const double layerMult = safeLayerMult(in.realmLayer);        // 1 + (layer-1)*0.1（:136-138）
    const double hpBonus = effectValue(in.talentEffects, "maxHp") + safeBrPct(in.bloodHpBonusPct);
    …
    s.maxHp          = roundToInt(rc.baseHp * hpVar * layerMult * (1.0 + hpBonus));
    s.physicalAttack = roundToInt(rc.basePhysicalAttack * safeVarianceMultiplier(in.physicalAttackVariance)
                                  * layerMult * (1.0 + attackBonus));
    …  s.critRate = kBaseCritRate + critBonus;
}
```

全仓 `baseHp` 实际消费者（A，我逐条列）：
| 位置 | 用途 |
|---|---|
| `disciple_tx.h:1018 const int32_t maxHp = ds.baseHps[row];` | 治疗上限（唯一功能性读取） |
| `ai_sect_recruit.h:333-335` | AI 弟子截断排序键 |
| `battle_residual_tx.h:378 case 10: ds.baseHps[row] += 1;` | 战斗胜利成长**写入** |
| `recruit_settlement.h:172` | 同人全字段去重相等性比较 |
| `gameview_encode.cpp:154 {"baseHp", 33, RowKind::kInt32}` | 镜像编码 |
| `GameCoreJni.cpp:238/240` | 对拍字段 |

Kotlin 同构（A）：`combat.baseHp` 共 19 处引用，功能性读取仅 `DiscipleCombatStats.kt:55`、`DiscipleStatCalculator修炼Ops6.kt:52 "hp" -> combat.baseHp`、`AITruncateOps.kt:25`/`AISectDiscipleManager.kt:444` 排序，`DiscipleDelegates.kt:17-22` 是 `@deprecated` 兼容访问器。

**后果（登记，不修改）**：招募时写入的 `baseHp = 120 × (1 + variance/100)` 与战斗胜利给的"基础属性 +1"成长，**都不影响实战数值与战力**。任何"给角色定基础属性"的做法若落在这些字段上，静默无效。

### 11.2 战力（A，公式已复核）

```cpp
// sect_power.h:26-33
inline int64_t discipleCombatPower(int32_t physicalAttack, int32_t magicAttack,
                                   int32_t maxHp, int32_t physicalDefense,
                                   int32_t magicDefense, int32_t speed) {
    return (physicalAttack + magicAttack) * 5 + maxHp * 4
         + (physicalDefense + magicDefense) * 3 + speed * 2;
}
```
妖兽版同式（`:36-48`，各字段先 `max(x,0)`）。Kotlin 同式：`SectCombatPowerCalculator.kt:30-33`。

- 层级：弟子级是**函数**（非字段），宗门级 = 存活弟子求和：`month_settlement.h:1872-1876 sectPowerOfDisciple` → `:1881-1892 calculateSectPower`（经 ECS view 遍历 `isAlive[row]==1` 累加）→ `:1895+ calculateAiSectPower` 同式作用于 `aiSectDisciples`。
- **战力输入是 `baseStats(d)`（血炼 null 口径），不含装备/功法/丹药；战斗用 `finalStats`。战力 ≠ 实战属性。**（A：`:1873` 调用即证）
- **不持久化、实时算**：`DiscipleStore` 无 power 列（A：grep `power` 仅 `soulPowers:188`，而 `soulPower`（`models.h:329`）是魂力，参与突破率 `min(soulPower/20,5)/100`，另一物）。仅在协议响应中现算返回：`src/execute_dispatch.cpp:937 / :946 return ok({{"power", discipleCombatPower(…)}})`，`SECT_POWER_DISCIPLE=1422`。
- `sectPowerFingerprint`（`sect_power.h:62-93`）不是战力，是 Java `hashCode` 语义的 31 进制混合缓存键（realm/realmLayer/6 variance/talentIds/血炼 6 字段），供"永久基础属性缓存"用。

### 11.3 最终属性聚合顺序（A，`finalStats` `disciple_stats.h:552-634`）

```
1  baseStatsWithBr(d, brPct)          :502-536 → disciple.h:225-279   境界表 × variance × layerMult × (1+天赋/词条/血炼)
2  + 装备                              :563-579  固定序 {weapon, armor, boots, accessory}，equipmentFinalStats 后加算
                                       （含孕养乘区 1 + L(L+1)/2 × 3/325，≤4.0，:261-269）
3  + 功法                              :582-616  遍历 manualIds，每项 × masteryBonus(1.5/2/3/4)，statOf("hp","maxHp") 兜底 :592-598
4  + 丹药                              :619-630  门条件 if (d.pillEffectDuration > 0)
   critRate 单独 double 累加            :560 → :578 → :615 → :629 → :632
```
最终消费点：`discipleToCombatant`（`mission_completion.h:525`）、回写钳制（`sect_defense_battle.h:414`、`battle_residual_tx.h:241`）。

---

## 12. 当前角色系统的实际架构类型

**判定：D. 混合模式 —— 精确表述为「物品/功法/特质层数据驱动 + 角色层代码驱动的程序化生成」。**

| 驱动方式 | 覆盖内容 | 证据 |
|---|---|---|
| **强代码驱动**（角色本体） | 无角色表、无角色定义位；生成参数全为 Kotlin `const val` / C++ `constexpr`（§8.2 表 14 项）；境界寿命 `switch` 硬编码；肖像池/名字池字面量区间；招募节奏（3 年 / 30 上限 / 人数区间）写死；`game_config.json` 不含这些 | A + B |
| **数据驱动** | 10 张模板表（talents 109 / physiques 24 / affixes 71 / manuals 540 / equipment 72 / forgeRecipes 72 / herbs 54 / seeds 54 / pillRecipes 732 / beastMaterials 192，A 实测条目数）；技能效果字段化（`skillHits/skillDamageMultiplier/skillCooldown/skillBuffsJson/skillIsAoe/skillShieldPercent/…`，查表零 id 硬编码分支）；特质 `effects` map 键驱动 | A + B |
| **表驱动但双份内联** | 境界基值表（C++ `disciple.h:69-83` + Kotlin `GameConfig.kt:264-295`）、名字池、肖像池、灵根权重 —— 每样两份到三处同值拷贝 | A |
| **存储层数据导向** | `DiscipleStore` SoA 列存 + 106 项 `DiscipleColumn` 协议枚举 + 列级写屏障 + bijection 守卫 | A |

**角色层不是数据驱动，而是"生成器 + 生成结果"两层。**

---

## 13. 当前系统中已具备的可复用基础（只列事实）

1. **109 字段弟子扁平记录 + 三端双向序列化闭环**：C++ struct ↔ JSON（109 键全等）↔ Kotlin data class ↔ Room 106 列 ↔ protobuf 108 `@ProtoNumber`，字段级往返有测试（`ArchivePayloadRoundTripTest.kt`）。
2. **`DiscipleStore` SoA 列存 + 列级脏导出 + bijection 守卫测试**（`column_dirty_test.cpp:68`）—— 新增字段的同步点是**编译器/测试可穷尽暴露**的，不是靠人肉记忆。
3. **模板注入通道完备**：`GameDataNativeBridge → nativeSetGameData → data_store.loadFromJson → data_inject.applyGameData` 整表 `std::move` 替换，带 `kUninitialized/kLoadedFromFile/kFallbackDefault` 三态 + `isGameDataSealed` 门 + 段缺失整体落兜底语义。
4. **代码生成链已在跑**：`gen-game-data.mjs / gen-manual-db.mjs / gen-templates.mjs / gen-recipe-db.mjs / gen-trait-db.mjs / gen-beast-material-db.mjs / gen-action-ids.mjs`，生成物带"禁止手改"横幅。
5. **值语义战斗单位 `Combatant`**：角色 → 战斗已经是"复制 + 最小回写"模式，不是同对象引用。
6. **统一属性聚合链 `finalStats`**：base→装备→功法（熟练度乘区）→丹药 四段固定顺序，两端逐位对拍；`DiscipleStats` 结构统一。
7. **ActionId 事务分派面**（199 个 / 166 个 case）+ 弟子侧事务化写点（`disciple_tx.h` 9 个事务 + `appointment_tx` + `recruit_tx` + `disciple_lifecycle_tx` + `battle_residual_tx`）。
8. **存档校验与版本框架**：`SaveValidationRule` 链（order 排序，含 id 上界 200000）、`saveVersion` 统一盖章、`@ProtoNumber` 只增不复用且有唯一性测试、C++ 宽松缺省导入宏族。
9. **确定性 RNG 4 分区（BATTLE/BREAKTHROUGH/EXPLORATION/SYSTEM）双端逐位对拍** + `Diff*` 跨语言测试；弟子行序作为 RNG 序列契约被显式建模并有守卫。
10. **ECS 派生迭代层已就位**（`DiscipleRef{row}` + `syncDiscipleEntities` 不变量自愈）—— 已是"实体可挂多组件"的形状，当前只挂一个组件。
11. **`PortraitPool` 运行时字符串 → 资源 id 映射 + 通用兜底图**（新增资源缺失时不崩，退化为通用头像）。
12. **`DiscipleAggregate` 只读投影层**：UI 与存储模型已经解耦，UI 全链路只见 Aggregate。
13. **`name_service` / `trait_db` / `manual_db` 均为模板化、可增条目**（词条 71、天赋 109、功法 540 的实际规模证明表可长）。
14. **`EquipmentInstance.ownerId` / `instance_buckets.h:72-79` owner 索引桶**：物品与角色的归属关系已有独立建模（虽然归属真相在实例侧）。

---

## 14. 当前系统中尚未具备的能力（只列事实）

1. **无角色定义/模板层**：无任何位置可声明"一个具体角色"（§11 前置事实，A）。
2. **无定义/实例分离**：一条 `Disciple` 记录同含身份与全部运行态。
3. **无角色稀有度、无卡池、无保底、无 UP、无碎片、无重复角色转换**（有"同人去重"，但目的是防重复候选，§4.2）。
4. **无 `level` / `exp` 经验条**：成长只有 realm × realmLayer × cultivation。
5. **无星级 / 命座 / 觉醒 / 升阶 / 潜能 / 好感度**（B：全仓 grep `starLevel|constellation|awaken|potential|好感` 零命中；`isAscension` 是"登仙丹"的 `targetRealm==0` 标志，`recipe_db.h:720`，非角色觉醒；`favor` 只挂 `WorldSect`/`SectRelation`，`models.h:817`）。
6. **无角色专属资源绑定**：portraitRes 只能取自两个硬编码区间（男 1..20 / 女 1..17）；无立绘、动画、专属特效、语音位。
7. **无「角色资源 id ↔ 实例 id」映射表**（因为不存在资源 id / 配置 id）。
8. **无内门/外门自动晋升逻辑**：仅 §14.1 的手动事务可达，且无白名单校验。
9. **生成参数未配置化**：14 项角色生成参数全为双端常量（§8.2）。
10. **无弟子数量上限（cap）**：仅月招募上限 30 + 存档 id 上界 200000。
11. **双份/多份实现未收敛**：肖像池两端、名字池两端、境界表两端、`finalStats` 两端、战力两端、`discipleToCombatant` 两端、灵根权重三端；**弟子构造有 4 条独立路径各自重复同一随机逻辑**（`disciple_factory::createDisciple` / `ai_sect_recruit::generateRandomAiDisciple` / `AISectDiscipleManager.kt:218/:263` / `RedeemCodeRewardOps.kt:249`）。
12. **无肖像/名字双端守卫测试**（注释承诺，代码未找到 → D）。
13. **死字段/死配置群（B 清单）**：`RealmConfig::breakthroughBase`（`disciple.h:56` 声明，全仓零读取）、`ManualProficiencyData.level`（零写入，仅默认 1 + 序列化）、`Disciple.manualMasteries`（玩家侧零结算读取）、`totalCultivation`（C++ 零读取）、`manualCompletionMonth/Phase` 与 `equipmentNurturingCompletionMonth/Phase`（双端零写入方）、`hasReviveEffect`、`hasClearAllEffect`、`usedFunctionalPillTypes`、`usedExtendLifePillIds`、`activePillCategory`、`cultivationSpeedBonus/Duration`、`salaryMissedCount`（仅持久化零消费）、`pillSkillExpSpeedBonus`/`pillNurtureSpeedBonus`（只写不清算）、`GameData.unlockedManuals`（零逻辑读取，仅持久化 + UI DTO 透传）、**7 个 `baseXxx`（不进公式，§11.1）**。
14. **战斗写回面过窄且不对称**：只回写 HP/MP + 死亡三元组；`battle_residual_tx.h:223-225` 记录 Kotlin 算出的 `updatedStatus` 从未写回状态列；`boundary_tx.h:17-18` 记录 Kotlin 的 `updateDiscipleHpMpAfterBattle` 为**零调用者死代码**。
15. **玩家弟子无被俘机制**（B）：全仓无 capture-prisoner 路径；"俘虏"仅指敌方弟子经 `prisonerSpiritRootFilter` 招募入宗（`recruit_settlement.h materializeCaptiveGear`、`GameEngineBattleOps.kt:343`）。
16. **招募无结果反馈面**：无招募结果弹窗（§9）。

### 14.1 【对子代理否定式结论的一处纠正 · A】

子代理曾报"`discipleType` 全仓无任何写 inner 的逻辑，内门加成不可达"。我逐跳核实，**该结论错误**。实测完整可达链：

```
DetailRightPanel.kt:206  onTypeSelected(if (localDiscipleType=="outer") "inner" else "outer")
  → :96  viewModel?.disciple?.changeDiscipleType(disciple.id, newType)
  → DiscipleDelegate.kt:67-70  gameEngine.launchOnEngine { gameEngine.changeDiscipleTypeAtomic(…) }
  → GameEngineCoordination.kt:199-202  tryDiscipleOpNative(ActionIds.DISCIPLE_OP_CHANGE_TYPE)
  → action_ids.h:523 = 1741
  → dispatch_w4a.cpp:69-70  case action::DISCIPLE_OP_CHANGE_TYPE → disciple_tx::changeDiscipleTypeTx(…)
  → disciple_tx.h:1319-1331  ds.discipleTypes[*ds.rowOf(discipleId)] = newType
```

**登记（不修改）**：
- 该事务对 `newType` **无白名单校验**，任意字符串直落列；而消费侧 `phase_settlement.h:257 const std::string& targetType = inner ? "inner" : "outer";` 与 `:795 if (d.discipleType != requiredType …)` 按小写精确比较。
- 自身测试 `disciple_ops_tx_test.cpp:514` 传的是大写 `"OUTER"`，`GameEngineDiscipleOpsNativeTxGateTest.kt:157/164` 传 `"OUTER"`/`"INNER"` —— 与生产字面量大小写不一致。

---

## 15. 当前代码无法确认（D 级，需运行时验证）

1. `deathYear` 被 `disciple_store.cpp:180` 丢弃是否为有意设计 —— 无测试断言其往返后保留。
2. 灵根"元素身份"的实际玩法效果：首元素被塞进 `Combatant.element`（`battle.h:260`、`BattleSystem.kt:277-278`、`AISectAttackManager.kt:360-361`，兜底 `"metal"`），但 C++ 战斗计算 grep `element` **无任何克制/加成乘区**。是否别处（Kotlin 战斗/表现层）消费未逐行确认。
3. Kotlin `BattleSystem.convertDiscipleToCombatant`（`BattleSystem.kt:245`）与 C++ `discipleToCombatant`（`mission_completion.h:513`）是否逐字段等价 —— 本次未逐行比对实现体（对拍注释声称等价，属 C 级）。
4. `PortraitPool` 与 `disciple_factory` 双端池漂移的实际影响窗口 —— 未找到守卫，也无法证明"无守卫曾导致事故"。
5. README 所称"`GameStateStore` 是 UI 侧唯一真相源（镜像）"的确切边界：`GameStateStoreImpl.kt:273 disciples: StateFlow<List<Disciple>>` 确实存在，抽样的 UI 文件均为 Aggregate，但**未穷尽 1190 个 Kotlin 文件**逐个确认无旁路。
6. `GameStateRepository.loadFullState` 是否为活路径（仅测试引用）。
7. `syncSlotMetadata(slot, data)` 在 `StorageEngineWriteOps.kt:175-176` 连续调用两次的原因。
8. `assets/data/manuals.pb`（`ManualDatabase.kt:424` 首选路径）仓库中不存在 → 生产实际走 JSON 分支还是另有打包步骤。
9. 招募候选在 Kotlin 臂与 C++ 臂之间的**当前生效归属**：两臂都在代码中，`NativeEngineFlag.Mode.AUTHORITATIVE` 具体覆盖到哪一步未逐条追。
10. 各"回退臂"在生产是否真会被触发：native 信封失败即回退（`GameEngineNativeOps.kt:61-88`），但无运行时命中率证据。
11. `game-data.hash.txt` 与 `game-data.json` 的一致性是否在 CI 强校验（`scripts/` 生成器存在，门禁强度未验）。
12. 37 张弟子头像的绘制归属与是否支持按角色定制（美术资产流程不在代码内）。

---

## 16. 现状与「角色卡池 + 少量长期养成角色」方向的差异（只列事实，不给改造方案）

1. **无定义层**：卡池的前提是"一组预定义角色"。当前 `createDisciple` 的身份字段（名字/立绘/天赋/属性）无入参可覆盖，且 `game-data.json` 无 disciples 表（A）。
2. **id 语义与该方向相反**：已拥有弟子 id = `max+1` 自增数字，且招募瞬间**无条件覆盖**候选 id（`recruit_settlement.h:390 d.id = idStr`，A）；候选 id = UUID；AI 弟子 = `gc-ai-d-N`。该方向需要"配置 id 稳定可寻址且与实例 id 分离"，当前恰好是"实例 id 吞掉一切"。
3. **群体模拟 vs 少量角色**：字段设计面向"成百上千个会老、会死、会叛逃、会生育、会偷盗、会领俸禄的模拟体"（寿命 / 丧亲 / 忠诚 / 叛逃 / 俸禄 / 血炼 / 储物袋 / 师徒 / 亲缘），无"玩家主力角色"与"NPC 消耗品"的类型区分；`discipleType` 只有手动 outer/inner 标签（§14.1）。
4. **成长轴不同构**：无 level/exp/星级；只有 realm(0-9) × realmLayer(1-9)，而 realm **同时**驱动属性基值、寿命、修炼速率、突破率、穿戴门槛、生产成功率、年俸索引 —— 是一个**强耦合的单一进度条**，不是可独立拉升的角色等级。
5. **稀有度方向一致但机制不是抽取式**：灵根越少越强（个数是修炼速度**除数**，`disciple_stats.h:763-766`，同时是悟性/资质阶梯正向因子，`disciple_factory.h:436-440`），而 1 根概率仅 1%、5 根 40%（`child_birth.h:51-55`）。这是"属性分布的自然尾部"，不是卡池权重。
6. **删除即物理删行**：死亡/叛逃/逐出走 `removeById`（`disciple_store.h:361`），无"角色卡收藏 / 图鉴 / 回收为碎片"的状态位；`archived_disciples` 表存在但只有 7 列（A 实测），不是角色卡归档。
7. **资源绑定受池大小上限约束**：男 20 / 女 17 是双端字面量区间，第 21 张男头像需改两端代码（A）。
8. **给角色定基础属性的直觉做法当前无效**：写进 `baseHp` 等 7 字段不进任何公式（§11.1，A）。
9. **战力口径与实战口径不一致**：战力用 `baseStats`（不含装备/功法/丹药），战斗用 `finalStats`；若以战力作为卡池验收指标，会出现"战力不涨但实战变强"（或反之）的系统性错位。
10. **战力不落库、UI 侧另有两份实现**：宗门总战力由 Kotlin 计算（`GameStateStoreImpl.kt:512`），非引擎返回值 —— 验收时须先确定以哪份为准。
11. **存档模型对"少量高价值角色"过肥**：单条 106 列 + 7 表复制 + 储物袋内嵌 39 个 `@ProtoNumber` 的完整 `ItemEffect` 配置 —— 该结构是为群体模拟的批量落盘优化的，不是为"少数角色精细养成面板"优化的。

---

## 附录 A：本次勘察涉及的关键文件清单

**C++ 真相层**
`include/gamecore/state/models.h`（1487 行，`struct Disciple:300`、`GameData:1258`、`GameState:1447`）· `state/disciple_store.h`（406）· `src/disciple_store.cpp` · `state/column_dirty.h`（704）· `state/json_codec.h/.cpp` · `state/gameview_encode.cpp` · `ecs/disciple_component.h` · `ecs/npc_component.h` · `system/disciple_factory.h`（482）· `system/disciple.h` · `system/disciple_stats.h`（900）· `system/recruit_settlement.h`（916）· `system/recruit_tx.h` · `system/ai_sect_recruit.h` · `system/name_service.h` · `system/child_birth.h` · `system/cultivation.h` · `system/breakthrough.h` · `system/phase_settlement.h`（1470）· `system/month_settlement.h`（2470）· `system/year_settlement.h`（1874）· `system/battle.h` · `system/battle_calculator.h`（858）· `system/battle_execution.h`（1233）· `system/battle_ai.h` · `system/battle_residual_tx.h` · `system/sect_battle.h` · `system/sect_defense_battle.h` · `system/sect_conquest.h` · `system/sect_power.h` · `system/mission_completion.h`（1198）· `system/disciple_tx.h`（1786）· `system/disciple_lifecycle_tx.h` · `system/death_handler.h` · `system/slot_cleanup.h` · `system/auto_gear.h`（951）· `system/pill_system.h` · `system/blood_refinement.h` · `system/profession.h` · `system/appointment_tx.h`（1063）· `system/rarity_progression.h` · `system/nurture_constants.h` · `data/trait_db.h`（781）· `data/manual_db.h`（7648）· `data/equipment_db.h` · `data/recipe_db.h` · `data/data_inject.h` · `data/data_store.h` · `src/execute_dispatch.cpp`（2799）· `src/dispatch_w4{a,b,c,d}.cpp` · `include/gamecore/action_ids.h` · `jni/GameCoreJni.cpp` · `test/column_dirty_test.cpp` · `test/disciple_factory_test.cpp`

**Kotlin 层**
`core/domain/.../core/model/Disciple.kt` · `DiscipleComponents.kt` · `DiscipleSerializer.kt` · `DiscipleAggregate.kt` · `DiscipleDelegates.kt` · `DiscipleTables.kt` · `core/domain/.../GameConfig.kt` · `core/domain/.../util/PortraitPool.kt` · `NameService.kt` · `SpiritRootGenerator.kt` · `SectLevel.kt` · `core/domain/.../core/util/DiscipleIndex.kt` · `core/engine/.../nativebridge/{GameCoreBridge,StateSyncService,NativeGameState,ActionIds}.kt` · `core/engine/.../domain/disciple/DiscipleStatCalculator*.kt`（8 分部）· `DiscipleFactory.kt` · `domain/battle/BattleSystem.kt` · `EnemyGenerator.kt` · `domain/diplomacy/{AISectDiscipleManager,AISectAttackManager,AITruncateOps}.kt` · `GameEngine{NativeOps,ManualOps,CoreNativeOps,CoreAuthoritativeOps,LoadDataOps,BattleOps,Coordination}.kt` · `GameStateStore(-Impl).kt` · `StorageEngine(-WriteOps|HeavyDataOps).kt` · `RecruitService.kt` · `DiscipleEquipmentService.kt` · `ManualProficiencySystem.kt` · `SectCombatPowerCalculator.kt` · `nativeengine/NativeEngineFlag.kt` · `save/rules/DiscipleIdBoundsRule.kt` · `feature/game/.../{DisciplesTab,DiscipleDetailScreen,GameOverlayHost,DialogCommon}.kt` · `components/{DiscipleComponents,DiscipleSlotComponents}.kt` · `components/detail/Detail*.kt` · `dialogs/{RecruitDialog,AttackDiscipleDialog,TianshuHallDialog,…}.kt` · `delegate/{DiscipleDelegate,OverlayDelegate}.kt` · `app/.../XianxiaApplication.kt` · `app/src/main/cpp/GameCoreBridge.cpp`

**数据与配置**
`assets/data/game-data.json`（1.6MB，`db` 10 表）· `assets/data/manuals.json` · `assets/data/game-data.hash.txt` · `assets/config/game_config.json` · `assets/config/buildings.json` · `assets/atlas/atlas-rgba-manifest.json` · `core/data/schemas/com.xianxia.sect.data.local.GameDatabase/52.json` · `scripts/gen-*.mjs`（7 个）· `scripts/data/*_db_sample.json`（6 个）· `scripts/build-atlas.mjs` · `scripts/resource-registry.json`
