# TASKBOOK-SS5 · 增量落盘（真增量写）

> **本文件是 SS5 的派工真源**（开工补卡，对齐派工册 §5 在册理由：SS5 的脏集形状取决于 SS4 后的写路径）。上位方案 [`single-save-and-persistence-consolidation-plan-2026-10-01.md`](../single-save-and-persistence-consolidation-plan-2026-10-01.md) **§2.5.1**。
> 协议：[`DISPATCH-ledger.md`](DISPATCH-ledger.md) §4 + [`../gacha-batches/EXECUTION-PROTOCOL.md`](../gacha-batches/EXECUTION-PROTOCOL.md)。
> 入口判据：**SS4 已合入**（`8b2e5c035`）；`StorageEngineWriteOps.kt` / `StorageEngine.kt` 事务编排段（SS3 冻结）**本批解禁**、无他批在途。
> 行号证据：2026-10-02 03:0x 主树实测。

---

## 1. 目标与验收判据

| 项 | 内容 |
|---|---|
| 业务目标 | 保存从「全删全写」改为**真增量写**：只落变化行，未变 heavy key 不删不写——这是本方案的性能核心收益批。 |
| 验收① | **`DirtySetTracker` 消费既有 `applyDirty` 变更集**（C++ 列级脏标记已经到达 Kotlin，`StateSyncService.applyDirty` / 增量镜像；落盘时被丢弃 = 方案缺口形状 b）+ 本地 Kotlin 变更源；**纯 Kotlin 闭环，零 C++ 触碰** |
| 验收② | **默认走增量路径**：变化行 `upsert`（只传变化 id）+ 删除集 `deleteById`（`persistedIds - pulledIds`）+ **未变 heavy key 直接跳过**（不删不写）+ 变动 heavy key 整 key 重编码 |
| 验收③ | **全量路径保留为兜底**：脏集缺失/无效/含 `requiresFullWrite` 标记 ⇒ 走既有全删全写 |
| 验收④ | **增量 ↔ 全量双路径读回逐字段全等**（对拍测试）：覆盖新增一批 / 删除一批 / 修改一批三种脏集形状 |
| 验收⑤ | **增量落盘前校验**：脏集 id ⊆ 当前快照 id 集合，越界或不可判定 ⇒ **回退全量路径并计数**（不得静默跳过）——计数走 SS3 的 `StorageMetrics.snapshot()` 框架（脏集回退次数 + 增量/全量路径分布，SS3 报告 §6.1 登记挂点） |
| 验收⑥ | **两处「防全删」补丁降级为断言**（保护对象消失）：`skippedHeavyKeysBySlot` 族（SS1 已改 `skippedHeavyKeys` Set，`StorageEngineHeavyDataOps.kt:61/231`）与 `stacksSerialized` 条件写（`StorageEngineWriteOps.kt:126-144`）——未被标脏的表不再被删，补丁语义由断言承载 |
| **不做** | `.sav`/`.bak` 文件镜像与云载荷仍为**单 blob 全量**（增量只在本地 Room 层；云增量上传=技术债 §八）；不碰 C++（不占 G 批锁）；不改读档行为语义；脏集**不持久化**（进程内存活，重启首保全量——正确性由全量兜底保证，YAGNI） |

---

## 2. 实测现状（2026-10-02 主树）

- `StorageEngineWriteOps.kt:26` `writeAllDataToDatabase(data)`：全删全写；`:68` 已有 `skippedHeavyKeys` 消费点（OOM 跳写记录）；`:126-144` `stacksSerialized` 防全删条件；`:195` 空列表守卫。
- `StorageEngineHeavyDataOps.kt:61` `skippedHeavyKeys: MutableSet`；`:199/:231` 跳写记录语义。
- `writeHeavyDataIncremental`（`:80-108`）的"增量"= **分块编码降内存峰值**，非"只写变化"——每次重编码 upsert 全部 7 个 heavy key。
- `clearCacheForSlot`（函数名含 Slot，SS4 报告 §6.3 登记）随本批写路径收口更名。
- **SS3 已交付挂点**：`StorageMetrics.snapshot()` 10 字段 + `#storage_metrics_report` 30 分钟上报——本批新计数器直接追加进快照与事件属性（三处同步：`AnalyticsEvents.kt` / knowledge-base 字典 / `AnalyticsEventsDictionaryTest`）。

---

## 3. 决策

