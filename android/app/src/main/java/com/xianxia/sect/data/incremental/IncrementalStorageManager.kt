package com.xianxia.sect.data.incremental

import android.content.Context
import android.util.Log
import com.xianxia.sect.data.model.SaveData
import com.xianxia.sect.data.model.SaveSlot
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
    const val MAX_SLOTS = 5
    const val MAX_DELTA_CHAIN_LENGTH = 50
    const val COMPACTION_THRESHOLD = 10
    const val AUTO_COMPACT_INTERVAL_MS = 600_000L
    const val MAX_DELTA_SIZE_MB = 10
    const val DELTA_DIR = "deltas"
    const val SNAPSHOT_DIR = "snapshots"
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
        private const val MAX_RETRY_COUNT = 3
        private const val RETRY_DELAY_MS = 100L
    }
    
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        isLenient = true
    }
    
    @OptIn(ExperimentalSerializationApi::class)
    private val protobufContext = SerializationContext(
        format = SerializationFormat.PROTOBUF,
        compression = CompressionType.ZSTD,
        compressThreshold = 256,
        includeChecksum = true
    )
    
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val saveMutex = Mutex()
    private val loadMutex = Mutex()
    
    private val currentVersion = AtomicLong(0)
    private val lastSnapshotVersion = AtomicLong(0)
    
    private val deltaChains = ConcurrentHashMap<Int, MutableList<DeltaMetadata>>()
    private val snapshots = ConcurrentHashMap<Int, MutableList<SnapshotInfo>>()
    
    private val _stats = MutableStateFlow(IncrementalStorageStats())
    val stats: StateFlow<IncrementalStorageStats> = _stats.asStateFlow()
    
    private val _saveProgress = MutableStateFlow(0f)
    val saveProgress: StateFlow<Float> = _saveProgress.asStateFlow()
    
    private var compactJob: Job? = null
    
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
                            compact(slot)
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Auto-compact failed", e)
                }
            }
        }
    }
    
    suspend fun saveIncremental(slot: Int, data: SaveData): IncrementalSaveResult {
        if (!isValidSlot(slot)) {
            return IncrementalSaveResult(false, 0, 0, false, 0, "Invalid slot")
        }
        
        return saveMutex.withLock {
            val startTime = System.currentTimeMillis()
            
            try {
                _saveProgress.value = 0.1f
                
                val changes = changeTracker.getChanges()
                
                if (changes.isEmpty) {
                    Log.d(TAG, "No changes to save incrementally")
                    return@withLock IncrementalSaveResult(true, 0, 0, false, 0)
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
        
        return saveMutex.withLock {
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
                val version = System.currentTimeMillis()
                
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
    
    suspend fun load(slot: Int): IncrementalLoadResult {
        if (!isValidSlot(slot)) {
            return IncrementalLoadResult(null, 0, 0, false, "Invalid slot")
        }
        
        return loadMutex.withLock {
            val startTime = System.currentTimeMillis()
            
            try {
                val snapshotList = snapshots[slot]
                val deltaList = deltaChains[slot]
                
                if (snapshotList.isNullOrEmpty() && deltaList.isNullOrEmpty()) {
                    Log.d(TAG, "No data found for slot $slot")
                    return@withLock IncrementalLoadResult(null, 0, 0, false)
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
                    
                    var deltaChainBroken = false
                    for (deltaMeta in deltasToApply) {
                        if (deltaChainBroken) break
                        
                        val deltaFile = getDeltaFile(slot, deltaMeta.version)
                        if (!deltaFile.exists()) {
                            Log.e(TAG, "Delta file missing: ${deltaMeta.version}, delta chain is broken")
                            deltaChainBroken = true
                            continue
                        }
                        
                        val compressedDelta = readDeltaFile(deltaFile)
                        if (compressedDelta == null) {
                            Log.e(TAG, "Failed to read delta file: ${deltaMeta.version}")
                            deltaChainBroken = true
                            continue
                        }
                        
                        val delta = deltaCompressor.decompressDelta(compressedDelta)
                        
                        if (delta != null && data != null) {
                            data = deltaCompressor.applyDelta(data, delta)
                            appliedDeltas++
                        }
                    }
                    
                    if (deltaChainBroken && appliedDeltas == 0) {
                        return@withLock IncrementalLoadResult(
                            null, 0, 0, false, 
                            "Delta chain is broken, cannot load data"
                        )
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
        
        return saveMutex.withLock {
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
    
    suspend fun delete(slot: Int): Boolean {
        if (!isValidSlot(slot)) return false
        
        return saveMutex.withLock {
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
    
    private fun saveManifest(slot: Int) {
        val manifestFile = getManifestFile(slot)
        val manifest = SlotManifest(
            slot = slot,
            version = FORMAT_VERSION,
            deltas = deltaChains[slot] ?: emptyList(),
            snapshots = snapshots[slot] ?: emptyList(),
            lastModified = System.currentTimeMillis()
        )
        
        manifestFile.parentFile?.mkdirs()
        val jsonStr = json.encodeToString(SlotManifest.serializer(), manifest)
        manifestFile.writeText(jsonStr)
    }
    
    private fun loadManifest(file: File): SlotManifest {
        return try {
            val jsonStr = file.readText()
            json.decodeFromString(SlotManifest.serializer(), jsonStr)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse manifest: ${e.message}")
            SlotManifest(0, FORMAT_VERSION, emptyList(), emptyList(), 0)
        }
    }
    
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
    
    private fun writeFileAtomically(file: File, data: ByteArray): Boolean {
        file.parentFile?.mkdirs()
        val tempFile = File(file.parent, "${file.name}.tmp_${System.currentTimeMillis()}")
        
        repeat(MAX_RETRY_COUNT) { attempt ->
            try {
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
                    return true
                }
                
                try {
                    tempFile.copyTo(file, overwrite = true)
                    tempFile.delete()
                    return true
                } catch (copyError: Exception) {
                    Log.w(TAG, "Rename and copy failed, attempt ${attempt + 1}: ${copyError.message}")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Write attempt ${attempt + 1} failed: ${e.message}")
            }
            
            if (attempt < MAX_RETRY_COUNT - 1) {
                Thread.sleep(RETRY_DELAY_MS)
            }
        }
        
        if (tempFile.exists()) {
            tempFile.delete()
        }
        
        return false
    }
    
    private fun writeSnapshotFile(file: File, data: ByteArray): Boolean {
        return writeFileAtomically(file, data)
    }
    
    private fun loadSnapshotFile(file: File): SaveData? {
        if (!file.exists()) return null
        
        return try {
            DataInputStream(FileInputStream(file)).use { dis ->
                val magic = String(dis.readNBytes(MAGIC_BYTES.length))
                if (magic != MAGIC_BYTES) {
                    Log.e(TAG, "Invalid snapshot file magic")
                    return null
                }
                
                val version = dis.readInt()
                val size = dis.readInt()
                val data = dis.readNBytes(size)
                
                deserializeSnapshot(data)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load snapshot file", e)
            null
        }
    }
    
    private fun writeDeltaFile(file: File, data: ByteArray): Boolean {
        return writeFileAtomically(file, data)
    }
    
    private fun readDeltaFile(file: File): ByteArray? {
        return try {
            DataInputStream(FileInputStream(file)).use { dis ->
                val magic = String(dis.readNBytes(MAGIC_BYTES.length))
                if (magic != MAGIC_BYTES) {
                    Log.e(TAG, "Invalid delta file magic")
                    return null
                }
                
                dis.readInt()
                val size = dis.readInt()
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
    
    fun shutdown() {
        compactJob?.cancel()
        scope.cancel()
        
        scope.launch {
            for (slot in 1..IncrementalStorageConfig.MAX_SLOTS) {
                try {
                    saveManifest(slot)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to save manifest for slot $slot during shutdown", e)
                }
            }
        }
        
        Log.i(TAG, "IncrementalStorageManager shutdown initiated")
    }
}
