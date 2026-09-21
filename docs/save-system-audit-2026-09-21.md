# 存档系统全量摸底勘察报告（Save System Audit）

> 日期：2026-09-21
> 勘察方式：只读代码取证，**未修改任何源文件**
> 取证范围：`android/` 全量（1870 Kotlin + 315 C/C++ 文件），排除 `build/` 产物
> 方法论：代码行为 > 实际调用链 > 实际文件/数据库操作 > 数据结构 > 命名 > 注释 > 文档
> 承重结论中 12 条由主 agent 亲自复核代码，非转述子代理结论
>
> **B19 后续处置（2026-09-21 晚补注，非原勘察内容）**：本报告 §5（145/146 行）指出的两列
> 死列 `game_data.battleTeam`（单数）/ `game_data.aiBattleTeams` 已由 **B19 批**清理——
> Room v51 → **v52** 迁移删列，实体字段与对应 TypeConverter 同步删除。
> 本文其余结论（含 §16 优先级建议 1–10）**均未处理**，仍为待办；v51 相关表述保留为
> 勘察时点事实（现库版本 = v52）。B19 施工卡见
> `docs/parallel-batches-w5/batch-B19-room-dead-columns.md`。

---

## 0. 执行摘要

**这个项目没有"存档系统"，只有一个"手动快照落库系统"。**

| 维度 | 实测结论 |
|---|---|
| 覆盖面 | **出奇地完整**——141 个 Room 列整对象装载，招募池/地形/RNG 流/种植到第几周目全部可恢复 |
| 触发模型 | **纯手动**——全项目仅 3 个可达保存调用点，全部要求玩家亲手点按钮 |
| 备份 | **假的**——`.bak` 与 `.sav` 写同一个 payload 字节串 |
| 加密 | **不存在**——存档明文落盘，全套加密子系统零调用者 |
| 崩溃安全 | **部分**——DB 事务原子，但 heavy 表与文件写在事务外，可留半存档 |
| 后台清理 | **与存档对打**——300s/600s 周期任务直接改 DB，会物理删除弟子与战斗日志 |
| 版本兼容 | **真实可用**——迁移链完整，新版本读旧档是可达路径 |

真正会让玩家损失进度的不是"某个字段没存"，而是"根本没存"。

**这是 2026-07-25 的主动设计变更**（`docs/report-移除自动存档-接入云存档.md` 记录移除自动存档、接入 TapTap 云存档）。代码取证独立确认该移除**真实且彻底**：`GameData.autoSaveIntervalMonths` 已 `@Ignore`+`@Transient`，Room v50 迁移已删列（`GameDatabaseMigrationsV50.kt:37,47,58`），`GameDatabase.kt:197` 自陈"纯手动存档设计"。**风险在于该决策把进度损失窗口从"最多 N 旬"变成"无上界"，且未配套任何兜底触发点。**

---

## 1. 项目存档系统实际架构

```
C++ gamecore（Mode.AUTHORITATIVE，模拟真相源，NativeEngineFlag.kt:38）
  │  ★ 全项目唯一原生磁盘写：Vulkan Pipeline Cache（VulkanBackend.cpp:1660-1721）
  │    —— 与游戏状态零关系
  │  ★ 不做任何存档 I/O：cpp/ 全量 grep（排除 build/、third_party/）证实
  │    gamecore/src/*.cpp 全部 11 文件 + GameCoreBridge.cpp + GameCoreJni.cpp 文件 I/O 命中数 = 0
       ↓ 每旬 nativeSettlePhase() → exportDirty()（protobuf 差异，仅内存）
GameStateStore（Kotlin 内存，权威镜像）
       GameEngineCoreAuthoritativeOps.kt:65 → :68 → StateSyncService.kt:404
       ↓ 仅在"玩家点保存"的那一刻被读取一次
SaveFacadeImpl.getStateSnapshot()（14 字段快照，SaveFacadeImpl.kt:78-126）
       ↓
StorageFacade.kt:234 save → StorageEngine.kt:122 save → :627 performFullTransactionSave
  ├─① Room 事务：xianxia_sect.db        ← ★ 读档真相源（仲裁点）
  └─② DB 事务【成功之后】SaveFileManager.atomicWrite → slot_N.sav + slot_N.bak
                                                          ← ★ 事后单向镜像，非独立存档
```

三条架构级事实：

1. **C++ 层没有任何状态只存在于原生而不在 Kotlin 存档里**（除 §11 列出的运行态）。双向同步链完整闭合：注入 `GameEngineCoreAuthoritativeOps.kt:178-206`、回写 `StateSyncService.kt:430 → :404`。存档格式与兼容性 100% 由 Kotlin 层保证。
2. **文件不是存档本体，是 DB 的单向镜像**。加载时只有 DB 判空/判损坏才回头看它（`StorageEngine.kt:271 → :278`）。
3. **存档数据源 = 引擎内存态**，不是 DB。每次保存是 `stateStore.gameData.value` 的全量快照覆盖写。

---

## 2. 存档入口（生产可达全集）

全仓 `storageFacade.save` grep 命中数 = 5（含 `StorageFacade.kt:241` 内部转发），**真实可达入口 3 个**：

| 函数 | 文件:行 | 调用者链 | 触发条件 | 保存内容 | 证据 |
|---|---|---|---|---|---|
| `performLocalSaveToSlot` | `SaveLoadViewModelSaveOps.kt:249` | `SettingsTab.kt:960` → `SaveOps:27 saveGame` → `:60` → `:187` | **设置页存档对话框"保存"按钮，手动** | 全量快照 | A |
| `performSynchronousSave` | `SaveLoadViewModelNewGameOps.kt:138` | `GameActivity.kt:772/780 startNewGame` → `:78-83 performInitialSaveForNewGame` → `:127` | 新游戏首存，失败重试一次；两次即 `:88` 报错并**中止开局** | 新档全量 | A |
| `performRestartSave` | `SaveLoadViewModelRestartOps.kt:277` | `GameActivity.kt:508` / `SettingsTab.kt:191` → `SaveLoadViewModel.kt:399 restartGame` → `:115` | 重开游戏。**顺序缺陷**：`:111 restartEngineAndReseed` 先重置引擎，`:115` 才落盘 → 写的是重置后的新世界，**旧档被覆盖，不是保护性预存** | 重置后状态 | A |
| `SaveStorageImpl.save` | `app/.../di/SaveStorageImpl.kt:37` | `CoreModule.kt:242` 绑定 Hilt，但**全仓无任何注入点** | — | **死代码**；其 `load()` 字面 `return null`（`:44`） | A |

云存档路径不写本地：`SaveOps.kt:31-34 saveToCloudViaSlot` → `CloudOps.kt:73 uploadSave`，仅 `:280 setCurrentSlot(0)`。

---

## 3. 读档入口

| 函数 | 文件:行 | 调用者链 | 触发条件 | 读取内容 | 证据 |
|---|---|---|---|---|---|
| `performLoadToSlot` | `SaveLoadViewModelLoadOps.kt:145` | `MainActivity.kt:585→:620 launchGame` → `GameActivity.kt:776 loadGameFromSlot` → `SaveLoadViewModel.kt:274` | 主菜单点槽位 / 游戏内"读档"按钮（`SettingsTab.kt:961`） | 全量 SaveData | A |
| `getSaveSlotsSuspend` | `SaveLoadViewModel.kt:170-183`、`MainActivity.kt:562-564` | VM 构造 / 每次进选档页 | 仅读槽位摘要，失败 `delay(500)` 重试一次后放弃 | `game_data` 列 | A |
| 云档下载 | `SaveLoadViewModelCloudLoadOps.kt:201 downloadSave` | `MainActivity.kt:595` → `GameActivity.kt:768` | 手动点云存档卡片 | **不落任何盘**，只进内存 | A |

- `SaveLoadLoadDelegate.kt`（含 `:109 load`）**全仓无实例化点** → 死代码。
- `GameActivity.kt:753 isGameAlreadyLoaded()` 提前 return，一次会话只 load 一次。
- 冷启动**不自动加载存档**，也**不自动拉云档**。

---

## 4. 实际存档文件 / 数据库清单

