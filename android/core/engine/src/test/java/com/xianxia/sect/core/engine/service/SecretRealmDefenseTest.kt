package com.xianxia.sect.core.engine.service

import com.xianxia.sect.core.GameConfig
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
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.kotlin.any
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner

/**
 * 远古秘境"对抗性审查防御"测试——独立类（避免 SecretRealmServiceTest 超 detekt LargeClass 阈值）。
 *
 * 覆盖：方向选择体力耗尽自动结束、方向事件防重复选择、旧档 BRIDGE 兼容、
 * 篡改档妖兽数量极值防 DoS、结束结算非法物品防护（方向事件功能对抗性审查修复）。
 */
@org.junit.experimental.categories.Category(com.xianxia.sect.core.RobolectricTests::class)
@RunWith(RobolectricTestRunner::class)
class SecretRealmDefenseTest {

    private lateinit var rngManager: GameRngManager
    private lateinit var battleSystem: BattleSystem
    private lateinit var inventorySystem: com.xianxia.sect.core.engine.system.InventorySystem
    private lateinit var spiritStoneWallet: com.xianxia.sect.core.wallet.SpiritStoneWallet
    private lateinit var service: SecretRealmService
    private lateinit var tables: DiscipleTables

    private val fixedRng = DeterministicRng.fromSeed(20260731L)

    @get:Rule
    val writeGuardRule = WriteGuardRule()

    @Before
    fun setUp() {
        rngManager = mock(GameRngManager::class.java)
        `when`(rngManager.getRng(RngPartition.SECRET_REALM)).thenReturn(fixedRng)
        battleSystem = mock(BattleSystem::class.java)
        inventorySystem = mock(com.xianxia.sect.core.engine.system.InventorySystem::class.java)
        // withTrackingSource 透传 block（否则结算的物品操作不执行）
        whenever(
            inventorySystem.withTrackingSource<Any>(any(), any())
        ).thenAnswer { inv ->
            inv.getArgument<() -> Any>(1).invoke()
        }
        spiritStoneWallet = mock(com.xianxia.sect.core.wallet.SpiritStoneWallet::class.java)
        service = SecretRealmService(
            rngManager = rngManager,
            battleSystem = battleSystem,
            inventorySystem = inventorySystem,
            spiritStoneWallet = spiritStoneWallet,
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

    /** 构造 4 名弟子 + 秘境 + 会话（直接写 GameData，模拟 startSession 后的状态） */
    private fun setupActiveSession(state: MutableGameState, stamina: Int = 20): List<String> {
        val ids = (1..4).map { insertDisciple(it, realm = 6 - (it % 3)).id }
        state.gameData = state.gameData.copy(
            secretRealmState = SecretRealmState(id = "realm_1", spawnYear = 1),
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
                    eventType = SecretRealmEventType.BEAST_ENCOUNTER.name,
                    title = "遭遇妖兽",
                    description = "途中遭遇妖兽",
                    options = listOf(
                        com.xianxia.sect.core.model.SecretRealmOption("远离妖兽", ""),
                        com.xianxia.sect.core.model.SecretRealmOption("发起战斗", ""),
                        com.xianxia.sect.core.model.SecretRealmOption("尝试偷袭", "")
                    ),
                    params = com.xianxia.sect.core.model.SecretRealmEventParams(
                        beastTypeName = "虎妖", beastRealm = 5, beastCount = 2
                    )
                )
            )
        )
        return ids
    }

    /** 构造固定胜利的战斗结果 */
    private fun victoryBattle(ids: List<String>): BattleSystemResult {
        val combatants = ids.map { id ->
            Combatant(
                id = id, name = "弟子", hp = 800, maxHp = 1000, mp = 100, maxMp = 200,
                physicalAttack = 100, magicAttack = 80, physicalDefense = 60,
                magicDefense = 50, speed = 40, critRate = 0.1, skills = emptyList()
            )
        }
        val battle = Battle(team = combatants, beasts = emptyList(), turn = 3, isFinished = true, winner = null)
        return BattleSystemResult(
            battle = battle, victory = true,
            rewards = mapOf("spiritStones" to 500),
            log = BattleLogData(rounds = emptyList()),
            turnCount = 3
        )
    }

