# 事件子系统审查报告

> 审查日期: 2026-03-30  
> 审查范围: 事件发布/订阅、事件溯源、状态变更请求处理

---

## 一、架构概览

### 1.1 核心组件

| 组件 | 文件路径 | 职责 |
|------|----------|------|
| EventBus | `core/event/GameEvents.kt` | 事件发布/订阅总线 |
| StateChangeRequestBus | `core/state/StateChangeRequestBus.kt` | 状态变更请求分发 |
| EventStore | `data/v2/eventsourcing/EventStore.kt` | 事件溯源持久化 |
| EventDrivenSubsystem | `core/engine/subsystem/GameSubsystem.kt` | 事件驱动子系统基类 |

### 1.2 数据流

```
┌─────────────────┐     ┌─────────────────┐     ┌─────────────────┐
│   GameEngine    │────▶│    EventBus     │────▶│  Subscribers    │
└─────────────────┘     └─────────────────┘     └─────────────────┘
        │                       │
        ▼                       ▼
┌─────────────────┐     ┌─────────────────┐
│StateChangeReqBus│     │   EventStore    │
└─────────────────┘     └─────────────────┘
        │
        ▼
┌─────────────────┐
│UnifiedStateMgr  │
└─────────────────┘
```

---

## 二、发现的问题

### 2.1 类型定义冲突 [严重]

**位置**: 
- `core/event/GameEvents.kt:14-17`
- `data/v2/eventsourcing/EventStore.kt:18-37`

**问题描述**:  
存在两个不同的 `DomainEvent` 定义：

```kotlin
// GameEvents.kt - 接口定义
interface DomainEvent {
    val timestamp: Long get() = System.currentTimeMillis()
    val type: String
}

// EventStore.kt - 数据类定义
data class DomainEvent(
    val eventId: String,
    val eventType: String,
    val aggregateType: String,
    val aggregateId: String,
    val version: Long,
    val timestamp: Long,
    val payload: ByteArray,
    val metadata: Map<String, String> = emptyMap(),
    val causationId: String? = null,
    val correlationId: String? = null
)
```

**影响**:  
- 类型不兼容，运行时事件无法直接存储到 EventStore
- 需要额外的转换层，增加复杂度
- 可能导致运行时类型转换错误

**建议方案**:
```kotlin
sealed class GameEvent {
    abstract val eventId: String
    abstract val timestamp: Long
    abstract val eventType: String
    abstract val aggregateId: String
    abstract val version: Long
    abstract fun toPayload(): ByteArray
}
```

---

### 2.2 EventBus 事件丢失风险 [高危]

**位置**: `core/event/GameEvents.kt:149-167`

**问题描述**:

```kotlin
private val _events = MutableSharedFlow<DomainEvent>(extraBufferCapacity = 64)

fun emit(event: DomainEvent) {
    _events.tryEmit(event)  // 缓冲区满时返回 false，事件被丢弃
    // ...
}
```

**影响**:
- 高并发场景下事件可能静默丢失
- 无背压机制，生产者速度超过消费者时数据丢失
- 64个槽位对于游戏事件可能不足

**建议方案**:
```kotlin
private val eventChannel = Channel<DomainEvent>(capacity = Channel.UNLIMITED)

suspend fun emit(event: DomainEvent) {
    eventChannel.send(event)  // 挂起而非丢弃
}
```

---

### 2.3 同步通知阻塞 [高危]

**位置**: `core/event/GameEvents.kt:203-211`

**问题描述**:

```kotlin
private fun notifySubscribers(event: DomainEvent) {
    subscribers[event.type]?.forEach { subscriber ->
        try {
            subscriber.onEvent(event)  // 同步调用
        } catch (e: Exception) {
            android.util.Log.e("EventBus", "Error notifying subscriber", e)
        }
    }
}
```

**影响**:
- 慢订阅者会阻塞后续订阅者的执行
- 单个订阅者异常不影响其他订阅者，但无熔断机制
- 长时间运行的订阅者会拖慢整个事件处理

**建议方案**:
```kotlin
private fun notifySubscribers(event: DomainEvent) {
    subscribers[event.type]?.forEach { config ->
        config.scope.launch {
            withRetry(config.retryPolicy) {
                config.subscriber.onEvent(event)
            }
        }
    }
}
```

---

### 2.4 EventStore 内存泄漏 [高危]

