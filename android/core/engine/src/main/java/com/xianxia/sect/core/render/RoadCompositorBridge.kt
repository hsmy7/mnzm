package com.xianxia.sect.core.render

import com.xianxia.sect.core.nativebridge.GameCoreBridge

/**
 * RoadCompositorBridge — 道路渲染合成器 Kotlin 消费通道（计划 v2 阶段 6：
 * 渲染合成器物理下沉，批次 R 剩余）。
 *
 * 逐格道路合成的**单一权威**在 C++ `gamecore/map/road_compositor.h`
 * （主体→边缘条的操作序列 + 格内局部整型几何；单一主体、横/竖免旋转、
 * 直路按方向出侧边缘、T 中心单侧、转角两开放侧且在格内、十字中心无边缘）。
 * 本对象仅做数据装配：JNI 取操作 + RoadSprite 枚举序 → 图集精灵名映射；
 * Kotlin 渲染路径（SoftwareCanvasBackend chunk 烘焙）不再持有合成逻辑。
 *
 * [SPRITE_KEYS] 下标 = C++ RoadSprite 枚举序 = SpriteAtlasDef.ROAD_RECTS
 * 声明序（NativeBridge roadUVMap 索引同序）——守护：
 * SpriteAtlasDefGeneratedTest + GTest road_compositor_test。
 *
 * 线程契约：合成器为无状态纯函数，任意线程可调；生产调用方为
 * Canvas chunk 烘焙（低频，每失效 chunk 一次逐格查询）。
 *
 * 降级契约：native 库未加载时 [compose] 返回 null——调用方跳过道路层
 * （chunk 烘焙其余层不受影响）。生产环境引擎仅在 AUTHORITATIVE 灰度
 * 开启时加载 native-game-core（默认 OFF），故 [compose] 首次调用自动
 * [ensureLoaded]（幂等）；仅加载失败（JVM 测试环境/极端损坏）走降级，
 * 结果缓存 false，进程内不再重试。
 */
object RoadCompositorBridge {

    /** native 通道可用性缓存（null=未探测；进程级状态，与库加载状态同生命周期） */
    @Volatile
    private var channelAvailable: Boolean? = null

    /**
     * 探测并加载 native 通道（幂等；加载失败缓存 false，进程内不再重试）。
     */
    @Suppress("TooGenericExceptionCaught")  // 降级契约：库缺失的任何形态（UnsatisfiedLinkError/SecurityException）都只影响道路层
    fun ensureAvailable(): Boolean {
        channelAvailable?.let { return it }
        val available = try {
            GameCoreBridge.ensureLoaded()
            GameCoreBridge.isLoaded
        } catch (_: Throwable) {
            // 降级：库缺失（JVM 测试环境/极端损坏）→ 调用方跳过道路层，
            // 不影响 chunk 烘焙其余层。engine 模块禁 android.util.Log，静默降级。
            false
        }
        channelAvailable = available
        return available
    }

    /** 每格操作扁平步长：[sprite, x, y, w, h] */
    const val OP_STRIDE = 5

    /** 单格最大操作数（主体 1 + 最多 2 侧 × 2 条 + 内凹角交汇块 = 6，转角格取最大） */
    const val MAX_OPS_PER_TILE = 6

    /** 道路精灵名（下标 = C++ RoadSprite 枚举序 = ROAD_RECTS 声明序） */
    val SPRITE_KEYS = arrayOf(
        "road_body",        // BODY：道路主体（方石板，恒用、不随方向旋转）
        "road_edge_v",      // EDGE_V：竖直边缘条（1/6 格厚 × 1/2 格长）
        "road_edge_h"       // EDGE_H：水平边缘条（edge_v 预烘焙旋转 90°）
    )

    /**
     * 取单格道路绘制操作（格内局部整型像素几何，十字中心可为负/外溢）。
     *
     * @return 扁平 [sprite, x, y, w, h] × N；native 通道不可用返回 null
     *         （调用方跳过道路层——降级契约，见类 KDoc）
     */
    fun compose(mask: Int, tileSize: Int): IntArray? {
        if (!ensureAvailable()) return null
        return GameCoreBridge.nativeRoadCompose(mask, tileSize)
    }
}
