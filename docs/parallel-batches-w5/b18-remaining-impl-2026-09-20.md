# B18 剩余项实施文档（P1–P5 施工细则 + B20/B19 专项卡定义）

> 生成：2026-09-20 深夜（看护侧）
> **拍板记录**：用户 2026-09-20 深夜确认 `b18-solution-2026-09-20.md` §6 四项**全部按推荐执行**
> （① γ 判归 A = 零删除 / ② G+H 移出 B18 并入镜像残余专项批 B20 / ③ 到期判据书面豁免 /
> ④ P1 待派）；随后用户指示**「只需要给出实施文档，不用实施」**——本文档即交付物，
> **未派发、未动工**，派发由用户口头指令触发（§8 有现成指令模板）。
>
> 文档链：勘察报告（`b18-remaining-arms-report-2026-09-20.md`）→ 解决方案
> （`b18-solution-2026-09-20.md`，含全部判据与勘误）→ **本文档（实施层，施工子会话唯一必读）**。
> 本文所有行号/计数均经看护 2026-09-20 深夜独立复核。

---

## 0. 总排期、commit 划分与门禁总表

| 阶段 | 内容 | commit | 前置 | 门禁 | 状态 |
|---|---|---|---|---|---|
| **P1** | `upsertsJson` 族 typed 化（A1 信封级两字段 + A2 弟子行 storageBagItems） | 2 笔独立 | 无 | 桥重建 + ctest 全量 + 组合门 + Diff* 0 skip + NDK arm64 + JNI 计数 87 不变 | 未动 |
| **P2** | `indexById` 收口（month_settlement 2 处 + I 族同型） | 1 笔 | 无 | settlement 两套 + ctest 全量 + NDK + 组合门 | 未动 |
| **P3** | 注释/口径收口（build-atlas.mjs/NativeBridge.cpp + Bench 残余①②） | 1 笔 | 臂 β `4ed1d7c24` ✅ | 组合门 + ctest 全量（codegen hash 面）+ NDK | 未动 |
| **P4** | `aiBeastEncounterTargets` 死臂清理 | 1 笔 | 收口批 C 组判归复核 | 组合门 + `GameDataFieldPatchGuardTest` + ctest 照跑 | 未动 |
| **P5** | γ-收口（口径 + 守卫，**零行为变更**） | 1 笔 | 拍板①✅ | 组合门 + ctest 照跑 + Diff* 五套零改动 | 未动 |
| **B20** | 镜像残余专项批（B20a/b/c 三阶段） | ≥3 笔 | **B18 P3 完成** | 组合门 + ctest + NDK + **分档 bench 门** + Diff* 0 skip | 立卡（附A） |
| **B19** | Room 死列清理批 | ≥2 笔 | 旧档样本勘察 | Room 迁移族 + **存档回归逐字段等价** + 组合门 | 立卡（附B） |

纪律复述（每条都有前车之鉴）：每子项独立 commit；删臂后守卫不得失去对照面（golden 夹具或活体实现，不是删断言）；proto 只增字段；存档 schema 单独走批；Canvas 兜底（`SoftwareCanvasBackend*`）零触碰；构建副产物（`atlas-rgba-manifest.json`）勿混提交。

---

## 1. P1 — `upsertsJson` 族 typed 化（b02 发现 8③④；A1 + A2 两笔 commit）

### 1.1 范围（三字段，看护已复核补全）

| 字段 | proto 位置 | 生产消费点（单点切读） | 归属 |
|---|---|---|---|
| `CollectionChange.upsertsJson`（bytes） | `game_view.proto:269` | `GameViewMirrorCodec.kt:162-163`（9 个非弟子集合） | **A1** |
| `JsonFieldChange.valueJson`（bytes） | `game_view.proto:281` | `GameViewMirrorCodec.kt:130-131`（`gameData.*` 其余键，嵌套容器整体替换语义） | **A1** |
| `DiscipleRow.storageBagItemsJson`（bytes，字段号 75） | `game_view.proto:195` | `GameViewDiscipleRows.kt:331-332`（typed 行解码）+ `GameViewMirrorCodec.kt:509`（JSON 树路径 `bj`）+ `GameViewDiscipleRows.kt:524`（测试夹具编码侧） | **A2** |

