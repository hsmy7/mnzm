# REPORT-SS7 · 云：灾备 + 换设备续玩

> 批次真源：[`TASKBOOK-SS7.md`](TASKBOOK-SS7.md)；协议：[`DISPATCH-ledger.md`](DISPATCH-ledger.md) §4/§7 + [`../gacha-batches/EXECUTION-PROTOCOL.md`](../gacha-batches/EXECUTION-PROTOCOL.md)。
> 分支：`feat/single-save-SS7`（基线 = main `85ed336d5`，含 SS0–SS6 全部并网结果）。

---

## 1. 做了什么（分类表）

### ① 坍缩（槽位维度 → 单键）

| 面 | 改动 |
|---|---|
| **云端命名**（验收②/D-1） | `TapTapSaveBackend`：`V2_SLOT_PREFIX`/`archiveNameFor`/`slotFromArchiveName` 全删；`SESSION_ARCHIVE_NAME` 更名 `ARCHIVE_NAME`（值不变 = `mnzm_v2_save`）；upload/download/currentCloudSaveId 接口去 `slot` 参数，全部操作定位唯一云档 |
| **台账单键**（D-2） | `UploadLedger` 全方法去 `slot` 参数；键 `cloud_upload_ledger_slot{N}_*` → `cloud_upload_ledger_{last_local,last_confirmed,pending}`；`resetSlot(slot)` → `reset()` |
| **队列单键**（D-2） | `UploadQueue`：`enqueue(slot,…)` → `enqueue(saveData, saveId)`；`resolveConflict(slot,…)` → `resolveConflict(keepLocal)`；`pending/heldConflicts` 的 `HashMap<Int,…>` → 单条目；`conflictAuthorized` 集合 → 单标志；Event 四类去 `slot`；`heldConflictSlots()` → `hasHeldConflict()`。**队列节流语义零变更**（2s 去抖/同档留最新/60s 限频/指数退避/熔断/排空逐项保留，Q1–Q13 单测全绿为证） |
| **跨槽下载坍缩**（验收③） | `CloudSaveCacheWriter.downloadIntoCache(sourceSlot, targetSlot)` → `downloadIntoCache()`；`Outcome.Written.targetSlot` 删除；`convergeLedger` 去跨槽分支（恒 adoptCloudState 收敛）。`SaveLoadViewModelCloudSlotOps.loadCloudSlot(slot)` → `loadCloudSave()`（内部 `performCloudSlotLoad` 同步无参化） |
| **接口面退役** | `SaveBackend.list()` / `delete(slot)` / `CloudSaveEntry` / `CloudSaveSummary` / `TapTapSaveBackend.parseSummary` 整链删除——SR-3 槽位列表 UI 已随 SS4 退役，全仓零生产消费（grep 取证在案） |
| **常量与锁命名**（验收②） | `StorageConstants.CLOUD_SAVE_SLOT` 删除；`SlotLockManager` → `SaveLockManager`（类+文件+DI provide 函数+引用 3 处+测试，sed 全量改名零残留） |
| **冲突事件** | `SaveConflictEvent.slot` 字段删除（`CloudConflictDialog` 消费面零使用，取证在案） |

### ② 换设备续玩链路修通（验收①）

**途中发现的行为缺陷（本批修复）**：`GameActivity` 的 `isCloudSaveLoad` 分支原先走 `loadFromCloudSave()`——`TapCloudSaveManager` 云会话独立加载，**下载只进内存不落盘**，且不收敛 `UploadLedger`。后果：换设备续玩后本地从未落盘 + 账本空（L=0,C=0）而云端 W>0，本机第一次保存上传时仲裁必判 CONFLICT 挂起；进程重启后内存进度丢失。

修复：GameActivity 云分支改走 `loadCloudSave()` → `CloudSaveCacheWriter.downloadIntoCache()`（下载 → 校验 → **落本地缓存** → 账本 adoptCloudState(W) 收敛 → boot 链）。`loadFromCloudSave()` + `performCloudLoad()` + `handleCloudLoadSuccess()` 随零消费退役。判定链（`AutoEntryResolver` 本地无档+云有档 → LoadCloud）SS4 已修，本批零改动、既有用例覆盖。

### ③ W>C 防覆盖护栏（验收④/⑥/D-5 硬门）

- `UploadQueue.conflictGate` 由「仅 CONFLICT 挂起」改**白名单放行**：只有 `IN_SYNC`/`UPLOAD_PENDING` 放行，`CONFLICT`（L>C 且 W>C 且 W≠L，双端各有新进度）与 `LOCAL_BEHIND`（L==C 且 W>C，云端有本机之外的新进度）一律挂起 + `ConflictHeld` 事件（玩家弹窗二选一，显式授权 ≠ 静默覆盖）。上传前比对云端 W 走既有 `currentCloudSaveId()` 面（SS6 链路零改动）。
- **守卫测试锁定降级路径无写调用**（UploadQueueTest 新增三例）：LOCAL_BEHIND 挂起时断言 `uploadCount==0` + 账本 L/C 不被改写；降级后玩家选云 = 基线收敛且零上传；玩家选本地 = 显式授权后放行。
- `SaveArbiter` 纯函数判定表**零变更**（U1–U11 与 Q6 拍板一致），`SaveArbiterTest` 零改动——三臂语义复核结论：LOCAL_BEHIND=只读拉取臂（下载路径放行 + 上传路径挂起）、CONFLICT=弹窗二选一臂、IN_SYNC/UPLOAD_PENDING=直过/重传臂，与「单档+不支持同时多设备」产品拍板自洽。

