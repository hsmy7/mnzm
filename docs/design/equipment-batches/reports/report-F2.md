# report-F2 — 装备四部位化 F2 批：弟子槽位与存档 + 引擎结算（Kotlin + C++ 对偶）

> 日期：2026-10-02 ｜ 分支：`feat/equipment-four-slot-f1`（F1 之上续批）
> 方案：`docs/design/equipment-four-slot-refactor-plan.md`（v1.0，批次表 §八）
> 性质：对未上线装备系统的收缩型重构；测试期 destructive rebuild，**无迁移、无补偿**。
> **数值表一字未动**（F-12/FA4：主词条基数/副词条档位/部位系数零改）。

## 一、交付范围（实测后落地面，79 文件 +1012/−1212）

### 1.1 方案 F2 清单逐项

| # | 项 | 落点 | 终态 |
|---|---|---|---|
| 1 | 弟子槽字段 6→4 | `DiscipleComponents.EquipmentSet` / `DiscipleEquipment` / `DiscipleAggregate` / `DiscipleDelegates` | `weaponId`/`legsId` 字段删除；槽位 when 4 分支；`clearedSlots` 四项 |
| 2 | 存档编号退役 | `DiscipleSerializer.DiscipleSurrogate` | `weaponId(17)`/`legsId(116)` **整属性删除**、编号 reserved 禁复用；写/读面四部位 112..115 |
| 3 | Room schema | `GameDatabaseConfig.DATABASE_VERSION` 70→**71**；`MigrationRequiredGuardTest` 基线注记同 commit（实体清单未变）；**v70 schema json 删除**（例行 SOP，`DeadCompatRemovalGuardTest` 抓获后清） | `disciples` 表 `weaponId`/`legsId` 两列随 `@Embedded` 删除；老库 destructive 重建 |
| 4 | 列式存储 | `DiscipleTables`（`weaponIds`/`legsIds` 表删除）+ ColumnRegistry/Assemblers/Write/AssembleGroup + `CultivationEventAutoWarehouseOps` 写回 | 四列表；`HpMpColumnInput` 删两字段（`HpMpColumnCoverageTest` 清单同步） |
| 5 | proto 镜像 | `game_view.proto` 122/123 删除 reserved；`GameViewMirrorCodec` 登记面、`GameViewDiscipleRows` 四处（has 表/equipmentOf/applyPatch/build） | 镜像四部位列 67..70 |
| 6 | 套装判定两档 | F1 已落（Kotlin `EquipmentSetDatabase` + C++ `disciple_stats.h`），本批零改动、仅验证 | `>= 4` 双效果同发 |
| 7 | C++ 模型与列 | `models.h`（Disciple 槽字段）、`disciple_store.h`（`DiscipleColumn::WeaponId/LegsId` 枚举 + 两列 vector）、`disciple_store.cpp`（append/reserve/clear/erase/swap 六组）、`column_dirty.h`（列名/脏导出）、`game_core.cpp`（全列登记） | 双端四列逐位对偶 |
| 8 | C++ 系统层 | `disciple_tx.h`（equip/unequip/clear/slotName/isEquipped 五组槽位分支）、`auto_gear.h`（读写辅助+写回+标脏+**槽位遍历 6→4**）、`disciple_purchase.h`（上下文+查表）、`death_handler.h`（装备持有守卫四部位）、`ai_sect_ops.h`（`aiSlotId`/`aiSetSlot`/实例映射 + **`kSlotNames[6]`→`[4]` 死表项**）、`ai_sect_recruit.h`（配装 4 部位） | |
| 9 | C++ 编解码 | `json_codec.cpp`（GC_TO/GC_FROM 四键，旧键宽松忽略）、`gameview_encode.cpp`（行表删 122/123，注释 reserved） | 与 Kotlin 存档/镜像同口径 |
| 10 | 战斗展示链 | `Combatant` 删 `weaponName`/`legsName`（C++ 侧本就不入战斗状态，battle_json.h 注释口径）；`BattleSystem`/`AISectAttackManager` 构造点删 | `BattleDescriptionGenerator` 六类武器动词表与 `getWeaponVerbs` 删除，普攻恒徒手动词表（**salt 键 `"w"` 不变，掷点序不变**） |
| 11 | 完整性规则 | `EquipmentRefRule` 删退役槽位孤儿扫描（F1 临时面收口）；`EquipmentDedupeRule` 注释口径 | |
| 12 | UI | `DiscipleDetailScreen`/`DetailBasicInfoSection`（装备 map 四部位）、`HeavenlyTrialBattleDialog`（敌方装备卡 3×2→**2×2**，`ENEMY_EQUIP_GRID_ROW_SIZE` 3→2） | |

