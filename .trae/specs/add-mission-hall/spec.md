# 任务阁系统 Spec

## Why

当前游戏中弟子除了修炼、探索、炼丹等活动外，缺乏更多样化的派遣玩法。任务阁系统允许玩家派遣弟子执行多种任务，获得灵石、丹药、材料、灵草、功法等奖励，增加游戏策略深度和弟子利用率。

## What Changes

* 新增任务阁界面（MissionHallScreen.kt）

* 新增任务数据模型（Mission、MissionSlot等）

* 新增任务系统引擎（MissionSystem.kt）

* 扩展GameData添加任务相关字段

* 扩展DiscipleStatus添加ON\_MISSION状态

* 新增数据库迁移

## Impact

* Affected specs: 弟子状态管理、时间系统、资源系统

* Affected code:

  * GameData.kt（新增任务槽位和任务列表）

  * Disciple.kt（新增ON\_MISSION状态）

  * GameEngine.kt（新增任务处理逻辑）

  * MainGameScreen.kt（新增任务阁入口）

  * DatabaseMigrations.kt（数据库迁移）

***

## ADDED Requirements

### Requirement: 任务类型系统

系统应提供多种任务类型，每种任务有不同的要求、时长和奖励。

#### 任务类型定义

| 任务类型 | 描述        | 队伍人数 | 时长（月） | 主要奖励    | 可用难度        |
| ---- | --------- | ---- | ----- | ------- | ----------- |
| 护送商队 | 护送商队前往目的地 | 固定5人 | 1-2   | 灵石      | 黄级/玄级/地级/天级 |
| 讨伐妖兽 | 讨伐作乱的妖兽   | 固定5人 | 2-3   | 灵石、妖兽材料 | 黄级/玄级/地级/天级 |
| 宗门巡逻 | 在宗门周边巡逻采集 | 固定5人 | 1     | 草药/种子材料 | 黄级/玄级       |
| 调查事件 | 调查异常事件    | 固定5人 | 1-2   | 灵石、丹药   | 黄级/玄级/地级/天级 |
| 镇守城池 | 镇守占领的城池   | 固定5人 | 2-4   | 功法/装备   | 地级/天级       |

#### 任务难度等级（由低到高）

* **黄级**：低难度，低境界弟子可完成，奖励较少

* **玄级**：中等难度，需要一定实力

* **地级**：高难度，需要高境界弟子

* **天级**：极高难度，奖励丰厚

#### 难度对应关系

| 难度 | 最低境界要求 | 奖励品质范围 |
| -- | ------ | ------ |
| 黄级 | 炼气期（9） | 稀有度1-2 |
| 玄级 | 筑基期（8） | 稀有度2-3 |
| 地级 | 金丹期（7） | 稀有度3-4 |
| 天级 | 元婴期（6） | 稀有度4-6 |

#### Scenario: 玩家查看可用任务

* **WHEN** 玩家打开任务阁界面

* **THEN** 系统显示当前可接取的任务列表（3-5个随机任务）

* **AND** 每个任务显示类型、难度、时长、奖励预览、弟子要求

#### Scenario: 任务刷新

* **WHEN** 每月月初

* **THEN** 系统自动刷新可用任务列表

* **AND** 保留正在执行的任务

### Requirement: 任务派遣机制

系统应允许玩家选择弟子派遣执行任务。

#### 弟子要求

* 弟子必须处于空闲状态（IDLE）

* 弟子境界需满足任务最低要求

* **队伍固定5人**

#### Scenario: 成功派遣任务

* **WHEN** 玩家选择5名符合条件的弟子并确认派遣

* **THEN** 弟子状态变为ON\_MISSION

* **AND** 任务开始计时

* **AND** 显示预计完成时间

#### Scenario: 派遣失败-弟子不符合条件

* **WHEN** 玩家选择不符合条件的弟子

* **THEN** 系统提示"弟子境界不足"或"弟子状态不允许"

* **AND** 不执行派遣

#### Scenario: 派遣失败-人数不足

* **WHEN** 玩家选择的弟子不足5人

* **THEN** 系统提示"队伍需要5名弟子"

* **AND** 不执行派遣

### Requirement: 任务完成与奖励

系统应在任务完成后发放奖励。

#### Scenario: 任务成功完成

* **WHEN** 任务时间到达且任务成功

* **THEN** 弟子状态恢复为IDLE

* **AND** 发放任务奖励到玩家资源/背包

