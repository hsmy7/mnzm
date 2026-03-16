# 任务阁建筑系统 Spec

## Why
当前游戏缺少一个让玩家派遣弟子执行任务获取奖励的系统，任务阁可以增加游戏的可玩性和策略深度，让玩家根据弟子属性合理安排任务，获得资源奖励。

## What Changes
- 新增任务阁建筑配置
- 新增任务数据模型（任务类型、难度、奖励、属性要求）
- 新增任务槽位系统（可派遣弟子的任务位）
- 新增任务刷新机制（每三月刷新）
- 新增任务执行与结算逻辑
- 新增任务阁UI界面

## Impact
- Affected specs: 建筑系统、弟子状态系统、时间系统
- Affected code: GameConfig.kt, GameData.kt, GameEngine.kt, GameViewModel.kt, Disciple.kt

## ADDED Requirements

### Requirement: 任务阁建筑配置
系统应在 GameConfig.Buildings 中添加任务阁建筑配置。

#### Scenario: 建筑配置
- **WHEN** 游戏初始化时
- **THEN** 任务阁建筑配置包含 id="taskHall", name="任务阁", description="派遣弟子执行任务", maxSlots=5

### Requirement: 任务数据模型
系统应提供完整的任务数据模型。

#### Scenario: 任务模型定义
- **WHEN** 定义任务数据时
- **THEN** 任务包含以下属性：
  - id: 任务唯一标识
  - name: 任务名称
  - description: 任务描述
  - difficulty: 难度等级（简单/普通/困难/极难/地狱）
  - requiredAttributes: 所需属性及权重（如智力、力量、速度等）
  - baseSuccessRate: 基础成功率
  - duration: 执行所需月数
  - rewards: 成功奖励（灵石、物品、声望等）
  - status: 任务状态（available/ongoing/completed/expired）

### Requirement: 任务难度系统
系统应使用文字描述任务难度。

#### Scenario: 难度等级定义
- **WHEN** 生成任务时
- **THEN** 难度等级包括：
  - 简单：基础成功率70%，适合低境界弟子
  - 普通：基础成功率50%，需要一定属性支持
  - 困难：基础成功率30%，需要高属性弟子
  - 极难：基础成功率15%，需要精英弟子
  - 地狱：基础成功率5%，需要顶尖弟子

### Requirement: 任务类型系统
系统应提供多种任务类型。

#### Scenario: 任务类型定义
- **WHEN** 生成任务时
- **THEN** 任务类型包括：
  - 探索任务：主要依赖速度和智力
  - 战斗任务：主要依赖攻击和防御
  - 采集任务：主要依赖种植和炼丹
  - 护送任务：主要依赖速度和防御
  - 调查任务：主要依赖智力和魅力

### Requirement: 任务刷新机制
系统应每三个月自动刷新任务列表。

#### Scenario: 定期刷新
- **WHEN** 游戏时间每过三个月（如1月、4月、7月、10月）
- **THEN** 系统自动生成新的任务列表，替换过期任务

#### Scenario: 首次进入
- **WHEN** 玩家首次进入任务阁
- **THEN** 如果当前没有任务或任务已过期，生成新任务列表

### Requirement: 任务派遣系统
系统应允许玩家派遣空闲弟子执行任务。

#### Scenario: 派遣弟子
- **WHEN** 玩家选择一个可用任务并选择一名空闲弟子
- **THEN** 弟子状态变更为 ON_MISSION，任务状态变更为 ongoing，记录开始时间

#### Scenario: 弟子状态限制
- **WHEN** 弟子处于非空闲状态（修炼中、探索中、炼丹中等）
- **THEN** 该弟子不可被派遣执行任务

### Requirement: 成功率计算系统
系统应根据弟子属性计算任务成功率。

#### Scenario: 成功率计算
- **WHEN** 计算任务成功率时
- **THEN** 成功率 = 基础成功率 + Σ(弟子属性值 - 50) × 属性权重 × 0.5%
- 成功率上限为95%，下限为5%

#### Scenario: 境界加成
- **WHEN** 弟子境界高于任务难度要求时
- **THEN** 成功率额外增加（每高一个大境界+5%）

### Requirement: 任务结算系统
系统应在任务完成后进行结算。

#### Scenario: 任务成功
- **WHEN** 任务执行时间结束且随机判定成功
- **THEN** 弟子获得奖励，任务状态变为 completed，弟子状态恢复为 IDLE

#### Scenario: 任务失败
- **WHEN** 任务执行时间结束且随机判定失败
- **THEN** 弟子无奖励，任务状态变为 completed，弟子状态恢复为 IDLE

#### Scenario: 弟子死亡处理
- **WHEN** 执行任务的弟子死亡
- **THEN** 任务自动失败，任务槽位释放

### Requirement: 任务奖励系统
系统应根据任务难度提供相应奖励。

#### Scenario: 奖励类型
- **WHEN** 任务成功完成
- **THEN** 奖励可能包括：
  - 灵石：根据难度100-5000不等
  - 材料：随机品质材料
  - 装备：低概率获得装备
  - 声望：增加宗门声望
  - 弟子经验：增加弟子战斗经验

### Requirement: 任务阁UI界面
系统应提供任务阁操作界面。

#### Scenario: 任务列表显示
- **WHEN** 玩家打开任务阁
- **THEN** 显示当前所有可用任务，包含任务名称、难度、预计成功率、奖励预览

#### Scenario: 弟子选择
- **WHEN** 玩家点击派遣按钮
- **THEN** 显示空闲弟子列表，选中弟子后显示预计成功率

#### Scenario: 进行中任务
- **WHEN** 有任务正在进行
- **THEN** 显示任务进度、执行弟子、剩余时间

### Requirement: 弟子状态扩展
系统应扩展弟子状态以支持任务执行。

#### Scenario: 新增状态
- **WHEN** 弟子被派遣执行任务
- **THEN** 弟子状态新增 ON_MISSION 枚举值

## MODIFIED Requirements

### Requirement: GameData 扩展
GameData 需要新增任务相关数据字段。

#### Scenario: 数据字段
- **WHEN** 保存游戏数据时
- **THEN** GameData 包含：
  - taskHallTasks: List<TaskData> 任务列表
  - taskHallLastRefreshYear: Int 上次刷新年份
  - taskHallLastRefreshMonth: Int 上次刷新月份

### Requirement: DiscipleStatus 枚举扩展
DiscipleStatus 枚举需要新增 ON_MISSION 状态。

#### Scenario: 状态显示
- **WHEN** 弟子执行任务时
- **THEN** 状态显示为"执行任务中"
