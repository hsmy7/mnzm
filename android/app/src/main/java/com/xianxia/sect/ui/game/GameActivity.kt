package com.xianxia.sect.ui.game

import android.annotation.SuppressLint
import android.app.AlarmManager
import android.content.ComponentCallbacks2
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.annotation.VisibleForTesting
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.activity.enableEdgeToEdge
import androidx.core.graphics.drawable.toDrawable
import androidx.core.net.toUri
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import com.xianxia.sect.R
import com.xianxia.sect.core.CrashHandler
import com.xianxia.sect.core.CrashRecoveryEngine
import com.xianxia.sect.core.VulkanPolicy
import com.xianxia.sect.core.engine.GameEngineCore
import com.xianxia.sect.core.engine.PerformanceMode
import com.xianxia.sect.core.util.GameForegroundService
import com.xianxia.sect.core.model.MapPreloadData
import com.xianxia.sect.core.state.BootPhase
import com.xianxia.sect.ui.util.ActionModeSafeCallback
import com.xianxia.sect.core.state.RunState
import com.xianxia.sect.core.util.VivoGCJITOptimizer
import com.xianxia.sect.core.perf.FrameMetricsMonitor
import com.xianxia.sect.platform.WindowFrameMetricsSession
import com.xianxia.sect.data.crypto.SecureKeyManager
import com.xianxia.sect.data.crypto.UiKeyRecoveryCallback
import com.xianxia.sect.data.facade.StorageFacade
import com.xianxia.sect.data.SessionManager
import com.xianxia.sect.ui.ComplianceDialogState
import com.xianxia.sect.ui.ComplianceLimitDialogs
import com.xianxia.sect.ui.MainActivity
import com.xianxia.sect.ui.components.GameButton
import com.xianxia.sect.ui.components.ImeAnimationTracker
import com.xianxia.sect.ui.components.ImeStateMachine
import com.xianxia.sect.ui.components.ImeVisibilityTracker
import com.xianxia.sect.ui.components.StandardPromptDialog
import com.xianxia.sect.ui.components.SystemBarFreezeScope
import com.xianxia.sect.ui.components.SystemBarHidePolicy
import com.xianxia.sect.ui.game.sect.NativeSurfaceView
import com.xianxia.sect.ui.theme.XianxiaTheme
import androidx.compose.runtime.CompositionLocalProvider
import com.xianxia.sect.core.audio.AudioConfig
import com.xianxia.sect.core.audio.AudioPlayerFacade
import com.xianxia.sect.ui.components.LocalPlayClickSound
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import com.xianxia.sect.core.AdFreeWhitelist
import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.RenderDebugSwitches
import com.xianxia.sect.core.render.VulkanPrewarmState
import com.xianxia.sect.taptap.AdServiceImpl
import com.xianxia.sect.core.nativebridge.NativeBridge
import com.xianxia.sect.core.engine.di.IoDispatcher
import android.view.ActionMode
import android.view.View
import android.view.Window
import javax.inject.Inject
import com.xianxia.sect.core.engine.onUserActivity

@AndroidEntryPoint
@Suppress("TooManyFunctions") // Activity 框架契约面：生命周期/权限/结果回调族（24 个 override 契约下界超类阈值）
// 必须驻留类体承载框架分发；余量为平台胶水（UI 装配/前台服务接线）。
class GameActivity : ComponentActivity() {

    companion object {
        private const val TAG = "GameActivity"
        private const val KEY_CURRENT_SLOT = "current_slot"
        /**
         * 解冻后延迟恢复系统栏隐藏的等待时长（毫秒）：
         * 覆盖 Dialog 窗口销毁后键盘收起动画的剩余时长，等待 IME 状态落定
         * 再恢复隐藏，切断"键盘动画期间 hide() 对抗"。
         */
        private const val SYSTEM_BAR_RESTORE_DELAY_MS = 350L
    }

    /** 主线程 Handler（解冻恢复延迟任务用） */
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    /**
     * 输入对话框销毁解冻 / 键盘动画结束后恢复系统栏隐藏。
     *
     * 触发源：① [SystemBarFreezeScope] 解冻监听器；② [ImeAnimationTracker] 键盘动画
     * onEnd 回调。执行语义：
     * - 立即复查：键盘不可见且无动画且未冻结 → 直接恢复隐藏
     *   （docs/ime-android-system-research.md §2.3：ROM 键盘动画时长差异大，固定延时在
     *   动画 >350ms 的 ROM 上过早恢复会与残余动画对抗）
     * - 350ms 延时仅作"回调未触发/状态未落定"兜底，执行前再经
     *   [SystemBarHidePolicy] 双守卫校验（isVisible 真值 + 动画状态）
     */
    private val systemBarRestoreListener: () -> Unit = {
        if (ImeStateMachine.canRestoreSystemBars() && !SystemBarFreezeScope.isFrozen) {
            hideSystemBars()
        } else {
            mainHandler.postDelayed({
                if (!SystemBarHidePolicy.shouldSkipHide()) hideSystemBars()
            }, SYSTEM_BAR_RESTORE_DELAY_MS)
        }
    }

    private val viewModel: GameViewModel by viewModels()
    private val saveLoadViewModel: SaveLoadViewModel by viewModels()
    private val productionViewModel: ProductionViewModel by viewModels()
    private val alchemyViewModel: AlchemyViewModel by viewModels()
    private val forgeViewModel: ForgeViewModel by viewModels()
    private val herbGardenViewModel: HerbGardenViewModel by viewModels()
    private val spiritMineViewModel: SpiritMineViewModel by viewModels()
    private val patrolTowerViewModel: PatrolTowerViewModel by viewModels()
    private val bloodRefiningViewModel: BloodRefiningViewModel by viewModels()
    private val worldMapInteractionViewModel: WorldMapInteractionViewModel by viewModels()
    private val worldMapGarrisonViewModel: WorldMapGarrisonViewModel by viewModels()
    private val battleViewModel: BattleViewModel by viewModels()

    @Inject
    lateinit var sessionManager: SessionManager

    @Inject
    lateinit var storageFacade: StorageFacade

    @Inject
    lateinit var crashHandler: CrashHandler

    @Inject
    lateinit var backgroundTaskScheduler: com.xianxia.sect.core.util.BackgroundTaskScheduler

    @Inject
    lateinit var frameMetricsMonitor: FrameMetricsMonitor

    @Inject
    lateinit var wakeLockManager: com.xianxia.sect.core.util.WakeLockManager

    @Inject
    lateinit var ioDispatcher: IoDispatcher

    @Inject
    lateinit var adServiceImpl: AdServiceImpl

    @Inject
    lateinit var audioConfig: AudioConfig

    @Inject
    lateinit var audioEngine: AudioPlayerFacade

    @Inject
    lateinit var complianceCallbackHost: com.xianxia.sect.taptap.ComplianceCallbackHost

    @Inject
    lateinit var gamePreferences: com.xianxia.sect.data.prefs.GamePreferences

    /** 防沉迷合规限制对话框状态（游戏内限制提示，与 MainActivity 共享类型） */
    private val complianceDialogState = mutableStateOf<ComplianceDialogState?>(null)

