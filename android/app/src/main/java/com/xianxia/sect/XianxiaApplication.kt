package com.xianxia.sect

import android.app.Application
import android.content.ComponentCallbacks2
import android.content.res.Configuration
import android.util.Log
import com.xianxia.sect.core.data.ManualDatabase
import com.xianxia.sect.core.util.GameMonitorManager
import com.xianxia.sect.core.util.VivoGCJITOptimizer
import com.xianxia.sect.data.facade.StorageFacade
import com.xianxia.sect.data.recovery.RecoveryManager
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.concurrent.CopyOnWriteArrayList
import javax.inject.Inject

@HiltAndroidApp
class XianxiaApplication : Application() {

    companion object {
        private const val TAG = "XianxiaApplication"
        
        @Volatile
        private var instance: XianxiaApplication? = null
        
        fun getInstance(): XianxiaApplication? = instance
    }

    @Inject
    lateinit var gameMonitorManager: GameMonitorManager

    @Inject
    lateinit var applicationScopeProvider: com.xianxia.sect.di.ApplicationScopeProvider

    @Inject
    lateinit var storageFacade: StorageFacade

    @Inject
    lateinit var recoveryManager: RecoveryManager

    private val memoryPressureListeners = CopyOnWriteArrayList<MemoryPressureListener>()

    interface MemoryPressureListener {
        fun onMemoryPressure(level: Int)
        fun onLowMemory()
    }

    fun registerMemoryPressureListener(listener: MemoryPressureListener) {
        if (!memoryPressureListeners.contains(listener)) {
            memoryPressureListeners.add(listener)
        }
    }

    fun unregisterMemoryPressureListener(listener: MemoryPressureListener) {
        memoryPressureListeners.remove(listener)
    }

    override fun onCreate() {
        super.onCreate()
        instance = this

        gameMonitorManager.initialize(this)
        gameMonitorManager.startMonitoring()
        
        val result = ManualDatabase.initializeSync(this)
        result.onSuccess { Log.i(TAG, "ManualDatabase 初始化成功") }
            .onFailure { 
                Log.e(TAG, "ManualDatabase 初始化失败，将以空功法库继续运行", it)
            }
        
        Log.i(TAG, "Application initialized with monitoring systems")

        applicationScopeProvider.ioScope.launch(Dispatchers.IO) {
            try {
                val report = recoveryManager.startupRecovery()
                if (report.recoveredSlots.isNotEmpty()) {
                    Log.i("AppStartup", "Crash recovery: recovered slots=${report.recoveredSlots}")
                }
                recoveryManager.scheduleDeferredWarmup(applicationScopeProvider.scope)
            } catch (e: Exception) {
                Log.e(TAG, "Startup recovery failed", e)
            }
        }
    }

    @Suppress("DEPRECATION")
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        when (level) {
            ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN -> {
                Log.d(TAG, "内存优化: UI已隐藏，可释放UI相关资源")
            }
            ComponentCallbacks2.TRIM_MEMORY_MODERATE -> {
                Log.w(TAG, "内存警告: 系统内存适中压力，建议释放部分资源")
                notifyMemoryPressure(level)
            }
            ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL -> {
                Log.e(TAG, "内存严重警告: 系统内存严重不足，需立即释放非关键资源")
                notifyMemoryPressure(level)
            }
            ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW -> {
                Log.w(TAG, "内存警告: 系统内存较低，建议释放可重建资源")
                notifyMemoryPressure(level)
            }
            ComponentCallbacks2.TRIM_MEMORY_COMPLETE -> {
                Log.e(TAG, "内存紧急: 系统即将杀死后台进程，释放所有可释放资源")
                notifyMemoryPressure(level)
            }
            else -> {
                Log.d(TAG, "内存优化: 收到内存裁剪级别 $level")
            }
        }
    }

    override fun onLowMemory() {
        super.onLowMemory()
        Log.e(TAG, "内存严重不足: 系统请求释放资源")
        notifyLowMemory()
    }

    private fun notifyMemoryPressure(level: Int) {
        memoryPressureListeners.forEach { listener ->
            try {
                listener.onMemoryPressure(level)
            } catch (e: Exception) {
                Log.e(TAG, "通知内存压力监听器失败: ${e.message}", e)
            }
        }
    }

    private fun notifyLowMemory() {
        memoryPressureListeners.forEach { listener ->
            try {
                listener.onLowMemory()
            } catch (e: Exception) {
                Log.e(TAG, "通知低内存监听器失败: ${e.message}", e)
            }
        }
    }
    
    override fun onTerminate() {
        super.onTerminate()
        try {
            storageFacade.shutdown()
        } catch (e: Exception) {
            Log.e(TAG, "Error shutting down storage subsystems", e)
        }

        try {
            memoryPressureListeners.clear()
            Log.i(TAG, "Memory pressure listeners cleared")
        } catch (e: Exception) {
            Log.e(TAG, "Error clearing memory pressure listeners", e)
        }

        try {
            applicationScopeProvider.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error closing ApplicationScopeProvider", e)
        }

        gameMonitorManager.cleanup()

        instance = null

        Log.i(TAG, "Application terminated, all resources cleaned up successfully")
    }
}
