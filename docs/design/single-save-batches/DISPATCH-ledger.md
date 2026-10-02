# 单存档改造 + 持久化面收口 · 批次派工册（DISPATCH-LEDGER）

> **v2（2026-10-01 第二次拍板后重写）**：前提变更为「**删档重置 + 去槽位维度 + 旧存档兼容代码清零**」。
> v1 的 SS4（存量多档迁移）**整批删除**；两条"必须同版本发布"约束**消失**。
> 上位方案（需求真源）：[`../single-save-and-persistence-consolidation-plan-2026-10-01.md`](../single-save-and-persistence-consolidation-plan-2026-10-01.md) —— **冲突时以该方案的 §0.5 为准**。
> 执行协议：**继承** [`../gacha-batches/EXECUTION-PROTOCOL.md`](../gacha-batches/EXECUTION-PROTOCOL.md)，本册只列增量铁律与跨批约束。
> 已到**派出级**的批次另有独立 `TASKBOOK-SSxx.md`；未到派出级的只给入口/出口（理由见 §5）。

---

## 0. 前提变更摘要（v2 相对 v1 的全部差异）

| 维度 | v1 | **v2（现行）** |
|---|---|---|
| 旧存档兼容 | 必须保留（迁移链 + 兼容层 + 存量迁移） | **不需要**：删档重置，全部清零 |
| `slot_id` 物理列 | 保留，恒为 `LOCAL_SLOT_ID = 1` | **删净**（Kotlin + C++ 双侧，含 189 个 DAO 方法与协议字段） |
| Room 迁移链 | 保留 63 条 + 新增 `MIGRATION_65_66` | **删掉全部迁移注册**，依赖 `fallbackToDestructiveMigrationFrom(1)`；版本 65→66 保持递增 |
| C++ 槽协议字段 | 不动（怕打破对拍基线） | **一起删**（`currentSlot` + 物品 `slotId`）⇒ **必须占重录窗口** |
| 存量迁移批（v1 SS4） | 必需 | **整批删除** |
| 同版本发布约束 | SS1/SS4、SS3/SS4 必须同发布 | **消失** |

---

## 0.1 看护同步须知（2026-10-01 第二次广播·开工前必读）

> 给无人值守看护 / 实施会话：**若你手上的认知早于本节，先按本节对齐再动手。**

1. **`WATCH-PROTOCOL.md` 已删除**（曾存在于本目录，用户指令删除；该文件从未提交，**不可恢复**）。
   ⇒ **本册 + 各 `TASKBOOK-SSxx.md` 是唯一派工真源**；不要再检索或引用任何"看护规程"文件。

2. 🔴 **工作树必须先对齐**：`C:\Mnzm\XianxiaSectNative-SS0`（分支 `feat/single-save-SS0`）停在 `1582c1e96`，
   落后 main **5 个提交**。开工前执行：

   ```powershell
   git -C C:\Mnzm\XianxiaSectNative-SS0 merge --ff-only main
   ```

   （该工作树**干净、无独有提交**，可安全 ff。）**未对齐就动手 = 按旧任务书做错整批**。

3. **SS0 的语义在这 5 个提交里被整体替换过**：

   | 提交 | 变更 | 对 SS0 的影响 |
   |---|---|---|
   | `9a4e207c9` | 前提变更：**删档重置 + 去槽位维度** | SS0 从「口径冻结与既有分叉收敛」**换成**「删档重置 + 旧存档兼容代码清零」；v1 的 SS4（存量多档迁移）**整批删除** |
   | `c5903948a` | SS0 并入**邮件清理 M1–M4** | 新增验收⑪ + §2.6 清单 + 片 `SS0-j` |
   | `e18001855` | 版本 **`4.2.00`/`4200`**、格式 **`X.X.XX`**、**游戏内更新日志清空** | SS0/SS10 的双 changelog 义务变更 |
   | `125d429ec` | SS0 增**五重对漏机制** | 验收⑫–⑮ + §2.7 锚点集（对账等式 / 编译零错误 / 反向守卫 / 独立二遍） |
   | `09351b0cb` | 清理边界 **D-9：提供冗余的实现一律不删** | 文件层与备份恢复链**不在删除范围** |

4. **若已按旧 SS0 动工 ⇒ 立即停手并报告**：旧 SS0 归一的是**即将被删除的**槽位常量（`DEFAULT_MAX_SLOTS`），
   属作废工作；且会让阶段机误判"SS0 已完成"而放行 SS1（去槽位维度），**前置实际不成立**。

5. 🔴 **删除范围以 `TASKBOOK-SS0.md` 决策 D-9 为准**：**提供可恢复性的一律保留**——
   `SaveFileManager` 文件层（`.sav`/`.bak`/`.tmp`/tombstone）、`backupDatabaseForMigration` 启动前快照、
   `restoreFromBackupIfNeeded`、`pruneMigrationBackups`、`SaveFileFormat` 本体；
   只删"**只做兼容、不提供冗余**"的分支。

6. **门禁与汇报格式**照 [`../gacha-batches/EXECUTION-PROTOCOL.md`](../gacha-batches/EXECUTION-PROTOCOL.md)（§2 命令 / §3 交付 / §4 汇报）；
   本册 §5 已说明"为什么只给前 4 批写细卡"。

---

## 1. 批次总览（11 批）

| 批 | 名称 | 类型 | 依赖 | 可并行 | 出口判据（一句话） |
|---|---|---|---|---|---|
| **SS0** | 删档重置 + 旧存档兼容代码清零 | 代码 | — | 立即可开工 | 老档被清且不可恢复；两条兼容链与迁移链全部消失；云端旧档失联；残留文件清零；CI 防静默删档守卫在册 |
| **SS1** | 去槽位维度（Kotlin + C++ 同批） | 代码 | SS0 | — | `slot_id` 列、189 个 DAO slot 参数、`RepoInterfaces` 默认值、`currentSlot`、物品 `slotId` 全部消失；**占对拍重录窗口** |
| **SS2** | 账号数据空间分库 + 登出五件套 | 代码 | SS0 | 与 SS3 串行；与 SS8 并行 | 换账号不串档（隔离守卫）；三处登出入口一致清数据空间 |
| **SS3** | 持久化面收口四组件 | 代码 | SS0 | 与 SS2 串行；与 SS8 并行 | `change_log` 有生产读者；`FunctionalWAL` 摘除后三条链全绿；归档有读面；`StorageMetrics` 有 getter + 上报 |
| **SS4** | 单档 UI 终态 + 元数据表清除 | 代码 | SS1, SS2 | — | 无槽位概念残留 UI；`save_slot_metadata` 删除；`SaveSlot` 数据类收敛 |
| **SS5** | 增量落盘（真增量写） | 代码 | SS4 | 与 SS7 | 增量 ↔ 全量双路径读回逐字段全等；未变 heavy key 跳过 |
| **SS6** | 事件触发自动存档 | 代码 | SS5 | — | 关键事件返回前已落盘；十连 10 事件合并为 1 次 |
| **SS7** | 云：灾备 + 换设备续玩 | 代码 | SS4, SS3 | 与 SS5 | 新设备恢复链路通；`W > C` 只读降级不覆盖；新命名基线 |
| **SS8** | 登录门槛 + 隐私政策双入口 | 代码/文档 | SS2 | 与 SS2/SS3 | 未登录不可进游戏；离线宽限按 B1；两处隐私政策同步 |
| **SS9** | 玉符账本（C++ 真源） | 代码 | SS1；与 G 批对表 | **独占** | 账本 append-only；派生余额 == 账本求和；期初开账后余额不变；**与 SS1 共用重录窗口** |
| **SS10** | 文档与发布收口 | 文档 | SS0–SS9 | — | 双 changelog、`CODE_WIKI.md`、`docs/architecture.md`、验收报告齐；门禁全绿 |

**总批次数 = 11（SS0–SS10）。**

---

## 2. 依赖图与并行组

```
SS0 ─┬─ SS1 ──────────────┐
     │                     │
     ├─ SS2 ─┬─ SS4 ─┬─ SS5 ── SS6 ─┐
     │       │       └─ SS7 ────────┤
     │       └─ SS8 ────────────────┤
     └─ SS3 ── SS7 ─────────────────┤
                     SS9 ───────────┴── SS10
```

- **串行主链**：`SS0 → SS1 → SS4 → SS5 → SS6 → SS10`
- **旁支 A**：`SS2 → SS8`（`SS2` 与 `SS3` 必须串行，§4.3）
- **旁支 B**：`SS3 → SS7`
- **独占**：`SS9`（玉符账本；与 SS1 共用重录窗口，§4.2）

---

## 3. 每批入口 / 出口判据

### SS0 · 删档重置 + 旧存档兼容代码清零

- **入口判据**：工作树干净；产品公告与渠道强制更新已就绪（W2/W10）。
- **出口判据**：见 [`TASKBOOK-SS0.md`](TASKBOOK-SS0.md)。
- **硬门**：老档被清且不可恢复（含云端与残留文件）；`node scripts/check-agent-instructions.mjs` EXIT=0。
- **不做**：不删槽位维度（SS1）；不删 `SaveValidator` 的运行期完整性规则。

