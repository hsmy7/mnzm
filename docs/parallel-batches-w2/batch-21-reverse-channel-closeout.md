> # ⛔ 本批已执行并**改判**（2026-09-15，handover §2.53，提交 `24c429d`）——本文件保留为**设计记录**，勿据以重新派工
>
> **执行结果**：288 站点写者穷尽审计实测 **14 个域无一可整体关闭**（弟子通道 46 稳态站点 / 9 类实体集合 82 站点 /
> 64 个 gameData 字段仍有稳态写者）⇒ 本文"直接关闭反向通道"的路线**前置不成立**。
> 已交付：**可证关闭面 68 单元**（67 gameData 字段 + 顶层段 `lockedBeastIds`）+ **逐域关闭机制**
> （`ReverseChannelPolicy` 单点策略 / 捕获与信封双端闸门 / `reopenDomain` 逐域回滚 /
> 关闭域写入检测 / 穷尽分类守卫 + 体积构成实测）。
> **后续路线改由 [parallel-batches-w3](../parallel-batches-w3/README.md) 承接**：
> UI 操作面收尾 13 批逐域搬迁残余稳态写者 → 终局删除通道（方案见
> [ADR reverse-channel-elimination](../adr/reverse-channel-elimination.md)）。
# Batch-21：反向同步通道关闭批（终局收敛）

| 项 | 内容 |
|---|---|
| 批次 | 21 ｜ **串行批**（⛔ 依赖 batch-11 ~ batch-20 **全部合入 `main`**） |
| 模块 | Kotlin（`GameStateStoreImpl.kt` 捕获面 + `StateSyncService.kt` 信封面）——**本轮唯一可动这两个文件的批** |
| 性质 | 收敛批：逐域停捕获 + 信封摘段 + 信封体积归零可观测验收 |
| 来源 | [ui-read-surface](../ui-read-surface.md) §4.3 关闭动作 + §4.2 顶层 @Transient 段现状 |
| 预分配 | handover **§2.53**（❗更正：原预分配为 §2.52，该节号已被 2026-09-12 第二轮集成收口占用）；无新 ActionId |
| 前置 | **11–20 全部完成并在 AUTHORITATIVE 稳态下各域确有 native 臂** |
| **前置现状（2026-09-13 实测）** | **11–20 已全部交付**（batch-12 与 batch-20b 于 2026-09-13 补齐，见 handover §2.43 / §2.51b）⇒ **"批次侧"前置已达成**。但**"各域稳态写者归 C++"尚未达成**：`ui-read-surface §4.1` 表中仍有 Kotlin 直改写者（库存残余 / 弟子管理后续子批 / 招募俘虏残余 / 月年边界编排残余 / `aiSectDisciples` 段回退路径 / `lockedBeastIds` UI 操作面 等）——**这些残余不属 11–20 的批次范围**，须按其 §4.1"下沉批次"列逐批推进后方可开本批。**不可因"11–20 已完成"就提前开批**（见 §1 因果链） |

## 1. 为什么必须等（关闭前置的因果链）

反向增量回导（`applyDirtyToNative`）当前承担"玩家在 Kotlin 产生的写入 → 回导 C++"。
**只要某域仍有 Kotlin 直改稳态写者，停掉该域捕获 = 该域写入永远到不了 C++（数据丢失缺陷）**。

`11–20` 完成后，各域在 AUTHORITATIVE 稳态下写者归 C++；Kotlin 原路径降级为**回退臂**，仅在 native 不可用时执行——
而 native 不可用时**整条 native 通道本就不存在**（无 C++ 状态可同步），故此时关闭捕获**不再有丢失面**。

> ⚠️ **但"11–20 完成"≠"各域写者归 C++"**（2026-09-13 实测登记）：11–20 是**批次编号**，不是**域清单**——
> `ui-read-surface §4.1` 表里仍有未被任何批次覆盖的 Kotlin 直改写者（库存残余 / 弟子管理后续子批 /
> 招募俘虏残余 / 月年边界编排残余 / `lockedBeastIds` UI 操作面 等）。**开本批前必须按域逐条复核**，
> 而不是按"11–20 是否完成"判断。

> ⚠️ 这也是为什么**绝不能提前关闭任何域**（ui-read-surface §4.3 原文）。开批前必须逐域复核"稳态写者是否真的归 C++"——
> **复核方式**：对该域做一次 `stateStore.update` 写者穷尽扫描，确认剩余写者**只在回退臂分支内**（可用静态扫描 + 逐点人工确认，结论进 §2.53）。

## 2. 范围

| # | 动作 | 落点 |
|---|---|---|
| 1 | **逐域停捕获** | `GameStateStoreImpl.captureReverseDirty`（按域开关/白名单） |
| 2 | **信封摘段** | `StateSyncService.buildReverseEnvelope`（不再携带已关闭域） |
| 3 | **顶层 @Transient 段处理** | `aiSectDisciples` / `lockedBeastIds` / `aiSectBeastDirectTargets` / `aiSectBeastSkipCooldowns` 四段（见 §4） |
| 4 | **体积归零可观测验收** | 反向信封体积逐域下降的实测数据（WS-1 基准：gameData 段全量 5349B/134 键 → 单字段增量 70B/1 键） |
| 5 | **回退臂语义复核** | 关闭后回退臂仍必须正确（native 不可用时不依赖反向通道） |

