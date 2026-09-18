# 镜像馈送链消费面审计报告（R2.3 第一波，批次 B07）

> 日期：2026-09-18　批次文件：`docs/parallel-batches-w5/batch-R2B.md`
> 来源条目：`docs/native-engine-refactor-plan-2026-09-17.md` §3 R2 表 R2.3 行（第一波）
> 范围：C++ 真相源 → Kotlin 镜像 → GameStateStore → UI 的**馈送链编码面**审计。
> **第二波（镜像瘦身 / GameViewStore / replaceAll 退役）与 R2.4 不在本报告结论范围内。**

---

## 1. 结论（一句话）

**热路径 UI 消费面（每旬 tick、动作后、月/年结后的状态镜像）在 B06 后已 100% 由
GameView protobuf 二进制信封馈送；残余的 JSON 解码点只有 1 个（F3 全量兜底臂），
它是 R2.2 设计上保留的低频降级路径，不属于"只换传输"这一波的可切面。**
故本批交付 = 审计清单 + 三臂收敛等价守卫（含 F3）+ 消费面单一入口静态门禁 +
镜像链路 e2e 观测固化；"第一波实质已由 B06 完成，本批补齐守卫与证明"。

---

## 2. 审计方法

对照 `StateSyncService → GameStateStore` 馈送链逐点核查：

1. 以 **JNI 拉取点**为锚：主源中 `GameCoreBridge.nativeExport*(` 的全部调用点
   （实测 2 处，均在 `StateSyncService.kt`）；
2. 以 **store 写入点**为锚：镜像链的 `stateStore.updateMirror{}` 全部调用点
   （`applySnapshot` / `applyEnvelope` 两处，均在 `StateSyncService.kt`）；
3. 以 **解码产物**为锚：`com.xianxia.sect.proto.gameview` 在主源的 import 面
   （实测 1 文件：`GameViewMirrorCodec.kt`）；
4. 以 **UI 消费面**为锚：`core/ui` 与 `feature` 各模块主源对镜像符号的命中
   （实测 0 命中——ViewModel 只读 `GameStateStore` 的 StateFlow）；
   六模块 `GameCoreBridge.` / `StateSyncService` 引用清单全量核对。

判据：**同一初态 + 同一棵变更集树** 经不同编码臂馈送后，GameStateStore 是否
逐字段同形同值（数据形状差异即缺陷，fail-fast）。

---

## 3. 馈送点清单（消费点 → 切换前后对照）

| # | 馈送点 | 位置 | 输入编码 | B06 前 | B07 后（现状） | 触发频次 | 第一波判定 |
|---|---|---|---|---|---|---|---|
| **F1** | 增量镜像（稳态热路径） | `StateSyncService.applyDirtyFromNative` :284 → `applyDirtyProto` :326 → `GameViewMirrorCodec.decode` → `applyEnvelope` :336 → `updateMirror` | **GameView protobuf** | JSON 文本 | **二进制**（旗标开=生产默认） | 每旬 tick / `tryExecuteNative` / `executeRaw` / 月变 / 年变后（13 个调用文件） | ✅ 已切换（B06 完成，本批补守卫） |
| **F2** | 增量 JSON 回滚臂 | `applyDirty(String)` :308 | JSON 文本 | JSON | JSON（旗标关时生效） | 灰度共存期 = 一个版本周期 | ⛔ 设计上保留（R2.2 红线"新旧共存"），非缺口 |
| **F3** | 全量快照兜底臂 | `syncFromNative` :102 → `nativeExportState` → `applySnapshot` :135 → `updateMirror`（**replaceAll 仍全量**） | JSON 全量快照 | JSON | **仍 JSON** | 仅 F1/F2 返回 null 时（native 异常 / 字节非法 / schema 漂移）——本批 e2e 实测 12/12 旬零触发 | ⚠️ **唯一残余点**，见 §4 判定 |
| F4 | 月/年结算信封 → 残留执行器 | `GameEngineCoreMonthOps` :85-96、`GameEngineCoreYearOps` :120-128 | JSON 信封 | JSON | JSON | 每 3 旬 / 每年 1 次 | 属 **R2.4**（eventFeed 接线），本批不动 |
| F5 | 动作结果信封（非镜像） | `GameEngineNativeOps.tryExecuteNative` :61-88、`executeRaw` :140-166、`RecruitAllEnvelope`、`ManualRecruitEnvelope` | JSON 结果载荷 | JSON | JSON | 每次转发动作 | 非 GameStateStore 馈送点（状态变更仍经 F1 回流），不属第一波 |
| F6 | Kotlin → C++ 回导 | `importToNative` :254、`rebaselineNativeMirror` | JSON | JSON | JSON | 读档/新档/事件后基线重建 | 反向通道，非消费面（存档/协议 JSON 面零变更） |
| F7 | 渲染/场景字节通道 | `NativeBridge`（tile/road/cliff/loop 帧计划） | 二进制标量数组 | 二进制 | 二进制 | 每帧 | 与镜像链无关，属 R3 |

