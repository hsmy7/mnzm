# 单存档改造 + 持久化面收口 · 设计方案

> 日期：2026-10-01
> 性质：**完整设计方案**（按 `rules/design-plan-review.md` 第零节编写规范；方案本身即最终态，不含"后续优化"）
> 前提约束：**当前无自有服务器**。云端仅有 TapTap 云存档（第三方存档托管），无服务端逻辑、无服务端发号、无服务端记账。
> 取证基础：`docs/save-system-survey-2026-10-01.md`（存档系统全量摸底，锚点 `be1e1f195`）
> 冲突对象：`docs/design/character-gacha-implementation.md`（G 批，15 批在册）与内存重构方案（D1–D7 / Phase 0–4）

---

## 0.5 前提变更声明（2026-10-01 第二次拍板：删档重置 + 去槽位维度）

> ⚠️ **本节取代本方案以下结论**，冲突时**以本节为准**：§2.1 的"保留 `slot_id` 物理列"、§2.3 的存量多档迁移、§3.1 的 `MIGRATION_65_66`、§3.6 的槽位维度项、§七 的 R2 结论、§八 的对应债项。

### 0.5.1 新前提

游戏处于**测试版**，产品决定进行一次**删档重置**并开启新测试期。随之：

1. **不再需要任何旧存档兼容** ⇒ 存量迁移整体取消；
2. **槽位维度整体删除**（Kotlin + C++ 双侧），不再"保留列恒常量"；
3. 项目内所有**旧存档兼容代码**清零；
4. **新旧数据不互通**，配合渠道强制更新。

### 0.5.2 拍板结果（W1–W12）

| # | 议题 | 结果 |
|---|---|---|
| W1 | 云端旧档 | **改云端存档命名 + 主动删除旧档**（双保险：命名变更让旧档自然失联，删除再兜一层） |
| W2 | 旧版本 App | **强制升级**——靠渠道；代码侧只保证"新旧数据不互通" |
| W3 | `slot_id` 列 | **删净槽位维度**（含 189 个 DAO 方法的 slot 参数） |
| W4 | Room 迁移链 | **删掉全部迁移注册**，依赖现有 `fallbackToDestructiveMigrationFrom(1)`；版本号 **65→66 保持递增**（归零会因降级直接崩溃） |
| W5 | 删档范围 | **连本地账号/合规缓存一起清** |
| W6 | 数据残留 | **全部清**：迁移前备份 + 归档 + MMKV 台账 + 孤儿 schema |
| W7 | `SaveValidator` 规则 | **逐条判定**：为历史旧档数据写的删、为运行期完整性写的留 |
| W8 | C++ 槽字段 | **一起删**：`currentSlot` + 物品 `slotId` 全去 |
| W9 | 对拍重录窗口 | **与 G 批 G10 合并为同一次** |
| W10 | 强制升级手段 | 渠道强制更新 + 代码侧数据不互通 |
| W11 | `AdFreeWhitelist` | **保留**（不属删档范围） |
| W12 | 删档触发 | **新版本首次启动自动清 + 保留开发入口**（可重复重置） |

**第三轮补充（邮件清理，M1–M4）**：

| # | 议题 | 结果 |
|---|---|---|
| M1 | 内置运营邮件 | **删除 QQ 群邮件**（`BuiltinMailConfig` 中唯一的非节日邮件），**仅保留节日邮件**（28 封） |
| M2 | 白名单福利邮件 | **删除**（`MailService.injectWhitelistBonus` + `GameEngineAdminOps.sendWhitelistBonus` + **4 个调用点**）；白名单只保留免广告特权（W11） |
| M3 | 手动补偿邮件 | **保留** `injectAdminMail`（`source="admin"`，运营工具性质） |
| M4 | 系统功能邮件 | 🔴 **必须保留**：`overflow`（仓库满转邮件）、`secret_realm`（秘境到期送达）——删了会直接丢物品；`nurture_pill_retirement` / `equipment_legacy_compensation` 属历史兼容补偿，**随 W7 逐条判定** |

### 0.5.3 对原方案的影响

| 原内容 | 处置 |
|---|---|
| §2.3 存量多档迁移（含 MMKV 台账键合并） | **整节作废** |
| §2.1 "保留 `slot_id` 物理列" | **反转为"删净槽位维度"**；原文保留为推理记录 |
| §3.1 `MIGRATION_65_66` | **取消**，改建 destructive 基线 |
| §八 各债项 | 保留；**新增**"destructive 永久保留的纪律风险"与其 CI 守卫 |
| 派工册 SS4（存量多档迁移） | **整批删除** |
| 两条"必须同版本发布"约束（SS1/SS4、SS3/SS4） | **消失**（不再有迁移窗口） |

### 0.5.4 新增的硬约束

1. **C++ 槽字段归档会改变导出形状** ⇒ **必须占对拍重录窗口**。本方案内共有**两次**形状变更（去槽位维度、玉符账本），二者**必须共用同一次重录**（G 批 R3：全局唯一权威重录窗口）。
   - 可接受安排：① 两者相邻合入，窗口设在后者完成时；② 期间 CI 的 Determinism 红点按 G 批"B 类红登记"先例显式登记。
2. **`Disciple.slotId` 不在协议面**（`models.h:305-306` 明示"Kotlin 用自定义序列化器排除 slotId……快照协议同样不含 slotId"）⇒ C++ 侧改动面**不含弟子列**。
3. **CI 守卫（防将来静默删档）**：新增 `@Entity` / 列变更时，必须同批出现 `MIGRATION_*`，否则判红——否则 destructive fallback 会把"忘写迁移"变成玩家档被静默重建。
4. **合规提示**：W5 会强制玩家**重新登录 + 重新实名**；`AdFreeWhitelist` 按 W11 保留。

### 0.5.5 版本号与更新日志（4.2.00）

| 项 | 值 | 依据 |
|---|---|---|
| 目标版本 | **`4.2.00` / `4200`** | 用户 2026-10-01 拍板 |
| 版本号格式 | **`X.X.XX`**（主 1 位 + 次 1 位 + 构建 2 位） | 规则由 `X.XX.XX` 改写，已落 `rules/version-release.md` §1 与根 `AGENTS.md` §8 |
| `versionCode` 公式 | **主版本 × 1000 + 次版本 × 100 + 构建**，强制单调递增 | 旧公式下"次版本 1"会算出 `4100` < 现行 `4116` ⇒ 商店拒收；改 `4.2.00` 得 `4200` 解决 |
| 游戏内更新日志 | **历史清空**（62 条 → 唯一 `4.2.00` 条目） | 用户拍板"清除以往日志（仅游戏内的）" |
| 外部 `CHANGELOG.md` | **保留完整历史**，新增 `[4.2.00]` 段 | 同上 |
| 三方归一 | `version.properties` / `CHANGELOG.md` 段头 / `changelog_entries.json` 均 `4.2.00` | `rules/version-release.md` |
| 后续义务 | SS10 收口时把 SS0–SS9 的玩家可见变更**并入同一 `4.2.00` 条目**（同版本禁止新建第二条目） | 同规则 §2 合并规则 |

> ⚠️ **已知的过期引用（不回改历史）**：`docs/gacha-watch/BATCH-PLAN-ALL.md:1028,1038` 与
> `docs/design/gacha-batches/TASKBOOK-G14.md:63,73` 的验收判据仍写 `X.XX.XX`。二者属 G 批**已收官**的
> 过程档案（`docs/AGENTS.md`：批次档案不回改），故保留原文；**新增批次一律以 `rules/version-release.md` 为准**。

---

## 一、决策分级与需求

### 1.1 决策分级（`rules/design-plan-review.md` 第六节）

**本方案属「架构级重构」**。判定依据：同模式问题 ≥3 处（槽位体系、玉符覆盖写、三个半截持久化组件），触及未来 6 个月规划（云存档/IAP/多端），影响跨平台路径（iOS 对等）。

### 1.2 需求要点（用户拍板，2026-10-01）

| # | 决策 | 取值 |
|---|---|---|
| Q1 | 云端角色 | **灾备 + 换设备可续玩**（不支持同时多设备） |
| Q2 | 玉符模型 | **不可变流水账 + 余额派生** |
| Q3 | 存量多档 | 保留最新一档，其余转**本地归档**并给「恢复旧档」入口 |
| Q4 | 登出/换账号 | **本地数据按账号隔离**，切账号即切数据空间 |
| Q5 | 登录门槛 | **全部功能要求登录** |
| Q6 | 多设备 | **明确不支持同时在线**（后登录设备接管） |
| Q7 | 交付 | 完整客户端方案 + 持久化面收口重构 |

### 1.3 补充拍板（2026-10-01 第二轮，共 7 项）

| # | 议题 | 拍板结果 |
|---|---|---|
| B1 | 离线宽限 | **已登录过可离线继续；从未登录必须联网首次登录** |
| B2 | 免广告白名单的玉符发放 | **这是白名单特权（产品有意设计），不是缺陷** —— 账本保持该语义不变，仅如实记录来源 |
| B3 | 旧 MMKV 台账键（按槽分片 4 组） | **读旧键 → 合并进单档台账 → 清理**（不留孤儿键） |
| B4 | `save_slot_metadata` 表 | **删除整表**（5 个读方法零消费者；同批改实体注册与两条守卫） |
| B5 | 既有分叉（`DEFAULT_MAX_SLOTS` 三处字面量 + `7 vs 6` 遍历口径） | **同批收敛**：三处字面量归一 + 遍历口径统一 + 明确 slot 0 日志的归档归属 |
| B6 | 影响范围清单粒度 | **维持三类组织**（必改 / 误伤 / 净收益），不铺开逐文件 |
| B7 | 文档落库 | 摸底报告与设计方案**一起提交** |

