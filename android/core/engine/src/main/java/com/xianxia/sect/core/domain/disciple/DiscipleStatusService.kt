package com.xianxia.sect.core.engine.domain.disciple

import com.xianxia.sect.core.engine.service.SecretRealmService
import com.xianxia.sect.core.model.CaveExplorationStatus
import com.xianxia.sect.core.model.DiscipleStatus
import com.xianxia.sect.core.model.ElderSlots
import com.xianxia.sect.core.model.GameData
import com.xianxia.sect.core.model.MANAGING_FALLBACK
import com.xianxia.sect.core.model.POSITION_NAME_KEY
import com.xianxia.sect.core.model.GarrisonSlot
import com.xianxia.sect.core.model.mining
import com.xianxia.sect.core.model.spiritPlanting
import com.xianxia.sect.core.state.DiscipleTables
import com.xianxia.sect.core.state.GameStateStore
import com.xianxia.sect.core.state.MutableGameState
import javax.inject.Inject
import javax.inject.Singleton



/**
 * 弟子状态同步服务。
 *
 * ## 职责
 * 1. **[syncAllDiscipleStatuses]** — 根据所有槽位分配推导并同步弟子状态
 * 2. **[resetAllDisciplesStatus]** — 重置所有弟子为 IDLE 状态（清除槽位）
 */
