# Tasks

- [x] Task 1: 修改Disciple.kt中的getBreakthroughChance方法
  - [x] SubTask 1.1: 添加innerElderComprehension参数（默认值为0）
  - [x] SubTask 1.2: 在突破率计算中添加内门长老悟性加成逻辑
  - [x] SubTask 1.3: 加成公式：(innerElderComprehension - 50) / 10 * 0.01

- [x] Task 2: 修改GameEngine.kt中的attemptBreakthrough方法
  - [x] SubTask 2.1: 在调用getBreakthroughChance前判断弟子是否为内门弟子
  - [x] SubTask 2.2: 如果是内门弟子，获取内门长老信息并提取其悟性
  - [x] SubTask 2.3: 将内门长老悟性传递给getBreakthroughChance方法

# Task Dependencies
- Task 2 依赖 Task 1（需要先修改getBreakthroughChance方法的签名）