### 1.4 成功标准（总验收）

1. **单存档语义成立**：一个账号 = 一份进度；UI 无槽位选择/槽位管理；切换账号不串档（A 账号任何界面/数据不可见 B 账号进度）。
2. **存量零丢失**：升级后老玩家最新一档完整可用；其余存档以归档形式保留且可自助恢复；旧库文件不删除。
3. **玉符账目可派生可审计**：任意时点余额 = 期初 + 流水合计；不存在绕过流水的余额写入；"余额回涨"缺陷类型在结构上不可能发生。
4. **存档时机**：关键事件（玉符流水、碎片入账、高品阶物品入库、里程碑）触发后**本次落盘必须在事件返回前完成**（同步语义），并立即入云上传队列。
5. **持久化面职责收敛**：`change_log` / `FunctionalWAL` / 归档表 / `StorageMetrics` 四者职责明确，无"只写不读"与"有壳无芯"。
6. **门禁全绿**：`compileReleaseKotlin` + 相关模块 `testReleaseUnitTest --max-workers=1` + `lintRelease` + `detekt`（baseline 只缩不增）；存档相关守卫测试全绿。
7. **文档收口**：双 changelog + `CODE_WIKI.md` + `docs/architecture.md` + 隐私政策双入口。

---

## 二、技术方案

### 2.1 总原则（本方案的架构基石）

> **不删 `slot_id` 物理列。单存档是"语义单档 + 账号分库"，不是"删掉槽位维度"。**

依据（**影响面普查实测，2026-10-01**）：

- **规模**：约 **105 个 Kotlin 生产文件** + **6 个 C++ 协议文件**涉及存档槽；Room **27 张实体中 26 张带 slot 维度**（唯一例外 `ChangeLogEntity`）；**18 个 DAO、189 个带 slot 参数的方法**。
- 多张表为 `(id, slot_id)` **复合主键**（`game_data` / `disciples` / 八张物品表 / `storage_bags` 等），`slot_id` 唯一索引 ≥ 8 张。
- **64 条迁移全链**中大量条目涉及 `slot_id` 与 `(id, slot_id)` 主键重建（`MIGRATION_22_23` / `24_25` / `41_42` / `49_50` / `55_56` / `57_58` / `64_65` 等）。
- **C++ 侧已承载存档槽号**：`currentSlot` 是 JNI JSON 快照协议字段（`models.h:1205` + `json_codec.cpp:1185` / `:1279` 双向编解码），并被 C++ 结算逻辑内化为邮件归属（`month_settlement.h:1062`、`secret_realm_settlement.h:64`、`secret_realm_residual_tx.h:142`），经 `execute_dispatch.cpp:1256` / `dispatch_w4c.cpp:174` 回传；物品 7 类的 `slotId` 同样双向编解码（`models.h:68/85/247/262/273/284/296` + `json_codec.cpp` 14 处 `GC_TO/GC_FROM`）。
  ⇒ **这正是"只钉常量、不改协议字段"决策的正当性依据**：一旦把 `slotId` 降级为常量或删除，就必须同步改 Kotlin 与 C++ 两端 JSON 字段面，而 `DiffAuthoritativeTickTest` / `DiffInventoryTest` / `DiffSpiritFieldTest` 等 JVM 跨语言对拍依赖字段逐位一致，会强制重录基线（撞 G 批 R3）。
- 规则硬约束：`rules/database-migration.md` 第 2 条——"拿不准时**保留旧列不删除**"；同时**禁止** `ALTER TABLE DROP COLUMN`。

> ⚠️ **本节结论已被 §0.5 前提变更取代**：删档重置后"64 条迁移 + 189 DAO 方法"的兼容包袱消失，"保留 `slot_id`"的理由不再成立。现行决定是**删净槽位维度**（Kotlin + C++ 双侧，含 `currentSlot` 与物品 `slotId`）。以下推理保留为历史记录，**执行者以 §0.5 为准**。

**结论**：删列意味着 10+ 表重建 × 64 条迁移的历史包袱 × 数百 DAO 方法签名改写，收益为零、风险极高。正确做法是：

| 层 | 改造 |
|---|---|
| **语义层** | 槽位概念从玩家可见语义中消失（无选槽、无存档管理） |
| **物理层** | `slot_id` 保留，**恒为常量 `LOCAL_SLOT_ID = 1`**；`DEFAULT_MAX_SLOTS` 语义收敛为 1 |
| **隔离层** | 账号隔离上移到**数据库文件 + 目录**维度（分库），不再依赖 slot 区分 |

### 2.2 账号数据空间（分库隔离）

```
filesDir/
  accounts/<accountKey>/            ← 每个 TapTap 账号一个数据空间
      xianxia_sect.db(+ -wal/-shm)
      saves/slot_1.sav / .bak / .deleted
      archives/*.arc                ← 旧档归档（含存量多档）
      legacy_import/                ← 存量迁移源标记
  accounts/.current                 ← 当前活跃 accountKey（登出时清除）
```

- `accountKey` = TapTap 账号标识（`openId` 或等价稳定 ID）经 **SHA-256 截断**后的文件名安全串；不落明文标识到文件名。
- **未登录不建数据空间**（Q5：全部功能要求登录）。
- 登出 = 关闭当前数据空间并把 `.current` 清空；**不删库**（下次登录同一账号进度仍在）。
- 数据库打开路径由 `GameDatabase.create()` 的 `name` 参数注入（现有实现已支持按名建库，见 `GameDatabase.kt` 的 `create()` 签名与 `getUnifiedDatabaseFile(context)`）。

**iOS 对等**：iOS 侧同样以"账号 → 沙盒子目录"实现；`accountKey` 派生逻辑（哈希/命名）下沉到 `:core:domain` 纯函数，双端共用同一套命名规则，避免两端数据空间命名分叉。

### 2.3 存量多档迁移（一次性，可回退）

**触发时机**：首次以新版本启动且已完成登录（能确定 `accountKey` 时）。用 `legacy_import/pending` 标记持久化，防止中断后重复迁移。

**步骤**：

1. 打开**旧库**（原 `xianxia_sect.db`），逐个读取槽 `1..6` 的元数据投影（`GameDataDao.getMetadataBySlot` 一族）。
2. 按 `lastSaveTime` 取最新一档为**主进度** → 完整读出（复用 `StorageEngine` 既有读档链）→ 写入新数据空间的 `slot_id = 1`。
3. 其余可读档 → 逐个导出为归档文件（复用 `SaveFileManager` 的 `.sav` 原子写 + CRC32C 校验 + `SaveData` 序列化；归档索引登记到既有归档表，见 §2.6）。
4. 旧库**不删除**，重命名为 `xianxia_sect.db.legacy_YYYYMMDD`，保留一个版本周期。
5. 迁移完成写 `legacy_import/done`；设置页「恢复旧档」入口列出归档清单（宗门名/年月/时间戳），恢复 = 覆盖当前唯一档（破坏性二次确认 + 恢复前自动存一份 `.bak`）。

**⚠️ 必须显式处置的陷阱：MMKV 台账按槽号分片 ⇒ 槽列表收缩会让"存量未迁移"静默消失**

云端两条账本都按**槽号**分片写 MMKV：`SaveMigrationLedger.kt:75`（`cloud_migration_slot{N}_state`）与 `UploadLedger.kt:85-90`（三键/槽 `cloud_upload_ledger_slot{N}_*`）。而升档门槛 `SaveMigrationPlanner.canPromoteToCloudOnly`（`:153-157`）的判据是：

```
inputs.filter { it.localHasSave }.all { it.migrationState.migrated }
```

其 KDoc **自陈**「本地一槽无档时三条**真空成立** ⇒ true」。单存档把槽空间收缩为 1 后，若旧 `slot2..6` 不再进入 `inputs`，这道门槛会**真空通过**——原本待迁移的存量档**静默退出迁移面**，玩家换设备时才发现云上没档。

**处置（本方案强制）**：
- 迁移前置不再以"槽列表"为判据，改为**以"旧库中实际存在的档"为判据**（§2.3 第 1 步已按 `getMetadataBySlot` 枚举旧库全槽，与此对齐）；
- 首次启动时扫描并**显式消费**旧 MMKV 键（`slot2..6` 的迁移态与上传账本）：**读旧键 → 合并进单档台账 → 清理**（已拍板 B3），禁止留成孤儿键；
- `legacy_import/done` 标记的写入位置必须在**台账键清理之后**，保证中断可重入。

**兼容性**：老玩家不迁移直接玩（若玩家在新版本首次启动即登录）无感知；迁移失败 → 旧库未动，可重试；任何一步崩溃 → `pending` 标记使下次启动重新迁移（幂等：新库为空时重跑无副作用）。

### 2.4 玉符：不可变流水账 + 余额派生

> ⚠️ **本节已按玉符全景取证修正（2026-10-01）**：初稿把账本放在 Kotlin 侧是**错的**——玉符落账**已经在 C++ 侧**（事务 1766–1769 + 购买族 1692/1693），Kotlin 单侧账本会被 C++ 直接绕过。修正后**账本真源在 C++**，Kotlin 仅派生只读 + 镜像投影。

