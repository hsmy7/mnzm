package com.xianxia.sect

import android.app.Application
import android.content.ComponentCallbacks2
import android.util.Log
import com.xianxia.sect.core.util.DomainLog
import com.xianxia.sect.core.util.PortraitPool
import com.xianxia.sect.core.util.GameMonitorManager
import com.xianxia.sect.core.model.DiscipleStatsProvider
import com.xianxia.sect.core.model.DiscipleAggregate
import com.xianxia.sect.core.model.EquipmentInstance
import com.xianxia.sect.core.model.ManualInstance
import com.xianxia.sect.core.model.ManualProficiencyData
import com.xianxia.sect.core.engine.domain.disciple.DiscipleStatCalculator
import com.xianxia.sect.core.engine.domain.building.BuildingFeatureRegistry
import com.xianxia.sect.ui.game.building.registerDefaults
// import com.huawei.agconnect.crash.AGConnectCrash  // 待 AGC Crash SDK 依赖就绪后启用
import com.xianxia.sect.data.ChangelogData
import com.xianxia.sect.ui.util.FontPreloader
import com.xianxia.sect.core.util.DeviceCompatibilityHelper
import com.xianxia.sect.core.util.ManufacturerAdapter
import com.xianxia.sect.core.CrashRecoveryEngine
import com.xianxia.sect.core.VulkanPolicy
import com.xianxia.sect.data.crypto.SaveCrypto
import com.xianxia.sect.data.facade.StorageFacade

import com.tencent.mmkv.MMKV
import com.getkeepsafe.relinker.ReLinker
import com.tencent.bugly.crashreport.CrashReport
import dagger.hilt.android.HiltAndroidApp
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import javax.inject.Inject

@HiltAndroidApp
@Suppress("TooManyFunctions") // 生命周期回调 + 跨模块注入/初始化方法均为独立职责
class XianxiaApplication : Application() {

    companion object {
        private const val TAG = "XianxiaApplication"

        @Volatile
        private var instance: XianxiaApplication? = null

        /** 主线程上次 dispatch 时间戳（ANR 诊断用） */
        @Volatile
        private var _lastMainThreadDispatch: Long = 0L

        fun getInstance(): XianxiaApplication? = instance
    }

    @Inject
    lateinit var gameMonitorManager: GameMonitorManager

    @Inject
    lateinit var applicationScopeProvider: com.xianxia.sect.di.ApplicationScopeProvider

    @Inject
    lateinit var storageFacade: StorageFacade

    private val memoryPressureListeners = CopyOnWriteArrayList<MemoryPressureListener>()

    /** AppStartup-Init 后台初始化执行器（Bugly/MMKV 一次性任务），onTerminate 时幂等 shutdown */
    private var appStartupExecutor: ExecutorService? = null

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

        injectDomainDependencies()
        initCrashProtection()
        initBuglyAndMmkv()

        SaveCrypto.initialize(applicationScopeProvider)

        ChangelogData.initialize(this)

        // 精灵图注册（C-7 拆分：数据在 SpriteRegistryData.kt）
        registerAllSprites()

        initPortraitsAndFonts()
        initGameMonitoring()
        installTapTapCrashGuard()

        // 2026-08-01 移除：v4_reset 一键清空玩家本地数据机制（4.0.00 删档重置遗留）。
        // 该机制无确认/备份，一次升级事件即清空全部玩家数据——删档已过，遗留炸弹直接移除。

        // 建筑特征注册表初始化（必须在第一次查询 BuildingFeatureRegistry 之前）
        BuildingFeatureRegistry.registerDefaults()

