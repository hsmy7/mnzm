# Tasks

- [x] Task 1: 创建AI进攻队伍数据结构
  - [x] SubTask 1.1: 在GameData.kt中新增AIBattleTeam数据类
  - [x] SubTask 1.2: 在GameData中新增aiBattleTeams字段
  - [x] SubTask 1.3: 在WorldSect中新增isUnderAttack、attackerSectId、occupierSectId字段
  - [x] SubTask 1.4: 更新数据库迁移文件

- [x] Task 2: 实现AI宗门进攻决策逻辑
  - [x] SubTask 2.1: 创建AISectAttackManager管理类
  - [x] SubTask 2.2: 实现进攻条件检查方法checkAttackConditions()
  - [x] SubTask 2.3: 实现路线相连判断方法isRouteConnected()（包括占领的宗门）
  - [x] SubTask 2.4: 实现实力对比评估方法calculatePowerScore()
  - [x] SubTask 2.5: 实现进攻概率计算方法calculateAttackProbability()
  - [x] SubTask 2.6: 实现进攻决策方法decideAttacks()

- [x] Task 3: 实现AI宗门进攻队伍组建
  - [x] SubTask 3.1: 实现进攻队伍人数计算（固定10人）
  - [x] SubTask 3.2: 实现弟子选择逻辑（优先高境界）
  - [x] SubTask 3.3: 实现进攻队伍创建方法createAttackTeam()

- [x] Task 4: 实现AI宗门进攻移动逻辑
  - [x] SubTask 4.1: 实现移动进度更新
  - [x] SubTask 4.2: 实现到达检测

- [x] Task 5: 实现AI宗门进攻战斗处理
  - [x] SubTask 5.1: 实现AI防守队伍组建逻辑（固定10人）
  - [x] SubTask 5.2: 实现战斗计算（复用现有战斗系统）
  - [x] SubTask 5.3: 实现战斗结果判定（胜利/失败/平局）

- [x] Task 6: 实现AI宗门进攻结果处理
  - [x] SubTask 6.1: 实现战斗死亡弟子从宗门aiDisciples列表移除
  - [x] SubTask 6.2: 实现宗门占领处理（化神及以上弟子全部死亡，占领后可作为进攻起点）

- [x] Task 7: 实现AI宗门进攻事件通知
  - [x] SubTask 7.1: 实现宗门灭亡事件生成

- [x] Task 8: 实现AI宗门进攻与结盟联动
  - [x] SubTask 8.1: 实现盟友被攻击支援逻辑（每月3.2%概率，好感度>90，必须路线相连，支援队伍固定10人）

- [x] Task 9: 实现AI宗门进攻玩家宗门
  - [x] SubTask 9.1: 实现进攻玩家宗门决策（与AI宗门相同条件，必须路线相连）
  - [x] SubTask 9.2: 实现玩家防守队伍组建（10人，空闲状态，高境界优先，不在思过崖）
  - [x] SubTask 9.3: 实现玩家防守失败后果（仓库掠夺30-50个道具，1000灵石算一个道具）

- [x] Task 10: 修改玩家战斗队伍进攻目标选择
  - [x] SubTask 10.1: 实现玩家可进攻目标计算（玩家宗门+玩家占领宗门的connectedSectIds）
  - [x] SubTask 10.2: 修改WorldMapScreen中高光显示逻辑，只显示路线相连的AI宗门
  - [x] SubTask 10.3: 修改BattleTeamDialog中移动按钮逻辑

- [x] Task 11: 集成到GameEngine
  - [x] SubTask 11.1: 在年度事件中添加进攻决策检查
  - [x] SubTask 11.2: 在月度事件中添加进攻队伍移动更新
  - [x] SubTask 11.3: 添加好感度变化联动

- [x] Task 12: 世界地图UI更新
  - [x] SubTask 12.1: 显示AI进攻队伍移动动画
  - [x] SubTask 12.2: 显示被进攻宗门的战斗标记

# Task Dependencies
- [Task 2] depends on [Task 1]
- [Task 3] depends on [Task 1]
- [Task 4] depends on [Task 3]
- [Task 5] depends on [Task 4]
- [Task 6] depends on [Task 5]
- [Task 7] depends on [Task 6]
- [Task 8] depends on [Task 6]
- [Task 9] depends on [Task 5]
- [Task 10] depends on [Task 1]
- [Task 11] depends on [Task 2, Task 3, Task 4, Task 5, Task 6, Task 7, Task 8, Task 9]
- [Task 12] depends on [Task 11]
