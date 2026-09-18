# 自研引擎重构级方案（2026-09-17）

> 依据：2026-09-17 全面技术审计（主循环/ECS/地图/渲染/JNI/内存/多线程/伪完成六线审计）
> 与主流游戏差距分析。本方案只做**重构与收口**，不含新玩法系统。
> 审计基线：AUTHORITATIVE 稳态（C++ 权威 + Kotlin 回退臂并存），
> 生产默认 flag = AUTHORITATIVE（`NativeEngineFlag.kt:38`）。

---

## 0. 范围声明

### 0.1 明确不做（本方案边界外）

| 项 | 理由 |
|---|---|
| NPC 行走动画 / 世界实体渲染演出 | 用户明确推迟。R3 的 SceneStore 为其预留同构通道（建筑/作物即"无动画实体"），但不实施 |
| 战斗可视化演出 | 依赖实体渲染，随上项推迟。**注意：其帧同步浮层需求（飘血/连击）不在此列**——由 R3.8 原生浮层与文本通道承接（本方案内实施） |
| 引擎自绘 UI 替换 Compose | 新子系统而非重构；Compose 收窄为菜单/弹窗后双栈成本可接受，单独立项评估 |
| 音频系统 C++ 化 | 新建子系统，非重构 |
| 网络 / live-ops / 热更 / 脚本层 | 产品级决策，非工程重构 |
| iOS Metal 后端实施 | 本方案只完成其**前置条件**（R3/R5/R6.1），Metal 开发另立项 |

### 0.2 方案目标（可度量）

| # | 度量 | 基线（审计实测/推断） | 目标 |
|---|---|---|---|
| G1 | 每旬结算堆分配次数（5000 弟子） | ~15 万次（materialize + map 重建） | **< 1 万次** |
| G2 | 每旬镜像耗时（PhaseSegmentTimer mirror 段） | 阈值告警线 100ms（WS-1 悬置） | **< 10ms**，关闭 WS-1 |
| G3 | 稳态每帧 JNI 传输字节（相机移动帧） | ≥128KB（tileData+roadData+UV 表） | **< 200B**（相机 6 标量+overlay 标志） |
| G4 | 放置模式每帧 JNI 次数 / vkCmdDraw | 最坏 ~300 / 数十 | **< 10 / < 15** |
| G5 | Kotlin 回退臂数量（battle/executor 系） | 战斗+秘境+探索+3 个 Executor 全保留 | **归零**（仅存 golden 测试夹具） |
| G6 | 跨语言逐位一致性工程锁定 | FP 收缩未钉死（仅靠测试巧合） | **编译器选项 + arm64 CI 对拍双锁** |
| G7 | 发布阻断项 | 证书 pin 占位（release 必崩或失效） | **清零** |

---

## 1. 现状基线（审计锚点）

架构现状（简化）：

```
Compose(UI+场景装配) ──JSON镜像──◀ GameCore(C++ 权威模拟) 
      │                              ▲ 每帧17槽LoopFrame计划
      ▼ RenderFrame(全图数组)        └─ Kotlin引擎线程驱动
NativeRenderer(Kotlin线程) ──每帧immediate-mode JNI──▶ VulkanBackend(顶点工厂)
```

关键病灶（file:line 见审计报告）：

1. **形状锁**：权威已翻转（w3-13 删反向通道，Kotlin 只读），但 C++ 仍逐位复制
   Kotlin 算法形状——每旬全量 materialize（`phase_settlement.h:1389,1425`）、
   每实体重建 `std::map<string,EquipmentInstance>`（`phase_settlement.h:159-181`）、
   数字 ID 走 `std::map<std::string>`。
2. **JSON 状态镜像**：每旬 nlohmann dump→双拷贝→kotlinx parse 整 GameState；
   protobuf 基建已在仓库（app 插件 + core/engine/src/main/proto）却未用于热路径；
   DirtyTracker 仍是全量序列化+树 diff。
3. **渲染器=顶点工厂**：场景真相在 Kotlin（`MainGameScreen.kt:491` 在 remember
   里烘焙建筑进瓦片副本），每帧整包数组过 JNI；gamecore 自持 terrain 权威却
   不被渲染消费；两个 .so 无共享场景表示；网格线 258 次 drawRect/帧。
4. **双实现稳态**：遭遇战/秘境/探索纯 Kotlin 生产（`EncounterBattleService.kt:168,280`），
   native 战斗通道已建成未接线；残留执行器解析 JSON 信封做平台效应。
5. **确定性未锁**：CMake 无 `-ffp-contract=off`，arm64 clang 默认可融合乘加，
   对拍仅在 x86/MSVC 桌面跑——浮点位一致是"没漂移"而非"不会漂移"。
6. **发布阻断**：证书 pin 全占位（`CertificatePinnerProvider.kt:64-95`）、
   API 域名占位、遥测全是"待凭证"脚手架。

---

## 2. 目标架构

```
Compose(菜单/弹窗/HUD) ◀──GameView(protobuf 投影+事件流)──◀ GameCore(C++ 权威模拟+循环判据)
                                                                    ▲ 平台效应适配器(邮件/Room/通知)
NativeRenderer(薄驱动) ──drawFrame(相机+overlay标志)──▶ SceneStore(C++ 场景真相)
      ▲                                                     ▲ 脏diff更新(建筑/作物/道路/云)
      └─ Kotlin 仅剩:线程/帧率/OEM对抗/ADPF(平台机制)          └─ 与 gamecore terrain 单一来源
VulkanBackend / GlesBackend(消费 SceneStore,含C++侧网格/高亮/预览生成)
```

职责终态：

| 层 | Kotlin 保留 | C++ 接管 |
|---|---|---|
| 模拟 | 平台效应适配器（邮件/Room/通知） | 全部结算 + 战斗全通道 + 探索生产 |
| 状态 | GameView 投影消费（UI 专用） | 权威 GameState（唯一份热状态） |
| 场景 | 触摸/相机意图、放置意图提交 | SceneStore 真相 + 全部 overlay 几何生成 |
| 循环 | 线程本体/帧率策略/OEM 对抗/ADPF | 判据状态机（维持现状；R5 条件项再议） |
| 资产 | 触发上传/生命周期 | 图集数据（离线产物）+ UV 常量 |

---

## 3. 分阶段方案

### R0 发布阻断与确定性护栏（~1 周，立即）

| 项 | 内容 | 验收 |
|---|---|---|
| R0.1 证书固定 | `CertificatePinnerProvider` 占位 pin 处理：新增 Gradle 校验任务，release 构建遇全占位 pin 即 fail（把现有运行时 IllegalStateException 前移到构建期）；`NetworkSecurityConfig` 占位域名同步清点 | 无凭证可出 release（pinning 显式降级为 debug-only），有凭证时构建期强制真实值 |
| R0.2 FP 钉死 | gamecore CMakeLists 加 `-ffp-contract=off`（并对齐 MSVC `/fp:precise`，桌面桥同样）；新增 arm64 真机对拍 CI（Firebase Test Lab 配置已在仓库：`firebase-test-lab-config.yml`，跑 DiffAuthoritativeTickTest 子集） | arm64 对拍绿；浮点位一致由编译器保证而非测试巧合 |
| R0.3 精灵容量悬崖 | `MAX_SPRITES_PER_FRAME` 溢出从静默丢弃改为：溢出计数进 RenderMetrics 遥测 + 超限时策略化降级（先跳装饰层→再截断，与现有 scale<0.6 LOD 门控同机制） | 溢出可观测、降级有序 |
| R0.4 存档静默空档 | `StorageEngine.kt:541-543` 异常路径回填空 SaveSlot 改为显式 error 态（UI 区分"空档"与"读取失败"） | 损坏不再伪装成空档 |
| R0.5 遥测最小闭环 | 接通已预留的崩溃上报（凭证就绪前至少 debug 通道 + native 符号上传流程演练） | 内测发行的数据闭环成立 |

### R1 解锁形状锁——C++ 内部数据布局优化（~3 周，无依赖，可先行）

**原则：diff 对拍测试是本阶段唯一且充分的正确性标准。只改形状，不改结果。**

