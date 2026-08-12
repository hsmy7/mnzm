package com.xianxia.sect.core.engine.service

import com.xianxia.sect.core.engine.domain.battle.BattleSystem
import com.xianxia.sect.core.engine.domain.disciple.DiscipleAssignmentGate
import com.xianxia.sect.core.model.CombatAttributes
import com.xianxia.sect.core.model.Disciple
import com.xianxia.sect.core.model.DiscipleStatus
import com.xianxia.sect.core.model.GameData
import com.xianxia.sect.core.model.MailEntity
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
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner

/**
 * 远古秘境 5 年自动关闭（processMonthlyExpiryCheck / closeSecretRealmByExpiry）单元测试：
 * 到期关闭背包转邮件灵石入钱包、未到期不触发、幂等、空背包无邮件、杜绝双发放。
 */
@RunWith(RobolectricTestRunner::class)
class SecretRealmExpiryTest {

    private lateinit var battleSystem: BattleSystem
    private lateinit var inventorySystem: com.xianxia.sect.core.engine.system.InventorySystem
    private lateinit var spiritStoneWallet: com.xianxia.sect.core.wallet.SpiritStoneWallet
    private lateinit var overflowMailSender: OverflowMailSender
    private lateinit var assignmentGate: DiscipleAssignmentGate
    private lateinit var service: SecretRealmService
    private lateinit var tables: DiscipleTables

    @get:org.junit.Rule
    val writeGuardRule = WriteGuardRule()