| 存储 | 类型 | 路径 | 创建/写入者 | 读取者 | 用途 | 证据 |
|---|---|---|---|---|---|---|
| `xianxia_sect.db` | Room v51 | `databases/` | `AppModule.kt:74`→`GameDatabase.create`；`StorageEngineWriteOps.kt`；`ProductionSlotRepository`(14 处写穿)；`MailRepositoryImpl` | `StorageEngineLoadOps`/`HeavyDataOps` | **唯一存档真相**，34 实体 | A |
| `slot_N.sav` | kotlinx-ProtoBuf + LZ4 + CRC32C 头 | `filesDir/saves/` | `SaveFileManager.kt:106-115`（tmp→`fd.sync()`→rename） | `readWithFallback:171-209` | DB 兜底镜像 | A |
| `slot_N.bak` | 同上 | 同上 | `SaveFileManager.kt:138`（**同一 payload 变量**） | 同上 | **名不副实的"备份"** | A |
| `slot_N.deleted` | tombstone 标记 | 同上 | `:319` | `LoadOps:34-41` | 删档标记 | A |
| `slot_N.pre_migrate_backup.vK` | DB 文件级复制 | `databases/` | `GameDatabase.kt:453-504`（先 `wal_checkpoint(TRUNCATE)`） | `restoreFromBackupIfNeeded:731-754` | 迁移前还原，`.restore_attempted` marker 防死循环 | A |
| `*.quarantine.{ts}` | DB 隔离副本 | `databases/` | `StorageEngine.kt:374` | 人工排查 | DB 判损坏时隔离 | A |
| `wal_v4/transactions.wal` | 自研 KV 日志 | `filesDir/` | `FunctionalWAL.kt:190-197` | `recover:320-367` | **只记 BEGIN/COMMIT 标记，零数据字节 → 不可重放** | A |
| `archives/*.arc` + `archive_index.pb` | 二进制归档 | `filesDir/` | `DataArchiver.kt:153-155` | `queryBattleLogs:242`/`restoreBattleLogs:252` **零生产调用者** | 写了永不回读 | A |
| `game_prefs` | MMKV 单实例 | — | 10 个 key（`GamePreferences.kt:135`） | — | **设置/UI/遥测，零游戏进度** | A |
| `xianxia_session` | EncryptedSharedPreferences | — | `SessionManager.kt:130,197` | — | 登录/合规/音效画质/性能模式 | A |
| `device_secret_prefs`/`secure_key_prefs` | SharedPreferences | — | `SecureKeyFileStore.kt:246` | — | 设备绑定与密钥哈希 | A |
| `.secure_key[.bak/.tmp]` | 设备绑定密钥 | `filesDir/` | `SecureKeyFileStore.kt:269-312` | `RequestSigner.kt:172`、`SecureHttpClient.kt:407` | **仅网络签名，与存档零关联** | A |
| `crash_logs/*.txt` | 文本 | `filesDir/` | `CrashRecoveryEngine.kt:270-311` | 上报 | 崩溃台账，**与进度恢复无关** | A |
| `cloud_save_temp.dat` | 临时 | `cacheDir/` | `TapCloudSaveManager.kt:238` | 上传后 `:262` 删除 | 云传输中转 | A |
| Vulkan pipeline cache | 二进制 | `cacheDir/` | `VulkanBackend.cpp:1718` | `:1660` | 原生唯一磁盘写，着色器编译缓存 | A |

**assets 全部只读**：`AssetSource.kt:24` 接口面无任何写路径；`data/game-data.json` 由 Kotlin 读后经 `nativeSetGameData` 字符串注入，C++ 从不打开 assets。

**schema 导出**：`core/data/schemas/.../` 有 43 个（2, 11–51），**v3–v10 缺失**；`app/schemas/.../1.json` 是 DB 从 `:app` 迁到 `:core:data` 的遗留。

---

## 5. 实际持久化数据清单（字段级）

`GameData` 共 **146 个 `var`**、`@Ignore` **5 个**、Room **141 列**（与 `schemas/.../51.json` 完全吻合）。

**关键架构巧合（本项目最大的运气）**：`GameData` 本身就是 Room `@Entity`，且加载时**整对象替换**（`GameStateStoreImpl.kt:1354 _gameDataFlow.value = backfillEventSequenceIds(gameData)`，不逐字段挑选）。因此**只要不是 `@Ignore`，就自动走完整对称闭环**。

| 数据 | 内存位置 | 保存位置 | 加载位置 | 状态 |
|---|---|---|---|---|
| 玩家 ID / 宗门名 / 灵石 / 中高阶灵石 / 灵草 / 宗门修为 | `GameData.kt:105-157` | `game_data` 列 + `.sav` | 整对象装入 | 已确认持久化 |
| 游戏时间 年/月/旬 | `GameData.kt:125,129,132` | `game_data` 列（**Int，非 epoch，无时区**） | 同上 | 已确认持久化 |
| 弟子全量（属性/境界/装备/扩展/战斗） | `SaveData.disciples:60` | `disciples` 表(`WriteOps:176`) + `.sav` | `HeavyDataOps:240` | 已确认持久化 |
| 弟子分片 6 表 + compact | `WriteOps:177-187` | `disciples_core/combat/equipment/extended/attributes`+`disciple_compact` | **零读取者** | 保存存在但加载未发现（纯写放大） |
| 装备/功法 stack+instance、丹/材/草/种/储物袋 | `SaveData.kt:61-70` | 各自表(`:195-212`) + `.sav` | `:241-249` | 已确认持久化 |
| 建筑 / 道路 / 地形瓦片 / 地图种子 | `GameData.kt:384,390,762,880,892` | 列 + `.sav` | `Boot:404-412` **存档段优先、不重算** | 已确认持久化 |
| 生产槽（炼丹/锻造，含 `completionMonth/Phase`） | `ProductionSlot.kt:54-92` | `production_slots` 表 + `gd.productionSlots` 镜像列（双写） | **以表为准，镜像被覆盖**（`LoadDataOps:111`） | 已确认持久化（镜像通道废弃） |
| 世界地图 30 宗门 / AI 宗门弟子 / 已探索 / 侦查 / 功法熟练 / 招募池 | `GameData.kt:206-274` | `game_heavy_data` 7 个 key | `HeavyDataOps:110-136` | 已确认持久化 |
| 秘境会话与冷却 / 血炼进行中 / 已刷未确认天赋 / 巡视战报 | `GameData.kt:595-613,675-695,425` | 列 + `.sav` | 整对象装入 | 已确认持久化 |
| 结盟 / 附庸契约 / 外交关系 / 宗主国 | `GameData.kt:430-440,718` | `game_data` 列(`alliances` 等) + `.sav`(tag38/39) | 整对象装入 | 已确认持久化 |
| 宗门战争（警告/冷却/战史/保护期） | `GameData.kt:728-743,547-554` | 列 | 同上 | 已确认持久化 |
| 商人/市集/自动采购/刷新次数与年份锚 | `GameData.kt:234-269` | 列 | 同上 | 已确认持久化 |
| 世界关卡刷新锚 / 灵矿结算锚 / 广纳门徒付费锚 | `GameData.kt:316,370,488` | 列 | 同上 | 已确认持久化 |
| 政策与自动招募/装备/功法筛选、年俸表 | `GameData.kt:483,569-665,171-190` | 列 + `sect_policy_state`(零读者) | 整对象装入 | 已确认持久化 |
| 任务 / 兑换码账本 / 邮件账本 / 等级领取记录 | `GameData.kt:559-562,527-535` | 列 | 同上 | 已确认持久化 |
| 战斗编队 `battleTeams`/`usedTeamNumbers` | `GameData.kt:502-509` | 列 | 同上 | 已确认持久化 |
| 天道试炼 / 签到 / 消息栏事件 / 引导进度 | `GameData.kt:702-708,748,753-756` | 列 | 同上 | 已确认持久化 |
| 年度报告 17 族 | `GameData.kt:769-847` | 列 | 同上 | 已确认持久化 |
| 玉符余额 / 当日额度 / 日锚 / 周期累计毫秒 | `GameData.kt:292-307` | 列（**墙钟 epoch**） | `JadeSymbolService:458-508` | 已确认持久化 |
| RNG 11 分区流态 | `GameData.rngStates:320` | Room 列 + proto(98) | `game_core.cpp:603 restoreStates` | 已确认持久化（**旧档缺键分区不可复现**，见 §12-H） |
| 邮件 | `MailEntity` | **仅 Room `mails` 表**（不在 `.sav`、不在 14 字段快照） | 实时 Flow | 已确认持久化（**唯一不可回滚通道**） |
| 战斗日志 | `SaveData.battleLogs:71` | `battle_logs`+`archived_battle_logs`+`.arc`+`.sav` | `:250` | 已确认持久化（保存前按 `maxBattleLogs` 裁剪；且被后台修剪，见 §12-F） |
| `SaveData.alliances` | `SaveData.kt:79` | `.sav` tag38 | `loadData(alliances=…)` **形参在函数体内从未引用** | 保存存在但加载未发现（**但无玩家影响**：`game_data.alliances` 列单独走通） |
| `battleTeam` | `GameData.kt:494` | **Room 有列**（`51.json` columnName 已直接验到） | 全仓无任何业务写入点，恒 null | 已确认不持久化（死列） |
| `aiBattleTeams` | `GameData.kt:522` | Room 有列 + `@Transient` 不入 `.sav` | 全仓无写入点 | 已确认不持久化（死列） |
| `autoSaveIntervalMonths` | `GameData.kt:161` | `@Ignore`+`@Transient`，v50 已删列 | 无 | 已确认不持久化（**自动存档功能已被移除**） |
| `AI 妖兽跳过冷却/遭遇战挂账/玩家锁定/直攻目标` | `GameData.kt:443,451,459,469`（4 个 `@Ignore`） | 无 | 无 | 已确认不持久化（见 §11） |
| `stacksSerialized` / `SaveData.version` | `SaveData.kt:72,56` | 仅 `.sav`，DB 无对应列 | DB 路径硬编码 `true`（`HeavyDataOps:226`） | 保存存在但加载未发现 |
| `recipes` 表 | `WriteOps:230-232` | 由 `unlockedRecipes` 派生写入 | **不在 `loadAllEntities`** | 保存存在但加载未发现（镜像列已覆盖，无影响） |
| `building_slots` | `ProductionDaos.kt:15` | **无任何 insert** | `WorldRepositoryImpl`（死代码） | 已确认不持久化（恒空表） |
| 音效 / 画质 / 性能模式 | `GameData.kt:854,860` + `GameEngineCore:221,226` | 列 + `SessionManager` SP | 引擎字段为镜像 | 已确认持久化（双写） |

