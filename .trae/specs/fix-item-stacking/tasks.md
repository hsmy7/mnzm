# Tasks

- [x] Task 1: 修复任务完成时的道具添加（第2028-2043行）
  - [x] SubTask 1.1: 修改 `_pills.value = _pills.value + result.pills` 改为遍历调用 addPillToWarehouse
  - [x] SubTask 1.2: 修改 `_materials.value = _materials.value + result.materials` 改为遍历调用 addMaterialToWarehouse
  - [x] SubTask 1.3: 修改 `_herbs.value = _herbs.value + result.herbs` 改为遍历调用 addHerbToWarehouse
  - [x] SubTask 1.4: 修改 `_seeds.value = _seeds.value + result.seeds` 改为遍历调用 addSeedToWarehouse

- [x] Task 2: 修复占领宗门仓库时的道具添加（第1441-1473行）
  - [x] SubTask 2.1: 修改材料添加逻辑，使用 addMaterialToWarehouse
  - [x] SubTask 2.2: 修改灵药添加逻辑，使用 addHerbToWarehouse
  - [x] SubTask 2.3: 修改种子添加逻辑，使用 addSeedToWarehouse

- [x] Task 3: 修复 addXxxToWarehouse 方法中的新道具添加逻辑（第4657-4709行）
  - [x] SubTask 3.1: 确认 addMaterialToWarehouse 的新道具添加正确
  - [x] SubTask 3.2: 确认 addHerbToWarehouse 的新道具添加正确
  - [x] SubTask 3.3: 修改 addPillToWarehouse 移除 cannotStack 检查，所有丹药可堆叠
  - [x] SubTask 3.4: 确认 addSeedToWarehouse 的新道具添加正确

- [x] Task 4: 修复灵药宛收获时的道具添加（第5075、5099行）
  - [x] SubTask 4.1: 修改 `_herbs.value = _herbs.value + newHerb` 使用 addHerbToWarehouse
  - [x] SubTask 4.2: 修改 `_seeds.value = _seeds.value + newSeed` 使用 addSeedToWarehouse

- [x] Task 5: 修复存储袋道具恢复时的添加逻辑（第8715-8805行）
  - [x] SubTask 5.1: 修改丹药恢复逻辑，使用 addPillToWarehouse
  - [x] SubTask 5.2: 修改材料恢复逻辑，使用 addMaterialToWarehouse
  - [x] SubTask 5.3: 修改灵药恢复逻辑，使用 addHerbToWarehouse
  - [x] SubTask 5.4: 修改种子恢复逻辑，使用 addSeedToWarehouse

- [x] Task 6: 修复兑换码奖励道具添加（第14415-14472行）
  - [x] SubTask 6.1: 修改材料添加逻辑，使用 addMaterialToWarehouse
  - [x] SubTask 6.2: 修改灵药添加逻辑，使用 addHerbToWarehouse
  - [x] SubTask 6.3: 修改种子添加逻辑，使用 addSeedToWarehouse

- [x] Task 7: 修复探索掉落丹药堆叠（第5695-5712行）
  - [x] SubTask 7.1: 移除 generateExplorationPillDropInTransaction 中的 cannotStack 检查

- [x] Task 8: 移除UI层"不可叠加"显示
  - [x] SubTask 8.1: MainGameScreen.kt 移除 cannotStack 显示
  - [x] SubTask 8.2: MerchantScreen.kt 移除 cannotStack 显示
  - [x] SubTask 8.3: InventoryScreen.kt 移除 cannotStack 显示
  - [x] SubTask 8.4: GiftDialog.kt 移除 cannotStack 显示
  - [x] SubTask 8.5: DiscipleDetailScreen.kt 移除 cannotStack 显示

# Task Dependencies
- Task 3 应优先完成，确保 addXxxToWarehouse 方法正确
- Task 1, 2, 4, 5, 6, 7 依赖 Task 3
- Task 8 在所有逻辑修复后完成
