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
import com.xianxia.sect.R
import com.xianxia.sect.BuildConfig
import com.xianxia.sect.core.CrashHandler
import com.xianxia.sect.data.SessionManager
import com.xianxia.sect.data.SaveManager
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

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    
    @Inject
    lateinit var sessionManager: SessionManager
    
    @Inject
    lateinit var saveManager: SaveManager
    
    @Inject
    lateinit var crashHandler: CrashHandler
    
    private var complianceDialogState = mutableStateOf<ComplianceDialogState?>(null)
    private val loadingProgress = mutableStateOf(0f)
    private var isLoadComplete = false
    private val loadHandler = android.os.Handler(android.os.Looper.getMainLooper())
    
    companion object {
        private const val TAG = "MainActivity"
        const val EXTRA_SLOT = "slot"
        const val EXTRA_NEW_GAME = "new_game"
        const val EXTRA_SECT_NAME = "sect_name"
    }
    
    sealed class ComplianceDialogState {
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
        val updateRunnable = object : Runnable {
            override fun run() {
                if (isLoadComplete) {
                    // 加载完成，快速跳到100%
                    loadingProgress.value = 1f
                    loadHandler.postDelayed({ onLoadingComplete() }, 150)
                } else {
                    // 加载未完成，进度缓慢增长（最多到90%）
                    val currentProgress = loadingProgress.value
                    if (currentProgress < 0.9f) {
                        loadingProgress.value = currentProgress + 0.05f
                    }
                    loadHandler.postDelayed(this, 50)
                }
            }
        }
        loadHandler.post(updateRunnable)
    }
    
    private fun onLoadingComplete() {
        if (!sessionManager.hasAgreedPrivacy) {
            // 新用户：显示隐私政策，同意后进入登录界面
            showPrivacyPolicyScreen()
            return
        }
        
        // 检测崩溃恢复
        if (crashHandler.hasCrashed() && saveManager.hasEmergencySave()) {
            Log.i(TAG, "Detected crash with emergency save, showing recovery dialog")
            showCrashRecoveryDialog()
            return
        }
        
        // 清理崩溃状态（如果存在但没有紧急存档）
        if (crashHandler.hasCrashed()) {
            Log.i(TAG, "Crash detected but no emergency save, clearing crash state")
            crashHandler.clearCrashState()
        }
        
        // 用户已同意隐私政策，初始化 TapTap SDK
        initTapTapSDK()
        
        if (sessionManager.isLoggedIn) {
            // 老用户：直接进入存档选择界面
            showSaveSelectScreen()
            return
        }
        
        // 新用户（已同意隐私政策但未登录）：进入登录界面
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
    
    private fun showMainScreen() {
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
    
    private fun showSaveSelectScreen() {
        val saveSlots = saveManager.getSaveSlots()
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
                            saveManager.delete(slot)
                            showSaveSelectScreen()
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
            val emergencyData = saveManager.loadEmergencySave()
            if (emergencyData != null) {
                Log.i(TAG, "Emergency save loaded successfully, sect: ${emergencyData.gameData.sectName}")
                
                // 清理紧急存档和崩溃状态
                saveManager.clearEmergencySave()
                crashHandler.clearCrashState()
                
                // 获取存档槽位
                val slot = if (emergencyData.gameData.currentSlot > 0) emergencyData.gameData.currentSlot else 1
                
                // 将紧急存档保存到正常槽位
                saveManager.save(slot, emergencyData)
                
                // 进入游戏
                val intent = Intent(this, GameActivity::class.java)
                intent.putExtra(EXTRA_SLOT, slot)
                startActivity(intent)
                finish()
            } else {
                Log.e(TAG, "Failed to load emergency save")
                Toast.makeText(this, "恢复数据失败，将进入正常游戏", Toast.LENGTH_LONG).show()
                clearCrashStateAndContinue()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error recovering from emergency save", e)
            Toast.makeText(this, "恢复数据时发生错误", Toast.LENGTH_LONG).show()
            clearCrashStateAndContinue()
        }
    }
    
    /**
     * 清理崩溃状态并继续正常流程
     */
    private fun clearCrashStateAndContinue() {
        try {
            saveManager.clearEmergencySave()
            crashHandler.clearCrashState()
        } catch (e: Exception) {
            Log.e(TAG, "Error clearing crash state", e)
        }
        
        // 继续正常流程
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
            
            ComplianceManager.registerCallback(object : ComplianceManager.ComplianceCallback {
                override fun onLoginSuccess() {
                    runOnUiThread {
                        showSaveSelectScreen()
                    }
                }
                override fun onExited() = handleUserExit()
                override fun onSwitchAccount() = handleUserExit()
                override fun onPeriodRestrict() {
                    runOnUiThread {
                        complianceDialogState.value = ComplianceDialogState.Restrict(
                            "时间限制",
                            "根据防沉迷规定，未成年人仅可在周五、周六、周日及法定节假日的20:00-21:00进行游戏。"
                        )
                    }
                }
                override fun onDurationLimit() {
                    runOnUiThread {
                        complianceDialogState.value = ComplianceDialogState.Restrict(
                            "时长限制",
                            "您今日的游戏时长已用尽，请合理安排游戏时间。"
                        )
                    }
                }
                override fun onAgeLimit() {
                    runOnUiThread {
                        complianceDialogState.value = ComplianceDialogState.AgeLimit
                    }
                }
                override fun onNetworkError() {
                    runOnUiThread {
                        Toast.makeText(this@MainActivity, "网络连接异常，请检查网络后重试", Toast.LENGTH_LONG).show()
                        showMainScreen()
                    }
                }
                override fun onRealNameStop() = handleUserExit()
            })
            
            Log.d(TAG, "TapTap SDK初始化成功")
        } catch (e: Exception) {
            Log.e(TAG, "TapTap SDK初始化失败: ${e.message}")
        }
    }
    
    private fun handleUserExit() {
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
                        TextButton(onClick = {
                            complianceDialogState.value = null
                            sessionManager.clearSession()
                            TapTapAuthManager.logout()
                            (context as? MainActivity)?.recreate()
                        }) {
                            Text("退出游戏")
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = {
                            complianceDialogState.value = null
                            sessionManager.clearSession()
                            TapTapAuthManager.logout()
                            (context as? MainActivity)?.recreate()
                        }) {
                            Text("切换账号")
                        }
                    }
                )
            }
            is MainActivity.ComplianceDialogState.AgeLimit -> {
                AlertDialog(
                    onDismissRequest = { },
                    title = { Text("适龄限制") },
                    text = { Text("根据游戏适龄提示，您当前年龄不符合本游戏的游玩要求。") },
                    confirmButton = {
                        TextButton(onClick = {
                            complianceDialogState.value = null
                            (context as? MainActivity)?.finish()
                        }) {
                            Text("退出游戏")
                        }
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
                TextButton(onClick = {
                    val activity = context as? MainActivity
                    activity?.finish()
                }) {
                    Text("退出应用", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showExitConfirm = false }) {
                    Text("重新阅读")
                }
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
                            
                            sessionManager.saveLoginSession(
                                userId = data.openid ?: "taptap_${System.currentTimeMillis()}",
                                userName = data.name ?: "TapTap用户",
                                loginType = "taptap"
                            )
                            
                            Toast.makeText(context, "欢迎, ${data.name}!", Toast.LENGTH_SHORT).show()
                            
                            val unionId = data.unionid
                            if (!unionId.isNullOrEmpty()) {
                                isLoading = false
                                (context as? MainActivity)?.startComplianceCheck(unionId)
                            } else {
                                Log.w("MainScreen", "unionId为空，跳过合规认证")
                                isLoading = false
                                onLoginSuccess()
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
                text = "《用户协议》",
                color = MaterialTheme.colorScheme.primary,
                fontSize = 12.sp,
                modifier = Modifier.clickable {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://hsmy7.github.io/user-agreement.html"))
                    context.startActivity(intent)
                }
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = "和",
                color = Color(0xFF999999),
                fontSize = 12.sp
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = "《隐私政策》",
                color = MaterialTheme.colorScheme.primary,
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
                
                TextButton(
                    onClick = {
                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://hsmy7.github.io/index.html/"))
                        context.startActivity(intent)
                    },
                    modifier = Modifier.align(Alignment.CenterHorizontally)
                ) {
                    Text("查看完整隐私政策", color = MaterialTheme.colorScheme.primary)
                }
            }
        },
        confirmButton = {
            GameButton(
                text = "同意并继续",
                onClick = onAgree
            )
        },
        dismissButton = {
            TextButton(onClick = onDisagree) {
                Text("退出", color = MaterialTheme.colorScheme.error)
            }
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
            
            TextButton(
                onClick = {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://hsmy7.github.io/index.html/"))
                    context.startActivity(intent)
                },
                modifier = Modifier.align(Alignment.CenterHorizontally)
            ) {
                Text("查看完整隐私政策", color = MaterialTheme.colorScheme.primary)
            }
            
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
                TextButton(onClick = onDismiss) {
                    Text("不恢复", color = Color(0xFF999999))
                }
            }
        )
    }
}
