# W5 收口批接管验证报告（2026-09-20）

> 对象：`docs/parallel-batches-w5/handover-closing-batch-2026-09-20.md`（W5 收口批交接文档）
> 执行：按交接文档 §3 SOP 跑完 §2 全部待验证项，并处置途中发现的缺陷。
> 结论：**全部验证项通过；途中发现并根治 2 个真实缺陷（1 个交接文档未预期 + 1 个 detekt 打回）。**

---

## 1. 核心发现：C 组 `GameDataTransientFace` 注解反射静默失效（交接文档未预期）

### 症状
`GameDataFieldPatchGuardTest` **3 个用例红**，均为「@Transient 面两臂逐值分歧」，
首个分歧点 = **`slotId`（expected 0 / actual 3）**——即 **legacy 臂（旧全量 JSON 往返）
把 `slotId` 打回默认 0**（新实现应保留事务前值 3）。

### 根因链（逐步实证）
1. 原实现：`GameData::class.java.declaredFields.filter { it.isAnnotationPresent(Transient) }`
2. 实测枚举结果 = **0 个字段** ⇒ `carryOver` 静默变 **no-op**
3. 为什么枚举不到：
   - `GameData` 字段是**主构造器 `var` 参数**；
   - `kotlinx.serialization.Transient` 的 `@Target` 含 `PROPERTY`/`VALUE_PARAMETER`
     但**不含 `FIELD`** ⇒ Kotlin 编译器**不把注解写到 JVM 字段上**；
   - 实测：`declaredFields` = **150** 个，带任何注解者 = **0**；
   - 构造器参数注解亦**为空**，且 `Parameter.isNamePresent = false`
     （未开 `-parameters` 编译选项，参数名不可得）⇒ 构造器参数反射路线**也不通**。

### 根治（权威面法）
改用 **kotlinx 序列化 descriptor 差集**：

```kotlin
val serializedNames = GameData.serializer().descriptor.elementNames.toSet()  // 137
val transientFields = GameData::class.java.declaredFields
    .filterNot { Modifier.isStatic(it.modifiers) }
    .filterNot { it.isSynthetic || it.name.startsWith("$") }
    .filterNot { it.name in setOf("Companion", "serializer") }
    .filterNot { it.name in serializedNames }   // 差集 = 9 个 @Transient 字段
    .onEach { it.isAccessible = true }
```

- 实测精确得 **9 个**字段、**双向无残余**（`descriptor − fields` 为空），
  与文档 §1C 注释的"9 个字段"一致：
  `slotId` / `autoSaveIntervalMonths` / `aiBeastEncounterTargets` / `aiSectDisciples` /
  `aiSectBeastSkipCooldowns` / `lockedBeastIds` / `aiSectBeastDirectTargets` /
  `battleTeam` / `aiBattleTeams`
- **新增 @Transient 字段自动纳管**（进不了 descriptor 即自动入差集）⇒ 结构性防复发，
  符合红线 §5.2 要求；
- **不引入 `kotlin-reflect` 生产依赖**（本仓库该库仅 `testImplementation`，
  生产代码不可用 ⇒ 改依赖树会牵动 APK 体积与 detekt 基线）。

### 验证
- `GameDataFieldPatchGuardTest` 全类绿（含新增的防复发守卫）
- **桌面 `DiffMirrorArmConvergenceTest` 三臂收敛全绿**（C 组终极判据）

---

## 2. detekt 打回：B05 重构残留未使用参数（组合门第 2 跑唯一失败点）

```
core/engine/.../exploration/PatrolBattleSystem.kt:542:9
Function parameter `state` is unused. [UnusedParameter]
```

**根因**：B05 根治把灵石入账移进 `applyVictoryGdChanges` 链、`applySpiritStoneReward`
删直写后，`applyVictoryRewards` 的 `state` 参数成为**空参数**（B05 重构的直接产物）。

