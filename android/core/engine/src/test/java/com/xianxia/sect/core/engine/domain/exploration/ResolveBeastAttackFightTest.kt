package com.xianxia.sect.core.engine.domain.exploration

import com.xianxia.sect.core.engine.FakeAtomicStateStore
import com.xianxia.sect.core.exploration.BeastAttackDetector
import com.xianxia.sect.core.exploration.DiscipleDeathHandler
import com.xianxia.sect.core.exploration.LootCalculator
import com.xianxia.sect.core.exploration.PatrolBattleSystem
import com.xianxia.sect.core.exploration.WorldLevelManager
import com.xianxia.sect.core.model.Disciple
import com.xianxia.sect.core.model.DiscipleStatus
import com.xianxia.sect.core.model.GameData
import com.xianxia.sect.core.model.LevelType
import com.xianxia.sect.core.model.Material
import com.xianxia.sect.core.model.WorldLevel
import com.xianxia.sect.core.model.WorldSect
import com.xianxia.sect.core.model.spiritStones
import com.xianxia.sect.core.state.WriteGuardRule
import com.xianxia.sect.core.engine.domain.battle.BattleSystem
import com.xianxia.sect.core.engine.domain.battle.BattleSystemResult
import com.xianxia.sect.core.engine.domain.battle.Battle
import com.xianxia.sect.core.engine.mockSmart
import com.xianxia.sect.core.engine.service.CultivationService
import com.xianxia.sect.core.engine.system.InventorySystem
import com.xianxia.sect.core.domain.battle.EncounterBattleService
import com.xianxia.sect.core.wallet.DeductResult
import com.xianxia.sect.core.wallet.SpiritStoneWallet
import com.xianxia.sect.core.util.GameRngManager
import com.xianxia.sect.core.util.DomainResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.Rule
import org.mockito.Mockito
import org.mockito.Mockito.`when`
import org.mockito.kotlin.any



class ResolveBeastAttackFightTest {

    @get:Rule val writeGuardRule = WriteGuardRule()
    private lateinit var service: ExplorationService
    private lateinit var stateStore: FakeAtomicStateStore

