# 存档审计 §16 十项修复台账（2026-09-21）

> 来源：`docs/save-system-audit-2026-09-21.md` §16「若要修复，优先级建议」1–10 项。
> 用户 2026-09-21 指令「全部解决」。本文档是**逐项状态台账**：已解决项给 commit + 实测，
> 待拍板项给选项与推荐，待实施项给可直接开工的实施规格。
> 纪律：存档 schema / wire 面变更**单独走批**；每项独立 commit；先验证施工前提再动手。

---

## 0. 总览

| 序 | 项 | 状态 | 依据 / commit |
|---|---|---|---|
| 1 | §12-F 归档物理删弟子主表 / 战斗日志 + `slotIds` 漏 slot 6 | **部分解决**：slot 6 已修；"是否停止物理删主表"= 保留策略 → **待拍板** | `DATAARCHIVE_SLOTFIX` |
| 2 | §12-C UI 谎报"保存成功" | ✅ **已解决** | `b35f1d9fb` |
| 3 | §12-A heavy「先删后写」+ 超大行跳过 | **前提勘误**（不成立）+ 真缺口待实施 | §2.1 / §4.1 |
| 4 | §12-B 假 `.bak`（无历史/无轮转） | ✅ **已解决** | `b35f1d9fb` |
| 5 | §12-E `@ProtoNumber(162)` 冲突 + 守卫缺唯一性断言 | 待实施（**wire 面单独走批**） | §4.2 |
| 6 | 触发模型：补 `onStop`/`onTrimMemory`/事件驱动保存 | **待拍板**（产品决策） | §3.1 |
| 7 | §12-K 删档残留 + `clearSlotDataQuietly` 只删 2 表 | ✅ **已解决**（并补 4 张漏表） | `2ecb51e2b` |
| 8 | §12-D 空函数 `restoreFromBackupIfCorrupted` | ✅ **已解决** | `b35f1d9fb` |
| 9 | §12-G 读档后 boot 覆盖存档值 | **已逐条判归**：多数不该动；5 子项待拍板 | §2.2 / §3.2 |
| 10 | §14 死代码清理 | 勘察完成（**推翻 3 处审计前提**）→ 待实施 | §2.3 / §4.3 |

---

## 1. 已解决项（4/10，全部带守卫与实测）

### 1.1 §12-C UI 谎报"保存成功"（优先级 2）→ `b35f1d9fb`
`handleSaveResult` 里备份 `Failure` 只记指标、异常只 `Log.w`，**都不改写 result**
⇒ 调用方仍见 `isSuccess` ⇒ 弹"游戏保存成功"。改为后置步骤降级原因**全程带回**：
`SaveOperationStats.postSaveWarning` → `handleSaveResult` 返回带告警 result →
门面 `SaveResult.Success(data, warning)`（`map` 保留）→ UI 提示"游戏保存成功（备份未写入）"。
新增纯函数 `backupWriteDegradationReason(...)`（空 message 有兜底文案）+ 守卫 9 例。

### 1.2 §12-B 假 `.bak`（优先级 4）→ `b35f1d9fb`
旧：`.sav` 与 `.bak` 用**同一 payload** 写 ⇒ `.bak ≡ .sav`（只能防同一次写坏）。
新：`rotateCurrentSaveToBackup(...)` 在 `.sav` 被覆盖**之前**把当前有效 `.sav` 轮转为 `.bak`
（流拷贝 + fsync + rename，不会半写）⇒ `.bak` = **上一存档点**，回退可退回上一版本。
首次保存无前一版本 ⇒ 不产生 `.bak`（如实）；超限/失败 ⇒ `Skipped(原因)` 不阻断主保存。
守卫：`.bak` 逐字节等于上一次 `.sav`；`.sav` 损坏时从轮转 `.bak` 恢复出 **v1 内容**。

### 1.3 §12-D 空函数恢复（优先级 8）→ `b35f1d9fb`
`StorageFacade.restoreFromBackupIfCorrupted` 旧体只有一行日志却**不触发恢复**。
新：真委派 `StorageEngine.restoreFromBackup`（读 `.sav/.bak` → CRC → 迁移 → 二次校验 →
隔离 → **写回 DB** → 缓存），返回 `Boolean`；调用方按返回值如实记日志。

