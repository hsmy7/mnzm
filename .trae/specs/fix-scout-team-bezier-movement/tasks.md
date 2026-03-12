# Tasks

- [x] Task 1: 创建贝塞尔曲线计算工具函数
  - [x] SubTask 1.1: 在 GameEngine.kt 中添加计算贝塞尔曲线控制点的函数
  - [x] SubTask 1.2: 添加计算二次贝塞尔曲线上点位置的函数

- [x] Task 2: 修改 processScoutTeamMovement 使用贝塞尔曲线
  - [x] SubTask 2.1: 获取起点和终点宗门信息
  - [x] SubTask 2.2: 计算贝塞尔曲线控制点
  - [x] SubTask 2.3: 使用贝塞尔公式计算队伍当前位置

- [x] Task 3: 修改 ScoutTeamMarker 使用贝塞尔曲线位置
  - [x] SubTask 3.1: 获取起点宗门和终点宗门坐标
  - [x] SubTask 3.2: 计算与路线绘制一致的控制点
  - [x] SubTask 3.3: 使用贝塞尔公式计算显示位置

# Task Dependencies
- [Task 2] depends on [Task 1]
- [Task 3] depends on [Task 1]
- [Task 2] and [Task 3] can run in parallel
