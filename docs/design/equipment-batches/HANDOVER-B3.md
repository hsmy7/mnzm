# EQ-B3 实施交接文档（HANDOVER）—— 2026-09-29 用户叫停

> **给恢复会话**：本文档是装备体系原子替换批（EQ-B3）中断时的全量交接。派发真源仍是
> `IMPLEMENTATION-BATCHES.md` §4.4 + 方案 HEAD（§3.1/§3.4.2/§3.7/§5.7/§6.5 A1/§9 I1/§14）。
> **工作区 184 个文件已改未提交**（中断现场，未动勿删）；本文档自身也是未跟踪文件，
> 恢复会话验收后可随收官笔一并入库（E13）。

---

## 一、当前状态快照

| 项 | 值 |
|---|---|
| 工作区 | `C:\Mnzm\XianxiaSectNative-equipment`（git worktree），分支 `feat/equipment-set` |
| HEAD | `fa36109fc`（EQ-B2 收官笔，**B3 未提交任何内容**） |
| 树 | **184 文件已改未提交** + 若干未跟踪新文件（见 §三） |
| Kotlin 编译 | `:core:domain` / `:core:data` / `:core:engine` **全绿**；`:feature:game` **189 错**（UI 面，见 §四.3） |
| C++ 编译 | **红**（EquipmentStack/EquipmentNurtureData 从 models.h 删除后消费点未清完，见 §四.4） |
| 已跑测试 | 尚未跑全量门禁；`RoomMigrationV63To64Test` 已写好未执行 |

---

## 二、已完成（可信，编译/已验）

