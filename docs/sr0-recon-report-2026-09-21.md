# SR-0 前置侦察报告（存档云唯一重构 · 前置事实核）

> 日期：2026-09-21
> 批次：SR-0（施工卡 = `docs/parallel-batches-w5/batch-SR0.md`；方案 = `docs/save-system-refactor-plan-2026-09-21.md` §4 SR-0）
> 性质：**零产品代码改动**。实测台架入测试目录（保留理由见各节），文档复核引官方来源。
> 结论速览：**T5 嵌套事务 = 并入同一事务，存疑解除**；T1/T2/T3/T4 结论见各节，§6 汇总 Go/No-Go 与 IN5 红线建议。

---

## §5 嵌套事务存疑收口（T5）——✅ 结论：并入同一事务，存疑解除

**审计存疑**（`save-system-audit-2026-09-21.md` §15 存疑 1，等级 C）：外层 `StorageEngine.performFullTransactionSave` + 内层 `StorageEngineWriteOps.writeAllDataToDatabase:55` 两处 `Room.withTransaction` 是否并入同一事务（"切线程后嵌套不一定合并"）。

### 5.1 静态证据（room-runtime 2.7.0，Google Maven sources jar）

Room 2.7.0（KMP 重写）事务实现在 `ConnectionPoolImpl`：

- `beginTransaction`：`transactionStack` **空** → `BEGIN DEFERRED/IMMEDIATE/EXCLUSIVE TRANSACTION`；**非空** → `SAVEPOINT '<depth>'`。嵌套调用在**同一连接**上只开 savepoint，不开新事务；
- `endTransaction(success)`：栈空（最外层）→ `END TRANSACTION`/`ROLLBACK TRANSACTION`；否则 `RELEASE SAVEPOINT`/`ROLLBACK TO SAVEPOINT`；
- `Transactor.withTransaction` KDoc 原文："If [inTransaction] returns true and this function is invoked it is the equivalent of starting a nested transaction … the [type] of the transaction will be ignored since its type will be inherited from the parent transaction."

（注：方案/审计时代 Room≤2.6 的 room-ktx `TransactionElement` 引用计数机制已在 2.7 KMP 重写中被连接级 `transactionStack` + SAVEPOINT 取代；两者结论一致——嵌套合并。）

### 5.2 实跑证据（Robolectric 真实 `GameDatabase`，4/4 绿）

测试：`android/core/data/src/test/java/com/xianxia/sect/data/local/RoomNestedTransactionSemanticsTest.kt`
（与生产两调用点同构：同一 `GameDatabase` 实例、suspend DAO 写入；台架**保留为 Room 升级守卫**——版本变化若破坏嵌套合并语义，四例即红。）

| # | 场景 | 断言 | 结果 |
|---|---|---|---|
| 1 | 嵌套成功 | 内外双写均提交 | ✅ 绿 |
| 2 | **内层"已成功"后外层失败** | 内层已写随外层**全量回滚**（无独立提交点——合并性核心证据） | ✅ 绿 |
| 3 | 内层失败未捕获 | 异常穿透，外层早于内层的写一并回滚 | ✅ 绿 |
| 4 | 内层失败被外层吞 | **仅内层写回滚**（`ROLLBACK TO SAVEPOINT`），外层早/晚于内层的写照常提交 | ✅ 绿 |

线程探针：外层/内层同在 `arch_disk_io_*` 单事务线程执行——"切线程导致不合并"的担忧不成立。

### 5.3 生产语义结论

- `writeAllDataToDatabase` 内层抛异常是**穿透路径**（无人吞）⇒ 外层全量回滚，`performFullTransactionSave` 失败分支（`StorageEngine.kt:599-604`）"事务已回滚，DB 保持旧数据"的注释**与实测一致**，heavy 先删后写 + 轻量写的原子性成立；
- **注意事项**（供后续批知悉）：场景 ④ 表明若未来有人在内外层之间吞异常，回滚范围缩小为"仅内层写"（2.7 savepoint 行为，旧 room-ktx 会连内层写一起保留）——写代码时不得依赖吞内层异常来"部分提交"；
- 审计行号漂移登记：`StorageEngine.kt:646`（审计时点）→ 现 `:596`，内层 `WriteOps:55` 未漂移。

### 5.4 回写

结论已以补注方式写入 `docs/save-system-audit-2026-09-21.md` §15 存疑表之后（【SR-0 补注 2026-09-21】段），原表未改动。

---

## §1 payload 尺寸分布实测（T1）

（待 T1 完成后回填）

## §2 TapTap v4 限额复核（T2）

（待 T2 完成后回填）

## §3 云多档语义勘察（T3）

（待 T3 完成后回填）

## §4 双设备冲突剧本设计（T4）

（待 T4 完成后回填）

## §6 结论汇总：Go/No-Go 与 IN5 红线建议

（待全部任务完成后回填）
