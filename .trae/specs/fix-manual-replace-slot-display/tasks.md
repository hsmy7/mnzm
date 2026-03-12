# Tasks

- [x] Task 1: 分析功法更换后状态更新流程
  - [x] SubTask 1.1: 检查 `ManualDetailDialog` 中功法更换的回调处理
  - [x] SubTask 1.2: 确认 `showManualDetailDialog` 状态的更新时机
  - [x] SubTask 1.3: 检查 `ManualsSection` 中功法列表的更新逻辑

- [x] Task 2: 修复功法更换后状态同步问题
  - [x] SubTask 2.1: 确保功法更换成功后正确关闭对话框
  - [x] SubTask 2.2: 确保 `learnedManuals` 列表正确更新

- [x] Task 3: 验证修复效果
  - [x] SubTask 3.1: 测试功法更换后功法槽位显示新功法
  - [x] SubTask 3.2: 测试功法更换后重新打开对话框显示正确信息

# Task Dependencies
- Task 2 depends on Task 1
- Task 3 depends on Task 2
