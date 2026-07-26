package com.xianxia.sect.ui.game.main

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.xianxia.sect.ui.components.SpriteImage
import com.xianxia.sect.ui.components.clickableWithSound

/**
 * UI 显隐切换按钮 — 圆形按钮，根据当前状态显示不同的精灵图。
 */
@Composable
internal fun HideUiToggleButton(
    isUiVisible: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier
) {
    val spriteName = if (isUiVisible) "ui_hide_button" else "ui_show_button"
    val description = if (isUiVisible) "隐藏UI" else "显示UI"
    Box(
        modifier = modifier
            .clip(CircleShape)
            .clickableWithSound(onClick = onToggle),
        contentAlignment = Alignment.Center
    ) {
        SpriteImage(
            name = spriteName,
            contentDescription = description,
            modifier = Modifier.matchParentSize(),
            contentScale = ContentScale.FillBounds
        )
    }
}
