package com.xianxia.sect.core.render

import com.xianxia.sect.core.nativebridge.GameCoreBridge

/**
 * IslandCliffBridge — 浮空岛崖壁布局合成器 Kotlin 消费通道（地图边缘系统）。
 *
 * 布局合成的**单一权威**在 C++ `gamecore/map/island_cliff.h`：给定地图尺寸、
 * 纹理尺寸表与池表，产出世界坐标绘制操作序列（左环 → 右环 → 下环 → 两角；
 * 锚定/裁剪/镜像约定见 C++ 头文件）。本对象仅做数据装配：池表 → JNI 取操作。
 * 双渲染路径（Vulkan/GLES 与 Canvas）消费同一份布局数据。
 *
 * [PIECE_STRIDE] 下标 = `[texIdx, x, y, w, h, u0, v0, u1, v1, flags]`：
 * texIdx 指向 [TextureIndex] 的纹理表；UV 逐条目携带（独立纹理各自归一化，
 * 且承载镜像与裁剪）；flags bit0 = 水平镜像（此时 u0 > u1，消费端取 min/max）。
 * 守护：`island_cliff_test`（C++ GTest）+ `IslandCliffBridgeTest`。
 *
 * **与旧 IslandEdgeBridge 的差异**：崖壁素材单张最大 1180×3552，超出 4096²
 * 图集容量，故走**独立纹理**而非图集切片——输出因此携带纹理下标与逐条目 UV，
 * 不再有全局 UV 表与图集精灵索引。
 *
 * 线程契约：合成器为无状态纯函数，任意线程可调；生产调用方为 Compose
 * remember（低频，仅地图尺寸/种子变化时一次）。
 *
 * 降级契约：native 库未加载（JVM 测试环境/极端损坏）→ [compose] 返回 null——
 * 双端跳过崖壁层（与 RoadCompositorBridge 同契约；生产 AUTHORITATIVE 恒加载
 * native-game-core）。
 */
object IslandCliffBridge {

    /** native 通道可用性缓存（null=未探测；进程级状态，与库加载状态同生命周期） */
    @Volatile
    private var channelAvailable: Boolean? = null

    /** 布局条目步长 `[texIdx, x, y, w, h, u0, v0, u1, v1, flags]` */
    const val PIECE_STRIDE = 10

    /** 绘制池数量（与 C++ IslandCliffPool 枚举一致：左/右/下/左下角/右下角） */
    const val POOL_COUNT = 5

    /** 条目 flags 位：bit0 = 水平镜像（u0/u1 视作区间两端） */
    const val FLAG_MIRROR_X = 1

    /** 条目字段下标（可读性：消费端按名取字段） */
    object Field {
        const val TEX = 0
        const val X = 1
        const val Y = 2
        const val W = 3
        const val H = 4
        const val U0 = 5
        const val V0 = 6
        const val U1 = 7
        const val V1 = 8
        const val FLAGS = 9
    }

    /**
     * 纹理下标表（序 = 上传次序 = [IslandCliffTextureSet] 的池表取值域）。
     *
     * 三个侧变体在源素材中是镜像对，素材阶段已统一朝向并去重，
     * **右侧崖壁水平镜像复用左侧纹理**（布局的 flags 位表达），故侧变体只有 3 张。
     */
    object TextureIndex {
        const val LEFT_1 = 0
        const val LEFT_2 = 1
        const val LEFT_3 = 2
        const val BOTTOM_1 = 3
        const val BOTTOM_2 = 4
        const val CORNER_BL = 5
        const val CORNER_BR = 6

        /** 纹理总数 */
        const val COUNT = 7
    }

    /** 侧环变体（左右共用同一组，靠镜像位区分） */
    val SIDE_VARIANTS = intArrayOf(
        TextureIndex.LEFT_1, TextureIndex.LEFT_2, TextureIndex.LEFT_3
    )

    /** 下环变体 */
    val BOTTOM_VARIANTS = intArrayOf(TextureIndex.BOTTOM_1, TextureIndex.BOTTOM_2)

