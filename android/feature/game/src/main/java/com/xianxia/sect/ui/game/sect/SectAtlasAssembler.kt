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

/**
 * 宗门地图图集运行时组装器（2026-08-13 自 NativeSurfaceView companion 外移——
 * 单一职责：把全部地图精灵按 [SpriteAtlasDef] 生成布局解码绘制到 2048×2048
 * 位图，供 Vulkan 上传（ASTC/RGBA）与 Canvas 软渲染共享）。
 *
 * 图集布局的**数值权威**在 build-atlas.mjs LAYOUT（SpriteAtlasDef 生成物），
 * 本文件只消费布局不定义布局——新增精灵只需加资源文件 + LAYOUT，无需改此处。
 */
object SectAtlasAssembler {

    /** 图集拼装日志标签 */
    private const val TAG = "SectAtlasAssembler"

    /** 瓦片/装饰精灵 R.drawable 预建映射（替代 getIdentifier 运行时查找）。 */
    private val TILE_DRAWABLE_MAP = mapOf(
        "map_grass_1" to R.drawable.map_grass_1,
        "decoration_grass_small" to R.drawable.decoration_grass_small,
        "decoration_grass_medium" to R.drawable.decoration_grass_medium,
        "decoration_grass_large" to R.drawable.decoration_grass_large,
        "decoration_tree1" to R.drawable.decoration_tree1,
        "decoration_tree2" to R.drawable.decoration_tree2,
    )

    /** 固定结构（宗门入口门楼）drawable 映射。 */
    private val STRUCTURE_DRAWABLE_MAP = mapOf(
        "sect_gate" to R.drawable.sect_gate,
    )

    /** 地砖 drawable 映射。 */
    private val FLOOR_TILE_DRAWABLE_MAP = mapOf(
        "floor_tile_2x2" to R.drawable.floor_tile_2x2,
        "floor_tile_2x3" to R.drawable.floor_tile_2x3,
        "floor_tile_3x2" to R.drawable.floor_tile_3x2,
        "floor_tile_3x3" to R.drawable.floor_tile_3x3,
        "spirit_mine_ground" to R.drawable.spirit_mine_ground,
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
        "road_base" to R.drawable.road_base,
        "road_base_v" to R.drawable.road_base_v,
        "road_junction" to R.drawable.road_junction,
        "road_edge_h" to R.drawable.road_edge_h,
        "road_edge_v" to R.drawable.road_edge_v,
        "road_corner_tr" to R.drawable.road_corner_tr,
        "road_corner_tl" to R.drawable.road_corner_tl,
        "road_corner_br" to R.drawable.road_corner_br,
        "road_corner_bl" to R.drawable.road_corner_bl,
        "road_cross_center" to R.drawable.road_cross_center,
    )

    /**
     * 构建地图图集位图（2048×2048 ARGB_8888）。
     *
     * 瓦片/建筑/地砖/作物四类精灵按 SpriteAtlasDef 像素位置绘制。
     * 子精灵 Bitmap **不调 recycle()**——避免国产 ROM NativeAllocationRegistry
     * CleanerThunk double-free SIGABRT（#11008）；子精灵很小（<1KB～4KB），
     * 自然 GC 消耗可忽略。
     *
     * @param context 资源上下文
     * @return 图集位图
     */
    fun buildAtlasBitmap(context: Context): Bitmap {
        val atlas = createBitmap(
            SpriteAtlasDef.ATLAS_W, SpriteAtlasDef.ATLAS_H,
            Bitmap.Config.ARGB_8888
        )
        val canvas = Canvas(atlas)
        val paint = Paint().apply { isFilterBitmap = false }

        val slots = buildSpriteSlots()
        val loadedCount = drawSlotsToAtlas(context, canvas, paint, slots)

        android.util.Log.i(TAG, "buildAtlas: $loadedCount/${slots.size} sprites loaded")
        return atlas
    }

    /**
     * 构建全部图集精灵槽位（瓦片/建筑/地砖/作物/固定结构——布局数值全部来自
     * SpriteAtlasDef 生成物，本函数只消费布局不定义布局）。
     */
    private fun buildSpriteSlots(): List<SpriteSlot> {
        val buildingMap = buildingAtlasDrawableMap()
        return buildTileSlots() + buildBuildingSlots(buildingMap) +
            buildFloorSlots + buildCropSlots + buildStructureSlots + buildCloudSlots +
            buildRoadSlots
    }

    /**
     * 建筑图集名 → drawableRes 映射。
     *
     * 键必须是 [SpriteAtlasDef.BUILDING_NAMES] 中的**图集精灵名**（经
     * [BuildingFeature.effectiveSpriteName] 解析）而非显示名——显示名可带分级前缀
     * （如「初级多人住所」）而图集精灵名保持历史名称（「多人住所」），按显示名建
     * 映射会导致住所类建筑槽位查空、精灵图不显示（2026-08 修复根因）。
     */
    internal fun buildingAtlasDrawableMap(): Map<String, Int> =
        BuildingFeatureRegistry.all.associate { it.effectiveSpriteName() to it.drawableRes }

    /** 瓦片/装饰精灵槽位（含 6 种草皮地面变体）。 */
    private fun buildTileSlots(): List<SpriteSlot> =
        SpriteAtlasDef.TileType.values().map { tile ->
            val name = tileDrawableName(tile)
            val sr = tile.rect
            SpriteSlot(name, sr.x, sr.y, sr.w, sr.h, if (name.isEmpty()) 0 else TILE_DRAWABLE_MAP[name] ?: 0)
        }

    /** 瓦片 → drawable 名（R.drawable 预建映射，避免运行时 getIdentifier）。 */
    private fun tileDrawableName(tile: SpriteAtlasDef.TileType): String = when (tile) {
        SpriteAtlasDef.TileType.GROUND -> "map_grass_1"
        SpriteAtlasDef.TileType.GRASS_SMALL -> "decoration_grass_small"
        SpriteAtlasDef.TileType.GRASS_MEDIUM -> "decoration_grass_medium"
        SpriteAtlasDef.TileType.GRASS_LARGE -> "decoration_grass_large"
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

    /** 地砖精灵槽位。 */
    private val buildFloorSlots: List<SpriteSlot> =
        SpriteAtlasDef.FloorTileType.values().map { ft ->
            val r = ft.pixelRect
            SpriteSlot(ft.key, r.x, r.y, r.w, r.h, FLOOR_TILE_DRAWABLE_MAP[ft.key] ?: 0)
        }

    /** 灵田作物精灵槽位（WP6 生长动画三阶段）。 */
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
                    canvas.drawBitmap(bmp, null,
                        Rect(slot.x, slot.y, slot.x + slot.w, slot.y + slot.h),
                        paint)
                    // ★ 不调 recycle()：避免国产 ROM double-free（#11008）
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

    /** 图集精灵槽位（名称/像素位置/资源 ID） */
    private data class SpriteSlot(
        val name: String,
        val x: Int, val y: Int, val w: Int, val h: Int,
        val resId: Int
    )
}
