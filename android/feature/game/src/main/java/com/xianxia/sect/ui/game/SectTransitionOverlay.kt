package com.xianxia.sect.ui.game

import android.media.MediaPlayer
import android.net.Uri
import android.widget.VideoView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.xianxia.sect.feature.game.R
import com.xianxia.sect.ui.components.DialogSystemBarGuard

/**
 * 进入宗门转场覆盖层（2026-08-16 独立文件抽取）。
 *
 * 用**平台 Dialog 窗口**承载，而非 MainGameScreen 组合内的 Box：
 * - 平台窗口创建于所有游戏窗口（含世界地图平台 Dialog、宗门地图 SurfaceView）之上，
 *   进入宗门时世界地图关闭不再露出底层地图（消除"先闪地图再出转场"）；
 * - `usePlatformDefaultWidth=false` + `decorFitsSystemWindows=false` 保证边到边全屏；
 * - [DialogSystemBarGuard] 隐藏 Dialog 窗口自身的系统栏（Dialog Window 不继承
 *   GameActivity 的 hideSystemBars()，不挂守卫则转场时状态栏/导航栏重新出现，
 *   覆盖层不是真全屏——2026-08-16 修复）；
 * - 视频 4:3 源经 `VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING` center-crop 填满全屏。
 *
 * 中央为转圈 + 「加载资源中…」（转圈在文本上方、字号 12sp）。关闭时机由
 * [SectMapController] 控制——目标宗门地图就绪且至少播放 1 秒，不依赖视频播放完毕。
 */
@Composable
internal fun SectTransitionOverlay(
    active: Boolean
) {
    if (!active) return
    // remember 持实例 + DisposableEffect：窗口关闭时显式停播，防 MediaPlayer 资源残留
    val context = LocalContext.current
    val videoView = remember {
        VideoView(context).apply {
            setVideoURI(
                Uri.parse("android.resource://${context.packageName}/${R.raw.sect_enter_transition}")
            )
            setOnPreparedListener { mp ->
                mp.isLooping = true
                mp.setVolume(0f, 0f) // 转场静音，避免与游戏 BGM/音效冲突
                // 4:3 源 center-crop 填满屏幕（去除黑边，全屏显示）
                mp.setVideoScalingMode(MediaPlayer.VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING)
                mp.start()
            }
        }
    }
    DisposableEffect(Unit) {
        onDispose { videoView.stopPlayback() }
    }

    Dialog(
        onDismissRequest = { },
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
            dismissOnBackPress = false,
            dismissOnClickOutside = false
        )
    ) {
        // Dialog 是独立平台 Window，不继承 GameActivity 的 hideSystemBars()：
        // 必须独立隐藏本窗口系统栏，否则转场时状态栏/导航栏重新出现，覆盖层非全屏
        DialogSystemBarGuard()

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black),
            contentAlignment = Alignment.Center
        ) {
            AndroidView(
                factory = { videoView },
                modifier = Modifier.fillMaxSize()
            )

            // 中央：转圈在文本上方 + 「加载资源中…」（字号 12sp）
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                CircularProgressIndicator(color = Color.White)
                Text(
                    text = "加载资源中…",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
            }
        }
    }
}
