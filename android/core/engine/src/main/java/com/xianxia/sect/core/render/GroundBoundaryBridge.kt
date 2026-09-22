package com.xianxia.sect.core.render

import com.xianxia.sect.core.nativebridge.GameCoreBridge

/**
 * GroundBoundaryBridge — 弯曲地皮轮廓合成器 Kotlin 消费通道（地图边缘系统 v2）。
 *
 * 合成单一权威 = C++ `gamecore/map/island_cliff.h` 的替代者
 * `gamecore/map/ground_boundary.h`：固定控制点 → 闭合 Catmull-Rom 采样折线
 * → 地皮三角 mesh + 底部挤出 mesh + 逐格掩码。本对象仅做通道装配：
 * native 优先，[GroundBoundaryGenerator]（位级移植）降级。
 *
 * 复合输出布局（头部 [Header.FLOATS] float，段偏移见 [Header.Field]）：
 * 头部 → 折线 polyCount×2 → 掩码 cols×rows（0..3 位组）→ 地皮 mesh（顶点×8
 * float，SpriteVertex 布局 px,py,u,v,r,g,b,a）→ 底部 mesh（同布局）。
 * 守护：`ground_boundary_test`（C++ GTest）+ `DiffGroundBoundaryTest`（双端对拍）。
 *
 * 线程契约：纯函数（kAnyThread）；生产调用方 = Compose remember（低频，
 * 仅地图尺寸变化时一次；第一阶段轮廓固定，与种子无关）。
 *
 * 降级契约：native 不可用 → [GroundBoundaryGenerator] 顶上（地皮层不能像
 * 崖壁那样整体跳过——轮廓是地皮本身的边界，生产恒有输出）。
 */
object GroundBoundaryBridge {

    /** 底部岩石带深度（世界像素；用户拍板「中等」档，第一阶段定值） */
    const val BOTTOM_DEPTH_PX = 768.0f

    /** 复合头部常量与字段下标（跨语言单一口径，见 C++ 头文件） */
    object Header {
        const val VERSION = 1
        const val FLOATS = 11

        object Field {
            const val VERSION = 0
            const val POLY_COUNT = 1
            const val COLS = 2
            const val ROWS = 3
            const val TILE_SIZE = 4
            const val BOTTOM_DEPTH = 5
            const val MASK_OFFSET = 6
            const val GROUND_MESH_OFFSET = 7
            const val GROUND_MESH_COUNT = 8
            const val BOTTOM_MESH_OFFSET = 9
            const val BOTTOM_MESH_COUNT = 10
        }
    }

    /** 掩码位（与 [GroundBoundaryGenerator].MASK_BIT_* 同值） */
    const val MASK_BIT_QUAD = 1
    const val MASK_BIT_TREE = 2

    /** native 通道可用性缓存（null=未探测；进程级状态） */
    @Volatile
    private var channelAvailable: Boolean? = null

    /**
     * 探测并加载 native 通道（幂等；加载失败缓存 false，进程内不再重试）。
     * 失败不致功能缺失：compose 走 Kotlin 降级。
     */
    @Suppress("TooGenericExceptionCaught") // 降级契约：库缺失的任何形态都只影响合成通道
    fun ensureAvailable(): Boolean {
        channelAvailable?.let { return it }
        val available = try {
            GameCoreBridge.ensureLoaded()
            GameCoreBridge.isLoaded
        } catch (_: Throwable) {
            false
        }
        channelAvailable = available
        return available
    }

    /**
     * 计算弯曲地皮轮廓复合数据（纯函数；结果稳定引用——Camera 变化不调用）。
     *
     * @param cols 地图列数（格）
     * @param rows 地图行数（格）
     * @param tileSize 单格像素（GameConfig.SectMap.TILE_SIZE=48）
     * @param bottomDepth 底部岩石带深度（世界像素；[BOTTOM_DEPTH_PX]）
     * @return 复合 FloatArray（布局见 [Header]）；非法输入返回空数组
     */
    fun compose(
        cols: Int,
        rows: Int,
        tileSize: Int,
        bottomDepth: Float = BOTTOM_DEPTH_PX
    ): FloatArray {
        if (cols <= 0 || rows <= 0 || tileSize <= 0) return FloatArray(0)
        if (ensureAvailable()) {
            try {
                return GameCoreBridge.nativeComposeGroundBoundary(
                    cols = cols, rows = rows, tileSize = tileSize,
                    bottomDepth = bottomDepth
                )
            } catch (_: Throwable) {
                // native 异常（极端损坏）→ Kotlin 镜像顶上
            }
        }
        return GroundBoundaryGenerator.generate(cols, rows, tileSize, bottomDepth)
    }
}
