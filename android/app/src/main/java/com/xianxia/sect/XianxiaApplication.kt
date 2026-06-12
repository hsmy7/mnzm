package com.xianxia.sect

import android.app.Application
import android.content.ComponentCallbacks2
import android.content.res.Configuration
import android.util.Log
import com.xianxia.sect.core.util.DomainLog
import com.xianxia.sect.core.util.GameMonitorManager
import com.xianxia.sect.core.model.DiscipleStatsProvider
import com.xianxia.sect.core.model.DiscipleAggregate
import com.xianxia.sect.core.model.DiscipleStats
import com.xianxia.sect.core.model.EquipmentInstance
import com.xianxia.sect.core.model.ManualInstance
import com.xianxia.sect.core.model.ManualProficiencyData
import com.xianxia.sect.core.engine.domain.disciple.DiscipleStatCalculator
import com.xianxia.sect.core.util.VivoGCJITOptimizer
// import com.huawei.agconnect.crash.AGConnectCrash  // 待 AGC Crash SDK 依赖就绪后启用
import com.xianxia.sect.core.ChangelogData
import com.xianxia.sect.ui.components.SpriteResRegistry
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
                override fun isLoggedIn(): Boolean = com.xianxia.sect.taptap.TapTapAuthManager.isLoggedIn()
                override fun getAccountUserId(): String? {
                    val account = com.xianxia.sect.taptap.TapTapAuthManager.getCurrentAccount()
                    return account?.openId ?: account?.unionId
                }
            }

        // 注入 DiscipleStatCalculator 实现到 domain 模块
        DiscipleAggregate.statsProvider = object : DiscipleStatsProvider {
            override fun getBaseStats(disciple: com.xianxia.sect.core.model.Disciple) = DiscipleStatCalculator.getBaseStats(disciple)
            override fun getBaseStats(aggregate: DiscipleAggregate) = DiscipleStatCalculator.getBaseStats(aggregate)
            override fun getTalentEffects(disciple: com.xianxia.sect.core.model.Disciple) = DiscipleStatCalculator.getTalentEffects(disciple)
            override fun getTalentEffects(aggregate: DiscipleAggregate) = DiscipleStatCalculator.getTalentEffects(aggregate)
            override fun getStatsWithEquipment(disciple: com.xianxia.sect.core.model.Disciple, equipments: Map<String, EquipmentInstance>) = DiscipleStatCalculator.getStatsWithEquipment(disciple, equipments)
            override fun getStatsWithEquipment(aggregate: DiscipleAggregate, equipments: Map<String, EquipmentInstance>) = DiscipleStatCalculator.getStatsWithEquipment(aggregate, equipments)
            override fun getFinalStats(disciple: com.xianxia.sect.core.model.Disciple, equipments: Map<String, EquipmentInstance>, manuals: Map<String, ManualInstance>, manualProficiencies: Map<String, ManualProficiencyData>) = DiscipleStatCalculator.getFinalStats(disciple, equipments, manuals, manualProficiencies)
            override fun getFinalStats(aggregate: DiscipleAggregate, equipments: Map<String, EquipmentInstance>, manuals: Map<String, ManualInstance>, manualProficiencies: Map<String, ManualProficiencyData>) = DiscipleStatCalculator.getFinalStats(aggregate, equipments, manuals, manualProficiencies)
            override fun calculateCultivationSpeed(disciple: com.xianxia.sect.core.model.Disciple, manuals: Map<String, ManualInstance>, manualProficiencies: Map<String, ManualProficiencyData>, buildingBonus: Double, additionalBonus: Double, preachingElderBonus: Double, preachingMastersBonus: Double, cultivationSubsidyBonus: Double, parentCultivationBonus: Double, griefCultivationSpeedPenalty: Double) = DiscipleStatCalculator.calculateCultivationSpeed(disciple, manuals, manualProficiencies, buildingBonus, additionalBonus, preachingElderBonus, preachingMastersBonus, cultivationSubsidyBonus, parentCultivationBonus, griefCultivationSpeedPenalty)
            override fun calculateCultivationSpeed(aggregate: DiscipleAggregate, manuals: Map<String, ManualInstance>, manualProficiencies: Map<String, ManualProficiencyData>, buildingBonus: Double, additionalBonus: Double, preachingElderBonus: Double, preachingMastersBonus: Double, cultivationSubsidyBonus: Double, parentCultivationBonus: Double, griefCultivationSpeedPenalty: Double) = DiscipleStatCalculator.calculateCultivationSpeed(aggregate, manuals, manualProficiencies, buildingBonus, additionalBonus, preachingElderBonus, preachingMastersBonus, cultivationSubsidyBonus, parentCultivationBonus, griefCultivationSpeedPenalty)
            override fun getBreakthroughChance(disciple: com.xianxia.sect.core.model.Disciple, innerElderComprehension: Int, outerElderComprehensionBonus: Double, pillBonus: Double, adBonus: Double, griefBreakthroughPenalty: Double) = DiscipleStatCalculator.getBreakthroughChance(disciple, innerElderComprehension, outerElderComprehensionBonus, pillBonus, adBonus, griefBreakthroughPenalty)
            override fun getBreakthroughChance(aggregate: DiscipleAggregate, innerElderComprehension: Int, outerElderComprehensionBonus: Double, pillBonus: Double, adBonus: Double, griefBreakthroughPenalty: Double) = DiscipleStatCalculator.getBreakthroughChance(aggregate, innerElderComprehension, outerElderComprehensionBonus, pillBonus, adBonus, griefBreakthroughPenalty)
        }

        // Feature: agconnect-crash pending SDK integration
        //   阻塞项：需在 build.gradle 添加 com.huawei.agconnect:crash 依赖并配置 agconnect-services.json
        //   当前状态：Bugly 已作为主崩溃收集 SDK，AGC 仅用于华为设备补充上报
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
                    ReLinker.loadLibrary(this@XianxiaApplication, requireNotNull(libName) { "libName must not be null" })
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

        ChangelogData.initialize(this)

        // 初始化精灵图注册表：app 模块持有 R.drawable 引用，
        // 通过 registry 注入到 core:ui 模块供 ItemCard 等组件使用
        SpriteResRegistry.initialize(
            equipmentSprites = mapOf(
                "精铁剑" to R.drawable.jing_tie_jian,
                "精铁刀" to R.drawable.jing_tie_dao,
                "烈焰剑" to R.drawable.lie_yan_jian,
                "灵锋剑" to R.drawable.ling_feng_jian,
                "凌华刀" to R.drawable.ling_hua_dao,
                "雷霆剑" to R.drawable.lei_ting_jian,
                "青莲剑" to R.drawable.qing_lian_jian,
                "诛仙剑" to R.drawable.zhu_xian_jian,
                "凤炎刃" to R.drawable.feng_yan_ren,
                "青碧刃" to R.drawable.qing_bi_ren,
                "暗影刃" to R.drawable.an_ying_ren,
                "玄玉刃" to R.drawable.xuan_yu_ren,
                "桃木杖" to R.drawable.tao_mu_zhang,
                "碧玉杖" to R.drawable.bi_yu_zhang,
                "玄雷杖" to R.drawable.xuan_lei_zhang,
                "虚华杖" to R.drawable.xu_hua_zhang,
                "天玄杖" to R.drawable.tian_xuan_zhang,
                "天星杖" to R.drawable.tian_xing_zhang,
                "碧木扇" to R.drawable.bi_mu_shan,
                "灵风扇" to R.drawable.ling_feng_shan,
                "玄冰扇" to R.drawable.xuan_bing_shan,
                "凰焰扇" to R.drawable.huang_yan_shan,
                "阴阳扇" to R.drawable.yin_yang_shan,
                "天玄扇" to R.drawable.tian_xuan_shan,
                "锁子甲" to R.drawable.suo_zi_jia,
                "皮甲" to R.drawable.pi_jia,
                "灵竹衣" to R.drawable.ling_zhu_yi,
                "精铁甲" to R.drawable.jing_tie_jia,
                "碧叶甲" to R.drawable.bi_ye_jia,
                "丹羽衣" to R.drawable.dan_yu_yi,
                "青鳞铠" to R.drawable.qing_lin_kai,
                "银板铠" to R.drawable.yin_ban_kai,
                "汐流衣" to R.drawable.xi_liu_yi,
                "灵丝袍" to R.drawable.ling_si_pao,
                "云纹袍" to R.drawable.yun_wen_pao,
                "龙鳞铠" to R.drawable.long_lin_kai,
                "渊岩铠" to R.drawable.yuan_yan_kai,
                "瑶光袍" to R.drawable.yao_guang_pao,
                "月华袍" to R.drawable.yue_hua_pao,
                "星辰袍" to R.drawable.xing_chen_pao,
                "玄幽袍" to R.drawable.xuan_you_pao,
                "墨幽铠" to R.drawable.mo_you_kai,
                "凌星袍" to R.drawable.ling_xing_pao,
                "定海铠" to R.drawable.ding_hai_kai,
                "不朽铠" to R.drawable.bu_xiu_kai,
                "苍罡铠" to R.drawable.cang_gang_kai,
                "曦光铠" to R.drawable.xi_guang_kai,
                "云影袍" to R.drawable.yun_ying_pao,
                "奔雷靴" to R.drawable.ben_lei_xue,
                "长明坠" to R.drawable.chang_ming_zhui,
                "赤煞靴" to R.drawable.chi_sha_xue,
                "渡厄佩" to R.drawable.du_e_pei,
                "凤羽坠" to R.drawable.feng_yu_zhui,
                "鹤岚靴" to R.drawable.he_lan_xue,
                "疾风靴" to R.drawable.ji_feng_xue,
                "灵泉戒" to R.drawable.ling_quan_jie,
                "灵玉佩" to R.drawable.ling_yu_pei,
                "龙灵珠" to R.drawable.long_ling_zhu,
                "鸾羽履" to R.drawable.luan_yu_lv,
                "轻羽靴" to R.drawable.qing_yu_xue,
                "青澜靴" to R.drawable.qing_lan_xue,
                "兽皮靴" to R.drawable.shou_pi_xue,
                "溯光靴" to R.drawable.su_guang_xue,
                "踏云履" to R.drawable.ta_yun_lv,
                "铜项链" to R.drawable.tong_xiang_lian,
                "迅捷珠" to R.drawable.xun_jie_zhu,
                "隐云佩" to R.drawable.yin_yun_pei,
                "幽朔珠" to R.drawable.you_shuo_zhu,
                "玉戒指" to R.drawable.yu_jie_zhi,
                "云栖靴" to R.drawable.yun_qi_xue,
                "蕴灵戒" to R.drawable.yun_ling_jie,
                "追风靴" to R.drawable.zhui_feng_xue
            ),
            manualSprites = mapOf(
                1 to R.drawable.manual_fan,
                2 to R.drawable.manual_ling,
                3 to R.drawable.manual_bao,
                4 to R.drawable.manual_xuan,
                5 to R.drawable.manual_di,
                6 to R.drawable.manual_tian
            ),
            pillSprites = mapOf(
                1 to R.drawable.pill_fan,
                2 to R.drawable.pill_ling,
                3 to R.drawable.pill_bao,
                4 to R.drawable.pill_xuan,
                5 to R.drawable.pill_di,
                6 to R.drawable.pill_tian
            ),
            spiritStoneRes = R.drawable.spirit_stone,
            materialSprites = mapOf(
                "虎皮" to R.drawable.tiger_hide,
                "虎血" to R.drawable.tiger_blood,
                "虎牙" to R.drawable.tiger_tooth,
                "虎内丹" to R.drawable.tiger_core,
                "狼皮" to R.drawable.wolf_hide,
                "狼骨" to R.drawable.wolf_bone,
                "狼牙" to R.drawable.wolf_tooth,
                "狼内丹" to R.drawable.wolf_core,
                "蛇鳞" to R.drawable.snake_scale,
                "蛇血" to R.drawable.snake_blood,
                "蛇牙" to R.drawable.snake_tooth,
                "蛇内丹" to R.drawable.snake_core,
                "熊皮" to R.drawable.bear_hide,
                "熊骨" to R.drawable.bear_bone,
                "熊掌" to R.drawable.bear_claw,
                "熊内丹" to R.drawable.bear_core,
                "鹰羽" to R.drawable.eagle_feather,
                "鹰骨" to R.drawable.eagle_bone,
                "鹰爪" to R.drawable.eagle_claw,
                "鹰内丹" to R.drawable.eagle_core,
                "狐皮" to R.drawable.fox_hide,
                "狐骨" to R.drawable.fox_bone,
                "狐尾" to R.drawable.fox_tail,
                "狐内丹" to R.drawable.fox_core,
                "龙鳞" to R.drawable.dragon_scale,
                "龙爪" to R.drawable.dragon_claw,
                "龙角" to R.drawable.dragon_horn,
                "龙内丹" to R.drawable.dragon_core,
                "龟壳" to R.drawable.turtle_shell,
                "龟骨" to R.drawable.turtle_bone,
                "龟血" to R.drawable.turtle_blood,
                "龟内丹" to R.drawable.turtle_core
            ),
            storageBagSprites = mapOf(
                1 to R.drawable.bag_fan,
                2 to R.drawable.bag_ling,
                3 to R.drawable.bag_bao,
                4 to R.drawable.bag_xuan,
                5 to R.drawable.bag_di,
                6 to R.drawable.bag_tian
            ),
            sectIconSprites = mapOf(
                0 to R.drawable.sect_icon_small,
                1 to R.drawable.sect_icon_medium,
                2 to R.drawable.sect_icon_large,
                3 to R.drawable.sect_icon_top
            ),
            allEquipmentResIds = listOf(
                R.drawable.jing_tie_jian, R.drawable.jing_tie_dao,
                R.drawable.lie_yan_jian, R.drawable.ling_feng_jian,
                R.drawable.ling_hua_dao, R.drawable.lei_ting_jian,
                R.drawable.qing_lian_jian, R.drawable.zhu_xian_jian,
                R.drawable.feng_yan_ren, R.drawable.qing_bi_ren,
                R.drawable.an_ying_ren, R.drawable.xuan_yu_ren,
                R.drawable.tao_mu_zhang, R.drawable.bi_yu_zhang,
                R.drawable.xuan_lei_zhang, R.drawable.xu_hua_zhang,
                R.drawable.tian_xuan_zhang, R.drawable.tian_xing_zhang,
                R.drawable.bi_mu_shan, R.drawable.ling_feng_shan,
                R.drawable.xuan_bing_shan, R.drawable.huang_yan_shan,
                R.drawable.yin_yang_shan, R.drawable.tian_xuan_shan,
                R.drawable.suo_zi_jia, R.drawable.pi_jia,
                R.drawable.ling_zhu_yi, R.drawable.jing_tie_jia,
                R.drawable.bi_ye_jia, R.drawable.dan_yu_yi,
                R.drawable.qing_lin_kai, R.drawable.yin_ban_kai,
                R.drawable.xi_liu_yi, R.drawable.ling_si_pao,
                R.drawable.yun_wen_pao, R.drawable.long_lin_kai,
                R.drawable.yuan_yan_kai, R.drawable.yao_guang_pao,
                R.drawable.yue_hua_pao, R.drawable.xing_chen_pao,
                R.drawable.xuan_you_pao, R.drawable.mo_you_kai,
                R.drawable.ling_xing_pao, R.drawable.ding_hai_kai,
                R.drawable.bu_xiu_kai, R.drawable.cang_gang_kai,
                R.drawable.xi_guang_kai, R.drawable.yun_ying_pao,
                R.drawable.ben_lei_xue, R.drawable.chang_ming_zhui,
                R.drawable.chi_sha_xue, R.drawable.du_e_pei,
                R.drawable.feng_yu_zhui, R.drawable.he_lan_xue,
                R.drawable.ji_feng_xue, R.drawable.ling_quan_jie,
                R.drawable.ling_yu_pei, R.drawable.long_ling_zhu,
                R.drawable.luan_yu_lv, R.drawable.qing_yu_xue,
                R.drawable.qing_lan_xue, R.drawable.shou_pi_xue,
                R.drawable.su_guang_xue, R.drawable.ta_yun_lv,
                R.drawable.tong_xiang_lian, R.drawable.xun_jie_zhu,
                R.drawable.yin_yun_pei, R.drawable.you_shuo_zhu,
                R.drawable.yu_jie_zhi, R.drawable.yun_qi_xue,
                R.drawable.yun_ling_jie, R.drawable.zhui_feng_xue
            )
        )

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

        // 4.0 重置：清空 MMKV 和 SharedPreferences
        val resetMarker = "v4_reset_done"
        val resetPrefs = getSharedPreferences("v4_reset", MODE_PRIVATE)
        if (!resetPrefs.getBoolean(resetMarker, false)) {
            try {
                MMKV.defaultMMKV().clearAll()
                Log.i(TAG, "[4.0] MMKV cleared")
                listOf("crash_handler", "app_session").forEach { name ->
                    getSharedPreferences(name, MODE_PRIVATE).edit().clear().apply()
                }
                Log.i(TAG, "[4.0] SharedPreferences cleared")
                resetPrefs.edit().putBoolean(resetMarker, true).apply()
                Log.i(TAG, "[4.0] All storage reset complete")
            } catch (e: Exception) {
                Log.e(TAG, "[4.0] Storage reset failed", e)
            }
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
