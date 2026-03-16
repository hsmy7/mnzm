# Tasks

- [x] Task 1: 修改Disciple.kt中的getBreakthroughChance方法，添加外门长老悟性加成参数
  - [x] SubTask 1.1: 在getBreakthroughChance方法中添加outerElderComprehensionBonus参数（默认值0.0）
  - [x] SubTask 1.2: 在突破率计算中应用外门长老悟性加成

- [x] Task 2: 修改GameEngine.kt，在突破时计算外门长老悟性加成
  - [x] SubTask 2.1: 创建calculateOuterElderComprehensionBonus方法计算外门长老悟性加成
  - [x] SubTask 2.2: 在attemptBreakthrough方法中判断是否为外门弟子，若是则计算并传递外门长老悟性加成

# Task Dependencies
- Task 2 依赖 Task 1（需要先修改getBreakthroughChance方法签名）
