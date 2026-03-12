# Tasks

- [x] Task 1: 修复 GameEngine.rewardItemsToDisciple 并发问题
  - [x] SubTask 1.1: 添加 Mutex 锁保护赏赐操作
  - [x] SubTask 1.2: 重构循环中的 StateFlow 更新，改为批量更新
  - [x] SubTask 1.3: 确保 disciple 数据修改的原子性

- [x] Task 2: 修复 processDiscipleItemInstantly 数据不一致问题
  - [x] SubTask 2.1: 使用统一的 disciple 快照
  - [x] SubTask 2.2: 将自动处理逻辑改为异步执行
  - [x] SubTask 2.3: 添加异常处理防止崩溃

- [x] Task 3: 添加防重复点击机制
  - [x] SubTask 3.1: 在 RewardItemsDialog 中添加赏赐中状态
  - [x] SubTask 3.2: 赏赐过程中禁用赏赐按钮
  - [x] SubTask 3.3: 赏赐完成后恢复按钮状态

- [x] Task 4: 测试验证
  - [x] SubTask 4.1: 测试单次赏赐功能正常
  - [x] SubTask 4.2: 测试快速连续赏赐不崩溃
  - [x] SubTask 4.3: 测试赏赐不同类型的道具

# Task Dependencies
- Task 2 依赖于 Task 1
- Task 3 可以并行执行
- Task 4 依赖于 Task 1、Task 2、Task 3
