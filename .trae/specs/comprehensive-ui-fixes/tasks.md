# Tasks

## 弟子列表排序
- [x] Task 1: 修复弟子列表排序逻辑
  - [x] SubTask 1.1: 将 `MainGameScreen.kt` 中 `DisciplesTab` 函数的 `sortedBy` 改为 `sortedByDescending`

## 战斗日志血量显示
- [x] Task 2: 修改 BattleMemberData 数据类添加 hp 和 maxHp 字段
  - [x] SubTask 2.1: 在 `BattleSystem.kt` 中的 `BattleMemberData` 添加 `hp: Int = 0` 和 `maxHp: Int = 0` 字段
- [x] Task 3: 修改 executeBattle 方法记录血量
  - [x] SubTask 3.1: 在 `BattleSystem.kt` 的 `executeBattle` 方法中，创建 `BattleMemberData` 时记录 combatant 的 hp 和 maxHp
  - [x] SubTask 3.2: 在战斗循环中更新 teamMembers 的 hp 值
- [x] Task 4: 修改 BattleLogMember 数据类
  - [x] SubTask 4.1: 在 `CultivatorCave.kt` 的 `BattleLogMember` 添加 `hp: Int = 0` 和 `maxHp: Int = 0` 字段（已存在）
- [x] Task 5: 修改 GameEngine 传递血量值
  - [x] SubTask 5.1: 在 `GameEngine.kt` 创建 `BattleLogMember` 时传递 hp 和 maxHp 值

## 灵矿场/藏经阁槽位边框颜色
- [x] Task 6: 修改灵矿场弟子槽位边框颜色
  - [x] SubTask 6.1: 在 `SpiritMineScreen.kt` 的 `SpiritMineSlotItem` 函数中，当有弟子时使用 `disciple.spiritRoot.countColor` 作为边框颜色
- [x] Task 7: 修改藏经阁弟子槽位边框颜色
  - [x] SubTask 7.1: 在 `LibraryScreen.kt` 的 `LibrarySlotItem` 函数中，当有弟子时使用 `disciple.spiritRoot.countColor` 作为边框颜色

## 仓库装备过滤
- [x] Task 8: 修复仓库界面显示已穿戴装备的问题
  - [x] SubTask 8.1: 在 `MainGameScreen.kt` 的 `WarehouseTab` 函数中，过滤掉 `isEquipped = true` 的装备

## 装备选择界面过滤
- [x] Task 9: 修复装备选择界面显示已被其他弟子穿戴装备的问题
  - [x] SubTask 9.1: 在 `DiscipleDetailScreen.kt` 的 `EquipmentSelectionDialog` 函数中，过滤掉 `isEquipped = true` 且 `ownerId != currentDiscipleId` 的装备

## 战斗日志队伍过滤
- [x] Task 10: 添加 teamId 到 BattleLog
  - [x] SubTask 10.1: 在 `CultivatorCave.kt` 的 `BattleLog` 数据类添加 `teamId: String? = null` 字段
  - [x] SubTask 10.2: 在 `GameEngine.kt` 创建 `BattleLog` 时传递 `teamId`
  - [x] SubTask 10.3: 修改 `SectMainScreen.kt` 中过滤战斗日志的逻辑，使用 `teamId` 过滤

## 死亡弟子显示
- [x] Task 11: 修复探索队伍死亡弟子显示
  - [x] SubTask 11.1: 修改 `SectMainScreen.kt` 中的 `TeamMemberSlot` 函数，当 `disciple.isAlive = false` 时显示灰色槽位
  - [x] SubTask 11.2: 修改 `SectMainScreen.kt` 中获取 teamMembers 的逻辑，保留死亡弟子

## 炼器材料匹配
- [x] Task 12: 修复炼器材料匹配逻辑
  - [x] SubTask 12.1: 在 `ForgeScreen.kt` 的 `EquipmentSelectionDialog` 函数中，将 `materials.associateBy { it.id }` 改为按名称匹配
  - [x] SubTask 12.2: 在 `ForgeScreen.kt` 的 `EquipmentDetailDialog` 函数中，将 `materials.associateBy { it.id }` 改为按名称匹配

## 宗门外交排序
- [x] Task 13: 修复宗门外交界面排序
  - [x] SubTask 13.1: 在 `MainGameScreen.kt` 的 `DiplomacyDialog` 函数中，将 `worldSects` 按 `relation` 从高到低排序

# Task Dependencies
- Task 2-5 依赖关系：Task 2 → Task 3 → Task 4 → Task 5
- Task 10 依赖关系：SubTask 10.1 → SubTask 10.2 → SubTask 10.3
- 其他任务可以并行执行
