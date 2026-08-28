# C++ 游戏引擎（game-core）架构文档

> 更新日期：2026-08-28。Kotlin→C++ 迁移——已完成批次归档，本文档仅保留**未完成项**详细规划。
> 总方案见 `docs/adr/cpp-engine-migration.md`。
> 当前基线：**桌面 GTest 550/550 · JUnit 全模块 7271/7271（engine 2928，testReleaseUnitTest 全量） · NDK externalNativeBuildRelease 通过 · engine detekt 全绿 · compileReleaseKotlin 通过（2026-08-28 实测）**。
> **计划 v2 阶段 0、1、2、3、4、5、6 已完成**（阶段 2：批量结算下沉 + tick 真相源切换 AUTHORITATIVE
> 过渡管线；阶段 3：反向增量通道 + DiscipleStore SoA 实体存储 + 静态数据单一源；阶段 4：
> 未迁移系统逐批 C++ 化——LevelGenerator/死亡物化/SecretRealm 状态机核心/外交决策/
> 11 槽分配/兑换码+邮件附件；阶段 5：游戏循环入 C++——平台能力接口化（Clock/Telemetry/
> 热控/电量端口）+ 引擎循环（PhaseClock/EngineLoop）+ 看门狗判据（ProgressMonitor）；
> 阶段 6：渲染 RHI + 合成器统一——道路合成器单一权威物理下沉（批次 R 剩余清零）+
> Renderer2D → Rhi.h 形式化（Metal/iOS 预留），详见 §7 阶段 6 行）。

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

> **终态决策（2026-08-25 二次修订，选项 A：彻底单引擎）**：C++ 为**唯一**引擎核心（游戏循环 + 结算 + 实体存储数据导向 + 渲染），
> Kotlin 最终降级为**纯平台层**（Activity/生命周期/权限/输入桥/平台 SDK/Compose UI 消费只读镜像）+ 存档编码（低频，可选迁移）。
> 推翻 2026-08-25 上午的"职责边界固化（双端并行=最终态）"——依据阶段 0 实测：批量通道往返 0.1µs、传输占比 <0.1%，
> 双实现并行的镜像/对拍开销是纯浪费。详见第 6 节（阶段 0 数据）与第 7 节（计划 v2）。

## 2. 目录结构

