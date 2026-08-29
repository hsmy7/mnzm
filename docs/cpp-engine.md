# C++ 游戏引擎（game-core）架构文档

> 更新日期：2026-08-29。Kotlin→C++ 迁移——已完成批次归档，本文档仅保留**未完成项**详细规划。
> 总方案见 `docs/adr/cpp-engine-migration.md`。
> 当前基线：**桌面 GTest 558/558（本机桌面工具链实跑；批 10-0 起 GTest 纳入本地验证门，CMake gtest_discover 需 llvm-mingw bin 在 PATH） · engine JUnit 2925/2925（testReleaseUnitTest 全量 + 桌面 JNI 对拍全执行 0 skip——本机已具备桌面工具链，`-Dgamecore.jni.path` 注入后原 194 个 Assume 跳过用例全部实跑） · app compileReleaseKotlin 通过 · detekt 全模块全绿（含首次纳入验证门的 `:feature:game:detekt`） · NDK externalNativeBuildRelease 通过（本批零 C++ 变更）**。
> **计划 v2 阶段 0~7 已完成**（阶段 2：批量结算下沉 + tick 真相源切换 AUTHORITATIVE
> 过渡管线；阶段 3：反向增量通道 + DiscipleStore SoA 实体存储 + 静态数据单一源；阶段 4：
> 未迁移系统逐批 C++ 化——LevelGenerator/死亡物化/SecretRealm 状态机核心/外交决策/
> 11 槽分配/兑换码+邮件附件；阶段 5：游戏循环入 C++——平台能力接口化（Clock/Telemetry/
> 热控/电量端口）+ 引擎循环（PhaseClock/EngineLoop）+ 看门狗判据（ProgressMonitor）；
> 阶段 6：渲染 RHI + 合成器统一——道路合成器单一权威物理下沉（批次 R 剩余清零）+
> Renderer2D → Rhi.h 形式化（Metal/iOS 预留）；阶段 7：AUTHORITATIVE 生产默认切换
> （C++ 真相源验收）+ 存档编码决策（T-CPP-1 保持 Kotlin）+ engine 平台能力接口化
> 收尾（Android import 36→11），详见 §7 阶段 7 行；**Kotlin 引擎逻辑全量退役随 C-06
> 续作（阶段 7 批 7-4 登记）**）。**计划 v2 批 8（C-06 续作）**：批 8-1 完成
> ThermalMonitor/FrameMetricsMonitor 平台能力接口化（engine `import android.*` 11→0）；
> 批 8-2 完成首个生产接线家族（库存 add/remove 7 动作 AUTHORITATIVE 路由 + 溢出邮件
> 草稿回传通道 + 行为审计登记）；批 8-3 完成库存家族收尾（consolidate/sort/toggleLock
> 新增 3 ActionId C++ 化 + 接线，该家族 10 动作全量接线）；**批 8-4 完成接线面收口判定**
> （87 动作全量清点六类裁决 + 钱包族行为审计——可接线面已穷尽，见 §7.1 批 8-4 行）；
> **退役专项批 9-1/9-2 完成**（SHADOW 对拍态 + 纯 Kotlin 旬结算路径删除——tick 结算
> 恒走 native 单引擎终态；对拍框架转长期回归基线，见 §7.2）；**月变残留执行器增量
> C++ 化批 10-1 完成**（S8 侦察过期清理下沉 + 宗门详情域协议扩容，见 §7.3）。

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
| 剩余·GameEngine 方法转发 | ~~Kotlin GameEngine 275 方法逐一转发~~ **转发接线面已收口（✅ 批 8-4 判定）**：87 个 ActionId 全量清点六类裁决（已接线 10 / 月结年结旬结内部路径 16 / 纯函数·影子对拍基准 46 / 查询留守 4 / 事务内变更原语留守 3 / 无独立生产调用点·嵌套调用面留守 8）——可接线面已穷尽，判定与证据见 §7.1 批 8-4 行；GameEngine 族其余 ~200 非 ActionId 操作（UI 编排/协调逻辑）终态属 Kotlin 输入桥，不迁移 |
| 剩余·全量切换 | ~~增量变更集~~（✅ 阶段 1 完成）；~~逐系统切换~~（✅ 阶段 2-6 完成）；~~AUTHORITATIVE 生产默认~~（✅ 阶段 7 批 7-1，OFF 保留为回退契约）；~~GameEngine 方法全量转发接线~~（✅ 批 8-4 判定收口——可接线面已穷尽，见 §7.1）；~~SHADOW 对拍模式 + 纯 Kotlin 旬结算路径~~（✅ 退役专项批 9-1/9-2 删除，见 §7.2）；**退役后常态**：逐动作转发降级契约保留（native 不可用回退 Kotlin 原实现）、Diff 对拍框架转长期回归基线（桌面对拍桥全量实跑）、Wallet/Inventory-未接线动作按批 8-4 判定留守（双实现为其降级契约本体） |
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
  - **批 5-5 R-02 循环路径清除**：`GameTimeClock.kt` 删 `SystemClock/Log` import（SystemTimeSource/TimeSourceModule 移 app 层 `di/PlatformTimeModule.kt`）；`GameEngineCore.kt` 删 `Build` import（doBusyWait SDK_INT≥33 改 supportsOnSpinWait 反射探测）；engine 模块 Android import 的接口化收尾由阶段 7 批 7-2 完成（36→11 处，见 §7 阶段 7 行） | R-02（core/engine Android 依赖随引擎退役自然消除——循环路径 3 处已清除；阶段 7 接口化 36→11，剩余 11 处随 C-06 退役批次移出） |
