# 派发件 · EQ-B2 孕养类加成丹药退役（R11）+ 补偿

> 派发真源：`docs/design/equipment-batches/IMPLEMENTATION-BATCHES.md`（§1 铁律 / §2 门禁 / §4.3 B2 / §5 / §8）+ `docs/design/equipment-set-system-refactor-plan.md` HEAD 版（§5.7 补偿 / §六）。

## 0. 工作区与纪律

- 唯一工作区：`C:\Mnzm\XianxiaSectNative-equipment`（git worktree），分支 `feat/equipment-set`（HEAD = EQ-B1 收官笔之后，开工先 `git status` 核干净）。**禁止在主树改任何文件。**
- 本会话只实施 **EQ-B2 一批**，完成即收官提交；不开下一批。
- 实施前先读：方案 HEAD（§5.7 补偿 / §六 / §四）+ IMPLEMENTATION-BATCHES（§1 E1–E13 / §4.3 / §5 / §8）+ 看护台账 `C:\Mnzm\XianxiaSectNative\docs\realtime-watch\DISPATCH-LEDGER.md`（只读；异议写其 §9）。
- 报告：`docs/design/equipment-batches/reports/report-B2.md`（门禁实测原数字；禁「应该通过」/占位符），随收官笔入库。
- 收官：单笔提交，格式 `feat(equip): B2 孕养丹退役+补偿——<要点>`；**版本号不自增**。
- 构建副产物（atlas/scene_uv/sprite-uid 等）提交前 `git checkout --` 还原。
- `accepted` 由看护亲验后设置。
- 🔴 **Room v63**：现值 `DATABASE_VERSION=62`（EQ-B1 已升）实测复核 ⇒ 本批 **v62→v63**（`GameDatabaseMigrationsV63.kt` 新增 + 版本 +1 + 注册 + schema `63.json` + `RoomMigrationV62To63Test`）。删 1 列走 `safeDropColumns`（pillNurtureSpeedBonus）；清 `unlockedRecipes` 孕养配方为数据清理（同迁移内幂等执行）。
- 🔴 **E12 开工前置**：`git status` 干净 + 无他线在途——命中即停手报告。

## 1. 批次任务（IMPLEMENTATION-BATCHES §4.3 原文）

| 项 | 内容 |
|---|---|
| **目标** | 删除 `nurtureAdd` / `nurtureSpeedPercent` 两类丹药的**全部定义与效果链**，并对玩家已持有资产按方案 §5.7 补偿（幂等、可审计）。**本批不碰装备模型**。 |
| **前置** | EQ-B1 完成（共享 `ItemEffect`/`PillEffects`/`DiscipleSurrogate` 已稳定 ✓）。 |
| **写入面** | ① 领域：`ItemEffect` 3/8 → `reserved`、`PillEffects.pillNurtureSpeedBonus` 退役、`DiscipleSerializer.kt`(47 → `reserved`) ② 存储：`GameDatabaseMigrationsV63.kt`（**新增**）：`safeDropColumns("disciples","pillNurtureSpeedBonus")` + 清 `unlockedRecipes` 内孕养丹配方 ③ 完整性：**新增** `NurturePillRetirementRule.kt`（幂等标记同事务）+ `SaveValidationRuleRegistry.registerDefaults()` 加一行 ④ 引擎：`PillEffectApplier`/`AutoPillService`/`HpMpRecoveryService`/`DiscipleFacadeImpl战斗Ops2`/`CultivationCore`/`NumericSanitizeRule` 删判据与写入点 ⑤ C++：`data/recipe_db.h`（配方生成段/分类判据/模板映射/类型清单）、`system/pill_system.h`、`system/disciple_tx.h`、`state/models.h`、`merchant_settlement.h`、`secret_realm_session.h`、`data/data_json.h`、`state/column_dirty.h` ⑥ 奖励池：邮件/兑换码/商店/自动购买的孕养丹条目移除 ⑦ UI：相关详情/列表条目移除 |
| **影响面** | 存储：删 1 列 + 道具条目清理；**经济**：新增「补偿发放」这一纯新增源（须做额度审计）；产出：奖励池缺口需登记；UI：丹药图鉴/详情少两类。 |
| **兼容性** | 旧档：`ItemEffect(3,8)` 与 `pillNurtureSpeedBonus(47)` 保留声明 + `reserved` ⇒ 可反序列化但不再读取（读取点全删）；补偿幂等标记用 proto 预留段编号；合成品/邮件附件中的孕养丹按方案 §5.7 折算。 |
| **验收判据** | ① **S13**：全仓 grep 归零（`nurtureAdd`/`nurtureSpeed`/`pillNurtureSpeedBonus`，含 C++ `recipe_db.h`）；② `NurturePillRetirementTest`：折算公式 / 配方回滚 / **幂等** / 单档 2000 万上限截断；③ `RoomMigrationV62To63Test` 绿（删列 + 其它数据零丢失）；④ C++ `recipe_db` 断言两类配方零产出；⑤ 经济基线表已登记补偿额度与产出缺口；⑥ 门禁全绿。 |
| **旧用例处置** | 删/改：涉及孕养丹效果与配方的用例（给出清单与处置）。 |
| **回滚** | 代码 revert + 迁移前备份；补偿已发放则**不回滚**（记录为已发生事件）。 |
| **规模** | 中（~45 文件）。 |

