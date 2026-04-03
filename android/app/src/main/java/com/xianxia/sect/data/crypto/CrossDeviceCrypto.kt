@file:OptIn(InternalSerializationApi::class, ExperimentalSerializationApi::class)

package com.xianxia.sect.data.crypto

import android.content.Context
import android.util.Log
import com.xianxia.sect.data.model.SaveData
import com.xianxia.sect.data.serialization.unified.SerializableSaveData
import com.xianxia.sect.data.serialization.unified.SaveDataConverter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer
import java.io.*
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

object CrossDeviceCrypto {
    private const val TAG = "CrossDeviceCrypto"

    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val KEY_ALGORITHM = "AES"
    private const val KEY_SIZE = 256
    private const val GCM_TAG_LENGTH = 128
    private const val GCM_IV_LENGTH = 12
    private const val HMAC_ALGORITHM = "HmacSHA256"
    private const val HMAC_LENGTH = 32
    private const val SALT_LENGTH = 32
    private const val PBKDF2_ITERATIONS = 310000

    private const val BACKUP_MAGIC = "XIANXIA_BACKUP_V1"
    private const val BACKUP_VERSION = 1

    private val secureRandom = SecureRandom()

    data class BackupHeader(
        val magic: String,
        val version: Int,
        val timestamp: Long,
        val slot: Int,
        val dataSize: Long,
        val checksum: String,
        val gameVersion: String,
        val sectName: String,
        val gameYear: Int,
        val gameMonth: Int
    )

