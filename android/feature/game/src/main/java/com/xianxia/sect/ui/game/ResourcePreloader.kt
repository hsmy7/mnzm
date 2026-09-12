package com.xianxia.sect.ui.game

import android.content.Context
import android.util.Log
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.audio.AudioPlayerFacade
import com.xianxia.sect.core.config.ConfigLoader
import com.xianxia.sect.core.config.BuildingConfigService
import com.xianxia.sect.core.platform.AssetSource
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
import kotlinx.coroutines.CancellationException
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
    private val assetSource: AssetSource,
    private val buildingConfigService: BuildingConfigService,
    private val configLoader: ConfigLoader,
    private val ioDispatcher: IoDispatcher,
    private val audioEngine: AudioPlayerFacade? = null
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
    @Suppress("TooGenericExceptionCaught") // 防御兜底: 异常源跨IO/SDK不可枚举, 降级继续+日志留痕, 非静默吞噬
    suspend fun preloadGameResources(
        onProgress: (Float) -> Unit,
        onPhase: (String) -> Unit
    ): PreloadResult {
        // ── 阶段1: 数据初始化（GameDataManager + ConfigLoader + ManualDatabase）并行 ──
        onPhase(SaveLoadViewModelConstants.PHASE_DATA_PRELOAD)
        onProgress(SaveLoadViewModelConstants.PROGRESS_DATA_PRELOAD)

        coroutineScope {
            val dataInit = async(Dispatchers.Default) {
                val ok = GameDataManager.initialize(assetSource)
                if (ok) {
                    GameConfig.initialize(configLoader.load())
                    buildingConfigService.initialize()
                }
                ok
            }
            val manualInit = async(ioDispatcher.dispatcher) {
                val result = ManualDatabase.initializeSync(assetSource)
                result.onSuccess { Log.i(TAG, "ManualDatabase preloaded") }
                    .onFailure { Log.w(TAG, "ManualDatabase preload failed", it) }
                result
            }
            // ── native-game-core .so 预载 ──
            // 在数据阶段并行 dlopen（早于游戏循环首 tick 的串行关键路径）；
            // IslandCliff/RoadCompositor 渲染链首次触达也会各自守卫加载，
            // ensureLoaded 幂等（后续调用零开销）。
            val nativeLibInit = async(ioDispatcher.dispatcher) {
                runCatching { com.xianxia.sect.core.nativebridge.GameCoreBridge.ensureLoaded() }
                    .onFailure { Log.w(TAG, "native-game-core preload failed", it) }
            }
            dataInit.await()
            manualInit.await()
            nativeLibInit.await()

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
            } catch (e: CancellationException) {
                throw e // 取消穿透: 读档取消时中止预加载
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
    @Suppress("TooGenericExceptionCaught") // 防御兜底: 异常源跨IO/SDK不可枚举, 降级继续+日志留痕, 非静默吞噬
    fun launchBackgroundPreload(
        scope: CoroutineScope,
        onComplete: (Map<Int, ImageBitmap>) -> Unit
    ) {
        scope.launch(Dispatchers.Default) {
            try {
                val sprites = preloadRemainingSprites()
                Log.d(TAG, "L2 background preload complete: ${sprites.size} sprites")
                onComplete(sprites)
            } catch (e: CancellationException) {
                throw e // 取消穿透: 宿主 scope 取消时中止 L2 预加载, 不再回调 UI
            } catch (e: Exception) {
                Log.w(TAG, "L2 background preload failed", e)
            }
        }
    }

    // ── L0: 弟子头像精灵图 ──

    @Suppress("TooGenericExceptionCaught") // 防御兜底: 异常源跨IO/SDK不可枚举, 降级继续+日志留痕, 非静默吞噬
    private fun preloadPortraitSprites(): Map<String, ImageBitmap> {
        val portraitNames = PortraitPool.allPortraitNames() + "disciple_portrait"
        return portraitNames.mapNotNull { name ->
            val resId = if (name == "disciple_portrait") {
                SpriteResRegistry.resolve("disciple_portrait") ?: return@mapNotNull null
            } else {
                // 动态资源查找走 PortraitPool 预构建映射
                //（XianxiaApplication.onCreate 已 initialize），避免裸 getIdentifier
                PortraitPool.getResourceId(name)
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

    @Suppress("TooGenericExceptionCaught") // 防御兜底: 异常源跨IO/SDK不可枚举, 降级继续+日志留痕, 非静默吞噬
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

    @Suppress("TooGenericExceptionCaught") // 防御兜底: 异常源跨IO/SDK不可枚举, 降级继续+日志留痕, 非静默吞噬
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

    // ── L2: 剩余小图标精灵图（后台异步，经 SaveLoadViewModel.l2Sprites 接入 UI）──

    /**
     * L2 后台预载：与 L1 同规格（300px 上限）的小图标类。
     *
     * 只预载小图标类；大图类 BEAST/CAVE/HEAVENLY_TRIAL/BACKGROUND/PORTRAIT
     * 不预载——大图类即使解码接入也会肉眼可见降质（ItemCard/SpriteImage 实际
     * 显示尺寸 ≤192px），300px 缓存无视觉收益且纯占内存
     *（每张 300²≈350KB × 数十张）。
     * - 本结果经 MainGameScreen 合并进 [LocalItemSpriteCache]，Material/草药/
     *   储物袋/灵石/宗门图标首次渲染避免 painterResource 全分辨率（1024px 级）
     *   解码。
     * - EQUIPMENT/PILL/MANUAL 已在 L1 加载，此处保留入集仅为 resId 去重兜底。
     */
    @Suppress("TooGenericExceptionCaught") // 防御兜底: 异常源不可枚举, 失败降级继续, 非静默吞噬
    private fun preloadRemainingSprites(): Map<Int, ImageBitmap> {
        val allRemaining = mutableSetOf<Int>()

        // 装备/功法/药丸（L1 已载，Set 去重）
        SpriteResRegistry.categoryResIds(SpriteCategory.EQUIPMENT)
           .forEach { allRemaining.add(it) }
        allRemaining.addAll(allPillSpriteResIds())
        allRemaining.addAll(allManualSpriteResIds())

        // 妖兽材料精灵图
        SpriteResRegistry.categoryResIds(SpriteCategory.MATERIAL)
            .forEach { allRemaining.add(it) }

        // 草药/种子/成长期精灵图
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

        return allRemaining.mapNotNull { resId ->
            try {
                val bmp = decodeBitmap(resId, MAX_SPRITE_DIMENSION)
                resId to (bmp?.asImageBitmap() ?: return@mapNotNull null)
            } catch (ignored: Exception) {
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
