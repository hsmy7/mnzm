# SR-6 存量迁移引导施工卡（首启检测矩阵 + 迁移完成度记账 + CLOUD_TRANSITION 收口）

> 立卡：2026-09-22（用户口头「新开分支完成第六批」直接驱动；看护 cron 已于 SR-3 终局删除，
> 本会话 = 实施会话，非派发）。
> 权威依据 = `docs/save-system-refactor-plan-2026-09-21.md` §4「SR-6」+ §0 D1/D2/D3 + §2 模式开关
> 与读路径 + §3 IN1/IN2/IN3/IN6/IN7/IN8 + §5 门 1/2/3/6；前置资产 = SR-2（SaveBackend/UploadQueue/
> UploadLedger/SaveArbiter/三态开关）+ SR-3（云槽位下载落盘链 + 冲突弹窗 + 云槽位列表）+
> SR-4（月变/onStop 自动触发面 + `SaveOrchestrator`）+ SR-5（`WallClock` 族 + 载荷签名）。
> **方案文档 untracked，直读工作区，不自行提交**。
> 编排台账 = `dispatch-ledger.md` SR 系列批次总表（SR 行由实施会话自记，沿用 SR-0..SR-5 口径；
> `accepted` 归用户）。
> **并发纪律（本批立卡时的实况）**：SR-5 收官会话在同一工作树内跑组合门（daemon 28148，
> 13:38 起跑），HEAD 一度从 `122f43dea` 前进到 `04c4567a8`。同一工作树只有一个 HEAD——
> 在对方收官笔落库前 `git checkout -b` 会把对方的后续提交带进本批分支。故本批分支
> `w5/sr6-cloud-migration` **必须在 SR-5 收官笔落库后开启**，且实施期任一时刻只允许一路 gradle
> 在跑（classes.jar FileSystemException 先例）。
> 纪律：本会话只做 SR-6 一批，不开下一批；**LEGACY 模式下零行为变化红线延续**（迁移检测本身
> 是新增可见面，按 §4 条 5 单独登记）；每子项独立 commit、不夹带。

---

## 0. 定位

SR 系列的**存量迁移引导批**（方案 §4 SR-6，"CLOUD_TRANSITION 收口，砍文件层前的硬前置"）。四件事：

1. **首启检测矩阵**：冷启动时逐槽比对「本地有无档 × 云端有无档 × 迁移记账」，产出类型化动作
   （引导逐槽上传 / 冲突二选一 / 改用云端 / 损坏阻断 / 无需动作），方案 §4 的四格全覆盖；
2. **迁移完成度记账**（MMKV per-slot 上云确认标记）+ **升档门槛纯函数**：未完成迁移的设备
   不得推 `CLOUD_ONLY`（方案 §4 SR-6 硬红线，SR-7 的前置门）；
3. **迁移引导落地面**：主菜单迁移卡（逐槽排队进度，TapTap 1 次/分钟共享冷却下的真实节奏）+
   存量单档 `mnzm_cloud_save` 的归宿（SR-3 报告 §4.1 明确移交本批）+ 迁移完成后的
   **一次性「启用云存档」确认**（本批是 `SaveBackendModeProvider.set` 在 SR-2 就绪后的第一个
   生产调用点）；
4. **完成率指标定义**（运营侧可查，走埋点字典三处同步纪律）。

**门**：迁移矩阵纯函数单测（四格 × 幂等态全组合）+ 协调器状态机单测 + 门槛守卫测试 +
定向测试 + 六模块组合门 + `Diff*` 0 skip（IN8）+ 桌面 ctest + 真机全剧本（本环境不可测，
逐项 pending-device，不虚报）。

---

## 1. 关键勘察结论（2026-09-22 直读代码确认，行号以本卡立卡时 HEAD 为准）

1. **F1 🔴 `UploadLedger.pendingSaveId` 生产零消费者——"确认丢失续传配方"目前只是 KDoc**。
   全仓（排除测试）只有 `UploadLedger.kt:27` 的读点与 `:48` 的清零判断，**没有任何启动期
   "按待传指针重入队"的调用者**。SR-2 §2.4 与 Q6 单测声称的恢复配方在生产侧无人执行 ⇒
   既是 IN6（机制要有调用者）违例，也是真实耐久性缺口：进程在"入队后、确认前"被杀，
   该槽的脏标志会一直挂着，直到下次本地保存才被重新入队。
   ⇒ 本批落地续传（LEGACY 下待传指针恒 0 ⇒ 天然零动作，红线不破）。
