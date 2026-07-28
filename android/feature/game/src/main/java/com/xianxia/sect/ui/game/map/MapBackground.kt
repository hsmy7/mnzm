package com.xianxia.sect.ui.game.map

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntSize
import com.xianxia.sect.feature.game.R
import com.xianxia.sect.ui.game.map.world.WorldCameraState
import kotlin.math.roundToInt

@Composable
fun MapBackground(
    cameraState: WorldCameraState,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val mapBitmap = remember {
        BitmapFactory.decodeResource(
            context.resources,
            R.drawable.map_zhongzhou,
            BitmapFactory.Options().apply {
                inPreferredConfig = Bitmap.Config.RGB_565
            }
        ).asImageBitmap()
    }

    Canvas(modifier = modifier.fillMaxSize()) {
        // 填充视口背景色，防止 minOf 缩放后因视口与地图尺寸不匹配产生的白边
        // 颜色取自 map_zhongzhou 边缘水域近似色（深灰蓝）
        drawRect(color = androidx.compose.ui.graphics.Color(0xFF1A1A2E))

        withTransform({
            translate(-cameraState.cameraX * cameraState.scale, -cameraState.cameraY * cameraState.scale)
            scale(cameraState.scale, cameraState.scale, Offset.Zero)
        }) {
            drawImage(
                image = mapBitmap,
                dstSize = IntSize(
                    cameraState.worldWidth.roundToInt(),
                    cameraState.worldHeight.roundToInt()
                )
            )
        }
    }
}
