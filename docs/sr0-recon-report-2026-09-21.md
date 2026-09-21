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

## §3 云多档语义勘察（T3）

（待 T3 完成后回填）

## §4 双设备冲突剧本设计（T4）

（待 T4 完成后回填）

## §6 结论汇总：Go/No-Go 与 IN5 红线建议

（待全部任务完成后回填）
