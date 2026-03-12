# Tasks

- [x] Task 1: 创建宗门详情对话框组件 `SectDetailDialog`
  - [x] SubTask 1.1: 设计对话框布局，包含宗门名称、等级、好感度显示
  - [x] SubTask 1.2: 添加侦查信息显示区域（弟子数量、最高境界、境界分布）
  - [x] SubTask 1.3: 添加操作按钮（送礼、结盟、交易、关闭）
  - [x] SubTask 1.4: 处理玩家宗门的特殊显示逻辑

- [x] Task 2: 在 `WorldMapDialog` 中实现宗门点击响应
  - [x] SubTask 2.1: 添加状态变量控制宗门详情对话框显示
  - [x] SubTask 2.2: 实现 `onMarkerClick` 回调，获取点击的宗门数据
  - [x] SubTask 2.3: 传递必要的参数给 `SectDetailDialog`（viewModel、gameData等）

- [x] Task 3: 测试验证功能
  - [x] SubTask 3.1: 验证点击AI宗门显示详情对话框
  - [x] SubTask 3.2: 验证点击玩家宗门显示正确的信息
  - [x] SubTask 3.3: 验证操作按钮功能正常

# Task Dependencies
- Task 2 依赖 Task 1（需要先有 `SectDetailDialog` 组件）
- Task 3 依赖 Task 1 和 Task 2