| 6 ✅ | **渲染 RHI + 合成器统一**（2026-08-28 完成，GTest 550/550 + JUnit 对拍全绿 + NDK 构建通过）：
  - **批 6-1 合成器单一权威**：`gamecore/map/road_compositor.h`（零依赖纯函数 `emitRoadDrawOps`：掩码 → RoadSprite 语义枚举 + 格内整型几何操作序列，顺序契约 主体→描边条→转角件→十字中心 与双端烘焙顺序一致；与生成式图集解耦——合成器零 UV/精灵名依赖，枚举序 = ROAD_RECTS 声明序 = roadUVMap 索引 = SPRITE_KEYS 下标三端映射锚点）；GTest 12（全 16 掩码操作数守恒/几何有界/顺序契约/枚举序锚点/tileSize=32 整型↔浮点一致性）
  - **批 6-2 Vulkan 路段接入**：`NativeBridge.drawAllTiles` 道路段删除 roadTypeForMask/UV 硬编码，改消费合成器操作序列（仅做 操作→SpriteBatcher 数据装配）；新增 roadUVMap 长度防御；删本地 roadTypeForMask 包装（road_system.h 直引）
  - **批 6-3 Canvas 接入**：`SoftwareCanvasBackend.drawRoadsToCanvas` 改纯数据装配（逐格 `RoadCompositorBridge.compose` JNI 通道 → RoadSprite 枚举序→精灵名→图集源矩形，RoadTiling 合成逻辑移除）；生产 JNI `GameCoreBridge.nativeRoadCompose`（无状态纯函数，不依赖引擎实例）；`RoadCompositorBridge`（core/render）+ 守护测试（SpriteAtlasDefGeneratedTest：SPRITE_KEYS ↔ ROAD_RECTS 声明序全等）+ JUnit DiffRoadComposeTest 5（桌面对拍桥 compose op，手算规格 + 全 16 掩码结构不变量）；降级契约：compose 首次调用幂等 ensureLoaded 自加载，仅加载失败（JVM 测试环境/极端损坏）返回 null 跳过道路层（生产不触达，不影响 chunk 其余层）；同批产品回退：恢复建造栏石板路建造入口（回退 8c9b7734，独立提交）
  - **批 6-4 RHI 形式化**：`Renderer2D.h` → `Rhi.h`（RHI 契约：上层 NativeBridge/SpriteBatcher 不得 include 图形 API 头，下层实现 VulkanBackend 现有 / MetalBackend iOS 预留；类名 Renderer2D 保留）；Metal 接入指南（CAMetalLayer/NDC 差异/uploadTexture/submitFrame 语义映射，见 Rhi.h 头注释）——iOS 立项时零上层改动接入 | 批次 R 剩余（✅ 全部完成）、iOS 预留 |
