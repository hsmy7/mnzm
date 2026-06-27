package com.xianxia.sect.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
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
import com.xianxia.sect.taptap.LoginData
import com.xianxia.sect.taptap.ComplianceManager
import com.xianxia.sect.ui.game.GameActivity
import com.xianxia.sect.ui.game.LoadingScreen
import com.xianxia.sect.ui.components.GameButton
import com.xianxia.sect.ui.theme.GameColors
import com.xianxia.sect.ui.theme.XianxiaTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/** 具名 ComplianceCallback 实现（避免匿名内部类触发 KSP getSimpleName NPE） */
private class MainComplianceCallback(private val activity: MainActivity) : ComplianceManager.ComplianceCallback {
    override fun onLoginSuccess() {
        activity.runOnUiThread {
            activity.sessionManager.markComplianceVerified()
            activity.showSaveSelectScreen()
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
    lateinit var crashHandler: CrashHandler
    
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
    }
    
    public sealed class ComplianceDialogState {
        data class Restrict(val title: String, val message: String) : ComplianceDialogState()
        object AgeLimit : ComplianceDialogState()
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(R.style.Theme_XianxiaSect)

        super.onCreate(savedInstanceState)

        WindowCompat.setDecorFitsSystemWindows(window, false)
        hideSystemBars()
        
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
        lifecycleScope.launch {
            // 检查 crash_flag 是否过期（超过7天视为无效，防止古早标记触发误恢复）
            if (crashHandler.hasCrashed()) {
                val crashTime = crashHandler.getCrashTime()
                val sevenDaysMs = 7 * 24 * 60 * 60 * 1000L
                if (crashTime > 0 && System.currentTimeMillis() - crashTime > sevenDaysMs) {
                    Log.w(TAG, "Crash flag expired (>7 days, crashTime=$crashTime), clearing")
                    crashHandler.clearCrashState()
                    try {
                        storageFacade.clearEmergencySaveSuspend()
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to clear emergency save for expired crash: ${e.message}")
                    }
                }
            }

            if (crashHandler.hasCrashed() && storageFacade.hasEmergencySave()) {
                Log.i(TAG, "Detected crash with emergency save, showing recovery dialog")
                showCrashRecoveryDialog()
                return@launch
            }

            if (crashHandler.hasCrashed()) {
                crashHandler.clearCrashState()
            }

            initTapTapSDK()

            if (sessionManager.isLoggedIn) {
                if (sessionManager.complianceVerified) {
                    showSaveSelectScreen()
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
                GameBackground {
                    MainScreen(
                        sessionManager = sessionManager,
                        complianceDialogState = complianceDialogState,
                        tapTapReady = tapTapReady.value,
                        onLoginSuccess = {
                            showSaveSelectScreen()
                        },
                        onPrivacyAgreed = {
                            onPrivacyAgreed()
                        }
                    )
                }
            }
        }
    }
    
    internal fun showSaveSelectScreen() {
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
            setContent {
                XianxiaTheme {
                    Surface(
                        modifier = Modifier.fillMaxSize(),
                        color = Color.White
                    ) {
                        SaveSelectScreen(
                            saveSlots = saveSlots,
                            onLoadSlot = { slot ->
                                val saveSlot = saveSlots.find { it.slot == slot }
                                if (saveSlot?.isAutoSave == true && saveSlot.isEmpty) {
                                    return@SaveSelectScreen
                                }
                                val intent = Intent(this@MainActivity, GameActivity::class.java)
                                if (saveSlot?.isEmpty == true) {
                                    intent.putExtra(EXTRA_SLOT, slot)
                                    intent.putExtra(EXTRA_NEW_GAME, true)
                                } else {
                                    intent.putExtra(EXTRA_SLOT, slot)
                                }
                                startActivity(intent)
                                finish()
                            },
                            onNewGame = { slot, sectName ->
                                val intent = Intent(this@MainActivity, GameActivity::class.java)
                                intent.putExtra(EXTRA_SLOT, slot)
                                intent.putExtra(EXTRA_NEW_GAME, true)
                                intent.putExtra(EXTRA_SECT_NAME, sectName)
                                startActivity(intent)
                                finish()
                            },
                            onDeleteSlot = { slot ->
                                lifecycleScope.launch {
                                    val result = storageFacade.delete(slot)
                                    if (result.isFailure) {
                                        android.widget.Toast.makeText(this@MainActivity, "删除存档失败", android.widget.Toast.LENGTH_SHORT).show()
                                    }
                                    showSaveSelectScreen()
                                }
                            },
                            onLogout = {
                                sessionManager.clearSession()
                                ComplianceManager.unregisterCallback()
                                recreate()
                            }
                        )
                    }
                }
            }
        }
    }
    
    /**
     * 显示崩溃恢复对话框
     * 当检测到上次游戏异常退出且存在紧急存档时调用
     * 先加载紧急存档元数据（游戏时间、宗门名），再显示对话框
     */
    private fun showCrashRecoveryDialog() {
        lifecycleScope.launch {
            // 加载紧急存档元数据以供用户判断数据新旧
            var emergencyYear = 0
            var emergencyMonth = 0
            var emergencySectName = ""
            try {
                val emergencyData = storageFacade.loadEmergencySaveSuspend()
                if (emergencyData != null) {
                    emergencyYear = emergencyData.gameData.gameYear
                    emergencyMonth = emergencyData.gameData.gameMonth
                    emergencySectName = emergencyData.gameData.sectName
                    Log.i(TAG, "Emergency save metadata: sect=$emergencySectName, " +
                        "year=$emergencyYear, month=$emergencyMonth")
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Failed to load emergency save metadata: ${e.message}")
            }

            withContext(Dispatchers.Main) {
                setContent {
                    XianxiaTheme {
                        Surface(
                            modifier = Modifier.fillMaxSize(),
                            color = Color.White
                        ) {
                            CrashRecoveryDialog(
                                gameYear = emergencyYear,
                                gameMonth = emergencyMonth,
                                sectName = emergencySectName,
                                onRecover = {
                                    recoverFromEmergencySave()
                                },
                                onDismiss = {
                                    clearCrashStateAndContinue()
                                }
                            )
                        }
                    }
                }
            }
        }
    }
    
    /**
     * 从紧急存档恢复并进入游戏
     */
    private fun recoverFromEmergencySave() {
        try {
            lifecycleScope.launch {
                val emergencyData = storageFacade.loadEmergencySaveSuspend()
                if (emergencyData != null) {
                    Log.i(TAG, "Emergency save loaded successfully, sect: ${emergencyData.gameData.sectName}, " +
                        "year=${emergencyData.gameData.gameYear}, month=${emergencyData.gameData.gameMonth}")

                    // 先保存到正常槽位，成功后再清除紧急存档和crash_flag
                    // 保证原子性：即使App在清除前被杀死，数据已在正常槽位中
                    val slot = if (emergencyData.gameData.currentSlot > 0) emergencyData.gameData.currentSlot else 1
                    storageFacade.save(slot, emergencyData)

                    storageFacade.clearEmergencySaveSuspend()
                    crashHandler.clearCrashState()

                    Log.i(TAG, "Emergency save recovered to slot $slot, emergency data cleared")

                    val intent = Intent(this@MainActivity, GameActivity::class.java)
                    intent.putExtra(EXTRA_SLOT, slot)
                    startActivity(intent)
                    finish()
                } else {
                    Log.e(TAG, "Failed to load emergency save")
                    Toast.makeText(this@MainActivity, "恢复数据失败，将进入正常游戏", Toast.LENGTH_LONG).show()
                    clearCrashStateAndContinue()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error recovering from emergency save", e)
            Toast.makeText(this, "恢复数据时发生错误", Toast.LENGTH_LONG).show()
            clearCrashStateAndContinue()
        }
    }
    
    private fun clearCrashStateAndContinue() {
        try {
            lifecycleScope.launch {
                storageFacade.clearEmergencySaveSuspend()
            }
            crashHandler.clearCrashState()
        } catch (e: Exception) {
            Log.e(TAG, "Error clearing crash state", e)
        }
        
        initTapTapSDK()
        
        if (sessionManager.isLoggedIn) {
            showSaveSelectScreen()
        } else {
            showMainScreen()
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

    override fun onResume() {
        super.onResume()
        hideSystemBars()
        requestNotificationPermissionIfNeeded()
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
    }

    override fun onDestroy() {
        loadHandler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }
}

@Composable
fun MainScreen(
    sessionManager: SessionManager,
    complianceDialogState: MutableState<MainActivity.ComplianceDialogState?>,
    tapTapReady: Boolean = false,
    onLoginSuccess: () -> Unit,
    onPrivacyAgreed: () -> Unit = {}
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
    
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(40.dp))

            Text(
                text = "模拟宗门",
                style = MaterialTheme.typography.headlineLarge,
                color = Color.Black,
                fontWeight = FontWeight.Bold,
                fontSize = 48.sp
            )

            Spacer(modifier = Modifier.weight(1f))

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
                                    unionId = unionId
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
                    modifier = Modifier.clickable {
                        showInAppPrivacy = true
                    }
                )
            }

            Spacer(modifier = Modifier.height(16.dp))
        }

        Text(
            text = "v${com.xianxia.sect.core.GameConfig.Game.VERSION}",
            color = Color.Black,
            fontSize = 12.sp,
            modifier = Modifier.align(Alignment.BottomEnd)
        )
    }
}

/**
 * 崩溃恢复对话框
 * 当检测到上次游戏异常退出时显示，询问用户是否恢复数据
 *
 * @param gameYear 紧急存档的游戏年份（0=加载失败或未知）
 * @param gameMonth 紧急存档的游戏月份
 * @param sectName 紧急存档的宗门名称
 */
@Composable
fun CrashRecoveryDialog(
    gameYear: Int = 0,
    gameMonth: Int = 0,
    sectName: String = "",
    onRecover: () -> Unit,
    onDismiss: () -> Unit
) {
    // 格式化游戏时间显示
    val gameTimeText = if (gameYear > 0) {
        "第${gameYear}年${gameMonth}月"
    } else {
        null
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.White),
        contentAlignment = Alignment.Center
    ) {
        AlertDialog(
            onDismissRequest = { },
            title = {
                Text(
                    text = "数据恢复",
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "检测到上次游戏异常退出，发现可恢复的游戏数据。",
                        fontSize = 14.sp,
                        lineHeight = 20.sp,
                        textAlign = TextAlign.Center
                    )

                    // 显示紧急存档的游戏时间信息
                    if (gameTimeText != null) {
                        Spacer(modifier = Modifier.height(12.dp))

                        Text(
                            text = "可恢复的游戏进度：",
                            fontSize = 13.sp,
                            color = Color.Black,
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = if (sectName.isNotEmpty()) {
                                "【$sectName】$gameTimeText"
                            } else {
                                gameTimeText
                            },
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFFD4380D),
                            textAlign = TextAlign.Center
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    Text(
                        text = "是否恢复上次的游戏进度？",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        textAlign = TextAlign.Center
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    Text(
                        text = "选择\"恢复\"将加载上次的游戏数据；\n选择\"不恢复\"将丢弃这些数据。",
                        fontSize = 12.sp,
                        color = Color.Black,
                        textAlign = TextAlign.Center,
                        lineHeight = 18.sp
                    )
                }
            },
            confirmButton = {
                GameButton(
                    text = "恢复",
                    onClick = onRecover
                )
            },
            dismissButton = {
                GameButton(
                    text = "不恢复",
                    onClick = onDismiss
                )
            }
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
