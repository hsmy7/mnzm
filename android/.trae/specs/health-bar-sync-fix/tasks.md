# 探索队伍血条实时同步修复 - 实现计划

## [x] 任务 1: 分析当前血条显示逻辑
- **Priority**: P0
- **Depends On**: None
- **Description**: 
  - 检查 `SectMainScreen.kt` 中 `TeamMemberSlot` 组件的血条显示逻辑
  - 确认血条如何获取和使用 `disciple.statusData["currentHp"]` 数据
- **Acceptance Criteria Addressed**: AC-2
- **Test Requirements**:
  - `human-judgement` TR-1.1: 确认血条显示逻辑正确读取 `statusData["currentHp"]`
  - `human-judgement` TR-1.2: 确认血条计算逻辑正确
- **Notes**: 了解当前血条显示的实现方式，为后续修复做准备

## [x] 任务 2: 检查战斗后生命值更新机制
- **Priority**: P0
- **Depends On**: 任务 1
- **Description**: 
  - 检查 `GameEngine.kt` 中的 `triggerBattleEvent` 方法
  - 确认战斗后弟子生命值的更新逻辑
  - 识别为什么 `statusData["currentHp"]` 没有被更新
- **Acceptance Criteria Addressed**: AC-1
- **Test Requirements**:
  - `human-judgement` TR-2.1: 确认战斗后弟子生命值的更新逻辑
  - `human-judgement` TR-2.2: 识别 `statusData["currentHp"]` 未更新的原因
- **Notes**: 重点关注战斗结果处理和弟子状态更新的部分

## [x] 任务 3: 修复战斗后生命值更新逻辑
- **Priority**: P0
- **Depends On**: 任务 2
- **Description**: 
  - 修改 `GameEngine.kt` 中的 `triggerBattleEvent` 方法
  - 确保战斗后更新弟子的 `statusData["currentHp"]`
  - 确保修改符合现有代码风格和架构
- **Acceptance Criteria Addressed**: AC-1
- **Test Requirements**:
  - `programmatic` TR-3.1: 代码编译无错误
  - `human-judgement` TR-3.2: 确认修改逻辑正确
- **Notes**: 确保修改最小化，只关注生命值更新部分

## [x] 任务 4: 验证血条实时同步功能
- **Priority**: P1
- **Depends On**: 任务 3
- **Description**: 
  - 构建并运行游戏
  - 测试探索队伍战斗后血条是否实时更新
  - 验证在各种情况下血条是否正确显示当前生命值
- **Acceptance Criteria Addressed**: AC-1, AC-2
- **Test Requirements**:
  - `human-judgement` TR-4.1: 战斗后血条立即更新
  - `human-judgement` TR-4.2: 血条显示与实际生命值一致
- **Notes**: 测试不同战斗场景，确保血条同步功能正常