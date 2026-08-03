package com.xianxia.sect.core.engine.service

import com.xianxia.sect.core.engine.SectWarehouseManager
import com.xianxia.sect.core.engine.domain.battle.AttackWarningService
import com.xianxia.sect.core.engine.domain.battle.BattleSystem
import com.xianxia.sect.core.exploration.DiscipleDeathHandler
import com.xianxia.sect.core.engine.system.InventorySystem
import com.xianxia.sect.core.model.Disciple
import com.xianxia.sect.core.model.GameData
import com.xianxia.sect.core.model.WorldSect
import com.xianxia.sect.core.perf.ThermalMonitor
import com.xianxia.sect.core.state.DiscipleTables
import com.xianxia.sect.core.state.EntityStore
import com.xianxia.sect.core.state.GameStateStore
import com.xianxia.sect.core.state.MutableGameState
import com.xianxia.sect.core.util.AnalyticsTracker
import com.xianxia.sect.core.util.CoroutineScopeProvider
import com.xianxia.sect.core.util.GameRngManager
import com.xianxia.sect.core.wallet.SpiritStoneWallet
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito.mock

class CaveExplorationProcessorTest {

    // ── buildDefenseBattleEnemies 测试 ──

    @Test
    fun `buildDefenseBattleEnemies - 全宗门200弟子但仅10人参战 敌人列表仅含10人`() {
        // 模拟：攻击方宗门有200名弟子
        val sectPool = (0 until 200).map { i ->
            makeDisciple(id = "attacker_$i", realm = (i % 10))
        }
        // 仅前10人参战，3人阵亡
        val survivingAttackers = sectPool.take(7).map {
            it.copy(combat = it.combat.copy(currentHp = 300))
        }
        val deadAttackerIds = sectPool.slice(7 until 10).map { it.id }

        val enemies = CaveExplorationProcessor.buildDefenseBattleEnemies(
            survivingAttackers = survivingAttackers,
            deadAttackerIds = deadAttackerIds,
            sectDisciplePool = sectPool,
            attackerSectName = "测试宗门"
        )

        // 核心断言：敌人列表应仅为10名参战弟子，而非200名
        assertEquals(10, enemies.size)
    }

    @Test
    fun `buildDefenseBattleEnemies - 幸存者 isAlive=true hp为实际值`() {
        val sectPool = listOf(
            makeDisciple(id = "a1", realm = 5),
            makeDisciple(id = "a2", realm = 5)
        )
        val survivors = listOf(
            sectPool[0].copy(combat = sectPool[0].combat.copy(currentHp = 450))
        )
        val deadIds = listOf("a2")

        val enemies = CaveExplorationProcessor.buildDefenseBattleEnemies(
            survivingAttackers = survivors,
            deadAttackerIds = deadIds,
            sectDisciplePool = sectPool,
            attackerSectName = "测试宗门"
        )

        assertEquals(2, enemies.size)
        val survivor = checkNotNull(enemies.find { it.id == "a1" })
        assertTrue(survivor.isAlive)
        assertEquals(450, survivor.hp)

        val dead = checkNotNull(enemies.find { it.id == "a2" })
        assertFalse(dead.isAlive)
        assertEquals(0, dead.hp)
    }

    @Test
    fun `buildDefenseBattleEnemies - 全部幸存 敌人列表仅含幸存者`() {
        val sectPool = (0 until 100).map { i ->
            makeDisciple(id = "attacker_$i", realm = (i % 10))
        }
        val survivors = sectPool.take(10).map {
            it.copy(combat = it.combat.copy(currentHp = 500))
        }
        val deadIds = emptyList<String>()

        val enemies = CaveExplorationProcessor.buildDefenseBattleEnemies(
            survivingAttackers = survivors,
            deadAttackerIds = deadIds,
            sectDisciplePool = sectPool,
            attackerSectName = "测试宗门"
        )

        assertEquals(10, enemies.size)
        assertTrue(enemies.all { it.isAlive })
        assertTrue(enemies.all { it.hp > 0 })
    }

    @Test
    fun `buildDefenseBattleEnemies - 全部阵亡 敌人列表仅含阵亡者`() {
        val sectPool = (0 until 150).map { i ->
            makeDisciple(id = "attacker_$i", realm = (i % 10))
        }
        val survivors = emptyList<Disciple>()
        val deadIds = sectPool.take(10).map { it.id }

        val enemies = CaveExplorationProcessor.buildDefenseBattleEnemies(
            survivingAttackers = survivors,
            deadAttackerIds = deadIds,
            sectDisciplePool = sectPool,
            attackerSectName = "测试宗门"
        )

        assertEquals(10, enemies.size)
        assertTrue(enemies.none { it.isAlive })
        assertTrue(enemies.all { it.hp == 0 })
    }

    @Test
    fun `buildDefenseBattleEnemies - name字段包含宗门名`() {
        val sectPool = listOf(makeDisciple(id = "a1", realm = 3))
        val survivors = listOf(sectPool[0])

        val enemies = CaveExplorationProcessor.buildDefenseBattleEnemies(
            survivingAttackers = survivors,
            deadAttackerIds = emptyList(),
            sectDisciplePool = sectPool,
            attackerSectName = "天剑宗"
        )

        assertEquals("天剑宗弟子", enemies[0].name)
    }

    @Test
    fun `buildDefenseBattleEnemies - 空宗门池+空参战者 返回空列表`() {
        val enemies = CaveExplorationProcessor.buildDefenseBattleEnemies(
            survivingAttackers = emptyList(),
            deadAttackerIds = emptyList(),
            sectDisciplePool = emptyList(),
            attackerSectName = "空宗门"
        )

        assertTrue(enemies.isEmpty())
    }

