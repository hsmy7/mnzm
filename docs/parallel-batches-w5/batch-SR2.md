# SR-2 SaveBackend + 上传队列 + 脏标志仲裁 + 重开预存施工卡（core 面）

> 立卡：2026-09-22（SR 看护 03:24 派发，新建 ZCode 子会话实施；本会话 = 实施会话）。
> 权威依据 = `docs/save-system-refactor-plan-2026-09-21.md` §4「SR-2」+ §2 目标架构 + §0 决策
> D3/D4 + 不变量 IN1/IN2/IN3/IN6；SR-0 侦察结论 = `docs/sr0-recon-report-2026-09-21.md`
> （仲裁单测清单 11+10 例 + 双设备剧本 S1-S10 = 现成用例基座；TapTap 限额 10MB/100 档/
> 1min 共享冷却；CloudSaveApi 抽象与反射桥复用不重写）。**方案文档 untracked，直读工作区，不自行提交**。
> 编排台账 = `dispatch-ledger.md` SR 系列批次总表。
> 纪律：本会话只做 SR-2 一批，不开下一批；**SaveBackendMode 默认 LEGACY 行为零变化是硬红线**（配守卫测试）；
> 迁移链历史基线零触碰；每子项独立 commit；不得自行登记 `accepted`（归用户）。

---

## 0. 定位

联网化三步（LEGACY → CLOUD_TRANSITION → CLOUD_ONLY）的**基建批**：接口与机制全部落地、
默认 LEGACY 全链惰性，切换动作归 SR-3/SR-6。四件事：

1. **D4 接口隔离**：`SaveBackend` 接口（upload/download/list/delete + observeConflict）落 core:data，
   `TapTapSaveBackend` 在 feature/game 包装现有 `CloudSaveApi` 反射桥（SDK 探测逻辑复用不重写）；
2. **D3 双步保存的第二步**：`UploadQueue`（协程 + MMKV 落地记账 + 指数退避 + 窗口合并 +
   TapTap 1 次/分钟共享冷却适配）；
3. **IN2 仲裁无时钟**：脏标志仲裁（lastLocalSaveId > lastConfirmedCloudId）整体替换时钟/版本串
   在"谁新"判定中的角色，真冲突显式暴露给 UI 层；
4. **审计 §2 重开顺序缺陷**：`SaveLoadViewModelRestartOps` 重置引擎前先保护性预存当前态。

**门**：仲裁纯函数单测（SR-0 清单 U1-U11）+ 队列状态机单测（Q1-Q10）+ 六模块组合门 +
`Diff*` 对拍 0 skip（IN8）+ LEGACY 零行为变化守卫 + 桌面 ctest 照跑。

## 1. 关键勘察结论（实施前提，2026-09-22 直读代码确认）

1. **反射桥可整体搬移**：`CloudSaveApi` 接口 / `CloudSaveApiReflector` / `ReflectiveCloudSaveApi` /
   `ArchiveEntry` / `CloudSaveRawInfo` / `CloudSaveOperationTimeoutException` 全部是
   `TapCloudSaveManager` 内部私有声明，无 SDK 编译期类型（纯反射）。整体提取为同包 internal
   顶层文件（`CloudSaveApiBridge.kt`）= 纯代码搬移，`TapCloudSaveManager` 改 import 即可，
   `TapTapSaveBackend`（同模块）得以在其上建槽位语义——SDK 探测逻辑零重写。
2. **"mtime-对-挂钟"确切落点**：`SaveLoadViewModelCloudOps.performCloudUpload` 成功分支
   `lastModifiedTime = System.currentTimeMillis()`（挂钟）写入本地缓存，此后
   `resolveCloudSaveInfo` 规则 5 用它与云端 mtime 比大小定摘要取舍——时钟偏移即长期误判
   （审计 §12-I）。修复 = 规则 5 弃时钟改脏标志（localDirty → 缓存；净 → 云 API），
   `lastModifiedTime` 降级为纯展示字段。
3. **版本串比较 = 兼容闸而非进度仲裁**（SR-0 §4.3C 预授权 SR-2 拍板）：`arbitrateCloudVersion`
   只拒"云端 App 版本 > 当前版本"的**不可加载**档，判据是 App 版本不是进度新旧，
   与 IN2 相容。本批**保留为兼容闸**（删除它 = LEGACY 下载路径行为变化，违反硬红线；
   且失去"版本不兼容"如实提示，退化为含混的"存档数据异常"），KDoc 与单测改注兼容闸身份；
   进度"谁新"判定完全交给新脏标志仲裁器。拍板理由入完成报告。
4. **重开缺陷精确位置**：`performRestartGame` 先 `restartEngineAndReseed`（:111 引擎重置，
   内存态覆写）后 `performRestartSave`（:115 新档落盘）——旧态在重置前确无保护。
   修复 = 重置前调 `performRestartSave`（快照/邮件/落盘/超时/自愈链复用，零新保存逻辑），
   **预存失败 = 中止重置**（此时旧档仅在盘上，继续重置将覆写唯一副本；中止点引擎未动，
   用户可直接重试）。
5. **LEGACY 红线的机制面保障**：队列惰性启动（无入队即无协程活动）+ 触发点以
   `shouldEnqueueCloudUpload(mode)` 纯函数门控（LEGACY 短路）+ 模式默认 LEGACY 守卫测试
   + 组合门全量回归。`SaveOrchestrator` 不在本批（归 SR-4 触发面）。