@Singleton
class DiscipleStatusService @Inject constructor(
    private val stateStore: GameStateStore,
    private val discipleLifecycleManager: DiscipleLifecycleManager,
    private val secretRealmService: SecretRealmService
) {
    companion object {
        /** 活跃洞穴探索队伍状态集合（internal 供 ProductionProcessor.buildOccupiedSlotDiscipleIds 复用，单一来源防集合漂移） */
        internal val caveExplorationStatuses = setOf(
            CaveExplorationStatus.TRAVELING, CaveExplorationStatus.EXPLORING
        )

        /**
         * 纯函数：根据弟子当前分配状态推导正确的 [DiscipleStatus]。
         *
         * 这是 [syncAllDiscipleStatuses] 的推导逻辑的纯函数版本，
         * 不访问 GameStateStore，无副作用，可独立测试。
         *
         * 优先级顺序（匹配 syncAllDiscipleStatuses 的 when 链）：
         * 死亡 → 活跃任务（ON_MISSION 从 activeMissions 推导，非无条件保护）
         * → 受保护状态（REFLECTING/REFINING）→ 远古秘境 → 仓库驻守 → 据点驻守 →
         * 队伍 → 执法 → 传道 → 执事 → 管理 → 学习 → 采矿 → 巡视 → 炼丹 → 锻造 →
         * 灵植 → 空闲
         *
         * 注：ON_MISSION 不再是无条件受保护状态，而是通过 [hasActiveMission] 参数
         * 从实际数据推导。这修复了旧存档中任务已移除但弟子卡在 ON_MISSION 的问题。
         *
         * @param isAlive 是否存活（死亡直接返回 DEAD）
         * @param currentStatus 当前状态（仅 REFLECTING/REFINING 受保护）
         * @param slotFlags 槽位归属标志，见 [SlotFlags]
         * @param hasActiveMission 弟子是否有活跃任务（来自 gameData.activeMissions）
         * @return 推导出的正确状态
         */
        fun deriveDiscipleStatus(
            isAlive: Boolean,
            currentStatus: DiscipleStatus,
            slotFlags: SlotFlags = SlotFlags(),
            hasActiveMission: Boolean = false
        ): DiscipleStatus = when {
            !isAlive -> DiscipleStatus.DEAD
            hasActiveMission -> DiscipleStatus.ON_MISSION
            currentStatus == DiscipleStatus.REFLECTING -> DiscipleStatus.REFLECTING
            currentStatus == DiscipleStatus.REFINING -> DiscipleStatus.REFINING
            slotFlags.inSecretRealm -> DiscipleStatus.SECRET_REALM
            slotFlags.inWarehouseGarrison -> DiscipleStatus.WAREHOUSE_GARRISON
            slotFlags.inGarrison -> DiscipleStatus.GARRISONING
            slotFlags.inTeam -> DiscipleStatus.IN_TEAM
            slotFlags.lawEnforcing -> DiscipleStatus.LAW_ENFORCING
            slotFlags.preaching -> DiscipleStatus.PREACHING
            slotFlags.deaconing -> DiscipleStatus.DEACONING
            slotFlags.managing -> DiscipleStatus.MANAGING
            slotFlags.studying -> DiscipleStatus.STUDYING
            slotFlags.mining -> DiscipleStatus.MINING
            slotFlags.patrolling -> DiscipleStatus.PATROLLING
            slotFlags.alchemy -> DiscipleStatus.ALCHEMY
            slotFlags.forge -> DiscipleStatus.FORGE
            slotFlags.spiritPlanting -> DiscipleStatus.SPIRIT_PLANTING
            else -> DiscipleStatus.IDLE
        }

        /**
         * 纯函数：为单个弟子构建 [SlotFlags]，用于 [deriveDiscipleStatus]。
         *
         * @param discipleId 弟子 ID
         * @param data 当前游戏数据快照
         * @return 该弟子的槽位归属标志
         */
        fun buildSlotFlagsFor(
            discipleId: String,
            data: GameData
        ): SlotFlags {
            val team = buildTeamFlags(data, discipleId)
            val officer = buildOfficerFlags(data.elderSlots, discipleId)
            val production = buildProductionFlags(data, discipleId)
            return SlotFlags(
                inGarrison = team.inGarrison,
                inWarehouseGarrison = team.inWarehouseGarrison,
                inTeam = team.inTeam,
                inSecretRealm = team.inSecretRealm,
                lawEnforcing = officer.lawEnforcing,
                preaching = officer.preaching,
                deaconing = officer.deaconing,
                managing = buildManagingFlag(data.elderSlots, discipleId),
                studying = production.studying,
                mining = production.mining,
                patrolling = production.patrolling,
                alchemy = production.alchemy,
                forge = production.forge,
                spiritPlanting = production.spiritPlanting
            )
        }

        /**
         * 全量同步的 O(n) 集合索引：一次遍历构建所有槽位 ID 集合，循环内 O(1) 查含。
         * 与 [buildSlotFlagsFor]（单弟子 O(n)）互补——全量同步不走纯函数避免 O(n²)。
         */
        private data class SyncIndex(
            val lawEnforcerIds: Set<String>,
            val preachingIds: Set<String>,
            val deaconingIds: Set<String>,
            val managingIds: Set<String>,
            val studyingIds: Set<String>,
            val miningIds: Set<String>,
            val garrisonIds: Set<String>,
            val warehouseGarrisonIds: Set<String>,
            val inTeamIds: Set<String>,
            val secretRealmIds: Set<String>,
            val patrollingIds: Set<String>,
            val alchemyIds: Set<String>,
            val forgeIds: Set<String>,
            val plantIds: Set<String>,
            val activeMissionDiscipleIds: Set<String>
        )
    }

    /**
     * 槽位归属标志集合，表示弟子当前占用的槽位类型。
     * 使用 data class + Boolean 标志而非 Set<Enum> 以提高可读性和性能。
     */
    data class SlotFlags(
        val inGarrison: Boolean = false,
        val inWarehouseGarrison: Boolean = false,
        val inTeam: Boolean = false,
        val inSecretRealm: Boolean = false,
        val lawEnforcing: Boolean = false,
        val preaching: Boolean = false,
        val deaconing: Boolean = false,
        val managing: Boolean = false,
        val studying: Boolean = false,
        val mining: Boolean = false,
        val patrolling: Boolean = false,
        val alchemy: Boolean = false,
        val forge: Boolean = false,
        val spiritPlanting: Boolean = false
    )

    private val currentDiscipleTables: DiscipleTables
        get() = stateStore.discipleTables

    // ── 槽位收集函数 ──────────────────────────────────

    private fun buildLawEnforcerIds(elderSlots: ElderSlots): Set<String> {
        val ids = mutableSetOf<String>()
        elderSlots.lawEnforcementElder?.let { ids.add(it) }
        elderSlots.lawEnforcementDisciples.mapNotNull { it.discipleId }.forEach { ids.add(it) }
        return ids
    }

    private fun buildPreachingIds(elderSlots: ElderSlots): Set<String> {
        val ids = mutableSetOf<String>()
        elderSlots.preachingElder?.let { ids.add(it) }
        elderSlots.qingyunPreachingElder?.let { ids.add(it) }
        elderSlots.preachingMasters.mapNotNull { it.discipleId }.forEach { ids.add(it) }
        elderSlots.qingyunPreachingMasters.mapNotNull { it.discipleId }.forEach { ids.add(it) }
        return ids
    }

    private fun buildManagingIds(elderSlots: ElderSlots): Set<String> {
        val ids = mutableSetOf<String>()
        elderSlots.viceSectMaster?.let { ids.add(it) }
        elderSlots.outerElder?.let { ids.add(it) }
        elderSlots.innerElder?.let { ids.add(it) }
        elderSlots.forgeElder?.let { ids.add(it) }
        elderSlots.alchemyElder?.let { ids.add(it) }
        elderSlots.herbGardenElder?.let { ids.add(it) }
        // 回归（2026-08-10）：纳徒长老与 buildSlotFlagsFor 的 managing 分支对称补齐
        elderSlots.recruitingElder?.let { ids.add(it) }
        elderSlots.herbGardenDisciples.forEach { if (it.discipleId.isNotEmpty()) ids.add(it.discipleId) }
        elderSlots.alchemyDisciples.forEach { if (it.discipleId.isNotEmpty()) ids.add(it.discipleId) }
        elderSlots.forgeDisciples.forEach { if (it.discipleId.isNotEmpty()) ids.add(it.discipleId) }
        return ids
    }

    private fun buildMiningIds(data: GameData, tables: DiscipleTables): Set<String> =
        data.spiritMineSlots
            .mapNotNull { it.discipleId.takeIf { id -> id.isNotEmpty() } }
            .filter { id -> tables.ids.contains(id.toInt()) }
            .toSet()

    private fun fixInvalidMiningSlots(data: GameData, tables: DiscipleTables) {
        val hasInvalid = data.spiritMineSlots.any { slot ->
            slot.discipleId.isNotEmpty() &&
                !tables.ids.contains(slot.discipleId.toInt())
        }
        if (hasInvalid) {
            val fixed = data.spiritMineSlots.map { slot ->
                if (slot.discipleId.isNotEmpty() &&
                    !tables.ids.contains(slot.discipleId.toInt())
                ) slot.copy(discipleId = "", discipleName = "") else slot
            }
            stateStore.update { gameData = gameData.copy(spiritMineSlots = fixed) }
        }
    }

    private fun buildGarrisonIds(data: GameData): Set<String> {
        val ids = mutableSetOf<String>()
        data.worldMapSects.find { it.isPlayerSect }?.garrisonSlots
            ?.filter { it.discipleId.isNotEmpty() }
            ?.forEach { ids.add(it.discipleId) }
        return ids
    }

    /** 仓库驻守弟子 ID 集合（WAREHOUSE_GARRISON 数据源，与 buildSlotFlagsFor 的 inWarehouseGarrison 对称） */
    private fun buildWarehouseGarrisonIds(data: GameData): Set<String> =
        data.warehouseGarrisons
            .filter { it.discipleId.isNotEmpty() }
            .map { it.discipleId }
            .toSet()

    /** 远古秘境成员 ID 集合（SECRET_REALM 数据源，与 buildSlotFlagsFor 的 inSecretRealm 对称——
     * 对抗性审查 S5：此前仅纯函数版本含秘境成员，syncAllDiscipleStatuses 推导为 IDLE） */
    private fun buildSecretRealmIds(data: GameData): Set<String> =
        if (data.secretRealmState.exists) {
            data.secretRealmSession.members
                .filter { !it.isDead }
                .map { it.discipleId }
                .toSet()
        } else {
            emptySet()
        }

    private fun buildInTeamIds(data: GameData): MutableSet<String> {
        val ids = mutableSetOf<String>()
        data.battleTeams.flatMap { it.slots }
            .filter { it.discipleId.isNotEmpty() }
            .forEach { ids.add(it.discipleId) }
        // 洞府探索队伍成员
        ids.addAll(data.caveExplorationTeams
            .filter { it.status in caveExplorationStatuses }
            .flatMap { it.memberIds })
        return ids
    }

    // ── 公开 API ──────────────────────────────────────

    /**
     * 根据所有槽位分配同步所有存活弟子的状态。
     * 保留 REFLECTING / ON_MISSION / REFINING 不覆盖。
     *
     * 所有读取在 [stateStore.update] 事务内完成：
     * - 正常调用时：从 deepCopy 读取当前状态
     * - 重入调用时（在外部事务内）：从 [reusableMutableState] 读取，包含同一事务内前序服务的修改
     */
    fun syncAllDiscipleStatuses() {
        stateStore.update {
            val data = gameData
            val tables = discipleTables
            val index = buildSyncIndex(data, tables)

            fixInvalidMiningSlots(data, tables)

            for (id in tables.ids) {
                syncStatusFromIndex(id, tables, data, index)
            }
        }
    }

    /** 全量同步 O(n) 索引构建（syncAllDiscipleStatuses 第一步，防单函数超限拆分） */
    private fun buildSyncIndex(data: GameData, tables: DiscipleTables): SyncIndex {
        val alchemyIds = data.productionSlots
            .filter { !it.assignedDiscipleId.isNullOrEmpty() && it.buildingId == "alchemy" }
            .mapNotNull { it.assignedDiscipleId }.toSet()
        val forgeIds = data.productionSlots
            .filter { !it.assignedDiscipleId.isNullOrEmpty() && it.buildingId == "forge" }
            .mapNotNull { it.assignedDiscipleId }.toSet()
        val plantIds = data.productionSlots
            .filter { !it.assignedDiscipleId.isNullOrEmpty() && it.buildingId == "herbGarden" }
            .mapNotNull { it.assignedDiscipleId }.toSet()
        return SyncIndex(
            lawEnforcerIds = buildLawEnforcerIds(data.elderSlots),
            preachingIds = buildPreachingIds(data.elderSlots),
            deaconingIds = data.elderSlots.spiritMineDeaconDisciples
                .mapNotNull { it.discipleId }.toSet(),
            managingIds = buildManagingIds(data.elderSlots),
            studyingIds = data.librarySlots
                .mapNotNull { it.discipleId.takeIf { id -> id.isNotEmpty() } }.toSet(),
            miningIds = buildMiningIds(data, tables),
            garrisonIds = buildGarrisonIds(data),
            warehouseGarrisonIds = buildWarehouseGarrisonIds(data),
            inTeamIds = buildInTeamIds(data),
            secretRealmIds = buildSecretRealmIds(data),
            patrollingIds = data.patrolSlots
                .filter { it.discipleId.isNotEmpty() }.map { it.discipleId }.toSet(),
            alchemyIds = alchemyIds,
            forgeIds = forgeIds,
            plantIds = plantIds,
            activeMissionDiscipleIds = data.activeMissions.flatMap { it.discipleIds }.toSet()
        )
    }

    /** 全量同步循环体：单个弟子的状态推导 + 写入（含 positionName） */
    private fun syncStatusFromIndex(
        id: Int,
        tables: DiscipleTables,
        data: GameData,
        index: SyncIndex
    ) {
        if (tables.isAlive[id] != 1) return
        val status = tables.statuses[id]
        val discipleId = id.toString()
        val newStatus = deriveDiscipleStatus(
            isAlive = true,
            currentStatus = status,
            slotFlags = SlotFlags(
                inGarrison = index.garrisonIds.contains(discipleId),
                inWarehouseGarrison = index.warehouseGarrisonIds.contains(discipleId),
                inTeam = index.inTeamIds.contains(discipleId),
                inSecretRealm = index.secretRealmIds.contains(discipleId),
                lawEnforcing = index.lawEnforcerIds.contains(discipleId),
                preaching = index.preachingIds.contains(discipleId),
                deaconing = index.deaconingIds.contains(discipleId),
                managing = index.managingIds.contains(discipleId),
                studying = index.studyingIds.contains(discipleId),
                mining = index.miningIds.contains(discipleId),
                patrolling = index.patrollingIds.contains(discipleId),
                alchemy = index.alchemyIds.contains(discipleId),
                forge = index.forgeIds.contains(discipleId),
                spiritPlanting = index.plantIds.contains(discipleId)
            ),
            hasActiveMission = discipleId in index.activeMissionDiscipleIds
        )
        if (status != newStatus) {
            tables.statuses[id] = newStatus
        }
        writePositionName(data, tables, id, newStatus)
    }

    /**
     * 增量推导：只同步单个弟子的状态。
     *
     * 与 [syncAllDiscipleStatuses] 不同，此方法不扫描全部弟子，
     * 只根据当前槽位分配推导指定弟子的状态并写入。
     *
     * 用于已知哪位弟子变更的场景（分配/释放/交换等），
     * 避免不必要的 O(n) 全量扫描。
     */
    fun syncSingleDiscipleStatus(discipleId: String) {
        val id = discipleId.toIntOrNull() ?: return
        stateStore.update {
            if (id !in discipleTables.ids) return@update
            val isAlive = discipleTables.isAlive[id] == 1
            val currentStatus = discipleTables.statuses[id]
            val data = gameData

            // 检查弟子是否有活跃任务
            val hasActiveMission = data.activeMissions.any { it.discipleIds.contains(discipleId) }

            val newStatus = deriveDiscipleStatus(
                isAlive = isAlive,
                currentStatus = currentStatus,
                slotFlags = buildSlotFlagsFor(
                    discipleId = discipleId,
                    data = data
                ),
                hasActiveMission = hasActiveMission
            )

            if (currentStatus != newStatus) {
                discipleTables.statuses[id] = newStatus
            }
            writePositionName(data, discipleTables, id, newStatus)
        }
    }

    /**
     * 写入/清除 positionName 派生数据（仅 MANAGING 状态持有，UI 职位文案数据源）。
     *
     * - MANAGING：写入 [ElderSlots.resolvePositionName] 解析结果（无职位时兜底"管理中"），
     *   仅值变化时写（定向 `+`，防每旬同步刷脏 statusData 列）
     * - 非 MANAGING：定向删除既有 positionName key（`-`，保留血炼 buildingId、思过
     *   reflectionStartYear/reflectionEndYear 等既有 key——禁止整体覆盖）
     *
     * syncAll / syncSingle 双路径共用，保证卸任后职位名残留能被清除。
     *
     * statusData 派生 key 与兜底文案定义在 core/domain（DiscipleStatusData.kt 单一来源，
     * engine 推导与 UI statusText 消费共用），此处通过 import 引用。
     */
    private fun writePositionName(
        data: GameData,
        tables: DiscipleTables,
        id: Int,
        newStatus: DiscipleStatus
    ) {
        val current = tables.statusData.getOrNull(id) ?: emptyMap()
        if (newStatus == DiscipleStatus.MANAGING) {
            val resolved = data.elderSlots.resolvePositionName(id.toString()) ?: MANAGING_FALLBACK
            if (current[POSITION_NAME_KEY] != resolved) {
                tables.statusData[id] = current + (POSITION_NAME_KEY to resolved)
            }
        } else if (POSITION_NAME_KEY in current) {
            tables.statusData[id] = current - POSITION_NAME_KEY
        }
    }

    /**
     * 重置所有弟子为 IDLE 状态。
     * 保留 REFLECTING / REFINING 不受影响。
     * 清除所有槽位分配（灵脉矿/藏经阁/长老/驻守/洞府探索队伍/任务/秘境会话）。
     */
    suspend fun resetAllDisciplesStatus() {
        val protectedIds = stateStore.updateAndReturn { clearSlotsForReset() }

        // 生产槽位通过 DiscipleLifecycleManager 清理（涉及 Repository）
        discipleLifecycleManager.clearProductionSlots(protectedIds)
    }

    /**
     * 重置槽位：清除所有非受保护弟子的槽位分配，重置状态为 IDLE。
     * 返回值：受保护弟子的 ID 集合（REFLECTING/REFINING，跳过清除）。
     */
    private fun MutableGameState.clearSlotsForReset(): Set<String> {
        val ids = mutableSetOf<String>()
        for (id in discipleTables.ids) {
            val status = discipleTables.statuses[id]
            if (status == DiscipleStatus.REFLECTING || status == DiscipleStatus.REFINING) {
                ids.add(id.toString())
            }
        }

        val clearedSpiritMineSlots = gameData.spiritMineSlots.map {
            if (it.discipleId.isNotEmpty() && it.discipleId !in ids)
                it.copy(discipleId = "", discipleName = "") else it
        }
        val clearedLibrarySlots = gameData.librarySlots.map {
            if (it.discipleId.isNotEmpty() && it.discipleId !in ids)
                it.copy(discipleId = "", discipleName = "") else it
        }
        val clearedElderSlots = clearAllDisciplesFromElderSlots(gameData.elderSlots, ids)
        val clearedGarrisonSects = gameData.worldMapSects.map { sect ->
            if (sect.isPlayerSect) {
                sect.copy(
                    garrisonSlots = sect.garrisonSlots.map { slot ->
                        if (slot.discipleId.isNotEmpty() && slot.discipleId !in ids)
                            GarrisonSlot(index = slot.index)
                        else slot
                    }
                )
            } else sect
        }
        val clearedCaveTeams = gameData.caveExplorationTeams.map { team ->
            if (team.memberIds.any { it !in ids }) {
                team.copy(
                    memberIds = emptyList(), memberNames = emptyList(),
                    status = CaveExplorationStatus.COMPLETED
                )
            } else team
        }
        val clearedActiveMissions = gameData.activeMissions.filter { mission ->
            mission.discipleIds.all { it in ids }
        }
        // 回归：重置漏清巡逻/仓库驻守/战斗队伍/生产槽，重置后派生状态把残留弟子
        // 重新推导回非 IDLE，与"重置为 IDLE"语义冲突（与 DiscipleSlotCleanup 对齐）
        val clearedPatrolSlots = gameData.patrolSlots.map {
            if (it.discipleId.isNotEmpty() && it.discipleId !in ids)
                it.copy(discipleId = "", discipleName = "") else it
        }
        val clearedWarehouseGarrisons = gameData.warehouseGarrisons.map {
            if (it.discipleId.isNotEmpty() && it.discipleId !in ids)
                it.copy(discipleId = "", discipleName = "") else it
        }
        val clearedBattleTeams = gameData.battleTeams.map { team ->
            team.copy(slots = team.slots.map { slot ->
                if (slot.discipleId.isNotEmpty() && slot.discipleId !in ids)
                    slot.copy(discipleId = "", discipleName = "", isAlive = true) else slot
            })
        }
        val clearedProductionSlots = gameData.productionSlots.map {
            if (!it.assignedDiscipleId.isNullOrEmpty() && it.assignedDiscipleId !in ids)
                it.copy(assignedDiscipleId = null, assignedDiscipleName = "") else it
        }

        gameData = gameData.copy(
            spiritMineSlots = clearedSpiritMineSlots,
            librarySlots = clearedLibrarySlots,
            elderSlots = clearedElderSlots,
            worldMapSects = clearedGarrisonSects,
            caveExplorationTeams = clearedCaveTeams,
            activeMissions = clearedActiveMissions,
            patrolSlots = clearedPatrolSlots,
            warehouseGarrisons = clearedWarehouseGarrisons,
            battleTeams = clearedBattleTeams,
            productionSlots = clearedProductionSlots
        )

        // 远古秘境：重置所有弟子时终止探索会话（背包结算入仓 + 秘境消失 + 冷却）
        if (gameData.secretRealmSession.isActive || gameData.secretRealmState.exists) {
            secretRealmService.endSession(this)
        }

        for (id in discipleTables.ids) {
            val isAlive = discipleTables.isAlive[id] == 1
            val status = discipleTables.statuses[id]
            if (!isAlive) continue
            if (status == DiscipleStatus.REFLECTING) continue
            if (status == DiscipleStatus.REFINING) continue
            if (status == DiscipleStatus.IDLE) continue
            discipleTables.statuses[id] = DiscipleStatus.IDLE
            discipleTables.statusData[id] = emptyMap()
        }

        return ids
    }

    private fun clearAllDisciplesFromElderSlots(slots: ElderSlots, protectedIds: Set<String>): ElderSlots {
        var updated = slots

        if (updated.viceSectMaster.isNotEmpty() && updated.viceSectMaster !in protectedIds)
            updated = updated.copy(viceSectMaster = "")
        if (updated.herbGardenElder.isNotEmpty() && updated.herbGardenElder !in protectedIds)
            updated = updated.copy(herbGardenElder = "")
        if (updated.alchemyElder.isNotEmpty() && updated.alchemyElder !in protectedIds)
            updated = updated.copy(alchemyElder = "")
        if (updated.forgeElder.isNotEmpty() && updated.forgeElder !in protectedIds)
            updated = updated.copy(forgeElder = "")
        if (updated.outerElder.isNotEmpty() && updated.outerElder !in protectedIds)
            updated = updated.copy(outerElder = "")
        if (updated.preachingElder.isNotEmpty() && updated.preachingElder !in protectedIds)
            updated = updated.copy(preachingElder = "")
        if (updated.lawEnforcementElder.isNotEmpty() && updated.lawEnforcementElder !in protectedIds)
            updated = updated.copy(lawEnforcementElder = "")
        if (updated.innerElder.isNotEmpty() && updated.innerElder !in protectedIds)
            updated = updated.copy(innerElder = "")
        // 回归：纳徒长老此前漏清（与 DiscipleSlotCleanup.clearElderSlots 对齐）
        if (updated.recruitingElder.isNotEmpty() && updated.recruitingElder !in protectedIds)
            updated = updated.copy(recruitingElder = "")
        if (updated.qingyunPreachingElder.isNotEmpty() && updated.qingyunPreachingElder !in protectedIds)
            updated = updated.copy(qingyunPreachingElder = "")

        updated = updated.copy(
            preachingMasters = updated.preachingMasters.filter { it.discipleId in protectedIds },
            lawEnforcementDisciples = updated.lawEnforcementDisciples.filter { it.discipleId in protectedIds },
            qingyunPreachingMasters = updated.qingyunPreachingMasters.filter { it.discipleId in protectedIds },
            herbGardenDisciples = updated.herbGardenDisciples.filter { it.discipleId in protectedIds },
            alchemyDisciples = updated.alchemyDisciples.filter { it.discipleId in protectedIds },
            forgeDisciples = updated.forgeDisciples.filter { it.discipleId in protectedIds },
            spiritMineDeaconDisciples = updated.spiritMineDeaconDisciples.filter { it.discipleId in protectedIds }
        )

        return updated
    }
}

