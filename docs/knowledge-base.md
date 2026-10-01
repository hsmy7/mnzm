# 项目知识库

> 本文档包含了项目的技术栈、关键类说明、核心子系统的设计参考。
> 架构设计详见 [architecture.md](architecture.md)，编码规范与任务路由表详见 [AGENTS.md](../AGENTS.md)。

---

## 目录

- [技术栈](#tech-stack)
- [关键类说明](#key-classes)
- [弟子分配门卫系统](#弟子分配门卫系统)
- [存档槽位隔离](#存档槽位隔离)
- [探索系统](#探索系统)
- [装备系统（六部位套装体系）](#装备系统六部位套装体系)
- [确定性 RNG 系统](#确定性-rng-系统)
- [弟子属性生成](#弟子属性生成)
- [Component Table 架构](#component-table-architecture)
- [修炼 Checkpoint 模式](#修炼-checkpoint)
- [EntityStore 增量更新](#entitystore-增量更新)
- [生产系统 Checkpoint](#生产系统-checkpoint)
- [SaveValidator 规则引擎](#savevalidator-规则引擎)
- [热控与温度读取](#热控与温度读取)
- [Hot Path 规则](#hot-path-rules)
- [Mail & Reward System](#mail--reward-system)
- [Navigation Pattern](#navigation-pattern)
- [Android SDK / Encoding](#android-sdk--encoding)
- [免广告特权白名单](#免广告特权白名单)
- [扩展性现状盘点（2026-08-04）](#扩展性现状盘点)

---

## 引擎架构组件（2026-08-13 对标 Godot 借鉴重构新增）

| 组件 | 位置 | 职责 |
|------|------|------|
| `SectAtlasAssembler` | `feature/game/.../sect/` | 宗门地图图集运行时组装（自 NativeSurfaceView companion 外移） |
| `CloudLayerAnimator` | `feature/game/.../sect/` | 世界顶部动态云朵动画引擎（渲染线程驱动：只在世界外生成/横向穿越/出界消失，3 格/秒，随机类型/方向/Y/缩放（0.4~0.8，2026-08 缩半）/透明度；逐帧快照 `host.cloudData` 双后端共享） |
| `SurfaceProvider` / `AndroidSurfaceProvider` | `core/engine/.../platform/` / `feature/game/.../sect/` | 渲染表面生命周期平台抽象（iOS 迁移点；防御逻辑：10s 超时/generation 防 stale/首帧黑屏） |
| `EngineTween` / `Timeline` / `EasingConstants` | `core/engine/.../animation/` / `core/domain/.../animation/` | 统一缓动库（TimeSource 驱动、帧率无关；CameraAnimator 已迁移；战斗动画经守卫评估不迁移） |
| `RenderCommandBus` 双通道 | `feature/game/.../sect/` | 帧数据覆盖槽 + RenderCommand 命令 FIFO（SPSC 无锁环形缓冲）；`RenderCommand`/`ResourceHandle` 契约在 `core/engine/.../core/render/` |
| `JitterSmoother` | `core/engine/.../loop/` | 插值因子 EWMA 平滑（对标 Godot physics_jitter_fix；仅渲染契约，确定性守卫锁定） |
| `GameSystemRegistry` / `GameSystemRegistryDefaults` | `core/engine/.../registry/` | @GameService 静态注册中心（39 系统；守卫测试锚） |
| 资源管线 codegen | `android/scripts/resource-manifest.mjs` + `build-atlas.mjs` | 扫描 drawable-nodpi → atlas-manifest.json + 三产物（SpriteRegistryData/SpriteAtlasDef/TextureAtlas.h）+ `android/scripts/sprite-uid-map.json` 持久 UID；生成物 build/generated 不入库 |

## C++ 引擎同步通道关键类（计划 v2 阶段 1-3，2026-08-26）

| 组件 | 位置 | 职责 |
|------|------|------|
| `GameCoreBridge` | `core/engine/.../nativebridge/`（Kotlin）+ `app/src/main/cpp/GameCoreBridge.cpp`（JNI） | 引擎 JNI 通用入口（init/advance/settleOnePhase/execute/export/import/exportDirty/applyReverseDirty/poll + RNG 标量通道）；桌面对拍镜像 `gamecore/jni/GameCoreJni.cpp` |
| `StateSyncService` | `core/engine/.../nativebridge/` | C++→Kotlin 增量镜像（applyDirtyFromNative/applyDirty + 全量 syncFromNative 兜底）+ **Kotlin→C++ 反向增量回导**（applyDirtyToNative：事务级捕获窗口 → 信封 {version,changed,removed}：gameData 全量剔除 rngStates + 弟子变化 id 全实体/removed + 集合引用变化全量/removed；失败降级全量 importToNative） |
| `GameStateStore.reverseDirtyAccumulator` | `app/.../state/GameStateStoreImpl.kt` | 事务级反向脏捕获（commitUpdateState 锁内：弟子脏 id 非消费 peek + gameData 引用 + 集合引用全量/消失 id）；`consumeReverseDirty()`/`resetReverseAccumulator()` 公开 API（窗口 = 自上次消费以来全部事务） |
| `DirtyTracker` | `gamecore/state/dirty_tracker.h/.cpp` | C++ 变更集（基线 JSON diff：gameData 字段级 + 实体按 id upsert/remove）；`syncBaselineToCurrent`（阶段 3：反向应用后同步基线防重发） |
| `DiscipleStore` | `gamecore/state/disciple_store.h/.cpp` | **SoA 列式实体存储**（阶段 3：~124 列 + idToRow；materialize/append/loadFrom/upsert 保序/removeById/swapRows 旋转同步索引；行序 == Kotlin ids 序为 RNG 红线）；GameState.disciples 唯一存储 |
| `PhaseSettlementExecutor` | `core/engine/.../service/` | 每旬结算入口（executeResidual = 自动装备/丹药/突破 = AUTHORITATIVE Kotlin 残留；executeCultivationBatch = 核心批次 OFF/SHADOW 路径） |
| 静态数据单一源 | `scripts/data/*.json` + `scripts/gen-*.mjs`（6 个） | T-CPP-2：中性源（唯一权威）→ 生成器 → C++ 表 + 测试快照；Kotlin Registry 由 RegistryGuardTest（6 类全量）+ StaticDataSingleSourceGuardTest（中性源↔快照逐字节）兜底 |

**双端共享渲染常量**（LOD 阈值/阴影常量/瓦片索引/语义建筑索引）单一数据源 = build-atlas.mjs LAYOUT——修改布局只改 LAYOUT，运行 codegen 后 Kotlin/C++ 双产物自动一致（守卫：SpriteCodegenSyncTest 头文本 ↔ 编译产物全等）。

## Building Y-Sort Rule

- **Y轴排序规则（2026-07-27）** — 宗门地图建筑渲染使用 Painter's Algorithm，排序键为**占地底部 Y 坐标**（`gridY + footprintHeight`），而非占地顶部（`gridY`）。当建筑占地高度不一致时按 `gridY` 排序会导致 z-order 错误。在 `MainGameScreen.buildBuildingDataArray()` 中实现，与 Unity Transparency Sort Axis(Y)、Godot Y Sort、Supercell(CoC) back-to-front 等行业标准一致
- **立体层装饰同序（2026-09）** — 树（`LAYOUT.tiles[].layer = 'object'`）与建筑并入**同一画家序**（按地面接触点归并，同键时建筑在后；契约 = `gamecore/map/draw_order.h`）。若把树留在地面层，北侧建筑会把树冠无脑压掉（树在前却消失）；草/石留在跟随地面的地面层
- **C++ 占地数组同步** — `NativeBridge.cpp` 的 `FP_W[]`/`FP_H[]`（由 `generateFootprintHeader` 从 `SpriteAtlasDef.FOOTPRINT_BY_NAME_INDEX` 生成）与 Kotlin 表必须完全同步（索引数量、顺序一致），新增建筑类型时两端同时添加
- **建筑/装饰显示尺寸口径（2026-09 起）** — 立体立绘按"屏上不变形"取值：精灵宽 = 占地宽、精灵高 = `round(宽 × 素材高 ÷ (0.75 × 素材宽))`（`0.75` = `TOPDOWN_Y_SCALE`），**精灵高 > 占地深是常态**（巡视楼 4×10、天枢殿 18×19）；占地 = 建筑底座（宽 = 精灵宽；深：塔/亭 2 格、池 3 格、院落/山丘/殿保持现状），**只减不增**以保旧档无拆除退款。贴地网格类（灵田/作物/瓦片/道路/岛边缘/门楼）按世界纵横比与地格对齐。装饰显示尺寸在 `build-atlas.mjs LAYOUT.tiles[].sprite`（小数格）：草/石 约 1 格宽、树 2×3.291 格。详见 [docs/adr/sprite-sizing-billboard.md](adr/sprite-sizing-billboard.md)；改尺寸由构建期 `validateDisplaySizing` + 守卫测试 `SpriteSizingFidelityTest` 双重把关

---

## Tech Stack

- **Language**: Kotlin 2.2.20, JVM target 17（UI / 平台能力层）+ **C++20**（引擎核心 `game-core`，经 JNI 对接；零 Android 依赖、桌面可编译、iOS 可复用）
- **Engine (C++)**: `game-core` 纯 C++20 引擎——确定性计算**唯一真相源**（时间/结算/战斗/生产/探索/内政/经济/外交/秘境与建筑/道路/弟子管理等 UI 操作面事务已 C++ 化，AUTHORITATIVE 生产默认；**反向同步通道逐域关闭与剩余 UI 操作面下沉仍在推进**，剩余域清单见 `docs/ui-read-surface.md` §4.1）；Kotlin `GameStateStore` 降级为镜像，`GameEngine` 签名保留、低频操作转发
- **ECS / 并行**: game-core 内置 ECS 骨架（Entity/Component/SoA 列存储/View 查询/System 调度）+ `JobSystem` 线程池并行化无共享写 system（每旬核心批次 `runPhaseCoreBatchParallel` 分块并行，零 RNG、与串行逐字节一致）
- **跨语言桥**: `GameCoreBridge`（Android JNI 薄层，逻辑收敛于 C++）+ C++ 侧 `nlohmann::json`；Kotlin 侧 kotlinx.serialization（全量快照镜像 + 增量变更集 `DirtyTracker`/`applyDirty`）
- **UI**: Jetpack Compose with Material3 (BOM 2026.05.01), no XML layouts
- **DI**: Hilt 2.56 (`@HiltAndroidApp`, `@HiltViewModel`, `@AndroidEntryPoint`)
- **Database**: Room 2.7.0 with KSP annotation processing; single shared DB file (`xianxia_sect.db`) for all save slots
- **Serialization**: Kotlinx Serialization (JSON + Protobuf + CBOR)；存档编码保持 Kotlin kotlinx-proto（镜像方案保证格式零变更）
- **Storage**: MMKV 2.4.1 统一偏好（`KeyValueStore`/`GamePreferences`，D-29 根治；DataStore 已移除），LZ4/Zstd (compression)
- **Network**: OkHttp + kotlinx.serialization（D-28 根治：Retrofit/Gson 死依赖已移除）
- **Auth**: TapTap SDK (login, compliance, analytics)
- **Build**: AGP 8.10.0, Gradle with Aliyun mirrors for China（版本以 `android/gradle/libs.versions.toml` 为准）

---

## Key Classes

- **`GameEngineCore`** — 游戏循环控制器（惰性结算引擎），仅推进时间 + 每旬 5 项最小检查 + 月变/年变事件
- **`GameEngine`** — 业务逻辑 Facade，注入到 ViewModel，写入 GameStateStore
- **`GameStateStore`** — 单一 MutableStateFlow<UnifiedGameState>，各字段通过 .map{} 派生。写操作由 `ReentrantLock` 串行化（非 `Mutex`，挂起时不释放锁），`_discipleTables` 进入 `deepCopy()` 提供快照隔离。生命周期状态采用 **BootPhase/RunState 双层设计**（详见 [architecture.md](architecture.md#lifecycle-architecture-bootphase--runstate-双层状态机)）。新增 `resetForSlot(slotId)` 方法，在创建新游戏/重启时同步 `GameStateRepository` 的 `currentSlotId` 和 `dirty` 集
- **`BootSequenceController`** — 启动序列控制器：统一编排新游戏/读档/重启的 BootPhase 推进、RunState 切换、资源预加载(回调)、游戏循环启停、地图生成、错误恢复。`boot()` 为统一入口
- **`GameViewModel`** — 主 ViewModel (Hilt)，通过 9 个 Delegate 拆分领域逻辑
- **`MainGameScreen`** — Tab 布局 (OVERVIEW/DISCIPLES/BUILDINGS/WAREHOUSE/SETTINGS)，无 NavHost
- **`GameData`** — Room @Entity，主键 (id, slot_id)
- **`CultivationService`** — 修炼 Checkpoint 快照法入口：`checkpointDisciple()` / `accumulateCultivationPerPhase()`（v4.0.82+ 列直读，无 Disciple 组装）/ `checkpointAllProduction()`
- **`CultivationRateCalculator`** — 修炼速率计算器（乘区法）。列直读入口 `calculateCultivationPerPhaseById`（每旬热点用），`calculatePreachingBonusesColumn` 返回「讲道长老 + 导师」两项传道（`teaching`）加成（对齐 `getEffectiveTeaching` 语义，计入社交乘区）
- **`GameStateStoreImpl`** — v4.0.82+：`discipleAggregates` + `sectCombatPower` 合并为单一 `DerivedAggregation` 派生链（sample 100 + 专用单线程调度器）；锁外弟子组装走 `assembleDispatcher` 单线程（防并发交错丢弟子）；`lastAssembledMutationVersion` 已删除
- **`GameLoopDelegate`** — 主线程健康检查（检测游戏循环卡死自动重启）。v4.0.82+ 加静态开关 `healthCheckEnabled`（测试环境禁用——mock 环境下每秒访问 relaxed mock 属性触发反射类加载风暴卡死）
- **`ComplianceCallbackHost`** — 防沉迷合规回调进程级宿主（D-42 根治，app/taptap）：WindowPort 接口 + 登录/游戏双窗口弱引用转发，限制类回调优先游戏窗口
- **`GamePreferences` / `KeyValueStore`** — 统一偏好存储（D-29 根治，core/data/prefs）：MMKV 实现 + 一次性 SP 迁移纯函数；豁免清单见 GamePreferences KDoc
- **`AudioPlayerFacade` / `AndroidAudioPlayer`** — 音频端口（G1 根治）：core/engine 接口 + app 层 SoundPool/MediaPlayer 实现
- **`CrashReporter` / `BuglyCrashReporter`** — 崩溃上报端口（G3 根治）：core/domain 接口 + app 层 Bugly 实现
- **`DiscipleAssignmentGate`** — 弟子分配门卫（v4.0.58），统一管理 11 个槽位系统的分配/释放/查询/读档重建

---

## 弟子分配门卫系统

v4.0.58 引入 `DiscipleAssignmentGate` + `DiscipleAssignmentRegistry` 集中管理所有槽位分配：

| 组件 | 文件 | 职责 |
|------|------|------|
| `DiscipleAssignmentGate` | `domain/disciple/DiscipleAssignmentGate.kt` | 门卫 Facade：`confirmAssign` / `release` / `rebuildFromGameData` / `filterAvailableDisciples` |
| `DiscipleAssignmentRegistry` | `domain/disciple/DiscipleAssignmentRegistry.kt` | Identity Map：`discipleId → SlotAssignment` |
| `DiscipleSlotCleanup` | `domain/disciple/DiscipleSlotCleanup.kt` | 死亡/释放时清理所有槽位，自动调用 `gate.release()` |
| `SlotCategory` | `model/SlotAssignment.kt` | 11 种槽位类别枚举 |
| `SlotAssignment` | `model/SlotAssignment.kt` | 分配记录数据模型 |
| `SlotCategoryCoverageTest` | `.../disciple/SlotCategoryCoverageTest.kt` | **守卫测试**：新增 `SlotCategory` 值时自动失败 |

**分配流程：** `releaseDiscipleFromAllSlotsAtomic(discipleId)` → `stateStore.update{}` → `gate.confirmAssign(discipleId, slotRef)`

> **编码注意事项：** 新增 `SlotCategory` 枚举值后需更新 **8 处**（权威清单见 `SlotCategoryCoverageTest` 类注释，守卫测试会失败并列出具体指引）：`scanAndRegister` + `DiscipleSlotCleanup.clearAllSlots` + 分配入口（事务内 `clearAllSlotsDataOnly` + 事务外 `releaseDiscipleFromAllSlotsAtomic`/`confirmAssign` + 旧 occupant release/sync）+ 守卫测试检查集合与 `entriesRequiringCleanup` + `DiscipleStatusService.buildSlotFlagsFor`/`SlotFlags` + `clearSlotsForReset` + `GameEngineSelfHealOps` 自愈扫描/重写 + `intentionalExcluded` 声明。详见 `docs/disciple-assignment-architecture.md`。

---

## 存档槽位隔离

所有存档共享单 SQLite DB，通过 `slot_id` 列 + 复合主键 `(id, slot_id)` 隔离：

- **所有实体必须使用 `primaryKeys = ["id", "slot_id"]`** — 例外会导致跨槽位 REPLACE 覆盖（StorageBag 在 v4.0.60 修复前就是唯一例外）
- **`GameStateRepository`** — 维护 `currentSlotId`（@Volatile），跟踪当前操作的槽位。`flushDirtyState()` 写入时用此值
- **`stateStore.resetForSlot(slotId)`** — 清空内存状态 + 清除仓库脏标记 + 设置当前槽位。`createNewGame` 和 `restartGameInternal` 中调用
- **`writeAllDataToDatabase` 统一强制 slotId** — 所有实体写入 DB 前必须 `.copy(slotId = slot)`，确保内存中的默认值 0 不会写入错误的槽位

---

## 探索系统

探索系统从 `ExplorationService`（Facade）拆分为 6 个独立职责的子系统：

| 子系统 | 文件 | 职责 |
|--------|------|------|
| `WorldLevelManager` | `exploration/WorldLevelManager.kt` | 关卡惰性管理：刷新/过期清理/妖兽移动（纯函数，分区 RNG） |
| `BeastAttackDetector` | `exploration/BeastAttackDetector.kt` | 妖兽攻击检测（纯函数，返回预警列表） |
| `PatrolBattleSystem` | `exploration/PatrolBattleSystem.kt` | 巡视塔战斗（拆 4 步：组队→索敌→战斗→结算） |
| `LootCalculator` | `exploration/LootCalculator.kt` | 掠夺计算（纯函数 + 副作用分离，修复双重扣除） |
| `DiscipleDeathHandler` | `exploration/DiscipleDeathHandler.kt` | 死亡标记 + deathYears + 装备断言守卫 |
| `ExplorationTeamManager` | `exploration/ExplorationTeamManager.kt` | 探索队伍管理（单事务内完成，竞态安全） |
| `ExplorationService` | `domain/exploration/ExplorationService.kt` | Facade：月度事件编排 + 保留玩家主动操作接口 |

---

## 装备系统（六部位套装体系）

> 2026-09 装备系统重构线（EQ-B0–B5）落地后的终态。权威方案 `docs/design/equipment-set-system-refactor-plan.md`（R1–R12/D1–D10/I1–I10）、批次编排与各批报告 `docs/design/equipment-batches/`、决策记录 [docs/adr/equipment-set-system.md](adr/equipment-set-system.md)、债务与遗留登记 `docs/architecture.md`「属性与装备体系」节。

| 组件 | 位置 | 职责 |
|------|------|------|
| `EquipmentSlot` | core/domain | 六部位枚举（HEAD/BODY/HANDS/FEET/WEAPON/LEGS，`@ProtoNumber` 10..15，显示序 = 声明序；移除饰品位） |
| `EquipmentInstance` | core/domain | 一行一实例模型：`setId/part/growth{level,exp,mainStat,subStats,subRolls}/meta`；等级/词条/强化随实例单点（装卸往返逐位保真）；全字段 val（恒等键缓存版本安全前提） |
| `EquipmentDatabase` / `EquipmentSetDatabase` | core/domain registry | 12 部件 × 6 品阶 72 展开条目；两套装（物理/法术）2/4/6 件档；`EquipmentRegistry` 只是纯转发层（D1 防复发守卫） |
| `EquipMainStatPool` / `EquipAffixPool` | core/domain registry | 主词条部位池随机（武器 1.15 输出向/腿部 0.95）+ 3 副词条 7 项权重池（13/13/14/15/15/15/15）不放回 |
| `EquipmentFactory` | core/engine | **唯一产出入口**：品阶受境界钳制（S17，双端默认哨兵 `REALM_UNRESTRICTED`）；词条 roll 走 `RngPartition.EQUIPMENT(13)` 双端 |
| `EquipmentLevelSystem` / `EquipmentUpgradeService` | core/engine | 升级 1–30（替换孕养）：曲线 `100×level×rarityMul`、每 3 级强化一条副词条、分解返还 50%；升级/分解走 native 事务（ActionId 1486/1487，C++ `equipment_tx.h`） |
| `EquipStatResolver` | core/engine | 词条+套装加成解析单点（2/4/6 档相加口径）；**恒等键整解析缓存**（4096 清空护栏；值语义键深哈希 6.3× 劣化实测否定，勿翻案） |
| `DiscipleEquipmentService` / `DiscipleEquipmentManager` | core/engine | 穿卸单轨六部位（卸装 = 实例保留 `isEquipped=false`，非删除）；`DiscipleSurrogate` 六部位列 headId(112)..legsId(116) 扁平代理 |
| `EquipmentValueSanitizeRule` | core/data integrity | 词条完整性消毒（order=28，coerce 双端一致）；旧装备折算补偿规则（原 order=27）已随 SS0 删档重置退役 |
| C++ 对偶 | `gamecore/data/equip_*.h`、`equipment_tx.h`、`equipment_factory.h`、`equipment_entries.h` | AUTHORITATIVE 真相源：装备事务/词条抽取/套装结算与 Kotlin 逐位对拍（`DiffEquipmentUpgradeTest` + `equip_*_test.cpp`） |
| codegen 链 | `scripts/data/equipment_db_sample.json` + `scripts/gen-templates.mjs` / `gen-game-data.mjs` | 静态数据单一真源（E4 禁手改生成物；G0 幂等门 + `TemplateCodegenIntegrityGuardTest`） |

**属性接口（B1 单列口径；五行属性伤害系统 2026-09-30 终态）**：装备/功法/丹药加成汇入弟子 `attack/defense` 单列；伤害类型走**普攻按角色配置（`innateDamageType`，当前全部角色设定物理——内容现状非架构恒等式）+ 技能按功法自带元素（`DamageType` 六活跃值：物理+金木水火土）+ 类型增伤/减伤 12 桶**；五行加成受**灵根 gate**（`SpiritRoot.elementGate`：灵根含该元素→全额、不含→0、物理恒全额，弟子侧 `typeDamageBonusesOf` 折算后进 Combatant 桶）；速度/灵力**不在**装备加成通道（S14 拍板）。战力公式 `attack×5 + maxHp×4 + defense×3 + speed×2`，装备占比锚 [30,45]（五行化 E4 校准重锚；`EquipmentPowerParityTest` 分维度钉死）。

**守卫测试族**：`EquipmentSlotOrderGuardTest` / `EquipmentSingleSourceGuardTest` / `EquipmentLevelPersistGuardTest`（R5 根因）/ `EquipmentStackRemovalGuardTest`（R6 符号面归零+白名单）/ `EquipmentRarityGateTest`（S17+产出链单点路由）/ `EquipmentProtoNumberFrozenTest`（E1 编号冻结）/ `EquipmentPowerParityTest`（S9）/ `EquipmentEconomyCalibrationTest`（S16）/ `EquipmentStatHotPathBenchmark`（S18 门 ≤1.10）。

**镜像面**：`equipmentInstances` 实体集合（ui-read-surface §2.2）+ disciples 行六部位列（proto 67-70/122/123）；UI 消费一律经 `GameEngine.equipmentInstances` 只读流，禁止 UI 侧自算第二份词条加成。

---

## 确定性 RNG 系统

所有随机操作使用分区 PRNG 确保存档/读档后随机序列一致：

| 组件 | 文件 | 说明 |
|------|------|------|
| `DeterministicRng` | `util/DeterministicRng.kt` | PCG-XSH-RR 算法，16 字节状态，可序列化 |
| `GameRngManager` | `util/GameRngManager.kt` | **14 分区枚举**（`RngPartition`）：BATTLE(0) / BREAKTHROUGH(1) / EXPLORATION(2) / SYSTEM(3) / ENEMY_GEN(4) / MAIL(5) / AI_SECT(6) / **SECRET_REALM(7)** / **MISSION(8)** / AI_SECT_MIRROR(9, `inSnapshot=false` 通道型) / CHAT(10) / RESIDUAL(11, `isLocal` 本地 PCG 不跨线) / GACHA(12) / **EQUIPMENT(13)**（装备词条 roll/升级强化，2026-09 装备重构线 B3 新增，双端 `kEquipment`）——`exportStates()`/`restoreStates()` 只处理 `inSnapshot=true` 的项（键 6 `kAiSect` 由 C++ 接管，Kotlin 侧经 9 号镜像键对齐，见 `game_core.cpp` 的 `rngStates.erase(kAiSect)`） |
| `RngPartition` | `util/RngPartition.kt` | 分区枚举（含 `inSnapshot` 通道型标记） |

**规则：** 新增任何使用随机数的逻辑，必须通过 `GameRngManager.getRng(RngPartition.xxx)` 调用，禁止直接使用 `kotlin.random.Random`（**红线由守卫测试闸门**：`RngSourceGuardTest` 五类入口逐模块登记上限只缩不增 + `RngEngineIsolationGuardTest` 禁止自建随机源；见 `docs/adr/rng-determinism-remediation.md`）。保存时 `exportStates()` 写入 `GameData.rngStates`，加载时 `restoreStates()` 恢复。

**MAIL 分区（2026-07-26 新增）：** `RngPartition.MAIL(5)` 专门用于邮件/兑换码奖励随机生成（弟子属性/装备/丹药/草药等）。`EquipmentDatabase`/`HerbDatabase`/`ItemDatabase`/`ManualDatabase` 的 `generateRandom*` 方法增加可选 `random: kotlin.random.Random` 参数，调用方（`MailService`/`RedeemCodeService`）从 `GameRngManager.getRng(MAIL)` 获取 RNG 传入。

**扩展：`nextGaussian()`** — `DeterministicRng` 新增 Box-Muller 变换实现的 `nextGaussian(mean, stddev)`，消耗 2 次 `nextDouble()` 调用生成 1 个标准正态偏差。不缓存配对值以保持 `snapshot`/`restore` 确定性。用于弟子属性生成（见下文）。

**出生随机流（2026-07-31 迁移）：** `ChildBirthSystem` 的受孕判定/出生月份/性别/灵根继承/新生儿属性全部迁移至 `RngPartition.SYSTEM` 分区（6 处 `GameRandom` → `rngManager.getRng(SYSTEM)`，灵根随机经 `rng.asKotlinRandom()` 适配 `SpiritRootGenerator.generate`）。旧存档已出生弟子不受影响；未来出生结果随存档确定性，读档后基于 `restoreStates` 继续推进。

**宗门地图种子（2026-07-31 确定性化；2026-09-14 熵源收敛）：** `createNewGame`/`restartGameInternal`（`GameEngineLoadDataOps.kt:265/336`）的 `mapSeed` 由 **`EngineEntropy.nextWorldSeed()`**（`SecureRandom.nextLong() xor System.nanoTime()`）生成——**显式会话熵源、非分区、不伪装可复现**（其 KDoc 明确"只允许用于创建全新世界的根种子"）；同时作为分区 PRNG 的 `initSystemSeed` 输入。历史实现 `GameRandom`（自建 object，挂钟种子 + `@ThreadLocal`）**已物理删除**（ADR 阶段 1③），残留调用变编译期报错。连带修复 `restartGameInternal` 不生成 mapSeed 导致重启后全分区种子为 0、地图完全相同的缺陷。

---

## 弟子属性生成

弟子创建时属性通过两个入口生成：

### 入口 1：`DiscipleFactory.create()`
- 路径：`domain/disciple/DiscipleFactory.kt`
- 使用 `DiscipleSeed.nextInt` Lambda（由 `GameRngManager.getRng(分区)` 提供；旧的 `GameRandom` 兼容路径已随对象删除而消失）
- 生产弟子构造唯一端口 = C++ `createDisciple`（开局名册与寻访解锁入册经 `instantiateTemplate` 走 native 事务）；本类为跨语言对拍孪生实现（`DiffDiscipleFactoryTest` 逐字段锁守），生产零调用

### 入口 2：`AISectDiscipleManager.generateRandomDisciple()`
- 路径：`domain/diplomacy/AISectDiscipleManager.kt`
- `rng` 为**解析式**：优先取注入的 `GameRngManager.getRng(AI_SECT)`（AUTHORITATIVE 下委托 C++ `kAiSect` 分区）；无管理器时回落 `initForSlot(aiSeed)` 播种的 `DeterministicRng.fromSeed()` **混种态**（`snapshot()` 是状态而非种子——2026-09-14 修复"写裸种子"根因，`DiffAiRngSeedingTest` 跨语言逐位锁守）
- 用于 AI 宗门弟子生成

### 属性分布规则（2026-07-24 优化）

| 属性类别 | 属性 | 分布 | 参数 |
|---------|------|------|------|
| 悟性 | `comprehension` | 灵根驱动均匀 | 1根[80,100] / 2根[60,100] / 3根[40,100] / 4根[20,100] / 5根[1,100] |
| 技能(8) | intelligence, charm, morality, artifactRefining, pillRefining, spiritPlanting, mining, teaching | **正态分布** | N(50.5, 16.5²)，截断[1,100] |
| 方差(7) | hpVariance ~ speedVariance | **正态分布** | N(0, 16.667²)，截断[-50,50] |

悟性保持不变（灵根数量决定范围），其余所有技能和方差属性使用 `gaussianInt()`（Box-Muller 变换生成的 int 值，越接近中间概率越高）。

---

## Component Table Architecture

Disciple entities are stored in `DiscipleTables` — ~95 narrow `ComponentTable`/`IntComponentTable`/`DoubleComponentTable` columns. 底层使用 `IntFlatArray`/`DoubleFlatArray`（dense 平铺数组 + idToSlot，零装箱），查询 O(1)，删除 O(1) swap-on-remove。所有 CRUD 通过 `buildCopyableRefs()` 声明式列表驱动，新增列只需在列表加一行。

**列级 Copy-on-Write 快照隔离（v4.0.82+）：** `deepCopy` 不再逐元素全量复制——`ComponentTable.store` 存储引用化，`adopt()` 共享源存储（O(1)），事务缓冲首次写入某列时 `ensureOwned` 私有化（Int/Double 整体 copyOf / SparseArray clone，O(capacity)）。13 张 Mutable 列（List/Set/Map）走 `adoptDeep` 急切深拷贝。`GameStateStoreImpl` 脏判定改为 `dirtyTracker.isDirty`——纯 UI 事务不再触发全量 assembleAll。兜底开关 `DiscipleTables.forceFullCopy`。性能基准：100 弟子 deepCopy+写 3 列 ≈ 122μs/次。

**注意事项：** 值对象（lifeEvents 等 List/Map 列）必须整体替换（`col[id] = newList`），禁止原地修改后依赖自动检测——原地修改绕过 set 不触发 COW 私有化，会污染共享存储破坏快照隔离。

---

## 修炼 Checkpoint

`cultivationCheckpoints: DoubleComponentTable` + `cultivationCheckpointGameMonths: IntComponentTable`。修炼值 = checkpoint + rate × (currentMonth - cpMonth) × 3。

**v4.0.82+ 变更：** Checkpoint **不再每旬同步**——每旬累积只改变修为、从不改变速率，每旬同步会让检查点恒等于修为、投影退化为恒等函数。现在只在**速率变化点**更新：政策切换（`SectPolicyToggleUseCase` 三个修炼政策已补 `checkpointAllDisciples`）、长老变更（`ElderManagementUseCase`）、丹药（`AutoPillService`）、突破（`DiscipleBreakthroughHandler`）。

**运行时投影：** `getEffectiveCultivation()` 实时投影——**源码级生产调用点存在**（`CultivationService.accumulateCultivationPerPhase` 投影块，活代码），但批 9-2（Kotlin 旬结算路径退役）后 **Kotlin 运行时无执行驱动**（唯一驱动 `PhaseSettlementExecutor.execute` 完整版仅对拍/回归基准），生产修炼累积/投影由 C++ `cultivation.h` 承担；Kotlin 链作为 checkpoint 投影契约 + 跨语言对拍基准保留（S-21 勘误，2026-09-02）。修炼速率计算走列直读 `CultivationRateCalculator.calculateCultivationPerPhaseById`（无 Disciple 组装，与对象式入口数学等价——`CultivationRateEquivalenceTest` 30+ fixtures 守卫，含讲道长老/导师/政策津贴与跨阈值组合）。

---

## EntityStore 增量更新

其他实体类型用 `EntityStore<T : HasId>`，MutableList 原地修改 + `freeze()` 快照 + `isDirty` 标记检测。GC 分配降低 80%+。

**注意事项：** `plus(item)` 必须通过 `EntityStore(newItems)` 构造新实例，不可 `EntityStore()` + `items_.addAll()`，否则 `frozenSnapshot` 未正确初始化。

### EntityStore 操作模式

| 操作 | 复杂度 | 说明 |
|------|--------|------|
| `entityStore.get(id)` | O(1) | HashMap index |
| `entityStore.update(id) { transform }` | O(n) indexOfFirst + O(1) HashMap | 零分配 |
| `entityStore.freeze()` | — | 仅在 dirty 时分配新 List，before StateFlow emission |
| `MutableGameState` 字段 | — | `discipleTables`, `equipmentStacks: EntityStore<EquipmentStack>`, `productionSlots: List<ProductionSlot>`, `spiritMineSlots: List<SpiritMineSlot>` |

---

## 生产系统 Checkpoint

`ProductionSlot.baseDuration` 存储配方基础持续时间。政策/长老变化时 `checkpointAllProduction()` 遍历所有活跃槽位，重算 duration 和 completionMonth，保留已完成进度比例。灵田/灵植使用 `calculateSpiritFieldMaturityBonus` 动态重算 effectiveGrowTime。

### 灵矿场结算

`spiritMineLastSettledMonth` 时间戳差分，`产出 = rate × (currentMonth - lastSettled)`。

### 炼丹/锻造 Checkpoint

`ProductionSlot.baseDuration` 存储配方基础值，`recalculateAllCompletionMonths()` 按当前政策/长老重算 duration + successRate。

### 政策/长老变更触发

`SectPolicyToggleUseCase` 和 `ElderManagementUseCase` 在变更后调用 `checkpointAllProduction()`。

---

## SaveValidator 规则引擎

存档完整性校验器（`data/integrity/SaveValidator.kt`）从 8 项硬编码检查重构为可扩展规则引擎（v4.0.67）。

### 规则接口

```kotlin
interface SaveValidationRule {
    val id: String                          // 唯一标识
    val order: Int                          // 执行顺序（小→大）
    fun execute(data: SaveData, context: RuleContext): RuleOutcome
}
```

### 注册表

`SaveValidationRuleRegistry` object（`CopyOnWriteArrayList` 存储，`order` 排序）。新规则只需在 `registerDefaults()` 加一行注册。

### 规则上下文

`RuleContext` 在遍历规则前一次性预计算：`allEquipmentIds`（equipmentStacks + instances 的 ID 并集）、`buildingInstanceIds`（placedBuildings 非空 instanceId）。中间状态 `removedDiscipleIds` 由 GhostDiscipleCleanupRule 写入、GhostRefCleanupRule 消费。

### 当前已注册规则（23 条，G10 重盘 2026-09-26，与 `SaveValidationRuleDefaults.kt` 对齐）

| 规则 | order | 功能 |
|------|-------|------|
| NumericSanitizeRule | 0 | NaN/负值消毒（防穿透 cap 规则） |
| DiscipleIdBoundsRule | 1 | 弟子 ID 越界判损坏（防大 id 扩容 OOM） |
| SectNameRule | 1 | sectName 空→默认名 |
| GameDateRule | 2 | year/month 范围 |
| GamePhaseRangeRule | 4 | phase 范围 [0,2] |
| CultivationCapRule | 5 | 修为上限截断 |
| EquipmentRefRule | 6 | 装备引用存在性 |
| BuildingRefRule | 8 | 建筑引用存在性 |
| DuplicateDiscipleIdRule | 9 | 重复弟子 ID 去重 |
| GhostDiscipleCleanupRule | 10 | 幽灵弟子清理 |
| GhostRefCleanupRule | 11 | 幽灵引用清理 |
| SpiritStoneNonNegativeRule | 12 | 灵石负值截断 |
| DiscipleRealmConsistencyRule | 13 | realm/layer 合法性 |
| DiscipleDeadStatusRule | 14 | 死亡弟子装备清理 |
| EquipmentDedupeRule | 15 | 装备去重 |
| SlotRefRule | 16 | 槽位引用存在性 |
| BloodPoolBuildingCleanupRule | 17 | 血炼池建筑残留清理 |
| ItemRefConsistencyRule | 18 | 物品引用一致性 |
| EntityCountBoundsRule | 19 | 实体数量上界 |
| RecruitListCleanupRule | 20 | 招募链残留恒空清表 |
| BattleLogRefRule | 21 | battleLogs 条目结构校验 |
| MailDiscipleAttachmentCleanupRule | 22 | 邮件弟子附件残留摘除 |
| JadeSymbolNonNegativeRule | 23 | 玉符字段负值/超限钳制 |

> order=3/7 空洞 = 已下线的年龄/寿命规则的退役编号，不复用。

### 规则文件位置

- 规则实现：`data/src/main/java/.../integrity/rules/*.kt`
- 规则测试：`data/src/test/java/.../integrity/rules/*Test.kt`
- 入口 facade：`data/src/main/java/.../integrity/SaveValidator.kt`
- IntegrityResult 密封接口保持兼容不变

## 热控与温度读取

`ThermalReader` 接口定义三通道温度获取策略，`AndroidThermalReader` 实现（v4.0.41）：

1. `PowerManager.getThermalHeadroom(10)` (API 30+) — 主动预测
2. `PowerManager.currentThermalStatus` (API 29+) — 被动状态
3. sysfs + BatteryManager — 降级回退

`ThermalController` 消费 `ThermalReader` 温度数据驱动四档降级阶梯（GREEN/YELLOW/ORANGE/RED），联动渲染质量、目标帧率。

## 平板省电体系（2026-08-14）

用户反馈平板耗电 3 倍。根因：120Hz 面板不降刷新率（×2 屏耗）+ 每帧全物理分辨率渲染（×1.58 像素填充），叠加黑名单平板走 CPU 软件渲染。五工作包：

| 机制 | 决策/实现 | 位置 |
|------|----------|------|
| 渲染分辨率缩放 | `RenderScalePolicy`（面积分级 COMPACT/STANDARD/LARGE/XLARGE × GPU 档 cap × 软件路径 0.8 × qualityFactor，floorTo05 离散 + clamp [0.5,1.0]；COMPACT 手机 + Vulkan 恒 1.0 基线；**COMPACT 手机 + SOFTWARE 降载**（LOW→0.5/MEDIUM→0.6/HIGH→0.8——2026 修复：手机 SOFTWARE 此前被短路无降载，低端真机拖动视角卡顿）） | `core/engine/.../render/RenderScalePolicy.kt` |
| Vulkan 降采样渲染 | 离屏颜色目标 + `vkCmdBlitImage` 上采样；blit 能力守卫回退直渲；setRenderScale 重建语义同 resize | `VulkanBackend.cpp/.h` |
| Canvas 降采样渲染 | 帧缓冲 = round(物理×renderScale) + drawScale 公式适配 + 双线性上采样提交 | `SoftwareCanvasBackend.kt` / `SoftwareRenderBackend.kt` |
| 帧率↔刷新率联动 | `FrameRateDeclarationPolicy`：>60Hz 面板 {60,30} 两档（首帧 60 恰逢淡入）+ 升档 2s 防抖；≤60Hz 旧行为一致 | `feature/game/.../sect/FrameRateDeclarationPolicy.kt` |
| 脏帧跳过 | `FrameSkipPolicy` 五守卫（相机/帧引用/总线/淡入/缩放）；跳帧不统计 EWMA 防虚高 | `feature/game/.../sect/FrameSkipPolicy.kt` |
| 动态 ADPF 目标 | `frameDurationNs(fps)` 替代硬编码 60fps；renderFrameRate collect 联动 | `GameEngineCore.kt` / `ThermalMonitor.setTargetWorkDuration` |
| 省电模式监听 | `isPowerSaveMode` + `ACTION_POWER_SAVE_MODE_CHANGED`；fpsCap = min(低电量45, 省电30) | `BatteryAwareController.kt` |

**关键设计**：render scale 是后端内部像素密度参数——`RenderBackend` 接口契约保持物理像素（相机/命中测试/世界可视范围全部不受缩放影响）；`RenderFlags.renderScaleEnabled`/`refreshRateDeclaration` 开关可二分定位回退。

---

## Hot Path Rules

Component Table 模式下的关键性能规则：

- **Disciple updates**: Write directly to `tables.loyalty[id] = 90` — O(1) via IntPackedArray (was O(log n) SparseArray)
- **Disciple reads**: `tables.names[id]`, `tables.realms[id]` — O(1) via IntPackedArray (was O(log n))
- **Disciple assembly**: `tables.assemble(id)` creates a full `Disciple` data class (~200 fields, 5 nested layers) — ONLY for UI/Serialization, NEVER in hot path
- **Hot path column reads**: `cultivation` 热路径用列直读替代 `assemble()`。父母加成仅读 `isAlive` + `spiritRootTypes` 两列，讲道加成仅读 `isAlive` + `realms` + `teachings` 三列。300 弟子时 `assemble` 调用从 1500+ 次/100ms 降至 300 次/100ms（降低 80%）

---

## 云存档系统

基于 TapTap Cloud Save SDK (`com.taptap.sdk:tap-cloudsave:4.10.5`) 实现。

### 架构

```
游戏内存档管理弹窗(slot 0) ← 显示云端数据、点击下载加载（SaveSlotDialog）
    ↕
TapCloudSaveManager     ← @Singleton，封装上传/下载/查询/清理
    ↕
ReflectiveCloudSaveApi  ← 运行时反射桥接 TapTapCloudSave 静态方法
    ↕
TapTap Cloud Save API   ← createArchive / updateArchive / getArchiveList / getArchiveData / deleteArchive
```

### 关键设计

- **slot 0 = 云存档入口** — 在游戏内存档管理弹窗显示"云"图标 + 云端存档信息（宗门/年份/弟子/灵石），与本地存档操作一致（主菜单选档页已于 2026-10-01 退役）
- **UUID 缓存** — 第一次上传成功后本地缓存云端 Archive UUID，后续直接 `updateArchive(uuid)` 避免创建重复存档
- **一次性旧档清理** — 首次上传前删除当前账号下全部旧协议命名档（`mnzm_cloud_save`/`slot_N`，SS0 删档重置第二保险；v2 命名之外的非本游戏命名档不动），单档删除失败不阻断
- **反射桥接** — 使用 `java.lang.reflect.Proxy` 动态代理适配 TapTap SDK，兼容 XDSDK 和原生 SDK 两套 API

### 反射 API 确认

反编译 `tap-cloudsave-4.10.5.aar` 确认的实际 API：

```kotlin
// 主类（Kotlin object，静态方法）
com.taptap.sdk.cloudsave.TapTapCloudSave
  fun createArchive(ArchiveMetadata, String, String?, TapCloudSaveRequestCallback)
  fun updateArchive(String, ArchiveMetadata, String, String?, TapCloudSaveRequestCallback)
  fun getArchiveList(TapCloudSaveRequestCallback)
  fun getArchiveData(String, String, TapCloudSaveRequestCallback)
  fun deleteArchive(String, TapCloudSaveRequestCallback)

// 回调接口
com.taptap.sdk.cloudsave.internal.TapCloudSaveRequestCallback
  onArchiveCreated(ArchiveData) / onArchiveUpdated(ArchiveData) / onArchiveDeleted(ArchiveData)
  onArchiveListResult(List<ArchiveData>) / onArchiveDataResult(ByteArray)
  onRequestError(Int, String)

// 数据类
com.taptap.sdk.cloudsave.ArchiveMetadata  ← Builder 模式：setName/setSummary/setExtra(JSON)/setPlaytime(int)
com.taptap.sdk.cloudsave.ArchiveData      ← getUuid/getFileId/getName/getSummary/getExtra/getSaveSize/getModifiedTime
```

### 云读档管线（2026-08-04 统一，与本地读档同语义）

下载的云存档写入本地前依次执行：

1. **完整性校验** `SaveValidator.validate` — 损坏（Corrupted）拒绝加载并提示；可修复（Repaired）自动修复后继续。saveVersion 为云档版本戳（`SaveVersion.CURRENT`，写入时盖章；顺序迁移器已随 SS0 删档重置退役）
3. **堆叠重建** `SaveDataReconciler.reconcileStacks` — 旧格式云档无堆叠数据时从实例重建

写入本地失败（低内存/编码失败）**必须中止并明确提示**，不再静默继续读档误读旧数据。游戏内下载（`performCloudDownload`）同样经过上述管线并**持久化到本地 DB**（重启不再丢失），且与加载流程重叠时拒绝执行（isLoading 保护）。

### 已知待修复问题

云存档模块的各项问题已在 2026-07-25~28 期间处理完毕（并发锁、下载备份、静默降级、跨版本兼容、shuffled PRNG 迁移等），详见 CHANGELOG.md。

## Mail & Reward System

Mail reward claims use Saga compensation: `stateStore.update {}` 原子写入物品+claim记录，若 `distributeAttachmentsInline` 抛出则 `mailRecords` 不写入，邮件保持未领取。

- **Stable IDs**: 内置邮件用 BuiltinMailConfig 确定性 ID，在线邮件用 `"online_${remoteMailId}"`（在线拉取通道已于 2026-09-15 下线，`online_*` 仅存量邮件仍可领取/过期）
- **GameData 存储**: `mailRecords: List<MailClaimRecord>`（含 mailId/claimedAt/source），非邮件内容
- **初始化**: `mailService.resetAndInitSlot()` 在世界初始化后调用
- **清理**: `StorageEngine.delete()` 清理已删档位的 mails 表
- **RNG 分区（2026-07-26）：** 邮件奖励随机生成使用 `RngPartition.MAIL` 分区 PRNG，通过 `GameRngManager` 注入到 `MailService` 和 `RedeemCodeService`，所有 `generateRandom*` 调用传入一致的 RNG 实例。
- **邮件来源收敛（2026-09-15，SS0 再收敛）：** 运营注入通道仅剩节日/内置邮件（`loadBuiltinMails`，28 封）+ 管理员通用补偿（`sendAdminCompensation`）；QQ 群引导邮件与白名单福利邮件（`injectWhitelistBonus`/`sendWhitelistBonus`）已随 SS0 删除（白名单只留免广告特权）；功能性邮件（溢出/秘境关闭）保留；在线邮件月度拉取、单用户定向注入与旧档天枢殿拆除补偿迁移已删除；存量邮件不清理、自然过期。

---

## Navigation Pattern

No `NavHost` is used for the main game. `MainGameScreen` switches content via `MainTab` enum. Feature screens (Alchemy, Forge, HerbGarden, etc.) are dialogs opened via `DialogStateManager.openDialog(DialogType, params)`. The only Activity transition is:

1. `MainActivity` → `GameActivity` (in-game; 登录/防沉迷验证通过后按存档状态自动进入——本地最新档自动读 / 云端兜底 / 自动新建，判定由 `AutoEntryResolver` 纯函数承担，全程加载界面承载)

---

## 引导系统（Guide System）

### 入口
- **按钮位置**：`GameActionButtons.kt` 右上角第一行，外交按钮左侧
- **DialogType**：`Guide`（全屏模式 `DialogMode.Full`）
- **路由**：`GameOverlayHost` → `GuideDialog`

### 数据模型
- **GuideTask**（`core/domain/model/guide/GuideTask.kt`）：任务定义，含名称、描述、条件列表、奖励
- **GuideCondition**（sealed interface）：12 种条件类型（BuildingCount、ElderAppointed、CumulativeCounter、PlantCropOnce 等）
- **GuideCounterKeys**（常量类）：所有计数器 Key 编译期安全，消除拼写错误
- **GameData 字段**：`guideClaimedRewardIds: Set<Int>` + `guideCounters: Map<String, Long>`

### 计数器接入点
计数器在对应子系统的事件完成处递增：`CultivationSettlement`（灵矿）、`ProductionProcessor`（炼药/锻造）、`DiscipleBreakthroughHandler`（突破）、`PatrolBattleSystem`（巡逻击败妖兽）、`GuideDelegate`（打开寻访）等。

### 奖励发放
- **入口**：`GameEngineGuideOps.claimGuideReward(taskId)`
- **读取任务定义的 `rewardItemQuantity`**，非硬编码
- **使用 GameRngManager**（SYSTEM 分区）替代 UUID.randomUUID()
- **条件检查和发放位于同一次 `stateStore.update`**，消除 TOCTOU 竞态

### 已知限制
- `DiscipleReachRealm` 条件类型 **已实现**（2026-07-24），`GuideCondition` 接口新增 `isMet(gameData, discipleTables)` 重载，`DiscipleTables` 通过 `GuideDelegate` / `GuideDialog` 透传
- ✅ 月度事件管线多事务问题已修复（2026-07-27）：所有子服务移入单次 `stateStore.update`，利用重入缓冲机制，月度循环 4→1 次 update
- ✅ 年变事件管线多事务问题已修复（2026-07-27）：18 个子服务全部移入单次 `stateStore.update`，年变循环 ~20→1 次 update
- ✅ `shuffled()` 迁移至分区 PRNG（2026-07-27）：`DisciplePurchaseService`(5处)+`LootCalculator`(1处) 改为 `GameRngManager` 分区 PRNG，新增 `RngExt.shuffled(rng)` 扩展函数

---

## Android SDK / Encoding

- `compileSdk = 35`, `minSdk = 24`, `targetSdk = 35`
- All Java/Kotlin compilation is forced to UTF-8 to prevent Chinese character corruption
- Uses Aliyun Maven mirrors for Gradle plugin and dependency resolution

---

## 仓库堆叠系统（Inventory Stacking）

### 存储模型

8 种独立实体存储在 `MutableGameState` 中：

| 类型 | 字段 | 存储 | 堆叠上限 | 合并键 |
|------|------|------|---------|--------|
| EquipmentStack | `equipmentStacks` | `EntityStore` | 999 | `name + rarity + slot.name` |
| ManualStack | `manualStacks` | `EntityStore` | 999 | `name + rarity + type.name` |
| Pill | `pills` | `EntityStore` | 999 | `name + rarity + category.name + grade.name` |
| Material | `materials` | `EntityStore` | 9999 | `name + rarity + category.name` |
| Herb | `herbs` | `EntityStore` | 9999 | `name + rarity + category` |
| Seed | `seeds` | `EntityStore` | 9999 | `name + rarity + growTime` |

### 统一合并入口

所有仓库物品的添加操作通过 `StackableItemStore<T>` 统一路由（`core/domain/.../state/StackableItemStore.kt`），替代原来分散在 `InventorySystem` 中的 6 组内联合并。

核心数据结构：
- `keyIndex: HashMap<StackKey, MutableList<String>>` — 支持同种物品多个堆叠
- 合并策略：遍历所有匹配堆叠，逐个填充剩余空间 → 所有堆叠填满后创建新堆叠 → 容量满时返回 `DomainResult.Partial`

### 溢出处理

2026-07-23 重构后：
- 溢出时优先创建新堆叠（不再静默丢失物品）
- `DomainResult.Partial` 仅在仓库真正满仓时返回
- 所有 20+ 调用方通过 `when(result)` 穷尽分支处理三种结果

### 整理功能

- `consolidateStacks()` — 合并分散堆叠，在 `sortWarehouse()` 和 `BootSequenceController` 读档时自动执行

### ⚠️ 已修复项目（2026-07-27）

#### 一、合并入口 — 已统一

`sellItem` 和 `bulkSellItems`（通过 `deductStack`）已从临时 `StackableItemStore(Int.MAX_VALUE)` 模式重构为直接 `EntityStore.get/update/remove` + `withQuantity()` 操作。消除了容量守卫绕过和 `stackKeyOf` lambda 重复。

| # | 路径 | 修复 |
|---|------|------|
| 4 | `sellItem` / `bulkSellItems` | 重构为 `sellStack`/`deductStack`：直接 EntityStore 操作，wallet.add 先于 store.remove，消除 Int.MAX_VALUE 绕过 |
| 13 | `confiscateStorageBagItem` stackKeyOf 重复 | 保持现有重复（各途径独立上下文，抽象通用方法收益有限） |
| 15 | 两套堆叠逻辑并存 | `sellItem` 已对齐为 EntityStore 操作，不再使用临时 StackableItemStore |

#### 二、事务完整性

（本次已全部修复：buyMerchantItem 灵石顺序、sortWarehouse 原子化、openStorageBag 单事务）

#### 三、防御性加固

| # | 问题 | 文件 | 当前状态 |
|---|------|------|---------|
| 11 | `StackableItemStore.replaceAll()` 不验证 items 是否超 maxStack | `StackableItemStore.kt` | 低概率触发 |
| 13 | `confiscateStorageBagItem` 中 `stackKeyOf` 匿名 lambda 与 `InventorySystem` 重复定义 | `InventoryFacadeImpl.kt` | 需复用 `::equipmentStackKey` |

#### 四、代码可维护性

| # | 问题 | 建议 |
|---|------|------|
| 15 | 两套堆叠逻辑并存（`StackableItemStore` / 直接 EntityStore） | 本次大幅缩减差距，`sellItem` 仍直接操作 EntityStore |
| 17 | `StackKey.parts` 使用 `List<Any>`（弱类型安全） | 未来用内联类加固（当前因泛型约束无法直接替换） |

#### 已完成（2026-07-24 架构债务批量处理）

| # | 问题 | 修复方式 |
|---|------|---------|
| 1 | StorageBagUtils 绕过统一入口 | `mergeEquipmentStackToWarehouse` 改为 `DiscipleEquipmentService` 直接使用 `StackableItemStore` |
| 2 | buyMerchantItem 非装备路径绕过容量守卫 | 改为 `StackableItemStore` |
| 3 | openStorageBag 直接 `EntityStore.add()` | 单事务 + `StackableItemStore` 统一入口 |
| 5 | AutoBuyService 直接 `EntityStore.add` | `addToWarehouse` 改为 `StackableItemStore` |
| 6 | DiscipleFacadeImpl equipItem/returnToWarehouse 绕过 | `unequipEquipmentLogic` 改为 `StackableItemStore` |
| 7 | confiscate 无事务回滚 | 先入库后移除弟子物品 |
| 8 | buyMerchantItem 先扣灵石后加物品 | 先加物品后扣灵石，Partial 时取消交易 |
| 9 | sortWarehouse 两次独立 update | consolidate+sort 合并为单事务 |
| 12 | merge=false + 仓满 Partial 语义歧义 | merge=false 时直接返回 Failure |
| 14 | canAddXxx 读 StateFlow | `canAddItemInTransaction(state)` 方法 |
| 16 | otherTypes 手动计算 | `otherSlotsCount(excludeType)` 辅助函数 |
| — | consolidate 合并不彻底（单次循环） | 改为 `while(changed)` 迭代合并 |

---

## 免广告特权白名单

### 架构设计

白名单守卫通过 **集中式入口** 实现，新增广告功能天然继承：

```
ViewModel → adService.watchAd(AdPurpose.XXX) { 发放奖励 }
                │
                ▼
         AdServiceImpl.watchAd()
                │
                ├─ 白名单用户 → onReward() 立即回调（跳过广告加载/播放）
                └─ 普通用户 → 正常加载并播放激励视频
```

### 关键文件

| 组件 | 文件 | 职责 |
|------|------|------|
| `AdFreeWhitelist` | `:core:domain/.../AdFreeWhitelist.kt` | 白名单身份检查（`isCurrentUserPrivileged()`） |
| `GameConfig.Whitelist` | `:core:domain/.../GameConfig.kt` | 白名单列表硬编码（`AD_FREE_UNION_IDS`） |
| `AdService` (interface) | `:core:engine/.../AdService.kt` | 广告服务统一接口 + `AdPurpose` 枚举 |
| `AdServiceImpl` | `:app/.../taptap/AdServiceImpl.kt` | 白名单守卫集中检查 + 实际广告播放 |
| `AdsDelegate` | `:feature:game/.../AdsDelegate.kt` | 冷却/每日次数限制（白名单用户自动跳过） |

### 新增广告类型的标准流程

```kotlin
// 1. AdPurpose 加枚举值（现状仅 JADE_SYMBOL_BONUS 一个）
enum class AdPurpose { JADE_SYMBOL_BONUS, NEW_FEATURE }

// 2. ViewModel 加方法（不需要任何白名单判断）
fun watchAdForNewFeature() {
    if (isDailyAdLimitReached()) return
    adService.watchAd(AdPurpose.NEW_FEATURE) { 发放奖励() }
}
```

白名单检查在 AdService 实现层统一完成，新增广告无需额外处理。

### 白名单管理

- **添加用户**：在 `GameConfig.Whitelist.AD_FREE_UNION_IDS` 的 `setOf(...)` 中添加 unionId
- **初始化时机**：`GameActivity.onCreate()` → `AdFreeWhitelist.initialize(sessionManager.unionId)`
- **管理维护**：手动硬编码维护，无需运行时更改

---

## 扩展性现状盘点

> **本文是 rules/*.md 扩展规范的唯一事实基线**（2026-08-04，对应 v4.00.86）。每个事实点附代码路径，可 grep 复核。**功能扩展落地后必须同步更新本表**，防止规则与代码事实漂移（本项目迭代极快，v4.0.84~4.0.86 三天内多次玩法发布）。

### 商业化现状

| 维度 | 现状 | 代码位置 |
|------|------|---------|
| 激励视频广告位 | 仅 1 个：`JADE_SYMBOL_BONUS`（观看广告获得玉符） | `AdPurpose` 枚举：`core/engine/.../service/AdService.kt` |
| 广告调用链 | `AdService`（接口）→ `AdServiceImpl`（app 层，白名单守卫集中检查）→ `RewardVideoAdManager`（TapTap SDK 封装，冷却+每日次数限制） | `:app/.../taptap/AdServiceImpl.kt`、`AdsDelegate.kt` |
| IAP/内购 | **0 个付费点**（无月卡/战令/礼包/直购） | 无代码 |
| 运营邮件 | 全部客户端内置 `BuiltinMailConfig`（节日 14 天限时 + `minVersion` 门槛，28 封；QQ 群引导与白名单福利已随 SS0 删除）；在线月度拉取、单用户定向注入与天枢殿拆除补偿迁移已于 2026-09-15 删除（存量自然过期）；管理员通用补偿入口 `GameEngineAdminOps.sendAdminCompensation` 保留 | `core/engine/.../config/BuiltinMailConfig.kt`、`MailService.kt` |
| 远程配置 | **未绑定**：`CoreModule.kt:157` 的 `HttpRemoteConfigProvider` 处于注释状态，`ConfigLoader(assetReader)` 无远程；接口 `RemoteConfigProvider`（core/domain）+ 实现 `HttpRemoteConfigProvider`（core/engine，10s 超时）已存在 | `:app/.../di/CoreModule.kt:156-158`、`core/domain/.../config/RemoteConfigProvider.kt` |
| 免广告白名单 | `AdFreeWhitelist` + `GameConfig.Whitelist.AD_FREE_UNION_IDS`（硬编码）+ 专属福利邮件 | 见"免广告特权白名单"章节 |
| 更新日志 | 游戏内 `android/app/src/main/assets/changelog_entries.json`（本地 asset，`core/data/.../ChangelogData.kt` 解析） | `ChangelogData.kt:39` |
| 广告冷却 | 全局 60 秒，奖励验证后计冷却，状态存 GameViewModel | `rules/ad-cooldown.md` |
| 合规 | `ComplianceManager`（隐私同意、OAID 广告追踪限制开关）；游戏内隐私政策 `PrivacyConsentScreen.kt` + 网站版 `docs/index.html` | — |

### 社交现状

| 维度 | 现状 | 代码位置 |
|------|------|---------|
| 玩家社交 | **无好友/聊天/排行/分享** | 无代码 |
| 世界外交 | 纯 AI 模拟：宗门好感度/送礼/结盟/附庸契约/交易/进攻警告/AI 宗门间结盟 | `engine/domain/diplomacy/`、`GameEngineDiplomacyOps.kt` |
| 玩家反馈渠道 | 无（无服务器通道） | — |

### 数据现状

| 维度 | 现状 | 代码位置 |
|------|------|---------|
| 事件埋点 | **已实现（2026-08-15）**：TapDB 客户端接入完成——FTUE 漏斗事件（`#game_new_save`/`#battle_first_win`/`#breakthrough_first`）、广告收入 `#ad_show`、广告奖励 `#ad_reward_claim`；事件字典见下方「事件字典」章节 | `:app/.../analytics/`、`core/domain/.../util/AnalyticsEvents.kt` |
| 广告收入 | **已实现（客户端模式）**：激励视频 onAdShow 上报 `#ad_show`（eCPM 为配置化估算分，运营从 ADN 数据报表更新）；TapDB 后台需开启「变现收入」扩展功能 | `:app/.../analytics/AdRevenueReporter.kt`、`AdRevenueConfig.kt` |
| A/B 测试 | **无** | 无代码 |
| 远程统计 | TapTap SDK 自带上报（登录/合规/时长）+ 游戏内事件埋点（TapDB，含总开关 `TapDBConfig.analyticsEnabled`） | `:app/.../taptap/`、`:app/.../analytics/` |

### 事件字典（TapDB 埋点，2026-08-15 建立）

> 唯一真相源 = `core/domain/.../core/util/AnalyticsEvents.kt` 常量。**新增事件必须同步三处**：
> ① 常量登记 ② 本字典登记 ③ TapDB 后台「事件管理」录入。守卫测试 `AnalyticsEventsDictionaryTest` 拦截漏登记。
> 自定义事件名以 `#` 开头（TapDB 规范）；`game_start`/`battle_end` 为历史兼容事件保持旧名。

| 事件名 | 触发点 | 属性 | 状态 |
|--------|--------|------|------|
| `#ad_show` | 激励视频 onAdShow（广告展示） | `#ad_union_type`/`#ad_placement_id`/`#ad_type`/`#ecpm`（分）/`currency_type` | TapDB 预置特殊事件（变现收入） |
| `#game_new_save` | GameActivity 新档首次进入 PLAYING | slot, sect_name | 自定义 |
| `#battle_first_win` | 引擎 `battle_end`(win) 首次（app 层 `FirstEventTracker` 去重） | enemy_type, turns, team_size | 自定义 |
| `#breakthrough_success` | 引擎 `DiscipleBreakthroughHandler` 突破成功 | realm, realm_layer, disciple_name | 自定义 |
| `#breakthrough_first` | 突破成功首次（`FirstEventTracker` 去重） | realm | 自定义 |
| `#ad_reward_claim` | 广告奖励验证通过（AdServiceImpl.onRewardVerify） | purpose, reward_name, reward_amount | 自定义 |
| `#storage_metrics_report` | 存储维护调度周期聚合（StorageMetricsReporter，30 分钟节拍） | storage_save_count, storage_load_count, storage_cache_hit_count, storage_cache_miss_count, storage_backup_failure_count, storage_backup_restore_count, storage_backup_skipped_count, storage_jade_drift_count, storage_change_log_pending, storage_archive_battle_log_rows, storage_archive_disciple_rows | 自定义 |
| `game_start` | GameActivity PLAYING（兼容旧事件） | sect_name, game_version | 兼容 |
| `battle_end` | 引擎 `CaveExplorationProcessor`（兼容旧事件） | outcome, enemy_type, turns, team_size | 兼容 |

**TapDB 预置事件（SDK 自动上报，无需代码）**：`device_login`（启动）、`user_login`（登录 setUser）、`play_game`（游玩时长，`TapDBManager.startGameDurationTracking`）、`charge`（充值，内购接入后自动生效）。

### 服务端接入契约（TapDB REST，2026-08-15 登记）

> 当前游戏**无自建后端**，服务端通道按 YAGNI 不建 HTTP 客户端（偿还触发条件见 docs/architecture.md 偿还触发条件档案 T-D46）。
> 未来接入后端/IAP 时按本契约实现 `TapDBServerReporter`（接口要点：OkHttp POST、注入式 HttpClient 便于测试、`runCatching` 静默降级）。

- **上报事件**：`POST https://e.tapdb.net/v2/event`，Content-Type: application/json
  - 请求体：`{"client_id": "<ClientID>", "device_id": "<TapTapEvent.getDeviceId()>", "user_id": "<与 setUser 相同的 userId>", "type": "track", "name": "<事件名>", "properties": {...}}`
  - `charge`（真实收入，按 `order_id` 去重）：`properties` 含 `amount`（单位分，映射 `charge_amount`）、`virtual_currency_amount`、`currency_type`、`product`、`#product_sku`、`#sub_payment`、`payment`、`ip`
  - `refund`：`properties` 含 `order_id`（去重）、`amount`、`virtual_currency_amount`、`currency_type`、`product`
  - `#ad_show`（广告变现收入，服务端模式）：`properties` 含 `#ad_union_type`/`#ad_placement_id`/`#ad_source_id`/`#ad_type`/`#ad_network`/`#ecpm`（必需，分）/`currency_type`（必需）
  - 响应码 200 = 接收成功；`client_id` 在 TapTap 开发者中心获取
- **在线人数**：`POST https://se.tapdb.net/tapdb/online`，请求体 `{"client_id": "<ClientID>", "onlines": [{"server": "s1", "online": 123, "timestamp": 秒级时间戳}]}`
  - ⚠️ TapDB 对同一服务器每个自然 5 分钟**仅接受一次**数据 → 必须由真后端聚合活跃设备后推送，**禁止客户端直报**（会覆盖为 1）
- **客户端/服务端二选一**：`#ad_show` 与 `charge` 客户端与服务端同时上报会重复统计；切换模式改 `TapDBConfig.adRevenueMode`（默认 CLIENT_ONLY）

### 留存手段清单

| 手段 | 现状 | 代码位置 |
|------|------|---------|
| 每日签到 | **已移除（2026-08-07）**——活动界面与每日签到整体移除；存档字段 `sign_in_state_json` 保留兼容旧档，禁止新代码读写 | — |
| 存档 | 手动存档（5 槽位）+ TapTap 云存档（slot 0 入口）+ 退出保存 + **现实墙钟节拍自动存档**（每 10 现实秒至多一次，`SaveTriggerFlag.realtimeTick` 门控；2026-09-27 §2.6 裁决，旧月变触发体系已删） | `SaveLoadViewModelAutoSaveOps.kt`、`TapCloudSaveManager.kt`、`SaveLoadSaveDelegate` |
| 活动入口 | "历战"卡片轮转（`LizhanDialog`：天道试炼/远古秘境已迁入）；活动界面已移除（2026-08-07） | `dialogs/LizhanDialog.kt` |
| 新手引导 | `GuideTask` 25 任务（12 种条件类型），计数器 12 处接入点 | `model/guide/`、`GameEngineGuideOps.kt` |
| 推送通知 | **无** | 无代码 |
| 离线收益 | **已落地（2026-09-28 B7）**——读档时按现实离线时段折算注入（≤12h 全额 → 12–24h 段 50% → 24h 硬顶，floor 到旬，回拨按 0），连续积分项离线照常累积、判定/事件零次；「云游归来」面板提示 | `GameConfig.Time.offlineGameMs`、`GameEngineCoreOfflineOps.kt`、`OfflineReturnFormatter.kt` |
| 回归奖励 | 无专用回归机制（仅白名单/节日邮件） | — |
| 游戏时间流速 | 权威时间轴 = 游戏毫秒 `elapsedGameMs`（现实时间 × speed 连续积分，未截断）；1 游戏月 = 6000 游戏毫秒（1x 下 6 现实秒，1 年 = 72 现实秒），日历年/月/旬是派生投影 | `GameConfig.Time` 常量栈、C++ `time_units.h`（双端守卫锁定）、`settlement.h` |

### 经济基线表（灵石源与汇初版）

> 用途：`rules/economy-design.md` 的审计基线。**每新增产出/消耗入口必须登记到本表**。数值档位见 `GameConfig.kt` 常量族（`THEFT_*`、`SALARY_*`、`SECT_LEVEL_*` 等）。

| 方向 | 入口 | 说明 | 代码位置 |
|------|------|------|---------|
| **产（源）** | 灵矿场 | 槽位数×产出率×游戏毫秒差分（B5 起毫秒时间戳），政策/长老影响；离线期间按毫秒差分照常结算（B7 注入） | `SpiritMineService`、C++ `accrueMonthlyContinuous`（灵矿差分）、`GameConfig` |
| 产（源） | 战斗掠夺 | 世界地图妖兽/洞府/秘境战利品 | `LootCalculator`、`BattleSystem` |
| 产（源） | 任务奖励 | 4 难度任务结算 | `CultivationEventMissionOps.kt` |
| 产（源） | 宗门交易/商人 | 出售物品/灵石商品 | `SectTradeDialog`、`MerchantAndRecruitService.kt` |
| 产（源） | 运营发放 | 兑换码/节日邮件/白名单 1000 万灵石邮件（每日签到已移除 2026-08-07） | `RedeemCodeService`、`BuiltinMailConfig` |
| 产（源） | 市场反馈 | 年度报告（`YearlyReport` 按来源拆分） | `BattleLogDialogs.kt` 的 `YearlyReportList` |
| 产（源） | ~~孕养丹退役补偿~~（装备重构 B2/R11 引入，**已随 SS0 删档重置退役**——规则与补偿邮件链删除，幂等标记 `GameData.nurturePillsRetired`(168) 字段保留防协议号漂移） | — | — |
| 产（源） | ~~旧装备折算补偿~~（装备重构 B3/R2 引入，**已随 SS0 删档重置退役**——规则/影子表读取器删除，幂等标记字段保留防协议号漂移） | — | — |
| 产（源） | **装备分解返还**（装备重构 B3，装备线新汇的配套回收） | 分解已升级装备返还 **50% 累计升级消耗**（灵石+兽材，向下取整；锁定/已穿戴拒绝分解）；T6 满级单件返还 783,000 灵石 + 19 兽材（测试断言锚）。返还率 50% 低于重取期望成本——抑制"分解/重取"循环放大波动（方案 R15） | `EquipmentUpgradeService`（core/engine）、C++ `equipment_tx.h`（ActionId 1487） |
| 耗（汇） | **装备升级消耗**（装备重构 B3/B4，装备线主消耗汇） | 公式：灵石 `100 × rarity² × level`/级 + 兽材 `max(1, floor(level/10))` 件/级；T6 单件 1→30 累计 **1,566,000 灵石 + 39 兽材**，一套六件满级 **9,396,000 灵石 + 234 兽材**；升级走 native 事务（ActionId 1486，先验后扣、失败臂零消费）。**月产出锚 `M` = 9,400,000 灵石/月**（`EquipmentEconomyCalibrationTest.STANDARD_MONTH_OUTPUT_AT_T6_STAGE`，T6 可穿阶段标准月画像，**拍板口径反推定锚、非测量值**，上线后以运营数据回标；量级交叉核对 = T6 物品市价 2,688 万 ≈ 2.9 个月产出）：满级一套 ÷ M = **0.9996** ∈ [0.75,1.25]（拍板 #4"一套满级 ≈ 1 个月"验收锚） | `EquipmentLevelSystem`/`EquipLevelCurve`（core/engine）、`EquipmentEconomyCalibrationTest` |
| 耗（汇） | **装备锻造**（装备重构 B3，装备线第二消耗汇） | 12 配方**天然定向**套装+部位（旧 73 配方退役）；品阶受职业等级上限钳制、T6 成功率 25%、**失败材料整耗**、主词条每炉重 roll；理想套（理想主词条+T6）期望 **88 次锻造**（头 8/身 16/手 12/脚 20/武 12/腿 20 单件；掉落链对照 11,389 件掉落——期望成本全景见方案 §九 I9）；与升级消耗 940 万灵石叠加构成装备线双汇，已可审计 | `ForgeRecipeDatabase`、`BuildingService` 锻造链、C++ `equipment_factory.h` |
| **耗（汇）** | 建造/拆除 | 建造扣灵石、一键拆除返还 50% | `PlaceBuildingUseCase.kt`、`GameEngineBuildingOps.kt` |
| 耗（汇） | 住所升级 | 初级住所→中级住所差价（单人 30000/多人 50000，目标造价-源造价动态计算，要求中型宗门） | `BuildingFacadeImpl.upgradeBuildings`、`BuildingUpgradeRegistry` |
| 耗（汇） | 生产投入 | 炼丹/锻造/种植材料 | `ProductionProcessor`、`AlchemySystem` |
| 耗（汇） | 突破/功法 | 突破消耗、藏经阁 | `DiscipleBreakthroughHandler`、`ManualDatabase` |
| 耗（汇） | 外交送礼 | 灵石档位 + 年份限制 | `GameEngineDiplomacyOps.kt`、`FavorConfig` |
| 耗（汇） | 月薪发放 | `SalaryConfig` 可配置 | `SalaryConfigDialog`、`CultivationEventProcessor` |
| 耗（汇） | 仙缘寻访 | 单抽/十连扣灵石（`GameConfig.Gacha.PRICE_PER_PULL`=5000/抽；保底 `PITY_PULL_THRESHOLD`=10 抽内必出单灵根角色碎片 `PITY_FRAGMENT_COUNT`=5，`pickMode=singleSpiritRoot`；普通角色碎片加权 1..5 片 30/20/20/20/10、物品加权 1..10 件钟形 2/4/9/15/20/20/15/9/4/2——池配置 `fragmentCountWeights`/`itemCountWeights` 单源，2026-09-30），结算在 C++ `gacha_tx.h` | `GachaFacade`、`GachaService`、`GameConfig.Gacha` |

### 玉符（氪金货币）经济登记与墙钟豁免论证（2026-08-07）

> 玉符独立于灵石体系：不占宗门仓库、无品阶、不走 `InventorySystem`/`OverflowMailSender`/`withTrackingSource`。

| 方向 | 入口 | 说明 | 代码位置 |
|------|------|------|---------|
| 产（源） | 在线时长 | 真实前台运行每满 10 分钟 1 枚（挂机/暂停累计、后台不累计），单日上限 20，墙钟次日 0 点重置（今日计数与周期累计时长均清零） | `JadeSymbolService.kt`、`GameConfig.Jade` |
| 耗（汇） | 玉符购买类玩法 | 见 `GameConfig.JadePurchase`（消耗即扣、账本落账） | `GameEngineJadePurchaseOps.kt`、`jade_tx.h` 六事务、`JadeSymbolService.deduct`（回退臂） |

**玉符账本模型（SS9：余额真源在 C++ `state.gameData.jadeLedger`）**：
1. **余额 = 期初条目 + Σdelta**——`jadeLedger` 是 append-only 流水（条目冗余 `balanceAfter` 供 O(1) 读末条），`jadeSymbols` 是账本求和的**派生缓存**，由 C++ 玉符事务（`appendLedgerEntry`）同事务双写；两者不一致时以账本为准重锚并经回执 `drift` 上报（`JadeSymbolService` 经 `PersistenceTelemetryPort` 计入 `StorageMetrics` 账本漂移计数，随 `#storage_metrics_report` 周期出报）。扣费/发放路径余额检查读账本末条 `balanceAfter`（O(1)），不遍历账本（防双花复活 + O(n) 退化）
2. **唯一写入入口**——native 臂可用时购买/发放走 C++ 事务落账（`jade_tx.h` 六事务）；降级回退臂经 `JadeSymbolService`（`deduct(state, cost, reason)` / `grantFromAd` / `settleGrants` 内部 `appendLedger`，append 条目 + 缓存跟随）。禁止在任何 Service/GameEngine 直接 `copy(jadeSymbols = ...)` / `copy(jadeLedger = ...)`，守卫测试 `JadeSymbolConsumptionGuardTest`（正则覆盖 `jadeSymbols` + `jadeLedger` 双字段，白名单 = `JadeSymbolService.kt` 玩法写 + `GameDataFieldPatch.kt` 镜像写）自动拦截
3. **消耗模式**（现存活参照：`GameEngineJadePurchaseOps` 商人刷新/购买系列）：`stateStore.updateAndReturn { 校验目标（先于扣费，达上限不扣玉符）→ deduct 失败 return Insufficient → 玩法逻辑（扣减成功后抽） }` → 成功后事务外 `publishJadeSymbolStateNow()`（清 1Hz 节流立即刷新徽章）；sealed 三态结果（Success/InsufficientJadeSymbols(current, required)/Error）；扣减失败不消耗 RNG 序列
4. **存档自愈例外**：`core/data` 的 `JadeSymbolNonNegativeRule`（order=23，启动时越界修正派生缓存）不经过服务——语义为数据修复而非玩家可触发的消耗/发放，不在守卫范围；其修复若致缓存与账本不一致，由下一次账本事务以账本为准重锚（账本为准语义）
5. **新档期初开账**：`withStartupLedger`（开局三臂单点）写 `OPENING_BALANCE` 期初条目（delta = 期初余额、余额不变）；C++ 导入侧对未开账态兜底（`ensureJadeLedgerOpeningEntry`）。派生余额 == 期初 + Σdelta 恒成立（守卫断言）

**玉符账本 reason 协议值**（C++ `jade_tx.h` 常量与 Kotlin `JadeLedgerReasons` 同名同步）：`OPENING_BALANCE`（新档/兜底开账）、`GRANT_TIME`（在线时长发放）、`GRANT_AD`（激励视频发放，含白名单直发——特权无上限语义保持，账本如实记录来源）、`SPEND_MERCHANT_REFRESH`（商人刷新扣费）、`SPEND_BREAKTHROUGH_BONUS`（突破率加成扣费）。玉符不走 `withTrackingSource`/`OverflowMailSender`（本表上方既有设计），reason 仅入账本、不对接邮件来源命名表。

**墙钟豁免论证（`rules/expansion-playbook.md` 第 7 项"进度锚定唯一权威时间轴"，原 L22"禁止以现实时间为准"条款已随 2026-09 实时结算改造改写——玉符豁免论证同步换锚：新条款约束的是进度结算轨道，玉符本就不是进度系统）**：玉符**不是进度系统**，是墙钟概念货币（对标商业游戏在线时长福利——原神月卡/星铁每日、放置类游戏挂机收益），与游戏内进度完全解耦：不参与游戏时间结算（不加速修炼/战斗/生产）、不产生任何游戏内收益、无离线收益、不进仓库、不参与排行榜。发放由单调时钟驱动（改墙钟无法加速，每枚仍需 10 分钟真实前台时间），仅跨天重置依赖墙钟。豁免理由：货币获取通道而非进度结算轨道。

**防作弊要点**（详见 `JadeSymbolService.kt` KDoc）：单调时钟差分累计（单 tick 裁剪 10s，OEM 挂起不补记）；墙钟 1s 节流采样 + 午夜锚点（回拨 `todayMidnight <= anchor` 不重置，回拨时跳过节流直接采样）；拿满冻结累计；旧档锚点 0 首次锚定无追溯发放；`SaveValidator` 的 `JadeSymbolNonNegativeRule`（order=23）钳制手改存档：accumMs 上限 `INTERVAL_MS - 1`（**严格小于发放阈值**，防"恰等于 10 分钟"读档首帧免费 +1 循环刷）、today 钳 `DAILY_CAP`、jadeSymbols 钳 `Int.MAX-DAILY_CAP`（防溢出回绕）。已知残余风险（书面接受）：快进-回拨循环可绕日上限，但时间产出率不可作弊；客户端本地货币持有量可被手改（无服务器权威校验，未来上商店须服务端校验）。

**线程模型约束（2026-08-07 对抗性审查根治）**：`stateStore.update` 有主线程运行时守卫（Debug `error()` / Release 静默丢弃）——玉符的 GameData 写入全部只发生在引擎线程：`onLoopStop()` 挂在游戏循环协程 finally（覆盖 cancel/emergencyRestart/正常退出全部停止路径），`onLoopStart()` 只读快照、跨天检查/首次锚定写入延迟到引擎线程首帧 tick；`checkpointNow()` 未启动（`lastSampleMs==0`）时跳过。

### iOS 跨平台可移植性基线

> 用途：`rules/code-quality.md` 跨平台章节的"现有基线"。游戏未来要做 iOS 端，本表记录当前已跨平台与 Android 独占的技术栈。

| 技术栈 | 现状 | iOS 可移植性 |
|--------|------|-------------|
| `:core:domain` | 零 Android 依赖（javax.inject + coroutines + kotlinx.serialization + room-common 注解） | ✅ 可移植（KMP 友好） |
| `:core:engine` | Kotlin 降级平台层/镜像 + C++ 引擎核心（game-core，JNI）；迁移主线收口（AUTHORITATIVE 真相源），Kotlin 侧仅 UI/平台/存档编码；音频已接口化（`AudioPlayerFacade`，G1 根治） | ⚠️ C++ 部分直接复用；JNI 需替换（iOS 用新桥/ObjC++） |
| C++ 引擎（game-core） | 纯 C++20 零 Android 依赖 + ECS/JobSystem 并行化（批 2026-09-10：每旬核心批次并行 + 战斗占领判定下沉） | ✅ 直接复用（桌面 GTest 已验证跨平台编译） |
| Compose UI（feature:game/app） | Jetpack Compose（Android 独占） | ⚠️ 需 Compose Multiplatform 或重写（评估见 docs/adr/compose-multiplatform-evaluation.md） |
| Room（core:data） | Room 2.7.0 | ⚠️ iOS 需 SQLDelight/原生 SQLite（评估见 docs/adr/sqlite-sqldelight-evaluation.md） |
| Hilt DI | Hilt 2.56 | ⚠️ iOS 需 Koin/手写 DI（评估见 docs/adr/di-abstraction-evaluation.md） |
| 渲染 | Vulkan（C++）+ `SoftwareCanvasBackend` 软件渲染双路径 | ✅ 软件渲染路径可跨平台；Vulkan 为 Android 独占（iOS 用 Metal 或软件渲染） |
| 序列化 | ProtoBuf + kotlinx.serialization + CBOR | ✅ 跨平台一致 |
| 存储 | MMKV 统一偏好（`KeyValueStore`/`GamePreferences`，G8 根治：DataStore 依赖已移除；豁免清单见 GamePreferences KDoc） | ✅ MMKV 跨平台 |
| 网络 | OkHttp（kotlinx.serialization 为唯一序列化栈，G7 根治：Gson 生产使用已清零） | ⚠️ iOS 需 Ktor 或保持接口抽象 |
| 崩溃上报 | `CrashReporter` 接口（core/domain）+ app 层 Bugly 实现（G3 根治） | ✅ 接口可复用（iOS 对等实现替换） |
| 音频 | `AudioPlayerFacade` 接口 + app 层 `AndroidAudioPlayer`（G1 根治） | ✅ 接口可复用（iOS 映射 AVAudioEngine/AVAudioPlayer） |
| 平台 SDK | TapTap（登录/云存档/广告） | ⚠️ iOS 需平台 SDK 对等实现（TapTap 有 iOS SDK） |
| API 守卫 | `Build.VERSION.SDK_INT` / `Build.SOC_*` 守卫模式 | ✅ 模式本身正确（Android 专用，iOS 无需） |

**规则：** 新增平台能力（时间/存储/网络/加密/通知/支付/广告/分享）一律接口抽象 + 平台实现（参照 `RemoteConfigProvider` / `AdService` 既有模式），禁止在 core 层直接使用 Android 独占 API。
