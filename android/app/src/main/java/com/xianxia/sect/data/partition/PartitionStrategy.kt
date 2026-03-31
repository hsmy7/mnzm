package com.xianxia.sect.data.partition

import android.util.Log
import com.xianxia.sect.data.cache.CacheKey
import java.util.concurrent.ConcurrentHashMap

object PartitionStrategy {

    private const val TAG = "PartitionStrategy"

    private val customStrategies = ConcurrentHashMap<DataType, ZoneStrategy>()
    private val typeAccessPatterns = ConcurrentHashMap<DataType, AccessPattern>()

    data class ZoneStrategy(
        val dataType: DataType,
        val preferredZone: DataZone,
        val promotionThreshold: PromotionThreshold = PromotionThreshold.DEFAULT,
        val demotionThreshold: DemotionThreshold = DemotionThreshold.DEFAULT,
        val customRules: List<ZoneRule> = emptyList()
    )

    data class PromotionThreshold(
        val accessCount: Int,
        val timeWindowMs: Long,
        val minDataAgeMs: Long = 0
    ) {
        companion object {
            val DEFAULT = PromotionThreshold(
                accessCount = 10,
                timeWindowMs = 3600_000L,
                minDataAgeMs = 0
            )

            val AGGRESSIVE = PromotionThreshold(
                accessCount = 5,
                timeWindowMs = 1800_000L,
                minDataAgeMs = 0
            )

            val CONSERVATIVE = PromotionThreshold(
                accessCount = 20,
                timeWindowMs = 7200_000L,
                minDataAgeMs = 0
            )
        }
    }

    data class DemotionThreshold(
        val maxAccessCount: Int,
        val timeWindowMs: Long,
        val maxDataAgeMs: Long = Long.MAX_VALUE
    ) {
        companion object {
            val DEFAULT = DemotionThreshold(
                maxAccessCount = 2,
                timeWindowMs = 3600_000L * 24,
                maxDataAgeMs = Long.MAX_VALUE
            )

            val AGGRESSIVE = DemotionThreshold(
                maxAccessCount = 5,
                timeWindowMs = 3600_000L * 12,
                maxDataAgeMs = Long.MAX_VALUE
            )

            val CONSERVATIVE = DemotionThreshold(
                maxAccessCount = 1,
                timeWindowMs = 3600_000L * 48,
                maxDataAgeMs = Long.MAX_VALUE
            )
        }
    }

    data class ZoneRule(
        val condition: RuleCondition,
        val targetZone: DataZone,
        val priority: Int = 0
    )

    data class RuleCondition(
        val field: String,
        val operator: RuleOperator,
        val value: Any
    )

    enum class RuleOperator {
        EQUALS,
        NOT_EQUALS,
        GREATER_THAN,
        LESS_THAN,
        GREATER_THAN_OR_EQUAL,
        LESS_THAN_OR_EQUAL,
        CONTAINS,
        STARTS_WITH,
        ENDS_WITH
    }

    class AccessPattern(
        val dataType: DataType,
        private val _totalAccesses: java.util.concurrent.atomic.AtomicLong = java.util.concurrent.atomic.AtomicLong(0),
        private val _lastAccessTime: java.util.concurrent.atomic.AtomicLong = java.util.concurrent.atomic.AtomicLong(0),
        private val _peakAccessRate: java.util.concurrent.atomic.AtomicReference<Double> = java.util.concurrent.atomic.AtomicReference(0.0),
        private val _averageAccessInterval: java.util.concurrent.atomic.AtomicLong = java.util.concurrent.atomic.AtomicLong(0),
        private val accessTimes: java.util.concurrent.ConcurrentLinkedQueue<Long> = java.util.concurrent.ConcurrentLinkedQueue()
    ) {
        val totalAccesses: Long get() = _totalAccesses.get()
        val lastAccessTime: Long get() = _lastAccessTime.get()
        val peakAccessRate: Double get() = _peakAccessRate.get()
        val averageAccessInterval: Long get() = _averageAccessInterval.get()

        fun recordAccess() {
            val now = System.currentTimeMillis()
            _totalAccesses.incrementAndGet()
            _lastAccessTime.set(now)
            accessTimes.add(now)
            
            while (accessTimes.size > 100) {
                accessTimes.poll()
            }
            
            updateStats()
        }

        private fun updateStats() {
            val times = accessTimes.toList()
            if (times.size >= 2) {
                var totalInterval = 0L
                for (i in 1 until times.size) {
                    totalInterval += times[i] - times[i - 1]
                }
                _averageAccessInterval.set(totalInterval / (times.size - 1))
                
                val windowStart = System.currentTimeMillis() - 3600_000L
                val recentAccesses = times.count { it > windowStart }
                _peakAccessRate.updateAndGet { current -> maxOf(current, recentAccesses.toDouble()) }
            }
        }

        fun getAccessRate(windowMs: Long): Double {
            val windowStart = System.currentTimeMillis() - windowMs
            val recentAccesses = accessTimes.count { it > windowStart }
            return recentAccesses.toDouble()
        }
    }

