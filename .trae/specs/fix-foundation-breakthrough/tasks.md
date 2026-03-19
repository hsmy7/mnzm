# Tasks

- [ ] Task 1: 分析并修复筑基雷劫伤害问题
  - [ ] SubTask 1.1: 确认问题根因：计算炼气10层弟子的实际HP和防御属性
  - [ ] SubTask 1.2: 调整筑基雷劫的伤害计算公式，确保炼气期弟子有合理存活率
  - [ ] SubTask 1.3: 验证修复后的突破成功率在合理范围内

- [ ] Task 2: 添加突破失败的详细日志
  - [ ] SubTask 2.1: 在突破失败时输出具体的失败原因（概率失败/心魔失败/雷劫失败）
  - [ ] SubTask 2.2: 显示心魔试炼的神魂要求和当前值

- [ ] Task 3: 编写单元测试验证修复
  - [ ] SubTask 3.1: 测试炼气10层弟子突破筑基的雷劫存活率
  - [ ] SubTask 3.2: 测试心魔试炼的神魂检查逻辑

# Task Dependencies
- [Task 2] depends on [Task 1]
- [Task 3] depends on [Task 1]