**位置**: `data/v2/eventsourcing/EventStore.kt:119`

**问题描述**:

```kotlin
private val eventCache = ConcurrentHashMap<String, DomainEvent>()  // 无大小限制
```

**影响**:
- 长时间运行后内存持续增长
- 无淘汰策略，旧事件永不清理

**建议方案**:
```kotlin
private val eventCache = object : LinkedHashMap<String, DomainEvent>(
    1000, 0.75f, true
) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, DomainEvent>?): Boolean {
        return size > MAX_CACHE_SIZE
    }
}
```

---

### 2.5 空快照问题 [高危]

**位置**: `data/v2/eventsourcing/EventStore.kt:328-329`

**问题描述**:

```kotlin
private suspend fun createSnapshotAsync(...) {
    val state = ByteArray(0)  // 空状态
    val snapshot = Snapshot(..., state = state, ...)
}
```

**影响**:
- 快照无法用于状态恢复
- 事件溯源的核心功能失效

**建议方案**:
```kotlin
suspend fun <T> createSnapshot(
    aggregateType: String,
    aggregateId: String,
    state: T,
    version: Long
) {
    val serialized = serializer.serializeState(state)
    // ...
}
```

---

### 2.6 缺少事件重放 [中危]

**位置**: `data/v2/eventsourcing/EventStore.kt`

**问题描述**:  
EventStore 存储了事件但没有提供从事件流重建状态的能力。

**建议方案**:
```kotlin
suspend fun <T> rebuildState(
    aggregateType: String,
    aggregateId: String,
    initialState: T,
    applier: (T, DomainEvent) -> T
): T {
    var state = initialState
    getEventStream(aggregateType, aggregateId).events
        .sortedBy { it.version }
        .forEach { state = applier(state, it) }
    return state
}
```

---

### 2.7 StateChangeRequestBus 静默失败 [中危]

**位置**: `core/state/StateChangeRequestBus.kt:62-64`

**问题描述**:

```kotlin
fun submit(request: StateChangeRequest): Boolean {
    return _requests.tryEmit(request)  // 调用方可能忽略返回值
}
```

**影响**:
- 请求失败时无告警
- 调用方可能假设请求成功

**建议方案**:
```kotlin
suspend fun submit(request: StateChangeRequest) {
    _requests.emit(request)  // 挂起直到成功
}

fun submitOrThrow(request: StateChangeRequest) {
    if (!_requests.tryEmit(request)) {
        throw StateChangeException("Failed to submit request: buffer full")
    }
}
```

---

### 2.8 线程安全问题 [中危]

**位置**: `core/event/GameEvents.kt:214-235`

**问题描述**:

```kotlin
class DomainEventHistory(private val maxSize: Int = 100) {
    private val history = mutableListOf<DomainEvent>()  // 非线程安全
}
```

**建议方案**:
```kotlin
class DomainEventHistory(private val maxSize: Int = 100) {
    private val history = CopyOnWriteArrayList<DomainEvent>()
}
```

---

### 2.9 子系统架构问题 [中危]

**位置**: 
- `core/engine/subsystem/CultivationSubsystem.kt`
- `core/engine/subsystem/TimeSubsystem.kt`
- `core/engine/subsystem/DiscipleLifecycleSubsystem.kt`

**问题描述**:

```kotlin
class CultivationSubsystem ... : BaseGameSubsystem() {  // 未使用事件驱动
    
    override suspend fun processTick(...): GameState {
        return state  // 无实际处理
    }
}
```

**影响**:
- 子系统未响应事件
- EventDrivenSubsystem 基类未被使用

---

## 三、缺失的关键特性

| 特性 | 当前状态 | 影响 |
|------|----------|------|
| 事件优先级 | 缺失 | 紧急事件无法优先处理 |
| 事件过滤/路由 | 缺失 | 订阅者收到所有订阅类型事件 |
| 事件去重 | 缺失 | 重复事件触发多次处理 |
| 死信队列 | 缺失 | 处理失败的事件永久丢失 |
| 监控指标 | 缺失 | 无法观测延迟、丢失率 |
| 事件重放 | 缺失 | 无法从历史重建状态 |
| 因果追踪 | 部分实现 | causationId/correlationId 未被使用 |

---

## 四、优化方案

### 4.1 增强型 EventBus

