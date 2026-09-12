package com.xianxia.sect.ui.components

import androidx.compose.runtime.staticCompositionLocalOf

/**
 * 按钮点击音效的 CompositionLocal。
 *
 * 由应用层（MainActivity/GameActivity）提供实际实现，
 * GameButton/CloseButton 等 UI 组件层仅调用，不依赖 AudioEngine。
 *
 * 用法：
 * ```kotlin
 * CompositionLocalProvider(LocalPlayClickSound provides { audioEngine.playSound("click") }) {
 *     // 游戏 UI
 * }
 * ```
 */
val LocalPlayClickSound = staticCompositionLocalOf<(() -> Unit)?> { null }
