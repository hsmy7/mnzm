package com.xianxia.sect.ui.game

import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.model.GameData
import com.xianxia.sect.core.model.MapPreloadData
import com.xianxia.sect.core.util.SectMapTileGenerator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * 每宗独立地图 + 进入宗门转场控制器（2026-08-16 从 GameViewModel 抽取，收敛上帝类）。
 *
 * 职责：
 * - [sectMapData]：随 activeSectId 惰性生成每宗底图（主宗=mapSeed，被占宗门=派生种子），
 *   按种子缓存；[SectMapState.sectId] 携带对应宗门，杜绝切换瞬间读到旧宗门图（stale value）。
 * - [sectTransitionActive] + [beginSectTransition]：进入宗门转场状态机——开启后等目标
 *   宗门地图就绪且至少播放 1 秒再关闭（不依赖视频播完；5s 超时兜底；代数计数防并发误关）。
 */
class SectMapController(
    gameData: StateFlow<GameData>,
    private val scope: CoroutineScope
) {
    private val sectMapCache = ConcurrentHashMap<Int, MapPreloadData>()

    val sectMapData: StateFlow<SectMapState?> = gameData
        // mapSeed==0 = 默认未加载态（真实游戏加载/新建时 mapSeed 随机非 0）
        .map { gd -> if (gd.mapSeed == 0) null else (gd.activeSectId to gd.mapSeed) }
        .distinctUntilChanged()
        .map { key -> key?.let { (sid, seed) -> SectMapState(sid, generateSectMap(sid, seed)) } }
        .flowOn(Dispatchers.Default)
        .stateIn(scope, SharingStarted.Eagerly, null)

    private val _sectTransitionActive = MutableStateFlow(false)
    val sectTransitionActive: StateFlow<Boolean> = _sectTransitionActive.asStateFlow()

    private val sectTransitionGen = AtomicInteger(0)

    private fun generateSectMap(sectId: String, baseSeed: Int): MapPreloadData {
        val seed = deriveSectSeed(baseSeed, sectId)
        return sectMapCache.getOrPut(seed) { buildSectMap(seed) }
    }

    /**
     * 开始进入宗门转场。调用方随后需执行引擎 enterSect（本类只负责转场 UI 状态机）。
     * 转场在目标宗门地图就绪且至少播放 1 秒后自动关闭。
     */
    fun beginSectTransition(sectId: String) {
        val gen = sectTransitionGen.incrementAndGet()
        _sectTransitionActive.value = true
        scope.launch {
            val startNs = System.nanoTime()
            withTimeoutOrNull(5_000L) {
                sectMapData.first { it != null && it.sectId == sectId }
            }
            val elapsedMs = (System.nanoTime() - startNs) / 1_000_000L
            if (elapsedMs < 1_000L) delay(1_000L - elapsedMs)
            if (sectTransitionGen.get() == gen) _sectTransitionActive.value = false
        }
    }
}

/**
 * 每宗地图种子派生（2026-08-16）：主宗（""）用 baseSeed 保证与 boot 图一致；
 * 其他宗门用 baseSeed 与 sectId 混合出确定性种子，同一宗门永远同一张底图。
 */
internal fun deriveSectSeed(baseSeed: Int, sectId: String): Int =
    if (sectId.isEmpty()) baseSeed else (baseSeed * 31) xor sectId.hashCode()

/** 每宗地图构建（2026-08-16）：按种子确定性生成瓦片数据（纯函数，可测试）。 */
internal fun buildSectMap(seed: Int): MapPreloadData {
    val w = GameConfig.SectMap.WORLD_WIDTH_CELLS
    val h = GameConfig.SectMap.WORLD_HEIGHT_CELLS
    val raw = SectMapTileGenerator.generateTileData(
        w, h,
        worldSeed = seed,
        borderTreeRing = GameConfig.SectMap.BORDER_TREE_RING
    )
    return MapPreloadData(
        rawTileData = raw,
        worldWidthCells = w,
        worldHeightCells = h,
        tileSize = GameConfig.SectMap.TILE_SIZE,
        worldPixelWidth = GameConfig.SectMap.WORLD_PIXEL_WIDTH,
        worldPixelHeight = GameConfig.SectMap.WORLD_PIXEL_HEIGHT,
        flatTileData = raw.flatMap { it.toList() }.toIntArray()
    )
}

/**
 * 每宗地图状态（2026-08-16）：携带该地图对应的 [sectId]（空串 = 主宗）。
 * 转场等待/测试据此判断当前 sectMapData 是否已切到目标宗门，避免读取到
 * 切换瞬间的旧宗门地图（stale value）。
 */
data class SectMapState(
    val sectId: String,
    val map: MapPreloadData
)
