package com.xianxia.sect.core.render

import com.xianxia.sect.core.nativebridge.GameCoreBridge

/**
 * RoadCompositorBridge — 道路渲染合成器 Kotlin 消费通道（计划 v2 阶段 6：
 * 渲染合成器物理下沉，批次 R 剩余）。
 *
 * 逐格道路合成的**单一权威**在 C++ `gamecore/map/road_compositor.h`
 * （主体→描边条→转角件→十字中心的操作序列 + 格内局部整型几何）。
 * 本对象仅做数据装配：JNI 取操作 + RoadSprite 枚举序 → 图集精灵名映射；
 * Kotlin 渲染路径（SoftwareCanvasBackend chunk 烘焙）不再持有合成逻辑。
 *
 * [SPRITE_KEYS] 下标 = C++ RoadSprite 枚举序 = SpriteAtlasDef.ROAD_RECTS
 * 声明序（NativeBridge roadUVMap 索引同序）——守护：
 * SpriteAtlasDefGeneratedTest + GTest road_compositor_test。
 *
 * 产品契约（2026-08-28）：道路**永远显示，无降级开关**——生产环境
 * native-game-core 随 APK 必然可加载（引擎 AUTHORITATIVE 灰度默认 OFF
 * 不加载，本通道自行 ensureLoaded，幂等），加载失败属安装损坏，
 * 直接抛出快速失败，不做静默跳过。
 *
 * 线程契约：合成器为无状态纯函数，任意线程可调；生产调用方为
 * Canvas chunk 烘焙（低频，每失效 chunk 一次逐格查询）。
 */
object RoadCompositorBridge {

    /** 每格操作扁平步长：[sprite, x, y, w, h] */
    const val OP_STRIDE = 5

    /** 单格最大操作数（主体 1 + 描边条 4 + 转角件 4 + 十字中心 1） */
    const val MAX_OPS_PER_TILE = 10

    /** 道路精灵名（下标 = C++ RoadSprite 枚举序 = ROAD_RECTS 声明序） */
    val SPRITE_KEYS = arrayOf(
        "road_base",        // BASE：横向直路主体
        "road_base_v",      // BASE_V：纵向直路主体
        "road_junction",    // JUNCTION：转角/T/十字拼接主体
        "road_edge_h",      // EDGE_H：水平描边条
        "road_edge_v",      // EDGE_V：垂直描边条
        "road_corner_tr",   // CORNER_TR：右上外缘转角件
        "road_corner_tl",   // CORNER_TL：左上外缘转角件
        "road_corner_br",   // CORNER_BR：右下外缘转角件
        "road_corner_bl",   // CORNER_BL：左下外缘转角件
        "road_cross_center" // CROSS_CENTER：十字中心装饰
    )

    /**
     * 取单格道路绘制操作（格内局部整型像素几何，十字中心可为负/外溢）。
     *
     * @return 扁平 [sprite, x, y, w, h] × N——道路永远显示，无空返回
     */
    fun compose(mask: Int, tileSize: Int): IntArray {
        GameCoreBridge.ensureLoaded()  // 幂等：AUTHORITATIVE 关闭时由本通道加载
        return GameCoreBridge.nativeRoadCompose(mask, tileSize)
    }
}
