package com.xianxia.sect.ui.components

import android.os.Looper
import android.view.View
import android.view.Window
import androidx.activity.ComponentActivity
import androidx.compose.material3.Text
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.graphics.Insets
import androidx.core.view.WindowInsetsCompat
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

/**
 * DialogSystemBarGuard 冻结感知测试：
 *
 * 输入对话框（freezeSystemBars=true）挂载期间本窗口经 DialogSystemBarFreezeScope
 * 冻结——只隐藏状态栏、不隐藏导航栏（切断 HIDE_NAVIGATION×IME 冲突面）；
 * 嵌套内联输入框挂载（冻结进入）恢复导航栏显示，解冻后延迟恢复隐藏；
 * 键盘可见期间对系统栏零操作（不再响应 IME 翻转切换系统栏）。
 *
 * 冻结状态经 [DialogSystemBarFreezeScope] 直接驱动；键盘可见性经
 * [ImeVisibilityTracker.setImeVisibleForTest] 驱动（Robolectric 对
 * android.view.WindowInsets 的 ime 类型支持不全，状态机逻辑与框架解析解耦验证）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DialogSystemBarGuardTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @After
    fun tearDown() {
        ImeVisibilityTracker.resetForTest()
        SystemBarFreezeScope.resetForTest()
        DialogSystemBarFreezeScope.resetForTest()
    }

    private fun imeInsetsWith(height: Int): WindowInsetsCompat =
        WindowInsetsCompat.Builder()
            .setInsets(WindowInsetsCompat.Type.ime(), Insets.of(0, 0, 0, height))
            .build()

    private fun findDialogWindow(view: View): Window? =
        generateSequence(view) { it.parent as? View }
            .filterIsInstance<DialogWindowProvider>()
            .firstOrNull()
            ?.window

    /**
     * 挂载 DialogSystemBarGuard 并捕获 Dialog 窗口引用（与 isInsideDialogWindow 同款祖先遍历）。
     * [frozen] 为 true 时先对 Dialog 窗口 enterFreeze（模拟容器 freezeSystemBars=true 的挂载顺序：
     * DialogSystemBarFreezeEffect 声明于 guard 之前，先执行冻结再挂 guard）。
     */
    private fun mountDialogWithGuard(frozen: Boolean = false): Window {
        val dialogWindow = mutableStateOf<Window?>(null)
        composeRule.setContent {
            Dialog(onDismissRequest = {}) {
                val dialogView = LocalView.current
                DisposableEffect(Unit) {
                    val window = findDialogWindow(dialogView)
                    dialogWindow.value = window
                    if (frozen && window != null) {
                        DialogSystemBarFreezeScope.enterFreeze(window)
                    }
                    onDispose {
                        if (frozen) window?.let { DialogSystemBarFreezeScope.exitFreeze(it) }
                    }
                }
                DialogSystemBarGuard()
                Text("守卫测试")
            }
        }
        composeRule.waitForIdle()
        val window = dialogWindow.value
        checkNotNull(window) { "应捕获到 Dialog 窗口（DialogWindowProvider 祖先）" }
        return window
    }

    @Suppress("DEPRECATION")
    private fun hasHideNavigation(window: Window): Boolean =
        window.decorView.systemUiVisibility and View.SYSTEM_UI_FLAG_HIDE_NAVIGATION != 0

    @Suppress("DEPRECATION")
    private fun hasFullscreen(window: Window): Boolean =
        window.decorView.systemUiVisibility and View.SYSTEM_UI_FLAG_FULLSCREEN != 0

    @Test
    fun `未冻结挂载 - 应用状态栏与导航栏隐藏标志`() {
        val window = mountDialogWithGuard(frozen = false)
        assertTrue("未冻结挂载后应含 HIDE_NAVIGATION", hasHideNavigation(window))
        assertTrue("挂载后应含 FULLSCREEN", hasFullscreen(window))
    }

    @Test
    fun `冻结挂载 - 只隐藏状态栏不隐藏导航栏`() {
        val window = mountDialogWithGuard(frozen = true)
        assertFalse("冻结（含输入框）挂载后不应隐藏导航栏（切断 IME 冲突面）", hasHideNavigation(window))
        assertTrue("冻结挂载后应仍隐藏状态栏（与键盘无冲突）", hasFullscreen(window))
    }

    @Test
    fun `挂载时键盘可见 - 零系统栏操作`() {
        ImeVisibilityTracker.setImeVisibleForTest(true)
        val window = mountDialogWithGuard(frozen = false)
        assertFalse("键盘可见期间挂载应零系统栏操作（无 HIDE_NAVIGATION）", hasHideNavigation(window))
        assertFalse("键盘可见期间挂载应零系统栏操作（无 FULLSCREEN）", hasFullscreen(window))
        ImeVisibilityTracker.setImeVisibleForTest(false)
    }

    @Test
    fun `冻结进入 - 恢复导航栏显示且保留状态栏隐藏`() {
        val window = mountDialogWithGuard(frozen = false)
        assertTrue("冻结前应已隐藏导航栏", hasHideNavigation(window))

        composeRule.runOnUiThread {
            DialogSystemBarFreezeScope.enterFreeze(window)
        }
        composeRule.waitForIdle()
        assertFalse("冻结进入（嵌套输入框挂载）应清除 HIDE_NAVIGATION", hasHideNavigation(window))
        assertTrue("FULLSCREEN（状态栏）与键盘无冲突应保留", hasFullscreen(window))
    }

    @Test
    fun `解冻 - 延迟后恢复导航栏隐藏`() {
        val window = mountDialogWithGuard(frozen = false)
        assertTrue("冻结前应已隐藏导航栏", hasHideNavigation(window))

        composeRule.runOnUiThread {
            DialogSystemBarFreezeScope.enterFreeze(window)
        }
        composeRule.waitForIdle()
        assertFalse("冻结期间应恢复导航栏显示", hasHideNavigation(window))

        composeRule.runOnUiThread {
            DialogSystemBarFreezeScope.exitFreeze(window)
        }
        composeRule.waitForIdle()
        assertFalse("解冻后延迟期间仍保持导航栏显示（等键盘收起动画落定）", hasHideNavigation(window))

        // 推进主线程延迟任务（350ms 恢复延迟）
        shadowOf(Looper.getMainLooper()).idleFor(400, TimeUnit.MILLISECONDS)
        composeRule.waitForIdle()
        assertTrue("解冻延迟结束后应恢复导航栏隐藏", hasHideNavigation(window))
    }

    @Test
    fun `解冻延迟内二次校验 - 键盘仍可见则不恢复隐藏`() {
        val window = mountDialogWithGuard(frozen = false)
        composeRule.runOnUiThread {
            DialogSystemBarFreezeScope.enterFreeze(window)
        }
        composeRule.waitForIdle()
        composeRule.runOnUiThread {
            DialogSystemBarFreezeScope.exitFreeze(window)
        }
        // 延迟回调执行前键盘仍可见（恢复链路二次校验应放行）
        ImeVisibilityTracker.setImeVisibleForTest(true)
        shadowOf(Looper.getMainLooper()).idleFor(400, TimeUnit.MILLISECONDS)
        composeRule.waitForIdle()
        assertFalse("键盘仍可见时延迟恢复应被二次校验拦截", hasHideNavigation(window))
        ImeVisibilityTracker.setImeVisibleForTest(false)
    }

    @Test
    fun `窗口销毁 - 解除跟踪且全局可见性回落`() {
        val showDialog = mutableStateOf(true)
        val dialogWindow = mutableStateOf<Window?>(null)
        composeRule.setContent {
            if (showDialog.value) {
                Dialog(onDismissRequest = {}) {
                    DialogSystemBarGuard()
                    val dialogView = LocalView.current
                    DisposableEffect(Unit) {
                        dialogWindow.value = findDialogWindow(dialogView)
                        onDispose {}
                    }
                    Text("守卫测试")
                }
            }
        }
        composeRule.waitForIdle()
        val window = checkNotNull(dialogWindow.value) { "应捕获到 Dialog 窗口" }

        ImeVisibilityTracker.imeVisibilityExtractor = { true }
        ImeVisibilityTracker.onInsetsApplied(window.decorView, imeInsetsWith(200), window)
        assertTrue("键盘可见期间全局应可见", ImeVisibilityTracker.isImeVisible)

        composeRule.runOnUiThread { showDialog.value = false }
        composeRule.waitForIdle()
        assertFalse("窗口销毁后应解除跟踪", ImeVisibilityTracker.isImeVisible)
        assertFalse("销毁窗口应不可查询", ImeVisibilityTracker.isImeVisibleFor(window))
    }
}