### 1.2 新增守卫（方案 §六 6.1 两项）

| 守卫 | 内容 |
|---|---|
| `ForgeRecipeSlotGuardTest`（domain） | ① 24 条配方产出部位 ∈ 四部位集合；② 配方/部件 id 无 `_WEAPON`/`_LEGS` 后缀（FA2 防改名保留）；③ 配方 pieceId 集合 = `EquipmentDatabase` 部件全集、每套恰 4 部位 |
| `EquipmentSlotRetirementGuardTest`（domain） | 全仓主源符号面扫描（`.kt/.java/.h/.cpp/.proto`）：`weaponId/legsId/weaponIds/legsIds/WeaponId/LegsId/weaponName/legsName` + `"WEAPON"/"LEGS"` 槽位串，非注释行且无退役关键词即红（F-2/FA2）。**故意排除项**（三要素②）：`gamecore/test/`（退役零命中断言合法引用）+ `data/recipe_db.h`（旧 72 条通用配方族=codegen 产物，**F3 写入面**，F1 报告 §四 交接；F3 收口后移除排除项） |

该守卫**当批抓获两处 F1 漏网主源**：`ai_sect_ops.h:307` `kSlotNames[6]`（死表项，RNG 不受影响）与 `auto_gear.h:812` 宗门自动装备槽位遍历（真行为面，仍遍历 WEAPON/LEGS 槽名）——均已本批修复并复验。

## 二、门禁与实跑数字

| 门禁 | 结果 |
|---|---|
| `ctest`（gamecore 全量，含 bench，改动后重编复跑） | **1538/1538 全绿**（58.24s） |
| Kotlin 全量 `testReleaseUnitTest --max-workers=1`（带 `-Dgamecore.jni.path`） | **7489 tests / 0 fail / 0 err / 19 skip**；**Diff 家族实跑 277 / skip 0**（首次不带 jni.path 被 `DiffBridgeGateTest` IN8 门正确拦截——桥重编后带参复跑） |
| 桌面 JNI 桥 | `build-desktop-jni.ps1` 重编两次（models/tx/stats/auto_gear 头面变更后）；259 源同源指纹；`Diff*` 家族全绿 |
| 新守卫 | `ForgeRecipeSlotGuardTest` / `EquipmentSlotRetirementGuardTest` / `EquipmentSlotOrderGuardTest` 全绿 |
| `lintRelease` | BUILD SUCCESSFUL（0 error / 40 warnings，均主预存，与 F1 持平） |
| `detekt` | BUILD SUCCESSFUL |
| codegen 零漂移 | 重跑 `gen-templates.mjs` + `gen-game-data.mjs`，主源 `git status` 零漂移 |
| `check-jni-count.mjs` | 87/87 双桥无扩散 |
| `check-agent-instructions.mjs` | 全绿（489 引用可解析、7 AGENTS.md、32574/32768 字节） |
| 版本号 | 未动（4.01.16）；`DATABASE_VERSION` 70→71 属 schema 纪律非版本发布 |
| 工作区噪音 | `atlas-rgba-manifest.json` 构建重生成已还原（不入批） |

## 三、旧用例处置表

| 处置 | 文件 | 说明 |
|---|---|---|
| 重写（冻结表语义升级） | `EquipmentProtoNumberFrozenTest` | 17/116 移入 reserved 禁复用面（`existing reserved` 用例 +17/116）；冻结表删两键；写面用例改名 `four part section is wired in write path`、退役清单 +weaponId/legsId |
| 机械替换（部位值无关断言） | `SaveValidatorTest`（18 处）、`EquipmentRefRuleTest`（10）、`DiscipleDeadStatusRuleTest`、`EntityCountBoundsRuleTest`、`EquipmentNoCapGuardTest`、`SaveValidatorIntegrationTest`、`DiscipleModelsTest`、`AssemblePatchEquivalenceTest`、`ArchivePayloadRoundTripTest`、`CloudPayloadSizeBenchTest`、`DiscipleStatCalculatorTest`、`DiscipleStatCalculatorCombatBonusTest`、`EquipmentStatHotPathBenchmark`、`AISectAttackManagerTest`、`DiffDeathHandlerTest`、`DiscipleMergeCoverageTest`（委托属性清单） | weaponId/legsId → handsId/headId 等，断言语义不变 |
| 重写（武器名链退役） | `BattleSystemTest` | 用例改名删「武器名」；断言改 `handsName == null`（装备名不入战斗模型） |
| 清单同步 | `HpMpColumnCoverageTest`（删 legsId/weaponId 两键）、`HpMpRecoveryEquivalenceTest`（夹具 4 部位）、`GameViewDiscipleColumnApplyEquivalenceTest`（「四部位列」直写等价）、`MirrorProtoFeedFixture`（rich 夹具 4 槽）、`MirrorProtoFeedEquivalenceTest`（断言 headId/feetId）、`DiffDirtyEnvelopeEquivalenceTest`（JSON 信封删两键） | |
| 联动去魔法数字 | `GameViewDiscipleProjectionTest` | 稀疏行缺失数 `>70` → `requiredScalarFields.size - 3` 联动断言（删两必在字段后恰掉线被该测试抓获，属预期联动） |
| C++ 测试面 | `disciple_tx_test`（默认 part HANDS、六部位用例→四部位、weaponIds 断言→handsIds）、`phase_settlement_test`（6×WEAPON→HANDS、weaponId→handsId）、`gameview_encode_test`（夹具删两键、122/123 改**空断言**）、`ai_sect_ops_test`（装备计数四部位、24 条展开表）、3×bench（weaponIds→handsIds、WEAPON→HANDS） | 双臂同输入，逐位断言保持 |

