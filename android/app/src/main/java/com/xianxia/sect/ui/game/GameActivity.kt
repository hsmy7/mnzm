package com.xianxia.sect.ui.game

import android.annotation.SuppressLint
import android.app.AlarmManager
import android.content.ComponentCallbacks2
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.Uri
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.activity.enableEdgeToEdge
import androidx.core.content.edit
import androidx.core.graphics.drawable.toDrawable
import androidx.core.net.toUri
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import com.xianxia.sect.R
import com.xianxia.sect.XianxiaApplication
import com.xianxia.sect.core.CrashHandler
import com.xianxia.sect.core.CrashRecoveryEngine
import com.xianxia.sect.core.VulkanPolicy
import com.xianxia.sect.core.engine.GameEngineCore
import com.xianxia.sect.core.util.GameForegroundService
import com.xianxia.sect.core.model.MapPreloadData
import com.xianxia.sect.core.state.BootPhase
import com.xianxia.sect.core.state.RunState
import com.xianxia.sect.core.util.VivoGCJITOptimizer
import com.xianxia.sect.core.perf.FrameMetricsMonitor
import com.xianxia.sect.data.crypto.SecureKeyManager
import com.xianxia.sect.data.crypto.UiKeyRecoveryCallback
import com.xianxia.sect.data.facade.StorageFacade
import com.xianxia.sect.data.SessionManager
import com.xianxia.sect.ui.MainActivity
import com.xianxia.sect.ui.components.GameButton
import com.xianxia.sect.ui.components.StandardPromptDialog
import com.xianxia.sect.ui.game.sect.NativeSurfaceView
import com.xianxia.sect.ui.theme.XianxiaTheme
import androidx.compose.runtime.CompositionLocalProvider
import com.xianxia.sect.core.audio.AudioConfig
import com.xianxia.sect.core.audio.AudioEngine
import com.xianxia.sect.ui.components.LocalPlayClickSound
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.TimeoutCancellationException
import com.xianxia.sect.core.AdFreeWhitelist
import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.taptap.AdServiceImpl
import com.xianxia.sect.core.nativebridge.NativeBridge
import com.xianxia.sect.core.engine.di.IoDispatcher
import kotlinx.coroutines.CoroutineDispatcher
import android.view.ActionMode
import android.view.View
import android.view.Window
import javax.inject.Inject

@AndroidEntryPoint
class GameActivity : ComponentActivity() {