```kotlin
@Singleton
class EnhancedEventBus @Inject constructor() {
    
    enum class EventPriority { HIGH, NORMAL, LOW }
    
    private val highPriorityChannel = Channel<DomainEvent>(capacity = 100)
    private val normalPriorityChannel = Channel<DomainEvent>(capacity = 500)
    private val lowPriorityChannel = Channel<DomainEvent>(capacity = 1000)
    
    private val subscriptions = ConcurrentHashMap<String, CopyOnWriteArrayList<SubscriptionConfig>>()
    private val deadLetterQueue = Channel<DeadLetter>(capacity = 1000)
    private val metrics = EventMetrics()
    
    data class SubscriptionConfig(
        val subscriber: DomainEventSubscriber,
        val dispatcher: CoroutineDispatcher,
        val retryPolicy: RetryPolicy,
        val filter: ((DomainEvent) -> Boolean)?
    )
    
    data class RetryPolicy(
        val maxRetries: Int,
        val initialDelayMs: Long,
        val maxDelayMs: Long,
        val multiplier: Double
    ) {
        companion object {
            val NONE = RetryPolicy(0, 0, 0, 1.0)
            val DEFAULT = RetryPolicy(3, 100, 5000, 2.0)
        }
    }
    
    suspend fun emit(
        event: DomainEvent,
        priority: EventPriority = EventPriority.NORMAL
    ) {
        metrics.recordEmit(event.type, priority)
        when (priority) {
            EventPriority.HIGH -> highPriorityChannel.send(event)
            EventPriority.NORMAL -> normalPriorityChannel.send(event)
            EventPriority.LOW -> lowPriorityChannel.send(event)
        }
    }
    
    fun subscribe(
        subscriber: DomainEventSubscriber,
        config: SubscriptionConfig.() -> Unit = {}
    ) {
        val subscriptionConfig = SubscriptionConfig(
            subscriber = subscriber,
            dispatcher = Dispatchers.Default,
            retryPolicy = RetryPolicy.DEFAULT,
            filter = null
        ).apply(config)
        
        subscriber.subscribedTypes.forEach { type ->
            subscriptions.computeIfAbsent(type) { CopyOnWriteArrayList() }
                .add(subscriptionConfig)
        }
    }
    
    private suspend fun processEvent(event: DomainEvent) {
        subscriptions[event.type]?.forEach { config ->
            config.subscriber.scope.launch(config.dispatcher) {
                try {
                    if (config.filter?.invoke(event) != false) {
                        executeWithRetry(config.retryPolicy) {
                            config.subscriber.onEvent(event)
                        }
                    }
                } catch (e: Exception) {
                    handleFailedEvent(event, config, e)
                }
            }
        }
    }
    
    private suspend fun handleFailedEvent(
        event: DomainEvent,
        config: SubscriptionConfig,
        error: Exception
    ) {
        metrics.recordError(event.type, error::class.simpleName ?: "Unknown")
        deadLetterQueue.send(DeadLetter(event, config.subscriber, error))
    }
}
```

### 4.2 完善事件溯源

```kotlin
class EnhancedEventStore(
    private val context: Context,
    private val serializer: EventSerializer,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
) {
    companion object {
        private const val MAX_CACHE_SIZE = 10000
        private const val SNAPSHOT_THRESHOLD = 100L
    }
    
    private val eventCache = LruCache<String, DomainEvent>(MAX_CACHE_SIZE)
    private val writeQueue = Channel<WriteRequest>(capacity = 1000)
    private val writeMutex = Mutex()
    
    data class WriteRequest(
        val event: DomainEvent,
        val deferred: CompletableDeferred<Boolean>
    )
    
    init {
        startWriteWorker()
        startCompactionWorker()
    }
    
    suspend fun append(event: DomainEvent): Boolean {
        val deferred = CompletableDeferred<Boolean>()
        writeQueue.send(WriteRequest(event, deferred))
        return deferred.await()
    }
    
    suspend fun <T : Any> rebuildAggregate(
        aggregateType: String,
        aggregateId: String,
        initialState: T,
        applier: (T, DomainEvent) -> T
    ): T {
        val snapshot = loadSnapshot(aggregateType, aggregateId)
        var state = snapshot?.let { 
            serializer.deserializeState(it.state) as T 
        } ?: initialState
        
        val fromVersion = snapshot?.version ?: 0L
        val events = getEventStream(aggregateType, aggregateId, fromVersion).events
        
        events.sortedBy { it.version }.forEach { event ->
            state = applier(state, event)
        }
        
        return state
    }
    
    suspend fun createSnapshot(
        aggregateType: String,
        aggregateId: String,
        state: Any,
        version: Long
    ) {
        val serialized = serializer.serializeState(state)
        val checksum = calculateChecksum(serialized)
        
        val snapshot = Snapshot(
            aggregateType = aggregateType,
            aggregateId = aggregateId,
            version = version,
            timestamp = System.currentTimeMillis(),
            state = serialized,
            checksum = checksum
        )
        
        saveSnapshot(snapshot)
    }
    
    private fun startWriteWorker() {
        scope.launch {
            while (isActive) {
                val request = writeQueue.receive()
                val success = writeMutex.withLock {
                    try {
                        appendEventToFile(request.event)
                        true
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to write event", e)
                        false
                    }
                }
                request.deferred.complete(success)
            }
        }
    }
}
```

