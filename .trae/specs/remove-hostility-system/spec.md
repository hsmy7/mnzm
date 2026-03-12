# 移除敌意值系统 Spec

## Why
敌意值系统增加了游戏的复杂性，但实际游戏体验中该系统可能不是核心玩法的一部分。移除该系统可以简化游戏逻辑，减少不必要的AI宗门攻击机制，让玩家专注于其他核心玩法。

## What Changes
- 移除 `WorldSect` 数据类中的 `hostility` 字段
- 移除 `GameEngine.kt` 中的敌意值处理逻辑：
  - `processHostilityAndAttacks` 方法
  - `updateHostilityAfterBattle` 方法
  - `addSectHostility` 方法
  - `launchAttackOnPlayerSect` 方法（如果仅被敌意值系统使用）
- 移除 `WorldMapGenerator.kt` 中敌意值的初始化逻辑
- 移除所有与敌意值相关的调用点

## Impact
- Affected specs: 世界地图系统、宗门战斗系统
- Affected code:
  - `GameData.kt` - WorldSect 数据类
  - `GameEngine.kt` - 敌意值处理逻辑
  - `WorldMapGenerator.kt` - 敌意值初始化

## ADDED Requirements
### Requirement: 移除敌意值字段
系统 SHALL 从 `WorldSect` 数据类中移除 `hostility` 字段。

#### Scenario: 数据迁移
- **WHEN** 玩家加载旧存档
- **THEN** 系统应正常加载，忽略旧的 hostility 字段

### Requirement: 移除敌意值处理逻辑
系统 SHALL 移除所有敌意值相关的处理逻辑。

#### Scenario: 游戏运行
- **WHEN** 游戏运行时
- **THEN** 不再有任何敌意值计算或AI宗门因敌意值发起攻击

## REMOVED Requirements
### Requirement: 敌意值增长机制
**Reason**: 简化游戏系统，移除非核心玩法
**Migration**: 直接移除，无需迁移

### Requirement: 敌意值触发攻击机制
**Reason**: 与敌意值系统绑定，一并移除
**Migration**: 直接移除，无需迁移

### Requirement: 战斗后敌意值调整
**Reason**: 与敌意值系统绑定，一并移除
**Migration**: 直接移除，无需迁移