    companion object {
        private const val TAG = "GameActivity"
        private const val KEY_CURRENT_SLOT = "current_slot"
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
    lateinit var audioEngine: AudioEngine

    // ── GameForegroundService 绑定 ──
    // 游戏循环控制权已迁移到 GameForegroundService，Activity 通过 Binder 获取 GameEngineCore 实例
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
     * ActionMode 跟踪器：拦截 FloatingActionMode 生命周期，确保在 Activity 销毁前清理。
     * 防止文本选择工具栏在窗口 token 无效后弹出导致 BadTokenException。
     */
    private var actionModeTracker: ActionModeSafeCallback? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        // ── 渲染安全模式检测 ──
        // 在 super.onCreate() 前切换主题，使 hardwareAccelerated 生效
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
        super.onCreate(savedInstanceState)
        Log.d(TAG, "onCreate started, savedInstanceState=$savedInstanceState")

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

        // ★ 渲染策略决策：模拟器直接走软件渲染，正常设备 Vulkan（带降级回退）
        val renderStrategy = VulkanPolicy.getRenderStrategy(this)
        val isSoftwareRendering = renderStrategy == VulkanPolicy.RenderStrategy.SOFTWARE_ONLY
        Log.i(TAG, "Render strategy: ${renderStrategy.description}")

        SecureKeyManager.recoveryCallback = UiKeyRecoveryCallback { this@GameActivity }

        enableEdgeToEdge()
        hideSystemBars()

        val savedSlot = savedInstanceState?.getInt(KEY_CURRENT_SLOT, -1) ?: -1
        val intentSlot = intent.getIntExtra(MainActivity.EXTRA_SLOT, -1)
        val isNewGame = intent.getBooleanExtra(MainActivity.EXTRA_NEW_GAME, false)
        val sectName = intent.getStringExtra(MainActivity.EXTRA_SECT_NAME) ?: "青云宗"
        val isCloudSaveLoad = intent.getBooleanExtra(MainActivity.EXTRA_CLOUD_SAVE_LOAD, false)
        
        val slot = if (savedSlot >= 0) savedSlot else intentSlot
        
        Log.d(TAG, "Slot info: savedSlot=$savedSlot, intentSlot=$intentSlot, finalSlot=$slot, isNewGame=$isNewGame, sectName=$sectName")
        Log.d(TAG, "ViewModel game loaded: ${saveLoadViewModel.isGameAlreadyLoaded()}")

        setContent {
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
                    val limitAdTrackingState = remember { mutableStateOf(sessionManager.limitAdTracking) }

                    // 贴图加载在 LaunchedEffect 中完成，但 MainGameScreen 只在加载完成后才进入组合树
                    // 从根本上杜绝 "LoadingScreen 消失但贴图未就绪" 的中间帧
                    var mapPreloadData by remember { mutableStateOf<MapPreloadData?>(null) }
                    val bootPhase by saveLoadViewModel.bootPhase.collectAsStateWithLifecycle()
                    val runState by saveLoadViewModel.runState.collectAsStateWithLifecycle()

                    // 游戏生命周期驱动 UI 过渡（替代旧的 gameData.isGameStarted 方案）
                    // MAP_READY → 地图瓦片就绪，安全切换 Crossfade 到 MainGameScreen
                    // PLAYING   → 游戏 fully loaded，触发 TapDB 上报和 Vulkan 预热
                    LaunchedEffect(bootPhase, runState) {
                        when {
                            bootPhase >= BootPhase.MAP_READY && mapPreloadData == null -> {
                                // 从 ViewModel 获取已预生成的地图瓦片数据
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

                                // Vulkan 预热：后台发射，不阻塞地图显示
                                if (!isSoftwareRendering) {
                                    val tileSize = GameConfig.SectMap.TILE_SIZE
                                    val worldWidthCells = GameConfig.SectMap.WORLD_WIDTH_CELLS
                                    val worldHeightCells = GameConfig.SectMap.WORLD_HEIGHT_CELLS
                                    val worldPixelWidth = worldWidthCells * tileSize
                                    val worldPixelHeight = worldHeightCells * tileSize

                                    launch(ioDispatcher.dispatcher) {
                                        NativeBridge.ensureLoaded()
                                        var prewarmOk = false
                                        com.xianxia.sect.core.CrashRecoveryEngine.markPrewarmStarted()
                                        try {
                                            withTimeout(5_000L) {
                                                val d = applicationContext.cacheDir
                                                prewarmOk = NativeBridge.prewarmDevice(
                                                    d.absolutePath, worldPixelWidth, worldPixelHeight, tileSize
                                                )
                                                if (prewarmOk) {
                                                    com.xianxia.sect.core.CrashRecoveryEngine.clearVulkanInitFailure()
                                                    com.xianxia.sect.core.CrashRecoveryEngine.clearPrewarmStarted()
                                                    com.xianxia.sect.core.VulkanPolicy.setDriverVersion(
                                                        com.xianxia.sect.core.nativebridge
                                                            .NativeBridge.getVulkanDriverVersion()
                                                    )
                                                } else {
                                                    com.xianxia.sect.core.CrashRecoveryEngine.clearPrewarmStarted()
                                                    com.xianxia.sect.core.CrashRecoveryEngine.recordVulkanInitFailure()
                                                }
                                            }
                                        } catch (e: TimeoutCancellationException) {
                                            Log.w(TAG, "prewarmDevice timed out after 5s, will init at surface time", e)
                                            com.xianxia.sect.core.CrashRecoveryEngine.clearPrewarmStarted()
                                        } catch (e: CancellationException) { throw e }
                                          catch (e: Exception) {
                                            Log.e(TAG, "Vulkan prewarm exception", e)
                                            com.xianxia.sect.core.CrashRecoveryEngine.clearPrewarmStarted()
                                            com.xianxia.sect.core.CrashRecoveryEngine.recordVulkanInitFailure()
                                        }

                                        if (!prewarmOk) {
                                            com.xianxia.sect.core.CrashRecoveryEngine.clearPrewarmStarted()
                                            com.xianxia.sect.core.CrashRecoveryEngine.recordVulkanInitFailure()
                                        }
                                    }
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

                    Crossfade(
                        targetState = mapPreloadData != null,
                        animationSpec = tween(durationMillis = 400),
                        label = "loadingToGameTransition"
                    ) { showGame ->
                        val preloadData = mapPreloadData
                        if (showGame && preloadData != null) {
                            // 初始化免广告特权白名单
                            AdFreeWhitelist.initialize(sessionManager.unionId)

                            // 注入 Activity 引用到广告服务实现
                            adServiceImpl.attachActivity(this@GameActivity)

                            MainGameScreen(
                                mapPreloadData = preloadData,
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
                                    val intent = Intent(this@GameActivity, MainActivity::class.java)
                                    intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                                    startActivity(intent)
                                    finish()
                                },
                                onRestartGame = {
                                    saveLoadViewModel.restartGame()
                                },
                                limitAdTracking = limitAdTrackingState.value,
                                onLimitAdTrackingChanged = { enabled ->
                                    sessionManager.limitAdTracking = enabled
                                    limitAdTrackingState.value = enabled
                                    android.widget.Toast.makeText(
                                        this@GameActivity,
                                        if (enabled) "已开启限制广告追踪，下次启动后生效" else "已关闭限制广告追踪，下次启动后生效",
                                        android.widget.Toast.LENGTH_SHORT
                                    ).show()
                                },
                                forceSoftwareRendering = isSoftwareRendering,
                                vulkanInitListener = object : NativeSurfaceView.VulkanInitListener {
                                    override fun onSurfaceInitStarted() {
                                        com.xianxia.sect.core.CrashRecoveryEngine.markSurfaceInitStarted()
                                    }

                                    override fun onSurfaceInitSucceeded() {
                                        com.xianxia.sect.core.CrashRecoveryEngine.clearSurfaceInitStarted()
                                        com.xianxia.sect.core.CrashRecoveryEngine.clearVulkanInitFailure()
                                    }

                                    override fun onSurfaceInitFailed() {
                                        com.xianxia.sect.core.CrashRecoveryEngine.clearSurfaceInitStarted()
                                        com.xianxia.sect.core.CrashRecoveryEngine.recordVulkanInitFailure()
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

                    errorMessage?.let { error ->
                        StandardPromptDialog(
                            onDismissRequest = { errorMessage = null },
                            title = "提示",
                            text = error,
                            confirmLabel = "确定"
                        )
                    }
                }
            }
            }
        }

        if (!saveLoadViewModel.isGameAlreadyLoaded()) {
            saveLoadViewModel.resetSaveLoadState()
            Log.d(
                TAG,
                "onCreate: Game not loaded, will initialize. slot=$slot, " +
                    "isNewGame=$isNewGame, isCloudSaveLoad=$isCloudSaveLoad"
            )
            lifecycleScope.launch {
                VivoGCJITOptimizer.runWithJitPaused(block = {
                    when {
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
        } else {
            Log.d(TAG, "Game already loaded in ViewModel, skipping initialization")
        }

        Log.d(TAG, "onCreate completed")
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        val currentSlot = viewModel.gameData.value?.currentSlot ?: -1
        outState.putInt(KEY_CURRENT_SLOT, currentSlot)
        Log.d(TAG, "onSaveInstanceState: currentSlot=$currentSlot")
    }

    override fun onPause() {
        frameMetricsMonitor.stopMonitoring(window)
        // ★ 进入后台 → 先停游戏循环和自定义渲染器，释放 GPU 资源
        // 再通知系统暂停（super.onPause），降低 HardwareRenderer.setStopped 阻塞时间
        audioEngine.pauseBGM()
        saveLoadViewModel.pauseForBackground()
        backgroundTaskScheduler.pause()
        wakeLockManager.release()
        super.onPause()
    }

    override fun onStop() {
        // 在 super.onStop() 前结束活跃的文本选择 ActionMode，防止窗口 token 失效后
        // FloatingActionMode 尝试弹出 PopupWindow 导致 BadTokenException
        actionModeTracker?.finishActiveActionMode()
        super.onStop()
        // pauseForBackground 已移到 onPause（保证调用），此处不再重复
        // onPause+onStop 序列中 pauseForBackground 幂等
        Log.d(TAG, "onStop: background tasks already paused in onPause")
    }

    override fun onResume() {
        super.onResume()
        hideSystemBars()
        frameMetricsMonitor.startMonitoring(window)
        if (audioConfig.musicEnabled) {
            audioEngine.resumeBGM()
        }
        backgroundTaskScheduler.resume()
        // ★ 回到前台 → 恢复游戏循环
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
    }

    // ── ActionMode 安全回调 ──

    /**
     * 安装 [ActionModeSafeCallback] 包装 Activity 的 [Window.Callback]，
     * 拦截 [ActionMode]（FloatingActionMode/文本选择工具栏）生命周期，
     * 确保在 Activity 销毁前结束活跃的 ActionMode，防止
     * [android.view.WindowManager.BadTokenException]。
     */
    private fun installActionModeSafeCallback() {
        val original = window.callback ?: return
        if (original is ActionModeSafeCallback) return
        ActionModeSafeCallback(original).also {
            window.callback = it
            actionModeTracker = it
        }
    }

    /**
     * 安全的 [Window.Callback] 包装器，跟踪当前活跃的 [ActionMode]。
     *
     * 当 Activity 开始销毁时，通过 [finishActiveActionMode] 提前结束文本选择
     * ActionMode，并标记 [isTearingDown]。销毁过程中若系统尝试创建新的
     * ActionMode（如视图销毁触发的文本选择回调），立即将其 finish，
     * 阻止 FloatingActionMode 弹出 PopupWindow 时抛出 BadTokenException。
     */
    private class ActionModeSafeCallback(
        private val delegate: Window.Callback
    ) : Window.Callback by delegate {

        @Volatile
        var activeActionMode: ActionMode? = null
            private set

        @Volatile
        var isTearingDown: Boolean = false
            private set

        override fun onActionModeStarted(mode: ActionMode) {
            if (isTearingDown) {
                // 窗口正在销毁，立即结束新创建的 ActionMode 防止崩溃
                try {
                    mode.finish()
                } catch (_: Exception) {
                    // 静默 — 尽力而为的清理
                }
                return
            }
            activeActionMode = mode
            delegate.onActionModeStarted(mode)
        }

        override fun onActionModeFinished(mode: ActionMode) {
            if (activeActionMode === mode) {
                activeActionMode = null
            }
            delegate.onActionModeFinished(mode)
        }

        fun finishActiveActionMode() {
            activeActionMode?.let { mode ->
                isTearingDown = true
                try {
                    mode.finish()
                } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
                    Log.w(TAG, "finishActiveActionMode failed: ${e.message}")
                }
                activeActionMode = null
            }
        }
    }

    override fun onDestroy() {
        actionModeTracker?.finishActiveActionMode()
        actionModeTracker = null
        super.onDestroy()
        Log.d(TAG, "onDestroy called")
        if (::adServiceImpl.isInitialized) adServiceImpl.detachActivity()
        com.xianxia.sect.taptap.RewardVideoAdManager.destroyAd()
        frameMetricsMonitor.stopMonitoring(window)
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
    @Suppress("NewApi")
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

        // 使用 SharedPreferences 记录是否已提示过，避免每次 resume 都弹
        val prefs = getSharedPreferences("battery_guide", MODE_PRIVATE)
        if (prefs.getBoolean("oem_guide_shown", false)) return

        prefs.edit { putBoolean("oem_guide_shown", true) }

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
    private fun requestExactAlarmPermissionIfNeeded() {
        // 仅 Android 12+ (API 31, S) 需要请求精确闹钟权限
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return

        val alarmManager = getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        if (alarmManager.canScheduleExactAlarms()) return

        // 使用独立 SharedPreferences 记录是否已询问过精确闹钟权限
        val prefs = getSharedPreferences("exact_alarm_prefs", MODE_PRIVATE)
        if (prefs.getBoolean("exact_alarm_prompted", false)) return

        prefs.edit { putBoolean("exact_alarm_prompted", true) }

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
