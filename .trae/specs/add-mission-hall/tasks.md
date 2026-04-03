# Tasks

- [ ] Task 1: 创建任务数据模型
  - [ ] SubTask 1.1: 创建Mission.kt，定义Mission、MissionType、MissionDifficulty数据类
  - [ ] SubTask 1.2: 定义MissionRewardConfig数据类（灵石、道具概率、材料/草药/种子数量范围）
  - [ ] SubTask 1.3: 定义InvestigateOutcome枚举（妖兽作乱/门派争斗/天命之子/一无所获）
  - [ ] SubTask 1.4: 定义InvestigateRewardConfig数据类（调查事件多结局奖励配置）
  - [ ] SubTask 1.5: 定义ActiveMission数据类，包含进度计算方法（固定5人队伍）
  - [ ] SubTask 1.6: 在DiscipleStatus枚举中添加ON_MISSION状态
  - [ ] SubTask 1.7: 定义RewardItemType枚举（功法/装备/丹药）
  - [ ] SubTask 1.8: 在MissionDifficulty中添加spawnChance、durationMonths、minRealm、allowedPositions属性

- [ ] Task 2: 扩展GameData数据结构
  - [ ] SubTask 2.1: 在GameData中添加missionSlots字段（初始值2）
  - [ ] SubTask 2.2: 在GameData中添加activeMissions字段
  - [ ] SubTask 2.3: 在GameData中添加availableMissions字段
  - [ ] SubTask 2.4: 在GameData中添加lastMissionRefreshYear字段（每年刷新）

- [ ] Task 3: 创建任务系统引擎
  - [ ] SubTask 3.1: 创建MissionSystem.kt，实现任务生成逻辑（黄级50%/玄级43%/地级6%/天级1%）
  - [ ] SubTask 3.2: 实现任务刷新逻辑（每年年初刷新1-7种任务，任务持续一年）
  - [ ] SubTask 3.3: 实现任务派遣功能（验证弟子状态/职务/境界、固定5人队伍）
  - [ ] SubTask 3.4: 实现护送商队奖励（固定灵石：黄级500/玄级1200/地级5000/天级20000）
  - [ ] SubTask 3.5: 实现讨伐妖兽奖励（灵石+概率道具+材料）
  - [ ] SubTask 3.6: 实现宗门巡逻奖励（草药/种子材料，仅黄级/玄级）
  - [ ] SubTask 3.7: 实现镇守城池奖励（功法/装备，仅地级/天级）
  - [ ] SubTask 3.8: 实现调查事件多结局系统（33%妖兽作乱/33%门派争斗/33%天命之子/1%一无所获）
  - [ ] SubTask 3.9: 实现调查事件各结局奖励发放
  - [ ] SubTask 3.10: 实现任务完成处理（计算成功率、发放奖励）
  - [ ] SubTask 3.11: 实现过期任务清理（一年未接取则消失）
  - [ ] SubTask 3.12: 实现槽位扩建功能

- [ ] Task 4: 集成到GameEngine
  - [ ] SubTask 4.1: 在GameEngine中添加MissionSystem实例
  - [ ] SubTask 4.2: 在年度更新流程中添加任务刷新逻辑
  - [ ] SubTask 4.3: 在月度更新流程中添加任务完成检查
  - [ ] SubTask 4.4: 添加任务相关的公开方法供UI调用

- [ ] Task 5: 创建任务阁界面
  - [ ] SubTask 5.1: 创建MissionHallScreen.kt基础结构
  - [ ] SubTask 5.2: 实现任务槽位显示和扩建按钮
  - [ ] SubTask 5.3: 实现进行中任务列表（显示进度、剩余时间、5名弟子）
  - [ ] SubTask 5.4: 实现可接取任务列表（显示详情、难度等级、奖励预览）
  - [ ] SubTask 5.5: 实现弟子选择对话框（根据难度筛选符合条件的弟子）
  - [ ] SubTask 5.6: 实现任务派遣确认流程

- [ ] Task 6: 集成到主界面
  - [ ] SubTask 6.1: 在MainGameScreen中添加任务阁入口按钮
  - [ ] SubTask 6.2: 添加导航路由

- [ ] Task 7: 数据库迁移
  - [ ] SubTask 7.1: 创建数据库迁移脚本（添加新字段默认值）
  - [ ] SubTask 7.2: 更新数据库版本号

- [ ] Task 8: 测试验证
  - [ ] SubTask 8.1: 验证任务派遣流程（固定5人队伍）
  - [ ] SubTask 8.2: 验证弟子筛选（状态/职务/境界条件正确）
  - [ ] SubTask 8.3: 验证任务刷新机制（每年刷新1-7种，概率正确）
  - [ ] SubTask 8.4: 验证任务过期清理（一年未接取消失）
  - [ ] SubTask 8.5: 验证护送商队奖励（固定灵石数量正确）
  - [ ] SubTask 8.6: 验证讨伐妖兽奖励（灵石+概率道具+材料）
  - [ ] SubTask 8.7: 验证宗门巡逻奖励（草药/种子材料）
  - [ ] SubTask 8.8: 验证镇守城池奖励（功法/装备，仅地级/天级）
  - [ ] SubTask 8.9: 验证调查事件多结局系统（四种结局概率正确）
  - [ ] SubTask 8.10: 验证调查事件各结局奖励
  - [ ] SubTask 8.11: 验证槽位扩建功能
  - [ ] SubTask 8.12: 验证难度等级正确显示和计算

# Task Dependencies
- [Task 2] depends on [Task 1]
- [Task 3] depends on [Task 1]
- [Task 4] depends on [Task 2, Task 3]
- [Task 5] depends on [Task 4]
- [Task 6] depends on [Task 5]
- [Task 7] depends on [Task 2]
- [Task 8] depends on [Task 6, Task 7]