    fun deriveKeyFromPassword(password: String, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(
            password.toCharArray(),
            salt,
            PBKDF2_ITERATIONS,
            KEY_SIZE
        )
        return try {
            val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            factory.generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    fun createPasswordBasedBackup(
        saveData: SaveData,
        password: String,
        slot: Int,
        gameVersion: String
    ): ByteArray {
        val salt = ByteArray(SALT_LENGTH).also { secureRandom.nextBytes(it) }
        val iv = ByteArray(GCM_IV_LENGTH).also { secureRandom.nextBytes(it) }
        val key = deriveKeyFromPassword(password, salt)

        val serializedData = serializeSaveData(saveData)
        val compressedData = compressData(serializedData)

        val cipher = Cipher.getInstance(TRANSFORMATION)
        val secretKey = SecretKeySpec(key, KEY_ALGORITHM)
        val gcmSpec = GCMParameterSpec(GCM_TAG_LENGTH, iv)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey, gcmSpec)

        val encryptedData = cipher.doFinal(compressedData)

        val header = createBackupHeader(saveData, slot, gameVersion, encryptedData.size.toLong())
        val headerBytes = serializeHeader(header)

        val result = ByteArrayOutputStream()
        DataOutputStream(result).use { dos ->
            dos.writeInt(headerBytes.size)
            dos.write(headerBytes)
            dos.write(salt)
            dos.write(iv)
            dos.writeInt(encryptedData.size)
            dos.write(encryptedData)

            val hmac = calculateHMAC(
                result.toByteArray(),
                key
            )
            dos.write(hmac)
        }

        secureWipe(key)
        return result.toByteArray()
    }

    fun restoreFromPasswordBasedBackup(
        backupData: ByteArray,
        password: String
    ): RestoreResult {
        return try {
            val dis = DataInputStream(ByteArrayInputStream(backupData))

            val headerSize = dis.readInt()
            val headerBytes = ByteArray(headerSize)
            dis.readFully(headerBytes)
            val header = deserializeHeader(headerBytes)

            if (header.magic != BACKUP_MAGIC) {
                return RestoreResult.Error("Invalid backup format")
            }

            if (header.version > BACKUP_VERSION) {
                return RestoreResult.Error("Unsupported backup version: ${header.version}")
            }

            val salt = ByteArray(SALT_LENGTH)
            dis.readFully(salt)

            val iv = ByteArray(GCM_IV_LENGTH)
            dis.readFully(iv)

            val encryptedSize = dis.readInt()
            val encryptedData = ByteArray(encryptedSize)
            dis.readFully(encryptedData)

            val storedHmac = ByteArray(HMAC_LENGTH)
            dis.readFully(storedHmac)

            val key = deriveKeyFromPassword(password, salt)

            val dataWithoutHmac = backupData.copyOfRange(0, backupData.size - HMAC_LENGTH)
            val calculatedHmac = calculateHMAC(dataWithoutHmac, key)

            if (!storedHmac.contentEquals(calculatedHmac)) {
                secureWipe(key)
                return RestoreResult.Error("Password incorrect or data corrupted")
            }

            val cipher = Cipher.getInstance(TRANSFORMATION)
            val secretKey = SecretKeySpec(key, KEY_ALGORITHM)
            val gcmSpec = GCMParameterSpec(GCM_TAG_LENGTH, iv)
            cipher.init(Cipher.DECRYPT_MODE, secretKey, gcmSpec)

            val compressedData = cipher.doFinal(encryptedData)
            val serializedData = decompressData(compressedData)
            val saveData = deserializeSaveData(serializedData)

            secureWipe(key)

            Log.i(TAG, "Successfully restored backup for slot ${header.slot}")
            RestoreResult.Success(saveData, header)

        } catch (e: Exception) {
            Log.e(TAG, "Failed to restore backup", e)
            RestoreResult.Error(e.message ?: "Unknown error")
        }
    }

    fun createExportCode(backupData: ByteArray): String {
        val checksum = sha256(backupData).take(8)
        val encoded = android.util.Base64.encodeToString(
            backupData,
            android.util.Base64.NO_WRAP or android.util.Base64.URL_SAFE
        )

        val chunks = encoded.chunked(80)
        val sb = StringBuilder()

        sb.append("XIANXIA-EXPORT-V1\n")
        sb.append("CHECKSUM:$checksum\n")
        sb.append("SIZE:${backupData.size}\n")
        sb.append("DATA:\n")

        chunks.forEach { chunk ->
            sb.append(chunk)
            sb.append("\n")
        }

        sb.append("END-EXPORT")

        return sb.toString()
    }

    fun parseExportCode(exportCode: String): ByteArray? {
        return try {
            val lines = exportCode.lines()

            if (lines.isEmpty() || lines[0] != "XIANXIA-EXPORT-V1") {
                Log.e(TAG, "Invalid export code format")
                return null
            }

            var checksum: String? = null
            var expectedSize: Int? = null
            var dataIndex = -1

            for (i in 1 until lines.size) {
                val line = lines[i]
                when {
                    line.startsWith("CHECKSUM:") -> {
                        checksum = line.substringAfter("CHECKSUM:")
                    }
                    line.startsWith("SIZE:") -> {
                        expectedSize = line.substringAfter("SIZE:").toIntOrNull()
                    }
                    line == "DATA:" -> {
                        dataIndex = i + 1
                        break
                    }
                }
            }

            if (dataIndex < 0 || checksum == null || expectedSize == null) {
                Log.e(TAG, "Missing required fields in export code")
                return null
            }

            val dataBuilder = StringBuilder()
            for (i in dataIndex until lines.size) {
                val line = lines[i]
                if (line == "END-EXPORT") break
                dataBuilder.append(line)
            }

            val backupData = android.util.Base64.decode(
                dataBuilder.toString(),
                android.util.Base64.NO_WRAP or android.util.Base64.URL_SAFE
            )

            if (backupData.size != expectedSize) {
                Log.e(TAG, "Size mismatch: expected=$expectedSize, actual=${backupData.size}")
                return null
            }

            val actualChecksum = sha256(backupData).take(8)
            if (actualChecksum != checksum) {
                Log.e(TAG, "Checksum mismatch: expected=$checksum, actual=$actualChecksum")
                return null
            }

            backupData

        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse export code", e)
            null
        }
    }

    fun generateRecoveryKey(): String {
        val bytes = ByteArray(32)
        secureRandom.nextBytes(bytes)

        val encoded = android.util.Base64.encodeToString(
            bytes,
            android.util.Base64.NO_WRAP or android.util.Base64.URL_SAFE
        )

        return encoded.chunked(4).joinToString("-")
    }

    fun createRecoveryKeyBackup(
        saveData: SaveData,
        recoveryKey: String,
        slot: Int,
        gameVersion: String
    ): ByteArray {
        val keyBytes = recoveryKey.replace("-", "").let {
            android.util.Base64.decode(it, android.util.Base64.NO_WRAP or android.util.Base64.URL_SAFE)
        }

        val salt = ByteArray(SALT_LENGTH).also { secureRandom.nextBytes(it) }
        val combinedKey = deriveKeyFromRecoveryKey(keyBytes, salt)

        return createBackupWithKey(saveData, combinedKey, salt, slot, gameVersion)
    }

    fun restoreFromRecoveryKeyBackup(
        backupData: ByteArray,
        recoveryKey: String
    ): RestoreResult {
        val keyBytes = recoveryKey.replace("-", "").let {
            android.util.Base64.decode(it, android.util.Base64.NO_WRAP or android.util.Base64.URL_SAFE)
        }

        return try {
            val dis = DataInputStream(ByteArrayInputStream(backupData))

            val headerSize = dis.readInt()
            val headerBytes = ByteArray(headerSize)
            dis.readFully(headerBytes)
            val header = deserializeHeader(headerBytes)

            if (header.magic != BACKUP_MAGIC) {
                return RestoreResult.Error("Invalid backup format")
            }

            val salt = ByteArray(SALT_LENGTH)
            dis.readFully(salt)

            val combinedKey = deriveKeyFromRecoveryKey(keyBytes, salt)

            val result = restoreWithKey(dis, combinedKey, header)

            secureWipe(combinedKey)
            result

        } catch (e: Exception) {
            Log.e(TAG, "Failed to restore from recovery key backup", e)
            RestoreResult.Error(e.message ?: "Unknown error")
        }
    }

    private fun deriveKeyFromRecoveryKey(recoveryKey: ByteArray, salt: ByteArray): ByteArray {
        val combined = recoveryKey + salt
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(combined)
    }

    private fun createBackupWithKey(
        saveData: SaveData,
        key: ByteArray,
        salt: ByteArray,
        slot: Int,
        gameVersion: String
    ): ByteArray {
        val iv = ByteArray(GCM_IV_LENGTH).also { secureRandom.nextBytes(it) }

        val serializedData = serializeSaveData(saveData)
        val compressedData = compressData(serializedData)

        val cipher = Cipher.getInstance(TRANSFORMATION)
        val secretKey = SecretKeySpec(key, KEY_ALGORITHM)
        val gcmSpec = GCMParameterSpec(GCM_TAG_LENGTH, iv)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey, gcmSpec)

        val encryptedData = cipher.doFinal(compressedData)

        val header = createBackupHeader(saveData, slot, gameVersion, encryptedData.size.toLong())
        val headerBytes = serializeHeader(header)

        val result = ByteArrayOutputStream()
        DataOutputStream(result).use { dos ->
            dos.writeInt(headerBytes.size)
            dos.write(headerBytes)
            dos.write(salt)
            dos.write(iv)
            dos.writeInt(encryptedData.size)
            dos.write(encryptedData)

            val hmac = calculateHMAC(result.toByteArray(), key)
            dos.write(hmac)
        }

        return result.toByteArray()
    }

