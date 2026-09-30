# EQ-B3 恢复会话二阶段交接（HANDOVER-R2）—— 2026-09-30 会话上下文收敛

> **给恢复会话**：本文档是 HANDOVER-B3.md 的续篇。派发真源不变（`IMPLEMENTATION-BATCHES.md`
> §4.4 + 方案 HEAD）；HANDOVER-B3.md §五决策/§六工具/§七盯点全部继续有效，本文只写
> **增量状态与剩余作业**。工作区仍是 `C:\Mnzm\XianxiaSectNative-equipment`（feat/equipment-set，
> HEAD `fa36109fc`，全部改动仍未提交）。

---

## 一、当前状态快照（2026-09-30 晨）

| 项 | 值 |
|---|---|
| HEAD | `fa36109fc`（B3 仍未提交任何内容；树约 215 文件已改/删/增） |
| **C++ 主库** | ✅ **`game-core` 目标 100% BUILD SUCCESSFUL**（llvm-mingw + cmake 3.22.1；旧符号 grep 清零） |
| Kotlin 主代码 | ✅ `:core:domain` / `:core:data` / `:core:engine` 全绿（含主代码改动） |
| Kotlin 单测编译 | ✅ `:core:data` / `:core:domain` 单测编译绿（本会话修完 domain 测试面 138 错 + data 6 锚点） |
| `:feature:game` | ❌ **198 错**（快照=同目录 `ui-errs-r2-snapshot.txt`；分布见 §三.1） |
| C++ GTest | ❌ 未动（`cmake --build build` 全量 all 目标会在 test 上红；主库 target 单独绿） |
| Room v64 迁移测试 | 未执行（`RoomMigrationV63To64Test` 已写好） |
| 门禁/报告/收官 | 未开始 |

## 二、本会话已完成（在上份 HANDOVER §二/§三 基础上的增量）

### 1. C++ 主库全链收口（上份交接 §三.1 的全部剩余项）
- **三个新头**：
  - `data/equipment_entries.h`——72 条部件×品阶展开条目（派生逻辑头，非生成件；展开式=Kotlin `EquipmentDatabase.expand` 逐字段一致；`equipmentEntries()/equipmentEntryById()/equipmentEntriesByPart()`）
  - `system/equipment_factory.h`——装备唯一产出入口（Kotlin EquipmentFactory/EquipMainStatPool/EquipAffixPool 逐位移植：rollMainStat 部位池 nextInt、rollSubStats 权重前缀和不放回 3 条、pickRarity 分层阈值 0.5/0.75/0.9/0.97/0.99、maxWearableRarity/clampRarity 境界钳制）
  - `system/equipment_tx.h`——升级/分解事务（1486/1487；灵石 100×rarity²×level、兽材 max(1,level/10) 全类目 (rarity,id) 升序扣、expRequired=100×level×rarityMul[1.0/1.5/2.0/3.0/4.5/6.0]、newLevel%3 强化 kEquipment nextInt(subStats.size) 上限 11、分解 50% floor+袋条目 strip 防复活）
