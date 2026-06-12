package com.xianxia.sect.core.event

/**
 * EventBus 消费者审计
 *
 * 记录所有事件消费者及其线程模型、背压策略、错误处理方式。
 * 用于识别潜在的线程安全问题和消息丢失风险。
 *
 * === EventBus 基础设施 ===
 *
 * 传输层: Channel<DomainEvent>(capacity = 256)
 *   - 非挂起发送 (trySend) 满时静默丢弃，仅计数 + 周期日志
 *   - 挂起发送 (emitTyped) 通过 scope.launch 中转，可背压但不会阻塞调用方
 *
 * 分发路径 (两条并行):
 *   1. Flow 路径: eventChannel.receiveAsFlow() → events: Flow<DomainEvent>
 *      消费者通过 .collect {} 订阅，背压取决于 collect 内处理速度
 *   2. Subscriber 路径: ConcurrentHashMap<String, CopyOnWriteArrayList<DomainEventSubscriber>>
 *      startProcessing() 中 for-loop 消费 Channel，每事件调用 notifySubscribers()
 *      notifySubscribers 为每个 subscriber 独立 launch 协程，异常 try-catch 隔离
 *
 * 事件生产者:
 *   - CombatService: emitSync(DeathEvent) — 非挂起，满时丢弃
 *   - ExplorationService: emitSync(DeathEvent) — 非挂起，满时丢弃
 *
 * === 风险等级判定标准 ===
 *
 * HIGH:  运行于主线程 / 可阻塞 EventBus 分发循环 / 异常可传播导致 bus 崩溃
 * MEDIUM: 运行于 applicationScope 但无背压控制 / 状态竞态风险
 * LOW:   运行于独立 scope / 有错误隔离 / 无阻塞风险
 */
object EventBusAudit {

    data class ConsumerRecord(
        val consumerClass: String,
        val eventType: String,
        val threading: String,
        val backpressure: String,
        val errorHandling: String,
        val riskLevel: String,
        val notes: String = ""
    )

