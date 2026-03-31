# 炼丹子系统技术审查报告

## 一、系统架构分析

### 1.1 模块组成

炼丹子系统涉及以下核心模块：

| 模块 | 文件路径 | 职责 |
|------|---------|------|
| AlchemySubsystem | `core/engine/system/AlchemySubsystem.kt` | 炼丹槽位管理、丹药仓库管理 |
| ProductionCoordinator | `core/engine/production/ProductionCoordinator.kt` | 生产流程原子操作协调 |
| GameEngine | `core/engine/GameEngine.kt` | 炼丹流程触发、状态更新 |
| AlchemyUseCase | `domain/usecase/AlchemyUseCase.kt` | 业务逻辑封装层 |
| ProductionUseCase | `domain/usecase/ProductionUseCase.kt` | 生产相关用例 |
| AlchemyScreen | `ui/game/AlchemyScreen.kt` | 炼丹界面UI |
| PillRecipeDatabase | `core/data/PillRecipeDatabase.kt` | 丹方静态数据 |

### 1.2 架构问题

#### 问题1：多套并行入口

`GameEngine` 中存在两个 `startAlchemy` 重载方法：

```kotlin
// 入口1：使用 recipeId (L10645)
fun startAlchemy(slotIndex: Int, recipeId: String): Boolean

// 入口2：使用 AlchemyRecipe 对象 (L10780)
fun startAlchemy(slotIndex: Int, recipe: AlchemyRecipe): Boolean
```

两个入口的内部实现逻辑不同：
- 入口1 调用 `ProductionCoordinator.startAlchemyAtomic()`
- 入口2 直接操作 `_gameData` 状态

#### 问题2：状态管理分散

槽位状态同时存在于多处：

| 存储位置 | 状态类型 | 同步机制 |
|---------|---------|---------|
| `GameEngine._gameData.alchemySlots` | `List<AlchemySlot>` | StateFlow |
| `ProductionCoordinator._slots` | `List<ProductionSlot>` | StateFlow |
| `AlchemySubsystem._alchemySlots` | `List<AlchemySlot>` | StateFlow |

三处状态没有统一的同步机制，存在数据不一致风险。

#### 问题3：职责边界模糊

```
┌─────────────────┐     ┌──────────────────┐     ┌─────────────────┐
│  GameEngine     │────▶│ ProductionCoord  │────▶│ AlchemySubsystem│
│  (业务触发)      │     │ (原子操作)        │     │ (状态存储)       │
└─────────────────┘     └──────────────────┘     └─────────────────┘
        │                                                │
        │         直接操作 _gameData                      │
        └────────────────────────────────────────────────┘
```

`GameEngine` 既通过 `ProductionCoordinator` 操作，又直接修改 `_gameData`，违反单一职责原则。

---

## 二、数据模型分析

### 2.1 Pill 模型

**文件位置**：`core/model/Items.kt:320-385`

```kotlin
@Entity(tableName = "pills")
data class Pill(
    @PrimaryKey override val id: String,
    override val name: String,
    override val rarity: Int,
    override val description: String,
    
    val category: PillCategory,
    val breakthroughChance: Double = 0.0,
    val targetRealm: Int = 0,
    val isAscension: Boolean = false,
    val cultivationSpeed: Double = 1.0,
    val duration: Int = 0,
    val cannotStack: Boolean = false,
    val cultivationPercent: Double = 0.0,
    val skillExpPercent: Double = 0.0,
    val extendLife: Int = 0,
    val physicalAttackPercent: Double = 0.0,
    val magicAttackPercent: Double = 0.0,
    val physicalDefensePercent: Double = 0.0,
    val magicDefensePercent: Double = 0.0,
    val hpPercent: Double = 0.0,
    val mpPercent: Double = 0.0,
    val speedPercent: Double = 0.0,
    val healMaxHpPercent: Double = 0.0,
    val healPercent: Double = 0.0,
    val heal: Int = 0,
    val battleCount: Int = 0,
    val revive: Boolean = false,
    val clearAll: Boolean = false,
    val mpRecoverMaxMpPercent: Double = 0.0,
    override var quantity: Int = 1,
    override val isLocked: Boolean = false
)
```

**问题**：
- 效果字段硬编码，共14个效果属性
- 无法支持条件触发效果（如"生命值低于30%时触发"）
- 无法支持复合效果（如"攻击+10%，防御-5%"）
- `PillEffect` 数据类与 `Pill` 字段重复

### 2.2 AlchemySlot 模型

**状态枚举**：

```kotlin
enum class AlchemySlotStatus {
    IDLE,      // 空闲
    WORKING,   // 进行中
    FINISHED   // 已完成
}
```

**缺失状态**：
- `PAUSED` - 暂停（如资源不足时）
- `FAILED` - 失败（区别于成功/失败的二元判定）