| 项 | 内容 | 说明 |
|---|---|---|
| R1.1 | `isFullHpMp` 的 map 重建从**每实体**提升到**每步骤入口** | `phase_settlement.h:159-181` 两个重载。注意不能无脑提循环外：注释明示"孕养升级当旬的候选判定依赖最新 nurtureLevel"。精确改法：突破候选筛选（步骤 7）在核心批次（步骤 1-5 含孕养）**之后**执行——在步骤 7 入口构建一次 map 传入，即保住"当旬最新"语义又消 D-1 次重建。同文件 :1315 核心批次已示范该模式 |
| R1.2 | `committedDisciples` 去物化 | 突破只需"长老悟性 committed 视图"少量字段。改法：候选筛选先在 SoA 列上判定（R1.1 后已便宜），仅对命中候选的弟子物化。每旬 D 次深拷贝 → 候选数次 |
| R1.3 | 字符串键 → dense 索引 | DiscipleStore 增 numeric id 列（ids 本是数字串）；equipment/manual 映射改 owner 行索引桶式存储。分两步：先建索引逐点替换，`idToRow` string 键仅保留在协议边界 |
| R1.4 | DirtyTracker 列级写屏障 | 各 SoA 列 dirty 位图 + 集合 tombstone，导出仅序列化脏列。R2 的前置 |
| R1.5 | `getMaxHpMp` 临时 map 消除 | `disciple_stats.h:289`，随 R1.1 同一改造 |
| R1.6 | `destroyDiscipleEntities` 去 O(D²) | eraseEntity 增加 swap-and-pop 变体供重建场景使用（确定性迭代序仅在需要对拍的路径保留保序版本） |

**验收**：全量 Diff 测试绿；新增 bench（malloc 计数 + 耗时）证明 G1。
**回滚**：每子项独立 commit，纯内部改动。

### R2 状态同步协议重构——JSON 镜像 → protobuf 视图契约（~4 周，依赖 R1.4）

| 项 | 内容 |
|---|---|
| R2.1 | 定义 `GameView` proto：resourcesHeader / discipleListDelta（行级增量）/ eventFeed（月年结算、突破、死亡、购买、秘境关闭）/ configEcho。**选型 protobuf**：基建已在（app 插件 + proto 目录），每旬级频率不需要零拷贝；flatbuffers 不引 |
| R2.2 | `nativeExportDirty` 产出 protobuf 信封；StateSyncService 解码换 protobuf。**存档格式不动**（仍 JSON，低频路径，兼容优先）——`nativeExportState` 全量 JSON 仅保留给存档/rebaseline |
| R2.3 | UI 消费面收窄（分两波）：第一波只换传输（镜像仍全量、二进制）；第二波镜像瘦身——GameStateStore 全量 replaceAll 退役，新增 GameViewStore 投影态，ViewModel 逐块迁移 |
| R2.4 | 月/年 JSON"信封"并入 eventFeed；**残留执行器退化为纯平台效应适配器**（发邮件/写 Room/通知），不再解析 JSON |

**验收**：PhaseSegmentTimer mirror 段 < 10ms@5000 弟子（G2）；Kotlin 每旬 GC 分配显著下降；WS-1 关闭。
**风险**：schema 演进（proto 字段只增不改）；R2.3 第二波迁移面大，双轨过渡。

### R3 渲染场景所有权下沉——顶点工厂 → 场景持有者（~6 周，本方案核心结构改动）

| 项 | 内容 |
|---|---|
| R3.1 | 新建 C++ `SceneStore`（native-renderer 内新模块）：持有 terrain（与 gamecore 单一来源，初始化时导入一次）、road mask、建筑集、作物集、崖壁布局（现为 Kotlin 预计算的稳定 FloatArray，下沉后 IslandCliffBridge 变薄）、云实例 |
| R3.2 | JNI 面重构：废弃 `drawAllTiles(17 参数全量数组)` → `sceneSetTerrain(v)`（一次性）+ `sceneUpdateBuildings(diff)` + `sceneUpdateCrops(progress)` + `sceneUpdateRoads(diff)` + `drawFrame(camera, overlayFlags)`。UV 常量表由 build-atlas.mjs 同源生成进 C++（现仅生成 TextureAtlas.h），Kotlin 不再每帧传 SpriteAtlasDef 数组 |
| R3.3 | overlay 几何全部 C++ 生成：网格线/放置预览框/选中/拆除高亮由 drawFrame 的模式标志驱动（消 258 次 drawRect JNI；选中索引与合法性数据随 building diff 同步） |
| R3.4 | 脏更新协议：游戏状态变化才推 diff（RenderCommandBus 语义平移至 C++ 场景更新）；相机变化仅传标量。渲染线程脏帧跳过机制保留 |
| R3.5 | 容量策略：整岛可见（缩小）走"远景观看"路径——chunk 级合并/烘焙 quad 或复活 GROUND_QUAD REPEAT 路径（`NativeBridge.cpp:860` 现编译期关闭，注释指向 Adreno 驱动问题，需带黑名单地验证）；溢出遥测接 R0.3 |
| R3.6 | Canvas 兜底路径**不动**（保留 Kotlin 侧数据流，显式标注为兜底专属技术债；避免为 1% 机型扩大改动面）。GLES 路径随 R3.2 同步改造（消费同一 SceneStore） |
| R3.7 | （仅设计预留，不实施）SceneStore 的实体通道与建筑/作物同构，为未来 NPC/战斗演出留位 |
| R3.8 | **原生浮层与文本通道**（承接战斗演出的帧同步浮层需求，见 §3.1 治理规则）：① 文本渲染分两档——Tier1（本方案实施）= 数字/拉丁字形图集 + 固定词条（"会心/格挡/连击"等有限游戏术语）**预烘焙为 sprite 资产**，复用现有 KTX/ASTC 图集管线，规避整套动态字体系统；Tier2（推迟）= 动态字形缓存（任意字符串：名牌/聊天气泡），需求出现再立项。② 浮字对象池（固定容量 ~256 实例）：世界空间锚定 + 相机变换走既有 push-constant 投影；上浮/淡出/暴击弹跳动画全部 C++ 时间驱动——**零每帧 JNI**；生成走事件驱动（`sceneSpawnFloatingText`，低频）。③ 该通道同时服务未来：名牌、世界锚定 Boss 条、连击计数器。| 约 +1~1.5 周 |

**验收**：G3/G4；截图回归测试（VK/GLES 双后端像素一致性已有测试设施，扩展为场景回归集）。
**风险**：行为等价性最难的一步——护栏=三后端一致性测试 + 截图回归 + 灰度开关（新旧 drawAllTiles 路径共存一个版本周期）。

#### §3.1 治理规则：世界视觉归 native（防长痛积累）

判定标准——一个视觉元素满足以下任一条，**必须**走 native 渲染通道（SceneStore/R3.8 浮层），禁止新建在 Compose：

1. 需要与世界坐标锚定（跟随地图/实体位置移动）；
2. 需要与渲染帧同步更新（≥30Hz 动效：飘字、连击、进度环、高亮呼吸）；
3. 需要与相机变换一致（视差、缩放补偿）。

静态菜单/弹窗/列表/文本输入继续 Compose。规则目的：确保未来任何帧同步需求出现时只有一处实现位置，Compose 面不再增长，替换成本单调不增。

### R4 纵切域接管与 Kotlin 臂删除（持续进行，按风险排队）

统一流程：**迁移 → 对拍 → flag 灰度 → 删臂 → diff 测试转 golden 快照**。

| 序 | 域 | 内容 | 备注 |
|---|---|---|---|
| R4.1 | 遭遇战 | `EncounterBattleService` 路由 `nativeBattleExecute`/`nativeAiBattleExecute`——**通道已建成未接线**（`GameCoreBridge.kt:190,203`），最低垂果实，可立即做，不依赖 R1-R3 | PvP/PvE 两条路径（`:168,:280`） |
| R4.2 | 秘境战斗 | `SecretRealmService.executeBattleWithTimeout` 切 native；会话管理/UI/邮件/暂停租约留 Kotlin（平台域） | 1291 行服务拆出战斗执行 |
| R4.3 | 探索/巡逻生产 | `ExplorationService` 结果生产评估下沉（AI 兽战处理器已是 native 优先模式） | |
| R4.4 | RNG 分区独立 | 残留执行器分配独立 RngPartition，Kotlin 本地 PCG 实例（seed 随分区 init，存档 rngStates 无键则重播——kAiSectMirror 同模式有先例）。**消 per-roll JNI**（`NativeBackedRng.kt:46`）。对拍测试相应重定基线 | 权威翻转后允许 |

