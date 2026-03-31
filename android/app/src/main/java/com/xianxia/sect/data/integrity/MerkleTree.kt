package com.xianxia.sect.data.integrity

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.security.MessageDigest
import java.util.*

data class MerkleNode(
    val hash: ByteArray,
    val left: MerkleNode? = null,
    val right: MerkleNode? = null,
    val data: ByteArray? = null,
    val index: Int = -1
) {
    val isLeaf: Boolean get() = left == null && right == null
    
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is MerkleNode) return false
        return hash.contentEquals(other.hash)
    }
    
    override fun hashCode(): Int = hash.contentHashCode()
}

data class MerkleProof(
    val leafHash: ByteArray,
    val leafIndex: Int,
    val siblings: List<ByteArray>,
    val rootHash: ByteArray
) {
    fun verify(): Boolean {
        var currentHash = leafHash
        var currentIndex = leafIndex
        
        for (sibling in siblings) {
            currentHash = if (currentIndex % 2 == 0) {
                hashPair(currentHash, sibling)
            } else {
                hashPair(sibling, currentHash)
            }
            currentIndex /= 2
        }
        
        return currentHash.contentEquals(rootHash)
    }
    
    companion object {
        private fun hashPair(left: ByteArray, right: ByteArray): ByteArray {
            val digest = MessageDigest.getInstance("SHA-256")
            digest.update(left)
            digest.update(right)
            return digest.digest()
        }
    }
}

data class DataChunk(
    val id: String,
    val type: String,
    val data: ByteArray,
    val timestamp: Long = System.currentTimeMillis()
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is DataChunk) return false
        return id == other.id && data.contentEquals(other.data)
    }
    
    override fun hashCode(): Int = 31 * id.hashCode() + data.contentHashCode()
}

data class IntegrityReport(
    val isValid: Boolean,
    val rootHash: ByteArray,
    val totalChunks: Int,
    val verifiedChunks: Int,
    val corruptedChunks: List<String>,
    val missingChunks: List<String>,
    val verificationTimeMs: Long,
    val errors: List<String>
)

