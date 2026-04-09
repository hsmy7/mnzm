package com.xianxia.sect.data.incremental

import android.content.Context
import android.util.Log
import com.xianxia.sect.data.model.SaveData
import com.xianxia.sect.data.model.SaveSlot
import com.xianxia.sect.data.retry.RetryPolicy
import com.xianxia.sect.data.retry.RetryConfig
import com.xianxia.sect.data.retry.RetryResult
import com.xianxia.sect.data.serialization.unified.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.*
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

@Serializable
data class DeltaMetadata(
    val version: Long,
    val timestamp: Long,
    val size: Long,
    val operationCount: Int,
    val checksum: String
)

object IncrementalStorageConfig {
    const val MAX_SLOTS = 6
    const val MAX_DELTA_CHAIN_LENGTH = 15
    const val SOFT_CHAIN_LIMIT = 8
    const val COMPACTION_THRESHOLD = 5
    const val AUTO_COMPACT_INTERVAL_MS = 180_000L
    const val MAX_DELTA_SIZE_MB = 10
    const val DELTA_DIR = "deltas"
    const val SNAPSHOT_DIR = "snapshots"
    const val CHAIN_HEALTH_CHECK_INTERVAL_MS = 60_000L
    const val URGENT_COMPACT_THRESHOLD = 12
}

@Serializable
data class SnapshotInfo(
    val version: Long,
    val timestamp: Long,
    val size: Long,
    val checksum: String,
    val isFull: Boolean
)

data class IncrementalSaveResult(
    val success: Boolean,
    val savedBytes: Long,
    val elapsedMs: Long,
    val isIncremental: Boolean,
    val deltaCount: Int,
    val error: String? = null
)

data class IncrementalLoadResult(
    val data: SaveData?,
    val elapsedMs: Long,
    val appliedDeltas: Int,
    val wasFromSnapshot: Boolean,
    val error: String? = null
)

data class IncrementalStorageStats(
    val totalDeltaSize: Long = 0,
    val deltaChainLength: Int = 0,
    val snapshotCount: Int = 0,
    val lastSnapshotTime: Long = 0,
    val lastDeltaTime: Long = 0,
    val pendingChanges: Int = 0,
    val needsCompaction: Boolean = false
)

@Serializable
data class SlotManifest(
    @SerialName("slot") val slot: Int,
    @SerialName("version") val version: Int,
    @SerialName("deltas") val deltas: List<DeltaMetadata>,
    @SerialName("snapshots") val snapshots: List<SnapshotInfo>,
    @SerialName("lastModified") val lastModified: Long
)

/**
 * Delta 链健康状态结果。
 * 用于检测和报告 delta 文件链的完整性问题。
 */
sealed class ChainHealthResult {
    data class Healthy(val chainLength: Int) : ChainHealthResult()
    data class Repairable(val brokenAt: Int, val reason: String) : ChainHealthResult()
    data class Broken(val errors: List<String>) : ChainHealthResult()
}

sealed class SmartCompactResult {
    object NoAction : SmartCompactResult()
    data class UrgentMerged(val beforeCount: Int, val afterCount: Int, val sizeBefore: Long, val sizeAfter: Long) : SmartCompactResult()
    data class ProgressiveMerged(val mergedCount: Int, val remainingCount: Int) : SmartCompactResult()
    data class Skipped(val reason: String) : SmartCompactResult()
}

data class ChainHealthReport(
    val slot: Int,
    val chainLength: Int,
    val totalSizeBytes: Long,
    val healthStatus: ChainHealthStatus,
    val recommendedAction: String?
)

enum class ChainHealthStatus { HEALTHY, WARNING, CRITICAL, BROKEN }

