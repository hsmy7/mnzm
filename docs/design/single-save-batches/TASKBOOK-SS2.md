# TASKBOOK-SS2 · 账号数据空间分库 + 登出五件套

> **本文件是 SS2 的派工真源**。上位方案 §2.2 / §2.8。
> 协议：[`DISPATCH-ledger.md`](DISPATCH-ledger.md) §4.3 + [`../gacha-batches/EXECUTION-PROTOCOL.md`](../gacha-batches/EXECUTION-PROTOCOL.md)。
> 入口判据：**SS0 已合入**；工作树干净。
> ⚠️ 删档重置使**旧档全部失效** ⇒ 本批**不需要任何存量迁移**，v1 时代的"分库后老玩家看不到档"窗口问题**不存在**。
> 证据等级：行号为影响面普查 + 主线程直读；`Room.databaseBuilder` 的具体调用形态实施时以 `grep` 定位。

---

## 1. 目标与验收判据

| 项 | 内容 |
|---|---|
| 业务目标 | 本地数据从"按设备归属"改为"**按账号隔离**"：一个账号一个数据空间（分库 + 分目录），换账号即切空间，且**不删库**（换回来进度还在）。同时把登出补成"五件套"。 |
| 验收① | 数据空间按 `accountKey` 落盘：`filesDir/accounts/<accountKey>/{xianxia_sect.db(+ -wal/-shm), saves/, archives/, legacy_import/}`；`accounts/.current` 标记当前活跃空间 |
| 验收② | **换账号不串档**：以 `@Database` 实体清单为锚点遍历的**隔离守卫测试**绿（断言不存在跨 `accountKey` 可见路径；`intentionallyExcluded` 显式声明例外并注明理由） |
| 验收③ | **登出五件套**（原四件套 + 关闭当前数据空间）在**三处登出入口逐字一致**：`GameActivity.onLogout` / `MainActivity.performComplianceLogout` / `ComplianceVerificationScreen.onLogout`。⚠️ 规范里第四处 `ModeSelectionScreen.onLogout` **已随主界面退役删除**，本批同批修正 `rules/sdk-init-lifecycle.md:50` |
| 验收④ | 登出**只清 `.current`、不删数据空间**；再次登录同一账号进度仍在 |
| 验收⑤ | 无账号标识时不建库（引导登录）——分库的必然前提；**离线宽限与隐私政策不属本批**（归 SS8） |
| 验收⑥ | 账号隔离键派生逻辑**下沉 `:core/domain` 纯函数**（双端共用），不在 Android 侧内联 |
| **不做** | 不改槽位（SS1 已删净）；不改云端链路（SS7）；不落"全部功能要求登录"的完整门槛（SS8）；**不碰 C++** |

---

## 2. 实测现状

- 数据库文件固定名（`getUnifiedDatabaseFile(context)`，`GameDatabase.kt:579` 附近）⇒ 全设备一份。
- `GameDatabase.kt`：`@Database(` 在 `:136`、`version` 绑定 `:241`、`DATABASE_VERSION` 在 `:95`；**SS0 之后迁移链已清空**，`backupDatabaseForMigration` 已改语义为启动前快照。
- **登出链只清会话，不清档、不隔离**（摸底报告 §12 结论）；本批把"数据空间切换"补进去。
- 登出四件套（`rules/sdk-init-lifecycle.md`）：`clearSession` + `TapTapAuthManager.logout` + `TapDBManager.stopGameDurationTracking` + `ComplianceManager.unregisterCallback`。**新登出入口必须复制完整四件套，禁止只做 `clearSession()`。**

### 2.1 分库的连带面（必须同批处理）

| 面 | 说明 |
|---|---|
| 启动前快照 / 启动恢复 | 路径须随库名走（快照文件与恢复 marker 都在同目录） |
| `saves/` 文件层 | `SaveFileManager` 的固定文件名（SS1 已去槽）落在账号空间内 |
| `archives/*.arc` | 落在账号空间内 |
| MMKV / SharedPreferences | **本批不动**；SS0 已清理旧台账键。若台账需随账号隔离，登记给 SS7 评估 |

---

## 3. 决策

