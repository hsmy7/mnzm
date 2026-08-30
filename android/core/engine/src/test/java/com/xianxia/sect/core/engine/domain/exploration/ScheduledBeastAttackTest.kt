package com.xianxia.sect.core.engine.domain.exploration

import com.xianxia.sect.core.engine.FakeAtomicStateStore
import com.xianxia.sect.core.engine.mockSmart
import com.xianxia.sect.core.engine.domain.battle.Battle
import com.xianxia.sect.core.engine.domain.battle.BattleSystem
import com.xianxia.sect.core.engine.domain.battle.BattleSystemResult
import com.xianxia.sect.core.engine.service.CultivationService
import com.xianxia.sect.core.engine.system.InventorySystem
import com.xianxia.sect.core.exploration.BeastAttackDetector
import com.xianxia.sect.core.exploration.DiscipleDeathHandler
import com.xianxia.sect.core.exploration.LootCalculator
import com.xianxia.sect.core.exploration.PatrolBattleSystem
import com.xianxia.sect.core.exploration.WorldLevelManager
import com.xianxia.sect.core.domain.battle.EncounterBattleService
import com.xianxia.sect.core.model.Disciple
import com.xianxia.sect.core.model.DiscipleStatus
import com.xianxia.sect.core.model.GameData
import com.xianxia.sect.core.model.LevelType
import com.xianxia.sect.core.model.Material
import com.xianxia.sect.core.model.WorldLevel
import com.xianxia.sect.core.model.WorldSect
import com.xianxia.sect.core.state.PendingBeastAttack
import com.xianxia.sect.core.state.WriteGuardRule
import com.xianxia.sect.core.util.DomainResult
import com.xianxia.sect.core.util.GameRngManager
import com.xianxia.sect.core.wallet.SpiritStoneWallet
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.mockito.Mockito
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull

/**
 * 排期妖兽攻击自动执行测试（预警纯通知化：警告当月 → 下月结算自动防守战）。
 *
 * 守护目标：
 * 1. [ExplorationService.executeScheduledBeastAttack] — 单只排期攻击执行（不存在/已击败跳过，
 *    无防守弟子走掠夺路径，有弟子走战斗路径）
 * 2. [ExplorationService.processMonthlyWorldLevels] Step 0 — 上月排期在月度结算自动执行并清空，
 *    新检测照常写入新排期
 */
class ScheduledBeastAttackTest {

    @get:Rule val writeGuardRule = WriteGuardRule()
    private lateinit var service: ExplorationService
    private lateinit var stateStore: FakeAtomicStateStore
    private lateinit var battleSystem: BattleSystem
    private lateinit var beastAttackDetector: BeastAttackDetector

