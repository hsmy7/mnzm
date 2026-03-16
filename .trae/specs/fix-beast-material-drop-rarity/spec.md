# 修复击败妖兽只掉落凡品材料的问题

## Why
在探索秘境战斗中，击败妖兽后掉落的材料品阶始终是凡品，与探索队伍的实际境界无关。这是因为 `BattleSystem.executeBattle` 方法中创建 `BattleMemberData` 时，`realm` 字段被硬编码为 `9`，导致后续计算材料掉落品阶时，队伍平均境界总是 9.0（炼气期），从而只掉落凡品材料。

## What Changes
- 修复 `BattleSystem.executeBattle` 方法中 `BattleMemberData` 的 `realm` 字段，从硬编码 `9` 改为从 `combatant.realm` 获取
- 同步修复 `realmName` 字段，从空字符串改为从 `combatant.realmName` 获取

## Impact
- Affected specs: 秘境探索系统、妖兽材料掉落系统
- Affected code: `BattleSystem.kt` 中的 `executeBattle` 方法

## 问题分析

### 问题代码位置
文件：`BattleSystem.kt` 第 130-140 行

```kotlin
val teamMembers = battle.team.map { combatant ->
    BattleMemberData(
        id = combatant.id,
        name = combatant.name,
        realm = 9,                    // 问题：硬编码为 9
        realmName = "",               // 问题：硬编码为空字符串
        hp = combatant.hp,
        maxHp = combatant.maxHp,
        isAlive = true
    )
}.toMutableList()
```

### 问题根源
`Combatant` 数据类已经包含 `realm` 和 `realmName` 字段（默认值分别为 9 和空字符串），但在创建 `BattleMemberData` 时没有使用这些字段，而是直接硬编码。

### 影响链路
1. `BattleSystem.executeBattle` 创建 `BattleMemberData` 时 realm 硬编码为 9
2. 战斗结果 `battleResult.log.teamMembers` 中所有成员的 realm 都是 9
3. `GameEngine.kt` 第 3972 行计算平均境界：`val avgRealm = if (aliveMembers.isNotEmpty()) aliveMembers.map { it.realm }.average() else 9.0`
4. 平均境界总是 9.0 左右
5. `getRarityRangeByRealm(9.0)` 返回 `Pair(1, 1)`（凡品）
6. 材料掉落始终是凡品

## ADDED Requirements

### Requirement: 战斗日志正确记录队伍成员境界
系统 SHALL 在战斗日志中正确记录队伍成员的实际境界值。

#### Scenario: 高境界队伍击败妖兽
- **WHEN** 化神期（realm=5）弟子组成的队伍击败妖兽
- **THEN** 战斗日志中记录的队伍成员 realm 应为 5，材料掉落品阶应为灵品-宝品

#### Scenario: 炼虚境队伍击败妖兽
- **WHEN** 炼虚期（realm=4）弟子组成的队伍击败妖兽
- **THEN** 战斗日志中记录的队伍成员 realm 应为 4，材料掉落品阶应为宝品-地品

## MODIFIED Requirements

### Requirement: BattleMemberData 创建逻辑
修改 `BattleSystem.executeBattle` 方法中的 `BattleMemberData` 创建逻辑：
- `realm` 字段从 `combatant.realm` 获取
- `realmName` 字段从 `combatant.realmName` 获取

## REMOVED Requirements
无移除的需求。