补充要点：EQ-B1 已把 `PillEffects` 四列收敛为 `pillAttackBonus/pillDefenseBonus`（Room v62）——本批退役的 `pillNurtureSpeedBonus` 是 EQ-B0 就已 `reserved` 在册的独立列（47 号），与 v62 新列无耦合；`DiscipleSerializer.kt` 操作时注意 EQ-B1 的新增段（118–123）已在位，勿误伤。proto 补偿幂等标记取号遵循 E1（方案 §四预留段，方案 HEAD 为准）。

## 2. 门禁命令（收官前全跑；报告记实测原数字）

worktree `android/` 下执行；测试一律 `--max-workers=1`：

1. `./gradlew.bat compileReleaseKotlin`
2. `./gradlew.bat testReleaseUnitTest --max-workers=1 -Dgamecore.jni.path=C:/Mnzm/XianxiaSectNative-equipment/android/core/engine/build/desktop-jni/libgamecorejni.so`（六模块全量含 feature:game）
3. 🔴 **本批触 C++**：先 `pwsh -NoProfile -File scripts/build-desktop-jni.ps1`（worktree 内重编），再跑 engine Diff 门（同 jni.path）
4. 桌面 ctest 全量（llvm-mingw PATH：`C:/Users/cp050/llvm-mingw/llvm-mingw-20260616-ucrt-x86_64/bin` + `C:/Users/cp050/AppData/Local/Android/Sdk/cmake/3.22.1/bin`；构建目录 `app/src/main/cpp/gamecore/build`）
5. `./gradlew.bat lintRelease detekt`
6. worktree 根：`node scripts/check-jni-count.mjs`（基线 **87/87**）+ `node scripts/check-agent-instructions.mjs`
7. **codegen 漂移门（G0 修正语义）**：`node scripts/gen-templates.mjs ; git diff --stat` 后「重跑+人检+还原」（D9 在案非红灯；本批改 `recipe_db_sample.json` 走 `gen-recipe-db.mjs` 同理）；`node scripts/gen-game-data.mjs --check` 校验通过

## 3. 环境教训（必读）

- feature:game 全量 Robolectric 多 daemon 会 OOM：跑前清别线 java。
- ctest 缺 llvm-mingw PATH = 假红；改 C++ 后必重跑 build-desktop-jni.ps1（worktree 内）。
- `atlas-rgba-manifest.json` 等构建副作用 checkout 还原勿混入提交。
- 诚实纪律：门禁失败须归因入报告。

## 4. 前批交付事实附录（EQ-B1，看护填）

1. EQ-B1 收官笔 `312a04983`（199 文件 +56327/−33504）；**看护已亲验 accepted**；worktree 树净，HEAD 即该笔。
2. **门禁基线**：六模块 JVM **7556/0/18**（app 1028/0/2、data 841/0/15、domain 1585/0/0、engine 2958/0/1、ui 155/0/0、feature:game 993/0/0——+10 = 迁移4+SingleColumnStatGuard1+InnateDamageTypeGuard4+Frozen 增量1）；ctest **1481/1481**（SingleColumnStat 8 用例在列；1482→1481 系 RecipeDb 测试重建归并）；jni-count **87/87**；detekt 零违规；agent-instructions 全绿。
3. **属性已单列**：`CombatAttributes.baseAttack(24)/baseDefense(18)` 两列 + `innateDamageType(117)` 已接线（String=DamageType.name）；`PillEffects` 四列 → `pillAttackBonus(122)/pillDefenseBonus(123)`；**物法四旧列保留声明 deprecated 只读**（归一化读取源 `*Total`）——本批退役孕养面时沿用此「保留+归一化」先例。
4. **Room v62 已落地**：disciples create-copy-drop-rename（删12增7幂等）+ pills ADD 2 列 + 回填取和（k=1）+ 固有属性首灵根 CASE 派生；真实 Room schema 校验绿——**本批 v63 迁移沿用同款模式**。
5. **对拍桥自洽**：.so+指纹为 EQ-B1 期 worktree 重编产物（08:04）——继续 worktree 内构建，禁止跨工作区拷贝。
6. **G0 修正语义**：B0–B2 期间 codegen/recipe-db 门 = 重跑+人检+还原（D9 在案非红灯）；`gen-game-data.mjs --check` 需通过（EQ-B1 期末 sha256 ffff3f42…）。
7. **E1 冻结表**：`pillNurtureSpeedBonus(47)` 已在计划退役锁面——本批退役即兑现该锁；`ItemEffect(3,8)` 退役同批；新增补偿幂等标记编号从方案 §四预留段取（勿占 112–123 已用段）。
8. **实时线遗留三项勿顺手处置**：`processAutoAlchemy` 生产零调用；bench `overBudgetCount==0` 断言噪声脆弱；`kMsPerPhase`/`kGameMsPerPhase` 同值并存待归一。