**验收**：G5——`BattleSystem.kt`、`PhaseSettlementExecutor`、`MonthSettlementExecutor`（完整版）、`YearSettlementExecutor`（完整版）退场为 golden 夹具；"双实现并行契约"注释清零。
**风险**：删臂后回退能力丧失——每域保留一个版本的灰度 flag 再物理删除。

### R5 循环归属 C++（条件项：iOS 立项才启动）

- C++ 引擎线程拥有循环（平台 ticker 注入：Android 侧仅注入防冻结 hook/帧率策略回调）。
- 消除每帧 17 槽 LongArray 计划与 owner 重锚机制。
- **诚实评估**：Android-only 时收益中等（省 1 次 JNI/帧、删一批状态机复杂度），OEM 对抗逻辑仍需平台协作——故设为 iOS 前置条件而非独立价值项，不默认排期。

### R6 资产与内容管线（部分纳入）

| 项 | 内容 | 属性 |
|---|---|---|
| R6.1 | 图集离线化：运行时 Canvas 拼图集（`SectAtlasAssembler`）→ build-atlas.mjs 直接产出 KTX/ASTC 包，运行时只 upload。消启动 Canvas 依赖与内存尖峰；**iOS 前置** | 纳入，~2 周 |
| R6.2 | 数值外置：C++ 头文件 DB（herb/equipment/recipe/trait 等）改数据文件加载（`nativeSetGameConfig` 通道扩展）。改数值不再触发逻辑重编译 | 纳入，~2 周 |
| — | 热更/脚本层 | 出范围（产品决策） |

### CI 与度量执法（贯穿各阶段）

1. 新增 bench 并入门禁（沿用 kover 开关模式：本地可关、CI 必跑）：每旬结算 e2e、mirror 通道、scene 更新后渲染帧预算。
2. arm64 真机对拍周期跑（R0.2 建立的 Test Lab 通道）。
3. 静态门禁：JNI 面计数不增（脚本比对 external fun 总数）；gamecore 平台纯度 gate（grep `#include <android` ——约定已有、无执法）。

---

## 4. 排期与依赖

```
周:  1    2-4         5-8          9-14          15+
     R0 ─ R1 ──────── R2 ────────  R3(含R3.8浮层)─ R4(持续至臂清零)
          └─ R4.1 随时可摘（无依赖）
               R1.4 ──▶ R2
               R6.1/R6.2 可与 R2 并行（不同人/不同文件族）
     R5：iOS 立项触发；前置 = R3 + R6.1
```

单人节奏约 3.5~4 个月；R4.1（遭遇战接线）建议本周即做。

---

## 5. 风险登记册

| 风险 | 等级 | 缓解 |
|---|---|---|
| R1 形状改动静默改变结算结果 | 中 | 全量 Diff 对拍是安全网；每子项独立 commit 可回滚 |
| R2/R3 行为等价性（渲染像素、UI 状态） | 高 | 截图回归基建（R3 验收前置）；新旧路径灰度共存一个版本 |
| R2.3 UI 消费面迁移量大 | 中 | 双轨过渡（先换传输后瘦身）；投影缺失字段时 fail-fast 而非静默空 |
| protobuf 依赖体积/构建复杂度 | 低 | 仅 mirror 通道；存档仍 JSON |
| R4 删臂后无回退 | 中 | 灰度 flag 保留一个版本周期；golden 快照留存行为基线 |
| RNG 分区重定基线改变老档行为 | 中 | 与 kAiSectMirror 同模式（无键重播）；对拍测试同步调整；变更写入存档版本说明 |
| ARM64 FP 行为差异已在线上发生而未被察觉 | 低 | R0.2 先行验证：pin 前后各跑一次全量对拍，如有差异即已存在，按 bug 处理而非阻塞本方案 |

---

## 6. 与既有工作的关系

- 本方案不推翻既有迁移成果：gamecore 权威、对拍基建、三级渲染降级链、OEM 对抗全部保留并复用。
- W4 已做的死代码清理/守卫收口与本方案正交。
- `docs/adr/reverse-channel-elimination.md`（w3-13）确立的"Kotlin 只读"是 R1 合法性的来源：**形状锁自权威翻转起已无契约依据，本方案只是兑现它**。

---

## 7. 实施状态（滚动更新）

### 7.1 首批落地（2026-09-17）：R0 全部 + R4.1

按 §4 排期（R0 立即 + R4.1 无依赖）实施完毕，验证口径见 CHANGELOG 4.01.14 段。

| 项 | 状态 | 关键落点 |
|---|---|---|
| R0.1 | ✅ | pin 声明收敛 `NetworkSecurityConfig.pinnedHostPins`；构建任务 `validateCertificatePins`（release 门禁）+ `api.properties CERT_PINNING_ENFORCED`；运行时占位 pin 显式降级不崩溃 |
| R0.2 | ✅ | gamecore CMake `-ffp-contract=off`（PUBLIC）+ 桌面桥双脚本；探针 `gamecore/determinism_probe.h` 双腿（桌面 GTest golden `0x490e8dc522e12921` / 真机 `nativeFpDeterminismProbe`）；workflow `arm64-fp-determinism.yml`（待配 `FIREBASE_SERVICE_ACCOUNT` secret 后生效）；携旗标全量 GTest 1419 绿 = pin 后无漂移 |
| R0.3 | ✅ | C++ 溢出累计遥测 + 有序降级（先跳装饰层 30 帧滞后恢复）+ `nativeGetSpriteOverflowStats` → `RenderMetrics` 三计数器 + Snapshot 扩展 |
| R0.4 | ✅ | `SaveSlot.isLoadError` 三态 + `getSaveSlots` 异常路径改 error 态 + `SaveSelectScreen` 红字/LOAD 不可点/新建走覆盖确认 |
| R0.5 | ✅ | `CrashHandler.uploadPendingCrashLogs` 启动积压重传 + 静默 catch 清偿；Bugly APP_ID 已配置（api.properties），凭证链路就绪 |
| R4.1 | ✅ | `EncounterBattleService` 两阶段经 `BattleExecutionRouter` → `nativeBattleExecute`，回退臂保留 |

登记的后续衔接项（属方案既有条目，非新债）：
- §"CI 与度量执法"：JNI 面计数不增静态门禁尚未建设（R0.2 探针 +1 已在 CHANGELOG 登记豁免理由）；
- `arm64-fp-determinism.yml` 需仓库 Secrets 配置 Firebase 服务账号后方可周期执行；
- R4.2–R4.4 / R1–R3 按 §4 排期推进；R4.1 的删臂（BattleSystem 转 golden 夹具）按 R4 统一流程在灰度一个版本周期后执行。

### 7.2 R1 逐批落地（2026-09-17/18）

#### B01 批（2026-09-17）= R1.1 + R1.5（map 重建提升到步骤入口）

批次文件 `docs/parallel-batches-w5/batch-R1A.md`；每子项独立 commit（01849d8b4 / ad5ca62ee），
只改形状不改结果。

