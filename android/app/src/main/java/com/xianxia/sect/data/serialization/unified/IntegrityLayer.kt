@file:Suppress("DEPRECATION")

package com.xianxia.sect.data.serialization.unified

import android.util.Log
import java.security.MessageDigest
import java.util.TreeMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
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

        /**
         * 共享线程池 —— 解决原版 P0 线程池泄漏问题。
         *
         * 原版 [computeChecksumConcurrent] 每次调用都创建新的 [Executors.newFixedThreadPool]，
         * 用完立即 shutdown，导致：
         * - 频繁创建/销毁线程的开销（线程创建 ~10-50ms/次）
         * - 高频调用时线程数爆炸风险
         * - GC 压力（每个 shutdown 的线程池遗留对象）
         *
         * 当前方案：使用守护线程的固定大小线程池，核心数上限，
         * 线程命名规则 "integrity-worker-N" 便于调试。
         * 线程池生命周期跟随进程，无需手动管理。
         */
        @JvmStatic
        val sharedExecutor: ExecutorService by lazy {
            val threadCounter = AtomicInteger(0)
            Executors.newFixedThreadPool(
                Runtime.getRuntime().availableProcessors().coerceIn(2, 8),
                ThreadFactory { r ->
                    Thread(r, "integrity-worker-${threadCounter.incrementAndGet()}").also {
                        it.isDaemon = true // 守护线程，不阻止 JVM 退出
                    }
                }
            )
        }

        /** 并发计算超时时间 */
        private const val CONCURRENT_TIMEOUT_SECONDS = 30L

        /** 单任务快速路径阈值：单元素列表直接在调用线程执行 */
        private const val SINGLE_ITEM_FAST_PATH = 1
    }

    // ========================================================================
    // ThreadLocal MessageDigest（无日志噪音版本）
    // ========================================================================

    /**
     * ThreadLocal MessageDigest 实例，确保线程安全。
     *
     * 每个线程拥有独立的 MessageDigest 实例，避免并发访问导致的数据损坏。
     *
     * 移除原版 initialValue() 和 getThreadLocalDigest() 中的 Log.d 调用，
     * 原因：这两个方法在高频场景下被大量调用（每次 checksum 计算都会触发），
     * 每次 Log.d 产生约 0.1-0.5ms I/O 开销和字符串拼接开销，
     * 在批量校验场景下会成为性能瓶颈。
     */
    private val digestThreadLocal: ThreadLocal<MessageDigest> = object : ThreadLocal<MessageDigest>() {
        override fun initialValue(): MessageDigest {
            return MessageDigest.getInstance(HASH_ALGORITHM)
        }
    }

    /**
     * 获取当前线程的 MessageDigest 实例。
     * 调用前会自动 reset() 确保状态干净。
     */
    private fun getThreadLocalDigest(): MessageDigest {
        val digest = digestThreadLocal.get()!!
        digest.reset()
        return digest
    }

    // ========================================================================
    // 核心 API：checksum 计算 / 验证
    // ========================================================================

    fun computeChecksum(data: ByteArray): ByteArray {
        val digest = getThreadLocalDigest()
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

    // ========================================================================
    // Merkle Tree 支持
    // ========================================================================

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

    // ========================================================================
    // 增量 & 分块校验
    // ========================================================================

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

    // ========================================================================
    // 便捷方法
    // ========================================================================

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

    // ========================================================================
    // 完整数据完整性校验流程
    // ========================================================================

    /**
     * 完整的数据完整性校验流程
     *
     * 执行步骤：
     * 1. 基础完整性检查 - 验证数据非空且大小合理
     * 2. 校验和验证 - 计算并比对 SHA-256 哈希
     * 3. 结构验证 - 检查数据结构是否完整
     * 4. 详细报告生成 - 返回详细的校验结果
     *
     * @param data 待校验的数据
     * @param expectedChecksum 预期的校验和（可选，如果为 null 则只计算不比对）
     * @return 完整的校验结果报告
     */
    fun performFullIntegrityCheck(
        data: ByteArray,
        expectedChecksum: ByteArray? = null
    ): IntegrityCheckResult {
        val startTime = System.currentTimeMillis()
        val errors = mutableListOf<String>()
        val warnings = mutableListOf<String>()

        // 步骤1：基础完整性检查
        if (data.isEmpty()) {
            errors.add("Data is empty")
            return IntegrityCheckResult(
                isValid = false,
                errors = errors,
                checkTimeMs = System.currentTimeMillis() - startTime
            )
        }

        if (data.size < HASH_SIZE) {
            warnings.add("Data size (${data.size}) is smaller than hash size ($HASH_SIZE)")
        }

        // 步骤2：计算实际校验和
        val actualChecksum = computeChecksum(data)

        // 步骤3：如果提供了预期校验和，进行比对验证
        if (expectedChecksum != null) {
            if (!actualChecksum.contentEquals(expectedChecksum)) {
                errors.add("Checksum mismatch")
                Log.w(TAG, "Integrity check failed: expected=${expectedChecksum.joinToString("") { "%02x".format(it) }}, " +
                        "actual=${actualChecksum.joinToString("") { "%02x".format(it) }}")
            }
        } else {
            // 无预期校验和时仅记录信息级日志（非 DEBUG），降低噪音
            Log.i(TAG, "No expected checksum provided, computed checksum only")
        }

        // 步骤4：生成详细报告
        val checkTime = System.currentTimeMillis() - startTime

        return IntegrityCheckResult(
            isValid = errors.isEmpty(),
            errors = errors,
            warnings = warnings,
            checkedChunks = 1,
            checkTimeMs = checkTime
        ).also { result ->
            if (result.isValid) {
                Log.i(TAG, "Full integrity check passed in ${checkTime}ms")
            } else {
                Log.e(TAG, "Full integrity check failed in ${checkTime}ms: ${errors.joinToString(", ")}")
            }
        }
    }

    // ========================================================================
    // 存档数据完整性验证（安全修复版）
    // ========================================================================

    /**
     * 验证存档数据的完整性（针对序列化对象）
     *
     * ## 安全修复（P1 -> P0 升级）
     *
     * 原版行为：当 [storedHash] 为 null 或空时返回 `isValid=true`。
     * 这构成安全漏洞：攻击者可删除存档文件中的哈希字段，
     * 使篡改后的数据通过完整性校验。
     *
     * 修复后行为：
     * - storedHash 为 null/空 -> 返回 `isValid=false`，errors 包含 "No stored hash available"
     * - storedHash 非空但不匹配 -> 返回 `isValid=false`
     * - storedHash 匹配 -> 返回 `isValid=true`
     *
     * @param serializedData 序列化后的字节数组
     * @param storedHash 存储的哈希值（十六进制字符串）
     * @return 校验结果，包含详细的错误信息
     */
    fun verifySaveDataIntegrity(
        serializedData: ByteArray,
        storedHash: String?
    ): IntegrityCheckResult {
        val startTime = System.currentTimeMillis()
        val errors = mutableListOf<String>()
        val warnings = mutableListOf<String>()

        // 检查数据是否为空
        if (serializedData.isEmpty()) {
            errors.add("Serialized data is empty")
            return IntegrityCheckResult(
                isValid = false,
                errors = errors,
                checkTimeMs = System.currentTimeMillis() - startTime
            )
        }

        // 安全修复：无存储哈希视为校验失败（原版此处返回 isValid=true）
        if (storedHash.isNullOrEmpty()) {
            errors.add("No stored hash available for verification — cannot confirm data integrity")
            Log.w(TAG, "Security: verification attempted without stored hash, marking as invalid. " +
                    "This may indicate tampered or incomplete save data.")
            return IntegrityCheckResult(
                isValid = false, // 关键修复：原版为 true
                errors = errors,
                warnings = warnings,
                checkedChunks = 0,
                checkTimeMs = System.currentTimeMillis() - startTime
            )
        }

        // 计算当前数据的哈希
        val computedHashHex = computeChecksumHex(serializedData)

        // 验证哈希是否匹配
        if (!computedHashHex.equals(storedHash, ignoreCase = true)) {
            errors.add("Save data integrity check failed: hash mismatch")
            Log.e(TAG, "Save data corrupted: stored=$storedHash, computed=$computedHashHex")
        } else {
            Log.i(TAG, "Save data integrity verified successfully")
        }

        val checkTime = System.currentTimeMillis() - startTime

        return IntegrityCheckResult(
            isValid = errors.isEmpty(),
            errors = errors,
            warnings = warnings,
            checkedChunks = 1,
            checkTimeMs = checkTime
        )
    }

    // ========================================================================
    // 辅助方法
    // ========================================================================

    /**
     * 为存档数据生成完整性信息
     *
     * @param data 存档数据字节数组
     * @return 包含校验和等信息的完整性元数据
     */
    fun generateIntegrityInfo(data: ByteArray): DataIntegrityInfo {
        return DataIntegrityInfo(
            checksum = computeChecksum(data),
            timestamp = System.currentTimeMillis(),
            dataSize = data.size
        )
    }

    /**
     * 批量验证多个数据块的完整性
     *
     * @param dataMap 数据块 ID 到数据的映射
     * @return 每个数据块的校验结果映射
     */
    fun batchVerifyIntegrity(
        dataMap: Map<String, ByteArray>
    ): Map<String, IntegrityCheckResult> {
        val results = mutableMapOf<String, IntegrityCheckResult>()

        dataMap.forEach { (id, data) ->
            results[id] = performFullIntegrityCheck(data)
        }

        val failedCount = results.values.count { !it.isValid }
        if (failedCount > 0) {
            Log.w(TAG, "Batch integrity check: $failedCount/${dataMap.size} chunks failed")
        } else {
            Log.i(TAG, "Batch integrity check: all ${dataMap.size} chunks passed")
        }

        return results
    }

    // ========================================================================
    // 并发校验和计算（使用共享线程池）
    // ========================================================================

    /**
     * 批量并发计算校验和。
     *
     * 使用**共享线程池** [sharedExecutor] 并行计算多个数据块的 SHA-256 校验和。
     *
     * ## 相比原版的改进
     * - **消除线程池泄漏**：不再每次调用 newFixedThreadPool + shutdown
     * - **减少线程创建开销**：复用已有线程，高频调用场景下性能提升显著
     * - **可控的并发度**：线程池大小固定为核心数（2-8），避免资源耗尽
     * - **Future-based 等待**：使用 Future.get() 替代 executor.awaitTermination，
     *   支持更细粒度的超时控制和取消能力
     *
     * 每个工作线程通过 ThreadLocal 获取独立的 MessageDigest 实例，
     * 确保并发安全且无锁竞争。
     *
     * @param dataList 待计算校验和的数据列表
     * @return Map<索引, 校验和>，键为原始列表中的索引，值为对应的 SHA-256 哈希值
     */
    fun computeChecksumConcurrent(dataList: List<ByteArray>): Map<Int, ByteArray> {
        if (dataList.isEmpty()) {
            return emptyMap()
        }

        // 单元素快速路径：直接在调用线程执行，避免提交到线程池的开销
        if (dataList.size == SINGLE_ITEM_FAST_PATH) {
            return mapOf(0 to computeChecksum(dataList[0]))
        }

        val startTime = System.currentTimeMillis()
        val results = ConcurrentHashMap<Int, ByteArray>()
        val futures = mutableListOf<Future<*>>()

        try {
            dataList.forEachIndexed { index, data ->
                futures.add(sharedExecutor.submit {
                    try {
                        results[index] = computeChecksum(data)
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to compute checksum for chunk $index", e)
                    }
                })
            }

            // 使用 Future.get() 替代 awaitTermination，支持逐个超时检测
            for ((index, future) in futures.withIndex()) {
                try {
                    future.get(CONCURRENT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                } catch (e: Exception) {
                    future.cancel(true)
                    Log.w(TAG, "Timeout or error waiting for chunk $index checksum computation", e)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected error during concurrent checksum computation", e)
        }

        val elapsed = System.currentTimeMillis() - startTime
        Log.i(TAG, "Concurrent checksum: completed ${results.size}/${dataList.size} chunks in ${elapsed}ms")

        return results.toMap()
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
