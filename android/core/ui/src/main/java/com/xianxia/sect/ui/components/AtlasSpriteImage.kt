package com.xianxia.sect.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize

/**
 * 图集缓存 CompositionLocal。
 *
 * 由 [ResourcePreloader] 在预加载阶段构建的图集结果。
 * 为 null 时表示图集未启用，[AtlasSpriteImage] 回退到 [SpriteImage]。
 */
val LocalAtlasCache = staticCompositionLocalOf<AtlasResult?> { null }

/**
 * 从图集中渲染精灵图（若图集中不存在则回退到 [SpriteImage]）。
 *
 * 使用 Canvas [drawImage] 从图集大图中裁剪出对应精灵区域，
 * 避免加载多个独立纹理，减少 GPU 纹理切换。
 *
 * 用法：
 * ```kotlin
 * AtlasSpriteImage("pill_3", "丹药")
 * AtlasSpriteImage("manual_5", "功法", modifier = Modifier.size(48.dp))
 * ```
 *
 * @param name 精灵图在 [SpriteResRegistry] 中注册的名称
 * @param contentDescription 无障碍描述
 * @param modifier 修饰符
 * @param contentScale 缩放模式（仅回退到 SpriteImage 时生效）
 * @param placeholder 未找到精灵图时显示的占位组件（可选）
 */
@Composable
fun AtlasSpriteImage(
    name: String,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Fit,
    placeholder: (@Composable () -> Unit)? = null
) {
    val resId = SpriteResRegistry.resolve(name)
    val atlasResult = LocalAtlasCache.current

    if (resId != null && atlasResult != null) {
        val region = atlasResult.regions[resId]
        if (region != null) {
            Canvas(modifier = modifier) {
                drawImage(
                    image = atlasResult.atlas,
                    srcOffset = IntOffset(region.x, region.y),
                    srcSize = IntSize(region.w, region.h),
                    dstSize = IntSize(size.width.toInt(), size.height.toInt())
                )
            }
            return
        }
    }

    // 图集不可用或该精灵不在图集中 → 回退到 SpriteImage
    SpriteImage(
        name = name,
        contentDescription = contentDescription,
        modifier = modifier,
        contentScale = contentScale,
        placeholder = placeholder
    )
}
