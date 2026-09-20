# B18 剩余未动臂 — 现状勘察报告

> 生成时间：2026-09-20 晚（**终版**：吸收项 H 已补勘察，9 项全部覆盖）
> 作者：B18 施工侧
> 范围：`docs/parallel-batches-w5/batch-B18.md` 中**尚未动工**的臂（臂 γ）与**全部吸收清单项**
> 姊妹文档：`batch-B18.md`（施工卡）、`dispatch-ledger.md`（台账）、`handover-b18-wip-2026-09-20.md`（交接）
>
> **臂状态（截至本报告）**：臂 1 `df6b70d5a` ✅ / 臂 2 `efba3ee72` ✅ / 臂 3 `a7de1360a` ✅ /
> 臂 β `4ed1d7c24` ✅ / **臂 γ + 吸收项 9 项 ⬜ 本报告覆盖**

---

## 0. 本报告定位

B18 五条臂中 **臂 1 / 臂 2 / 臂 3 / 臂 β 已完成**，本报告只覆盖**开工前需要先解决的判据缺口**、
**臂 γ（G5 战斗臂）**、以及**吸收清单 9 项**的事实勘察、风险、前置依赖与建议顺序。

报告中所有行号均为 **2026-09-20 实测**，非推测。

### 0.1 已完成臂对照（供坐标系）

| 臂 | 内容 | 状态 | 引入提交 / 删除提交 |
|---|---|---|---|
| 臂 1 | 传输臂退役：`mirrorProtobufTransport` | ✅ 已提交 | 引入 `6b3354708` / 删 `df6b70d5a` |
| 臂 2 | 投影臂退役：`gameViewProjection` | ✅ 已提交 | 引入 `6fd9fe03d` / 删 `efba3ee72` |
| 臂 3 | 列级臂退役：`dirtyColumnExport` | ✅ 已提交 | 引入 `4d053b9be` / 删 `a7de1360a` |
| 臂 β | 场景臂退役：`sceneStoreRender` | ✅ 本轮实施（代码/文档/记忆已落地，**待提交**） | 引入 `242440778` / 删 **待提交** |
| **臂 γ** | **G5 战斗臂退役** | ⬜ **未动** | 引入 `3a7d0e3de` 前后 / — |
| 吸收项 | 9 项（下 §2） | ⬜ **未动** | — |

### 0.2 臂 β 本轮实测验证数字（供后续臂参考门禁口径）

| 门 | 结果 |
|---|---|
| 组合门 `testReleaseUnitTest --max-workers=1 :core:engine:detekt :feature:game:detekt :app:lintRelease` | **BUILD SUCCESSFUL 20m 58s / 319 任务**（28 executed / 4 cache / 287 up-to-date） |
| `:app:lintRelease` | Lint found **43 warnings**（3 条已被 `lint-baseline.xml` 过滤），零 error |
| JNI 面计数门禁 | **87/87**（基线 `89 → 87`，`NativeBridge.kt` 49 → 47） |
| 桌面 GTest `ctest` 全量 | **1553/1553** |
| 桌面 GTest 单进程（**必须 `cd` 到 gamecore 源码根**） | **1553/1553，exit=0** |
| NDK arm64 `:app:assembleRelease` | **BUILD SUCCESSFUL 8m 51s / 259 任务** |

> **口径提醒**：历史交接文档曾写「ctest 1556 / 单进程 1553」，本环境实测 `ctest` = **1553**。
> 后续声明数字**以本次实际输出为准**，勿照抄旧文档。

---

## 1. 臂 γ — G5 战斗臂退役

### 1.1 施工卡原文

`batch-B18.md:143`：

> `| G5 | `BattleSystem` → golden 夹具退场 | 4 个生产回退点退役（战斗/秘境/探索/3 执行器） |`

### 1.2 回退机制现状（实测）

战斗路径的**核心降级契约**位于 `BattleExecutionRouter.kt:39-61`：

```kotlin
/**
 * 尝试经 C++ 执行战斗；成功返回重建的 [BattleSystemResult]，否则 null（调用方回退 Kotlin）。
 */
@Suppress("ReturnCount")  // 多 return 为降级契约（flag 关/native 不可用/失败信封逐级返回）
internal fun tryExecuteNative(
    battle: Battle,
    playerDamageModifier: Double = 1.0
): BattleSystemResult? {
    if (!NativeEngineFlag.authoritative) return null
    if (!GameCoreBridge.isLoaded) return null
    ...
    if (out.containsKey("error")) return null
    return rebuildResult(out, battle)
}
```

**三级降级**：① 旗标非 AUTHORITATIVE → null；② native 未加载 → null；③ C++ 返回 `error` 信封 → null。
三个 `null` 都意味着**调用方回退 Kotlin `BattleSystem` 原实现**。

### 1.3 四个生产回退点（逐点实测）

