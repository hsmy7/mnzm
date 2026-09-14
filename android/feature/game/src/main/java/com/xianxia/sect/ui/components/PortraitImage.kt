package com.xianxia.sect.ui.components

import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale

/**
 * 统一头像绘制。
 *
 * 优先命中 [LocalPortraitCache]（ResourcePreloader L0 预载产物，≤256px）；
 * 未命中回退 painterResource 全分辨率解码（源图 800~2048px）。
 *
 * ## 质量边界（使用前必读）
 * 预载位图上限 256px——仅限**显示尺寸 ≤80dp（约 240px @3x）**的场景使用
 * （弟子列表头像 40~56dp、聊天头像 80dp）。更大显示尺寸（详情页大立绘等）
 * 必须继续走 painterResource，否则会肉眼可见降质。
 *
 * @param name 肖像名（[com.xianxia.sect.core.util.PortraitPool] 键；缓存键）
 * @param resId 调用方按既有回退链解析的资源 ID（0 = 不绘制）
 */
@Composable
fun PortraitImage(
    name: String,
    resId: Int,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Fit
) {
    if (resId == 0) return
    val cached = LocalPortraitCache.current[name]
    if (cached != null) {
        Image(
            bitmap = cached,
            contentDescription = null,
            modifier = modifier,
            contentScale = contentScale
        )
    } else {
        Image(
            painter = androidx.compose.ui.res.painterResource(id = resId),
            contentDescription = null,
            modifier = modifier,
            contentScale = contentScale
        )
    }
}
