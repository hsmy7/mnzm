package com.xianxia.sect.data.v2.compression

import android.content.Context
import android.util.Log
import com.xianxia.sect.data.v2.StorageArchitecture
import com.xianxia.sect.data.v2.CompressionStrategy
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.io.*
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import java.util.zip.*

data class CompressionResult(
    val originalSize: Long,
    val compressedSize: Long,
    val compressionRatio: Double,
    val compressionTimeMs: Long,
    val strategy: CompressionStrategy
) {
    val savedBytes: Long get() = originalSize - compressedSize
    val savedPercent: Double get() = if (originalSize > 0) (savedBytes.toDouble() / originalSize) * 100 else 0.0
}

data class CompressionStats(
    val totalCompressed: Long = 0,
    val totalDecompressed: Long = 0,
    val totalBytesSaved: Long = 0,
    val avgCompressionRatio: Double = 0.0,
    val avgCompressionTimeMs: Double = 0.0,
    val cacheHitCount: Long = 0,
    val cacheMissCount: Long = 0
)

data class CompressedBlock(
    val id: String,
    val originalSize: Long,
    val compressedSize: Long,
    val checksum: String,
    val strategy: CompressionStrategy,
    val timestamp: Long,
    val accessCount: Int = 0,
    val lastAccessTime: Long = System.currentTimeMillis()
)

