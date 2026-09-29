# EQ-B1 实施报告 · 属性机制重构（单列 + 类型通道 + 固有伤害属性）

> 批次：B1（装备系统重构 · 属性机制重构）
> 真源：`docs/design/equipment-set-system-refactor-plan.md` §15/§四 WP8/§5.1/§六 + `docs/design/equipment-batches/IMPLEMENTATION-BATCHES.md` §1/§4.2/§5/§8
> 工作区：`C:\Mnzm\XianxiaSectNative-equipment`（git worktree，分支 `feat/equipment-set`）
> 状态：**实施完成，门禁全绿，待看护核验 accepted**

---

## 1. 做了什么（分类表）

### 1.1 领域层（`:core:domain`）

| 项 | 变更 |
|---|---|
| `CombatAttributes` | `basePhysicalAttack/baseMagicAttack/basePhysicalDefense/baseMagicDefense` 四列 → `baseAttack(24)/baseDefense(18)` 两列；方差 4 → `attackVariance/defenseVariance` 2；新增 `innateDamageType`（String，DamageType.name，空 = 未派生）；新列带 `@ColumnInfo(defaultValue)` 对齐迁移 DDL |
| `PillEffects` | 四 pill 加成列 → `pillAttackBonus/pillDefenseBonus`（带 DEFAULT 0） |
| `DiscipleStats` / `BaseCombatStats` | 物法四属性 → `attack/defense`；`BaseCombatStats` 默认 24/18 |
| `ItemEffect` | 旧 14–17 物法四列保留为 deprecated 归一化读取源；新增 `attackAdd(39)/defenseAdd(40)`；消费点走 `attackAddTotal/defenseAddTotal` |
| `PillEffect`（仓库丹药） | 同上（旧 12–15 保留，新 36/37） |
| `InnateDamageType`（新） | 固有属性唯一派生口：模板命中取模板值；缺失按首灵根（金/土→物理，水/木/火→法术）；空串兜底物理；与迁移 SQL CASE 逐条一致 |
| `CharacterTemplate` + `CharacterTemplateDb` | 新增 `innateDamageType` 字段；六模板按首灵根定值（zhouming/xieche=PHYSICAL，余=MAGIC） |
| `WorldLevel` | 妖兽预计算四列 → `beastAttack(25)/beastDefense(26)`；旧列保留归一化（`beastAttackTotal`） |
| `AICaveDisciple` | 同口径（新 17/18，旧 9–12 归一化） |
| `DiscipleAggregate/DiscipleCombatStats/DiscipleDelegates/DiscipleTables*` | 列面/投影/写入/组装全量单列同步；`innateDamageTypes` 列登记 ColumnRegistry/AssembleGroup |

### 1.2 存档面

| 项 | 变更 |
|---|---|
| `DiscipleSerializer` | E1 冻结表增量：新写 `baseAttack(118)/baseDefense(119)/attackVariance(120)/defenseVariance(121)/pillAttackBonus(122)/pillDefenseBonus(123)`；`innateDamageType(117)` B0 占号 B1 接线；旧 69–72/62–65/36–39 保留声明（deprecated）**只读不写**；读面线性归一化（基值取和、方差均值、丹药取和）——新档旧号恒 0，归一化对两代存档皆正确 |
| Room v62 迁移 | `GameDatabaseMigrationsV62.kt`（新增）：disciples create-copy-drop-rename（删 12 列增 7 列，幂等）+ pills 表 ADD 2 列（幂等）；回填取和（Q7，**k=1**）+ 固有属性按首灵根 CASE 派生；`GameDatabase.kt` 版本 61→62 + ALL_MIGRATIONS 注册 |
| schema 62.json | KSP 自动导出，七新列 DEFAULT 与迁移 DDL 逐字核对一致 |
| ItemDatabase / PillRecipeDatabase / MerchantItemConverter | 丹药模板与配方效果四列 → 两列（pillType/文案/价格保留物法身份，效果等价归并） |

### 1.3 引擎层（`:core:engine`）

