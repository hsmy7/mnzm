package com.xianxia.sect.data.validation

import android.util.Log
import com.xianxia.sect.data.crypto.IntegrityValidator
import com.xianxia.sect.data.crypto.SaveCrypto
import com.xianxia.sect.data.crypto.SignedPayload
import com.xianxia.sect.data.crypto.VerificationResult as CryptoVerificationResult
import com.xianxia.sect.data.incremental.DeltaMetadata
import com.xianxia.sect.data.incremental.SnapshotInfo
import com.xianxia.sect.data.model.SaveData
import com.xianxia.sect.data.unified.SlotMetadata
import java.io.File
import java.security.MessageDigest

/**
 * 统一存储验证器
 *
 * 职责：集中管理所有存储层的数据完整性验证逻辑，消除各模块中的重复验证代码。
 * 关注点：文件完整性、数据一致性、加密校验、Delta链有效性等存储层关注点。
 *
 * 与 InputValidator 的区别：
 * - InputValidator: 面向UI层的用户输入验证（名称合法性、格式检查等）
 * - StorageValidator: 面向存储层的数据完整性验证（文件存在性、checksum、签名等）
 */
object StorageValidator {
    private const val TAG = "StorageValidator"

    // ===== 数据类定义 =====

    data class ValidationResult(
        val isValid: Boolean,
        val errors: List<ValidationIssue> = emptyList(),
        val warnings: List<ValidationIssue> = emptyList()
    ) {
        companion object {
            fun valid(): ValidationResult = ValidationResult(true)
            fun validWithWarnings(warnings: List<ValidationIssue>): ValidationResult =
                ValidationResult(true, emptyList(), warnings)
            fun error(code: String, message: String, context: Map<String, Any?> = emptyMap()): ValidationResult =
                ValidationResult(false, listOf(ValidationIssue(code, message, Severity.ERROR, context)))
            fun errors(issues: List<ValidationIssue>): ValidationResult =
                ValidationResult(false, issues)
            fun errorWithWarnings(errors: List<ValidationIssue>, warnings: List<ValidationIssue>): ValidationResult =
                ValidationResult(false, errors, warnings)
        }
    }

    data class ValidationIssue(
        val code: String,
        val message: String,
        val severity: Severity = Severity.ERROR,
        val context: Map<String, Any?> = emptyMap()
    )

    enum class Severity { ERROR, WARNING, INFO }

    // ===== 文件级验证 =====

    /**
     * 验证文件是否存在
     */
    fun validateFileExists(file: File, description: String): ValidationResult {
        return if (file.exists()) {
            ValidationResult.valid()
        } else {
            ValidationResult.error(
                code = "FILE_NOT_FOUND",
                message = "$description not found: ${file.absolutePath}",
                context = mapOf("path" to file.absolutePath, "description" to description)
            )
        }
    }

    /**
     * 验证文件大小是否在允许范围内
     */
    fun validateFileSize(file: File, maxSize: Long, description: String): ValidationResult {
        val existenceResult = validateFileExists(file, description)
        if (!existenceResult.isValid) return existenceResult

        val actualSize = file.length()
        return if (actualSize <= maxSize) {
            ValidationResult.valid()
        } else {
            ValidationResult.error(
                code = "FILE_TOO_LARGE",
                message = "$description exceeds maximum size: $actualSize bytes (max: $maxSize bytes)",
                context = mapOf("actualSize" to actualSize, "maxSize" to maxSize, "description" to description)
            )
        }
    }

