package com.xianxia.sect.core.engine

import com.xianxia.sect.core.config.BuildingConfigService
import com.xianxia.sect.core.engine.domain.disciple.DiscipleSnapshotCache
import com.xianxia.sect.core.model.GridBuildingData
import com.xianxia.sect.core.model.MapPreloadData
import com.xianxia.sect.core.state.BootPhase
import com.xianxia.sect.core.state.GameStateStore
import com.xianxia.sect.core.state.RunState
import com.xianxia.sect.core.util.DomainLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 启动序列控制器 — 统一编排游戏加载/新游戏/重启的启动流程。
 *
 * ## 职责
 *
 * 1. 推进 [BootPhase]：UNINITIALIZED → ... → BOOT_COMPLETE
 * 2. 管理 [RunState] 切换：IDLE → LOADING → PLAYING（reload 时经过 RELOADING）
 * 3. 资源预加载编排（通过回调桥接 UI 层）
 * 4. 统一错误恢复逻辑
 *
 * ## 调用方职责
 *
 * ViewModel 在调用 [boot] 前负责：
 * 1. 从 [StorageFacade] 加载存档数据
 * 2. 调用 [GameEngine.loadData] / [createNewGame]
 * 3. 设置 [StorageFacade] 的 currentSlot
 * 4. 初始化 [GameRngManager] 的状态
 *
 * [boot] 负责后续所有生命周期管理和错误恢复。
 */
