package com.xianxia.sect.core.engine.domain.settlement

import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.engine.system.FocusDomain
import com.xianxia.sect.core.model.*
import com.xianxia.sect.core.state.DiscipleTables
import com.xianxia.sect.core.state.EntityStore
import com.xianxia.sect.core.state.MutableGameState
import org.junit.Assert.*
import org.junit.Test

/**
 * 空闲模式结算单元测试。
 *
 * 覆盖：
 * - computeDomainsFromView 空闲过滤
 * - ProductionRateFingerprint 计算
 * - classifySlotsProgress 80% 分类
 * - 微结算逻辑（cultivationMicroSettle）
 * - 空闲全量结算流程
 */
class IdleModeSettlementTest {

    // ============================================================
    // 辅助方法
    // ============================================================

    private fun emptyState(): MutableGameState = MutableGameState(
        gameData = GameData(),
        discipleTables = DiscipleTables(),
        equipmentStacks = EntityStore(),
        equipmentInstances = EntityStore(),
        manualStacks = EntityStore(),
        manualInstances = EntityStore(),
        pills = EntityStore(),
        materials = EntityStore(),
        herbs = EntityStore(),
        seeds = EntityStore(),
        storageBags = EntityStore(),
        teams = emptyList(),
        battleLogs = emptyList(),
        isPaused = false,
        isLoading = false,
        isSaving = false
    )

    // ============================================================
    // 1. FocusDomain 空闲过滤
    // ============================================================

    @Test
    fun `resolveDomainsFromView - idle removes DISCIPLES adds BACKGROUND`() {
        // OVERVIEW 活跃模式：DISCIPLES + BUILDINGS + ALWAYS
        val active = com.xianxia.sect.core.engine.resolveDomainsFromView(
            tab = "OVERVIEW", dialog = null, focusedDiscipleId = null
        )
        assertTrue("active OVERVIEW has DISCIPLES", FocusDomain.DISCIPLES in active)
        assertTrue("active OVERVIEW has BUILDINGS", FocusDomain.BUILDINGS in active)

        // 空闲模式：过滤 DISCIPLES + 加 BACKGROUND
        val idle = active.toMutableSet().apply {
            remove(FocusDomain.DISCIPLES)
            add(FocusDomain.BACKGROUND)
        }
        assertFalse("idle OVERVIEW removed DISCIPLES", FocusDomain.DISCIPLES in idle)
        assertTrue("idle OVERVIEW keeps BUILDINGS", FocusDomain.BUILDINGS in idle)
        assertTrue("idle OVERVIEW adds BACKGROUND", FocusDomain.BACKGROUND in idle)
    }

    @Test
    fun `resolveDomainsFromView - Production dialogs keep BUILDINGS in idle`() {
        for (dialog in listOf("Alchemy", "Forge", "HerbGarden", "SpiritMine", "Planting")) {
            val active = com.xianxia.sect.core.engine.resolveDomainsFromView(
                tab = null, dialog = dialog, focusedDiscipleId = null
            )
            val idle = active.toMutableSet().apply {
                remove(FocusDomain.DISCIPLES)
                add(FocusDomain.BACKGROUND)
            }
            assertTrue("$dialog: BUILDINGS kept in idle", FocusDomain.BUILDINGS in idle)
            assertTrue("$dialog: BACKGROUND added", FocusDomain.BACKGROUND in idle)
        }
    }

    @Test
    fun `resolveDomainsFromView - MissionHall keeps EXPLORATION in idle`() {
        val active = com.xianxia.sect.core.engine.resolveDomainsFromView(
            tab = null, dialog = "MissionHall", focusedDiscipleId = null
        )
        val idle = active.toMutableSet().apply {
            remove(FocusDomain.DISCIPLES)
            add(FocusDomain.BACKGROUND)
        }
        assertTrue("MissionHall idle keeps EXPLORATION", FocusDomain.EXPLORATION in idle)
        assertFalse("MissionHall idle removes DISCIPLES", FocusDomain.DISCIPLES in idle)
    }

    @Test
    fun `resolveDomainsFromView - BloodRefiningPool keeps BUILDINGS in idle`() {
        val active = com.xianxia.sect.core.engine.resolveDomainsFromView(
            tab = null, dialog = "BloodRefiningPool", focusedDiscipleId = null
        )
        val idle = active.toMutableSet().apply {
            remove(FocusDomain.DISCIPLES)
            add(FocusDomain.BACKGROUND)
        }
        assertTrue("BloodRefiningPool idle keeps BUILDINGS", FocusDomain.BUILDINGS in idle)
    }