| 项 | 状态 | 关键落点 |
|---|---|---|
| R1.1 | ✅ | `isFullHpMp` 两重载（`phase_settlement.h`）去逐实体现场重建，签名改 `gd + eqMap + mnMap` 由步骤入口传入；步骤 7（`processBreakthroughs`）入口一次构建——候选筛选 D 次重建 → 1 次，`performBreakthrough` 循环内逐尝试重建同步消除；`battle_residual_tx.h` 战前突破事务（battlePresettleTx）同型改造。孕养升级当旬语义保持：步骤 7 在核心批次（1-5 含孕养提交）之后执行，入口映射已含当旬最新 nurtureLevel，与 Kotlin `battleWritebackMaxHpMp` 当前 state 现场口径对齐（步骤 7 全程只读 equipmentInstances/manualInstances，`attemptAutoPill` 只写 pills/储物袋） |
| R1.5 | ✅ | `getMaxHpMp` 两重载（`disciple_stats.h`）不再物化 `mergeEffects(talentEffectsFor, affixEffectsFor)` 三次中间 map（原每弟子每次调用三重 map 分配，实际只消费 maxHp/maxMp 两键）；新增 `hpMpEffectsFor` 两键直算（加法序 = 天赋 id 序 → 词条 id 序，与 map 版逐位一致）+ `computeBaseHpMpResolved` 效果已解析版（基础公式单一来源，map 版委托之——breakthrough.h/pill_system.h/applyBreakthroughFailure/GameCoreJni 等 7 处调用方签名不变零改动） |

测试口径：桌面全量 GTest **1419/1419**（携 `-ffp-contract=off` 旗标；R1.1 单独态与 R1.1+R1.5
终态各实跑一轮）+ engine JUnit 全量串行（桌面 JNI 对拍桥 `-Dgamecore.jni.path` 0 skip）+
detekt 绿 + `compileReleaseKotlin`/`lintRelease` 绿；JNI 面零变更、协议面零变更。

#### B02 批（2026-09-18）= R1.2 + R1.3（去物化 + dense 索引）

批次文件 `docs/parallel-batches-w5/batch-R1B.md`；每子项独立 commit（R1.2 = 96636ec95；
R1.3 分两步 = d4e25dac1 / dd2b4e0e9，补遗 f7e9b3251——JNI 纯特质速率通道桶视图迁移），
只改形状不改结果。

| 项 | 状态 | 关键落点 |
|---|---|---|
| R1.2 | ✅ | 结算入口全量 D 弟子物化快照 `committedDisciples` 退役（每旬 D 次深拷贝 → **0 次**；剩余物化仅突破命中候选的工作副本 = 候选数次）。快照唯一消费点 = `breakthroughChanceInput` 长老悟性读取（内/外门长老位 ≤2 名弟子）⇒ 新增 `committedElderComprehensionOf`：按 elderSlots 数值 id 行扫描、SoA 列直算 `baseComprehension` 捕获**入口时点**值（零物化）。逐位一致论证：键命中 = 数值 id 入口已存在（同 id 首行 == 原 emplace 首写）；值 = 入口时点 comprehensions/talentIds/affixIds 列直算（与物化快照同列同序）；结算步骤间 elderSlots 无重指派（任命属 UI 事务不入结算；偷盗叛逃仅清空槽位——消费点读空 id 提前返回），步骤 7 读到的非空长老 id 与入口一致；入口后新出现 → 回退 live 列（同原快照缺失路径）。`performBreakthrough`/`processBreakthroughs`/`battlePresettleTx` 传参随视图收窄（`map<int32,Disciple>` → `map<int32,int32>`），数值 id 键控语义保留（偷盗叛逃行移除不影响关联） |
| R1.3 | ✅ | 分两步：**第一步（d4e25dac1）** DiscipleStore 增 `numericIds`/`hasNumericIds` 派生列（ids 本是数字串——装载/增删时按 Kotlin toIntOrNull 同口径全串严格解析一次，纯内存列不进协议）+ `numericIdToRow` 数值行索引（与 `idToRow` 同点同步维护：append/eraseAt 同循环重建/swapRows 双键/clear）；`indexById(DiscipleStore)` 改数值列直读；热路径逐点替换（核心批次串行/并行循环、突破候选筛选、亲属赠送/日志循环、自动丹药钩子、`rowOf(to_string)` 往返 → `rowOfNumber`、战前突破事务、residenceBuildingBonus 数值 id 重载）；**第二步（dd2b4e0e9）** equipment/manual 映射改 owner 行索引桶式存储（`instance_buckets.h`：桶值 = 全局向量下标零拷贝、构建 O(E)、本人桶内末次匹配零分配；无主/异常 owner 回退全量末次扫描 = 原 `map.find` 同覆盖面；实例 id 唯一不变量下逐位一致）——每步入口 `equipmentMapOf`/`manualMapOf` 全量深拷贝映射（E 次实例深拷贝 + 字符串键节点）退役，`getMaxHpMp`/`calculateCultivationPerPhaseColumn` 收敛桶查找单源；`idToRow` 字符串键仅保留在协议边界（JSON 编解码/JNI/UI 事务字符串寻址），GameState 实例存储形状与 JSON 协议零变更 |

测试口径：桌面全量 GTest **1425/1425**（携 `-ffp-contract=off` 旗标；基线 1419 + 新增
DiscipleStore 数值列守卫 6 条；R1.2 单独态 1419、R1.3 第一步终态 1425、R1.3 第二步终态
1425 各实跑一轮）+ testReleaseUnitTest 全量串行 `--rerun-tasks` 实跑（六模块 **7777 用例 /
0 失败**；`:core:engine` 3296 含 47 个 `Diff*Test` 桌面 JNI 对拍类 **268 用例 0 skip**——
JNI 桥经 `scripts/build-desktop-jni.ps1` 重建携入本批 C++ 改动）+ detekt 绿 +
`compileReleaseKotlin`/`lintRelease` 绿；JNI 面零变更、协议面零变更、存档格式零变更。

#### B03 批（2026-09-18）= R1.4 + R1.6 + R1 收官 bench（G1 达成）

批次文件 `docs/parallel-batches-w5/batch-R1C.md`；每子项独立 commit（R1.4 = eb9812c0a；
R1.6 = 7e3bb87a9；bench = c97d7a4b0——含 R1.3 dense 索引收尾的每旬入口 indexById 逐点
替换），只改形状不改结果。**R1（解锁形状锁）全部条目至此收官。**

| 项 | 状态 | 关键落点 |
|---|---|---|
| R1.4 | ✅ | `column_dirty.h`：`ColumnDirtyTracker` 列级写屏障——DiscipleStore SoA 协议列枚举（109 列，与 Disciple to_json 协议字段双射，守卫锁定）+ 行主序脏位图 + 集合 tombstone（通用实体集合名）+ gameData 域级标脏；导出仅序列化脏列（`{version,changed,removed}` 与 diffToJson 同形同版本语义，脏行恒携 id 键；tombstone 撤销规则 = upsert 保序旋转删→回不复删、漏标保守兜底整行标脏）。写屏障挂点 = DiscipleStore 协议边界变更原语（append/eraseAt 位移段/swapRows/clear，`attachColumnDirtyTracker` 显式挂载、未挂载零开销）；**生产 exportDirtyJson 仍走全量树 diff（对拍显式依赖的全量模式开关，零漂移）**，列级导出为显式 opt-in 能力，热路径写点标脏接线随 R2。R2 前置就绪 |
| R1.6 | ✅ | `eraseEntityUnordered` swap-and-pop 变体（O(1)；IStorage 虚接口 + World/Registry 门面）与保序 `eraseEntity` 并存——**仅用于顺序无观察点场景**：唯一接线点 `destroyDiscipleEntities`（buildDiscipleEntities 先全清再按行序重建场景），批量销毁 O(D²) → O(D)；保序单删 `destroyDiscipleEntity` 与 View 迭代域不变；重建后行序不变量由 syncDiscipleEntities 校验兜底 |
| R1 bench | ✅ | `test/bench/`：全局 operator new 计数替换（malloc 后端）+ runPhaseSettlement 全八步 e2e——**D=5000 修炼热路径结算 17 次 malloc / ~1.8ms**（G1 基线同族口径；确定性计数 3 轮一致），门禁断言 < 10000 硬红；带实例清单真实快照 85017 次（残差 = 桶节点 + 熟练度/孕养 pending 提交的数据驱动每旬分配，登记为后续形状观察项，非 materialize/map 重建形状）；耗时三档打印。门禁接线 kover 模式：`GAMECORE_BUILD_BENCH` 本地默认关、ci.yml 显式 ON（独立 game-core-bench 目标，计数替换不进主测试二进制）。**G1 达成**：基线构成（committedDisciples 物化 + 每旬/逐实体 map 重建）分配形状全部退役 |

