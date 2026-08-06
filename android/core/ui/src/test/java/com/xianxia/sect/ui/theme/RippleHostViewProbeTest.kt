package com.xianxia.sect.ui.theme

import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * RippleHostView 探测测试（Bugly #9076 修复有效性的决定性验证）：
 * 在 XianxiaTheme 内渲染 M3 Button 并模拟点击，遍历视图树检查是否创建了 RippleHostView。
 * #9076 崩溃点是 RippleHostView 硬件水波纹动画的 RenderNode.addAnimator 原生 abort——
 * 只要视图树中还存在 RippleHostView，崩溃路径就未被阻断（alpha=0 只画透明，节点与动画照常）。
 * RippleHostView 为 material-ripple 内部类，按类名匹配（反射式探测）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RippleHostViewProbeTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun `XianxiaTheme - 点击 M3 Button 后视图树不应存在 RippleHostView`() {
        composeRule.setContent {
            XianxiaTheme {
                Button(onClick = {}) {
                    Text("probe")
                }
            }
        }
        composeRule.onNodeWithText("probe").performClick()
        composeRule.waitForIdle()

        val root = composeRule.activity.findViewById<ViewGroup>(android.R.id.content)
        val hosts = collectByClassName(root, RIPPLE_HOST_VIEW_CLASS_NAME)
        assertTrue(
            "视图树中仍存在 RippleHostView(${hosts.size} 个)——#9076 崩溃路径未被阻断，" +
                "alpha=0 只使涟漪不可见但硬件动画机制照常运行",
            hosts.isEmpty()
        )
    }

    private fun collectByClassName(view: View, className: String): List<View> {
        val found = mutableListOf<View>()
        if (view.javaClass.name == className) found.add(view)
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                found.addAll(collectByClassName(view.getChildAt(i), className))
            }
        }
        return found
    }

    private companion object {
        const val RIPPLE_HOST_VIEW_CLASS_NAME = "androidx.compose.material.ripple.RippleHostView"
    }
}
