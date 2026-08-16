package com.xianxia.sect.ui.game

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
import com.xianxia.sect.feature.game.R

/**
 * 进入宗门转场覆盖层（2026-08-16 独立文件抽取）。
 *
 * 全屏播放转场动画（循环、静音），中央为转圈 + 「加载资源中…」（转圈在文本上方、
 * 字号 12sp，与加载界面一致的白色系提示）。关闭时机由 [SectMapController] 控制——
 * 目标宗门地图就绪且至少播放 1 秒，不依赖视频播放完毕（避免 13.85s 全片卡死玩家）。
 */
@Composable
internal fun SectTransitionOverlay(
    active: Boolean,
    modifier: Modifier = Modifier
) {
    if (!active) return
    // remember 持实例 + DisposableEffect：覆盖层移出组合时显式停播，防 MediaPlayer 资源残留
    val context = LocalContext.current
    val videoView = remember {
        VideoView(context).apply {
            setVideoURI(
                Uri.parse("android.resource://${context.packageName}/${R.raw.sect_enter_transition}")
            )
            setOnPreparedListener { mp ->
                mp.isLooping = true
                mp.setVolume(0f, 0f) // 转场静音，避免与游戏 BGM/音效冲突
                mp.start()
            }
        }
    }
    DisposableEffect(Unit) {
        onDispose { videoView.stopPlayback() }
    }

    Box(
        modifier = modifier
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
