package com.xianxia.sect.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.view.View
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
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
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import com.xianxia.sect.R
import com.xianxia.sect.core.util.ImeVisibilityTracker
import com.xianxia.sect.core.util.SystemBarHidePolicy
import com.xianxia.sect.ui.components.DialogFocusGuard
import com.xianxia.sect.ui.components.SystemBarFreezeScope
import com.xianxia.sect.ui.components.canRenderDialogs
import com.xianxia.sect.ui.components.GameBackground
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.xianxia.sect.BuildConfig
import com.xianxia.sect.data.SessionManager
import com.xianxia.sect.data.facade.StorageFacade
import com.xianxia.sect.data.model.SaveSlot
import com.xianxia.sect.taptap.TapTapAuthManager
import com.xianxia.sect.taptap.TapCloudSaveManager
import com.xianxia.sect.taptap.LoginData
import com.xianxia.sect.taptap.ComplianceManager
import com.xianxia.sect.taptap.TapDBManager
import com.xianxia.sect.ui.game.GameActivity
import com.xianxia.sect.ui.game.LoadingScreen
import com.xianxia.sect.ui.util.ActionModeSafeCallback
import com.xianxia.sect.ui.components.GameButton
import com.xianxia.sect.ui.components.AudioToggleRow
import androidx.compose.runtime.CompositionLocalProvider
import com.xianxia.sect.ui.components.LocalPlayClickSound
import com.xianxia.sect.ui.components.clickableWithSound
import com.xianxia.sect.ui.model.SaveSelectMode
import com.xianxia.sect.ui.theme.GameColors
import com.xianxia.sect.ui.theme.XianxiaTheme
import com.xianxia.sect.core.audio.AudioConfig
import com.xianxia.sect.core.audio.AudioEngine
import com.xianxia.sect.core.audio.AudioPreloader
import com.xianxia.sect.core.engine.di.IoDispatcher
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * SDK 服务初始化与关键路径的解耦编排（2026-08-15 回归教训固化）。
 *
 * 广告/统计/合规回调注册与登录无因果关系，其初始化失败不得阻断后续关键步骤
 * （防沉迷验证启动、界面跳转）。本函数保证：初始化抛任何 [Exception] 时记录
 * [onInitFailed] 日志后**仍执行** [block]；[kotlinx.coroutines.CancellationException]
 * 始终重新抛出（协程取消语义不被吞）；[Error] 不拦截（致命缺陷应崩溃暴露）。
 *
 * 语义守护见 `SafeRunAfterSdkInitTest`——未来改动此编排不得破坏
 * "初始化异常不阻断关键步骤"契约。
 */
internal fun safeRunAfterSdkInit(
    initSdkServices: () -> Unit,
    onInitFailed: (Throwable) -> Unit,
    block: () -> Unit
) {
    try {
        initSdkServices()
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
        // Exception 全量兜底是契约本身：初始化来源多样（SDK/守卫/日志），
        // 无法枚举具体类型；任何异常都不得阻断关键步骤
        onInitFailed(e)
    }
    block()
}

