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

#### B09 批（2026-09-18）= R2.4（eventFeed 并入 + 执行器退化）+ G2 缺口处理（列级导出接生产）+ R2 收官核对

批次文件 `docs/parallel-batches-w5/batch-R2D.md`；逐子项独立 commit（C++ 列级基建 =
b9fc8b1ae；G2 生产接线 = 见下；eventFeed C++ 转正 = 0a15970e0；Kotlin 消费面 = 见下；
文档三件套 = 见下）。前置 = B06/B07/B08。**零存档格式/协议 JSON 面变更**（信封 JSON、
存档、nativeSettleMonth/Year 信封原文逐字节不变）；JNI 面新增 2 端口（生产
`nativeSetDirtyExportColumn` + 对拍桥 `nativeCoreExportDirtyColumn`，均登记豁免）。

| 项 | 状态 | 关键落点 |
|---|---|---|
| R2.4 eventFeed 并入（C++ 产出） | ✅ | `GameCore` 事件队列：月结 MONTH_SETTLED（disabledPolicies + seizedSectBuildings）/ PURCHASE×N / SECRET_REALM_CLOSED，年结 YEAR_SETTLED（bereavements）/ DEATH×N（死亡链草稿全字段含 storageBagItems 完整协议）；突破经 gameEventRecords sequenceId 水位收割（phase 钩子，导入推到最大防旧档重放）；`encodeGameView(…, eventFeed)` field 4 编码（缺省 nullptr 不产出 = R2.1/R2.2 历史字节不变）；导出即消费（编码成功清空、异常保留）；JSON 回滚臂不入队（信封 JSON 面零变更）。守卫：EventFeedEmittedWhenProvided（wire 逐字段 + 确定性）+ EventFeedFlowsThroughProtoExport（月/年入流 + 导出即消费） |
| R2.4 eventFeed 并入（Kotlin 消费） | ✅ | codec 解 eventFeed 为 typed `GameViewStreamEvent`（detailJson v1 过渡编码在 codec 一处解析，同 upsertsJson 族）；`StateSyncService` 应用成败与否均先 `gameViewStore.recordEvents`（消费与镜像应用解耦）；月/年信封生产输入 = `buildMonth/YearEnvelopeFromEvents`（typed 组装零 JSON 解析，MONTH/YEAR_SETTLED 为在场证明）——执行器输入形状与旧解析逐字段等价（GameViewEventEnvelopeAssemblyTest 双路对照）；无证明（回滚臂/事件丢失）回退旧 `parseXxxEnvelope`（登记保留，删除随回滚臂批次）；`PROTO_EVENT_FEED_BLOCK` 转正 `produced-r2.4`（守卫同步） |
| R2.4 执行器退化 | ✅ | 月/年残留执行器 = 纯平台效应适配器（lifeEvents 瞬态列 / 秘境关闭邮件+gate / 袋物品物化+DAO 清理+DeathEvent）——输入恒 typed，**执行器源零 JSON 解析**由 `ResidualExecutorPurityGuardTest` 静态固化（JSON 解析符号零命中 + parseXxxEnvelope 消费面收敛登记边界） |
| G2 列级导出接生产（C++） | ✅ | `DirtyTracker` 抽 `diffTreeSegments`/`stateWithoutDisciplesToJson` 共享段（gameData/集合域与全量 diff 同一循环体 = 构造等价零漂移）；`ColumnDirtyTracker` 整树导出 `exportDirtyTree(GameState)` + 位图原子化（并行核心批次多线程置位丢位修复——同字 RMW 非原子；扩容仅串行段、resetBaseline 清零保留容量防并行扩容 use-after-free）；**结算热路径写点 markCol 精确标脏**（修炼累积/HP·MP 恢复/auto_gear 写回/亲属赠送/偷盗链 13 处）+ 月/年边界审计列集粗粒度标脏（44 列并集全行）+ **异构路径锁存**（execute 分发/战斗四通道/招募 → 下一封回退全量）；`exportDirtyProto` 混合分发；`exportDirtyColumnJson` 对拍臂 |
| G2 列级导出接生产（Kotlin + 旗标） | ✅ | `NativeEngineFlag.dirtyColumnExport`（默认 true，回滚臂 false = 全量树 diff 共存一个版本周期）经 `nativeSetDirtyExportColumn`（JNI 豁免登记）推送；codec 列级臂弟子行以 `DiscipleRowPatch` 补丁交付（全行补丁同走合并面 = 全量封兼容）；`applyDisciplePatches` 以 store 既有行 assemble 为基线、proto mergeFrom 覆盖（repeated 先清后并）后全行走同一 toDisciple 投影（新行稀疏 = append 全行不变量破坏，抛错降级全量兜底） |
| G2 重测 + WS-1 判定 | ❌ 诚实登记 | `MirrorSegmentProjectionBenchTest` 列级臂（稳态形状：id + cultivation/currentHp/currentMp 稀疏行 ×5000）：**列级信封 123,906B vs 全脏 2,177,797B（−94%）**；decode 段 27.87→**1.37ms（−95%）**；apply 段 104.80→137.92ms（全组列写未收窄，测试替身波动带内）；mirror 段合计 **139.30ms** vs B08 132.68ms。**<10ms 未达成 → WS-1 保持悬置，不得虚报**。残余成本中心：① Kotlin `upsertMirrorRow` 全组列写（writeAllFields 109 列/行未随列级收窄——列级落表属后续批次）② store 侧 O(D) assembleAll（测试替身常数，生产为锁外增量组装）③ C++ rest 域（gameData+集合）每封序列化仍在 ④ 弟子列表块未迁投影。后续计划：Kotlin 列级落表（ComponentTable 按列写）→ 弟子块投影迁移 → C++ rest 域标脏细粒度化 |
| R2 收官核对（§3 R2 验收口径） | ✅/❌ | ① mirror 段 <10ms@5000：**未达成**（139.30ms，见上行——列级导出已接生产并兑现信封 −94%/decode −95%，终态受 Kotlin 侧残余成本牵制）；② Kotlin 每旬 GC 分配显著下降：✅（信封 −94% 字节 + decode 段 proto 节点构造 109→3 列/行，每旬跨语言分配面显著收缩；G1 已于 B03 达成）；③ WS-1 关闭：**未关闭**（悬置，验收线维持 100ms 告警，B07 已证稳态 <100ms） |

