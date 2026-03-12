# Tasks

- [x] Task 1: 修改赏赐道具界面为单选模式
  - [x] SubTask 1.1: 修改 selectedItems 从 List 改为单个选中项（selectedItem: RewardSelectedItem?）
  - [x] SubTask 1.2: 修改点击逻辑为单选模式（点击选中，再点击取消）
  - [x] SubTask 1.3: 更新 RewardBottomPanel 显示选中的道具名称

- [x] Task 2: 添加数量调整功能
  - [x] SubTask 2.1: 添加 rewardQuantity 状态变量
  - [x] SubTask 2.2: 在 RewardBottomPanel 左侧添加数量调整区域（布局：减按钮 | 数量 | 加按钮）
  - [x] SubTask 2.3: 实现增加数量逻辑（不超过持有数量）
  - [x] SubTask 2.4: 实现减少数量逻辑（不低于1）

- [x] Task 3: 实现连续赏赐功能
  - [x] SubTask 3.1: 移除确认对话框，直接执行赏赐
  - [x] SubTask 3.2: 赏赐后保持界面打开
  - [x] SubTask 3.3: 赏赐后刷新道具列表
  - [x] SubTask 3.4: 赏赐后重置选中和数量状态

- [x] Task 4: 添加每秒修为显示
  - [x] SubTask 4.1: 在 Disciple 模型中添加计算每秒修为的方法
  - [x] SubTask 4.2: 在修炼进度左侧显示每秒修为（如"5.0/秒"）

- [x] Task 5: 移除百分比显示
  - [x] SubTask 5.1: 移除进度条下方的百分比文字

- [x] Task 6: 验证功能完整性
  - [x] SubTask 6.1: 测试单选模式
  - [x] SubTask 6.2: 测试数量调整功能
  - [x] SubTask 6.3: 测试连续赏赐功能
  - [x] SubTask 6.4: 测试每秒修为显示
  - [x] SubTask 6.5: 测试百分比已移除

# Task Dependencies
- Task 2 依赖 Task 1
- Task 3 依赖 Task 1 和 Task 2
- Task 4、Task 5 可以与其他任务并行执行
- Task 6 依赖所有其他任务