**UI 消费面（ GameStateStore → ViewModel → Compose）**：`GameViewModel` /
`SaveLoadViewModel` 等只读 `StateFlow`，主源对 `StateSyncService` /
`GameViewMirrorCodec` / `proto.gameview` / `nativeExport*` / `applyDirty` /
`applySnapshot` 零命中 ⇒ **馈送链换编码对 UI 层不可见**（数据形状不变、仅来源编码变）。

---

## 4. F3（全量兜底臂）保留 JSON 的判定与依据

**不切**，四条依据：

1. **方案口径**：R2.2 行明确"`nativeExportState` 全量 JSON 仅保留给存档/rebaseline
   （低频兼容优先）"——F3 正是该低频兜底面，切它等于推翻 B06 已登记的边界；
2. **本批红线**："C++ 侧原则上不动"。F3 需要 C++ 侧新增**全量视图** GameView 编码器
   （现编码器 `gameview_encode.h` 的输入是 `DirtyTracker::diffToTree` 的**增量树**，
   全量视图要么另建导出路径、要么让 DirtyTracker 支持全量标脏——都是 C++ 改动 +
   新增 JNI/控制端口，须按 R0.2/B06 先例逐条豁免登记）；
3. **设计耦合**：全量 GameView 视图的字段域应与第二波的 `GameViewStore` 投影态
   一起定（投影缺字段时 fail-fast 是方案 §5 风险条款），先做二进制全量信封、
   第二波再改一次形状 = 重复劳动与双份守卫；
4. **风险已被消除**：F3 与 F1 的收敛同值由本批 `DiffMirrorArmConvergenceTest` 守卫
   （12 旬真实结算下 gameData + 5 类实体集合三臂全等）⇒ "没切 F3" 不构成 UI 语义
   分叉；第二波替换 F3 时 applier 与 GameStateStore 契约不变，改动面被压到解码器一处。

---

## 5. 诚实边界（第一波"二进制"≠"零 JSON 解析"）

`GameViewMirrorCodec` 还原的变更集树中，三类载荷仍按 **JSON 原文**在 proto 字段里
搬运（R2.1 schema 已声明为 v1 过渡编码，typed 化时"字段只增不改"追加新号）：

| 载荷 | proto 字段 | 解码动作 |
|---|---|---|
| 非弟子实体集合 upserts | `CollectionChange.upsertsJson` | `parseToJsonElement` 回填同形数组 |
| `resourcesHeader` 未覆盖的 gameData 字段 | `JsonFieldChange.valueJson` | 同上 |
| 弟子储物袋条目（递归结构） | `DiscipleRow.storageBagItemsJson` | 同上 |

即：**JNI 跨语言字节已全二进制**（弟子行 109 字段走 typed、无需 JSON parse），
但扩展区仍"二进制信封内嵌 JSON 文本"。这部分成本（消费者侧 JSON parse）与
GameStateStore 全量 replaceAll 一起属第二波（镜像瘦身 / typed 化）的收益面，
本批红线内不动。G2 <10ms 终态因此仍在第二波。

---

## 6. 本批新增守卫（三件）

| 守卫 | 位置 | 覆盖 |
|---|---|---|
| `MirrorProtoFeedEquivalenceTest` | `core/engine/src/test/.../nativebridge/`（Robolectric，夹具拆至 `MirrorProtoFeedFixture` + `MirrorDiscipleRowFixture`） | 验收门 4 主证据：**proto 信封 → 解码 → GameStateStore 馈送**与旧 JSON 路径逐字段同形同值——弟子整行 109 协议字段（每个 wire 类别取非默认值）、集合 upsert/remove、gameData 标量+容器、`DirtyApplyResult` 计数、单事务原子；含"防两臂同错"的期望值断言 |
| `DiffMirrorArmConvergenceTest` | 同上（普通 JUnit + 桌面 JNI，0 skip） | **残余消费点 F3 收敛**：12 旬真实 C++ 结算下 F1（二进制增量）↔ F2（JSON 回滚）↔ F3（全量兜底）三臂 gameData/实体集合全等 + 版本号单调 + 换轨生效（信封非 JSON 文本）+ mirror 段观测固化 |
| `MirrorConsumerSurfaceGuardTest` | `core/engine/src/test/.../architecture/` | 审计结论静态化：① `GameCoreBridge.nativeExport(Dirty\|State)(` 调用点唯一 = `StateSyncService.kt`；② `proto.gameview` import 唯一 = `GameViewMirrorCodec.kt`；③ `core/ui` + `feature` 各模块对镜像符号零命中；④ 灰度双分支 + 旗标默认值 `true` 在源码面保留 |

### 观测数据（本批 e2e 实跑，桌面 JNI 0 skip）

