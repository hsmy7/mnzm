# ADR：反向通道根治——UI 操作面收尾与单向数据流收口

| 项 | 内容 |
|---|---|
| 状态 | **已拍板（2026-09-15，用户选择"选项 2 彻底根治"）**；实施计划见 [parallel-batches-w3/README.md](../parallel-batches-w3/README.md) |
| 决策 | **删除反向增量通道**（Kotlin → C++ 回导），使数据流单向：UI → C++ 事务 → Kotlin 只读镜像 + 存档链路 |
| 前置事实 | [handover §2.53](../cpp-migration-handover-m0.md) + [ui-read-surface §4.4](../ui-read-surface.md)：288 写入点穷尽审计证明 **14 个域无一可关**；batch-21 已交付逐域关闭机制与 68 个可证关闭单元 |
| 关联 ADR | [cpp-engine-migration.md](cpp-engine-migration.md)（总方案：双实现并行 → 逐域收口）、[rng-determinism-remediation.md](rng-determinism-remediation.md)（随机源治理，本方案的前提之一） |

---

## 1. 背景与目标

### 1.1 需求要点

迁移期采用"双实现并行"：C++ 为 AUTHORITATIVE 真相源，Kotlin 保存镜像；玩家在 Kotlin 侧产生的写入经一条**反向增量通道**（每个 tick 把 Kotlin 脏变更打包回传 C++）保持一致。该通道是**兼容设施**，不是终态：

- **终态契约**：任何影响游戏状态的写入都必须发生在 C++（或经 C++ 事务下发）；Kotlin 只读镜像 + 平台效应。
- **现状（batch-21 实测）**：288 个 Kotlin 写入点逐条审计后，仍有 **46 个弟子表稳态写者、9 类实体集合全部有稳态写者、64 个 gameData 字段有稳态写者**；关闭清单仅能覆盖 68 个单元。
- **因此**：删除通道 = **把剩余稳态写者逐域搬进 C++**，这是唯一路径，也是本 ADR 决策的全部内容。

### 1.2 成功标准（可验收）

| 标准 | 度量 |
|---|---|
| 通道删除 | `git grep -n "applyReverseDirty\|captureReverseDirty\|consumeReverseDirty\|ReverseDirtyAccumulator"` **归零**（含 C++/JNI/Kotlin 三侧） |
| 数据流单向 | AUTHORITATIVE 稳态下 `stateStore.update {}`（非镜像事务）**零命中**——由运行期断言 + 长跑对拍守卫 |
| 功能等价 | 引擎全量（含 46 Diff 对拍类）+ 桌面 C++ 全量 + 各域 GateTest 全绿；长跑 ≥100 旬存档往返逐字段一致 |
| 回退能力 | native 不可用时各域回退臂与迁移前行为一致（GateTest flag-OFF 臂） |
| 体积 | 反向信封体积 = 0（协议面可观测） |

### 1.3 非目标（明确不做）

- ❌ 不复刻平台效应到 C++（支付/广告/通知/网络投递/TapDB/热状态/墙钟）——它们留在 Kotlin，只把**决策结果**作为事务参数推送。
- ❌ 不为"关闭通道"改动玩法行为基线（含 RNG 消费序）——任何行为漂移都须走拍板流程。
- ❌ 不做"一次性大爆炸"重写；按域分批，每批独立可验收、可回滚。

---

## 2. 技术方案

### 2.1 架构变化（目标态）

```
现在：  UI ──► Kotlin 状态 ──(反向增量通道)──► C++ 真相源 ──(前向镜像)──► Kotlin 镜像 ──► UI
目标：  UI ──► C++ 事务（nativeExecute/结算）──(前向镜像 + 脏段)──► Kotlin 只读镜像 ──► UI
                     ▲
                  结算/tick（C++ 独占）
```

**关键不变量**（删除通道后必须成立）：

1. **单写者**：每个状态单元在 AUTHORITATIVE 稳态下只有一个写入者（C++）；Kotlin 侧写入仅出现在"回退臂"（native 不可用）与"装载期"（新档/读档，随后全量导入）。
2. **镜像只读**：Kotlin 状态对 UI 是只读投影；`updateMirror` 是唯一镜像写入口。
3. **平台效应外置**：平台 IO 的**读数**进 C++（作为参数），**副作用**留 Kotlin（通知/支付/网络）。
4. **RNG 单源**：所有影响状态的抽取走分区 RNG（C++ 态为唯一真相源）。

### 2.2 关键工作项（按批次拆解，详见 w3 README）

