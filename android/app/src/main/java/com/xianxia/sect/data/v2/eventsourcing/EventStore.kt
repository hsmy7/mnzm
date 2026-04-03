package com.xianxia.sect.data.v2.eventsourcing

import android.content.Context
import android.util.Log
import com.xianxia.sect.data.v2.StorageArchitecture
import com.xianxia.sect.data.v2.StoragePriority
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.*
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

data class StoredDomainEvent(
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
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is StoredDomainEvent) return false
        return eventId == other.eventId
    }
    
    override fun hashCode(): Int = eventId.hashCode()
}

data class EventStream(
    val aggregateType: String,
    val aggregateId: String,
    val version: Long,
    val events: List<StoredDomainEvent>
)

data class EventStoreStats(
    val totalEvents: Long = 0,
    val totalAggregates: Long = 0,
    val storageSize: Long = 0,
    val oldestEventTime: Long = 0,
    val newestEventTime: Long = 0,
    val snapshotCount: Int = 0
)

data class Snapshot(
    val aggregateType: String,
    val aggregateId: String,
    val version: Long,
    val timestamp: Long,
    val state: ByteArray,
    val checksum: String
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Snapshot) return false
        return aggregateType == other.aggregateType && aggregateId == other.aggregateId && version == other.version
    }
    
    override fun hashCode(): Int = 31 * aggregateType.hashCode() + aggregateId.hashCode()
}

interface EventSerializer {
    fun serialize(event: StoredDomainEvent): ByteArray
    fun deserialize(data: ByteArray): StoredDomainEvent
    fun serializeState(state: Any): ByteArray
    fun deserializeState(data: ByteArray): Any
}

class JsonEventSerializer : EventSerializer {
    private val gson = com.google.gson.GsonBuilder()
        .disableHtmlEscaping()
        .create()
    
    override fun serialize(event: StoredDomainEvent): ByteArray {
        val json = gson.toJson(event)
        return json.toByteArray(Charsets.UTF_8)
    }
    
    override fun deserialize(data: ByteArray): StoredDomainEvent {
        val json = String(data, Charsets.UTF_8)
        return gson.fromJson(json, StoredDomainEvent::class.java)
    }
    
    override fun serializeState(state: Any): ByteArray {
        val json = gson.toJson(state)
        return json.toByteArray(Charsets.UTF_8)
    }
    
    override fun deserializeState(data: ByteArray): Any {
        val json = String(data, Charsets.UTF_8)
        return gson.fromJson(json, Any::class.java)
    }
}