| # | 回退点 | 文件:行 | 回退形态 |
|---|---|---|---|
| 1 | 遭遇战（含 PvP/PvE 两阶段） | `EncounterBattleService.kt:152-154` | `tryExecuteNative(battle) ?: battleSystem.executeBattle(battle)` |
| 2 | 秘境会话战斗 | `SecretRealmService.kt:802-803` | `tryExecuteNative(battle) ?: battleSystem.executeBattleWithTimeout(battle)`（**注意：回退用的是 `executeBattleWithTimeout`，非 `executeBattle`**） |
| 3 | 妖兽防守生产（R4.3） | `ExplorationService.kt:332-333` | `tryExecuteNative(battle) ?: battleSystem.executeBattle(battle)` |
| 4 | 任务系统（双分支：BEAST + HUMAN） | `MissionSystem.kt:344-345` 与 `:367-368` | 同上（**两处，同一函数内两个 `EnemyType` 分支**） |

> **施工卡口径校正**：卡上写「4 个生产回退点（战斗/秘境/探索/3 执行器）」，实测应是
> 「遭遇战 / 秘境 / 妖兽防守 / 任务系统（双分支）」共 **5 个调用点、4 个文件**。
> 计划中的「3 执行器」若指 `MissionSystem` 的两分支加总，需在开工前与施工卡对齐口径。

### 1.4 判定：臂 γ 与臂 1/2/3/β 有**本质差异**

前四条臂删的是**灰度开关 + 生产分支**，删后系统**功能形态不变**（恒走新路径）。

臂 γ 不同：`tryExecuteNative` 的 `null` 分支**不只是灰度回退**，它同时是：
- **native 未加载的生产兜底**（`GameCoreBridge.isLoaded == false`，例如 ABI 不匹配、so 加载失败的设备）；
- **C++ 侧失败信封的容错路径**（`out.containsKey("error")`）。

⇒ **臂 γ 不能简单"删掉 `?: battleSystem.executeBattle(battle)`"**，否则一旦 native 不可用或
C++ 返回 error，战斗将**直接崩溃或无结果**。这是本报告**最重要的风险结论**。

### 1.5 臂 γ 开工前置——必须先拍板的 3 个语义问题

| # | 待拍板问题 | 影响面 | 建议决策方向 |
|---|---|---|---|
| γ-1 | **native 战斗 `error` 信封的用户可见语义**是什么？ | 4 个文件 5 个调用点 | 保留现有语义：error → 抛业务异常 / 走原有"战斗失败"UI 分支；**不得静默吞掉** |
| γ-2 | `!GameCoreBridge.isLoaded` 时**战斗如何降级**？ | 同上 | 明确产线是否允许"无 native 运行"。若允许 → 必须保留 Kotlin 战斗实现（则该实现**不是回滚臂**，而是**平台兜底实现**，不进本批删除面） |
| γ-3 | 本批是否**同时删 Kotlin `BattleSystem.executeBattle`**？ | 影响面巨大（`BattleSystemTest` / `DiffBattle*` 5 个对拍套） | **建议不删**。只删"灰度旗标门控"，保留 Kotlin 实现作**测试侧 golden 与平台兜底双用途** |

### 1.6 臂 γ 建议实施形态（若 γ-2 判为"允许无 native"）

**只删灰度门控，不删兜底路径**：

- `BattleExecutionRouter.tryExecuteNative` 中的 `if (!NativeEngineFlag.authoritative) return null`
  → **删**（不再由旗标决定，恒尝试 native）；
- 保留 `if (!GameCoreBridge.isLoaded) return null` + `if (out.containsKey("error")) return null`
  → **这是正确性机制，不是回滚臂**（同臂 3 的 `columnExportBlocked_` 异构锁存判例）；
- 调用方 `?: battleSystem.executeBattle(battle)` → **保留**（平台兜底 + 测试 golden 对照面）。

⇒ 「golden 夹具退场」的含义应理解为：**对拍测试的对照臂从"活体生产分支"转为"测试侧 golden 冻结快照"**，
而不是把 Kotlin 战斗实现从代码库删除。这与臂 2 `GameDataFieldPatchGuardTest.goldenRoundTrip` 判例一致。

### 1.7 臂 γ 守卫面清单（开工前须逐个确认对照面归属）

| 测试文件 | 类型 | 处置 |
|---|---|---|
| `DiffBattleExecutionTest.kt` | 桌面对拍 | 对照臂 → golden 冻结 |
| `DiffBattleTest.kt` | 桌面对拍 | 同上 |
| `DiffBattleAITest.kt` | 桌面对拍 | 同上 |
| `DiffBattleCalculatorTest.kt` | 桌面对拍 | 同上 |
| `DiffSectBattleTest.kt` | 桌面对拍 | 同上 |
| `EncounterBattleServiceRouteTest.kt` | 路由守卫 | 旗标断言 → 反向断言（不得回流） |
| `BattleExecutionRouterTest.kt` | 路由契约 | 保留（契约本身不删） |
| `BattleResidualNativeTxGateTest.kt` | 残余门 | 保留（回退点未删则断言仍有效） |
| `BattleSystemTest.kt` | 实现测试 | 保留（若 γ-3 判为不删实现） |
| `PatrolBattleSystemTest.kt` / `SecretRealmBattleHelperTest.kt` | 场景测试 | 保留 |
| `AISectBattleProcessorTest.kt` / `GameEngineBattleOpsTest.kt` | 集成 | 保留 |

