# Tasks

## 仓库一键出售按钮
- [x] Task 1: 在仓库界面添加一键出售按钮
  - [x] SubTask 1.1: 在 `MainGameScreen.kt` 的 `WarehouseTab` 函数中添加"一键出售"按钮
  - [x] SubTask 1.2: 创建 `BulkSellDialog` 对话框组件用于选择出售条件
  - [x] SubTask 1.3: 调用 `GameViewModel.bulkSellItems` 方法执行出售

## 招募弟子界面文字修改
- [x] Task 2: 修改招募弟子界面的刷新提示文字
  - [x] SubTask 2.1: 在 `RecruitScreen.kt` 中将"请等待每年刷新"改为"请等待每三年刷新"

## 种子详情界面添加草药信息
- [x] Task 3: 在种子详情界面添加长成后草药的信息
  - [x] SubTask 3.1: 在 `HerbGardenScreen.kt` 的 `SeedDetailDialog` 函数中添加"长成后"信息
  - [x] SubTask 3.2: 使用 `HerbDatabase.getHerbFromSeed` 获取对应草药名称

## 草药详情界面添加丹药用途
- [x] Task 4: 在草药详情界面添加可制作丹药的信息
  - [x] SubTask 4.1: 在 `MainGameScreen.kt` 的 `WarehouseItemDetailDialog` 函数中添加丹药用途显示
  - [x] SubTask 4.2: 使用 `PillRecipeDatabase.getRecipesByHerb` 获取相关丹药列表

## 功法效果描述修复
- [x] Task 5: 修复功法详情界面效果描述不全的问题
  - [x] SubTask 5.1: 在 `DiscipleDetailScreen.kt` 的 `ManualDetailDialog` 函数中检查效果描述
  - [x] SubTask 5.2: 确保所有属性加成（包括cultivationSpeedPercent）都正确显示
  - [x] SubTask 5.3: 检查其他道具详情界面是否有相同问题

## 修炼值显示位置修复
- [x] Task 6: 修复每秒修炼值与修为显示位置
  - [x] SubTask 6.1: 在 `DiscipleDetailScreen.kt` 的 `BasicInfoSection` 中修改显示布局
  - [x] SubTask 6.2: 将每秒修炼值与当前修为/最大修为居中显示
  - [x] SubTask 6.3: 确保与境界名称处于同一行

## 弟子灵根颜色修复
- [x] Task 7: 修复弟子信息界面灵根颜色显示
  - [x] SubTask 7.1: 在 `DiscipleDetailScreen.kt` 的 `BasicInfoSection` 函数中修改灵根颜色显示
  - [x] SubTask 7.2: 使用 `disciple.spiritRoot.countColor` 根据灵根数量显示不同颜色

# Task Dependencies
- Task 1 依赖：SubTask 1.1 → SubTask 1.2 → SubTask 1.3
- Task 3 依赖：SubTask 3.1 → SubTask 3.2
- Task 4 依赖：SubTask 4.1 → SubTask 4.2
- 其他任务可以并行执行
