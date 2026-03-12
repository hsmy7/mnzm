# Tasks

- [x] Task 1: 扩展WarTeam模型添加死亡成员追踪
  - [x] SubTask 1.1: 在WarTeam数据类中添加 `deadMemberIds: List<String>` 字段
  - [x] SubTask 1.2: 更新相关的数据库迁移（如果需要）

- [x] Task 2: 修改战斗伤亡处理逻辑
  - [x] SubTask 2.1: 修改 `handleDefeatCasualties` 方法，将死亡弟子ID添加到队伍的 `deadMemberIds`
  - [x] SubTask 2.2: 从弟子主列表 `_disciples` 中移除死亡弟子
  - [x] SubTask 2.3: 确保死亡弟子不出现在弟子选择列表中

- [x] Task 3: 修改队伍解散逻辑
  - [x] SubTask 3.1: 在 `disbandWarTeam` 方法中区分死亡弟子和存活弟子
  - [x] SubTask 3.2: 死亡弟子的装备从 `_equipment` 列表中移除
  - [x] SubTask 3.3: 死亡弟子的功法从 `_manuals` 列表中移除
  - [x] SubTask 3.4: 死亡弟子的储物袋物品消失（随弟子移除自动处理）

- [x] Task 4: 修改队伍UI显示灰色槽位
  - [x] SubTask 4.1: 在 `WarHallScreen.kt` 的 `WarTeamCard` 中检查成员是否在 `deadMemberIds` 中
  - [x] SubTask 4.2: 为死亡成员显示灰色背景和"已阵亡"标记
  - [x] SubTask 4.3: 禁用死亡成员槽位的点击事件

- [x] Task 5: 确保弟子列表过滤死亡弟子
  - [x] SubTask 5.1: 检查所有使用 `disciples` 列表的地方，确保过滤 `isAlive = false` 的弟子
  - [x] SubTask 5.2: 特别检查 `CreateWarTeamDialog` 中的弟子列表过滤

# Task Dependencies
- [Task 2] depends on [Task 1]
- [Task 3] depends on [Task 1]
- [Task 4] depends on [Task 1]
- [Task 5] depends on [Task 2]