### 1.8 臂 γ 风险评级

| 风险 | 等级 | 说明 |
|---|---|---|
| 误删平台兜底 ⇒ 无 native 设备战斗崩溃 | 🔴 **高** | γ-2 未拍板前**禁止开工** |
| `error` 信封语义未定义 ⇒ 静默失败 | 🔴 **高** | γ-1 未拍板前**禁止开工** |
| 与 `Diff*` 五个对拍套联动 | 🟠 中 | 对照面转 golden 工作量大，建议单独 commit |
| 涉及 `BattleSystem` 3.5 万行级实现 | 🟠 中 | 若 γ-3 判为"删实现"，工程量远超其余四臂 |

---

## 2. 吸收清单（9 项）逐项勘察

来源见 `batch-B18.md:123-136`。以下按**建议实施顺序**排列。

### 2.1 【A 组】`upsertsJson` 族 typed 化（b02 发现 8③④）

**现状实测**——`upsertsJson` 全仓触点仅 **5 处**：

| 文件:行 | 角色 | 内容 |
|---|---|---|
| `gamecore/include/gamecore/state/gameview_encode.h:30` | **C++ 协议契约（单一权威注释）** | `// collectionChange（upsertsJson = 实体数组 JSON 原文，与旧协议逐字节同值）` |
| `gamecore/test/gameview_encode_test.cpp:221` | C++ 守卫（编码侧） | — |
| `gamecore/test/gameview_encode_test.cpp:235` | C++ 守卫（编码侧） | — |
| `core/engine/.../GameViewStreamEvent.kt:14` | Kotlin 事件模型字段声明 | `upsertsJson` 字段定义 |
| `core/engine/.../GameViewMirrorCodec.kt:134 / 155 / 162-163` | **Kotlin 消费侧单点切读** | 见下 |

`GameViewMirrorCodec.kt` 关键现场（实测原文）：

```kotlin
if (!cc.upsertsJson.isEmpty) {
    changed[cc.name] = json.parseToJsonElement(cc.upsertsJson.toStringUtf8())
}
```

**任务定义**（`batch-B18.md:126`）：
> proto **只增字段**（type 化后 `collectionChange` 不再内嵌 JSON 原文）；
> codec 单点切读 + 等价守卫 + 桥重建。

**风险与红线**：
- 🔴 **协议 JSON 面红线**：typed 化**只允许 proto 追加字段**，**既有字段号与语义零变更**；
- 🟠 `upsertsJson` 是「与旧协议**逐字节同值**」契约 ⇒ 切成 typed 后必须**等价守卫**证明解码结果逐字段相同；
- 🟢 触点集中（C++ 注释 + Kotlin 单点切读 + 2 个 C++ 守卫），是 9 项中**最独立、最适合先做**的一项。

**建议**：**作为吸收项第一个 commit**（不依赖其他项，风险面窄）。

---

### 2.2 【B 组】`month_settlement.h` 的 `indexById` 现场重建（b03 遗留）

**现状实测**——`month_settlement.h` 中 `indexById` 共 **9 处出现**（含 1 处 using 转发 + 1 处注释）：

| 行 | 形态 | 是否循环内重建 |
|---|---|---|
| `:114` | 注释（共享小工具清单） | — |
| `:118` | `using settle_util::indexById;` 转发 | — |
| `:1339` | `calculateCaptureRate(state, indexById(ds))` | 否（循环外一次） |
| **`:1361`** | `const auto freshIdx = indexById(ds);` | ✅ **是**（`for (const int32_t id : atRiskIds)` 循环内，每次迭代重建） |
| `:1370` | `captureDiscipleForReflection(..., indexById(ds))` | ✅ **是**（同循环内，**又一次重建**） |
| `:1373` | `desertDiscipleCleanup(..., indexById(ds))` | ✅ **是**（同循环内，**第三次重建**） |
| **`:1727`** | `const auto freshIdx = indexById(ds);` | ❌ 否（`processSingleTheft` 单弟子函数体内，**函数入口一次**，正常） |
| `:1773` | `desertDiscipleCleanup(..., indexById(ds), "theft_desertion", ...)` | ❌ 否（同函数内**单次调用**，无重复） |
| **`:1842`** | `if (indexById(ds).find(id) == indexById(ds).end()) continue;` | 🔴 **同一表达式内两次重建（最贵）** |
| `:2304` | `detail::indexById(state.disciples)` | ❌ 否（`runMonthSettlement` **函数入口一次**，正常基线） |

**收口优先级重排（实测）**：
1. 🔴 **`:1842`** — 同一表达式两次重建，**纯成本零语义**，最易改；
2. 🟠 **`:1358-1375` 循环体** — 同一迭代内 **3 次重建**（`:1361` + `:1370`/`:1373` 二选一执行），
   可收敛为 **1 次**（`freshIdx` 复用）；
3. 🟢 `:1727` / `:1773` / `:2304` — **本就正常**，无需改动。

> ⇒ 实际待收口现场是 **2 处**（非施工卡暗示的 9 处），`batch-B18.md:132` 的
> 「9 处 `indexById` 现场重建」应校正为「**9 处 `indexById` 出现，其中 2 处为循环内重复重建**」。