    private fun restoreWithKey(
        dis: DataInputStream,
        key: ByteArray,
        header: BackupHeader
    ): RestoreResult {
        return try {
            val iv = ByteArray(GCM_IV_LENGTH)
            dis.readFully(iv)

            val encryptedSize = dis.readInt()
            val encryptedData = ByteArray(encryptedSize)
            dis.readFully(encryptedData)

            val storedHmac = ByteArray(HMAC_LENGTH)
            dis.readFully(storedHmac)

            val cipher = Cipher.getInstance(TRANSFORMATION)
            val secretKey = SecretKeySpec(key, KEY_ALGORITHM)
            val gcmSpec = GCMParameterSpec(GCM_TAG_LENGTH, iv)
            cipher.init(Cipher.DECRYPT_MODE, secretKey, gcmSpec)

            val compressedData = cipher.doFinal(encryptedData)
            val serializedData = decompressData(compressedData)
            val saveData = deserializeSaveData(serializedData)

            Log.i(TAG, "Successfully restored backup for slot ${header.slot}")
            RestoreResult.Success(saveData, header)

        } catch (e: Exception) {
            Log.e(TAG, "Failed to restore with key", e)
            RestoreResult.Error(e.message ?: "Unknown error")
        }
    }

    private fun createBackupHeader(
        saveData: SaveData,
        slot: Int,
        gameVersion: String,
        dataSize: Long
    ): BackupHeader {
        return BackupHeader(
            magic = BACKUP_MAGIC,
            version = BACKUP_VERSION,
            timestamp = System.currentTimeMillis(),
            slot = slot,
            dataSize = dataSize,
            checksum = calculateDataChecksum(saveData),
            gameVersion = gameVersion,
            sectName = saveData.gameData.sectName,
            gameYear = saveData.gameData.gameYear,
            gameMonth = saveData.gameData.gameMonth
        )
    }