| 项 | 变更 |
|---|---|
| `BattleCalculator` | `DamageZones`：删 `attackBuffs`；新增 `physicalDefenseBuffs/magicDefenseBuffs`（守方类型减伤 buff 分桶）+ `typeDamageBonus/typeDamageReduction`（选桶合并后结算位）；`calculateFinalDamage` 公式 `(1+增伤+类型增伤) × (1−减伤−类型减伤)`——默认 0.0 时与基准公式逐位一致（S19） |
| `Combatant` | 单列 `attack/defense` + `innateDamageType` + 四类型桶字段；effective 物法四属性删除；物法攻防 buff **枚举名不变、结算位置迁移**为类型增伤/减伤语义（§15.2 改动点③） |
| 伤害类型判定 | 普攻 = `attacker.innateDamageType`（原物攻≥法攻启发式退役）；技能 = `skill.damageType` |
| `SectCombatPowerCalculator` | 战力公式 `attack×5 + HP×4 + defense×3 + 速度×2`（与旧双列取和口径线性恒等，k=1，迁移前后战力逐位不变） |
| `DiscipleStatCalculator` | `computeBaseStats` 物法两半各自 round 后相加；功法 Q2 结算层相加；丹药单列 |
| `EnemyGenerator` / `AISectAttackManager` / `HeavenlyTrial*` / `BattleSystem` / `CaveExplorationSystem` / `SecretRealm*` | 生成/组装全量单列；散修敌人/妖兽伤害类型按元素派生（金/土→物理，水/木/火→法术） |
| `AISectDiscipleManager` / `DiscipleFactory` / `ai_sect_recruit.h` / `disciple_factory.h` | 方差 roll 7→5 抽（hp/mp/atk/def/speed）；base 快照单列；装备/功法类型匹配改走固有属性（§15.4「物法倾向由固有属性表达」） |
| `PillEffectApplier` / `DiscipleFacadeImpl战斗Ops2` / `pill_system.h` / `disciple_tx.h` / `AutoPillService` / `HpMpRecoveryService` / `month_settlement.h` / `phase_settlement.h` | 丹药写面/清零面/衰减面单列（旧四列经 *Total 归一化） |
| `GameViewDiscipleRows` / `GameViewMirrorCodec` / `game_view.proto` | 镜像协议：旧 35–38/42–45/52–55 退役 reserved；新 113–119（baseAttack/baseDefense/innateDamageType/attackVariance/defenseVariance/pillAttackBonus/pillDefenseBonus）；C++ `gameview_encode.cpp` 字段表同步 |
| UI | 弟子详情战斗段四行→两行 + 普攻属性标签；试炼/关卡/地图敌人两行 + 类型；丹药效果行单列 |

### 1.4 C++（`game-core`，authoritative）

| 文件 | 变更 |
|---|---|
| `models.h` | `Disciple` 单列字段 + `innateDamageType`；`ItemEffect/PillEffect` 旧四列保留（归一化源）+ 新两列 + `*Total()` 辅助；`WorldLevel` beast 单列 |
| `disciple.h` | `BaseStatsInput/DiscipleStats` 单列；`computeBaseStats` 物法两半相加 |
| `disciple_stats.h` | `EquipmentStats` 单列（装备四列在出口合并）；`baseStats/finalStats` 同构 |
| `battle.h` / `battle_calculator.h` / `battle_ai.h` / `battle_execution.h` | 与 Kotlin 同构：Combatant 单列+类型桶、DamageZones 新字段、公式迁移、enum DamageType 前置 battle.h |
| `disciple_store.h/.cpp` | SoA 列重命名 + `defenseVariances/innateDamageTypes` 新列（物化/追加/reserve/clear/erase/swap 全链） |
| `column_dirty.h` | `DiscipleColumn` 枚举 + 列名映射 + 序列化导出单列 |
| `json_codec.cpp` | 存档 JSON：写面只写新键；读面旧物法键线性归一化（与 Kotlin Serializer/迁移 SQL 同口径）；ItemEffect/PillEffect/WorldLevel 同步 |
| `gameview_encode.cpp` | 镜像行字段表（113–119） |
| `sect_power.h` / `execute_dispatch.cpp` | 战力/指纹三个 op 签名单列（对拍桥协议同步） |
| `recipe_db.h` / `data_json.h` / `json_codec.cpp` 丹药段 | PillTemplateSpec/PillRecipeTemplate 尾部追加 attackAdd/defenseAdd；战斗丹模板构造改新键（与 Kotlin ItemDatabase 同式）；效果复制/JSON 编解码同步 |
| `ai_sect_ops.h` / `exploration_tx.h` / `secret_realm*.h` / `level_generator.h` / `month_settlement.h` / `phase_settlement.h` / `mission_completion.h` / `auto_gear.h` / `pill_system.h` / `determinism_probe.h` / `GameCoreJni.cpp` | 妖兽/秘境/月结/旬结/任务/AI 装备/丹药/探针/JNI 导出全链单列 |
| `game-data.json` / `recipe_db_sample.json` / `gen-recipe-db.mjs` / `gen-game-data.mjs` | codegen 数据链新键（characterTemplates 补 innateDamageType；pillRecipes 攻防效果归并）；生成器复刻逻辑同步 |