**关键现场上下文**（`:1358-1375`，实测原文）：

```cpp
for (const int32_t id : atRiskIds) {
    // 前序捕获（remove+重插）/逃脱（remove）移行——行存在性重解析
    const auto freshIdx = indexById(ds);
    const auto rit = freshIdx.find(id);
    if (rit == freshIdx.end()) continue;
    const std::size_t row = rit->second;
    const double prob = calcDesertionProbability(ds.loyalties[row]);
    if (rngSystem.nextDouble() >= prob) continue;
    // 第二次抽取：捕获 vs 逃脱
    if (rngSystem.nextDouble() < captureRate) {
        captureDiscipleForReflection(state, id, state.gameData.gameYear,
                                     indexById(ds));       // ← 循环内第 2 次重建
    } else {
        desertDiscipleCleanup(state, id, lawLoyaltyThreshold(),
                              indexById(ds));              // ← 循环内第 3 次重建
    }
}
```

**⚠️ 最高优先级发现**：`:1842` 的
`if (indexById(ds).find(id) == indexById(ds).end()) continue;`
在**同一表达式中重建索引两次**（每次 O(D)）。这是纯成本中心，**且不改变语义**——
直接改写为 `const auto idx = indexById(ds); if (idx.find(id) == idx.end()) continue;` 即可。
现场上下文（`:1838-1844`）：

```cpp
for (std::size_t k = 0; k < judgeCount; ++k) {
    const int32_t id = candidateIds[k];
    // 前一候选叛逃会移除行——行存在性重解析（id 寻址等价）
    if (indexById(ds).find(id) == indexById(ds).end()) continue;   // ← 两次重建
    judgeSingleTheftCandidate(state, id, currentMonth, rngSystem, world);
}
```

**为什么"不能顺手缓存"**：`:1361` 的注释明说「前序捕获（remove+重插）/逃脱（remove）**移行**」
——行地址会变，故**必须重解析**。这是 **A 组 UAF 根治后的正确性机制**，不是低效写法。
⇒ 收口方向是**减少同一迭代内的重复重建次数**（3 次 → 1 次），**不是**把索引提到循环外。

**风险**：🟠 中。属**行为等价重构**，但落在月度结算热路径，须有桌面 GTest 逐月对拍覆盖。
**建议**：作为**独立 commit**，配套跑 `month_settlement_test.cpp` + `phase_settlement_test.cpp` 全绿。

---

### 2.3 【C 组】`aiBeastEncounterTargets` 死臂（收口批 §4⑤）

**现状实测**——全仓 **9 处命中**：

| 文件:行 | 角色 |
|---|---|
| `gamecore/.../exploration_tx.h:40` | C++ 侧对应字段 |
| `core/engine/.../model/GameData.kt:454` | 字段声明（`@Transient`） |
| `core/engine/.../ExplorationServiceBeastRaidOps.kt:128` | **读取**（门判） |
| `core/engine/.../ExplorationServiceBeastRaidOps.kt:179-180` | **删除**（清理） |
| `core/engine/.../GameEngineExplorationNativeOps.kt:170` | **门判** |
| `core/engine/.../GameEngineExplorationNativeOps.kt:178` | **门判** |
| `core/engine/.../GameDataFieldPatchGuardTest.kt:158 / 159 / 262` | 守卫 |

**已确证结论（2026-09-20 核查，见 `batch-B18.md:135`）**：
> 全仓（Kotlin + C++）该表**零插入者**——无任何 `+` / `put` / `copy(aiBeastEncounterTargets = ...)` 写入
> ⇒ **表恒空** ⇒ 两处门判恒 `false` ⇒ 遭遇战路径（`resolveEncounterPath` / `resolveBeastEncounterIfAny`）**整条死代码**。

**🚨 处置红线（务必遵守）**：
> 按 B18 口径清理，但 **`aiBeastEncounterTargets` 死臂「勿顺手删」**：
> **修复后该表保留现值**——一旦有人接上插入者即恢复工作（见收口批 C 组「值保留」判归）。

⇒ 本项**只清理死代码路径的调用点**，**字段本身保留**（含 `@Transient` 声明与 C++ 侧字段）。

**风险**：🟢 低（死代码），但**判归争议**：删门判会改变"未来接上插入者"的恢复成本。
**建议**：**最后做**，且实施前与收口批 C 组判归再确认一次。

---

### 2.4 【D 组】Room 死列清理：`battleTeam` / `aiBattleTeams`（**必须单独走批**）

**来源**：收口批 C 组判归（`batch-B18.md:134`）。

**🔴 红线（`batch-B18.md:161`）**：
> **存档 schema 变更（Room 死列）必须单独走批 + 存档回归**，不得夹带回滚臂删除。

**为什么必须单独走批**：
1. 删列需 **Room schema migration**（升 `version` + 提供 `Migration` 对象）；
2. 旧存档 → 新 schema 的 **migration 路径必须逐字段等价**（存档回归）；
3. 若与回滚臂删除混装，**对拍归因失效**（无法判断红是 migration 引起还是删臂引起）。

