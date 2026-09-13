# 随机源治理 ADR 实施状态（未完成批 · 交接）

> **本文件是中途交接**：ADR《随机源治理》实施进行到「验证收口」阶段时，仍有 **14 处测试失败**
> 未根治。按用户公约第 10 条（诚实报告进度）落档，禁止把本批误判为已完成。

| 项 | 内容 |
|---|---|
| 依据 | [ADR rng-determinism-remediation.md](adr/rng-determinism-remediation.md)（阶段 0+1+2+4） |
| 已确认范围 | 阶段 0 + 1 + 2 + 4（阶段 3 另立分批）+ 在途阻塞根治（用户 2026-09 拍板） |
| 当前状态 | **未完成**——主体改动已落地且主源全绿，测试验证未收口 |

---

## 1. 已验证通过（可复现的绿）

| 门禁 | 结果 | 命令 |
|---|---|---|
| 桌面 C++ 全量单测 | ✅ **1322/1322**（基线 1309 + 本批新增 13） | `build/desktop-test` → `ninja game-core-tests && ctest` |
| Kotlin 主源编译 | ✅ 六模块全绿 | `:core:domain :core:engine :feature:game :app compileReleaseKotlin` |
| NDK arm64 | ✅ | `:app:externalNativeBuildRelease`（修复前 HEAD 即断） |
| Hilt DI 图 | ✅ | `:app:hiltJavaCompileRelease` |
| `RngSourceGuardTest`（R1/R3/R4/R5/R2） | ✅ 5 用例全绿 | `--tests com.xianxia.sect.core.architecture.RngSourceGuardTest` |
| 桌面 JNI 对拍桥 | ✅ 重建成功 | `pwsh -File scripts/build-desktop-jni.ps1` |

---

## 2. 已落地改动（按 ADR 阶段）

### 在途阻塞根治（非本批范围，用户确认一并做）
- `NativeBridge.cpp:1499-1500`：`ktx1::KtxInfo`/`ktx1::loadKtx1` → 去限定符。**根因**：`KtxLoader.h` 的 `namespace ktx1` 只包常量，`KtxInfo`/`loadKtx1` 声明在全局。
- Kotlin 崖壁纹理接线：`NativeSurfaceView` 实例化 `IslandCliffTextureHolder` + `hasAnyCliffTexture`/`cliffTextureCount`；`SectMapViewport.onRendererReady` 触发加载；`MainGameScreen` 传 `textureMask`。**`:feature:game` 恢复可编译**。

### 阶段 0
- 新增 `docs/rng-source-inventory.md`（五类入口逐处分类表 = 阶段 3 工作清单）。
- 新增 `RngSourceGuardTest`：**注释感知**（剔除 KDoc/注释，避免"改注释即改守卫"）+ 逐模块逐类登记上限（只缩不增）+ `inSnapshot` 集合一致性 + 分区 id/名双射。
- CI 红线 step：**未做**（见 §3）。

### 阶段 1①（开袋下沉 + 抽签归分区）
- 新增 `system/storage_bag_tx.h` + `storage_bag_tx_test.cpp`（13 用例全绿）：C++ 只消费 `EXPLORATION` 分区产出 `count + kind[]` 描述符序列，模板物化留 Kotlin（13.3 红线 + 模板库在 Kotlin 注册表）。
- 新增 `InventoryFacadeImplBagOps.kt`：抽签/物化拆两段，**7 处抽签全部显式传分区 rng**（原 6 处走默认实参回落 `Random.Default`）。
- 新增 `core:domain/util/RandomWithExt.kt`（`randomWith(rng)`）替代 `templates.random()`。
- `ActionId STORAGE_BAG_OPEN_TX = 1734`（`gen-action-ids.mjs` 单一事实源）。
- **顺手清偿**：`game_core.cpp` 分区上界守卫写死 `kSecretRealm(7)` ⇒ `MISSION(8)` 被整体拒绝（抽取恒 0/快照恒 0/恢复静默失败）→ 改 `RngManager::kMaxPartitionId` 权威常量。
- **顺手清偿（本批自伤）**：`handleInventoryTx` 范围判据从 `<= INV_CONFISCATE_BAG_ITEM(1531)` 被误扩为 `<= STORAGE_BAG_OPEN_TX(1734)`，吞掉 1730–1733 ⇒ 23 个桌面测试红。**根治**：拆成两段独立判据（1734 单独判）+ 注释写明事故根因。

