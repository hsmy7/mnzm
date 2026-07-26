package com.xianxia.sect.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier

/**
 * 带点击音效的 clickable。
 *
 * 替代 `Modifier.clickable { onClick() }`，自动播放来自 [LocalPlayClickSound] 的按钮音效。
 *
 * 用法：
 * ```kotlin
 * Box(modifier = Modifier.clickableWithSound { handleClick() })
 * ```
 */
@Composable
fun Modifier.clickableWithSound(
    enabled: Boolean = true,
    onClick: () -> Unit
): Modifier {
    val currentOnClick by rememberUpdatedState(onClick)
    val playSound = LocalPlayClickSound.current
    return this.clickable(
        interactionSource = remember { MutableInteractionSource() },
        indication = null,
        enabled = enabled,
        onClick = {
            playSound?.invoke()
            currentOnClick()
        }
    )
}