```
android/app/src/main/cpp/
├── CMakeLists.txt              # 主构建：native-renderer（渲染）+ native-game-core（引擎 JNI）
├── GameCoreBridge.cpp/.h       # 引擎 JNI 桥（Android 专用薄层，仅转换不承载逻辑）
├── gamecore/                   # 纯 C++ 引擎（零 Android 依赖）
│   ├── CMakeLists.txt          # 静态库 game-core（GAMECORE_BUILD_TESTS=ON 构建桌面测试）
│   ├── include/gamecore/
│   │   ├── core/    types.h / result.h / clock.h / logger.h / platform.h（阶段 5：Clock/Telemetry/热控/电量端口）
│   │   ├── rng/     pcg_xsh_rr.h（DeterministicRng 复刻）/ rng_manager.h（8 分区）
│   │   ├── state/   models.h / json_codec.h（状态模型 + 快照编解码）
│   │   ├── data/    equipment_db / herb_db / trait_db / recipe_db / beast_material_db / manual_db
│   │   ├── map/     road_system.h（道路求解器单一权威）
│   │   ├── system/  economy / inventory / spirit_field / disciple / cultivation /
│   │   │            breakthrough / lifecycle / battle / government / exploration /
│   │   │            time_system / settlement / engine_loop.h（阶段 5：PhaseClock+EngineLoop）/
│   │   │            watchdog.h（阶段 5：ProgressMonitor 判据）
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
| 已完成 | feature flag / StateSyncService（宽松合并防丢字段）/ tick 桥（shadow 对拍）/ 转发辅助 / 性能基准（见 4.9 剩余·基础设施）；**阶段 1 新增**：增量变更集通道（C++ `state::DirtyTracker` + Kotlin `StateSyncService.applyDirty/applyDirtyFromNative`，DiffDirtyTest / DiffDirtyDisciplesTest / GTest dirty_tracker_test 三层守护）、RNG 读档恢复接线（C-13，含导出前活动状态回写） |
| 剩余·GameEngine 方法转发 | Kotlin GameEngine 275 方法逐一转发——转发范围按计划 v2 阶段 2-4 决定：**C++ 侧实现对应逻辑后即可转发**（正确基准：真实实现对比 1.1×、批量打平，转发成本可忽略），不再按"低频/高频"裁剪 |
| 剩余·全量切换 | ~~增量变更集~~（✅ 阶段 1 完成）；**计划 v2 阶段 2 起逐系统切换**（每阶段 C++ 真相源 + 对拍守护）；阶段 7 完成后 Kotlin 引擎退役，shadow 对拍转回归基线 |
| 阻塞依赖 | 未迁移系统（SecretRealm 状态机/外交/邮件/兑换码/11 槽分配/死亡物化/LevelGenerator 等）——**纳入计划 v2 阶段 4 逐批 C++ 化**（不再"保持 Kotlin 实现"） |

### 5.2 批次 10：彻底单引擎（C-07，2026-08-25 二次重定义）

> **2026-08-25 二次重新审视**：上午"职责边界固化"结论基于"批量通道未验证 + 全量退役无收益"的保守假设；
> 阶段 0 实测（第 6 节）推翻该假设——批量通道（标量参数）往返仅 0.1µs、传输占比 <0.1%，
> 且双实现并行 = 每 tick 双倍计算 + 全量快照同步 = 纯浪费。用户决策采纳**选项 A：彻底单引擎**。
> 批次 10 重定义为**阶段化退役 Kotlin 引擎**（计划 v2 见第 7 节），不再保留"Kotlin 永久保留"清单。

| 项 | 说明 |
|---|---|
| C++ 接管（终态） | 游戏循环 + 时间推进/结算引擎 + 实体存储（数据导向/ECS）+ 未迁移系统逐批 + 静态数据单一源 + 渲染 RHI |
| Kotlin 保留（终态） | UI/Compose + 平台能力（SDK/广告/合规/看门狗接口化）+ 存档编码（可选 T-CPP-1 迁移）+ 输入桥 |
| 落地动作 | ① 增量变更集（exportDirty）实现——C++ 真相源 → Kotlin 镜像核心通道；② 未迁移系统按第 7 节逐批 C++ 化（不再"永久保留"）；③ ~~游戏循环平台能力接口化后迁 C++~~（✅ **阶段 5 已完成**：Clock/Telemetry/热控/电量端口 + PhaseClock/EngineLoop + 看门狗判据全部入 C++，Kotlin 驱动侧仅剩协程线程本体/delay/帧率策略/ADPF 上报等平台机制）；④ 静态数据单一源（T-CPP-2 提前触发，✅ 阶段 3 已完成）；⑤ 对拍框架全程守护 |
| 验收 | 每阶段 C++ 真相源切换 + 对拍全绿 + 性能对比（对阶段 0 基线） |

### 5.3 ~~批次 R 剩余：渲染合成器物理下沉~~ ✅（计划 v2 阶段 6 完成，见 §7 阶段 6 行）

| 项 | 说明 |
|---|---|
| ~~已完成~~ ✅ | 求解器权威性（掩码→形态/描边/邻接双端对拍）+ Vulkan 端位掩码判定收敛 road_system.h 单一权威 |
| ~~剩余~~ ✅ **渲染合成器物理下沉**（2026-08-28 阶段 6 完成）：逐格合成（主体/描边条/转角件/十字中心的摆放顺序）统一为单一 C++ 合成器 `gamecore/map/road_compositor.h`（RoadSprite 语义枚举 + 格内整型几何操作序列），Kotlin Canvas `drawRoadsToCanvas` 改为纯数据装配（枚举序→精灵名→源矩形），Vulkan `drawAllTiles` 道路段删除 UV 硬编码改消费操作序列；与生成式图集解耦（合成器零 UV/精灵名依赖，图集映射由各端按枚举序号查表）；降级契约：native 通道不可用（库加载失败，生产不触达）时跳过道路层，chunk 烘焙其余层不受影响 |

### 5.4 审查登记项（C-10 ~ C-15）

| # | 项 | 触发/计划 |
|---|---|---|
| C-10 | **批次 3 剩余：月变/年变结算钩子系统实现**（政策成本/生产/年俸/年度报告等 onMonthChange/onYearChange 钩子接线） | 政策成本/灵矿/年俸已 C++ 化；钩子接线随**计划 v2 阶段 2**（批量结算下沉）推进 |
| C-11 | **审查登记：C++ `shuffled(rng)` 未实现**——实现时必须用 `std::stable_sort`（Kotlin sortedBy 稳定），且确定性对拍 | 批次 5+（涉及随机打乱时） |
| C-12 | **审查登记：nextGaussian 跨语言精度风险**——JVM Math.cos/log/sqrt 与 C++ std::cos/log/sqrt 可能最后一位差异；对拍验证，发现差异则内嵌 fdlibm | 批次 5（弟子属性生成） |
| ~~C-13~~ ✅ | **RNG 读档恢复已接线**：`importStateJson` 从 `GameData.rngStates` 恢复分区状态，`exportStateJson/exportDirtyJson` 导出前回写活动状态（守护：dirty_tracker_test.ImportRestoresRngPartitionStates + DiffStateTest 契约更新） | 完成（计划 v2 阶段 1） |
| C-14 | **审查登记：float 字段对拍覆盖**（WorldSect.x/y、WorldLevel.x/y）——已覆盖抽样，全量 float 语义随批次扩展 | 随批次 4-8（核心已完成） |
| ~~C-15~~ ✅ | **Diff 对拍基准已切换真实引擎**：DiffTimeTest Kotlin 侧改为真实 `TimeSystem` 实例驱动（内联复刻删除）；DiffExecute 等其余对拍本就走真实通道 | 完成（计划 v2 阶段 1） |

## 6. 阶段 0 测量基线（2026-08-25，彻底单引擎决策依据）

> 测试：`Phase0SettlementBenchmarkTest`（纯 JVM，真实 CultivationCore + 桌面对拍桥，预热+多次采样取最小）。

| 测量 | 结果 | 结论 |
|---|---|---|
| C++ 批量通道 `advancePhases(1)`（标量参数，含 JNI 往返） | **0.1µs/次** | 标量/二进制协议下 JNI 往返可忽略；JSON 编解码才是 12µs 往返的成本大头——**高频批量必须走标量/二进制协议，禁用 JSON 逐操作** |
| Kotlin 每旬核心路径（HP/MP 恢复+修炼累积，真实 CultivationCore） | 100 弟子 167µs · 1000 弟子 **327µs** · 5000 弟子 1189µs（O(D)，每弟子 ~0.3µs 收敛） | 每旬检查是真实 CPU 热点（月 30 旬 ≈ 10ms+，叠加月变/年变更高）；未含熟练度/孕养/丹药/突破（同量级 O(D)） |
| 批量下沉传输占比 | **<0.1%** | 每旬整批下沉 C++ 的传输成本可忽略——阶段 2 收益最高且最可行 |

**收益排序（据此安排阶段）**：阶段 2 批量结算下沉（传输无碍，C++ 结算替代 Kotlin 每旬热点）> 阶段 1 增量变更集（消除全量快照镜像）> 阶段 3 数据导向存储（降每弟子成本）> 阶段 4-5 系统迁移/引擎循环（确定性/平台化）。

## 7. 彻底单引擎计划 v2（选项 A 执行路径，合并架构待办）

> 每阶段验收：C++ 真相源切换 + 对拍守护全绿（GTest 289 + JUnit 对拍）+ 性能对比对阶段 0 基线 + 可运行可回退。
> 待办合并规则：C 系列 = 迁移主线（调整触发）；R 系列 = Kotlin 侧质量债务（保留，随 Kotlin 面收窄部分自然消除；R-14 = 2026-08-28 阶段 6 途中发现的 feature:game detekt 存量 10 项——`feature:game:detekt` 从未进入批次验证门，见 architecture.md R 系列登记表）；T 系列 = 触发条件调整（T-CPP-2 提前、T-CPP-1 保持）。

| 阶段 | 内容 | 合并的待办 |
|---|---|---|
| 0 ✅ | 测量基线（已完成：热点 + 批量原型） | — |
| 1 ✅ | **增量变更集 + RNG 恢复**（已完成：`state::DirtyTracker` changed/removed/version 协议 + `StateSyncService.applyDirty` 单事务增量镜像 + import 恢复 rngStates（C-13）+ 对拍基准切换真实引擎（C-15）+ S-01~S-04 清理） | C-13、C-15 |
| 2 ✅ | **批量结算下沉**（已完成 2026-08-26）：每旬核心批次（步骤 1-5 零 RNG）C++ 化（T2.1）、月变钩子（T2.2）、年变钩子（T2.3，含钩子序年先于月对齐）、tick 真相源切换 AUTHORITATIVE 过渡管线（T2.4：settleOnePhase 标量通道 + NativeEngineFlag 三态 + 残留执行器 + NativeBackedRng 委托式 RNG 单一真相源 + 每旬双向同步）；D7 三注册表效果聚合填表 + comprehension 分叉修复（T2.4a）。**AUTHORITATIVE 默认 OFF（灰度开关）**；100 旬逐旬互锁对拍验收 PASS | C-10、C-06 增量部分、T-CPP-2（部分触发：注册表消费侧已统一） |
| 3 ✅ | **反向增量通道 + SoA 实体存储 + 静态数据单一源**（2026-08-26 完成）：
  - **反向增量通道**（T3.1）：AUTHORITATIVE tick 步骤 ⑤ 由全量 importToNative 改为 `applyDirtyToNative` 增量回导——GameStateStoreImpl 事务级反向脏捕获（弟子脏 id peek + gameData 引用 + 集合引用全量/消失 id）+ StateSyncService 信封 {version,changed,removed}（gameData 全量剔除 rngStates + 弟子变化 id 全实体/removed + 集合变化全量/removed）+ C++ `GameCore::applyReverseDirty`（版本严格递增 + 基线同步）；失败降级全量。100 旬互锁对拍 PASS
  - **DiscipleStore SoA**（T3.2）：`GameState.disciples` 由 `std::vector<Disciple>` 改为 SoA 列式存储（~124 列 + idToRow + materialize/append/loadFrom/upsert 保序/removeById/swapRows 旋转同步索引）；JSON 协议零变更；每旬核心批次/月变/年变/突破/丹药路径全列化；快照语义保留；**性能：runPhaseCoreBatch 1000 弟子 180µs vs 阶段 0 Kotlin 基线 327µs（1.8x）**
  - **静态数据单一源**（T3.3 / T-CPP-2）：`scripts/data/*.json`（6 类中性源，唯一权威）→ 6 个 gen-*.mjs 只读中性源 → C++ 表 + 测试快照（重跑零漂移）；Kotlin Registry 由各 RegistryGuardTest + 新增 `StaticDataSingleSourceGuardTest`（中性源 ↔ 快照逐字节）兜底；补齐灵草/种子双端守卫（HerbRegistryGuardTest + herb_db_test.cpp，预存缺口）；修复 beast_material_db.h 中文妖兽名映射漂移（收敛进生成器） | T-CPP-2（Kotlin Registry 文件级生成余项登记，偿还触发：阶段 7/iOS 立项） |
| 4 ✅ | **未迁移系统逐批 C++ 化**（2026-08-27 完成，5 批 6 模块，GTest 478/478 + JUnit 对拍全绿 + NDK 构建通过）：
  - **批 4-1 LevelGenerator**：`system/level_generator.h`（妖兽类型/境界属性表 + selectBeastRealm 年份加权 + generateBeastLevel/CaveLevel/WorldLevels 位置去重与距离校验 + 属性预生成 + 洞府奖励）；ActionId 1402/1403；`WorldLevel.beastSpeed` 协议补齐；GTest 15 + JUnit DiffLevelGeneratorTest 3
  - **批 4-2 死亡物化**：`system/death_handler.h`（markDead 三字段 + 年死亡计数 + 装备断言 + backfillDeathYears）；`DiscipleStore.deathYears` 列（纯内存，不进 JSON 协议，upsert 保留语义对齐 Kotlin replaceAll）；ActionId 1404/1405；GTest 11 + JUnit DiffDeathHandlerTest 4
  - **批 4-3 SecretRealm 状态机核心**：`system/secret_realm.h`（playerAvgRealm/rollBeastRealm/事件生成 beast·rest·ruins·direction·AI 遭遇/rollNextEvent 一次 nextDouble 分段/buildBeastPreGenStats/rollBeastLoot/遗迹结算/applyLootLoss 洗牌/AI 队伍派遣/位置寻找 Float 精度/体力 clamp/年变现世判定）；模型复用批次 1；边界：战斗执行（BattleSystem）与秘宝模板实例化保留 Kotlin；ActionId 1406~1419；GTest 29 + JUnit DiffSecretRealmTest 8
  - **批 4-4 外交**：`system/sect_decision.h`（四因素概率模型 + 脱离 + 战力分档，SectDecisionConfig 同源）+ `system/sect_power.h`（弟子/妖兽战力 + fingerprint，Java hashCode 语义见 `system/java_hash.h` UTF-16 解码）+ `system/rarity_progression.h`（品阶时间曲线，3000 年后爬升轨道）+ `system/sect_trade.h`（交易确定性种子/库存曲线/价格波动/灵石映射）；ActionId 1420~1432；GTest 20 + JUnit DiffSectDiplomacyTest 7
  - **批 4-5 11 槽分配**：`system/slot_cleanup.h`（clearAllSlotsDataOnly 11 类槽位纯数据变换）；补齐缺失模型（GarrisonSlot/BattleTeam/BattleTeamSlot/WarehouseGarrisonSlot/CaveExplorationTeam/ActiveMissionLite + GameData/WorldSect 字段 + JSON 协议）；边界：Gate 注册表与完整 ActiveMission 保留 Kotlin；ActionId 1433；GTest 9 + JUnit DiffSlotCleanupTest 2
  - **批 4-6 兑换码+邮件附件**：`system/redeem_code.h`（格式校验/灵根生成含 java.util.Random 48 位 LCG 复现/灵根阶梯/年龄寿元/方差）+ `MailAttachment` 模型与 kotlinx 对齐的附件 JSON 编码；边界：名字/体质/词条/天赋注册表与服务器验证保留 Kotlin；ActionId 1434~1439；GTest 13 + JUnit DiffRedeemCodeTest 3 | 原 C-06 阻塞依赖清单（原"永久保留"清单全部纳入，不再保留） |
| 5 ✅ | **游戏循环入 C++**（2026-08-27 完成，GTest 538/538 + JUnit 对拍全绿 + NDK 构建通过）：
  - **批 5-1 平台能力端口**：`core/platform.h`（MonotonicClock + Steady/Fixed 实现、TelemetrySink + Null、ThermalState 枚举 + Settable 热/电 Provider、BatteryStatus 结构）——ADR Clock/Logger 注入先例扩展；GameCoreBridge.cpp 注入 `AndroidMonotonicClock`（CLOCK_BOOTTIME，与 elapsedRealtime 一致含深度睡眠）+ `AndroidTelemetrySink`（logcat）+ Settable 热/电全局实例；`PlatformProviders` + `setPlatformProviders()` 门面
  - **批 5-2 引擎循环**：`system/engine_loop.h`——**PhaseClock**（GameTimeClock 逐位移植：墙钟消费/速度切换旧速度结算/追补上限 3×speed 余量丢弃/consumeDeadTime/forceConsumeOnePhase/refundPhases/msPerPhase/phaseProgress，accumulatedGameMs/speed atomic 镜像 Kotlin @Volatile）+ **EngineLoop**（gameLoopIteration 判据移植：iterate(pausedOrLoading, isSaving) 返回 LoopFramePlan；kLogicDtNs=100ms、kMaxAccumulatorNs=5 步、kMaxStepsPerFrame=5；心跳 lastLoopActivityMs；notifyUserActivity；onLoopRestart）；**17 槽 LongArray 帧计划协议**（每帧一次 JNI 标量通道，禁 JSON——阶段 0 基准 JSON 往返 12µs 为成本大头）；GTest 31（PhaseClock 19 + EngineLoop 10 + 平台端口 2）+ JUnit DiffEngineLoopTest 15 双端对拍（时钟状态机 + 帧计划语义）
  - **批 5-3 看门狗判据**：`system/watchdog.h`——**ProgressMonitor**（GameTimeProgressMonitor 逐位移植：ProgressSnapshot 12 字段/StallVerdict 数值码 0-4/evaluate/classify/classifyFlags/三阈值 45s·90s·20s/std::mutex 线程安全；S1/S4/S5/F2/V1/V6 修复分支随行移植）；GameCore 组合通道 `watchdogVerdict(flags)`（引擎侧状态 C++ 组合 + 平台侧 6 flags）；-1 未初始化回退 Kotlin；GTest 25 + JUnit DiffWatchdogTest 24 全矩阵对拍
  - **批 5-4 AUTHORITATIVE 接线**：`GameEngineCoreLoopOps.kt`（authoritativeLoopIteration 帧迭代 AUTHORITATIVE 化 + tickAuthoritativeStep + nativeVerdictToStall + thermalSeverityCode）+ `GameEngineCore.kt`（gameLoopIteration 顶部 AUTHORITATIVE 分流、onSpeedChanged→nativeLoopSetSpeed 钩子、nativeLoopPipelineActive refund 分流、prepareLoopStart→nativeLoopStart、performEmergencyRestart→nativeLoopOnRestart、onUserActivity→nativeLoopNotifyUserActivity、progressVerdict native 判据分支 + 6 内部辅助/共享辅助提取）；`GameEngineCoreAuthoritativeOps.kt` refund 按真相源分流；回退契约：帧计划不可用→纯 Kotlin 累积器路径；**AUTHORITATIVE 默认 OFF（灰度开关）不变**
  - **批 5-5 R-02 循环路径清除**：`GameTimeClock.kt` 删 `SystemClock/Log` import（SystemTimeSource/TimeSourceModule 移 app 层 `di/PlatformTimeModule.kt`）；`GameEngineCore.kt` 删 `Build` import（doBusyWait SDK_INT≥33 改 supportsOnSpinWait 反射探测）；engine 模块 33 处 Android import 剩 ~30 处（perf/thermal/registry/config/service/domain）随阶段 7 Kotlin 引擎退役时移 app 层 | R-02（core/engine Android 依赖随引擎退役自然消除——循环路径 3 处已清除，剩余 ~30 处阶段 7 退役时移出） |
| 6 ✅ | **渲染 RHI + 合成器统一**（2026-08-28 完成，GTest 550/550 + JUnit 对拍全绿 + NDK 构建通过）：
  - **批 6-1 合成器单一权威**：`gamecore/map/road_compositor.h`（零依赖纯函数 `emitRoadDrawOps`：掩码 → RoadSprite 语义枚举 + 格内整型几何操作序列，顺序契约 主体→描边条→转角件→十字中心 与双端烘焙顺序一致；与生成式图集解耦——合成器零 UV/精灵名依赖，枚举序 = ROAD_RECTS 声明序 = roadUVMap 索引 = SPRITE_KEYS 下标三端映射锚点）；GTest 12（全 16 掩码操作数守恒/几何有界/顺序契约/枚举序锚点/tileSize=32 整型↔浮点一致性）
  - **批 6-2 Vulkan 路段接入**：`NativeBridge.drawAllTiles` 道路段删除 roadTypeForMask/UV 硬编码，改消费合成器操作序列（仅做 操作→SpriteBatcher 数据装配）；新增 roadUVMap 长度防御；删本地 roadTypeForMask 包装（road_system.h 直引）
  - **批 6-3 Canvas 接入**：`SoftwareCanvasBackend.drawRoadsToCanvas` 改纯数据装配（逐格 `RoadCompositorBridge.compose` JNI 通道 → RoadSprite 枚举序→精灵名→图集源矩形，RoadTiling 合成逻辑移除）；生产 JNI `GameCoreBridge.nativeRoadCompose`（无状态纯函数，不依赖引擎实例）；`RoadCompositorBridge`（core/render）+ 守护测试（SpriteAtlasDefGeneratedTest：SPRITE_KEYS ↔ ROAD_RECTS 声明序全等）+ JUnit DiffRoadComposeTest 5（桌面对拍桥 compose op，手算规格 + 全 16 掩码结构不变量）；降级契约：compose 首次调用幂等 ensureLoaded 自加载，仅加载失败（JVM 测试环境/极端损坏）返回 null 跳过道路层（生产不触达，不影响 chunk 其余层）；同批产品回退：恢复建造栏石板路建造入口（回退 8c9b7734，独立提交）
  - **批 6-4 RHI 形式化**：`Renderer2D.h` → `Rhi.h`（RHI 契约：上层 NativeBridge/SpriteBatcher 不得 include 图形 API 头，下层实现 VulkanBackend 现有 / MetalBackend iOS 预留；类名 Renderer2D 保留）；Metal 接入指南（CAMetalLayer/NDC 差异/uploadTexture/submitFrame 语义映射，见 Rhi.h 头注释）——iOS 立项时零上层改动接入 | 批次 R 剩余（✅ 全部完成）、iOS 预留 |
| 7 | **Kotlin 降级纯平台层 + 存档决策**：Kotlin 引擎逻辑退役；存档编码决策（T-CPP-1 保持 Kotlin 或迁 C++ 直出 proto）；iOS Swift 平台层 | T-CPP-1、C-07 验收 |

**保持不动（与迁移方向无关）**：R-01/03~14（detekt/lint/测试质量债务；R-14 = feature:game detekt 存量 10 项 + 验证门缺口，随阶段 7 Kotlin 面收窄与 MainGameScreen/Canvas 拆分专项处置）、T-D46~D49/T-D40/T-A2/T-RB/T-CONV/T-PRO（平台/发行技术债）、P 系列真机验证、扩展性预留（RemoteConfig/商业化/离线收益——离线收益结算接入点在阶段 4 后自动走 C++）。

## 8. 存量问题清理清单（S 系列，迁移全程途中发现）

> 来源：迁移架构报告与子代理深潜途中发现（死代码/过时文档/设计缺口），统一登记并分配清理时机，防止遗漏。
> 原则：死代码清理必须是低风险最小修改（删除前确认无引用 + 全量测试守护）；文档勘误不阻塞阶段，可随时执行。

| # | 问题 | 位置 | 类型 | 清理时机 |
|---|---|---|---|---|
| ~~S-01~~ ✅ | **死代码 `GameEngineCore.tick()` 已删除**（连同仅其使用的 `TICK_WARNING_THRESHOLD_MS` 常量；删除前确认全仓库无调用点） | `GameEngineCore.kt` | 死代码 | 完成（阶段 1 前置） |
| ~~S-02~~ ✅ | **过时文档 `UnifiedGameState` 已修正**：KDoc 与 docs/architecture.md 改为"逐字段流 + 三层派生流"现状描述 | `GameEngineCore.kt` + `docs/architecture.md` | 文档过时 | 完成 |
| ~~S-03~~ ✅ | **地图渲染文档勘误完成**：`docs/map-rendering-architecture.md` 两处"每帧更新"改为 RenderFrame 帧率门控推送（与 SectMapViewport 实现一致） | `docs/map-rendering-architecture.md` | 文档滞后 | 完成 |
| ~~S-04~~ ✅ | **SavePipeline 旧名注释已清理**：SaveStorage.kt KDoc + StorageSystemBenchmark.kt 三处输出文案改为现行组件名 | `core/domain/.../repository/SaveStorage.kt` 等 | 注释过时 | 完成 |
| ~~S-05~~ ✅ | **RNG 读档恢复已接线**（并入 C-13，见 §5.4） | `game_core.cpp` | 功能缺口 | 完成（计划 v2 阶段 1） |
| ~~S-06~~ ✅ | **exportDirty 变更集已实现**（C++ DirtyTracker + Kotlin applyDirty，见 §5.1/§7 阶段 1） | `GameCoreBridge` / `game_core.h` | 功能缺口 | 完成（计划 v2 阶段 1） |
| S-07 | **设计限制 `DomainLog` 无 logger getter**：`setLogger` 后无法恢复旧 logger（基准测试需行为等价替代） | `core/domain/.../util/DomainLog.kt` | 设计改进（低优先） | 可选：暴露 `currentLogger()` 或 `setLogger` 返回旧值；不阻塞任何阶段 |
| ~~S-08~~ ✅ | **NDK 编译验证已通过**：2026-08-27 `externalNativeBuildRelease` 成功（阶段 2 新增 JNI 入口 + 阶段 4 新增 6 系统头文件/模型在 NDK 工具链下编译通过） | `GameCoreBridge.cpp` + `GameCoreBridge.kt` | 验证缺口 | 完成（计划 v2 阶段 4） |
| ~~S-09~~ ✅ | **对拍测试隔离缺口已修复**：JUnit 对拍测试中 C++ `nativeCoreInit` 幂等复用引擎单例（阶段 1 既有设计），`EngineLoop.tickCount/speed/累积` 跨用例残留，与 Kotlin 侧每用例 `new GameTimeClock` 的干净基准不对称——首轮 DiffEngineLoopTest 8/15 失败（tickTotal 残留 65、speed 残留致 catch-up cap 3→6 等）。根因修复：`EngineLoop::resetForTest()`（tick 计数/速度/累积/帧状态/活跃基准全清，生产路径不调用——与 Kotlin 单例语义一致）+ 桌面对拍桥 `nativeCoreLoopReset` + GTest 2 用例守护；另修测试自身 2 处（死区消费缺暂停帧刷新帧基准、2x 断言算术错） | `engine_loop.h` + `GameCoreJni.cpp` + `DiffEngineLoopTest.kt` | 测试基建缺口 | 完成（计划 v2 阶段 5） |
