# 交接文档：W5 收口批（2026-09-20）——已完成变更清单与剩余工作

> 交接对象：接手实施者（人或子会话）。
> 来源：2026-09-20 最终审查（完成度核对 + b02-findings 处置）后的收口施工，
> 施工进行到一半按用户指示停止——**全部代码变更已在工作区（未提交）**，
> 其中 C++ 侧已构建验证，Kotlin 侧未经验证（编译/测试均未跑）。
> 本文给出：变更分组与根治逻辑 → 验证 SOP 与预期结果 → 未开始的剩余工作。
> 关联文档：`docs/native-engine-refactor-plan-2026-09-17.md`（方案+§7 状态）、
> `docs/parallel-batches-w5/b02-findings.md`（发现 3-11）、`b03/b05-findings.md`。

---

## 0. 工作区状态总览（git status 实测 2026-09-20）

17 个修改 + 4 个新增，全部未提交。按工作项分组（建议每组独立 commit，仓库纪律）：

| 组 | 工作项 | 文件 | 状态 |
|---|---|---|---|
| A | **UAF 段错误根治**（桌面单进程直跑崩溃） | 新增 `cpp/gamecore/include/gamecore/data/index_snapshot.h`；改 `data/trait_db.h`（3 处）、`data/recipe_db.h`（2 处） | ✅ 全验证（ctest 1556/1556、单进程 exit=0 1553/1553、NDK arm64 构建成功） |
| B | **B05 巡逻灵石丢失修复**（玩家可见缺陷） | `exploration/PatrolBattleSystem.kt`；测试 `ExplorationPatrolRouteTest.kt` | ✅ 已验证（`ExplorationPatrolRouteTest` 全类绿） |
| C | **b02 发现 11：@Transient 重置根治** | 新增 `gameview/GameDataTransientFace.kt`；改 `GameDataFieldPatch.kt`、`StateSyncService.kt`、测试 `GameDataFieldPatchGuardTest.kt`、`SaveLoadViewModelCloudLoadOps.kt`（仅注释） | ✅ 已验证（修复原反射失效后转绿；三臂对拍 `DiffMirrorArmConvergenceTest` 收敛全绿） |
| D | **B17-min：JNI 计数门禁**（发现 6 根治） | 新增 `scripts/check-jni-count.mjs`、`scripts/jni-count.baseline.json`；改 `.github/workflows/ci.yml` | ✅ 已验证（脚本自检 91/91 双桥无扩散；ci.yml 人工核对通过） |
| E | **发现 5：死管道清理** | `app/build.gradle`（删任务+preBuild 接线）；`FootprintTableSyncTest.kt`（用例 1 退役）；`BuildingSpriteFootprintJsonGuardTest.kt`（文案） | ✅ 已验证（2 测试类绿；preBuild 跑通且 footprint_table.h 不再生成） |
| F | **发现 7/9/10**（proto 纪律/预热/夹具卡点） | `game_view.proto`、`GameViewMirrorCodec.kt`（注释）、`GameEngineCoreAuthoritativeOps.kt`（预热+导入）；测试 `MirrorProtoFeedEquivalenceTest.kt`、`MirrorProtoFeedFixture.kt`、`MirrorSegmentProjectionBenchTest.kt`、`GameDataFieldPatchGuardTest.kt` | ✅ 已验证（proto 再生成通过；Equivalence/Bench 测试绿） |


---

## 1. 各工作项的根治逻辑（为什么这样改）

### A. UAF 段错误根治（已验证 ✅）

**缺陷**：桌面全量 GTest **单进程直跑**稳定段错误（exit=139）；ctest 分进程 1556 全绿掩盖了它。根因（二分定位，最小复现一条命令见 §3.4）：`trait_db.h` / `recipe_db.h` 的 5 个按 id 查询（talentById/physiqueById/affixById/forgeRecipeById/pillRecipeById）用函数级 `static const std::map<std::string, const T*> kIndex` 缓存指向数据向量元素的裸指针；数据注入/测试复位**整体替换向量**后索引悬挂，解引用即 UAF（显形为段错误/bad_alloc/垃圾断言三种症状）。生产路径暂未触达（注入仅一次、早于首次查询），但违反 `data_store.h:108-110` 自声明的指针稳定性契约。