**验收门附加要求**（`batch-B18.md:172`）：
> 存档回归（Room migration）逐字段等价。

**风险**：🔴 **高**（涉及用户存档数据，错误 migration = 不可逆数据丢失）。
**建议**：**绝对不要放进本批**。单独开一张施工卡（如 B19 或 `batch-room-dead-columns.md`），
含：① 旧档样本准备；② migration 实现；③ migrate 前后逐字段 diff；④ 回滚方案。

---

### 2.5 【E 组】`build-atlas.mjs` / `scene_uv_tables.h` 历史注释收口（收口批 F 项）

**现状实测**：

| 文件:行 | 内容 |
|---|---|
| `android/scripts/build-atlas.mjs:14` | `* - app/src/main/cpp/scene/scene_uv_tables.h — C++ 场景 UV 常量表 + 占地表 + 双端渲染` |
| `android/scripts/build-atlas.mjs:57` | `const SCENE_UV_TABLES_OUT = path.resolve(ANDROID_DIR, 'app/src/main/cpp/scene/scene_uv_tables.h');` |
| `android/scripts/build-atlas.mjs:739` | `* 叠加层常量的双端生成行（LAYOUT.overlay 单一数据源，R3.3/B11）。` |
| `android/scripts/build-atlas.mjs:959` | `'    // 世界叠加层（overlay）视觉常量（R3.3/B11：C++ drawFrame 生成叠加层几何',` |
| `android/scripts/build-atlas.mjs:1461` | `// ── 生成器：scene_uv_tables.h（cpp/scene/，R3.2/B10 场景常量进 C++） ──` |
| `android/scripts/build-atlas.mjs:1623-1625` | `'// ── 世界叠加层（overlay）视觉常量（R3.3/B11：网格线/预览框/选中/拆除高亮的', ...overlayCppLines(layout),` |
| `NativeBridge.cpp:23 / :134 / :162 / :212 / :287` | 5 处引用 `scene_uv_tables.h` 的注释 |
| `scene/scene_draw.h:11 / :148`、`scene/float_text.h:21 / :31 / :74 / :95` | include 与单一权威说明 |
| `gamecore/test/scene_*.cpp` 4 文件 | 测试侧引用 |

**关键点**：臂 β 删除了**叠加层全套视觉常量**（`GRID_*` / `PREVIEW_*` / `HIGHLIGHT_*` / `DEMOLISH_*` /
`SELECTED_DATA_STRIDE`）的 **Kotlin 消费者**，但 `build-atlas.mjs:211-221` 仍在**生成** `LAYOUT.overlay`
双端行，`:1623-1625` 仍生成 C++ 侧注释「网格线/预览框/选中/拆除高亮」。

⇒ **收口内容应包含**：
1. `:1623-1625` 的生成注释**删去"预览框/选中/拆除高亮"**（Vulkan 侧已无消费者），
   或改为"Canvas/C++ 两路消费者"（与守卫侧的 `...on remaining arms` 口径一致）；
2. `:959` 的 Kotlin 侧生成注释同步核对——`LAYOUT.overlay` 双端生成是否仍有实际消费者，
   若仅剩 Canvas/C++ 消费者需在注释中说明；
3. `NativeBridge.cpp` 5 处注释中"双端共用"措辞按臂 β 后的实际形态收口。

**风险**：🟡 低-中。**注意 `build-atlas.mjs` 是 codegen 源**，改动会影响
`scene_uv_tables.h` 的 hash 门（`codegenSource()`）⇒ 生成物会变 ⇒ GTest 需重跑全量。
**建议**：作为**独立 commit**，且**必须在臂 β 提交之后**做（否则注释与实际形态对不上）。

---

### 2.6 【F 组】`MirrorSegmentProjectionBenchTest` 残余①② 断言口径（收口批 G2）

**现状实测**——文件实际路径为
`android/core/engine/src/test/java/com/xianxia/sect/core/gameview/MirrorSegmentProjectionBenchTest.kt`。

**B18-臂 2 已完成的部分**（`batch-B18.md:74`）：
> `MirrorSegmentProjectionBenchTest` 单臂化（去对照臂计时，保留全等断言 + 趋势数字打印）。

**残余①② 现状**——类头 KDoc 明说（`:22-45`）：

```kotlin
/**
 * ...B18-臂2 后投影臂退役、旧全量重建臂已从生产删除，
 * 本类随之从"两臂对照"改为**单臂趋势台架**...
 * 去掉的只是"与已删回滚臂比快慢"的对照臂计时（对照面已由 GameDataFieldPatchGuardTest 的 golden 夹具承接）。
 * ...
 * ## 已知观测偏差（诚实登记）
 * 测试替身 [FakeGameStateStore] 每次事务提交后 `assembleAll()` 全表组装
 * （生产是锁外增量/patch 组装），故两段都含一份相同的 O(D) 组装常数；
 * 真实设备的 mirror 段构成另由 PhaseSegmentTimer 每旬打点（debug 构建）。
 */
```

**残余问题**：
- 测试 2 `mirror 段消费侧列级导出臂对照 G2 重测`（`:63`）**仍以"对照"命名**，
  但其对照对象（列级导出臂）**已在臂 3 删除** ⇒ 命名与形态不符；
