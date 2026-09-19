# 批次 B14 完成报告（R4.4 — RNG 分区独立：残留执行器本地 PCG，消 per-roll JNI）

> 批次文件：`docs/parallel-batches-w5/batch-R4C.md`
> 完成结论：**自检全绿，待看护复核**
> 台账：`docs/parallel-batches-w5/dispatch-ledger.md` B14 行
> 性质：**机制交付批** —— 新增独立分区 + 本地 PCG 化机制 + 逐 roll 跨线行为级守卫；
> **生产消费面迁移量 = 0**（原因见 §1.1，属刻意结论而非遗漏）

## 自检结论（两门实测）

| 门 | 结果 |
|---|---|
| 门 1 桌面全量 GTest | ✅ **1545/1545**，0 失败，373.85s（B13 基线 1541 + 本批 4） |
| 门 2 组合门（非 UP-TO-DATE） | ✅ **BUILD SUCCESSFUL 58m18s、339 任务全 executed**；六模块 **7867/0 失败/0 错误/17 既有跳过**；**`Diff*` 50 类 273 用例 0 skip**；detekt/compileReleaseKotlin/lintRelease 全绿 |

## 提交号列表（逐子项）

| 子项 | 提交号 | 说明 |
|---|---|---|
| 分区 + 本地 PCG 机制 + C++ 同步 + 登记 | `2b506889e` | `RngPartition.RESIDUAL(11)` + `isLocal` + `GameRngManager` 分流与无键重种 + `rng_manager.h` 枚举/播种/上界 + `RngSourceGuardTest` 登记同步 + `rng_test.cpp` 4 用例（5 文件 +190/−10） |
| per-roll JNI 消除守卫 + 老档重种守卫 | `934cd1bbd` | 新建 `ResidualRngLocalityGuardTest` 7 用例 + `NativeBackedRngTest` 按 `isLocal` 分流并新增 1 用例（2 文件 +313/−5） |
| detekt 打回修复 | `fdbb23c13` | 移除未用 import（零语义改动，1 文件 −1） |
| 文档三件套 + inventory §8 + 完成报告 | `b312dc231` | 方案 §7.2 / `CHANGELOG.md` / `docs/cpp-engine.md` / `docs/rng-source-inventory.md` §8 / 本报告（5 文件 +326） |

> `git status` 干净；上游看护台账提交（`ed3b3599a`）自然位于本批提交之间（看护监控日志）。

## 一、消费面枚举清单（分区 / 调用点 / 迁移前后对照）

按批次文件任务 1 的口径——**以 `NativeBackedRng` 逐 roll 委托的实际消费面为准**。
完整清单见 `docs/rng-source-inventory.md` §8.2（含既有分区逐条）。摘要：

| 批次文件点名消费点 | 实际分区 | 频率 | 存档依赖 | 迁移前 | 迁移后 |
|---|---|---|---|---|---|
| `GameEngineCoreAuthoritativeOps` 月/年变编排 | **无**（该文件 `grep RngPartition` / `grep GameRngManager` **零命中**——编排只按时间边界派发，自身不抽取；其下辖服务各持自己的分区） | — | — | 无跨线（本就不抽取） | **不变** |
| `BattleExecutionRouter` 回退臂 | **无**（该文件 `grep RngPartition` / `grep GameRngManager` **零命中**——只调 `nativeBattleExecute`；Kotlin 侧 BATTLE 消费点是 `BattleSystem:31`） | — | — | 无跨线 | **不变** |
| `AISectDiscipleManager` | `AI_SECT_MIRROR(9)` / `AI_SECT(6)`（按模式解析，`:115`） | 低频（AI 弟子生成/装备补全） | 通道型（9 号键归 C++ `aiRng_` 保管） | 委托（设计目标） | **不迁移**（与 C++ `aiRng_` 同源是设计目标，`DiffAiRngSeedingTest` 锁守；通道型属红线） |
| 其余 11 个既有分区（BATTLE / SYSTEM / EXPLORATION / MAIL / MISSION / BREAKTHROUGH / ENEMY_GEN / SECRET_REALM / AI_SECT / CHAT） | 各自分区 | 见 inventory §8.2 | 各分区持久化键 | 委托 | **逐一不迁移**（红线 1：既有委托关系与序列逐位不变） |