### 阶段 1②（AI RNG 归一 + 通道分区）
- `RngPartition` 新增 `AI_SECT_MIRROR(9, inSnapshot = false)`：**通道型分区**（只是取用 C++ `aiRng_` 的句柄，状态归宿主侧保管）。
- `rng_manager.h`：`kAiSectMirror` + `kMaxPartitionId` + 播种种 9 + `exportStates/restoreStates` 跳过 9。
- `game_core.cpp`：`rngNextInt/rngSnapshotPartition/rngRestorePartition` 对 9 路由到 `aiRng_`；`mirrorAiRng()`；`syncRngStates` 导出前镜像并**擦除退役键 6**；`rngInitSystemSeed` 同步播种 `aiRng_`；`importStateInternal` 按 9 号键续接归档态（0 值显式排除：PCG 混种后数学上不可能为 0）。
- `AISectDiscipleManager`：删自持 `_rng` 影子 + `initForSlot` 兜底重建；`rng` 改**解析式**（注入管理器 → 委托模式取分区 9 / 非委托取分区 6；无管理器回落 `fallbackRng`）；`initForSlot(seed)` 播种兜底流并 `restore` 分区 6（保住"同 seed ⇒ 同序列"契约）；新增 `resetManagerForTest`（进程级 object 的测试隔离）。
- `GameRngManager.exportStates/restoreStates` 只遍历 `inSnapshot = true`。

### 阶段 1③（`GameRandom` 摘除）
- **删除** `GameRandom.kt` + `GameRandomTest.kt` + 两处死代码（`Disciple.fixBaseStats` 7 抽、`SpiritRootGenerator.generateWithGameRandom`）——残留调用变编译期报错。
- 新增 `core:engine/util/EngineEntropy.kt`：`mapSeed` 改**显式会话熵**（`SecureRandom xor nanoTime`）。**理由**：`mapSeed` 是 `systemSeed` 上游，走分区构成循环依赖；新档/重启语义本就要求"新世界"。**这是唯一偏离 ADR 字面建议处，已登记。**
- `GameConfig.generateRandomSpiritRootCount()` → 纯函数 `rollSpiritRootCount(rand)`；`SpiritRootGenerator.rollSpiritRootCount` 收敛到它（单一权重表）。
- `HeavenlyTrialComponents` 天劫立绘抽取 → `PresentationRandom`（清偿"UI 层调随机"架构违规）。
- 测试同步：`SpiritRootGeneratorTest`（删 4 个死路径用例、分布用例改纯函数）、`SpiritRootConfigTest`（改测纯函数，改等差铺满采样消除 flaky）。

### 阶段 2（部分）
- 新增 `core:engine/util/PresentationRandom.kt`（`@Singleton`；`pick`/`pickOrNull`/`nextInt`/`nextDouble`/`nextBoolean`/`boundPicker`；不落盘）。
- 已迁入：`HeavenlyTrialComponents`、`DiplomacyGiftTexts`（4 处）、`DiplomacyVassalTexts`（5 处）。
- 接线：`HeavenlyTrialViewModel` / `WorldMapInteractionViewModel` 注入 `presentationRandom` 并透传；`DiplomacyFlows` 8 处调用点补齐实参；`SectDiplomacyDialogTest` 同步。

### 阶段 4（部分）
- ✅ `R2`（`inSnapshot` 集合一致性）、`R4`（分区登记/双射/通道语义）断言。
- ❌ CI 红线 step、10k 抽取 JNI 基准：**未做**。

---

## 3. 未完成清单（诚实口径）

### 3.1 引擎全量单测 **4 处失败**（3255 用例）
全部集中在 **AI RNG 流归一域**，均为确定性失败：

| 测试 | 症状 |
|---|---|
| `AISectDiscipleManagerTest > processMonthlyCultivation - 突破失败HP和MP打一折` | expected 12 / actual 120（未走突破失败分支） |
| `AISectDiscipleManagerTest > processMonthlyCultivation - 装备孕养经验满升级且进度扣减` | expected 1 / actual 0 |
| `DiffAuthoritativeTickTest > authoritative pipeline ... over 100 phases` | 第 9 旬 `$.gameData.availableMissions` expected 1 / actual 4 |
| `DiffYearSettlementTest > ai sect yearly recruitment matches Kotlin bit-for-bit` | 弟子条数 expected 3 / actual 4 |

