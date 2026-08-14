package com.xianxia.sect.core.engine.service

import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.engine.domain.battle.Battle
import com.xianxia.sect.core.engine.domain.disciple.DiscipleAssignmentGate
import com.xianxia.sect.core.engine.domain.battle.BattleLogData
import com.xianxia.sect.core.engine.domain.battle.BattleSystem
import com.xianxia.sect.core.engine.domain.battle.BattleSystemResult
import com.xianxia.sect.core.engine.domain.battle.Combatant
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
import org.robolectric.RobolectricTestRunner
import org.junit.runner.RunWith
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock
import org.mockito.kotlin.any
import org.mockito.kotlin.whenever


@org.junit.experimental.categories.Category(com.xianxia.sect.core.RobolectricTests::class)
@RunWith(RobolectricTestRunner::class)
class SecretRealmServiceTest {

    private lateinit var rngManager: GameRngManager
    private lateinit var battleSystem: BattleSystem
    private lateinit var inventorySystem: com.xianxia.sect.core.engine.system.InventorySystem
    private lateinit var spiritStoneWallet: com.xianxia.sect.core.wallet.SpiritStoneWallet
    private lateinit var overflowMailSender: OverflowMailSender
    private lateinit var assignmentGate: DiscipleAssignmentGate
    private lateinit var service: SecretRealmService
    private lateinit var tables: DiscipleTables

    private val fixedRng = DeterministicRng.fromSeed(20260731L)

