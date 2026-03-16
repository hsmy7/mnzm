package com.xianxia.sect.ui.game

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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.xianxia.sect.core.CrashHandler
import com.xianxia.sect.data.SaveManager
import com.xianxia.sect.data.SessionManager
import com.xianxia.sect.ui.MainActivity
import com.xianxia.sect.ui.theme.XianxiaTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class GameActivity : ComponentActivity() {

    companion object {
        private const val TAG = "GameActivity"
        private const val KEY_CURRENT_SLOT = "current_slot"
    }

    private val viewModel: GameViewModel by viewModels()

    @Inject
    lateinit var sessionManager: SessionManager

    @Inject
    lateinit var saveManager: SaveManager

    @Inject
    lateinit var crashHandler: CrashHandler

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d(TAG, "onCreate started, savedInstanceState=$savedInstanceState")

        // 初始化并注册崩溃处理器
        setupCrashHandler()

        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).let { controller ->
            controller.hide(WindowInsetsCompat.Type.statusBars())
            controller.hide(WindowInsetsCompat.Type.navigationBars())
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        val savedSlot = savedInstanceState?.getInt(KEY_CURRENT_SLOT, -1) ?: -1
        val intentSlot = intent.getIntExtra(MainActivity.EXTRA_SLOT, -1)
        val isNewGame = intent.getBooleanExtra(MainActivity.EXTRA_NEW_GAME, false)
        val sectName = intent.getStringExtra(MainActivity.EXTRA_SECT_NAME) ?: "青云宗"
        
        val slot = if (savedSlot > 0) savedSlot else intentSlot
        
        Log.d(TAG, "Slot info: savedSlot=$savedSlot, intentSlot=$intentSlot, finalSlot=$slot, isNewGame=$isNewGame, sectName=$sectName")
        Log.d(TAG, "ViewModel game loaded: ${viewModel.isGameAlreadyLoaded()}")

        setContent {
            XianxiaTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    val isLoading by viewModel.isLoading.collectAsState()
                    val loadingProgress by viewModel.loadingProgress.collectAsState()
                    val errorMessage by viewModel.errorMessage.collectAsState()
                    val isRestarting by viewModel.isRestarting.collectAsState()
                    
                    // 跟踪是否是首次加载（游戏数据是否已初始化）
                    val gameData by viewModel.gameData.collectAsState()
                    val isInitialLoading = remember { mutableStateOf(true) }
                    
                    // 当游戏数据有效时，标记首次加载完成
                    LaunchedEffect(gameData.sectName) {
                        if (gameData.sectName.isNotEmpty()) {
                            isInitialLoading.value = false
                        }
                    }

                    Box(modifier = Modifier.fillMaxSize()) {
                        MainGameScreen(
                            viewModel = viewModel,
                            onLogout = {
                                sessionManager.clearSession()
                                val intent = Intent(this@GameActivity, MainActivity::class.java)
                                intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                                startActivity(intent)
                                finish()
                            }
                        )

                        // 显示加载界面：首次加载或正在加载时（重新开始时不显示）
                        if ((isInitialLoading.value || isLoading) && !isRestarting) {
                            LoadingScreen(
                                progress = loadingProgress,
                                showProgress = true
                            )
                        }

                        errorMessage?.let { error ->
                            AlertDialog(
                                onDismissRequest = { viewModel.clearErrorMessage() },
                                title = { Text("提示") },
                                text = { Text(error) },
                                confirmButton = {
                                    TextButton(onClick = {
                                        viewModel.clearErrorMessage()
                                    }) {
                                        Text("确定")
                                    }
                                }
                            )
                        }
                    }
                }
            }
        }

        if (!viewModel.isGameAlreadyLoaded()) {
            lifecycleScope.launch {
                repeatOnLifecycle(Lifecycle.State.CREATED) {
                    try {
                        when {
                            isNewGame && slot > 0 -> {
                                Log.d(TAG, "Starting new game: sectName=$sectName, slot=$slot")
                                viewModel.startNewGame(sectName, slot)
                            }
                            slot > 0 -> {
                                Log.d(TAG, "Loading game from slot: $slot")
                                viewModel.loadGameFromSlot(slot)
                            }
                            isNewGame -> {
                                Log.d(TAG, "Starting new game with default slot: sectName=$sectName")
                                viewModel.startNewGame(sectName = sectName)
                            }
                            else -> {
                                Log.e(TAG, "Invalid game start parameters: slot=$slot, isNewGame=$isNewGame")
                                finish()
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error starting game", e)
                    }
                }
            }
        } else {
            Log.d(TAG, "Game already loaded in ViewModel, skipping initialization")
        }

        Log.d(TAG, "onCreate completed")
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(KEY_CURRENT_SLOT, viewModel.gameData.value.currentSlot)
        Log.d(TAG, "onSaveInstanceState: currentSlot=${viewModel.gameData.value.currentSlot}")
    }

    override fun onPause() {
        super.onPause()
        try {
            viewModel.pauseGame()
            viewModel.performAutoSave()
        } catch (e: Exception) {
            Log.e(TAG, "Error during onPause", e)
        }
    }

    override fun onResume() {
        super.onResume()
        try {
            viewModel.resumeGame()
        } catch (e: Exception) {
            Log.e(TAG, "Error during onResume", e)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "onDestroy called")
        // ViewModel's onCleared will be called automatically, which stops the game loop
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
                val saveData = viewModel.createSaveDataSync()
                saveManager.emergencySave(saveData)
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