**已排除/已定位的事实**（供接手者省时）：
- 失败**不是**"注入了管理器但漏注入 RNG"——`getRng` 返回 null 类 NPE 已全部清偿（`AISectDiscipleManagerTest`/`GameEngineCoordinationTest`/`AISectBattleProcessorTest`/`CaveExplorationProcessorTest` 由红转绿）。
- 已修的一处**真实顺序缺陷**：`initForSlot(seed)` 必须在 `restoreStates(...)` **之后**调用，否则快照里的分区键会抹掉播种（实测：分区停在快照值 `188022` 而非 `0 + 6×31337` 的播种态）。
- 已修的一处**协议伪分歧**：C++ `syncRngStates` 继续导出退役键 6，而 Kotlin 侧 6 号停在播种态 ⇒ 对拍读出一条两侧序列本就不同的键。已改为 C++ 不再导出 6。
- **仍未解决的可疑点**：`DeterministicRng.fromSeed(188022L).snapshot()` 实测返回 **-5182850315112888150**（即**混种前**的 `(seed shl 1) or 1` 值），而 `AISectDiscipleManager.initForSlot` 写入分区后读回的是 **188022**。两者对"同一函数的返回语义"口径不一致，疑点指向 `fromSeed` 内 `rng.nextLong()` 的语义（`nextLong(bound = Long.MAX_VALUE)` 是有界重载，非无参 advance）。**接手者应先把这个语义钉死**（一条最小单测即可），再据此校正两处 diff 断言的期望值——这很可能就是剩余 4 处失败的共同根因。

### 3.2 `feature:game` 单测 **10 处失败**（868 用例）——**两族均为预存，非本批引入**
- `SectCameraStateTest` ×5（`clampPosition` 岛边缘带居中 / `minScaleBound` 极限缩放居中）——断言期望"岛中心屏幕居中"（`expected 540.0 but was 844.453`），属**在途渲染/相机批次**。**裁定依据**：本批未触碰 `SectCameraState` 主源，也未触碰该测试文件（`git status` 空）。
- `GameViewModelTest` ×5（`setAutoLearnSettings` / `setAutoEquipSettings` / `setBreakthroughAutoPillSettings` / `setDaoCompanionConsentRequired` / `setDaoCompanionBannedRootCounts`）——症状统一为 `lambdaSlot.captured` 抛 `IllegalStateException: Value not yet captured`，即 `gameEngine.updateGameData` 未被调用。**裁定依据**：① 该测试文件本批零触碰；② 使 UI 走 native 臂的 `updateSettingsNative` + `ActionIds.SETTINGS_PATCH_TX` 在 **HEAD 中已存在**（batch-23 设置项域下沉引入），并非本批改动；③ 模拟环境下 `NativeEngineFlag.authoritative` 为真 ⇒ 入口走 native 臂而不落回 `updateGameData` 回退臂，测试的 `coEvery { gameEngine.updateGameData(...) }` 捕获点未被触达。⇒ **归属 batch-23 遗留（设置项域测试夹具未适配 native 臂）**，建议在下一批随设置项域一并修夹具。

> 上述两族**不计入本批债务**，但会让 `:feature:game:testReleaseUnitTest` 保持红：接手者若需整模块绿，需先修这两族（均与本批改动无因果）。

### 3.3 阶段 0/2/4 未完成项
- 阶段 0 CI 红线 step（`.github/workflows/ci.yml`）：未写。
- 阶段 2 未迁入：`SectResponseTexts`（2）、`DiscipleChatDialog`（3，**决策类**——写弟子 skills/cultivation，应走决策源而非表现流）、`LoadingTips`（1）、`CloudLayerAnimator`（1）、`BattleDescriptionGenerator`（12，文本持久化）。
- 阶段 4：阶段 3 前置 10k JNI 基准未做。
- 文档回写（handover §2.x/§3/§4.1、ADR 勘误、ui-read-surface §4.3、cpp-engine、CODE_WIKI）+ 两个更新日志：未做。
- `docs/rng-source-inventory.md` 的计数需按"注释剔除"口径刷新（现文中的 §2 表是剔除前口径；`RngSourceGuardTest` 用的是剔除后口径，二者不一致）。

---

## 4. 提交状态

**未提交**（按用户公约第 14 条：任务全部完成、清理一次性代码后一次性提交）。
工作区含 58 个已改文件 + 9 个新增文件；临时诊断代码已全部清理（`ZzProbe.kt` 已删、`AISECT_PROBE` 已删、`Diff*` 断言已还原为原始形态）。
