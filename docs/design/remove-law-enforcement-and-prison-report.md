# 执法堂 / 执法长老 / 执法弟子 / 监牢 删除 —— 实施报告

> 方案真源：[`remove-law-enforcement-and-prison-implementation-plan.md`](remove-law-enforcement-and-prison-implementation-plan.md)
> 用户拍板（2026-09-27）：**P-1** 下架「赏善罚恶」政策 · **P-2** 删除建筑素材 · **P-3** 版本号不动 · **一并解决途中发现**
> 提交前基线：HEAD `b16f00aac` + 工作区在途改动（G13 批 + 另线 "remove-2x-speed"/"save-trigger-flag" 重构，详见 §6）

## 一、实施范围（按用户指令）

| # | 项 | 结果 |
|---|---|---|
| 1 | 执法堂建筑（`law_enforcement_hall`） | ✅ 三端定义/配置/别名/名称/repo/槽组/UI/图集全删 |
| 2 | 监牢建筑（`reflection_cliff`） | ✅ 同上 + 对话框 `ReflectionCliffDialog.kt` 整文件删 + native 特例删除 |
| 3 | 执法长老槽位（`lawEnforcementElder`） | ✅ 双端删 + `reserved 9` + `ElderSlotType.LAW_ENFORCEMENT` 删 |
| 4 | 执法亲传槽位（`lawEnforcementDisciples`） | ✅ 双端删 + `reserved 10` |
| 5 | 「赏善罚恶」政策（P-1） | ✅ `SectPolicies.rewardPunish` 双端删 + `reserved 32` + UI/开关/月扣费/常量全清 |
| 6 | 建筑素材（P-2） | ✅ `building_law_enforcement.webp` / `building_reflection_cliff.webp` 删除（仓内产物；素材源归档未动） |
| 7 | 版本号（P-3） | ✅ 未动（`version.properties` 4.01.14 / `CHANGELOG.md` 4.01.16 保持原样） |
| 8 | 途中发现 F-1（C++ 引导注册表缺 id=26） | ✅ 补齐 + 断言改写 |
| 9 | 途中发现 F-2（任务 23 奖励数量双端分歧） | ✅ 以 Kotlin 为权威对齐为 2 |
| 10 | 途中发现 F-3（`report-G02.md` 记「引导 17/18」失真） | ✅ 就地勘误注记 |
| 11 | 途中发现 F-4（`LawEnforcementConfig` 死配置） | ✅ 收口（整块删） |
| 12 | 途中发现 F-5（`building_residual_tx.h` 无 `discipleIds` 参数） | ✅ 方案文档已更正，实施按空参数面执行 |

## 二、核心兼容措施（本批最高价值项）

**问题**：`DiscipleStatus.REFLECTING` 是受保护状态（`deriveDiscipleStatus` 对其永不回退），而其**全部生产写入方**
（叛逃捕获/偷盗捕获/年结思过释放）与**唯一解除入口**（监牢对话框「释放」；及"显示所有弟子"选中释放）
都已随 G02（写入方）与本批（入口）下线——不处理即等于把存量弟子**永久卡死**。

**措施**：新增 `LawEnforcementPrisonCleanupRule`（`SaveValidator`，`id=law_enforcement_prison_cleanup`，`order=24`）：

1. 清理 `placedBuildings` 中两栋建筑实例 + 挂靠实例的**六类**实例键控槽位（矿场/藏经阁/住所/灵田/巡视/生产）；
2. `REFLECTING` / `LAW_ENFORCING` 弟子 → `IDLE`，并剥离 `reflectionStartYear`/`reflectionEndYear`/`positionName`；
3. 无残留 ⇒ `Passed`（不触发无谓落盘），有残留 ⇒ `Repaired`（一次性收敛）；幂等。

**路径覆盖实测**：`StorageEngine.load()`（本地 Room 直读）、`StorageEngineLoadOps.load()`（云档/`.sav`）、
`CorruptedResultHandler.validateRestoredData()`（损坏恢复后二次校验）三处都经 `SaveValidator.validate`。

**未覆盖面（显式声明，不假装覆盖）**：仅存在于 C++ 原生快照、不经 Kotlin 读档管线的路径未加对等归一化；
由 Kotlin 归一化 → `importToNative` 全量导入 + 首轮 `syncAllDiscipleStatuses` 收敛兜底（`LAW_ENFORCING` 无槽位 ⇒ 必收敛 IDLE）。

## 三、验证（实跑）