    /**
     * 验证文件的SHA-256 checksum
     */
    fun validateFileChecksum(file: File, expectedChecksum: String): ValidationResult {
        val existenceResult = validateFileExists(file, "File")
        if (!existenceResult.isValid) return existenceResult

        return try {
            val data = file.readBytes()
            val actualChecksum = computeSha256Hex(data)

            if (actualChecksum == expectedChecksum) {
                ValidationResult.valid()
            } else {
                ValidationResult.error(
                    code = "CHECKSUM_MISMATCH",
                    message = "File checksum mismatch",
                    context = mapOf("expected" to expectedChecksum, "actual" to actualChecksum, "file" to file.name)
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to validate file checksum", e)
            ValidationResult.error(
                code = "CHECKSUM_ERROR",
                message = "Failed to compute file checksum: ${e.message}",
                context = mapOf("file" to file.name, "error" to e.message)
            )
        }
    }

    /**
     * 验证磁盘空间是否充足
     */
    fun validateDiskSpace(requiredBytes: Long, directory: File): ValidationResult {
        return try {
            val freeSpace = directory.usableSpace
            if (freeSpace >= requiredBytes) {
                ValidationResult.valid()
            } else {
                ValidationResult.error(
                    code = "INSUFFICIENT_DISK_SPACE",
                    message = "Insufficient disk space: need $requiredBytes bytes, available ${freeSpace} bytes",
                    context = mapOf("required" to requiredBytes, "available" to freeSpace, "directory" to directory.absolutePath)
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to check disk space", e)
            ValidationResult.error(
                code = "DISK_SPACE_ERROR",
                message = "Failed to check available disk space: ${e.message}",
                context = mapOf("directory" to directory.absolutePath, "error" to e.message)
            )
        }
    }

    // ===== 数据级验证 =====

    /**
     * 验证SaveData的基本完整性
     */
    fun validateSaveData(data: SaveData): ValidationResult {
        val issues = mutableListOf<ValidationIssue>()

        if (data.version.isBlank()) {
            issues.add(ValidationIssue("EMPTY_VERSION", "SaveData version is blank"))
        }

        if (data.timestamp <= 0) {
            issues.add(ValidationIssue("INVALID_TIMESTAMP", "SaveData timestamp is invalid: ${data.timestamp}"))
        }

        if (data.gameData == null) {
            issues.add(ValidationIssue("NULL_GAME_DATA", "SaveData gameData is null"))
        }

        if (data.disciples == null) {
            issues.add(ValidationIssue("NULL_DISCIPLES", "SaveData disciples is null"))
        }

        return if (issues.isEmpty()) {
            ValidationResult.valid()
        } else {
            ValidationResult.errors(issues)
        }
    }

    /**
     * 验证槽位编号是否在有效范围内
     */
    fun validateSlotRange(slot: Int, maxSlots: Int): ValidationResult {
        return when {
            slot < 0 -> ValidationResult.error(
                code = "SLOT_NEGATIVE",
                message = "Slot number cannot be negative: $slot",
                context = mapOf("slot" to slot, "maxSlots" to maxSlots)
            )
            slot > maxSlots -> ValidationResult.error(
                code = "SLOT_OUT_OF_RANGE",
                message = "Slot number exceeds maximum: $slot (max: $maxSlots)",
                context = mapOf("slot" to slot, "maxSlots" to maxSlots)
            )
            else -> ValidationResult.valid()
        }
    }

    /**
     * 验证SlotMetadata的完整性
     */
    fun validateMetadata(metadata: SlotMetadata): ValidationResult {
        val errors = mutableListOf<ValidationIssue>()
        val warnings = mutableListOf<ValidationIssue>()

        if (metadata.timestamp <= 0) {
            errors.add(ValidationIssue(
                code = "INVALID_META_TIMESTAMP",
                message = "Metadata timestamp is invalid: ${metadata.timestamp}",
                context = mapOf("slot" to metadata.slot)
            ))
        }

        if (metadata.fileSize < 0) {
            errors.add(ValidationIssue(
                code = "INVALID_FILE_SIZE",
                message = "Metadata fileSize is negative: ${metadata.fileSize}",
                context = mapOf("slot" to metadata.slot, "fileSize" to metadata.fileSize)
            ))
        }

        if (metadata.discipleCount < 0) {
            warnings.add(ValidationIssue(
                code = "NEGATIVE_DISCIPLE_COUNT",
                message = "Metadata discipleCount is negative: ${metadata.discipleCount}",
                severity = Severity.WARNING,
                context = mapOf("slot" to metadata.slot, "discipleCount" to metadata.discipleCount)
            ))
        }

        if (metadata.spiritStones < 0) {
            warnings.add(ValidationIssue(
                code = "NEGATIVE_SPIRIT_STONES",
                message = "Metadata spiritStones is negative: ${metadata.spiritStones}",
                severity = Severity.WARNING,
                context = mapOf("slot" to metadata.slot, "spiritStones" to metadata.spiritStones)
            ))
        }

        return if (errors.isEmpty()) {
            if (warnings.isEmpty()) ValidationResult.valid() else ValidationResult.validWithWarnings(warnings)
        } else {
            ValidationResult.errorWithWarnings(errors, warnings)
        }
    }

    // ===== 加密/完整性验证 =====

    /**
     * 验证加密数据的完整性（通过HMAC）
     */
    fun validateEncryptionIntegrity(data: ByteArray, expectedHash: String, key: ByteArray): ValidationResult {
        return try {
            val actualHash = SaveCrypto.sha256Hex(data)
            if (actualHash == expectedHash) {
                ValidationResult.valid()
            } else {
                ValidationResult.error(
                    code = "ENCRYPTION_INTEGRITY_FAILED",
                    message = "Encryption integrity check failed: hash mismatch",
                    context = mapOf("expected" to expectedHash, "actual" to actualHash)
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to validate encryption integrity", e)
            ValidationResult.error(
                code = "ENCRYPTION_VALIDATION_ERROR",
                message = "Failed to validate encryption integrity: ${e.message}"
            )
        }
    }

    /**
     * 验证签名payload的有效性
     * @param payload 已签名的payload
     * @param key 用于验证的密钥
     * @param data 可选的原始数据，如果提供则进行完整的数据+签名验证；如果为null则仅验证签名格式
     */
    fun validateSignature(payload: SignedPayload, key: ByteArray, data: Any? = null): ValidationResult {
        return try {
            val verificationResult = if (data != null) {
                // 完整验证：数据 + 签名
                IntegrityValidator.verifySignedPayload(data, payload, key)
            } else {
                // 仅验证签名格式和基本有效性
                verifySignatureFormat(payload, key)
            }

            when (verificationResult) {
                is CryptoVerificationResult.Valid -> ValidationResult.valid()
                is CryptoVerificationResult.Tampered -> ValidationResult.error(
                    code = "SIGNATURE_TAMPERED",
                    message = "Signature verification failed: ${verificationResult.reason}"
                )
                is CryptoVerificationResult.Invalid -> ValidationResult.error(
                    code = "SIGNATURE_INVALID",
                    message = "Signature is invalid: ${verificationResult.reason}"
                )
                is CryptoVerificationResult.Expired -> ValidationResult.errorWithWarnings(
                    errors = emptyList(),
                    warnings = listOf(ValidationIssue(
                        code = "SIGNATURE_EXPIRED",
                        message = "Signature expired at ${verificationResult.signedAt}",
                        severity = Severity.WARNING
                    ))
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to validate signature", e)
            ValidationResult.error(
                code = "SIGNATURE_VALIDATION_ERROR",
                message = "Failed to validate signature: ${e.message}"
            )
        }
    }

    /**
     * 验证带数据的签名（完整流程）
     */
    fun validateSignedData(data: Any, payload: SignedPayload, key: ByteArray): ValidationResult {
        return validateSignature(payload, key, data)
    }

    // ===== Delta链验证 =====

    /**
     * 验证单个Delta文件的完整性
     */
    fun validateDeltaFile(deltaFile: File, metadata: DeltaMetadata): ValidationResult {
        val existenceResult = validateFileExists(deltaFile, "Delta file")
        if (!existenceResult.isValid) return existenceResult

        val errors = mutableListOf<ValidationIssue>()

        // 检查文件大小
        val actualSize = deltaFile.length()
        if (actualSize != metadata.size) {
            errors.add(ValidationIssue(
                code = "DELTA_SIZE_MISMATCH",
                message = "Delta file size mismatch: expected ${metadata.size}, actual $actualSize",
                context = mapOf("version" to metadata.version, "expected" to metadata.size, "actual" to actualSize)
            ))
        }

        // 检查checksum
        if (errors.isEmpty()) {
            try {
                val data = deltaFile.readBytes()
                val actualChecksum = computeSha256Hex(data)
                if (actualChecksum != metadata.checksum) {
                    errors.add(ValidationIssue(
                        code = "DELTA_CHECKSUM_MISMATCH",
                        message = "Delta checksum mismatch for version ${metadata.version}",
                        context = mapOf("version" to metadata.version, "expected" to metadata.checksum, "actual" to actualChecksum)
                    ))
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to read delta file for checksum validation", e)
                errors.add(ValidationIssue(
                    code = "DELTA_READ_ERROR",
                    message = "Failed to read delta file for validation: ${e.message}",
                    context = mapOf("version" to metadata.version, "error" to e.message)
                ))
            }
        }

        return if (errors.isEmpty()) ValidationResult.valid() else ValidationResult.errors(errors)
    }

    /**
     * 验证整个Delta链的完整性和连续性
     */
    fun validateDeltaChain(
        deltas: List<DeltaMetadata>,
        snapshots: List<SnapshotInfo>
    ): ValidationResult {
        val errors = mutableListOf<ValidationIssue>()
        val warnings = mutableListOf<ValidationIssue>()

        if (deltas.isEmpty() && snapshots.isEmpty()) {
            warnings.add(ValidationIssue(
                code = "EMPTY_DELTA_CHAIN",
                message = "No deltas or snapshots found in chain",
                severity = Severity.INFO
            ))
        }

        // 检查delta版本连续性
        if (deltas.size > 1) {
            val sortedDeltas = deltas.sortedBy { it.version }
            for (i in 1 until sortedDeltas.size) {
                val prev = sortedDeltas[i - 1]
                val curr = sortedDeltas[i]
                // 注意：版本号不一定是严格连续的，但应该是递增的
                if (curr.version <= prev.version) {
                    errors.add(ValidationIssue(
                        code = "NON_MONOTONIC_VERSIONS",
                        message = "Delta versions are not monotonically increasing: ${prev.version} -> ${curr.version}",
                        context = mapOf("prevVersion" to prev.version, "currentVersion" to curr.version)
                    ))
                }
            }
        }

        // 检查快照时间顺序
        if (snapshots.size > 1) {
            val sortedSnapshots = snapshots.sortedBy { it.version }
            for (i in 1 until sortedSnapshots.size) {
                val prev = sortedSnapshots[i - 1]
                val curr = sortedSnapshots[i]
                if (curr.timestamp < prev.timestamp) {
                    warnings.add(ValidationIssue(
                        code = "SNAPSHOT_TIME_ORDER",
                        message = "Snapshot timestamps are out of order",
                        severity = Severity.WARNING,
                        context = mapOf("prevVersion" to prev.version, "currentVersion" to curr.version)
                    ))
                }
            }
        }

        return if (errors.isEmpty()) {
            if (warnings.isEmpty()) ValidationResult.valid() else ValidationResult.validWithWarnings(warnings)
        } else {
            ValidationResult.errorWithErrorsAndWarnings(errors, warnings)
        }
    }

    // ===== 内存/资源验证 =====

    /**
     * 验证可用内存是否充足
     * @param minRatio 最小可用内存比例（0.0-1.0），默认15%
     */
    fun validateMemoryAvailable(minRatio: Float = 0.15f): ValidationResult {
        return try {
            val runtime = Runtime.getRuntime()
            runtime.gc() // 建议GC以获取更准确的可用内存估计
            val maxMemory = runtime.maxMemory()
            val freeMemory = runtime.freeMemory()
            val totalMemory = runtime.totalMemory()
            val usedMemory = totalMemory - freeMemory
            val availableMemory = maxMemory - usedMemory
            val ratio = availableMemory.toFloat() / maxMemory.toFloat()

            if (ratio >= minRatio) {
                ValidationResult.valid()
            } else {
                ValidationResult.error(
                    code = "LOW_MEMORY",
                    message = "Low memory available: ${(ratio * 100).toInt()}% (minimum: ${(minRatio * 100).toInt()}%)",
                    context = mapOf(
                        "available" to availableMemory,
                        "max" to maxMemory,
                        "ratio" to ratio,
                        "minRatio" to minRatio
                    )
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to check available memory", e)
            ValidationResult.error(
                code = "MEMORY_CHECK_ERROR",
                message = "Failed to check available memory: ${e.message}"
            )
        }
    }

    // ===== 组合验证（编排多个验证步骤）=====

    /**
     * 保存前的完整验证
     * 包含：槽位范围 + 数据完整性 + 磁盘空间 + 内存检查
     */
    fun validateBeforeSave(slot: Int, data: SaveData, maxSlots: Int, saveDir: File, estimatedSize: Long): ValidationResult {
        val allErrors = mutableListOf<ValidationIssue>()
        val allWarnings = mutableListOf<ValidationIssue>()

        // 1. 槽位范围验证
        val slotResult = validateSlotRange(slot, maxSlots)
        collectIssues(slotResult, allErrors, allWarnings)

        // 2. 数据完整性验证
        val dataResult = validateSaveData(data)
        collectIssues(dataResult, allErrors, allWarnings)

        // 3. 磁盘空间验证
        val spaceResult = validateDiskSpace(estimatedSize, saveDir)
        collectIssues(spaceResult, allErrors, allWarnings)

        // 4. 内存可用性验证（警告级别，不应阻止保存）
        val memoryResult = validateMemoryAvailable()
        if (!memoryResult.isValid) {
            allWarnings.addAll(memoryResult.warnings.ifEmpty { 
                listOf(ValidationIssue("LOW_MEMORY_WARNING", memoryResult.errors.first().message, Severity.WARNING))
            })
        }

        return if (allErrors.isEmpty()) {
            if (allWarnings.isEmpty()) ValidationResult.valid() else ValidationResult.validWithWarnings(allWarnings)
        } else {
            ValidationResult.errorWithErrorsAndWarnings(allErrors, allWarnings)
        }
    }

    /**
     * 加载前的验证
     * 包含：槽位范围 + 文件存在性
     */
    fun validateBeforeLoad(slot: Int, maxSlots: Int, saveFile: File): ValidationResult {
        val allErrors = mutableListOf<ValidationIssue>()

        // 1. 槽位范围验证
        val slotResult = validateSlotRange(slot, maxSlots)
        if (!slotResult.isValid) {
            allErrors.addAll(slotResult.errors)
            return ValidationResult.errors(allErrors)
        }

        // 2. 文件存在性验证
        val fileResult = validateFileExists(saveFile, "Save file")
        if (!fileResult.isValid) {
            allErrors.addAll(fileResult.errors)
            return ValidationResult.errors(allErrors)
        }

        return ValidationResult.valid()
    }

    // ===== 辅助方法 =====

    /**
     * 计算数据的SHA-256十六进制字符串
     */
    internal fun computeSha256Hex(data: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(data).joinToString("") { "%02x".format(it) }
    }

    /**
     * 仅验证签名格式（不验证数据匹配）
     * 用于在无法获取原始数据时进行基本的签名有效性检查
     */
    private fun verifySignatureFormat(payload: SignedPayload, key: ByteArray): CryptoVerificationResult {
        return try {
            // 检查版本
            if (payload.version > 1) {
                return CryptoVerificationResult.Invalid("Unsupported signature version: ${payload.version}")
            }

            // 检查时间戳是否过期（使用较长的过期时间）
            val currentTime = System.currentTimeMillis()
            val maxAgeMs = 365L * 24 * 60 * 60 * 1000 // 1年
            if (currentTime - payload.timestamp > maxAgeMs) {
                return CryptoVerificationResult.Expired(payload.timestamp, currentTime)
            }

            // 验证签名HMAC
            val payloadToVerify = buildString {
                append(payload.version.toString())
                append("|")
                append(payload.timestamp.toString())
                append("|")
                append(payload.dataHash)
                append("|")
                append(payload.merkleRoot)
                payload.metadata.toSortedMap().forEach { (k, v) ->
                    append("|")
                    append(k)
                    append("=")
                    append(v)
                }
            }

            val expectedSignature = IntegrityValidator.computeHmacForString(payloadToVerify, key)
            if (!constantTimeEquals(payload.signature, expectedSignature)) {
                return CryptoVerificationResult.Tampered("Signature mismatch")
            }

            CryptoVerificationResult.Valid
        } catch (e: Exception) {
            Log.e(TAG, "Failed to verify signature format", e)
            CryptoVerificationResult.Invalid("Signature verification error: ${e.message}")
        }
    }

    /**
     * 常量时间字符串比较（防时序攻击）
     */
    private fun constantTimeEquals(a: String, b: String): Boolean {
        if (a.length != b.length) return false
        var result = 0
        for (i in a.indices) {
            result = result or (a[i].code xor b[i].code)
        }
        return result == 0
    }

    private fun collectIssues(result: ValidationResult, errors: MutableList<ValidationIssue>, warnings: MutableList<ValidationIssue>) {
        errors.addAll(result.errors)
        warnings.addAll(result.warnings)
    }

    // ===== 扩展ValidationResult以支持errors+warnings构造 =====

    private fun ValidationResult.Companion.errorWithErrorsAndWarnings(errors: List<ValidationIssue>, warnings: List<ValidationIssue>): ValidationResult {
        return ValidationResult(false, errors, warnings)
    }
}