* **AND** 添加事件日志

#### Scenario: 任务失败

* **WHEN** 任务时间到达但任务失败（概率事件）

* **THEN** 弟子状态恢复为IDLE

* **AND** 弟子可能受伤（概率）

* **AND** 发放部分奖励或无奖励

* **AND** 添加事件日志

#### 成功率计算

```
基础成功率 = 70%
境界加成 = (队伍平均境界 - 任务最低境界) * 5%
最终成功率 = min(95%, 基础成功率 + 境界加成)
```

### Requirement: 奖励系统

任务奖励应使用游戏内已有的物品。

#### 奖励类型

| 奖励类型 | 来源                          | 说明                  |
| ---- | --------------------------- | ------------------- |
| 灵石   | 基础资源                        | 固定数量                |
| 丹药   | ItemDatabase                | 突破丹、修炼丹、战斗丹、治疗丹、功能丹 |
| 材料   | ItemDatabase.beastMaterials | 妖兽材料                |
| 装备   | EquipmentDatabase           | 各品质装备               |
| 功法   | ManualDatabase              | 各类型功法               |

#### 品质对应关系

| 品质名称 | 稀有度 |
| ---- | --- |
| 凡品   | 1   |
| 灵品   | 2   |
| 宝品   | 3   |
| 地品   | 4   |
| 天品   | 5-6 |

#### 护送商队奖励配置

| 难度 | 灵石奖励    |
| -- | ------- |
| 黄级 | 500灵石   |
| 玄级 | 1200灵石  |
| 地级 | 5000灵石  |
| 天级 | 20000灵石 |

#### 讨伐妖兽奖励配置

以护送商队的灵石奖励为基础，额外增加道具奖励：

| 难度 | 灵石奖励    | 额外道具奖励                        | 妖兽材料         |
| -- | ------- | ----------------------------- | ------------ |
| 黄级 | 500灵石   | 75%概率获得1-5个道具（灵品及以下的功法/装备/丹药） | 5-10种（灵品及以下） |
| 玄级 | 1200灵石  | 60%概率获得1-2个道具（宝品至灵品的功法/装备/丹药） | 5-10种（宝品至灵品） |
| 地级 | 5000灵石  | 20%概率获得1个道具（地品至宝品的功法/装备/丹药）   | 1-5种（地品至宝品）  |
| 天级 | 20000灵石 | 20%概率获得1个道具（天品至地品的功法/装备/丹药）   | 1-5种（天品至地品）  |

#### 奖励池设计

```kotlin
data class MissionRewardConfig(
    val spiritStones: Int,                    // 固定灵石数量
    val extraItemChance: Double = 0.0,        // 额外道具概率
    val extraItemCountRange: IntRange = 0..0, // 额外道具数量范围
    val extraItemMinRarity: Int = 1,          // 额外道具最小稀有度
    val extraItemMaxRarity: Int = 2,          // 额外道具最大稀有度
    val materialCountRange: IntRange = 0..0,  // 材料数量范围
    val materialMinRarity: Int = 1,           // 材料最小稀有度
    val materialMaxRarity: Int = 2,           // 材料最大稀有度
    val herbCountRange: IntRange = 0..0,      // 草药数量范围
    val herbMaxRarity: Int = 1,               // 草药最大稀有度
    val seedCountRange: IntRange = 0..0,      // 种子数量范围
    val seedMaxRarity: Int = 1                // 种子最大稀有度
)

enum class RewardItemType {
    MANUAL,    // 功法
    EQUIPMENT, // 装备
    PILL       // 丹药
}

// 调查事件结局
enum class InvestigateOutcome {
    BEAST_RIOT,      // 妖兽作乱
    SECT_CONFLICT,   // 门派争斗
    DESTINED_CHILD,  // 天命之子
    NOTHING_FOUND    // 一无所获
}

// 调查事件奖励配置
data class InvestigateRewardConfig(
    val outcome: InvestigateOutcome,
    val spiritStones: Int,                    // 灵石（一无所获时为0）
    val materialCountRange: IntRange = 0..0,  // 材料数量范围
    val materialMinRarity: Int = 1,           // 材料最小稀有度
    val materialMaxRarity: Int = 1,           // 材料最大稀有度
    val itemCountRange: IntRange = 0..0,      // 道具数量范围
    val itemMinRarity: Int = 1,               // 道具最小稀有度
    val itemMaxRarity: Int = 2,               // 道具最大稀有度
    val discipleCount: Int = 0,               // 弟子数量
    val discipleSpiritRootCount: Int = 2      // 灵根数量（2=双灵根，1=单灵根）
)
```

