# AI宗门互相进攻机制规格

## Why

当前游戏世界中AI宗门之间只有好感度变化和结盟关系，缺乏动态的冲突和战争行为。增加AI宗门互相进攻机制可以让游戏世界更加生动，玩家可以观察到AI宗门之间的争斗，同时好感度系统也会更有意义——低好感度可能导致战争。

## What Changes

* 新增AI宗门进攻决策逻辑（基于好感度、正邪对立、实力对比、路线相连）

* 新增AI宗门进攻队伍数据结构

* 新增AI宗门进攻战斗处理

* 新增AI宗门进攻结果处理（胜利/失败/占领）

* 新增宗门灭亡事件通知

* 修改好感度系统，进攻行为影响好感度

* 修改玩家战斗队伍进攻规则，增加路线相连限制

## Impact

* Affected specs: sect-alliance-system, battle-team-attack-occupy, ai-sect-disciple-system

* Affected code:

  * `GameData.kt` - 新增AIBattleTeam数据结构

  * `GameEngine.kt` - 新增AI进攻决策和处理逻辑

  * `WorldMapScreen.kt` - 显示AI进攻队伍移动，修改玩家进攻目标选择

  * `BattleTeamDialog.kt` - 修改移动按钮逻辑

## ADDED Requirements

### Requirement: AI宗门进攻队伍数据结构

系统应为AI宗门进攻提供专门的数据结构。

#### Scenario: 进攻队伍数据

* **WHEN** AI宗门发起进攻时

* **THEN** 创建进攻队伍，包含：

  * 进攻方宗门ID

  * 防守方宗门ID

  * 参战弟子列表（从进攻方AI弟子中选择，固定10人）

  * 当前位置、目标位置

  * 移动进度

  * 进攻状态（移动中/战斗中/返回中）

### Requirement: AI宗门进攻决策

系统应根据好感度和其他因素决定AI宗门是否发起进攻。

#### Scenario: 进攻条件检查

* **GIVEN** AI宗门A考虑进攻AI宗门B

* **WHEN** 检查进攻条件

* **THEN** 需满足以下条件：

  * 双方好感度 < 20（敌对状态）

  * 进攻方至少有10名弟子

  * 进攻方未被其他宗门进攻中

  * 双方非盟友关系

  * **双方路线相连**（B在A的connectedSectIds中，或B在A占领的宗门的connectedSectIds中）

#### Scenario: 路线相连判断

* **GIVEN** AI宗门A考虑进攻目标宗门

* **WHEN** 判断路线是否相连

* **THEN** 目标宗门必须满足以下条件之一：

  * 目标宗门在A的connectedSectIds中

  * 目标宗门在A占领的宗门的connectedSectIds中

  * 占领的宗门视为自己的宗门，可作为进攻起点

#### Scenario: 正邪对立加成

* **GIVEN** AI宗门A考虑进攻AI宗门B

* **WHEN** 一方正道、一方魔道

* **THEN** 进攻概率额外+10%

#### Scenario: 实力对比评估

* **GIVEN** AI宗门A考虑进攻AI宗门B

* **WHEN** 评估实力对比

* **THEN** 计算实力评分：

  * 实力评分 = 弟子数量 × (10 - 平均境界)

  * 进攻方实力评分 >= 防守方实力评分 × 0.8 时，才考虑进攻

#### Scenario: 进攻概率计算

* **GIVEN** AI宗门满足进攻条件

* **WHEN** 计算进攻概率

* **THEN** 概率 = 基础概率(3%) + 好感度惩罚 + 正邪加成

  * 好感度惩罚 = (20 - 好感度) × 0.5%

  * 正邪加成 = 10%（若正邪对立）

  * 最高概率不超过20%

### Requirement: AI宗门进攻队伍组建

系统应为AI宗门进攻自动组建进攻队伍。

#### Scenario: 进攻队伍人数

* **WHEN** AI宗门组建进攻队伍

* **THEN** 队伍人数固定为10人

#### Scenario: 进攻队伍弟子选择

* **WHEN** AI宗门组建进攻队伍

* **THEN** 优先选择境界较高的弟子

  * 从最高境界开始依次选择

#### Scenario: 进攻队伍属性

* **WHEN** 进攻队伍组建完成

* **THEN** 队伍继承弟子的真实属性、天赋、装备和功法

### Requirement: AI宗门进攻移动

系统应使AI进攻队伍移动至目标宗门。

#### Scenario: 移动路径

* **WHEN** AI进攻队伍开始移动

* **THEN** 直接移动至目标宗门（因为目标必须路线相连）

#### Scenario: 移动速度

* **WHEN** AI进攻队伍移动时

* **THEN** 移动速度与探查队伍一致

  * 根据路线距离计算移动时间

#### Scenario: 移动显示

* **WHEN** AI进攻队伍移动时

* **THEN** 在世界地图上显示队伍移动动画

  * 显示进攻方宗门颜色标记

### Requirement: AI宗门进攻战斗