**现状（实测，证据 A）**：玉符四字段在**两侧同时存在**——Kotlin `GameData.kt:292-311`（`@ProtoNumber(220~223)`、`@SettlementStrategy(USE_SHADOW)`、Room 列 `jade_symbols` / `jade_symbols_today` / `jade_day_anchor_ms` / `jade_accum_ms`，由 `MIGRATION_41_42` 建列，`GameDatabaseMigrationsV42.kt:26-42`）与 **C++ `models.h:1234-1237`**，并有全量 JSON 双向编解码（`json_codec.cpp:1199-1200` / `:1293-1294`）。

**关键事实（本次亲自核实）**：玉符落账已在 C++ 建了完整事务族，`jade_tx.h:24-37` 明文自陈「W4-B 把其中 GameData 四字段稳态写下沉为**事务 5–8（1766–1769）**」，并把"绝对值覆盖写模型"登记为 🔴 前置条件：

| 事务 | ActionId | 位置 | 语义 |
|---|---|---|---|
| `settleJadeGrantsTx` | 1766 | `jade_tx.h:451-453` | 时长发放落账（绝对值覆盖写） |
| `jadeDayResetTx` | 1767 | `jade_tx.h:500-503` | 跨天重置 |
| `jadeCheckpointTx` | 1768 | `jade_tx.h:541-544` | 存档快照四字段覆盖写 |
| `grantJadeFromAdTx` | 1769 | `jade_tx.h:573-575` | 广告发放 `= totalBefore + amount` |
| `purchaseBreakthroughBonusTx` | 1693 | `jade_tx.h:361-372` | 突破加成**扣费**（`-= cost`） |
| `purchaseMerchantRefreshTx` | 1692 | `jade_tx.h:304-313` | 商人刷新**扣费**（`-= cost`） |

ActionId 常量已核实存在（`ActionIds.kt:462` / `:465` / `:558` / `:561` / `:564`）。

**因此当前是「三份真相」**：① C++ `state.gameData.jadeSymbols`（AUTHORITATIVE，差额直接改）② Kotlin 镜像 `GameData.jadeSymbols`（经 `GameDataFieldPatch.kt:175-178` 写入）③ Kotlin 运行时 `@Volatile totalCount`（`JadeSymbolService.kt:137`）。对齐靠 5 个重锚点（`deduct` / `settleGrants` 回执 / `grantFromAd` 回执 / `syncBalanceFromSnapshot` / `onLoopStart`），而 `syncBalanceFromSnapshot` 读的是**镜像快照**、正确性依赖同一 `tryExecuteNative` 内已先跑 `applyDirtyFromNative()` —— **全链最脆的一环**。"余额回涨"类缺陷全部源自第 ③ 份对第 ①② 份的绝对值覆盖（`JadeSymbolService.kt:266-275` / `:415-421`，C++ 对偶 `jade_tx.h:541-544` / `:451-453`），历史真实发生过（`CHANGELOG.md:4293` 玉符 20→3 冷启动竞态；`:4519` 旧循环 finally 覆盖新档）。

**目标模型（三层收敛）**：

```
C++（真源，符合「C++ 是 AUTHORITATIVE 真相源」不变式）
  state.jadeLedger：append-only 条目数组（跨语言协议字段）
  jadeSymbols：派生缓存（账本求和结果，禁止独立赋值）
  jade_tx.h 六事务改造：
      扣费/发放 → appendLedgerEntry(delta, reason, balanceAfter, …)
      余额校验仍读缓存（O(1)，见性能护栏）
        ↓ 块级基线比对（column_dirty.h restBaseline_.diffAdvance → gameData.jadeLedger）
Kotlin 镜像（只读）
  GameData.jadeLedger（新 @ProtoNumber 字段，取 240+ 段；保留段禁复用 ← ProtoNumberUniquenessTest）
  GameData.jadeSymbols：派生缓存值
  GameDataFieldPatch.coveredFields 必须登记新字段（否则 GameDataFieldPatchGuardTest 红）
        ↓
UI（收敛为单一读数）
  徽章（现读镜像）/ 说明框倒计时（现读运行时流）/ 消耗弹窗红字（现读镜像）→ 统一读镜像派生值
```

- **余额 = 期初条目 + Σ delta**；派生函数下沉 `:core:domain` 纯函数（双端共用）。
- **今日计数** `jadeSymbolsToday` 与周期累计 `jadeAccumMs` 保持独立语义（墙钟日闸），**不并入账本**。
- **性能护栏（必须写明）**：若每次扣费都遍历账本求和，会把 `jade_tx.h:304` / `:361` 的 O(1) 余额检查变为 O(n)，并可能复活 `docs/cpp-migration-handover-m0.md:303` 记录的"tick 滞后窗口双花"。对策：**账本条目 + 派生缓存同事务双写**，条目内冗余 `balance_after`；两者不一致时以账本为准并计数上报（`StorageMetrics`，见 §2.6）。
- **迁移（头号风险）**：老档只有余额、没有账本 ⇒ 必须写一条 `reason = OPENING_BALANCE` 的**期初条目**（余额不变）。**漏掉这一步会把全部存量玩家玉符清零。**
- **守卫改造（含一处现有漏检）**：
  - `JadeSymbolConsumptionGuardTest` 的两个正则**只认 `jadeSymbols`**（`JadeSymbolConsumptionGuardTest.kt:37-40`）⇒ **新增账本字段的 `copy(...)` 写入会被静默漏检**。必须把账本字段名纳入正则，并重划白名单分界（镜像写 `GameDataFieldPatch` vs 玩法写 `JadeSymbolService`）。
  - 新增"账本 append-only + 派生余额 == 账本求和"守卫。
  - `JadeSymbolNonNegativeRule`（order=23）的钳制语义改为钳制派生值；**order 不得改动**（`MailDiscipleAttachmentCleanupRuleTest.kt:204-215` 断言了规则先后关系）。
- **ActionId 纪律**：新增账本事务走 `scripts/gen-action-ids.mjs` 双产物，**禁止手改生成物**；号只增不复用（退役洗炼族 392/395/401/512/515 已标禁复用）。
- **对拍双守护（`android/app/src/main/cpp/gamecore/AGENTS.md` 硬要求）**：桌面 GTest（`jade_tx_test.cpp` 17 例 / `jade_runtime_tx_test.cpp` 18 例，逐值锁定现有绝对值语义，**须同步改写**）+ JUnit `Diff*Test` 跨语言对拍，两侧齐备，缺一即任务未完成。

**经济影响（`经济` 标签）**：源仅两条——S1 在线时长（`GameConfig.Jade.INTERVAL_MS` 10 分钟 / `DAILY_CAP = 20`）、S2 激励视频（`AdsDelegate.kt:35` `JADE_AD_REWARD = 3`）；汇仅两条——C1 突破率加成、C2 商人刷新。**玉符不走 `withTrackingSource` 是既有设计决定**（`docs/knowledge-base.md:742`：不占宗门仓库、无品阶、不走 `InventorySystem`/`OverflowMailSender`）⇒ 账本 `reason` 登记 `docs/knowledge-base.md` 经济基线表即可，**不要**与 `OverflowMailSender.SOURCE_DISPLAY_NAMES` 对接。**现状 0 IAP**（`rules/commercialization.md:3`）⇒ 账本的真实价值是**在 IAP 上线前把账立起来**。

**白名单特权的产品定性（用户 2026-10-01 确认：这是特权，不是缺陷）**：`AdServiceImpl.kt:52-57` 免广告特权用户跳过广告播放直接发奖，且 `AdsDelegate` 的冷却与每日次数对白名单返回"不受限" ⇒ **白名单玩家的玉符获取不设次数上限是有意设计**。账本改造**保持该语义不变**，仅在流水中如实记录来源（`reason = GRANT_AD`，可区分白名单直发与真实观看）。经济建模须把"白名单 = 无上限来源"作为已知事实纳入。

**无服务器下的边界（必须写明）**：`rules/commercialization.md:19` 要求购买校验"防重放 + **服务器确认后才发奖**"。**无服务器 ⇒ IAP 无法合规落地**；本方案不引入任何付费通道，玉符仅作为广告/活动/白名单来源的准货币存在。该限制登记为技术债（§八）。

### 2.5 自动存档：短间隔节拍 + 关键事件立即落盘

**保留**：现实墙钟节拍 10s（`REALTIME_AUTO_SAVE_INTERVAL_MS = 10_000L`，`SaveLoadViewModelAutoSaveOps.kt:21`）+ `SaveOrchestrator` 500ms 合并窗 + `onStop` 后台保存。均**不改**。

**新增：关键事件触发立即落盘**（事件集按"是否涉钱 / 是否不可逆 / 是否唯一"分类，而非按资源名）：

| 类别 | 事件 | 语义 |
|---|---|---|
| 涉钱 | 玉符流水 append | **同步落盘**（事件返回前完成）+ 立即入云队列 |
| 不可逆随机结果 | 抽卡出角色碎片/高品阶物品（G 批 `gacha_tx` 产物） | 结果展示前落盘 |
| 不可逆消耗 | 碎片合成/升星、物品熔炼 | 与产出**同事务** |
| 唯一性里程碑 | 首次通关、成就、渡劫成败 | 立即落盘 |
| 版本节点 | `MIGRATION_*` 成功后 | 立即落盘 + 入云队列 |

