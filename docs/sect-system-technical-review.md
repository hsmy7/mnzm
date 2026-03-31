# 门派系统技术审查报告

> 审查日期: 2026-03-30  
> 审查范围: 门派系统核心架构、AI系统、外交系统、战斗系统、数据模型

---

## 一、系统架构分析

### 1.1 核心模块分布

| 模块 | 文件路径 | 主要职责 |
|------|----------|----------|
| SectSystem | `core/engine/system/SectSystem.kt` | 门派数据、时间、资源、关系、联盟管理 |
| GameEngine | `core/engine/GameEngine.kt` | 游戏状态总控、弟子管理、事件系统 |
| DiplomacySubsystem | `core/engine/system/DiplomacySubsystem.kt` | 外交数据管理 |
| AISectAttackManager | `core/engine/AISectAttackManager.kt` | AI攻击决策 |
| AISectDiscipleManager | `core/engine/AISectDiscipleManager.kt` | AI弟子生成与培养 |
| WorldMapGenerator | `core/engine/WorldMapGenerator.kt` | 世界地图与宗门生成 |

### 1.2 数据流向

```
┌─────────────────────────────────────────────────────────────────┐
│                         GameEngine                               │
│  ┌─────────────────────────────────────────────────────────┐    │
│  │ _gameData: MutableStateFlow<GameData>                   │    │
│  │ _disciples: MutableStateFlow<List<Disciple>>            │    │
│  │ _equipment: MutableStateFlow<List<Equipment>>           │    │
│  └─────────────────────────────────────────────────────────┘    │
└─────────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────────┐
│                         SectSystem                               │
│  ┌─────────────────────────────────────────────────────────┐    │
│  │ _gameData: MutableStateFlow<GameData>  (独立副本)        │    │
│  └─────────────────────────────────────────────────────────┘    │
└─────────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────────┐
│                    DiplomacySubsystem                            │
│  ┌─────────────────────────────────────────────────────────┐    │
│  │ _alliances: MutableStateFlow<List<Alliance>>            │    │
│  │ _sectRelations: MutableStateFlow<List<SectRelation>>    │    │
│  │ _supportTeams: MutableStateFlow<List<SupportTeam>>      │    │
│  └─────────────────────────────────────────────────────────┘    │
└─────────────────────────────────────────────────────────────────┘
```

### 1.3 架构问题

#### 问题1: 状态管理分散

**位置**: `SectSystem.kt:30-32`, `GameEngine.kt:37-55`, `DiplomacySubsystem.kt:24-34`

**描述**: 三个独立的类各自维护 `MutableStateFlow`，数据存储位置分散：

```kotlin
// SectSystem.kt
private val _gameData = MutableStateFlow(GameData())

// GameEngine.kt  
private val _gameData = MutableStateFlow(GameData())
private val _disciples = MutableStateFlow<List<Disciple>>(emptyList())

// DiplomacySubsystem.kt
private val _alliances = MutableStateFlow<List<Alliance>>(emptyList())
private val _sectRelations = MutableStateFlow<List<SectRelation>>(emptyList())
```

**影响**:
- 数据同步依赖手动调用 `loadData()` 方法
- 存在数据不一致风险
- 状态更新顺序不可控

#### 问题2: 职责边界模糊

**位置**: `SectSystem.kt` 与 `GameEngine.kt`

**描述**: 两个类存在大量重叠职责：

| 功能 | SectSystem | GameEngine |
|------|------------|------------|
| 管理 GameData | ✓ | ✓ |
| 管理资源(灵石/灵草) | ✓ | ✓ |
| 管理联盟 | ✓ | ✓ |
| 管理宗门关系 | ✓ | ✓ |
| 管理弟子 | - | ✓ |
| 管理装备 | - | ✓ |

**影响**:
- 代码维护困难
- 调用方需要知道使用哪个类
- 未来扩展时职责归属不明确

---

## 二、数据模型分析

### 2.1 核心数据结构

#### GameData

**位置**: `core/model/GameData.kt:9-170`

```kotlin
@Entity(tableName = "game_data")
data class GameData(
    @PrimaryKey
    var id: String = "game_data",
    var sectName: String = "青云宗",
    
    // 游戏时间
    var gameYear: Int = 1,
    var gameMonth: Int = 1,
    var gameDay: Int = 1,
    
    // 资源
    var spiritStones: Long = 1000,
    var spiritHerbs: Int = 0,
    
    // 世界地图宗门
    var worldMapSects: List<WorldSect> = emptyList(),
    
    // 宗门间关系
    var sectRelations: List<SectRelation> = emptyList(),
    
    // 结盟关系
    var alliances: List<Alliance> = emptyList(),
    
    // ... 其他字段
)
```