#### 其他任务类型奖励

| 任务类型 | 基础奖励     | 说明                                  |
| ---- | -------- | ----------------------------------- |
| 宗门巡逻 | 草药/种子材料  | 黄级：5-20种（凡品）；玄级：5-15种（灵品及以下）；无地级/天级 |
| 调查事件 | 灵石+多结局奖励 | 33%妖兽作乱/33%门派争斗/33%天命之子/1%一无所获      |
| 镇守城池 | 参考讨伐妖兽   | 灵石+材料+丹药                            |

#### 宗门巡逻奖励配置

| 难度 | 奖励内容                 |
| -- | -------------------- |
| 黄级 | 5-20种材料（凡品的草药/种子）    |
| 玄级 | 5-15种材料（灵品及以下的草药/种子） |
| 地级 | 无此难度任务               |
| 天级 | 无此难度任务               |

#### 调查事件奖励配置

调查事件以护送商队的灵石奖励为基础，根据调查结果发放额外奖励：

**结局概率**：

* 妖兽作乱：33%

* 门派争斗：33%

* 天命之子：33%

* 一无所获：1%（无任何奖励，灵石也没有）

**妖兽作乱结局奖励**：

| 难度 | 额外奖励           |
| -- | -------------- |
| 黄级 | 5-20种凡品妖兽材料    |
| 玄级 | 5-15种宝品至灵品妖兽材料 |
| 地级 | 1-10种地品至玄品妖兽材料 |
| 天级 | 1-5种天品至地品妖兽材料  |

**门派争斗结局奖励**：

| 难度 | 额外奖励                   |
| -- | ---------------------- |
| 黄级 | 1-10种灵品及以下道具（功法/装备/丹药） |
| 玄级 | 1-5种玄品至灵品道具（功法/装备/丹药）  |
| 地级 | 1-3种地品至宝品道具（功法/装备/丹药）  |
| 天级 | 1-2种天品至地品道具（功法/装备/丹药）  |

**天命之子结局奖励**：

| 难度 | 额外奖励             |
| -- | ---------------- |
| 黄级 | 1名双灵根弟子（年龄5-10岁） |
| 玄级 | 3名双灵根弟子（年龄5-10岁） |
| 地级 | 1名单灵根弟子（年龄5-10岁） |
| 天级 | 3名单灵根弟子（年龄5-10岁） |

**一无所获结局**：

* 无任何奖励（包括灵石）

### Requirement: 任务槽位系统

系统应限制同时进行的任务数量。

#### 槽位规则

* 初始槽位：2个

* 每扩建一次增加1个槽位

* 最大槽位：5个

* 扩建消耗灵石

#### Scenario: 槽位已满

* **WHEN** 所有任务槽位都在使用中

* **THEN** 玩家无法派遣新任务

* **AND** 显示"任务槽位已满"提示

### Requirement: 任务界面

系统应提供直观的任务管理界面。

#### 界面布局

```
┌─────────────────────────────────────┐
│           任务阁                     │
├─────────────────────────────────────┤
│ 任务槽位：2/3 [扩建 5000灵石]        │
├─────────────────────────────────────┤
│ 进行中的任务                         │
│ ┌─────────────────────────────────┐ │
│ │ 护送商队 [黄级]                  │ │
│ │ 弟子：李逍遥、林月如、赵灵儿...   │ │
│ │ 进度：██████░░░░ 60%            │ │
│ │ 剩余：1月2天                     │ │
│ └─────────────────────────────────┘ │
├─────────────────────────────────────┤
│ 可接取的任务                         │
│ ┌─────────────────────────────────┐ │
│ │ 讨伐妖兽 [地级]                  │ │
│ │ 时长：3月 | 需要：5名弟子        │ │
│ │ 最低境界：金丹期                 │ │
│ │ 奖励：8000-20000灵石、妖兽材料   │ │
│ │ [选择弟子]                       │ │
│ └─────────────────────────────────┘ │
└─────────────────────────────────────┘
```

***

## MODIFIED Requirements

### Requirement: DiscipleStatus枚举扩展

在现有DiscipleStatus枚举中添加ON\_MISSION状态。

