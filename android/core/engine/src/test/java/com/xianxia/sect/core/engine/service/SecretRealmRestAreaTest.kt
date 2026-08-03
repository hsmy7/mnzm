package com.xianxia.sect.core.engine.service

import com.xianxia.sect.core.model.CombatAttributes
import com.xianxia.sect.core.model.Disciple
import com.xianxia.sect.core.model.DiscipleAggregate
import com.xianxia.sect.core.model.DiscipleStats
import com.xianxia.sect.core.model.DiscipleStatsProvider
import com.xianxia.sect.core.model.DiscipleStatus
import com.xianxia.sect.core.model.EquipmentInstance
import com.xianxia.sect.core.model.GameData
import com.xianxia.sect.core.model.ManualInstance
import com.xianxia.sect.core.model.ManualProficiencyData
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
 * 远古秘境"平坦空地"事件测试——独立类（避免 SecretRealmServiceTest 超 detekt LargeClass 阈值）。
 *
 * 覆盖：选择方向后 30% 概率生成空地事件、原地休整恢复（濒死/半血/满血/死亡/幽灵成员）、
 * 继续前进成员不变、休整不进入战斗、非法 eventType 回退方向事件分支。
 */
@RunWith(RobolectricTestRunner::class)
class SecretRealmRestAreaTest {

    private lateinit var rngManager: GameRngManager
    private lateinit var service: SecretRealmService
    private lateinit var tables: DiscipleTables

    private val fixedRng = DeterministicRng.fromSeed(20260731L)

    @get:org.junit.Rule
    val writeGuardRule = WriteGuardRule()

