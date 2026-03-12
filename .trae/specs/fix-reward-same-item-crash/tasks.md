# Tasks

- [ ] Task 1: 修复 GameViewModel.rewardItemsToDisciple 防重复点击机制
  - [ ] SubTask 1.1: 添加 rewardingDiscipleIds 集合跟踪正在赏赐的弟子
  - [ ] SubTask 1.2: 在函数开始时检查并添加弟子ID到集合
  - [ ] SubTask 1.3: 在 finally 块中移除弟子ID

- [ ] Task 2: 修复 DiscipleDetailScreen 中的 isRewarding 状态管理
  - [ ] SubTask 2.1: 使用 LaunchedEffect 管理赏赐异步操作
  - [ ] SubTask 2.2: 确保 isRewarding 在协程完成后才重置为 false
  - [ ] SubTask 2.3: 添加错误处理防止状态无法重置

- [ ] Task 3: 验证 GameEngine 中的数据一致性
  - [ ] SubTask 3.1: 检查 processDiscipleItemInstantlyInternal 的数据快照使用
  - [ ] SubTask 3.2: 确保在 transactionMutex 锁内数据一致性

- [ ] Task 4: 测试验证
  - [ ] SubTask 4.1: 测试单次赏赐功能正常
  - [ ] SubTask 4.2: 测试快速连续赏赐相同道具不崩溃
  - [ ] SubTask 4.3: 测试赏赐不同类型的道具

# Task Dependencies
- Task 2 依赖于 Task 1
- Task 3 可以并行执行
- Task 4 依赖于 Task 1、Task 2、Task 3
