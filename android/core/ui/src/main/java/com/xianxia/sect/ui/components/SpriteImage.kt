package com.xianxia.sect.ui.components

import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize

/**
 * 统一精灵图加载 Composable。
 *
 * 根据精灵图名称自动查缓存（[LocalItemSpriteCache]），
 * 命中则使用预加载的 [ImageBitmap]，未命中回退到 [painterResource]。
 *
 * 用法：
 * ```kotlin
 * SpriteImage("tiger", "虎妖")
 * SpriteImage("ui_merchant_button", "商人")
 * ```
 *
 * @param name 精灵图在 [SpriteResRegistry] 中注册的名称
 * @param contentDescription 无障碍描述
 * @param modifier 修饰符
 * @param contentScale 缩放模式
 * @param placeholder 未找到精灵图时显示的占位组件（可选）
 */
@Composable
fun SpriteImage(
    name: String,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Fit,
    placeholder: (@Composable () -> Unit)? = null
) {
    val resId = SpriteResRegistry.resolve(name)
    if (resId == null) {
        placeholder?.invoke()
        return
    }

    val cachedBitmap = LocalItemSpriteCache.current[resId]
    if (cachedBitmap != null) {
        Image(
            bitmap = cachedBitmap,
            contentDescription = contentDescription,
            modifier = modifier,
            contentScale = contentScale
        )
    } else {
        Image(
            painter = painterResource(id = resId),
            contentDescription = contentDescription,
            modifier = modifier,
            contentScale = contentScale
        )
    }
}

/**
 * 统一精灵图加载 Composable（重载 — 直接使用 [Painter]）。
 *
 * 用于已经持有 Painter 引用但仍需要统一接口的场景。
 * 注意：此重载不使用精灵图缓存。
 */
@Composable
fun SpriteImage(
    painter: Painter,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Fit,
) {
    Image(
        painter = painter,
        contentDescription = contentDescription,
        modifier = modifier,
        contentScale = contentScale
    )
}

/**
 * 在 Canvas 中通过精灵图名称绘制精灵图。
 *
 * 仅使用预加载缓存中的 [ImageBitmap]，不执行回退加载。
 * 如果缓存中未找到，静默跳过（不绘制）。
 *
 * 用法：
 * ```kotlin
 * Canvas(modifier) {
 *     drawSprite("building_alchemy", cache, dstOffset = ...)
 * }
 * ```
 *
 * @param name 精灵图在 [SpriteResRegistry] 中注册的名称
 * @param cache 预加载的精灵图缓存（通常来自 PreloadResult）
 * @param dstOffset 目标偏移
 * @param dstSize 目标尺寸（null 则使用位图原始尺寸）
 * @param alpha 透明度
 */
fun androidx.compose.ui.graphics.drawscope.DrawScope.drawSprite(
    name: String,
    cache: Map<Int, ImageBitmap>,
    dstOffset: IntOffset = IntOffset.Zero,
    dstSize: IntSize? = null,
    alpha: Float = 1f
) {
    val resId = SpriteResRegistry.resolve(name) ?: return
    val bitmap = cache[resId] ?: return
    drawImage(
        image = bitmap,
        dstOffset = dstOffset,
        dstSize = dstSize ?: IntSize(bitmap.width, bitmap.height),
        alpha = alpha
    )
}
