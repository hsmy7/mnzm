package com.xianxia.sect.core.engine

import com.xianxia.sect.core.engine.domain.battle.BattleFacade
import com.xianxia.sect.core.engine.domain.cultivation.CultivationFacade
import com.xianxia.sect.core.engine.domain.disciple.DiscipleAssignmentGate
import com.xianxia.sect.core.engine.domain.disciple.DiscipleAssignmentRegistry
import com.xianxia.sect.core.engine.domain.disciple.DiscipleStatusService
import com.xianxia.sect.core.engine.domain.economy.EconomyFacade
import com.xianxia.sect.core.engine.domain.inventory.InventoryFacade
import com.xianxia.sect.core.engine.domain.production.ProductionCoordinator
import com.xianxia.sect.core.engine.domain.production.ProductionFacade
import com.xianxia.sect.core.engine.service.JadeSymbolService
import com.xianxia.sect.core.engine.service.WallClock
import com.xianxia.sect.core.engine.system.TimeSource
import com.xianxia.sect.core.model.BloodRefinementProgress
import com.xianxia.sect.core.model.DiscipleStatus
import com.xianxia.sect.core.model.ManualInstance
import com.xianxia.sect.core.model.ManualStack
import com.xianxia.sect.core.model.Material
import com.xianxia.sect.core.nativebridge.NativeEngineFlag
import com.xianxia.sect.core.state.WriteGuardRule
import com.xianxia.sect.core.util.DeterministicRng
import com.xianxia.sect.core.util.GameRngManager
import com.xianxia.sect.core.util.RngPartition
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner

/**
 * w3-01 弟子操作面九事务 native 臂门控单测（W4-A 第一子批——
 * GameEngineAppointmentNativeTxGateTest 同族三级降级契约守护）。
 *
 * JVM 单测环境 GameCoreBridge 恒未加载：断言 AUTHORITATIVE 稳态与 flag OFF
 * 两模式下九 native 臂均降级（null/false）、入口走 Kotlin 回退臂且语义不变
 * （改名净化/类型直改/关注翻转/功法替换/血炼启动/状态派生）；native 事务
 * 本身的校验链/字段面由桌面 C++ disciple_ops_tx_test.cpp 黄金用例守护，
 * 真机转发臂由真机验证批覆盖。
 *
 * 红线 8 回归面：DiscipleStatusService 以 mock StateSyncService（未 stub）
 * 构造 ⇒ 派生同步必须降级（不得 NPE）。
 */
@org.junit.experimental.categories.Category(com.xianxia.sect.core.RobolectricTests::class)
@RunWith(RobolectricTestRunner::class)
class GameEngineDiscipleOpsNativeTxGateTest {

    @get:Rule val writeGuardRule = WriteGuardRule()

    /** 单调时钟 fake（玉符服务构造要求）。 */
    private class FakeTimeSource(var nowMs: Long) : TimeSource {
        override fun elapsedRealtime(): Long = nowMs
    }

    private lateinit var store: FakeAtomicStateStore
    private lateinit var gate: DiscipleAssignmentGate
    private lateinit var jadeService: JadeSymbolService
    private lateinit var engine: GameEngine
    private lateinit var systemRng: DeterministicRng

    private val discipleA = "1"