| 7 ✅ | **Kotlin 降级纯平台层 + 存档决策**（2026-08-28 完成，分批 7-1~7-3；**剩余项批 7-4 依赖 C-06 转发收尾，见下注**）：
  - **批 7-1 AUTHORITATIVE 生产默认切换**：`NativeEngineFlag` 默认 OFF→**AUTHORITATIVE**（C++ 真相源切换验收——每旬时间推进+核心结算标量通道、委托式 RNG、Kotlin 残留执行器互插；SHADOW 对拍与 OFF 回退契约保留，任一时刻可切回纯 Kotlin）；随批修复降级契约缺口：`ensureAuthoritativeNative` 原只捕 `Exception`，而 `System.loadLibrary` 失败抛 `UnsatisfiedLinkError`（Error）——OFF 灰度期从未触达该路径，生产同理存在（split APK 损坏/16KB 对齐失败），改捕 `Throwable`（CancellationException 穿透）后 native 不可用严格回退纯 Kotlin；JUnit 4 失败复验全绿
  - **批 7-2 R-02 收尾（engine 平台能力接口化，`import android.*` 36→11 处）**：① `AndroidThermalReader` 物理移 app 层 `platform/`（ThermalReader 接口既有，零 engine 消费者）；② `BatteryAwareController` 拆分——接口 `BatteryStatusProvider`+`BatteryPolicy` 常量+`evaluatePowerPolicy` 纯函数+`NoopBatteryStatus` 留 engine，Android 广播/binder 读取移 app `platform/BatteryAwareController`，测试同步拆分（engine 纯策略 12 用例 + app Robolectric 平台回退 2 用例）；③ `GpuTierDetector` 移 feature/game `ui/game/perf/`（唯一消费域；`GpuTier`/`GpuRenderConfig` 留 engine 供 RenderScalePolicy）；④ `OemPowerProfileProvider` 厂商识别改平台串注入 `injectPlatformManufacturer`（app Application.onCreate 注入 Build.MANUFACTURER/BRAND；未注入按 OTHER 安全回退）；⑤ `android.util.Log`→`DomainLog`（HttpRemoteConfigProvider/SaveLoadCoordinator/DisciplePillManager/BuildingConfigService）；⑥ `android.util.Base64`→`kotlin.io.encoding.Base64`（ManualDatabase，minSdk 24 兼容）；⑦ **资产链端口** `core/platform/AssetSource`（BuildingConfigService/ManualDatabase/ManualRegistry/GameDataManager/ResourcePreloader 全链改造，app `AndroidAssetSource` + CoreModule 绑定；缺失返回 null 由调用方回退，日志语义保留）；⑧ **签名校验端口** `core/platform/ApkSigningCertificateSource`（RedeemCodeService 防篡改校验，证书提取移 app `AndroidApkSigningCertificateSource`，SHA-256 摘要比对留 engine 跨平台一致）
  - **批 7-3 存档决策（T-CPP-1 正式定案）**：**保持 Kotlin kotlinx-proto 存档编码**（Room 34 表 + .sav + 云存档链路零改动；kotlinx-proto 2174 字段号 schema 的 C++ 直出无当前消费者）——偿还触发不变：iOS 立项且需无 Kotlin 纯 C++ 存档时（ADR 技术债表登记）
  - **批 7-4 R-14 全量清偿 + 剩余项登记**：验证门补 `:feature:game:detekt`，10 项存量 + 接口化迁移暴露项全部真修（提取拆函数/折行/RenderFrame 收参/手势簇 467 行拆出 `MainGameScreenGestures.kt`——R-13 拆分专项首阶段；GpuTierDetector 分档规则链 + EglGpuProbe 拆分）；**剩余项（依赖 C-06，登记为阶段 7 续作）**：① `ThermalMonitor`（ADPF，4 处 import）+ `FrameMetricsMonitor`（7 处）接口化——深度耦合引擎循环/看门狗测试面（Bugly #3114 并发敏感守卫），随 C-06 退役批次专项重构；② **Kotlin 引擎逻辑全量退役**：GameEngine 族 ~289 方法中 84 动作协议已建未接线、其余 ~200 操作待 C++ 化（C-06 转发收尾），完成后 Kotlin 双实现删除 + shadow 对拍转回归基线 + 剩余 11 处 import 随迁 | T-CPP-1（定案保持）、C-07 验收（C++ 真相源切换完成；全量退役随 C-06） |

### 7.1 计划 v2 批 8（C-06 转发收尾续作，2026-08-29 启动）

> 范围：批 7-4 登记的剩余项——GameEngine 方法全量转发接线（84 已建未接线 + ~200 待 C++ 化）→
> Kotlin 双实现删除 + shadow 对拍转回归基线。按家族逐批接线，每批 = 逐动作行为审计（C++ handler ↔
> Kotlin 生产路径全语义比对）+ 接线 + 守护测试；**无 C++ 对应动作或行为不等价的一律不接线**（保持 Kotlin 回退）。

