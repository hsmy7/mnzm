# C++ 游戏引擎（game-core）架构文档

> 更新日期：2026-08-25。Kotlin→C++ 迁移——已完成批次归档，本文档仅保留**未完成项**详细规划。
> 总方案见 `docs/adr/cpp-engine-migration.md`。

## 1. 目标架构

```
Compose UI (feature/game + app) —— 保留，零改动
  ↓
ViewModel / UseCase —— 保留，零改动
  ↓
GameEngine 族 (~275 方法) + 10 Facade (117 方法) —— Kotlin 保留签名；低频业务操作方法体转发
  ↓  参数/结果 JSON 编解码 (kotlinx.serialization ↔ nlohmann/json)
JNI 桥 GameCoreBridge (通用入口：init/advance/execute/export/import/poll + Clock 注入)
  ↓
★ C++ GameCore（纯 C++20、零 Android 依赖、桌面可编译、iOS 可复用）—— 确定性计算真相源
  ↓  版本号 + 变更集（增量）或全量快照（存档/读档）
Kotlin StateSyncService → GameStateStore（镜像写入，接口/StateFlow 不变）
  ↓
Room 34 表 + .sav/云存档 —— 保留，链路零改动
```

> **职责边界（2026-08-25 性能基准后修正）**：C++ 接管**时间推进/结算引擎（确定性核心）+ 低频业务操作（经 JNI 转发）+ 静态数据（单一源）**；
> Kotlin 保留**高频纯计算（NativeBenchmarkTest 证实 JNI+JSON 开销 408× 于 Kotlin 纯计算，高频留 Kotlin 更快）+ UI 链路 + 未迁移系统 + 存档编码**。
> 详见第 6 节批次 10 重新审视。

## 2. 目录结构

```
android/app/src/main/cpp/
├── CMakeLists.txt              # 主构建：native-renderer（渲染）+ native-game-core（引擎 JNI）
├── GameCoreBridge.cpp/.h       # 引擎 JNI 桥（Android 专用薄层，仅转换不承载逻辑）
├── gamecore/                   # 纯 C++ 引擎（零 Android 依赖）
│   ├── CMakeLists.txt          # 静态库 game-core（GAMECORE_BUILD_TESTS=ON 构建桌面测试）
│   ├── include/gamecore/
│   │   ├── core/    types.h / result.h / clock.h / logger.h
│   │   ├── rng/     pcg_xsh_rr.h（DeterministicRng 复刻）/ rng_manager.h（8 分区）
│   │   ├── state/   models.h / json_codec.h（状态模型 + 快照编解码）
│   │   ├── data/    equipment_db / herb_db / trait_db / recipe_db / beast_material_db / manual_db
│   │   ├── map/     road_system.h（道路求解器单一权威）
│   │   ├── system/  economy / inventory / spirit_field / disciple / cultivation /
│   │   │            breakthrough / lifecycle / battle / government / exploration /
│   │   │            time_system / settlement
│   │   ├── game_core.h         # 引擎门面（execute/advance/export/import/poll）
│   │   └── action_ids.h        # 生成产物（scripts/gen-action-ids.mjs）
│   ├── src/  rng.cpp / game_core.cpp / json_codec.cpp / execute_dispatch.cpp
│   ├── jni/  GameCoreJni.cpp   # 桌面 JNI 对拍桥（无 Android 依赖，测试用）
│   ├── test/  （GTest，见各批次一览）
│   └── third_party/nlohmann/json.hpp   # vendored 单头 JSON
├── VulkanBackend.cpp ...       # 既有渲染层（不动）
```

## 3. 确定性保真要求（迁移全程）

1. **RNG**：PCG-XSH-RR 复刻；`shuffled(rng)` 用 `std::stable_sort`（Kotlin sortedBy 稳定）——登记 C-11
2. **数学语义**：IEEE754 double、Int/Long 溢出回绕、截断除法、coerceIn/coerceAtMost——登记 C-12（nextGaussian 精度风险）
3. **顺序稳定性**：禁止 `unordered_map` 参与业务迭代（用 map/vector + 显式排序）
4. **现实时间**：一律 `Clock` 注入（对拍用 FixedClock）；引擎内禁直接系统时间
5. **对拍守护**：C++ GTest 黄金序列 + JUnit 跨语言对拍——登记 C-14/C-15

## 4. 已完成批次（归档一览）

