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
     *
     * 圈复杂度偏高（22）为查表式映射本质：与 C++ road_system.h 同源并由
     * DiffRoadTest 全 16 掩码对拍守护，禁止重构（改写引入漂移风险）。
     */
    @Suppress("CyclomaticComplexMethod")
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

    /** 格坐标 → Long 编码（x 高 32 位 | y 低 32 位）；RoadMaskTracker 增量 diff 共用。 */
    internal fun packCell(x: Int, y: Int): Long =
        (x.toLong() shl 32) or (y.toLong() and 0xFFFFFFFF)

    /** [packCell] 编码还原列号。 */
    internal fun unpackX(cell: Long): Int = (cell ushr 32).toInt()

    /** [packCell] 编码还原行号。 */
    internal fun unpackY(cell: Long): Int = cell.toInt()

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
    @Suppress("ComplexCondition")  // 四邻边界检查为显式自解释判据（与 C++ road_system.h 同源，DiffRoadTest 守护）
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
     * 从道路集合构建渲染用每格位掩码数组（展平，index = row*cols+col）。
     *
     * **数组值为「位掩码 + 1」（1-based）**——若直接存
     * 位掩码（0 = 非道路），但**单格道路（无邻居）的邻接掩码也是 0**，渲染端
     * `mask == 0` 跳过判据把单格道路当非道路格跳过 → 玩家放置的第一格（无邻居）
     * 道路永不显示。改 1-based 后：0 = 非道路格；1 = 单格道路（原掩码 0）；
     * 2..16 = 原掩码 1..15。渲染端取 `raw - 1` 还原后交给合成器
     * （[RoadCompositorBridge] / C++ emitRoadDrawOps 均接收原始掩码 0..15）。
     *
     * @return null 表示无道路（渲染端跳过整层）
     */
    fun buildRoadMaskArray(roads: Collection<RoadData>, cols: Int, rows: Int): IntArray? {
        if (roads.isEmpty()) return null
        val cells = HashSet<Long>(roads.size * 2)
        for (r in roads) cells.add(packCell(r.gridX, r.gridY))
        val mask = IntArray(cols * rows)
        for (r in roads) {
            if (r.gridX in 0 until cols && r.gridY in 0 until rows) {
                // +1：单格道路（掩码 0）与"非道路格（数组默认 0）"解耦（渲染端 -1 还原）
                mask[r.gridY * cols + r.gridX] = bitmaskAt(cells, r.gridX, r.gridY, cols, rows) + 1
            }
        }
        return mask
    }
}

/**
 * 道路掩码增量装配器（O(全图) 收敛——替代每格修路整图重建）。
 *
 * 持有上次道路编码集与掩码数组；[syncTo] 在道路集变化时只重算**受影响格**
 * （变更格 + 四邻——邻接掩码仅依赖自身与四邻）并写入上次数组的副本，产出
 * 新引用（渲染端以引用变化驱动 chunk 失效/拷贝，原地写入会破坏该契约）。
 * 内容未变（引用变而集合相等，如镜像重发）→ 返回稳定引用。
 *
 * 语义守护：全量构建路径直接复用 [RoadTiling.buildRoadMaskArray]（逐位一致），
 * 增量路径由 RoadMaskTrackerTest 与全量构建做随机变更序列差分对拍。
 *
 * 线程契约：非线程安全（生产仅在 Compose 组装点单线程调用）。
 *
 * @param cols 地图列数 @param rows 地图行数（掩码 index = row*cols+col）
 */
class RoadMaskTracker(private val cols: Int, private val rows: Int) {

    private var lastCells: Set<Long> = emptySet()
    private var lastMask: IntArray? = null

    /**
     * 同步到当前道路集。
     *
     * @return 渲染用掩码数组（1-based，见 [RoadTiling.buildRoadMaskArray]）；
     *         无道路返回 null（渲染端跳过整层）。内容未变时返回上次引用（稳定）。
     */
    fun syncTo(roads: Collection<RoadData>): IntArray? {
        val cells = HashSet<Long>(roads.size * 2)
        for (r in roads) cells.add(RoadTiling.packCell(r.gridX, r.gridY))
        if (cells == lastCells) return lastMask
        val next = when {
            cells.isEmpty() -> null
            // 全量路径（首次/从空恢复）复用权威构建器——语义逐位同源
            lastCells.isEmpty() -> RoadTiling.buildRoadMaskArray(roads, cols, rows)
            else -> incremental(cells)
        }
        lastCells = cells
        lastMask = next
        return next
    }

    /** 增量路径：上次数组副本 + 受影响格（变更格 + 四邻）重算。调用方保证非空态。 */
    private fun incremental(cells: Set<Long>): IntArray {
        val previous = requireNotNull(lastMask) { "增量路径要求上次掩码非空（lastCells 非空）" }
        val mask = previous.copyOf()
        val changed = HashSet<Long>(cells.size)
        for (c in cells) if (c !in lastCells) changed.add(c)
        for (c in lastCells) if (c !in cells) changed.add(c)
        for (c in changed) {
            val x = RoadTiling.unpackX(c)
            val y = RoadTiling.unpackY(c)
            recompute(mask, cells, x, y)
            recompute(mask, cells, x, y - 1)
            recompute(mask, cells, x + 1, y)
            recompute(mask, cells, x, y + 1)
            recompute(mask, cells, x - 1, y)
        }
        return mask
    }

    /** 单格掩码重写（含 1-based 编码；非道路格写 0；越界格跳过——与全量构建一致） */
    private fun recompute(mask: IntArray, cells: Set<Long>, x: Int, y: Int) {
        if (x !in 0 until cols || y !in 0 until rows) return
        val cellMask = RoadTiling.bitmaskAt(cells, x, y, cols, rows)
        mask[y * cols + x] = if (RoadTiling.packCell(x, y) in cells) cellMask + 1 else 0
    }
}