### 1.5 测试

| 类 | 变更 |
|---|---|
| `RoomMigrationV61To62Test`（新） | 3+1 用例：真实 Room 校验（v61 建库全链升 62 触发 onValidateSchema）/ 回填取和+灵根派生+保留列零丢失 / **S20 战力比 ∈ [0.98,1.02]** / 幂等 |
| `single_column_stat_test.cpp`（新） | S19 八用例：单列结算取和/战力线性恒等/类型桶零值逐位中性/类型桶非零生效/防御 buff 分桶迁移/伤害类型判定/旧列归一化 |
| `SingleColumnStatGuardTest`（新，S21 组合） | 全仓源码扫描：physicalAttack 等四列名在弟子属性面归零（装备/功法/旧档归一化面豁免清单显式登记） |
| `InnateDamageTypeGuardTest`（新，S21） | 模板覆盖完整+派生幂等+首灵根口径+迁移 SQL CASE 同口径（源码扫描） |
| `EquipmentProtoNumberFrozenTest` | 同步 B1 增量冻结表（118–123 新段 + 旧号归一化源断言）；字节流断言改**写入面源码扫描**（118/119 缺省非零合法写出后，B0 全流分帧断言语义失效，理由在案） |
| `CharacterTemplateGuardTest` | MIRRORED_FIELDS 第 7 字段（innateDamageType） |
| 全部旧用例 | 见 §2 旧用例处置表 |

## 2. 旧用例处置表

