package com.xianxia.sect.ui

import android.graphics.Bitmap
import android.util.Log
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
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
import com.bumptech.glide.Glide
import com.xianxia.sect.R
import com.xianxia.sect.ui.components.SmallScreenDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val TAG = "ModeSelectionScreen"

/**
 * 选择模式界面 — 登录后、选存档前。
 *
 * 背景图铺满，左下角三个按钮（新游戏/读取存档/退出登录），
 * 右上角显示 TapTap 用户名和头像。
 */
@Composable
fun ModeSelectionScreen(
    userName: String,
    unionId: String,
    avatarUrl: String?,
    onNewGame: () -> Unit,
    onLoadSave: () -> Unit,
    onLogout: () -> Unit
) {
    var showUserInfo by remember { mutableStateOf(false) }
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
        Box(modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.systemBars.only(WindowInsetsSides.Left + WindowInsetsSides.Right))) {
            // 右上角：用户名 + 头像
            UserAvatarHeader(
                userName = userName,
                avatarBitmap = avatarBitmap,
                onClick = { showUserInfo = true }
            )

            // 左侧：三个按钮纵向排列
            ActionButtons(
                onNewGame = onNewGame,
                onLoadSave = onLoadSave,
                onLogout = onLogout
            )
        }

        // 用户信息小屏对话框
        if (showUserInfo) {
            SmallScreenDialog(
                onDismissRequest = { showUserInfo = false },
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
    }
}

@Composable
private fun UserAvatarHeader(
    userName: String,
    avatarBitmap: Bitmap?,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 16.dp, end = 16.dp)
            .clickable { onClick() },
        horizontalArrangement = Arrangement.End,
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
                    .background(Color(0xFFCCCCCC)),
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
                .clickable { onNewGame() },
            contentScale = ContentScale.Fit
        )
        Spacer(modifier = Modifier.height(16.dp))
        Image(
            painter = painterResource(id = R.drawable.btn_load_save),
            contentDescription = "读取存档",
            modifier = Modifier
                .height(56.dp)
                .clickable { onLoadSave() },
            contentScale = ContentScale.Fit
        )
        Spacer(modifier = Modifier.height(16.dp))
        Image(
            painter = painterResource(id = R.drawable.btn_logout),
            contentDescription = "退出登录",
            modifier = Modifier
                .height(56.dp)
                .clickable { onLogout() },
            contentScale = ContentScale.Fit
        )
    }
}

/**
 * 使用 Glide 异步加载头像 Bitmap。
 * 返回 null 表示加载中或失败。
 */
@Composable
fun rememberAvatarBitmap(url: String?): Bitmap? {
    val context = LocalContext.current
    var bitmap by remember { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(url) {
        if (url.isNullOrBlank()) return@LaunchedEffect
        try {
            bitmap = withContext(Dispatchers.IO) {
                Glide.with(context)
                    .asBitmap()
                    .load(url)
                    .submit()
                    .get()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load avatar bitmap: ${e.message}")
        }
    }
    return bitmap
}
