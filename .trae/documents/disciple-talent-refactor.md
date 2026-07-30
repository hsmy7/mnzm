# 弟子天赋三分类重构方案

## Summary

将现有单一的 `Talent` 概念拆分为三个独立系统：**天赋**（基础属性 + 战斗属性 + 职务加成）、**体质**（修炼速度 + 战斗伤害特殊加成）、**词条**（通用加成，覆盖所有加成类型）。三者各自独立生成、独立持久化、独立计算，在属性计算与战斗结算的乘区结构中占据不同层级。

---

## Current State Analysis

### 现有单一天赋系统

| 要素 | 位置 | 说明 |
|------|------|------|
| 领域模型 | [Disciple.kt#L358-L381](file:///c:/Mnzm/XianxiaSectNative/android/core/domain/src/main/java/com/xianxia/sect/core/model/Disciple.kt) | `Talent` data class，`effects: Map<String, Double>` 承载效果 |
| 静态库 | [TalentDatabase.kt](file:///c:/Mnzm/XianxiaSectNative/android/core/domain/src/main/java/com/xianxia/sect/core/registry/TalentDatabase.kt) | 22 种 `TalentType`，6 档 rarity，生成 0-5 个 |
| 注册表 | [TalentRegistry.kt](file:///c:/Mnzm/XianxiaSectNative/android/core/domain/src/main/java/com/xianxia/sect/core/registry/TalentRegistry.kt) | 薄封装 |
| 持久化 | [DiscipleExtended.kt#L18](file:///c:/Mnzm/XianxiaSectNative/android/core/domain/src/main/java/com/xianxia/sect/core/model/DiscipleExtended.kt) | `talentIds: List<String>` 存于 `disciples_extended` 表 |
| 弟子字段 | [Disciple.kt#L93](file:///c:/Mnzm/XianxiaSectNative/android/core/domain/src/main/java/com/xianxia/sect/core/model/Disciple.kt) | `talentIds: List<String>` |
| 计算入口 | [DiscipleStatCalculator.kt#L36-L51](file:///c:/Mnzm/XianxiaSectNative/android/core/engine/src/main/java/com/xianxia/sect/core/domain/disciple/DiscipleStatCalculator.kt) | `computeTalentEffects()` 聚合 effects map |
| 生成入口 | [DiscipleFactory.kt#L116-L117](file:///c:/Mnzm/XianxiaSectNative/android/core/engine/src/main/java/com/xianxia/sect/core/domain/disciple/DiscipleFactory.kt) | `TalentDatabase.generateTalentsForDisciple()` |

### 天赋加成流向（4 条路径）

1. **基础属性**（[DiscipleStatCalculator.computeBaseStats() L55-L125](file:///c:/Mnzm/XianxiaSectNative/android/core/engine/src/main/java/com/xianxia/sect/core/domain/disciple/DiscipleStatCalculator.kt)）：天赋百分比与血炼百分比**同乘区加算** `(1+bonus)`；扁平加成直接加到 SkillStats。
2. **修炼速度**（[computeCultivationZones() L435-L489](file:///c:/Mnzm/XianxiaSectNative/android/core/engine/src/main/java/com/xianxia/sect/core/domain/disciple/DiscipleStatCalculator.kt)）：天赋 `cultivationSpeed` 独占 `aptitudeBonus` 乘区。
3. **突破概率**（[computeBreakthroughZones() L640-L668](file:///c:/Mnzm/XianxiaSectNative/android/core/engine/src/main/java/com/xianxia/sect/core/domain/disciple/DiscipleStatCalculator.kt)）：天赋 `breakthroughChance` 进入 `selfBonus` 乘区。
4. **功法槽/寿命**：`manualSlot` 扁平加成；`lifespan` 在 Factory 与 BreakthroughHandler 中应用。

### 现有 22 种 TalentType 归类

| 类别 | TalentType | 目标归属 |
|------|-----------|----------|
| 修炼 | CULT_SPEED | 体质 |
| 修炼 | BREAK_CHANCE | **移除** |
| 修炼 | LIFESPAN | 词条 |
| 战斗属性 | BAT_PHY/MAG_ATK/DEF, BAT_HP/MP/SPEED/CRIT | 天赋 |
| 基础属性 | BASE_INT/CHARM/LOYAL/COMP/ARTI/PILL/PLANT/TEACH/MORAL/MINING | 天赋 |
| 特殊 | MANUAL_SLOT, WIN_GROWTH | 词条 |

### 战斗伤害乘区现状

[BattleCalculator.DamageZones](file:///c:/Mnzm/XianxiaSectNative/android/core/engine/src/main/java/com/xianxia/sect/core/util/BattleCalculator.kt)（L24-L30）共 5 个乘区：`attackBuffs`、`defensePenetration`、`critDamageBonus`、`damageAmplification`、`damageReduction`，全部从 `Combatant.buffs` 构建。

### 职务系统现状

[ElderSlotType](file:///c:/Mnzm/XianxiaSectNative/android/core/domain/src/main/java/com/xianxia/sect/core/model/ElderSlotType.kt) 共 10 种职务。当前职务只对**他人**产生加成，职务持有者自身无职务加成。

---

## Proposed Changes

### 决策前提（已确认）

1. **职务加成机制**：拥有职务天赋/词条的弟子担任职务时，**增强该职务的职能效果**（如招贤伯乐→担任招贤长老→招募弟子数上限+10%），不作用于弟子自身属性。
2. **战斗属性归属**：战斗属性加成仅属天赋/词条；体质不含面板战斗属性，只保留修炼速度 + 战斗伤害特殊加成。
3. **体质战斗加成为独立乘算**：体质的伤害加成/减免/减伤/防御加成在 `BattleCalculator` 中作为**独立乘法因子**，不与 buff 同乘区加算。
4. **词条为全能加成池**：词条覆盖所有加成类型——基础属性、战斗属性、职务、战斗伤害特殊、修炼速度。词条是独立于天赋/体质的额外修饰层。
5. **稀有度系统**：天赋、体质、词条均分为 **3 阶**（rarity 1-3），每阶加成数值重新设计梯度。三类**均包含负面加成**，负面统一使用一个颜色（灰色 `#9E9E9E`）。
6. **生成数量**：天赋、体质、词条均生成 **0-3 个**。
7. **天赋清理**：移除 `CULT_SPEED`（迁体质）、`LIFESPAN`/`MANUAL_SLOT`/`WIN_GROWTH`（迁词条）、`BREAK_CHANCE`（直接删除）。**旧天赋定义保留**（不从代码中删除），仅从新生成池中移除，旧存档弟子 talentIds 可正常解析。
8. **旧存档迁移**：旧存档弟子 `talentIds` 保留不变，`physiqueIds = empty`、`affixIds = empty`（视为无体质无词条）。
9. **LIFESPAN 归入词条**，**BREAK_CHANCE 直接移除**。

---

### 1. 数据模型层（core:domain）

#### 1.1 新增 `Physique` 体质模型

**文件**：[Disciple.kt](file:///c:/Mnzm/XianxiaSectNative/android/core/domain/src/main/java/com/xianxia/sect/core/model/Disciple.kt)（新增 data class）

```kotlin
data class Physique(
    val id: String,
    val name: String,
    val description: String,
    val rarity: Int,                    // 1-3 阶（负面为 0）
    val cultivationSpeedBonus: Double,  // 修炼速度加成（进入 aptitudeBonus 乘区）
    val damageAmplification: Double,    // 伤害加成（独立乘算因子）
    val damageReduction: Double,        // 减伤（独立乘算因子）
    val critDamageBonus: Double,        // 暴击伤害加成
    val defenseBonus: Double,           // 防御加成（独立乘算因子）
    val isNegative: Boolean = false
)
```

#### 1.2 `Talent` 模型扩展

**文件**：[Disciple.kt#L358-L381](file:///c:/Mnzm/XianxiaSectNative/android/core/domain/src/main/java/com/xianxia/sect/core/model/Disciple.kt)

```kotlin
data class Talent(
    val id: String,
    val name: String,
    val description: String,
    val rarity: Int,                    // 1-3 阶（负面为 0）
    val effects: Map<String, Double>,
    val isNegative: Boolean = false,
    val positionBonus: PositionBonus? = null  // 职务职能效果加成（为空表示非职务类天赋）
)

data class PositionBonus(
    val slotType: ElderSlotType,
    val effectBonus: Double             // 职务职能效果百分比加成（0.10 = 职能效果+10%）
)
```

**PositionBonus 语义**：当拥有此天赋/词条的弟子担任对应职务时，该职务的**职能效果**获得额外百分比加成（乘算）。例如招贤伯乐天赋 +10% → 担任招贤长老时，招募弟子数上限额外×1.1。不作用于弟子自身属性。

#### 1.3 新增 `Affix` 词条模型

**文件**：[Disciple.kt](file:///c:/Mnzm/XianxiaSectNative/android/core/domain/src/main/java/com/xianxia/sect/core/model/Disciple.kt)

```kotlin
data class Affix(
    val id: String,
    val name: String,
    val description: String,
    val rarity: Int,                    // 1-3 阶（负面为 0）
    val effects: Map<String, Double>,
    val isNegative: Boolean = false,
    val positionBonus: PositionBonus? = null  // 词条也可提供职务加成
)
```

#### 1.4 `Disciple` 实体新增字段

**文件**：[Disciple.kt#L93](file:///c:/Mnzm/XianxiaSectNative/android/core/domain/src/main/java/com/xianxia/sect/core/model/Disciple.kt)

```kotlin
val physiqueIds: List<String> = emptyList(),  // 体质（0-3 个）
val affixIds: List<String> = emptyList()      // 词条（0-3 个）
```

#### 1.5 `DiscipleExtended` 持久化扩展

**文件**：[DiscipleExtended.kt#L18](file:///c:/Mnzm/XianxiaSectNative/android/core/domain/src/main/java/com/xianxia/sect/core/model/DiscipleExtended.kt)

```kotlin
var physiqueIds: List<String> = emptyList(), // 新增（0-3 个）
var affixIds: List<String> = emptyList()     // 新增（0-3 个）
```

同步更新 `fromDisciple()` 映射。

---

### 2. 注册表层（core:domain）

#### 2.1 `TalentDatabase` 重构

**文件**：[TalentDatabase.kt](file:///c:/Mnzm/XianxiaSectNative/android/core/domain/src/main/java/com/xianxia/sect/core/registry/TalentDatabase.kt)

**变更**：
- 移除 `CULT_SPEED`（迁移至 PhysiqueDatabase）
- 移除 `LIFESPAN`、`MANUAL_SLOT`、`WIN_GROWTH`（迁移至 AffixDatabase）
- **移除 `BREAK_CHANCE`**（直接删除，突破概率不再受天赋/词条影响）
- 保留全部 `BAT_*` 与 `BASE_*` 类型
- 新增职务类天赋模板
- **`talentGrade()` 压缩逻辑保留**：用于旧天赋定义的 rarity 映射（旧天赋仍为 1-6 序号）；新天赋定义直接使用 rarity 1-3
- **新天赋生成数量改为 0-3**：`Random.nextInt(0, 6)` 改为 `Random.nextInt(0, 4)`
- 负面天赋机制保留（现有 `NEGATIVE_TALENT_CHANCE = 0.14`）
- **旧天赋定义保留**：被清理的天赋（CULT_SPEED/LIFESPAN/MANUAL_SLOT/WIN_GROWTH/BREAK_CHANCE）的定义不从 `TalentDatabase.talents` 中删除，仅从 `generateTalentsForDisciple()` 的生成池中移除，确保旧存档弟子 talentIds 可正常解析

**完整天赋列表**（每个模板 3 阶，加成梯度重新设计）：

##### 基础属性类天赋（正面）

| 天赋名 | 加成 | 1阶 | 2阶 | 3阶 |
|--------|------|-----|-----|-----|
| 天慧 | 智力 | 4 | 10 | 18 |
| 仙姿 | 魅力 | 4 | 10 | 18 |
| 赤诚 | 忠诚 | 4 | 10 | 18 |
| 顿悟 | 悟性 | 4 | 10 | 18 |
| 天工 | 炼器 | 4 | 10 | 18 |
| 天丹 | 炼丹 | 4 | 10 | 18 |
| 青帝 | 灵植 | 4 | 10 | 18 |
| 地眼 | 采矿 | 4 | 10 | 18 |
| 夫子 | 传道 | 4 | 10 | 18 |
| 仁心 | 德行 | 4 | 10 | 18 |

##### 战斗属性类天赋（正面，百分比加成）

| 天赋名 | 加成 | 1阶 | 2阶 | 3阶 |
|--------|------|-----|-----|-----|
| 勇武 | 物攻 | 6% | 13% | 22% |
| 神通 | 法攻 | 6% | 13% | 22% |
| 铁骨 | 物防 | 6% | 13% | 22% |
| 玄清 | 法防 | 6% | 13% | 22% |
| 体健 | 气血 | 10% | 18% | 30% |
| 气海 | 法力 | 10% | 18% | 30% |
| 疾风 | 速度 | 6% | 13% | 22% |
| 锋锐 | 暴击 | 4% | 8% | 14% |

##### 职务类天赋（正面，担任职务时增强该职务职能效果）

| 天赋名 | 对应职务 | 职能效果加成 | 1阶 | 2阶 | 3阶 |
|--------|---------|------------|-----|-----|-----|
| 辅政之才 | 副宗主 | 政策效果加成 | 7% | 14% | 22% |
| 灵田灵手 | 灵田长老 | 灵药成熟速度加成 | 7% | 14% | 22% |
| 丹道宗师 | 炼丹长老 | 炼丹成功率加成 | 7% | 14% | 22% |
| 器道宗师 | 炼器长老 | 炼器成功率加成 | 7% | 14% | 22% |
| 外门栋梁 | 外门长老 | 外门弟子突破指导加成 | 7% | 14% | 22% |
| 传道大师 | 传道长老 | 外门弟子传道修炼速度加成 | 7% | 14% | 22% |
| 执法金刚 | 执法长老 | 叛逃/偷盗捕获率加成 | 7% | 14% | 22% |
| 内门柱石 | 内门长老 | 内门弟子突破指导加成 | 7% | 14% | 22% |
| 招贤伯乐 | 招贤长老 | 招募弟子数上限加成 | 7% | 14% | 22% |
| 青云传道 | 青云传道 | 内门弟子传道修炼速度加成 | 7% | 14% | 22% |

**注**：职能效果加成为乘算加成。例如招贤伯乐 2 阶（+14%）→ 担任招贤长老时，招募弟子数上限 `bonusCap × 1.14`。

##### 负面天赋（统一灰色，无品阶区分）

| 天赋名 | 加成 | 数值 |
|--------|------|-----|
| 愚钝 | 智力 | -12 |
| 丑陋 | 魅力 | -12 |
| 叛逆 | 忠诚 | -12 |
| 鲁钝 | 悟性 | -12 |
| 怯懦 | 物攻 | -15% |
| 体虚 | 气血 | -20% |
| 迟缓 | 速度 | -15% |

**注**：负面天赋统一 rarity=0、颜色为灰色 `#9E9E9E`，无品阶区分，单一数值。

#### 2.2 新增 `PhysiqueDatabase`

**文件**：`c:\Mnzm\XianxiaSectNative\android\core\domain\src\main\java\com\xianxia\sect\core\registry\PhysiqueDatabase.kt`（新建）

```kotlin
object PhysiqueDatabase {
    fun generateForDisciple(): List<Physique>  // 0-3 个
    fun getByIds(ids: List<String>): List<Physique>
    fun aggregatePhysiqueEffects(ids: List<String>): AggregatedPhysiqueEffects
}
```

**完整体质列表**（每个模板 3 阶，名称为"XX之体"形式，加成为修炼速度 + 战斗特殊加成）：

##### 正面体质

| 体质名 | 加成 | 1阶 | 2阶 | 3阶 |
|--------|------|-----|-----|-----|
| 木灵之体 | 修炼速度/减伤 | 8%/3% | 16%/7% | 28%/11% |
| 火灵之体 | 修炼速度/增伤 | 8%/3% | 16%/7% | 28%/11% |
| 水灵之体 | 修炼速度/防御 | 8%/5% | 16%/10% | 28%/16% |
| 风灵之体 | 修炼速度/暴伤 | 8%/5% | 16%/10% | 28%/16% |
| 金刚不坏体 | 修炼速度/减伤/防御 | 5%/5%/3% | 10%/10%/7% | 16%/16%/12% |
| 天魔之体 | 修炼速度/增伤/暴伤 | 5%/5%/5% | 10%/10%/10% | 16%/16%/16% |
| 万古长青体 | 修炼速度/减伤 | 10%/5% | 20%/10% | 32%/16% |
| 先天道体 | 修炼速度/增伤/减伤 | 7%/3%/3% | 14%/7%/7% | 22%/12%/12% |
| 无漏之体 | 修炼速度/减伤/防御 | 7%/3%/5% | 14%/7%/10% | 22%/12%/16% |
| 九阳之体 | 修炼速度/增伤 | 10%/5% | 20%/10% | 32%/16% |
| 玄阴之体 | 修炼速度/减伤/暴伤 | 7%/3%/5% | 14%/7%/10% | 22%/12%/16% |
| 混沌之体 | 修炼速度/增伤/减伤/暴伤/防御 | 4%/3%/3%/3%/3% | 8%/7%/7%/7%/7% | 14%/12%/12%/12%/12% |

##### 负面体质（统一灰色，无品阶区分）

| 体质名 | 加成 | 数值 |
|--------|------|-----|
| 凡俗之体 | 修炼速度 | -12% |
| 破败之体 | 减伤（受更多伤） | -8% |
| 漏丹之体 | 修炼速度/减伤 | -10%/-7% |

**注**：负面体质统一 rarity=0、颜色为灰色 `#9E9E9E`，无品阶区分，单一数值。

#### 2.3 新增 `AffixDatabase`

**文件**：`c:\Mnzm\XianxiaSectNative\android\core\domain\src\main\java\com\xianxia\sect\core\registry\AffixDatabase.kt`（新建）

```kotlin
object AffixDatabase {
    fun generateForDisciple(): List<Affix>  // 0-3 个
    fun getByIds(ids: List<String>): List<Affix>
    fun calculateAffixEffects(ids: List<String>): Map<String, Double>
}
```

**完整词条列表**（每个模板 3 阶，名称为简短修饰词，覆盖所有加成类型）：

##### 基础属性类词条（正面）

| 词条名 | 加成 | 1阶 | 2阶 | 3阶 |
|--------|------|-----|-----|-----|
| 灵慧 | 智力 | 3 | 7 | 12 |
| 清丽 | 魅力 | 3 | 7 | 12 |
| 忠义 | 忠诚 | 3 | 7 | 12 |
| 悟性 | 悟性 | 3 | 7 | 12 |
| 匠心 | 炼器 | 3 | 7 | 12 |
| 药理 | 炼丹 | 3 | 7 | 12 |
| 百草 | 灵植 | 3 | 7 | 12 |
| 寻脉 | 采矿 | 3 | 7 | 12 |
| 教化 | 传道 | 3 | 7 | 12 |
| 厚德 | 德行 | 3 | 7 | 12 |

##### 战斗属性类词条（正面，百分比加成）

| 词条名 | 加成 | 1阶 | 2阶 | 3阶 |
|--------|------|-----|-----|-----|
| 勇毅 | 物攻 | 3% | 7% | 12% |
| 法威 | 法攻 | 3% | 7% | 12% |
| 坚韧 | 物防 | 3% | 7% | 12% |
| 灵台 | 法防 | 3% | 7% | 12% |
| 旺盛 | 气血 | 5% | 10% | 16% |
| 充盈 | 法力 | 5% | 10% | 16% |
| 迅捷 | 速度 | 3% | 7% | 12% |
| 锐利 | 暴击 | 2% | 4% | 7% |

##### 修炼速度类词条（正面）

| 词条名 | 加成 | 1阶 | 2阶 | 3阶 |
|--------|------|-----|-----|-----|
| 悟道 | 修炼速度 | 3% | 7% | 12% |
| 修道 | 修炼速度 | 5% | 10% | 16% |

##### 战斗特殊加成类词条（正面）

| 词条名 | 加成 | 1阶 | 2阶 | 3阶 |
|--------|------|-----|-----|-----|
| 破甲 | 增伤 | 2% | 4% | 7% |
| 护体 | 减伤 | 2% | 4% | 7% |
| 会心 | 暴伤 | 3% | 7% | 12% |
| 铁壁 | 防御 | 3% | 7% | 12% |

##### 寿命类词条（正面）

| 词条名 | 加成 | 1阶 | 2阶 | 3阶 |
|--------|------|-----|-----|-----|
| 长寿 | 寿命 | 8% | 16% | 26% |
| 延年 | 寿命 | 5% | 10% | 16% |

##### 特殊类词条（正面）

| 词条名 | 加成 | 1阶 | 2阶 | 3阶 |
|--------|------|-----|-----|-----|
| 多识 | 功法槽 | 0 | 1 | 2 |
| 百战 | 胜场属性 | 1 | 2 | 3 |

##### 职务类词条（正面，担任职务时增强该职务职能效果）

| 词条名 | 对应职务 | 职能效果加成 | 1阶 | 2阶 | 3阶 |
|--------|---------|------------|-----|-----|-----|
| 勤勉 | 灵田长老 | 灵药成熟速度加成 | 3% | 7% | 12% |
| 精炼 | 炼丹长老 | 炼丹成功率加成 | 3% | 7% | 12% |
| 善造 | 炼器长老 | 炼器成功率加成 | 3% | 7% | 12% |
| 善导 | 外门长老 | 外门弟子突破指导加成 | 3% | 7% | 12% |
| 明道 | 传道长老 | 外门弟子传道修炼速度加成 | 3% | 7% | 12% |
| 威严 | 执法长老 | 叛逃/偷盗捕获率加成 | 3% | 7% | 12% |
| 稳重 | 内门长老 | 内门弟子突破指导加成 | 3% | 7% | 12% |
| 善缘 | 招贤长老 | 招募弟子数上限加成 | 3% | 7% | 12% |
| 弘法 | 青云传道 | 内门弟子传道修炼速度加成 | 3% | 7% | 12% |
| 辅翼 | 副宗主 | 政策效果加成 | 3% | 7% | 12% |

##### 负面词条（统一灰色，无品阶区分）

| 词条名 | 加成 | 数值 |
|--------|------|-----|
| 愚笨 | 智力 | -8 |
| 粗鄙 | 魅力 | -8 |
| 怯弱 | 物攻 | -8% |
| 病弱 | 气血 | -10% |
| 懒散 | 修炼速度 | -8% |
| 短命 | 寿命 | -16% |
| 迟滞 | 速度 | -8% |
| 脆弱 | 减伤（受更多伤） | -5% |

**注**：负面词条统一 rarity=0、颜色为灰色 `#9E9E9E`，无品阶区分，单一数值。

---

### 3. 计算器层（core:engine）

#### 3.1 `DiscipleStatCalculator` 重构

**文件**：[DiscipleStatCalculator.kt](file:///c:/Mnzm/XianxiaSectNative/android/core/engine/src/main/java/com/xianxia/sect/core/domain/disciple/DiscipleStatCalculator.kt)

##### 3.1.1 新增体质/词条效果聚合

```kotlin
fun computePhysiqueEffects(physiqueIds: List<String>): AggregatedPhysiqueEffects
fun computeAffixEffects(affixIds: List<String>): Map<String, Double>
```

```kotlin
data class AggregatedPhysiqueEffects(
    val cultivationSpeedBonus: Double,
    val damageAmplification: Double,
    val damageReduction: Double,
    val critDamageBonus: Double,
    val defenseBonus: Double
)
```

##### 3.1.2 `computeBaseStats()` 变更（L55-L125）

- 天赋百分比加成：保持现有逻辑（BAT_* 进入 `(1+bonus)` 乘区）
- **新增词条加成**：词条中 BAT_* 百分比与天赋同乘区加算；词条中 BASE_* 扁平加成与天赋累加
- **职务加成不在此处生效**：PositionBonus 作用于职务职能效果，不影响弟子自身面板属性

##### 3.1.3 `computeCultivationZones()` 变更（L435-L489）

```kotlin
// 现有
val aptitudeBonus = talentEffects["cultivationSpeed"] ?: 0.0
// 变更后
val aptitudeBonus = (talentEffects["cultivationSpeed"] ?: 0.0) +
                    (affixEffects["cultivationSpeed"] ?: 0.0) +
                    (physiqueEffects.cultivationSpeedBonus)
```

##### 3.1.4 `computeBreakthroughZones()` 变更（L640-L668）

- **移除天赋 `breakthroughChance` 加成**：`selfBonus` 乘区中 `talentBreakthroughBonus` 项删除
- `selfBonus` 乘区保留：`pillBonus + soulPowerBonus + masterDiscipleBonus`

##### 3.1.5 新增 `getPhysiqueDamageZones()` 接口

```kotlin
fun getPhysiqueDamageZones(disciple: Disciple): PhysiqueDamageZones {
    val physiqueEffects = computePhysiqueEffects(disciple.physiqueIds)
    return PhysiqueDamageZones(
        damageAmplification = physiqueEffects.damageAmplification,
        damageReduction = physiqueEffects.damageReduction,
        critDamageBonus = physiqueEffects.critDamageBonus,
        defenseBonus = physiqueEffects.defenseBonus
    )
}
```

##### 3.1.6 职务查询接口

```kotlin
fun getCurrentPosition(disciple: Disciple, elderSlots: ElderSlots): ElderSlotType?
```

---

### 4. 战斗系统（core:engine）

#### 4.1 `BattleCalculator.DamageZones` 扩展

**文件**：[BattleCalculator.kt#L24-L30](file:///c:/Mnzm/XianxiaSectNative/android/core/engine/src/main/java/com/xianxia/sect/core/util/BattleCalculator.kt)

```kotlin
data class DamageZones(
    // buff 乘区（现有，保持不变）
    val attackBuffs: Double = 0.0,
    val defensePenetration: Double = 0.0,
    val critDamageBonus: Double = 0.0,
    val damageAmplification: Double = 0.0,
    val damageReduction: Double = 0.0,
    // 体质乘区（独立乘算因子，不与 buff 同区加算）
    val physiqueDamageAmplification: Double = 0.0,
    val physiqueDamageReduction: Double = 0.0,
    val physiqueCritDamageBonus: Double = 0.0,
    val physiqueDefenseBonus: Double = 0.0
)
```

#### 4.2 `calculateFinalDamage()` 公式扩展（L105-L128）

```kotlin
// 现有
effectiveDefense = defense × (1 - defensePenetration)
reduction = effectiveDefense / (effectiveDefense + DEFENSE_CONSTANT)
preCritDamage = effectiveAttack × skillMultiplier × (1 - reduction) × realmGapMultiplier
critMult = 暴击 ? (1 + CRIT_BASE_MULTIPLIER + critDamageBonus) : 1.0
finalDamage = preCritDamage × critMult × (1 + damageAmplification) × (1 - damageReduction) × variance

// 变更后（体质为独立乘算因子）
effectiveDefense = defense × (1 - defensePenetration) × (1 + physiqueDefenseBonus)
reduction = effectiveDefense / (effectiveDefense + DEFENSE_CONSTANT)
preCritDamage = effectiveAttack × skillMultiplier × (1 - reduction) × realmGapMultiplier
critMult = 暴击 ? (1 + CRIT_BASE_MULTIPLIER + critDamageBonus + physiqueCritDamageBonus) : 1.0
finalDamage = preCritDamage × critMult ×
              (1 + damageAmplification) × (1 - damageReduction) ×       // buff 层
              (1 + physiqueDamageAmplification) × (1 - physiqueDamageReduction) ×  // 体质层（独立乘算）
              variance
```

**乘区规则（体质独立乘算）**：
- 体质增伤：独立乘法因子 `(1 + physiqueDamageAmplification)`，与 buff 的 `(1 + damageAmplification)` 相乘
- 体质减伤：独立乘法因子 `(1 - physiqueDamageReduction)`，与 buff 的 `(1 - damageReduction)` 相乘
- 体质暴伤：与 buff 暴伤同区加算进入暴击伤害乘区
- 体质防御：独立乘法因子 `(1 + physiqueDefenseBonus)`，作用于 `effectiveDefense`

#### 4.3 `buildDamageZones()` 变更（L72）

`Combatant` 构建时从 `DiscipleStatCalculator.getPhysiqueDamageZones()` 注入体质加成。

---

### 5. 持久化与迁移

#### 5.1 Room 数据库迁移

新增 Migration：
- `disciples_extended` 表新增 `physiqueIds` (TEXT, JSON 序列化) 与 `affixIds` (TEXT, JSON 序列化) 列
- 旧 `talentIds` 列保留不变；旧弟子 `physiqueIds` 默认 empty、`affixIds` 默认 empty

#### 5.2 `DiscipleFactory` 变更

**文件**：[DiscipleFactory.kt#L116-L117](file:///c:/Mnzm/XianxiaSectNative/android/core/engine/src/main/java/com/xianxia/sect/core/domain/disciple/DiscipleFactory.kt)

```kotlin
val talentIds = TalentDatabase.generateTalentsForDisciple().map { it.id }       // 0-3 个
val physiqueIds = PhysiqueDatabase.generateForDisciple().map { it.id }           // 0-3 个
val affixIds = AffixDatabase.generateForDisciple().map { it.id }                 // 0-3 个
```

寿命计算（L170-L177）改为从词条效果中读取 `lifespan`。

---

### 6. 职务加成生效点

职务加成（PositionBonus）作用于**职务的职能效果**，不作用于弟子自身属性。需在各职务职能效果的计算点插入加成。

#### 6.1 职务查询接口

新增辅助方法查询担任职务的弟子是否拥有对应 PositionBonus：

```kotlin
// 查询弟子当前职务
fun getCurrentPosition(discipleId: String, elderSlots: ElderSlots): ElderSlotType?
// 查询弟子拥有的 PositionBonus 加成总和（天赋 + 词条，同职务累加）
fun getPositionEffectBonus(disciple: Disciple, slotType: ElderSlotType): Double
```

#### 6.2 各职务职能效果接入点

| 职务 | 接入文件 | 接入函数 | 变更方式 |
|------|---------|---------|---------|
| 招贤长老 | [RecruitService.kt](file:///c:/Mnzm/XianxiaSectNative/android/core/engine/src/main/java/com/xianxia/sect/core/service/RecruitService.kt) | `calcRecruitBonusCap` (L252-258) | `bonusCap × (1 + positionBonus)` |
| 灵田长老 | [HerbGardenAuraService.kt](file:///c:/Mnzm/XianxiaSectNative/android/core/engine/src/main/java/com/xianxia/sect/core/domain/building/HerbGardenAuraService.kt) | `calculateElderMaturityBonus` (L11-22) | `elderBonus × (1 + positionBonus)` |
| 炼丹长老 | [FormulaService.kt](file:///c:/Mnzm/XianxiaSectNative/android/core/engine/src/main/java/com/xianxia/sect/core/service/FormulaService.kt) | `calculateElderAndDisciplesBonus` (L339-351) + `getElderPositionBonus` (L284-288) | `successBonus × (1 + positionBonus)` + `speedBonus × (1 + positionBonus)` |
| 炼器长老 | [FormulaService.kt](file:///c:/Mnzm/XianxiaSectNative/android/core/engine/src/main/java/com/xianxia/sect/core/service/FormulaService.kt) | `calculateElderAndDisciplesBonus` (L352-364) + `getElderPositionBonus` (L278-283) | 同炼丹长老 |
| 传道长老 | [CultivationRateCalculator.kt](file:///c:/Mnzm/XianxiaSectNative/android/core/engine/src/main/java/com/xianxia/sect/core/service/CultivationRateCalculator.kt) | `calculatePreachingBonuses.elderBonus` (L148-155) | `elderBonus × (1 + positionBonus)` |
| 外门长老 | [DiscipleStatCalculator.kt](file:///c:/Mnzm/XianxiaSectNative/android/core/engine/src/main/java/com/xianxia/sect/core/domain/disciple/DiscipleStatCalculator.kt) | `elderBreakthroughBonus` (L742-752) | `outerElderBonus × (1 + positionBonus)` |
| 内门长老 | [DiscipleStatCalculator.kt](file:///c:/Mnzm/XianxiaSectNative/android/core/engine/src/main/java/com/xianxia/sect/core/domain/disciple/DiscipleStatCalculator.kt) | `elderBreakthroughBonus` (L742-752) | `innerElderBonus × (1 + positionBonus)` |
| 执法长老 | [LawEnforcementProcessor.kt](file:///c:/Mnzm/XianxiaSectNative/android/core/engine/src/main/java/com/xianxia/sect/core/service/LawEnforcementProcessor.kt) | `calculateCaptureRate` (L110-138) | `captureRate × (1 + positionBonus)` |
| 青云传道 | [CultivationRateCalculator.kt](file:///c:/Mnzm/XianxiaSectNative/android/core/engine/src/main/java/com/xianxia/sect/core/service/CultivationRateCalculator.kt) | `calculatePreachingBonuses.elderBonus` (L148-155, 172-173) | `qingyunElderBonus × (1 + positionBonus)` |
| 副宗主 | [SectPolicyToggleUseCase.kt](file:///c:/Mnzm/XianxiaSectNative/android/core/engine/src/main/java/com/xianxia/sect/core/usecase/SectPolicyToggleUseCase.kt) | `getViceSectMasterIntelligenceBonus` (L249-254) | `bonus × (1 + positionBonus)` |

**注**：副宗主当前加成仅在 UI 显示未实际生效（引擎层未接入）。执行时需先将副宗主加成接入引擎层（`FormulaService`/`CultivationRateCalculator`），再应用 PositionBonus。

#### 6.3 职务变更触发

职务分配/解除时（`DiscipleSlotManager`）已有重算机制（`ElderManagementUseCase` 中 productionElderTypes 触发 `checkpointAllProduction`，cultivationElderTypes 触发 `checkpointAllDisciples`），无需额外修改。

---

### 7. UI 层（feature:game）

#### 7.1 品阶颜色体系统一

**文件**：[Color.kt#L77-81](file:///c:/Mnzm/XianxiaSectNative/android/core/ui/src/main/java/com/xianxia/sect/ui/theme/Color.kt)

现有天赋颜色定义（`TalentGradeLow/Mid/High` + `TalentNegative`）已被天赋/体质/词条三类共用。变更：

```kotlin
// 现有（保留，语义泛化为"品阶颜色"）
val TalentGradeLow = Color(0xFF4CAF50)     // 1阶 下品 绿色
val TalentGradeMid = Color(0xFF2196F3)     // 2阶 中品 蓝色
val TalentGradeHigh = Color(0xFFE74C3C)    // 3阶 上品 红色
val TalentNegative = Color(0xFF9E9E9E)     // 负面 灰色（统一）
```

**颜色映射函数**：[DiscipleComponents.kt#L595-600](file:///c:/Mnzm/XianxiaSectNative/android/core/engine/src/main/java/com/xianxia/sect/ui/components/DiscipleComponents.kt) `getTalentRarityColor(rarity)` 已支持 1/2/3 + else(负面)，三类共用，无需修改。

**模型层字符串颜色**：[Disciple.kt](file:///c:/Mnzm/XianxiaSectNative/android/core/domain/src/main/java/com/xianxia/sect/core/model/Disciple.kt) 中 `Talent.color`/`rarityName` 逻辑。`Physique`/`Affix` 新增相同的 `color`/`rarityName` 计算属性：

```kotlin
// Physique / Affix 均添加
val color: String get() = when {
    isNegative -> "#9E9E9E"
    rarity == 1 -> "#4CAF50"
    rarity == 2 -> "#2196F3"
    rarity == 3 -> "#E74C3C"
    else -> "#4CAF50"
}
val rarityName: String get() = when (rarity) {
    1 -> "下品"
    2 -> "中品"
    3 -> "上品"
    else -> "下品"
}
```

#### 7.2 弟子卡片移除天赋显示

**文件**：[DiscipleComponents.kt#L280-306](file:///c:/Mnzm/XianxiaSectNative/android/core/engine/src/main/java/com/xianxia/sect/ui/components/DiscipleComponents.kt)

`PortraitDiscipleCard` 中第 4-5 行的天赋文本块（`talentRows`/`displayRows` 逻辑）**整体删除**。卡片右栏仅保留 3 行信息（性别年龄/境界灵根/悟性忠诚）。

同时移除 `talents` 数据获取（L140-142 `TalentDatabase.getTalentsByIds`）。

**影响范围**：所有复用 `PortraitDiscipleCard` 的 14+ 处弟子列表/选择对话框均自动不再显示天赋。

#### 7.3 详情页天赋/体质/词条区块

**文件**：[DetailActionButtons.kt#L28-85](file:///c:/Mnzm/XianxiaSectNative/android/core/engine/src/main/java/com/xianxia/sect/ui/game/components/detail/DetailActionButtons.kt)

现有 `TalentsSection` 重构为三个区块，按顺序纵向排列：

```
TalentsSection（天赋）       ← 现有，保留
PhysiquesSection（体质）     ← 新增，位于天赋下方
AffixesSection（词条）       ← 新增，位于体质下方
```

##### 7.3.1 通用区块组件抽取

现有 `TalentsSection` 内的"带框 Box + 动态列数 + 单击"逻辑抽取为通用 Composable：

```kotlin
@Composable
fun AttributeChipSection(
    title: String,
    items: List<AttributeChipItem>,
    onChipClick: (AttributeChipItem) -> Unit,
    onChipLongClick: (AttributeChipItem) -> Unit   // 新增长按
)

data class AttributeChipItem(
    val id: String,
    val name: String,
    val rarity: Int,
    val isNegative: Boolean,
    val payload: Any    // Talent/Physique/Affix 实例，供详情对话框使用
)
```

- 动态列数：复用现有 `screenWidthDp / 80` 逻辑
- 边框颜色：`getTalentRarityColor(rarity)` 三类共用
- **长按交互**：使用 `Modifier.combinedClickable(onClick = ..., onLongClick = ...)` 触发详情对话框（替代现有单击触发）

##### 7.3.2 三个区块实现

```kotlin
// 天赋区块（现有 TalentsSection 改造）
AttributeChipSection(
    title = "天赋",
    items = talents.map { it.toChipItem() },
    onChipClick = { selectedDetail = it.payload },
    onChipLongClick = { selectedDetail = it.payload }
)

// 体质区块（新增，位于天赋下方）
AttributeChipSection(
    title = "体质",
    items = physiques.map { it.toChipItem() },
    onChipClick = { selectedDetail = it.payload },
    onChipLongClick = { selectedDetail = it.payload }
)

// 词条区块（新增，位于体质下方）
AttributeChipSection(
    title = "词条",
    items = affixes.map { it.toChipItem() },
    onChipClick = { selectedDetail = it.payload },
    onChipLongClick = { selectedDetail = it.payload }
)
```

#### 7.4 详情对话框复用

**文件**：[DiscipleComponents.kt#L602-646](file:///c:/Mnzm/XianxiaSectNative/android/core/engine/src/main/java/com/xianxia/sect/ui/components/DiscipleComponents.kt)

现有 `TalentDetailDialog` 重构为通用详情对话框，支持 Talent/Physique/Affix 三种类型：

```kotlin
@Composable
fun AttributeDetailDialog(
    item: Any,              // Talent | Physique | Affix
    onDismiss: () -> Unit
)
```

内部根据类型分发渲染：
- **Talent**：显示 `effects` 列表（复用 `formatTalentEffectText`）
- **Physique**：显示 5 个固定字段（修炼速度/增伤/减伤/暴伤/防御），使用中文描述
- **Affix**：显示 `effects` 列表（复用 `formatTalentEffectText`），若有 `positionBonus` 则追加职务加成描述

标题颜色：`getTalentRarityColor(item.rarity)` 三类共用。

#### 7.5 详情页调用链更新

**文件**：[DiscipleDetailScreen.kt](file:///c:/Mnzm/XianxiaSectNative/android/core/engine/src/main/java/com/xianxia/sect/ui/game/DiscipleDetailScreen.kt)

```kotlin
// 现有
var selectedTalent by remember { mutableStateOf<Talent?>(null) }
TalentsSection(talents, disciple.statusData, onTalentClick = { selectedTalent = it })
selectedTalent?.let { TalentDetailDialog(...) }

// 变更后
var selectedDetail by remember { mutableStateOf<Any?>(null) }   // Talent | Physique | Affix
TalentsSection(talents, onChipClick = { selectedDetail = it }, onChipLongClick = { selectedDetail = it })
PhysiquesSection(physiques, onChipClick = { selectedDetail = it }, onChipLongClick = { selectedDetail = it })
AffixesSection(affixes, onChipClick = { selectedDetail = it }, onChipLongClick = { selectedDetail = it })
selectedDetail?.let { AttributeDetailDialog(it) { selectedDetail = null } }
```

数据获取：
```kotlin
val talents = remember(disciple.talentIds) { TalentDatabase.getTalentsByIds(disciple.talentIds) }
val physiques = remember(disciple.physiqueIds) { PhysiqueDatabase.getByIds(disciple.physiqueIds) }
val affixes = remember(disciple.affixIds) { AffixDatabase.getByIds(disciple.affixIds) }
```

---

## Assumptions & Decisions

### 已确认决策
1. **职务加成**：持有职务天赋/词条的弟子担任职务时，增强该职务的职能效果（不作用于弟子自身属性）
2. **战斗属性归属**：BAT_* 仅属天赋/词条，体质不含面板战斗属性
3. **体质战斗加成独立乘算**：体质伤害加成/减免/减伤/防御为独立乘法因子
4. **词条为全能加成池**：覆盖基础属性/战斗属性/职务/战斗伤害特殊/修炼速度 所有加成类型
5. **稀有度**：三类均 3 阶（1-3），均含负面加成；负面无品阶，统一灰色 `#9E9E9E`
6. **数量**：三类均 0-3 个
7. **旧存档**：保留 talentIds，physiqueIds/affixIds 为空；旧天赋定义保留不删除
8. **LIFESPAN 归入词条**，**BREAK_CHANCE 直接移除**
9. **副宗主现有bug**：副宗主加成当前仅在UI显示未实际生效，执行时需先修复接入引擎层
10. **UI 显示**：体质/词条位于天赋下方；三类均根据内容区域动态调整行列；所有弟子卡片不再显示天赋；体质/词条长按弹出详情（复用天赋设计）；品阶颜色三类一致，负面统一灰色

---

## Verification Steps

1. **单元测试**：
   - `DiscipleStatCalculator` 三类效果聚合正确（天赋 + 体质 + 词条 + 职务）
   - `BattleCalculator` 体质伤害独立乘算正确
   - `PhysiqueDatabase`/`AffixDatabase` 生成数量与稀有度分布符合配置

2. **集成测试**：
   - 弟子创建后三类 ID 正确持久化到 `DiscipleExtended`
   - 弟子担任职务/卸任职务时 base stats 正确变化
   - 战斗结算中体质伤害加成正确生效

3. **回归测试**：
   - 现有修炼速度计算（[CultivationRateCalculator](file:///c:/Mnzm/XianxiaSectNative/android/core/engine/src/main/java/com/xianxia/sect/core/service/CultivationRateCalculator.kt)）不受破坏
   - 突破概率计算（[DiscipleBreakthroughHandler](file:///c:/Mnzm/XianxiaSectNative/android/core/engine/src/main/java/com/xianxia/sect/core/service/DiscipleBreakthroughHandler.kt)）不受破坏（移除 breakthroughChance 后）
   - 数据库迁移后旧存档可正常加载
