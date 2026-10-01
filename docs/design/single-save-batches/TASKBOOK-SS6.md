# TASKBOOK-SS6 · 事件触发自动存档

> **本文件是 SS6 的派工真源**（开工补卡）。上位方案 [`single-save-and-persistence-consolidation-plan-2026-10-01.md`](../single-save-and-persistence-consolidation-plan-2026-10-01.md) **§2.5**（自动存档：短间隔节拍 + 关键事件立即落盘）。
> 协议：[`DISPATCH-ledger.md`](DISPATCH-ledger.md) §4 + [`../gacha-batches/EXECUTION-PROTOCOL.md`](../gacha-batches/EXECUTION-PROTOCOL.md)。
> 入口判据：**SS5 已合入**（`f13b14ad6`，关键事件落盘成本已从全量降为增量）；`StorageEngine.kt` / `StorageEngineWriteOps.kt` 冻结区本批解禁（保存触发链）。
> ⚠️ 既有纪律（根 AGENTS.md §3）：**禁止复活旧月变触发式自动存档**；命名统一 `realtimeAutoSave*` 族；节拍常量 10s **不动**。

---

## 1. 目标与验收判据

| 项 | 内容 |
|---|---|
| 业务目标 | 关键事件（涉钱/不可逆/唯一性）发生时，**事件返回前数据已落盘**——把"等 10s 节拍"的丢失窗口从结构上关掉。 |
| 验收① | **事件集接入**（方案 §2.5 五类，v2 适配）：玉符流水 append（涉钱，同步语义）；抽卡碎片/高品阶物品入库（不可逆随机，结果展示前）；碎片合成/升星、熔炼（不可逆消耗，与产出同事务窗口）；首次通关/成就/渡劫成败（唯一性里程碑）；~~`MIGRATION_*` 成功~~（**迁移链已随 SS0 退役，该事件不存在——报告注明 v2 适配**）；删档/重置成功（SS0 的 `SaveWipeCoordinator` 完成路径，v2 新增点）。每个事件点 enumerated 进报告（触发位置/类别/合并窗类型） |
| 验收② | **防风暴纪律**：事件触发**不直接调保存**，一律 `SaveOrchestrator.submit(trigger)`（既有接口）——500ms 合并窗内多次事件合成一次落盘；涉钱类走 `flushNow` 立即冲刷（既有分支） |
| 验收③ | **时序断言**：涉钱事件（玉符流水 append）在事件调用返回前**已落盘**（`flushNow` 同步语义）——测试断言"事件方法返回时 DB 已含该笔流水落盘结果" |
| 验收④ | **合并窗计数断言**：十连抽 10 次事件（500ms 窗内）→ **恰好 1 次**落盘（`SaveOrchestratorTest` 扩展） |
| 验收⑤ | **落盘后入云队列**：关键事件落盘成功即入 `UploadQueue`（既有 2s 去抖/限频/退避语义复用，不新建云通道） |
| 验收⑥ | **三前置门控随批收口**：`shouldAutoSave(flagOn, hasActiveSlot, engineLoaded)`（`SaveTriggerFlag.kt:59`，SS4 报告 §6.2 登记参数名含 slot）——参数名随批收敛（如 `hasSaveSpace`），行为语义不变 |
| **不做** | 节拍常量 10s 与 `SaveOrchestrator` 500ms 合并窗、`onStop` 后台保存**全不改**；不新增 Kotlin→C++ 反向状态写（`MirrorReadOnlyGuardTest` 零命中）；不改增量路径（SS5）；不碰 C++；禁月变触发式自动存档复活 |

---

## 2. 实测现状（2026-10-02 主树）

- `SaveOrchestrator.kt`（`feature/game/saveload/`）：`submit(trigger: AutoSaveTrigger)` 500ms 合并窗 + `flushNow` 分支已存在（`:69/:85`）；`SaveOrchestratorTest` 在册。
- 节拍：`SaveLoadViewModelAutoSaveOps.kt:21` `REALTIME_AUTO_SAVE_INTERVAL_MS = 10_000L`（保留）。
- 事件源位置：玉符流水 = C++ `appendLedgerEntry`（SS9）→ 回执经 `JadeSymbolService`；碎片/物品 = G 批 `gacha_tx` 产物 → `applyDirty` 镜像；里程碑 = 成就/渡劫结算；删档 = `SaveWipeCoordinator`。接线点在 Kotlin 侧消费回执/镜像后（**不在 C++**）。
- `shouldAutoSave(flagOn, hasActiveSlot, engineLoaded)`：`core/data/SaveTriggerFlag.kt:59`。

---

## 3. 决策

