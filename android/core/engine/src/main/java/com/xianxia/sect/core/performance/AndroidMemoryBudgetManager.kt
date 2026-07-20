package com.xianxia.sect.core.performance

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.util.Log
import androidx.annotation.VisibleForTesting
import com.xianxia.sect.core.performance.MemoryInfoProvider.MemoryInfo

@VisibleForTesting
class AndroidMemoryBudgetManager(
    private val context: Context
) : MemoryInfoProvider {

    companion object {
        private const val TAG = "AndroidMemoryBudget"
        private const val LOW_MEM_THRESHOLD = 0.15f
        private const val CRITICAL_MEM_THRESHOLD = 0.08f
    }

    override fun getMemoryInfo(): MemoryInfoProvider.MemoryInfo {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        if (am == null) {
            Log.w(TAG, "ActivityManager not available, returning empty MemoryInfo")
            return MemoryInfoProvider.MemoryInfo(availMem = 0L, totalMem = 0L, isLowMemory = false)
        }
        val mi = ActivityManager.MemoryInfo()
        am.getMemoryInfo(mi)
        return MemoryInfoProvider.MemoryInfo(
            availMem = mi.availMem,
            totalMem = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) mi.totalMem else 0L,
            isLowMemory = mi.lowMemory,
            threshold = mi.threshold
        )
    }

    fun isMemoryConstrained(): Boolean = getAvailMemRatio() < LOW_MEM_THRESHOLD

    fun isMemoryCritical(): Boolean =
        getAvailMemRatio() < CRITICAL_MEM_THRESHOLD || getMemoryInfo().isLowMemory

    val summary: String get() {
        val info = getMemoryInfo()
        val ratio = getAvailMemRatio()
        return "AndroidMemoryBudget[avail=${info.availMem / (1024 * 1024)}MB, " +
            "total=${info.totalMem / (1024 * 1024)}MB, " +
            "ratio=${"%.2f".format(ratio)}, " +
            "lowMemory=${info.isLowMemory}]"
    }
}