**问题**: 单一实体包含过多字段（50+），违反单一职责原则。

#### WorldSect

**位置**: `core/model/GameData.kt:352-404`

```kotlin
data class WorldSect(
    val id: String = "",
    val name: String = "",
    val level: Int = 1,
    val x: Float = 0f,
    val y: Float = 0f,
    val isPlayerSect: Boolean = false,
    val isRighteous: Boolean = true,
    val disciples: Map<Int, Int> = emptyMap(),
    val aiDisciples: List<Disciple> = emptyList(),
    val allianceId: String? = null,
    val warehouse: SectWarehouse = SectWarehouse(),
    // ... 其他字段
)
```

**问题**: 
- `disciples` (Map) 与 `aiDisciples` (List) 字段语义重叠
- 包含运行时数据 (`connectedSects`) 与持久化数据混合

#### SectRelation

**位置**: `core/model/GameData.kt:476-482`

```kotlin
data class SectRelation(
    val sectId1: String,
    val sectId2: String,
    var favor: Int = INITIAL_SECT_FAVOR,
    var lastInteractionYear: Int = 0,
    var noGiftYears: Int = 0
)
```

**问题**: 
- 使用 `var` 声明可变属性，违反不可变性原则
- 关系数据过于简化，无法表达复杂外交状态

### 2.2 数据冗余问题

**位置**: `WorldSect.relation` 与 `SectRelation.favor`

```kotlin
// WorldSect 中
val relation: Int = 0,

// SectRelation 中
var favor: Int = INITIAL_SECT_FAVOR,
```

**影响**: 两处数据可能不同步，需要额外维护一致性。

### 2.3 数据类型问题

#### 溢出风险

**位置**: `SectSystem.kt:24-25`

```kotlin
private const val MAX_SPIRIT_STONES = Long.MAX_VALUE
private const val MAX_SPIRIT_HERBS = Int.MAX_VALUE
```

**问题**: 使用 `Long.MAX_VALUE` 作为上限，实际业务中难以处理溢出场景。

#### 类型不一致

**位置**: `GameData.kt`

```kotlin
var spiritStones: Long = 1000,    // Long 类型
var spiritHerbs: Int = 0,         // Int 类型
```

**影响**: 资源类型不一致，可能导致类型转换错误。

---

## 三、AI系统分析

### 3.1 决策系统

#### 攻击决策

**位置**: `AISectAttackManager.kt:56-78`

```kotlin
fun checkAttackConditions(attacker: WorldSect, defender: WorldSect, gameData: GameData): Boolean {
    if (attacker.id == defender.id) return false
    
    val attackerDisciples = attacker.aiDisciples.filter { it.isAlive }
    if (attackerDisciples.size < MIN_DISCIPLES_FOR_ATTACK) return false
    
    val relation = gameData.sectRelations.find { ... }
    val favor = relation?.favor ?: 0
    if (favor > 0) return false
    
    if (attacker.allianceId != null && attacker.allianceId == defender.allianceId) return false
    
    if (!isRouteConnected(attacker, defender, gameData)) return false
    
    val attackerPower = calculatePowerScore(attacker)
    val defenderPower = calculatePowerScore(defender)
    if (attackerPower < defenderPower * POWER_RATIO_THRESHOLD) return false
    
    return true
}
```

**问题**:
- 决策条件为硬编码布尔判断，无权重系统
- 缺乏决策优先级排序
- 无决策历史记录

#### 战力计算

**位置**: `AISectAttackManager.kt:94-102`

```kotlin
fun calculatePowerScore(sect: WorldSect): Double {
    val disciples = sect.aiDisciples.filter { it.isAlive }
    if (disciples.isEmpty()) return 0.0
    
    val avgRealm = disciples.map { it.realm }.average()
    val powerScore = disciples.size * (10.0 - avgRealm)
    
    return powerScore
}
```

**问题**:
- 仅考虑弟子数量和平均境界
- 未考虑: 装备品质、功法等级、天赋效果、战斗经验
- 计算公式 `disciples.size * (10.0 - avgRealm)` 中，境界越低数值越高，语义不明确