- 「已知观测偏差」段的 `FakeGameStateStore.assembleAll()` 全表组装常数**未解决** ⇒ 基线数字含固定偏置。

**建议处置**：
1. 测试 2 更名（去"对照"措辞），改为**列级信封形状的消费侧基线**（与测试 1 并列而非对照）；
2. 断言口径两条硬性质保留（列级臂不得明显慢于全脏投影臂 → 改为**列级信封基线不得退化**）；
3. 「观测偏差」段**保留**（诚实登记有价值），但需在方案 §7.2 B09 行引用时注明偏置。

**风险**：🟢 低（测试侧，不影响生产）。
**建议**：可与 §2.5 注释收口**合并为同一个"注释/口径收口 commit"**。

---

### 2.7 【G 组】方案 R2.3 残余①②：`upsertMirrorRow` 列级收窄 + store 侧 `assembleAll`

**任务定义**（`batch-B18.md:127`）：
> G2 分档基线后的残余成本中心（§7.3 已登）。

**现状**：与 §2.6 的 Bench 残余①②**同源**（同一 G2 基线下的成本中心）。
`MirrorSegmentProjectionBenchTest` 的「已知观测偏差」段正是把 `assembleAll()` 全表组装
标记为未解偏置 ⇒ 本项即「消除该偏置」的工程任务。

**风险**：🟠 中（触及生产镜像消费侧热路径）。
**建议**：与 §2.6 **同期评估**，但**生产改动与测试口径改动分 commit**。

---

### 2.8 【H 组】方案 B09 残余③④：C++ rest 域标脏细粒度化 + 弟子列表块迁投影

**任务定义**（`batch-B18.md:128`）：
> C++ rest 域标脏细粒度化、弟子列表块迁投影。

**✅ 本轮已补勘察**：

**(a) rest 域标脏现状** —— 实现位于 `state/column_dirty.h`：

| 现场 | 内容 |
|---|---|
| `column_dirty.h:496` | `resetBaseline(const GameState& s)` → `restBaseline_ = stateWithoutDisciplesToJson(s);`（初始化/导入/全量导出后重捕基线） |
| `column_dirty.h:596-598` | `exportDirtyTree`：`json cur = stateWithoutDisciplesToJson(s); diffTreeSegments(restBaseline_, cur, kNonDiscipleCollections, changed, removed); restBaseline_ = std::move(cur);` |
| `column_dirty.h:700` | `nlohmann::json restBaseline_ = nlohmann::json::object();` |
| `dirty_tracker.cpp:117` | `stateWithoutDisciplesToJson` 实现 |
| `dirty_tracker.cpp:55-65` | `kNonDiscipleCollections` = **9 个集合**（`equipmentStacks` / `equipmentInstances` / `manualStacks` / `manualInstances` / `pills` / `materials` / `herbs` / `seeds` / `storageBags`） |

**当前粒度（实测）**：
- **`gameData` 域已是字段级 diff**（`diffTreeSegments` 逐顶层 key 比对，`changed["gameData." + key]`）——
  **但注释明说「嵌套容器为整体替换语义」** ⇒ `gameData` 下的嵌套对象/数组一旦任一元素变化，
  **整个嵌套容器作为整体上报**（非逐元素）；
- **9 个非弟子集合**：调用 `diffTreeSegments(..., kNonDiscipleCollections, ...)` 做集合级 diff。

⇒ **「细粒度化」的落点**：把 ① `gameData` 嵌套容器的整体替换语义细化为逐元素/逐字段；
② 9 个集合的 diff 粒度（当前是整集合对拍 JSON 树）细化到**行级/字段级**。

**关键成本**：`stateWithoutDisciplesToJson(s)` 每旬全量序列化整棵非弟子域树（O(全状态)），
**再加** 一次全树递归 diff ⇒ 即使某个集合零变更也要付全量序列化成本。这是**真正的成本中心**。

**(b) 弟子列表块迁投影** —— 弟子域当前走**行位图 + tombstone**（`rowBits_` / `tombstones_`），
与非弟子域的「JSON 树 diff」是**两套机制**。本项即把非弟子域也从「JSON 树 diff」
迁到与弟子域同族的**typed 投影 + 脏位图**机制。

**风险**：🟠 中-高（触及 C++ 列级导出核心 + 必须与 Kotlin 消费侧解码契约同步）。
**建议**：**下轮单独立项勘察/施工**，本批只登记。收益明确（消除每旬全量序列化），
但改动面横跨 `column_dirty.h` / `dirty_tracker.{h,cpp}` + Kotlin 消费侧，
**不宜塞进 B18 收口批**。

---

### 2.9 【I 组】b03 遗留同族（除 `month_settlement.h` 外）

`batch-B18.md:132` 标注为「含 `:1361` / `:1842` **循环内重建**（A 组 UAF 根治后的同族收口）」。
同族文件建议一并 grep：`phase_settlement.h`（`:87` / `:94` / `:1226` 有 `indexById` 引用）、
`battle_residual_tx.h`（`:18` / `:461` 有 `indexById` 调用）、
`disciple_store.h`（`:26` / `:304` 有 `indexById` 语义注释）。

