package com.xianxia.sect.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.view.ActionMode
import android.view.View
import android.view.Window
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.activity.enableEdgeToEdge
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import com.xianxia.sect.R
import com.xianxia.sect.ui.components.GameBackground
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.xianxia.sect.BuildConfig
import com.xianxia.sect.core.CrashHandler
import com.xianxia.sect.data.SessionManager
import com.xianxia.sect.data.facade.StorageFacade
import com.xianxia.sect.data.model.SaveSlot
import com.xianxia.sect.taptap.TapTapAuthManager
import com.xianxia.sect.taptap.TapCloudSaveManager
import com.xianxia.sect.taptap.LoginData
import com.xianxia.sect.taptap.ComplianceManager
import com.xianxia.sect.ui.game.GameActivity
import com.xianxia.sect.ui.game.LoadingScreen
import com.xianxia.sect.ui.components.GameButton
import com.xianxia.sect.ui.components.AudioToggleRow
import androidx.compose.runtime.CompositionLocalProvider
import com.xianxia.sect.ui.components.LocalPlayClickSound
import com.xianxia.sect.ui.components.clickableWithSound
import com.xianxia.sect.ui.theme.GameColors
import com.xianxia.sect.ui.theme.XianxiaTheme
import com.xianxia.sect.core.audio.AudioConfig
import com.xianxia.sect.core.audio.AudioEngine
import com.xianxia.sect.core.audio.AudioPreloader
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/** 具名 ComplianceCallback 实现（避免匿名内部类触发 KSP getSimpleName NPE） */
private class MainComplianceCallback(private val activity: MainActivity) : ComplianceManager.ComplianceCallback {
    override fun onLoginSuccess() {
        activity.runOnUiThread {
            activity.sessionManager.markComplianceVerified()
            activity.showModeSelectionScreen()
        }
    }
    override fun onExited() = activity.handleUserExit()
    override fun onSwitchAccount() = activity.handleUserExit()
    override fun onPeriodRestrict() {
        activity.runOnUiThread {
            activity.sessionManager.complianceVerified = false
            activity.complianceDialogState.value = MainActivity.ComplianceDialogState.Restrict(
                "时间限制", "根据防沉迷规定，未成年人仅可在周五、周六、周日及法定节假日的20:00-21:00进行游戏。"
            )
        }
    }
    override fun onDurationLimit() {
        activity.runOnUiThread {
            activity.sessionManager.complianceVerified = false
            activity.complianceDialogState.value = MainActivity.ComplianceDialogState.Restrict(
                "时长限制", "您今日的游戏时长已用尽，请合理安排游戏时间。"
            )
        }
    }
    override fun onAgeLimit() {
        activity.runOnUiThread {
            activity.sessionManager.complianceVerified = false
            activity.complianceDialogState.value = MainActivity.ComplianceDialogState.AgeLimit
        }
    }
    override fun onNetworkError() {
        activity.runOnUiThread { Toast.makeText(activity, "网络连接异常，请检查网络后重试", Toast.LENGTH_LONG).show(); activity.showMainScreen() }
    }
    override fun onRealNameStop() = activity.handleUserExit()
}