C++ 生产编码器 = `gamecore/src/gameview_encode.cpp:326`（`encodeGameView`，手写 wire 编码器，~百行；契约注释在 `include/gamecore/state/gameview_encode.h:29-31`）。C++ 守卫 = `gamecore/test/gameview_encode_test.cpp:221/235/205`。

### 1.2 proto 设计（**只增字段**，既有字段号与语义零变更）

追加三个通用消息 + 三个 typed 字段（草案，施工时可微调命名，字段号不可动）：

```proto
/// 通用 typed 值（JSON 树任意节点的 proto 原生承载；递归覆盖数组/对象）
message TypedValue {
  optional string vString = 1;
  optional int64 vInt = 2;
  optional double vDouble = 3;
  optional bool vBool = 4;
  repeated TypedValue vArray = 5;
  repeated TypedField vObject = 6;
  // 勘察点①：JSON null 是否实际出现于三字段载荷——无则省略 vNull
  optional bool vNull = 7;
}

/// 通用 typed 键值行（fields 按 key 字典序 = nlohmann std::map 序，确定性保持）
message TypedField {
  optional string key = 1;
  optional TypedValue value = 2;
}

/// 通用 typed 实体行（一个 upsert 实体 = 一个 TypedRow；行内字段可经 TypedValue 递归）
message TypedRow {
  repeated TypedField fields = 1;
}
```

- `CollectionChange` 追加：`repeated TypedRow upsertsTyped = 4;`（2 号 `upsertsJson` **停写保留、号冻结**）
- `JsonFieldChange` 追加：`optional TypedValue valueTyped = 3;`（2 号 `valueJson` 同上）
- `DiscipleRow` 追加：`repeated TypedRow storageBagItemsTyped = 110;`（109 号后首个空号；75 号同上停写保留）

**为什么用通用行式而非 9 个专属消息**：C++ diff 树里这些集合本就是 nlohmann::json 对象数组，通用递归走树即可编码，零 per-collection 字段表（弟子行 `kDiscipleRowFields` 先例是"109 字段单消息"才值得专属表）；Kotlin 侧内部交换格式仍是 JsonElement 树，下游 `GameStateStore` applier 零变更。收益 = 消灭 C++ JSON 文本序列化 + Kotlin JSON parse（正是 CHANGELOG 登记的"第二波收益面"）。

### 1.3 C++ 编码面（`gameview_encode.cpp`）

1. 新增递归编码助手：`appendTypedValue(buf, fieldNo, const json& v)`（按 `v.is_string/is_number_integer/is_number_float/is_boolean/is_array/is_object/is_null` 分派，**数组/对象递归**）与 `appendTypedRow(buf, fieldNo, const json& row)`；
2. `collectionChange` 编码点：现写 `upsertsJson`（JSON dump bytes）处 → 改写 `upsertsTyped`（走树）；**旧 bytes 编码分支删除**（这是本项的"删臂"，对照面转测试侧 golden，见 1.5）；
3. `gameDataChange` 编码点：同法切 `valueTyped`；
4. `DiscipleRow` 字段 75 编码点：改写 `storageBagItemsTyped`（A2）；
5. 契约注释同步：`gameview_encode.h:29-31` 语义契约段改写为 typed 承载描述（保留"与旧 JSON 协议逐值等价，等价性守卫锁定"措辞）；
6. 确定性约束保持：字段号升序 + nlohmann 键序遍历（`gameview_encode.h:37-39`）——TypedField 内 key(1)→value(2)，TypedRow.fields 按字典序。

### 1.4 Kotlin 解码面（单点切读，三处）

1. `GameViewMirrorCodec.decodeCollectionChanges`（:156-167）：`upsertsTypedCount > 0` → 走 `typedRowsToJsonElement()` 重建 JsonArray；**否则 fallback 旧 `upsertsJson` bytes 解析**（旧格式 golden 夹具仍可解码——对照面保留，非删断言）；
2. `decodeView` 的 gameDataChange 段（:129-132）：`valueTyped` 优先、`valueJson` fallback，同上；
3. `GameViewDiscipleRows.kt:331-332`（A2）：`storageBagItemsTypedCount > 0` → typed 重建 JsonArray → **复用既有 `json.decodeFromJsonElement(serializer, …)`**（域模型反序列化器零变更）；fallback 75 号 bytes；`:524` 夹具编码侧与 `codec:509 bj` 同步双路。