13 个批次，覆盖 14 个域的残余稳态写者，最后一批删除通道本体：

| 批次族 | 内容 | 关键设计点 |
|---|---|---|
| w3-01/02 弟子 | 赏赐/服药/属性直改/改名/类型/状态派生/槽位清理/婚姻审批/lifeEvent | 弟子表为**列式存储**：C++ 侧新增"派生列重算"事务（状态/槽位标记），避免把 Kotlin 的派生逻辑留在镜像层 |
| w3-03 巡逻 | 灵矿槽位 UI 直改 + 矿场自愈 | 自愈改为 C++ 纯函数 + 回执驱动 Kotlin 侧 gate/Room 残差 |
| w3-04 玉符 | 运行时累加/跨天/checkpoint | **墙钟是平台输入**：Kotlin 只推 `nowWall` 与限额，累加/跨天/结算移入 C++（或 C++ 侧只存"应发总额"由事务落账） |
| w3-05 邮件/行商 | 附件领取账本、手动刷新 | 账本（`mailRecords`）与商人池（`travelingMerchantItems`）入 C++；邮件**投递**仍留 Kotlin |
| w3-06/07/08 战斗族 | 伤亡写回、关卡战果、宗门战战后段、秘境残差 | 战利品生成需**物品随机生成器**（前置：模板抽取改走分区 RNG）；战史/日志为显示域可保留 Kotlin 只读 |
| w3-09 建筑/道路 | 拆除/放置槽位残差、月变没收 | `GridBuildingData` 补槽位字段或改由 C++ 槽位表承载（消除"模型缺字段导致残差留 Kotlin"根因） |
| w3-10 生产 | 月结前 repo→镜像对齐、自动续炼槽位 | 评估改为 C++ 直读 repo 快照（消除"窗口对齐"这一临时手段） |
| w3-11 月年编排 | native 结算后的 Kotlin 扇出、引导领奖、兑换码 | 扇出项逐条判定：状态写 → C++；通知/日志 → Kotlin |
| w3-12 外交/自愈 | 外交稳态段、存档前自愈、内存裁剪、检查点重锚 | 自愈族改为 C++ 自愈事务 + Kotlin 只读 |
| **w3-13 终局** | 删除通道本体 + 全部守卫收口 | 见 §4 验收 |

### 2.3 数据流与接口

- **下发**：UI → `ViewModel` → `GameEngine.xxx()` → `nativeTx`（ActionId + JSON 参数）→ C++ 事务 → 回执信封（`status`/`data`/`errorType`）→（成功）`applyDirtyFromNative` 脏段回读。
- **结算**：C++ tick/月结/年结独占；Kotlin 仅"残留执行器"处理平台效应（通知/邮件草稿/日志）。
- **装载**：读档/新档 → Kotlin 从 Room 读 → `importToNative` 全量导入（**保留**，这是基线建立路径，不是兼容通道）。
- **失败语义**：native 不可用 → 回退臂（Kotlin 原路径）；**失败信封 → 回退臂重执行校验链**（双实现一致契约由 GateTest 守卫）——终局后该路径仍保留（native 不可用场景），但**稳态下不再需要"Kotlin 写入回传"**。

---

## 3. 影响范围清单

