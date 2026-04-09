package com.xianxia.sect.data.memory

import android.util.Log
import com.xianxia.sect.data.cache.GameDataCacheManager
import com.xianxia.sect.data.concurrent.StorageScopeManager
import com.xianxia.sect.data.concurrent.StorageSubsystem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

data class MemoryGuardConfig(
    val checkIntervalMs: Long = 10_000L,
    val warningThresholdPercent: Double = 0.75,
    val criticalThresholdPercent: Double = 0.85,
    val emergencyThresholdPercent: Double = 0.92,
    val jvmWarningThresholdPercent: Double = 0.70,
    val jvmCriticalThresholdPercent: Double = 0.80,
    val jvmEmergencyThresholdPercent: Double = 0.90,
    val cooldownMs: Long = 30_000L,
    val maxGcAttempts: Int = 3
)

enum class MemoryGuardLevel {
    NORMAL,
    WARNING,
    CRITICAL,
    EMERGENCY
}

data class MemoryGuardSnapshot(
    val level: MemoryGuardLevel,
    val systemAvailablePercent: Double,
    val jvmUsedPercent: Double,
    val totalChecks: Long,
    val totalWarnings: Long,
    val totalCriticalEvents: Long,
    val totalEmergencyEvents: Long,
    val totalGcInvocations: Long,
    val totalCacheEvictions: Long,
    val lastActionTime: Long,
    val lastActionDescription: String
)

