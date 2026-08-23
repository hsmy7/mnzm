package com.xianxia.sect.ui.components

import android.app.Activity
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * rememberImeAwareAutoFocusRequester 聚焦重试守卫测试
 * （2026-08 第四根因键盘频闪根治）：
 * hasTextInputFocus 判定逻辑——已有文本输入焦点时不重复 requestFocus，
 * 杜绝 ROM 智能输入法在检测信号不稳定时反复重弹键盘。
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
}