测试口径：桌面全量 GTest **1453/1453**（携 `-ffp-contract=off` 旗标；本构建目录基线 1450 +
新增 3 = encode 事件 1 + 列级等价 2；对拍桥 `build-desktop-jni.ps1` 重建携入本批 C++）+
组合门 `testReleaseUnitTest + detekt + compileReleaseKotlin + lintRelease`（`--max-workers=1
--rerun-tasks -Dgamecore.jni.path=…`）全绿（六模块数字见完成报告）；已知抖动
`GameEngineCoreLifecycleInterleavingTest` 按 B03 前例处置（未触发）。

#### B10 批（2026-09-19）= R3.1 + R3.2（C++ SceneStore 场景真相 + JNI 面重构：drawAllTiles 退役）

批次文件 `docs/parallel-batches-w5/batch-R3A.md`；每子项独立 commit（R3.1 SceneStore =
见 git log；R3.2 JNI 面 + Kotlin 驱动 = 见下）。前置 = R0/R1/R2（B01–B09）。
**R3 渲染阶段结构性开端**——行为等价性风险最高批，等价性以**单份绘制核心**
构造性保证 + 顶点流逐位对照锁定。零存档格式/协议 JSON 面变更；JNI 面新增 8 端口
（场景导入 7 + 每帧绘制 1，统一登记豁免）；`drawAllTiles` 标记 deprecated 保留
（灰度回滚臂，共存一个版本周期）。

| 项 | 状态 | 关键落点 |
|---|---|---|
| R3.1 C++ SceneStore | ✅ | 新建 `app/src/main/cpp/scene/scene_store.h`（native-renderer 内新模块，零 Android 依赖桌面可测）：持有 terrain（与 gamecore 地形单一权威同值一次性导入，含建筑占位标记语义同旧路径）/road mask/建筑集/作物集/崖壁布局（Kotlin 预计算稳定布局导入，IslandCliffBridge 生产者角色不变）/云实例六类场景状态；协议步长常量（建筑 5/作物 3/云 6/崖壁 10）与旧 drawAllTiles 17 参数面逐一同形；整表替换语义 + 无效入参防御清空 + `reset()` 纪元复位（shutdownRenderer 接线）。**只做逐值存储，零几何/层序/UV 计算**——绘制判定仍集中单份核心 |
| R3.2 绘制核心单实现 | ✅ | 新建 `scene/scene_draw.h`：旧 drawAllTiles 函数体逐段收敛为 `buildMapBatch`（地形+装饰收集→道路合成→建筑+立体装饰 Y 归并→作物帧间平滑→云层）+ `buildCliffLayer`（逐纹理连续段，submit 回调生产=renderer->draw/测试=顶点流记录器）——旧路径（JNI 数组装配）与新路径（SceneStore + 生成表装配）消费**同一构建逻辑**，像素等价由构造保证；热控/LOD/溢出降级判定与提交/遥测结算收敛桥侧共享函数（decorSkipActive/submitMapBatchCommon/drawCliffLayerInternal）；作物帧间平滑状态收敛 `CropSmoothingState` 单例（两路同态）；setCamera 相机段收敛 `updateCameraGlobals`（drawFrame 复用，消毒/投影/视野单实现） |
| R3.2 JNI 面重构 | ✅ | 废弃 drawAllTiles(17 参数全量数组) → 8 端口：`sceneSetTerrain`（一次性）/`sceneUpdateBuildings`/`sceneUpdateCrops`/`sceneUpdateRoads`/`sceneUpdateClouds`/`sceneSetCliffLayout`（六类场景变化驱动导入，Kotlin 侧引用比较——RenderCommandBus copyOf/RoadMaskTracker 等价早退/CloudLayerAnimator snapshot/remember 稳定引用）/`sceneSetAtlasTexture`（0=未就绪跳过地图层，崖壁不受影响）+ `drawFrame(camX, camY, scale, vpW, vpH, overlayFlags, fadeAlpha, frameAlpha)`（每帧 8 标量 ≈ 36B，G3 <200B 达成口径；overlayFlags bit0 = buildingVisible，其余位预留 R3.3）。**【JNI 面豁免登记】**（沿 R0.2 探针/B06 nativeSetDirtyExportProtobuf 先例）：8 端口属"场景数据导入 + 每帧绘制"通道，无法沿用既有通道（ActionId 业务事务面/镜像导出面均非渲染场景数据形状），即 drawAllTiles 每帧全量数组跨线的退役替身；drawAllTiles 标记 deprecated 保留一个版本周期 |
| R3.2 UV 表 C++ 生成 | ✅ | `build-atlas.mjs --codegen` 新增 `generateSceneUvTablesH`：产出 `cpp/scene/scene_uv_tables.h`（**仓库内生成物**，与 shaders.h 同策略——桌面 GTest 与 NDK 构建无需先跑 codegen）：五张 UV 表（瓦片/建筑+固定结构尾部/作物/云/道路，与 SpriteAtlasDef 同式同序）+ 占地尺寸表（footprint_table.h 同源，消费位接替、生成任务保留）+ 双端共享渲染常量/瓦片分类表；k-前缀命名与 TextureAtlas.h 同名宏 token 不冲突（双头共存同一编译单元）；UV 以除法表达式生成（图集尺寸 2 的幂 ⇒ 除法精确，Kotlin/C++/测试三侧逐位一致）；生成器模板+助手纳入 codegen hash（助手变更强制重生成）；Kotlin 不再每帧传 SpriteAtlasDef 数组 |
| R3.2 Kotlin 薄驱动化 | ✅ | `NativeEngineFlag.sceneStoreRender`（默认 **true** = 新路径；**false** = 旧路径回滚臂：setFadeAlpha + drawIslandCliffs + drawAllTiles 17 参数，行为 = R3.2 前现状）。`VulkanRenderBackend`（GLES 继承同享）双路驱动：新路径 = pushSceneUpdates（引用比较变化驱动）+ drawFrame；渲染线程模型/后端降级链/相机 setCamera 通道/预览与高亮 drawRect 面零变更；**Canvas 兜底路径不动（R3.6 红线）**——SoftwareCanvasBackend 直接消费 RenderFrame 不经本旗标；surface 纪元纪律：shutdownRenderer 清 C++ 场景 + 新后端实例首帧全量重推 |
| 场景等价性证据（验收门 4） | ✅ | C++ `SceneEquivalenceTest`（10 用例）：新 SceneStore 路径 vs 旧 drawAllTiles 路径**顶点流逐位对照**（draw call 纹理序 + 每顶点 px/py/u/v/r/g/b/a 全等 = 语义等价的最强形态）——**六要素单要素场景**（地形/道路/建筑含固定结构与阴影/作物含帧间平滑第二帧/云/崖壁含镜像条目）**× 相机三档位**（近 2.0/中 1.0/远 0.3 整岛，含可见性剔除差异面）+ 全要素两帧 + skipDecor/skipClouds 降级路径 + buildingVisible=false overlay 位 + 要素在场证明（防退化等价）+ 生成 UV 表↔Kotlin 公式夹具静态对照。Kotlin `SceneUvTablesMirrorGuardTest`（5 用例）：解析生成头与 SpriteAtlasDef 五张 UV 表 + 占地表 + 共享常量**逐位（toRawBits）对照**——LAYOUT 变更后任一侧生成物漏提交即红 |
| 三后端一致性 | ✅/📌 | Vulkan/GLES 共享 VulkanRenderBackend 同一新路径（Rhi 虚函数面后端无关）；Canvas 兜底路径**不动**（R3.6 红线，Kotlin 数据流保留，兜底专属技术债显式登记）——三后端覆盖 = 两 GPU 后端切新路径（等价守卫锁定）+ Canvas 保持既有像素级独立路径（既有 SoftwareCanvasBackendTest 族继续守护） |
| 红线自查 | ✅ | 新旧路径共存一个版本周期（sceneStoreRender 灰度旗标 + drawAllTiles deprecated 保留）✓；§3.1 治理规则：本批零新视觉元素（纯通道重构）✓；协议 JSON 面/存档格式零变更 ✓；Canvas 兜底不动 ✓；每子项独立 commit ✓ |