| 模块/文件 | 变更类型 | 说明 |
|---|---|---|
| `gamecore/include/gamecore/system/*_tx.h` | 新增 | 每批一个域事务头（判定链先行 + 失败零写入 + 零/分区 RNG） |
| `gamecore/src/execute_dispatch.cpp` | 修改 | 每批新增 handler 分支（当前 33 个 handler） |
| `ActionIds.kt` / `action_ids.h` | 修改 | 生成器单一事实源再生成（当前 **171 动作 / maxId=1734**） |
| `android/core/engine/.../{domain}NativeTx.kt` | 新增 | 各域 native 转发臂 |
| 各域门面/引擎入口 | 修改 | 首行 native 臂 + 回退臂保留 |
| `core/domain/.../ReverseChannelPolicy.kt` | 修改 → **删除** | 关闭清单逐批扩大；w3-13 删除本体 |
| `app/.../GameStateStoreImpl.kt` | 修改 → **删除反向捕获** | w3-13 删 `captureReverseDirty`/累加器 |
| `core/engine/.../StateSyncService.kt` | 修改 → **删除反向发送** | w3-13 删 `applyDirtyToNative`/信封构建 |
| `gamecore/{src,include}/game_core.*` + `jni/GameCoreJni.cpp` | 删除导出 | w3-13 删 `applyReverseDirty` + JNI 导出 |
| 测试 | 新增/修改 | 每批 GateTest + Diff 对拍；w3-13 新增"稳态零写入"长跑断言 |
| **核查补充（2026-09-15 事实核查新增的未登记稳态写者）** | 修改 | ① `GameEngineManualOps.kt:137 replaceManual`（功法替换，活 UI `DiscipleDetailScreen.kt:954`，**从未登记**）→ 归 w3-01；② `GameEngine.kt:276 approveMarriageProposal`（婚姻审批，C++ 事务 `DISCIPLE_LIFECYCLE_MARRY_APPROVE=1592` 已有实现与 GTest 但**Kotlin 零接线**）→ 归 w3-02**低成本起手项**；③ `GameEngineBattleOps.kt:66 forceSettleDisciplesBeforeBattle`（宗门战战前结算，§2.51b 未显式登记）→ 归 w3-07 |
| 文档 | 修改 | handover 批次记录 + `ui-read-surface §4.4` 滚动更新 + 双更新日志 |
| **经济**（标签） | 无直接影响 | 本方案不改货币/奖励的源汇与数值；仅改"谁写状态"（玉符/灵石事务幂等性与账目一致性须逐批对拍验证） |
| **iOS**（标签） | 正面影响 | C++ 逻辑核心与平台层进一步解耦（平台效应显式外置到 Kotlin/宿主层），未来 iOS 侧只需实现同一组平台接口 |

---

## 4. 兼容性分析

| 面 | 结论 |
|---|---|
| **存档格式** | **不变**。存档为 Kotlin kotlinx ProtoBuf 序列化；本方案不改字段（若需新增 C++ 侧字段，按既有 `@ProtoNumber` + `@EncodeDefault(ALWAYS)` 规则追加，并补 Room Migration） |
| **存档语义** | 不变（关闭通道不改变谁持有什么值——因为关闭前该域已无 Kotlin 稳态写者） |
| **协议** | ActionId 只增（预分配段 1740–1849，整组提交）；w3-13 删除 `applyReverseDirty` 相关的**内部** JNI 导出（非对外协议） |
| **前后兼容** | 每批 native 臂失败即回退，故"新 Kotlin + 旧 .so"可运行（回退臂）；反之亦然（native 臂不认识的动作 → 旧 .so 返回失败 → 回退） |
| **降级路径** | `NativeEngineFlag.OFF` / `.so` 未加载 / 初始化失败：全链路回退 Kotlin（各批 GateTest 必须覆盖） |
| **迁移窗口** | 每批合入即生效（无存档迁移窗口；因为不涉及持久化结构变化） |

---

## 5. 测试方案

| 层 | 内容 |
|---|---|
| **单元（C++）** | 每批域事务 GTest：判定链全分支 + 失败零写入 + 全分区 RNG 快照差分 + 双运行全状态 JSON 逐位一致 |
| **单元（Kotlin）** | 每批 GateTest：同一入口 flag OFF vs ON → 结果 sealed 值 + 状态逐字段一致 |
| **跨语言对拍** | 既有 **47** 个 `Diff*` 类全绿（含 `DiffAuthoritativeTickTest` 100 旬管线对拍；2026-09-15 实测 47——§2.58 新增 `DiffAiRngSeedingTest`）；**本方案要求把该 harness 对齐生产**（用 C++ 月结 + Kotlin 残差替换 Kotlin 完整编排），以消除"生产超集"盲区 |
| **通道删除守卫（w3-13）** | ① 稳态零写入断言（AUTHORITATIVE 下 `stateStore.update` 命中即失败）；② 长跑 ≥100 旬 + 存档往返逐字段一致；③ `git grep` 归零校验；④ 反向信封体积 = 0 观测断言 |
| **对抗性审查要点** | ① 失败信封回归路径是否仍可能产生 Kotlin 稳态写入（本方案的核心风险，见 §6）；② 平台效应搬迁是否引入时序依赖（墙钟/热状态）；③ 槽位/派生列双写（C++ 与 Kotlin 同算一列）导致的漂移；④ RNG 抽取点搬迁是否改变消费序（须双端同步改算法 + 黄金用例重录，走拍板） |

---

## 6. 风险评估与兜底

