package com.xianxia.sect.core.render

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * IslandCliffBridge 契约测试。
 *
 * JVM 环境**不加载 native 库**（`GameCoreBridge.ensureLoaded` 失败）⇒ 本测试锁定
 * 的是：
 * 1. **降级契约**：native 缺失时 [IslandCliffBridge.compose] 返回 null（而非抛异常/
 *    返回空数组）——调用方据此跳过崖壁层，不影响地图其余层。
 * 2. **输入校验**：非法尺寸/尺寸表不足同样走 null 降级（不进入 native 调用）。
 * 3. **布局契约常量**：与 C++ `island_cliff.h` 的 stride/池数/镜像位逐值一致
 *    （两处任一方改动而另一方未同步 → 本测试变红）。
 * 4. **池表结构**：池数 = [IslandCliffBridge.POOL_COUNT]、左右环共享同一组侧变体
 *    （靠镜像位区分）、元素均为合法纹理下标。
 *
 * 布局数学正确性（锚定/拼接/裁剪/随机/降级）由 C++ GTest `island_cliff_test` 守护
 * （12 用例）——纯函数在桌面直接执行，比 JNI 往返更直接。
 */
class IslandCliffBridgeTest {

    /** 生产纹理尺寸表（与 IslandCliffTextureSet.TEXTURE_SIZES 同源，此处独立复述防耦合） */
    private val textureSizes = floatArrayOf(
        1176f, 3552f, 1120f, 3368f, 1180f, 3552f,
        2560f, 1696f, 2304f, 1888f, 1832f, 2400f, 1828f, 2396f
    )

    @Test
    fun `native 不可用时 compose 返回 null 降级`() {
        val result = IslandCliffBridge.compose(
            cols = 128, rows = 128, tileSize = 48, seed = 42,
            textureSizes = textureSizes,
            textureMask = (1 shl IslandCliffBridge.TextureIndex.COUNT) - 1
        )
        assertNull("JVM 无 native 库时应返回 null（调用方跳过崖壁层）", result)
    }

    @Test
    fun `非法输入直接返回 null 不进入 native`() {
        val fullMask = (1 shl IslandCliffBridge.TextureIndex.COUNT) - 1
        assertNull(
            "cols=0",
            IslandCliffBridge.compose(0, 128, 48, 1, textureSizes, fullMask)
        )
        assertNull(
            "rows=0",
            IslandCliffBridge.compose(128, 0, 48, 1, textureSizes, fullMask)
        )
        assertNull(
            "tileSize=0",
            IslandCliffBridge.compose(128, 128, 0, 1, textureSizes, fullMask)
        )
        assertNull(
            "尺寸表不足（少于纹理数 × 2）",
            IslandCliffBridge.compose(
                128, 128, 48, 1,
                floatArrayOf(1176f, 3552f), fullMask
            )
        )
    }

    @Test
    fun `布局契约常量与 C++ island_cliff_h 一致`() {
        // 与 C++ kIslandCliffStride / kIslandCliffPoolCount / kIslandCliffFlagMirrorX
        // 逐值一致；任一处改动而另一方未同步即失败
        assertEquals("PIECE_STRIDE 须等于 kIslandCliffStride", 10, IslandCliffBridge.PIECE_STRIDE)
        assertEquals("POOL_COUNT 须等于 kIslandCliffPoolCount", 5, IslandCliffBridge.POOL_COUNT)
        assertEquals("FLAG_MIRROR_X 须等于 kIslandCliffFlagMirrorX", 1, IslandCliffBridge.FLAG_MIRROR_X)
        assertEquals("纹理总数", 7, IslandCliffBridge.TextureIndex.COUNT)
        assertEquals(
            "尺寸表长度须为纹理数 × (w,h)",
            IslandCliffBridge.TextureIndex.COUNT * 2,
            textureSizes.size
        )
    }

    @Test
    fun `条目字段下标连续且覆盖 stride`() {
        val fields = listOf(
            IslandCliffBridge.Field.TEX, IslandCliffBridge.Field.X, IslandCliffBridge.Field.Y,
            IslandCliffBridge.Field.W, IslandCliffBridge.Field.H,
            IslandCliffBridge.Field.U0, IslandCliffBridge.Field.V0,
            IslandCliffBridge.Field.U1, IslandCliffBridge.Field.V1,
            IslandCliffBridge.Field.FLAGS
        )
        assertEquals("字段数须等于 PIECE_STRIDE", IslandCliffBridge.PIECE_STRIDE, fields.size)
        assertEquals("字段下标须为 0..stride-1 连续", (0 until IslandCliffBridge.PIECE_STRIDE).toList(), fields)
    }

    @Test
    fun `池表结构合法且左右环共享侧变体`() {
        val pools = IslandCliffBridge.POOLS
        assertEquals("池数须等于 POOL_COUNT", IslandCliffBridge.POOL_COUNT, pools.size)
        for ((i, pool) in pools.withIndex()) {
            assertTrue("池 $i 不得为空（空池会让合成器退化为无输出）", pool.isNotEmpty())
            for (t in pool) {
                assertTrue(
                    "池 $i 含越界纹理下标 $t（合法范围 0..${IslandCliffBridge.TextureIndex.COUNT - 1}）",
                    t in 0 until IslandCliffBridge.TextureIndex.COUNT
                )
            }
        }
        // 左右环（池 0/1）共享同一组侧变体——右侧靠镜像位区分，不重复烘焙纹理
        assertTrue(
            "左右环应共享同一组侧变体（右侧以镜像位复用）",
            pools[0].contentEquals(pools[1])
        )
        assertEquals("侧变体应为 3 个真变体", 3, pools[0].size)
        assertEquals("下环变体数", 2, pools[2].size)
        assertEquals("左下角应单变体", 1, pools[3].size)
        assertEquals("右下角应单变体", 1, pools[4].size)
    }

    @Test
    fun `观感微调参数在合理区间`() {
        // topInset：岛面顶线内缩（0 = 素材顶边贴地图边；素材顶部草沿实测 46~67px）
        assertTrue(
            "topInset 应在 [0, 128) 区间（超出会把崖壁整体推离地图边）",
            IslandCliffBridge.TOP_INSET >= 0f && IslandCliffBridge.TOP_INSET < 128f
        )
        // 下环铺装区间比例：实测转角实体岩体自内缘向内 0.36~0.93 宽
        for ((name, v) in listOf(
            "BOTTOM_START_RATIO" to IslandCliffBridge.BOTTOM_START_RATIO,
            "BOTTOM_END_RATIO" to IslandCliffBridge.BOTTOM_END_RATIO
        )) {
            assertTrue(
                "$name=$v 应在 (0, 1) 区间（0 = 不重叠；1 = 覆盖整个转角宽）",
                v > 0f && v < 1f
            )
        }
    }
}