### 1.5 等价守卫（三层，缺一不可）

1. **C++ 编码层**：`gameview_encode_test.cpp` golden 更新——同树断言 typed 字段在位 + **逐字节确定性**（两次编码相等，既有 `:349-350` 同款断言扩展）；`:221/:235` 用例改 typed 断言，`:205`（字段 75）随 A2 改；
2. **双路解码等价（核心）**：Kotlin 侧新增/扩展守卫（建议挂 `MirrorProtoFeedEquivalenceTest` 族）：对同一事实源夹具构造**旧格式信封（直设 bytes 字段）与新格式信封（C++ 真编码，桌面 Diff* 路径 / JVM 用 Kotlin 夹具构 typed）**，`decodeView` 双路产物逐字段相等（含 9 集合全类型叶值：string/int/double/bool/数组/对象、gameData 标量与嵌套容器、storageBagItems 递归结构）；
3. **端到端不动**：`MirrorProtoFeedEquivalenceTest` / `DiffMirrorArmConvergenceTest` / `GameDataFieldPatchGuardTest` 原断言零改动全绿（它们锁的是语义端到端，形状换轨不得扰动）。

### 1.6 红线与勘察点

- 🔴 proto **只增字段**：2/75 号既有字段**停写但保留**（号冻结永不复用、语义注释不改"过渡编码 v1"历史事实）；
- 🔴 数字格式等价：int64/double 经 TypedValue 重建的 `JsonPrimitive` content 必须与旧 JSON 文本解析产物**逐字符串相等**（树已过 `normalizeIntegralFloats`，双路同源；若 double 格式化有差——如 `1.0` vs `1`——以等价守卫红为准，在 C++ 侧按 nlohmann dump 同规则格式化或 Kotlin 侧 content 直构，**不得改 normalize 语义**）；
- 🟠 勘察点①：JSON null 在三字段载荷中是否出现（决定 `vNull` 去留）；
- 🟠 勘察点②：9 集合实体是否含超长嵌套（equipment/manual 族技能列表）——通用递归已覆盖，但若某集合 typed 后体积/耗时劣化（proto 重复 tag 开销 vs JSON 紧凑），**实测对比登记**，必要时该集合暂留 JSON（登记残余，B20 一并收）；
- 🟢 镜像信封为瞬态（导出即消费，不落盘），无双端版本错配风险；存档面零触及。

### 1.7 A1/A2 划分与门禁

- **A1（第一笔 commit）**：`CollectionChange.upsertsTyped` + `JsonFieldChange.valueTyped`（proto + C++ 编码切换 + codec 两处切读 + 守卫三层）；
- **A2（第二笔 commit，依赖 A1 的 TypedValue 基建）**：`DiscipleRow.storageBagItemsTyped`（C++ 行编码 75→110 + `GameViewDiscipleRows` 双路 + codec `bj` + 夹具侧）；
- **门禁（每笔各自过）**：`pwsh scripts/build-desktop-jni.ps1` 桥重建 → ctest 全量（基线 1553）→ 组合门（`testReleaseUnitTest --max-workers=1 --rerun-tasks "-Dgamecore.jni.path=<.so>"` + detekt + compile + lint，XML 时间戳落本轮实证）→ **Diff* 0 skip** → NDK arm64 `:app:assembleRelease` → JNI 计数门禁 **87/87 不变**（零新端口）；
- 完成报告须附：双路等价守卫的实跑输出、`gameview_encode_test` golden diff 逐条说明、（可选）`MirrorSegmentProjectionBenchTest` decode 段趋势数字前后对照。

---

## 2. P2 — `indexById` 收口（b03 遗留 + I 族同型；一笔 commit）

### 2.1 `month_settlement.h`（10 行命中 = 1 注释 `:114` + 1 using `:118` + 8 调用；**只收 2 处**）

**(a) `:1842` 同表达式两次重建（最贵，纯成本零语义）：**

```cpp
// 现状（judgeSingleTheftCandidate 候选循环内）
if (indexById(ds).find(id) == indexById(ds).end()) continue;
// 改为
const auto idx = indexById(ds);
if (idx.find(id) == idx.end()) continue;
```

**(b) `:1358-1375` 循环体 3 次重建 → 1 次（`freshIdx` 复用传参）：**