2. **F2 `StorageFacade.load(slot)` 不返回邮件快照**：`SaveData.mails`（proto tag 56，SR-1）由
   **保存编排**从 `mails` 表注入（`StorageFacade.getMailsForSlot:195` →
   `StorageEngineMailOps.getMailsForSlot:30`），`StorageEngine.load`/`loadFromDatabase` 全程
   不填该字段（grep 实证）。⇒ 迁移侧若直接 `load(slot)` 后上传，云档邮件字段为**空表**，
   而云恢复侧是"整对象替换回表"（`StorageEngineMailOps.replaceMailsForSlot:40` 先删后写）⇒
   **换设备即丢邮件**，正是方案 §1.3 收编 SR-1 要防的事故。⇒ 迁移上传必须
   `load(slot).copy(mails = getMailsForSlot(slot))`，与保存编排同源。
3. **F3 slot 0 是"假槽位"**：`StorageEngine.getSaveSlots():487-498` 无条件插入
   `SaveSlot(slot=0, name="云存档", isEmpty=false)`。⇒ 枚举"本地有档的槽位"必须排除
   slot 0（SR-4 `MIN_AUTO_SAVE_SLOT=1` 同纪律），否则矩阵把云会话入口卡当成本地存量档。
4. **F4 存量单档 = slot 0 命名映射**：`TapTapSaveBackend.archiveNameFor:377` 把 slot 0 映射到
   `LEGACY_ARCHIVE_NAME = "mnzm_cloud_save":374`，`slotFromArchiveName:381` 又把它解回 slot 0 ⇒
   `SaveBackend.list()` **已经**会把存量单档作为 `slot=0` 条目返回（`list():286` 的
   mapNotNull 不过滤它）。SR-3 §4.1 明确"slot 0 云会话的落盘化归 SR-6 迁移矩阵一并处理"。
   ⇒ 本批消费该事实，不新增 SDK 面。
5. **F5 `performCloudSlotLoad` 的"源槽 = 目标槽"耦合**（`SaveLoadViewModelCloudSlotOps.kt:150-269`）：
   `download(slot)` → `handleCloudSlotPayload(slot, payload)` → `storageFacade.save(slot, ...)` →
   `adoptCloudState(slot, W)` → `applyCloudSaveToEngine(...)`。存量单档迁移需要
   **源 slot 0、目标空槽 N**，且主菜单场景**不应 boot 引擎**。
   ⇒ 抽出一个"下载→迁移/校验/堆叠重建→落缓存→账本收敛（不 boot）"的可复用段，
   SR-3 链 = 该段 + boot；迁移链 = 该段 + 选槽。这正是 SR-3 §5.2 登记的"~15 行有意重复、
   后续批收敛须以行为等价测试兜底"的正当收敛点（本批带等价测试）。
6. **F6 迁移上传需要引擎未加载态读档**：`StorageEngine.load` 走 `withReadLockLight(slot)` +
   读档熔断 + `handleDbDataHit`（`StorageEngineLoadOps.kt:32`）。后者有**真实副作用**：
   tombstone 命中时 `clearSlotDataQuietly(slot)`、`validateDbData` 可能走损坏恢复。
   ⇒ 迁移逐槽 `load` 与玩家亲手打开该槽的读档语义**完全一致**（不是新机制），如实登记；
   但"load 出来的物化/修复态"若不落盘，可能与 Room 现状不完全逐位一致 ⇒ 云快照取的是
   "玩家打开这个档会看到的态"，方向正确，登记为已知语义。
7. **F7 上传节奏与限频**：`UploadQueue.Config.debounceMs = 2_000`（`UploadQueue.kt:49`）而
   `sharedUploadCooldownMs = 60_000`（`:51`）；SR-4 实测游戏月 = 6 秒真实时间且用户拍板
   "月月必存"⇒ 非 LEGACY 下每 6 秒一次 enqueue。2 秒合并窗意味着**每分钟都会真的发一次上传**
   （60 秒内约 10 次保存、窗只合并 2 秒内的），靠共享冷却退避兜住——能收敛但持续吃 400001 退避。
   SR-4 报告 §8 已建议"把合并窗提到与冷却同量级（纯调参）"。本批是**上传真正在生产被打开**的
   批次，故纳入：`debounceMs` 与 `sharedUploadCooldownMs` 同量级（窗内恒取最新快照），
   `requestDrain()`（onStop）仍跳窗。**本地"月月必存"零改动**——这是 D3 的"限频适配"，
   不是对 D6 字面的降频，代价与理由在完成报告量化登记（不偷偷节流）。
