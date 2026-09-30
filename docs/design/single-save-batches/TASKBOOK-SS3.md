# TASKBOOK-SS3 · 持久化面收口四组件（change_log / WAL / 归档 / Metrics）

> **本文件是 SS3 的派工真源**。上位方案 §2.6。
> 协议：[`DISPATCH-ledger.md`](DISPATCH-ledger.md) §4.3 + [`../gacha-batches/EXECUTION-PROTOCOL.md`](../gacha-batches/EXECUTION-PROTOCOL.md)。
> 入口判据：**SS0 已合入**；`StorageModule.kt` 与 `StorageEngine.kt` **无他批在途改动**（本批与 SS2 共享 `StorageModule.kt`，按 ledger §4.3 **SS2 先、SS3 后**）。
> ⚠️ 删档重置后**不再有"恢复旧档"需求** ⇒ 归档读面的消费者收窄为**存档诊断 + 战报/陨落历史**。

---

## 1. 目标与验收判据

| 项 | 内容 |
|---|---|
| 业务目标 | 四个"半截"持久化组件逐一**赋责或摘除**。 |
| 验收① | **`change_log` 写入真实变更摘要**（表名/主键/字段集），不再写 `old/new = null` 空行；**读面有真实生产消费者**（存档诊断 + `StorageMetrics` 上报），不是"为将来预留" |
| 验收② | **`FunctionalWAL` 摘除**：解除 DI 绑定与全部调用点；事务编排职责明确归 Room；`WalRetirementGuardTest` 改为"退役面无残留引用"断言（**改语义，不放宽**） |
| 验收③ | **归档表接上读面**：最小可用集 = ① 列表（按时间）② 按 id 还原载荷；消费者 = 存档诊断 + 战报/陨落历史（**不再服务"恢复旧档"**） |
| 验收④ | **`StorageMetrics` 补 getter + 上报**（按 `rules/data-analytics.md` 三处同步：事件定义 / 上报点 / 守卫测试） |
| 验收⑤ | `ArchiveWriteOnlyGuardTest` 按**新职责**改写（原断言"归档读者为空"必须失效并被替换，**不得删除守卫**） |
| **不做** | 不改槽位（SS1 已做）；不改保存/读档的**行为语义**；不接云端（SS7）；不做增量落盘（SS5）；不碰 C++ |

---

## 2. 实测现状

| 组件 | "半截"表现 |
|---|---|
| `change_log` | 唯一生产写点 `StorageEngineSaveSupport.kt:69-81`，每次保存写 **1 行 UPDATE**、`old/new` 恒 `null`；读方法（`getUnsyncedChanges` 一族）**生产零调用者**；7 天剪除 `DataPruningScheduler.kt:210-214` |
| `FunctionalWAL` | DI 绑定 `StorageModule.kt:51`；持有者 `StorageCoreFacade.kt:18`；`StorageEngine.kt:611-621` begin、`:643-651` commit、`:634-640` checkpoint、CLOUD_ONLY 停开 `:612`；**条目不承载数据字节**，`recover()` 仅记日志（`:559-578`）⇒ 耐久性完全由 Room 事务承担 |
| 归档表 | DI `StorageModule.kt:80-84`；持有者 `StorageEngine.kt:77`；`DataArchiveScheduler`（600s）写入，行内 `dataBlob` 是**可还原全量载荷**；查询面零生产调用者，`ArchiveWriteOnlyGuardTest` 把"无读者"钉死为现状 |
| `StorageMetrics` | 持有者 `StorageInfraFacade.kt:16`；**8 个计数器无任何 getter** ⇒ 存档失败率/耗时线上不可见 |

---

## 3. 决策