class EventStore(
    private val context: Context,
    private val serializer: EventSerializer = JsonEventSerializer(),
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
) {
    companion object {
        private const val TAG = "EventStore"
        private const val EVENT_DIR = "event_store"
        private const val SNAPSHOT_DIR = "snapshots"
        private const val INDEX_FILE = "index.dat"
        private const val EVENT_FILE_PREFIX = "events_"
        private const val FILE_EXTENSION = ".dat"
        private const val MAX_CACHE_SIZE = 10000
    }
    
    private val eventCache = object : LinkedHashMap<String, StoredDomainEvent>(
        16, 0.75f, true
    ) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, StoredDomainEvent>?): Boolean {
            return size > MAX_CACHE_SIZE
        }
    }
    private val cacheLock = Any()
    private val aggregateVersions = ConcurrentHashMap<String, Long>()
    private val snapshotCache = ConcurrentHashMap<String, Snapshot>()
    
    private val writeMutex = Mutex()
    private val eventCounter = AtomicLong(0)
    private val aggregateCounter = AtomicLong(0)
    
    private val _stats = MutableStateFlow(EventStoreStats())
    val stats: StateFlow<EventStoreStats> = _stats.asStateFlow()
    
    private val _events = MutableSharedFlow<StoredDomainEvent>(extraBufferCapacity = 1024)
    val events: SharedFlow<StoredDomainEvent> = _events.asSharedFlow()
    
    private var isShuttingDown = false
    
    init {
        loadIndex()
        startBackgroundTasks()
    }
    
    private fun loadIndex() {
        val indexFile = File(context.filesDir, "$EVENT_DIR/$INDEX_FILE")
        if (!indexFile.exists()) {
            File(context.filesDir, EVENT_DIR).mkdirs()
            File(context.filesDir, "$EVENT_DIR/$SNAPSHOT_DIR").mkdirs()
            return
        }
        
        try {
            DataInputStream(FileInputStream(indexFile)).use { dis ->
                val version = dis.readInt()
                val count = dis.readInt()
                
                repeat(count) {
                    val aggregateKey = dis.readUTF()
                    val version = dis.readLong()
                    aggregateVersions[aggregateKey] = version
                    aggregateCounter.incrementAndGet()
                }
                
                eventCounter.set(dis.readLong())
            }
            
            Log.i(TAG, "Loaded index: ${aggregateVersions.size} aggregates, ${eventCounter.get()} events")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load index", e)
        }
    }
    
    private fun saveIndex() {
        val indexFile = File(context.filesDir, "$EVENT_DIR/$INDEX_FILE")
        indexFile.parentFile?.mkdirs()
        
        try {
            DataOutputStream(FileOutputStream(indexFile)).use { dos ->
                dos.writeInt(1)
                dos.writeInt(aggregateVersions.size)
                
                aggregateVersions.forEach { (key, version) ->
                    dos.writeUTF(key)
                    dos.writeLong(version)
                }
                
                dos.writeLong(eventCounter.get())
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save index", e)
        }
    }
    
    private fun startBackgroundTasks() {
        scope.launch {
            while (isActive && !isShuttingDown) {
                delay(60_000L)
                cleanupOldEvents()
            }
        }
    }
    
    suspend fun append(event: StoredDomainEvent): Boolean {
        return writeMutex.withLock {
            try {
                val aggregateKey = "${event.aggregateType}:${event.aggregateId}"
                val expectedVersion = aggregateVersions.getOrDefault(aggregateKey, 0L)
                
                if (event.version != expectedVersion + 1 && expectedVersion > 0) {
                    Log.w(TAG, "Version conflict for $aggregateKey: expected ${expectedVersion + 1}, got ${event.version}")
                    return@withLock false
                }
                
                val eventFile = getEventFile(event.aggregateType, event.aggregateId)
                appendEventToFile(eventFile, event)
                
                aggregateVersions[aggregateKey] = event.version
                synchronized(cacheLock) {
                    eventCache[event.eventId] = event
                }
                eventCounter.incrementAndGet()
                
                _events.emit(event)
                
                true
            } catch (e: Exception) {
                Log.e(TAG, "Failed to append event", e)
                false
            }
        }
    }
    
    suspend fun appendWithSnapshot(
        event: StoredDomainEvent,
        state: Any? = null
    ): Boolean {
        val success = append(event)
        if (success && state != null && event.version % StorageArchitecture.EventSourcing.SNAPSHOT_THRESHOLD == 0L) {
            createSnapshot(event.aggregateType, event.aggregateId, state, event.version)
        }
        return success
    }
    
    suspend fun appendBatch(events: List<StoredDomainEvent>): Int {
        var successCount = 0
        
        events.groupBy { "${it.aggregateType}:${it.aggregateId}" }.forEach { (_, aggregateEvents) ->
            val sorted = aggregateEvents.sortedBy { it.version }
            for (event in sorted) {
                if (append(event)) {
                    successCount++
                } else {
                    break
                }
            }
        }
        
        return successCount
    }
    
    private fun getEventFile(aggregateType: String, aggregateId: String): File {
        val dir = File(context.filesDir, "$EVENT_DIR/$aggregateType")
        if (!dir.exists()) dir.mkdirs()
        return File(dir, "$aggregateId$FILE_EXTENSION")
    }
    
    private fun appendEventToFile(file: File, event: StoredDomainEvent) {
        val serialized = serializer.serialize(event)
        val compressed = compress(serialized)
        val checksum = calculateChecksum(serialized)
        
        FileOutputStream(file, true).use { fos ->
            DataOutputStream(fos).use { dos ->
                dos.writeInt(compressed.size)
                dos.write(compressed)
                dos.writeUTF(checksum)
            }
        }
    }
    
    suspend fun getEventStream(
        aggregateType: String,
        aggregateId: String,
        fromVersion: Long = 0
    ): EventStream {
        val events = mutableListOf<StoredDomainEvent>()
        val aggregateKey = "$aggregateType:$aggregateId"
        val currentVersion = aggregateVersions.getOrDefault(aggregateKey, 0L)
        
        val snapshot = loadSnapshot(aggregateType, aggregateId)
        val startVersion = if (snapshot != null && snapshot.version > fromVersion) {
            snapshot.version
        } else {
            fromVersion
        }
        
        val eventFile = getEventFile(aggregateType, aggregateId)
        if (eventFile.exists()) {
            readEventsFromFile(eventFile, startVersion).let { events.addAll(it) }
        }
        
        return EventStream(aggregateType, aggregateId, currentVersion, events)
    }
    
    private fun readEventsFromFile(file: File, fromVersion: Long): List<StoredDomainEvent> {
        val events = mutableListOf<StoredDomainEvent>()
        
        try {
            FileInputStream(file).use { fis ->
                while (fis.available() > 0) {
                    DataInputStream(fis).use { dis ->
                        val size = dis.readInt()
                        val compressed = ByteArray(size)
                        dis.readFully(compressed)
                        val checksum = dis.readUTF()
                        
                        val decompressed = decompress(compressed)
                        val calculatedChecksum = calculateChecksum(decompressed)
                        
                        if (checksum == calculatedChecksum) {
                            val event = serializer.deserialize(decompressed)
                            if (event.version > fromVersion) {
                                events.add(event)
                            }
                        }
                    }
                }
            }
        } catch (e: EOFException) {
        } catch (e: Exception) {
            Log.e(TAG, "Error reading events from file", e)
        }
        
        return events.sortedBy { it.version }
    }
    
    private suspend fun createSnapshotAsync(aggregateType: String, aggregateId: String, version: Long, state: ByteArray? = null) {
        scope.launch {
            try {
                val snapshotState = state ?: ByteArray(0)
                
                val snapshot = Snapshot(
                    aggregateType = aggregateType,
                    aggregateId = aggregateId,
                    version = version,
                    timestamp = System.currentTimeMillis(),
                    state = snapshotState,
                    checksum = calculateChecksum(snapshotState)
                )
                
                saveSnapshot(snapshot)
                
                Log.d(TAG, "Created snapshot for $aggregateType:$aggregateId at version $version")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to create snapshot", e)
            }
        }
    }
    
    suspend fun createSnapshot(
        aggregateType: String,
        aggregateId: String,
        state: Any,
        version: Long
    ) {
        val serialized = serializer.serializeState(state)
        createSnapshotAsync(aggregateType, aggregateId, version, serialized)
    }
    
    private fun getSnapshotFile(aggregateType: String, aggregateId: String): File {
        val dir = File(context.filesDir, "$EVENT_DIR/$SNAPSHOT_DIR/$aggregateType")
        if (!dir.exists()) dir.mkdirs()
        return File(dir, "$aggregateId.snap")
    }
    
    private suspend fun loadSnapshot(aggregateType: String, aggregateId: String): Snapshot? {
        val key = "$aggregateType:$aggregateId"
        snapshotCache[key]?.let { return it }
        
        val file = getSnapshotFile(aggregateType, aggregateId)
        if (!file.exists()) return null
        
        return try {
            DataInputStream(FileInputStream(file)).use { dis ->
                val snapshot = Snapshot(
                    aggregateType = dis.readUTF(),
                    aggregateId = dis.readUTF(),
                    version = dis.readLong(),
                    timestamp = dis.readLong(),
                    state = ByteArray(dis.readInt()).also { dis.readFully(it) },
                    checksum = dis.readUTF()
                )
                snapshotCache[key] = snapshot
                snapshot
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load snapshot", e)
            null
        }
    }
    
    private fun saveSnapshot(snapshot: Snapshot) {
        val file = getSnapshotFile(snapshot.aggregateType, snapshot.aggregateId)
        
        DataOutputStream(FileOutputStream(file)).use { dos ->
            dos.writeUTF(snapshot.aggregateType)
            dos.writeUTF(snapshot.aggregateId)
            dos.writeLong(snapshot.version)
            dos.writeLong(snapshot.timestamp)
            dos.writeInt(snapshot.state.size)
            dos.write(snapshot.state)
            dos.writeUTF(snapshot.checksum)
        }
        
        val key = "${snapshot.aggregateType}:${snapshot.aggregateId}"
        snapshotCache[key] = snapshot
    }
    
    fun getAggregateVersion(aggregateType: String, aggregateId: String): Long {
        return aggregateVersions.getOrDefault("$aggregateType:$aggregateId", 0L)
    }
    
    suspend fun getEventsByType(eventType: String, limit: Int = 100): List<StoredDomainEvent> {
        synchronized(cacheLock) {
            return eventCache.values
                .filter { it.eventType == eventType }
                .sortedByDescending { it.timestamp }
                .take(limit)
        }
    }
    
    suspend fun getEventsByTimeRange(startTime: Long, endTime: Long, limit: Int = 1000): List<StoredDomainEvent> {
        synchronized(cacheLock) {
            return eventCache.values
                .filter { it.timestamp in startTime..endTime }
                .sortedByDescending { it.timestamp }
                .take(limit)
        }
    }
    
    suspend fun <T : Any> rebuildAggregate(
        aggregateType: String,
        aggregateId: String,
        initialState: T,
        applier: (T, StoredDomainEvent) -> T
    ): T {
        val snapshot = loadSnapshot(aggregateType, aggregateId)
        var state = snapshot?.let { 
            try {
                serializer.deserializeState(it.state) as T
            } catch (e: Exception) {
                Log.w(TAG, "Failed to deserialize snapshot state, using initial state", e)
                initialState
            }
        } ?: initialState
        
        val fromVersion = snapshot?.version ?: 0L
        val eventStream = getEventStream(aggregateType, aggregateId, fromVersion)
        
        eventStream.events.sortedBy { it.version }.forEach { event ->
            state = applier(state, event)
        }
        
        return state
    }
    
    private fun cleanupOldEvents() {
        val cutoffTime = System.currentTimeMillis() - 
            (StorageArchitecture.EventSourcing.EVENT_RETENTION_DAYS * 24 * 60 * 60 * 1000L)
        
        synchronized(cacheLock) {
            val toRemove = eventCache.entries
                .filter { it.value.timestamp < cutoffTime }
                .take(1000)
            
            toRemove.forEach { eventCache.remove(it.key) }
            
            if (toRemove.isNotEmpty()) {
                Log.d(TAG, "Cleaned up ${toRemove.size} old events from cache")
            }
        }
        
        saveIndex()
        updateStats()
    }
    
    private fun updateStats() {
        val eventDir = File(context.filesDir, EVENT_DIR)
        val storageSize = calculateDirectorySize(eventDir)
        
        _stats.value = EventStoreStats(
            totalEvents = eventCounter.get(),
            totalAggregates = aggregateCounter.get(),
            storageSize = storageSize,
            snapshotCount = snapshotCache.size
        )
    }
    
    private fun calculateDirectorySize(dir: File): Long {
        if (!dir.exists()) return 0
        return dir.walkTopDown().filter { it.isFile }.map { it.length() }.sum()
    }
    
    private fun compress(data: ByteArray): ByteArray {
        return ByteArrayOutputStream().use { baos ->
            GZIPOutputStream(baos).use { it.write(data) }
            baos.toByteArray()
        }
    }
    
    private fun decompress(data: ByteArray): ByteArray {
        return GZIPInputStream(ByteArrayInputStream(data)).use { it.readBytes() }
    }
    
    private fun calculateChecksum(data: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(data).joinToString("") { "%02x".format(it) }
    }
    
    fun generateEventId(): String {
        return "${System.currentTimeMillis()}_${eventCounter.incrementAndGet()}"
    }
    
    fun shutdown() {
        isShuttingDown = true
        saveIndex()
        scope.cancel()
        Log.i(TAG, "EventStore shutdown completed")
    }
}