> 各批次详细验收记录（产物/对拍清单/验证数字）已随推进写入 git 历史（commit 2bb319d9 / 7269ab6c 及更早）。
> 当前基线：**桌面 GTest 289/289 · engine JUnit 2818/2818 · NDK externalNativeBuildRelease 通过 · engine detekt 全绿**。

| 批次 | 内容 | 关键产物 | 验证 |
|---|---|---|---|
| 0 基础设施 | game-core 静态库（C++20 零依赖）、Result/Clock/Logger、RNG 8 分区复刻、JNI 桥、ActionId 协议、桌面对拍桥 | `core/*`、`rng/`、`GameCoreBridge`、`gen-action-ids.mjs` | GTest + DiffRngTest 双守护 |
| 1 状态模型+快照 | GameData 全字段 + 18 嵌套类型 + Disciple/物品；JSON 编解码（宽松 from_json、optional、浮点规范化） | `state/models.h`、`json_codec`、`NativeGameState.kt` | DiffStateTest + json_codec_test 38 |
| 1 剩余·低频嵌套 | 血炼三件套/功法精通/矿脉/巡视槽位 + **远古秘境状态机 10 类型**（含 optional currentEvent） | models.h 扩展 + 编解码 | DiffNestedTypesTest |
| 2 静态数据 | 装备 72 / 灵草种子 108 / 天赋体质词条 204 / 配方 804 / **妖兽材料 192 / 功法 540** | `data/*_db.h` + 生成器 6 个 | 双端守卫测试（Kotlin + C++） |
| 3 时间+结算引擎 | TimeSystem 复刻 + SettlementEngine（对齐 GameTimeClock 语义、读档复位） | `system/time_system.h`、`settlement.h` | DiffTimeTest + time_system_test 15 |
| 4 经济/库存/灵田 | SpiritStoneWallet 逐行等价 / StackableItemStore / 灵田收获 | `system/economy.h`、`inventory.h`、`spirit_field.h` | DiffEconomy 8 + DiffInventory 5 + DiffSpiritField 4 |
| 5 弟子系统 | 属性乘区法/修炼 Checkpoint/突破/生命周期 | `system/disciple.h`、`cultivation.h`、`breakthrough.h`、`lifecycle.h` | Diff* 30 + GTest 192 |
| 6 战斗系统 | 乘区法伤害/境界压制/斩杀/闪避/护盾/DoT/冷却 | `system/battle.h` | DiffBattleTest 7 + GTest 225 |
| 7 内政系统 | ZoneCalculator 等价/政策成本/灵矿产出/年俸 | `system/government.h` | DiffGovernmentTest 6 + GTest 245 |
| 8 探索/关卡 | 关卡过期/刷新/妖兽移动 | `system/exploration.h` | DiffExplorationTest 2 + GTest 254 |
| 9 核心 | ActionId **46 动作** + execute 分发表（7 handler 统一信封） | `action_ids.h`、`execute_dispatch.cpp` | DiffExecuteTest 6 + GTest 268 |
| 9 剩余·基础设施 | feature flag / StateSyncService（字段级宽松合并）/ tick 桥（shadow 对拍）/ 转发辅助 / 性能基准 | `NativeEngineFlag`、`StateSyncService`、`GameEngineNativeOps` | DiffStateSyncTest 7 + DiffNativeForwardTest 4 + NativeBenchmarkTest 2 |
| R 求解器权威 | 位掩码→形态/描边/邻接计算单一权威（Kotlin RoadTiling ↔ C++ road_system.h 双端对拍）；Vulkan 端位掩码判定收敛 | `map/road_system.h`、`NativeBridge.cpp` 收敛 | DiffRoadTest 3 + road_system_test 16 |

## 5. 未完成项（活跃待办）

### 5.1 批次 9 剩余：转发层收尾（C-06）

