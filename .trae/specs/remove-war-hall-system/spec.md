# 移除战堂系统 Spec

## Why
战堂系统是一个用于宗门征战的战斗队伍管理系统，包含战队创建、驻守、进攻、侦查等功能。根据需求需要移除整个战堂系统，简化游戏功能。

## What Changes
- **BREAKING** 移除战堂相关的所有UI界面（WarHallScreen.kt、CreateWarTeamDialog.kt）
- **BREAKING** 移除战堂数据模型（WarTeam.kt及相关数据类）
- **BREAKING** 移除GameEngine中所有战堂相关逻辑
- **BREAKING** 移除数据库中的war_teams表及相关DAO
- **BREAKING** 移除GameRepository中战堂相关方法
- **BREAKING** 移除GameData中的warTeams字段
- **BREAKING** 移除SaveData中的warTeams字段
- **BREAKING** 移除DiffUpdateSystem中战堂相关的差异比较逻辑
- **BREAKING** 移除ModelConverters中战堂相关的类型转换器
- **BREAKING** 移除MainGameScreen和GameScreen中战堂入口按钮及相关UI
- **BREAKING** 移除GameViewModel中战堂相关的StateFlow和方法
- **BREAKING** 移除AppModule中WarTeamDao的依赖注入
- **BREAKING** 添加数据库迁移以删除war_teams表

## Impact
- 受影响的代码文件:
  - `WarTeam.kt` - 完全删除
  - `WarHallScreen.kt` - 完全删除
  - `CreateWarTeamDialog.kt` - 完全删除
  - `GameEngine.kt` - 移除战堂相关方法和状态
  - `GameData.kt` - 移除warTeams字段
  - `SaveData.kt` - 移除warTeams字段
  - `GameDatabase.kt` - 移除WarTeam实体和DAO
  - `Daos.kt` - 移除WarTeamDao接口
  - `GameRepository.kt` - 移除战堂相关方法
  - `AppModule.kt` - 移除WarTeamDao注入
  - `MainGameScreen.kt` - 移除战堂入口
  - `GameScreen.kt` - 移除战堂入口
  - `ModelConverters.kt` - 移除战堂相关转换器
  - `DiffUpdateSystem.kt` - 移除战堂差异比较
  - `DatabaseMigrations.kt` - 添加迁移删除war_teams表

## ADDED Requirements
### Requirement: 数据库迁移
系统 SHALL 提供数据库迁移以安全删除war_teams表，不影响其他数据。

#### Scenario: 用户升级应用
- **WHEN** 用户从旧版本升级到新版本
- **THEN** war_teams表被安全删除
- **AND** 其他游戏数据保持完整

## REMOVED Requirements
### Requirement: 战堂系统
**Reason**: 根据需求移除战堂系统功能
**Migration**: 
- 已创建的战堂队伍将被清除
- 驻守在宗门的战堂队伍将被解散
- 战堂相关的所有UI入口将被移除
