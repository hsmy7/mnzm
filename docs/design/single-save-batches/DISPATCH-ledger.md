# 单存档改造 + 持久化面收口 · 批次派工册（DISPATCH-LEDGER）

> 上位方案（唯一需求真源）：[`../single-save-and-persistence-consolidation-plan-2026-10-01.md`](../single-save-and-persistence-consolidation-plan-2026-10-01.md)
> 摸底事实源：[`../../save-system-survey-2026-10-01.md`](../../save-system-survey-2026-10-01.md)
> 执行协议：**继承** [`../gacha-batches/EXECUTION-PROTOCOL.md`](../gacha-batches/EXECUTION-PROTOCOL.md)（G 批在册规程）——铁律、门禁命令、交付形态、汇报格式**一律照用**，本册只列增量约束。
> 本册是**派工真源**：批次总览 + 每批入口/出口判据 + 跨批串行约束。
> 已到**派出级**的批次另有独立 `TASKBOOK-SSxx.md`；未到派出级的只给入口/出口（理由见 §4）。

---

## 0. 批次总览

| 批 | 名称 | 类型 | 依赖 | 可并行 | 出口判据（一句话） |
|---|---|---|---|---|---|
| **SS0** | 口径冻结与既有分叉收敛 | 代码 | — | 立即可开工 | 三处 `DEFAULT_MAX_SLOTS` 归一为单一真源；`7 vs 6` 遍历口径统一；唯一槽常量冻结；相关单测绿 |
| **SS1** | 账号数据空间分库 + 登出五件套 | 代码 | SS0 | 与 SS8 | 换账号不串档（隔离守卫）；三处登出入口一致清数据空间；库按 `accountKey` 落盘 |
| **SS2** | 持久化面收口四组件 | 代码 | SS0 | 与 SS8；**与 SS1 串行** | `change_log` 有生产读者；`FunctionalWAL` 摘除后启动/保存/读档全绿；归档有读面；`StorageMetrics` 有 getter + 上报 |
| **SS3** | 单档语义 + UI 收敛 + 删元数据表 | 代码 | SS0, SS1 | — | 无槽位选择/管理 UI；`getSaveSlots` 单档；`save_slot_metadata` 整表删除且两条守卫同步 |
| **SS4** | 存量多档迁移 + 旧台账键合并清理 | 代码 | SS2, SS3 | 与 SS5 / SS7 | 老玩家最新档完整可用；其余档归档且可自助恢复；`canPromoteToCloudOnly` 不再真空成立 |
| **SS5** | 增量落盘（真增量写） | 代码 | SS3 | 与 SS4 / SS7 | 增量与全量**双路径读回逐字段全等**；未变 heavy key 跳过；脏集越界回退全量 |
| **SS6** | 事件触发自动存档 | 代码 | SS5 | — | 关键事件返回前已落盘；十连 10 次事件合并为 **1 次**；落盘后入云队列 |
| **SS7** | 云：灾备 + 换设备续玩 | 代码 | SS3, SS4 | 与 SS4 / SS5 | 新设备 `LoadCloud` 链路通；`W > C` 只读降级、绝不静默覆盖 |
| **SS8** | 登录门槛 + 隐私政策双入口 | 代码/文档 | SS1 | 与 SS1 / SS2 | 未登录不可进游戏；离线宽限按 B1；两处隐私政策同步更新 |
| **SS9** | 玉符账本（C++ 真源） | 代码 | SS3；**与 G 批对表** | **独占** | 账本 append-only；派生余额 == 账本求和；期初开账后存量余额不变；两侧 GTest + `Diff*` 齐备 |
| **SS10** | 文档与发布收口 | 文档 | SS0–SS9 | — | 双 changelog、`CODE_WIKI.md`、`docs/architecture.md`、验收报告齐；门禁全绿 |

**总批次数 = 11（SS0–SS10）。**

---

## 1. 依赖图与并行组

```
SS0 ─┬─ SS1 ─┬─ SS3 ─┬─ SS5 ── SS6 ──┐
     │       │       ├─ SS4 ── SS7 ──┤
     │       │       └─ SS9 ──────────┤
     │       └─ SS8 ──────────────────┤
     └─ SS2 ── SS4 ───────────────────┤
                                       └── SS10
```