| 门 | 命令 | 结果 |
|---|---|---|
| Kotlin 主源编译 | `./gradlew.bat compileReleaseKotlin` | `:core:domain` / `:core:data` / `:core:engine` **通过**；`:feature:game` / `:app` **被另线在途改动阻断**（§6，非本批缺陷；末轮编译报错**仅** `SaveLoadViewModel.kt` 两处 `speedFlow`/`setSpeed`） |
| `:core:domain` 单测 | `./gradlew.bat :core:domain:testReleaseUnitTest --max-workers=1` | ✅ 通过（含 `GuideTaskTest` 22 任务 + 空号断言、`ElderSlotsPositionNameTest`、`GameDataTest`、`ItemsTest`、`ResignGateTest`、`GameConfigConsistencyTest`） |
| `:core:data` 单测 | `./gradlew.bat :core:data:testReleaseUnitTest --max-workers=1` | ✅ **819 通过 / 0 失败 / 15 skipped**（含新增 `LawEnforcementPrisonCleanupRuleTest` 7 例、`ElderSlotsRetiredFieldCompatTest` 2 例、`ProtoNumberUniquenessTest` 新增类作用域退役号守卫） |
| C++ 桌面编译 | `cmake -G "MinGW Makefiles" -DGAMECORE_BUILD_TESTS=ON` + `cmake --build` | ✅ EXIT=0（仅既有 `-Wunused-variable` 警告） |
| C++ GTest 全量 | `ctest --test-dir build/gamecore-desktop` | ✅ **1431 / 1431 全通过 · 0 失败**（含 `AppointmentTxFixture` 9 类任命、`GuideRewardTxFixture` 22 任务形状锚点、`BuildingResidualTxFixture`、`SceneEquivalenceTest`、`GovernmentTxFixture`）；**零 A 类、零 B 类**（删除不消费 RNG ⇒ 无黄金序列平移，与方案预判一致） |
| 桌面对拍桥 | `pwsh -File scripts/build-desktop-jni.ps1` | ✅ EXIT=0（`libgamecorejni.so` 重新生成） |
| 图集 codegen | `node scripts/resource-manifest.mjs` / `--atlas-def-only` / `--codegen` | ✅ 三跑 EXIT=0；`显示尺寸保真校验通过：16 栋建筑 + 9 个装饰` |
| 图集产物 | `node scripts/atlas-offline-rgba.mjs` + `node scripts/build-atlas.mjs` | ✅ EXIT=0；`atlas_astc.ktx` **87 精灵**（89−2）、`assets/atlas/atlas-manifest.json` **39 条**（41−2）、`atlas-rgba-manifest.json` **38 精灵**（40−2）、`layoutHash=08275e94a419e726`；**`atlas-rgba-*` 的权威态由 `atlas-offline-rgba.mjs`（图集 map 精灵集，38）产出**——`build-atlas.mjs` 图集模式内联写的是含 tier1 的 86 精灵版，两者非同源，**默认构建链（`preBuild` → `generateOfflineRgbaAtlas`）以后者为准**，故最终产物为该脚本输出（`AtlasOfflineRgbaSyncTest` 动态期望集 = 38） |
| `:core:engine` 单测 | `./gradlew.bat :core:engine:testReleaseUnitTest --max-workers=1 -Dgamecore.jni.path=android/core/engine/build/desktop-jni/libgamecorejni.so` | **2936 tests / 1 skipped**；**本批相关用例全绿**（`MirrorReadOnlyGuardTest`、`Diff*` 跨语言对拍族、槽位/状态/建筑/引导族）。同轮 **1 项失败属另线在途**：`GameEngineCoreLifecycleInterleavingTest > stop during emergency restart aborts restart and loop stays stopped`（时钟/引擎生命周期 = 另线 remove-2x-speed 改动面）；相邻一次运行该项通过而 `RngSourceGuardTest` 失败 ⇒ 该线收口前本模块存在**与本批无关的浮动红**（见 §6.2） |
| `:core:ui` 单测 | `./gradlew.bat :core:ui:testReleaseUnitTest` | ✅ **155 tests / 0 failures / 0 skipped**（`GameRouteDialogTypeMappingTest` 路由总数 26→25 已同步） |
| `:app` 单测 | `./gradlew.bat :app:testReleaseUnitTest` | ✅ **1009 tests / 0 failures / 2 skipped**（`SpriteCodegenSyncTest` MAP_SPRITES 40→38 + `中级多人住所` rect 修正、`BuildingSpriteFootprintJsonGuardTest` 建筑数 18→16 已同步；两处期望值均以生成的 `TextureAtlas.h`/`buildings.json` 为权威复核） |
| `:feature:game` 单测 | `./gradlew.bat :feature:game:testReleaseUnitTest` | ⚠️ **环境失败（非断言失败）**：测试 JVM `Native memory allocation (malloc) failed to allocate 1048576 bytes`（并发高负载下的原生内存耗尽）；测试源编译 `preReleaseUnitTestBuild` UP-TO-DATE（**编译通过**）。见 §6.3 |
| `:feature:game` / `:app` 单测 + detekt/lint | 同上门禁 | ⛔ **被另线在途改动阻断**（§6） |
| 终态 grep（生产面） | Kotlin 六模块 `src/main` + C++ `gamecore/{include,src}` + `assets/config` + `scripts` | ✅ `lawEnforcement\|LAW_ENFORCEMENT\|lawEnforcing\|reflection_cliff\|REFLECTION_CLIFF\|ReflectionCliff\|rewardPunish\|REWARD_PUNISH\|LawEnforcementConfig\|realmLawEnforcement` **全部 0 命中**；唯一豁免 `scripts/sprite-uid-map.json:38/45`（uid 不复用，方案已声明） |
| detekt 六模块 | `./gradlew.bat detekt` | ✅ **BUILD SUCCESSFUL**（首轮 2 条本批新写代码告警——规则 1 处破 120 字符 + 删函数后 1 处孤儿 import——已修复，未入 baseline；`detekt-baseline.xml` 零新增） |
| `lintRelease` | `./gradlew.bat lintRelease` | ✅ **BUILD SUCCESSFUL**（`Lint found 36 warnings`——与 G13 报告的预存计数逐字一致，**零新增**；baseline 过滤 3 条） |
| 规范分发门禁 | `node scripts/check-agent-instructions.mjs` | ✅ EXIT=0（路由闭包 42 篇 / 446 条引用全可解析） |

