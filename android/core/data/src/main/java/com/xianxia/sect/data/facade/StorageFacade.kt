package com.xianxia.sect.data.facade

import android.util.Log
import com.xianxia.sect.core.model.MailEntity
import com.xianxia.sect.data.backup.SaveFileManager
import com.xianxia.sect.data.concurrent.SlotLockManager
import com.xianxia.sect.data.engine.StorageEngine
import com.xianxia.sect.data.engine.getMails
import com.xianxia.sect.data.engine.replaceMails
import com.xianxia.sect.data.model.SaveData

import com.xianxia.sect.data.unified.SaveError
import com.xianxia.sect.data.unified.SaveInfo
import com.xianxia.sect.data.unified.SaveResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

// ==================== 扩展函数 ====================

/** StorageError → SaveError 错误码映射：未知错误码归 UNKNOWN */
private val STORAGE_ERROR_TO_SAVE_ERROR: Map<com.xianxia.sect.data.result.StorageError, SaveError> = mapOf(
    com.xianxia.sect.data.result.StorageError.INVALID_SLOT to SaveError.INVALID_SLOT,
    com.xianxia.sect.data.result.StorageError.SLOT_EMPTY to SaveError.SLOT_EMPTY,
    com.xianxia.sect.data.result.StorageError.SLOT_CORRUPTED to SaveError.SLOT_CORRUPTED,
    com.xianxia.sect.data.result.StorageError.SAVE_FAILED to SaveError.SAVE_FAILED,
    com.xianxia.sect.data.result.StorageError.LOAD_FAILED to SaveError.LOAD_FAILED,
    com.xianxia.sect.data.result.StorageError.DELETE_FAILED to SaveError.DELETE_FAILED,
    com.xianxia.sect.data.result.StorageError.IO_ERROR to SaveError.IO_ERROR,
    com.xianxia.sect.data.result.StorageError.OUT_OF_MEMORY to SaveError.OUT_OF_MEMORY,
    com.xianxia.sect.data.result.StorageError.ENCRYPTION_ERROR to SaveError.ENCRYPTION_ERROR,
    com.xianxia.sect.data.result.StorageError.DECRYPTION_ERROR to SaveError.DECRYPTION_ERROR,
    com.xianxia.sect.data.result.StorageError.KEY_DERIVATION_ERROR to SaveError.KEY_DERIVATION_ERROR,
    com.xianxia.sect.data.result.StorageError.TIMEOUT to SaveError.TIMEOUT,
    com.xianxia.sect.data.result.StorageError.WAL_ERROR to SaveError.WAL_ERROR,
    com.xianxia.sect.data.result.StorageError.DATABASE_ERROR to SaveError.DATABASE_ERROR,
    com.xianxia.sect.data.result.StorageError.TRANSACTION_FAILED to SaveError.TRANSACTION_FAILED
)

fun <T> com.xianxia.sect.data.result.StorageResult<T>.toUnifiedResult(): SaveResult<T> = when (this) {
    is com.xianxia.sect.data.result.StorageResult.Success -> SaveResult.success(data)
    is com.xianxia.sect.data.result.StorageResult.Skipped -> SaveResult.failure(
        SaveError.SAVE_FAILED, message
    )
    is com.xianxia.sect.data.result.StorageResult.Failure -> SaveResult.failure(
        STORAGE_ERROR_TO_SAVE_ERROR[error] ?: SaveError.UNKNOWN,
        message,
        cause
    )
}

// ==================== StorageFacade ====================

