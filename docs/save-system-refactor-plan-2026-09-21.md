# 存档系统重构实施方案——云唯一存档 + 月度自动存档（Save Refactor / SR 系列）

> 日期：2026-09-21
> 依据：`docs/save-system-audit-2026-09-21.md`（全量勘察）+
> `docs/parallel-batches-w5/save-audit-16-remediation-2026-09-21.md`（§16 十项修复台账，**已全部入库**）+
> 用户 2026-09-21 会话拍板（见 §0 决策记录）。
> 性质：**实施方案（主计划）**。批次施工卡开工时按 `parallel-batches` 目录约定另立，
> `dispatch-ledger.md` 登记；本文档不替代施工卡。
> 纪律沿用现行口径：**存档 schema / wire 面变更单独走批**；每子项独立 commit；
> 不夹带；迁移链历史基线零触碰。

---

## 0. 决策记录（ADR，本方案的宪法）

| # | 决策 | 含义 | 来源 |
|---|---|---|---|
| D1 | **联网化方向，不保离线可玩** | 本地 Room 从"存档"降级为**会话缓存**：可丢弃，丢了=重下云档，不等于丢玩家进度 | 用户 2026-09-21 |
| D2 | **TapTap 云存档 = 唯一玩家可见存档** | 槽位概念搬到云端；本地槽位 UI、`.sav`/`.bak` 文件层最终废除 | 用户 2026-09-21 |
| D3 | **保存 = 双步结构**：本地事务（必成）→ 异步上传队列（重试/合并/退避） | 网络韧性问题，不是离线功能：月变那一刻断网不能丢整月 | 方案推演（TapTap 限频 1 次/分钟 + 单档 10MB 是硬约束） |
| D4 | **`SaveBackend` 接口隔离云 SDK** | 业务层永不直连 TapTap 类型；未来换自家游戏服务器只换实现 | 联网化预埋切换点 |
| D5 | **文件层整体废除**（含 2026-09-21 刚实施的真 `.bak` 轮转，`b35f1d9fb`） | 轮转备份在 CLOUD_TRANSITION 过渡期继续保护玩家，最终随文件层一起退役——**生命周期显式声明，不是返工** | D2 推论 |
| D6 | **自动存档 = 游戏月月变钩子 + `onStop`** | "每月一次"解读为**游戏内月**（月变钩子，3 旬/月；联网方向下墙钟月与进度无关）。`onStop` 保存基建已由 `870be9771` 落地（旗标默认关），本方案打开并接入云上传 | 用户 2026-09-21 |
| D7 | **保值资产投资倾斜**：SaveData 序列化核心 / saveVersion+migrator / 每旬 `exportDirty()` 差分管线 | 差分管线（`nativeSettlePhase → exportDirty → StateSyncService.mergeGameDataChanges`）是未来客户端→服务器同步协议的雏形，已存在且在跑；重构不得破坏其信封语义 | 战略判断 |

**明确不做**：离线可玩、本地多槽位产品概念、本地文件轮转备份的长期维护、
client 墙钟信任（过渡期以接口化收敛，见 SR-5）。

---

## 1. 基线校准（2026-09-21 收官时点，防重复施工）

### 1.1 §16 十项已全部入库（remediation 台账 + git 验证）

| 项 | commit | 对本方案的意义 |
|---|---|---|
| UI 谎报保存成功（postSaveWarning 全程带回） | `b35f1d9fb` | SR 批直接复用告警通道上云上传失败 |
| 真 `.bak` 轮转 | `b35f1d9fb` | 过渡期安全网；SR-7 退役（D5） |
| 空函数恢复真实化 | `b35f1d9fb` | 同上 |
| heavy 保全（缺失 key 回填 + 跳过分支显式告警） | `853bb2d8b` | 缓存层保持 |
| proto162 让号 →1002 + **唯一性守卫** | `908f24180` | SR-1 邮件入快照新增 tag 时守卫即红线 |
| **`onStop` 后台保存，旗标默认关** | `870be9771` | SR-4 打开旗标 + 接入上传队列 |
| 删档完整性（clearAllSlotTables 单事务 32 DAO + 守卫） | `2ecb51e2b` | SR-1 邮件同步纳入该清单纪律 |
| 商人"仅当从未生成"锚（§12-G 唯一真修项） | `bf8394bbc` | 其余 §12-G 子项判归"需产品确认"锁定，不在本方案强推 |
| 死代码第一刀（core:data 存储死面，schema 中性） | `5a421f3d6` | 第二刀（6 冗余表+crypto 链）归 SR-7 schema 批 |
| 归档可还原 + slot 6 补齐 | `e9c8988ed`/`0f6215981` | 云唯一后归档策略重审（SR-7 一并判归） |

