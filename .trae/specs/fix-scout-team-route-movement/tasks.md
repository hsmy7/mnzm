# Tasks

- [x] Task 1: 修复 SupportTeamMarker 坐标计算逻辑
  - [x] SubTask 1.1: 修改 SupportTeamMarker 中的坐标转换，使其与 ScoutTeamMarker 保持一致
  - [x] SubTask 1.2: 将像素坐标正确转换为归一化坐标用于显示

- [x] Task 2: 修复 SupportTeam 创建时的坐标系统
  - [x] SubTask 2.1: 修改 GameEngine.kt 中创建 SupportTeam 时的 targetX/targetY
  - [x] SubTask 2.2: 使用玩家宗门的像素坐标而非硬编码的归一化坐标

- [x] Task 3: 验证探查队伍路线移动逻辑
  - [x] SubTask 3.1: 检查 processScoutTeamsDaily 函数是否正确执行
  - [x] SubTask 3.2: 确认路线计算和位置更新逻辑正确
  - [x] SubTask 3.3: 修复 initializeWorldMap 中 connectedSectIds 为空的问题
  - [x] SubTask 3.4: 修改探查队伍位置计算，使其沿着贝塞尔曲线移动

# Task Dependencies
- [Task 2] depends on [Task 1]
- [Task 3] can run in parallel with [Task 1]