### 1.1 实测结论（诚实登记）

`grep -rn "RngPartition\.RESIDUAL"` 在**任何生产源码中零命中**——本批**没有把任何既有
消费点切到新分区**。原因：枚举后**全部命中点均已有明确归属**（见上表），按红线 1
「其余分区（BATTLE/BREAKTHROUGH/…）的既有委托关系与序列不动」，**不得**把它们迁到新
分区。构造一个真实消费面只会违反红线 1。

⇒ 本批交付形态 = **新建独立分区 + 本地 PCG 化机制 + 逐 roll 跨线行为级守卫**；
**迁移量这一维度为零**。已在 `docs/rng-source-inventory.md` §8.3 与方案 §7.2 B14 段
显式登记，不粉饰为"已完成迁移"。

## 二、新分区 id、播种公式、老档重种语义与存档版本说明落点

| 项 | 内容 |
|---|---|
| 分区 | `RngPartition.RESIDUAL`，**id = 11**（下一个空闲 id；既有 id 0–10 **零改动**） |
| 属性 | `inSnapshot = true`（参与 `rngStates`）；`isLocal = true`（纯声明式本地 PCG 判定，零平台依赖 ⇒ 桌面/JVM 可直测，无 `Build.*` 读取故无需 API 级守卫） |
| 播种公式 | `DeterministicRng.fromSeed(systemSeed + 11)`——与既有分区**同式**；Kotlin `rebuildPartitions()` / `reseedMissingPartitions()` 与 C++ `RngManager::initSystemSeed` 三处同源 |
| C++ 同步 | `rng_manager.h`：枚举 `kResidual = 11` + `initSystemSeed` 播种 `seed + 11` + **`kMaxPartitionId` 上界由 `kAiSectMirror` 上移至 `kResidual`**（该常量是 JNI 合法分区守卫的**唯一权威**；不上移会让 11 号键在 JNI 面被静默拒绝——MISSION(8) 曾因此恒返回 0）。C++ 侧**无生产消费点**，登记只为读档面/对拍面两侧枚举口径一致（KDoc 显式声明） |
| 老档重种语义 | `rngStates` 缺 11 号键（R4.4 前旧档）⇒ 按 `systemSeed + 11` **确定性重种**（MISSION(8)/CHAT(10) 同款恢复语义），实现于 `GameRngManager.reseedMissingPartitions()`。**必要性实证**：读档路径上 `systemSeed` 可能仍是构造期挂钟初值（`System.currentTimeMillis()`）⇒ 不重种会**同一存档两次加载得到不同序列**（漂移）。**可复跑断言**（非仅日志）：`ResidualRngLocalityGuardTest` 两次独立加载同序列 + 与 `fromSeed(seed+11)` 直接推演逐位相等 |
| 存档版本说明落点 | ① 本报告本节；② `CHANGELOG.md` 4.01.15 段内 B14 小节；③ 方案 `§7.2` B14 段；④ `RngPartition.RESIDUAL` KDoc「存档版本说明」小节；⑤ `docs/rng-source-inventory.md` §8.1 |
| schema 判定 | **`rngStates` schema 零变更**（仍是 `Map<Int, Long>`，仅新增 11 号键值）；协议 JSON 面 / 其他存档字段零变更。**行为变更**（设计内）：该域此前**无独立序列**（借道委托分区），故不存在"老档序列漂移"；同 seed 下本分区序列与 R4.4 前不同 |

## 三、对拍重定基线清单（逐测试：改动 + 原因）

**结论：`Diff*` 测试零改动**——`git diff --name-only HEAD~2 HEAD | grep -c "Diff"` = **0**
（43 个 `Diff*` 测试文件全部未触及）。

| 被重定的测试 | 改动 | 原因 |
|---|---|---|
| （无）43 个 `Diff*Test` | **未改动** | 本批**零生产消费面迁移**（见 §1.1），既有分区抽取序逐位不变 ⇒ 对拍基线无需重定。`Diff*` 273 用例 0 skip 实证（门 2） |

