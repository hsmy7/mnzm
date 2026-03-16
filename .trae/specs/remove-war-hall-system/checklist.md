# 移除战堂系统 Checklist

- [x] WarHallScreen.kt 文件已删除
- [x] CreateWarTeamDialog.kt 文件已删除
- [x] WarTeam.kt 文件已删除
- [x] GameEngine.kt 中所有战堂相关代码已移除
- [x] GameData.kt 中 warTeams 字段已移除
- [x] SaveData.kt 中 warTeams 字段已移除
- [x] GameDatabase.kt 中 WarTeam 实体和 warTeamDao 已移除
- [x] Daos.kt 中 WarTeamDao 接口已移除
- [x] GameRepository.kt 中战堂相关代码已移除
- [x] AppModule.kt 中 WarTeamDao 注入已移除
- [x] MainGameScreen.kt 中战堂入口已移除
- [x] GameScreen.kt 中战堂入口已移除
- [x] ModelConverters.kt 中战堂相关转换器已移除
- [x] DiffUpdateSystem.kt 中战堂差异比较逻辑已移除
- [x] 数据库迁移已添加以删除 war_teams 表
- [x] 项目构建成功，无编译错误
- [x] 无遗漏的战堂相关引用
