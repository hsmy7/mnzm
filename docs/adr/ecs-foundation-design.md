# ADR: ECS 数据导向基础（ecs-foundation-design）

> 状态：草稿（待行业对标研究集成后定稿）。日期：2026-09。
> 背景：用户已拍板"打好 ECS 基础"。本文档基于现有代码地基，给出可落地的 C++20 ECS 骨架设计。
> 【待集成】行业对标研究（Unity DOTS / EnTT / Flecs / Bevy / Overwatch GDC / Mike Acton）正在调研中，第 6 节的"对比与选型依据"待研究返回后填充定稿。

## 1. 背景与目标

### 1.1 现状（代码证据）
- 唯一的"数据导向"成果是 `gamecore/state/disciple_store.h` 的 **DiscipleStore SoA 列式存储**（~124 列，`std::map<std::string,std::size_t> idToRow` 索引，`materialize/appendDisciple/upsertDisciple/removeById/swapRows` 保序）。
- **非通用 ECS**：只服务**弟子**单一实体类型，硬编码 schema；没有 Entity 句柄、没有 Component 概念、没有 System 调度、没有 Archetype/SparseSet。
- 系统是"**过程式 system 函数操作大状态对象**"（见 `month_settlement.h`：8 步编排 + `SystemManager.onMonthlyEvent` 按 `@SystemPriority` 升序扇出 7 系统），**不是基于组件数组的 ECS 迭代**。
- 世界层（地图/建筑/地形/瓦片）**根本不在引擎里**——在 feature:game UI 层（`MainGameScreen`/`SectMapController`/`SectMapTileGenerator`），启动时 Kotlin 生成。
- 引擎**完全单线程**，无 JobSystem（全仓库无 `std::thread`/`std::async`/`ThreadPool`）。

### 1.2 目标
1. 建立一套**可持续扩展、数据导向、cache 友好**的 C++20 ECS 骨架，覆盖"多实体类型 + 组件连续存储 + 确定性 System 调度 + 为将来并行预留接口"。
2. **平滑演进**：与现有 `DiscipleStore` SoA 共存，不破坏 RNG 确定性红线与 JSON 协议零变更。
3. **避免"假 ECS"**：提供明确的检查清单，防止"名义 ECS、实际面向对象"。
4. 保持**纯 C++20、零 Android/iOS 依赖**，沿用 `core/platform.h` 端口注入模式。

### 1.3 成功标准
- 新增通用 ECS 模块可编译、可测试（GTest），在工程内通过"组件化弟子存储"验证迁移路径。
- 不改变现有存档 JSON 协议、不破坏确定性对拍。
- 明确定义"本作的 ECS 边界"（什么实体化、什么保持数据表）。

## 2. 技术方案

### 2.1 核心概念
| 概念 | 设计 |
|---|---|
| **Entity 句柄** | `EntityId{ uint32_t index; uint32_t generation; }`。`index` 为稠密数组下标，`generation` 防悬垂（复用现 `idToRow`+`swapRows` 保序思路）。 |
| **ComponentTag** | 每个组件一个编译期类型 tag（`static constexpr ComponentTypeId id`），用于类型注册/查询。 |
| **Component 存储** | 每组件一个 **SoA 列向量** + **SparseSet 索引**（稀疏 `vector<EntityId->denseIndex>` + 稠密 `denseEntities`）→ O(1) 实体↔行映射、cache 友好。复用 DiscipleStore 的"列向量 + 有序 map"模式。 |
| **Archetype vs 组件注册表** | **首期采用"组件注册表"**（entity 持 presence 位图 + 每组件 SoA）。理由：单实体类型（弟子）为主、实体数量大但类型少；Archetype/Chunk 为多实体异构类型的高阶方案，留作 v2。 |
| **EntityManager** | 分配/回收实体（freelist + generation 递增），维护 index→entity、entity→component presence。 |
| **System** | 纯函数式/无状态的系统对象，持 `View`（只读/读写组件子集）；**注册时声明依赖与优先级**，调度器按优先级串行执行（确定性），接口预留 `scheduleOn(jobGraph)` 为并行 job 化埋点。 |
| **Query** | 按 ComponentSet 过滤实体（`view<A,B>()`），返回连续行区间供迭代，批处理 cache 友好。 |

