# 探查战斗使用AI宗门真实弟子 Spec

## Why
当前探查战斗的敌人是动态生成的虚拟弟子，用户需要使用AI宗门的真实弟子进行战斗，使游戏体验更加真实和一致。

## What Changes
- 修改探查战斗逻辑，从目标宗门的真实AI弟子中选择战斗敌人
- 敌人数量固定为20人（或该宗门筑基及以下弟子的实际数量，取较小值）
- 敌人境界限制为筑基（8）及炼气（9）
- 战斗胜利后获取宗门信息，失败则队伍返回

## Impact
- Affected specs: 探查系统、AI宗门弟子系统
- Affected code: `GameEngine.kt` 中的 `triggerScoutBattle` 和 `generateScoutEnemies` 函数

## ADDED Requirements

### Requirement: 探查战斗使用真实AI弟子
探查队伍到达目标宗门后，系统 SHALL 从目标宗门的真实AI弟子中选择战斗敌人。

#### Scenario: 选择战斗弟子
- **WHEN** 探查队伍到达目标宗门触发战斗
- **THEN** 从目标宗门的 `aiDisciples` 中选择存活的弟子
- **AND** 仅选择筑基（8）和炼气（9）境界的弟子
- **AND** 选择数量最多20人

#### Scenario: 弟子数量不足
- **WHEN** 目标宗门筑基及以下弟子少于20人
- **THEN** 选择所有符合条件的弟子参与战斗

#### Scenario: 无符合条件的弟子
- **WHEN** 目标宗门没有筑基及以下的存活弟子
- **THEN** 探查自动成功，获取宗门信息

### Requirement: 探查战斗胜利
#### Scenario: 战斗胜利
- **WHEN** 探查队伍在战斗中获胜
- **THEN** 获取目标宗门的弟子信息
- **AND** 队伍状态恢复为已完成
- **AND** 存活弟子状态恢复为空闲

### Requirement: 探查战斗失败
#### Scenario: 战斗失败
- **WHEN** 探查队伍在战斗中失败
- **THEN** 队伍全员阵亡或返回
- **AND** 不获取目标宗门信息
- **AND** 队伍状态恢复为已完成

## MODIFIED Requirements

### Requirement: 探查敌人生成函数
`generateScoutEnemies` 函数 SHALL 从AI宗门真实弟子中生成敌人。

**修改前**：
- 敌人数量：5-10人（随机）
- 敌人境界：根据探查队伍平均境界生成（±1浮动）
- 敌人属性：动态计算生成

**修改后**：
- 敌人数量：固定20人（或实际符合条件的弟子数量）
- 敌人境界：仅筑基（8）和炼气（9）
- 敌人属性：使用AI宗门弟子的真实属性

### Requirement: triggerScoutBattle函数
`triggerScoutBattle` 函数 SHALL 使用目标宗门的真实弟子进行战斗。

**修改内容**：
- 从 `targetSect.aiDisciples` 获取真实弟子
- 筛选筑基及以下境界的存活弟子
- 最多选择20人参与战斗
- 使用弟子的真实属性计算战斗
