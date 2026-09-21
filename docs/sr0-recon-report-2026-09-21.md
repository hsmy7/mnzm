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

## §1 payload 尺寸分布实测（T1）——✅ 结论：最大档 ≈0.28MB，远低于红线，Go

### 1.1 测量路径（生产同构）

`SaveData → NullSafeProtoBuf(PROTOBUF) → LZ4 + SHA-256 checksum`——与云上传
`SerializationModule.serializeAndCompressSaveData` 完全相同的 `SerializationContext`。
台架 = `android/core/data/src/test/.../CloudPayloadSizeBenchTest.kt`（**保留为 IN5 CI 断言种子**，
方案 §3 IN5 落地时收紧为红线门）。

### 1.2 两个改变判断的代码事实（勘察发现）

1. **`GameData.aiSectDisciples` 带 `@kotlinx.serialization.Transient`，不进 SaveData proto**
   ⇒ **云档 payload 根本不含 AI 宗弟子**（29 宗 × 至多 1000 人的最坏担心不成立）。
   AI 弟子走 Room `game_heavy_data` 独立通道；`GameData.kt:892` 注释自证
   "云存档链无 heavy_data 侧车补偿"。同 @Transient 还有 aiSectBeastSkipCooldowns /
   aiBeastEncounterTargets / lockedBeastIds / aiSectBeastDirectTargets。
   **SR-3 必读推论**：跨设备云读档时 AI 宗弟子由 `ensureGameDataIntegrity`
   按 ~初始态重生成（`MAX_AI_SECT_DISCIPLES=50`/宗），玩家在原设备花资源打出来的
   AI 宗弟子池（占领战、装备补全）在换设备后**不保真**——这是现役云链路的既有语义，
   CLOUD_TRANSITION 设计槽位云化时必须拍板：接受重生成 or heavy 域并入云档（体积可行，见 1.4）。
2. **`terrainTiles`（@ProtoNumber(1001)，16384 瓦片 flat）在云档 payload 内**，实测仅 ≈3KB（LZ4）。

### 1.3 尺寸-游戏年曲线（3/3 测试绿，2026-09-21 实测）

| 档位 | 玩家弟子 | 战斗日志 | 装备/功法/材料 | proto 原始 B | **LZ4 后 B** | LZ4 后 MB | 压缩率 |
|---|---|---|---|---|---|---|---|
| 第1年 | 20 | 100 | 60/30/40 | 317,478 | **32,775** | 0.03 | 0.103 |
| 第5年 | 60 | 400 | 180/80/120 | 1,089,598 | **106,426** | 0.10 | 0.098 |
| 第20年 | 120 | 1000 | 350/150/250 | 2,620,136 | **247,988** | 0.24 | 0.095 |
| 第50年 | 200 | 1000 | 500/200/300 | 2,669,881 | **263,665** | 0.25 | 0.099 |
| 第50年·重玩家档 | 300 | 1000 | 800/300/400 | 2,734,070 | **283,206** | 0.27 | 0.104 |
| 极限档·年报满100(200年) | 300 | 1000 | 800/300/400 | 2,743,458 | **286,313** | 0.27 | 0.104 |

成分归因（LZ4 后增量）：单弟子 ≈351B；单战斗日志（中等密度 4-9 回合）≈437B；
terrainTiles 16384 瓦片 ≈3KB；满探索世界 GameData ≈9KB。
battleLogs 条数敏感性（LZ4 后）：100 条 21KB / 500 条 105KB / 1000 条 209KB——**线性 ≈215B/条，
是 20 年后档位的最大单项（≈73%）**。

### 1.4 云档裁剪决策 + IN5 红线建议

- **战斗日志限条数：维持现役 1000 条封顶（`SaveDataTrimmer`），无需收紧**。1000 条 = 209KB，
  即便未来内容密度翻倍也不构成压力；"heavy 分 key 上传"**无必要**（AI 宗弟子域本就 @Transient
  不进 payload；其余域实测总和 <100KB）。
- **IN5 红线建议 = 2MB（2,000,000 字节，LZ4 后）**：对最坏实测档（0.29MB）留 ~7× 余量，
  对 TapTap 10MB 硬上限留 5× 保护；覆盖 SR-1 邮件并入快照、未来功能漂移、
  以及"台架合成内容密度低于真实存档 3×"的最坏修正后仍有 ~2.3× 余量。