**计算属性（无 backing field，不参与持久化，属正确设计）**：`displayTime:894`、`spiritStoneCount:904`、`isPlayerProtected:910`、`playerProtectionRemainingYears:917`、`worldMap:926`、`buildings:934`、`economy:941`、`organization:954`、`exploration:966`。

---

## 6. 保存触发时机（实测表）

| 触发事件 | 是否保存 | 实际代码 | 证据 |
|---|---|---|---|
| **玩家点"保存"按钮** | **是** | `SaveOps.kt:249` | A |
| 新游戏 | 是 | `NewGameOps.kt:138` | A |
| 重开游戏 | 是（**覆盖式**，先重置引擎后落盘） | `RestartOps.kt:111 → :277` | A |
| **游戏退出（"退出游戏"按钮）** | **否** | `SettingsTab.kt:286→:179→:340-355` 只弹确认框，弹窗原文「未保存的进度将会丢失」 | A |
| **Activity onPause** | **否** | `GameActivity.kt:798-804`：`pauseForBackground()` + `backgroundTaskScheduler.pause()`；`SaveLoadViewModel.kt:381-385` 明示不触发存档 | A |
| **onStop / onDestroy** | **否** | `GameActivity.kt:820-831 / 1023-1059`（仅日志 + 解绑 Service） | A |
| onTrimMemory / onLowMemory | 否 | `GameActivity.kt:1061-1088` 只释放 Bitmap/图集缓存 | A |
| onSaveInstanceState | 否 | `:791-796` 只 `putInt(KEY_CURRENT_SLOT)` | A |
| ViewModel.onCleared | 否 | `SaveLoadViewModel.kt:446-461` 只 `withTimeout(2000)` **等**在飞任务，不发起 | A |
| **自动保存定时器** | **不存在** | 全仓 `WorkManager`/`AlarmManager`/`ScheduledExecutor`/保存链 `delay(` 零命中；`AlarmWatchdogReceiver.kt` 全文无 save | A |
| **每旬/月/年 tick 推进** | **否** | `GameEngineCore.kt` 与 `GameEngineCoreAuthoritativeOps.kt` 全文 grep `save\|persist\|flush\|insert\|upsert` **零命中**；`:70 applyDirtyFromNative` 只写内存 | A |
| 战斗结束 / 任务完成 / 建筑升级 / 招募 / 突破 / 收菜 / 生产结算 / 退出秘境 / 邮件领取 / 月变 / 年变 | **全部否** | 同上（A 级负证据：全仓 `storageFacade` 引用清单已穷举） | A |
| 返回主菜单 / 登出 / 切槽位 | 否 | `GameActivity.kt:505,648,635` | A |
| 后台被系统杀 | **无任何兜底** | `XianxiaApplication.kt:466-468 onTerminate` 真机不被调用 | A |
| 删槽 | 只删不存 | `MainActivity.kt:607 storageFacade.delete(slot)` | A |

**损失窗口无上界**：从上次手点保存到进程死亡之间的全部推进量归零，可跨几十个游戏年。

---

## 7. 加载触发时机

```
XianxiaApplication.onCreate:100
  → CrashRecoveryEngine.initialize:323 → VulkanPolicy:325 → 安全模式判定:329/333
  → MMKV.initialize:360/372 → crashReporter.initialize:381
MainActivity 隐私同意 → :379 proceedAfterPrivacyConsent
  → :388-417 初始化重试环（maxRetries=3，storageFacade.initialize()，间隔 delay(500*n)）
  → StorageFacade.initialize 内部序：startMaintenance:160 → saveFileManager.initialize:162
      → cleanupOrphanedTmp/cleanExpiredBackups:166-167 → getSlotMetadata(1) 完整性探测:186
主菜单（★不自动读档） → 用户手点槽位 → launchGame(EXTRA_SLOT) → GameActivity.loadGameFromSlot
  → StorageEngine.load:249
      ① :264 tryCacheLoad（内存缓存，含迁移+双校验）
      ② :271 loadFromDatabase（game_data 141 列 + game_heavy_data 7 key + 20+ 实体表）
      ③ :278 restoreFromBackup —— 仅当 DB 返回 null 或判 Corrupted
          → readWithFallback(.sav → .bak) → 反序列化 → 迁移 → 二次校验
          → quarantineCurrentDatabase → 反向回写 DB(:378)
      ④ :284 SLOT_EMPTY
  → stateStore.loadFromSnapshot（整对象替换）
  → ★之后★ performLoadBoot → BootSequenceController.boot
  → :131 syncNativeBaselineAfterLoad → C++ 全量 importStateJson（含 rngStates restore）
```

- 冷启动**不自动加载**、崩溃后**不自动恢复**，均须用户手点。
- `GameActivity.kt:753` 防重复加载，一次会话只 load 一次。
- 云档仅手动下载，且**下载后不落盘**（`CloudOps.kt:280-281` 只进内存）。
- 维护周期任务真实拉起（B 级链：`MainActivity.kt:393` → `StorageFacade.kt:158` → `StorageEngine.kt:588` → `StorageMaintenanceFacade.kt:23-27`）：MemoryGuard **10s**、DataPruning **300s**、DataArchive **600s**。

---

## 8. 存档版本机制

**存在 5 处版本号，互不联动**：

| 位置 | 值 | 读时是否比较 |
|---|---|---|
| `.sav` 外层头 `SaveFileFormat.kt:36` | `0x0101`（容器格式） | **是**，`readAndVerify:390-436` 校验 ∈{0x0100,0x0101} |
| 内层帧头 `SerializationTypes.kt:7` | `3` | **否**，从不比较 |
| `GameData.saveVersion:541`（Room 列 + proto 100） | `CURRENT` | **是**，既写又读——**这才是真正的数据版本**。保存前盖章 `StorageEngine.kt:215-223` |
| `SaveData.version:56`（App 版本串） | 恒写 | 本地读路径**从不比较**；云比对用 metadata JSON 的 version |
| Room `DATABASE_VERSION` | `51` | 仅用于 SQLite user_version 迁移还原判定 |