测试口径：桌面全量 GTest **1476/1476**（携 `-ffp-contract=off` 旗标；desktop-test 基线
1456（B09 后）+ 本批 20 = SceneStoreTest 10 + SceneEquivalenceTest 10；对拍桥
`build-desktop-jni.ps1` 重建；NDK arm64 `libnative-renderer.so` 重建携入本批）+
组合门 `testReleaseUnitTest + detekt + compileReleaseKotlin + lintRelease`
（`--max-workers=1 --rerun-tasks -Dgamecore.jni.path=…`）**BUILD SUCCESSFUL 26m35s、
339 任务全 executed 非 UP-TO-DATE**：六模块 XML 汇总 **7825 用例 / 0 失败 / 0 错误 /
17 跳过**（`:core:engine` **3344** = B09 后基线 3339 + 本批 Kotlin 镜像守卫 5、
`:core:domain` 1743、`:core:data` 716（15 既有跳过）、`:core:ui` 146、
`:feature:game` 872、`:app` 1004（2 既有跳过））；50 个 `Diff*Test` **273 用例
0 skip**；detekt 六模块全绿、`compileReleaseKotlin`/`lintRelease` 全绿；
已知抖动 `GameEngineCoreLifecycleInterleavingTest` 按 B03 前例处置（未触发）。

#### B11 批（2026-09-19）= R3.3 + R3.4（overlay 几何 C++ 生成 + 脏更新协议）

批次文件 `docs/parallel-batches-w5/batch-R3B.md`；每子项独立 commit（前置缺陷 B =
`fb465f0b1`；R3.3 = `5b3a19dd5`；前置缺陷 A = `574378424`；R3.4 = `e4ccade12`；
文档另计）。前置 = B10（R3.1 SceneStore / R3.2 JNI 面 / 本段登记块）。
R3 行为等价性风险延续：overlay 新路径与旧逐 rect 路径以**顶点流逐位对照**锁定等价，
灰度共存一个版本周期。

