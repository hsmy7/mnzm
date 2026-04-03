# Tasks

- [x] Task 1: 修复 GameViewModel 中 StateFlow 订阅策略
  - [x] SubTask 1.1: 将所有 `SharingStarted.Eagerly` 改为 `SharingStarted.WhileSubscribed(5000)`
  - [x] SubTask 1.2: 验证修改后游戏正常运行

- [x] Task 2: 添加存档操作内存检查
  - [x] SubTask 2.1: 在 GameViewModel 中添加 `canPerformSaveOperation()` 方法
  - [x] SubTask 2.2: 在 `saveGame()` 和 `saveToSlot()` 方法开始时检查内存
  - [x] SubTask 2.3: 内存不足时显示友好提示而非闪退

- [x] Task 3: 优化读档操作流程
  - [x] SubTask 3.1: 在读档前确保游戏循环已停止
  - [x] SubTask 3.2: 在读档前调用 GC 释放临时资源
  - [x] SubTask 3.3: 添加读档操作的内存检查

- [x] Task 4: 添加存档操作超时保护
  - [x] SubTask 4.1: 为 `saveAsync()` 添加超时限制（30秒）
  - [x] SubTask 4.2: 为 `loadAsync()` 添加超时限制（30秒）
  - [x] SubTask 4.3: 超时时显示错误提示而非闪退

- [x] Task 5: 验证修复效果
  - [x] SubTask 5.1: 编译项目确保无语法错误
  - [ ] SubTask 5.2: 测试长时间运行后存档/读档功能

# Task Dependencies
- Task 2 依赖 Task 1 完成（内存检查需要配合正确的订阅策略）
- Task 3 可与 Task 2 并行
- Task 4 可与 Task 2、Task 3 并行
- Task 5 依赖 Task 1-4 全部完成
