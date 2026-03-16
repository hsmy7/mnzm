# Tasks

- [x] Task 1: 移除战堂UI文件
  - [x] SubTask 1.1: 删除 WarHallScreen.kt 文件
  - [x] SubTask 1.2: 删除 CreateWarTeamDialog.kt 文件

- [x] Task 2: 移除战堂数据模型
  - [x] SubTask 2.1: 删除 WarTeam.kt 文件（包含WarTeam、WarTeamMember、WarTeamStatus、DefenseDisciple、WarBattleResult、BattleRoundLog、BattleAction、WarCombatantType、WarLoot、Casualty、InjuryType等数据类）

- [x] Task 3: 清理GameEngine中的战堂逻辑
  - [x] SubTask 3.1: 移除 _warTeams StateFlow 和 warTeams 属性
  - [x] SubTask 3.2: 移除 createWarTeam 方法
  - [x] SubTask 3.3: 移除 loadWarTeam 方法
  - [x] SubTask 3.4: 移除 disbandWarTeam 方法
  - [x] SubTask 3.5: 移除 moveWarTeam 方法
  - [x] SubTask 3.6: 移除 recallWarTeam 方法
  - [x] SubTask 3.7: 移除 processWarTeamsMonthly 方法
  - [x] SubTask 3.8: 移除 processWarTeamTravel 方法
  - [x] SubTask 3.9: 移除 executeWarBattle 方法
  - [x] SubTask 3.10: 移除 occupySect 方法
  - [x] SubTask 3.11: 移除 returnTeamToStation 方法
  - [x] SubTask 3.12: 移除 updateWarTeamsSync 方法
  - [x] SubTask 3.13: 清理 GameDataSnapshot 中的 warTeams 相关代码
  - [x] SubTask 3.14: 清理月度处理中的战堂相关调用

- [x] Task 4: 清理GameData和SaveData
  - [x] SubTask 4.1: 从 GameData.kt 移除 warTeams 字段
  - [x] SubTask 4.2: 从 SaveData.kt 移除 warTeams 字段

- [x] Task 5: 清理数据库相关代码
  - [x] SubTask 5.1: 从 GameDatabase.kt 移除 WarTeam 实体和 warTeamDao 方法
  - [x] SubTask 5.2: 从 Daos.kt 移除 WarTeamDao 接口
  - [x] SubTask 5.3: 从 GameRepository.kt 移除战堂相关方法和 warTeamDao 依赖
  - [x] SubTask 5.4: 从 AppModule.kt 移除 WarTeamDao 注入

- [x] Task 6: 清理UI入口
  - [x] SubTask 6.1: 从 MainGameScreen.kt 移除战堂入口按钮和相关状态
  - [x] SubTask 6.2: 从 GameScreen.kt 移除战堂入口按钮和相关状态
  - [x] SubTask 6.3: 从 GameViewModel.kt 移除战堂相关的StateFlow和方法

- [x] Task 7: 清理辅助代码
  - [x] SubTask 7.1: 从 ModelConverters.kt 移除战堂相关转换器
  - [x] SubTask 7.2: 从 DiffUpdateSystem.kt 移除战堂差异比较逻辑

- [x] Task 8: 添加数据库迁移
  - [x] SubTask 8.1: 在 DatabaseMigrations.kt 添加迁移删除 war_teams 表
  - [x] SubTask 8.2: 更新数据库版本号

- [x] Task 9: 验证和测试
  - [x] SubTask 9.1: 运行构建确保无编译错误
  - [x] SubTask 9.2: 检查是否有遗漏的战堂相关引用

# Task Dependencies
- Task 2 应在 Task 3-7 之前完成（移除数据模型后才能清理引用）
- Task 3-7 可以并行进行
- Task 8 应在 Task 5 之后完成（数据库清理后再添加迁移）
- Task 9 应在所有其他任务完成后执行