```cpp
for (const int32_t id : atRiskIds) {
    // 前序捕获（remove+重插）/逃脱（remove）移行——行存在性重解析（注释保留，勿删）
    const auto freshIdx = indexById(ds);
    const auto rit = freshIdx.find(id);
    if (rit == freshIdx.end()) continue;
    const std::size_t row = rit->second;
    const double prob = calcDesertionProbability(ds.loyalties[row]);
    if (rngSystem.nextDouble() >= prob) continue;
    if (rngSystem.nextDouble() < captureRate) {
        captureDiscipleForReflection(state, id, state.gameData.gameYear, freshIdx);  // ← 复用
    } else {
        desertDiscipleCleanup(state, id, lawLoyaltyThreshold(), freshIdx);           // ← 复用
    }
}
```

**等价性论证（写进 commit body）**：`freshIdx` 构建与 capture/desert 调用之间无任何 `ds` 变更（变异发生在被调函数内部，只影响下一迭代的重解析需求）⇒ 传参与调用点现建同值。**红线**：跨迭代重建是 A 组 UAF 根治后的正确性机制，**不得提循环外**；`:1727`/`:1773`/`:2304` 本就正常**勿动**。

### 2.2 I 族同型收口（grep 已定位，只收"循环内/同表达式重复重建"）

| 文件 | 行 | 处置 |
|---|---|---|
| `phase_settlement.h` | `:87` / `:94` / `:1226` | 勘察：同型才收，函数入口单次不动 |
| `battle_residual_tx.h` | `:18` / `:461` | 同上 |
| `disciple_store.h` | `:26` / `:304` | 注释语义引用，随实际改动同步，无重复重建则零改动 |

### 2.3 门禁

`month_settlement_test` + `phase_settlement_test` 全绿 + ctest 全量（C++ 改动，行为等价重构落月结热路径）+ NDK arm64 + 组合门。完成报告附：改动前后两文件 diff + 等价性论证段。

---

## 3. P3 — 注释/口径收口（收口批 F 项 + G2 残余②；一笔 commit）

### 3.1 E：`build-atlas.mjs` / `scene_uv_tables.h` 历史措辞（臂 β 后实际形态 = Vulkan 旧臂已删，消费者仅 Canvas/C++ 两路）

| 文件:行 | 改动 |
|---|---|
| `android/scripts/build-atlas.mjs:1623-1625` | 生成注释删"预览框/选中/拆除高亮"（Vulkan 消费者已随臂 β 退役），改"Canvas/C++ 两路消费者"口径（与守卫侧 `...on remaining arms` 一致） |
| `build-atlas.mjs:959` | Kotlin 侧 overlay 生成注释同步核对同口径 |
| `build-atlas.mjs:14` / `:57` / `:739` / `:1461` | 历史措辞按臂 β 后形态收口（`LAYOUT.overlay` 双端生成行的"双端渲染"说明保留事实、删已退役路径暗示） |
| `NativeBridge.cpp:23` / `:134` / `:162` / `:212` / `:287` | 5 处"双端共用"类注释按两路消费者口径改写 |
| `scene/scene_draw.h:11` / `:148`、`scene/float_text.h:21` / `:31` / `:74` / `:95` | include 与单一权威说明核对（预期零改动或极小） |
| `gamecore/test/scene_*.cpp` 4 文件 | 测试侧引用核对（预期零改动） |

**注意**：`build-atlas.mjs` 是 codegen 源 → `scene_uv_tables.h` 重新生成 → `codegenSource()` hash 门变化属预期，**GTest 全量必须重跑**；若重生成产生 `atlas-rgba-manifest.json` 仅 `generatedAt` 变化 → `git checkout --` 还原，勿混提交。

### 3.2 F：`MirrorSegmentProjectionBenchTest` 残余①②

文件 `android/core/engine/src/test/java/com/xianxia/sect/core/gameview/MirrorSegmentProjectionBenchTest.kt`：

