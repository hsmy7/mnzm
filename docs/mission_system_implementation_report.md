# 宗门任务系统 v2.4.0 实现报告

## 一、实现概述

根据 `docs/mission_system_design.md` 设计方案，完成宗门任务系统全面升级，从原有3个任务模板扩展至24个，覆盖4种难度、3种任务类型，新增人型敌人战斗系统，并修复了4个已有严重缺陷。

**版本**：2.3.33 → 2.4.0（versionCode: 2046 → 2047）

---

## 二、变更文件清单

| 文件 | 操作 | 说明 |
|------|------|------|
| `core/model/Mission.kt` | 修改 | 新增枚举、扩展24个任务模板、扩展奖励配置 |
| `core/engine/EnemyGenerator.kt` | **新增** | 人型敌人生成器 |
| `core/engine/MissionSystem.kt` | 重写 | 奖励生成、弟子校验、任务完成逻辑 |
| `core/engine/BattleSystem.kt` | 修改 | 修复 beastLevel 参数被忽略的bug |
| `core/engine/GameEngine.kt` | 修改 | 传入 battleSystem，处理战斗失败 |
| `core/engine/service/CultivationService.kt` | 修改 | 同上 |
| `ui/game/MissionHallScreen.kt` | 修改 | 弟子筛选增加境界检查 |
| `data/serialization/unified/SerializableSaveData.kt` | 修改 | 新增15个ProtoNumber字段 |
| `data/serialization/unified/SerializationModule.kt` | 修改 | 完整序列化+旧存档兼容迁移 |
| `test/.../MissionSystemTest.kt` | 重写 | 测试用例更新 |
| `app/build.gradle` | 修改 | 版本号更新 |
| `CHANGELOG.md` | 修改 | 更新日志 |

---

## 三、核心实现详情

### 3.1 Mission.kt — 数据模型扩展

**新增枚举**：

| 枚举 | 值 | 说明 |
|------|-----|------|
| `MissionType` | NO_COMBAT, COMBAT_REQUIRED, COMBAT_RANDOM | 三种任务类型 |
| `EnemyType` | BEAST, HUMAN | 两种敌人类型 |

**MissionTemplate 枚举（3→24个）**：

| 难度 | 无战斗 | 必战斗 | 概率突发 |
|------|--------|--------|----------|
| 简单 | ESCORT_CARAVAN, PATROL_TERRITORY, DELIVER_SUPPLIES | SUPPRESS_LOW_BEASTS, CLEAR_BANDITS | EXPLORE_ABANDONED_MINE |
| 普通 | ESCORT_SPIRIT_CARAVAN, INVESTIGATE_ANOMALY, DELIVER_PILLS | SUPPRESS_JINDAN_BEASTS, DESTROY_MAGIC_OUTPOST | EXPLORE_ANCIENT_CAVE |
| 困难 | ESCORT_IMMORTAL_ENVOY, REPAIR_ANCIENT_FORMATION, SEARCH_MISSING_ELDER | SUPPRESS_HUASHEN_BEAST_KING, DESTROY_MAGIC_BRANCH | EXPLORE_ANCIENT_BATTLEFIELD |
| 禁忌 | ESCORT_RELIC_ARTIFACT, SEAL_SPATIAL_RIFT, SEARCH_SECRET_REALM_CLUE | SUPPRESS_ANCIENT_FIEND, DESTROY_MAGIC_HEADQUARTERS | EXPLORE_CORE_BATTLEFIELD |

每个模板新增属性：`missionType`, `triggerChance`, `enemyType`, `beastCountRange`, `humanCountRange`

**MissionDifficulty 新增属性**：
- `minRealm`：最低境界要求（简单=9, 普通=7, 困难=5, 禁忌=3）
- `allowedDiscipleTypes`：允许的弟子类型列表
- `enemyRealmMin`/`enemyRealmMax`：敌人境界范围

**MissionRewardConfig 扩展（6→21个字段）**：

| 新增字段 | 类型 | 默认值 | 说明 |
|----------|------|--------|------|
| pillCountMin/Max | Int | 0 | 丹药数量范围 |
| pillMinRarity/MaxRarity | Int | 1 | 丹药稀有度范围 |
| equipmentChance | Double | 0.0 | 概率掉落装备 |
| equipmentMinRarity/MaxRarity | Int | 1 | 装备稀有度范围 |
| manualChance | Double | 0.0 | 概率掉落功法 |
| manualMinRarity/MaxRarity | Int | 1 | 功法稀有度范围 |
| baseSpiritStones | Int | 0 | 概率战斗基础灵石 |
| baseMaterialCountMin/Max | Int | 0 | 基础材料数量 |
| baseMaterialMinRarity/MaxRarity | Int | 1 | 基础材料稀有度 |

