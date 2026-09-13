package com.xianxia.sect.core.util

import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.nativebridge.GameCoreBridge

/**
 * SectTerrainBridge — 宗门地图地形生成 Kotlin 消费通道。
 *
 * 地形生成的**单一权威**在 C++ `gamecore/map/terrain.h`（Kotlin
 * [SectMapTileGenerator] 的位级等价移植）；本对象做通道收敛：native 优先、
 * 异常回退 Kotlin 生成器（双实现并行契约，与 IslandCliffBridge/RoadCompositorBridge
 * 降级模式同族）。对拍守护：`DiffSectTerrainTest`（桌面 JNI，双端全数组逐位一致）。
 *
 * 返回**行主序展平**瓦片数组（index = row*worldWidthCells+col）——地图数据
 * 模型的唯一表示。
 *
 * 线程契约：无状态纯函数通道，任意线程可调；生产调用方（SectMapController
 * / BootSequenceController）已在 Dispatchers.Default。
 *
 * 降级契约：native 库未加载（JVM 测试环境/极端损坏）→ 走 Kotlin 生成器——
 * 输出与 C++ 位级一致（对拍已证），地图渲染不受影响。仅加载失败走降级，
 * 探测结果缓存 false，进程内不再重试。
 */
object SectTerrainBridge {

    /** native 通道可用性缓存（null=未探测；进程级状态，与库加载状态同生命周期） */
    @Volatile
    private var channelAvailable: Boolean? = null

    /** 探测并加载 native 通道（幂等；加载失败缓存 false，进程内不再重试）。 */
    @Suppress("TooGenericExceptionCaught")  // 降级契约：库缺失的任何形态（UnsatisfiedLinkError/SecurityException）都只影响本通道
    fun ensureAvailable(): Boolean {
        channelAvailable?.let { return it }
        val available = try {
            GameCoreBridge.ensureLoaded()
            GameCoreBridge.isLoaded
        } catch (_: Throwable) {
            // 降级：库缺失（JVM 测试环境/极端损坏）→ Kotlin 生成器接管
            false
        }
        channelAvailable = available
        return available
    }

    /**
     * 生成宗门地图瓦片（展平行主序；确定性纯函数）。
     *
     * 门楼常量经 [GameConfig.SectMap] 传值给 C++（单一数据源不落 C++）。
     *
     * @param decorationDensity 总装饰密度 (0.0~1.0)，与 Kotlin 生成器同默认 0.18
     * @param worldSeed 世界随机种子（不同种子不同地图分布）
     * @param borderTreeRing 边界树环厚度（0 = 无）
     * @return 展平瓦片数组（TILE_* 索引，size = w*h）
     */
    fun generateFlatTileData(
        worldWidthCells: Int,
        worldHeightCells: Int,
        decorationDensity: Float = 0.18f,
        worldSeed: Int = 0,
        borderTreeRing: Int = 0
    ): IntArray {
        val cfg = GameConfig.SectMap
        if (ensureAvailable()) {
            val native = GameCoreBridge.nativeGenerateSectTerrain(
                seed = worldSeed,
                width = worldWidthCells,
                height = worldHeightCells,
                density = decorationDensity,
                borderTreeRing = borderTreeRing,
                gateX = cfg.GATE_X,
                gateY = cfg.GATE_Y,
                gateWidth = cfg.GATE_WIDTH,
                gateHeight = cfg.GATE_HEIGHT,
                gateSpriteY = cfg.GATE_SPRITE_Y
            )
            if (native != null && native.size == worldWidthCells * worldHeightCells) {
                return native
            }
        }
        // 降级：Kotlin 生成器（JVM 测试/极端损坏；输出与 C++ 位级一致）
        return SectMapTileGenerator.generateTileData(
            worldWidthCells, worldHeightCells, decorationDensity, worldSeed, borderTreeRing
        ).flatMap { it.toList() }.toIntArray()
    }
}
