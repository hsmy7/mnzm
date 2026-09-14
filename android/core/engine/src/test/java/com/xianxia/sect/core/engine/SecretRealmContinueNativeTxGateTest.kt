package com.xianxia.sect.core.engine

import com.xianxia.sect.core.engine.domain.battle.BattleSystem
import com.xianxia.sect.core.engine.domain.cultivation.CultivationFacade
import com.xianxia.sect.core.engine.domain.disciple.DiscipleAssignmentGate
import com.xianxia.sect.core.engine.domain.disciple.DiscipleAssignmentRegistry
import com.xianxia.sect.core.engine.domain.economy.EconomyFacade
import com.xianxia.sect.core.engine.domain.inventory.InventoryFacade
import com.xianxia.sect.core.engine.domain.exploration.ExplorationFacade
import com.xianxia.sect.core.engine.domain.production.ProductionCoordinator
import com.xianxia.sect.core.engine.domain.production.ProductionFacade
import com.xianxia.sect.core.engine.service.OverflowMailSender
import com.xianxia.sect.core.engine.service.SecretRealmService
import com.xianxia.sect.core.engine.system.InventorySystem
import com.xianxia.sect.core.model.CombatAttributes
import com.xianxia.sect.core.model.Disciple
import com.xianxia.sect.core.model.DiscipleStatus
import com.xianxia.sect.core.model.SecretRealmExplorationSession
import com.xianxia.sect.core.model.SecretRealmMemberState
import com.xianxia.sect.core.model.SecretRealmState
import com.xianxia.sect.core.model.SkillStats
import com.xianxia.sect.core.nativebridge.NativeEngineFlag
import com.xianxia.sect.core.state.WriteGuardRule
import com.xianxia.sect.core.util.DeterministicRng
import com.xianxia.sect.core.util.GameRngManager
import com.xianxia.sect.core.util.RngPartition
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.mockito.kotlin.any
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner

/**
 * SecretRealmContinueNativeTxGateTest — 秘境平台段读档恢复 native 臂门控
 * 降级守卫（batch-20a：continueSecretRealmExploration 下沉）。
 *
 * 守护契约（双实现并行契约 + handover findings 13）：
 * - **降级等价**：flag OFF 与 AUTHORITATIVE（JVM 无生产 .so，GameCoreBridge
 *   未加载 → tryForward 降级 null）下 `continueSecretRealmExploration` 均
 *   回退 Kotlin 原实现，**返回值与终态逐位一致**（同播种双运行对拍）
 * - **镜像缺失不 NPE**：测试 mock 未 stub `stateSyncServiceRef` → 返回 null
 *   sync → native 臂先赋可空局部再判空（findings 13），不得 NPE（本测试类
 *   全部用例即为该契约的回归网）
 * - **回退臂语义不变**：正常会话 true + 成员保持 / 到期关闭（spawnYear+5）
 *   false + 秘境清场 / 死亡成员净化写回
 *
 * C++ 侧的判定序/零 RNG/信封面由 GTest `secret_realm_platform_tx_test.cpp`
 * 逐位守护；真机 native 臂对拍由 batch-22 物理设备验证批承担。
 */
@org.junit.experimental.categories.Category(com.xianxia.sect.core.RobolectricTests::class)
@RunWith(RobolectricTestRunner::class)
class SecretRealmContinueNativeTxGateTest {

    @get:Rule
    val writeGuardRule = WriteGuardRule()

    private lateinit var store: FakeAtomicStateStore
    private lateinit var secretRealmService: SecretRealmService
    private lateinit var assignmentGate: DiscipleAssignmentGate
    private lateinit var engine: GameEngine