### 写面 A 领域+静态数据（✅ 全部完成）
- **新文件**：`core/domain/.../core/model/EquipStat.kt`（EquipStat 8 维 + EquipStatValue）、
  `EquipAffix.kt`（EquipAffixSet/EquipInstanceMeta/EquipGrowth/**EquipLevelCurve** 等级曲线+消耗+分解返还）
- **Items.kt 重写**：`EquipmentSlot` 六值（HEAD(10)/BODY(11)/HANDS(12)/FEET(13)/WEAPON(14)/LEGS(15) + displayOrder）；
  `EquipmentInstance` 新模型（id(1)/slotId(100)/name(2)/setId(60)/part(61)/growth(62)/meta(63)/ownerId(16)/isEquipped(11)，退役 3/4/7/10/13/14/15/50-56 注释在位）；
  **`EquipmentStack` 保留为 @Deprecated 补偿只读载体**（含新增 nurtureLevel(18) 搬运字段）+ `LegacyEquipmentPrices` 72 条价格快照 + `toLegacyInstance()` 过渡桥；
  `rebuildEquipmentStacks` 已删、`EquipmentStats` 已删
- **DiscipleComponents.EquipmentSet 六部位**（headId/bodyId/handsId/feetId/weaponId/legsId + slotId(part)/setSlotId(part,id)/clearedSlots()）；`Disciple.equipmentNurturing*` 删、`EquipmentNurtureData` @Deprecated
- **DiscipleSerializer**：112-116 接线（withEquipmentUsageFields/Values 双向）；18/19/20/24..27/98/99 退役（保留声明 deprecated 供旧档反序列化）
- **注册表**：`EquipmentDatabase` 重写（SetPieceTemplate 12 部件 + EquipPieceEntry 72 展开条目 + RARITY_PRICES/RARITY_MIN_REALMS）；`EquipmentSetDatabase`（lietian/zifu × 2/4/6 档）、`EquipMainStatPool`（六部位池+系数+基数表）、`EquipAffixPool`（7 项权重不放回）均为新文件；`EquipmentRegistry` 降级纯转发（D1）；`ForgeRecipeDatabase` 12 条（materialsFor/durationFor/successRateFor，TIER_DURATION/TIER_SUCCESS_RATE）；`ForgeRecipeRegistry` 适配
- **`EquipmentFactory`**（core/engine/domain/）：create(setId,part,rarity,rng,discipleRealm)/pickRarity/pickPart/maxWearableRarity——产出唯一入口+境界钳制（S17）
- **gen-templates.mjs D9/D10 收口**：重写为纯中性源驱动，产出**四表**（equipment_db.h/equip_set_db.h/equip_main_stat_db.h/equip_affix_db.h，全部含 operator==/*Mutable() 幂等）；`equipment_db_sample.json` 重写为 12 部件+2 套装+池+词条复合结构；`gen-game-data.mjs` 适配新结构（--check 绿，sha 7c1e5377…）
- **静态四表 + 测试快照已生成落盘**（untracked 新文件 ×3 + equipment_db.h/herb_db.h 重写）

### 写面 B 存档与迁移（✅ 全部完成）
- **`GameDatabaseMigrationsV64.kt` 新增（七步）**：①影子表搬运 `legacy_equipment_stacks`/`legacy_equipment_instances`（CREATE AS SELECT，**方案盲区补齐——见 §五决策 1**）②DROP equipment_stacks ③DROP+CREATE equipment_instances 新模型 ④disciples 增 5 部位列 ⑤清空六槽位（A1 幽灵件兜底）⑥rebuildTableDroppingColumns 删 9 列 ⑦game_data 增 `legacy_equipment_compensated`
- **GameDatabase v64**：版本 63→64、ALL_MIGRATIONS 注册、EquipmentStack 实体+DAO 摘除
- **EquipmentLegacyTableReader**（新，rawQuery 读影子表→EquipmentStack 载体+实例行带 nurtureLevel）；StorageEngineHeavyDataOps 装配影子行（补偿未置位时物化、置位时惰性 dropLegacyTables）；StorageEngineWriteOps 堆叠写回删
- **JsonConverters**：toEquipmentSlot 回退 HEAD+Log.w（D6）；EquipGrowth/EquipInstanceMeta/EquipAffixSet/EquipStatValue 四对 JSON 转换器
- **EquipmentDaos**：StackDao 删、InstanceDao 增 getByPart/getBySet
- **SaveDataReconciler**：堆叠重建仅剩功法；**OldSaveFormatDeserializer**：旧 equipment 一律丢弃（死代码删）
- **规则组**：EquipmentRefRule/EquipmentDedupeRule 六部位重写、EntityCountBoundsRule 装备只告警不截断（warn 800/页面 1200）、**LegacyEquipmentCompensationRule order=27**（四路折算+1 亿上限+补偿邮件+幂等）、**EquipmentValueSanitizeRule order=28**（coerce 口径）；SaveValidationRuleDefaults 注册；RuleContext 单源化
- **GameData.legacyEquipmentCompensated(169)** + `nurture_pills_retired` 邻接列式面（game_data 列在迁移⑦）
- **SaveData.equipmentStacks(53) @Deprecated 载体**（禁改指向；号禁复用）
- OverflowMailSender 登记来源键 `equipment_legacy_compensation`

### 写面 C Kotlin 引擎（✅ core:engine 编译绿）
- **状态四件套** equipmentStacks 全删（MutableGameState/GameStateStore/Unified/SnapshotProvider + GameStateStoreImpl 28 触点 + GameEngine 快照/流）
- **StackKeys.equipment 删、InventoryConfig equipment_stack 删**
- **DiscipleEquipmentService 重写**（单轨六部位；**卸装语义改实例保留表内 isEquipped=false**——C++ disciple_tx.h 必须同语义）；**DiscipleEquipmentManager 重写**（袋实例单源、比较键品阶→套装流派→等级）
- **EquipStatResolver**（新）：resolve(instances)→EquipBonus + resolveSetBonus（2/4/6 档）；applyEquipmentStats/applyEquipBonus 乘区口径（atk=(base+flat)×(1+pct)）
- **EquipmentLevelSystem**（新，纯函数）+ **EquipmentUpgradeService**（新 @GameService，经 CultivationFacade 暴露；升级扣灵石/兽材+强化节点 kEquipment 分区、分解返还 50%）
- **EquipmentNurtureSystem/Service 删除**；CultivationCore 三孕养方法删、PhaseSettlementExecutor/CultivationServiceCoreOps/GameSystemRegistryDefaults 调用点清
- **StatCalculator**：Ops3/Ops4 装备段走 EquipStatResolver；HpMpColumnInput 六槽位（HpMpRecoveryService 构造适配）
- **ActionIds 1486/1487**（catalog core.mjs + 双产物重生成）；**RngPartition.EQUIPMENT(13)** + C++ rng_manager.h 同步
- **Combatant**：critDamageBonus 新增（D3 接线）+ 六名展示字段（head/body/hands/feet/weapon/legsName）
- **CaptiveGearUtils 六件物化**（entry 重建+占位空词条）；**SectWarehouseManager itemId 改实例 id**（0.2-2）
- **产出链全改 EquipmentFactory**：EnemyGenerator/HeavenlyTrial/CaveExploration/LootCalculator（组2 代理）/MissionSystem+RewardOps（MissionResult.equipmentInstances）/DiplomacyService 贸易/RedeemCode/邮件附件分发/洞窟掉落/商店购买/AutoBuy/WarRewards 实例化/StorageBag 敞口/BagItemReconstructor 装备分支删
- **InventorySystem 链**：addEquipmentStack/合并/分块/溢出装备分支删；equip 产能/容量/排序去堆叠；MerchantItemConverter.toEquipment(item, rng) 实例版；InventoryFactories.createEquipmentFromMerchantItem
- **BuildingService/SlotOps**：配方不分 tier——锻造门禁按 maxCraftableTier(workerLevel)、产出 EquipmentFactory 实例、时长按 workerLevel
- **镜像三件**：game_view.proto 六列（67-70 head/body/hands/feet + weaponId=122/legsId=123，71..74/31/32 reserved，EquipmentNurtureDataView 删）；gameview_encode.cpp 字段表同步；GameViewDiscipleRows/GameViewMirrorCodec 全部六部位+删孕养视图；StateSyncService equipmentStacks 容器与集合删
- **存读档链**：loadData 签名/SaveFacadeImpl/SaveService 统计/StorageEngine*.kt 全适配

### 写面 F 部分完成
- **`RoomMigrationV63To64Test`**（新，3 用例：真实 Room 校验/七步断言/幂等；种子 82 列+堆叠 2 行+实例 1 行）——**已写未跑**
- **`EquipmentProtoNumberFrozenTest`⑥ 翻转**：112..116 必写 + 退役字段禁写（buildSurrogate 源码扫描）
- **core:data 测试面 11 文件已适配**：SaveDataReconcilerTest 重写（仅剩功法）、EquipmentRefRuleTest 重写六部位、SaveValidatorTest/CultivationCapRuleTest/DiscipleDeadStatusRuleTest/NumericSanitizeRuleTest/RuleContextTest/SaveValidatorIntegrationTest/ArchivePayloadRoundTripTest/CloudPayloadSizeBenchTest
- **ui-read-surface.md §2.2/§3.3 登记**（equipmentStacks 移出镜像面 + B3 新模型协议说明）；规范门禁 agent-instructions 已预检绿

### 记忆
- `realtime-settlement-implementation.md` 已存 B3 实施快照（含教训四条）。

---

## 三、未完成（恢复会话的作业清单，按优先序）

### 1. C++ 全链（写面 D，**最大缺口**）——cmake 编译红
前批代理已完成：models.h（新结构落位）、disciple_store.h/.cpp 六列、column_dirty.h、gameview_encode.cpp 字段表、静态四表、rng kEquipment=13、action_ids.h 1486/1487。**剩余全部**：
- `state/json_codec.h/.cpp`：EquipmentStack/EquipmentNurtureData 声明与实现删（编译错首怖位）；EquipmentInstance 新字段编解码（JSON 键与 kotlinx 驼峰一致：setId/part/growth{level,exp,affix{mainStat{stat,value},subStats[],subRolls[]}}/meta{rarity,minRealm,description,isLocked}）；GameState equipmentStacks 容器删；Disciple EquipmentSet 六列
- `system/inventory.h`：equipmentKey(156)/999 上限(672)/equipmentStacks 计数槽位/addEquipmentStack 全删
- `system/disciple_tx.h`：equipTransaction/unequipTransaction 单轨六部位重写（**卸装=实例保留表内 isEquipped=false+袋条目**，对齐 Kotlin DiscipleEquipmentService）
- `system/equipment_tx.h` **新建**：upgrade/dismantle 事务——**先读 Kotlin `service/EquipmentUpgradeService.kt` 与 `EquipmentLevelSystem.kt` 逐位对齐**（灵石 100×rarity²×level、兽材 max(1,level/10) BEAST 全类目 (rarity,id) 升序扣、expRequired=100×level×rarityMul[1.0/1.5/2.0/3.0/4.5/6.0]、newLevel%3 强化 kEquipment nextInt(subStats.size) 上限 11、分解返还 50% floor、信封对齐 DiscipleTxResult）
- `system/auto_gear.h`：候选=袋实例；比较键 品阶→套装流派（innateDamageType vs setId=="lietian"）→level；六列写
- `system/disciple_stats.h`：删 nurtureMultiplier/旧 equipmentFinalStats；**先读 Kotlin `EquipStatResolver.kt`** 逐位实现 totalBonus+套装档位
- `system/ai_sect_ops.h`/`ai_sect_recruit.h`：孕养三函数删；六列单 id；AI 轻量实例（查表+占位空词条）
- `system/nurture_constants.h`：装备族删（熟练度族留）
- `system/phase_settlement.h`：applyNurtureExp/processEquipmentNurture+调用点(1095/1194)+战前按秒段(1449-1451) 删
- `system/mission_completion.h`：孕养等级表(639-652)+nurtureLevel 写(664) 删→EquipmentFactory 等价
- `src/execute_dispatch.cpp`：INV_ADD_EQUIPMENT_STACK case 适配/删；handleDiscipleTx 加 1486/1487；区间上界 1485→1487
- `data/data_inject.h` 58-62：equipment 注入适配新 JSON 结构（对齐 gen-game-data 新 db.equipment：setPieces/sets/mainStatPools/mainStatBase/subAffixes）
- battle_calculator.h/battle_json.h：critDamageBonus 接线（`critMult = 1 + kCritBaseMultiplier + critDamageBonus`，默认 0.0 逐位一致）
- **GTest**：equipment_db_test.cpp 重写（12/2/6/7 断言）；新 equipment_tx_test/equip_affix_test（前缀和+nextInt(total) 不放回 3 条）/equip_main_stat_test/equip_set_bonus_test（0..6 件）；disciple_tx_test/ai_sect_ops_test/phase_settlement_test 孕养用例删+装备适配
- 验证：llvm-mingw PATH + `cmake --build build` 迭代零错 → `ctest` 全绿 → `pwsh -NoProfile -File scripts/build-desktop-jni.ps1`（仓库根）→ engine Diff 门

### 2. UI 面（写面 E，`:feature:game` **189 错**）——代理被停时未落盘
错误分布（`/tmp/ui_errs_final.txt` 有全清单）：ItemDetailOtherEffects 39 / ForgeDialog 32 / ItemDetailDialog 30 / DiscipleDetailScreen 25 / ItemDetailEffects 14 / HeavenlyTrialBattleDialog 12 / ForgeViewModel 12 / DetailBasicInfoSection 9 / ReplaceSelectionData 6 / 其余 8 文件零星。
**修法指南**（交接 prompt 已验证可直接复用）：
- 旧 7 项面板字段（physicalAttack 等）/getFinalStats/stats/totalMultiplier/nurtureLevel → `totalBonus()` 遍历（EquipStatValue.toString 已给中文摘要）或 `bonusDescription`
- ForgeRecipe 新形状：type/tier/rarity/materials/duration/successRate → part/setId + materialsFor(tier)/durationFor(tier)/successRateFor(tier)（UI tier 取工作弟子 forgeLevel 或 1）
- Combatant armorName/bootsName/accessoryName → 六名；getTemplateByName → EquipmentDatabase.entries/getById
- equipmentStacks 流消费 → equipmentInstances；watchKey("equipment", name) → 实例 id
- DiscipleDetailScreen 四槽 → `EquipmentSlot.displayOrder` 遍历 + `equipment.slotId(part)`
- 弟子四槽字段扩展函数（accessoryId/armorId/bootsId imports）删，用 `equipment.slotId(part)`
- UI 语义从简原则：详情=套装·部件名+Lv+词条摘要；完整升级/分解对话框可留待续批（编译优先）
- **未做的 UI 加分项**（可留 B5 或续批）：EquipmentSetBonusPanel 套装面板、WarehouseTab 筛选排序批量分解、resource-registry.json 12 部件精灵图占位（I6 程序化占位）

### 3. 剩余 core:data 测试错（~6 锚点）
- SaveValidatorTest 374（armorId 断言改 headId）+ makeDisciple 51-54 残参 + 241/253/277/362 调用点 armorId 实参 → 六部位等价改写（如 armorId→headId）或删参
- **先跑** `:core:data:compileReleaseUnitTestKotlin` 看实时清单（部分文件可能已顺带修复）

### 4. 其余测试面（写面 F 主体，编译驱动）
- core:engine 测试面尚未编译过（EquipmentNurtureSystemTest 等孕养测试文件需删/改、CaptiveGearMaterializationTest/Diff* fixture/TemplateRegistryGuardTest↔新 72 条快照/StackKeysTest/MergeStackableTest/ItemsTest/EquipmentDatabaseTest/ForgeRecipeDatabaseTest 等 domain 面测试）——**全是编译驱动修**
- 新增守卫/单测（方案 §6.1 清单中 B3 范围）：EquipmentSlotOrderGuardTest、EquipmentStackRemovalGuardTest、EquipmentSingleSourceGuardTest、TemplateCodegenIntegrityGuardTest、EquipmentLevelPersistGuardTest、EquipmentSetBonusTest(0-6 件)、EquipmentValueSanitizeRuleTest（配套规则已有）、EquipMainStatPoolTest、EquipAffixPoolTest、EquipmentSetDatabaseTest、EquipmentRarityGateTest、EquipmentNoCapGuardTest、EquipmentLegacyCompensationTest、EquipmentLevelSystemTest、EquipmentUpgradeServiceTest、EquipStatResolverTest、DiffEquipmentGeneration/Upgrade/SetBonus 三对拍（依赖 C++ 完成）
- `RoomMigrationV63To64Test` 执行验证 + `RoomMigrationTest` 全链尾追加 v64
- 旧用例处置表（三类分组：EquipmentStack 删除/孕养→升级/四槽→六部位）——报告必需件

### 5. 门禁全套（§2 派发件）+ 报告 + 收官
- compileReleaseKotlin / 六模块 JVM 全量（jni.path 指 worktree .so，**先 build-desktop-jni.ps1 重编**）/ ctest 全量 / lint+detekt（baseline 只缩不增）/ jni-count（87 基线；新增 JNI 口才需重锚+豁免）/ agent-instructions / **G0 严格判据：gen-templates 重跑 git diff 零差异（本批 D9/D10 收口验证）** / gen-game-data --check
- 报告 `docs/design/equipment-batches/reports/report-B3.md`：门禁实测原数字 + 旧用例处置表三类分组 + **「不可回退」公告文案预留**（方案 §9 I1）+ §五决策记录（尤其影子表）+ 诚实归因
- 单笔收官 `feat(equip): B3 装备体系原子替换——<要点>`；构建副产物（atlas-rgba-manifest.json 当前在树！**提交前 git checkout -- 还原**）；双 changelog **归 B5 不动**

---

## 四、当前精确错误清单（恢复起点）

- Kotlin 全模块：`:feature:game` 189 错（§三.2），**其余模块 0 错**
- core:data 单测编译：约 6 锚点（§三.3）
- C++：`cmake --build build` 红（json_codec.h/inventory.h 首报，error-limit 截断；全量估计 100+）

## 五、关键决策记录（恢复会话必须遵守，勿翻案）

1. **影子表方案（方案盲区补齐）**：补偿规则链跑在 Room 装配出的 SaveData 上，若迁移直接 DROP 影子行则 Room 主链旧装备补偿不可达 → MIGRATION_63_64 第①步 CREATE TABLE legacy_equipment_stacks/legacy_equipment_instances AS SELECT 保行；读档装配物化进 `SaveData.equipmentStacks`（deprecated 载体）；补偿置位后 `EquipmentLegacyTableReader.dropLegacyTables()` 惰性清。**EquipmentStack 类型必须保留 @Deprecated**（连同 SaveData(53)）——全删会断补偿，这是与方案字面（S12 符号归零）的有意偏差，报告须登记。
2. **卸装语义改为实例保留表内**（isEquipped=false，Kotlin 已实施）——C++ disciple_tx.h 必须同语义，否则对拍红。
3. **ForgeRecipe 不分 tier**：12 条配方 × materialsFor(tier)，产出品阶=锻造弟子 forgeLevel（1..6），晋升/时长/成功率全部按 workerLevel/maxTier 取档。
4. **Combatant 装备展示六名**（head/body/hands/feet/weapon/legsName，可空）；critDamageBonus 默认 0.0 逐位一致。
5. **proto 六列号**：67-70=头/身/手/脚，weaponId=122、legsId=123；71..74 与 31/32 reserved；EquipmentNurtureDataView 消息已删；C++ gameview_encode.cpp 字段表已同步（kNurture 枚举位保留无消费者）。
6. **ActionId EQUIP_UPGRADE=1486 / EQUIP_DISMANTLE=1487**（catalog core.mjs 已加+双产物已重生成）；RngPartition.EQUIPMENT(13) 双端已加（委托模式，同 GACHA）。
7. **RewardSelectedItem 装备赏赐链已改实例轨**（rewardEquipment：可穿即穿/不可穿入袋；仓库装备堆叠轨不存在）。
8. **gen-templates.mjs 已重写**（中性源驱动+四表幂等输出）；equipment_db_sample.json 新复合结构（setPieces/sets/mainStatPools/mainStatBase/subAffixes）——data_inject.h 注入面必须对齐此结构。
9. 弟子 PurchaseService/AutoBuy 的商人装备购买=按 quantity 产 N 条实例（EQUIPMENT 分区 roll）。
10. WarRewards.equipmentInstances（AISectAttackModels 已改）；MissionResult.equipmentInstances。

## 六、工具与环境备忘

- 编译：`cd android && ./gradlew.bat compileReleaseKotlin`（模块级 `:feature:game:` 前缀）；测试一律 `--max-workers=1`
- 错误清单导出：`./gradlew.bat <task> --console=plain 2>&1 | grep "^e: " | sed 's|file:///C:/Mnzm/XianxiaSectNative-equipment/android/||; s|^e: ||' > /tmp/xxx.txt`
- C++：`export PATH="/c/Users/cp050/llvm-mingw/llvm-mingw-20260616-ucrt-x86_64/bin:/c/Users/cp050/AppData/Local/Android/Sdk/cmake/3.22.1/bin:$PATH"`；build 目录 `app/src/main/cpp/gamecore/build`
- **bash heredoc + python 正则坑**：heredoc 内 `\$` 被 bash 吃掉、`\.` 被 python 吃掉——复杂正则/转义一律用 `Write` 工具落 .py 文件再执行，或字节级 `bytes([0x5C])` 构造
- 子代理派发会因 quota 中断：**中断后先 `git status` 盘点半成品再继续**，勿盲目重派（本轮 UI 代理停止前未落盘、C++ 代理一期已落盘 models.h 等 9 文件）
- 门禁命令全集见派发件 §2 / IMPLEMENTATION-BATCHES §2

## 七、验收盯点（看护 §5 口径，恢复会话自查）

S1-S8/S12/S14/S17/S18；G0 重跑零差异证据；RoomMigrationTest 全链 v11→64；旧用例处置表三类分组；217 口径测试全绿（实测口径：core:data 20+domain 15+engine 60+feature 5+app 8=108 Kotlin 装备测试文件 + C++ GTest）；jni-count 87/87（无新增 JNI 口则不动）。

—— 交接完。恢复会话从 §三.1（C++）与 §三.2（UI）并行开工即可，两写面互不重叠。