- **改写文件**（语义权威=各 Kotlin 源，逐位对齐）：
  `data_json.h`（装备五子表 json 适配器）、`data_inject.h`（db.equipment 复合结构注入；AppliedCounts.equipment=setPieces 数）、`dirty_tracker.cpp`（equipmentStacks 集合面删）、`execute_dispatch.cpp`（1010 改实例 add；handleDiscipleTx 加 1486/1487；区间上界→EQUIP_DISMANTLE）、`disciple_stats.h`（EquipBonus/resolveEquipBonus/resolveSetBonus 逐位移植；HP/MP 段六槽一次截断；finalStats 乘区口径 applyEquipBonusToAccum；**outEquipBonus 出参回传 critDamage/类型通道**）、`auto_gear.h`（装备半边重写：候选=袋实例单源、比较键品阶→套装流派（resolvedInnateIsPhysical vs setId=="lietian"）→level、六槽 displayOrder 循环、卸装=保真回袋+实例表 isEquipped=false）、`ai_sect_recruit.h`/`ai_sect_ops.h`（六部位条目 id 直写、AI 轻量实例查 72 条表、孕养三函数删）、`mission_completion.h`（敌人生成 B3：六部位洗牌+工厂产出+逐件 totalBonus 累加器（逐条 toInt 口径）、mp/speed 不吃装备；任务奖励 generateEquipment 工厂化；发放 addEquipmentInstance）、`inventory_tx.h`（出售/收购/上架/购买/充公五流实例轨）、`merchant_settlement.h`（toEquipment 转换器实例版+kEquipment rng、canAddEquipment 纯槽位、executeAutoBuy 加 rng 参数装备按 qty 产 N 条实例）、`disciple_purchase.h`（hasWarehouseStock/addToWarehouseAndBag 实例轨、决策面六列）、`production.h`（锻造产出=工厂+forgeTier=全宗存活弟子最高 forgeLevel）、`year_settlement.h`（商人池=72 条、贸易装备 item.itemId=条目 id）、`secret_realm_session.h`（背包实例轨、reconstructStackedItem 装备臂删、遗迹奖励轻量实例）、`phase_settlement.h`+`nurture_constants.h`（孕养管线全删，熟练度族保留）、`death_handler.h`/`game_core.cpp`（六列面）、`battle.h`/`battle_calculator.h`/`battle_json.h`（**critDamageBonus 全链接线**：Combatant+CombatantStats 字段、calculateFinalDamage 形参、管线/DoT/期望三路径、json 编解码）、`inventory.h`（sortEquipmentInstances 专用排序）、`GameCoreJni.cpp`（invAddEquipment op 实例轨）、`month_settlement.h`（executeAutoBuy 传 rng）、`gacha_pool_db.h`+`data_json.h`（**CharacterTemplate 补 innateDamageType 镜像字段**——Kotlin/数据文件早有、C++ 缺）

### 2. Kotlin 测试面（上份交接 §三.3/.4 的 Kotlin 部分）
- `SaveValidatorTest`（六处：六部位参数/断言、堆叠引用 keeps→cleared 两用例按 B3 语义改写「堆叠引用=孤立」）、`ItemsTest`（六值枚举/删 withQuantity+EquipmentStats 用例）、`StackKeysTest`（equipment 键用例删）、`MergeStackableTest`（载体改 Material，七用例语义不变）、`GameEventRecordTest`（equipmentStacks 构造参删）、`DiscipleModelsTest`（六槽断言+slotIdRoundTrip 新增+孕养用例删）、`AlchemySystemTest`（六值枚举）、`AssemblePatchEquivalenceTest`（孕养列→headIds/bodyIds/legsIds）、`StorageBagMaterializerTest`（EquipmentInstance 新构造 part/meta）、`ForgeRecipeDatabaseTest`（**整文件重写**：12 条/六部位覆盖/材料六档/durationFor/successRateFor/byPiece/byType/byMaterial/craftable 恒全量/越界回退）、`EquipmentDatabaseTest`（slot→part）
- **删除**：`StackRebuildTest.kt`、`EquipmentFinalStatsCacheTest.kt`（git rm，已入暂存区——收官笔一并提交）
- 实测：`:core:data` 与 `:core:domain` 单测编译 BUILD SUCCESSFUL

### 3. UI 面（进行中，~44 错已清）
已清零文件：ItemWatchKeys（实例 watchKey=id）、SaveDataTrimmer、SaveLoadViewModelRestartOps、SaveLoadLoadDelegate（loadData 去 equipmentStacks 参）、MerchantOpsDelegate（批量出售实例轨）、GameViewModel（equipmentStacks 流删+forgeSlots 品阶占位 1）、ForgeViewModel（12 条配方+materialsFor(forgeTier)+ProfessionRules 门禁退役）、DetailBasicInfoSection（六槽 map）、ReplaceSelectionData（**实例单轨重写**：buildEquipmentReplaceItems 去 stacks 参/equipmentStackDetail 删/instanceDetail=词条摘要+Lv+部位副标题）、WarehouseTab 详情数量（装备恒 1）。
**注意级联**：GameViewModel.equipmentStacks 流删除后，MerchantDialog/MerchantInventoryDialog/DialogCommon/WarehouseBulkSellDialog/DetailPillSection/DetailEquipmentSection/WarehouseTab/MerchantOpsDelegate 出现下游错（已计入 198 总数，见快照文件）。

