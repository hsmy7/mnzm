# Tasks

- [x] Task 1: 重构 SaveManager 使用文件存储
  - [x] SubTask 1.1: 创建 FileSaveManager 类，实现文件存储逻辑
  - [x] SubTask 1.2: 实现 GZIP 压缩和解压缩功能
  - [x] SubTask 1.3: 实现异步保存和加载机制
  - [x] SubTask 1.4: 实现存档元数据管理（槽位信息）

- [x] Task 2: 实现存档数据清理机制
  - [x] SubTask 2.1: 在保存前自动清理过期战斗日志（保留最近 100 条）
  - [x] SubTask 2.2: 在保存前自动清理过期游戏事件（保留最近 50 条）
  - [x] SubTask 2.3: 清理死亡弟子的储物袋数据

- [x] Task 3: 实现数据迁移机制
  - [x] SubTask 3.1: 检测旧版 SharedPreferences 存档
  - [x] SubTask 3.2: 将旧存档迁移到新文件格式
  - [x] SubTask 3.3: 迁移成功后删除旧存档

- [x] Task 4: 添加错误处理和恢复机制
  - [x] SubTask 4.1: 实现存档失败时的错误提示
  - [x] SubTask 4.2: 实现存档损坏时的恢复提示
  - [x] SubTask 4.3: 添加存档完整性校验

- [x] Task 5: 更新 GameViewModel 存档调用
  - [x] SubTask 5.1: 更新 saveGame 方法使用新的 SaveManager
  - [x] SubTask 5.2: 更新 loadGame 方法使用新的 SaveManager
  - [x] SubTask 5.3: 更新 performAutoSave 方法

- [x] Task 6: 测试和验证
  - [x] SubTask 6.1: 测试大数据量存档（500+ 弟子）
  - [x] SubTask 6.2: 测试旧存档迁移
  - [x] SubTask 6.3: 测试存档压缩效果

# Task Dependencies
- [Task 2] depends on [Task 1]
- [Task 3] depends on [Task 1]
- [Task 4] depends on [Task 1]
- [Task 5] depends on [Task 1, Task 2, Task 3, Task 4]
- [Task 6] depends on [Task 5]