**根治**：新增 `data/index_snapshot.h` 的 `IdIndexSnapshot`（RCU 发布模式）——快照记录向量 `data()+size()`，每次查询比对、失效即重建；读路径无锁（原子指针 acquire load），重建持互斥双检，旧快照由静态持有列表保管不释放（替换在生产上至多一次，KB 级）。5 个查询函数换用该快照。**不用 `std::atomic<std::shared_ptr>`**：llvm-mingw libc++ 无该特化（编译实证）。

**验证证据（已跑）**：重建后单进程 `game-core-tests.exe` **1553/1553 全绿、exit=0、24s**（此前同命令必崩）；bench 二进制另 3 例，合计 1556 口径不变。

### B. B05 巡逻灵石丢失（b05-findings 的"修复方向"落地）

**缺陷**（b05-findings 已确证，玩家可见）：`PatrolBattleSystem.executePatrolRound` 入口快照 `gd` 作为 `applyResults` 链头，终局 `state.gameData = finalGd` 覆盖三笔中途直写——①灵石奖励不入账（弹窗显示与实际不符）②冲突 AI 直攻目标不移除 ③AI 阵亡被"复活"。

**根治（单写者，非补丁）**：
- `applyResults` 链头从入口快照改为 **`state.gameData` 当前值**（冲突段两笔直写经种子保留）；
- **灵石入账移进 `applyVictoryGdChanges` 的 updatedGd 链**（从 `result.result.rewards["spiritStones"]` 折叠）；
- `applySpiritStoneReward` **删除对 state.gameData 的直写**，只产出弹窗奖励卡——applyResults 全程对 gameData 单写者，终局覆盖不再吞并任何写入。
- 测试恢复三条意图断言：灵石入账（基线 1000+奖励 100=**1100**）、冲突目标表清空、AI 阵亡 isAlive=false。

### C. b02 发现 11：@Transient 重置根治

**缺陷**：镜像每旬把 5 个 @Transient 运行态字段（slotId/autoSaveIntervalMonths/aiBeastEncounterTargets/battleTeam/aiBattleTeams）打回声明默认值（旧全量 JSON 往返的副作用，B08 按红线复刻为 `LEGACY_RESET_ON_MIRROR`）。

**根治**：删 `LEGACY_RESET_ON_MIRROR`；新增 `GameDataTransientFace`（**以 kotlinx 序列化 descriptor 差集枚举 @Transient 字段**——`serializer().descriptor.elementNames` 与「非 static 非合成实例字段」求差，新增字段自动纳管，防复发是结构性的）提供 `carryOver(before, after)`；镜像**三条臂统一**在落值前以事务前值承载该面：
- `GameDataFieldPatch.apply`：浅拷贝天然保留（删复刻即得）；
- `StateSyncService.applySnapshot`（F3 全量快照臂）：全量替换分支删 4 字段手抄回填、改 carryOver（顶层携带字段在其后覆盖——携带优先语义不变）；
- `StateSyncService.mergeGameDataChanges` 回滚臂（旧 JSON 往返）：删 4 字段手抄回填、改 carryOver。
- 守卫：`GameDataFieldPatchGuardTest` 复刻断言改"保留"断言 + 新增**反射枚举防复发守卫**（两臂各跑、逐字段比对 before/after）。
- `reconcileCloudSlot` **不动**：其"恒 0"前提来自云档 JSON 序列化天然不含 slotId（与镜像重置是两个来源），修复不影响其必要性——仅注释精确化。
- **判归决策（已定）**：`battleTeam`/`aiBattleTeams` 死字段只做值保留，**Room 列清理留 B18**（删列需 schema migration，须存档回归单独走批）；`autoSaveIntervalMonths` 列已 @Ignore 无影响。

