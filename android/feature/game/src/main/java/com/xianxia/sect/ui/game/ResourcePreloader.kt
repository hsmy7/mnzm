package com.xianxia.sect.ui.game

import android.content.Context
import android.util.Log
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.audio.AudioEngine
import com.xianxia.sect.core.config.ConfigLoader
import com.xianxia.sect.core.config.BuildingConfigService
import com.xianxia.sect.core.registry.GameDataManager
import com.xianxia.sect.core.registry.ManualDatabase
import com.xianxia.sect.core.util.PortraitPool
import com.xianxia.sect.ui.components.allEquipmentSpriteResIds
import com.xianxia.sect.ui.components.allManualSpriteResIds
import com.xianxia.sect.ui.components.allPillSpriteResIds
import com.xianxia.sect.ui.components.AtlasPacker
import com.xianxia.sect.ui.components.AtlasResult
import com.xianxia.sect.ui.components.SpriteCategory
import com.xianxia.sect.ui.components.SpriteResRegistry
import com.xianxia.sect.core.engine.di.IoDispatcher
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
    private val configLoader: ConfigLoader,
    private val ioDispatcher: IoDispatcher,
    private val audioEngine: AudioEngine? = null
) {
    companion object {
        private const val TAG = "ResourcePreloader"
        private const val MAX_SPRITE_DIMENSION = 300
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
     * @param itemSprites 物品精灵图（功法/药丸/装备，仓库用）
     * @param itemAtlas 小物品精灵图合并后的图集，减少纹理切换（可能为 null）
     * @param portraitSprites 弟子头像精灵图（L0，首屏弟子列表用）
     * @param uiSprites 关键 UI 精灵图（L0，底部按钮栏用）
     */
    data class PreloadResult(
        val itemSprites: Map<Int, ImageBitmap>,
        val itemAtlas: AtlasResult?,
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
                    GameConfig.initialize(configLoader.load())
                    buildingConfigService.initialize()
                }
                ok
            }
            val manualInit = async(ioDispatcher.dispatcher) {
                val result = ManualDatabase.initializeSync(context)
                result.onSuccess { Log.i(TAG, "ManualDatabase preloaded") }
                    .onFailure { Log.w(TAG, "ManualDatabase preload failed", it) }
                result
            }
            dataInit.await()
            manualInit.await()

            // ── 音频引擎初始化（不阻塞数据加载） ──
            audioEngine?.init()
        }

        // ── 阶段2: 精灵图预加载（L0 + L1）并行 ──
        onPhase(SaveLoadViewModelConstants.PHASE_SPRITE_PRELOAD)
        onProgress(SaveLoadViewModelConstants.PROGRESS_SPRITE_PRELOAD)

        return withContext(Dispatchers.Default) {
            val itemDeferred = async { preloadItemSprites() }
            val portraitDeferred = async { preloadPortraitSprites() }
            val uiDeferred = async { preloadCriticalUiSprites() }

            val itemSprites = itemDeferred.await()

            // 图集打包：将小物品精灵合并到一张大图上，降低 GPU 纹理切换开销
            val atlasResult = try {
                AtlasPacker().pack(itemSprites)
            } catch (e: Exception) {
                Log.w(TAG, "Atlas packing failed, falling back to individual sprites", e)
                null
            }

            val result = PreloadResult(
                itemSprites = itemSprites,
                itemAtlas = atlasResult,
                portraitSprites = portraitDeferred.await(),
                uiSprites = uiDeferred.await()
            )
            Log.d(TAG, "Preload complete: " +
                "items=${result.itemSprites.size}, atlas=${result.itemAtlas != null}, " +
                "portraits=${result.portraitSprites.size}, ui=${result.uiSprites.size}")

            // ── 音频预加载（不阻塞精灵图主流程） ──
            preloadAudio()

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
        val portraitNames = PortraitPool.allPortraitNames() + "disciple_portrait"
        return portraitNames.mapNotNull { name ->
            val resId = if (name == "disciple_portrait") {
                SpriteResRegistry.resolve("disciple_portrait") ?: return@mapNotNull null
            } else {
                context.resources.getIdentifier(
                    name, "drawable", context.packageName)
            }
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
        val uiResIds = SpriteResRegistry.categoryResIds(SpriteCategory.UI)
        return uiResIds.mapNotNull { resId ->
            try {
                val bmp = decodeBitmap(resId, MAX_UI_DIMENSION)
                // 通过资源名反查精灵图名（用于预加载结果 key）
                val name = context.resources.getResourceEntryName(resId)
                name to (bmp?.asImageBitmap() ?: return@mapNotNull null)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to decode UI sprite: $resId", e)
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

        // ── 统一精灵图分类（codegen 注册，自动发现） ──
        // 装备/妖兽材料/草药种子成长期（ITEM）/储物袋/灵石/宗门图标/妖兽/洞穴/天劫试炼/背景/地图精灵图
        SpriteResRegistry.categoryResIds(SpriteCategory.EQUIPMENT)
            .forEach { allRemaining.add(it) }

        // 药丸/功法精灵图（已在 L1 加载，但通过 resId 查重去重）
        allRemaining.addAll(allPillSpriteResIds())
        allRemaining.addAll(allManualSpriteResIds())

        // 妖兽材料精灵图
        SpriteResRegistry.categoryResIds(SpriteCategory.MATERIAL)
            .forEach { allRemaining.add(it) }

        // 草药/种子/成长期精灵图（已通过 SpriteCategory.ITEM 注册）
        SpriteResRegistry.categoryResIds(SpriteCategory.ITEM).forEach { allRemaining.add(it) }

        // 储物袋精灵图
        SpriteResRegistry.categoryResIds(SpriteCategory.STORAGE_BAG)
            .forEach { allRemaining.add(it) }

        // 灵石精灵图
        SpriteResRegistry.categoryResIds(SpriteCategory.SPIRIT_STONE)
            .filter { it != 0 }
            .forEach { allRemaining.add(it) }

        // 宗门图标
        SpriteResRegistry.categoryResIds(SpriteCategory.SECT_ICON)
            .filter { it != 0 }
            .forEach { allRemaining.add(it) }

        // 妖兽/洞穴/天劫试炼/背景/地图精灵图
        SpriteResRegistry.categoryResIds(SpriteCategory.BEAST)
            .forEach { allRemaining.add(it) }
        SpriteResRegistry.categoryResIds(SpriteCategory.CAVE)
            .forEach { allRemaining.add(it) }
        SpriteResRegistry.categoryResIds(SpriteCategory.HEAVENLY_TRIAL)
            .forEach { allRemaining.add(it) }
        SpriteResRegistry.categoryResIds(SpriteCategory.BACKGROUND)
            .forEach { allRemaining.add(it) }
        SpriteResRegistry.categoryResIds(SpriteCategory.PORTRAIT)
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

    // ── 音频资源预加载（SFX + BGM） ──

    /**
     * 预加载音频资源。
     *
     * 在精灵图预加载阶段（Phase 2）末尾调用，不阻塞主流程。
     * 新增音效或 BGM 时在此处添加预加载调用。
     *
     * 添加步骤：
     * 1. 将 .mp3/.ogg 文件放入 `res/raw/` 目录（两个模块均需放置）
     * 2. 在此方法中调用 engine.preloadSound("名称", R.raw.xxx)
     */
    private fun preloadAudio() {
        val engine = audioEngine ?: return
        if (!engine.isReady) return

        // 按钮音效
        engine.preloadSound("click", com.xianxia.sect.feature.game.R.raw.sfx_button)
        // 背景音乐
        engine.preloadBGM(com.xianxia.sect.feature.game.R.raw.bgm_main)

        Log.d(TAG, "Audio preload phase complete " +
            "(soundCache=${engine.isReady})")
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
}