### 3.2 弟子管理系统

#### 弟子生成

**位置**: `AISectDiscipleManager.kt:34-94`

```kotlin
fun generateRandomDisciple(sectName: String, maxRealm: Int = 9): Disciple {
    val gender = if (Random.nextBoolean()) "male" else "female"
    val name = generateName(gender)
    val spiritRoot = generateSpiritRoot()
    val comprehension = Random.nextInt(30, 81)
    // ... 随机生成各项属性
    
    return Disciple(
        id = java.util.UUID.randomUUID().toString(),
        name = name,
        realm = 9,
        // ...
    ).apply {
        // 设置基础属性
    }
}
```

**问题**:
- 生成逻辑完全随机，无配置化支持
- 与宗门等级关联仅通过 `maxRealm` 参数
- 无弟子质量分布控制

#### 境界分布

**位置**: `AISectDiscipleManager.kt:397-413`

```kotlin
private fun generateRealmDistribution(total: Int, maxRealm: Int): Map<Int, Int> {
    val distribution = mutableMapOf<Int, Int>()
    var remaining = total
    
    val topCount = if (maxRealm == 1) Random.nextInt(1, 4) else Random.nextInt(1, 6)
    distribution[maxRealm] = topCount.coerceAtMost(remaining)
    remaining -= topCount
    
    for (realm in (maxRealm + 1)..9) {
        if (remaining <= 0) break
        val count = Random.nextInt(5, 21).coerceAtMost(remaining)
        distribution[realm] = count
        remaining -= count
    }
    
    return distribution
}
```

**问题**:
- 分布逻辑硬编码
- `maxRealm` 语义为"最高境界"，但循环 `(maxRealm + 1)..9` 生成的是更低境界弟子

### 3.3 缺失的AI系统

| 系统 | 状态 | 影响 |
|------|------|------|
| 经济系统 | 缺失 | AI宗门无资源产出/消耗逻辑 |
| 发展策略 | 缺失 | AI无长期发展目标 |
| 外交策略 | 缺失 | AI仅被动响应，无主动外交 |
| 防御策略 | 缺失 | AI无防御部署逻辑 |

---

## 四、外交系统分析

### 4.1 外交数据模型

**位置**: `GameData.kt:465-471`, `GameData.kt:476-482`

```kotlin
data class Alliance(
    val id: String = java.util.UUID.randomUUID().toString(),
    val sectIds: List<String> = emptyList(),
    val startYear: Int = 0,
    val initiatorId: String = "",
    val envoyDiscipleId: String = ""
)

data class SectRelation(
    val sectId1: String,
    val sectId2: String,
    var favor: Int = INITIAL_SECT_FAVOR,
    var lastInteractionYear: Int = 0,
    var noGiftYears: Int = 0
)
```

**问题**:
- `Alliance` 无期限字段，无法处理联盟到期
- `SectRelation` 仅存储好感度，缺乏信任、威胁等维度
- 无外交事件历史记录

### 4.2 外交操作

**位置**: `DiplomacyUseCase.kt`

```kotlin
fun giftSpiritStones(params: GiftSpiritStonesParams): GiftResult
fun giftItem(sectId: String, itemId: String, itemType: String, quantity: Int): GiftResult
fun requestAlliance(params: AllianceParams): AllianceResult
fun dissolveAlliance(sectId: String): AllianceResult
```

**问题**: 外交操作仅4种，缺乏：
- 宣战/停战
- 互不侵犯条约
- 贸易协定
- 附庸关系

### 4.3 好感度系统

**位置**: `WorldMapGenerator.kt:16`

```kotlin
const val INITIAL_SECT_FAVOR = 50
```

**位置**: `WorldMapGenerator.kt:522-528`

```kotlin
private fun calculateInitialFavor(sameAlignment: Boolean): Int {
    var favor = INITIAL_SECT_FAVOR
    if (sameAlignment) {
        favor += SAME_ALIGNMENT_BONUS
    }
    return favor.coerceIn(10, 90)
}
```

**问题**:
- 初始好感度固定为50
- 正邪同阵营加成固定为10
- 无动态因素影响（如距离、实力差距）

---

## 五、战斗系统分析

### 5.1 战斗数据模型

**位置**: `BattleSystem.kt:596-602`