| 度量 | 值 | 说明 |
|---|---|---|
| 传输字节比 | **0.20**（proto 18493 B / JSON 91480 B，12 旬 3 弟子） | 与 B06 C++ bench 的 5000 弟子 1/5.5 同向（`DirtyTrackerBench.MirrorTransportJsonVsProtobuf`） |
| 增量臂 mirror 稳态中位 | **2.6 ms/旬** | 首封 194 ms = protobuf 运行时 + 解码路径一次性初始化（生产在启动期摊销） |
| 全量兜底臂 mirror 稳态中位 | **3.2 ms/旬** | 3 弟子小态下全量快照重灌；弟子规模上线后为 O(D) 全量路径（第二波退役对象） |
| 预算断言 | 两臂均 < **100 ms/旬** 告警线 | WS-1 悬置阈值；G2 <10ms 终态属第二波（本批镜像仍全量） |
| F3 触发次数 | **0 / 12 旬** | 逐旬 `assertNotNull("F1 二进制信封解析失败", viaProto)` 恒成立 ⇒ 增量臂从不返回 null，生产的 `syncFromNative` 兜底分支不会被走到（低频性实证） |

---

## 7. 灰度开关现状

`NativeEngineFlag.mirrorProtobufTransport`（`core/engine/.../nativebridge/NativeEngineFlag.kt:60`）：

- 默认 **true** = F1 二进制臂生效；native 初始化后由
  `GameEngineCoreAuthoritativeOps:200` 经 `nativeSetDirtyExportProtobuf` 推送
  C++ 分发模式（生产/解码两端读同一旗标）；native 未收到推送时缺省 false = 旧格式
  （跨版本回滚安全缺省）；
- **false** = F2 旧 JSON 增量臂，回滚臂与二进制臂同树同 applier，逐值等价由
  `DiffDirtyEnvelopeEquivalenceTest`（B06，树层）+ `DiffMirrorArmConvergenceTest`
  （B07，多旬序列 + store 层）双层守卫；
- 语义边界不变：仅影响镜像通道**字节载荷编码**——全量镜像内容、版本号、基线消费、
  存档格式零变更；F3 兜底臂不受旗标影响（恒 JSON）。
- 灰度期满后的删除动作（删 F2 + 删 JNI setter）属独立批次，不属 R2.3。

---

## 8. 与本批红线的逐条对照

| 红线 | 结论 |
|---|---|
| UI 行为零变更 | ✅ 三臂 store 逐字段全等；UI 层零镜像符号命中；本批零生产代码变更（git 可验：4 个 commit 全在 test/docs 面） |
| 镜像仍全量（不瘦身） | ✅ `applySnapshot` 的 `replaceAll` 与 `applyEnvelope` 的全量字段覆盖一字未改；无投影/增量替换引入 |
| 存档/协议 JSON 面、JNI 签名零变更 | ✅ 零 JNI 声明改动（含旗标 setter 未新增）、零存档格式改动；F5/F6 JSON 信封原样 |
| Kotlin 侧改动允许、C++ 原则上不动 | ✅ C++ 零改动（桌面 GTest 基线 1453 持平，无需重建二进制）；Kotlin 主源零改动，仅新增测试 |
| 每子项独立 commit + 提交前 compile/lint 绿 | ✅ 见 §9 提交清单 |

---

## 9. 交付物清单

| 子项 | commit | 面 |
|---|---|---|
| 全链路馈送等价守卫 | `707cbf2df` | `MirrorProtoFeedEquivalenceTest.kt`（+ 夹具两文件，见下） |
| 三臂收敛守卫 + e2e 观测固化 | `cb59bf537` | `DiffMirrorArmConvergenceTest.kt` |
| 消费面单一入口静态门禁 | `57f1d67ae` | `architecture/MirrorConsumerSurfaceGuardTest.kt` |
| 守卫夹具拆分（detekt 合规） | `18083ac5a` | `MirrorProtoFeedFixture.kt` + `MirrorDiscipleRowFixture.kt` |
| 本审计报告 + 文档三件套 | 随批 | 方案 §7.2 B07 行、CHANGELOG 4.01.15 段、`docs/cpp-engine.md` 口径（架构图镜像链路段 + 批览） |

门禁复跑（看护将亲自复跑）：桌面全量 GTest **1453/1453**（`ninja: no work to do` 证零 C++
变更、ctest 直接跑，68.19s）；组合门 `testReleaseUnitTest + detekt + compileReleaseKotlin +
lintRelease`（`--max-workers=1 --rerun-tasks -Dgamecore.jni.path=…`）**339 任务全 executed**、
BUILD SUCCESSFUL 24m43s；六模块 **7794 用例 / 0 失败 / 17 跳过**（`:core:engine` 3313，
49 个 `Diff*Test` 272 用例 **0 skip**；跳过 = data 15 + app 2 既有）；已知抖动类
`GameEngineCoreLifecycleInterleavingTest` 本轮 12/12 绿未触发。
