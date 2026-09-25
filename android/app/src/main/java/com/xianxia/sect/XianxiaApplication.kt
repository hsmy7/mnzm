package com.xianxia.sect

import android.app.Application
import android.content.ComponentCallbacks2
import android.util.Log
import com.xianxia.sect.core.util.AlarmWatchdogReceiver
import com.xianxia.sect.core.util.DomainLog
import com.xianxia.sect.core.util.GameForegroundService
import com.xianxia.sect.core.util.PortraitPool
import com.xianxia.sect.core.util.GameMonitorManager
import com.xianxia.sect.core.model.DiscipleStatsProvider
import com.xianxia.sect.core.model.DiscipleAggregate
import com.xianxia.sect.core.model.EquipmentInstance
import com.xianxia.sect.core.model.ManualInstance
import com.xianxia.sect.core.model.ManualProficiencyData
import android.os.Build
import com.xianxia.sect.core.engine.OemPowerProfileProvider
import com.xianxia.sect.core.engine.domain.disciple.DiscipleStatCalculator
import com.xianxia.sect.core.engine.domain.building.BuildingFeatureRegistry
import com.xianxia.sect.core.engine.releaseMemory
import com.xianxia.sect.ui.game.building.registerDefaults
// import com.huawei.agconnect.crash.AGConnectCrash  // 待 AGC Crash SDK 依赖就绪后启用
import com.xianxia.sect.data.ChangelogData
import com.xianxia.sect.ui.util.FontPreloader
import com.xianxia.sect.core.util.DeviceCompatibilityHelper
import com.xianxia.sect.core.util.ManufacturerAdapter
import com.xianxia.sect.core.CrashRecoveryEngine
import com.xianxia.sect.core.TapTapCrashGuard
import com.xianxia.sect.core.VulkanPolicy
import com.xianxia.sect.core.memory.TrimMemoryBridge
import com.xianxia.sect.data.facade.StorageFacade
import com.xianxia.sect.umeng.UmengManager

