package com.xianxia.sect.core.model

import com.xianxia.sect.core.GameConfig
import org.junit.Assert.*
import org.junit.Test

class MapModelsTest {

    // ==================== MapCoordinateSystem 常量 ====================

    @Test
    fun mapCoordinateSystem_worldDimensions() {
        assertEquals(GameConfig.WorldMap.MAP_WIDTH.toFloat(), MapCoordinateSystem.WORLD_WIDTH)
        assertEquals(GameConfig.WorldMap.MAP_HEIGHT.toFloat(), MapCoordinateSystem.WORLD_HEIGHT)
    }

    // ==================== MapCoordinateSystem worldToNormalized ====================

    @Test
    fun worldToNormalized_origin() {
        val (nx, ny) = MapCoordinateSystem.worldToNormalized(0f, 0f)
        assertEquals(0f, nx, 0.001f)
        assertEquals(0f, ny, 0.001f)
    }

    @Test
    fun worldToNormalized_maxValues() {
        val (nx, ny) = MapCoordinateSystem.worldToNormalized(
            MapCoordinateSystem.WORLD_WIDTH,
            MapCoordinateSystem.WORLD_HEIGHT
        )
        assertEquals(1f, nx, 0.001f)
        assertEquals(1f, ny, 0.001f)
    }

    @Test
    fun worldToNormalized_midPoint() {
        val (nx, ny) = MapCoordinateSystem.worldToNormalized(
            MapCoordinateSystem.WORLD_WIDTH / 2,
            MapCoordinateSystem.WORLD_HEIGHT / 2
        )
        assertEquals(0.5f, nx, 0.001f)
        assertEquals(0.5f, ny, 0.001f)
    }

    @Test
    fun worldToNormalized_clampsOverflow() {
        val (nx, ny) = MapCoordinateSystem.worldToNormalized(
            MapCoordinateSystem.WORLD_WIDTH * 2,
            MapCoordinateSystem.WORLD_HEIGHT * 2
        )
        assertEquals(1f, nx, 0.001f)
        assertEquals(1f, ny, 0.001f)
    }

    @Test
    fun worldToNormalized_clampsNegative() {
        val (nx, ny) = MapCoordinateSystem.worldToNormalized(-100f, -100f)
        assertEquals(0f, nx, 0.001f)
        assertEquals(0f, ny, 0.001f)
    }

    // ==================== MapCoordinateSystem normalizedToWorld ====================

    @Test
    fun normalizedToWorld_origin() {
        val (wx, wy) = MapCoordinateSystem.normalizedToWorld(0f, 0f)
        assertEquals(0f, wx, 0.001f)
        assertEquals(0f, wy, 0.001f)
    }

    @Test
    fun normalizedToWorld_maxValues() {
        val (wx, wy) = MapCoordinateSystem.normalizedToWorld(1f, 1f)
        assertEquals(MapCoordinateSystem.WORLD_WIDTH, wx, 0.001f)
        assertEquals(MapCoordinateSystem.WORLD_HEIGHT, wy, 0.001f)
    }

    @Test
    fun normalizedToWorld_midPoint() {
        val (wx, wy) = MapCoordinateSystem.normalizedToWorld(0.5f, 0.5f)
        assertEquals(MapCoordinateSystem.WORLD_WIDTH / 2, wx, 0.001f)
        assertEquals(MapCoordinateSystem.WORLD_HEIGHT / 2, wy, 0.001f)
    }

    // ==================== MapCoordinateSystem worldToCanvas ====================

    @Test
    fun worldToCanvas_origin() {
        val (cx, cy) = MapCoordinateSystem.worldToCanvas(0f, 0f, 800f, 600f)
        assertEquals(0f, cx, 0.001f)
        assertEquals(0f, cy, 0.001f)
    }

    @Test
    fun worldToCanvas_maxValues() {
        val (cx, cy) = MapCoordinateSystem.worldToCanvas(
            MapCoordinateSystem.WORLD_WIDTH,
            MapCoordinateSystem.WORLD_HEIGHT,
            800f, 600f
        )
        assertEquals(800f, cx, 0.001f)
        assertEquals(600f, cy, 0.001f)
    }