| # | 决策 | 依据 / 代价 |
|---|---|---|
| **D-1** | **事件点在 Kotlin 回执/镜像消费侧接线**，不经 C++ | C++ 无存档概念；`MirrorReadOnlyGuardTest` 零命中红线 |
| **D-2** | **分类驱动而非资源名驱动**：`AutoSaveTrigger` 语义按方案五类映射（涉钱 flushNow / 其余 submit 合并窗） | 方案 §2.5 分类原则；避免每个资源一个触发点 |
| **D-3** | **合并窗类型：仅涉钱 flushNow**，其余（抽卡/合成/里程碑/删档）走 500ms 合并窗 | 十连抽 10 事件→1 落盘的防风暴目标；删档/重置本身是低频单发 |
| **D-4** | **时序语义只对涉钱类承诺**（同步落盘），其余为"尽快落盘"（合并窗内） | 方案原文涉钱行明示"同步"；其余类未承诺返回前完成 |
| **D-5** | **`shouldAutoSave` 参数名收口随批**（SS4 §6.2 登记），行为零变更 | 命名残留清零；`hasActiveSlot` 语义即"已有数据空间"，更名不改判定 |

---

## 4. 文件面与切片（≤10 文件/片）

| 片 | 允许改 | 禁止改 | 自检项 |
|---|---|---|---|
| **SS6-a** 触发器扩展 | `AutoSaveTrigger`（类别映射）/`SaveOrchestrator`（如需新 trigger 值）+ `SaveOrchestratorTest` 扩展 | 500ms 窗/节拍常量/onStop 链 | 合并窗计数断言绿 |
| **SS6-b** 事件点接线 | `JadeSymbolService`（流水回执后）、抽卡/合成/熔炼结果消费面、里程碑结算、`SaveWipeCoordinator`（v2 新增点） | C++、镜像写入面 | 事件点 enumerated 表完整；每点仅 `submit`/`flushNow` 一行级接线 |
| **SS6-c** 时序断言 | 涉钱同步落盘测试（事件返回前 DB 已写） | 保存主链语义 | 断言真实打 DB（Robolectric） |
| **SS6-d** 参数名收口 | `SaveTriggerFlag.kt` + 调用点 | 判定逻辑 | 行为零变更（现有测试全绿） |
| **主线程** | `report-SS6.md`、门禁复跑 | — | §5 清单 |

**共享面**：`StorageEngine.kt`/`StorageEngineWriteOps.kt` 冻结解除给保存触发链——本批**只许动触发判定/调用面，不许动写路径本体**（SS5 增量写）；改完重新冻结。

---

## 5. 门禁清单

```powershell
# 工作目录 = C:\Mnzm\XianxiaSectNative\android
.\gradlew.bat compileReleaseKotlin --console=plain
.\gradlew.bat :core:data:testReleaseUnitTest :core:engine:testReleaseUnitTest :feature:game:testReleaseUnitTest :app:testReleaseUnitTest --max-workers=1 "-Dgamecore.jni.path=C:\Mnzm\XianxiaSectNative\android\core\engine\build\desktop-jni\libgamecorejni.so" --rerun-tasks --console=plain
.\gradlew.bat :core:data:detekt :core:engine:detekt :feature:game:detekt :app:detekt --console=plain
node scripts/check-agent-instructions.mjs   # 改文档面后必跑
```

**提交前**：`git status` 只留本批改动；构建副产物 `git checkout --` 还原；一次性脚本清零；**文档面编辑一律在 worktree 内完成并随批 commit**。

---

## 6. 登记项

1. **跨批登记（SS7）**：关键事件入云队列走既有 `UploadQueue`（SS7 台账键坍缩时一并收口）。
2. **跨批登记（SS10）**：玩家可见变更（关键数据丢失窗口关闭）并入 4.2.00 唯一条目。
3. **风险（必须写进报告）**：涉钱 flushNow 的**主线程阻塞面**——同步落盘在事件调用线程完成，若事件在主线程发起则落盘 IO 也在主线程（既有 `flushNow` 语义如斯）——报告须给出事件发起线程的核实结论与耗时实测（增量路径应为亚毫秒~毫秒级）。
4. **风险**：事件点遗漏=丢失窗口残留——报告 enumerated 表须与方案五类逐类对账（v2 适配注明）。

---

## 7. 一句话给执行者

**在玉符流水、抽卡产物、合成/熔炼、里程碑、删档完成这些 Kotlin 消费点接一行级 `SaveOrchestrator.submit`（涉钱 flushNow 同步落盘），十连 10 事件合并成 1 次落盘，时序断言锁"涉钱返回前已写库"——节拍 10s、合并窗 500ms、增量路径全都不动。**