@Singleton
class ProactiveMemoryGuard @Inject constructor(
    private val memoryManager: DynamicMemoryManager,
    private val cacheManager: GameDataCacheManager,
    private val scopeManager: StorageScopeManager
) {
    private val config = MemoryGuardConfig()
    private var monitorJob: Job? = null

    private val totalChecks = AtomicLong(0)
    private val totalWarnings = AtomicLong(0)
    private val totalCriticalEvents = AtomicLong(0)
    private val totalEmergencyEvents = AtomicLong(0)
    private val totalGcInvocations = AtomicLong(0)
    private val totalCacheEvictions = AtomicLong(0)
    private var lastActionTime = 0L
    private var lastActionDescription = ""

    private val _currentLevel = MutableStateFlow(MemoryGuardLevel.NORMAL)
    val currentLevel: StateFlow<MemoryGuardLevel> = _currentLevel.asStateFlow()

    private val _snapshot = MutableStateFlow(
        MemoryGuardSnapshot(
            level = MemoryGuardLevel.NORMAL,
            systemAvailablePercent = 100.0,
            jvmUsedPercent = 0.0,
            totalChecks = 0,
            totalWarnings = 0,
            totalCriticalEvents = 0,
            totalEmergencyEvents = 0,
            totalGcInvocations = 0,
            totalCacheEvictions = 0,
            lastActionTime = 0,
            lastActionDescription = ""
        )
    )
    val snapshot: StateFlow<MemoryGuardSnapshot> = _snapshot.asStateFlow()

    fun startMonitoring() {
        if (monitorJob?.isActive == true) {
            Log.w(TAG, "Memory guard monitoring already active")
            return
        }

        monitorJob = scopeManager.scopeFor(StorageSubsystem.MONITOR).launch {
            Log.i(TAG, "Proactive memory guard started (interval=${config.checkIntervalMs}ms)")
            while (isActive) {
                try {
                    performCheck()
                } catch (e: Exception) {
                    Log.e(TAG, "Memory guard check failed", e)
                }
                delay(config.checkIntervalMs)
            }
        }
    }

    fun stopMonitoring() {
        monitorJob?.cancel()
        monitorJob = null
        Log.i(TAG, "Proactive memory guard stopped")
    }

    private suspend fun performCheck() {
        totalChecks.incrementAndGet()

        val memSnapshot = memoryManager.getMemorySnapshot()
        val systemAvailPercent = memSnapshot.availablePercent
        val jvmUsedPercent = memSnapshot.jvmUsagePercent

        val newLevel = when {
            systemAvailPercent < (100.0 - config.emergencyThresholdPercent * 100.0) ||
                jvmUsedPercent > config.jvmEmergencyThresholdPercent * 100.0 -> MemoryGuardLevel.EMERGENCY
            systemAvailPercent < (100.0 - config.criticalThresholdPercent * 100.0) ||
                jvmUsedPercent > config.jvmCriticalThresholdPercent * 100.0 -> MemoryGuardLevel.CRITICAL
            systemAvailPercent < (100.0 - config.warningThresholdPercent * 100.0) ||
                jvmUsedPercent > config.jvmWarningThresholdPercent * 100.0 -> MemoryGuardLevel.WARNING
            else -> MemoryGuardLevel.NORMAL
        }

        val previousLevel = _currentLevel.value
        _currentLevel.value = newLevel

        if (newLevel != previousLevel) {
            Log.w(TAG, "Memory guard level changed: $previousLevel -> $newLevel " +
                "(systemAvail=${String.format("%.1f%%", systemAvailPercent)}, " +
                "jvmUsed=${String.format("%.1f%%", jvmUsedPercent)})")
        }

        when (newLevel) {
            MemoryGuardLevel.WARNING -> handleWarning()
            MemoryGuardLevel.CRITICAL -> handleCritical()
            MemoryGuardLevel.EMERGENCY -> handleEmergency()
            MemoryGuardLevel.NORMAL -> {}
        }

        updateSnapshot(systemAvailPercent, jvmUsedPercent)
    }

    private suspend fun handleWarning() {
        if (!isCooldownElapsed()) return

        totalWarnings.incrementAndGet()
        Log.d(TAG, "Memory WARNING: requesting cache reduction")
        memoryManager.requestDegradation()
        recordAction("Requested memory degradation (warning level)")
    }

    private suspend fun handleCritical() {
        if (!isCooldownElapsed()) return

        totalCriticalEvents.incrementAndGet()
        Log.w(TAG, "Memory CRITICAL: forcing GC and aggressive cache eviction")

        withContext(Dispatchers.IO) {
            memoryManager.forceGcAndWait(300L)
            totalGcInvocations.incrementAndGet()
        }

        cacheManager.evictColdData()
        totalCacheEvictions.incrementAndGet()

        memoryManager.requestDegradation()
        recordAction("Forced GC + cache eviction (critical level)")
    }

    private suspend fun handleEmergency() {
        totalEmergencyEvents.incrementAndGet()
        Log.e(TAG, "Memory EMERGENCY: aggressive cleanup required")

        withContext(Dispatchers.IO) {
            repeat(config.maxGcAttempts) { attempt ->
                memoryManager.forceGcAndWait(500L)
                totalGcInvocations.incrementAndGet()

                val snapshot = memoryManager.getMemorySnapshot()
                if (snapshot.pressureLevel.ordinal < MemoryPressureLevel.HIGH.ordinal) {
                    Log.i(TAG, "Memory recovered after GC attempt ${attempt + 1}")
                    return@withContext
                }
            }
        }

        cacheManager.evictColdData()
        totalCacheEvictions.incrementAndGet()

        memoryManager.requestDegradation()
        recordAction("Emergency GC + full cache eviction")
    }

    private fun isCooldownElapsed(): Boolean {
        val elapsed = System.currentTimeMillis() - lastActionTime
        return elapsed >= config.cooldownMs
    }

    private fun recordAction(description: String) {
        lastActionTime = System.currentTimeMillis()
        lastActionDescription = description
    }

    private fun updateSnapshot(systemAvailPercent: Double, jvmUsedPercent: Double) {
        _snapshot.value = MemoryGuardSnapshot(
            level = _currentLevel.value,
            systemAvailablePercent = systemAvailPercent,
            jvmUsedPercent = jvmUsedPercent,
            totalChecks = totalChecks.get(),
            totalWarnings = totalWarnings.get(),
            totalCriticalEvents = totalCriticalEvents.get(),
            totalEmergencyEvents = totalEmergencyEvents.get(),
            totalGcInvocations = totalGcInvocations.get(),
            totalCacheEvictions = totalCacheEvictions.get(),
            lastActionTime = lastActionTime,
            lastActionDescription = lastActionDescription
        )
    }

    fun getDiagnostics(): MemoryGuardSnapshot = _snapshot.value

    companion object {
        private const val TAG = "ProactiveMemoryGuard"
    }
}
