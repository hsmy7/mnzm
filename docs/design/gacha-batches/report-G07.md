# report-G07 · 玩家侧战死 → 重伤 + 旬结回血

**日期**：2026-09-23
**分支**：`feat/gacha-m0-m1`
**依赖**：G01（协议字段）；本批**先于 G02** 落地（寿元真死亡链保留，G02 收口）

## 做了什么

1. **口径反转：玩家侧弟子不再死亡 → 重伤（Q20/Q41）**
   - 三端最低层写入：`DiscipleTables.markDead` / `gamecore::system::markDead` 由「死亡三元组（isAlive=0 + status=DEAD + deathYears）」改为「**气血钳到 1 且保持存活**」，不写 status/deathYears、**不递增年报死亡计数**；名字保留以兼容既有调用点。
   - 常量单一源：`GameConfig.Disciple.INJURED_HP = 1`（Kotlin 侧 `DiscipleDeathHandler.INJURED_HP` 引用它），C++ 侧 `gamecore::system::kInjuredHp`。
2. **调用点清单（实施计划要求「先出清单，禁止只改三处」）**

| # | 调用点 | 路径 | 处置 |
|---|---|---|---|
| 1 | `CombatService.processBattleCasualties`（`isOutsideSect=true`） | 宗门战/世界关/侦查/秘境/探索（**生产恒传 true**） | 短路为重伤支：逐 id 钳 1 + 幸存者 HP/MP 回写，**不再**物化行囊/清槽/广播 DeathEvent/清 Room 生产槽 |
| 2 | `CombatService` 内 `materializeDiscipleBagAndMarkDead` 调用 | 同上 | 生产不可达（仅 `isOutsideSect=false` 走 native 1780 臂，无生产调用方） |
| 3 | `DiscipleDeathHandler.markDead / markAllDead / markInjured` | Kotlin 薄封装 | 全部转重伤 |
| 4 | `InventorySystem.materializeDiscipleBagAndMarkDead` | 宗门战/世界关/侦查/秘境 | 不再物化行囊、不清袋、不计年报；只写 HP=1 |
| 5 | `PatrolBattleSystem.finalizeBattleOutcome` | 巡逻 | 败者 `combat.currentHp=1`；**摘除悲痛传播 + `patrolSlots` 清理**（重伤不清槽） |
| 6 | `ExplorationServiceBeastRaidOps.processBeastCasualties` | 妖兽袭宗（玩家驻守） | 败者 HP=1；**摘除悲痛传播 + 驻防槽清理**（原清理取 `!isAlive`，G07 后成死码） |
| 7 | `EncounterBattleService.applyDeathsForSect`（玩家臂） | 遭遇战 | 经 `markAllDead` → 重伤；AI 臂仍写 `aiSectDisciples` 真死亡 |
| 8 | `CultivationEventProcessor.updateDiscipleHpMpAfterBattle` | 战斗 HP/MP 写回 | 经 `markAllDead` → 重伤 |
| 9 | `DiscipleLifecycleProcessor.handleDiscipleDeath(isOutsideSect=true)` | 宗门内/外败北路径 | 重伤支：只写 HP=1 + 事件；**不**清槽/解绑/清装/物化袋 |
| 10 | `gamecore::system::settleBattleCasualtiesTx`（`isOutsideSect=true`） | C++ 战斗伤亡残差 | 逐 id `markDead` → 重伤；悲痛/装备/槽位段仅 `isOutsideSect=false` 可达 |
| 11 | `gamecore::system::markAllDead` 调用点（含 `sect_defense_battle.h` / `sect_conquest.h`） | 宗门防守战 / 被夺回 | 守方全员重伤；**摘除 `propagateGriefToRelatives` 与 11 类槽位清理** |
| 12 | `gamecore::system::sr_session::detail::materializeDiscipleBagAndMarkDead` | 秘境/探索战斗 | 不再物化行囊、不清袋；只写 HP=1 |

3. **旬结回血：复用既有机制，零新增**（用户明确口径）
   - 既有链路 = `PhaseSettlementExecutor`（存活过滤 `isAlive != 1` 跳过）→ `CultivationCore.recoverHpMpSingleColumn` → `HpMpRecoveryService`（`maxValue × PHASE_HP_MP_RECOVERY_RATE(0.2) × 旬数`，至少 1，钳上限）；C++ 对等 = `phase_settlement.h` + `kPhaseHpMpRecoveryRate`。
   - 重伤弟子保持 `isAlive=1` ⇒ **必然通过存活过滤并被回血**；从 HP=1 回满约 5 旬（≈1.67 月），符合 §6.9「按现有 HP 回复机制回升至满」。
   - **本批未新增任何回血机制、未新增配置项**；`gachaDefaults.injuryHealPctPerPhase`（G01 已落配置源）本批未消费，留待 G13 数值落地时判定是否需要偏离既有 0.2。