    @Before
    fun setUp() {
        val rngManager = mock(GameRngManager::class.java)
        `when`(rngManager.getRng(RngPartition.SECRET_REALM)).thenReturn(DeterministicRng.fromSeed(20260731L))
        battleSystem = mock(BattleSystem::class.java)
        inventorySystem = mock(com.xianxia.sect.core.engine.system.InventorySystem::class.java)
        whenever(
            inventorySystem.withTrackingSource<Any>(any(), any())
        ).thenAnswer { inv ->
            inv.getArgument<() -> Any>(1).invoke()
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

    /** 现世第 5 年 + 活跃会话 + 非空背包（材料+种子+灵石）的到期档 */
    private fun setupExpiringSession(state: MutableGameState) {
        setupActiveSession(state)
        state.gameData = state.gameData.copy(
            gameYear = 10,
            secretRealmState = com.xianxia.sect.core.model.SecretRealmState(
                id = "realm_1", spawnYear = 5
            ),
            secretRealmAITeams = listOf(
                SecretRealmAITeam(
                    id = "team_1", sectId = "sect1", sectName = "青云宗",
                    sectLevel = 1,
                    members = listOf(SecretRealmAIMember(discipleId = "a1", name = "剑尘"))
                )
            ),
            secretRealmSession = state.gameData.secretRealmSession.copy(
                backpack = com.xianxia.sect.core.model.SecretRealmBackpack(
                    spiritStones = 300L,
                    materials = listOf(
                        com.xianxia.sect.core.model.Material(
                            id = "m1", name = "虎骨", rarity = 2,
                            description = "", category = com.xianxia.sect.core.model.MaterialCategory.BEAST_BONE,
                            quantity = 1
                        )
                    ),
                    seeds = listOf(
                        com.xianxia.sect.core.model.Seed(
                            id = "s1", name = "聚灵草种", rarity = 2,
                            description = "", growTime = 3, yield = 2, quantity = 2
                        )
                    )
                )
            )
        )
    }

    @Test
    fun `processMonthlyExpiryCheck - 现世满 5 年关闭背包转邮件灵石入钱包`() {
        val state = createState()
        val ids = setupActiveSession(state)
        setupExpiringSession(state)
        service.processMonthlyExpiryCheck(state, year = 10)
        // 秘境消失 + 冷却年 = 当前年 + 会话/AI 队伍清空
        assertFalse(state.gameData.secretRealmState.exists)
        assertEquals(10, state.gameData.secretRealmCooldownYear)
        assertFalse(state.gameData.secretRealmSession.isActive)
        assertTrue(state.gameData.secretRealmAITeams.isEmpty())
        // gate 全部释放（成员解占，防弟子卡死）
        ids.forEach { org.mockito.Mockito.verify(assignmentGate).release(it) }
        // 灵石已入钱包（300 下品）
        org.mockito.Mockito.verify(spiritStoneWallet).add(
            org.mockito.kotlin.any(),
            org.mockito.kotlin.eq(300L),
            org.mockito.kotlin.eq(com.xianxia.sect.core.model.SpiritStoneGrade.LOW),
            org.mockito.kotlin.eq(com.xianxia.sect.core.wallet.SpiritStoneSource.SecretRealm),
            org.mockito.kotlin.any()
        )
        // 邮件：标题/内容规定文案 + 附件与背包逐件对应
        val captor = argumentCaptor<MailEntity>()
        org.mockito.Mockito.verify(overflowMailSender).sendDirectMail(captor.capture())
        val mail = captor.firstValue
        assertEquals("远古秘境已关闭", mail.title)
        assertTrue(mail.content.contains("远古秘境已关闭，这些物品是远古秘境中获得的物品："))
        assertTrue(mail.content.contains("虎骨 ×1"))
        assertTrue(mail.content.contains("聚灵草种 ×2"))
        assertEquals("secret_realm", mail.source)
        assertEquals("secret_realm_close", mail.mailType)
        assertTrue(mail.expireTime > mail.sendTime)
        assertEquals(2, mail.attachments.split("quantity").size - 1)
    }

    @Test
    fun `processMonthlyExpiryCheck - 未到期不触发`() {
        val state = createState()
        setupActiveSession(state)
        state.gameData = state.gameData.copy(
            gameYear = 9,
            secretRealmState = com.xianxia.sect.core.model.SecretRealmState(
                id = "realm_1", spawnYear = 5
            )
        )
        service.processMonthlyExpiryCheck(state, year = 9)
        assertTrue(state.gameData.secretRealmState.exists)
        assertTrue(state.gameData.secretRealmSession.isActive)
        org.mockito.Mockito.verify(overflowMailSender, org.mockito.Mockito.never())
            .sendDirectMail(org.mockito.kotlin.any())
    }

    @Test
    fun `processMonthlyExpiryCheck - 无秘境幂等 no-op`() {
        val state = createState()
        service.processMonthlyExpiryCheck(state, year = 100)
        assertFalse(state.gameData.secretRealmState.exists)
        org.mockito.Mockito.verify(overflowMailSender, org.mockito.Mockito.never())
            .sendDirectMail(org.mockito.kotlin.any())
    }

    @Test
    fun `closeSecretRealmByExpiry - 空背包不产生邮件且灵石不入钱包`() {
        val state = createState()
        setupActiveSession(state)
        state.gameData = state.gameData.copy(
            secretRealmState = com.xianxia.sect.core.model.SecretRealmState(id = "realm_1", spawnYear = 1)
        )
        service.closeSecretRealmByExpiry(state)
        assertFalse(state.gameData.secretRealmState.exists)
        org.mockito.Mockito.verify(overflowMailSender, org.mockito.Mockito.never())
            .sendDirectMail(org.mockito.kotlin.any())
        org.mockito.Mockito.verify(spiritStoneWallet, org.mockito.Mockito.never())
            .add(
                org.mockito.kotlin.any(), org.mockito.kotlin.any(),
                org.mockito.kotlin.any(), org.mockito.kotlin.any(), org.mockito.kotlin.any()
            )
    }

    @Test
    fun `closeSecretRealmByExpiry - 先清空背包再结算杜绝双发放`() {
        val state = createState()
        setupExpiringSession(state)
        service.closeSecretRealmByExpiry(state)
        // 背包清空后才 endSession → settleBackpack 空背包 no-op，物品不得二次入仓
        org.mockito.Mockito.verify(inventorySystem, org.mockito.Mockito.never())
            .addMaterial(org.mockito.kotlin.any())
        // 邮件恰好一封（背包非空时）且关闭幂等不重复发送
        org.mockito.Mockito.verify(overflowMailSender).sendDirectMail(org.mockito.kotlin.any())
        service.closeSecretRealmByExpiry(state)
        org.mockito.Mockito.verify(overflowMailSender, org.mockito.Mockito.times(1))
            .sendDirectMail(org.mockito.kotlin.any())
    }
}
