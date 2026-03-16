# Tasks

- [x] Task 1: 增强全局异常捕获机制
  - [x] SubTask 1.1: 在 GameActivity 中实现增强的 UncaughtExceptionHandler
  - [x] SubTask 1.2: 崩溃时尝试保存当前游戏数据到紧急存档
  - [x] SubTask 1.3: 标记异常退出状态到 SharedPreferences
  - [x] SubTask 1.4: 记录崩溃日志到文件

- [x] Task 2: 实现崩溃恢复机制
  - [x] SubTask 2.1: 在 MainActivity 启动时检测异常退出标记
  - [x] SubTask 2.2: 检测是否存在紧急存档
  - [x] SubTask 2.3: 显示恢复对话框，让用户选择是否恢复
  - [x] SubTask 2.4: 恢复成功后清理紧急存档和标记

- [x] Task 3: 增强游戏循环异常处理
  - [x] SubTask 3.1: 在 GameViewModel 游戏循环中添加 try-catch 包装
  - [x] SubTask 3.2: 在 GameEngine.processSecondTick() 中添加异常处理
  - [x] SubTask 3.3: 在 GameEngine.advanceDay() 中添加异常处理
  - [x] SubTask 3.4: 记录异常详情到日志，便于排查

- [x] Task 4: 增强存档操作安全性
  - [x] SubTask 4.1: 确保存档写入使用临时文件+原子重命名
  - [x] SubTask 4.2: 添加存档写入锁，防止并发写入
  - [x] SubTask 4.3: 存档失败时保留备份文件
  - [x] SubTask 4.4: 添加存档完整性校验

- [x] Task 5: 增加关键操作日志记录
  - [x] SubTask 5.1: 在关键游戏操作中添加详细日志
  - [x] SubTask 5.2: 记录游戏状态快照到日志
  - [x] SubTask 5.3: 添加性能监控日志（内存、帧率等）

- [x] Task 6: 测试和验证
  - [x] SubTask 6.1: 模拟崩溃场景测试数据保护
  - [x] SubTask 6.2: 测试崩溃恢复流程
  - [x] SubTask 6.3: 测试存档操作原子性
  - [x] SubTask 6.4: 压力测试游戏循环稳定性

# Task Dependencies
- [Task 2] depends on [Task 1]
- [Task 4] depends on [Task 1]
- [Task 6] depends on [Task 1, Task 2, Task 3, Task 4, Task 5]