> **⚠️ 接手修正记录（2026-09-20 验证执行时发现并已修复）**：原实现用
> `declaredFields.filter { it.isAnnotationPresent(kotlinx...Transient) }` 枚举，
> **实测枚举为 0 个字段**（`carryOver` 静默变 no-op ⇒ legacy 臂 slotId 打回 0，
> `GameDataFieldPatchGuardTest` 3 用例红）。根因：GameData 字段是主构造器 `var`
> 参数，`@Transient` 的 `@Target` 含 `PROPERTY`/`VALUE_PARAMETER` **不含 `FIELD`**，
> Kotlin 不把注解写到 JVM 字段上（declaredFields=150、带注解者 0）；构造器参数注解
> 亦空且 `isParamNamePresent=false`（无 `-parameters`）⇒ 注解反射两条路均不通。
> 已改 **descriptor 差集法**（精确得 9 字段、双向无残余、不引 kotlin-reflect 生产依赖）。
> **本面共 9 个字段**（非 5 个）：slotId / autoSaveIntervalMonths /
> aiBeastEncounterTargets / aiSectDisciples / aiSectBeastSkipCooldowns /
> lockedBeastIds / aiSectBeastDirectTargets / battleTeam / aiBattleTeams。


### D. B17-min：JNI 计数门禁（发现 6 根治）

`scripts/check-jni-count.mjs` 三条规则：①双桥 `external fun` 计数不增（基线 `jni-count.baseline.json`：42+49=91）②**面不扩散**——生产 src/main 只允许两桥文件含 external fun（防"计数不变面变大"绕行）③收缩提示。豁免=同 PR 显式改基线+附理由（`--update` 重生成），**无跳过通道**。CI 新增 `jni-count-gate` job。本地自检已过（91/91 双桥无扩散）。**平台纯度 gate 不建**（决策依据：ci.yml:75 既有论证——Linux 直接编译即构造性证明，grep 三条失效；建议后续把该论证升格为 ADR 一句话即可）。

### E. 发现 5：generateFootprintHeader 死管道清理

按 b02-findings 清理清单逐项：删 Gradle 任务（原 build.gradle:512-576）+ preBuild 接线条目；`FootprintTableSyncTest` 用例 1 退役（守卫职责已由 `SceneUvTablesMirrorGuardTest` 接替，KDoc 已注明），用例 2/3 保留；`BuildingSpriteFootprintJsonGuardTest:86` 文案改指 scene_uv_tables.h。基线联动：**:app JUnit 1004 → 1003**。

### F. 发现 7/9/10

- **7（proto3 present 纪律）**：`game_view.proto` 头部纪律补条（"集合 present 不承载业务语义、回落域默认"）；codec KDoc 同步；`MirrorProtoFeedEquivalenceTest` 新增守卫——空信封零变更可解 + 缺省消息序列化为零字节（wire 不可区分性的直接证明）。
- **9（protobuf 首封 ~194ms）**：`ensureAuthoritativeNative` 启动期解一次全缺省信封预热（runCatching 包裹、零写入）；`MirrorSegmentProjectionBenchTest.measure` 首轮改不计时预热遍（`repeat(REPEATS + 1)`、`iteration > 0` 才计时）——bench 只对稳态口径负责。
- **10（夹具字段名假绿）**：`MirrorProtoFeedFixture.mirrorGameDataField(name)` 卡点（不在 `GameDataFieldPatch.coveredFields` 即 require 红）；等价测试的 5 处字段名全部过卡点；`GameDataFieldPatchGuardTest.change()` 同款卡点（注意：未知键用例的 `futureFieldFromNewerNative` 是直接 `to JsonPrimitive` 构造、不经 change()，不受影响——有意保留）。

---

## 2. 验证状态矩阵（接手后第一件事：跑完未验证项）