### 2.2 数据流
```
GameState (GameData, 确定性真相源)
   └─ EntityManager 持有所有实体
        ├─ disciples: 组件集合 (DiscipleStore 的列迁移为组件/或映射到 DiscipleStore)
        ├─ buildings: 未来 (建筑组件: Transform/Footprint/BuildingType)
        ├─ npc/beast: 未来 (Transform/AI/动画)
   SettlementEngine (现有) 触发 System 调度
        SystemScheduler.reschedule(priority) → 按 PriorityQueue 串行 run
             System::run(WorldView& w) → query<CompA,CompB>() 迭代 → 批处理
   ▸ 确定性红线：系统迭代序 == 实体行序（RNG 对拍命门），禁止重排。
```

### 2.3 与现有 DiscipleStore 的演进（关键决策）
- **方案 A（推荐）：保留 DiscipleStore SoA 为"弟子组件"的唯一权威存储**，ECS 提供 `EntityId → DiscipleStore.row` 的映射层与"组件视图迭代"。原因：DiscipleStore 已承载 RNG 红线（行序）、确定性、JSON 协议零变更；**直接改造成组件会大动干戈并触碰存档**。
- 方案 B：将 DiscipleStore 拆成 N 个独立组件 SoA（每个 `vector` 一个组件）。更"纯 ECS"，但**与大改 DiscipleStore API/`materialize`/对拍**挂钩，风险高。
- **结论：首期落地"通用 ECS 骨架 + 弟子经实体映射接入"，把 DiscipleStore 列视作"弟子组件集"；等世界实体（建筑/NPC）需要异构实体时再升级 Archetype。**

### 2.4 世界层（地图/建筑）
- **当前状态**：地图/建筑/瓦片在 feature:game UI 层 Kotlin，**不在引擎**。
- **决策点（需人工评审）**：是否把建筑/地形/NPC 纳入 ECS World？
  - 若纳入：需要把"建筑占格、位置、类型"从 Kotlin buildingData 迁入引擎 ECS，做 Tile Occupancy 系统（空间索引）。
  - 若不纳入：保持"弟子为实体、建筑为 Kotlin 渲染数据"，ECS 只服务弟子/逻辑实体。
  - **建议**：首期只把"弟子（逻辑实体）"实体化；建筑/地形渲染数据保持在渲染链路（RenderFrame），**不急于迁入 ECS**，避免大范围重构。建筑占格校验可用独立 GridSystem（Kotlin）维持。

### 2.5 建议的文件/接口骨架（gamecore/ecs/）
```
gamecore/include/gamecore/ecs/
  entity.h          EntityId, EntityManager, EntityHandle(类型安全)
  component.h       ComponentTypeId, ComponentTraits
  storage.h         ComponentStorage(SoA+Index), SparseSet
  registry.h        ComponentRegistry, entity→presence
  view.h            ComponentView(只读/写), archetype query (首期=组件集合)
  system.h          System 基类 + SystemScheduler(priority) + job hook
  world.h           World(EntityManager+Registry), WorldView
```

## 3. 影响范围清单

| 文件 | 变更类型 | 说明 |
|---|---|---|
| `gamecore/include/gamecore/ecs/*`（新增） | 新增 | 通用 ECS 骨架（如上）。 |
| `gamecore/include/gamecore/state/disciple_store.h` | 保持/适配 | 保留 SoA 权威；可选加 EntityId↔row 映射层。 |
| `gamecore/src/ecs/*`（新增） | 新增 | ECS 实现。 |
| `gamecore/test/ecs_*_test.cpp`（新增） | 新增 | GTest：entity 复用/悬垂、storage 插入删除、query 迭代、system 调度确定性。 |
| `gamecore/include/gamecore/system/*` | 保留 | 现有系统暂不改，作为"过程式"基线对照；后续逐步迁移到 ECS System。 |
| `gamecore/CMakeLists.txt` | 修改 | 接入新源文件 + 测试。 |
| `docs/cpp-engine.md` / `docs/architecture.md` | 修改 | 记录 ECS 边界决策与进度。 |

> 注意：**不改 `state/models.h`、`json_codec.*`、`rng/*`、`state/disciple_store` 的 JSON 协议**——存档零变更。

## 4. 兼容性分析
- **存档/序列化**：ECS 骨架不触碰 `json_codec`/`rngStates`/kotlinx 协议；DiscipleStore SoA JSON 协议零变更。**无 Migration。**
- **确定性**：ECS 只提供实体/组件迭代；RNG 抽取序仍由现有系统保持（System 迭代序 == 实体行序红线）。**对拍不受影响。**
- **Android/iOS**：`gamecore/ecs/*` 纯 C++20，零平台依赖，桌面/iOS 可编译。

