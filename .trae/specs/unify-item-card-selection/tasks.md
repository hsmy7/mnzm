# Tasks

- [x] Task 1: 重构 EquipmentSelectionDialog 使用仓库卡片样式
  - [x] SubTask 1.1: 将 Column + verticalScroll + forEach 改为 LazyVerticalGrid（4列）
  - [x] SubTask 1.2: 使用 WarehouseEquipmentCard 组件替代自定义 Box 卡片
  - [x] SubTask 1.3: 调整对话框布局以适应网格布局
  - [x] SubTask 1.4: 确保查看按钮功能正常工作

- [x] Task 2: 重构 ManualSelectionDialog 使用仓库卡片样式
  - [x] SubTask 2.1: 将 Column + verticalScroll + forEach 改为 LazyVerticalGrid（4列）
  - [x] SubTask 2.2: 使用 WarehouseManualCard 组件替代自定义 Box 卡片
  - [x] SubTask 2.3: 调整对话框布局以适应网格布局
  - [x] SubTask 2.4: 确保查看按钮功能正常工作

- [x] Task 3: 优化 EquipmentSlot 和 ManualSlot 组件渲染性能
  - [x] SubTask 3.1: 使用 remember 缓存稀有度颜色计算
  - [x] SubTask 3.2: 优化组件重组，避免不必要的重绘

- [x] Task 4: 验证功能完整性
  - [x] SubTask 4.1: 测试装备选择对话框的选中功能
  - [x] SubTask 4.2: 测试功法选择对话框的选中功能
  - [x] SubTask 4.3: 测试查看详情功能
  - [x] SubTask 4.4: 验证确认装备/学习功法功能正常
  - [x] SubTask 4.5: 验证槽位渲染性能

# Task Dependencies
- Task 2 依赖 Task 1（可以先完成 Task 1 验证方案可行后再执行 Task 2）
- Task 3 可以与 Task 1、Task 2 并行执行
- Task 4 依赖 Task 1、Task 2 和 Task 3
