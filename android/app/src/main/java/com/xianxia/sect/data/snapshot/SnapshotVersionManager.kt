package com.xianxia.sect.data.snapshot

import android.content.Context
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.*
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

object SnapshotConfig {
    const val MAX_SNAPSHOTS_PER_SLOT = 20
    const val MAX_INCREMENTAL_SNAPSHOTS = 10
    const val SNAPSHOT_INTERVAL_MS = 300_000L
    const val FULL_SNAPSHOT_INTERVAL_MS = 3_600_000L
    const val SNAPSHOT_DIR = "snapshots"
    const val MANIFEST_FILE = "snapshot_manifest.json"
    const val CHUNK_SIZE = 64 * 1024
}

enum class SnapshotType {
    FULL,
    INCREMENTAL,
    DIFF,
    AUTO
}

enum class SnapshotStatus {
    CREATING,
    COMPLETE,
    CORRUPTED,
    RESTORING
}

data class SnapshotMetadata(
    val id: String,
    val slot: Int,
    val type: SnapshotType,
    val status: SnapshotStatus,
    val createdAt: Long,
    val size: Long,
    val checksum: String,
    val parentSnapshotId: String? = null,
    val gameYear: Int = 1,
    val gameMonth: Int = 1,
    val sectName: String = "",
    val description: String = "",
    val tags: List<String> = emptyList(),
    val isEmergency: Boolean = false
)

data class SnapshotManifest(
    val slot: Int,
    val snapshots: MutableList<SnapshotMetadata> = mutableListOf(),
    var lastFullSnapshotId: String? = null,
    var lastIncrementalSnapshotId: String? = null,
    var totalSize: Long = 0
)

data class DiffEntry(
    val path: String,
    val operation: DiffOperation,
    val oldHash: String?,
    val newHash: String?,
    val size: Long
)

enum class DiffOperation {
    CREATE,
    MODIFY,
    DELETE
}