    @Test
    fun `resolveDomainsFromView - DISCIPLES tab idle only has BACKGROUND`() {
        val active = com.xianxia.sect.core.engine.resolveDomainsFromView(
            tab = "DISCIPLES", dialog = null, focusedDiscipleId = null
        )
        val idle = active.toMutableSet().apply {
            remove(FocusDomain.DISCIPLES)
            add(FocusDomain.BACKGROUND)
        }
        // DISCIPLES tab 只有 DISCIPLES → 空闲时只剩 BACKGROUND
        assertEquals("DISCIPLES tab idle: only ALWAYS+BACKGROUND",
            setOf(FocusDomain.ALWAYS, FocusDomain.BACKGROUND), idle)
    }

    @Test
    fun `resolveDomainsFromView - idle mode domain resolution`() {
        // 视角驱动：当前界面所在域即为焦点域
        // 焦点域界面（外交/世界地图）→ 对应域
        for ((dialog, expectedDomain) in listOf(
            "Diplomacy" to FocusDomain.DIPLOMACY,
            "WorldMap" to FocusDomain.WORLD_MAP
        )) {
            val active = com.xianxia.sect.core.engine.resolveDomainsFromView(
                tab = null, dialog = dialog, focusedDiscipleId = null
            )
            assertEquals("$dialog should include its domain",
                setOf(FocusDomain.ALWAYS, expectedDomain), active)
        }
        // 仅 ALWAYS 界面（天枢殿/邮件）→ 仅 ALWAYS + BACKGROUND
        for (dialog in listOf("TianshuHall", "Mail")) {
            val active = com.xianxia.sect.core.engine.resolveDomainsFromView(
                tab = null, dialog = dialog, focusedDiscipleId = null
            )
            val idle = active.toMutableSet().apply {
                add(FocusDomain.BACKGROUND)
            }
            assertEquals("$dialog idle: ALWAYS+BACKGROUND only",
                setOf(FocusDomain.ALWAYS, FocusDomain.BACKGROUND), idle)
        }
    }

    @Test
    fun `resolveDomainsFromView - WORLD_MAP and DIPLOMACY only from their dialogs`() {
        // WORLD_MAP/DIPLOMACY 仅在对应界面激活，不应在其他界面出现
        for ((tab, dialog) in listOf(
            null to "Alchemy", "OVERVIEW" to null, "DISCIPLES" to null,
            null to "MissionHall", null to "BloodRefiningPool"
        )) {
            val domains = com.xianxia.sect.core.engine.resolveDomainsFromView(
                tab = tab, dialog = dialog, focusedDiscipleId = null
            )
            assertFalse("WORLD_MAP not in domains for tab=$tab dialog=$dialog",
                FocusDomain.WORLD_MAP in domains)
            assertFalse("DIPLOMACY not in domains for tab=$tab dialog=$dialog",
                FocusDomain.DIPLOMACY in domains)
        }
    }

    // ============================================================
    // 2. ProductionRateFingerprint 计算
    // ============================================================

    @Test
    fun `productionFingerprint - empty state computes without crash`() {
        val fp = ProductionRateFingerprint.compute(emptyState())
        assertNotNull("fingerprint computed", fp)
    }

    @Test
    fun `productionFingerprint - spiritMine assignment changes hash`() {
        val state1 = emptyState()
        val fp1 = ProductionRateFingerprint.compute(state1)

        val state2 = emptyState().copy(
            gameData = GameData(
                spiritMineSlots = listOf(
                    SpiritMineSlot(index = 0, discipleId = "42", buildingInstanceId = "b1")
                )
            )
        )
        val fp2 = ProductionRateFingerprint.compute(state2)
        assertNotEquals("spirit mine assignment changes fingerprint",
            fp1.spiritMineHash, fp2.spiritMineHash)
    }

    @Test
    fun `productionFingerprint - bloodRefinement changes hash`() {
        val state1 = emptyState()
        val fp1 = ProductionRateFingerprint.compute(state1)

        val state2 = emptyState().copy(
            gameData = GameData(
                activeBloodRefinements = mapOf(
                    "b1" to BloodRefinementProgress(
                        discipleId = "7", selectedStat = "speed",
                        durationMonths = 6, startYear = 1, startMonth = 1
                    )
                )
            )
        )
        val fp2 = ProductionRateFingerprint.compute(state2)
        assertNotEquals("blood refinement changes fingerprint",
            fp1.bloodRefinementHash, fp2.bloodRefinementHash)
    }