| 批 | 内容 | 验证 |
|---|---|---|
| 8-1 ✅ | **监控器平台能力接口化**（engine `import android.*` 11→0 处，R-02 收尾完成）：`core/perf/ThermalPorts.kt`（ThermalStatusReader + PerformanceHintPort 不透明句柄端口）；ThermalMonitor 重写——轮询/映射/线程绑定守卫（Bugly #3114）全留引擎，Android API 移 app `platform/AndroidThermalPorts.kt`（hintManager internal 接缝随端口化消失）；`core/perf/FrameMetricsSession.kt` + FrameMetricsMonitor 重写（卡顿判定/统计留引擎，Window/FrameMetrics 采集移 app `WindowFrameMetricsSession.kt`）；CoreModule 绑定 + GameActivity 调用点改造；ThermalMonitorTest 重写为 fake port 纯 JVM（守卫语义断言逐条对应，脱离 Robolectric） | compileReleaseKotlin + lintRelease + engine/app detekt + ThermalMonitorTest 全绿 |
| 8-2 ✅ | **库存 add/remove 家族生产接线**（首批 7 动作：INV_ADD_{EQUIPMENT_STACK,MANUAL_STACK,PILL,MATERIAL,HERB,SEED} + INV_REMOVE_EQUIPMENT）：逐动作行为审计（C++ `gamecore::system::inventory.h` ↔ Kotlin `InventorySystem` 全语义比对：合并/分块/槽位跨类型容量/溢出邮件/annual 追踪/锁语义逐项等价，登记缺口见下）；GameEngineInventoryOps 7 方法 AUTHORITATIVE 路由（`InventoryNativeForward.tryForward`：flag 三态守卫——SHADOW 保持 Kotlin 执行真相源；顶层失败/native 不可用回退 Kotlin；**data.status=partial 不回退**——C++ 状态已变更，回退会二次入仓复制物品）；**溢出邮件草稿回传通道**（批 8-2 前置缺口修复：C++ handleInventory 信封新增 overflowDrafts 数组 + OverflowDraft 扩展 grade/category/slot/type/growTime/yield 反查区分字段，Kotlin 侧重建最小模型走 `InventorySystem.resolveOverflowItemId` 同一解析路径 + sendOverflowMail 投递，精度与 Kotlin 原路径一致）；GTest 3 用例（partial/full/remover 无草稿）+ JUnit GameEngineInventoryForwardTest 5 用例（OFF/AUTHORITATIVE 回退契约） | NDK externalNativeBuildRelease 通过 + engine 全量 JUnit + detekt + ThermalMonitorTest 回归；GTest 3 新用例已随批 10-0 本地桌面构建实跑（✅） |
| 8-3 ✅ | **库存家族收尾 C++ 化 + 接线**（consolidateStacks/sortWarehouse/toggleItemLock，批 8-2 审计登记的"无 C++ 对应动作"三项）：gen-action-ids 新增 INV_CONSOLIDATE(1027)/INV_SORT(1028)/INV_TOGGLE_LOCK(1029)（87 动作）+ 双端产物重生成；`inventory.h` 新增 `consolidateItems`（Kotlin 2026-08-01 对抗性审查语义逐条移植：单遍合并/满堆叠跳过防振荡/锁定可作目标禁作来源/组间独立序无关）+ `sortStacks`（rarity desc name asc，stable_sort 对齐 sortedWith）+ `sortWarehouse`（含装备/功法实例轨道）+ `toggleItemLock`（6 类堆叠轨道，未知类型 no-op 对齐 when 无 else）；handleInventory 3 case + execute 路由范围扩至 1029；GameEngineInventoryOps 3 方法 AUTHORITATIVE 路由（同批 8-2 三态守卫契约）；GTest 3 用例（三同键堆叠合并锁语义/排序双键序/翻转+未知 id/类型）+ JUnit 回退守卫补 1 用例；**途中发现并修复转发层缺陷：`tryExecuteNative` 的 Kotlin 非空参数内在检查在函数入口（早于 isLoaded 早退）即抛 NPE——测试 mock 未 stub `stateSyncServiceRef` 时必触**（tryForward 先行空过滤，登记 S-12：转发辅助的 Kotlin 非空参数在 mock 场景的入口 NPE 语义） | NDK externalNativeBuildRelease + engine 全量 JUnit（BootSequence 12 用例回归确认）+ detekt 全绿；GTest 6 新用例已随批 10-0 本地桌面构建实跑（✅） |
| 8-4 ✅ | **接线面收口判定**（2026-08-29 完成，审计/判定批——无生产代码变更，判定依据为全仓库调用点核查）：87 个 ActionId 全量清点**六类裁决**（详表见下"批 8-4 接线面收口判定"节）：① 已接线生产 10（批 8-2/8-3）；② 月结/年结/旬结内部路径 16——AUTHORITATIVE tick 已在 C++ 侧执行，无独立 Kotlin 生产调用点（不接线=已接线）；③ 纯函数/影子对拍基准 46——Kotlin 消费方为系统内部计算（战斗执行/秘宝模板/注册表/服务器校验按批 4-3/4-6 边界保留 Kotlin），随双实现退役自然消失；④ 查询动作留守 4（WALLET_BALANCE/TOTAL_SELL_VALUE、INV_CAN_ADD_ITEM/CAPACITY_INFO——事务内消费 + C++ 只读通道在 tick 间落后 Kotlin，直读精确且零成本）；⑤ **事务内变更原语留守 3（钱包族 WALLET_ADD/DEDUCT/BATCH，行为审计完成）**：C++ `economy.h` 为 Kotlin `SpiritStoneWallet` 纯逻辑忠实移植（add 饱和回绕/deduct 自动售卖补差价/batch 预检查原子回滚/年度报告累积逐项等价），判定留守的依据：a) 钱包是 `stateStore.update` 事务内被组合调用的原语（~数十调用点），`tryExecuteNative` 的 `syncFromNative` store 级镜像在闭包内调用会被外层事务提交覆盖（镜像丢失→反向同步把变更冲回）；b) 可观察契约含 Kotlin 独有平台效应（SpiritStoneLedger 流水 + pendingEvents 事件暂存/flush + DomainLog），转发需在转发层第三次复刻该逻辑，劣于现状双实现 + 逐旬对拍；c) JSON execute 往返 1.1× 慢于 Kotlin 真实实现，无性能收益；C++ 侧钱包收敛由反向增量通道保证（gameData 全量同步，每旬 tick 步骤 ⑤）；⑥ 无独立生产调用点/嵌套调用面留守 8（INV_REMOVE_{MANUAL,PILL,MATERIAL,HERB,SEED} 仅被 InventorySystem 内部 sell*/consume 组合操作消费；INV_ADD_{EQUIPMENT,MANUAL}_INSTANCE 的 ItemAdder 接口无外部生产调用点；INV_ADD_STORAGE_BAG 调用面嵌套形态混杂——引导奖励外层 update 闭包/宗门升级批量发放循环/邮件附件分发，无单一自有事务编排入口）。**结论：可接线面已穷尽，C-06 转发接线阶段终结**；剩余终态收尾 = **Kotlin 引擎退役专项**（删除 tick/结算双实现 + shadow 对拍转回归基线，另行批次规划） | 审计批无测试面变更；判定证据：全仓库 ActionIds 引用核查（生产接线仅库存 10 动作）+ execute_dispatch.cpp 87 case 全覆盖核对 + 钱包/库存调用点形态核查 |