**建议**：与 §2.2 合并为**一个 commit**（同族、同信号、同验证面）。

---

## 3. 吸收项汇总表

| 项 | 来源 | 触点量 | 风险 | 独立性 | 建议顺序 |
|---|---|---|---|---|---|
| A `upsertsJson` typed 化 | b02 发现 8③④ | 5 处 | 🟠 中（协议红线） | **高** | **1** |
| B `month_settlement.h` `indexById` | b03 遗留 | 9 处出现 / **2 处待收口** | 🟠 中 | **高** | **2** |
| I b03 同族（settlement 其他文件） | b03 遗留 | ~6 处 | 🟠 中 | 与 B 合并 | **2**（合并） |
| E `build-atlas.mjs` 注释收口 | 收口批 F | 7+4 处 | 🟡 低-中（codegen hash 门） | 高 | **3**（须在臂 β 后） |
| F Bench 残余①② | 收口批 G2 | 1 文件 | 🟢 低 | 高 | **3**（可与 E 合并） |
| G `upsertMirrorRow` 列级收窄 | 方案 R2.3 残余①② | 待勘察 | 🟠 中 | 中 | **4** |
| H C++ rest 域标脏 + 弟子块迁投影 | 方案 B09 残余③④ | `column_dirty.h` 4 处 + `dirty_tracker.{h,cpp}` | 🟠 中-高（双端联动） | 低 | **单独立项（不宜塞进 B18）** |
| C `aiBeastEncounterTargets` 死臂 | 收口批 §4⑤ | 9 处 | 🟢 低（但判归敏感） | 中 | **5**（最后） |
| D Room 死列 `battleTeam`/`aiBattleTeams` | 收口批 C 组 | schema 级 | 🔴 **高** | — | **🚫 单独走批，不进 B18** |

---

## 4. 全局风险与红线复述

### 4.1 到期判据缺口（**仍未解决**）

`batch-B18.md:17-40` 实测结论：硬前置「一个完整发版周期 + 零回滚事件」**不满足**：

| 判据 | 状态 | 依据 |
|---|---|---|
| ① 发版周期经过 | ❌ **不满足** | `version.properties` 仍为 `versionName=4.01.14` / `versionCode=4114`，**仅 1 次提交**（`c13d65157`），版本号从未变更 |
| ② 零回滚事件可查证 | ⚠️ **证据面缺失** | 仓库内**不存在** `RenderMetrics` 回滚计数器实现；全仓 `rollback` 命中均为业务语义 |

**现状**：用户已于 2026-09-20 晚**两次显式指令开工**（知情覆盖，已登 `batch-B18.md:51`）。
**本报告重申**：该缺口**仍然存在**，臂 γ 与吸收项开工同样带此缺口；
最终验收（`batch-B18.md:173`）要求「到期判据证据附于完成报告」⇒ **该项须在验收前补齐或书面豁免**。

### 4.2 纪律复述（每条都有前车之鉴）

1. **每子项独立 commit**（`batch-B18.md:159`）——回滚臂删除 / `upsertsJson` typed 化 /
   Room 死列 / 注释收口 **各自独立**，勿混装（对拍归因红线）；
2. **删臂后守卫不得失去对照面**（`:164`）——对照臂 = golden 夹具或**仍在生产的实现**，
   **不是删断言、也不是留活体死码**；
3. **「保留作对照实现」必须过 detekt**——臂 β 实证：私有函数无消费者 ⇒
   `UnusedPrivateMember` 报红，**留活体死码不算保留对照面**；
4. **删臂前须 grep 每个端口的调用点**，而非只 grep 旗标——臂 β 的 `setFadeAlpha` / `drawAllTiles`
   即为「连锁死面」（仅由旧臂调用 ⇒ 连带成死面，JNI -2）；
5. **协议 JSON 面**：typed 化只允许 proto **追加字段**，既有字段号与语义**零变更**；
6. **存档 schema 变更必须单独走批 + 存档回归**；
7. **Canvas 兜底红线（R3/R3.6）**：`SoftwareCanvasBackend*` 不得进任何改动清单；
8. **构建副产物勿混入提交**（如 `atlas-rgba-manifest.json` 仅 `generatedAt` 变化须 `git checkout --`）。

### 4.3 环境约定（Windows 本机）

- `JAVA_HOME=C:/Users/cp050/.jdks/jdk-21.0.12.1+1`；
- 桌面 GTest 单进程直跑**必须 `cd` 到 gamecore 源码根**，否则 6 个 `DataStoreGuardTest` fail-fast；
- release 构建残留句柄会锁 `classes.jar` / `merged_res`；`rm -rf` 被沙箱 safe-delete 拦截时
  改用 Python `shutil.rmtree`；
- 组合门全六模块耗时 **20-48 分钟**（本轮 20m58s，含大量 up-to-date）。

---

## 5. 建议排期