    /**
     * 池平铺表（池序：LEFT / RIGHT / BOTTOM / CORNER_BL / CORNER_BR）。
     *
     * LEFT 与 RIGHT 引用**同一组侧变体**（右侧靠镜像复用）；两角各 1 张。
     * 与 C++ 合成器的 poolBase/poolCount/poolFlat 三件套一一对应。
     */
    val POOLS: Array<IntArray> = arrayOf(
        SIDE_VARIANTS,        // LEFT
        SIDE_VARIANTS,        // RIGHT（镜像位区分）
        BOTTOM_VARIANTS,      // BOTTOM
        intArrayOf(TextureIndex.CORNER_BL),
        intArrayOf(TextureIndex.CORNER_BR)
    )

    /** 岛面顶线内缩（世界像素）：实测素材顶部草沿 46~67px 落在 3 格边界树环内，取 0 */
    const val TOP_INSET = 0.0f

    /** 下环起点占左下角纹理宽比例（实测角实体岩体自内缘向内 0.36~0.93 宽） */
    const val BOTTOM_START_RATIO = 0.5f

    /** 下环终点占右下角纹理宽比例 */
    const val BOTTOM_END_RATIO = 0.5f

    /**
     * 探测并加载 native 通道（幂等；加载失败缓存 false，进程内不再重试）。
     */
    @Suppress("TooGenericExceptionCaught")  // 降级契约：库缺失的任何形态（UnsatisfiedLinkError/SecurityException）都只影响崖壁层
    fun ensureAvailable(): Boolean {
        channelAvailable?.let { return it }
        val available = try {
            GameCoreBridge.ensureLoaded()
            GameCoreBridge.isLoaded
        } catch (_: Throwable) {
            // 降级：库缺失（JVM 测试环境/极端损坏）→ 调用方跳过崖壁层，
            // 不影响地图其余层。engine 模块禁 android.util.Log，静默降级。
            false
        }
        channelAvailable = available
        return available
    }

    /**
     * 计算浮空岛崖壁布局（纯函数；结果稳定引用——Camera 变化不调用）。
     *
     * @param cols 地图列数（格）
     * @param rows 地图行数（格）
     * @param tileSize 单格像素（GameConfig.SectMap.TILE_SIZE=48）
     * @param seed 变体种子（= 地图种子；确定性变体序列）
     * @param textureSizes 纹理尺寸表 `[w, h] × TextureIndex.COUNT`（世界像素 =
     *   纹理像素，1:1；由 [IslandCliffTextureSet] 从已烘焙 drawable 实测填充）
     * @param textureMask 纹理可用位掩码（bit i = 纹理 i 已成功上传；
     *   引用不可用纹理的条目由合成器跳过——单张失败降级）
     * @return 扁平 `[texIdx, x, y, w, h, u0, v0, u1, v1, flags] × N`；native 通道
     *   不可用/非法输入返回 null（调用方跳过崖壁层——降级契约，见类 KDoc）
     */
    fun compose(
        cols: Int,
        rows: Int,
        tileSize: Int,
        seed: Int,
        textureSizes: FloatArray,
        textureMask: Int
    ): FloatArray? {
        val unusable = cols <= 0 || rows <= 0 || tileSize <= 0 ||
            textureSizes.size < TextureIndex.COUNT * 2 || !ensureAvailable()
        if (unusable) return null
        val pools = buildPools()
        return try {
            GameCoreBridge.nativeIslandCliffCompose(
                cols = cols, rows = rows, tileSize = tileSize, seed = seed,
                textureSizes = textureSizes,
                poolBase = pools.base, poolCount = pools.count, poolFlat = pools.flat,
                topInset = TOP_INSET, bottomStartRatio = BOTTOM_START_RATIO,
                bottomEndRatio = BOTTOM_END_RATIO, textureMask = textureMask
            )
        } catch (_: Throwable) {
            // native 异常（极端损坏）→ 降级跳过崖壁层（不影响地图其余层）
            null
        }
    }

    /** 池三件套（base/count/flat）——由 [POOLS] 装配，单一数据源 */
    private data class Pools(val base: IntArray, val count: IntArray, val flat: IntArray)

    private fun buildPools(): Pools {
        val flat = IntArray(POOLS.sumOf { it.size })
        val base = IntArray(POOLS.size)
        val count = IntArray(POOLS.size)
        var off = 0
        for ((i, pool) in POOLS.withIndex()) {
            base[i] = off
            count[i] = pool.size
            pool.copyInto(flat, off)
            off += pool.size
        }
        return Pools(base, count, flat)
    }
}
