# 修复小境界突破使用突破丹药问题 - 实现计划

## [x] Task 1: 修改autoUseBreakthroughPills方法，添加大境界突破判断
- **Priority**: P0
- **Depends On**: None
- **Description**: 
  - 修改 `GameEngine.kt` 中的 `autoUseBreakthroughPills` 方法
  - 在方法开头添加大境界突破判断
  - 如果不是大境界突破，直接返回不使用丹药
  - 使用 `TribulationSystem.isBigBreakthrough(disciple)` 判断是否为大境界突破
- **Acceptance Criteria**: AC-1, AC-2
- **Notes**: 修改应保持代码简洁，与现有代码风格一致

## [x] Task 2: 更新或添加单元测试
- **Priority**: P1
- **Depends On**: Task 1
- **Description**: 
  - 添加或更新测试用例验证小境界突破不使用丹药
  - 验证大境界突破正常使用丹药
  - 验证丹药目标境界匹配逻辑仍然有效
- **Acceptance Criteria**: AC-1, AC-2
- **Notes**: 可以在 `BreakthroughPillTest.kt` 中添加测试用例

# Task Dependencies
- Task 2 depends on Task 1