- **Room 版本与数据版本零校验代码**（A）。
- **迁移机制真实存在**：`SaveDataVersionMigrator.kt:47-116` 实际只有 v0→1（修为 ÷10）、v1→2（`acquainted=true`）；`>2` 或 `<0` → Rejected，调用方放弃数据走备份或失败。
- **三条路径都接了迁移管线**：DB（`LoadOps:175-183`）、备份（`migrateRestoredData:257-264`）、云（`CloudOps:128,252`）→ **"新版本读旧档"是真实可用路径**（B 级完整链）。
- Room 迁移：49 条全链式（`MIGRATION_2_3 … MIGRATION_50_51`）**无缺环**；破坏性回退 `.fallbackToDestructiveMigrationFrom(1)` **仅对 v1**；`allowMainThreadQueries` 全仓无调用。
- **生产代码不存在 `onValidateFailed`**（全仓 grep 仅命中测试类文件名）。`RoomDatabase.Callback` 只有 `onCreate`（PRAGMA）与 `onOpen`（`PRAGMA optimize` + `verifyAndRecoverDatabase`）。
- `verifyAndRecoverDatabase:649-675` 实际行为：跑 `PRAGMA integrity_check`、数行数，失败**只 `Log.wtf`，不做任何恢复**（`:657-660` 注释自陈交由 StorageEngine 备份恢复）。真正的还原在建库**之前**：`backupDatabaseForMigration:453-504` + `restoreFromBackupIfNeeded:731-754`（含 `.restore_attempted` marker 防"恢复→崩溃"死循环，200MB/64 行上限）。

---

## 9. 异常情况处理

### 保存失败

| 场景 | 实际行为 | 证据 |
|---|---|---|
| 一般异常 | 重试 `maxRetryCount`=2 次；`:237` OOM 短路 | A |
| DB 事务失败 | `withTransaction` 回滚 + `:682-684 abortWalQuietly` 后重抛 | A |
| **`writeAllDataToDatabase` 恒 return success**（`WriteOps.kt:61`） | 使 `StorageEngine.kt:649 writeResult.isFailure` 及其"事务已回滚，DB 保持旧数据"注释**成为不可达分支** | A |
| 抛异常的失败 | 路径（`:159-175`）**早于** `:151 recordSaveCircuitResult` → **不计入熔断器** | A |
| 备份写失败 | 只 `recordBackupFailure()`，**不改写 result** → 见 §12-C UI 谎报 | A |
| `.bak` rename 失败 | 只 `Log.w(非阻断)`，仍返回 success | A |
| payload 超 `MAX_BACKUP_SIZE_MB`=100 | **只跳 `.bak`，不拦 `.sav`** | A |
| 保存失败后的"恢复" | `SaveSupport.kt:172-189` else 分支读 `readWithFallback` 后打日志"从备份恢复数据成功"，**payload 被完全丢弃**，无回写无恢复，纯日志 | A |

### 加载失败

| 场景 | 实际行为 | 证据 |
|---|---|---|
| 文件不存在 | → `SLOT_EMPTY`（`StorageEngine.kt:284`） | A |
| CRC 校验失败 | `readAndVerify` 返回 null → `.bak` 回退（`readWithFallback:171-209`）→ 均坏返回 CORRUPTED | A |
| protobuf 未知字段 | 按 wire 规范**静默跳过** | A |
| 缺字段 | 取类定义默认值（`encodeDefaults=false` 下，等于默认值的字段本就不写字节） | A |
| 必填字段缺失/类型不符 | 抛异常 → 转旧格式尝试（`OldSaveFormatDeserializer.kt:32-62`，无压缩无校验和帧，经 JSON 桥）→ 双双失败抛 `SerializationException` | A |
| DB 任何异常 | `LoadOps:165-168` 返回 null → 退化为文件恢复 → **并用文件整体覆写 DB**（`:378`，旧档可能覆盖新档，仅靠 `:374` 隔离缓解） | A |
| 版本不匹配（`saveVersion>2`） | `SaveDataVersionMigrator` Rejected → 放弃该数据源，走备份或失败 | A |
| 部分数据缺失（heavy 单行超 CursorWindow） | `HeavyDataOps:166-173` **跳过该 key** → 见 §12-A 永久丢失路径 | A |
| 校验可修复（Repaired） | **保存路径**：替换后持久化（`StorageEngine.kt:195-204`）。**加载路径**：仅入缓存不写库（`LoadOps:117-124 persisted=false`；`SaveValidatorFixes.shouldPersistRepair` 零调用者）→ 同一份脏档每次读都重修一遍 | A |
| 判为 Corrupted | `restoreFromBackup` **真实触发**（`LoadOps:129`），含二次校验（`CorruptedResultHandler.validateRestoredData:32-56`）+ 隔离 + 回写 | A |
| 吞异常返回默认值 | `StorageFacade.kt:331/348/353/366`、`StorageEngine.kt:582-584` | A |
| 初始化 3 次全败 | 见 §12-J 静默放行 | A |

### 写入安全性

`.sav` **确为** tmp → `fos.fd.sync()` → rename（`SaveFileManager.kt:109-115, 372-382`），失败回退 delete+rename，兼容 FAT32/exFAT。**不是直接覆盖**。

存在：`magic 0x5853`/`"XSBK"`、格式版本、CRC32C（payload）、SHA-256（帧内）、长度、tmp/fsync/rename、pre_migrate DB 副本、quarantine。
**不存在**：真正的历史备份（§12-B）、应用层数据 WAL（无可重放字节）、轮转（`maxBackupVersions` 全仓零读取）。
**写入但从不校验**：外层头 flags、长度字段（且语义与注释矛盾，实存压缩后长度）。

**原子性缺口**（决定崩溃是否留半存档）：
1. `game_heavy_data` 的 7 次 `deleteByKeyPrefix` + `upsertAll` 在 `withTransaction` **之前**逐段 autocommit（`WriteOps:50-51`）。
2. `.sav`/`.bak` 写在 DB 提交**之后**。
3. `change_log` 插入也在提交后。
→ 崩溃可留下"heavy 已清空但实体未写"或"DB 已提交而 `.sav` 未落"。
4. 嵌套事务（`Engine:646` 外层 + `WriteOps:55` 内层两处 `Room.withTransaction`）是否并入同一事务：**无实际代码证据确认，需实跑验证**。

### 并发保护

`SlotLockManager`：save/load/delete/pruning/archive 五处获取，每槽一把 Mutex。**`withReadLockLight:74` 与 `withWriteLockLight:95` 是同一把锁**（`:79/:100` 均 `mutex.withLock`）→ "读锁"不并发，源码注释 `:66-70` 诚实承认。
`load` 持读锁内调 `performFullTransactionSave`，后者不重新获取槽锁 → **无重入死锁**。
**未加锁的裸读写**：`hasData:465`、`getSlotMetadata:479`、`getSaveSlots:519`、`forceDeleteSlotData:577`。
`globalMutex` 生产无使用。

---

## 10. 多数据源问题

仲裁顺序**硬编码**于 `StorageEngine.load`：① 内存缓存 → ② Room → ③ `.sav/.bak`。缓存命中还要过迁移+双校验，否则回落 DB。

| 状态 | 重复落点 | 加载以谁为准 |
|---|---|---|
| **弟子** 8 处 | `disciples` + 6 张 `disciples_*`/`compact`（零读者） + `game_heavy_data`(2 key) + `.sav` + 内存缓存 + 云档 | ①② |
| **GameData** | `game_data`(141 列轻型) + `game_heavy_data`(7 key) + `.sav`(全量含重型) + 5 张 domain state 表 + `save_slot_metadata`(零读者) + 引擎内存 | ①②，`.sav` 仅 DB 空时救活 |
| **productionSlots** | `gd.productionSlots` 镜像列 + `production_slots` 表 + `.sav` | **表为准，镜像被 `LoadDataOps:111` 覆盖** |
| **战斗日志** 4 处 | `battle_logs` + `archived_battle_logs` + `*.arc` + `.sav` | ①②；三级截断（`SaveSupport:94` 上限 / `DataArchiveScheduler:21` hot 200） |
| **槽位摘要** 5 处 | `game_data` 列 / `save_slot_metadata` / `.sav` 头 / 云 extra / MMKV `cloud_save_info` | 各自展示，互不校验 |
| **音效/画质/性能模式** | `game_data` 列 + `SessionManager` SP + 引擎内存镜像 | SP 为设备级真源 |