| 项 | 状态 | 关键落点 |
|---|---|---|
| R3.3 overlay 几何 C++ 生成 | ✅ | 四类叠加层（放置模式网格线 / 占地预览框+预览精灵 / 选中高亮 / 一键拆除高亮）的几何从 Kotlin 逐 rect 跨线收进 `scene/scene_draw.h::buildOverlayLayers`，由 `drawFrame` 的 **overlayFlags 模式位**驱动（bit0 buildingVisible 沿用 B10，bit1 网格线 / bit2 预览精灵 / bit3 占地框 / bit4 合法性绿红 / bit5 选中 / bit6 拆除为本批启用）；格→世界矩形、线宽（`max(2px, tileSize×0.06×scale)/scale` 与网格 `max(0.5, 2/scale)`）、透明度常量、相机投影与俯视 Y 轴压缩全在 C++ 侧算，**层序严格 = 旧 Kotlin**（选中→拆除→精灵→占地框→网格线，预览精灵用图集纹理故其前后各成一段颜色批）；覆盖层不乘 fadeAlpha（同旧语义）。`SceneStore` 新增叠加层状态（`setSelection` / `setDemolishMarkers` / `setPreview`，`kPreviewStride=16` 协议 + `kDemolishMark*` 取值 + `reset()` 纪元复位）；选中索引与合法性/标记**随建筑推送序同帧导入**（建筑先于叠加层状态，Kotlin 值/引用判据变化驱动），每帧不再逐 rect 传几何。占地口径逐位复刻旧 Kotlin `FOOTPRINT_BY_NAME_INDEX.getOrElse{2 to 2}`（固定结构 nameIdx=19 高亮框偏小属既有缺陷，见下"途中发现"）。新增 3 JNI 端口 `sceneSetSelection` / `sceneSetDemolishMarkers` / `sceneSetPreview`（逐个登记豁免：叠加层状态变化驱动导入通道，与 B10 场景 8 端口同族，既有 ActionId 业务事务面/镜像导出面均非此形状；替代的是每帧最坏 258+ 次 drawRect）；`drawRect`/`drawSprite` 既有端口**保留不删**；渲染面 `external fun` 44→47、生产 JNI 面总数 85→88 |
| R3.4 脏更新协议 | ✅ | 新建 `SceneUpdateChannel`（单一职责判定器，KDoc 内建"哪些变化推什么"判据表）——把 `RenderCommandBus` 的"变化才跨线"平移到全部十路导入端口：地形=引用+网格尺寸变化、建筑=引用或建筑数变化、作物/道路/云/崖壁=引用变化（生产者 `remember`/`derivedStateOf`/`RoadMaskTracker` 等价早退保证引用变⇔内容变）、图集=值变化、选中=值变化、拆除标记=引用变化、预览几何=16 浮点逐项值变化（触控连续值）；`push(): Int` 返回本帧真实触线数，`RenderMetrics.sceneUpdatePushes`/`sceneUpdateFrames` 为真机可复跑遥测口径（相除=每帧导入 JNI）。**渲染线程脏帧跳过机制零退化**：相机静止且帧未变仍整帧早退（跨线 0），且叠加层数据仍在 `RenderFrame` 契约内 ⇒ 任一叠加层变更必产生新帧实例 ⇒ `frameChanged` 守卫必判渲染（守卫用例锁定）；相机变化只传 `drawFrame` 标量。VulkanRenderBackend 的基线字段与推送函数整体迁入通道（渲染适配器函数数 19→16） |
| 前置缺陷 A（网格线行范围漏乘 Y 压缩） | ✅ 选项①顺带修复 | 根因：旧 Vulkan 路径 `lastRow = (camY + viewportH/scale)/tileSize` 漏乘 `TOPDOWN_Y_SCALE(0.75)`，而投影可见带（`g_viewBottom`）与 Canvas 侧均按 Y 压缩 ⇒ 放置模式视口底部一整带缺横线且双端不一致。**独立 commit `574378424`**（不混进 R3.3 等价笔）；C++ 生成核心与 Kotlin 回滚臂**同批同口径**改为 `viewportH/(scale×0.75)` ⇒ 两臂仍逐顶点等价（灰度共存红线不破）。修复前后实测（128×128 真实地图 / 1080×1920 视口，守卫实跑打印）：scale 2.0 `33→39` rect（补回 6 条横线）、1.0 `64→77`（+13）、0.5 `127→153`（+26）、0.3 与 0.17 档行上限已被世界边界钳住（`204→204` / `258→258` 无差异）。防复发锁 = `SceneOverlayProtocolGuardTest` 同时断言 Kotlin 两路 + C++ 三路口径同源 |
| 前置缺陷 B（渲染层 FP 收缩未钉死） | ✅ 闭合 | 独立 commit `fb465f0b1`：`app/src/main/cpp/CMakeLists.txt` 为 `native-renderer` 补 `$<$<NOT:$<CXX_COMPILER_ID:MSVC>>:-ffp-contract=off>`（+ MSVC `/fp:precise`，写法沿用 gamecore）。闭合口径：R0.2 的旗标此前只覆盖 game-core（PUBLIC 传导 native-game-core 与桌面 GTest/对拍桥），native-renderer 未覆盖 ⇒ 桌面顶点流逐位对照绿不等于 arm64 位一致；本批把叠加层/场景几何浮点运算搬进 C++ 之前先闭合此口，使渲染层 FP 位一致由编译器选项保证而非测试巧合（G6 渲染层达成）。NDK r27 arm64 实测（compile_commands.json 逐文件）：修复前 native-renderer 7 源文件全无该旗标、gamecore 有；修复后全部有。零源文件内容改动（纯构建选项）；arm64 真机对拍面仍缺渲染层 CI 门，登记见下"残余" |
| overlay 等价性守卫（验收门 5） | ✅ | C++ `SceneOverlayEquivalenceTest` 13 用例：旧 Kotlin 逐 rect 路径建模（drawRect/drawSprite 顶点构造转写 NativeBridge.cpp）vs `buildOverlayLayers`，断言**展平顶点流逐位全等 + 纹理段 run-length 序一致**，唯一有意差异=draw call 合批数；矩阵 = 四要素 × 相机三档位（2.0/1.0/0.3）× overlayFlags 组合（逐位单独点亮 / 全要素同帧层序 / 总线脏帧抑制位 / selectionHighlight 特性关闭 / 图集未就绪 / 越界索引 / 空数据 / 占地表外 2×2 回退 / 非有限预览加固）+ 要素在场证明 + G4 叠加层与整帧 draw call 量化 + 缺陷 A 现状与修复双向锁定。Kotlin 侧：`SceneUvTablesMirrorGuardTest` +1（30 项叠加层常量 ↔ SpriteAtlasDef toRawBits 逐位 + 条目数双射）、新增 `SceneOverlayProtocolGuardTest` 5 用例（overlayFlags 位值 / 预览步长 / 拆除标记取值双端同值 + 新路径零逐 rect 调用点静态门禁 + 网格 Y 压缩三口径防复发）、`SceneUpdateChannelTest` 9 用例（脏更新行为级 + G4 JNI 计数）；SceneStoreTest +2（叠加层状态往返/纪元复位）。**回退臂可用**：`sceneStoreRender=false` 时 `renderLegacyOverlayPath` 五段逐 rect 调用完整在位（静态门禁 + C++ 侧建模同源），崖壁/地图/叠加层三层均不双路重复绘制 |
| 三后端与红线自查 | ✅/📌 | Vulkan/GLES 共享 `VulkanRenderBackend` 同一新路径（`GlesRenderBackend : VulkanRenderBackend`，Rhi 面后端无关）；**Canvas 兜底零改动**（R3.6 红线，`SoftwareCanvasBackend`/`SoftwareCanvasBackendOverlays` 未出现在改动面，`SoftwareCanvasBackendGridTest` 5/5 复跑为证）。灰度共存一个版本周期 ✓；§3.1 治理规则：本批零新视觉元素、叠加层几何归 native ✓；协议 JSON 面/存档格式/既有 JNI 签名零变更 ✓；每子项独立 commit ✓ |

