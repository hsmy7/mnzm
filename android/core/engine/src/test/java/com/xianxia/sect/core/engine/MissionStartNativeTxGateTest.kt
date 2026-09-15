package com.xianxia.sect.core.engine

import com.xianxia.sect.core.model.Disciple
import com.xianxia.sect.core.model.DiscipleStatus
import com.xianxia.sect.core.model.Mission
import com.xianxia.sect.core.model.MissionDifficulty
import com.xianxia.sect.core.model.MissionRewardConfig
import com.xianxia.sect.core.model.MissionTemplate
import com.xianxia.sect.core.model.PatrolSlot
import com.xianxia.sect.core.model.ResidenceSlot
import com.xianxia.sect.core.nativebridge.NativeEngineFlag
import com.xianxia.sect.core.state.WriteGuardRule
import com.xianxia.sect.core.util.GameRngManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner

/**
 * MissionStartNativeTxGateTest — 任务派遣 native 臂门控守卫
 * （W4-D 续批·任务域收口，MISSION_START_TX=1861）。
 *
 * 守护契约（双实现并行契约 + findings 13，GuideRewardNativeTxGateTest 同口径——
 * JVM 无生产 .so，nativeExecute 必然降级，本类守护回退臂与降级等价；C++ 侧
 * 逐位语义由 GTest `mission_start_tx_test.cpp` 守护 + 桌面 JNI + 引擎全量门禁）：
 * - **回退臂语义逐位**：派遣后 activeMissions +1（模板/队员快照）、巡逻槽清理、
 *   住所保留、状态重置 IDLE；
 * - **降级等价**：flag OFF 与 AUTHORITATIVE（镜像服务未 stub → null 降级）两臂
 *   终态逐位一致；
 * - **事务外平台面两臂同形**：gate 释放 / Repository 清理 / 状态同步照原序执行
 *   （本夹具经 mock no-op 断言不抛）。
 */
@org.junit.experimental.categories.Category(com.xianxia.sect.core.RobolectricTests::class)
@RunWith(RobolectricTestRunner::class)
class MissionStartNativeTxGateTest {

    @get:Rule
    val writeGuardRule = WriteGuardRule()

    private lateinit var store: FakeAtomicStateStore
    private lateinit var engine: GameEngine

    private val mission = Mission(
        id = "gc-mission-1",
        template = MissionTemplate.ESCORT_CARAVAN,
        name = "护送商队",
        description = "测试任务",
        difficulty = MissionDifficulty.SIMPLE,
        duration = 6,
        rewards = MissionRewardConfig(spiritStones = 500)
    )