**一致性风险**：
- `.sav` 永远只是 DB 的事后镜像，两者**非原子**；`.sav` 至多比 DB 旧一个保存周期，但也可能是**上一个版本**的镜像（DB 成功后 `.sav` 写失败仅记 metrics）。DB 被误判 Corrupted 时旧 `.sav` 会**静默回滚玩家进度**，仅留 `.quarantine.{ts}` 供人工排查。
- `save_slot_metadata` 被 `WriteOps.kt:164,165` **连续重复写两次**（同事务冗余 UPSERT）。
- `GamePreferences.migrateInto:145-160` 把 **8 个旧 SP 文件平铺进同一 MMKV 实例、无命名空间**，`max_save_size`（StorageConfig 默认 50MB vs SaveLimitsConfig 默认 100MB）、`max_battle_logs`（500 vs 1000）**同名 key 被两个配置类以不同默认值读写，后迁移者覆盖前者**；`Set` 类型跳过迁移却仍 `clear()` 旧文件（当前无 `putStringSet` 写点，未触发）。
- 邮件是唯一"只在 Room、不在 `.sav`、删档不走同一清理路径"的通道 → **不可回滚**。

---

## 11. 未持久化的重要运行时数据

### 先给一条反直觉的干净结论（逐 key 穷举所得）

**MMKV / SharedPreferences 里不存在任何游戏进度数据** —— 无灵石、无弟子、无解锁、无剧情、无槽位的 `put*` 调用。KV 与存档**既非重复保存、也非唯一存放**，进度只有一条链（A 级，基于全仓 `put*` 穷举）。

全部 10 个 KV key：`first_{user}_{event}`(遥测)、`personalized_ads_enabled`(设置)、`oem_guide_shown`(UI)、`exact_alarm_prompted`(UI)、`archive_uuid`(云句柄)、`cloud_save_info`(云摘要只读缓存)、`cleanup_done`(一次性标记)、`last_upload_date`/`last_uploaded_power`(遥测节流)、以及 4 个**无写者只读**的配置 key。
唯一含游戏数值的是 `cloud_save_info`（`spiritStones`/`discipleCount`/`gameYear`/`sectName`），但它是**只读展示缓存、从不回流引擎**。

### 未持久化清单（按"玩家会不会觉得是 bug"排序）

| # | 状态 | 持有者 | 重启后行为 | 影响 | 证据 |
|---|---|---|---|---|---|
| 1 | **上次所在页签** | `GameStateStoreImpl.kt:123 activeTab = "OVERVIEW"` | **恒回首页**，每次重开要重新导航。`activeDialog:126`、`activeSubDialogs:129`（引擎用它解析 `FocusDomain`，影响 LOD/暂停域）同丢 | 高 | A |
| 2 | **游戏倍速** | `GameTimeClock.kt:48`；**`SaveService.kt:66` 直接硬编码 `gameSpeed = 1`**（已亲自验到） | 2× 玩家每次回到 1× | 中高 | A |
| 3 | **筛选/排序/选中** | `DisciplesTab.kt:43-48`、`WarehouseTab.kt:153-155`、`MessageBarHost.kt:33-35` | 全清 | 中高 | A |
| 4 | **当前游玩槽位** | `StorageFacade.kt:139` / `StorageEngine.kt:117` 纯内存 `MutableStateFlow(1)`；进程存活期靠 Bundle（`GameActivity.kt:794`→`:726`） | 无"继续上次槽位"概念，兜底回 slot 1（`SaveOps.kt:27 ?: 1`） | 中 | A |
| 5 | **5 个瞬态队列** | `GameStateStoreImpl.kt:231-240`：`pendingBattleResult`、`pendingNotification`/`notifications`、`pendingBattleRewardCards`/`rewardCardQueue`、`pendingBeastAttacks`、`pendingMarriageProposals` | 清空，且 `:1413 clearTransientQueues()` **主动清**（防换档幽灵弹窗），且不重算 | 中——**后果已在结算中发生，丢的是玩家响应入口**（妖兽来袭选择窗、战斗结算窗） | A |
| 6 | AI 妖兽跳过冷却(剩余旬数)/遭遇战挂账/玩家锁定/直攻目标 | `GameData.kt:443,451,459,469`（4 个 `@Ignore`） | 清空，注释自认"读档后自动清空" | 低-中（AI 行为基线漂移一旬；玩家"锁定妖兽"体感为没记住） | A |
| 7 | 兑换码限流三级计时 | `RedeemCodeManager.kt:98,355`、`RedeemCodeRateLimitOps.kt:27,131` | 冷却清零（`usedRedeemCodes` 本身已持久化，重复兑换仍防得住） | 低——**可被绕过的防刷，对玩家无害对运营有害** | A |
| 8 | `Rng systemSeed` | `GameRngManager.kt:29 = System.currentTimeMillis()` | **读档路径不调 `initSystemSeed`**（全仓唯一调用点 `LoadDataOps:265` 仅在新游戏）→ 旧档缺键分区按当前挂钟重播 → 跨进程不可复现 | 仅命中升级前旧档 | A |
| 9 | 旬内墙钟零头 | `GameTimeClock.kt:81 accumulatedGameMs` / C++ `engine_loop.h:67` | 最多丢 <1 旬 | 可忽略 | A |
| 10 | 天道试炼战斗演出 | `HeavenlyTrialViewModel.kt:48-59`（参战名单/结果/时长） | **战绩 `heavenlyTrialState` 已存、过程画面不存** → 不存档退出则该场退回上次存档点 | 中 | A |
| 11 | 正在拖动到一半的建筑 | `GameViewModel.kt:348 _movingBuildingInstanceId`、`:573/:576` | 丢（`placedBuildings` 只在事务提交后写，语义合理） | 低 | A |
| 12 | UI 红点/消息已读游标 | — | **未发现任何承载字段**（全 `GameData` 无 `lastRead/seen/unread/redDot`；邮件 `isRead` 是 Room 列，已持久化） | 未发现证据 | A/C |
| 13 | 派生态（无需持久化） | 宗门战力/弟子聚合(`:79/:89`)、ECS world、`heavyDataLoaded` 标志、看门狗/反冻、AI 热控分批、`pendingViewEvents_`、dirty 基线 | 重算/重建 | 无 | A |

补充硬证据：**`grep -c rememberSaveable feature/game/src/main` = 0**（已亲自执行）。整个 feature 层一处 `rememberSaveable` 都没有，所有界面级 UI 态都是 `remember { mutableStateOf(...) }` —— 不但进程重建必丢，连 Composable 离开组合范围都丢。

---

## 12. 已持久化但存在恢复风险的数据（A–K）

### A. `exploredSects` / `scoutInfo` —— 唯一"永久丢失且无再生源"的字段对
`WriteOps.kt:65-78` 每次保存**先全删 7 个 heavy 前缀再增量写**；`HeavyDataOps.kt:166-173` 读档时单行超 CursorWindow 会**跳过该 key**。若某 key 连续两次保存都超限：旧行已删、新行未写成 → **侦查/探索情报永久消失**。建议方向（未改动）：写成功后再删。（A）

### B. `.bak` 根本不是备份 —— 亲自读代码确认
`SaveFileManager.kt:106 val payload = serializeAndCompressSaveData(saveData)`，step 3 用它写 `.sav`，step 5（`:138`）用**同一个 `payload` 变量**再写 `.bak`。**`.bak ≡ .sav`，无历史、无轮转**（`StorageConfig.kt:51 maxBackupVersions` 全仓零读取）。它只能防"同一次内容写坏"，**防不住内容本身错、防不住误覆盖、防不住逻辑 bug**。头注释 `:26`「前一有效备份」、`:88`「复制 .sav → .bak（保留历史快照）」与代码不符。（A）