| 风险 | 概率 | 影响 | 兜底 |
|---|---|---|---|
| 某域"以为搬完了其实还有写者" → 删除通道即丢数据 | 中 | 高 | 三闸门：① 批内写者穷尽扫描；② `ReverseChannelPolicy` 关闭域写入检测（运行期 ERROR + 计数）；③ 稳态零写入断言（w3-13 前作为**观察模式**开启，命中即告警不阻断） |
| 失败信封回退臂产生稳态写入 | 中 | 中 | 双实现逐位一致契约（GateTest）；把"失败信封 → 不允许 Kotlin 写"逐步改为结构性约束（`executeRaw` 三态化，禁止回退臂写状态）——列为 §8 债务 |
| 平台效应搬迁引入时序/权限问题 | 低 | 中 | 平台 IO 一律留 Kotlin；进 C++ 的只有"读数"（墙钟/热档/网络结果）；逐批回归 |
| 行为基线漂移（RNG 消费序） | 低 | 高 | 涉及抽取的批次必须双端同步改算法 + 拍板；否则不下沉（沿用 §2.51b 战利品族先例） |
| 大批次并行导致共享文件半提交 | 中 | 高 | w2 协议：五件套同批同提交；收口时干净检出复验 |
| 删除通道后发现需要"临时通道" | 低 | 中 | 通道删除批**最后**执行；删除前保留一个 release 周期的双跑观察（信封体积 = 0 但代码仍在）|

---

## 7. 未来场景推演（≥6 个月档）

| 维度 | 推演 | 结论 |
|---|---|---|
| **规模增长** | 弟子数 100 → 5000：前向镜像已是 O(变更)；反向通道删除后每旬成本再降（免去脏集比较与信封构建） | 收益随规模放大 |
| **生命周期** | 新玩法域加入：必须按"C++ 事务 + 回退臂 + GateTest"模板落地，**不得**再引入 Kotlin 稳态写者 | 需把"稳态零写入断言"作为 CI 长期门禁 |
| **平台扩张（iOS）** | 平台效应外置 + 逻辑核心在 C++ ⇒ iOS 侧只需实现平台接口（时间/存储/支付/广告/通知）| 本方案是 iOS 可移植性的**净收益** |
| **运营演进** | LiveOps/活动/远程配置：新增配置读取不影响状态所有权；活动奖励发放必须走 C++ 事务（沿用物品发放统一入口） | 需在 rules/expansion-playbook 增补"状态所有权"检查项 |
| **兼容回退** | 若需回退到"双写"（极端情况）：`ReverseChannelPolicy` 的历史实现可从 git 历史恢复，且导入导出协议未变 | 可回退（代价：重新接线） |

---

## 8. 技术债与偿还计划

| 债务 | 现状 | 偿还触发/计划 |
|---|---|---|
| 失败信封回退臂仍可能写状态 | 双实现一致契约（测试保证），无结构性禁止 | w3-13 前把高频域改为 `executeRaw` 三态（成功/业务拒绝/降级），**业务拒绝不进入写路径**；触发条件 = 该域搬迁批 |
| `DiffAuthoritativeTickTest` harness 为生产超集 | 把 Kotlin 月/年完整编排纳入 AUTHORITATIVE 稳态 | w3-11 批内对齐（改用 C++ 月结 + Kotlin 残差），并重录 4 个被覆写字段的关闭结论 |
| 每旬弟子全脏的镜像成本 | 受"全量实体 JSON 序列化"支配（协议形状） | 随计划 v2 阶段 3（列级 delta / 二进制通道）落地 |
| 死代码/死 API 残留（33 处站点 + `updatePatrolConfig` + 洞府探索整族） | batch-21 审计登记 | w3 各批顺手删除（同域即同批），或单列清理批 |

---

## 9. 行业对标（调研来源与结论摘要）

> 调研范围：单一真相源/单写者、确定性模拟与表现层分离、引擎迁移策略（strangler fig）与"何时停止共存"、
> 跨语言边界同步代价、混合架构所有权划分、迁移收尾的安全下线验收。**有效来源 49 条（S 级 33 / A 级 10 / B 级 6）**，
> 全部为近三年内且逐条核验发布日期；≥12 条 S/A 的硬性要求达成（实际 43 条）。

### 9.1 结论速览（对方案的直接启示）

