package com.xianxia.sect.ui.game.sect

import com.xianxia.sect.core.GameConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.random.Random

/**
 * 云层动画引擎测试。
 *
 * 覆盖维度：
 * - 只在世界外生成（左外生成右移 / 右外生成左移，两种方向都会出现）
 * - 移动速度恰为 3 格/秒（与 GameConfig.SectMap.TILE_SIZE 同源）
 * - 完全移出世界后消失（活跃计数必须出现过下降——计数只会因出界销毁而减少）
 * - 帧间隔钳制（卡顿/后台恢复后云朵不瞬移）
 * - 快照条目值域合法（无 NaN、y 在顶部条带、alpha/类型索引在配置区间、宽高为正）
 * - 云量不泄漏（并发数维持在有界区间）
 *
 * 确定性：注入固定 seed 的 Random + 外部时间戳推进，全程可复现。
 */
class CloudLayerAnimatorTest {

    /** 世界像素宽度（与真实宗门地图一致：128 格 × tileSize） */
    private val worldW = GameConfig.SectMap.WORLD_PIXEL_WIDTH.toFloat()

    private fun animator(seed: Int = 42): CloudLayerAnimator =
        CloudLayerAnimator(worldW, Random(seed))

    // ── 生成/方向 ──

    @Test
    fun `初始无云且快照为 null`() {
        val a = animator()
        assertNull(a.snapshot())
        assertEquals(0, a.activeCount())
        assertFalse("无云时 update 不应要求渲染", a.update(0L))
    }

    @Test
    fun `云朵只在世界外生成且两种移动方向都会出现`() {
        val a = animator(seed = 42)
        var now = 0L
        var prevCount = 0
        // 模拟 60 秒（100ms 步进）：每次计数增加（新云生成）时，
        // 快照末条（本次生成追加的云）必须完全出生在世界外
        val spawnSides = mutableSetOf<String>()
        repeat(600) {
            now += 100L
            a.update(now)
            val count = a.activeCount()
            if (count > prevCount) {
                val snap = a.snapshot() ?: return@repeat
                val idx = snap.size - CloudLayerAnimator.CLOUD_DATA_STRIDE
                val x = snap[idx]
                val w = snap[idx + 2]
                val offLeft = x + w <= 0f
                val offRight = x >= worldW
                assertTrue("新云出生位置必须完全在世界外 (x=$x, w=$w)", offLeft || offRight)
                spawnSides += if (offLeft) "LEFT" else "RIGHT"
            }
            prevCount = count
        }
        assertTrue(
            "两种移动方向都应出现（左外生成→右移 / 右外生成→左移），实际 $spawnSides",
            spawnSides == setOf("LEFT", "RIGHT")
        )
    }

    // ── 速度 ──

    @Test
    fun `速度常量与配置同源_每秒 3 格`() {
        assertEquals(3, CloudLayerAnimator.SPEED_TILES_PER_SECOND)
        val expectedPxPerMs = 3f * GameConfig.SectMap.TILE_SIZE / 1000f
        assertEquals(expectedPxPerMs, CloudLayerAnimator.SPEED_PX_PER_MS, 0.0001f)
        // 1 秒位移 = 3 格 × tileSize（与 GameConfig.SectMap.TILE_SIZE 同源）
        assertEquals(3f * GameConfig.SectMap.TILE_SIZE, CloudLayerAnimator.SPEED_PX_PER_MS * 1000f, 0.001f)
    }

    @Test
    fun `缩放区间为原生尺寸一半_云朵整体缩小`() {
        // 0.8~1.6 → 0.4~0.8：所有云朵显示尺寸减半（锁住 50% 缩小不变量）
        assertEquals(0.4f, CloudLayerAnimator.SCALE_MIN)
        assertEquals(0.8f, CloudLayerAnimator.SCALE_MAX)
        assertEquals("最大/最小缩放比值应保持不变（整体减半）", 2.0f, CloudLayerAnimator.SCALE_MAX / CloudLayerAnimator.SCALE_MIN, 0.0001f)
    }

