package com.xianxia.sect.data.concurrent

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.job
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton

enum class StorageSubsystem {
    SAVE,
    LOAD,
    CACHE,
    WAL,
    RECOVERY,
    PRUNING,
    MONITOR
}

@Singleton
class StorageScopeManager @Inject constructor() {

    private val rootJob = SupervisorJob()
    private val rootScope = CoroutineScope(Dispatchers.IO + rootJob)

    private val subsystemScopes = mutableMapOf<StorageSubsystem, CoroutineScope>()
    private val activeOperations = AtomicInteger(0)
    private val subsystemActiveOps = mutableMapOf<StorageSubsystem, AtomicInteger>()
    private val isShutdown = AtomicBoolean(false)

    init {
        for (subsystem in StorageSubsystem.entries) {
            subsystemScopes[subsystem] = CoroutineScope(rootJob + Dispatchers.IO)
            subsystemActiveOps[subsystem] = AtomicInteger(0)
        }
        Log.i(TAG, "StorageScopeManager initialized with ${StorageSubsystem.entries.size} subsystem scopes")
    }

    fun scopeFor(subsystem: StorageSubsystem): CoroutineScope {
        if (isShutdown.get()) {
            Log.w(TAG, "Attempting to get scope for $subsystem after shutdown, returning cancelled scope")
            return CoroutineScope(Dispatchers.IO + kotlinx.coroutines.Job().apply { cancel() })
        }
        return subsystemScopes[subsystem] ?: rootScope
    }

    val globalScope: CoroutineScope get() = rootScope

    fun beginOperation(subsystem: StorageSubsystem) {
        activeOperations.incrementAndGet()
        subsystemActiveOps[subsystem]?.incrementAndGet()
    }

    fun endOperation(subsystem: StorageSubsystem) {
        activeOperations.decrementAndGet()
        subsystemActiveOps[subsystem]?.decrementAndGet()
    }

    inline fun <T> withOperationTracking(subsystem: StorageSubsystem, block: () -> T): T {
        beginOperation(subsystem)
        return try {
            block()
        } finally {
            endOperation(subsystem)
        }
    }

    fun activeOperationCount(): Int = activeOperations.get()

    fun activeOperationCount(subsystem: StorageSubsystem): Int =
        subsystemActiveOps[subsystem]?.get() ?: 0

    fun isHealthy(): Boolean {
        if (isShutdown.get()) return false
        if (!rootScope.coroutineContext.job.isActive) return false
        return subsystemScopes.all { (_, scope) ->
            scope.coroutineContext.job.isActive
        }
    }

    fun shutdown() {
        if (!isShutdown.compareAndSet(false, true)) {
            Log.w(TAG, "Shutdown already in progress, ignoring duplicate call")
            return
        }

        Log.i(TAG, "Shutting down StorageScopeManager - active operations: ${activeOperations.get()}")

        val pendingOps = activeOperations.get()
        if (pendingOps > 0) {
            Log.w(TAG, "Shutting down with $pendingOps active operations")
        }

        rootJob.cancel()
        Log.i(TAG, "StorageScopeManager shutdown complete")
    }

    fun getDiagnostics(): ScopeDiagnostics {
        return ScopeDiagnostics(
            isShutdown = isShutdown.get(),
            isHealthy = isHealthy(),
            totalActiveOps = activeOperations.get(),
            subsystemStats = StorageSubsystem.entries.associateWith {
                SubsystemStats(
                    activeOps = subsystemActiveOps[it]?.get() ?: 0,
                    isScopeActive = subsystemScopes[it]?.coroutineContext?.job?.isActive ?: false
                )
            }
        )
    }

    data class SubsystemStats(
        val activeOps: Int,
        val isScopeActive: Boolean
    )

    data class ScopeDiagnostics(
        val isShutdown: Boolean,
        val isHealthy: Boolean,
        val totalActiveOps: Int,
        val subsystemStats: Map<StorageSubsystem, SubsystemStats>
    )

    companion object {
        private const val TAG = "StorageScopeManager"
    }
}