**批 8-4 接线面收口判定（2026-08-29 完成，87 动作全量清点六类裁决）**："84 动作已建未接线"的实际生产接线面小于字面量——
- SPIRIT_FIELD_HARVEST / WORLD_LEVEL_* / LEVEL_* / DISCIPLE_MARK_DEAD+BACKFILL 等是**月结/年结内部路径**，阶段 2 AUTHORITATIVE tick 已在 C++ 侧执行，无独立 Kotlin 生产调用点（不接线 = 已接线）；
- SECRET_REALM_*（14）/ SECT_*（13）/ BATTLE_*（7）/ DISCIPLE_BASE_STATS 等纯函数/查询动作的 Kotlin 消费方是系统内部计算（阶段 4 已 C++ 化系统逻辑，Kotlin 侧为影子对拍基准），随双实现退役自然消失，无需单独接线；战斗执行（BattleSystem）/秘宝模板实例化/注册表与服务器验证按批 4-3/4-6 边界保留 Kotlin；
- 真正需要接线的表面 = **有玩家/系统发起的独立生产调用点的编排操作**（如批 8-2 库存 add/remove）+ ~200 个未 C++ 化操作——后者多数是 UI 编排/协调逻辑（终态属 Kotlin 输入桥），逐批判定"转发 vs 留守"而非全量 C++ 化。

