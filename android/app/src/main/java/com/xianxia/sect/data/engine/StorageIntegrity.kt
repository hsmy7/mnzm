package com.xianxia.sect.data.engine

import android.content.Context
import android.util.Log
import com.xianxia.sect.data.crypto.CryptoModule
import com.xianxia.sect.data.crypto.IntegrityReport
import com.xianxia.sect.data.crypto.IntegrityValidator
import com.xianxia.sect.data.model.SaveData
import com.xianxia.sect.data.result.StorageError
import com.xianxia.sect.data.result.StorageResult
import com.xianxia.sect.data.unified.MetadataManager
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class StorageIntegrity @Inject constructor(
    @ApplicationContext private val context: Context,
    private val crypto: CryptoModule,
    private val metadataManager: MetadataManager
) {
    companion object {
        private const val TAG = "StorageIntegrity"
    }

    suspend fun validateIntegrity(slot: Int, saveData: SaveData): StorageResult<IntegrityReport> {
        return try {
            val key = crypto.getOrCreateKey(context)
            val currentDataHash = crypto.computeFullDataSignature(saveData, key)
            val currentMerkleRoot = crypto.computeMerkleRoot(saveData)

            val storedMetadata = metadataManager.loadSlotMetadata(slot)
            val errors = mutableListOf<String>()

            val signatureValid = if (storedMetadata != null && storedMetadata.checksum.isNotEmpty()) {
                val result = IntegrityValidator.verifyFullDataSignature(saveData, storedMetadata.checksum, key)
                if (!result) errors.add("Signature verification failed")
                result
            } else {
                null
            }

            val hashValid = if (storedMetadata != null && storedMetadata.dataHash.isNotEmpty()) {
                val result = constantTimeEquals(currentDataHash, storedMetadata.dataHash)
                if (!result) errors.add("Data hash mismatch")
                result
            } else {
                null
            }

            val merkleValid = if (storedMetadata != null && storedMetadata.merkleRoot.isNotEmpty()) {
                val result = constantTimeEquals(currentMerkleRoot, storedMetadata.merkleRoot)
                if (!result) errors.add("Merkle root mismatch")
                result
            } else {
                null
            }

            val isValid = signatureValid != false && hashValid != false && merkleValid != false

            StorageResult.success(IntegrityReport(
                isValid = isValid,
                dataHash = currentDataHash,
                merkleRoot = currentMerkleRoot,
                signatureValid = signatureValid ?: true,
                hashValid = hashValid ?: true,
                merkleValid = merkleValid ?: true,
                errors = errors
            ))
        } catch (e: Exception) {
            Log.e(TAG, "Integrity validation failed for slot $slot", e)
            StorageResult.failure(StorageError.SLOT_CORRUPTED, e.message ?: "Integrity check failed")
        }
    }

    internal fun constantTimeEquals(a: String, b: String): Boolean {
        if (a.length != b.length) return false
        var result = 0
        for (i in a.indices) {
            result = result or (a[i].code xor b[i].code)
        }
        return result == 0
    }
}