        Log.i(TAG, "Application initialized with monitoring systems")

    }

    /** 预加载弟子肖像资源 ID 映射与自定义字体（缺失时回退系统默认字体）。 */
    private fun initPortraitsAndFonts() {
        PortraitPool.initialize(this)
        FontPreloader.init(this)
    }

    /** 游戏监控初始化 + 主线程 Looper 超时监控（ANR 诊断）。 */
    private fun initGameMonitoring() {
        gameMonitorManager.initialize(this)
        gameMonitorManager.startMonitoring()

        // 主线程 Looper 监控：检测消息处理超时（ANR 诊断）
        android.os.Looper.getMainLooper().setMessageLogging { msg ->
            if (msg?.startsWith(">>>>> Dispatching") == true) {
                val currentTime = System.currentTimeMillis()
                val lastDispatch = _lastMainThreadDispatch
                if (lastDispatch > 0 && (currentTime - lastDispatch) > 3000) {
                    Log.w(TAG, "Main thread starved for ${currentTime - lastDispatch}ms (potential ANR indicator)")
                }
                _lastMainThreadDispatch = currentTime
            }
        }
    }

    /**
     * 安装 TapTap lateinit 异常守卫。
     *
     * 合规：TapTap SDK 必须在用户同意隐私政策后才能初始化。但在同意前，
     * TapTap 内部可能触发 Toast 等操作访问 lateinit context 导致崩溃。
     * 此处拦截 TapTap SDK 内部的 lateinit 异常（含混淆后变体）。
     */
    private fun installTapTapCrashGuard() {
        val originalHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            val isTapTapLateinit = throwable.stackTrace.any {
                it.className?.contains("taptap", ignoreCase = true) == true
            } && (
                throwable is kotlin.UninitializedPropertyAccessException ||
                throwable.message?.contains("lateinit", ignoreCase = true) == true
            )
            if (isTapTapLateinit) {
                Log.w(TAG, "Suppressed TapTap lateinit crash (SDK not yet consented)", throwable)
                return@setDefaultUncaughtExceptionHandler
            }
            originalHandler?.uncaughtException(thread, throwable)
        }
    }

    /** 注入跨模块实现（日志 / 账号绑定 / 弟子属性计算） */
    private fun injectDomainDependencies() {
        // 注入 Android 日志实现到 domain 模块
        DomainLog.setLogger(object : DomainLog.Logger {
            override fun d(tag: String, msg: String) { Log.d(tag, msg) }
            override fun i(tag: String, msg: String) { Log.i(tag, msg) }
            override fun w(tag: String, msg: String, throwable: Throwable?) {
                if (throwable != null) Log.w(tag, msg, throwable) else Log.w(tag, msg)
            }
            override fun e(tag: String, msg: String, throwable: Throwable?) {
                if (throwable != null) Log.e(tag, msg, throwable) else Log.e(tag, msg)
            }
        })

        // 注入 AccountBindingProvider 实现到 data 模块
        com.xianxia.sect.data.crypto.SecureKeyManager.accountBindingProvider =
            object : com.xianxia.sect.core.util.AccountBindingProvider {
                override fun isLoggedIn(): Boolean =
                    com.xianxia.sect.taptap.TapTapAuthManager.isLoggedIn()
                override fun getAccountUserId(): String? {
                    val account = com.xianxia.sect.taptap.TapTapAuthManager.getCurrentAccount()
                    return account?.openId ?: account?.unionId
                }
            }

        // 注入 DiscipleStatCalculator 实现到 domain 模块
        DiscipleAggregate.statsProvider = object : DiscipleStatsProvider {
            override fun getBaseStats(disciple: com.xianxia.sect.core.model.Disciple) =
                DiscipleStatCalculator.getBaseStats(disciple)
            override fun getBaseStats(aggregate: DiscipleAggregate) =
                DiscipleStatCalculator.getBaseStats(aggregate)
            override fun getTalentEffects(disciple: com.xianxia.sect.core.model.Disciple) =
                DiscipleStatCalculator.getTalentEffects(disciple)
            override fun getTalentEffects(aggregate: DiscipleAggregate) =
                DiscipleStatCalculator.getTalentEffects(aggregate)
            override fun getStatsWithEquipment(
                disciple: com.xianxia.sect.core.model.Disciple,
                equipments: Map<String, EquipmentInstance>
            ) = DiscipleStatCalculator.getStatsWithEquipment(disciple, equipments)
            override fun getStatsWithEquipment(
                aggregate: DiscipleAggregate,
                equipments: Map<String, EquipmentInstance>
            ) = DiscipleStatCalculator.getStatsWithEquipment(aggregate, equipments)
            override fun getFinalStats(
                disciple: com.xianxia.sect.core.model.Disciple,
                equipments: Map<String, EquipmentInstance>,
                manuals: Map<String, ManualInstance>,
                manualProficiencies: Map<String, ManualProficiencyData>,
                bloodRefinementPct: com.xianxia.sect.core.model.BloodRefinementPctTotal?
            ) = DiscipleStatCalculator.getFinalStats(
                disciple, equipments, manuals, manualProficiencies, bloodRefinementPct
            )
            override fun getFinalStats(
                aggregate: DiscipleAggregate,
                equipments: Map<String, EquipmentInstance>,
                manuals: Map<String, ManualInstance>,
                manualProficiencies: Map<String, ManualProficiencyData>,
                bloodRefinementPct: com.xianxia.sect.core.model.BloodRefinementPctTotal?
            ) = DiscipleStatCalculator.getFinalStats(
                aggregate, equipments, manuals, manualProficiencies, bloodRefinementPct
            )
            override fun calculateCultivationSpeed(
                disciple: com.xianxia.sect.core.model.Disciple,
                manuals: Map<String, ManualInstance>,
                manualProficiencies: Map<String, ManualProficiencyData>,
                buildingBonus: Double,
                additionalBonus: Double,
                preachingElderBonus: Double,
                preachingMastersBonus: Double,
                cultivationSubsidyBonus: Double,
                parentCultivationBonus: Double,
                griefCultivationSpeedPenalty: Double,
                masterDiscipleBonus: Double
            ) = DiscipleStatCalculator.calculateCultivationPerPhase(
                disciple, manuals, manualProficiencies, buildingBonus,
                preachingElderBonus, preachingMastersBonus, cultivationSubsidyBonus,
                parentCultivationBonus, griefCultivationSpeedPenalty, masterDiscipleBonus
            )
            override fun calculateCultivationSpeed(
                aggregate: DiscipleAggregate,
                manuals: Map<String, ManualInstance>,
                manualProficiencies: Map<String, ManualProficiencyData>,
                buildingBonus: Double,
                additionalBonus: Double,
                preachingElderBonus: Double,
                preachingMastersBonus: Double,
                cultivationSubsidyBonus: Double,
                parentCultivationBonus: Double,
                griefCultivationSpeedPenalty: Double,
                masterDiscipleBonus: Double
            ) = DiscipleStatCalculator.calculateCultivationPerPhase(
                aggregate, manuals, manualProficiencies, buildingBonus,
                preachingElderBonus, preachingMastersBonus, cultivationSubsidyBonus,
                parentCultivationBonus, griefCultivationSpeedPenalty, masterDiscipleBonus
            )
            override fun getBreakthroughChance(
                disciple: com.xianxia.sect.core.model.Disciple,
                innerElderComprehension: Int,
                outerElderComprehension: Int,
                pillBonus: Double,
                adBonus: Double,
                griefBreakthroughPenalty: Double,
                masterDiscipleBonus: Double
            ) = DiscipleStatCalculator.getBreakthroughChance(
                disciple, innerElderComprehension, outerElderComprehension,
                pillBonus, adBonus, griefBreakthroughPenalty, masterDiscipleBonus
            )
            override fun getBreakthroughChance(
                aggregate: DiscipleAggregate,
                innerElderComprehension: Int,
                outerElderComprehension: Int,
                pillBonus: Double,
                adBonus: Double,
                griefBreakthroughPenalty: Double,
                masterDiscipleBonus: Double
            ) = DiscipleStatCalculator.getBreakthroughChance(
                aggregate, innerElderComprehension, outerElderComprehension,
                pillBonus, adBonus, griefBreakthroughPenalty, masterDiscipleBonus
            )
        }
    }

    /** 崩溃自愈引擎 + 渲染策略初始化（必须在任何 Activity 启动前完成） */
    private fun initCrashProtection() {
        DeviceCompatibilityHelper.logDeviceInfo()

        // 全厂商适配：根据当前设备厂商执行差异化适配策略
        ManufacturerAdapter.apply(this)

        // ── 崩溃自愈引擎初始化 ──
        // 必须在任何 Activity 启动前完成，GameActivity.onCreate 中会读取安全模式状态
        CrashRecoveryEngine.initialize(this)
        // 渲染策略初始化——缓存硬件加速决策，供 GameActivity 在 super.onCreate() 前读取
        VulkanPolicy.initialize(this)
        // 渲染策略诊断（记录到日志供 Bugly 分析）
        VulkanPolicy.logDeviceDiagnostics(this)
        // 渲染相关崩溃计数器 + 设备分级 → 决定是否进入安全模式
        if (CrashRecoveryEngine.isSafeMode()) {
            android.util.Log.w(
                TAG, "Render safe mode is ACTIVE — HW acceleration disabled"
            )
        } else if (VulkanPolicy.detectTier(this) == VulkanPolicy.DeviceTier.PROBLEMATIC) {
            android.util.Log.w(
                TAG, "Problematic device detected — crash recovery will activate on consecutive crashes"
            )
        }
    }

    /**
     * Bugly 崩溃收集 + MMKV 显式初始化（含 ReLinker 兜底）。
     *
     * 2026-08-01 后台化：原生库加载（ReLinker 从 APK 解压 .so）与 Bugly 网络初始化
     * 是典型冷启动杀手（数百 ms 主线程阻塞）。两者均支持非主线程初始化；
     * Application.onCreate 后续代码不依赖 MMKV（已确认 SaveCrypto/ChangelogData 无依赖），
     * 首次业务访问发生在 Activity 阶段（后台任务早已完成）。
     * 崩溃保护仍由 initCrashProtection 的自研 handler 先行安装兜底。
     */
    private fun initBuglyAndMmkv() {
        // 幂等守卫：已初始化过（executor 非空）则跳过，防止二次调用覆盖执行器引用
        if (appStartupExecutor != null) return
        val executor = Executors.newSingleThreadExecutor { r ->
            Thread(r, "AppStartup-Init").apply { priority = Thread.NORM_PRIORITY }
        }
        appStartupExecutor = executor
        executor.execute {
            // MMKV 排首位：ReLinker 解压耗时最长，尽早开始
            try {
                MMKV.initialize(this, object : MMKV.LibLoader {
                    override fun loadLibrary(libName: String?) {
                        ReLinker.loadLibrary(
                            this@XianxiaApplication,
                            requireNotNull(libName) { "libName must not be null" }
                        )
                    }
                })
                Log.i(TAG, "MMKV initialized with ReLinker fallback")
            } catch (e: Throwable) {
                Log.e(TAG, "MMKV initialization failed, falling back to default loader", e)
                try {
                    MMKV.initialize(this)
                } catch (e2: Throwable) {
                    Log.e(TAG, "MMKV default initialization also failed", e2)
                }
            }

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

        try {
            appStartupExecutor?.shutdown()
            appStartupExecutor = null
        } catch (e: Exception) {
            Log.e(TAG, "Error shutting down app startup executor", e)
        }

        gameMonitorManager.cleanup()

        instance = null

        Log.i(TAG, "Application terminated, all resources cleaned up successfully")
    }
}

