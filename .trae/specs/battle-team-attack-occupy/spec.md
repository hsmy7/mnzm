# 战斗队伍进攻与占领系统 Spec

## Why
玩家需要使用战斗队伍对AI宗门发起进攻并占领，增强游戏的征战玩法。战斗队伍可以移动至相连的AI宗门，与AI宗门弟子发生战斗，胜利后可占领宗门获得资源加成。

## What Changes
- 在战斗队伍详情界面底部增加"移动"按钮
- 点击移动按钮后，与玩家宗门路线相连的AI宗门边框高光显示（红色）
- 点击高光AI宗门后战斗队伍沿路线移动至目标宗门
- 到达目标宗门后与AI宗门弟子发生战斗（25回合制）
- AI宗门组建防御队伍抵御（10人，高境界弟子优先）
- 战斗胜利/失败后战斗队伍返回玩家宗门
- 若AI宗门无化神及以上弟子，战斗队伍驻扎并占领该宗门
- 占领宗门后获得资源加成（根据宗门等级）

## Impact
- Affected specs: add-battle-team, ai-sect-disciple-system
- Affected code:
  - `GameData.kt` - 扩展 BattleTeam 数据模型
  - `GameEngine.kt` - 添加战斗队伍移动和战斗逻辑
  - `GameViewModel.kt` - 添加移动和战斗状态管理
  - `MainGameScreen.kt` - 扩展 BattleTeamDialog 添加移动按钮
  - `WorldMapScreen.kt` - 添加AI宗门高光显示和点击处理

## ADDED Requirements

### Requirement: 战斗队伍详情界面移动按钮
系统 SHALL 在战斗队伍详情界面底部提供移动按钮。

#### Scenario: 显示移动按钮
- **WHEN** 玩家已有战斗队伍且队伍在宗门驻守
- **THEN** 队伍详情界面底部显示"移动"按钮

#### Scenario: 隐藏移动按钮
- **WHEN** 战斗队伍正在移动或战斗中
- **THEN** 不显示"移动"按钮

### Requirement: AI宗门高光显示
系统 SHALL 在点击移动按钮后高光显示可进攻的AI宗门。

#### Scenario: 显示可进攻宗门
- **WHEN** 玩家点击移动按钮
- **THEN** 关闭队伍详情界面
- **AND** 与玩家宗门路线相连的AI宗门边框显示红色高光

#### Scenario: 高光宗门点击
- **WHEN** 玩家点击高光的AI宗门
- **THEN** 战斗队伍开始向该宗门移动
- **AND** 取消所有宗门的高光显示

#### Scenario: 取消移动
- **WHEN** 玩家点击非高光区域
- **THEN** 取消所有宗门的高光显示
- **AND** 不发起进攻

### Requirement: 战斗队伍移动
系统 SHALL 使战斗队伍沿路线移动至目标AI宗门。

#### Scenario: 移动速度
- **WHEN** 战斗队伍开始移动
- **THEN** 移动速度参考探查队伍的移动算法
- **AND** 根据路线距离计算移动时间

#### Scenario: 移动路径
- **WHEN** 战斗队伍移动时
- **THEN** 沿路线（connectedSectIds）计算移动路径
- **AND** 在世界地图上显示队伍移动动画

#### Scenario: 移动中状态
- **WHEN** 战斗队伍正在移动
- **THEN** 队伍状态变为"moving"
- **AND** 队伍在世界地图上显示移动标记

### Requirement: AI宗门防御队伍组建
系统 SHALL 为AI宗门自动组建防御队伍。

#### Scenario: 防御队伍人数
- **WHEN** 战斗队伍到达AI宗门
- **THEN** AI宗门组建10人防御队伍

#### Scenario: 防御队伍选择
- **WHEN** AI宗门组建防御队伍
- **THEN** 优先选择高境界弟子
- **AND** 从最高境界开始依次选择

#### Scenario: 无弟子情况
- **WHEN** AI宗门没有弟子
- **THEN** 防御队伍为空
- **AND** 战斗队伍直接占领该宗门

### Requirement: 战斗队伍与AI宗门战斗
系统 SHALL 在战斗队伍到达AI宗门后发起战斗。

#### Scenario: 战斗回合
- **WHEN** 战斗开始
- **THEN** 战斗进行25回合
- **AND** 使用现有战斗系统进行计算

#### Scenario: 战斗胜利
- **WHEN** 战斗队伍在25回合内击败所有敌人
- **THEN** 战斗队伍胜利
- **AND** 战斗队伍开始返回玩家宗门

#### Scenario: 战斗失败
- **WHEN** 战斗队伍在25回合内被击败
- **THEN** 战斗队伍失败
- **AND** 存活弟子返回玩家宗门

