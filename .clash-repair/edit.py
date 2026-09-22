import re
BASE = 'c:/Mnzm/XianxiaSectNative/android/'
log = []

def rd(p):
    with open(BASE + p, encoding='utf-8', newline='') as f:
        return f.read()

def wr(p, s):
    with open(BASE + p, 'w', encoding='utf-8', newline='') as f:
        f.write(s)

def rep(p, old, new, tag):
    s = rd(p)
    n = s.count(old)
    if n == 0:
        log.append('MISS ' + tag); return
    wr(p, s.replace(old, new)); log.append('OK(%d) %s' % (n, tag))

def rep_re(p, pat, new, tag):
    s = rd(p)
    s2, n = re.subn(pat, new, s)
    if n == 0:
        log.append('MISS ' + tag); return
    wr(p, s2); log.append('OK(%d) %s' % (n, tag))

def cut(p, a, b, tag):
    s = rd(p)
    i = s.find(a); j = s.find(b)
    if i < 0 or j < 0 or j < i:
        log.append('MISS ' + tag); return
    wr(p, s[:i] + s[j:]); log.append('OK %s' % tag)

# ---------- StorageFacade ----------
p = 'core/data/src/main/java/com/xianxia/sect/data/facade/StorageFacade.kt'
cut(p, '// ==================== 数据类定义 ====================', '// ==================== 扩展函数 ====================', 'SF-dataclasses')
rep(p, """    private val _progress = MutableStateFlow(FacadeSaveProgress(FacadeSaveProgress.Stage.IDLE, 0f))
    val progress: StateFlow<FacadeSaveProgress> = _progress.asStateFlow()

""", "", 'SF-progress')
rep(p, """    private val _currentSlot = MutableStateFlow(1)
    val currentSlotFlow: StateFlow<Int> = _currentSlot.asStateFlow()
""", """    private val _currentSlot = MutableStateFlow(1)
""", 'SF-currentSlotFlow')
rep(p, """
    private val saveCount = AtomicLong(0)
    private val loadCount = AtomicLong(0)
    private val deleteCount = AtomicLong(0)
    private val totalSaveTimeMs = AtomicLong(0)
    private val totalLoadTimeMs = AtomicLong(0)
""", "", 'SF-counters')
rep_re(p, r'[ \t]*_progress\.value = FacadeSaveProgress\([^)]*\)\n', '', 'SF-progress-assign')
rep(p, """        ensureInitialized()
        val startTime = System.currentTimeMillis()

        return try {
""", """        ensureInitialized()

        return try {
""", 'SF-startTime')
rep(p, """            val result = engine.save(slot, data)
            val elapsed = System.currentTimeMillis() - startTime
""", """            val result = engine.save(slot, data)
""", 'SF-elapsed-save')
rep(p, """            val result = engine.load(slot)
            val elapsed = System.currentTimeMillis() - startTime
""", """            val result = engine.load(slot)
""", 'SF-elapsed-load')
rep(p, """                saveCount.incrementAndGet()
                totalSaveTimeMs.addAndGet(elapsed)
""", "", 'SF-inc-save')
rep(p, """                loadCount.incrementAndGet()
                totalLoadTimeMs.addAndGet(elapsed)
""", "", 'SF-inc-load')
rep(p, """                deleteCount.incrementAndGet()
""", "", 'SF-inc-delete')
rep(p, """    /**
     * 强制删除 slot 数据（跳过校验，用于云存档 slot 0 等特殊槽位）。
     * 只清理 Room DB，不做文件级清理。
     */
    suspend fun forceDeleteSlotData(slot: Int) {
        engine.forceDeleteSlotData(slot)
    }

""", "", 'SF-forceDelete')
rep(p, """    @Suppress("TooGenericExceptionCaught") // 防御兜底: 异常源跨IO/SDK不可枚举, 降级继续+日志留痕, 非静默吞噬
    suspend fun hasSaveSuspend(slot: Int): Boolean {
        return try {
            engine.hasData(slot)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "hasSave check failed for slot $slot", e)
            false
        }
    }

""", "", 'SF-hasSaveSuspend')
cut(p, '    // ==================== 统计与健康检查方法 ====================', '    // ==================== 内部辅助方法 ====================', 'SF-stats-section')
rep(p, 'import kotlinx.coroutines.flow.StateFlow\n', '', 'SF-imp-StateFlow')
rep(p, 'import kotlinx.coroutines.flow.asStateFlow\n', '', 'SF-imp-asStateFlow')
rep(p, 'import java.util.concurrent.atomic.AtomicLong\n', '', 'SF-imp-AtomicLong')