1. 测试 2（`:63` 附近，"mirror 段消费侧列级导出臂对照 G2 重测"）**更名**去"对照"措辞（对照对象列级臂已于臂 3 删除）→ 改为"列级信封形状的消费侧基线"（与测试 1 并列）；
2. 断言口径：两条硬性质改为"**列级信封基线不得退化**"（对自身历史趋势，非对已删臂）；
3. 「已知观测偏差」KDoc 段（`:22-45`）**保留**（诚实登记有价值），在方案 §7.2 B09 行引用处注明 `FakeGameStateStore.assembleAll()` O(D) 组装偏置（消除工作属 B20a）。

### 3.3 门禁

组合门 + ctest 全量 + NDK arm64（codegen 面变化）。零生产 Kotlin/C++ 行为变更（注释 + 测试更名 + codegen 注释行）。

---

## 4. P4 — `aiBeastEncounterTargets` 死臂清理（收口批 §4⑤；一笔 commit）

**已确证**（2026-09-20 收口批 + B18 复核）：全仓（Kotlin + C++）该表**零插入者** ⇒ 恒空 ⇒ 两处门判恒 false ⇒ 遭遇战死路径。

| 触点 | 处置 |
|---|---|
| `ExplorationServiceBeastRaidOps.kt:128`（读取/门判） | 删 |
| `ExplorationServiceBeastRaidOps.kt:179-180`（删除/清理） | 删 |
| `GameEngineExplorationNativeOps.kt:170` / `:178`（门判） | 删 |
| `resolveEncounterPath` / `resolveBeastEncounterIfAny` 死路径函数 | 随门判一并清理（先 grep 确认调用链闭合） |
| `GameData.kt:454`（`@Transient` 字段声明） | **保留现值**（红线） |
| `exploration_tx.h:40`（C++ 侧字段） | **保留**（零 C++ 改动） |
| `GameDataFieldPatchGuardTest.kt:158` / `:159` / `:262`（守卫） | **保留**（值保留判归的锁定面） |

**红线**："值保留"判归——一旦有人接上插入者即恢复工作，故字段本体与守卫全留。开工前与收口批 C 组判归再确认一次。门禁：组合门 + `GameDataFieldPatchGuardTest` 全绿 + ctest 照跑（纯 Kotlin 批，免桥重建理由 = 零 C++ 改动）。

---

## 5. P5 — γ-收口（判归 A = 零删除；一笔 commit，**零行为变更**）

**判归依据**（解决方案 §2，用户已拍板）：`if (!NativeEngineFlag.authoritative) return null` = 全局逐动作 OFF kill-switch 的战斗子系统投影（`GameEngineNativeOps` 等 ~30 生产点共用，五条灰度臂名单本不含战斗）；`isLoaded` 门 = 平台兜底前置；error 信封门 = 容错；`?: executeBattle`（**6 文件 11 调用点**）= 容错 + JVM 测试回退 + Diff* 对照面三职。**全部保留，一行生产逻辑不改。**

| # | 文件:行 | 改动（全部为注释/KDoc/测试文档） |
|---|---|---|
| 1 | `BattleExecutionRouter.kt:17-31`（KDoc） | 降级契约三行重标性质：OFF kill-switch 投影 / 平台兜底 / 失败信封容错——**均非灰度回滚臂，B18 判归不删**（同臂 3 `columnExportBlocked_` 判例）；"适配范围"段校准为 6 文件 11 调用点全列（现漏秘境 R4.2 与 AI 宗门三段） |
| 2 | `ExplorationService.kt:329-331`（注释） | 「…回退 Kotlin 既有臂（**不删臂**）」→「kill-switch + 容错兜底（B18 判归：非回滚臂，不删）」 |
| 3 | `PatrolBattleSystem.kt:378-380`（注释） | 同款「不删臂」注释同口径改写 |
| 4 | `SecretRealmService.kt:794-799`（KDoc） | 「灰度契约」→「降级契约（非灰度）」；保留"Kotlin 臂保留超时口径"说明 |
| 5 | `MissionSystem.kt:340` / `:363` 相邻注释 | 勘察确认：有"回退臂/灰度"措辞则同口径改写，无则零改动 |
| 6 | `BattleExecutionRouterTest.kt`（KDoc） | 补诚实登记：error 信封→null 分支 JVM 无桥不可单测（无注入缝），守卫由 KDoc 契约 + 桌面 `Diff*` 对拍承担——**勿造假用例** |
| 7 | 方案 §7.2 + 台账 | G5 判归结论登记（B18 行） |

