# 仓库与物品系统审查报告

> 审查日期：2026-03-30  
> 审查范围：物品模型、仓库管理、背包系统、物品操作工具类  
> 审查标准：行业最佳实践、数据完整性、并发安全、性能优化

---

## 目录

1. [系统架构概览](#一系统架构概览)
2. [核心问题分析](#二核心问题分析)
3. [优化方案详解](#三优化方案详解)
4. [实施优先级](#四实施优先级)
5. [代码变更清单](#五代码变更清单)

---

## 一、系统架构概览

### 1.1 现有架构

```
┌─────────────────────────────────────────────────────────────┐
│                      GameEngine                              │
├─────────────────────────────────────────────────────────────┤
│  ┌─────────────┐  ┌─────────────┐  ┌─────────────────────┐  │
│  │InventorySys │  │MerchantSub  │  │ProductionSubsystem  │  │
│  │   tem       │  │  system     │  │                     │  │
│  └──────┬──────┘  └──────┬──────┘  └──────────┬──────────┘  │
│         │                │                    │              │
│         ▼                ▼                    ▼              │
│  ┌─────────────────────────────────────────────────────┐    │
│  │           StateFlowListUtils (全局锁)                │    │
│  └─────────────────────────────────────────────────────┘    │
└─────────────────────────────────────────────────────────────┘
         │
         ▼
┌─────────────────────────────────────────────────────────────┐
│                    数据持久层                                │
│  ┌─────────┐ ┌─────────┐ ┌─────────┐ ┌─────────┐           │
│  │Equipment│ │ Manual  │ │  Pill   │ │Material │ ...       │
│  │  Dao    │ │  Dao    │ │  Dao    │ │  Dao    │           │
│  └─────────┘ └─────────┘ └─────────┘ └─────────┘           │
└─────────────────────────────────────────────────────────────┘
```

### 1.2 核心文件清单

| 文件路径 | 职责 | 行数 |
|----------|------|------|
| `android/app/src/main/java/com/xianxia/sect/core/model/Items.kt` | 物品数据模型定义 | 525 |
| `android/app/src/main/java/com/xianxia/sect/core/model/Disciple.kt` | 弟子模型（含储物袋） | 659 |
| `android/app/src/main/java/com/xianxia/sect/core/model/GameData.kt` | 游戏数据（含仓库） | 587 |
| `android/app/src/main/java/com/xianxia/sect/core/engine/system/InventorySystem.kt` | 物品系统核心逻辑 | 446 |
| `android/app/src/main/java/com/xianxia/sect/core/engine/SectWarehouseManager.kt` | 宗门仓库管理 | 304 |
| `android/app/src/main/java/com/xianxia/sect/core/util/StateFlowListUtils.kt` | 状态流列表工具 | 314 |

### 1.3 物品类型关系

```
GameItem (sealed class)
    ├── Equipment (不可堆叠)
    │     └── quantity: Int = 1
    │
    ├── Manual (可堆叠) ─── StackableItem
    │     └── quantity: Int
    │
    ├── Pill (可堆叠) ─── StackableItem
    │     └── quantity: Int
    │
    ├── Material (可堆叠) ─── StackableItem
    │     └── quantity: Int
    │
    ├── Herb (可堆叠) ─── StackableItem
    │     └── quantity: Int
    │
    └── Seed (可堆叠) ─── StackableItem
          └── quantity: Int
```

---

## 二、核心问题分析

### 2.1 数据完整性问题

#### 问题 2.1.1：物品数量边界校验缺失

**严重程度：P0（高危）**

**问题位置：** `android/app/src/main/java/com/xianxia/sect/core/util/StateFlowListUtils.kt:221-253`

**问题描述：**
```kotlin
fun <T> removeStackable(...): Boolean where T : StackableItem {
    StateFlowListUtils.globalLock.withLock {
        flow.value = flow.value.mapNotNull { item ->
            if (getId(item) == id && !removed) {
                val newQty = getQuantity(item) - quantity
                when {
                    newQty < 0 -> {
                        logWarning?.invoke("Cannot remove $quantity items, only ${getQuantity(item)} available")
                        item  // 返回原物品，但操作被标记为"成功"
                    }
                    newQty == 0 -> { removed = true; null }
                    else -> { removed = true; withQuantity(item, newQty) }
                }
            } else item
        }
        return removed  // 即使数量不足也可能返回 true
    }
}
```

**漏洞分析：**
- 当 `newQty < 0` 时，仅打印警告日志，不阻止操作
- 调用方无法区分"操作成功"、"部分成功"、"完全失败"
- 可能导致物品数量变为负数（理论上被警告拦截，但逻辑不严谨）

**影响范围：**
- 所有可堆叠物品的移除操作
- 交易系统、炼丹系统、战斗消耗等

---

#### 问题 2.1.2：物品合并竞态条件

**严重程度：P1（中危）**

**问题位置：** `android/app/src/main/java/com/xianxia/sect/core/util/StateFlowListUtils.kt:145-177`

**问题描述：**
```kotlin
inline fun <T> addStackable(...) where T : StackableItem {
    StateFlowListUtils.globalLock.withLock {
        if (merge) {
            val existingIndex = flow.value.indexOfFirst { ... }  // 第一次遍历
            if (existingIndex >= 0) {
                val existing = flow.value[existingIndex]
                val newQty = getQuantity(existing) + getQuantity(item)
                flow.value = flow.value.mapIndexed { ... }  // 第二次遍历
                return
            }
        }
        flow.value = flow.value + item
    }
}
```

**性能问题：**
- 双重遍历：`indexOfFirst` + `mapIndexed`
- 每次操作创建新列表对象

**潜在风险：**
- 虽然有全局锁保护，但在高并发场景下性能瓶颈明显
- 批量添加时每次都触发 StateFlow 更新

---

#### 问题 2.1.3：物品ID无唯一性保证

**严重程度：P2（低危）**

**问题位置：** `android/app/src/main/java/com/xianxia/sect/core/model/Items.kt:38`

**问题描述：**
```kotlin
data class Equipment(
    @PrimaryKey
    override val id: String = java.util.UUID.randomUUID().toString(),
    // ...
)
```

**风险场景：**
- 存档导入/导出时可能产生ID冲突
- 批量创建物品时无去重校验
- 理论上UUID碰撞概率极低，但无防护机制

---

### 2.2 并发安全问题

#### 问题 2.2.1：全局锁粒度过粗

**严重程度：P1（中危）**

**问题位置：** `android/app/src/main/java/com/xianxia/sect/core/util/StateFlowListUtils.kt:9`

**问题描述：**
```kotlin
object StateFlowListUtils {
    val globalLock = ReentrantLock()
    // 所有物品操作共享此锁
}
```

**影响分析：**
- 装备操作会阻塞丹药操作
- 材料操作会阻塞种子操作
- 不同类型物品之间无并发能力

**性能影响：**
```
假设：
- 单次物品操作耗时 1ms
- 每秒 100 次操作（混合类型）

当前架构：
- 所有操作串行执行
- 理论吞吐量：1000 ops/s
- 实际吞吐量：约 100 ops/s（受锁竞争影响）

优化后：
- 按类型分锁
- 理论吞吐量：6000 ops/s（6种物品类型并行）
```

---

#### 问题 2.2.2：StateFlow更新非原子性

**严重程度：P1（中危）**

**问题位置：** 多处使用 `flow.value = flow.value + item` 模式

**问题描述：**
```kotlin
// 读取当前值
val current = flow.value
// 计算新值
val newValue = current + item
// 写入新值（此时 current 可能已被其他线程修改）
flow.value = newValue
```

**风险：**
- 虽然有 `globalLock` 保护，但锁外读取值仍可能导致数据不一致
- `StateFlow.value` 的读写本身是原子的，但复合操作不是

---

### 2.3 性能问题

#### 问题 2.3.1：物品查询线性复杂度

**严重程度：P2（中危）**

**问题位置：** `android/app/src/main/java/com/xianxia/sect/core/util/StateFlowListUtils.kt:100-104`

**问题描述：**
```kotlin
inline fun <T> findItemById(
    flow: MutableStateFlow<List<T>>,
    id: String,
    crossinline getId: (T) -> String
): T? = flow.value.find { getId(it) == id }  // O(n) 复杂度
```

**性能测试估算：**
```
物品数量    查询耗时（估算）
100         ~0.1ms
1,000       ~1ms
10,000      ~10ms
100,000     ~100ms
```

**影响场景：**
- 装备详情查看
- 物品使用/出售
- 战斗中装备属性计算

---

#### 问题 2.3.2：批量操作无优化

**严重程度：P2（中危）**

**问题位置：** `android/app/src/main/java/com/xianxia/sect/core/util/StateFlowListUtils.kt:179-219`

**问题描述：**
```kotlin
inline fun <T> addStackableBatch(...) where T : StackableItem {
    // ...
    for (item in validItems) {
        // 每次循环都可能触发列表重建
        if (merge) {
            val existingIndex = currentList.indexOfFirst { ... }
            if (existingIndex >= 0) {
                currentList = currentList.mapIndexed { ... }  // 创建新列表
            }
        }
    }
    flow.value = currentList
}
```

**问题：**
- 批量添加100个物品可能创建100个临时列表
- UI层收到多次更新通知

---

### 2.4 架构设计问题

#### 问题 2.4.1：物品系统缺乏统一抽象

**严重程度：P2（中危）**

**问题描述：**
- 每种物品类型有独立的DAO和存储逻辑
- 物品操作接口不统一，代码重复率高
- `GameItem` 密封类定义了基础接口，但未充分利用

**代码重复示例：**
```kotlin
// InventorySystem.kt 中重复的模式
fun addPill(item: Pill, merge: Boolean = true) { ... }
fun addMaterial(item: Material, merge: Boolean = true) { ... }
fun addHerb(item: Herb, merge: Boolean = true) { ... }
fun addSeed(item: Seed, merge: Boolean = true) { ... }

// 每个方法都有相似的结构，仅类型不同
```

---

#### 问题 2.4.2：仓库与背包边界模糊

**严重程度：P2（中危）**

**问题位置：** `android/app/src/main/java/com/xianxia/sect/core/model/GameData.kt:406-418`, `android/app/src/main/java/com/xianxia/sect/core/model/Disciple.kt:608-620`

**问题描述：**
```kotlin
// 宗门仓库
data class SectWarehouse(
    val items: List<WarehouseItem> = emptyList(),
    val spiritStones: Long = 0
)

// 弟子储物袋
data class StorageBagItem(
    val itemId: String,
    val itemType: String,
    val name: String,
    val rarity: Int,
    val quantity: Int = 1,
    // ...
)
```

**问题：**
- `WarehouseItem.itemData` 使用 `@Transient` 标记，序列化时丢失
- `itemId` 与实际物品表无外键约束，可能产生悬空引用
- 两套系统职责重叠，转换逻辑分散

---

### 2.5 业务逻辑漏洞

#### 问题 2.5.1：物品转移缺乏事务性

**严重程度：P0（高危）**

**问题位置：** `android/app/src/main/java/com/xianxia/sect/core/engine/SectWarehouseManager.kt:185-201`

**问题描述：**
```kotlin
fun addItemToWarehouse(warehouse: SectWarehouse, item: WarehouseItem): SectWarehouse {
    // 仅更新仓库，未验证源物品是否存在
    val existingIndex = warehouse.items.indexOfFirst { ... }
    return if (existingIndex >= 0) {
        // 更新数量
    } else {
        warehouse.copy(items = warehouse.items + item)
    }
}
```

**风险场景：**
1. 玩家将物品从背包转移到仓库
2. 背包移除成功，仓库添加失败
3. 物品丢失，无回滚机制

---

#### 问题 2.5.2：物品堆叠上限未限制

**严重程度：P3（低危）**

**问题描述：**
- 可堆叠物品无数量上限
- 理论上可能导致整数溢出（Int.MAX_VALUE = 2,147,483,647）

**风险：**
- 恶意修改存档可能导致异常
- UI显示问题（数字过长）

---

#### 问题 2.5.3：物品稀有度校验缺失

**严重程度：P3（低危）**

**问题位置：** `android/app/src/main/java/com/xianxia/sect/core/model/Items.kt:20-21`

**问题描述：**
```kotlin
val rarityColor: String get() = GameConfig.Rarity.getColor(rarity)
val rarityName: String get() = GameConfig.Rarity.getName(rarity)
```

- `rarity` 使用 `Int` 类型，无范围校验
- 可能产生无效稀有度的物品（如 rarity = 0 或 rarity = 999）

---

## 三、优化方案详解

### 3.1 数据完整性优化

#### 方案 3.1.1：引入操作结果类型

```kotlin
sealed class ItemOperationResult<out T> {
    data class Success<T>(val data: T) : ItemOperationResult<T>()
    data class PartialSuccess<T>(val data: T, val warning: String) : ItemOperationResult<T>()
    data class Failed<T>(val reason: String, val code: ErrorCode) : ItemOperationResult<T>()
    
    enum class ErrorCode {
        ITEM_NOT_FOUND,
        INSUFFICIENT_QUANTITY,
        INVALID_OPERATION,
        CONCURRENT_MODIFICATION
    }
    
    inline fun <R> map(transform: (T) -> R): ItemOperationResult<R> = when (this) {
        is Success -> Success(transform(data))
        is PartialSuccess -> PartialSuccess(transform(data), warning)
        is Failed -> Failed(reason, code)
    }
    
    inline fun onSuccess(action: (T) -> Unit): ItemOperationResult<T> {
        if (this is Success) action(data)
        return this
    }
    
    inline fun onFailure(action: (String) -> Unit): ItemOperationResult<T> {
        if (this is Failed) action(reason)
        return this
    }
}
```

#### 方案 3.1.2：增强移除操作校验

```kotlin
object StackableItemUtils {
    
    fun <T> removeStackable(
        flow: MutableStateFlow<List<T>>,
        id: String,
        quantity: Int,
        getId: (T) -> String,
        getQuantity: (T) -> Int,
        withQuantity: (T, Int) -> T,
        logWarning: ((String) -> Unit)? = null
    ): ItemOperationResult<Int> where T : StackableItem {
        if (quantity <= 0) {
            return ItemOperationResult.Failed(
                "Invalid quantity: $quantity",
                ItemOperationResult.ErrorCode.INVALID_OPERATION
            )
        }
        
        StateFlowListUtils.globalLock.withLock {
            val item = flow.value.find { getId(it) == id }
                ?: return ItemOperationResult.Failed(
                    "Item not found: $id",
                    ItemOperationResult.ErrorCode.ITEM_NOT_FOUND
                )
            
            val currentQty = getQuantity(item)
            if (currentQty < quantity) {
                return ItemOperationResult.Failed(
                    "Insufficient quantity: have $currentQty, need $quantity",
                    ItemOperationResult.ErrorCode.INSUFFICIENT_QUANTITY
                )
            }
            
            val newQty = currentQty - quantity
            
            if (newQty == 0) {
                flow.value = flow.value.filterNot { getId(it) == id }
            } else {
                flow.value = flow.value.map { 
                    if (getId(it) == id) withQuantity(it, newQty) else it 
                }
            }
            
            return ItemOperationResult.Success(newQty)
        }
    }
}
```

#### 方案 3.1.3：物品ID生成器

```kotlin
object ItemIdGenerator {
    private val usedIds = ConcurrentHashMap.newKeySet<String>()
    private const val MAX_ATTEMPTS = 100
    
    fun generateId(prefix: String = "item"): String {
        repeat(MAX_ATTEMPTS) {
            val id = "${prefix}_${UUID.randomUUID().toString().take(8)}"
            if (usedIds.add(id)) return id
        }
        throw IllegalStateException("Failed to generate unique ID after $MAX_ATTEMPTS attempts")
    }
    
    fun registerId(id: String): Boolean = usedIds.add(id)
    
    fun unregisterId(id: String) = usedIds.remove(id)
    
    fun loadExistingIds(ids: Collection<String>) {
        usedIds.addAll(ids)
    }
    
    fun clear() = usedIds.clear()
}
```

---

### 3.2 并发安全优化

#### 方案 3.2.1：细粒度锁管理

```kotlin
object ItemLockManager {
    private val locks = ConcurrentHashMap<String, ReentrantReadWriteLock>()
    
    fun <T> withReadLock(itemType: String, block: () -> T): T {
        val lock = locks.computeIfAbsent(itemType) { ReentrantReadWriteLock() }
        return lock.readLock().withLock(block)
    }
    
    fun <T> withWriteLock(itemType: String, block: () -> T): T {
        val lock = locks.computeIfAbsent(itemType) { ReentrantReadWriteLock() }
        return lock.writeLock().withLock(block)
    }
    
    fun <T> withMultiWriteLock(itemTypes: Collection<String>, block: () -> T): T {
        val sortedTypes = itemTypes.sorted()
        val acquiredLocks = mutableListOf<ReentrantReadWriteLock.WriteLock>()
        try {
            sortedTypes.forEach { type ->
                val lock = locks.computeIfAbsent(type) { ReentrantReadWriteLock() }.writeLock()
                lock.lock()
                acquiredLocks.add(lock)
            }
            return block()
        } finally {
            acquiredLocks.reversed().forEach { it.unlock() }
        }
    }
}
```

#### 方案 3.2.2：原子更新扩展

```kotlin
fun <T> MutableStateFlow<List<T>>.atomicUpdate(transform: (List<T>) -> List<T>): Boolean {
    var success = false
    var attempts = 0
    val maxAttempts = 10
    
    while (!success && attempts < maxAttempts) {
        val current = value
        val newValue = transform(current)
        if (current === newValue) return true
        success = compareAndSet(current, newValue)
        attempts++
    }
    
    return success
}

fun <T> MutableStateFlow<List<T>>.atomicUpdateSync(transform: (List<T>) -> List<T>) {
    synchronized(this) {
        value = transform(value)
    }
}
```

---

### 3.3 性能优化

#### 方案 3.3.1：索引化物品存储

```kotlin
class IndexedItemRepository<T : GameItem>(
    private val getId: (T) -> String,
    private val getName: (T) -> String,
    private val getRarity: (T) -> Int
) {
    private val items = MutableStateFlow<List<T>>(emptyList())
    private val idIndex = ConcurrentHashMap<String, T>()
    private val nameRarityIndex = ConcurrentHashMap<Pair<String, Int>, MutableList<T>>()
    
    val flow: StateFlow<List<T>> = items.asStateFlow()
    
    fun add(item: T, merge: Boolean = false) {
        synchronized(this) {
            val currentList = items.value.toMutableList()
            
            if (merge) {
                val key = Pair(getName(item), getRarity(item))
                val existing = nameRarityIndex[key]?.firstOrNull()
                
                if (existing != null && item is StackableItem && existing is StackableItem) {
                    val idx = currentList.indexOf(existing)
                    @Suppress("UNCHECKED_CAST")
                    currentList[idx] = (existing as T).withQuantity(
                        existing.quantity + item.quantity
                    ) as T
                    items.value = currentList
                    rebuildIndexes()
                    return
                }
            }
            
            currentList.add(item)
            items.value = currentList
            idIndex[getId(item)] = item
            
            val key = Pair(getName(item), getRarity(item))
            nameRarityIndex.getOrPut(key) { mutableListOf() }.add(item)
        }
    }
    
    fun getById(id: String): T? = idIndex[id]
    
    fun getByNameAndRarity(name: String, rarity: Int): List<T> = 
        nameRarityIndex[Pair(name, rarity)]?.toList() ?: emptyList()
    
    fun remove(id: String): Boolean {
        synchronized(this) {
            val item = idIndex[id] ?: return false
            items.value = items.value.filterNot { getId(it) == id }
            idIndex.remove(id)
            nameRarityIndex[Pair(getName(item), getRarity(item))]?.remove(item)
            return true
        }
    }
    
    private fun rebuildIndexes() {
        idIndex.clear()
        nameRarityIndex.clear()
        items.value.forEach { item ->
            idIndex[getId(item)] = item
            nameRarityIndex.getOrPut(Pair(getName(item), getRarity(item))) { 
                mutableListOf() 
            }.add(item)
        }
    }
}
```

#### 方案 3.3.2：批量操作优化

```kotlin
fun <T> addStackableBatchOptimized(
    flow: MutableStateFlow<List<T>>,
    items: List<T>,
    merge: Boolean = true,
    matchPredicate: ((T, T) -> Boolean)? = null,
    getName: (T) -> String,
    getRarity: (T) -> Int,
    getQuantity: (T) -> Int,
    withQuantity: (T, Int) -> T
) where T : StackableItem {
    if (items.isEmpty()) return
    
    val validItems = items.filter { getQuantity(it) > 0 }
    if (validItems.isEmpty()) return
    
    StateFlowListUtils.globalLock.withLock {
        val currentList = flow.value.toMutableList()
        
        if (merge) {
            // 构建合并索引
            val mergeIndex = mutableMapOf<Pair<String, Int>, Int>()
            currentList.forEachIndexed { idx, item ->
                val key = Pair(getName(item), getRarity(item))
                mergeIndex[key] = idx
            }
            
            // 批量合并
            for (item in validItems) {
                val key = Pair(getName(item), getRarity(item))
                val existingIdx = mergeIndex[key]
                
                if (existingIdx != null) {
                    val existing = currentList[existingIdx]
                    currentList[existingIdx] = withQuantity(
                        existing, 
                        getQuantity(existing) + getQuantity(item)
                    )
                } else {
                    currentList.add(item)
                    mergeIndex[key] = currentList.size - 1
                }
            }
        } else {
            currentList.addAll(validItems)
        }
        
        flow.value = currentList  // 单次更新
    }
}
```

---

### 3.4 架构优化

#### 方案 3.4.1：统一物品服务层

```kotlin
interface ItemRepository<T : GameItem> {
    suspend fun add(item: T, merge: Boolean = true): Result<T>
    suspend fun remove(id: String, quantity: Int = 1): Result<Int>
    suspend fun update(id: String, transform: (T) -> T): Result<T>
    suspend fun getById(id: String): T?
    suspend fun getByPredicate(predicate: (T) -> Boolean): List<T>
    fun observe(): Flow<List<T>>
}

class UnifiedItemService(
    private val equipmentRepo: ItemRepository<Equipment>,
    private val manualRepo: ItemRepository<Manual>,
    private val pillRepo: ItemRepository<Pill>,
    private val materialRepo: ItemRepository<Material>,
    private val herbRepo: ItemRepository<Herb>,
    private val seedRepo: ItemRepository<Seed>
) {
    @Suppress("UNCHECKED_CAST")
    fun <T : GameItem> getRepository(itemType: String): ItemRepository<T>? = when (itemType.lowercase()) {
        "equipment" -> equipmentRepo as ItemRepository<T>
        "manual" -> manualRepo as ItemRepository<T>
        "pill" -> pillRepo as ItemRepository<T>
        "material" -> materialRepo as ItemRepository<T>
        "herb" -> herbRepo as ItemRepository<T>
        "seed" -> seedRepo as ItemRepository<T>
        else -> null
    }
    
    suspend fun <T : GameItem> operate(
        itemType: String,
        operation: suspend ItemRepository<T>.() -> Result<*>
    ): Result<*> {
        val repo = getRepository<T>(itemType)
            ?: return Result.failure(IllegalArgumentException("Unknown item type: $itemType"))
        return repo.operation()
    }
    
    suspend fun transfer(
        sourceType: String,
        sourceId: String,
        targetType: String,
        quantity: Int = 1
    ): Result<Boolean> {
        return coroutineScope {
            val sourceRepo = getRepository<GameItem>(sourceType)
                ?: return@coroutineScope Result.failure(IllegalArgumentException("Unknown source type"))
            val targetRepo = getRepository<GameItem>(targetType)
                ?: return@coroutineScope Result.failure(IllegalArgumentException("Unknown target type"))
            
            // 事务性转移
            val item = sourceRepo.getById(sourceId)
                ?: return@coroutineScope Result.failure(IllegalArgumentException("Source item not found"))
            
            // ... 实现转移逻辑
            Result.success(true)
        }
    }
}
```

#### 方案 3.4.2：物品稀有度类型安全

```kotlin
@JvmInline
value class Rarity private constructor(val value: Int) : Comparable<Rarity> {
    
    companion object {
        val COMMON = Rarity(1)
        val UNCOMMON = Rarity(2)
        val RARE = Rarity(3)
        val EPIC = Rarity(4)
        val LEGENDARY = Rarity(5)
        
        private val colorMap = mapOf(
            1 to "#9E9E9E",  // 灰色
            2 to "#4CAF50",  // 绿色
            3 to "#2196F3",  // 蓝色
            4 to "#9C27B0",  // 紫色
            5 to "#FF9800"   // 橙色
        )
        
        private val nameMap = mapOf(
            1 to "普通",
            2 to "优秀",
            3 to "稀有",
            4 to "史诗",
            5 to "传说"
        )
        
        fun fromInt(value: Int): Result<Rarity> {
            return if (value in 1..5) {
                Result.success(Rarity(value))
            } else {
                Result.failure(IllegalArgumentException("Invalid rarity: $value, must be 1-5"))
            }
        }
        
        fun unsafe(value: Int): Rarity = Rarity(value.coerceIn(1, 5))
    }
    
    val color: String get() = colorMap[value] ?: "#9E9E9E"
    val name: String get() = nameMap[value] ?: "未知"
    
    override fun compareTo(other: Rarity): Int = value.compareTo(other.value)
}
```

---

### 3.5 业务逻辑优化

#### 方案 3.5.1：事务性物品转移

```kotlin
class ItemTransferService(
    private val inventorySystem: InventorySystem,
    private val itemLockManager: ItemLockManager
) {
    sealed class TransferResult {
        data class Success(val transferredQuantity: Int) : TransferResult()
        data class PartialSuccess(val transferredQuantity: Int, val remainingQuantity: Int) : TransferResult()
        data class Failed(val reason: String, val errorCode: ErrorCode) : TransferResult()
        
        enum class ErrorCode {
            SOURCE_NOT_FOUND,
            TARGET_NOT_FOUND,
            INSUFFICIENT_QUANTITY,
            LOCK_TIMEOUT,
            INTERNAL_ERROR
        }
    }
    
    suspend fun transferToWarehouse(
        sourceDiscipleId: String,
        itemId: String,
        itemType: String,
        quantity: Int,
        targetWarehouse: SectWarehouse
    ): TransferResult {
        if (quantity <= 0) {
            return TransferResult.Failed("Invalid quantity", TransferResult.ErrorCode.INTERNAL_ERROR)
        }
        
        return withTimeoutOrNull(5000) {
            itemLockManager.withMultiWriteLock(listOf(itemType, "warehouse")) {
                performTransfer(sourceDiscipleId, itemId, itemType, quantity, targetWarehouse)
            }
        } ?: TransferResult.Failed("Lock timeout", TransferResult.ErrorCode.LOCK_TIMEOUT)
    }
    
    private suspend fun performTransfer(
        sourceDiscipleId: String,
        itemId: String,
        itemType: String,
        quantity: Int,
        targetWarehouse: SectWarehouse
    ): TransferResult {
        // 1. 验证源物品
        val sourceQuantity = inventorySystem.getItemQuantity(itemType, itemId)
        if (sourceQuantity < quantity) {
            return TransferResult.Failed(
                "Insufficient quantity: have $sourceQuantity, need $quantity",
                TransferResult.ErrorCode.INSUFFICIENT_QUANTITY
            )
        }
        
        // 2. 创建快照（用于回滚）
        val snapshot = createTransferSnapshot(sourceDiscipleId, itemId, itemType, quantity)
        
        // 3. 执行转移
        return try {
            val removeResult = inventorySystem.removeItem(itemType, itemId, quantity)
            if (!removeResult) {
                return TransferResult.Failed("Failed to remove from source", TransferResult.ErrorCode.INTERNAL_ERROR)
            }
            
            val warehouseItem = WarehouseItem(
                itemId = itemId,
                itemType = itemType,
                quantity = quantity
            )
            val newWarehouse = SectWarehouseManager.addItemToWarehouse(targetWarehouse, warehouseItem)
            
            TransferResult.Success(quantity)
        } catch (e: Exception) {
            // 回滚
            rollbackTransfer(snapshot)
            TransferResult.Failed("Transfer failed: ${e.message}", TransferResult.ErrorCode.INTERNAL_ERROR)
        }
    }
    
    private data class TransferSnapshot(
        val sourceDiscipleId: String,
        val itemId: String,
        val itemType: String,
        val quantity: Int,
        val timestamp: Long = System.currentTimeMillis()
    )
    
    private fun createTransferSnapshot(
        sourceDiscipleId: String,
        itemId: String,
        itemType: String,
        quantity: Int
    ) = TransferSnapshot(sourceDiscipleId, itemId, itemType, quantity)
    
    private suspend fun rollbackTransfer(snapshot: TransferSnapshot) {
        // 实现回滚逻辑
        inventorySystem.addItemByType(snapshot.itemType, snapshot.itemId, snapshot.quantity)
    }
}
```

#### 方案 3.5.2：物品堆叠上限配置

```kotlin
object ItemConfig {
    const val MAX_STACK_SIZE = 9999
    const val MAX_INVENTORY_SLOTS = 500
    const val MAX_WAREHOUSE_SLOTS = 1000
    
    private val typeSpecificLimits = mapOf(
        "pill" to 999,
        "material" to 9999,
        "herb" to 999,
        "seed" to 99
    )
    
    fun getMaxStackSize(itemType: String): Int = typeSpecificLimits[itemType] ?: MAX_STACK_SIZE
    
    fun validateQuantity(itemType: String, quantity: Int): Result<Int> {
        val maxStack = getMaxStackSize(itemType)
        return when {
            quantity < 0 -> Result.failure(IllegalArgumentException("Quantity cannot be negative"))
            quantity > maxStack -> Result.failure(IllegalArgumentException("Quantity exceeds max stack size: $maxStack"))
            else -> Result.success(quantity)
        }
    }
}
```

---

## 四、实施优先级

### 4.1 优先级矩阵

| 优先级 | 问题 | 影响 | 实施难度 | 建议时间 |
|--------|------|------|----------|----------|
| **P0** | 物品数量边界校验 | 数据丢失风险 | 低 | 1-2天 |
| **P0** | 物品转移事务性 | 数据丢失风险 | 中 | 2-3天 |
| **P1** | 全局锁粒度优化 | 并发性能 | 中 | 3-5天 |
| **P1** | 物品合并竞态修复 | 数据一致性 | 低 | 1天 |
| **P2** | 物品查询索引化 | 查询性能 | 高 | 5-7天 |
| **P2** | 统一物品服务层 | 代码维护性 | 高 | 7-10天 |
| **P3** | 堆叠上限限制 | 边界防护 | 低 | 0.5天 |
| **P3** | 稀有度类型安全 | 代码健壮性 | 低 | 1天 |

### 4.2 实施路线图

```
Phase 1 (Week 1): 数据完整性修复
├── 引入 ItemOperationResult
├── 增强 removeStackable 校验
└── 实现物品转移事务

Phase 2 (Week 2): 并发优化
├── 实现 ItemLockManager
├── 替换全局锁
└── 添加原子更新扩展

Phase 3 (Week 3-4): 性能优化
├── 实现 IndexedItemRepository
├── 优化批量操作
└── 添加缓存层

Phase 4 (Week 5-6): 架构重构
├── 设计统一物品服务层
├── 迁移现有代码
└── 添加类型安全封装
```

---

## 五、代码变更清单

### 5.1 新增文件

| 文件路径 | 说明 |
|----------|------|
| `android/app/src/main/java/com/xianxia/sect/core/util/ItemOperationResult.kt` | 操作结果类型定义 |
| `android/app/src/main/java/com/xianxia/sect/core/util/ItemLockManager.kt` | 细粒度锁管理 |
| `android/app/src/main/java/com/xianxia/sect/core/util/ItemIdGenerator.kt` | ID生成器 |
| `android/app/src/main/java/com/xianxia/sect/core/util/IndexedItemRepository.kt` | 索引化存储 |
| `android/app/src/main/java/com/xianxia/sect/core/model/Rarity.kt` | 稀有度类型安全封装 |
| `android/app/src/main/java/com/xianxia/sect/core/service/ItemTransferService.kt` | 物品转移服务 |
| `android/app/src/main/java/com/xianxia/sect/core/repository/ItemRepository.kt` | 物品仓库接口 |

### 5.2 修改文件

| 文件路径 | 修改内容 |
|----------|----------|
| `android/app/src/main/java/com/xianxia/sect/core/util/StateFlowListUtils.kt` | 增强校验、优化批量操作 |
| `android/app/src/main/java/com/xianxia/sect/core/engine/system/InventorySystem.kt` | 使用新结果类型、替换锁机制 |
| `android/app/src/main/java/com/xianxia/sect/core/engine/SectWarehouseManager.kt` | 添加事务支持 |
| `android/app/src/main/java/com/xianxia/sect/core/model/Items.kt` | 使用 Rarity 类型 |

### 5.3 测试用例

| 测试文件 | 覆盖场景 |
|----------|----------|
| `test/ItemOperationResultTest.kt` | 结果类型转换、错误处理 |
| `test/ItemLockManagerTest.kt` | 并发安全、死锁检测 |
| `test/ItemTransferServiceTest.kt` | 转移成功、失败回滚 |
| `test/IndexedItemRepositoryTest.kt` | 查询性能、索引一致性 |

---

## 附录

### A. 性能基准测试

```kotlin
@RunWith(AndroidJUnit4::class)
class InventoryPerformanceTest {
    
    @Test
    fun benchmarkItemLookup() {
        val repository = IndexedItemRepository<Equipment>(
            getId = { it.id },
            getName = { it.name },
            getRarity = { it.rarity }
        )
        
        // 准备 10000 个物品
        val items = (1..10000).map { 
            Equipment(id = "eq_$it", name = "Equipment $it") 
        }
        items.forEach { repository.add(it) }
        
        // 基准测试
        val startTime = System.nanoTime()
        repeat(1000) {
            repository.getById("eq_5000")
        }
        val elapsedMs = (System.nanoTime() - startTime) / 1_000_000
        
        println("Average lookup time: ${elapsedMs / 1000.0} ms")
        assertTrue(elapsedMs < 100) // 应小于 100ms
    }
}
```

### B. 并发压力测试

```kotlin
@Test
fun concurrentAddRemove() = runBlocking {
    val inventorySystem = InventorySystem()
    val iterations = 1000
    val concurrency = 10
    
    val results = mutableListOf<Deferred<Boolean>>()
    
    repeat(concurrency) { workerId ->
        results.add(async(Dispatchers.Default) {
            repeat(iterations / concurrency) {
                val item = Pill(id = "pill_${workerId}_$it", quantity = 1)
                inventorySystem.addPill(item)
                inventorySystem.removePill(item.id)
            }
            true
        })
    }
    
    val allSuccess = results.awaitAll().all { it }
    assertTrue(allSuccess)
    assertEquals(0, inventorySystem.pills.value.size)
}
```

---

> 文档版本：1.0  
> 最后更新：2026-03-30  
> 维护者：系统架构组
