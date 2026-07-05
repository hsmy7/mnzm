package com.xianxia.sect.ui.game

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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.activity.enableEdgeToEdge
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import androidx.compose.ui.graphics.asImageBitmap
import com.xianxia.sect.R
import com.xianxia.sect.XianxiaApplication
import com.xianxia.sect.core.CrashHandler
import com.xianxia.sect.core.CrashRecoveryEngine
import com.xianxia.sect.core.VulkanPolicy
import com.xianxia.sect.core.engine.GameEngineCore
import com.xianxia.sect.core.util.GameForegroundService
import com.xianxia.sect.core.model.MapPreloadData
import com.xianxia.sect.core.util.SectMapTileGenerator
import com.xianxia.sect.core.util.VivoGCJITOptimizer
import com.xianxia.sect.core.perf.FrameMetricsMonitor
import com.xianxia.sect.data.crypto.SecureKeyManager
import com.xianxia.sect.data.crypto.UiKeyRecoveryCallback
import com.xianxia.sect.data.facade.StorageFacade
import com.xianxia.sect.data.SessionManager
import com.xianxia.sect.ui.MainActivity
import com.xianxia.sect.ui.components.GameButton
import com.xianxia.sect.ui.components.StandardPromptDialog
import com.xianxia.sect.ui.theme.XianxiaTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.perf.GpuTierDetector
import com.xianxia.sect.core.perf.GpuRenderConfig
import android.view.ActionMode
import android.view.Window
import kotlin.random.Random
import javax.inject.Inject

@AndroidEntryPoint
class GameActivity : ComponentActivity() {

