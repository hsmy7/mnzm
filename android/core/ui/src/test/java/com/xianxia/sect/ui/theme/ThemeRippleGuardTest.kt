package com.xianxia.sect.ui.theme

import androidx.activity.ComponentActivity
import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.material3.RippleConfiguration
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 主题涟漪守卫测试（Bugly #9076 SIGABRT 根治的防回归守卫）：
 * XianxiaTheme 必须把 LocalRippleConfiguration 置为 null——DelegatingThemeAwareRippleNode
 * 收到 null 配置时 removeRipple() 真正卸载 ripple 节点（RippleHostView 不再创建）。
 * RippleHostView 硬件水波纹动画在特定 ROM + 快速点击下触发 RenderNode.addAnimator
 * 原生 abort，应用侧唯一可靠根治是禁用 ripple。
 *
 * 对抗性审查教训：rippleAlpha=0 曾被采用并被实测证明无效（节点与动画照常运行，仅画透明，
 * 见 RippleHostViewProbeTest），守卫因此断言 null 而非 alpha 全零——若有人改回任何
 * 非 null 配置（含 alpha 全零），此测试失败。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ThemeRippleGuardTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun `XianxiaTheme - LocalRippleConfiguration 为 null 全局禁用涟漪`() {
        var captured: RippleConfiguration? = null
        var hasDefault: Boolean = false

        composeRule.setContent {
            XianxiaTheme {
                captured = LocalRippleConfiguration.current
                hasDefault = true
            }
        }
        composeRule.waitForIdle()

        // hasDefault 防误判：若 composition 未执行，captured=null 是假阳性
        assert(hasDefault)
        assertNull(
            "XianxiaTheme 必须把 LocalRippleConfiguration 置为 null（全局禁用涟漪，" +
                "Bugly #9076 根治；rippleAlpha=0 已被实测证明无效，见 RippleHostViewProbeTest）",
            captured
        )
    }

    @Test
    fun `LocalRippleConfiguration - 默认值非 null 证明主题覆盖是显式行为`() {
        // 对照用例：无 XianxiaTheme 包裹时读到 M3 默认值。
        // 若默认值本身就是 null，则"provides null"与默认无异、守卫失去意义——此用例确保
        // 默认值非 null，从而证明 XianxiaTheme 内的 null 是显式覆盖行为。
        var captured: RippleConfiguration? = null
        var hasDefault: Boolean = false

        composeRule.setContent {
            captured = LocalRippleConfiguration.current
            hasDefault = true
        }
        composeRule.waitForIdle()

        assert(hasDefault)
        assertNotNull("M3 默认 LocalRippleConfiguration 应为非 null（否则主题覆盖无意义）", captured)
    }
}