| 处置 | 用例 | 说明 |
|---|---|---|
| 改断言 | `BattleSystemTest`（effective*×2 用例） | effectivePhysicalAttack/MagicAttack 删除，物理 buff 用例改断言 buildDamageZones 分桶（语义迁移 §15.2③） |
| 改断言 | `BattleCalculatorTest`（magic attack×2） | 单列后 isPhysicalAttack=false 仅置标志位，伤害走单列；auto-select 用例断言缺省恒物理（CombatantStats 简化面缺省） |
| 改断言 | `SectCombatPowerCalculatorTest`（4 处期望值） | 旧双列取和口径 → 单列线性恒等（(a+b) 与单列 a+b 数值恒等），5310→4760 等按 fixture 单列值重算 |
| 改断言 | `BattleAITest` / `BattleExecutionRouterTest`（目标选择） | 低防御/高威胁目标单列 defense；fixture 物法对合并 |
| 改断言 | `HeavenlyTrialServiceTest`（方差范围） | 单列攻 = 物法两半相和，范围 350..700 |
| 改断言 | `BattleExecutionRouterTest`（协议键） | battle_json 协议键单列（attack/defense/innateDamageType/四桶） |
| 改断言 | `BattleAI.kt`（C++ 目标选择） | 与 Kotlin 同构 |
| 改断言 | `BattleCalculatorCoverageTest`（物 buff 不加成法术） | buff 语义迁移后断言「物理类型增伤 buff 不加成魔法技能」 |
| 改断言 | `recipe_db_test.cpp`（3 处） | 战斗丹模板 attackAdd/defenseAdd 新键 |
| 改断言 | `disciple_factory_test.cpp`（黄金序列 ×2 + 往返） | 五维方差新序列黄金值实跑重钉（DiffDiscipleFactoryTest 双端互拍互证）；baseAttack 26/21、baseDefense 20/16 |
| 改断言 | `battle_execution_test.cpp`（黄金序列） | turn 5、hp 164/629 实跑重钉 |
| 改断言 | `determinism_probe_test` + `determinism_probe.h` | kGoldenDigest 0xb4f3c6912207f597 → 0x8877d164f6bfe1fc（探针脚本轨迹随方差抽取序变化；FP 确定性本身不变——DigestIsStableAcrossRepeatedRuns 仍绿） |
| 改断言 | `sect_diplomacy_test.cpp` / `star_zone_test.cpp`（战力公式） | 单列入参同值（180/1000/110/40 = 5310 与旧双列取和恒等） |
| 改断言 | `mission_completion_test`（妖兽属性） | 单列取和 296/110 |
| 改断言 | `json_codec_test` / `secret_realm_test` | EquipmentStats 单列 / 秘境预生成单列 |
| 改断言 | `RoomMigrationV58To59Test` / `V60To61Test` | 「真实 Room 升级终版列数」84（89−12+7，ALL_MIGRATIONS 全链升到当前版） |
| 改断言 | `DiscipleMergeCoverageTest`（computedProps 清单） | 单列 getter 登记 |
| 改断言 | `ArchivePayloadRoundTripTest` / `CloudPayloadSizeBenchTest` | CombatAttributes fixture 单列 |
| 删除 | `effectivePhysical/Magic*` 相关断言（Combatant 计算属性） | 属性删除（E9）；buff 断言迁 buildDamageZones 分桶 |
| 删除 | `attackBuffs` zones 用例（`attackBuffs - 攻击 buff 作用于 effectiveAttack`） | DamageZones.attackBuffs 删除；改 typeDamageBonus 语义用例 |
| 保留 | 全部装备四列断言（`EquipmentStack/EquipmentInstance/EquipmentStats` 模板面、`equipment_db_test`、`inventory_tx_test`、`ItemsTest` 装备段、`ai_sect_ops_test` 装备复制、`manual_db_test` 功法 stats 键、`phase_settlement_test` 装备段） | 装备/功法模型本体 B1 保留（B3 退役），断言继续有效；`toDiscipleStats` 单列映射在 `ItemsTest`/`EquipmentFinalStatsCacheTest` 继续覆盖 |
| 保留 | `recipe_db_test` 旧字段复制断言 | C++ 模板旧字段保留（归一化源），新字段断言补充 |

## 3. 验证（门禁实测原数字）

| 门禁 | 结果 |
|---|---|
| `compileReleaseKotlin`（主代码） | BUILD SUCCESSFUL，0 error |
| `compileReleaseUnitTestKotlin`（六模块测试代码） | 0 error |
| `testReleaseUnitTest --max-workers=1`（六模块全量，带 jni.path） | **BUILD SUCCESSFUL**；XML 汇总 **7556 tests / 0 failed / 18 skipped**（EQ-B0 基线 7546 → +10：迁移 4 + SingleColumnStatGuard 1 + InnateDamageTypeGuard 4 + Frozen 增量 1） |
| 桌面 ctest（llvm-mingw 全量） | **1481 tests / PASSED 1481**（含新增 `SingleColumnStat` 8 用例；含重建后的 RecipeDb 全量） |
| `build-desktop-jni.ps1`（worktree 重编） | 成功（JNI .so 2025-09-29 08:04 重编；跨工作区不拷贝原则遵守） |
| `lintRelease detekt` | **BUILD SUCCESSFUL**（detekt 零违规：LongMethod/NestedBlockDepth 以清单型抑制 + 理由登记，先例 V11ToV20/StorageEngine；UnusedImports/UnusedPrivateProperty/MaxLineLength/NewLine 全清） |
| `check-jni-count.mjs` | **87/87 基线内，双桥无扩散**（本批未动 JNI 面符号计数） |
| `check-agent-instructions.mjs` | 全绿（规则④⑤ 通过） |
| G0 codegen（gen-templates 重跑 + 人检 + 还原） | diff 仅 D9 已在案类（生成器删 operator==/人工补全注释：equipment_db.h/herb_db.h）；已 `git checkout --` 还原；`gen-game-data.mjs --check` 校验通过（sha256 ffff3f42…） |
| Room v62 真实校验 | `MIGRATION_61_62 passes real Room schema validation` 绿（v61 建库 → ALL_MIGRATIONS 升 62 → onValidateSchema 通过） |

