package com.xianxia.sect.ui.game.sect

import android.content.Context
import android.graphics.Bitmap
import androidx.core.graphics.createBitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import com.xianxia.sect.core.engine.domain.building.BuildingFeatureRegistry
import com.xianxia.sect.core.render.RenderMetrics
import com.xianxia.sect.core.render.SpriteAtlasDef
import com.xianxia.sect.feature.game.R
import kotlin.math.max
import kotlin.math.min

/**
 * 宗门地图图集运行时组装器——
 * 单一职责：把全部地图精灵按 [SpriteAtlasDef] 生成布局解码绘制到 2048×2048
 * 位图，供 Vulkan 上传（ASTC/RGBA）与 Canvas 软渲染共享）。
 *
 * 图集布局的**数值权威**在 build-atlas.mjs LAYOUT（SpriteAtlasDef 生成物），
 * 本文件只消费布局不定义布局——新增精灵只需加资源文件 + LAYOUT，无需改此处。
 */
object SectAtlasAssembler {

    /** 图集拼装日志标签 */
    private const val TAG = "SectAtlasAssembler"

    /**
     * 图集位图封顶边长（RGBA 上传路径 **与** Canvas 软渲染路径统一封顶）。
     *
     * 4096² 图集按原尺寸建 ARGB_8888 位图 = 64MB；软渲染路径再加上传路径的
     * 像素缓冲，低端机必然 OOM。封顶 2048（16MB）后对图集缩放采样——UV 是
     * 归一化的，视觉无差异，仅构建分辨率降一档。
     *
     * ASTC 压缩路径不受此限制（走 4096 KTX，约 16MB 压缩纹理，不经位图）。
     */
    private const val ATLAS_BITMAP_MAX_EDGE = 2048

    /**
     * 图集位图缩放系数（[SpriteAtlasDef.ATLAS_W/H] → 封顶边长）。
     *
     * 图集未超封顶时恒为 1.0（不缩放），超出时按长边等比降到封顶值。
     */
    private val atlasBitmapScale: Float =
        min(1.0f, ATLAS_BITMAP_MAX_EDGE / max(SpriteAtlasDef.ATLAS_W, SpriteAtlasDef.ATLAS_H).toFloat())

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

    /** 固定结构（宗门入口门楼）drawable 映射。 */
    private val STRUCTURE_DRAWABLE_MAP = mapOf(
        "sect_gate" to R.drawable.sect_gate,
    )

    /** 灵田作物三阶段 drawable（按 CropStage ordinal）。 */
    private val CROP_DRAWABLE = listOf(
        R.drawable.growing_spiritgrass7,
        R.drawable.growing_spiritgrass8,
        R.drawable.growing_spiritgrass9,
    )

    /** 云层精灵 drawable（按 SpriteAtlasDef.CLOUD_RECTS 声明顺序）。 */
    private val CLOUD_DRAWABLE_LIST = listOf(
        R.drawable.cloud_1,
        R.drawable.cloud_2,
        R.drawable.cloud_3,
        R.drawable.cloud_4,
        R.drawable.cloud_5,
    )

    /** 石板道路精灵 drawable（按 SpriteAtlasDef.ROAD_RECTS 键；names 与 ROAD_DRAWABLE 产物一致）。 */
    private val ROAD_DRAWABLE_MAP = mapOf(
        "road_body" to R.drawable.road_body,
        "road_edge_v" to R.drawable.road_edge_v,
        "road_edge_h" to R.drawable.road_edge_h,
    )