    @Before
    fun setup() {
        store = FakeAtomicStateStore()
        // 真实 SecretRealmService（依赖 mock——回退臂路径语义完整执行）
        val rngManager = mock(GameRngManager::class.java)
        `when`(rngManager.getRng(RngPartition.SECRET_REALM))
            .thenReturn(DeterministicRng.fromSeed(20260912L))
        val battleSystem = mock(BattleSystem::class.java)
        val inventorySystem = mock(InventorySystem::class.java)
        whenever(inventorySystem.withTrackingSource<Any>(any(), any())).thenAnswer { inv ->
            inv.getArgument<() -> Any>(1).invoke()
        }
        assignmentGate = DiscipleAssignmentGate(DiscipleAssignmentRegistry())
        secretRealmService = SecretRealmService(
            rngManager = rngManager,
            battleSystem = battleSystem,
            inventorySystem = inventorySystem,
            spiritStoneWallet = mock(com.xianxia.sect.core.wallet.SpiritStoneWallet::class.java),
            overflowMailSender = mock(OverflowMailSender::class.java),
            assignmentGate = assignmentGate
        )
        // 注意：**不** stub stateSyncServiceRef —— native 臂可空判空降级
        //（handover findings 13：mock 未 stub 时返回 null 不得 NPE）
        val mockCore = mock<GameEngineCore>()
        val mockExplorationFacade = mock<ExplorationFacade>()
        whenever(mockExplorationFacade.secretRealmService).thenReturn(secretRealmService)
        val mockBattleFacade = mock<com.xianxia.sect.core.engine.domain.battle.BattleFacade>()
        whenever(mockBattleFacade.assignmentGate).thenReturn(assignmentGate)
        val mockRng = mock<GameRngManager>()
        whenever(mockRng.getRng(RngPartition.SYSTEM))
            .thenReturn(DeterministicRng.fromSeed(20260912L))
        engine = GameEngine(
            gameEngineCore = mockCore,
            engineContextDispatcher = FakeEngineContextDispatcher(),
            stateStore = store,
            gameRngManager = mockRng,
            explorationFacade = mockExplorationFacade,
            cultivationFacade = mockCultivationFacade(),
            economyFacade = mockEconomyFacade(),
            battleFacade = mockBattleFacade
        )
    }

    /** 构造期 Facade 访问器 stub 链（防 GameEngine 构造 NPE，对齐既有测试）。 */
    private fun mockCultivationFacade(): CultivationFacade = mock<CultivationFacade>().also {
        whenever(it.cultivationService).thenReturn(mock())
        whenever(it.discipleService).thenReturn(mock())
        val mockProductionFacade = mock<ProductionFacade>()
        whenever(mockProductionFacade.productionSlots)
            .thenReturn(kotlinx.coroutines.flow.MutableStateFlow(emptyList()))
        whenever(it.productionFacade).thenReturn(mockProductionFacade)
        val mockPC = mock<ProductionCoordinator>()
        whenever(mockPC.repository).thenReturn(mock())
        whenever(it.productionCoordinator).thenReturn(mockPC)
    }

    /** 构造期 Facade 访问器 stub 链（对齐既有测试）。 */
    private fun mockEconomyFacade(): EconomyFacade = mock<EconomyFacade>().also {
        val mockInventoryFacade = mock<InventoryFacade>()
        whenever(mockInventoryFacade.inventorySystem).thenReturn(mock())
        whenever(it.inventoryFacade).thenReturn(mockInventoryFacade)
        whenever(it.mailService).thenReturn(mock())
    }

    /** 播种弟子（isAlive/status 写 SoA 列——assembleAll 同源）。 */
    private fun insertDisciple(id: String, alive: Boolean) {
        store.persistentDiscipleTables.insert(
            Disciple(
                id = id,
                name = "弟子$id",
                age = 25,
                lifespan = 90,
                skills = SkillStats(comprehension = 100),
                combat = CombatAttributes(currentHp = -1)
            )
        )
        store.persistentDiscipleTables.isAlive[id.toInt()] = if (alive) 1 else 0
        store.persistentDiscipleTables.statuses[id.toInt()] = DiscipleStatus.IDLE
    }

