package com.xianxia.sect.ui.components

import android.app.Activity
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * rememberImeAwareAutoFocusRequester 聚焦重试守卫测试：
 * hasTextInputFocus 判定逻辑——已有文本输入焦点时不重复 requestFocus，
 * 杜绝 ROM 智能输入法在检测信号不稳定时反复重弹键盘。
 *
 * Compose 文本字段聚焦时 Android 层
 * findFocus() 返回 ComposeView（AndroidComposeView，非 EditText/TextView），
 * 补充 `focused === view && view.hasFocus()` 判定覆盖该场景。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ImeAwareAutoFocusTest {

    private val activity: Activity by lazy {
        Robolectric.buildActivity(Activity::class.java).setup().get()
    }

    private fun rootWith(vararg children: android.view.View): ViewGroup =
        LinearLayout(activity).apply {
            children.forEach { addView(it) }
        }

    private fun focusedEditText(): EditText =
        EditText(activity).apply {
            isFocusable = true
            isFocusableInTouchMode = true
        }

    @Test
    fun `聚焦的 EditText - 判定为文本输入焦点`() {
        val editText = focusedEditText()
        val root = rootWith(editText)
        assertTrue("EditText 应可聚焦", editText.requestFocus())
        assertTrue("聚焦的 EditText 应判定为文本输入焦点", hasTextInputFocus(root))
    }

    @Test
    fun `未聚焦的 EditText - 判定为无文本输入焦点`() {
        val editText = focusedEditText()
        val root = rootWith(editText)
        assertFalse("未聚焦时不构成文本输入焦点", hasTextInputFocus(root))
    }

    @Test
    fun `聚焦的 Button - 判定为无文本输入焦点`() {
        val button = Button(activity).apply {
            isFocusable = true
            isFocusableInTouchMode = true
        }
        val root = rootWith(button)
        assertTrue("Button 应可聚焦", button.requestFocus())
        assertFalse("聚焦的非文本输入视图不应判定为文本输入焦点", hasTextInputFocus(root))
    }

    @Test
    fun `无任何焦点 - 判定为无文本输入焦点`() {
        val root = rootWith(Button(activity), EditText(activity))
        assertFalse("无焦点时不应判定为文本输入焦点", hasTextInputFocus(root))
    }

    @Test
    fun `文本输入焦点位于兄弟分支 - 仍判定为文本输入焦点`() {
        val editText = focusedEditText()
        val siblingContainer = LinearLayout(activity).apply { addView(editText) }
        val root = rootWith(siblingContainer)
        assertTrue("EditText 应可聚焦", editText.requestFocus())
        assertTrue("子树内任意文本输入焦点均应判定命中", hasTextInputFocus(root))
    }

    // ── Compose 场景 ──────────────────────────
    // Compose 文本字段聚焦时 Android 层 findFocus() 返回 ComposeView 自身
    //（AndroidComposeView，非 EditText/TextView），以 FrameLayout 模拟该形态

    private fun composeStyleView(): FrameLayout =
        FrameLayout(activity).apply {
            isFocusable = true
            isFocusableInTouchMode = true
        }

    @Test
    fun `Compose 文本聚焦 - ComposeView 自持焦点判定为文本输入焦点`() {
        val composeView = composeStyleView()
        val root = rootWith(composeView)
        assertTrue("ComposeView 应可聚焦", composeView.requestFocus())
        assertTrue(
            "Compose 内部聚焦时 ComposeView 自持焦点应判定为文本输入焦点（第五根因修复）",
            hasTextInputFocus(composeView)
        )
    }

    @Test
    fun `ComposeView 无焦点 - 不判定为文本输入焦点`() {
        val composeView = composeStyleView()
        assertFalse("未聚焦的 ComposeView 不应判定为文本输入焦点", hasTextInputFocus(composeView))
    }

    @Test
    fun `ComposeView 聚焦但判定入口为父容器 - 不误判命中`() {
        // 判定入口（LocalView）不是焦点持有者本身时，focused === view 不成立，
        // 保持 false——防 ComposeView 在父链不同层级时的误判
        val composeView = composeStyleView()
        val root = rootWith(composeView)
        assertTrue("ComposeView 应可聚焦", composeView.requestFocus())
        assertFalse(
            "判定入口为父容器时不应命中 focused === view 分支",
            hasTextInputFocus(root)
        )
    }
}