    @Test
    fun `移动位移恰为速度乘 dt`() {
        val a = animator(seed = 7)
        var now = 0L
        val dtMs = 250L
        val step = CloudLayerAnimator.SPEED_PX_PER_MS * dtMs
        var stableWindowsChecked = 0
        // 240s 内寻找活跃云数量稳定的窗口（前后快照条目一一对应）验证位移
        repeat(960) {
            val before = a.snapshot()
            now += dtMs
            a.update(now)
            val after = a.snapshot()
            if (before != null && after != null && before.size == after.size) {
                for (i in before.indices step CloudLayerAnimator.CLOUD_DATA_STRIDE) {
                    val dx = after[i] - before[i]
                    assertEquals("云朵位移必须=3格/秒×dt", step, abs(dx), 0.01f)
                }
                stableWindowsChecked++
                if (stableWindowsChecked >= 3) return
            }
        }
        assertTrue("应观察到至少一个稳定窗口验证位移", stableWindowsChecked > 0)
    }

    // ── 出界消失 / 不泄漏 ──

    @Test
    fun `云朵完全移出世界后消失且云量不泄漏`() {
        val a = animator(seed = 42)
        var now = 0L
        var prevCount = 0
        var sawDecrease = false
        var maxCount = 0
        repeat(3000) { // 300s
            now += 100L
            a.update(now)
            val count = a.activeCount()
            if (count < prevCount) sawDecrease = true
            maxCount = maxOf(maxCount, count)
            prevCount = count
        }
        assertTrue(
            "云朵应完全移出世界后消失——活跃计数必须出现过下降（计数只会因出界销毁减少）",
            sawDecrease
        )
        assertTrue(
            "云量不应泄漏（并发数需有界），最大并发=$maxCount",
            maxCount <= CloudLayerAnimator.TARGET_COUNT_MAX + 2
        )
    }

    // ── 值域合法性 ──

    @Test
    fun `快照条目值域合法`() {
        val a = animator(seed = 42)
        var now = 0L
        repeat(3000) { // 300s 全程逐条校验
            now += 100L
            a.update(now)
            val snap = a.snapshot() ?: return@repeat
            for (i in snap.indices step CloudLayerAnimator.CLOUD_DATA_STRIDE) {
                val x = snap[i]
                val y = snap[i + 1]
                val w = snap[i + 2]
                val h = snap[i + 3]
                val spriteIndex = snap[i + 4]
                val alpha = snap[i + 5]
                assertFalse("x 不允许 NaN", x.isNaN())
                assertFalse("y 不允许 NaN", y.isNaN())
                assertTrue("宽必须为正 (w=$w)", w > 0f)
                assertTrue("高必须为正 (h=$h)", h > 0f)
                assertTrue("y 必须位于世界顶部条带内 (y=$y)", y >= 0f && y < CloudLayerAnimator.BAND_MAX_Y_PX)
                assertTrue(
                    "spriteIndex 必须在云层类型区间内 (index=$spriteIndex)",
                    spriteIndex >= 0f && spriteIndex < CloudLayerAnimator.CLOUD_TYPE_COUNT
                )
                assertTrue(
                    "alpha 必须在配置区间内 (alpha=$alpha)",
                    alpha >= CloudLayerAnimator.ALPHA_MIN && alpha <= CloudLayerAnimator.ALPHA_MAX
                )
            }
        }
    }

    // ── 帧间隔钳制 ──

    @Test
    fun `帧间隔钳制_卡顿后云朵不瞬移`() {
        val a = animator(seed = 3)
        var now = 0L
        // 等待出现单朵云状态（确定性 seed）
        var guard = 0
        while (a.activeCount() != 1 && guard < 1000) {
            now += 100L
            a.update(now)
            guard++
        }
        assertTrue("应出现单朵云状态", a.activeCount() == 1)
        val xBefore = a.snapshot()!![0]
        // 5000ms 大跳（模拟卡顿/后台恢复）：dt 钳制到 500ms，位移 ≤ 48px
        now += 5000L
        a.update(now)
        assertTrue("大跳后仍应有云", a.activeCount() >= 1)
        val dx = abs(a.snapshot()!![0] - xBefore)
        val clampBound = CloudLayerAnimator.SPEED_PX_PER_MS * CloudLayerAnimator.DT_CLAMP_MS + 0.01f
        assertTrue("dt 钳制后位移应≤500ms 对应距离（48px），实际 $dx", dx <= clampBound)
    }
}