#### Scenario: 回合耗尽
- **WHEN** 25回合结束双方仍有存活
- **THEN** 判定为平局
- **AND** 战斗队伍返回玩家宗门

### Requirement: 战斗队伍返回
系统 SHALL 在战斗结束后使战斗队伍返回玩家宗门。

#### Scenario: 返回移动
- **WHEN** 战斗结束（胜利/失败/平局）
- **THEN** 战斗队伍沿原路线返回玩家宗门
- **AND** 返回速度与出发时一致

#### Scenario: 返回后状态
- **WHEN** 战斗队伍返回玩家宗门
- **THEN** 队伍状态变为"idle"
- **AND** 队伍位置重置为玩家宗门位置

#### Scenario: 死亡弟子移除
- **WHEN** 战斗队伍返回玩家宗门
- **AND** 战斗中有弟子死亡
- **THEN** 死亡弟子从战斗队伍槽位中移除
- **AND** 对应槽位变为空槽位

### Requirement: 战斗队伍补充弟子
系统 SHALL 允许玩家在战斗队伍空槽位中补充弟子。

#### Scenario: 点击空槽位
- **WHEN** 玩家点击战斗队伍中的空槽位
- **THEN** 弹出选择弟子列表界面

#### Scenario: 可选弟子筛选
- **WHEN** 显示选择弟子列表
- **THEN** 仅显示满足以下条件的弟子：
  - 状态为空闲（IDLE）
  - 境界为练气一层以上（realmLayer > 0）
  - 不在思过崖（status != REFLECTING）

#### Scenario: 弟子加入队伍
- **WHEN** 玩家点击弟子列表中的弟子
- **THEN** 该弟子加入战斗队伍对应槽位
- **AND** 弟子状态变为战斗队伍状态
- **AND** 关闭选择弟子列表界面

### Requirement: AI宗门占领
系统 SHALL 在满足条件时允许战斗队伍占领AI宗门。

#### Scenario: 占领条件检查
- **WHEN** 战斗队伍到达AI宗门
- **AND** AI宗门内没有化神（realm=5）及以上的弟子
- **THEN** 战斗队伍驻扎该宗门
- **AND** AI宗门变为玩家宗门

#### Scenario: 占领后宗门状态
- **WHEN** AI宗门被玩家占领
- **THEN** 宗门名称的背景色与玩家宗门一致
- **AND** 宗门标记为已占领状态

#### Scenario: 占领后弟子处理
- **WHEN** AI宗门被玩家占领
- **THEN** AI宗门内的存活弟子变成玩家弟子
- **AND** 这些弟子被收入问道峰成为外门弟子

#### Scenario: 占领后道具处理
- **WHEN** AI宗门被玩家占领
- **THEN** AI宗门仓库内的道具都进入玩家仓库

### Requirement: 占领宗门资源加成
系统 SHALL 根据占领宗门的等级提供资源加成。

#### Scenario: 小型宗门加成
- **WHEN** 玩家占领小型宗门
- **THEN** 灵矿场的灵矿槽位加3

#### Scenario: 中型宗门加成
- **WHEN** 玩家占领中型宗门
- **THEN** 炼丹槽位加3

#### Scenario: 大型宗门加成
- **WHEN** 玩家占领大型宗门
- **THEN** 炼器槽位加3

#### Scenario: 顶级宗门加成
- **WHEN** 玩家占领顶级宗门
- **THEN** 藏经阁槽位加3

### Requirement: 战斗队伍数据模型扩展
系统 SHALL 扩展 BattleTeam 数据模型支持移动和战斗状态。

#### Scenario: 移动状态数据
- **WHEN** 战斗队伍移动时
- **THEN** 记录当前位置、目标位置、移动进度
- **AND** 记录移动路径（经过的宗门ID列表）

#### Scenario: 战斗状态数据
- **WHEN** 战斗队伍战斗时
- **THEN** 记录战斗目标宗门ID
- **AND** 记录战斗状态

## MODIFIED Requirements

### Requirement: BattleTeam 数据模型扩展
BattleTeam 需要支持移动和占领状态。

**修改内容**：
- 新增 `targetSectId: String?` 目标宗门ID
- 新增 `route: List<String>` 移动路径
- 新增 `currentRouteIndex: Int` 当前路径索引
- 新增 `moveProgress: Float` 移动进度
- 新增 `isOccupying: Boolean` 是否正在驻扎占领
- 新增 `occupiedSectId: String?` 占领的宗门ID

### Requirement: WorldSect 数据模型扩展
WorldSect 需要支持被玩家占领状态。

**修改内容**：
- 新增 `isPlayerOccupied: Boolean` 是否被玩家占领
- 新增 `occupierBattleTeamId: String?` 占领的战斗队伍ID

## REMOVED Requirements
无移除的需求。