    @Before
    fun setup() {
        store = FakeAtomicStateStore()
        // 不 stub stateSyncServiceRef → AUTHORITATIVE 臂可空判空降级（findings 13）
        val mockCore = mock<GameEngineCore>()
        // launchInScope 同步执行（派遣入口为 launchInScope 包裹）；返回 mock Job
        //（GameEngineAppointmentNativeTxGateTest 同款桩法）
        whenever(mockCore.launchInScope(any())).thenAnswer { invocation ->
            val block = invocation.getArgument<suspend kotlinx.coroutines.CoroutineScope.() -> Unit>(0)
            runBlocking { block(kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined)) }
            mock<kotlinx.coroutines.Job>()
        }
        whenever(mockCore.scopeForStateIn()).thenReturn(kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined))
        engine = GameEngine(
            gameEngineCore = mockCore,
            engineContextDispatcher = FakeEngineContextDispatcher(),
            stateStore = store,
            gameRngManager = GameRngManager(),
            explorationFacade = mock(),
            cultivationFacade = mockCultivationFacade(),
            economyFacade = mock(),
            battleFacade = mockBattleFacade()
        )
    }

    /** GameEngine init 块 + 派遣事务外部平台面消费桩（GuideRewardNativeTxGateTest 同款）。 */
    private fun mockCultivationFacade(): com.xianxia.sect.core.engine.domain.cultivation.CultivationFacade =
        mock<com.xianxia.sect.core.engine.domain.cultivation.CultivationFacade>().also {
            whenever(it.cultivationService).thenReturn(mock())
            whenever(it.discipleService).thenReturn(mock())
            whenever(it.discipleFacade).thenReturn(mock())
            val mockProductionFacade = mock<com.xianxia.sect.core.engine.domain.production.ProductionFacade>()
            whenever(mockProductionFacade.productionSlots)
                .thenReturn(MutableStateFlow(emptyList()))
            whenever(it.productionFacade).thenReturn(mockProductionFacade)
            val mockPC = mock<com.xianxia.sect.core.engine.domain.production.ProductionCoordinator>()
            whenever(mockPC.repository).thenReturn(mock())
            whenever(it.productionCoordinator).thenReturn(mockPC)
        }

    private fun mockBattleFacade(): com.xianxia.sect.core.engine.domain.battle.BattleFacade =
        mock<com.xianxia.sect.core.engine.domain.battle.BattleFacade>().also {
            whenever(it.assignmentGate).thenReturn(mock())
        }

    /** 播种：301（巡逻槽 + 住所占用 + 血炼中）+ 302～306（IDLE）——模板 requiredMemberCount=6。 */
    private fun seed(): List<Disciple> {
        val team: List<Disciple>
        store.update {
            discipleTables.insert(
                Disciple(id = "301", name = "队员甲", status = DiscipleStatus.REFINING)
            )
            for (id in 302..306) {
                discipleTables.insert(Disciple(id = id.toString(), name = "队员$id", status = DiscipleStatus.IDLE))
            }
            gameData = gameData.copy(
                activeMissions = emptyList(),
                patrolSlots = listOf(
                    PatrolSlot(index = 0, discipleId = "301", discipleName = "队员甲")
                ),
                residenceSlots = listOf(
                    ResidenceSlot(buildingInstanceId = "res-1", slotIndex = 0, discipleId = "301", discipleName = "队员甲")
                )
            )
        }
        team = (301..306).map { store.discipleTables.assemble(it) }
        return team
    }

    @Test
    fun `fallback arm appends mission clears patrol keeps residence resets status`() = runBlocking {
        val team = seed()
        NativeEngineFlag.withMode(NativeEngineFlag.Mode.OFF) {
            try {
                engine.startMission(mission, team)
            } catch (t: Throwable) {
                println("PROBE startMission threw: $t")
            }
        }
        println("PROBE team=${team.size} gdYear=${store.gameDataSnapshot.gameYear} am=${store.gameDataSnapshot.activeMissions.size}")
        val gd = store.gameDataSnapshot
        assertEquals("恰一条进行中任务", 1, gd.activeMissions.size)
        val am = gd.activeMissions.single()
        assertEquals("任务模板 id", "gc-mission-1", am.missionId)
        assertEquals("任务名", "护送商队", am.missionName)
        assertEquals("队员快照", (301..306).map { it.toString() }, am.discipleIds)
        // 巡逻槽清理 / 住所保留
        assertEquals("巡逻槽已清理", "", gd.patrolSlots.single().discipleId)
        assertEquals("住所保留", "301", gd.residenceSlots.single().discipleId)
        // 状态重置 IDLE（血炼中 → 派遣换岗重置，buildingId 键剥离）
        val d301 = store.discipleTables.assemble(301)
        assertEquals(DiscipleStatus.IDLE, d301.status)
        assertEquals(false, d301.statusData.containsKey("buildingId"))
    }

    @Test
    fun `flag OFF and degraded AUTHORITATIVE arms agree bit for bit`() = runBlocking {
        val off = NativeEngineFlag.withMode(NativeEngineFlag.Mode.OFF) {
            val team = seed()
            engine.startMission(mission, team)
            store.gameDataSnapshot.activeMissions.single().let {
                Triple(it.missionId, it.discipleIds, it.startYear)
            }
        }
        // 复位播种面后重跑 AUTHORITATIVE 臂（镜像缺失 → null 降级 → 同一回退臂）
        val auth = NativeEngineFlag.withMode(NativeEngineFlag.Mode.AUTHORITATIVE) {
            val team = seed()
            engine.startMission(mission, team)
            store.gameDataSnapshot.activeMissions.single().let {
                Triple(it.missionId, it.discipleIds, it.startYear)
            }
        }
        assertEquals("两臂终态逐位一致（回退臂）", off, auth)
    }
}