### 2.3 PillRecipe 模型

**文件位置**：`core/data/PillRecipeDatabase.kt:7-37`

```kotlin
data class PillRecipe(
    val id: String,
    val name: String,
    val tier: Int,
    val rarity: Int,
    val category: PillCategory,
    val description: String,
    val materials: Map<String, Int>,  // 材料ID -> 数量
    val duration: Int,
    val successRate: Double,
    // ... 效果字段
)
```

**问题**：
- `materials` 使用字符串ID引用，非类型安全
- 材料类型仅支持草药，未扩展至兽材/矿石等

---

## 三、核心逻辑分析

### 3.1 炼制流程

```
用户选择丹方
    │
    ▼
GameEngine.startAlchemy()
    │
    ├─▶ ProductionCoordinator.startAlchemyAtomic()
    │       ├─ 验证槽位状态
    │       ├─ 验证材料充足
    │       ├─ 扣除材料
    │       └─ 创建工作槽位
    │
    └─▶ 直接修改 _gameData（入口2）
            ├─ 验证槽位状态
            └─ 创建 AlchemySlot
```

### 3.2 成功率计算

**位置**：`GameEngine.kt:2457-2461`

```kotlin
val elderBonus = calculateElderAndDisciplesBonus("alchemy")
val totalSuccessBonus = elderBonus.successBonus
val finalSuccessRate = recipe.successRate + totalSuccessBonus
val success = Random.nextDouble() < finalSuccessRate
```

**问题**：
- `finalSuccessRate` 无上限约束，理论上可超过 1.0
- `speedBonus` 参数在 `processAlchemyProgress` 中声明但未使用

### 3.3 材料消耗

**位置**：`ProductionCoordinator.kt:174-183`

```kotlin
val newHerbs = herbs.map { herb ->
    var newQuantity = herb.quantity
    recipe.materials.forEach { (herbId, requiredAmount) ->
        val herbData = HerbDatabase.getHerbById(herbId)
        if (herbData?.name == herb.name && herbData.rarity == herb.rarity) {
            newQuantity -= requiredAmount
        }
    }
    herb.copy(quantity = newQuantity)
}.filter { it.quantity > 0 }
```

**问题**：
- 材料在开始炼制时立即扣除
- 无材料消耗日志/审计追踪
- 炼制失败时材料已损失，无回滚机制

### 3.4 产出生成

**位置**：`AlchemySubsystem.kt:153-170`

```kotlin
val pill = Pill(
    id = java.util.UUID.randomUUID().toString(),
    name = recipe.name,
    rarity = recipe.rarity,  // 品阶固定等于配方品阶
    description = recipe.description,
    category = recipe.category,
    // ...
)
```

**问题**：
- 产出丹药品阶完全由配方决定，无波动
- 无品质分级机制
- 无暴击产出机制

---

## 四、长老/弟子加成系统

### 4.1 加成计算

**位置**：`GameEngine.kt:9574-9667`

```kotlin
private fun calculateElderAndDisciplesBonus(buildingType: String): ElderBonusResult {
    val (elderId, discipleSlots) = when (buildingType) {
        "alchemy" -> data.elderSlots.alchemyElder to data.elderSlots.alchemyDisciples
        // ...
    }
    
    // 长老加成计算
    val elder = elderId?.let { _disciples.value.find { d -> d.id == it } }
    if (elder != null) {
        when (buildingType) {
            "alchemy" -> {
                val pillRefiningDiff = elder.pillRefining - 50
                successBonus += (pillRefiningDiff / 5.0) * 0.01
            }
        }
    }
    
    // 亲传弟子加成计算
    disciples.forEach { d ->
        val pillRefiningDiff = d.pillRefining - 50
        successBonus += (pillRefiningDiff / 5.0) * 0.01
    }
    
    return ElderBonusResult(
        yieldBonus = yieldBonus,
        speedBonus = speedBonus,
        successBonus = successBonus,
        growTimeReduction = growTimeReduction
    )
}
```

**加成规则**：
- 基准属性值：50点
- 每5点超过基准值，增加1%加成
- 公式：`加成 = (属性值 - 50) / 5 * 1%`

### 4.2 加成应用

| 加成类型 | 应用场景 | 当前状态 |
|---------|---------|---------|
| successBonus | 炼制成功率 | ✅ 已实现 |
| speedBonus | 炼制时间 | ❌ 未使用 |
| yieldBonus | 产出数量 | ❌ 不适用炼丹 |

---

## 五、数据库索引分析

### 5.1 Pills 表索引

```kotlin
@Entity(
    tableName = "pills",
    indices = [
        Index(value = ["name"]),
        Index(value = ["rarity"]),
        Index(value = ["category"]),
        Index(value = ["targetRealm"]),
        Index(value = ["rarity", "category"])
    ]
)
```