    // ==================== CultivatorCave ====================

    @Test
    fun cultivatorCave_defaultConstruction() {
        val cave = CultivatorCave()
        assertNotNull(cave.id)
        assertEquals("", cave.name)
        assertEquals(5, cave.ownerRealm)
        assertEquals("", cave.ownerRealmName)
        assertEquals(0f, cave.x, 0.001f)
        assertEquals(0f, cave.y, 0.001f)
        assertEquals(1, cave.spawnYear)
        assertEquals(1, cave.spawnMonth)
        assertEquals(1, cave.expiryYear)
        assertEquals(1, cave.expiryMonth)
        assertFalse(cave.isExplored)
        assertNull(cave.exploredByTeamId)
        assertEquals(CaveStatus.AVAILABLE, cave.status)
        assertTrue(cave.canOperate)
        assertFalse(cave.isOwned)
        assertTrue(cave.connectedSects.isEmpty())
        assertTrue(cave.mineSlots.isEmpty())
        assertEquals(0L, cave.occupationTime)
    }

    @Test
    fun cultivatorCave_isAvailable() {
        assertTrue(CultivatorCave(status = CaveStatus.AVAILABLE).isAvailable)
        assertFalse(CultivatorCave(status = CaveStatus.EXPLORING).isAvailable)
        assertFalse(CultivatorCave(status = CaveStatus.EXPLORED).isAvailable)
        assertFalse(CultivatorCave(status = CaveStatus.EXPIRED).isAvailable)
    }

    @Test
    fun cultivatorCave_isExpired_status() {
        assertTrue(CultivatorCave(status = CaveStatus.EXPIRED).isExpired)
        assertFalse(CultivatorCave(status = CaveStatus.AVAILABLE).isExpired)
    }

    @Test
    fun cultivatorCave_isExpired_byTime() {
        val cave = CultivatorCave(
            status = CaveStatus.AVAILABLE,
            expiryYear = 10, expiryMonth = 6
        )
        assertTrue(cave.isExpired(11, 1))
        assertTrue(cave.isExpired(10, 6))
        assertFalse(cave.isExpired(10, 5))
        assertFalse(cave.isExpired(9, 12))
    }

    @Test
    fun cultivatorCave_getRemainingMonths() {
        val cave = CultivatorCave(expiryYear = 2, expiryMonth = 6)
        assertEquals(17, cave.getRemainingMonths(1, 1))
        assertEquals(0, cave.getRemainingMonths(3, 1))
    }

    @Test
    fun cultivatorCave_getRemainingMonths_sameYear() {
        val cave = CultivatorCave(expiryYear = 1, expiryMonth = 6)
        assertEquals(5, cave.getRemainingMonths(1, 1))
        assertEquals(0, cave.getRemainingMonths(1, 6))
    }

    @Test
    fun cultivatorCave_copy() {
        val cave = CultivatorCave(name = "古洞", status = CaveStatus.AVAILABLE)
        val copied = cave.copy(status = CaveStatus.EXPLORING)
        assertEquals(CaveStatus.AVAILABLE, cave.status)
        assertEquals(CaveStatus.EXPLORING, copied.status)
        assertEquals("古洞", copied.name)
    }

    // ==================== CaveStatus 枚举 ====================

    @Test
    fun caveStatus_displayName() {
        assertEquals("可探索", CaveStatus.AVAILABLE.displayName)
        assertEquals("探索中", CaveStatus.EXPLORING.displayName)
        assertEquals("已探索", CaveStatus.EXPLORED.displayName)
        assertEquals("已消失", CaveStatus.EXPIRED.displayName)
    }

    @Test
    fun caveStatus_values() {
        assertEquals(4, CaveStatus.values().size)
    }

    // ==================== GridBuildingData ====================

