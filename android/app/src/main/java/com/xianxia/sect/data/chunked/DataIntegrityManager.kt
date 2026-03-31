package com.xianxia.sect.data.chunked

import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

class DataIntegrityManager(
    private val storage: ChunkedFileStorage,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
) {
    companion object {
        private const val TAG = "DataIntegrityManager"
        private const val MERKLE_TREE_BRANCHES = 16
        private const val CHECKSUM_ALGORITHM = "SHA-256"
    }

    private val checksumCache = ConcurrentHashMap<String, ByteArray>()
    private val verificationLock = Mutex()

    data class ChecksumInfo(
        val chunkChecksum: ByteArray,
        val recordChecksums: Map<String, ByteArray>,
        val merkleRoot: ByteArray
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is ChecksumInfo) return false
            return chunkChecksum.contentEquals(other.chunkChecksum) &&
                   merkleRoot.contentEquals(other.merkleRoot)
        }

        override fun hashCode(): Int {
            var result = chunkChecksum.contentHashCode()
            result = 31 * result + merkleRoot.contentHashCode()
            return result
        }
    }

    data class VerificationResult(
        val isValid: Boolean,
        val corruptedChunks: List<String>,
        val repairedChunks: List<String>,
        val errors: List<String>
    )

    data class SnapshotInfo(
        val id: String,
        val timestamp: Long,
        val type: SnapshotType,
        val size: Long,
        val checksum: ByteArray,
        val parentSnapshotId: String?,
        val chunkIds: List<String>
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is SnapshotInfo) return false
            return id == other.id && checksum.contentEquals(other.checksum)
        }

        override fun hashCode(): Int = 31 * id.hashCode() + checksum.contentHashCode()
    }

    enum class SnapshotType {
        FULL, INCREMENTAL, DIFF
    }

    fun calculateChecksum(data: ByteArray): ByteArray {
        val digest = MessageDigest.getInstance(CHECKSUM_ALGORITHM)
        return digest.digest(data)
    }

    fun calculateChecksumWithRecords(data: ByteArray, recordBoundaries: List<Int>): ChecksumInfo {
        val digest = MessageDigest.getInstance(CHECKSUM_ALGORITHM)
        val chunkChecksum = digest.digest(data)

        val recordChecksums = mutableMapOf<String, ByteArray>()
        var offset = 0
        recordBoundaries.forEachIndexed { index, end ->
            val recordData = data.copyOfRange(offset, end)
            recordChecksums["record_$index"] = digest.digest(recordData)
            offset = end
        }

        val merkleRoot = buildMerkleRoot(recordChecksums.values.toList())

        return ChecksumInfo(chunkChecksum, recordChecksums, merkleRoot)
    }

    fun buildMerkleRoot(checksums: List<ByteArray>): ByteArray {
        if (checksums.isEmpty()) {
            return ByteArray(32)
        }

        if (checksums.size == 1) {
            return checksums[0]
        }

        val digest = MessageDigest.getInstance(CHECKSUM_ALGORITHM)
        var currentLevel = checksums

        while (currentLevel.size > 1) {
            val nextLevel = mutableListOf<ByteArray>()
            for (i in currentLevel.indices step 2) {
                val left = currentLevel[i]
                val right = if (i + 1 < currentLevel.size) currentLevel[i + 1] else ByteArray(32)
                
                digest.reset()
                digest.update(left)
                digest.update(right)
                nextLevel.add(digest.digest())
            }
            currentLevel = nextLevel
        }

        return currentLevel[0]
    }

    suspend fun verifyChunk(slot: Int, chunkId: String): Boolean {
        return verificationLock.withLock {
            val chunk = storage.readChunk(slot, chunkId)
            if (chunk == null) {
                Log.w(TAG, "Chunk not found for verification: $chunkId")
                return@withLock false
            }

            val calculatedChecksum = calculateChecksum(chunk.data)
            val storedChecksum = chunk.checksum

            val isValid = calculatedChecksum.contentEquals(storedChecksum)
            if (!isValid) {
                Log.w(TAG, "Checksum mismatch for chunk: $chunkId")
            }

            isValid
        }
    }

    suspend fun verifyAllChunks(slot: Int): VerificationResult {
        return verificationLock.withLock {
            val startTime = System.currentTimeMillis()
            val corruptedChunks = mutableListOf<String>()
            val repairedChunks = mutableListOf<String>()
            val errors = mutableListOf<String>()

            val chunkIds = storage.getChunkIds(slot)
            Log.i(TAG, "Starting verification of ${chunkIds.size} chunks for slot $slot")

            for (chunkId in chunkIds) {
                try {
                    val isValid = verifyChunkInternal(slot, chunkId)
                    if (!isValid) {
                        corruptedChunks.add(chunkId)

                        val repaired = attemptRepair(slot, chunkId)
                        if (repaired) {
                            repairedChunks.add(chunkId)
                            Log.i(TAG, "Repaired chunk: $chunkId")
                        } else {
                            errors.add("Failed to repair chunk: $chunkId")
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error verifying chunk: $chunkId", e)
                    errors.add("Verification error for $chunkId: ${e.message}")
                }
            }

            val elapsed = System.currentTimeMillis() - startTime
            Log.i(TAG, "Verification completed in ${elapsed}ms. " +
                    "Corrupted: ${corruptedChunks.size}, Repaired: ${repairedChunks.size}")

            VerificationResult(
                isValid = corruptedChunks.isEmpty() || corruptedChunks.size == repairedChunks.size,
                corruptedChunks = corruptedChunks,
                repairedChunks = repairedChunks,
                errors = errors
            )
        }
    }

    private suspend fun verifyChunkInternal(slot: Int, chunkId: String): Boolean {
        val chunk = storage.readChunk(slot, chunkId) ?: return false

        val calculatedChecksum = calculateChecksum(chunk.data)
        return calculatedChecksum.contentEquals(chunk.checksum)
    }

    private suspend fun attemptRepair(slot: Int, chunkId: String): Boolean {
        return try {
            val recovered = storage.recoverChunk(slot, chunkId)
            if (recovered) {
                val verifyAfterRecovery = verifyChunkInternal(slot, chunkId)
                if (verifyAfterRecovery) {
                    Log.i(TAG, "Successfully repaired and verified chunk: $chunkId")
                    true
                } else {
                    Log.w(TAG, "Chunk still corrupted after repair: $chunkId")
                    false
                }
            } else {
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to repair chunk: $chunkId", e)
            false
        }
    }

    suspend fun createSnapshot(
        slot: Int,
        type: SnapshotType = SnapshotType.FULL,
        parentSnapshotId: String? = null
    ): SnapshotInfo? {
        return try {
            val startTime = System.currentTimeMillis()
            val snapshotId = "snapshot_${System.currentTimeMillis()}"
            val snapshotDir = File(storage.getSaveDir(slot), "snapshots")
            if (!snapshotDir.exists()) snapshotDir.mkdirs()

            val chunkIds = when (type) {
                SnapshotType.FULL -> storage.getChunkIds(slot)
                SnapshotType.INCREMENTAL -> getChangedChunkIds(slot, parentSnapshotId)
                SnapshotType.DIFF -> getChangedChunkId(slot, parentSnapshotId)
            }

            var totalSize = 0L
            val digest = MessageDigest.getInstance(CHECKSUM_ALGORITHM)

            for (chunkId in chunkIds) {
                val chunk = storage.readChunk(slot, chunkId)
                if (chunk != null) {
                    digest.update(chunk.checksum)
                    totalSize += chunk.data.size
                }
            }

            val snapshotChecksum = digest.digest()

            val snapshotInfo = SnapshotInfo(
                id = snapshotId,
                timestamp = System.currentTimeMillis(),
                type = type,
                size = totalSize,
                checksum = snapshotChecksum,
                parentSnapshotId = parentSnapshotId,
                chunkIds = chunkIds.toList()
            )

            saveSnapshotInfo(slot, snapshotInfo)

            val elapsed = System.currentTimeMillis() - startTime
            Log.i(TAG, "Created $type snapshot in ${elapsed}ms, size: $totalSize bytes")

            snapshotInfo
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create snapshot", e)
            null
        }
    }

    private suspend fun getChangedChunkIds(slot: Int, parentSnapshotId: String?): Set<String> {
        if (parentSnapshotId == null) {
            return storage.getChunkIds(slot)
        }

        val parentSnapshot = loadSnapshotInfo(slot, parentSnapshotId)
        if (parentSnapshot == null) {
            return storage.getChunkIds(slot)
        }

        val currentChunks = storage.getChunkIds(slot)
        val changedChunks = mutableSetOf<String>()

        for (chunkId in currentChunks) {
            val currentInfo = storage.getChunkInfo(slot, chunkId)
            val wasInParent = chunkId in parentSnapshot.chunkIds

            if (!wasInParent || currentInfo?.modifiedTime ?: 0 > parentSnapshot.timestamp) {
                changedChunks.add(chunkId)
            }
        }

        return changedChunks
    }

    private suspend fun getChangedChunkId(slot: Int, parentSnapshotId: String?): Set<String> {
        return getChangedChunkIds(slot, parentSnapshotId)
    }

    private fun saveSnapshotInfo(slot: Int, snapshotInfo: SnapshotInfo) {
        val snapshotDir = File(storage.getSaveDir(slot), "snapshots")
        val snapshotFile = File(snapshotDir, "${snapshotInfo.id}.meta")
        
        snapshotFile.writeText(buildString {
            appendLine("id=${snapshotInfo.id}")
            appendLine("timestamp=${snapshotInfo.timestamp}")
            appendLine("type=${snapshotInfo.type.name}")
            appendLine("size=${snapshotInfo.size}")
            appendLine("checksum=${snapshotInfo.checksum.joinToString(",") { "%02x".format(it) }}")
            appendLine("parent=${snapshotInfo.parentSnapshotId ?: ""}")
            appendLine("chunks=${snapshotInfo.chunkIds.joinToString(",")}")
        })
    }

    private fun loadSnapshotInfo(slot: Int, snapshotId: String): SnapshotInfo? {
        return try {
            val snapshotDir = File(storage.getSaveDir(slot), "snapshots")
            val snapshotFile = File(snapshotDir, "$snapshotId.meta")

            if (!snapshotFile.exists()) return null

            val lines = snapshotFile.readLines()
            val map = lines.associate { line ->
                val (key, value) = line.split("=", limit = 2)
                key to value
            }

            SnapshotInfo(
                id = map["id"] ?: "",
                timestamp = map["timestamp"]?.toLongOrNull() ?: 0,
                type = try { SnapshotType.valueOf(map["type"] ?: "FULL") } catch (e: Exception) { SnapshotType.FULL },
                size = map["size"]?.toLongOrNull() ?: 0,
                checksum = map["checksum"]?.split(",")?.map { it.toInt(16).toByte() }?.toByteArray() ?: ByteArray(0),
                parentSnapshotId = map["parent"]?.takeIf { it.isNotEmpty() },
                chunkIds = map["chunks"]?.split(",")?.filter { it.isNotEmpty() } ?: emptyList()
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load snapshot info: $snapshotId", e)
            null
        }
    }

    suspend fun restoreFromSnapshot(slot: Int, snapshotId: String): Boolean {
        return try {
            val snapshotInfo = loadSnapshotInfo(slot, snapshotId)
            if (snapshotInfo == null) {
                Log.e(TAG, "Snapshot not found: $snapshotId")
                return false
            }

            Log.i(TAG, "Restoring from snapshot: $snapshotId, type: ${snapshotInfo.type}")

            when (snapshotInfo.type) {
                SnapshotType.FULL -> restoreFullSnapshot(slot, snapshotInfo)
                SnapshotType.INCREMENTAL -> restoreIncrementalSnapshot(slot, snapshotInfo)
                SnapshotType.DIFF -> restoreDiffSnapshot(slot, snapshotInfo)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to restore from snapshot: $snapshotId", e)
            false
        }
    }

    private suspend fun restoreFullSnapshot(slot: Int, snapshotInfo: SnapshotInfo): Boolean {
        for (chunkId in snapshotInfo.chunkIds) {
            val recovered = storage.recoverChunk(slot, chunkId)
            if (!recovered) {
                Log.w(TAG, "Failed to restore chunk: $chunkId")
            }
        }
        return true
    }

    private suspend fun restoreIncrementalSnapshot(slot: Int, snapshotInfo: SnapshotInfo): Boolean {
        if (snapshotInfo.parentSnapshotId != null) {
            restoreFromSnapshot(slot, snapshotInfo.parentSnapshotId)
        }
        return restoreFullSnapshot(slot, snapshotInfo)
    }

    private suspend fun restoreDiffSnapshot(slot: Int, snapshotInfo: SnapshotInfo): Boolean {
        return restoreFullSnapshot(slot, snapshotInfo)
    }

    fun listSnapshots(slot: Int): List<SnapshotInfo> {
        val snapshotDir = File(storage.getSaveDir(slot), "snapshots")
        if (!snapshotDir.exists()) return emptyList()

        return snapshotDir.listFiles()
            ?.filter { it.extension == "meta" }
            ?.mapNotNull { loadSnapshotInfo(slot, it.nameWithoutExtension) }
            ?.sortedByDescending { it.timestamp }
            ?: emptyList()
    }

    fun deleteSnapshot(slot: Int, snapshotId: String): Boolean {
        return try {
            val snapshotDir = File(storage.getSaveDir(slot), "snapshots")
            val snapshotFile = File(snapshotDir, "$snapshotId.meta")
            if (snapshotFile.exists()) {
                snapshotFile.delete()
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to delete snapshot: $snapshotId", e)
            false
        }
    }

    fun cleanupOldSnapshots(slot: Int, keepCount: Int = 5) {
        val snapshots = listSnapshots(slot)
        val toDelete = snapshots.drop(keepCount)

        for (snapshot in toDelete) {
            deleteSnapshot(slot, snapshot.id)
            Log.d(TAG, "Deleted old snapshot: ${snapshot.id}")
        }

        if (toDelete.isNotEmpty()) {
            Log.i(TAG, "Cleaned up ${toDelete.size} old snapshots")
        }
    }

    fun shutdown() {
        checksumCache.clear()
        scope.cancel()
        Log.i(TAG, "DataIntegrityManager shutdown completed")
    }
}
