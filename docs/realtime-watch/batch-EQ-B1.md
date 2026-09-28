# 派发件 · EQ-B1 属性机制重构（单列 + 类型通道 + 固有伤害属性）

> 派发真源：`docs/design/equipment-batches/IMPLEMENTATION-BATCHES.md`（§1 铁律 / §2 门禁 / §4.2 B1 / §5 串行约束 / §8）+ `docs/design/equipment-set-system-refactor-plan.md` HEAD 版（§15 属性重构 / §四编号表）。

## 0. 工作区与纪律

- 唯一工作区：`C:\Mnzm\XianxiaSectNative-equipment`（git worktree），分支 `feat/equipment-set`（HEAD = EQ-B0 收官笔之后，开工先 `git status` 核干净——EQ-B0 已 accepted 树净）。**禁止在主树改任何文件。**
- 本会话只实施 **EQ-B1 一批**，完成即收官提交；不开下一批。
- 实施前先读：方案 HEAD（§15 / §四 / §3.4.2）+ IMPLEMENTATION-BATCHES（§1 / §4.2 / §5 / §8）+ 看护台账 `C:\Mnzm\XianxiaSectNative\docs\realtime-watch\DISPATCH-LEDGER.md`（只读；异议写其 §9）。
- 报告：`docs/design/equipment-batches/reports/report-B1.md`（门禁实测原数字；禁「应该通过」/占位符），随收官笔入库。
- 收官：单笔提交，格式 `feat(attr): B1 属性机制重构——<要点>`；共享五件套（sample.json/codegen 产物/models.h 链/迁移文件/对拍基线）**同一 commit**（§5.3/§6.4）；**版本号不自增**。
- 构建副产物（atlas/scene_uv/sprite-uid 等）提交前 `git checkout --` 还原。
- `accepted` 由看护亲验后设置。
- 🔴 **Room v62**：现值 `DATABASE_VERSION=61` 实测复核 ⇒ 本批 **v61→v62**（`GameDatabaseMigrationsV62.kt` 新增 + 版本 +1 + `ALL_MIGRATIONS` 注册 + schema `62.json` + `RoomMigrationV61To62Test`）。**禁 DROP COLUMN**（4 列删走 `safeDropColumns` + 3 列 ADD 带 DEFAULT）；迁移前备份链沿用。
- 🔴 **E12 开工前置**：`git status` 干净 + 无他线在途（实时线/设计线均已收官）——命中即停手报告。

## 1. 批次任务（IMPLEMENTATION-BATCHES §4.2 原文）