    @Test
    fun gridBuildingData_defaultConstruction() {
        val data = GridBuildingData()
        assertEquals("", data.buildingId)
        assertEquals("", data.displayName)
        assertEquals(0, data.gridX)
        assertEquals(0, data.gridY)
        assertEquals(2, data.width)
        assertEquals(3, data.height)
        assertEquals("", data.instanceId)
        assertEquals("", data.sectId)
    }

    @Test
    fun gridBuildingData_parameterizedConstruction() {
        val data = GridBuildingData(
            buildingId = "b1",
            displayName = "灵药园",
            gridX = 5,
            gridY = 3,
            width = 4,
            height = 4,
            instanceId = "inst1",
            sectId = "sect1"
        )
        assertEquals("b1", data.buildingId)
        assertEquals("灵药园", data.displayName)
        assertEquals(5, data.gridX)
        assertEquals(3, data.gridY)
        assertEquals(4, data.width)
        assertEquals(4, data.height)
        assertEquals("inst1", data.instanceId)
        assertEquals("sect1", data.sectId)
    }

    @Test
    fun gridBuildingData_withInstanceId_blankInstanceId_generatesNew() {
        val data = GridBuildingData(instanceId = "")
        val withId = data.withInstanceId()
        assertTrue(withId.instanceId.isNotEmpty())
        assertEquals(data.buildingId, withId.buildingId)
    }

    @Test
    fun gridBuildingData_withInstanceId_existingInstanceId_returnsSame() {
        val data = GridBuildingData(instanceId = "existing")
        val withId = data.withInstanceId()
        assertEquals("existing", withId.instanceId)
    }

    @Test
    fun gridBuildingData_ensureAllHaveInstanceId_emptyList() {
        val result = GridBuildingData.ensureAllHaveInstanceId(emptyList())
        assertTrue(result.isEmpty())
    }

    @Test
    fun gridBuildingData_ensureAllHaveInstanceId_generatesForMissing() {
        val buildings = listOf(
            GridBuildingData(buildingId = "b1", instanceId = ""),
            GridBuildingData(buildingId = "b2", instanceId = "existing")
        )
        val result = GridBuildingData.ensureAllHaveInstanceId(buildings)
        assertTrue(result[0].instanceId.isNotEmpty())
        assertEquals("existing", result[1].instanceId)
    }

    @Test
    fun gridBuildingData_copy() {
        val data = GridBuildingData(buildingId = "b1", gridX = 1, gridY = 2)
        val copied = data.copy(gridX = 5)
        assertEquals(1, data.gridX)
        assertEquals(5, copied.gridX)
        assertEquals(2, copied.gridY)
    }

    @Test
    fun gridBuildingData_equality() {
        val d1 = GridBuildingData(buildingId = "b1", gridX = 1, gridY = 2)
        val d2 = GridBuildingData(buildingId = "b1", gridX = 1, gridY = 2)
        assertEquals(d1, d2)
    }

    // ==================== ExplorationStatus 枚举 ====================

    @Test
    fun explorationStatus_displayName() {
        assertEquals("前往中", ExplorationStatus.TRAVELING.displayName)
        assertEquals("探索中", ExplorationStatus.EXPLORING.displayName)
        assertEquals("遇险中", ExplorationStatus.DANGER.displayName)
        assertEquals("已完成", ExplorationStatus.COMPLETED.displayName)
        assertEquals("侦查中", ExplorationStatus.SCOUTING.displayName)
    }

    @Test
    fun explorationStatus_values() {
        assertEquals(5, ExplorationStatus.values().size)
    }

    // ==================== ExplorationTeam ====================

    @Test
    fun explorationTeam_defaultConstruction() {
        val team = ExplorationTeam()
        assertNotNull(team.id)
        assertEquals(0, team.slotId)
        assertEquals("", team.name)
        assertNull(team.caveId)
        assertEquals(ExplorationStatus.TRAVELING, team.status)
        assertEquals(0, team.progress)
    }

