# TASKBOOK-SS2 · 持久化面收口四组件（change_log / WAL / 归档 / Metrics）

> **本文件是 SS2 的派工真源**。上位方案：[`../single-save-and-persistence-consolidation-plan-2026-10-01.md`](../single-save-and-persistence-consolidation-plan-2026-10-01.md) §2.6。
> 协议：[`DISPATCH-ledger.md`](DISPATCH-ledger.md) §3 + [`../gacha-batches/EXECUTION-PROTOCOL.md`](../gacha-batches/EXECUTION-PROTOCOL.md)。
> 入口判据：**SS0 已合入**；**`StorageModule.kt` 无他批在途改动**（本批与 SS1 共享该文件，按 §3.3 SS1 先 / SS2 后）。

---

## 1. 目标与验收判据

| 项 | 内容 |
|---|---|
| 业务目标 | 把四个"半截"持久化组件**逐一赋责或摘除**：`change_log`（只写不读）→ 赋责；`FunctionalWAL`（有壳无芯）→ 摘除；归档表（只写不读）→ 接读面；`StorageMetrics`（只写不可读）→ 补 getter + 上报。 |
| 验收① | **`change_log` 写入真实变更摘要**（表名/主键/字段集），不再写 `old/new = null` 的空行；**读面有真实生产消费者**（存档诊断：设置页诊断入口 + `StorageMetrics` 上报），不是"为将来预留" |
| 验收② | **`FunctionalWAL` 摘除**：解除 DI 绑定与全部调用点；事务编排职责明确归 Room；`WalRetirementGuardTest` 改为"退役面无残留引用"断言（**改语义，不放宽**） |
| 验收③ | **归档表接上读面**：最小可用读面 = ① 列表（按槽/时间）② 按 id 还原载荷；由**存档诊断**消费者，并被 SS4 的"恢复旧档"复用 |
| 验收④ | **`StorageMetrics` 补 getter + 上报**（按 `rules/data-analytics.md` 三处同步：事件定义 / 上报点 / 守卫测试） |
| 验收⑤ | `ArchiveWriteOnlyGuardTest` 按**新职责**改写（原断言"归档读面为空"必须失效并被替换，不得删除守卫） |
| **不做** | 不改槽语义（SS3）；不改保存/读档的**行为语义**（只收口组件职责）；不接云端；不碰 C++；不做增量落盘（SS5） |

---

## 2. 实测现状（四组件各自的"半截"）

### 2.1 `change_log` —— 只写不读

- 实体/DAO/持久化：`ChangeLogEntity.kt` / `ChangeLogDao.kt` / `ChangeLogPersistence.kt`。
- 唯一生产写点：`StorageEngineSaveSupport.kt:69-81`，每次保存写 **1 行 UPDATE**，`old/new` 恒 `null`。
- 读方法（`getUnsyncedChanges` 一族）**生产零调用者**。
- 7 天剪除：`DataPruningScheduler.kt:210-214`。
- ⇒ 成本付了（每次保存一行 + 定期裁剪），收益为零。

### 2.2 `FunctionalWAL` —— 有壳无芯

- DI 绑定：`StorageModule.kt:51`（`WALProvider` → `FunctionalWAL`）；持有者 `StorageCoreFacade.kt:18`。
- 调用形态：`StorageEngine.kt:611-621` begin（仅 LEGACY / CLOUD_TRANSITION）、`:643-651` commit、`:634-640` post-save PASSIVE checkpoint；CLOUD_ONLY 下整体停开（`:612`）。
- **条目不承载数据字节**，`recover()` 仅记日志（`StorageEngine.kt:559-578` 注释"仅记录日志供监控"）。
- ⇒ 耐久性完全由 Room 事务承担；该组件制造了"它在保护存档"的错觉。
- 既有退役先例：`WalRetirementGuardTest`（自称"退役面静态守卫"）。

### 2.3 归档表 —— 只写不读

- DI：`StorageModule.kt:80-84`（`provideDataArchiver`）；持有者 `StorageEngine.kt:77`。
- 写入：`DataArchiveScheduler.kt`（600s 周期）把溢出战斗日志与已故弟子搬出主表；归档行 `dataBlob` 是**可还原的全量载荷**。
- 查询面零生产调用者，`ArchiveWriteOnlyGuardTest` 把"无读者"钉死为现状。
- ⇒ 成本付了（写 + 180 天存储），收益没拿。

### 2.4 `StorageMetrics` —— 只写不可读

- 持有者：`StorageInfraFacade.kt:16`。
- 8 个计数器**无任何 getter** ⇒ 存档失败率/耗时在线上完全不可见。

---

## 3. 决策

