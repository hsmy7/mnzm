# 通知通道后端管线整链退役报告（NOTIFY-RETIRE）

> 批次真源：`docs/design/gacha-batches/TASKBOOK-NOTIFY-RETIRE.md`（随本批收官笔入库，D-5）。
> 实施方式：**独立 worktree**（`C:/Mnzm/XianxiaSectNative-notify-retire`，分支 `feat/notify-retire`），
> 基点 = G 线权威收官 `7e2c74832`（feat/gacha-m0-m1 别名重放终笔）。主树他线（refactor/remove-2x-speed
> 上 remove-law-enforcement 大规模实施）零接触——任务书前置条件①「分支归位+他线静止」以隔离树方式绕开，
> D-2「污染他线共享树」顾虑在 worktree 下不成立。
> 批次性质：纯删码（死基建退役），零行为改动、零 C++ 面、零存档序列化面。

---

## 一、任务书 §1 六条判据逐条交付

### ① 四符号 + GameNotification 全仓 kt 0 命中 ✅

删前（基点 `7e2c74832`，kt，排除 `core/util/` 系统通知同名异物与 build）：

```
$ git grep -E 'pendingNotification|notifications|enqueueNotification|consumeNotification|clearPendingNotification|GameNotification' 7e2c74832 -- 'android/*.kt' ':!android/**/core/util/*' | wc -l
84
```

删后（本树工作面，同口径）：

```
$ grep -rn --include='*.kt' -E 'pendingNotification|notifications|enqueueNotification|consumeNotification|clearPendingNotification|GameNotification' android/core android/app/src android/feature | grep -v 'core/util/' | grep -v 'GameNotificationHelper' | wc -l
0
```

### ② GameNotification 整文件删除、系统通知零触碰 ✅

- `android/core/domain/src/main/java/com/xianxia/sect/core/state/GameNotification.kt` 整文件删除（git status `D`）。
- `git status` 文件面对照：**`core/util/` 路径零条目**——`GameNotificationHelper.kt` /
  `GameNotificationHelperTest.kt` / `GameForegroundService.kt` 一个字符未动（同名异物红线 §2-6）。
- 删后 `find android -name 'GameNotification*'` 仅剩 `core/util/GameNotificationHelper.kt` 与其测试（系统通知存活）。
- 全仓**非 kt 口径**扫描补齐（验收轮）：发现两处存活引用面——构建配置 `android/stability_config.conf`
  与代码级 Wiki `CODE_WIKI.md`，随批摘除，证据与理由见 §三-6；摘除后 `GameNotification` 残留仅
  `core/util/` 系统通知同名异物与历史文档（「不回改历史」纪律）。

### ③ GameStateStoreTransientQueueGuardTest 处置：**改造保留 + 批前失牙修复** ✅

选择任务书 §4 的保留改造路径（非整删），理由与过程：

1. **侦察发现**：守卫的 `_pending*` 通用枚举覆盖 `GameStateStoreImpl` 全部瞬态 flow
   （`_pendingBeastAttacksFlow`/`_pendingBattleResultFlow`/`_pendingBattleRewardCardsFlow` 均存活），
   整删会误伤存活队列的守卫面——与 D-3「守卫对象即被删队列」的前提不符（D-3 依据的是 G10 时点信息）。
2. **判红实验发现批前预存缺陷**：按任务书要求构造反例（临时注释
   `clearTransientQueues()` 对 `_pendingBeastAttacksFlow` 的清空）→ **守卫未判红**（BUILD SUCCESSFUL）。
   根因：灌值走 `pendingMutableProps()`（`filterIsInstance<KMutableProperty1>`），而全部 `_pending*`
   flow 声明为 `val`——属性层无 setter，过滤结果为空集，灌值与检测双双空转。**该守卫在本批之前已失牙**
   （唯一有牙的 `notificationQueue` 点名反射恰为本批删除对象）。
3. **修复**：灌值改为经 getter 取 flow 引用后强转 `MutableStateFlow<Any?>` 直写 `.value`
   （类型擦除非空占位，属性可变性无关）；KDoc 登记修复缘由；`ConcurrentLinkedQueue` import 与
   `notificationQueue()` 点名反射随批删除。
