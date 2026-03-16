# Tasks

- [x] Task 1: 修改炼丹长老速度加成计算逻辑
  - [x] SubTask 1.1: 修改 `calculateElderAndDisciplesBonus` 方法中炼丹部分的速度加成计算
  - [x] SubTask 1.2: 长老速度加成公式改为：以50为基准，每高5点增加1%，每低5点减少1%
  - [x] SubTask 1.3: 亲传弟子速度加成公式改为：以50为基准，每高10点增加1%，每低10点减少1%

- [x] Task 2: 修改炼丹速度加成应用逻辑
  - [x] SubTask 2.1: 修改 `calculateWorkDurationWithAllDisciples` 方法，对于炼丹建筑使用 `calculateElderAndDisciplesBonus` 计算速度加成
  - [x] SubTask 2.2: 确保速度加成正确应用到炼丹时间计算

# Task Dependencies
- [Task 2] depends on [Task 1]
