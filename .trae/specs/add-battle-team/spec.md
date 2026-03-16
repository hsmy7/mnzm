# 战斗队伍系统 Spec

## Why
玩家需要一个战斗队伍系统来组织弟子进行战斗活动，在世界地图中直观显示队伍状态，提升游戏的可玩性和策略性。玩家宗门只能拥有一支战斗队伍。

## What Changes
- 在世界地图中增加"组建队伍"和"管理队伍"两个按钮
- 创建队伍界面，显示10个槽位（分两行显示）
- 实现弟子选择界面，支持境界筛选和排序
- 在世界地图玩家宗门名称上方显示战斗队伍
- 新增 BattleTeam 数据模型存储队伍信息

## Impact
- Affected code: 
  - `WorldMapScreen.kt` - 添加组建队伍和管理队伍按钮
  - `MainGameScreen.kt` - 添加队伍相关弹窗
  - `GameViewModel.kt` - 添加队伍管理逻辑
  - `GameData.kt` - 添加 battleTeam 字段
  - 新增 `BattleTeam.kt` 数据模型

## ADDED Requirements

### Requirement: 战斗队伍数据模型
系统 SHALL 提供 BattleTeam 数据模型存储战斗队伍信息。

#### Scenario: 创建战斗队伍数据
- **WHEN** 玩家组建战斗队伍
- **THEN** 系统创建包含10个槽位的 BattleTeam 数据，每个槽位存储弟子ID、名称、境界信息

### Requirement: 玩家宗门地址上只能存在一支战斗队伍
系统 SHALL 限制玩家宗门地址上只能存在一支战斗队伍。

#### Scenario: 队伍驻守在宗门地址
- **WHEN** 玩家已有一支战斗队伍驻守在宗门地址
- **THEN** 无法在宗门地址创建新的战斗队伍
- **AND** 现有队伍可以离开宗门地址去执行任务

#### Scenario: 队伍离开后可创建新队伍
- **WHEN** 现有战斗队伍离开宗门地址
- **THEN** 可以在宗门地址创建新的战斗队伍

### Requirement: 世界地图创建队伍和管理队伍按钮
系统 SHALL 在世界地图界面提供创建队伍和管理队伍按钮。

#### Scenario: 显示组建队伍和管理队伍按钮
- **WHEN** 玩家打开世界地图
- **THEN** 在界面左下角显示"组建队伍"按钮
- **AND** 在"组建队伍"按钮右侧显示"管理队伍"按钮

#### Scenario: 已有队伍时的按钮状态
- **WHEN** 玩家已有战斗队伍
- **THEN** "组建队伍"按钮禁用或隐藏
- **AND** "管理队伍"按钮可用

#### Scenario: 无队伍时的按钮状态
- **WHEN** 玩家没有战斗队伍
- **THEN** "组建队伍"按钮可用
- **AND** "管理队伍"按钮禁用或隐藏

### Requirement: 队伍界面槽位显示
系统 SHALL 提供队伍界面显示10个槽位，分两行显示。

#### Scenario: 显示空闲槽位
- **WHEN** 槽位没有弟子
- **THEN** 显示"+"号表示可添加弟子

#### Scenario: 显示已占用槽位
- **WHEN** 槽位有弟子
- **THEN** 显示弟子名称和境界
- **AND** 槽位下方显示"卸任"按钮

### Requirement: 弟子选择界面
系统 SHALL 提供弟子选择界面，仅显示符合条件的弟子。

#### Scenario: 筛选可用弟子
- **WHEN** 玩家点击槽位选择弟子
- **THEN** 仅显示满足以下条件的弟子：
  - 状态为空闲（IDLE）
  - 境界为练气一层以上（realmLayer > 0）
  - 不在思过崖（status != REFLECTING）

#### Scenario: 境界筛选
- **WHEN** 弟子选择界面显示
- **THEN** 提供境界筛选按钮，按境界分类显示弟子数量

#### Scenario: 弟子排序
- **WHEN** 弟子列表显示
- **THEN** 按最高修为排序（境界从高到低，同境界按层数从高到低）

### Requirement: 组建队伍验证
系统 SHALL 验证队伍组建条件。

#### Scenario: 满员验证
- **WHEN** 玩家点击"组建队伍"按钮
- **AND** 槽位未满10名弟子
- **THEN** 显示提示"必须满10名弟子才可组建队伍"

#### Scenario: 组建成功
- **WHEN** 玩家点击"组建队伍"按钮
- **AND** 槽位已满10名弟子
- **THEN** 创建战斗队伍并关闭界面

### Requirement: 世界地图队伍显示
系统 SHALL 在世界地图玩家宗门名称上方显示战斗队伍。

#### Scenario: 显示队伍标记
- **WHEN** 玩家已组建战斗队伍
- **THEN** 在世界地图玩家宗门名称上方显示队伍标记
- **AND** 标记显示"战斗队伍"或队伍名称

#### Scenario: 无队伍时不显示
- **WHEN** 玩家未组建战斗队伍
- **THEN** 世界地图不显示队伍标记

### Requirement: 卸任弟子功能
系统 SHALL 提供卸任弟子功能。

#### Scenario: 卸任弟子
- **WHEN** 玩家点击槽位下方的"卸任"按钮
- **THEN** 该弟子从队伍槽位移除
- **AND** 弟子状态恢复为空闲

## MODIFIED Requirements
无

## REMOVED Requirements
无