- **串行主链**：`SS0 → SS1 → SS3 → SS5 → SS6 → SS10`
- **旁支 A**：`SS2 → SS4 → SS7`（`SS2` 与 `SS1` **必须串行**，见 §3.3）
- **旁支 B**：`SS8`（`SS1` 后可与 `SS2` 并行）
- **独占**：`SS9`（玉符账本，见 §3.1/§3.2）

---

## 2. 每批入口 / 出口判据

### SS0 · 口径冻结与既有分叉收敛

- **入口判据**：工作树干净；`git status` 只含本批改动。
- **出口判据**：
  1. `DEFAULT_MAX_SLOTS` 归一为**单一真源**（现状三处独立字面量：`StorageConstants.kt:20` / `StorageConfig.kt:111` / `SlotLockManager.kt:31`），其余两处改为引用；
  2. 槽位遍历口径统一（现 `DataPruningScheduler.kt:24` 为 `0..6`、`DataArchiveScheduler.kt:31` 为 `1..6`，后者 `:28-30` 注释自陈不一致），并**明确 slot 0 云会话槽的 battle_logs 归档归属**；
  3. `StorageConfig.maxSlots`（`:36-37` 生产零消费者）二选一处置：接入或删除——**不得留着分叉**；
  4. 冻结唯一槽常量（本地档槽 + 云会话伪槽）并加守卫断言"生产代码中 `slot_id` 取值集合 ⊆ 冻结集"；
  5. 相关单测绿（`StorageConstantsTest` 现有 `DEFAULT_MAX_SLOTS is 6` 断言须随口径更新并注明理由）。
- **硬门**：`compileReleaseKotlin` 绿 + 触及模块 detekt 绿。
- **文件面概览**：`StorageConstants.kt`、`StorageConfig.kt`、`SlotLockManager.kt`、`StorageEngine.kt`、`DataPruningScheduler.kt`、`DataArchiveScheduler.kt` + 对应测试。
- **不做**：不改槽语义（仍 6 槽）、不删表、不碰 C++。

### SS1 · 账号数据空间分库 + 登出五件套

- **入口判据**：SS0 已合入；工作树干净。
- **出口判据**：见 `TASKBOOK-SS1.md`。
- **硬门**：隔离守卫测试（以 Room 表清单为锚点遍历）绿；登出三入口一致性守卫绿。
- **不做**：不改槽位数量语义（SS3 做）；不接入云端。

### SS2 · 持久化面收口四组件

- **入口判据**：SS0 已合入；`StorageModule.kt` 无他批在途改动。
- **出口判据**：见 `TASKBOOK-SS2.md`。
- **硬门**：`FunctionalWAL` 摘除后启动 / 保存 / 读档三条链全绿；`ArchiveWriteOnlyGuardTest` 与 `WalRetirementGuardTest` 按新职责同步改（**改语义而非放宽**）。
- **不做**：不改槽语义；不接云端。

### SS3 · 单档语义 + UI 收敛 + 删元数据表

- **入口判据**：SS0、SS1 已合入。
- **出口判据**：见 `TASKBOOK-SS3.md`。
- **硬门**：`ClearAllSlotTablesCoverageTest` 与 `CacheWriteAtomicityGuardTest` 同批更新；`save_slot_metadata` 删除后 Room schema 与实体注册一致。
- **不做**：不做增量落盘（SS5）；不做存量迁移（SS4）。

### SS4 · 存量多档迁移 + 旧台账键合并清理

- **入口判据**：SS2（归档读面）、SS3（单档语义）已合入。
- **出口判据**：老玩家升级后最新档完整可用；其余可读档转为归档且设置页可自助恢复；旧库保留不删；**旧 MMKV 台账键按"读 → 合并 → 清理"处置**（B3）；升档门槛判据改为"旧库实际存在的档"，**不再真空成立**。
- **硬门**：迁移中断可重入（`pending/done` 幂等）；归档内容与源档逐字段相等。
- **不做**：不上云（SS7）。
- **风险（必须写进报告）**：`SaveMigrationPlanner.canPromoteToCloudOnly`（`:153-157`）的"本地一槽无档 ⇒ 真空成立"是既有语义，本批必须切断它对本场景的影响。

