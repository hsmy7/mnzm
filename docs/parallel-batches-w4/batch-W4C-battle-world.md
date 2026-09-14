# W4-C 批次方案：战斗与世界协议轴（战斗/秘境收尾 + WS-5b 地图冻结 + 随机源决策类下沉）

> ## 🚧 本批是**并行方案**——实施人员开工前必读
>
> **本批与 [W4-A](batch-W4A-disciple-building.md)、[W4-B](batch-W4B-court-economy.md) 是同一波次的三个并行批次，三者会同时在各自的工作树上施工。**
> 并行度是设计出来的，不是"注意避让"出来的——请先读 [README](README.md) §2（拆分依据）与 §4（协作协议），再开工。
>
> | 你必须知道的 | 内容 |
> |---|---|
> | **你的工作区（独占）** | `C:\Mnzm\XianxiaSectNative-w4c`，分支 `w4/c-battle-world`（由 `scripts/w4/setup-worktrees.ps1` 建立）。**不要**在主仓 `C:\Mnzm\XianxiaSectNative` 或另外两个工作树里改代码 |
> | **硬前置** | **W4-00（并行前置批）必须已合入 `main`**。判据：`scripts/action-catalog/w4c.mjs`、`src/dispatch_w4c.cpp`、`test/w4c_tests.cmake`、`reversechannel/W4CChannelClosures.kt` 四者均已存在，且 `git tag` 有 `w4-base` |
> | **你的独占文件** | `scripts/action-catalog/w4c.mjs`｜`src/dispatch_w4c.cpp`｜`test/w4c_tests.cmake`｜`reversechannel/W4CChannelClosures.kt`｜**协议面（租约第一顺位）**：`state/models.h`、`src/json_codec.cpp`、`game_core.h/.cpp`、`core/domain/.../model/GameData.kt`、`core/data/.../local/GameDatabase.kt` + `MIGRATION_50_51` + schema JSON｜`map/terrain.h`｜`SectTerrainBridge.kt`｜`MapPreloadData.kt`｜`GameEngineSaveOps.kt`｜`domain/battle/*` 四文件｜`GameEngineBattleOps/SecretRealm*` |
> | **🔴 绝对禁改**（另两批的面向对象，或全局共享面） | `scripts/action-catalog/w4a.mjs`、`w4b.mjs`｜`src/dispatch_w4a.cpp`、`dispatch_w4b.cpp`｜`test/w4a_tests.cmake`、`w4b_tests.cmake`｜`reversechannel/W4A*`、`W4B*`｜`scripts/gen-action-ids.mjs`｜`action_ids.h`、`ActionIds.kt`｜`src/execute_dispatch.cpp`｜`src/CMakeLists.txt`、`test/CMakeLists.txt`｜`ReverseChannelPolicy.kt`｜`GameStateStoreImpl.kt`｜`StateSyncService.kt`｜`GameViewModel.kt`｜`GameEngineCore.kt`｜**宿主文件族**（`CultivationEventMonthlyOps.kt`/`MonthSettlementResidualExecutor.kt`/`YearSettlementResidualExecutor.kt`/`GameEngineCoreMonthOps.kt`/`GameEngineCoreYearOps.kt`）｜**`SaveFacadeImpl.kt`（归 W4-B，本批禁改——见 §2.3.3 R6）**｜`RngSourceGuardTest.kt`（归 W4-A）｜`RngEngineIsolationGuardTest.kt`（归 W4-D）｜`CHANGELOG.md`、`changelog_entries.json`、handover §3/§4.1/§5/§6、`ui-read-surface.md` §4.4（收口人独占） |
> | **重活必须先取构建令牌** | `pwsh -File scripts/w4/build-token.ps1 -Acquire -Batch w4c` → 跑 Gradle 全量测试/detekt/NDK/lint/ctest 构建 → `-Release`。**不取令牌并跑 = 会让另两批的构建随机失败**（Gradle `classes.jar` 锁 / KSP 缓存损坏 / ctest 假失败） |
> | **提交纪律** | `git commit -- <本批触碰文件清单>`，**禁止整仓 `git add`**（历史事故：混入 433 个并行改动文件）；每个子批提交后打 `w4c/<nn>` tag + `git bundle`（`.git` 曾两次被毁） |
> | **🔴 本批是唯一改存档格式的批** | WS-5b 触碰 Room `@Database` 50→51 + 新增 ProtoBuf 字段 ⇒ **Migration 集成测试是硬门禁**；改 `@Entity` 前先读 `rules/database-migration.md`；禁止 `ALTER TABLE DROP COLUMN`。**`GameEngine.kt` 的 `:170-172` 需向 W4-A 申请租约** |
> | **合并** | 由收口人按 W4-A → W4-B → W4-C → W4-D 顺序合并；**冲突禁取 theirs**；生成物冲突一律"重生成" |
>
> **判据（机器可执行）**：`git diff --name-only w4-base..w4/c-battle-world` 中若出现上表"绝对禁改"里的任一文件 ⇒ **本批打回**。

| 项 | 内容 |
|---|---|
| 批次代号 | **W4-C**（worktree `C:\Mnzm\XianxiaSectNative-w4c`，分支 `w4/c-battle-world`） |
| 覆盖的 w3 子批 | **w3-06 → w3-07**（串行）｜**w3-06 → w3-08**（串行） |
| 支线工作项 | **WS-5b 地图冻结批（已拍板完整业界方案）**｜RNG 阶段 3·战斗侧（`BattleDescriptionGenerator`）｜三处顶层可变 `xxxRngManager` 收敛｜死代码：`InventorySystem.materializeDiscipleBagAndMarkDead` 同签名遮蔽 |
| ActionId 段 | **1780–1789**（w3-06）｜**1790–1799**（w3-07）｜**1800–1809**（w3-08）｜**1855–1859**（RNG 阶段 3·战斗侧，**条件段**）｜WS-5b：**无**（纯协议/生命周期，已实测确认） |
| 决策分级 | **架构级重构**（WS-5b 触及存档协议 + Room Migration + 跨语言状态模型；战斗族触及 RNG 边界） |
| 硬前置 | **W4-00 已完成并合入 `main`** |
| 只读（禁止触碰） | `GameStateStoreImpl.kt`｜`StateSyncService.kt`｜`GameViewModel.kt`｜`GameEngineCore.kt`｜`src/execute_dispatch.cpp`｜`test/CMakeLists.txt`｜`scripts/gen-action-ids.mjs`｜`ReverseChannelPolicy.kt`｜`RngSourceGuardTest.kt`（归 W4-A）｜`RngEngineIsolationGuardTest.kt`（归 W4-D）｜**宿主文件族**（`CultivationEventMonthlyOps.kt` / `MonthSettlementResidualExecutor.kt` / `YearSettlementResidualExecutor.kt` / `GameEngineCoreMonthOps.kt` / `GameEngineCoreYearOps.kt`，README §2.3）｜**`SaveFacadeImpl.kt`（归 W4-B，本批禁改——见 §2.3.3 R6）** |
| 独占协议面 | `state/models.h`｜`src/json_codec.cpp`｜`game_core.h`｜`game_core.cpp`｜`core/domain/.../model/GameData.kt`｜`core/data/.../local/GameDatabase.kt`（+ schema / Migration）——按 README §5.4 租约，**本批为第一顺位持有者** |
| handover 章节 | **§2.64**（只在本小节内追加） |

---

## 1. 背景与目标

### 1.1 需求要点

本批是三批中**唯一同时触碰"行为面"与"协议面"**的批次，由三块互不干扰的工作组成：