    /** 合规回调窗口端口（游戏窗口适配器，宿主按接口转发） */
    private val complianceWindowPort = object : com.xianxia.sect.taptap.ComplianceCallbackHost.WindowPort {
        override fun postToUi(block: () -> Unit) = this@GameActivity.runOnUiThread(block)
        override fun isAlive(): Boolean = !isFinishing && !isDestroyed
        override fun onLoginSuccess() = Unit
        override fun onExited() = Unit
        override fun onNetworkError() = Unit
        override fun onRestrict(title: String, message: String) =
            this@GameActivity.showComplianceRestrict(title, message)
        override fun onAgeLimit() = this@GameActivity.showComplianceAgeLimit()
    }

    // ── GameForegroundService 绑定 ──
    // 游戏循环控制权在 GameForegroundService，Activity 通过 Binder 获取 GameEngineCore 实例
    private var gameService: GameForegroundService? = null
    private var gameEngineCore: GameEngineCore? = null
    private var isServiceBound = false
    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as GameForegroundService.GameEngineBinder
            gameService = binder.getService()
            gameEngineCore = binder.getGameEngineCore()
            onGameServiceBound()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            gameService = null
            gameEngineCore = null
            // 服务崩溃后重置绑定标志，允许 onResume 重新 bindService
            isServiceBound = false
        }
    }

    // 持有地图预加载数据引用，供 onTrimMemory 中释放内存使用
    @Volatile
    private var mapPreloadDataRef: MapPreloadData? = null

    /**
     * Vulkan 设备预热 + ASTC 图集预取单次守卫。
     *
     * 预热在 onCreate 即发起、与 boot 数据阶段并行，避免 Phase1
     * （instance/device/ShaderModule/PipelineCache 加载）与 surface 初始化竞速。
     * PLAYING 分支复用同一守卫兜底（已完成/在飞时直接返回，不二次 prewarm——
     * prewarmDevice 会 delete 旧 g_renderer，重复进入有破坏性）。
     */
    private val vulkanPrewarmLaunched = java.util.concurrent.atomic.AtomicBoolean(false)

    /**
     * ActionMode 跟踪器：拦截 FloatingActionMode 生命周期，确保在 Activity 销毁前清理。
     * 防止文本选择工具栏在窗口 token 无效后弹出导致 BadTokenException。
     */
    private var actionModeTracker: ActionModeSafeCallback? = null

    // 新档埋点一次性标记：由 onCreate 启动参数写入，PLAYING 首次上报 #game_new_save 后消费置 false
    private var launchIsNewGame = false
    private var launchSectName = ""
    private var launchSlot = -1

    override fun onCreate(savedInstanceState: Bundle?) {
        // 渲染安全模式检测（必须在 super.onCreate() 前）
        applySafeModeThemeIfNeeded()
        super.onCreate(savedInstanceState)
        Log.d(TAG, "onCreate started, savedInstanceState=$savedInstanceState")

        // 窗口背景/崩溃处理器/渲染策略/系统 UI
        setupWindowAndDiagnostics()

        SecureKeyManager.recoveryCallback = UiKeyRecoveryCallback { this@GameActivity }

        // 启动参数解析
        val launch = resolveLaunchIntent(savedInstanceState)
        val slot = launch.slot
        val isNewGame = launch.isNewGame
        val sectName = launch.sectName
        val isCloudSaveLoad = launch.isCloudSaveLoad
        val isSoftwareRendering = launch.isSoftwareRendering
        val isGlesRendering = launch.isGlesRendering
        launchIsNewGame = isNewGame
        launchSectName = sectName
        launchSlot = slot

        Log.d(
            TAG,
            "Slot info: savedSlot=${savedInstanceState?.getInt(KEY_CURRENT_SLOT, -1)}, " +
                "intentSlot=${intent.getIntExtra(MainActivity.EXTRA_SLOT, -1)}, " +
                "finalSlot=$slot, isNewGame=$isNewGame, sectName=$sectName"
        )
        Log.d(TAG, "ViewModel game loaded: ${saveLoadViewModel.isGameAlreadyLoaded()}")

        setContent {
            GameContent(isSoftwareRendering, isGlesRendering)
        }

        // 免广告白名单须在游戏初始化前就绪：boot 后同协程即注入特权邮件，
        // 若等 Compose 重组，unionId 尚为 null，白名单判定会失败
        AdFreeWhitelist.initialize(sessionManager.unionId)

        // 游戏初始化分发（新游戏/读档/云读档/云槽位下载）
        initializeGameIfNeeded(slot, isNewGame, sectName, isCloudSaveLoad, launch.cloudSlot)

        // Vulkan 设备预热（Phase1）+ ASTC 图集预取在进入 Activity 即后台执行——
        // 与 boot 数据阶段并行，避免 PLAYING 时点才发起的预热与 surface 初始化竞速。
        // 渲染策略在 setupWindowAndDiagnostics 已确定，此处直接按策略分流。
        if (!isSoftwareRendering && !isGlesRendering) {
            startVulkanPrewarmAndAtlasPrefetch()
        } else {
            Log.d(
                TAG,
                "Non-Vulkan strategy (software=$isSoftwareRendering, gles=$isGlesRendering)" +
                    " — skip early prewarm/prefetch"
            )
        }

        Log.d(TAG, "onCreate completed")
    }

    /**
     * Vulkan 设备预热（Phase1：instance/device/ShaderModule/PipelineCache 加载）
     * + ASTC 图集资产预取（IO 移出 surface 就绪后的关键路径）。
     *
     * 单次执行（[vulkanPrewarmLaunched] 守卫）；PLAYING 分支的调用是兜底重入点，
     * 已完成/在飞时直接返回。
     *
     * prewarm 运行在**不可取消的专用线程**：仅以 `withTimeout(5s)`
     * 放弃等待时 JNI 仍持 `g_rendererLifecycleMutex` 运行（与 surface 期
     * initRenderer 在同锁上真实竞争），且 TimeoutCancellationException 分支会对
     * 仍在跑的 prewarm 伪造失败记录。结果经台账落地：
     * - 成功 → setVulkanDeviceInfo（低于量化阈值由其内部记 soft-fail）；
     * - 失败 → recordVulkanSoftFailure("prewarm")；
     * - prewarm 慢 → 由 VulkanPrewarmState 预算协同接住（NativeSurfaceView 把
     *   安全网延长为 prewarm 起点 + 8s + 10s），不再有超时伪造失败；
     * - prewarm 真挂死 → 预算到期降级 + 写前标记残留（进程死后下轮 kill+1）。
     * 图集预取保留协程（可取消、无锁竞争）。
     */
    @Suppress("TooGenericExceptionCaught") // 防御兜底: 异常源跨IO/SDK不可枚举, 降级继续+日志留痕, 非静默吞噬
    private fun startVulkanPrewarmAndAtlasPrefetch() {
        if (!vulkanPrewarmLaunched.compareAndSet(false, true)) return
        lifecycleScope.launch(ioDispatcher.dispatcher) {
            // ── 图集预取：KTX 21.3MB 资产读取提前（上传仍在 surface 就绪后主线程）──
            try {
                val prefetched = com.xianxia.sect.ui.game.sect.SectAtlasPrefetch.prefetch(
                    applicationContext
                )
                Log.d(TAG, "ASTC atlas prefetch: ${if (prefetched) "done" else "unavailable"}")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "ASTC atlas prefetch failed (will re-read at surface time)", e)
            }
        }
        // ── 设备预热：专用线程（JNI 不可取消——withTimeout 只能放弃等待，
        //    放弃后仍持 g_rendererLifecycleMutex 运行，与 initRenderer 同锁竞争）──
        kotlin.concurrent.thread(name = "VulkanPrewarm", isDaemon = true) {
            NativeBridge.ensureLoaded()
            VulkanPrewarmState.markStarted()
            CrashRecoveryEngine.markPrewarmStarted()
            val ok = try {
                NativeBridge.prewarmDevice(
                    applicationContext.cacheDir.absolutePath,
                    GameConfig.SectMap.WORLD_WIDTH_CELLS * GameConfig.SectMap.TILE_SIZE,
                    GameConfig.SectMap.WORLD_HEIGHT_CELLS * GameConfig.SectMap.TILE_SIZE,
                    GameConfig.SectMap.TILE_SIZE
                )
            } catch (t: Throwable) {
                // 防御兜底: JNI 异常源不可枚举, 记台账失败+日志留痕, 非静默吞噬
                Log.e(TAG, "Vulkan prewarm exception", t)
                false
            }
            CrashRecoveryEngine.clearPrewarmStarted()
            if (ok) {
                // 低于厂商量化阈值时由 setVulkanDeviceInfo 内部记 soft-fail("threshold")
                VulkanPolicy.setVulkanDeviceInfo(
                    NativeBridge.getVulkanVendorId(),
                    NativeBridge.getVulkanApiVersion(),
                    NativeBridge.getVulkanDriverVersion(),
                    NativeBridge.getVulkanDeviceName()
                )
            } else {
                CrashRecoveryEngine.recordVulkanSoftFailure("prewarm")
            }
            VulkanPrewarmState.markFinished()
            Log.i(TAG, "Vulkan prewarm finished (ok=$ok)")
        }
    }

    /**
     * 游戏主内容（成员字段可直接访问）。
     *
     * 声明式 UI 单屏（LoadingScreen/MainGameScreen 过渡 + Vulkan 预热），
     * 结构不可再拆（状态驱动渲染树），复杂度豁免与 MainGameScreen 同类。
     */
    @Suppress("LongMethod", "CyclomaticComplexMethod")
    @Composable
    private fun GameContent(isSoftwareRendering: Boolean, isGlesRendering: Boolean) {
            XianxiaTheme {
                CompositionLocalProvider(LocalPlayClickSound provides { audioEngine.playSound("click") }) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    val loadingProgress by saveLoadViewModel.loadingProgress.collectAsStateWithLifecycle()
                    val preloadPhase by saveLoadViewModel.preloadPhase.collectAsStateWithLifecycle()
                    var errorMessage by remember { mutableStateOf<String?>(null) }
                    val isRestarting by saveLoadViewModel.isRestarting.collectAsStateWithLifecycle()
                    
                    val gameData by viewModel.gameData.collectAsStateWithLifecycle()
                    // 每宗独立地图：sectMapData 随 activeSectId 惰性生成（主宗=boot 种子，
                    // 被占宗门=派生种子）；游戏未加载时保持 null，由 boot 图兜底
                    val sectMapData by viewModel.sectMapData.collectAsStateWithLifecycle()

                    // 贴图加载在 LaunchedEffect 中完成，但 MainGameScreen 只在加载完成后才进入组合树
                    // 从根本上杜绝 "LoadingScreen 消失但贴图未就绪" 的中间帧
                    var mapPreloadData by remember { mutableStateOf<MapPreloadData?>(null) }
                    val bootPhase by saveLoadViewModel.bootPhase.collectAsStateWithLifecycle()
                    val runState by saveLoadViewModel.runState.collectAsStateWithLifecycle()

                    // 游戏生命周期驱动 UI 过渡（替代旧的 gameData.isGameStarted 方案）
                    // MAP_READY → 地图瓦片就绪，安全切换 Crossfade 到 MainGameScreen
                    // PLAYING   → 游戏 fully loaded，触发 TapDB 上报和 Vulkan 预热
                    // restartVersion 变化（游戏内重启）时强制
                    // 刷新地图瓦片——仅靠 mapPreloadData == null 守卫会让重启后旧世界
                    // 瓦片与新世界建筑混显
                    val restartVersion by saveLoadViewModel.restartVersion.collectAsStateWithLifecycle()
                    LaunchedEffect(bootPhase, runState, restartVersion) {
                        when {
                            bootPhase >= BootPhase.MAP_READY -> {
                                // 从 ViewModel 获取已预生成的地图瓦片数据（重启后必是新世界数据）
                                val precomputed = saveLoadViewModel.mapPreloadData.value
                                if (precomputed != null) {
                                    mapPreloadData = precomputed
                                    mapPreloadDataRef = precomputed
                                }

                                saveLoadViewModel.setLoadingProgress(1.0f)
                            }
                            runState == RunState.PLAYING -> {
                                com.xianxia.sect.taptap.TapDBManager.setLevel(gameData.gameYear)
                                com.xianxia.sect.taptap.TapDBManager.setServer(gameData.sectName)
                                com.xianxia.sect.taptap.TapDBManager.trackEvent(
                                    "game_start",
                                    mapOf(
                                        "sect_name" to gameData.sectName,
                                        "game_version" to com.xianxia.sect.BuildConfig.VERSION_NAME
                                    )
                                )
                                // 新档创建（FTUE 漏斗）：仅本次新档启动上报一次（首次消费后不再重复）
                                if (launchIsNewGame) {
                                    launchIsNewGame = false
                                    com.xianxia.sect.taptap.TapDBManager.trackEvent(
                                        com.xianxia.sect.core.util.AnalyticsEvents.GAME_NEW_SAVE,
                                        mapOf(
                                            com.xianxia.sect.core.util.AnalyticsEvents.PROP_SLOT to launchSlot,
                                            com.xianxia.sect.core.util.AnalyticsEvents.PROP_SECT_NAME to launchSectName
                                        )
                                    )
                                }

                                // Vulkan 预热：后台发射，不阻塞地图显示。
                                // 预热主路径在 onCreate（与 boot 数据阶段并行），此处仅兜底——
                                // [vulkanPrewarmLaunched] 单次守卫保证真正执行一次；
                                // 已完成/在飞时本调用直接返回。GLES 无两阶段
                                // prewarm（C++ 侧 g_backendType!=0 直接返回），且提前
                                // 创建 VulkanBackend 会与后续 GLES init 冲突。
                                if (!isSoftwareRendering && !isGlesRendering) {
                                    startVulkanPrewarmAndAtlasPrefetch()
                                } else {
                                    Log.d(TAG, "Software rendering — skipping Vulkan prewarm")
                                }
                            }
                        }
                    }

                    // L2 后台精灵图预加载：主界面就绪后触发，不阻塞首帧
                    LaunchedEffect(mapPreloadData) {
                        if (mapPreloadData != null) {
                            saveLoadViewModel.launchL2Preload()
                        }
                    }

                    LaunchedEffect(Unit) {
                        saveLoadViewModel.errorEvents.collect { msg ->
                            errorMessage = msg
                        }
                    }

                    // 全屏加载页仅在首次进入（地图未就绪，mapPreloadData==null）时显示；
                    // 游戏内读档/云下载的加载反馈由存档弹窗自身的"转圈+读取中"承担
                    //（不用 isLoading 驱动全屏切换：游戏内弹窗为独立窗口 + 60% 黑色
                    // 遮罩，会完全盖住全屏加载页并导致游戏画面无谓切换）
                    Crossfade(
                        targetState = mapPreloadData != null,
                        animationSpec = tween(durationMillis = 400),
                        label = "loadingToGameTransition"
                    ) { showGame ->
                        val preloadData = mapPreloadData
                        if (showGame && preloadData != null) {
                            // 注入 Activity 引用到广告服务实现
                            adServiceImpl.attachActivity(this@GameActivity)

                            MainGameScreen(
                                // 主宗用 sectMapData.map（= boot 同种子图），
                                // 进入被占宗门时 sectMapData 已换为该宗派生种子底图；null 兜底 boot 图
                                mapPreloadData = sectMapData?.map ?: preloadData,
                                viewModel = viewModel,
                                saveLoadViewModel = saveLoadViewModel,
                                productionViewModel = productionViewModel,
                                alchemyViewModel = alchemyViewModel,
                                forgeViewModel = forgeViewModel,
                                herbGardenViewModel = herbGardenViewModel,
                                spiritMineViewModel = spiritMineViewModel,
                                patrolTowerViewModel = patrolTowerViewModel,
                                bloodRefiningViewModel = bloodRefiningViewModel,
                                worldMapInteractionViewModel = worldMapInteractionViewModel,
                                worldMapGarrisonViewModel = worldMapGarrisonViewModel,
                                battleViewModel = battleViewModel,
                                onLogout = {
                                    sessionManager.clearSession()
                                    // 完整登出（对齐 MainActivity.handleUserExit）：清 TapTap SDK
                                    // 登录态——否则残留会话使下次登录走"静默登录"（不弹登录页），
                                    // 防沉迷验证不触发导致卡在登录界面；停时长统计；解绑合规回调
                                    com.xianxia.sect.taptap.TapTapAuthManager.logout()
                                    com.xianxia.sect.taptap.TapDBManager.stopGameDurationTracking()
                                    com.xianxia.sect.taptap.ComplianceManager.unregisterCallback()
                                    val intent = Intent(this@GameActivity, MainActivity::class.java)
                                    intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                                    startActivity(intent)
                                    finish()
                                },
                                onRestartGame = {
                                    saveLoadViewModel.restartGame()
                                },
                                forceSoftwareRendering = isSoftwareRendering,
                                glesRendering = isGlesRendering,
                                vulkanInitListener = object : NativeSurfaceView.VulkanInitListener {
                                    override fun onSurfaceInitStarted() {
                                        com.xianxia.sect.core.CrashRecoveryEngine.markSurfaceInitStarted()
                                    }

                                    override fun onSurfaceInitSucceeded() {
                                        // 清除写前标记（此前本回调从未被调用，
                                        // 标记全靠 onCleanLaunch 清除——台账增量消费语义下
                                        // 成功会话必须自清）
                                        com.xianxia.sect.core.CrashRecoveryEngine.clearSurfaceInitStarted()
                                    }

                                    override fun onSurfaceInitFailed() {
                                        com.xianxia.sect.core.CrashRecoveryEngine.clearSurfaceInitStarted()
                                        com.xianxia.sect.core.CrashRecoveryEngine.recordVulkanSoftFailure("initSurface")
                                    }

                                    override fun onVulkanChainSucceeded() {
                                        // ★ 台账成功回写（Task 2.3）：清零失败计数 +
                                        // GPU 设备信息落台账（量化阈值的真实输入）
                                        com.xianxia.sect.core.CrashRecoveryEngine.recordVulkanSuccess(
                                            NativeBridge.getVulkanVendorId(),
                                            NativeBridge.getVulkanApiVersion(),
                                            NativeBridge.getVulkanDriverVersion(),
                                            NativeBridge.getVulkanDeviceName()
                                        )
                                    }
                                }
                            )
                        } else {
                            LoadingScreen(
                                progress = loadingProgress,
                                showProgress = true,
                                phaseText = preloadPhase
                            )
                        }
                    }

                    // 生命周期门控：存档错误事件可能落在 Activity 销毁窗口期，
                    // 此时渲染 Dialog 会抛 BadTokenException（Bugly #3098）
                    val activityLifecycleState by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
                    if (activityLifecycleState.isAtLeast(Lifecycle.State.STARTED)) {
                        // SR-3：云存档真冲突弹窗（双源置态：下载侧 conflicts 流 /
                        // 上传侧 ConflictHeld）——二选一收口，禁止静默覆盖
                        val cloudConflict by saveLoadViewModel.pendingCloudConflict.collectAsStateWithLifecycle()
                        cloudConflict?.let { conflict ->
                            CloudConflictDialog(
                                conflict = conflict,
                                onKeepLocal = { saveLoadViewModel.resolveCloudConflict(keepLocal = true) },
                                onKeepCloud = { saveLoadViewModel.resolveCloudConflict(keepLocal = false) }
                            )
                        }
                        errorMessage?.let { error ->
                            // boot 失败（isGameLoaded=false）时补"返回主菜单"按钮——
                            // 否则 LoadingScreen 无按钮，唯一出口是系统返回键
                            StandardPromptDialog(
                                onDismissRequest = { errorMessage = null },
                                title = "提示",
                                text = error,
                                customButtons = {
                                    if (!saveLoadViewModel.isGameLoaded) {
                                        GameButton(
                                            text = "返回主菜单",
                                            onClick = {
                                                errorMessage = null
                                                navigateBackToMainMenu()
                                            },
                                            buttonBackgroundRes = R.drawable.ui_button
                                        )
                                        Spacer(modifier = Modifier.width(16.dp))
                                    }
                                    GameButton(
                                        text = "确定",
                                        onClick = { errorMessage = null },
                                        buttonBackgroundRes = R.drawable.ui_button
                                    )
                                }
                            )
                        }
                    }

                    // 防沉迷合规限制对话框（游戏内时长/时间/年龄限制提示）——
                    // 共享组件自带生命周期门控 + DialogSystemBarGuard（游戏内系统栏
                    // 已隐藏，Dialog Window 需独立守卫）
                    ComplianceLimitDialogs(
                        complianceDialogState = complianceDialogState,
                        onLogout = { performComplianceLogout() },
                        onAgeFinish = { navigateBackToMainMenu() }
                    )
                }
            }
            }
        }

    /** 渲染安全模式检测——super.onCreate() 前切换主题使 hardwareAccelerated 生效。 */

    /**
     * 渲染后端调试开关（第 6 步；仅 DEBUG 构建生效，Release 构建内
     * [RenderDebugSwitches.forceBackend] 恒返回 NONE——无行为分叉）：覆写策略
     * 决策强制会话走指定后端，为 GLES 线程契约/台账闭环的真机验证提供入口。
     */
    private fun applyRenderDebugSwitchIfNeeded() {
        when (RenderDebugSwitches.forceBackend(this)) {
            RenderDebugSwitches.FORCE_VULKAN -> {
                _isSoftwareRendering = false; _isGlesRendering = false
                Log.w(TAG, "RenderDebugSwitch: force VULKAN")
            }
            RenderDebugSwitches.FORCE_GLES -> {
                _isSoftwareRendering = false; _isGlesRendering = true
                Log.w(TAG, "RenderDebugSwitch: force GLES")
            }
            RenderDebugSwitches.FORCE_SOFTWARE -> {
                _isSoftwareRendering = true; _isGlesRendering = false
                Log.w(TAG, "RenderDebugSwitch: force SOFTWARE")
            }
            else -> Unit // 跟随策略
        }
    }

    // ── 合规限制展示（进程级宿主转发入口） ──

    /** 时间/时长限制弹窗（游戏内窗口） */
    internal fun showComplianceRestrict(title: String, message: String) {
        complianceDialogState.value = ComplianceDialogState.Restrict(title, message)
    }

    /** 适龄限制弹窗（游戏内窗口） */
    internal fun showComplianceAgeLimit() {
        complianceDialogState.value = ComplianceDialogState.AgeLimit
    }

    /**
     * 合规限制弹窗"退出游戏/切换账号"：清会话 + 完整登出（清 TapTap SDK 登录态 /
     * 停时长统计 / 解绑合规回调，对齐 MainActivity.performComplianceLogout）+ 回主界面。
     */
    private fun performComplianceLogout() {
        sessionManager.clearSession()
        com.xianxia.sect.taptap.TapTapAuthManager.logout()
        com.xianxia.sect.taptap.TapDBManager.stopGameDurationTracking()
        com.xianxia.sect.taptap.ComplianceManager.unregisterCallback()
        navigateBackToMainMenu()
    }

    /**
     * boot 失败弹窗"返回主菜单"——复用 onLogout 的
     * MainActivity 重建模式（不清 session，仅清 Activity 栈）。
     */
    private fun navigateBackToMainMenu() {
        val intent = buildMainMenuIntent(this)
        startActivity(intent)
        finish()
    }

    private fun applySafeModeThemeIfNeeded() {
        // CrashRecoveryEngine + VulkanPolicy 在 Application.onCreate 中已初始化，
        // 此处直接读取缓存的决策，无需 Context
        val disableAccel = CrashRecoveryEngine.isSafeMode() ||
            VulkanPolicy.isAccelerationDisabled()
        if (disableAccel) {
            setTheme(R.style.Theme_XianxiaSect_GameSafe)
            val msg = "HW accel disabled: safeMode=${CrashRecoveryEngine.isSafeMode()}, " +
                "vulkan=${VulkanPolicy.isAccelerationDisabled()}"
            Log.w(TAG, msg)
        }
    }

    /** 渲染策略（setupWindowAndDiagnostics 与启动解析共享） */
    @Volatile
    private var _isSoftwareRendering = false
    /** 是否使用 GPU OpenGL ES 中间层（Vulkan 不可靠但 GPU 可用设备） */
    private var _isGlesRendering = false

    /**
     * 窗口背景/崩溃处理/ActionMode 拦截/渲染策略/系统 UI 初始化。
     * 必须在 setContent 之前调用。
     */
    private fun setupWindowAndDiagnostics() {
        // 设置实心窗口背景，防止华为模拟器等设备上 MainActivity 窗口残留
        // 穿透透明 windowBackground 显示。必须在 setContent 之前调用。
        window.setBackgroundDrawable(
            android.graphics.Color.BLACK.toDrawable()
        )

        // 初始化并注册崩溃处理器
        setupCrashHandler()

        // 拦截和管理 ActionMode 生命周期，防止 FloatingActionMode（文本选择工具栏）
        // 在 Activity 销毁时弹出 PopupWindow 导致 BadTokenException
        installActionModeSafeCallback()

        // 记录设备诊断信息到日志（供 Bugly / 崩溃分析使用）
        VulkanPolicy.logDeviceDiagnostics(this)
        // 标记本次为干净启动，重置连续崩溃计数器
        CrashRecoveryEngine.onCleanLaunch()

        // ★ 渲染回退结构化上报接线：core:engine 的 Reporter 经
        //   端口注入 app 模块能力（持久化 → CrashRecoveryEngine；遥测 → TapDB），
        //   避免反向依赖。任何一次降级可事后回答「从哪降到哪、卡在哪个阶段、什么 GPU」。
        com.xianxia.sect.core.render.RenderFallbackReporter.persistSink =
            { CrashRecoveryEngine.recordLastFallback(it) }
        com.xianxia.sect.core.render.RenderFallbackReporter.telemetrySink =
            { name, props -> com.xianxia.sect.taptap.TapDBManager.trackEvent(name, props) }

        // 渲染策略决策：模拟器/云游戏/安全模式走软件渲染；正常设备 Vulkan（带降级回退）；
        //   Vulkan 不可靠但 GPU 可用（MediaTek/Mali/非高通国产/旧 API 非白名单）→ GPU GLES 中间层
        val renderStrategy = VulkanPolicy.getRenderStrategy(this)
        _isSoftwareRendering = renderStrategy == VulkanPolicy.RenderStrategy.SOFTWARE_ONLY
        _isGlesRendering = renderStrategy == VulkanPolicy.RenderStrategy.GLES_PREFERRED
        applyRenderDebugSwitchIfNeeded()
        Log.i(TAG, "Render strategy: ${renderStrategy.description}")

        enableEdgeToEdge()
        // 键盘可见性跟踪 + 输入对话框解冻恢复
        ImeVisibilityTracker.attach(window)
        // 键盘显隐动画跟踪（动画期系统栏零切换 +
        // 动画结束回调驱动系统栏恢复）
        ImeAnimationTracker.attach(window)
        SystemBarFreezeScope.addOnUnfreezeListener(systemBarRestoreListener)
        ImeAnimationTracker.addOnAnimationEndedListener(systemBarRestoreListener)
        hideSystemBars()
    }

    /** 从 savedInstanceState/intent 解析启动参数。 */
    private fun resolveLaunchIntent(savedInstanceState: Bundle?): GameLaunchParams {
        val savedSlot = savedInstanceState?.getInt(KEY_CURRENT_SLOT, -1) ?: -1
        val intentSlot = intent.getIntExtra(MainActivity.EXTRA_SLOT, -1)
        val isNewGame = intent.getBooleanExtra(MainActivity.EXTRA_NEW_GAME, false)
        val sectName = intent.getStringExtra(MainActivity.EXTRA_SECT_NAME) ?: "青云宗"
        val isCloudSaveLoad = intent.getBooleanExtra(MainActivity.EXTRA_CLOUD_SAVE_LOAD, false)
        // SR-3：云槽位下载（slot_N → 云端 slot_N 档，下载落盘后 boot）。不随
        // savedInstanceState 持久化——进程回收重建时若缓存已落盘则走常规槽位加载
        val cloudSlot = intent.getIntExtra(MainActivity.EXTRA_CLOUD_SLOT, -1)
        return GameLaunchParams(
            slot = if (savedSlot >= 0) savedSlot else intentSlot,
            isNewGame = isNewGame,
            sectName = sectName,
            isCloudSaveLoad = isCloudSaveLoad,
            cloudSlot = cloudSlot,
            isSoftwareRendering = _isSoftwareRendering,
            isGlesRendering = _isGlesRendering
        )
    }

    /** 启动参数聚合。 */
    private data class GameLaunchParams(
        val slot: Int,
        val isNewGame: Boolean,
        val sectName: String,
        val isCloudSaveLoad: Boolean,
        val cloudSlot: Int = -1,
        val isSoftwareRendering: Boolean,
        val isGlesRendering: Boolean = false
    )

    /** 游戏初始化分发（新游戏/读档/云读档/云槽位下载，JIT 暂停下执行）。 */
    private fun initializeGameIfNeeded(
        slot: Int,
        isNewGame: Boolean,
        sectName: String,
        isCloudSaveLoad: Boolean,
        cloudSlot: Int
    ) {
        if (saveLoadViewModel.isGameAlreadyLoaded()) {
            Log.d(TAG, "Game already loaded in ViewModel, skipping initialization")
            return
        }
        saveLoadViewModel.resetSaveLoadState()
        Log.d(
            TAG,
            "onCreate: Game not loaded, will initialize. slot=$slot, " +
                "isNewGame=$isNewGame, isCloudSaveLoad=$isCloudSaveLoad, cloudSlot=$cloudSlot"
        )
        lifecycleScope.launch {
            VivoGCJITOptimizer.runWithJitPaused(block = {
                when {
                    // SR-3：云槽位下载优先（与 EXTRA_SLOT/EXTRA_CLOUD_SAVE_LOAD 互斥的独立入口）
                    cloudSlot >= 0 -> {
                        Log.d(TAG, "Loading cloud slot from MainActivity: slot=$cloudSlot")
                        saveLoadViewModel.loadCloudSlot(cloudSlot)
                    }
                    isCloudSaveLoad -> {
                        Log.d(TAG, "Loading cloud save from MainActivity")
                        saveLoadViewModel.loadFromCloudSave()
                    }
                    isNewGame && slot >= 0 -> {
                        Log.d(TAG, "Starting new game: sectName=$sectName, slot=$slot")
                        saveLoadViewModel.startNewGame(sectName, slot)
                    }
                    slot >= 0 -> {
                        Log.d(TAG, "Loading game from slot: $slot")
                        saveLoadViewModel.loadGameFromSlot(slot)
                    }
                    isNewGame -> {
                        Log.d(TAG, "Starting new game with default slot: sectName=$sectName")
                        saveLoadViewModel.startNewGame(sectName = sectName)
                    }
                    else -> {
                        Log.e(TAG, "Invalid game start parameters: slot=$slot, isNewGame=$isNewGame")
                        finish()
                    }
                }
            }, tag = "GameActivity_Init")
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        val currentSlot = viewModel.gameData.value?.currentSlot ?: -1
        outState.putInt(KEY_CURRENT_SLOT, currentSlot)
        Log.d(TAG, "onSaveInstanceState: currentSlot=$currentSlot")
    }

    override fun onPause() {
        frameMetricsMonitor.stopMonitoring()
        // 进入后台 → 先停游戏循环和自定义渲染器，释放 GPU 资源
        // 再通知系统暂停（super.onPause），降低 HardwareRenderer.setStopped 阻塞时间
        audioEngine.pauseBGM()
        saveLoadViewModel.pauseForBackground()
        backgroundTaskScheduler.pause()
        wakeLockManager.release()
        // Bugly #11017：finish 置位从 onStop 提前到 onPause——已创建的
        // FloatingActionMode 的 reposition/show 由系统消息队列驱动（不经 window
        // callback），onStop 时 finish 与已 post 的 show 存在竞态；onPause 即离场
        actionModeTracker?.finishActiveActionMode()
        super.onPause()
    }

    override fun onStart() {
        super.onStart()
        // 回到前台：复位销毁态，恢复文本选择 ActionMode 能力
        // （onStop 进入销毁态后若不复位，返回前台后文本选择永久失效）
        actionModeTracker?.resetForResume()
    }

    override fun onStop() {
        // 在 super.onStop() 前结束活跃的文本选择 ActionMode，防止窗口 token 失效后
        // FloatingActionMode 尝试弹出 PopupWindow 导致 BadTokenException
        actionModeTracker?.finishActiveActionMode()
        // 清除游戏窗口注册（新 Activity onResume 先于旧 Activity onStop，
        // 窗口切换期间宿主转发无缝衔接）
        complianceCallbackHost.clearGameWindow(complianceWindowPort)
        super.onStop()
        // 后台保存触发（审计 §16 #6 方案 A，旗标默认关 ⇒ 默认零行为变更）
        triggerBackgroundSaveIfEnabled()
        // pauseForBackground 已移到 onPause（保证调用），此处不再重复
        // onPause+onStop 序列中 pauseForBackground 幂等
        Log.d(TAG, "onStop: background tasks already paused in onPause")
    }

    /**
     * 退到后台时的保存触发（审计 §16 #6 方案 A + SR-4/D6 打开旗标）。
     *
     * `SaveTriggerFlag.saveOnBackground` 默认开（D6 拍板："自动存档 = 游戏月月变钩子 +
     * onStop"）；关闭态 = 回滚臂，第一行短路返回后与历史行为逐行等价（零副作用）。
     * 开启后经 [SaveLoadViewModel.saveOnBackground] 走 SR-4 编排点：不等合并窗立即落
     * 本地事务（viewModelScope 在 onStop 不取消，但进程随时可能被杀），非 LEGACY 下
     * 再追加一次上传队列排空尝试；成功静默、失败仍投递告警通道（后台不可见是既有登记）。
     */
    private fun triggerBackgroundSaveIfEnabled() {
        if (!com.xianxia.sect.data.SaveTriggerFlag.saveOnBackground) return
        val slot = viewModel.gameData.value?.currentSlot ?: -1
        val enabled = com.xianxia.sect.data.shouldAutoSave(
            flagOn = true,
            hasActiveSlot = slot >= 1,
            engineLoaded = saveLoadViewModel.isGameLoaded
        )
        if (!enabled) {
            Log.d(TAG, "onStop: 后台保存跳过（slot=$slot, loaded=${saveLoadViewModel.isGameLoaded}）")
            return
        }
        saveLoadViewModel.saveOnBackground()
        Log.i(TAG, "onStop: 已触发后台保存 slot=$slot")
    }

    override fun onResume() {
        super.onResume()
        // 回到前台立即恢复文本选择能力（onPause 提前置位后的配套复位）
        actionModeTracker?.resetForResume()
        // 注册游戏窗口（合规回调宿主转发目标；onStop 清除）
        complianceCallbackHost.registerGameWindow(complianceWindowPort)
        hideSystemBars()
        frameMetricsMonitor.startMonitoring(WindowFrameMetricsSession(window))
        if (audioConfig.musicEnabled) {
            audioEngine.resumeBGM()
        }
        backgroundTaskScheduler.resume()
        // 回到前台 → 恢复游戏循环
        saveLoadViewModel.resumeFromBackground()
        wakeLockManager.acquire()
        Log.d(TAG, "onResume: background tasks resumed, game loop restored")

        // 启动并绑定 GameForegroundService：游戏循环控制权已迁移到 Service
        // WakeLock 由 Activity onPause/onResume 管理（后台暂停时释放，前台恢复时获取）
        val startIntent = Intent(this, GameForegroundService::class.java).apply {
            action = GameForegroundService.ACTION_START
        }
        // API 26+：前台服务必须使用 startForegroundService 启动
        // 参见 Android 14 FGS 类型强制要求
        // 注意：API 31+ 可能抛出 ForegroundServiceStartNotAllowedException
        //（应用处于后台状态时），此处 try-catch 兜底
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                startForegroundService(startIntent)
            } catch (e: IllegalStateException) {
                Log.w(TAG, "startForegroundService failed (likely background start restriction)", e)
                startService(startIntent)
            }
        } else {
            startService(startIntent)
        }
        // 仅在未绑定时绑定，避免 onResume 多次调用导致重复 bind
        if (!isServiceBound) {
            bindService(
                Intent(this, GameForegroundService::class.java),
                serviceConnection,
                Context.BIND_AUTO_CREATE
            )
            isServiceBound = true
        }
        // 通知系统退出加载状态 → 恢复正常游戏性能调度
        notifyGameLoadingState(false)
        // 应用系统 Game Mode（BATTERY/PERFORMANCE 映射，服务绑定前用缓存值）
        applySystemGameMode()
        // 华为/荣耀设备：首次进入游戏时引导用户关闭电池优化
        showBatteryOptimizationGuideIfNeeded()
        // Android 12+：引导用户授予精确闹钟权限（AlarmWatchdogReceiver 兜底依赖）
        requestExactAlarmPermissionIfNeeded()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            hideSystemBars()
        }
    }

    private fun hideSystemBars() {
        // 双守卫：输入对话框冻结期间或键盘可见期间
        // 跳过窗口系统栏操作，切断"焦点抖动→hide()→insets翻转→键盘收起→
        // 焦点抖动"振荡回路的放大器环节（详见 SystemBarHidePolicy KDoc）
        if (SystemBarHidePolicy.shouldSkipHide()) {
            Log.d(TAG, "hideSystemBars 跳过（IME 守卫）: ${SystemBarHidePolicy.skipReason()}")
            return
        }
        WindowInsetsControllerCompat(window, window.decorView).let { controller ->
            controller.hide(WindowInsetsCompat.Type.statusBars() or WindowInsetsCompat.Type.navigationBars())
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        // 增强兼容性：国产 OEM ROM（HyperOS/MagicUI/ColorOS 等）对 WindowInsetsController
        // 支持不完整。使用传统 SystemUI 标志作为补充，确保状态栏在所有设备上可靠隐藏。
        if (Build.VERSION.SDK_INT < 35) {
            val decor = window.decorView
            @Suppress("DEPRECATION")
            decor.systemUiVisibility = decor.systemUiVisibility or
                (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                 View.SYSTEM_UI_FLAG_FULLSCREEN or
                 View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                 View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                 View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                 View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION)
        }
    }

    /**
     * GameForegroundService 绑定完成回调。
     *
     * Service 绑定后 gameEngineCore 已就绪，可在此执行依赖引擎实例的 UI 状态恢复。
     * 游戏循环的启动/暂停由 Service 通过 ACTION_START/ACTION_RESUME 处理，Activity 不直接调用。
     */
    private fun onGameServiceBound() {
        Log.d(TAG, "onGameServiceBound: GameForegroundService bound, gameEngineCore available")
        // 应用系统 Game Mode（BATTERY→节能 / PERFORMANCE→性能，经 ViewModel 统一状态）
        applySystemGameMode()
        // 场景状态上报（深闲置 MODE_NONE 让系统接管功耗优化 / 游玩与挂机档 MODE_GAMEPLAY）
        val core = gameEngineCore ?: return
        lifecycleScope.launch {
            core.sceneState.collect { scene -> notifyGameScene(scene) }
        }
    }

    /**
     * Activity 级全局触摸钩子（点按/按钮/切 Tab 全覆盖）：
     * 刷新引擎闲置计时，防止动态帧率误降帧。
     * Dialog 为独立窗口不触发本回调，其内部已有手动调用点。
     */
    override fun onUserInteraction() {
        super.onUserInteraction()
        gameEngineCore?.onUserActivity()
    }

    /**
     * 读取系统 Game Mode 并映射到性能模式（经 ViewModel 统一引擎/UI 状态）：
     * - BATTERY → 节能（系统省电模式优先，运行时生效不写持久化）
     * - PERFORMANCE → 性能
     * - 其余（STANDARD/未支持）→ 恢复用户设置
     *
     * ⚠️ 守卫必须为 TIRAMISU(33)：`GameManager.getGameMode()` 是 API 33 方法，
     * API 31/32 上调用抛 NoSuchMethodError（Error 子类，不被 catch Exception 捕获）。
     */
    // 防御兜底: 异常源跨IO/SDK不可枚举, 降级继续+日志留痕, 非静默吞噬
    @Suppress("TooGenericExceptionCaught", "NewApi")
    private fun applySystemGameMode() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        try {
            val gameManager = getSystemService(android.app.GameManager::class.java) ?: return
            val mapped = when (gameManager.gameMode) {
                android.app.GameManager.GAME_MODE_BATTERY -> PerformanceMode.ENERGY_SAVING
                android.app.GameManager.GAME_MODE_PERFORMANCE -> PerformanceMode.PERFORMANCE
                else -> null
            }
            viewModel.setSystemGameModeOverride(mapped)
            if (mapped != null) {
                Log.d(TAG, "System GameMode → ${mapped.displayName}")
            }
        } catch (e: Exception) {
            Log.w(TAG, "applySystemGameMode failed (non-critical): ${e.message}", e)
        }
    }

    /**
     * 上报当前游戏场景给系统（Android 13+ GameState API）：
     * - IDLE（10fps 深闲置）→ MODE_NONE 让系统接管功耗优化
     * - 其余（含 GAMEPLAY_IDLE 30fps 挂机档）→ MODE_GAMEPLAY_INTERRUPTIBLE——
     *   GAMEPLAY_IDLE 仍在渲染（用户可能盯着挂机数字），报 MODE_NONE 会被系统
     *   激进降压导致恢复抖动与热控误判
     */
    // 防御兜底: 异常源跨IO/SDK不可枚举, 降级继续+日志留痕, 非静默吞噬
    @Suppress("TooGenericExceptionCaught", "NewApi")
    private fun notifyGameScene(scene: GameEngineCore.GameScene) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        try {
            val gameManager = getSystemService(android.app.GameManager::class.java) ?: return
            val mode = if (scene == GameEngineCore.GameScene.IDLE) {
                android.app.GameState.MODE_NONE
            } else {
                android.app.GameState.MODE_GAMEPLAY_INTERRUPTIBLE
            }
            gameManager.setGameState(android.app.GameState(false, mode, gameManager.gameMode, 0))
        } catch (e: Exception) {
            Log.w(TAG, "notifyGameScene failed (non-critical): ${e.message}", e)
        }
    }

    // ── ActionMode 安全回调 ──

    /**
     * 安装 [ActionModeSafeCallback] 包装 Activity 的 [Window.Callback]，
     * 拦截 [ActionMode]（FloatingActionMode/文本选择工具栏）生命周期，
     * 确保在 Activity 销毁前结束活跃的 ActionMode，防止
     * [android.view.WindowManager.BadTokenException]。
     * 共享实现见 [com.xianxia.sect.ui.util.ActionModeSafeCallback]
     * （含创建期 stub 拦截与 onStart 复位）。
     */
    private fun installActionModeSafeCallback() {
        val original = window.callback ?: return
        if (original is ActionModeSafeCallback) return
        ActionModeSafeCallback(original, applicationContext).also {
            window.callback = it
            actionModeTracker = it
        }
    }

    @Suppress("TooGenericExceptionCaught") // 防御兜底: 异常源跨IO/SDK不可枚举, 降级继续+日志留痕, 非静默吞噬
    override fun onDestroy() {
        SystemBarFreezeScope.removeOnUnfreezeListener(systemBarRestoreListener)
        // 注销动画结束监听 + 解除 IME 窗口跟踪（detach 对称防全局状态残留）
        ImeAnimationTracker.removeOnAnimationEndedListener(systemBarRestoreListener)
        ImeAnimationTracker.detach(window)
        ImeVisibilityTracker.detach(window)
        mainHandler.removeCallbacksAndMessages(null)
        actionModeTracker?.finishActiveActionMode()
        actionModeTracker = null
        super.onDestroy()
        Log.d(TAG, "onDestroy called")
        if (::adServiceImpl.isInitialized) adServiceImpl.detachActivity()
        com.xianxia.sect.taptap.RewardVideoAdManager.destroyAd()
        frameMetricsMonitor.stopMonitoring()
        SecureKeyManager.recoveryCallback = null
        // 解除与 GameForegroundService 的绑定
        if (isServiceBound) {
            try {
                unbindService(serviceConnection)
            } catch (e: Exception) {
                Log.w(TAG, "unbindService failed: ${e.message}")
            }
            isServiceBound = false
        }
        // 用户主动退出（isFinishing=true）时停止 Service，释放游戏循环与 WakeLock
        // 配置变更等非主动退出场景不停止 Service，保持游戏在后台运行
        if (isFinishing) {
            // ImplicitSamInstance 为 lint 误报：component-based Intent 是 stopService 标准写法
            @SuppressLint("ImplicitSamInstance")
            val stopIntent = Intent(this, GameForegroundService::class.java)
            stopService(stopIntent)
        }
        // 注意：不在此处调用 gameEngineCore.shutdown()
        // shutdown 会取消协程作用域和释放系统。
        // GameEngineCore 是 @Singleton，其生命周期绑定到应用进程，由 Application 统一管理。
        // ViewModel.onCleared() 中会调用 stopGameLoopAndWait() 来停止游戏循环。
    }

    override fun onLowMemory() {
        super.onLowMemory()
    }

    @Suppress("DEPRECATION")
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        when (level) {
            ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN -> {
                // Release UI-only resources
            }
            ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL,
            ComponentCallbacks2.TRIM_MEMORY_COMPLETE -> {
                // 释放地图 Bitmap 引用以允许 GC 回收内存（ImageBitmap 无 recycle API）
                mapPreloadDataRef = null
                // 丢弃未消费的图集预取缓存（21MB 级）——
                // 已被上传路径消费时为空操作；未消费时丢弃后由 surface 期重新读取
                com.xianxia.sect.ui.game.sect.SectAtlasPrefetch.clear()
            }
            ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW,
            ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE,
            ComponentCallbacks2.TRIM_MEMORY_BACKGROUND -> {
                Log.w(TAG, "运行时内存压力(level=$level)")
            }
            ComponentCallbacks2.TRIM_MEMORY_MODERATE -> {
                Log.w(TAG, "内存适中压力，建议释放部分资源")
            }
        }
    }

    /**
     * 设置崩溃处理器
     */
    @Suppress("TooGenericExceptionCaught") // 防御兜底: 异常源跨IO/SDK不可枚举, 降级继续+日志留痕, 非静默吞噬
    private fun setupCrashHandler() {
        try {
            CrashHandler.init(crashHandler)
            crashHandler.register()
            Log.i(TAG, "CrashHandler setup completed")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to setup CrashHandler", e)
        }
    }

    // ── GameState API (Android 13+) ──

    /**
     * 通知系统当前游戏加载状态。
     *
     * Android 13+ GameState API — 系统根据游戏状态调整 CPU 调度：
     * - isLoading=true  → GAME_LOADING 模式，主动提升 CPU 频率
     * - isLoading=false → 维持正常游戏性能调度
     *
     * 参考：https://developer.android.com/about/versions/13/features#game-performance
     */
    // 防御兜底: 异常源跨IO/SDK不可枚举, 降级继续+日志留痕, 非静默吞噬
    @Suppress("TooGenericExceptionCaught", "NewApi")
    private fun notifyGameLoadingState(isLoading: Boolean) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        try {
            val gameManager = getSystemService(android.app.GameManager::class.java) ?: return
            // GameState(isLoading, mode, gameMode, label)
            val gameState = android.app.GameState(
                isLoading,
                android.app.GameState.MODE_NONE,
                gameManager.gameMode,
                0
            )
            gameManager.setGameState(gameState)
            Log.d(TAG, "GameState → loading=$isLoading")
        } catch (e: Exception) {
            Log.w(TAG, "setGameState failed (non-critical): ${e.message}")
        }
    }

    // ── 电池优化引导 ──

    /**
     * 首次进入游戏时引导用户关闭电池优化。
     *
     * 由 [BatteryOptimizationHelper.shouldShowGuide] 数据驱动，
     * 覆盖全部激进 OEM（华为/荣耀/vivo/iQOO/小米/OPPO）。
     */
    private fun showBatteryOptimizationGuideIfNeeded() {
        val helper = com.xianxia.sect.core.util.BatteryOptimizationHelper
        if (!helper.shouldShowGuide(this)) return

        // 偏好统一迁入 MMKV（键名不变，旧 SharedPreferences 一次性迁移）
        gamePreferences.migrateFromSharedPreferences("battery_guide")
        if (gamePreferences.getBoolean("oem_guide_shown", false)) return

        gamePreferences.putBoolean("oem_guide_shown", true)

        val guideText = helper.getGuideText(this)
        if (guideText.isEmpty()) return

        // 在 UI 线程显示引导
        lifecycleScope.launch(Dispatchers.Main) {
            android.widget.Toast.makeText(
                this@GameActivity,
                guideText,
                android.widget.Toast.LENGTH_LONG
            ).show()
            // 直接请求电池优化豁免
            helper.requestExemption(this@GameActivity)
        }
    }

    // ── 精确闹钟权限引导 ──

    /**
     * 引导用户授予 SCHEDULE_EXACT_ALARM 权限（Android 12+）。
     *
     * AlarmWatchdogReceiver 依赖 [AlarmManager.setExactAndAllowWhileIdle] 在
     * OEM 省电策略冻结游戏循环时兜底唤醒。Android 12+ 默认不授予该权限，
     * 需引导用户到系统设置授权。
     *
     * 使用 SharedPreferences 记录是否已询问过，避免每次 onResume 都跳转。
     */
    @Suppress("TooGenericExceptionCaught") // 防御兜底: 异常源跨IO/SDK不可枚举, 降级继续+日志留痕, 非静默吞噬
    private fun requestExactAlarmPermissionIfNeeded() {
        // 仅 Android 12+ (API 31, S) 需要请求精确闹钟权限
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return

        val alarmManager = getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        if (alarmManager.canScheduleExactAlarms()) return

        // 偏好统一迁入 MMKV（键名不变，旧 SharedPreferences 一次性迁移）
        gamePreferences.migrateFromSharedPreferences("exact_alarm_prefs")
        if (gamePreferences.getBoolean("exact_alarm_prompted", false)) return

        gamePreferences.putBoolean("exact_alarm_prompted", true)

        try {
            val intent = Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
                .setData("package:$packageName".toUri())
            startActivity(intent)
            Log.d(TAG, "Requesting SCHEDULE_EXACT_ALARM permission")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to request exact alarm permission: ${e.message}")
        }
    }
}

/**
 * 构造返回主菜单的 Intent。
 * 独立顶层函数供单元测试（GameActivity 为 Hilt 入口不便实例化）。
 * 复用 onLogout 的 MainActivity 重建模式：清 Activity 栈但不影响 session。
 *
 * @param context 启动上下文（Activity）
 * @return 带 NEW_TASK|CLEAR_TASK flags 的 MainActivity Intent
 */
@VisibleForTesting
internal fun buildMainMenuIntent(context: Context): Intent {
    return Intent(context, MainActivity::class.java).apply {
        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
    }
}
