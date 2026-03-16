# Checklist

- [x] GameEngine中resetAllDisciplesStatus函数正确实现
  - [x] 探索队伍（ExplorationTeam）正确解散，弟子状态变为IDLE
  - [x] 洞府探索队伍（CaveExplorationTeam）正确解散，弟子状态变为IDLE
  - [x] 战斗队伍（BattleTeam）正确解散，弟子状态变为IDLE
  - [x] 工作槽位（BuildingSlot）正确清空，弟子状态变为IDLE
  - [x] 灵矿槽位（SpiritMineSlot）正确清空，弟子状态变为IDLE
  - [x] 藏经阁槽位（LibrarySlot）正确清空，弟子状态变为IDLE
  - [x] 职务槽位（ElderSlots）正确清空，弟子状态变为IDLE
  - [x] 思过崖弟子（REFLECTING状态）不受影响
  - [x] 炼丹槽位（AlchemySlot）无discipleId字段，无需处理

- [x] GameViewModel中正确调用GameEngine的重置方法

- [x] 设置界面正确显示重置弟子状态按钮
  - [x] 按钮样式与其他设置按钮一致
  - [x] 点击按钮显示确认对话框
  - [x] 确认对话框显示正确的提示信息
  - [x] 确认后正确执行重置操作