| # | 决策 | 依据 / 代价 |
|---|---|---|
| **D-1** | **增量默认 + 全量兜底**，回退必须计数可见 | 静默回退 = 性能收益不可测；计数进 `StorageMetrics` |
| **D-2** | **脏集来源 = applyDirty 变更集 + 本地 Kotlin 写**，不新增 C++ 通道 | 脏信息已到达 Kotlin 被丢弃；新增通道违反「不新增 Kotlin→C++ 反向写」（`MirrorReadOnlyGuardTest` 零命中红线） |
| **D-3** | **heavy key 粒度跳过**（不删不写），变动则整 key 重编码 | heavy 数据无行级 id 稳定键；行级增量只做集合表 |
| **D-4** | **删除集 = persistedIds − pulledIds**，用既有 `SlotEntityIndexDao`/等价 id 枚举 | 方案 §2.5.1 保留清单点 |
| **D-5** | **两处防全删补丁降级为断言**（验收⑥） | 保护对象消失；补丁留Recognition = 死防御 |
| **D-6** | **脏集不持久化** | 重启首保全量正确性无损；持久化脏集是另一个分布式一致性问题，YAGNI |

---

## 4. 文件面与切片（≤10 文件/片）

| 片 | 允许改 | 禁止改 | 自检项 |
|---|---|---|---|
| **SS5-a** 脏集追踪 | 新 `DirtySetTracker`（core/data）+ `applyDirty` 链接线 + 单测 | C++、镜像协议 | 三种脏集形状可表达；线程安全 |
| **SS5-b** 增量写路径 | `StorageEngineWriteOps.kt`（增量分支）、`StorageEngineHeavyDataOps.kt`（heavy 跳过/重编码）、`clearCacheForSlot` 更名 | 读档链、`SaveValidator` | 双路径开关可注入；越界回退+计数 |
| **SS5-c** Metrics 追加 | `StorageMetrics.kt` 快照扩字段、上报属性、三处同步 | 既有 10 字段语义 | `#storage_metrics_report` 属性键白名单守卫绿 |
| **SS5-d** 补丁降级 | `skippedHeavyKeys`/`stacksSerialized` 断言化 + 相关测试 | 守卫删除 | 断言红 = 真回归 |
| **SS5-e** 对拍测试 | 双路径对拍（三形状 × 逐字段全等）+ 越界回退用例 | — | 对拍绿；回退计数断言 |
| **主线程** | `report-SS5.md`、门禁复跑 | — | §5 清单 |

**共享面**：`StorageEngine.kt` 事务编排段本批解禁（SS3 冻结解除）；`StorageEngineWriteOps.kt` 同理——改完后**重新冻结**给 SS6（若 SS6 触及保存触发链）。

---

## 5. 门禁清单

```powershell
# 工作目录 = C:\Mnzm\XianxiaSectNative\android
.\gradlew.bat compileReleaseKotlin --console=plain
.\gradlew.bat :core:data:testReleaseUnitTest :app:testReleaseUnitTest :core:engine:testReleaseUnitTest --max-workers=1 "-Dgamecore.jni.path=C:\Mnzm\XianxiaSectNative\android\core\engine\build\desktop-jni\libgamecorejni.so" --rerun-tasks --console=plain
.\gradlew.bat :core:data:detekt :core:engine:detekt :app:detekt --console=plain
node scripts/check-agent-instructions.mjs   # 改 knowledge-base/AnalyticsEvents 文档面后必跑
```

**提交前**：`git status` 只留本批改动；构建副产物 `git checkout --` 还原；一次性脚本清零；**文档面编辑一律在 worktree 内完成并随批 commit（SS3 教训）**。

---

## 6. 登记项

1. **跨批登记（SS6）**：事件触发自动存档（`SaveOrchestrator.submit` 合并窗）依赖本批增量路径的耗时特征——关键事件返回前落盘的成本从"全量"降为"增量"，SS6 的时序断言以此为基础。
2. **跨批登记（SS7）**：云载荷单 blob 全量不变；云增量上传=技术债（方案 §八）。
3. **跨批登记（SS10）**：性能收益（保存耗时）玩家不可见，changelog 不强制；若写则并入 4.2.00 唯一条目。
4. **风险（必须写进报告）**：增量路径的**首次启用保存**（脏集空）必须走全量基线建立，此后增量——报告给 出首保/次保的路径判定证据。
5. **风险**：`applyDirty` 变更集与落盘之间的**时间窗**（多次脏变更合并为一次保存）——合并语义须保证"最终态正确"而非"逐次回放"，对拍测试覆盖。

---

## 7. 一句话给执行者

**把"每次保存全删全写"改成"脏集驱动的真增量写"：applyDirty 变更集 + 本地写构成脏集，变化行 upsert、删除集 delete、未变 heavy key 跳过；越界回退全量并计数进 StorageMetrics；双路径读回逐字段全等是对拍铁门——.sav/.bak 与云载荷不动，零 C++ 触碰。**