@Singleton
class IncrementalStorageManager @Inject constructor(
    private val context: Context,
    private val changeTracker: ChangeTracker,
    private val deltaCompressor: DeltaCompressor,
    private val saveDataConverter: SaveDataConverter,
    private val serializationEngine: UnifiedSerializationEngine
) {
    companion object {
        private const val TAG = "IncrementalStorage"
        private const val SNAPSHOT_FILE = "snapshot.dat"
        private const val DELTA_FILE_PREFIX = "delta_"
        private const val MANIFEST_FILE = "manifest.json"
        private const val MAGIC_BYTES = "XIANXIA_INC"
        private const val FORMAT_VERSION = 2
    }
    
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        isLenient = true
    }
    
    @OptIn(ExperimentalSerializationApi::class)
    private val protobufContext = SerializationContext(
        format = SerializationFormat.PROTOBUF,
        compression = CompressionType.LZ4,
        compressThreshold = 256,
        includeChecksum = true
    )
    
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /**
     * Per-slot 互斥锁数组（index 1-6 对应槽位 1-6）。
     * 不同槽位的存取操作可以完全并行，只有同一槽位的操作才会串行化。
     */
    private val slotLocks = Array(IncrementalStorageConfig.MAX_SLOTS + 1) { Mutex() }

    /**
     * 全局 manifest 锁，仅用于跨槽位的 manifest 批量操作（如 shutdown 时保存所有 manifest）。
     */
    private val globalManifestLock = Mutex()
    
    private val currentVersion = AtomicLong(0)
    private val lastSnapshotVersion = AtomicLong(0)
    
    private val deltaChains = ConcurrentHashMap<Int, MutableList<DeltaMetadata>>()
    private val snapshots = ConcurrentHashMap<Int, MutableList<SnapshotInfo>>()
    
    private val _stats = MutableStateFlow(IncrementalStorageStats())
    val stats: StateFlow<IncrementalStorageStats> = _stats.asStateFlow()
    
    private val _saveProgress = MutableStateFlow(0f)
    val saveProgress: StateFlow<Float> = _saveProgress.asStateFlow()
    
    private var compactJob: Job? = null
    private var healthMonitorJob: Job? = null
    
    init {
        loadManifests()
        startAutoCompact()
    }
    
    private fun loadManifests() {
        for (slot in 1..IncrementalStorageConfig.MAX_SLOTS) {
            try {
                val manifestFile = getManifestFile(slot)
                if (manifestFile.exists()) {
                    val manifest = loadManifest(manifestFile)
                    deltaChains[slot] = manifest.deltas.toMutableList()
                    snapshots[slot] = manifest.snapshots.toMutableList()
                    
                    if (manifest.deltas.isNotEmpty()) {
                        currentVersion.set(manifest.deltas.last().version)
                    }
                    if (manifest.snapshots.isNotEmpty()) {
                        lastSnapshotVersion.set(manifest.snapshots.last().version)
                    }
                } else {
                    deltaChains[slot] = mutableListOf()
                    snapshots[slot] = mutableListOf()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load manifest for slot $slot", e)
                deltaChains[slot] = mutableListOf()
                snapshots[slot] = mutableListOf()
            }
        }
        updateStats()
    }
    
    private fun startAutoCompact() {
        compactJob = scope.launch {
            while (isActive) {
                delay(IncrementalStorageConfig.AUTO_COMPACT_INTERVAL_MS)
                try {
                    for (slot in 1..IncrementalStorageConfig.MAX_SLOTS) {
                        val chain = deltaChains[slot] ?: continue
                        if (chain.size >= IncrementalStorageConfig.COMPACTION_THRESHOLD) {
                            val result = smartCompact(slot)
                            Log.d(TAG, "Smart compact slot $slot: $result")
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Auto-compact failed", e)
                }
            }
        }

        startChainHealthMonitor()
    }
    
    suspend fun saveIncremental(slot: Int, data: SaveData): IncrementalSaveResult {
        if (!isValidSlot(slot)) {
            return IncrementalSaveResult(false, 0, 0, false, 0, "Invalid slot")
        }
        
        return slotLocks[slot].withLock {
            val startTime = System.currentTimeMillis()
            
            try {
                _saveProgress.value = 0.1f
                
                val changes = changeTracker.getChanges()
                
                if (changes.isEmpty) {
                    Log.d(TAG, "No changes to save incrementally")
                    return@withLock IncrementalSaveResult(true, 0, 0, false, 0)
                }

                val currentChainLength = deltaChains[slot]?.size ?: 0
                if (currentChainLength >= IncrementalStorageConfig.MAX_DELTA_CHAIN_LENGTH) {
                    Log.w(TAG, "Delta chain at max length (${IncrementalStorageConfig.MAX_DELTA_CHAIN_LENGTH}), forcing compact before save")
                    compact(slot)
                }
                
                _saveProgress.value = 0.3f
                
                val baseVersion = lastSnapshotVersion.get()
                val delta = deltaCompressor.computeDelta(changes, baseVersion)
                
                _saveProgress.value = 0.5f
                
                val compressedDelta = deltaCompressor.compressDelta(delta)
                val deltaFile = getDeltaFile(slot, delta.deltaVersion)
                
                if (!writeFileAtomically(deltaFile, compressedDelta)) {
                    return@withLock IncrementalSaveResult(false, 0, 0, false, 0, "Failed to write delta file")
                }
                
                _saveProgress.value = 0.7f
                
                val deltaMetadata = DeltaMetadata(
                    version = delta.deltaVersion,
                    timestamp = delta.timestamp,
                    size = compressedDelta.size.toLong(),
                    operationCount = delta.operations.size,
                    checksum = delta.checksum.joinToString("") { "%02x".format(it) }
                )
                
                deltaChains.getOrPut(slot) { mutableListOf() }.add(deltaMetadata)
                currentVersion.set(delta.deltaVersion)
                
                _saveProgress.value = 0.9f
                
                saveManifest(slot)
                changeTracker.clearChanges()
                updateStats()
                
                val elapsed = System.currentTimeMillis() - startTime
                _saveProgress.value = 1.0f
                
                Log.i(TAG, "Incremental save completed: slot=$slot, deltaSize=${compressedDelta.size}, " +
                    "operations=${delta.operations.size}, elapsed=${elapsed}ms")
                
                IncrementalSaveResult(
                    success = true,
                    savedBytes = compressedDelta.size.toLong(),
                    elapsedMs = elapsed,
                    isIncremental = true,
                    deltaCount = delta.operations.size
                )
                
            } catch (e: Exception) {
                Log.e(TAG, "Incremental save failed for slot $slot", e)
                IncrementalSaveResult(false, 0, 0, false, 0, e.message)
            }
        }
    }
    
    suspend fun saveFull(slot: Int, data: SaveData): IncrementalSaveResult {
        if (!isValidSlot(slot)) {
            return IncrementalSaveResult(false, 0, 0, false, 0, "Invalid slot")
        }
        
        return slotLocks[slot].withLock {
            val startTime = System.currentTimeMillis()

            try {
                _saveProgress.value = 0.1f

                val snapshotFile = getSnapshotFile(slot)
                val snapshotData = serializeSnapshot(data)
                
                _saveProgress.value = 0.3f
                
                if (!writeFileAtomically(snapshotFile, snapshotData)) {
                    return@withLock IncrementalSaveResult(false, 0, 0, false, 0, "Failed to write snapshot file")
                }
                
                _saveProgress.value = 0.5f
                
                val checksum = computeChecksum(snapshotData)
                val version = currentVersion.incrementAndGet()
                
                val snapshotInfo = SnapshotInfo(
                    version = version,
                    timestamp = System.currentTimeMillis(),
                    size = snapshotData.size.toLong(),
                    checksum = checksum,
                    isFull = true
                )
                
                snapshots.getOrPut(slot) { mutableListOf() }.add(snapshotInfo)
                lastSnapshotVersion.set(version)
                currentVersion.set(version)
                
                _saveProgress.value = 0.7f
                
                deltaChains[slot]?.clear()
                
                _saveProgress.value = 0.9f
                
                saveManifest(slot)
                changeTracker.clearChanges()
                updateStats()
                
                val elapsed = System.currentTimeMillis() - startTime
                _saveProgress.value = 1.0f
                
                Log.i(TAG, "Full save completed: slot=$slot, size=${snapshotData.size}, elapsed=${elapsed}ms")
                
                IncrementalSaveResult(
                    success = true,
                    savedBytes = snapshotData.size.toLong(),
                    elapsedMs = elapsed,
                    isIncremental = false,
                    deltaCount = 0
                )
                
            } catch (e: Exception) {
                Log.e(TAG, "Full save failed for slot $slot", e)
                IncrementalSaveResult(false, 0, 0, false, 0, e.message)
            }
        }
    }
    
    // ==================== Delta 链自愈机制 ====================
    
    /**
     * 验证指定槽位的 delta 链完整性。
     *
     * 检查项：
     * 1. 每个 delta 文件是否存在且大小 > 0
     * 2. 验证文件 checksum 与 metadata 中记录的一致
     * 3. 检查版本号连续性（无断裂）
     *
     * @return 链健康状态结果
     */
    private fun validateDeltaChain(slot: Int): ChainHealthResult {
        val deltaList = deltaChains[slot] ?: return ChainHealthResult.Healthy(0)
        
        if (deltaList.isEmpty()) return ChainHealthResult.Healthy(0)
        
        val errors = mutableListOf<String>()
        var lastValidVersion = -1L
        var brokenAtIndex = -1
        
        for ((index, deltaMeta) in deltaList.withIndex()) {
            val deltaFile = getDeltaFile(slot, deltaMeta.version)
            
            // 检查1: 文件存在性
            if (!deltaFile.exists()) {
                val msg = "Delta file missing at index $index, version=${deltaMeta.version}"
                Log.w(TAG, "[$slot] $msg")
                errors.add(msg)
                if (brokenAtIndex < 0) brokenAtIndex = index
                continue
            }
            
            // 检查2: 文件大小有效性
            if (deltaFile.length() <= 0) {
                val msg = "Delta file empty at index $index, version=${deltaMeta.version}"
                Log.w(TAG, "[$slot] $msg")
                errors.add(msg)
                if (brokenAtIndex < 0) brokenAtIndex = index
                continue
            }
            
            // 检查3: checksum 校验
            try {
                val fileData = readDeltaFile(deltaFile)
                if (fileData != null) {
                    val actualChecksum = computeChecksum(fileData)
                    if (actualChecksum != deltaMeta.checksum) {
                        val msg = "Checksum mismatch at index $index, version=${deltaMeta.version} " +
                                "(expected=${deltaMeta.checksum}, actual=$actualChecksum)"
                        Log.w(TAG, "[$slot] $msg")
                        errors.add(msg)
                        if (brokenAtIndex < 0) brokenAtIndex = index
                        continue
                    }
                } else {
                    val msg = "Failed to read delta file at index $index, version=${deltaMeta.version}"
                    errors.add(msg)
                    if (brokenAtIndex < 0) brokenAtIndex = index
                    continue
                }
            } catch (e: Exception) {
                val msg = "Exception validating delta at index $index: ${e.message}"
                errors.add(msg)
                if (brokenAtIndex < 0) brokenAtIndex = index
                continue
            }
            
            // 检查4: 版本号连续性
            if (lastValidVersion >= 0 && deltaMeta.version != lastValidVersion + 1) {
                // 版本号不连续，记录警告但不一定算损坏（允许跳号）
                Log.d(TAG, "[$slot] Version gap at index ${index}: ${lastValidVersion} -> ${deltaMeta.version}")
            }
            lastValidVersion = deltaMeta.version
        }
        
        return when {
            errors.isEmpty() -> ChainHealthResult.Healthy(deltaList.size)
            brokenAtIndex == 0 -> {
                // 第一个 delta 就坏了，无法修复
                ChainHealthResult.Broken(errors)
            }
            else -> {
                // 存在可截断的修复点
                ChainHealthResult.Repairable(brokenAtIndex, errors.first())
            }
        }
    }
    
    /**
     * 尝试修复损坏的 delta 链。
     *
     * 修复策略：
     * 1. 从最后一个有效的 snapshot 开始重放 delta
     * 2. 截断损坏点之后的所有 delta 文件和 metadata
     * 3. 更新 manifest 以反映修复后的状态
     *
     * @return 是否修复成功
     */
    private suspend fun repairDeltaChain(slot: Int): Boolean {
        return try {
            val health = validateDeltaChain(slot)
            
            when (health) {
                is ChainHealthResult.Healthy -> {
                    Log.d(TAG, "[$slot] Delta chain already healthy, no repair needed")
                    true
                }
                
                is ChainHealthResult.Repairable -> {
                    Log.w(TAG, "[$slot] Attempting to repair delta chain, brokenAt=${health.brokenAt}")
                    
                    val deltaList = deltaChains[slot]?.toMutableList() ?: return false
                    
                    // 截断损坏点之后的 delta
                    val removedDeltas = deltaList.subList(health.brokenAt, deltaList.size).toList()
                    deltaList.subList(health.brokenAt, deltaList.size).clear()
                    
                    // 删除被截断的 delta 文件
                    for (removed in removedDeltas) {
                        val file = getDeltaFile(slot, removed.version)
                        if (file.exists()) {
                            if (file.delete()) {
                                Log.d(TAG, "[$slot] Deleted corrupted delta file: version=${removed.version}")
                            } else {
                                Log.w(TAG, "[$slot] Failed to delete delta file: version=${removed.version}")
                            }
                        }
                    }
                    
                    // 更新内存中的链
                    deltaChains[slot] = deltaList
                    
                    // 持久化修复后的 manifest
                    saveManifest(slot)
                    updateStats()
                    
                    Log.i(TAG, "[$slot] Delta chain repaired: truncated ${removedDeltas.size} deltas from index ${health.brokenAt}, " +
                            "${deltaList.size} deltas remaining")

                    // 修复后验证：尝试完整重构数据并做业务校验
                    val truncatedCount = removedDeltas.size
                    try {
                        val loadResult = load(slot)
                        if (loadResult.data != null) {
                            val validation = com.xianxia.sect.data.validation.StorageValidator.validateSaveData(loadResult.data)
                            if (validation.isValid) {
                                Log.i(TAG, "[$slot] Post-repair audit PASSED: slot=$slot, truncated=$truncatedCount, " +
                                        "validation=${validation.errors.size + validation.warnings.size} rules OK")
                            } else {
                                val allIssues = validation.errors + validation.warnings
                                Log.w(TAG, "[$slot] Post-repair audit WARN: slot=$slot, truncated=$truncatedCount, " +
                                        "issues=${allIssues.map { "${it.code}:${it.severity}" }}")
                            }
                        } else {
                            Log.w(TAG, "[$slot] Post-repair audit: load returned null data (slot=$slot, truncated=$truncatedCount)")
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "[$slot] Post-repair audit exception (non-fatal): ${e.message}")
                    }

                    true
                }
                
                is ChainHealthResult.Broken -> {
                    Log.e(TAG, "[$slot] Delta chain irreparable: ${health.errors.joinToString(", ")}")
                    false
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "[$slot] Exception during delta chain repair", e)
            false
        }
    }
    
    // ==================== 加载方法 ====================
    
    suspend fun load(slot: Int): IncrementalLoadResult {
        if (!isValidSlot(slot)) {
            return IncrementalLoadResult(null, 0, 0, false, "Invalid slot")
        }
        
        return slotLocks[slot].withLock {
            val startTime = System.currentTimeMillis()
            
            try {
                val snapshotList = snapshots[slot]
                val deltaList = deltaChains[slot]
                
                if (snapshotList.isNullOrEmpty() && deltaList.isNullOrEmpty()) {
                    Log.d(TAG, "No data found for slot $slot")
                    return@withLock IncrementalLoadResult(null, 0, 0, false)
                }
                
                // ===== Delta 链自愈检查（在加载数据前执行）=====
                if (!deltaList.isNullOrEmpty()) {
                    val health = validateDeltaChain(slot)
                    when (health) {
                        is ChainHealthResult.Broken -> {
                            Log.w(TAG, "[$slot] Delta chain broken, attempting self-repair")
                            if (!repairDeltaChain(slot)) {
                                Log.e(TAG, "[$slot] Delta chain irreparable, falling back to snapshot only")
                                // 不直接返回失败，而是尝试仅从 snapshot 加载
                                // 如果没有 snapshot 则返回错误
                            }
                        }
                        is ChainHealthResult.Repairable -> {
                            Log.w(TAG, "[$slot] Delta chain repairable at index ${health.brokenAt}, auto-repairing")
                            repairDeltaChain(slot)
                        }
                        is ChainHealthResult.Healthy -> {
                            // 链条健康，继续正常加载
                        }
                    }
                    // 修复后重新获取最新的 delta 列表
                    // （repairDeltaChain 会更新内存中的 deltaChains）
                }
                
                var data: SaveData? = null
                var appliedDeltas = 0
                var wasFromSnapshot = false
                
                if (!snapshotList.isNullOrEmpty()) {
                    val latestSnapshot = snapshotList.last()
                    val snapshotFile = getSnapshotFile(slot)
                    
                    if (snapshotFile.exists()) {
                        data = loadSnapshotFile(snapshotFile)
                        wasFromSnapshot = true
                        Log.d(TAG, "Loaded snapshot for slot $slot, version=${latestSnapshot.version}")
                    }
                }
                
                if (!deltaList.isNullOrEmpty()) {
                    val snapshotVersion = if (wasFromSnapshot) lastSnapshotVersion.get() else 0L
                    
                    val deltasToApply = deltaList.filter { it.version > snapshotVersion }
                        .sortedBy { it.version }
                    
                    if (data == null && deltasToApply.isNotEmpty()) {
                        Log.w(TAG, "No snapshot found but deltas exist, cannot reconstruct data")
                        return@withLock IncrementalLoadResult(null, 0, 0, false, "Missing base snapshot")
                    }
                    
                    for (deltaMeta in deltasToApply) {
                        val deltaFile = getDeltaFile(slot, deltaMeta.version)
                        if (!deltaFile.exists()) {
                            Log.e(TAG, "Delta file missing: ${deltaMeta.version}, delta chain is broken")
                            return@withLock IncrementalLoadResult(
                                null, 0, 0, false,
                                "Delta chain is broken: missing file ${deltaMeta.version}"
                            )
                        }
                        
                        val compressedDelta = readDeltaFile(deltaFile)
                        if (compressedDelta == null) {
                            Log.e(TAG, "Failed to read delta file: ${deltaMeta.version}")
                            return@withLock IncrementalLoadResult(
                                null, 0, 0, false,
                                "Delta chain is broken: failed to read ${deltaMeta.version}"
                            )
                        }
                        
                        val delta = deltaCompressor.decompressDelta(compressedDelta)
                        
                        if (delta != null && data != null) {
                            data = deltaCompressor.applyDelta(data, delta)
                            appliedDeltas++
                        } else if (delta == null) {
                            Log.e(TAG, "Failed to decompress delta: ${deltaMeta.version}")
                            return@withLock IncrementalLoadResult(
                                null, 0, 0, false,
                                "Delta chain is broken: failed to decompress ${deltaMeta.version}"
                            )
                        }
                    }
                    
                    Log.d(TAG, "Applied $appliedDeltas deltas for slot $slot")
                }
                
                val elapsed = System.currentTimeMillis() - startTime
                
                IncrementalLoadResult(
                    data = data,
                    elapsedMs = elapsed,
                    appliedDeltas = appliedDeltas,
                    wasFromSnapshot = wasFromSnapshot
                )
                
            } catch (e: Exception) {
                Log.e(TAG, "Load failed for slot $slot", e)
                IncrementalLoadResult(null, 0, 0, false, e.message)
            }
        }
    }
    
    suspend fun compact(slot: Int): Boolean {
        if (!isValidSlot(slot)) return false
        
        return slotLocks[slot].withLock {
            try {
                val deltaList = deltaChains[slot] ?: return@withLock false
                
                if (deltaList.size < IncrementalStorageConfig.COMPACTION_THRESHOLD) {
                    return@withLock true
                }
                
                Log.i(TAG, "Starting compaction for slot $slot, deltaCount=${deltaList.size}")
                
                val deltas = mutableListOf<Delta>()
                for (meta in deltaList) {
                    val deltaFile = getDeltaFile(slot, meta.version)
                    if (deltaFile.exists()) {
                        val compressed = readDeltaFile(deltaFile)
                        if (compressed != null) {
                            deltaCompressor.decompressDelta(compressed)?.let { deltas.add(it) }
                        }
                    }
                }
                
                if (deltas.isEmpty()) {
                    return@withLock true
                }
                
                val compactedDelta = deltaCompressor.compactDeltas(deltas)
                
                if (compactedDelta != null) {
                    val compactedFile = File(getDeltaDir(slot), "compacted_${compactedDelta.deltaVersion}.dat")
                    val compressed = deltaCompressor.compressDelta(compactedDelta)
                    
                    if (!writeFileAtomically(compactedFile, compressed)) {
                        Log.e(TAG, "Failed to write compacted delta file")
                        return@withLock false
                    }
                    
                    for (meta in deltaList) {
                        val deltaFile = getDeltaFile(slot, meta.version)
                        if (deltaFile.exists()) {
                            deltaFile.delete()
                        }
                    }
                    
                    deltaChains[slot] = mutableListOf(DeltaMetadata(
                        version = compactedDelta.deltaVersion,
                        timestamp = compactedDelta.timestamp,
                        size = compressed.size.toLong(),
                        operationCount = compactedDelta.operations.size,
                        checksum = compactedDelta.checksum.joinToString("") { "%02x".format(it) }
                    ))
                    
                    saveManifest(slot)
                    updateStats()
                    
                    Log.i(TAG, "Compaction completed for slot $slot, reduced ${deltaList.size} deltas to 1")
                }
                
                true
            } catch (e: Exception) {
                Log.e(TAG, "Compaction failed for slot $slot", e)
                false
            }
        }
    }

    private suspend fun smartCompact(slot: Int): SmartCompactResult {
        val chain = deltaChains[slot] ?: return SmartCompactResult.NoAction

        return when {
            chain.size >= IncrementalStorageConfig.URGENT_COMPACT_THRESHOLD -> {
                urgentCompact(slot)
            }
            chain.size >= IncrementalStorageConfig.SOFT_CHAIN_LIMIT -> {
                progressiveCompact(slot)
            }
            else -> SmartCompactResult.NoAction
        }
    }

    private suspend fun urgentCompact(slot: Int): SmartCompactResult {
        return slotLocks[slot].withLock {
            try {
                val deltaList = deltaChains[slot]?.toList() ?: return@withLock SmartCompactResult.Skipped("No delta chain")
                if (deltaList.isEmpty()) return@withLock SmartCompactResult.Skipped("Empty delta chain")

                val sizeBefore = deltaList.sumOf { it.size }

                val deltas = mutableListOf<Delta>()
                for (meta in deltaList) {
                    val deltaFile = getDeltaFile(slot, meta.version)
                    if (deltaFile.exists()) {
                        val compressed = readDeltaFile(deltaFile)
                        if (compressed != null) {
                            deltaCompressor.decompressDelta(compressed)?.let { deltas.add(it) }
                        }
                    }
                }

                if (deltas.isEmpty()) return@withLock SmartCompactResult.Skipped("No valid deltas to merge")

                val compactedDelta = deltaCompressor.compactDeltas(deltas) ?: return@withLock SmartCompactResult.Skipped("compactDeltas returned null")

                val compactedFile = File(getDeltaDir(slot), "compacted_${compactedDelta.deltaVersion}.dat")
                val compressed = deltaCompressor.compressDelta(compactedDelta)

                if (!writeFileAtomically(compactedFile, compressed)) {
                    return@withLock SmartCompactResult.Skipped("Atomic write failed")
                }

                for (meta in deltaList) {
                    val deltaFile = getDeltaFile(slot, meta.version)
                    if (deltaFile.exists()) deltaFile.delete()
                }

                val newMeta = DeltaMetadata(
                    version = compactedDelta.deltaVersion,
                    timestamp = compactedDelta.timestamp,
                    size = compressed.size.toLong(),
                    operationCount = compactedDelta.operations.size,
                    checksum = compactedDelta.checksum.joinToString("") { "%02x".format(it) }
                )

                deltaChains[slot] = mutableListOf(newMeta)
                saveManifest(slot)
                updateStats()

                Log.i(TAG, "Urgent compact slot $slot: ${deltaList.size} -> 1 delta, size ${sizeBefore} -> ${compressed.size.toLong()}")
                SmartCompactResult.UrgentMerged(deltaList.size, 1, sizeBefore, compressed.size.toLong())
            } catch (e: Exception) {
                Log.e(TAG, "Urgent compact failed for slot $slot", e)
                SmartCompactResult.Skipped("Exception: ${e.message}")
            }
        }
    }

    private suspend fun progressiveCompact(slot: Int): SmartCompactResult {
        return slotLocks[slot].withLock {
            try {
                val deltaList = deltaChains[slot]?.toList() ?: return@withLock SmartCompactResult.Skipped("No delta chain")
                if (deltaList.size < 2) return@withLock SmartCompactResult.NoAction

                val mergeCount = (deltaList.size + 1) / 2
                val toMerge = deltaList.takeLast(mergeCount)
                val remaining = deltaList.dropLast(mergeCount)

                val deltas = mutableListOf<Delta>()
                for (meta in toMerge) {
                    val deltaFile = getDeltaFile(slot, meta.version)
                    if (deltaFile.exists()) {
                        val compressed = readDeltaFile(deltaFile)
                        if (compressed != null) {
                            deltaCompressor.decompressDelta(compressed)?.let { deltas.add(it) }
                        }
                    }
                }

                if (deltas.isEmpty()) return@withLock SmartCompactResult.Skipped("No valid deltas to merge")

                val compactedDelta = deltaCompressor.compactDeltas(deltas) ?: return@withLock SmartCompactResult.Skipped("compactDeltas returned null")

                val compactedFile = File(getDeltaDir(slot), "progressive_${compactedDelta.deltaVersion}.dat")
                val compressed = deltaCompressor.compressDelta(compactedDelta)

                if (!writeFileAtomically(compactedFile, compressed)) {
                    return@withLock SmartCompactResult.Skipped("Atomic write failed")
                }

                for (meta in toMerge) {
                    val deltaFile = getDeltaFile(slot, meta.version)
                    if (deltaFile.exists()) deltaFile.delete()
                }

                val newMeta = DeltaMetadata(
                    version = compactedDelta.deltaVersion,
                    timestamp = compactedDelta.timestamp,
                    size = compressed.size.toLong(),
                    operationCount = compactedDelta.operations.size,
                    checksum = compactedDelta.checksum.joinToString("") { "%02x".format(it) }
                )

                deltaChains[slot] = (remaining.toMutableList() + newMeta).toMutableList()
                saveManifest(slot)
                updateStats()

                Log.i(TAG, "Progressive compact slot $slot: merged $mergeCount deltas, ${remaining.size} remaining")
                SmartCompactResult.ProgressiveMerged(mergeCount, remaining.size)
            } catch (e: Exception) {
                Log.e(TAG, "Progressive compact failed for slot $slot", e)
                SmartCompactResult.Skipped("Exception: ${e.message}")
            }
        }
    }

    private fun startChainHealthMonitor(): Job {
        return scope.launch {
            while (isActive) {
                delay(IncrementalStorageConfig.CHAIN_HEALTH_CHECK_INTERVAL_MS)
                try {
                    for (slot in 1..IncrementalStorageConfig.MAX_SLOTS) {
                        val chain = deltaChains[slot] ?: continue
                        when {
                            chain.size >= IncrementalStorageConfig.URGENT_COMPACT_THRESHOLD -> {
                                Log.w(TAG, "Chain health monitor: slot $slot CRITICAL (chain=${chain.size}), triggering urgent compact")
                                smartCompact(slot)
                            }
                            chain.size >= IncrementalStorageConfig.SOFT_CHAIN_LIMIT -> {
                                Log.d(TAG, "Chain health monitor: slot $slot WARNING (chain=${chain.size}), triggering progressive compact")
                                smartCompact(slot)
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Chain health check failed", e)
                }
            }
        }
    }

    fun trackChange(dataType: DataType, entityId: String, oldValue: Any?, newValue: Any?) {
        when {
            oldValue == null && newValue != null -> {
                changeTracker.trackCreate(dataType, entityId, newValue)
            }
            oldValue != null && newValue == null -> {
                changeTracker.trackDelete(dataType, entityId, oldValue)
            }
            oldValue != null && newValue != null -> {
                changeTracker.trackUpdate(dataType, entityId, oldValue, newValue)
            }
        }
    }
    
    fun hasPendingChanges(): Boolean = changeTracker.hasChanges()
    
    fun getPendingChangeCount(): Int = changeTracker.changeCount()
    
    fun getSlotInfo(slot: Int): SaveSlot? {
        if (!isValidSlot(slot)) return null
        
        val snapshotList = snapshots[slot]
        if (snapshotList.isNullOrEmpty()) return null
        
        val latestSnapshot = snapshotList.last()
        return SaveSlot(
            slot = slot,
            name = "Save $slot",
            timestamp = latestSnapshot.timestamp,
            gameYear = 1,
            gameMonth = 1,
            sectName = "",
            discipleCount = 0,
            spiritStones = 0,
            isEmpty = false
        )
    }
    
    fun hasSave(slot: Int): Boolean {
        if (!isValidSlot(slot)) return false
        
        val snapshotList = snapshots[slot]
        val deltaList = deltaChains[slot]
        
        return !snapshotList.isNullOrEmpty() || !deltaList.isNullOrEmpty()
    }
    
    fun hasSnapshot(slot: Int): Boolean {
        if (!isValidSlot(slot)) return false
        
        val snapshotList = snapshots[slot]
        return !snapshotList.isNullOrEmpty()
    }
    
    suspend fun loadFromSnapshot(slot: Int, version: Long? = null): IncrementalLoadResult {
        if (!isValidSlot(slot)) {
            return IncrementalLoadResult(null, 0, 0, false, "Invalid slot")
        }
        
        return slotLocks[slot].withLock {
            val startTime = System.currentTimeMillis()
            
            try {
                val snapshotList = snapshots[slot]
                if (snapshotList.isNullOrEmpty()) {
                    return@withLock IncrementalLoadResult(null, 0, 0, false, "No snapshot available")
                }
                
                val targetSnapshot = if (version != null) {
                    snapshotList.find { it.version == version }
                } else {
                    snapshotList.lastOrNull()
                }
                
                if (targetSnapshot == null) {
                    return@withLock IncrementalLoadResult(null, 0, 0, false, "Snapshot version not found")
                }
                
                val snapshotFile = getSnapshotFile(slot)
                if (!snapshotFile.exists()) {
                    return@withLock IncrementalLoadResult(null, 0, 0, false, "Snapshot file not found")
                }
                
                val data = loadSnapshotFile(snapshotFile)
                if (data == null) {
                    return@withLock IncrementalLoadResult(null, 0, 0, false, "Failed to load snapshot data")
                }
                
                val elapsed = System.currentTimeMillis() - startTime
                
                Log.i(TAG, "Loaded snapshot for slot $slot, version=${targetSnapshot.version}, elapsed=${elapsed}ms")
                
                IncrementalLoadResult(
                    data = data,
                    elapsedMs = elapsed,
                    appliedDeltas = 0,
                    wasFromSnapshot = true
                )
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load snapshot for slot $slot", e)
                IncrementalLoadResult(null, 0, 0, false, e.message)
            }
        }
    }
    
    suspend fun delete(slot: Int): Boolean {
        if (!isValidSlot(slot)) return false
        
        return slotLocks[slot].withLock {
            try {
                val deltaDir = getDeltaDir(slot)
                val snapshotDir = getSnapshotDir(slot)
                val manifestFile = getManifestFile(slot)
                
                if (deltaDir.exists()) deltaDir.deleteRecursively()
                if (snapshotDir.exists()) snapshotDir.deleteRecursively()
                if (manifestFile.exists()) manifestFile.delete()
                
                deltaChains.remove(slot)
                snapshots.remove(slot)
                
                updateStats()
                
                Log.i(TAG, "Deleted slot $slot")
                true
            } catch (e: Exception) {
                Log.e(TAG, "Failed to delete slot $slot", e)
                false
            }
        }
    }
    
    fun getDeltaChain(slot: Int): DeltaChain? {
        if (!isValidSlot(slot)) return null
        
        val deltaList = deltaChains[slot] ?: return null
        val snapshotList = snapshots[slot]
        
        return DeltaChain(
            slot = slot,
            baseSnapshotVersion = snapshotList?.lastOrNull()?.version ?: 0,
            deltas = deltaList,
            chainLength = deltaList.size,
            totalSize = deltaList.sumOf { it.size },
            createdAt = deltaList.firstOrNull()?.timestamp ?: 0,
            lastModified = deltaList.lastOrNull()?.timestamp ?: 0
        )
    }
    
    private fun updateStats() {
        val totalDeltaSize = deltaChains.values.sumOf { chain -> 
            chain.sumOf { it.size } 
        }
        val maxChainLength = deltaChains.values.maxOfOrNull { it.size } ?: 0
        
        _stats.value = IncrementalStorageStats(
            totalDeltaSize = totalDeltaSize,
            deltaChainLength = maxChainLength,
            snapshotCount = snapshots.values.sumOf { it.size },
            lastSnapshotTime = snapshots.values.maxOfOrNull { 
                it.maxOfOrNull { s -> s.timestamp } ?: 0 
            } ?: 0,
            lastDeltaTime = deltaChains.values.maxOfOrNull { 
                it.maxOfOrNull { d -> d.timestamp } ?: 0 
            } ?: 0,
            pendingChanges = changeTracker.changeCount(),
            needsCompaction = maxChainLength >= IncrementalStorageConfig.COMPACTION_THRESHOLD
        )
    }
    
    /**
     * 原子写入 manifest 文件。
     * 使用临时文件 + rename 策略，避免进程被杀导致半写数据。
     */
    private fun saveManifest(slot: Int) {
        val manifestFile = getManifestFile(slot)
        manifestFile.parentFile?.mkdirs()
        val manifest = SlotManifest(
            slot = slot,
            version = FORMAT_VERSION,
            deltas = deltaChains[slot] ?: emptyList(),
            snapshots = snapshots[slot] ?: emptyList(),
            lastModified = System.currentTimeMillis()
        )

        val jsonStr = json.encodeToString(SlotManifest.serializer(), manifest)

        // 原子写入：先写临时文件再 rename
        val tempFile = File(manifestFile.parent, "${manifestFile.name}.tmp_${System.currentTimeMillis()}")
        try {
            tempFile.writeText(jsonStr)
            if (!tempFile.renameTo(manifestFile)) {
                // rename 失败时的 fallback（跨文件系统时可能失败）
                manifestFile.writeText(jsonStr)
            }
        } finally {
            if (tempFile.exists()) tempFile.delete()
        }
    }
    
    /**
     * 容错加载 manifest 文件。
     * 如果主文件解析失败，尝试读取 .tmp 备份文件（上次写入未完成时可能存在）。
     * 所有尝试均失败时返回空 manifest 而非抛异常。
     */
    private fun loadManifest(file: File): SlotManifest {
        // 尝试读取主 manifest 文件
        return try {
            val jsonStr = file.readText()
            json.decodeFromString(SlotManifest.serializer(), jsonStr)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse main manifest: ${e.message}, attempting tmp backup")

            // 尝试查找并读取 .tmp 备份文件
            try {
                val parentDir = file.parentFile ?: return emptyManifest()
                val tmpFiles = parentDir.listFiles { f ->
                    f.name.startsWith(file.name) && f.name.endsWith(".tmp") && f.exists()
                }?.sortedByDescending { it.lastModified() }

                if (!tmpFiles.isNullOrEmpty()) {
                    for (tmpFile in tmpFiles) {
                        try {
                            val jsonStr = tmpFile.readText()
                            val manifest = json.decodeFromString(SlotManifest.serializer(), jsonStr)
                            Log.i(TAG, "Recovered manifest from tmp backup: ${tmpFile.name}")
                            return manifest
                        } catch (tmpError: Exception) {
                            Log.w(TAG, "Tmp backup ${tmpFile.name} also corrupted, trying next")
                        }
                    }
                }

                // 所有备份都失败，返回空 manifest
                Log.e(TAG, "All manifest recovery attempts failed, returning empty manifest")
                emptyManifest()
            } catch (recoveryError: Exception) {
                Log.e(TAG, "Manifest recovery failed with exception", recoveryError)
                emptyManifest()
            }
        }
    }

    /** 返回空的默认 manifest */
    private fun emptyManifest(): SlotManifest = SlotManifest(0, FORMAT_VERSION, emptyList(), emptyList(), 0)
    
    @OptIn(ExperimentalSerializationApi::class)
    private fun serializeSnapshot(data: SaveData): ByteArray {
        val serializableData = saveDataConverter.toSerializable(data)
        val result = serializationEngine.serialize(serializableData, protobufContext, SerializableSaveData.serializer())
        return result.data
    }
    
    @OptIn(ExperimentalSerializationApi::class)
    private fun deserializeSnapshot(data: ByteArray): SaveData? {
        return try {
            val result = serializationEngine.deserialize(data, protobufContext, SerializableSaveData.serializer())
            if (result.isSuccess && result.data != null) {
                saveDataConverter.fromSerializable(result.data)
            } else {
                Log.e(TAG, "Failed to deserialize snapshot: ${result.error?.message}")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to deserialize snapshot", e)
            null
        }
    }
    
    /**
     * 原子写入文件（suspend 版本）。
     * 使用临时文件 + rename 策略确保原子性，
     * 通过 RetryPolicy 实现指数退避重试。
     */
    private suspend fun writeFileAtomically(file: File, data: ByteArray): Boolean {
        file.parentFile?.mkdirs()
        val tempFile = File(file.parent, "${file.name}.tmp_${System.currentTimeMillis()}")

        val writeRetryConfig = RetryConfig(
            maxRetries = 3,
            initialDelayMs = 100L,
            maxDelayMs = 5000L,
            backoffMultiplier = 2.0,
            jitterFactor = 0.2,
            retryableErrors = setOf(OutOfMemoryError::class.java, java.io.IOException::class.java)
        )

        val result: RetryResult<Boolean> = RetryPolicy.executeWithRetry(writeRetryConfig) {
            DataOutputStream(FileOutputStream(tempFile)).use { dos ->
                dos.write(MAGIC_BYTES.toByteArray())
                dos.writeInt(FORMAT_VERSION)
                dos.writeInt(data.size)
                dos.write(data)
            }

            if (file.exists()) {
                if (!file.delete()) {
                    Log.w(TAG, "Failed to delete existing file: ${file.path}")
                }
            }

            if (tempFile.renameTo(file)) {
                true
            } else {
                try {
                    tempFile.copyTo(file, overwrite = true)
                    tempFile.delete()
                    true
                } catch (copyError: Exception) {
                    Log.w(TAG, "Rename and copy failed: ${copyError.message}")
                    throw IOException("Failed to write file atomically: ${copyError.message}")
                }
            }
        }

        return when (result) {
            is RetryResult.Success -> result.data
            is RetryResult.Failure -> {
                Log.e(TAG, "Failed to write file atomically after ${result.totalAttempts} attempts: ${result.lastError.message}")
                false
            }
        }.also {
            if (!it && tempFile.exists()) {
                tempFile.delete()
            }
        }
    }
    
    private suspend fun writeSnapshotFile(file: File, data: ByteArray): Boolean {
        return writeFileAtomically(file, data)
    }
    
    private fun loadSnapshotFile(file: File): SaveData? {
        if (!file.exists()) return null
        
        return try {
            DataInputStream(FileInputStream(file)).use { dis ->
                @Suppress("NewApi")
                val magic = String(dis.readNBytes(MAGIC_BYTES.length))
                if (magic != MAGIC_BYTES) {
                    Log.e(TAG, "Invalid snapshot file magic")
                    return null
                }

                val version = dis.readInt()
                val size = dis.readInt()
                @Suppress("NewApi")
                val data = dis.readNBytes(size)
                
                deserializeSnapshot(data)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load snapshot file", e)
            null
        }
    }
    
    private suspend fun writeDeltaFile(file: File, data: ByteArray): Boolean {
        return writeFileAtomically(file, data)
    }
    
    private fun readDeltaFile(file: File): ByteArray? {
        return try {
            DataInputStream(FileInputStream(file)).use { dis ->
                @Suppress("NewApi")
                val magic = String(dis.readNBytes(MAGIC_BYTES.length))
                if (magic != MAGIC_BYTES) {
                    Log.e(TAG, "Invalid delta file magic")
                    return null
                }

                dis.readInt()
                val size = dis.readInt()
                @Suppress("NewApi")
                dis.readNBytes(size)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read delta file", e)
            null
        }
    }
    
    private fun computeChecksum(data: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(data).joinToString("") { "%02x".format(it) }
    }
    
    private fun getDeltaDir(slot: Int): File {
        val dir = File(context.filesDir, "${IncrementalStorageConfig.DELTA_DIR}/slot_$slot")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }
    
    private fun getSnapshotDir(slot: Int): File {
        val dir = File(context.filesDir, "${IncrementalStorageConfig.SNAPSHOT_DIR}/slot_$slot")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }
    
    private fun getDeltaFile(slot: Int, version: Long): File {
        return File(getDeltaDir(slot), "$DELTA_FILE_PREFIX$version.dat")
    }
    
    private fun getSnapshotFile(slot: Int): File {
        return File(getSnapshotDir(slot), SNAPSHOT_FILE)
    }
    
    private fun getManifestFile(slot: Int): File {
        return File(context.filesDir, "${IncrementalStorageConfig.DELTA_DIR}/manifest_$slot.json")
    }
    
    private fun isValidSlot(slot: Int): Boolean = slot in 1..IncrementalStorageConfig.MAX_SLOTS

    fun getChainHealthReport(slot: Int): ChainHealthReport {
        if (!isValidSlot(slot)) return ChainHealthReport(slot, 0, 0, ChainHealthStatus.BROKEN, "Invalid slot")

        val chain = deltaChains[slot] ?: emptyList()
        val totalSize = chain.sumOf { it.size }
        val healthStatus = when {
            chain.isEmpty() -> ChainHealthStatus.HEALTHY
            chain.size >= IncrementalStorageConfig.URGENT_COMPACT_THRESHOLD -> ChainHealthStatus.CRITICAL
            chain.size >= IncrementalStorageConfig.SOFT_CHAIN_LIMIT -> ChainHealthStatus.WARNING
            else -> ChainHealthStatus.HEALTHY
        }
        val recommendedAction = when (healthStatus) {
            ChainHealthStatus.CRITICAL -> "urgent_compact"
            ChainHealthStatus.WARNING -> "progressive_compact"
            else -> null
        }
        return ChainHealthReport(slot, chain.size, totalSize, healthStatus, recommendedAction)
    }

    fun getAllChainHealthReports(): Map<Int, ChainHealthReport> {
        val reports = mutableMapOf<Int, ChainHealthReport>()
        for (slot in 1..IncrementalStorageConfig.MAX_SLOTS) {
            reports[slot] = getChainHealthReport(slot)
        }
        return reports
    }

    fun validateDeltaChainPublic(slot: Int): ChainHealthStatus {
        return getChainHealthReport(slot).healthStatus
    }

    suspend fun repairDeltaChainPublic(slot: Int): Boolean {
        return try {
            val result = forceSmartCompact(slot)
            when (result) {
                is SmartCompactResult.UrgentMerged, is SmartCompactResult.ProgressiveMerged -> true
                else -> false
            }
        } catch (e: Exception) {
            Log.w(TAG, "Public repair failed for slot $slot", e)
            false
        }
    }

    suspend fun forceSmartCompact(slot: Int): SmartCompactResult {
        if (!isValidSlot(slot)) return SmartCompactResult.Skipped("Invalid slot")
        return smartCompact(slot)
    }
    
    fun shutdown() {
        compactJob?.cancel()
        healthMonitorJob?.cancel()
        scope.cancel()

        scope.launch {
            globalManifestLock.withLock {
                for (slot in 1..IncrementalStorageConfig.MAX_SLOTS) {
                    try {
                        saveManifest(slot)
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to save manifest for slot $slot during shutdown", e)
                    }
                }
            }
        }

        Log.i(TAG, "IncrementalStorageManager shutdown initiated")
    }
}
