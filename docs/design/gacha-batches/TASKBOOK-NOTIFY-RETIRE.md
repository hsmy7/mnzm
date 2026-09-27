# TASKBOOK-NOTIFY-RETIRE · 通知通道后端管线整链退役

> **本文件是通知管线退役批的派工真源**；来源 = G10 报告 §3.4/§8-1 登记 + 用户 2026-09-27 拍板「整链退役」。
> **时点**：2026-09-27，G 批四批（G10/G12/G13/G14）已全部 accepted 并总收官（feat/gacha-m0-m1 = `7e2c74832`）。
> **侦察方式**：看护会话只读 grep 逐 file:line 实测（2026-09-27 04:5x，主树 `refactor/remove-2x-speed` 工作树；管线文件均不在他线改动集内，读数有效）。
> **纪律**：引用一律写仓库根相对完整路径；代码片段已过项目红线；**同名异物红线见 §2-6，先读再做**。

---

## 0. 位置、依赖与风险等级

| 项 | 内容 |
|---|---|
| 批次性质 | 纯删码批（死基建退役）：删一条**零生产者、零消费者**的通道 |
| 前置条件（硬） | ① 主树已切回 `feat/gacha-m0-m1` 且他线分支已重置/静止（分支手术残留项，见 `docs/gacha-watch/dispatch-ledger.md` 总收官条）；② `git status` 除本任务书与 `docs/research/`×2 外树净；③ 他线四文档（realtime-settlement / remove-2x-speed / remove-law-enforcement / equipment-set）不在本批文件面 |
| 风险等级 | 🟡 中——跨 `:core:domain` 契约面 + `:app`/`:core:engine`/测试替身三层，但**零行为改动**（通道无生产者无消费者）+ **零 C++ 面 + 零存档序列化面**（已实证，见 §2-5） |
| 出口判据 | 四符号 + `GameNotification` 全仓 grep **0 命中**（§3.1 贴证）；全量 JUnit 绿 + detekt 六模块 0 + lint 36w/0e；ctest 不适用（零 C++）；`report-NOTIFY-RETIRE.md`；单次提交 + 树净 |
| 不做 | 不碰 `android/app/src/main/java/com/xianxia/sect/core/util/GameNotificationHelper.kt` / `GameForegroundService.kt`（Android 系统通知，§2-6）；不做任何新功能；不动版本号；不动 Room；不回改历史报告 |

---

## 1. 目标与验收判据

| # | 判据 | 证据形式 |
|---|---|---|
| ① | 四符号 `pendingNotification` / `notifications` / `enqueueNotification` / `consumeNotification` / `clearPendingNotification` 全仓（kt，排除 `.worktrees` 与 build）**0 命中** | 删前删后 grep 贴证（双向） |
| ② | `GameNotification`（游戏内空 sealed 接口）**整文件删除**且全仓 0 引用；`core/util/GameNotificationHelper*` 与 `GameForegroundService` **零触碰**（系统通知存活） | grep 贴证 + `git status` 文件面对照 |
| ③ | `GameStateStoreTransientQueueGuardTest` 处置完毕（整删或改造成仍有不变量守卫，二选一并说明） | 测试文件 diff + 报告说明 |
| ④ | `docs/ui-read-surface.md` 读面登记行（`:111-112` 含 pendingNotification/notifications 的清单行）同步改写并按该文件 §2 纪律登记 | diff |
| ⑤ | 全量 JUnit 绿（逐模块账闭合说明：删例清单）+ detekt 六模块 0/0 + lint 36w/0e + node 四门 | 命令原文数字 |
| ⑥ | 双 changelog + `report-NOTIFY-RETIRE.md`（参照 report-G14-completion.md 结构）+ 单次提交 + 树净 | 提交与文件 |

---

## 2. 🔴 实测管线全貌（含 G10 §3.4 转述之外的 4 项新增发现）

### 2.1 接口与实现（:core:domain → :app）

| 文件 | 行 | 内容 |
|---|---|---|
| `android/core/domain/src/main/java/com/xianxia/sect/core/state/GameStateStore.kt` | `:54` | `val pendingNotification: StateFlow<GameNotification?>` |
| 同上 | `:115` | `val notifications: StateFlow<List<GameNotification>>` |
| 同上 | `:116` | `fun enqueueNotification(notification: GameNotification)` |
| 同上 | `:117` | `fun consumeNotification(): GameNotification?` |
| 同上 | `:119-121` | `@Deprecated` `fun clearPendingNotification()`（注释自陈「通知系统已改为队列」） |
| `android/app/src/main/java/com/xianxia/sect/core/state/GameStateStoreImpl.kt` | `:232` / `:234` | `_pendingNotificationFlow` / `_notificationsFlow`（MutableStateFlow） |
| 同上 | `:235` | `notificationQueue`（ConcurrentLinkedQueue，`:714` 上限 200 丢弃最旧） |
| 同上 | `:355-356` | 两个 override flows |
| 同上 | `:682` | 某清除路径 `pendingNotification = null` |
| 同上 | `:706-707` / `:712-715` / `:721-723` | clear / enqueue / consume 实现 |

