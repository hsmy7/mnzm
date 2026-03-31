# 建筑系统审查报告

> 审查日期: 2026-03-30  
> 审查范围: 建筑系统核心模块  
> 审查标准: 行业先进技术实践

---

## 一、架构层面问题

### 1. 数据模型冗余与不一致

**问题位置**: `GameData.kt:116-120`

```kotlin
forgeSlots: List<BuildingSlot>  // 同时存储炼丹和锻造
alchemySlots: List<AlchemySlot> // 独立的炼丹槽位
```

**问题描述**:
- `forgeSlots` 通过 `buildingId` 区分不同建筑，但炼丹又有独立的 `alchemySlots`
- 在 `GameEngine.kt:10453-10485` 中，`startAlchemy` 同时更新两套数据

**风险**: 数据同步失败会导致状态不一致，玩家可能丢失进度或获得重复产物

**行业对比**:
- 现代游戏架构通常采用单一数据源原则(Single Source of Truth)
- Unity的ECS架构中，相同类型组件存储在统一容器中

---

### 2. 违反单一职责原则

**问题位置**: `GameEngine.kt`

**问题描述**:
- `GameEngine` 文件超过 10000 行，包含炼丹、锻造、种植、采矿等所有逻辑
- 子系统 `AlchemySubsystem`、`ForgingSubsystem` 已存在但未被充分使用
- `ProductionUseCase` 和 `AlchemyUseCase` 功能重叠

**风险**: 维护困难，修改一处可能影响其他功能，测试复杂度高

**行业对比**:
- 大型游戏项目通常按功能模块拆分系统
- 推荐每个类不超过 500 行，单个方法不超过 50 行

---

### 3. 状态机设计缺陷

**问题位置**: 
- `BuildingSlot` 使用 `SlotStatus`
- `AlchemySlot` 使用 `AlchemySlotStatus`

**问题描述**:
- 两套状态枚举：`SlotStatus { IDLE, WORKING, COMPLETED }` 和 `AlchemySlotStatus { IDLE, WORKING, FINISHED }`
- 状态转换没有统一验证，任何代码都可以直接修改状态

**风险**: 非法状态转换（如从 IDLE 直接到 COMPLETED）无法被检测

**行业对比**:
- 现代状态管理采用有限状态机(FSM)或行为树
- 状态转换应通过统一接口，支持验证和日志

---

## 二、并发与事务问题

### 4. 缺乏原子性保证

**问题位置**: `GameEngine.kt:10435-10447`

```kotlin
// 扣除草药材料
recipe.materials.forEach { (herbId, requiredAmount) ->
    _herbs.value = _herbs.value.map { ... }
}
// 后续可能失败，但材料已扣除
```

**问题描述**: 材料扣除和槽位状态更新不是原子操作，中途失败无法回滚

**风险**: 玩家可能损失材料但未开始生产

**行业对比**:
- 游戏服务器通常采用事务机制保证数据一致性
- 推荐使用 Unit of Work 模式或事务脚本

---

### 5. 竞态条件风险

**问题位置**: `BuildingSubsystem.kt:67-68`

```kotlin
fun updateBuildingSlots(transform: (List<BuildingSlot>) -> List<BuildingSlot>) {
    _buildingSlots.value = transform(_buildingSlots.value)
}
```

**问题描述**: 多个协程同时调用 `updateBuildingSlots` 可能导致数据丢失

**风险**: 并发操作可能导致槽位状态被覆盖

**行业对比**:
- Kotlin Flow 应配合 `Mutex` 或 `StateFlow` 的原子更新
- 推荐使用 `update` 方法而非直接赋值

---

## 三、硬编码与配置问题

### 6. 魔法数字和字符串

**问题位置**:
- `GameEngine.kt:10565`: `slotIndex >= 3`
- `GameEngine.kt:10451`: `"alchemyRoom"`, `"forge"`

**问题描述**: 槽位数量、建筑ID硬编码在代码中

**风险**: 修改配置需要改代码，无法通过热更新调整

**行业对比**:
- 现代游戏采用配置驱动设计
- 数值、字符串应外置到 JSON/YAML 配置文件

---

## 四、数据库设计问题

### 7. 表结构冗余

**问题位置**: `Daos.kt`

```
BuildingSlotDao  -> building_slots 表
ForgeSlotDao     -> forge_slots 表  
AlchemySlotDao   -> alchemy_slots 表
```

**问题描述**: 三个表存储相似数据，但字段不完全一致

**风险**: 数据迁移困难，查询复杂

**行业对比**:
- 推荐使用单表继承(STI)或类表继承(CTI)
- 或采用 NoSQL 方案存储异构数据

---

## 五、错误处理问题

### 8. 缺乏结构化错误处理

**问题位置**: `GameEngine.kt:10417-10418`

```kotlin
if (existingSlot?.status == SlotStatus.WORKING) {
    addEvent("该炼丹槽位正在工作中", EventType.WARNING)
    return false
}
```

**问题描述**: 使用字符串消息和布尔返回值，缺乏错误码和异常类型

**风险**: UI层无法针对不同错误提供精确反馈

**行业对比**:
- 推荐使用 `Result<T>` 或密封类表示操作结果
- 错误应包含错误码、消息、上下文信息

---

## 六、优化方案

### 方案1: 统一槽位模型

