# Tasks

- [x] Task 1: 添加统一颜色常量到GameColors
  - [x] SubTask 1.1: 添加米色背景色常量 `ButtonBackground = Color(0xFFF5DEB3)`
  - [x] SubTask 1.2: 添加浅棕色边框色常量 `ButtonBorder = Color(0xFFD2B48C)`
  - [x] SubTask 1.3: 添加选中状态金色常量 `SelectedBorder = Color(0xFFFFD700)`

- [x] Task 2: 创建统一的按钮组件
  - [x] SubTask 2.1: 创建 `GameButton` 组件，使用米色背景、黑色字体、浅棕色边框
  - [x] SubTask 2.2: 设置按钮高度为 `32.dp`，字体大小为 `10.sp`
  - [x] SubTask 2.3: 支持禁用状态样式

- [x] Task 3: 统一道具卡片组件
  - [x] SubTask 3.1: 确保 `UnifiedItemCard` 使用正方形尺寸（`68.dp`）
  - [x] SubTask 3.2: 确保选中状态使用金色边框（`3.dp`）
  - [x] SubTask 3.3: 确保查看按钮显示在右上角，样式统一
  - [x] SubTask 3.4: 确保数量显示在右下角

- [x] Task 4: 替换Switch为Checkbox
  - [x] SubTask 4.1: 替换 `MainGameScreen.kt` 中宗门政策的Switch为Checkbox
  - [x] SubTask 4.2: 替换 `TournamentScreen.kt` 中的Switch为Checkbox（已确认使用Checkbox）
  - [x] SubTask 4.3: 确认 `SalaryConfigScreen.kt` 和 `CreateWarTeamDialog.kt` 已使用Checkbox

- [x] Task 5: 统一所有界面的按钮样式
  - [x] SubTask 5.1: 替换 `MainGameScreen.kt` 中的按钮为统一样式
  - [x] SubTask 5.2: 替换 `InventoryScreen.kt` 中的按钮为统一样式
  - [x] SubTask 5.3: 替换其他界面文件中的按钮为统一样式

- [x] Task 6: 验证功能完整性
  - [x] SubTask 6.1: 测试所有界面的按钮样式是否统一
  - [x] SubTask 6.2: 测试所有开关是否已改为勾选框
  - [x] SubTask 6.3: 测试道具卡片的选中状态和查看功能
  - [x] SubTask 6.4: 测试道具详情界面显示正确

# Task Dependencies
- Task 2 依赖 Task 1（需要颜色常量）
- Task 3 可以独立进行
- Task 4 可以独立进行
- Task 5 依赖 Task 1 和 Task 2
- Task 6 依赖所有其他任务
