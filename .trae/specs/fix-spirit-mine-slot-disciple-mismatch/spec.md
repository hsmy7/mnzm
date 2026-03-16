# 修复弟子采矿状态与槽位显示不一致问题 Spec

## Why
用户反馈弟子处于"采矿中"状态但是采矿槽位没有显示该弟子。这是由于弟子状态（DiscipleStatus.MINING）与灵矿槽位数据（spiritMineSlots中的discipleId）不一致导致的问题。

## What Changes
- 在GameEngine.loadData方法中添加数据一致性校验，确保弟子状态与槽位数据同步
- 添加数据修复逻辑：如果弟子状态为MINING但槽位中没有对应记录，则将弟子状态重置为IDLE
- 添加数据修复逻辑：如果槽位中有discipleId但对应弟子状态不是MINING，则将弟子状态设为MINING
- 确保灵矿执事（spiritMineDeaconDisciples）的数据一致性校验

## Impact
- Affected specs: 弟子状态管理、灵矿槽位显示
- Affected code: 
  - GameEngine.kt (loadData方法)
  - SpiritMineScreen.kt (显示逻辑)

## ADDED Requirements

### Requirement: 数据一致性校验
系统 SHALL 在加载存档时校验弟子状态与灵矿槽位数据的一致性。

#### Scenario: 弟子状态为MINING但槽位无记录
- **WHEN** 加载存档时发现弟子状态为MINING但spiritMineSlots中没有对应的discipleId
- **THEN** 系统应将该弟子状态重置为IDLE

#### Scenario: 槽位有discipleId但弟子状态不是MINING
- **WHEN** 加载存档时发现spiritMineSlots中有discipleId但对应弟子状态不是MINING
- **THEN** 系统应将该弟子状态设置为MINING

#### Scenario: 灵矿执事数据不一致
- **WHEN** 加载存档时发现spiritMineDeaconDisciples中有discipleId但对应弟子状态不是MINING
- **THEN** 系统应将该弟子状态设置为MINING

#### Scenario: 弟子已死亡但槽位仍有记录
- **WHEN** 加载存档时发现槽位中的discipleId对应弟子已死亡（isAlive=false）
- **THEN** 系统应清空该槽位并将弟子状态重置为IDLE

### Requirement: 实时数据修复
系统 SHALL 提供数据修复功能，确保在游戏运行期间数据一致性。

#### Scenario: 打开灵矿界面时校验
- **WHEN** 用户打开灵矿界面
- **THEN** 系统应校验并修复数据不一致问题
