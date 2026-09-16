# 随机源治理 ADR 实施状态（**已收口存档** · 2026-09-14）

> **状态：阶段 0/1/2/4 已交付**（收口批见 [cpp-migration-handover-m0.md](cpp-migration-handover-m0.md) **§2.58**，权威记录以该节为准）。
> 本文件保留为**根因考古存档**：它记录了收口前的失败清单与已排除事实——这些事实在
> 后续专项定位 `DiffYearSettlementTest`（本文件 §3.1 的同一例）时仍可复用，**勿删**。
>
> **收口结论（一句话）**：§3.1 的 4 处失败中 **3 处已根因修复**（`AISectDiscipleManagerTest` 2 例 +
> `DiffAuthoritativeTickTest` 1 例），**1 例未收敛**（`DiffYearSettlementTest`，分歧窗口已再收窄）；
> §3.2 的 `:feature:game` 10 处**未清偿**（根因已定位，需 testFixtures 基建）；
> §3.3 的阶段 0/2/4 未完成项**已全部完成**。
>
> **2026-09-17 追记（W4-D/D6）**：上文两处"未收敛/未清偿"**均早已清偿**——`DiffYearSettlementTest`
> 见 handover **§2.59.1**（夹具快照 9 号键垃圾值，测试侧修复）；`:feature:game` 两族见 **§2.59.2**。
> **阶段 3 弟子侧/战斗侧已交付**（§2.62.1 W4-A/A5 `CHAT` 分区 / §2.64.1 W4-C/C7 散列确定性选词），
> 余量与守卫收口（② 上限 13→6、白名单 4→1 + 计数断言）见 [rng-source-inventory §8](rng-source-inventory.md)。

| 项 | 内容 |
|---|---|
| 依据 | [ADR rng-determinism-remediation.md](adr/rng-determinism-remediation.md)（阶段 0+1+2+4） |
| 已确认范围 | 阶段 0 + 1 + 2 + 4（阶段 3 另立分批）+ 在途阻塞根治（用户 2026-09 拍板） |
| 当前状态 | **阶段 0/1/2/4 已交付**；余 1 例未收敛 + `:feature:game` 两族夹具欠账（见 §3） |

---

## 0. 收口批（2026-09-14，§2.58）交付摘要

| 项 | 结果 |
|---|---|
| 🔴 根因修复一 | `AISectDiscipleManager.initForSlot` 写**裸种子**而非 `fromSeed` **混种态** ⇒ 同一 `aiSeed` 两侧两条序列。修后：`AISectDiscipleManagerTest` 34/34 绿、`DiffYearSettlementTest` 条数断言转绿。新增 `DiffAiRngSeedingTest`（3 用例）锁守 |
| 🔴 根因修复二 | `MissionSystem` 是**进程级 object** + 可变 `rngManager` ⇒ 双引擎夹具互相覆写（侧 B 月变消费 C++ 的 MISSION 分区）。修法 = **形参必传**（摘除 object 级状态）。`DiffAuthoritativeTickTest` 100 旬全量对拍绿。新增 `RngEngineIsolationGuardTest` 防复发（首跑抓出 3 处同族遗留） |
| 阶段 0 | CI 红线 step 落 `ci.yml`（**守卫测试而非 grep**，理由成文） |
| 阶段 2 | `SectResponseTexts` / `LoadingTips` / `CloudLayerAnimator` 收口；`BattleDescriptionGenerator` + `DiscipleChatDialog` **改判决策类**归阶段 3 |
| 阶段 4 | 10k JNI 基准：`kotlin 14ns/op` vs `native 11ns/op`（**ratio 0.8 ⇒ 无成本障碍**） |
| 引擎全量 | **4 失败 → 1 失败**（3260 用例） |

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
| `DiffAiRngSeedingTest`（收口批新增） | ✅ 3 用例全绿 | 播种态混种 × 3 档 seed / 前 8 抽跨语言逐位 / 同 seed 幂等 |
| `RngEngineIsolationGuardTest`（收口批新增） | ✅ 1 用例全绿 | 禁止 object 持有可变 `GameRngManager`（白名单只缩不增） |
| 引擎全量 | ⚠️ **3260 用例 / 1 失败** | 余 `DiffYearSettlementTest`（见 §3.1） |


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

### 3.1 引擎全量单测 **1 处失败**（3260 用例；收口批前为 4 处）
全部集中在 **AI RNG 流归一域**，均为确定性失败；**收口批已修复 3 处、余 1 处**：