    fun determineZone(dataType: DataType, context: StrategyContext = StrategyContext.EMPTY): DataZone {
        val customStrategy = customStrategies[dataType]
        if (customStrategy != null) {
            return evaluateCustomStrategy(customStrategy, context)
        }

        val pattern = typeAccessPatterns[dataType]
        if (pattern != null) {
            val dynamicZone = evaluateAccessPattern(pattern, dataType)
            if (dynamicZone != null) {
                return dynamicZone
            }
        }

        return dataType.defaultZone
    }

    fun determineZone(key: CacheKey, context: StrategyContext = StrategyContext.EMPTY): DataZone {
        val dataType = DataType.fromCacheKeyType(key.type)
        return determineZone(dataType, context)
    }

    private fun evaluateCustomStrategy(strategy: ZoneStrategy, context: StrategyContext): DataZone {
        for (rule in strategy.customRules.sortedByDescending { it.priority }) {
            if (evaluateRule(rule, context)) {
                return rule.targetZone
            }
        }

        return strategy.preferredZone
    }

    private fun evaluateRule(rule: ZoneRule, context: StrategyContext): Boolean {
        val fieldValue = context.getField(rule.condition.field) ?: return false
        
        return when (rule.condition.operator) {
            RuleOperator.EQUALS -> fieldValue == rule.condition.value
            RuleOperator.NOT_EQUALS -> fieldValue != rule.condition.value
            RuleOperator.GREATER_THAN -> compareValues(fieldValue, rule.condition.value) > 0
            RuleOperator.LESS_THAN -> compareValues(fieldValue, rule.condition.value) < 0
            RuleOperator.GREATER_THAN_OR_EQUAL -> compareValues(fieldValue, rule.condition.value) >= 0
            RuleOperator.LESS_THAN_OR_EQUAL -> compareValues(fieldValue, rule.condition.value) <= 0
            RuleOperator.CONTAINS -> {
                fieldValue is String && rule.condition.value is String && 
                    fieldValue.contains(rule.condition.value)
            }
            RuleOperator.STARTS_WITH -> {
                fieldValue is String && rule.condition.value is String && 
                    fieldValue.startsWith(rule.condition.value)
            }
            RuleOperator.ENDS_WITH -> {
                fieldValue is String && rule.condition.value is String && 
                    fieldValue.endsWith(rule.condition.value)
            }
        }
    }

    private fun compareValues(a: Any, b: Any): Int {
        return when {
            a is Number && b is Number -> a.toDouble().compareTo(b.toDouble())
            a is Comparable<*> && b::class == a::class -> {
                @Suppress("UNCHECKED_CAST")
                (a as Comparable<Any>).compareTo(b)
            }
            else -> a.toString().compareTo(b.toString())
        }
    }

    private fun evaluateAccessPattern(pattern: AccessPattern, dataType: DataType): DataZone? {
        val accessRate = pattern.getAccessRate(3600_000L)
        
        return when {
            accessRate >= 60 -> DataZone.HOT
            accessRate >= 10 -> DataZone.WARM
            accessRate >= 1 -> null
            else -> DataZone.COLD
        }
    }

    fun setCustomStrategy(dataType: DataType, strategy: ZoneStrategy) {
        customStrategies[dataType] = strategy
        Log.d(TAG, "Set custom strategy for ${dataType.displayName}: ${strategy.preferredZone}")
    }