R1.3 收尾（随 bench commit）：bench 实测暴露每旬入口最后一处 O(D) 分配 = 串行/并行核心
批次与两版步骤 7 入口的 `indexById` 全量 map 重建（2×D 节点）——`numericIdToRow` 本是其
"免重建缓存"（R1.3 索引镜像守卫锁定一致），4 处调用点收尾替换为直用 store 索引（行结构
变更经 eraseAt 同点维护，步骤内无行结构变更 ⇒ 引用绑定 == 快照，行为逐位一致）。

测试口径：桌面全量 GTest **1443/1443**（携 `-ffp-contract=off` 旗标；基线 1425 + 新增
ColumnDirty 守卫 11 + ECS swap-and-pop 守卫 4 + bench 门禁 3；`GAMECORE_BUILD_BENCH`
本地默认关时 1440/1440；GTest 分项实跑：R1.4 终态 1436、R1.6 终态 1440、bench 终态
1443 各实跑一轮）+ testReleaseUnitTest 全量串行 `--rerun-tasks` 实跑（六模块 **7777 用例 /
0 失败**，222 任务全 executed 非 UP-TO-DATE；`:core:engine` 3296 含 47 个 `Diff*Test`
**268 用例 0 skip**——JNI 桥经 `scripts/build-desktop-jni.ps1` 重建携入本批 C++ 改动；
17 跳过 = data 15 + app 2 既有。途中偶发：`GameEngineCoreLifecycleInterleavingTest`
首两轮各 1 例失败（5s 并发时序窗），单独重跑 12/12 绿 + 第三轮全量绿——既有偶发
（progress.md §912 同款前例），与本批无关：本批零 Kotlin/JNI 变更）+ detekt 绿 +
`compileReleaseKotlin`/`lintRelease` 绿；JNI 面零变更、协议面零变更、存档格式零变更。


#### B04 批（2026-09-18）= R4.2 秘境战斗切 native（复用 BattleExecutionRouter 路由与灰度模式）

批次文件 `docs/parallel-batches-w5/batch-R4A.md`；单代码 commit（fa8fa5833），零 C++ 变更。

| 项 | 状态 | 关键落点 |
|---|---|---|
| R4.2 | ✅ | `SecretRealmService` 新增战斗执行统一入口 `executeRouted`（妖兽战 PvE `buildAndExecuteBattle` / AI 宗门遭遇 PvP `buildAndExecuteAISectBattle` 两分支共用）：AUTHORITATIVE 生产经 `BattleExecutionRouter.tryExecuteNative` → `GameCoreBridge.nativeBattleExecute`（C++ `battle::executeBattle`，消费 BATTLE 分区——NativeBackedRng 委托式约定同区同序）；flag 关 / native 未加载 / 失败信封（C++ 全 catch 返回 `{"error"}`）→ null 回退 Kotlin `executeBattleWithTimeout` 既有臂（超时口径不变），**回退臂保留一个版本周期（本批不删臂、不转 golden）**。会话管理 / UI / 邮件 / 暂停租约留 Kotlin 平台域——只切战斗执行段；秘境会话交互面既有 native 事务通道（SECRET_REALM_CHOOSE / CONTINUE / END / EXPIRY_GUARD / START_RELEASE）不动。**灰度 flag：`NativeEngineFlag.authoritative`（`NativeEngineFlag.kt`，`mode` 生产默认 `AUTHORITATIVE`——R4.1 既有旗标体系，零新增 flag）**。守卫：`SecretRealmServiceRouteTest` 3 用例（桥未加载 AUTHORITATIVE + 旗标 OFF 两回退路径 × 妖兽/PvP 编排面：战报记录/成员写回/胜负一致/体力扣除/AI 队伍移除一致性）；等价性由 `DiffBattleExecutionTest`（C++ `battle_execution.h` 逐位对拍）+ `DiffSecretRealmTest`（事件生成/判定段）守护 |

测试口径：桌面全量 GTest **1443/1443**（携 `-ffp-contract=off` 旗标；本批零 C++ 变更，基线 1443 持平）+
testReleaseUnitTest 全量串行 `--rerun-tasks` 实跑（六模块 **7780 用例 / 0 失败**，222 任务全 executed
非 UP-TO-DATE；`:core:engine` 3299 = 基线 3296 + `SecretRealmServiceRouteTest` 3，含 47 个 `Diff*Test`
**268 用例 0 skip**——对拍桥经 `scripts/build-desktop-jni.ps1` 重建；17 跳过 = data 15 + app 2 既有）+
detekt 绿 + `compileReleaseKotlin`/`lintRelease` 绿；JNI 面零变更、协议面零变更、存档格式零变更、
RNG 分区零调整（分区独立属 R4.4）。


#### B05 批（2026-09-18）= R4.3 探索/巡逻生产下沉（复用 BattleExecutionRouter 路由与灰度模式）

批次文件 `docs/parallel-batches-w5/batch-R4B.md`；单代码 commit，零 C++ 变更。

| 项 | 状态 | 关键落点 |
|---|---|---|
| R4.3 | ✅ | 探索/巡逻**结果生产评估**切 native（参照系 = AI 兽战处理器既有 `tryExecuteNative ?: executeBattle` 模式，R4.1/R4.2 同一路由与灰度契约）：① `ExplorationService.createBeastBattle`——妖兽防守战生产（排期妖兽自动防守 `executeScheduledBeastAttack` + 弹窗迎战/手动进攻无遭遇战路径 `resolveBeastAttackFight` 共用）；② `PatrolBattleSystem` 三处——巡逻楼普通 PvE（`executeBattles`）+ 冲突战 Phase 1 PvP（`buildTeamPhase1Battle` 巡逻队 vs AI）+ 冲突战 Phase 2 PvE（`executeTeamConflict` 胜者 vs 妖兽）。AUTHORITATIVE 生产经 `BattleExecutionRouter.tryExecuteNative` → `GameCoreBridge.nativeBattleExecute`（C++ `battle::executeBattle`，消费 BATTLE 分区——NativeBackedRng 委托式同区同序，战后神魂/属性/材料/灵石结算的抽取序逐位不变）；flag 关 / native 未加载 / 失败信封（C++ 全 catch 返回 `{"error"}`）→ null 回退 Kotlin `executeBattle` 既有臂，**回退臂保留一个版本周期（本批不删臂、不转 golden）**。**探索会话管理/UI/通知留 Kotlin 平台域**：战报/弹窗（`BattleResultUIData`/`pendingPatrolBattleResults`）、奖励生成（材料 `BeastMaterialDatabase` 非分区随机域 + `InventorySystem` 统一入口 + `SpiritStoneWallet`）、伤亡写回/悲痛/死亡处理、世界关卡刷新（`WorldLevelManager`）/妖兽攻击检测（`BeastAttackDetector`）不动；洞府探索（`CaveExplorationSystem`）评估结论 = 仍留 Kotlin（会话管理平台域 + `System.nanoTime` 种子非分区随机域，`BattleExecutionRouter` KDoc 已同步该边界）。**灰度 flag：`NativeEngineFlag.authoritative`（`NativeEngineFlag.kt`，`mode` 生产默认 `AUTHORITATIVE`——R4.1 既有旗标体系，零新增 flag）**。守卫：`ExplorationPatrolRouteTest` 5 用例（桥未加载 AUTHORITATIVE + 旗标 OFF 两回退路径 × 妖兽防守/巡逻 PvE/冲突战两阶段编排面：击败标记/战报记录/防守弹窗与奖励卡/幸存者 HP 写回/引导计数一致性）；等价性由 `DiffBattleExecutionTest`（C++ `battle_execution.h` 逐位对拍）+ 既有 `PatrolBattleSystemTest`/`ResolveBeastAttackFightTest`/`ScheduledBeastAttackTest` 族守护 |