class MerkleTree(
    private val algorithm: String = "SHA-256"
) {
    companion object {
        private const val TAG = "MerkleTree"
        private const val MAX_TREE_DEPTH = 32
    }
    
    private val digest = MessageDigest.getInstance(algorithm)
    private var root: MerkleNode? = null
    private val leaves = mutableListOf<MerkleNode>()
    private val chunkIndexMap = mutableMapOf<String, Int>()
    
    fun buildTree(chunks: List<DataChunk>): MerkleNode? {
        if (chunks.isEmpty()) {
            root = null
            leaves.clear()
            chunkIndexMap.clear()
            return null
        }
        
        leaves.clear()
        chunkIndexMap.clear()
        
        chunks.forEachIndexed { index, chunk ->
            val hash = hashData(chunk.data)
            val node = MerkleNode(
                hash = hash,
                data = chunk.data,
                index = index
            )
            leaves.add(node)
            chunkIndexMap[chunk.id] = index
        }
        
        root = buildTreeFromLeaves(leaves.toList())
        return root
    }
    
    private fun buildTreeFromLeaves(nodes: List<MerkleNode>): MerkleNode {
        if (nodes.size == 1) return nodes[0]
        
        val nextLevel = mutableListOf<MerkleNode>()
        
        for (i in nodes.indices step 2) {
            val left = nodes[i]
            val right = if (i + 1 < nodes.size) nodes[i + 1] else left
            
            val combinedHash = hashPair(left.hash, right.hash)
            nextLevel.add(MerkleNode(
                hash = combinedHash,
                left = left,
                right = right
            ))
        }
        
        return buildTreeFromLeaves(nextLevel)
    }
    
    fun getRootHash(): ByteArray? = root?.hash?.copyOf()
    
    fun getRootHashHex(): String? = root?.hash?.let { bytesToHex(it) }
    
    fun generateProof(chunkId: String): MerkleProof? {
        val index = chunkIndexMap[chunkId] ?: return null
        val leaf = leaves.getOrNull(index) ?: return null
        val rootHash = root?.hash ?: return null
        
        val siblings = mutableListOf<ByteArray>()
        var currentIndex = index
        var currentLevel = leaves.toList()
        
        while (currentLevel.size > 1) {
            val nextLevel = mutableListOf<MerkleNode>()
            
            for (i in currentLevel.indices step 2) {
                val left = currentLevel[i]
                val right = if (i + 1 < currentLevel.size) currentLevel[i + 1] else left
                
                if (i == currentIndex || i + 1 == currentIndex) {
                    val siblingIndex = if (currentIndex % 2 == 0) currentIndex + 1 else currentIndex - 1
                    if (siblingIndex < currentLevel.size) {
                        siblings.add(currentLevel[siblingIndex].hash.copyOf())
                    }
                }
                
                val combinedHash = hashPair(left.hash, right.hash)
                nextLevel.add(MerkleNode(hash = combinedHash, left = left, right = right))
            }
            
            currentIndex /= 2
            currentLevel = nextLevel
        }
        
        return MerkleProof(
            leafHash = leaf.hash.copyOf(),
            leafIndex = index,
            siblings = siblings,
            rootHash = rootHash.copyOf()
        )
    }
    
    fun verifyChunk(chunkId: String, data: ByteArray): Boolean {
        val proof = generateProof(chunkId) ?: return false
        val newHash = hashData(data)
        
        if (!newHash.contentEquals(proof.leafHash)) {
            Log.w(TAG, "Chunk $chunkId hash mismatch")
            return false
        }
        
        return proof.verify()
    }
    
    fun verifyAllChunks(chunks: List<DataChunk>): IntegrityReport {
        val startTime = System.currentTimeMillis()
        val corruptedChunks = mutableListOf<String>()
        val missingChunks = mutableListOf<String>()
        val errors = mutableListOf<String>()
        var verifiedCount = 0
        
        for (chunk in chunks) {
            val index = chunkIndexMap[chunk.id]
            if (index == null) {
                missingChunks.add(chunk.id)
                continue
            }
            
            try {
                if (verifyChunk(chunk.id, chunk.data)) {
                    verifiedCount++
                } else {
                    corruptedChunks.add(chunk.id)
                }
            } catch (e: Exception) {
                errors.add("Failed to verify chunk ${chunk.id}: ${e.message}")
                corruptedChunks.add(chunk.id)
            }
        }
        
        val elapsed = System.currentTimeMillis() - startTime
        
        return IntegrityReport(
            isValid = corruptedChunks.isEmpty() && missingChunks.isEmpty(),
            rootHash = root?.hash?.copyOf() ?: ByteArray(0),
            totalChunks = chunks.size,
            verifiedChunks = verifiedCount,
            corruptedChunks = corruptedChunks,
            missingChunks = missingChunks,
            verificationTimeMs = elapsed,
            errors = errors
        )
    }
    
    fun findCorruptedChunks(chunks: List<DataChunk>): List<String> {
        val corrupted = mutableListOf<String>()
        
        for (chunk in chunks) {
            val expectedHash = leaves.getOrNull(chunkIndexMap[chunk.id] ?: -1)?.hash
            if (expectedHash == null) {
                corrupted.add(chunk.id)
                continue
            }
            
            val actualHash = hashData(chunk.data)
            if (!actualHash.contentEquals(expectedHash)) {
                corrupted.add(chunk.id)
            }
        }
        
        return corrupted
    }
    
    fun updateChunk(chunkId: String, newData: ByteArray): Boolean {
        val index = chunkIndexMap[chunkId] ?: return false
        
        val newHash = hashData(newData)
        leaves[index] = MerkleNode(
            hash = newHash,
            data = newData,
            index = index
        )
        
        root = buildTreeFromLeaves(leaves.toList())
        return true
    }
    
    fun addChunk(chunk: DataChunk): Boolean {
        if (chunkIndexMap.containsKey(chunk.id)) {
            return updateChunk(chunk.id, chunk.data)
        }
        
        val index = leaves.size
        val hash = hashData(chunk.data)
        leaves.add(MerkleNode(
            hash = hash,
            data = chunk.data,
            index = index
        ))
        chunkIndexMap[chunk.id] = index
        
        root = buildTreeFromLeaves(leaves.toList())
        return true
    }
    
    fun removeChunk(chunkId: String): Boolean {
        val index = chunkIndexMap[chunkId] ?: return false
        
        leaves.removeAt(index)
        chunkIndexMap.remove(chunkId)
        
        chunkIndexMap.entries.forEach { entry ->
            if (entry.value > index) {
                entry.setValue(entry.value - 1)
            }
        }
        
        leaves.forEachIndexed { i, node ->
            leaves[i] = node.copy(index = i)
        }
        
        root = if (leaves.isEmpty()) null else buildTreeFromLeaves(leaves.toList())
        return true
    }
    
    fun getChunkCount(): Int = leaves.size
    
    fun getTreeDepth(): Int {
        if (leaves.isEmpty()) return 0
        var depth = 1
        var count = leaves.size
        while (count > 1) {
            count = (count + 1) / 2
            depth++
        }
        return depth
    }
    
    fun serialize(): ByteArray {
        val baos = java.io.ByteArrayOutputStream()
        val dos = java.io.DataOutputStream(baos)
        
        dos.writeInt(leaves.size)
        leaves.forEach { node ->
            dos.writeInt(node.hash.size)
            dos.write(node.hash)
        }
        
        val rootHash = root?.hash
        if (rootHash != null) {
            dos.writeInt(rootHash.size)
            dos.write(rootHash)
        } else {
            dos.writeInt(0)
        }
        
        return baos.toByteArray()
    }
    
    fun deserialize(data: ByteArray): Boolean {
        return try {
            val dis = java.io.DataInputStream(java.io.ByteArrayInputStream(data))
            
            val leafCount = dis.readInt()
            leaves.clear()
            chunkIndexMap.clear()
            
            for (i in 0 until leafCount) {
                val hashSize = dis.readInt()
                val hash = ByteArray(hashSize)
                dis.readFully(hash)
                leaves.add(MerkleNode(hash = hash, index = i))
            }
            
            val rootHashSize = dis.readInt()
            if (rootHashSize > 0) {
                val rootHash = ByteArray(rootHashSize)
                dis.readFully(rootHash)
                root = MerkleNode(hash = rootHash)
            } else {
                root = null
            }
            
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to deserialize MerkleTree", e)
            false
        }
    }
    
    private fun hashData(data: ByteArray): ByteArray {
        return digest.digest(data)
    }
    
    private fun hashPair(left: ByteArray, right: ByteArray): ByteArray {
        val newDigest = MessageDigest.getInstance(algorithm)
        newDigest.update(left)
        newDigest.update(right)
        return newDigest.digest()
    }
    
    private fun bytesToHex(bytes: ByteArray): String {
        return bytes.joinToString("") { "%02x".format(it) }
    }
    
    fun clear() {
        root = null
        leaves.clear()
        chunkIndexMap.clear()
    }
}

