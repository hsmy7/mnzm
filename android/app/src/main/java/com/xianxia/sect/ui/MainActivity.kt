package com.xianxia.sect.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import kotlinx.coroutines.launch
import com.xianxia.sect.BuildConfig
import com.xianxia.sect.core.CrashHandler
import com.xianxia.sect.data.SessionManager
import com.xianxia.sect.data.facade.RefactoredStorageFacade
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
            activity.loadingProgress.value = 1f
            activity.loadHandler.postDelayed({ activity.onLoadingComplete() }, 150)
        } else {
            val current = activity.loadingProgress.value
            if (current < 0.9f) activity.loadingProgress.value = current + 0.05f
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
    lateinit var storageFacade: RefactoredStorageFacade
    
    @Inject
    lateinit var crashHandler: CrashHandler
    
    public var complianceDialogState = mutableStateOf<ComplianceDialogState?>(null)
    internal val loadingProgress = mutableFloatStateOf(0f)
    internal var isLoadComplete = false
    internal val loadHandler = android.os.Handler(android.os.Looper.getMainLooper())
    
    companion object {
        private const val TAG = "MainActivity"
        const val EXTRA_SLOT = "slot"
        const val EXTRA_NEW_GAME = "new_game"
        const val EXTRA_SECT_NAME = "sect_name"
    }
    
    public sealed class ComplianceDialogState {
        data class Restrict(val title: String, val message: String) : ComplianceDialogState()
        object AgeLimit : ComplianceDialogState()
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        // 切换回正常主题
        setTheme(R.style.Theme_XianxiaSect)
        
        super.onCreate(savedInstanceState)
        
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).let { controller ->
            controller.hide(WindowInsetsCompat.Type.statusBars())
            controller.hide(WindowInsetsCompat.Type.navigationBars())
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        
        if (!::sessionManager.isInitialized) {
            Log.e(TAG, "SessionManager未初始化")
            finish()
            return
        }
        
        // 先显示加载界面
        showLoadingScreen()
        
        // 启动进度条动画（持续更新直到加载完成）
        startProgressAnimation()
        
        // 模拟加载完成（实际项目中应该根据真实加载状态）
        loadHandler.postDelayed({
            isLoadComplete = true
        }, 500) // 0.5秒后标记加载完成
    }
    
    private fun startProgressAnimation() {
        val updateRunnable = ProgressRunnable()
        ProgressRunnable.attach(updateRunnable, this)
        loadHandler.post(updateRunnable)
    }
    
    internal fun onLoadingComplete() {
        if (!sessionManager.hasAgreedPrivacy) {
            showPrivacyPolicyScreen()
            return
        }
        
        if (crashHandler.hasCrashed() && storageFacade.hasEmergencySave()) {
            Log.i(TAG, "Detected crash with emergency save, showing recovery dialog")
            showCrashRecoveryDialog()
            return
        }
        
        if (crashHandler.hasCrashed()) {
            Log.i(TAG, "Crash detected but no emergency save, clearing crash state")
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
            return
        }
        
        showMainScreen()
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
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = Color.White
                ) {
                    MainScreen(
                        sessionManager = sessionManager,
                        complianceDialogState = complianceDialogState,
                        onLoginSuccess = {
                            showSaveSelectScreen()
                        }
                    )
                }
            }
        }
    }
    
    private fun showPrivacyPolicyScreen() {
        setContent {
            XianxiaTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = Color.White
                ) {
                    PrivacyPolicyScreen(
                        onAgree = {
                            sessionManager.hasAgreedPrivacy = true
                            initTapTapSDK()
                            recreate()
                        },
                        onDisagree = {
                            finish()
                        }
                    )
                }
            }
        }
    }
    
    internal fun showSaveSelectScreen() {
        val saveSlots = storageFacade.getSaveSlots().filter { !it.isAutoSave }
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
                            val intent = Intent(this, GameActivity::class.java)
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
                            val intent = Intent(this, GameActivity::class.java)
                            intent.putExtra(EXTRA_SLOT, slot)
                            intent.putExtra(EXTRA_NEW_GAME, true)
                            intent.putExtra(EXTRA_SECT_NAME, sectName)
                            startActivity(intent)
                            finish()
                        },
                        onDeleteSlot = { slot ->
                            lifecycleScope.launch {
                                storageFacade.delete(slot)
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
    
    /**
     * 显示崩溃恢复对话框
     * 当检测到上次游戏异常退出且存在紧急存档时调用
     */
    private fun showCrashRecoveryDialog() {
        setContent {
            XianxiaTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = Color.White
                ) {
                    CrashRecoveryDialog(
                        onRecover = {
                            // 用户选择恢复
                            recoverFromEmergencySave()
                        },
                        onDismiss = {
                            // 用户选择不恢复
                            clearCrashStateAndContinue()
                        }
                    )
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
                val emergencyData = storageFacade.loadEmergencySave()
                if (emergencyData != null) {
                    Log.i(TAG, "Emergency save loaded successfully, sect: ${emergencyData.gameData.sectName}")
                    
                    storageFacade.clearEmergencySave()
                    crashHandler.clearCrashState()
                    
                    val slot = if (emergencyData.gameData.currentSlot > 0) emergencyData.gameData.currentSlot else 1
                    
                    storageFacade.saveSync(slot, emergencyData)
                    
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
            storageFacade.clearEmergencySave()
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
        try {
            TapTapAuthManager.init(
                this,
                BuildConfig.TAPTAP_CLIENT_ID,
                BuildConfig.TAPTAP_CLIENT_TOKEN,
                BuildConfig.TAPTAP_IS_CN
            )
            
            ComplianceManager.registerCallback(MainComplianceCallback(this))
            
            Log.d(TAG, "TapTap SDK初始化成功")
        } catch (e: Exception) {
            Log.e(TAG, "TapTap SDK初始化失败: ${e.message}")
        }
    }
    
    internal fun handleUserExit() {
        runOnUiThread {
            sessionManager.clearSession()
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

    override fun onDestroy() {
        // 清理 Handler 防止内存泄漏
        loadHandler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }
}

@Composable
fun MainScreen(
    sessionManager: SessionManager,
    complianceDialogState: MutableState<MainActivity.ComplianceDialogState?>,
    onLoginSuccess: () -> Unit
) {
    val context = LocalContext.current
    var isLoading by remember { mutableStateOf(false) }
    var loginResult by remember { mutableStateOf<String?>(null) }
    var showPrivacyDialog by remember { mutableStateOf(!sessionManager.hasAgreedPrivacy) }
    var showExitConfirm by remember { mutableStateOf(false) }
    
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
    
    if (showPrivacyDialog) {
        PrivacyPolicyDialog(
            onAgree = {
                sessionManager.hasAgreedPrivacy = true
                showPrivacyDialog = false
            },
            onDisagree = {
                showExitConfirm = true
            }
        )
    }
    
    if (showExitConfirm) {
        AlertDialog(
            onDismissRequest = { showExitConfirm = false },
            title = { Text("确认退出") },
            text = { Text("您需要同意隐私政策才能使用本应用。拒绝后应用将关闭。") },
            confirmButton = {
                GameButton(
                    text = "退出应用",
                    onClick = {
                        val activity = context as? MainActivity
                        activity?.finish()
                    }
                )
            },
            dismissButton = {
                GameButton(
                    text = "重新阅读",
                    onClick = { showExitConfirm = false }
                )
            }
        )
    }
    
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.height(40.dp))
        
        Text(
            text = "修仙宗门",
            style = MaterialTheme.typography.headlineLarge,
            color = Color.Black,
            fontWeight = FontWeight.Bold,
            fontSize = 48.sp
        )
        
        Spacer(modifier = Modifier.height(8.dp))
        
        Text(
            text = "踏入修仙之路，成就无上大道",
            color = Color(0xFF666666),
            fontSize = 16.sp
        )
        
        Spacer(modifier = Modifier.weight(1f))
        
        if (isLoading) {
            CircularProgressIndicator()
            Spacer(modifier = Modifier.height(16.dp))
            Text("正在登录...", color = Color.Black)
        } else {
            Button(
                onClick = {
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
                            
                            Toast.makeText(context, "欢迎, ${data.name}!", Toast.LENGTH_SHORT).show()
                            
                            isLoading = false
                            (context as? MainActivity)?.startComplianceCheck(unionId)
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
                    .fillMaxWidth()
                    .height(56.dp),
                shape = RoundedCornerShape(28.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF00D26A)
                )
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
        
        Spacer(modifier = Modifier.height(100.dp))
        
        Text(
            text = "v${com.xianxia.sect.core.GameConfig.Game.VERSION}",
            color = Color(0xFF999999),
            fontSize = 12.sp
        )
        
        Spacer(modifier = Modifier.height(12.dp))
        
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "登录即表示同意",
                color = Color(0xFF999999),
                fontSize = 12.sp
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = "《隐私政策》",
                color = Color.Black,
                fontSize = 12.sp,
                modifier = Modifier.clickable {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://hsmy7.github.io/index.html"))
                    context.startActivity(intent)
                }
            )
        }
        
        Spacer(modifier = Modifier.height(16.dp))
    }
}

@Composable
fun PrivacyPolicyDialog(
    onAgree: () -> Unit,
    onDisagree: () -> Unit
) {
    val context = LocalContext.current
    
    AlertDialog(
        onDismissRequest = { },
        title = { 
            Text(
                text = "隐私政策",
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
            ) {
                Text(
                    text = "欢迎使用修仙宗门！我们重视您的隐私保护，在使用本应用前，请您仔细阅读并同意我们的隐私政策。",
                    fontSize = 14.sp,
                    lineHeight = 20.sp
                )
                
                Spacer(modifier = Modifier.height(12.dp))
                
                Text(
                    text = "我们收集的信息包括：",
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp
                )
                
                Spacer(modifier = Modifier.height(8.dp))
                
                Text(
                    text = "1. 设备标识符：用于识别设备，提供游戏存档功能\n2. 网络状态：用于游戏数据同步\n3. 存储空间：用于保存游戏存档数据",
                    fontSize = 13.sp,
                    lineHeight = 20.sp,
                    color = Color(0xFF666666)
                )
                
                Spacer(modifier = Modifier.height(12.dp))
                
                Text(
                    text = "一、信息收集",
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp
                )
                
                Spacer(modifier = Modifier.height(6.dp))
                
                Text(
                    text = "1. 设备标识符：我们收集设备标识符用于识别您的设备，确保游戏存档与您的设备关联。\n2. 网络状态信息：用于判断网络连接情况。\n3. 存储空间权限：用于保存游戏存档数据到本地。",
                    fontSize = 13.sp,
                    lineHeight = 20.sp,
                    color = Color(0xFF666666)
                )
                
                Spacer(modifier = Modifier.height(12.dp))
                
                Text(
                    text = "二、信息使用",
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp
                )
                
                Spacer(modifier = Modifier.height(6.dp))
                
                Text(
                    text = "我们收集的信息仅用于游戏核心功能，不会用于其他用途。",
                    fontSize = 13.sp,
                    lineHeight = 20.sp,
                    color = Color(0xFF666666)
                )
                
                Spacer(modifier = Modifier.height(12.dp))
                
                Text(
                    text = "三、信息保护",
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp
                )
                
                Spacer(modifier = Modifier.height(6.dp))
                
                Text(
                    text = "游戏数据仅保存在您的设备本地。",
                    fontSize = 13.sp,
                    lineHeight = 20.sp,
                    color = Color(0xFF666666)
                )
                
                Spacer(modifier = Modifier.height(16.dp))
                
                GameButton(
                    text = "查看完整隐私政策",
                    onClick = {
                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://hsmy7.github.io/index.html/"))
                        context.startActivity(intent)
                    }
                )
            }
        },
        confirmButton = {
            GameButton(
                text = "同意并继续",
                onClick = onAgree
            )
        },
        dismissButton = {
            GameButton(
                text = "退出",
                onClick = onDisagree
            )
        }
    )
}

@Composable
fun PrivacyPolicyScreen(
    onAgree: () -> Unit,
    onDisagree: () -> Unit
) {
    val context = LocalContext.current
    val scrollState = rememberScrollState()
    
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Text(
            text = "隐私政策",
            fontWeight = FontWeight.Bold,
            fontSize = 20.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )
        
        Spacer(modifier = Modifier.height(16.dp))
        
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(scrollState)
        ) {
            Text(
                text = "欢迎使用模拟宗门！我们重视您的隐私保护，在使用本应用前，请您仔细阅读并同意我们的隐私政策。",
                fontSize = 14.sp,
                lineHeight = 20.sp
            )
            
            Spacer(modifier = Modifier.height(12.dp))
            
            Text(
                text = "我们收集的信息包括：",
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp
            )
            
            Spacer(modifier = Modifier.height(8.dp))
            
            Text(
                text = "1. 设备标识符：用于识别设备，提供游戏存档功能\n2. 网络状态：用于游戏数据同步\n3. 存储空间：用于保存游戏存档数据",
                fontSize = 13.sp,
                lineHeight = 20.sp,
                color = Color(0xFF666666)
            )
            
            Spacer(modifier = Modifier.height(12.dp))
            
            Text(
                text = "一、信息收集",
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp
            )
            
            Spacer(modifier = Modifier.height(6.dp))
            
            Text(
                text = "1. 设备标识符：我们收集设备标识符用于识别您的设备，确保游戏存档与您的设备关联。\n2. 网络状态信息：用于判断网络连接情况。\n3. 存储空间权限：用于保存游戏存档数据到本地。",
                fontSize = 13.sp,
                lineHeight = 20.sp,
                color = Color(0xFF666666)
            )
            
            Spacer(modifier = Modifier.height(12.dp))
            
            Text(
                text = "二、信息使用",
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp
            )
            
            Spacer(modifier = Modifier.height(6.dp))
            
            Text(
                text = "我们收集的信息仅用于游戏核心功能，不会用于其他用途。",
                fontSize = 13.sp,
                lineHeight = 20.sp,
                color = Color(0xFF666666)
            )
            
            Spacer(modifier = Modifier.height(12.dp))
            
            Text(
                text = "三、信息保护",
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp
            )
            
            Spacer(modifier = Modifier.height(6.dp))
            
            Text(
                text = "游戏数据仅保存在您的设备本地。",
                fontSize = 13.sp,
                lineHeight = 20.sp,
                color = Color(0xFF666666)
            )
            
            Spacer(modifier = Modifier.height(16.dp))
            
            GameButton(
                text = "查看完整隐私政策",
                onClick = {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://hsmy7.github.io/index.html/"))
                    context.startActivity(intent)
                }
            )
            
            Spacer(modifier = Modifier.height(16.dp))
        }
        
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            GameButton(
                text = "退出",
                onClick = onDisagree,
                modifier = Modifier.weight(1f)
            )
            
            GameButton(
                text = "同意并继续",
                onClick = onAgree,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

/**
 * 崩溃恢复对话框
 * 当检测到上次游戏异常退出时显示，询问用户是否恢复数据
 */
@Composable
fun CrashRecoveryDialog(
    onRecover: () -> Unit,
    onDismiss: () -> Unit
) {
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
                        color = Color(0xFF666666),
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
            color = Color(0xFF666666),
            fontSize = 16.sp,
            textAlign = TextAlign.Center,
            lineHeight = 24.sp
        )
        
        Spacer(modifier = Modifier.weight(1f))
        
        GameButton(
            text = "开始认证",
            onClick = onStartVerification,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
        )
        
        Spacer(modifier = Modifier.height(16.dp))
        
        GameButton(
            text = "切换账号",
            onClick = onLogout
        )
        
        Spacer(modifier = Modifier.height(100.dp))
    }
}
