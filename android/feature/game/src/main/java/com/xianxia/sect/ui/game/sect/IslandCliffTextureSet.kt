package com.xianxia.sect.ui.game.sect

import com.xianxia.sect.core.render.IslandCliffBridge
import com.xianxia.sect.feature.game.R

/**
 * IslandCliffTextureSet — 浮空岛崖壁独立纹理集（地图边缘系统）。
 *
 * 崖壁素材单张最大 1180×3552，**超出 4096² 图集容量**，故不走图集：
 * 每个变体一张独立纹理，纹理下标序 = [IslandCliffBridge.TextureIndex]。
 *
 * ## 尺寸表为何是编译期常量
 * 布局合成（[IslandCliffBridge.compose]）**需要纹理尺寸表**才能算出铺装块数与
 * 裁剪比例，且必须在纹理上传完成前就能算——若改为异步读取图像头再合成，布局会
 * 依赖加载时序（首帧无崖壁 → 尺寸到达后重建整层），徒增竞态与重建路径。
 * 故尺寸表为编译期常量，由 `EdgeKtxSyncTest` 解析真实 WebP 头
 * **逐个字节校验**（守卫：改素材忘记改常量即测试变红）。
 *
 * 尺寸口径：**烘焙后尺寸**（`bake{preserve, roundUp4}` 产物 —— 宽高向上取整到
 * 4 的倍数，ASTC 4×4 的硬性要求），世界像素与纹理像素 1:1。
 *
 * ## 镜像复用
 * 三个侧变体在源素材中是镜像对，素材阶段已统一朝向（岩左草右）并去重；
 * **右侧崖壁水平镜像复用左侧纹理**（布局的 flags 位表达），故侧变体只有 3 张：
 * 纹理总数 7 = 侧 3 + 下 2 + 角 2。
 */
internal object IslandCliffTextureSet {

    /** 纹理总数（= [IslandCliffBridge.TextureIndex.COUNT]） */
    const val TEXTURE_COUNT = IslandCliffBridge.TextureIndex.COUNT

    /**
     * 纹理尺寸表 `[w, h] × TEXTURE_COUNT`（世界像素 = 纹理像素，1:1）。
     *
     * 序 = 纹理下标序（[IslandCliffBridge.TextureIndex]）：
     * LEFT_1 / LEFT_2 / LEFT_3 / BOTTOM_1 / BOTTOM_2 / CORNER_BL / CORNER_BR。
     * 各值 = 烘焙产物实测尺寸（源尺寸向上取整到 4 的倍数）。
     */
    val TEXTURE_SIZES: FloatArray = floatArrayOf(
        1176f, 3552f,  // LEFT_1     源 1176×3552（已合规）
        1120f, 3368f,  // LEFT_2     源 1119×3368 → 1119 取整为 1120
        1180f, 3552f,  // LEFT_3     源 1178×3552 → 1178 取整为 1180
        2560f, 1696f,  // BOTTOM_1   源 2560×1696（已合规）
        2304f, 1888f,  // BOTTOM_2   源 2304×1888（已合规）
        1832f, 2400f,  // CORNER_BL  源 1832×2399 → 2399 取整为 2400
        1828f, 2396f   // CORNER_BR  源 1828×2396（已合规）
    )

    /**
     * 纹理 drawable 资源 ID（序 = 纹理下标序）。
     *
     * 与图集无关——这些 drawable **不入图集**（`build-atlas.mjs` 只登记图集内精灵），
     * 由 [IslandCliffTextureLoader] 单独解码上传。
     * R 引用须为编译期字面量（Android 资源引用约束——显式清单，
     * 与 `SectAtlasAssembler` 的精灵映射同纪律）。
     */
    val TEXTURE_DRAWABLES: IntArray = intArrayOf(
        R.drawable.map_edge_left_1,
        R.drawable.map_edge_left_2,
        R.drawable.map_edge_left_3,
        R.drawable.map_edge_bottom_1,
        R.drawable.map_edge_bottom_2,
        R.drawable.map_edge_corner_bl,
        R.drawable.map_edge_corner_br
    )

    /**
     * KTX 资产相对路径表（序 = 纹理下标序；`app/src/main/assets/atlas/edge/`）。
     *
     * 由 `scripts/build-edge-ktx.mjs` 从同尺寸 WebP 生成（ASTC 4×4 + mip 链），
     * 仅 Vulkan 且设备支持 ASTC 时使用（三级降级的第一级）。
     */
    val TEXTURE_KTX_ASSETS: Array<String> = arrayOf(
        "atlas/edge/map_edge_left_1.ktx",
        "atlas/edge/map_edge_left_2.ktx",
        "atlas/edge/map_edge_left_3.ktx",
        "atlas/edge/map_edge_bottom_1.ktx",
        "atlas/edge/map_edge_bottom_2.ktx",
        "atlas/edge/map_edge_corner_bl.ktx",
        "atlas/edge/map_edge_corner_br.ktx"
    )

    /** 纹理宽（世界像素） */
    fun widthOf(index: Int): Float = TEXTURE_SIZES[index * 2]

    /** 纹理高（世界像素） */
    fun heightOf(index: Int): Float = TEXTURE_SIZES[index * 2 + 1]

    /** 全纹理可用掩码（[IslandCliffBridge.compose] 的 textureMask 实参） */
    fun maskFor(available: IntArray): Int {
        var mask = 0
        for (i in available.indices) {
            if (available[i] != 0) mask = mask or (1 shl i)
        }
        return mask
    }
}