1. **单写者原则在业界是规范式表述，不是设计偏好**——Unity Netcode《Authority》原文："每个对象必须有且只有一个 authority"；镜像端的任何写入被定义为"预测"，会被权威快照回滚。⇒ 本方案的终态正是这条例外的消除。
2. **反向通道 = 过渡架构（transitional architecture），必须有删除计划书**——Azure Strangler Fig 把 façade 定义为"迁移完成后即移除"；Anti-Corruption Layer 要求显式回答"这层是永久还是迁移完就退役"。⇒ 本 ADR + w3 计划就是这份退役书。
3. **切换顺序有标准四阶段：暗发布+同步 parity → 拦读 → 拦写（新系统成为 System of Record）→ 搬业务规则**（Fowler Event Interception 真实案例）。**只有"写权翻转 + 完整业务周期观察窗"完成后才能删旧写路径**。⇒ 本项目当前 14 个域"无一能关闭"，本质是停在阶段 2→3 之间；w3 各批须按此顺序推进，w3-13 才可删除。
4. **平台效应不属于确定性核心，业界明确反对把副作用写回核心**——Unreal 把 Steam/Xbox 服务关在 Online Subsystem 接口后；Unity IAP 文档说明初始化"可能无限期等待"、回调"可能在任何时刻到达"（本质非确定性）；GAS 规定 Cue 仅外观反馈，绑玩法会失步。⇒ 玉符/邮件/行商等**不是"谁来写镜像"的问题，而是"谁签发结果"的问题**：应重构为"命令进 C++ 事务、回执出 C++"，平台副作用留宿主层。
5. **宿主侧调度会污染确定性**——Factorio desync 复盘（FFF #415）：同一逻辑在不同 CPU 核数机器上生成结果不同，缺陷潜伏 7 年；FFF #416 进一步把"依赖建造顺序"的吞吐模型改成只依赖连通段，主动消除执行历史影响。⇒ "Kotlin 按自己节奏写状态、每旬回传"就是同类风险，**回传顺序成了模拟结果的一部分**。
6. **删通道的安全证明不是"看日志没流量"**——Azure Well-Architected 安全下线规程五步：跨完整业务周期验证不活跃 → 删前留快照 → 先禁用再删除 → 观察窗内任何意外活动即重置流程 → 清理残留引用。⇒ w3-13 验收按此执行（本方案已有"信封体积 = 0 观测"，需补"先禁用 + 完整业务周期观察 + 状态指纹零差异"）。
7. **业界明确不做**：长期维护两套权威实现；把反向增量通道当长期架构；大爆炸式 1:1 复刻（feature parity trap，Thoughtworks 已在技术雷达挂起）；用遥测"无流量"替代强制失活证明；把平台副作用写回确定性核心。

### 9.2 与本方案的映射（关键取舍）

| 主题 | 行业做法 | 对 w3 计划的具体落地 |
|---|---|---|
| 单写者 | Unity DOTS **write group** 让一个 system 结构性排除其他写入者；API 层读写标注违规即抛异常；ECB 警告"两个并行写队列会交错" | 每关一域 = 把该状态的写入者集合收缩到 1；**只删写入点而不切断写能力，写者会以新形式长回来** ⇒ 需补"镜像只读契约"（见 §8 债务） |
| 模拟/表现分离 | Unity `SimulationSystemGroup → PresentationSystemGroup` 是方向性下游分组；Netcode 固定步长模拟 + 独立插值渲染 | "UI → C++ 事务 → 只读镜像"即同一条单向链；表现类字段不得回写核心（守卫测试） |
| 迁移策略 | 四阶段（暗发布→拦读→拦写→搬规则）；Scientist 差异化对拍（只对不改数据的方法安全；**读侧实验先退役、写侧双跑长期保留**） | w3 各批 = 单域四阶段；GateTest/对拍即 Scientist 的 `use`/`try` 比对面 |
| 边界代价 | .NET/Unity 原生互操作最佳实践：blittable 优先、避免大字符串跨边界、池化缓冲、静态数据抽为中性源 | 保留 JSON 镜像格式（调试/存档兼容优先）；优化点放"按域刷新、减少跨界次数"；真机验证若显示镜像刷新成瓶颈再评估 FlatBuffers（见 §9.4-3） |
| 收尾验收 | Azure 安全下线五步 + "健康模型须含使用量指标，'无用户报障'可能只是'没有流量'" | w3-13 验收扩为"禁用 + 完整业务周期观察 + 状态指纹零差异 + 删前快照" |
| 1:1 复刻陷阱 | Feature Parity：旧系统约 50% 功能无人使用、历史 workaround 会被误当需求复刻 | **288 个写入点分三类**：① 影响模拟结果 ⇒ 搬迁；② 纯表现 ⇒ 删除或改只读派生；③ 平台效应回执 ⇒ 重构为"命令 + 回执"（不逐点 1:1 搬迁） |

### 9.3 参考来源清单（计入配额的 49 条）