**防风暴**：事件触发**不直接调用保存**，而是 `SaveOrchestrator.submit(trigger)`（现有接口）——500ms 合并窗把十连抽的多次事件合成一次落盘；`BACKGROUND` 与"涉钱"两类走 `flushNow` 立即冲刷（`SaveOrchestrator.kt` 已有该分支）。

**与 G 批的关系**：G 批 `gacha_tx` 在 C++ 侧产出结果 → `applyDirtyFromNative` 镜像 → Kotlin 侧触发落盘。**本方案不新增任何 Kotlin→C++ 反向状态写**（`MirrorReadOnlyGuardTest` 保持零命中）；玉符账本块新增的是 C++ **命令事务**（走 `ActionId` 命令通道，与 `gacha_tx` 同构），不引入反向状态通道。

### 2.5.1 增量落盘（真增量写，本地 DB 层）

**现状（实测，证据 A）**：每次保存是**全删全写**，不是增量。

- `StorageEngineWriteOps.kt:126-148` `clearOldSlotEntities` 对 **12+ 张表逐个 `deleteAll`**（弟子/堆叠/装备实例/秘籍实例/丹药/材料/灵草/种子/储物袋/战斗日志/配方/生产槽/邮件），随后 `writeCoreEntities` / `writeStackedItems` 用 `upsertAll` **全量重写**。
- `StorageEngineWriteOps.kt:67-77` `clearHeavyDataByPrefix` 把 **7 个 heavy key 全部删除**；`:80-108` `writeHeavyDataIncremental` 的"增量"指的是**分块编码以降低内存峰值**，不是"只写变化"——它每次重新编码并 upsert 全部 7 个 key。
- 由此派生的两处防护实为**全删全写的补丁**：`skippedHeavyKeysBySlot`（读档跳过的 key 不删）与 `stacksSerialized` 条件删除（旧格式存档不删堆叠表）。

**关键事实（本方案的可行性依据）**：C++ 侧的**列级脏标记通道已经存在**（`exportDirty` / `ColumnDirtyTracker`），且脏变更集**已经到达 Kotlin**（`StateSyncService` 的 `applyDirty` / 增量镜像）。**脏信息在落盘时被丢弃了**——这正是"能力止步于上一层"（缺口形状 b）。因此增量落盘**可纯 Kotlin 侧闭环实现，无需触碰 C++ 任何文件**，也就不占用 G 批 R2 的文件级锁。

**设计**：

```
DirtySetTracker（自上次成功落盘以来的脏集）
  dirtyTables:      Set<Table>        ← 来源 = applyDirty 变更集 + 本地 Kotlin 变更
  dirtyEntityIds:   Map<Table, Set<String>>
  heavyDirtyKeys:   Set<String>       ← heavy 按 key 粒度判定（blob 不可部分更新）
  requiresFullWrite: Boolean          ← 无基准 / 脏集溢出 / 结构变更 / 云档恢复 / 迁移后首启
```

落盘拆两条路径：

| 路径 | 触发 | 行为 |
|---|---|---|
| **增量路径**（默认） | 有有效脏集且无 `requiresFullWrite` | ① 变化行 `upsert`（现有 `upsertAll`，只传变化 id）② 删除集 = `persistedIds - pulledIds` → `deleteById` ③ **未变的 heavy key 直接跳过**（不删不写）④ 变动 heavy key 整 key 重编码 |
| **全量路径**（兜底） | 首次落盘 / 脏集不可判定 / 溢出 / 读档后首次 / 迁移或云恢复后 | 沿用现有 `clearOldSlotEntities` + 全量重写 |

**删除检测**：新增 `SlotEntityIndexDao`（每槽每类一行，存已落盘 id 集合的紧凑表示），落盘成功后在**同一事务内**更新。对无删除语义的表（如 `game_data` 单行）不需要。

**正确性护栏（硬门禁）**：
1. **双路径对拍**：同一内存状态分别走增量与全量路径落盘 → 读回**逐字段全等**；覆盖"新增一批 / 删除一批 / 修改一批"三种脏集形状。
2. 增量路径落盘前校验：脏集 id 必须 ⊆ 当前快照 id 集合，否则**回退全量路径**并计数（不得静默跳过）。
3. 全量路径保留为永久兜底，**不删除**（它是正确性的最后一道防线）。

**顺带收益**：增量路径落地后，`skippedHeavyKeysBySlot` 与 `stacksSerialized` 两处"防全删"补丁的保护对象消失（未被标记为脏的表根本不会被删），可降级为断言而非补丁。

**边界（诚实声明）**：`.sav`/`.bak` 文件镜像与云载荷受**单 blob 格式**限制，仍是全量——增量只发生在本地 Room 层。云载荷增量上传登记为技术债（§八）。

### 2.6 持久化面收口（四组件赋责或摘除）

| 组件 | 现状（实测） | 本方案裁定 |
|---|---|---|
| `change_log` | 每次保存写 1 行 UPDATE，`old/new` 恒 null，7 天后剪除，**读方法零生产调用者** | **赋责**：升级为「变更审计 + 灾备差分」——写入真实变更摘要（表名/主键/字段集），读面接上：① 本地审计视图（诊断「哪次保存改了什么」）② 云灾备的差分比对源 ③ §2.3 归档索引 |
| `FunctionalWAL` | 包住 save 事务但**不承载数据字节**，`recover()` 仅记日志；耐久性完全由 Room 事务承担 | **摘除**：解除 DI 绑定与调用点，事务编排职责明确归 Room。理由：养一个"看起来在保护存档"的组件比没有更危险（已有 `WalRetirementGuardTest` 说明退役路径已铺好）。**注意**：摘除动作牵动 `StorageCoreFacade`/`StorageInfraFacade`/`StorageModule`，需与 G 批/内存册对表（§七） |
| 归档表 / `DataArchiver` | 写入正常，**查询面零生产调用者**（`ArchiveWriteOnlyGuardTest` 钉死"无读者"） | **赋责**：接上读面——服务 §2.3 的「恢复旧档」入口 + 战斗日志历史。同一个读面同时修掉"只写不读"与"归档数据换不来收益" |
| `StorageMetrics` | 8 个计数器**无任何 getter**，只写不可读 | **赋责**：补 getter + 接入埋点上报（`rules/data-analytics.md` 三处同步）。它是本方案验证「云一致性 / 流水与缓存差额 / 存档失败率」的前提，不是可选装饰 |

### 2.7 云存档：灾备 + 换设备续玩

复用现有 `SaveArbiter` / `UploadLedger` / `UploadQueue` / `TapTapSaveBackend`（**不新建云通道**）：

- **槽位维度坍缩**：单档后云端只用 `slot_1` 命名；`slot 0 = mnzm_cloud_save` 作为**存量迁移源**保留读取能力（用于把老玩家的旧云端单档收编）。
- **上传**：关键事件立即入队 + 定时兜底；沿用 2s 去抖 / 同槽留最新 / 60s 限频 / 30s→10min 退避 / 5 连败熔断 5min。
- **换设备续玩**：新设备登录 → 本地无档 → `AutoEntryResolver.resolve()` 的 `LoadCloud` 分支（**已存在**，`AutoEntry.kt:15-19`）→ 下载 → 落本地。
- **不支持同时多设备（Q6）**：本地每次保存前比对云端 `currentCloudSaveId()`（W）；若 `W > 本地 C` → 判定"进度已在其他设备更新" → **降级为只读并提示**，绝不静默覆盖。这是无服务端发号前提下唯一能避开序号撞车的策略（`UploadLedger.kt:33-36` 的序号是本地自增）。
- **不做历史版本云备份**：TapTap 单档 10MB 上限与云槽位有限；"历史版本"由 §2.3 的**本地归档**承担（与 Q3 一致）。

### 2.8 全部功能要求登录（Q5）

- 入口收敛到 `LoginFlowStateMachine`：未登录 → **强制登录页**，作为唯一入口分支。**禁止**在 Activity 新增"只置位不复位"的一次性布尔标记（`rules/sdk-init-lifecycle.md` 原则 5 红线）。
- **登出清单从四件套升为五件套**：`clearSession` + `TapTapAuthManager.logout` + `TapDBManager.stopGameDurationTracking` + `ComplianceManager.unregisterCallback` + **关闭当前账号数据空间（清 `.current`）**。三处登出入口必须一致（`GameActivity.onLogout` / `performComplianceLogout` / `ComplianceVerificationScreen.onLogout`；规范里第四处 `ModeSelectionScreen.onLogout` **已随主界面退役删除**，规范文件需同步修正）。
- **离线宽限（已拍板 B1）**：已登录过且本地有档 → 允许离线进入；从未登录 → 必须联网首次登录。
- **隐私合规（`隐私合规` 标签，强制）**：本次变更登录门槛与云上传频率 ⇒ 必须同步更新两处隐私政策：`PrivacyConsentScreen.kt` + `docs/index.html`（`rules/commercialization.md` §6 双入口强制）。

### 2.9 YAGNI 反向检查（`rules/design-plan-review.md` 第三节：每个新抽象必须有当前生产消费者）