组合门基线（remediation §5.1 实测）：**7861 用例 / 0 失败 / 17 跳过**，
`Diff*` 50 类 / 273 例 / **0 skip**。后续批以收官时最新台账基线为准。

### 1.2 审计前提勘误（remediation §2，本方案已吸收）

- heavy"先删后写在事务外"**不成立**——实测在外层 `database.withTransaction` 之内；
- `SlotLockManager` / `FunctionalWAL` / `DataArchiver` 查询面**是活的**，不可照审计"死代码"清单删。

### 1.3 审计十项之外、仍开放并被本方案收编的缺口

| 缺口 | 证据 | 收编批次 |
|---|---|---|
| 重开游戏**先重置引擎后落盘**，旧档被重置态覆盖 | `SaveLoadViewModelRestartOps.kt:111 restartEngineAndReseed → :115`（审计 §2） | **SR-2**（保护性预存：重置前先把当前态落槽） |
| 初始化 3 次全败**静默放行**进主菜单 | `MainActivity.kt:388-417`（审计 §12-J） | **SR-3**（云主路径下初始化失败必须如实阻断/降级提示） |
| 云仲裁只比版本串 + 服务端 mtime 对比本地挂钟（时钟偏移即长期误判） | `TapCloudSaveManager.kt:359 arbitrateCloudVersion` / `:104 resolveCloudSaveInfo`（审计 §12-I） | **SR-2**（脏标志仲裁整体替换，不再比时间） |
| 云档下载**不落盘**（只进内存） | `SaveLoadViewModelCloudLoadOps.kt:280`（审计 §3/§12-I） | **SR-3** |
| 邮件只在 Room、不在 SaveData 快照 | 审计 §10（唯一不可回滚通道） | **SR-1**（并入快照，否则云唯一=换设备丢邮件） |
| 客户端墙钟信任族（玉符日额/兑换码限流/周奖励冷却/邮件 30 天删除） | 审计 §12-H | **SR-5**（TimeSource 接口化） |
| 存档明文、无签名 | 审计 §14（crypto 链零调用） | **SR-5**（HMAC 签名最小子集） |

---

## 2. 目标架构

```
C++ gamecore（Mode.AUTHORITATIVE，不变）
    ↓ 每旬 exportDirty() protobuf 差分（保值资产 D7，零改动）
GameStateStore（Kotlin 内存权威镜像，不变）
    ↓
SaveOrchestrator（新，唯一保存编排点）
    ├─ 触发面：手动（设置页）/ 月变自动（静默）/ onStop（旗标）/ 新游戏 / 重开（预存修正后）
    ├─ 去抖合并：窗口内多触发合并为一次快照
    ├─ 第一步：本地事务提交（Room 会话缓存，必成、离线也能完成）
    └─ 第二步：投递 UploadQueue → SaveBackend
SaveBackend（新接口，D4）
    ├─ TapTapSaveBackend（现 CloudSaveApi 之上包装；archiveName=slot_N 多档）
    └─ （未来）GameServerSaveBackend —— 联网化后端就绪时新增实现
UploadQueue（新）：协程作用域 + 落地队列（MMKV 记账）+ 指数退避 + 合并
仲裁：脏标志 = 本地存在未确认上传的保存（lastLocalSaveId > lastConfirmedCloudId）
      —— 完全不比较时钟（D3/审计 §12-I 根治）
```

**现有代码的落点**：`TapCloudSaveManager` 内部已有 `CloudSaveApi` 抽象
（`:681-688` createOrUpdateArchive/downloadArchive/queryArchiveInfo/listAllArchives/deleteArchive，
XDSdk/TapSdk 双实现 `:656/:668`）——`SaveBackend` 建立**在其上**一层，
门面化槽位语义与仲裁，SDK 探测逻辑全部复用，不重写。

