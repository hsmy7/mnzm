package com.xianxia.sect.core.engine.service

import com.xianxia.sect.core.engine.domain.battle.Battle
import com.xianxia.sect.core.engine.domain.battle.BattleLogData
import com.xianxia.sect.core.engine.domain.battle.BattleSystem
import com.xianxia.sect.core.engine.domain.battle.BattleSystemResult
import com.xianxia.sect.core.engine.domain.battle.Combatant
import com.xianxia.sect.core.engine.domain.disciple.DiscipleAssignmentGate
import com.xianxia.sect.core.engine.domain.exploration.SecretRealmChoiceResult
import com.xianxia.sect.core.model.CombatAttributes
import com.xianxia.sect.core.model.Disciple
import com.xianxia.sect.core.model.DiscipleStatus
import com.xianxia.sect.core.model.GameData
import com.xianxia.sect.core.model.SecretRealmAIMember
import com.xianxia.sect.core.model.SecretRealmAITeam
import com.xianxia.sect.core.model.SecretRealmEventType
import com.xianxia.sect.core.model.SecretRealmState
import com.xianxia.sect.core.model.SkillStats
import com.xianxia.sect.core.state.DiscipleTables
import com.xianxia.sect.core.state.EntityStore
import com.xianxia.sect.core.state.MutableGameState
import com.xianxia.sect.core.state.WriteGuardRule
import com.xianxia.sect.core.util.DeterministicRng
import com.xianxia.sect.core.util.GameRngManager
import com.xianxia.sect.core.util.RngPartition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock
import org.mockito.kotlin.any
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner

/**
 * 秘境"遭遇 AI 宗门探索队伍"事件（AI_SECT_ENCOUNTER）单元测试：
 * 避让必成功、交战胜利品阶/件数、战败损失、全灭 WIPEOUT、对方全灭直通。
 */
@RunWith(RobolectricTestRunner::class)
class SecretRealmAIEncounterTest {

    private lateinit var battleSystem: BattleSystem
    private lateinit var service: SecretRealmService
    private lateinit var tables: DiscipleTables

    @get:org.junit.Rule
    val writeGuardRule = WriteGuardRule()

    @Before
    fun setUp() {
        val rngManager = mock(GameRngManager::class.java)
        `when`(rngManager.getRng(RngPartition.SECRET_REALM)).thenReturn(DeterministicRng.fromSeed(20260731L))
        battleSystem = mock(BattleSystem::class.java)
        val inventorySystem = mock(com.xianxia.sect.core.engine.system.InventorySystem::class.java)
        whenever(
            inventorySystem.withTrackingSource<Any>(any(), any())
        ).thenAnswer { inv ->
            inv.getArgument<() -> Any>(1).invoke()
        }
        service = SecretRealmService(
            rngManager = rngManager,
            battleSystem = battleSystem,
            inventorySystem = inventorySystem,
            spiritStoneWallet = mock(com.xianxia.sect.core.wallet.SpiritStoneWallet::class.java),
            overflowMailSender = mock(OverflowMailSender::class.java),
            assignmentGate = mock(DiscipleAssignmentGate::class.java)
        )
        tables = DiscipleTables()
    }

    private fun createState(): MutableGameState = MutableGameState(
        gameData = GameData(),
        discipleTables = tables,
        equipmentStacks = EntityStore(emptyList()),
        equipmentInstances = EntityStore(emptyList()),
        manualStacks = EntityStore(emptyList()),
        manualInstances = EntityStore(emptyList()),
        pills = EntityStore(emptyList()),
        materials = EntityStore(emptyList()),
        herbs = EntityStore(emptyList()),
        seeds = EntityStore(emptyList()),
        storageBags = EntityStore(emptyList()),
                battleLogs = emptyList(),
        isPaused = false,
        isLoading = false,
        isSaving = false
    )

    private fun insertDisciple(id: Int, realm: Int = 5, hp: Int = -1): Disciple {
        val disciple = Disciple(
            id = id.toString(),
            name = "弟子$id",
            realm = realm,
            realmLayer = 1,
            age = 25,
            lifespan = 90,
            skills = SkillStats(comprehension = 100),
            combat = CombatAttributes(currentHp = hp)
        )
        tables.insert(disciple)
        tables.isAlive[id] = 1
        tables.statuses[id] = DiscipleStatus.IDLE
        return disciple
    }

