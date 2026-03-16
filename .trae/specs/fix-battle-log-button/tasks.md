# Tasks
- [x] Task 1: 在 MainGameScreen.kt 的 QuickActionPanel 中添加战斗日志按钮
  - [x] 在第三行按钮布局中，将 Spacer 替换为战斗日志按钮
  - [x] 按钮点击调用 viewModel.openBattleLogDialog()

- [x] Task 2: 添加战斗日志对话框显示逻辑
  - [x] 添加 showBattleLogDialog 状态监听
  - [x] 添加 BattleLogListDialog 显示逻辑

- [x] Task 3: 将 SectMainScreen.kt 中的组件移动到 MainGameScreen.kt
  - [x] 移动 SecretRealmDialog 及其依赖（SecretRealmCard, DispatchTeamDialog, ExplorationTeamDialog）
  - [x] 移动 BattleLogListDialog 及其依赖（BattleLogListItem, BattleLogDetailDialog 等）

- [x] Task 4: 删除 SectMainScreen.kt 文件

- [x] Task 5: 验证项目编译通过
