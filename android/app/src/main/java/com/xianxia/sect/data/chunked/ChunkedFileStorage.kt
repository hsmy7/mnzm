package com.xianxia.sect.data.chunked

import android.content.Context
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.*
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

class ChunkedFileStorage(
    private val context: Context,
    private val compressor: Compressor = LZ4Compressor(),
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
) {
    companion object {
        private const val TAG = "ChunkedFileStorage"
        private const val HEADER_MAGIC = "XIANXIA"
        private const val FORMAT_VERSION = 1
        private const val MANIFEST_FILE = "manifest.dat"
        private const val CHUNKS_DIR = "chunks"
        private const val BACKUP_EXTENSION = ".bak"
        private const val TEMP_EXTENSION = ".tmp"
        private const val MAX_BACKUP_VERSIONS = 3
    }

    private val manifestLock = ReentrantReadWriteLock()
    private val chunkLocks = ConcurrentHashMap<String, Mutex>()
    private val manifestCache = ConcurrentHashMap<Int, SaveManifest>()

    data class Chunk(
        val id: String,
        val type: ChunkType,
        val data: ByteArray,
        val checksum: ByteArray
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Chunk) return false
            return id == other.id && type == other.type && data.contentEquals(other.data)
        }

        override fun hashCode(): Int {
            var result = id.hashCode()
            result = 31 * result + type.hashCode()
            result = 31 * result + data.contentHashCode()
            return result
        }
    }

    enum class ChunkType(val code: Int) {
        CORE(1),
        DISCIPLE(2),
        ITEM(3),
        WORLD(4),
        LOG(5);

        companion object {
            fun fromCode(code: Int): ChunkType = values().find { it.code == code } ?: CORE
        }
    }

    data class SaveManifest(
        val version: String = "",
        val timestamp: Long = System.currentTimeMillis(),
        val slot: Int = 1,
        val sectName: String = "",
        val gameYear: Int = 1,
        val gameMonth: Int = 1,
        val spiritStones: Long = 0,
        val discipleCount: Int = 0,
        val chunks: Map<String, ChunkInfo> = emptyMap(),
        val formatVersion: Int = FORMAT_VERSION,
        val merkleRoot: ByteArray = ByteArray(0)
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is SaveManifest) return false
            return slot == other.slot && merkleRoot.contentEquals(other.merkleRoot)
        }

        override fun hashCode(): Int {
            var result = slot
            result = 31 * result + merkleRoot.contentHashCode()
            return result
        }
    }

    data class ChunkInfo(
        val path: String,
        val offset: Long = 0,
        val size: Long,
        val checksum: ByteArray,
        val formatVersion: Int = FORMAT_VERSION,
        val modifiedTime: Long = System.currentTimeMillis(),
        val type: ChunkType = ChunkType.CORE
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is ChunkInfo) return false
            return path == other.path && checksum.contentEquals(other.checksum)
        }

        override fun hashCode(): Int {
            var result = path.hashCode()
            result = 31 * result + checksum.contentHashCode()
            return result
        }
    }

    fun getSaveDir(slot: Int): File {
        val dir = File(context.filesDir, "saves_v2/slot_$slot")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    fun getChunksDir(slot: Int): File {
        val dir = File(getSaveDir(slot), CHUNKS_DIR)
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    fun getManifestFile(slot: Int): File {
        return File(getSaveDir(slot), MANIFEST_FILE)
    }

    fun getChunkFile(slot: Int, chunkId: String): File {
        return File(getChunksDir(slot), "$chunkId.dat")
    }

    private fun getChunkMutex(chunkId: String): Mutex {
        return chunkLocks.getOrPut(chunkId) { Mutex() }
    }

    suspend fun writeChunk(
        slot: Int,
        chunk: Chunk
    ): WriteResult {
        val mutex = getChunkMutex(chunk.id)
        return mutex.withLock {
            withContext(Dispatchers.IO) {
                writeChunkInternal(slot, chunk)
            }
        }
    }

    private fun writeChunkInternal(slot: Int, chunk: Chunk): WriteResult {
        val chunkFile = getChunkFile(slot, chunk.id)
        val tempFile = File(chunkFile.parent, "${chunkFile.name}$TEMP_EXTENSION")

        try {
            val compressed = compressor.compress(chunk.data)
            val checksum = calculateChecksum(chunk.data)

            FileOutputStream(tempFile).use { output ->
                output.write(HEADER_MAGIC.toByteArray())
                output.write(FORMAT_VERSION)
                output.write(chunk.type.code)
                output.writeInt(compressed.size)
                output.write(compressed)
                output.write(checksum)
            }

            rotateBackup(chunkFile)

            if (chunkFile.exists()) {
                if (!chunkFile.delete()) {
                    Log.w(TAG, "Failed to delete existing chunk file: ${chunk.id}")
                }
            }

            if (!tempFile.renameTo(chunkFile)) {
                Log.e(TAG, "Failed to rename temp file for chunk: ${chunk.id}")
                restoreFromBackup(chunkFile)
                return WriteResult.Error("Failed to rename temp file")
            }

            updateManifest(slot, chunk.id, ChunkInfo(
                path = chunkFile.name,
                size = chunkFile.length(),
                checksum = checksum,
                type = chunk.type
            ))

            Log.d(TAG, "Chunk written: ${chunk.id}, size=${chunk.data.size}, compressed=${compressed.size}")
            return WriteResult.Success(chunk.id)

        } catch (e: Exception) {
            Log.e(TAG, "Failed to write chunk: ${chunk.id}", e)
            tempFile.delete()
            return WriteResult.Error(e.message ?: "Unknown error")
        }
    }

    suspend fun readChunk(
        slot: Int,
        chunkId: String
    ): Chunk? {
        val mutex = getChunkMutex(chunkId)
        return mutex.withLock {
            withContext(Dispatchers.IO) {
                readChunkInternal(slot, chunkId)
            }
        }
    }

    private fun readChunkInternal(slot: Int, chunkId: String): Chunk? {
        val chunkFile = getChunkFile(slot, chunkId)

        if (!chunkFile.exists()) {
            val backupFile = File(chunkFile.parent, "${chunkFile.name}$BACKUP_EXTENSION.1")
            if (backupFile.exists()) {
                Log.w(TAG, "Chunk file not found, trying backup: $chunkId")
                backupFile.copyTo(chunkFile)
            } else {
                return null
            }
        }

        return try {
            FileInputStream(chunkFile).use { input ->
                val magic = String(input.readNBytes(HEADER_MAGIC.length))
                if (magic != HEADER_MAGIC) {
                    Log.e(TAG, "Invalid chunk file magic: $chunkId")
                    return null
                }

                val formatVersion = input.read()
                val typeCode = input.read()
                val chunkType = ChunkType.fromCode(typeCode)
                val compressedSize = input.readInt()
                val compressed = input.readNBytes(compressedSize)
                val storedChecksum = input.readNBytes(32)

                val data = compressor.decompress(compressed)
                val calculatedChecksum = calculateChecksum(data)

                if (!calculatedChecksum.contentEquals(storedChecksum)) {
                    Log.e(TAG, "Chunk checksum mismatch: $chunkId")
                    return tryRecoverFromBackup(slot, chunkId, chunkType)
                }

                Chunk(chunkId, chunkType, data, storedChecksum)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read chunk: $chunkId", e)
            tryRecoverFromBackup(slot, chunkId, ChunkType.CORE)
        }
    }

    private fun tryRecoverFromBackup(slot: Int, chunkId: String, type: ChunkType): Chunk? {
        val chunkFile = getChunkFile(slot, chunkId)

        for (version in 1..MAX_BACKUP_VERSIONS) {
            val backupFile = File(chunkFile.parent, "${chunkFile.name}$BACKUP_EXTENSION.$version")
            if (backupFile.exists()) {
                try {
                    val result = FileInputStream(backupFile).use { input ->
                        val magic = String(input.readNBytes(HEADER_MAGIC.length))
                        if (magic != HEADER_MAGIC) return@use null

                        input.read()
                        input.read()
                        val compressedSize = input.readInt()
                        val compressed = input.readNBytes(compressedSize)
                        val storedChecksum = input.readNBytes(32)

                        val data = compressor.decompress(compressed)
                        val calculatedChecksum = calculateChecksum(data)

                        if (calculatedChecksum.contentEquals(storedChecksum)) {
                            Log.i(TAG, "Recovered chunk from backup version $version: $chunkId")
                            Chunk(chunkId, type, data, storedChecksum)
                        } else {
                            null
                        }
                    }
                    if (result != null) return result
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to read backup version $version: $chunkId", e)
                }
            }
        }

        return null
    }

    private fun rotateBackup(chunkFile: File) {
        if (!chunkFile.exists()) return

        val oldestBackup = File(chunkFile.parent, "${chunkFile.name}$BACKUP_EXTENSION.$MAX_BACKUP_VERSIONS")
        if (oldestBackup.exists()) {
            oldestBackup.delete()
        }

        for (version in MAX_BACKUP_VERSIONS - 1 downTo 1) {
            val currentBackup = File(chunkFile.parent, "${chunkFile.name}$BACKUP_EXTENSION.$version")
            if (currentBackup.exists()) {
                val nextBackup = File(chunkFile.parent, "${chunkFile.name}$BACKUP_EXTENSION.${version + 1}")
                currentBackup.renameTo(nextBackup)
            }
        }

        val firstBackup = File(chunkFile.parent, "${chunkFile.name}$BACKUP_EXTENSION.1")
        chunkFile.copyTo(firstBackup)
    }

    private fun restoreFromBackup(chunkFile: File): Boolean {
        for (version in 1..MAX_BACKUP_VERSIONS) {
            val backupFile = File(chunkFile.parent, "${chunkFile.name}$BACKUP_EXTENSION.$version")
            if (backupFile.exists()) {
                backupFile.copyTo(chunkFile, overwrite = true)
                Log.i(TAG, "Restored from backup version $version: ${chunkFile.name}")
                return true
            }
        }
        return false
    }

    fun readManifest(slot: Int): SaveManifest {
        return manifestLock.read {
            manifestCache[slot] ?: run {
                val manifestFile = getManifestFile(slot)
                if (!manifestFile.exists()) {
                    SaveManifest(slot = slot)
                } else {
                    try {
                        readManifestFromFile(manifestFile)
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to read manifest for slot $slot", e)
                        SaveManifest(slot = slot)
                    }
                }.also { manifestCache[slot] = it }
            }
        }
    }

    private fun readManifestFromFile(file: File): SaveManifest {
        FileInputStream(file).use { input ->
            val magic = String(input.readNBytes(HEADER_MAGIC.length))
            if (magic != HEADER_MAGIC) {
                throw IOException("Invalid manifest file")
            }

            val formatVersion = input.read()
            val version = readString(input)
            val timestamp = input.readLong()
            val slot = input.readInt()
            val sectName = readString(input)
            val gameYear = input.readInt()
            val gameMonth = input.readInt()
            val spiritStones = input.readLong()
            val discipleCount = input.readInt()

            val chunkCount = input.readInt()
            val chunks = mutableMapOf<String, ChunkInfo>()
            repeat(chunkCount) {
                val chunkId = readString(input)
                val path = readString(input)
                val size = input.readLong()
                val checksum = input.readNBytes(32)
                val modifiedTime = input.readLong()
                val typeCode = input.read()
                chunks[chunkId] = ChunkInfo(path, 0, size, checksum, formatVersion, modifiedTime, ChunkType.fromCode(typeCode))
            }

            val merkleRoot = input.readNBytes(32)

            return SaveManifest(
                version, timestamp, slot, sectName, gameYear, gameMonth,
                spiritStones, discipleCount, chunks, formatVersion, merkleRoot
            )
        }
    }

    private fun writeManifest(slot: Int, manifest: SaveManifest) {
        manifestLock.write {
            val manifestFile = getManifestFile(slot)
            val tempFile = File(manifestFile.parent, "${manifestFile.name}$TEMP_EXTENSION")

            try {
                FileOutputStream(tempFile).use { output ->
                    output.write(HEADER_MAGIC.toByteArray())
                    output.write(FORMAT_VERSION)
                    writeString(output, manifest.version)
                    output.writeLong(manifest.timestamp)
                    output.writeInt(manifest.slot)
                    writeString(output, manifest.sectName)
                    output.writeInt(manifest.gameYear)
                    output.writeInt(manifest.gameMonth)
                    output.writeLong(manifest.spiritStones)
                    output.writeInt(manifest.discipleCount)

                    output.writeInt(manifest.chunks.size)
                    manifest.chunks.forEach { (id, info) ->
                        writeString(output, id)
                        writeString(output, info.path)
                        output.writeLong(info.size)
                        output.write(info.checksum)
                        output.writeLong(info.modifiedTime)
                        output.write(info.type.code)
                    }

                    output.write(manifest.merkleRoot)
                }

                if (manifestFile.exists()) {
                    manifestFile.delete()
                }
                tempFile.renameTo(manifestFile)

                manifestCache[slot] = manifest

            } catch (e: Exception) {
                Log.e(TAG, "Failed to write manifest for slot $slot", e)
                tempFile.delete()
                throw e
            }
        }
    }

    private fun updateManifest(slot: Int, chunkId: String, chunkInfo: ChunkInfo) {
        val manifest = readManifest(slot)
        val updatedChunks = manifest.chunks + (chunkId to chunkInfo)
        val updatedManifest = manifest.copy(
            chunks = updatedChunks,
            timestamp = System.currentTimeMillis()
        )
        writeManifest(slot, updatedManifest)
    }

    fun updateManifestMetadata(
        slot: Int,
        version: String,
        sectName: String,
        gameYear: Int,
        gameMonth: Int,
        spiritStones: Long,
        discipleCount: Int
    ) {
        val manifest = readManifest(slot)
        val updatedManifest = manifest.copy(
            version = version,
            sectName = sectName,
            gameYear = gameYear,
            gameMonth = gameMonth,
            spiritStones = spiritStones,
            discipleCount = discipleCount,
            timestamp = System.currentTimeMillis()
        )
        writeManifest(slot, updatedManifest)
    }

    suspend fun writeChunksBatch(
        slot: Int,
        chunks: List<Chunk>
    ): BatchWriteResult {
        return withContext(Dispatchers.IO) {
            val results = mutableMapOf<String, WriteResult>()
            var successCount = 0

            for (chunk in chunks) {
                val result = writeChunk(slot, chunk)
                results[chunk.id] = result
                if (result is WriteResult.Success) {
                    successCount++
                }
            }

            Log.i(TAG, "Batch write completed: $successCount/${chunks.size} chunks")
            BatchWriteResult(results, successCount, chunks.size - successCount)
        }
    }

    suspend fun readChunksBatch(
        slot: Int,
        chunkIds: List<String>
    ): Map<String, Chunk> {
        return withContext(Dispatchers.IO) {
            chunkIds.mapNotNull { id ->
                readChunk(slot, id)?.let { id to it }
            }.toMap()
        }
    }

    fun deleteChunk(slot: Int, chunkId: String): Boolean {
        val chunkFile = getChunkFile(slot, chunkId)
        var deleted = false

        if (chunkFile.exists()) {
            deleted = chunkFile.delete()
        }

        for (version in 1..MAX_BACKUP_VERSIONS) {
            val backupFile = File(chunkFile.parent, "${chunkFile.name}$BACKUP_EXTENSION.$version")
            if (backupFile.exists()) {
                backupFile.delete()
            }
        }

        if (deleted) {
            val manifest = readManifest(slot)
            val updatedChunks = manifest.chunks - chunkId
            writeManifest(slot, manifest.copy(chunks = updatedChunks))
        }

        return deleted
    }

    suspend fun recoverChunk(slot: Int, chunkId: String): Boolean {
        return withContext(Dispatchers.IO) {
            val chunkFile = getChunkFile(slot, chunkId)
            
            for (version in 1..MAX_BACKUP_VERSIONS) {
                val backupFile = File(chunkFile.parent, "${chunkFile.name}$BACKUP_EXTENSION.$version")
                if (backupFile.exists()) {
                    try {
                        backupFile.copyTo(chunkFile, overwrite = true)
                        Log.i(TAG, "Recovered chunk $chunkId from backup version $version")
                        return@withContext true
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to recover from backup version $version", e)
                    }
                }
            }
            false
        }
    }

    fun cleanupStaleLocks(maxAgeMs: Long = 3600_000) {
        val now = System.currentTimeMillis()
        val iterator = chunkLocks.entries.iterator()
        var cleaned = 0
        
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (entry.value.isLocked) continue
            
            iterator.remove()
            cleaned++
        }
        
        if (cleaned > 0) {
            Log.d(TAG, "Cleaned up $cleaned stale lock entries")
        }
    }

    fun deleteSlot(slot: Int): Boolean {
        val saveDir = getSaveDir(slot)
        if (!saveDir.exists()) return true

        return try {
            saveDir.deleteRecursively()
            manifestCache.remove(slot)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to delete slot $slot", e)
            false
        }
    }

    fun hasSlot(slot: Int): Boolean {
        return getManifestFile(slot).exists()
    }

    fun getChunkIds(slot: Int): Set<String> {
        return readManifest(slot).chunks.keys
    }

    fun getChunkInfo(slot: Int, chunkId: String): ChunkInfo? {
        return readManifest(slot).chunks[chunkId]
    }

    fun calculateMerkleRoot(slot: Int): ByteArray {
        val manifest = readManifest(slot)
        val digest = MessageDigest.getInstance("SHA-256")

        manifest.chunks.entries.sortedBy { it.key }.forEach { (_, info) ->
            digest.update(info.checksum)
        }

        return digest.digest()
    }

    fun verifyIntegrity(slot: Int): IntegrityResult {
        val manifest = readManifest(slot)
        val errors = mutableListOf<String>()

        manifest.chunks.forEach { (chunkId, info) ->
            val chunkFile = getChunkFile(slot, chunkId)
            if (!chunkFile.exists()) {
                errors.add("Missing chunk file: $chunkId")
                return@forEach
            }

            if (chunkFile.length() != info.size) {
                errors.add("Size mismatch for chunk: $chunkId")
            }
        }

        val calculatedMerkleRoot = calculateMerkleRoot(slot)
        if (!calculatedMerkleRoot.contentEquals(manifest.merkleRoot) && manifest.merkleRoot.isNotEmpty()) {
            errors.add("Merkle root mismatch")
        }

        return if (errors.isEmpty()) {
            IntegrityResult.Valid
        } else {
            IntegrityResult.Invalid(errors)
        }
    }

    fun getStorageStats(slot: Int): StorageStats {
        val manifest = readManifest(slot)
        val chunksDir = getChunksDir(slot)

        var totalSize = 0L
        var chunkCount = 0

        chunksDir.listFiles()?.forEach { file ->
            if (file.extension == "dat") {
                totalSize += file.length()
                chunkCount++
            }
        }

        return StorageStats(
            totalSize = totalSize,
            chunkCount = chunkCount,
            manifestChunkCount = manifest.chunks.size,
            oldestChunk = manifest.chunks.values.minByOrNull { it.modifiedTime }?.modifiedTime ?: 0,
            newestChunk = manifest.chunks.values.maxByOrNull { it.modifiedTime }?.modifiedTime ?: 0
        )
    }

    private fun calculateChecksum(data: ByteArray): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(data)
    }

    private fun readString(input: InputStream): String {
        val length = input.readInt()
        val bytes = input.readNBytes(length)
        return String(bytes, Charsets.UTF_8)
    }

    private fun writeString(output: OutputStream, value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        output.writeInt(bytes.size)
        output.write(bytes)
    }

    fun shutdown() {
        scope.cancel()
        manifestCache.clear()
        chunkLocks.clear()
        Log.i(TAG, "ChunkedFileStorage shutdown completed")
    }
}

