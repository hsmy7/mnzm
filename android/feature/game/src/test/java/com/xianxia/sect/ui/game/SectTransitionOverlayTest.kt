package com.xianxia.sect.ui.game

import android.view.View
import android.view.ViewGroup
import android.view.Window
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.window.DialogWindowProvider
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 进入宗门转场覆盖层全屏守卫测试（2026-08-16 回归）：
 *
 * [SectTransitionOverlay] 用平台 Dialog 窗口承载，Dialog Window 不继承 GameActivity
 * 的 hideSystemBars()，必须经 DialogSystemBarGuard 独立隐藏本窗口系统栏；否则转场时
 * 状态栏/导航栏重新出现，覆盖层不是真全屏（"转场动画没有全屏"问题的根因）。
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
     * 因此改为反射遍历 [WindowManagerGlobal]（@hide 类，SDK stub 不可见）的全部窗口，
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
}