```kotlin
enum class DiscipleStatus {
    IDLE, CULTIVATING, EXPLORING, ALCHEMY, FORGING, FARMING, 
    STUDYING, BATTLE, WORKING, SCOUTING, MINING, REFLECTING, 
    LAW_ENFORCING, PREACHING, MANAGING, GROWING, ON_MISSION;
    
    val displayName: String get() = when (this) {
        // ... 现有状态 ...
        ON_MISSION -> "执行任务中"
    }
}
```

### Requirement: GameData扩展

在GameData中添加任务相关字段。

```kotlin
// 任务槽位数量
var missionSlots: Int = 2,

// 进行中的任务
var activeMissions: List<ActiveMission> = emptyList(),

// 可用任务列表（每月刷新）
var availableMissions: List<Mission> = emptyList(),

// 上次任务刷新时间
var lastMissionRefreshYear: Int = 1,
var lastMissionRefreshMonth: Int = 1
```

***

## REMOVED Requirements

无移除的需求。

***

## 数据模型设计

### Mission（任务模板）

```kotlin
data class Mission(
    val id: String = UUID.randomUUID().toString(),
    val type: MissionType,
    val name: String,
    val description: String,
    val difficulty: MissionDifficulty,
    val minRealm: Int,
    val duration: Int, // 月数
    val rewards: MissionRewardConfig,
    val successRate: Double = 0.7
)

enum class MissionType {
    ESCORT,       // 护送商队
    HUNT,         // 讨伐妖兽
    PATROL,       // 宗门巡逻
    INVESTIGATE,  // 调查事件
    GUARD_CITY    // 镇守城池
}

enum class MissionDifficulty {
    YELLOW,   // 黄级
    MYSTERIOUS, // 玄级
    EARTH,    // 地级
    HEAVEN;   // 天级
    
    val displayName: String get() = when(this) {
        YELLOW -> "黄级"
        MYSTERIOUS -> "玄级"
        EARTH -> "地级"
        HEAVEN -> "天级"
    }
    
    val minRealm: Int get() = when(this) {
        YELLOW -> 9    // 炼气期
        MYSTERIOUS -> 8 // 筑基期
        EARTH -> 7     // 金丹期
        HEAVEN -> 6    // 元婴期
    }
}

data class MissionRewardConfig(
    val spiritStones: Int,                    // 固定灵石数量
    val extraItemChance: Double = 0.0,        // 额外道具概率
    val extraItemCountRange: IntRange = 0..0, // 额外道具数量范围
    val extraItemMaxRarity: Int = 2,          // 额外道具最大稀有度（灵品=2）
    val materialCountRange: IntRange = 0..0,  // 材料数量范围
    val materialMaxRarity: Int = 2            // 材料最大稀有度
)

enum class RewardItemType {
    MANUAL,    // 功法
    EQUIPMENT, // 装备
    PILL       // 丹药
}
```

### ActiveMission（进行中的任务）

```kotlin
data class ActiveMission(
    val id: String = UUID.randomUUID().toString(),
    val missionId: String,
    val missionName: String,
    val missionType: MissionType,
    val difficulty: MissionDifficulty,
    val discipleIds: List<String>,      // 固定5人
    val discipleNames: List<String>,
    val startYear: Int,
    val startMonth: Int,
    val duration: Int,
    val rewards: MissionRewardConfig,
    val successRate: Double
) {
    val memberCount: Int get() = discipleIds.size
    
    fun getRemainingMonths(currentYear: Int, currentMonth: Int): Int
    fun getProgressPercent(currentYear: Int, currentMonth: Int): Int
    fun isComplete(currentYear: Int, currentMonth: Int): Boolean
}
```

***

## 文件结构

```
android/app/src/main/java/com/xianxia/sect/
├── core/
│   ├── engine/
│   │   └── MissionSystem.kt          # 任务系统核心逻辑
│   └── model/
│       └── Mission.kt                # 任务数据模型
├── ui/
│   └── game/
│       └── MissionHallScreen.kt      # 任务阁界面
└── data/
    └── local/
        └── DatabaseMigrations.kt     # 数据库迁移
```

***

## 事件通知

| 事件类型    | 触发条件   | 通知内容             |
| ------- | ------ | ---------------- |
| SUCCESS | 任务成功完成 | "{任务名}完成，获得{奖励}" |
| WARNING | 任务失败   | "执行{任务名}失败"      |
| WARNING | 弟子受伤   | "{弟子名}在任务中受伤"    |
| INFO    | 任务开始   | "开始执行{任务名}"      |

