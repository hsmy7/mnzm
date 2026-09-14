package com.xianxia.sect.core.engine

import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.config.BuildingConfigService
import com.xianxia.sect.core.engine.domain.building.BuildingFeatureRegistry
import com.xianxia.sect.core.engine.service.MailService
import com.xianxia.sect.core.engine.service.TIANSHU_COMPENSATION_SPIRIT_STONES
import com.xianxia.sect.core.engine.service.buildTianshuCompensationMail
import com.xianxia.sect.core.model.GridBuildingData
import com.xianxia.sect.core.model.MapPreloadData
import com.xianxia.sect.core.state.BootPhase
import com.xianxia.sect.core.state.GameStateStore
import com.xianxia.sect.core.state.RunState
import com.xianxia.sect.core.util.DomainLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
    private val mailService: MailService
) {
    companion object {
        private const val TAG = "BootSequence"

        /** 地图预加载生成重试次数（含首次尝试，共 2 次） */
        private const val MAP_GENERATE_RETRY_COUNT = 2
    }

    /**
     * 启动流程是否正在进行（只读状态）。
     *
     * 作为入口层统一互斥信号——SaveLoadViewModel 所有
     * 会触发 [boot] 的入口（新游戏/读档/重启/云读档/云下载）前置检查此状态，
     * 防止"云存档操作进行中（不设 isLoading，互斥依赖 cloudDownloadLock）而
     * 其他入口不查该锁"的守卫不对称窗口导致并发 boot。UI 层亦可监听此状态
     * 禁用相关按钮（防御性增强）。内部仍保留拒绝式原子保护作为最后防线。
     */
    val bootInProgress: kotlinx.coroutines.flow.StateFlow<Boolean> get() = _bootInProgress

    /** 重入保护：防止 boot() 被并发调用（暴露为 [bootInProgress] 只读状态） */
    private val _bootInProgress = kotlinx.coroutines.flow.MutableStateFlow(false)

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
    // 统一启动入口（多回调签名）。主体在 [bootCore]（引擎上下文执行体）。
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
        // 线程契约：boot 整体在引擎线程执行——引擎线程是状态权威写线程（无锁单线程
        // 模型），stateStore.update 与 native 转发必须同线程串行；boot 期间游戏循环
        // 已停/未启动（Step 1 stopGameLoop），引擎线程空闲，无阻塞风险。
        // 测试环境经 FakeEngineContextDispatcher 注入（BootSequenceControllerTest stub）。
        return gameEngine.engineContextDispatcher.withEngineContext {
            bootCore(slot, onPreloadResources, onProgress, onPhase, onMapReady, onSuccess, onError)
        }
    }

    /**
     * 启动主体（[boot] 的引擎上下文执行体）。
     *
     * 步进/自愈/迁移/循环启动编排；本函数内可安全执行 stateStore.update 与 native
     * 转发（全部位于引擎线程）。
     */
    // 启动编排大函数（多阶段/多守卫/多回调），既有结构保持
    @Suppress(
        "LongParameterList", "LongMethod", "CyclomaticComplexMethod", "ReturnCount",
        "TooGenericExceptionCaught", // 自愈隔离 catch 需捕获 Error 类
        "ThrowsCount" // 各阶段取消穿透 rethrow 刻意独立抛出(结构化取消语义), 非疏忽超标
    )
    private suspend fun bootCore(
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
            if (!_bootInProgress.compareAndSet(false, true)) {
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

            // ── Step 3: 建筑自愈（孤儿归属归一化 → activeSectId 净化 →
            //             fixup 尺寸修正+越界钳制 → 回填 instanceId）──
            // 所有读档路径（本地/云端）收敛于 boot，归一化必须在此执行——
            // 旧档跨宗门建筑 sectId 无对应宗门 → 被 activeSectId 过滤整体排除
            // （点不中/占用缺失可叠建/不可渲染）。归一化在溢出迁移之前（迁移按 sectId
            // 分组，孤儿不先归位则重叠检测失效）；世界重生（Step 5）之前，worldMapSects
            // 为空时归一化自动跳过（防误伤），下次读档收敛。
            var legacyTianshuHalls: List<GridBuildingData> = emptyList()
            gameEngine.updateGameData { data ->
                // 问题1 选项2：推导"玩家持有（占领）宗门"权威集合（sectDetails.isOwned ∪ isPlayerOccupied），
                // 归一化/净化以其为准——被占宗门缺失于 roster 时保留归属而非误归主宗。
                val playerOwnedSectIds = derivePlayerOwnedSectIds(data.sectDetails, data.worldMapSects)
                val norm = normalizeOrphanBuildingSectIds(
                    data.placedBuildings, data.spiritMineSlots, data.worldMapSects, playerOwnedSectIds
                )
                val purified = purifyStaleActiveSectId(data.activeSectId, data.worldMapSects, playerOwnedSectIds)
                // 存量存档回填：老档占领状态目前仅存于 worldMapSects.isPlayerOccupied（会被世界重生清除），
                // 补标 sectDetails.isOwned（持久化、不随重生清除），保证未来重生死后占领进度不丢。
                val backfilledSectDetails = backfillPlayerOwnedSectDetails(data.sectDetails, data.worldMapSects)
                // 旧档遗留天枢殿识别（占地尺寸命中历史白名单 TIANSHU_LEGACY_FOOTPRINTS）
                // ——必须在 fixup 之前判定（fixup 会把尺寸统一修正为当前配置，先判定才能识别
                // 旧档遗留）；删除 + 补偿邮件（1000 万灵石）由 Step 3.1 编排（先发邮件成功再删建筑）。
                // 注：判据是**历史尺寸白名单**而非"≠ 当前配置"——后者会让任何配置尺寸调整变成
                // 全服拆殿事故（详见 BuildingLoadSelfHeal.TIANSHU_LEGACY_FOOTPRINTS KDoc）
                legacyTianshuHalls = filterLegacyTianshuHalls(norm.buildings)
                val fixed = buildingConfigService.fixupBuildingSizes(norm.buildings)
                val withIds = GridBuildingData.ensureAllHaveInstanceId(fixed)
                // 住所显示名分级前缀迁移（旧档「单人住所/多人住所」→「初级…」，
                // 含引导累计建造计数 key 同步），幂等
                val (renamedBuildings, renamedCounters) =
                    normalizeResidenceDisplayNames(withIds, data.guideCounters)
                val buildingsChanged = withIds != data.placedBuildings
                val activeSectChanged = purified != data.activeSectId
                val mineSlotsChanged = norm.spiritMineSlots != data.spiritMineSlots
                val namesRenamed = renamedBuildings != withIds
                val countersChanged = renamedCounters != data.guideCounters
                val sectDetailsChanged = backfilledSectDetails != data.sectDetails
                val anySelfHealChanged = buildingsChanged || activeSectChanged || mineSlotsChanged ||
                    namesRenamed || countersChanged || sectDetailsChanged
                if (anySelfHealChanged) {
                    data.copy(
                        placedBuildings = renamedBuildings,
                        activeSectId = purified,
                        spiritMineSlots = norm.spiritMineSlots,
                        guideCounters = renamedCounters,
                        sectDetails = backfilledSectDetails
                    )
                } else {
                    data
                }
            }

            // ── Step 3.1: 旧档天枢殿删除 + 补偿邮件 ──
            migrateLegacyTianshuHalls(legacyTianshuHalls, slot)

            // ── Step 3.5: 溢出迁移（旧档放不下的建筑拆除全额退款）──
            migrateOverflowBuildings()
            // 移除3格边界树木区域内的旧存档建筑（返还一半造价）
            migrateBorderZoneBuildings()

            // ── Step 3.6: 引导累计建造计数回填（旧档无计数，按最终存量回填）──
            // 回填后建筑升级/拆除不再回退引导建造进度（max 语义幂等，健康档零副作用）
            gameEngine.backfillBuildingGuideCounters()

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
                } catch (e: CancellationException) {
                    throw e // 取消穿透: 取消时上抛中止 boot, 不以空槽位重建 Gate(状态错乱)
                } catch (_: Exception) {
                    emptyList()
                }
            )

            // ── Step 6.3: 双槽位自愈（旧档"同一弟子多槽位"残留清理）──
            // 清理后 gate 二次重建，健康存档零副作用。
            // boot 等待自愈完成（join）——否则 Step 7 startGameLoop 可能先于自愈
            // 执行，窗口期内 assignmentGate 仍是含重复注册的旧状态。
            // join 失败（Error 类，如 OOM/栈溢出）不得传播进 boot——自愈是
            // 尽力而为的清理步骤，不决定读档成败
            try {
                gameEngine.healDuplicateSlotAssignments()?.join()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                // 自愈是尽力而为的清理步骤，Error 类（OOM/栈溢出）
                // 也不阻断启动——join 传播的失败在此隔离
                DomainLog.e(TAG, "双槽位自愈失败（不阻断启动）", e)
            }

            // ── Step 6.4: 旧档资质自愈（资质=50 未生成哨兵 → 按灵根数重算）──
            // 资质为固定基础属性；旧档（Migration/云存档）
            // aptitude 为默认 50。healDefaultAptitudes 在写作用域内按灵根阶梯 + id
            // 散列确定性补算（幂等，同一 id 结果稳定），结果随下一次存档持久化。
            // 尽力而为：自愈失败不阻断启动（隔离方式同 Step 6.3）。
            healDefaultAptitudesSafely()

            // ── Step 6.5: 仓库堆叠整理（旧档散落堆叠归并）──
            gameEngine.consolidateStacks()

            // ── Step 7: 启动游戏循环 ──
            onProgress(0.60f)
            startGameLoop()
            stateStore.advanceBootPhase() // → SYSTEMS_READY

            // ── Step 7: 生成地图瓦片数据 ──
            onProgress(0.80f)
            val mapData = generateMapDataSafely()
            if (mapData == null) {
                // 地图生成失败 = 硬失败——静默继续会推进到
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
                // 恢复失败时必须清理状态——若异常发生在循环启动后仍直接 onError，
                // 游戏循环仍在跑（时间推进但 boot 失败），
                // 状态不一致（点击按钮无效 / 界面与状态脱节）
                cleanupAfterBootFailure()
            }

            onError(e.message ?: "启动失败")
            return Result.failure(e)
        } finally {
            _bootInProgress.value = false
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
    @Suppress("TooGenericExceptionCaught") // 防御兜底: 异常源跨IO/SDK不可枚举, 降级继续+日志留痕, 非静默吞噬
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
    @Suppress("TooGenericExceptionCaught") // 防御兜底: 异常源跨IO/SDK不可枚举, 降级继续+日志留痕, 非静默吞噬
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
        val mapSeed = gameEngine.gameData.value?.mapSeed ?: 0

        // 地图冻结（WS-5b）：**存的地形恒优先**——已回填的权威地形段直接采用
        //（跨版本冻结，不重算）；无段（新档/老档未回填）才走生成路径，并经
        // ensureSectTerrainBackfilled 回填 GameData（此后地图冻结）。
        val authoritative = gameEngine.gameData.value
            ?.takeIf { it.mapSeed != 0 }
            ?.terrainTiles
            ?.takeIf { it.isNotEmpty() }
            ?.toIntArray()
        val flatTileData = authoritative ?: withContext(Dispatchers.Default) {
            // 生成真源在 C++（SectTerrainBridge native 优先 + Kotlin 降级），
            // 数据展平为唯一的一维瓦片表示。
            com.xianxia.sect.core.util.SectTerrainBridge.generateFlatTileData(
                worldWidthCells = worldWidthCells,
                worldHeightCells = worldHeightCells,
                worldSeed = mapSeed,
                borderTreeRing = com.xianxia.sect.core.GameConfig.SectMap.BORDER_TREE_RING
            )
        }
        // 无段 ⇒ 生成即数据：回填 GameData（幂等；已有段时零写入）
        if (authoritative == null && mapSeed != 0) {
            gameEngine.ensureSectTerrainBackfilled(flatTileData)
        }

        return MapPreloadData(
            flatTileData = flatTileData,
            worldWidthCells = worldWidthCells,
            worldHeightCells = worldHeightCells,
            tileSize = tileSize,
            worldPixelWidth = worldWidthCells * tileSize,
            worldPixelHeight = worldHeightCells * tileSize,
            seed = mapSeed
        )
    }

    /**
     * 旧档遗留天枢殿删除 + 补偿邮件。
     *
     * 旧档遗留的天枢殿（占地尺寸命中历史白名单 [TIANSHU_LEGACY_FOOTPRINTS]，由
     * [filterLegacyTianshuHalls] 在 fixup 前识别）直接删除，通过邮件补偿玩家 1000 万灵石。
     *
     * **先发邮件成功、再删建筑**：邮件插入失败时保留建筑（已被 Step 3 fixup 修正为
     * 当前尺寸，下次读档不再触发），杜绝"删了没补偿"的资产丢失。天枢殿为全局唯一
     * 建筑（全图最多 1 座），删除按显示名匹配即安全。
     *
     * @param legacy 旧档遗留天枢殿列表（Step 3 识别、fixup 修正前的原始数据）
     * @param slot 当前存档槽位（补偿邮件落位）
     */
    private suspend fun migrateLegacyTianshuHalls(legacy: List<GridBuildingData>, slot: Int) {
        if (legacy.isEmpty()) return
        try {
            mailService.insertMail(buildTianshuCompensationMail(slot))
        } catch (e: CancellationException) {
            throw e
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            // 邮件插入失败不删建筑（无补偿不删除）；建筑已被 fixup 成当前尺寸，下次读档不重发
            DomainLog.e(TAG, "天枢殿补偿邮件插入失败 slot=$slot（保留建筑，不发放补偿）", e)
            return
        }
        stateStore.update {
            var gd = gameData.copy(
                placedBuildings = gameData.placedBuildings
                    .filterNot { it.displayName == TIANSHU_HALL_DISPLAY_NAME }
            )
            // 槽位清理：天枢殿唯一（全局唯一建筑），ElderPositions 职务（副宗主/招募长老）清空回归空闲
            for (b in legacy) {
                val feature = BuildingFeatureRegistry.findByDisplayName(b.displayName)
                if (feature != null) {
                    for (group in feature.slotGroups) {
                        gd = group.filterFromGameData(gd, b.instanceId, feature)
                    }
                }
            }
            gameData = gd
        }
        DomainLog.w(TAG, "旧档天枢殿已删除（尺寸变更补偿）：${legacy.size} 座，" +
            "补偿邮件已发 灵石×$TIANSHU_COMPENSATION_SPIRIT_STONES，slot=$slot")
    }

    /**
     * 溢出迁移：将旧档中按当前占地尺寸放不下（越界/重叠）的建筑拆除，全额返还灵石，弟子恢复空闲。
     *
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

    /**
     * 边界区域迁移：移除3格边界树木区域内的旧存档建筑，返还一半造价。
     *
     * 旧存档中可能已有建筑位于边界树木环（BORDER_TREE_RING）内。
     * 这些建筑显示在树木层之上但无法交互（无法移动/新建到边界内），
     * 因此视同拆除处理：移除建筑 + 清理关联槽位 + 返还 50% 造价 + 释放弟子。
     *
     * 在 Step 3 建筑修复后、游戏循环启动前执行，保证迁移是原子且安全的。
     */
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

    // 防御兜底: 异常源跨IO/SDK不可枚举, 降级继续+日志留痕, 非静默吞噬
    @Suppress("TooGenericExceptionCaught", "ReturnCount", "ThrowsCount") // 多失败守卫 return 守卫风格;
    // 恢复守卫段取消穿透 rethrow 刻意独立抛出(结构化取消语义), 非疏忽超标
    private suspend fun recoverWithPartialData(onMapReady: (MapPreloadData) -> Unit): Boolean {
        val partialGameData = gameEngine.gameData.value
        if (partialGameData.sectName.isEmpty() || gameEngine.disciples.value.isEmpty()) {
            return false
        }
        DomainLog.w(TAG, "boot: recovering with partial data (sect=${partialGameData.sectName})")

        // 恢复前补齐主路径 Step 5/6/6.5 完整性守卫——
        // 半初始化状态（重数据未加载/Gate 未重建）禁止进入 PLAYING。
        // 守卫失败则放弃恢复，走 onError 流程（比半初始化进游戏安全）。
        try {
            gameEngine.ensureHeavyDataLoaded()
            gameEngine.ensureGameDataIntegrity()
            gameEngine.assignmentGate.rebuildFromGameData(
                gameData = gameEngine.gameDataSnapshot,
                productionSlots = try {
                    gameEngine.productionCoordinator.repository.getSlots()
                } catch (e: CancellationException) {
                    throw e // 取消穿透: 取消时上抛外层 CE 分支中止恢复, 不以空槽位重建 Gate(状态错乱)
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
        // 恢复路径必须先产出地图数据并回调 onMapReady——
        // 否则 UI 侧 mapPreloadData 为 null → 永久 LoadingScreen
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
