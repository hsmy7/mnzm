# Tasks

- [ ] Task 1: 补全功法技能中文映射与解析
  - [ ] SubTask 1.1: 在 `ItemDetailEffects.kt` 的 `getBuffTypeName(String)` 中增加 `damage_link` / `damage_share` / `shield` / `damage_reduction` / `damage_boost` / `turn_advance` 到中文。
  - [ ] SubTask 1.2: 在 `parseManualStackBuffs` 中增加对上述 buff 类型的解析。
  - [ ] SubTask 1.3: 增加作用目标中文映射函数，覆盖 `self` / `ally` / `enemy` / `team`。
- [ ] Task 2: 统一并补全功法技能详情展示
  - [ ] SubTask 2.1: 在 `ItemDetailEffects.kt` 中改造 `addManualSkillInfo`，补充作用目标、是否全体、固定治疗、护盾、行动提前、伤害分摊、伤害链接、buff 正负号处理。
  - [ ] SubTask 2.2: 更新 `ItemDetailDialog.kt` 中 `ManualTemplate` 分支，复用 `addManualSkillInfo` 或同步补充缺失字段。
  - [ ] SubTask 2.3: 更新 `getManualEffects`（`ManualInstance`）与 `LearnedManualDetailDialog` 的 `ManualStatsContent`，同步展示完整技能信息。
  - [ ] SubTask 2.4: 更新 `ItemDetailOtherEffects.kt` 中 `MerchantItem` / `StorageBagItem` 的 manual 展示，复用 `addManualSkillInfo`。
- [ ] Task 3: 修正丹药一次性/持续时间展示
  - [ ] SubTask 3.1: 在 `getPillEffects` 中调整 `isInstant` 判定，覆盖立即生效的固定数值、治疗、复活、清除、延寿等效果。
  - [ ] SubTask 3.2: 同步调整 `ItemDetailOtherEffects.kt` 中 `MerchantItem` / `StorageBagItem` 的 pill `isInstant` 判定。
- [ ] Task 4: 补充材料/灵草/种子描述
  - [ ] SubTask 4.1: 在 `getMaterialEffects` / `getHerbEffects` / `getSeedEffects` 中加入 `item.description`。
  - [ ] SubTask 4.2: 在 `MerchantItem` / `StorageBagItem` 的 `material` / `herb` / `seed` 分支中加入对应模板或原始描述。
- [ ] Task 5: 增加物品关联配方/产物信息
  - [ ] SubTask 5.1: 在 `getSeedEffects` 中通过 `HerbDatabase.getHerbFromSeedName(seed.name)` 显示“成熟后：{草药名}”。
  - [ ] SubTask 5.2: 在 `getHerbEffects` 中通过 `PillRecipeDatabase.getRecipesByHerb(herb.id)` 显示“可用于炼制：{丹药名}×数量…”。
  - [ ] SubTask 5.3: 在 `getPillEffects` 中通过 `PillRecipeDatabase.getRecipeById(pill.id)` 显示“炼制材料：{草药名}×数量…”。
  - [ ] SubTask 5.4: 在 `DetailEquipmentSection` / `getEquipmentEffects` 中通过 `ForgeRecipeDatabase.getRecipeById(equipment.id)` 显示“锻造材料：{材料名}×数量…”。
  - [ ] SubTask 5.5: 在 `getMaterialEffects` 中通过 `ForgeRecipeDatabase.getRecipesByMaterial(material.id)` 显示“可用于锻造：{装备名}×数量…”。
  - [ ] SubTask 5.6: 在 `MerchantItem` / `StorageBagItem` 对应分支中同步展示上述关联信息。
- [ ] Task 6: 验证
  - [ ] SubTask 6.1: 运行对应模块编译命令，确保无编译错误。
  - [ ] SubTask 6.2: 检查现有测试与 lint/detekt baseline，确认无新增失败。

# Task Dependencies

- Task 2 depends on Task 1
- Task 3、Task 4、Task 5 可并行
- Task 6 depends on Task 2、Task 3、Task 4、Task 5