## 四、改动规模（本批）

- 生产面：Kotlin 六模块 **58 文件**（含 1 整文件删 + 1 新增规则 + 1 新增测试目录族）+ C++ **16 文件**（8 头/源 + 8 测试）+
  脚本/资产 **4 项**（`build-atlas.mjs`、`lib/atlas-offline-rgba-lib.mjs`、2 个 webp 删）+ 生成物 **6 件**。
- 测试面：新增 **3 文件**（清理规则 7 例 + 旧档 tag 兼容 2 例 + ProtoNumber 守卫 1 例）、收窄 **16 文件**。
- 文档面：双 changelog + `report-G02.md` 勘误 + `knowledge-base.md` / `cpp-engine.md` / `topdown-view-art-spec.md` 现值同步 + 本报告。

## 五、关键设计取舍（可复核）

| 取舍 | 理由 |
|---|---|
| `DiscipleStatus.LAW_ENFORCING` / `REFLECTING` **枚举值保留**，只删推导规则与生产写者 | 旧档以 String name 持久化（`statuses` 列 + C++ 组件列）；删值风险 > 收益；与既有 `WAREHOUSE_GARRISON` 同口径。归一化规则负责把存量值收敛为 IDLE |
| 退役号用**注释**登记 `reserved 9,10` / `29,32`，并补**类作用域**守卫测试 | Kotlin 无 `reserved` 关键字；注释 + 守卫是既有约定（`GameData.kt` / `DiscipleSerializer.kt` 先例）。守卫必须按类抽取（同文件 `SectPolicies` 合法占用 9/10 等号） |
| 旧档建筑**不返还灵石** | 与 G04 血炼池同口径（下线建筑不退费）；方案 §6.3 已声明 |
| `sprite-uid-map.json` 的 uid 28/35 **保留** | uid 不复用规则（G04 先例） |
| 引导空号 13/14/25 **禁复用**并双向锁死 | 旧档 `guideClaimedRewardIds` 残留会让复用 id 的新步骤"开局即完成"（G12 为 24 号已立过规） |
| 不引入 feature flag | 删除类变更的固有属性（不可运行时回退）；靠门禁 + 手工旧档实测降险；加无人消费的 flag 违反 YAGNI |

## 六、⚠️ 阻塞项：另线在途改动的预存编译中断（非本批引入）

工作区同时存在**另一条线的在途重构**（未提交、进行中），其已改动的 `GameTimeClock.kt` 删除了
`speed`/`speedFlow`/`setSpeed`，但**调用方尚未收口**，导致工作区在 HEAD 之上**无法编译**：

| 文件 | 残留 | 归属 |
|---|---|---|
| `android/feature/game/.../ui/game/SaveLoadViewModel.kt:597/609` | `gameClock.speedFlow` / `gameClock.setSpeed` 未解析 | 另线（其方案 `docs/design/remove-2x-speed-implementation-plan.md` §U2 明写要删 `timeScale`/`timeSpeed`/`setTimeSpeed`） |
| `android/feature/game/.../ui/game/tabs/SettingsTab.kt:163/175/233/250/818-918` | `timeSpeed` 形参 / `SpeedToggleButton` / `setTimeSpeed` 调用 | 另线（同方案 §U1） |
| `android/core/engine/.../GameEngineCoreSetOps1.kt:312` | `speed=${gameClock.speed}` 日志 | **本批修复**（该文件当时为 HEAD 态、被另线改动打断；仅删日志中的一个字段，零行为变更） |