**模式开关（回滚与灰度的总闸）**：`SaveBackendMode` 三态——
`LEGACY`（现状：本地为真相，云为镜像）→ `CLOUD_TRANSITION`（双写：本地照旧 + 云上传收口，
存量迁移引导期）→ `CLOUD_ONLY`（云为真相，本地=缓存，文件层退役）。
SR-2 引入（默认 LEGACY），SR-3/SR-6 逐级切换，任何一级出问题退回上一级。

**读路径（CLOUD_TRANSITION 起）**：
冷启动 → `SaveBackend.list()` 云槽位列表 → 用户选档 →
脏标志判定：本地净 ⇒ 云下载 → 校验 → 迁移 → 落缓存 → 整对象替换 → boot；
本地脏 ⇒ 双方都有新进度 ⇒ **真冲突，弹窗让玩家选**（禁止静默覆盖）。
`isGameAlreadyLoaded` 一次会话一 load 的语义保留。

**触发矩阵（终态）**：

| 触发 | 行为 | 用户可见 |
|---|---|---|
| 设置页"保存" | 全量：本地事务 + 队列 + 上传结果如实（复用 postSaveWarning 通道） | 结果提示 |
| **月变（游戏月）** | 静默全量：本地事务 + 入队 | 消息栏"已自动存档"一行 |
| `onStop` | 本地事务 + 队列尝试排空（旗标打开） | 无 |
| 新游戏首存 | 本地 + 入队（失败重试一次，两次中止开局——现语义保留） | 错误如实 |
| 重开游戏 | **先保护性预存当前态，后重置引擎，再落新档**（顺序修正） | 确认弹窗 |

---

## 3. 不变量（长期维护红线，CI 守卫锚点）

- **IN1 原子性**：所有缓存层 DB 写在单事务内；后置步骤（上传/归档）失败只降级不回滚本地。
- **IN2 仲裁无时钟**：任何"谁新"判定只看保存序号/脏标志，**永不**比较墙钟或 mtime。
- **IN3 接口隔离**：`feature`/`core:engine` 对存储层只依赖 `StorageFacade`/`SaveBackend`，
  **零 TapTap SDK 类型引用**（静态守卫）。
- **IN4 wire 唯一性**：proto tag 全局唯一（守卫已在 `908f24180` 落地）；新字段必须过守卫。
- **IN5 尺寸红线**：云档 payload ≤ 红线值（SR-0 定值，TapTap 上限 10MB 减裕量），
  CI 构造老玩家样本档断言。
- **IN6 机制要么有调用者+测试，要么删**：防"安全剧场"再生。
- **IN7 boot 只读不写**：读档后修复逻辑仅补索引/幂等收敛，不改存档值
  （§12-G 其余子项已判归锁定，待产品逐条拍板，不在本方案夹带）。
- **IN8 差分管线零破坏**：每批 `Diff*` 对拍 0 skip 是出厂门。

---

## 4. 批次分解（SR-0 … SR-7）

> 编号与 B 系列独立（B 已至 B20c）。施工卡落 `parallel-batches` 目录时沿用
> `batch-SR*.md` 命名，`dispatch-ledger.md` 登记。依赖链：
> SR-0 → 全部；SR-1 → SR-3；SR-2 → SR-3/SR-4；SR-5 可与 SR-3/SR-4 并行；
> **SR-6 必须在 SR-3 稳定 + SR-7 存量迁移引导之后**。

### SR-0 前置侦察批（无产品代码改动）

| 任务 | 方法 | 产出 |
|---|---|---|
| payload 尺寸分布实测 | 构造多游戏年样本档（1/5/20/50 年 × 不同弟子规模），跑 `SaveFacadeImpl.getStateSnapshot` → serialize+LZ4 量字节 | 尺寸-游戏年曲线；**云档裁剪决策**（战斗日志限条数 / heavy 分 key 上传是否必要）→ IN5 红线值 |
| TapTap v4 限额复核 | 官方文档复核：单档上限（现文档 10MB）、**每用户多档数量上限**、每分钟上传限频的精确语义 | Go/No-Go + 槽位云化上限 |
| 云多档语义实测 | `CloudSaveApi.createOrUpdateArchive(archiveName=slot_N …)` 多档并存/列举/删除，真机或沙盒 | SR-3 槽位映射方案确认 |
| 双设备冲突剧本 | 剧本级测试设计（两实例交替上传/下载 + 本地脏标志组合） | SR-2 仲裁单测用例清单 |
| 嵌套事务存疑项收口 | 审计 §15 存疑 1（外层 `Engine:646` + 内层 `WriteOps:55` 两处 `Room.withTransaction` 是否并入同一事务）实跑验证 | 结论回写审计报告 |