# ---------- StorageEngine ----------
p = 'core/data/src/main/java/com/xianxia/sect/data/engine/StorageEngine.kt'
rep(p, """    @Suppress("TooGenericExceptionCaught") // 异常显式包装进 Result 上抛, 非静默吞噬
    suspend fun listSlots(): StorageResult<List<SlotMetadata>> {
        return try {
            val slots = (1..core.lockManager.getMaxSlots()).mapNotNull { slot ->
                getSlotMetadata(slot)
            }
            StorageResult.success(slots)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "listSlots failed", e)
            StorageResult.failure(StorageError.LOAD_FAILED, e.message ?: "Failed to list slots")
        }
    }

""", "", 'SE-listSlots')
rep(p, """    /**
     * 强制删除指定 slot 的数据（跳过 slot 校验，用于云存档 slot 等特殊槽位）。
     * 仅清理 Room DB 中的 game_data 条目，不涉及文件级清理。
     */
    @Suppress("TooGenericExceptionCaught") // 防御兜底: 异常源跨IO/SDK不可枚举, 降级继续+日志留痕, 非静默吞噬
    suspend fun forceDeleteSlotData(slot: Int) {
        try {
            core.database.gameDataDao().deleteAll(slot)
            Log.i(TAG, "forceDeleteSlotData: deleted data for slot $slot")
        } catch (e: CancellationException) { throw e }
          catch (e: Exception) {
            Log.w(TAG, "forceDeleteSlotData: failed for slot $slot", e)
        }
    }

""", "", 'SE-forceDelete')

# ---------- StorageEngineHeavyDataOps (only one function) ----------
p = 'core/data/src/main/java/com/xianxia/sect/data/engine/StorageEngineHeavyDataOps.kt'
rep(p, """internal suspend fun StorageEngine.loadHeavyDataForSlot(slot: Int): Map<String, ByteArray> {
    val heavyDataList = loadHeavyDataSafe(slot)
    return GameHeavyData.reassemble(heavyDataList)
}

""", "", 'SEH-loadHeavyDataForSlot')

# ---------- SaveFileManager ----------
p = 'core/data/src/main/java/com/xianxia/sect/data/backup/SaveFileManager.kt'
rep(p, """    /** 获取备份信息（用于 UI 展示） */
    @Suppress("TooGenericExceptionCaught") // 防御兜底: 异常源跨IO/SDK不可枚举, 降级继续+日志留痕, 非静默吞噬
    fun getBackupInfo(slot: Int): BackupInfo? {
        ensureInitialized()
        if (!isValidSlot(slot)) return null

        val savFile = getSavFile(slot)
        val bakFile = getBakFile(slot)

        return try {
            BackupInfo(
                slot = slot,
                primaryExists = savFile.exists(),
                backupExists = bakFile.exists(),
                primaryValid = null, // 惰性校验：用户点击时再调用 verifySlot
                backupValid = null,
                primaryTimestamp = if (savFile.exists()) savFile.lastModified() else null,
                backupTimestamp = if (bakFile.exists()) bakFile.lastModified() else null,
                primarySizeBytes = if (savFile.exists()) savFile.length() else null,
                backupSizeBytes = if (bakFile.exists()) bakFile.length() else null
            )
        } catch (e: Exception) {
            Log.w(TAG, "获取备份信息失败 slot=$slot", e)
            null
        }
    }

""", "", 'SFM-getBackupInfo')
rep(p, """/** 备份信息（用于 UI 展示） */
data class BackupInfo(
    val slot: Int,
    val primaryExists: Boolean,
    val backupExists: Boolean,
    val primaryValid: Boolean?,
    val backupValid: Boolean?,
    val primaryTimestamp: Long?,
    val backupTimestamp: Long?,
    val primarySizeBytes: Long?,
    val backupSizeBytes: Long?
)

""", "", 'SFM-BackupInfo')