4. **判别力双向证明**：同一反例复跑 → **判红**
   （`GameStateStoreTransientQueueGuardTest > reset clears every pending transient field FAILED`）；
   撤销反例 → **转绿**（BUILD SUCCESSFUL）。守卫对存活瞬态队列恢复真实判别力。

### ④ docs/ui-read-surface.md 登记行改写 ✅

§3.4 非镜像运行态通道清单去掉 `pendingNotification、notifications、`，并加退役注记指向本报告；
格式照该文件现行登记（顿号分隔 + 破折号说明）。

### ⑤ 全量门禁绿 + 逐模块账闭合 ✅

| 门 | 结果 |
|---|---|
| JUnit（逐模块，testReleaseUnitTest，`--max-workers=1 --rerun-tasks`） | **7476 / 0f / 0e / 18s，与 G13 基线逐位一致**：app 1011(2s)、core/domain 1571、core/engine 2938(1s)、core/ui 155、core/data 809(15s)、feature/game 992 |
| 删例清单 | **空**——六个测试文件的 5 成员 override 均为替身实现而非 `@Test` 用例；守卫测试 2 例保留（改造）；总数与基线零差即证 |
| detekt（六模块） | `:app` `:core:data` `:core:domain` `:core:engine` `:core:ui` `:feature:game` 全部执行，0 违规（0 新增 0 baseline，任务红即构建红） |
| lint | `Lint found 36 warnings (and 3 warnings filtered by baseline lint-baseline.xml)` = **36w / 0e**，与基线一致 |
| node 四门 | `gen-action-ids`：201 actions / maxId 1872（零漂移）；`gen-game-data --check`：sha256 `809375f4…` 一致；`check-jni-count`：86/86 在册；`check-agent-instructions`：五规则全 ✓（报告落盘后闭环，见 §二） |
| ctest | **不适用**——零 C++ 改动（复核见 §三-5） |
| Kotlin 组合门 | `compileReleaseKotlin testReleaseUnitTest detekt lintRelease --rerun-tasks --continue` → **BUILD SUCCESSFUL in 44m 13s**（346 actionable tasks 全执行） |

### ⑥ 双 changelog + 报告 + 单次提交 + 树净 ✅

- 游戏内 `changelog_entries.json`：4.01.16 条目 `changes` 末尾追加（99→100 条），文本级最小 diff（2+/1-）。
- 外部 `CHANGELOG.md`：4.01.16 段末新增「通知通道后端管线整链退役」小节。
- 本报告落盘；任务书随批入库（D-5）。单次提交 + 树净（见 §六）。

---

## 二、门禁实证（命令与输出原文）

```
工作目录 android/：
$ ./gradlew.bat compileReleaseKotlin testReleaseUnitTest --max-workers=1 --rerun-tasks --continue \
    "-Dgamecore.jni.path=<worktree>\android\core\engine\build\desktop-jni\libgamecorejni.so" \
    detekt lintRelease --console=plain
BUILD SUCCESSFUL in 44m 13s
346 actionable tasks: 346 executed
Lint found 36 warnings (and 3 warnings filtered by baseline lint-baseline.xml)

工作目录 仓库根：
$ node scripts/gen-action-ids.mjs
gen-action-ids: 201 actions (maxId=1872)
$ node scripts/gen-game-data.mjs --check
校验通过：game-data.json 与中性源一致（sha256 809375f4126ae90e688d5bbf10fb0d0180ff0ab91108e4d8c1d1ed6013938619）
$ node scripts/check-jni-count.mjs
✓ JNI 面计数在基线内：total=86/86，双桥无扩散。
$ node scripts/check-agent-instructions.mjs
✓ 规则① 预算闸 / ✓ 规则② 单一真源 / ✓ 规则③ 引用无死链 / ✓ 规则④ 路由表完整 / ✓ 规则⑤ 子目录启动链路
```

ctest：不适用。零 C++ 面（§三-5），无 JNI 重建需求（桌面 `libgamecorejni.so` 复用主树同源产物，
仅作测试注入路径，未重编）。

---

## 三、关键实施事实

### 1. 删除面 file:line 对表（实测 vs 任务书）