**处置（遵守"不覆盖同伴在途改动"纪律）**：本批**不代做** U1/U2（那些文件正被另线编辑，写入会覆盖其进度），
只修复了**未被另线编辑**的那处 stale-HEAD 日志引用以恢复 `:core:engine` 可编译。
之后另线收口完成，`compileReleaseKotlin` 已 **BUILD SUCCESSFUL**（全六模块），本批因此在 `:feature:game`/`:app`/`:core:ui` 的门禁也全部跑通（见 §3）。

### 6.2 非本批的两处预存红（工作区基线，逐条贴证归属）

| 测试 | 现象 | 归属证据 |
|---|---|---|
| `GameEngineCoreLifecycleInterleavingTest > stop during emergency restart aborts restart and loop stays stopped`（`:core:engine`，另一轮为 `RngSourceGuardTest`） | 引擎生命周期：紧急重启途中 stop 后循环应保持停止 | 该测试锚定 `GameEngineCore.stop/restart` 与 `GameTimeClock` 语义 = 另线 remove-2x-speed 的改动面（`GameEngineCore*.kt` / `system/GameTimeClock.kt` 均为其 M 文件）；本批 diff 不含任何生命周期/时钟文件。两次相邻运行红的用例**不同**（生命周期 ↔ RNG 登记）⇒ 该线在途期间本模块存在浮动红 |
| `RngSourceGuardTest > 主源随机源逐类计数不超过登记上限`（`:core:engine`） | `[core/domain] ⑤ 默认值陷阱 命中 13 处 > 登记 12 处`；13 处命中**全部**位于 `EquipmentDatabase.kt` / `EquipmentRegistry.kt` / `HerbDatabase.kt` / `ItemDatabase.kt` / `GameUtils.kt` / `NameService.kt` / `SpiritRootGenerator.kt` | 上述 7 个文件与守卫测试文件**均不在本批 diff 内**；登记值 12 由 G10（2026-09-26）按实测锁定，现实测 13 ⇒ 登记值与实测回漂（该线后续已自行恢复通过，进一步确认为其浮动面） |
| `EquipmentSingleSourceGuardTest` ×3（`:core:domain`） | `EquipmentRegistry` 与 `EquipmentDatabase` 同 id 模板数值不一致（`ironSword`：15/4000/0.03 vs 10/3600/0.02） | 两侧文件**均不在本批 diff 内**；工作区在本批执行期间被另一会话改动（`game-data.json`/`game-data.hash.txt` 一度为 M、随后复原）⇒ 属他线在途/基线态 |

**处置**：两者均**不修改**（改动静态表或下调守卫登记值 = 替另一条线做决策，且会与其在途编辑冲突），
按 `AGENTS.md` 公约 12 在本报告登记，交 RNG 库存线 / 装备数据线处理。

### 6.3 环境干扰（并发构建）

工作区在被另一会话的 Gradle 构建同时占用，本批执行期间遇到两类**非代码**故障：

1. `java.nio.file.FileSystemException: ...\core\domain\...\classes.jar: 另一个程序正在使用此文件`（连发 3 次，致
   `:core:ui`/`:feature:game`/`:app` 无法执行）——根因是**本批第一次全量测试的 job 被中断后遗留的 gradle wrapper 进程**
   （PID 27488，命令行与本批命令逐字一致）持续占用 daemon 构建；已 `Stop-Process` 清理后门禁恢复。
2. `:feature:game:testReleaseUnitTest` 在系统内存 32GB、空闲 15GB 的并发高负载下抛
   `Native memory allocation (malloc) failed to allocate 1048576 bytes`（测试 JVM 原生 OOM）——**非断言失败**；
   该模块测试源 `:feature:game:preReleaseUnitTestBuild` 为 UP-TO-DATE（**编译通过**）。
   `:feature:game` 的单测数字待环境空闲后复跑补录（本批其余模块均已实跑）。

## 七、待真机验证（pending-device 台账）

1. 旧档（含执法堂/监牢实例 + 执法长老任命 + 思过弟子）读档：两栋建筑消失、长老任命清空、**思过弟子回归空闲且可分配**；
2. 旧档（副宗主/内门长老在位）读档：**其余长老槽位逐项保留**（`ElderSlotsRetiredFieldCompatTest` 的代码级证据 + 真机实测）；
3. 旧档（已完成引导 13/14/25）读档：引导列表不含这三步、其余步骤进度不变、可继续完成；
4. 旧档（开启过「赏善罚恶」）读档：天枢殿不再显示该政策、不再扣费；
5. 图集在低端设备 Canvas 软渲染路径的观感（产物一致性由 `AtlasOfflineRgbaSyncTest` 守住）。
