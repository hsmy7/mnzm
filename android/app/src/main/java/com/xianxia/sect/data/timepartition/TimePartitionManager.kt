package com.xianxia.sect.data.timepartition

import android.content.Context
import android.util.Log
import com.xianxia.sect.core.model.BattleLog
import com.xianxia.sect.core.model.GameEvent
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.*
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

object TimePartitionConfig {
    const val PARTITION_SIZE_DAYS = 7
    const val MAX_PARTITIONS = 52
    const val COMPRESSION_THRESHOLD_BYTES = 1024
    const val INDEX_CACHE_SIZE = 100
    const val FLUSH_INTERVAL_MS = 60_000L
    const val COMPACTION_THRESHOLD_PARTITIONS = 4
}

data class PartitionInfo(
    val partitionId: String,
    val startTime: Long,
    val endTime: Long,
    val recordCount: Int,
    val compressedSize: Long,
    val uncompressedSize: Long,
    val lastAccessTime: Long,
    val isLoaded: Boolean
)

data class PartitionIndex(
    val partitionId: String,
    val entries: MutableList<IndexEntry> = mutableListOf()
)

data class IndexEntry(
    val recordId: String,
    val timestamp: Long,
    val offset: Long,
    val size: Int
)

/** 具名有界 LinkedHashMap（避免匿名内部类触发 KSP getSimpleName NPE） */
private class BoundedPartitionIndexCache : LinkedHashMap<String, PartitionIndex>(TimePartitionConfig.INDEX_CACHE_SIZE, 0.75f, true) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, PartitionIndex>?): Boolean = size > TimePartitionConfig.INDEX_CACHE_SIZE
}