| 新抽象 / 新接口 | 当前生产消费者 | 结论 |
|---|---|---|
| C++ 账本协议字段（`models.h`） | `jade_tx.h` 六事务 append + Kotlin 镜像投影 | 保留 |
| 流水派生纯函数 | C++ 余额校验 + 读档对账 + UI 余额展示（`JadeSymbolBadge`） | 保留 |
| `DirtySetTracker` | `StorageEngineWriteOps.writeAllDataToDatabase` 增量路径 | 保留 |
| `SlotEntityIndexDao` | 增量路径的删除检测 | 保留 |
| `LegacySlotImporter` | 首次启动的存量多档迁移 | 保留 |
| 归档读面（`DataArchiver` 查询族） | 设置页「恢复旧档」入口 + 战斗日志历史 | 保留 |
| `StorageMetrics` getter | 云一致性诊断 + 流水/缓存差额 + 存档失败率上报 | 保留 |
| ~~云端增量上传协议~~ | **无**（TapTap 单 blob 语义） | **不出现在方案内**，已移入债表（§八） |
| ~~服务端发号机制~~ | **无**（无服务器） | **不出现在方案内**，已移入债表（§八） |
| ~~增量落盘的独立压缩/编码格式~~ | 无（复用现有 `ProtobufConverters` 编码） | 不引入 |

**结论**：本方案未引入任何"计划批准但无生产消费者"的抽象；两处无消费者的设想已移入债表，不占方案正文。

### 2.10 规范交叉核对（`rules/design-plan-review.md` 第五节）

| 规范文件 | 是否触碰 | 结论 |
|---|---|---|
| `rules/database-migration.md` | 是 | 新表走完整迁移（v66）+ schema JSON 提交 + 集成测试；**不删任何列**；版本号唯一来源 `GameDatabaseConfig.DATABASE_VERSION`；新列带 DEFAULT |
| `rules/economy-design.md` | 是（`经济`） | 玉符源汇闭环不变；新增流水 `reason` 全部登记经济基线表；`经济` 标签见 §三 |
| `rules/commercialization.md` | 是（`隐私合规`） | 不新增付费点位；隐私政策双入口强制更新（§2.8） |
| `rules/sdk-init-lifecycle.md` | 是 | 登出四件套 → 五件套；登录流程收敛 `LoginFlowStateMachine`，禁新增"只置位不复位"布尔标记；同时修正该文件已过期的 `ModeSelectionScreen.onLogout` 行 |
| `rules/code-quality.md` §1.5 | 是（`iOS`） | `accountKey` 派生 / 流水派生 / 归档格式 / 云仲裁逻辑全部下沉 `:core:domain` 纯函数，双端共用同一套规则 |
| `rules/testing.md` | 是 | 守卫测试三要素；测试一律 `--max-workers=1` |
| `rules/pr-review-checklist.md` | 是 | 玉符「消耗/发放必须收敛于 `JadeSymbolService`」条目保持不变且被加强（升级为流水守卫） |
| `android/core/data/AGENTS.md` | 是 | 存档入口纪律、ProtoBuf 契约（仅 `List` / `@EncodeDefault` / 号禁复用）、槽位隔离假设**保留不破坏** |
| `rules/design-plan-review.md` | 是 | 本方案编写依据 |
| `docs/threading-contract.md` | **否** | 不新增跨线程交互；增量落盘仍在既有 IO 协程 + Room 事务执行器内 |

**无冲突**。

---

## 三、影响范围清单

> 格式：`文件路径 — 变更类型 — 变更说明`。标签：`经济` / `iOS` / `隐私合规`。
> 证据等级：**A** = 本次亲自读代码确认；**B** = 摸底报告结论（行号未重锚）；**C** = 需实施时核对。

### 3.1 存储与迁移（`:core:data`）

| 文件路径 | 变更 | 说明 |
|---|---|---|
| `android/core/data/src/main/java/com/xianxia/sect/data/local/GameDatabase.kt` | 改 | 按名建库（accountKey 分库）；`DATABASE_VERSION` 65 → 66（A：当前 = 65，`:95`）；v66 迁移内容取决于账本载体选型（见下方"账本存储载体"行） |
| `android/core/data/src/main/java/com/xianxia/sect/data/local/GameDatabaseMigrationsV66.kt` | 新增（条件） | 仅当账本落 Room 列时建表（幂等、列带 DEFAULT）；若走 `GameHeavyData` 新 key 载体则**不建表**，仅登记 key 与 schema 变更 |
| `android/core/data/src/main/java/com/xianxia/sect/data/StorageConstants.kt` | 改 | `DEFAULT_MAX_SLOTS` 语义收敛为单档（A：当前 = 6，`:20`，且 `SlotLockManager.kt:31` / `StorageConfig.kt:111` 各有一份重复定义 → 一并收敛为单一真源） |
| 账本存储载体（**不新建 Room 表**） | 改 | 账本真源在 C++（§2.4）。持久化载体二选一，实施时按实测体积定：① `GameHeavyData` 新增 key（复用既有分块 heavy 读写链）② `GameData` 新字段（走 TypeConverter 列） |
| `android/core/domain/src/main/java/com/xianxia/sect/core/model/GameData.kt` | 改 | `jadeSymbols` 语义降级为物化缓存（保留字段与 proto 号，**不删列**） |
| `android/core/data/src/main/java/com/xianxia/sect/data/model/SaveData.kt` | 改 | 新增流水序列字段（`@ProtoNumber` 取预留段 + `@EncodeDefault`） |
| `android/core/data/src/main/java/com/xianxia/sect/data/backup/SaveFileManager.kt` | 改 | 增加归档导出/导入能力（复用现有 `atomicWrite` + CRC32C） |
| `android/core/data/src/main/java/com/xianxia/sect/data/archive/DataArchiver.kt` | 改 | 接上读面（§2.6） |
| `android/core/data/src/main/java/com/xianxia/sect/data/incremental/ChangeLogPersistence.kt` | 改 | 从"空行埋点"升级为真实变更摘要写入（§2.6） |
| `android/core/data/src/main/java/com/xianxia/sect/data/wal/FunctionalWAL.kt` + `WALProvider.kt` | 删/退役 | 摘除调用点与 DI 绑定（§2.6） |
| `android/app/src/main/java/com/xianxia/sect/di/StorageModule.kt` | 改 | 移除 WAL 绑定；数据空间路径改为 accountKey 维度 |
| `android/core/data/src/main/java/com/xianxia/sect/data/engine/StorageEngine.kt` | 改 | 事务内 append 流水；读档时流水 ↔ 缓存对账；旧库一次性迁移入口 |
| `android/core/data/src/main/java/com/xianxia/sect/data/engine/StorageEngineWriteOps.kt` | 改 | **全删全写 → 增量写**：`clearOldSlotEntities`（12+ 表 `deleteAll`）与 `clearHeavyDataByPrefix`（7 key 全删）改为增量路径 + 全量兜底；未变 heavy key 跳过（A：`:126-148`、`:67-77`、`:80-108`） |
| `android/core/data/src/main/java/com/xianxia/sect/data/engine/DirtySetTracker.kt` | 新增 | 自上次落盘以来的脏集（脏表 / 脏实体 id / 脏 heavy key / 需全量标记），来源 = Kotlin 镜像已收到的 `applyDirty` 变更集 + 本地变更 |
| `android/core/data/src/main/java/com/xianxia/sect/data/local/SlotEntityIndexDao.kt` | 新增 | 已落盘实体 id 影子集，供增量路径的删除检测（每槽每类一行） |
| `android/core/data/src/main/java/com/xianxia/sect/data/local/LegacySlotImporter.kt` | 新增 | 存量多档 → 单档 + 归档（§2.3） |

### 3.2 引擎与玉符（`:core:engine`）

| 文件路径 | 变更 | 说明 |
|---|---|---|
| `android/core/engine/src/main/java/com/xianxia/sect/core/engine/service/JadeSymbolService.kt` | 改 | `checkpointNow`/`settleGrants` 停止绝对值覆盖写，改为仅重锚缓存；所有变动经 C++ 账本事务（A：覆盖写位于 `:266-275` / `:415-421`） |
| `android/app/src/main/cpp/gamecore/include/gamecore/state/models.h` | 改 | 新增账本协议字段——**R2 锁表内，须串行**（A：玉符四字段在 `:1234-1237`） |
| `android/app/src/main/cpp/gamecore/include/gamecore/system/jade_tx.h` | 改 | 六事务（1766–1769 / 1692 / 1693）改为 append 账本条目 + 派生缓存校验（A：红线声明在 `:24-37`） |
| C++ 分派入口（`execute_dispatch.cpp` / `dispatch_w4b.cpp`） | 改 | 新增账本事务的分派分支 |
| `android/core/engine/src/main/java/com/xianxia/sect/core/nativebridge/ActionIds.kt` + `scripts/gen-action-ids.mjs` 五件套 | 改 | 新 ActionId 走 codegen 双产物，**禁止手改生成物** |
| `android/app/src/main/cpp/gamecore/test/jade_tx_test.cpp` + `jade_runtime_tx_test.cpp` | 改 | 逐值锁定现有绝对值语义的 GTest（17 + 18 例）须同步改写——对拍双守护硬要求 |
| `android/core/engine/src/main/java/com/xianxia/sect/core/engine/gameview/GameDataFieldPatch.kt` | 改 | 登记账本字段到 `coveredFields`（否则 `GameDataFieldPatchGuardTest` 红）（A：`jadeSymbols` patch 位于 `:175-178`） |
| `android/core/engine/src/main/java/com/xianxia/sect/core/engine/GameEngineJadePurchaseOps.kt` | 改 | 消耗改走流水 append；保留"先扣后抽 + sealed 三态 + 事务外发布"骨架（A） |
| `android/core/engine/src/main/java/com/xianxia/sect/core/gameview/GameDataFieldPatch.kt` | 改 | C++ 反向字段补丁**只允许 patch 缓存**且必须补对账流水（A：`jadeSymbols` patch 位于 `:175-176`） |
| `android/core/engine/src/main/java/com/xianxia/sect/core/engine/GameEngineSaveOps.kt` | 改 | 事件触发 → `SaveOrchestrator.submit` + `flushNow`（§2.5） |
| `android/feature/game/src/main/java/com/xianxia/sect/ui/game/SaveLoadViewModelAutoSaveOps.kt` | 改 | 接入事件触发入口（A：节拍常量位于 `:21`） |

