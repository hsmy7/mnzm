# 弟子自动使用物品响应速度优化与显示问题修复 Spec

## Why
当前弟子自动使用丹药/自动穿戴装备/自动学习功法的响应速度较慢，需要等待每月处理周期才能触发。用户希望这些操作能够在物品进入储物袋时立即执行（毫秒级响应）。同时，弟子自动学习功法后功法槽位没有显示功法，需要修复此问题并检查装备槽位和丹药加成效果是否存在类似问题。

## What Changes
- 将自动使用丹药/自动穿戴装备/自动学习功法的触发时机从每月处理改为物品进入储物袋时立即触发
- 修复功法自动学习后功法槽位不显示功法的问题
- 检查并确保装备槽位显示正确
- 验证丹药使用后弟子获得正确的加成效果

## Impact
- Affected specs: 弟子自动化系统、储物袋系统
- Affected code:
  - `GameEngine.kt` - rewardItemToDisciple, rewardItemsToDisciple, processDiscipleEquipmentAndManuals, processAutoLearnManual
  - `DiscipleDetailScreen.kt` - 功法槽位显示逻辑

## ADDED Requirements

### Requirement: 物品进入储物袋时立即触发自动处理
系统应在物品进入弟子储物袋时立即触发自动使用丹药/自动穿戴装备/自动学习功法的处理。

#### Scenario: 丹药进入储物袋时立即使用
- **GIVEN** 弟子储物袋为空
- **WHEN** 丹药被添加到弟子储物袋
- **THEN** 系统应立即检查并自动使用符合条件的丹药
- **AND** 响应时间应在毫秒级

#### Scenario: 装备进入储物袋时立即穿戴
- **GIVEN** 弟子装备槽位为空或储物袋内有更高品质装备
- **WHEN** 装备被添加到弟子储物袋
- **THEN** 系统应立即检查并自动穿戴更高品质的装备
- **AND** 响应时间应在毫秒级

#### Scenario: 功法进入储物袋时立即学习
- **GIVEN** 弟子功法槽位未满或储物袋内有更高品质功法
- **WHEN** 功法被添加到弟子储物袋
- **THEN** 系统应立即检查并自动学习功法
- **AND** 响应时间应在毫秒级

### Requirement: 功法自动学习后功法槽位正确显示
系统应确保弟子自动学习功法后，功法槽位能够正确显示已学习的功法。

#### Scenario: 功法自动学习后槽位显示
- **GIVEN** 弟子储物袋内有功法道具
- **WHEN** 功法被自动学习
- **THEN** 功法ID应添加到弟子的 manualIds 列表
- **AND** 功法对象应保留在 _manuals 列表中
- **AND** 功法槽位应正确显示功法名称和稀有度

## MODIFIED Requirements

### Requirement: rewardItemToDisciple 函数增强
在将物品添加到弟子储物袋后，立即调用自动处理函数：
- 丹药：调用自动使用丹药逻辑
- 装备：调用自动穿戴装备逻辑
- 功法：调用自动学习功法逻辑

### Requirement: rewardItemsToDisciple 函数增强
批量添加物品后，统一触发自动处理逻辑。

### Requirement: processAutoLearnManual 函数修复
修复功法自动学习后功法对象不在 _manuals 列表中的问题，确保功法槽位能正确显示。

## REMOVED Requirements
无

## Technical Analysis

### 问题根因分析

1. **响应速度问题**
   - 当前 `processDiscipleEquipmentAndManuals` 只在 `processMonthlyEvents` 中被调用
   - 物品通过 `rewardItemToDisciple` 添加到储物袋时，没有触发自动处理
   - 需要在物品添加后立即调用自动处理逻辑

2. **功法槽位显示问题**
   - 功法被添加到储物袋时，从 `_manuals` 列表中移除（第3866行）
   - 自动学习功法时，只将功法ID添加到 `disciple.manualIds`
   - `DiscipleDetailScreen.kt` 第71-73行通过 `allManuals.filter { it.id in disciple.manualIds }` 获取已学功法
   - 由于功法对象已从 `_manuals` 移除，导致无法显示
   - 解决方案：功法学习后应将功法对象重新添加到 `_manuals` 列表

3. **装备槽位显示**
   - 装备穿戴时，装备对象保留在 `_equipment` 列表中（只更新 ownerId 和 isEquipped）
   - 装备槽位通过 `allEquipment.find { it.id == id }` 获取，应该正常显示

4. **丹药加成效果**
   - 丹药加成存储在弟子的 `pillPhysicalAttackBonus` 等字段
   - `getFinalStats` 方法会计算丹药加成（第277-291行）
   - 需要确保自动使用丹药时正确更新这些字段