sealed class WriteResult {
    data class Success(val chunkId: String) : WriteResult()
    data class Error(val message: String) : WriteResult()
}

data class BatchWriteResult(
    val results: Map<String, WriteResult>,
    val successCount: Int,
    val failureCount: Int
) {
    val isCompleteSuccess: Boolean get() = failureCount == 0
    val isCompleteFailure: Boolean get() = successCount == 0
}

sealed class IntegrityResult {
    object Valid : IntegrityResult()
    data class Invalid(val errors: List<String>) : IntegrityResult()
}

data class StorageStats(
    val totalSize: Long,
    val chunkCount: Int,
    val manifestChunkCount: Int,
    val oldestChunk: Long,
    val newestChunk: Long
)

private fun InputStream.readInt(): Int {
    return (read() shl 24) or (read() shl 16) or (read() shl 8) or read()
}

private fun OutputStream.writeInt(value: Int) {
    write(value ushr 24)
    write(value ushr 16)
    write(value ushr 8)
    write(value)
}

private fun InputStream.readLong(): Long {
    return (read().toLong() shl 56) or
           (read().toLong() shl 48) or
           (read().toLong() shl 40) or
           (read().toLong() shl 32) or
           (read().toLong() shl 24) or
           (read().toLong() shl 16) or
           (read().toLong() shl 8) or
           read().toLong()
}

private fun OutputStream.writeLong(value: Long) {
    write((value ushr 56).toInt())
    write((value ushr 48).toInt())
    write((value ushr 40).toInt())
    write((value ushr 32).toInt())
    write((value ushr 24).toInt())
    write((value ushr 16).toInt())
    write((value ushr 8).toInt())
    write(value.toInt())
}
