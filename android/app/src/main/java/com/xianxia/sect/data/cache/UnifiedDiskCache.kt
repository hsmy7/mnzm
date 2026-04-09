package com.xianxia.sect.data.cache

import android.content.Context
import android.util.Log
import com.tencent.mmkv.MMKV
import com.xianxia.sect.data.serialization.NullSafeProtoBuf
import kotlinx.coroutines.*
import net.jpountz.lz4.LZ4Factory
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.zip.CRC32
import kotlin.math.min

// ==================== 数据类定义 ====================

/**
 * 统一磁盘缓存配置
 */
data class UnifiedDiskCacheConfig(
    /** 小对象使用 MMKV 的阈值（字节），默认 64KB */
    val mmkvThreshold: Int = DEFAULT_MMKV_THRESHOLD,
    /** 写入合并窗口（毫秒） */
    val coalesceWindowMs: Long = 500L,
    /** 每个 key 最大待处理写入数 */
    val maxPendingPerKey: Int = 10,
    /** 后台校验间隔（毫秒），默认 5 分钟 */
    val verifyIntervalMs: Long = 300_000L,
    /** 是否启用 CRC32 校验 */
    val enableCrcCheck: Boolean = true,
    /** 是否启用后台自动校验 */
    val enableAutoVerify: Boolean = true
) {
    companion object {
        const val DEFAULT_MMKV_THRESHOLD = 64 * 1024  // 64KB
    }
}

/**
 * 缓存序列化器接口
 *
 * 统一的序列化管道，支持不同序列化策略的插拔替换。
 */

/**
 * 默认 Protobuf + LZ4 序列化器实现
 *
 * 序列化格式:
 * ┌──────────┬─────────┬────────────┬──────────┬──────────────┬────────────────┐
 * │ magic(2B)│ ver(1B) │ typeLen(4B)│ typeName │ origSize(4B) │ compressedData │
 * └──────────┴─────────┴────────────┴──────────┴──────────────┴────────────────┘
 */
class ProtobufLz4Serializer : CacheSerializer<Any> {

    companion object {
        private const val TAG = "PbLz4Serializer"
        private const val MAGIC: Short = 0x5544  // "UD" - Unified Disk
        private const val VERSION: Byte = 1
    }

    private val protoBuf = NullSafeProtoBuf.protoBuf
    private val lz4Compressor = LZ4Factory.fastestInstance().fastCompressor()
    private val lz4Decompressor = LZ4Factory.fastestInstance().fastDecompressor()

    override fun serialize(value: Any): ByteArray {
        val typeName = value::class.qualifiedName ?: "Unknown"
        val typeNameBytes = typeName.toByteArray(Charsets.UTF_8)

        // 使用 CacheEntry 的 V2 序列化方式
        val rawData = when (value) {
            is ByteArray -> value
            is String -> value.toByteArray(Charsets.UTF_8)
            is CacheEntry -> value.serializeV2()
            else -> value.toString().toByteArray(Charsets.UTF_8)
        }

        // LZ4 压缩
        val maxCompressed = lz4Compressor.maxCompressedLength(rawData.size)
        val compressed = ByteArray(maxCompressed)
        val compressedSize = lz4Compressor.compress(rawData, 0, rawData.size, compressed, 0)

        // 组装输出
        val baos = ByteArrayOutputStream()
        val dos = DataOutputStream(baos)
        dos.writeShort(MAGIC.toInt())
        dos.writeByte(VERSION.toInt())
        dos.writeInt(typeNameBytes.size)
        dos.write(typeNameBytes)
        dos.writeInt(rawData.size)
        dos.write(compressed, 0, compressedSize)

        return baos.toByteArray()
    }