class TimePartitionManager(
    private val context: Context,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
) {
    companion object {
        private const val TAG = "TimePartitionManager"
        private const val BATTLE_LOG_DIR = "battle_logs"
        private const val EVENT_DIR = "events"
        private const val INDEX_SUFFIX = ".idx"
        private const val DATA_SUFFIX = ".dat"
        private const val MANIFEST_FILE = "manifest.json"
    }

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    
    private val battleLogPartitions = ConcurrentHashMap<String, PartitionInfo>()
    private val eventPartitions = ConcurrentHashMap<String, PartitionInfo>()
    
    private val battleLogIndexCache = BoundedPartitionIndexCache()
    private val eventIndexCache = BoundedPartitionIndexCache()
    
    private val writeBuffer = ConcurrentHashMap<String, MutableList<Any>>()
    private val writeBufferSize = AtomicLong(0)
    
    private val _partitionStats = MutableStateFlow(PartitionStats())
    val partitionStats: StateFlow<PartitionStats> = _partitionStats.asStateFlow()
    
    private var flushJob: Job? = null
    private var isShuttingDown = false

    init {
        loadManifests()
        startFlushTask()
    }

    private fun loadManifests() {
        scope.launch {
            loadPartitionManifest(BATTLE_LOG_DIR, battleLogPartitions)
            loadPartitionManifest(EVENT_DIR, eventPartitions)
            updateStats()
        }
    }

    private fun loadPartitionManifest(dirName: String, partitionMap: ConcurrentHashMap<String, PartitionInfo>) {
        val dir = File(context.filesDir, dirName)
        if (!dir.exists()) {
            dir.mkdirs()
            return
        }
        
        val manifestFile = File(dir, MANIFEST_FILE)
        if (manifestFile.exists()) {
            try {
                val json = manifestFile.readText()
                val manifest = parseManifest(json)
                manifest.forEach { info ->
                    partitionMap[info.partitionId] = info
                }
                Log.i(TAG, "Loaded ${manifest.size} partitions from $dirName")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load manifest for $dirName", e)
            }
        }
    }

    private fun parseManifest(json: String): List<PartitionInfo> {
        val partitions = mutableListOf<PartitionInfo>()
        val lines = json.lines().filter { it.isNotBlank() }
        for (line in lines) {
            try {
                val parts = line.split(",")
                if (parts.size >= 7) {
                    partitions.add(PartitionInfo(
                        partitionId = parts[0],
                        startTime = parts[1].toLong(),
                        endTime = parts[2].toLong(),
                        recordCount = parts[3].toInt(),
                        compressedSize = parts[4].toLong(),
                        uncompressedSize = parts[5].toLong(),
                        lastAccessTime = parts[6].toLong(),
                        isLoaded = false
                    ))
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to parse partition info: $line")
            }
        }
        return partitions
    }

    private fun saveManifest(dirName: String, partitionMap: ConcurrentHashMap<String, PartitionInfo>) {
        val dir = File(context.filesDir, dirName)
        if (!dir.exists()) dir.mkdirs()
        
        val manifestFile = File(dir, MANIFEST_FILE)
        val sb = StringBuilder()
        partitionMap.values.forEach { info ->
            sb.append("${info.partitionId},${info.startTime},${info.endTime},${info.recordCount},${info.compressedSize},${info.uncompressedSize},${info.lastAccessTime}\n")
        }
        manifestFile.writeText(sb.toString())
    }

    private fun startFlushTask() {
        flushJob = scope.launch {
            while (isActive && !isShuttingDown) {
                delay(TimePartitionConfig.FLUSH_INTERVAL_MS)
                flushBuffers()
            }
        }
    }

    suspend fun saveBattleLogs(slot: Int, logs: List<BattleLog>) {
        if (logs.isEmpty()) return
        
        val grouped = logs.groupBy { getPartitionId(it.timestamp) }
        
        grouped.forEach { (partitionId, partitionLogs) ->
            val bufferKey = "battle_log_${slot}_$partitionId"
            synchronized(writeBuffer) {
                val buffer = writeBuffer.getOrPut(bufferKey) { mutableListOf() }
                buffer.addAll(partitionLogs)
                writeBufferSize.addAndGet(partitionLogs.size.toLong())
            }
            
            updatePartitionInfo(BATTLE_LOG_DIR, slot, partitionId, partitionLogs)
        }
        
        if (writeBufferSize.get() > 1000) {
            flushBuffers()
        }
    }

    suspend fun saveEvents(slot: Int, events: List<GameEvent>) {
        if (events.isEmpty()) return
        
        val grouped = events.groupBy { getPartitionId(it.timestamp) }
        
        grouped.forEach { (partitionId, partitionEvents) ->
            val bufferKey = "event_${slot}_$partitionId"
            synchronized(writeBuffer) {
                val buffer = writeBuffer.getOrPut(bufferKey) { mutableListOf() }
                buffer.addAll(partitionEvents)
                writeBufferSize.addAndGet(partitionEvents.size.toLong())
            }
            
            updatePartitionInfo(EVENT_DIR, slot, partitionId, partitionEvents)
        }
        
        if (writeBufferSize.get() > 1000) {
            flushBuffers()
        }
    }

    private fun getPartitionId(timestamp: Long): String {
        val calendar = Calendar.getInstance()
        calendar.timeInMillis = timestamp
        calendar.set(Calendar.HOUR_OF_DAY, 0)
        calendar.set(Calendar.MINUTE, 0)
        calendar.set(Calendar.SECOND, 0)
        calendar.set(Calendar.MILLISECOND, 0)
        
        val dayOfWeek = calendar.get(Calendar.DAY_OF_WEEK)
        val daysToSubtract = dayOfWeek - Calendar.MONDAY
        if (daysToSubtract < 0) {
            calendar.add(Calendar.DAY_OF_MONTH, daysToSubtract + 7)
        } else {
            calendar.add(Calendar.DAY_OF_MONTH, -daysToSubtract)
        }
        
        return dateFormat.format(calendar.time)
    }

    private fun getPartitionTimeRange(partitionId: String): Pair<Long, Long> {
        val startDate = dateFormat.parse(partitionId) ?: return Pair(0L, 0L)
        val calendar = Calendar.getInstance()
        calendar.time = startDate
        val startTime = calendar.timeInMillis
        
        calendar.add(Calendar.DAY_OF_MONTH, TimePartitionConfig.PARTITION_SIZE_DAYS)
        val endTime = calendar.timeInMillis - 1
        
        return Pair(startTime, endTime)
    }

    private fun updatePartitionInfo(dirName: String, slot: Int, partitionId: String, records: List<Any>) {
        val partitionMap = if (dirName == BATTLE_LOG_DIR) battleLogPartitions else eventPartitions
        val key = "${slot}_$partitionId"
        
        val (startTime, endTime) = getPartitionTimeRange(partitionId)
        val existing = partitionMap[key]
        
        if (existing != null) {
            partitionMap[key] = existing.copy(
                recordCount = existing.recordCount + records.size,
                lastAccessTime = System.currentTimeMillis()
            )
        } else {
            partitionMap[key] = PartitionInfo(
                partitionId = partitionId,
                startTime = startTime,
                endTime = endTime,
                recordCount = records.size,
                compressedSize = 0,
                uncompressedSize = 0,
                lastAccessTime = System.currentTimeMillis(),
                isLoaded = false
            )
        }
    }

    private suspend fun flushBuffers() {
        val buffersToFlush: Map<String, List<Any>>
        
        synchronized(writeBuffer) {
            buffersToFlush = writeBuffer.toMap().mapValues { it.value.toList() }
            writeBuffer.clear()
            writeBufferSize.set(0)
        }
        
        buffersToFlush.forEach { (key, records) ->
            try {
                val parts = key.split("_")
                if (parts.size >= 3) {
                    val type = parts[0]
                    val slot = parts[1].toIntOrNull() ?: 1
                    val partitionId = parts.drop(2).joinToString("_")
                    
                    when (type) {
                        "battle" -> flushPartition(BATTLE_LOG_DIR, slot, partitionId, records)
                        "event" -> flushPartition(EVENT_DIR, slot, partitionId, records)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to flush buffer: $key", e)
            }
        }
        
        saveManifest(BATTLE_LOG_DIR, battleLogPartitions)
        saveManifest(EVENT_DIR, eventPartitions)
        updateStats()
    }

    private suspend fun flushPartition(dirName: String, slot: Int, partitionId: String, records: List<Any>) {
        if (records.isEmpty()) return
        
        val dir = File(context.filesDir, "$dirName/$slot")
        if (!dir.exists()) dir.mkdirs()
        
        val dataFile = File(dir, "$partitionId$DATA_SUFFIX")
        val indexFile = File(dir, "$partitionId$INDEX_SUFFIX")
        
        val existingIndex = if (indexFile.exists()) {
            loadIndex(indexFile)
        } else {
            PartitionIndex(partitionId)
        }
        
        val compressedData = ByteArrayOutputStream().use { baos ->
            GZIPOutputStream(baos).use { gzipStream ->
                DataOutputStream(gzipStream).use { dataOutputStream ->
                    var offset = if (dataFile.exists()) dataFile.length() else 0L
                    
                    records.forEach { record ->
                        val json = serializeRecord(record)
                        val bytes = json.toByteArray(Charsets.UTF_8)
                        
                        val entry = IndexEntry(
                            recordId = getRecordId(record),
                            timestamp = getRecordTimestamp(record),
                            offset = offset,
                            size = bytes.size
                        )
                        existingIndex.entries.add(entry)
                        
                        dataOutputStream.writeInt(bytes.size)
                        dataOutputStream.write(bytes)
                        offset += 4 + bytes.size
                    }
                }
            }
            baos.toByteArray()
        }
        
        if (dataFile.exists()) {
            dataFile.appendBytes(compressedData)
        } else {
            dataFile.writeBytes(compressedData)
        }
        
        saveIndex(indexFile, existingIndex)
        
        val partitionMap = if (dirName == BATTLE_LOG_DIR) battleLogPartitions else eventPartitions
        val key = "${slot}_$partitionId"
        val existing = partitionMap[key]
        if (existing != null) {
            partitionMap[key] = existing.copy(
                compressedSize = dataFile.length(),
                uncompressedSize = existing.uncompressedSize + records.sumOf { estimateSize(it) }
            )
        }
    }

    private fun loadIndex(file: File): PartitionIndex {
        return try {
            DataInputStream(FileInputStream(file)).use { dis ->
                val partitionId = dis.readUTF()
                val count = dis.readInt()
                val entries = mutableListOf<IndexEntry>()
                
                repeat(count) {
                    entries.add(IndexEntry(
                        recordId = dis.readUTF(),
                        timestamp = dis.readLong(),
                        offset = dis.readLong(),
                        size = dis.readInt()
                    ))
                }
                
                PartitionIndex(partitionId, entries)
            }
        } catch (e: Exception) {
            PartitionIndex(file.nameWithoutExtension)
        }
    }

    private fun saveIndex(file: File, index: PartitionIndex) {
        DataOutputStream(FileOutputStream(file)).use { dos ->
            dos.writeUTF(index.partitionId)
            dos.writeInt(index.entries.size)
            
            index.entries.forEach { entry ->
                dos.writeUTF(entry.recordId)
                dos.writeLong(entry.timestamp)
                dos.writeLong(entry.offset)
                dos.writeInt(entry.size)
            }
            
            dos.flush()
        }
    }

    suspend fun loadBattleLogs(slot: Int, cutoffTime: Long): List<BattleLog> {
        val logs = mutableListOf<BattleLog>()
        val relevantPartitions = battleLogPartitions.filterKeys { 
            it.startsWith("${slot}_") 
        }.filterValues { 
            it.endTime >= cutoffTime 
        }
        
        relevantPartitions.forEach { (key, info) ->
            try {
                val partitionLogs = loadPartition<BattleLog>(BATTLE_LOG_DIR, slot, info.partitionId)
                logs.addAll(partitionLogs.filter { it.timestamp >= cutoffTime })
                
                battleLogPartitions[key] = info.copy(lastAccessTime = System.currentTimeMillis())
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load battle log partition: $key", e)
            }
        }
        
        return logs.sortedByDescending { it.timestamp }
    }

    suspend fun loadEvents(slot: Int, cutoffTime: Long): List<GameEvent> {
        val events = mutableListOf<GameEvent>()
        val relevantPartitions = eventPartitions.filterKeys { 
            it.startsWith("${slot}_") 
        }.filterValues { 
            it.endTime >= cutoffTime 
        }
        
        relevantPartitions.forEach { (key, info) ->
            try {
                val partitionEvents = loadPartition<GameEvent>(EVENT_DIR, slot, info.partitionId)
                events.addAll(partitionEvents.filter { it.timestamp >= cutoffTime })
                
                eventPartitions[key] = info.copy(lastAccessTime = System.currentTimeMillis())
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load event partition: $key", e)
            }
        }
        
        return events.sortedByDescending { it.timestamp }
    }

    private suspend inline fun <reified T> loadPartition(dirName: String, slot: Int, partitionId: String): List<T> {
        val dir = File(context.filesDir, "$dirName/$slot")
        val dataFile = File(dir, "$partitionId$DATA_SUFFIX")
        
        if (!dataFile.exists()) return emptyList()
        
        val records = mutableListOf<T>()
        
        try {
            val fis = FileInputStream(dataFile)
            val gzipStream = GZIPInputStream(fis)
            val dis = DataInputStream(gzipStream)
            
            while (dis.available() > 0) {
                try {
                    val size = dis.readInt()
                    val bytes = ByteArray(size)
                    dis.readFully(bytes)
                    val json = String(bytes, Charsets.UTF_8)
                    val record = deserializeRecord<T>(json)
                    if (record != null) {
                        records.add(record)
                    }
                } catch (e: EOFException) {
                    break
                } catch (e: Exception) {
                    Log.w(TAG, "Error reading record in partition $partitionId", e)
                }
            }
            
            dis.close()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load partition data: $partitionId", e)
        }
        
        return records
    }

    suspend fun queryBattleLogs(slot: Int, startTime: Long, endTime: Long, limit: Int): List<BattleLog> {
        val logs = mutableListOf<BattleLog>()
        
        battleLogPartitions.filterKeys { it.startsWith("${slot}_") }
            .filterValues { it.startTime <= endTime && it.endTime >= startTime }
            .toSortedMap(compareByDescending { it })
            .forEach { (key, info) ->
                if (logs.size < limit) {
                    val partitionLogs = loadPartition<BattleLog>(BATTLE_LOG_DIR, slot, info.partitionId)
                    logs.addAll(partitionLogs.filter { it.timestamp in startTime..endTime }
                        .sortedByDescending { it.timestamp }
                        .take(limit - logs.size))
                }
            }
        
        return logs.take(limit)
    }

    suspend fun queryEvents(slot: Int, startTime: Long, endTime: Long, limit: Int): List<GameEvent> {
        val events = mutableListOf<GameEvent>()
        
        eventPartitions.filterKeys { it.startsWith("${slot}_") }
            .filterValues { it.startTime <= endTime && it.endTime >= startTime }
            .toSortedMap(compareByDescending { it })
            .forEach { (key, info) ->
                if (events.size < limit) {
                    val partitionEvents = loadPartition<GameEvent>(EVENT_DIR, slot, info.partitionId)
                    events.addAll(partitionEvents.filter { it.timestamp in startTime..endTime }
                        .sortedByDescending { it.timestamp }
                        .take(limit - events.size))
                }
            }
        
        return events.take(limit)
    }

    suspend fun compactPartitions(slot: Int) {
        compactPartitionType(BATTLE_LOG_DIR, slot, battleLogPartitions)
        compactPartitionType(EVENT_DIR, slot, eventPartitions)
        updateStats()
    }

    private suspend fun compactPartitionType(
        dirName: String, 
        slot: Int, 
        partitionMap: ConcurrentHashMap<String, PartitionInfo>
    ) {
        val oldPartitions = partitionMap.filterKeys { it.startsWith("${slot}_") }
            .entries
            .sortedBy { it.value.startTime }
            .takeWhile { 
                System.currentTimeMillis() - it.value.endTime > 30L * 24 * 60 * 60 * 1000 
            }
            .take(TimePartitionConfig.COMPACTION_THRESHOLD_PARTITIONS)
        
        if (oldPartitions.size < TimePartitionConfig.COMPACTION_THRESHOLD_PARTITIONS) return
        
        val mergedPartitionId = "merged_${dateFormat.format(Date(oldPartitions.first().value.startTime))}"
        val mergedRecords = mutableListOf<Any>()
        var totalSize = 0L
        
        oldPartitions.forEach { (key, info) ->
            try {
                val records = loadPartition<Any>(dirName, slot, info.partitionId)
                mergedRecords.addAll(records)
                totalSize += info.uncompressedSize
                
                val dir = File(context.filesDir, "$dirName/$slot")
                File(dir, "${info.partitionId}$DATA_SUFFIX").delete()
                File(dir, "${info.partitionId}$INDEX_SUFFIX").delete()
                
                partitionMap.remove(key)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load partition for compaction: $key", e)
            }
        }
        
        if (mergedRecords.isNotEmpty()) {
            flushPartition(dirName, slot, mergedPartitionId, mergedRecords)
            
            val (startTime, endTime) = getPartitionTimeRange(mergedPartitionId)
            partitionMap["${slot}_$mergedPartitionId"] = PartitionInfo(
                partitionId = mergedPartitionId,
                startTime = startTime,
                endTime = endTime,
                recordCount = mergedRecords.size,
                compressedSize = 0,
                uncompressedSize = totalSize,
                lastAccessTime = System.currentTimeMillis(),
                isLoaded = false
            )
            
            Log.i(TAG, "Compacted ${oldPartitions.size} partitions into $mergedPartitionId")
        }
    }

    suspend fun verifyIntegrity(slot: Int): Boolean {
        val battleLogValid = verifyPartitionIntegrity(BATTLE_LOG_DIR, slot, battleLogPartitions)
        val eventValid = verifyPartitionIntegrity(EVENT_DIR, slot, eventPartitions)
        return battleLogValid && eventValid
    }

    private suspend fun verifyPartitionIntegrity(
        dirName: String, 
        slot: Int, 
        partitionMap: ConcurrentHashMap<String, PartitionInfo>
    ): Boolean {
        return partitionMap.filterKeys { it.startsWith("${slot}_") }.all { (key, info) ->
            try {
                val dir = File(context.filesDir, "$dirName/$slot")
                val dataFile = File(dir, "${info.partitionId}$DATA_SUFFIX")
                val indexFile = File(dir, "${info.partitionId}$INDEX_SUFFIX")
                
                dataFile.exists() && indexFile.exists()
            } catch (e: Exception) {
                Log.e(TAG, "Integrity check failed for partition: $key", e)
                false
            }
        }
    }

    fun deleteSlot(slot: Int) {
        scope.launch {
            listOf(BATTLE_LOG_DIR, EVENT_DIR).forEach { dirName ->
                val dir = File(context.filesDir, "$dirName/$slot")
                if (dir.exists()) {
                    dir.deleteRecursively()
                }
            }
            
            battleLogPartitions.keys.removeAll { it.startsWith("${slot}_") }
            eventPartitions.keys.removeAll { it.startsWith("${slot}_") }
            
            saveManifest(BATTLE_LOG_DIR, battleLogPartitions)
            saveManifest(EVENT_DIR, eventPartitions)
            updateStats()
        }
    }

    fun getPartitionInfo(slot: Int, type: String): List<PartitionInfo> {
        val partitionMap = if (type == "battle_log") battleLogPartitions else eventPartitions
        return partitionMap.filterKeys { it.startsWith("${slot}_") }.values.toList()
    }

    private fun updateStats() {
        _partitionStats.value = PartitionStats(
            battleLogPartitionCount = battleLogPartitions.size,
            eventPartitionCount = eventPartitions.size,
            totalBattleLogSize = battleLogPartitions.values.sumOf { it.compressedSize },
            totalEventSize = eventPartitions.values.sumOf { it.compressedSize },
            totalRecordCount = battleLogPartitions.values.sumOf { it.recordCount.toLong() } + 
                              eventPartitions.values.sumOf { it.recordCount.toLong() }
        )
    }

    private fun serializeRecord(record: Any): String {
        return when (record) {
            is BattleLog -> com.google.gson.Gson().toJson(record)
            is GameEvent -> com.google.gson.Gson().toJson(record)
            else -> record.toString()
        }
    }

    private inline fun <reified T> deserializeRecord(json: String): T? {
        return try {
            com.google.gson.Gson().fromJson(json, T::class.java)
        } catch (e: Exception) {
            null
        }
    }

    private fun getRecordId(record: Any): String {
        return when (record) {
            is BattleLog -> record.id
            is GameEvent -> record.id
            else -> record.hashCode().toString()
        }
    }

    private fun getRecordTimestamp(record: Any): Long {
        return when (record) {
            is BattleLog -> record.timestamp
            is GameEvent -> record.timestamp
            else -> System.currentTimeMillis()
        }
    }

    private fun estimateSize(record: Any): Long {
        return serializeRecord(record).length.toLong()
    }

    fun shutdown() {
        isShuttingDown = true
        flushJob?.cancel()
        scope.cancel()

        runBlocking {
            flushBuffers()
        }
        
        saveManifest(BATTLE_LOG_DIR, battleLogPartitions)
        saveManifest(EVENT_DIR, eventPartitions)
        
        Log.i(TAG, "TimePartitionManager shutdown completed")
    }

    data class PartitionStats(
        val battleLogPartitionCount: Int = 0,
        val eventPartitionCount: Int = 0,
        val totalBattleLogSize: Long = 0L,
        val totalEventSize: Long = 0L,
        val totalRecordCount: Long = 0L
    )
}