> **2026-09-20 接手执行结果（本节原为"待验证"清单，现已全部跑完 → 全绿）**

| 工作项 | 已验证 | 待验证 |
|---|---|---|
| A UAF | ✅ 构建+单进程 1553/1553+exit=0；ctest 全量 ✅ 1556/1556；**NDK arm64 构建** ✅（`:app:externalNativeBuildRelease` SUCCESSFUL） | — |
| B B05 | ✅ `ExplorationPatrolRouteTest` 全类绿；**detekt 曾打回 1 处 `UnusedParameter`（B05 重构残留：`applyVictoryRewards` 的 `state` 成空参数）已修**（删参数+调用点同步）并复验绿 | 行为变更 → CHANGELOG 已记玩家可见口径 |
| C 发现 11 | ✅ `GameDataFieldPatchGuardTest` 全类绿（**先修复了发现的反射失效缺陷**）；`MirrorProtoFeedEquivalenceTest`/`MirrorSegmentProjectionBenchTest` 绿；**桌面 `DiffMirrorArmConvergenceTest` 三臂收敛全绿** | 存档读写回归（建议 5 门禁） |
| D B17-min | ✅ 脚本本地自检 91/91；ci.yml 结构人工核对通过（无 pyyaml，逐行核） | CI 实跑（需推远端） |
| E 发现 5 | ✅ `FootprintTableSyncTest`（退役后 **2 用例**）+ `BuildingSpriteFootprintJsonGuardTest` 绿；preBuild 跑通且 `footprint_table.h` 不再生成 | — |
| F 发现 7/9/10 | ✅ proto 再生成通过；`MirrorProtoFeedEquivalenceTest`（+1 新用例）绿；`MirrorSegmentProjectionBenchTest` 绿；`GameDataFieldPatchGuardTest` 绿 | — |
| **③ 全量组合门** | ✅ **BUILD SUCCESSFUL**（第 2 跑 55m07s / 230 executed / 非 UP-TO-DATE）——六模块 **7876 tests / 0 fail / 17 既有 skip**；**`Diff*` 50 类 273 用例 0 skip**；`SoftwareCanvasBackend*` 10 类 106 用例 0 fail；detekt 修复后 engine 模块复跑 **3374/0/0** + detekt + compileReleaseKotlin 全绿（16m30s / 57 executed） | — |

> **第 1 跑环境故障（非代码红）**：`:app:mergeReleaseResources` 在 30m01s 时撞 Windows 文件锁
> （`Unable to delete directory ... merged_res/...`，`99 executed`、**测试面零证据**）⇒ 按既有 SOP
> `gradlew --stop` + 删目录 + 清 journal lock 后整门重跑（即上表第 2 跑）。

**测试计数预期**（对账基线用）：GTest 1556 不变（单进程 1553+bench 3）；`:core:engine` 3372 → **3374**（GuardTest +1、EquivalenceTest +1）；`:app` 1004 → **1003**。

> **⚠️ 接手实测校正（2026-09-20 全量组合门）**：`:core:engine` **3374** ✅（与预期一致）。
> `:app` 实测 **1010**（含 2 skip）——与本文档"1004→1003"的**绝对基线不同口径**（差额
> `−1` 一致：`FootprintTableSyncTest` 用例 1 退役后为 **2 用例**、已实证生效）。以
> **实测 1010** 为准记基线，勿按 1003 对账。六模块实测 TOTAL = **7876 tests / 0 fail / 17 既有 skip**。
> 注意 E 组 `BuildingSpriteFootprintJsonGuardTest` **不在 `:app` 模块**（在 `:core:ui` 一带），
> 交接文档把两者并列为 `:app` 门项宜按模块分开核。


## 3. 验证 SOP（沿用台账既有命令，Windows Git Bash）