测试口径：桌面全量 GTest **1443/1443**（携 `-ffp-contract=off` 旗标；本批零 C++ 变更，基线 1443 持平）+
testReleaseUnitTest 全量串行 `--rerun-tasks` 实跑（六模块 **7785 用例 / 0 失败**，222 任务全 executed
非 UP-TO-DATE；`:core:engine` 3304 = 基线 3299 + `ExplorationPatrolRouteTest` 5，含 47 个 `Diff*Test`
**268 用例 0 skip**——对拍桥经 `scripts/build-desktop-jni.ps1` 重建；17 跳过 = data 15 + app 2 既有）+
detekt 绿 + `compileReleaseKotlin`/`lintRelease` 绿；JNI 面零变更、协议面零变更、存档格式零变更、
RNG 分区零调整（分区独立属 R4.4）。

#### B06 批（2026-09-18）= R2.1 + R2.2（GameView proto 定义 + 镜像通道换 protobuf）

批次文件 `docs/parallel-batches-w5/batch-R2A.md`；逐子项独立 commit（R2.1 = daa8eeb11；
R2.2 = 6b3354708；mirror 传输对照 bench = 9e1d9c0cc）。前置 = B03 R1.4 列级写屏障。**进入 R2 阶段**
（状态同步协议重构——JSON 镜像 → protobuf 视图契约）。

| 项 | 状态 | 关键落点 |
|---|---|---|
| R2.1 | ✅ | **`GameView` proto 定义**（`core/engine/src/main/proto/game_view.proto`，选型 protobuf 不引 flatbuffers，基建已在 core:engine java_lite 插件）：镜像信封四块结构——`resourcesHeader`（每旬热路径 gameData 资源标量直拷，v1 = spiritStones）/ `discipleListDelta`（typed `DiscipleRow` 109 字段行级增量 upsert 全行 + removed id 列表，与 Disciple to_json 协议字段一一对应，含 CombatAttributes/PillEffects/EquipmentSet/SocialData/SkillStats/UsageTracking 扁平化、storageBagItems JSON 原文过渡编码）/ `eventFeed`（月年结算·突破·死亡·购买·秘境关闭，本批 **schema 预留不产出**，R2.4 接线）/ `configEcho`（快照 schema 版本，版本协商用）；其余实体集合与 gameData 字段经 `collectionChange`/`gameDataChange` 扩展区承载（v1 = JSON 载荷过渡编码）。**schema 演进纪律「proto 字段只增不改」在文件头声明**（方案 §5 风险条款：字段号冻结/不复用/不改型、新字段追加最大号+1、枚举只增不改号、proto3 标量恒 `optional` 显式 presence、集合恒 repeated 不用 map 沿 §7.3）；**C++ 零依赖 wire 编码器** `gamecore/state/gameview_encode.h/.cpp`（gamecore 纯 C++ 不链 libprotobuf，手写 varint/fixed64/长度前缀，表驱动 `kDiscipleRowFields` 与 proto 字段号双向锁定，字段号升序 + nlohmann 键有序遍历 ⇒ 同一变更集树恒产出逐字节相同信封，对拍/RNG 红线）；DirtyTracker 抽 `diffToTree`（规范化变更集树），`diffToJson` 与 `encodeGameView` 共用同一树源（同 ++version/同基线推进）⇒ 双传输格式逐值等价由构造保证。守卫 `gameview_encode_test.cpp`（9 用例：内置最小 wire 解码器按字段/值断言 version/resourcesHeader/DiscipleRow typed 全类别/collectionChange/gameDataChange/configEcho/eventFeed 不产出/确定性/畸形输入鲁棒，锁定字节级正确并证明产出合法 protobuf）|
| R2.2 | ✅ | **镜像通道换 protobuf**：生产 JNI `nativeExportDirty` 改走 `GameCore::exportDirty()` 分发（按 `dirtyExportProtobuf_` 选 JSON/protobuf，缺省 false = 旧格式跨版本回滚安全；**JNI 返回 jbyteArray 签名不变、仅字节载荷编码换轨**）；桌面对拍桥 `nativeCoreExportDirty` **仍走 `exportDirtyJson`（对拍全量 JSON 零漂移红线保留）**，另加纯编码入口 `nativeCoreEncodeGameView`（不触碰基线）供等价对照；Kotlin `StateSyncService` 新增 `applyDirtyProto` 经 `GameViewMirrorCodec` 把 GameView 信封还原为与旧协议**同形**的 `{changed, removed}` 变更集树、**复用既有 `applyEnvelope`（同一 applier，逐值等价由构造保证）**，`applyDirtyFromNative` 按灰度旗标选分支；**存档格式不动**（`nativeExportState` 全量 JSON 仅保留给存档/rebaseline，低频兼容优先）；**UI 消费面本批不动**（镜像仍全量、仅换传输编码为二进制 protobuf）。**灰度开关：`NativeEngineFlag.mirrorProtobufTransport`（默认 `true` = 换轨生效；`false` = 旧 JSON 镜像回滚臂，新旧共存一个版本周期）**。**【JNI 面豁免登记】**：新增 `external fun nativeSetDirtyExportProtobuf(Boolean)` + C++ 同名 setter，无法沿用既有业务 `nativeExecute` ActionId codegen 通道（传输格式为引擎控制态、非玩法操作，塞进业务操作码表是语义误用），沿 R0.2 `nativeFpDeterminismProbe` 探针先例登记，与既有 `nativeSetAiThermalBatchSize` 同族引擎线程控制端口。**镜像通道等价性证据（验收门 4）**：`DiffDirtyEnvelopeEquivalenceTest`（3 用例）编码面富变更集树 protobuf 往返逐值 deep-equal（弟子全字段 typed 重建/集合 raw json/gameData 标量与容器/removed，数字按值归一、空 repeated 容器与缺省键域等价）+ 应用面真实 native 变更集双解码对照（`DirtyApplyResult`+`GameData` 全等）；`DirtyTrackerBench.MirrorTransportJsonVsProtobuf` 观测 mirror 段非劣化 |

**proto schema 摘要**：`GameView{ version, resourcesHeader{spiritStones}, discipleListDelta{upserts[DiscipleRow×109], removedIds}, eventFeed[ViewEvent], configEcho{snapshotSchemaVersion}, collectionChange[name,upsertsJson,removedIds], gameDataChange[name,valueJson] }`。
**灰度开关**：`NativeEngineFlag.mirrorProtobufTransport`，默认 `true`（换轨生效），`false` 回旧 JSON 镜像路径。

测试口径：桌面全量 GTest **1453/1453**（携 `-ffp-contract=off` 旗标；基线 1443 + R2.1 编码守卫
9 + R2.2 传输对照 bench 1；`GAMECORE_BUILD_BENCH` 本地默认关时 1450）+ `testReleaseUnitTest`
全量串行 `--rerun-tasks` 实跑（六模块 **7788 用例 / 0 失败**，222 任务全 executed 非 UP-TO-DATE；
`:core:engine` 3307 = 基线 3304 + `DiffDirtyEnvelopeEquivalenceTest` 3，含 48 个 `Diff*Test`
**271 用例 0 skip**——对拍桥经 `scripts/build-desktop-jni.ps1` 重建携入本批 C++ 改动；17 跳过 =
data 15 + app 2 既有；`GameEngineCoreLifecycleInterleavingTest` 已知抖动本轮未触发）+ detekt 绿 +
`compileReleaseKotlin`/`lintRelease` 绿；协议 JSON 面零变更、存档格式零变更，JNI 面仅新增 1 个
引擎控制端口（`nativeSetDirtyExportProtobuf`，已登记豁免理由）。**G2 <10ms 终态属 R2.3 镜像瘦身
（本批镜像仍全量，仅换传输编码；本批证明 mirror 段未劣化、JNI 传输字节缩至 1/5.5）**。

#### B07 批（2026-09-18）= R2.3 第一波（UI 消费面完成二进制传输切换，镜像仍全量）

批次文件 `docs/parallel-batches-w5/batch-R2B.md`；逐子项独立 commit（全链路馈送等价守卫 =
707cbf2df；三臂收敛守卫 + 观测固化 = cb59bf537；消费面单一入口静态门禁 = 57f1d67ae；守卫夹具
拆分（detekt 合规）= 18083ac5a；文档三件套 + 审计报告 = 见下）。审计报告
**`docs/mirror-consumer-audit-2026-09-18.md`**。前置 = B06（R2.1+R2.2）。