### 3.3 UI（`:feature:game` / `:app`）

| 文件路径 | 变更 | 说明 |
|---|---|---|
| `android/feature/game/src/main/java/com/xianxia/sect/ui/game/tabs/SettingsTab.kt` | 改 | 移除"存档管理"多槽弹窗（A：`SaveSlotDialog` 位于 `:895`，保存键 `:1208`，删档确认 `:1063-1074`）→ 改为「立即备份」+「恢复旧档」 |
| `android/feature/game/src/main/java/com/xianxia/sect/ui/game/saveload/CloudSlotEntryCard.kt` | 改/删 | 云端槽位卡片在单档下无意义 → 收敛为"云端备份状态"单卡（A：该文件已迁至 `feature/game/.../saveload/`） |
| `android/feature/game/src/main/java/com/xianxia/sect/ui/game/saveload/SaveMigrationCard.kt` | 改 | 存量迁移卡改为"已完成迁移"提示 + 旧档恢复入口（A：该文件已迁至 `feature/game/.../saveload/`） |
| `android/feature/game/src/main/java/com/xianxia/sect/ui/game/SaveLoadViewModelSlotManageOps.kt` | 改 | `deleteSlot` 语义变为"清空唯一进度"（A：`:21`），需更重的二次确认 |
| `android/app/src/main/java/com/xianxia/sect/ui/model/AutoEntry.kt` | 改 | 单档下 `LoadLocal` 不再需要选最新（唯一档）；保留 `LoadCloud` / `CreateNew`（A） |
| `android/app/src/main/java/com/xianxia/sect/ui/game/GameActivity.kt` + `MainActivity.kt` | 改 | 登出入口扩为五件套；登录前置成为硬门槛（A：`onStop` 位于 `:857`；登出入口三处） |
| `android/app/src/main/java/com/xianxia/sect/ui/PrivacyConsentScreen.kt` | 改 | `隐私合规` 双入口之一 |
| `docs/index.html` | 改 | `隐私合规` 双入口之二 |

### 3.4 跨平台

| 面 | 说明 |
|---|---|
| `iOS` | `accountKey` 派生纯函数下沉 `:core:domain`；数据空间目录结构、流水派生、归档格式、云仲裁四者与 Android 逐条对等；iOS 无 Keystore 等价能力差异（本方案不依赖设备绑定密钥） |

### 3.5 玉符流水改造的其余触点（本次实测补充，证据 A）

| 文件路径 | 变更 | 说明 |
|---|---|---|
| `android/feature/game/src/main/java/com/xianxia/sect/ui/game/main/JadeSymbolBadge.kt` | 改 | 余额展示改读派生值（`:29-35` 形参 `jadeSymbols`） |
| `android/feature/game/src/main/java/com/xianxia/sect/ui/game/dialogs/MerchantDialog.kt` | 改 | 商人刷新消耗走流水（`:154` / `:234` / `:242`） |
| `android/feature/game/src/main/java/com/xianxia/sect/ui/game/components/JadePurchaseFlow.kt` | 改 | 不足判定改读派生余额（`:50` / `:63` / `:116-120`） |
| `android/feature/game/src/main/java/com/xianxia/sect/ui/game/DiscipleDetailScreen.kt` | 改 | 展示位（`:434`） |
| `android/feature/game/src/main/java/com/xianxia/sect/ui/game/MainGameScreen.kt` | 改 | 顶栏展示（`:1361`） |
| `android/core/engine/src/main/java/com/xianxia/sect/core/engine/GameEngineInventoryOps.kt` | 改 | 保留"禁止在此直接写 `jadeSymbols`"约束，并把静态断言升级为流水守卫（`:119`） |

**测试类（12 个，需同步改造或核对）**：`JadeSymbolConsumptionGuardTest`（守卫升级为流水守卫）、`JadeSymbolServiceTest`、`GameEngineJadePurchaseTest`、`JadeNativeTxGateTest`、`JadeRuntimeNativeTxGateTest`、`JadeSymbolNonNegativeRuleTest`、`MerchantDialogJadeFlowTest`、`JadePurchaseFlowTest`、`GameEngineCoreJadeReloadInterleavingTest`（竞态断言改为"流水不被旧值覆盖"）。

> ⚠️ **其中 `DiffTimeTest`（`:143` 断言"玉符不得被修改"）、`DiffStateTest`（`:128-129`）、`DiffStateSyncTest`（`:195` / `:219`）属 G 批 R2「Diff 夹具」文件级锁范围**——本方案对三者**只加不改**（新增流水相关断言，不改既有断言语义），且必须与 G 批对表后进行（参见 §七）。

### 3.6 影响面统计 / 误伤点 / 净收益点（影响面普查实测，2026-10-01）

**统计**：约 105 个 Kotlin 生产文件 + 6 个 C++ 协议文件；**27 实体中 26 张带 slot 维度**；**18 个 DAO / 189 个带 slot 参数的方法**；`SaveSlot` 6 个生产构造点、16 个消费文件；云端 ≥45 个带 slot 的公开方法；`src/test` 约 60 个相关文件。

**误伤点（盲改 `slotId` / `slot` 必然打到，须显式排除）**：

| 文件:行 | 符号 | 真实语义（**非存档槽**） |
|---|---|---|
| `feature/game/.../SpiritMineViewModel.kt:163,254` | `slotId = "spiritMine_miner_$slotIndex"` | 矿工指派 ID |
| `feature/game/.../DiscipleDetailScreen.kt:350,548` | `disciple.equipment?.slotId(part)` | 装备六部位 |
| `core/domain/.../model/SlotAssignment.kt:41-50` | `slotId: String`（如 `elder_viceSectMaster`） | 长老 / 生产指派 ID |
| `core/engine/.../state/CaptiveGearUtils.kt:54-63,189` | `discipleTables.slotIdOf(...)` | 装备部位 |
| `core/engine/.../repository/ProductionSlotRepository*.kt` | `getSlotById` / `removeSlot`（String id） | 生产槽位（**但** `initializeAllSlots` / `restoreSlots` / `clear` 的 `slotId: Int` 确实是存档槽） |
| `cpp/.../execute_dispatch.cpp:312` | `currentSlots = computeSlotCount(state)` | 仓库槽数 |

**净收益点（可整表删除、不触发数据迁移）**：
- `save_slot_metadata` 表的**5 个读方法**（`SaveSlotMetadataDao.kt:15,18,21,24,27`）**生产代码零消费者**（全仓 grep 仅 `:13 upsert` 与 `:31 deleteBySlotId` 被调用）。单存档下该表退化为"1 行常量表" ⇒ **本方案删除整表（已拍板 B4）**。
- **代价**：删表会立即触发 `ClearAllSlotTablesCoverageTest`（清单 = `@Database` 实体数守卫）与 `CacheWriteAtomicityGuardTest`（须整体在 `withTransaction` 内），并需同步 `GameDatabase` 实体注册（§4 回退行）。

**必须一起收敛的既有分叉（已拍板 B5：同批收敛）**：
- `DEFAULT_MAX_SLOTS = 6` 有**三处独立字面量**：`StorageConstants.kt:20`、`StorageConfig.kt:111`、`SlotLockManager.kt:31`。
- **实际生效的是 `SlotLockManager`**（DI 默认值 `StorageModule.kt:34` 不传参），而 `StorageEngine.kt:519` 的槽位循环用 `lockManager.getMaxSlots()`、**不是** `storageConfig.maxSlots` ⇒ `StorageConfig.maxSlots`（`:36-37`）生产零消费者，配置面与实际面**早已分叉**。
- 槽位遍历口径**已不一致**：`DataPruningScheduler.kt:24` 遍历 `0..6`（7 个，含云槽）、`DataArchiveScheduler.kt:31` 遍历 `1..6`（6 个），后者 `:28-30` 注释自陈此事 ⇒ **slot 0 的 battle_logs 会被裁剪但永不被归档**（既有缺陷，单存档会把差异放大）。

**另外两条必须尊重的约束**：
- `RepoInterfaces.kt` 有 **35 处 `slotId: Int = 0`** 默认值，而 `0` = 云会话伪槽且 `isValidSlot(0)` 为真 ⇒ **漏传参数会静默读写错误的槽且不报错**。单存档改造须消灭该默认值（改为显式唯一槽常量）。
- `SaveMigrationCoordinator.kt:363-375` 上报四个**逐槽计数**埋点（`PROP_MIGRATION_*_TOTAL`）⇒ 分母从 6 变 1，须按 `rules/data-analytics.md` 三处同步，否则运营完成率曲线会出现台阶式跳变被误读为回归。

---

## 四、兼容性分析

