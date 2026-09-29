# 派发件 · EQ-B3 装备体系原子替换（最大·原子·合并且仅合一次）

> 派发真源：`docs/design/equipment-batches/IMPLEMENTATION-BATCHES.md`（§1 铁律 / §2 门禁 / §4.4 B3 / §5 串行 / §8）+ `docs/design/equipment-set-system-refactor-plan.md` HEAD 版（§3.1/§3.4.2/§3.7/§5.7/§6.5/§9/§14）。
> ⚠️ **本批不可拆分**：「删除全部旧装备+移除堆叠」会让 ~497 文件引用面同时断裂，按层拆会留不可编译中间态。**按六写面并行实施、按合并原子一次完成**（§4.4）。

## 0. 工作区与纪律

- 唯一工作区：`C:\Mnzm\XianxiaSectNative-equipment`（git worktree），分支 `feat/equipment-set`（HEAD = EQ-B2 收官笔之后，开工先 `git status` 核干净）。**禁止在主树改任何文件。**
- 本会话只实施 **EQ-B3 一批**；**单笔收官提交**（原子），共享五件套同 commit；不开下一批。
- 实施前先读：方案 HEAD（§3.1/§3.4.2/§3.7/§5.7/§6.5 A1/§9 I1/§14）+ IMPLEMENTATION-BATCHES（§1 E1–E13 / §4.4 / §5 / §7 / §8）+ 看护台账 `C:\Mnzm\XianxiaSectNative\docs\realtime-watch\DISPATCH-LEDGER.md`（只读；异议写其 §9）。
- 报告：`docs/design/equipment-batches/reports/report-B3.md`（门禁实测原数字 + 旧用例处置表按三类分组；禁「应该通过」/占位符），随收官笔入库。
- 收官：单笔提交，格式 `feat(equip): B3 装备体系原子替换——<要点>`；**版本号不自增**；**必须在更新日志预留位与报告写明「不可回退」**（方案 §9 I1，正式公告文案 B5 收口）。
- 构建副产物提交前 `git checkout --` 还原。`accepted` 由看护亲验后设置。
- 🔴 **Room v64**：现值 `DATABASE_VERSION=63`（EQ-B2 已升）实测复核 ⇒ 本批 **v63→v64**（`GameDatabaseMigrationsV64.kt` + schema `64.json` + `RoomMigrationV63To64Test` + `RoomMigrationTest` 全链延至 64）。迁移要点：`DROP TABLE equipment_stacks`、`DROP+CREATE equipment_instances`、`safeDropColumns` 8 删 5 增、**清空全部旧装备行与六部位列**（`LEGS` 与旧 `ACCESSORY` 同名异义——不清行会复活幽灵件，方案 §6.5 A1）。
- 🔴 **E12 开工前置**：`git status` 干净 + 无他线在途——命中即停手报告。

## 1. 批次任务（IMPLEMENTATION-BATCHES §4.4 原文要点；全文以批次文档为准）