### ④ 双保险延续（D-4）

`isLegacyArchiveName` 扩覆盖 `mnzm_v2_slot_1..6`（`RETIRED_V2_SLOT_PREFIX` 仅作识别面保留）；`TapCloudSaveManager.oneTimeCleanup` 登录后尽力删除退役命名云档，现役 `mnzm_v2_save` 显式不命中。

### ⑤ 复用纪律（验收⑤）

`SaveArbiter`/`UploadLedger`/`UploadQueue` 全部复用零新建；SS6 事件触发入队链 `maybeEnqueueCloudUploadAfterLocalSave` 签名/时序/调用点零改动（仅函数体内 `enqueue` 实参随单键坍缩去槽参数——验收②的直接要求）；不碰 C++（零 cpp 文件改动）。

### ⑥ 文档面

SS4 登记的两文件历史表述随批清（`CloudSaveCacheWriter` KDoc 重写为当前职责；`SaveLoadViewModelCloudSlotOps` 文件头「slot_N 保留至 SS7」句删除）；`TapTapSaveBackend`/`TapCloudSaveManager` KDoc 单档化。活文档（knowledge-base/CODE_WIKI/architecture/threading-contract）grep 复核零过期引用（仅过程档案提及，按 V5 先例不回改）。

---

## 2. 验证（门禁实跑数值）

| 门禁 | 结果 |
|---|---|
| `compileReleaseKotlin` | BUILD SUCCESSFUL |
| JVM 单测（三模块 `--max-workers=1 --rerun-tasks`） | **core:data 702/0 · feature:game 968/0 · app 1016/0 = 2686/0/0** |
| `detekt`（:core:data :feature:game :app） | 全绿（途中 2 违规实修：`UnusedParameter`（process 去参）+ `MaxLineLength`（用例名缩短），零 baseline 新增） |
| `node scripts/check-agent-instructions.mjs` | EXIT=0（485 引用全绿） |
| C++/ctest/JNI | 不适用（零 C++ 触碰） |

复核性 grep：`V2_SLOT_PREFIX`/`slotFromArchiveName`/`archiveNameFor`/`CLOUD_SAVE_SLOT` 生产零残留（唯一保留 = `RETIRED_V2_SLOT_PREFIX` 退役识别面 + 测试断言）；`SlotLockManager`/`loadFromCloudSave`/`performCloudLoad` 全仓零残留；单键 API 全部调用形态已核对。

---

## 3. 旧用例处置表

| 文件 | 处置 | 说明 |
|---|---|---|
| `UploadLedgerTest` | 改断言 ×8、删 ×1 | 全用例去 slot 参数；「槽位隔离」用例随维度退役；U10 用例第二槽断言随删；Q6 键名更新为新单键 |
| `UploadQueueTest` | 改断言 ×14、**新增 ×3** | Q1–Q13/幂等闸全保留（队列语义零变更的证词）；新增 W>C 护栏三例（挂起零写/选云收敛/选本显式放行）；FakeBackend 同步单档签名 |
| `TapTapSaveBackendTest` | 改写 ×2、删 ×2、新增 ×1 | `archiveNameFor`/`slotFromArchiveName` 用例删（函数退役）；新增 `ARCHIVE_NAME` 单档名锚定；`isLegacyArchiveName` 用例扩 v2 槽命名 9 断言；`parseSummary` ×2 随函数删 |
| `CloudSaveCacheWriterTest` | 改断言 ×9、删 ×1 | 「跨槽迁移不抄序号」用例随跨槽语义退役（单档恒收敛）；UPLOAD_PENDING 文案更新（「此槽位」→「本机」）逐字锚定 |
| `SaveLoadViewModelCloudSlotLoadTest` → `SaveLoadViewModelCloudSaveLoadTest` | 改名 + 改断言 ×13 | 全用例单档化；落盘链/verdict 分流/LEGACY 门控/冲突收口锚定面全保留 |
| `SaveLoadViewModelLoadTest` | 改走等价入口 ×2、删 ×4 | v0 迁移与修复两例改走 `downloadFromCloudSave`（同管线 `handleCloudDownloadSuccess`）；「success path」「云会话槽位 0」两例删（前者与他例重复、后者槽位语义已退役）；「boot 进行中拒绝」删（同一 `isBootOperationBlocked` 守卫已由他例+CloudSaveLoadTest 锚定）；「isLoading 置位」删（由 CloudSaveLoadTest Success 链 + downloadFromCloudSave 用例覆盖） |
| `SaveLoadViewModelAutoSaveTest` | 改断言 ×2 | enqueue mock 校验两参签名（SS6 LEGACY 零入队/CLOUD_TRANSITION 入队断言语义不变） |
| `SlotLockManagerTest` → `SaveLockManagerTest` | 改名 | 断言零改动 |
| `SaveArbiterTest` / `SaveBackendModeTest` / `AutoEntryResolverTest` | **零改动** | 语义未变的直接证据 |