```bash
# ⓪ 环境前置（2026-09-20 接手实测）
export JAVA_HOME="C:/Users/cp050/.jdks/jdk-21.0.12.1+1"   # 勿用 Android Studio 的 jbr：该目录不完整（无 lib/jvm.cfg）
export PATH="$JAVA_HOME/bin:$PATH"

# ① 桌面 GTest（~75s ctest / ~24s 单进程；C++ 已重建过，接手后应 no work to do + 全绿）
export PATH="/c/Users/cp050/AppData/Local/Android/Sdk/cmake/3.22.1/bin:/c/Users/cp050/llvm-mingw/llvm-mingw-20260616-ucrt-x86_64/bin:$PATH"
cd android/app/src/main/cpp/gamecore/build/desktop-test
ctest -j 8
# ①' 单进程复跑（UAF 修复的判定实验，必须 exit=0）
# ⚠️ cwd 必须在 gamecore 源码根：data_store_test.cpp 的 game-data.json 候选路径是
#    **相对 cwd** 的，ctest 用 WORKING_DIRECTORY 钉为 gamecore/。在 build/desktop-test
#    下直跑会 6 个 DataStoreGuardTest fail（"game-data.json 不存在"）——非代码缺陷！
cd /c/Mnzm/XianxiaSectNative/android/app/src/main/cpp/gamecore
./build/desktop-test/test/game-core-tests.exe > /tmp/g.txt 2>&1; echo $?; grep -c "OK ]" /tmp/g.txt   # 期望 exit=0、1553

# ② 目标 Kotlin 单类（先单类后全量）
cd /c/Mnzm/XianxiaSectNative/android
./gradlew :core:engine:testReleaseUnitTest --tests 'com.xianxia.sect.core.engine.domain.exploration.ExplorationPatrolRouteTest'
./gradlew :core:engine:testReleaseUnitTest --tests 'com.xianxia.sect.core.gameview.GameDataFieldPatchGuardTest'
./gradlew :core:engine:testReleaseUnitTest --tests 'com.xianxia.sect.core.nativebridge.MirrorProtoFeedEquivalenceTest'
./gradlew :app:testReleaseUnitTest --tests 'com.xianxia.sect.FootprintTableSyncTest'

# ③ 组合门（~20-60min；桥须先建）——对拍含 DiffMirrorArmConvergenceTest
# 桥构建（本机 Bash 无法调 pwsh——安全策略拦截；用等价 clang++ 手工命令，见 §1C 备注或日志）
./gradlew testReleaseUnitTest --max-workers=1 --rerun-tasks "-Dgamecore.jni.path=<so 绝对路径>" detekt compileReleaseKotlin lintRelease
# ⚠️ -Dgamecore.jni.path 必须写 **Windows 原生路径**（C:\...\libgamecorejni.so）；
#    Git Bash 风格的 /c/... 会被 Gradle 解析成 C:\c\... ⇒ UnsatisfiedLinkError。

# ④ JNI 门禁本地自检
node scripts/check-jni-count.mjs
```

环境注意（台账既有 + 2026-09-20 新增）：Windows 文件锁（mergeReleaseResources 失败 → `gradlew --stop` + 删该目录重跑）；已知抖动 `GameEngineAcycCoreLifecycleInterleavingTest` 单类重跑绿即放行；**A 组的 5 个查询函数中文测试名在 GBK 控制台不可用 gtest_filter 匹配**（argv 编码问题），过滤请用 ASCII 通配；**NDK arm64 构建**：`./gradlew :app:externalNativeBuildRelease`（NDK r27，arm64-v8a）。


---

## 4. 未开始的剩余工作（按优先级）