### 4.3 批处理状态变更请求

```kotlin
@Singleton
class BatchedStateChangeRequestBus @Inject constructor(
    private val stateManager: UnifiedGameStateManager,
    private val eventBus: EventBus
) {
    private val pendingRequests = ConcurrentHashMap<String, StateChangeRequest>()
    private val batchMutex = Mutex()
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    
    private var lastFlushTime = 0L
    private val flushIntervalMs = 100L
    
    fun submit(request: StateChangeRequest): Boolean {
        val key = generateDedupKey(request)
        pendingRequests[key] = request
        
        if (System.currentTimeMillis() - lastFlushTime > flushIntervalMs) {
            scope.launch { flushBatch() }
        }
        return true
    }
    
    suspend fun flushBatch() {
        batchMutex.withLock {
            val batch = pendingRequests.values.toList()
            pendingRequests.clear()
            lastFlushTime = System.currentTimeMillis()
            
            val merged = mergeRequests(batch)
            merged.forEach { processRequest(it) }
        }
    }
    
    private fun mergeRequests(requests: List<StateChangeRequest>): List<StateChangeRequest> {
        val grouped = requests.groupBy { it::class }
        val result = mutableListOf<StateChangeRequest>()
        
        grouped.forEach { (type, reqs) ->
            when (type) {
                StateChangeRequest.UpdateDiscipleCultivation::class -> {
                    reqs.groupBy { (it as StateChangeRequest.UpdateDiscipleCultivation).discipleId }
                        .forEach { (_, discipleReqs) ->
                            val totalDelta = discipleReqs.sumOf { 
                                (it as StateChangeRequest.UpdateDiscipleCultivation).cultivationDelta 
                            }
                            result.add(StateChangeRequest.UpdateDiscipleCultivation(
                                discipleId = discipleReqs.first().let { 
                                    (it as StateChangeRequest.UpdateDiscipleCultivation).discipleId 
                                },
                                cultivationDelta = totalDelta
                            ))
                        }
                }
                else -> result.addAll(reqs)
            }
        }
        
        return result
    }
    
    private fun generateDedupKey(request: StateChangeRequest): String {
        return when (request) {
            is StateChangeRequest.UpdateDiscipleCultivation -> 
                "${request.discipleId}:cultivation:${request.timestamp / 1000}"
            is StateChangeRequest.UpdateDiscipleRealm -> 
                "${request.discipleId}:realm"
            is StateChangeRequest.UpdateSpiritStones -> 
                "spiritStones:${request.timestamp / 1000}"
            else -> "${request.timestamp}:${request.source}"
        }
    }
}
```

### 4.4 事件监控指标

