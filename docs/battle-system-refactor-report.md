# 战斗系统重构报告

> 提交日期：2026-04-18
> 分支：main
> 提交：44b4f81, d27d232
> 修改文件：29个

---

## 1. 改造背景

原始战斗系统存在两套并行且互不兼容的引擎（PVE引擎 BattleSystem + PVP引擎 AISectAttackManager），导致代码重复约400行，且存在关键逻辑差异（闪避、Buff、技能系统、伤害公式不一致）。同时伤害公式存在数学缺陷，妖兽行为单调，灵根五行系统在战斗中完全无效。

---

## 2. 方案实施清单

### 2.1 方案一：统一战斗引擎 ✅

**目标**：消除双引擎代码重复，建立单一战斗核心。

**实现内容**：
- 将 `AICombatant`/`AICombatSkill`/`AICombatBuff` 合并到 `Combatant`/`CombatSkill`/`CombatBuff` 体系
- 使用 `CombatantSide`（ATTACKER/DEFENDER）替代 `isAttacker: Boolean` 和 `CombatantType`
- `AISectAttackManager.executeAIBattleTurn()` 复用 `BattleSystem` 的伤害计算逻辑
- 统一Buff系统（乘法叠加，物攻/法攻独立计算）
- 修复PVP引擎中 `effectiveCritRate` 计算错误（原使用 `speedBonus * 0.01`）
- 修复PVP引擎中物攻Buff同时增加法攻的Bug

**收益**：减少约400行重复代码，消除逻辑不一致，PVP战斗体验与PVE一致。

---

### 2.2 方案二：改进伤害公式 ✅

**原公式**：
```
damage = (attack * multiplier * critMultiplier - defense) * variance
```

**新公式**：
```
baseDamage = attack * multiplier * critMultiplier
reduction = defense / (defense + 500)
finalDamage = baseDamage * (1 - reduction) * critMultiplier * realmGapMultiplier * elementMultiplier * variance
```

**关键改进**：
- 防御采用百分比减伤（`defense/(defense+500)`），高防始终有意义但收益递减
- 暴击影响最终伤害倍率（1.5x），而非攻击力倍率
- 伤害下限为1，上限为 `Int.MAX_VALUE/2`
- 伤害波动范围 90%-110%

**配置项**（GameConfig.Battle）：
| 常量 | 值 | 说明 |
|---|---|---|
| DEFENSE_CONSTANT | 500.0 | 防御收益曲线控制 |
| CRIT_MULTIPLIER | 1.5 | 暴击倍率（从2.0降低） |
| DAMAGE_VARIANCE_MIN | 0.9 | 伤害波动下限 |
| DAMAGE_VARIANCE_MAX | 1.1 | 伤害波动上限 |
| MIN_DAMAGE | 1 | 最低伤害 |

---

### 2.3 方案三：五行克制系统 ✅

**克制关系**：
```
金 → 克木 → 克土 → 克水 → 克火 → 克金
```

**效果**：
- 克制目标：伤害 +30%
- 被克目标：伤害 -20%
- 同属性/无关系：无加成

**元素分配**：
- 弟子：基于灵根类型（`SpiritRoot.types.firstOrNull()`）
- 妖兽：基于类型配置（虎妖→金、蛇妖→水等）

**配置项**（GameConfig.Battle.Element）：
| 常量 | 值 | 说明 |
|---|---|---|
| ADVANTAGE_MULTIPLIER | 1.3 | 克制伤害倍率 |
| DISADVANTAGE_MULTIPLIER | 0.8 | 被克伤害倍率 |
| ADVANTAGES | Map | 克制关系映射 |

---

### 2.4 方案四：妖兽技能系统 ✅

每种妖兽类型配置1-2个专属技能：

| 妖兽 | 元素 | 技能1 | 技能2 |
|---|---|---|---|
| 虎妖（狂暴） | 金 | 猛虎下山（1.8x物攻，3回合CD） | 咆哮（全队物攻+20%，5回合CD） |
| 狼妖（迅捷） | 木 | 狼群撕咬（1.5x物攻×2hit，2回合CD） | — |
| 蛇妖（剧毒） | 水 | 毒牙（1.2x物攻+中毒3回合，2回合CD） | — |
| 熊妖（铁甲） | 土 | 震地（1.5x物攻AOE，4回合CD） | 铁壁（自身物防+40%，5回合CD） |
| 鹰妖（神风） | 金 | 俯冲（2.0x物攻，3回合CD） | — |
| 狐妖（幻魅） | 火 | 妖术（1.5x法攻+沉默1回合，3回合CD） | 幻影（全队速度+30%，5回合CD） |
| 龙妖（远古） | 火 | 龙息（1.8x法攻AOE，4回合CD） | 龙威（全队攻击+25%，6回合CD） |
| 龟妖（玄甲） | 水 | 缩壳（自身减伤50%，4回合CD） | 水盾（全队法防+30%，5回合CD） |