### SS5 · 增量落盘（真增量写）

- **入口判据**：SS3 已合入；`StorageEngineWriteOps.kt` 无他批在途改动。
- **出口判据**：`DirtySetTracker` 消费既有 `applyDirty` 变更集；默认走增量路径（变化行 upsert + 删除集 delete + 未变 heavy key 跳过），全量路径保留为兜底；**增量 ↔ 全量双路径读回逐字段全等**（含新增/删除/修改三类脏集形状）；脏集越界或不可判定时**回退全量并计数**。
- **硬门**：双路径对拍测试绿；`skippedHeavyKeysBySlot` 与 `stacksSerialized` 两处"防全删"补丁降级为断言（保护对象已消失）。
- **不做**：不改 `.sav`/`.bak` 与云载荷（单 blob，仍全量）；**不碰 C++**。
- **说明**：本批是"全删全写 → 增量"的核心，落点见方案 §2.5.1。

### SS6 · 事件触发自动存档

- **入口判据**：SS5 已合入。
- **出口判据**：关键事件（玉符流水 append、碎片入账、高品阶物品入库、里程碑、迁移成功）**返回前完成落盘**；事件走 `SaveOrchestrator.submit` 合并窗（十连 10 次事件 → 1 次落盘）；落盘后入云队列。
- **硬门**：时序断言（事件返回前已落盘）；合并窗计数断言。
- **不做**：不改节拍常量（保持 10s）；不新增 Kotlin→C++ 反向状态写。

### SS7 · 云：灾备 + 换设备续玩

- **入口判据**：SS3、SS4 已合入。
- **出口判据**：新设备登录 → 本地无档 → `AutoEntryResolver.LoadCloud` 链路通；单档命名收敛为 `slot_1`（保留 `slot 0 = mnzm_cloud_save` 的**读取**能力用于存量收编）；**不支持同时多设备**：保存前比对云端 `currentCloudSaveId()`，`W > 本地 C` ⇒ 只读降级 + 提示，**绝不静默覆盖**。
- **硬门**：复用既有 `SaveArbiter`/`UploadLedger`/`UploadQueue`，**不新建云通道**；`TapTapSaveBackend.slotFromArchiveName` 的硬编码 `1..6`（`:383`）与槽空间收缩的错配必须显式处置（孤儿云档）。
- **不做**：不做云端历史版本；不做增量上传（TapTap 单 blob）；不引入服务端。

### SS8 · 登录门槛 + 隐私政策双入口

- **入口判据**：SS1 已合入。
- **出口判据**：未登录 → 强制登录页（入口收敛 `LoginFlowStateMachine`，**禁止**在 Activity 新增"只置位不复位"的一次性布尔标记）；离线宽限按 **B1**（已登录过可离线继续、从未登录必须联网首次登录）；**隐私政策双入口同步更新**（`PrivacyConsentScreen.kt` + `docs/index.html`）。
- **硬门**：`LoginFlowStateMachineTest` / `SafeRunAfterSdkInitTest` / `ComplianceManagerSelfHealTest` / `SdkInitGuardTest` / `TapDBManagerInitGuardTest` 全绿。
- **附带修正**：`rules/sdk-init-lifecycle.md:50` 仍列已删除的 `ModeSelectionScreen.onLogout`，同批修正。

### SS9 · 玉符账本（C++ 真源）🔴 与 G 批串行