    private fun serializeHeader(header: BackupHeader): ByteArray {
        val baos = ByteArrayOutputStream()
        DataOutputStream(baos).use { dos ->
            dos.writeUTF(header.magic)
            dos.writeInt(header.version)
            dos.writeLong(header.timestamp)
            dos.writeInt(header.slot)
            dos.writeLong(header.dataSize)
            dos.writeUTF(header.checksum)
            dos.writeUTF(header.gameVersion)
            dos.writeUTF(header.sectName)
            dos.writeInt(header.gameYear)
            dos.writeInt(header.gameMonth)
        }
        return baos.toByteArray()
    }

    private fun deserializeHeader(data: ByteArray): BackupHeader {
        DataInputStream(ByteArrayInputStream(data)).use { dis ->
            return BackupHeader(
                magic = dis.readUTF(),
                version = dis.readInt(),
                timestamp = dis.readLong(),
                slot = dis.readInt(),
                dataSize = dis.readLong(),
                checksum = dis.readUTF(),
                gameVersion = dis.readUTF(),
                sectName = dis.readUTF(),
                gameYear = dis.readInt(),
                gameMonth = dis.readInt()
            )
        }
    }

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        isLenient = true
    }
    
    private fun serializeSaveData(saveData: SaveData): ByteArray {
        val serializableData = SaveDataConverter().toSerializable(saveData)
        return json.encodeToString(SerializableSaveData.serializer(), serializableData).toByteArray(Charsets.UTF_8)
    }

    private fun deserializeSaveData(data: ByteArray): SaveData {
        val serializableData = json.decodeFromString(SerializableSaveData.serializer(), String(data, Charsets.UTF_8))
        return SaveDataConverter().fromSerializable(serializableData)
    }

    private fun compressData(data: ByteArray): ByteArray {
        val baos = ByteArrayOutputStream()
        GZIPOutputStream(baos).use { it.write(data) }
        return baos.toByteArray()
    }

    private fun decompressData(data: ByteArray): ByteArray {
        GZIPInputStream(ByteArrayInputStream(data)).use { return it.readBytes() }
    }

    private fun calculateHMAC(data: ByteArray, key: ByteArray): ByteArray {
        val mac = Mac.getInstance(HMAC_ALGORITHM)
        mac.init(SecretKeySpec(key, HMAC_ALGORITHM))
        return mac.doFinal(data)
    }

    private fun calculateDataChecksum(saveData: SaveData): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(saveData.timestamp.toString().toByteArray())
        digest.update(saveData.gameData.sectName.toByteArray())
        digest.update(saveData.disciples.size.toString().toByteArray())
        return digest.digest().take(8).joinToString("") { "%02x".format(it) }
    }

    private fun sha256(data: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(data).joinToString("") { "%02x".format(it) }
    }

    private fun secureWipe(data: ByteArray?) {
        if (data != null) {
            for (i in data.indices) {
                data[i] = 0
            }
            secureRandom.nextBytes(data)
            for (i in data.indices) {
                data[i] = 0
            }
        }
    }
}

sealed class RestoreResult {
    data class Success(
        val saveData: SaveData,
        val header: CrossDeviceCrypto.BackupHeader
    ) : RestoreResult()

    data class Error(val message: String) : RestoreResult()

    val isSuccess: Boolean get() = this is Success
    val isFailure: Boolean get() = this is Error
}