**修复**：**删参数** + 调用点同步（非加 `@Suppress`——该 `state` 是纯历史残留，
不同于同文件 `applySurvivorSoulAndAttribute.target` 那种"API 决策域"语义形参）。

**验证**：`:core:engine:detekt` 单跑绿；engine 模块全量复跑
（`--rerun-tasks` 57 executed / 16m30s）**BUILD SUCCESSFUL**、`core:engine` **3374/0 fail/0 skip**。

---

## 3. 验证结果矩阵（全部项）

| 项 | 命令/方法 | 结果 |
|---|---|---|
| A：ctest 全量 | `ctest -j 8` @ `build/desktop-test` | ✅ **1556/1556**（74.87s） |
| A：单进程直跑（UAF 判定） | `cd gamecore && ./build/desktop-test/test/game-core-tests.exe` | ✅ **exit=0、1553/1553、23.8s** |
| A：NDK arm64 构建 | `./gradlew :app:externalNativeBuildRelease` | ✅ BUILD SUCCESSFUL（NDK r27） |
| B：`ExplorationPatrolRouteTest` | `:core:engine:testReleaseUnitTest --tests …` | ✅ 全类绿 |
| C：`GameDataFieldPatchGuardTest` | 同上 | ✅ 修复后转绿（原 3 红） |
| C：`DiffMirrorArmConvergenceTest` | 携桌面桥 | ✅ 三臂收敛全绿 |
| C/F：`MirrorProtoFeedEquivalenceTest` / `MirrorSegmentProjectionBenchTest` | 同上 | ✅ 绿 |
| D：`check-jni-count.mjs` | `node scripts/check-jni-count.mjs` | ✅ **91/91**，双桥无扩散 |
| D：ci.yml 合法性 | 人工逐行核对（无 pyyaml 可用） | ✅ `jni-count-gate` 结构正确 |
| E：`:app` 测试 | `:app:testReleaseUnitTest --tests …` | ✅ 2 类绿 |
| E：preBuild 链 | 删残留 → `./gradlew :app:preBuild` | ✅ 跑通、`footprint_table.h` **不再生成** |
| **③ 全量组合门** | 见下 | ✅ **BUILD SUCCESSFUL** |

### ③ 全量组合门（第 2 跑，清锁后）

```
./gradlew testReleaseUnitTest --max-workers=1 --rerun-tasks \
  "-Dgamecore.jni.path=C:\...\libgamecorejni.so" detekt compileReleaseKotlin lintRelease
```
- **BUILD SUCCESSFUL in 55m07s，230 actionable tasks: 230 executed**（非 UP-TO-DATE）
- 六模块：**7876 tests / 0 fail / 0 err / 17 既有 skip**

| 模块 | tests | fail | skip |
|---|---|---|---|
| core:engine | **3374** | 0 | 0 |
| core:domain | 1743 | 0 | 0 |
| core:data | 716 | 0 | 15（既有） |
| core:ui | 146 | 0 | 0 |
| feature:game | 887 | 0 | 0 |
| app | **1010** | 0 | 2 |
| **TOTAL** | **7876** | **0** | **17** |

- **`Diff*` 50 类 273 用例 0 失败 0 skip**（对拍桥真加载）
- `SoftwareCanvasBackend*` 10 类 106 用例 0 失败（Canvas 红线）

> **第 1 跑环境故障（非代码红）**：`:app:mergeReleaseResources` 在 30m01s 时撞 Windows 文件锁
> （`Unable to delete directory ... merged_res/release/mergeReleaseResources`，
> `99 executed`、**测试面零证据**）⇒ 按既有 SOP 清锁后整门重跑（即上表第 2 跑）。
> **教训**：此错早于 `testReleaseUnitTest`，第 1 跑的 "99 executed" 不能当门结果。

---

## 4. 文档修正（对账口径）

