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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.xianxia.sect.R
import com.xianxia.sect.ui.components.ImeAnimationTracker
import com.xianxia.sect.ui.components.ImeStateMachine
import com.xianxia.sect.ui.components.ImeVisibilityTracker
import com.xianxia.sect.ui.components.SystemBarFreezeScope
import com.xianxia.sect.ui.components.SystemBarHidePolicy
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
import com.xianxia.sect.login.LoginFlowEvent
import com.xianxia.sect.login.LoginFlowHost
import com.xianxia.sect.login.LoginFlowStateMachine
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
import com.xianxia.sect.core.audio.AudioPlayerFacade
import com.xianxia.sect.core.audio.AudioPreloader
import com.xianxia.sect.core.engine.di.IoDispatcher
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * SDK 服务初始化与关键路径的解耦编排。
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
        fun attach(ctx: MainActivity) { weakActivity = java.lang.ref.WeakReference(ctx) }
    }
}

@AndroidEntryPoint
@Suppress("TooManyFunctions") // Activity 框架契约面：生命周期/权限/结果回调族（29 个 override 契约下界超类阈值）
// 必须驻留类体承载框架分发；余量为平台胶水。
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
    lateinit var audioEngine: AudioPlayerFacade

    @Inject
    lateinit var audioPreloader: AudioPreloader

    @Inject
    lateinit var ioDispatcher: IoDispatcher

    @Inject
    lateinit var adServiceImpl: com.xianxia.sect.taptap.AdServiceImpl

    @Inject
    lateinit var complianceCallbackHost: com.xianxia.sect.taptap.ComplianceCallbackHost
    
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
        /** 登录超时兜底：TapTap 授权页无回调时中止登录流程（防 loading 永久转圈） */
        private const val LOGIN_TIMEOUT_MS = 60_000L
        /**
         * 解冻后延迟恢复系统栏隐藏的等待时长（毫秒）：
         * 覆盖 Dialog 窗口销毁后键盘收起动画的剩余时长，等待 IME 状态落定
         * 再恢复隐藏，切断"键盘动画期间 hide() 对抗"。
         */
        private const val SYSTEM_BAR_RESTORE_DELAY_MS = 350L
        const val EXTRA_SLOT = "slot"
        const val EXTRA_NEW_GAME = "new_game"
        const val EXTRA_SECT_NAME = "sect_name"
        const val EXTRA_CLOUD_SAVE_LOAD = "cloud_save_load"
    }

    /**
     * 输入对话框销毁解冻 / 键盘动画结束后恢复系统栏隐藏。
     *
     * 触发源：① [SystemBarFreezeScope] 解冻监听器；② [ImeAnimationTracker] 键盘动画
     * onEnd 回调（键盘收起动画结束，含系统取消场景）。执行语义：
     * - 立即复查：键盘不可见且无动画且未冻结 → 直接恢复隐藏
     *   （docs/ime-android-system-research.md §2.3：ROM 键盘动画时长差异大，固定延时在
     *   动画 >350ms 的 ROM 上过早恢复会与残余动画对抗）
     * - 350ms 延时仅作"回调未触发/状态未落定"的兜底，执行前再经
     *   [SystemBarHidePolicy] 双守卫校验（isVisible 真值 + 动画状态）
     * onDestroy 中 loadHandler.removeCallbacksAndMessages(null) 兜底清理。
     */
    private val systemBarRestoreListener: () -> Unit = {
        if (ImeStateMachine.canRestoreSystemBars() && !SystemBarFreezeScope.isFrozen) {
            hideSystemBars()
        } else {
            loadHandler.postDelayed({
                if (!SystemBarHidePolicy.shouldSkipHide()) hideSystemBars()
            }, SYSTEM_BAR_RESTORE_DELAY_MS)
        }
    }

    /** 防沉迷验证超时任务（状态机 ScheduleVerificationTimeout 副作用驱动） */
    private var verificationTimeoutJob: Job? = null

    /** 登录超时任务（状态机 ScheduleLoginTimeout 副作用驱动） */
    private var loginTimeoutJob: Job? = null

    /** 状态机副作用宿主：唯一执行 Android 侧动作的入口（先于状态机声明——构造依赖） */
    private val loginFlowHost = object : LoginFlowHost {
        override fun onStartComplianceVerification(unionId: String) {
            // 防御：SDK 未就绪则不启动（30s 超时兜底回 VerificationFailed 可重试）
            if (!TapTapAuthManager.isReady()) {
                Log.w(TAG, "防沉迷验证启动前 TapTap SDK 未就绪，等待超时兜底")
                return
            }
            // 回调注册与验证启动原子绑定：注册失败自愈重试，保证验证结果回调可达
            ComplianceManager.ensureCallbackRegistered(complianceCallbackHost.callback)
            ComplianceManager.startup(this@MainActivity, unionId)
        }

        override fun onShowComplianceVerificationScreen() {
            showComplianceVerificationScreen()
        }

        override fun onShowModeSelection() {
            showModeSelectionScreen()
        }

        override fun onShowLoginScreen() {
            showMainScreen()
        }

        override fun onClearSessionAndLogout() {
            performFullLogout()
        }

        override fun onShowToast(message: String) {
            Toast.makeText(this@MainActivity, message, Toast.LENGTH_LONG).show()
        }

        override fun onRecoverSdkRunningState() {
            runCatching { ComplianceManager.exit() }
            runCatching { ComplianceManager.resetSdkRunningState() }
        }

        override fun onSetVerificationTimeout(active: Boolean) {
            if (active) {
                scheduleVerificationTimeout()
            } else {
                verificationTimeoutJob?.cancel()
                verificationTimeoutJob = null
            }
        }

        override fun onSetLoginTimeout(active: Boolean) {
            if (active) {
                scheduleLoginTimeout()
            } else {
                loginTimeoutJob?.cancel()
                loginTimeoutJob = null
            }
        }

        override fun onLog(message: String) {
            Log.i(TAG, message)
        }
    }

    /**
     * 登录/防沉迷验证流程状态机（唯一真相源，替代散落的手工布尔标志）。
     *
     * 重构背景：complianceCheckInFlight / complianceCheckDeferredStarted 等手工状态
     * 生命周期互不约束——一次性标记永不复位导致"退出认证/切换账号后再登录"验证被
     * 永久跳过（根因 B）；回调注册与 SDK 就绪无统一契约导致冷启动注册失败后永久
     * 失去回调（根因 A）。状态机以转移表收敛全部状态与副作用，详见
     * docs/login-flow-state-machine.md。
     */
    internal val loginFlowStateMachine = LoginFlowStateMachine(loginFlowHost)

    /** 合规回调窗口端口（登录窗口适配器，宿主按接口转发） */
    private val complianceWindowPort = object : com.xianxia.sect.taptap.ComplianceCallbackHost.WindowPort {
        override fun postToUi(block: () -> Unit) = this@MainActivity.runOnUiThread(block)
        override fun isAlive(): Boolean = !isFinishing && !isDestroyed
        override fun onLoginSuccess() = this@MainActivity.onComplianceLoginSuccess()
        override fun onExited() = this@MainActivity.onComplianceExited()
        override fun onNetworkError() = this@MainActivity.onComplianceNetworkError()
        override fun onRestrict(title: String, message: String) =
            this@MainActivity.showComplianceRestrict(title, message)
        override fun onAgeLimit() = this@MainActivity.showComplianceAgeLimit()
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
        // 键盘可见性跟踪（键盘弹出期间冻结系统栏隐藏）
        ImeVisibilityTracker.attach(window)
        // 键盘显隐动画跟踪（动画期系统栏零切换 +
        // 动画结束回调驱动系统栏恢复）
        ImeAnimationTracker.attach(window)
        // 输入对话框销毁解冻 / 键盘动画结束后恢复系统栏隐藏
        SystemBarFreezeScope.addOnUnfreezeListener(systemBarRestoreListener)
        ImeAnimationTracker.addOnAnimationEndedListener(systemBarRestoreListener)
        hideSystemBars()
        // 安装 ActionMode 安全回调，防御文本选择工具栏 BadTokenException
        installActionModeSafeCallback()

        // 登录/防沉迷流程状态机：Activity 稳定进入 RESUMED 时通知（防转场窗口期
        // startup 导致实名认证弹窗展示失败——"延迟到 RESUMED 启动"契约收敛到状态机）
        lifecycleScope.launch {
            lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                loginFlowStateMachine.onEvent(LoginFlowEvent.ActivityResumed)
            }
        }
        
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
    
    @Suppress("TooGenericExceptionCaught") // 防御兜底: 异常源跨IO/SDK不可枚举, 降级继续+日志留痕, 非静默吞噬
    private fun proceedAfterPrivacyConsent() {
        showLoadingScreen()
        startProgressAnimation()

        lifecycleScope.launch(ioDispatcher.dispatcher) {
            // 友盟统计正式初始化：此处是"已同意冷启"与"首次同意"两条路径的统一汇合点，
            // 满足"仅在用户同意隐私政策后采集数据"的合规契约（preInit 已在 Application 完成）。
            // IO 线程执行——init 内部 SP 读取/注册回调不在主线程冷启动关键路径上
            com.xianxia.sect.umeng.UmengManager.init(application)
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
                Log.e(
                    TAG,
                    "StorageFacade initialization failed after $maxRetries attempts, " +
                        "proceeding with empty cache"
                )
            }
            withContext(Dispatchers.Main) {
                isLoadComplete = true
            }
        }
    }
    
    private fun startProgressAnimation() {
        val updateRunnable = ProgressRunnable()
        ProgressRunnable.attach(this)
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

        lifecycleScope.launch(ioDispatcher.dispatcher) {
            // 仅初始化 TapTap 登录 SDK（登录按钮前置依赖，自身幂等）。
            // 广告聚合 SDK / 游戏时长统计 / 合规回调已从通用启动协程移出，
            // 只在登录成功回调（或已登录冷启动兜底）中初始化一次，
            // 避免进程销毁复用后 MainActivity 重建时重复调用 SDK 内部方法
            initTapTapLoginSdk()

            if (sessionManager.isLoggedIn) {
                // 已登录冷启动兜底：补做一次 SDK 服务初始化（广告聚合 SDK / 时长统计 /
                // 合规回调注册——登录发生在上个进程，本进程未经过登录成功回调，不补做则
                // 游戏内激励视频广告全部失效）。解耦契约（safeRunAfterSdkInit）：
                // 初始化失败只记日志，不得阻断主流程
                safeRunAfterSdkInit(
                    initSdkServices = { ensureSdkServicesInitialized() },
                    onInitFailed = { e -> Log.e(TAG, "SDK 服务初始化异常（不影响主流程）", e) },
                    block = {}
                )
                // 等待登录 SDK 就绪（"SDK 调用前必须就绪"契约——冷启动路径合规回调
                // 注册早于 SDK 就绪会注册失败并永久失去回调），再经状态机 ColdStart
                // 事件路由：已验证 → 直接进模式选择；未验证 → 显示实名认证界面手动重试
                awaitTapTapSdkReady()
                withContext(Dispatchers.Main) {
                    loginFlowStateMachine.onEvent(
                        LoginFlowEvent.ColdStart(
                            complianceVerified = sessionManager.complianceVerified,
                            unionId = sessionManager.unionId
                        )
                    )
                }
                return@launch
            }

            withContext(Dispatchers.Main) {
                showMainScreen()
            }
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
                        // 登出统一入口：状态机 LogoutRequested → ClearSessionAndLogout
                        //（清会话 + 清 TapTap SDK 登录态 + 停时长统计 + 解绑合规回调）+
                        // ShowLoginScreen。不再依赖 recreate() 重置状态——状态机自身复位
                        loginFlowStateMachine.onEvent(LoginFlowEvent.LogoutRequested)
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
    @Suppress("TooGenericExceptionCaught") // 防御兜底: 异常源跨IO/SDK不可枚举, 降级继续+日志留痕, 非静默吞噬
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
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e // 取消穿透: 画面退出取消时上抛, 不以 null 冒充"无云存档"
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
    @Suppress("TooGenericExceptionCaught") // 防御兜底: 异常源跨IO/SDK不可枚举, 降级继续+日志留痕, 非静默吞噬
    private fun initTapTapLoginSdk() {
        lifecycleScope.launch(ioDispatcher.dispatcher) {
            try {
                // 幂等守卫：SDK 全局初始化仅进程内首次执行，MainActivity 重建
                // （登出/合规切换/系统回收重建）不重复初始化
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
     * 合规回调注册移到 SDK 就绪之后（awaitTapTapSdkReady 完成）再执行，且经
     * runCatching 全量兜底（含 Error 类）——注册失败只记日志，不阻断后续步骤；
     * 即使此处失败，状态机 startup 前置的 [complianceCallbackHost] 自愈补注册
     * 仍保证验证结果回调可达。广告 SDK 与时长统计异步执行（DirichletSdk.init
     * 为异步 API，不阻塞）。
     *
     * 契约：**永不抛出**（本方法位于登录/主流程关键路径，不得有能力阻断后续
     * 步骤；调用方统一经 [safeRunAfterSdkInit] 编排，双保险 + 语义测试守护）。
     */
    internal fun ensureSdkServicesInitialized() {
        lifecycleScope.launch(ioDispatcher.dispatcher) {
            // 冷启动路径下本协程与 initTapTapLoginSdk 并发，广告聚合 SDK 依赖
            // TapTapKit.context（TapTapAuthManager.init 反射兜底），必须先等其就绪；
            // 登录成功路径 SDK 必已就绪（login 前置检查），零等待
            awaitTapTapSdkReady()
            // 合规回调注册经进程级宿主（ComplianceCallbackHost.callback）——
            // 回调不绑定 MainActivity 实例，登录后 MainActivity finish 不影响
            // 游戏内时长/时间/年龄限制提示（转发到当前前台窗口）。
            // 注册必须在 SDK 就绪之后（awaitTapTapSdkReady 完成）：注册早于 SDK 就绪
            // 会失败并永久失去回调；即使此处失败，startup 前置的
            // ensureCallbackRegistered（onStartComplianceVerification）会自愈重试
            withContext(Dispatchers.Main) {
                runCatching {
                    ComplianceManager.ensureCallbackRegistered(complianceCallbackHost.callback)
                }.onFailure {
                    Log.e(TAG, "合规回调注册异常（不影响后续流程）", it)
                }
            }
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
    
    // ── 合规回调处理（进程级宿主转发入口） ──
    // 回调注册已由 ComplianceCallbackHost 进程级持有，本组方法仅承担 UI 响应；
    // 登录流程回调转发为状态机事件（状态转移与副作用由 LoginFlowStateMachine 统一管理）。

    /** 合规验证成功（登录窗口回调）：状态机 VerificationSuccess → 进模式选择 */
    internal fun onComplianceLoginSuccess() {
        Log.i(TAG, "防沉迷验证成功（CODE_LOGIN_SUCCESS）")
        sessionManager.markComplianceVerified()
        loginFlowStateMachine.onEvent(LoginFlowEvent.VerificationSuccess)
    }

    /** SDK 要求退出（退出/切换账号/实名停止统一入口，登录窗口回调）：状态机 VerificationExited → 统一登出 */
    internal fun onComplianceExited() {
        loginFlowStateMachine.onEvent(LoginFlowEvent.VerificationExited)
    }

    /** 合规网络异常（登录窗口回调）：状态机 VerificationNetworkError → 保留会话可重试 */
    internal fun onComplianceNetworkError() {
        Log.w(TAG, "防沉迷验证网络异常（CODE_NETWORK_ERROR）")
        loginFlowStateMachine.onEvent(LoginFlowEvent.VerificationNetworkError)
    }

    /** 时间/时长限制弹窗（限制类回调，主界面窗口） */
    internal fun showComplianceRestrict(title: String, message: String) {
        complianceDialogState.value = ComplianceDialogState.Restrict(title, message)
    }

    /** 适龄限制弹窗（限制类回调，主界面窗口） */
    internal fun showComplianceAgeLimit() {
        complianceDialogState.value = ComplianceDialogState.AgeLimit
    }

    /**
     * 防沉迷验证超时兜底：SDK 静默失败（startup 后无任何回调）时经状态机
     * VerificationTimeout 事件进入可恢复流程（恢复 SDK 运行状态 + 实名认证界面重试）。
     * 验证成功（[SessionManager.complianceVerified] 置位）或任务取消后自动失效。
     */
    private fun scheduleVerificationTimeout() {
        verificationTimeoutJob?.cancel()
        verificationTimeoutJob = lifecycleScope.launch {
            delay(COMPLIANCE_TIMEOUT_MS)
            if (!sessionManager.complianceVerified && !isFinishing && !isDestroyed) {
                Log.w(TAG, "防沉迷验证超时（SDK 静默失败无回调），进入可恢复流程")
                loginFlowStateMachine.onEvent(LoginFlowEvent.VerificationTimeout)
            }
        }
    }

    /** 登录超时兜底：TapTap 授权页无回调（SDK 异常/网络问题）时中止登录流程，防 loading 永久转圈 */
    private fun scheduleLoginTimeout() {
        loginTimeoutJob?.cancel()
        loginTimeoutJob = lifecycleScope.launch {
            delay(LOGIN_TIMEOUT_MS)
            if (!isFinishing && !isDestroyed) {
                Log.w(TAG, "登录超时（TapTap 授权页无回调），中止登录流程")
                loginFlowStateMachine.onEvent(LoginFlowEvent.LoginTimeout)
            }
        }
    }

    /**
     * 登出统一四件套（唯一实现点）：清本地会话 + 清 TapTap SDK 登录态 + 停时长统计 +
     * 解绑合规回调。所有登出入口经状态机 LogoutRequested 事件汇聚到此处，杜绝
     * "登出不完整 → 残留会话使再次登录走静默登录 → 防沉迷验证不触发"的回归。
     * 界面切换（回登录界面）由状态机 ShowLoginScreen 副作用负责。
     */
    private fun performFullLogout() {
        sessionManager.clearSession()
        TapTapAuthManager.logout()
        TapDBManager.stopGameDurationTracking()
        ComplianceManager.unregisterCallback()
    }

    /** 登出请求入口（文件内顶层 Composable 回调使用）：统一转发状态机 LogoutRequested */
    internal fun requestLogout() {
        loginFlowStateMachine.onEvent(LoginFlowEvent.LogoutRequested)
    }
    
    private fun showComplianceVerificationScreen() {
        setContent {
            XianxiaTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = Color.White
                ) {
                    ComplianceVerificationScreen(
                        onStartVerification = {
                            // SDK 就绪契约：重试前先等待登录 SDK 就绪（防 TapTapKit.context
                            // 未就绪时 startup 静默失败），就绪后经状态机 RetryVerification
                            // 重新发起验证（状态机自身保证单飞与可重试）
                            lifecycleScope.launch {
                                awaitTapTapSdkReady()
                                if (TapTapAuthManager.isReady()) {
                                    loginFlowStateMachine.onEvent(LoginFlowEvent.RetryVerification)
                                } else {
                                    Toast.makeText(
                                        this@MainActivity,
                                        "TapTap SDK 初始化中，请稍后重试",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                }
                            }
                        },
                        onLogout = {
                            // 登出统一入口：状态机 LogoutRequested（清会话 + 清 SDK 登录态 +
                            // 停时长统计 + 解绑回调 + 回登录界面）
                            loginFlowStateMachine.onEvent(LoginFlowEvent.LogoutRequested)
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
        // 注册登录窗口（合规回调宿主转发目标；onStop 清除）
        complianceCallbackHost.registerLoginWindow(complianceWindowPort)
        hideSystemBars()
        requestNotificationPermissionIfNeeded()
        if (audioConfig.musicEnabled && ::audioEngine.isInitialized && audioEngine.isReady) {
            audioEngine.resumeBGM()
        }
    }

    /**
     * Android 13+：直接弹出系统通知权限请求（无中间提示框）。
     *
     * 在进入应用时（隐私同意后）直接调用 [requestPermissions]，无中间提示框。
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

    override fun onStart() {
        super.onStart()
        // 回到前台：复位销毁态，恢复文本选择 ActionMode 能力
        actionModeTracker?.resetForResume()
    }

    override fun onStop() {
        // 与 GameActivity 对齐：进入后台前结束文本选择 ActionMode，
        // 缩小窗口 token 失效期间的崩溃窗口（Bugly #3026）
        actionModeTracker?.finishActiveActionMode()
        // 清除登录窗口注册（新 Activity onResume 先于旧 Activity onStop，
        // 窗口切换期间宿主转发无缝衔接）
        complianceCallbackHost.clearLoginWindow(complianceWindowPort)
        super.onStop()
    }

    override fun onDestroy() {
        SystemBarFreezeScope.removeOnUnfreezeListener(systemBarRestoreListener)
        // 注销动画结束监听 + 解除 IME 窗口跟踪（detach 对称防全局状态残留）
        ImeAnimationTracker.removeOnAnimationEndedListener(systemBarRestoreListener)
        ImeAnimationTracker.detach(window)
        ImeVisibilityTracker.detach(window)
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
// 屏幕级入口函数：登录/合规/音频等跨模块参数分组会破坏调用语义
@Suppress("UnusedParameter", "LongParameterList")
fun MainScreen(
    sessionManager: SessionManager,
    complianceDialogState: MutableState<ComplianceDialogState?>,
    tapTapReady: Boolean = false,
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

/** 登录列内容：加载指示 / 进入游戏按钮 / 错误提示 / 隐私勾选 */
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
    Box(modifier = Modifier.fillMaxSize()) {
        // 底部：加载指示 / 进入游戏按钮 + 隐私勾选行
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp)
                .padding(bottom = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Bottom
        ) {
            if (isLoading) {
                CircularProgressIndicator()
                Spacer(modifier = Modifier.height(16.dp))
                Text("正在登录...", color = Color.Black)
            } else {
                EnterGameButton(
                    context = context,
                    sessionManager = sessionManager,
                    privacyChecked = privacyChecked,
                    tapTapReady = tapTapReady,
                    onLoadingChange = onLoadingChange,
                    onLoginError = onLoginError
                )
            }

            PrivacyAgreementRow(
                privacyChecked = privacyChecked,
                onCheckedChange = onPrivacyCheckedChange,
                onShowPrivacy = onShowPrivacy
            )
        }

        // 屏幕中央：登录错误提示
        loginResult?.let { error ->
            Text(
                text = error,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(horizontal = 24.dp)
            )
        }
    }
}

/**
 * 合规限制弹窗"退出游戏/切换账号"：统一经状态机 LogoutRequested（清会话 + 清 TapTap
 * SDK 登录态 / 停时长统计 / 解绑合规回调 + 回登录界面），不再各写四件套 + recreate。
 */
private fun performComplianceLogout(context: Context) {
    (context as? MainActivity)?.requestLogout()
}

/** 隐私政策展示 + 合规限制对话框（对话框本体为共享组件 ComplianceLimitDialogs） */
@Composable
private fun MainComplianceDialogs(
    showInAppPrivacy: Boolean,
    onBackFromPrivacy: () -> Unit,
    complianceDialogState: MutableState<ComplianceDialogState?>,
    context: Context
) {
    if (showInAppPrivacy) {
        FullPrivacyPolicyScreen(onBack = onBackFromPrivacy)
        return
    }

    ComplianceLimitDialogs(
        complianceDialogState = complianceDialogState,
        onLogout = { performComplianceLogout(context) },
        onAgeFinish = { (context as? MainActivity)?.finish() }
    )
}

/** 进入游戏按钮：隐私校验 + 平台自动登录 + 会话保存 + 合规检查触发 */
@Composable
private fun EnterGameButton(
    context: Context,
    sessionManager: SessionManager,
    privacyChecked: Boolean,
    tapTapReady: Boolean,
    onLoadingChange: (Boolean) -> Unit,
    onLoginError: (String?) -> Unit
) {
    Image(
        painter = painterResource(id = R.drawable.btn_enter_game),
        contentDescription = "进入游戏",
        modifier = Modifier
            .width(310.dp)
            .clickableWithSound {
                if (!privacyChecked) {
                    Toast.makeText(context, "请先阅读并同意隐私政策", Toast.LENGTH_SHORT).show()
                    return@clickableWithSound
                }

                if (!tapTapReady) {
                    Toast.makeText(context, "TapTap SDK 正在初始化，请稍后再试", Toast.LENGTH_SHORT).show()
                    return@clickableWithSound
                }

                onLoadingChange(true)
                onLoginError(null)

                val activity = context as? MainActivity
                if (activity == null) {
                    onLoadingChange(false)
                    Toast.makeText(context, "登录失败", Toast.LENGTH_SHORT).show()
                    return@clickableWithSound
                }

                // 登录请求进入状态机（LoggingIn 态 + 登录超时兜底）
                activity.loginFlowStateMachine.onEvent(LoginFlowEvent.LoginRequested)

                TapTapAuthManager.login(activity, object : TapTapAuthManager.LoginResultCallback {
                    override fun onSuccess(data: LoginData) {
                        // TapTap SDK 回调线程不保证主线程：全部 UI/状态操作经 runOnUiThread 收敛
                        //（历史缺陷：Compose 状态/Toast 在后台线程写入）
                        val act = context as? MainActivity
                        act?.runOnUiThread {
                            Log.d("MainScreen", "登录成功: ${data.name}")

                            val unionId = data.unionid
                            if (unionId.isNullOrEmpty()) {
                                Log.e("MainScreen", "unionId为空，登录失败")
                                onLoadingChange(false)
                                Toast.makeText(context, "登录失败，请重试", Toast.LENGTH_SHORT).show()
                                return@runOnUiThread
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
                            // 登录成功回调：一次性初始化广告/统计/合规服务（进程级幂等）。
                            // 解耦契约（safeRunAfterSdkInit）：初始化异常只记日志，
                            // 不得阻断防沉迷验证（状态机接管后续流程）
                            safeRunAfterSdkInit(
                                initSdkServices = { act.ensureSdkServicesInitialized() },
                                onInitFailed = { e ->
                                    Log.e("MainActivity", "SDK 服务初始化异常（不影响登录流程）", e)
                                },
                                // 登录成功：状态机 LoginSuccess → VerifyPending，等 Activity
                                // 稳定 RESUMED（repeatOnLifecycle 通知）后启动防沉迷验证——
                                // 转场窗口期 startup 导致实名认证弹窗展示失败的问题由状态机
                                // 前置（resumedReady 新会话复位）+ 超时兜底双重防护。
                                // 仅当登录成功时 Activity 已稳定 RESUMED（静默登录等无
                                // 授权页转场场景）才补发 ActivityResumed 立即启动验证
                                block = {
                                    act.loginFlowStateMachine.onEvent(LoginFlowEvent.LoginSuccess(unionId))
                                    if (act.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                                        act.loginFlowStateMachine.onEvent(LoginFlowEvent.ActivityResumed)
                                    }
                                }
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
        contentScale = ContentScale.Fit
    )
}

/** 隐私政策勾选行 */
@Composable
private fun PrivacyAgreementRow(
    privacyChecked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    onShowPrivacy: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
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
