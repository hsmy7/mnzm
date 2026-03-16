# Tasks
- [x] Task 1: 修复 _alchemySlots 初始化逻辑
  - [x] SubTask 1.1: 在 initializeGame 方法中，为 _alchemySlots 创建 3 个空闲槽位
  - [x] SubTask 1.2: 确保每个槽位的 slotIndex 正确设置（0, 1, 2）

- [x] Task 2: 修复 startAlchemy 方法中的槽位更新逻辑
  - [x] SubTask 2.1: 当 slotIndex >= currentAlchemySlots.size 时，先用空闲槽位填充到 slotIndex
  - [x] SubTask 2.2: 确保新槽位被设置到正确的索引位置

- [x] Task 3: 添加 alchemySlots 存档持久化
  - [x] SubTask 3.1: 修改 SaveData.kt 添加 alchemySlots 字段
  - [x] SubTask 3.2: 修改 GameViewModel.kt 保存时处理 alchemySlots
  - [x] SubTask 3.3: 修改 GameEngine.kt loadData 方法恢复 alchemySlots
  - [x] SubTask 3.4: 修改 GameViewModel.kt 加载时传递 alchemySlots

- [x] Task 4: 统一其他位置的槽位更新逻辑
  - [x] SubTask 4.1: 修复自动续炼成功时的槽位更新逻辑
  - [x] SubTask 4.2: 修复自动续炼失败时的槽位更新逻辑
  - [x] SubTask 4.3: 修复 startAlchemy(AlchemyRecipe) 方法的槽位更新逻辑

# Task Dependencies
- Task 2 依赖 Task 1
- Task 3 独立
- Task 4 依赖 Task 1