/** 具名 ComplianceCallback 实现（避免匿名内部类触发 KSP getSimpleName NPE） */
private class MainComplianceCallback(private val activity: MainActivity) : ComplianceManager.ComplianceCallback {
    /** 回调经 SDK 线程到达，销毁窗口期执行会导致 Dialog.show BadToken（Bugly #3098） */
    private fun runOnUiThreadIfAlive(block: () -> Unit) {
        activity.runOnUiThread {
            if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread
            block()
        }
    }
    override fun onLoginSuccess() {
        runOnUiThreadIfAlive {
            activity.sessionManager.markComplianceVerified()
            activity.showModeSelectionScreen()
        }
    }
    override fun onExited() = activity.handleUserExit()
    override fun onSwitchAccount() = activity.handleUserExit()
    override fun onPeriodRestrict() {
        runOnUiThreadIfAlive {
            activity.sessionManager.complianceVerified = false
            activity.complianceDialogState.value = MainActivity.ComplianceDialogState.Restrict(
                "时间限制", "根据防沉迷规定，未成年人仅可在周五、周六、周日及法定节假日的20:00-21:00进行游戏。"
            )
        }
    }
    override fun onDurationLimit() {
        runOnUiThreadIfAlive {
            activity.sessionManager.complianceVerified = false
            activity.complianceDialogState.value = MainActivity.ComplianceDialogState.Restrict(
                "时长限制", "您今日的游戏时长已用尽，请合理安排游戏时间。"
            )
        }
    }
    override fun onAgeLimit() {
        runOnUiThreadIfAlive {
            activity.sessionManager.complianceVerified = false
            activity.complianceDialogState.value = MainActivity.ComplianceDialogState.AgeLimit
        }
    }
    override fun onNetworkError() {
        runOnUiThreadIfAlive {
            Toast.makeText(activity, "网络连接异常，请检查网络后重试", Toast.LENGTH_LONG).show()
            activity.showMainScreen()
        }
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

    @Inject
    lateinit var ioDispatcher: IoDispatcher

    @Inject
    lateinit var adServiceImpl: com.xianxia.sect.taptap.AdServiceImpl
    
    public var complianceDialogState = mutableStateOf<ComplianceDialogState?>(null)
    /** TapTap SDK 初始化就绪状态，登录按钮需此标记为 true 才可点击 */
    internal var tapTapReady = mutableStateOf(false)
    internal val loadingProgress = mutableFloatStateOf(0f)
    internal var isLoadComplete = false
    internal val loadHandler = android.os.Handler(android.os.Looper.getMainLooper())
    
    companion object {
        private const val TAG = "MainActivity"
        private const val REQUEST_CODE_POST_NOTIFICATIONS = 1001
        /** 等待 TapTap 登录 SDK 就绪的超时（广告聚合 SDK 依赖 TapTapKit.context） */
        private const val TAP_SDK_READY_WAIT_MS = 5_000L
        /** TapTap 登录 SDK 就绪轮询间隔 */
        private const val TAP_SDK_READY_POLL_INTERVAL_MS = 100L
        /** 防沉迷验证超时兜底：SDK 静默失败（无任何回调）时提示用户，避免死卡登录界面 */
        private const val COMPLIANCE_TIMEOUT_MS = 30_000L
        const val EXTRA_SLOT = "slot"
        const val EXTRA_NEW_GAME = "new_game"
        const val EXTRA_SECT_NAME = "sect_name"
        const val EXTRA_CLOUD_SAVE_LOAD = "cloud_save_load"
    }

    /** 输入对话框销毁解冻后恢复系统栏隐藏（荣耀X70键盘频闪根治） */
    private val systemBarRestoreListener: () -> Unit = { hideSystemBars() }

    /** 防沉迷验证超时兜底任务（验证成功回调后自动失效） */
    private var complianceTimeoutJob: Job? = null
    
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
        // 键盘可见性跟踪（荣耀X70键盘频闪根治：键盘弹出期间冻结系统栏隐藏）
        ImeVisibilityTracker.attach(window)
        // 输入对话框销毁解冻后恢复系统栏隐藏
        SystemBarFreezeScope.addOnUnfreezeListener(systemBarRestoreListener)
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
        
        lifecycleScope.launch(ioDispatcher.dispatcher) {
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
            // 仅初始化 TapTap 登录 SDK（登录按钮前置依赖，自身幂等）。
            // 广告聚合 SDK / 游戏时长统计 / 合规回调已从通用启动协程移出，
            // 只在登录成功回调（或已登录冷启动兜底）中初始化一次，
            // 避免进程销毁复用后 MainActivity 重建时重复调用 SDK 内部方法
            initTapTapLoginSdk()

            if (sessionManager.isLoggedIn) {
                // 已登录冷启动兜底：登录发生在上个进程（进程销毁复用），
                // 本进程未经过登录成功回调，在此补做一次 SDK 服务初始化。
                // 解耦契约（safeRunAfterSdkInit）：初始化失败只记日志，
                // 不得阻断主流程（界面跳转/合规验证）
                safeRunAfterSdkInit(
                    initSdkServices = { ensureSdkServicesInitialized() },
                    onInitFailed = { e -> Log.e(TAG, "SDK 服务初始化异常（不影响主流程）", e) },
                    block = {
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
                    }
                )
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
                        // 完整登出（对齐 handleUserExit）：清 TapTap SDK 登录态——否则残留
                        // 会话使再次登录走"静默登录"（不弹登录页），防沉迷验证不触发
                        // 导致卡在登录界面；停时长统计
                        TapTapAuthManager.logout()
                        TapDBManager.stopGameDurationTracking()
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
            val saveSlots = loadSaveSlotsForSelect()
            val cloudInfo = queryCloudSaveInfo()
            renderSaveSelectScreen(mode, saveSlots, cloudInfo)
        }
    }

    /** 渲染存档选择界面（含全部存档操作回调） */
    private fun renderSaveSelectScreen(
        mode: SaveSelectMode,
        saveSlots: List<SaveSlot>,
        cloudInfo: TapCloudSaveManager.CloudSaveInfo?
    ) {
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
                            launchGame(slot = slot)
                        },
                        onCloudSaveLoad = {
                            if (!sessionManager.isLoggedIn) {
                                Toast.makeText(
                                    this@MainActivity, "请先登录 TapTap",
                                    Toast.LENGTH_SHORT
                                ).show()
                                return@SaveSelectScreen
                            }
                            launchGame(cloudLoad = true)
                        },
                        onNewGame = { slot, sectName ->
                            launchGame(
                                slot = slot,
                                newGame = true,
                                sectName = sectName
                            )
                        },
                        onDeleteSlot = { slot ->
                            lifecycleScope.launch {
                                withContext(ioDispatcher.dispatcher) {
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
    
    /** 携带存档参数启动游戏 Activity（slot/新游戏/云存档 三选一或组合） */
    private fun launchGame(
        slot: Int? = null,
        newGame: Boolean = false,
        sectName: String? = null,
        cloudLoad: Boolean = false
    ) {
        val intent = Intent(this, GameActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            if (slot != null) putExtra(EXTRA_SLOT, slot)
            if (newGame) {
                putExtra(EXTRA_NEW_GAME, true)
                putExtra(EXTRA_SECT_NAME, sectName)
            }
            if (cloudLoad) putExtra(EXTRA_CLOUD_SAVE_LOAD, true)
        }
        startActivity(intent)
        finish()
    }

    /** 加载全部存档槽位（失败返回空列表） */
    private suspend fun loadSaveSlotsForSelect(): List<SaveSlot> {
        return withContext(ioDispatcher.dispatcher) {
            try {
                storageFacade.getSaveSlotsSuspend()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "getSaveSlots failed, returning empty list", e)
                emptyList()
            }
        }
    }

    /** 查询云存档信息（异步，失败则静默跳过） */
    private suspend fun queryCloudSaveInfo(): TapCloudSaveManager.CloudSaveInfo? {
        return withContext(ioDispatcher.dispatcher) {
            try {
                tapCloudSaveManager.checkCloudSave()
            } catch (_: Exception) {
                null
            }
        }
    }

    /**
     * 初始化 TapTap 登录 SDK（登录按钮前置依赖，必须在登录发起前就绪）。
     *
     * 广告聚合 SDK / 游戏时长统计 / 合规回调不在此处初始化——它们已收敛到
     * [ensureSdkServicesInitialized]（登录成功回调 + 已登录冷启动兜底），
     * 避免进程销毁复用后 MainActivity 重建时重复调用 SDK 内部方法。
     */
    private fun initTapTapLoginSdk() {
        lifecycleScope.launch(ioDispatcher.dispatcher) {
            try {
                // 幂等守卫：SDK 全局初始化仅进程内首次执行，MainActivity 重建
                // （登出/合规切换/系统回收重建）不重复初始化（广告公司反馈
                // "重复初始化"同类问题一并根治）
                if (com.xianxia.sect.taptap.SdkInitGuard.tryInitTapTapSdk()) {
                    TapTapAuthManager.init(
                        this@MainActivity,
                        BuildConfig.TAPTAP_CLIENT_ID,
                        BuildConfig.TAPTAP_CLIENT_TOKEN,
                        BuildConfig.TAPTAP_IS_CN
                    )
                    Log.d(TAG, "TapTap SDK初始化成功，就绪状态: ${TapTapAuthManager.isReady()}")
                } else {
                    Log.d(TAG, "TapTap SDK already initialized, skipping")
                }
                // 反射验证 context 并通过 isReady() 双重确认（登录按钮依赖此状态）
                tapTapReady.value = TapTapAuthManager.isReady()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: java.util.concurrent.TimeoutException) {
                // 初始化失败：释放守卫占用，允许下次 MainActivity 重建重试（防永久不可用）
                com.xianxia.sect.taptap.SdkInitGuard.releaseTapTapSdkInit()
                tapTapReady.value = false
                Log.e(TAG, "TapTap SDK初始化超时，尝试降级模式", e)
                withContext(Dispatchers.Main) {
                    showSaveSelectScreen()
                }
            } catch (e: Exception) {
                // 初始化失败：释放守卫占用，允许下次 MainActivity 重建重试（防永久不可用）
                com.xianxia.sect.taptap.SdkInitGuard.releaseTapTapSdkInit()
                tapTapReady.value = false
                Log.e(TAG, "TapTap SDK初始化失败: ${e.message}", e)
            }
        }
    }

    /**
     * 登录成功后（或已登录冷启动兜底）进程级一次性初始化 SDK 服务：
     * 广告聚合 SDK（DirichletSdk.init）、游戏时长统计（TapDB）、合规回调注册。
     *
     * 已从通用启动协程移出：进程销毁复用后 MainActivity 重建不再触发 SDK 内部
     * 初始化方法，仅在用户真实登录（或此前已登录的进程复用场景）时执行。
     * 各子系统内部自带幂等守卫（SdkInitGuard / TapDBManager / ComplianceManager），
     * 同进程内重复调用安全。
     *
     * 合规回调须在主线程同步注册且先于 [startComplianceCheck]（防验证结果回调
     * 因未注册而丢失）；广告 SDK 与时长统计异步执行（DirichletSdk.init 为异步
     * API，不阻塞）。
     *
     * 契约：**永不抛出**（registerCallback 经 runCatching 全量兜底，含 Error 类）——
     * 本方法位于登录/主流程关键路径，不得有能力阻断后续步骤；调用方统一经
     * [safeRunAfterSdkInit] 编排（双保险 + 语义测试守护）。
     */
    internal fun ensureSdkServicesInitialized() {
        runCatching {
            ComplianceManager.registerCallback(MainComplianceCallback(this@MainActivity))
        }.onFailure {
            Log.e(TAG, "合规回调注册异常（不影响后续流程）", it)
        }
        lifecycleScope.launch(ioDispatcher.dispatcher) {
            // 冷启动路径下本协程与 initTapTapLoginSdk 并发，广告聚合 SDK 依赖
            // TapTapKit.context（TapTapAuthManager.init 反射兜底），必须先等其就绪；
            // 登录成功路径 SDK 必已就绪（login 前置检查），零等待
            awaitTapTapSdkReady()
            initAdSdk()
            TapDBManager.startGameDurationTracking(application)
        }
    }

    /** 等待 TapTap 登录 SDK 就绪；超时则跳过（降级模式，SdkInitGuard 未占用可重试） */
    private suspend fun awaitTapTapSdkReady() {
        // var：轮询累加器，copy-on-write 不可行
        var waitedMs = 0L
        while (!TapTapAuthManager.isReady() && waitedMs < TAP_SDK_READY_WAIT_MS) {
            delay(TAP_SDK_READY_POLL_INTERVAL_MS)
            waitedMs += TAP_SDK_READY_POLL_INTERVAL_MS
        }
        if (!TapTapAuthManager.isReady()) {
            Log.w(TAG, "TapTap 登录 SDK 未就绪，跳过广告/统计 SDK 初始化")
        }
    }

    /**
     * 初始化 Dirichlet 聚合 Ad SDK（在 TapTapAuthManager.init() 后调用）。
     *
     * 仅在 [ensureSdkServicesInitialized]（登录成功回调 / 已登录冷启动兜底）中调用，
     * 已从通用启动协程移出——进程销毁复用后 MainActivity 重建不再重复触发
     * SDK 内部初始化方法。
     */
    private fun initAdSdk() {
        // 幂等守卫：广告聚合 SDK 全局初始化仅进程内首次执行，登出后再次登录、
        // MainActivity 重建（登出 recreate / 合规切换 recreate / 系统回收重建）
        // 都会走到本方法，无守卫时 DirichletSdk.init 被重复调用（广告公司反馈"重复初始化"）
        if (!com.xianxia.sect.taptap.SdkInitGuard.tryInitAdSdk()) {
            Log.d(TAG, "Ad SDK already initialized, skipping")
            return
        }
        try {
            val config = com.tapsdk.tapad.group.DirichletAdConfig.Builder()
                .withMediaId(1105785)
                .withMediaName("模拟宗门")
                .withMediaKey("eO9LxSkT1NqmQnzUA3Ldx7Q7c8vv54HdRSOsLrH7oy0pFsknnHSrn66xuceULxga")
                .enableDebug(BuildConfig.DEBUG)
                .shakeEnabled(true)
                .build()
            com.tapsdk.tapad.group.DirichletSdk.init(
                application,
                config,
                object : com.tapsdk.tapad.group.DirichletSdk.InitListener {
                    override fun onInitSuccess() {
                        Log.i(TAG, "Dirichlet 聚合 SDK 初始化完成（用户已同意隐私政策）")
                        // 初始化成功后同步个性化广告偏好（合规：退出个性化广告能力）
                        adServiceImpl.applyPersonalizationSetting()
                    }

                    override fun onInitFail(code: Int, message: String) {
                        Log.e(TAG, "Dirichlet 聚合 SDK 初始化失败: code=$code, message=$message")
                    }
                }
            )
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            // 同步构建失败：释放守卫占用，允许下次 MainActivity 重建重试初始化
            //（异步 onInitFail 不复位——SDK 已执行过 init，重试意义有限）
            com.xianxia.sect.taptap.SdkInitGuard.releaseAdSdkInit()
            Log.e(TAG, "Dirichlet 聚合 SDK 初始化失败: ${e.message}", e)
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
        scheduleComplianceTimeoutHint()
    }

    /**
     * 防沉迷验证超时兜底：SDK 静默失败（startup 后无任何回调，如残留会话
     * 场景）时弹提示引导重新登录，避免用户无反馈地卡在登录界面。
     * 验证成功（[SessionManager.complianceVerified] 置位）后自动失效。
     */
    private fun scheduleComplianceTimeoutHint() {
        if (complianceTimeoutJob?.isActive == true) return
        complianceTimeoutJob = lifecycleScope.launch {
            delay(COMPLIANCE_TIMEOUT_MS)
            if (!sessionManager.complianceVerified && !isFinishing && !isDestroyed) {
                Toast.makeText(
                    this@MainActivity,
                    "实名认证无响应，请退出后重新登录",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
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
                            TapDBManager.stopGameDurationTracking()
                            ComplianceManager.unregisterCallback()
                            showMainScreen()
                        }
                    )
                }
            }
        }
    }

    override fun onPause() {
        // Bugly #11017：finish 置位从 onStop 提前到 onPause——已创建的
        // FloatingActionMode 的 reposition/show 由系统消息队列驱动（不经 window
        // callback），onStop 时 finish 与已 post 的 show 存在竞态；onPause 即离场
        actionModeTracker?.finishActiveActionMode()
        super.onPause()
        audioEngine.pauseBGM()
    }

    override fun onResume() {
        super.onResume()
        // 回到前台立即恢复文本选择能力（onPause 提前置位后的配套复位）
        actionModeTracker?.resetForResume()
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
        // 双守卫（荣耀X70键盘频闪根治）：输入对话框冻结期间或键盘可见期间
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

    override fun onStart() {
        super.onStart()
        // 回到前台：复位销毁态，恢复文本选择 ActionMode 能力
        actionModeTracker?.resetForResume()
    }

    override fun onStop() {
        // 与 GameActivity 对齐：进入后台前结束文本选择 ActionMode，
        // 缩小窗口 token 失效期间的崩溃窗口（Bugly #3026）
        actionModeTracker?.finishActiveActionMode()
        super.onStop()
    }

    override fun onDestroy() {
        SystemBarFreezeScope.removeOnUnfreezeListener(systemBarRestoreListener)
        actionModeTracker?.finishActiveActionMode()
        actionModeTracker = null
        loadHandler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    // ── ActionMode 安全回调（共享实现见 com.xianxia.sect.ui.util.ActionModeSafeCallback） ──
    // 拦截 ActionMode（FloatingActionMode/文本选择工具栏）生命周期，
    // 确保在 Activity 销毁前结束活跃的 ActionMode，防止 BadTokenException。

    @Volatile
    private var actionModeTracker: ActionModeSafeCallback? = null

    private fun installActionModeSafeCallback() {
        val original = window.callback ?: return
        if (original is ActionModeSafeCallback) return
        ActionModeSafeCallback(original, applicationContext).also {
            window.callback = it
            actionModeTracker = it
        }
    }
}

@Composable
@Suppress("LongParameterList") // 屏幕级入口函数：登录/合规/音频等跨模块参数分组会破坏调用语义
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

    MainComplianceDialogs(
        showInAppPrivacy = showInAppPrivacy,
        onBackFromPrivacy = { showInAppPrivacy = false },
        complianceDialogState = complianceDialogState,
        sessionManager = sessionManager,
        context = context
    )

    Box(modifier = Modifier.fillMaxSize()) {
        // 登录界面背景图
        Image(
            painter = painterResource(id = R.drawable.login_background),
            contentDescription = "登录界面背景",
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.FillBounds
        )

        LoginColumnContent(
            context = context,
            sessionManager = sessionManager,
            isLoading = isLoading,
            loginResult = loginResult,
            privacyChecked = privacyChecked,
            onPrivacyCheckedChange = { checked ->
                privacyChecked = checked
                sessionManager.privacyCheckboxConfirmed = checked
            },
            tapTapReady = tapTapReady,
            onLoadingChange = { isLoading = it },
            onLoginError = { loginResult = it },
            onShowPrivacy = { showInAppPrivacy = true }
        )

        VersionAndAudioOverlay(
            soundEnabled = soundEnabled,
            musicEnabled = musicEnabled,
            onSoundToggle = onSoundToggle,
            onMusicToggle = onMusicToggle
        )
    }
}

/** 右下角版本号 + 右上角音乐/音效勾选浮层 */
@Composable
private fun VersionAndAudioOverlay(
    soundEnabled: Boolean,
    musicEnabled: Boolean,
    onSoundToggle: (Boolean) -> Unit,
    onMusicToggle: (Boolean) -> Unit
) {
    Box(modifier = Modifier.fillMaxSize()) {
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

/** 登录列内容：加载指示 / TapTap 登录按钮 / 错误提示 / 隐私勾选 */
@Composable
@Suppress("LongParameterList") // 登录状态与回调聚合，分组会破坏状态编排可读性
private fun LoginColumnContent(
    context: Context,
    sessionManager: SessionManager,
    isLoading: Boolean,
    loginResult: String?,
    privacyChecked: Boolean,
    onPrivacyCheckedChange: (Boolean) -> Unit,
    tapTapReady: Boolean,
    onLoadingChange: (Boolean) -> Unit,
    onLoginError: (String?) -> Unit,
    onShowPrivacy: () -> Unit
) {
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
            TapTapLoginButton(
                context = context,
                sessionManager = sessionManager,
                privacyChecked = privacyChecked,
                tapTapReady = tapTapReady,
                isLoading = isLoading,
                onLoadingChange = onLoadingChange,
                onLoginError = onLoginError
            )
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

        PrivacyAgreementRow(
            privacyChecked = privacyChecked,
            onCheckedChange = onPrivacyCheckedChange,
            onShowPrivacy = onShowPrivacy
        )
    }
}

/** 当前组合是否允许渲染 Dialog（Activity 生命周期 ≥ STARTED）。 */
@Composable
private fun dialogRenderableInComposition(): Boolean {
    val lifecycleState by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    return lifecycleState.canRenderDialogs()
}

/**
 * 合规限制弹窗"退出游戏/切换账号"：清会话 + 完整登出（清 TapTap SDK 登录态 /
 * 停时长统计 / 解绑合规回调，对齐 [MainActivity.handleUserExit]）+ 重建主界面。
 */
private fun performComplianceLogout(sessionManager: SessionManager, context: Context) {
    sessionManager.clearSession()
    TapTapAuthManager.logout()
    TapDBManager.stopGameDurationTracking()
    ComplianceManager.unregisterCallback()
    (context as? MainActivity)?.recreate()
}

/** 隐私政策展示 + 合规限制对话框（适龄限制/账号封禁提示） */
@Composable
private fun MainComplianceDialogs(
    showInAppPrivacy: Boolean,
    onBackFromPrivacy: () -> Unit,
    complianceDialogState: MutableState<MainActivity.ComplianceDialogState?>,
    sessionManager: SessionManager,
    context: Context
) {
    if (showInAppPrivacy) {
        FullPrivacyPolicyScreen(onBack = onBackFromPrivacy)
        return
    }

    // 生命周期门控：销毁窗口期渲染 AlertDialog 抛 BadToken（Bugly #3098）
    if (!dialogRenderableInComposition()) return

    complianceDialogState.value?.let { state ->
        when (state) {
            is MainActivity.ComplianceDialogState.Restrict -> {
                AlertDialog(
                    onDismissRequest = { },
                    title = { Text(state.title) },
                    text = { DialogFocusGuard(); Text(state.message) },
                    confirmButton = {
                        GameButton(
                            text = "退出游戏",
                            onClick = {
                                complianceDialogState.value = null
                                performComplianceLogout(sessionManager, context)
                            }
                        )
                    },
                    dismissButton = {
                        GameButton(
                            text = "切换账号",
                            onClick = {
                                complianceDialogState.value = null
                                performComplianceLogout(sessionManager, context)
                            }
                        )
                    }
                )
            }
            is MainActivity.ComplianceDialogState.AgeLimit -> {
                AlertDialog(
                    onDismissRequest = { },
                    title = { Text("适龄限制") },
                    text = { DialogFocusGuard(); Text("根据游戏适龄提示，您当前年龄不符合本游戏的游玩要求。") },
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
}

/** TapTap 登录按钮：隐私校验 + SDK 登录 + 会话保存 + 合规检查触发 */
@Composable
private fun TapTapLoginButton(
    context: Context,
    sessionManager: SessionManager,
    privacyChecked: Boolean,
    tapTapReady: Boolean,
    isLoading: Boolean,
    onLoadingChange: (Boolean) -> Unit,
    onLoginError: (String?) -> Unit
) {
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

            onLoadingChange(true)
            onLoginError(null)

            val activity = context as? MainActivity
            if (activity == null) {
                onLoadingChange(false)
                Toast.makeText(context, "登录失败", Toast.LENGTH_SHORT).show()
                return@Button
            }

            TapTapAuthManager.login(activity, object : TapTapAuthManager.LoginResultCallback {
                override fun onSuccess(data: LoginData) {
                    Log.d("MainScreen", "登录成功: ${data.name}")

                    val unionId = data.unionid
                    if (unionId.isNullOrEmpty()) {
                        Log.e("MainScreen", "unionId为空，登录失败")
                        onLoadingChange(false)
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

                    onLoadingChange(false)
                    val act = context as? MainActivity
                    act?.runOnUiThread {
                        // 登录成功回调：一次性初始化广告/统计/合规服务（进程级幂等）。
                        // 解耦契约（safeRunAfterSdkInit）：初始化异常只记日志，
                        // 不得阻断防沉迷验证；合规回调必须先于 startComplianceCheck
                        // 注册（防验证结果回调丢失）
                        safeRunAfterSdkInit(
                            initSdkServices = { act.ensureSdkServicesInitialized() },
                            onInitFailed = { e ->
                                Log.e("MainActivity", "SDK 服务初始化异常（不影响登录流程）", e)
                            },
                            block = { act.startComplianceCheck(unionId) }
                        )
                    }
                }

                override fun onFailure(error: Exception) {
                    Log.e("MainScreen", "登录失败: ${error.message}")
                    onLoadingChange(false)
                    onLoginError(error.message)
                    Toast.makeText(context, "登录失败: ${error.message}", Toast.LENGTH_SHORT).show()
                }
            })
        },
        modifier = Modifier
            .wrapContentWidth()
            .height(56.dp),
        shape = RoundedCornerShape(28.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = if (privacyChecked) Color(0xFF00D26A) else GameColors.DividerGray,
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

/** 隐私政策勾选行 */
@Composable
private fun PrivacyAgreementRow(
    privacyChecked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    onShowPrivacy: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(
            checked = privacyChecked,
            onCheckedChange = onCheckedChange,
            colors = CheckboxDefaults.colors(
                checkedColor = GameColors.SpiritBlue,
                uncheckedColor = GameColors.DividerGray
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
            modifier = Modifier.clickableWithSound { onShowPrivacy() }
        )
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