| 项 | 状态 | 关键落点 |
|---|---|---|
| R2.3（一） | ✅ | **UI 消费面二进制传输切换收官（生产代码零变更——切换实质已由 B06 完成，本批补齐守卫与证明）**。① **消费面审计**（馈送点清单 F1–F7，逐点核查 `StateSyncService → GameStateStore`）：以"JNI 拉取点 / store 写入点 / 解码产物 import 面 / UI 模块符号命中"四锚定位——热路径稳态馈送（每旬 tick、`tryExecuteNative`/`executeRaw`、月变、年变后，13 个调用文件）**已 100% 由 GameView protobuf 二进制信封馈送**（F1）；**唯一残余 JSON 点 = F3 全量快照兜底臂**（`syncFromNative`，仅在增量臂返回 null 时触发），判**不属本波可切面**并留档四条依据：R2.2 已声明 `nativeExportState` 全量 JSON 保留、本批"C++ 侧不动"红线（切它需 C++ 全量 GameView 编码器 + 新端口）、全量视图字段域应与第二波 GameViewStore 投影一起定、且 F1↔F3 收敛已由守卫锁定（不构成 UI 语义分叉）。F4 月/年信封属 R2.4、F5 动作结果信封非馈送点、F6 为反向回导、F7 渲染面属 R3；**GameStateStore 仍全量 replaceAll 一字未改**（第二波退役）。② **全链路守卫补齐**（验收门 4）：`MirrorProtoFeedEquivalenceTest`（Robolectric，夹具拆至 `MirrorProtoFeedFixture`/`MirrorDiscipleRowFixture`）——proto 信封 → 解码 → applier → **GameStateStore 馈送**与旧 JSON 路径逐字段同形同值（弟子整行 **109 协议字段**恒设、每个 wire 类别取非默认值，集合 upsert/remove、gameData 标量+容器、`DirtyApplyResult` 计数、单事务原子 + "防两臂同错"期望值断言）；`DiffMirrorArmConvergenceTest`（桌面 JNI 0 skip）——12 旬真实 C++ 结算下 **F1（二进制增量）↔ F2（JSON 回滚）↔ F3（全量兜底）三臂全等** + 版本号单调（基线消费在**序列**上不错位，B06 只锁单封）+ 换轨生效（信封非 JSON 文本）。③ **审计结论静态化**：`MirrorConsumerSurfaceGuardTest`——`GameCoreBridge.nativeExport(Dirty\|State)(` 调用点唯一 = `StateSyncService`、`proto.gameview` import 唯一 = `GameViewMirrorCodec`、`core/ui` + `feature` 各模块对镜像符号零命中（UI 只读 GameStateStore 流）、灰度双分支与旗标默认值在源码面保留 ⇒ 未审计的第二消费入口一旦长出即红。**观测固化**：传输字节比 0.20（proto 18493B vs JSON 91480B，与 B06 bench 5000 弟子 1/5.5 同向）、增量臂 mirror 稳态中位 2.6ms/旬（首封 194ms = protobuf 运行时一次性初始化）、兜底臂 3.2ms/旬，两臂 < 100ms 告警线；F3 触发 0/12 旬（增量臂恒非 null）。**灰度开关零变更**：`NativeEngineFlag.mirrorProtobufTransport` 默认 true，false = F2 回滚臂，新旧共存一个版本周期（删除属独立批次）。**诚实边界登记**：解码树内 `collectionChange.upsertsJson`/`gameDataChange.valueJson`/`storageBagItemsJson` 仍为"二进制信封内嵌 JSON 原文"（R2.1 v1 过渡编码）——跨语言字节已全二进制，消费者侧 JSON parse 与全量 replaceAll 一并属第二波收益面，**G2 <10ms 终态仍在 R2.3 第二波**。**本批零 C++ 变更、零 Kotlin 主源变更、零 JNI/协议 JSON/存档变更**（桌面 GTest 基线 1453 持平、二进制无重建）。

测试口径：桌面全量 GTest **1453/1453**（携 `-ffp-contract=off` 旗标；本批零 C++ 变更，
`ninja: no work to do` + ctest 直接跑，68.19s）+ `testReleaseUnitTest` 全量串行
`--rerun-tasks` 实跑（六模块 **7794 用例 / 0 失败 / 17 跳过**，**339 任务全 executed
非 UP-TO-DATE**（组合门：testReleaseUnitTest + detekt + compileReleaseKotlin +
lintRelease，24m43s）；
`:core:engine` 3313 = 基线 3307 + `MirrorProtoFeedEquivalenceTest` 1 +
`DiffMirrorArmConvergenceTest` 1 + `MirrorConsumerSurfaceGuardTest` 4，含 49 个 `Diff*Test`
**272 用例 0 skip**；17 跳过 = data 15 + app 2 既有）+ detekt 绿 +
`compileReleaseKotlin`/`lintRelease` 绿。


#### B08 批（2026-09-18）= R2.3 第二波（镜像瘦身：GameViewStore 投影态 + 每旬级全量重建退场 + UI 逐块迁移）

批次文件 `docs/parallel-batches-w5/batch-R2C.md`；逐子项独立 commit（gameData 字段级应用 = 4d053b9be；弟子行 typed 投影 = b7b68b4ba；GameViewStore 投影态 = 6fd9fe03d；块①/②/③ 迁移 = 759526124 / 9a8d9a96a / 3b5c3ac22；退场静态门禁 = c61e2c9c7；G2 观测 bench = 1d4086f89；detekt 合规 = 3061da72f）。**零 C++ 变更**（桌面 GTest 基线 1453 持平、`ninja: no work to do` 为证）、零 JNI 签名变更、零协议 JSON 面变更、零存档格式变更。

测试口径：桌面全量 GTest **1453/1453**（携 `-ffp-contract=off` 旗标；本批零 C++ 变更，ninja no work to do + ctest 直接跑，60.44s，基线持平无需重建二进制）+ 组合门 `testReleaseUnitTest + detekt + compileReleaseKotlin + lintRelease`（`--max-workers=1 --rerun-tasks -Dgamecore.jni.path=…`）**BUILD SUCCESSFUL 24m09s、339 任务全 executed 非 UP-TO-DATE**；六模块 XML 汇总 **7812 用例 / 0 失败 / 0 错误 / 17 跳过**（= 基线 7794 + 本批新增 18：GameDataFieldPatchGuardTest 5 + GameViewDiscipleProjectionTest 4 + GameViewStoreGuardTest 6 + MirrorSegmentProjectionBenchTest 1 + MirrorConsumerSurfaceGuardTest 新增 2）；`:core:engine` **3331**（基线 3313 + 18）、`:core:domain` 1743、`:core:data` 716（15 既有跳过）、`:core:ui` 146、`:feature:game` 872、`:app` 1004（2 既有跳过）；49 个 `Diff*Test` **272 用例 0 skip**（含本批新增"真实 C++ 结算信封逐行 presence"用例实跑）；已知抖动类 `GameEngineCoreLifecycleInterleavingTest` 本轮未触发；detekt 六模块全绿、`compileReleaseKotlin`/`lintRelease` 全绿。