## 5. 测试方案
- GTest：`EntityManager`（创建/复用/回收/generation 悬挂）、`ComponentStorage`（SoA 插入/removeById/swapRows 保序）、`SystemScheduler`（优先级、串行确定性）、`Query`（组件子集过滤、连续迭代）。
- 确定性守护：`DiffEcsTest`（跨语言行为不变：弟子迭代序与现有 SoA 一致）。
- 回归：现有 GTest 722 全绿；engine JUnit 对拍 0 skip。

## 6. 风险评估与兜底
- **风险 1：过度设计导致"为 ECS 而 ECS"**。兜底：明确"本作 ECS 边界"（弟子=实体；建筑/地形=渲染数据），不硬造世界实体。
- **风险 2：改造 DiscipleStore 触碰 RNG 红线/存档**。兜底：**不在首期改 DiscipleStore 的内部列结构**，ECS 作为其上叠加的"实体+组件视图"层。
- **风险 3：并行化的冲动**。兜底：ECS System 调度**先串行**（确定性是命门），只预留 job hook 接口，不贪图先上并行。

## 7. 未来场景推演（≥6 个月）
- 规模增长（5 千弟子）：ECS 组件 SoA + 批处理 system 使每旬 O(D) 更 cache 友好；再叠加 JobSystem 可并行化独立 system（战斗/探索/内政）。
- 平台扩张：ECS 无平台依赖，iOS 直接复用。
- 玩法扩展：新增"建筑实体 + Tile Occupancy + 空间索引"可平滑纳入（v2 Archetype 或组件注册表扩展）。
- 运营演进：无需改 ECS。

## 8. 技术债与偿还计划
| 债项 | 偿还触发 |
|---|---|
| "组件注册表"而非 Archetype/Chunk | 需要多实体异构类型（建筑/地形/NPC 同 world）时升级 |
| 无并行 JobSystem | 需要 5 千弟子并发 system 时，先建 JobSystem 再并行化独立 system |
| 弟子仍经 DiscipleStore 映射而非纯组件 | 若弟子需要加入"世界"（位置/空间）或与其他实体组合时，再拆为独立组件 |

## 9. 行业对标研究（已集成）
> 主流数据导向/ECS 做法的实证结论（详见 render-strategy-decision.md 第 9 节完整来源清单，此处为 ECS 相关提炼）。

### 9.1 主流 ECS 做法对比
- **Unity DOTS（Entities）**：Archetype + Chunk 内存布局（按组件集分桶、连续存储），配合 Burst 编译器 + JobSystem 并行——**"组件集同桶"是其性能核心**。
- **EnTT / Flecs**：EnTT 以 **SparseSet（稀疏/稠密双数组）** 为核心，支持按组件集查询、动态类型、层级；Flecs 是 C/C++ ECS，强调**关系、可观测性、与并行调度**（Stages）。
- **Bevy（Rust）**：Repr 数据导向 + 组件以 `TypeId` 索引 + 调度器支持确定性并行/串行。
- **共性**：Entity 是 ID 不是对象；Component 是纯数据连续存储（SoA/SparseSet/Archetype）；System 是批量迭代 + 可并行调度。
- **Overwatch GDC17**：真实大项目验证"数据导向 + 实体组件"在网游中的价值，但也不做激进纯 ECS（保留部分对象语义）。

### 9.2 本作选型依据
| 方案 | 适用 | 本作取舍 |
|---|---|---|
| **SparseSet 组件注册表（本文档 2.1 采用）** | 实体类型少、量大（弟子为主） | ✅ 首期采用：连续 SoA + O(1) 查询 + 确定性；复用现有 DiscipleStore 思路 |
| **Archetype/Chunk** | 多实体异构类型同 world | 留作 v2：建筑/地形/NPC 需异构实体时升级 |
| **JOB/并行调度** | 需要 5 千弟子并发 system | 预留接口；先串行保确定性，再建 JobSystem |

### 9.3 来源（ECS 相关，详见 render-strategy-decision.md 第 9 节清单 #9–#15）
Unity DOTS《ECS concepts》、Overwatch GDC17、EnTT、Flecs、Bevy、Andrew Kelley《Practical Data-Oriented Design》、Habr archetypes 综述。

### 附：避免"假 ECS"检查清单（起草）
- Entity 是 ID 不是对象；Component 是数据不是行为；System 是批量迭代不是单对象方法。
- 组件连续存储（SoA/SparseSet），非每实体一个复杂对象。
- 无大量 new/delete 于热路径；迭代无虚函数调用。
- System 顺序确定性、可为并行预留；实体生命周期有 generation 防悬垂。
- 有 query/迭代 API；不是"纯函数操作大状态对象"换名。