| 块 | 内容 | 性质 |
|---|---|---|
| **C-①** | w3-06 / w3-07 / w3-08：战斗、探索、秘境三域的残余稳态写者搬迁 | 行为面（RNG 红线密集） |
| **C-②** | **WS-5b 地图冻结批**：生成即数据 + 协议全链 + RLE 仅存储编码 + 老档再生回填 + `SectTerrainBridge` 读权威态 | 协议面（存档格式 + Migration） |
| **C-③** | RNG 阶段 3·战斗侧 + 三处顶层可变 `xxxRngManager` 收敛 | 确定性面（随机源治理） |

三块**文件零交集**（C-① 在 `GameEngineBattleOps/SecretRealmOps/CombatService`；C-② 在 `models.h`/`json_codec`/`GameData.kt`/`terrain.h`/`SectTerrainBridge`；C-③ 在 `domain/battle/BattleDescriptionGenerator`/`EnemyGenerator`/`AISectAttackManager`/`AISectTeamComposer`），批内可交错推进。

> **⚠️ 本批最关键的一手核实结论（推翻了上游文档的隐含假设，必须写入实施前提）**
>
> handover §2.60.2 把 WS-5b 描述为"`models.h` 增地形段 + 协议全链"，读起来像**纯 C++ 协议改动**。**实测不是**：
>
> 1. **存档确实是 Kotlin kotlinx ProtoBuf**（本地 = Room `game_data` 行 + `game_heavy_data` 分块侧车；云 = `SaveData` 的 ProtoBuf 字节）——证据链：`SaveLoadViewModelSaveOps.kt:235→246→249`、`GameEngine.kt:208-213 buildSaveSnapshot`、**`SaveFacadeImpl.kt:94-117 getStateSnapshot()` 只读 `stateStore.*Snapshot` + `gameRngManager.exportStates()`，全链无 `nativeExportState()` 调用**、`UnifiedSerializationEngine.kt:64 protoBuf.encodeToByteArray(serializer, data)`；C++ `exportStateJson` 的消费者只有 `StateSyncService` 与 47 个 `Diff*Test`。
> 2. ⇒ **地形要进存档，Kotlin `GameData` 必须新增 `@ProtoNumber` + `@ColumnInfo` 字段，并递增 `@Database(version)` 50→51 写 `MIGRATION_50_51` + 更新 schema JSON**；否则 `mapGenVersion` 与地形段根本进不了 `SaveData`。
> 3. **`@Transient` + `GameHeavyData` 侧车路线不可行**：`@Transient` 不进 `SaveData`，而云上传链 `SaveLoadViewModelCloudOps.kt:65-73` **没有** heavy→cloud 补偿步骤 ⇒ **云存档会丢地形**（`aiSectDisciples` 走的正是这条，是既有不对称）。
> 4. **C++ 侧也不能省**：`DiffSurfaceAssertion.kt:45-50` 断言"C++ 导出而 Kotlin 缺失的键 = 协议漂移即红"，且 `StateSyncService.applySnapshot` 按 `exportedKeys` 白名单合并 ⇒ 地形段必须**两端同存**。
> 5. **`rules/database-migration.md`（128 行）需同步扩充**：现全文无 C++ 侧/协议版本戳迁移章节。

### 1.2 成功标准（可验收）

| # | 标准 | 度量 |
|---|---|---|
| 1 | 三个域的 `STEADY_KOTLIN` 写者归零 | 批内穷尽扫描表无 `STEADY_KOTLIN` 残留；`W4CChannelClosures.kt` 覆盖本批单元；**关闭域写入检测零命中** |
| 2 | 行为零变更（C-①/C-③） | 各域 GateTest 逐位一致 + 47 个 `Diff*` 全绿 + 桌面 C++ 全量绿 + `:core:engine` 全量绿 |
| 3 | **地形存读往返逐位一致**（C-②） | 存档 → 读档 → C++ 导出地形段**逐位相同**；RLE 编解码 roundtrip 相同；老档无段 ⇒ 按种子生成并回填 ⇒ 再存再读仍一致 |
| 4 | **跨版本冻结成立**（C-②） | 人为改动生成器版本后：带地形段的旧档**地图不变**；无段档按当前版本生成（老档老地图、新档新地图） |
| 5 | 存档兼容（C-②） | `@Database` 50→51 的 `MIGRATION_50_51` 有集成测试（旧档种子数据 → 迁移 → 数据完整）；旧版本 App 读新档、新版本 App 读旧档行为明确 |
| 6 | 云存档不丢地形（C-②） | 云上传/下载往返后地形段存在且逐位一致（**不得**依赖 heavy_data 侧车） |
| 7 | 随机源治理（C-③） | `BattleDescriptionGenerator` 不再消费非分区随机源；三处 `xxxRngManager` 改形参必传；`RngEngineIsolationGuardTest` 白名单**只缩不增** |
| 8 | 协议零漂移 | `node scripts/gen-action-ids.mjs && git diff --exit-code` 空；`DiffSurfaceAssertion` 全绿（新增地形键两端同步登记） |

---

## 2. 技术方案

### 2.1 交付节奏（批内三块可交错，块内串行）

```
C-① 战斗族（行为面，RNG 红线密集）
  C1  w3-06 战斗/探索残差      （1780–1789）
  C2  w3-07 宗门战战后段        （1790–1799）← C1
  C3  w3-08 秘境残差            （1800–1809）← C1；顺手修 materializeDiscipleBagAndMarkDead 遮蔽

C-② WS-5b 地图冻结（协议面，独占 models.h/json_codec/GameData.kt/GameDatabase.kt）
  C4  协议面落地（Kotlin + C++ 双侧字段 + Migration + 生成/回填入口）
  C5  RLE 存储编码 + SectTerrainBridge 读权威态 + 注释口径改写
  C6  测试面（存读往返 / RLE roundtrip / 老档回填 / 跨版本冻结 / 云档）

C-③ 随机源（确定性面）
  C7  BattleDescriptionGenerator（1855–1859，条件段）
  C8  三处顶层可变 xxxRngManager 收敛
```

### 2.2 C-① 战斗/探索/秘境：三子批技术要点

#### C1 · w3-06 战斗/探索残差（ActionId 1780–1789）

| 写者（**逐行实测**） | 分类 | 处置 |
|---|---|---|
| `CombatService.kt:78`（native 战斗后伤亡残差） | ① | 伤亡写回移入 C++ 战斗事务；**死亡标记路径必须调用 `DiscipleDeathHandler.markDead` / `handleDiscipleDeath`**（CLAUDE.md 13.3 红线） |
| `GameEngineWorldBattleOps.kt:188`（∈`applyWorldLevelVictoryTransaction`@183） | ① | 关卡胜利事务的状态写下沉——**注意既有 TOCTOU 教训**：C++ 若顺手写 `defeated`，Kotlin 的重查臂会早退**漏发魂力** ⇒ C++ **明确不写** `defeated`（batch-13 既有口径，[findings](../../findings.md) 已登记） |
| `GameEngineWorldBattleOps.kt:288`（∈`applyWorldLevelDefeat`@281） | ① | 失败事务同批下沉 |
| `GameEngineExplorationNativeOps.kt:134`（native 转发前的战前结算，**无条件执行**） | ① | 改门控 + 状态下沉 |
| 战利品生成族（`generateWarRewards` + 六类 `addWar*`） | ③/红线 | **不下沉**：模板抽取 `templates.random(random)` 六个调用点（`AISectTeamComposer.kt:143/158/171/182/194/208`）**均未传 `random`** ⇒ 实际序列为 `kotlin.random.Random.Default`（非分区），C++ 无法逐位复刻；且 `sectBattleRewardCount` 的 BATTLE 分区 `nextInt(7)` 夹在该非分区域中间，拆分即"半吊子混合态" ⇒ **整族留 Kotlin**（batch-20b 既有登记，禁止近似复刻） |
| `battleLogs` | ③ | **Kotlin 显示域，不入 C++ 状态**；`recordSectBattleRecord` 与它同一事务 ⇒ 拆出会产生**撕裂事务**，不下沉（batch-20b 登记） |