    private fun stubBattle(result: BattleSystemResult) {
        whenever(battleSystem.createBattle(any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
            .thenReturn(result.battle)
        whenever(battleSystem.executeBattleWithTimeout(any(), any(), any())).thenReturn(result)
    }

    private fun defeatBattle(ids: List<String>): BattleSystemResult {
        val combatants = ids.mapIndexed { index, id ->
            Combatant(
                id = id, name = "弟子",
                hp = if (index == 0) 200 else 0,  // 2~4 号阵亡
                maxHp = 1000, mp = 100, maxMp = 200,
                physicalAttack = 100, magicAttack = 80, physicalDefense = 60,
                magicDefense = 50, speed = 40, critRate = 0.1, skills = emptyList()
            )
        }
        val battle = Battle(team = combatants, beasts = emptyList(), turn = 5, isFinished = true, winner = null)
        return BattleSystemResult(
            battle = battle, victory = false, rewards = emptyMap(),
            log = BattleLogData(rounds = emptyList()), turnCount = 5
        )
    }

    /** 全员阵亡的战斗结果（全灭场景） */
    private fun wipeoutBattle(ids: List<String>): BattleSystemResult {
        val combatants = ids.map { id ->
            Combatant(
                id = id, name = "弟子", hp = 0, maxHp = 1000, mp = 100, maxMp = 200,
                physicalAttack = 100, magicAttack = 80, physicalDefense = 60,
                magicDefense = 50, speed = 40, critRate = 0.1, skills = emptyList()
            )
        }
        val battle = Battle(team = combatants, beasts = emptyList(), turn = 5, isFinished = true, winner = null)
        return BattleSystemResult(
            battle = battle, victory = false, rewards = emptyMap(),
            log = BattleLogData(rounds = emptyList()), turnCount = 5
        )
    }

    /** 构造 AI 遭遇当前事件 + AI 宗门弟子（默认存活） */
    private fun setupAIEncounterSession(
        state: MutableGameState,
        stamina: Int = 20,
        aiSectLevel: Int = 2,
        aiAlive: Boolean = true
    ): Pair<List<String>, String> {
        val ids = (1..4).map { insertDisciple(it, realm = 5).id }
        val aiId = "a1"
        val aiMembers = listOf(
            SecretRealmAIMember(discipleId = aiId, name = "剑尘", portraitRes = "", realm = 5)
        )
        val aiDisciple = Disciple(
            id = aiId, name = "剑尘", realm = 5, realmLayer = 1,
            age = 30, lifespan = 90,
            skills = SkillStats(comprehension = 100),
            combat = CombatAttributes(currentHp = -1)
        ).copy(isAlive = aiAlive)
        state.gameData = state.gameData.copy(
            secretRealmState = com.xianxia.sect.core.model.SecretRealmState(id = "realm_1", spawnYear = 1),
            secretRealmAITeams = listOf(
                SecretRealmAITeam(
                    id = "team_1", sectId = "sect1", sectName = "青云宗",
                    sectLevel = aiSectLevel, members = aiMembers
                )
            ),
            aiSectDisciples = mapOf("sect1" to listOf(aiDisciple)),
            secretRealmSession = state.gameData.secretRealmSession.copy(
                secretRealmId = "realm_1",
                members = ids.map { did ->
                    com.xianxia.sect.core.model.SecretRealmMemberState(
                        discipleId = did, name = "弟子", portraitRes = "",
                        realm = 5, realmName = "化神"
                    )
                },
                stamina = stamina,
                currentEvent = com.xianxia.sect.core.model.SecretRealmEventRecord(
                    eventType = SecretRealmEventType.AI_SECT_ENCOUNTER.name,
                    title = "遭遇青云宗探索队伍",
                    description = "前方发现青云宗的探索队伍，狭路相逢",
                    options = listOf(
                        com.xianxia.sect.core.model.SecretRealmOption("向左避让", ""),
                        com.xianxia.sect.core.model.SecretRealmOption("与之交战", ""),
                        com.xianxia.sect.core.model.SecretRealmOption("向右避让", "")
                    ),
                    params = com.xianxia.sect.core.model.SecretRealmEventParams(
                        aiSectId = "sect1", aiSectName = "青云宗",
                        aiSectLevel = aiSectLevel, aiMembers = aiMembers
                    )
                )
            )
        )
        return ids to aiId
    }

    /** stub convertDiscipleToCombatant（PvP 构建路径需要，默认 null 会污染 Battle 构造） */
    private fun stubConvertDiscipleToCombatant() {
        whenever(
            battleSystem.convertDiscipleToCombatant(
                any(), any(), any(), any(), any(), any(), any()
            )
        ).thenReturn(
            Combatant(
                id = "x", name = "x", hp = 1000, maxHp = 1000, mp = 100, maxMp = 200,
                physicalAttack = 100, magicAttack = 80, physicalDefense = 60,
                magicDefense = 50, speed = 40, critRate = 0.1, skills = emptyList()
            )
        )
    }

    /** 胜利战斗结果（beasts 含战死 AI 弟子 id 列表） */
    private fun aiVictoryBattle(ids: List<String>, deadAiIds: List<String>): BattleSystemResult {
        val combatants = ids.map { id ->
            Combatant(
                id = id, name = "弟子", hp = 800, maxHp = 1000, mp = 100, maxMp = 200,
                physicalAttack = 100, magicAttack = 80, physicalDefense = 60,
                magicDefense = 50, speed = 40, critRate = 0.1, skills = emptyList()
            )
        }
        val beasts = deadAiIds.map { id ->
            Combatant(
                id = id, name = "剑尘", hp = 0, maxHp = 1000, mp = 100, maxMp = 200,
                physicalAttack = 100, magicAttack = 80, physicalDefense = 60,
                magicDefense = 50, speed = 40, critRate = 0.1, skills = emptyList()
            )
        }
        val battle = Battle(team = combatants, beasts = beasts, turn = 3, isFinished = true, winner = null)
        return BattleSystemResult(
            battle = battle, victory = true, rewards = emptyMap(),
            log = BattleLogData(rounds = emptyList()), turnCount = 3
        )
    }

    @Test
    fun `chooseOption - AI 遭遇向左避让必成功接结束事件`() {
        val state = createState()
        setupAIEncounterSession(state)
        val result = service.chooseOption(0, state)
        assertTrue(result.isSuccess)
        // 无战斗：不调用战斗执行
        org.mockito.Mockito.verify(battleSystem, org.mockito.Mockito.never())
            .executeBattleWithTimeout(org.mockito.kotlin.any(), org.mockito.kotlin.any(), org.mockito.kotlin.any())
        val session = state.gameData.secretRealmSession
        assertEquals(19, session.stamina)
        assertTrue(session.resultMessage.contains("向左避让"))
        assertTrue(session.resultMessage.contains("青云宗"))
        // 接结束事件（探索方向）
        assertEquals(SecretRealmEventType.DIRECTION_CHOICE.name, session.currentEvent?.eventType)
        assertFalse(result.isSessionEnded)
        // 避让后队伍保留（擦肩而过，可能再遇）
        assertEquals(1, state.gameData.secretRealmAITeams.size)
    }

    @Test
    fun `chooseOption - AI 遭遇向右避让必成功接结束事件`() {
        val state = createState()
        setupAIEncounterSession(state)
        val result = service.chooseOption(2, state)
        assertTrue(result.isSuccess)
        org.mockito.Mockito.verify(battleSystem, org.mockito.Mockito.never())
            .executeBattleWithTimeout(org.mockito.kotlin.any(), org.mockito.kotlin.any(), org.mockito.kotlin.any())
        val session = state.gameData.secretRealmSession
        assertEquals(19, session.stamina)
        assertTrue(session.resultMessage.contains("向右避让"))
        assertEquals(SecretRealmEventType.DIRECTION_CHOICE.name, session.currentEvent?.eventType)
    }

    @Test
    fun `chooseOption - AI 遭遇交战胜利获得品阶区间物品且无灵石`() {
        val state = createState()
        val (ids, aiId) = setupAIEncounterSession(state, aiSectLevel = 1)
        stubConvertDiscipleToCombatant()
        stubBattle(aiVictoryBattle(ids, deadAiIds = listOf(aiId)))
        val result = service.chooseOption(1, state)
        assertTrue(result.isSuccess)
        assertTrue(result.isEnteredCombat)
        assertTrue(result.isVictory)
        val backpack = state.gameData.secretRealmSession.backpack
        // 胜利获得 1~15 件物品，品阶在宗门等级区间（中型 1：灵品~宝品 2..3）
        assertTrue("件数 ${backpack.totalItemCount} 超出 1..15", backpack.totalItemCount in 1..15)
        assertTrue(
            "品阶越界",
            backpack.allItemRarities().all { it in 2..3 }
        )
        // 奖励不含灵石
        assertEquals(0L, backpack.spiritStones)
        // 战死 AI 弟子已标记死亡 + 队伍从派遣池移除
        val aiDisciples = state.gameData.aiSectDisciples.getValue("sect1")
        assertEquals(false, aiDisciples.first { it.id == aiId }.isAlive)
        assertTrue(state.gameData.secretRealmAITeams.isEmpty())
        // 结算后进入探索方向事件
        assertEquals(
            SecretRealmEventType.DIRECTION_CHOICE.name,
            state.gameData.secretRealmSession.currentEvent?.eventType
        )
    }

    @Test
    fun `chooseOption - AI 遭遇交战战败损失背包物品`() {
        val state = createState()
        val (ids, _) = setupAIEncounterSession(state)
        // 预置 5 件背包物品（战败丢失 20%~45%：丢 1~3 件）
        state.gameData = state.gameData.copy(
            secretRealmSession = state.gameData.secretRealmSession.copy(
                backpack = com.xianxia.sect.core.model.SecretRealmBackpack(
                    materials = (1..5).map { i ->
                        com.xianxia.sect.core.model.Material(
                            id = "m$i", name = "虎骨$i", rarity = 2,
                            description = "", category = com.xianxia.sect.core.model.MaterialCategory.BEAST_BONE,
                            quantity = 1
                        )
                    }
                )
            )
        )
        stubConvertDiscipleToCombatant()
        stubBattle(defeatBattle(ids))
        val result = service.chooseOption(1, state)
        assertTrue(result.isSuccess)
        assertFalse(result.isVictory)
        val backpack = state.gameData.secretRealmSession.backpack
        assertTrue(
            "战败丢失后剩余 ${backpack.totalItemCount} 不在 2..4",
            backpack.totalItemCount in 2..4
        )
    }

    @Test
    fun `chooseOption - AI 遭遇交战全灭 WIPEOUT 自动结束`() {
        val state = createState()
        val (ids, _) = setupAIEncounterSession(state)
        // 全员濒死（保命机制已用尽），再战败 → 全员永久死亡
        state.gameData = state.gameData.copy(
            secretRealmSession = state.gameData.secretRealmSession.copy(
                members = state.gameData.secretRealmSession.members.map { m ->
                    m.copy(isDying = true)
                }
            )
        )
        stubConvertDiscipleToCombatant()
        stubBattle(wipeoutBattle(ids))
        val result = service.chooseOption(1, state)
        assertTrue(result.isSuccess)
        assertTrue(result.isSessionEnded)
        val success = result as SecretRealmChoiceResult.Success
        assertEquals(ids.toSet(), success.releasedMemberIds)
        assertEquals(4, success.deadIds.size)
        assertFalse(state.gameData.secretRealmState.exists)
    }

    @Test
    fun `chooseOption - AI 遭遇对方全灭直通无战斗`() {
        val state = createState()
        setupAIEncounterSession(state, aiAlive = false)
        val result = service.chooseOption(1, state)
        assertTrue(result.isSuccess)
        org.mockito.Mockito.verify(battleSystem, org.mockito.Mockito.never())
            .executeBattleWithTimeout(org.mockito.kotlin.any(), org.mockito.kotlin.any(), org.mockito.kotlin.any())
        val session = state.gameData.secretRealmSession
        assertEquals(19, session.stamina)
        assertTrue(session.resultMessage.contains("无力应战"))
        assertEquals(SecretRealmEventType.DIRECTION_CHOICE.name, session.currentEvent?.eventType)
    }
}

/** 背包全物品品阶（六类统一口径，供品阶区间断言） */
private fun com.xianxia.sect.core.model.SecretRealmBackpack.allItemRarities(): List<Int> =
    equipment.map { it.rarity } + manuals.map { it.rarity } + pills.map { it.rarity } +
        materials.map { it.rarity } + herbs.map { it.rarity } + seeds.map { it.rarity }