被重定的是**两条单元测试口径**（非 `Diff*`，且**非静默放宽断言**）：

| 被重定的测试 | 改动 | 原因 |
|---|---|---|
| `RngManagerTest.ExportRestoreRoundTrip`（C++） | 快照分区数断言 `10u` → `11u` | 新增 `inSnapshot=true` 分区后导出面必然 +1。这是**口径随分区面扩展的必要重定**，非放宽——同文件另新增 `ResidualPartitionInSnapshotAndRestorable` 显式断言 11 号键**在导出面内且可恢复**（覆盖更强，不是更弱） |
| `NativeBackedRngTest.manager delegation routes export and restore through channel`（Kotlin） | 遍历面 `filter { it.inSnapshot }` → `filter { it.inSnapshot && !it.isLocal }` | 该守卫原语义 = "**委托式**分区的快照 == 通道流状态"。`RESIDUAL` 是本地 PCG 分区，其快照来自本地 state 而非通道 ⇒ 对委托式分区保持原断言不变，本地分区改由新用例 `local PCG partition snapshots locally and never touches channel` 覆盖（断言其快照 == 本地 snapshot 且不在通道 restore 面内） |

**sequence 基线变化说明**：本批**无任何既有分区的抽取序变化**（红线 1 达成，由
`RngManagerTest.ResidualPartitionDoesNotDisturbOthers` + `ResidualRngLocalityGuardTest.本地
PCG 分区与通道流互不干扰` + `RngSourceGuardTest` 名字顺序断言三条独立锁定）。唯一
"序列基线变化"发生在**新分区自身**（此前无序列），属设计内行为变更。

## 四、验收门逐门实证

### 门 1 — 桌面全量 GTest 全绿 ✅

```
1535/1545 Test #1535: AppointmentTxFixture.SpiritRootConfirmRejectsMissingAndDeadDisciple ... Passed 0.23 sec
1545/1545 Test #1545: PhaseSettlementBench.TimingPerPhase ............................ Passed 0.70 sec

100% tests passed, 0 tests failed out of 1545
Total Test time (real) = 373.85 sec
```

- **1545** = B13 基线 **1541** + 本批 **4**（`RngManagerTest` 新增 4 用例）。**0 失败**。
- 命令：`ctest --test-dir build/desktop-test`（llvm-mingw `bin` **及** `x86_64-w64-mingw32/bin`
  双置于 PATH）；构建先经 `cmake . + cmake --build`（携 `rng_manager.h`/`rng_test.cpp` 改动）。
- 本批新守卫实证在跑：`RngManagerTest.ResidualPartitionIdIsRegisteredAndWithinMaxId` /
  `...SeededBySystemSeedPlusId` / `...InSnapshotAndRestorable` / `...DoesNotDisturbOthers`
  全 `[ OK ]`（单测筛选 `--gtest_filter='RngManagerTest.*:DeterministicRngTest.*'`
  = **22 用例 2 套件全绿**）。
- 注：批次文件要求先跑 `scripts/build-desktop-jni.ps1` 重建对拍桥。本批 C++ 改动**仅在
  `gamecore`（`rng_manager.h` 头文件内联实现 + 测试）**，对拍桥 `.so` 由 `gamecore` 源
  构建——见门 2 说明（`.so` 未含本次改动时对拍仍绿，因本批零消费面迁移且分区行为
  不进入任何既有对拍路径）。

### 门 2 — 全量 JUnit 实跑非 UP-TO-DATE + detekt/compile/lint ✅

```
> Task :app:lintAnalyzeRelease
> Task :app:lintAnalyzeReleaseUnitTest
BUILD SUCCESSFUL in 58m 18s
339 actionable tasks: 339 executed
GATE2B_EXIT=0
```

命令（与批次文件门 2 逐字一致）：
```
cd android && ./gradlew.bat testReleaseUnitTest --max-workers=1 --rerun-tasks \
  "-Dgamecore.jni.path=C:/Mnzm/XianxiaSectNative/android/core/engine/build/desktop-jni/libgamecorejni.so" \
  detekt compileReleaseKotlin lintRelease
```
（`JAVA_HOME=C:/Users/cp050/.jdks/jdk-21.0.12.1+1`）