    val consumers: List<ConsumerRecord> = listOf(

        // ── Subscriber 路径消费者 (DomainEventSubscriber) ──

        ConsumerRecord(
            consumerClass = "DiplomacyService",
            eventType = "battle_completed (BattleCompletedEvent, 仅 victory)",
            threading = "applicationScope.launch — 非主线程，Dispatchers.Default 继承",
            backpressure = "无背压控制: scope.launch 即发即弃，高频率战斗可堆积协程",
            errorHandling = "EventBus.notifySubscribers 内 try-catch 隔离; onEvent 内部无 try-catch, StateAccessor 写入失败会抛出",
            riskLevel = "MEDIUM",
            notes = "onEvent 内 scope.launch 异步写入状态，与外部方法(如 giftSpiritStones)存在竞态; " +
                    "init{} 中订阅，无 unsubscribe 逻辑(单例无生命周期问题); " +
                    "BattleCompletedEvent 过滤条件为 victory=true，实际触发频率取决于战斗节奏"
        ),

        ConsumerRecord(
            consumerClass = "EconomySubsystem",
            eventType = "building_completed (BuildingCompletedEvent)",
            threading = "applicationScope.launch — 非主线程",
            backpressure = "无背压控制: scope.launch 即发即弃",
            errorHandling = "EventBus 层 try-catch 隔离; onEvent 内无 try-catch, cultivationService.processSpiritMineProduction() 异常会传播",
            riskLevel = "MEDIUM",
            notes = "initialize()/release() 中 subscribe/unsubscribe 配对正确; " +
                    "processSpiritMineProduction 为异步启动但无结果检查，静默失败风险"
        ),

        ConsumerRecord(
            consumerClass = "PartnerSystem",
            eventType = "breakthrough (BreakthroughEvent, 仅 success=true)",
            threading = "applicationScope.launch — 非主线程",
            backpressure = "无背压控制: scope.launch 即发即弃",
            errorHandling = "EventBus 层 try-catch 隔离; onEvent 内 stateStore.update {} 异常会传播但被 EventBus 捕获",
            riskLevel = "LOW",
            notes = "initialize()/release() 中 subscribe/unsubscribe 配对正确; " +
                    "突破成功后伴侣忠诚度+3，逻辑简单，竞态窗口小"
        ),

        // ── Flow 路径消费者 (eventBus.events.collect) ──

        ConsumerRecord(
            consumerClass = "GameEngineCore",
            eventType = "death (DeathEvent) — 全事件 collect 后类型过滤",
            threading = "engineScope (Dispatchers.Default + SupervisorJob) — 非主线程",
            backpressure = "Channel 背压: collect 挂起等待，不会堆积; 但处理逻辑为空 (注释: 消息系统已移除)",
            errorHandling = "engineExceptionHandler 捕获 CancellationException 以外的异常; collect 内无 try-catch",
            riskLevel = "LOW",
            notes = "当前 DeathEvent 处理体为空，仅消费事件不执行操作; " +
                    "startListening() 有幂等检查 (deathEventJob?.isActive); " +
                    "shutdown() 中 cancel deathEventJob; " +
                    "⚠️ 全事件 collect: 即使只关心 DeathEvent，也会消费 Channel 中所有事件，" +
                    "与 Subscriber 路径共享同一 Channel，不会互相阻塞 (collect 和 for-loop 各自独立消费)"
        ),

        // ── 事件生产者 (仅记录，非消费者) ──
        // 以下两条记录事件来源侧的信息，帮助理解事件流向

        ConsumerRecord(
            consumerClass = "[Producer] CombatService",
            eventType = "death (DeathEvent, cause=战斗阵亡)",
            threading = "调用线程 (非挂起 emitSync)",
            backpressure = "trySend 满时丢弃，仅计数; Channel capacity=256",
            errorHandling = "emitSync 返回 Boolean 表示发送是否成功，调用方未检查返回值",
            riskLevel = "MEDIUM",
            notes = "仅在 isOutsideSect=true 时发送 DeathEvent; " +
                    "emitSync 不检查返回值，事件丢弃时无业务层感知; " +
                    "高频战斗场景下 Channel 可能饱和"
        ),

        ConsumerRecord(
            consumerClass = "[Producer] ExplorationService",
            eventType = "death (DeathEvent, cause=探索阵亡)",
            threading = "调用线程 (非挂起 emitSync)",
            backpressure = "trySend 满时丢弃，仅计数; Channel capacity=256",
            errorHandling = "emitSync 返回值未检查",
            riskLevel = "MEDIUM",
            notes = "探索阵亡事件丢弃时无业务层感知; " +
                    "与 CombatService 共享同一 Channel，两者同时高频发送时竞争加剧"
        )
    )

    val summary: String by lazy {
        val total = consumers.size
        val producers = consumers.count { it.consumerClass.startsWith("[Producer]") }
        val consumersOnly = total - producers
        val highRisk = consumers.count { it.riskLevel == "HIGH" }
        val mediumRisk = consumers.count { it.riskLevel == "MEDIUM" }
        val lowRisk = consumers.count { it.riskLevel == "LOW" }
        """
        |EventBus 审计摘要
        |─────────────────
        |消费者数量: $consumersOnly
        |生产者数量: $producers
        |风险分布: HIGH=$highRisk, MEDIUM=$mediumRisk, LOW=$lowRisk
        |
        |关键发现:
        |1. Channel capacity=256, trySend 满时静默丢弃 — 无业务层感知
        |2. 所有 Subscriber 路径消费者使用 scope.launch 即发即弃 — 无背压/无结果检查
        |3. GameEngineCore 全事件 collect 但处理体为空 — 消费 Channel 位置但不执行操作
        |4. DiplomacyService onEvent 内异步状态写入与外部方法存在竞态风险
        |5. emitSync 返回值均未检查 — 事件丢失无感知
        |6. 无消费者对 DeathEvent 做实际业务处理 (GameEngineCore 处理体为空)
        """.trimMargin()
    }
}