### 2.2 转发链（:core:engine）

| 文件 | 行 | 内容 |
|---|---|---|
| `android/core/engine/src/main/java/com/xianxia/sect/core/engine/GameEngine.kt` | `:257-259` | `pendingNotification` / `notifications` / `consumeNotification()` 三转发（另 `:27` import GameNotification） |
| `android/core/engine/src/main/java/com/xianxia/sect/core/engine/GameEngineDiscipleSlotOps.kt` | `:19` | 扩展转发 `fun GameEngine.clearPendingNotification()`（**G10 §3.4 未记载**） |
| `android/core/engine/src/main/java/com/xianxia/sect/core/engine/domain/disciple/DiscipleFacade.kt` | `:9` / `:62-63` | import + `clearPendingNotification()` + `pendingNotification` |
| `android/core/engine/src/main/java/com/xianxia/sect/core/engine/domain/disciple/DiscipleFacadeImpl.kt` | `:23` / `:65` / `:402-403` | import + override 两个 |

### 2.3 🔴 数据字段面（**G10 §3.4 未记载，本批实测新增——不删则编译不过**）

| 文件 | 行 | 内容 |
|---|---|---|
| `android/app/src/main/java/com/xianxia/sect/core/state/GameStateStoreImpl.kt` | `:794` | `ReusableMutableState` 构造参数 `val pendingNotification: GameNotification?` |
| 同上 | `:899` / `:905` | 事务提交判据 `notificationBeforeBlock` / `notificationChanged`（比较 `!==`） |
| 同上 | `:1031` / `:1055` | 归一 / 基线路径的 `pendingNotification` 赋值 |
| `android/core/domain/src/main/java/com/xianxia/sect/core/state/MutableGameState.kt` | `:43` | `var pendingNotification: GameNotification? = null`（⚠️ 实施者必须先核该类序列化注解——字段类型为空 sealed 接口，运行时恒 null；若带 `@Serializable`，删除属安全字段删除，旧档多余键读取忽略） |
| `android/core/domain/src/main/java/com/xianxia/sect/core/state/UnifiedGameState.kt` | `:41` | `val pendingNotification: GameNotification? = null` |

### 2.4 类型本体

`android/core/domain/src/main/java/com/xianxia/sect/core/state/GameNotification.kt` —— G10 删 `RecruitFailed` 后已是**空 sealed 接口** ⇒ 本批整文件删除。

### 2.5 测试面（实测 6 文件；G10 §3.4 称「8 个替身」——按文件数 6、按接口实现处数实施者开工后逐文件枚举并以实数为准）

| 文件 | 性质 |
|---|---|
| `android/core/engine/src/test/java/com/xianxia/sect/core/nativebridge/FakeGameStateStore.kt` | 真替身：5 成员 override（`:89/:135-138` 一带） |
| `android/core/engine/src/test/java/com/xianxia/sect/core/engine/FakeAtomicStateStore.kt` | 真替身：5 成员 override |
| `android/core/engine/src/test/java/com/xianxia/sect/core/engine/BootSequenceControllerTest.kt` | 测试内嵌实现（`:590/:632-635` 等，**同文件可能多处实现**，逐处删） |
| `android/core/engine/src/test/java/com/xianxia/sect/core/engine/GameEngineCoordinationTest.kt` | 测试内嵌实现（`:679` 一带） |
| `android/core/engine/src/test/java/com/xianxia/sect/core/engine/GameEngineWatchItemTest.kt` | 测试内嵌实现（`:238` 一带） |
| `android/core/engine/src/test/java/com/xianxia/sect/core/engine/domain/battle/HeavenlyTrialClaimRewardTest.kt` | 测试内嵌实现（`:369` 一带） |
| `android/app/src/test/java/com/xianxia/sect/core/state/GameStateStoreTransientQueueGuardTest.kt` | G10 改造成的**反射口径守卫**（7 处命中）⇒ 见 §1-③ 处置判据 |

### 2.6 🔴 同名异物红线（先读再做）

`android/app/src/main/java/com/xianxia/sect/core/util/GameNotificationHelper.kt`、`GameForegroundService.kt`（及其测试 `GameNotificationHelperTest.kt`）是 **Android 系统状态栏通知**（前台服务保活），与本管线**零关系**——**一个字符都不许碰**。grep 定位时必须排除 `core/util/` 路径的误伤。

### 2.7 零镜像实证（本侦察已做，实施者复核贴证）

C++ `gamecore`（`models.h` / `json_codec` / 全部 `.h/.cpp`）notification 零命中（仅 googletest 第三方库无关同名）；存档序列化面零涉及；`game-data.json` 无此字段 ⇒ **零 C++ 改动、零迁移、ctest/JNI 重建不适用**。

---

## 3. 任务 A：退役顺序（自外向内，每步可编译）