| 任务书 | 实测 | 处置 |
|---|---|---|
| GameStateStore.kt `:54/:115-121` 接口五成员 | 一致（`:54`、`:113-121` 含段注释与 `@Deprecated` 尾巴） | 全删 |
| GameEngine.kt `:27 import/:257-259` 三转发 | 一致 | 全删 |
| GameEngineDiscipleSlotOps.kt `:19` 扩展转发 | 一致 | 删 |
| DiscipleFacade.kt `:9/:62-63` | 一致 | 全删 |
| DiscipleFacadeImpl.kt `:23/:65/:402-403` | 一致 | 全删 |
| GameStateStoreImpl.kt `:232/:234/:235` flows+队列 | 一致（`:233` KDoc `/** 通知队列 */` 随删） | 全删 |
| 同上 `:355-356` overrides | 一致 | 全删 |
| 同上 `:682` 清除路径赋值 | 实为 `reusableMutableState`（MutableGameState 实例）构造的具名参数 `pendingNotification = null` | 删 |
| 同上 `:706-707/:712-715/:721-723` 三方法 | 一致（含 `=== 通知 API ===` 段注释、200 上限注释） | 全删 |
| 同上 `:794` 数据字段 | **归属修正：该字段在 `UpdateBaseline`**（事务起始快照 data class），非 ReusableMutableState——`reusableMutableState` 是 `MutableGameState` 实例（`:666` 构造），其字段删自 `MutableGameState` | 两处全删 |
| 同上 `:899/:905` 提交判据 | 一致（`notificationBeforeBlock` + `resolveCommitFlags(notificationChanged=…)`） | 判据删除，`resolveCommitFlags` 签名收敛为 `(baseline)` |
| 同上 `:1031/:1055` 归一/基线赋值 | 一致（captureBaseline / initReusableState） | 全删 |
| MutableGameState.kt `:43` | 一致 | 删 |
| UnifiedGameState.kt `:41` | 一致 | 删 |
| GameNotification.kt 整文件 | 一致（空 sealed 接口） | 整删 |

### 2. 任务书未记载引用面（§8-3 登记，均属同一删除对象，随批删净）

| 位置 | 内容 | 处置 |
|---|---|---|
| GameStateStoreImpl `:1114-1115` | `emitStateFlows` 的 `if (flags.notificationChanged) _pendingNotificationFlow.value = …`（事务恢复行） | 删 |
| GameStateStoreImpl `:1395-1397` | `clearTransientQueues()` 对两 flow 清空 + `ConcurrentLinkedQueue` drain | 删 |
| GameStateStoreImpl `CommitFlags.notificationChanged`（`:798-803` 定义、`:1073-1086` resolve、`:1139` detectFieldChanges） | 通知变更检测参与「是否有字段变化」判据 | 全链删除；`detectFieldChanges` 剩 15 路比较、`emitStateFlows` 剩 12 路（@Suppress 注释同步改）；`emitStateFlows` 的 `flags` 参数因唯一消费者被删而摘除 |
| HeavenlyTrialClaimRewardTest `:368` | `@Suppress("OVERRIDE_DEPRECATION")`（因 override `@Deprecated` 成员） | 随 override 同删 |
| 两处 CyclomaticComplexMethod 计数注释 | 13/16 路 → 12/15 路 | 同步改 |
| Impl `clearTransientQueues` KDoc | 「反射枚举 _pending* / notification 字段」表述 | 改为仅 `_pending*` |
| `android/stability_config.conf` `:31`（验收轮补） | Compose 稳定性配置列 `com.xianxia.sect.core.state.GameNotification`（已删类的稳定性声明） | 摘除该行（存活构建配置引用面） |
| `CODE_WIKI.md` `:374`（验收轮补） | 独立 StateFlow 清单行 `pendingNotification / StateFlow<GameNotification?> / GameOverlayHost` | 摘除该行；同文件 `:965` 稳定性配置计数 26 → 29（摘除后实数） |

### 3. 序列化注解核实（任务书 §8-2）