    // ── 年变单事务内快照覆写回归测试 ──
    // 背景：年变事件单事务化后，processSectDisciplesYearlyRecruitment 曾读已提交
    // 快照（stateStore.gameData.value）覆盖事务 buffer，导致 refreshRecruitList
    // 追加的新弟子丢失（招募列表每3年不刷新）。修复后必须基于 buffer 读写。

    private val processor: CaveExplorationProcessor by lazy { createProcessor() }

    private fun createProcessor(): CaveExplorationProcessor {
        return CaveExplorationProcessor(
            stateStore = mock(GameStateStore::class.java),
            inventorySystem = mock(InventorySystem::class.java),
            scopeProvider = mock(CoroutineScopeProvider::class.java),
            battleSystem = mock(BattleSystem::class.java),
            eventProcessor = mock(CultivationEventProcessor::class.java),
            analyticsTracker = mock(AnalyticsTracker::class.java),
            thermalMonitor = mock(ThermalMonitor::class.java),
            attackWarningService = mock(AttackWarningService::class.java),
            sectWarehouseManager = mock(SectWarehouseManager::class.java),
            cultivationService = mock(CultivationService::class.java),
            spiritStoneWallet = mock(SpiritStoneWallet::class.java),
            rngManager = mock(GameRngManager::class.java),
            deathHandler = mock(DiscipleDeathHandler::class.java)
        )
    }

    private fun createState(
        recruitList: List<Disciple> = emptyList(),
        aiSectDisciples: Map<String, List<Disciple>> = emptyMap(),
        worldMapSects: List<WorldSect> = emptyList()
    ): MutableGameState {
        val tables = DiscipleTables()
        tables.writeAllowed = true
        return MutableGameState(
            gameData = GameData(
                recruitList = recruitList,
                aiSectDisciples = aiSectDisciples,
                worldMapSects = worldMapSects
            ),
            discipleTables = tables,
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
    }

    @Test
    fun `processSectDisciplesYearlyRecruitment - 同事务内 refreshRecruitList 追加的弟子不被覆盖`() {
        val initialRecruit = makeDisciple(id = "recruit_old", realm = 9)
        val freshRecruits = listOf(
            makeDisciple(id = "recruit_fresh_1", realm = 9),
            makeDisciple(id = "recruit_fresh_2", realm = 9)
        )
        val state = createState(
            recruitList = listOf(initialRecruit),
            aiSectDisciples = mapOf(
                "ai1" to listOf(makeDisciple(id = "ai_d1", realm = 9))
            ),
            worldMapSects = listOf(
                WorldSect(id = "player", isPlayerSect = true),
                WorldSect(id = "ai1", isPlayerOccupied = true)
            )
        )
        // 模拟年变单事务内 refreshRecruitList 的追加（buffer 操作）
        state.gameData = state.gameData.copy(
            recruitList = state.gameData.recruitList + freshRecruits
        )
        // 随后调用被修复函数：必须基于同一 buffer 读写，不得用已提交快照覆盖
        processor.processSectDisciplesYearlyRecruitment(4, state)
        val finalIds = state.gameData.recruitList.map { it.id }
        assertTrue(
            "refreshRecruitList 追加的弟子被覆盖丢失: $finalIds",
            finalIds.containsAll(freshRecruits.map { it.id })
        )
        assertTrue("初始弟子丢失: $finalIds", finalIds.contains("recruit_old"))
    }

    @Test
    fun `processSectDisciplesYearlyRecruitment - 无占领宗门时保留现有招募列表`() {
        val initialRecruit = makeDisciple(id = "recruit_old", realm = 9)
        val freshRecruits = listOf(makeDisciple(id = "recruit_fresh_1", realm = 9))
        val state = createState(
            recruitList = listOf(initialRecruit),
            aiSectDisciples = mapOf(
                "ai1" to listOf(makeDisciple(id = "ai_d1", realm = 9))
            ),
            worldMapSects = listOf(
                WorldSect(id = "player", isPlayerSect = true),
                WorldSect(id = "ai1")
            )
        )
        state.gameData = state.gameData.copy(
            recruitList = state.gameData.recruitList + freshRecruits
        )
        processor.processSectDisciplesYearlyRecruitment(4, state)
        val finalIds = state.gameData.recruitList.map { it.id }
        // 未被占领的 AI 宗门不产生招募俘虏，列表必须保留 refreshRecruitList 的追加
        assertEquals(setOf("recruit_old", "recruit_fresh_1"), finalIds.toSet())
    }

    @Test
    fun `processSectDisciplesAging - AI 宗门弟子老化结果写入 buffer`() {
        val state = createState(
            aiSectDisciples = mapOf(
                "ai1" to listOf(makeDisciple(id = "ai_d1", realm = 9).copy(age = 30))
            ),
            worldMapSects = listOf(
                WorldSect(id = "player", isPlayerSect = true),
                WorldSect(id = "ai1")
            )
        )
        processor.processSectDisciplesAging(5, state)
        val aged = state.gameData.aiSectDisciples["ai1"]
        assertEquals(1, aged?.size)
        assertEquals(31, aged?.singleOrNull()?.age)
    }

    // ── 辅助方法 ──

    private fun makeDisciple(
        id: String,
        realm: Int = 9,
        isAlive: Boolean = true
    ): Disciple {
        return Disciple(
            id = id,
            realm = realm,
            isAlive = isAlive
        )
    }
}
