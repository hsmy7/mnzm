# TASKBOOK-SS0 · 口径冻结与既有分叉收敛

> **本文件是 SS0 的派工真源**。上位方案：[`../single-save-and-persistence-consolidation-plan-2026-10-01.md`](../single-save-and-persistence-consolidation-plan-2026-10-01.md) §3.6。
> 协议：[`DISPATCH-ledger.md`](DISPATCH-ledger.md) §6 + [`../gacha-batches/EXECUTION-PROTOCOL.md`](../gacha-batches/EXECUTION-PROTOCOL.md)（照用）。
> 开工时点：HEAD `7c15015e8`，工作树干净（开工前复核）。
> 证据等级：本卡行号来自**影响面普查 + 主线程直读**，均为实测；实施时先 `grep` 复核再改。

---

## 1. 目标与验收判据

| 项 | 内容 |
|---|---|
| 业务目标 | 把"槽位数量"这件事从**三份字面量 + 两套遍历口径 + 一份死配置**收敛成**单一真源 + 统一口径**，为 SS1/SS3 的槽空间收缩扫清地基。本批**不改槽语义**（仍是 6 槽），只消除分叉。 |
| 验收① | `DEFAULT_MAX_SLOTS` 在**全仓只有一个字面量定义**（`StorageConstants.kt:20`）；`StorageConfig.kt:111` 与 `SlotLockManager.kt:31` 的同名字面量**删除**，改为引用前者 |
| 验收② | 槽位**遍历口径统一**：`DataPruningScheduler.kt:24`（现 `0..DEFAULT_MAX_SLOTS`，7 个）与 `DataArchiveScheduler.kt:31`（现 `1..DEFAULT_MAX_SLOTS`，6 个）不再各写一套；两处共用同一"合法槽集合"来源 |
| 验收③ | **slot 0 写入可达性取证完成**：用检索给出"生产代码是否存在 `slot_id = 0` 的写入路径"的结论（含命中清单）；若可达 ⇒ 归档面必须覆盖 0；若不可达 ⇒ 加守卫断言阻断未来写入。**不得以注释代替取证** |
| 验收④ | `StorageConfig.maxSlots`（`:36-37`，生产零消费者）**处置完毕**：删除，或在方案里写明接入点与守卫——**禁止留着"看起来可配"的死配置** |
| 验收⑤ | 合法槽集合**单一来源**：`SaveFileManager.isValidSlot`（`:501`，取 `StorageConstants`）与 `SlotLockManager.slotIndexMap`（`:49`，取自有字面量）不再各自成集 |
| 验收⑥ | `StorageConstantsTest` 的 `DEFAULT_MAX_SLOTS is 6` 断言随口径更新并**注明理由**（不得静默改数） |
| **不做** | 不改槽语义（不把 6 改成 1，那是 SS3）；不删任何表；不碰 C++；不引入账号维度（SS1）；不收敛旧 MMKV 键（SS4） |

---

## 2. 实测现状（本批要消除的分叉）

### 2.1 三处独立字面量

| 位置 | 内容 | 处置 |
|---|---|---|
| `android/core/data/.../StorageConstants.kt:20` | `const val DEFAULT_MAX_SLOTS = 6` | **保留为单一真源** |
| `android/core/data/.../config/StorageConfig.kt:111` | `const val DEFAULT_MAX_SLOTS = 6` | **删除**，改引用 |
| `android/core/data/.../concurrent/SlotLockManager.kt:31` | `const val DEFAULT_MAX_SLOTS = 6` | **删除**，改引用 |

### 2.2 配置面与实际面已分叉

- `StorageConfig.kt:36-37`：`val maxSlots get() = store().getInt("max_slots", DEFAULT_MAX_SLOTS)` —— **生产零消费者**（MMKV 可覆盖的槽位上限读端从未被读）。
- `SlotLockManager.kt:26`：`private val maxSlots: Int = DEFAULT_MAX_SLOTS`；构造点 `StorageModule.kt:31-35` 的 `SlotLockManager()` **不传参**。
- `StorageEngine.kt:519`：槽位循环用 `core.lockManager.getMaxSlots()`，**不是** `storageConfig.maxSlots`。
- ⇒ 实际生效的是 `SlotLockManager` 的默认值，"可配置"是幻觉。

### 2.3 两套遍历口径（已在注释里自陈）

- `DataPruningScheduler.kt:24`：`slotIds = (0..StorageConstants.DEFAULT_MAX_SLOTS).toList()` ⇒ **0..6，7 个**（含云会话伪槽）。
- `DataArchiveScheduler.kt:31`：`slotIds = (1..StorageConstants.DEFAULT_MAX_SLOTS).toList()` ⇒ **1..6，6 个**。
- `DataArchiveScheduler.kt:28-30` 注释原文自陈："与 `DataPruningScheduler` 的 `(0..DEFAULT_MAX_SLOTS)` 口径不一致。改为覆盖全部**本地存档槽** 1..DEFAULT_MAX_SLOTS；slot 0 是云档槽（本地无行）故不纳入。"
- ⇒ **本批必须用检索裁决**：`slot 0` 是否真的"本地无行"。注意 `RepoInterfaces.kt` 有 **35 处 `slotId: Int = 0` 默认值**，任何漏传参数的调用点都可能写入 `slot_id = 0` 的行（`isValidSlot(0)` 为真）。