1. 删测试面 6 文件中的 5 成员 override（每文件编译验证）；
2. 处置 `GameStateStoreTransientQueueGuardTest`（§1-③）；
3. 删转发链：`GameEngineDiscipleSlotOps:19` → `DiscipleFacadeImpl` → `DiscipleFacade` → `GameEngine:257-259`（含 import）；
4. 删 `GameStateStore.kt` 接口五成员（`:54/:115-121`）；
5. 删 `GameStateStoreImpl`：`:355-356` overrides → `:706-723` 三方法 → `:232/:234/:235` flows 与队列 → **`:794` ReusableMutableState 字段 + `:899/:905` 判据 + `:1031/:1055` 赋值**（判据删除后该事务的「notificationChanged」参与条件同步简化，保持其余判据语义不变）；
6. 删 `MutableGameState:43` / `UnifiedGameState:41` 字段（先核 §2.3 序列化注解）；
7. 删 `GameNotification.kt` 整文件；
8. `docs/ui-read-surface.md` `:111-112` 清单行改写（去掉 pendingNotification/notifications，格式照该文件现行登记）；
9. 全仓 grep 贴证（§1-①②）。

## 4. 守卫与测试面同步

- `GameStateStoreTransientQueueGuardTest`：其不变量「队列必须登记 clearTransientQueues」的对象即本批删除的队列 ⇒ **推荐整文件删除**并在报告写明理由；若选择保留改造（改为守卫 `clearTransientQueues` 对其他队列的覆盖），须证明守卫仍有判别力（构造反例判红）。
- 删除测试文件/用例后，JUnit 总数**纯删例**，逐模块账闭合说明写入报告（对照 G13 基线 **7476/0/0/18**）。
- 镜像/架构守卫（`MirrorReadOnlyGuardTest` 等）如引用被删成员，同批适配。

---

## 5. 决策

| # | 决策 | 依据 |
|---|---|---|
| D-1 | `GameNotification.kt` **整文件删除**（非保留空接口） | 空接口零实现零引用，保留即死码，违背单源化纪律 |
| D-2 | 自外向内删、每步可编译 | 中途不可编译状态会污染他线共享树 |
| D-3 | `GameStateStoreTransientQueueGuardTest` 推荐整删 | 守卫对象即被删队列；改造不增不变量 |
| D-4 | 系统通知（`core/util/`）零触碰 | 同名异物（§2.6） |
| D-5 | 暂不入库任务书本文件 | 主树现处他线分支（见前置条件），本任务书随退役批收官笔入库 |

---

## 6. 文件面与切片（≤10 文件/片；全路径）

| 片 | 允许改 |
|---|---|
| **NR-a** 接口与实现 | `GameStateStore.kt`、`GameStateStoreImpl.kt`（含 §2.3 字段面） |
| **NR-b** 转发链 | `GameEngine.kt`、`GameEngineDiscipleSlotOps.kt`、`DiscipleFacade.kt`、`DiscipleFacadeImpl.kt` |
| **NR-c** 类型与数据字段 | `GameNotification.kt`（整删）、`MutableGameState.kt`、`UnifiedGameState.kt` |
| **NR-d** 测试面 | §2.5 六文件 + `GameStateStoreTransientQueueGuardTest.kt` |
| **NR-e** 文档 | `docs/ui-read-surface.md`、双 changelog、`report-NOTIFY-RETIRE.md`（新建） |

## 7. 门禁清单（终树同轮；判据 = 命令输出原文）

```powershell
# 1) Kotlin 组合门（工作目录 android）
.\gradlew.bat compileReleaseKotlin testReleaseUnitTest --max-workers=1 --rerun-tasks --continue `
  "-Dgamecore.jni.path=<repo>\android\core\engine\build\desktop-jni\libgamecorejni.so" detekt lintRelease --console=plain
# 2) node 四门（工作目录 = 仓库根）
node scripts/gen-action-ids.mjs ; node scripts/gen-game-data.mjs --check
node scripts/check-jni-count.mjs ; node scripts/check-agent-instructions.mjs
# 3) 桌面 ctest：不适用（零 C++ 改动），报告写明即可
```

## 8. 登记 / 待拍板

1. 🟡 本任务书暂不入库（主树在他线分支），随退役批收官笔入库或主树回 feat 后补提交。
2. 🟡 `MutableGameState.pendingNotification` 的序列化注解核实结果写进报告（§2.3 ⚠️）。
3. 🟠 若实跑中发现本任务书未记载的引用面（对照 G10/G13 先例属正常），按「登记不擅自扩删 → 报告给理由」处理。

## 9. 一句话给执行者

**自外向内删一条零生产者零消费者的死通道：6 个测试文件的 5 成员 override → 4 个转发文件 → 接口五成员 → Impl 的 flows/队列/方法/数据字段与提交判据 → 两个数据字段 → 空接口 `GameNotification.kt` 整删；系统通知 `core/util/` 一个字符不许碰；每步可编译、grep 双向贴证、全量门绿、报告逐 file:line 对表。**