8. **F8 模式写入面现状**：`SaveBackendModeProvider.set`（`SaveBackendMode.kt:36`）生产零调用
   （全仓 grep 实证）；纯函数门控只有 `shouldEnqueueCloudUpload:53`。主菜单 `MainActivity` 已注入
   provider 并只读 `current()`（`:145/:463/:753`）。⇒ 本批新增**第一个**生产写入点
   （见 §2 条 4 的玩家确认式升档），`CLOUD_ONLY` 仍零写入（归 SR-7 且受门槛守卫约束）。
9. **F9 埋点指标必须三处同步**：`rules/data-analytics.md` §1.2 + `AnalyticsEvents.kt`（事件名与
   属性键唯一真相源，`#` 前缀、snake_case、属性白名单、禁 PII）+ `docs/knowledge-base.md#事件字典`
   + 守卫测试 `AnalyticsEventsDictionaryTest`。`AnalyticsTracker`（core/domain）由 :app
   `CoreModule:276` 绑 TapDB 实现 ⇒ 可在 feature 注入。总开关 `TapDBConfig.analyticsEnabled`，
   埋点失败静默降级（不得进玩家主链路）。
10. **F10 可测性基建现成**：`InMemoryKeyValueStore`（core/data 测试）已被
    `UploadLedgerTest` / `SaveBackendModeTest` 使用 ⇒ 迁移账本与矩阵纯函数按同形态直测；
    `UploadQueueTest` 的"共享 TestScope 调度器 + 独立 Job + queueTest 包装器"模式沿用
    （SR-2 §5.2 教训：`backgroundScope` 不被 `advanceTimeBy` 推进）。
11. **F11 零 C++/零 wire/零 Room schema**：本批不碰 proto tag、不碰 Room 版本、不碰迁移链
    ⇒ ctest 预期 `ninja: no work to do.`，`Diff*` 必须仍 273 例 0 skip。
12. **F12 🔴 `SaveArbiter` U11 在迁移语境下会放行静默覆盖**：W 未知时按 `W==C` 保守重算 ⇒
    `L>C && W(null)<=C` ⇒ `UPLOAD_PENDING` ⇒ 队列**直接覆盖上传**。日常链路里 W 未知只可能
    来自 slot 0 存量单档（`TapCloudSaveManager.performCloudUpload:524` 写的 extra 无 `saveId`），
    但迁移是"第一次把本机档推上 slot_N"的通道，一旦云端 `slot_N` 存在无 `saveId` 的历史档
    就会静默覆盖另一台设备的进度（方案 §6 风险"双设备真冲突静默丢档 低/极高"）。
    ⇒ 迁移矩阵在 **planner 层**就把"云有 `slot_N` 档 && `saveId==null` && 本机 `C==0`"判为
    `RESOLVE_CONFLICT`（须玩家裁决），**不依赖**队列仲裁兜底；`SaveArbiter` 本体零改动
    （U11 语义在它的调用面仍是对的）。
13. **F13 主菜单是"Activity 重建式 setContent"，无 VM、无 `collectAsState` 先例**：
    `MainActivity` 注入的是 `saveBackend:141`/`saveBackendModeProvider:144` 等单例，
    **没有** `saveLoadViewModel`/`UploadQueue`/`UploadLedger`；选档页数据是
    `showSaveSelectScreen:620-627` 里 `loadSaveSlotsForSelect():718` + `queryCloudSlotEntries():752`
    的**一次性快照**（LEGACY 在 753-756 短路返空列表）。生命周期收集的现成范式只有
    `repeatOnLifecycle(RESUMED)`（`:346-350`）。⇒ 迁移协调器落 `feature/game/saveload`
    的 `@Singleton`，MainActivity 直注（`:app` 依赖 `:feature:game`，`build.gradle:190`），
    用 `remember { mutableStateOf }` + `repeatOnLifecycle` 收集其 `StateFlow` 后重建渲染。
