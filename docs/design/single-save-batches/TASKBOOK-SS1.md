# TASKBOOK-SS1 · 账号数据空间分库 + 登出五件套

> **本文件是 SS1 的派工真源**。上位方案：[`../single-save-and-persistence-consolidation-plan-2026-10-01.md`](../single-save-and-persistence-consolidation-plan-2026-10-01.md) §2.2 / §2.8。
> 协议：[`DISPATCH-ledger.md`](DISPATCH-ledger.md) §3 + [`../gacha-batches/EXECUTION-PROTOCOL.md`](../gacha-batches/EXECUTION-PROTOCOL.md)。
> 入口判据：**SS0 已合入**、工作树干净。
> 证据等级：行号为影响面普查 + 主线程直读实测；`Room.databaseBuilder` 的具体调用形态**实施时以 `grep` 定位**。

---

## 1. 目标与验收判据

| 项 | 内容 |
|---|---|
| 业务目标 | 本地数据从"按设备归属"改为"**按账号隔离**"：一个账号一个数据空间（分库 + 分目录），换账号即切空间，且**不删库**（换回来进度还在）。同时把登出补成"五件套"。 |
| 验收① | 数据空间按 `accountKey` 落盘：`filesDir/accounts/<accountKey>/{xianxia_sect.db(+ -wal/-shm), saves/, archives/, legacy_import/}`；`accounts/.current` 标记当前活跃空间 |
| 验收② | **换账号不串档**：以 `@Database` 实体清单为锚点遍历的**隔离守卫测试**绿（断言不存在跨 `accountKey` 可见路径；`intentionallyExcluded` 显式声明例外并注明理由） |
| 验收③ | **登出五件套**（原四件套 + 关闭当前数据空间）在**三处登出入口逐字一致**：`GameActivity.onLogout` / `MainActivity.performComplianceLogout` / `ComplianceVerificationScreen.onLogout`。⚠️ 规范里第四处 `ModeSelectionScreen.onLogout` **已随主界面退役删除**，本批同批修正 `rules/sdk-init-lifecycle.md:50` |
| 验收④ | 登出**只清 `.current`、不删数据空间**；再次登录同一账号进度仍在 |
| 验收⑤ | 无账号标识时不建库（引导登录）——这是分库的必然前提；**离线宽限与隐私政策不属本批**（归 SS8） |
| 验收⑥ | 账号隔离键派生逻辑**下沉 `:core/domain` 纯函数**（双端共用），不在 Android 侧内联 |
| **不做** | 不改槽位数语义（SS3）；不收敛旧 MMKV 台账键（SS4）；不改云端链路（SS7）；不落"全部功能要求登录"的完整门槛（SS8）；**不碰 C++** |

---

## 2. 实测现状

### 2.1 当前是"按设备归属、与账号零绑定"

- 数据库文件固定名（`getUnifiedDatabaseFile(context)`，`GameDatabase.kt:579` 附近），全设备一份。
- `GameDatabase.kt`：`@Database(` 在 `:136`，`version = GameDatabaseConfig.DATABASE_VERSION` 绑定在 `:241`，`DATABASE_VERSION = 65` 在 `:95`；迁移前备份 `backupDatabaseForMigration` 在 `:485`（调用点 `:543`）、启动恢复 `restoreFromBackupIfNeeded` 在 `:763`。
- **登出链只清会话，不清档、不隔离**（摸底报告 §12 结论）；本批要把"数据空间切换"补进去。

### 2.2 登出四件套（`rules/sdk-init-lifecycle.md` §"登出完整清单"）

`clearSession` + `TapTapAuthManager.logout` + `TapDBManager.stopGameDurationTracking` + `ComplianceManager.unregisterCallback`。

**新登出入口必须复制完整四件套，禁止只做 `clearSession()`** —— 本批追加第五件：**关闭当前账号数据空间（清 `.current`）**。

### 2.3 分库的连带面（必须同批处理）

| 面 | 说明 |
|---|---|
| 迁移前备份 / 启动恢复 | 路径须随库名走（`xianxia_sect.db.pre_migrate_backup.v{N}` 与 `.restore_attempted` marker 都在同目录） |
| `.sav`/`.bak`/`.deleted` | `SaveFileManager` 的 `saves/` 目录须落在账号空间内（`getSavFile/getBakFile/getTmpFile`，`:388-390`） |
| 归档目录 | `archives/*.arc` 落在账号空间内 |
| MMKV / SharedPreferences | **本批不动**（台账键收敛归 SS4）；但须在报告里登记"MMKV 仍是全局的"这一残留面 |

---

## 3. 决策

