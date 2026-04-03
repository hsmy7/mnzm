# 修复弟子突破筑基失败问题

## Why
用户反馈弟子无法突破筑基。经代码分析，发现炼气期弟子在尝试突破筑基时，雷劫伤害过高导致弟子HP不足以承受，从而突破失败。

## 问题分析

### 根本原因
炼气10层弟子的属性不足以承受筑基雷劫的伤害：

**筑基雷劫伤害计算**（`TribulationSystem.createThunderTribulation`）：
- 筑基境界 multiplier = 1.5
- 物理攻击 = 50 × 1.5 × 0.6 = 45
- 法术攻击 = 50 × 1.5 × 0.6 = 45
- 每回合伤害 = max(1, 45 - 物防) + max(1, 45 - 法防)
- 3回合总伤害 ≈ 228

**炼气10层弟子属性**（`Disciple.getBaseStats`）：
- baseHp = 100, basePhysicalDefense = 5, baseMagicDefense = 3
- realmMultiplier = 1.0, layerBonus = 1.9
- maxHp ≈ 190, physicalDefense ≈ 9, magicDefense ≈ 5
- 每回合承受伤害 = (45-9) + (45-5) = 76
- 3回合总承受伤害 = 228

**结论**：190 HP < 228 伤害，弟子必然在雷劫中失败

### 次要问题
心魔试炼要求 soulPower >= 20，但弟子默认 soulPower = 10，如果弟子没有获得足够的神魂值，也会失败。

## What Changes
- **调整筑基雷劫伤害计算**：降低筑基雷劫的基础伤害系数，使炼气期弟子有合理的突破成功率
- **可选**：调整炼气期弟子的基础属性成长

## Impact
- Affected code: `TribulationSystem.kt`, 可能涉及 `GameConfig.kt`
- Affected systems: 弟子突破系统、游戏平衡性

## ADDED Requirements

### Requirement: 筑基雷劫平衡性调整
系统应当确保炼气10层弟子有合理的突破筑基成功率（基础成功率约50%-70%）。

#### Scenario: 炼气10层弟子突破筑基
- **GIVEN** 炼气10层弟子，修为已满
- **WHEN** 弟子尝试突破筑基
- **THEN** 弟子应当有合理的概率成功渡过雷劫（不因HP不足而必然失败）

#### Scenario: 心魔试炼
- **GIVEN** 炼气10层弟子，修为已满
- **WHEN** 弟子尝试突破筑基
- **THEN** 如果弟子 soulPower < 20，应当提示玩家神魂不足，而非直接失败

## MODIFIED Requirements
无

## REMOVED Requirements
无