| 项 | 内容 |
|---|---|
| **目标** | 按方案 §15 把 `物攻/法攻 + 物防/法防` 四列收敛为 `attack/defense` 两列；物法差异改由三条通道承载（普攻 `innateDamageType`、技能 `damageType`、类型增伤/减伤分桶）；旧值映射 = **取两列之和 × 系数 k**（Q7）。 |
| **前置** | EQ-B0 完成（编号已冻结 ✓）。 |
| **写入面** | ① 领域：`Disciple.kt`(`DiscipleStats`/`ItemEffect`)、`DiscipleComponents.kt`(`CombatAttributes`/`PillEffects`)、`DiscipleSerializer.kt`(60–73 段 + 117)、`CharacterTemplate.kt`(+codegen) ② 数据：`GameDatabaseMigrationsV62.kt`（**新增**）、`GameDatabase.kt`（版本 +1、注册）、schema `62.json` ③ 引擎：`DiscipleStatCalculator*`、`BattleCalculator*`、`DamageZones`、`SectCombatPowerCalculator`、`EnemyGenerator`、`AISectAttackManager`、`HeavenlyTrial*` ④ C++：`disciple.h`/`disciple_stats.h`/`battle.h`/`battle_calculator.h`/`battle_json.h`/`state/models.h`/`disciple_store.h`/`column_dirty.h`/`json_codec.cpp`/`data/beast_config.h` ⑤ UI：弟子面板四行→两行 + 固有属性标签 ⑥ 测试：全部 `DiffBattle*`/`Diff*Settlement*` 基线**一次性重录** |
| **影响面** | 存储：Room `disciples` 4 列 → 3 列（`baseAttack/baseDefense/innateDamageType`）+ ProtoBuf 编号退役/新增；**战斗平衡一次重算**；UI 面板字段变化；经济：丹药/功法数值口径不变，无源汇变化。 |
| **兼容性** | ① Migration：`safeDropColumns` 删 4 列 + `ADD COLUMN` 3 列（带 DEFAULT）；② 旧档回填：`attack = 旧物攻+旧法攻`、`defense = 旧物防+旧法防`、`innateDamageType` 按 `templateId` 从模板取（**幂等**）；③ ProtoBuf：旧编号 `reserved`（禁复用）+ 新编号；④ 跨端：三端字段链同 commit；⑤ 对拍基线重录需在报告中给出「新旧数值对照表」。 |
| **验收判据** | ① `ctest` 全绿（含新增 `single_column_stat_test`）；② `DiffBattle*`/`DiffBattleExecution*`/`DiffSectBattle*` 全绿；③ **S19**：默认桶全 0.0 时伤害与旧公式逐位一致；④ **S20**：`LegacyStatMigrationTest` 断言迁移前后总战力比 ∈ [0.98,1.02]；⑤ **S21**：`InnateDamageTypeGuardTest`；⑥ 全仓符号面 `physicalAttack`/`magicAttack`/`physicalDefense`/`magicDefense` 作为**属性名**归零（`SingleColumnStatGuardTest`）；⑦ `compileReleaseKotlin` + `lintRelease detekt` 绿。 |
| **旧用例处置** | 给「旧用例处置表」：断言四列数值的用例 → 改断言单列和值（附换算依据）；`BattleCalculatorTest` 期望值 → 按新公式重算并注明；不保留双列断言。 |
| **回滚** | 代码 revert + 迁移前备份恢复；不可逆点：迁移已执行且用户已存新档 ⇒ 靠备份。 |
| **规模** | 大（~120 文件；其中 ~40 为测试基线）。 |

补充要点（E1/E2/E3/E9/E10 全文见批次文档 §1，逐条铁律遵守）：`innateDamageType(117)` 在 EQ-B0 已按 `String=""` 占号——本批接线时如需改型（varint 枚举），属 wire 变更，须按 E1 流程改冻结表 + 同步 `EquipmentProtoNumberFrozenTest` 后实施（报告登记）；`weaponId(17)` 复用与本批无涉勿碰。

## 2. 门禁命令（收官前全跑；报告记实测原数字）

worktree `android/` 下执行；测试一律 `--max-workers=1`：

1. `./gradlew.bat compileReleaseKotlin`
2. `./gradlew.bat testReleaseUnitTest --max-workers=1 -Dgamecore.jni.path=C:/Mnzm/XianxiaSectNative-equipment/android/core/engine/build/desktop-jni/libgamecorejni.so`（六模块全量含 feature:game；带参防 Diff*Test skip）
3. 🔴 **本批触 C++**：先 `pwsh -NoProfile -File scripts/build-desktop-jni.ps1`（**worktree 内重编，禁止从主树拷 .so**——EQ-B0 实证跨工作区 CRLF/LF 指纹不齐），再跑 engine Diff 门（同 jni.path）
4. 桌面 ctest 全量（llvm-mingw PATH 必带：`C:/Users/cp050/llvm-mingw/llvm-mingw-20260616-ucrt-x86_64/bin` + `C:/Users/cp050/AppData/Local/Android/Sdk/cmake/3.22.1/bin`；构建目录 `app/src/main/cpp/gamecore/build`）
5. `./gradlew.bat lintRelease detekt`
6. worktree 根：`node scripts/check-jni-count.mjs`（基线 **87/87**；本批若动 JNI 面按实际重锚并附豁免理由）+ `node scripts/check-agent-instructions.mjs`
7. **codegen 漂移门（G0 修正语义）**：`node scripts/gen-templates.mjs ; git diff --stat` 后「**重跑 + 人检 + 还原**」——当前 D9 在案（生成器输出缺人工补全成员，B3 同批收口），重跑必现删 `operator==`/`*TemplatesMutable()` 差异，**非红灯信号**；人检确认差异仅此类后 `git checkout --` 还原（EQ-B0 报告 §5-② 口径，B0–B2 通用）

