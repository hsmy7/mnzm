package com.xianxia.sect.ui.game

import android.content.ComponentCallbacks2
import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import com.xianxia.sect.XianxiaApplication
import com.xianxia.sect.core.CrashHandler
import com.xianxia.sect.core.engine.GameEngineCore
import com.xianxia.sect.core.util.VivoGCJITOptimizer
import com.xianxia.sect.data.crypto.SecureKeyManager
import com.xianxia.sect.data.crypto.UiKeyRecoveryCallback
import com.xianxia.sect.data.facade.StorageFacade
import com.xianxia.sect.data.SessionManager
import com.xianxia.sect.ui.MainActivity
import com.xianxia.sect.ui.components.GameButton
import com.xianxia.sect.ui.theme.XianxiaTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class GameActivity : ComponentActivity(), XianxiaApplication.MemoryPressureListener {

    companion object {
        private const val TAG = "GameActivity"
        private const val KEY_CURRENT_SLOT = "current_slot"
    }

    private val viewModel: GameViewModel by viewModels()
    private val saveLoadViewModel: SaveLoadViewModel by viewModels()
    private val productionViewModel: ProductionViewModel by viewModels()
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
                    val isLoading by saveLoadViewModel.isLoading.collectAsState()
                    val loadingProgress by saveLoadViewModel.loadingProgress.collectAsState()
                    var errorMessage by remember { mutableStateOf<String?>(null) }
                    val isRestarting by saveLoadViewModel.isRestarting.collectAsState()
                    
                    val gameData by viewModel.gameData.collectAsState()
                    val isInitialLoading = remember { mutableStateOf(true) }
                    val limitAdTrackingState = remember { mutableStateOf(sessionManager.limitAdTracking) }
                    
                    LaunchedEffect(gameData.sectName) {
                        if (gameData.sectName.isNotEmpty()) {
                            isInitialLoading.value = false
                        }
                    }

                    Box(modifier = Modifier.fillMaxSize()) {
                        MainGameScreen(
                            viewModel = viewModel,
                            saveLoadViewModel = saveLoadViewModel,
                            productionViewModel = productionViewModel,
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

                        if (isInitialLoading.value && !isRestarting) {
                            LoadingScreen(
                                progress = loadingProgress,
                                showProgress = true
                            )
                        }

                        errorMessage?.let { error ->
                            AlertDialog(
                                onDismissRequest = { errorMessage = null },
                                title = { Text("提示") },
                                text = { Text(error) },
                                confirmButton = {
                                    GameButton(
                                        text = "确定",
                                        onClick = { errorMessage = null }
                                    )
                                }
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
        } catch (e: Exception) {
            Log.e(TAG, "Error during onStop", e)
        }
    }

    override fun onResume() {
        super.onResume()
        hideSystemBars()
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
                        storageFacade.emergencySave(saveData)
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