---

## 4. 风险与登记

### 4.1 slot_N 旧云档失联受影响面（任务书 §6.3，与 SS0 W1 双保险口径一致）

- **受影响面**：v2 槽位命名时代（SS0 落地后至本批前的测试期）曾上传到 `mnzm_v2_slot_1..6` 的云档，本批后不再被任何读路径识别（`slotFromArchiveName` 已删），且被 `oneTimeCleanup` 登录后尽力删除。**4.2.00 尚未发布** ⇒ 不存在生产玩家的 v2 slot_N 档，受影响面 = 本仓库测试期自身，与 SS0 删档重置口径（W1：改命名 + 主动删除，命名失联为主保险）完全同构。
- 主保险 = 命名失联（读路径零识别）；辅助保险 = `isLegacyArchiveName` 扩覆盖后的 `oneTimeCleanup` 尽力删除（删除失败不构成恢复路径）。

### 4.2 UploadLedger 旧键残留清理策略（任务书 §6.4）

- 旧键 `cloud_upload_ledger_slot{0..6}_*`：**`SaveWipeCoordinator.executeWipe` 既有前缀清理已覆盖**——`CLOUD_LEDGER_KEY_PREFIXES = ["cloud_upload_ledger_", "cloud_migration_"]` 按前缀删除全部世代键，新单键 `cloud_upload_ledger_last_*` 同前缀继续被未来 wipe 覆盖（`SaveWipeCoordinatorTest` 在册）。无需迁移逻辑、无新增清理代码。
- **已核实**：4.2.00 未发布 ⇒ 不存在携带 `slot{N}` 键的已发布升级设备；首个 4.2.00 启动即触发 wipe 清零。故本批键名切换零残留风险。

### 4.3 已核实的行为面

- `SaveConflictEvent.slot` 删除安全：`CloudConflictDialog` 仅消费 `source/lastLocalSaveId/lastConfirmedCloudId/cloudSaveId`（grep 取证）。
- `saveBackend.list()/delete()` 零生产消费（SR-3 选槽 UI 已随 SS4 退役）；云端退役档删除能力保留在 `TapCloudSaveManager.oneTimeCleanup`（走 `CloudSaveApi` 层），删档「云端也清」铁律 18 不受影响。
- `SaveWipeCoordinator` 零改动：前缀清理天然覆盖新键。

### 4.4 推测/登记（需后续批或真机确认）

1. **真机 pending-device**（任务书 §6.2）：换设备续玩链路（新设备登录 → AutoEntry 云分支 → 落盘 boot）与 W>C 场景需真机双设备验证；JVM 面以 stub 后端覆盖（CloudSaveLoadTest 13 例 + UploadQueueTest 护栏三例）。
2. **SS10 跨批登记**：玩家可见变更（换设备续玩 + W>C 提示文案）并入 4.2.00 唯一条目。
3. **多设备禁令下的边缘窗口**（推测，产品拍板容忍）：两台违规同时在线的设备保存序号可能撞车（W==L），仲裁按 U9 判 UPLOAD_PENDING 放行——「不支持同时多设备」（Q6 拍板）下该场景属违规使用，CONFLICT 弹窗已是主防线，不另设防。
4. **途中发现（不属本批验收，登记不动）**：游戏内云会话入口（`CloudSaveDialog` 云下载 / `SettingsTab` 云下载卡）仍走 `TapCloudSaveManager` 内存加载不落盘——玩家拉取后不触发本地保存即退出则进度不落盘。属历史语义（显式预览动作），换设备续玩正规链路（AutoEntry 自动 + 本批落盘链）已绕开；建议 SS10 收口时评估是否统一改走落盘链。
5. **LOCAL_BEHIND 上传臂的可达性**：结构上需显式 `saveId` 入队且账本净（L==C）才可达（生产配方 `recordLocalSave` 派发恒 L>C）——护栏按纵深防御实现（白名单挂起），守卫测试经显式 saveId 构造覆盖，不依赖「不可达」论证。

---

## 5. 一句话总结

**云侧最后的槽位残留已坍缩为单键：`mnzm_v2_slot_N` 族与 `downloadIntoCache` 跨槽签名全部退役、`UploadLedger`/`UploadQueue` 单键化（节流语义零变更，Q1–Q13 全绿）、换设备续玩链路改走落盘链并收敛账本基线、W>C 只读降级护栏白名单化并以守卫测试锁定降级路径零写调用——复用既有仲裁/队列/上传链，零新建云通道，零 C++ 触碰。**