| 项 | 状态 | 关键落点 |
|---|---|---|
| R2.3（二）① 每旬级全量重建退场 | ✅ | **gameData**：`GameDataFieldPatch`（core/engine 新 `gameview` 包）把「整份 GameData `encodeToJsonElement` → 覆盖变更键 → 整份 `decodeFromJsonElement`」换成「一次 `copy()` 浅拷贝 + 本封变更字段逐个解码」——旧形状每旬按 **137 个序列化字段**（含 gameEventRecords/recruitList/worldMapSects/placedBuildings 等巨型容器）整树序列化 + 反序列化各一次，只为改 3~5 个标量。表体 = GameData 构造器序列化字段全集（@Transient 9 项除外），逐字段同一 `Json` 实例 + 同一 serializer ⇒ 与整份解码逐值等价；未知键宽松忽略、任一在册字段解码失败整组丢弃（两语义同旧臂）。**弟子行**：`GameViewDiscipleRows` 把 `DiscipleRow →（109 键）JsonObject → kotlinx 解码 → Disciple` 换成 proto typed 直读（每行 109 个 JsonElement 节点的造树成本整段退场）；`GameViewMirrorCodec` 抽 `parse`/`decodeView(includeDiscipleJson)`，投影臂下 `changed["disciples"]` 恒缺、行以 typed 列表交付，回滚臂仍产同一棵树。**全表替换**：`replaceAll` 经静态门禁锁定为"只存在于低频全量兜底臂 F3（10 个实体集合各一处）、每旬增量臂零命中"（B07 实测 F3 触发 0/12 旬），热路径若复活即红——**每旬级全量重建路径退场**。 |
| R2.3（二）② GameViewStore 投影态 | ✅ | 按 UI 消费块拆分的投影 store（Hilt 单例），替代 GameStateStore 全量快照的"UI 唯一入口"地位。块清单：**①资源头部**（三阶灵石 + 年/月/旬）/ **②配置回声**（政策、年俸(+开关)、长老槽、放置建筑、自动招募灵根）/ **③事件流当前载体**（gameEventRecords）；proto 块 3 `eventFeed` C++ 本批不产出（R2.4），`PROTO_EVENT_FEED_BLOCK = "reserved-not-produced"` 显式登记、不长出消费面。镜像提交后只重投本封触及的块，未触及块引用不变。馈送点全在镜像链单一入口 `StateSyncService` 内：增量臂 `project(carried, gameData)`、全量兜底臂 `reprojectAll`、基线建立点（读档/新档/rebaseline 共用 `importToNative`）`reprojectAll`——换档走 `loadFromSnapshot` 不发事务，不在基线点收敛则暂停态读档后 HUD 停在上一档头部值。**非镜像写入对账**：注册 `GameStateStore.TransactionObserver`，提交世代 ≠ 最近镜像世代 ⇒ 按 store gameData 快照立即重投（Kotlin 回退臂/未下沉 UI 事务命中即自愈；`reconciliationCount` 观测面，AUTHORITATIVE 稳态恒 0）。 |
| R2.3（二）③ ViewModel 逐块迁移 | ✅ | 顺序 = ①资源头部 → ②配置回声 → ③事件流，一次一块、各一 commit、各自可回滚。切换点全在 `GameEngine` 转发面（UI 代码零语义改动）：`GameViewModel.spiritStoneTotals` ← `gameEngine.resourcesHeader`；`GameViewModel.configState` ← `gameEngine.configEcho`（还原成 UI 既有 `GameStateStore.ConfigState` 形状，字段集与取值逐字段等价）；`GameViewModel.gameEventRecords` ← `gameEngine.eventLog`。回滚臂（旗标关）由 GameEngine 从整份 gameData 快照经**同一纯函数**（`resourcesViewOf`/`configViewOf`）派生同一视图 ⇒ 只换来源不换值。未迁块（弟子列表/聚合等）仍读 GameStateStore 三层流，两态共存一个版本周期。 |
| R2.3（二）④ mirror 段耗时观测（G2 趋势） | ✅ | 新增 `MirrorSegmentProjectionBenchTest`（Robolectric；B06 `DirtyTrackerBench.MirrorTransportJsonVsProtobuf` 的 **Kotlin 消费侧对应面**）——同一封"每旬全脏"信封两臂各跑、分阶段计时：**D=1000 旧臂 117.81ms（decode 25.56 + apply 92.25）→ 新臂 47.99ms（10.39 + 37.60）= −59%**；**D=5000（信封 2,177,797B）旧臂 343.13ms（65.49 + 277.64）→ 新臂 132.68ms（27.87 + 104.80）= −61%**。断言只锁硬性质（不设绝对阈值门，CI 抖动）：投影臂不得明显慢于旧臂（>15% 即红）、两臂 store 落库逐字段全等、投影块值 == 迁移前取数、回滚臂不馈送投影。**诚实登记**：测试替身每事务 `assembleAll()` 全表组装（生产为锁外增量/patch 组装），两臂含同一 O(D) 常数 ⇒ 绝对值高于真机、比值方向一致。**G2 <10ms@5000 未达成**：残余成本中心 = 每旬全脏 5000 行 `upsertMirrorRow`（109 列写屏障）+ store 侧 O(D) 列表组装 + C++ 侧全量序列化基线（R1.4 列级导出未接生产）。WS-1 关闭判定留 R2 收官核对。 |
| 红线：投影缺失字段 fail-fast | ✅ | 三处落地：① **行投影**——C++ 行内标量恒 emit-always，`requiredScalarFields`（93 项）与 `GameViewMirrorCodec.rowScalarFieldNames()` **逐项双射**，任一 `hasXxx()==false` 抛错并点名缺失字段（稀疏行守卫显式记录 JSON 臂"静默补默认"的对照形状 = 本批禁止的形状）；② **gameData 字段级应用**——字段在序列化面内但表内无写入器即抛错，`写入器键集 ↔ GameData.serializer().descriptor.elementNames` 双射守卫锁定（新增字段漏登记 = 镜像静默丢变更 → 红）；③ **投影块契约**——块声明字段一旦掉出 gameData 镜像协议面（改名 / 转 @Transient / C++ 不再导出）即抛错，不以默认值掩盖。抛出经 `applyDirtyFromNative` 降级契约转为"本封增量失败 → 全量兜底"，不污染镜像。 |
| 等价守卫（验收门 5） | ✅ | `GameDataFieldPatchGuardTest`（5：生产两臂旗标开/关对照，20 个字段覆盖全部 wire 类别 + 失败整组丢弃 + 未知键宽松 + @Transient 复刻面 + 表↔序列化面双射）；`GameViewDiscipleProjectionTest`（4：B07 富弟子夹具三方对照 `源 == typed 投影 == JSON 树重建` + 契约双射 + 稀疏行 fail-fast + **真实 C++ 结算信封逐行 presence 实证**，桌面 JNI 0 skip）；`GameViewStoreGuardTest`（6：投影值↔迁移前整份快照取数逐字段等价、未触及块引用不变、fail-fast、非镜像事务对账、换档复位、关旗标不馈送）；`MirrorSegmentProjectionBenchTest` 附带两臂落库全等断言。既有 B06/B07 守卫（`DiffDirtyEnvelopeEquivalenceTest`、`MirrorProtoFeedEquivalenceTest`、`DiffMirrorArmConvergenceTest` 三臂收敛）全绿未改语义——仅把 `MirrorDiscipleRowFixture`/`MirrorProtoFeedFixture` 的"三键稀疏新弟子"夹具随 emit-always 契约收敛为全字段两臂同源（原形状只有测试存在，生产 C++ 恒全字段）。 |
| 灰度共存 | ✅ | 新增旗标 `NativeEngineFlag.gameViewProjection`（默认 **true** = 第二波形态：字段级 gameData 应用 + 弟子行 typed 直读 + 投影馈送 + 已迁 UI 块以投影为来源；**false** = 第一波形态回滚臂：整份 GameData JSON 往返 + 每行 JSON 造树 + UI 块读 GameStateStore 全量流）。UI 块来源在 `GameEngine` 转发面首次访问时决策（进程级），回退 = 旗标默认值改 false 重编/重启；`mirrorProtobufTransport`（R2.2 传输臂）语义与默认值零变更，两旗标正交。回滚臂物理删除属独立批次。 |
| 登记缺陷（不在重构批顺手改行为） | 📌 | 旧「整份 GameData 解码」必然把不入 JSON 的 5 个 @Transient 运行态字段（`slotId`/`autoSaveIntervalMonths`/`aiBeastEncounterTargets`/`battleTeam`/`aiBattleTeams`）**每旬打回声明默认值**（现状即缺陷：镜像每旬静默重置 Kotlin 运行态字段）。本批按"UI 行为零变更"红线以 `LEGACY_RESET_ON_MIRROR` **原样复刻**，修复须另批裁决并跑全量对拍（改变 Diff 基准面）。 |