### 2.4 合法槽集合的第二处来源

- `SaveFileManager.kt:501`：`isValidSlot(slot) = slot in 0..StorageConstants.DEFAULT_MAX_SLOTS`
- `SlotLockManager.kt:49`：`slotIndexMap = (0..maxSlots).map { it to it }`（`maxSlots` 来自**自有字面量**）
- `SlotLockManager.kt:35`：锁数组 `Array(maxSlots + 2) { Mutex() }` —— **`+2` 的语义本批不改**，仅改常量来源。

---

## 3. 决策

| # | 决策 | 依据 / 代价 |
|---|---|---|
| **D-1** | **单一真源 = `StorageConstants.DEFAULT_MAX_SLOTS`**；其余两处删除并改引用 | 三处字面量是"改一处必漏两处"的直接成因（SS3 收缩槽空间时会是头号踩坑点） |
| **D-2** | **`StorageConfig.maxSlots` 删除**（含 `max_slots` MMKV 键的读取） | 生产零消费者；留着会给实施者"槽位数可配"的错误预期。若未来确需可配，走 RemoteConfig（当前 `CoreModule` 未绑定，且 `rules/commercialization.md:47` 明令未具服务端能力前禁改绑定） |
| **D-3** | **遍历口径统一为"一个合法槽集合常量"**，两调度器共用 | 消除 `7 vs 6`；具体集合取值取决于 D-4 的取证结论 |
| **D-4** | 🔴 **先取证再定口径**：检索生产代码中 `slot_id = 0` 的**写入可达性**（DAO 写入点 + `RepoInterfaces` 默认值调用方 + 云会话链路） | 注释称"slot 0 本地无行"，但 35 处默认值 `slotId = 0` 让该断言**需要证据**。若可达而归档不覆盖 0，会形成"只裁不归档"的无界增长；若不可达，则加守卫阻断未来写入（两条路都要落地，不允许"维持现状"） |
| **D-5** | **合法槽集合单一来源**：`SaveFileManager.isValidSlot` 与 `SlotLockManager.slotIndexMap` 必须取自同一常量 | 两处各自成集会让"合法槽"在文件层与锁层不一致 |
| **D-6** | **本批不动槽语义**：`DEFAULT_MAX_SLOTS` 仍为 6；SS3 才把它收缩为单档 | 保持每批独立可验收；避免 SS0 变成半个 SS3 |

---

## 4. 文件面与切片（≤10 文件/片）

| 片 | 允许改 | 禁止改 | 自检项 |
|---|---|---|---|
| **SS0-a** 常量归一 | `StorageConstants.kt`、`StorageConfig.kt`、`SlotLockManager.kt` | 槽语义、`+2` 数组尺寸语义 | 全仓 `DEFAULT_MAX_SLOTS =` 仅剩 1 处字面量定义 |
| **SS0-b** 口径统一 | `DataPruningScheduler.kt`、`DataArchiveScheduler.kt`、`SaveFileManager.kt` | 具体保留策略（天数/条数） | 两调度器共用同一集合来源；`isValidSlot` 与 `slotIndexMap` 同源 |
| **SS0-c** 取证与守卫 | 新增取证记录（写入 `report-SS0.md`）＋ 必要的守卫测试 | 生产逻辑 | `slot_id = 0` 写入可达性结论**带命中清单**；按结论落归档覆盖或守卫阻断 |
| **SS0-d** 测试同步 | `StorageConstantsTest`、`SlotLockManagerTest`、`SaveFileManagerTest` | 断言强度 | 断言更新须注明理由，不得放宽 |

**共享面**：`StorageConstants.kt` 被 SS1/SS2/SS3 共同引用 ⇒ 本批改完后**冻结**该文件的常量区，后续批只读。

---

## 5. 门禁清单

```powershell
# 工作目录 = C:\Mnzm\XianxiaSectNative\android
.\gradlew.bat compileReleaseKotlin --console=plain
.\gradlew.bat :core:data:testReleaseUnitTest --max-workers=1 --console=plain
.\gradlew.bat :core:data:detekt --console=plain
# 仓库根
node scripts/check-agent-instructions.mjs   # 仅在改 docs/ 后需要
```

**提交前**：`git status` 只留本批改动；构建副产物（`atlas-rgba-manifest.json` 等）`git checkout --` 还原。

---

## 6. 登记项

1. **本批不做而后续批依赖**：槽位取值集合守卫（"生产代码传入 DAO 的 slot 实参 ⊆ 冻结集合"）归 **SS3**——因为集合要到 SS3 才收紧为 `{0, 1}`。
2. **需在报告里写明的风险**：D-4 的取证结论若为"slot 0 写入可达"，则本批范围内的归档覆盖只解决**归档归属**，不解决"谁写进了 0"——后者登记给 SS3（消灭 `RepoInterfaces` 默认值，铁律 17）。

---

## 7. 一句话给执行者

**本批只做一件事：把"槽位数"从三份字面量、两套遍历口径、一份死配置收敛成单一真源 + 统一口径，并且用检索裁决 `slot 0` 到底有没有本地行——不改槽语义、不删表、不碰 C++。**
