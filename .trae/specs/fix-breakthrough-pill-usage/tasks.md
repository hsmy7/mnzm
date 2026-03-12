# 修复弟子突破大乘境会使用登仙丹的问题 - 实现计划

## [ ] Task 1: 分析丹药数据结构，确认targetRealm属性
- **Priority**: P0
- **Depends On**: None
- **Description**: 
  - 检查丹药数据结构，确认targetRealm属性的定义和使用方式
  - 了解登仙丹等突破丹药的targetRealm值
  - 确认弟子realm属性与丹药targetRealm的对应关系
- **Acceptance Criteria Addressed**: AC-1, AC-2
- **Test Requirements**:
  - `programmatic` TR-1.1: 确认丹药数据中包含targetRealm属性
  - `programmatic` TR-1.2: 确认登仙丹的targetRealm值与大乘境（realm=2）不匹配
- **Notes**: 需要查看PillRecipeDatabase或相关丹药数据定义

## [ ] Task 2: 修改autoUseBreakthroughPills方法，添加境界检查逻辑
- **Priority**: P0
- **Depends On**: Task 1
- **Description**: 
  - 修改GameEngine.kt中的autoUseBreakthroughPills方法
  - 添加逻辑检查丹药的targetRealm是否与弟子当前realm匹配
  - 确保只使用适合当前境界的突破丹药
- **Acceptance Criteria Addressed**: AC-1, AC-2
- **Test Requirements**:
  - `programmatic` TR-2.1: 弟子突破大乘境（realm=2）时不使用登仙丹
  - `programmatic` TR-2.2: 弟子突破时只使用targetRealm匹配的丹药
  - `human-judgement` TR-2.3: 代码修改逻辑清晰，与现有代码风格一致
- **Notes**: 修改应局限在autoUseBreakthroughPills方法中，保持其他逻辑不变

## [ ] Task 3: 测试修复效果
- **Priority**: P1
- **Depends On**: Task 2
- **Description**: 
  - 测试弟子突破大乘境时是否不再使用登仙丹
  - 测试其他境界突破时丹药使用是否正常
  - 测试其他类型丹药的使用逻辑是否不受影响
- **Acceptance Criteria Addressed**: AC-1, AC-2, AC-3
- **Test Requirements**:
  - `programmatic` TR-3.1: 弟子突破大乘境时不使用登仙丹
  - `programmatic` TR-3.2: 弟子突破其他境界时正常使用对应丹药
  - `programmatic` TR-3.3: 其他类型丹药（修炼丹、战斗丹、恢复丹）使用逻辑正常
- **Notes**: 可以通过游戏内测试或单元测试验证修复效果