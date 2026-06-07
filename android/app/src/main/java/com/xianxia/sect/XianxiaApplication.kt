package com.xianxia.sect

import android.app.Application
import android.content.ComponentCallbacks2
import android.content.res.Configuration
import android.util.Log
import com.xianxia.sect.core.util.GameMonitorManager
import com.xianxia.sect.core.util.VivoGCJITOptimizer
// import com.huawei.agconnect.crash.AGConnectCrash  // 待 AGC Crash SDK 依赖就绪后启用
import com.xianxia.sect.core.util.DeviceCompatibilityHelper
import com.xianxia.sect.core.util.ManufacturerAdapter
import com.xianxia.sect.data.crypto.SaveCrypto
import com.xianxia.sect.data.facade.StorageFacade
import com.xianxia.sect.data.recovery.RecoveryManager
import com.tencent.mmkv.MMKV
import com.getkeepsafe.relinker.ReLinker
import com.tencent.bugly.crashreport.CrashReport
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

        // TODO: 华为 AGC Crash — 待 agconnect-crash 依赖就绪后启用
        // if (DeviceCompatibilityHelper.isHuaweiOrHonor) {
        //     try {
        //         AGConnectCrash.getInstance().enableCrashCollection(true)
        //     } catch (e: Exception) {
        //         Log.w(TAG, "AGC Crash init failed", e)
        //     }
        // }

        DeviceCompatibilityHelper.logDeviceInfo()

        // 全厂商适配：根据当前设备厂商执行差异化适配策略
        ManufacturerAdapter.apply(this)

        // 腾讯 Bugly 崩溃收集（主崩溃收集 SDK，自研 CrashHandler 保留作为兜底）
        try {
            CrashReport.initCrashReport(this, BuildConfig.BUGLY_APP_ID, BuildConfig.DEBUG)
            CrashReport.setAppVersion(this, BuildConfig.VERSION_NAME)
            CrashReport.setUserId("unknown")
            CrashReport.putUserData(this, "manufacturer", android.os.Build.MANUFACTURER)
            CrashReport.putUserData(this, "model", android.os.Build.MODEL)
            Log.i(TAG, "Bugly crash report initialized")
        } catch (e: Exception) {
            Log.w(TAG, "Bugly initialization failed, self-built CrashHandler will be fallback", e)
        }

        // P0修复：MMKV 显式初始化，使用 ReLinker 兜底原生库加载
        // 华为 HarmonyOS/EMUI 的 linker 不支持从 APK 直接 mmap 加载 .so，
        // ReLinker 会在系统加载失败后手动从 APK 提取 .so 到私有目录再加载
        try {
            MMKV.initialize(this, object : MMKV.LibLoader {
                override fun loadLibrary(libName: String?) {
                    ReLinker.loadLibrary(this@XianxiaApplication, libName!!)
                }
            })
            Log.i(TAG, "MMKV initialized with ReLinker fallback")
        } catch (e: Exception) {
            Log.e(TAG, "MMKV initialization failed, falling back to default loader", e)
            try {
                MMKV.initialize(this)
            } catch (e2: Exception) {
                Log.e(TAG, "MMKV default initialization also failed", e2)
            }
        }

        SaveCrypto.initialize(applicationScopeProvider)

        gameMonitorManager.initialize(this)
        gameMonitorManager.startMonitoring()

        // 合规：TapTap SDK 必须在用户同意隐私政策后才能初始化。
        // 但在同意前，TapTap 内部可能触发 Toast 等操作访问 lateinit context 导致崩溃。
        // 此处安装全局异常守卫，仅拦截 TapTap SDK 内部的 UninitializedPropertyAccessException。
        val originalHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            if (throwable is kotlin.UninitializedPropertyAccessException
                && throwable.stackTrace.any { it.className?.contains("taptap", ignoreCase = true) == true }
            ) {
                Log.w(TAG, "Suppressed TapTap lateinit crash (SDK not yet consented)", throwable)
                return@setDefaultUncaughtExceptionHandler
            }
            originalHandler?.uncaughtException(thread, throwable)
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