### C. UI 谎报"保存成功" —— 亲自读代码确认
`StorageEngineSaveSupport.kt:146-163`：备份 `StorageResult.Failure` → 只 `recordBackupFailure()`；`Exception` → 只 `Log.w(非阻断)`。**两条都不改写 `result`**。于是 `SaveOps.kt:252 saveResult.isSuccess` → `:259 showSuccess("游戏保存成功")`。
**Room DB 事务成功而 `.sav` 从未落盘时，玩家看到"游戏保存成功"。**（A）

### D. 名为恢复、实为空函数 —— 亲自读代码确认
`StorageFacade.kt:371 restoreFromBackupIfCorrupted` **函数体只有一行 `Log.w`**（`:372`），无任何恢复代码，却被 `SaveLoadViewModelRestartOps.kt:295` 当真恢复手段调用。其 `:373-375` 注释声称的回退确实存在于 `StorageEngine.load`，但**本函数本身不触发它**。（A）

### E. `@ProtoNumber(162)` 重复 —— 亲自 grep 确认
`GameData.kt:572`（`prisonerSpiritRootFilter: Set<Int>`，`@ProtoPacked`）与 `GameData.kt:865`（`pendingTraitAdds: List<PendingTraitAdd>`）**共用 tag 162**，且两者 wire type 均为 length-delimited → **解码不报错但语义抢占**。本地 DB 路径不受影响；**`.sav`/`.bak` 恢复与云存档路径可能读出错误集合**（玉符"已刷未确认"天赋产物消失、可能被重复扣玉符；俘虏灵根筛选可能被污染）。
漏网原因：守卫测试 `ProtoNumberCoverageTest.kt:112-125` 只查"有没有标注"、**不查唯一性**，尽管 `:276` 报错文案写着"全局唯一"。（B；影响面局部）

### F. 后台清理与手动存档互相拆台 —— 已验到硬编码槽列表
`MainActivity.kt:393` → `StorageMaintenanceFacade.kt:23-25` 确实拉起周期任务：
- `DataPruningScheduler.kt:189` 按 `BattleLog.timestamp`（**创建墙钟**，`CultivatorCave.kt:129`）删 7 天前日志（`BattleLogDao.kt:48-50`）。低频玩家（玩 3 小时存档、8 天后回来）的日志被真删；而 `WriteOps:148` 存档时 `deleteAll` + 按内存全量重写又会把它塞回去 → **对常存档玩家是空转，对不常存档的玩家才是真删**。
- `DataArchiveScheduler.kt:182-201 archiveDeadDisciples`：把已故弟子**从 `disciples` 主表物理删除**（`deleteDeadBySlot`），归档行 `dataBlob = ""`（只剩 id/名/境界，**不可还原**）；`:149-178 archiveBattleLogs` 同理。`:114-121` 180 天后连空壳也删。
- `ArchiveConfig.slotIds = listOf(1,2,3,4,5)`（`DataArchiveScheduler.kt:24`，**已亲自验到**）→ **slot 6 永不归档**；对照 `DataPruningScheduler.kt:24` 用的是完整 `(0..6)`。
- `DataPruningScheduler.kt:66-79 deleteLegacySnapshotsOnce` 的 `legacySnapshotsDeleted` 是**进程内 `AtomicBoolean` 成员**，注释称"一次性"，实为每次进程重启都执行一遍。
- 归档表与 `.arc` 文件的**全部查询方法零调用者**（无 UI、无导出）→ 定性：**写了就永不回读的单向数据流失**。
（A）

### G. 读档后重算会覆盖存档值（`performLoadBoot` 在落盘值装入**之后**执行）
| 时序 | 位置 | 覆盖内容 | 是否破坏存档值 |
|---|---|---|---|
| 2 | `LoadDataOps:147`→`LoadSlotOps:47-128` | **重建 `patrolSlots`**（10→8 槽/塔）、裁撤 `patrolConfigs`、被裁槽弟子置 IDLE | **是** |
| 4 | `LoadDataOps:106`→`LoadSlotOps:148-190` | **截断 ALCHEMY/FORGE 槽数到当前建筑数**；空则 `initializeAllSlots` 造默认空槽 | **是** |
| 5 | `LoadDataOps:107`→`LoadSlotOps:141-146` | **读档即刻自动收割已完成槽位**（玩家看不到"已完成"弹窗） | **是** |
| 6 | `LoadDataOps:111` | `gd.productionSlots ← repository.getSlots()` | **是** |
| 15 | `Boot:216 ensureGameDataIntegrity` | `worldMapSects` 空→**整世界重生**（驻军丢失）、`aiSectDisciples` 不满→填充、商人/招募列表空→**重刷**（重建 ≠ 还原） | **是** |
| 13 | `Boot:197/199` | 拆除溢出/边界建筑并退款 | **是** |
| 12 | `Boot:158-194` | `placedBuildings` 归一化+补 instanceId、`activeSectId` 净化、`guideCounters` 回填 | 幂等收敛 |
| 7/8 | `LoadDataOps:113-115, 119` | `merchantRefreshChances 0→1`、`spiritMineLastSettledMonth 0→当前月`（防暴增） | 兼容旧档，正向 |
| 19 | `Boot:395-425` | `terrainTiles` **非空即采用、空才生成** | **否**——注释宣称的"读档后重算地形"不成立，地形已冻结 |

`ensureHeavyDataLoaded`（`GameEngineLifecycleOps.kt:25-39`）**实际不加载任何数据**，只做 `if (worldMapSects.isNotEmpty()) heavyDataLoaded = true` 的纯标志位。`WriteOps.kt:41` 提示"请检查 ensureHeavyDataLoaded 日志"具有误导性。（A）

### H. 墙钟冷却与时间基准三处风险
- **宗门等级每周奖励**：`GameEngineSectLevelOps.kt:350` 亲自验到 `System.currentTimeMillis() - lastClaim.claimedAtEpochMs >= WEEK_MS`，其中 `claimedAtEpochMs` 是**存档字段**（`GameData.kt:54`）→ **时间前调即可重复领取**。
- **邮件 30 天墙钟删除**：`MailService.deleteExpiredMails(slotId, timeSource())`，而**邮件不在 `SaveData` 14 字段快照里** → 时间前调 30 天 + 打开邮件页 = 未领附件**物删且读档救不回**（全项目唯一不可回滚通道）。
- **玉符 `jadeDayAnchorMs`**：做了完整回拨防御（`JadeSymbolService.kt:460-478`：墙钟回拨跳过节流直接采样、`todayMidnight <= anchor` 不重置、旧档锚点 0 首次直接锚定无追溯发放），但时间前调仍会**清当日额度且 `dayAnchorMs` 单向赋值不会复原**。
- **`worldLevelLastRefreshMonth == 0`** → `WorldLevelManager.kt:51` 判"从未刷过"，读档后第一批月结**整批重刷世界关卡/妖兽**。同型问题的两处补丁都在（`initSpiritMineLastSettledMonth`、`initMerchantRefreshChances`），**唯独这个没有**。
- **`lastSaveTime` 参与迁移判据**：`SaveDataVersionMigrator.kt:130-133` 用墙钟 epoch 判"误标新档"。一个 `saveVersion=0` 的历史档若玩家在 2026-06-15 之前存过盘，升版后首次读档会被判真旧档、`sectCultivation ÷ 10`，**损失 90% 修为**。缓解：现每次保存强制盖 `saveVersion=CURRENT`（B 级）。
- **游戏内时间与墙钟彻底独立**（正向结论）：`gameYear/gameMonth/gamePhase` 是 Int 字段，存档回灌即恢复；**没有任何一处用墙钟反推或覆盖游戏内时间**（`GameEngineLoadDataOps.kt` 中 `currentTimeMillis` 零命中）。**不存在离线收益机制**（全仓 `offline|离线` 无一处玩法实现）。生产槽/种植全用 Int 游戏绝对年月（`ProductionSlot.kt:54-92`、`GameDataFieldModels.kt:44-50`、`LazyEvaluationDispatcher.kt:17-39`）→ **改系统时间、跨时区、应用更新零影响**，这是本项目时间处理最干净的一块。
- **挂后台追补有硬上限**：`GameTimeClock.kt:157-172` catch-up 被 `maxPhasesPerTick(speed)=3×speed` 截断，超限余量 `:169` **直接丢弃**。挂后台 1 小时回来不会得到 1 小时游戏时间。