    @Suppress("TooGenericExceptionCaught")
    override fun deserialize(data: ByteArray): Any? {
        if (data.size < 12) return null

        return try {
            val dis = DataInputStream(ByteArrayInputStream(data))
            val magic = dis.readShort()
            if (magic != MAGIC) {
                Log.w(TAG, "Invalid magic header: ${String.format(Locale.getDefault(), "0x%04X", magic)}")
                return null
            }

            dis.readByte() // version
            val typeNameLen = dis.readInt()
            if (typeNameLen <= 0 || typeNameLen > data.size) return null

            val typeNameBytes = ByteArray(typeNameLen)
            dis.readFully(typeNameBytes)

            val originalSize = dis.readInt()
            if (originalSize <= 0 || originalSize > data.size * 10) return null

            val compressedStart = 2 + 1 + 4 + typeNameLen + 4
            val compressedData = data.copyOfRange(compressedStart, data.size)

            // LZ4 解压
            val decompressed = ByteArray(originalSize)
            lz4Decompressor.decompress(compressedData, 0, decompressed, 0, originalSize)

            // 尝试还原为 CacheEntry
            CacheEntry.deserializeV2(decompressed) ?: decompressed
        } catch (e: Exception) {
            Log.w(TAG, "Deserialize failed: ${e.message}")
            null
        }
    }
}

/**
 * 索引元数据条目
 */
data class UnifiedCacheMeta(
    val key: String,
    val storageType: StorageType,
    var size: Int,
    val createdAt: Long,
    val ttlMs: Long,
    var crc32: Long = 0,
    var lastAccessTime: Long = 0L
)

/**
 * 存储类型枚举
 */
enum class StorageType(val displayName: String) {
    MMKV("MMKV mmap 存储"),
    FILE("文件系统存储")
}

/**
 * 统一磁盘缓存统计信息
 */
data class UnifiedDiskCacheStats(
    val hitCount: Long,
    val missCount: Long,
    val evictionCount: Long,
    val totalSize: Long,
    val entryCount: Int,
    val corruptionCount: Long,
    val mmkvEntryCount: Int,
    val fileEntryCount: Int,
    val maxSize: Long,
    val usagePercent: Double,
    val hitRate: Double
)

// ==================== 核心实现 ====================

/**
 * ## UnifiedDiskCache - 统一磁盘缓存
 *
 * 合并原有 DiskCache（MMKV）和 DiskCacheLayer（文件系统）的统一实现。
 *
 * ### 分级存储架构
 * ```
 *                    UnifiedDiskCache
 *                          │
 *              ┌───────────┴───────────┐
 *              │                       │
 *         小对象 (<64KB)          大对象 (>=64KB)
 *              │                       │
 *           MMKV (mmap)           文件系统 + LZ4
 *         零拷贝高性能             压缩节省空间
 * ```
 *
 * ### 核心特性
 * 1. **分级存储**: 自动根据数据大小选择最优存储后端
 * 2. **统一序列化**: Protobuf + LZ4 压缩，格式统一可扩展
 * 3. **CRC32 校验**: 每次读写都进行完整性验证
 * 4. **写入合并**: 通过 WriteCoalescer 减少重复 I/O
 * 5. **索引维护**: 内存索引加速查找，支持持久化恢复
 * 6. **损坏恢复**: 自动检测并修复损坏的数据
 * 7. **容量管理**: LRU 淘汰策略，优先淘汰大对象
 */