```kotlin
// 建议的统一模型
sealed class ProductionSlot {
    abstract val id: String
    abstract val slotIndex: Int
    abstract val buildingType: BuildingType
    abstract val status: SlotStatus
    abstract val recipeId: String?
    abstract val startTime: GameTime?
    abstract val duration: Int
    abstract val assignedDiscipleId: String?
}

data class AlchemySlot(
    override val buildingType: BuildingType = BuildingType.ALCHEMY,
    val pillId: String? = null,
    val successRate: Double = 0.0
) : ProductionSlot()

data class ForgeSlot(
    override val buildingType: BuildingType = BuildingType.FORGE,
    val equipmentId: String? = null
) : ProductionSlot()
```

**优点**:
- 统一的数据结构，减少同步问题
- 支持多态处理，代码复用性高
- 便于扩展新的建筑类型

---

### 方案2: 引入状态机

```kotlin
sealed class SlotState {
    object Idle : SlotState()
    data class Working(val startTime: GameTime, val duration: Int) : SlotState()
    data class Completed(val result: ProductionResult) : SlotState()
    
    fun transitionTo(newState: SlotState): Result<SlotState> {
        return when {
            this is Idle && newState is Working -> Result.success(newState)
            this is Working && newState is Completed -> Result.success(newState)
            this is Completed && newState is Idle -> Result.success(newState)
            else -> Result.failure(IllegalStateException("Invalid transition"))
        }
    }
}
```

**优点**:
- 强制合法状态转换
- 便于调试和日志追踪
- 支持状态回滚

---

### 方案3: 事务性操作

```kotlin
class ProductionTransaction(
    private val materialRepository: MaterialRepository,
    private val slotRepository: SlotRepository
) {
    suspend fun startProduction(
        slotIndex: Int,
        recipe: Recipe,
        materials: Map<String, Int>
    ): Result<ProductionSlot> = runCatching {
        // 原子性检查和扣除
        materialRepository.consumeMaterials(materials)
        
        // 创建工作槽位
        val slot = slotRepository.updateSlot(slotIndex) { current ->
            require(current.status == SlotStatus.IDLE)
            current.copy(
                status = SlotStatus.WORKING,
                recipeId = recipe.id,
                startTime = gameClock.currentTime()
            )
        }
        
        slot
    }.onFailure {
        // 自动回滚
        materialRepository.restoreMaterials(materials)
    }
}
```

**优点**:
- 保证数据一致性
- 自动回滚机制
- 错误处理集中化

---

### 方案4: 配置化

```kotlin
// building_config.json
{
  "buildings": {
    "alchemy": {
      "slotCount": 3,
      "unlockConditions": { "sectLevel": 1 },
      "baseSuccessRate": 0.7
    },
    "forge": {
      "slotCount": 2,
      "unlockConditions": { "sectLevel": 2 }
    }
  }
}

data class BuildingConfig(
    val slotCount: Int,
    val unlockConditions: UnlockConditions,
    val baseSuccessRate: Double = 1.0
)
```

**优点**:
- 热更新支持
- 策划可独立调整数值
- 便于 A/B 测试

---

### 方案5: 分层架构重构

```
┌─────────────────────────────────────────┐
│              UI Layer                    │
├─────────────────────────────────────────┤
│           UseCase Layer                  │
│  AlchemyUseCase / ForgeUseCase          │
├─────────────────────────────────────────┤
│          Domain Layer                    │
│  ProductionService (核心业务逻辑)        │
├─────────────────────────────────────────┤
│          Data Layer                      │
│  SlotRepository / MaterialRepository     │
├─────────────────────────────────────────┤
│          Database Layer                  │
│  Unified SlotDao / MaterialDao          │
└─────────────────────────────────────────┘
```

**优点**:
- 职责清晰，便于测试
- 支持模块化开发
- 便于后续扩展

---

## 七、优先级建议

| 优先级 | 问题 | 影响 | 工作量 | 建议时间 |
|--------|------|------|--------|----------|
| **P0** | 数据同步问题 | 可能导致数据丢失 | 中 | 立即修复 |
| **P0** | 事务原子性 | 材料损失风险 | 中 | 立即修复 |
| **P1** | 状态机设计 | 状态不一致风险 | 高 | 1-2周 |
| **P1** | 并发安全 | 数据竞争风险 | 中 | 1周 |
| **P2** | 架构重构 | 维护成本高 | 高 | 1-2月 |
| **P2** | 配置化 | 灵活性差 | 低 | 2周 |
| **P3** | 数据库统一 | 查询复杂 | 高 | 长期规划 |

---

## 八、相关文件索引

| 文件 | 说明 |
|------|------|
| `BuildingSubsystem.kt` | 建筑子系统核心实现 |
| `AlchemySubsystem.kt` | 炼丹子系统 |
| `ForgingSubsystem.kt` | 锻造子系统 |
| `GameData.kt` | 核心数据模型 |
| `CultivatorCave.kt` | BuildingSlot/AlchemySlot 定义 |
| `AlchemySystem.kt` | AlchemySlot/ForgeSlot 定义 |
| `Daos.kt` | 数据库访问层 |
| `GameEngine.kt` | 游戏引擎主逻辑 |
| `ProductionUseCase.kt` | 生产相关用例 |
| `AlchemyUseCase.kt` | 炼丹相关用例 |

---

## 九、行业参考

1. **Unity ECS Architecture** - 实体组件系统设计模式
2. **Clean Architecture** - 分层架构最佳实践
3. **State Pattern** - 状态机设计模式
4. **Unit of Work Pattern** - 事务管理模式
5. **Repository Pattern** - 数据访问抽象

---

*报告结束*