| 面 | 策略 |
|---|---|
| Room 迁移 | `DATABASE_VERSION` 65 → 66；v66 内容取决于账本载体（落 Room 列则建表，落 `GameHeavyData` key 则仅登记 key 与 schema 变更）；**不删任何列**；`MigrationChainGuardTest` 链尾断言自动覆盖 |
| `saveVersion` 格式轴 | 2 → 3（新增流水序列字段）；`SaveDataVersionMigrator` 增 `v2 → v3`（老档无流水 → 生成 `OPENING_BALANCE` 期初）；`> CURRENT` 仍拒载 |
| ProtoBuf | 新字段取 1000+ 预留段；**仅 `List`**（禁 `Set`/`Map`）；非零默认值标 `@EncodeDefault(ALWAYS)` |
| 旧档 | ① 槽位结构不变（`slot_id` 恒 1）② 旧库整体保留为 `xianxia_sect.db.legacy_*` ③ 旧档无流水 → 期初流水补齐 |
| 云档 | `slot 0 = mnzm_cloud_save` 读取能力保留（存量收编）；`slot_1` 单档命名；版本低的老云档走同一 `v2 → v3` 迁移链 |
| 回退 | 方案按批合入 → 单批 revert；数据库层不删列/不删库 ⇒ 回退无数据损失；`FunctionalWAL` 摘除需独立 revert 单元 |
| 降级 | 高版本档被低版本 App 打开 → 现有 `Rejected` 拒载语义不变（不损坏数据） |

---

## 五、测试方案

### 5.1 单元与集成

| 类型 | 内容 |
|---|---|
| 流水 | append-only 不可变；派生余额 == `期初 + Σdelta`；`balance_after` 与派生值一致；重复读档幂等；**跨语言对拍**（C++ GTest 黄金序列 + JUnit `Diff*Test`，两侧齐备） |
| **增量 ↔ 全量双路径对拍** | 同一内存状态分别走增量与全量路径落盘 → 读回**逐字段全等**（本方案硬门禁）；脏集形状覆盖新增/删除/修改三类；脏集不可判定或 id 越界时必须回退全量 |
| 迁移 | `MIGRATION_65_66` 幂等 + Room `onValidateSchema` 真实校验；老档 → 期初流水余额不变 |
| 存量迁移 | 多档取最新正确；其余归档可恢复且内容逐字段相等；中断后重跑幂等；旧库保留 |
| 账号隔离 | 切换账号后 A 不可见 B 的任何数据（表级断言）；并发登录/登出无残留 `.current` |
| 事件落盘 | 玉符流水/碎片/高品阶物品事件返回前已完成落盘（断言时序）；十连抽 10 次事件 → 合并为 **1 次**落盘 |
| 云 | 换设备 `LoadCloud` 链路；`W > C` → 只读降级不覆盖；上传退避/熔断行为不变 |
| 持久化收口 | `change_log` 读面消费；归档读面消费；`StorageMetrics` getter 有真实消费者；`FunctionalWAL` 摘除后启动/保存/读档全绿 |

### 5.2 守卫测试（`rules` §9.5 三要素）

1. **流水守卫**（枚举驱动）：账本 append-only（C++ 侧不提供账本条目改写/删除接口；Kotlin 镜像面不得篡改账本字段）；业务代码不得绕过账本事务写余额。
2. **派生一致性守卫**：读档后 `GameData.jadeSymbols` 必须等于派生值，否则红。
3. **账号隔离守卫**：以数据空间为锚点遍历所有 Room 表，断言按 accountKey 分库后不存在跨账号可见路径；`intentionallyExcluded` 显式声明例外（如全局配置表）。
4. **单档守卫**：`slot_id` 在生产代码中的取值集合 ⊆ {1}。
5. **账本守卫**：`JadeSymbolConsumptionGuardTest` 的正则应覆盖**账本字段名**（现只认 `jadeSymbols` ⇒ 新字段写入会静默漏检）；账本 append-only；派生余额 == 账本求和。
6. **跨语言常量守卫**：`GameConfig.Jade.INTERVAL_MS` / `DAILY_CAP`（`GameConfig.kt:328-330`）与 `jade_tx.h:397-399` 当前靠注释声明同值、**无测试锁相等**——随账本改造一并补守卫。

### 5.3 墙钟成本核算（`rules/design-plan-review.md` 第四节）

- 新增守卫均为静态源码扫描或单次确定性断言 → **< 1 秒/类**。
- 迁移测试用 Robolectric + 真实 `Room.databaseBuilder`（先例 `RoomMigrationV64To65Test`）→ 约 2–5 秒/类。
- **不引入迭代型守卫**（无 100/1000 次循环）；全量测试预算仍在 ~10 分钟内。

### 5.4 对抗性审查要点

流水无 UPDATE/DELETE 是否真成立（含 Room 自动生成的 `@Update`）/ C++ 反向补丁绕过账本 / 十连抽跨合并窗边界 / 增量路径漏删（`persisted - pulled`）导致幽灵行 / 增量与全量读回不等 / 脏集溢出未回退全量 / 首次落盘无基准却误走增量 / 双路径对拍自身是否覆盖删除场景 / 迁移中断于归档写到一半 / 换账号时旧数据空间的 `.sav` 与 `.bak` 是否泄漏 / 登出五件套漏一处 / Trim 是否误清流水与归档索引 / 备份（`allowBackup`）是否把多账号数据空间一起带走。

---

## 六、风险评估与兜底

| 风险 | 等级 | 兜底 |
|---|---|---|
| 分库改造导致老玩家进度丢失 | 高 | 旧库永不删除（改名保留）+ 迁移 `pending/done` 幂等标记 + 迁移前 `.bak` |
| 玉符改造破坏现有经济 | 高 | 期初条目余额不变（**漏写即存量玩家玉符清零**）+ 现有 12 个玉符相关测试类与两侧 GTest 断言逐条保留 + 守卫升级而非放宽 |
| 玉符账本落 C++ 撞 G 批 R2 文件锁 | 高 | 与 G 批/内存册对表**串行合入**（`models.h` / `jade_tx.h`）；对拍双守护齐备后才允许动基线（§七） |
| 白名单玉符收入无上限（**产品有意设计**，非缺陷） | 低 | 账本如实记录来源；经济建模把"白名单 = 无上限来源"纳入（§2.4） |
| **存量档静默不迁移**（MMKV 台账孤儿键 + 门槛真空成立） | 高 | §2.3 强制处置：判据改为"旧库实际存在的档"、显式消费旧键、`done` 标记后置 |
| C++ 协议槽号被误改 ⇒ 跨语言对拍基线强制重录 | 高 | §2.1 决策"只钉常量、不改协议字段"；任何字段面改动须并入 G 批 R3 窗口 |
| `RepoInterfaces` 默认 `slotId = 0` 漏传静默错槽 | 中 | 单存档同批消灭该默认值（`isValidSlot(0)` 为真 ⇒ 不报错） |
| 删 `save_slot_metadata` 触发两条守卫连锁 | 低 | 与 `GameDatabase` 实体注册、`ClearAllSlotTablesCoverageTest`、`CacheWriteAtomicityGuardTest` 同批改（§3.6） |
| `FunctionalWAL` 摘除引发启动路径回归 | 中 | 独立 revert 单元；摘除前后对拍「保存/读档/启动」三条链的行为测试 |
| 换设备序号撞车导致覆盖 | 中 | `W > C` → 只读降级（§2.7），绝不静默覆盖 |
| 账号隔离面遗漏（某表未按库隔离） | 中 | §5.2 守卫测试以表清单为锚点穷尽遍历 |
| 与 G 批/内存册文件级撞车 | 高 | §七 协调章；本方案**不碰**被锁文件 |
| 无服务器下 IAP 无法合规 | — | 显式登记为技术债（§八），不引入付费通道 |

---

## 七、与在册方案的冲突协调（硬约束）

对照 `docs/design/character-gacha-implementation.md` 的 R1–R6：

| 规则 | 本方案应对 |
|---|---|
| **R2 文件级锁**（`models.h`(GameData)/`dirty_tracker.*`/`column_dirty.h`/`game_core.cpp` import/Diff 夹具/`CODE_WIKI.md`/双 changelog 同一时间只进一个方案） | **修正**：除玉符账本块外，本方案不碰 C++ 侧文件、不改导出形状。**但玉符账本块必须落 C++**（§2.4），会动 `models.h`（账本协议字段）与 `jade_tx.h`（六事务改造）——**这两处正是 R2 锁表内的文件 ⇒ 必须与 G 批/内存册对表串行合入，不得并行**。`CODE_WIKI.md` 与双 changelog 走"后合者以 main 为准 rebase" |
| **R3 对拍基线只允许一次权威重录窗口** | 本方案**不新增需要重录基线的变更**（不改 C++ 协议字段、不改导出形状）；若实施时发现必须重录 → 并入 G10 窗口，禁止独占第二次 |
| **R1 Trim 白名单** | 本方案把**玉符流水、归档索引、旧档恢复索引**加入"只释放资源不释放进度"白名单（G 批 R1 要求回写内存册，本方案一并登记） |
| G 批兼容性分析的 "Room: slot_id 隔离 + `resetForSlot`" 假设 | **本方案保留 `slot_id` 物理列与 `resetForSlot`**（§2.1）⇒ 该假设不被破坏，两方案零冲突 |
| 产品删档语义 | G 批 S3 写有"自动存档（产品已禁）"，与 2026-09-21 拍板及现状冲突 → 本方案以**用户 2026-10-01 新拍板**为准，并登记该文档漂移待修（§十盲区④） |

**结论**：与 G 批**无产品语义冲突**；**文件级冲突仅剩玉符账本块一处**（`models.h` / `jade_tx.h`），须对表串行合入。§2.1 的"不删 `slot_id`"消除了槽位语义冲突——G 批兼容性分析依赖的"`slot_id` 隔离 + `resetForSlot`"假设**不被破坏**。与内存册的交集同样仅限玉符账本块。