    @Before
    fun setUp() {
        gate = DiscipleAssignmentGate(DiscipleAssignmentRegistry())
        store = FakeAtomicStateStore()
        jadeService = JadeSymbolService(
            timeSource = FakeTimeSource(1_000_000L),
            stateStore = store,
            wallClock = WallClock { 1_700_000_000_000L }
        )
        seedDisciples()

        val mockCore = mock<GameEngineCore>()
        whenever(mockCore.jadeSymbolServiceRef).thenReturn(jadeService)
        whenever(mockCore.launchInScope(any())).thenAnswer { invocation ->
            val block = invocation.getArgument<suspend CoroutineScope.() -> Unit>(0)
            runBlocking { block(CoroutineScope(Dispatchers.Unconfined)) }
            mock<kotlinx.coroutines.Job>()
        }
        whenever(mockCore.scopeForStateIn()).thenReturn(CoroutineScope(Dispatchers.Unconfined))
        systemRng = DeterministicRng.fromSeed(20260912L)
        val mockRng = mock<GameRngManager>()
        whenever(mockRng.getRng(RngPartition.SYSTEM)).thenReturn(systemRng)

        val mockBattleFacade = mock<BattleFacade>()
        org.mockito.kotlin.whenever(mockBattleFacade.assignmentGate).thenReturn(gate)

        engine = GameEngine(
            gameEngineCore = mockCore,
            engineContextDispatcher = FakeEngineContextDispatcher(),
            stateStore = store,
            gameRngManager = mockRng,
            explorationFacade = mock(),
            cultivationFacade = mockCultivationFacade(),
            economyFacade = mockEconomyFacade(),
            battleFacade = mockBattleFacade
        )
    }

    @After
    fun restoreFlag() {
        NativeEngineFlag.mode = NativeEngineFlag.Mode.AUTHORITATIVE
    }

    // ── 1. 改名：两模式均走回退臂（name 行写 + 招募同人净化）────────────

    @Test
    fun renameDiscipleDegradesToKotlinFallbackInBothModes() = runTest {
        seedRecruitCandidate()
        NativeEngineFlag.withMode(NativeEngineFlag.Mode.AUTHORITATIVE) {
            runBlocking { engine.renameDisciple(discipleA, "新名甲") }
        }
        var name = ""
        var recruitSize = -1
        store.update {
            name = discipleTables.names[1]
            recruitSize = gameData.recruitList.size
        }
        assertEquals("新名甲", name)
        // 同人残留净化（签名命中）
        assertEquals(0, recruitSize)

        // flag OFF 同语义
        store.update {
            discipleTables.writeAllowed = true
            discipleTables.names[1] = "弟子A"
            discipleTables.writeAllowed = false
        }
        NativeEngineFlag.withMode(NativeEngineFlag.Mode.OFF) {
            runBlocking { engine.renameDisciple(discipleA, "新名乙") }
        }
        store.update { name = discipleTables.names[1] }
        assertEquals("新名乙", name)
    }

    // ── 2. 类型直改：两模式回退臂列写 ──────────────────────────────────

    @Test
    fun changeDiscipleTypeDegradesToKotlinFallbackInBothModes() = runTest {
        NativeEngineFlag.withMode(NativeEngineFlag.Mode.AUTHORITATIVE) {
            runBlocking { engine.changeDiscipleTypeAtomic(discipleA, "OUTER") }
        }
        var type = ""
        store.update { type = discipleTables.discipleTypes[1] }
        assertEquals("OUTER", type)

        NativeEngineFlag.withMode(NativeEngineFlag.Mode.OFF) {
            runBlocking { engine.changeDiscipleTypeAtomic(discipleA, "INNER") }
        }
        store.update { type = discipleTables.discipleTypes[1] }
        assertEquals("INNER", type)
    }

    // ── 3. 关注切换：两模式回退臂 statusData 翻转 ──────────────────────

    @Test
    fun toggleFollowDegradesToKotlinFallbackInBothModes() = runTest {
        NativeEngineFlag.withMode(NativeEngineFlag.Mode.AUTHORITATIVE) {
            runBlocking { engine.toggleFollowDisciple(discipleA) }
        }
        var followed = ""
        store.update { followed = discipleTables.statusData[1]["followed"] ?: "" }
        assertEquals("true", followed)

        NativeEngineFlag.withMode(NativeEngineFlag.Mode.OFF) {
            runBlocking { engine.toggleFollowDisciple(discipleA) }
        }
        store.update { followed = discipleTables.statusData[1]["followed"] ?: "" }
        assertEquals("", followed)
    }