14. **F14 `SaveSelectScreen` 增卡是纯增量**：形参全部带默认值（`:49-63`），生产构造点唯一
    （`MainActivity:642-688`），**无 `@Preview`**；`SaveSelectMode` 只有 `NEW_GAME`/`LOAD_SAVE`
    （`ui/model/SaveSelectMode.kt:4-7`）。迁移卡落 `SaveSelectContent`（`:166-202`）的
    `SaveSlotList(weight(1f))` 之上（`:188`/`:190` 之间），滚动列表外 ⇒ 进度不被卷走。
    进度组件先例：`dialogs/CloudSaveDialog.kt:232-247`（`CircularProgressIndicator`+文案）。
15. **F15 埋点字典守卫是硬编码镜像，不是解析 markdown**：
    `app/src/test/.../analytics/AnalyticsEventsDictionaryTest.kt:41-62` 用两个字面量 `setOf`
    断言一一对应 ⇒ 新增事件必须同步**四处**（`AnalyticsEvents` 常量 / 该测试三处集合 /
    `docs/knowledge-base.md:661-670` 表格 / TapDB 后台）。`TapDBManager.trackEvent:134-151`
    无初始化守卫、异常一律兜底不上抛（未初始化即静默降级）⇒ 迁移指标上报无需时序前置。

---

## 2. 设计

### 2.1 矩阵（方案 §4 SR-6 四格 + 幂等/损坏两格，`SaveMigrationPlanner` 纯函数）

输入（逐槽）：`localHasSave`（排除 slot 0，F3）/ `localLoadError` / `cloudEntry?`（F4）/
`migrationState`（MMKV 记账）/ `pendingSaveId > lastConfirmedCloudId`（F1）。

| 本地 | 云 | 已有迁移裁决 | 动作 |
|---|---|---|---|
| 有 | 无 | 无 | `UPLOAD_LOCAL`（引导上传；`reason = MIGRATE`）|
| 有 | 无 | 待传指针未确认 | `UPLOAD_LOCAL`（`reason = RESUME_PENDING`，F1 续传）|
| 有 | 有 | 已 UPLOADED / CLOUD_PREFERRED | `NOTHING`（幂等，不重复打扰）|
| 有 | 有 | 未裁决 | `RESOLVE_CONFLICT`（**禁止静默覆盖**，二选一）|
| 无 | 有 | 任意 | `USE_CLOUD`（"直接云档"：引导下载到本槽 = 落缓存，不 boot，F5）|
| 无 | 无 | 任意 | `NOTHING`（新游戏槽）|
| 损坏（isLoadError）| 任意 | 任意 | `BLOCKED_LOAD_ERROR`（不得当空档，`SaveSlot` 三态纪律）|

槽外单列：`slot 0 = mnzm_cloud_save` 云有档 ⇒ `MIGRATE_LEGACY_ARCHIVE`（下载到玩家选定空槽）。

派生判据（纯函数，方案 SR-6 硬红线）：
`canPromoteToCloudOnly(所有"本地有档"槽 ∈ {UPLOADED, CLOUD_PREFERRED} 且 无 UPLOAD_PENDING
待传) = true`；否则 false ⇒ SR-7 消费，本批加守卫测试锁死。

### 2.2 记账（`SaveMigrationLedger`，core:data/cloud，MMKV）

- key 风格沿用 `UploadLedger`（`cloud_upload_ledger_slotN_*`）⇒ `cloud_migration_slot{N}_state`、
  `cloud_migration_notice_shown`、`cloud_migration_promotion_confirmed`；
- `MigrationSlotState`：`NONE / QUEUED / UPLOADED / CLOUD_PREFERRED / SKIPPED`；
  未写入/非法值回落 `NONE`（`SaveBackendMode.fromStored` 同纪律，失败封闭）；
- **删档/清槽对齐** `UploadLedger.resetSlot` 与 `clearAllSlotTables` 清单纪律（删槽后迁移态一并清，
  否则残留 `UPLOADED` 会让 `canPromoteToCloudOnly` 说真话而事实无档）。

### 2.3 编排（`SaveMigrationCoordinator`，feature/game/saveload，@Singleton）

- `state: StateFlow<MigrationUiState>`（`IDLE / SCANNING / READY(待玩家开始) / RUNNING /
  DONE / PARTIAL_FAILED / SERVICE_UNAVAILABLE` + 逐槽行）；