class TieredCompressionManager(
    private val context: Context,
    private val defaultStrategy: CompressionStrategy = CompressionStrategy.BALANCED,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
) {
    companion object {
        private const val TAG = "TieredCompression"
        private const val BLOCK_DIR = "compressed_blocks"
        private const val INDEX_FILE = "compression_index.dat"
        private const val MIN_COMPRESS_SIZE = StorageArchitecture.Compression.THRESHOLD_BYTES
    }
    
    private val blockIndex = ConcurrentHashMap<String, CompressedBlock>()
    private val compressionCache = ConcurrentHashMap<String, ByteArray>()
    
    private val totalCompressed = AtomicLong(0)
    private val totalDecompressed = AtomicLong(0)
    private val totalBytesSaved = AtomicLong(0)
    private val totalCompressionTime = AtomicLong(0)
    private val cacheHits = AtomicLong(0)
    private val cacheMisses = AtomicLong(0)
    
    private val _stats = MutableStateFlow(CompressionStats())
    val stats: StateFlow<CompressionStats> = _stats.asStateFlow()
    
    private var isShuttingDown = false
    
    init {
        loadIndex()
    }
    
    private fun loadIndex() {
        val indexFile = File(context.filesDir, "$BLOCK_DIR/$INDEX_FILE")
        if (!indexFile.exists()) {
            File(context.filesDir, BLOCK_DIR).mkdirs()
            return
        }
        
        try {
            DataInputStream(FileInputStream(indexFile)).use { dis ->
                val count = dis.readInt()
                repeat(count) {
                    val block = CompressedBlock(
                        id = dis.readUTF(),
                        originalSize = dis.readLong(),
                        compressedSize = dis.readLong(),
                        checksum = dis.readUTF(),
                        strategy = CompressionStrategy.valueOf(dis.readUTF()),
                        timestamp = dis.readLong(),
                        accessCount = dis.readInt(),
                        lastAccessTime = dis.readLong()
                    )
                    blockIndex[block.id] = block
                }
            }
            Log.i(TAG, "Loaded ${blockIndex.size} compression blocks")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load compression index", e)
        }
    }
    
    private fun saveIndex() {
        val indexFile = File(context.filesDir, "$BLOCK_DIR/$INDEX_FILE")
        indexFile.parentFile?.mkdirs()
        
        try {
            DataOutputStream(FileOutputStream(indexFile)).use { dos ->
                dos.writeInt(blockIndex.size)
                blockIndex.values.forEach { block ->
                    dos.writeUTF(block.id)
                    dos.writeLong(block.originalSize)
                    dos.writeLong(block.compressedSize)
                    dos.writeUTF(block.checksum)
                    dos.writeUTF(block.strategy.name)
                    dos.writeLong(block.timestamp)
                    dos.writeInt(block.accessCount)
                    dos.writeLong(block.lastAccessTime)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save compression index", e)
        }
    }
    
    suspend fun compress(
        id: String,
        data: ByteArray,
        strategy: CompressionStrategy = defaultStrategy
    ): CompressionResult {
        if (data.size < MIN_COMPRESS_SIZE) {
            return CompressionResult(
                originalSize = data.size.toLong(),
                compressedSize = data.size.toLong(),
                compressionRatio = 1.0,
                compressionTimeMs = 0,
                strategy = CompressionStrategy.NONE
            )
        }
        
        val effectiveStrategy = if (strategy == CompressionStrategy.ADAPTIVE) {
            if (data.size < 64 * 1024) CompressionStrategy.FAST else CompressionStrategy.BALANCED
        } else {
            strategy
        }
        
        val level = when (effectiveStrategy) {
            CompressionStrategy.FAST -> StorageArchitecture.Compression.LEVEL_FAST
            CompressionStrategy.MAXIMUM -> StorageArchitecture.Compression.LEVEL_MAX
            else -> StorageArchitecture.Compression.LEVEL_BALANCED
        }
        
        val startTime = System.currentTimeMillis()
        val compressed = compressGzip(data, level)
        val compressionTime = System.currentTimeMillis() - startTime
        
        val checksum = calculateChecksum(data)
        
        val block = CompressedBlock(
            id = id,
            originalSize = data.size.toLong(),
            compressedSize = compressed.size.toLong(),
            checksum = checksum,
            strategy = effectiveStrategy,
            timestamp = System.currentTimeMillis()
        )
        
        saveCompressedBlock(id, compressed)
        blockIndex[id] = block
        
        totalCompressed.incrementAndGet()
        totalBytesSaved.addAndGet(block.originalSize - block.compressedSize)
        totalCompressionTime.addAndGet(compressionTime)
        
        updateStats()
        
        return CompressionResult(
            originalSize = block.originalSize,
            compressedSize = block.compressedSize,
            compressionRatio = block.compressedSize.toDouble() / block.originalSize,
            compressionTimeMs = compressionTime,
            strategy = effectiveStrategy
        )
    }
    
    private fun compressGzip(data: ByteArray, level: Int): ByteArray {
        return ByteArrayOutputStream().use { baos ->
            GZIPOutputStream(baos, level).use { it.write(data) }
            baos.toByteArray()
        }
    }
    
    suspend fun decompress(id: String): ByteArray? {
        blockIndex[id]?.let { block ->
            val cached = compressionCache[id]
            if (cached != null) {
                cacheHits.incrementAndGet()
                return cached
            }
            
            cacheMisses.incrementAndGet()
            
            val compressed = loadCompressedBlock(id) ?: return null
            
            val decompressed = decompressGzip(compressed)
            
            val checksum = calculateChecksum(decompressed)
            if (checksum != block.checksum) {
                Log.e(TAG, "Checksum mismatch for block $id")
                return null
            }
            
            blockIndex[id] = block.copy(
                accessCount = block.accessCount + 1,
                lastAccessTime = System.currentTimeMillis()
            )
            
            compressionCache[id] = decompressed
            totalDecompressed.incrementAndGet()
            
            updateStats()
            
            return decompressed
        }
        
        return null
    }
    
    private fun decompressGzip(data: ByteArray): ByteArray {
        return GZIPInputStream(ByteArrayInputStream(data)).use { it.readBytes() }
    }
    
    private fun saveCompressedBlock(id: String, data: ByteArray) {
        val blockFile = getBlockFile(id)
        blockFile.parentFile?.mkdirs()
        blockFile.writeBytes(data)
    }
    
    private fun loadCompressedBlock(id: String): ByteArray? {
        val blockFile = getBlockFile(id)
        return if (blockFile.exists()) blockFile.readBytes() else null
    }
    
    private fun getBlockFile(id: String): File {
        val hash = id.hashCode().toString(16).padStart(8, '0')
        val subdir = hash.substring(0, 2)
        val dir = File(context.filesDir, "$BLOCK_DIR/$subdir")
        return File(dir, "$id.dat")
    }
    
    fun hasBlock(id: String): Boolean = blockIndex.containsKey(id)
    
    fun getBlockInfo(id: String): CompressedBlock? = blockIndex[id]
    
    fun deleteBlock(id: String): Boolean {
        blockIndex.remove(id)?.let { block ->
            val blockFile = getBlockFile(id)
            if (blockFile.exists()) blockFile.delete()
            compressionCache.remove(id)
            totalBytesSaved.addAndGet(-(block.originalSize - block.compressedSize))
            updateStats()
            return true
        }
        return false
    }
    
    fun clearCache() {
        compressionCache.clear()
    }
    
    fun cleanupOldBlocks(maxAge: Long = 7 * 24 * 60 * 60 * 1000L) {
        val cutoff = System.currentTimeMillis() - maxAge
        val toRemove = blockIndex.entries
            .filter { it.value.timestamp < cutoff && it.value.accessCount < 2 }
            .map { it.key }
        
        toRemove.forEach { deleteBlock(it) }
        
        if (toRemove.isNotEmpty()) {
            Log.i(TAG, "Cleaned up ${toRemove.size} old compression blocks")
            saveIndex()
        }
    }
    
    private fun calculateChecksum(data: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(data).joinToString("") { "%02x".format(it) }
    }
    
    private fun updateStats() {
        val compressed = totalCompressed.get()
        val saved = totalBytesSaved.get()
        val time = totalCompressionTime.get()
        val hits = cacheHits.get()
        val misses = cacheMisses.get()
        
        _stats.value = CompressionStats(
            totalCompressed = compressed,
            totalDecompressed = totalDecompressed.get(),
            totalBytesSaved = saved,
            avgCompressionRatio = if (compressed > 0) saved.toDouble() / compressed else 0.0,
            avgCompressionTimeMs = if (compressed > 0) time.toDouble() / compressed else 0.0,
            cacheHitCount = hits,
            cacheMissCount = misses
        )
    }
    
    fun getStorageSize(): Long {
        val blockDir = File(context.filesDir, BLOCK_DIR)
        if (!blockDir.exists()) return 0
        return blockDir.walkTopDown().filter { it.isFile }.map { it.length() }.sum()
    }
    
    fun shutdown() {
        isShuttingDown = true
        saveIndex()
        compressionCache.clear()
        scope.cancel()
        Log.i(TAG, "TieredCompressionManager shutdown completed")
    }
}
