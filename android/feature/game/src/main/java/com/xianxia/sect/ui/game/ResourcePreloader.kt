package com.xianxia.sect.ui.game

import android.content.Context
import android.util.Log
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.xianxia.sect.core.config.BuildingConfigService
import com.xianxia.sect.core.registry.EquipmentDatabase
import com.xianxia.sect.core.registry.ForgeRecipeDatabase
import com.xianxia.sect.core.registry.ItemDatabase
import com.xianxia.sect.core.registry.ManualDatabase
import com.xianxia.sect.core.registry.PillRecipeDatabase
import com.xianxia.sect.ui.components.allEquipmentSpriteResIds
import com.xianxia.sect.ui.components.allManualSpriteResIds
import com.xianxia.sect.ui.components.allPillSpriteResIds
import com.xianxia.sect.ui.game.building.BuildingRegistry
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 游戏资源预加载器
 * 从 SaveLoadViewModel.kt 提取，负责在游戏启动/读档时预加载数据库与位图
 */
@Singleton
class ResourcePreloader @Inject constructor(
    @ApplicationContext private val context: Context,
    private val buildingConfigService: BuildingConfigService
) {
    companion object {
        private const val TAG = "ResourcePreloader"
        private const val MAX_SPRITE_DIMENSION = 300
        private const val MAX_BUILDING_DIMENSION = 256
    }

    /**
     * 预加载结果
     */
    data class PreloadResult(
        val buildingBitmaps: Map<String, ImageBitmap>,
        val itemSprites: Map<Int, ImageBitmap>
    )

    /**
     * 预加载游戏资源
     *
     * @param onProgress 进度回调，参数为 0f..1f 之间的进度值
     */
    suspend fun preloadGameResources(onProgress: (Float) -> Unit): PreloadResult {
        // ManualDatabase 初始化
        onProgress(SaveLoadViewModelConstants.PROGRESS_MANUAL_PRELOAD)
        withContext(Dispatchers.IO) {
            val result = ManualDatabase.initializeSync(context)
            result.onSuccess { Log.i(TAG, "ManualDatabase preloaded") }
                .onFailure { Log.w(TAG, "ManualDatabase preload failed", it) }
        }

        // 配方数据库预热
        onProgress(SaveLoadViewModelConstants.PROGRESS_RECIPE_PRELOAD)
        withContext(Dispatchers.Default) {
            ItemDatabase.allPills.size
            EquipmentDatabase.allTemplates.size
            ItemDatabase.beastMaterials.size
            PillRecipeDatabase.getAllRecipes()
            ForgeRecipeDatabase.getAllRecipes()
            buildingConfigService.initialize()
        }

        // 位图预加载
        onProgress(SaveLoadViewModelConstants.PROGRESS_BITMAP_PRELOAD)
        return withContext(Dispatchers.Default) {
            val buildingBitmaps = preloadBuildingBitmaps()
            val itemSprites = preloadItemSprites()
            Log.d(TAG, "Building bitmaps: ${buildingBitmaps.size}, Item sprites: ${itemSprites.size}")
            PreloadResult(buildingBitmaps, itemSprites)
        }
    }

    private fun preloadBuildingBitmaps(): Map<String, ImageBitmap> {
        val bitmapNames = BuildingRegistry.names
        return bitmapNames.mapNotNull { name ->
            val resId = getBuildingDrawableResId(name)
            try {
                val opts = android.graphics.BitmapFactory.Options().apply {
                    inJustDecodeBounds = true
                }
                android.graphics.BitmapFactory.decodeResource(
                    context.resources, resId, opts)
                opts.inSampleSize = calculateSpriteSampleSize(
                    opts.outWidth, opts.outHeight, MAX_BUILDING_DIMENSION)
                opts.inJustDecodeBounds = false
                val bmp = android.graphics.BitmapFactory.decodeResource(
                    context.resources, resId, opts)
                name to (bmp?.asImageBitmap() ?: return@mapNotNull null)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to decode building bitmap: $name", e)
                null
            }
        }.toMap()
    }

    private fun preloadItemSprites(): Map<Int, ImageBitmap> {
        val spriteResIds = allPillSpriteResIds() + allManualSpriteResIds() + allEquipmentSpriteResIds()
        return spriteResIds.mapNotNull { resId ->
            try {
                val opts = android.graphics.BitmapFactory.Options().apply {
                    inJustDecodeBounds = true
                }
                android.graphics.BitmapFactory.decodeResource(context.resources, resId, opts)
                val sampleSize = calculateSpriteSampleSize(opts.outWidth, opts.outHeight)
                val decodeOpts = android.graphics.BitmapFactory.Options().apply {
                    inSampleSize = sampleSize
                }
                val bmp = android.graphics.BitmapFactory.decodeResource(context.resources, resId, decodeOpts)
                resId to (bmp?.asImageBitmap() ?: return@mapNotNull null)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to preload sprite $resId", e)
                null
            }
        }.toMap()
    }

    private fun calculateSpriteSampleSize(width: Int, height: Int, maxDimension: Int = MAX_SPRITE_DIMENSION): Int {
        var sampleSize = 1
        while (width / (sampleSize * 2) >= maxDimension || height / (sampleSize * 2) >= maxDimension) {
            sampleSize *= 2
        }
        return sampleSize
    }

    private fun getBuildingDrawableResId(displayName: String): Int = BuildingRegistry.drawableRes(displayName)
}