**守卫零变更声明**（完成报告必含）：`BattleExecutionRouterTest`（OFF→null / 桥未加载→null 两契约）、`EncounterBattleServiceRouteTest`（JVM 兜底回归）、`BattleResidualNativeTxGateTest`、`Diff*` 五套（对照面 = 活体 Kotlin `BattleSystem`，继续在库即继续有效）、`BattleSystemTest` —— **一个文件都不改断言**。

**门禁**：组合门（纯 Kotlin/注释批，免桥重建理由 = 零 C++ 改动）+ ctest 照跑（SOP）+ Diff* 五套零改动零退化（XML 对比）。

---

## 附 A — B20 镜像残余专项批施工卡（`batch-B20-mirror-residual.md`，派发时照此立卡）

> 定位：G2 终态收官批。吸收 B18 移出的 G（方案 R2.3 残余①②）+ H（方案 B09 残余③④）+ G2 分档基线重定（终审建议③A）+ WS-1 关闭。
> 前置：**B18 P3 完成**（Bench 口径收口是测量前提）。建议三阶段独立 commit/可分批派发。

### B20a — Kotlin 侧组装偏置消除（G 残余①②，纯 Kotlin）

- 成本中心：`DiscipleTables.upsertMirrorRow`（`DiscipleTables.kt:558`，行 upsert 全组列写）+ `assembleAll`（`:661`，O(D) 全表组装）；
- **既有增量机制勘察先行**：`assembleAllIncremental(prevSnapshot, changedIds)`（`:707`）与 `assembleAllPatched`（`:762`）已在库——本阶段 = 勘察其生产接线缺口（`StateSyncService.kt:523/549` 列级补丁合并臂、`:641/:660` upsert 调用点、`GameViewStore` 快照组装路径）并补齐，使生产锁外组装走增量/patch，**消除 Bench 登记的 `FakeGameStateStore.assembleAll()` O(D) 偏置**（测试替身同步换口径）；
- `upsertMirrorRow` 列级收窄：与列级补丁臂合并逻辑统一评估（合并已存在则评估"合并后仍全组写"的残余成本）；
- 守卫：`MirrorSegmentProjectionBenchTest` 基线重定 + 全等断言不破；`DiscipleTablesMirrorUpsertTest` 语义零变。
- 门禁：组合门 + ctest 照跑 + Bench 新基线数字（偏置消除前后对照）。

### B20b — C++ rest 域标脏细粒度化（H 残余③前半）

- 成本中心（已勘察）：`column_dirty.h:596-598` 每旬 `stateWithoutDisciplesToJson(s)` **全量序列化整棵非弟子域树 + 全树递归 diff**（零变更也照付）；基线重捕 `:496` / 成员 `:700`；`dirty_tracker.cpp:117`（序列化实现）+ `:55-65`（`kNonDiscipleCollections` 9 集合）；
- 方向：9 集合 + `gameData` 嵌套容器从「JSON 树 diff」迁到与弟子域同族的 **typed 投影 + 脏位图**；`gameData` 域嵌套容器"整体替换语义"细化为行级/字段级；
- **与 P1(A1) 的衔接**：rest 域直出 `TypedRow`（P1 已建通用行式基建），`collectionChange` 生产者从"树 diff + JSON 原文"变"脏位图 + typed 直出"；
- 红线：`columnExportBlocked_` 异构锁存语义不破；`resetBaseline`（初始化/导入/全量导出后重捕）语义不破；mirror 应用结果与现实现逐字段全等（`DiffMirrorArmConvergenceTest` / `GameDataFieldPatchGuardTest` 零改动全绿）；proto 只增字段。
- 门禁：桥重建 + ctest 全量 + 组合门 + Diff* 0 skip + NDK。

### B20c — 弟子列表块迁投影 + G2 分档基线收官（H 残余④ + WS-1 关闭）

- 弟子域现走行位图 + tombstone（`rowBits_`/`tombstones_`），非弟子域经 B20b 同族化后，评估弟子列表"块迁投影"残余（勘察定义实施面）；
- **G2 分档基线门（本批验收核心）**：D≤1000 <10ms、D=5000 <150ms（终审建议③A），bench 门按 G1 先例（`GAMECORE_BUILD_BENCH` 模式）考虑接入 ci.yml；达标即登记 **WS-1 关闭**（方案 §7.2/§7.3 回填）；
- 若 D=5000 档仍未达 150ms：诚实登记 + 分档阈值重拍板，**不得放宽断言了事**。
- 门禁：组合门 + ctest + NDK + 分档 bench 门 + Diff* 0 skip + 存档回归零触及声明。

