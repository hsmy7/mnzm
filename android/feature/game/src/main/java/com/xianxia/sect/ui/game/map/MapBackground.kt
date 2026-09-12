package com.xianxia.sect.ui.game.map

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.unit.IntSize
import com.xianxia.sect.feature.game.R
import com.xianxia.sect.ui.game.map.world.WorldCameraState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

@Composable
fun MapBackground(
    cameraState: WorldCameraState,
    modifier: Modifier = Modifier
) {
    // 用 LocalResources 获取资源（配置变化时正确更新）
    val resources = LocalResources.current
    // 世界地图底图（map_zhongzhou 1698×926，RGB_565 ≈3.1MB）后台异步解码。
    // 解码完成前本层不绘制（世界地图其余图层正常显示），不阻塞世界地图首帧。
    // resources 变化（配置变更）时重解码。
    val mapBitmap = produceState<ImageBitmap?>(initialValue = null, resources) {
        value = withContext(Dispatchers.Default) {
            BitmapFactory.decodeResource(
                resources,
                R.drawable.map_zhongzhou,
                BitmapFactory.Options().apply {
                    inPreferredConfig = Bitmap.Config.RGB_565
                }
            )?.asImageBitmap()
        }
    }

    val bitmap = mapBitmap.value
    Canvas(modifier = modifier.fillMaxSize()) {
        if (bitmap != null) {
            withTransform({
                translate(-cameraState.cameraX * cameraState.scale, -cameraState.cameraY * cameraState.scale)
                scale(cameraState.scale, cameraState.scale, Offset.Zero)
            }) {
                drawImage(
                    image = bitmap,
                    dstSize = IntSize(
                        cameraState.worldWidth.roundToInt(),
                        cameraState.worldHeight.roundToInt()
                    )
                )
            }
        }
    }
}
