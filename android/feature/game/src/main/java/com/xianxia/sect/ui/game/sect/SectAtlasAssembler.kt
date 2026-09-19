package com.xianxia.sect.ui.game.sect

import android.graphics.Bitmap
import com.xianxia.sect.core.engine.domain.building.BuildingFeatureRegistry
import com.xianxia.sect.core.render.SpriteAtlasDef
import com.xianxia.sect.feature.game.R
import kotlin.math.max

/**
 * 图集运行时辅助（B15 / R6.1 后的**残余职责**）。
 *
 * ## 历史与退役
 *
 * 本对象原为「宗门地图图集运行时组装器」——在设备上逐精灵解码 + Canvas 画布
 * 拼装 2048² 位图，供 Vulkan 上传（ASTC/RGBA）与 Canvas 软渲染共享。
 * **B15 / R6.1 起该职责退役**：图集像素改由 `scripts/atlas-offline-rgba.mjs`
 * 在**构建期**产出（`assets/atlas/atlas-rgba-raw.bin` / `atlas-rgba-mips.bin`），
 * 运行时只做一次性映射/解码 + upload（见 [AtlasAsyncPipeline.prepareOfflineRgbaAtlas]）。
 * 退役动因：消除启动期 Canvas 依赖、数百毫秒拼装耗时，以及 2048² 位图
 * （16MB）+ 逐精灵解码中间缓冲的内存尖峰（低端机 OOM 高危，亦为 iOS 前置阻塞项）。
 *
 * ## 保留职责（不得删除）
 *
 * 1. [downscaleWithBilinearChain] —— Canvas 软渲染**深缩放兜底**的实际绘制依赖
 *    （`SoftwareCanvasBackend.drawPreScaled` 在源 > 目标 2× 时调用）；本对象是
 *    该函数的**单一实现**，删除会让软渲路径失去软件 mip 近似。
 * 2. [buildingAtlasDrawableMap] / [tileDrawableRes] —— 「图集精灵名 → R.drawable」
 *    映射的守卫入口（`SectAtlasBuildingDrawableGuardTest` / `SectAtlasTileDrawableGuardTest`
 *    消费），锁住 LAYOUT 与资源表不漂移。
 *
 * 图集布局的**数值权威**在 build-atlas.mjs LAYOUT（SpriteAtlasDef 生成物），
 * 本文件只消费布局不定义布局——新增精灵只需加资源文件 + LAYOUT，无需改此处。
 * 离线产物的槽位权威与源像素解析见 `scripts/lib/atlas-offline-rgba-lib.mjs`。
 */
object SectAtlasAssembler {

    /** 瓦片/装饰精灵 R.drawable 预建映射（替代 getIdentifier 运行时查找）。 */
    private val TILE_DRAWABLE_MAP = mapOf(
        "map_grass_1" to R.drawable.map_grass_1,
        "decoration_grass1" to R.drawable.decoration_grass1,
        "decoration_grass2" to R.drawable.decoration_grass2,
        "decoration_grass3" to R.drawable.decoration_grass3,
        "decoration_grass4" to R.drawable.decoration_grass4,
        "decoration_stone1" to R.drawable.decoration_stone1,
        "decoration_stone2" to R.drawable.decoration_stone2,
        "decoration_stone3" to R.drawable.decoration_stone3,
        "decoration_tree1" to R.drawable.decoration_tree1,
        "decoration_tree2" to R.drawable.decoration_tree2,
    )


    /**
     * 建筑图集名 → drawableRes 映射。
     *
     * 键必须是 [SpriteAtlasDef.BUILDING_NAMES] 中的**图集精灵名**（经
     * [BuildingFeature.effectiveSpriteName] 解析）而非显示名——显示名可带分级前缀
     * （如「初级多人住所」）而图集精灵名保持历史名称（「多人住所」），按显示名建
     * 映射会导致住所类建筑槽位查空、精灵图不显示。
     */
    internal fun buildingAtlasDrawableMap(): Map<String, Int> =
        BuildingFeatureRegistry.all.associate { it.effectiveSpriteName() to it.drawableRes }

    /**
     * 瓦片 → drawable 资源 id（0 = 该瓦片无精灵，如建筑占位）。
     *
     * 图集拼装与守卫测试的唯一入口：新增瓦片类型漏配 drawable 时，槽位静默为空
     * （画面上装饰不显示且无报错），由守卫测试直接拦截。
     */
    internal fun tileDrawableRes(tile: SpriteAtlasDef.TileType): Int {
        val name = tileDrawableName(tile)
        return if (name.isEmpty()) 0 else (TILE_DRAWABLE_MAP[name] ?: 0)
    }

    /** 瓦片 → drawable 名（R.drawable 预建映射，避免运行时 getIdentifier）。 */
    private fun tileDrawableName(tile: SpriteAtlasDef.TileType): String = when (tile) {
        SpriteAtlasDef.TileType.GROUND -> "map_grass_1"
        SpriteAtlasDef.TileType.GRASS1 -> "decoration_grass1"
        SpriteAtlasDef.TileType.GRASS2 -> "decoration_grass2"
        SpriteAtlasDef.TileType.GRASS3 -> "decoration_grass3"
        SpriteAtlasDef.TileType.GRASS4 -> "decoration_grass4"
        SpriteAtlasDef.TileType.STONE1 -> "decoration_stone1"
        SpriteAtlasDef.TileType.STONE2 -> "decoration_stone2"
        SpriteAtlasDef.TileType.STONE3 -> "decoration_stone3"
        SpriteAtlasDef.TileType.TREE1 -> "decoration_tree1"
        SpriteAtlasDef.TileType.TREE2 -> "decoration_tree2"
        SpriteAtlasDef.TileType.TILE_BUILDING -> ""
    }

    /**
     * 双线性链式降采样（软件 mip 近似）：每次把最长边减半（下限为目标尺寸），
     * 直到源尺寸 ≤ 目标 2 倍，再交由调用方双线性绘制完成最后一步。
     *
     * 纯双线性一步降采样（如 20:1）对高频图案仍会产生摩尔纹；逐级 2:1 双线性
     * 与 GPU mip 链生成的面积平均语义一致，深度降采样后仍保持图案结构
     * （道路主体 1254→64 实测：链式 → 石板缝清晰稳定；点采样 → 噪声）。
     *
     * @param bmp 源位图（不修改、不回收——调用方负责生命周期）
     * @param dstW 目标槽位宽度（图集缩放后像素）
     * @param dstH 目标槽位高度
     * @return 预降采样后的位图（尺寸 ∈ [dst, 2×dst)，可能即源本身——仅实际降采样时新建）
     */
    internal fun downscaleWithBilinearChain(bmp: Bitmap, dstW: Int, dstH: Int): Bitmap {
        var scaled = bmp
        while (scaled.width > dstW * 2 || scaled.height > dstH * 2) {
            val w = max(dstW, scaled.width / 2)
            val h = max(dstH, scaled.height / 2)
            scaled = Bitmap.createScaledBitmap(scaled, w, h, true)
        }
        return scaled
    }
}