**地基复用**：`exploration_tx.h`（EXPLORE_TX_* 1570–1573）；⚠️ `battle.h` / `sect_battle.h` 是**纯逻辑非 tx 头** ⇒ 战斗事务需新建 `battle_residual_tx.h`。

#### C2 · w3-07 宗门战战后段（ActionId 1790–1799）

| 写者（**4/4 精确**） | 分类 | 处置 |
|---|---|---|
| `GameEngineBattleOps.kt:66`（∈`attackSect`@53，`forceSettleDisciplesBeforeBattle`） | ① | **本批范围明确化项**（§2.51b 未显式登记）⇒ 战前结算状态下沉 |
| `GameEngineBattleOps.kt:274`（`recordSectBattleRecord`@273） | ③ | **不下沉**：与 `battleLogs`（Kotlin 显示域）同一事务 ⇒ 撕裂事务 |
| `GameEngineBattleOps.kt:339`（∈`occupySectRewards`@307） | ①/红线 | **不下沉**：与 `grantWarRewardsInside` 同一 `stateStore.update` 原子事务（占领标记/俘虏/`vassalContracts` 清理/`sectDetails.isOwned`），奖励段不可复刻 ⇒ 原子性不可拆 |
| `GameEngineBattleOps.kt:366`（∈`crushSectRewards`@365） | ①/红线 | 同上 |

**地基复用**：`sect_attack_tx.h`（1711 阵亡守军清理 / 1712 魂魄发放，batch-20b 已交付）。
**登记纪律**：②③ 类"不下沉"必须**逐条带证据成文**（`findings.md` 的"非分区随机域不可逐位复刻，是能否下沉的红线判据"）。

#### C3 · w3-08 秘境残差（ActionId 1800–1809）

| 写者 | 分类 | 处置 |
|---|---|---|
| `GameEngineSecretRealmOps.kt:57`（∈`startSecretRealmExploration`@44，出发换岗） | ① | 换岗/清理状态下沉。> ⚠️ **引用勘误**：w3 README 记的 `SecretRealmNativeOps.kt` **文件不存在** ⇒ 真值 `GameEngineSecretRealmNativeOps.kt`（`:100` 到期兜底 / `:263` 战报写回，均已实测命中） |
| `GameEngineSecretRealmNativeOps.kt:100`（`rejectIfSecretRealmExpired`，无 native 门控） | ① | 到期关闭判定链下沉（`closedSecretRealmByExpiry` 状态段） |
| `GameEngineSecretRealmNativeOps.kt:263`（`recordSecretRealmBattleReport`） | ③ | 战报/显示通道**不入协议**（S6 决策③既有口径：`resultText` 为确定性字面量直出、`rounds.message` 不入 C++） |
| `InventorySystem.materializeDiscipleBagAndMarkDead` **成员/扩展同签名遮蔽** | — | **顺手根治**：同签名遮蔽会让"到底调了哪个"依赖解析顺序（静默行为分歧风险）⇒ 收敛为单一实现 |

**地基复用**：`secret_realm_platform_tx.h`（`continueSessionTx`@75，batch-20a）。

### 2.3 C-② · WS-5b 地图冻结批（**本批最大的工作块**）

#### 2.3.1 现状实测（一手证据）

| 项 | 事实 |
|---|---|
| C++ 地形生成 | `map/terrain.h`（234L）：`generateTileData(w,h,density,seed,borderRing,GateBox)` **L219–232**；`enum TileType` **L47–59**；`GateBox` **L63–69**；行主序 `index = row*w+col` |
| JNI 入口 | `GameCoreBridge.cpp` `nativeGenerateSectTerrain` **L1070–1096**（仅装配：填 GateBox → 调生成 → `NewIntArray/SetIntArrayRegion`）；声明 `GameCoreBridge.kt:463-474`（无状态纯函数 / `kAnyThread`） |
| **C++ 状态模型** | `state/models.h` GameData **L1258–1424**、GameState **L1435–1473**：`grep 'terrain\|flatTileData\|mapGenVersion'` ⇒ **0 命中**；仅有 `mapSeed` **L1348** 与 `saveVersion` **L1305** |
| 编解码 | `json_codec.cpp` `to_json(GameData)` **L1233–1316** / `from_json` **L1317–1399**（`GC_TO`/`GC_FROM` 宏）；`GameState` 段有**"非空才导出键"先例**（`aiSectDisciples` L1410-1412、`lockedBeastIds` L1422-1424），导入侧 `contains + !is_null` 宽松跳过 L1445-1458 ⇒ **地形段的现成协议位** |
| 生命周期入口 | `GameCore::initialize`（`game_core.cpp:213-280`）**只注入 `GameCoreConfig`（不含 mapSeed）**；**C++ 无 `newGame`**；`importStateInternal` **L491–548**（`state_ = j.get<GameState>()` L495；`aiRng_` 由 `mapSeed + 6×31337` 播种 L499-500；归一化族 `normalizeAICorpseEntries`/`normalizeLedgers` L533/536 **先于** `resetBaseline` L537）；`grep 'terrain'` in `game_core.cpp` ⇒ 0 命中 |
| Kotlin 通道 | `SectTerrainBridge.kt`（85L）**自身无状态纯函数零缓存**；缓存实际在调用方：`SectMapController.kt:51-54 getOrPut(seed)`、`BootSequenceController.kt:415-422`（boot 一次）；调用方全集仅 4 处 ⇒ 改动面收敛；降级红线（native 不可用 → Kotlin 位级等价生成器）在 KDoc L20-22 |
| `MapPreloadData.rawTileData` | `grep rawTileData` 全仓 **0 命中** ⇒ 已删除 ✓（与 handover §2.19 一致） |
| 测试现状 | `DiffSectTerrainTest.kt` 4 用例（仅锁生成器，**不涉协议/存档**）；`terrain_test.cpp` **19 个 TEST**（handover §2.19 记"16 用例"= 文档漂移） |

#### 2.3.2 设计决策（**生成即数据的落点重新定义**）

handover §2.60.2 写"新档 `initialize`/`newGame`（mapSeed 确定后）由 C++ 生成地形一次"。实测 `initialize` **拿不到 mapSeed**、C++ **无 `newGame`** ⇒ 若照字面实施需新增 C++ 生命周期入口。

**本方案的更优落点**：把"生成即数据"落在 **`importStateInternal` 的归一化族**（`game_core.cpp:491-548`，与 `normalizeAICorpseEntries`/`normalizeLedgers` 同位置族）：

```
importStateInternal(state_, restoreRng):
    state_ = j.get<GameState>()                     // L495
    …
    ensureTerrainGenerated(state_):                 // ← 新增（归一化族）
        if (state_.terrain.empty()):                //   无段 ⇒ 生成 + 落为权威数据 + 标脏
            state_.terrain       = generateTileData(mapSeed, …生产配置…)
            state_.mapGenVersion = kCurrentMapGenVersion
            dirtyTracker_.markDirty(terrain)        //   首次前向镜像把地形带到 Kotlin（一次）
        else:
            /* 有段 ⇒ 直接采用（跨版本冻结；不再重算） */
    resetBaseline()                                 // L537
```

**为什么更优**：① **新档与老档走同一条路径**（老档无段 ⇒ 生成 + 回填；新档无段 ⇒ 生成），消除"两条生成路径可能漂移"的风险；② 不需要新增 C++ 生命周期 API（`game_core.h` 只加数据段，不加方法）；③ 与既有"归一化先于 `resetBaseline`"惯例一致；④ `mapSeed` 一定已在快照里（它是 `SAVE_LOAD` 域的既有字段）。

