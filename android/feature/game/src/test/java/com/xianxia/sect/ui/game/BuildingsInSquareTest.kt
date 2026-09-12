package com.xianxia.sect.ui.game

import com.xianxia.sect.core.engine.domain.building.BuildingFeatureRegistry
import com.xianxia.sect.core.model.GridBuildingData
import com.xianxia.sect.ui.game.building.registerDefaults
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 一键拆除-区域选择：buildingsInSquare 纯函数单元测试。
 *
 * 覆盖：正方形区域几何（奇数/偶数直径对称）、矩形重叠边界（半开区间）、
 * 不可拆除建筑排除、直径越界钳制、并集累积幂等守卫（用户确认语义）。
 */
class BuildingsInSquareTest {

    /**
     * 注册 BuildingFeature 默认表（Application 启动时由 registerDefaults 填充，
     * 单元测试无 Application——需手动调用；ConcurrentHashMap put 幂等，重复调用安全）。
     */
    @Before
    fun registerFeatures() {
        BuildingFeatureRegistry.registerDefaults()
    }

    /** 构造 1x1 占地建筑（宽高显式 1，精确单格语义） */
    private fun b1(id: String, x: Int, y: Int, name: String = "灵田"): GridBuildingData =
        GridBuildingData(gridX = x, gridY = y, width = 1, height = 1, displayName = name, instanceId = id)

    // ============================================================
    // 正方形区域几何
    // ============================================================

    @Test
    fun `buildingsInSquare - 直径3中心对称 命中中心及四周各1格`() {
        val buildings = listOf(
            b1("center", 5, 5),
            b1("topLeft", 4, 4),
            b1("bottomRight", 6, 6),
            b1("outX", 7, 5),   // x=7 超出 [4,7) 右缘
            b1("outY", 5, 7)    // y=7 超出 [4,7) 下缘
        )

        val result = buildingsInSquare(buildings, centerX = 5, centerY = 5, diameter = 3)

        assertEquals("应命中区域内 3 栋（列数恰 3：x ∈ [4,6]）", setOf("center", "topLeft", "bottomRight"), result)
        assertTrue("边界外建筑不得命中", "outX" !in result && "outY" !in result)
    }

    @Test
    fun `buildingsInSquare - 直径20范围正确 左缘10命中 右缘30不命中`() {
        val buildings = listOf(
            b1("leftEdge", 10, 20),  // = cx-10 左缘，命中
            b1("center", 20, 20),
            b1("nearRight", 29, 20),
            b1("rightOut", 30, 20),  // = cx+10 超出右缘，不命中
            b1("topOut", 20, 9)      // y=9 超出 [10,30) 上缘，不命中
        )

        val result = buildingsInSquare(buildings, centerX = 20, centerY = 20, diameter = 20)

        assertEquals("区域内 3 栋全命中", setOf("leftEdge", "center", "nearRight"), result)
    }

    @Test
    fun `buildingsInSquare - 直径4偶数 左缘cx-2 右缘cx+1`() {
        val buildings = listOf(
            b1("leftEdge", 3, 5),   // = cx-2 左缘，命中
            b1("center", 5, 5),
            b1("rightEdge", 6, 5),  // = cx+1 右缘，命中
            b1("out", 7, 5)         // = cx+2 超出，不命中
        )

        val result = buildingsInSquare(buildings, centerX = 5, centerY = 5, diameter = 4)

        assertEquals("x ∈ [3,6] 共 4 列", setOf("leftEdge", "center", "rightEdge"), result)
    }

    @Test
    fun `buildingsInSquare - 直径5奇数对称 命中cx-2至cx+2`() {
        val buildings = listOf(
            b1("left2", 3, 5),  // = cx-2，命中
            b1("right2", 7, 5), // = cx+2，命中
            b1("out", 8, 5)     // = cx+3，不命中
        )

        val result = buildingsInSquare(buildings, centerX = 5, centerY = 5, diameter = 5)

        assertEquals("x ∈ [3,7] 共 5 列", setOf("left2", "right2"), result)
    }

    // ============================================================
    // 矩形重叠边界（半开区间）
    // ============================================================

    @Test
    fun `buildingsInSquare - 部分重叠命中 建筑跨区域边界`() {
        // 建筑占 [4,7)：左缘与区域 minX=4 对齐、右缘越过 maxXExclusive=7 左 1 格
        val buildings = listOf(
            GridBuildingData(gridX = 4, gridY = 4, width = 3, height = 1, displayName = "灵田", instanceId = "cross")
        )

        val result = buildingsInSquare(buildings, centerX = 5, centerY = 5, diameter = 3)

        assertEquals("跨边界部分重叠应命中", setOf("cross"), result)
    }

