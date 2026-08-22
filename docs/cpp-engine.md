# C++ 游戏引擎（game-core）架构文档

> 更新日期：2026-08-16。Kotlin→C++ 迁移（批次 0：基础设施）。
> 总方案见 `docs/adr/cpp-engine-migration.md`（计划批准后补充 ADR 细节）。

## 1. 目标架构

```
Compose UI (feature/game + app) —— 保留，零改动
  ↓
ViewModel / UseCase —— 保留，零改动
  ↓
GameEngine 族 (~275 方法) + 10 Facade (117 方法) —— Kotlin 保留签名，方法体转发
  ↓  参数/结果 JSON 编解码 (kotlinx.serialization ↔ nlohmann/json)
JNI 桥 GameCoreBridge (通用入口：init/advance/execute/export/import/poll + Clock 注入)
  ↓
★ C++ GameCore（纯 C++20、零 Android 依赖、桌面可编译、iOS 可复用）—— 新真相源
  ↓  版本号 + 变更集（增量）或全量快照（存档/读档）
Kotlin StateSyncService → GameStateStore（镜像写入，接口/StateFlow 不变）
  ↓
Room 34 表 + .sav/云存档 —— 保留，链路零改动
```

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
│   │   ├── state/   （批次 1：模型 + 快照编解码）
│   │   ├── system/  （批次 3+：各游戏系统）
│   │   ├── game_core.h         # 引擎门面（execute/advance/export/import/poll）
│   │   └── action_ids.h        # 生成产物（scripts/gen-action-ids.mjs）
│   ├── src/  rng.cpp / game_core.cpp
│   ├── jni/  GameCoreJni.cpp   # 桌面 JNI 对拍桥（无 Android 依赖，测试用）
│   ├── test/  rng_test / result_test / game_core_test（GTest）
│   └── third_party/nlohmann/json.hpp   # vendored 单头 JSON
├── VulkanBackend.cpp ...       # 既有渲染层（不动）
```

## 3. 批次 0 已交付

| 项 | 说明 |
|---|---|
| game-core 静态库 | C++20，零 Android 依赖，桌面/CI 可编译（`cmake -B build -DGAMECORE_BUILD_TESTS=ON`） |
| 基础接口 | `Result<T>`（三态，对应 DomainResult）、`Clock`（现实时间注入）、`Logger`（跨平台日志） |
| RNG 复刻 | PCG-XSH-RR 64→32（先截断 32 位再旋转的修复后语义）；`RngManager` 8 分区（BATTLE=0…SECRET_REALM=7）与 Kotlin `RngPartition` id 对齐；黄金序列 GTest 守护 |
| JNI 桥 | Android `GameCoreBridge.cpp`（库名 native-game-core）+ Kotlin `GameCoreBridge.kt`；通用入口设计 |
| ActionId 协议 | `scripts/gen-action-ids.mjs` 单一清单 → `action_ids.h` + `ActionIds.kt` 双产物（提交 git 防漂移） |
| 桌面对拍桥 | `gamecore/jni/GameCoreJni.cpp` + `DiffRngBridge.kt` + `DiffRngTest.kt`（JUnit 跨语言 RNG 对拍；`-Dgamecore.jni.path` 注入） |
| CI | `ci.yml` 新增 `cpp-engine-test` job（Linux g++/CMake/GTest 独立跑） |
| 构建脚本 | `scripts/build-desktop-jni.ps1`（本地 llvm-mingw 构建对拍库） |

## 4. 确定性保真要求（迁移全程）

1. **RNG**：PCG-XSH-RR 复刻；`shuffled(rng)` 用 `std::stable_sort`（Kotlin sortedBy 稳定）
2. **数学语义**：IEEE754 double、Int/Long 溢出回绕、截断除法、coerceIn/coerceAtMost
3. **顺序稳定性**：禁止 `unordered_map` 参与业务迭代（用 map/vector + 显式排序）
4. **现实时间**：一律 `Clock` 注入（对拍用 FixedClock）；引擎内禁直接系统时间
5. **对拍守护**：C++ GTest 黄金序列 + JUnit 跨语言对拍（DiffRngTest）+ 后续存档级对拍

## 5. 批次进度

### 批次 0（已完成）：基础设施

| 项 | 说明 |
|---|---|
| game-core 静态库 | C++20，零 Android 依赖，桌面/CI 可编译（`cmake -B build -DGAMECORE_BUILD_TESTS=ON`） |
| 基础接口 | `Result<T>`（三态，对应 DomainResult）、`Clock`（现实时间注入）、`Logger`（跨平台日志） |
| RNG 复刻 | PCG-XSH-RR 64→32（先截断 32 位再旋转的修复后语义）；`RngManager` 8 分区与 Kotlin `RngPartition` id 对齐；黄金序列 GTest + JUnit 跨语言对拍双守护 |
| JNI 桥 | Android `GameCoreBridge.cpp`（库名 native-game-core）+ Kotlin `GameCoreBridge.kt`；通用入口设计 |
| ActionId 协议 | `scripts/gen-action-ids.mjs` 单一清单 → `action_ids.h` + `ActionIds.kt` 双产物（提交 git 防漂移） |
| 桌面对拍桥 | `gamecore/jni/GameCoreJni.cpp` + `DiffRngBridge.kt` + `DiffRngTest.kt`（JUnit 跨语言 RNG 对拍；`-Dgamecore.jni.path` 注入） |
| CI | `ci.yml` 新增 `cpp-engine-test` job（Linux g++/CMake/GTest 独立跑） |

### 批次 1（已完成）：状态模型 + JSON 快照

| 项 | 说明 |
|---|---|
| C++ 状态模型 | `state/models.h`：GameData 全部标量/简单集合字段（~90 个）+ **嵌套对象类型 18 个**（SectPolicies 40 字段 / ElderSlots+DirectDiscipleSlot / ProductionSlot / GridBuildingData / MerchantItem / Alliance / VassalContract / SectRelation / WorldSect / ResidenceSlot / SpiritFieldPlant / PatrolConfig / WorldLevel / MailClaimRecord / SectLevelClaimRecord / YearlyReport / PendingTraitAdd）；Disciple 与 8 类物品核心字段 |
| JSON 快照编解码 | `state/json_codec.h/.cpp`：nlohmann ↔ kotlinx 字段名一致；宽松 from_json；int-key Map 显式转换；可空字段 `std::optional`（含 NullableStringAsEmpty/IntAsZero 序列化器语义对齐）；浮点规范化（规避 kotlinx "N.0" 缺陷） |
| 对齐的 Kotlin 语义 | DiscipleSerializer 排除 slotId；lifeEvents/monthlyUsedPillIds 类体属性；ProductionSlot 的 slotId/requiredMaterials @Transient；@Transient 字段不进入快照协议（读档后由引擎重算） |
| GameCore 集成 | `GameCore::state()` 持有真相状态；`importStateJson/exportStateJson` 实现 |
| 快照 DTO | Kotlin `NativeGameState.kt`（gameData + 10 类实体列表） |
| 对拍测试 | `DiffStateTest`（JUnit 跨语言快照往返逐字段相等：gameData 90 标量 + 10 嵌套类型样本 / 弟子+全物品 / 空状态）+ C++ `json_codec_test`（38 测试全绿） |
| 验证 | 桌面 GTest 38/38；JUnit 对拍 10/10；NDK `externalNativeBuildRelease` 通过 |

> 批次 1 剩余（后续回合）：低频嵌套类型（秘境/血炼/探索队伍/功法精通/侦查信息等 ~20 个）与重型 @Transient 字段（aiSectDisciples 等）——随批次 3-8 子系统迁移配套补齐。

### 批次 2（第一子步已完成）：静态数据/注册表（装备表）

| 项 | 说明 |
|---|---|
| 模板提取生成器 | `scripts/gen-templates.mjs`：解析 Kotlin Registry Map 字面量（`"id" to EquipmentTemplate(...)` 模式）→ 生成 C++ 静态表 + 抽取快照 JSON；去重校验；产物提交 git 防漂移 |
| C++ 装备表 | `include/gamecore/data/equipment_db.h`：`EquipmentTemplate` 结构 + `equipmentTemplates()`（72 条，Kotlin EquipmentDatabase 同源；slot 为 EquipmentSlot.name） |
| 守卫测试（双端锚定） | Kotlin `TemplateRegistryGuardTest`（快照 ↔ EquipmentDatabase 实时数据，72 条逐字段比对）；C++ `equipment_db_test`（数量/代表性条目/去重，4 测试）——任一侧改动数据 → 测试失败提示重跑生成器 |
| 验证 | 桌面 GTest 43/43；JUnit nativebridge 11/11（含守卫）；NDK 构建通过 |

> 批次 2 剩余（后续回合）：丹药配方/功法/天赋/体质/词缀/材料/锻造配方/妖兽材料等 Registry 的提取器扩展（格式差异逐表适配）。

### 批次 3（已完成）：时间系统 + 惰性结算引擎

| 项 | 说明 |
|---|---|
| C++ 时间系统 | `system/time_system.h`：`advancePhase`（年/月/旬进位，等价 TimeSystem.onPhaseTick）、`totalPhases` 时间编码、月末/年末判定——纯函数零依赖 |
| C++ 结算引擎 | `system/settlement.h`：`SettlementEngine`——**对齐 GameTimeClock 语义**（对抗性审查 2026-08-22：msPerPhase=2000ms@1x × speed、单 tick 上限 3×speed 旬、超限丢弃余量）、月/年边界检测、`onPhaseSettle`/`onMonthChange`/`onYearChange` 结算钩子（批次 4-7 注册，登记 C-10） |
| GameCore 接入 | `advance(wallDeltaMs)` 走结算引擎；`advancePhases(n)` 直接推进（对拍/测试）；**读档复位结算引擎**（对抗性审查 A1 修复：importStateJson 后 reset 防累积残留多推进） |
| 对拍测试 | `DiffTimeTest`（JUnit 跨语言：Kotlin 复刻 TimeSystem.onPhaseTick vs C++ 推进，旬/月/年进位、1000 旬跨年、**非时间字段不变性**逐位一致）+ C++ `time_system_test`（15 测试：黄金场景/边界/accumulator 消费/speed 缩放/暂停/部分旬累积/超限丢弃/钩子） |
| 验证 | 桌面 GTest 58/58；JUnit nativebridge 16/16（DiffTime 5 + DiffState 3 + DiffRng 7 + 守卫 1）；NDK 构建通过 |

> 批次 3 验收达成：同档 1000 旬推进时间逐位一致 + 空档非时间状态不变（灵石/玉符/设置等）。
> 月变/年变结算钩子的系统实现依赖批次 4-7 子系统（登记 C-10），随各批次填充。

## 6. 后续批次（详见实施计划）

- 批次 1：状态模型（GameData/实体/组件表）+ JSON 快照导出/导入
- 批次 2：静态数据/注册表
- 批次 3：时间系统 + 惰性结算引擎
- 批次 4-8：经济/生产/弟子/战斗/内政/探索 子系统
- 批次 9：转发层 + 状态同步 + feature flag 切换
- 批次 10：Kotlin 引擎退役
