package com.xianxia.sect.ui

import android.graphics.Bitmap
import android.util.Log
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.bumptech.glide.Glide
import com.xianxia.sect.R
import com.xianxia.sect.ui.components.AudioToggleRow
import com.xianxia.sect.ui.components.SmallScreenDialog
import com.xianxia.sect.ui.components.SpriteImage
import com.xianxia.sect.ui.components.clickableWithSound
import com.xianxia.sect.ui.game.dialogs.LeaderboardDialog
import com.xianxia.sect.ui.game.leaderboard.LeaderboardViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.xianxia.sect.ui.theme.GameColors

private const val TAG = "ModeSelectionScreen"

/**
 * 选择模式界面 — 登录后、选存档前。
 *
 * 背景图铺满，左下角三个按钮（新游戏/读取存档/退出登录），
 * 右上角显示 TapTap 用户名和头像。
 */
@Composable
@Suppress("LongParameterList") // 屏幕级入口函数：登录信息/音频/导航回调参数分组会破坏调用语义
fun ModeSelectionScreen(
    userName: String,
    unionId: String,
    avatarUrl: String?,
    onNewGame: () -> Unit,
    onLoadSave: () -> Unit,
    onLogout: () -> Unit,
    soundEnabled: Boolean = true,
    musicEnabled: Boolean = true,
    onSoundToggle: (Boolean) -> Unit = {},
    onMusicToggle: (Boolean) -> Unit = {}
) {
    var showUserInfo by remember { mutableStateOf(false) }
    var showLeaderboard by remember { mutableStateOf(false) }
    val avatarBitmap = rememberAvatarBitmap(avatarUrl)

    Box(modifier = Modifier.fillMaxSize()) {
        // 背景图
        Image(
            painter = painterResource(id = R.drawable.mode_selection_bg),
            contentDescription = "选择模式界面背景",
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.FillBounds
        )

        // 主内容区 — 仅避让左右安全区域（曲面屏/挖孔屏边缘）
        Box(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(
                    WindowInsets.systemBars.only(
                        WindowInsetsSides.Left + WindowInsetsSides.Right
                    )
                )
        ) {
            // 右上角：用户名 + 头像 + 音频勾选
            Column(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 16.dp, end = 16.dp),
                horizontalAlignment = Alignment.End
            ) {
                UserAvatarHeader(
                    userName = userName,
                    avatarBitmap = avatarBitmap,
                    onClick = { showUserInfo = true },
                    onOpenLeaderboard = { showLeaderboard = true }
                )
                Spacer(modifier = Modifier.height(8.dp))
                AudioToggleRow(
                    soundEnabled = soundEnabled,
                    musicEnabled = musicEnabled,
                    onSoundToggle = onSoundToggle,
                    onMusicToggle = onMusicToggle
                )
            }

            // 左侧：三个按钮纵向排列
            ActionButtons(
                onNewGame = onNewGame,
                onLoadSave = onLoadSave,
                onLogout = onLogout
            )
        }

        // 用户信息小屏对话框
        if (showUserInfo) {
            UserInfoDialog(
                userName = userName,
                unionId = unionId,
                onDismiss = { showUserInfo = false }
            )
        }

        // 排行榜覆层（主菜单无存档上下文，默认落在玩家排行标签）
        if (showLeaderboard) {
            LeaderboardDialogOverlay(onDismiss = { showLeaderboard = false })
        }
    }
}

/** 排行榜对话框覆层（主菜单入口专用：默认落在玩家排行标签，天下宗门 Tab 显示引导提示） */
@Composable
private fun LeaderboardDialogOverlay(onDismiss: () -> Unit) {
    val leaderboardViewModel = hiltViewModel<LeaderboardViewModel>()
    LeaderboardDialog(
        viewModel = leaderboardViewModel,
        onDismiss = onDismiss,
        initialTab = LeaderboardViewModel.LeaderboardTab.CLOUD
    )
}