| 项 | 说明 |
|---|---|
| 已完成 | feature flag / StateSyncService（宽松合并防丢字段）/ tick 桥（shadow 对拍）/ 转发辅助 / 性能基准（见 4.9 剩余·基础设施） |
| 剩余·GameEngine 方法转发 | Kotlin GameEngine 275 方法逐一转发——**范围必须按性能基准裁剪**：NativeBenchmarkTest 证实 JNI+JSON 开销显著（wallet add 1000 次 11.8ms vs Kotlin 28µs），**高频纯计算应留在 Kotlin 侧**；仅低频业务操作（玩家行为入口）适合转发 |
| 剩余·增量变更集同步 | `nativeExportDirty` 当前为空实现，需 C++ 侧变更集追踪（对比上次导出，`changed`/`removed` 增量） |
| 剩余·全量切换 | C++ 为真相源的全量切换登记为批次 10 前置；shadow 对拍期 Kotlin 引擎仍是运行时真相源 |
| 阻塞依赖 | 未迁移系统（SecretRealm 状态机/外交/邮件/兑换码/11 槽分配/死亡物化/LevelGenerator 等）无 C++ 对应动作，转发无从谈起——**这些系统不迁移，保持 Kotlin 实现**（职责边界见第 1 节） |

### 5.2 批次 10：职责边界固化（C-07，重新审视后重定义）

> **2026-08-25 重新审视**：原计划"Kotlin 引擎退役（删除 6.9 万行 Kotlin 引擎逻辑）"与性能基准数据冲突——
> NativeBenchmarkTest 证实 JNI+JSON 传输开销 408× 于 Kotlin 纯计算；且大量系统（SecretRealm/外交/邮件/兑换码等）
> 依赖 Kotlin 状态链路，C++ 无对应动作。**全量退役既无性能收益也不可行**。批次 10 重定义为**职责边界固化**：

| 项 | 说明 |
|---|---|
| C++ 接管 | 时间推进/结算引擎（确定性核心）+ 低频业务操作转发（玩家行为入口）+ 静态数据单一源 |
| Kotlin 保留 | 高频纯计算（性能基准）+ UI 链路 + 未迁移系统 + 存档编码（kotlinx-proto 链路零改动） |
| 落地动作 | ① 按性能基准确定转发方法清单（低频操作）并接线；② 未迁移系统显式登记为"Kotlin 永久保留"；③ 对拍框架转回归基线；④ 静态数据双份（T-CPP-2）决定保留或 codegen 单一源 |
| 验收 | 转发清单全部接线 + 对拍守护 + 文档职责边界冻结；**不做 Kotlin 引擎全量删除** |

### 5.3 批次 R 剩余：渲染合成器物理下沉

| 项 | 说明 |
|---|---|
| 已完成 | 求解器权威性（掩码→形态/描边/邻接双端对拍）+ Vulkan 端位掩码判定收敛 road_system.h 单一权威 |
| 剩余 | **渲染合成器物理下沉**：Kotlin Canvas `SoftwareCanvasBackend.drawRoadsToCanvas` 与 C++ Vulkan `NativeBridge.drawAllTiles` 道路段的逐格合成（主体/描边条/转角件/十字中心的摆放顺序）统一为单一 C++ 合成器，Kotlin 侧仅做数据装配；同时解除与生成式图集的强耦合 |
| 依赖 | 跨模块 JNI 通道 + Vulkan/Canvas 双路径回归——渲染层大工程，需与渲染回归协同推进 |

### 5.4 审查登记项（C-10 ~ C-15）

| # | 项 | 触发/计划 |
|---|---|---|
| C-10 | **批次 3 剩余：月变/年变结算钩子系统实现**（政策成本/生产/年俸/年度报告等 onMonthChange/onYearChange 钩子接线） | 政策成本/灵矿/年俸已 C++ 化；钩子接线随批次 9 转发层推进 |
| C-11 | **审查登记：C++ `shuffled(rng)` 未实现**——实现时必须用 `std::stable_sort`（Kotlin sortedBy 稳定），且确定性对拍 | 批次 5+（涉及随机打乱时） |
| C-12 | **审查登记：nextGaussian 跨语言精度风险**——JVM Math.cos/log/sqrt 与 C++ std::cos/log/sqrt 可能最后一位差异；对拍验证，发现差异则内嵌 fdlibm | 批次 5（弟子属性生成） |
| C-13 | **审查登记：读档后 RNG 分区状态恢复**——GameCore.rng_ 需从 GameData.rngStates 恢复（import 时），当前未接线 | 批次 9 转发层接线时 |
| C-14 | **审查登记：float 字段对拍覆盖**（WorldSect.x/y、WorldLevel.x/y）——已覆盖抽样，全量 float 语义随批次扩展 | 随批次 4-8（核心已完成） |
| C-15 | **审查登记：Diff 对拍基准为内联复刻**（DiffTimeTest 复刻 TimeSystem.onPhaseTick；集成时切换为真实引擎对拍） | 批次 9 集成切换时 |