@Singleton
class BootSequenceController @Inject constructor(
    private val stateStore: GameStateStore,
    private val gameEngineCore: GameEngineCore,
    private val gameEngine: GameEngine,
    private val buildingConfigService: BuildingConfigService,
    private val discipleSnapshotCache: DiscipleSnapshotCache
) {
    companion object {
        private const val TAG = "BootSequence"
    }

    /** 重入保护：防止 boot() 被并发调用 */
    private val bootInProgress = AtomicBoolean(false)

    /**
     * 统一启动入口。必须在 [gameEngine.loadData] / [gameEngine.createNewGame] 之后调用。
     *
     * @param slot 存档槽位
     * @param onPreloadResources 资源预加载回调（UI 层提供实现）
     * @param onProgress 进度回调 (0.0 ~ 1.0)
     * @param onPhase 阶段标签回调（UI 展示用）
     * @param onMapReady 地图预加载数据就绪回调
     * @param onSuccess 启动成功回调
     * @param onError 启动失败回调
     */
    @Suppress("LongParameterList")
    suspend fun boot(
        slot: Int,
        onPreloadResources: suspend () -> Unit = {},
        onProgress: (Float) -> Unit = {},
        onPhase: (String) -> Unit = {},
        onMapReady: (MapPreloadData) -> Unit = {},
        onSuccess: () -> Unit = {},
        onError: (String) -> Unit = {}
    ): Result<Unit> {
        val startTime = System.currentTimeMillis()
        var gameStarted = false

        try {
            if (!bootInProgress.compareAndSet(false, true)) {
                val err = "boot() already in progress for slot $slot"
                DomainLog.w(TAG, err)
                onError(err)
                return Result.failure(IllegalStateException(err))
            }

            onProgress(0.05f)

            // ── Step 1: 处理 reload 场景（从 PLAYING 回退）──
            if (stateStore.runState.value == RunState.PLAYING ||
                stateStore.bootPhase.value >= BootPhase.BOOT_COMPLETE
            ) {
                DomainLog.i(TAG, "boot: reloading — stopping game loop and resetting boot phase")
                stopGameLoop()
                stateStore.setReloading()
                stateStore.resetBootPhase()
            }

            // ── Step 2: 确保从 UNINITIALIZED 开始 ──
            if (stateStore.bootPhase.value != BootPhase.UNINITIALIZED) {
                stateStore.resetBootPhase()
            }

            onProgress(0.10f)
            onPhase("data_load")

            // ── Step 3: 建筑修复 ──
            gameEngine.updateGameData { data ->
                val fixed = buildingConfigService.fixupBuildingSizes(data.placedBuildings)
                val withIds = GridBuildingData.ensureAllHaveInstanceId(fixed)
                if (withIds != data.placedBuildings) data.copy(placedBuildings = withIds) else data
            }

            stateStore.advanceBootPhase() // → DATA_READY
            onProgress(0.20f)

            // ── Step 4: 资源预加载 ──
            onPhase("preload")
            onPreloadResources()
            onPhase("ready")
            onProgress(0.40f)

            // ── Step 5: 弟子快照预热 + 重型数据 ──
            discipleSnapshotCache.prewarm(gameEngine.discipleTables)
            gameEngine.ensureHeavyDataLoaded()

            // ── Step 6: 启动游戏循环 ──
            onProgress(0.60f)
            startGameLoop()
            stateStore.advanceBootPhase() // → SYSTEMS_READY

            // ── Step 7: 生成地图瓦片数据 ──
            onProgress(0.80f)
            val mapData = try {
                generateMapPreloadData().also { onMapReady(it) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                DomainLog.e(TAG, "boot: map preload failed (non-fatal)", e)
                null
            }

            if (mapData != null) {
                stateStore.advanceBootPhase() // → MAP_READY
            }

            // ── Step 8: 确保到达 BOOT_COMPLETE ──
            while (stateStore.bootPhase.value < BootPhase.BOOT_COMPLETE) {
                stateStore.advanceBootPhase()
            }
            stateStore.setPlaying()

            gameStarted = true
            onProgress(1.0f)
            onSuccess()

            val gd = gameEngine.gameData.value
            DomainLog.i(TAG, "boot SUCCESS: " +
                "sectName=${gd.sectName}, year=${gd.gameYear}, month=${gd.gameMonth}, " +
                "slot=$slot, elapsed=${System.currentTimeMillis() - startTime}ms")

            return Result.success(Unit)

        } catch (e: CancellationException) {
            DomainLog.w(TAG, "boot cancelled")
            cleanupAfterCancellation()
            throw e
        } catch (e: Exception) {
            DomainLog.e(TAG, "boot FAILED: ${e.message}", e)

            if (!gameStarted) {
                val recovered = recoverWithPartialData()
                if (recovered) {
                    DomainLog.w(TAG, "boot: recovered with partial data, continuing as success")
                    gameStarted = true
                    onProgress(1.0f)
                    onSuccess()
                    return Result.success(Unit)
                }
            }

            onError(e.message ?: "启动失败")
            return Result.failure(e)
        } finally {
            bootInProgress.set(false)
        }
    }

    private fun startGameLoop() {
        gameEngineCore.startListening()
        gameEngineCore.startGameLoop()
        DomainLog.d(TAG, "Game loop started")
    }

    private fun stopGameLoop() {
        gameEngineCore.stopGameLoop()
        DomainLog.d(TAG, "Game loop stopped")
    }

    private suspend fun generateMapPreloadData(): MapPreloadData {
        val tileSize = com.xianxia.sect.core.GameConfig.SectMap.TILE_SIZE
        val worldWidthCells = com.xianxia.sect.core.GameConfig.SectMap.WORLD_WIDTH_CELLS
        val worldHeightCells = com.xianxia.sect.core.GameConfig.SectMap.WORLD_HEIGHT_CELLS

        val rawTileData = withContext(Dispatchers.Default) {
            com.xianxia.sect.core.util.SectMapTileGenerator.generateTileData(
                worldWidthCells, worldHeightCells,
                worldSeed = gameEngine.gameData.value?.mapSeed ?: 0
            )
        }
        val flatTileData = rawTileData.flatMap { it.toList() }.toIntArray()

        return MapPreloadData(
            rawTileData = rawTileData,
            worldWidthCells = worldWidthCells,
            worldHeightCells = worldHeightCells,
            tileSize = tileSize,
            worldPixelWidth = worldWidthCells * tileSize,
            worldPixelHeight = worldHeightCells * tileSize,
            flatTileData = flatTileData
        )
    }

    /**
     * 在 boot() 被取消时清理状态。
     * 停止游戏循环、重置 bootPhase，并将 runState 恢复为 IDLE。
     */
    private fun cleanupAfterCancellation() {
        if (gameEngineCore.isGameLoopRunning) {
            gameEngineCore.stopGameLoop()
        }
        stateStore.resetBootPhase()
        if (stateStore.runState.value != RunState.IDLE) {
            stateStore.setIdle()
        }
    }

    private fun recoverWithPartialData(): Boolean {
        val partialGameData = gameEngine.gameData.value
        if (partialGameData.sectName.isNotEmpty() && gameEngine.disciples.value.isNotEmpty()) {
            DomainLog.w(TAG, "boot: recovering with partial data (sect=${partialGameData.sectName})")
            try {
                startGameLoop()
                stateStore.resetBootPhase()
                while (stateStore.bootPhase.value < BootPhase.BOOT_COMPLETE) {
                    stateStore.advanceBootPhase()
                }
                stateStore.setPlaying()
                DomainLog.w(TAG, "boot: recovered with partial data")
                return true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                DomainLog.e(TAG, "boot: partial data recovery failed", e)
            }
        }
        return false
    }
}