```kotlin
data class Battle(
    val team: List<Combatant>,
    val beasts: List<Combatant>,
    val turn: Int = 0,
    val isFinished: Boolean = false,
    val winner: BattleWinner? = null
)
```

**位置**: `AISectAttackManager.kt:586-592`

```kotlin
data class AIBattle(
    val attackers: List<AICombatant>,
    val defenders: List<AICombatant>,
    val turn: Int = 0,
    val isFinished: Boolean = false,
    val winner: AIBattleWinner? = null
)
```

**问题**: 存在两套战斗数据模型 `Battle` 和 `AIBattle`，结构相似但独立定义。

### 5.2 战斗执行

**位置**: `AISectAttackManager.kt:364-422`

```kotlin
private fun executeAIBattleTurn(battle: AIBattle): AIBattle {
    val allCombatants = (battle.attackers + battle.defenders)
        .filter { !it.isDead }
        .sortedByDescending { it.speed }
    
    var attackers = battle.attackers.toMutableList()
    var defenders = battle.defenders.toMutableList()
    
    allCombatants.forEach { combatant ->
        if (combatant.isDead) return@forEach
        
        val targets = if (combatant.isAttacker) {
            defenders.filter { !it.isDead }
        } else {
            attackers.filter { !it.isDead }
        }
        
        if (targets.isEmpty()) { return battle.copy(...) }
        
        val target = targets.random()  // 随机选择目标
        
        // ... 执行攻击
    }
    
    return battle.copy(...)
}
```

**问题**:
- 目标选择完全随机，无战术逻辑
- 无技能使用策略
- 无撤退判定
- 无战斗日志详细记录

### 5.3 战斗结果处理

**位置**: `AISectAttackManager.kt:235-291`

```kotlin
fun executeAISectBattle(attackTeam: AIBattleTeam, defenderSect: WorldSect): AIBattleResult {
    // ... 执行战斗
    
    val highRealmDefendersAlive = defenderSect.aiDisciples
        .filter { it.isAlive && it.realm <= 5 }
        .none { it.id !in deadDefenderIds }
    
    return AIBattleResult(
        battle = finalBattle,
        winner = winner,
        deadAttackerIds = deadAttackerIds,
        deadDefenderIds = deadDefenderIds,
        canOccupy = winner == AIBattleWinner.ATTACKER && highRealmDefendersAlive
    )
}
```

**问题**:
- 占领条件仅检查高境界弟子是否存活
- 无战败赔偿计算
- 无领土割让逻辑

---

## 六、并发与线程安全

### 6.1 锁机制

**位置**: `SectSystem.kt:28`

```kotlin
private val mutex = Mutex()

suspend fun <T> withLock(block: suspend SectSystem.() -> T): T {
    return mutex.withLock { block() }
}
```

**位置**: `GameEngine.kt:97`

```kotlin
private val transactionMutex = Mutex()
```

**位置**: `ProductionSubsystem.kt:26`

```kotlin
private val updateMutex = Mutex()
```

**问题**:
- 多个独立的 Mutex，无法保证跨类操作的原子性
- `SectSystem` 的 `updateGameData` 方法未使用锁：

```kotlin
fun updateGameData(transform: (GameData) -> GameData) {
    _gameData.value = transform(_gameData.value)  // 无锁保护
}
```

### 6.2 StateFlow 更新

**位置**: `GameEngine.kt:187-270`

```kotlin
private inner class GameTransaction {
    private var gameDataSnapshot: GameData = _gameData.value
    // ... 其他快照
    
    fun commit(): List<Pair<String, EventType>> {
        _gameData.value = gameDataSnapshot
        _disciples.value = disciplesSnapshot
        // ... 批量更新
        return pendingEvents.toList()
    }
}
```

**问题**: 
- 快照读取与提交之间存在时间窗口
- 多个 StateFlow 更新非原子性

---

## 七、配置系统分析

### 7.1 配置结构

**位置**: `GameConfig.kt`

```kotlin
object GameConfig {
    object Game { ... }
    object Time { ... }
    object Cultivation { ... }
    object Rarity { ... }
    object Realm { ... }
    object SpiritRoot { ... }
    object Buildings { ... }
    object Beast { ... }
    object Dungeons { ... }
    object Starting { ... }
    object PlayerProtection { ... }
    object Performance { ... }
    object Logs { ... }
    object Battle { ... }
    object PolicyConfig { ... }
}
```

