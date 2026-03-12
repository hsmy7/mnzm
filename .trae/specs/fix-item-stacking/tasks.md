# Tasks

- [x] Task 1: 创建统一的丹药添加方法
  - [x] SubTask 1.1: 在 GameEngine.kt 中创建 `addPillToWarehouse` 方法，实现丹药堆叠逻辑（匹配名称、品阶、类别）
  - [x] SubTask 1.2: 添加 `cannotStack` 字段检查

- [x] Task 2: 替换所有直接添加丹药的代码
  - [x] SubTask 2.1: 替换炼丹成功时的丹药添加代码（约第1241行）
  - [x] SubTask 2.2: 替换炼丹成功时的丹药添加代码（约第2740行）
  - [x] SubTask 2.3: 替换探索掉落丹药的添加代码 - generateExplorationPillDropSilent
  - [x] SubTask 2.4: 替换从旅行商人购买丹药的添加代码（约第5878行）
  - [x] SubTask 2.5: 替换从宗门交易购买丹药的添加代码（约第9674行）
  - [x] SubTask 2.6: 替换从交易堂下架丹药的添加代码（约第6059行）
  - [x] SubTask 2.7: 替换其他丹药添加代码（交易堂收购、炼丹收集、奖励等）

- [x] Task 3: 修复 Transaction 版本的丹药添加逻辑
  - [x] SubTask 3.1: 修复 generateExplorationPillDropInTransaction 添加 cannotStack 检查

# Task Dependencies
- Task 2 依赖 Task 1
- Task 3 依赖 Task 1