// ── 文件级私有构建函数（buildSlotFlagsFor 拆分：控制单函数圈复杂度且不计入类函数数）──

/** 队伍/秘境/驻守类 flag（与 [buildSlotFlagsFor] 拆分的复杂度隔离） */
private data class TeamFlags(
    val inGarrison: Boolean,
    val inWarehouseGarrison: Boolean,
    val inTeam: Boolean,
    val inSecretRealm: Boolean
)

/** 执法/传道/执事 flag 中间载体 */
private data class OfficerFlags(
    val lawEnforcing: Boolean,
    val preaching: Boolean,
    val deaconing: Boolean
)

/** 生产/建筑职务 flag 中间载体 */
private data class ProductionFlags(
    val studying: Boolean,
    val mining: Boolean,
    val patrolling: Boolean,
    val alchemy: Boolean,
    val forge: Boolean,
    val spiritPlanting: Boolean
)

private fun buildTeamFlags(data: GameData, discipleId: String): TeamFlags {
    val inCaveExploration = data.caveExplorationTeams.any { team ->
        team.memberIds.contains(discipleId) &&
            team.status in DiscipleStatusService.caveExplorationStatuses
    }
    // 远古秘境：探索会话存在且秘境在地图上时，成员标记为秘境占用（SECRET_REALM）
    val inSecretRealm = data.secretRealmState.exists &&
        data.secretRealmSession.members.any { it.discipleId == discipleId && !it.isDead }
    val inGarrison =
        data.worldMapSects.find { it.isPlayerSect }
            ?.garrisonSlots?.any { it.discipleId == discipleId } == true
    // 仓库驻守单独标记（WAREHOUSE_GARRISON），不再并入 inGarrison——
    // 文案需区分"驻守中"（据点驻军）与"仓库驻守中"
    val inWarehouseGarrison =
        data.warehouseGarrisons.any { it.discipleId == discipleId }
    val inTeam = data.battleTeams
        .any { t -> t.slots.any { it.discipleId == discipleId } }
        || inCaveExploration
    return TeamFlags(inGarrison, inWarehouseGarrison, inTeam, inSecretRealm)
}