## 三、剩余作业清单（按优先序）

### 1. UI 面 ~198 错（快照=ui-errs-r2-snapshot.txt；修完以实时编译为准）
| 文件 | 错数 | 修法要点 |
|---|---|---|
| components/ItemDetailOtherEffects.kt | 39 | getTemplateByName→`EquipmentDatabase.getById(条目id)`；7 项面板字段→词条摘要（`totalBonus()` 逐条 `"$sv"`）；`materials` 分解返还展示→删或改「分解返还 50% 累计消耗」文案（不再列兽材明细） |
| dialogs/ForgeDialog.kt | 32 | ForgeRecipe 新形状：`type/tier/rarity/materials/duration/successRate` → `part/setId + materialsFor(tier)/durationFor(tier)/successRateFor(tier)`（tier=工作弟子 forgeLevel 或 1）；`getTemplateByName`→72 条表 byId；产出预览面板字段→套装·部件名+品阶 |
| components/ItemDetailDialog.kt | 30 | EquipmentNurtureSystem/孕养段删；装备详情=套装·部件名+Lv+`bonusDescription`（或 totalBonus 逐条）；getTemplateByName→EquipmentDatabase.getById |
| DiscipleDetailScreen.kt | 29 | 四槽（weaponId/armorId/bootsId/accessoryId + 扩展 import）→六槽 `EquipmentSlot.displayOrder` 遍历 + `equipment.slotId(part)`；352-359 的 remember 链同改 |
| components/ItemDetailEffects.kt | 14 | 同 ItemDetailDialog 语义（getFinalStats/stats/totalMultiplier/nurtureLevel→totalBonus 摘要） |
| dialogs/HeavenlyTrialBattleDialog.kt | 12 | Combatant `armorName/bootsName/accessoryName`→六名 head/body/hands/feet/weapon/legsName（可空，空则不显示）；getTemplateByName→byId；UnifiedItemCard 实参面（rarity 等取 meta） |
| components/dialog/DialogCommon.kt | 10 | equipmentStacks 流消费→equipmentInstances（GameViewModel 已删旧流） |
| delegate/MerchantOpsDelegate.kt | 7 | 级联残余（bulkSellItem* 已改实例轨；查最新错误行） |
| tabs/WarehouseTab.kt | 6 | 装备行数量恒 1 已做；级联残余（排序/数量列） |
| dialogs/MerchantInventoryDialog.kt + MerchantDialog.kt | 6+4 | `viewModel.equipmentStacks`→`equipmentInstances`；EquipmentStack 类型→EquipmentInstance（商人货架展示 name/rarity→meta 面） |
| tabs/WarehouseBulkSellDialog.kt | 4 | 装备批量出售选择面按实例（数量恒 1） |
| components/detail/DetailPillSection.kt + DetailEquipmentSection.kt | 4+1 | 装备段字段→meta/growth 面 |
- **UI 语义从简原则不变**：详情=套装·部件名+Lv+词条摘要；升级/分解完整对话框留续批；**禁止**新增套装面板/仓库批量分解/精灵图等加分项。
- 修完后跑全模块 `./gradlew.bat compileReleaseKotlin` 必须 BUILD SUCCESSFUL。