## 3. 环境教训（必读）

- feature:game 全量 Robolectric 多 daemon 会 OOM：跑前清别线 java；daemon 卡死 → 杀本工作区相关 java 重跑。
- ctest 缺 llvm-mingw PATH = 0xc0000135 假红；改 C++ 后必重跑 build-desktop-jni.ps1（worktree 内）→ 否则 Diff 全系假红。
- `atlas-rgba-manifest.json` 等构建副作用 checkout 还原勿混入提交。
- 诚实纪律：门禁失败须归因入报告。

## 4. 前批交付事实附录（EQ-B0，看护填）

1. EQ-B0 收官笔 `5280d1b46`（4 文件 +269/−1）；**看护已亲验 accepted**；worktree 树净，HEAD 即该笔。
2. **门禁基线**：六模块 JVM **7546/0/18**（app 1028/0/2、data 836/0/15、domain 1585/0/0、engine 2949/0/1、ui 155/0/0、feature:game 993/0/0——data +5 = 新增 FrozenTest 用例）；jni-count **87/87**；detekt 六模块 0；lint 存量警告；agent-instructions 468 引用全绿（根 AGENTS.md 已精简 30742/32768）。
3. **E1 冻结表已生效**（`EquipmentProtoNumberFrozenTest` 5 用例守卫）：新增段 `headId(112)/bodyId(113)/handsId(114)/feetId(115)/legsId(116)`+`innateDamageType(117)` 按 `String=""` **占号未接线**；`weaponId(17)` 唯一复用；reserved 锁面 = 存量 `7,8,11–16,22,29,50,76,88,93,95,102,104,105,110` + 计划退役 `18/19/20/24..27/47/98/99` + `SaveData.equipmentStacks(53)`。**本批接线 `innateDamageType` 如需改型须先按 E1 流程改冻结表+同步守卫**（报告登记）。
4. **对拍桥自洽状态**：worktree 内 `.so`+指纹为 EQ-B0 期 worktree 重编产物（CRLF/LF 跨工作区逐字节同源不成立——**一切 .so 构建在 worktree 内做**）；首轮 `DiffBridgeSourceSyncGuardTest` 判红即此因，重编即绿。
5. **G0 修正语义**（EQ-B0 报告 §5-② 采纳）：D9 在案，B0–B2 期间 codegen 门 = 重跑+人检+还原；严格红绿判据待 B3 生成器补全后恢复。
6. **worktree 本机件已归位**：`api.properties`/`keystore.properties`/`local.properties` 均在 `android/` 下（EQ-B0 修正过看护拷贝错位）。
7. **实时线并网语义背景**（B1 对拍基线重录的环境事实）：权威轴 `elapsedGameMs`+双轨 L0–L4 已并网，`GameTimeClock.phaseProgressFlow` 旬内进度真源；速度维度已删（单一时速）；`DiffAuthoritativeTickTest` 等 Diff 家族守卫在位——B1 重录 DiffBattle* 基线时以此为准，勿回退旧语义。
8. **实时线遗留三项勿顺手处置**：`processAutoAlchemy` 生产零调用；bench `overBudgetCount==0` 断言噪声脆弱；`kMsPerPhase`/`kGameMsPerPhase` 同值并存待归一。
