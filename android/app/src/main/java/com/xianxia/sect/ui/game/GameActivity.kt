package com.xianxia.sect.ui.game

import android.content.ComponentCallbacks2
import android.content.Intent
import android.os.Build
import android.os.Bundle
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
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import androidx.compose.ui.graphics.asImageBitmap
import com.xianxia.sect.R
import com.xianxia.sect.XianxiaApplication
import com.xianxia.sect.core.CrashHandler
import com.xianxia.sect.core.engine.GameEngineCore
import com.xianxia.sect.core.model.MapPreloadData
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
import com.xianxia.sect.core.GameConfig
import kotlin.random.Random
import javax.inject.Inject

@AndroidEntryPoint
class GameActivity : ComponentActivity(), XianxiaApplication.MemoryPressureListener {

    companion object {
        private const val TAG = "GameActivity"
        private const val KEY_CURRENT_SLOT = "current_slot"

        private const val TILE_GROUND = 0
        private const val TILE_GRASS = 1
        private const val TILE_TREE = 2

        private fun generateRawTileData(worldWidthCells: Int, worldHeightCells: Int): Array<IntArray> {
            val rng = Random(42)
            val data = Array(worldHeightCells) { IntArray(worldWidthCells) { TILE_GROUND } }

            for (tx in 0 until worldWidthCells / 5) {
                for (ty in 0 until worldHeightCells / 5) {
                    if (rng.nextFloat() < 0.10f) {
                        val cx = (tx * 5 + rng.nextInt(3)).coerceIn(0, worldWidthCells - 1)
                        val cy = (ty * 5 + rng.nextInt(3)).coerceIn(0, worldHeightCells - 1)
                        data[cy][cx] = TILE_TREE
                    }
                }
            }
            for (gx in 0 until worldWidthCells) {
                for (gy in 0 until worldHeightCells) {
                    if (data[gy][gx] == TILE_GROUND && rng.nextFloat() < 0.06f) {
                        data[gy][gx] = TILE_GRASS
                    }
                }
            }
            return data
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
    private val worldMapViewModel: WorldMapViewModel by viewModels()
    private val battleViewModel: BattleViewModel by viewModels()

    @Inject
    lateinit var sessionManager: SessionManager

    @Inject
    lateinit var storageFacade: StorageFacade

    @Inject
    lateinit var crashHandler: CrashHandler

    @Inject
    lateinit var gameEngineCore: GameEngineCore

    @Inject
    lateinit var backgroundTaskScheduler: com.xianxia.sect.core.util.BackgroundTaskScheduler

    @Inject
    lateinit var frameMetricsMonitor: FrameMetricsMonitor

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d(TAG, "onCreate started, savedInstanceState=$savedInstanceState")

        // 注册内存压力监听
        (application as? XianxiaApplication)?.registerMemoryPressureListener(this)

        // 初始化并注册崩溃处理器
        setupCrashHandler()

        SecureKeyManager.recoveryCallback = UiKeyRecoveryCallback { this@GameActivity }

        gameEngineCore.initialize()

        WindowCompat.setDecorFitsSystemWindows(window, false)
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
                            val worldPixelWidth = GameConfig.SectMap.WORLD_PIXEL_WIDTH
                            val worldPixelHeight = GameConfig.SectMap.WORLD_PIXEL_HEIGHT

                            val result = withContext(Dispatchers.IO) {
                                val groundBmp = try {
                                    val opts = android.graphics.BitmapFactory.Options().apply { inSampleSize = 2 }
                                    val src = android.graphics.BitmapFactory.decodeResource(
                                        resources, R.drawable.sect_ground_map, opts
                                    ) ?: throw Exception("ground decode failed")
                                    android.graphics.Bitmap.createScaledBitmap(src, worldPixelWidth, worldPixelHeight, false)
                                } catch (e: Exception) { null }

                                val grassBmp = try {
                                    val opts = android.graphics.BitmapFactory.Options().apply { inSampleSize = 4 }
                                    android.graphics.BitmapFactory.decodeResource(
                                        resources, R.drawable.decoration_grass, opts
                                    )
                                } catch (e: Exception) { null }

                                val treeBmp = try {
                                    val opts = android.graphics.BitmapFactory.Options().apply { inSampleSize = 4 }
                                    android.graphics.BitmapFactory.decodeResource(
                                        resources, R.drawable.decoration_trees, opts
                                    )
                                } catch (e: Exception) { null }

                                val rawTileData = generateRawTileData(worldWidthCells, worldHeightCells)

                                if (groundBmp != null && grassBmp != null && treeBmp != null) {
                                    // 预渲染完整静态地图（地面纹理 + 全部草/树装饰）
                                    val fullBmp = android.graphics.Bitmap.createBitmap(
                                        worldPixelWidth, worldPixelHeight, android.graphics.Bitmap.Config.ARGB_8888
                                    )
                                    val canvas = android.graphics.Canvas(fullBmp)
                                    canvas.drawBitmap(groundBmp, 0f, 0f, null)

                                    val overlap = 1
                                    for (row in 0 until worldHeightCells) {
                                        for (col in 0 until worldWidthCells) {
                                            val tile = rawTileData[row][col]
                                            if (tile == TILE_GROUND) continue
                                            val dstX = col * tileSize - overlap
                                            val dstY = row * tileSize - overlap
                                            val dstW = tileSize + overlap * 2
                                            when (tile) {
                                                TILE_GRASS -> {
                                                    canvas.drawBitmap(grassBmp, null,
                                                        android.graphics.Rect(dstX, dstY, dstX + dstW, dstY + dstW), null)
                                                }
                                                TILE_TREE -> {
                                                    val cx = col * tileSize + tileSize / 2
                                                    val cy = row * tileSize + tileSize / 2
                                                    val decW = tileSize * 2 + overlap * 2
                                                    canvas.drawBitmap(treeBmp, null,
                                                        android.graphics.Rect(
                                                            cx - tileSize - overlap, cy - tileSize - overlap,
                                                            cx + tileSize + overlap, cy + tileSize + overlap
                                                        ), null)
                                                }
                                            }
                                        }
                                    }

                                    MapPreloadData(
                                        groundTileBmp = groundBmp.asImageBitmap(),
                                        grassDecBmp = grassBmp.asImageBitmap(),
                                        treeDecBmp = treeBmp.asImageBitmap(),
                                        rawTileData = rawTileData,
                                        worldWidthCells = worldWidthCells,
                                        worldHeightCells = worldHeightCells,
                                        tileSize = tileSize,
                                        worldPixelWidth = worldPixelWidth,
                                        worldPixelHeight = worldPixelHeight,
                                        fullMapBmp = fullBmp.asImageBitmap()
                                    )
                                } else {
                                    // 贴图解码失败时生成纯色回退瓦片，确保游戏仍可启动
                                    val fallbackBmp = android.graphics.Bitmap.createBitmap(
                                        tileSize, tileSize, android.graphics.Bitmap.Config.ARGB_8888
                                    ).also { it.eraseColor(0xFFF2EDE4.toInt()) }
                                    val fallbackImg = fallbackBmp.asImageBitmap()
                                    val fullFallbackBmp = android.graphics.Bitmap.createBitmap(
                                        worldPixelWidth, worldPixelHeight, android.graphics.Bitmap.Config.ARGB_8888
                                    ).also { it.eraseColor(0xFFF2EDE4.toInt()) }
                                    MapPreloadData(
                                        groundTileBmp = fallbackImg,
                                        grassDecBmp = fallbackImg,
                                        treeDecBmp = fallbackImg,
                                        rawTileData = Array(worldHeightCells) { IntArray(worldWidthCells) },
                                        worldWidthCells = worldWidthCells,
                                        worldHeightCells = worldHeightCells,
                                        tileSize = tileSize,
                                        worldPixelWidth = worldPixelWidth,
                                        worldPixelHeight = worldPixelHeight,
                                        fullMapBmp = fullFallbackBmp.asImageBitmap()
                                    )
                                }
                            }

                            if (!viewModel.gameData.value.isGameStarted) return@LaunchedEffect
                            mapPreloadData = result
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
                                worldMapViewModel = worldMapViewModel,
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
                                showProgress = true
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
        try {
            gameEngineCore.pauseForBackground()
            Log.d(TAG, "onPause: game engine paused for background")
        } catch (e: Exception) {
            Log.e(TAG, "Error pausing game engine in onPause", e)
        }
    }

    override fun onStop() {
        super.onStop()
        try {
            saveLoadViewModel.pauseAndSaveForBackground()
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
        if (gameEngineCore.wasPausedByBackground) {
            gameEngineCore.clearBackgroundPauseFlag()
            try {
                saveLoadViewModel.resumeGameLoop()
            } catch (e: Exception) {
                Log.e(TAG, "Error during onResume", e)
            }
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            hideSystemBars()
        }
    }

    private var lastEmergencySaveTime = 0L
    private val emergencySaveDebounceMs = 5000L

    private fun hideSystemBars() {
        WindowInsetsControllerCompat(window, window.decorView).let { controller ->
            controller.hide(WindowInsetsCompat.Type.statusBars() or WindowInsetsCompat.Type.navigationBars())
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "onDestroy called")
        frameMetricsMonitor.stopMonitoring(window)
        SecureKeyManager.recoveryCallback = null
        (application as? XianxiaApplication)?.unregisterMemoryPressureListener(this)
        // 注意：不在此处调用 gameEngineCore.shutdown()
        // shutdown 会取消协程作用域和释放系统，可能干扰 ViewModel.onCleared() 中的保存操作。
        // GameEngineCore 是 @Singleton，其生命周期绑定到应用进程，由 Application 统一管理。
        // ViewModel.onCleared() 中会调用 clearResources() -> stopGameLoop() 来停止游戏循环。
    }

    override fun onLowMemory() {
        super.onLowMemory()
        Log.e(TAG, "系统内存严重不足，执行紧急保存")
        performEmergencySaveWithDebounce()
    }

    @Suppress("DEPRECATION")
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        when (level) {
            ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN -> {
                Log.d(TAG, "UI已隐藏，可释放UI相关资源")
            }
            ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW,
            ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE,
            ComponentCallbacks2.TRIM_MEMORY_BACKGROUND -> {
                Log.w(TAG, "运行时内存压力(level=$level)")
            }
            ComponentCallbacks2.TRIM_MEMORY_MODERATE -> {
                Log.w(TAG, "内存适中压力，建议释放部分资源")
            }
            ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL,
            ComponentCallbacks2.TRIM_MEMORY_COMPLETE -> {
                Log.e(TAG, "内存严重不足(level=$level)，执行紧急保存")
                performEmergencySaveWithDebounce()
            }
        }
    }

    override fun onMemoryPressure(level: Int) {
        Log.w(TAG, "收到内存压力通知: level=$level")
        viewModel.onMemoryPressure(level)
    }

    private fun performEmergencySaveWithDebounce() {
        val now = System.currentTimeMillis()
        if (now - lastEmergencySaveTime > emergencySaveDebounceMs) {
            lastEmergencySaveTime = now
            try {
                saveLoadViewModel.performEmergencySave()
            } catch (e: Exception) {
                Log.e(TAG, "紧急保存失败", e)
            }
        } else {
            Log.d(TAG, "跳过重复的紧急保存请求，距上次保存 ${now - lastEmergencySaveTime}ms")
        }
    }

    /**
     * 设置崩溃处理器
     * 注册全局异常捕获，并在崩溃时尝试紧急保存游戏数据
     */
    private fun setupCrashHandler() {
        try {
            // 初始化 CrashHandler 单例
            CrashHandler.init(crashHandler)

            // 设置紧急保存回调
            crashHandler.setEmergencySaveCallback {
                performEmergencySave()
            }

            // 注册崩溃处理器
            crashHandler.register()

            Log.i(TAG, "CrashHandler setup completed")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to setup CrashHandler", e)
        }
    }

    /**
     * 执行紧急保存
     * 在崩溃发生时调用，尝试保存当前游戏状态
     * 注意：使用同步方法避免死锁，可能读取到不一致状态但总比丢失数据好
     * @return 是否保存成功
     */
    private fun performEmergencySave(): Boolean {
        return try {
            val gameData = viewModel.gameData.value
            if (gameData.sectName.isNotEmpty()) {
                Log.i(TAG, "Attempting emergency save for sect: ${gameData.sectName}")
                val saveData = saveLoadViewModel.createSaveDataSync()
                kotlinx.coroutines.runBlocking(kotlinx.coroutines.Dispatchers.IO) {
                    kotlinx.coroutines.withTimeoutOrNull(2_000L) {
                        storageFacade.emergencySaveSuspend(saveData)
                    } ?: false
                }
            } else {
                Log.w(TAG, "No valid game data to save in emergency")
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Emergency save failed", e)
            false
        }
    }
}