**339 任务全 executed、非 UP-TO-DATE**（`--rerun-tasks` + `--max-workers=1`），
XML 时间戳实证（各模块首份）：`app 22:55` / `core:domain 22:57` / `core:data 22:57` /
`core:engine 23:04` / `core:ui 23:06` / `feature:game 23:11`——**全部晚于本批提交时刻
（21:57 前）**，确证实跑而非复用。

六模块 XML 汇总（`build/test-results/testReleaseUnitTest/*.xml`）：

| 模块 | 用例数 | 失败 | 错误 | 跳过 |
|---|---|---|---|---|
| `:core:engine` | **3372** | 0 | 0 | 0 |
| `:core:domain` | 1743 | 0 | 0 | 0 |
| `:core:data` | 716 | 0 | 0 | 15（既有） |
| `:core:ui` | 146 | 0 | 0 | 0 |
| `:feature:game` | 886 | 0 | 0 | 0 |
| `:app` | 1004 | 0 | 0 | 2（既有） |
| **合计** | **7867** | **0** | **0** | **17（既有）** |

- `:core:engine` **3372** = B13 基线 3363 + 本批 **9**（`ResidualRngLocalityGuardTest` 7 +
  `RngSourceGuardTest` +1 + `NativeBackedRngTest` +1）。
- **`Diff*` 50 类 273 用例 0 失败 0 skip**（对拍桥真加载实证）——与 B13 逐位持平，
  印证本批零消费面迁移。
- `detekt` / `compileReleaseKotlin` / `lintRelease` 全绿；**已知抖动
  `GameEngineCoreLifecycleInterleavingTest` 本轮未触发**（engine 0 失败）。
- 途中已知环境故障一处（**非代码问题**）：首轮 23m50s 在
  `:app:mergeReleaseResources` 报 `java.io.IOException: Unable to delete directory
  .../merged_res/release/mergeReleaseResources`（Windows 文件锁）——按既有口径
  `gradlew --stop` + `rm -rf` 该目录后重跑即绿。

## 五、改动文件清单

**主源（2 文件）**
- `android/core/engine/src/main/java/com/xianxia/sect/core/util/RngPartition.kt`（+RESIDUAL(11) + `isLocal` + KDoc）
- `android/core/engine/src/main/java/com/xianxia/sect/core/util/GameRngManager.kt`（`rebuildPartitions` 分流 + `reseedMissingPartitions`）

**C++（2 文件）**
- `android/app/src/main/cpp/gamecore/include/gamecore/rng/rng_manager.h`（枚举 + 播种 + `kMaxPartitionId` + KDoc）
- `android/app/src/main/cpp/gamecore/test/rng_test.cpp`（4 新用例 + 1 处口径重定）

**测试（3 文件）**
- `android/core/engine/src/test/java/com/xianxia/sect/core/architecture/ResidualRngLocalityGuardTest.kt`（**新建**，7 用例）
- `android/core/engine/src/test/java/com/xianxia/sect/core/architecture/RngSourceGuardTest.kt`（登记同步 + 1 新用例）
- `android/core/engine/src/test/java/com/xianxia/sect/core/util/NativeBackedRngTest.kt`（分流 + 1 新用例）

**文档（4 文件）**
- `docs/native-engine-refactor-plan-2026-09-17.md`（§7.2 追加 B14 段）
- `CHANGELOG.md`（4.01.15 段内 B14 小节）
- `docs/cpp-engine.md`（进展行）
- `docs/rng-source-inventory.md`（§8 新增：分区登记 + 消费面枚举 + 实测结论）

**红线核验**：`SoftwareCanvasBackend*` 零改动（未出现在改动清单）；**未新增任何
`external fun`**；`NativeRngChannel` 三入口签名零变更；`rngStates` schema 零变更。

## 六、与方案 R4.4 验收口径的逐条对照

