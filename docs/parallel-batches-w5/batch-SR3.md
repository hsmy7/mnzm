# SR-3 云主路径 + 槽位云化 + 初始化如实施工卡（产品可见面）

> 立卡：2026-09-22（SR 看护 06:14 派发，新建 ZCode 子会话实施；本会话 = 实施会话）。
> 权威依据 = `docs/save-system-refactor-plan-2026-09-21.md` §4「SR-3」+ §2 读路径/触发矩阵 +
> §5 门 5（真机硬门起算）；前置资产 = SR-2（SaveBackend/UploadQueue/SaveArbiter/三态开关）
> + SR-0 侦察（slot_N 多档可行 + 剧本 S1-S10 + 6 项待真机清单）。
> **方案文档 untracked，直读工作区，不自行提交**。
> 编排台账 = `dispatch-ledger.md` SR 系列批次总表（SR-3 行已由看护登记 in_progress）。
> 纪律：本会话只做 SR-3 一批，不开下一批；**LEGACY 模式行为零变化红线不变**；
> 迁移链历史基线零触碰；每子项独立 commit、不夹带；不得自行登记 `accepted`（归用户）。
> aiSectDisciples 拍板项 = 维持现役语义（@Transient 换设备重生成 = 现状）实施，登记 open，
> 不嵌入任何新决策；若实施中发现必须二选一的设计分叉，停在卡内登记等待用户。

---

## 0. 定位

云唯一化的**产品可见面批**：SR-2 基建（接口/队列/仲裁/三态开关）之上，把云主路径接到玩家
眼前。四件事：

1. **云下载落盘**（审计 §3/§12-I 修复面）：云档下载 → 校验 → 迁移 → **落本地缓存** →
   再走既有 boot 链——不再只进内存；
2. **冷启动云槽位列表**：`SaveBackend.list()` → slot_N 映射 + 摘要渲染；
   CLOUD_TRANSITION 下本地槽位 UI 并存（本地照常可用），CLOUD_ONLY 后才移除（归 SR-7 收口批）；
3. **MainActivity 初始化 3 次全败静默放行修正**（审计 §12-J）：如实阻断 + 重试 UI，
   文案模式感知；
4. **真冲突弹窗**（本地脏 × 云端有更新）：玩家二选一，选谁留档明确可见——
   SR-2 `SaveArbiter` conflicts 流 + `UploadQueue.resolveConflict` 数据面已备好，本批接 UI。

**门**：定向测试 + 六模块组合门 + `Diff*` 0 skip（IN8）+ LEGACY 守卫 + 桌面 ctest 照跑 +
**真机硬门**（§5.5：本环境无法自动化，逐项如实登记 pending-device，不虚报；代码交付后批次停
在 pending-device 状态等用户）。

## 1. 关键勘察结论（实施前提，2026-09-22 直读代码确认）

1. **LEGACY 云读档路径（`SaveLoadViewModelCloudLoadOps.handleCloudLoadSuccess` /
   `SaveLoadViewModelCloudOps.handleCloudDownloadSuccess`）不动**——审计"只进内存"缺陷的
   修复落在**新增模式门控链** `performCloudSlotLoad(slot)`（新文件
   `SaveLoadViewModelCloudSlotOps.kt`）：CLOUD_TRANSITION+ 生效，LEGACY 拒绝并提示
   （硬红线：默认模式下载/读档行为逐行保持）。方案 §2 读路径本身即标注"CLOUD_TRANSITION 起"。
2. **新链槽位语义**：slot_N 云档 → 落本地缓存槽 N（`storageFacade.save(N, processed)`）→
   `applyCloudSaveToEngine(processed, N)` boot（该函数现为云会话槽 0 专用，需把
   `pendingSlot` 参数化——默认值 0 = 既有调用行为零变化）。落缓存成功才 boot（缓存失败 =
   如实报错中止，不带病 boot）。
3. **账本基线收敛**：下载即采纳云端为基线（非新保存）——W 已知时
   `UploadLedger.adoptCloudState(slot, W)`；W 未知（存量档 U11）保持账本原状
   （L==C 净态不变；脏态会先被 verdict 拦截，见 4）。落缓存**不走** `recordLocalSave`
   （不制造假脏标志、不触发上传）。
4. **verdict 分流**（`CloudSavePayload.verdict`，后端已在 download 内仲裁）：
   `LOCAL_BEHIND`/`IN_SYNC` → 正常下载落盘；`UPLOAD_PENDING`（本机有未上传新进度、云端
   并不更新）→ **拒绝覆盖** + 如实提示（等队列自动上传后再加载）；`CONFLICT` → 后端返回
   Failure(CONFLICT) 并发事件，本链不报错不落盘，交冲突弹窗二选一。