- **入口判据**：SS3 已合入；**G 批 `models.h` / `jade_tx.h` 无在途改动**；开工前与 G 批负责人对表（§3.1）。
- **出口判据**：账本真源在 C++（`jade_tx.h` 六事务 1766–1769 / 1692 / 1693 改为 append 条目 + 派生缓存校验）；`jadeSymbols` 降级为派生缓存；Kotlin 侧仅派生只读 + 镜像投影（`GameDataFieldPatch.coveredFields` 登记新字段）；**老档写 `OPENING_BALANCE` 期初条目，余额不变**；UI 三读数收敛为一。
- **硬门**：`JadeSymbolConsumptionGuardTest` 正则**扩到账本字段名**（现只认 `jadeSymbols`，新字段写入会静默漏检）；两侧对拍齐备（GTest `jade_tx_test.cpp` / `jade_runtime_tx_test.cpp` 同步改写 + JUnit `Diff*Test`）；新 ActionId 走 `scripts/gen-action-ids.mjs` 双产物，**禁手改生成物**；`GameDataFieldPatchGuardTest` 绿。
- **不做**：不改白名单发奖语义（**B2：这是特权**）；不引入付费通道。

### SS10 · 文档与发布收口

- **入口判据**：SS0–SS9 全部合入。
- **出口判据**：双 changelog（`CHANGELOG.md` + `assets/changelog_entries.json`）齐；`CODE_WIKI.md`、`docs/architecture.md`、`docs/ui-read-surface.md` §2 登记同步；本册与各 `report-SSxx.md` 回填；验收报告出具；全门禁绿。
- **硬门**：`node scripts/check-agent-instructions.mjs` EXIT=0；detekt baseline **只缩不增**。

---

## 3. 跨批串行约束（不可违反）

### 3.1 与 G 批卡池方案的冲突面（仅一处）

- **`SS9` 是唯一会碰 C++ 的批次**，且会动 `models.h`（账本协议字段）与 `jade_tx.h`（六事务）——**这两处正是 G 批 R2 文件级锁表内的文件**。
- ⇒ `SS9` **必须与 G 批对表串行合入**，禁止并行编辑；其余批次一律不碰 C++ 文件、不改导出形状。

### 3.2 对拍基线唯一重录窗口（G 批 R3）

- 全局**只允许一次**权威重录窗口。
- `SS9` 新增 `GameData` 协议字段会改变导出形状 ⇒ 是本册**唯一可能占窗口**的批次。
- ⇒ `SS9` 开工前必须确认窗口归属：**并入 G 批 G10 窗口**，或与其严格先后且后录者吃前者的 diff 脚本；**禁止独占第二次重录**。`SS0`–`SS8`、`SS10` 不得触发任何重录。

### 3.3 共享文件面（同批独占，禁并行编辑）

| 文件 | 冲突批 | 处置 |
|---|---|---|
| `android/app/src/main/java/com/xianxia/sect/di/StorageModule.kt` | SS1（数据空间路径）与 SS2（WAL 解绑） | **串行**：SS1 先，SS2 后 |
| `android/core/data/.../engine/StorageEngineWriteOps.kt` | SS3（单档写路径）与 SS5（增量写） | **串行**：SS3 先，SS5 后 |
| `android/core/data/.../engine/StorageEngineSaveSupport.kt` | SS3（删表 → 改 `clearAllSlotTables`）与 SS4（迁移） | 串行 |
| `android/feature/game/.../ui/game/tabs/SettingsTab.kt` | SS3（存档弹窗收敛）与 SS4（恢复旧档入口） | 串行 |

### 3.4 开工纪律

- 每批开工前 `git status` 必须干净；发现他批在途改动就**停手报告**，不得在他批改动上叠加。
- 分片并行期**禁止子代理跑 gradle/cmake**；主线程终树复跑全部门禁。

### 3.5 本方案的增量铁律（在 G 批 14 条铁律之外追加）

