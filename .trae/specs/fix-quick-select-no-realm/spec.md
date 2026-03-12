# 修复一键选择按钮选择无境界弟子问题 Spec

## Why
1. 一键选择按钮在派遣队伍时会选择无境界弟子（realmLayer == 0 或 age < 5），这不符合游戏逻辑，因为无境界弟子无法参与探索任务。
2. 弟子列表没有按最高境界排序，高境界弟子应该在上面显示。
3. 一键选择应该优先选择最高境界弟子，而不是按列表顺序选择。
4. 多个弟子选择界面存在相同问题：排序逻辑错误、未过滤无境界弟子。
5. 长老和亲传弟子选择界面需要按相关属性排序，高属性弟子显示在上面。

## What Changes
- 修复所有弟子选择界面的过滤和排序逻辑
- 长老和亲传弟子选择界面按相关属性排序（属性优先，属性相同时境界高的在前）

## Impact
- Affected specs: 队伍派遣功能、战堂队伍创建、藏经阁弟子分配、灵矿弟子分配、长老任命、亲传弟子分配
- Affected code: 
  - `SectMainScreen.kt` - DispatchTeamDialog
  - `CreateWarTeamDialog.kt` - 创建战堂队伍对话框
  - `LibraryScreen.kt` - LibraryDiscipleSelectionDialog
  - `SpiritMineScreen.kt` - DiscipleSelectionDialog
  - `MainGameScreen.kt` - ElderDiscipleSelectionDialog, DirectDiscipleSelectionDialog
  - `WarHallScreen.kt` - CreateWarTeamTab

## ADDED Requirements
### Requirement: 弟子选择界面不显示无境界弟子
系统应在所有弟子选择界面中排除无境界弟子（realmLayer == 0 或 age < 5 的弟子）。

#### Scenario: 弟子列表不显示无境界弟子
- **WHEN** 用户打开任意弟子选择界面
- **THEN** 系统应只显示有境界的弟子（realmLayer > 0 且 age >= 5）
- **AND** 无境界弟子不应出现在列表中

### Requirement: 弟子列表按境界从高到低排序
系统应在所有弟子选择界面中按境界从高到低排序弟子列表。

#### Scenario: 高境界弟子显示在上面
- **WHEN** 用户打开任意弟子选择界面
- **THEN** 仙人境界弟子应显示在最上面
- **AND** 渡劫境界弟子应显示在仙人之后
- **AND** 以此类推，炼气境界弟子显示在最下面

#### Scenario: 同境界弟子按层数排序
- **WHEN** 多个弟子处于同一境界
- **THEN** 层数高的弟子应显示在层数低的弟子前面

### Requirement: 一键选择优先选择最高境界弟子
系统应在一键选择时优先选择境界最高的弟子。

#### Scenario: 一键选择按境界从高到低选择
- **WHEN** 用户点击"一键选择"按钮
- **THEN** 系统应优先选择境界最高的弟子（仙人 > 渡劫 > 大乘 > ... > 炼气）
- **AND** 同境界内优先选择层数高的弟子

### Requirement: 长老选择界面按相关属性排序
系统应在长老选择界面中按相关属性从高到低排序弟子列表。属性相同时，境界高的弟子排在前面。

#### Scenario: 灵植长老按灵植属性排序
- **WHEN** 用户选择灵植长老
- **THEN** 弟子列表应按灵植属性从高到低排序
- **AND** 灵植属性相同时，境界高的弟子排在前面

#### Scenario: 炼丹长老按炼丹属性排序
- **WHEN** 用户选择炼丹长老
- **THEN** 弟子列表应按炼丹属性从高到低排序
- **AND** 炼丹属性相同时，境界高的弟子排在前面

#### Scenario: 炼器长老按炼器属性排序
- **WHEN** 用户选择炼器长老
- **THEN** 弟子列表应按炼器属性从高到低排序
- **AND** 炼器属性相同时，境界高的弟子排在前面

#### Scenario: 藏经阁长老按传道属性排序
- **WHEN** 用户选择藏经阁长老
- **THEN** 弟子列表应按传道属性从高到低排序
- **AND** 传道属性相同时，境界高的弟子排在前面

#### Scenario: 灵矿长老按道德属性排序
- **WHEN** 用户选择灵矿长老
- **THEN** 弟子列表应按道德属性从高到低排序
- **AND** 道德属性相同时，境界高的弟子排在前面

#### Scenario: 纳徒长老按魅力属性排序
- **WHEN** 用户选择纳徒长老
- **THEN** 弟子列表应按魅力属性从高到低排序
- **AND** 魅力属性相同时，境界高的弟子排在前面

### Requirement: 亲传弟子选择界面按相关属性排序
系统应在亲传弟子选择界面中按相关属性从高到低排序弟子列表（与长老选择相同的属性排序规则）。属性相同时，境界高的弟子排在前面。