| # | 决策 | 依据 / 代价 |
|---|---|---|
| **D-1** | **`accountKey` = TapTap 账号标识经 SHA-256 截断**后的文件安全串；**不落明文标识到文件名** | 文件名会出现在备份/日志/文件管理器里，明文标识属不必要暴露 |
| **D-2** | **目录结构**：`filesDir/accounts/<accountKey>/…` + `accounts/.current` | 与 `cacheDir` 分离（清缓存不影响档）；账号维度可见可审计 |
| **D-3** | **派生逻辑下沉 `:core/domain` 纯函数**（哈希/命名/合法性校验） | `rules/code-quality.md` §1.5 跨平台对等：iOS 侧复用同一套命名规则 |
| **D-4** | **登出 = 清 `.current`，不删空间** | 单存档 + 账号隔离下，删库等于删玩家进度；换回来应还在 |
| **D-5** | **无账号标识 ⇒ 不建库 + 引导登录** | 没有账号就没有隔离键；建"匿名空间"会在 SS8 落地后变成孤儿数据 |
| **D-6** | **本批不动 MMKV / 云端链路** | 保持每批独立可验收；台账键与云链路分别归 SS4/SS7 |

---

## 4. 文件面与切片（≤10 文件/片）

| 片 | 允许改 | 禁止改 | 自检项 |
|---|---|---|---|
| **SS1-a** 账号键与应用域纯函数 | `core/domain/.../account/AccountKey.kt`（**新**，纯函数）＋ 其单测 | Android API、`Context` | 纯函数、无 Android 依赖；同输入同输出；非法输入有定义行为 |
| **SS1-b** 数据空间路径 | `GameDatabase.kt`（库名 + 迁移备份/恢复路径随库名）、`StorageModule.kt`（路径注入） | 迁移链、`DATABASE_VERSION` | 库名/备份名/`.restore_attempted` 全在同一账号目录；默认库名路径不再被使用 |
| **SS1-c** 登出五件套 | `MainActivity.kt`（`performComplianceLogout` / 统一登出收敛）、`GameActivity.kt`（`onLogout`）、合规验证界面登出回调 | 登录状态机语义 | 三处逐字一致；不新增"只置位不复位"的一次性布尔标记（`rules/sdk-init-lifecycle.md` 原则 5） |
| **SS1-d** 隔离守卫 | 新增 `AccountDataIsolationGuardTest`（以 `@Database` 实体清单为锚点） | 生产源 | 断言消息带操作指引；`intentionallyExcluded` 逐项注明理由 |
| **SS1-e** 规范修正 | `rules/sdk-init-lifecycle.md:50`（删除已退役的 `ModeSelectionScreen.onLogout` 行 + 登出清单升为五件套） | 其它规范 | 改后跑 `node scripts/check-agent-instructions.mjs` |

**共享面**：`StorageModule.kt` **与 SS2 冲突**（SS2 解绑 `FunctionalWAL`）⇒ 按 `DISPATCH-ledger.md` §3.3 **SS1 先、SS2 后**，禁并行编辑。

---

## 5. 门禁清单

```powershell
# 工作目录 = C:\Mnzm\XianxiaSectNative\android
.\gradlew.bat compileReleaseKotlin --console=plain
.\gradlew.bat :core:domain:testReleaseUnitTest :core:data:testReleaseUnitTest :app:testReleaseUnitTest `
  --max-workers=1 --console=plain
.\gradlew.bat :core:domain:detekt :core:data:detekt :app:detekt --console=plain
# 登录生命周期四守卫（rules/sdk-init-lifecycle.md 修改检查清单）
.\gradlew.bat :app:testReleaseUnitTest --tests "*LoginFlowStateMachineTest*" --tests "*ComplianceManagerSelfHealTest*" `
  --tests "*SafeRunAfterSdkInitTest*" --tests "*SdkInitGuardTest*" --tests "*TapDBManagerInitGuardTest*" `
  --max-workers=1 --console=plain
# 仓库根
node scripts/check-agent-instructions.mjs
```

**真机冒烟**（照 `rules/sdk-init-lifecycle.md`）：登录 → 进游戏 → 退出 → 再登录 → 防沉迷 → 进游戏；**新增**：A 账号建进度 → 登出 → B 账号登录（**不得看到 A 的进度**）→ 登出 → A 账号再登录（**进度仍在**）。

---

## 6. 登记项

1. **跨批登记（SS4 承接）**：MMKV（`cloud_migration_slot{N}_state` / `cloud_upload_ledger_slot{N}_*`）本批仍是**全局**的，未随账号空间隔离——SS4 处理旧键合并时须一并评估"台账是否也要 accountKey 维度"。
2. **跨批登记（SS8 承接）**：本批只保证"无账号标识 ⇒ 不建库 + 引导登录"；**离线宽限（B1）与隐私政策双入口**归 SS8。
3. **风险**：分库后**旧设备的既有档在旧路径**——本批不迁移（迁移归 SS4），因此本批合入后到 SS4 合入前存在"老玩家看不到档"的窗口。⇒ **SS1 与 SS4 不得跨版本发布**，必须在同一发布内合入（此约束写入报告与 SS4 入口判据）。

---

## 7. 一句话给执行者

**把"一份设备级数据库"改成"一个账号一个数据空间目录"，登出补成五件套（清 `.current` 但不删库），并用实体清单遍历的守卫证明换账号不串档——旧档迁移不归本批，但必须与 SS4 同版本发布。**