**版本戳语义**：`mapGenVersion` 记录"产生该地形数据的生成器版本"；判定**存的地形恒优先**——只有"无地形段"才重算。Kotlin 侧不写判定逻辑（`saveVersion` 的先例是"判定全在 Kotlin"，但地形判定天然属于导入侧归一化）。

#### 2.3.3 落地清单（R1–R9，缺一不可）

| # | 面 | 项 | 关键要求 |
|---|---|---|---|
| **R1** | Kotlin 模型 | `core/domain/.../model/GameData.kt` 新增 **`mapGenVersion: Int`** + **地形 RLE 段**（`@ProtoPacked` 的 `List<Int>` 或等价的紧凑表示） | 必须 `@ProtoNumber` + `@ColumnInfo` + `@SettlementStrategy(PRESERVE_OLD)`（照抄**最近邻先例** `mapSeed` L758-762 / `saveVersion` L537-541）；非零默认值必须 `@EncodeDefault(ALWAYS)`（否则 `encodeDefaults=false` 下静默丢失）；编号取**预留段 1000+**（实测全仓 `@ProtoNumber(1\d{3}+)` 零命中 ⇒ 空闲；GameData 本体当前已用最高 224） |
| **R2** | Room | `GameDatabase.kt:74 DATABASE_VERSION` **50 → 51** + 新增 `MIGRATION_50_51`（`ALTER TABLE game_data ADD COLUMN … DEFAULT …`）+ 注册到 `build()` + 更新 schema JSON | 规则：**禁止 `ALTER TABLE DROP COLUMN`**；新列先例 `GameDatabaseMigrationsV2ToV10.kt:22-32`；**必须有集成测试**（旧档种子 → 迁移 → 数据完整） |
| **R3** | C++ 模型 | `state/models.h` GameData 增 `mapGenVersion` + 地形段（内存表示 = `std::vector<int32_t>` 行主序 flat）；`src/json_codec.cpp` 双向编解码（`GC_TO`/`GC_FROM`，插在 `saveVersion` 模式附近 L1254/L1337）；`GameState` 段采用**"非空才导出键"**先例 | 🔴 **§2.19 红线不破**：内存与协议**结构面**保持 flat 单一表示；RLE 仅是**存储编码层** |
| **R4** | C++ 生命周期 | `game_core.cpp` 新增 `ensureTerrainGenerated`（§2.3.2 落点）；`game_core.h` 只加数据段/常量（**不加新方法**更佳） | 生成配置（门楼常量等）**由 Kotlin `GameConfig.SectMap` 传值**（§2.19 既有口径：单一数据源不落 C++） |
| **R5** | Kotlin 接线 | `SectTerrainBridge.kt` 由"每会话生成 + 调用方缓存"改为**读权威态**（缺数据时才走生成路径并回填）；`SectMapController.kt:51-54` / `BootSequenceController.kt:415-422` 调用面同步 | 🔴 **保留降级臂**（native 不可用 → Kotlin 位级等价生成器，KDoc L20-22 契约不破） |
| **R6** | 存档链路 | `SaveFacadeImpl.getStateSnapshot()` **无需改动**——地形段是 `GameData` 字段，经 `stateStore.gameDataSnapshot` 自动携带 | ⚠️ **所有权边界**：`SaveFacadeImpl.kt`（122L）**归 W4-B**，本批**禁改**。故必须走"GameData 新字段"路线（通道 A）；若最终判定需在该文件落地形段 ⇒ **停下找收口人**（不得自行修改 W4-B 的文件） |
| **R7** | 反向通道分类 | 地形段与 `mapGenVersion` 在 `reversechannel/W4CChannelClosures.kt` 登记为 **CLOSED**（`LOAD_BOOT` 类写者 = 读档/新档/boot，随后由 `importToNative` 全量导入吸收，**不做反向传输**） | 理由：地形无 Kotlin 稳态写者；且 `ReverseChannelPolicy` 本体将在 W4-D/w3-13 被删除 ⇒ **不得依赖反向回导承载地形**。`ReverseChannelPolicyGuardTest`（穷尽分类）必须绿 |
| **R8** | 注释口径改写 | `terrain.h:31-35`（现记"地形**不入存档/镜像 JSON 协议**……生成器版本演进时旧种子地图随之变化"）与 `MapPreloadData.kt:22-23` KDoc（"仅内存传递不序列化"）**与 §2.60.2 决策直接冲突，必须同批改写** | CLAUDE.md 注释一致性红线：注释只描述**最终状态**，不得保留旧口径 |
| **R9** | 规则文档 | `rules/database-migration.md`（128L）**需扩充**：协议版本戳迁移判定口径 + C++ 侧字段与 Room/Kotlin 字段的同步清单 | 现全文无 C++/协议版本戳章节 |
| **R10** | 红线登记 | `docs/architecture/tile-rendering-next-gen.md`（模块 4 的 **Autotile bitmask**：低 4 位基础类型 + 高 4 位 biome/变体）**会改变瓦片值编码 = 改地形输出** ⇒ 显式登记为"必须等 WS-5b 落地之后"的红线覆盖项 | 该文档目前**只被 `CHANGELOG.md:255` 引用**，w3/w4 任何批次均未点名它——**本方案补上这个缺口** |

#### 2.3.4 WS-5b 不需要新 ActionId（已实测确认，可作机器判据）

地形生成由**生命周期**（init / import / 新档）触发，**不由玩家操作触发** ⇒ 无 `handleXxxTx`、无 `action_ids.h`/`ActionIds.kt`/`gen-action-ids.mjs` 变更。
**判据**：`execute_dispatch.cpp` 属 W4-00 冻结清单（"出现在三批 diff 中即打回"）——该冻结本身即证明 WS-5b 被设计为不碰分发表。

### 2.4 C-③ 随机源（确定性面）

#### C7 · RNG 阶段 3·战斗侧（ActionId 1855–1859，**条件段**）

> **开工第一步**：读 [ADR rng-determinism-remediation §8/阶段 3](../adr/rng-determinism-remediation.md) 定界。
> **口径修正（实测）**：ADR **并未要求 ActionId + C++ 事务**——阶段 3 的落地形态是"**按调用点粒度下沉**"，硬要求只有 `R1`（影响状态的随机必须取自 `GameRngManager.getRng(RngPartition.*)`）。
> - **优先路线（默认）**：**分区化** —— 把 `BattleDescriptionGenerator` 的 14 个 `.random()` 调用点改为取分区随机或显式传入的随机源。
> - **仅当**确认"必须由 C++ 事务签发结果"时才启用 **1855–1859**（新增 `system/battle_description_tx.h`）；退段须登记结论。
>
> **写者事实（已实测）**：`BattleDescriptionGenerator.kt`（267L，`object` `:12`）——**14 个 `.random()` 调用点 / 12 行**（`:95`、`:103`×2、`:105`×2、`:113`、`:123`、`:140`、`:144`、`:158`、`:173`、`:189`、`:227`、`:261`）；`Random.Default` 字面量 0，无 `import kotlin.random.Random`（走 stdlib 扩展）；唯一调用方 `BattleSystem伤害Ops3.kt:194/210/218/229`。
> **输出确证落 Room**：`BattleSystem回合Ops2.kt:45 message = turnMessage.text` → `BattleLogAction.message`（`CultivatorCave.kt:443`）→ `BattleLog.rounds`（`:155`）→ `@Entity("battle_logs")`（`:116`）→ `StorageEngineWriteOps.kt:215 upsertAll`；schema 实证 `app/schemas/.../1.json:3758 rounds TEXT NOT NULL`。
> **生产可达性（ADR 未写明的一层）**：C++ 战斗路由 `BattleExecutionRouter.kt:37-57` 只覆盖"系统内部战斗（AI 兽战/任务完成）"且 C++ 侧 message 是**确定性摘要**（`:173/:189-205`）；**玩家可见战斗仍走 Kotlin**（`EncounterBattleService.kt:168/280`、`ExplorationService.kt:321`、`CaveExplorationProcessor.kt:193`、`GameEngineScoutOps.kt:80`、`GameEngineWorldBattleOps.kt:124`、`PatrolBattleSystem.kt:336/375/751`）⇒ **随机措辞确实在生产路径上落库**，不是"死路径"。
>
> **注意**：`battleLogs` 是 **Kotlin 显示域，不入 C++ 状态**（batch-20b 登记）⇒ 本项目标是"随机源可复现"，**不是**"把战报搬进 C++ 状态"。
> **守卫面**：本项**不改** `RngSourceGuardTest.kt`（登记上限是"实际命中 ≤ 上限"的只缩不增语义，不下调也仍然绿）——该文件由 W4-A 独占（README §5.1）；登记值收缩统一留 W4-D。