`MutableGameState` 与 `UnifiedGameState` 均为**纯 Kotlin data class**（前者内存事务缓冲 /
后者 `@Immutable` Compose 只读快照），**无任何 `@Serializable`/`@ProtoNumber` 注解**，不参与
ProtoBuf/JSON 存档序列化 ⇒ 删除属安全编译面变更，旧档零影响、零 Migration。`game-data.json`
无此字段（gen-game-data `--check` 通过即证）。

### 4. 零 C++ 面 / ctest 不适用复核

`gamecore`（`models.h`/`json_codec`/全部 `.h/.cpp`）`pendingNotification|enqueueNotification|GameNotification`
0 命中（仅 googletest 第三方无关同名）；`action_ids.h`/`ActionIds.kt` codegen 零漂移（201/1872）；
`scene_uv_tables.h`、图集清单、`sprite-uid-map.json` 门禁重生成后与提交版逐字节一致（仅行尾噪音，已还原）。

### 5. 同名异物红线执行

`core/util/GameNotificationHelper*`、`GameForegroundService`（Android 系统状态栏通知/前台服务保活）
零触碰：grep 定位全程排除 `core/util/`，`git status` 文件面核验零条目。

### 6. 验收轮补记：两处存活引用面（构建配置 + 代码级 Wiki）

验收轮按判据②「**全仓** 0 引用」口径（而非仅 kt）复扫，发现两处任务书与本报告前五节均未覆盖的
存活引用面：

```
$ git grep -n -I -E 'pendingNotification|enqueueNotification|consumeNotification|clearPendingNotification|GameNotification' -- . | 排除 /build/
android/stability_config.conf:31:  com.xianxia.sect.core.state.GameNotification
CODE_WIKI.md:374:  | `pendingNotification` | `StateFlow<GameNotification?>` | 通知触发时 | GameOverlayHost |
```

（同口径其余命中 = `core/util/` 系统通知同名异物 + CHANGELOG 历史条目 + 历史报告/审计文档，均按纪律保留。）

1. `android/stability_config.conf` `:31` —— Compose 稳定性配置把已删类登记为稳定性契约，属**构建配置
   存活引用**；同仓先例（`CHANGELOG.md`「同步清理 — stability_config.conf 删 4 条」）表明类型删除须同步
   摘除条目 ⇒ 摘除该行。
2. `CODE_WIKI.md` `:374` —— 独立 StateFlow 清单把已删 flow 及其消费者（G10 已删 `GameOverlayHost`
   通知节）登记为**现行**读取面，与 `docs/ui-read-surface.md` §3.4（本批已改写）同源 ⇒ 摘除该行。
   同文件 `:965` 计数原文「26 个类」，而摘除前实数为 30（**批前既有漂移**，非本批引入）⇒ 按摘除后
   实数改为 29；批前漂移在此登记。

摘除后全仓 `GameNotification` 残留 = `core/util/` 系统通知同名异物（一个字符未碰）+ 历史文档
（CHANGELOG 历史条目、`docs/design/gacha-batches/` 历史报告、审计文档——「不回改历史」纪律）。

---

## 四、诚实残余与过程记录

1. **「每步可编译」的偏差登记**：接口（`:core:domain`）↔ 实现/替身（`:app`/`:core:engine` test）跨模块，
   严格逐文件步进编译不可能两头绿（替身少实现抽象成员即编译失败）。隔离 worktree 下 D-2 的共享树
   污染顾虑不成立，改为**按域分批删除 + 域边界编译验证**（主源码三域删净即
   `compileReleaseKotlin` 绿，随后测试面+全量门）。
2. **批前预存缺陷（非本批引入）**：`GameStateStoreTransientQueueGuardTest` 的 `_pending*` 灌值机制
   因 `val` 属性过滤空集而空转——判红实验实证并已修复（§一-③）。该守卫在 G10 改造时
   （report-G10 §3.3「同一反射口径灌入」）即未对 `_pending*` 生效，建议后续批次留意
   「反射守卫需带自身判红验证」。
3. **ui-read-surface §3.4 顺带发现**：`pendingMarriageProposals` 登记条目已死（G03 整链删除，
   全树 kt 0 命中），属残留登记——本批改写同一行时一并摘除（登记处置，非扩删代码）。
   如需回滚此摘除，改动仅 ui-read-surface.md 一行。