## 4. 数值口径（对拍基线重录的对照表）

| 面 | 旧口径 | 新口径 | 关系 |
|---|---|---|---|
| 弟子基值 | basePhysicalAttack=12/16/…，baseMagicAttack=同 | baseAttack = 物+法（Room 行直取和；结算层两半各自 round 后相加） | 战力线性恒等（k=1） |
| 弟子方差 | physicalAttackVariance/magicAttackVariance 独立 | attackVariance = (旧物+旧法)/2 | 期望等价，round 噪声 ≤1 |
| 战力 | (物攻+法攻)×5 + HP×4 + (物防+法防)×3 + 速度×2 | attack×5 + HP×4 + defense×3 + 速度×2 | **逐位恒等**（S20 比值=1.0） |
| 伤害 | 物理技能取物攻打法防 | 单列 attack 打 defense ± 类型桶 | 类型桶默认 0 时与单列基准逐位一致（S19）；**与旧双列数值不同**——Diff 全系基线随本批一次性重录（fixture 同构 + 双端互拍全绿） |
| 妖兽 | 物=法两列同值 V | attack = 2V（两半各自 round 相加） | 战力/伤害期望恒等 |
| 丹药 | 物攻+X / 法攻+Y 分列 | attackAdd = X+Y | 面板总加成恒等（经济口径不变，无源汇变化） |

## 5. 未完成 / 登记

1. **B3 收口项不动**：codegen D9（生成器缺人工补全成员）维持「重跑+人检+还原」口径至 B3；装备四列/功法 stats 键/旧档归一化声明全部按计划 B3 退役。
2. **归一化源清理**：DiscipleSurrogate 69–72/62–65/36–39 deprecated 声明、ItemEffect/PillEffect 旧四列、WorldLevel/AICaveDisciple 旧列——全部旧档完成迁移后按退役流程清理（登记于 FrozenTest b1 legacy 用例消息）。
3. **docs 更新延后**：`knowledge-base/architecture/cpp-engine/ui-read-surface` 的属性描述更新按批次表归 B5（本批 ui-read-surface 镜像面变更已在本报告 §1.3 登记，B5 汇总入册）。
4. **DiffBridgeSourceSyncGuardTest**：重录后 recorded sources 与工作树逐字节一致（本批随 B1 收口通过）。
5. **途中发现（已修）**：`InnateDamageType.deriveFromRoot` 空串初版落 MAGIC，与迁移 SQL `ELSE 'PHYSICAL'` 不一致——首跑即被 S21 守卫捕获，修正为空串/未知元素兜底物理。

## 6. 风险

| # | 风险 | 等级 | 说明 |
|---|---|---|---|
| 1 | 伤害数值全量变化（单列 attack=物+法，攻防基数翻倍进公式） | 已核实（方案预期） | §15.4 明示「战斗平衡一次重算」；Q7 战力面恒等、经济面不变；B4 承接数值对齐 |
| 2 | 迁移已执行且用户已存新档后不可逆 | 已核实（方案 §4.2 回滚） | 依赖迁移前备份链（backupDatabaseForMigration 落 `.pre_migrate_backup.v61`） |
| 3 | 黄金序列重钉依赖双端一致性 | 已核实 | C++ 黄金值由实跑钉住，Kotlin DiffDiscipleFactory/DiffBattleExecution 双端互拍通过；若 Kotlin 序列与 C++ 分叉会在 Diff 系失败（当前全绿） |
| 4 | determinism_probe 基线变更 | 已核实 | 探针脚本含方差抽取序列，B1 改 7→5 抽必然变更；re-record 理由登记于常量注释（惯例允诺在提交说明重申） |
| 5 | pills 表 ADD COLUMN 后旧列留存 | 已核实（方案 §5.1 不删列纪律） | 旧列由 *Total 读面归一化，写入面只写新列；B3 装备批清理 disciples 旧列时一并评估 pills 旧列 |