六类裁决汇总（合计 87）：

| 类别 | 数量 | 动作 | 判定 |
|---|---|---|---|
| ① 已接线生产 | 10 | INV_ADD_{EQUIPMENT_STACK,MANUAL_STACK,PILL,MATERIAL,HERB,SEED} + INV_REMOVE_EQUIPMENT + INV_{CONSOLIDATE,SORT,TOGGLE_LOCK} | ✅ 批 8-2/8-3 AUTHORITATIVE 路由 |
| ② 月结/年结/旬结内部路径 | 16 | SPIRIT_FIELD_HARVEST、WORLD_LEVEL_MONTHLY/CHECK_EXPIRED、LEVEL_SELECT_BEAST_REALM/GENERATE_LEVELS、DISCIPLE_MARK_DEAD/BACKFILL_DEATH_YEARS、DISCIPLE_{CULTIVATION_PER_PHASE,CHECKPOINT,ACCUMULATE_CULTIVATION,AGE,BREAKTHROUGH}、GOV_{POLICY_COSTS,POLICY_MONTHLY_EFFECTS,SPIRIT_MINE_MONTHLY,ANNUAL_SALARY} | 不接线 = 已接线（AUTHORITATIVE tick C++ 侧执行，ActionId 仅为协议占位/对拍入口） |
| ③ 纯函数/影子对拍基准 | 46 | DISCIPLE_{BASE_STATS,BREAKTHROUGH_CHANCE,MAX_AGE,ESTIMATE_BREAKTHROUGH_MONTH}、BATTLE_FINAL_DAMAGE~COOLDOWN_UPDATE（7）、GOV_ZONE_CALCULATE、SECRET_REALM_*（14）、SECT_*（13）、SLOT_CLEAR_ALL、REDEEM_*（5）、MAIL_ATTACHMENT_ENCODE | 无需接线（Kotlin 消费方为系统内部计算/对拍基准，随双实现退役自然消失） |
| ④ 查询动作留守 | 4 | WALLET_{BALANCE,TOTAL_SELL_VALUE}、INV_{CAN_ADD_ITEM,CAPACITY_INFO} | 留守：事务内消费（DiplomacyService/AutoBuyService 在 update 闭包内 canAddItemInTransaction）+ C++ 只读通道在 tick 间落后 Kotlin（反向同步每旬一次），直读精确且零成本 |
| ⑤ 事务内变更原语留守（钱包族） | 3 | WALLET_{ADD,DEDUCT,BATCH} | 留守（行为审计完成，等价性确认；依据见批 8-4 行 a/b/c 三条——镜像机制不兼容事务内调用/平台效应不可产出/无性能收益；C++ 收敛由反向增量通道保证） |
| ⑥ 无独立生产调用点/嵌套调用面留守 | 8 | INV_REMOVE_{MANUAL,PILL,MATERIAL,HERB,SEED}、INV_ADD_{EQUIPMENT,MANUAL}_INSTANCE、INV_ADD_STORAGE_BAG | 留守：remove 族仅被 InventorySystem 内部 sell*/consume 组合操作消费；instance 族 ItemAdder 接口无外部调用点；storageBag 调用面嵌套形态混杂，无单一自有事务编排入口 |

**批 8-2 行为审计登记缺口**（不阻塞接线，登记偿还）：
- **S-10**：C++ 库存容量常量硬编码（`kWarehouseBaseCapacity=50`/`kWarehouseCapacityPerBuilding=75`，inventory.h），Kotlin 读 `gameConfigProvider.warehouse.*`——config 改动时双端漂移（偿还：配置对象注入 C++ 或 codegen 常量单源）
- **S-11**：C++ `validateStackableItem` 用 `name.empty()`，Kotlin `isBlank()` 拒绝纯空白名——空白名行为差异（低风险）
- ~~未接线（无 C++ 对应动作）：sortWarehouse/consolidateStacks/toggleItemLock~~（✅ 批 8-3 已 C++ 化接线）；consumeMaterialByName（多堆叠跨栈消耗）/sell*/merchant 交易族——保持 Kotlin（批 8-4 判定归入类别 ⑥：组合操作内部路径）
- INV_ADD_EQUIPMENT_INSTANCE(1011)/INV_ADD_MANUAL_INSTANCE(1013) 声明无 handler（顶层 UNKNOWN_ACTION → 天然回退 Kotlin，正确性无损）——批 8-4 判定确认无生产调用点，无需补 handler

