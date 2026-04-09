package com.xianxia.sect.data.unified

import android.content.Context
import android.util.Log
import com.xianxia.sect.data.crypto.IntegrityValidator
import com.xianxia.sect.data.crypto.SecureKeyManager
import com.xianxia.sect.data.model.SaveData
import com.xianxia.sect.data.serialization.NullSafeProtoBuf
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MetadataManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val fileHandler: SaveFileHandler
) {
    
    companion object {
        private const val TAG = "MetadataManager"
    }
    
    private val protoBuf = NullSafeProtoBuf.protoBuf
    
    val slotMetadataCache = ConcurrentHashMap<Int, SlotMetadata>()
    
    fun updateSlotMetadata(slot: Int, data: SaveData, fileSize: Long) {
        val key = SecureKeyManager.getOrCreateKey(context)
        val signedPayload = IntegrityValidator.createSignedPayload(
            data = data,
            key = key,
            metadata = mapOf(
                "slot" to slot.toString(),
                "version" to (data.version ?: "1.0")
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
            version = data.version ?: "1.0",
            signedPayload = signedPayload,
            dataHash = signedPayload.dataHash,
            merkleRoot = signedPayload.merkleRoot
        )
        
        slotMetadataCache[slot] = metadata
        saveSlotMetadata(slot, metadata)
    }
    
    fun saveSlotMetadata(slot: Int, metadata: SlotMetadata) {
        try {
            val metaFile = fileHandler.getMetaFile(slot)
            val bytes = protoBuf.encodeToByteArray(SlotMetadata.serializer(), metadata)
            @Suppress("NewApi")
            metaFile.writeText(Base64.getEncoder().encodeToString(bytes))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save metadata for slot $slot", e)
        }
    }

    fun loadSlotMetadata(slot: Int): SlotMetadata? {
        return try {
            val metaFile = fileHandler.getMetaFile(slot)
            if (!metaFile.exists()) return null

            val base64 = metaFile.readText()
            @Suppress("NewApi")
            val bytes = Base64.getDecoder().decode(base64)
            protoBuf.decodeFromByteArray(SlotMetadata.serializer(), bytes)
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