**门**：侦察报告入 `docs/`；Go/No-Go 明确（No-Go = payload 无法压到红线内且分 key 不可行 ⇒ 回用户重拍板）。

### SR-1 邮件并入 SaveData 快照（wire 面变更，**单独走批**）

- `SaveData` 增 `mails: List<MailEntity>`（proto 新 tag，过 IN4 唯一性守卫）；
  保存时从 `mails` 表读当前 slot 全量入快照；加载/云恢复时整对象替换回表；删档走
  `clearAllSlotTables` 清单纪律（守卫自动覆盖）。
- 30 天墙钟删除逻辑**本批不动**（归 SR-5 TimeSource 收口）。
- **红线**：wire 面单独走批；旧档无 mails 字段 ⇒ 默认空表（向后兼容方向单向，无需迁移器条目）；
  roundtrip 测试（存→读→逐字段等价，含含附件未领邮件样本）。
- **门**：`:core:data` + `:core:domain` 定向 + 组合门 + 存档回归实跑输出。

### SR-2 SaveBackend + 上传队列 + 脏标志仲裁 + 重开预存（core 面）

- 新 `SaveBackend` 接口（upload/download/list/delete + `observeConflict`），
  `TapTapSaveBackend` 包装现有 `CloudSaveApi`；`SaveBackendMode` 三态开关（默认 LEGACY，行为零变化）。
- `UploadQueue`：协程 + 落地记账（MMKV：`lastLocalSaveId` / `lastConfirmedCloudId` / 待传指针），
  指数退避、窗口内合并、限频适配（TapTap 1 次/分钟）。
- 仲裁重写：删除 `arbitrateCloudVersion` 的版本串比较与 `resolveCloudSaveInfo` 的
  mtime-对-挂钟比较（IN2）；改为脏标志 + 真冲突显式暴露给 UI 层。
- **重开顺序缺陷修正**：`SaveLoadViewModelRestartOps` 重置引擎**前**先走一次
  保护性预存（审计 §2 缺口，§16 未覆盖）。
- 上传失败经 `postSaveWarning` 通道如实呈现（复用 `b35f1d9fb` 基建）。
- **门**：仲裁纯函数单测（脏/净/双端冲突组合全覆盖）+ 队列状态机单测 + 组合门；
  LEGACY 模式下全量回归零行为变化（模式开关默认关的守卫测试）。

### SR-3 云主路径 + 槽位云化 + 初始化如实（产品可见面）

- 云下载**落盘**：修 `SaveLoadViewModelCloudLoadOps.kt:280` 只进内存——下载 → 校验 →
  迁移 → 落缓存 → 再走既有 boot 链。
- 冷启动主菜单：云槽位列表（`listAllArchives` → slot_N 映射）+ 摘要渲染；
  本地槽位 UI 在 CLOUD_TRANSITION 下并存（本地照常可用），CLOUD_ONLY 后移除。
- `MainActivity` 初始化 3 次全败**静默放行**修正（§12-J）：如实阻断 + 重试 UI，
  或显式"离线缓存模式"降级提示（模式感知）。
- 真冲突弹窗（本地脏 × 云端有更新）：玩家二选一，选谁留档明确可见。
- **门**：双设备冲突剧本实跑（SR-0 用例清单）+ 真机验证（本批起**必须**真机，
  云链路 Robolectric 不可全信）+ 组合门。

### SR-4 月变自动存档 + onStop 收口（自动触发面）

- 月变钩子：`GameEngineCoreAuthoritativeOps.kt:78-81`（`settleFlags and FLAG_MONTH_CHANGED`）
  → `GameEngineCorePausOps4.kt:90 processMonthYearChange` 月副作用完整结算**之后**触发
  `SaveOrchestrator.autoSave(MONTHLY)`；年变先于月变的编排顺序（`AuthoritativeOps.kt:43`）不触碰。