#### C8 · 三处顶层可变 `xxxRngManager` 收敛

| 文件 | 现状 | 处置 |
|---|---|---|
| `domain/battle/EnemyGenerator.kt`（347L） | **文件级顶层 `var enemyGenRngManager` @`:22`**（不在 object 内；`object EnemyGenerator` 起于 `:26`），解析器 `:23` ⇒ **16 个抽取点**（`:51/:52/:89/:93/:99/:102/:105/:123/:130/:133/:138/:141/:145/:178/:193/:199`）；生产赋值点仅 `GameEngine.kt:171`（注释"给 object 单例使用"） | 改**形参必传**（消除顶层可变状态） |
| `domain/battle/AISectAttackManager.kt`（547L） | `var aisRngManager` @`:40` + `internal val aisRng` @`:41`；抽取点 `:229`；生产赋值 `GameEngine.kt:170` | 同上 |
| `domain/battle/AISectTeamComposer.kt`（214L） | `var teamComposerRngManager` @`:9` + **`private val teamComposerRng`** @`:10-11`（守卫白名单注释写 "internal" 属其自身文字漂移）；抽取点 `:111`；生产赋值 `GameEngine.kt:172`；**测试赋值 0** | 同上 |

**与已修复的 `MissionSystem` 同形态**（顶层可变全局 + 外部覆写）：修法 = **形参必传**，隔离性由构造期依赖保证，不再依赖"初始化顺序恰好正确"。
**事实基线**：主源 `var x: GameRngManager` 实测 **4 文件**（上述三处 + `AISectDiscipleManager.kt:82`），**全部在 `RngEngineIsolationGuardTest.kt:46-62` 白名单内（4 条）** ⇒ 守卫当前为绿。
**语义变化说明（诚实口径）**：生产**单引擎**下行为不变；改变的是**双引擎同进程**场景——从"串流"变"隔离"。
**守卫同步**：本批收敛后**不改** `RngEngineIsolationGuardTest.kt`（残留白名单条目无害）；白名单收缩 + 把条目数落成**显式计数断言**统一在 **W4-D/D5** 执行（README §5.1 / §12）。

### 2.5 数据流（本批涉及的三个流向）

```
① 战斗/秘境状态写（C-①）：
   UI ──► GameEngineBattleOps/SecretRealmOps（首行 native 臂）
            ├─ native → dispatchW4C(core, actionId, params) → C++ 事务 → 回执 → 脏段回读 → 只读镜像
            └─ 回退臂（Kotlin 原路径）

② 地形（C-②，生命周期触发，不走玩家操作）：
   新档/读档 ──► Kotlin 从 Room 读 SaveData（含地形 RLE，若有）
                   └─ importToNative（全量导入，保留面）
                        └─ C++ importStateInternal:
                             ├─ 有地形段 → 直接采用（跨版本冻结）
                             └─ 无地形段 → generateTileData(mapSeed,…) → 落为权威数据 + 标脏
                                  └─ 首次前向脏镜像 → Kotlin GameData 地形字段（一次）
                                        └─ 存档（RLE 编码写入 ProtoBuf）
   显示侧：SectTerrainBridge 读权威态（缺数据才生成并回填）；native 不可用 → Kotlin 位级等价生成器

③ 随机源（C-③）：
   所有影响状态的抽取 ──► GameRngManager.getRng(RngPartition.*)（唯一合法）
   表现类随机       ──► PresentationRandom（不落盘，不得被决策路径调用 —— R3）
```

---

## 3. 影响范围清单

### 3.1 Kotlin 侧（C-①）

| 文件 | 变更 | 说明 |
|---|---|---|
| `.../engine/domain/battle/CombatService.kt` | 修改 | `:78` 伤亡残差下沉 |
| `.../engine/GameEngineWorldBattleOps.kt` | 修改 | `:188` 胜利 / `:288` 失败事务状态下沉（**不写 `defeated`**） |
| `.../engine/GameEngineExplorationNativeOps.kt` | 修改 | `:134` 战前结算改门控 + 下沉 |
| `.../engine/GameEngineBattleOps.kt` | 修改 | `:66` 战前结算下沉；`:274/:339/:366` **登记不下沉**（撕裂事务/原子性） |
| `.../engine/GameEngineSecretRealmOps.kt` | 修改 | `:57` 出发换岗下沉 |
| `.../engine/GameEngineSecretRealmNativeOps.kt` | 修改 | `:100` 到期兜底下沉；`:263` 战报**保留 Kotlin** |
| `.../engine/domain/inventory/InventorySystem*.kt` | 修改 | `materializeDiscipleBagAndMarkDead` 同签名遮蔽收敛 |
| `core/domain/.../reversechannel/W4CChannelClosures.kt` | **新增** | 本批关闭单元（含 C-② 的地形段分类） |

### 3.2 Kotlin 侧（C-② WS-5b）

| 文件 | 变更 | 说明 |
|---|---|---|
| `core/domain/.../model/GameData.kt` | **修改** | 新增 `mapGenVersion` + 地形 RLE 段（`@ProtoNumber` + `@ColumnInfo` + `@SettlementStrategy(PRESERVE_OLD)`；非零默认值加 `@EncodeDefault(ALWAYS)`） |
| `core/data/.../local/GameDatabase.kt` | **修改** | `DATABASE_VERSION` 50 → 51 |
| `core/data/.../local/GameDatabaseMigrationsV51.kt` | **新增** | `MIGRATION_50_51`（新增两列，`DEFAULT` 兜底） |
| `core/data/.../local/GameDatabaseMigrationSupport.kt` | 修改 | 迁移链注册 |
| `core/data/schemas/…json` | 修改 | Room schema 导出（50 → 51） |
| `core/engine/.../util/SectTerrainBridge.kt` | 修改 | 改为读权威态；缺数据才生成并回填；**保留降级臂** |
| `core/domain/.../model/MapPreloadData.kt` | 修改 | KDoc 口径改写（`:22-23` "仅内存传递不序列化"已过期） |
| `feature/game/.../SectMapController.kt` | 修改 | `:51-54` 缓存语义改为"读权威态 + 缺失回填" |
| `feature/game/.../BootSequenceController.kt` | 修改 | `:415-422` 同 |
| `rules/database-migration.md` | **修改** | 扩充协议版本戳迁移判定口径 + C++/Kotlin 字段同步清单 |
| `android/core/engine/src/test/.../ProtoNumberCoverageTest.kt` | 需通过 | 新字段必须有 `@ProtoNumber`；非零默认值必须有 `@EncodeDefault` |

### 3.3 Kotlin 侧（C-③）