| # | 决策 | 依据 / 代价 |
|---|---|---|
| **D-1** | **`accountKey` = 账号标识经 SHA-256 截断**后的文件安全串；**不落明文标识到文件名** | 文件名会出现在备份/日志/文件管理器里，明文标识属不必要暴露 |
| **D-2** | **目录结构** `filesDir/accounts/<accountKey>/…` + `accounts/.current` | 与 `cacheDir` 分离（清缓存不影响档）；账号维度可见可审计 |
| **D-3** | **派生逻辑下沉 `:core/domain` 纯函数**（哈希/命名/合法性校验） | `rules/code-quality.md` §1.5 跨平台对等：iOS 侧复用同一套命名规则 |
| **D-4** | **登出 = 清 `.current`，不删空间** | 单存档 + 账号隔离下删库等于删进度；换回来应还在 |
| **D-5** | **无账号标识 ⇒ 不建库 + 引导登录** | 没有账号就没有隔离键；建"匿名空间"会在 SS8 落地后变成孤儿数据 |
| **D-6** | **不需要任何存量迁移** | 删档（SS0）已使旧档全部失效；v1 的"分库后老玩家看不到档"窗口问题不存在 |

---

## 4. 文件面与切片

| 片 | 允许改 | 禁止改 | 自检项 |
|---|---|---|---|
| **SS2-a** 账号键纯函数 | `core/domain/.../account/AccountKey.kt`（**新**）＋ 单测 | Android API、`Context` | 纯函数、无 Android 依赖；非法输入有定义行为 |
| **SS2-b** 数据空间路径 | `GameDatabase.kt`（库名 + 快照/恢复路径随库名）、`StorageModule.kt`（路径注入） | 建库/schema 基线 | 库名/快照名/恢复 marker 全在同一账号目录；默认固定名路径不再被使用 |
| **SS2-c** 登出五件套 | `MainActivity.kt`（统一登出收敛）、`GameActivity.kt`（`onLogout`）、合规验证界面登出回调 | 登录状态机语义 | 三处逐字一致；不新增"只置位不复位"的一次性布尔标记（`rules/sdk-init-lifecycle.md` 原则 5） |
| **SS2-d** 隔离守卫 | 新增 `AccountDataIsolationGuardTest`（以 `@Database` 实体清单为锚点） | 生产源 | 断言消息带操作指引；`intentionallyExcluded` 逐项注明理由 |
| **SS2-e** 规范修正 | `rules/sdk-init-lifecycle.md:50`（删已退役行 + 登出清单升五件套） | 其它规范 | 改后跑 `node scripts/check-agent-instructions.mjs` |

**共享面**：`StorageModule.kt` **与 SS3 冲突**（SS3 解绑 `FunctionalWAL`）⇒ 按 ledger §4.3 **SS2 先、SS3 后**，禁并行编辑。

---

## 5. 门禁清单

```powershell
# 工作目录 = C:\Mnzm\XianxiaSectNative\android
.\gradlew.bat compileReleaseKotlin --console=plain
.\gradlew.bat :core:domain:testReleaseUnitTest :core:data:testReleaseUnitTest :app:testReleaseUnitTest --max-workers=1 --console=plain
.\gradlew.bat :core:domain:detekt :core:data:detekt :app:detekt --console=plain
# 登录生命周期守卫
.\gradlew.bat :app:testReleaseUnitTest --tests "*LoginFlowStateMachineTest*" --tests "*ComplianceManagerSelfHealTest*" `
  --tests "*SafeRunAfterSdkInitTest*" --tests "*SdkInitGuardTest*" --tests "*TapDBManagerInitGuardTest*" --max-workers=1 --console=plain
node scripts/check-agent-instructions.mjs
```

**真机冒烟**：A 账号建进度 → 登出 → B 账号登录（**不得看到 A 的进度**）→ 登出 → A 账号再登录（**进度仍在**）。

---

## 6. 登记项

1. **跨批登记（SS7）**：MMKV 本批仍为**全局**；云上传账本是否也要 `accountKey` 维度，由 SS7 在收口台账键时一并评估。
2. **跨批登记（SS8）**：本批只保证"无账号标识 ⇒ 不建库 + 引导登录"；**离线宽限（B1）与隐私政策双入口**归 SS8。
3. **注意**：`rules/sdk-init-lifecycle.md` 的登出清单目前是**四件套**，本批改为**五件套**后，该规范文件与代码必须同时更新（规范是唯一真源）。

---

## 7. 一句话给执行者

**把"一份设备级数据库"改成"一个账号一个数据空间目录"，登出补成五件套（清 `.current` 但不删库），并用实体清单遍历的守卫证明换账号不串档——删档之后已无存量迁移问题，本批不需要任何兼容逻辑。**