4. **worktree 环境补齐记录**（一次性、不入库）：`keystore.properties`/`api.properties`/
   `android/local.properties`（gitignore 本地件，主树复制）；`android/scripts/node_modules`
   （sharp 依赖，图集 codegen 用）；`core/engine/build/desktop-jni/libgamecorejni.so`（测试注入路径，
   主树同源复制）。门禁重生成产物（action_ids.h 等）实测逐字节一致后已还原，不入提交。
5. **历史文档不回改**：`docs/save-system-audit-2026-09-21.md`、`docs/longrun-stability-*`、
   `docs/character-*`、gacha-batches 下 G 系列历史报告对已删符号的记述均为时点记录，按「不回改历史报告」
   纪律保留。

## 五、pending-device 清单

无。零行为改动、零 UI 面、零 C++ 面、零存档面——无真机验证项。

## 六、收官笔材料清单（单次提交）

| 类型 | 文件 |
|---|---|
| NR-a 接口与实现 | `GameStateStore.kt`、`GameStateStoreImpl.kt`（含 UpdateBaseline/CommitFlags/事务判据链） |
| NR-b 转发链 | `GameEngine.kt`、`GameEngineDiscipleSlotOps.kt`、`DiscipleFacade.kt`、`DiscipleFacadeImpl.kt` |
| NR-c 类型与字段 | `GameNotification.kt`（整删）、`MutableGameState.kt`、`UnifiedGameState.kt` |
| NR-d 测试面 | 6 替身文件 + `GameStateStoreTransientQueueGuardTest.kt`（改造保留） |
| NR-e 文档 | `docs/ui-read-surface.md`、`CHANGELOG.md`、`changelog_entries.json`、本报告、任务书（D-5 随批入库） |
| NR-f 存活引用面（验收轮补，§三-6） | `android/stability_config.conf`、`CODE_WIKI.md` |

## 七、验收轮建议复跑

```bash
# 仓库根：node 四门
node scripts/gen-action-ids.mjs && node scripts/gen-game-data.mjs --check
node scripts/check-jni-count.mjs && node scripts/check-agent-instructions.mjs
# 全仓 kt 零命中贴证（排除系统通知同名异物）
grep -rn --include='*.kt' -E 'pendingNotification|notifications|enqueueNotification|consumeNotification|clearPendingNotification|GameNotification' android/core android/app/src android/feature | grep -v 'core/util/' | wc -l   # 期望 0
# 全仓「非 kt」口径复扫（构建配置/文档/脚本；排除 /build/ 与历史文档）
git grep -n -I -E 'pendingNotification|enqueueNotification|consumeNotification|clearPendingNotification|GameNotification' -- . | grep -v '/build/'   # 期望仅 core/util 系统通知 + 历史文档
# Gradle 组合门（或单模块抽验 :app :core:engine）
cd android && ./gradlew.bat testReleaseUnitTest --max-workers=1 "-Dgamecore.jni.path=<worktree>\android\core\engine\build\desktop-jni\libgamecorejni.so" detekt lintRelease --console=plain
```

## 八、验收轮复跑（合并 `main` 终树）

本批在**合并 `main` 的终树**上由验收轮同轮实跑全量门禁（含 §三-6 补齐面），原文数字与逐模块账
记录于该次**合并提交说明**（本批工作区分支随合并删除，合并提交说明为长期载体）。口径与判据：

| 项 | 口径 |
|---|---|
| 符号面（kt） | 五符号 + `GameNotification` 全仓 kt 零命中；删前基点 `7e2c74832` 同口径 84 |
| 符号面（非 kt） | 构建配置/文档/脚本复扫，仅 `core/util/` 系统通知同名异物 + 历史文档（§三-6） |
| node 四门 | `gen-action-ids` / `gen-game-data --check` / `check-jni-count` / `check-agent-instructions` |
| Gradle 组合门 | `compileReleaseKotlin testReleaseUnitTest detekt lintRelease --max-workers=1 --rerun-tasks` |
| 守卫判别力 | `GameStateStoreTransientQueueGuardTest` 反例注入判红 → 还原判绿（双向） |