private fun buildOfficerFlags(elderSlots: ElderSlots, discipleId: String): OfficerFlags {
    val lawEnforcing = elderSlots.lawEnforcementElder == discipleId
        || elderSlots.lawEnforcementDisciples
            .any { it.discipleId == discipleId }
    val preaching = elderSlots.preachingElder == discipleId
        || elderSlots.preachingMasters.any { it.discipleId == discipleId }
        || elderSlots.qingyunPreachingElder == discipleId
        || elderSlots.qingyunPreachingMasters
            .any { it.discipleId == discipleId }
    val deaconing = elderSlots.spiritMineDeaconDisciples
        .any { it.discipleId == discipleId }
    return OfficerFlags(lawEnforcing, preaching, deaconing)
}

/** 管理职位 flag（副宗主/各长老/直属弟子）——9 条件独立成函数防超限 */
private fun buildManagingFlag(elderSlots: ElderSlots, discipleId: String): Boolean =
    elderSlots.viceSectMaster == discipleId
        || elderSlots.outerElder == discipleId
        || elderSlots.innerElder == discipleId
        || elderSlots.forgeElder == discipleId
        || elderSlots.alchemyElder == discipleId
        || elderSlots.herbGardenElder == discipleId
        // 回归（2026-08-10）：纳徒长老此前漏推——被推导为 IDLE 后
        // 从"可用弟子"列表可见，月度自动排班等入口将其当作空闲调动
        || elderSlots.recruitingElder == discipleId
        || elderSlots.herbGardenDisciples
            .any { it.discipleId == discipleId }
        || elderSlots.alchemyDisciples
            .any { it.discipleId == discipleId }
        || elderSlots.forgeDisciples
            .any { it.discipleId == discipleId }

/** 生产/建筑职务类 flag（藏经阁/矿场/巡视/炼丹/锻造/灵植） */
private fun buildProductionFlags(data: GameData, discipleId: String): ProductionFlags {
    val studying = data.librarySlots.any { it.discipleId == discipleId }
    val mining = data.spiritMineSlots.any { it.discipleId == discipleId }
    val patrolling = data.patrolSlots.any { it.discipleId == discipleId }
    val alchemy = data.productionSlots
        .any { it.assignedDiscipleId == discipleId && it.buildingId == "alchemy" }
    val forge = data.productionSlots
        .any { it.assignedDiscipleId == discipleId && it.buildingId == "forge" }
    val spiritPlanting = data.productionSlots
        .any { it.assignedDiscipleId == discipleId && it.buildingId == "herbGarden" }
    return ProductionFlags(studying, mining, patrolling, alchemy, forge, spiritPlanting)
}