系统应在进攻队伍到达目标后进行战斗。

#### Scenario: 防守队伍组建（AI宗门）

* **WHEN** AI进攻队伍到达AI目标宗门

* **THEN** 防守方组建防御队伍

  * 人数固定为10人

  * 优先选择境界较高的弟子

#### Scenario: 战斗回合

* **WHEN** 战斗开始

* **THEN** 战斗进行25回合

  * 使用现有战斗系统进行计算

#### Scenario: 进攻胜利

* **WHEN** 进攻方在25回合内击败所有防守弟子

* **THEN** 进攻方胜利

* **AND** 防守方弟子死亡或重伤

* **AND** 进攻方开始返回

#### Scenario: 进攻失败

* **WHEN** 进攻方在25回合内被击败

* **THEN** 进攻方失败

* **AND** 进攻方存活弟子返回

#### Scenario: 平局处理

* **WHEN** 25回合结束双方仍有存活

* **THEN** 判定为平局

* **AND** 进攻方返回

### Requirement: AI宗门进攻结果处理

系统应根据进攻结果更新宗门状态，战斗中使用真实弟子，战斗中死亡的弟子会被正确移除。

#### Scenario: 进攻胜利后果

* **GIVEN** 进攻方胜利

* **WHEN** 处理胜利结果

* **THEN** 战斗中的死亡弟子从宗门弟子列表中移除

#### Scenario: 进攻失败后果

* **GIVEN** 进攻方失败

* **WHEN** 处理失败结果

* **THEN** 战斗中的死亡弟子从宗门弟子列表中移除

#### Scenario: 平局处理后果

* **GIVEN** 战斗平局

* **WHEN** 处理平局结果

* **THEN** 战斗中的死亡弟子从宗门弟子列表中移除

#### Scenario: AI宗门占领条件

* **GIVEN** 进攻方胜利

* **WHEN** 防守方化神（realm=5）及以上弟子全部死亡

* **THEN** 防守方宗门被进攻方占领

  * 宗门名称保留，但标记为进攻方的附庸

  * 进攻方获得防守方所有资源

  * **占领的宗门视为进攻方的宗门，可作为新的进攻起点**

### Requirement: AI宗门进攻事件通知

系统应向玩家通知AI宗门灭亡事件。

#### Scenario: 宗门灭亡通知

* **WHEN** 宗门被吞并

* **THEN** 生成事件："【宗门灭亡】{防守方}被{进攻方}吞并！"

### Requirement: AI宗门进攻与结盟联动

系统应使进攻行为影响结盟关系。

#### Scenario: 盟友被攻击支援

* **GIVEN** AI宗门A进攻AI宗门B

* **WHEN** B有盟友C，且C与B好感度 > 90

* **THEN** 每月有3.2%概率C向A发起进攻（支援盟友）

  * C必须与A路线相连才能发起支援进攻

  * 支援队伍固定为10人

### Requirement: AI宗门进攻玩家宗门

系统应允许AI宗门进攻玩家宗门。

#### Scenario: 进攻玩家条件

* **GIVEN** AI宗门A考虑进攻玩家宗门

* **WHEN** 检查进攻条件

* **THEN** 需满足以下条件：

  * 好感度 < 20

  * 进攻方至少有10名弟子

  * 双方非盟友关系

  * **玩家宗门在A的connectedSectIds中，或在A占领的宗门的connectedSectIds中**

  * 进攻概率计算与进攻AI宗门相同

#### Scenario: 玩家防守队伍组建

* **WHEN** AI进攻队伍到达玩家宗门

* **THEN** 玩家自动组建防御队伍

  * 人数固定为10人

  * 选择空闲状态（IDLE）的弟子

  * 优先选择境界较高的弟子

  * 不选择在思过崖的弟子

#### Scenario: 玩家防守失败后果

* **GIVEN** AI进攻方胜利

* **WHEN** 玩家防守失败

* **THEN** 玩家仓库被掠夺

  * 随机掠夺30-50个道具

  * 1000灵石算一个道具

### Requirement: AI宗门进攻队伍数据模型

系统 SHALL 创建AIBattleTeam数据模型支持进攻状态。

#### Scenario: 进攻队伍数据

* **WHEN** AI宗门发起进攻

* **THEN** 记录进攻方宗门ID、防守方宗门ID、参战弟子列表、移动进度、进攻状态

## MODIFIED Requirements

### Requirement: 玩家战斗队伍进攻目标选择

玩家战斗队伍进攻AI宗门时，需要遵循路线相连规则。

**修改内容**：

* 点击移动按钮后，只有与玩家宗门路线相连的AI宗门才高光显示

* 与被玩家占领的宗门路线相连的AI宗门也可作为进攻目标

* 占领的宗门视为玩家宗门，可作为新的进攻起点

#### Scenario: 可进攻目标显示

* **GIVEN** 玩家点击战斗队伍的移动按钮

* **WHEN** 显示可进攻目标

