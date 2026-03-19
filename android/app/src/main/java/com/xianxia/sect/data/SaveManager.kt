package com.xianxia.sect.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.xianxia.sect.data.model.SaveData
import com.xianxia.sect.data.model.SaveSlot
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.*
import java.security.MessageDigest
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SaveManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "SaveManager"
        private const val PREFS_NAME = "xianxia_saves"
        private const val KEY_SAVE_SLOT = "save_slot_"
        private const val KEY_AUTO_SAVE = "autosave"
        private const val KEY_EMERGENCY_SAVE = "emergency"
        private const val KEY_MIGRATION_DONE = "migration_done"
        private const val MAX_SLOTS = 5
        private const val SAVE_DIR = "saves"
        private const val FILE_EXTENSION = ".sav"
        private const val META_EXTENSION = ".meta"
        private const val MAX_BATTLE_LOGS = 100
        private const val MAX_GAME_EVENTS = 50
        const val EMERGENCY_SAVE = "emergency"
    }

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val gson = GsonConfig.createGson()

    private val saveMutex = Mutex()
    private val loadMutex = Mutex()
    
    // 同步锁，用于同步方法
    private val syncSaveLock = Any()

    private val saveDir: File
        get() = File(context.filesDir, SAVE_DIR).apply { if (!exists()) mkdirs() }

    init {
        migrateFromSharedPreferences()
    }

    fun getSaveSlots(): List<SaveSlot> = (1..MAX_SLOTS).map(::getSlotInfo)

    fun getSlotInfo(slot: Int): SaveSlot {
        if (!isValidSlot(slot)) return emptySlot(slot)

        val metaFile = getMetaFile(slot)
        if (!metaFile.exists()) {
            return tryLoadMetaFromSaveFile(slot) ?: emptySlot(slot)
        }

        return try {
            val metaJson = metaFile.readText()
            val meta = gson.fromJson(metaJson, SaveSlotMeta::class.java)
            SaveSlot(
                slot = slot,
                name = "Save $slot",
                timestamp = meta.timestamp,
                gameYear = meta.gameYear,
                gameMonth = meta.gameMonth,
                sectName = meta.sectName,
                discipleCount = meta.discipleCount,
                spiritStones = meta.spiritStones,
                isEmpty = false
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read meta for slot $slot", e)
            tryLoadMetaFromSaveFile(slot) ?: emptySlot(slot)
        }
    }

    private fun tryLoadMetaFromSaveFile(slot: Int): SaveSlot? {
        val saveFile = getSaveFile(slot)
        if (!saveFile.exists()) return null

        return try {
            val data = loadFromFile(slot) ?: return null
            SaveSlot(
                slot = slot,
                name = "Save $slot",
                timestamp = data.timestamp,
                gameYear = data.gameData.gameYear,
                gameMonth = data.gameData.gameMonth,
                sectName = data.gameData.sectName,
                discipleCount = data.disciples.count { it.isAlive },
                spiritStones = data.gameData.spiritStones,
                isEmpty = false
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load meta from save file for slot $slot", e)
            null
        }
    }

    fun save(slot: Int, data: SaveData): Boolean {
        if (!isValidSlot(slot)) {
            Log.e(TAG, "Invalid save slot: $slot")
            return false
        }

        Log.i(TAG, "=== save BEGIN === slot=$slot, sectName=${data.gameData.sectName}, " +
            "year=${data.gameData.gameYear}, month=${data.gameData.gameMonth}, day=${data.gameData.gameDay}, " +
            "spiritStones=${data.gameData.spiritStones}, disciples=${data.disciples.count { it.isAlive }}")

        // 使用同步锁确保没有并发写入
        return synchronized(syncSaveLock) {
            val startTime = System.currentTimeMillis()
            val cleanedData = cleanSaveData(data)
            val saveFile = getSaveFile(slot)
            val tempFile = File(saveFile.parent, "${saveFile.name}.tmp")
            val backupFile = File(saveFile.parent, "${saveFile.name}.bak")
            
            // 标记是否成功完成，用于决定是否清理备份
            var saveCompleted = false
            var backupCreated = false

            try {
                // 步骤1: 写入临时文件
                val checksum = saveToFileWithChecksum(tempFile, cleanedData)
                
                // 步骤2: 创建备份（如果原存档存在）
                if (saveFile.exists()) {
                    // 先删除旧备份（如果存在）
                    if (backupFile.exists()) {
                        if (!backupFile.delete()) {
                            Log.w(TAG, "Failed to delete old backup file for slot $slot")
                        }
                    }
                    // 将当前存档重命名为备份
                    if (saveFile.renameTo(backupFile)) {
                        backupCreated = true
                        Log.d(TAG, "Created backup for slot $slot")
                    } else {
                        Log.w(TAG, "Failed to create backup for slot $slot, continuing anyway")
                    }
                }

                // 步骤3: 原子重命名临时文件为正式存档
                if (!tempFile.renameTo(saveFile)) {
                    Log.e(TAG, "Failed to rename temp file to save file for slot $slot")
                    // 尝试恢复备份
                    if (backupCreated && backupFile.exists()) {
                        backupFile.renameTo(saveFile)
                        Log.i(TAG, "Restored backup after rename failure for slot $slot")
                    }
                    return false
                }

                // 步骤4: 保存元数据
                saveMetaWithChecksum(slot, cleanedData, checksum)

                saveCompleted = true
                val elapsed = System.currentTimeMillis() - startTime
                Log.i(TAG, "=== save SUCCESS === slot=$slot, elapsed=${elapsed}ms, " +
                    "compressedSize=${saveFile.length()} bytes, " +
                    "disciples=${cleanedData.disciples.size}, equipment=${cleanedData.equipment.size}, " +
                    "manuals=${cleanedData.manuals.size}, pills=${cleanedData.pills.size}")
                true
            } catch (e: FileNotFoundException) {
                Log.e(TAG, "=== save FAILED === slot=$slot, error=FileNotFoundException", e)
                // 写入失败，不删除备份文件
                false
            } catch (e: IOException) {
                Log.e(TAG, "=== save FAILED === slot=$slot, error=IOException", e)
                // 写入失败，不删除备份文件
                false
            } catch (e: Exception) {
                Log.e(TAG, "=== save FAILED === slot=$slot, error=${e.javaClass.simpleName}", e)
                // 写入失败，不删除备份文件
                false
            } finally {
                // 清理临时文件（如果存在）
                if (tempFile.exists()) {
                    tempFile.delete()
                }
                // 注意：成功写入后也不删除备份文件，保留最近一次的备份
                // 备份文件会在下次成功保存时被覆盖
                if (saveCompleted) {
                    Log.d(TAG, "Save completed, backup preserved for slot $slot")
                }
            }
        }
    }

    suspend fun saveAsync(slot: Int, data: SaveData): Boolean {
        return saveMutex.withLock {
            withContext(Dispatchers.IO) {
                save(slot, data)
            }
        }
    }

    fun load(slot: Int): SaveData? {
        if (!isValidSlot(slot)) {
            Log.e(TAG, "Invalid save slot: $slot")
            return null
        }

        Log.i(TAG, "=== load BEGIN === slot=$slot")
        val startTime = System.currentTimeMillis()

        val saveFile = getSaveFile(slot)
        if (!saveFile.exists()) {
            Log.w(TAG, "=== load FAILED === slot=$slot, reason=file not found")
            return null
        }

        // 先校验存档完整性
        val verificationResult = verifySave(slot)
        if (verificationResult == SaveVerificationResult.VALID) {
            val data = loadFromFile(slot)
            val elapsed = System.currentTimeMillis() - startTime
            if (data != null) {
                Log.i(TAG, "=== load SUCCESS === slot=$slot, elapsed=${elapsed}ms, " +
                    "sectName=${data.gameData.sectName}, year=${data.gameData.gameYear}, " +
                    "month=${data.gameData.gameMonth}, day=${data.gameData.gameDay}, " +
                    "spiritStones=${data.gameData.spiritStones}, disciples=${data.disciples.count { it.isAlive }}")
            } else {
                Log.e(TAG, "=== load FAILED === slot=$slot, reason=loadFromFile returned null")
            }
            return data
        }

        // 存档可能损坏，尝试从备份恢复
        Log.w(TAG, "=== load RETRY === slot=$slot, verification=$verificationResult, attempting backup recovery")
        val restoredData = restoreFromBackupIfCorrupted(slot)
        val elapsed = System.currentTimeMillis() - startTime
        if (restoredData != null) {
            Log.i(TAG, "=== load SUCCESS (from backup) === slot=$slot, elapsed=${elapsed}ms")
        } else {
            Log.e(TAG, "=== load FAILED === slot=$slot, reason=backup recovery failed")
        }
        return restoredData
    }

    suspend fun loadAsync(slot: Int): SaveData? {
        return loadMutex.withLock {
            withContext(Dispatchers.IO) {
                load(slot)
            }
        }
    }

    fun delete(slot: Int): Boolean {
        if (!isValidSlot(slot)) {
            Log.e(TAG, "=== delete FAILED === slot=$slot, reason=invalid slot")
            return false
        }

        Log.i(TAG, "=== delete BEGIN === slot=$slot")
        val startTime = System.currentTimeMillis()

        return try {
            val saveFile = getSaveFile(slot)
            val metaFile = getMetaFile(slot)
            val backupFile = File(saveFile.parent, "${saveFile.name}.bak")

            var success = true
            var saveFileSize = 0L
            var metaFileSize = 0L
            
            if (saveFile.exists()) {
                saveFileSize = saveFile.length()
                if (!saveFile.delete()) {
                    Log.e(TAG, "Failed to delete save file for slot $slot")
                    success = false
                }
            }
            if (metaFile.exists()) {
                metaFileSize = metaFile.length()
                if (!metaFile.delete()) {
                    Log.e(TAG, "Failed to delete meta file for slot $slot")
                    success = false
                }
            }
            if (backupFile.exists()) {
                backupFile.delete()
            }

            val elapsed = System.currentTimeMillis() - startTime
            if (success) {
                Log.i(TAG, "=== delete SUCCESS === slot=$slot, elapsed=${elapsed}ms, " +
                    "saveFileSize=$saveFileSize bytes, metaFileSize=$metaFileSize bytes")
            } else {
                Log.e(TAG, "=== delete PARTIAL === slot=$slot, elapsed=${elapsed}ms")
            }
            success
        } catch (e: Exception) {
            Log.e(TAG, "=== delete FAILED === slot=$slot, error=${e.javaClass.simpleName}", e)
            false
        }
    }

    fun autoSave(data: SaveData) {
        try {
            Log.i(TAG, "=== autoSave BEGIN === sectName=${data.gameData.sectName}, " +
                "year=${data.gameData.gameYear}, month=${data.gameData.gameMonth}, day=${data.gameData.gameDay}")
            val startTime = System.currentTimeMillis()
            val cleanedData = cleanSaveData(data.copy(timestamp = System.currentTimeMillis()))
            val autoSaveFile = getAutoSaveFile()
            saveToFileWithChecksum(autoSaveFile, cleanedData)
            val elapsed = System.currentTimeMillis() - startTime
            Log.i(TAG, "=== autoSave SUCCESS === elapsed=${elapsed}ms, size=${autoSaveFile.length()} bytes")
        } catch (e: Exception) {
            Log.e(TAG, "=== autoSave FAILED === error=${e.javaClass.simpleName}", e)
        }
    }

    suspend fun autoSaveAsync(data: SaveData) {
        saveMutex.withLock {
            withContext(Dispatchers.IO) {
                autoSave(data)
            }
        }
    }

    fun loadAutoSave(): SaveData? {
        Log.i(TAG, "=== loadAutoSave BEGIN ===")
        val startTime = System.currentTimeMillis()
        val autoSaveFile = getAutoSaveFile()
        if (!autoSaveFile.exists()) {
            Log.d(TAG, "=== loadAutoSave FAILED === reason=file not found")
            return null
        }

        return try {
            val data = loadFromFile(autoSaveFile)
            val elapsed = System.currentTimeMillis() - startTime
            if (data != null) {
                Log.i(TAG, "=== loadAutoSave SUCCESS === elapsed=${elapsed}ms, " +
                    "sectName=${data.gameData.sectName}, year=${data.gameData.gameYear}")
            } else {
                Log.e(TAG, "=== loadAutoSave FAILED === reason=loadFromFile returned null")
            }
            data
        } catch (e: Exception) {
            Log.e(TAG, "=== loadAutoSave FAILED === error=${e.javaClass.simpleName}", e)
            null
        }
    }

    fun hasAutoSave(): Boolean = getAutoSaveFile().exists()

    fun hasSave(slot: Int): Boolean = isValidSlot(slot) && getSaveFile(slot).exists()

    fun getSaveFileSize(slot: Int): Long {
        val saveFile = getSaveFile(slot)
        return if (saveFile.exists()) saveFile.length() else 0L
    }

    fun getCompressedSize(slot: Int): Long = getSaveFileSize(slot)

    fun getEstimatedUncompressedSize(slot: Int): Long {
        return try {
            val saveFile = getSaveFile(slot)
            if (!saveFile.exists()) return 0L

            GZIPInputStream(FileInputStream(saveFile)).use { input ->
                val buffer = ByteArray(8192)
                var total = 0L
                var read: Int
                while (input.read(buffer).also { read = it } > 0) {
                    total += read
                }
                total
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to estimate uncompressed size for slot $slot", e)
            0L
        }
    }

    private fun saveToFileWithChecksum(file: File, data: SaveData): String {
        val digest = MessageDigest.getInstance("MD5")
        
        GZIPOutputStream(FileOutputStream(file)).use { gzipOutput ->
            val digestStream = object : OutputStream() {
                override fun write(b: Int) {
                    digest.update(b.toByte())
                    gzipOutput.write(b)
                }
                override fun write(b: ByteArray, off: Int, len: Int) {
                    digest.update(b, off, len)
                    gzipOutput.write(b, off, len)
                }
            }
            OutputStreamWriter(digestStream, Charsets.UTF_8).use { writer ->
                gson.toJson(data, writer)
                writer.flush()
            }
        }

        val checksum = digest.digest().joinToString("") { "%02x".format(it) }
        Log.d(TAG, "Saved to ${file.name}, compressed size: ${file.length()} bytes, checksum: ${checksum.take(8)}...")
        return checksum
    }

    private fun loadFromFile(slot: Int): SaveData? {
        val saveFile = getSaveFile(slot)
        if (!saveFile.exists()) return null
        return loadFromFile(saveFile)
    }

    private fun loadFromFile(file: File): SaveData? {
        return try {
            val startTime = System.currentTimeMillis()
            val jsonBytes = GZIPInputStream(FileInputStream(file)).use { input ->
                input.readBytes()
            }
            val json = String(jsonBytes, Charsets.UTF_8)
            val data = gson.fromJson(json, SaveData::class.java)
            val elapsed = System.currentTimeMillis() - startTime
            Log.d(TAG, "Loaded from ${file.name} in ${elapsed}ms, uncompressed size: ${jsonBytes.size} bytes")
            data
        } catch (e: FileNotFoundException) {
            Log.e(TAG, "File not found: ${file.name}", e)
            null
        } catch (e: java.util.zip.ZipException) {
            Log.e(TAG, "GZIP decompression failed for ${file.name}, file may be corrupted", e)
            null
        } catch (e: com.google.gson.JsonSyntaxException) {
            Log.e(TAG, "JSON parsing failed for ${file.name}, data may be corrupted", e)
            null
        } catch (e: IOException) {
            Log.e(TAG, "IO error while loading ${file.name}", e)
            null
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected error while loading ${file.name}", e)
            null
        }
    }

    private fun saveMetaWithChecksum(slot: Int, data: SaveData, checksum: String) {
        try {
            val meta = SaveSlotMeta(
                timestamp = data.timestamp,
                gameYear = data.gameData.gameYear,
                gameMonth = data.gameData.gameMonth,
                sectName = data.gameData.sectName,
                discipleCount = data.disciples.count { it.isAlive },
                spiritStones = data.gameData.spiritStones,
                checksum = checksum
            )
            val metaFile = getMetaFile(slot)
            metaFile.writeText(gson.toJson(meta))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save meta for slot $slot", e)
        }
    }

    private fun cleanSaveData(data: SaveData): SaveData {
        val cleanedBattleLogs = if (data.battleLogs.size > MAX_BATTLE_LOGS) {
            val removed = data.battleLogs.size - MAX_BATTLE_LOGS
            Log.d(TAG, "Cleaning battle logs: keeping $MAX_BATTLE_LOGS, removing $removed old entries")
            data.battleLogs.sortedByDescending { it.timestamp }.take(MAX_BATTLE_LOGS)
        } else {
            data.battleLogs
        }

        val cleanedEvents = if (data.events.size > MAX_GAME_EVENTS) {
            val removed = data.events.size - MAX_GAME_EVENTS
            Log.d(TAG, "Cleaning game events: keeping $MAX_GAME_EVENTS, removing $removed old entries")
            data.events.sortedByDescending { it.timestamp }.take(MAX_GAME_EVENTS)
        } else {
            data.events
        }

        return data.copy(
            battleLogs = cleanedBattleLogs,
            events = cleanedEvents
        )
    }

    fun verifySave(slot: Int): SaveVerificationResult {
        val saveFile = getSaveFile(slot)
        if (!saveFile.exists()) {
            return SaveVerificationResult.NOT_FOUND
        }

        return try {
            // 首先尝试加载存档数据，验证文件是否可以正确解析
            val data = loadFromFile(slot)
            if (data == null) {
                Log.w(TAG, "Save file cannot be parsed for slot $slot")
                return SaveVerificationResult.CORRUPTED
            }

            // 验证元数据中的 checksum
            val metaFile = getMetaFile(slot)
            if (metaFile.exists()) {
                try {
                    val metaJson = metaFile.readText()
                    val meta = gson.fromJson(metaJson, SaveSlotMeta::class.java)
                    
                    if (meta.checksum.isNotEmpty()) {
                        // 重新计算存档数据的 checksum
                        val currentChecksum = calculateDataChecksum(slot)
                        if (currentChecksum.isNotEmpty() && meta.checksum != currentChecksum) {
                            Log.w(TAG, "Checksum mismatch for slot $slot: expected ${meta.checksum.take(8)}..., got ${currentChecksum.take(8)}...")
                            return SaveVerificationResult.CHECKSUM_MISMATCH
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to read meta for checksum verification for slot $slot", e)
                    // 元数据读取失败，但存档数据有效，仍然返回 VALID
                }
            }
            
            Log.d(TAG, "Save verification passed for slot $slot")
            SaveVerificationResult.VALID
        } catch (e: Exception) {
            Log.e(TAG, "Save verification failed for slot $slot", e)
            SaveVerificationResult.CORRUPTED
        }
    }

    /**
     * 计算存档数据的 checksum（解压后重新计算）
     * 这与保存时计算 checksum 的方式一致
     */
    private fun calculateDataChecksum(slot: Int): String {
        val saveFile = getSaveFile(slot)
        if (!saveFile.exists()) return ""

        return try {
            val jsonBytes = GZIPInputStream(FileInputStream(saveFile)).use { input ->
                input.readBytes()
            }
            val digest = MessageDigest.getInstance("MD5")
            digest.update(jsonBytes)
            digest.digest().joinToString("") { "%02x".format(it) }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to calculate data checksum for slot $slot", e)
            ""
        }
    }

    private fun calculateChecksum(slot: Int): String {
        val saveFile = getSaveFile(slot)
        if (!saveFile.exists()) return ""

        return try {
            val digest = MessageDigest.getInstance("MD5")
            FileInputStream(saveFile).use { input ->
                val buffer = ByteArray(8192)
                var read: Int
                while (input.read(buffer).also { read = it } > 0) {
                    digest.update(buffer, 0, read)
                }
            }
            digest.digest().joinToString("") { "%02x".format(it) }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to calculate checksum for slot $slot", e)
            ""
        }
    }

    fun restoreFromBackup(slot: Int): Boolean {
        if (!isValidSlot(slot)) return false

        val saveFile = getSaveFile(slot)
        val backupFile = File(saveFile.parent, "${saveFile.name}.bak")

        if (!backupFile.exists()) {
            Log.w(TAG, "No backup file found for slot $slot")
            return false
        }

        return try {
            if (saveFile.exists()) saveFile.delete()
            backupFile.copyTo(saveFile)
            Log.i(TAG, "Restored slot $slot from backup")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to restore slot $slot from backup", e)
            false
        }
    }

    /**
     * 检测存档是否损坏
     * @param slot 存档槽位
     * @return true 如果存档损坏或不存在
     */
    fun isSaveCorrupted(slot: Int): Boolean {
        if (!isValidSlot(slot)) return true
        
        val saveFile = getSaveFile(slot)
        if (!saveFile.exists()) return true
        
        val result = verifySave(slot)
        return result != SaveVerificationResult.VALID
    }

    /**
     * 如果存档损坏，尝试从备份恢复
     * @param slot 存档槽位
     * @return 恢复后的存档数据，如果恢复失败返回 null
     */
    fun restoreFromBackupIfCorrupted(slot: Int): SaveData? {
        if (!isValidSlot(slot)) {
            Log.e(TAG, "Invalid slot for backup recovery: $slot")
            return null
        }

        val saveFile = getSaveFile(slot)
        val backupFile = File(saveFile.parent, "${saveFile.name}.bak")

        // 检查备份文件是否存在
        if (!backupFile.exists()) {
            Log.w(TAG, "No backup available for corrupted slot $slot")
            return null
        }

        // 验证备份文件是否有效
        val backupValid = try {
            val data = loadFromFile(backupFile)
            data != null
        } catch (e: Exception) {
            Log.e(TAG, "Backup file is also corrupted for slot $slot", e)
            false
        }

        if (!backupValid) {
            Log.e(TAG, "Backup file is corrupted for slot $slot")
            return null
        }

        // 尝试从备份恢复
        return try {
            Log.i(TAG, "Attempting to restore slot $slot from backup")
            
            // 删除损坏的存档
            if (saveFile.exists()) {
                if (!saveFile.delete()) {
                    Log.w(TAG, "Failed to delete corrupted save file for slot $slot")
                }
            }

            // 复制备份文件到存档位置
            backupFile.copyTo(saveFile)
            
            // 重新加载元数据
            val data = loadFromFile(slot)
            if (data != null) {
                // 重新生成元数据（使用数据 checksum 而非文件 checksum）
                val checksum = calculateDataChecksum(slot)
                saveMetaWithChecksum(slot, data, checksum)
                Log.i(TAG, "Successfully restored slot $slot from backup")
                data
            } else {
                Log.e(TAG, "Failed to load restored save for slot $slot")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to restore slot $slot from backup", e)
            null
        }
    }

    private fun migrateFromSharedPreferences() {
        if (prefs.getBoolean(KEY_MIGRATION_DONE, false)) {
            return
        }

        Log.i(TAG, "Starting migration from SharedPreferences to file storage")

        try {
            for (slot in 1..MAX_SLOTS) {
                val json = prefs.getString(slotKey(slot), null)
                if (!json.isNullOrEmpty()) {
                    try {
                        val data = gson.fromJson(json, SaveData::class.java)
                        if (data != null) {
                            save(slot, data)
                            Log.i(TAG, "Migrated slot $slot from SharedPreferences")
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to migrate slot $slot", e)
                    }
                }
            }

            val autoSaveJson = prefs.getString(KEY_AUTO_SAVE, null)
            if (!autoSaveJson.isNullOrEmpty()) {
                try {
                    val data = gson.fromJson(autoSaveJson, SaveData::class.java)
                    if (data != null) {
                        autoSave(data)
                        Log.i(TAG, "Migrated auto save from SharedPreferences")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to migrate auto save", e)
                }
            }

            prefs.edit().putBoolean(KEY_MIGRATION_DONE, true).apply()
            Log.i(TAG, "Migration completed successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Migration failed", e)
        }
    }

    private fun isValidSlot(slot: Int): Boolean = slot in 1..MAX_SLOTS

    private fun slotKey(slot: Int): String = "$KEY_SAVE_SLOT$slot"

    private fun emptySlot(slot: Int): SaveSlot = SaveSlot(slot, "", 0, 1, 1, "", 0, 0, true)

    private fun getSaveFile(slot: Int): File = File(saveDir, "slot_$slot$FILE_EXTENSION")

    private fun getMetaFile(slot: Int): File = File(saveDir, "slot_$slot$META_EXTENSION")

    private fun getAutoSaveFile(): File = File(saveDir, "autosave$FILE_EXTENSION")

    private fun getEmergencySaveFile(): File = File(saveDir, "emergency$FILE_EXTENSION")

    // ==================== 紧急存档相关方法 ====================

    /**
     * 紧急保存游戏数据
     * 在崩溃发生时调用，尝试快速保存当前游戏状态
     * @param data 要保存的游戏数据
     * @return 是否保存成功
     */
    fun emergencySave(data: SaveData): Boolean {
        return try {
            val startTime = System.currentTimeMillis()
            Log.i(TAG, "Starting emergency save...")

            val emergencyFile = getEmergencySaveFile()
            val tempFile = File(emergencyFile.parent, "${emergencyFile.name}.tmp")

            GZIPOutputStream(FileOutputStream(tempFile)).use { gzipOutput ->
                OutputStreamWriter(gzipOutput, Charsets.UTF_8).use { writer ->
                    gson.toJson(data.copy(timestamp = System.currentTimeMillis()), writer)
                    writer.flush()
                }
            }

            if (emergencyFile.exists()) {
                emergencyFile.delete()
            }

            if (!tempFile.renameTo(emergencyFile)) {
                Log.e(TAG, "Failed to rename temp file to emergency save file")
                return false
            }

            val elapsed = System.currentTimeMillis() - startTime
            Log.i(TAG, "Emergency save completed in ${elapsed}ms, size: ${emergencyFile.length()} bytes")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Emergency save failed", e)
            false
        }
    }

    /**
     * 检查是否存在紧急存档
     * @return 是否存在紧急存档
     */
    fun hasEmergencySave(): Boolean {
        val emergencyFile = getEmergencySaveFile()
        val exists = emergencyFile.exists() && emergencyFile.length() > 0
        Log.d(TAG, "Emergency save exists: $exists")
        return exists
    }

    /**
     * 加载紧急存档
     * @return 紧急存档数据，如果不存在或加载失败则返回 null
     */
    fun loadEmergencySave(): SaveData? {
        val emergencyFile = getEmergencySaveFile()
        if (!emergencyFile.exists()) {
            Log.w(TAG, "Emergency save file does not exist")
            return null
        }

        return try {
            val startTime = System.currentTimeMillis()
            val jsonBytes = GZIPInputStream(FileInputStream(emergencyFile)).use { input ->
                input.readBytes()
            }
            val json = String(jsonBytes, Charsets.UTF_8)
            val data = gson.fromJson(json, SaveData::class.java)
            val elapsed = System.currentTimeMillis() - startTime
            Log.i(TAG, "Emergency save loaded in ${elapsed}ms")
            data
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load emergency save", e)
            null
        }
    }

    /**
     * 清除紧急存档
     * 在成功恢复紧急存档后调用
     * @return 是否清除成功
     */
    fun clearEmergencySave(): Boolean {
        return try {
            val emergencyFile = getEmergencySaveFile()
            if (emergencyFile.exists()) {
                val deleted = emergencyFile.delete()
                if (deleted) {
                    Log.i(TAG, "Emergency save cleared")
                } else {
                    Log.w(TAG, "Failed to delete emergency save file")
                }
                deleted
            } else {
                Log.d(TAG, "No emergency save to clear")
                true
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to clear emergency save", e)
            false
        }
    }

    data class SaveSlotMeta(
        val timestamp: Long = 0,
        val gameYear: Int = 1,
        val gameMonth: Int = 1,
        val sectName: String = "",
        val discipleCount: Int = 0,
        val spiritStones: Long = 0,
        val checksum: String = ""
    )

    enum class SaveVerificationResult {
        VALID,
        NOT_FOUND,
        CORRUPTED,
        CHECKSUM_MISMATCH
    }
}