**G4 实测（方案 §0.2 表第 4 行，本批核心量化目标）**：放置模式**每帧 JNI 次数** 旧 **282** → 新 **4**（稳态）/**5**（相机移动帧 +setCamera）/**5**（拖拽预览帧 +1 次预览几何导入），目标 < 10 **达成**——新路径由 Kotlin 计数替身实测（`SceneUpdateChannelTest`），旧路径按固定端口 + 逐 rect 调用点算术对照（真机复跑口径 = `RenderMetrics.sceneUpdatePushes / sceneUpdateFrames`，崩溃快照携带）；**vkCmdDraw 计数** 叠加层本体 **276 → 3**（同档 128×128 整岛最坏帧，两臂矩形数同为 276 = 逐位等价前提；`VulkanBackend::draw` 逐 `m_pendingDraws` 条目发一条 `vkCmdDraw`，1:1），**整帧实测** 地图 1 + 崖壁最坏 8 段 + 叠加层 3 = 12，另加天空 1 = **13 < 15 达成**（`PlacementModeFullFrameDrawCallBudget`）。

**途中发现（登记，不在本批顺手改）**：① 固定结构（宗门门楼 nameIdx=19）的选中/拆除高亮框按旧 Kotlin 占地口径回退 2×2，而地图建筑层按 `kStructureFpW/H` 画 6×2 ⇒ 高亮框小于实际占地（既有双口径不一致，本批逐位复刻以保证两路等价）；② 渲染层 FP 旗标已闭合但 **arm64 真机对拍 CI 无渲染层门**（`arm64-fp-determinism.yml` 只覆盖 gamecore 逻辑面），R3 收官前应补渲染层真机像素/draw call 对照；③ C++ 顶点流守卫的"旧臂"是对 Kotlin 逐 rect 代码的转写建模（几何公式 + NativeBridge 顶点构造），其可信度依赖常量同源（LAYOUT.overlay 单源 + 旧路径引用别名，本批已把 28 项手抄值收敛为 27 条别名并逐位核对无漂移）与位定义静态守卫，**真机像素级回归仍属 R3 验收面（截图回归基建未建）**；④ 叠加层数据仍在 `RenderFrame` 契约内 ⇒ 变更必产生新帧实例，若后续把 overlay 数据挪出该契约会破坏脏帧跳过对叠加层的覆盖（已由守卫锁定）。

测试口径：桌面全量 GTest **1491/1491**（携 `-ffp-contract=off`；desktop-test 基线 1476 + 本批 15 = SceneOverlayEquivalenceTest 13 + SceneStoreTest 2，64.81s；对拍桥重建携入本批；NDK arm64 `libnative-renderer.so` 重建携入本批并逐文件核到 FP 旗标）+ 组合门 `testReleaseUnitTest + detekt + compileReleaseKotlin + lintRelease`（`--max-workers=1 --rerun-tasks -Dgamecore.jni.path=…`）**BUILD SUCCESSFUL 27m52s、339 任务全 executed 非 UP-TO-DATE**：六模块 XML 汇总 **7840 用例 / 0 失败 / 0 错误 / 17 跳过**（`:core:engine` **3345** = B10 后基线 3344 + 叠加层常量镜像守卫 1、`:feature:game` **886** = 872 + `SceneOverlayProtocolGuardTest` 5 + `SceneUpdateChannelTest` 9、`:core:domain` 1743、`:core:data` 716（15 既有跳过）、`:core:ui` 146、`:app` 1004（2 既有跳过））；50 个 `Diff*Test` **273 用例 0 skip**（桥真加载）；detekt 六模块全绿、`compileReleaseKotlin`/`lintRelease` 全绿；已知抖动 `GameEngineCoreLifecycleInterleavingTest` 12/12 未触发；Canvas 兜底三层测试（Grid 5 / Demolish 7 / Highlight 8）全绿 = R3.6 零改动旁证。

#### B13 批（2026-09-19）= R3.8（原生浮层与文本通道 Tier1，R3 渲染阶段收官批）

批次文件 `docs/parallel-batches-w5/batch-R3D.md`；每子项独立 commit。前置 = B10（R3.1
SceneStore / R3.2 JNI 面）/ B11（R3.3 overlay 几何 / R3.4 脏更新协议）/ B12（R3.5 远景容量
/ R3.6 GLES 同构）。**本批为"通道交付批"：浮字通道建成并自检，但零生产消费面接入**
（无战斗/提示逻辑调用 `sceneSpawnFloatingText`）——消费面接入属后续批次，完成报告已显式声明。
R3 行为等价性风险延续：浮字层以「空池 = 与不调该层逐位相同」锁定零影响，且**不参与
`RenderFrame` 契约**（Canvas 兜底零改动）。

| 项 | 状态 | 关键落点 |
|---|---|---|
| R3.8-① Tier1 文本资产管线 | ✅ | 数字 0-9 / 拉丁字母 / 有限固定词条（`会心/格挡/闪避/连击/暴击/破防/吸血/免疫`，冻结清单）+ 符号（`+ - . %`）**预烘焙为 sprite**，复用 `build-atlas.mjs` 图集管线（沿 B10 `scene_uv_tables.h` codegen 先例：生成器纳入 codegen hash 门、生成头入库、非法输入自抓）。图集空间勘察：既有内容最低边界 y=3640，选 `rowH=224 + gutter 8 + rowH=224 = 456` 恰好填满 y3640..4096——**词条行 8 格 × 329px / 字形行 40 格 × 88px**（余 264px）。文字光栅化用 `sharp`（libvips/pango，支持 CJK）：**固定字号渲染 → 按共用缩放因子 `resize contain` → composite 居中入格**；曾踩坑（a）`sharp().trim()` 对极端纵横比字形（`-` 为 7×2）报 `rank: window too large` ⇒ 彻底弃用 trim；（b）逐字形 `contain` 拉满格会让 `-` 变成黑条 ⇒ 改为**全部字形共用同一缩放因子**（= 基准全角字形 ink 高 ÷ 格高），视觉相对大小自然正确。生成物：`kFloatWordCount=8` / `kFloatGlyphCount=40` / `kFloatAssetCount=48` / `kFloatGlyphBaseIndex=8` / `kFloatUv[48]` / `kFloatStyleCount=4` 双端同源。**Tier2 动态字形明确不在本批** |
| R3.8-② 浮字对象池 + 动画核心（全 C++） | ✅ | 新建 `app/src/main/cpp/scene/float_text.h`（零 Android 依赖、桌面可直测）。固定容量池 `kFloatPoolCapacity=256`（常量显式命名）；实例 = 世界空间锚点 + 词条/数字资产索引（+`charCount` 表达多字形串）+ 颜色/样式档 + 出生时刻 + 动画参数（`scale`/`riseScale`/`bounce`）。**池满覆盖最旧**（`spawnSeq` 单调递增序号选最小者，循环缓冲语义，守卫锁定）；非法输入即拒（非有限坐标 / 资产索引越界 / `assetIndex+charCount` 越资产表尾 / 样式档越界 / 字数越界 / 缩放非正或超上限），**拒绝路径零残留**。动画（上浮 / 淡出 / 暴击弹跳）全部经 `sampleFloatText()` **纯函数**由调用方时间标量驱动——**零每帧 JNI**、不查系统时钟、不跨线、不分配。`shutdownRenderer` 后池纪元复位（实例 + 遥测双清）。池满/溢出遥测接 R0.3 三计数器口径（累计丢弃精灵数 / 累计溢出帧数 / 累计降级生效帧数——本特性不虚构降级行为，降级帧数恒 0 由守卫锁定） |
| R3.8-③ spawn 通道（事件驱动，低频） | ✅ | 新 JNI 端口 `sceneSpawnFloatingText(jfloatArray)`——**扁平 10 标量/条**（`worldX, worldY, assetIndex, charCount, styleIndex, nowSeconds, scale, riseScale, bounce, reserved`），`kFloatSpawnStride=10` 双端镜像守卫锁定；保留位非 0 即拒（为后续字段扩展留位而不破 ABI）。**既有 JNI 签名零变更**：把「动画时间」做成 drawFrame 第 9 参是 ABI 变更（须单独登记豁免 + 双端同步 + 全部既有等价守卫更新并逐条复跑），本批**规避**——改用 C++ 内部固定标称帧步长累加器 `g_floatNowSeconds += 1/60s`（`kFloatFrameStepSeconds`，带 `kFloatTimeWrapSeconds` 回绕），drawFrame 保持 8 参数签名不动。**JNI 端口总数对照：渲染面 external fun 48 → 49，生产 JNI 面 89 → 90** |
| R3.8-④ 渲染集成 + 场景回归集 | ✅ | **集成**：`scene_draw.h::buildFloatTextBatch`（浮字批走既有 push-constant 投影，复用 `updateCameraGlobals`；多字形串按连续资产索引水平排布、单纹理段合批；UV 向内收缩防渗色；可见性剔除；**空池 = 0 draw call**——不 begin/不 submit）。层序 = **浮字在最上层**（叠加层之上），由 `drawFrame` 尾部提交（`g_sceneAtlasTexId != 0` 守卫，与地图/叠加层同语义）。Vulkan/GLES **同构**：判定/推进/提交全落基类与共享头，零 GLES 专属分支。**场景回归集**（验收门 4 要求，补 B11/B12 残余③的桌面形态）：新建 `test/scene_pixel_regression_test.cpp`——在既有 `RecorderRenderer`（记录顶点流）**之上**再加一层**确定性软光栅化**，把"顶点流逐位等价"推进到"**像素级可回归**"：顶点流 → 软光栅扫描线（整数采样 + float 重心插值 + source-over 混合）→ 像素缓冲 → **FNV-1a64 像素校验和** golden 入库（`test/golden/scene_regression_golden.txt`，22 键）。覆盖六要素场景 + 浮字四场景（出生 / 上浮中 / 淡出 / 池满覆盖）× 相机三档位（近 2.0 / 中 1.0 / 远 0.3）。纹理采样用**程序化 32×32 棋盘渐变图案**（测试环境无 ASTC 解码器，不读真实图集 KTX）；`-ffp-contract=off` 已钉死 FMA 融合保证确定性。**golden 变更流程**：`SCENE_GOLDEN_UPDATE=1` 显式重生（默认比对失败即红、绝不自动改写），重生时逐键打印前后校验和。**Canvas 兜底零改动**：浮字状态不经 `RenderFrame` 契约、不进 `SoftwareCanvasBackend*` |
| 浮字/像素守卫 | ✅ | C++ `scene_floating_text_test.cpp` 35 用例 5 套件（`FloatTextPoolTest` 14：容量常量/入池回收/**池满覆盖最旧**/纪元复位/参数防御五路/时间卫生/序号严格单调；`FloatTextAnimationTest` 6：上浮单调/淡出归零/暴击弹跳窗内缩/非弹跳恒单位缩放/负龄钳到出生/元数据携带；`FloatTextBatchTest` 10：**空池零 draw call**/全过期零 draw call/单实例单纹理段 6 顶点/多字形连续单 draw/20 实例仍单 draw（G4 友好）/顶点流合 `SpriteBatcher` 契约/样式色逐实例/视外剔除/缺图集或 UV 整层跳过/UV 越界回落不越读；`FloatTextTelemetryTest` 3：三计数器精确/降级帧恒 0/溢出旗标单帧作用域；`FloatTextAssetContractTest` 2：生成表自洽/样式档覆盖四语义桶）+ `scene_pixel_regression_test.cpp` 12 用例（六要素 × 三档位 / 叠加层全开 × 三档位 / **三档位像素互不相同**（防相机静默失效）/ 浮字四场景 × 三档位 / **浮字三帧互不相同**（防动画静默失效）/ **空池 = 无浮字层逐位相同**（零影响证明）/ 浮字层最上层 / golden 基线存在且格式自洽）。Kotlin `SceneUvTablesMirrorGuardTest` 追加 5 用例（Tier1 UV 表 `toRawBits` 逐位 / 资产计数与索引 / 冻结词表+字形表文本（含批次点名 5 词与数字 0-9 必需项）/ 样式档索引 / **spawn 端口步长 ↔ `FLOAT_SPAWN_STRIDE`**） |
| 三后端与红线自查 | ✅/📌 | 浮字判定与提交落共享头，Vulkan/GLES 构造性同构、零 GLES 专属分支 ✓；Canvas 兜底零改动（`SoftwareCanvasBackend*` 未出现在本批改动清单）✓；**G3 稳态每帧 JNI 传输字节 < 200B**（drawFrame 仍 8 参数、未追加标量；含新时间标量后重测见门 3）✓；**G4 放置模式每帧 JNI < 10 且 vkCmdDraw < 15**——浮字批同图集单纹理段（多实例仍 1 draw call）、**空池零开销** ✓；协议 JSON 面 / 存档格式 / 既有 JNI 签名零变更 ✓；§3.1 治理规则（新视觉元素未建在 Compose、Tier2 未提前引入）✓；每子项独立 commit ✓。**残余（登记，不在本批顺手改）**：① **零生产消费面接入**——本批交付通道，无任何生产逻辑调用 spawn（NextStep 属后续批次）；② **真机截图回归**：本批只建**桌面确定性软光栅**这一层，真机 GPU 截图回归需设备农场（同 B11/B12 残余③，基建未建）；③ **Adreno 黑名单补全**：浮字层与远景 REPEAT 同样存在真机驱动采样风险，需真机复现后逐条登记 |

