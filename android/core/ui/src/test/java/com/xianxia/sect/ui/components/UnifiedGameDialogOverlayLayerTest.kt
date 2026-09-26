package com.xianxia.sect.ui.components

import androidx.activity.ComponentActivity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * `UnifiedGameDialog` 的**窗口级 overlay 槽位能否承载「层外点击只关本层」**的能力验证
 * （G11 D-2：寻访结果页要在主界面之上叠一层，点框外关闭结果层而**不**关掉整个寻访窗）。
 *
 * ## 为什么必须实测而不是读代码就下结论
 * 容器的关闭点击挂在 `DialogScrim` 那层 **sibling** Box 上（`GameDialog.kt:281`），
 * 框内任何 `clickable` 都拦不住它——这是「叠层」方案唯一的真实摩擦点。
 * 结论决定实现分支：能拦住 ⇒ 结果层用 `overlay` 槽位（A 案）；
 * 拦不住 ⇒ 结果层必须另起独立 `UnifiedGameDialog` 窗口（B 案）。
 *
 * ## 用例与判据
 * | 用例 | 钉住的语义 |
 * |---|---|
 * | [overlay 层外点击只关结果层] | overlay 全屏 clickable 吃掉框内空白点击，整窗不关（A 案成立前提） |
 * | [overlay 层内元素优先消费] | 奖励格自己的 clickable 先于层背景消费（Q39「点框不弹详情、仅防误关」） |
 * | [遮罩关闭开关关掉后点击遮罩不关整窗] | 容器能做成「不由框外点击关闭」 |
 * | [遮罩关闭开关打开时点击遮罩关整窗] | 上一条的**正向对照**——0 计数不是测不到点击的假绿 |
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class UnifiedGameDialogOverlayLayerTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @After
    fun tearDown() {
        // IME 跟踪器与系统栏冻结作用域为全局单例（Dialog 窗口经 DialogSystemBarGuard 接入），测试间隔离
        ImeVisibilityTracker.resetForTest()
        SystemBarFreezeScope.resetForTest()
        DialogSystemBarFreezeScope.resetForTest()
    }

    @Test
    fun `overlay 层外点击只关结果层 - 不关整个对话框窗口`() {
        var windowDismisses = 0
        var layerDismisses = 0
        composeRule.setContent {
            RenderDialogWithResultLayer(
                onDismissRequest = { windowDismisses++ },
                onLayerDismissRequest = { layerDismisses++ }
            )
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(LAYER_TAG).assertIsDisplayed()
        // 节点中心是层内空白（奖励格被钉到 TopStart），等价于「点框外」
        composeRule.onNodeWithTag(LAYER_TAG).performClick()

        assertEquals("点击结果层空白处必须关闭结果层", 1, layerDismisses)
        assertEquals(
            "结果层自己吃掉点击后，容器的关闭通道不得被连带触发——" +
                "这一条不成立就说明 overlay 槽位拦不住 scrim，D-2 要退到独立窗口方案",
            0,
            windowDismisses,
        )
    }

    @Test
    fun `overlay 层内元素优先消费点击 - 点奖励格不触发层外关闭`() {
        var cellClicks = 0
        var layerDismisses = 0
        composeRule.setContent {
            RenderDialogWithResultLayer(
                onDismissRequest = {},
                onLayerDismissRequest = { layerDismisses++ },
                onCellClick = { cellClicks++ }
            )
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(CELL_TAG).performClick()

        assertEquals("奖励格自己的 clickable 必须吃到点击", 1, cellClicks)
        assertEquals("层内元素消费后，层背景不得再收到同一次点击（否则点格子会把结果页关掉）", 0, layerDismisses)
    }

    @Test
    fun `遮罩关闭开关关掉后 - 点击遮罩不关整窗`() {
        var windowDismisses = 0
        composeRule.setContent {
            UnifiedGameDialog(
                onDismissRequest = { windowDismisses++ },
                title = "寻访",
                mode = DialogMode.Half,
                dismissOnClickOutside = false
            ) {
                Text(MAIN_CONTENT_MARKER)
            }
        }
        composeRule.waitForIdle()

        // 点遮罩左上角：Half 模式下 frame 是 0.83w×0.78h 居中，角上必然落在框外
        // （点节点中心会被 frame 自己的 swallow-click 吃掉，测不到遮罩）
        composeRule.onNodeWithTag(SCRIM_TAG).performTouchInput { click(Offset(OUTSIDE_FRAME_X, OUTSIDE_FRAME_Y)) }

        assertEquals("dismissOnClickOutside=false 时框外点击不应关闭对话框", 0, windowDismisses)
    }

    @Test
    fun `遮罩关闭开关打开时 - 同样的点击会关整窗（正向对照）`() {
        var windowDismisses = 0
        composeRule.setContent {
            UnifiedGameDialog(
                onDismissRequest = { windowDismisses++ },
                title = "寻访",
                mode = DialogMode.Half,
                dismissOnClickOutside = true
            ) {
                Text(MAIN_CONTENT_MARKER)
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(SCRIM_TAG).performTouchInput {
            click(Offset(OUTSIDE_FRAME_X, OUTSIDE_FRAME_Y))
        }

        assertEquals(
            "正向对照：开关打开时同一条点击路径必须能关窗，" +
                "否则上一条用例的 0 计数只是「点不到遮罩」的假绿",
            1,
            windowDismisses,
        )
    }

    /** 复刻寻访结果层的结构：容器关外部点击 + overlay 槽位铺满窗口并自管层外点击。 */
    @Test
    fun `overlay 内容渲染在窗口最上层 - 下层主界面收不到点击`() {
        var windowDismisses = 0
        var layerDismisses = 0
        composeRule.setContent {
            RenderDialogWithResultLayer(
                onDismissRequest = { windowDismisses++ },
                onLayerDismissRequest = { layerDismisses++ }
            )
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(LAYER_TAG).assertIsDisplayed()
        // 主界面文字在 frame 之内、overlay 之下：点它的位置应当被结果层吃掉
        composeRule.onNodeWithText(MAIN_CONTENT_MARKER).performClick()

        assertEquals("overlay 的点击命中必须优先于 frame 内容（z 序在最上层）", 1, layerDismisses)
        assertEquals("下层主界面不得收到同一次点击", 0, windowDismisses)
    }

    @Composable
    private fun RenderDialogWithResultLayer(
        onDismissRequest: () -> Unit,
        onLayerDismissRequest: () -> Unit,
        onCellClick: () -> Unit = {}
    ) {
        UnifiedGameDialog(
            onDismissRequest = onDismissRequest,
            title = "寻访",
            mode = DialogMode.Full,
            dismissOnClickOutside = false,
            overlay = {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .testTag(LAYER_TAG)
                        .clickable(onClick = onLayerDismissRequest),
                    contentAlignment = Alignment.Center
                ) {
                    Text("恭喜获得")
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .size(CELL_SIZE_DP.dp)
                            .testTag(CELL_TAG)
                            .clickable(onClick = onCellClick)
                    )
                }
            }
        ) {
            Text(MAIN_CONTENT_MARKER)
        }
    }

    private companion object {
        const val LAYER_TAG = "gacha_result_layer"
        const val CELL_TAG = "gacha_reward_cell"
        const val MAIN_CONTENT_MARKER = "主界面内容"
        const val SCRIM_TAG = "scrim"
        const val CELL_SIZE_DP = 48

        /** 遮罩上必然落在框外的取样点（Half 模式 frame 居中，四周留 8.5%/11% 边距） */
        const val OUTSIDE_FRAME_X = 2f
        const val OUTSIDE_FRAME_Y = 2f
    }
}