- `scan()`：`storageFacade.getSaveSlotsSuspend()`（排除 slot 0）× `saveBackend.list()` ×
  `SaveMigrationLedger` × `UploadLedger` → `SaveMigrationPlanner.plan()`；
- `start()`：对每个 `UPLOAD_LOCAL` 槽 `load(slot)` + 邮件注入（F2）→ `uploadQueue.enqueue` →
  收集 `queue.events`：`UploadConfirmed ⇒ markUploaded` / `ConflictHeld ⇒ 行内二选一` /
  `UploadFailed(willRetry=false) ⇒ 行内失败文案`；对每个 `USE_CLOUD`/`MIGRATE_LEGACY_ARCHIVE`
  槽走 §2.1 的"下载→落缓存→账本收敛（不 boot）"共享段（F5）；
- 全部动作只在 `mode != CLOUD_ONLY`（文件层尚在）与 SDK 可用时执行；不可用时**如实显示**
  "云存档服务不可用（TapTap 未登录/SDK 缺失）"，不静默、不假装迁移完成（IN6/诚实红线）。

### 2.4 升档（本批唯一的模式写入点）

迁移矩阵内**所有需要动作的槽位收口后**，主菜单给一次「启用云存档」确认（`promotion_confirmed`
置位 ⇒ 只问一次）：玩家确认 ⇒ `saveBackendModeProvider.set(CLOUD_TRANSITION)`；
拒绝 ⇒ 保持 LEGACY，迁移卡下次仍可再入口（不反复弹窗骚扰）。
`CLOUD_ONLY` 生产零写入（SR-7 + §2.1 门槛守卫）。

### 2.5 指标（运营侧可查）

事件 `#save_migration_result`：属性白名单 `pending_total / migrated_total /
conflict_total / blocked_total / mode_after`（去标识化，零槽位内容、零 PII；
`blocked_total` = 损坏槽数，替代立卡初稿的 `skipped_total`——见 §2.6 差异注）。三处同步 F9。
指标定义写进完成报告：完成率 = `migrated_total / (migrated_total + pending_total)`，
按设备维度上报，TapDB 侧可聚合。

### 2.6 接口签名（**C2/C3 已按此落库，签名以代码为准**；后续子项实施时照此对齐）

```kotlin
// core/data/cloud/SaveMigrationLedger.kt —— 已落库（ccbd262c2）
enum class MigrationSlotState { NONE, QUEUED, UPLOADED, CLOUD_PREFERRED }  // 无 SKIPPED，见下注
val settled: Boolean; val migrated: Boolean      // QUEUED 两者皆 false：入队 ≠ 上云

@Singleton class SaveMigrationLedger @Inject constructor(store: KeyValueStore) {
    fun state(slot: Int): MigrationSlotState          // 未写入/非法值 → NONE（失败封闭）
    fun markQueued(slot: Int); fun markUploaded(slot: Int); fun markCloudPreferred(slot: Int)
    fun clearSlot(slot: Int)                          // 删档面，与 UploadLedger.resetSlot 同清单
    companion object { fun fromStored(raw: String?): MigrationSlotState }  // key: cloud_migration_slotN_state
}
```

**立卡后实施时删掉的两项（登记差异，防"记账面虚胖"）**：`SKIPPED` 态与
`noticeSeen` / `promotionConfirmed` 两个一次性标记。理由——① 不标记即保持 `NONE`，
下次冷启动矩阵照样列出，随手点掉一个"暂不"就把某槽永久排除在云唯一方向之外是净负债；
② 迁移引导落为**常驻卡**（S3）而非弹窗，弹窗才需要"只弹一次"的抑制位，常驻卡不需要，
留着就是 IN6 说的"没人用的机制"。升档确认因此只剩 `confirmEnableCloud()` 这一个动作面。

