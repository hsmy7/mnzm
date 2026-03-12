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
    }

    private val viewModel: GameViewModel by viewModels()

    @Inject
    lateinit var sessionManager: SessionManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d(TAG, "onCreate started")

        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            Log.e(TAG, "Uncaught exception in thread ${thread.name}", throwable)
        }

        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).let { controller ->
            controller.hide(WindowInsetsCompat.Type.statusBars())
            controller.hide(WindowInsetsCompat.Type.navigationBars())
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        val slot = intent.getIntExtra(MainActivity.EXTRA_SLOT, -1)
        val isNewGame = intent.getBooleanExtra(MainActivity.EXTRA_NEW_GAME, false)
        val sectName = intent.getStringExtra(MainActivity.EXTRA_SECT_NAME) ?: "青云宗"

        Log.d(TAG, "Intent extras: slot=$slot, isNewGame=$isNewGame, sectName=$sectName")

        setContent {
            XianxiaTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    val isLoading by viewModel.isLoading.collectAsState()
                    val errorMessage by viewModel.errorMessage.collectAsState()
                    val successMessage by viewModel.successMessage.collectAsState()

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

                        if (isLoading) {
                            Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center
                            ) {
                                CircularProgressIndicator()
                            }
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

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.CREATED) {
                try {
                    when {
                        isNewGame && slot > 0 -> {
                            viewModel.startNewGame(sectName, slot)
                        }
                        slot > 0 -> {
                            viewModel.loadGameFromSlot(slot)
                        }
                        isNewGame -> {
                            viewModel.startNewGame(sectName = sectName)
                        }
                        else -> {
                            Log.e(TAG, "Invalid game start parameters")
                            finish()
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error starting game", e)
                }
            }
        }

        Log.d(TAG, "onCreate completed")
    }

    override fun onPause() {
        super.onPause()
        try {
            viewModel.pauseGame()
            // 使用阻塞方式执行自动保存，确保在进入后台前完成
            kotlinx.coroutines.runBlocking(Dispatchers.IO) {
                try {
                    viewModel.performAutoSave()
                } catch (e: Exception) {
                    Log.e(TAG, "Error during auto save", e)
                }
            }
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
}
