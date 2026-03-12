# 综合UI修复 Spec

## Why
本次修复整合了多个UI相关的问题，包括排序、显示、过滤等方面的缺陷。

## What Changes
1. **弟子列表排序**：将 `sortedBy` 改为 `sortedByDescending`，实现按境界从高到低排序
2. **战斗日志血量显示**：添加 `hp` 和 `maxHp` 字段到 `BattleLogMember` 和 `BattleMemberData`
3. **灵矿场/藏经阁槽位边框**：使用弟子灵根元素颜色作为边框颜色
4. **仓库装备过滤**：过滤掉已穿戴的装备
5. **装备选择界面过滤**：过滤掉已被其他弟子穿戴的装备
6. **战斗日志队伍过滤**：添加 `teamId` 字段，按队伍过滤战斗日志
7. **死亡弟子显示**：保留死亡弟子但显示为灰色槽位
8. **炼器材料匹配**：使用材料名称而不是ID匹配
9. **宗门外交排序**：按好感度从高到低排序

## Impact
- Affected specs: 弟子列表、战斗日志、灵矿场、藏经阁、仓库、装备选择、炼器室、宗门外交
- Affected code: 
  - `MainGameScreen.kt` - DisciplesTab, WarehouseTab, DiplomacyDialog
  - `SectMainScreen.kt` - TeamMemberSlot, ExplorationTeamDialog
  - `SpiritMineScreen.kt` - SpiritMineSlotItem
  - `LibraryScreen.kt` - LibrarySlotItem
  - `DiscipleDetailScreen.kt` - EquipmentSelectionDialog
  - `ForgeScreen.kt` - EquipmentSelectionDialog, EquipmentDetailDialog
  - `BattleSystem.kt` - BattleMemberData, executeBattle
  - `GameEngine.kt` - 创建BattleLog的代码
  - `CultivatorCave.kt` - BattleLog, BattleLogMember

## ADDED Requirements

### Requirement: 弟子列表排序
系统应该按境界从高到低排序弟子列表，境界最高的弟子显示在列表最前面。

#### Scenario: 弟子列表排序正确
- **WHEN** 用户查看弟子列表时
- **THEN** 弟子按境界从高到低排序（仙人 > 渡劫 > 大乘 > 合体 > 炼虚 > 化神 > 元婴 > 金丹 > 筑基 > 炼气）
- **AND** 无境界的弟子排在列表最后

### Requirement: 战斗日志血量显示
战斗日志中应该正确记录并显示弟子的血量信息。

#### Scenario: 战斗详情血条正确显示
- **WHEN** 用户查看战斗详情时
- **THEN** 弟子血条应该根据实际血量百分比显示颜色
- **AND** 血量 > 60% 显示绿色
- **AND** 血量 30%-60% 显示橙色
- **AND** 血量 < 30% 显示红色
- **AND** 死亡弟子血条为空（灰色背景）

### Requirement: 弟子槽位边框颜色
弟子槽位的边框颜色应该根据弟子的灵根元素类型显示对应的颜色。

#### Scenario: 灵矿场/藏经阁弟子槽位边框颜色
- **WHEN** 槽位有弟子时
- **THEN** 边框颜色应该根据弟子的灵根元素类型显示
- **AND** 空槽位显示灰色边框

### Requirement: 仓库装备显示
仓库界面应该只显示未被穿戴的装备。

#### Scenario: 仓库不显示已穿戴装备
- **WHEN** 弟子穿戴装备后
- **THEN** 该装备不应该在仓库界面显示

### Requirement: 装备选择界面过滤
装备选择界面应该只显示未被其他弟子穿戴的装备。

#### Scenario: 装备选择界面不显示已被穿戴的装备
- **WHEN** 弟子打开装备选择界面时
- **THEN** 只显示未被其他弟子穿戴的同类型装备
- **AND** 显示当前弟子已装备的装备（用于更换）

### Requirement: 战斗日志按队伍过滤
战斗日志应该只显示当前队伍的战斗记录。

#### Scenario: 新队伍不显示旧战斗日志
- **WHEN** 新探索队伍被派遣
- **THEN** 战斗日志只显示该队伍的战斗记录

### Requirement: 探索队伍显示死亡弟子
探索队伍界面应该显示死亡的弟子，但以灰色样式显示。

#### Scenario: 探索队伍显示死亡弟子
- **WHEN** 队伍中有弟子死亡
- **THEN** 该弟子的槽位应该显示为灰色
- **AND** 显示"死亡"文字

### Requirement: 炼器材料数量正确显示
炼器界面应该正确显示仓库中材料的数量。

#### Scenario: 炼器材料数量显示正确
- **WHEN** 用户查看炼器配方详情时
- **THEN** 应该显示仓库中对应材料的实际数量

### Requirement: 宗门按好感度排序
宗门外交界面的宗门列表应该按好感度从高到低排序。

#### Scenario: 宗门按好感度排序显示
- **WHEN** 用户打开宗门外交界面时
- **THEN** 宗门按好感度从高到低排序

## MODIFIED Requirements
无

## REMOVED Requirements
无