| 文件 | 变更 | 说明 |
|---|---|---|
| `.../engine/domain/battle/BattleDescriptionGenerator.kt` | 修改（或下沉） | RNG 阶段 3·战斗侧（14 个 `.random()` 调用点） |
| `.../engine/domain/battle/EnemyGenerator.kt` | 修改 | `enemyGenRngManager`（`:22`）→ 形参必传 |
| `.../engine/domain/battle/AISectAttackManager.kt` | 修改 | `aisRngManager`（`:40`）→ 形参必传 |
| `.../engine/domain/battle/AISectTeamComposer.kt` | 修改 | `teamComposerRngManager`（`:9`）→ 形参必传 |
| `.../engine/GameEngine.kt` | 修改（**租约文件**） | 三处赋值点 `:170-172` 随形参化移除 ⇒ **需向 W4-A 申请租约**（W4-A 为主所有者）。若 W4-A 尚未完成 w3-02 接线 ⇒ 本批的 `GameEngine.kt` 改动顺延至 W4-A 交付后（或由收口人串行化） |
| ~~`RngEngineIsolationGuardTest.kt`~~ / ~~`RngSourceGuardTest.kt`~~ | **不改** | 白名单/登记值的收缩统一留 W4-D/D5（README §5.1） |

### 3.4 C++ 侧

| 文件 | 变更 | 说明 |
|---|---|---|
| `include/gamecore/system/battle_residual_tx.h` | **新增** | 战斗残差事务（`battle.h`/`sect_battle.h` 为纯逻辑非 tx） |
| `include/gamecore/system/secret_realm_residual_tx.h` | **新增** | 出发换岗/到期兜底事务 |
| `include/gamecore/system/battle_description_tx.h` | **新增（条件）** | RNG 阶段 3·战斗侧 |
| `include/gamecore/map/terrain.h` | 修改 | 注释口径改写（`:31-35`）；如需暴露生成常量则同步 |
| `include/gamecore/state/models.h` | **修改（独占）** | GameData 增 `mapGenVersion` + 地形段；GameState 段采用"非空才导出"先例 |
| `src/json_codec.cpp` | **修改（独占）** | 双向编解码（`GC_TO`/`GC_FROM`） |
| `src/game_core.cpp` / `include/gamecore/game_core.h` | **修改（独占）** | `importStateInternal` 归一化族新增 `ensureTerrainGenerated` |
| `src/dispatch_w4c.cpp` | **新增/填充** | 本批 handler 实现 |
| `test/w4c_tests.cmake` | **新增/填充** | 本批 GTest 源清单 |
| `test/battle_residual_tx_test.cpp` / `secret_realm_residual_tx_test.cpp` / 新增 `terrain_freeze_test.cpp` | 新增 | 黄金用例（`terrain_test.cpp` 19 例保持不变 = 生成器位级锁不动） |
| `scripts/action-catalog/w4c.mjs` | **新增/填充** | 本批 ActionId 条目（WS-5b 不入此文件） |

### 3.5 标签项

| 标签 | 结论 |
|---|---|
| **经济** | **无直接影响**：战斗/秘境奖励生成族**整族不下沉**（非分区随机红线）⇒ 奖励数值与源汇闭环不变；WS-5b 不涉及任何货币/物品 |
| **iOS** | **正面影响**：`mapGenVersion` + 地形段的判定落在 C++ 归一化族（平台无关）；`SectTerrainBridge` 的降级臂保证无 native 时仍可用；ProtoBuf/Room 侧的 `@Transient` 云档陷阱已显式规避 |
| **隐私合规** | **无影响**：不新增 SDK / 权限 / 网络请求 / 数据收集；云存档仍走既有 TapCloud 通道（无新增数据类型） |
| **存档** | 🔴 **有重大变更**（本批唯一）：`@Database` 50→51 + 新增两列 + ProtoBuf 字段追加。详见 §4 |

---

## 4. 兼容性分析

| 面 | 结论 |
|---|---|
| **本地存档格式** | 🔴 **变更**：Room `game_data` 表新增 2 列（`map_gen_version` + 地形 RLE 列）；`@Database(version)` 50 → 51；**必须** `MIGRATION_50_51` + 集成测试（旧档种子数据 → 迁移 → 数据完整）。**禁止 `ALTER TABLE DROP COLUMN`** |
| **云存档格式** | 变更：`SaveData.gameData` 新增 ProtoBuf 字段（追加式，向后兼容）。**旧版本 App 读新档**：未知 `@ProtoNumber` 被 kotlinx 忽略（lenient）；**新版本 App 读旧档**：字段缺省 → `mapGenVersion = 0` + 地形空 ⇒ 走"无段生成 + 回填"路径 |
| **存档语义** | 变更：地形从"种子确定性重生"变为"生成即数据、存的地形恒优先"。**老档**首次读入按当前版本生成并回填；此后地图冻结 |
| **协议** | ActionId 只增（1780–1789 / 1790–1799 / 1800–1809 / 1855–1859）；WS-5b **零 ActionId**。`models.h` 新增地形段 = **协议漂移**，须同批同步 `json_codec` + Kotlin `GameData` + `DiffSurfaceAssertion` 对拍（两端同登记） |
| **前后兼容** | ① 战斗/秘境：native 臂失败即回退；② WS-5b：`models.h` 新段用"非空才导出键"+ 导入侧宽松跳过 ⇒ **旧 C++ .so 收到新 Kotlin 无影响**（不认的键被忽略）；**新 .so 收到旧 Kotlin 快照** ⇒ 地形段缺失 ⇒ 生成回填 |
| **降级路径** | ① `NativeEngineFlag.OFF` / `.so` 未加载 ⇒ 各域回退臂；② **地形**：`SectTerrainBridge` native 不可用 ⇒ Kotlin 位级等价生成器（KDoc L20-22 契约）；③ 地形段缺失 ⇒ 按种子生成（零回归） |
| **迁移窗口** | 🔴 **本批有迁移窗口**：`@Database` 版本递增要求 App 升级后首次打开触发 Migration。**不得**在未写 Migration 的情况下改 Entity（CLAUDE.md 7.1 最常见的存档损坏原因） |
| **性能/体积** | 存储面：RLE 后预期 16384 整数段压至 KB 级；内存/协议**结构面**保持 flat IntArray 单一表示（§2.19 红线不破）。前向镜像：地形段由 `dirtyTracker` 标脏，**仅生成/回填那一次**进入镜像载荷（稳态每旬零增量），故 §2.19 担心的"+80KB/次持续代价"在**脏镜像路径上不成立**；仍在全量 `syncFromNative` 回退路径上承担一次 ~64KB（16 位整数 × 16384）传输——已登记为可接受代价 |

---

## 5. 测试方案

### 5.1 C++ GTest

**C-①/C-③ 通用四类**（同 W4-A §5.1）：正常路径 / 校验链全失败臂零写入 / 边界与篡改防御 / **零 RNG 或分区 RNG 全分区快照差分** + 双运行全状态 JSON 逐位一致。

**C-② WS-5b 专项**（新增 `terrain_freeze_test.cpp`）：

| # | 测试 | 断言 |
|---|---|---|
| 1 | **生成即数据** | 无段快照导入 ⇒ 地形段被填充且 `mapGenVersion = 当前版本`；同输入两次导入 ⇒ 地形**逐位相同**（确定性） |
| 2 | **存的地形恒优先** | 人为构造"与当前生成器不一致"的地形段（如把某瓦片改成 `BUILDING`）⇒ 导入后**保留该段**（不重算、不覆盖） |
| 3 | **老档回填** | 无段快照 → 导入（生成）→ 导出 → 再导入 ⇒ 第二次**不重算**（`mapGenVersion` 与地形逐位相同） |
| 4 | **RLE roundtrip** | `encodeRLE(decodeRLE(x)) == x`；`decodeRLE(encodeRLE(tiles)) == tiles`；全同值段、单元素、空数组、极长段（16384 全同）四类边界 |
| 5 | **段存在性协议** | 无地形 ⇒ `exportStateJson` **不含**地形键（"非空才导出"先例）；有地形 ⇒ 键存在且可解析 |
| 6 | **`terrain_test.cpp` 19 例保持** | 生成器本身的双端位级锁**不动**（handover §2.60.2 项⑦） |