### 2. C++ GTest + ctest + JNI（上份交接 §三.1 GTest 子项 + §三.4）
- `test/` 下现有用例适配（编译驱动）：equipment_db_test（重写 12/2/6/7 断言）、disciple_tx_test/ai_sect_ops_test/phase_settlement_test（孕养用例删+六槽适配）、inventory_test/inventory_tx_test（堆叠轨用例删/实例轨改）、data_store_test（db.equipment 五子表注入断言）、execute_dispatch_test（1010 实例形状/1486/1487 用例）、gameview_encode_test/month_settlement_test/production_test 顺带。
- **新增**（方案 §6.1 B3 范围）：equipment_tx_test（升级/分解逐位断言）、equip_affix_test（前缀和+nextInt(total) 不放回 3 条）、equip_main_stat_test、equip_set_bonus_test（0..6 件）。
- 验证链：`cmake --build build`（全量，含 test）零错 → `ctest` 全绿（llvm-mingw PATH 必带）→ `pwsh -NoProfile -File scripts/build-desktop-jni.ps1`（worktree 根）→ .so mtime 记录进报告。

### 3. Kotlin 测试面剩余（上份交接 §三.4）
- core:engine 测试面未编译过（EquipmentNurtureSystemTest 等孕养测试删/改、CaptiveGearMaterializationTest/Diff* fixture/TemplateRegistryGuardTest↔72 条快照/StackKeys 域余量）——**全是编译驱动修**。
- feature:game / app 测试适配（编译驱动）；`DiffExecuteTest`（1010 用例改实例形状）、`DiffInventoryTest`（堆叠对拍删/实例改）。
- 新增守卫/单测（方案 §6.1 B3 范围，上份交接 §三.4 清单原样有效）：EquipmentSlotOrderGuardTest、EquipmentStackRemovalGuardTest、EquipmentSingleSourceGuardTest、TemplateCodegenIntegrityGuardTest、EquipmentLevelPersistGuardTest、EquipmentValueSanitizeRuleTest、EquipmentLegacyCompensationTest、EquipmentRarityGateTest、EquipmentNoCapGuardTest、EquipMainStatPoolTest、EquipAffixPoolTest、EquipmentSetDatabaseTest、EquipmentLevelSystemTest、EquipmentUpgradeServiceTest、EquipStatResolverTest、EquipmentSetBonusTest(0-6 件)、DiffEquipmentGeneration/Upgrade/SetBonus 三对拍（依赖 §三.2 的 .so）。
- `RoomMigrationV63To64Test` 执行 + `RoomMigrationTest` 全链尾追加 v64。
- **旧用例处置表**（报告必需件，三类分组：EquipmentStack 删除/孕养→升级/四槽→六部位）——本会话已删 2 文件（StackRebuildTest/EquipmentFinalStatsCacheTest）+ SaveValidatorTest 2 用例语义改写，全部要进表。

### 4. 门禁全套 + 报告 + 收官（上份交接 §三.5 原样有效）
- 门禁：compileReleaseKotlin / 六模块 JVM 全量（`--max-workers=1`，jni.path 指 worktree .so）/ ctest 全量 / lint+detekt（baseline 只缩不增）/ jni-count 87 / agent-instructions / **G0**：gen-templates 重跑 git diff 零差异 / gen-game-data --check。
- 报告 `reports/report-B3.md`：门禁实测原数字 + 旧用例处置表 + 影子表决策登记（HANDOVER-B3 §五.1）+ **I1 不可回退公告文案预留** + 诚实归因。
- 收官笔：单笔 `feat(equip): B3 装备体系原子替换——…`；**提交前 `git checkout -- android/app/src/main/assets/atlas-rgba-manifest.json`**（codegen 副产物在树）；HANDOVER-B3.md + HANDOVER-B3-R2.md + ui-errs-r2-snapshot.txt（或删除该临时快照）随笔入库；双 changelog **归 B5 不动**。

## 四、本会话新增决策（勿翻案，报告须登记）

