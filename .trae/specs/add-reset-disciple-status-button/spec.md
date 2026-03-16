# 重置弟子状态按钮 Spec

## Why
玩家需要一个快速重置弟子状态的功能，以便在需要时能够快速将弟子从各种任务中召回，重新分配任务。目前需要手动逐个解除弟子的任务，效率较低。

## What Changes
- 在设置界面增加"重置弟子状态"按钮
- 点击后弹出确认对话框
- 确认后执行以下操作：
  - 探索队伍中的弟子立即回归宗门，队伍解散，弟子变为空闲状态
  - 战斗队伍中的弟子立即回归宗门，队伍解散，弟子变为空闲状态
  - 工作槽位（炼丹、炼器、灵矿、藏经阁等）中的弟子变为空闲状态，槽位变为空闲
  - 职务槽位（长老、执事等）中的弟子变为空闲状态，槽位变为空闲
  - **思过崖弟子不受影响，保持思过状态**

## Impact
- Affected specs: 无
- Affected code:
  - `MainGameScreen.kt` - 设置界面UI
  - `GameViewModel.kt` - 添加重置弟子状态的函数
  - `GameEngine.kt` - 核心重置逻辑

## ADDED Requirements

### Requirement: 重置弟子状态功能
系统应提供重置弟子状态功能，允许玩家一键将所有非思过状态的弟子重置为空闲状态。

#### Scenario: 重置探索队伍弟子
- **WHEN** 玩家点击重置弟子状态按钮并确认
- **AND** 存在探索中的队伍（ExplorationTeam、CaveExplorationTeam）
- **THEN** 所有探索队伍解散，队伍中的弟子状态变为IDLE，队伍数据从GameData中移除

#### Scenario: 重置战斗队伍弟子
- **WHEN** 玩家点击重置弟子状态按钮并确认
- **AND** 存在战斗队伍（BattleTeam）
- **THEN** 战斗队伍解散，队伍中的弟子状态变为IDLE，battleTeam置为null

#### Scenario: 重置工作槽位弟子
- **WHEN** 玩家点击重置弟子状态按钮并确认
- **AND** 存在工作中的弟子（BuildingSlot、AlchemySlot、SpiritMineSlot、LibrarySlot）
- **THEN** 所有工作槽位变为空闲状态，槽位中的弟子状态变为IDLE

#### Scenario: 重置职务槽位弟子
- **WHEN** 玩家点击重置弟子状态按钮并确认
- **AND** 存在担任职务的弟子（ElderSlots中的各种职务）
- **THEN** 所有职务槽位变为空闲状态，槽位中的弟子状态变为IDLE

#### Scenario: 思过崖弟子不受影响
- **WHEN** 玩家点击重置弟子状态按钮并确认
- **AND** 存在思过状态的弟子（DiscipleStatus.REFLECTING）
- **THEN** 思过崖弟子保持REFLECTING状态不变

#### Scenario: 确认对话框
- **WHEN** 玩家点击重置弟子状态按钮
- **THEN** 显示确认对话框，提示"确定要重置所有弟子状态吗？探索/战斗队伍将解散，工作/职务槽位将清空，思过崖弟子不受影响。"
- **AND** 提供"确认"和"取消"按钮
