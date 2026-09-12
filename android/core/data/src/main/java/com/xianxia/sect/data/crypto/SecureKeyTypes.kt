package com.xianxia.sect.data.crypto

import java.util.concurrent.atomic.AtomicLong

/**
 * 密钥恢复决策枚举
 * 用于在密钥丢失时让用户选择恢复策略
 */
enum class KeyRecoveryDecision {
    IMPORT_TOKEN,       // 用户选择导入recovery token
    GENERATE_NEW_KEY,   // 用户确认生成新密钥（已知旧存档将永久丢失）
    RETRY,              // 重试读取密钥文件
    CANCEL              // 取消操作，抛出异常阻止自动恢复
}

/**
 * 密钥恢复回调接口
 * 当主密钥和备份都不可读时触发，由调用方决定如何处理
 *
 * 使用场景：
 * - 首次安装后数据迁移
 * - 设备更换后的密钥恢复
 * - 数据损坏时的用户确认
 */
interface KeyRecoveryCallback {
    /**
     * 当密钥需要恢复时调用
     * 注意：此方法设计为非 suspend 函数，因为调用方 getOrCreateDerivedKey() 本身不是协程上下文。
     * 如需在实现中执行异步操作（如弹 UI 对话框），由实现方自行处理（例如通过事件机制）。
     *
     * @param reason 密钥不可用的原因描述
     * @return 用户的恢复决策
     */
    fun onKeyRecoveryRequired(reason: String): KeyRecoveryDecision
}

sealed class KeyRecoveryResult {
    data class Success(val key: ByteArray, val message: String) : KeyRecoveryResult()
    data class Failure(val error: String) : KeyRecoveryResult()
}

sealed class KeyReadResult {
    data class Success(val data: ByteArray) : KeyReadResult()
    object FileNotFound : KeyReadResult()
    object PermissionDenied : KeyReadResult()
    data class Error(val exception: Exception) : KeyReadResult()
}

enum class KeyError {
    FILE_NOT_FOUND,
    PERMISSION_DENIED,
    CORRUPTED_DATA,
    BACKUP_RECOVERY_FAILED,
    DISK_FULL,
    FILE_SYSTEM_ERROR,
    UNKNOWN_ERROR
}

class KeyIntegrityException(message: String, cause: Throwable? = null) : Exception(message, cause)
class KeyPermissionException(message: String, cause: Throwable? = null) : Exception(message, cause)
class KeyFileSystemException(message: String, cause: Throwable? = null) : Exception(message, cause)

object KeyManagerMetrics {
    val permissionFixAttempts = AtomicLong(0)
    val permissionFixSuccesses = AtomicLong(0)
    val backupRecoveries = AtomicLong(0)
    val keyRegenerations = AtomicLong(0)
    val diskFullErrors = AtomicLong(0)
    val fileSystemErrors = AtomicLong(0)

    fun reset() {
        permissionFixAttempts.set(0)
        permissionFixSuccesses.set(0)
        backupRecoveries.set(0)
        keyRegenerations.set(0)
        diskFullErrors.set(0)
        fileSystemErrors.set(0)
    }
}
