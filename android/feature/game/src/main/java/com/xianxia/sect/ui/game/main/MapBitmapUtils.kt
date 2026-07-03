package com.xianxia.sect.ui.game.main

import android.graphics.BitmapFactory
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import com.xianxia.sect.ui.game.building.BuildingRegistry

/** 建筑纹理最大尺寸 256px — 建筑在网格上最大 3×3 格 = 192px */
private const val MAX_BUILDING_TEXTURE_PX = 256

/**
 * 预加载所有建筑 Bitmap，按名称缓存。
 * 限制纹理尺寸防止低端设备超出 GL_MAX_TEXTURE_SIZE 导致 libhwui.so SIGSEGV。
 */
@Composable
internal fun rememberBuildingBitmaps(): Map<String, ImageBitmap> {
    val context = LocalContext.current
    val names = BuildingRegistry.names
    return remember {
        names.associateWith { name ->
            val resId = BuildingRegistry.drawableRes(name)
            val opts = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            BitmapFactory.decodeResource(context.resources, resId, opts)
            opts.inSampleSize = calculateInSampleSize(
                opts.outWidth, opts.outHeight, MAX_BUILDING_TEXTURE_PX)
            opts.inJustDecodeBounds = false
            BitmapFactory.decodeResource(context.resources, resId, opts)
                ?.asImageBitmap() ?: createFallbackBuildingBitmap()
        }
    }
}

/**
 * 计算 BitmapFactory 的 inSampleSize（总是 2 的幂），
 * 确保解码后尺寸不超过 maxDimension。
 */
internal fun calculateInSampleSize(
    width: Int,
    height: Int,
    maxDimension: Int
): Int {
    var sampleSize = 1
    while (width / (sampleSize * 2) >= maxDimension
        || height / (sampleSize * 2) >= maxDimension
    ) {
        sampleSize *= 2
    }
    return sampleSize
}

/**
 * 建筑纹理缺失时的回退 Bitmap — 2×2 灰色像素
 */
internal fun createFallbackBuildingBitmap(): ImageBitmap {
    val bmp = android.graphics.Bitmap.createBitmap(2, 2,
        android.graphics.Bitmap.Config.ARGB_8888)
    bmp.eraseColor(0xFFBDBDBD.toInt())
    return bmp.asImageBitmap()
}
