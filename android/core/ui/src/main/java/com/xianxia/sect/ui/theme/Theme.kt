package com.xianxia.sect.ui.theme

import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color

private val LightColorScheme = lightColorScheme(
    primary = GameColors.ButtonBackground,
    secondary = Color(0xFF03DAC6),
    tertiary = Color(0xFFC4A484),
    background = Color.White,
    surface = Color.White,
    onPrimary = Color.Black,
    onSecondary = Color.Black,
    onTertiary = Color.Black,
    onBackground = Color.Black,
    onSurface = Color.Black,
    primaryContainer = GameColors.ButtonBackground,
    onPrimaryContainer = Color.Black,
    secondaryContainer = Color(0xFFE8DEF8),
    onSecondaryContainer = Color.Black,
    surfaceVariant = Color(0xFFF5F5F5),
    onSurfaceVariant = Color.Black
)

@Composable
fun XianxiaTheme(
    content: @Composable () -> Unit
) {
    val colorScheme = LightColorScheme

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        shapes = Shapes,
        content = {
            // 全局禁用涟漪（Bugly #9076 SIGABRT：RippleHostView 硬件水波纹动画在特定 ROM +
            // 快速点击下触发 RenderNode.addAnimator 原生 abort，应用侧唯一可靠根治是禁用
            // ripple。本游戏主按钮均为缩放反馈，无涟漪视觉依赖）。
            // LocalRippleConfiguration provides null → DelegatingThemeAwareRippleNode
            // 收到 null 配置时 removeRipple() 真正卸载 ripple 节点（RippleHostView 不再创建）。
            // 实测证明 rippleAlpha=0 无效（节点与硬件动画照常运行，仅画透明）——
            // 见 RippleHostViewProbeTest 与对抗性审查记录。
            CompositionLocalProvider(
                LocalRippleConfiguration provides null
            ) {
                content()
            }
        }
    )
}