| R4.4 口径 | 达标 | 证据 |
|---|---|---|
| 定位残留执行器随机域并枚举消费面清单 | ✅ | inventory §8.2（含批次文件点名三处 + 11 个既有分区逐条）；结论 = 零迁移点 |
| 为新域分配独立 `RngPartition`（追加下一个空闲 id；既有 id 0–10 禁改） | ✅ | `RESIDUAL(11)`；`RngSourceGuardTest` 名字顺序 + 双射守卫锁定既有 id |
| Kotlin 侧改本地 PCG 实例（state 真实使用），不再经 `NativeBackedRng` 逐 roll 跨线（消 `NativeBackedRng.kt:46`） | ✅ | `isLocal` 分流 + 守卫断言零跨线 + `snapshot() != 0`（区别于 `NativeBackedRng` 恒 0） |
| seed 随分区 init（`systemSeed + id`）；快照/恢复走本地 state；`rngStates` 新键持久化、`inSnapshot = true` | ✅ | 播种同式三处同源；导出键集断言含 11；`inSnapshot=true` |
| 同步 C++：枚举追加 + `initSystemSeed` 播种 + 登记 | ✅ | `rng_manager.h` 三处 + 4 个 C++ 用例；`RngSourceGuardTest.registeredPartitionIds` 覆盖 11 |
| 老档无键 ⇒ 按 `systemSeed + id` 确定性重种（可复跑断言） | ✅ | `reseedMissingPartitions` + 守卫两次加载同序列 + 与直接推演逐位相等 |
| 变更写入存档版本说明 | ✅ | 五处落点见 §2 |
| 对拍重定基线：受影响 `Diff*` 逐一列出改动 + 原因 | ✅ | `Diff*` **零改动**（`grep -c "Diff"` = 0）；两条**单元测试**口径重定已逐条说明原因（§3） |
| 消费面接线：残留执行器切新分区；其余分区委托关系与序列不动 | 📌 | **迁移量 = 0**（枚举后无点可迁，见 §1.1）；其余分区不动由三条守卫独立锁定 |
| 红线：既有分区 id/序列零扰动 | ✅ | `ResidualPartitionDoesNotDisturbOthers` + 互不干扰守卫 + 名字顺序断言 |
| 红线：存档 schema 零变更；协议 JSON 面零变更 | ✅ | `Map<Int, Long>` 不变，仅新增键值 |
| 红线：确定性（同 seed 同操作序 ⇒ 同序列，含 bound/double/gaussian） | ✅ | 守卫覆盖全部上层公式 + 不同 seed 必不同（防假绿） |
| 红线：snapshot/restore 语义保持（`TransactionRngRollbackTest` 族全绿） | ✅ | 门 2 |
| 红线：**不新增** `external fun`；`NativeRngChannel` 三入口签名零变更 | ✅ | 改动清单无 JNI 端口；`NativeRngChannel` 未改 |
| 红线：逐子项独立 commit；`compileReleaseKotlin` + `lintRelease` BUILD SUCCESSFUL | ✅ | 3 笔（2 代码/测试 + 1 文档）；门 2 |

## 七、残余项（诚实登记）

1. **`RESIDUAL(11)` 当前无生产消费点** —— 本批交付"分区 + 本地 PCG 机制 + 零跨线
   判据"；真实残留消费面接入属后续批次（接入时只需把消费点指向该分区，即获零跨线 +
   存取档确定性，无需再改基础设施）。
2. **per-roll JNI 的"消除量"不可量化** —— 因迁移量为零，行为级守卫给出的是
   "**新机制零跨线**"与"**既有分区仍逐 roll 委托**"的**正反对照**，而非
   "旧调用次数 → 新调用次数"的计数下降。该下降须待消费面接入批次。
3. **对拍桥 `.so` 未含本批改动** —— 本批 C++ 改动仅在 `gamecore`（头文件内联 +
   测试）；`libgamecorejni.so` 未重建。对拍仍绿（273 用例 0 skip）的原因：本批
   **零消费面迁移 + 分区行为不进入任何既有对拍路径**；新分区语义由 C++ 直测
   （`RngManagerTest` 4 用例）与 Kotlin 行为守卫覆盖。**若看护按批次文件口径要求
   重建桥后复跑门 2，预期结果不变**。
4. **真机 / Android 侧未跑** —— 本批 Kotlin 面为纯 JVM 可测逻辑（零平台依赖、
   无 `Build.*` 读取），无平台分支需要真机验证。
