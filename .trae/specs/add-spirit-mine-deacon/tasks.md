# Tasks

- [x] Task 1: 更新GameData.kt数据模型
  - [x] SubTask 1.1: 在ElderSlots数据类中新增spiritMineDeaconDisciples字段（List<DirectDiscipleSlot>类型）
  - [x] SubTask 1.2: 确保字段默认值为emptyList()

- [x] Task 2: 更新GameViewModel.kt执事管理方法
  - [x] SubTask 2.1: 新增assignSpiritMineDeacon方法用于任命执事
  - [x] SubTask 2.2: 新增removeSpiritMineDeacon方法用于卸任执事
  - [x] SubTask 2.3: 更新isDiscipleInAnyPosition方法，包含执事位置检查

- [x] Task 3: 更新SpiritMineScreen.kt执事UI
  - [x] SubTask 3.1: 新增SpiritMineDeaconSection组件显示执事板块
  - [x] SubTask 3.2: 新增SpiritMineDeaconSlotItem组件显示单个执事槽位
  - [x] SubTask 3.3: 新增SpiritMineDeaconSelectionDialog组件用于选择内门弟子
  - [x] SubTask 3.4: 在SpiritMineDialog中集成执事板块，位于灵矿槽位下方
  - [x] SubTask 3.5: 更新产量计算逻辑，添加执事道德加成

- [x] Task 4: 更新GameEngine.kt产量计算逻辑
  - [x] SubTask 4.1: 在灵矿产量计算中添加执事道德加成计算
  - [x] SubTask 4.2: 计算公式：(道德-50)/5 * 0.02 的加成比例

- [x] Task 5: 修复复查发现的问题
  - [x] SubTask 5.1: 在assignSpiritMineDeacon方法的职位排除列表中添加spiritMineDeaconDisciples

# Task Dependencies
- Task 2 depends on Task 1
- Task 3 depends on Task 2
- Task 4 depends on Task 1
- Task 5 depends on Task 2
