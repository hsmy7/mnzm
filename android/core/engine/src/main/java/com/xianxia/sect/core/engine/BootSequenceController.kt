package com.xianxia.sect.core.engine

import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.config.BuildingConfigService
import com.xianxia.sect.core.engine.domain.building.BuildingFeatureRegistry
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
    private val buildingConfigService: BuildingConfigService
) {
    companion object {
        private const val TAG = "BootSequence"

        /** 地图预加载生成重试次数（含首次尝试，共 2 次） */
        private const val MAP_GENERATE_RETRY_COUNT = 2
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
    // 启动编排大函数（多阶段/多守卫/多回调），既有结构保持
    @Suppress(
        "LongParameterList", "LongMethod", "CyclomaticComplexMethod", "ReturnCount",
        "TooGenericExceptionCaught" // 自愈隔离 catch 需捕获 Error 类
    )
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

            // ── Step 3: 建筑自愈（D-13 孤儿归属归一化 → D-11 activeSectId 净化 →
            //             D-14 fixup 尺寸修正+越界钳制 → 回填 instanceId）──
            // 2026-08-06：所有读档路径（本地/云端）收敛于 boot，归一化必须在此执行——
            // 旧档跨宗门建筑 sectId 无对应宗门 → 被 activeSectId 过滤整体排除
            // （点不中/占用缺失可叠建/不可渲染）。归一化在溢出迁移之前（迁移按 sectId
            // 分组，孤儿不先归位则重叠检测失效）；世界重生（Step 5）之前，worldMapSects
            // 为空时归一化自动跳过（防误伤），下次读档收敛。
            gameEngine.updateGameData { data ->
                val norm = normalizeOrphanBuildingSectIds(
                    data.placedBuildings, data.spiritMineSlots, data.worldMapSects
                )
                val purified = purifyStaleActiveSectId(data.activeSectId, data.worldMapSects)
                val fixed = buildingConfigService.fixupBuildingSizes(norm.buildings)
                val withIds = GridBuildingData.ensureAllHaveInstanceId(fixed)
                if (withIds != data.placedBuildings || purified != data.activeSectId ||
                    norm.spiritMineSlots != data.spiritMineSlots
                ) {
                    data.copy(
                        placedBuildings = withIds,
                        activeSectId = purified,
                        spiritMineSlots = norm.spiritMineSlots
                    )
                } else {
                    data
                }
            }

            // ── Step 3.5: 溢出迁移（占地×2 后放不下的旧建筑拆除全额退款）──
            // 2026-08-06 从 SaveLoadLoadDelegate 归位：此前在 loadData 后、fixup 前执行
            // （SaveLoadViewModel:687），顺序反转导致用旧尺寸判定；云端读档路径此前缺失。
            migrateOverflowBuildings()
            // 移除3格边界树木区域内的旧存档建筑（返还一半造价）
            migrateBorderZoneBuildings()

            stateStore.advanceBootPhase() // → DATA_READY
            onProgress(0.20f)

            // ── Step 4: 资源预加载 ──
            onPhase("preload")
            onPreloadResources()
            onPhase("ready")
            onProgress(0.40f)

            // ── Step 5: 重型数据 + 数据完整性守卫 ──
            gameEngine.ensureHeavyDataLoaded()
            gameEngine.ensureGameDataIntegrity()

            // ── Step 6: 重建分配注册表（读档后同步 Gate 状态）──
            onProgress(0.50f)
            gameEngine.assignmentGate.rebuildFromGameData(
                gameData = gameEngine.gameDataSnapshot,
                productionSlots = try {
                    gameEngine.productionCoordinator.repository.getSlots()
                } catch (_: Exception) {
                    emptyList()
                }
            )

            // ── Step 6.3: 双槽位自愈（旧档"同一弟子多槽位"残留清理）──
            // 清理后 gate 二次重建，健康存档零副作用
            // D23（2026-08-05）：boot 等待自愈完成——此前 fire-and-forget，
            // Step 7 startGameLoop 可能先于自愈执行，窗口期内 assignmentGate
            // 仍是含重复注册的旧状态
            // 对抗性审查修复（2026-08-06）：join 失败（Error 类，如 OOM/栈溢出）
            // 不得传播进 boot——自愈是尽力而为的清理步骤，不决定读档成败
            try {
                gameEngine.healDuplicateSlotAssignments()?.join()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                // 对抗性审查修复：自愈是尽力而为的清理步骤，Error 类（OOM/栈溢出）
                // 也不阻断启动——join 传播的失败在此隔离
                DomainLog.e(TAG, "双槽位自愈失败（不阻断启动）", e)
            }

            // ── Step 6.4: 旧档资质自愈（资质=50 未生成哨兵 → 按灵根数重算）──
            // 2026-08-12 悟性重设计：资质为新增固定基础属性，旧档（Migration/云存档）
            // aptitude 为默认 50。healDefaultAptitudes 在写作用域内按灵根阶梯 + id
            // 散列确定性补算（幂等，同一 id 结果稳定），结果随下一次存档持久化。
            // 尽力而为：自愈失败不阻断启动（仿 Step 6.3 双槽位自愈隔离风格）。
            healDefaultAptitudesSafely()

            // ── Step 6.5: 仓库堆叠整理（修复旧档散落问题）──
            gameEngine.consolidateStacks()

            // ── Step 7: 启动游戏循环 ──
            onProgress(0.60f)
            startGameLoop()
            stateStore.advanceBootPhase() // → SYSTEMS_READY

            // ── Step 7: 生成地图瓦片数据 ──
            onProgress(0.80f)
            val mapData = generateMapDataSafely()
            if (mapData == null) {
                // 2026-08-04 修复：地图生成失败 = 硬失败——原实现静默继续推进到
                // BOOT_COMPLETE + setPlaying，但 onMapReady 从未调用 → UI 侧
                // mapPreloadData 为 null → 永久 LoadingScreen（"读档成功但无法游玩"）
                DomainLog.e(TAG, "boot: map generation failed, aborting boot")
                cleanupAfterBootFailure()
                onError("地图数据生成失败，请重新进入")
                return Result.failure(IllegalStateException("Map generation failed"))
            }
            onMapReady(mapData)
            stateStore.advanceBootPhase() // → MAP_READY

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
                val recovered = recoverWithPartialData(onMapReady)
                if (recovered) {
                    DomainLog.w(TAG, "boot: recovered with partial data, continuing as success")
                    gameStarted = true
                    onProgress(1.0f)
                    onSuccess()
                    return Result.success(Unit)
                }
                // 2026-08-04 修复：恢复失败时清理状态——原实现直接 onError，
                // 若异常发生在循环启动后，游戏循环仍在跑（时间推进但 boot 失败），
                // 状态不一致（点击按钮无效 / 界面与状态脱节）
                cleanupAfterBootFailure()
            }

            onError(e.message ?: "启动失败")
            return Result.failure(e)
        } finally {
            bootInProgress.set(false)
        }
    }

    private fun startGameLoop() {
        gameEngineCore.startGameLoop()
        DomainLog.d(TAG, "Game loop started")
    }

    private fun stopGameLoop() {
        gameEngineCore.stopGameLoop()
        DomainLog.d(TAG, "Game loop stopped")
    }

    /**
     * 旧档资质自愈（隔离 try/catch，避免 boot() 抛语句超限）。
     * 尽力而为：失败仅记录日志，不阻断启动。
     *
     * 自愈补算资质后同步重锚全量修炼检查点（资质影响修炼速率，
     * 不重锚则旧档首次加载的修炼进度投影按旧速率虚高）。
     */
    private suspend fun healDefaultAptitudesSafely() {
        try {
            gameEngine.updateGameData { data ->
                val healedCount = gameEngine.discipleTables.healDefaultAptitudes()
                if (healedCount > 0) {
                    val currentMonth = data.gameYear * 12 + data.gameMonth
                    gameEngine.discipleTables.checkpointAllDisciples(currentMonth)
                    DomainLog.i(
                        TAG,
                        "资质自愈：$healedCount 名弟子按灵根数补算资质，修炼检查点已重锚"
                    )
                }
                data
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            DomainLog.e(TAG, "资质自愈失败（不阻断启动）", e)
        }
    }

    /**
     * 安全生成地图预加载数据（带一次重试）。
     * 生成失败由调用方决定语义（主路径硬失败 / 恢复路径放弃恢复）。
     *
     * @return 地图预加载数据；重试后仍失败返回 null
     */
    private suspend fun generateMapDataSafely(): MapPreloadData? {
        var attempts = 0
        while (attempts < MAP_GENERATE_RETRY_COUNT) {
            attempts++
            try {
                return generateMapPreloadData()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                DomainLog.e(TAG, "boot: map preload failed (attempt $attempts/$MAP_GENERATE_RETRY_COUNT)", e)
            }
        }
        return null
    }

    /** boot 失败后的状态清理：停循环 + 复位生命周期（与取消清理同语义）。 */
    private fun cleanupAfterBootFailure() {
        if (gameEngineCore.isGameLoopRunning) {
            gameEngineCore.stopGameLoop()
        }
        stateStore.resetBootPhase()
        if (stateStore.runState.value != RunState.IDLE) {
            stateStore.setIdle()
        }
    }

    private suspend fun generateMapPreloadData(): MapPreloadData {
        val tileSize = com.xianxia.sect.core.GameConfig.SectMap.TILE_SIZE
        val worldWidthCells = com.xianxia.sect.core.GameConfig.SectMap.WORLD_WIDTH_CELLS
        val worldHeightCells = com.xianxia.sect.core.GameConfig.SectMap.WORLD_HEIGHT_CELLS

        val rawTileData = withContext(Dispatchers.Default) {
            com.xianxia.sect.core.util.SectMapTileGenerator.generateTileData(
                worldWidthCells, worldHeightCells,
                worldSeed = gameEngine.gameData.value?.mapSeed ?: 0,
                borderTreeRing = com.xianxia.sect.core.GameConfig.SectMap.BORDER_TREE_RING
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
     * 迁移：移除3格边界树木区域内的旧存档建筑，返还一半造价。
     *
     * BORDER_TREE_RING 特性上线后，旧存档中可能已有建筑位于边界3格内。
     * 这些建筑显示在树木层之上但无法交互（无法移动/新建到边界内），
     * 因此视同拆除处理：移除建筑 + 清理关联槽位 + 返还 50% 造价 + 释放弟子。
     *
     * 在 Step 3 建筑修复后、游戏循环启动前执行，保证迁移是原子且安全的。
     */
    /**
     * 溢出迁移：将占地×2 时代（或归一化并入本宗）放不下的建筑拆除，全额返还灵石，弟子恢复空闲。
     *
     * 2026-08-06 从 SaveLoadLoadDelegate 迁入 boot 编排：此前在 loadData 后、fixup 前执行
     * （SaveLoadViewModel:687），顺序反转导致用旧尺寸判定；云端读档路径此前缺失此迁移。
     * 必须在 Step 3（归一化 + fixup+钳制）之后执行——归一化后并入本宗的孤儿建筑与既有
     * 建筑重叠时由此拆除退款；按 sectId 分组（不同宗门的建筑使用独立网格，坐标互不干扰）。
     */
    private suspend fun migrateOverflowBuildings() {
        val gd = gameEngine.gameDataSnapshot
        val allBuildings = gd.placedBuildings
        if (allBuildings.isEmpty()) return

        val buildingsBySect = allBuildings.groupBy { it.sectId }
        val allKept = mutableListOf<GridBuildingData>()
        var totalRefund = 0L
        val allFreedDiscipleIds = mutableSetOf<String>()

        for ((_, sectBuildings) in buildingsBySect) {
            val result = computeBuildingOverflowMigration(
                buildings = sectBuildings,
                gameData = gd,
                buildingConfigService = buildingConfigService
            )
            allKept.addAll(result.kept)
            totalRefund += result.totalRefund
            allFreedDiscipleIds.addAll(result.freedDiscipleIds)
        }

        if (allKept.size == allBuildings.size) return  // 无建筑被拆除

        DomainLog.i(TAG, "旧存档建筑占地迁移：${allBuildings.size - allKept.size} 座建筑因空间不足被拆除，" +
            "返还灵石×$totalRefund，解放弟子 ${allFreedDiscipleIds.size} 人")

        gameEngine.applyBuildingMigrationOnEngine(
            kept = allKept,
            totalRefund = totalRefund,
            freedDiscipleIds = allFreedDiscipleIds
        )
    }

    private suspend fun migrateBorderZoneBuildings() {
        val border = GameConfig.SectMap.BORDER_TREE_RING
        val w = GameConfig.SectMap.WORLD_WIDTH_CELLS
        val h = GameConfig.SectMap.WORLD_HEIGHT_CELLS

        val buildings = gameEngine.gameDataSnapshot.placedBuildings
        val inBorder = buildings.filter { b ->
            b.gridX < border || b.gridY < border ||
                b.gridX + b.width > w - border || b.gridY + b.height > h - border
        }
        if (inBorder.isEmpty()) return

        val removedIds = inBorder.map { it.instanceId }.toSet()
        val removedNames = inBorder.map { it.displayName }

        var totalRefund = 0L
        val discipleIdsToFree = mutableSetOf<String>()

        // 第一遍：计算退款 + 收集待释放弟子
        for (building in inBorder) {
            val config = buildingConfigService.getBuildingConfigByDisplayName(building.displayName)
            val cost = config?.cost ?: 1000L
            totalRefund += cost / 2

            val feature = BuildingFeatureRegistry.findByDisplayName(building.displayName)
            if (feature != null) {
                for (group in feature.slotGroups) {
                    discipleIdsToFree.addAll(
                        group.collectDiscipleIds(gameEngine.gameDataSnapshot, building.instanceId, feature)
                    )
                }
            }
        }

        // 第二遍：原子化更新游戏数据
        stateStore.update {
            var gd = gameData.copy(
                placedBuildings = gameData.placedBuildings.filter { it.instanceId !in removedIds },
                spiritStones = gameData.spiritStones + totalRefund
            )
            for (building in inBorder) {
                val feature = BuildingFeatureRegistry.findByDisplayName(building.displayName)
                if (feature != null) {
                    for (group in feature.slotGroups) {
                        gd = group.filterFromGameData(gd, building.instanceId, feature)
                    }
                }
            }
            gameData = gd
        }

        DomainLog.w(TAG, "迁移边界建筑: 拆除了 ${inBorder.size} 座 (${removedNames.joinToString(", ")}), " +
            "返还灵石×$totalRefund, 释放弟子 ${discipleIdsToFree.size} 人")
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

    @Suppress("ReturnCount") // 恢复路径多失败守卫，多 return 为守卫风格
    private suspend fun recoverWithPartialData(onMapReady: (MapPreloadData) -> Unit): Boolean {
        val partialGameData = gameEngine.gameData.value
        if (partialGameData.sectName.isEmpty() || gameEngine.disciples.value.isEmpty()) {
            return false
        }
        DomainLog.w(TAG, "boot: recovering with partial data (sect=${partialGameData.sectName})")

        // T15（2026-08-05）：恢复前补齐主路径 Step 5/6/6.5 完整性守卫——
        // 半初始化状态（重数据未加载/Gate 未重建）禁止进入 PLAYING。
        // 守卫失败则放弃恢复，走既有 onError 流程（比半初始化进游戏安全）。
        try {
            gameEngine.ensureHeavyDataLoaded()
            gameEngine.ensureGameDataIntegrity()
            gameEngine.assignmentGate.rebuildFromGameData(
                gameData = gameEngine.gameDataSnapshot,
                productionSlots = try {
                    gameEngine.productionCoordinator.repository.getSlots()
                } catch (_: Exception) {
                    emptyList()
                }
            )
            gameEngine.consolidateStacks()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            DomainLog.e(TAG, "boot: partial recovery guards failed, aborting recovery", e)
            return false
        }

        val mapData = try {
            generateMapDataSafely()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            DomainLog.e(TAG, "boot: partial data recovery failed", e)
            return false
        }
        if (mapData == null) {
            // 地图生成失败 → 放弃恢复（循环尚未启动，无需 stopGameLoop）
            DomainLog.e(TAG, "boot: partial recovery aborted (map generation failed)")
            return false
        }
        // 2026-08-04 修复：恢复路径必须先产出地图数据并回调 onMapReady——
        // 原实现不回调，UI 侧 mapPreloadData 为 null → 永久 LoadingScreen
        onMapReady(mapData)
        startGameLoop()
        stateStore.resetBootPhase()
        while (stateStore.bootPhase.value < BootPhase.BOOT_COMPLETE) {
            stateStore.advanceBootPhase()
        }
        stateStore.setPlaying()
        DomainLog.w(TAG, "boot: recovered with partial data")
        return true
    }
}