### 5.2 Kotlin 测试

| 层 | 内容 |
|---|---|
| **Migration 集成测试** | `MIGRATION_50_51`：v50 种子数据（含既有 `map_seed` 等） → 运行迁移 → 断言新列存在且默认值正确、既有列数据零丢失（`rules/database-migration.md` 7.4 强制） |
| **存读往返** | 存档 → 读档 ⇒ 地形段与 `mapGenVersion` 逐位一致（本地 Room 路径 + 云 `SaveData` ProtoBuf 路径**各一例**） |
| **云档不丢地形** | `buildSaveSnapshot → createSaveDataFromSnapshot → protoBuf.encodeToByteArray` 往返后地形段存在（堵死 `@Transient` + heavy_data 的丢数据路径） |
| **跨版本冻结** | 用旧 `mapGenVersion` + 已存地形 → 读档 ⇒ 地图不变（不重算） |
| **降级臂** | `SectTerrainBridge` 在 native 不可用时返回与 native 位级等价的数组（既有 `DiffSectTerrainTest` 4 例保持） |
| **守卫测试** | `ProtoNumberCoverageTest`（新字段合规）+ `ReverseChannelPolicyGuardTest`（穷尽分类）+ `RngSourceGuardTest` / `RngEngineIsolationGuardTest`（只缩不增） |
| **GateTest（C-①）** | 每域 flag OFF vs ON：结果值 + 状态逐字段一致；失败信封回退臂重执行校验链 |

### 5.3 对抗性审查要点

| # | 审查点 |
|---|---|
| 1 | **非分区随机红线**：战利品生成族是否被"顺手"下沉？（`templates.random(random)` 六处**未传实参** ⇒ 实际 `Random.Default`；判定必须看**实参**，不能信形参名） |
| 2 | **TOCTOU 早退**：C++ 是否顺手写了 `defeated` ⇒ Kotlin 重查臂早退漏发魂力（batch-13 教训） |
| 3 | **撕裂事务**：`recordSectBattleRecord` / `occupySectRewards` / `crushSectRewards` 与 `battleLogs` 的原子性边界是否被切开 |
| 4 | **`@Transient` + heavy_data 的云档陷阱**：地形是否被放在 `@Transient`（会是丢数据缺陷） |
| 5 | **`@EncodeDefault` 遗漏**：`mapGenVersion` 默认 0 = 该类型零值 ⇒ 可不加；但**地形段若用非空默认值**必须加，否则 `encodeDefaults=false` 下静默丢失 |
| 6 | **`@ProtoNumber` 编号冲突**：必须取预留段 1000+（实测 1000+ 空闲、GameData 本体已用最高 224） |
| 7 | **proto 编号守卫**：`ProtoNumberCoverageTest` 必须绿 |
| 8 | **前向镜像载荷**：地形段是否只在生成/回填时标脏一次（避免每旬 64KB 增量） |
| 9 | **注释口径**：`terrain.h:31-35` 与 `MapPreloadData.kt:22-23` 的"不入协议"表述已过期，是否同批改写（注释一致性红线） |
| 10 | **Autotile 红线**：`docs/architecture/tile-rendering-next-gen.md` 的 bitmask 提案是否已被显式登记为 WS-5b 之后的红线覆盖项 |
| 11 | **`RngEngineIsolationGuardTest` 白名单**：三处收敛后白名单是否同步缩（只缩不增） |
| 12 | **死亡标记路径**：新增写 `isAlive=0`/`status=DEAD` 的代码必须调用 `discipleTables.markDead(id, year)` 或 `handleDiscipleDeath` |

### 5.4 测试墙钟成本核算

| 项 | 评估 |
|---|---|
| `terrain_freeze_test.cpp` 新增 6 组 | 含 16384 元素 roundtrip（微秒级），总墙钟 < 1 秒 |
| Migration 集成测试 | Room 内存库迁移，Robolectric 单例 ~秒级 |
| 存读往返 × 2 路径 | 各一例；**不设迭代**（确定性偏差单次即可捕获，100 与 1000 迭代守卫强度相同） |
| 全量门禁 | 桌面 C++ 全量（含 `terrain_test.cpp` 19 例）+ `:core:engine` 3281 例 + 47 个 `Diff*`；**批内按块推进**（C-①/②/③ 各一次全量），重活经**构建令牌**串行 |
| 上限约束 | 单测试 < 30 秒；无外部路径/构建产物依赖（`DiffSectTerrainTest` 用 `assumeTrue(DiffRngBridge.isAvailable())` 跳过语义保持） |

---

## 6. 风险评估与兜底

| 风险 | 概率 | 影响 | 兜底 |
|---|---|---|---|
| **漏写 Migration / schema 未更新 ⇒ 存档损坏** | 中 | **极高** | R1–R3 清单硬约束 + Migration 集成测试为门禁；**改 `@Entity` 前先读 `rules/database-migration.md`** |
| **地形走了 `@Transient` + heavy_data ⇒ 云档丢地形** | 中 | 高 | §2.3.3 R1 明令；云往返用例为门禁（§5.2） |
| **前向镜像每旬携带 64KB 地形 ⇒ 性能回归** | 中 | 中 | 地形段只在生成/回填时标脏；加"稳态窗口镜像体积"用例（对齐 `ReverseChannelVolumeProfileTest` 方法学） |
| **`models.h` 变更触发 15 个 `*_tx.h` 重编译** | 高（必然） | 低（构建时间） | 已知且可接受（只读 include，无文本冲突）；构建令牌串行化重活 |
| **RLE 与 flat 表示混用 ⇒ 契约破裂（§2.19 红线）** | 中 | 高 | 内存/协议**结构面**保持 flat；RLE 仅在存储编解码层；roundtrip 用例 + 注释明写 |
| **战利品族被"顺手"下沉 ⇒ 行为基线破裂** | 中 | 高 | §5.3 第 1 条为必审项 + 整族"不下沉"登记进批文档 |
| **TOCTOU 早退 ⇒ 漏发魂力** | 中 | 高 | `defeated` 明确不写 + 回归用例 |
| **三处 `xxxRngManager` 收敛改变双引擎测试夹具行为** | 中 | 中 | 明示"生产单引擎下行为不变"；全量对拍复验；守卫白名单同步缩 |
| **`DocsAutotile` 提案在 WS-5b 之前被启动 ⇒ 老档地图漂移** | 低 | 高 | R10 显式登记红线覆盖项 |
| **同一工作树内 C-①/C-②/C-③ 交错导致编译面互相阻塞** | 中 | 中 | 批内按块提交（一个块一个提交）；每块提交前跑 `compileReleaseKotlin` + `compileReleaseUnitTestKotlin` |
| **与 W4-A/B 的隐藏文件交集**（尤其 `SaveFacadeImpl.kt` 归 W4-B） | 中 | 中 | README §5 所有权矩阵 + R6 明令 + 冻结文件出现在本批 diff 中即打回 |

---

## 7. 未来场景推演（≥6 个月档）

