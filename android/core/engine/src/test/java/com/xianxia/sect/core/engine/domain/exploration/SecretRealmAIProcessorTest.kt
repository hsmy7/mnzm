package com.xianxia.sect.core.engine.domain.exploration

import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.model.CombatAttributes
import com.xianxia.sect.core.model.Disciple
import com.xianxia.sect.core.model.GameData
import com.xianxia.sect.core.model.SecretRealmState
import com.xianxia.sect.core.model.SkillStats
import com.xianxia.sect.core.state.EntityStore
import com.xianxia.sect.core.state.MutableGameState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SecretRealmAIProcessorTest {

    private val processor = SecretRealmAIProcessor()

    private fun createState(): MutableGameState = MutableGameState(
        gameData = GameData(),
        discipleTables = com.xianxia.sect.core.state.DiscipleTables(),
        equipmentStacks = EntityStore(emptyList()),
        equipmentInstances = EntityStore(emptyList()),
        manualStacks = EntityStore(emptyList()),
        manualInstances = EntityStore(emptyList()),
        pills = EntityStore(emptyList()),
        materials = EntityStore(emptyList()),
        herbs = EntityStore(emptyList()),
        seeds = EntityStore(emptyList()),
        storageBags = EntityStore(emptyList()),
        teams = emptyList(),
        battleLogs = emptyList(),
        isPaused = false,
        isLoading = false,
        isSaving = false
    )

    private fun aiDisciple(id: String, realm: Int): Disciple = Disciple(
        id = id,
        name = "AI弟子$id",
        realm = realm,
        realmLayer = 1,
        age = 30,
        lifespan = 90,
        skills = SkillStats(comprehension = 100),
        statusData = emptyMap(),
        combat = CombatAttributes(currentHp = -1)
    )

    @Test
    fun `processMonthlyAiTeams - 无秘境时不做任何事`() {
        val state = createState()
        processor.processMonthlyAiTeams(state)
        assertTrue(state.gameData.secretRealmAITeams.isEmpty())
    }

    @Test
    fun `processMonthlyAiTeams - 有弟子宗门派遣境界最高 4 人`() {
        val state = createState()
        state.gameData = state.gameData.copy(
            secretRealmState = SecretRealmState(id = "r1"),
            aiSectDisciples = mapOf(
                "sect1" to listOf(
                    aiDisciple("a1", realm = 2),
                    aiDisciple("a2", realm = 5),
                    aiDisciple("a3", realm = 3),
                    aiDisciple("a4", realm = 4),
                    aiDisciple("a5", realm = 6),
                    aiDisciple("a6", realm = 1)
                )
            ),
            worldMapSects = listOf(
                com.xianxia.sect.core.model.WorldSect(id = "sect1", name = "青云宗")
            )
        )
        processor.processMonthlyAiTeams(state)
        val teams = state.gameData.secretRealmAITeams
        assertEquals(1, teams.size)
        val team = teams.first()
        assertEquals("青云宗", team.sectName)
        assertEquals(GameConfig.SecretRealm.AI_TEAM_SIZE, team.members.size)
        // 境界最高 4 人：realm 数值最小 = a6(1), a1(2), a3(3), a4(4)
        assertEquals(listOf("a6", "a1", "a3", "a4"), team.members.map { it.discipleId })
    }

    @Test
    fun `processMonthlyAiTeams - 幂等去重：已派遣宗门不再重复派遣`() {
        val state = createState()
        state.gameData = state.gameData.copy(
            secretRealmState = SecretRealmState(id = "r1"),
            aiSectDisciples = mapOf(
                "sect1" to listOf(aiDisciple("a1", realm = 3), aiDisciple("a2", realm = 4))
            )
        )
        processor.processMonthlyAiTeams(state)
        processor.processMonthlyAiTeams(state)
        assertEquals(1, state.gameData.secretRealmAITeams.size)
    }

    @Test
    fun `processMonthlyAiTeams - 无存活弟子的宗门不派遣`() {
        val state = createState()
        val dead = aiDisciple("a1", realm = 1).copy(isAlive = false)
        state.gameData = state.gameData.copy(
            secretRealmState = SecretRealmState(id = "r1"),
            aiSectDisciples = mapOf("sect1" to listOf(dead))
        )
        processor.processMonthlyAiTeams(state)
        assertTrue(state.gameData.secretRealmAITeams.isEmpty())
    }
}
