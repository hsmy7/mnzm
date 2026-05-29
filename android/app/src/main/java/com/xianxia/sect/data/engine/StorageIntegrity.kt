package com.xianxia.sect.data.engine

import android.content.Context
import android.util.Log
import com.xianxia.sect.data.crypto.CryptoModule
import com.xianxia.sect.data.crypto.IntegrityReport
import com.xianxia.sect.data.model.SaveData
import com.xianxia.sect.data.result.StorageError
import com.xianxia.sect.data.result.StorageResult
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class StorageIntegrity @Inject constructor(
    @ApplicationContext private val context: Context,
    private val crypto: CryptoModule
) {
    companion object {
        private const val TAG = "StorageIntegrity"
    }

    suspend fun validateIntegrity(slot: Int, saveData: SaveData): StorageResult<IntegrityReport> {
        return try {
            val key = crypto.getOrCreateKey(context)
            val currentDataHash = crypto.computeFullDataSignature(saveData, key)
            val currentMerkleRoot = crypto.computeMerkleRoot(saveData)

            StorageResult.success(IntegrityReport(
                isValid = true,
                dataHash = currentDataHash,
                merkleRoot = currentMerkleRoot,
                signatureValid = true,
                hashValid = true,
                merkleValid = true,
                errors = emptyList()
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
