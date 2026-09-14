package com.xianxia.sect.ui.game

import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.view.Window
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.window.DialogWindowProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 进入宗门转场覆盖层全屏守卫测试：
 *
 * 1. 系统栏： [SectTransitionOverlay] 用平台 Dialog 窗口承载，Dialog Window 不继承
 *    GameActivity 的 hideSystemBars()，必须经 DialogSystemBarGuard 独立隐藏本窗口
 *    系统栏；否则转场时状态栏/导航栏重新出现，覆盖层不是真全屏。
 * 2. 视频铺满：转场视频经 TextureView + MediaPlayer 渲染，视图铺满 Dialog 窗口
 *    （fillMaxSize）后由 [computeCoverScale] 等比放大使视频内容撑满容器——
 *    不依赖 MediaPlayer 缩放模式 / SurfaceView surface 同步（部分 OEM ROM 失效导致
 *    左右留空）。视图级断言 TextureView ≥ 屏幕尺寸，纯函数级断言 cover 倍率数学。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SectTransitionOverlayTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    /**
     * 枚举当前所有窗口中的 Compose Dialog 窗口。
     *
     * 转场覆盖层的内容在独立 Dialog 窗口内，测试侧（Activity 组合）的 LocalView 祖先链
     * 上不存在 DialogWindowProvider（DialogSystemBarGuard 在 Dialog 内部读取才能命中），
     * 因此用反射遍历 [WindowManagerGlobal]（@hide 类，SDK stub 不可见）的全部窗口，
     * 深度查找实现 [DialogWindowProvider] 的 Compose 窗口载体，命中者必为 Dialog 窗口。
     */
    private fun dialogWindows(): List<Window> {
        val wmgClass = Class.forName("android.view.WindowManagerGlobal")
        val instance = wmgClass.getMethod("getInstance").invoke(null)
        @Suppress("UNCHECKED_CAST")
        val roots = wmgClass.getMethod("getWindowViews").invoke(instance) as List<View>
        val found = mutableListOf<Window>()
        roots.forEach { root -> findDialogWindows(root, found) }
        return found
    }

    private fun findDialogWindows(view: View, out: MutableList<Window>) {
        if (view is DialogWindowProvider) {
            out += view.window
            return
        }
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                findDialogWindows(view.getChildAt(i), out)
            }
        }
    }

    /** 深度遍历寻找转场窗口内的 TextureView（视频渲染层，铺满断言用）。 */
    private fun findTextureView(view: View): TextureView? =
        when {
            view is TextureView -> view
            view is ViewGroup -> (0 until view.childCount)
                .firstNotNullOfOrNull { findTextureView(view.getChildAt(it)) }
            else -> null
        }

    @Suppress("DEPRECATION")
    private fun hasHideNavigation(window: Window): Boolean =
        window.decorView.systemUiVisibility and View.SYSTEM_UI_FLAG_HIDE_NAVIGATION != 0

    @Suppress("DEPRECATION")
    private fun hasFullscreen(window: Window): Boolean =
        window.decorView.systemUiVisibility and View.SYSTEM_UI_FLAG_FULLSCREEN != 0

    @Composable
    private fun renderTransition() {
        SectTransitionOverlay(active = true)
    }

    @Test
    fun `挂载后 - 转场 Dialog 窗口已隐藏系统栏（全屏无状态栏导航栏）`() {
        composeRule.setContent { renderTransition() }
        composeRule.waitForIdle()

        val windows = dialogWindows()
        assertTrue("应存在转场 Dialog 窗口（WindowProvider 窗口）", windows.isNotEmpty())
        val window = windows.first()
        assertTrue("转场窗口应含 HIDE_NAVIGATION", hasHideNavigation(window))
        assertTrue("转场窗口应含 FULLSCREEN", hasFullscreen(window))
    }

    @Test
    fun `挂载后 - 转场视频层铺满整个窗口（左右无留空）`() {
        composeRule.setContent { renderTransition() }
        composeRule.waitForIdle()

        val windows = dialogWindows()
        assertTrue("应存在转场 Dialog 窗口（WindowProvider 窗口）", windows.isNotEmpty())
        val textureView = requireNotNull(findTextureView(windows.first().decorView)) {
            "转场窗口内应存在 TextureView 视频层"
        }

        val metrics = composeRule.activity.resources.displayMetrics
        assertTrue(
            "视频层宽度 ${textureView.width}px 应 >= 屏幕宽度 ${metrics.widthPixels}px（否则左右留空）",
            textureView.width >= metrics.widthPixels
        )
        assertTrue(
            "视频层高度 ${textureView.height}px 应 >= 屏幕高度 ${metrics.heightPixels}px",
            textureView.height >= metrics.heightPixels
        )
    }

    @Test
    fun `computeCoverScale - 横屏 16_9 视频放大铺满（撑宽溢出高）`() {
        // 横屏手机 2400x1080、16:9 视频：宽向比例 1.25 更高，fit=1.0，cover=1.25
        assertEquals(1.25f, computeCoverScale(2400, 1080, 1920, 1080), 1e-4f)
    }

    @Test
    fun `computeCoverScale - 横屏 4_3 视频放大铺满`() {
        // 横屏手机 2400x1080、4:3 视频：fit=1.0（高度贴合），cover=1.6667
        assertEquals(1.6667f, computeCoverScale(2400, 1080, 1440, 1080), 1e-3f)
    }

    @Test
    fun `computeCoverScale - 竖屏容器放大铺满`() {
        // 竖屏 1080x2400、16:9 视频：fit=0.5625（宽度贴合），cover=2.2222/0.5625≈3.95
        assertEquals(3.9506f, computeCoverScale(1080, 2400, 1920, 1080), 1e-3f)
    }

    @Test
    fun `computeCoverScale - 宽高比一致时恰好铺满返回 1`() {
        assertEquals(1f, computeCoverScale(1920, 1080, 1920, 1080), 1e-4f)
    }

    @Test
    fun `computeCoverScale - 非法尺寸返回 1（无副作用）`() {
        assertEquals(1f, computeCoverScale(0, 1080, 1920, 1080), 1e-4f)
        assertEquals(1f, computeCoverScale(2400, 0, 1920, 1080), 1e-4f)
        assertEquals(1f, computeCoverScale(2400, 1080, 0, 1080), 1e-4f)
        assertEquals(1f, computeCoverScale(2400, 1080, 1920, 0), 1e-4f)
    }
}