| 测试 | 状态 |
|---|---|
| `AISectDiscipleManagerTest > processMonthlyCultivation - 突破失败HP和MP打一折` | ✅ **已修复**（根因 = `initForSlot` 写裸种子非混种态） |
| `AISectDiscipleManagerTest > processMonthlyCultivation - 装备孕养经验满升级且进度扣减` | ✅ **已修复**（同上） |
| `DiffAuthoritativeTickTest > authoritative pipeline ... over 100 phases` | ✅ **已修复**（根因 = `MissionSystem` 进程级 object 的可变 `rngManager` 被双引擎夹具互相覆写） |
| `DiffYearSettlementTest > ai sect yearly recruitment matches Kotlin bit-for-bit` | ⚠️ **未收敛**——见下 |

**`DiffYearSettlementTest` 的分歧窗口（收口批实测再收窄）**：
- ✅ **已排除**：AI 分区**播种态**（`initForSlot` 修后 `aiSeed=188022` 双侧 `snapshot` 完全相同、前 8 次抽取逐位一致）；
- ✅ **已排除**：Kotlin 侧 `AISectDiscipleManager.generateYearlyRecruits` 直调产出与测试 `expected` **完全一致**（风阵玄/令狐子涵/百里符玄）⇒ 分歧**不在 AI 招募生成链本身**；
- 🔍 **已收窄到**：**"第二名 AI 弟子的装备/功法段"**——依据：前两名弟子的 7 项方差（`hpVariance`…`speedVariance`）与 9 项技能逐位一致 ⇒ 生成序一致；至第三名弟子的**灵根数**已不同（4 vs 3）= 漂移表现而非原因；
- 📌 **实测提示**：`ManualDatabase` 是否初始化会**显著改变**该链的消费序列（实测同一 seed 下产出从 `风阵玄/令子涵/百里符玄` 变为 `风阵玄/紫子涵/寒芷若`）⇒ 夹具与 C++ 侧静态数据面的一致性需一并核对；
- 📌 **下一步建议**：在 `advanceKotlinSide` 与 C++ 侧各插一次 AI 分区快照差分（探针位置已在收口批验证可行——`gameRng.getRng(AI_SECT).snapshot()` vs `nativeCoreRngSnapshotPartition(9)`）。

### 3.2 `feature:game` 单测 **10 处失败**（868 用例）——**两族均为预存，收口批未清偿**
- `SectCameraStateTest` ×5（`clampPosition` 岛边缘带居中 / `minScaleBound` 极限缩放居中）——断言期望"岛中心屏幕居中"，属**相机契约**问题。**须先做"期望值来源考古"**判定是实现漂移还是测试漂移（禁止直接把期望改成实现值，否则把缺陷固化为契约）。
- `GameViewModelTest` ×5（`setAutoLearnSettings` / `setAutoEquipSettings` / `setBreakthroughAutoPillSettings` / `setDaoCompanionConsentRequired` / `setDaoCompanionBannedRootCounts`）——症状统一为 `lambdaSlot.captured` 抛 `IllegalStateException: Value not yet captured`。**根因（收口批定位）**：batch-23 后设置项写路径为 `updateSettingsOrFallback`（native 成功即完成 / 失败降级 `updateGameDataSync`），测试断言的 `gameEngine.updateGameData` 捕获点在**回退臂**；而 `updateGameDataSync` 的块是 `gameEngineCore.launchInScope { stateStore.update { … } }`，夹具的 `gameEngine.stateStore` 为空引用（`stateStore` 在 `core:engine` 为 **`internal`**，feature 模块测试**不可 stub**）⇒ 捕获块永不落位。**修法** = 给 feature 测试模块提供可注入的 `GameStateStore` 夹具（`core:engine` testFixtures 依赖或上提 Fake）——属测试基建改动，收口批未硬凑。

### 3.3 阶段 0/2/4 未完成项 → **收口批已全部完成**
- ✅ 阶段 0 CI 红线 step（`.github/workflows/ci.yml`）——以 `RngSourceGuardTest` + `RngEngineIsolationGuardTest` 为闸门，step 注释写明为何不用 grep。
- ✅ 阶段 2 迁移（`SectResponseTexts` / `LoadingTips` / `CloudLayerAnimator`）；`BattleDescriptionGenerator` + `DiscipleChatDialog` **改判决策类**归阶段 3。
- ✅ 阶段 4 10k JNI 基准（ratio 0.8，无成本障碍）。
- ✅ 文档回写（handover §2.58/§3/§4.1/§6、ADR §0 状态快照 + 勘误二 + §4 状态表、本文件、`docs/rng-source-inventory.md` §2 计数）+ 两个更新日志。
- ✅ `docs/rng-source-inventory.md` 计数已按"注释剔除"口径刷新（与本文件原 §2 表口径一致）。


---

## 4. 提交状态

**已提交**（收口批一次性提交，见 handover §2.58）：`AISectDiscipleManager` 混种态修复 +
`MissionSystem` 全局解除 + 两道新守卫 + 阶段 0/2/4 收口 + 全部文档/更新日志。
临时诊断代码（`ZzRngDiag*` 探针、调试 `println`）**已全部清理**。

