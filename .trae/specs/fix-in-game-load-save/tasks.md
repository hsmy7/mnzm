# Tasks

- [x] Task 1: 修复 GameViewModel.loadGame 方法
  - [x] SubTask 1.1: 移除阻止游戏内读档的 `_isGameLoaded` 检查
  - [x] SubTask 1.2: 在读档前停止当前游戏循环
  - [x] SubTask 1.3: 重置 `_isGameLoaded` 标志为 false
  - [x] SubTask 1.4: 确保读档成功后正确设置 `_isGameLoaded` 为 true

- [x] Task 2: 验证修复效果
  - [x] SubTask 2.1: 测试游戏内读取其他存档功能
  - [x] SubTask 2.2: 测试读档后游戏循环正常运行

# Task Dependencies
- [Task 2] depends on [Task 1]