class UnifiedDiskCache(
    context: Context,
    private val cacheId: String = "unified_disk_cache",
    private val cacheDirName: String = "unified_disk_cache",
    maxSize: Long = DEFAULT_MAX_SIZE,
    private val config: UnifiedDiskCacheConfig = UnifiedDiskCacheConfig()
) {
    companion object {
        private const val TAG = "UnifiedDiskCache"

        /** 默认最大容量 100MB */
        const val DEFAULT_MAX_SIZE = 100L * 1024 * 1024

        /** MMKV 内部 key 前缀 */
        private const val MMKV_KEY_PREFIX = "udc_"

        /** 元数据 key */
        private const val META_TOTAL_SIZE = "_udc_meta_total_size"
        private const val META_ENTRY_COUNT = "_udc_meta_entry_count"

        /** 后台任务延迟启动时间 */
        private const val BACKGROUND_INIT_DELAY_MS = 1000L

        @Volatile
        private var mmkvInitialized = false

        fun initializeMmkv(context: Context) {
            if (!mmkvInitialized) {
                MMKV.initialize(context.applicationContext)
                mmkvInitialized = true
                Log.i(TAG, "MMKV initialized for UnifiedDiskCache")
            }
        }
    }

    // ==================== 内部组件 ====================

    /** MMKV 实例（小对象存储） */
    private val mmkv: MMKV by lazy {
        initializeMmkv(context.applicationContext)
        MMKV.mmkvWithID(cacheId, MMKV.MULTI_PROCESS_MODE, "${cacheId}_key")
    }

    /** 文件缓存目录（大对象存储） */
    private val fileCacheDir: File by lazy {
        val dir = File(context.cacheDir, cacheDirName)
        if (!dir.exists()) dir.mkdirs()
        dir
    }

    /** 内存索引：key -> meta */
    private val indexMap = ConcurrentHashMap<String, UnifiedCacheMeta>()

    /** 序列化器 */
    private val serializer: CacheSerializer<Any> = ProtobufLz4Serializer()

    /** 写入合并器 */
    private val writeCoalescer: WriteCoalescer by lazy {
        WriteCoalescer(
            coalesceWindowMs = config.coalesceWindowMs,
            maxPendingPerKey = config.maxPendingPerKey
        )
    }

    /** CRC32 计算实例（线程安全，可复用） */
    private val crc32 = CRC32()

    // ==================== 统计计数器 ====================

    private val totalSize = AtomicLong(0)
    private val maxSize: Long = maxSize.coerceAtLeast(1024 * 1024)  // 最少 1MB
    private val hitCount = AtomicLong(0)
    private val missCount = AtomicLong(0)
    private val evictionCount = AtomicLong(0)
    private val corruptionCount = AtomicLong(0)

    private val mmkvEntryCount = AtomicInteger(0)
    private val fileEntryCount = AtomicInteger(0)

    /** 协程作用域 */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 后台校验任务 */
    private var verifyJob: Job? = null

    init {
        loadIndex()
        startBackgroundTasks()
    }

    // ==================== 公共接口（兼容原 DiskCache）====================

    /**
     * 存入缓存条目
     *
     * 自动选择存储后端：
     * - 数据 < mmkvThreshold -> MMKV 存储
     * - 数据 >= mmkvThreshold -> 文件系统 + LZ4 压缩
     */
    fun put(key: String, entry: CacheEntry): Boolean {
        return try {
            // 序列化一次，然后直接传递 ByteArray 给底层存储
            val serialized = entry.serializeV2()
            doPut(key, serialized, entry.ttlMs)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to put entry: $key", e)
            false
        }
    }

    /**
     * 获取缓存条目
     *
     * 包含 CRC32 校验，校验失败返回 null（触发 cache miss 回源）
     */
    fun get(key: String): CacheEntry? {
        return try {
            doGet(key)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get entry: $key", e)
            missCount.incrementAndGet()
            null
        }
    }

    fun remove(key: String): Boolean {
        return try {
            doRemove(key)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to remove entry: $key", e)
            false
        }
    }

    fun contains(key: String): Boolean {
        val meta = indexMap[key] ?: return false
        if (isExpired(meta)) {
            doRemove(key)
            return false
        }
        return true
    }

    fun clear() {
        synchronized(this) {
            // 清除所有 MMKV 条目
            val allMmkvKeys = mmkv.allKeys()?.filter { it.startsWith(MMKV_KEY_PREFIX) } ?: emptyList()
            for (k in allMmkvKeys) {
                mmkv.remove(k)
                mmkv.remove("${k}_crc")
                mmkv.remove("${k}_size")
            }

            // 删除所有文件
            fileCacheDir.listFiles()?.forEach { it.delete() }

            // 清空内存索引
            indexMap.clear()

            // 重置统计
            totalSize.set(0)
            mmkvEntryCount.set(0)
            fileEntryCount.set(0)
            saveMetadata()

            Log.i(TAG, "Cache cleared completely")
        }
    }

    fun cleanExpired(): Int {
        var removed = 0
        synchronized(this) {
            val expiredKeys = indexMap.keys.filter { key -> isExpired(indexMap[key] ?: return@filter false) == true }
            for (key in expiredKeys) {
                if (doRemove(key)) removed++
            }
        }
        if (removed > 0) {
            Log.d(TAG, "Cleaned $removed expired entries")
        }
        return removed
    }

    /**
     * 刷新所有待处理的合并写入
     */
    fun flush() {
        writeCoalescer.flushAll { key, value ->
            if (value is CacheEntry) {
                putDirect(key, value)
            } else {
                true
            }
        }
        saveIndex()
        Log.d(TAG, "Flush completed")
    }

    fun size(): Long = totalSize.get()

    fun count(): Int = indexMap.size

    fun maxSize(): Long = maxSize

    // ==================== 扩展接口 ====================

    /**
     * 获取详细统计信息
     */
    fun getStats(): UnifiedDiskCacheStats {
        val total = hitCount.get() + missCount.get()
        return UnifiedDiskCacheStats(
            hitCount = hitCount.get(),
            missCount = missCount.get(),
            evictionCount = evictionCount.get(),
            totalSize = totalSize.get(),
            entryCount = indexMap.size,
            corruptionCount = corruptionCount.get(),
            mmkvEntryCount = mmkvEntryCount.get(),
            fileEntryCount = fileEntryCount.get(),
            maxSize = maxSize,
            usagePercent = if (maxSize > 0) totalSize.get().toDouble() / maxSize * 100 else 0.0,
            hitRate = if (total > 0) hitCount.get().toDouble() / total else 0.0
        )
    }

    /**
     * 验证并修复损坏的条目
     *
     * 扫描所有条目，对 CRC32 校验失败的条目执行删除。
     * 建议在应用启动时或空闲期调用。
     *
     * @return 修复的损坏条目数量
     */
    fun verifyAndRepair(): Int {
        var repaired = 0
        synchronized(this) {
            val keysToCheck = indexMap.keys.toList()
            for (key in keysToCheck) {
                val meta = indexMap[key] ?: continue
                if (!verifyCrc(key, meta)) {
                    Log.w(TAG, "Corruption detected, removing: $key")
                    forceRemove(key)
                    repaired++
                    corruptionCount.incrementAndGet()
                }
            }
        }
        if (repaired > 0) {
            Log.i(TAG, "Verified and repaired $repaired corrupted entries")
        }
        return repaired
    }

    /**
     * 关闭缓存，释放资源
     */
    fun shutdown() {
        // 停止后台任务
        verifyJob?.cancel()
        verifyJob = null

        // 最终 flush
        flush()

        // 持久化索引
        saveIndex()

        // 关闭写入合并器
        writeCoalescer.shutdown { key, _ ->
            // shutdown 期间不做额外写入
            true
        }

        // 取消协程
        scope.cancel()

        Log.i(TAG, "UnifiedDiskCache shutdown completed. Stats: hits=${hitCount.get()}, " +
                "misses=${missCount.get()}, evictions=${evictionCount.get()}, " +
                "corruptions=${corruptionCount.get()}")
    }

    // ==================== 内部实现 ====================

    /**
     * 直接存入（绕过 coalescer，用于内部调用）
     */
    private fun putDirect(key: String, entry: CacheEntry): Boolean {
        return try {
            val serialized = serializer.serialize(entry)
            doPutInternal(key, serialized, entry.ttlMs)
        } catch (e: Exception) {
            Log.e(TAG, "Direct put failed: $key", e)
            false
        }
    }

    /**
     * 带 coalescer 的 put 实现
     *
     * 直接接收已序列化的 data（ByteArray），避免重复序列化。
     * coalesce 回调中直接使用 data 而不是再次从 CacheEntry 序列化。
     */
    private fun doPut(key: String, data: ByteArray, ttlMs: Long): Boolean {
        // 通过写入合并器处理，直接传递已序列化的 data
        return writeCoalescer.coalesce(key, data) { k, v ->
            if (v is ByteArray) {
                doPutInternal(k, v, ttlMs)
            } else {
                true
            }
        }
    }

    /**
     * 实际执行存储操作
     */
    private fun doPutInternal(key: String, data: ByteArray, ttlMs: Long): Boolean {
        synchronized(this) {
            val now = System.currentTimeMillis()

            // 如果 key 已存在，先移除旧数据以释放空间
            val existingMeta = indexMap[key]
            if (existingMeta != null) {
                removePhysicalData(key, existingMeta)
                totalSize.addAndGet(-existingMeta.size.toLong())
            }

            // 检查容量
            val requiredSpace = data.size.toLong()
            if (totalSize.get() + requiredSpace > maxSize) {
                if (!evictToFit(requiredSpace)) {
                    Log.w(TAG, "Cannot make enough space for key: $key, required=$requiredSpace")
                    return false
                }
            }

            // 计算 CRC32
            val crcValue = if (config.enableCrcCheck) computeCrc32(data) else 0L

            // 选择存储后端并写入
            val storageType = if (data.size < config.mmkvThreshold) StorageType.MMKV else StorageType.FILE
            val success = when (storageType) {
                StorageType.MMKV -> writeToMmkv(key, data, crcValue)
                StorageType.FILE -> writeFile(key, data, crcValue)
            }

            if (!success) return false

            // 更新索引
            val meta = UnifiedCacheMeta(
                key = key,
                storageType = storageType,
                size = data.size,
                createdAt = now,
                ttlMs = ttlMs,
                crc32 = crcValue,
                lastAccessTime = now
            )
            indexMap[key] = meta
            totalSize.addAndGet(data.size.toLong())

            // 更新分区计数
            when (storageType) {
                StorageType.MMKV -> mmkvEntryCount.incrementAndGet()
                StorageType.FILE -> fileEntryCount.incrementAndGet()
            }

            saveMetadata()

            Log.d(TAG, "Put: $key, size=${data.size}, storage=$storageType, total=${totalSize.get()}")
            return true
        }
    }

    /**
     * 实际执行读取操作
     */
    private fun doGet(key: String): CacheEntry? {
        val meta = indexMap[key] ?: run {
            missCount.incrementAndGet()
            return null
        }

        // 检查过期
        if (isExpired(meta)) {
            doRemove(key)
            missCount.incrementAndGet()
            Log.d(TAG, "Entry expired: $key")
            return null
        }

        // 读取物理数据
        val data = readPhysicalData(key, meta) ?: run {
            // 读取失败，可能数据损坏
            corruptionCount.incrementAndGet()
            Log.w(TAG, "Failed to read or corrupted entry: $key")
            doRemove(key)
            missCount.incrementAndGet()
            return null
        }

        // CRC32 校验
        if (config.enableCrcCheck && !verifyCrcData(data, meta.crc32)) {
            corruptionCount.incrementAndGet()
            Log.w(TAG, "CRC32 mismatch for key: $key, expected=${meta.crc32}, removing")
            forceRemove(key)
            missCount.incrementAndGet()
            return null
        }

        // 更新访问时间和反序列化
        meta.lastAccessTime = System.currentTimeMillis()
        hitCount.incrementAndGet()

        val entry = serializer.deserialize(data)
        return if (entry is CacheEntry) entry.touch() else CacheEntry.create(entry, meta.ttlMs)
    }

    /**
     * 实际执行删除操作
     */
    private fun doRemove(key: String): Boolean {
        synchronized(this) {
            val meta = indexMap.remove(key) ?: return false
            removePhysicalData(key, meta)
            totalSize.addAndGet(-meta.size.toLong())

            when (meta.storageType) {
                StorageType.MMKV -> mmkvEntryCount.decrementAndGet()
                StorageType.FILE -> fileEntryCount.decrementAndGet()
            }

            saveMetadata()
            Log.d(TAG, "Removed: $key, size=${meta.size}")
            return true
        }
    }

    /**
     * 强制删除（不更新统计，仅清理物理数据）
     */
    private fun forceRemove(key: String) {
        val meta = indexMap.remove(key)
        if (meta != null) {
            removePhysicalData(key, meta)
            totalSize.addAndGet(-meta.size.toLong())
            when (meta.storageType) {
                StorageType.MMKV -> mmkvEntryCount.decrementAndGet()
                StorageType.FILE -> fileEntryCount.decrementAndGet()
            }
        }
    }

    // ==================== 物理存储操作 ====================

    private fun writeToMmkv(key: String, data: ByteArray, crc: Long): Boolean {
        return try {
            val internalKey = MMKV_KEY_PREFIX + key
            mmkv.encode(internalKey, data)
            mmkv.encode("${internalKey}_size", data.size)
            if (config.enableCrcCheck) {
                mmkv.encode("${internalKey}_crc", crc)
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "MMKV write failed: $key", e)
            false
        }
    }

    private fun writeFile(key: String, data: ByteArray, crc: Long): Boolean {
        return try {
            val sanitizedKey = sanitizeKey(key)
            val file = File(fileCacheDir, sanitizedKey)
            file.writeBytes(data)

            // 将 CRC 和元数据写入同目录下的 .meta 文件
            val metaFile = File(fileCacheDir, "$sanitizedKey.meta")
            val metaContent = buildString {
                appendLine(crc)
                appendLine(data.size)
                appendLine(System.currentTimeMillis())
            }
            metaFile.writeText(metaContent)

            true
        } catch (e: Exception) {
            Log.e(TAG, "File write failed: $key", e)
            false
        }
    }

    private fun readFromMmkv(key: String): ByteArray? {
        return try {
            val internalKey = MMKV_KEY_PREFIX + key
            mmkv.decodeBytes(internalKey)
        } catch (e: Exception) {
            Log.w(TAG, "MMKV read failed: $key", e)
            null
        }
    }

    private fun readFile(key: String): ByteArray? {
        return try {
            val sanitizedKey = sanitizeKey(key)
            val file = File(fileCacheDir, sanitizedKey)
            if (file.exists()) file.readBytes() else null
        } catch (e: Exception) {
            Log.w(TAG, "File read failed: $key", e)
            null
        }
    }

    private fun readPhysicalData(key: String, meta: UnifiedCacheMeta): ByteArray? {
        return when (meta.storageType) {
            StorageType.MMKV -> readFromMmkv(key)
            StorageType.FILE -> readFile(key)
        }
    }

    private fun removePhysicalData(key: String, meta: UnifiedCacheMeta) {
        try {
            when (meta.storageType) {
                StorageType.MMKV -> {
                    val internalKey = MMKV_KEY_PREFIX + key
                    mmkv.remove(internalKey)
                    mmkv.remove("${internalKey}_size")
                    mmkv.remove("${internalKey}_crc")
                }
                StorageType.FILE -> {
                    val sanitizedKey = sanitizeKey(key)
                    File(fileCacheDir, sanitizedKey).delete()
                    File(fileCacheDir, "$sanitizedKey.meta").delete()
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error removing physical data: $key", e)
        }
    }

    // ==================== CRC32 校验 ====================

    private fun computeCrc32(data: ByteArray): Long {
        synchronized(crc32) {
            crc32.reset()
            crc32.update(data)
            return crc32.value
        }
    }

    private fun verifyCrcData(data: ByteArray, expectedCrc: Long): Boolean {
        val actualCrc = computeCrc32(data)
        return actualCrc == expectedCrc
    }

    private fun verifyCrc(key: String, meta: UnifiedCacheMeta): Boolean {
        if (!config.enableCrcCheck) return true
        val data = readPhysicalData(key, meta) ?: return false
        return verifyCrcData(data, meta.crc32)
    }

    // ==================== 容量管理与淘汰 ====================

    /**
     * LRU 淘汰以腾出空间
     * 优先淘汰大对象和最久未访问的条目
     */
    private fun evictToFit(requiredSpace: Long): Boolean {
        if (indexMap.isEmpty()) return requiredSpace <= maxSize

        // 按最后访问时间排序（LRU），同时考虑大小（优先淘汰大的）
        val candidates = indexMap.values
            .sortedWith(compareBy<UnifiedCacheMeta> { it.lastAccessTime }.thenByDescending { it.size })

        var freed = 0L
        var evictedNum = 0

        for (meta in candidates) {
            if (totalSize.get() - freed + requiredSpace <= maxSize) break

            removePhysicalData(meta.key, meta)
            totalSize.addAndGet(-meta.size.toLong())
            indexMap.remove(meta.key)

            when (meta.storageType) {
                StorageType.MMKV -> mmkvEntryCount.decrementAndGet()
                StorageType.FILE -> fileEntryCount.decrementAndGet()
            }

            freed += meta.size
            evictedNum++
            evictionCount.incrementAndGet()

            Log.d(TAG, "Evicted: ${meta.key}, size=${meta.size}, idle=${System.currentTimeMillis() - meta.lastAccessTime}ms")
        }

        saveMetadata()
        Log.d(TAG, "Evicted $evictedNum entries, freed $freed bytes")
        return totalSize.get() + requiredSpace <= maxSize
    }

    // ==================== 索引维护 ====================

    /**
     * 从持久化存储加载索引
     *
     * 启动时从 MMKV 元数据和文件系统恢复索引信息。
     * 不读取实际数据，只重建 key -> meta 映射。
     */
    private fun loadIndex() {
        // 从 MMKV 加载元数据
        totalSize.set(mmkv.decodeLong(META_TOTAL_SIZE, 0))
        val savedCount = mmkv.decodeLong(META_ENTRY_COUNT, 0).toInt()

        // 扫描 MMKV 中的缓存条目
        val allMmkvKeys = mmkv.allKeys()?.filter {
            it.startsWith(MMKV_KEY_PREFIX) && !it.endsWith("_size") && !it.endsWith("_crc")
        } ?: emptyList()

        for (internalKey in allMmkvKeys) {
            val key = internalKey.removePrefix(MMKV_KEY_PREFIX)
            val size = mmkv.decodeInt("${internalKey}_size", 0)
            val crc = mmkv.decodeLong("${internalKey}_crc", 0)
            if (size > 0) {
                indexMap[key] = UnifiedCacheMeta(
                    key = key,
                    storageType = StorageType.MMKV,
                    size = size,
                    createdAt = 0,
                    ttlMs = Long.MAX_VALUE,
                    crc32 = crc
                )
                mmkvEntryCount.incrementAndGet()
            }
        }

        // 扫描文件系统中的缓存条目
        fileCacheDir.listFiles()?.filter { it.isFile && !it.name.endsWith(".meta") }?.forEach { file ->
            val key = file.nameWithoutExtension
            val size = file.length().toInt()
            if (size > 0 && !indexMap.containsKey(key)) {
                indexMap[key] = UnifiedCacheMeta(
                    key = key,
                    storageType = StorageType.FILE,
                    size = size,
                    createdAt = file.lastModified(),
                    ttlMs = Long.MAX_VALUE
                )
                fileEntryCount.incrementAndGet()
            }
        }

        Log.i(TAG, "Index loaded: ${indexMap.size} entries (mmkv=${mmkvEntryCount.get()}, files=${fileEntryCount.get()}), " +
                "totalSize=${totalSize.get()}")
    }

    /**
     * 持久化索引到 MMKV
     */
    private fun saveIndex() {
        saveMetadata()
    }

    private fun saveMetadata() {
        try {
            mmkv.encode(META_TOTAL_SIZE, totalSize.get())
            mmkv.encode(META_ENTRY_COUNT, indexMap.size.toLong())
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save metadata", e)
        }
    }

    // ==================== 辅助方法 ====================

    private fun isExpired(meta: UnifiedCacheMeta): Boolean {
        if (meta.ttlMs <= 0 || meta.ttlMs == Long.MAX_VALUE) return false
        return System.currentTimeMillis() - meta.createdAt > meta.ttlMs
    }

    private fun sanitizeKey(key: String): String {
        return key.replace(":", "_").replace("/", "_").replace("\\", "_")
            .replace(".", "_").replace(" ", "_")
    }

    // ==================== 后台任务 ====================

    private fun startBackgroundTasks() {
        if (config.enableAutoVerify) {
            verifyJob = scope.launch {
                delay(BACKGROUND_INIT_DELAY_MS)
                while (isActive) {
                    try {
                        delay(config.verifyIntervalMs)
                        verifyAndRepair()
                    } catch (e: Exception) {
                        Log.e(TAG, "Error in background verification", e)
                    }
                }
            }
        }
    }
}