| # | 决策 | 依据 / 代价 |
|---|---|---|
| **D-1** | **`change_log` 赋责**：写入真实变更摘要（表名 + 主键 + 字段集），读面接**存档诊断**（设置页诊断入口 + `StorageMetrics` 上报） | 满足 `rules/design-plan-review.md` 第三节 YAGNI"每个新抽象必须至少有一个**当前**生产消费者"；诊断是真实需求（摸底报告指出"玩家丢档后查不出原因"） |
| **D-2** | **`FunctionalWAL` 摘除**（解绑 DI + 摘调用点），事务编排归 Room | 规则：养一个"看起来在保护存档"的组件比没有更危险。已有 `WalRetirementGuardTest` 说明退役路径已铺好 |
| **D-3** | **归档读面先给最小可用集**（列表 + 按 id 还原），消费者 = 存档诊断；SS4 的"恢复旧档"复用同一读面 | 两个消费者共用一面，避免各写一套读逻辑 |
| **D-4** | **`StorageMetrics` 补 getter + 上报**，按 `rules/data-analytics.md` 三处同步 | 它是 SS4/SS5/SS7 验证"迁移成功/双路径对拍/云一致性"的前提，不是装饰 |
| **D-5** | **不改保存/读档行为语义**：本批只动组件职责与观测面 | 保持每批独立可验收；行为改动归 SS3/SS5 |
| **D-6** | **守卫测试一律"改语义"而非"放宽"**：`ArchiveWriteOnlyGuardTest`（归档有读者 ⇒ 原断言失效）、`WalRetirementGuardTest`（退役无残留引用） | detekt baseline 只缩不增；守卫放松等于埋雷 |

---

## 4. 文件面与切片（≤10 文件/片）

| 片 | 允许改 | 禁止改 | 自检项 |
|---|---|---|---|
| **SS2-a** `change_log` 赋责 | `ChangeLogEntity.kt`、`ChangeLogDao.kt`、`ChangeLogPersistence.kt`、`StorageEngineSaveSupport.kt`（写入点） | 保存事务边界、`SaveValidator` | 摘要含表名 + 主键 + 字段集；`old/new` 不再是 null 占位；裁剪策略不变 |
| **SS2-b** WAL 摘除 | `StorageModule.kt`（解绑）、`StorageCoreFacade.kt`、`StorageEngine.kt`（begin/commit/checkpoint 调用点）、`FunctionalWAL.kt` / `WALProvider.kt`（删或降级为纯日志并改名）、`WalRetirementGuardTest` | Room 事务结构、`StorageCircuitBreaker` | 启动/保存/读档三条链全绿；全仓无 `FunctionalWAL` 生产引用 |
| **SS2-c** 归档读面 | `DataArchiver.kt`（查询族）、`ArchiveDaos.kt`、`ArchiveWriteOnlyGuardTest` | 归档**写入**策略（保留期/搬运时机） | 列表 + 还原可用；守卫按新职责改写 |
| **SS2-d** Metrics getter + 上报 | `StorageMetrics.kt`、上报接线点、`rules/data-analytics.md` 要求的三处（事件定义/上报点/守卫测试） | 计数器语义 | 每个 getter 有消费者；守卫测试在册 |
| **SS2-e** 诊断入口 | 设置页诊断入口（只读展示最近 N 次保存变更 + 关键计数器） | 存档主流程 | UI 不直写 Store；不新增对话框类型时可挂既有页 |

**共享面**：`StorageModule.kt`（与 SS1 冲突，见 §3.3）；`StorageEngine.kt`（与 SS3/SS5 都要改）⇒ 本批改完后冻结其"事务编排"段。

---

## 5. 门禁清单

```powershell
# 工作目录 = C:\Mnzm\XianxiaSectNative\android
.\gradlew.bat compileReleaseKotlin --console=plain
.\gradlew.bat :core:data:testReleaseUnitTest :app:testReleaseUnitTest --max-workers=1 --console=plain
.\gradlew.bat :core:data:detekt :app:detekt --console=plain
# 本批关键守卫
.\gradlew.bat :core:data:testReleaseUnitTest --tests "*ArchiveWriteOnlyGuardTest*" --tests "*WalRetirementGuardTest*" --max-workers=1 --console=plain
# 仓库根
node scripts/check-agent-instructions.mjs
```

**提交前**：`git status` 只留本批改动。

---

## 6. 登记项

1. **跨批登记（SS4）**：归档读面的"按 id 还原"接口形态要在 SS2 冻结，SS4 只消费不改签名。
2. **跨批登记（SS5/SS7）**：`StorageMetrics` 新增的计数器（脏集回退次数、增量/全量路径分布、云一致性差额）在各自批次追加，**本批只给 getter 与上报框架**。
3. **风险**：`FunctionalWAL` 摘除会改启动路径 ⇒ 必须在报告里给出"摘除前后：启动 / 保存 / 读档 三条链"的对拍证据，不接受"应该无影响"。

---

## 7. 一句话给执行者

**四个半截组件一次收口：`change_log` 写真实变更并给诊断读它、`FunctionalWAL` 整组摘掉、归档表接上读面、`StorageMetrics` 补 getter 并上报——只动组件职责与观测面，不动保存/读档的行为语义。**