# ---------- ArchiveDaos ----------
p = 'core/data/src/main/java/com/xianxia/sect/data/archive/ArchiveDaos.kt'
rep(p, """    @Query("SELECT * FROM archived_battle_logs WHERE slot_id = :slotId ORDER BY timestamp DESC")
    suspend fun getBySlot(slotId: Int): List<ArchivedBattleLog>

    @Query("SELECT * FROM archived_battle_logs WHERE slot_id = :slotId AND timestamp BETWEEN :startMs AND :endMs " +
        "ORDER BY timestamp DESC")
    suspend fun getByTimeRange(slotId: Int, startMs: Long, endMs: Long): List<ArchivedBattleLog>

    @Query("SELECT COUNT(*) FROM archived_battle_logs WHERE slot_id = :slotId")
    suspend fun getCountBySlot(slotId: Int): Int

""", "", 'AD-battle-queries')
rep(p, """    @Query("SELECT * FROM archived_disciples WHERE slot_id = :slotId ORDER BY archived_at DESC")
    suspend fun getBySlot(slotId: Int): List<ArchivedDisciple>

    @Query("SELECT * FROM archived_disciples WHERE slot_id = :slotId AND name LIKE '%' || :keyword || '%'")
    suspend fun searchByName(slotId: Int, keyword: String): List<ArchivedDisciple>

    @Query("SELECT COUNT(*) FROM archived_disciples WHERE slot_id = :slotId")
    suspend fun getCountBySlot(slotId: Int): Int

""", "", 'AD-disciple-queries')

# ---------- CoreModule ----------
p = 'app/src/main/java/com/xianxia/sect/di/CoreModule.kt'
rep(p, """    @Provides
    @Singleton
    fun provideSaveStorage(impl: SaveStorageImpl): com.xianxia.sect.core.repository.SaveStorage = impl

""", "", 'CM-provideSaveStorage')

# ---------- AppModule ----------
p = 'app/src/main/java/com/xianxia/sect/di/AppModule.kt'
rep(p, 'import com.xianxia.sect.data.incremental.ChangeTracker\n', '', 'AM-imp-ChangeTracker')
rep(p, """    @Provides
    @Singleton
    fun provideChangeTracker(): ChangeTracker {
        return ChangeTracker()
    }

""", "", 'AM-provideChangeTracker')

# ---------- SaveCryptoKeyCache ----------
p = 'core/data/src/main/java/com/xianxia/sect/data/crypto/SaveCryptoKeyCache.kt'
rep(p, 'import android.util.Log\n', 'import android.util.Log\nimport java.security.MessageDigest\n', 'SKC-imp-MessageDigest')
rep(p, 'private const val TAG = SaveCrypto.TAG', 'private const val TAG = "SaveCryptoKeyCache"', 'SKC-TAG')
s = rd(p)
if 'sha256Hex(password.toByteArray())' in s and 'private fun sha256Hex' not in s:
    s = s.rstrip('\n') + '\n\nprivate fun sha256Hex(data: ByteArray): String {\n    val digest = MessageDigest.getInstance("SHA-256")\n    return digest.digest(data).joinToString("") { "%02x".format(it) }\n}\n'
    wr(p, s); log.append('OK SKC-sha256Hex-helper')
else:
    log.append('SKIP SKC-sha256Hex-helper')

# ---------- AppErrorExt ----------
p = 'app/src/main/java/com/xianxia/sect/core/util/AppErrorExt.kt'
rep(p, """
fun com.xianxia.sect.data.crypto.VerificationResult.toAppError(): AppError.Domain.Storage? = when (this) {
    is com.xianxia.sect.data.crypto.VerificationResult.Valid -> null
    is com.xianxia.sect.data.crypto.VerificationResult.Invalid ->
        AppError.Domain.Storage.VerificationFailed(reason, null)
    is com.xianxia.sect.data.crypto.VerificationResult.Expired ->
        AppError.Domain.Storage.Expired("签名已过期 (签名时间: $signedAt, 当前时间: $currentTime)", null)
    is com.xianxia.sect.data.crypto.VerificationResult.Tampered ->
        AppError.Domain.Storage.Tampered(reason, null)
}
""", "", 'AEE-VerificationResult-ext')

with open('c:/Mnzm/XianxiaSectNative/.clash-repair/_editlog.txt', 'w', encoding='utf-8') as f:
    f.write('\n'.join(log) + '\n')