## 四、途中发现（预存问题处置）

1. **F1 漏网主源两处（已修）**：`ai_sect_ops.h:307` `kSlotNames[6]`（emptySlots 恒 0..3，表项 4/5 为死数据，RNG 消耗不受影响）；`auto_gear.h:812` 宗门自动装备槽位遍历仍含 WEAPON/LEGS（行为面：`equipSlotId` 已无分支恒空串 → 对袋内武器实例做无效挑选）。均为 `EquipmentSlotRetirementGuardTest` 当批抓获，修复后 ctest/JNI 复验绿。
2. **F1 报告的 `EquipmentRefRule`「规则语义扩展」属临时面（已收口）**：F1 因字段未删而按孤儿判据清理退役槽位；本批字段已删，临时扫描面随删，规则回到纯四部位。
3. **`DeadCompatRemovalGuardTest` 拦截 v70 孤儿 schema**：版本递增后旧 schema json 未删即红（与 SS 批例行 SOP「低于当期版本 schema json 删除」一致），已删。
4. **投影守卫魔法数字**：`missing.size > 70` 隐式绑定必在字段数，字段增删必碎——改与 `requiredScalarFields.size` 联动。
5. **`BattleDescriptionGenerator` 武器动词链随武器退役失去对象（已删）**：普攻物理动词恒走徒手动词表；salt 键与容量不变 ⇒ 掷点序零漂移（Diff 全绿实证）。六类武器动词表为武器部位时代的展示资产，F3 若引入法宝/法器类部位语义需另行立项。

## 五、遗留与交接（F3 写入面）

| 项 | 交接 |
|---|---|
| **旧 72 条通用锻造配方族** | `recipe_db.h`（codegen，源 `scripts/data/recipe_db_sample.json`）+ `recipe_db_test.cpp`（`r.type == "WEAPON"` 计数断言）——F1 §四 已交接；`processAutoForgeStep` 仍从旧族选配方（完成面按部件反查失败，产出零）；退役守卫已显式登记本项为 F3 排除面 |
| 掉落/商店/AI 配装深层清理 | `mission_completion.h` 掉落产出、`RedeemCode`/商店条目、`CaptiveGearUtils` 俘虏物化深层面 |
| 24 精灵图 | `*_weapon`/`*_legs` 图与注册项删除（方案 §3.7，FA5 注册面一致性） |
| UI 部位文案 | 剩余「六部位」口径文案清扫 |
| 数值校准 | F4：装备占比实测（F1 已重锚 [0.22,0.40] 带，中位约 27%）+ 文档/ADR/双 changelog |

## 六、DoD 对照（本批相关项）

| DoD | 状态 |
|---|---|
| 2. 武器/腿部全链零残留（退役守卫绿） | ✅（主源符号面扫描；排除面=测试断言+F3 配方族，已显式登记） |
| 3. 套装档位恰 2/4、无 `>= 6` | ✅（F1 落地，本批 ctest+Diff 复验） |
| 5. 无隐藏补偿 | ✅（数值表零改动；F1 幂等重生成零漂移） |
| 7. 弟子槽字段恰 4；Room 版本与基线同 commit | ✅（v70→v71 + 基线注记 + v70 schema 删除） |
| 8. 双端确定性（ctest + Diff 逐位） | ✅（1538/1538；Diff 实跑 277/0 skip） |
| 9. 门禁全绿（lint/detekt/codegen/jni-count/agent-instructions） | ✅ |
| 1/4/6/10/11 | F1 ✅ / F3 / F4 |