- `onStop` 旗标打开（`870be9771` 基建，NativeEngineFlag 风格），行为 = 本地事务 + 队列排空尝试。
- 去抖合并：手动/月变/onStop 窗口内合并为一次快照；熔断复用 `recordSaveCircuitResult` 链。
- 用户可见：消息栏一行"已自动存档"；失败走告警通道，**不再静默**。
- 后台修剪任务与自动保存的互斥审查（`DataPruning` 300s / `DataArchive` 600s 与新触发点的锁交互）。
- **实施补记（2026-09-22 SR-4 交付）**：① **频率口径实测** = 游戏月 6 秒真实时间
  （`GameTimeClock.kt:224` 2000ms/旬 × 3 旬；2x 下 3 秒），实施前向用户提请三选（60s 节流 /
  月月必存 / 5min 节流），**用户拍板"严格按 D6 字面：月月必存"** ⇒ 编排层不做按秒节流，
  合并窗（500ms）只做同刻多源合并；后果（每 6 秒全量快照 + Room 事务、非 LEGACY 入队频率
  ≫ TapTap 1 次/分钟、性能/发热/耗电）登记于 `report-SR4-completion-2026-09-22.md`，真机为硬门。
  ② "消息栏一行"落为**常驻状态行**而非常驻 snackbar（6 秒一次弹窗会刷屏并挤掉真事件消息），
  自动口径的失败同承载位显示"自动存档失败：…"，手动口径 snackbar 逐行不变。
  ③ 互斥审查结论：**既有 `SlotLockManager` 每槽排他锁已覆盖**（保存/读档/修剪/归档同锁），
  零新增锁与零生产行为改动，详见 SR-4 完成报告 §5。
- **门**：月变触发时序单测（月副作用完成后才存）+ 去抖单测 + 组合门 + 真机后台杀场景。

### SR-5 时间与签名面收敛（联网前置，可与 SR-3/4 并行）

- 新 `TimeSource` 接口：现实现 = 云端 mtime 校正的墙钟（启动时用云元数据修正偏移），
  后端就绪换服务器真源；**消费方收敛**：玉符日额（`JadeSymbolService`）、周奖励冷却
  （`GameEngineSectLevelOps.kt:350`）、兑换码限流（`RedeemCodeRateLimitOps`）、邮件过期
  （`MailService.deleteExpiredMails`）全部改走 TimeSource。
- payload HMAC 签名（最小子集）：复用 `.secure_key` 体系密钥派生；签名写入云档 metadata；
  验证侧暂在客户端（后端就绪后服务端验）。诚实登记：**无服务器验证前，防篡改强度有限**，
  此项是接口与格式预埋，不是安全闭环。
- **实施补记（2026-09-22 SR-5 交付）**：① **命名偏离**——本批"新 `TimeSource` 接口"
  落地为既有 **`WallClock`**（`JadeSymbolService.kt:39` 提升为共享类型），因 `TimeSource`
  名已被两个**单调钟**占用（`GameTimeClock.kt:17-19` `elapsedRealtime`、
  `core/animation/TimeSource.kt:16-23` `nanoTime`），再造第三个同名词必然互相误读
  （用户 P1 拍板）。② **范围按游戏语义全族**（P2，用户拍板，不止本节点名的 4 处）：
  实测玉符日额**早已收敛**（本节收编项为既成事实，本批只做抽象搬迁）；真正新增的是
  周冷却 3 处（含 `GameViewModel:519` 与引擎分歧的内联重复）、兑换码 5 处（宿主是
  `object`，改为注入式入口一次采样后形参下传，不引入进程级可变钟）、邮件链 16 个取时点
  （含 `MailDao.insertWithEnforceLimit` 事务内每次写入删过期的**守卫抓不到的绕行点**）。
  ③ **云 mtime 校正的采样点被实测证伪后改挂**（P3 同一意图下修正）：冷启动云列表的
  `modifiedTimeMs` 是"上次归档写入时刻"，按其校正等于把本地钟往回拨"距上次上传过了多久"
  （小时/天量级），会直接打穿日额与邮件判据 ⇒ 采样改挂**上传成功后的元数据读回**
  （此刻 mtime 才等价"现在"），进程内至多一次、|漂移|>5min 丢弃、不跨进程持久化；
  零新增启动往返、LEGACY 不上传 ⇒ 永不采样 ⇒ 默认模式逐位零变化。
  ④ **验签按 P4 降级放行**：判据四态（VERIFIED/UNSIGNED/MISMATCH/KEY_UNAVAILABLE），
  密钥故障单列以防把基础设施问题判成玩家篡改。⑤ **登记遗留**（不在本批夹带）：
  `StorageEngine:232` 存档时间戳盖写与 `core:domain` 模型默认时间戳族**未收敛**——
  `WallClock` 现居 `core:engine`，`core:data` 不能反向依赖，须先定抽象落点；
  `RequestSigner.deriveLocalSigningKey` 对 `SecureKeyManager` **引用缓存**数组做
  `fill(0)`，疑污染同进程后续取键（独立议题）；`RedeemCodeManager.validateCodeWithServerAuth`
  与其 `remoteValidator` 注册链实测零调用者（IN6 议题）。