**缺失索引**：
- 无复合索引 `(name, rarity)` 用于堆叠查询

### 5.2 Production_slots 表索引

```kotlin
@Entity(tableName = "production_slots")
data class ProductionSlot(...)
```

**问题**：
- 无任何索引定义
- 按 `buildingId` + `slotIndex` 查询频繁，应添加复合索引

---

## 六、并发与线程安全

### 6.1 ProductionCoordinator

```kotlin
private val mutex = Mutex()

suspend fun startAlchemyAtomic(...): ProductionStartResult = mutex.withLock {
    // 原子操作
}
```

使用了 `Mutex` 保证原子性。

### 6.2 GameEngine

```kotlin
fun startAlchemy(slotIndex: Int, recipeId: String): Boolean {
    val result = kotlinx.coroutines.runBlocking {
        productionCoordinator.startAlchemyAtomic(...)
    }
    // ...
}
```

**问题**：
- 使用 `runBlocking` 阻塞调用协程方法
- 可能导致主线程阻塞

---

## 七、问题汇总

| 编号 | 问题 | 严重程度 | 影响范围 |
|------|------|---------|---------|
| A1 | 多套炼丹入口逻辑不一致 | 高 | 数据一致性 |
| A2 | 状态管理分散在三处 | 高 | 数据一致性 |
| A3 | GameEngine 职责过重 | 中 | 可维护性 |
| B1 | 成功率无上限约束 | 中 | 游戏平衡 |
| B2 | speedBonus 未使用 | 低 | 功能完整性 |
| B3 | 材料消耗无日志 | 中 | 可追溯性 |
| B4 | 产出品质固定 | 中 | 玩法深度 |
| C1 | Pill 效果字段硬编码 | 中 | 扩展性 |
| C2 | 配方材料使用字符串ID | 低 | 类型安全 |
| C3 | ProductionSlot 无索引 | 低 | 查询性能 |
| D1 | runBlocking 阻塞调用 | 中 | 性能 |

---

## 八、重构建议

### 8.1 统一入口层

```
┌─────────────────────────────────────────────────────┐
│                   AlchemyService                     │
│  ┌─────────────┐  ┌─────────────┐  ┌─────────────┐ │
│  │ 验证逻辑     │  │ 状态管理    │  │ 事件发布    │ │
│  └─────────────┘  └─────────────┘  └─────────────┘ │
└─────────────────────────────────────────────────────┘
                          │
                          ▼
┌─────────────────────────────────────────────────────┐
│              ProductionCoordinator                  │
│              (原子操作保证)                          │
└─────────────────────────────────────────────────────┘
```

### 8.2 状态同步机制

```kotlin
class AlchemyStateRepository @Inject constructor() {
    private val _slots = MutableStateFlow<List<AlchemySlot>>(emptyList())
    val slots: StateFlow<List<AlchemySlot>> = _slots.asStateFlow()
    
    suspend fun updateSlot(slotIndex: Int, transform: (AlchemySlot) -> AlchemySlot) {
        // 单一更新入口
    }
}
```

### 8.3 成功率约束

```kotlin
val finalSuccessRate = (recipe.successRate + totalSuccessBonus)
    .coerceIn(0.0, 1.0)  // 添加上下限约束
```

### 8.4 材料消耗日志

```kotlin
data class MaterialConsumptionLog(
    val id: String,
    val timestamp: Long,
    val slotIndex: Int,
    val recipeId: String,
    val materials: Map<String, Int>,
    val reason: String
)
```

---

## 九、文件引用

| 文件 | 关键行号 | 说明 |
|------|---------|------|
| [GameEngine.kt](file:///c:/Mnzm/XianxiaSectNative/android/app/src/main/java/com/xianxia/sect/core/engine/GameEngine.kt) | L10645, L10780, L10884 | 炼丹入口方法 |
| [AlchemySubsystem.kt](file:///c:/Mnzm/XianxiaSectNative/android/app/src/main/java/com/xianxia/sect/core/engine/system/AlchemySubsystem.kt) | L129-182 | 进度处理逻辑 |
| [ProductionCoordinator.kt](file:///c:/Mnzm/XianxiaSectNative/android/app/src/main/java/com/xianxia/sect/core/engine/production/ProductionCoordinator.kt) | L121-215 | 原子操作实现 |
| [Items.kt](file:///c:/Mnzm/XianxiaSectNative/android/app/src/main/java/com/xianxia/sect/core/model/Items.kt) | L320-426 | Pill 数据模型 |
| [PillRecipeDatabase.kt](file:///c:/Mnzm/XianxiaSectNative/android/app/src/main/java/com/xianxia/sect/core/data/PillRecipeDatabase.kt) | L1-1457 | 丹方数据定义 |