    @Test
    fun explorationTeam_isTraveling() {
        assertTrue(ExplorationTeam(status = ExplorationStatus.TRAVELING).isTraveling)
        assertFalse(ExplorationTeam(status = ExplorationStatus.EXPLORING).isTraveling)
    }

    @Test
    fun explorationTeam_isExploring() {
        assertTrue(ExplorationTeam(status = ExplorationStatus.EXPLORING).isExploring)
        assertFalse(ExplorationTeam(status = ExplorationStatus.TRAVELING).isExploring)
    }

    @Test
    fun explorationTeam_isComplete() {
        assertTrue(ExplorationTeam(status = ExplorationStatus.COMPLETED).isComplete)
        assertFalse(ExplorationTeam(status = ExplorationStatus.TRAVELING).isComplete)
    }

    @Test
    fun explorationTeam_isScouting() {
        assertTrue(ExplorationTeam(status = ExplorationStatus.SCOUTING).isScouting)
        assertFalse(ExplorationTeam(status = ExplorationStatus.TRAVELING).isScouting)
    }

    @Test
    fun explorationTeam_isMoving_scoutingWithProgress() {
        val team = ExplorationTeam(status = ExplorationStatus.SCOUTING, moveProgress = 0.5f)
        assertTrue(team.isMoving)
    }

    @Test
    fun explorationTeam_isNotMoving_scoutingCompleted() {
        val team = ExplorationTeam(status = ExplorationStatus.SCOUTING, moveProgress = 1f)
        assertFalse(team.isMoving)
    }

    @Test
    fun explorationTeam_getProgressPercent_zeroDuration() {
        val team = ExplorationTeam(duration = 0)
        assertEquals(0, team.getProgressPercent(1, 1))
    }

    // ==================== CaveExplorationStatus 枚举 ====================

    @Test
    fun caveExplorationStatus_displayName() {
        assertEquals("前往中", CaveExplorationStatus.TRAVELING.displayName)
        assertEquals("探索中", CaveExplorationStatus.EXPLORING.displayName)
        assertEquals("已完成", CaveExplorationStatus.COMPLETED.displayName)
    }

    @Test
    fun caveExplorationStatus_values() {
        assertEquals(3, CaveExplorationStatus.values().size)
    }

    // ==================== CaveExplorationTeam ====================

    @Test
    fun caveExplorationTeam_defaultConstruction() {
        val team = CaveExplorationTeam()
        assertNotNull(team.id)
        assertEquals("", team.caveId)
        assertEquals("", team.caveName)
        assertTrue(team.memberIds.isEmpty())
        assertEquals(CaveExplorationStatus.TRAVELING, team.status)
        assertEquals(2000f, team.startX, 0.001f)
        assertEquals(1750f, team.startY, 0.001f)
    }

    @Test
    fun caveExplorationTeam_isTraveling() {
        assertTrue(CaveExplorationTeam(status = CaveExplorationStatus.TRAVELING).isTraveling)
        assertFalse(CaveExplorationTeam(status = CaveExplorationStatus.EXPLORING).isTraveling)
    }

    @Test
    fun caveExplorationTeam_isExploring() {
        assertTrue(CaveExplorationTeam(status = CaveExplorationStatus.EXPLORING).isExploring)
        assertFalse(CaveExplorationTeam(status = CaveExplorationStatus.TRAVELING).isExploring)
    }

    @Test
    fun caveExplorationTeam_isComplete() {
        assertTrue(CaveExplorationTeam(status = CaveExplorationStatus.COMPLETED).isComplete)
        assertFalse(CaveExplorationTeam(status = CaveExplorationStatus.TRAVELING).isComplete)
    }

    @Test
    fun caveExplorationTeam_isMoving_travelingWithProgress() {
        val team = CaveExplorationTeam(
            status = CaveExplorationStatus.TRAVELING, moveProgress = 0.3f
        )
        assertTrue(team.isMoving)
    }

    @Test
    fun caveExplorationTeam_isNotMoving_travelingCompleted() {
        val team = CaveExplorationTeam(
            status = CaveExplorationStatus.TRAVELING, moveProgress = 1f
        )
        assertFalse(team.isMoving)
    }