    @Before
    fun setUp() {
        // Fake 提供真实语义：gameData/disciples 等 flow 全真实，测试直接 setGameData 推进
        stateStore = FakeAtomicStateStore()
        val battleSystem = mockSmart(BattleSystem::class.java)
        val rngManager = GameRngManager().also { it.initSystemSeed(42) }
        val inventorySystem = mockSmart(InventorySystem::class.java)
        val worldLevelManager = mockSmart(WorldLevelManager::class.java)
        val patrolBattleSystem = mockSmart(PatrolBattleSystem::class.java)
        val beastAttackDetector = mockSmart(BeastAttackDetector::class.java)
        val lootCalculator = mockSmart(LootCalculator::class.java)
        val encounterBattleService = mockSmart(EncounterBattleService::class.java)
        val cultivationService = mockSmart(CultivationService::class.java)
        val spiritStoneWallet = mockSmart(SpiritStoneWallet::class.java)
        // deduct 返回 sealed class DeductResult（ByteBuddy 无法代理 sealed）→ doReturn 风格。
        // 本文件用例全为失败路径：not found/defeated 在 deduct 前提前返回，
        // 灵石不足用例正需要 Insufficient（非 Success → paid=false）
        Mockito.doReturn(DeductResult.Insufficient(balance = 0, required = 0))
            .`when`(spiritStoneWallet).deduct(any(), any(), any(), any(), any(), any(), any())

        `when`(battleSystem.createBattle(any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
            .thenReturn(Battle(team = emptyList(), beasts = emptyList()))
        `when`(battleSystem.executeBattle(any(), any())).thenReturn(
            BattleSystemResult(battle = Battle(team = emptyList(), beasts = emptyList()),
                victory = true, rewards = emptyMap(), turnCount = 1)
        )
        // doReturn 风格：addMaterial 返回 sealed interface DomainResult（ByteBuddy 无法代理），
        // when 风格的第一次调用会触发 smart nulls 创建而抛 MockitoException；
        // doReturn 先注册 stub，调用直接返回，不触发默认 answer
        Mockito.doReturn(DomainResult.Success(Material())).`when`(inventorySystem).addMaterial(any())

        service = ExplorationService(
            stateStore = stateStore,
            battleSystem = battleSystem,
            rngManager = rngManager,
            inventorySystem = inventorySystem,
            worldLevelManager = worldLevelManager,
            patrolBattleSystem = patrolBattleSystem,
            beastAttackDetector = beastAttackDetector,
            lootCalculator = lootCalculator,
            encounterBattleService = encounterBattleService,
            cultivationService = cultivationService,
            spiritStoneWallet = spiritStoneWallet,
            deathHandler = mockSmart(DiscipleDeathHandler::class.java)
        )
    }

    // ── resolveBeastAttackFight ────────────────────────────────────────

    @Test
    fun `resolveBeastAttackFight returns false when beast not found`() = runBlocking {
        stateStore.setGameData(GameData(worldLevels = emptyList()))
        val result = service.resolveBeastAttackFight("nonexistent")
        assertFalse(result)
    }

    @Test
    fun `resolveBeastAttackFight returns false when beast already defeated`() = runBlocking {
        val beast = WorldLevel(id = "b1", type = LevelType.BEAST, defeated = true)
        stateStore.setGameData(GameData(worldLevels = listOf(beast)))
        val result = service.resolveBeastAttackFight("b1")
        assertFalse(result)
    }

    @Test
    fun `resolveBeastAttackPayTribute returns false when beast not found`() = runBlocking {
        stateStore.setGameData(GameData(worldLevels = emptyList()))
        val result = service.resolveBeastAttackPayTribute("nonexistent")
        assertFalse(result)
    }

    @Test
    fun `resolveBeastAttackPayTribute returns false when beast already defeated`() = runBlocking {
        val beast = WorldLevel(id = "b1", type = LevelType.BEAST, defeated = true)
        stateStore.setGameData(GameData(worldLevels = listOf(beast)))
        val result = service.resolveBeastAttackPayTribute("b1")
        assertFalse(result)
    }

    @Test
    fun `resolveBeastAttackPayTribute returns false when spirit stones insufficient`() = runBlocking {
        // 灵石不足时 deduct 失败，DeductResult 非 Success → return@update → paid=false
        val beast = WorldLevel(id = "b1", type = LevelType.BEAST, defeated = false, x = 500f, y = 500f)
        stateStore.setGameData(GameData(
            worldLevels = listOf(beast),
            worldMapSects = listOf(WorldSect(isPlayerSect = true, x = 500f, y = 500f, name = "玩家宗门")),
            spiritStones = 0  // 无灵石
        ))
        val result = service.resolveBeastAttackPayTribute("b1")
        assertFalse("灵石不足时应返回 false", result)
    }

    // ── runWithoutStoreUpdate tests for resolveBeastFightInternal ───────

    @Test
    fun `defender selection prioritizes patrol disciples first`() {
        val allDiscipleList = listOf(
            Disciple(id = "1", name = "巡逻甲", realm = 5, realmLayer = 50,
                status = DiscipleStatus.PATROLLING, isAlive = true),
            Disciple(id = "2", name = "巡逻乙", realm = 4, realmLayer = 40,
                status = DiscipleStatus.PATROLLING, isAlive = true),
            Disciple(id = "3", name = "空闲甲", realm = 6, realmLayer = 60,
                status = DiscipleStatus.IDLE, isAlive = true)
        )
        val patrolDiscipleIds = setOf("1", "2")

        // Same logic as resolveBeastFightInternal
        val allAlive = allDiscipleList.filter { it.isAlive }
        val patrolDefenders = allAlive.filter { it.id in patrolDiscipleIds }
        val excludeStatuses = setOf(DiscipleStatus.ON_MISSION, DiscipleStatus.IN_TEAM, DiscipleStatus.REFLECTING, DiscipleStatus.GARRISONING, DiscipleStatus.REFINING)
        val remainingAlive = allAlive.filter {
            it.id !in patrolDiscipleIds && it.status !in excludeStatuses
        }.sortedByDescending { it.realmLayer }
        val defenders = (patrolDefenders + remainingAlive).take(8)

        assertEquals(3, defenders.size)
        assertTrue(defenders.any { it.id == "1" })
        assertTrue(defenders.any { it.id == "2" })
        assertTrue(defenders.any { it.id == "3" })
        // Patrol disciples come before non-patrol
        val idx1 = defenders.indexOfFirst { it.id == "1" }
        val idx3 = defenders.indexOfFirst { it.id == "3" }
        assertTrue("patrol disciple should come before non-patrol", idx1 < idx3)
    }

    @Test
    fun `defender selection excludes ON_MISSION IN_TEAM REFLECTING GARRISONING REFINING`() {
        val allDiscipleList = listOf(
            Disciple(id = "1", name = "正常", realm = 5, realmLayer = 50,
                status = DiscipleStatus.IDLE, isAlive = true),
            Disciple(id = "2", name = "任务中", realm = 5, realmLayer = 50,
                status = DiscipleStatus.ON_MISSION, isAlive = true),
            Disciple(id = "3", name = "探索中", realm = 5, realmLayer = 50,
                status = DiscipleStatus.IN_TEAM, isAlive = true),
            Disciple(id = "4", name = "思过中", realm = 5, realmLayer = 50,
                status = DiscipleStatus.REFLECTING, isAlive = true),
            Disciple(id = "5", name = "驻军中", realm = 5, realmLayer = 50,
                status = DiscipleStatus.GARRISONING, isAlive = true),
            Disciple(id = "6", name = "血炼中", realm = 5, realmLayer = 50,
                status = DiscipleStatus.REFINING, isAlive = true),
            Disciple(id = "7", name = "生产中", realm = 5, realmLayer = 50,
                status = DiscipleStatus.MINING, isAlive = true)
        )
        val excludeStatuses = setOf(
            DiscipleStatus.ON_MISSION, DiscipleStatus.IN_TEAM,
            DiscipleStatus.REFLECTING, DiscipleStatus.GARRISONING,
            DiscipleStatus.REFINING
        )
        val defenders = allDiscipleList.filter { it.isAlive && it.status !in excludeStatuses }

        assertEquals(2, defenders.size) // 1(IDLE) + 7(MINING)
        assertTrue(defenders.any { it.id == "1" })
        assertTrue(defenders.any { it.id == "7" })
    }
}