**保持不动（与迁移方向无关）**：R-01/03~14（detekt/lint/测试质量债务；R-14 = feature:game detekt 存量 10 项 + 验证门缺口，随阶段 7 Kotlin 面收窄与 MainGameScreen/Canvas 拆分专项处置）、T-D46~D49/T-D40/T-A2/T-RB/T-CONV/T-PRO（平台/发行技术债）、P 系列真机验证、扩展性预留（RemoteConfig/商业化/离线收益——离线收益结算接入点在阶段 4 后自动走 C++）。

### 7.2 退役专项（Kotlin 引擎退役，2026-08-29 启动；批 8-4 接线面收口后启动）

> 终态依据：选项 A 彻底单引擎（ADR 二次修订）——C++ 单一真相源，双实现并行的镜像/对拍开销为纯浪费。
> 退役边界 = **tick/结算层的 Kotlin 并行实现**；逐动作转发降级契约（native 不可用回退 Kotlin 原实现）
> 是产品降级能力的本体，随单引擎长期保留。

| 批 | 内容 | 验证 |
|---|---|---|
| 9-1 ✅ | **SHADOW 对拍模式退役**：`NativeEngineFlag` 三态→双态（OFF/AUTHORITATIVE，SHADOW 枚举删除）；`tickNativeShadow` 影子推进桥删除（GameEngineCoreNativeOps 重写为读档基线对齐单职责）；tickInternal 影子推进调用点删除；DiffNativeForwardTest SHADOW 用例改 AUTHORITATIVE（语义等价：非 OFF 态 + 生产桥未加载降级）；跨语言语义守护由 Diff 对拍测试以回归基线形态继续承担 | compileReleaseKotlin + detekt |
| 10-0 ✅ | **GTest 本地桌面验证门建立**（2026-08-29）：本机桌面工具链（llvm-mingw + SDK cmake/ninja）实跑 GTest 全量 556/556——批 8-2/8-3 登记的 6 个新用例验证缺口关闭；**途中修复 1 个测试场景错误**：`InvOverflowPartialEmitsDrafts` 原"先填 999 满堆叠再入 5"预期 partial——实际双端 StackableItemStore 契约一致（同键堆叠已满且有空槽 → 分块创建新堆叠返回 Success 非溢出；Partial = 发生合并 + 槽位全满），场景改为 49 填充 + 木剑×998 占满 50 槽再入 5 → 合并 1 溢出 4；实现零变更（C++/Kotlin 逐位等价复核） | cmake --build + game-core-tests.exe（llvm-mingw bin 入 PATH） |
| 9-2 ✅ | **纯 Kotlin 旬结算路径删除（tick 层双实现退役）**：`processTickPhases`（OFF 路径多旬合并事务：TimeSystem.onPhaseTick 时间推进 + checkBreakthroughsAndPills 六步结算）删除；`checkBreakthroughsAndPills` 生产入口删除（PhaseSettlementExecutor 完整版 execute 保留为对拍基准）；tickInternal 分支改造——**tick 结算恒走 native**（不再检查 flag：单引擎终态 OFF 不影响 tick），native 未就绪（.so 加载/初始化失败）时本旬跳过结算 + refundPhases 归还未落地旬数（时间不丢），持续不可用由看门狗停滞判据 → 紧急重启自愈（重启重建 native 链路）；私有 FLAG_MONTH/YEAR_CHANGED 常量删除；KDoc 定位更新（NativeEngineFlag OFF 语义收窄为逐动作/循环集成降级；TimeSystem.onPhaseTick 保留为对拍时间驱动器；GameTimeClock.refundPhases 注释随行）；**语义决策**：引擎级"切回纯 Kotlin"回退契约随退役消灭（选项 A 的必然结果），逐动作降级与循环/看门狗 Kotlin 集成路径保留 | **engine JUnit 全量 2925/2925（桌面 JNI 对拍全执行 0 skip）+ engine detekt + app compileReleaseKotlin 全绿，零回归** |