| 项 | 交接文档原值 | 实测值 | 说明 |
|---|---|---|---|
| `:core:engine` | 3372 → 3374 | **3374** ✅ | 一致 |
| `:app` | 1004 → 1003 | **1010**（含 2 skip） | **不同绝对口径**；差额 −1 一致（`FootprintTableSyncTest` 用例 1 退役后为 **2 用例**，已实证生效）。以实测 1010 记基线 |
| E 组测试位置 | 并列于 `:app` 门 | `BuildingSpriteFootprintJsonGuardTest` **不在 `:app`** | 建议按模块分开核 |

---

## 5. 顺带完成的 §4 剩余工作

- **文档三件套**：方案 §7.3 收口批（A–F 六项 + 计数 + G2 拍板）、CHANGELOG 4.01.15 段
  （含 B05 玩家可见修复口径）、`docs/cpp-engine.md`（`index_snapshot.h` 一段）
- **`batch-B18.md`**：已写，含**显式到期判据**（一完整发版周期 + 零回滚事件，未满足不得开工）
- **`aiBeastEncounterTargets` 死臂核查**：✅ **已确证**——全仓（Kotlin+C++）**零插入者**
  （仅 1 读 `GameEngineExplorationNativeOps.kt:178` + 1 读 `ExplorationServiceBeastRaidOps.kt:128`
  + 1 删 `:179-180`）⇒ 表恒空 ⇒ 两处门判恒 false ⇒ 遭遇战路径整条死代码。按 B18 口径登记，不顺手删
- **G2/WS-1 拍板**：选项 A 分档基线（D≤1000 <10ms、D=5000 <150ms），WS-1 以"已收口"关闭，
  残余成本中心①② 登 B18
- **B17 整批关闭**（由 B17-min + 既有 ci 覆盖）；台账 `dispatch-ledger.md` 更新

---

## 6. 副产物说明（勿混装提交）

`android/app/src/main/assets/atlas/atlas-rgba-manifest.json` 的 `generatedAt` 时间戳被 B15
`generateOfflineRgbaAtlas` 任务刷新（**像素内容逐位未变**，B15 四层守卫可证）⇒
建议**不纳入**本次提交，或单独一笔标注"仅时间戳"。

---

## 7. 分组提交建议（按交接文档 §0 六组独立 commit）

| 组 | 文件 | 备注 |
|---|---|---|
| A | `data/index_snapshot.h`(新) + `trait_db.h` + `recipe_db.h` | C++ 侧，已构建验证 |
| B | `PatrolBattleSystem.kt` + `ExplorationPatrolRouteTest.kt` | **含 detekt 修复**（参数删除）；生产+测试同 commit |
| C | `GameDataTransientFace.kt`(新) + `GameDataFieldPatch.kt` + `StateSyncService.kt` + `GameDataFieldPatchGuardTest.kt` + `SaveLoadViewModelCloudLoadOps.kt` | **C 组 5 文件须同一 commit**；含反射失效根治 |
| D | `check-jni-count.mjs`(新) + `jni-count.baseline.json`(新) + `.github/workflows/ci.yml` | — |
| E | `app/build.gradle` + `FootprintTableSyncTest.kt` + `BuildingSpriteFootprintJsonGuardTest.kt` | — |
| F | `game_view.proto` + `GameViewMirrorCodec.kt` + `GameEngineCoreAuthoritativeOps.kt` + `MirrorProtoFeedEquivalenceTest.kt` + `MirrorProtoFeedFixture.kt` + `MirrorSegmentProjectionBenchTest.kt` + `GameDataFieldPatchGuardTest.kt` | proto 仅注释；GuardTest 与 C 组共用（归 C 更合适） |
| 文档 | 方案 §7.3 + CHANGELOG + `docs/cpp-engine.md` + 交接文档更新 + `batch-B18.md` + `dispatch-ledger.md` | — |

**待用户指示**：是否代为分组提交（交接文档 §4 第 1 项含"分组提交"，但当前工作区全未提交，
提交动作建议由用户确认）。
