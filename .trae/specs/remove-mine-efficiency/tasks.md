# Tasks

- [x] Task 1: 修改 calculateMineEfficiency 函数
  - [x] 将函数改为固定返回 1.0
  - [x] 移除境界判断逻辑

- [x] Task 2: 更新 GameEngine 中的基础产出值
  - [x] 修改 processSpiritMineMonthly 函数中的基础产出表
  - [x] 使用指数增长曲线
  - [x] 新数值：仙人250000, 渡劫90000, 大乘35000, 合体13000, 炼虚5000, 化神2000, 元婴800, 金丹300, 筑基120, 炼气50

- [x] Task 3: 更新 SpiritMineScreen 中的基础产出值
  - [x] 修改显示计算中的基础产出表
  - [x] 与 GameEngine 保持一致

- [x] Task 4: 简化 assignDiscipleToSpiritMineSlot 函数
  - [x] 效率直接使用 1.0
  - [x] output 字段保持 100（向后兼容）

- [x] Task 5: 验证产出计算
  - [x] 确认炼气弟子产出为 50 灵石/月（无加成）
  - [x] 确认金丹弟子产出为 300 灵石/月（无加成）
  - [x] 确认仙人弟子产出为 250000 灵石/月（无加成）
