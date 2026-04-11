package com.xianxia.sect.data.unified

import android.content.Context
import android.util.Log
import com.xianxia.sect.data.crypto.IntegrityValidator
import com.xianxia.sect.data.crypto.SecureKeyManager
import com.xianxia.sect.data.model.SaveData
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString

@Singleton
class MetadataManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val fileHandler: SaveFileHandler
) {

    companion object {
        private const val TAG = "MetadataManager"
    }

    private val json = Json { ignoreUnknownKeys = true }
    val slotMetadataCache = ConcurrentHashMap<Int, SlotMetadata>()

    fun updateSlotMetadata(slot: Int, data: SaveData, fileSize: Long) {
        val key = SecureKeyManager.getOrCreateKey(context)
        val signedPayload = IntegrityValidator.createSignedPayload(
            data = data,
            key = key,
            metadata = mapOf(
                "slot" to slot.toString(),
                "version" to data.version
            )
        )

        val metadata = SlotMetadata(
            slot = slot,
            timestamp = data.timestamp,
            gameYear = data.gameData.gameYear,
            gameMonth = data.gameData.gameMonth,
            sectName = data.gameData.sectName,
            discipleCount = data.disciples.count { it.isAlive },
            spiritStones = data.gameData.spiritStones,
            fileSize = fileSize,
            checksum = signedPayload.signature,
            version = data.version,
            dataHash = signedPayload.dataHash,
            merkleRoot = signedPayload.merkleRoot
        )

        slotMetadataCache[slot] = metadata
        saveSlotMetadata(slot, metadata)
    }

    fun saveSlotMetadata(slot: Int, metadata: SlotMetadata) {
        try {
            val metaFile = fileHandler.getMetaFile(slot)
            val text = json.encodeToString(metadata)
            metaFile.writeText(text)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save metadata for slot $slot", e)
        }
    }

    fun loadSlotMetadata(slot: Int): SlotMetadata? {
        return try {
            val metaFile = fileHandler.getMetaFile(slot)
            if (!metaFile.exists()) return null

            val text = metaFile.readText()
            json.decodeFromString<SlotMetadata>(text)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load metadata for slot $slot", e)
            null
        }
    }

    fun loadAllMetadata(maxSlots: Int) {
        (1..maxSlots).forEach { slot ->
            loadSlotMetadata(slot)?.let { slotMetadataCache[slot] = it }
        }
    }

    fun getFromCache(slot: Int): SlotMetadata? = slotMetadataCache[slot]

    fun removeFromCache(slot: Int): SlotMetadata? = slotMetadataCache.remove(slot)

    fun clearCache() {
        slotMetadataCache.clear()
    }
}