---

## 八、技术债与偿还计划

| 债项 | 产生原因 | 偿还时机（明确触发条件） |
|---|---|---|
| 云载荷增量上传未实施 | TapTap 云存档是**单 blob 语义**（10MB 上限），无法只上传差分；无服务器亦无法做服务端 diff | 服务端对象存储就绪后改增量上传（本地变更集已是现成输入） |
| IAP 付费通道未接入 | 无服务器 ⇒ 不满足 `rules/commercialization.md:19`「服务器确认后才发奖」 | 服务端能力就绪（含订单校验端点）后独立立项 |
| 多设备同时在线未支持 | 无服务端发号，本地自增序号会撞车 | 服务端发号能力就绪后，把 `UploadLedger` 序号来源切换为服务端 |
| 云端历史版本未提供 | TapTap 单档 10MB 上限 + 云槽位有限 | 服务端对象存储就绪，或 TapTap 侧支持多档托管时 |
| `ModeSelectionScreen.onLogout` 已死但仍在 `rules/sdk-init-lifecycle.md:50` | 主界面退役时未同步规范文件 | 本方案实施同批修正（非债，随批修） |
| 玉符常量跨语言手抄无守卫 | `GameConfig.Jade.INTERVAL_MS` / `DAILY_CAP`（`GameConfig.kt:328-330`）与 `jade_tx.h:397-399` 靠注释声明同值，无测试锁相等 | 账本改造同批补跨语言常量一致性守卫（§5.2 第 6 条） |

---

## 九、未来场景推演（≥6 个月）

| 维度 | 必答问题 | 本方案结论 |
|---|---|---|
| 规模增长 | 内容 ×10 时成本是否线性？ | 流水表随玉符变动笔数线性增长；已设归档阈值（流水按游戏年归档到 `archives`），增长可控 |
| 生命周期 | 构建/重启/重建/清缓存下行为一致？ | 数据空间以 accountKey 为锚，清缓存不影响（`filesDir` 非 `cacheDir`）；重建进程后 `.current` 决定打开哪个库 |
| 平台扩张 | iOS 端是否需重做？ | 不需要：分库/流水派生/归档格式/仲裁逻辑全部在 `:core:domain` + `:core:data`，iOS 复用；仅路径 API 与 SDK 调用对等实现 |
| 运营演进 | 6 个月内数值/活动调整是否需发版？ | 玉符上限/日闸/消耗价均在 `GameConfig` 常量与 `game-data.json`，调参不发版；新增流水 reason 需发版（可接受） |
| 兼容回退 | 上线后出缺陷能否关闭？ | 分库与流水为数据结构变更，**无开关**（一次性赌注）→ 靠"旧库不删 + 单批 revert"兜底 |
| **服务端接入（终局）** | 未来上服务器时是否重做？ | **不需要重做**：流水账即服务端对账的天然输入；`accountKey` 即账号维度键；上传协议已幂等（`saveId` 台账）。上服务端时只需把"权威写入点"从 `JadeSymbolService` 换成服务端接口 |

---

## 十、盲区自查与完善建议（末章）

> 逐维度自查，条目三要素：**盲点 + 潜在影响 + 完善建议**。实质性问题已回写正文。

**① 需求理解（离线宽限）** — 盲点：Q5"全部功能要求登录"与 TapTap 不可用/断网时的可玩性存在张力。**已于 2026-10-01 拍板（B1）**：已登录过可离线继续、从未登录必须联网首次登录（已回写 §2.8）。**本条已关闭。**

**② 需求理解（玉符与抽卡的关系）** — 盲点：用户列举"玉符新增和消耗"与"获得高品阶物品"作为触发点，但在册 G 批设计的抽卡消耗的是**灵石**（单价 5000），玉符是独立货币。潜在影响：若按"玉符=抽卡货币"设计触发点会与 G 批冲突。建议：本方案按"**玉符=独立货币，任何变动都触发**；抽卡产物按 G 批 `gacha_tx` 结果触发"实现（已回写 §2.5）。**待用户确认玉符的用途边界。**

**③ 边界与极端** — 盲点：账号隔离后 `allowBackup="true"` 会把**所有账号的数据空间**一起备份到用户云盘，换机恢复后多账号数据同时落地。潜在影响：与"账号隔离"的隐私预期不符（同一设备的历史账号数据会被恢复）。建议：在 `dataExtractionRules` 中显式声明备份范围（或整体排除），并做真机 D2D 恢复验证。

**④ 数据与兼容（在册文档漂移）** — 盲点：G 批 S3 写"自动存档（产品已禁）"，`rules/sdk-init-lifecycle.md:50` 仍列已删除的 `ModeSelectionScreen.onLogout`。潜在影响：实施者据过期文档做出错误判断。建议：本方案实施同批修正这两处（已登记 §八）。

**⑤ 系统耦合** — 盲点：`GameEngineCoreJadeReloadInterleavingTest` 覆盖"旧循环 finally 用旧运行时值覆盖新档玉符"的竞态。流水改造后该竞态的表现会变为"流水与缓存不一致"。建议：保留该测试类并追加"流水不为旧值覆盖"的断言（已回写 §5.1）。

**⑥ 非功能属性** — 盲点：流水表在长期高频消耗下可能膨胀（广告发放/商人刷新）。建议：按游戏年归档到 `archives`（复用 §2.6 归档读面），并在 `StorageMetrics` 暴露流水行数（已回写 §2.6/§九）。

**⑦ 流程盲区** — 盲点：分库改造**无回退开关**，若线上发现只读旧账号数据异常，只能发版修。建议：实施时按"分库打开 → accountKey 解析 → 迁移"三个独立 commit 切分，使 revert 粒度可控（已回写 §四回退行）。

**⑧ 测试盲区** — 盲点：现有 round-trip 只覆盖 mails 单面，**全档级"写→读→全字段比对"测试缺失**。潜在影响：新增流水字段若读侧丢弃，无测试会红。建议：本方案一并补一个全档级 round-trip 守卫（列为 §5.1 首行之外的独立项）。

**⑨ native 路径无 JVM 覆盖（重要）** — 盲点：`JadeNativeTxGateTest` / `JadeRuntimeNativeTxGateTest` 在 JVM 无生产 `.so`，**恒走 Kotlin 回退臂**（`JadeRuntimeNativeTxGateTest.kt:23-25` 明文）。潜在影响："C++ 扣费 → `applyDirtyFromNative` 带回余额 → `syncBalanceFromSnapshot` 重锚"这条链**只有 C++ GTest 单侧覆盖，跨语言无对拍**——而它正是账本改造要动的链。建议：改账本前先补该链的 `Diff*Test` 或真机 Instrumentation 验证（已回写 §5.1）。

**⑩ 影子函数未确认** — 盲点：`startGameLoop` 存在成员（`GameEngineCore.kt:743`）与同签名扩展（`GameEngineCoreSetOps1.kt:166`）双定义，二者都调 `jadeSymbolService.onLoopStart()`；Kotlin 成员遮蔽扩展 ⇒ 扩展版疑为死代码。潜在影响：改重锚路径时可能改到不执行的那一处。建议：实施前先编译期/反编译确认哪一处生效（登记为待确认）。

**⑪ UI 三读数必须收敛** — 盲点：玉符数量在 UI 上有三个读数——徽章读镜像投影（`MainGameScreen.kt:1361`）、说明框倒计时读运行时流（`GameViewModel.kt:275`）、消耗弹窗红字读镜像（`JadePurchaseFlow.kt:120`）。潜在影响：账本落地后会出现"徽章 20 / 弹窗说不足"。建议：收敛为单一读数（已回写 §2.4 目标模型）。

**⑫ 文档与代码漂移（勿按文档推测现状）** — 盲点：`syncJadeRuntimeAfterNative` 在 CHANGELOG / handover 中大量出现，但主源代码 grep **零命中**（随 G04 洗炼族下线一并消失）。潜在影响：实施者按文档推测现状会走错路。建议：账本改造前先按本方案 §2.4 的实测清单核对，不接受文档转述。

**⑬ 存量迁移"真空成立"陷阱（设计级）** — 盲点：升档门槛 `canPromoteToCloudOnly` 的"所有本地有档槽均已迁移"在槽列表收缩后**真空成立**（`SaveMigrationPlanner.kt:153-157` KDoc 自陈），叠加 MMKV 台账按槽分片 ⇒ 存量档静默退出迁移面。潜在影响：玩家换设备时发现云上无档。建议：判据改为"旧库实际存在的档"并显式消费旧键（已回写 §2.3）。

**⑭ 伪同形名误伤** — 盲点：`slotId` / `slot` 在 6 处生产代码中是"装备部位 / 生产槽 / 指派 ID / 仓库槽数"，与存档槽同名。潜在影响：机械替换会打破装备与指派系统。建议：改造以本方案 §3.6 的误伤点表为排除清单（已回写）。

---

## 附：本方案不做的事（显式排除，非"后续优化"）

- 不引入 IAP / 付费通道（无服务器，不满足合规前置）
- 不引入服务端权威（前提约束）
- 不做多设备同时在线（Q6）
- 不做云端历史版本（由本地归档承担）
- 不做云载荷增量上传（TapTap 单 blob 语义所限，登记为技术债见 §八）
- 不改 C++ 侧状态模型与导出形状（G 批 R2/R3 文件级锁）