    private fun stubBattle(result: BattleSystemResult) {
        whenever(battleSystem.createBattle(any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
            .thenReturn(result.battle)
        whenever(battleSystem.executeBattleWithTimeout(any(), any(), any())).thenReturn(result)
    }

    // ── 方向事件对抗性审查防御 ────────────────────────────────────────

    @Test
    fun `chooseOption - 方向选择体力耗尽自动结束探索`() {
        val state = createState()
        val ids = setupActiveSession(state, stamina = 2)
        stubBattle(victoryBattle(ids))
        whenever(inventorySystem.addMaterial(any()))
            .thenReturn(
                com.xianxia.sect.core.util.DomainResult.Success(
                    com.xianxia.sect.core.model.Material()
                )
            )
        // 战斗（扣 1）→ 探索方向事件（体力 1），会话未结束
        val result = service.chooseOption(1, state)
        assertTrue(result.isSuccess)
        assertFalse(result.isSessionEnded)
        val session = state.gameData.secretRealmSession
        assertEquals(1, session.stamina)
        assertEquals(SecretRealmEventType.DIRECTION_CHOICE.name, session.currentEvent?.eventType)
        // 选择方向（扣最后 1 体力）→ 体力耗尽自动结束，秘境消失
        val directionResult = service.chooseOption(0, state)
        assertTrue(directionResult.isSuccess)
        assertTrue(directionResult.isSessionEnded)
        assertFalse(state.gameData.secretRealmState.exists)
        assertFalse(state.gameData.secretRealmSession.isActive)
    }

    @Test
    fun `chooseOption - 方向事件已标记时防重复选择`() {
        val state = createState()
        val ids = setupActiveSession(state)
        stubBattle(victoryBattle(ids))
        service.chooseOption(1, state)
        // 篡改档：方向事件已标记选择
        state.gameData = state.gameData.copy(
            secretRealmSession = state.gameData.secretRealmSession.copy(
                currentEvent = state.gameData.secretRealmSession.currentEvent?.copy(
                    chosenOptionIndex = 0
                )
            )
        )
        val result = service.chooseOption(0, state)
        assertTrue(result is SecretRealmChoiceResult.Error)
        assertEquals(
            "事件已处理，请勿重复选择",
            (result as SecretRealmChoiceResult.Error).message
        )
    }

    @Test
    fun `chooseOption - 旧档 BRIDGE 事件按方向事件语义结算`() {
        val state = createState()
        setupActiveSession(state)
        // 40f24e79 前旧档的 BRIDGE 事件："BRIDGE" 字符串不在枚举内，
        // valueOf 兜底进方向事件分支；旧三选项索引 0/1/2 全部有效
        state.gameData = state.gameData.copy(
            secretRealmSession = state.gameData.secretRealmSession.copy(
                currentEvent = com.xianxia.sect.core.model.SecretRealmEventRecord(
                    eventType = "BRIDGE",
                    title = "探索方向",
                    description = "战斗结束！，请选择探索方向",
                    options = listOf(
                        com.xianxia.sect.core.model.SecretRealmOption("走左路", ""),
                        com.xianxia.sect.core.model.SecretRealmOption("直线前进", ""),
                        com.xianxia.sect.core.model.SecretRealmOption("走右路", "")
                    )
                )
            )
        )
        val result = service.chooseOption(0, state)
        assertTrue(result.isSuccess)
        val session = state.gameData.secretRealmSession
        // 按方向事件语义结算：扣 1 体力、结果文本含方向名、进入下一真实事件
        assertEquals(19, session.stamina)
        assertTrue(session.resultMessage.contains("左路"))
        assertTrue(
            session.currentEvent?.eventType in setOf(
                SecretRealmEventType.BEAST_ENCOUNTER.name,
                SecretRealmEventType.REST_AREA.name,
                SecretRealmEventType.RUIN_EXPLORE.name
            )
        )
    }

    @Test
    fun `chooseOption - 篡改档妖兽数量极值战斗不崩溃且掉落按上限`() {
        val state = createState()
        val ids = setupActiveSession(state)
        // 篡改档：beastCount = Int.MAX——rollBeastLoot 的 repeat(beastCount*2) 未经 clamp
        // 会溢出负数崩溃 / 上亿次循环卡死引擎线程（对抗性审查 M3）
        val currentEvent = requireNotNull(state.gameData.secretRealmSession.currentEvent) {
            "setupActiveSession 应构造当前事件"
        }
        state.gameData = state.gameData.copy(
            secretRealmSession = state.gameData.secretRealmSession.copy(
                currentEvent = currentEvent.copy(
                    params = currentEvent.params.copy(beastCount = Int.MAX_VALUE)
                )
            )
        )
        stubBattle(victoryBattle(ids))
        val result = service.chooseOption(1, state)
        assertTrue(result.isSuccess)
        // clamp 到上限 6：不崩溃、不卡死，掉落按 6 只（12 件材料）入背包，进入探索方向事件
        assertEquals(
            SecretRealmEventType.DIRECTION_CHOICE.name,
            state.gameData.secretRealmSession.currentEvent?.eventType
        )
        assertEquals(
            GameConfig.SecretRealm.BEAST_COUNT_MAX * 2,
            state.gameData.secretRealmSession.backpack.materials.size
        )
    }

    @Test
    fun `endSession - 篡改档非正数量物品跳过结算不崩溃`() {
        val state = createState()
        setupActiveSession(state)
        val invalid = com.xianxia.sect.core.model.Material(
            id = "neg_1", name = "负数量材料", rarity = 2,
            description = "", category = com.xianxia.sect.core.model.MaterialCategory.BEAST_BONE,
            quantity = -1
        )
        state.gameData = state.gameData.copy(
            secretRealmSession = state.gameData.secretRealmSession.copy(
                backpack = com.xianxia.sect.core.model.SecretRealmBackpack(materials = listOf(invalid))
            )
        )
        service.endSession(state)
        // 非法物品在调用 addXxx 前被过滤（防 addXxx 异常回滚软锁，对抗性审查 B-L2）
        verify(inventorySystem, never()).addMaterial(invalid)
        assertFalse(state.gameData.secretRealmState.exists)
        assertFalse(state.gameData.secretRealmSession.isActive)
    }
}