## 3. 实施步骤

1. **关闭前置复核**（§1）：逐域扫描稳态写者，产出「域 → 是否可关 → 证据」表。**任一域不满足即不关该域**（部分关闭是允许的——按域独立验收）。
2. **停捕获**：在 `GameStateStoreImpl` 加域级开关（默认全开 → 逐域关闭），保持"关闭域不再进脏集"。
3. **信封摘段**：`StateSyncService` 侧同步移除该域段；**旧 C++ 收到不含该段的信封必须为 no-op**（前向兼容已在 §2.21 验证：C++ 对未知集合名宽松忽略——反向亦然，缺段即不应用）。
4. **四段 @Transient 终局判定**（§4）。
5. **验收**：跑真机/模拟器会话，采集反向信封体积逐域下降曲线 + 零数据丢失（存档往返逐字段一致）。
6. **文档**：handover §2.53 + §3 行 + §4.1/§4.3 更新 + 双更新日志。

## 4. 顶层 @Transient 四段的终局判定（[ui-read-surface](../ui-read-surface.md) §4.2）

| 段 | 现状 | 终局判定依据（**2026-09-13 实测更新**） |
|---|---|---|
| `aiSectDisciples` | 独立全量段（S-15 变化检测） | **实测收窄但未归零**：20b 已下沉**阵亡守军清理**（`sect_attack_tx.h removeDeadDefendersTx`，ActionId 1711——本段最大 UI 触发写者）与魂魄发放（1712）；**仍需复核**：月结回退路径的战斗阵亡/吞并写者 + load/存档自愈写者是否仍在 → 若仍在则**保留段**；若月变真相源已切 C++（WS-4 相关项）→ 可关闭。（`GameEngineBattleOps:172` 原始写者已降级为回退臂） |
| `lockedBeastIds` | 独立全量段（**2026-09-08 补齐**，§2.21） | UI `lockBeastView/unlockBeastView` 直改 → **仍需先下沉该 UI 操作面**（本轮 11–20 **未覆盖**）→ 默认**保留段**，并登记 `batch-23` |
| `aiSectBeastDirectTargets` / `aiSectBeastSkipCooldowns` | 无增量段（可接受） | 写者仅在 Kotlin 月结回退路径；AUTHORITATIVE 稳态 C++ 独占 → **维持无段** |

> 结论可能不是"全关"——**部分关闭 + 明确登记保留项**是合格交付；强行全关导致数据丢失才是缺陷。

## 5. 文件所有权与冲突面

- **本批独占**：`GameStateStoreImpl.kt`、`StateSyncService.kt`（11–20 一律只读）。
- **禁改**：各域事务头（11–20 产物）、`models.h`、`GameCoreBridge.*`。
- **共享面**：无（本批不动 action_ids / execute_dispatch / CMakeLists）。

## 6. 确定性要求

- **关闭动作本身必须零行为变更**：关闭前后，同一存档往返的 `GameData` + 各实体集合**逐字段一致**。
- 必配：`Diff*` 对拍类全绿（47 个）+ 存档往返一致性用例 + 反向信封体积实测数据。

## 7. 验收

| 项 | 标准 |
|---|---|
| 前置复核 | 「域 → 可关性 → 证据」表进 §2.53（含未关闭域的登记与理由） |
| 功能 | 逐域关闭后：AUTHORITATIVE 稳态零数据丢失；降级回退臂仍正确 |
| 体积 | 反向信封体积逐域下降实测（对比 WS-1 基准） |
| 回归 | 引擎全量对拍 46 类全绿 + 六模块 detekt 全绿 + NDK + lint + 模块回归 |
| 真机 | 至少一次真机（或按 README §8 的模拟器口径）会话验证：存/读档往返逐字段一致 + 零数据丢失告警 |
| 文档 | §2.53 + §3 行 + ui-read-surface §4.1/§4.3 更新 + 双更新日志 |

## 8. 触碰文件声明

- **改**：`GameStateStoreImpl.kt`、`StateSyncService.kt`、（如需）`StateSyncServiceReverseTest.kt` 等既有测试、handover、`CHANGELOG.md`、`changelog_entries.json`、`docs/ui-read-surface.md`。
- **不触碰**：全部 C++ 事务头、`models.h`、`GameCoreBridge.*`、各域 Kotlin 门面（回退臂保留）。

## 9. 风险与回退

| 风险 | 处置 |
|---|---|
| 某域"稳态写者其实没归 C++"→ 关闭即丢数据 | §1 的逐域穷尽扫描 + 证据表；**任一域不确定即不关该域** |
| 关闭导致回退臂（native 不可用）行为异常 | 回退臂不依赖反向通道；加"flag OFF 全流程"回归（关闭前后一致） |
| 关完后发现漏域 | 开关是逐域的 → **可单域回滚**（把该域开关置回开） |
| 与真机验证批互斥 | batch-22 与本批**串行**（都碰运行时会话）：先 batch-22 采集基线，再 batch-21 关闭后复测 |