5. **冲突双源**：下载侧 = `SaveBackend.conflicts` 流（`arbitrateAgainstCloud` 发出）；
   上传侧 = `UploadQueue.Event.ConflictHeld`（VM init 收集器现 `else -> {}` 占位，注释
   明言"弹窗归 SR-3"）。收口：下载侧 keepCloud = `adoptCloudState` 后重跑下载
   （仲裁转 IN_SYNC 通过），keepLocal = 仅关弹窗（云端不动）；上传侧 =
   `uploadQueue.resolveConflict(slot, keepLocal)`（keepLocal 授权越过冲突闸 /
   keepCloud 丢弃待传 + 基线收敛，SR-2 Q10 已实现）。
6. **list() 摘要富化落点**：SR-2 `TapTapSaveBackend.list()` 现返回 summary=null/saveId=null
   （注释明言"逐档摘要/序号查询归 SR-3"）。逐档 `queryArchiveInfo` = O(N×getArchiveList)
   往返浪费；反射桥 `ArchiveEntry` 只提 3 字段但 SDK `ArchiveData` 携带
   summary/extra/saveSize 十字段（SR-0 §3.1 AAR 核验）——扩 `ArchiveEntry` 增
   summary/extra/sizeBytes 三字段（listAllArchives 映射处一次性提取），
   `list()` 一次往返即得全部摘要。extra → `CloudSaveSummary` 提取为 internal 纯函数
   `parseSummary`（JVM 可测）。
7. **MainActivity 初始化静默放行确切位置**：`proceedAfterPrivacyConsent` 内
   `storageFacade.initialize()` 3 次重试全败后仅 `Log.e("proceeding with empty cache")` +
   `isLoadComplete = true`（`MainActivity.kt:414-423`）——ProgressRunnable 推进进度到 1f
   静默进主菜单。修复 = 提取 `initializeStorageWithRetry()`；全败置 `storageInitError`
   阻断态（进度动画停摆，onLoadingComplete 不触发），加载页切换为如实错误屏
   （上次真实错误 + 重试按钮 + 模式感知文案：LEGACY 点明"本地存档是唯一进度来源"，
   非 LEGACY 点明"落盘缓存不可写"）。§12-J 修复本身即行为变更（方案 §1.3 收编授权），
   非 LEGACY 红线违反（SR-2 重开预存同口径先例）。
8. **主菜单接线面**：`MainActivity.showSaveSelectScreen`（本地槽 + slot 0 旧云卡）→
   `SaveSelectScreen`；新增 `queryCloudSlotEntries()`（非 LEGACY 才查，失败降级空列表 +
   日志）与新 `onCloudSlotLoad(slot)` 回调 → `launchGame(cloudSlot = N)` → 新 intent extra
   `EXTRA_CLOUD_SLOT` → `GameActivity` 分发 `saveLoadViewModel.loadCloudSlot(N)`。
   NEW_GAME 模式不显示云槽位卡（新游戏=建本地档，CLOUD_TRANSITION 语义）。
9. **aiSectDisciples 现役语义**（SR-0 §1.2/§6.3 拍板项）：新链沿用同一 SaveData 管线，
   @Transient 字段照旧不进云档、换设备由 `ensureGameDataIntegrity` 重生成——**零代码
   变化即零语义变化**，拍板项维持 open 等用户，本批不实现 heavy 并入云档分支。
10. **detekt 阈值**（连续两批触发）：新逻辑全部落新文件小函数切分；
    `SaveLoadViewModel.kt` 只增 1 个 StateFlow + 1 个收集分支 + 1 个公开入口；
    `PersistenceFacade` 增 1 个构造参数（LongParameterList 已有豁免注）。
11. **Room 2.7.0 嵌套事务警示遵守**（SR-0 §5）：新链只调用 `storageFacade.save`
    既有事务链，不新增嵌套事务、不依赖吞异常做部分提交。
12. **测试基建先例**（SR-2 §5.2）：VM 测试的 SharedFlow 依赖必须桩真实
    MutableSharedFlow（relaxed mock collect 抛 KotlinNothingValueException）——
    本批 init 新增 `saveBackend.conflicts` 收集，`SaveLoadViewModelLoadTest` setUp 需同步补桩。

## 2. 子项分解与 commit 切分

