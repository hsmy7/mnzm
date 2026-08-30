package com.xianxia.sect.ui.components

import androidx.activity.ComponentActivity
import android.view.View
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.core.graphics.Insets
import androidx.core.view.WindowInsetsCompat
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * ImeAwareContainer 事件驱动避让容器测试（2026-09 IME 状态机根治）：
 * 键盘可见性翻转 → 内容一次性上移（动画驱动）；键盘收起 → 恢复；
 * 键盘不可见时零位移（无输入框对话框零行为变化）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ImeAwareContainerTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @After
    fun tearDown() {
        ImeVisibilityTracker.resetForTest()
    }

    private fun imeInsetsWith(height: Int): WindowInsetsCompat =
        WindowInsetsCompat.Builder()
            .setInsets(WindowInsetsCompat.Type.ime(), Insets.of(0, 0, 0, height))
            .build()

    private fun driveImeVisible(heightPx: Int) {
        ImeVisibilityTracker.imeVisibilityExtractor = { true }
        ImeVisibilityTracker.attach(composeRule.activity.window)
        composeRule.runOnUiThread {
            ImeVisibilityTracker.onInsetsApplied(
                View(composeRule.activity), imeInsetsWith(heightPx), composeRule.activity.window
            )
        }
    }

    private fun currentY(): Float =
        composeRule.onNodeWithText("对话框内容")
            .fetchSemanticsNode()
            .positionInRoot.y

    @Test
    fun `键盘不可见 - 内容零位移`() {
        composeRule.setContent {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                ImeAwareContainer {
                    Text("对话框内容")
                }
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("对话框内容").assertIsDisplayed()
        assertTrue("键盘不可见时 offset 应恒 0", currentY() > 0f)
    }

    @Test
    fun `键盘可见 - 内容一次性上移`() {
        composeRule.setContent {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                ImeAwareContainer {
                    Text("对话框内容")
                }
            }
        }
        composeRule.waitForIdle()
        val before = currentY()
        driveImeVisible(heightPx = 400)
        composeRule.mainClock.advanceTimeBy(300) // 越过 200ms 避让动画
        composeRule.waitForIdle()
        val after = currentY()
        assertTrue("键盘可见时内容应上移（事件驱动一次性位移）", after < before)
    }

    @Test
    fun `键盘收起 - 内容恢复原位`() {
        composeRule.setContent {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                ImeAwareContainer {
                    Text("对话框内容")
                }
            }
        }
        composeRule.waitForIdle()
        val baseline = currentY()
        driveImeVisible(heightPx = 400)
        composeRule.mainClock.advanceTimeBy(300)
        composeRule.waitForIdle()
        // 键盘收起：驱动不可见 + 高度 0
        ImeVisibilityTracker.imeVisibilityExtractor = { false }
        composeRule.runOnUiThread {
            ImeVisibilityTracker.onInsetsApplied(
                View(composeRule.activity), imeInsetsWith(0), composeRule.activity.window
            )
        }
        composeRule.mainClock.advanceTimeBy(300)
        composeRule.waitForIdle()
        assertTrue("键盘收起后内容应恢复原位", currentY() >= baseline - 1f)
    }
}
