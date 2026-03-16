# Tasks

- [ ] Task 1: 定义任务阁数据模型
  - [ ] SubTask 1.1: 在 GameConfig.kt 中添加 TaskHall 配置对象
  - [ ] SubTask 1.2: 创建 TaskData 数据类（任务模型）
  - [ ] SubTask 1.3: 创建 TaskReward 数据类（奖励模型）
  - [ ] SubTask 1.4: 创建 TaskType 枚举（任务类型）

- [ ] Task 2: 扩展现有数据模型
  - [ ] SubTask 2.1: 在 GameData.kt 中添加任务阁相关字段
  - [ ] SubTask 2.2: 在 DiscipleStatus 枚举中添加 ON_MISSION 状态
  - [ ] SubTask 2.3: 在 GameConfig.Buildings 中添加任务阁建筑配置

- [ ] Task 3: 实现任务刷新逻辑
  - [ ] SubTask 3.1: 在 GameEngine 中实现任务生成函数
  - [ ] SubTask 3.2: 在 GameEngine 中实现三月刷新检测逻辑
  - [ ] SubTask 3.3: 实现任务过期清理逻辑

- [ ] Task 4: 实现任务派遣与结算系统
  - [ ] SubTask 4.1: 在 GameEngine 中实现任务派遣函数
  - [ ] SubTask 4.2: 实现成功率计算函数
  - [ ] SubTask 4.3: 实现任务结算函数（成功/失败处理）
  - [ ] SubTask 4.4: 实现奖励发放逻辑

- [ ] Task 5: 实现任务阁UI界面
  - [ ] SubTask 5.1: 创建 TaskHallDialog 组件
  - [ ] SubTask 5.2: 实现任务列表展示
  - [ ] SubTask 5.3: 实现弟子选择对话框
  - [ ] SubTask 5.4: 实现进行中任务显示

- [ ] Task 6: 集成到游戏系统
  - [ ] SubTask 6.1: 在 GameViewModel 中添加任务阁相关状态和方法
  - [ ] SubTask 6.2: 在 MainGameScreen 中添加任务阁入口
  - [ ] SubTask 6.3: 处理弟子死亡时的任务中断

# Task Dependencies
- [Task 3] depends on [Task 1, Task 2]
- [Task 4] depends on [Task 1, Task 2]
- [Task 5] depends on [Task 1, Task 2]
- [Task 6] depends on [Task 3, Task 4, Task 5]