import com.tencent.mmkv.MMKV
import com.getkeepsafe.relinker.ReLinker
import com.xianxia.sect.core.platform.CrashReporter
import dagger.hilt.android.HiltAndroidApp
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import javax.inject.Inject
import com.xianxia.sect.core.engine.domain.disciple.calculateCultivationPerPhase
import com.xianxia.sect.core.engine.domain.disciple.getBaseStats
import com.xianxia.sect.core.engine.domain.disciple.getBreakthroughChance
import com.xianxia.sect.core.engine.domain.disciple.getFinalStats
import com.xianxia.sect.core.engine.domain.disciple.getStatsWithEquipment

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

    @Inject
    lateinit var crashReporter: CrashReporter

    @Inject
    lateinit var crashHandler: com.xianxia.sect.core.CrashHandler

    @Inject
    lateinit var gameEngine: com.xianxia.sect.core.engine.GameEngine

    @Inject
    lateinit var gameDataCacheManager: com.xianxia.sect.data.cache.GameDataCacheManager

    /** AppStartup-Init 后台初始化执行器（Bugly/MMKV 一次性任务），onTerminate 时幂等 shutdown */
    private var appStartupExecutor: ExecutorService? = null

    /**
     * 装配 TrimMemoryBridge 的引擎/缓存动作面（MR1-P1.3 收敛装配点——UI 资源面
     * 由 GameActivity 在其生命周期内注册）。原 `MemoryPressureListener` 广播机制
     * 随 trim 收敛删除（唯一注册者 GameMonitorManager 为空壳实现）。
     */
    private fun assembleTrimBridgeActions() {
        TrimMemoryBridge.engineTrimAction = TrimMemoryBridge.EngineTrimAction { level ->
            // GameEngine.releaseMemory 保留 Android 级别入参（引擎既有归一层）：
            // SOFT → 轻裁剪（战斗日志），AGGRESSIVE/CRITICAL → + 重列表裁剪
            val androidLevel = when (level) {
                com.xianxia.sect.core.domain.memory.MemoryTrimLevel.CRITICAL,
                com.xianxia.sect.core.domain.memory.MemoryTrimLevel.AGGRESSIVE ->
                    ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL
                else -> ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW
            }
            gameEngine.releaseMemory(androidLevel)
        }
        TrimMemoryBridge.cacheTrimAction = TrimMemoryBridge.CacheTrimAction { level ->
            gameDataCacheManager.onMemoryTrimBridge(level)
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this

        // 看门狗闹钟链启动自愈：进程被 OEM 杀死后 onDestroy 未跑、
        // 闹钟未清——此处若本进程无游戏会话（服务未运行且引擎循环未跑），说明
        // 本次 onCreate 正是「空转进程被闹钟拉起」的存量状态，直接取消闹钟退链，
        // 根除「15s 拉起完整 Application → emergencyRestart 被 STOPPED 拒绝 →
        // 再续链」的永续循环。正常游戏会话由 GameActivity.onResume /
        // GameForegroundService.onCreate 重新调度（既有），不受影响。
        //（AlarmManager 无查询 API——无条件 cancel 对不存在的闹钟是安全 no-op）
        if (!GameForegroundService.isRunning) {
            AlarmWatchdogReceiver.cancelAlarm(this)
        }

        // 友盟统计 preInit：用户同意隐私政策前的官方预备调用（不采集个人信息）。
        // 正式 init 在 MainActivity 同意隐私后执行（proceedAfterPrivacyConsent），
        // UMENG_APP_KEY 未配置时 UmengManager 内部整体跳过
        UmengManager.preInit(this)

        // OEM 厂商识别数据注入引擎层（OemPowerProfileProvider 看门狗/忙等三档策略；
        // 必须在首次访问 OemPowerProfileProvider.current 之前——lazy 求值锁定结果）
        OemPowerProfileProvider.injectPlatformManufacturer(Build.MANUFACTURER, Build.BRAND)

        injectDomainDependencies()
        // TrimMemoryBridge 动作面装配（MR1-P1.3 收敛装配点；注入字段此时已可用）
        assembleTrimBridgeActions()
        initCrashProtection()
        initBuglyAndMmkv()

        // changelog_entries.json（129KB JSON 全量解析）在 AppStartup-Init 后台执行器
        // 解析。唯一消费者是设置页更新日志（SettingsTab，用户触达时后台解析早已完成）；
        // 未初始化完成时 ChangelogData.entries 返回空列表，无启动期同步读取依赖。
        appStartupExecutor?.execute { ChangelogData.initialize(this) }

        // 精灵图注册（数据在 SpriteRegistryData.kt）
        registerAllSprites()

        initPortraitsAndFonts()
        initGameMonitoring()
        installTapTapCrashGuard()

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

        // 主线程 Looper 监控：检测消息处理超时（ANR 诊断）。
        // ★ 仅 DEBUG 构建注册——setMessageLogging 对主线程**每条消息**
        //   执行一次 startsWith（常驻微开销）；Release 的 ANR 诊断由 Bugly 主线程
        //   卡顿监控覆盖（行为零损失），此处不再每消息付费。
        if (com.xianxia.sect.BuildConfig.DEBUG) {
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
    }

    /**
     * 安装 TapTap lateinit 异常守卫。
     *
     * 合规：TapTap SDK 必须在用户同意隐私政策后才能初始化。但在同意前，
     * TapTap 内部可能触发 Toast 等操作访问 lateinit context 导致崩溃。
     * 此守卫拦截 TapTap SDK 内部的 lateinit 异常（含混淆后变体）。
     *
     * 注意：Bugly 初始化会在内部覆盖默认崩溃处理器，因此必须在 Bugly
     * 初始化完成后重新安装（见 [initBuglyAndMmkv]），否则守卫失效。
     */
    private fun installTapTapCrashGuard() {
        TapTapCrashGuard.install()
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
        com.xianxia.sect.data.crypto.DeviceBindingIdentity.accountBindingProvider =
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
                manualProficiencies: Map<String, ManualProficiencyData>
            ) = DiscipleStatCalculator.getFinalStats(
                disciple, equipments, manuals, manualProficiencies
            )
            override fun getFinalStats(
                aggregate: DiscipleAggregate,
                equipments: Map<String, EquipmentInstance>,
                manuals: Map<String, ManualInstance>,
                manualProficiencies: Map<String, ManualProficiencyData>
            ) = DiscipleStatCalculator.getFinalStats(
                aggregate, equipments, manuals, manualProficiencies
            )
            override fun calculateCultivationSpeed(
                disciple: com.xianxia.sect.core.model.Disciple,
                manuals: Map<String, ManualInstance>,
                manualProficiencies: Map<String, ManualProficiencyData>,
                buildingBonus: Double,
                additionalBonus: Double,
                preachingElderBonus: Double,
                preachingMastersBonus: Double,
                cultivationSubsidyBonus: Double
            ) = DiscipleStatCalculator.calculateCultivationPerPhase(
                disciple, manuals, manualProficiencies, buildingBonus,
                preachingElderBonus, preachingMastersBonus, cultivationSubsidyBonus
            )
            override fun calculateCultivationSpeed(
                aggregate: DiscipleAggregate,
                manuals: Map<String, ManualInstance>,
                manualProficiencies: Map<String, ManualProficiencyData>,
                buildingBonus: Double,
                additionalBonus: Double,
                preachingElderBonus: Double,
                preachingMastersBonus: Double,
                cultivationSubsidyBonus: Double
            ) = DiscipleStatCalculator.calculateCultivationPerPhase(
                aggregate, manuals, manualProficiencies, buildingBonus,
                preachingElderBonus, preachingMastersBonus, cultivationSubsidyBonus
            )
            override fun getBreakthroughChance(
                disciple: com.xianxia.sect.core.model.Disciple,
                innerElderComprehension: Int,
                outerElderComprehension: Int,
                pillBonus: Double,
                adBonus: Double
            ) = DiscipleStatCalculator.getBreakthroughChance(
                disciple, innerElderComprehension, outerElderComprehension,
                pillBonus, adBonus
            )
            override fun getBreakthroughChance(
                aggregate: DiscipleAggregate,
                innerElderComprehension: Int,
                outerElderComprehension: Int,
                pillBonus: Double,
                adBonus: Double
            ) = DiscipleStatCalculator.getBreakthroughChance(
                aggregate, innerElderComprehension, outerElderComprehension,
                pillBonus, adBonus
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
     * 原生库加载（ReLinker 从 APK 解压 .so）与 Bugly 网络初始化
     * 是典型冷启动杀手（数百 ms 主线程阻塞）。两者均支持非主线程初始化；
     * Application.onCreate 后续代码不依赖 MMKV（已确认 SaveCrypto/ChangelogData 无依赖），
     * 首次业务访问发生在 Activity 阶段（后台任务早已完成）。
     * 崩溃保护仍由 initCrashProtection 的自研 handler 先行安装兜底。
     */
    @Suppress("TooGenericExceptionCaught") // 防御兜底: 异常源跨IO/SDK不可枚举, 降级继续+日志留痕, 非静默吞噬
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

            // 腾讯 Bugly 崩溃收集（经 CrashReporter 端口调用，接口实现永不抛出；
            // 自研 CrashHandler 保留作为兜底）
            try {
                crashReporter.initialize()
                crashReporter.setAppVersion(BuildConfig.VERSION_NAME)
                crashReporter.setUserId("unknown")
                crashReporter.putUserData("manufacturer", android.os.Build.MANUFACTURER)
                crashReporter.putUserData("model", android.os.Build.MODEL)
                Log.i(TAG, "Bugly crash report initialized")
            } catch (e: Exception) {
                Log.w(TAG, "Crash reporter initialization failed, self-built CrashHandler will be fallback", e)
            }
            // Bugly 内部会覆盖默认崩溃处理器——必须在其后重新安装 TapTap 守卫，
            //   使守卫位于 Bugly 之外层（守卫被覆盖后 TapTap 崩溃直达 Bugly 上报）。
            installTapTapCrashGuard()

            // R0.5 遥测闭环：崩溃时刻的上传大概率失败（进程即将退出/网络不可达），
            // 启动时重传 crash_logs 积压（成功即删，失败保留待下次），后台线程执行
            try {
                val uploaded = crashHandler.uploadPendingCrashLogs()
                if (uploaded > 0) {
                    Log.i(TAG, "Crash backlog uploaded: $uploaded file(s)")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Crash backlog upload failed (kept for next launch)", e)
            }
        }
    }

    /**
     * 系统 trim 唯一消费入口（MR1-P1.3/D3 收敛）：只转发 TrimMemoryBridge
     * （归一 + 去抖 + 分发），Application 自身不留任何游戏内存动作。
     */
    @Suppress("DEPRECATION")
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        Log.d(TAG, "onTrimMemory($level) -> TrimMemoryBridge")
        TrimMemoryBridge.onSystemTrim(level)
    }

    override fun onLowMemory() {
        super.onLowMemory()
        Log.e(TAG, "onLowMemory -> TrimMemoryBridge(CRITICAL)")
        TrimMemoryBridge.onSystemLowMemory()
    }
    
    @Suppress("TooGenericExceptionCaught") // 防御兜底: 异常源跨IO/SDK不可枚举, 降级继续+日志留痕, 非静默吞噬
    override fun onTerminate() {
        super.onTerminate()
        try {
            storageFacade.shutdown()
        } catch (e: Exception) {
            Log.e(TAG, "Error shutting down storage subsystems", e)
        }

        // （MR1-P1.3）原 memoryPressureListeners 清理块随广播机制删除

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