class SaveDataIntegrityValidator {
    companion object {
        private const val TAG = "SaveDataIntegrity"
    }
    
    private val merkleTree = MerkleTree()
    
    suspend fun buildIntegrityTree(
        gameData: ByteArray,
        disciples: List<Pair<String, ByteArray>>,
        items: List<Pair<String, ByteArray>>
    ): ByteArray? = withContext(Dispatchers.Default) {
        val chunks = mutableListOf<DataChunk>()
        
        chunks.add(DataChunk(
            id = "game_data",
            type = "core",
            data = gameData
        ))
        
        disciples.forEach { (id, data) ->
            chunks.add(DataChunk(
                id = "disciple_$id",
                type = "disciple",
                data = data
            ))
        }
        
        items.forEach { (id, data) ->
            chunks.add(DataChunk(
                id = "item_$id",
                type = "item",
                data = data
            ))
        }
        
        merkleTree.buildTree(chunks)
        merkleTree.getRootHash()
    }
    
    suspend fun validateSaveData(
        gameData: ByteArray,
        disciples: List<Pair<String, ByteArray>>,
        items: List<Pair<String, ByteArray>>
    ): IntegrityReport = withContext(Dispatchers.Default) {
        val chunks = mutableListOf<DataChunk>()
        
        chunks.add(DataChunk(
            id = "game_data",
            type = "core",
            data = gameData
        ))
        
        disciples.forEach { (id, data) ->
            chunks.add(DataChunk(
                id = "disciple_$id",
                type = "disciple",
                data = data
            ))
        }
        
        items.forEach { (id, data) ->
            chunks.add(DataChunk(
                id = "item_$id",
                type = "item",
                data = data
            ))
        }
        
        merkleTree.verifyAllChunks(chunks)
    }
    
    fun getMerkleRoot(): ByteArray? = merkleTree.getRootHash()
    
    fun getMerkleRootHex(): String? = merkleTree.getRootHashHex()
    
    fun generateChunkProof(chunkId: String): MerkleProof? {
        return merkleTree.generateProof(chunkId)
    }
    
    fun serializeTree(): ByteArray = merkleTree.serialize()
    
    fun deserializeTree(data: ByteArray): Boolean = merkleTree.deserialize(data)
    
    fun clear() = merkleTree.clear()
}