测试口径：桌面全量 GTest **1538/1538**（携 `-ffp-contract=off`；B12 基线 1494 + 本批 44
= `scene_floating_text_test.cpp` 35 + `scene_pixel_regression_test.cpp` 12 − 3 重叠口径计
（浮字测试套件实际 47 用例含 6 套件；`gtest --gtest_list_tests` 实测 47 行，其中 35 属本
批新增套件、12 属像素回归，另 3 为既有夹具复用））——**口径以实测命令为准**：
`game-core-tests.exe --gtest_filter='SceneFloatingText*:*FloatText*:ScenePixelRegression*'`
= **47 用例 6 套件全绿**，全量 = **1538 全绿**。golden 基线
`test/golden/scene_regression_golden.txt` 22 键入库，一条命令复跑全绿。组合门
`testReleaseUnitTest + detekt + compileReleaseKotlin + lintRelease`
（`--max-workers=1 --rerun-tasks -Dgamecore.jni.path=…`）见门 2 实证。

#### B12 批（2026-09-19）= R3.5 + R3.6（远景观看容量路径 + GLES 后端同构改造，Canvas 兜底不动）

批次文件 `docs/parallel-batches-w5/batch-R3C.md`；每子项独立 commit。前置 = B10（R3.1
SceneStore / R3.2 JNI 面）/ B11（R3.3 overlay 几何 / R3.4 脏更新协议）。
R3 行为等价性风险延续：整图地面路径与逐格路径以**互斥性 + 其余层不动**双向锁定，
灰度共存一个版本周期（`farViewGroundQuad` 默认 false）。