    fun removeCustomStrategy(dataType: DataType) {
        customStrategies.remove(dataType)
        Log.d(TAG, "Removed custom strategy for ${dataType.displayName}")
    }

    fun recordAccess(dataType: DataType) {
        val pattern = typeAccessPatterns.getOrPut(dataType) { AccessPattern(dataType) }
        pattern.recordAccess()
    }

    fun recordAccess(key: CacheKey) {
        val dataType = DataType.fromCacheKeyType(key.type)
        recordAccess(dataType)
    }

    fun getAccessPattern(dataType: DataType): AccessPattern? {
        return typeAccessPatterns[dataType]
    }

    fun shouldPromote(
        dataType: DataType,
        currentZone: DataZone,
        accessInfo: AccessInfo?
    ): Boolean {
        if (currentZone == DataZone.HOT) return false

        val strategy = customStrategies[dataType]
        val threshold = strategy?.promotionThreshold ?: PromotionThreshold.DEFAULT

        if (accessInfo == null) return false

        val recentAccesses = accessInfo.getRecentAccessCount(threshold.timeWindowMs)
        return recentAccesses >= threshold.accessCount
    }

    fun shouldDemote(
        dataType: DataType,
        currentZone: DataZone,
        accessInfo: AccessInfo?
    ): Boolean {
        if (currentZone == DataZone.COLD) return false

        val strategy = customStrategies[dataType]
        val threshold = strategy?.demotionThreshold ?: DemotionThreshold.DEFAULT

        if (accessInfo == null) return true

        val recentAccesses = accessInfo.getRecentAccessCount(threshold.timeWindowMs)
        return recentAccesses <= threshold.maxAccessCount
    }

    fun initializeDefaultStrategies() {
        setCustomStrategy(DataType.DISCIPLE, ZoneStrategy(
            dataType = DataType.DISCIPLE,
            preferredZone = DataZone.HOT,
            promotionThreshold = PromotionThreshold.AGGRESSIVE,
            demotionThreshold = DemotionThreshold.CONSERVATIVE
        ))

        setCustomStrategy(DataType.EQUIPMENT, ZoneStrategy(
            dataType = DataType.EQUIPMENT,
            preferredZone = DataZone.HOT,
            promotionThreshold = PromotionThreshold.AGGRESSIVE,
            demotionThreshold = DemotionThreshold.CONSERVATIVE
        ))

        setCustomStrategy(DataType.GAME_DATA, ZoneStrategy(
            dataType = DataType.GAME_DATA,
            preferredZone = DataZone.HOT,
            promotionThreshold = PromotionThreshold.AGGRESSIVE,
            demotionThreshold = DemotionThreshold.CONSERVATIVE
        ))

        setCustomStrategy(DataType.EVENT, ZoneStrategy(
            dataType = DataType.EVENT,
            preferredZone = DataZone.COLD,
            promotionThreshold = PromotionThreshold.CONSERVATIVE,
            demotionThreshold = DemotionThreshold.AGGRESSIVE
        ))

        setCustomStrategy(DataType.BATTLE_LOG, ZoneStrategy(
            dataType = DataType.BATTLE_LOG,
            preferredZone = DataZone.COLD,
            promotionThreshold = PromotionThreshold.CONSERVATIVE,
            demotionThreshold = DemotionThreshold.AGGRESSIVE
        ))

        Log.i(TAG, "Default strategies initialized")
    }

    fun clearAllStrategies() {
        customStrategies.clear()
        typeAccessPatterns.clear()
        Log.i(TAG, "All strategies cleared")
    }

    fun getStrategyStats(): Map<DataType, ZoneStrategy> {
        return customStrategies.toMap()
    }
}

data class StrategyContext(
    private val fields: Map<String, Any> = emptyMap()
) {
    fun getField(name: String): Any? = fields[name]

    fun withField(name: String, value: Any): StrategyContext {
        return StrategyContext(fields + (name to value))
    }

    fun withFields(newFields: Map<String, Any>): StrategyContext {
        return StrategyContext(fields + newFields)
    }

    companion object {
        val EMPTY = StrategyContext()
    }
}
