package com.xianxia.sect.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.data.model.SaveData
import com.xianxia.sect.data.model.SaveSlot
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.*
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.Deflater
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
        private const val BACKUP_EXTENSION = ".bak"
        private const val MAX_BATTLE_LOGS = 100
        private const val MAX_GAME_EVENTS = 50
        const val EMERGENCY_SAVE = "emergency"
        
        private const val COMPRESSION_LEVEL = Deflater.BEST_SPEED
        private const val SAVE_QUEUE_CAPACITY = 32
        private const val MIN_VALID_SAVE_SIZE = 100L
        
        // 多版本备份配置
        private const val MAX_BACKUP_VERSIONS = 5
        
        private const val STREAMED_SAVE_DATA_FIELD_COUNT = 17
        
        private const val HEALTH_CHECK_INTERVAL_MS = 60_000L
        private const val MIN_MEMORY_RATIO = 0.3
        private const val MAX_RETRY_COUNT = 2
        private const val RETRY_DELAY_MS = 200L
    }

    private fun logMemoryStatus(tag: String) {
        val runtime = Runtime.getRuntime()
        val maxMemory = runtime.maxMemory() / 1024 / 1024
        val totalMemory = runtime.totalMemory() / 1024 / 1024
        val freeMemory = runtime.freeMemory() / 1024 / 1024
        val usedMemory = totalMemory - freeMemory
        val availableMemory = maxMemory - usedMemory
        Log.d(TAG, "[$tag] Memory: max=${maxMemory}MB, used=${usedMemory}MB, available=${availableMemory}MB")
    }

    private fun checkAvailableMemory(): Boolean {
        val runtime = Runtime.getRuntime()
        val maxMemory = runtime.maxMemory()
        val usedMemory = runtime.totalMemory() - runtime.freeMemory()
        val availableMemory = maxMemory - usedMemory
        val ratio = availableMemory.toDouble() / maxMemory.toDouble()
        Log.d(TAG, "Memory check: available=${availableMemory/1024/1024}MB, ratio=${(ratio*100).toInt()}%")
        return ratio >= MIN_MEMORY_RATIO
    }

    private fun forceGcAndWait() {
        Log.d(TAG, "Forcing GC before save retry...")
        System.gc()
        try {
            Thread.sleep(RETRY_DELAY_MS)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val gson = GsonConfig.createGson()

    private val saveMutex = Mutex()
    private val loadMutex = Mutex()
    
    private val saveQueue = Channel<SaveRequest>(capacity = SAVE_QUEUE_CAPACITY)
    private val _saveQueueSize = MutableStateFlow(0)
    val saveQueueSize: StateFlow<Int> = _saveQueueSize.asStateFlow()
    
    private val saveScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    
    private val lastAutoSaveTime = AtomicLong(0L)
    private val lastSaveHash = AtomicReference<String?>(null)
    private val autoSaveDebounceMs = GameConfig.Game.AUTO_SAVE_DEBOUNCE_MS
    
    // 定期健康检查相关
    private var healthCheckJob: Job? = null
    private val _lastHealthCheckResult = MutableStateFlow<SaveHealthReport?>(null)
    val lastHealthCheckResult: StateFlow<SaveHealthReport?> = _lastHealthCheckResult.asStateFlow()
    
    private data class SaveRequest(
        val slot: Int,
        val data: SaveData,
        val isAutoSave: Boolean = false
    )

    private val saveDir: File
        get() = File(context.filesDir, SAVE_DIR).apply { if (!exists()) mkdirs() }

    init {
        verifyStreamSaveDataFields()
        migrateFromSharedPreferences()
        startSaveQueueProcessor()
        startPeriodicHealthCheck()
    }
    
    private fun verifyStreamSaveDataFields() {
        val expectedFields = SaveData::class.java.declaredFields.count { 
            !it.name.startsWith("$") && !java.lang.reflect.Modifier.isTransient(it.modifiers)
        }
        val streamedFields = STREAMED_SAVE_DATA_FIELD_COUNT
        if (expectedFields != streamedFields) {
            Log.e(TAG, "CRITICAL: streamSaveData field count mismatch! SaveData has $expectedFields fields, but streamSaveData writes $streamedFields fields. Data loss may occur!")
        } else {
            Log.d(TAG, "streamSaveData field count verified: $streamedFields fields")
        }
    }
    
    fun shutdown() {
        healthCheckJob?.cancel()
        saveScope.cancel()
        Log.i(TAG, "SaveManager shutdown completed")
    }
    
    private fun startSaveQueueProcessor() {
        saveScope.launch {
            for (request in saveQueue) {
                _saveQueueSize.value = _saveQueueSize.value - 1
                processSaveRequest(request)
            }
        }
    }
    
    private suspend fun processSaveRequest(request: SaveRequest) {
        saveMutex.withLock {
            if (request.isAutoSave) {
                val dataHash = computeDataHash(request.data)
                val currentHash = lastSaveHash.get()
                if (dataHash == currentHash) {
                    Log.d(TAG, "Skipping auto-save: data unchanged")
                    return@withLock
                }
                lastSaveHash.set(dataHash)
            }
            saveInternal(request.slot, request.data)
        }
    }
    
    private fun computeDataHash(data: SaveData): String {
        return try {
            val digest = MessageDigest.getInstance("SHA-256")
            
            // 游戏基础数据
            digest.update(data.version.toByteArray())
            digest.update(data.timestamp.toString().toByteArray())
            digest.update(data.gameData.gameYear.toString().toByteArray())
            digest.update(data.gameData.gameMonth.toByte())
            digest.update(data.gameData.gameDay.toByte())
            digest.update(data.gameData.spiritStones.toString().toByteArray())
            digest.update(data.gameData.sectName.toByteArray())
            
            // 弟子数据
            digest.update(data.disciples.size.toString().toByteArray())
            data.disciples.forEach { disciple ->
                digest.update(disciple.id.toString().toByteArray())
                digest.update(disciple.name.toByteArray())
                digest.update(disciple.realm.toString().toByteArray())
                digest.update(disciple.status.name.toByteArray())
                digest.update(disciple.cultivation.toString().toByteArray())
            }
            
            // 装备数据
            digest.update(data.equipment.size.toString().toByteArray())
            data.equipment.forEach { equip ->
                digest.update(equip.id.toString().toByteArray())
                digest.update(equip.rarity.toString().toByteArray())
            }
            
            // 功法数据
            digest.update(data.manuals.size.toString().toByteArray())
            data.manuals.forEach { manual ->
                digest.update(manual.id.toString().toByteArray())
                digest.update(manual.rarity.toString().toByteArray())
            }
            
            // 丹药数据
            digest.update(data.pills.size.toString().toByteArray())
            
            // 材料数据
            digest.update(data.materials.size.toString().toByteArray())
            
            // 灵草数据
            digest.update(data.herbs.size.toString().toByteArray())
            
            // 种子数据
            digest.update(data.seeds.size.toString().toByteArray())
            
            // 队伍数据
            digest.update(data.teams.size.toString().toByteArray())
            data.teams.forEach { team ->
                digest.update(team.id.toString().toByteArray())
                digest.update(team.memberIds.joinToString(",").toByteArray())
            }
            
            // 建筑槽位数据
            digest.update(data.slots.size.toString().toByteArray())
            
            // 联盟数据
            digest.update(data.alliances.size.toString().toByteArray())
            
            // 支援队伍数据
            digest.update(data.supportTeams.size.toString().toByteArray())
            
            // 炼丹槽位数据
            digest.update(data.alchemySlots.size.toString().toByteArray())
            
            // 取前16个字符作为短哈希
            digest.digest().take(8).joinToString("") { "%02x".format(it) }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to compute data hash", e)
            System.currentTimeMillis().toString()
        }
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
                isEmpty = false,
                customName = meta.customName ?: ""
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
                isEmpty = false,
                customName = ""
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
        return saveMutex.tryLock().let { locked ->
            if (locked) {
                try {
                    saveInternal(slot, data)
                } finally {
                    saveMutex.unlock()
                }
            } else {
                Log.w(TAG, "Save mutex is locked, another save operation is in progress for slot $slot")
                false
            }
        }
    }
    
    private fun saveInternal(slot: Int, data: SaveData): Boolean {
        Log.i(TAG, "=== save BEGIN === slot=$slot, sectName=${data.gameData.sectName}, " +
            "year=${data.gameData.gameYear}, month=${data.gameData.gameMonth}, day=${data.gameData.gameDay}, " +
            "spiritStones=${data.gameData.spiritStones}, disciples=${data.disciples.count { it.isAlive }}")
        
        logMemoryStatus("save BEGIN")

        val startTime = System.currentTimeMillis()
        val cleanedData = cleanSaveData(data)
        val saveFile = getSaveFile(slot)
        val tempFile = File(saveFile.parent, "${saveFile.name}.tmp")
        
        if (!checkAvailableMemory()) {
            Log.w(TAG, "Low memory before save, attempting GC")
            forceGcAndWait()
            if (!checkAvailableMemory()) {
                Log.e(TAG, "Insufficient memory for save operation after GC")
                return false
            }
        }
        
        var retryCount = 0
        var lastError: Throwable? = null
        
        while (retryCount <= MAX_RETRY_COUNT) {
            var saveCompleted = false
            
            try {
                val checksum = saveToFileWithChecksum(tempFile, cleanedData)
                
                if (saveFile.exists()) {
                    rotateBackupVersions(slot)
                    val backupFile = getBackupFile(slot, 1)
                    if (saveFile.renameTo(backupFile)) {
                        Log.d(TAG, "Created backup version 1 for slot $slot")
                    } else {
                        Log.w(TAG, "Failed to create backup for slot $slot, continuing anyway")
                    }
                }

                if (!tempFile.renameTo(saveFile)) {
                    Log.e(TAG, "Failed to rename temp file to save file for slot $slot")
                    val backupFile = getBackupFile(slot, 1)
                    if (backupFile.exists()) {
                        backupFile.renameTo(saveFile)
                        Log.i(TAG, "Restored backup after rename failure for slot $slot")
                    }
                    return false
                }

                saveMetaWithChecksum(slot, cleanedData, checksum)

                saveCompleted = true
                val elapsed = System.currentTimeMillis() - startTime
                Log.i(TAG, "=== save SUCCESS === slot=$slot, elapsed=${elapsed}ms, " +
                    "compressedSize=${saveFile.length()} bytes, " +
                    "disciples=${cleanedData.disciples.size}, equipment=${cleanedData.equipment.size}, " +
                    "manuals=${cleanedData.manuals.size}, pills=${cleanedData.pills.size}")
                logMemoryStatus("save SUCCESS")
                return true
            } catch (e: OutOfMemoryError) {
                lastError = e
                retryCount++
                Log.w(TAG, "OOM during save (attempt $retryCount/$MAX_RETRY_COUNT), slot=$slot")
                logMemoryStatus("save OOM")
                if (tempFile.exists()) tempFile.delete()
                forceGcAndWait()
            } catch (e: FileNotFoundException) {
                Log.e(TAG, "=== save FAILED === slot=$slot, error=FileNotFoundException", e)
                return false
            } catch (e: IOException) {
                Log.e(TAG, "=== save FAILED === slot=$slot, error=IOException", e)
                return false
            } catch (e: Exception) {
                Log.e(TAG, "=== save FAILED === slot=$slot, error=${e.javaClass.simpleName}", e)
                return false
            } finally {
                if (!saveCompleted && tempFile.exists()) {
                    tempFile.delete()
                }
            }
        }
        
        Log.e(TAG, "=== save FAILED === slot=$slot, error=OutOfMemoryError after $MAX_RETRY_COUNT retries", lastError)
        return false
    }

    suspend fun saveAsync(slot: Int, data: SaveData): Boolean {
        if (!isValidSlot(slot)) {
            Log.e(TAG, "Invalid save slot: $slot")
            return false
        }
        return saveMutex.withLock {
            withContext(Dispatchers.IO) {
                saveInternal(slot, data)
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

        val verification = verifySaveWithData(slot)
        if (verification.status == SaveVerificationResult.VALID && verification.data != null) {
            val data = verification.data
            val elapsed = System.currentTimeMillis() - startTime
            Log.i(TAG, "=== load SUCCESS === slot=$slot, elapsed=${elapsed}ms, " +
                "sectName=${data.gameData.sectName}, year=${data.gameData.gameYear}, " +
                "month=${data.gameData.gameMonth}, day=${data.gameData.gameDay}, " +
                "spiritStones=${data.gameData.spiritStones}, disciples=${data.disciples.count { it.isAlive }}")
            return data
        }

        Log.w(TAG, "=== load RETRY === slot=$slot, verification=${verification.status}, attempting backup recovery")
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
        val now = System.currentTimeMillis()
        val lastSave = lastAutoSaveTime.get()
        if (now - lastSave < autoSaveDebounceMs) {
            Log.d(TAG, "Auto-save debounced, last save was ${now - lastSave}ms ago")
            return
        }
        lastAutoSaveTime.set(now)
        
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
    
    fun queueAutoSave(slot: Int, data: SaveData) {
        val now = System.currentTimeMillis()
        val lastSave = lastAutoSaveTime.get()
        if (now - lastSave < autoSaveDebounceMs) {
            Log.d(TAG, "Auto-save queued but debounced")
            return
        }
        lastAutoSaveTime.set(now)
        
        val dataHash = computeDataHash(data)
        val currentHash = lastSaveHash.get()
        if (dataHash == currentHash) {
            Log.d(TAG, "Skipping queued auto-save: data unchanged")
            return
        }
        
        _saveQueueSize.value = _saveQueueSize.value + 1
        saveQueue.trySendBlocking(SaveRequest(slot, data, isAutoSave = true))
        Log.d(TAG, "Auto-save queued for slot $slot, queue size: ${_saveQueueSize.value}")
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
        
        BufferedOutputStream(FileOutputStream(file), 64 * 1024).use { buffer ->
            OptimizedGZIPOutputStream(buffer, COMPRESSION_LEVEL).use { gzipOutput ->
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
                    streamSaveData(data, writer)
                }
            }
        }

        val checksum = digest.digest().joinToString("") { "%02x".format(it) }
        Log.d(TAG, "Saved to ${file.name}, compressed size: ${file.length()} bytes, checksum: ${checksum.take(8)}...")
        return checksum
    }
    
    private fun streamSaveData(data: SaveData, writer: OutputStreamWriter) {
        val gson = this.gson
        writer.write("{\"version\":")
        writer.write(gson.toJson(data.version))
        writer.write(",\"timestamp\":")
        writer.write(data.timestamp.toString())
        writer.write(",\"gameData\":")
        gson.toJson(data.gameData, writer)
        writer.write(",\"disciples\":")
        gson.toJson(data.disciples, writer)
        writer.write(",\"equipment\":")
        gson.toJson(data.equipment, writer)
        writer.write(",\"manuals\":")
        gson.toJson(data.manuals, writer)
        writer.write(",\"pills\":")
        gson.toJson(data.pills, writer)
        writer.write(",\"materials\":")
        gson.toJson(data.materials, writer)
        writer.write(",\"herbs\":")
        gson.toJson(data.herbs, writer)
        writer.write(",\"seeds\":")
        gson.toJson(data.seeds, writer)
        writer.write(",\"teams\":")
        gson.toJson(data.teams, writer)
        writer.write(",\"slots\":")
        gson.toJson(data.slots, writer)
        writer.write(",\"events\":")
        gson.toJson(data.events, writer)
        writer.write(",\"battleLogs\":")
        gson.toJson(data.battleLogs, writer)
        writer.write(",\"alliances\":")
        gson.toJson(data.alliances, writer)
        writer.write(",\"supportTeams\":")
        gson.toJson(data.supportTeams, writer)
        writer.write(",\"alchemySlots\":")
        gson.toJson(data.alchemySlots, writer)
        writer.write("}")
    }
    
    private class OptimizedGZIPOutputStream(
        out: OutputStream,
        level: Int = Deflater.BEST_SPEED
    ) : GZIPOutputStream(out) {
        init {
            def.setLevel(level)
        }
    }

    private fun loadFromFile(slot: Int): SaveData? {
        val saveFile = getSaveFile(slot)
        if (!saveFile.exists()) return null
        return loadFromFile(saveFile)
    }

    private fun loadFromFile(file: File): SaveData? {
        return try {
            val startTime = System.currentTimeMillis()
            val data = BufferedInputStream(FileInputStream(file), 64 * 1024).use { input ->
                GZIPInputStream(input).use { gzipInput ->
                    InputStreamReader(gzipInput, Charsets.UTF_8).use { reader ->
                        gson.fromJson(reader, SaveData::class.java)
                    }
                }
            }
            val elapsed = System.currentTimeMillis() - startTime
            val fileSize = file.length()
            Log.d(TAG, "Loaded from ${file.name} in ${elapsed}ms, compressed size: ${fileSize} bytes")
            
            migrateSaveData(data)
        } catch (e: FileNotFoundException) {
            Log.e(TAG, "File not found: ${file.name}", e)
            null
        } catch (e: java.util.zip.ZipException) {
            Log.e(TAG, "GZIP decompression failed for ${file.name}, file may be corrupted", e)
            null
        } catch (e: com.google.gson.JsonSyntaxException) {
            Log.e(TAG, "JSON parsing failed for ${file.name}, data may be corrupted: ${e.message}", e)
            null
        } catch (e: IllegalArgumentException) {
            Log.e(TAG, "Enum deserialization failed for ${file.name}, unknown enum value: ${e.message}", e)
            null
        } catch (e: IOException) {
            Log.e(TAG, "IO error while loading ${file.name}", e)
            null
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected error while loading ${file.name}: ${e.javaClass.simpleName} - ${e.message}", e)
            null
        }
    }

    private fun saveMetaWithChecksum(slot: Int, data: SaveData, checksum: String, customName: String = "") {
        try {
            val meta = SaveSlotMeta(
                timestamp = data.timestamp,
                gameYear = data.gameData.gameYear,
                gameMonth = data.gameData.gameMonth,
                sectName = data.gameData.sectName,
                discipleCount = data.disciples.count { it.isAlive },
                spiritStones = data.gameData.spiritStones,
                checksum = checksum,
                customName = customName
            )
            val metaFile = getMetaFile(slot)
            metaFile.writeText(gson.toJson(meta))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save meta for slot $slot", e)
        }
    }
    
    /**
     * 重命名存档槽位
     * @param slot 存档槽位
     * @param customName 自定义名称
     * @return 是否重命名成功
     */
    fun renameSlot(slot: Int, customName: String): Boolean {
        if (!isValidSlot(slot)) {
            Log.e(TAG, "Invalid slot for rename: $slot")
            return false
        }
        
        val metaFile = getMetaFile(slot)
        if (!metaFile.exists()) {
            Log.w(TAG, "No meta file found for slot $slot, cannot rename")
            return false
        }
        
        return try {
            val metaJson = metaFile.readText()
            val meta = gson.fromJson(metaJson, SaveSlotMeta::class.java)
            val updatedMeta = meta.copy(customName = customName)
            metaFile.writeText(gson.toJson(updatedMeta))
            Log.i(TAG, "Renamed slot $slot to '$customName'")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to rename slot $slot", e)
            false
        }
    }
    
    // ==================== 版本迁移相关方法 ====================
    
    /**
     * 执行存档数据版本迁移
     * @param data 原始存档数据
     * @return 迁移后的存档数据
     */
    private fun migrateSaveData(data: SaveData): SaveData {
        val currentVersion = GameConfig.Game.VERSION
        val saveVersion = data.version
        
        // 如果版本相同或存档版本更新，无需迁移
        if (compareVersions(saveVersion, currentVersion) >= 0) {
            return data
        }
        
        Log.i(TAG, "Migrating save data from version $saveVersion to $currentVersion")
        
        var migratedData = data
        
        // 按版本号执行迁移
        if (compareVersions(saveVersion, "1.4.85") < 0) {
            migratedData = migrateTo_1_4_85(migratedData)
        }
        
        // 更新版本号
        if (migratedData.version != currentVersion) {
            migratedData = migratedData.copy(version = currentVersion)
            Log.i(TAG, "Save data version updated to $currentVersion")
        }
        
        return migratedData
    }
    
    /**
     * 迁移到版本 1.4.85
     * 添加新字段的默认值
     */
    private fun migrateTo_1_4_85(data: SaveData): SaveData {
        Log.d(TAG, "Migrating to version 1.4.85")
        
        // 确保 alliances 字段不为 null（如果之前不存在）
        val alliances = data.alliances ?: emptyList()
        
        // 确保 supportTeams 字段不为 null（如果之前不存在）
        val supportTeams = data.supportTeams ?: emptyList()
        
        // 确保 alchemySlots 字段不为 null（如果之前不存在）
        val alchemySlots = data.alchemySlots ?: emptyList()
        
        return data.copy(
            version = "1.4.85",
            alliances = alliances,
            supportTeams = supportTeams,
            alchemySlots = alchemySlots
        )
    }
    
    /**
     * 比较版本号
     * @return 负数表示 v1 < v2，0 表示相等，正数表示 v1 > v2
     */
    private fun compareVersions(v1: String, v2: String): Int {
        val parts1 = v1.split(".").map { it.toIntOrNull() ?: 0 }
        val parts2 = v2.split(".").map { it.toIntOrNull() ?: 0 }
        
        val maxLen = maxOf(parts1.size, parts2.size)
        for (i in 0 until maxLen) {
            val p1 = parts1.getOrElse(i) { 0 }
            val p2 = parts2.getOrElse(i) { 0 }
            if (p1 != p2) {
                return p1.compareTo(p2)
            }
        }
        return 0
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

        val cleanedWorldMapSects = data.gameData.worldMapSects.map { sect ->
            val aliveAiDisciples = sect.aiDisciples.filter { it.isAlive }
            if (aliveAiDisciples.size != sect.aiDisciples.size) {
                Log.d(TAG, "Cleaning dead AI disciples from sect ${sect.name}: ${sect.aiDisciples.size - aliveAiDisciples.size} removed")
            }
            sect.copy(aiDisciples = aliveAiDisciples)
        }

        val cleanedSupportTeams = data.supportTeams.map { team ->
            val aliveAiDisciples = team.aiDisciples.filter { it.isAlive }
            team.copy(aiDisciples = aliveAiDisciples)
        }

        val cleanedGameData = data.gameData.copy(worldMapSects = cleanedWorldMapSects)

        return data.copy(
            battleLogs = cleanedBattleLogs,
            events = cleanedEvents,
            gameData = cleanedGameData,
            supportTeams = cleanedSupportTeams
        )
    }

    private data class VerificationResult(
        val status: SaveVerificationResult,
        val data: SaveData? = null
    )

    private fun verifySaveWithData(slot: Int): VerificationResult {
        val saveFile = getSaveFile(slot)
        if (!saveFile.exists()) {
            return VerificationResult(SaveVerificationResult.NOT_FOUND)
        }

        return try {
            val data = loadFromFile(slot)
            if (data == null) {
                Log.w(TAG, "Save file cannot be parsed for slot $slot")
                return VerificationResult(SaveVerificationResult.CORRUPTED)
            }
            
            Log.d(TAG, "Save verification passed for slot $slot")
            VerificationResult(SaveVerificationResult.VALID, data)
        } catch (e: Exception) {
            Log.e(TAG, "Save verification failed for slot $slot", e)
            VerificationResult(SaveVerificationResult.CORRUPTED)
        }
    }

    fun verifySave(slot: Int): SaveVerificationResult {
        return verifySaveWithData(slot).status
    }

    /**
     * 计算存档数据的 checksum（解压后重新计算）
     * 这与保存时计算 checksum 的方式一致
     */
    private fun calculateDataChecksum(slot: Int): String {
        val saveFile = getSaveFile(slot)
        if (!saveFile.exists()) return ""

        return try {
            val digest = MessageDigest.getInstance("MD5")
            BufferedInputStream(FileInputStream(saveFile), 64 * 1024).use { input ->
                GZIPInputStream(input).use { gzipInput ->
                    val buffer = ByteArray(8192)
                    var read: Int
                    while (gzipInput.read(buffer).also { read = it } > 0) {
                        digest.update(buffer, 0, read)
                    }
                }
            }
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
    
    // ==================== 多版本备份相关方法 ====================
    
    /**
     * 获取备份文件路径
     * @param slot 存档槽位
     * @param version 备份版本号 (1 = 最新备份)
     */
    private fun getBackupFile(slot: Int, version: Int): File {
        return File(saveDir, "slot_$slot$BACKUP_EXTENSION.$version")
    }
    
    /**
     * 轮转备份版本
     * 将旧备份向后移动，删除最老的备份
     */
    private fun rotateBackupVersions(slot: Int) {
        // 从最老的版本开始删除
        val oldestBackup = getBackupFile(slot, MAX_BACKUP_VERSIONS)
        if (oldestBackup.exists()) {
            if (!oldestBackup.delete()) {
                Log.w(TAG, "Failed to delete oldest backup for slot $slot")
            }
        }
        
        // 将现有备份向后移动
        for (version in MAX_BACKUP_VERSIONS - 1 downTo 1) {
            val currentBackup = getBackupFile(slot, version)
            if (currentBackup.exists()) {
                val nextBackup = getBackupFile(slot, version + 1)
                if (currentBackup.renameTo(nextBackup)) {
                    Log.d(TAG, "Rotated backup version $version to ${version + 1} for slot $slot")
                } else {
                    Log.w(TAG, "Failed to rotate backup version $version for slot $slot")
                }
            }
        }
    }
    
    /**
     * 获取所有可用的备份版本
     * @param slot 存档槽位
     * @return 备份版本列表，按时间倒序排列
     */
    fun getBackupVersions(slot: Int): List<BackupVersion> {
        if (!isValidSlot(slot)) return emptyList()
        
        val versions = mutableListOf<BackupVersion>()
        for (version in 1..MAX_BACKUP_VERSIONS) {
            val backupFile = getBackupFile(slot, version)
            if (backupFile.exists() && backupFile.length() >= MIN_VALID_SAVE_SIZE) {
                versions.add(BackupVersion(
                    slot = slot,
                    version = version,
                    timestamp = backupFile.lastModified(),
                    fileSize = backupFile.length()
                ))
            }
        }
        return versions.sortedByDescending { it.timestamp }
    }
    
    /**
     * 从指定备份版本恢复
     * @param slot 存档槽位
     * @param version 备份版本号
     * @return 恢复后的存档数据，如果恢复失败返回 null
     */
    fun restoreFromBackupVersion(slot: Int, version: Int): SaveData? {
        if (!isValidSlot(slot)) {
            Log.e(TAG, "Invalid slot for backup restore: $slot")
            return null
        }
        
        val backupFile = getBackupFile(slot, version)
        if (!backupFile.exists()) {
            Log.w(TAG, "Backup version $version not found for slot $slot")
            return null
        }
        
        return try {
            Log.i(TAG, "Attempting to restore slot $slot from backup version $version")
            
            val data = loadFromFile(backupFile)
            if (data != null) {
                // 验证备份数据有效性
                val saveFile = getSaveFile(slot)
                
                // 保存当前存档为新备份（如果存在）
                if (saveFile.exists()) {
                    rotateBackupVersions(slot)
                    saveFile.renameTo(getBackupFile(slot, 1))
                }
                
                // 复制备份文件到存档位置
                backupFile.copyTo(saveFile, overwrite = true)
                
                // 重新生成元数据
                val checksum = calculateDataChecksum(slot)
                saveMetaWithChecksum(slot, data, checksum)
                
                Log.i(TAG, "Successfully restored slot $slot from backup version $version")
                data
            } else {
                Log.e(TAG, "Failed to load backup version $version for slot $slot")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to restore slot $slot from backup version $version", e)
            null
        }
    }
    
    /**
     * 备份版本信息
     */
    data class BackupVersion(
        val slot: Int,
        val version: Int,
        val timestamp: Long,
        val fileSize: Long
    )

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

            BufferedOutputStream(FileOutputStream(tempFile), 64 * 1024).use { buffer ->
                OptimizedGZIPOutputStream(buffer, COMPRESSION_LEVEL).use { gzipOutput ->
                    OutputStreamWriter(gzipOutput, Charsets.UTF_8).use { writer ->
                        streamSaveData(data.copy(timestamp = System.currentTimeMillis()), writer)
                        writer.flush()
                    }
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
            val data = BufferedInputStream(FileInputStream(emergencyFile), 64 * 1024).use { input ->
                GZIPInputStream(input).use { gzipInput ->
                    InputStreamReader(gzipInput, Charsets.UTF_8).use { reader ->
                        gson.fromJson(reader, SaveData::class.java)
                    }
                }
            }
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
    
    fun performHealthCheck(): SaveHealthReport{
        val startTime = System.currentTimeMillis()
        val slotReports = mutableMapOf<Int, SlotHealthReport>()
        var totalIssues = 0
        
        for (slot in 1..MAX_SLOTS) {
            val report = quickCheckSlotHealth(slot)
            slotReports[slot] = report
            if (report.hasIssues) totalIssues++
        }
        
        val autoSaveHealthy = quickCheckFileHealth(getAutoSaveFile())
        val emergencySaveHealthy = quickCheckFileHealth(getEmergencySaveFile())
        
        val elapsed = System.currentTimeMillis() - startTime
        Log.i(TAG, "Health check completed in ${elapsed}ms, found $totalIssues issues")
        
        return SaveHealthReport(
            slotReports = slotReports,
            autoSaveHealthy = autoSaveHealthy,
            emergencySaveHealthy = emergencySaveHealthy,
            totalIssues = totalIssues,
            checkDurationMs = elapsed
        )
    }
    
    private fun quickCheckSlotHealth(slot: Int): SlotHealthReport{
        val saveFile = getSaveFile(slot)
        val metaFile = getMetaFile(slot)
        val backupFile = File(saveFile.parent, "${saveFile.name}.bak")
        
        val issues = mutableListOf<String>()
        var canRecover = false
        
        if (!saveFile.exists()) {
            return SlotHealthReport(
                slot = slot,
                exists = false,
                hasIssues = false,
                issues = emptyList(),
                canRecover = false,
                fileSize = 0
            )
        }
        
        val fileSize = saveFile.length()
        if (fileSize < MIN_VALID_SAVE_SIZE) {
            issues.add("Save file too small: $fileSize bytes")
        }
        
        if (!metaFile.exists()) {
            issues.add("Meta file missing")
        } else {
            try {
                val metaJson = metaFile.readText()
                val meta = gson.fromJson(metaJson, SaveSlotMeta::class.java)
                if (meta.timestamp <= 0) {
                    issues.add("Invalid meta timestamp")
                }
            } catch (e: Exception) {
                issues.add("Meta file corrupted: ${e.message}")
            }
        }
        
        if (backupFile.exists() && backupFile.length() >= MIN_VALID_SAVE_SIZE) {
            canRecover = true
        }
        
        return SlotHealthReport(
            slot = slot,
            exists = true,
            hasIssues = issues.isNotEmpty(),
            issues = issues,
            canRecover = canRecover,
            fileSize = fileSize
        )
    }
    
    private fun quickCheckFileHealth(file: File): Boolean {
        if (!file.exists()) return true
        if (file.length() < MIN_VALID_SAVE_SIZE) return false
        
        return try {
            GZIPInputStream(FileInputStream(file)).use { input ->
                val buffer = ByteArray(1024)
                input.read(buffer) > 0
            }
            true
        } catch (e: Exception) {
            Log.w(TAG, "Quick health check failed for ${file.name}", e)
            false
        }
    }
    
    fun performDeepHealthCheck(): SaveHealthReport{
        val startTime = System.currentTimeMillis()
        val slotReports = mutableMapOf<Int, SlotHealthReport>()
        var totalIssues = 0
        
        for (slot in 1..MAX_SLOTS) {
            val report = checkSlotHealth(slot)
            slotReports[slot] = report
            if (report.hasIssues) totalIssues++
        }
        
        val autoSaveHealthy = checkAutoSaveHealth()
        val emergencySaveHealthy = checkEmergencySaveHealth()
        
        val elapsed = System.currentTimeMillis() - startTime
        Log.i(TAG, "Deep health check completed in ${elapsed}ms, found $totalIssues issues")
        
        return SaveHealthReport(
            slotReports = slotReports,
            autoSaveHealthy = autoSaveHealthy,
            emergencySaveHealthy = emergencySaveHealthy,
            totalIssues = totalIssues,
            checkDurationMs = elapsed
        )
    }
    
    private fun checkSlotHealth(slot: Int): SlotHealthReport{
        val saveFile = getSaveFile(slot)
        val metaFile = getMetaFile(slot)
        val backupFile = File(saveFile.parent, "${saveFile.name}.bak")
        
        val issues = mutableListOf<String>()
        var canRecover = false
        
        if (!saveFile.exists()) {
            return SlotHealthReport(
                slot = slot,
                exists = false,
                hasIssues = false,
                issues = emptyList(),
                canRecover = false,
                fileSize = 0
            )
        }
        
        val verification = verifySave(slot)
        if (verification != SaveVerificationResult.VALID) {
            issues.add("Save verification failed: $verification")
            if (backupFile.exists()) {
                canRecover = true
            }
        }
        
        if (!metaFile.exists()) {
            issues.add("Meta file missing")
        }
        
        return SlotHealthReport(
            slot = slot,
            exists = true,
            hasIssues = issues.isNotEmpty(),
            issues = issues,
            canRecover = canRecover,
            fileSize = saveFile.length()
        )
    }
    
    private fun checkAutoSaveHealth(): Boolean {
        val autoSaveFile = getAutoSaveFile()
        if (!autoSaveFile.exists()) return true
        
        return try {
            val data = loadFromFile(autoSaveFile)
            data != null
        } catch (e: Exception) {
            Log.w(TAG, "Auto-save health check failed", e)
            false
        }
    }
    
    private fun checkEmergencySaveHealth(): Boolean {
        val emergencyFile = getEmergencySaveFile()
        if (!emergencyFile.exists()) return true
        
        return try {
            val data = loadFromFile(emergencyFile)
            data != null
        } catch (e: Exception) {
            Log.w(TAG, "Emergency save health check failed", e)
            false
        }
    }
    
    fun repairAllSaves(): Int {
        var repairedCount = 0
        
        for (slot in 1..MAX_SLOTS) {
            val report = checkSlotHealth(slot)
            if (report.hasIssues && report.canRecover) {
                if (restoreFromBackup(slot)) {
                    Log.i(TAG, "Repaired slot $slot from backup")
                    repairedCount++
                }
            }
        }
        
        return repairedCount
    }
    
    /**
     * 启动定期健康检查
     */
    private fun startPeriodicHealthCheck() {
        healthCheckJob = saveScope.launch {
            while (isActive) {
                delay(HEALTH_CHECK_INTERVAL_MS)
                try {
                    val report = performHealthCheck()
                    _lastHealthCheckResult.value = report
                    if (report.totalIssues > 0) {
                        Log.w(TAG, "Periodic health check found ${report.totalIssues} issues")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Periodic health check failed", e)
                }
            }
        }
    }
    
    data class SaveHealthReport(
        val slotReports: Map<Int, SlotHealthReport>,
        val autoSaveHealthy: Boolean,
        val emergencySaveHealthy: Boolean,
        val totalIssues: Int,
        val checkDurationMs: Long
    )
    
    data class SlotHealthReport(
        val slot: Int,
        val exists: Boolean,
        val hasIssues: Boolean,
        val issues: List<String>,
        val canRecover: Boolean,
        val fileSize: Long
    )

    data class SaveSlotMeta(
        val timestamp: Long = 0,
        val gameYear: Int = 1,
        val gameMonth: Int = 1,
        val sectName: String = "",
        val discipleCount: Int = 0,
        val spiritStones: Long = 0,
        val checksum: String = "",
        val customName: String = ""
    )

    enum class SaveVerificationResult {
        VALID,
        NOT_FOUND,
        CORRUPTED,
        CHECKSUM_MISMATCH
    }
}
