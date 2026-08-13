package com.xianxia.sect.ui.game.sect

import android.content.Context
import android.graphics.Bitmap
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
        val atlas = Bitmap.createBitmap(
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
     * 构建全部图集精灵槽位（瓦片/建筑/地砖/作物——布局数值全部来自
     * SpriteAtlasDef 生成物，本函数只消费布局不定义布局）。
     */
    private fun buildSpriteSlots(): List<SpriteSlot> {
        // 瓦片/装饰精灵 R.drawable 预建映射（替代 getIdentifier 运行时查找，
        // 避免华为 HarmonyOS 资源表分片返回 0 导致精灵图加载为空白）
        val tileDrawableMap = mapOf(
            "map_tile" to R.drawable.map_tile,
            "map_tile_v2" to R.drawable.map_tile_v2,
            "decoration_grass_small" to R.drawable.decoration_grass_small,
            "decoration_grass_medium" to R.drawable.decoration_grass_medium,
            "decoration_grass_large" to R.drawable.decoration_grass_large,
            "decoration_tree1" to R.drawable.decoration_tree1,
            "decoration_tree2" to R.drawable.decoration_tree2,
        )
        val floorTileDrawableMap = mapOf(
            "floor_tile_2x2" to R.drawable.floor_tile_2x2,
            "floor_tile_2x3" to R.drawable.floor_tile_2x3,
            "floor_tile_3x2" to R.drawable.floor_tile_3x2,
            "floor_tile_3x3" to R.drawable.floor_tile_3x3,
            "spirit_mine_ground" to R.drawable.spirit_mine_ground,
        )
        val buildingMap = BuildingFeatureRegistry.all.associate { it.displayName to it.drawableRes }

        // 瓦片精灵：来自 SpriteAtlasDef.TileType
        val tileSlots = SpriteAtlasDef.TileType.values().map { tile ->
            val name = when (tile) {
                SpriteAtlasDef.TileType.GROUND -> "map_tile"
                SpriteAtlasDef.TileType.GRASS_SMALL -> "decoration_grass_small"
                SpriteAtlasDef.TileType.GRASS_MEDIUM -> "decoration_grass_medium"
                SpriteAtlasDef.TileType.GRASS_LARGE -> "decoration_grass_large"
                SpriteAtlasDef.TileType.TREE1 -> "decoration_tree1"
                SpriteAtlasDef.TileType.TREE2 -> "decoration_tree2"
                SpriteAtlasDef.TileType.TILE_BUILDING -> ""
                SpriteAtlasDef.TileType.GROUND_V2 -> "map_tile_v2"
            }
            val sr = tile.rect
            val id = if (name.isEmpty()) 0 else tileDrawableMap[name] ?: 0
            SpriteSlot(name, sr.x, sr.y, sr.w, sr.h, id)
        }

        // 建筑精灵：来自 SpriteAtlasDef.BUILDING_NAMES
        val buildingSlots = SpriteAtlasDef.BUILDING_NAMES.indices.map { idx ->
            val name = SpriteAtlasDef.BUILDING_NAMES[idx]
            val sr = SpriteAtlasDef.buildingRect(idx)
            SpriteSlot(name, sr.x, sr.y, sr.w, sr.h, buildingMap[name] ?: 0)
        }

        // 地砖精灵：来自 SpriteAtlasDef.FloorTileType
        val floorTileSlots = SpriteAtlasDef.FloorTileType.values().map { ft ->
            val r = ft.pixelRect
            SpriteSlot(ft.key, r.x, r.y, r.w, r.h, floorTileDrawableMap[ft.key] ?: 0)
        }

        // 灵田作物精灵：来自 SpriteAtlasDef.CropStage（WP6 生长动画——
        // 图集 y=0 行 832/896/960 空槽，与 C++ crop_seedling/growing/mature 同步）
        val cropDrawableMap = listOf(
            R.drawable.growing_spiritgrass7,
            R.drawable.growing_spiritgrass8,
            R.drawable.growing_spiritgrass9,
        )
        val cropSlots = SpriteAtlasDef.CropStage.values().map { stage ->
            val r = stage.rect
            SpriteSlot(
                stage.name, r.x, r.y, r.w, r.h,
                cropDrawableMap.getOrNull(stage.ordinal) ?: 0
            )
        }

        return tileSlots + buildingSlots + floorTileSlots + cropSlots
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
