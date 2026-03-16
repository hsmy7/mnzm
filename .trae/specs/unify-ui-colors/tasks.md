# Tasks

- [x] Task 1: 更新 GameColors 颜色常量
  - [x] SubTask 1.1: 添加 `PageBackground = Color(0xFFF8F5F2)` 界面背景色
  - [x] SubTask 1.2: 更新 `Border = Color(0xFFDCD6D0)` 边框颜色
  - [x] SubTask 1.3: 确认 `ButtonBackground = Color(0xFFF5DEB3)` 按钮米色背景
  - [x] SubTask 1.4: 更新 `ButtonBorder = Color(0xFFDCD6D0)` 按钮边框色

- [x] Task 2: 更新 GameButton 组件
  - [x] SubTask 2.1: 更新按钮边框颜色使用 `GameColors.ButtonBorder`
  - [x] SubTask 2.2: 确认按钮背景使用 `GameColors.ButtonBackground`

- [x] Task 3: 更新主界面背景色
  - [x] SubTask 3.1: 更新 `MainGameScreen.kt` 中所有 `Color.White` 背景为 `GameColors.PageBackground`
  - [x] SubTask 3.2: 更新 `GameScreen.kt` 中所有 `Color.White` 背景为 `GameColors.PageBackground`

- [x] Task 4: 更新各功能界面背景色
  - [x] SubTask 4.1: 更新 `InventoryScreen.kt` 背景色
  - [x] SubTask 4.2: 更新 `AlchemyScreen.kt` 背景色
  - [x] SubTask 4.3: 更新 `ForgeScreen.kt` 背景色
  - [x] SubTask 4.4: 更新 `HerbGardenScreen.kt` 背景色
  - [x] SubTask 4.5: 更新 `SpiritMineScreen.kt` 背景色
  - [x] SubTask 4.6: 更新 `LibraryScreen.kt` 背景色
  - [x] SubTask 4.7: 更新 `RecruitScreen.kt` 背景色
  - [x] SubTask 4.8: 更新 `WorldMapScreen.kt` 背景色
  - [x] SubTask 4.9: 更新 `DiscipleDetailScreen.kt` 背景色
  - [x] SubTask 4.10: 更新其他界面文件背景色

- [x] Task 5: 更新对话框和卡片组件背景色
  - [x] SubTask 5.1: 更新 `ItemCard.kt` 背景色
  - [x] SubTask 5.2: 更新对话框组件背景色
  - [x] SubTask 5.3: 更新卡片组件背景色

- [x] Task 6: 更新边框颜色
  - [x] SubTask 6.1: 更新所有界面中的边框颜色为 `GameColors.Border`
  - [x] SubTask 6.2: 更新分隔线颜色为 `GameColors.Border`

- [x] Task 7: 验证颜色统一性
  - [x] SubTask 7.1: 检查所有界面背景色是否统一为 `#F8F5F2`
  - [x] SubTask 7.2: 检查所有边框颜色是否统一为 `#DCD6D0`
  - [x] SubTask 7.3: 检查所有按钮颜色是否统一为米色 `#F5DEB3`

# Task Dependencies
- Task 2 依赖 Task 1（需要颜色常量）
- Task 3-6 依赖 Task 1（需要颜色常量）
- Task 7 依赖所有其他任务