### I. 云存档无时间戳仲裁 + 覆盖式上传
`TapCloudSaveManager.kt:359-372 arbitrateCloudVersion` 只比 App 版本串，仅挡"云档来自更高版本"。`resolveCloudSaveInfo:104-114` 在**两个不同来源的毫秒数之间比大小**——服务端文件 mtime（`getModifiedTime(target)*1000`）vs 客户端自造本地挂钟（`CloudOps.kt:78-88 lastModifiedTime = System.currentTimeMillis()`）→ **设备时钟偏快会长期误判自己的缓存更新**。
下载后**不落盘**（`CloudOps:280` 仅 `setCurrentSlot(0)`），玩家之后一次保存到 slot 0 → **无条件覆盖云端**，无"云端更新则保留云端"判定。本地 1..6 不会被云下载覆盖。
slot 0 展示有两套不共享真值的链路：`StorageEngine.kt:529-541` 硬编码全 0 占位（`gameYear=0` 违反自家 `validateSaveData:738` 的 `gameYear<1` 判据）→ `mergeCloudSlot` 覆盖；但**主菜单不走 `mergeCloudSlot`**（`MainActivity.kt:572-585` 另路渲染）。（A/B）

### J. 初始化失败静默放行
`MainActivity.kt:388-417`：3 次 `storageFacade.initialize()` 全失败 → `:414-419` 只 `Log.e("... proceeding with empty cache")`，随后 `:421-423 isLoadComplete = true` → **无任何用户提示，照常进主菜单、照常可进游戏**。兜底是 `StorageFacade.kt:493-498 ensureInitialized()` 在每次 save/load/delete 时再试一次。（A）

### K. 删档残留
真实实现 `StorageEngine.kt:401-459`：删 tombstone + `.sav/.bak/.tmp` + 单事务 **29 表** + 内存缓存。但注册实体 34 个 → **5 表未删**：`change_log`（无 slot 列）、`archived_battle_logs`、`archived_disciples`、`overflow_mail_drafts`、`direct_mail_drafts`（后两者 DAO 有 `deleteAll*ForSlot`、仓储有 `deleteAllDraftsForSlotBlocking`，**全仓零调用者**）。
另有全部 MMKV key（含 `archive_uuid`/`cloud_save_info`）、**TapTap 云档**（`deleteArchive` 仅在接口 `:688`，无调用路径）、`archives/*.arc`+index、`pre_migrate_backup.vK`、`wal_v4/`、quarantine 快照全部存活。
**更糟**：`clearSlotDataQuietly`（`SaveSupport.kt:208-220`）**只删 `game_data`+`disciples` 两表**，而 load 的 tombstone 分支正是走它（`LoadOps:34-41`、`StorageEngine.kt:327`）→ 残留 27 表行。（A）

### L. 确定性/正确性正向确认（避免只报坏消息）
- RNG 分区流态真实落盘且加载可复现（`GameData.rngStates` → `game_core.cpp:603 restoreStates`，AI 镜像走键 9）。当前版本新档键齐全 → 精确恢复。
- 招募池/商人/世界关卡**不是"进页面刷新"而是"按游戏内绝对年月刷新"**，刷新判据与随机流双双持久化 → **存过档就能刷出同样的东西**。
- `YearlyOpsQueue` 闭环：存档前 `flushYearlyOpsQueue()`、读档前 `clearYearlyOpsQueue()`，设计上无残留风险。
- `cleanExpiredBackups` **安全**（`SaveFileManager.kt:255-277` 三重合取：`>7天` **且** 孤儿 **且** 对应 `.sav` 不存在；`.sav` 永不清理，注释明说它是"DB 损坏时的恢复点"）。
- `cleanupOrphanedTmp`（>5 分钟 `.tmp`）是崩溃残留，安全。
- CRC32C + 帧内 SHA-256 **真实校验**，恒走、无 API 分支（用自实现查表）；`SaveFileManagerSdk33Test.kt:16` 注释"CRC32 分支"与实现矛盾，以代码为准。

---

## 13. 存档系统真实流程图

```
【启动】
  XianxiaApplication.onCreate:100
    → CrashRecoveryEngine.initialize:323 → VulkanPolicy:325 → 安全模式判定:329/333
    → MMKV.initialize:360/372 → crashReporter.initialize:381
  MainActivity 隐私同意 → :379 proceedAfterPrivacyConsent
    → :388-417 StorageFacade.initialize ×3 重试（★3 次全败也只打日志、照常进主菜单）
    → ★拉起 3 个周期任务：MemoryGuard 10s / DataPruning 300s / DataArchive 600s
        （后两者会 DELETE battle_logs / disciples 主表）
  主菜单（★不自动读档、★不自动拉云档）
        ↓ 用户手点槽位（MainActivity.kt:585 → :620）
【读档】
  GameActivity.kt:776 loadGameFromSlot → SaveLoadViewModel.kt:274 → LoadOps.kt:145
    → StorageFacade.kt:263 load → StorageEngine.kt:249 load
       ① :264 tryCacheLoad（内存缓存 + 迁移 + 双校验）
       ② :271 loadFromDatabase：game_data(141 列) + game_heavy_data(7 key) + 20+ 实体表
       ③ :278 restoreFromBackup —— 仅当 DB null 或 Corrupted
            readWithFallback(.sav→.bak) → CRC32C → 帧内 SHA-256 → LZ4 → ProtoBuf decode
            → SaveDataVersionMigrator(v0→1→2) → 二次校验 → quarantine DB → 反向回写(:378)
       ④ :284 SLOT_EMPTY
    → GameStateStoreImpl.kt:1354 _gameDataFlow.value = 整对象替换
    → ★之后★ performLoadBoot：截断生产槽 / 重建巡视槽 / 自动收割 / 世界重生
                / 拆除溢出建筑 / 重刷商人招募池（§12-G）
    → :131 syncNativeBaselineAfterLoad → nativeImportState（含 rngStates restore）
【运行】每旬
  GameEngineCore.kt:552 gameLoopMainLoop → AuthoritativeOps:65 nativeSettlePhase()
    → :68 applyDirtyFromNative → StateSyncService:404 updateMirror{mergeGameDataChanges}
       ★ 这条链上没有任何一次落盘 ★
       ★ 全项目没有任何 tick→save 的边 ★
【保存】仅玩家手动点按钮
  SettingsTab.kt:960 → SaveOps.kt:27 → :187 performLocalSaveToSlot
    → GameEngine.buildSaveSnapshot → SaveFacadeImpl.getStateSnapshot（14 字段）
    → StorageFacade.kt:234 save → StorageEngine.kt:122 save
       :128 withWriteLockLight → :131 熔断检查 → :139 validateAndPrepareData
         （校验/裁剪 battleLogs/盖 saveVersion/盖 timestamp=墙钟）
       :145 saveWithRetry → :627 performFullTransactionSave
         ├─ :637 wal.beginTransaction（★仅记操作名字节，无数据）
         ├─ :646 database.withTransaction { writeAllDataToDatabase }  ← ★真相源
         │     :50-51 game_heavy_data 先删后写（★事务外 autocommit）
         │     :56 清旧行 → :57 game_data → :58 5 张 state 表
         │     :176 disciples → :177-187 6 张冗余表（零读者）→ :194-217 物品族
         │     :227 production_slots → :164,165 syncSlotMetadata ×2（冗余）
         ├─ :657 checkpoint → :667 wal.commit（checksum 恒 ""）
       :148 handleSaveResult
         └─ :146 if autoBackupOnSave → SaveFileManager.atomicWrite
              :106 payload = ProtoBuf(kotlinx,encodeDefaults=false) → LZ4 → 帧头
              :109-115 .sav：tmp → fd.sync() → rename        ← 原子
              :138  .bak：**同一 payload**                    ← ★假备份
              ★ 备份 Failure/Exception 均不改写 result        ← ★UI 谎报
       :151 recordSaveCircuitResult → :169 logSaveChanges（change_log 1 行空记录，零读者）
    → SaveOps.kt:252 isSuccess → :259 "游戏保存成功"
【退出】
  ★ 什么都不做 ★（SettingsTab.kt:351 弹窗明示"未保存的进度将会丢失"）
```

---

## 14. 声称存在但实际不生效的机制（逐一 grep 验调用点）