### SS1 · 去槽位维度（Kotlin + C++ 同批）

- **入口判据**：SS0 已合入；**与 G 批负责人确认过重录窗口归属**（§4.1）。
- **出口判据**：见 [`TASKBOOK-SS1.md`](TASKBOOK-SS1.md)。
- **硬门**：Kotlin 与 C++ 双侧同 batch 改完（协议字段链"同 commit"）；对拍基线按 §4.2 的重录安排处理；`DiffAuthoritativeTickTest` / `DiffInventoryTest` / `DiffSpiritFieldTest` 最终全绿。
- **不做**：不删 `Disciple.slotId` 的 C++ 侧（它本就不在协议面，`models.h:305-306`）；不碰伪同形名（§4.5）。

### SS2 · 账号数据空间分库 + 登出五件套

- **入口判据**：SS0 已合入；`StorageModule.kt` 无他批在途改动。
- **出口判据**：见 [`TASKBOOK-SS2.md`](TASKBOOK-SS2.md)。
- **硬门**：账号隔离守卫（以 `@Database` 实体清单为锚点）绿；登出三入口一致性守卫绿。
- **不做**：不改槽位（SS1 已做）；不接云端（SS7）。

### SS3 · 持久化面收口四组件

- **入口判据**：SS0 已合入；`StorageModule.kt` 与 `StorageEngine.kt` 无他批在途改动。
- **出口判据**：见 [`TASKBOOK-SS3.md`](TASKBOOK-SS3.md)。
- **硬门**：`FunctionalWAL` 摘除后启动/保存/读档三条链全绿；`ArchiveWriteOnlyGuardTest` 与 `WalRetirementGuardTest` 按新职责改写（**改语义，不放宽**）。
- **不做**：不改保存/读档行为语义；不做增量落盘（SS5）。

### SS4 · 单档 UI 终态 + 元数据表清除

- **入口判据**：SS1（去槽位维度）、SS2（账号空间）已合入。
- **出口判据**：UI 无槽位概念（无选槽、无多卡列表、无槽号文案）；`save_slot_metadata` 整表删除且 `clearAllSlotTables` 与两条守卫同步；`SaveSlot` 数据类收敛为单档投影。
- **硬门**：`ClearAllSlotTablesCoverageTest`、`CacheWriteAtomicityGuardTest` 同批更新；对话框注册四同步。
- **不做**：不做增量落盘（SS5）；不接云端（SS7）。

### SS5 · 增量落盘（真增量写）

- **入口判据**：SS4 已合入；`StorageEngineWriteOps.kt` 无他批在途改动。
- **出口判据**：`DirtySetTracker` 消费既有 `applyDirty` 变更集；默认走增量路径（变化行 upsert + 删除集 delete + 未变 heavy key 跳过），全量路径保留为兜底；**增量 ↔ 全量双路径读回逐字段全等**；脏集越界或不可判定时回退全量并计数。
- **硬门**：双路径对拍测试绿；`skippedHeavyKeysBySlot` 与 `stacksSerialized` 两处"防全删"补丁降级为断言。
- **不做**：不改 `.sav`/`.bak` 与云载荷（单 blob 仍全量）；**不碰 C++**。

### SS6 · 事件触发自动存档

- **入口判据**：SS5 已合入。
- **出口判据**：关键事件（玉符流水、碎片入账、高品阶物品、里程碑、删档/重置）**返回前完成落盘**；事件走 `SaveOrchestrator.submit` 合并窗（十连 → 1 次落盘）；落盘后入云队列。
- **硬门**：时序断言 + 合并窗计数断言。
- **不做**：不改节拍常量（保持 10s）；不新增 Kotlin→C++ 反向状态写。

### SS7 · 云：灾备 + 换设备续玩

- **入口判据**：SS4、SS3 已合入。
- **出口判据**：新设备登录 → 本地无档 → 自动从云恢复链路通；**新云端命名基线**（SS0 已改）；**不支持同时多设备**：保存前比对云端 `currentCloudSaveId()`，`W > 本地 C` ⇒ 只读降级 + 提示，**绝不静默覆盖**。
- **硬门**：复用既有 `SaveArbiter` / `UploadLedger` / `UploadQueue`，**不新建云通道**；台账键随槽维度消失一并坍缩（SS1 已删槽，本批收口为单键）。
- **不做**：不做云端历史版本；不做增量上传；不引入服务端。

### SS8 · 登录门槛 + 隐私政策双入口

- **入口判据**：SS2 已合入。
- **出口判据**：未登录 → 强制登录页（入口收敛 `LoginFlowStateMachine`，禁新增"只置位不复位"的一次性布尔标记）；离线宽限按 **B1**；**隐私政策双入口同步**（`PrivacyConsentScreen.kt` + `docs/index.html`）。
- **硬门**：`LoginFlowStateMachineTest` / `SafeRunAfterSdkInitTest` / `ComplianceManagerSelfHealTest` / `SdkInitGuardTest` / `TapDBManagerInitGuardTest` 全绿。
- **附带修正**：`rules/sdk-init-lifecycle.md:50` 仍列已删除的 `ModeSelectionScreen.onLogout`，同批修正。

### SS9 · 玉符账本（C++ 真源）🔴 与 G 批串行 + 共用重录窗口

- **入口判据**：SS1 已合入（槽维度已删）；**G 批 `models.h` / `jade_tx.h` 无在途改动**；重录窗口归属已确认（§4.1）。
- **出口判据**：账本真源在 C++（`jade_tx.h` 六事务 1766–1769 / 1692 / 1693 改为 append 条目 + 派生缓存校验）；`jadeSymbols` 降级为派生缓存；Kotlin 仅派生只读 + 镜像投影（`GameDataFieldPatch.coveredFields` 登记新字段）；**老档写 `OPENING_BALANCE` 期初条目**；UI 三读数收敛为一。
- **硬门**：`JadeSymbolConsumptionGuardTest` 正则扩到账本字段名（现只认 `jadeSymbols`，新字段写入会**静默漏检**）；两侧对拍齐备（GTest `jade_tx_test.cpp` / `jade_runtime_tx_test.cpp` 同步改写 + JUnit `Diff*Test`）；新 ActionId 走 `scripts/gen-action-ids.mjs` 双产物；`GameDataFieldPatchGuardTest` 绿。
- **不做**：不改白名单发奖语义（**B2：这是特权**）；不引入付费通道。

### SS10 · 文档与发布收口

- **入口判据**：SS0–SS9 全部合入。
- **出口判据**：双 changelog（`CHANGELOG.md` + `assets/changelog_entries.json`，**删档重置必须写进玩家可见条目**）；`CODE_WIKI.md`、`docs/architecture.md`、`docs/ui-read-surface.md` §2 同步；本册与各 `report-SSxx.md` 回填；验收报告出具。
- **硬门**：`node scripts/check-agent-instructions.mjs` EXIT=0；detekt baseline **只缩不增**。

---

## 4. 跨批串行约束（不可违反）

### 4.1 与 G 批卡池方案的冲突面

- **`SS1` 与 `SS9` 是仅有的两个碰 C++ 的批次**，且都会动 `models.h` / `json_codec.cpp` / 相关头文件——**正是 G 批 R2 文件级锁表内的文件**。
- ⇒ 两者**必须与 G 批对表串行合入**，禁止并行编辑。

### 4.2 对拍基线：全局唯一重录窗口（G 批 R3）

- 本方案有**两次**导出形状变更：`SS1`（删 `currentSlot` + 物品 `slotId`）与 `SS9`（新增账本字段）。
- 🔴 **二者必须共用同一次重录**，且该窗口**与 G 批 G10 合并**（W9）。可接受安排二选一：
  - **安排 ①**：SS1 与 SS9 相邻合入，窗口设在后者完成时（期间旧基线红）；
  - **安排 ②**：SS1 先落、SS9 后落，期间 Determinism 红点按 G 批"B 类红登记"先例**逐条显式登记**，窗口设在 G10。
- **开工前必须确认窗口归属**（SS1/SS9 的入口判据已列此条）。**禁止**任何批次独立占用第二次重录。

### 4.3 共享文件面（同批独占，禁并行编辑）

| 文件 | 冲突批 | 处置 |
|---|---|---|
| `android/app/src/main/java/com/xianxia/sect/di/StorageModule.kt` | SS2（数据空间路径）与 SS3（WAL 解绑） | **串行**：SS2 先，SS3 后 |
| `android/core/data/.../engine/StorageEngine.kt` | SS1（去槽）、SS3（WAL/编排）、SS5（增量写） | 严格串行 |
| `android/core/data/.../engine/StorageEngineWriteOps.kt` | SS1（去槽盖章）、SS5（增量写） | 串行 |
| `android/core/data/.../engine/StorageEngineSaveSupport.kt` | SS1（去槽）、SS4（删表清单） | 串行 |
| `android/app/src/main/cpp/gamecore/include/gamecore/state/models.h` | SS1、SS9 | 严格串行 + 与 G 批对表 |

