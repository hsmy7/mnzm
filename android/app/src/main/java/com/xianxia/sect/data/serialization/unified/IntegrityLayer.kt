package com.xianxia.sect.data.serialization.unified

import android.util.Log
import java.security.MessageDigest
import java.util.TreeMap
import javax.inject.Inject
import javax.inject.Singleton

data class IntegrityCheckResult(
    val isValid: Boolean,
    val errors: List<String> = emptyList(),
    val warnings: List<String> = emptyList(),
    val checkedChunks: Int = 0,
    val checkTimeMs: Long = 0
)

sealed class ChecksumData {
    data class Simple(val sha256: ByteArray) : ChecksumData() {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Simple) return false
            return sha256.contentEquals(other.sha256)
        }
        override fun hashCode(): Int = sha256.contentHashCode()
    }
    
    data class MerkleTree(
        val root: ByteArray,
        val leaves: Map<String, ByteArray>
    ) : ChecksumData() {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is MerkleTree) return false
            return root.contentEquals(other.root)
        }
        override fun hashCode(): Int = root.contentHashCode()
        
        fun verifyChunk(chunkId: String, data: ByteArray): Boolean {
            val leafHash = leaves[chunkId] ?: return false
            val computedHash = MessageDigest.getInstance("SHA-256").digest(data)
            return leafHash.contentEquals(computedHash)
        }
    }
}

@Singleton
class IntegrityLayer @Inject constructor() {
    companion object {
        private const val TAG = "IntegrityLayer"
        private const val HASH_ALGORITHM = "SHA-256"
        private const val HASH_SIZE = 32
    }
    
    private val digest: MessageDigest by lazy {
        MessageDigest.getInstance(HASH_ALGORITHM)
    }
    
    fun computeChecksum(data: ByteArray): ByteArray {
        return digest.digest(data)
    }
    
    fun computeChecksumHex(data: ByteArray): String {
        val checksum = computeChecksum(data)
        return checksum.joinToString("") { "%02x".format(it) }
    }
    
    fun verifyChecksum(data: ByteArray, expectedChecksum: ByteArray): Boolean {
        val computed = computeChecksum(data)
        return computed.contentEquals(expectedChecksum)
    }
    
    fun verifyChecksumHex(data: ByteArray, expectedHex: String): Boolean {
        val computed = computeChecksumHex(data)
        return computed.equals(expectedHex, ignoreCase = true)
    }
    
    fun computeMerkleRoot(hashes: Collection<ByteArray>): ByteArray {
        if (hashes.isEmpty()) {
            return ByteArray(HASH_SIZE)
        }
        
        if (hashes.size == 1) {
            return hashes.first()
        }
        
        var currentLevel = hashes.toList()
        
        while (currentLevel.size > 1) {
            val nextLevel = mutableListOf<ByteArray>()
            
            for (i in currentLevel.indices step 2) {
                val left = currentLevel[i]
                val right = if (i + 1 < currentLevel.size) currentLevel[i + 1] else left
                
                val combined = left + right
                nextLevel.add(computeChecksum(combined))
            }
            
            currentLevel = nextLevel
        }
        
        return currentLevel.first()
    }
    
    fun computeMerkleTree(chunks: Map<String, ByteArray>): ChecksumData.MerkleTree {
        val sortedChunks = TreeMap(chunks)
        val leaves = mutableMapOf<String, ByteArray>()
        
        sortedChunks.forEach { (id, data) ->
            leaves[id] = computeChecksum(data)
        }
        
        val root = computeMerkleRoot(leaves.values)
        
        return ChecksumData.MerkleTree(
            root = root,
            leaves = leaves.toMap()
        )
    }
    
    fun verifyMerkleTree(
        chunks: Map<String, ByteArray>,
        expectedRoot: ByteArray
    ): IntegrityCheckResult {
        val startTime = System.currentTimeMillis()
        val errors = mutableListOf<String>()
        val warnings = mutableListOf<String>()
        
        val tree = computeMerkleTree(chunks)
        
        if (!tree.root.contentEquals(expectedRoot)) {
            errors.add("Merkle root mismatch")
            
            chunks.forEach { (id, data) ->
                if (!tree.verifyChunk(id, data)) {
                    errors.add("Chunk integrity failed: $id")
                }
            }
        }
        
        val checkTime = System.currentTimeMillis() - startTime
        
        return IntegrityCheckResult(
            isValid = errors.isEmpty(),
            errors = errors,
            warnings = warnings,
            checkedChunks = chunks.size,
            checkTimeMs = checkTime
        )
    }
    
    fun computeIncrementalChecksum(
        baseChecksum: ByteArray,
        newData: ByteArray
    ): ByteArray {
        val combined = baseChecksum + newData
        return computeChecksum(combined)
    }
    
    fun computeChunkChecksums(data: ByteArray, chunkSize: Int): List<ByteArray> {
        val checksums = mutableListOf<ByteArray>()
        var offset = 0
        
        while (offset < data.size) {
            val end = minOf(offset + chunkSize, data.size)
            val chunk = data.copyOfRange(offset, end)
            checksums.add(computeChecksum(chunk))
            offset = end
        }
        
        return checksums
    }
    
    fun verifyChunkChecksums(
        data: ByteArray,
        expectedChecksums: List<ByteArray>,
        chunkSize: Int
    ): IntegrityCheckResult {
        val startTime = System.currentTimeMillis()
        val errors = mutableListOf<String>()
        val computedChecksums = computeChunkChecksums(data, chunkSize)
        
        if (computedChecksums.size != expectedChecksums.size) {
            errors.add("Checksum count mismatch: expected ${expectedChecksums.size}, got ${computedChecksums.size}")
        } else {
            computedChecksums.forEachIndexed { index, computed ->
                if (!computed.contentEquals(expectedChecksums[index])) {
                    errors.add("Chunk $index checksum mismatch")
                }
            }
        }
        
        val checkTime = System.currentTimeMillis() - startTime
        
        return IntegrityCheckResult(
            isValid = errors.isEmpty(),
            errors = errors,
            checkedChunks = computedChecksums.size,
            checkTimeMs = checkTime
        )
    }
    
    fun computeSimpleChecksum(data: ByteArray): ChecksumData.Simple {
        return ChecksumData.Simple(computeChecksum(data))
    }
    
    fun combineChecksums(checksums: List<ByteArray>): ByteArray {
        val combined = checksums.reduce { acc, bytes -> acc + bytes }
        return computeChecksum(combined)
    }
    
    fun quickVerify(data: ByteArray, checksum: ByteArray): Boolean {
        return try {
            verifyChecksum(data, checksum)
        } catch (e: Exception) {
            Log.e(TAG, "Quick verify failed", e)
            false
        }
    }
    
    fun computeHash(data: ByteArray): String {
        return computeChecksumHex(data)
    }
    
    fun computeHashShort(data: ByteArray, length: Int = 16): String {
        return computeChecksumHex(data).take(length)
    }
}

data class DataIntegrityInfo(
    val checksum: ByteArray,
    val merkleRoot: ByteArray? = null,
    val chunkChecksums: List<ByteArray> = emptyList(),
    val timestamp: Long = System.currentTimeMillis(),
    val dataSize: Int
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is DataIntegrityInfo) return false
        return checksum.contentEquals(other.checksum)
    }
    
    override fun hashCode(): Int = checksum.contentHashCode()
}
