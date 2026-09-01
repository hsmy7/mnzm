package com.xianxia.sect.ui.game.map

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.xianxia.sect.ui.game.map.world.WorldCameraState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 世界地图拖动性能守护测试（2026 修复）。
 *
 * 根因：WorldMapScreen 的标记层（markers）在**组合作用域**读相机状态
 * （worldToScreenX/Y、isVisible）——每次拖动 pan 都触发整棵标记树重组 + 布局重排，
 * 几十个标记 × 60-120Hz 事件率导致主线程超载（违反"禁止 Composition 内读 State"）。
 *
 * 修复：相机读取下沉到 marker 的 graphicsLayer lambda（draw 阶段求值）与
 * MapBackground 的 DrawScope——拖动视角零重组零布局、仅重绘。
 *
 * 守护维度：
 * 1. 拖动相机不触发世界地图组合层重组（重组计数不变）
 * 2. 拖动后 marker 屏幕位置正确跟随相机（graphicsLayer 变换链路生效，行为回归）
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WorldMapScreenRecompositionTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private fun sampleItems(): List<MapItem> = listOf(
        MapItem.Sect(
            id = "s1", worldX = 300f, worldY = 300f, name = "青云宗",
            level = 0, levelName = "小型宗门", isPlayerSect = true,
            isRighteous = true, isPlayerOccupied = false, occupierSectId = null,
            isDiscovered = true, isHighlighted = false
        ),
        MapItem.Sect(
            id = "s2", worldX = 900f, worldY = 600f, name = "血刀门",
            level = 5, levelName = "中型宗门", isPlayerSect = false,
            isRighteous = false, isPlayerOccupied = false, occupierSectId = null,
            isDiscovered = true, isHighlighted = false
        )
    )

    @Test
    fun `pan camera - no recomposition of world map layer`() {
        val camera = WorldCameraState(worldWidth = 1698f, worldHeight = 926f)
        camera.updateViewport(1080, 2400)
        // 重组计数：普通数组而非 Compose state——组合体内写 snapshot state 会
        // 触发自身重组形成无限循环；数组写入不触发重组，重组发生时（若组合层
        // 仍订阅相机）lambda 重跑计数仍会递增，检测语义不变
        val recompositions = intArrayOf(0)
        composeRule.setContent {
            recompositions[0]++
            WorldMapScreen(items = sampleItems(), cameraState = camera)
        }
        // 等布局/onSizeChanged 副作用稳定后再取基线
        composeRule.waitForIdle()
        composeRule.waitForIdle()

        // 放大使视口小于世界（默认 autoScale 铺满时相机被钳制在角落无法平移）；
        // zoom 同样是相机 state 变化——组合层也不应重组（一并守护）
        composeRule.runOnIdle { camera.zoom(3f, 160f, 235f) }
        composeRule.waitForIdle()
        val stable = recompositions[0]

        // 模拟拖动：相机平移——组合层不读相机 → 不应触发任何重组
        composeRule.runOnIdle { camera.pan(40f, 25f) }
        composeRule.waitForIdle()

        assertEquals(
            "拖动/缩放相机不应触发世界地图组合层重组（相机读取已下沉 graphicsLayer draw 阶段）",
            stable, recompositions[0]
        )
        // pan 本身必须生效（zoom 后相机不在钳制死区）
        assertTrue("相机应已平移", camera.cameraX != 0f || camera.cameraY != 0f)
    }

    @Test
    fun `pan camera - marker screen position follows camera`() {
        val camera = WorldCameraState(worldWidth = 1698f, worldHeight = 926f)
        camera.updateViewport(1080, 2400)
        composeRule.setContent {
            WorldMapScreen(items = sampleItems(), cameraState = camera)
        }
        composeRule.waitForIdle()

        // 放大使视口小于世界（世界 1698×926 被 onSizeChanged autoScale 铺满时
        // 相机 y 向被钳制无法平移；放大后两个方向都可平移）
        composeRule.runOnIdle { camera.zoom(3f, 160f, 235f) }
        composeRule.waitForIdle()
        val beforeX = composeRule.onNodeWithText("青云宗").fetchSemanticsNode().positionInRoot.x

        // 拖动：向右下平移 40×25 屏幕像素
        composeRule.runOnIdle { camera.pan(40f, 25f) }
        composeRule.waitForIdle()
        val afterX = composeRule.onNodeWithText("青云宗").fetchSemanticsNode().positionInRoot.x

        assertTrue(
            "marker 屏幕位置应随相机平移而右移（graphicsLayer 变换生效）",
            afterX > beforeX + 30f
        )
    }
}