| # | 铁律 |
|---|---|
| 15 | **不删 `slot_id` 物理列、不改 C++ 协议槽字段**（`currentSlot` / 物品 `slotId`）。改它们会打破 JVM 跨语言对拍（`DiffAuthoritativeTickTest` / `DiffInventoryTest` / `DiffSpiritFieldTest`）并强制重录基线。 |
| 16 | **伪同形名排除清单**：`SpiritMineViewModel.slotId`（矿工指派）、`DiscipleDetailScreen.slotId(part)`（装备部位）、`SlotAssignment.slotId`（String 指派 ID）、`CaptiveGearUtils.slotIdOf`、`ProductionSlotRepository` 的 String id、`execute_dispatch.cpp:312` `currentSlots`（仓库槽数）——**均非存档槽，禁止一并替换**。 |
| 17 | **`RepoInterfaces` 的 `slotId: Int = 0` 默认值必须消灭**（`0` = 云会话伪槽且 `isValidSlot(0)` 为真 ⇒ 漏传参数静默写错槽且不报错）。 |
| 18 | **单档语义下 `slot_id` 恒为常量**，其取值集合须有守卫断言（SS0 建立）。 |

---

## 4. 为什么只给前 4 批写细卡

对齐 `TASKBOOK-G09.md` §0 的在册理由：**后批的细粒度切片取决于前批的落地形态**。

- SS4 的迁移源清单取决于 SS3 单档后的落盘形态；
- SS5 的脏集形状取决于 SS3 的写路径改造结果；
- SS6 的事件集取决于 SS5 的增量路径边界；
- SS7 的云端命名与台账处置取决于 SS4 的键合并结果；
- SS9 的账本字段面取决于 SS3 的槽维度坍缩结果 + G 批 `models.h` 的当期形态。

提前给出细粒度切片会重演 G16/G08"上位内联任务书无文件面、照字面派工踩坑"的代价。故 SS4–SS10 只给入口/出口判据，各自开工时补自己的 `TASKBOOK-SSxx.md`。

---

## 5. 拍板记录（用户 2026-10-01）

### 5.1 方案级（方案 §1.2，7 项）

| # | 议题 | 结果 |
|---|---|---|
| Q1 | 云端角色（无服务器） | 灾备 + 换设备续玩（不支持同时多设备） |
| Q2 | 玉符模型 | 不可变流水账 + 余额派生 |
| Q3 | 存量多档 | 保留最新一档，其余转本地归档并给恢复入口 |
| Q4 | 登出/换账号 | 本地数据按账号隔离 |
| Q5 | 登录门槛 | 全部功能要求登录 |
| Q6 | 多设备 | 明确不支持同时在线 |
| Q7 | 交付 | 完整客户端方案 + 持久化面收口重构 |

### 5.2 派工级（方案 §1.3，7 项）

| # | 议题 | 结果 | 落点批 |
|---|---|---|---|
| B1 | 离线宽限 | 已登录过可离线继续；从未登录必须联网首次登录 | SS8 |
| B2 | 免广告白名单玉符发放 | **是特权，不是缺陷** —— 账本保持语义、只如实记录来源 | SS9 |
| B3 | 旧 MMKV 台账键 | 读 → 合并进单档台账 → 清理 | SS4 |
| B4 | `save_slot_metadata` | 删除整表 | SS3 |
| B5 | 既有分叉 | 同批收敛（三处字面量 + `7 vs 6` 口径） | SS0 |
| B6 | 影响范围清单粒度 | 维持三类组织（必改 / 误伤 / 净收益） | — |
| B7 | 文档落库 | 摸底报告与方案一起提交（已完成 `7c15015e8`） | — |

---

## 6. 交付形态与汇报（照 `EXECUTION-PROTOCOL.md` §3/§4，此处只强调本册差异）

- **每批必交**：代码（一次覆盖全部影响点）/ 测试（删除批给"旧用例处置表"）/ `docs/design/single-save-batches/report-SSxx.md` / 中文 commit。
- **commit 前缀**：`feat|refactor|chore(single-save|account|storage|jade|cloud|login): SSxx <一句话>`。
- **汇报格式**：

```
批次：SSxx
commit：<sha> <标题>
门禁：ctest N/N · engine JUnit N/N · core:data/app 面 · detekt 模块全绿 · compileReleaseKotlin 绿 · 生成器零漂移
改动规模：N 文件 +A/-D
未完成/登记：<逐条>
风险：<逐条，含「已核实」与「推测」区分>
```

- **跨批登记**：任何本批不做而后续批依赖的项，必须写进报告末节"未完成/登记"，并在本册对应批次的入口判据里被消费。
