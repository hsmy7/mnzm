package com.xianxia.sect.data.cache

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.util.Log
import javax.inject.Inject
import javax.inject.Singleton

data class AdaptiveCacheConfig(
    val memoryCacheSize: Long,
    val diskCacheSize: Long,
    val hotZoneSize: Long,
    val warmZoneSize: Long,
    val coldZoneSize: Long,
    val isLowRamDevice: Boolean,
    val memoryClass: Int
)

@Singleton
class AdaptiveCacheConfigProvider @Inject constructor(
    private val context: Context
) {
    companion object {
        private const val TAG = "AdaptiveCacheConfig"
    }
    
    private val activityManager: ActivityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    
    fun getConfig(): AdaptiveCacheConfig {
        val isLowRamDevice = activityManager.isLowRamDevice
        val memoryClass = activityManager.memoryClass
        
        val memoryCacheSize: Long
        val diskCacheSize: Long
        val hotZoneSize: Long
        val warmZoneSize: Long
        val coldZoneSize: Long
        
        return when {
            isLowRamDevice -> {
                AdaptiveCacheConfig(
                    memoryCacheSize = 16 * 1024 * 1024,
                    diskCacheSize = 50 * 1024 * 1024,
                    hotZoneSize = 4 * 1024 * 1024,
                    warmZoneSize = 8 * 1024 * 1024,
                    coldZoneSize = 4 * 1024 * 1024,
                    isLowRamDevice = true,
                    memoryClass = memoryClass
                )
            }
            
            val baseMemoryCacheSize = 64L * 1024L
            val baseDiskCacheSize = 128L * 1024L
            
            val baseHotZoneSize = (baseMemoryCacheSize * 0.4).toLong()
            val baseWarmZoneSize = (baseMemoryCacheSize * 0.35).toLong()
            val baseColdZoneSize = (baseMemoryCacheSize * 0.25).toLong()
            
            return AdaptiveCacheConfig(
                memoryCacheSize = when {
                    memoryClass <= 128 -> 32 * 1024 * 1024L
                    memoryClass <= 256 -> 64 * 1024 * 1024L
                    memoryClass <= 512 -> 128 * 1024 * 1024L
                    else -> baseMemoryCacheSize
                }.toLong(),
                diskCacheSize = when {
                    isLowRamDevice -> baseDiskCacheSize
                    else -> baseDiskCacheSize
                }.toLong(),
                hotZoneSize = when {
                    isLowRamDevice -> baseHotZoneSize
                    else -> baseHotZoneSize
                }.toLong(),
                warmZoneSize = when {
                    isLowRamDevice -> baseWarmZoneSize
                    else -> baseWarmZoneSize
                }.toLong(),
                coldZoneSize = when {
                    isLowRamDevice -> baseColdZoneSize
                    else -> baseColdZoneSize
                }.toLong(),
                isLowRamDevice = isLowRamDevice,
                memoryClass = memoryClass
            )
        }
    }
    
    fun logConfig(config: AdaptiveCacheConfig) {
        Log.i(TAG, "Adaptive cache config: memory=${config.memoryCacheSize / 1024 / 1024}KB, " +
            "disk=${config.diskCacheSize / 1024 / 1024}KB, " +
            "hotZone=${config.hotZoneSize / 1024 / 1024}KB, " +
            "warmZone=${config.warmZoneSize / 1024 / 1024}KB, " +
            "coldZone=${config.coldZoneSize / 1024 / 1024}KB, " +
            "isLowRamDevice=${config.isLowRamDevice}, " +
            "memoryClass=${config.memoryClass}MB")
    }
}