- **门**：TimeSource 替换面静态守卫（旧 `System.currentTimeMillis` 判据零回流）+ 组合门。

### SR-6 存量迁移引导（CLOUD_TRANSITION 收口，砍文件层前的硬前置）

- 首启检测矩阵：本地有档 × 云无档 ⇒ 引导逐槽上传（slot_1..6）；本地有 × 云有 ⇒ 脏标志仲裁 +
  冲突 UI；本地无 × 云有 ⇒ 直接云档；双无 ⇒ 新游戏。
- **实施补记（2026-09-22 SR-6 交付）**：① **矩阵落地为 `SaveMigrationPlanner` 六步短路纯函数**
  （`core:data/cloud`，零时钟零 IO，22 例方案门单测全组合）——比本节四格多两格：损坏槽
  `BlockedByLoadError`（本机有数据但读取失败，既不上云也不当空档，`SaveSlot.isLoadError` 三态纪律）
  与"未确认待传 ⇒ 续传"（优先级排在"已裁决幂等"之前，否则续传会被迁移记账抹掉）。
  ② 🔴 **施工期发现三处本节未预料的既有缺口，已随批修掉**：
  `UploadLedger.pendingSaveId` **生产零消费者**（SR-2 的"重启按待传指针重入队"配方从未被调用 ⇒
  本批 `scan()` 成为该调用者，仅非 LEGACY 自动执行）；`StorageFacade.load` **不返回邮件快照**
  （迁移若直接 load→upload 产出空邮件云档，而云恢复是整对象替换回表 ⇒ 换设备丢邮件，
  现与保存编排同源补 `getMailsForSlot` 且读邮件失败即中止该槽上传）；
  **`SaveArbiter` U11 在迁移语境下会放行静默覆盖**（W 未知按 W==C 保守重算 ⇒ 判 `UPLOAD_PENDING`
  直接覆盖他端历史档），故矩阵层前置升级为玩家裁决，`SaveArbiter` 本体零改动。
  ③ **S1 模式写入形态**：本批是 `SaveBackendModeProvider.set` 的第一个生产调用者，形态为
  **全员收口后玩家点「启用云存档」才升 `CLOUD_TRANSITION`**（不静默升档、不无限悬置）；
  `CLOUD_ONLY` 生产零写入，并由新 `SaveMigrationGuardTest` 钉"写入点唯一 + 只写 TRANSITION +
  迁移判定族零时钟"。`canPromoteToCloudOnly` 三条件（无损坏槽/无未确认待传/所有本地有档槽已迁）
  作为 SR-7 的前置门交付，本批刻意不接线。
  ④ **引导落为常驻卡而非弹窗**（6 槽 × TapTap 1 次/分钟 ⇒ ≥6 分钟，弹窗看不见进度），
  两阶段设计保 LEGACY 红线：阶段 A 纯本地零云请求，阶段 B 才由玩家显式触发云列表与投递。
  ⑤ **存量单档 `mnzm_cloud_save`（SR-3 §4.1 移交项）已落地**：抽 `CloudSaveCacheWriter`
  统一"下载→管线→落缓存→账本收敛（不 boot）"，支持源槽≠目标槽，且跨槽**不把源槽序号抄进
  目标槽**（`UploadLedger` 序号按槽独立）；SR-3 §5.2 登记的"~15 行有意重复"以此为收敛点，
  兜底 = SR-3 那 13 例改持真实组件跑原断言。
  ⑥ **SR-4 §8 的"加宽合并窗"建议经实测回绝**：单飞循环本是"窗→上传→成功后 delay(冷却)"，
  稳态已 ≈62s/次贴住 1 次/分钟，加宽只让快照更旧；参数零改动，新 Q13 用例钉住该性质。
  ⑦ 完成率指标 = 事件 `#save_migration_result`，
  `migrated_total/(migrated_total+pending_total)`；**TapDB 后台事件录入待运营**。
  ⑧ **本批第一次真正把云上传交到玩家手里 ⇒ 真机 8 项是硬门**（报告 §7），
  交付停在 pending-device。