| 维度 | 推演 | 结论 |
|---|---|---|
| **规模增长** | 地图尺寸放大（128² → 256²）：地形段 16384 → 65536 整数；RLE 在真实地形（大片同类型瓦片）上压缩比高，存储增长远低于线性；内存 flat 表示按 4 字节/格线性增长（65536 × 4B = 256KB，可接受）。**触发条件**：若未来世界格数 > 1024²，需重新评估（改分块存储） | 线性可控，已登记触发条件 |
| **生命周期** | 跨版本升级：**存的地形恒优先** ⇒ 老档地图永久冻结（Minecraft"区块边境"模式，主动接受）；新档用新生成器。构建/重启/清缓存不影响（数据在存档里） | 一致 |
| **平台扩张（iOS）** | 地形判定在 C++ 归一化族（平台无关）；ProtoBuf/Room 侧 iOS 需 SQLDelight 等价迁移（ADR ios-migration-plan 已登记 Room→SQLDelight 评估）；`mapGenVersion` 是平台中立的整数戳 | iOS 可移植 |
| **运营演进** | 生成器演进（新增地形类型/生物群系）**只需递增 `mapGenVersion`**，老档不受影响、新档自动采用 ⇒ **无需发版协调**（对比"重算"路线会强制全量玩家地图变化） | 无需发版 |
| **兼容回退** | ① 战斗/秘境：`reopenDomain(BATTLE/SECRET_REALM)`（一行）；② 地形：`mapGenVersion` 判定可回退为"忽略存量段、总是重算"（一行判定翻转）；③ 批次回退：`archive/w4-c` tag；④ **存档回退**：新增列可保留不用（禁止 DROP COLUMN 红线天然保证向后兼容） | 可回退，不发版 |

---

## 8. 技术债与偿还计划

| 债项 | 产生原因（为何本批不全做） | 偿还时机（可判断的触发条件） |
|---|---|---|
| **全量 `syncFromNative` 回退路径承担一次 ~64KB 地形传输** | 脏镜像路径零成本，但全量兜底路径必须带全段 | 触发条件 = 真机埋点显示全量同步耗时显著上升；届时改为"地形段独立按需拉取" |
| 战利品生成族不下沉 | 模板抽取走 `Random.Default`（非分区），C++ 无逐位等价物 | 触发条件 = C++ 侧具备物品随机生成器（模板 codegen 下沉）→ 双端同步改算法 + 拍板（路线 A） |
| `recordSectBattleRecord` / `occupySectRewards` / `crushSectRewards` 不下沉 | 与 `battleLogs`（Kotlin 显示域）同事务 ⇒ 撕裂事务 | 触发条件 = `battleLogs` 入 C++ 协议（或改为纯派生只读） |
| `BattleDescriptionGenerator` 若判定为"部分决策" | 撕裂事务禁止 ⇒ 只能整段处置 | ADR 阶段 3 分批推进时按域重议 |
| `RngEngineIsolationGuardTest` 白名单仍保留 `AISectDiscipleManager` | AI 随机源是**解析器**，状态归宿主侧——结构性消除需改 AI 域所有权模型 | 触发条件 = AI 域 UI 操作面下沉（若届时立项） |
| `rules/database-migration.md` 的协议版本戳章节 | 本批只补"地形/`mapGenVersion` 相关口径"，不做全量文档重构 | 触发条件 = 第二个协议版本戳出现（届时抽公共章节） |

---

## 9. 盲区自查与完善建议

| # | 盲点 / 未验证假设 | 影响 | 处置 |
|---|---|---|---|
| 1 | **"新档在 `initialize`/`newGame` 生成地形"在 C++ 侧不存在**（`initialize` 拿不到 mapSeed、C++ 无 `newGame`） | 照字面实施要新增 C++ 生命周期 API，且新档/老档两条生成路径可能漂移 | ✅ **已回写 §2.3.2**：落点改为 `importStateInternal` 归一化族 `ensureTerrainGenerated`，新档与老档**同一条路径** |
| 2 | **地形入档需改 Kotlin ProtoBuf + Room + Migration**（上游文档未写明） | 按"纯 C++ 协议改动"排期会漏掉最大的工作量与最高的风险 | ✅ **已回写 §1.1 核实结论 + §2.3.3 R1/R2 + §4**；`rules/database-migration.md` 同步扩充（R9） |
| 3 | **`@Transient` + heavy_data 会让云档丢地形** | 静默数据丢失（玩家地图在云档恢复后重生成） | ✅ **已回写 §2.3.3 R1 + §5.2 云档不丢地形用例** |
| 4 | **`SaveFacadeImpl.kt` 属 W4-B，本批禁改** | 若 WS-5b 实现需改它 ⇒ 跨批冲突 | ✅ **已回写 §2.3.3 R6**：走"GameData 新字段"路线则无需改；确需改 ⇒ 停下找收口人 |
| 5 | **每旬镜像是否带 64KB 地形** | 若每旬都带 ⇒ 性能回归（§2.19 当初拒绝入协议的理由） | ✅ **已回写 §4 性能行 + §5.3 第 8 条**：地形只在生成/回填时标脏一次；加稳态镜像体积用例 |
| 6 | **`terrain.h:31-35` 与 `MapPreloadData.kt:22-23` 注释与决策冲突** | 注释即错误口径（违反注释一致性红线） | ✅ **已回写 §2.3.3 R8**（同批必改） |
| 7 | **`docs/architecture/tile-rendering-next-gen.md` 的 Autotile 提案会改地形输出** | 若在 WS-5b 前启动 ⇒ 老档地图漂移窗口 | ✅ **已回写 §2.3.3 R10**（显式登记红线覆盖项——上游两份计划文档均未点名它） |
| 8 | **RNG 阶段 3 是否真的需要 ActionId + C++ 事务** | 若只需形参化，1855–1859 应退段、工作项应缩 | ✅ **已回写 §2.4 C7**：标为条件段；开工第一步定界，退段须登记结论 |
| 9 | **`handle*Tx` / `terrain_test.cpp` 等计数口径**：实测 `handle*Tx` **18** 个（文档记 33 handler）、`terrain_test.cpp` **19** 例（handover 记 16） | 计数漂移会误导验收 | ✅ **已回写 §9 本条**：批内一律以 `git grep` 实跑计数为准，**文档禁止手抄**（handover §7 已立此纪律） |
| 10 | **`GameData` 字段总数口径**：实测 `@ProtoNumber` 158 处（GameData 本体 136 + 4 个内嵌类 22），而 policy 记"135 字段" | `ReverseChannelPolicyGuardTest` 的穷尽分类边界可能不是 135 | ⚠️ **以守卫测试实跑为准**；本批新增 2 字段后必须重跑并据实更新（差值 1 属既有口径问题，**不得**为了让数字对上而改测试） |
| 11 | **非功能属性（功耗/内存）** | 地形常驻内存 64KB（128² × 4B） | ✅ 已评估：可接受；1024² 以上需重新评估（§7 已登记触发条件） |
| 12 | **隐私合规** | —— | ✅ 无影响（无新增 SDK/权限/网络/数据类型），已回写 §3.5 |
| 13 | **流程盲区**：`DiffSectTerrainTest` 用 `assumeTrue` 跳过 ⇒ JNI 不可用时**假绿** | 地形位级锁可能长期未被真正执行 | ✅ 已回写 §5.4：CI 必须注入 `-Dgamecore.jni.path` 且 `--rerun-tasks`（否则 `UP-TO-DATE` 假绿）；门禁第 6 条要求实跑证据 |
| 14 | **跨块交错导致的编译面阻塞** | C-①/C-②/C-③ 同工作树，任一未完成符号会卡死共享模块编译 | ✅ 已回写 §6 倒数第 2 行：**按块提交**，每块提交前跑主源 + 测试源编译 |

> 实质影响方案主体的结论（#1 生成落点、#2 Kotlin/Room 改动、#3 云档陷阱、#4 所有权边界、#5 镜像载荷、#6 注释、#7 Autotile 红线）**已全部回写** §1.1/§2.3/§3/§4。