**数据结构**（GameConfig）：
```kotlin
data class BeastSkillConfig(
    val name: String,
    val damageMultiplier: Double,
    val cooldown: Int,
    val mpCost: Int,
    val skillType: SkillType,
    val damageType: DamageType,
    val hits: Int = 1,
    val isAoe: Boolean = false,
    val buffType: BuffType? = null,
    val buffValue: Double = 0.0,
    val buffDuration: Int = 0,
    val targetScope: String = "enemy"
)
```

---

### 2.5 方案六：智能技能AI ✅

**决策优先级**：
1. 紧急治疗：自身或队友HP < 30%时，优先使用治疗技能（80%概率）
2. AOE时机：存活敌人 ≥ 3时使用AOE技能（70%概率）
3. 伤害效率：比较可用技能的MP/伤害比，选择效率最高的
4. 保留MP：MP < 30%时降低技能使用频率，仅使用廉价技能

**目标选择策略**：
1. 低血优先：HP < 30%的目标（70%概率）
2. 五行克制优先：选择元素被克制的目标（30%概率）
3. 随机选择

---

### 2.6 方案八：性能优化 ✅

**优化措施**：
- 使用 `withIndex().associate { it.value.id to it.index }` 构建ID→索引映射，替代 `indexOfFirst {}` 线性查找
- 战斗中Combatant使用可变列表（`toMutableList()`），避免每回合全量重建
- Buff更新仅处理活跃Buff（`filter { it.remainingDuration > 0 }`）

---

### 2.7 新增：跨境界伤害衰减/增加机制 ✅

**规则**：
- 低境界攻击高境界：每级伤害 +15%
- 高境界攻击低境界：每级伤害 -12%
- 最大计算差距：5级
- 伤害倍率范围：10% - 300%

**公式**：
```kotlin
fun calculateRealmGapMultiplier(attackerRealm: Int, defenderRealm: Int): Double {
    val gap = attackerRealm - defenderRealm
    val absGap = kotlin.math.abs(gap).coerceAtMost(5)
    if (absGap == 0) return 1.0
    
    val ratio = if (gap < 0) {
        1.0 + absGap * 0.15  // 低境界攻击高境界
    } else {
        1.0 - absGap * 0.12  // 高境界攻击低境界
    }
    
    return ratio.coerceIn(0.1, 3.0)
}
```

---

## 3. 数据模型变更

### 3.1 新增枚举（移至 GameConfig）

| 枚举 | 值 | 说明 |
|---|---|---|
| SkillType | ATTACK, SUPPORT | 技能类型 |
| DamageType | PHYSICAL, MAGIC | 伤害类型 |
| HealType | HP, MP | 治疗类型 |
| CombatantSide | ATTACKER, DEFENDER | 战斗方 |
| BuffType | 20种（见下方） | Buff/Debuff类型 |

### 3.2 BuffType 完整列表

**增益（8种）**：
HP_BOOST, MP_BOOST, SPEED_BOOST, PHYSICAL_ATTACK_BOOST, MAGIC_ATTACK_BOOST, PHYSICAL_DEFENSE_BOOST, MAGIC_DEFENSE_BOOST, CRIT_RATE_BOOST

**减益（6种）**：
PHYSICAL_ATTACK_REDUCE, MAGIC_ATTACK_REDUCE, PHYSICAL_DEFENSE_REDUCE, MAGIC_DEFENSE_REDUCE, SPEED_REDUCE, CRIT_RATE_REDUCE

**持续伤害（2种）**：
POISON, BURN

**控制效果（4种）**：
STUN, FREEZE, SILENCE, TAUNT

### 3.3 Combatant 新增字段

| 字段 | 类型 | 说明 |
|---|---|---|
| side | CombatantSide | 战斗方（替代type） |
| element | String | 五行元素 |
| buffs | List<CombatBuff> | Buff列表 |

### 3.4 CombatBuff

```kotlin
data class CombatBuff(
    val type: BuffType,
    val value: Double,        // 百分比值，如0.2表示20%
    var remainingDuration: Int // 剩余回合数
)
```

### 3.5 CombatSkill 新增字段

| 字段 | 类型 | 说明 |
|---|---|---|
| skillType | SkillType | 技能类型（攻击/辅助） |
| hits | Int | 连击次数 |
| healPercent | Double | 治疗百分比 |
| healType | HealType | 治疗类型 |
| buffType | BuffType? | 附加Buff类型 |
| buffValue | Double | Buff值 |
| buffDuration | Int | Buff持续回合 |
| buffs | List<Triple<BuffType, Double, Int>> | 多Buff支持 |
| isAoe | Boolean | 是否AOE |
| targetScope | String | 目标范围（self/team/enemy） |