4. **AI / 妖兽 / 随机人类敌人照旧可死**（已核实物理分离：玩家 = `DiscipleStore`/`DiscipleTables`；AI = `GameData.aiSectDisciples`；妖兽与随机人类敌人 = 战斗局部 `Combatant`，从不落任何弟子表）。
5. **`death_handler` / `hasReviveEffect` / 尸体新陈代谢 / 丧亲字段保留**（AI 侧与旧档仍用）；玩家侧调用点已摘除。`hasReviveEffect` 经核实**无任何游戏行为消费者**（仅在声明/列桥接/序列化/镜像流动），故「停读写」落地面为零，字段留待后续批。
6. **UI「重伤」由 HP 派生，不新增状态枚举**：`DetailCultivationSection` 气血条在 `isAlive && currentHp == INJURED_HP` 时改显示「重伤」+ 警示色。
7. **顺带根因修复**：`DiscipleTablesAssemblers.assembleCombat` 缺列默认值 `currentHp/currentMp = 0` 与三端默认（-1 = 满血）不一致，会让缺列弟子被误判「未满血」并参与每旬回血 → 改为 `FULL_HP_SENTINEL = -1`。

## 验证

| 门 | 结果 |
|---|---|
| 桌面 ctest 全量 | ✅ **1606/1606**，0 failed，59.7s |
| `:core:engine` JUnit（G07 回归面 18 类过滤） | ✅ 见下 |
| `:core:domain` / `:app` JUnit（G07 回归面） | ✅ 见下 |
| `compileReleaseKotlin` | ✅ BUILD SUCCESSFUL |

## 旧用例处置表

| 用例 | 处置 | 理由 |
|---|---|---|
| `DiscipleDeathHandlerTest`（7 例） | **改断言** | 改为重伤语义（HP=1/存活/无 deathYear/不计年报）；删除恒真无效断言（原断言查的是新建空的 `GameData`） |
| `CultivationCoreTest` 新增 1 例 | **新增** | G07 回血守卫：重伤弟子通过存活过滤 → 既有每旬回血回升至满 |
| `DiffDeathHandlerTest.backfill death years` | **改基线** | 原基线用 `markDead` 预置 deathYears；G07 后死亡行只来自存量旧档 ⇒ 两端基线同为「缺失 → 补写 → 二次调用幂等」 |
| `BattleResidualNativeTxGateTest` | **改断言 + 改名口径** | 三臂不再物化行囊/清 Room 槽/广播 DeathEvent；改为断言三臂重伤不变量一致 |
| `GameEngineJadePurchaseTest` / `GameEngineTraitWashTest` / `GameEngineTraitAddTest` / `GameEngineSpiritRootWashTest` | **改夹具** | 原用 `markDead` 造「死弟子」夹具；改直写 `isAlive[1] = 0`（= 存量旧档已故行），被测的「拒绝」语义不变 |
| `GameStateStoreAggregationCacheTest.死亡弟子仍在快照中` | **改夹具** | 同上 |
| `sect_defense_battle_test.cpp` | **改断言 + 拆例** | 删除依赖「尸体数反推胜负」的假绿断言；拆为「攻方胜 → 掠夺 + 守方重伤 + 槽位保留」直驱用例 + 「任意种子守方不产生尸体行」不变量用例 |
| `sect_conquest_test.cpp` | **改断言** | 原断言「驻军阵亡数 = 年报计数」在 G07 后退化为 `0 == 0` 假绿；改为重伤不变量 |
| `gamecore/test/death_handler_test.cpp`、`battle_residual_tx_test.cpp`、`exploration_tx_test.cpp` | **改断言** | 随重伤语义调整 |

## 分类表（本批）

| 类别 | 面 |
|---|---|
| ① 搬迁 | — |
| ② 删除/只读 | 玩家侧死亡副作用摘除：行囊物化、清袋、悲痛传播、槽位/装备清理、DeathEvent 广播、Room 生产槽清理、年报死亡计数 |
| ③ 命令进 C++ / 回执出 C++ | 无新增 ActionId、无新增 JNI 端口；既有 1780 残差签名不变 |

## 未完成 / 登记

- **寿元（`isOutsideSect=false`）真死亡链保留**，随 G02 删除（本批注释已标注收口点）；删除后 `battle_residual_tx.h` 的 `isOutsideSect=false` 分支、`materializeDiscipleBagAndMarkDead` 双端实现、`propagateGriefToRelatives`、`slot_cleanup` 引用将成为死码，由 G02/G03 清理。
- `isOutsideSect` 布尔**语义过载**（原义「死在宗门外→遗物不回收」被反用为「走重伤」）：当前生产恒传 `true` 故行为正确；G02 删除 `false` 分支后应改为显式命名参数（登记为 G02 收口项）。
- `DiscipleDeathHandler` / `gamecore::system::markDead` **命名与语义已不符**（名字叫死亡、行为是重伤）：改名需同步跨语言对拍与 JNI 契约，登记随 G02/G10 收口。
- 秘境**会话态 vs 弟子态**不一致（会话成员可「永久死亡」但弟子行存活重伤）：属 §6.9 秘境链，登记待 G09/G11 一并核定。
- RNG 影响：本批**零 RNG 抽取变更**（仅状态写入语义），不产生对拍基线平移；G10 仍按 G02–G06 的删除面统一重录。
- `gachaDefaults.injuryHealPctPerPhase` 未被消费（既有 0.2 与配置值一致），G13 判定是否需要在配置侧单源化。
