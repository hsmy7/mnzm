# 探索队伍界面血条实时同步修复 - 实现计划

## [ ] Task 1: 分析血条显示逻辑
- **Priority**: P0
- **Depends On**: None
- **Description**: 分析 `TeamMemberSlot` 组件中的血条显示逻辑，了解当前如何获取和显示生命值数据
- **Acceptance Criteria Addressed**: AC-1, AC-3
- **Test Requirements**:
  - `programmatic` TR-1.1: 验证血条当前的数据源和更新机制
  - `human-judgement` TR-1.2: 确认血条显示逻辑的正确性
- **Notes**: 重点关注 `TeamMemberSlot` 组件中获取 `currentHp` 的方式

## [ ] Task 2: 检查弟子状态更新机制
- **Priority**: P0
- **Depends On**: Task 1
- **Description**: 检查 GameEngine 中弟子生命值更新的机制，确保 `disciple.statusData["currentHp"]` 在生命值变化时被正确更新
- **Acceptance Criteria Addressed**: AC-1
- **Test Requirements**:
  - `programmatic` TR-2.1: 验证 GameEngine 是否正确更新弟子的生命值状态
  - `programmatic` TR-2.2: 检查 StateFlow 是否在弟子状态变化时发出通知
- **Notes**: 重点关注 `gameEngine.disciples` StateFlow 的更新机制

## [ ] Task 3: 修复血条实时同步问题
- **Priority**: P0
- **Depends On**: Task 2
- **Description**: 根据分析结果，修复血条实时同步问题。可能的修复方案包括：
  1. 确保 `disciple` 对象在生命值变化时被正确更新
  2. 确保 StateFlow 能够捕获到弟子状态的变化
  3. 优化 `TeamMemberSlot` 组件的数据流
- **Acceptance Criteria Addressed**: AC-1, AC-2, AC-3
- **Test Requirements**:
  - `programmatic` TR-3.1: 验证血条在生命值变化时能够实时更新
  - `programmatic` TR-3.2: 验证死亡状态的正确显示
  - `programmatic` TR-3.3: 验证血条计算逻辑的正确性
- **Notes**: 重点关注数据流的完整性和实时性

## [ ] Task 4: 测试和验证
- **Priority**: P1
- **Depends On**: Task 3
- **Description**: 测试修复后的血条显示功能，确保在各种情况下都能正确实时更新
- **Acceptance Criteria Addressed**: AC-1, AC-2, AC-3
- **Test Requirements**:
  - `programmatic` TR-4.1: 测试生命值正常变化时的血条更新
  - `programmatic` TR-4.2: 测试生命值降为0时的死亡状态显示
  - `programmatic` TR-4.3: 测试不同生命值范围的血条颜色变化
- **Notes**: 确保测试覆盖各种边界情况