    /**
     * 构建地图图集位图（长边封顶 [ATLAS_BITMAP_MAX_EDGE]，当前 2048 ARGB_8888）。
     *
     * 瓦片/建筑/地砖/作物四类精灵按 SpriteAtlasDef 像素位置绘制（按 [atlasBitmapScale] 缩放）。
     *
     * 调用线程：**必须在后台线程**。逐精灵解码 + Canvas 绘制在 2048²
     * 图集上耗时可达数百毫秒，在主线程执行属 ANR 高危路径。
     *
     * 子精灵 Bitmap **不调 recycle()**——避免国产 ROM NativeAllocationRegistry
     * CleanerThunk double-free SIGABRT（#11008）；子精灵很小（<1KB～4KB），
     * 自然 GC 消耗可忽略。
     *
     * @param context 资源上下文
     * @return 图集位图（边长 ≤ [ATLAS_BITMAP_MAX_EDGE]）
     */
    fun buildAtlasBitmap(context: Context): Bitmap {
        val w = (SpriteAtlasDef.ATLAS_W * atlasBitmapScale).toInt().coerceAtLeast(1)
        val h = (SpriteAtlasDef.ATLAS_H * atlasBitmapScale).toInt().coerceAtLeast(1)
        val atlas = createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(atlas)
        // 双线性过滤——
        //   drawBitmap 降采样走 bilinear，配合 [downscaleWithBilinearChain] 抑制
        //   点采样摩尔纹；与 ASTC 图集（lanczos3 拼装 + 三线性 mip）观感对齐。
        val paint = Paint().apply { isFilterBitmap = true }

        val slots = buildSpriteSlots()
        val loadedCount = drawSlotsToAtlas(context, canvas, paint, slots)

        android.util.Log.i(
            TAG,
            "buildAtlas: $loadedCount/${slots.size} sprites loaded @ ${w}x$h (scale=$atlasBitmapScale)"
        )
        return atlas
    }

    /**
     * 构建全部图集精灵槽位（瓦片/建筑/地砖/作物/固定结构——布局数值全部来自
     * SpriteAtlasDef 生成物，本函数只消费布局不定义布局）。
     */
    private fun buildSpriteSlots(): List<SpriteSlot> {
        val buildingMap = buildingAtlasDrawableMap()
        return buildTileSlots() + buildBuildingSlots(buildingMap) +
            buildCropSlots + buildStructureSlots + buildCloudSlots +
            buildRoadSlots
    }

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

    /** 瓦片/装饰精灵槽位（地面 + 草/石/树全部装饰变体，按 TileType 声明序）。 */
    private fun buildTileSlots(): List<SpriteSlot> =
        SpriteAtlasDef.TileType.values().map { tile ->
            val sr = tile.rect
            SpriteSlot(tileDrawableName(tile), sr.x, sr.y, sr.w, sr.h, tileDrawableRes(tile))
        }

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

    /** 建筑精灵槽位。 */
    private fun buildBuildingSlots(buildingMap: Map<String, Int>): List<SpriteSlot> =
        SpriteAtlasDef.BUILDING_NAMES.indices.map { idx ->
            val name = SpriteAtlasDef.BUILDING_NAMES[idx]
            val sr = SpriteAtlasDef.buildingRect(idx)
            SpriteSlot(name, sr.x, sr.y, sr.w, sr.h, buildingMap[name] ?: 0)
        }

    /** 灵田作物精灵槽位（生长动画三阶段）。 */
    private val buildCropSlots: List<SpriteSlot> =
        SpriteAtlasDef.CropStage.values().map { stage ->
            val r = stage.rect
            SpriteSlot(
                stage.name, r.x, r.y, r.w, r.h,
                CROP_DRAWABLE.getOrNull(stage.ordinal) ?: 0
            )
        }

    /** 固定结构精灵槽位（宗门入口门楼/阶梯，渲染走建筑层）。 */
    private val buildStructureSlots: List<SpriteSlot> =
        SpriteAtlasDef.STRUCTURES.map { s ->
            SpriteSlot(
                s.name, s.rect.x, s.rect.y, s.rect.w, s.rect.h,
                STRUCTURE_DRAWABLE_MAP[s.key] ?: 0
            )
        }

    /** 云层精灵槽位（世界顶部动态云朵——图集槽位，位置/运动由 CloudLayerAnimator 驱动）。 */
    private val buildCloudSlots: List<SpriteSlot> =
        SpriteAtlasDef.CLOUD_RECTS.mapIndexed { index, (name, rect) ->
            SpriteSlot(
                name, rect.x, rect.y, rect.w, rect.h,
                CLOUD_DRAWABLE_LIST.getOrNull(index) ?: 0
            )
        }