```kotlin
// core/data/cloud/SaveMigrationPlanner.kt —— 已落库（本笔）
data class SlotLedgerSnapshot(lastLocalSaveId: Long, lastConfirmedCloudId: Long, pendingSaveId: Long) {
    val hasUnconfirmedPending: Boolean      // F1 续传判据；EMPTY 常量供测试
}
data class SlotMigrationInput(slot: Int, localHasSave: Boolean, localLoadError: Boolean,
                              cloud: CloudSaveEntry?, migrationState: MigrationSlotState,
                              ledger: SlotLedgerSnapshot)
enum class UploadReason { MIGRATE, RESUME_PENDING }
enum class CloudConflictReason { BOTH_ADVANCED, UNVERIFIABLE_CLOUD_STATE }
sealed class SlotMigrationAction {
    data object NoAction
    data class UploadLocal(reason: UploadReason)
    data class ResolveConflict(reason: CloudConflictReason)
    data object UseCloud
    data object BlockedByLoadError
}
data class MigrationSlotPlan(slot: Int, action: SlotMigrationAction)
data class MigrationPlan(slots: List<MigrationSlotPlan>, legacyArchive: CloudSaveEntry?) {
    val uploadSlots / conflictSlots / cloudSlots / blockedSlots: List<Int>
    val actionableCount: Int; val requiresPlayerDecision: Boolean
}
object SaveMigrationPlanner {
    fun plan(inputs: List<SlotMigrationInput>, legacyArchive: CloudSaveEntry? = null): MigrationPlan
    fun decide(input: SlotMigrationInput): SlotMigrationAction
    fun canPromoteToCloudOnly(inputs: List<SlotMigrationInput>): Boolean
}
```

判定顺序（逐槽，短路自上而下）：`localLoadError → BlockedByLoadError` ⇒
`pendingSaveId > lastConfirmedCloudId → UploadLocal(RESUME_PENDING)`（F1，优先于"已裁决"幂等）⇒
`state.settled → NoAction` ⇒
`!localHasSave → (cloud == null ? NoAction : UseCloud)` ⇒
`localHasSave && cloud == null → UploadLocal(MIGRATE)` ⇒
`cloud.saveId == null || ledger.lastConfirmedCloudId == 0L → ResolveConflict(UNVERIFIABLE_CLOUD_STATE)`
（F12 前置守卫）⇒ 其余交 `SaveArbiter`：`CONFLICT → ResolveConflict(BOTH_ADVANCED)`、
`LOCAL_BEHIND → UseCloud`、`UPLOAD_PENDING → UploadLocal(MIGRATE)`、`IN_SYNC → NoAction`。

`canPromoteToCloudOnly` = 「无损坏槽 && 无未确认待传」且「所有 `localHasSave` 槽
`migrationState.migrated`」——本地一槽无档时**真空成立**即 true（全新设备升档不需要迁移前置，
迁移卡的可见性另有 `actionableCount` 判据，两者不混用）。

```kotlin
// feature/game/ui/game/saveload/SaveMigrationCoordinator.kt（@Singleton，MainActivity 直注，F13）
enum class MigrationPhase { IDLE, SCANNING, READY, RUNNING, DONE, PARTIAL_FAILED, SERVICE_UNAVAILABLE }
data class MigrationSlotRow(val slot: Int, val label: String, val actionText: String, val failed: Boolean)
data class MigrationUiState(
    val phase: MigrationPhase, val rows: List<MigrationSlotRow>,
    val pendingTotal: Int, val migratedTotal: Int,
    val awaitingDecisionSlot: Int?,       // 行内二选一
    val canEnableCloud: Boolean           // §2.4 一次性「启用云存档」
)
class SaveMigrationCoordinator @Inject constructor(...) {
    val state: StateFlow<MigrationUiState>
    suspend fun scan(); suspend fun start(); suspend fun resolveConflict(slot: Int, keepLocal: Boolean)
    suspend fun downloadToCache(sourceSlot: Int, targetSlot: Int)   // F5 共享段
    fun confirmEnableCloud()                                        // 唯一 set(CLOUD_TRANSITION) 点
}
```

---

## 3. 子项分解与 commit 切分