| # | 等级 | 标题 | URL | 日期 |
|---|---|---|---|---|
| 1 | S | Strangler Fig pattern — Azure Architecture Center | https://learn.microsoft.com/en-us/azure/architecture/patterns/strangler-fig | 2026-06-02 |
| 2 | S | Anti-Corruption Layer pattern — Azure | https://learn.microsoft.com/en-us/azure/architecture/patterns/anti-corruption-layer | 2026-05-30 |
| 3 | S | Safe deployment practices（OE:11）— Azure Well-Architected | https://learn.microsoft.com/en-us/azure/well-architected/operational-excellence/safe-deployments | 2026-06-17 |
| 4 | S | Saga distributed transactions pattern — Azure | https://learn.microsoft.com/en-us/azure/architecture/patterns/saga | 2026-04 |
| 5 | S | Compensating Transaction pattern — Azure | https://learn.microsoft.com/en-us/azure/architecture/patterns/compensating-transaction | 2026-04-20 |
| 6 | S | Idempotent Consumer pattern — Azure | https://learn.microsoft.com/en-us/azure/architecture/patterns/idempotent-consumer | 2026-09-05 |
| 7 | S | Design principles for Azure applications | https://learn.microsoft.com/en-us/azure/architecture/guide/design-principles/ | 2025-09-26 |
| 8 | S | Native interoperability best practices — .NET | https://learn.microsoft.com/en-us/dotnet/standard/native-interop/best-practices | 2026-03-26 |
| 9 | S | Pass data between managed and unmanaged code — Unity 6.6 | https://docs.unity3d.com/Manual/plug-ins-native-pass-data.html | 2026-09-12 |
| 10 | S | Native plug-ins — Unity 6.6 Manual | https://docs.unity3d.com/Manual/plug-ins-native.html | 2026-09-12 |
| 11 | S | Ghosts and snapshots — Unity Netcode for Entities 1.8.0 | https://docs.unity3d.com/Packages/com.unity.netcode@1.8/manual/ghost-snapshots.html | 2025-08-19 |
| 12 | S | Unity Netcode for Entities 1.5.1（包手册） | https://docs.unity3d.com/Packages/com.unity.netcode@1.5/manual/index.html | 2025-05-08 |
| 13 | S | Write groups — Unity Entities 6.4.0 | https://docs.unity3d.com/Packages/com.unity.entities@6.4/manual/systems-write-groups.html | 2026-05-21 |
| 14 | S | Write groups — Unity Entities 1.3.15 | https://docs.unity3d.com/Packages/com.unity.entities@1.3/manual/systems-write-groups.html | 2026-01-14 |
| 15 | S | Job dependencies — Unity Entities 1.3.15 | https://docs.unity3d.com/Packages/com.unity.entities@1.3/manual/scheduling-jobs-dependencies.html | 2026-01-14 |
| 16 | S | Entity command buffers — Unity Entities 6.4.0 | https://docs.unity3d.com/Packages/com.unity.entities@6.4/manual/ecs-workflow-example-ecb.html | 2026-05-21 |
| 17 | S | Initialization — Unity In App Purchasing 4.13.2 | https://docs.unity3d.com/Packages/com.unity.purchasing@4.13/manual/UnityIAPInitialization.html | 2025-10-16 |
| 18 | S | Online Subsystem in Unreal Engine — UE 5.8 Docs | https://dev.epicgames.com/documentation/en-us/unreal-engine/online-subsystem-in-unreal-engine | 2026（版本集） |
| 19 | S | Gameplay Ability System Overview — UE 5.8 Docs | https://dev.epicgames.com/documentation/en-us/unreal-engine/understanding-the-unreal-engine-gameplay-ability-system | 2026（版本集） |
| 20 | S | Actor Owner and Owning Connection — UE 5.8 Docs | https://dev.epicgames.com/documentation/en-us/unreal-engine/actor-owner-and-owning-connection-in-unreal-engine | 2026（版本集） |
| 21 | S | Replication Graph in Unreal Engine | https://dev.epicgames.com/documentation/en-us/unreal-engine/replication-graph-in-unreal-engine | 2026（版本集） |
| 22 | S | Cross-Platform Determinism in Warhammer Age of Sigmar: Realms of Ruin — GDC 2024 | https://gdcvault.com/play/1034229/Cross-Platform-Determinism-in-Warhammer | 2024（GDC 2024 场次） |
| 23 | S | Write-Ahead Logging — SQLite Documentation | https://sqlite.org/wal.html | 2026-08-25 |
| 24 | S | Logical Decoding Concepts — PostgreSQL 19 Docs | https://www.postgresql.org/docs/19/logicaldecoding-explanation.html | 2026-08-13 |
| 25 | S | FlatBuffers Overview — Google | https://flatbuffers.dev/ | 2025 |
| 26 | S | Controllers — Kubernetes Documentation | https://kubernetes.io/docs/concepts/architecture/controller/ | 2024-09-01 |
| 27 | A | Friday Facts #415 — Factorio | https://www.factorio.com/blog/post/fff-415 | 2024-06-14 |
| 28 | A | Friday Facts #438 — Factorio | https://www.factorio.com/blog/post/fff-438 | 2024-11-22 |
| 29 | A | Friday Facts #442 — Factorio | https://www.factorio.com/blog/post/fff-442 | 2026-06-12 |
| 30 | A | Friday Facts #444 — Factorio | https://www.factorio.com/blog/post/fff-444 | 2026-06-26 |
| 31 | A | How Supercell Powers its Massive Social Network with ScyllaDB | https://hackernoon.com/how-supercell-powers-its-massive-social-network-with-scylladb | 2025-12-30 |
| 32 | A | Manage flag permissions at scale — LaunchDarkly | https://launchdarkly.com/blog/preset-role-scope-flag-lifecycle-settings/ | 2025-10-30 |
| 33 | A | Using feature flags to manage technical debt — Unleash | https://www.getunleash.io/blog/using-feature-flags-to-manage-technical-debt | 2026-04-02 |
| 34 | A | Scientist — github/scientist | https://github.com/github/scientist | 2024-12 |
| 35 | B | bliki: Strangler Fig — Martin Fowler | https://martinfowler.com/bliki/StranglerFigApplication.html | 2024-08-22 |
| 36 | B | Patterns of Legacy Displacement — Cartwright/Horn/Lewis | https://martinfowler.com/articles/patterns-legacy-displacement/ | 2024-03-05 |
| 37 | B | Event Interception — Patterns of Legacy Displacement | https://martinfowler.com/articles/patterns-legacy-displacement/event-interception.html | 2024-03-05 |
| 38 | B | Keeping the cloud afloat with deterministic simulation testing（Antithesis × etcd） | https://antithesis.com/blog/2026/keeping-cloud-afloat/ | 2026-04-09 |
| 39 | B | Leaving Godot Behind: Building a Custom Engine — Tales of Landor | https://tales-of-landor.com/news/new-engine | 2025-06-11 |
| 40 | B | Transitional Architecture — Patterns of Legacy Displacement | https://martinfowler.com/articles/patterns-legacy-displacement/transitional-architecture.html | 2024-03-05 |
| 41 | B | Legacy Displacement 索引（Event Interception / Dark Launching / Canary 等） | https://martinfowler.com/articles/patterns-legacy-displacement/ | 2024-03-05 |
| 42 | S | Managing latency with prediction — Netcode for Entities 1.6.2 | https://docs.unity3d.com/Packages/com.unity.netcode@1.6/manual/prediction-n4e.html | 2025-07-08 |
| 43 | S | System concepts — Unity Entities 6.4.0 | https://docs.unity3d.com/Packages/com.unity.entities@6.4/manual/concepts-systems.html | 2026-05-21 |
| 44 | S | Safety in Entities — Unity Entities 6.4.0 | https://docs.unity3d.com/Packages/com.unity.entities@6.4/manual/concepts-safety.html | 2026-05-21 |
| 45 | S | Entity command buffer overview — Unity Entities 6.4.0 | https://docs.unity3d.com/Packages/com.unity.entities@6.4/manual/systems-entity-command-buffers.html | 2026-05-21 |
| 46 | S | Authority — Netcode for GameObjects 2.7.0 | https://docs.unity3d.com/Packages/com.unity.netcode.gameobjects@2.7/manual/terms-concepts/authority.html | 2025-10-31 |
| 47 | S | Ownership — Netcode for GameObjects 2.7.0 | https://docs.unity3d.com/Packages/com.unity.netcode.gameobjects@2.7/manual/terms-concepts/ownership.html | 2025-10-31 |
| 48 | S | Xbox and PC multiplayer design guidance — Microsoft GDK | https://learn.microsoft.com/en-us/gaming/gdk/docs/services/multiplayer/overviews/multiplayer-design-guidance-xbox-pc-gdk | 2025-11-06 |
| 49 | A | Bevy 0.16（官方发布说明，不可变组件） | https://bevy.org/news/bevy-0-16/ | 2025-04-24 |

