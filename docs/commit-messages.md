# 战斗系统重构 - 提交说明

## 提交 1: 44b4f81 - 核心重构

### 提交信息
```
refactor: 统一战斗引擎 + 改进伤害公式 + 五行克制 + 妖兽技能 + 智能AI + 跨境界机制

- 方案一：统一战斗引擎，合并AICombatant/AICombatSkill/AICombatBuff到Combatant体系
- 方案二：改进伤害公式为百分比减伤(defense/(defense+500))，暴击影响最终伤害
- 方案三：引入五行克制系统(金克木克土克水克火克金)，灵根决定元素属性
- 方案四：8种妖兽专属技能(虎妖猛虎下山/咆哮、狼妖狼群撕咬、蛇妖毒牙、熊妖震地/铁壁、鹰妖俯冲、狐妖妖术、龙妖龙息/龙威、龟妖缩壳/水盾)
- 方案六：智能技能选择AI(紧急治疗>控制优先>AOE时机>伤害效率>保留MP)
- 方案八：性能优化(索引映射替代线性查找)
- 新增：跨境界伤害衰减/增加机制(每级±15%/12%，上限5级)
- 将共享枚举(SkillType/DamageType/BuffType/HealType/CombatantSide)移至GameConfig
- BuffType扩展至20种(新增减益/持续伤害/控制效果)
- 修复AISectAttackManager中物攻Buff同时增加法攻的Bug
- 更新BattleCalculator与BattleSystem使用统一伤害公式
- 修复ItemDetailDialog.kt中预先存在的编译错误
```

### 涉及文件（29个）

#### 核心改造
| 文件 | 变更说明 |
|---|---|
| `BattleSystem.kt` | 统一Combatant模型、新伤害公式、五行克制、妖兽技能、智能AI、跨境界、DOT结算 |
| `BattleCalculator.kt` | 统一伤害公式、新增五行/跨境界计算 |
| `GameConfig.kt` | 新增枚举、五行配置、跨境界配置、妖兽技能配置 |
| `AISectAttackManager.kt` | 统一到Combatant体系 |
| `CaveExplorationSystem.kt` | 适配新模型、添加element字段 |

#### 适配更新
| 文件 | 变更说明 |
|---|---|
| `Items.kt` | 枚举引用路径更新 |
| `ItemDetailDialog.kt` | 枚举引用更新 + 修复编译错误 |
| `DiscipleDetailScreen.kt` | 枚举引用路径更新 |
| `BattleSystemTest.kt` | 重写测试用例 |
| `BattleCalculatorTest.kt` | 更新测试期望值 |
| `DiscipleStatCalculatorTest.kt` | PillEffects类型修复 |
| `SaveDataConverterTest.kt` | PillEffects类型修复 |
| `BoundaryAndEdgeCaseTest.kt` | PillEffects类型修复 |

---

## 提交 2: d27d232 - P0问题修复

### 提交信息
```
fix: 修复代码复查发现的P0问题

- UnifiedAIBattle补充闪避/buff/debuff/DOT/辅助技能/AOE/技能冷却递减
- CaveExplorationSystem弟子/守护兽添加element字段
- AISectAttackManager添加HealType import
- AI洞府弟子使用默认metal元素
```

### 涉及文件（2个）
| 文件 | 变更说明 |
|---|---|
| `AISectAttackManager.kt` | UnifiedAIBattle重构，补全PVP战斗机制 |
| `CaveExplorationSystem.kt` | 添加element字段 |