| # | 子项 | 内容 | commit |
|---|---|---|---|
| C1 | 施工卡 + 台账 | 本卡 + 台账 SR-6 行 in_progress（实施会话自记；**写前重读目标段**，SR-5 会话可能并发改同一文件） | 1 笔 |
| C2 | 迁移记账 | `SaveMigrationLedger`（MMKV）+ `MigrationSlotState` + 非法值回落 + 与 `UploadLedger.resetSlot` 的清槽对齐；单测 | 1 笔 |
| C3 | 矩阵纯函数 | `SaveMigrationPlanner`（输入/动作/派生判据 + `canPromoteToCloudOnly`）+ 四格×幂等×损坏全组合单测（**方案门**） | 1 笔 |
| C4 | 下载落盘段收敛 | 从 SR-3 `handleCloudSlotPayload` 抽出"下载→管线→落缓存→账本收敛（不 boot）"共享段，SR-3 链改为"该段 + boot"，行为等价测试兜底（SR-3 §5.2 授权收敛） | 1 笔 |
| C5 | 待传续传 | 冷启动按 `pendingSaveId > lastConfirmedCloudId` 重入队（F1 缺口根治，LEGACY 天然零动作）+ 单测 | 1 笔 |
| C6 | 协调器 | `SaveMigrationCoordinator`（scan/start/events 收口/服务不可用如实）+ 状态机单测（含邮件注入断言 F2、slot 0 排除断言 F3） | 1 笔 |
| C7 | 上传节奏 | `Config.debounceMs` 与共享冷却同量级（F7）+ 参数守卫单测 + 理由注释；`requestDrain` 跳窗行为不变 | 1 笔 |
| C8 | 引导 UI | 主菜单迁移卡（逐槽行 + 排队进度 + 冲突二选一 + 失败如实）+ 存量单档选槽下载 + 「启用云存档」一次性确认（§2.4）；`SaveSelectScreen`/`MainActivity` 接线 | 1 笔（过大则拆 卡/接线 2 笔） |
| C9 | 指标 | `#save_migration_result` 三处同步（常量 + 事件字典 + 守卫测试）+ 上报点（非热路径、失败静默降级） | 1 笔 |
| C10 | 门槛守卫 | `SaveMigrationGuardTest`：未迁完不得 `CLOUD_ONLY`（纯函数面）+ 生产 `set(CLOUD_ONLY)` 零调用（源面扫描）+ 迁移族文件零裸墙钟（IN2） | 1 笔 |
| C11 | 门禁 | 定向测试 + 六模块组合门（XML executed 计数 + 时间戳判绿，基线 SR-4 收官 7,986/0/17 与 SR-5 增量）+ `Diff*` 0 skip + ctest（预期 no work to do）；**等 SR-5 那路 gradle 收工后再起** | 结果入完成报告 |
| C12 | 真机登记 | 双设备迁移剧本 + 限频真实表现 + 迁移卡渲染目视 | 完成报告 pending-device |
| C13 | 收尾 | 完成报告 `report-SR6-completion-2026-09-22.md` + 台账 SR-6 行 delivered + CHANGELOG 4.01.15 段 SR-6 小节（含玩家可见变化）+ 方案 §4 SR-6 实施补记 | 1 笔 |

---

## 4. 红线复述（实施中逐条对照）

- **LEGACY 零行为变化**：迁移**检测**是新增可见面（本批主题本身，无法对 LEGACY 隐藏——
  存量玩家正是在 LEGACY 下才有未上云的档），但 LEGACY 下：队列零入队零上传、不自动改模式、
  不自动下载覆盖；所有网络动作都由玩家显式点「开始迁移」触发 ⇒ 完成后报告 §4 量化登记；
- **IN2 仲裁无时钟**：矩阵输入只有"有没有档/序号/记账态"，零 `System.currentTimeMillis`、
  零 mtime 比较（守卫锁死，C10）；
- **禁止静默覆盖**（方案 §6 风险"双设备真冲突静默丢档 低/极高"）：本地有 × 云有 一律二选一，
  两选项后果文案写明"留谁/丢什么"；「改用云端」必须显式确认"本机该槽进度将被云端版本覆盖"；
- **IN1 原子性**：迁移的本地读失败 = 该槽如实标失败，不影响其他槽、不回滚任何东西；
  上传只降级（队列语义）；
- **IN3 接口隔离**：协调器/UI 只依赖 `SaveBackend` 接口，零 SDK 类型（既有 konsist 守卫覆盖）；
- **IN5 尺寸红线**：迁移复用既有上传路径 ⇒ 10MB 判定在 `TapTapSaveBackend.upload:96`，
  超限如实 `SIZE_LIMIT` 上抛到迁移卡行内，不静默跳过；
- **IN6 机制要有调用者 + 测试**：F1（待传指针）本批必须有启动期调用者，否则不落地；
- **IN7 boot 只读不写**：迁移段不 boot、不改引擎态；
- **IN8**：`Diff*` 0 skip；零 C++/wire/schema；
- **不夹带**：SR-5 §8 的三条遗留（`StorageEngine:232` 时间戳盖写族 / `RequestSigner` 缓存数组
  `fill(0)` 疑污 / `validateCodeWithServerAuth` 零调用者）与 SR-3 §8（云档删除 UI、
  `aiSectDisciples` 拍板）**一律不碰**，完成后按 rule 12 复述给用户；