    /** 播种秘境现世 + 活跃会话（id 匹配——continue 判定序的正常前提）。 */
    private fun seedActiveSession(
        spawnYear: Int = 1,
        gameYear: Int = 3,
        memberIds: List<String>
    ) {
        store.update {
            gameData = gameData.copy(
                gameYear = gameYear,
                secretRealmState = SecretRealmState(id = "realm_1", spawnYear = spawnYear),
                secretRealmSession = SecretRealmExplorationSession(
                    secretRealmId = "realm_1",
                    members = memberIds.map { id ->
                        SecretRealmMemberState(
                            discipleId = id, name = "弟子$id", portraitRes = "",
                            realm = 5, realmName = "化神"
                        )
                    },
                    stamina = 20
                )
            )
        }
    }

    // ── 降级等价：flag OFF 与 AUTHORITATIVE（无桥）双运行逐位一致 ──

    @Test
    fun flagOffContinueActiveSessionReturnsTrueAndKeepsMembers() = runBlocking {
        insertDisciple("1", alive = true)
        insertDisciple("2", alive = true)
        insertDisciple("3", alive = true)
        insertDisciple("4", alive = true)
        seedActiveSession(memberIds = listOf("1", "2", "3", "4"))
        NativeEngineFlag.withMode(NativeEngineFlag.Mode.OFF) {
            val canContinue = engine.continueSecretRealmExploration()
            assertTrue(canContinue)
            val session = store.latestGameData.secretRealmSession
            assertEquals(4, session.members.size)
            assertEquals("realm_1", store.latestGameData.secretRealmState.id)
        }
    }

    @Test
    fun flagOffExpiredClosesRealmAndSession() = runBlocking {
        insertDisciple("1", alive = true)
        // spawnYear=5, gameYear=10 → 满 5 年到期（Kotlin OPEN_YEARS=5）
        seedActiveSession(spawnYear = 5, gameYear = 10, memberIds = listOf("1"))
        NativeEngineFlag.withMode(NativeEngineFlag.Mode.OFF) {
            val canContinue = engine.continueSecretRealmExploration()
            assertEquals(false, canContinue)
            assertEquals("", store.latestGameData.secretRealmState.id)
            assertTrue(store.latestGameData.secretRealmSession.members.isEmpty())
        }
    }

    @Test
    fun flagOffPurifiesDeadMembersAndReturnsTrue() = runBlocking {
        insertDisciple("1", alive = true)
        insertDisciple("2", alive = false) // 已死亡 → 净化移除
        insertDisciple("3", alive = true)
        insertDisciple("4", alive = true)
        seedActiveSession(memberIds = listOf("1", "2", "3", "4"))
        NativeEngineFlag.withMode(NativeEngineFlag.Mode.OFF) {
            val canContinue = engine.continueSecretRealmExploration()
            assertTrue(canContinue)
            val members = store.latestGameData.secretRealmSession.members
            assertEquals(listOf("1", "3", "4"), members.map { it.discipleId })
        }
    }

    @Test
    fun authoritativeWithoutBridgeMatchesFallbackBitwise() = runBlocking {
        // 同播种双运行：flag OFF 与 AUTHORITATIVE（JVM 无 .so → 降级回退）
        // 返回值与终态逐位一致（双实现并行契约的降级臂对拍）
        insertDisciple("1", alive = true)
        insertDisciple("2", alive = false)
        insertDisciple("3", alive = true)
        insertDisciple("4", alive = true)
        seedActiveSession(memberIds = listOf("1", "2", "3", "4"))

        val fallbackMembers: List<String> = NativeEngineFlag.withMode(NativeEngineFlag.Mode.OFF) {
            val r = engine.continueSecretRealmExploration()
            assertTrue(r)
            store.latestGameData.secretRealmSession.members.map { it.discipleId }
        }
        // AUTHORITATIVE 默认模式（生产默认）——本测试类未 stub stateSyncServiceRef
        //（findings 13 回归网）且 JVM 无生产 .so → tryForward null → 回退
        seedActiveSession(memberIds = listOf("1", "2", "3", "4"))
        val authoritativeMembers: List<String> = run {
            val r2 = engine.continueSecretRealmExploration()
            assertTrue(r2)
            store.latestGameData.secretRealmSession.members.map { it.discipleId }
        }
        assertEquals(fallbackMembers, authoritativeMembers)
        assertEquals(listOf("1", "3", "4"), authoritativeMembers)
    }
}