**退役专项剩余（登记）**：
- 对拍框架长期化：Diff *Test 全套以桌面对拍桥（`-Dgamecore.jni.path`）作为 C++ 回归基线持续运行（CI 桌面 job + 本地 build-desktop-jni.ps1）；Kotlin 臂（残留执行器 + TimeSystem.onPhaseTick 时间驱动）即回归基准，不再承担"迁移验收"职责
- Wallet/库存未接线动作双实现按批 8-4 判定留守——该双实现即逐动作降级契约本体，不退役
- 月/年残留执行器（MonthSettlementExecutor/YearSettlementExecutor/PhaseSettlementExecutor.executeResidual）为 AUTHORITATIVE 生产实现的 Kotlin 侧组成（未 C++ 化扇出见 month_settlement.h 范围边界），其 C++ 化属后续增量迁移批次（非退役范畴）

### 7.3 月变/年变残留执行器增量 C++ 化（2026-08-29 启动；非退役范畴）

> 退役专项（§7.2）收口后的主线：把 AUTHORITATIVE 生产管线中仍由 Kotlin 残留执行器
> 承担的月变/年变编排逐批下沉 C++（月变八步中未下沉扇出 + S8 十六子事件余量，
> 见 month_settlement.h 文件头范围边界）。每批 = Kotlin 源码逐条审计 → C++ 等价
> 移植（含协议扩容）→ GTest 黄金序列 + JUnit Diff 对拍守护。真相源切换（月变编排
> 整体走 C++）待下沉面收敛后单独立批。

| 批 | 内容 | 验证 |
|---|---|---|
| 10-1 ✅ | **S8 子事件 8：侦察信息过期清理**（Kotlin `CultivationEventDiplomacyOps.applyScoutInfoExpiry` 等价移植；零 RNG 纯数据变换）：**协议扩容**——宗门详情域 6 模型入 C++ 快照（SectDetail/SectScoutInfo/MineSlot/SectWarehouse/WarehouseItem + GiftPreferenceType 枚举按 name-string 约定，GameData 新增 `sectDetails`/`scoutInfo` 两 map 字段，json_codec 双向编解码；dirty_tracker 对 gameData 顶层字段为通用 diff，新字段自动覆盖）；C++ `detail::applyScoutInfoExpiry`（过期判定/无过期纯早退/三段更新逐条对齐 Kotlin 读取顺序——剩余条目明细刷新+新建、被移除明细 scoutInfo 清空保留其余字段、worldMapSects.isKnown 翻转读原始明细）；接线进 runMonthSettlement 子事件 8 位（gameOverCheck 与 spiritMine 之间，相对序对齐 Kotlin） | GTest +2（过期移除+isKnown 翻转+明细保留/新建刷新；无过期零写入）558/558 · DiffMonthSettlementTest 场景扩展（AI 宗门×2：过期/未过期+明细保留/新建+嵌套 map 资源/弟子键）1/1 · engine JUnit 2925/2925（0 skip）· NDK externalNativeBuildRelease 通过 · detekt 绿。**途中修复协议默认值缺陷：giftPreference C++ 默认空串→"NONE"**（Kotlin 枚举默认名，空串不可解码） |

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
| S-10 | **C++ 库存容量常量硬编码**：`kWarehouseBaseCapacity=50`/`kWarehouseCapacityPerBuilding=75`（`gamecore/include/gamecore/system/inventory.h`），Kotlin 读 `gameConfigProvider.warehouse.*`——config 改动时双端漂移 | `inventory.h` | 配置单源缺口 | 偿还时机：库存配置进 C++（config 注入或 codegen）时 |
| S-11 | **空白名校验差异**：C++ `validateStackableItem` 用 `name.empty()`，Kotlin `isBlank()` 拒绝纯空白名 | `inventory.h` | 语义差异（低风险） | 偿还时机：随批 8 库存家族复审 |
| S-12 | **转发辅助入口 NPE 语义**：`GameEngineNativeOps.tryExecuteNative` 的 Kotlin 非空参数 `stateSyncService` 的内在 null 检查在函数入口即触发（早于 flag/isLoaded 早退）——生产恒非空无影响，测试 mock（未 stub `stateSyncServiceRef`）返回 null 必触；InventoryNativeForward.tryForward 已先行空过滤 | `GameEngineNativeOps.kt` | 降级契约缺口（测试场景） | 偿还时机：tryExecuteNative 参数改可空 + 内部守卫（随后续接线批次顺带） |