class SnapshotVersionManager(
    private val context: Context,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
) {
    companion object {
        private const val TAG = "SnapshotVersionManager"
    }

    private val dateFormat = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
    
    private val manifests = ConcurrentHashMap<Int, SnapshotManifest>()
    private val snapshotLocks = ConcurrentHashMap<String, Mutex>()
    
    private val _snapshotState = MutableStateFlow<SnapshotState>(SnapshotState.Idle)
    val snapshotState: StateFlow<SnapshotState> = _snapshotState.asStateFlow()
    
    private var autoSnapshotJob: Job? = null
    private var isShuttingDown = false

    sealed class SnapshotState {
        object Idle : SnapshotState()
        data class Creating(val progress: Float, val snapshotId: String) : SnapshotState()
        data class Restoring(val progress: Float, val snapshotId: String) : SnapshotState()
        data class Error(val message: String) : SnapshotState()
    }

    init {
        loadAllManifests()
    }

    private fun loadAllManifests() {
        val snapshotDir = File(context.filesDir, SnapshotConfig.SNAPSHOT_DIR)
        if (!snapshotDir.exists()) {
            snapshotDir.mkdirs()
            return
        }
        
        (1..5).forEach { slot ->
            loadManifest(slot)
        }
    }

    private fun loadManifest(slot: Int) {
        val manifestFile = getManifestFile(slot)
        if (manifestFile.exists()) {
            try {
                val json = manifestFile.readText()
                val manifest = parseManifest(json)
                manifests[slot] = manifest
                Log.i(TAG, "Loaded manifest for slot $slot with ${manifest.snapshots.size} snapshots")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load manifest for slot $slot", e)
                manifests[slot] = SnapshotManifest(slot)
            }
        } else {
            manifests[slot] = SnapshotManifest(slot)
        }
    }

    private fun parseManifest(json: String): SnapshotManifest {
        val lines = json.lines().filter { it.isNotBlank() }
        if (lines.isEmpty()) return SnapshotManifest(1)
        
        val parts = lines[0].split(",")
        val slot = parts.getOrNull(0)?.toIntOrNull() ?: 1
        val lastFullId = parts.getOrNull(1)?.takeIf { it.isNotEmpty() }
        val lastIncrementalId = parts.getOrNull(2)?.takeIf { it.isNotEmpty() }
        val totalSize = parts.getOrNull(3)?.toLongOrNull() ?: 0L
        
        val snapshots = mutableListOf<SnapshotMetadata>()
        lines.drop(1).forEach { line ->
            try {
                val snapshot = parseSnapshotMetadata(line)
                snapshots.add(snapshot)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to parse snapshot metadata: $line")
            }
        }
        
        return SnapshotManifest(
            slot = slot,
            snapshots = snapshots,
            lastFullSnapshotId = lastFullId,
            lastIncrementalSnapshotId = lastIncrementalId,
            totalSize = totalSize
        )
    }

    private fun parseSnapshotMetadata(line: String): SnapshotMetadata {
        val parts = line.split(",")
        return SnapshotMetadata(
            id = parts[0],
            slot = parts[1].toInt(),
            type = SnapshotType.valueOf(parts[2]),
            status = SnapshotStatus.valueOf(parts[3]),
            createdAt = parts[4].toLong(),
            size = parts[5].toLong(),
            checksum = parts[6],
            parentSnapshotId = parts.getOrNull(7)?.takeIf { it.isNotEmpty() },
            gameYear = parts.getOrNull(8)?.toIntOrNull() ?: 1,
            gameMonth = parts.getOrNull(9)?.toIntOrNull() ?: 1,
            sectName = parts.getOrNull(10) ?: "",
            description = parts.getOrNull(11) ?: "",
            tags = parts.getOrNull(12)?.split("|")?.filter { it.isNotEmpty() } ?: emptyList()
        )
    }

    private fun saveManifest(slot: Int) {
        val manifest = manifests[slot] ?: return
        val manifestFile = getManifestFile(slot)
        
        val sb = StringBuilder()
        sb.append("${manifest.slot},${manifest.lastFullSnapshotId ?: ""},${manifest.lastIncrementalSnapshotId ?: ""},${manifest.totalSize}\n")
        
        manifest.snapshots.forEach { snapshot ->
            sb.append("${snapshot.id},${snapshot.slot},${snapshot.type},${snapshot.status},${snapshot.createdAt},${snapshot.size},${snapshot.checksum},${snapshot.parentSnapshotId ?: ""},${snapshot.gameYear},${snapshot.gameMonth},${snapshot.sectName},${snapshot.description},${snapshot.tags.joinToString("|")}\n")
        }
        
        manifestFile.writeText(sb.toString())
    }

    private fun getManifestFile(slot: Int): File {
        val snapshotDir = File(context.filesDir, SnapshotConfig.SNAPSHOT_DIR)
        if (!snapshotDir.exists()) snapshotDir.mkdirs()
        return File(snapshotDir, "manifest_$slot.json")
    }

    suspend fun createFullSnapshot(slot: Int, description: String = ""): SnapshotMetadata? {
        return createSnapshot(slot, SnapshotType.FULL, null, description)
    }

    suspend fun createPreSaveSnapshot(slot: Int): String {
        val metadata = createSnapshot(slot, SnapshotType.INCREMENTAL, null, "pre-save")
        return metadata?.id ?: ""
    }

    suspend fun createIncrementalSnapshot(slot: Int, description: String = ""): SnapshotMetadata? {
        val manifest = manifests[slot] ?: return null
        val parentId = manifest.lastFullSnapshotId ?: manifest.lastIncrementalSnapshotId
        return createSnapshot(slot, SnapshotType.INCREMENTAL, parentId, description)
    }

    suspend fun createDiffSnapshot(slot: Int, baseSnapshotId: String, description: String = ""): SnapshotMetadata? {
        return createSnapshot(slot, SnapshotType.DIFF, baseSnapshotId, description)
    }

    private suspend fun createSnapshot(
        slot: Int,
        type: SnapshotType,
        parentSnapshotId: String?,
        description: String
    ): SnapshotMetadata? {
        val snapshotId = generateSnapshotId(slot, type)
        val lock = snapshotLocks.getOrPut(snapshotId) { Mutex() }
        
        return lock.withLock {
            _snapshotState.value = SnapshotState.Creating(0f, snapshotId)
            
            try {
                val snapshotDir = getSnapshotDir(slot, snapshotId)
                snapshotDir.mkdirs()
                
                val startTime = System.currentTimeMillis()
                
                _snapshotState.value = SnapshotState.Creating(0.1f, snapshotId)
                copyDatabaseFiles(slot, snapshotDir)
                
                _snapshotState.value = SnapshotState.Creating(0.3f, snapshotId)
                copyChunkedFiles(slot, snapshotDir)
                
                _snapshotState.value = SnapshotState.Creating(0.5f, snapshotId)
                if (type == SnapshotType.INCREMENTAL && parentSnapshotId != null) {
                    createDiffIndex(slot, parentSnapshotId, snapshotId)
                }
                
                _snapshotState.value = SnapshotState.Creating(0.7f, snapshotId)
                val checksum = calculateChecksum(snapshotDir)
                
                _snapshotState.value = SnapshotState.Creating(0.8f, snapshotId)
                compressSnapshot(snapshotDir)
                
                val size = calculateSnapshotSize(snapshotDir)
                
                val metadata = SnapshotMetadata(
                    id = snapshotId,
                    slot = slot,
                    type = type,
                    status = SnapshotStatus.COMPLETE,
                    createdAt = System.currentTimeMillis(),
                    size = size,
                    checksum = checksum,
                    parentSnapshotId = parentSnapshotId,
                    description = description
                )
                
                _snapshotState.value = SnapshotState.Creating(0.9f, snapshotId)
                addSnapshotToManifest(slot, metadata)
                
                cleanupOldSnapshots(slot)
                
                val elapsed = System.currentTimeMillis() - startTime
                Log.i(TAG, "Created $type snapshot $snapshotId in ${elapsed}ms")
                
                _snapshotState.value = SnapshotState.Idle
                metadata
            } catch (e: Exception) {
                Log.e(TAG, "Failed to create snapshot $snapshotId", e)
                _snapshotState.value = SnapshotState.Error(e.message ?: "Unknown error")
                deleteSnapshotFiles(slot, snapshotId)
                null
            }
        }
    }

    private fun generateSnapshotId(slot: Int, type: SnapshotType): String {
        val timestamp = dateFormat.format(Date())
        val typePrefix = when (type) {
            SnapshotType.FULL -> "F"
            SnapshotType.INCREMENTAL -> "I"
            SnapshotType.DIFF -> "D"
            SnapshotType.AUTO -> "A"
        }
        return "S${slot}_${typePrefix}_${timestamp}"
    }

    private fun getSnapshotDir(slot: Int, snapshotId: String): File {
        val baseDir = File(context.filesDir, SnapshotConfig.SNAPSHOT_DIR)
        return File(baseDir, "$slot/$snapshotId")
    }

    private suspend fun copyDatabaseFiles(slot: Int, snapshotDir: File) {
        val dbFile = OptimizedGameDatabase.getDatabaseFile(context, slot)
        val walFile = OptimizedGameDatabase.getWalFile(context, slot)
        val shmFile = OptimizedGameDatabase.getShmFile(context, slot)
        
        if (dbFile.exists()) {
            dbFile.copyTo(File(snapshotDir, dbFile.name), overwrite = true)
        }
        if (walFile.exists()) {
            walFile.copyTo(File(snapshotDir, walFile.name), overwrite = true)
        }
        if (shmFile.exists()) {
            shmFile.copyTo(File(snapshotDir, shmFile.name), overwrite = true)
        }
    }

    private suspend fun copyChunkedFiles(slot: Int, snapshotDir: File) {
        val chunkedDir = File(context.filesDir, "chunked/$slot")
        if (chunkedDir.exists()) {
            val targetDir = File(snapshotDir, "chunked")
            chunkedDir.copyRecursively(targetDir, overwrite = true)
        }
        
        val battleLogDir = File(context.filesDir, "battle_logs/$slot")
        if (battleLogDir.exists()) {
            val targetDir = File(snapshotDir, "battle_logs")
            battleLogDir.copyRecursively(targetDir, overwrite = true)
        }
        
        val eventDir = File(context.filesDir, "events/$slot")
        if (eventDir.exists()) {
            val targetDir = File(snapshotDir, "events")
            eventDir.copyRecursively(targetDir, overwrite = true)
        }
    }

    private suspend fun createDiffIndex(slot: Int, baseSnapshotId: String, newSnapshotId: String) {
        val baseDir = getSnapshotDir(slot, baseSnapshotId)
        val newDir = getSnapshotDir(slot, newSnapshotId)
        
        if (!baseDir.exists()) return
        
        val diffEntries = mutableListOf<DiffEntry>()
        
        val baseFiles = collectFiles(baseDir)
        val newFiles = collectFiles(newDir)
        
        val baseFileMap = baseFiles.associateBy { it.relativeTo(baseDir).path }
        val newFileMap = newFiles.associateBy { it.relativeTo(newDir).path }
        
        newFileMap.forEach { (relativePath, newFile) ->
            val baseFile = baseFileMap[relativePath]
            val newHash = calculateFileHash(newFile)
            
            if (baseFile == null) {
                diffEntries.add(DiffEntry(
                    path = relativePath,
                    operation = DiffOperation.CREATE,
                    oldHash = null,
                    newHash = newHash,
                    size = newFile.length()
                ))
            } else {
                val baseHash = calculateFileHash(baseFile)
                if (baseHash != newHash) {
                    diffEntries.add(DiffEntry(
                        path = relativePath,
                        operation = DiffOperation.MODIFY,
                        oldHash = baseHash,
                        newHash = newHash,
                        size = newFile.length()
                    ))
                }
            }
        }
        
        baseFileMap.forEach { (relativePath, baseFile) ->
            if (!newFileMap.containsKey(relativePath)) {
                diffEntries.add(DiffEntry(
                    path = relativePath,
                    operation = DiffOperation.DELETE,
                    oldHash = calculateFileHash(baseFile),
                    newHash = null,
                    size = 0
                ))
            }
        }
        
        val diffFile = File(newDir, "diff_index.json")
        diffFile.writeText(serializeDiffEntries(diffEntries))
    }

    private fun collectFiles(dir: File): List<File> {
        if (!dir.exists()) return emptyList()
        return dir.walkTopDown()
            .filter { it.isFile }
            .filter { !it.name.endsWith(".tmp") && it.name != "diff_index.json" && !it.name.endsWith(".gz") }
            .toList()
    }

    private fun calculateFileHash(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { fis ->
            val buffer = ByteArray(8192)
            var read: Int
            while (fis.read(buffer).also { read = it } > 0) {
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun calculateChecksum(snapshotDir: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        
        snapshotDir.walkTopDown()
            .filter { it.isFile }
            .sortedBy { it.path }
            .forEach { file ->
                file.inputStream().use { fis ->
                    val buffer = ByteArray(8192)
                    var read: Int
                    while (fis.read(buffer).also { read = it } > 0) {
                        digest.update(buffer, 0, read)
                    }
                }
            }
        
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private suspend fun compressSnapshot(snapshotDir: File) {
        val files = snapshotDir.listFiles()?.filter { it.isFile } ?: return
        
        files.forEach { file ->
            if (!file.name.endsWith(".gz") && file.length() > 1024) {
                val compressedFile = File(file.parent, "${file.name}.gz")
                GZIPOutputStream(FileOutputStream(compressedFile)).use { gos ->
                    FileInputStream(file).use { fis ->
                        fis.copyTo(gos)
                    }
                }
                file.delete()
            }
        }
    }

    private fun calculateSnapshotSize(snapshotDir: File): Long {
        return snapshotDir.walkTopDown()
            .filter { it.isFile }
            .map { it.length() }
            .sum()
    }

    private fun addSnapshotToManifest(slot: Int, metadata: SnapshotMetadata) {
        val manifest = manifests.getOrPut(slot) { SnapshotManifest(slot) }
        manifest.snapshots.add(metadata)
        
        when (metadata.type) {
            SnapshotType.FULL -> manifest.lastFullSnapshotId = metadata.id
            SnapshotType.INCREMENTAL, SnapshotType.DIFF -> manifest.lastIncrementalSnapshotId = metadata.id
            SnapshotType.AUTO -> {}
        }
        
        manifest.totalSize = manifest.snapshots.sumOf { it.size }
        
        saveManifest(slot)
    }

    private fun cleanupOldSnapshots(slot: Int) {
        val manifest = manifests[slot] ?: return
        
        if (manifest.snapshots.size > SnapshotConfig.MAX_SNAPSHOTS_PER_SLOT) {
            val toRemove = manifest.snapshots
                .sortedBy { it.createdAt }
                .take(manifest.snapshots.size - SnapshotConfig.MAX_SNAPSHOTS_PER_SLOT)
            
            toRemove.forEach { snapshot ->
                deleteSnapshotFiles(slot, snapshot.id)
                manifest.snapshots.remove(snapshot)
            }
            
            manifest.totalSize = manifest.snapshots.sumOf { it.size }
            saveManifest(slot)
        }
    }

    private fun deleteSnapshotFiles(slot: Int, snapshotId: String) {
        val snapshotDir = getSnapshotDir(slot, snapshotId)
        if (snapshotDir.exists()) {
            snapshotDir.deleteRecursively()
        }
    }

    suspend fun restoreFromSnapshot(slot: Int, snapshotId: String): Boolean {
        val lock = snapshotLocks.getOrPut(snapshotId) { Mutex() }
        
        return lock.withLock {
            _snapshotState.value = SnapshotState.Restoring(0f, snapshotId)
            
            try {
                val snapshotDir = getSnapshotDir(slot, snapshotId)
                if (!snapshotDir.exists()) {
                    Log.e(TAG, "Snapshot directory not found: $snapshotId")
                    _snapshotState.value = SnapshotState.Error("Snapshot not found")
                    return false
                }
                
                val metadata = getSnapshotMetadata(slot, snapshotId)
                if (metadata == null) {
                    Log.e(TAG, "Snapshot metadata not found: $snapshotId")
                    _snapshotState.value = SnapshotState.Error("Snapshot metadata not found")
                    return false
                }
                
                if (metadata.type == SnapshotType.INCREMENTAL && metadata.parentSnapshotId != null) {
                    _snapshotState.value = SnapshotState.Restoring(0.1f, snapshotId)
                    restoreFromSnapshot(slot, metadata.parentSnapshotId)
                }
                
                _snapshotState.value = SnapshotState.Restoring(0.2f, snapshotId)
                val checksum = calculateChecksum(snapshotDir)
                if (checksum != metadata.checksum) {
                    Log.e(TAG, "Snapshot checksum mismatch for $snapshotId")
                    _snapshotState.value = SnapshotState.Error("Snapshot corrupted")
                    return false
                }
                
                _snapshotState.value = SnapshotState.Restoring(0.3f, snapshotId)
                decompressSnapshot(snapshotDir)
                
                _snapshotState.value = SnapshotState.Restoring(0.4f, snapshotId)
                restoreDatabaseFiles(slot, snapshotDir)
                
                _snapshotState.value = SnapshotState.Restoring(0.6f, snapshotId)
                restoreChunkedFiles(slot, snapshotDir)
                
                _snapshotState.value = SnapshotState.Restoring(0.8f, snapshotId)
                if (metadata.type == SnapshotType.DIFF) {
                    applyDiff(slot, snapshotDir)
                }
                
                _snapshotState.value = SnapshotState.Idle
                Log.i(TAG, "Successfully restored snapshot $snapshotId")
                true
            } catch (e: Exception) {
                Log.e(TAG, "Failed to restore snapshot $snapshotId", e)
                _snapshotState.value = SnapshotState.Error(e.message ?: "Unknown error")
                false
            }
        }
    }

    private suspend fun decompressSnapshot(snapshotDir: File) {
        snapshotDir.walkTopDown()
            .filter { it.isFile && it.name.endsWith(".gz") }
            .forEach { gzFile ->
                val originalFile = File(gzFile.parent, gzFile.nameWithoutExtension)
                GZIPInputStream(FileInputStream(gzFile)).use { gis ->
                    FileOutputStream(originalFile).use { fos ->
                        gis.copyTo(fos)
                    }
                }
                gzFile.delete()
            }
    }

    private suspend fun restoreDatabaseFiles(slot: Int, snapshotDir: File) {
        val dbFile = OptimizedGameDatabase.getDatabaseFile(context, slot)
        val walFile = OptimizedGameDatabase.getWalFile(context, slot)
        val shmFile = OptimizedGameDatabase.getShmFile(context, slot)
        
        val snapshotDbFile = File(snapshotDir, dbFile.name)
        val snapshotWalFile = File(snapshotDir, walFile.name)
        val snapshotShmFile = File(snapshotDir, shmFile.name)
        
        if (snapshotDbFile.exists()) {
            snapshotDbFile.copyTo(dbFile, overwrite = true)
        }
        if (snapshotWalFile.exists()) {
            snapshotWalFile.copyTo(walFile, overwrite = true)
        }
        if (snapshotShmFile.exists()) {
            snapshotShmFile.copyTo(shmFile, overwrite = true)
        }
    }

    private suspend fun restoreChunkedFiles(slot: Int, snapshotDir: File) {
        val chunkedDir = File(snapshotDir, "chunked")
        if (chunkedDir.exists()) {
            val targetDir = File(context.filesDir, "chunked/$slot")
            targetDir.deleteRecursively()
            chunkedDir.copyRecursively(targetDir, overwrite = true)
        }
        
        val battleLogDir = File(snapshotDir, "battle_logs")
        if (battleLogDir.exists()) {
            val targetDir = File(context.filesDir, "battle_logs/$slot")
            targetDir.deleteRecursively()
            battleLogDir.copyRecursively(targetDir, overwrite = true)
        }
        
        val eventDir = File(snapshotDir, "events")
        if (eventDir.exists()) {
            val targetDir = File(context.filesDir, "events/$slot")
            targetDir.deleteRecursively()
            eventDir.copyRecursively(targetDir, overwrite = true)
        }
    }

    private suspend fun applyDiff(slot: Int, snapshotDir: File) {
        val diffFile = File(snapshotDir, "diff_index.json")
        if (!diffFile.exists()) return
        
        val diffEntries = parseDiffEntries(diffFile.readText())
        
        diffEntries.forEach { entry ->
            when (entry.operation) {
                DiffOperation.CREATE, DiffOperation.MODIFY -> {
                    val sourceFile = File(snapshotDir, entry.path)
                    val targetFile = File(context.filesDir, entry.path)
                    if (sourceFile.exists()) {
                        targetFile.parentFile?.mkdirs()
                        sourceFile.copyTo(targetFile, overwrite = true)
                    }
                }
                DiffOperation.DELETE -> {
                    val targetFile = File(context.filesDir, entry.path)
                    targetFile.delete()
                }
            }
        }
    }

    private fun parseDiffEntries(json: String): List<DiffEntry> {
        val entries = mutableListOf<DiffEntry>()
        json.lines().filter { it.isNotBlank() }.forEach { line ->
            val parts = line.split(",")
            if (parts.size >= 5) {
                entries.add(DiffEntry(
                    path = parts[0],
                    operation = DiffOperation.valueOf(parts[1]),
                    oldHash = parts[2].takeIf { it.isNotEmpty() },
                    newHash = parts[3].takeIf { it.isNotEmpty() },
                    size = parts[4].toLongOrNull() ?: 0
                ))
            }
        }
        return entries
    }

    private fun serializeDiffEntries(entries: List<DiffEntry>): String {
        return entries.joinToString("\n") { entry ->
            "${entry.path},${entry.operation},${entry.oldHash ?: ""},${entry.newHash ?: ""},${entry.size}"
        }
    }

    fun listSnapshots(slot: Int): List<SnapshotMetadata> {
        val manifest = manifests[slot] ?: return emptyList()
        return manifest.snapshots.sortedByDescending { it.createdAt }
    }

    fun getSnapshotMetadata(slot: Int, snapshotId: String): SnapshotMetadata? {
        val manifest = manifests[slot] ?: return null
        return manifest.snapshots.find { it.id == snapshotId }
    }

    fun deleteSnapshot(slot: Int, snapshotId: String): Boolean {
        val manifest = manifests[slot] ?: return false
        val snapshot = manifest.snapshots.find { it.id == snapshotId } ?: return false
        
        deleteSnapshotFiles(slot, snapshotId)
        manifest.snapshots.remove(snapshot)
        manifest.totalSize = manifest.snapshots.sumOf { it.size }
        
        if (manifest.lastFullSnapshotId == snapshotId) {
            manifest.lastFullSnapshotId = manifest.snapshots
                .filter { it.type == SnapshotType.FULL }
                .maxByOrNull { it.createdAt }?.id
        }
        
        if (manifest.lastIncrementalSnapshotId == snapshotId) {
            manifest.lastIncrementalSnapshotId = manifest.snapshots
                .filter { it.type == SnapshotType.INCREMENTAL || it.type == SnapshotType.DIFF }
                .maxByOrNull { it.createdAt }?.id
        }
        
        saveManifest(slot)
        return true
    }

    fun deleteSlotSnapshots(slot: Int) {
        val snapshotDir = File(context.filesDir, "${SnapshotConfig.SNAPSHOT_DIR}/$slot")
        if (snapshotDir.exists()) {
            snapshotDir.deleteRecursively()
        }
        manifests.remove(slot)
        getManifestFile(slot).delete()
    }

    fun getSnapshotStats(slot: Int): SnapshotStats {
        val manifest = manifests[slot] ?: return SnapshotStats()
        return SnapshotStats(
            totalCount = manifest.snapshots.size,
            fullCount = manifest.snapshots.count { it.type == SnapshotType.FULL },
            incrementalCount = manifest.snapshots.count { it.type == SnapshotType.INCREMENTAL },
            diffCount = manifest.snapshots.count { it.type == SnapshotType.DIFF },
            totalSize = manifest.totalSize,
            oldestTimestamp = manifest.snapshots.minByOrNull { it.createdAt }?.createdAt ?: 0,
            newestTimestamp = manifest.snapshots.maxByOrNull { it.createdAt }?.createdAt ?: 0
        )
    }

    fun hasEmergencySnapshot(slot: Int): Boolean {
        val manifest = manifests[slot] ?: return false
        return manifest.snapshots.any { it.isEmergency }
    }

    fun getLatestEmergencySnapshotId(slot: Int): String? {
        val manifest = manifests[slot] ?: return null
        return manifest.snapshots
            .filter { it.isEmergency }
            .maxByOrNull { it.createdAt }
            ?.id
    }

    fun clearEmergencySnapshots(slot: Int) {
        val manifest = manifests[slot] ?: return
        val emergencySnapshots = manifest.snapshots.filter { it.isEmergency }
        
        emergencySnapshots.forEach { snapshot ->
            try {
                deleteSnapshotFiles(slot, snapshot.id)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to delete emergency snapshot file: ${snapshot.id}", e)
            }
        }
        
        manifest.snapshots.removeAll { it.isEmergency }
        saveManifest(slot)
        
        Log.i(TAG, "Cleared ${emergencySnapshots.size} emergency snapshots for slot $slot")
    }

    data class SnapshotStats(
        val totalCount: Int = 0,
        val fullCount: Int = 0,
        val incrementalCount: Int = 0,
        val diffCount: Int = 0,
        val totalSize: Long = 0,
        val oldestTimestamp: Long = 0,
        val newestTimestamp: Long = 0
    )

    fun shutdown() {
        isShuttingDown = true
        autoSnapshotJob?.cancel()
        
        manifests.keys.forEach { saveManifest(it) }
        
        Log.i(TAG, "SnapshotVersionManager shutdown completed")
    }
}

private object OptimizedGameDatabase {
    fun getDatabaseFile(context: Context, slot: Int): File {
        return context.getDatabasePath("game_slot_$slot.db")
    }
    
    fun getWalFile(context: Context, slot: Int): File {
        return File(getDatabaseFile(context, slot).path + "-wal")
    }
    
    fun getShmFile(context: Context, slot: Int): File {
        return File(getDatabaseFile(context, slot).path + "-shm")
    }
}
