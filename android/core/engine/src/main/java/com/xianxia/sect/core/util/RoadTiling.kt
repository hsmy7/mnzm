package com.xianxia.sect.core.util

import com.xianxia.sect.core.model.RoadData

/**
 * 石板道路形态枚举（与 C++ gamecore/map/road_system.h RoadTileType 一一对应）。
 *
 * 渲染端按 [RoadData.roadType] 选取道路主体/路口素材；[tileTypeForBitmask]
 * 提供位掩码 → 形态的唯一映射（任意复杂道路网络自动归类）。
 */
enum class RoadTileType {
    /** 单格（无邻居/死路） */
    SINGLE,
    /** 横向直路（左右） */
    HORIZONTAL,
    /** 纵向直路（上下） */
    VERTICAL,
    /** 左上转角（上+左） */
    CORNER_TOP_LEFT,
    /** 右上转角（上+右） */
    CORNER_TOP_RIGHT,
    /** 左下转角（下+左） */
    CORNER_BOTTOM_LEFT,
    /** 右下转角（下+右） */
    CORNER_BOTTOM_RIGHT,
    /** T 型（主干朝上：上+左+右） */
    T_UP,
    /** T 型（主干朝右：上+下+右） */
    T_RIGHT,
    /** T 型（主干朝下：下+左+右） */
    T_DOWN,
    /** T 型（主干朝左：上+下+左） */
    T_LEFT,
    /** 十字路口（上下左右全通） */
    CROSS
}

/**
 * 道路位掩码工具（纯函数、零依赖、确定性；与 C++ 求解器双端同语义）。
 *
 * 邻接方向位：上=1 右=2 下=4 左=8。渲染/装配层据此决定：
 * - [tileTypeForBitmask]：自动选图（直/转角/T/十字）
 * - [roadBorderMask]：需要描边的方向 = 无道路邻居的方向（并行道路内部不重复描边）
 */
object RoadTiling {

    const val DIR_UP = 1
    const val DIR_RIGHT = 2
    const val DIR_DOWN = 4
    const val DIR_LEFT = 8
    const val MASK_ALL = 0xF

    /** 掩码中已连接的方向数。 */
    fun connectionCount(mask: Int): Int {
        var count = 0
        for (i in 0..3) if (mask and (1 shl i) != 0) ++count
        return count
    }

    /**
     * 位掩码 → 道路形态（自动选图核心）。
     *
     * 任意 4-bit 组合必然落在 12 类之一：
     *   0/1 连接 → 单格；2 连接（对边）→ 直路、（相邻边）→ 转角；
     *   3 连接 → T 型（主干 = 单独臂）；4 连接 → 十字。
     */
    fun tileTypeForBitmask(mask: Int): RoadTileType {
        val m = mask and MASK_ALL
        return when (connectionCount(m)) {
            0 -> RoadTileType.SINGLE
            // 死路（1 连接）按方向归为直路（道路端点仍是横向/纵向直路主体）
            1 -> when (m) {
                DIR_LEFT, DIR_RIGHT -> RoadTileType.HORIZONTAL
                DIR_UP, DIR_DOWN -> RoadTileType.VERTICAL
                else -> RoadTileType.SINGLE
            }
            2 -> when (m) {
                DIR_UP or DIR_DOWN -> RoadTileType.VERTICAL
                DIR_LEFT or DIR_RIGHT -> RoadTileType.HORIZONTAL
                DIR_UP or DIR_LEFT -> RoadTileType.CORNER_TOP_LEFT
                DIR_UP or DIR_RIGHT -> RoadTileType.CORNER_TOP_RIGHT
                DIR_DOWN or DIR_LEFT -> RoadTileType.CORNER_BOTTOM_LEFT
                DIR_DOWN or DIR_RIGHT -> RoadTileType.CORNER_BOTTOM_RIGHT
                else -> RoadTileType.SINGLE
            }
            3 -> when (m) {
                DIR_UP or DIR_LEFT or DIR_RIGHT -> RoadTileType.T_UP   // 缺下
                DIR_DOWN or DIR_LEFT or DIR_RIGHT -> RoadTileType.T_DOWN // 缺上
                DIR_UP or DIR_DOWN or DIR_RIGHT -> RoadTileType.T_RIGHT // 缺左
                DIR_UP or DIR_DOWN or DIR_LEFT -> RoadTileType.T_LEFT   // 缺右
                else -> RoadTileType.SINGLE
            }
            4 -> RoadTileType.CROSS
            else -> RoadTileType.SINGLE
        }
    }

    /**
     * 需要绘制边框的方向掩码 = 掩码按位取反（四方向）。
     *
     * 某侧有道路邻居 → 该侧位 1 → 不描边（内部）；某侧无道路邻居 → 该侧位 0 →
     * 描边（外缘）。这样并行道路内部相邻格永不重复描边，只有道路区域最外缘描边。
     */
    fun roadBorderMask(mask: Int): Int = MASK_ALL xor (mask and MASK_ALL)

    private fun packCell(x: Int, y: Int): Long =
        (x.toLong() shl 32) or (y.toLong() and 0xFFFFFFFF)

    /**
     * 计算某个坐标在道路集合中的位掩码（实时检查上下左右是否也是道路）。
     * 越界视为无道路邻居。供 place/remove 后组装 [RoadData] 使用。
     */
    fun bitmaskAt(roads: Collection<RoadData>, x: Int, y: Int, width: Int, height: Int): Int {
        val cellSet = HashSet<Long>(roads.size * 2)
        for (r in roads) cellSet.add(packCell(r.gridX, r.gridY))
        return bitmaskAt(cellSet, x, y, width, height)
    }

    /** 基于编码集合的位掩码查询（[cells] 为 `packCell(x,y)` 集合）。 */
    fun bitmaskAt(cells: Set<Long>, x: Int, y: Int, width: Int, height: Int): Int {
        var mask = 0
        if (x >= 0 && y >= 0 && x < width && y < height) {
            if (y - 1 >= 0 && packCell(x, y - 1) in cells) mask = mask or DIR_UP
            if (x + 1 < width && packCell(x + 1, y) in cells) mask = mask or DIR_RIGHT
            if (y + 1 < height && packCell(x, y + 1) in cells) mask = mask or DIR_DOWN
            if (x - 1 >= 0 && packCell(x - 1, y) in cells) mask = mask or DIR_LEFT
        }
        return mask
    }

    /**
     * 从道路集合构建渲染用每格位掩码数组（展平，index = row*cols+col；0 = 非道路）。
     * @return null 表示无道路（渲染端跳过整层）。
     */
    fun buildRoadMaskArray(roads: Collection<RoadData>, cols: Int, rows: Int): IntArray? {
        if (roads.isEmpty()) return null
        val cells = HashSet<Long>(roads.size * 2)
        for (r in roads) cells.add(packCell(r.gridX, r.gridY))
        val mask = IntArray(cols * rows)
        for (r in roads) {
            if (r.gridX in 0 until cols && r.gridY in 0 until rows) {
                mask[r.gridY * cols + r.gridX] = bitmaskAt(cells, r.gridX, r.gridY, cols, rows)
            }
        }
        return mask
    }
}