    companion object {
        private const val TAG = "GameActivity"
        private const val KEY_CURRENT_SLOT = "current_slot"

        /** 单张 Bitmap 内存分配硬上限 (50 MB)，防止异常大图导致 OOM */
        private const val MAX_BITMAP_ALLOCATION_BYTES = 50 * 1024 * 1024

        /** 解码失败回退的地图瓦片颜色 */
        private const val FALLBACK_TILE_COLOR = 0xFFF2EDE4.toInt()
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
        }
    }

    // GPU 分级检测 — 在 Activity 级别缓存，供地图预渲染使用
    // 来源: docs/device-adaptation-plan.md §5 Step 5 — 优先使用 GameManager API
    private val gpuRenderConfig: GpuRenderConfig by lazy { GpuRenderConfig.forTier(GpuTierDetector().detect(this)) }

    // 持有地图预加载数据引用，供 onTrimMemory 中释放 Bitmap 使用
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

        // 初始化并注册崩溃处理器
        setupCrashHandler()

        // 拦截和管理 ActionMode 生命周期，防止 FloatingActionMode（文本选择工具栏）
        // 在 Activity 销毁时弹出 PopupWindow 导致 BadTokenException
        installActionModeSafeCallback()

        // 记录设备诊断信息到日志（供 Bugly / 崩溃分析使用）
        VulkanPolicy.logDeviceDiagnostics(this)
        // 标记本次为干净启动，重置连续崩溃计数器
        CrashRecoveryEngine.onCleanLaunch()

        SecureKeyManager.recoveryCallback = UiKeyRecoveryCallback { this@GameActivity }

        enableEdgeToEdge()
        hideSystemBars()

        val savedSlot = savedInstanceState?.getInt(KEY_CURRENT_SLOT, -1) ?: -1
        val intentSlot = intent.getIntExtra(MainActivity.EXTRA_SLOT, -1)
        val isNewGame = intent.getBooleanExtra(MainActivity.EXTRA_NEW_GAME, false)
        val sectName = intent.getStringExtra(MainActivity.EXTRA_SECT_NAME) ?: "青云宗"
        
        val slot = if (savedSlot >= 0) savedSlot else intentSlot
        
        Log.d(TAG, "Slot info: savedSlot=$savedSlot, intentSlot=$intentSlot, finalSlot=$slot, isNewGame=$isNewGame, sectName=$sectName")
        Log.d(TAG, "ViewModel game loaded: ${saveLoadViewModel.isGameAlreadyLoaded()}")

        setContent {
            XianxiaTheme {
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

                    LaunchedEffect(gameData.isGameStarted) {
                        if (gameData.isGameStarted && mapPreloadData == null) {
                            saveLoadViewModel.setLoadingProgress(SaveLoadViewModel.PROGRESS_MAP_PRELOAD)

                            val tileSize = GameConfig.SectMap.TILE_SIZE
                            val worldWidthCells = GameConfig.SectMap.WORLD_WIDTH_CELLS
                            val worldHeightCells = GameConfig.SectMap.WORLD_HEIGHT_CELLS
                            val worldPixelWidth = worldWidthCells * tileSize
                            val worldPixelHeight = worldHeightCells * tileSize

                            val result = withContext(Dispatchers.IO) {
                                val renderConfig = gpuRenderConfig

                                // 渲染分辨率低于世界分辨率，画的时候拉伸
                                // 来源: docs/gpu-tier-fairness-plan.md §3 — GPU 分级只影响渲染质量，不改变游戏世界尺寸
                                val renderScale = renderConfig.mapResolution.toFloat() / GameConfig.SectMap.WORLD_WIDTH_CELLS.toFloat()
                                val renderWidth = (worldPixelWidth * renderScale).toInt()
                                val renderHeight = (worldPixelHeight * renderScale).toInt()

                                // 来源: docs/device-adaptation-plan.md §4 — textureLodOffset 控制贴图质量
                                // +1 = 更模糊(省显存), 0 = 默认, -1 = 更清晰
                                val lodMultiplier = when (renderConfig.textureLodOffset) {
                                    -1 -> 1   // ULTRA: 高质量 (inSampleSize 减半)
                                    0 -> 2    // HIGH/MEDIUM: 当前质量
                                    1 -> 4    // LOW: 低质量 (inSampleSize 翻倍)
                                    else -> 2
                                }
                                val groundSampleSize = lodMultiplier
                                val decorationSampleSize = lodMultiplier * 2

                                // 1. 加载地图单格纹理，平铺合成地面位图
                                val mapTileSrc = try {
                                    val opts = android.graphics.BitmapFactory.Options().apply { inSampleSize = groundSampleSize }
                                    android.graphics.BitmapFactory.decodeResource(
                                        resources, R.drawable.map_tile, opts
                                    )
                                } catch (e: kotlinx.coroutines.CancellationException) { throw e }
                                catch (e: Exception) {
                                    Log.w(TAG, "Failed to load map_tile", e); null
                                }

                                // 地面位图：将 mapTile 平铺到渲染分辨率
                                val groundBmp = if (mapTileSrc != null) {
                                    val bmpConfig = if (renderConfig.useArgb8888)
                                        android.graphics.Bitmap.Config.ARGB_8888
                                    else
                                        android.graphics.Bitmap.Config.RGB_565
                                    val estBytes = renderWidth.toLong() * renderHeight * (if (bmpConfig == android.graphics.Bitmap.Config.ARGB_8888) 4 else 2)
                                    if (estBytes > MAX_BITMAP_ALLOCATION_BYTES) {
                                        Log.e(TAG, "groundBmp too large: ${renderWidth}x$renderHeight est=${estBytes}bytes > max=$MAX_BITMAP_ALLOCATION_BYTES")
                                        null
                                    } else {
                                        android.graphics.Bitmap.createBitmap(renderWidth, renderHeight, bmpConfig).apply {
                                            val c = android.graphics.Canvas(this)
                                            val tileW = mapTileSrc.width
                                            val tileH = mapTileSrc.height
                                            var sy = 0
                                            while (sy < renderHeight) {
                                                var sx = 0
                                                while (sx < renderWidth) {
                                                    c.drawBitmap(mapTileSrc, sx.toFloat(), sy.toFloat(), null)
                                                    sx += tileW
                                                }
                                                sy += tileH
                                            }
                                        }
                                    }
                                } else null

                                // 2. 加载 3 种草装饰精灵图
                                val grassSmallBmp = try {
                                    val opts = android.graphics.BitmapFactory.Options().apply { inSampleSize = decorationSampleSize }
                                    android.graphics.BitmapFactory.decodeResource(resources, R.drawable.decoration_grass_small, opts)
                                } catch (e: kotlinx.coroutines.CancellationException) { throw e }
                                catch (e: Exception) {
                                    Log.w(TAG, "Failed to load grass_small", e); null
                                }
                                val grassMediumBmp = try {
                                    val opts = android.graphics.BitmapFactory.Options().apply {
                                        inSampleSize = decorationSampleSize
                                    }
                                    android.graphics.BitmapFactory.decodeResource(
                                        resources, R.drawable.decoration_grass_medium, opts
                                    )
                                } catch (e: kotlinx.coroutines.CancellationException) { throw e }
                                catch (e: Exception) {
                                    Log.w(TAG, "Failed to load grass_medium", e); null
                                }
                                val grassLargeBmp = try {
                                    val opts = android.graphics.BitmapFactory.Options().apply {
                                        inSampleSize = decorationSampleSize
                                    }
                                    android.graphics.BitmapFactory.decodeResource(
                                        resources, R.drawable.decoration_grass_large, opts
                                    )
                                } catch (e: kotlinx.coroutines.CancellationException) { throw e }
                                catch (e: Exception) {
                                    Log.w(TAG, "Failed to load grass_large", e); null
                                }

                                // 3. 加载 2 种树装饰精灵图
                                val tree1Bmp = try {
                                    val opts = android.graphics.BitmapFactory.Options().apply {
                                        inSampleSize = decorationSampleSize
                                    }
                                    android.graphics.BitmapFactory.decodeResource(
                                        resources, R.drawable.decoration_tree1, opts
                                    )
                                } catch (e: kotlinx.coroutines.CancellationException) { throw e }
                                catch (e: Exception) {
                                    Log.w(TAG, "Failed to load tree1", e); null
                                }
                                val tree2Bmp = if (renderConfig.showTrees) {
                                    try {
                                        val opts = android.graphics.BitmapFactory.Options().apply {
                                            inSampleSize = decorationSampleSize
                                        }
                                        android.graphics.BitmapFactory.decodeResource(
                                            resources, R.drawable.decoration_tree2, opts
                                        )
                                    } catch (e: kotlinx.coroutines.CancellationException) { throw e }
                                    catch (e: Exception) {
                                        Log.w(TAG, "Failed to load tree2", e); null
                                    }
                                } else null

                                // 4. 瓦片数据生成（SectMapTileGenerator）
                                val rawTileData = SectMapTileGenerator.generateTileData(
                                    worldWidthCells, worldHeightCells
                                )

                                if (groundBmp != null && grassSmallBmp != null && mapTileSrc != null) {
                                    val grassSmallImg = grassSmallBmp.asImageBitmap()
                                    groundBmp.prepareToDraw()
                                    grassSmallBmp.prepareToDraw()
                                    grassMediumBmp?.prepareToDraw()
                                    grassLargeBmp?.prepareToDraw()
                                    tree1Bmp?.prepareToDraw()
                                    tree2Bmp?.prepareToDraw()
                                    mapTileSrc.prepareToDraw()

                                    MapPreloadData(
                                        mapTileBmp = mapTileSrc.asImageBitmap(),
                                        groundTileBmp = groundBmp.asImageBitmap(),
                                        grassDecBitmaps = listOf(
                                            grassSmallImg,
                                            grassMediumBmp?.asImageBitmap() ?: grassSmallImg,
                                            grassLargeBmp?.asImageBitmap() ?: grassSmallImg
                                        ),
                                        treeDecBitmaps = listOf(
                                            tree1Bmp?.asImageBitmap() ?: grassSmallImg,
                                            tree2Bmp?.asImageBitmap() ?: grassSmallImg
                                        ),
                                        rawTileData = rawTileData,
                                        worldWidthCells = worldWidthCells,
                                        worldHeightCells = worldHeightCells,
                                        tileSize = tileSize,
                                        worldPixelWidth = worldPixelWidth,
                                        worldPixelHeight = worldPixelHeight,
                                        renderWidth = renderWidth,
                                        renderHeight = renderHeight
                                    )
                                } else {
                                    Log.w(TAG, "Map tile decode failed, using fallback")
                                    val fallbackBmp = android.graphics.Bitmap.createBitmap(
                                        tileSize, tileSize,
                                        android.graphics.Bitmap.Config.ARGB_8888
                                    ).also { it.eraseColor(FALLBACK_TILE_COLOR) }
                                    val fallbackImg = fallbackBmp.asImageBitmap()
                                    MapPreloadData(
                                        mapTileBmp = fallbackImg,
                                        groundTileBmp = fallbackImg,
                                        grassDecBitmaps = listOf(
                                            fallbackImg, fallbackImg, fallbackImg
                                        ),
                                        treeDecBitmaps = listOf(fallbackImg, fallbackImg),
                                        rawTileData = SectMapTileGenerator.generateTileData(
                                            worldWidthCells, worldHeightCells
                                        ),
                                        worldWidthCells = worldWidthCells,
                                        worldHeightCells = worldHeightCells,
                                        tileSize = tileSize,
                                        worldPixelWidth = worldPixelWidth,
                                        worldPixelHeight = worldPixelHeight,
                                        renderWidth = renderWidth,
                                        renderHeight = renderHeight
                                    )
                                }
                            }

                            if (!viewModel.gameData.value.isGameStarted) return@LaunchedEffect
                            mapPreloadData = result
                            mapPreloadDataRef = result  // 同步到类级引用，供 onTrimMemory 使用
                            saveLoadViewModel.setLoadingProgress(1.0f)
                            com.xianxia.sect.taptap.TapDBManager.setLevel(gameData.gameYear)
                            com.xianxia.sect.taptap.TapDBManager.setServer(gameData.sectName)
                            com.xianxia.sect.taptap.TapDBManager.trackEvent(
                                "game_start",
                                mapOf(
                                    "sect_name" to gameData.sectName,
                                    "game_version" to com.xianxia.sect.BuildConfig.VERSION_NAME
                                )
                            )
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

                    Box(modifier = Modifier.fillMaxSize()) {
                        // 只在贴图加载完成后才将 MainGameScreen 加入组合树
                        // 这从根本上消除了 "LoadingScreen 关闭但贴图未就绪" 的帧间隙
                        val preloadData = mapPreloadData
                        if (preloadData != null) {
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
                                }
                            )
                        }

                        if (mapPreloadData == null && !isRestarting) {
                            LoadingScreen(
                                progress = loadingProgress,
                                showProgress = true,
                                phaseText = preloadPhase
                            )
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
            Log.d(TAG, "onCreate: Game not loaded, will initialize. slot=$slot, isNewGame=$isNewGame")
            lifecycleScope.launch {
                VivoGCJITOptimizer.runWithJitPaused(block = {
                    when {
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
        super.onPause()
        frameMetricsMonitor.stopMonitoring(window)
        // 游戏循环继续运行（由 GameForegroundService 持有），不再调用 pauseForBackground
        // WakeLock 由 Service 持有，Activity 不再管理 release
    }

    override fun onStop() {
        // 在 super.onStop() 前结束活跃的文本选择 ActionMode，防止窗口 token 失效后
        // FloatingActionMode 尝试弹出 PopupWindow 导致 BadTokenException
        actionModeTracker?.finishActiveActionMode()
        super.onStop()
        try {
            saveLoadViewModel.pauseForBackground()
            backgroundTaskScheduler.pause()
            Log.d(TAG, "onStop: background tasks paused")
        } catch (e: Exception) {
            Log.e(TAG, "Error during onStop", e)
        }
    }

    override fun onResume() {
        super.onResume()
        hideSystemBars()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            frameMetricsMonitor.startMonitoring(window)
        }
        backgroundTaskScheduler.resume()
        Log.d(TAG, "onResume: background tasks resumed")

        // 启动并绑定 GameForegroundService：游戏循环控制权已迁移到 Service
        // WakeLock 由 Service 持有，Activity 不再管理 acquire/release
        val startIntent = Intent(this, GameForegroundService::class.java).apply {
            action = GameForegroundService.ACTION_START
        }
        startService(startIntent)
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
                } catch (e: Exception) {
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
            stopService(Intent(this, GameForegroundService::class.java))
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
            ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL -> {
                // 释放地图 Bitmap 引用以允许 GC 回收内存（ImageBitmap 无 recycle API）
                mapPreloadDataRef = null
            }
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

        prefs.edit().putBoolean("oem_guide_shown", true).apply()

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

        prefs.edit().putBoolean("exact_alarm_prompted", true).apply()

        try {
            val intent = Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
                .setData(Uri.parse("package:$packageName"))
            startActivity(intent)
            Log.d(TAG, "Requesting SCHEDULE_EXACT_ALARM permission")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to request exact alarm permission: ${e.message}")
        }
    }
}
