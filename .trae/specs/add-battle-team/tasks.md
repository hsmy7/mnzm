# Tasks

- [x] Task 1: 创建 BattleTeam 数据模型
  - [x] SubTask 1.1: 在 GameData.kt 中添加 BattleTeam 数据类，包含10个槽位
  - [x] SubTask 1.2: 在 GameData 类中添加 battleTeam 字段（可选，仅一支队伍）

- [x] Task 2: 在 GameViewModel 中添加队伍管理逻辑
  - [x] SubTask 2.1: 添加战斗队伍状态 StateFlow
  - [x] SubTask 2.2: 实现 getAvailableDisciplesForBattleTeam 方法（筛选空闲、练气一层以上、不在思过崖的弟子）
  - [x] SubTask 2.3: 实现 assignDiscipleToBattleTeamSlot 方法（分配弟子到槽位）
  - [x] SubTask 2.4: 实现 removeDiscipleFromBattleTeamSlot 方法（从槽位移除弟子）
  - [x] SubTask 2.5: 实现 createBattleTeam 方法（组建队伍，验证满10人，限制宗门地址上只能存在一支队伍）
  - [x] SubTask 2.6: 添加队伍管理弹窗状态控制

- [x] Task 3: 创建队伍界面组件
  - [x] SubTask 3.1: 创建 BattleTeamDialog 队伍管理弹窗
  - [x] SubTask 3.2: 实现10个槽位的两行布局
  - [x] SubTask 3.3: 实现空闲槽位显示"+"号
  - [x] SubTask 3.4: 实现已占用槽位显示弟子名称和境界
  - [x] SubTask 3.5: 实现槽位下方"卸任"按钮

- [x] Task 4: 创建弟子选择界面组件
  - [x] SubTask 4.1: 创建 BattleTeamDiscipleSelectionDialog 弟子选择弹窗
  - [x] SubTask 4.2: 实现境界筛选按钮（参考现有 DiscipleSelectionDialog 样式）
  - [x] SubTask 4.3: 实现按最高修为排序（境界从高到低，同境界按层数从高到低）
  - [x] SubTask 4.4: 实现弟子列表显示

- [x] Task 5: 在世界地图中添加组建队伍和管理队伍按钮
  - [x] SubTask 5.1: 在 WorldMapScreen 中添加"组建队伍"按钮（左下角）
  - [x] SubTask 5.2: 在"组建队伍"按钮右侧添加"管理队伍"按钮
  - [x] SubTask 5.3: 已有队伍时隐藏/禁用"组建队伍"按钮，显示"管理队伍"按钮
  - [x] SubTask 5.4: 无队伍时显示"组建队伍"按钮，隐藏/禁用"管理队伍"按钮

- [x] Task 6: 在世界地图中显示战斗队伍标记
  - [x] SubTask 6.1: 在玩家宗门名称上方添加战斗队伍标记
  - [x] SubTask 6.2: 仅当有战斗队伍时显示标记

- [x] Task 7: 集成到 MainGameScreen
  - [x] SubTask 7.1: 在 WorldMapDialog 中添加队伍管理弹窗状态
  - [x] SubTask 7.2: 连接 ViewModel 和界面组件

# Task Dependencies
- [Task 2] depends on [Task 1]
- [Task 3] depends on [Task 2]
- [Task 4] depends on [Task 2]
- [Task 5] depends on [Task 3]
- [Task 6] depends on [Task 1]
- [Task 7] depends on [Task 3, Task 4, Task 5, Task 6]