**问题**:
- 配置分散在多个嵌套对象中
- 缺乏配置验证机制
- 无法热更新配置

### 7.2 硬编码值

**位置**: `AISectAttackManager.kt:21-23`

```kotlin
private const val MIN_DISCIPLES_FOR_ATTACK = 10
private const val POWER_RATIO_THRESHOLD = 0.8
const val TEAM_SIZE = 10
```

**位置**: `WorldMapGenerator.kt:7-14`

```kotlin
private const val MAP_WIDTH = 4000
private const val MAP_HEIGHT = 3500
private const val SECT_RADIUS = 70
private const val MIN_DISTANCE = 80
private const val MAX_CONNECTION_DISTANCE = 500.0
private const val BORDER_PADDING = 150
private const val TARGET_SECT_COUNT = 55
private const val MAX_ATTEMPTS = 50000
```

**问题**: 关键参数硬编码，无法通过配置调整。

---

## 八、错误处理分析

### 8.1 日志记录

**位置**: `SectSystem.kt:113-119`

```kotlin
fun addSpiritStones(amount: Long): Boolean {
    if (amount < 0) {
        Log.w(TAG, "Cannot add negative spirit stones: $amount")
        return false
    }
    // ...
}
```

**问题**: 
- 仅使用 Android Log，无结构化日志
- 缺乏错误码和上下文信息
- 无日志级别控制

### 8.2 异常处理

**位置**: `AISectAttackManager.kt:294-296`

```kotlin
if (!ManualDatabase.isInitialized) {
    throw IllegalStateException("ManualDatabase not initialized when converting disciple ${disciple.name} to combatant")
}
```

**位置**: `GameEngine.kt:167-184`

```kotlin
fun getStateSnapshotSync(): GameStateSnapshot {
    return try {
        GameStateSnapshot(...)
    } catch (e: Exception) {
        Log.e(TAG, "Emergency save snapshot failed", e)
        GameStateSnapshot(...)  // 返回默认值
    }
}
```

**问题**:
- 部分位置抛出异常，部分位置静默处理
- 无统一的错误处理策略
- 异常信息缺乏上下文

---

## 九、性能考量

### 9.1 数据拷贝

**位置**: `GameData` 为 data class，每次更新创建新实例

```kotlin
_gameData.value = _gameData.value.copy(
    spiritStones = newAmount
)
```

**问题**: 
- `GameData` 包含大量嵌套 List 和 Map
- 频繁更新导致大量对象创建
- 可能触发 GC 压力

### 9.2 列表操作

**位置**: `SectSystem.kt:182-193`

```kotlin
fun updateWorldSect(sectId: String, transform: (WorldSect) -> WorldSect): Boolean {
    var found = false
    _gameData.value = _gameData.value.copy(
        worldMapSects = _gameData.value.worldMapSects.map { 
            if (it.id == sectId) {
                found = true
                transform(it)
            } else it 
        }
    )
    return found
}
```

**问题**: 每次更新单个宗门都需要遍历整个列表并创建新列表。

### 9.3 战斗计算

**位置**: `BattleSystem.kt:141-231`

```kotlin
fun executeBattleWithTimeout(battle: Battle, timeoutMs: Long = MAX_BATTLE_DURATION_MS): BattleSystemResult {
    val startTime = System.currentTimeMillis()
    var currentBattle = battle
    val rounds = mutableListOf<BattleRoundData>()
    // ...
    
    while (!currentBattle.isFinished && currentBattle.turn < GameConfig.Battle.MAX_TURNS) {
        // ... 每回合创建大量临时对象
    }
}
```

**问题**: 战斗过程中创建大量临时对象，可能导致内存抖动。

---

## 十、测试覆盖分析

### 10.1 可测试性问题

| 问题 | 位置 | 影响 |
|------|------|------|
| 单例模式 | `object AISectAttackManager` | 难以 Mock |
| 静态方法 | `WorldMapGenerator.generateWorldSects()` | 难以隔离测试 |
| 硬编码依赖 | `ManualDatabase.isInitialized` | 测试需要初始化数据库 |
| 随机数使用 | `Random.nextInt()`, `Random.nextDouble()` | 结果不可预测 |

### 10.2 依赖注入

**位置**: `SectSystem.kt:19`

```kotlin
@Singleton
class SectSystem @Inject constructor() : GameSystem
```