- CI 断言落地（IN5 收紧时）：`CloudPayloadSizeBenchTest` 的"极限档"断言
  `compressed < 2_000_000`（当前 <10MB 宽松断言照旧保留作硬上限兜底）。
- 诚实登记：样本为**合成生产形状**（字段内容长度取真实中位数、无 mail/SR-1 增量、
  存量真实档未采样）；若验收轮要求真档校准，可在真机导出 `.sav` 对拍一次。

## §2 TapTap v4 限额复核（T2）——✅ 结论：Go，6 槽位方案全额可行

### 2.1 依据与口径

- 官方文档（TapTap 开发者中心，**v4**，与本项目集成 `com.taptap.sdk:tap-cloudsave:4.10.5`
  完全对应）：
  [云存档功能介绍](https://developer.taptap.cn/docs/sdk/tap-cloudsave/features/) +
  [云存档开发指南](https://developer.taptap.cn/docs/sdk/tap-cloudsave/guide/)（2026-09-21 复核）；
- 本地佐证：gradle 缓存 AAR `tap-cloudsave-4.10.5` 反汇编核对公开 API 面
  （`TapTapCloudSave.createArchive/getArchiveList/getArchiveData/updateArchive/deleteArchive/getArchiveCover`），
  SDK 侧**无限额常量**——限额全部服务端强制，SDK 透传错误码（300001 未登录 / 300002 初始化失败）。

### 2.2 限额事实（官方原文）

| 项 | 限额 | 出处 |
|---|---|---|
| 单档数据文件 | **≤ 10MB** | 功能介绍 + 开发指南（创建/更新存档节均注明） |
| 单档封面 | ≤ 512KB | 同上 |
| **每玩家每游戏存档数量** | **≤ 100 个**，总容量 **≤ 100MB** | 功能介绍页限额总纲 |
| **上传限频** | **创建/更新"一分钟内仅可调用一次"（创建与更新共享冷却时间）** | 开发指南创建/更新存档节（两节各自明示） |
| 并发 | 创建/更新最多 10 并发；**同一存档不允许并发更新** | 功能介绍页 |
| archiveName 命名 | **仅英文/数字/下划线/中划线，不支持中文**（summary/extra 无限制） | 开发指南 |

错误码（`onRequestError`）：400000 非法大小 / **400001 上传频率超限** / 400002 存档不存在 /
**400003 单应用存档数量超限** / 400004 单应用空间超限 / 400005 总空间超限 /
**400006 操作令牌失效（创建/更新耗时过长）** / **400007 不允许并发调用** / 400008 无 OSS / **400009 存档名称不合法**。

### 2.3 口径冲突如实登记

功能介绍页总纲写"创建/更新云存档限制**每分钟最多 60 次**"，而开发指南创建/更新两节均写
"**一分钟内仅可调用一次**（与更新/创建共享冷却时间）"——两处矛盾。处置：**按最保守口径
（1 次/分钟，创建+更新共享同一冷却）设计 SR-2 UploadQueue**；若后续真机实测证实 60 次/分钟
口径成立，队列参数放宽即可（设计上冷却参数化）。小游戏文档（tapfile:// 通道，非本项目 SDK）
同样表述"每分钟最多上传 1 次（400001）"。

### 2.4 判定

- **Go**：`archiveName=slot_1..slot_6` 命名合法（英文+下划线）✓；6 档 ≪ 100 档上限 ✓；
  6 × 0.3MB ≈ 2MB ≪ 100MB 总容量 ✓（§1.4 实测单档最大 0.29MB）；
  **槽位云化上限建议：以 6 槽为产品设计上限，技术上 100 档可用**（若未来做云档历史版本，
  空间充裕；100 档为硬顶， UploadQueue 需防 400003）。
- UploadQueue 必须处理的错误码：400001（冷却未到→退避重试）、400006（令牌失效→重取令牌重试）、
  400007（并发→串行化单飞）；400000/400009（构造缺陷→熔断上报）。

**结论：D3"限频 1 次/分钟 + 单档 10MB 是硬约束"的设计前提与官方 v4 文档一致，SR-2/SR-3 可开工。**

## §3 云多档语义勘察（T3）——✅ 结论：`archiveName=slot_N` 代码层可行，SDK 签名已核验；6 项待真机

### 3.1 CloudSaveApi 抽象现状（`TapCloudSaveManager.kt`）

- 抽象接口 `CloudSaveApi`（:678-689）**全名参数化**：`createOrUpdateArchive(archiveName,…)` /
  `downloadArchive(archiveName)` / `queryArchiveInfo(archiveName)` / `listAllArchives()` / `deleteArchive(uuid)`；
- 双实现探测（`CloudSaveApiReflector` :644-675）：`tryDetectXDSdkApi`（`com.xd.sdk.taptap.XDTapCloudSave`，
  需额外依赖 `com.xd.sdk:xdsdk-taptap`，本项目**未集成**，运行时探测落空）→ `tryDetectTapSdkApi`
  （`com.taptap.sdk.cloudsave.TapTapCloudSave`，**tap-cloudsave 4.10.5 在库**，运行时实际走此路径）；
- **SDK 签名核验**（gradle 缓存 AAR `tap-cloudsave-4.10.5` javap 反汇编）：`createArchive(ArchiveMetadata,
  filePath, coverPath?, callback)` / `updateArchive(uuid, metadata, filePath, fileId?, callback)` /
  `deleteArchive(uuid, callback)` / `getArchiveList(callback)` / `getArchiveData(uuid, fileId, callback)`；
  回调 `onArchiveCreated/Updated/Deleted(ArchiveData)` / `onArchiveListResult(List<ArchiveData>)` /
  `onArchiveDataResult(byte[])` / `onRequestError(int, String)`——与反射桥
  `ReflectiveCloudSaveApi` 的动态派发**逐签名匹配**，`ArchiveData` 携带
  `uuid/fileId/name/summary/extra/playtime/saveSize/coverSize/createdTime/modifiedTime` 十字段。

### 3.2 多档并存的代码层判定

**可行。** 依据：
1. 上传/下载/查询全部以 `archiveName` 为键：`findArchiveUuidByName` 每次对 `getArchiveList`
   结果做 `firstOrNull { getName(it) == name }` 扫描；不同名字即不同云档，互不覆盖；
2. `listAllArchives()` 返回**全部**档（uuid+name+modifiedTime）——SR-3 槽位列表 UI 的
   数据源现成；`oneTimeCleanup`（:606-629）已实践"非本命存档一律保留"的多档共存先例；
3. 命名 `slot_1..slot_6` 满足官方命名规则（英文/数字/下划线，§2.2）。

**SR-2/SR-3 施工面清单（单档假设 → 多档要改的点）**：

| 现状（单档硬编码） | 位置 | 多档化方向 |
|---|---|---|
| `CLOUD_SAVE_ARCHIVE_NAME = "mnzm_cloud_save"` | :51 | `slot_N` 派生；保留旧名作迁移源（SR-6） |
| 单一 UUID 缓存 `KEY_ARCHIVE_UUID` | :58, :140-155 | 按 slot 分键；不缓存也能靠名字扫描兜底（多一次列表往返） |
| 单一摘要缓存 `KEY_CLOUD_SAVE_INFO` | :60, :429-476 | 按 slot 分键 |
| 单一临时文件 `CLOUD_SAVE_FILE_NAME` | :45 | 按操作/分档命名（现 `cloudOpLock` 全局互斥下安全） |
| `cloudOpLock` 全局互斥 | :138 | **保留全局**——限频（1 次/分钟共享冷却）按游戏全局计，多档更需串行 |
| 仲裁 `arbitrateCloudVersion` / `resolveCloudSaveInfo` | :359 / :104 | 按 slot 实例化（SR-2 脏标志仲裁整体替换，版本串比较退役——方案 §1.3/IN2） |
| 上传即触发 `getArchiveList` 扫描 | :732 findArchiveUuidByName | 分档 UUID 缓存命中后可省列表往返；上传冷却（400001）重试按档参数化 |

### 3.3 待真机/沙盒实测清单（本环境无法执行，如实登记）

1. **服务端实际多档行为**：同一账号连续创建 6 个 `slot_N`（100 档上限是文档值，实际放行/报 400003 的边界未验）；
2. **列表最终一致性**：上传 `slot_1` 后立即 `getArchiveList` 是否立即可见（影响 SR-3 槽位列表刷新与 SR-6 迁移完成度判定；单档时代 `resolveCloudSaveInfo` 已记录过 metadata 陈旧问题，多档下影响放大）；
3. **限频真实粒度**：1 次/分钟（开发指南）vs 60 次/分钟（功能介绍，§2.3 口径冲突）——真机触发 400001 实测为准；
4. **命名校验**：`slot_N` 实际过服务端校验（400009 风险，理论上合法）；
5. **回调可靠性**：SDK 回调不触发的场景面（现有 15s 超时兜底 :828-836）与 400100（SDK 未就绪）的实际触发条件；
6. **大档传输**：接近 10MB 的档上传/下载在弱网下的成功率与 400006（令牌失效）触发频率——当前实测 payload ≤0.3MB，风险本就低。

### 3.4 SR-3 槽位映射方案确认

`slot_N (N=1..6)` ↔ `ArchiveData.name`，列表 = `listAllArchives().filter { name matches slot_N }`，
摘要渲染用 `ArchiveData.summary/extra`（现有 extra JSON 协议 year/month/sect/disciples/stones/version
直接复用），读档 = `downloadArchive("slot_N")`，删档 = `deleteArchive(uuid)`。
**存量 `mnzm_cloud_save` 在 SR-6 引导期作为第 0 源参与迁移矩阵（本地有 × 云无 等），CLOUD_ONLY 后退役。**

## §4 双设备冲突剧本设计（T4）——✅ 产出 SR-2 仲裁单测用例清单 + SR-3 真机剧本

### 4.1 状态模型（SR-2 仲裁的判定输入，方案 §2/IN2 口径）

| 记号 | 含义 | 落点（SR-2 设计） |
|---|---|---|
| `L` | 本地最新保存序号 `lastLocalSaveId` | MMKV per-slot |
| `C` | 本地已确认上云的序号 `lastConfirmedCloudId` | MMKV per-slot |
| `W` | 云端实际保存序号（下载/上传响应回填） | 云档 extra JSON |

**脏标志 = `L > C`**（存在未确认上传的保存）。判定**只看序号**，永不比较墙钟/mtime（IN2）。
verdict 纯函数：`arbitrate(L, C, W) →`：
`LOCAL_BEHIND`（L==C 且 W>L ⇒ 云新，直接下载）｜`IN_SYNC`（L==C==W）｜
`UPLOAD_PENDING`（L>C 且 W==C ⇒ 仅本地新，队列重试即可，非冲突）｜
`CONFLICT`（L>C 且 W>C ⇒ 本地与云各有新进度，必须弹窗二选一，禁止静默覆盖）。

### 4.2 双设备剧本矩阵（剧本级设计；SR-3 起真机实跑为硬门）

设设备 A/B，本地状态 (L,C)，云端 W。每剧本 = 初始态 + 交替动作 + 期望判定 + 期望用户可见。

| # | 剧本 | 初始 (A: L,C ｜ B: L,C ｜ W) | 动作序列 | 期望 |
|---|---|---|---|---|
| S1 | 单设备净循环 | (3,3)｜—｜3 | A 保存→上传确认→重启 | L=4→C=4，IN_SYNC；重启后不弹任何冲突 |
| S2 | 净-净交替 | (3,3)｜(3,3)｜3 | A 存+传 → B 下载 → B 存+传 → A 下载 | 全程零冲突提示；两轮后 A/B/W 同步到 5 |
| S3 | 双端同脏（真冲突） | (4,3)｜(4,3)｜3 | A、B 各自本地保存（均未传），任一端进入选档 | **双端都判 CONFLICT**，弹窗列出双方摘要，玩家二选一；选谁留谁的档，另一方被覆盖前有明确提示 |
| S4 | A 脏 × 云有 B 新档 | (4,3)｜(4,4)｜4 | B 存+传成功；A 带未传保存进入选档 | A 判 CONFLICT（不是静默下载覆盖 A 的月度进度） |
| S5 | A 脏 × 云旧（上传重试中） | (4,3)｜—｜3 | A 保存后断网→恢复网络 | UPLOAD_PENDING，队列自动重试上传成功转净，**零冲突弹窗**（区分于 S3/S4 是本设计的关键正确性） |
| S6 | 确认丢失（上传幂等） | (4,3)→上传成功但确认未落地｜—｜4 | A 重启→队列重传同 id | 幂等覆盖 W=4，转 IN_SYNC；**不得误报 CONFLICT** |
| S7 | 下载不完整 | (3,3)｜—｜4 | 下载中断/校验失败 | 本地旧档原样保留（整对象替换原子性，审计 §3 修复面），报错可重试 |
| S8 | 冷启动首次云接入 | (0,0)｜—｜3 | 本地无档，选云档下载 | LOCAL_BEHIND → 下载→校验→迁移→落缓存→boot（`SaveLoadViewModelCloudLoadOps:280` 修复面） |
| S9 | 双无 | (0,0)｜—｜0 | 新安装 | 新游戏引导（SR-6 矩阵入口） |
| S10 | 月变自动存 × 冲突 | (4,3) 月变触发｜(4,4)｜4 | 月变静默自动保存完成 → 上传 | 上传前 arbitration 判 CONFLICT 时**自动存不静默上传覆盖**——入队但标记冲突待决，UI 提示（SR-4 联动项） |

### 4.3 SR-2 仲裁单测用例清单（纯函数 + 队列状态机，JVM 可测）

**A. verdict 纯函数**（`arbitrate(L,C,W)`，输入三元组 → 期望 verdict；每行一例）：

| # | L | C | W | 期望 verdict | 守卫点 |
|---|---|---|---|---|---|
| U1 | 3 | 3 | 3 | IN_SYNC | 基线 |
| U2 | 3 | 3 | 4 | LOCAL_BEHIND | 云新下载 |
| U3 | 4 | 3 | 3 | UPLOAD_PENDING | 仅本地新≠冲突 |
| U4 | 4 | 3 | 5 | CONFLICT | 双边各有新 |
| U5 | 4 | 4 | 3 | IN_SYNC*（云落后视为已确认旧态，以下载仲裁为准） | 确认语义 |
| U6 | 0 | 0 | 0 | IN_SYNC（双无） | 新游戏 |
| U7 | 0 | 0 | 3 | LOCAL_BEHIND | 首次云接入 |
| U8 | 5 | 3 | 4 | CONFLICT | 跨多版仍冲突（不因差距大而静默） |
| U9 | 4 | 3 | 4*（W==L） | UPLOAD_PENDING（确认回填竞态：云端已是本档） | 幂等重传收敛 |
| U10 | L<C（非法态） | | | 判 IN_SYNC + 遥测上报（防御：确认写入先于本地序号写入的中断窗） | 不变量自愈 |
| U11 | 任意 | 任意 | —（W 未知，查询失败） | 保守退化 = 视 W==C 重算 U1/U3/U4（查询失败不得把 UPLOAD_PENDING 升级成"云无档"而静默上传） | 失败封闭性 |

**B. 队列状态机**（`UploadQueue` 每状态转移一例）：

| # | 前置 | 事件 | 期望 |
|---|---|---|---|
| Q1 | 空闲 | 入队保存 id=4 | 状态=待传，L=4，C 不变 |
| Q2 | 待传 | 上传 400001（冷却） | 指数退避重排，不改 L/C |
| Q3 | 待传 | 上传 400006（令牌失效） | 重取令牌后重试，不改 L/C |
| Q4 | 待传 | 上传 400007（并发） | 串行化单飞重试（§3.2 cloudOpLock 全局保留） |
| Q5 | 待传 | 上传成功（onArchiveUpdated） | C=4，出队，摘要缓存更新 |
| Q6 | 待传 | 上传成功但进程被杀（确认未落地） | 重启后仍待传，重传幂等（对应 S6） |
| Q7 | 待传×N（窗口内 3 次保存） | 合并 | 只保留最新 id=6 一次上传，L=6 |
| Q8 | 待传，手动保存再次触发 | 用户手动保存 | 合并为一次快照（去抖窗口，方案 §2 触发矩阵） |
| Q9 | 退避中 | 上限 reached（连续 N 次失败） | 熔断（复用 `recordSaveCircuitResult` 链），告警通道如实提示，不静默 |
| Q10 | 冲突待决（S10） | 上传条件满足 | 不自动上传，冲突 UI 优先 |

**C. 兜底守卫**：`compareVersions` 退役后其单测的处置（删除或转兼容闸）由 SR-2 拍板——
若保留"云档版本 > 本地版本拒绝加载"作为**兼容闸**（非"谁新"仲裁），须与 IN2 相容性说明（闸的判据是 App 版本不是进度新旧，不违 IN2）。

### 4.4 真机剧本（SR-3 硬门引用）

S1-S8 全套在两台真机（或一台真机 + TapTap 沙盒账号）实跑，证据 = 步骤录像/日志摘录；
Robolectric 不可替代（方案 §5.5）。单测清单 §4.3 与剧本矩阵 §4.2 的编号一一对应，
真机红 ⇒ 对应单测补失败路径回归。

## §6 结论汇总：Go/No-Go 与 IN5 红线建议

（待全部任务完成后回填）