    @Before
    fun setUp() {
        stateStore = FakeAtomicStateStore()
        battleSystem = mockSmart(BattleSystem::class.java)
        beastAttackDetector = mockSmart(BeastAttackDetector::class.java)
        val rngManager = GameRngManager().also { it.initSystemSeed(42) }
        val inventorySystem = mockSmart(InventorySystem::class.java)
        val worldLevelManager = mockSmart(WorldLevelManager::class.java)
        val patrolBattleSystem = mockSmart(PatrolBattleSystem::class.java)
        val lootCalculator = mockSmart(LootCalculator::class.java)
        val encounterBattleService = mockSmart(EncounterBattleService::class.java)
        val cultivationService = mockSmart(CultivationService::class.java)
        val spiritStoneWallet = mockSmart(SpiritStoneWallet::class.java)

        // doReturn 风格：sealed 接口返回类型（DomainResult）ByteBuddy 无法代理；
        // BeastLootData 用 doReturn 直接注册避免 smart-null 代理
        Mockito.doReturn(DomainResult.Success(Material())).`when`(inventorySystem).addMaterial(any())
        Mockito.doReturn(LootCalculator.BeastLootData()).`when`(lootCalculator).computeLootPlan(any(), any())
        // withTrackingSource 是 InventorySystem 成员方法：mock 需执行 block 再返回其结果，
        // 否则返回 smart-null 导致 when(addR) 无分支命中
        Mockito.doAnswer { invocation ->
            @Suppress("UNCHECKED_CAST")
            (invocation.getArgument(1) as () -> Any).invoke()
        }.`when`(inventorySystem)
            .withTrackingSource<DomainResult<Material>>(any(), any())
        // 月度关卡管理保持原样返回（清理/刷新/移动零效果），隔离 Step 0 与检测逻辑；
        // playerAvgRealm 为可空 Int，需 anyOrNull() 匹配 null（any() 的 InstanceOf 不匹配 null）
        `when`(worldLevelManager.processMonthly(any(), anyOrNull()))
            .thenAnswer { invocation -> invocation.getArgument(0) as GameData }
        `when`(battleSystem.createBattle(any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
            .thenReturn(Battle(team = emptyList(), beasts = emptyList()))
        `when`(battleSystem.executeBattle(any(), any())).thenReturn(
            BattleSystemResult(battle = Battle(team = emptyList(), beasts = emptyList()),
                victory = true, rewards = emptyMap(), turnCount = 1)
        )

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

    private fun playerSectGameData(beasts: List<WorldLevel>): GameData {
        return GameData(
            worldLevels = beasts,
            worldMapSects = listOf(
                WorldSect(id = "s1", name = "玩家宗门", isPlayerSect = true)
            )
        )
    }

    private fun pending(beast: WorldLevel): PendingBeastAttack {
        return PendingBeastAttack(
            beastLevel = beast, targetSectId = "s1",
            targetSectName = "玩家宗门", distance = 1f
        )
    }

    // ── executeScheduledBeastAttack ──────────────────────────────────────

    @Test
    fun `executeScheduledBeastAttack - 关卡不存在返回 false`() {
        stateStore.setGameData(GameData(worldLevels = emptyList()))

        val result = stateStore.updateAndReturn {
            service.executeScheduledBeastAttack(this, "nonexistent")
        }

        assertFalse("关卡不存在时应返回 false", result)
        verify(battleSystem, never()).executeBattle(any(), any())
    }

    @Test
    fun `executeScheduledBeastAttack - 已击败返回 false 且不触发战斗`() {
        val beast = WorldLevel(id = "b1", type = LevelType.BEAST, defeated = true, beastName = "虎妖")
        stateStore.setGameData(playerSectGameData(listOf(beast)))

        val result = stateStore.updateAndReturn {
            service.executeScheduledBeastAttack(this, "b1")
        }

        assertFalse("已击败时应返回 false", result)
        verify(battleSystem, never()).executeBattle(any(), any())
    }

    @Test
    fun `executeScheduledBeastAttack - 无防守弟子走掠夺路径并返回 true`() {
        val beast = WorldLevel(id = "b1", type = LevelType.BEAST, defeated = false, beastName = "虎妖")
        stateStore.setGameData(playerSectGameData(listOf(beast)))

        val result = stateStore.updateAndReturn {
            service.executeScheduledBeastAttack(this, "b1")
        }

        assertTrue("存活妖兽应执行攻击", result)
        assertTrue("妖兽应标记击败", stateStore.gameData.value.worldLevels.first().defeated)
        val results = stateStore.gameData.value.pendingPatrolBattleResults
        assertTrue("应产生防守失败结果", results.any { !it.victory && it.isBeastDefense })
    }

    @Test
    fun `executeScheduledBeastAttack - 有防守弟子执行战斗并返回 true`() {
        val beast = WorldLevel(id = "b1", type = LevelType.BEAST, defeated = false, beastName = "虎妖")
        stateStore.setGameData(playerSectGameData(listOf(beast)))
        stateStore.update {
            discipleTables.insert(Disciple(
                id = "1", name = "防守甲", realm = 5, realmLayer = 50,
                status = DiscipleStatus.IDLE, isAlive = true
            ))
        }

        val result = stateStore.updateAndReturn {
            service.executeScheduledBeastAttack(this, "b1")
        }

        assertTrue("存活妖兽应执行攻击", result)
        assertTrue("妖兽应标记击败", stateStore.gameData.value.worldLevels.first().defeated)
        verify(battleSystem).executeBattle(any(), any())
        val results = stateStore.gameData.value.pendingPatrolBattleResults
        assertTrue("应产生防守胜利结果", results.any { it.victory && it.isBeastDefense })
    }

    // ── processMonthlyWorldLevels Step 0（排期自动执行 + 清空）──────────────

    @Test
    fun `processMonthlyWorldLevels - 有排期时自动执行战斗并清空排期`() {
        val beast = WorldLevel(id = "b1", type = LevelType.BEAST, defeated = false, beastName = "虎妖")
        stateStore.setGameData(playerSectGameData(listOf(beast)))
        stateStore.update {
            discipleTables.insert(Disciple(
                id = "1", name = "防守甲", realm = 5, realmLayer = 50,
                status = DiscipleStatus.IDLE, isAlive = true
            ))
        }
        stateStore.setPendingBeastAttacks(listOf(pending(beast)))

        stateStore.update { service.processMonthlyWorldLevels(this) }

        assertTrue("排期应已清空（弹窗随之自动关闭）", stateStore.pendingBeastAttacks.value.isEmpty())
        assertTrue("妖兽应已标记击败", stateStore.gameData.value.worldLevels.first().defeated)
        verify(battleSystem).executeBattle(any(), any())
    }

    @Test
    fun `processMonthlyWorldLevels - 排期妖兽已击败时跳过并清空排期`() {
        val beast = WorldLevel(id = "b1", type = LevelType.BEAST, defeated = true, beastName = "虎妖")
        stateStore.setGameData(playerSectGameData(listOf(beast)))
        stateStore.setPendingBeastAttacks(listOf(pending(beast)))

        stateStore.update { service.processMonthlyWorldLevels(this) }

        assertTrue("排期应已清空", stateStore.pendingBeastAttacks.value.isEmpty())
        verify(battleSystem, never()).executeBattle(any(), any())
    }

    @Test
    fun `processMonthlyWorldLevels - 无排期时零影响`() {
        val beast = WorldLevel(id = "b1", type = LevelType.BEAST, defeated = false, beastName = "虎妖")
        stateStore.setGameData(playerSectGameData(listOf(beast)))

        stateStore.update { service.processMonthlyWorldLevels(this) }

        assertTrue(stateStore.pendingBeastAttacks.value.isEmpty())
        assertFalse("无排期时妖兽不应被标记击败", stateStore.gameData.value.worldLevels.first().defeated)
        verify(battleSystem, never()).executeBattle(any(), any())
    }

    @Test
    fun `processMonthlyWorldLevels - 旧排期执行后检测仍写入新排期`() {
        val oldBeast = WorldLevel(id = "b1", type = LevelType.BEAST, defeated = false, beastName = "旧虎")
        val newBeast = WorldLevel(id = "b2", type = LevelType.BEAST, defeated = false, beastName = "新狼")
        stateStore.setGameData(playerSectGameData(listOf(oldBeast, newBeast)))
        stateStore.setPendingBeastAttacks(listOf(pending(oldBeast)))
        `when`(beastAttackDetector.detectAttacks(any()))
            .thenReturn(listOf(pending(newBeast)))

        stateStore.update { service.processMonthlyWorldLevels(this) }

        assertEquals(
            "新排期应写入（旧排期已被执行清空）",
            listOf("b2"),
            stateStore.pendingBeastAttacks.value.map { it.beastLevel.id }
        )
        assertTrue("旧排期妖兽应已标记击败", stateStore.gameData.value.worldLevels.first { it.id == "b1" }.defeated)
    }
}