    /** 石板道路精灵槽位（按 SpriteAtlasDef.ROAD_RECTS 声明顺序，渲染叠加层取 UV/源矩形）。 */
    private val buildRoadSlots: List<SpriteSlot> =
        SpriteAtlasDef.ROAD_RECTS.map { (name, rect) ->
            SpriteSlot(
                name, rect.x, rect.y, rect.w, rect.h,
                ROAD_DRAWABLE_MAP[name] ?: 0
            )
        }

    /**
     * 逐个解码绘制精灵到位图图集。
     *
     * @return 成功绘制的精灵数
     */
    // 子精灵解码失败模式无稳定异常契约（资源损坏/ROM 差异可抛任意运行时异常），
    // 全捕获 + 计数 + 日志是非关键路径语义（原 NativeSurfaceView 同款）
    @Suppress("TooGenericExceptionCaught")
    private fun drawSlotsToAtlas(
        context: Context,
        canvas: Canvas,
        paint: Paint,
        slots: List<SpriteSlot>
    ): Int {
        var loadedCount = 0
        for (slot in slots) {
            if (slot.resId == 0) continue
            try {
                val bmp = BitmapFactory.decodeResource(context.resources, slot.resId)
                if (bmp != null) {
                    // 按 atlasBitmapScale 缩放绘制矩形（图集位图长边封顶 2048，
                    // 防止 4096 图集建 64MB 位图 OOM）
                    val sx = slot.x * atlasBitmapScale
                    val sy = slot.y * atlasBitmapScale
                    val dstRight = (sx + slot.w * atlasBitmapScale).toInt()
                    val dstBottom = (sy + slot.h * atlasBitmapScale).toInt()
                    val dstW = (dstRight - sx.toInt()).coerceAtLeast(1)
                    val dstH = (dstBottom - sy.toInt()).coerceAtLeast(1)
                    // 源显著大于目标槽位（>2:1×）时先做
                    //   双线性链式预降采样（近似软件 mip 链），再以双线性绘制入槽——
                    //   道路主体 1254→64（≈20:1×）等深度降采样若直接 drawBitmap 点采样
                    //   会产出密集摩尔纹（放大后呈"模糊线框 ↔ 噪点细节"跳变）。
                    val src = resolveSlotBitmap(bmp, dstW, dstH)
                    canvas.drawBitmap(src, null,
                        Rect(
                            sx.toInt(), sy.toInt(),
                            dstRight, dstBottom
                        ),
                        paint)
                    // 不调 recycle()：避免国产 ROM double-free
                    loadedCount++
                } else {
                    android.util.Log.w(TAG, "buildAtlas: null bitmap for '${slot.name}'")
                    RenderMetrics.atlasLoadSpriteFailed.incrementAndGet()
                }
            } catch (e: Exception) {
                android.util.Log.w(TAG, "buildAtlas: error loading '${slot.name}': ${e.message}")
                RenderMetrics.atlasLoadSpriteFailed.incrementAndGet()
            }
        }
        return loadedCount
    }

    /**
     * 槽点位图解析：源显著大于目标槽位（>2:1×）时链式预降采样，否则原位图。
     * （提取为单表达式——drawSlotsToAtlas 嵌套深度已触 detekt 阈值）
     */
    private fun resolveSlotBitmap(bmp: Bitmap, dstW: Int, dstH: Int): Bitmap =
        if (bmp.width > dstW * 2 || bmp.height > dstH * 2) {
            downscaleWithBilinearChain(bmp, dstW, dstH)
        } else {
            bmp
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

    /** 图集精灵槽位（名称/像素位置/资源 ID） */
    private data class SpriteSlot(
        val name: String,
        val x: Int, val y: Int, val w: Int, val h: Int,
        val resId: Int
    )
}
