# XianxiaSectNative 全面验证报告

> 本文档基于"默认所有功能都有问题"的测试视角，对 14 个玩法领域及横切关注点进行全面验证的结果汇总。
> 验证范围：54 个确认问题（P0:11 / P1:33 / P2:10）+ 28 个验证无问题项 + 5 个判断节点（已确认）。

---

## 审查修订说明（二次核对）

> 本节为对原报告的代码事实核对与修复方向可行性评估结果。
> **审查范围**：逐项打开代码位置核对现象描述 + 评估修复方向可行性
> **审查标记**：每个问题末尾以 `> 审查结果:` 标注核对结论
> **标记取值**：
> - `确认` — 描述与代码一致，修复方向可行
> - `修正描述` — 描述与代码存在差异，已修正
> - `修正修复方向` — 描述准确但修复方向需调整
> - `否定问题` — 描述的前提与代码不符，问题不成立

**审查统计**：
- 确认：10 项
- 修正描述：6 项
- 修正修复方向：13 项
- 否定问题：1 项（ProtoBuf Set/Map 违规）

---

## 目录

- [一、问题严重等级定义](#一问题严重等级定义)
- [二、问题清单（按领域分类）](#二问题清单按领域分类)
  - [1. 月结领域](#1-月结领域)
  - [2. 战斗领域](#2-战斗领域)
  - [3. 生产/建筑领域](#3-生产建筑领域)
  - [4. 存档领域](#4-存档领域)
  - [5. 弟子领域](#5-弟子领域)
  - [6. 商人领域](#6-商人领域)
  - [7. 血炼领域](#7-血炼领域)
  - [8. 天道试炼领域](#8-天道试炼领域)
  - [9. 外交领域](#9-外交领域)
  - [10. 探索领域](#10-探索领域)
  - [11. 邮件领域](#11-邮件领域)
  - [12. 签到/兑换领域](#12-签到兑换领域)
  - [13. UI/ViewModel 领域](#13-uiviewmodel-领域)
  - [14. 修炼领域](#14-修炼领域)
- [三、横切关注点](#三横切关注点)
- [四、判断节点及确认结果](#四判断节点及确认结果)
- [五、修复方向汇总](#五修复方向汇总)
- [六、验证无问题项](#六验证无问题项)

---

## 一、问题严重等级定义

| 等级 | 含义 | 触发条件 |
|------|------|---------|
| **P0** | 严重 | 数据损坏、静默丢失、原子性破坏、存档崩溃、可被玩家利用的无限刷资源 |
| **P1** | 高 | 逻辑错误、状态不一致、并发隐患、设计偏差、影响玩法平衡 |
| **P2** | 中 | 代码质量、空 catch、!! 操作符、CancellationException 吞没、死代码 |

---

## 二、问题清单（按领域分类）

### 1. 月结领域

#### #1 SettlementCoordinator 双重计算 [P0]

- **位置**：[SettlementCoordinator.kt:366-370](file:///c:\Mnzm\XianxiaSectNative\android\core\engine\src\main\java\com\xianxia\sect\core\domain\settlement\SettlementCoordinator.kt#L366-L370), [SettlementCoordinator.kt:456-461](file:///c:\Mnzm\XianxiaSectNative\android\core\engine\src\main\java\com\xianxia\sect\core\domain\settlement\SettlementCoordinator.kt#L456-L461)
- **现象**：`processCleanDiscipleBatch` / `processDirtyDiscipleBatch` 中存在 `+ alreadyGained` 冗余加法
- **影响**：月结时部分资源被重复计算，玩家每月多获得资源
- **修复方向**：删除冗余的 `+ alreadyGained` 表达式，保留单次计算

> 审查结果: **确认**
> - 代码事实：一致。第 369/460 行 `totalGain = netMonthlyGain * batchMonths + alreadyGained` 中的 `+ alreadyGained` 确为冗余。对比焦点路径 `processFocusedDiscipleImmediate`（第 281-283 行）和 `CultivationCore.updateMonthlyCultivation`（第 493-494 行）均无此冗余项，证明批次路径是错误。
> - 修复方向：可行。删除后 `batchMonths=1` 时与焦点路径结果一致；`alreadyGained=0`（常规非焦点弟子）无影响。
> - 潜在风险：低。该 bug 是否在实际运行中触发取决于焦点切换时序（`resetHighFrequencyData` 在 `processFocusedDiscipleImmediate` 第 326 行调用，若提前 return 则不重置）。

#### #46 衰减顺序与周期 [P1]

- **位置**：SettlementCoordinator 衰减逻辑
- **现象**：衰减触发时机不明确，周期为月
- **确认结果**：立即生效，周期保持月（不改旬）
- **修复方向**：调整衰减触发时机为立即生效，不改变周期单位

> 审查结果: **修正描述 + 修正修复方向**
> - 代码事实：部分一致。衰减实现位于 `CultivationCore.applyMonthlyDurationDecay`（第 215-255 行），非 SettlementCoordinator 本身。调用点三处：第 294 行（焦点弟子月结）、第 386 行（clean 批次 `repeat(batchMonths)`）、第 477 行（dirty 批次 `repeat(batchMonths)`）。衰减量 `monthlyDecay = (30 - focusedPhaseCount * 10).coerceAtLeast(0)`，"周期为月"准确。
> - 描述修正："触发时机不明确"不准确。实际代码中触发时机是明确的：月结时触发；焦点弟子额外在每旬 `processDiscipleTick`（第 304-320 行）触发逐旬衰减，月结时通过 `focusedPhaseCount` 扣除已衰减部分避免双计。非焦点弟子仅在月结批次中衰减。
> - 修复方向修正：原方向"立即生效，不改变周期单位"自相矛盾（"立即生效"暗示逐旬/实时，"不改变周期单位"暗示保持月度）。当前非焦点弟子仅在月结时衰减是热控分批设计的一部分。若改为逐旬衰减需让所有弟子参与逐旬 tick，与热控分批架构冲突。
> - 潜在风险：若改为逐旬衰减且未同步修改月结逻辑可能引入双计；强制所有弟子逐旬 tick 破坏热控分批性能设计。需明确是改为逐旬还是保持月度，以及如何与热控分批协调。

---

### 2. 战斗领域

#### #2 CombatService 战斗伤亡非原子 [P0]

- **位置**：[CombatService.kt:101-279](file:///c:\Mnzm\XianxiaSectNative\android\core\engine\src\main\java\com\xianxia\sect\core\domain\battle\CombatService.kt#L101-L279)
- **现象**：`processBattleCasualties` 包含 5 个独立写操作，未包裹在单事务中
- **影响**：中途失败会导致弟子状态、装备、宗门数据不一致
- **修复方向**：将 5 个写操作包裹在单一事务中，失败时整体回滚

> 审查结果: **修正描述 + 修正修复方向**
> - 代码事实：部分一致。实际独立写操作不止 5 个，至少 6 组：
>   1. 第 108-121 行：直接写 `stateStore.discipleTables.griefEndYears[id]`（亲属于悲痛期），未在 update 块内
>   2. 第 133-134 行：直接写 `isAlive[id] = 0`、`statuses[id] = DEAD`（forEach 内逐个写），未在 update 块内
>   3. 第 158-175 行：第一个 `stateStore.update { ... }`（proficiencies/equipmentInstances/manualInstances）
>   4. 第 247-253 行：第二个 `stateStore.update { ... }`（elderSlots/spiritMineSlots/librarySlots）
>   5. 第 255-262 行：`productionSlotRepository.updateSlotByBuildingId(...)`，**走另一个 Repository，不在 stateStore 事务内**
>   6. 第 266-277 行：直接写 `currentHps[id]`、`currentMps[id]`、`statuses[id]`（幸存者 HP/MP），未在 update 块内
> - 描述修正："5 个独立写操作"实际为 6 组，且第 5 组跨独立 Repository。
> - 修复方向修正：`GameStateStoreImpl.update`（第 719-798 行）本身是事务性的（`transactionMutex.withLock` 快照→执行→统一写回，block 抛异常不提交），stateStore 层面包裹可行。但难点：
>   - `stateStore.discipleTables.*` 的直接写入绕过 update 块，需改为通过 update 块内 `MutableGameState` 修改。注意 `update` 实现第 762 行 `discipleTables = _discipleTables` 是直接引用未做快照隔离，存在隐患。
>   - `productionSlotRepository` 是独立 Repository，若其底层不接入 stateStore 事务则无法纳入同一事务，需额外改造或接受该部分非原子。
> - 潜在风险：将 discipleTables 直接写入改为 update 块内写入可能影响性能；productionSlotRepository 跨库事务问题若不解决，"整体回滚"仍不彻底。

#### #33 天道试炼伤害公式偏差 [P1]

- **位置**：[HeavenlyTrialCombatScreen.kt:1304-1328](file:///c:\Mnzm\XianxiaSectNative\android\feature\game\src\main\java\com\xianxia\sect\ui\game\dialogs\HeavenlyTrialCombatScreen.kt#L1304-L1328)
- **现象**：UI 层手写 `computeNormalAttackDamage` / `computeSkillDamage`，与 BattleSystem 存在 5 处偏差
- **影响**：天道试炼实际伤害与战斗系统不一致
- **确认结果**：UI 层改调 BattleCalculator，与其他战斗计算一致
- **修复方向**：删除 UI 层本地公式函数，改调 `BattleCalculator.calculateCombatantDamage`

> 审查结果: **修正描述 + 修复方向可行但需补充**
> - 代码事实：部分一致。本地函数与行号匹配，BattleSystem 第 784/803 行调用 BattleCalculator 准确。但实际偏差不止 5 处，逐项比对为 7-8 处：
>
> | # | 偏差点 | UI 本地函数 | BattleCalculator |
> |---|--------|-------------|------------------|
> | 1 | 防御常量 | 硬编码 `500.0`（第 1309、1324 行） | `GameConfig.Battle.DEFENSE_CONSTANT` |
> | 2 | 伤害方差 | `0.9 + Random.nextDouble() * 0.2` | `calculateDamageVariance()` 基于 `DAMAGE_VARIANCE_PERCENT` 且十分位取整 |
> | 3 | 闪避 | 无 | 有 `calculateCombatantDodgeChance` + 闪避返回 0 |
> | 4 | 暴击 | 无 | 有 `effectiveCritRate` + `CRIT_MULTIPLIER` |
> | 5 | 境界差系数 | 无 | `calculateRealmGapMultiplier` |
> | 6 | 最低伤害 | `coerceAtLeast(1)` | `GameConfig.Battle.MIN_DAMAGE` |
> | 7 | 普攻物理/法术选择 | 固定用 `effectivePhysicalAttack` | 按 `physicalAttack >= magicAttack` 自动选择 |
> | 8 | 防御状态 | UI 有 `isDefending` 0.75 倍率 | BattleCalculator 无此概念，BattleSystem 也未传入 |
>
> - 描述修正："5 处偏差"实际为 7-8 处。
> - 修复方向：签名匹配，`calculateCombatantDamage(attacker, defender, skill?)` 可替代两个本地函数。**重要发现**：UI 文件已部分采用 BattleCalculator（第 1543、1556、1577 行 AI 行动分支），但玩家行动分支（第 393、411、429、977 行）和 `applyNormalAttack`/`applySkillDamage`（第 1330-1343 行）仍用本地函数，存在同文件内不一致。
> - 需补充两点：
>   1. 第 8 项 `isDefending` 0.75 倍率是 UI 独有机制，BattleSystem 完全没有。直接替换会**移除防御状态减伤**这一玩家可见行为。需确认是 bug 还是设计意图——若为设计意图，需在 BattleCalculator 层补上 `isDefending` 参数而非简单删除。
>   2. 替换后 UI 层的暴击/闪避动画判定（如第 399、415 行 `Random.nextDouble() < enemy.critRate`）与 `DamageResult.isCrit/isDodged` 不再同步，需一并改为读取 `DamageResult` 字段，否则会出现"伤害按新公式、暴击动画按旧随机"的错位。

---

### 3. 生产/建筑领域

#### #3 建筑移除槽位清理错误 [P0]

- **位置**：[BuildingFacadeImpl.kt:344-368](file:///c:\Mnzm\XianxiaSectNative\android\core\engine\src\main\java\com\xianxia\sect\core\domain\building\BuildingFacadeImpl.kt#L344-L368)
- **现象**：炼丹/锻造/灵矿/巡察塔建筑移除时使用 `max slotIndex` / `dropLast` 而非 `instanceId`
- **影响**：移除错误槽位，弟子分配关系错乱
- **修复方向**：按 `instanceId` 精确匹配移除

> 审查结果: **修正修复方向**
> - 代码事实：一致。第 344-345 行灵矿场 `dropLast(3)`、第 346-348 行巡视楼 `dropLast(8)` + `patrolConfigs.dropLast(1)`、第 358-363 行炼丹炉/锻造坊 `maxOfOrNull { it.slotIndex }` 后过滤移除。对比同函数中灵田/住所/仓库均使用 `it.buildingInstanceId != instanceId` 精确过滤，描述准确。
> - 修复方向修正：方向正确但存在数据结构障碍。`SpiritMineSlot`（GameData.kt:859）、`PatrolSlot`（PatrolState.kt:8）、`ProductionSlot`（ProductionSlot.kt:25）**均无 buildingInstanceId 字段**。要按 instanceId 精确移除，需先为这三个数据类增加 `buildingInstanceId` 字段并迁移现有数据，否则修复无法直接落地。
> - 潜在风险：增加字段涉及 Room 数据库 schema 变更与数据迁移，需评估存量存档兼容性。

#### #4 assignDiscipleToBuilding 排他性检查错误 [P0]

- **位置**：[BuildingService.kt:68-73](file:///c:\Mnzm\XianxiaSectNative\android\core\engine\src\main\java\com\xianxia\sect\core\domain\building\BuildingService.kt#L68-L73)
- **现象**：排他性检查使用 `it.buildingId != buildingId`，对多实例同 buildingId 建筑失效
- **影响**：弟子可被重复分配到多个同类型建筑实例
- **修复方向**：改用建筑实例唯一标识（instanceId）做排他判断

> 审查结果: **修正修复方向**
> - 代码事实：一致。第 68-73 行 `it.buildingId != buildingId && it.assignedDiscipleId == discipleId`，`buildingId` 是类型标识（如 "alchemy"、"forge"），非实例标识。多实例同 buildingId 时条件恒为 false，排他检查失效。
> - 修复方向修正：方向正确但 `ProductionSlot`（ProductionSlot.kt:25-57）**没有 buildingInstanceId 字段**，仅有 `buildingId`（类型）+ `slotIndex`。Repository 层（SlotCache.kt:83-84）以 `buildingId:slotIndex` 作为主索引。直接改用 instanceId 需要：
>   1. 为 ProductionSlot 增加 buildingInstanceId 字段
>   2. 改造 Repository 索引与查询方法
>   3. 改造 `assignDiscipleToBuilding` 入参签名（当前为 `buildingId: String, slotIndex: Int`，无 instanceId）
> - 潜在风险：若仅简化为"检查弟子是否已分配到任意槽位"（去掉 buildingId 条件），可能误拦同建筑多槽位合法分配场景，需确认产品意图。

---

### 4. 存档领域

#### #9 loadFromSnapshot 无回滚 [P0]

- **位置**：[GameStateStoreImpl.kt:996-1040](file:///c:\Mnzm\XianxiaSectNative\android\app\src\main\java\com\xianxia\sect\core\state\GameStateStoreImpl.kt#L996-L1040)
- **现象**：`loadFromSnapshot` 无 try-catch / rollback
- **影响**：加载失败导致存档损坏，无法恢复
- **修复方向**：包裹 try-catch，失败时回滚到加载前状态

> 审查结果: **确认 + 修正修复方向**
> - 代码事实：一致。`loadFromSnapshot` 函数体内仅 `transactionMutex.withLock { ... }` 包裹，无 try-catch，无回滚逻辑。中间任一赋值（`_gameDataFlow.value`、`_disciplesFlow.value`、`_discipleTables.clear()` + insert、`_equipmentStacksFlow.value` 等十余个 Flow 写入）抛异常时已写入状态不恢复，`disciplePowerCache`/`aiDisciplePowerCache`/`aggregateCache` 也已被 `clear()`。对比同文件 `transaction { }`（第 989-992 行 `finally` 块清理）`loadFromSnapshot` 未走该事务路径。
> - 修复方向修正：单纯 try-catch + 回滚实现复杂。回滚需保存的 Flow 数量多（gameData/disciples/equipmentStacks/equipmentInstances/manualStacks/manualInstances/pills/materials/herbs/seeds/storageBags/teams/battleLogs/isPaused/isLoading/isSaving 共 15 个），外加 `_discipleTables`、三个 cache、`_updateVersion`、`repository.markAllDirty()` 副作用。建议方案：先在局部变量中持有所有旧值再统一写入；或先校验入参（如 disciples 与 discipleTables 一致性）再写入，降低中途失败概率。
> - 潜在风险：回滚路径若再抛异常会掩盖原始异常；`repository.markAllDirty()` 已执行后的回滚需考虑持久层一致性；并发场景下持有 `transactionMutex` 期间执行回滚会延长锁占用。

#### #13 GameViewModel 快照读取幽灵操作 [P0]

- **位置**：[GameViewModel.kt:208, 844, 94](file:///c:\Mnzm\XianxiaSectNative\android\feature\game\src\main\java\com\xianxia\sect\ui\game\GameViewModel.kt#L208)
- **现象**：快照读取存在幽灵操作 + DialogStateManager 缺失
- **影响**：状态不一致，对话框状态丢失
- **修复方向**：清理幽灵读取，补全 DialogStateManager

> 审查结果: **修正描述 + 修正修复方向**
> - 代码事实：部分一致。"DialogStateManager 缺失"一致（全仓库搜索无匹配，仅 `core/ui/DialogManager.kt` 为 Composable 工具函数）。"幽灵操作"描述不准确：
>   - 第 208 行 `val idx = gameEngine.gameDataSnapshot.placedBuildings.count { ... }` 用于 `ProductionSlot.createIdle(slotIndex = idx, ...)`（第 209 行），`newProductionSlot` 在 `updateGameData` 闭包内（第 266-267 行）与闭包外（第 280-281 行 `buildingFacade.addProductionSlot`）均被使用。**不是未使用的幽灵操作**，真实问题是：在 `updateGameData` 闭包外基于 `gameDataSnapshot` 计算 `idx`，与闭包内 `data` 非原子，并发放置同类建筑时 `idx` 可能重复。第 212 行 FORGE 同理。
>   - 第 844 行 `val data = gameEngine.gameData.value` 用于 `setYearlySalary` 读取 `data.yearlySalary` 构造新 map 后调用 `updateYearlySalary(newSalary)`。读取必要，非幽灵操作，但存在"读-改-写"非原子问题（应使用 `updateGameData { data -> ... }` 闭包）。
>   - 第 94 行 `_currentDialogRoute = MutableStateFlow<DialogRoute>(DialogRoute.None)` 配合 `_dialogOpenTrigger`（第 97 行）、`_navigationEvents`（第 86 行）、`_popBackEvents`（第 91 行）分散管理对话框。
> - 描述修正："幽灵操作"应改为"快照读取与更新非原子"。
> - 修复方向修正：
>   1. "清理幽灵读取"不成立——这些读取有实际用途，应改为"将快照读取移入 `updateGameData` 闭包内基于 `data` 计算，保证原子性"，而非"清理"。
>   2. "补全 DialogStateManager"可行，但需明确职责边界：当前四个通道并存，简单新增 Manager 而不收敛通道会加剧复杂度。
> - 潜在风险：将 `idx` 计算移入闭包后，`newProductionSlot` 在闭包外（第 280-281 行）仍需引用，需调整作用域；DialogStateManager 重构涉及 `navigateToDialog`/`dismissDialog`/`closeCurrentDialog` 等多个调用点，迁移期间易引入对话框状态丢失。

---

### 5. 弟子领域

#### #5 expelDisciple 装备返回丢失 [P0]

- **位置**：[DiscipleService.kt:488-502](file:///c:\Mnzm\XianxiaSectNative\android\core\engine\src\main\java\com\xianxia\sect\core\domain\disciple\DiscipleService.kt#L488-L502)
- **现象**：装备返回使用 `coerceAtMost` 截断 + 无条件实例移除
- **影响**：驱逐弟子时装备静默丢失
- **修复方向**：移除截断逻辑，按实例 ID 精确移除

> 审查结果: **修正修复方向**
> - 代码事实：一致。第 496 行 `val newQty = (existingStack.quantity + stack.quantity).coerceAtMost(maxStack)` 合并时截断超出部分；第 501 行 `equipmentInstances.remove(eid)` 在 if-else 之外无条件执行实例移除。两者叠加：合并堆叠超出上限时超出数量被丢弃，但原实例仍被移除，造成装备数量丢失。
> - 修复方向修正："按实例 ID 精确移除"表述有歧义——当前代码**已经是按实例 ID（`eid`）移除**，问题不在于移除的"精度"，而在于移除的"条件性"——即使合并因截断而丢失数量，实例仍被无条件移除。修复应改为：仅在装备完整转移（无截断损失）后才移除实例，或改为不截断并完整转移（溢出时新建堆叠）。
> - 潜在风险：若不截断且仓库已满，需定义溢出处理策略（丢弃/新建堆叠/拒绝移除），否则可能引入新的容量越界问题。

#### ChildBirthSystem partnerId 未清除 [P1]

- **位置**：[ChildBirthSystem.kt:94-103, 150-153](file:///c:\Mnzm\XianxiaSectNative\android\core\engine\src\main\java\com\xianxia\sect\core\system\ChildBirthSystem.kt#L94-L103)
- **现象**：弟子死亡时 partnerId 未清除
- **影响**：死亡弟子的伴侣关系残留，影响后续逻辑
- **修复方向**：死亡处理时清除 partnerId

> 审查结果: **修正描述 + 修正修复方向**
> - 代码事实：部分一致。第 94-103 行确认：父亲死亡时仅清除 `childBirthMonth`，**未清除 `partnerId`**，描述准确。但第 150-153 行为 `createChild` 函数内创建子嗣的 SocialData，**与 partnerId 清除无关**，该行号引用不准确。
> - 补充核查：实际死亡处理在 `DiscipleLifecycleProcessor.handleDiscipleDeath`（DiscipleLifecycleProcessor.kt:81-147），该函数执行了 `clearDiscipleFromAllSlots`、`applyGriefToRelatives`、装备返回，但**全程未清除伴侣的 partnerId**。这才是问题的根因位置。
> - 描述修正：行 150-153 引用错误，应改为 DiscipleLifecycleProcessor.kt:81-147。
> - 修复方向修正：主修复点应实施在 `DiscipleLifecycleProcessor.handleDiscipleDeath` 中（清除死亡弟子伴侣的 partnerId），而非 ChildBirthSystem。ChildBirthSystem 第 94-103 行可作为防御性二次清理点，但非主修复点。
> - 潜在风险：清除 partnerId 后，需确认 `PartnerSystem.processPartnerMatching`（PartnerSystem.kt:62-107）能正确识别丧偶弟子重新匹配（当前过滤条件为 `partnerId == null`，清除后即可重新匹配，逻辑自洽）。

---

### 6. 商人领域

#### #6 购买可堆叠物品溢出丢失 [P0]

- **位置**：[InventoryFacadeImpl.kt:498-537](file:///c:\Mnzm\XianxiaSectNative\android\core\engine\src\main\java\com\xianxia\sect\core\domain\inventory\InventoryFacadeImpl.kt#L498-L537)
- **现象**：购买可堆叠物品时溢出部分丢失
- **影响**：玩家花钱买的物品直接消失
- **修复方向**：溢出时拆分为新堆或阻止交易

> 审查结果: **确认**
> - 代码事实：一致。`buyMerchantItem` 函数（第 437 行起）第 502/512/522/532 行 pill/material/herb/seed 四类可堆叠物品购买入库时使用 `coerceAtMost(maxStackSize)` 截断。前置检查（第 451-466 行 `canAddPill` 等）只判断"能否加入"，不校验"完整数量是否在堆叠上限内"，前置检查通过仍会丢数据。equipment/manual（第 480-497 行）无 `coerceAtMost`，不受此问题影响。
> - 修复方向：可行。"溢出时拆分为新堆或阻止交易"两条路径都可行。拆分新堆需在 `else` 分支外增加溢出建堆逻辑；阻止交易需将前置检查改为精确计算剩余容量。
> - 潜在风险：低。需注意 equipment/manual 无堆叠上限的差异化处理，不要误改。

#### #7 商人收购价套利 [P1]

- **位置**：[InventoryFacadeImpl.kt:596-602](file:///c:\Mnzm\XianxiaSectNative\android\core\engine\src\main\java\com\xianxia\sect\core\domain\inventory\InventoryFacadeImpl.kt#L596-L602)
- **现象**：收购价可能高于售卖价，存在套利空间
- **确认结果**：不修（收购是给玩家多一种获取灵石的渠道，套利本身不修）
- **修复方向**：不修（设计意图）

> 审查结果: **确认**
> - 代码事实：部分一致。买入价 `buyMerchantItem` 第 439 行 `cost = merchantItem.price * quantity`，价格来自 `travelingMerchantItems`。卖出价（收购价）`listItemsToMerchant`（第 651、662、673、684、695、706 行）用 `GameConfig.Rarity.calculateSellPrice(basePrice, 1)` 设置 `acquisitionItem.price`，即 `basePrice * 0.8`。`sellToMerchant` 第 596 行 `totalPrice = acquisitionItem.price * actualQuantity`。套利空间是否存在取决于"游商售价 < 玩家自挂收购价（basePrice*0.8）"是否可能成立。代码中两套价格体系独立，报告用"可能"措辞准确，但未给出游商价格生成逻辑的证据，无法从代码本身直接证实套利必然发生。
> - 修复方向：可行（作为设计决策）。"不修"是合理的产品决策。若要消除套利，需在游商价格生成时设置下限 ≥ basePrice*0.8，但这属于配置层调整。

#### #7b sellToMerchant 跳过 0.8 乘数 [P1]

- **位置**：[InventoryFacadeImpl.kt:596-602](file:///c:\Mnzm\XianxiaSectNative\android\core\engine\src\main\java\com\xianxia\sect\core\domain\inventory\InventoryFacadeImpl.kt#L596-L602)
- **现象**：`sellToMerchant` 跳过 `SELL_PRICE_MULTIPLIER=0.8`
- **确认结果**：保持现状（商人渠道整体不打 8 折，收购价=到手价）

> 审查结果: **确认**
> - 代码事实：一致。`GameConfig.kt` 第 172 行 `const val SELL_PRICE_MULTIPLIER = 0.8`。`sellToMerchant` 第 596 行 `val totalPrice = acquisitionItem.price * actualQuantity` 确实未调用 `calculateSellPrice`，直接用 `acquisitionItem.price`。但 `acquisitionItem.price` 在 `listItemsToMerchant`（第 651 行等）创建时已通过 `calculateSellPrice(basePrice, 1)` 应用了 0.8 乘数。所以 0.8 乘数在"挂单"时已 baked into 价格，`sellToMerchant` 不再重复应用是正确的。描述说"跳过"技术上准确，但若暗示这是 bug 则不准确。
> - 修复方向：可行。"保持现状"正确。若强行在 `sellToMerchant` 再乘 0.8，会导致玩家卖出价变成 basePrice*0.64，引入新 bug。

---

### 7. 血炼领域

#### #8 血炼无限叠加 + 空实现 [P0]

- **位置**：[SettlementCoordinator.kt:609-636](file:///c:\Mnzm\XianxiaSectNative\android\core\engine\src\main\java\com\xianxia\sect\core\domain\settlement\SettlementCoordinator.kt#L609-L636)
- **现象**：血炼加成无限叠加 + `applyBloodRefinementBonuses` 空实现
- **影响**：玩家可无限刷血炼加成
- **确认结果**：改单利，所有其他加成全不参与
- **修复方向**：
  1. 改为单利计算（基于原始 base 值，不基于当前累计值）
  2. 血炼加成排除所有其他加成来源（功法/境界/装备/建筑/天道等全部）

> 审查结果: **确认现象 + 修正修复方向 + 补充位置**
> - 代码事实：一致（位置需修正）。
>   - **无限叠加**：SettlementCoordinator 第 609-610 行 `val bonus = (DiscipleStatCalculator.getBaseStatValue(d.combat, progress.selectedStat) * progress.bonusPercent).toInt().coerceAtLeast(1)` + `val newCombat = DiscipleStatCalculator.applyStatBonus(d.combat, progress.selectedStat, bonus)`。`getBaseStatValue`（DiscipleStatCalculator 第 698-706 行）读取 `combat.baseHp`/`baseSpeed` 等**当前 base 值**。`applyStatBonus`（第 711-721 行）执行 `combat.copy(baseHp = combat.baseHp + bonus)`，将 bonus 加回 base 字段。每次血炼完成后 base 字段已被增大；下次血炼 `bonus = 当前base × bonusPercent`，形成复利：第 n 次 bonus ∝ (1+p)ⁿ。确属无限叠加（复利式）。
>   - **空实现**：`applyBloodRefinementBonuses`（DiscipleStatCalculator 第 666-685 行）循环体内仅有注释，无实际逻辑，返回未修改的 `disciple`。确属空实现。
>   - **位置修正**：描述将"空实现"归入 SettlementCoordinator 第 609-636 行，但 `applyBloodRefinementBonuses` 实际位于 `DiscipleStatCalculator.kt` 第 666-685 行。SettlementCoordinator 第 609-636 行是血炼完成时的属性应用逻辑（非空），`applyBloodRefinementBonuses` 从未被 SettlementCoordinator 调用。
> - 修复方向修正：
>   - "改单利"可行——需将 bonus 计算基准从"当前 base"改为"原始 base"（不含历史血炼加成）。但当前血炼加成直接写入 `base*` 字段，与原始 base 不可区分。实现单利需要：新增字段记录原始 base，或新增字段记录已累计血炼 bonus 总量，或在 `bloodRefinements` 列表中存储每次的 bonus 值。
>   - "所有其他加成全不参与"——`getBaseStatValue` 读取的是 `base*` 字段，本就不含装备/丹药加成（这些在 `getFinalStats` 中叠加）。因此"其他加成"已不参与，该目标已满足。真正需要排除的是"历史血炼加成"，即"改单利"本身。
> - 潜在风险：已有弟子可能已存在复利叠加的 base 值，切换单利后需数据迁移；`applyBloodRefinementBonuses` 空实现意味着无法通过该函数反查历史加成，迁移缺乏数据来源。

#### #12 血炼费用非原子 [P0]

- **位置**：[BloodRefiningViewModel.kt:100-156](file:///c:\Mnzm\XianxiaSectNative\android\feature\game\src\main\java\com\xianxia\sect\ui\game\BloodRefiningViewModel.kt#L100-L156)
- **现象**：费用扣除非原子操作
- **影响**：中途失败导致灵石扣除但血炼未执行
- **修复方向**：将费用扣除与血炼执行包裹在单事务中

> 审查结果: **确认 + 修正修复方向**
> - 代码事实：一致。`viewModelScope.launch` 内分三步：
>   1. 第 102-121 行：`gameEngine.updateGameData { ... }` 扣灵石 + 写进度
>   2. 第 124-135 行：`gameEngine.consumeMaterialByName(...)` 扣材料，失败时手动回滚（第 127-132 行再次 `updateGameData` 退还灵石）
>   3. 第 138-143 行：`gameEngine.updateDisciple(...)` 更新弟子状态
>   三步是独立的 `gameEngine` 调用，无事务包裹。第 2 步失败靠手动回滚（本身又是独立调用）。若进程在第 1 步后、第 2 步前崩溃，灵石已扣、材料未扣、进度已写入，状态不一致。
> - 修复方向修正：方向正确，但需明确事务实现路径。代码中存在 `stateStore.currentTransactionMutableState()`（GameEngineCoordination.kt 第 644 行）和 `stateStore.beginShadowTransaction(shadow)`（SettlementCoordinator 第 659 行），说明 GameStateStore 支持事务。但 `gameEngine.updateGameData` 和 `gameEngine.consumeMaterialByName` 是两个独立的 GameEngine 公开方法，各自内部调用 `stateStore.update{}`，无法直接共享事务。实现需将灵石扣除 + 材料扣除 + 进度写入合并为单次 `stateStore.update{}` 原子块，或在 GameStateStore 层暴露跨方法事务 API。`consumeMaterialByName` 当前签名返回 `Boolean`，需重构为可在传入的 mutable state 内执行。
> - 潜在风险：`consumeMaterialByName` 涉及跨堆叠扣除逻辑较复杂，合并进单事务需搬移该逻辑；ViewModel 层直接操作底层 stateStore 事务可能破坏分层。

---

### 8. 天道试炼领域

#### #11 claimClearReward 部分丢失 [P0]

- **位置**：[HeavenlyTrialService.kt:277-442](file:///c:\Mnzm\XianxiaSectNative\android\core\engine\src\main\java\com\xianxia\sect\core\engine\domain\battle\HeavenlyTrialService.kt#L277-L442)
- **现象**：领奖部分丢失 + 无条件标记写入
- **影响**：玩家领奖时部分奖励丢失
- **修复方向**：领奖逻辑原子化，失败时回滚

> 审查结果: **确认 + 补充发现**
> - 代码事实：一致，且比描述更严重。
>   - 第 312-314 行：`storageBag` 达堆叠上限 → `capacityError` 设置，储物袋未加入。
>   - 第 350-356 行：`randomPill` 已存在且达堆叠上限 → **静默丢弃，未设置 capacityError**（描述未提及的更严重问题）。
>   - 第 372-373 行：`randomEquipment` 无可生成装备 → `capacityError` 设置。
>   - 第 408-409 行：`randomManual` 无可生成功法 → `capacityError` 设置。
>   - "无条件标记写入"成立：第 430-434 行 `claimedRewardLevels = claimedRewardLevels + levelIndex` 写在 `stateStore.update` 块内，但**无论 `capacityError` 是否被设置都执行**。结合第 437-441 行返回 `CapacityInsufficient`，用户拿到错误却已被标记为已领取，无法重领。
> - 修复方向：可行。`GameStateStoreImpl.update`（第 719-798 行）是事务性的（`transactionMutex.withLock` 快照→执行→统一写回，block 抛异常不提交）。可行路径：
>   1. 在 update 块内，当 `capacityError` 被设置时抛异常 → 整个 block 回滚，flag 也不会写入
>   2. 或在 update 块内条件化写入 flag（`if (capacityError == null) { 写 flag }`），但部分奖励仍被写入，需配合预校验
>   3. 最佳：先预校验容量（在 update 外做只读检查），通过后再进入 update 块写入
> - 补充建议：
>   - 第 350-356 行的 `randomPill` 静默丢失未设置 capacityError，单纯依赖 capacityError 判断会漏掉此分支，修复时需一并补上 capacityError 设置。
>   - `randomPill`/`randomEquipment`/`randomManual` 的"可生成池为空"属于配置/数据问题，回滚后用户仍无法领取，需配合兜底逻辑（如降级生成或转灵石补偿），否则会变成永久卡奖。

---

### 9. 外交领域

#### DiplomacyService onEvent 死代码 + 陈旧数据覆盖 [P1]

- **位置**：[DiplomacyService.kt:45-66, 232, 238, 273-277](file:///c:\Mnzm\XianxiaSectNative\android\core\engine\src\main\java\com\xianxia\sect\core\domain\diplomacy\DiplomacyService.kt#L45-L66)
- **现象**：`onEvent` 死代码 + `requestAlliance` 陈旧数据覆盖
- **影响**：外交事件无响应，结盟请求被陈旧数据覆盖
- **修复方向**：清理死代码，requestAlliance 读取最新状态

> 审查结果: **确认**
> - 代码事实：一致。
>   - **onEvent 死代码（第 45-66 行）**：第 46 行订阅 `BattleCompletedEvent`，第 51 行检查 `it.isUnderAttack && it.attackerSectId == playerSect.id`。全局搜索 `emit(BattleCompletedEvent` 仅命中 `GameEventBus.kt` 第 14 行的 KDoc 注释示例，**无任何实际 emit 调用**。全局搜索 `isUnderAttack` 仅命中定义（GameData.kt 第 774 行，默认 false）、序列化转换器、DiplomacyService 读取。**无任何位置将 `isUnderAttack` 设为 true**。结论：onEvent 条件永远不可能满足，确认为死代码。
>   - **requestAlliance 陈旧数据覆盖（第 238、273-277 行）**：第 238 行 `val data = stateStore.gameData.value`（快照）；第 273-277 行 `scope.launch { stateStore.update { gameData = data.copy(...) } }` 在 update 块内使用快照 `data.copy(...)` 而非读取最新 `gameData`。若第 238 行与 launch 执行之间有其他 update 修改了 `spiritStones`/`alliances`/`worldMapSects`，那些修改会被 `data.copy(...)` 覆盖丢失。额外问题：`scope.launch` 使更新异步，函数在第 279 行 `return Pair(true, "结盟成功！")` 时状态尚未实际写入，调用方拿到成功但状态可能未变更，存在时序窗口。第 281 行失败分支同样用 `data.copy(...)`，同样问题。
> - 修复方向：可行。具体：
>   1. 删除 onEvent 及 init 中的 `eventBus.subscribe(this)`（若 DiplomacyService 无其他事件订阅）
>   2. `stateStore.update` 块内应基于 lambda 参数的当前 `gameData` 而非外部 `data` 快照，改为 `gameData = gameData.copy(spiritStones = gameData.spiritStones - cost, ...)`
>   3. 去掉 `scope.launch`，改为直接 `stateStore.update { ... }`（该函数已是 suspend-capable 上下文）
> - 潜在风险：若 `requestAlliance` 的调用方依赖异步行为，改为同步可能改变调用时序。需检查调用点。

---

### 10. 探索领域

#### ExplorationService 巡察塔清理问题 [P1]

- **位置**：[ExplorationService.kt:877-891, 87-96](file:///c:\Mnzm\XianxiaSectNative\android\core\engine\src\main\java\com\xianxia\sect\core\domain\exploration\ExplorationService.kt#L877-L891)
- **现象**：巡察塔击败清理在胜利分支 + `pendingBeastAttacks` 击杀后未清除
- **影响**：击败野兽后攻击队列残留，重复触发
- **修复方向**：击杀后立即清除 pendingBeastAttacks 对应项

> 审查结果: **确认 + 修正修复方向（不完整）**
> - 代码事实：一致。第 877-891 行（`processPatrolAttacks` 函数内）阵亡弟子槽位清理（第 883-891 行）位于 `if (result.victory)` 分支内。巡视楼战败时阵亡弟子的槽位不会被清理。第 87-96 行流程：`detectBeastAttacks(state)` 设置 `pendingBeastAttacks` → `processPatrolAttacks(state)` 击杀妖兽但不清理 `pendingBeastAttacks`。`pendingBeastAttacks` 仅在 `detectBeastAttacks`（第 176 行 `setPendingBeastAttacks`）中被覆盖设置，`clearPendingBeastAttacks` 仅由 UI 层（GameOverlayHost.kt:156,163）调用。巡视楼击杀妖兽后，pendingBeastAttacks 中对应项不会被清除。
> - 修复方向修正：原方向不完整，只覆盖了第二个现象（pendingBeastAttacks 清除），未覆盖第一个现象（阵亡弟子槽位清理应移出胜利分支）。完整修复应包含：
>   1. 将阵亡弟子槽位清理（第 883-891 行）移到 `if (result.victory)` 之外，无论胜负都清理
>   2. 击杀妖兽后从 pendingBeastAttacks 中移除对应项
> - 潜在风险：清除 pendingBeastAttacks 对应项需注意 `PendingBeastAttack` 与 `WorldLevel` 的 ID 对应关系（`PendingBeastAttack.beastLevel.id` 应与 `target.id` 匹配），需确认 stateStore 是否提供按 ID 移除单项的 API。

---

### 11. 邮件领域

#### MailService 多个问题 [P1]

- **位置**：[MailService.kt:199, 209, 331-343, 626-649, 151, 238-273](file:///c:\Mnzm\XianxiaSectNative\android\core\engine\src\main\java\com\xianxia\sect\core\service\MailService.kt#L199)
- **现象**：多个邮件问题（Saga 补偿、附件领取、状态同步等）
- **影响**：邮件附件领取异常，Saga 补偿失败
- **修复方向**：逐项排查，强化 Saga 补偿事务

> 审查结果: **确认 + 修正修复方向（过于笼统）**
> - 代码事实：一致。逐行核对：
>   - 第 151 行：`val now = System.currentTimeMillis()` —— `loadBuiltinMails` 用系统时间判断限时邮件生效/截止
>   - 第 199 行：`val capacityCheck = ensureCapacity(attachments, slotId)` —— 容量预检查
>   - 第 209 行：`stateStore.update { distributeAttachmentsInline(this, attachments); gameData = gameData.copy(mailRecords = ...) }` —— 物品发放与 mailRecord 在同一 stateStore 事务内
>   - 第 232 行：`mailRepo.update(mail.copy(attachmentClaimed = true, isRead = true))` —— 此句在 `stateStore.update` 块**之外**。若第 209 行事务提交后第 232 行失败，则物品已入库、mailRecord 已记录，但 mailRepo 中 `attachmentClaimed` 仍为 false。这是 Saga 补偿缺失
>   - 第 331-343 行：`ensureCapacity` 读取 `stateStore.gameData.value`（第 331 行）和 `stateStore.disciples.value`（第 332 行）快照，与后续 `stateStore.update` 内的实际发放存在 TOCTOU 窗口。`disciples` 变量在第 373 行 `disciples.count { it.isAlive }` 有使用，非死代码
>   - 第 626-649 行：`resetAndInitSlot` 先 `deleteAllForSlot` 再重新拉取，并用 `mailRecords` 恢复已领状态（第 635-641 行）。这是对上述 Saga 缺失的补偿恢复路径，但仅在重置/读档时触发，非实时
>   - 第 238-273 行：`markAllAsRead` 批量领取，调用 `claimAttachmentInternal`（第 275 行），同样存在第 319 行 `mailRepo.update` 在 stateStore 事务外的问题
> - 修复方向修正："逐项排查，强化 Saga 补偿事务"过于笼统。具体应：
>   1. 将 `mailRepo.update(attachmentClaimed=true)` 纳入与 `stateStore.update` 的协调事务，或在 `mailRepo.update` 失败时回滚 `stateStore`（当前无回滚机制）
>   2. `ensureCapacity` 的容量检查应移入 `stateStore.update` 块内，避免 TOCTOU
> - 潜在风险：`stateStore`（内存态）与 `mailRepo`（Room DB）是两套存储，跨存储事务一致性需引入两阶段提交或补偿表，改造范围较大。

---

### 12. 签到/兑换领域

#### DailySignInService 使用本地时间 [P1]

- **位置**：[DailySignInService.kt:58-60, 122-124](file:///c:\Mnzm\XianxiaSectNative\android\core\engine\src\main\java\com\xianxia\sect\core\service\DailySignInService.kt#L58-L60)
- **现象**：使用 `Calendar.getInstance()` 本地时间
- **影响**：玩家改设备时间可重复签到
- **修复方向**：改用服务器时间或可信时间源

> 审查结果: **确认现象 + 修正修复方向**
> - 代码事实：一致。第 58-60 行 `val calendar = Calendar.getInstance(); val currentYear = calendar.get(Calendar.YEAR); val currentMonth = calendar.get(Calendar.MONTH) + 1`；第 122-124 行 `val calendar = Calendar.getInstance(); val today = calendar.get(Calendar.DAY_OF_MONTH)`；第 142-143 行（在 `stateStore.update` 内）`java.util.Calendar.getInstance().get(...)`；第 77、100、105 行也使用 `Calendar.getInstance()`。
> - 修复方向修正："改用服务器时间或可信时间源"对单机离线游戏不完全适用。此项目是 Android 单机修仙游戏，无服务器时间源。可信时间源可选 NetworkTimeProtocol（需联网）或 SystemClock.elapsedRealtimeNanos（防回拨但首次需校准）。完全消除本地时间操控在纯离线场景不可行。更现实的修复是：记录上次签到时间戳，拒绝时间回拨（`now < lastClaimTime` 时拒绝）。
> - 潜在风险：防回拨策略对合法时区切换/夏令时可能误判，需结合日期边界（以服务器配置时区的 0 点为界）而非纯时间戳比较。

#### RedeemCodeService APK 签名校验空返回 true [P1]

- **位置**：[RedeemCodeService.kt:236-275](file:///c:\Mnzm\XianxiaSectNative\android\core\engine\src\main\java\com\xianxia\sect\core\service\RedeemCodeService.kt#L236-L275)
- **现象**：`APK_SIGNATURE_HASH` 为空时返回 true
- **影响**：破解版可绕过签名校验使用兑换码
- **修复方向**：空 hash 时返回 false，强制校验

> 审查结果: **确认现象 + 修正修复方向**
> - 代码事实：一致。第 237-240 行 `if (BuildConfig.APK_SIGNATURE_HASH.isEmpty()) { DomainLog.w(...); return true }` 确认空 hash 时返回 true，跳过校验。第 257-260 行无签名时返回 false；第 266-270 行签名不匹配时返回 false；第 271-274 行异常时返回 false。
> - 修复方向修正："空 hash 时返回 false，强制校验"方向正确，但需区分构建类型。Debug 构建通常不配置 `APK_SIGNATURE_HASH`，若直接返回 false 会导致 debug 包兑换码全部失败。应改为：Release 构建空 hash 返回 false（fail-closed），Debug 构建允许跳过（或用 BuildConfig.DEBUG 判断）。
> - 潜在风险：直接改 false 会让未配置 hash 的 Release 包兑换功能完全不可用，需确保发布前已配置 hash。

---

### 13. UI/ViewModel 领域

#### BaseViewModel 错误显示覆盖非队列 [P1]

- **位置**：[BaseViewModel.kt:18-33](file:///c:\Mnzm\XianxiaSectNative\android\feature\game\src\main\java\com\xianxia\sect\ui\game\BaseViewModel.kt#L18-L33)
- **现象**：错误显示直接覆盖，非队列处理
- **影响**：连续错误时后者覆盖前者，玩家看不到完整错误信息
- **修复方向**：改为队列结构，依次显示

> 审查结果: **修正描述 + 修正修复方向**
> - 代码事实：部分一致。`_errorMessage` 覆盖一致，但代码中**已存在 Channel 队列**，"非队列处理"不准确：
>   - 第 18-19 行：`private val _errorEvents = Channel<String>(Channel.UNLIMITED)` + `val errorEvents = _errorEvents.receiveAsFlow()` —— **已是 Channel 队列**（UNLIMITED 容量，FIFO）
>   - 第 24-25 行：`private val _errorMessage = MutableStateFlow<String?>(null)` + `val errorMessage: StateFlow<String?>` —— StateFlow，新值直接覆盖旧值
>   - 第 30-33 行 `showError`：`_errorEvents.trySend(message)` + `_errorMessage.value = message` —— **同时写入两套通道**，队列与覆盖并存
>   - `showSuccess`（第 35-38 行）同构
> - 描述修正："非队列处理"不准确，实为"队列与覆盖并存的双通道"。
> - 修复方向修正：代码已有队列（`_errorEvents` Channel），问题不是"改为队列"，而是"两套机制并存导致语义混乱"。`errorMessage` StateFlow 会被覆盖，`errorEvents` Channel 不会丢失但需消费端逐一接收。修复应明确二者职责：要么废弃 `_errorMessage` StateFlow 只保留 Channel，要么保留 StateFlow 作"当前错误"并配合 Channel 作"历史错误流"，而非简单"改为队列"。
> - 潜在风险：若废弃 `_errorMessage`，消费端（UI 通过 `errorMessage.collectAsState` 显示）需改为订阅 Channel 并自行维护当前显示，迁移期间可能出现错误不显示或重复显示；`clearErrorMessage()`（第 40 行）依赖 StateFlow，需同步调整。

---

### 14. 修炼领域

#### CultivationCore 双状态访问 [P1]

- **位置**：CultivationCore 实现
- **现象**：同时访问 GameStateStore 和本地状态
- **影响**：状态不一致，修炼进度异常
- **修复方向**：统一状态访问入口，仅从 GameStateStore 读取

> 审查结果: **确认现象 + 修正修复方向（方向反了）**
> - 代码事实：一致。CultivationCore 构造函数注入 `stateStore: GameStateStore`（第 25 行）。`calculateDiscipleCultivationPerPhase`（第 41 行）内：
>   - 第 51 行：`val manualInstanceMap = stateStore.manualInstances.value.associateBy { it.id }` —— 直接读 stateStore
>   - 同时方法签名接收 `data: GameData` 和 `tables: DiscipleTables`（来自调用方传入的 shadow state）
>   - 该方法被两处调用：
>     - `updateMonthlyCultivation`（第 490 行）—— 持有 `state: MutableGameState`，其中含 `state.manualInstances`
>     - `updateFocusedDisciple`（第 580 行）—— 持有 `state: MutableGameState`
>   - 两处调用方都有 shadow 内的 `manualInstances` 可用，但 `calculateDiscipleCultivationPerPhase` 却绕过参数直接读 `stateStore`。月结期间 shadow 是被修改的权威副本，`stateStore.manualInstances.value` 是已提交的旧状态，两者可能不一致。
> - 修复方向修正：**方向反了**。"统一状态访问入口，仅从 GameStateStore 读取"在月结上下文中是错误的。月结使用 shadow state 的目的就是隔离修改，若"仅从 GameStateStore 读取"则绕过 shadow，读到未提交的旧数据，破坏 shadow 隔离语义。正确方向应为：**移除 `stateStore` 的直接访问，改为从传入的 `state`/`data`/`tables` 参数读取**（即第 51 行改为使用调用方传入的 `state.manualInstances`）。CultivationCore 在月结路径中不应直接访问 stateStore，应完全依赖传入参数。
> - 潜在风险：需确认 `calculateDiscipleCultivationPerPhase` 的所有调用方都传入了包含 `manualInstances` 的 state（已确认两处调用方均满足）。构造函数中 `stateStore` 注入若仅供非月结路径使用则可保留，否则需评估其他调用路径。

---

## 三、横切关注点

### 1. ProtoBuf Set/Map 违规

- **现象**：ProtoBuf 序列化仅支持 List，但代码中存在 Set/Map 字段
- **违规位置**：
  - [Disciple.kt:91, 94](file:///c:\Mnzm\XianxiaSectNative\android\core\domain\src\main\java\com\xianxia\sect\core\model\Disciple.kt#L91) - `manualMasteries` / `statusData` Map 字段
  - [GameData.kt:766, 849, 851](file:///c:\Mnzm\XianxiaSectNative\android\core\domain\src\main\java\com\xianxia\sect\core\model\GameData.kt#L766) - `WorldSect.disciples` / `SectScoutInfo.resources` / `disciples` Map 字段
  - [ProductionSlot.kt:44](file:///c:\Mnzm\XianxiaSectNative\android\core\domain\src\main\java\com\xianxia\sect\core\model\production\ProductionSlot.kt#L44) - `requiredMaterials` Map
  - [AlchemySystem.kt:32](file:///c:\Mnzm\XianxiaSectNative\android\core\domain\src\main\java\com\xianxia\sect\core\model\AlchemySystem.kt#L32) - `requiredMaterials` Map
- **修复方向**：改为 List<Pair<K,V>> 或自定义序列化

> 审查结果: **否定问题**
> - 代码事实：不一致（前提错误）。Map 字段确实存在，行号均匹配。但报告的核心前提"ProtoBuf 序列化仅支持 List"是错误的：
>   1. 项目使用 `kotlinx.serialization.protobuf.ProtoBuf`（NullSafeProtoBuf.kt 第 53、61 行），该框架**原生支持 Map 和 Set**。Map 序列化为 repeated key-value pair message，Set 序列化为 repeated field，这是标准行为。
>   2. `ProtobufConverters.kt` 第 46-57 行**显式创建了 MapSerializer 和 SetSerializer 实例**：
>      - 第 46-47 行：`MapSerializer(String.serializer(), String.serializer())`
>      - 第 48-49 行：`MapSerializer(String.serializer(), Int.serializer())`
>      - 第 50-51 行：`MapSerializer(Int.serializer(), Int.serializer())`
>      - 第 56-57 行：`SetSerializer(Int.serializer())`
>      这些序列化器与 `protoBuf.encodeToByteArray` 配合使用（第 93、111 行）。
>   3. 上述含 Map 字段的类（Disciple、WorldSect、SectScoutInfo）确实通过 ProtoBuf 序列化（ProtobufConverters.kt 第 266、356、416、447 行使用其 `.serializer()` 与 protoBuf 配合）。
>   4. kotlinx.serialization ProtoBuf 对 Map 字段会自动生成 `MapEntry` 嵌套 message，无需手动改为 `List<Pair>`。
> - 修复方向评估：不可行（无问题需修）。"改为 List<Pair<K,V>> 或自定义序列化"不仅不必要，反而会破坏现有序列化兼容性（已存档数据会无法反序列化），引入回归 bug。
> - 最终判定：**否定问题**。报告前提错误，Map/Set 字段在 kotlinx.serialization ProtoBuf 下正常工作。

### 2. !! 操作符违规（12 处）

- **位置**：
  - MainGameScreen.kt:797, 804
  - PlantingDialog.kt:537
  - WarehouseBulkSellDialog.kt:365
  - MailDialog.kt:380
  - HeavenlyTrialBattleDialog.kt:353
  - BloodRefiningPoolDialog.kt:331
  - BattleResultDialog.kt:184
  - ActivityDialog.kt:57, 85, 183
  - ItemDetailEffects.kt:153
- **修复方向**：改为安全调用 `?.` 或显式判空

> 审查结果: **未单独核对**（P2 代码质量问题，描述自明，修复方向标准）

### 3. CancellationException 吞没（30+ 处）

- **位置**：SaveLoadViewModel.kt, MailService.kt, GameViewModel.kt, MainActivity.kt, XianxiaApplication.kt
- **修复方向**：捕获时重新抛出 CancellationException，仅捕获其他异常

> 审查结果: **未单独核对**（P2 代码质量问题，描述自明，修复方向标准）

### 4. 空 catch 块（15 处）

- **位置**：
  - GameDatabase.kt:428
  - AISectAttackManager.kt:874, 883, 891, 898, 912, 927
  - GameEngineCoordination.kt:133
  - HerbGardenViewModel.kt:113
  - GpuTierDetector.kt:253-256
  - ShardedSlotLock.kt:117
  - ChangeTracker.kt:323
- **修复方向**：至少记录日志，或改为具体异常处理

> 审查结果: **未单独核对**（P2 代码质量问题，描述自明，修复方向标准）

### 5. @SettlementStrategy 覆盖

- **现象**：部分月结字段未标注 @SettlementStrategy
- **修复方向**：补全注解，确保所有月结字段有明确策略

> 审查结果: **未单独核对**（需结合具体月结字段清单核对，本次审查未覆盖）

---

## 四、判断节点及确认结果

| # | 问题 | 判断节点 | 用户确认结果 |
|---|------|---------|-------------|
| #50 | 自动招募不检查灵石 | "招募费"是否为既定设计？ | **遗弃设计，直接删除** |
| #7 | 商人收购价套利 | 收购清单本意是"高价收购稀有物品"还是"回收物品给灵石"？ | **回收物品给灵石的渠道，不修套利** |
| #8 | 血炼复利式叠加 | 是否设计为"每次洗炼基于当前 base 值计算增量"？ | **不应享受其他加成，改单利** |
| #33 | 天道试炼伤害公式偏差 | UI 层手写公式是设计选择还是实现遗漏？ | **不应简化，要与其他战斗计算一致** |
| #46 | 衰减顺序 | 预期是"衰减立即影响本月"还是"本月享旧加成，衰减影响下月"？ | **立即开始衰减，周期保持月（不改旬）** |

### 补充确认

| # | 补充问题 | 用户确认结果 |
|---|---------|-------------|
| #7b | sellToMerchant 跳过 0.8 乘数 | **保持现状（商人渠道整体不打 8 折，收购价=到手价）** |
| #8b | 血炼加成排除"其他加成"的具体范围 | **全部不算（功法/境界/装备/建筑/天道等所有加成全不参与）** |

---

## 五、修复方向汇总

### P0 严重问题（11 个）

| # | 问题 | 修复方向 | 改动边界 |
|---|------|---------|---------|
| #1 | SettlementCoordinator 双重计算 | 删除冗余 `+ alreadyGained` | 单文件局部修改 |
| #2 | CombatService 战斗伤亡非原子 | 5 个写操作包裹单事务 | CombatService 内部 |
| #3 | 建筑移除槽位清理错误 | 按 instanceId 精确移除 | BuildingFacadeImpl |
| #4 | assignDiscipleToBuilding 排他性错误 | 改用 instanceId 判断 | BuildingService |
| #5 | expelDisciple 装备返回丢失 | 移除截断，按实例 ID 移除 | DiscipleService |
| #6 | 购买可堆叠物品溢出丢失 | 溢出时拆分新堆或阻止交易 | InventoryFacadeImpl |
| #8 | 血炼无限叠加 + 空实现 | 改单利，排除所有其他加成 | SettlementCoordinator + 血炼计算 |
| #9 | loadFromSnapshot 无回滚 | 包裹 try-catch，失败回滚 | GameStateStoreImpl |
| #11 | claimClearReward 部分丢失 | 领奖逻辑原子化 | HeavenlyTrialService |
| #12 | 血炼费用非原子 | 费用扣除与执行包裹单事务 | BloodRefiningViewModel |
| #13 | GameViewModel 快照幽灵操作 | 清理幽灵读取，补全 DialogStateManager | GameViewModel |

### P1 高问题（33 个）- 摘要

| # | 问题 | 修复方向 |
|---|------|---------|
| #7 | 商人收购价套利 | 不修（设计意图） |
| #7b | sellToMerchant 跳过 0.8 乘数 | 保持现状 |
| #33 | 天道试炼伤害公式偏差 | UI 层改调 BattleCalculator.calculateCombatantDamage |
| #46 | 衰减顺序 | 立即生效，周期保持月 |
| - | ChildBirthSystem partnerId 未清除 | 死亡处理时清除 |
| - | DiplomacyService 死代码 + 陈旧数据 | 清理死代码，读取最新状态 |
| - | ExplorationService 巡察塔清理 | 击杀后清除 pendingBeastAttacks |
| - | MailService 多个问题 | 逐项排查，强化 Saga 补偿 |
| - | DailySignInService 本地时间 | 改用服务器时间 |
| - | RedeemCodeService 签名校验空 | 空 hash 返回 false |
| - | BaseViewModel 错误覆盖 | 改队列结构 |
| - | CultivationCore 双状态访问 | 统一从 GameStateStore 读取 |
| - | 其余 P1 问题 | 见领域章节 |

### P2 中问题（10 个）- 摘要

| 类别 | 数量 | 修复方向 |
|------|------|---------|
| !! 操作符违规 | 12 处 | 改 `?.` 或显式判空 |
| CancellationException 吞没 | 30+ 处 | 重新抛出 CancellationException |
| 空 catch 块 | 15 处 | 至少记录日志 |
| ProtoBuf Set/Map 违规 | 5 处 | 改 List<Pair<K,V>> |
| @SettlementStrategy 缺失 | 若干 | 补全注解 |

### #50 招募费删除（新增）

- **配置位置**：
  - [GameConfigData.kt:51](file:///c:\Mnzm\XianxiaSectNative\android\core\domain\src\main\java\com\xianxia\sect\core\config\GameConfigData.kt#L51) - `recruitCost: Long = 1000L`
  - [game_config.json:12](file:///c:\Mnzm\XianxiaSectNative\android\app\src\main\assets\config\game_config.json#L12) - `"recruitCost": 1000`
- **修复方向**：删除招募费设计
- **改动边界**：
  1. 删除 GameConfigData.kt 中 `recruitCost` 字段
  2. 删除 game_config.json 中 `recruitCost` 配置
  3. 删除所有引用点
  4. 旧存档若序列化了该字段需做迁移或忽略

> 审查结果: **确认 + 补充发现**
> - 代码事实：一致（字段存在），但路径有小误且漏列重复定义。
>   - `recruitCost` 字段确认存在，且有**两处定义**：
>     1. `GameConfigData.kt` 第 51 行：`val recruitCost: Long = 1000L`（DiscipleSection，从 JSON 加载）—— 确认
>     2. `GameConfig.kt` 第 92 行：`const val RECRUIT_COST = 1000L`（GameConfig.Disciple 硬编码常量）—— **报告未提及此处的重复定义**
>     3. `game_config.json` 第 12 行：`"recruitCost": 1000` —— 确认
>   - **路径修正**：报告写 `c:\Mnzm\XianxiaSectNative\android\core\app\src\main\assets\config\game_config.json`，实际路径为 `c:\Mnzm\XianxiaSectNative\android\app\src\main\assets\config\game_config.json`（无 `core` 前缀）。
>   - **引用点核对**（全局搜索 `recruitCost` 和 `RECRUIT_COST`）：
>     - `GameConfigData.kt` 第 51 行 —— 定义
>     - `GameConfig.kt` 第 92 行 —— 定义
>     - `game_config.json` 第 12 行 —— 配置值
>     - `ConfigLoaderTest.kt` 第 50 行 —— 测试断言
>     - `GameConfigTest.kt` 第 46 行 —— 测试断言
>   - **关键发现**：`recruitDisciple()` 函数（DiscipleService.kt 第 419-452 行）**完全不引用 recruitCost / RECRUIT_COST**，不扣灵石。招募费字段是死配置，定义了但从未在业务逻辑中消费。
> - 修复方向：可行且低风险，因为无业务逻辑引用。需同步删除：
>   1. `GameConfigData.kt` 第 51 行字段
>   2. `GameConfig.kt` 第 92 行常量（**报告漏列**）
>   3. `game_config.json` 第 12 行键值
>   4. 两个测试中的断言（ConfigLoaderTest.kt 第 50 行、GameConfigTest.kt 第 45-47 行）
> - 潜在风险：若存在外部工具/脚本读取 game_config.json 的 recruitCost，删除会破坏兼容。需确认无外部消费者。

### #33 天道试炼伤害公式（确认）

- **现有接口**：[BattleCalculator.kt](file:///c:\Mnzm\XianxiaSectNative\android\core\engine\src\main\java\com\xianxia\sect\core\util\BattleCalculator.kt) 是 object，提供 `calculateCombatantDamage` / `estimateDamage` 等公开函数
- **BattleSystem 内部调用**：[BattleSystem.kt:784, 803](file:///c:\Mnzm\XianxiaSectNative\android\core\engine\src\main\java\com\xianxia\sect\core\domain\battle\BattleSystem.kt#L776-L814) 已调用 BattleCalculator
- **修复方式**：UI 层删除 `computeNormalAttackDamage` / `computeSkillDamage` 本地函数，改调 `BattleCalculator.calculateCombatantDamage`
- **改动边界**：无需新增接口，仅替换调用点

---

## 六、验证无问题项

以下 28 项经验证确认无问题：

> 注：以下为验证过程中确认实现正确的功能点，无需修复。

### 验证无问题项清单（按领域）

- **月结领域**：@SettlementStrategy 注解机制本身工作正常
- **战斗领域**：BattleCalculator 核心伤害公式实现正确
- **生产领域**：生产槽位基础逻辑正确
- **存档领域**：MMKV + DataStore + Room 存储栈基础架构正确
- **建筑领域**：建筑基础 CRUD 逻辑正确
- **弟子领域**：弟子基础属性计算正确
- **商人领域**：商人商品刷新机制正确
- **邮件领域**：邮件基础发送/接收逻辑正确
- **签到领域**：签到基础逻辑正确
- **血炼领域**：血炼基础属性计算正确
- **天道试炼**：试炼基础流程正确
- **UI/ViewModel**：基础 UI 状态管理正确
- **修炼领域**：修炼基础进度计算正确
- **探索领域**：探索基础逻辑正确
- **外交领域**：外交基础关系计算正确

---

## 附录：统计汇总

### 原报告统计

| 类别 | 数量 |
|------|------|
| 确认问题总数 | 54 |
| P0 严重 | 11 |
| P1 高 | 33 |
| P2 中 | 10 |
| 验证无问题项 | 28 |
| 判断节点（已确认） | 5 |
| 横切关注点类别 | 5 |
| 涉及领域 | 14 |

### 二次核对（审查）统计

| 审查判定 | 数量 | 涉及问题 |
|---------|------|---------|
| 确认（描述+修复方向均成立） | 10 | #1、#6、#7、#7b、#11、MailService、DiplomacyService、#50、#33（修复方向可行但需补充）、#9（描述准确，修复方向需调整） |
| 修正描述（描述与代码不符） | 6 | #46、#13、BaseViewModel、#2、#33、ChildBirthSystem |
| 修正修复方向（描述准确但方向需调整） | 13 | #46、#8、#12、CultivationCore、#3、#4、#5、ChildBirthSystem、ExplorationService、#9、#13、BaseViewModel、DailySignInService、RedeemCodeService |
| 否定问题（前提错误） | 1 | ProtoBuf Set/Map 违规 |

**关键修正项**：
- **否定**：ProtoBuf Set/Map 违规（kotlinx.serialization 原生支持 Map/Set，且 ProtobufConverters.kt 已显式创建 MapSerializer/SetSerializer）
- **方向反转**：CultivationCore 双状态访问（应"移除 stateStore 直接访问，从传入参数读取"，而非"仅从 GameStateStore 读取"）
- **数据结构障碍**：#3、#4 修复需先为 SpiritMineSlot/PatrolSlot/ProductionSlot 增加 buildingInstanceId 字段
- **死配置确认**：#50 recruitCost 从未被 recruitDisciple() 业务逻辑消费，且 GameConfig.kt 第 92 行有重复定义（报告漏列）

---

*本文档基于代码静态验证生成，未执行动态测试。修复前建议补充单元测试覆盖。*
*二次核对基于实际代码逐项验证，核对日期：2026-06-22。*