/** 具名 Runnable 实现（避免匿名内部类触发 KSP getSimpleName NPE） */
private class ProgressRunnable : Runnable {
    override fun run() {
        val activity = weakActivity.get() ?: return
        if (activity.isLoadComplete) {
            activity.loadingProgress.floatValue = 1f
            activity.loadHandler.postDelayed({ activity.onLoadingComplete() }, 150)
        } else {
            val current = activity.loadingProgress.floatValue
            if (current < 0.9f) activity.loadingProgress.floatValue = current + 0.05f
            activity.loadHandler.postDelayed(this, 50)
        }
    }
    companion object { private lateinit var weakActivity: java.lang.ref.WeakReference<MainActivity>
        fun attach(activity: ProgressRunnable, ctx: MainActivity) { weakActivity = java.lang.ref.WeakReference(ctx) }
    }
}

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    
    @Inject
    lateinit var sessionManager: SessionManager
    
    @Inject
    lateinit var storageFacade: StorageFacade

    @Inject
    lateinit var tapCloudSaveManager: TapCloudSaveManager

    @Inject
    lateinit var audioConfig: AudioConfig

    @Inject
    lateinit var audioEngine: AudioEngine

    @Inject
    lateinit var audioPreloader: AudioPreloader
    
    public var complianceDialogState = mutableStateOf<ComplianceDialogState?>(null)
    /** TapTap SDK 初始化就绪状态，登录按钮需此标记为 true 才可点击 */
    internal var tapTapReady = mutableStateOf(false)
    internal val loadingProgress = mutableFloatStateOf(0f)
    internal var isLoadComplete = false
    internal val loadHandler = android.os.Handler(android.os.Looper.getMainLooper())
    
    companion object {
        private const val TAG = "MainActivity"
        private const val REQUEST_CODE_POST_NOTIFICATIONS = 1001
        const val EXTRA_SLOT = "slot"
        const val EXTRA_NEW_GAME = "new_game"
        const val EXTRA_SECT_NAME = "sect_name"
        const val EXTRA_CLOUD_SAVE_LOAD = "cloud_save_load"
    }
    
    public sealed class ComplianceDialogState {
        data class Restrict(val title: String, val message: String) : ComplianceDialogState()
        object AgeLimit : ComplianceDialogState()
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        // ── HW 加速决策（与 GameActivity 保持一致） ──
        // 检测 VulkanPolicy 和 CrashRecoveryEngine 是否要求禁用 HW 加速。
        // 在 super.onCreate() 之前设置主题，确保窗口创建时 HWUI 使用正确的渲染模式
        val disableAccel = com.xianxia.sect.core.CrashRecoveryEngine.isSafeMode() ||
            com.xianxia.sect.core.VulkanPolicy.isAccelerationDisabled()
        if (disableAccel) {
            setTheme(R.style.Theme_XianxiaSect_GameSafe)
        } else {
            setTheme(R.style.Theme_XianxiaSect)
        }

        super.onCreate(savedInstanceState)

        enableEdgeToEdge()
        hideSystemBars()
        // 安装 ActionMode 安全回调，防御文本选择工具栏 BadTokenException
        installActionModeSafeCallback()
        
        if (!::sessionManager.isInitialized) {
            Log.e(TAG, "SessionManager未初始化")
            finish()
            return
        }
        
        if (sessionManager.hasAgreedPrivacy) {
            com.xianxia.sect.core.util.VivoGCJITOptimizer.initialize()
            if (com.xianxia.sect.core.util.VivoGCJITOptimizer.isOptimizationActive()) {
                com.xianxia.sect.core.util.VivoGCJITOptimizer.extendGcDelayForMs(10_000L)
            }
            proceedAfterPrivacyConsent()
        } else {
            showPrivacyConsentScreen()
        }
    }
    
    internal fun onPrivacyAgreed() {
        sessionManager.hasAgreedPrivacy = true
        com.xianxia.sect.core.util.VivoGCJITOptimizer.initialize()
        if (com.xianxia.sect.core.util.VivoGCJITOptimizer.isOptimizationActive()) {
            com.xianxia.sect.core.util.VivoGCJITOptimizer.extendGcDelayForMs(10_000L)
        }
        requestNotificationPermissionIfNeeded()
        proceedAfterPrivacyConsent()
    }
    
    private fun showPrivacyConsentScreen() {
        setContent {
            XianxiaTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = Color.White
                ) {
                    PrivacyConsentScreen(
                        onAgree = {
                            onPrivacyAgreed()
                        },
                        onDisagree = {
                            finish()
                        }
                    )
                }
            }
        }
    }
    
    private fun proceedAfterPrivacyConsent() {
        showLoadingScreen()
        startProgressAnimation()
        
        lifecycleScope.launch(Dispatchers.IO) {
            var initialized = false
            var retryCount = 0
            val maxRetries = 3
            while (!initialized && retryCount < maxRetries) {
                try {
                    val initResult = storageFacade.initialize()
                    if (initResult.isSuccess) {
                        Log.i(TAG, "StorageFacade initialized successfully (attempt ${retryCount + 1})")
                        initialized = true
                    } else {
                        retryCount++
                        Log.e(TAG, "StorageFacade initialization failed (attempt $retryCount/$maxRetries): $initResult")
                        if (retryCount < maxRetries) {
                            kotlinx.coroutines.delay(500L * retryCount)
                        }
                    }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    retryCount++
                    Log.e(TAG, "StorageFacade initialization error (attempt $retryCount/$maxRetries)", e)
                    if (retryCount < maxRetries) {
                        kotlinx.coroutines.delay(500L * retryCount)
                    }
                }
            }
            if (!initialized) {
                Log.e(TAG, "StorageFacade initialization failed after $maxRetries attempts, proceeding with empty cache")
            }
            withContext(Dispatchers.Main) {
                isLoadComplete = true
            }
        }
    }
    
    private fun startProgressAnimation() {
        val updateRunnable = ProgressRunnable()
        ProgressRunnable.attach(updateRunnable, this)
        loadHandler.post(updateRunnable)
    }
    
    internal fun onLoadingComplete() {
        // ── 音频引擎初始化（不阻塞后续流程） ──
        audioEngine.init()
        audioConfig.loadFromPrefs(sessionManager.soundEnabled, sessionManager.musicEnabled)
        audioPreloader.preloadAll(listOf("click" to R.raw.sfx_button))
        audioPreloader.preloadBGM(R.raw.bgm_main)
        if (audioConfig.musicEnabled) {
            audioEngine.playBGM()
        }

        lifecycleScope.launch {
            initTapTapSDK()

            if (sessionManager.isLoggedIn) {
                if (sessionManager.complianceVerified) {
                    showModeSelectionScreen()
                } else {
                    val savedUnionId = sessionManager.unionId
                    if (!savedUnionId.isNullOrEmpty()) {
                        Log.d(TAG, "已登录但未通过防沉迷验证，重新验证")
                        showComplianceVerificationScreen(savedUnionId)
                    } else {
                        Log.w(TAG, "已登录但缺少unionId，需要重新登录")
                        sessionManager.clearSession()
                        TapTapAuthManager.logout()
                        showMainScreen()
                    }
                }
                return@launch
            }

            showMainScreen()
        }
    }
    
    private fun showLoadingScreen() {
        setContent {
            XianxiaTheme {
                val progress by loadingProgress
                LoadingScreen(
                    progress = progress,
                    showProgress = true
                )
            }
        }
    }
    
    internal fun showMainScreen() {
        setContent {
            XianxiaTheme {
                CompositionLocalProvider(LocalPlayClickSound provides { audioEngine.playSound("click") }) {
                GameBackground {
                    MainScreen(
                        sessionManager = sessionManager,
                        complianceDialogState = complianceDialogState,
                        tapTapReady = tapTapReady.value,
                        onLoginSuccess = {
                            showModeSelectionScreen()
                        },
                        onPrivacyAgreed = {
                            onPrivacyAgreed()
                        },
                        soundEnabled = audioConfig.soundEnabled,
                        musicEnabled = audioConfig.musicEnabled,
                        onSoundToggle = { enabled ->
                            audioConfig.soundEnabled = enabled
                            sessionManager.soundEnabled = enabled
                        },
                        onMusicToggle = { enabled ->
                            audioConfig.musicEnabled = enabled
                            sessionManager.musicEnabled = enabled
                            if (enabled) audioEngine.playBGM() else audioEngine.stopBGM()
                        }
                    )
                }
                }
            }
        }
    }
    
    internal fun showModeSelectionScreen() {
        setContent {
            XianxiaTheme {
                CompositionLocalProvider(LocalPlayClickSound provides { audioEngine.playSound("click") }) {
                ModeSelectionScreen(
                    userName = sessionManager.userName ?: "TapTap用户",
                    unionId = sessionManager.unionId ?: "",
                    avatarUrl = sessionManager.avatar,
                    onNewGame = {
                        showSaveSelectScreen(mode = SaveSelectMode.NEW_GAME)
                    },
                    onLoadSave = {
                        showSaveSelectScreen(mode = SaveSelectMode.LOAD_SAVE)
                    },
                    onLogout = {
                        sessionManager.clearSession()
                        ComplianceManager.unregisterCallback()
                        recreate()
                    },
                    soundEnabled = audioConfig.soundEnabled,
                    musicEnabled = audioConfig.musicEnabled,
                    onSoundToggle = { enabled ->
                        audioConfig.soundEnabled = enabled
                        sessionManager.soundEnabled = enabled
                    },
                    onMusicToggle = { enabled ->
                        audioConfig.musicEnabled = enabled
                        sessionManager.musicEnabled = enabled
                        if (enabled) audioEngine.playBGM() else audioEngine.stopBGM()
                    }
                )
                }
            }
        }
    }

    internal fun showSaveSelectScreen(mode: SaveSelectMode = SaveSelectMode.LOAD_SAVE) {
        lifecycleScope.launch {
            val saveSlots = withContext(Dispatchers.IO) {
                try {
                    storageFacade.getSaveSlotsSuspend()
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "getSaveSlots failed, returning empty list", e)
                    emptyList()
                }
            }
            // 迁移旧自动存档数据到空槽位
            withContext(Dispatchers.IO) {
                try {
                    val legacyData = storageFacade.load(0).getOrNull()
                    if (legacyData != null) {
                        // 找第一个空槽
                        val emptySlot = saveSlots.firstOrNull { it.isEmpty }?.slot
                        if (emptySlot != null) {
                            storageFacade.setCurrentSlot(emptySlot)
                            storageFacade.save(emptySlot, legacyData)
                            storageFacade.forceDeleteSlotData(0)
                            Log.i(TAG, "Migrated legacy auto-save data to slot $emptySlot")
                        } else {
                            Log.w(TAG, "No empty slot found, keeping legacy auto-save in slot 0")
                        }
                    }
                } catch (_: Exception) { }
            }
            // 查询云存档信息（异步，失败则静默跳过）
            val cloudInfo = withContext(Dispatchers.IO) {
                try { tapCloudSaveManager.checkCloudSave() } catch (_: Exception) { null }
            }
            setContent {
                XianxiaTheme {
                    Surface(
                        modifier = Modifier.fillMaxSize(),
                        color = Color.White
                    ) {
                        SaveSelectScreen(
                            mode = mode,
                            saveSlots = saveSlots,
                            cloudSaveInfo = cloudInfo,
                            onLoadSlot = { slot ->
                                val intent = Intent(this@MainActivity, GameActivity::class.java).apply {
                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                                    putExtra(EXTRA_SLOT, slot)
                                }
                                startActivity(intent)
                                finish()
                            },
                            onCloudSaveLoad = {
                                if (!sessionManager.isLoggedIn) {
                                    Toast.makeText(this@MainActivity, "请先登录 TapTap", Toast.LENGTH_SHORT).show()
                                    return@SaveSelectScreen
                                }
                                val intent = Intent(this@MainActivity, GameActivity::class.java).apply {
                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                                    putExtra(EXTRA_CLOUD_SAVE_LOAD, true)
                                }
                                startActivity(intent)
                                finish()
                            },
                            onNewGame = { slot, sectName ->
                                val intent = Intent(this@MainActivity, GameActivity::class.java).apply {
                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                                    putExtra(EXTRA_SLOT, slot)
                                    putExtra(EXTRA_NEW_GAME, true)
                                    putExtra(EXTRA_SECT_NAME, sectName)
                                }
                                startActivity(intent)
                                finish()
                            },
                            onDeleteSlot = { slot ->
                                lifecycleScope.launch {
                                    withContext(Dispatchers.IO) {
                                        storageFacade.delete(slot)
                                    }
                                    showSaveSelectScreen(mode)
                                }
                            },
                            onBack = {
                                showModeSelectionScreen()
                            }
                        )
                    }
                }
            }
        }
    }
    
    private fun initTapTapSDK() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                // 必须先初始化 TapTap 核心 SDK，再初始化 Ad SDK
                // TapAdSdk 内部依赖 TapTapKit.context，反序会导致 lateinit context 未初始化崩溃
                TapTapAuthManager.init(
                    this@MainActivity,
                    BuildConfig.TAPTAP_CLIENT_ID,
                    BuildConfig.TAPTAP_CLIENT_TOKEN,
                    BuildConfig.TAPTAP_IS_CN,
                    sessionManager.limitAdTracking
                )
                // 反射验证 context 并通过 isReady() 双重确认
                tapTapReady.value = TapTapAuthManager.isReady()
                Log.d(TAG, "TapTap SDK初始化成功，就绪状态: ${tapTapReady.value}")

                initAdSdk()
                com.xianxia.sect.taptap.TapDBManager.startGameDurationTracking(application)
                withContext(Dispatchers.Main) {
                    ComplianceManager.registerCallback(MainComplianceCallback(this@MainActivity))
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: java.util.concurrent.TimeoutException) {
                tapTapReady.value = false
                Log.e(TAG, "TapTap SDK初始化超时，尝试降级模式", e)
                withContext(Dispatchers.Main) {
                    showSaveSelectScreen()
                }
            } catch (e: Exception) {
                tapTapReady.value = false
                Log.e(TAG, "TapTap SDK初始化失败: ${e.message}", e)
            }
        }
    }

    /** 初始化 Dirichlet Ad SDK（仅在用户同意隐私政策后调用） */
    private fun initAdSdk() {
        try {
            val config = com.tapsdk.tapad.TapAdConfig.Builder()
                .withMediaId(1102528)
                .withMediaName("模拟宗门")
                .withMediaKey("mVqNo2pNuostrqythQ9HXeLOMF4flzBA71skS5P9vNqChyIWIhbj1Qotmutf0Dbn")
                .enableDebug(BuildConfig.DEBUG)
                .shakeEnabled(true)
                .build()
            com.tapsdk.tapad.TapAdSdk.init(application, config)
            Log.i(TAG, "Dirichlet Ad SDK 初始化完成（用户已同意隐私政策）")
        } catch (e: Exception) {
            Log.e(TAG, "Dirichlet Ad SDK 初始化失败: ${e.message}", e)
        }
    }
    
    internal fun handleUserExit() {
        runOnUiThread {
            sessionManager.clearSession()
            com.xianxia.sect.taptap.TapDBManager.stopGameDurationTracking()
            TapTapAuthManager.logout()
            showMainScreen()
        }
    }
    
    internal fun startComplianceCheck(unionId: String) {
        Log.d(TAG, "开始合规认证检查，unionId: $unionId")
        ComplianceManager.startup(this, unionId)
    }
    
    private fun showComplianceVerificationScreen(unionId: String) {
        setContent {
            XianxiaTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = Color.White
                ) {
                    ComplianceVerificationScreen(
                        onStartVerification = {
                            startComplianceCheck(unionId)
                        },
                        onLogout = {
                            sessionManager.clearSession()
                            TapTapAuthManager.logout()
                            showMainScreen()
                        }
                    )
                }
            }
        }
    }

    override fun onPause() {
        super.onPause()
        audioEngine.pauseBGM()
    }

    override fun onResume() {
        super.onResume()
        hideSystemBars()
        requestNotificationPermissionIfNeeded()
        if (audioConfig.musicEnabled && ::audioEngine.isInitialized) {
            audioEngine.resumeBGM()
        }
    }

    /**
     * Android 13+：直接弹出系统通知权限请求（无中间提示框）。
     *
     * 在进入应用时（隐私同意后）直接调用 [requestPermissions]，
     * 不再使用自定义提示框作为中转。
     */
    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (!sessionManager.hasAgreedPrivacy) return
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
            == PackageManager.PERMISSION_GRANTED
        ) return

        requestPermissions(
            arrayOf(Manifest.permission.POST_NOTIFICATIONS),
            REQUEST_CODE_POST_NOTIFICATIONS
        )
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

    override fun onDestroy() {
        actionModeTracker?.finishActiveActionMode()
        actionModeTracker = null
        loadHandler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    // ── ActionMode 安全回调（与 GameActivity.ActionModeSafeCallback 一致） ──
    // 拦截 ActionMode（FloatingActionMode/文本选择工具栏）生命周期，
    // 确保在 Activity 销毁前结束活跃的 ActionMode，防止 BadTokenException。

    @Volatile
    private var actionModeTracker: ActionModeSafeCallback? = null

    private fun installActionModeSafeCallback() {
        val original = window.callback ?: return
        if (original is ActionModeSafeCallback) return
        ActionModeSafeCallback(original).also {
            window.callback = it
            actionModeTracker = it
        }
    }

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
                try { mode.finish() } catch (_: Exception) { }
                return
            }
            activeActionMode = mode
            delegate.onActionModeStarted(mode)
        }

        override fun onActionModeFinished(mode: ActionMode) {
            if (activeActionMode === mode) activeActionMode = null
            delegate.onActionModeFinished(mode)
        }

        fun finishActiveActionMode() {
            activeActionMode?.let { mode ->
                isTearingDown = true
                try { mode.finish() } catch (_: Exception) { }
                activeActionMode = null
            }
        }
    }
}