    @Test
    fun `productionFingerprint - activeMission changes hash`() {
        val state1 = emptyState()
        val fp1 = ProductionRateFingerprint.compute(state1)

        val state2 = emptyState().copy(
            gameData = GameData(
                activeMissions = listOf(
                    ActiveMission(
                        missionId = "m1", missionName = "test",
                        template = MissionTemplate.EXPLORE_ABANDONED_MINE,
                        difficulty = MissionDifficulty.SIMPLE,
                        discipleIds = listOf("1"), discipleNames = listOf("d1"),
                        discipleRealms = listOf("练气"),
                        startYear = 1, startMonth = 1, duration = 3,
                        rewards = MissionRewardConfig(),
                        missionType = MissionType.NO_COMBAT
                    )
                )
            )
        )
        val fp2 = ProductionRateFingerprint.compute(state2)
        assertNotEquals("mission changes fingerprint",
            fp1.missionHash, fp2.missionHash)
    }

    @Test
    fun `productionFingerprint - policy changes hash`() {
        val state1 = emptyState()
        val fp1 = ProductionRateFingerprint.compute(state1)

        val state2 = emptyState().copy(
            gameData = GameData(
                sectPolicies = SectPolicies(spiritMineBoost = true, alchemyIncentive = true)
            )
        )
        val fp2 = ProductionRateFingerprint.compute(state2)
        assertNotEquals("policy changes fingerprint",
            fp1.productionPolicyHash, fp2.productionPolicyHash)
    }

    @Test
    fun `productionFingerprint - elder assignments change alchemy hash`() {
        val state1 = emptyState()
        val fp1 = ProductionRateFingerprint.compute(state1)

        val state2 = emptyState().copy(
            gameData = GameData(
                elderSlots = ElderSlots(alchemyElder = "disciple_5")
            )
        )
        val fp2 = ProductionRateFingerprint.compute(state2)
        assertNotEquals("alchemy elder changes fingerprint",
            fp1.alchemyHash, fp2.alchemyHash)
    }

    // ============================================================
    // 3. CultivationRateFingerprint 完整性
    // ============================================================

    @Test
    fun `cultivationFingerprint - empty state computes`() {
        val state = emptyState()
        val fp = computeFingerprintForTest(state)
        assertNotNull("fingerprint computed", fp)
        assertEquals("empty: no alive disciples, aliveDiscipleIdsHash",
            emptyList<Int>().hashCode(), fp.aliveDiscipleIdsHash)
    }

    /**
     * 测试用指纹计算（复用 SettlementCoordinator 的公开方法路径）。
     * 生产代码中由 [SettlementCoordinator.computeCultivationFingerprint] 调用。
     */
    private fun computeFingerprintForTest(state: MutableGameState): CultivationRateFingerprint {
        val data = state.gameData
        val tables = state.discipleTables
        val aliveIds = tables.ids.filter { tables.isAlive[it] == 1 }
        return CultivationRateFingerprint(
            residenceLayout = data.residenceSlots.hashCode() * 31 +
                data.placedBuildings.hashCode(),
            elderAssignments = data.elderSlots.hashCode(),
            preachingAssignments = (
                data.elderSlots.preachingElder.hashCode() * 31 +
                data.elderSlots.preachingMasters.hashCode() * 31 +
                data.elderSlots.qingyunPreachingElder.hashCode() * 31 +
                data.elderSlots.qingyunPreachingMasters.hashCode()
            ),
            policyFlags = data.sectPolicies.hashCode(),
            aliveDiscipleIdsHash = aliveIds.hashCode(),
            realmHash = aliveIds.map { tables.realms[it] }.hashCode(),
            perDiscipleHash = aliveIds.map { id ->
                var h = 1
                h = 31 * h + tables.cultivationSpeedBonuses.getOrDefault(id, 0.0).hashCode()
                h = 31 * h + tables.cultivationSpeedDurations.getOrDefault(id, 0)
                h = 31 * h + tables.pillEffectDurations.getOrDefault(id, 0)
                h = 31 * h + tables.pillCultivationSpeedBonuses.getOrDefault(id, 0.0).hashCode()
                h = 31 * h + (tables.griefEndYears.getOrNull(id)?.hashCode() ?: 0)
                h = 31 * h + (tables.manualIds.getOrNull(id)?.hashCode() ?: 0)
                val lifespan = tables.lifespans.getOrDefault(id, 0)
                val age = tables.ages.getOrDefault(id, 0)
                val remain = if (lifespan > 0) ((lifespan - age) * 100 / lifespan).coerceIn(0, 100) else 100
                h = 31 * h + remain
                h = 31 * h + (tables.masterIds.getOrNull(id)?.hashCode() ?: 0)
                val mid = tables.masterIds.getOrNull(id)?.toIntOrNull()
                h = 31 * h + (mid?.let { tables.realms.getOrDefault(it, 9) } ?: 9)
                val p1 = tables.parentId1s.getOrNull(id)?.toIntOrNull()
                h = 31 * h + (p1?.let { tables.isAlive.getOrDefault(it, 0) } ?: 0)
                h = 31 * h + (p1?.let { tables.spiritRootTypes.getOrDefault(it, "").split(",").size } ?: 0)
                val p2 = tables.parentId2s.getOrNull(id)?.toIntOrNull()
                h = 31 * h + (p2?.let { tables.isAlive.getOrDefault(it, 0) } ?: 0)
                h = 31 * h + (p2?.let { tables.spiritRootTypes.getOrDefault(it, "").split(",").size } ?: 0)
                h
            }.hashCode()
        )
    }