    @Test
    fun caveExplorationTeam_getProgressPercent_zeroDuration() {
        val team = CaveExplorationTeam(duration = 0)
        assertEquals(0, team.getProgressPercent(1, 1))
    }

    // ==================== AITeamStatus 枚举 ====================

    @Test
    fun aiTeamStatus_displayName() {
        assertEquals("探索中", AITeamStatus.EXPLORING.displayName)
        assertEquals("已击败", AITeamStatus.DEFEATED.displayName)
    }

    @Test
    fun aiTeamStatus_values() {
        assertEquals(2, AITeamStatus.values().size)
    }

    // ==================== AICaveTeam ====================

    @Test
    fun aiCaveTeam_defaultConstruction() {
        val team = AICaveTeam()
        assertNotNull(team.id)
        assertEquals("", team.caveId)
        assertEquals("", team.sectId)
        assertEquals(5, team.memberCount)
        assertEquals(5, team.avgRealm)
        assertEquals(AITeamStatus.EXPLORING, team.status)
    }

    @Test
    fun aiCaveTeam_isExploring() {
        assertTrue(AICaveTeam(status = AITeamStatus.EXPLORING).isExploring)
        assertFalse(AICaveTeam(status = AITeamStatus.DEFEATED).isExploring)
    }

    @Test
    fun aiCaveTeam_isDefeated() {
        assertTrue(AICaveTeam(status = AITeamStatus.DEFEATED).isDefeated)
        assertFalse(AICaveTeam(status = AITeamStatus.EXPLORING).isDefeated)
    }

    // ==================== AICaveDisciple ====================

    @Test
    fun aiCaveDisciple_defaultConstruction() {
        val disciple = AICaveDisciple()
        assertEquals("", disciple.id)
        assertEquals("", disciple.name)
        assertEquals(5, disciple.realm)
        assertEquals(1000, disciple.hp)
        assertEquals(1000, disciple.maxHp)
    }

    @Test
    fun aiCaveDisciple_isAlive() {
        assertTrue(AICaveDisciple(hp = 100).isAlive)
        assertFalse(AICaveDisciple(hp = 0).isAlive)
    }

    @Test
    fun aiCaveDisciple_hpPercent() {
        assertEquals(100, AICaveDisciple(hp = 1000, maxHp = 1000).hpPercent)
        assertEquals(50, AICaveDisciple(hp = 500, maxHp = 1000).hpPercent)
        assertEquals(0, AICaveDisciple(hp = 0, maxHp = 1000).hpPercent)
    }

    @Test
    fun aiCaveDisciple_hpPercent_zeroMaxHp() {
        assertEquals(0, AICaveDisciple(hp = 100, maxHp = 0).hpPercent)
    }

    // ==================== SlotStatus 枚举 ====================

    @Test
    fun slotStatus_displayName() {
        assertEquals("空闲", SlotStatus.IDLE.displayName)
        assertEquals("进行中", SlotStatus.WORKING.displayName)
        assertEquals("已完成", SlotStatus.COMPLETED.displayName)
    }

    // ==================== RecipeType 枚举 ====================

    @Test
    fun recipeType_displayName() {
        assertEquals("丹方", RecipeType.PILL.displayName)
        assertEquals("锻造", RecipeType.FORGE.displayName)
    }

    // ==================== BattleType 枚举 ====================

    @Test
    fun battleType_displayName() {
        assertEquals("PVE战斗", BattleType.PVE.displayName)
        assertEquals("PVP战斗", BattleType.PVP.displayName)
        assertEquals("宗门战", BattleType.SECT_WAR.displayName)
        assertEquals("洞府探索", BattleType.CAVE_EXPLORATION.displayName)
        assertEquals("探查", BattleType.SCOUT.displayName)
    }

    // ==================== BattleResult 枚举 ====================