> **配额核算**：计入 49 条 = S 33 + A 10 + B 6（≥20 ✅、S/A ≥12 ✅）。
> 另有 2 条显式**不计入配额**仅作背景：Fowler *Feature Parity*（2021-07-27，超三年窗口）、
> LaunchDarkly 陈旧定义页（无发布日期）；另披露 8 条候选来源因站点不可达/无日期/超窗口而未采用
> （Android NDK JNI tips、Riot《Determinism in LoL》2017、Bungie Destiny GDC 2015 等）。

### 9.4 无法采纳的行业做法与原因

1. **帧级 deterministic lockstep / rollback 预测回滚** —— 服务实时对战；本项目是单机懒惰结算（旬/月/年），时间粒度差数个数量级。**替代**：保留"确定性可复现"内核（同存档 + 同输入 ⇒ 同状态指纹）用于长跑对拍，不引入输入缓冲与 rollback buffer。
2. **引入 Unreal GAS / Replication Graph 式复制层** —— 依赖网络栈与属性系统，本项目存档即权威。**替代**：只借其三条所有权规则（单一 owner / 只复制投影子集 / 表现仅限外观），落成镜像只读契约 + "表现字段不得回写核心"守卫。
3. **立刻用 Cap'n Proto/FlatBuffers 替换 JSON 镜像** —— 当前瓶颈不是序列化吞吐而是"谁有权写"；过早换格式会移开注意力并改动存档兼容面。**替代**：保留 JSON；先做"按域/按需刷新、减少跨界次数"；真机验证显示瓶颈后再定点替换。
4. **按自然日定义"通道陈旧"（如 60 天阈值）** —— 属 SaaS 语境。**替代**：用**完整游戏业务周期**（≥1 游戏年 + 离线结算 + 跨旬/月 + 存档写读）作观察窗。
5. **以"遥测无流量"作为删除依据** —— Azure 明确要求先禁用 + 跨完整周期观察 + 观察窗内任何活动即重置。**替代**：遥测仅作辅助，证明责任落在"禁用 + 观察窗 + 状态指纹零差异"三件套。
6. **对 288 个写入点做无差别 1:1 搬迁** —— feature parity trap（旧行为/历史 workaround 被固化为需求）。**替代**：先按"影响模拟结果 / 纯表现 / 平台效应回执"三类分流，只对第一类执行搬迁（见 §9.2 末行）。
7. **在镜像侧引入 ECS 式 write group 机制** —— Kotlin 侧不是 ECS，不必照搬。**替代**：取其等价约束（镜像只读视图 + 写必走 C++ 事务 + 回执驱动），并优先让"镜像不可写"成为编译期/结构性事实而非纪律要求。