    @Before
    fun setUp() {
        rngManager = mock(GameRngManager::class.java)
        `when`(rngManager.getRng(RngPartition.SECRET_REALM)).thenReturn(fixedRng)
        val battleSystem = mock(com.xianxia.sect.core.engine.domain.battle.BattleSystem::class.java)
        val inventorySystem = mock(com.xianxia.sect.core.engine.system.InventorySystem::class.java)
        whenever(
            inventorySystem.withTrackingSource<Any>(any(), any())
        ).thenAnswer { inv ->
            inv.getArgument<() -> Any>(1).invoke()
        }
        val spiritStoneWallet = mock(com.xianxia.sect.core.wallet.SpiritStoneWallet::class.java)
        service = SecretRealmService(
            rngManager = rngManager,
            battleSystem = battleSystem,
            inventorySystem = inventorySystem,
            spiritStoneWallet = spiritStoneWallet
        )
        tables = DiscipleTables()
        // 注册装配 provider（休整恢复按装配 maxHp 计算；固定 maxHp=1000 便于断言）
        DiscipleAggregate.statsProvider = object : DiscipleStatsProvider {
            override fun getBaseStats(disciple: Disciple): DiscipleStats =
                DiscipleStats(hp = 1000, maxHp = 1000, mp = 200, maxMp = 200)
            override fun getBaseStats(aggregate: DiscipleAggregate): DiscipleStats =
                DiscipleStats(hp = 1000, maxHp = 1000, mp = 200, maxMp = 200)
            override fun getTalentEffects(disciple: Disciple): Map<String, Double> = emptyMap()
            override fun getTalentEffects(aggregate: DiscipleAggregate): Map<String, Double> = emptyMap()
            override fun getStatsWithEquipment(
                disciple: Disciple, equipments: Map<String, EquipmentInstance>
            ): DiscipleStats = DiscipleStats()
            override fun getStatsWithEquipment(
                aggregate: DiscipleAggregate, equipments: Map<String, EquipmentInstance>
            ): DiscipleStats = DiscipleStats()
            override fun getFinalStats(
                disciple: Disciple, equipments: Map<String, EquipmentInstance>,
                manuals: Map<String, ManualInstance>,
                manualProficiencies: Map<String, ManualProficiencyData>
            ): DiscipleStats = DiscipleStats()
            override fun getFinalStats(
                aggregate: DiscipleAggregate, equipments: Map<String, EquipmentInstance>,
                manuals: Map<String, ManualInstance>,
                manualProficiencies: Map<String, ManualProficiencyData>
            ): DiscipleStats = DiscipleStats()
            override fun calculateCultivationSpeed(
                disciple: Disciple, manuals: Map<String, ManualInstance>,
                manualProficiencies: Map<String, ManualProficiencyData>,
                buildingBonus: Double, additionalBonus: Double,
                preachingElderBonus: Double, preachingMastersBonus: Double,
                cultivationSubsidyBonus: Double, parentCultivationBonus: Double,
                griefCultivationSpeedPenalty: Double, masterDiscipleBonus: Double
            ): Double = 0.0
            override fun calculateCultivationSpeed(
                aggregate: DiscipleAggregate, manuals: Map<String, ManualInstance>,
                manualProficiencies: Map<String, ManualProficiencyData>,
                buildingBonus: Double, additionalBonus: Double,
                preachingElderBonus: Double, preachingMastersBonus: Double,
                cultivationSubsidyBonus: Double, parentCultivationBonus: Double,
                griefCultivationSpeedPenalty: Double, masterDiscipleBonus: Double
            ): Double = 0.0
            override fun getBreakthroughChance(
                disciple: Disciple, innerElderComprehension: Int, outerElderComprehension: Int,
                pillBonus: Double, adBonus: Double,
                griefBreakthroughPenalty: Double, masterDiscipleBonus: Double
            ): Double = 0.0
            override fun getBreakthroughChance(
                aggregate: DiscipleAggregate, innerElderComprehension: Int,
                outerElderComprehension: Int, pillBonus: Double, adBonus: Double,
                griefBreakthroughPenalty: Double, masterDiscipleBonus: Double
            ): Double = 0.0
        }
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
        teams = emptyList(),
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
    private fun setupActiveSession(state: MutableGameState): List<String> {
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
                stamina = 20,
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

    /** 构造空地事件（原地休整 / 继续前进） */
    private fun restAreaEvent(): com.xianxia.sect.core.model.SecretRealmEventRecord =
        com.xianxia.sect.core.model.SecretRealmEventRecord(
            eventType = SecretRealmEventType.REST_AREA.name,
            title = "发现空地",
            description = "发现一处平坦空地",
            options = listOf(
                com.xianxia.sect.core.model.SecretRealmOption("原地休整", "所有弟子恢复40%状态"),
                com.xianxia.sect.core.model.SecretRealmOption("继续前进", "不做停留，继续探索")
            )
        )

    // ── 空地事件 ──────────────────────────────────────────────────────

    @Test
    fun `chooseOption - 空地休整后进入探索方向事件且体力扣减`() {
        val state = createState()
        setupActiveSession(state)
        state.gameData = state.gameData.copy(
            secretRealmSession = state.gameData.secretRealmSession.copy(
                currentEvent = restAreaEvent()
            )
        )
        val result = service.chooseOption(0, state)
        assertTrue(result.isSuccess)
        val session = state.gameData.secretRealmSession
        // 休整结算后进入探索方向事件（结束选项）
        assertEquals(SecretRealmEventType.DIRECTION_CHOICE.name, session.currentEvent?.eventType)
        assertEquals("探索方向", session.currentEvent?.title)
        assertEquals(
            listOf("向左走", "走中间", "向右走"),
            session.currentEvent?.options?.map { it.label }
        )
        assertTrue(session.currentEvent?.description?.contains("请选择探索方向") == true)
        assertEquals(19, session.stamina)
        assertTrue(session.resultMessage.isNotEmpty())
        // 选择方向（扣 1 体力）后进入下一真实事件
        val directionResult = service.chooseOption(0, state)
        assertTrue(directionResult.isSuccess)
        val afterDirection = state.gameData.secretRealmSession
        assertTrue(
            afterDirection.currentEvent?.eventType in setOf(
                SecretRealmEventType.BEAST_ENCOUNTER.name,
                SecretRealmEventType.REST_AREA.name,
                SecretRealmEventType.RUIN_EXPLORE.name
            )
        )
        assertEquals(18, afterDirection.stamina)
    }

    @Test
    fun `chooseOption - 空地休整恢复弟子血量与濒死状态并写回表`() {
        val state = createState()
        setupActiveSession(state)
        // 1 号濒死（1 血）、2 号半血（500）、3 号满血（-1）、4 号死亡
        tables.isAlive[4] = 0
        state.gameData = state.gameData.copy(
            secretRealmSession = state.gameData.secretRealmSession.copy(
                currentEvent = restAreaEvent(),
                members = state.gameData.secretRealmSession.members.mapIndexed { index, m ->
                    when (index) {
                        0 -> m.copy(currentHp = 1, isDying = true)
                        1 -> m.copy(currentHp = 500)
                        2 -> m.copy(currentHp = -1)
                        else -> m.copy(currentHp = 0, isDead = true)
                    }
                }
            )
        )
        val result = service.chooseOption(0, state)
        assertTrue(result.isSuccess)
        val members = state.gameData.secretRealmSession.members
        // 1 号濒死：1 + 1000×40% = 401，脱离濒死
        assertEquals(401, members[0].currentHp)
        assertFalse(members[0].isDying)
        assertEquals(401, tables.currentHps[1])
        // 2 号半血：500 + 400 = 900
        assertEquals(900, members[1].currentHp)
        assertEquals(900, tables.currentHps[2])
        // 3 号满血：不恢复
        assertEquals(-1, members[2].currentHp)
        // 4 号死亡：不恢复
        assertTrue(members[3].isDead)
        // 休整结算后进入探索方向事件
        assertEquals(
            SecretRealmEventType.DIRECTION_CHOICE.name,
            state.gameData.secretRealmSession.currentEvent?.eventType
        )
    }

    @Test
    fun `chooseOption - 空地休整满血封顶写满血标记`() {
        val state = createState()
        setupActiveSession(state)
        // 1 号 900 血：900 + 400 = 1300 → 封顶 1000（满血 -1）
        state.gameData = state.gameData.copy(
            secretRealmSession = state.gameData.secretRealmSession.copy(
                currentEvent = restAreaEvent(),
                members = state.gameData.secretRealmSession.members.mapIndexed { index, m ->
                    if (index == 0) m.copy(currentHp = 900) else m.copy(currentHp = -1)
                }
            )
        )
        val result = service.chooseOption(0, state)
        assertTrue(result.isSuccess)
        val members = state.gameData.secretRealmSession.members
        assertEquals(-1, members[0].currentHp)
        assertEquals(1000, tables.currentHps[1])
    }

    @Test
    fun `chooseOption - 空地继续前进成员不变并进入探索方向事件`() {
        val state = createState()
        setupActiveSession(state)
        // 1 号濒死：继续前进不触发任何恢复
        state.gameData = state.gameData.copy(
            secretRealmSession = state.gameData.secretRealmSession.copy(
                currentEvent = restAreaEvent(),
                members = state.gameData.secretRealmSession.members.mapIndexed { index, m ->
                    if (index == 0) m.copy(currentHp = 1, isDying = true) else m
                }
            )
        )
        val result = service.chooseOption(1, state)
        assertTrue(result.isSuccess)
        val session = state.gameData.secretRealmSession
        val members = session.members
        assertEquals(1, members[0].currentHp)
        assertTrue(members[0].isDying)
        // 继续前进结算后进入探索方向事件
        assertEquals(SecretRealmEventType.DIRECTION_CHOICE.name, session.currentEvent?.eventType)
        assertEquals("你方不做停留，继续探索，请选择探索方向", session.currentEvent?.description)
        assertEquals(19, session.stamina)
    }

    @Test
    fun `chooseOption - 空地休整不进入战斗`() {
        val state = createState()
        setupActiveSession(state)
        state.gameData = state.gameData.copy(
            secretRealmSession = state.gameData.secretRealmSession.copy(
                currentEvent = restAreaEvent()
            )
        )
        val result = service.chooseOption(0, state)
        assertTrue(result.isSuccess)
        val success = result as com.xianxia.sect.core.engine.domain.exploration.SecretRealmChoiceResult.Success
        assertFalse(success.enteredCombat)
        assertEquals(null, success.combatLog)
    }

    @Test
    fun `chooseOption - 表中找不到弟子时休整跳过该成员`() {
        val state = createState()
        setupActiveSession(state)
        // 篡改档：成员 4 指向不存在的弟子
        state.gameData = state.gameData.copy(
            secretRealmSession = state.gameData.secretRealmSession.copy(
                currentEvent = restAreaEvent(),
                members = state.gameData.secretRealmSession.members.mapIndexed { index, m ->
                    if (index == 3) m.copy(discipleId = "9999", currentHp = 100) else m
                }
            )
        )
        val result = service.chooseOption(0, state)
        assertTrue(result.isSuccess)
        val members = state.gameData.secretRealmSession.members
        // 幽灵成员原样保留（未被恢复、未崩溃）
        assertEquals("9999", members[3].discipleId)
        assertEquals(100, members[3].currentHp)
    }

    @Test
    fun `chooseOption - 篡改档极大血量不溢出且收敛到已知上限`() {
        val state = createState()
        setupActiveSession(state)
        // 篡改档：currentHp = Int.MAX_VALUE（Int 加法会回绕为负数写坏真值表）
        state.gameData = state.gameData.copy(
            secretRealmSession = state.gameData.secretRealmSession.copy(
                currentEvent = restAreaEvent(),
                members = state.gameData.secretRealmSession.members.mapIndexed { index, m ->
                    if (index == 0) m.copy(currentHp = Int.MAX_VALUE) else m.copy(currentHp = -1)
                }
            )
        )
        val result = service.chooseOption(0, state)
        assertTrue(result.isSuccess)
        val members = state.gameData.secretRealmSession.members
        // 收敛到装配 maxHp=1000（满血 -1），不产生溢出负值
        assertEquals(-1, members[0].currentHp)
        assertEquals(1000, tables.currentHps[1])
    }

    @Test
    fun `chooseOption - 战斗口径 maxHp 恢复装备弟子到战斗上限`() {
        val state = createState()
        setupActiveSession(state)
        // 成员 maxHp=1500（战斗写回维护，含装备加成）、当前 1350
        state.gameData = state.gameData.copy(
            secretRealmSession = state.gameData.secretRealmSession.copy(
                currentEvent = restAreaEvent(),
                members = state.gameData.secretRealmSession.members.mapIndexed { index, m ->
                    if (index == 0) m.copy(currentHp = 1350, maxHp = 1500) else m.copy(currentHp = -1)
                }
            )
        )
        val result = service.chooseOption(0, state)
        assertTrue(result.isSuccess)
        val members = state.gameData.secretRealmSession.members
        // 1350 + 1500×40% = 1950 → 封顶 1500（满血 -1），表写 1500
        assertEquals(-1, members[0].currentHp)
        assertEquals(1500, tables.currentHps[1])
    }

    @Test
    fun `chooseOption - 旧档缺战斗 maxHp 时恢复不写坏真值表`() {
        val state = createState()
        setupActiveSession(state)
        // 旧档成员无 maxHp（0）：curHp 超过基础装配 maxHp（1000）时收敛处理，表不被压低
        tables.currentHps[1] = 1350
        state.gameData = state.gameData.copy(
            secretRealmSession = state.gameData.secretRealmSession.copy(
                currentEvent = restAreaEvent(),
                members = state.gameData.secretRealmSession.members.mapIndexed { index, m ->
                    if (index == 0) m.copy(currentHp = 1350) else m.copy(currentHp = -1)
                }
            )
        )
        val result = service.chooseOption(0, state)
        assertTrue(result.isSuccess)
        val members = state.gameData.secretRealmSession.members
        // 收敛到基础 maxHp=1000（满血 -1），表取 max(1000, 1350) 不降
        assertEquals(-1, members[0].currentHp)
        assertEquals(1350, tables.currentHps[1])
    }

    @Test
    fun `chooseOption - 休整写表只增不减防篡改档压低表级血量`() {
        val state = createState()
        setupActiveSession(state)
        // 成员声称 10 血（篡改），表真值 500：休整按成员值算 10+400=410，表不得被压低
        tables.currentHps[1] = 500
        state.gameData = state.gameData.copy(
            secretRealmSession = state.gameData.secretRealmSession.copy(
                currentEvent = restAreaEvent(),
                members = state.gameData.secretRealmSession.members.mapIndexed { index, m ->
                    if (index == 0) m.copy(currentHp = 10) else m.copy(currentHp = -1)
                }
            )
        )
        val result = service.chooseOption(0, state)
        assertTrue(result.isSuccess)
        val members = state.gameData.secretRealmSession.members
        assertEquals(410, members[0].currentHp)
        assertEquals(500, tables.currentHps[1])
    }

    @Test
    fun `chooseOption - 濒死满血矛盾数据仍可脱离濒死`() {
        val state = createState()
        setupActiveSession(state)
        // 篡改档：isDying=true 且 currentHp=-1（满血哨兵，矛盾数据）——按濒死保底 1 血归一恢复
        state.gameData = state.gameData.copy(
            secretRealmSession = state.gameData.secretRealmSession.copy(
                currentEvent = restAreaEvent(),
                members = state.gameData.secretRealmSession.members.mapIndexed { index, m ->
                    if (index == 0) m.copy(currentHp = -1, isDying = true) else m.copy(currentHp = -1)
                }
            )
        )
        val result = service.chooseOption(0, state)
        assertTrue(result.isSuccess)
        val members = state.gameData.secretRealmSession.members
        assertEquals(401, members[0].currentHp)
        assertFalse(members[0].isDying)
        assertEquals(401, tables.currentHps[1])
    }

    @Test
    fun `chooseOption - 非法事件类型回退方向事件分支`() {
        val state = createState()
        setupActiveSession(state)
        val mockRng = mock(DeterministicRng::class.java)
        `when`(mockRng.nextDouble()).thenReturn(0.9)
        `when`(rngManager.getRng(RngPartition.SECRET_REALM)).thenReturn(mockRng)
        state.gameData = state.gameData.copy(
            secretRealmSession = state.gameData.secretRealmSession.copy(
                currentEvent = restAreaEvent().copy(eventType = "HACKED")
            )
        )
        val result = service.chooseOption(0, state)
        assertTrue(result.isSuccess)
        val session = state.gameData.secretRealmSession
        // 回退方向事件分支：HACKED 事件按方向事件语义直接结算——
        // 消费一次 nextDouble（=0.9 → 妖兽分支），扣 1 体力，结果文本含方向名
        assertEquals(19, session.stamina)
        assertTrue(session.resultMessage.contains("左路"))
        assertEquals(
            SecretRealmEventType.BEAST_ENCOUNTER.name,
            session.currentEvent?.eventType
        )
    }
}
