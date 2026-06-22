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
import kotlinx.coroutines.withTimeoutOrNull
import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.perf.GpuTierDetector
import com.xianxia.sect.core.perf.GpuRenderConfig
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

        /** 单张 Bitmap 内存分配硬上限 (50 MB)，防止异常大图导致 OOM */
        private const val MAX_BITMAP_ALLOCATION_BYTES = 50 * 1024 * 1024

        /**
         * @param grassProbability 草地生成概率 (0.0=无草, 0.03=稀疏, 0.06=正常)
         * @param treeProbability 树木生成概率 (0.0=无树, 0.05=稀疏, 0.10=正常)
         */
        private fun generateRawTileData(
            worldWidthCells: Int,
            worldHeightCells: Int,
            grassProbability: Float = 0.06f,
            treeProbability: Float = 0.10f
        ): Array<IntArray> {
            val rng = Random(42)
            val data = Array(worldHeightCells) { IntArray(worldWidthCells) { TILE_GROUND } }

            if (treeProbability > 0f) {
                for (tx in 0 until worldWidthCells / 5) {
                    for (ty in 0 until worldHeightCells / 5) {
                        if (rng.nextFloat() < treeProbability) {
                            val cx = (tx * 5 + rng.nextInt(3)).coerceIn(0, worldWidthCells - 1)
                            val cy = (ty * 5 + rng.nextInt(3)).coerceIn(0, worldHeightCells - 1)
                            data[cy][cx] = TILE_TREE
                        }
                    }
                }
            }
            if (grassProbability > 0f) {
                for (gx in 0 until worldWidthCells) {
                    for (gy in 0 until worldHeightCells) {
                        if (data[gy][gx] == TILE_GROUND && rng.nextFloat() < grassProbability) {
                            data[gy][gx] = TILE_GRASS
                        }
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
    lateinit var gameEngineCore: GameEngineCore

    @Inject
    lateinit var backgroundTaskScheduler: com.xianxia.sect.core.util.BackgroundTaskScheduler

    @Inject
    lateinit var frameMetricsMonitor: FrameMetricsMonitor

    @Inject
    lateinit var wakeLockManager: com.xianxia.sect.core.util.WakeLockManager

    // GPU 分级检测 — 在 Activity 级别缓存，供地图预渲染使用
    // 来源: docs/device-adaptation-plan.md §5 Step 5 — 优先使用 GameManager API
    private val gpuRenderConfig: GpuRenderConfig by lazy { GpuRenderConfig.forTier(GpuTierDetector().detect(this)) }

    // 持有地图预加载数据引用，供 onTrimMemory 中释放 Bitmap 使用
    @Volatile
    private var mapPreloadDataRef: MapPreloadData? = null

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

                                val groundBmp = try {
                                    // 安全检查：异常大图拒绝分配，防止 OOM
                                    val estBytes = renderWidth.toLong() * renderHeight * 4
                                    if (estBytes > MAX_BITMAP_ALLOCATION_BYTES) {
                                        Log.e(TAG, "groundBmp too large: ${renderWidth}x$renderHeight est=${estBytes}bytes > max=$MAX_BITMAP_ALLOCATION_BYTES")
                                        null
                                    } else {
                                        val opts = android.graphics.BitmapFactory.Options().apply { inSampleSize = groundSampleSize }
                                        val src = android.graphics.BitmapFactory.decodeResource(
                                            resources, R.drawable.sect_ground_map, opts
                                        ) ?: throw Exception("ground decode failed")
                                        android.graphics.Bitmap.createScaledBitmap(src, renderWidth, renderHeight, false)
                                    }
                                } catch (e: kotlinx.coroutines.CancellationException) {
                                    throw e
                                } catch (e: Exception) { null }

                                val grassBmp = try {
                                    val opts = android.graphics.BitmapFactory.Options().apply { inSampleSize = decorationSampleSize }
                                    android.graphics.BitmapFactory.decodeResource(
                                        resources, R.drawable.decoration_grass, opts
                                    )
                                } catch (e: kotlinx.coroutines.CancellationException) {
                                    throw e
                                } catch (e: Exception) { null }

                                // 来源: docs/huawei-performance-research.md §4.2 — LOW 级别跳过树木装饰
                                val treeBmp = if (renderConfig.showTrees) {
                                    try {
                                        val opts = android.graphics.BitmapFactory.Options().apply { inSampleSize = decorationSampleSize }
                                        android.graphics.BitmapFactory.decodeResource(
                                            resources, R.drawable.decoration_trees, opts
                                        )
                                    } catch (e: kotlinx.coroutines.CancellationException) {
                                        throw e
                                    } catch (e: Exception) { null }
                                } else null

                                // 来源: docs/device-adaptation-plan.md §4 — particleEffectMode 控制装饰密度
                                // "off"=无草无树, "simple"=半密度, "full"=全密度
                                val grassProbability = when (renderConfig.particleEffectMode) {
                                    "off" -> 0.0f
                                    "simple" -> 0.03f
                                    else -> 0.06f
                                }
                                val treeProbability = when (renderConfig.particleEffectMode) {
                                    "off" -> 0.0f
                                    "simple" -> 0.05f
                                    else -> 0.10f
                                }
                                val rawTileData = generateRawTileData(worldWidthCells, worldHeightCells, grassProbability, treeProbability)

                                if (groundBmp != null && grassBmp != null) {
                                    // 预渲染完整静态地图（地面纹理 + 草/树装饰）
                                    // 来源: docs/huawei-performance-research.md §4.4 — 低端 GPU 使用 RGB_565
                                    val bmpConfig = if (renderConfig.useArgb8888)
                                        android.graphics.Bitmap.Config.ARGB_8888
                                    else
                                        android.graphics.Bitmap.Config.RGB_565
                                    // 安全检查：异常大图拒绝分配，防止 OOM
                                    val bytesPerPixel = if (bmpConfig == android.graphics.Bitmap.Config.ARGB_8888) 4 else 2
                                    val estFullBytes = renderWidth.toLong() * renderHeight * bytesPerPixel
                                    if (estFullBytes > MAX_BITMAP_ALLOCATION_BYTES) {
                                        Log.e(TAG, "fullBmp too large: ${renderWidth}x$renderHeight est=$estFullBytes bytes > max=$MAX_BITMAP_ALLOCATION_BYTES")
                                        throw Exception("fullBmp allocation refused: $estFullBytes bytes exceeds limit")
                                    }
                                    val fullBmp = android.graphics.Bitmap.createBitmap(
                                        renderWidth, renderHeight, bmpConfig
                                    )
                                    val canvas = android.graphics.Canvas(fullBmp)
                                    // groundBmp 已缩放到渲染分辨率，直接铺满
                                    canvas.drawBitmap(groundBmp, 0f, 0f, null)
                                    // Canvas 缩放使装饰物世界坐标映射到渲染分辨率
                                    canvas.scale(renderScale, renderScale)

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
                                                    if (treeBmp != null) {
                                                        val cx = col * tileSize + tileSize / 2
                                                        val cy = row * tileSize + tileSize / 2
                                                        canvas.drawBitmap(treeBmp, null,
                                                            android.graphics.Rect(
                                                                cx - tileSize - overlap, cy - tileSize - overlap,
                                                                cx + tileSize + overlap, cy + tileSize + overlap
                                                            ), null)
                                                    }
                                                }
                                            }
                                        }
                                    }

                                    // 来源: docs/huawei-performance-research.md §4.4 — 预热 GPU 纹理上传管线
                                    groundBmp.prepareToDraw()
                                    grassBmp.prepareToDraw()
                                    treeBmp?.prepareToDraw()
                                    fullBmp.prepareToDraw()

                                    MapPreloadData(
                                        groundTileBmp = groundBmp.asImageBitmap(),
                                        grassDecBmp = grassBmp.asImageBitmap(),
                                        treeDecBmp = treeBmp?.asImageBitmap() ?: grassBmp.asImageBitmap(),
                                        rawTileData = rawTileData,
                                        worldWidthCells = worldWidthCells,
                                        worldHeightCells = worldHeightCells,
                                        tileSize = tileSize,
                                        worldPixelWidth = worldPixelWidth,
                                        worldPixelHeight = worldPixelHeight,
                                        renderWidth = renderWidth,
                                        renderHeight = renderHeight,
                                        fullMapBmp = fullBmp.asImageBitmap()
                                    )
                                } else {
                                    // 贴图解码失败时生成纯色回退瓦片，确保游戏仍可启动
                                    val fallbackBmp = android.graphics.Bitmap.createBitmap(
                                        tileSize, tileSize, android.graphics.Bitmap.Config.ARGB_8888
                                    ).also { it.eraseColor(0xFFF2EDE4.toInt()) }
                                    val fallbackImg = fallbackBmp.asImageBitmap()
                                    val fullFallbackBmp = android.graphics.Bitmap.createBitmap(
                                        renderWidth, renderHeight, android.graphics.Bitmap.Config.ARGB_8888
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
                                        renderWidth = renderWidth,
                                        renderHeight = renderHeight,
                                        fullMapBmp = fullFallbackBmp.asImageBitmap()
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
        // 释放 WakeLock：游戏循环停止后再释放，
        // 确保 stopGameLoop 和清理操作在 CPU 保护下完成
        wakeLockManager.release()
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
        // 获取 WakeLock：保持 CPU 活跃，防止 OEM 省电策略挂起游戏线程
        wakeLockManager.acquire()
        // 通知系统退出加载状态 → 恢复正常游戏性能调度
        notifyGameLoadingState(false)
        if (gameEngineCore.wasPausedByBackground) {
            gameEngineCore.resumeFromBackground()
        }
        // 华为/荣耀设备：首次进入游戏时引导用户关闭电池优化
        showBatteryOptimizationGuideIfNeeded()
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
                // Release UI-only resources
            }
            ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW -> {
                viewModel.onMemoryPressure(level)
            }
            ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL -> {
                viewModel.onMemoryPressure(level)
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
                var emergencyResult = false
                val latch = java.util.concurrent.CountDownLatch(1)
                lifecycleScope.launch(Dispatchers.IO) {
                    try {
                        emergencyResult = withTimeoutOrNull(2_000L) {
                            storageFacade.emergencySaveSuspend(saveData)
                        } ?: false
                    } finally {
                        latch.countDown()
                    }
                }
                latch.await(3, java.util.concurrent.TimeUnit.SECONDS)
                emergencyResult
            } else {
                Log.w(TAG, "No valid game data to save in emergency")
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Emergency save failed", e)
            false
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

    /** 首次进入游戏时在华为/荣耀设备上引导用户关闭电池优化 */
    private fun showBatteryOptimizationGuideIfNeeded() {
        val helper = com.xianxia.sect.core.util.BatteryOptimizationHelper
        if (!helper.shouldShowHuaweiGuide(this)) return

        // 使用 SharedPreferences 记录是否已提示过，避免每次 resume 都弹
        val prefs = getSharedPreferences("battery_guide", MODE_PRIVATE)
        if (prefs.getBoolean("huawei_guide_shown", false)) return

        prefs.edit().putBoolean("huawei_guide_shown", true).apply()

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
}