@Composable
fun MainScreen(
    sessionManager: SessionManager,
    complianceDialogState: MutableState<MainActivity.ComplianceDialogState?>,
    tapTapReady: Boolean = false,
    onLoginSuccess: () -> Unit,
    onPrivacyAgreed: () -> Unit = {},
    soundEnabled: Boolean = true,
    musicEnabled: Boolean = true,
    onSoundToggle: (Boolean) -> Unit = {},
    onMusicToggle: (Boolean) -> Unit = {}
) {
    val context = LocalContext.current
    var isLoading by remember { mutableStateOf(false) }
    var loginResult by remember { mutableStateOf<String?>(null) }
    var showInAppPrivacy by remember { mutableStateOf(false) }
    var privacyChecked by remember { mutableStateOf(sessionManager.privacyCheckboxConfirmed) }
    
    if (showInAppPrivacy) {
        FullPrivacyPolicyScreen(
            onBack = { showInAppPrivacy = false }
        )
        return
    }
    
    complianceDialogState.value?.let { state ->
        when (state) {
            is MainActivity.ComplianceDialogState.Restrict -> {
                AlertDialog(
                    onDismissRequest = { },
                    title = { Text(state.title) },
                    text = { Text(state.message) },
                    confirmButton = {
                        GameButton(
                            text = "退出游戏",
                            onClick = {
                                complianceDialogState.value = null
                                sessionManager.clearSession()
                                TapTapAuthManager.logout()
                                (context as? MainActivity)?.recreate()
                            }
                        )
                    },
                    dismissButton = {
                        GameButton(
                            text = "切换账号",
                            onClick = {
                                complianceDialogState.value = null
                                sessionManager.clearSession()
                                TapTapAuthManager.logout()
                                (context as? MainActivity)?.recreate()
                            }
                        )
                    }
                )
            }
            is MainActivity.ComplianceDialogState.AgeLimit -> {
                AlertDialog(
                    onDismissRequest = { },
                    title = { Text("适龄限制") },
                    text = { Text("根据游戏适龄提示，您当前年龄不符合本游戏的游玩要求。") },
                    confirmButton = {
                        GameButton(
                            text = "退出游戏",
                            onClick = {
                                complianceDialogState.value = null
                                (context as? MainActivity)?.finish()
                            }
                        )
                    }
                )
            }
        }
    }
    
    Box(modifier = Modifier.fillMaxSize()) {
        // 登录界面背景图
        Image(
            painter = painterResource(id = R.drawable.login_background),
            contentDescription = "登录界面背景",
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.FillBounds
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Bottom
        ) {
            if (isLoading) {
                CircularProgressIndicator()
                Spacer(modifier = Modifier.height(16.dp))
                Text("正在登录...", color = Color.Black)
            } else {
                Button(
                    onClick = {
                        if (!privacyChecked) {
                            Toast.makeText(context, "请先阅读并同意隐私政策", Toast.LENGTH_SHORT).show()
                            return@Button
                        }

                        if (!tapTapReady) {
                            Toast.makeText(context, "TapTap SDK 正在初始化，请稍后再试", Toast.LENGTH_SHORT).show()
                            return@Button
                        }

                        isLoading = true
                        loginResult = null

                        val activity = context as? MainActivity
                        if (activity == null) {
                            isLoading = false
                            Toast.makeText(context, "登录失败", Toast.LENGTH_SHORT).show()
                            return@Button
                        }

                        TapTapAuthManager.login(activity, object : TapTapAuthManager.LoginResultCallback {
                            override fun onSuccess(data: LoginData) {
                                Log.d("MainScreen", "登录成功: ${data.name}")

                                val unionId = data.unionid
                                if (unionId.isNullOrEmpty()) {
                                    Log.e("MainScreen", "unionId为空，登录失败")
                                    isLoading = false
                                    Toast.makeText(context, "登录失败，请重试", Toast.LENGTH_SHORT).show()
                                    return
                                }

                                sessionManager.saveLoginSession(
                                    userId = data.openid ?: "taptap_${System.currentTimeMillis()}",
                                    userName = data.name ?: "TapTap用户",
                                    loginType = "taptap",
                                    unionId = unionId,
                                    avatar = data.avatar
                                )

                                com.xianxia.sect.taptap.TapDBManager.setUser(
                                    userId = data.openid ?: "taptap_${System.currentTimeMillis()}",
                                    name = data.name
                                )

                                Toast.makeText(context, "欢迎, ${data.name}!", Toast.LENGTH_SHORT).show()

                                isLoading = false
                                val activity = context as? MainActivity
                                activity?.runOnUiThread {
                                    activity.startComplianceCheck(unionId)
                                }
                            }

                            override fun onFailure(error: Exception) {
                                Log.e("MainScreen", "登录失败: ${error.message}")
                                isLoading = false
                                loginResult = error.message
                                Toast.makeText(context, "登录失败: ${error.message}", Toast.LENGTH_SHORT).show()
                            }
                        })
                    },
                    modifier = Modifier
                        .wrapContentWidth()
                        .height(56.dp),
                    shape = RoundedCornerShape(28.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (privacyChecked) Color(0xFF00D26A) else Color(0xFFCCCCCC),
                        contentColor = Color.White
                    ),
                    enabled = !isLoading
                ) {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_taptap),
                        contentDescription = "TapTap",
                        modifier = Modifier.size(24.dp),
                        tint = Color.White
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        "使用 TapTap 登录",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }

            loginResult?.let { error ->
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = error,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Checkbox(
                    checked = privacyChecked,
                    onCheckedChange = { checked ->
                        privacyChecked = checked
                        sessionManager.privacyCheckboxConfirmed = checked
                    },
                    colors = CheckboxDefaults.colors(
                        checkedColor = GameColors.SpiritBlue,
                        uncheckedColor = Color(0xFFCCCCCC)
                    )
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "已阅读并同意",
                    color = if (privacyChecked) Color.Black else Color.Black,
                    fontSize = 12.sp
                )
                Spacer(modifier = Modifier.width(2.dp))
                Text(
                    text = "《隐私政策》",
                    color = GameColors.SpiritBlue,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.clickableWithSound {
                        showInAppPrivacy = true
                    }
                )
            }

        }

        Text(
            text = "v${com.xianxia.sect.core.GameConfig.Game.VERSION}",
            color = Color.Black,
            fontSize = 12.sp,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 16.dp, bottom = 12.dp)
        )

        // 右上角：音乐/音效勾选项
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = 16.dp, end = 16.dp)
        ) {
            AudioToggleRow(
                soundEnabled = soundEnabled,
                musicEnabled = musicEnabled,
                onSoundToggle = onSoundToggle,
                onMusicToggle = onMusicToggle
            )
        }
    }
}


@Composable
fun ComplianceVerificationScreen(
    onStartVerification: () -> Unit,
    onLogout: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.height(100.dp))
        
        Text(
            text = "实名认证",
            style = MaterialTheme.typography.headlineLarge,
            color = Color.Black,
            fontWeight = FontWeight.Bold,
            fontSize = 32.sp
        )
        
        Spacer(modifier = Modifier.height(24.dp))
        
        Text(
            text = "根据国家防沉迷相关规定，\n需要进行实名认证后方可进入游戏。",
            color = Color.Black,
            fontSize = 16.sp,
            textAlign = TextAlign.Center,
            lineHeight = 24.sp
        )
        
        Spacer(modifier = Modifier.weight(1f))
        
        GameButton(
            text = "开始认证",
            onClick = onStartVerification
        )
        
        Spacer(modifier = Modifier.height(16.dp))
        
        GameButton(
            text = "切换账号",
            onClick = onLogout
        )
        
        Spacer(modifier = Modifier.height(100.dp))
    }
}