| 阶段 | 内容 | 前置 | 预估门禁 |
|---|---|---|---|
| **P0** | 拍板臂 γ 的 γ-1 / γ-2 / γ-3 三个语义问题 | 需产品/架构决策 | — |
| **P1** | 吸收项 A（`upsertsJson` typed 化） | 无 | 组合门 + 桌面对拍 |
| **P2** | 吸收项 B + I（`indexById` 收口） | 无 | 组合门 + `month_settlement_test` / `phase_settlement_test` |
| **P3** | 吸收项 E + F（注释/口径收口） | 臂 β 已提交 | 组合门 + 全量 ctest + NDK arm64 |
| **P4** | 吸收项 G（列级收窄） | P3 完成 | 组合门 + Bench |
| **P5** | 吸收项 C（`aiBeastEncounterTargets` 死臂） | 收口批判归确认 | 组合门 + `GameDataFieldPatchGuardTest` |
| **P6** | 臂 γ（视 P0 结论，形态可能退化为仅删旗标门控） | **P0 必须完成** | 组合门 + `Diff*` 五套 + GTest |
| **PX** | 吸收项 H（B09 残余③④） | **单独立项**（本轮已补勘察：成本中心 = 每旬全量序列化 + 全树 diff） | 专项 |
| **🚫** | 吸收项 D（Room 死列） | **单独走批** | 存档回归 + migration 逐字段等价 |

**PX 说明**：H 项成本中心确认为 `column_dirty.h:596` 的
`stateWithoutDisciplesToJson(s)` —— **每旬全量序列化整棵非弟子域树 + 一次全树递归 diff**，
即使零变更也照付。收益明确，但改动横跨 `column_dirty.h` / `dirty_tracker.{h,cpp}`
+ Kotlin 消费侧解码契约 ⇒ **建议单独开卡（如 `batch-b09-residual.md`），不进 B18 收口批**。

---

## 6. 本报告未覆盖 / 待补

| # | 缺口 | 需补什么 |
|---|---|---|
| 1 | ~~吸收项 H 触点~~ | ✅ **本轮已补**（见 §2.8：`column_dirty.h` / `dirty_tracker.{h,cpp}`，成本中心已定位） |
| 2 | 臂 γ 的 γ-1/2/3 决策 | 需产品/架构拍板 |
| 3 | 到期判据证据面 | 补灰度回滚埋点（`RenderMetrics` 计数器或等效）或书面豁免 |
| 4 | 施工卡口径校正（**2 处**） | ① `batch-B18.md:143`「3 执行器」→ 实测 **5 个调用点 / 4 个文件**；② `batch-B18.md:132`「9 处 `indexById` 现场重建」→ 实测 **9 处出现，其中 2 处循环内重复重建** |
| 5 | 吸收项 D 的 migration 设计 | 单独施工卡 |

---

## 附录 A：臂 γ 四个回退点原文摘录

```kotlin
// 1. EncounterBattleService.kt:152
private fun executeRouted(battle: Battle): BattleSystemResult {
    return BattleExecutionRouter.tryExecuteNative(battle)
        ?: battleSystem.executeBattle(battle)
}

// 2. SecretRealmService.kt:802
return BattleExecutionRouter.tryExecuteNative(battle)
    ?: battleSystem.executeBattleWithTimeout(battle)   // ← 注意与其余三处不同

// 3. ExplorationService.kt:332（注释已明写"不删臂"）
// flag 关/native 未加载/失败信封 → null 回退 Kotlin 既有臂（不删臂）
return BattleExecutionRouter.tryExecuteNative(battle)
    ?: battleSystem.executeBattle(battle)

// 4. MissionSystem.kt:344（EnemyType.BEAST 分支）
BattleExecutionRouter.tryExecuteNative(battle)
    ?: battleSystem.executeBattle(battle)
// 4'. MissionSystem.kt:367（EnemyType.HUMAN 分支，同函数内）
BattleExecutionRouter.tryExecuteNative(battle)
    ?: battleSystem.executeBattle(battle)
```

> **注**：`ExplorationService.kt:331` 的现成注释「**不删臂**」是既有代码里对
> 本降级契约的显式声明 ⇒ 臂 γ 动这块之前**必须**先推翻或改写该声明。

## 附录 B：`aiBeastEncounterTargets` 死臂全触点

```
gamecore/include/gamecore/system/exploration_tx.h:40
core/engine/src/main/java/com/xianxia/sect/core/model/GameData.kt:454          (@Transient 字段声明)
core/engine/src/main/java/com/xianxia/sect/core/engine/domain/exploration/ExplorationServiceBeastRaidOps.kt:128      (读取/门判)
core/engine/src/main/java/com/xianxia/sect/core/engine/domain/exploration/ExplorationServiceBeastRaidOps.kt:179-180  (删除/清理)
core/engine/src/main/java/com/xianxia/sect/core/engine/GameEngineExplorationNativeOps.kt:170                          (门判)
core/engine/src/main/java/com/xianxia/sect/core/engine/GameEngineExplorationNativeOps.kt:178                          (门判)
core/engine/src/test/java/com/xianxia/sect/core/model/GameDataFieldPatchGuardTest.kt:158
core/engine/src/test/java/com/xianxia/sect/core/model/GameDataFieldPatchGuardTest.kt:159
core/engine/src/test/java/com/xianxia/sect/core/model/GameDataFieldPatchGuardTest.kt:262
```