| 项 | 内容 |
|---|---|
| **目标** | 一次完成：六部位（头/身/手/脚/武/腿）+ 套装（物理套/法术套 2/4/6）+ 升级 1–30（替换孕养，装卸不改等级）+ 主词条部位池随机 + 3 副词条（7 项池权重 13/13/14/15/15/15/15）+ **删除全部旧装备** + **移除堆叠** + 产出链/镜像/C++/UI 全量适配 + Room v64 + 旧装备折算补偿（100% basePrice，单档 1 亿上限，幂等同事务）+ 同批清偿 **D1/D9/D10**。 |
| **六个写面（互不重叠；合并原子）** | **A 领域+静态数据**（WP1+WP0 的 EquipmentInstance reserved：`EquipStat.kt`/`EquipAffix.kt`/`Items.kt`/`DiscipleComponents.kt`/`EquipmentDatabase.kt`/`EquipmentSetDatabase.kt`/`EquipMainStatPool.kt`/`EquipAffixPool.kt`/`EquipmentRegistry.kt` 降级转发/`ForgeRecipeDatabase.kt`/`EquipmentFactory.kt`/`scripts/data/equipment_db_sample.json`/`gen-templates.mjs` 补全输出+删死代码→**D9/D10 本批收口**）<br>**B 存档与迁移**（WP2：`GameDatabaseMigrationsV64.kt`/`EquipmentDaos.kt`/`SaveDataReconciler.kt`/`OldSaveFormatDeserializer.kt`/`EquipmentRefRule`/`EquipmentDedupeRule`/`EquipmentValueSanitizeRule`(新)/`EntityCountBoundsRule`/`JsonConverters.kt`/schema `64.json`）<br>**C Kotlin 引擎**（WP3：`EquipmentLevelSystem`/`EquipmentUpgradeService`/`EquipStatResolver`/`DiscipleEquipmentService`/`DiscipleEquipmentManager`/`DiscipleStatCalculator*`/`CaptiveGearUtils`/产出链 10 处/`RngPartition.EQUIPMENT(13)`/镜像 `StateSyncService`/`GameViewMirrorCodec`/`GameViewDiscipleRows`）<br>**D C++**（WP4：`equip_affix.h`/`equip_set_bonus.h`/`equip_main_stat.h`/`equipment_tx.h`(新)/`disciple_tx.h`/`auto_gear.h`/`disciple_stats.h`/`inventory.h`/`ai_sect_ops.h`/`ai_sect_recruit.h`/`rng_manager.h` + 静态表/编解码/分发 + GTest）<br>**E UI**（WP5+WP7 的 0.2-1/0.2-12/0.2-13：六宫格/套装面板/升级与分解对话框/仓库去角标+筛选排序批量分解/锻造 12 配方/商店/邮件/精灵图 12 张+`ItemSortUtils` 关注键改实例 id+`Combatant` 装备展示字段六部位化）<br>**F 测试与文档**（217 个装备测试文件对齐+`Equipment*GuardTest` 新增+双更新日志+ADR） |
| **验收判据** | ① 方案 **S1–S8/S12/S14/S17/S18** 全达标；② `RoomMigrationV63To64Test`+`RoomMigrationTest` 全链 v11→64 绿；③ `DiffEquipmentGenerationTest`/`DiffEquipmentUpgradeTest`/`DiffEquipmentSetBonusTest` 逐位一致；④ `ctest` 含 `equipment_tx_test`/`equip_affix_test`/`equip_main_stat_test`/`equip_set_bonus_test` 全绿；⑤ `EquipmentSingleSourceGuardTest`（D1）+`TemplateCodegenIntegrityGuardTest`（D9/D10）+**门禁 G0 严格判据自本批起生效**；⑥ `EquipmentLevelPersistGuardTest`+`EquipmentSetBonusTest`（0–6 件）+`EquipmentValueSanitizeRuleTest`；⑦ 217 个装备测试文件全绿；⑧ `lintRelease detekt`（baseline 只缩不增）。 |
| **旧用例处置** | 按 `EquipmentStack` 删除/孕养→升级/四槽→六部位 三类分组，逐文件标注「删除/改断言/保留+理由」。 |
| **回滚** | 无运行时开关；兜底=迁移前备份+云存档+强制更新；**更新日志与登录流程写明不可回退**。 |
| **规模** | 最大（~500 文件；其中 ~217 为测试）。 |

补充要点：**E1 冻结表**（EQ-B0 守卫在位）：`headId(112)/bodyId(113)/handsId(114)/feetId(115)/legsId(116)`+`innateDamageType(117)` 已接线；`weaponId(17)` 唯一复用；退役 `accessoryId(20)/armorId(18)/bootsId(19)/weaponNurture..(24..27)/pillNurtureSpeedBonus(47)/equipmentNurturing*(98,99)/basePhysical·MagicAttack·Defense(69–72)/方差两列/ItemEffect(3,8)/SaveData.equipmentStacks(53)/EquipmentInstance(3,4,7,10,13,14,15,50–56)/EquipmentSlot 旧 0–3`——本批落地退役时**逐号对照方案 §四**，`EquipmentProtoNumberFrozenTest` 同步增量。六部位枚举 `HEAD(10)/BODY(11)/HANDS(12)/FEET(13)/WEAPON(14)/LEGS(15)`（声明序=显示序，无饰品）；主词条池与权重以方案 §3.4.2/§3.7 HEAD 为唯一真源。

## 2. 门禁命令（收官前全跑；报告记实测原数字）

worktree `android/` 下执行；测试一律 `--max-workers=1`：