**Mission/ActiveMission 新增字段**：`missionType`, `enemyType`, `triggerChance`（均有默认值，保持序列化兼容）

### 3.2 EnemyGenerator.kt — 人型敌人生成器

**核心逻辑**：

```
人型敌人 = 基础属性（境界×倍率） + 装备加成（含孕养） + 功法技能（含熟练度）
```

**装备生成规则**：
- 数量：0-4件（`Random.nextInt(0, 5)`）
- 槽位：WEAPON/ARMOR/BOOTS/ACCESSORY 随机打乱后取前N个，保证不重复
- 稀有度：根据敌人境界确定 minRarity/maxRarity
- 孕养等级：0 到该稀有度最大孕养等级之间随机

**功法生成规则**：
- 数量：0-5本（`Random.nextInt(0, 6)`）
- 心法限制：最多1本 ManualType.MIND，通过标志位 `hasMindManual` 控制
- 其余功法从 ATTACK/DEFENSE/SUPPORT 中随机
- 熟练度：0-3 随机，通过 `ManualProficiencySystem.calculateSkillDamageMultiplier` 调整技能伤害倍率

**属性计算**：
- 基础属性 = 固定基础值 × 境界倍率 × 小层加成
- 装备加成通过 `EquipmentInstance.getFinalStats()` 获取（已含孕养加成）
- 暴击率 = 0.05 + realm × 0.01 + 装备暴击

### 3.3 MissionSystem.kt — 任务系统逻辑

**弟子校验规则**：

| 难度 | 弟子类型 | 最低境界 |
|------|----------|----------|
| 简单 | outer（外门） | 无限制 |
| 普通 | outer/inner | 金丹(realm≤7) |
| 困难 | inner（内门） | 化神(realm≤5) |
| 禁忌 | inner（内门） | 合体(realm≤3) |

**任务完成逻辑**：

```
NO_COMBAT       → 必定成功，发放完整奖励
COMBAT_REQUIRED → 必须战斗，胜利得奖励，失败无奖励
COMBAT_RANDOM   → 概率触发战斗
                    未触发 → 基础奖励（baseSpiritStones + 少量材料）
                    触发且胜利 → 完整奖励
                    触发且失败 → 无奖励
```

**战斗系统集成**：
- 妖兽战斗：`BattleSystem.createBattle()`，beastLevel 在 enemyRealmMin~enemyRealmMax 之间随机
- 人型战斗：`EnemyGenerator.generateHumanEnemies()` 生成敌人，手动构建 `Battle` 对象

**奖励生成**：
- 灵石：固定值或范围随机
- 材料：`BeastMaterialDatabase` 按稀有度筛选随机
- 丹药：`ItemDatabase.generateRandomPill` 按稀有度
- 装备：`EquipmentDatabase.generateRandom` 按概率和稀有度
- 功法：`ManualDatabase.generateRandom` 按概率和稀有度

### 3.4 奖励差异化对照

| 难度 | 无战斗灵石 | 必战斗灵石 | 材料稀有度 | 额外奖励 |
|------|-----------|-----------|-----------|---------|
| 简单 | 300-600 | 400-500 | 1 | 无 |
| 普通 | 800-1500 | 1200-1500 | 2-3 | 概率装备/功法(稀有度2-3) |
| 困难 | 20000-40000 | 30000-40000 | 4-5 | 概率装备/功法(稀有度4-5) |
| 禁忌 | 100000-200000 | 150000-200000 | 5-6 | 概率装备/功法(稀有度5-6) |

---

## 四、修复的已有缺陷

### 4.1 BattleSystem.createBattle beastLevel 参数被忽略

**问题**：`beastLevel` 参数传入后被 `GameUtils.calculateBeastRealm(disciples)` 覆盖，妖兽境界始终由弟子平均境界决定。

**修复**：当 `beastLevel in 0..9` 时直接使用，否则回退到弟子平均境界计算。