| # | 事项 | 说明 | 量级 |
|---|---|---|---|
| 1 | **跑完 §2 全部待验证项 + 分组提交** | 按 §0 六组独立 commit；B05/C 是行为变更，CHANGELOG 记玩家可见修复（B05：巡逻灵石真实入账；C：镜像不再清空运行态字段——slotId 相关邮件/槽位行为修正） | 半天 |
| 2 | **文档三件套登记** | 方案 `native-engine-refactor-plan-2026-09-17.md` 增 §7.3「收口批」：登记 A-F 六项 + 测试计数 + CHANGELOG 4.01.x 段；`docs/cpp-engine.md` 若涉及 C++ 面（index_snapshot.h）补一句 | 1h |
| 3 | **G2 拍板并文档化** | 推荐选项 A：分档重定基线（如 D≤1000 <10ms、D=5000 <150ms），WS-1 以"已收口"关闭，残余成本中心①②（upsertMirrorRow 列级收窄、assembleAll）登入 B18；备选 B：坚持 <10ms@5000 则立"镜像收官批"。**数据已备**（B08/B09：D=5000 投影臂 132.68ms、列级臂 decode 1.37ms、mirror 合计 139.30ms） | 0.5h（纯文档决策） |
| 4 | **B18 批次文件定义**（`docs/parallel-batches-w5/batch-B18.md`） | 回滚臂删除批。**到期判据（须显式写入）**：一个完整发版周期经过 + 零回滚事件（RenderMetrics / 崩溃快照可查证）——五条臂 09-18/19 建立，**未经历发版前不得执行**（本会话已核）。吸收清单：drawAllTiles 旧臂 + sceneStoreRender/gameViewProjection/mirror JSON 回退分支退役；发现 8③④（upsertsJson 族 typed 化，proto 只增字段）；G5（BattleSystem→golden 夹具退场，4 个生产回退点）；battleTeam/aiBattleTeams Room 死列清理（schema migration + 存档回归）；b03 遗留（month_settlement.h 9 处 indexById 现场重建，含 :1361/:1842 循环内重建）；build-atlas.mjs/scene_uv_tables.h 历史注释措辞收口；MirrorSegmentProjectionBench 残余①② | 1h（写文件） |
| 5 | **aiBeastEncounterTargets 死臂核查**（发现 11 附带） | `GameEngineExplorationNativeOps.kt:178` 的 `containsKey` 门判在"主源无插入者"前提下恒 false——确认该遭遇战分支死活；死则登清理项（**勿顺手删**，走 B18 口径）。修复后该表保留现值，一旦有人接上插入者即恢复工作 | 1h |
| 6 | **arm64 CI 激活**（运维） | `arm64-fp-determinism.yml` 配 `FIREBASE_SERVICE_ACCOUNT` secret（需仓库管理员）；上线检查单加一条：发现 3 附注的 stol 超界样本入对拍集（G6 最后一块闭环） | 依赖权限 |
| 7 | **B17 完整版（可选）** | B17-min 已覆盖 JNI 计数门禁；原 B17 其余项（bench 门禁已入 ci.yml、平台纯度维持构造性保证）无剩余必要交付——建议在 §7.3 登记中把 B17 标记为"由 B17-min + 既有 ci 覆盖，整批关闭" | 0.5h |
| 8 | 后续产品批（非收口，勿混入） | R3.5 白名单真机验证后启用；R3.8 浮字消费面接入（战斗演出时）；R4.4 RESIDUAL 分区消费面接入 | 另立项 |

## 5. 红线提醒（接手必读）

1. **B05 与发现 11 是行为变更**（前者玩家可见）——CHANGELOG 不得写"零变更"；发现 11 触碰镜像写结果，若 `Diff*` 对拍有红，先查夹具 @Transient 初值再怀疑实现。
2. C 组的 `GameDataTransientFace` 用 Java 反射枚举 @Transient——**新增 @Transient 字段自动纳管**（防复发守卫同源），不要再手抄字段清单。
3. A 组旧快照**故意不释放**（RCU 发布模式，文件头有内存模型论证）——不要"顺手优化"成释放，那会重新引入竞态。
4. 分组提交时：C 组 5 个文件（含新文件）必须同一 commit；B 组生产+测试同 commit；勿混装（对拍归因红线）。
5. `game_view.proto` 只改了注释——schema 零变更，不需要桌面对拍桥重建之外的任何协议动作。