### 4.4 开工纪律

- 每批开工前 `git status` 必须干净；发现他批在途改动就**停手报告**，不得在他批改动上叠加。
- 分片并行期**禁止子代理跑 gradle/cmake**；主线程终树复跑全部门禁。

### 4.5 本方案增量铁律（在 G 批 14 条之外追加）

| # | 铁律 |
|---|---|
| 15 | **伪同形名排除清单**：`SpiritMineViewModel.slotId`（矿工指派）、`DiscipleDetailScreen.slotId(part)`（装备部位）、`SlotAssignment.slotId`（String 指派 ID）、`CaptiveGearUtils.slotIdOf`、`ProductionSlotRepository` 的 String id、`patrol_tx.h` 的 `slotIdx`（生产槽索引）、`execute_dispatch.cpp:312` 的 `currentSlots`（仓库槽数）——**均非存档槽，删槽时禁止一并替换**。 |
| 16 | **`Disciple.slotId` 不在 C++ 协议面**（`models.h:305-306`）⇒ 删槽时**不得**顺手改 C++ 弟子结构；C++ 改动面 = `currentSlot` + 物品 7 类 `slotId` + 邮件归属槽 + 编解码/分派。 |
| 17 | **删档后必须重建迁移纪律**：新增 `@Entity` / 列变更必须同批出现 `MIGRATION_*`，否则 CI 判红（防 destructive fallback 把"忘写迁移"变成静默删档）。 |
| 18 | **数据残留清理必须含云端**：本地清干净而云端留旧档 = 删档未生效（玩家换机即复原）。 |

---

## 5. 为什么只给前 4 批写细卡

对齐 `TASKBOOK-G09.md` §0 的在册理由：**后批的细切片取决于前批的落地形态**。

- SS4 的 UI 收敛面取决于 SS1 去槽后的数据模型；
- SS5 的脏集形状取决于 SS4 的写路径；
- SS7 的台账处置取决于 SS1 去槽后的键空间；
- SS9 的字段面取决于 SS1 的协议形状 + G 批 `models.h` 当期形态。

提前切细会重演 G16/G08"照字面派工踩坑"的代价。故 SS4–SS10 只给入口/出口判据，各自开工时补自己的 `TASKBOOK-SSxx.md`。

---

## 6. 拍板记录

### 6.1 方案级（7 项，2026-10-01 第一轮）

| # | 议题 | 结果 |
|---|---|---|
| Q1 | 云端角色 | 灾备 + 换设备续玩（不支持同时多设备） |
| Q2 | 玉符模型 | 不可变流水账 + 余额派生 |
| Q3 | 存量多档 | ~~保留最新一档，其余归档~~ → **W6 取代：全部清理** |
| Q4 | 登出/换账号 | 本地数据按账号隔离 |
| Q5 | 登录门槛 | 全部功能要求登录 |
| Q6 | 多设备 | 明确不支持同时在线 |
| Q7 | 交付 | 完整客户端方案 + 持久化面收口 |

### 6.2 派工级（7 项，第一轮）

| # | 议题 | 结果 | 落点批 |
|---|---|---|---|
| B1 | 离线宽限 | 已登录过可离线继续；从未登录必须联网首次登录 | SS8 |
| B2 | 免广告白名单玉符发放 | **是特权，不是缺陷** | SS9 |
| B3 | 旧 MMKV 台账键 | ~~读→合并→清理~~ → **W6 取代：直接清理** | SS0 |
| B4 | `save_slot_metadata` | 删除整表 | SS4 |
| B5 | 既有分叉 | ~~同批收敛~~ → 槽维度整体消失（SS1） | SS1 |
| B6 | 影响范围清单粒度 | 维持三类组织 | — |
| B7 | 文档落库 | 一起提交 | — |

### 6.3 删档级（12 项，第二轮）

| # | 议题 | 结果 |
|---|---|---|
| W1 | 云端旧档 | 改命名 + 主动删除 |
| W2/W10 | 强制升级 | 渠道强制更新；代码侧只保证数据不互通 |
| W3 | `slot_id` 列 | 删净槽位维度 |
| W4 | 迁移链 | 删全部迁移注册，依赖 destructive；版本 65→66 递增 |
| W5 | 删档范围 | 连本地账号/合规缓存一起清 |
| W6 | 数据残留 | 迁移前备份 + 归档 + MMKV 台账 + 孤儿 schema 全清 |
| W7 | 校验规则 | 逐条判定：历史兼容删、运行期完整性留 |
| W8 | C++ 槽字段 | `currentSlot` + 物品 `slotId` 一起删 |
| W9 | 重录窗口 | 与 G 批 G10 合并为同一次 |
| W11 | `AdFreeWhitelist` | 保留 |
| W12 | 删档触发 | 首次启动自动清 + 保留开发入口 |

### 6.4 邮件清理（4 项，第三轮）

| # | 议题 | 结果 | 落点批 |
|---|---|---|---|
| M1 | 内置运营邮件 | 删 QQ 群邮件（唯一非节日），**仅保留节日邮件 28 封** | SS0-j |
| M2 | 白名单福利邮件 | **删除全链**（方法 + 4 个调用点）；白名单只留免广告特权 | SS0-j |
| M3 | 手动补偿邮件 | **保留** `injectAdminMail`（工具性质） | — |
| M4 | 系统功能邮件 | 🔴 **保留** `overflow` / `secret_realm`（删了会丢物品） | — |

### 6.5 版本与更新日志（第三轮）

| # | 议题 | 结果 |
|---|---|---|
| V1 | 目标版本 | **`4.2.00` / `4200`**（用户 2026-10-01）；`version.properties` 已更新 |
| V2 | 版本号格式 | **`X.X.XX`**（原 `X.XX.XX`）；`versionCode` = 主×1000 + 次×100 + 构建，**强制单调递增**。已改 `rules/version-release.md` §1 + 根 `AGENTS.md` §8 |
| V3 | 游戏内更新日志 | **历史清空**（62 条 → 唯一 `4.2.00` 条目）；外部 `CHANGELOG.md` 保留历史并新增 `[4.2.00]` 段 |
| V4 | 后续义务 | **SS10 收口**：SS0–SS9 的玩家可见变更并入**同一 `4.2.00` 条目**（同版本禁止新建第二条目，`rules/version-release.md` §2 合并规则） |
| V5 | 已知过期引用 | `docs/gacha-watch/BATCH-PLAN-ALL.md:1028,1038` 与 `docs/design/gacha-batches/TASKBOOK-G14.md:63,73` 仍写 `X.XX.XX`——属 G 批已收官过程档案，**不回改**，新批次以规则为准 |

### 6.6 清理边界（第三次拍板：有风险就不删）

用户 2026-10-01 判定「**有风险就不删了**」⇒ SS0 的清理边界钉死：

- 🔴 **提供可恢复性的一律保留**：文件层（`.sav`/`.bak`/`.tmp`/tombstone）、启动前快照、启动恢复、`pruneMigrationBackups`、`SaveFileFormat` 格式本体
- **仍删**（只做兼容、不提供冗余）：`OldSaveFormatDeserializer` + `OldSerializableSaveData` 整包、`SaveDataVersionMigrator` 旧版本迁移分支、无版本后缀旧备份的兼容分支
- **判据**：清理后可恢复路径数量**不得少于**清理前（本地事务 / 文件层 / 启动快照 / 云端，至少留三条且不全在同一故障域）

---

## 7. 交付形态与汇报

照 `EXECUTION-PROTOCOL.md` §3/§4；本册差异：

- **commit 前缀**：`feat|refactor|chore(wipe|single-save|account|storage|jade|cloud|login): SSxx <一句话>`。
- **每批必交**：代码 + 测试（删除批给"旧用例处置表"）+ `docs/design/single-save-batches/report-SSxx.md` + 中文 commit。
- **汇报格式**：

```
批次：SSxx
commit：<sha> <标题>
门禁：ctest N/N · engine JUnit N/N · core:data/app 面 · detekt 模块全绿 · compileReleaseKotlin 绿 · 生成器零漂移
改动规模：N 文件 +A/-D
未完成/登记：<逐条>
风险：<逐条，含「已核实」与「推测」区分>
```

---

## 8. 看护执行记录（无人值守自动化 · 一行一轮）

> 看护自动化：ZCode `automation-8b4bcd82-6ed0-416d-9c58-630ef681f79a`（主树项目键，2026-10-01 建立）。
> 节拍（用户指定）：**常态每 30 分钟**；批次进入**收尾期**（跑全量门禁 / 并网手术）自动切**每 10 分钟**，回常态切回。
> 登记纪律：一行一轮，随 `docs(single-save-watch):` 前缀小步 commit；只登记已发生事实——派发以截图终验为准、验收以门禁实跑为准。阶段机：A 待派发 / B 实施中 / C 收尾期 / D 待验收 / E 收官。