| 声称 | 实际 | 证据 |
|---|---|---|
| **存档加密**（`crypto/` 14 文件：Argon2id + AES-GCM + HMAC + Merkle） | **`.sav` 明文落盘**。在 `backup/`、`serialization/`、`engine/` 三目录 grep crypto 引用 = **0 命中**（已亲自执行）；`CryptoModule` 全仓零调用者。`.secure_key` 只服务网络签名（`RequestSigner:172`、`SecureHttpClient:407`）→ **密钥丢失/换设备不会导致存档不可读** | A |
| **FunctionalWAL 崩溃重放** | 不记数据字节，`recover()` 只打日志，错误文案自认"DB 事务已回滚，无需快照恢复"（`:732`）；begin/commit 失败全部非阻断 | A |
| **增量存档** | `ChangeTracker` 被 new 成单例但**无注入者**，`track*` 零调用；`change_log` 每次只写一行 `old/new=null` 且**零读者**；`wasIncremental=false` 硬编码（`Engine:676`）。每次保存仍全量先删后写 | A |
| **存储配额/分片限流** | `SaveLimitsConfig` 的 `maxSaveSizeBytes`/`totalStorageQuotaBytes`/`shardingThresholdBytes`/`shardMaxSizeBytes`/`isDynamicQuotaEnabled`/`getEffectiveTotalStorageQuota` 引用者**只有本文件自身**；7 个 setter + `resetToDefaults` + `dumpConfig` 全仓零调用。真实生效的仅 `maxBattleLogs` | A |
| **`StorageConfig` 大部分开关** | 只有 `enablePreSaveValidation`/`autoBackupOnSave`/`maxRetryCount`/`retryDelayMs` 被读；`maxSaveSize`/`minMemoryRatio`/`updateCacheAfterSave`/`maxBackupVersions` 零 `storageConfig.` 读取者 | A |
| **可配槽位数** | `StorageConfig.kt:36 maxSlots` 可配，但 `StorageModule.kt:33-35` 用无参构造（硬编码 6），`SaveFileManager.kt:488` 另用 `StorageConstants.DEFAULT_MAX_SLOTS` → `max_slots` 偏好设置对真实槽数无影响 | A |
| **自动存档** | 字段已 `@Ignore`+`@Transient`，v50 迁移删列；`GameDatabase.kt:197` 自陈"纯手动存档设计" | A |
| **归档可查** | `archived_*` 两表 + `.arc` 的全部查询方法零调用者，无 UI、无导出 | A |
| **`SaveStorage` 领域接口** | `SaveStorageImpl.load()` 字面 `return null`；整条桥接链无注入点 | A |
| **存储可观测性** | `StorageMetrics` 8 个计数器**类内无任何 getter**，写后永不可读 | A |
| **`SaveValidatorFixes` 修复持久化** | `shouldPersistRepair` 零调用者；加载路径 Repaired 只进缓存 | A |
| **死代码集合** | `StorageFacade` 8 个方法、`StorageEngine.listSlots/forceDeleteSlotData`、`SaveFileManager.getBackupInfo`、`loadHeavyDataForSlot`、`SlotLockManager` 8 个方法（仅测试）、`SchemaVersion.kt`、`SerializationQuota.kt`、`GameStateRepository.loadFullState()`、`DiscipleRepository`/`WorldRepository`/`InventoryRepository`/`EquipmentRepository`/`GameDataRepository` 全套（仅 `BridgeBindingsModule.kt:39-51` 绑定，engine/ui 无注入点）、`SaveLoadLoadDelegate` | A |
| **外层头 flags / 长度字段** | 写入但从不校验；长度字段存压缩后长度（与注释矛盾） | A |
| **`priority: SavePriority` 参数** | `StorageEngine.kt:123` 有参，全仓无人传 | A |

---

## 15. 证据等级与存疑项

**方法论**：本报告所有 A/B 级结论均附 file:line。以下 12 条承重论断由主 agent **亲自复核代码、非转述子代理**：

1. `@ProtoNumber(162)` 重复（`GameData.kt:572` / `:865`）
2. 全仓 `storageFacade.save` 真实调用点计数 = 3 + 1 死
3. `DataArchiveScheduler.kt:24 slotIds = listOf(1,2,3,4,5)`（slot 6 缺失）
4. `rememberSaveable` 在 `feature/game/src/main` 出现 0 次
5. `SaveService.kt:66 gameSpeed = 1` 硬编码
6. `GameEngineSectLevelOps.kt:350` 墙钟周冷却判定
7. `battleTeam`/`aiBattleTeams` 在 `schemas/51.json` 确有 columnName
8. `backup/`、`serialization/`、`engine/` 三目录零 crypto 引用
9. `SaveFileManager.kt` 内 `.bak` 复用同一 `payload` 变量
10. `StorageFacade.kt:371 restoreFromBackupIfCorrupted` 函数体只有一行日志
11. `handleSaveResult` 备份失败非阻断、不改写 result
12. `@Ignore` 字段集实际为 5 个（`:161,443,451,460,469`）

**降为 C/D 的存疑项（明示，不补全猜测）**：

| # | 存疑 | 等级 | 说明 |
|---|---|---|---|
| 1 | `StorageEngine.kt:646` 外层 + `WriteOps.kt:55` 内层两处 `Room.withTransaction` 是否并入同一事务 | **C** | Room `withTransaction` 会 `withContext(transactionDispatcher)` 切线程，嵌套不一定合并。**无实际代码证据确认，需实跑验证** |
| 2 | 月变进位与结算副作用回灌之间是否存在"月已进、该月产出未发"的存档窗口 | **C** | 年变有 `flushYearlyOpsQueue` 守卫，月变在 C++ 侧的原子性 Kotlin 侧无法证实。**未发现实际证据** |
| 3 | `claimedAtEpochMs` 因编码丢失退回默认 `0L` → 等价"从未领过"→ 重复发放一次 | **D** | 该字段无 `@EncodeDefault(ALWAYS)`，但**无实际代码证据确认此路径可达** |
| 4 | `performanceMode`/`clarityMode` 从 `SessionManager` 灌入引擎的具体绑定方向 | **B** | 持久化侧已确认（SP 列），灌入点未逐行追 |
| 5 | `SNAPSHOT_DIR_NAME` 目录是否被别的子系统当恢复点写入 | **B** | 全仓 grep 只命中删除点与常量定义，未发现写入方，故判定安全 |

---

## 16. 若要修复，优先级建议（本报告不改动代码）

| 序 | 项 | 为什么排这里 |
|---|---|---|
| 1 | **§12-F 归档删除弟子主表 / 战斗日志** | 唯一"不可逆销毁玩家数据 + 无人读取归档结果"的组合，且每 600s 自动发生 |
| 2 | **§12-C UI 谎报保存成功** | 直接摧毁玩家对存档系统的信任；修复成本极低（让备份失败可上抛） |
| 3 | **§12-A heavy 先删后写 + 超大行跳过** | `exploredSects`/`scoutInfo` 无再生源，丢了就是丢了 |
| 4 | **§12-B 假 `.bak`** | 当前"备份"对误覆盖/逻辑 bug 零防护，玩家与运维都以为有 |
| 5 | **§12-E `@ProtoNumber(162)` 冲突 + 守卫测试补唯一性断言** | 现在不爆，云档/备份恢复路径上必爆，且难归因 |
| 6 | **触发模型：补 `onStop`/`onTrimMemory`/事件驱动保存** | 这是损失面最大的一项，但属**产品决策**（2026-07-25 主动移除，需确认是否回摆） |
| 7 | **§12-K 删档残留 + `clearSlotDataQuietly` 只删 2 表** | 合规（账号注销/隐私）与 tombstone 正确性双重问题 |
| 8 | **§12-D 空函数 `restoreFromBackupIfCorrupted`** | 被当作恢复手段调用，属"安全网实际是空的" |
| 9 | **§12-G 读档后 boot 覆盖存档值** | 逐条判定哪些重算应改为"仅索引、不动数据" |
| 10 | **§14 死代码清理（crypto / WAL / 增量 / 配额 / 6 张冗余表 / metrics）** | 降低后续改动误判为"有保护"的概率 |

---

*报告结束。全程只读，未修改任何源文件、未执行构建或迁移、未改动 git 状态。*