6. **TapTap 限额适配**（SR-0 §2）：1 次/分钟为创建+更新**共享冷却**且按用户计（多档共用）⇒
   队列全局单飞 worker + 每次上传后全局冷却间隔（参数化，默认 60s 保守口径）；400001/400006/
   400007 分类退避重试，400000/400003/400004/400005/400009 构造/配额类缺陷熔断+如实告警。
   错误码经反射桥异常 message 携带（`[400001]`），TapTapSaveBackend 解析为类型化错误。
7. **IN3 守卫可静态化**：feature/game 已有 konsist 依赖与目录扫描先例
   （`ViewModelArchitectureTest`）；core:data 测试 classpath 无 tap-cloudsave 依赖，
   `Class.forName` 负向断言即类路径级守卫。两者叠加 = 双层静态守卫。
8. **Room 2.7.0 嵌套事务警示遵守**（SR-0 §5）：本批不新增嵌套事务，不依赖吞内层异常做部分提交。
9. **detekt 阈值**（上两批触发过）：TooManyFunctions file=15/class=20、LongMethod=60——
   新机制按小文件多类切分；TapCloudSaveManager 只动 import 与两处 KDoc/签名，函数数不增。

## 2. 子项分解与 commit 切分

| # | 子项 | 内容 | commit |
|---|---|---|---|
| C1 | 施工卡 + 台账 | 本卡 + 台账 SR-2 行证据补记 + 监控日志一行 | 1 笔 |
| C2 | 桥提取 | `CloudSaveApiBridge.kt`（反射桥 6 成员整体搬移，internal，零逻辑变化）+ `TapCloudSaveManager` 改 import；行为零变化由既有 TapCloudSaveManagerTest + 组合门兜底 | 1 笔 |
| C3 | core:data cloud 面 | `SaveBackend` 接口 + 结果/条目/冲突类型（零 SDK 类型）+ `SaveBackendMode` 三态 + provider（默认 LEGACY）+ `UploadLedger`（MMKV per-slot 记账：lastLocalSaveId/lastConfirmedCloudId/pending + U10 自愈）+ `SaveArbiter.arbitrate` 纯函数（U1-U11）+ 单测（11 例 + ledger + mode + IN3 双层守卫） | 1 笔 |
| C4 | 队列 + 后端 | `UploadQueue`（单飞 worker + 惰性启动 + 窗口合并 + 指数退避 + 共享冷却 + 冲突挂起 Q10 + 熔断 Q9 + 幂等重传）+ `TapTapSaveBackend`（slot_N 门面 + extra JSON saveId + 错误码分类 + 冲突流）+ DI 绑定 + 队列状态机单测（Q1-Q10）+ 后端纯映射单测 | 1 笔 |
| C5 | 重开预存 | `performRestartGame` 重置前保护性预存（复用 `performRestartSave` 全链）+ 失败中止重置（旧档保留 + 如实提示） | 1 笔 |
| C6 | 仲裁去时钟 + 如实告警 | `resolveCloudSaveInfo` 规则 5 弃 mtime 改脏标志（`TapCloudSaveManager` 注入 `UploadLedger`，签名加 `localDirty`）+ 单测改写 + `compareVersions`/`arbitrateCloudVersion` 改注兼容闸（行为不变）+ 本地保存成功后模式门控入队（LEGACY 短路）+ 队列失败事件经 show* 通道如实呈现 + `shouldEnqueueCloudUpload` 纯函数守卫测试 | 1 笔 |
| C7 | 门禁 | 六模块组合门 + Diff* 0 skip + ctest + 判绿核验（XML executed 计数+时间戳） | 结果入完成报告 |
| C8 | 收尾 | 完成报告 `report-SR2-completion-2026-09-22.md` + 台账 SR-2 行 delivered + CHANGELOG | 1 笔 |

## 3. 红线复述（实施中逐条对照）

- **IN1** 原子性：本地事务在前必成，上传失败只降级不回滚本地；
- **IN2** 仲裁无时钟：进度"谁新"只看保存序号/脏标志；兼容闸（App 版本）不判进度新旧；
- **IN3** 接口隔离：core:data/feature 面零 TapTap SDK 类型引用（konsist 扫描 + core:data
  classpath 负向断言双守卫）；
- **IN6** 机制要有调用者+测试：队列生产调用点 = 非 LEGACY 保存路径（本批落地），
  `SaveBackend` 消费者 = UploadQueue/TapTapSaveBackend（本批落地）；
- **LEGACY 硬红线**：默认 LEGACY 下——队列零活动、触发点短路、UI 零新增提示、
  既有云上传/下载/摘要行为逐字节保持（C2/C6 搬移面由既有测试兜底）；
- **IN8**：Diff* 0 skip；**迁移链**：零触碰（本批无 wire/schema 变更）。

## 4. 交付物清单

- `android/core/data/src/main/java/com/xianxia/sect/data/cloud/`（接口/模式/账本/仲裁/队列）
- `android/feature/game/src/main/java/com/xianxia/sect/taptap/CloudSaveApiBridge.kt`、
  `TapTapSaveBackend.kt`、DI 绑定
- `SaveLoadViewModelRestartOps.kt` 预存修正、`SaveLoadViewModelCloudUploadHooks.kt` 触发钩子、
  `TapCloudSaveManager.kt`（import + resolveCloudSaveInfo 去时钟 + KDoc）
- 测试：`SaveArbiterTest`（U1-U11）/`UploadQueueTest`（Q1-Q10）/`UploadLedgerTest`/
  `SaveBackendModeTest`/IN3 守卫（konsist + classpath）/`TapCloudSaveManagerTest` 改写段
- 文档：完成报告 + 台账登记
