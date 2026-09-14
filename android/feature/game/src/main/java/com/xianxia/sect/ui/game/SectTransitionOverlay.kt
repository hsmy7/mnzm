package com.xianxia.sect.ui.game

import android.content.Context
import android.graphics.SurfaceTexture
import android.media.MediaPlayer
import android.net.Uri
import android.view.Surface
import android.view.TextureView
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
import androidx.compose.ui.draw.clipToBounds
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
import kotlin.math.max
import kotlin.math.min

/**
 * 进入宗门转场覆盖层。
 *
 * 用**平台 Dialog 窗口**承载（与全项目所有游戏弹窗一致），置于世界地图之上，
 * 进入宗门时世界地图关闭不再露出底层地图（消除"先闪地图再出转场"）：
 * - `usePlatformDefaultWidth=false` + `decorFitsSystemWindows=false` 保证边到边全屏；
 * - [DialogSystemBarGuard] 隐藏 Dialog 窗口自身的系统栏（Dialog Window 不继承
 *   GameActivity 的 hideSystemBars()，不挂守卫则转场时状态栏/导航栏重新出现，
 *   覆盖层不是真全屏）。
 *
 * 视频铺满策略：**TextureView + MediaPlayer + 手动 cover 等比变换**。
 * - TextureView 铺满 Dialog 窗口（[fillMaxSize]），视频经 MediaPlayer 渲染进其纹理；
 * - surface 尺寸/视频尺寸已知后，按 [computeCoverScale] 对 TextureView 施加等比放大
 *   并以中心为轴，使视频内容至少撑满容器（父层 [clipToBounds] 裁掉溢出）——
 *   纯视图层数学变换，不依赖 SurfaceView 尺寸同步与 MediaPlayer 缩放模式，
 *   任何设备都必然铺满。
 *
 * 中央为转圈 + 「加载资源中…」（转圈在文本上方、字号 12sp）。关闭时机由
 * [SectMapController] 控制——目标宗门地图就绪且至少播放 1 秒，不依赖视频播放完毕。
 */
@Composable
internal fun SectTransitionOverlay(
    active: Boolean
) {
    if (!active) return
    val context = LocalContext.current
    val controller = remember { TransitionVideoController(context) }
    DisposableEffect(Unit) {
        onDispose { controller.release() }
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
                .clipToBounds()
                .background(Color.Black),
            contentAlignment = Alignment.Center
        ) {
            AndroidView(
                factory = { controller.view },
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

/**
 * 计算视频在容器内 "cover 铺满" 所需的等比缩放倍率（返回 ≥1：>1 放大、=1 恰好铺满）。
 *
 * 语义：MediaPlayer 默认将视频按 fit 缩放显示在 TextureView 内（比例不一致时
 * 容器内出现透明留空）；对 TextureView 整体施加本倍率等比放大后，视频内容恰好
 * 撑满容器（多余部分由父层裁剪）。倍率 = max(容器宽/视频宽, 容器高/视频高) 相对
 * fit 比例的比值，纯数学变换，与 SurfaceView/缩放模式实现无关。
 */
internal fun computeCoverScale(
    containerW: Int,
    containerH: Int,
    videoW: Int,
    videoH: Int
): Float {
    if (minOf(containerW, containerH, videoW, videoH) <= 0) return 1f
    val scaleX = containerW.toFloat() / videoW
    val scaleY = containerH.toFloat() / videoH
    val fit = min(scaleX, scaleY)
    val cover = max(scaleX, scaleY)
    return if (fit <= 0f) 1f else cover / fit
}

/**
 * 转场视频控制器：TextureView 生命周期 ↔ MediaPlayer 生命周期绑定，
 * 视频尺寸就绪后施加 [computeCoverScale] 等比放大铺满。
 */
internal class TransitionVideoController(context: Context) {
    private var player: MediaPlayer? = null
    private var videoW = 0
    private var videoH = 0

    val view: TextureView = TextureView(context).apply {
        surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
                val mp = MediaPlayer()
                player = mp
                mp.setDataSource(
                    context,
                    Uri.parse("android.resource://${context.packageName}/${R.raw.sect_enter_transition}")
                )
                mp.isLooping = true
                mp.setVolume(0f, 0f) // 转场静音，避免与游戏 BGM/音效冲突
                mp.setSurface(Surface(surface))
                mp.setOnPreparedListener { prepared ->
                    videoW = prepared.videoWidth
                    videoH = prepared.videoHeight
                    applyCoverScale()
                    prepared.start()
                }
                mp.prepareAsync()
            }

            override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
                applyCoverScale()
            }

            override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
                player?.release()
                player = null
                return true
            }

            @Suppress("EmptyFunctionBlock") // 帧回调无处理，画面由 Surface 直接呈现
            override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {}

            private fun applyCoverScale() {
                val scale = computeCoverScale(
                    containerW = this@TransitionVideoController.view.width,
                    containerH = this@TransitionVideoController.view.height,
                    videoW = videoW,
                    videoH = videoH
                )
                if (scale <= 1f) return // 恰好铺满或尺寸未知，无需变换
                view.pivotX = view.width / 2f
                view.pivotY = view.height / 2f
                view.scaleX = scale
                view.scaleY = scale
            }
        }
    }

    /** 显式释放播放器（Dialog 关闭/组合移除时调用；surface 销毁回调亦兜底）。 */
    fun release() {
        player?.release()
        player = null
    }
}