    @Test
    fun `buildingsInSquare - 紧贴边界不重叠 右缘等于区域左缘`() {
        // 区域 minX = 10 - 1 = 9；建筑右缘 == 9 → 不重叠；右缘 10 > 9 → 重叠
        val buildings = listOf(
            b1("touch", 7, 20, name = "仓库").let { it.copy(width = 2) },  // 占 [7,9)，紧贴不命中
            b1("overlap", 7, 20, name = "仓库").let { it.copy(width = 3) } // 占 [7,10)，命中
        )

        val result = buildingsInSquare(buildings, centerX = 10, centerY = 20, diameter = 3)

        assertEquals("紧贴边界不算重叠", setOf("overlap"), result)
    }

    @Test
    fun `buildingsInSquare - 大建筑仅1列与区域重叠 宽高参与判定`() {
        // 仓库 6x5，gridX=6：占 [6,12)，与区域 x ∈ [4,7) 仅第 1 列重叠
        val buildings = listOf(
            GridBuildingData(gridX = 6, gridY = 5, width = 6, height = 5, displayName = "仓库", instanceId = "big")
        )

        val result = buildingsInSquare(buildings, centerX = 5, centerY = 5, diameter = 3)

        assertEquals("大建筑部分重叠应命中", setOf("big"), result)
    }

    // ============================================================
    // 可拆除性过滤 / 边界输入
    // ============================================================

    @Test
    fun `buildingsInSquare - 不可拆除建筑排除 已注册建筑命中`() {
        val buildings = listOf(
            b1("unknown", 5, 5, name = "不存在的建筑"), // 未注册 → 不可拆除
            b1("mine", 5, 6, name = "灵矿场")           // 已注册 → 可拆除
        )

        val result = buildingsInSquare(buildings, centerX = 5, centerY = 5, diameter = 3)

        assertEquals("仅可拆除建筑命中", setOf("mine"), result)
    }

    @Test
    fun `buildingsInSquare - 空列表返回空集合`() {
        val result = buildingsInSquare(emptyList(), centerX = 5, centerY = 5, diameter = 3)

        assertEquals("空列表 → 空集合", emptySet<String>(), result)
    }

    @Test
    fun `buildingsInSquare - 直径越界钳制 0等同3 25等同20`() {
        // d=0 未钳制时区域仅中心格，此处 (4,5) 命中可证明钳制到 3
        val buildings = listOf(
            b1("in3", 4, 5),
            b1("in20", 10, 5),
            b1("out", 15, 5)
        )

        val resultMin = buildingsInSquare(buildings, centerX = 5, centerY = 5, diameter = 0)
        val resultMax = buildingsInSquare(buildings, centerX = 5, centerY = 5, diameter = 25)

        assertEquals("diameter=0 钳制为 3（x ∈ [4,7)）", setOf("in3"), resultMin)
        assertEquals("diameter=25 钳制为 20（x ∈ [-5,15)）", setOf("in3", "in20"), resultMax)
    }

    // ============================================================
    // 并集累积语义守卫（用户确认：新区域内已选中的建筑保持选中）
    // ============================================================

    @Test
    fun `buildingsInSquare - 重复框选并集累积 已选中建筑不被取消`() {
        // c(6,7) 在第一轮区域（y ∈ [4,7)）外、第二轮区域（center(6,6)，y ∈ [5,8)）内
        val buildings = listOf(
            b1("a", 5, 5),
            b1("b", 4, 4),
            b1("c", 6, 7)
        )
        // 模拟调用方 onTap 模式：merged = merged + buildingsInSquare(...)
        var merged: Set<String> = emptySet()
        merged += buildingsInSquare(buildings, centerX = 5, centerY = 5, diameter = 3) // 命中 a、b
        val secondRound = merged + buildingsInSquare(buildings, centerX = 6, centerY = 6, diameter = 3) // 命中 a、c

        assertEquals("第一轮命中 a、b", setOf("a", "b"), merged)
        assertEquals("第二轮并集：a 保持选中且不取消，新增 c、保留 b", setOf("a", "b", "c"), secondRound)
        assertTrue("重叠区已选中的 a 保持选中", "a" in secondRound)
    }
}
