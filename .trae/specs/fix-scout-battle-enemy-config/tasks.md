# Tasks

- [x] Task 1: 修改探查战斗敌人生成逻辑
  - [x] SubTask 1.1: 修改 `triggerScoutBattle` 函数，从目标宗门的 `aiDisciples` 获取真实弟子
  - [x] SubTask 1.2: 筛选筑基（8）和炼气（9）境界的存活弟子
  - [x] SubTask 1.3: 最多选择20人参与战斗
  - [x] SubTask 1.4: 处理无符合条件弟子的情况（自动成功）

- [x] Task 2: 修改敌人属性计算
  - [x] SubTask 2.1: 使用 `AISectDisciple.getBaseStats()` 获取真实属性
  - [x] SubTask 2.2: 将 `AISectDisciple` 转换为 `BattleEnemy` 格式

- [x] Task 3: 更新战斗结果处理
  - [x] SubTask 3.1: 确保战斗胜利后正确获取宗门信息
  - [x] SubTask 3.2: 确保战斗失败后队伍正确返回

# Task Dependencies
- [Task 2] depends on [Task 1]
- [Task 3] depends on [Task 2]
