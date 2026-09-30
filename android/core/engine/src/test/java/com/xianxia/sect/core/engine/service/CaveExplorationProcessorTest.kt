package com.xianxia.sect.core.engine.service

import com.xianxia.sect.core.engine.FakeAtomicStateStore
import com.xianxia.sect.core.engine.mockSmart
import com.xianxia.sect.core.engine.system.InventorySystem
import com.xianxia.sect.core.model.Disciple
import com.xianxia.sect.core.model.GameData
import com.xianxia.sect.core.model.WorldSect
import com.xianxia.sect.core.state.DiscipleTables
import com.xianxia.sect.core.state.EntityStore
import com.xianxia.sect.core.state.MutableGameState
import com.xianxia.sect.core.wallet.SpiritStoneWallet
import org.junit.Assert.*
import org.junit.Test

class CaveExplorationProcessorTest {

    /**
     * AI 随机源注入（**必须**）。
     *
     * [com.xianxia.sect.core.engine.domain.diplomacy.AISectDiscipleManager] 是进程级
     * `object`，随机源解析为注入的 `GameRngManager`（R5：禁止自建随机源）。年度招募
     * 路径会生成 AI 弟子 ⇒ 不注入会解析到其他测试类残留的实例并抛 NPE（跨类顺序
     * 相关 flaky）。固定种子实例同时保证生成结果确定可复现。
     */
    @org.junit.Before
    fun setUpAiRng() {
        com.xianxia.sect.core.engine.domain.diplomacy.AISectDiscipleManager.initialize(
            com.xianxia.sect.core.util.GameRngManager().also { it.initSystemSeed(AI_RNG_SEED) }
        )
    }

    @org.junit.After
    fun tearDownAiRng() {
        com.xianxia.sect.core.engine.domain.diplomacy.AISectDiscipleManager.resetManagerForTest()
    }

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

        val enemies = AISectBattleProcessor.buildDefenseBattleEnemies(
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

        val enemies = AISectBattleProcessor.buildDefenseBattleEnemies(
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

        val enemies = AISectBattleProcessor.buildDefenseBattleEnemies(
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

        val enemies = AISectBattleProcessor.buildDefenseBattleEnemies(
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

        val enemies = AISectBattleProcessor.buildDefenseBattleEnemies(
            survivingAttackers = survivors,
            deadAttackerIds = emptyList(),
            sectDisciplePool = sectPool,
            attackerSectName = "天剑宗"
        )

        assertEquals("天剑宗弟子", enemies[0].name)
    }

    @Test
    fun `buildDefenseBattleEnemies - 空宗门池+空参战者 返回空列表`() {
        val enemies = AISectBattleProcessor.buildDefenseBattleEnemies(
            survivingAttackers = emptyList(),
            deadAttackerIds = emptyList(),
            sectDisciplePool = emptyList(),
            attackerSectName = "空宗门"
        )

        assertTrue(enemies.isEmpty())
    }

    private val processor: CaveExplorationProcessor by lazy { createProcessor() }

    private fun createProcessor(): CaveExplorationProcessor {
        return CaveExplorationProcessor(
            stateStore = FakeAtomicStateStore(),
            inventorySystem = mockSmart(InventorySystem::class.java),
            // W4-B/B4 死链清理：battleSystem/eventProcessor/analyticsTracker/deathHandler
            // 参数已随洞府探索链删除
            spiritStoneWallet = mockSmart(SpiritStoneWallet::class.java),
            aiSectBattleProcessor = mockSmart(AISectBattleProcessor::class.java)
        )
    }

    private fun createState(
        aiSectDisciples: Map<String, List<Disciple>> = emptyMap(),
        worldMapSects: List<WorldSect> = emptyList()
    ): MutableGameState {
        val tables = DiscipleTables()
        tables.writeAllowed = true
        return MutableGameState(
            gameData = GameData(
                aiSectDisciples = aiSectDisciples,
                worldMapSects = worldMapSects
            ),
            discipleTables = tables,
            equipmentInstances = EntityStore(),
            manualStacks = EntityStore(),
            manualInstances = EntityStore(),
            pills = EntityStore(),
            materials = EntityStore(),
            herbs = EntityStore(),
            seeds = EntityStore(),
            storageBags = EntityStore(),
                        battleLogs = emptyList(),
            isPaused = false,
            isLoading = false,
            isSaving = false
        )
    }

    @Test
    fun `processSectDisciplesAging - AI 宗门弟子列表原样写回`() {
        val state = createState(
            aiSectDisciples = mapOf(
                "ai1" to listOf(makeDisciple(id = "ai_d1", realm = 9))
            ),
            worldMapSects = listOf(
                WorldSect(id = "player", isPlayerSect = true),
                WorldSect(id = "ai1")
            )
        )
        processor.processSectDisciplesAging(5, state)
        val aged = state.gameData.aiSectDisciples["ai1"]
        assertEquals(1, aged?.size)
        assertEquals("ai_d1", aged?.singleOrNull()?.id)
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

    private companion object {
        /** 本类 AI 流固定种子（消除跨类顺序依赖；年度招募生成确定可复现） */
        const val AI_RNG_SEED = 20260914L
    }
}