---

## 附 B — B19 Room 死列清理批施工卡骨架（`batch-B19-room-dead-columns.md`，派发时照此立卡）

> 定位：`game_data` 表死列清理。**🔴 单独走批红线**：存档 schema 变更不得与任何其他批混装；错误 migration = 不可逆用户数据丢失。
> **本轮看护澄清（防误删）**：死列 = `battleTeam TEXT`（**单数**；`GameData.kt:494`，注释明说"保留用于 Room schema 兼容旧存档，逻辑层使用 battleTeams"）+ `aiBattleTeams TEXT`（`GameData.kt:522`，死判待本批第一步复核）。**`battleTeams`（复数）是活跃列**（`GameDatabaseMigrationsV40.kt:14` 起 V40 持久化；生产消费者遍布 feature/game 与 engine），**绝不可动**。

### 任务（顺序执行）

1. **死判复核**：grep 两列的 Room 写入者/读取者（含 `RoomMigrationSupport.kt:351` 列清单、`GameDatabaseMigrationSupport.kt:70-71`、`GameDatabaseMigrationsV11ToV20.kt:98-99/339/394-395/484/548-549` 迁移链引用——迁移链内的列搬运引用**不属于死判范围**）；同时核 `aiBattleTeams` 与 `SectOrganizationState.kt:19` 的关系；
2. **旧档数据窗口勘察**：V40 之前旧档 `battleTeam`（单数）可能**非空**——决定 migration 语义：搬运（单数 → battleTeams 兼容形状）或确认业务上可弃（收口批判归依据回填）；
3. `@Database(version)` 递增（版本常量 `GameDatabase.kt:95`）+ **同步登记 `MIGRATION_(N-1)_N`**（`MigrationChainGuardTest` 纪律：漏登记即红）；
4. 列删除走**重建表模式**（`GameDatabaseMigrationsV11ToV20.kt` 全程先例；不依赖 SQLite DROP COLUMN 版本面）；
5. Room schema 导出 JSON 更新 + 豁免登记；
6. **存档回归**：构造旧档样本（V39 形态，含单数 battleTeam 非空）→ migrate → 读回 → `battleTeams` 等逐字段等价断言 + 被删列数据处置确认；
7. 回滚方案：升级前自动备份判据（`GameDatabase.kt:95` 统一常量）说明 + 降级不支持声明。

### 门禁

`RoomMigrationTest` / `MigrationChainGuardTest` / `RoomMigrationLegacyTest` / `RoomMigrationRecoveryTest` 全绿 + 存档回归逐字段 diff + 组合门 + ctest 照跑（预期零 C++ 改动）。

---

## 8. 派发指令模板（用户口头触发时直接用）

| 派发 | 指令 |
|---|---|
| P1 | 读取 `docs/parallel-batches-w5/b18-remaining-impl-2026-09-20.md`，严格按 §1 实施 P1（upsertsJson 族 typed 化，A1+A2 两笔独立 commit）。完成后按 §1.7 门禁逐项自检并给完成报告。 |
| P2 | 读取同文档，严格按 §2 实施 P2（indexById 收口，一笔 commit），门禁见 §2.3。 |
| P3 | 读取同文档，严格按 §3 实施 P3（注释/口径收口，一笔 commit），门禁见 §3.3。 |
| P4 | 读取同文档，严格按 §4 实施 P4（死臂清理，一笔 commit），开工前先与收口批 C 组判归复核。 |
| P5 | 读取同文档，严格按 §5 实施 P5（γ-收口，零行为变更，一笔 commit）。 |
| B20 | 读取同文档附 A，按 B20a/b/c 立卡 `batch-B20-mirror-residual.md` 后实施（建议 a/b/c 分派）。 |
| B19 | 读取同文档附 B，立卡 `batch-B19-room-dead-columns.md` 后实施（先做任务 1-2 勘察再动 schema）。 |

> 派发渠道照旧（ZCode + 焦点红线六步）；验收 SOP 照旧（看护亲跑两门，不采信自述）。