    // ── 4. 功法替换：两模式回退臂 manualIds 换血 + 旧实例入袋 ──────────

    @Test
    fun replaceManualDegradesToKotlinFallbackInBothModes() = runTest {
        seedManuals()
        NativeEngineFlag.withMode(NativeEngineFlag.Mode.AUTHORITATIVE) {
            runBlocking { engine.replaceManual(discipleA, "old-1", "stack-1") }
        }
        assertReplaced()

        // 复位后 flag OFF 同语义
        seedManuals()
        NativeEngineFlag.withMode(NativeEngineFlag.Mode.OFF) {
            runBlocking { engine.replaceManual(discipleA, "old-1", "stack-1") }
        }
        assertReplaced()
    }

    private fun assertReplaced() {
        store.update {
            val mids = discipleTables.manualIds[1]
            assertEquals(1, mids.size)
            assertTrue(!mids.contains("old-1"))
            // 旧实例入袋（D-03）+ 实例表移除（防双持有）
            val bag = discipleTables.storageBagItems[1]
            assertTrue(bag.any { it.itemId == "old-1" && it.manualInstance != null })
            assertNull(manualInstances.get("old-1"))
            assertNotNull(manualInstances.get(mids[0]))
            // 新堆叠整摞消耗
            assertNull(manualStacks.get("stack-1"))
        }
    }

    private fun seedManuals() {
        store.update {
            val old = ManualInstance(
                id = "old-1", name = "青元功", rarity = 2, type = com.xianxia.sect.core.model.ManualType.SUPPORT
            )
            manualInstances.replaceAll(listOf(old))
            manualStacks.replaceAll(
                listOf(
                    ManualStack(
                        id = "stack-1", name = "赤炎诀", rarity = 2, quantity = 1,
                        type = com.xianxia.sect.core.model.ManualType.SUPPORT, minRealm = 9
                    )
                )
            )
            discipleTables.writeAllowed = true
            discipleTables.manualIds[1] = listOf("old-1")
            discipleTables.writeAllowed = false
        }
    }

    // ── 5. 血炼启动：两模式回退臂（灵石/材料扣减 + 进度 + REFINING）────

    @Test
    fun startBloodRefinementDegradesToKotlinFallbackInBothModes() = runTest {
        NativeEngineFlag.withMode(NativeEngineFlag.Mode.AUTHORITATIVE) {
            assertBloodRefinementViaFallback()
        }
        // 复位
        resetBloodRefinement()
        NativeEngineFlag.withMode(NativeEngineFlag.Mode.OFF) {
            assertBloodRefinementViaFallback()
        }
    }

    private fun assertBloodRefinementViaFallback() = runBlocking {
        store.update {
            materials.replaceAll(
                listOf(Material(id = "m1", name = "妖兽血", rarity = 2, quantity = 5))
            )
            gameData = gameData.copy(spiritStones = 1000)
        }
        val result = engine.startBloodRefinementAtomic(
            materialName = "妖兽血",
            materialRarity = 2,
            materialCount = 3,
            buildingInstanceId = "pool-1",
            requiredSpiritStones = 100L,
            progress = BloodRefinementProgress(
                discipleId = discipleA,
                discipleName = "弟子A",
                materialId = "m1",
                selectedStat = "hp",
                bonusPercent = 0.05,
                durationMonths = 3
            )
        )
        assertTrue(result is BloodRefinementStartResult.Success)
        store.update {
            assertEquals(900L, gameData.spiritStones)
            assertEquals(1, gameData.activeBloodRefinements.size)
            assertEquals(DiscipleStatus.REFINING, discipleTables.statuses[1])
            assertEquals(2, materials.get("m1")?.quantity)
        }
    }

    private fun resetBloodRefinement() {
        store.update {
            gameData = gameData.copy(
                spiritStones = 1000,
                activeBloodRefinements = emptyMap()
            )
            discipleTables.writeAllowed = true
            discipleTables.statuses[1] = DiscipleStatus.IDLE
            discipleTables.writeAllowed = false
        }
    }