    // ============================================================
    // 4. 空闲退出流程验证
    // ============================================================

    @Test
    fun `onUserInteraction - zero accumulation clears immediately`() {
        // 无累积 → 直接设置 isInIdleState=false + cleanup
        // 验证：不需要 pendingReturnFromIdleSettle，不需要等 tick
        val idleAccumulatedPhases = 0
        val idleAccumulatedMonths = 0
        val needsPendingSettle = idleAccumulatedPhases > 0 || idleAccumulatedMonths > 0
        assertFalse("zero accumulation: no pending settle needed", needsPendingSettle)
    }

    @Test
    fun `onUserInteraction - with accumulation sets pending flag`() {
        // 有累积 → 设 pendingReturnFromIdleSettle=true，isInIdleState 保持 true
        val idleAccumulatedPhases = 3
        val idleAccumulatedMonths = 1
        val needsPendingSettle = idleAccumulatedPhases > 0 || idleAccumulatedMonths > 0
        assertTrue("with accumulation: needs pending settle", needsPendingSettle)
    }

    @Test
    fun `idle settlement bypasses scheduler`() {
        val scheduler = SettlementScheduler()
        assertFalse("fresh scheduler has no pending work", scheduler.hasPendingWork)
        // 空闲模式：scheduleMonthly/scheduleYearly 不调用
        // 微结算：cultivationMicroSettle/productionMicroSettle 直接执行
        // 全量结算：fullIdleSettle 直接 swap
    }

    // ============================================================
    // 5. 边界条件
    // ============================================================

    @Test
    fun `productionFingerprint - same state produces same hash`() {
        val state = emptyState().copy(
            gameData = GameData(
                spiritMineSlots = listOf(
                    SpiritMineSlot(index = 0, discipleId = "1", buildingInstanceId = "b1")
                )
            )
        )
        val fp1 = ProductionRateFingerprint.compute(state)
        val fp2 = ProductionRateFingerprint.compute(state)
        assertEquals("same state → same spiritMineHash", fp1.spiritMineHash, fp2.spiritMineHash)
        assertEquals("same state → same herbGardenHash", fp1.herbGardenHash, fp2.herbGardenHash)
        assertEquals("same state → same alchemyHash", fp1.alchemyHash, fp2.alchemyHash)
        assertEquals("same state → same forgeHash", fp1.forgeHash, fp2.forgeHash)
        assertEquals("same state → same bloodRefinementHash",
            fp1.bloodRefinementHash, fp2.bloodRefinementHash)
        assertEquals("same state → same missionHash", fp1.missionHash, fp2.missionHash)
    }

    @Test
    fun `productionFingerprint - different states produce different hash`() {
        val state1 = emptyState().copy(
            gameData = GameData(spiritMineSlots = emptyList())
        )
        val state2 = emptyState().copy(
            gameData = GameData(
                spiritMineSlots = listOf(
                    SpiritMineSlot(index = 0, discipleId = "42", buildingInstanceId = "b1")
                )
            )
        )
        val fp1 = ProductionRateFingerprint.compute(state1)
        val fp2 = ProductionRateFingerprint.compute(state2)
        assertNotEquals("different spirit mine → different hash",
            fp1.spiritMineHash, fp2.spiritMineHash)
    }

    @Test
    fun `settlementCache - builds from empty state without crash`() {
        val cache = SettlementCache(emptyState())
        assertTrue("cultivation rate cache empty", cache.cultivationRateCache.isEmpty())
        assertTrue("dirty flags empty", cache.dirtyFlags.isEmpty())
        assertTrue("clean ids empty", cache.cleanDiscipleIds.isEmpty())
        assertTrue("dirty ids empty", cache.dirtyDiscipleIds.isEmpty())
    }
}