- **门**：迁移矩阵单测 + 真机全剧本 + 完成率指标定义（运营侧可查）。

### SR-7 文件层废除 + schema 第二刀 + 收官（CLOUD_ONLY 切换批）

- **切换前置门**：SR-3 真机稳定 + SR-6 迁移完成率达标（阈值 SR-6 定）。
- 文件层退役：`.sav`/`.bak`（含 `b35f1d9fb` 轮转，D5）/`.tmp`/tombstone/quarantine/
  `pre_migrate_backup` 全链删除；旧 `.sav` 转**只读应急源**保留 N 个版本后清理；
  `FunctionalWAL` 先摘 5 处调用点再删组件（remediation 勘误：它是活的）；
  `SlotLockManager` **保留**（缓存层并发保护仍需要）。
- Room v52→v53 schema 批：删 6 张零读者冗余表（`disciples_core/combat/equipment/
  extended/attributes` + `disciple_compact`）+ crypto 存档加密链（`5a421f3d6` 第二刀，
  **注意保留 `.secure_key` 网络签名链**）。单独走批，逐字段等价断言纪律同 B19。
- 归档任务判归重审：云唯一后 `DataArchive/DataPruning` 的存在意义（云档裁剪策略是否取代本地修剪）。
- 收官：守卫测试全家桶固化进 CI（IN1-IN8 各配守卫）、审计报告补注终态、
  本文档 §0 决策回写 ADR、CHANGELOG、`dispatch-ledger` 收口。
- **实施补记（2026-09-22 SR-7 交付）**：① **前置门实测不满足 ⇒ 本批按"终态代码就位"交付而非
  "已切换"**：SR-3 真机 8 项未跑、SR-6 完成率无分子分母（TapDB 事件录入待运营），故全部新行为
  门控在 `mode == CLOUD_ONLY`，而 CLOUD_ONLY 生产写入点仍为零（SR-6 `SaveMigrationGuardTest` 锁）。
  ② **文件层组件本体未物理删除**——本批全量设备模式恒 LEGACY，而 D5 写明轮转备份"随文件层
  **一起**退役"，现在删等于抽掉唯一还在生效的兜底；落地的是停写判据
  `shouldWriteLocalSaveFile(mode)`（`.sav`/`.bak`/tombstone 停写）+ `readWithFallback(readOnly)`
  把旧 `.sav` 转为字面意义的**只读**应急源。删档路径的 `deleteSlot`/`clearSlotDeleted` 与
  `wal.shutdown`、过期文件清理**刻意不门控**（遗留 `.sav` 不删会在 DB 损坏时被复活成"删掉的档又回来"）。
  ③ **schema 第二刀实测纠三条**：方案"删 6 表"不可读成"删 6 类"——`DiscipleAggregate` 与
  `DiscipleStatCalculator` 实测在内存侧消费这 5 个类 ⇒ 保类去 Room 注解，仅零消费者的
  `DiscipleCompact`/`DiscipleAggregateWithRelations` 连类删（IN6）；`FunctionalWAL` 调用点实测
  **6 处**非 5 处；crypto 第二刀实测只剩 `SaveCryptoKeyCache` + `StorageConfig` 两个死壳
  （真载荷加密码早在 `5a421f3d6` 切走），`.secure_key` 链零触碰并新立守卫。
  ④ **归档判归**：`archived_battle_logs`/`archived_disciples` 实测 DAO 零 SELECT、归档内容
  不入云档 payload、还原 API 零调用者、无 UI 入口 ⇒ 判"随文件层退役"，修剪三项（change_log/
  迁移备份/snapshots）仍必要故保留；顺带暴露 `cleanSaveDataWithArchive` 落盘前把溢出战斗日志
  裁进零读者归档 ⇒ 玩家视角静默丢失，与 D2 冲突，**属产品决策故本批不处置**（详情报告 §6）。
  ⑤ **守卫缺口如实登记**：IN7（boot 只读不写）**至今无有效守卫**，能写的浅层断言是同义反复故
  不写；IN4 唯一性扫描面漏 `state/BattleResultUIData.kt` 与 `backwardcompat/OldSerializableSaveData.kt`；
  已补的是 IN1 事务形状、IN5 红线（新常量 `CLOUD_PAYLOAD_RED_LINE_BYTES = 2_000_000`，
  原先只用 10MB 平台硬上限 ⇒ 可涨 30 倍仍判绿）、IN8（新 `DiffBridgeGateTest`：缺
  `-Dgamecore.jni.path` 时 45/50 个 `Diff*` 文件静默 skip 而 Gradle 视为成功 ⇒ 改为判红）。
  ⑥ 判别力自证两次实跑：schema 守卫初版共用生产常量导致"少删一张表"只有 3/5 例判红
  （Room 校验对未声明表只告警）⇒ 期望改独立字面清单 + 漂移守卫；C3 守卫首跑抓到本会话漏删的
  `setCacheDerivedKey`。