- **并发**：不编辑 SR-5 会话正在改的文件（`WallClockReflowGuardTest`、`MailDialog`、
  `GameViewModel`、`report-SR5-*`）；台账写入前重读目标段。

---

## 5. 真机硬门清单（本环境不可自动化，逐项 pending-device）

1. 双设备迁移剧本：A 设备 6 槽存量档 → 迁移 → B 设备首启矩阵应判 `USE_CLOUD`（本地无×云有）；
2. 逐槽上传在 TapTap 1 次/分钟共享冷却下的真实耗时与 400001/退避表现（6 槽 ≥6 分钟）；
3. 迁移卡 Compose 渲染与进度刷新（本环境无设备农场，单测只证数据链路）；
4. 存量单档 `mnzm_cloud_save` → 空槽落盘 → 再上云为 `slot_N` 的真实往返（含列表最终一致性窗口）；
5. 冲突行内二选一端到端（保留本机覆盖云端 / 改用云端覆盖本地缓存）后，进游戏的真实进度正确；
6. 「启用云存档」确认后月变自动存档开始入队，6 秒节奏 × 60 秒窗的实际上传频次与发热/耗电；
7. 迁移中断（进程被杀/断网）后重启的续传收敛（F1 路径真机验证）。

---

## 6. 交付物清单

- `android/core/data/.../data/cloud/SaveMigrationLedger.kt`（新）
- `android/core/data/.../data/cloud/SaveMigrationPlanner.kt`（新，纯函数 + 矩阵 + 门槛判据）
- `android/feature/game/.../ui/game/saveload/SaveMigrationCoordinator.kt`（新）
- `android/feature/game/.../ui/game/SaveLoadViewModelCloudSlotOps.kt`（C4 抽出共享段）
- `android/feature/game/.../taptap/TapTapSaveBackend.kt`（如需暴露"按名下载"最小面）
- `android/core/data/.../data/cloud/UploadQueue.kt`（C7 参数）+ `app/.../di/SaveBackendModule.kt`
- `android/feature/game/.../ui/game/SaveLoadViewModel*.kt`（C5/C6 接线）、
  `android/app/.../ui/MainActivity.kt`、`android/app/.../ui/SaveSelectScreen.kt`、
  新 `SaveMigrationCard`（C8）
- `android/core/domain/.../core/util/AnalyticsEvents.kt` + `docs/knowledge-base.md` 事件字典 +
  `AnalyticsEventsDictionaryTest`（C9）
- 测试：`SaveMigrationLedgerTest`、`SaveMigrationPlannerTest`、`SaveMigrationCoordinatorTest`、
  `SaveMigrationGuardTest`、C4 行为等价测试、`UploadQueueTest` 增例
- 文档：完成报告 + 台账 SR-6 行 + CHANGELOG + 方案 §4 SR-6 实施补记

---

## 7. 立卡时自定的三条口径（登记备用户改判）

用户指令是"完成第六批"，未逐条点口径；以下三条按方案字面 + 最小玩家风险取向自定，
验收轮如与预期不符可改判（本卡与完成报告双登记）：

| # | 议题 | 本卡口径 | 依据 |
|---|---|---|---|
| S1 | 模式升档是否真写 | **写**：迁移收口后一次性「启用云存档」确认，玩家点确认才 `set(CLOUD_TRANSITION)`；不静默升档、也不无限期悬置 | 方案 §2"SR-3/SR-6 逐级切换"字面归本批收口；SR-3 §8.6 又把时机移交用户 ⇒ "玩家显式确认"是唯一同时满足两者的实施形态 |
| S2 | 存量单档 `mnzm_cloud_save` | 作为"云有档"进矩阵：本地无档 → 引导下载到玩家选定空槽并落盘；本地有档 → 二选一；旧云会话 UI 迁移期并存，废除归 SR-7 | SR-3 §4.1 把 slot 0 落盘化移交本批；D5 文件层/旧链退役节奏属 SR-7 |
| S3 | 引导交互形态 | 主菜单迁移卡 + 逐槽排队进度（可退出、下次续） | 6 槽 × 1/min ⇒ ≥6 分钟，一次性弹窗看不见进度；SR-4 "常驻状态行而非 snackbar"同教训 |
