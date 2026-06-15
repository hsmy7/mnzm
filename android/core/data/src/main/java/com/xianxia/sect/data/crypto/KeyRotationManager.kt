package com.xianxia.sect.data.crypto

import android.content.Context
import android.util.Log
import com.xianxia.sect.data.facade.StorageFacade
import com.xianxia.sect.data.unified.SaveError
import com.xianxia.sect.data.unified.SaveResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

class KeyRotationManager(
    private val context: Context,
    private val storageFacade: StorageFacade
) {
    companion object {
        private const val TAG = "SecureKeyManager"
        private const val ROTATION_PREFS = "key_rotation_prefs"
        private const val KEY_LAST_ROTATION = "last_rotation"
        private const val ROTATION_INTERVAL_MS = 30L * 24 * 60 * 60 * 1000
    }

    /**
     * 维护模式锁（AtomicBoolean 保证线程安全）
     *
     * 用途：
     * - 在密钥轮换期间标记系统处于维护状态
     * - 外部可通过 isMaintenanceMode() 查询当前状态
     * - 在轮换进行时阻止并发操作或提示用户等待
     *
     * 为什么需要维护模式锁：
     * 密钥轮换涉及读取所有存档、生成新密钥、重新加密并写回的完整流程，
     * 此过程中存档数据处于不一致状态（旧密钥加密的存档尚未用新密钥重加密）。
     * 如果此时允许其他写入操作，可能导致数据混乱或丢失。
     */
    private val isInMaintenanceMode = AtomicBoolean(false)

    /**
     * 查询当前是否处于密钥轮换维护模式
     * @return true 表示正在进行密钥轮换，系统处于维护状态
     */
    fun isMaintenanceMode(): Boolean = isInMaintenanceMode.get()

    suspend fun needsRotation(): Boolean = withContext(Dispatchers.IO) {
        val prefs = context.getSharedPreferences(ROTATION_PREFS, Context.MODE_PRIVATE)
        val lastRotation = prefs.getLong(KEY_LAST_ROTATION, 0)

        System.currentTimeMillis() - lastRotation > ROTATION_INTERVAL_MS
    }

    suspend fun performRotation(): SaveResult<Unit> = withContext(Dispatchers.IO) {
        // 进入维护模式：防止轮换期间的并发操作导致数据不一致
        isInMaintenanceMode.set(true)
        Log.i(TAG, "Entering maintenance mode for key rotation")

        try {
            Log.i(TAG, "Starting key rotation process")

            val slots = (1..6).filter { slot ->
                storageFacade.hasSaveSuspend(slot)
            }

            SecureKeyManager.rotateKey(context)

            for (slot in slots) {
                val result = storageFacade.load(slot)
                if (!result.isSuccess) {
                    Log.w(TAG, "Failed to load slot $slot during rotation, skipping")
                    continue
                }

                try {
                    val data = result.getOrThrow()

                    val saveResult = storageFacade.save(slot, data)
                    if (saveResult.isFailure) {
                        Log.e(TAG, "Failed to re-encrypt slot $slot after key rotation")
                        return@withContext SaveResult.failure(
                            SaveError.ENCRYPTION_ERROR,
                            "Failed to re-encrypt slot $slot"
                        )
                    }

                    Log.d(TAG, "Successfully rotated and saved slot $slot")
                } catch (slotEx: Exception) {
                    Log.e(TAG, "Error processing slot $slot during rotation", slotEx)
                }
            }

            val prefs = context.getSharedPreferences(ROTATION_PREFS, Context.MODE_PRIVATE)
            prefs.edit().putLong(KEY_LAST_ROTATION, System.currentTimeMillis()).apply()

            Log.i(TAG, "Key rotation completed successfully for ${slots.size} slots")

            SaveResult.success(Unit)

        } catch (e: Exception) {
            Log.e(TAG, "Key rotation failed", e)
            SaveResult.failure(
                SaveError.KEY_DERIVATION_ERROR,
                "Key rotation failed: ${e.message}",
                e
            )
        } finally {
            // 无论成功还是失败，都必须退出维护模式
            // 防止因异常导致锁永远无法释放，阻塞后续所有操作
            isInMaintenanceMode.set(false)
            Log.i(TAG, "Exited maintenance mode (key rotation finished or aborted)")
        }
    }
}