### 1.4 §12-K 删档残留（优先级 7）→ `2ecb51e2b`
两条路径清单漂移：`delete()` 清 29 表、`clearSlotDataQuietly` 只清 **2 表**（残留 27 表行）。
新：唯一实现 `StorageEngine.clearAllSlotTables(slot)`（单事务，32 DAO），两条路径共用，
并补删审计点名的 4 张带 slot 列漏表（`archived_battle_logs` / `archived_disciples` /
`overflow_mail_drafts` / `direct_mail_drafts`）。仅豁免 `change_log`（无 slot 列）。
守卫：静态扫描锁「`@Database` 槽位域 DAO 集合 − 豁免 **双向等于** 清理清单」（新增实体忘补删即红）。

---

## 2. 前提勘误（必须回写审计报告，防下一轮被误导）

### 2.1 #3「heavy 先删后写 ⇒ 可留半存档」**不成立**（实测）
审计称 `WriteOps` 的"先删 7 个前缀再增量写"在 `withTransaction` **之前**逐段 autocommit。
实测：该调用链位于 `StorageEngine.kt:646` 的外层 `database.withTransaction { writeAllDataToDatabase(...) }`
**之内** ⇒ 不存在"旧行已删、新行未写"的窗口。
**真缺口**：`StorageEngineHeavyDataOps.kt:165-173` 的"单行超 CursorWindow ⇒ 跳过该 key"分支
只 `Log.w`；且域表回填只在 `allRows.isEmpty()` 时触发，**单 key 缺失不回填**。
⇒ 施工方向改为「缺失 key 触发回填 + 跳过分支显式失败」，见 §4.1。

### 2.2 #9 逐条核实结论（Explore 勘察 + 复核）
| 审计列的点 | 实测判归 |
|---|---|
| 2 重建 `patrolSlots`（10→8 槽）、裁 `patrolConfigs` | **改存档值**（旧档槽位被裁）→ 需产品确认 |
| 4 截断 ALCHEMY/FORGE 槽数到当前建筑数 | **改存档值** → 需产品确认 |
| 5 读档即自动收割已完成槽位 | **改存档值**（玩家看不到"已完成"弹窗）→ 需产品确认 |
| 6 `gd.productionSlots ← repository.getSlots()` | 镜像/索引同步，**不该动** |
| 12 `placedBuildings` 归一化 / `activeSectId` 净化 / `guideCounters` 回填 | 幂等收敛，**不该动** |
| 13 拆除溢出/边界建筑并退款 | **改存档值**（退款比例是玩法）→ 需产品确认 |
| 15 `ensureGameDataIntegrity`：世界重生 / AI 弟子填充 / 商人招募重刷 | **改存档值**（整世界重生会丢驻军）→ 需产品确认 |
| 7/8 `merchantRefreshChances 0→1`、`spiritMineLastSettledMonth 0→当前月` | **正向兼容补丁**（防暴增）→ **不该动** |

### 2.3 #10 死代码复核：**3 处审计前提被推翻**（不可照删）
| 审计称 | 实测 |
|---|---|
| `SlotLockManager` 8 个方法"仅测试" | **活的**：`StorageEngine.save/delete/load`、两个周期任务都经 `core.lockManager.withWriteLockLive` |
| `FunctionalWAL` 零数据字节"不可重放" | **活的**：5 处生产调用（begin/commit/abort/recover） |
| `DataArchiver` 查询面零调用者 | **活的**：`.arc` 归档查询/恢复有生产调用者 |
**真死面**（生产 0 + 测试同批可删）：`ChangeTracker`（无注入者）、`SaveStorageImpl`
（`load()` 字面 `return null`）、`SchemaVersion.kt`、`SerializationQuota.kt`、
`StorageMetrics` 无 getter 的计数器、6 张零读者冗余表（`disciples_core/combat/equipment/
extended/attributes` + `disciple_compact`）、`GameStateRepository.loadFullState()`、
5 个 Repository 族（仅 `BridgeBindingsModule` 绑定）、`StorageFacade` 8 个死方法、
`StorageEngine.listSlots/forceDeleteSlotData`、`SaveFileManager.getBackupInfo`、
`loadHeavyDataForSlot`、`archived_*` 查询方法、`crypto/` 的**存档加密链**
（Argon2id/AES-GCM/HMAC/Merkle——**注意 `.secure_key` 网络签名链是活的，不可整目录删**）。

---

## 3. 待拍板清单（产品决策，不可工程单方面决定）

### 3.1 #6 保存触发模型（审计自陈"损失面最大，但属产品决策"）
背景：2026-07-25 主动移除自动存档（`docs/report-移除自动存档-接入云存档.md`），
现全项目仅 3 个**手动**保存入口；`onStop`/`onTrimMemory` **不保存**（弹窗明示"未保存的进度将会丢失"）。