@Singleton
class StorageFacade @Inject constructor(
    private val engine: StorageEngine,
    private val lockManager: SlotLockManager,
    private val saveFileManager: SaveFileManager,
    private val accountSpace: com.xianxia.sect.data.account.AccountSpaceManager
) {
    companion object {
        private const val TAG = "StorageFacade"
    }

    private val isInitialized = AtomicBoolean(false)
    private val isShuttingDown = AtomicBoolean(false)

    // ==================== 生命周期方法 ====================

    @Suppress("TooGenericExceptionCaught", "ThrowsCount") // 前者: 防御兜底异常源不可枚举; 后者: 启动清理段取消穿透
    // rethrow 刻意独立抛出(结构化取消语义), 非疏忽超标
    suspend fun initialize(): SaveResult<Unit> {
        if (isInitialized.get()) {
            Log.d(TAG, "StorageFacade already initialized")
            return SaveResult.success(Unit)
        }

        return try {
            engine.startMaintenance()
            // 初始化 SaveFileManager 双缓冲备份（.sav/.bak）——落在当前账号数据空间
            // （无活跃空间时 requireRoot 抛 IllegalStateException，与建库同门控）
            saveFileManager.initialize(accountSpace.requireRoot())
            // 启动时清理崩溃遗留 .tmp 文件与孤儿 .bak（.sav 永不清——
            // 它是 DB 损坏时的恢复点）
            try {
                saveFileManager.cleanupOrphanedTmp()
                saveFileManager.cleanExpiredBackups()
            } catch (e: CancellationException) {
                throw e // 取消穿透: 初始化取消时中止; 两清理为阻塞 IO 无挂起点, 分支防未来挂起点引入
            } catch (e: Exception) {
                Log.w(TAG, "启动清理备份目录失败（非阻断）", e)
            }

            // Database integrity check: verify the database can be read.
            // If Room schema validation fails (e.g., FK mismatch on orphaned sub-tables),
            // this throws immediately, giving a clear error instead of silent failure.
            try {
                val metadata = withContext(Dispatchers.IO) { engine.readMetadata() }
                Log.d(TAG, "Database integrity check passed (sect: ${metadata?.sectName ?: "empty"})")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Database integrity check FAILED -- database may have schema mismatch", e)
                return SaveResult.failure(
                    SaveError.DATABASE_ERROR,
                    "Database schema is invalid or corrupted: ${e.message}",
                    e
                )
            }

            isInitialized.set(true)

            Log.i(TAG, "StorageFacade initialized successfully")
            SaveResult.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "StorageFacade initialization failed", e)
            SaveResult.failure(SaveError.IO_ERROR, e.message ?: "Initialization failed", e)
        }
    }

    @Suppress("TooGenericExceptionCaught") // 防御兜底: 异常源跨IO/SDK不可枚举, 降级继续+日志留痕, 非静默吞噬
    fun shutdown() {
        if (!isShuttingDown.compareAndSet(false, true)) {
            Log.w(TAG, "StorageFacade shutdown already in progress")
            return
        }

        try {
            Log.i(TAG, "StorageFacade shutting down")
            engine.stopMaintenance()
            engine.shutdown()
            isInitialized.set(false)
            Log.i(TAG, "StorageFacade shutdown completed")
        } catch (e: Exception) {
            Log.e(TAG, "Error during StorageFacade shutdown", e)
        }
    }

    // ==================== 异步存取方法 ====================

    @Suppress("TooGenericExceptionCaught") // 异常显式包装进 Result 上抛, 非静默吞噬
    suspend fun save(data: SaveData): SaveResult<Unit> {
        ensureInitialized()

        return try {
            val result = engine.save(data)

            if (result.isSuccess) {
                // 后置步骤（.sav 镜像 / .bak 备份）降级原因必须带给调用方
                // —— UI 据此如实提示，不得只报"游戏保存成功"（审计 §12-C）
                SaveResult.successWithWarning(Unit, result.getOrNull()?.postSaveWarning)
            } else {
                result.toUnifiedResult().map { }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Save failed", e)
            SaveResult.failure(SaveError.SAVE_FAILED, e.message ?: "Save failed", e)
        }
    }

    @Suppress("TooGenericExceptionCaught") // 异常显式包装进 Result 上抛, 非静默吞噬
    suspend fun load(): SaveResult<SaveData> {
        ensureInitialized()

        return try {
            val result = engine.load()

            result.toUnifiedResult()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Load failed", e)
            SaveResult.failure(SaveError.LOAD_FAILED, e.message ?: "Load failed", e)
        }
    }

    // ==================== 邮件快照方法（SR-1） ====================

    /**
     * 槽位全量邮件读取（保存编排注入 SaveData.mails 用）。
     * 如实返回表内现状，不做任何过期清理（30 天删除逻辑归 SR-5）。
     * 失败异常直接上抛——邮件快照缺失会以空表替换回写，静默降级 = 丢邮件。
     */
    suspend fun getMails(): List<MailEntity> {
        ensureInitialized()
        return engine.getMails()
    }

    /**
     * 槽位邮件整对象替换（云恢复面用）：先删后写单事务，仅动邮件表。
     * 内层异常直接上抛（Room 2.7.0 吞内层异常 = 仅回滚内层写，不得依赖其做部分提交）。
     */
    suspend fun replaceMails(mails: List<MailEntity>) {
        ensureInitialized()
        engine.replaceMails(mails)
    }

    // ==================== 删除方法 ====================

    @Suppress("TooGenericExceptionCaught") // 异常显式包装进 Result 上抛, 非静默吞噬
    suspend fun delete(): SaveResult<Unit> {
        return try {
            ensureInitialized()
            val result = engine.delete()
            if (result.isSuccess) {
                Log.i(TAG, "Deleted save")
                SaveResult.success(Unit)
            } else {
                Log.e(TAG, "Delete failed: ${result.getOrNull()}")
                SaveResult.failure(SaveError.DELETE_FAILED, "Delete failed")
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Delete failed", e)
            SaveResult.failure(SaveError.DELETE_FAILED, e.message ?: "Unknown error", e)
        }
    }

    // ==================== 单档摘要方法 ====================

    /**
     * 本地单档摘要（三态：有存档 / 空档 / 读取失败）。
     * 引擎层异常在此收敛为 isLoadError 态而非空档——损坏存档伪装成空档
     * 会被玩家"点击创建"静默覆盖（数据丢失红线）。
     */
    @Suppress("TooGenericExceptionCaught") // 防御兜底: 异常源跨IO/SDK不可枚举, 降级继续+日志留痕, 非静默吞噬
    suspend fun getSaveInfoSuspend(): SaveInfo {
        return try {
            engine.getSaveInfo()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "getSaveInfoSuspend FAILED, marking as load error (not empty)", e)
            SaveInfo(isLoadError = true)
        }
    }

    // ==================== 数据检查方法 ====================

    @Suppress("TooGenericExceptionCaught") // 防御兜底: 异常源跨IO/SDK不可枚举, 降级继续+日志留痕, 非静默吞噬
    suspend fun isSaveCorruptedSuspend(): Boolean {
        return try {
            !engine.hasData()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "isSaveCorrupted check failed", e)
            false
        }
    }

    /**
     * 从备份恢复指定槽位（**真恢复**，审计 §12-D 修正）。
     *
     * 旧实现函数体只有一行日志（"已委派给 StorageEngine.load()"）却**不触发任何恢复**，
     * 被 `persistRestartSave` 的失败分支当真恢复手段调用 ⇒ 安全网实际是空的。
     *
     * 现委派 [StorageEngine.restoreFromBackup]（真链路）：读 `.sav`/`.bak`
     * （CRC32C 校验 + 回退）→ 反序列化 → 版本迁移 → 二次校验 → 隔离当前库 →
     * **写回 DB**（`performFullTransactionSave`）→ 更新缓存。
     *
     * 槽位锁：本入口不在 load 持锁路径内（调用方为保存失败分支），故自取写锁；
     * 与 [StorageEngine.load] 内的同族调用不构成重入（锁为同一把排他锁，非可重入）。
     *
     * @return true = 已从备份成功恢复并写回 DB；false = 备份不可用或恢复失败
     *   （调用方**保持失败语义**，不得据此报成功）
     */
    @Suppress("TooGenericExceptionCaught") // 恢复链异常面广（IO/反序列化/写库），失败即如实返回 false
    suspend fun restoreFromBackupIfCorrupted(): Boolean {
        return lockManager.withWriteLockLight() {
            try {
                val restored = engine.restoreFromBackup()
                val ok = restored?.isSuccess == true
                if (ok) {
                    Log.w(TAG, "已从备份恢复（.sav/.bak → DB）")
                } else {
                    Log.e(TAG, "备份恢复失败（备份不可用或写库失败）")
                }
                ok
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "备份恢复异常", e)
                false
            }
        }
    }

    // ==================== 内部辅助方法 ====================

    private suspend fun ensureInitialized() {
        if (!isInitialized.get()) {
            Log.w(TAG, "StorageFacade not initialized, attempting auto-initialization")
            initialize()
        }
    }
}