```kotlin
val beastRealm = if (beastLevel in 0..9) {
    beastLevel
} else {
    GameUtils.calculateBeastRealm(disciples, ...)
}
```

### 4.2 GameEngine/CultivationService 未传入 battleSystem

**问题**：`processMissionCompletion` 调用时未传入 `battleSystem`、`equipmentMap`、`manualMap`、`manualProficiencies`，导致所有战斗任务默认失败（`battleSystem == null` 时返回 `null`）。

**修复**：两个调用点均构建完整的装备/功法映射和熟练度映射，传入 `battleSystem` 实例。

### 4.3 MissionRewardConfig 序列化丢失字段

**问题**：`SerializableActiveMission` 和 `SerializableMission` 只序列化了6个旧字段，丹药/装备/功法/基础奖励的15个新字段存档后丢失。

**修复**：在 `SerializableSaveData.kt` 中新增 ProtoNumber 36-50 字段（均有默认值），`SerializationModule.kt` 中完整读写。

### 4.4 旧存档 MissionTemplate 枚举名不兼容

**问题**：旧枚举名 `ESCORT`/`SUPPRESS_BEASTS`/`SUPPRESS_BEASTS_NORMAL` 在新枚举中不存在，反序列化时 `valueOf()` 抛异常后回退到默认值，导致所有旧任务变成"护送商队"。

**修复**：新增 `migrateMissionTemplate()` 方法，显式映射旧名到新名：

```kotlin
"ESCORT" → ESCORT_CARAVAN
"SUPPRESS_BEASTS" → SUPPRESS_LOW_BEASTS
"SUPPRESS_BEASTS_NORMAL" → SUPPRESS_JINDAN_BEASTS
```

---

## 五、序列化兼容性

### 5.1 新字段默认值策略

所有新增字段均有默认值，旧存档反序列化时自动填充：

| 字段类别 | 默认值 | 含义 |
|----------|--------|------|
| pillCountMin/Max | 0 | 无丹药奖励 |
| equipmentChance/manualChance | 0.0 | 无装备/功法掉落 |
| baseSpiritStones | 0 | 无基础灵石 |
| missionType | NO_COMBAT | 无战斗任务 |
| enemyType | BEAST | 妖兽敌人 |
| triggerChance | 0.0 | 不触发战斗 |

### 5.2 ProtoNumber 分配

新增字段使用 ProtoNumber 36-50，避免与现有字段（1-35）冲突。ProtoBuf 协议天然支持字段缺失时使用默认值。

### 5.3 旧存档迁移路径

```
旧存档 → 反序列化 → migrateMissionTemplate() 映射枚举名 → 新数据模型
                      ↓
               新字段取默认值（无丹药/装备/功法奖励）
                      ↓
               重新序列化时写入完整字段
```

---

## 六、UI 变更

**MissionHallScreen.kt**：
- 弟子筛选增加境界检查：`disciple.realm <= mission.difficulty.minRealm`
- 需求文本显示境界要求：`"要求：外门弟子，炼气及以上，空闲状态"`

---

## 七、测试覆盖

`MissionSystemTest.kt` 覆盖以下场景：

- 所有难度有显示名称
- 持续时间递增
- 24个任务模板均有名称和描述
- 每个难度6个模板（2无战斗+2必战斗+2概率战斗）
- 所有模板需要6名弟子
- 境界限制正确
- 敌人境界范围正确
- 奖励配置正确（ESCORT_CARAVAN固定600灵石等）
- NO_COMBAT任务必定成功
- COMBAT_RANDOM任务有触发率
- 弟子校验：外门弟子通过简单/不通过困难、境界不足不通过
- 月度刷新机制

---

## 八、已知限制与后续优化方向

1. **概率战斗基础奖励**：设计文档要求"基础奖励(灵石30%)"，当前实现使用 `baseSpiritStones` 固定值而非完整奖励的30%，与设计文档的语义略有差异，但数值已按30%比例配置
2. **人型敌人名称**：当前使用通用名称（魔修/邪修/散修等），后续可根据难度/场景定制
3. **战斗失败弟子受伤**：当前战斗失败仅无奖励，未实现弟子受伤/死亡逻辑（需与 BattleSystem 的战斗日志集成）
4. **任务刷新权重**：当前使用 `MissionTemplate.entries.random()` 均匀随机，设计文档要求按难度权重（简单25%/普通12%/困难3%/禁忌0.5%）