1. **Kotlin BattleCalculator 暴伤双端接线**：发现 Kotlin 侧 `Combatant.critDamageBonus` 只声明未消费（calculateFinalDamage/estimateDamage 均未含），而 zifu 4 件套即产 CRIT_DAMAGE——按举一反三原则与 C++ 同步接线（calculateFinalDamage 加 `critDamageBonus: Double = 0.0` 形参，三调用点传 `attacker.critDamageBonus`；estimateDamage 的 buffCritMult 折入）。**未做完**：Kotlin 战斗装配点（GameEngineBattleOps/BattleSystem战斗Ops1 等 Combatant 构造）尚未填 critDamageBonus 与六名——与 C++ 装配点同步留给下一会话（grep `critDamageBonusOf` 消费面即达）。
2. **C++ 12 条锻造配方 = 部件表派生**：配方 id 规则 `forge_{pieceId}`（Kotlin ForgeRecipeDatabase 同构），produceForgeEquipment 反查 setPieceTemplates 得 setId/part；旧 73 条配方 id 一律失配→产出失败（与 Kotlin getRecipeById null 臂一致）。**recipe_db.h 旧表与数据文件 db.forgeRecipes(72 条) 未迁**——锻造启动扣料在 Kotlin SlotOps（新 12 条面），C++ 完成期只需 setId/part；旧表清理归 B4/后续。
3. **CharacterTemplate.innateDamageType C++ 镜像补齐**（gacha_pool_db.h 字段+operator==+data_json 适配器）——InnateDamageType.derive 模板优先臂的双端对齐。
4. **Kotlin `nextBoolean()` 语义锚定**：RngRandomAdapter 未覆写 nextBoolean → 默认 `nextBits(1)!=0` = `nextInt() ushr 31`（恰耗一注整 nextInt）——C++ 对偶 `kotlinNextBoolean`（mission_completion.h）按此实现，产出链（套装二选一）抽取序逐位基准。
5. **AI 装备轻量实例**：C++ 与 Kotlin（Gear.kt aiEquipmentInstance）一致——id=条目 id、mainStat 占位 (ATTACK,0.0)、无副词条、等级恒 1，AI 不结算词条（B4 战斗装配对齐）。
6. **GameViewModel.forgeSlots.equipmentRarity 占位 1**（产出品阶完成时才定，UI 展示面从简）。

## 五、途中发现（报告项，不阻塞）

- `AlchemySystem.ForgeRecipe`（旧 11 字段 DTO）仍被引擎 10+ 文件消费——B3 未迁，登记后续批。
- Kotlin `SecretRealmBackpack.equipment` 仍是旧 `List<EquipmentStack>` 轨（代码注释自登记「待 B4」）；C++ 侧已实例轨——**跨端载体临时不对称**，对拍面注意背包结算段（C++ addEquipmentInstance vs Kotlin toLegacyInstance→addEquipmentInstance，语义等价）。
- `data/game-data.json` 的 `db.forgeRecipes` 仍为旧 72 条段（与 2 相关；`--check` 绿因生成器未覆盖该段语义变更）。
- 子代理派发在账号 5h 配额窗口（昨日 19:04 触顶，23:07 重置）会 1308 中断——**中断后先 `git status` 盘点再续，勿盲目重派**（本会话两代理死前：C++ 侧落盘 4 文件已完成收编；UI 侧零落盘）。

## 六、环境备忘（同 HANDOVER-B3 §六，新增两条）

- Bash heredoc 内嵌 python 的 `${it.xxx}` 会被 shell 吃掉（本轮实际踩坑两次）——**复杂编辑一律 Write 落 .py 文件再执行**，大块字符串匹配拆小块锚点。
- UI 错误导出命令（HANDOVER-B3 §六）实测可用；实时快照在 `docs/design/equipment-batches/ui-errs-r2-snapshot.txt`（198 错时点），修完可删。

—— 交接完。恢复会话建议顺序：§三.1 UI 清零（编译驱动）→ §三.2 C++ test/ctest/JNI → §三.3 Kotlin 测试面 → §三.4 门禁 → 报告+收官。UI 与 C++ test 两写面可并行（若派子代理：两写面互不重叠，gradle 与 cmake 无锁冲突）。
