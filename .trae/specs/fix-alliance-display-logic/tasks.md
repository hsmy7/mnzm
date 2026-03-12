# Tasks

- [x] Task 1: 修复 SectListItem 组件中的盟友判断逻辑
  - [x] SubTask 1.1: 找到 SectListItem 组件的调用位置，确认是否已传递 viewModel 参数
  - [x] SubTask 1.2: 将 `val isAlly = sect.allianceId != null` 修改为使用 viewModel.isAlly(sect.id)
  - [x] SubTask 1.3: 验证修改后的显示逻辑正确

- [x] Task 2: 修复 SectTradeDialog 组件中的盟友判断逻辑
  - [x] SubTask 2.1: 找到 SectTradeDialog 组件的调用位置，确认参数传递
  - [x] SubTask 2.2: 将 `val isAlly = sect?.allianceId != null` 修改为使用正确的 isAlly 参数
  - [x] SubTask 2.3: 验证修改后的交易价格优惠逻辑正确

- [x] Task 3: 编译验证
  - [x] SubTask 3.1: 运行编译确保无错误
  - [x] SubTask 3.2: 确认修改不影响其他功能

# Task Dependencies

- [Task 2] depends on [Task 1]
- [Task 3] depends on [Task 1], [Task 2]