**位置**: `AISectAttackManager.kt:19`

```kotlin
object AISectAttackManager {
```

**问题**: 部分类使用 Hilt 依赖注入，部分使用 object 单例，风格不一致。

---

## 十一、代码质量统计

### 11.1 文件规模

| 文件 | 行数 | 建议 |
|------|------|------|
| GameEngine.kt | 6000+ | 拆分为多个子系统 |
| GameData.kt | 587 | 拆分为多个数据模型 |
| BattleSystem.kt | 1002 | 可接受 |
| AISectAttackManager.kt | 609 | 可接受 |

### 11.2 复杂度热点

| 方法 | 圈复杂度 | 建议 |
|------|----------|------|
| `GameEngine.loadData()` | 高 | 拆分为多个初始化方法 |
| `BattleSystem.executeTurnWithLog()` | 高 | 提取子方法 |
| `WorldMapGenerator.generateConnections()` | 高 | 简化逻辑 |

---

## 十二、技术债务清单

| 编号 | 类型 | 描述 | 优先级 |
|------|------|------|--------|
| TD-001 | 架构 | GameEngine 与 SectSystem 职责重叠 | 高 |
| TD-002 | 架构 | 状态管理分散于多个类 | 高 |
| TD-003 | 数据 | WorldSect.disciples 与 aiDisciples 语义重叠 | 中 |
| TD-004 | 数据 | WorldSect.relation 与 SectRelation.favor 数据冗余 | 中 |
| TD-005 | 并发 | 跨类操作缺乏原子性保证 | 高 |
| TD-006 | 并发 | updateGameData 方法无锁保护 | 中 |
| TD-007 | AI | 战力计算过于简化 | 中 |
| TD-008 | AI | 决策逻辑硬编码 | 中 |
| TD-009 | 外交 | 关系模型过于简单 | 低 |
| TD-010 | 战斗 | 存在两套战斗数据模型 | 中 |
| TD-011 | 配置 | 关键参数硬编码 | 低 |
| TD-012 | 测试 | 单例模式影响可测试性 | 中 |

---

## 附录A: 关键文件索引

| 文件 | 路径 |
|------|------|
| SectSystem | `core/engine/system/SectSystem.kt` |
| GameEngine | `core/engine/GameEngine.kt` |
| DiplomacySubsystem | `core/engine/system/DiplomacySubsystem.kt` |
| AISectAttackManager | `core/engine/AISectAttackManager.kt` |
| AISectDiscipleManager | `core/engine/AISectDiscipleManager.kt` |
| WorldMapGenerator | `core/engine/WorldMapGenerator.kt` |
| BattleSystem | `core/engine/BattleSystem.kt` |
| GameData | `core/model/GameData.kt` |
| GameConfig | `core/GameConfig.kt` |
| DiplomacyUseCase | `domain/usecase/DiplomacyUseCase.kt` |

---

## 附录B: 数据模型关系图

```
┌─────────────────────────────────────────────────────────────────┐
│                           GameData                               │
├─────────────────────────────────────────────────────────────────┤
│  worldMapSects: List<WorldSect>                                 │
│  sectRelations: List<SectRelation>                              │
│  alliances: List<Alliance>                                      │
│  supportTeams: List<SupportTeam>                                │
│  battleTeam: BattleTeam?                                        │
│  aiBattleTeams: List<AIBattleTeam>                              │
└─────────────────────────────────────────────────────────────────┘
         │                    │                    │
         ▼                    ▼                    ▼
┌─────────────────┐  ┌─────────────────┐  ┌─────────────────┐
│    WorldSect    │  │  SectRelation   │  │    Alliance     │
├─────────────────┤  ├─────────────────┤  ├─────────────────┤
│ id              │  │ sectId1         │  │ id              │
│ name            │  │ sectId2         │  │ sectIds         │
│ level           │  │ favor           │  │ startYear       │
│ aiDisciples     │  │ lastInteraction │  │ initiatorId     │
│ allianceId      │  │ noGiftYears     │  │ envoyDiscipleId │
│ warehouse       │  │                 │  │                 │
└─────────────────┘  └─────────────────┘  └─────────────────┘
         │
         ▼
┌─────────────────┐
│     Disciple    │
├─────────────────┤
│ id              │
│ name            │
│ realm           │
│ cultivation     │
│ equipmentIds    │
│ manualIds       │
└─────────────────┘
```
