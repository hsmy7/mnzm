# Batch-05：GameStateRepository dirty 记账机制摘除（write-only 残留专项）

| 项 | 内容 |
|---|---|
| 批次 | 05 ｜ 并行组 B（与 batch-04 文件正交——04 跳过本批文件） |
| 模块 | `core:data`（`GameStateRepository.kt`，markDirty:62 / markAllDirty:94 / clearDirty:108） |
| 性质 | 死机制摘除 + **load 失败回滚语义重构**（有行为依赖，非纯删除） |
| 来源 | handover §2.26 途中发现：「GameStateRepository dirty 机制为 write-only 残留（flushDirtyState 死链删除后，markDirty/markAllDirty/clearDirty 只置位无消费者）——WS-0.a 自动存档删除的记账残留；markAllDirty 在 loadFromSnapshot 失败回滚路径有行为依赖（StateRevertRegressionTest 注入失败触发回滚），保留并在专项批拍板（整体摘除须重构 load 回滚语义）」 |
| 预分配 | handover 小节 §2.34 |

## 1. 背景与目标路径

WS-0.a（2026-09-04）删除自动存档时清掉了 `markDirty()/consumeDirty()` 死代码链
（GameStateStoreImpl 侧），但 `core:data` 的 GameStateRepository 仍残留一套 dirty
记账：`markDirty(...)` / `markAllDirty()` / `clearDirty()` 只置位、**无任何消费者**
（§2.26 已删 flushDirtyState/flushDisciples/restoreDirtyMarks 死链）。唯一行为依赖：
`markAllDirty` 在 `loadFromSnapshot` **失败回滚路径**被调用，
`StateRevertRegressionTest` 以注入失败触发回滚并断言。

**本批目标**：确认 markAllDirty 在回滚路径的实际作用（若也是 write-only 则整体摘除；
若回滚语义真实依赖某副作用，重构回滚实现使依赖显式化后摘除记账），把 WS-0.a 的
记账残留清干净。

## 2. 实施步骤

1. **考古**：读 GameStateRepository dirty 三方法 + 全部调用点（生产/测试）；读
   `loadFromSnapshot` 失败回滚分支 + `StateRevertRegressionTest` 断言——回答
   「回滚路径上 markAllDirty 改变的任何状态，后续是否有人读」。
   - 若无人读 → 回滚语义实际由别处承载（如状态快照恢复本身），测试绿只是巧合——
     摘除后测试仍应绿（回归证明）。
   - 若有人读（隐藏耦合）→ 把该读取显式化（回滚路径直接重建/重读，不经 dirty 位），
     再摘除记账。
2. **摘除**：删三方法 + 全部置位调用点 + 相关字段；调用点若在表达式位置注意保留
   副作用语义（WS-0.a §2 副作用保留改造 7 处先例——elvis 早退/裸语句化）。
3. **测试**：StateRevertRegressionTest 保持全绿（必要时改断言对象为显式回滚语义，
   登记理由）；补一条「读档失败后状态与读档前逐位一致」的回归断言（若尚无）。
4. 顺带核查 `SlotCache.markDirty()`（core:engine repository 包同名方法，非本批对象）：
   若同族 write-only，登记进 §2.34 待办（跨模块让 batch-01 避让，不扩本批范围）。

## 3. 纪律

- `:core:data` baseline 已清零——本批**不得**产生任何新 baseline 条目。
- batch-04 协议：GameStateRepository 为本批所有权文件，04 跳过其中 suspend catch 点。
- 摘除后 `:core:data` 全量 707 用例 + 引擎对拍全量必跑（读档回滚是 Diff 家族敏感面）。

## 4. 验收

1. dirty 三方法与置位调用点全删（或回滚语义显式化重构后删）；考古结论进 §2.34。
2. §4 模板：①②③（对拍——读档路径零行为影响证明）④（data 全量 + app state/repository）
   ⑤。
3. handover §2.34 + §4.1 相关注册（§2.26 途中发现条目）勾销 + CHANGELOG。

## 5. 本批触碰文件声明

- `android/core/data/**/GameStateRepository.kt` + 其调用点 + `StateRevertRegressionTest`
  （引擎侧测试只改断言不改产品码时与 batch-01 冲突面为零，仍按文件清单暂存）。
- 不触碰：detekt baseline/guard、C++、其他模块产品码。