---

## 4. 兼容性说明

### 4.1 废弃标记

```kotlin
@Deprecated("Use CombatantSide instead", replaceWith = ReplaceWith("CombatantSide"))
enum class CombatantType {
    DISCIPLE, BEAST
}
```

### 4.2 向后兼容

- BeastTypeConfig 新增 `element` 和 `skills` 字段，均有默认值，旧代码无需修改
- `AIBattleResult` 接口（winner/deadAttackerIds/deadDefenderIds/canOccupy）保持不变
- CultivationService 调用方式无需修改

---

## 5. 代码复查与修复

### 5.1 复查发现的问题

| 严重性 | 问题 | 状态 |
|---|---|---|
| P0 | UnifiedAIBattle缺失闪避机制 | ✅ 已修复 |
| P0 | UnifiedAIBattle缺失buff/debuff结算 | ✅ 已修复 |
| P0 | UnifiedAIBattle缺失DOT（持续伤害）结算 | ✅ 已修复 |
| P0 | UnifiedAIBattle缺失辅助技能执行 | ✅ 已修复 |
| P0 | UnifiedAIBattle缺失AOE攻击逻辑 | ✅ 已修复 |
| P0 | CaveExplorationSystem弟子/守护兽缺失element字段 | ✅ 已修复 |
| P0 | AISectAttackManager.convertToCombatant缺失element字段 | ✅ 已修复 |

### 5.2 修复后状态

- 主代码编译：0错误
- PVP战斗引擎现已包含完整的闪避/Buff/Debuff/DOT/辅助技能/AOE/技能冷却递减机制
- 五行克制系统在所有战斗类型中生效（PVE洞府、PVP宗门战、AI战斗）

---

## 6. 测试覆盖

### 6.1 BattleSystemTest 新增用例

- Combatant buff/debuff属性计算（effectivePhysicalAttack、effectiveSpeed等）
- 跨境界伤害倍率计算（同境界、低攻高、高攻低）
- 五行克制倍率计算（克制、被克、中立）
- BuffType枚举完整性验证
- 控制效果检测（hasControlEffect）
- 多弟子多妖兽战斗执行

### 6.2 BattleCalculatorTest 新增用例

- 百分比减伤公式验证
- 防御百分比收益递减验证
- 五行克制/跨境界计算验证
- 伤害波动范围验证

---

## 7. 修改文件清单

### 核心改造（5个）
1. `BattleSystem.kt` — 统一Combatant模型、新伤害公式、五行克制、妖兽技能、智能AI、跨境界、DOT结算
2. `BattleCalculator.kt` — 统一伤害公式、新增五行/跨境界计算
3. `GameConfig.kt` — 新增枚举（SkillType/DamageType/BuffType/HealType/CombatantSide）、五行配置、跨境界配置、妖兽技能配置、BeastSkillConfig
4. `AISectAttackManager.kt` — 统一到Combatant体系，补全闪避/Buff/DOT/辅助技能/AOE/技能冷却
5. `CaveExplorationSystem.kt` — 适配新模型（CombatantSide替代CombatantType），添加element字段

### 适配更新（24个）
- Items.kt — 枚举引用路径更新
- ItemDetailDialog.kt — 枚举引用路径更新 + 修复预先存在的编译错误（PillCategory/DiscipleStatCalculatorTest）
- DiscipleDetailScreen.kt — 枚举引用路径更新
- DiscipleStatCalculatorTest.kt — PillEffects类型修复
- SaveDataConverterTest.kt — PillEffects类型修复
- BoundaryAndEdgeCaseTest.kt — PillEffects类型修复
- BattleSystemTest.kt — 重写测试用例
- BattleCalculatorTest.kt — 更新测试期望值

---

## 8. 未包含内容

| 方案 | 原因 |
|---|---|
| 方案五：扩展Buff/Debuff系统 | 已包含在方案一实现中（BuffType已扩展至20种） |
| 方案七：战斗奖励丰富化 | 用户指出秘境探索已有奖励，无需额外添加 |

---

## 9. 关键指标

| 指标 | 改造前 | 改造后 |
|---|---|---|
| 战斗引擎数量 | 2套（PVE + PVP） | 1套（统一） |
| BuffType数量 | 8种 | 20种 |
| 妖兽技能 | 0 | 8种×（1-2个技能） |
| 五行克制 | 无 | 金木水火土循环克制 |
| 跨境界机制 | 无 | ±15%/12%每级 |
| 智能AI | 仅低血+随机 | 6层优先级决策 |
| 伤害公式缺陷 | 防御无效化、无上限 | 百分比减伤、收益递减 |
