# Tasks

- [x] Task 1: 分析当前秘境掉落逻辑并设计品阶数量配置
  - [x] SubTask 1.1: 分析 `grantExplorationCompletionDropsInTransaction` 方法中的掉落数量逻辑
  - [x] SubTask 1.2: 创建品阶掉落数量配置数据结构

- [x] Task 2: 实现品阶掉落数量细分逻辑
  - [x] SubTask 2.1: 创建获取品阶掉落数量范围的辅助方法
  - [x] SubTask 2.2: 修改功法/装备/丹药掉落数量逻辑
  - [x] SubTask 2.3: 修改草药/种子/材料掉落数量逻辑

- [x] Task 3: 实现妖兽材料掉落数量细分逻辑
  - [x] SubTask 3.1: 创建妖兽材料掉落数量配置数据结构
  - [x] SubTask 3.2: 修改战斗胜利后的材料掉落逻辑

- [x] Task 4: 测试验证掉落数量细分功能
  - [x] SubTask 4.1: 验证各品阶道具掉落数量符合规范
  - [x] SubTask 4.2: 验证代码编译通过

# Task Dependencies
- Task 2 depends on Task 1
- Task 3 depends on Task 1
- Task 4 depends on Task 2 and Task 3