| # | 子项 | 内容 | commit |
|---|---|---|---|
| C1 | 施工卡 + 台账 | 本卡 + 台账 SR-3 行证据补记（施工卡已立）+ 监控日志开跑条目 | 1 笔 |
| C2 | 云下载落盘 | 反射桥 `ArchiveEntry` 增 summary/extra/sizeBytes + `TapTapSaveBackend.list()` 富化 + `parseSummary` 纯函数 + `PersistenceFacade` 增 `saveBackend` + `SaveLoadViewModelCloudSlotOps.kt`（`loadCloudSlot` 入口守卫族 + `performCloudSlotLoad`：模式门控 → download → verdict 分流 → 迁移/校验/堆叠重建管线 → 落缓存 → 账本收敛 → `applyCloudSaveToEngine(pendingSlot 参数化)`）+ 单测（落盘/冲突短路/UPLOAD_PENDING 拒绝/LEGACY 拒绝/缓存失败中止/keepCloud 重载/keepLocal） | 1 笔 |
| C3 | 冷启动云槽位列表 | `MainActivity`：inject `SaveBackendModeProvider`+`SaveBackend`、`queryCloudSlotEntries()`、`launchGame(cloudSlot)`、`EXTRA_CLOUD_SLOT`；`SaveSelectScreen` 云槽位卡（LOAD_SAVE 模式渲染，摘要 = 年/月/宗门/弟子/灵石/保存时间）；`GameActivity` 分发 `loadCloudSlot`；测试 | 1 笔 |
| C4 | 初始化如实 | `initializeStorageWithRetry()` 提取 + `storageInitError` 阻断态 + `StorageInitErrorScreen`（错误 + 重试 + 模式感知文案，文案纯函数可测）+ 重试重跑初始化 | 1 笔 |
| C5 | 真冲突弹窗 | `SaveLoadViewModel`：`pendingCloudConflict` StateFlow + `saveBackend.conflicts`/`ConflictHeld` 双源收集 + `resolveCloudConflict(keepLocal)`（下载/上传两源分流收口）；`CloudConflictDialog`（两选项 + 选谁留谁/丢什么明确文案，模态不可点外关闭）；`GameContent` 渲染；测试（双源置态 + 两源收口 + 幂等清态） | 1 笔 |
| C6 | aiSectDisciples 登记 | 零代码：现役语义维持声明 + 拍板项 open 登记入完成报告（§0 纪律） | 随 C7 |
| C7 | 门禁 | 定向测试 + 六模块组合门（XML executed 计数+时间戳判绿）+ Diff* 0 skip + LEGACY 守卫 + ctest | 结果入完成报告 |
| C8 | 真机硬门登记 | 双设备剧本 S1-S10（SR-0 §4.2）+ 云链路真机项逐项 pending-device，不虚报 | 入完成报告 |
| C9 | 收尾 | 完成报告 `report-SR3-completion-2026-09-22.md` + 台账 SR-3 行 delivered（标注 pending-device 清单） | 1 笔 |

## 3. 红线复述（实施中逐条对照）

- **LEGACY 硬红线**：默认模式——旧云读档/云上传/摘要链零触碰（新文件隔离）；
  `loadCloudSlot` 模式门控拒绝；主菜单云槽位列表不查询（LEGACY 短路）；
  初始化阻断修复为审计收编的显式行为修正（唯一全模式行为变化，如实登记）；
- **IN2 仲裁无时钟**：新链零 mtime/挂钟比较；verdict 唯一来源 = `SaveArbiter`；
- **IN1 原子性**：落缓存失败中止 boot；账本收敛在落缓存成功后；上传侧收口沿用队列语义；
- **IN3 接口隔离**：新链零 SDK 类型（只依赖 `SaveBackend` 接口）；
- **IN7 boot 只读不写**：boot 链不触碰；落缓存发生在 boot 之前；
- **IN8**：Diff* 0 skip；**迁移链**：零触碰（本批无 wire/schema 变更）；
- **真冲突禁止静默覆盖**：CONFLICT 一律弹窗，双选项后果文案明确可见。

## 4. 真机硬门清单（本环境不可自动化，逐项 pending-device 登记）

- 双设备剧本 S1-S10 实跑（SR-0 §4.2 矩阵：净循环/净净交替/双端同脏/A脏×云新/A脏×云旧/
  确认丢失幂等/下载中断/冷启动首接/双无/月变冲突联动）；
- 云槽位列表真机验证（SR-0 §3.3 六项：多档服务端行为/列表最终一致性/限频粒度/命名校验/
  回调可靠性/大档传输）；
- 冷启动云槽位下载→落盘→boot 全链真机；
- 初始化失败阻断屏真机（清数据/制造 DB 损坏场景）。

## 5. 交付物清单

- `android/feature/game/src/main/java/com/xianxia/sect/taptap/`：`CloudSaveApiBridge.kt`
  （ArchiveEntry 扩字段）、`TapTapSaveBackend.kt`（list 富化 + parseSummary）
- `android/feature/game/src/main/java/com/xianxia/sect/ui/game/`：
  `SaveLoadViewModelCloudSlotOps.kt`（新）、`SaveLoadViewModel.kt`（冲突状态+入口）、
  `saveload/PersistenceFacade.kt`（+saveBackend）、`SaveLoadViewModelCloudLoadOps.kt`
  （applyCloudSaveToEngine pendingSlot 参数化，默认零变化）
- `android/app/src/main/java/com/xianxia/sect/ui/`：`MainActivity.kt`（云槽位列表 +
  初始化阻断 + launchGame）、`SaveSelectScreen.kt`（云槽位卡）、
  `StorageInitErrorScreen.kt`（新）、`StorageInitMessaging.kt`（新，纯函数）、
  `ui/game/GameActivity.kt`（分发 + 冲突弹窗渲染）、`ui/game/CloudConflictDialog.kt`（新）
- 测试：`SaveLoadViewModelCloudSlotLoadTest`（新）、`SaveLoadViewModelLoadTest`（补桩）、
  `TapTapSaveBackendTest`（parseSummary）、`SaveSlotDispatchTest`/app 侧新增
- 文档：完成报告 + 台账登记