- **门**：schema 批门（同 B19 口径）+ 组合门 + ctest + 真机升级路径实测
  （本批**必须**真机，无可豁免）。

---

## 5. 全局验收门（每批必过，一处定义多处引用）

1. **六模块组合门**（命令与判绿口径照 remediation §5.1 实测）：
   `cd android && export JAVA_HOME=C:/Users/cp050/.jdks/jdk-21.0.12.1+1`；
   `./gradlew.bat testReleaseUnitTest --max-workers=1 "-Dgamecore.jni.path=<Windows 原生路径>" detekt compileReleaseKotlin lintRelease`；
   判绿看 **XML executed 计数 + 时间戳**，不看 `BUILD SUCCESSFUL`；基线 7861/0/0/17 起步，
   以最新台账为准。
2. **桌面 ctest 照跑**（纯 Kotlin 批预期 `ninja: no work to do.` + 基线 1558）。
3. **`Diff*` 对拍 0 skip**（IN8）。
4. wire/schema 批（SR-1/SR-7）：存档回归**逐字段等价**断言实跑输出贴完成报告 +
   `MigrationChainGuardTest` 纪律。
5. SR-3 起云链路批：**真机实测为硬门**（Robolectric 不可替代），证据 = 步骤录像/日志摘录 +
   双设备剧本结果。
6. 文档三件套：本方案相应节更新 + `CHANGELOG.md` + `dispatch-ledger.md`。

## 6. 风险登记

| 风险 | 概率/影响 | 缓解 |
|---|---|---|
| payload 超 TapTap 10MB 上限 | 中/高（老玩家长线档） | SR-0 实测定红线；裁剪策略（云档战斗日志限条数、heavy 分 key）；IN5 CI 断言 |
| TapTap 多档数量/限频与槽位设计冲突 | 中/中 | SR-0 复核 v4 文档 + 多档实测；上传队列限频适配 |
| 双设备真冲突静默丢档 | 低/极高 | IN2 脏标志仲裁 + 冲突弹窗（禁止静默覆盖）+ 剧本测试 |
| 存量玩家不迁云即被切 CLOUD_ONLY | 中/高 | SR-6 完成率门槛 + 模式开关按设备推进，可整体回退 |
| 无后端下时间/签名预埋强度不足 | 高/中 | SR-5 诚实登记局限；接口化保证后端就绪即换真源 |
| 过渡期双写不一致（本地 vs 云） | 中/中 | 脏标志是唯一仲裁依据；本地只是缓存，不一致=重下，不修复"假一致" |
| 真机/云链路测试基建缺失 | 高/中 | SR-3 起列为硬门并先建最小剧本基建（remediation §5.2 已登记此项欠账） |

## 7. 与现行编排的关系

- 本方案是**新专题系列**（SR-0…SR-7），不占用 B 系列编号；B20c 后 B 系列收官状态不受影响。
- 每批施工卡/完成报告/台账登记沿用现行 w5 目录约定（或开工时另立 SR 目录，由派发时定）。
- §12-G 其余"需产品确认"子项（巡视槽裁撤/生产槽截断/自动收割/世界重生/拆除退款）
  **不在本方案内**，仍按 remediation §3.2 等产品拍板。

---

*本文档只读入库，未修改任何源代码。SR-0 为无风险首批，可直接开工。*