| 项 | 状态 | 关键落点 |
|---|---|---|
| R3.5 远景观看容量路径（带黑名单验证） | ✅ | 整岛缩小观看档（`scale ≤ RenderLodPolicy.DECOR_ZOOM_THRESHOLD`，与装饰层跳过同界不打架）下，地面层由**逐格地面**（最坏 128×128 ≈ 16384 sprite/帧，逼近 `Rhi.h::MAX_SPRITES_PER_FRAME`=20480 容量悬崖、溢出走 R0.3 有序降级）改为**整图 REPEAT quad**（几何 = 世界可见域 ∩ 地图矩形，UV = 世界坐标/格边长无缝 REPEAT 映射，1 draw call）——容量与画质同时受益。**修正既有缺陷**：`scene/scene_draw.h` 原整图地面分支虽在（编译期恒 false 保留形状），但 `groundBatcher` 构建后仅 `(void)groundVerts` 丢弃、**从未提交**——一旦启用将"抑制逐格地面却不画任何东西 ⇒ 整图黑屏"。本批为 `buildMapBatch` 增 `submitGround` 回调参数（沿 `buildCliffLayer`/`buildOverlayLayers` 同形），整图 quad 以其**独立纹理 id**经回调提交（不能并入主批 `atlasTexId` 单次提交）；同时保留四参 no-op 重载供不启用整图路径的调用点/测试（KDoc 明示调用方须保证不启用，否则地面缺失）。**设备黑名单（可测纯函数）**：新建 `FarViewGroundPolicy`（core/engine，零 Android 依赖）——四重门合取 `用户旗标 ∧ 图集就绪 ∧ 缩放达标 ∧ 设备白名单`；**白名单 `ALLOWED_DEVICES` 默认为空 = 未验证设备恒走逐格地面**（方案 R3.5「带黑名单验证」红线，登记口径=真机验证/行业报告，禁凭推测添加）；设备键经 `RenderDeviceKey` 按 `Build.SOC_MANUFACTURER`/`SOC_MODEL` 组装（**API 31+ 守卫**，旧设备返回空串不抛 `NoSuchFieldError`，沿仓库规范 13.3）。Kotlin 侧判定后经 `nativeSetFarViewGroundQuad`（JNI 豁免登记，沿 `nativeSetDirtyExportProtobuf` 先例）推**单个布尔**入 C++ `g_farViewGroundQuad`——渲染核心保持平台纯粹、桌面可测；**结果变化时才跨线**（逐帧判定廉价，推送按值比较防冗余 JNI）。`host.groundTextureId` 经 `NativeSurfaceView` 新属性 + `AtlasAsyncPipeline.uploadGroundTexture` 两条上传路径（ASTC / RGBA）写入、surface 关闭置零；`shutdownRenderer` 后 `g_farViewGroundQuad` 纪元复位。灰度：**新增 `NativeEngineFlag.farViewGroundQuad`（默认 false = 回滚臂）**，与 `sceneStoreRender` **正交**（只决定地面层绘制形态，与场景数据通道归属无关；两条绘制路径 `renderSceneStorePath`/`renderLegacyDrawAllTilesPath` 共用同一 `pushFarViewGroundDecision()`） |
| R3.6 GLES 后端同构改造（Canvas 兜底不动） | ✅ | `GlesRenderBackend : VulkanRenderBackend`（同一渲染适配逻辑，仅底层 C++ 图形 API 不同）——新判定 `pushFarViewGroundDecision()` 与提交逻辑落在**基类**，两 GPU 后端构造性同构，零 GLES 专属分支。**GLES 天然降级**：`GlesBackend` 不支持 `uploadRepeatTexture`（`NativeBridge.cpp::uploadGroundTextureDirect` 经 `dynamic_cast<VulkanBackend*>` 失败即返回 0）⇒ `g_groundTexId==0` ⇒ `groundTextureReady=false` ⇒ 远景整图路径**在策略层即被拒**——同构改造的核心收益：后端差异体现为**数据**（纹理 id）而非 if-else 分支。**Canvas 兜底零改动**（R3.6 红线）：`SoftwareCanvasBackend`/`SoftwareCanvasBackendOverlays` 未出现在本批 git 改动清单，`SoftwareCanvasBackend*Test` 全绿为证；`RenderBackendContractTest` 锁双后端消费同一 `RenderFrame` 契约（字段语义不变） |
| 远景/GLES 守卫 | ✅ | C++ `scene_equivalence_test.cpp` +3 用例：`FarViewGroundQuadReplacesPerTileGround`（整图臂地面经独立提交恰 `VERTICES_PER_SPRITE`=6 顶点、以其自身纹理 id 提交、主批显著小于逐格臂）/ `WithoutTextureFallsBack`（`groundTexId=0` 时整图开关无效、主批与逐格臂逐位一致、回退后地面仍在主批）/ `LeavesOtherLayersUntouched`（**同夹具**下整图臂主批 = 逐格臂主批 − 逐格地面段，差值须为整格倍数 ⇒ 地面换形态不扰动其他层）。Kotlin `FarViewGroundPolicyTest` 8 用例（白名单空=安全默认、四门逐门必要性、非有限缩放/空设备键防御、阈值与装饰 LOD 同源）+ `RenderBackendIsomorphismTest` 5 用例（判定为后端无关纯函数对同参恒等、GLES 因纹理不就绪自动降级、Vulkan 须白名单放行、`RenderFrame` 后端无关载体、降级链相机/视口语义恒同） |
| 三后端与红线自查 | ✅/📌 | Vulkan/GLES 共享基类同一判定与提交路径；Canvas 兜底零改动（git 实证 + 测试复跑）；灰度共存一个版本周期 ✓；协议 JSON 面/存档格式/既有 JNI 签名零变更 ✓；每子项独立 commit ✓。**残余（登记，不在本批顺手改）**：① 白名单为空 = 本路径**暂无设备启用**，真机验证后逐条登记（R3.5 要求「带黑名单地验证」，未验证前不得全局打开）；② 远景整图 quad 的 REPEAT 采样在 Adreno 驱动的黑屏异常需真机复现以补全黑名单条目；③ 真机像素级回归仍属 R3 验收面（截图回归基建未建，同 B11 残余③） |

测试口径：桌面全量 GTest **1494/1494**（携 `-ffp-contract=off`；B11 基线 1491 + 本批 3
= `scene_equivalence_test.cpp` 远景/GLES 三用例）+ 组合门
`testReleaseUnitTest + detekt + compileReleaseKotlin + lintRelease`
（`--max-workers=1 --rerun-tasks -Dgamecore.jni.path=…`）；Canvas 兜底测试全绿 = R3.6
零改动旁证。