---

## 10. 盲区自查与完善建议

| # | 盲点/未验证假设 | 影响 | 处置 |
|---|---|---|---|
| 1 | **"回退臂永不产生稳态写入"未被结构性保证** | 若某域双实现在边缘输入下不一致，回退臂会写状态且删除通道后无处回传 | 本 ADR 已列为首要债务；短期靠 GateTest + 关闭域写入检测，长期靠 `executeRaw` 三态禁止写路径 |
| 2 | **槽位/派生列的双算漂移** | C++ 与 Kotlin 各自计算派生列（状态标记/槽位）会产生隐蔽分歧 | 每批明确"派生列唯一计算方"；跨语言对拍覆盖派生列 |
| 3 | **平台读数的时序假设**（墙钟/热档） | 玉符/热控批依赖读数推送，若推送时机变化会改行为 | 读数推送点在批内显式定义并加测试（同一 tick 内幂等） |
| 4 | **"删除通道 = 删除全量导入"的误读风险** | 全量导入是基线建立路径，必须保留 | 本 ADR §2.3 显式区分"反向增量通道（删）"与"全量导入（留）" |
| 5 | **规模未验证** | 5000 弟子档的长跑未在 CI 覆盖 | w3-13 长跑用中大规模夹具（≥1000 弟子），并在真机批补大规模观测 |
| 6 | **真机验证缺口** | 本环境无物理设备/模拟器 | w3-13 前安排真机批（存档往返 + 稳态零写入观察窗） |
| 7 | **文档数字漂移** | 本轮已实测发现 ActionId 基线漂移（170/1733 → **171/1734**） | 各批验收脚本统一从 `gen-action-ids.mjs` 实跑取值，文档不得手抄 |
| 8 | **并行批共享文件** | 五件套半提交会让干净检出无法编译 | 沿用 w2 协议；收口批干净检出复验 |