    @Test
    fun battleResult_displayName() {
        assertEquals("胜利", BattleResult.WIN.displayName)
        assertEquals("失败", BattleResult.LOSE.displayName)
        assertEquals("平局", BattleResult.DRAW.displayName)
    }

    @Test
    fun battleResult_winner() {
        assertEquals("team", BattleResult.WIN.winner)
        assertEquals("beasts", BattleResult.LOSE.winner)
        assertEquals("draw", BattleResult.DRAW.winner)
    }

    // ==================== BattleLog ====================

    @Test
    fun battleLog_displayTime() {
        val log = BattleLog(year = 5, month = 8)
        assertEquals("第5年8月", log.displayTime)
    }

    // ==================== LevelType 枚举 ====================

    @Test
    fun levelType_values() {
        assertEquals(2, LevelType.values().size)
        assertTrue(LevelType.values().contains(LevelType.BEAST))
        assertTrue(LevelType.values().contains(LevelType.CAVE))
    }

    // ==================== WorldLevel ====================

    @Test
    fun worldLevel_defaultConstruction() {
        val level = WorldLevel()
        assertNotNull(level.id)
        assertEquals(LevelType.BEAST, level.type)
        assertNull(level.beastType)
        assertEquals(9, level.realm)
        assertEquals(1, level.realmLayer)
        assertEquals("", level.beastName)
        assertEquals("", level.guardianName)
        assertEquals("", level.caveName)
        assertEquals(0f, level.x, 0.001f)
        assertEquals(0f, level.y, 0.001f)
        assertEquals(5, level.count)
        assertFalse(level.defeated)
    }

    @Test
    fun worldLevel_isBeast() {
        assertTrue(WorldLevel(type = LevelType.BEAST).isBeast)
        assertFalse(WorldLevel(type = LevelType.CAVE).isBeast)
    }

    @Test
    fun worldLevel_isCave() {
        assertTrue(WorldLevel(type = LevelType.CAVE).isCave)
        assertFalse(WorldLevel(type = LevelType.BEAST).isCave)
    }

    @Test
    fun worldLevel_realmName() {
        assertEquals("仙人", WorldLevel(realm = 0).realmName)
        assertEquals("渡劫", WorldLevel(realm = 1).realmName)
        assertEquals("大乘", WorldLevel(realm = 2).realmName)
        assertEquals("合体", WorldLevel(realm = 3).realmName)
        assertEquals("炼虚", WorldLevel(realm = 4).realmName)
        assertEquals("化神", WorldLevel(realm = 5).realmName)
        assertEquals("元婴", WorldLevel(realm = 6).realmName)
        assertEquals("金丹", WorldLevel(realm = 7).realmName)
        assertEquals("筑基", WorldLevel(realm = 8).realmName)
        assertEquals("炼气", WorldLevel(realm = 9).realmName)
        assertEquals("炼气", WorldLevel(realm = 99).realmName)
    }

    @Test
    fun worldLevel_isExpired_defeated() {
        assertTrue(WorldLevel(defeated = true).isExpired)
        assertFalse(WorldLevel(defeated = false).isExpired)
    }

    @Test
    fun worldLevel_checkExpired_defeated() {
        assertTrue(WorldLevel(defeated = true).checkExpired(1, 1))
    }

    @Test
    fun worldLevel_checkExpired_byTime() {
        val level = WorldLevel(expiryYear = 10, expiryMonth = 6, defeated = false)
        assertTrue(level.checkExpired(11, 1))
        assertTrue(level.checkExpired(10, 6))
        assertFalse(level.checkExpired(10, 5))
    }

    // ==================== TeamStatus 枚举 ====================

    @Test
    fun teamStatus_displayName() {
        assertEquals("待命", TeamStatus.IDLE.displayName)
        assertEquals("探索中", TeamStatus.EXPLORING.displayName)
        assertEquals("返回中", TeamStatus.RETURNING.displayName)
        assertEquals("已完成", TeamStatus.COMPLETED.displayName)
    }

    @Test
    fun teamStatus_values() {
        assertEquals(4, TeamStatus.values().size)
    }
}