1. `./gradlew.bat compileReleaseKotlin`
2. `./gradlew.bat testReleaseUnitTest --max-workers=1 -Dgamecore.jni.path=C:/Mnzm/XianxiaSectNative-equipment/android/core/engine/build/desktop-jni/libgamecorejni.so`（六模块全量含 feature:game）
3. 🔴 **本批大触 C++**：`pwsh -NoProfile -File scripts/build-desktop-jni.ps1`（worktree 内重编）→ engine Diff 门（同 jni.path）
4. 桌面 ctest 全量（llvm-mingw PATH：`C:/Users/cp050/llvm-mingw/llvm-mingw-20260616-ucrt-x86_64/bin` + `C:/Users/cp050/AppData/Local/Android/Sdk/cmake/3.22.1/bin`；构建目录 `app/src/main/cpp/gamecore/build`）
5. `./gradlew.bat lintRelease detekt`
6. worktree 根：`node scripts/check-jni-count.mjs`（基线 **87/87**；本批若新增 equipment_tx JNI 口按实际重锚+豁免理由）+ `node scripts/check-agent-instructions.mjs`
7. **codegen 漂移门（G0——本批起恢复严格判据）**：本批 A 面补全 `gen-templates.mjs` 输出+删死代码后，重跑 `git diff` **必须零差异**（D9/D10 收口验证）；`gen-game-data.mjs --check` 通过；`gen-recipe-db.mjs` 链同理

## 3. 环境教训（必读）

- feature:game 全量 Robolectric 多 daemon 会 OOM：跑前清别线 java。大批门禁多轮耗时正常。
- ctest 缺 llvm-mingw PATH = 假红；改 C++ 后必重跑 build-desktop-jni.ps1（worktree 内，禁跨工作区拷 .so）。
- `atlas-rgba-manifest.json`/`sprite-uid-map.json`/`scene_uv_tables.h` 构建副作用 checkout 还原勿混入提交。
- 5 小时用量上限：触顶会暂停（界面横幅显示重置时点）——重置后等看护「继续」或自行续跑，现场（未提交成果）勿动勿删。
- 诚实纪律：门禁失败须归因入报告。

## 4. 前批交付事实附录（EQ-B0/B1/B2，看护填）

1. **三批均已 accepted**：EQ-B0 `5280d1b46`（4 文件，冻结表+守卫）、EQ-B1 `9068049a1`（amend 后，191 文件，属性单列+Room v62）、EQ-B2 `fa36109fc`（amend 后，79 文件，孕养丹退役+Room v63）；worktree 树净，HEAD = EQ-B2。
2. **门禁基线**：六模块 JVM **7556/0/18**、ctest **1481/1481**（SingleColumnStat 8 用例在列；1482→1481 系 RecipeDb 测试归并）、jni-count **87/87**、detekt 零违规、agent-instructions 全绿（根 AGENTS.md 精简后 30742/32768）。
3. **E1 冻结表现状**：新增段 112–117 已接线（EQ-B1）；reserved 锁面 = 存量 `7,8,11–16,22,29,50,76,88,93,95,102,104,105,110` + 计划退役 `18/19/20/24..27/47/53/69–72/98/99` + EquipmentInstance 段（本批 B3 落地）；**守卫 `EquipmentProtoNumberFrozenTest` 在位——本批退役落地时同步增量冻结表**。
4. **物法已单列**（EQ-B1）：`attack/defense` 两列 + 三通道（`innateDamageType`/`skill.damageType`/类型桶）；伤害类型判定=普攻取 `attacker.innateDamageType`（物攻≥法攻启发式已退役）——B3 装备词条的类型增伤/减伤分桶直接挂此通道。
5. **「保留+归一化」先例**（EQ-B1/EQ-B2 惯例）：旧列保留声明 deprecated 只读 + `*Total` 归一化读取源——B3 删旧装备列时对「需兼容读取的」沿用、对「彻底退役的」走 reserved（逐号对照方案 §四）。
6. **G0 语义切换**：EQ-B0/B1/B2 期间 codegen 门=重跑+人检+还原（D9 在案）；**本批 A 面补全生成器后 G0 恢复严格判据**（重跑零差异）——这是 D9/D10 的收口验证，报告必须给出重跑零差异证据。
7. **对拍桥自洽**：.so+指纹为 EQ-B1/B2 期 worktree 重编产物——本批大触 C++ 后照常 worktree 内重编。
8. **实时线遗留三项勿顺手处置**：`processAutoAlchemy` 生产零调用；bench `overBudgetCount==0` 断言噪声脆弱；`kMsPerPhase`/`kGameMsPerPhase` 同值并存待归一。**双 changelog 归 EQ-B5**（EQ-B2 起的批次分工，B3 不动双更新日志但须在报告预留「不可回退」公告文案）。