```kotlin
@Singleton
class EventMetrics @Inject constructor() {
    
    private val emitCounters = ConcurrentHashMap<String, AtomicLong>()
    private val processCounters = ConcurrentHashMap<String, AtomicLong>()
    private val errorCounters = ConcurrentHashMap<String, AtomicLong>()
    private val latencyAccumulators = ConcurrentHashMap<String, AtomicLong>()
    
    private val startTime = System.currentTimeMillis()
    
    fun recordEmit(eventType: String, priority: EventBus.EventPriority) {
        emitCounters.computeIfAbsent(eventType) { AtomicLong() }.incrementAndGet()
    }
    
    fun recordProcess(eventType: String, latencyMs: Long, success: Boolean) {
        processCounters.computeIfAbsent(eventType) { AtomicLong() }.incrementAndGet()
        latencyAccumulators.computeIfAbsent(eventType) { AtomicLong() }.addAndGet(latencyMs)
        if (!success) {
            errorCounters.computeIfAbsent(eventType) { AtomicLong() }.incrementAndGet()
        }
    }
    
    fun getStats(): EventSystemStats {
        val allTypes = emitCounters.keys + processCounters.keys + errorCounters.keys
        
        val typeStats = allTypes.associateWith { type ->
            val emitted = emitCounters[type]?.get() ?: 0
            val processed = processCounters[type]?.get() ?: 0
            val errors = errorCounters[type]?.get() ?: 0
            val totalLatency = latencyAccumulators[type]?.get() ?: 0
            
            EventTypeStats(
                emitted = emitted,
                processed = processed,
                errors = errors,
                avgLatencyMs = if (processed > 0) totalLatency / processed else 0,
                errorRate = if (processed > 0) errors.toDouble() / processed else 0.0
            )
        }
        
        return EventSystemStats(
            uptimeMs = System.currentTimeMillis() - startTime,
            totalEmitted = emitCounters.values.sumOf { it.get() },
            totalProcessed = processCounters.values.sumOf { it.get() },
            totalErrors = errorCounters.values.sumOf { it.get() },
            typeStats = typeStats
        )
    }
    
    data class EventSystemStats(
        val uptimeMs: Long,
        val totalEmitted: Long,
        val totalProcessed: Long,
        val totalErrors: Long,
        val typeStats: Map<String, EventTypeStats>
    )
    
    data class EventTypeStats(
        val emitted: Long,
        val processed: Long,
        val errors: Long,
        val avgLatencyMs: Long,
        val errorRate: Double
    )
}
```

---

## 五、实施优先级

| 优先级 | 问题 | 工作量 | 风险 |
|--------|------|--------|------|
| P0 | 类型定义冲突 | 2天 | 高 - 需要修改所有事件类 |
| P0 | EventBus 事件丢失 | 1天 | 中 - 需要测试并发场景 |
| P1 | EventStore 内存泄漏 | 0.5天 | 低 |
| P1 | 空快照问题 | 1天 | 中 - 需要状态序列化 |
| P2 | 子系统事件驱动改造 | 3天 | 中 - 需要逐个迁移 |
| P2 | 监控指标 | 1天 | 低 |
| P3 | 批处理优化 | 2天 | 低 |

---

## 六、测试建议

### 6.1 单元测试

```kotlin
@Test
fun `emit should not lose events under high load`() = runTest {
    val eventBus = EventBus()
    val receivedEvents = mutableListOf<DomainEvent>()
    
    eventBus.subscribe(object : DomainEventSubscriber {
        override val subscribedTypes = setOf("test")
        override fun onEvent(event: DomainEvent) {
            receivedEvents.add(event)
        }
    })
    
    repeat(1000) {
        eventBus.emit(TestEvent(id = it))
    }
    
    eventually {
        assertEquals(1000, receivedEvents.size)
    }
}

@Test
fun `slow subscriber should not block others`() = runTest {
    val eventBus = EventBus()
    val fastSubscriberReceived = mutableListOf<DomainEvent>()
    
    eventBus.subscribe(SlowSubscriber())
    eventBus.subscribe(FastSubscriber(fastSubscriberReceived))
    
    eventBus.emit(TestEvent())
    
    eventually {
        assertTrue(fastSubscriberReceived.isNotEmpty())
    }
}
```

### 6.2 压力测试

```kotlin
@Test
fun `event system should handle sustained high load`() = runTest {
    val eventBus = EventBus()
    val metrics = EventMetrics()
    
    val job = launch {
        repeat(100_000) {
            eventBus.emit(TestEvent(id = it))
            metrics.recordEmit("test", EventPriority.NORMAL)
        }
    }
    
    job.join()
    
    val stats = metrics.getStats()
    assertTrue(stats.totalEmitted >= 100_000)
}
```

---

## 七、参考资源

- [Kotlin Flow 背压处理](https://kotlinlang.org/docs/flow.html#buffering)
- [事件溯源模式](https://martinfowler.com/eaaDev/EventSourcing.html)
- [CQRS 模式](https://martinfowler.com/bliki/CQRS.html)
- [Android 协程最佳实践](https://developer.android.com/kotlin/coroutines/coroutines-best-practices)
