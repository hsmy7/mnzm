package com.xianxia.sect.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier

/**
 * 带点击音效的 clickable，兼容 [Modifier.clickable] 的各种签名。
 *
 * 替代 `Modifier.clickable { onClick() }`，自动播放来自 [LocalPlayClickSound] 的按钮音效。
 * 所有点击音效受 [AudioConfig.soundEnabled] 控制（已在 AudioEngine.playSound 中检查）。
 *
 * 用法：
 * ```kotlin
 * Box(modifier = Modifier.clickableWithSound { handleClick() })
 * Box(modifier = Modifier.clickableWithSound(enabled = canClick) { onClick() })
 * Box(modifier = Modifier.clickableWithSound(interactionSource = src, indication = null) { onClick() })
 * ```
 */
@Composable
fun Modifier.clickableWithSound(
    enabled: Boolean = true,
    onClickLabel: String? = null,
    interactionSource: MutableInteractionSource? = null,
    indication: androidx.compose.foundation.Indication? = null,
    onClick: () -> Unit
): Modifier {
    val currentOnClick by rememberUpdatedState(onClick)
    val playSound = LocalPlayClickSound.current
    val actualInteractionSource = interactionSource ?: remember { MutableInteractionSource() }
    return this.clickable(
        interactionSource = actualInteractionSource,
        indication = indication,
        enabled = enabled,
        onClickLabel = onClickLabel,
        onClick = {
            playSound?.invoke()
            currentOnClick()
        }
    )
}