| 选项 | 内容 | 代价 |
|---|---|---|
| A（推荐） | `onStop`（进后台）触发一次保存，挂 `NativeEngineFlag` 风格旗标**默认关**，先灰度 | 引入"后台写盘"行为；需产品确认是否违背"纯手动"设计 |
| B | 只做"事件驱动保存"白名单（战斗结束/年变/退出秘境），不碰生命周期 | 覆盖仍不完整，但语义可控 |
| C | 保持现状（不实现），仅把损失窗口写进产品文档 | 玩家仍可能丢几十个游戏年 |

### 3.2 #9 的 5 个子项（见 §2.2 表中标"需产品确认"者）
逐条需要玩法拍板：读档即收割是否给玩家可见弹窗 / 生产槽截断是否该退资源 /
世界重生是否该保留驻军快照 / 拆除退款比例 / 巡视槽 10→8 是否给补偿。

### 3.3 #1 的保留策略
`DataArchiveScheduler.archiveDeadDisciples` 会**物理删主表行**，归档行 `dataBlob=""`（不可还原），
且归档表全部查询方法零调用者（写了永不回读）。选项：
(a) 停止物理删主表（只标 `isAlive=false`，数据不丢但 `disciples` 无界增长）；
(b) 归档行保留完整 `dataBlob`（可还原，占空间）；
(c) 保持现状但把保留期从 180 天延长 + 补 UI 可查。
**slot 6 漏归档已按工程判断修复**（`(1..DEFAULT_MAX_SLOTS)`，见 `DATAARCHIVE_SLOTFIX`）。

---

## 4. 待实施项的实施规格（前提已验证，可直接开工）

### 4.1 #3 真缺口（`core:data`，一笔）
1. `StorageEngineHeavyDataOps.kt:165-173`：跳过分支改为**显式失败/告警上抛**
   （沿本批 `postSaveWarning` 同思路，不得只 `Log.w`）；
2. 回填判据从 `allRows.isEmpty()` 放宽为「**任一 heavy key 缺失**」，
   回填只补缺失 key（不覆盖已存在 key）；
3. 门禁：`StorageEngineHeavyDataOps` 相关用例 + `:core:data` 全量 + detekt。

### 4.2 #5 proto 号唯一性（**wire 面，单独走批**，一笔）
1. `GameData.kt:569`（`prisonerSpiritRootFilter`，`@ProtoPacked`）与 `:862`
   （`pendingTraitAdds`）同号 162 ⇒ **新字段让号**（`pendingTraitAdds` 是 v47 新加，改它）；
2. **兼容策略**：旧 `.sav` 里 162 号可能是任一方 ⇒ 读取侧需按"能解析成哪边"判别，
   或接受"旧档 pendingTraitAdds 丢失"（须显式登记并评估玩家可见后果）；
3. `ProtoNumberCoverageTest` 补**唯一性断言**（`groupBy { n }.filter { size > 1 }` 即红）
   —— 该守卫现只查"有没有标注"，报错文案却写"全局唯一"；
4. 门禁：`:core:data` + `:core:domain` + 组合门 + 存档回归（旧档样本读回）。

### 4.3 #10 死代码清理（跨模块，建议 3 笔）
按 §2.3「真死面」清单分组：① `core:data` 存储死面（ChangeTracker/SaveStorageImpl/
SchemaVersion/SerializationQuota/StorageFacade 8 方法/StorageEngine 2 方法/
SaveFileManager.getBackupInfo/loadHeavyDataForSlot/6 张冗余表 + 其 DAO/Entity 注册）；
② Repository 族 + `GameStateRepository.loadFullState` + `BridgeBindingsModule` 绑定；
③ `crypto/` 存档加密链（**只删加密链，保留 `.secure_key` 网络签名链**）+ StorageMetrics getter。
**每笔必须**：先 `grep` 复核"生产调用 0"，测试引用同批删，`:module:detekt` 单跑，
组合门 + `Diff*` 0 skip。

---

## 5. 门禁与残余

- 已实施两批实测：`:core:data` **733/0/0/15**（721 基线 + 本批 12）；`:core:data:detekt` 绿；
  `:core:data` + `:feature:game` `compileReleaseKotlin` BUILD SUCCESSFUL。
- **诚实残余**：engine→facade→UI 全链**无单元测试基建**（core:data 无 engine/facade 测试目录，
  构造 `StorageEngine` 需 8 个 fake）⇒ 已解决项以「纯函数映射 + 文件层语义 + 静态源码守卫」为证，
  全链集成测试登记为后续。
- 剩余 6 项（#1 保留策略 / #3 / #5 / #6 / #9 / #10）**未实施**，本文件 §3/§4 已给出
  拍板选项与可直接开工的规格。