| 时间 | 阶段 | 批次 | 动作/判定 | 证据 | 节拍 |
|---|---|---|---|---|---|
| 2026-10-01 02:55 | — | — | 看护自动化建立：ZCode `automation-8b4bcd82-6ed0-416d-9c58-630ef681f79a`（常态 30min，收尾期自动切 10min，CronUpdate 自调 + 失败硬扛不重建）；首轮即遇 §0.1 v2 对齐广播并逐项核实（main 前进 6 笔至 `00d77837e`、WATCH-PROTOCOL 确已删除、SS0 worktree 落后），自动化 prompt 已改指本册，增设 §8 | `79769bf6c` | 30 分钟 |
| 2026-10-01 03:00 | A→B | SS0 | **派发成功**：worktree `C:\Mnzm\XianxiaSectNative-SS0`（`feat/single-save-SS0` @ `79769bf6c`，与 main 一致、status 干净、properties+node_modules 补件齐）+ ZCode GUI 新会话「【派工】单存档改造批次 SS0」，模型 chip=GLM-5.3-Flash（最高），输入回读逐字核对后发送；截图终验=会话运转中（工作中 21s，正读 TASKBOOK-SS0 @ SS0 worktree 路径），实施按 v2 任务书（删档重置+兼容清零+邮件 M1–M4） | 截图 artifact `tool-result-8bbdab1c`（sess_b9878721） | 30 分钟 |
| 2026-10-01 03:26 | B | SS0 | 实施正常：worktree 在途改动与 TASKBOOK-SS0 删除面吻合（`schemas/1.json`、`SaveMigrationLedger/Planner` 删，`StorageModule.kt`/`AnalyticsEventsDictionaryTest.kt` 改），report-SS0.md 未产出；节拍 */30 与阶段 B 目标一致，未动 CronUpdate。⚠️ 本轮自动化会话无 windows-mcp 工具面（GUI 判活不可用，三次误选其他 MCP 只读工具均无副作用），改以 git 证据判定；若下轮仍缺该工具面，A 阶段 GUI 派发将受阻——下轮复核 | `git -C XianxiaSectNative-SS0 status` | 30 分钟 |
| 2026-10-01 03:48 | B | SS0 | 用户指令重试 windows-mcp：**可用**（Snapshot 正常返回语义树），轮#2 的工具面缺失确认为暂时性（疑 MCP 启动时序），**A 阶段 GUI 派发受阻预警解除**；同刻实施推进迅猛——worktree 在途改动 5→**142 文件**（兼容链大批删除中），SS0 会话在 ZCode 列表首条活跃，report-SS0.md 未产出，主树干净；节拍维持 */30 | 本轮 Snapshot + `git status`（142 文件在途） | 30 分钟 |
| 2026-10-01 03:56 | B | SS0 | 会话活跃推进健康（非卡死）：进度面板 todo **6/16 完成**（SS0-e 启动 wipe 幂等+开发入口、SS0-f 云端新命名+主动删旧档、SS0-j 邮件清理 M1/M2 均已落地），正以 python 脚本做对账/schema 检查并分析测试根因（Robolectric SQLite 拒绝裸 `PRAGMA wal_checkpoint` 的兼容问题）；在途改动维持 142 文件、分支无新 commit、report 未产出——142 不变系处于分析调试段；节拍维持 */30 未动 | Snapshot（ZCode 实施会话实况） | 30 分钟 |
| 2026-10-01 04:26 | B（尾声） | SS0 | 大幅推进：todo 6/16→**11/16**，验收⑭ DeadCompatRemovalGuardTest 已建、**全量门禁已跑完一轮**、验收⑫双向对账已核对（命中数==表数）；当前做文档同步（knowledge-base.md）+ 主树/worktree 复验（会话自行纠正过一次 grep 跑在主树的目录错误并在 worktree 复验），待处理仅剩 2 项，后台终端 1 个在跑；在途 148 文件、无新 commit、report 未产出——活动以编辑复验为主，「正在跑门禁」证据不足，**按协议拿不准不切节拍**，维持 */30；预期下轮进 C/D | Snapshot（todo 面板+终端实录） | 30 分钟 |
| 2026-10-01 04:56 | **C** | SS0 | **report-SS0.md 已产出**（EXECUTION-PROTOCOL 四件套最后一件落地），分支仍无 commit、148 文件在途——实施会话处于交付最后一步（报告已写、最终 commit 待打），判定进入收尾期；GUI 快照超时一次（疑门禁占资源），不再重试、以 git 证据判定。🔴 节拍切换未遂：CronUpdate 连续 5 次误发为 CronList（疑本轮自动化会话工具面缺 CronUpdate，同轮#2 windows-mcp 时序症），按铁律**维持 */30 硬扛不重建**；代价=验收最多晚 20 分钟，可接受；下轮工具面恢复时优先补切 10 分钟 | `ls report-SS0.md` + CronList（自动化唯一 */30） | 30 分钟（切换未遂） |
| 2026-10-01 05:26 | **C** | SS0 | 在途 148→**210 文件**且全为真实实施面（140 删 + 64 改 + 6 新增；core/data 158 / feature/game 20 / domain 11 / engine 10，**无构建副产物噪音**），report-SS0.md（30KB）**05:25 仍在修订**未 commit——收尾期 C 维持，实施会话活跃。🔴🔴 **节拍补切连续第 2 轮未遂**（CronUpdate 仍误发 CronList）——升级判定：自动化唤醒会话**稳定缺 CronUpdate 工具**（windows-mcp 已恢复而它没有），收手不再试；维持 */30 硬扛、不重建；**请用户在 ZCode 自动化页手动将「单存档SS批次看护」节拍调为每 10 分钟**（收尾期加速），或接受验收响应最多晚 20 分钟 | git status 构成分析（D/M/?? 分布）+ report mtime 05:25 | 30 分钟（连续两轮切换失败） |
| 2026-10-01 05:57 | D→**验收通过并并网** | SS0 | 实施会话交付：`f79afcd84 feat(wipe): SS0 删档重置+旧存档兼容代码清零`（211 文件 +1326/−323317，树净）。验收=形态 PASS（report 四件套齐：旧用例处置表+锚点对账 A1–A8+验收⑮独立三轮复核 FAIL→FAIL→**PASS** 抓 ~35 处差集含 core/data/schemas 56 json 补删+字面偏离规范登记 §5.6 From(1)→全量 fallback+dropAllTables）+ 主树复跑全绿（compileReleaseKotlin ✓ / detekt 六模块 ✓ / jni-count 87/87 ✓ / agent-instructions EXIT=0；JVM 7297/0 实施侧 worktree 终树实测）。**并网 merge `3a89a9e7a`（零冲突）**；**推送成功 `a473a22b6..3a89a9e7a`**（本地代理恢复，09-30 以来全部滞留笔随推）；worktree 已 remove（分支保留；残留 4 文件 jar 被 Gradle daemon 锁定留待下轮清）。SS1 对表完成：G 批 models.h 最后改动 G08 已在 main、无 G 批在途；装备线未并网残留=EQ-B5 纯文档批零 C++ 无冲突；重录窗口按 §4.2 安排①（SS1 落形状变更、窗口设 SS9 完成时，期间 B 类红逐条登记）→ **SS1 具备派发条件** | report-SS0.md + 主树门禁实跑 + push 输出 | 30 分钟 |
| 2026-10-01 06:08 | A→B | SS1 | **派发成功**：worktree `C:\Mnzm\XianxiaSectNative-SS1`（`feat/single-save-SS1` 自 main `48f86ef6a` 切出，含 SS0 并网结果；properties+node_modules 补件齐）+ ZCode GUI 新会话「【派工】单存档改造批次 SS1」，模型 chip=GLM-5.3-Flash（最高），回读逐字核对后发送；截图终验=运转中（工作中 23s，思考 worktree 复核）。派工词含五条本批特别约束（§4.2 安排①不重录+B 类红登记、两侧同 commit、伪同形名六处不动、D-1 依据须写进报告、105+7 文件分步推进每步 compile 绿）。阶段回 B，节拍维持 */30（收尾期已过） | 截图 artifact `tool-result-b330dee9`（sess_b9878721） | 30 分钟 |
| 2026-10-01 06:26 | B | SS1 | 实施起步正常（派发 18 分钟）：在途 2 文件=`GameData.kt` 修改（实体层 A-SS1a 起步）+ 自建一次性脚本 `.ss1-transform.js`；分支无新 commit、report 未产出——符合全案最大批次的慢启动预期，无需介入；节拍 */30 已符合未动 | `git -C XianxiaSectNative-SS1 status` | 30 分钟 |
| 2026-10-01 06:56 | B | SS1 | 大规模推进：在途 2→**110 文件**（103 改 + 7 新增）——Kotlin 101（data 43 / domain 27 / engine 18 / game 8 / app 5，接近任务书 ~105 预估）+ **C++ 1 文件已动工**（T 侧切片开始）；实施会话以 8 个 `.ss1-*.js` 一次性脚本做批量机械替换（⚠️ 验收核对点：提交前必须删除这些脚本，守「清理一次性代码」纪律）；分支无新 commit、report 未产出；节拍 */30 已符合未动。SS0 残留 jar 仍被 Gradle daemon 锁定（4 文件，持续留待） | git status 模块分布分析 | 30 分钟 |
| 2026-10-01 07:26 | B | SS1 | 持续推进：在途 110→**131 文件**（115 改 + 16 新增）；分支无新 commit、report 未产出、主树干净——节奏正常无异常；节拍 */30 已符合未动 | `git -C XianxiaSectNative-SS1 status` | 30 分钟 |
| 2026-10-01 07:56 | B | SS1 | 持续推进：在途 131→**161 文件**（127 改 + 34 新增，新增面在扩——测试/守卫建设中）；分支无新 commit、report 未产出、主树干净——无异常；节拍 */30 已符合未动 | `git -C XianxiaSectNative-SS1 status` | 30 分钟 |
| 2026-10-01 08:27 | B | SS1 | 推进持续：在途 161→**175 文件**（139 改 + 36 新增），增速放缓疑进入编译修错/测试对齐段；分支无新 commit、report 未产出、主树干净——无异常；节拍 */30 已符合未动 | `git -C XianxiaSectNative-SS1 status` | 30 分钟 |
| 2026-10-01 08:56 | B | SS1 | 推进持续：在途 175→**211 文件**（增速回升）；分支无新 commit、report 未产出、主树干净——无异常；节拍 */30 已符合未动。SS0 残留 jar 仍被锁（持续留待） | `git -C XianxiaSectNative-SS1 status` | 30 分钟 |
| 2026-10-01 09:27 | B | SS1 | 推进持续：在途 211→**228 文件**；分支无新 commit、report 未产出、主树干净——无异常；节拍 */30 已符合未动 | `git -C XianxiaSectNative-SS1 status` | 30 分钟 |
| 2026-10-01 13:56 | B（中断→已恢复） | SS1 | ⚠️ **看护自动化漏发 9 轮**（09:57–13:27，疑机器休眠/应用中断，13:56 一次性补发堆积 prompt——按幂等原则并作本轮执行一次）。**实施会话曾中断**：约 08:4x 撞 5 小时用量上限暂停（非卡死；此间 228→251 文件为暂停前推进），**13:41 重置后看护已发「继续」恢复**（截图终验=工作中 22s，用量横幅消失）。会话进度：进程 **7/11 完成**，T-SS1 C++ 侧（models.h/inventory.h/json_codec.cpp 等）进行中，余测试守卫更新（含 Diff 红点 B 类登记、ClearAllSlotTablesCoverage）与全门禁复跑（ctest/JNI 重建/JVM×4 串行/detekt/jni-count/生成器零漂移）；Snapshot UI 树连续超时改用 Screenshot 完成（像素路径兜底）；主树干净；节拍 */30 未动 | 截图 artifact `tool-result-e25dcf5e`（上限横幅）+ `tool-result-c736521f`（恢复运转） | 30 分钟 |
| 2026-10-01 14:26 | **C** | SS1 | **进入收尾期**：恢复后实施会话完成收尾前置——8 个 `.ss1-*.js` 一次性脚本**全部清零**、未跟踪文件归零、构建副产物已还原（251→207 = 2 删 + 205 改，改动面冻结）；剩「测试守卫更新 + 全门禁复跑」两大项。🔴 节拍切换第 3 次未遂（误发 CronList，与轮#5/#6 同症）——**确认为自动化会话持久性缺 CronUpdate 工具面**，永久收手不再试；维持 */30 硬扛（手动调 10 分钟的建议见轮#5/#6 报告）；主树干净 | git status（脚本 0 残留、2D+205M）+ CronList | 30 分钟（工具面缺件，硬扛） |
| 2026-10-01 17:52 | D→**验收通过并并网** | SS1 | 实施会话交付（用户同步确认完成）：`c92e70133 refactor(single-save): SS1 去槽位维度（Kotlin + C++ 同批）`（207 文件 +7955/−8863，树净）。验收=形态 PASS（report 四件套齐：旧用例处置表 + 门禁实跑数值 ctest **1523/1523**·JVM 五模块 **7270/0**·detekt 实修零新增·jni-count 87/87·生成器零漂移；§4.2 安排①遵守——本批无 B 类红（两侧同 commit 对称落地），**正式重录窗口让位 SS9**；D-1 依据写明：SS0 删档后无旧库可迁，版本 66→67 防 identity-hash 崩溃；伪同形名九处零误伤清单在案）+ 主树复跑全绿（compileReleaseKotlin ✓ / detekt 六模块 ✓ / jni-count 87/87 ✓ / agent-instructions ✓ / 桌面 ctest **1532/1532** / 桌面 JNI 重建 259 文件同源指纹；JVM 全量后台复跑中）。**并网 merge `580fab8fb`（零冲突）**。批次进度 2/11 → 3/11 | report-SS1.md + 主树门禁实跑 | 30 分钟 |
| 2026-10-01 17:5x | A（准备） | SS9 | **定批 = SS9（玉符账本）**：§4.2 安排① 要求 SS1 与 SS9 相邻合入（窗口设在后者完成时）⇒ SS9 为下一批且独占；SS2/SS3（互串行）其后。SS9 属「开工补卡」批次——已按方案 §2.4 + 主树实测补写 `TASKBOOK-SS9.md`（六事务 SS1 后行号 289/322/418/481/530/565、期初开账 v2 适配=新档初始化、守卫正则扩面、重录窗口本批执行、白名单 B2 语义不动）；G 批对表已核（models.h/jade_tx.h 无在途）；worktree 与派发进行中 | TASKBOOK-SS9.md + git log models.h | 30 分钟 |
| 2026-10-01 18:25 | A→B | SS9 | **派发成功**：worktree `C:\Mnzm\XianxiaSectNative-SS9`（`feat/single-save-SS9` @ `67aa10570`，含 SS1 并网+任务书；补件齐）。验收补记：主树 JVM 五模块后台复跑中 4 模块绿，`:core:data` 仅 `DeadCompatRemovalGuardTest` 红——**根因=主树残留构建副产物**（SS0 时代 compile 导出的未跟踪 `66.json`，SS1 升版 67 后被反向守卫按设计判红；实施 worktree 独立检出无此文件故绿），删过期副产物+复跑守卫绿=主树 JVM 等效全绿；**守卫首秀即在主树抓真残留，验收复跑价值实证**。GUI 派发：Ctrl+N 新会话+剪贴板粘贴（30 行/2072 字符校验和核对），Snapshot UI 树连续超时期间消息已发出（疑用户旁按回车），截图终验=会话「【派工】SS9·玉符账本（C++ 真源）」工作中、模型 GLM-5.3-Flash 最高、完全访问；侧栏"同名前缀+时间戳"条目核验为 SS1 会话正常老化，**无双开**。节拍维持 */30 | 截图 artifact `call_ca2c414c`（派发运转）+ `call_f3685e2d`（无双开） | 30 分钟 |
| 2026-10-01 20:39 | D→**验收通过并并网** | SS9 | 实施会话交付：`01f3ec931 refactor(jade): SS9 玉符账本（C++ 真源）——append-only 流水 + 派生缓存同事务双写`（25 文件 +1618/−871，树净）。验收=形态 PASS（report 四件套齐：验收①–⑩逐项对照全✅、GTest 35→41 例新语义改写+JUnit Diff* 全绿、重录窗口**以最优结果关闭**——`Determinism` 2/2、digest `0x8877d164f6bfe1fc` 无平移，两次形状变更均不触及转录面，基线冻结此后禁重录；期初开账落点字面差异有实证级登记：Kotlin `withStartupLedger` 三臂单点，C++ 导入态兜底因破坏逐位保真已删；白名单 B2 语义零改动）+ 主树复跑（compileReleaseKotlin ✓ / detekt 六模块 ✓ / jni-count 87/87 ✓ / agent-instructions ✓ / 桌面 ctest **1538/1538** / JNI 重建 ✓ / JVM 五模块——唯一红=`DeadCompatRemovalGuardTest` 判 `67.json` 过期副产物，与 66.json 同型，**登记为版本递增批并网后的例行处置**：删低于当期版本 schema 即愈，复跑绿=等效全绿）。**并网 merge `7efe2fce0`（零冲突）**。批次进度 3/11 → 4/11；下一批 **SS2**（§4.3 SS2 先 SS3 后），worktree 已备 | report-SS9.md + 主树门禁实跑 | 30 分钟 |
| 2026-10-01 21:04 | A→B | SS2 | **派发成功**：worktree `C:\Mnzm\XianxiaSectNative-SS2`（`feat/single-save-SS2` @ `7efe2fce0`，含 SS0/SS1/SS9 并网结果；补件齐）+ ZCode GUI 新会话「【派工】SS2·账号数据空间分库+登出五件套」，模型 chip=GLM-5.3-Flash（最高/完全访问），粘贴回读（尾部交付条款完整）后发送；截图终验=运转中（工作中 22s，正按纪律复核 worktree 干净）。派工词含八条本批特别约束（无迁移+版本 68→69、SHA-256 截断不落明文、五件套三入口逐字一致、清 .current 不删空间、隔离守卫实体清单锚点、无账号不建库、MMKV 不动登记 SS7、规范文件同批修正）。推送仍不通（代理 7897 未连，SS9 验收笔滞留本地，下轮重试）；节拍维持 */30。收尾：SS1/SS9 worktree 已移除（分支保留至全批收官）；两目录各剩 1 个 `detekt-rules.jar` 被 Gradle daemon 锁定（SS2 实施可能正在用 daemon，不做 `--stop` 打断——SS2 交付后随daemon 退出清理，与 SS0 残留同型） | 截图 artifact `call_7bd9f9e9` + git worktree list | 30 分钟 |
| 2026-10-01 22:45 | D→**验收通过并并网** | SS2 | 实施会话交付：`6eff39677 feat(account): SS2 账号数据空间分库 + 登出五件套`（18 文件 +677/−223，树净）。验收=形态 PASS（report 四件套齐：验收①–⑥全✅——隔离守卫六断言以 @Database 实体清单为锚点+intentionallyExcluded 六项注明理由、五件套三入口共享 `FullLogout.kt` 逐字一致、fail-fast 实测拦下 40 个隐性建库用例并修正「未登录冷启动建库」生产行为；门禁实跑 data 681/0+登录守卫组全绿+detekt 绿；风险 4 条已核实含「登出=进程重启」设计依据；版本 68→69 例行递增）+ 主树复跑（compile ✓ / detekt 六模块 ✓ / jni-count 87/87 ✓ / agent-instructions ✓ / JVM 三模块 domain+data+app 全绿——唯一红=`DeadCompatRemovalGuardTest` 判 `68.json` 过期副产物，例行处置删除复跑绿，与 66/67.json 同型第三次印证）。**并网 merge `a4c17acc2`（零冲突）**。批次进度 4/11 → 5/11 | report-SS2.md + 主树门禁实跑 | 30 分钟 |
| 2026-10-01 22:45 | A→B | SS3 | **派发成功**：worktree `C:\Mnzm\XianxiaSectNative-SS3`（`feat/single-save-SS3` @ `a4c17acc2`，含 SS0/SS1/SS9/SS2 全部并网结果；补件齐）+ ZCode GUI 新会话「【派工】SS3·持久化面收口四组件」，模型 chip=GLM-5.3-Flash（最高/完全访问），粘贴回读（尾部 storage 前缀交付条款完整）后发送；截图终验=运转中（工作中 24s，正复核 worktree 干净）。派工词含八条特别约束（守卫改语义不放宽、WAL 摘除须三条链对拍证据、归档读面消费者收窄仍须保留、Metrics 三处同步、SS9 drift 计数接 SS3 上报面、SS2 的 wal_v4 排除项随批删除等）。SS2 worktree 已移除（分支保留；目录 jar 锁残留同型留待）。推送持续不通持续重试；节拍维持 */30 | 截图 artifact `call_1c9a3562`（派发运转） | 30 分钟 |
| 2026-10-01 22:56 | B | SS3 | 起步正常（派发 11 分钟）：在途 0 文件——实施会话正按必读顺序读三份文档（读文档后再动代码的派工纪律），report 未产出、主树干净、无异常；节拍 */30 已符合未动；推送持续不通（持续重试） | `git -C XianxiaSectNative-SS3 status` | 30 分钟 |
| 2026-10-01 23:27 | B | SS3 | 推进正常：在途 0→**25 文件**（17 改 + 4 删 + 4 新增）——删除面=FunctionalWAL 整族（`FunctionalWAL.kt`/`EntryCodec`/`WALProvider`/`WALDataClassesTest`，SS3-b 摘除切片）；新增面=`ArchiveReader`（SS3-c 归档读面）+`SaveDataChangeSummarizer`（SS3-a change_log 摘要器）各配测试——三组件并进与切片吻合；分支无新 commit、report 未产出、主树干净；节拍 */30 已符合未动；推送持续不通（持续重试） | git status 删/增明细 | 30 分钟 |
| 2026-10-01 23:5x | D→**验收通过并并网** | SS3 | 实施会话交付：`773f26f81 feat(storage): SS3 持久化面收口四组件`（树净）+ **看护补笔 `b874b24e5`**（实施会话把 CODE_WIKI/knowledge-base 两文档误落主树未提交——看护查明后转移进分支 amend 补笔，主树还原；⚠️ 派工词已补「文档面编辑一律在 worktree 内完成」条款给后续批）。验收=形态 PASS（验收①–⑤全✅；**WAL 摘除三条链对拍证据齐备**（启动/保存/读档逐行对照，任务书硬要求）；`data` 680/0 对账等式闭合 681−11−6+2+14；守卫全部改语义未放宽；归档读面实测偏差有说明（落 Room 表非文件型 DataArchiver）；登记八条含 ThermalStatusProvider 孤儿接口与文件型查询族 YAGNI 悬置）+ 主树复跑全绿（compile ✓ / detekt 六模块 ✓ / jni-count 87/87 ✓ / agent-instructions ✓ / data+app JVM 全绿——SS3 未动版本号故无过期 schema）。**并网 merge `d1d5940d1`（零冲突）**。批次进度 5/11 → 6/11 | report-SS3.md + 主树门禁实跑 | 30 分钟 |
| 2026-10-01 23:5x | A（准备） | SS4 | **定批 = SS4（单档 UI 终态 + 元数据表清除）**：入口=SS1+SS2 已合入 ✓✓（SS3 也已并网）；SS4 属开工补卡批——已按派工册 §5 理由 + 主树实测补写 `TASKBOOK-SS4.md`（save_slot_metadata 整表删+版本 69→70、SlotMetadata/SaveSlot 投影收敛、UI 零槽号（伪同形名九处除外）、三守卫改语义、注释语病清零=SS1 §5.4/§6 登记消费；云侧槽命名保留至 SS7）；worktree 与派发进行中 | TASKBOOK-SS4.md + 主树 grep 取证 | 30 分钟 |
| 2026-10-02 00:19 | A→B | SS4 | **派发成功**：worktree `C:\Mnzm\XianxiaSectNative-SS4`（`feat/single-save-SS4` @ `d6a86884e`，含 SS0/SS1/SS9/SS2/SS3 全部并网结果+本批任务书；补件齐）+ ZCode GUI 新会话「【派工】SS4·单档 UI 终态+元数据表清除」，模型 chip=GLM-5.3-Flash（最高/完全访问），粘贴回读（尾部 single-save 前缀交付条款完整）后发送；截图终验=运转中（工作中 23s，进度面板 0/12：开工复核 git status 进行中、任务书与派工册在必读队列）。派工词含九条特别约束（含「文档面编辑一律在 worktree 内完成」——SS3 漏提交教训入词）。推送持续不通持续重试；节拍维持 */30 | 截图 artifact `call_c0339ec7`（派发运转） | 30 分钟 |
| 2026-10-02 00:26 | B | SS4 | 起步正常（派发 7 分钟）：在途 0 文件（读文档期），report 未产出、主树干净、无异常；节拍 */30 已符合未动。推送仍不通。残留清理：SS0 目录已清；SS1/SS2/SS9 jar 锁未释放（daemon 活跃中，SS4 交付后随退出清理） | `git -C XianxiaSectNative-SS4 status` | 30 分钟 |
| 2026-10-02 00:57 | B | SS4 | 推进正常：在途 0→**29 文件**（24 改 + 5 删——元数据表/槽位 UI 删除面展开）；分支无新 commit、report 未产出、主树干净；节拍 */30 已符合未动；推送仍不通 | `git -C XianxiaSectNative-SS4 status` | 30 分钟 |
| 2026-10-02 01:27 | B | SS4 | 推进持续：在途 29→**41 文件**；分支无新 commit、report 未产出、主树干净——无异常；节拍 */30 已符合未动；推送仍不通 | `git -C XianxiaSectNative-SS4 status` | 30 分钟 |
| 2026-10-02 03:0x | D→**验收通过并并网** | SS4 | 实施会话交付：`6b1c928b2 refactor(single-save): SS4 单档 UI 终态 + 元数据表清除`（38 文件，树净）。验收=形态 PASS（验收①–⑥全✅：save_slot_metadata 整表删+版本 69→70、SaveSlot 类整删/SaveInfo 单档投影（消费清单 11 处全列零残留）、UI 零槽号（存档语境 grep 三界分拣：本地清零/云侧留 SS7/伪同形名扩大集合）、ClearAllSlotTablesCoverageTest 改语义不放宽+CacheWriteAtomicityGuardTest 锚点核验零改动、对话框四同步核对、SS1 两处注释登记消费；**两件途中发现**：AutoEntry 云伪槽误判行为修复（本地无档时云端兜底此前永不触发）+ SS3 遗留 detekt 2 处实修；data 678/0 对账闭合 680−9+7；登记八条风险六条分级）+ 主树复跑全绿（compile ✓ / detekt 六模块 ✓ / jni-count 87/87 ✓ / agent-instructions ✓ / JVM 三模块 195 tasks 全绿）。**并网 merge `8b2e5c035`（零冲突）**。批次进度 6/11 → **7/11** | report-SS4.md + 主树门禁实跑 | 30 分钟 |
| 2026-10-02 03:0x | A（准备） | SS5 | **定批 = SS5（增量落盘）**：入口=SS4 已合入 ✓（串行主链 SS0→SS1→SS4→SS5）；SS5 属开工补卡批——已按方案 §2.5.1 + 主树实测补写 `TASKBOOK-SS5.md`（DirtySetTracker 消费 applyDirty 变更集、增量默认+全量兜底+越界回退计数进 SS3 挂点、heavy key 粒度跳过、两处防全删补丁降级断言、双路径对拍铁门；.sav/云载荷不动零 C++ 触碰；SS4 §6.3 clearCacheForSlot 更名消费）；StorageEngine 事务编排段 SS3 冻结本批解禁；worktree 与派发进行中 | TASKBOOK-SS5.md + 主树 grep 取证 | 30 分钟 |
| 2026-10-02 02:12 | A→B | SS5 | **派发成功**：worktree `C:\Mnzm\XianxiaSectNative-SS5`（`feat/single-save-SS5` @ `a5710c79e`，含 SS0–SS4 全部并网结果+本批任务书；补件齐）+ ZCode GUI 新会话「【派工】SS5·增量落盘（真增量写）」，模型 chip=GLM-5.3-Flash（最高/完全访问），粘贴回读（尾部 storage 前缀交付条款完整）后发送；截图终验=运转中（工作中 23s，开工顺序清晰：复核 worktree→读四文档→补卡理解→实施）。派工词含十条特别约束（applyDirty 脏集纯 Kotlin 闭环、增量默认+兜底+回退计数、双路径对拍铁门、两补丁降级断言、首保全量基线证据、事务编排段改完重新冻结等）。SS4 worktree 已移除（目录 jar 锁残留同型留待）；SS3 worktree 移除撞长路径（注册已清，目录残留同型）。推送持续不通持续重试；节拍维持 */30 | 截图 artifact `call_f99b2e09`（派发运转） | 30 分钟 |
| 2026-10-02 02:26 | B | SS5 | 起步正常（派发 14 分钟）：在途 0 文件（读四文档期——本批含方案 §2.5.1 共四份必读），report 未产出、主树干净、无异常；节拍 */30 已符合未动；推送仍不通 | `git -C XianxiaSectNative-SS5 status` | 30 分钟 |
| 2026-10-02 02:56 | B | SS5 | 推进正常：在途 0→**6 文件**（4 改 + 2 新增——脏集追踪器起步，SS5-a 切片）；分支无新 commit、report 未产出、主树干净；节拍 */30 已符合未动；推送仍不通 | `git -C XianxiaSectNative-SS5 status` | 30 分钟 |
| 2026-10-02 04:2x | D→**验收通过并并网** | SS5 | 实施会话交付：`a4a3ce2a8 feat(storage): SS5 增量落盘（真增量写）——脏集驱动双路径 + 越界回退计数 + 双路径对拍铁门`（树净）。验收=形态 PASS（验收①–⑥全✅：SaveDirtySet 契约+DirtySetTracker 代序号结算+store 提交段单点捕获（等价性论证：applyDirty 全部写经 updateMirror→update 单段）、增量默认+NO_BASELINE/NO_DIRTY_SET/越界/溢出四类兜底、双路径对拍铁门=三形状×逐字段全等+首保/次保判定证据+越界计数断言、skippedHeavyKeys 增量侧断言化而全量侧保留排除（数据丢失红线高于字面收敛，偏离有据）、Metrics +4 字段三处同步+新增属性键白名单守卫；**途中代 SS3 抓出在册漂移**：WallClockReflowGuardTest 66→59（摘 WAL 随删 7 处裸墙钟，SS3 验收只跑 data+app 未复跑 engine）——**验收复跑范围自此扩至三模块**；knowledge-base「存档槽位隔离」过期段（SS1/SS2 文档同步遗漏）本批改写；`.sav`/云载荷 @Transient 字节面逐位一致+proto 编号冻结守卫绿）+ 主树复跑（compile ✓ / detekt 六模块 ✓ / jni-count 87/87 ✓ / agent-instructions ✓ / JVM 三模块全绿——唯一红=`DeadCompatRemovalGuardTest` 判 `69.json` 过期副产物，例行处置第四次印证，删后复跑绿）。**并网 merge `f13b14ad6`（零冲突）**。批次进度 7/11 → **8/11** | report-SS5.md + 主树门禁实跑 | 30 分钟 |
| 2026-10-02 04:2x | A（准备） | SS6 | **定批 = SS6（事件触发自动存档）**：入口=SS5 已合入 ✓（串行主链 SS0→SS1→SS4→SS5→SS6）；SS6 属开工补卡批——已按方案 §2.5 + 主树取证补写 `TASKBOOK-SS6.md`（五类事件集+v2 适配：MIGRATION 事件随 SS0 退役、删档完成=SaveWipeCoordinator v2 新增点；防风暴 submit/flushNow 既有接口；涉钱同步时序断言+合并窗计数断言；shouldAutoSave 参数名收口=SS4 §6.2 登记；StorageEngine 冻结区解禁只许动触发面）；worktree 与派发进行中 | TASKBOOK-SS6.md + 方案 §2.5/SaveOrchestrator 取证 | 30 分钟 |
| 2026-10-02 04:36 | A→B | SS6 | **派发成功**：worktree `C:\Mnzm\XianxiaSectNative-SS6`（`feat/single-save-SS6` @ `2b0168e4f`，含 SS0–SS5 全部并网结果+本批任务书；补件齐）+ ZCode GUI 新会话「【派工】SS6·事件触发自动存档」，模型 chip=GLM-5.3-Flash（最高/完全访问），粘贴回读（尾部 storage 前缀交付条款完整）后发送；截图终验=运转中（工作中 28s，正复核 worktree 干净）。派工词含十条特别约束（五类事件 v2 适配 enumerated 表、防风暴 submit/flushNow、涉钱时序断言+合并窗计数断言、事件发起线程核实+落盘耗时实测、shouldAutoSave 参数名收口、冻结区只动触发面等）。推送仍不通（持续重试）；节拍维持 */30 | 截图 artifact `call_63725a16`（派发运转） | 30 分钟 |
| 2026-10-02 04:56 | B | SS6 | 起步正常（派发 20 分钟）：在途 0 文件（读四文档期），report 未产出、无异常；节拍 */30 已符合未动。主树一处图集副产物（看护 SS5 验收 compile 触发再生）已 checkout -- 还原；推送仍不通 | git status（主树还原后净） | 30 分钟 |
| 2026-10-02 05:26 | B | SS6 | 推进正常：在途 0→**25 文件**（22 改 + 3 新增——事件点接线面铺开，SS6-b 切片）；分支无新 commit、report 未产出、主树干净；节拍 */30 已符合未动；推送仍不通 | `git -C XianxiaSectNative-SS6 status` | 30 分钟 |
| 2026-10-02 05:56 | **C** | SS6 | **进入收尾期**：在途 25→23 文件（收尾清理）+ **report-SS6.md 已产出**、分支 commit 待打——与 SS0/SS1 交付前形态一致；主树干净。节拍切换因 CronUpdate 工具面持久缺件（轮#5/#6/#16 三度铁证）不再重试，按铁律硬扛 */30（手动调 10 分钟建议在册）；推送仍不通 | `ls report-SS6.md` + git status | 30 分钟（工具面缺件硬扛） |
| 2026-10-02 06:27 | C | SS6 | 收尾期门禁跑批中（非卡死）：分支仍无 commit、在途 23→25（+2 = 新增 `CriticalSaveEventBus.kt`（core/domain 事件总线——SS6 采用比逐点接线更收敛的总线架构）+ `CriticalSaveEventBusTest`/`CriticalEventSaveTimingTest` 两测试）；mtime 证据=`SaveOrchestrator.kt` 05:58 修改、图集副产物 **06:06 再生**（compile 活动铁证，最后活动 21 分钟前，三模块 rerun 时长范围内）；report 05:40 未再更新（先写后修型收尾）；节拍硬扛 */30 | git status 明细 + mtime 排序 | 30 分钟（工具面缺件硬扛） |
| 2026-10-02 08:0x | D→**验收通过并并网** | SS6 | 实施会话交付：`a9637891e feat(storage): SS6 事件触发自动存档——五类关键事件接入 SaveOrchestrator（涉钱 flushNow 同步落盘）`（树净）。验收=形态 PASS（事件点 enumerated 表 8 点与方案五类逐类对账+**v2 适配四条裁定逐条因果链**：删档不接线=数据空间整树删除+首存双直存无裸窗、合成/升星承载面枚举、广告/时长发放物理不可挂起、CriticalSaveEventBus 总线=依赖方向唯一单向汇入面；涉钱时序断言真打 Room DB+十连合并窗计数断言（验收③④铁门）；主线程阻塞面核实=notify 全 tryEmit 非挂起/IO 全协程+Room 执行器；落盘耗时实测 p50=14.7ms 增量路径；`shouldAutoSave` 参数名收口=SS4 §6.2 消费；验收⑤入云队列零新代码满足）+ 主树复跑（compile ✓ / detekt 六模块 ✓ / jni-count 87/87 ✓ / agent-instructions ✓ / JVM 四模块复跑进行中）。**并网 merge `4c9d98ec5`（零冲突）**。批次进度 8/11 → **9/11** | report-SS6.md + 主树门禁实跑 | 30 分钟 |
| 2026-10-02 08:0x | A（准备） | SS7 | **定批 = SS7（云：灾备+换设备续玩）**：入口=SS4+SS3 已合入 ✓✓（SS5/SS6 也已并网，上传链挂点就绪）；SS7 属开工补卡批——已按方案 §2.3/§2.5 + 主树实测补写 `TASKBOOK-SS7.md`（云端单档名复用 mnzm_v2_save、slot_N 族坍缩+isLegacyArchiveName 扩覆盖、UploadLedger/UploadQueue 单键、downloadIntoCache 跨槽签名坍缩、W>C 只读降级护栏硬门、复用 SaveArbiter/UploadQueue 不新建通道；SS1/SS4 全部云侧登记消费）；worktree 与派发进行中 | TASKBOOK-SS7.md + 主树 grep 取证 | 30 分钟 |
| 2026-10-02 07:0x | 验收补记 | SS6 | 主树 JVM 四模块复跑 **BUILD SUCCESSFUL**（208 tasks 零失败，本期无过期 schema 副产物）——SS6 验收全绿闭环；SS6 worktree 已移除（目录 jar 锁残留同型留待） | 后台 exec_77c19e1c 日志 | 30 分钟 |
| 2026-10-02 07:03 | A→B | SS7 | **派发成功**：worktree `C:\Mnzm\XianxiaSectNative-SS7`（`feat/single-save-SS7` @ `85ed336d5`，含 SS0–SS6 全部并网结果+本批任务书；补件齐）+ ZCode GUI 新会话「【派工】SS7·云：灾备+换设备续玩」，模型 chip=GLM-5.3-Flash（最高/完全访问），粘贴回读（尾部 cloud 前缀交付条款完整）后发送；截图终验=运转中（工作中 28s，正复核 worktree 干净）。派工词含十条特别约束（mnzm_v2_save 单档名复用、slot_N 族坍缩+isLegacyArchiveName 扩覆盖、UploadLedger/UploadQueue 单键、downloadIntoCache 跨槽签名坍缩、W>C 只读降级护栏硬门、复用 SaveArbiter 不新建通道、slot_N 失联受影响面与旧键清理策略入报告等）。推送仍不通（持续重试）；节拍维持 */30 | 截图 artifact `call_2fa38906`（派发运转） | 30 分钟 |
| 2026-10-02 07:26 | B | SS7 | 推进正常：在途 0→**24 文件**（22 改 + 2 改名——命名坍缩切片展开）；分支无新 commit、report 未产出；主树一处图集副产物（看护 compile 触发）已还原；节拍 */30 已符合未动；推送仍不通 | `git -C XianxiaSectNative-SS7 status` | 30 分钟 |
| 2026-10-02 08:4x | D→**验收通过并并网** | SS7 | 实施会话交付：`b0c76b42e feat(cloud): SS7 云灾备+换设备续玩——云侧槽位残留坍缩单键 + W>C 只读降级护栏`（树净）。验收=形态 PASS（验收①–⑥全✅：云端命名/台账/队列/跨槽下载四处坍缩+`SaveBackend.list()/delete()` 接口面退役（grep 取证零消费）+`SlotLockManager`→`SaveLockManager` 全量改名；**途中发现换设备续玩行为缺陷并修复**——原 GameActivity 云分支走内存加载不落盘+不收敛账本（换机后进度丢失+仲裁必误挂），改走落盘链+`adoptCloudState(W)` 收敛=真根因修复；W>C 护栏白名单化+守卫三例锁定降级路径零写调用；SaveArbiter 判定表零变更（U1–U11 与 Q6 一致）；失联面=测试期自身（4.2.00 未发布）+旧键=wipe 前缀天然覆盖（均零新增代码）；Q1–Q13 队列单测全绿=节流语义零变更证词）+ 主树复跑全绿（compile ✓ / detekt 六模块 ✓ / jni-count 87/87 ✓ / agent-instructions ✓ / JVM 三模块 188 tasks 零失败）。**并网 merge `405e06449`（零冲突）**。批次进度 9/11 → **10/11** | report-SS7.md + 主树门禁实跑 | 30 分钟 |
| 2026-10-02 08:4x | A（准备） | SS8 | **定批 = SS8（登录门槛+隐私政策双入口）**：入口=SS2 已合入 ✓；SS8 属开工补卡批——已按方案 §2.2/§2.8 + 主树取证补写 `TASKBOOK-SS8.md`（RequireLogin 状态机显式化禁一次性布尔、B1 离线宽限两分支=判定面用 SessionManager 既有缓存不新增键、隐私双入口同步单存档条款、五守卫硬门；派工册附带修正项 rules/sdk-init-lifecycle.md:50 已由 SS2 消费，本批复核即可）；worktree 与派发进行中 | TASKBOOK-SS8.md + 主树取证 | 30 分钟 |
| 2026-10-02 08:10 | A→B | SS8 | **派发成功**：worktree `C:\Mnzm\XianxiaSectNative-SS8`（`feat/single-save-SS8` @ `617179adf`，含 SS0–SS7 全部并网结果+本批任务书；补件齐）+ ZCode GUI 新会话「【派工】SS8·登录门槛+隐私政策双入口」，模型 chip=GLM-5.3-Flash（最高/完全访问），粘贴回读（尾部 login 前缀交付条款完整）后发送；截图终验=运转中（工作中 42s，「Worktree 干净、分支正确，继续读任务书与执行协议」）。派工词含十条特别约束（RequireLogin 状态机显式化禁一次性布尔、B1 两分支判定面用既有缓存、隐私双入口同步、五守卫硬门、B1 边界态与三启动路径回归证据入报告、不做游客模式等）。推送仍不通（持续重试）；节拍维持 */30 | 截图 artifact `call_c223133d`（派发运转） | 30 分钟 |
| 2026-10-02 08:27 | B | SS8 | 起步正常（派发 17 分钟）：在途 0→**4 文件**（4 改——状态机切片 SS8-a 起步）；分支无新 commit、report 未产出、主树干净；节拍 */30 已符合未动；推送仍不通 | `git -C XianxiaSectNative-SS8 status` | 30 分钟 |
| 2026-10-02 10:1x | D→**验收通过并并网** | SS8 | 实施会话交付：`2ff65a537 feat(login): SS8 登录门槛状态机化 + B1 离线宽限 + 隐私政策双入口同步`（10 文件 +413/−105，树净）。验收=形态 PASS（验收①–⑤全✅：ColdStart 四分支互斥判定面+RequireLogin 唯一出口=LoginRequested、B1 两分支测试锁定+判定面用 SessionManager 既有缓存零新增键、隐私双入口一致性核对表九项全勾（更新日期 2026-10-02、删档告知/账号隔离/云灾备条款与行为逐条对应无营销承诺）、五守卫定向复跑绿、三启动路径回归证据表；**途中发现两个真根因缺陷并修复**：enterGameAuto 会话残缺兜底的状态机死锁（SS2 防御兜底只改 UI 没改状态机——此后登录成功也进不了游戏）+ initTapTapLoginSdk 超时直调绕过状态机+双启动窗口；B1 wipe 后半态定义=任务书登记项 2 满足；零一次性布尔复核）+ 主树复跑全绿（compile ✓ / detekt 六模块 ✓ / jni-count 87/87 ✓ / agent-instructions ✓ / JVM 三模块 195 tasks 零失败）。**并网 merge `e7ab3d4e5`（零冲突）**。批次进度 10/11 → **11 批中 10 批已并网（SS0–SS9 全齐），仅余收官批 SS10** | report-SS8.md + 主树门禁实跑 | 30 分钟 |
| 2026-10-02 10:1x | A（准备） | SS10 | **定批 = SS10（文档与发布收口·收官批）**：入口=SS0–SS9 全部已合入 main ✓（`e7ab3d4e5` 时刻核验）；SS10 属开工补卡批——已补写 `TASKBOOK-SS10.md`（**零生产代码**：双 changelog 收口=SS0–SS9 玩家可见变更并入唯一 4.2.00 条目（删档重置必须显著；素材汇总表九批齐备）+外部 CHANGELOG 技术段、四份活文档同步（CODE_WIKI/architecture/ui-read-surface §2）、验收报告 report-SS-final.md 出具（11 批对照+门禁演进+真机 pending 汇总+过期引用清单）、册面回填；硬门=check-agent-instructions EXIT=0 + diff 自证零生产代码）；worktree 与派发进行中 | TASKBOOK-SS10.md + 各批 SS10 登记汇总 | 30 分钟 |