class CrossDeviceBackupManager(
    private val context: Context
) {
    companion object {
        private const val TAG = "CrossDeviceBackupManager"
        private const val BACKUP_DIR = "cross_device_backups"
        private const val MAX_BACKUPS_PER_SLOT = 5
    }

    private val backupDir: File by lazy {
        File(context.filesDir, BACKUP_DIR).apply {
            if (!exists()) mkdirs()
        }
    }

    suspend fun createPasswordBackup(
        saveData: SaveData,
        password: String,
        slot: Int,
        gameVersion: String
    ): BackupResult {
        return withContext(Dispatchers.IO) {
            try {
                val backupData = CrossDeviceCrypto.createPasswordBasedBackup(
                    saveData, password, slot, gameVersion
                )

                val backupFile = File(
                    backupDir,
                    "slot_${slot}_${System.currentTimeMillis()}.backup"
                )
                backupFile.writeBytes(backupData)

                cleanupOldBackups(slot)

                Log.i(TAG, "Created password backup for slot $slot: ${backupFile.name}")
                BackupResult.Success(backupFile.absolutePath, backupData.size.toLong())

            } catch (e: Exception) {
                Log.e(TAG, "Failed to create password backup", e)
                BackupResult.Error(e.message ?: "Unknown error")
            }
        }
    }

    suspend fun restoreFromPasswordBackup(
        backupFile: File,
        password: String
    ): RestoreResult {
        return withContext(Dispatchers.IO) {
            try {
                val backupData = backupFile.readBytes()
                CrossDeviceCrypto.restoreFromPasswordBasedBackup(backupData, password)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to restore from password backup", e)
                RestoreResult.Error(e.message ?: "Unknown error")
            }
        }
    }

    suspend fun createRecoveryKeyBackup(
        saveData: SaveData,
        slot: Int,
        gameVersion: String
    ): Pair<String, BackupResult> {
        return withContext(Dispatchers.IO) {
            try {
                val recoveryKey = CrossDeviceCrypto.generateRecoveryKey()

                val backupData = CrossDeviceCrypto.createRecoveryKeyBackup(
                    saveData, recoveryKey, slot, gameVersion
                )

                val backupFile = File(
                    backupDir,
                    "slot_${slot}_recovery_${System.currentTimeMillis()}.backup"
                )
                backupFile.writeBytes(backupData)

                cleanupOldBackups(slot)

                Log.i(TAG, "Created recovery key backup for slot $slot")
                Pair(recoveryKey, BackupResult.Success(backupFile.absolutePath, backupData.size.toLong()))

            } catch (e: Exception) {
                Log.e(TAG, "Failed to create recovery key backup", e)
                Pair("", BackupResult.Error(e.message ?: "Unknown error"))
            }
        }
    }

    suspend fun restoreFromRecoveryKeyBackup(
        backupFile: File,
        recoveryKey: String
    ): RestoreResult {
        return withContext(Dispatchers.IO) {
            try {
                val backupData = backupFile.readBytes()
                CrossDeviceCrypto.restoreFromRecoveryKeyBackup(backupData, recoveryKey)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to restore from recovery key backup", e)
                RestoreResult.Error(e.message ?: "Unknown error")
            }
        }
    }

    fun createExportCode(saveData: SaveData, password: String, slot: Int, gameVersion: String): String {
        val backupData = CrossDeviceCrypto.createPasswordBasedBackup(
            saveData, password, slot, gameVersion
        )
        return CrossDeviceCrypto.createExportCode(backupData)
    }

    fun restoreFromExportCode(exportCode: String, password: String): RestoreResult {
        val backupData = CrossDeviceCrypto.parseExportCode(exportCode)
            ?: return RestoreResult.Error("Invalid export code format")

        return CrossDeviceCrypto.restoreFromPasswordBasedBackup(backupData, password)
    }

    fun listBackups(slot: Int): List<BackupInfo> {
        return backupDir.listFiles()
            ?.filter { it.name.startsWith("slot_${slot}_") && it.extension == "backup" }
            ?.map { file ->
                BackupInfo(
                    file = file,
                    slot = slot,
                    timestamp = file.lastModified(),
                    size = file.length()
                )
            }
            ?.sortedByDescending { it.timestamp }
            ?: emptyList()
    }

    fun deleteBackup(backupFile: File): Boolean {
        return try {
            backupFile.delete()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to delete backup", e)
            false
        }
    }

    private fun cleanupOldBackups(slot: Int) {
        val backups = listBackups(slot)
        if (backups.size > MAX_BACKUPS_PER_SLOT) {
            backups.drop(MAX_BACKUPS_PER_SLOT).forEach { backup ->
                try {
                    backup.file.delete()
                    Log.d(TAG, "Deleted old backup: ${backup.file.name}")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to delete old backup", e)
                }
            }
        }
    }

    fun getBackupStats(): BackupStats {
        val allBackups = backupDir.listFiles()
            ?.filter { it.extension == "backup" }
            ?: emptyList()

        return BackupStats(
            totalBackups = allBackups.size,
            totalSize = allBackups.sumOf { it.length() },
            oldestBackup = allBackups.minOfOrNull { it.lastModified() } ?: 0,
            newestBackup = allBackups.maxOfOrNull { it.lastModified() } ?: 0
        )
    }
}

data class BackupInfo(
    val file: File,
    val slot: Int,
    val timestamp: Long,
    val size: Long
)

sealed class BackupResult {
    data class Success(val path: String, val size: Long) : BackupResult()
    data class Error(val message: String) : BackupResult()

    val isSuccess: Boolean get() = this is Success
    val isFailure: Boolean get() = this is Error
}

data class BackupStats(
    val totalBackups: Int = 0,
    val totalSize: Long = 0,
    val oldestBackup: Long = 0,
    val newestBackup: Long = 0
)