* **THEN** 只有满足以下条件的AI宗门高光显示：

  * 目标宗门在玩家宗门的connectedSectIds中

  * 或目标宗门在被玩家占领的宗门的connectedSectIds中

#### Scenario: 进攻目标选择

* **GIVEN** 玩家点击高光的AI宗门

* **WHEN** 确认进攻

* **THEN** 战斗队伍开始移动至目标宗门

### Requirement: SectRelation好感度变化

好感度系统需要与进攻行为联动。

**修改内容**：

* 进攻行为会大幅降低双方好感度

* 进攻失败会降低进攻方对防守方的好感度

* 进攻胜利会降低防守方对进攻方的好感度

### Requirement: 年度事件处理扩展

年度事件处理需要添加AI进攻决策逻辑。

**修改内容**：

* 每年检查所有AI宗门之间的进攻可能性

* 根据好感度、实力对比和路线相连决定是否发起进攻

## REMOVED Requirements

无移除的需求。

***

## 数据结构设计

### 新增AIBattleTeam数据结构

```kotlin
data class AIBattleTeam(
    val id: String = java.util.UUID.randomUUID().toString(),
    val attackerSectId: String = "",
    val attackerSectName: String = "",
    val defenderSectId: String = "",
    val defenderSectName: String = "",
    val disciples: List<AISectDisciple> = emptyList(),
    val currentX: Float = 0f,
    val currentY: Float = 0f,
    val targetX: Float = 0f,
    val targetY: Float = 0f,
    val moveProgress: Float = 0f,
    val status: String = "moving", // moving, battling, returning, completed
    val route: List<String> = emptyList(),
    val currentRouteIndex: Int = 0,
    val startYear: Int = 0,
    val startMonth: Int = 0
)
```

### GameData扩展字段

```kotlin
data class GameData(
    // ... 现有字段 ...
    val aiBattleTeams: List<AIBattleTeam> = emptyList()
)
```

### WorldSect扩展字段

```kotlin
data class WorldSect(
    // ... 现有字段 ...
    val isUnderAttack: Boolean = false,
    val attackerSectId: String? = null,
    val occupierSectId: String? = null  // 占领方宗门ID
)
```

***

## 核心数值表

### 进攻条件

| 条件          | 数值              |
| ----------- | --------------- |
| 最低好感度（进攻阈值） | < 20            |
| 进攻方最少弟子数    | 10人             |
| 实力对比最低比例    | 0.8             |
| 路线相连        | 必须（占领的宗门算自己的宗门） |

### 进攻概率计算

| 因素     | 计算                |
| ------ | ----------------- |
| 基础概率   | 3%                |
| 好感度惩罚  | (20 - 好感度) × 0.5% |
| 正邪对立加成 | +10%              |
| 最高概率   | 20%               |

### 进攻队伍规模

| 条件   | 数值    |
| ---- | ----- |
| 队伍人数 | 固定10人 |

### 防守队伍规模

| 条件       | 数值                      |
| -------- | ----------------------- |
| AI宗门防守人数 | 固定10人                   |
| 玩家宗门防守人数 | 固定10人（空闲状态，高境界优先，不在思过崖） |

### 进攻结果处理

| 结果   | 处理方式          |
| ---- | ------------- |
| 进攻胜利 | 死亡弟子从宗门弟子列表移除 |
| 进攻失败 | 死亡弟子从宗门弟子列表移除 |
| 平局   | 死亡弟子从宗门弟子列表移除 |

**重要说明**：

* 进攻/防守队伍使用真实弟子数据

* 战斗中死亡的弟子会被正确移除

* 死亡弟子从宗门的aiDisciples列表中移除

### AI宗门占领条件

| 条件      | 说明              |
| ------- | --------------- |
| 化神及以上弟子 | 全部死亡即可被占领       |
| 占领后     | 视为自己的宗门，可作为进攻起点 |

### 盟友支援

| 条件     | 数值            |
| ------ | ------------- |
| 支援概率   | 3.2%/月        |
| 好感度要求  | 与被攻击方好感度 > 90 |
| 路线要求   | 必须与进攻方路线相连    |
| 支援队伍人数 | 固定10人         |

### 玩家防守失败后果

| 后果   | 说明          |
| ---- | ----------- |
| 仓库掠夺 | 随机30-50个道具  |
| 灵石换算 | 1000灵石算一个道具 |

### 玩家进攻目标选择

| 条件    | 说明                                 |
| ----- | ---------------------------------- |
| 路线相连  | 目标宗门在玩家宗门或玩家占领宗门的connectedSectIds中 |
| 占领的宗门 | 视为玩家宗门，可作为进攻起点                     |

***

## UI设计要点

### 世界地图显示

在现有世界地图上增加：

1. AI进攻队伍移动动画
2. 进攻方宗门颜色标记
3. 被进攻宗门的战斗标记
4. 玩家战斗队伍移动时，只高光显示路线相连的可进攻目标

### 事件通知

使用现有事件系统显示：

1. 宗门灭亡事件

