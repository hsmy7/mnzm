# Tasks

- [x] Task 1: 在GameEngine中添加数据一致性校验方法
  - [x] SubTask 1.1: 创建validateAndFixSpiritMineData方法，校验灵矿槽位与弟子状态一致性
  - [x] SubTask 1.2: 处理弟子状态为MINING但槽位无记录的情况
  - [x] SubTask 1.3: 处理槽位有discipleId但弟子状态不是MINING的情况
  - [x] SubTask 1.4: 处理灵矿执事数据不一致的情况
  - [x] SubTask 1.5: 处理弟子已死亡但槽位仍有记录的情况

- [x] Task 2: 在loadData方法中调用数据一致性校验
  - [x] SubTask 2.1: 在loadData方法末尾调用validateAndFixSpiritMineData方法

- [x] Task 3: 在SpiritMineScreen中添加打开时校验
  - [x] SubTask 3.1: 在GameViewModel中添加validateSpiritMineData方法
  - [x] SubTask 3.2: 在SpiritMineDialog初始化时调用校验方法

# Task Dependencies
- Task 2 depends on Task 1
- Task 3 depends on Task 1