| # | 决策 | 依据 / 代价 |
|---|---|---|
| **D-1** | **`change_log` 赋责**：写真实变更摘要，读面接**存档诊断**（设置页诊断入口 + `StorageMetrics` 上报） | 满足 `rules/design-plan-review.md` 第三节 YAGNI"每个新抽象至少一个**当前**生产消费者"；"玩家丢档后查不出原因"是真实需求 |
| **D-2** | **`FunctionalWAL` 摘除**（解绑 DI + 摘调用点），事务编排归 Room | 养一个"看起来在保护存档"的组件比没有更危险；已有 `WalRetirementGuardTest` 说明退役路径已铺好 |
| **D-3** | **归档读面最小集**（列表 + 按 id 还原），消费者 = 存档诊断 + 战报/陨落历史 | 删档后不再有"恢复旧档"，读面消费者随之收窄；**不得**因消费者变化而把读面砍掉（否则又回到只写不读） |
| **D-4** | **`StorageMetrics` 补 getter + 上报** | 它是 SS5（双路径对拍）、SS7（云一致性）验证的前提 |
| **D-5** | **不改保存/读档行为语义** | 保持每批独立可验收；行为改动归 SS1/SS5 |
| **D-6** | **守卫一律"改语义"而非"放宽"** | detekt baseline 只缩不增；守卫放松等于埋雷 |

---

## 4. 文件面与切片

| 片 | 允许改 | 禁止改 | 自检项 |
|---|---|---|---|
| **SS3-a** `change_log` 赋责 | `ChangeLogEntity.kt`、`ChangeLogDao.kt`、`ChangeLogPersistence.kt`、`StorageEngineSaveSupport.kt`（写入点） | 保存事务边界、`SaveValidator` | 摘要含表名 + 主键 + 字段集；`old/new` 不再是 null 占位；裁剪策略不变 |
| **SS3-b** WAL 摘除 | `StorageModule.kt`（解绑）、`StorageCoreFacade.kt`、`StorageEngine.kt`（begin/commit/checkpoint 调用点）、`FunctionalWAL.kt` / `WALProvider.kt`（删）、`WalRetirementGuardTest` | Room 事务结构、`StorageCircuitBreaker` | 启动/保存/读档三条链全绿；全仓零 `FunctionalWAL` 生产引用 |
| **SS3-c** 归档读面 | `DataArchiver.kt`（查询族）、`ArchiveDaos.kt`、`ArchiveWriteOnlyGuardTest` | 归档**写入**策略（保留期/搬运时机） | 列表 + 还原可用；守卫按新职责改写 |
| **SS3-d** Metrics getter + 上报 | `StorageMetrics.kt`、上报接线、`rules/data-analytics.md` 要求的三处 | 计数器语义 | 每个 getter 有消费者；守卫测试在册 |
| **SS3-e** 诊断入口 | 设置页诊断入口（只读展示最近 N 次保存变更 + 关键计数器） | 存档主流程 | UI 不直写 Store |

**共享面**：`StorageModule.kt`（与 SS2 冲突 ⇒ SS2 先）；`StorageEngine.kt`（与 SS5 冲突 ⇒ 本批改完后冻结其"事务编排"段）。

---

## 5. 门禁清单

```powershell
# 工作目录 = C:\Mnzm\XianxiaSectNative\android
.\gradlew.bat compileReleaseKotlin --console=plain
.\gradlew.bat :core:data:testReleaseUnitTest :app:testReleaseUnitTest --max-workers=1 --console=plain
.\gradlew.bat :core:data:detekt :app:detekt --console=plain
.\gradlew.bat :core:data:testReleaseUnitTest --tests "*ArchiveWriteOnlyGuardTest*" --tests "*WalRetirementGuardTest*" --max-workers=1 --console=plain
node scripts/check-agent-instructions.mjs
```

---

## 6. 登记项

1. **跨批登记（SS5/SS7）**：`StorageMetrics` 新增计数器（脏集回退次数、增量/全量路径分布、云一致性差额）在各自批次追加；**本批只给 getter 与上报框架**。
2. **风险**：`FunctionalWAL` 摘除会改启动路径 ⇒ 报告必须给出"摘除前后：启动 / 保存 / 读档 三条链"的对拍证据，不接受"应该无影响"。
3. **注意**：归档读面的消费者在删档后已收窄（无"恢复旧档"）——若实施时发现读面只剩"诊断"一个消费者，仍需保留，因为它同时修复了"只写不读"。

---

## 7. 一句话给执行者

**四个半截组件一次收口：`change_log` 写真实变更并让诊断读它、`FunctionalWAL` 整组摘掉、归档表接上读面（消费者是诊断与战报历史，不再是"恢复旧档"）、`StorageMetrics` 补 getter 并上报——只动组件职责与观测面。**