    // ── 6. 状态派生：mock StateSyncService 未 stub ⇒ 降级不 NPE（红线 8）─

    @Test
    fun statusSyncDegradesAndDerivesViaKotlinFallback() {
        // StateSyncService 手工厂内置（GameEngineCore 同款）；JVM 桥未加载 ⇒
        // AUTHORITATIVE 下 tryExecuteNative 降级 null → Kotlin 推导回退臂
        val service = DiscipleStatusService(
            stateStore = store,
            discipleLifecycleManager = mock(),
            secretRealmService = mock()
        )
        NativeEngineFlag.withMode(NativeEngineFlag.Mode.AUTHORITATIVE) {
            service.syncSingleDiscipleStatus(discipleA)
            service.syncAllDiscipleStatuses()
        }
        NativeEngineFlag.withMode(NativeEngineFlag.Mode.OFF) {
            service.syncAllDiscipleStatuses()
        }
        var status: DiscipleStatus? = null
        store.update { status = discipleTables.statuses[1] }
        // 藏经阁/长老等槽位为空 ⇒ 推导 IDLE（受保护态 REFLECTING/REFINING 除外）
        assertEquals(DiscipleStatus.IDLE, status)
    }

    // ── 夹具（GameEngineAppointmentNativeTxGateTest 同款）──────────────

    private fun mockCultivationFacade(): CultivationFacade = mock<CultivationFacade>().also {
        whenever(it.cultivationService).thenReturn(mock())
        whenever(it.discipleService).thenReturn(mock())
        whenever(it.discipleFacade).thenReturn(mock())
        val mockProductionFacade = mock<ProductionFacade>()
        whenever(mockProductionFacade.productionSlots)
            .thenReturn(kotlinx.coroutines.flow.MutableStateFlow(emptyList()))
        whenever(it.productionFacade).thenReturn(mockProductionFacade)
        val mockPC = mock<ProductionCoordinator>()
        whenever(mockPC.repository).thenReturn(mock())
        whenever(it.productionCoordinator).thenReturn(mockPC)
    }

    private fun mockEconomyFacade(): EconomyFacade = mock<EconomyFacade>().also {
        val mockInventoryFacade = mock<InventoryFacade>()
        whenever(mockInventoryFacade.inventorySystem).thenReturn(mock())
        whenever(it.inventoryFacade).thenReturn(mockInventoryFacade)
        whenever(it.mailService).thenReturn(mock())
    }

    /** 招募列表同人候选播种（与弟子 A 签名命中——name/surname/gender/root + 年龄容差）。 */
    private fun seedRecruitCandidate() {
        store.update {
            // 与 seedDisciples 装配面同签名（DiscipleTablesAssemblers 默认：
            // gender="male" / spiritRootType="metal"），age=20 与弟子 A 同龄
            val candidate = com.xianxia.sect.core.model.Disciple(
                id = "cand-1", name = "弟子A", surname = "张", age = 20
            ).copy(isAlive = true)
            gameData = gameData.copy(recruitList = listOf(candidate))
        }
    }

    /** 测试弟子 A 播种（事务内初始化 DiscipleTables）。 */
    private fun seedDisciples() {
        store.update {
            discipleTables.writeAllowed = true
            val a = discipleA.toInt()
            discipleTables.addId(a)
            discipleTables.names[a] = "弟子A"
            discipleTables.surnames[a] = "张"
            discipleTables.statuses[a] = DiscipleStatus.IDLE
            discipleTables.statusData[a] = emptyMap()
            discipleTables.isAlive[a] = 1
            discipleTables.realms[a] = 9
            discipleTables.realmLayers[a] = 1
            discipleTables.ages[a] = 20
            discipleTables.lifespans[a] = 80
            discipleTables.storageBagItems[a] = emptyList()
            discipleTables.writeAllowed = false
        }
    }
}