/** 用户信息小屏对话框 */
@Composable
private fun UserInfoDialog(
    userName: String,
    unionId: String,
    onDismiss: () -> Unit
) {
    SmallScreenDialog(
        onDismissRequest = onDismiss,
        title = "用户信息",
        titleColor = Color.Black
    ) {
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "用户名称",
            fontSize = 12.sp,
            color = Color(0xFF888888)
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = userName,
            fontSize = 16.sp,
            color = Color.Black,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "TapTap UnionId",
            fontSize = 12.sp,
            color = Color(0xFF888888)
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = unionId,
            fontSize = 14.sp,
            color = Color.Black,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun UserAvatarHeader(
    userName: String,
    avatarBitmap: Bitmap?,
    onClick: () -> Unit,
    onOpenLeaderboard: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 16.dp, end = 16.dp),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 排行榜按钮（用户名左侧）
        LeaderboardEntryButton(onClick = onOpenLeaderboard)
        Spacer(modifier = Modifier.width(10.dp))
        // 用户名 + 头像区域整体可点击（查看用户信息）
        Row(
            modifier = Modifier.clickableWithSound { onClick() },
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = userName,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = Color.Black,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.width(8.dp))
            if (avatarBitmap != null) {
                Image(
                    bitmap = avatarBitmap.asImageBitmap(),
                    contentDescription = "用户头像",
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape),
                    contentScale = ContentScale.Crop
                )
            } else {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(GameColors.DividerGray),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = userName.take(1).ifEmpty { "?" },
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                }
            }
        }
    }
}

/** 主菜单排行榜入口按钮（排行榜图标 + 排行文字，与主游戏界面入口同源图标） */
@Composable
private fun LeaderboardEntryButton(onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(36.dp)
            .clip(CircleShape)
            .clickableWithSound { onClick() },
        contentAlignment = Alignment.BottomCenter
    ) {
        SpriteImage(
            name = "ui_leaderboard_button",
            contentDescription = "排行榜",
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.FillBounds
        )
        Text(
            text = "排行",
            fontSize = 8.sp,
            fontWeight = FontWeight.Bold,
            color = Color.Black,
            modifier = Modifier.padding(horizontal = 3.dp, vertical = 1.dp)
        )
    }
}

@Composable
private fun ActionButtons(
    onNewGame: () -> Unit,
    onLoadSave: () -> Unit,
    onLogout: () -> Unit
) {
    Column(
        modifier = Modifier
            .padding(start = 24.dp, top = 80.dp),
        verticalArrangement = Arrangement.Top,
        horizontalAlignment = Alignment.Start
    ) {
        Image(
            painter = painterResource(id = R.drawable.btn_new_game),
            contentDescription = "新游戏",
            modifier = Modifier
                .height(56.dp)
                .clickableWithSound { onNewGame() },
            contentScale = ContentScale.Fit
        )
        Spacer(modifier = Modifier.height(16.dp))
        Image(
            painter = painterResource(id = R.drawable.btn_load_save),
            contentDescription = "读取存档",
            modifier = Modifier
                .height(56.dp)
                .clickableWithSound { onLoadSave() },
            contentScale = ContentScale.Fit
        )
        Spacer(modifier = Modifier.height(16.dp))
        Image(
            painter = painterResource(id = R.drawable.btn_logout),
            contentDescription = "退出登录",
            modifier = Modifier
                .height(56.dp)
                .clickableWithSound { onLogout() },
            contentScale = ContentScale.Fit
        )
    }
}

/**
 * 使用 Glide 异步加载头像 Bitmap。
 * 返回 null 表示加载中或失败。
 */
@Composable
fun rememberAvatarBitmap(url: String?, dispatcher: CoroutineDispatcher = Dispatchers.IO): Bitmap? {
    val context = LocalContext.current
    var bitmap by remember { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(url) {
        if (url.isNullOrBlank()) return@LaunchedEffect
        try {
            bitmap = withContext(dispatcher) {
                Glide.with(context)
                    .asBitmap()
                    .load(url)
                    .submit()
                    .get()
            }
        } catch (e: java.util.concurrent.ExecutionException) {
            Log.w(TAG, "Failed to load avatar bitmap: ${e.message}")
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            Log.w(TAG, "Avatar loading interrupted")
        }
    }
    return bitmap
}
