# Tasks

- [x] Task 1: 扩展 BattleTeam 数据模型
  - [x] SubTask 1.1: 添加 targetSectId、route、currentRouteIndex、moveProgress 字段
  - [x] SubTask 1.2: 添加 isOccupying、occupiedSectId 字段
  - [x] SubTask 1.3: 添加状态计算属性（isMoving、isInBattle、isOccupying）

- [x] Task 2: 扩展 WorldSect 数据模型
  - [x] SubTask 2.1: 添加 isPlayerOccupied 字段
  - [x] SubTask 2.2: 添加 occupierBattleTeamId 字段

- [x] Task 3: 在 GameEngine 中实现战斗队伍移动逻辑
  - [x] SubTask 3.1: 实现 processBattleTeamMovement 方法（参考探查队伍移动逻辑）
  - [x] SubTask 3.2: 实现计算移动路径的方法（根据 connectedSectIds）
  - [x] SubTask 3.3: 在 processDailyEvents 中调用战斗队伍移动处理
  - [x] SubTask 3.4: 实现战斗队伍返回玩家宗门的逻辑

- [x] Task 4: 在 GameEngine 中实现AI宗门防御队伍组建
  - [x] SubTask 4.1: 实现 generateAISectDefenseTeam 方法
  - [x] SubTask 4.2: 选择10名高境界弟子优先组建防御队伍
  - [x] SubTask 4.3: 使用 AI 弟子的真实属性

- [x] Task 5: 在 GameEngine 中实现战斗队伍与AI宗门战斗
  - [x] SubTask 5.1: 实现 triggerBattleTeamCombat 方法
  - [x] SubTask 5.2: 战斗回合限制为25回合
  - [x] SubTask 5.3: 战斗胜利/失败/平局判定
  - [x] SubTask 5.4: 更新双方弟子状态（死亡标记）
  - [x] SubTask 5.5: 战斗队伍返回后移除死亡弟子槽位

- [x] Task 6: 在 GameEngine 中实现AI宗门占领逻辑
  - [x] SubTask 6.1: 检查AI宗门是否无化神及以上弟子
  - [x] SubTask 6.2: 实现占领宗门逻辑（转移道具、弟子）
  - [x] SubTask 6.3: 实现弟子转为玩家外门弟子
  - [x] SubTask 6.4: 实现宗门资源加成（灵矿/炼丹/炼器/藏经阁槽位）

- [x] Task 7: 在 GameViewModel 中添加移动和战斗状态管理
  - [x] SubTask 7.1: 添加 showMoveMode 状态（是否处于选择目标模式）
  - [x] SubTask 7.2: 添加 startBattleTeamMove 方法
  - [x] SubTask 7.3: 添加 selectMoveTarget 方法
  - [x] SubTask 7.4: 添加 cancelMoveMode 方法

- [x] Task 8: 扩展 BattleTeamDialog 添加移动按钮
  - [x] SubTask 8.1: 在界面底部添加"移动"按钮
  - [x] SubTask 8.2: 仅在队伍驻守宗门时显示移动按钮
  - [x] SubTask 8.3: 点击移动按钮后触发移动模式

- [x] Task 9: 在 WorldMapScreen 中实现AI宗门高光显示
  - [x] SubTask 9.1: 添加 movableTargetSectIds 参数
  - [x] SubTask 9.2: 在 MapMarkerItem 中添加高光边框效果（红色）
  - [x] SubTask 9.3: 点击高光宗门触发进攻

- [x] Task 10: 在 WorldMapScreen 中显示移动中的战斗队伍
  - [x] SubTask 10.1: 在地图上显示移动中的战斗队伍标记
  - [x] SubTask 10.2: 显示移动路径（虚线）

- [x] Task 11: 集成到 MainGameScreen
  - [x] SubTask 11.1: 连接移动模式状态到 WorldMapScreen
  - [x] SubTask 11.2: 处理宗门点击事件
  - [x] SubTask 11.3: 更新 BattleTeamDialog 参数

- [x] Task 12: 战斗队伍补充弟子功能
  - [x] SubTask 12.1: 在 BattleTeamDialog 中点击空槽位触发选择弟子
  - [x] SubTask 12.2: 创建战斗队伍弟子选择界面（复用现有选择器逻辑）
  - [x] SubTask 12.3: 筛选条件：空闲、练气一层以上、不在思过崖
  - [x] SubTask 12.4: 选中弟子后更新槽位和弟子状态

# Task Dependencies
- [Task 3] depends on [Task 1]
- [Task 4] depends on [Task 2]
- [Task 5] depends on [Task 3, Task 4]
- [Task 6] depends on [Task 5]
- [Task 7] depends on [Task 1]
- [Task 8] depends on [Task 7]
- [Task 9] depends on [Task 7]
- [Task 10] depends on [Task 1]
- [Task 11] depends on [Task 8, Task 9, Task 10]
- [Task 12] depends on [Task 5, Task 11]
