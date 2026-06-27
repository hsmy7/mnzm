package com.xianxia.sect.ui.game

import android.content.Context
import android.util.Log
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.xianxia.sect.core.config.ConfigLoader
import com.xianxia.sect.core.config.BuildingConfigService
import com.xianxia.sect.core.registry.GameDataManager
import com.xianxia.sect.core.registry.ManualDatabase
import com.xianxia.sect.core.util.PortraitPool
import com.xianxia.sect.ui.components.allEquipmentSpriteResIds
import com.xianxia.sect.ui.components.allManualSpriteResIds
import com.xianxia.sect.ui.components.allPillSpriteResIds
import com.xianxia.sect.ui.components.SpriteResRegistry
import com.xianxia.sect.ui.game.building.BuildingRegistry
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 游戏资源预加载器
 *
 * 在游戏启动/读档时预加载所有静态资源，采用分层并行策略：
 * - 阶段1（并行）: GameDataManager 注册表初始化 + ConfigLoader 配置加载
 * - 阶段2（并行）: L0 首屏精灵（弟子头像+UI按钮）+ L1 重要精灵（建筑物+物品）
 * - L2 后台: 剩余精灵异步加载，不阻塞首帧渲染
 */
@Singleton
class ResourcePreloader @Inject constructor(
    @ApplicationContext private val context: Context,
    private val buildingConfigService: BuildingConfigService,
    private val configLoader: ConfigLoader
) {
    companion object {
        private const val TAG = "ResourcePreloader"
        private const val MAX_SPRITE_DIMENSION = 300
        private const val MAX_BUILDING_DIMENSION = 256
        private const val MAX_PORTRAIT_DIMENSION = 256
        private const val MAX_UI_DIMENSION = 128

        /**
         * 计算位图采样大小（纯逻辑方法，便于测试）
         */
        fun calcSampleSize(
            width: Int,
            height: Int,
            maxDimension: Int = MAX_SPRITE_DIMENSION
        ): Int {
            var sampleSize = 1
            while (width / (sampleSize * 2) >= maxDimension ||
                height / (sampleSize * 2) >= maxDimension
            ) {
                sampleSize *= 2
            }
            return sampleSize
        }
    }

    /**
     * 预加载结果
     *
     * @param buildingBitmaps 建筑物精灵图（宗门地图渲染用）
     * @param itemSprites 物品精灵图（功法/药丸/装备，仓库用）
     * @param portraitSprites 弟子头像精灵图（L0，首屏弟子列表用）
     * @param uiSprites 关键 UI 精灵图（L0，底部按钮栏用）
     */
    data class PreloadResult(
        val buildingBitmaps: Map<String, ImageBitmap>,
        val itemSprites: Map<Int, ImageBitmap>,
        val portraitSprites: Map<String, ImageBitmap>,
        val uiSprites: Map<String, ImageBitmap>
    )

    /**
     * 预加载游戏资源（分层并行）
     *
     * @param onProgress 进度回调 0f..1f
     * @param onPhase 阶段变更回调，传入当前阶段标签
     */
    suspend fun preloadGameResources(
        onProgress: (Float) -> Unit,
        onPhase: (String) -> Unit
    ): PreloadResult {
        // ── 阶段1: 数据初始化（GameDataManager + ConfigLoader + ManualDatabase）并行 ──
        onPhase(SaveLoadViewModelConstants.PHASE_DATA_PRELOAD)
        onProgress(SaveLoadViewModelConstants.PROGRESS_DATA_PRELOAD)

        coroutineScope {
            val dataInit = async(Dispatchers.Default) {
                val ok = GameDataManager.initialize(context)
                if (ok) {
                    configLoader.load()
                    buildingConfigService.initialize()
                }
                ok
            }
            val manualInit = async(Dispatchers.IO) {
                val result = ManualDatabase.initializeSync(context)
                result.onSuccess { Log.i(TAG, "ManualDatabase preloaded") }
                    .onFailure { Log.w(TAG, "ManualDatabase preload failed", it) }
                result
            }
            dataInit.await()
            manualInit.await()
        }

        // ── 阶段2: 精灵图预加载（L0 + L1）并行 ──
        onPhase(SaveLoadViewModelConstants.PHASE_SPRITE_PRELOAD)
        onProgress(SaveLoadViewModelConstants.PROGRESS_SPRITE_PRELOAD)

        return withContext(Dispatchers.Default) {
            val buildingDeferred = async { preloadBuildingBitmaps() }
            val itemDeferred = async { preloadItemSprites() }
            val portraitDeferred = async { preloadPortraitSprites() }
            val uiDeferred = async { preloadCriticalUiSprites() }

            val result = PreloadResult(
                buildingBitmaps = buildingDeferred.await(),
                itemSprites = itemDeferred.await(),
                portraitSprites = portraitDeferred.await(),
                uiSprites = uiDeferred.await()
            )
            Log.d(TAG, "Preload complete: buildings=${result.buildingBitmaps.size}, " +
                "items=${result.itemSprites.size}, portraits=${result.portraitSprites.size}, " +
                "ui=${result.uiSprites.size}")
            result
        }
    }

    /**
     * 启动 L2 后台精灵图预加载（不阻塞首帧）
     *
     * 在 MainGameScreen 已显示后调用，异步加载剩余精灵到 [onComplete] 回调。
     */
    fun launchBackgroundPreload(
        scope: CoroutineScope,
        onComplete: (Map<Int, ImageBitmap>) -> Unit
    ) {
        scope.launch(Dispatchers.Default) {
            try {
                val sprites = preloadRemainingSprites()
                Log.d(TAG, "L2 background preload complete: ${sprites.size} sprites")
                onComplete(sprites)
            } catch (e: Exception) {
                Log.w(TAG, "L2 background preload failed", e)
            }
        }
    }

    // ── L0: 弟子头像精灵图 ──

    private fun preloadPortraitSprites(): Map<String, ImageBitmap> {
        val portraitNames = PortraitPool.allPortraitNames()
        return portraitNames.mapNotNull { name ->
            val resId = context.resources.getIdentifier(
                name, "drawable", context.packageName)
            if (resId == 0) return@mapNotNull null
            try {
                val bmp = decodeBitmap(resId, MAX_PORTRAIT_DIMENSION)
                name to (bmp?.asImageBitmap() ?: return@mapNotNull null)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to decode portrait: $name", e)
                null
            }
        }.toMap()
    }

    // ── L0: 关键 UI 精灵图 ──

    private fun preloadCriticalUiSprites(): Map<String, ImageBitmap> {
        val uiNames = listOf(
            "ui_button", "ui_close_button", "ui_play_button",
            "ui_pause_button", "ui_settings_button", "ui_start_button",
            "loading_background"
        )
        return uiNames.mapNotNull { name ->
            val resId = context.resources.getIdentifier(
                name, "drawable", context.packageName)
            if (resId == 0) return@mapNotNull null
            try {
                val bmp = decodeBitmap(resId, MAX_UI_DIMENSION)
                name to (bmp?.asImageBitmap() ?: return@mapNotNull null)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to decode UI sprite: $name", e)
                null
            }
        }.toMap()
    }

    // ── L1: 建筑物精灵图 ──

    private fun preloadBuildingBitmaps(): Map<String, ImageBitmap> {
        val bitmapNames = BuildingRegistry.names
        return bitmapNames.mapNotNull { name ->
            val resId = getBuildingDrawableResId(name)
            try {
                val bmp = decodeBitmap(resId, MAX_BUILDING_DIMENSION)
                name to (bmp?.asImageBitmap() ?: return@mapNotNull null)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to decode building bitmap: $name", e)
                null
            }
        }.toMap()
    }

    // ── L1: 物品精灵图（功法/药丸/装备） ──

    private fun preloadItemSprites(): Map<Int, ImageBitmap> {
        val spriteResIds = allPillSpriteResIds() +
            allManualSpriteResIds() +
            allEquipmentSpriteResIds()
        return spriteResIds.mapNotNull { resId ->
            try {
                val bmp = decodeBitmap(resId, MAX_SPRITE_DIMENSION)
                resId to (bmp?.asImageBitmap() ?: return@mapNotNull null)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to preload sprite $resId", e)
                null
            }
        }.toMap()
    }

    // ── L2: 剩余精灵图（后台异步） ──

    private fun preloadRemainingSprites(): Map<Int, ImageBitmap> {
        val allRemaining = mutableSetOf<Int>()

        // 装备精灵图
        allRemaining.addAll(SpriteResRegistry.allEquipmentResIds)

        // 药丸/功法精灵图（已在 L1 加载，但通过 resId 查重去重）
        allRemaining.addAll(allPillSpriteResIds())
        allRemaining.addAll(allManualSpriteResIds())

        // 妖兽材料精灵图
        SpriteResRegistry.materialSprites.values.forEach { allRemaining.add(it) }

        // 草药/种子精灵图
        SpriteResRegistry.herbSprites.values.forEach { allRemaining.add(it) }
        SpriteResRegistry.seedSprites.values.forEach { allRemaining.add(it) }
        SpriteResRegistry.growingSprites.values.forEach { allRemaining.add(it) }

        // 储物袋精灵图
        SpriteResRegistry.storageBagSprites.values.forEach { allRemaining.add(it) }

        // 灵石精灵图
        SpriteResRegistry.spiritStoneSprites.values
            .filter { it != 0 }
            .forEach { allRemaining.add(it) }

        // 宗门图标
        SpriteResRegistry.sectIconSprites.values
            .filter { it != 0 }
            .forEach { allRemaining.add(it) }

        return allRemaining.mapNotNull { resId ->
            try {
                val bmp = decodeBitmap(resId, MAX_SPRITE_DIMENSION)
                resId to (bmp?.asImageBitmap() ?: return@mapNotNull null)
            } catch (e: Exception) {
                null // L2 静默跳过失败的精灵
            }
        }.toMap()
    }

    // ── 位图解码工具方法 ──

    private fun decodeBitmap(
        resId: Int,
        maxDimension: Int
    ): android.graphics.Bitmap? {
        val opts = android.graphics.BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }
        android.graphics.BitmapFactory.decodeResource(context.resources, resId, opts)
        opts.inSampleSize = calculateSampleSize(
            opts.outWidth, opts.outHeight, maxDimension)
        opts.inJustDecodeBounds = false
        return android.graphics.BitmapFactory.decodeResource(
            context.resources, resId, opts)
    }

    internal fun calculateSampleSize(
        width: Int,
        height: Int,
        maxDimension: Int = MAX_SPRITE_DIMENSION
    ): Int = calcSampleSize(width, height, maxDimension)

    private fun getBuildingDrawableResId(displayName: String): Int =
        BuildingRegistry.drawableRes(displayName)
}
