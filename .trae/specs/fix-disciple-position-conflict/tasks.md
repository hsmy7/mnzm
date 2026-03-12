# Tasks

- [x] Task 1: 创建辅助函数检查弟子是否已任职
  - [x] SubTask 1.1: 在 MainGameScreen.kt 中创建 `isDiscipleInAnyPosition` 函数，检查弟子是否已担任长老或亲传弟子
  - [x] SubTask 1.2: 函数需要检查所有长老槽位（6个）和所有亲传弟子槽位（最多12个）

- [x] Task 2: 修复长老选择对话框的过滤逻辑
  - [x] SubTask 2.1: 修改 `ElderDiscipleSelectionDialog` 组件，传入 elderSlots 参数
  - [x] SubTask 2.2: 在 `filteredDisciplesBase` 过滤条件中添加排除已任职弟子的逻辑
  - [x] SubTask 2.3: 更新调用 `ElderDiscipleSelectionDialog` 的地方，传入 elderSlots

- [x] Task 3: 修复亲传弟子选择对话框的过滤逻辑
  - [x] SubTask 3.1: 修改 `DirectDiscipleSelectionDialog` 组件，传入 elderSlots 参数
  - [x] SubTask 3.2: 在 `filteredDisciplesBase` 过滤条件中添加排除已任职弟子的逻辑
  - [x] SubTask 3.3: 更新调用 `DirectDiscipleSelectionDialog` 的地方，传入 elderSlots

- [x] Task 4: 添加后端验证逻辑 - 长老任命
  - [x] SubTask 4.1: 在 `GameViewModel.assignElder` 函数中添加验证，检查弟子是否已担任其他职位
  - [x] SubTask 4.2: 如果弟子已任职，显示错误提示并阻止任命

- [x] Task 5: 添加后端验证逻辑 - 亲传弟子任命 (GameEngine)
  - [x] SubTask 5.1: 在 `GameEngine.assignDirectDisciple` 函数中添加检查弟子是否已担任长老
  - [x] SubTask 5.2: 如果弟子已担任长老，显示警告并阻止任命

- [x] Task 6: 添加后端验证逻辑 - 亲传弟子任命 (GameViewModel)
  - [x] SubTask 6.1: 在 `GameViewModel.assignDirectDisciple` 函数中添加完整验证
  - [x] SubTask 6.2: 检查弟子是否已担任长老或亲传弟子

# Task Dependencies
- Task 2 和 Task 3 依赖 Task 1（需要先创建辅助函数）
- Task 4, 5, 6 独立，可并行执行