    @get:org.junit.Rule
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
        // D-03 死亡统一入口透传：mock 需模拟真实 markDead 语义（否则死亡标记被吞）
        whenever(inventorySystem.materializeDiscipleBagAndMarkDead(any(), any(), any(), any()))
            .thenAnswer { inv ->
                val id = inv.getArgument<Int>(1)
                val year = inv.getArgument<Int>(2)
                val cause = inv.getArgument<String>(3)
                tables.markDead(id, year, cause)
            }
        spiritStoneWallet = mock(com.xianxia.sect.core.wallet.SpiritStoneWallet::class.java)
        overflowMailSender = mock(OverflowMailSender::class.java)
        assignmentGate = mock(DiscipleAssignmentGate::class.java)
        service = SecretRealmService(
            rngManager = rngManager,
            battleSystem = battleSystem,
            inventorySystem = inventorySystem,
            spiritStoneWallet = spiritStoneWallet,
            overflowMailSender = overflowMailSender,
            assignmentGate = assignmentGate
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

    private fun stubBattle(result: BattleSystemResult) {
        whenever(battleSystem.createBattle(any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
            .thenReturn(result.battle)
        whenever(battleSystem.executeBattleWithTimeout(any(), any(), any())).thenReturn(result)
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

    // ── 刷新 ──────────────────────────────────────────────────────────

    @Test
    fun `processYearlySpawn - 首次开启在第 50 年`() {
        // 从未出现过（cooldown=0）：第 50 年首次现世
        val state = createState()
        service.processYearlySpawn(year = 49, state = state)
        assertFalse(state.gameData.secretRealmState.exists)
        service.processYearlySpawn(year = 50, state = state)
        assertTrue(state.gameData.secretRealmState.exists)
        assertEquals(50, state.gameData.secretRealmState.spawnYear)
    }

    @Test
    fun `processYearlySpawn - 冷却差值不满 50 年时不刷新`() {
        val state = createState()
        state.gameData = state.gameData.copy(secretRealmCooldownYear = 100)
        val mockRng = mock(DeterministicRng::class.java)
        `when`(rngManager.getRng(RngPartition.SECRET_REALM)).thenReturn(mockRng)
        service.processYearlySpawn(year = 120, state = state)
        assertFalse(state.gameData.secretRealmState.exists)
    }

    @Test
    fun `processYearlySpawn - 冷却差值满 50 年时创建秘境`() {
        val state = createState()
        state.gameData = state.gameData.copy(secretRealmCooldownYear = 70)
        val mockRng = mock(DeterministicRng::class.java)
        `when`(mockRng.nextInt(org.mockito.ArgumentMatchers.anyInt())).thenReturn(1)
        `when`(rngManager.getRng(RngPartition.SECRET_REALM)).thenReturn(mockRng)
        service.processYearlySpawn(year = 120, state = state)
        assertTrue(state.gameData.secretRealmState.exists)
        assertEquals(120, state.gameData.secretRealmState.spawnYear)
    }

    @Test
    fun `processYearlySpawn - 冷却差值恰好 50 年时创建秘境`() {
        val state = createState()
        state.gameData = state.gameData.copy(secretRealmCooldownYear = 70)
        val mockRng = mock(DeterministicRng::class.java)
        `when`(mockRng.nextInt(org.mockito.ArgumentMatchers.anyInt())).thenReturn(1)
        `when`(rngManager.getRng(RngPartition.SECRET_REALM)).thenReturn(mockRng)
        service.processYearlySpawn(year = 120, state = state)
        assertTrue(state.gameData.secretRealmState.exists)
    }

    @Test
    fun `processYearlySpawn - 已存在秘境时不重复刷新`() {
        val state = createState()
        state.gameData = state.gameData.copy(secretRealmState = SecretRealmState(id = "r1"))
        service.processYearlySpawn(year = 1, state = state)
        assertEquals("r1", state.gameData.secretRealmState.id)
    }

    // ── 出发 ──────────────────────────────────────────────────────────

    @Test
    fun `startSession - 非 4 人队伍被拒绝`() {
        val state = createState()
        state.gameData = state.gameData.copy(secretRealmState = SecretRealmState(id = "r1"))
        val result = service.startSession(listOf("1", "2", "3"), state)
        assertTrue(result.isFailure)
        assertFalse(state.gameData.secretRealmSession.isActive)
    }

    @Test
    fun `startSession - 死亡弟子被拒绝`() {
        val state = createState()
        state.gameData = state.gameData.copy(secretRealmState = SecretRealmState(id = "r1"))
        val ids = (1..4).map { insertDisciple(it).id }
        tables.isAlive[2] = 0
        val result = service.startSession(ids, state)
        assertTrue(result.isFailure)
    }

    @Test
    fun `startSession - 成功写入会话与初始妖兽事件`() {
        val state = createState()
        state.gameData = state.gameData.copy(secretRealmState = SecretRealmState(id = "r1"))
        val ids = (1..4).map { insertDisciple(it, realm = 5).id }
        val result = service.startSession(ids, state)
        assertTrue(result.isSuccess)
        val session = state.gameData.secretRealmSession
        assertTrue(session.isActive)
        assertEquals(GameConfig.SecretRealm.STAMINA_MAX, session.stamina)
        assertEquals(SecretRealmEventType.BEAST_ENCOUNTER.name, session.currentEvent?.eventType)
        assertEquals(4, session.members.size)
    }

    // ── 选择选项 ──────────────────────────────────────────────────────

    @Test
    fun `chooseOption - 已处理事件标记防重复结算`() {
        val state = createState()
        val ids = setupActiveSession(state)
        stubBattle(victoryBattle(ids))
        val result = service.chooseOption(1, state)  // 发起战斗
        assertTrue(result.isSuccess)
        // 旧事件已标记选择并进入历史
        val history = state.gameData.secretRealmSession.eventHistory
        assertEquals(1, history.size)
        assertEquals(1, history.last().chosenOptionIndex)
        // 新事件（探索方向）未标记，可继续选择
        val next = state.gameData.secretRealmSession.currentEvent
        assertEquals(-1, next?.chosenOptionIndex)
    }

    @Test
    fun `chooseOption - 战斗胜利获得掉落并入背包且体力扣减`() {
        val state = createState()
        val ids = setupActiveSession(state)
        stubBattle(victoryBattle(ids))
        val result = service.chooseOption(1, state)
        assertTrue(result.isSuccess)
        assertTrue(result.isEnteredCombat)
        assertTrue(result.isVictory)
        val session = state.gameData.secretRealmSession
        assertEquals(19, session.stamina)
        assertEquals(500L, session.backpack.spiritStones)
        // 战斗结算后进入探索方向事件（结束选项），描述含战斗结果文本
        assertEquals(SecretRealmEventType.DIRECTION_CHOICE.name, session.currentEvent?.eventType)
        assertEquals("探索方向", session.currentEvent?.title)
        assertTrue(session.currentEvent?.description?.contains("战斗结束") == true)
        assertTrue(session.currentEvent?.description?.contains("请选择探索方向") == true)
        assertEquals(
            listOf("向左走", "走中间", "向右走"),
            session.currentEvent?.options?.map { it.label }
        )
        assertFalse(result.isSessionEnded)
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
        assertTrue(afterDirection.resultMessage.contains("左路"))
    }

    @Test
    fun `chooseOption - 战斗失败丢失物品且血量写回`() {
        val state = createState()
        val ids = setupActiveSession(state)
        // 预置背包物品
        state.gameData = state.gameData.copy(
            secretRealmSession = state.gameData.secretRealmSession.copy(
                backpack = com.xianxia.sect.core.model.SecretRealmBackpack(
                    spiritStones = 1000L,
                    materials = listOf(
                        com.xianxia.sect.core.model.Material(
                            id = "m1", name = "虎骨", rarity = 2,
                            description = "", category = com.xianxia.sect.core.model.MaterialCategory.BEAST_HIDE,
                            quantity = 1
                        )
                    )
                )
            )
        )
        stubBattle(defeatBattle(ids))
        val result = service.chooseOption(1, state)
        assertTrue(result.isSuccess)
        assertFalse(result.isVictory)
        val session = state.gameData.secretRealmSession
        // 1000 * 0.2~0.45 = 200~450 → 丢失 200~450，剩余 550~800
        assertTrue(session.backpack.spiritStones in 550L..800L)
        // 1 件物品按 20%~45% ceil 必丢 1 件
        assertEquals(0, session.backpack.totalItemCount)
    }

    @Test
    fun `chooseOption - 首次阵亡弟子进入重伤濒死而非永久死亡`() {
        val state = createState()
        val ids = setupActiveSession(state)
        stubBattle(defeatBattle(ids))
        service.chooseOption(1, state)
        val members = state.gameData.secretRealmSession.members
        // 2~4 号阵亡 → 濒死（isDying=true, isDead=false）；1 号幸存
        assertEquals(1, members.count { !it.isDying && !it.isDead })
        assertEquals(3, members.count { it.isDying })
        assertTrue(members.none { it.isDead })
        // 濒死弟子在表中 isAlive 仍为 1
        assertTrue(tables.isAlive[2] == 1)
    }

    @Test
    fun `chooseOption - 濒死弟子再次阵亡则永久死亡`() {
        val state = createState()
        val ids = setupActiveSession(state)
        // 将 2~4 号弟子标记为濒死（首次阵亡后的状态），随后打一场败仗
        state.gameData = state.gameData.copy(
            secretRealmSession = state.gameData.secretRealmSession.copy(
                members = state.gameData.secretRealmSession.members.mapIndexed { index, m ->
                    if (index > 0) m.copy(isDying = true) else m
                }
            )
        )
        // 濒死弟子参战血量 1，再次阵亡 → 永久死亡
        stubBattle(defeatBattle(ids))
        val result = service.chooseOption(1, state)
        assertTrue(result.isSuccess)
        val members = state.gameData.secretRealmSession.members
        assertEquals(3, members.count { it.isDead })
        // 永久死亡已 markDead（isAlive 置 0）
        assertTrue(tables.isAlive[2] == 0)
        assertTrue(tables.isAlive[3] == 0)
        assertTrue(tables.isAlive[4] == 0)
        // 1 号幸存者未标记死亡
        assertTrue(tables.isAlive[1] == 1)
    }

    @Test
    fun `chooseOption - 偷袭成功时回传 ambushSucceeded=true`() {
        val state = createState()
        val ids = setupActiveSession(state)
        stubBattle(victoryBattle(ids))
        // 偷袭判定：nextDouble() >= 0.5 视为成功
        val mockRng = mock(DeterministicRng::class.java)
        `when`(mockRng.nextDouble()).thenReturn(0.9)
        `when`(rngManager.getRng(RngPartition.SECRET_REALM)).thenReturn(mockRng)
        val result = service.chooseOption(2, state)  // 选项 2 = 尝试偷袭
        val success = result as SecretRealmChoiceResult.Success
        assertTrue(success.ambushSucceeded)
    }

    @Test
    fun `chooseOption - 偷袭失败时回传 ambushSucceeded=false`() {
        val state = createState()
        val ids = setupActiveSession(state)
        stubBattle(victoryBattle(ids))
        val mockRng = mock(DeterministicRng::class.java)
        `when`(mockRng.nextDouble()).thenReturn(0.4)  // < 0.5 判定失败
        `when`(rngManager.getRng(RngPartition.SECRET_REALM)).thenReturn(mockRng)
        val result = service.chooseOption(2, state)
        val success = result as SecretRealmChoiceResult.Success
        assertFalse(success.ambushSucceeded)
    }

    @Test
    fun `chooseOption - 体力归零自动结束探索并结算`() {
        val state = createState()
        val ids = setupActiveSession(state, stamina = 1)
        stubBattle(victoryBattle(ids))
        // 胜利掉落结算：mock addMaterial 返回 Success（否则 settleItem 收到 null）
        whenever(inventorySystem.addMaterial(org.mockito.kotlin.any()))
            .thenReturn(
                com.xianxia.sect.core.util.DomainResult.Success(
                    com.xianxia.sect.core.model.Material()
                )
            )
        val result = service.chooseOption(1, state)
        assertTrue(result.isSuccess)
        assertTrue(result.isSessionEnded)
        assertFalse(state.gameData.secretRealmState.exists)
        // 冷却年 = 当前游戏年（默认 GameData.gameYear = 1）
        assertEquals(1, state.gameData.secretRealmCooldownYear)
        assertFalse(state.gameData.secretRealmSession.isActive)
    }

    // ── 结束 ──────────────────────────────────────────────────────────

    @Test
    fun `endSession - 幂等：无会话无秘境时直接返回`() {
        val state = createState()
        service.endSession(state)
        assertFalse(state.gameData.secretRealmState.exists)
    }

    @Test
    fun `endSession - 结算背包灵石入钱包并清空秘境写入冷却年`() {
        val state = createState()
        setupActiveSession(state)
        state.gameData = state.gameData.copy(
            gameYear = 77,
            secretRealmSession = state.gameData.secretRealmSession.copy(
                backpack = com.xianxia.sect.core.model.SecretRealmBackpack(spiritStones = 300L)
            )
        )
        service.endSession(state)
        assertFalse(state.gameData.secretRealmState.exists)
        assertEquals(77, state.gameData.secretRealmCooldownYear)
        assertFalse(state.gameData.secretRealmSession.isActive)
        assertTrue(state.gameData.secretRealmAITeams.isEmpty())
        // 灵石已入钱包（SecretRealm 来源、下品）
        org.mockito.Mockito.verify(spiritStoneWallet).add(
            org.mockito.kotlin.any(),
            org.mockito.ArgumentMatchers.anyLong(),
            org.mockito.kotlin.eq(com.xianxia.sect.core.model.SpiritStoneGrade.LOW),
            org.mockito.kotlin.eq(com.xianxia.sect.core.wallet.SpiritStoneSource.SecretRealm),
            org.mockito.kotlin.any()
        )
    }

    @Test
    fun `chooseOption - 队伍全灭自动结束并返回释放成员`() {
        val state = createState()
        val ids = setupActiveSession(state)
        // 全部成员标记濒死（保命机制已用尽），再打一场败仗 → 全员永久死亡
        state.gameData = state.gameData.copy(
            secretRealmSession = state.gameData.secretRealmSession.copy(
                members = state.gameData.secretRealmSession.members.map { m ->
                    m.copy(isDying = true)
                }
            )
        )
        stubBattle(wipeoutBattle(ids))
        val result = service.chooseOption(1, state)
        assertTrue(result.isSuccess)
        assertTrue(result.isSessionEnded)
        val success = result as SecretRealmChoiceResult.Success
        assertEquals(ids.toSet(), success.releasedMemberIds)
        assertEquals(4, success.deadIds.size)
        // 全员已 markDead
        assertTrue((1..4).all { tables.isAlive[it] == 0 })
        // 秘境已消失（WIPEOUT 结束）
        assertFalse(state.gameData.secretRealmState.exists)
    }

    @Test
    fun `endSession - 结算失败物品转邮件补偿`() {
        val state = createState()
        setupActiveSession(state)
        // 篡改档场景：背包塞入非法材料，addMaterial 返回 Failure
        val invalid = com.xianxia.sect.core.model.Material(
            id = "invalid_1", name = "非法材料", rarity = 2,
            description = "", category = com.xianxia.sect.core.model.MaterialCategory.BEAST_BONE,
            quantity = 1
        )
        state.gameData = state.gameData.copy(
            secretRealmSession = state.gameData.secretRealmSession.copy(
                backpack = com.xianxia.sect.core.model.SecretRealmBackpack(
                    materials = listOf(invalid)
                )
            )
        )
        org.mockito.kotlin.whenever(inventorySystem.addMaterial(invalid))
            .thenReturn(
                com.xianxia.sect.core.util.DomainResult.Failure(
                    com.xianxia.sect.core.util.AppError.Domain.Storage.SlotNotFound()
                )
            )
        service.endSession(state)
        // Failure 分支：转邮件补偿（source="secret_realm"）
        org.mockito.Mockito.verify(inventorySystem).sendOverflowMail(
            org.mockito.kotlin.eq("secret_realm"),
            org.mockito.kotlin.eq("material"),
            org.mockito.kotlin.eq("非法材料"),
            org.mockito.ArgumentMatchers.anyInt(),
            org.mockito.ArgumentMatchers.anyInt()
        )
    }

    @Test
    fun `endSession - 仓库满失败不重复补偿邮件`() {
        val state = createState()
        setupActiveSession(state)
        // 仓库满：addMaterial 返回 Failure(Inventory.Full)，addXxx 内部已按整件转邮件
        val full = com.xianxia.sect.core.model.Material(
            id = "full_1", name = "虎骨", rarity = 2,
            description = "", category = com.xianxia.sect.core.model.MaterialCategory.BEAST_BONE,
            quantity = 2
        )
        state.gameData = state.gameData.copy(
            secretRealmSession = state.gameData.secretRealmSession.copy(
                backpack = com.xianxia.sect.core.model.SecretRealmBackpack(
                    materials = listOf(full)
                )
            )
        )
        org.mockito.kotlin.whenever(inventorySystem.addMaterial(full))
            .thenReturn(
                com.xianxia.sect.core.util.DomainResult.Failure(
                    com.xianxia.sect.core.util.AppError.Domain.Inventory.Full()
                )
            )
        service.endSession(state)
        // Full 已由 addXxx 内部 handleOverflowResult 按整件转邮件，settleItem 不得重复补偿
        org.mockito.Mockito.verify(
            inventorySystem, org.mockito.Mockito.never()
        ).sendOverflowMail(
            org.mockito.kotlin.eq("secret_realm"),
            org.mockito.kotlin.eq("material"),
            org.mockito.kotlin.eq("虎骨"),
            org.mockito.ArgumentMatchers.anyInt(),
            org.mockito.ArgumentMatchers.anyInt()
        )
    }
}

// ── sealed 结果便捷访问（测试断言辅助，internal 供同模块测试类共享） ──

internal val SecretRealmChoiceResult.isSuccess: Boolean
    get() = this is SecretRealmChoiceResult.Success

internal val SecretRealmChoiceResult.isSessionEnded: Boolean
    get() = (this as? SecretRealmChoiceResult.Success)?.sessionEnded == true

internal val SecretRealmChoiceResult.isEnteredCombat: Boolean
    get() = (this as? SecretRealmChoiceResult.Success)?.enteredCombat == true

internal val SecretRealmChoiceResult.isVictory: Boolean
    get() = (this as? SecretRealmChoiceResult.Success)?.victory == true

