package com.xianxia.sect.core.engine

import com.xianxia.sect.core.model.Disciple
import com.xianxia.sect.core.model.SkillStats
import com.xianxia.sect.core.nativebridge.NativeEngineFlag
import com.xianxia.sect.core.state.WriteGuardRule
import com.xianxia.sect.core.util.GameRngManager
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner

/**
 * DiscipleChatEffectNativeTxGateTest — 交谈效果 native 臂门控守卫
 * （W4-D 续批·弟子通道收口，DISCIPLE_CHAT_EFFECT_TX=1860）。
 *
 * 守护契约（双实现并行契约 + handover findings 13，GuideRewardNativeTxGateTest
 * 同口径——JVM 无生产 .so，nativeExecute 必然降级，故本类守护降级臂；真臂
 * 语义由 GTest `chat_effect_tx_test.cpp` 逐位守护 + 桌面 JNI + 引擎全量门禁）：
 * - **降级等价**：flag OFF 与 AUTHORITATIVE（镜像服务未 stub → null 降级）均走
 *   Kotlin 回退臂，终态逐位一致；
 * - **回退臂语义逐位**：修为 max(0,+x)、道德/忠诚/悟性 (原值+增量) clamp
 *   [1,100]、`lastChatYear` 冷却标记恒写（与增量是否为零无关）；
 * - **弟子不存在 = 成功静默无操作**（return@update 同语义，不抛不写）。
 */
@org.junit.experimental.categories.Category(com.xianxia.sect.core.RobolectricTests::class)
@RunWith(RobolectricTestRunner::class)
class DiscipleChatEffectNativeTxGateTest {

    @get:Rule
    val writeGuardRule = WriteGuardRule()

    private lateinit var store: FakeAtomicStateStore
    private lateinit var engine: GameEngine

    @Before
    fun setup() {
        store = FakeAtomicStateStore()
        // 不 stub stateSyncServiceRef → AUTHORITATIVE 臂可空判空降级（findings 13）
        engine = GameEngine(
            gameEngineCore = mock<GameEngineCore>(),
            engineContextDispatcher = FakeEngineContextDispatcher(),
            stateStore = store,
            gameRngManager = GameRngManager(),
            explorationFacade = mock(),
            cultivationFacade = mockCultivationFacade(),
            economyFacade = mock(),
            battleFacade = mock()
        )
    }

    /** GameEngine init 块消费面桩（GuideRewardNativeTxGateTest 同款）。 */
    private fun mockCultivationFacade(): com.xianxia.sect.core.engine.domain.cultivation.CultivationFacade =
        mock<com.xianxia.sect.core.engine.domain.cultivation.CultivationFacade>().also {
            whenever(it.cultivationService).thenReturn(mock())
            whenever(it.discipleService).thenReturn(mock())
            val mockProductionFacade = mock<com.xianxia.sect.core.engine.domain.production.ProductionFacade>()
            whenever(mockProductionFacade.productionSlots)
                .thenReturn(kotlinx.coroutines.flow.MutableStateFlow(emptyList()))
            whenever(it.productionFacade).thenReturn(mockProductionFacade)
            val mockPC = mock<com.xianxia.sect.core.engine.domain.production.ProductionCoordinator>()
            whenever(mockPC.repository).thenReturn(mock())
            whenever(it.productionCoordinator).thenReturn(mockPC)
        }

    /** 播种弟子"201"：修为 100.5 / 道德 98 / 忠诚 3 / 悟性 70（clamp 边界可观测）。 */
    private fun seedDisciple() {
        store.update {
            discipleTables.insert(
                Disciple(
                    id = "201",
                    name = "交谈弟子",
                    cultivation = 100.5,
                    skills = SkillStats(morality = 98, loyalty = 3, intelligence = 70),
                    statusData = mapOf("lastChatYear" to "1")
                )
            )
        }
    }

    private fun assembled(): Disciple = store.discipleTables.assemble(201)

    private suspend fun apply(year: Int = 3) = engine.applyConversationEffectAtomic(
        discipleId = "201", currentYear = year,
        moralityDelta = 5, loyaltyDelta = -5,
        cultivationDelta = 0.25, intelligenceDelta = 2
    )

    @Test
    fun `fallback arm applies deltas cooldown clamp verbatim`() = runBlocking {
        seedDisciple()
        NativeEngineFlag.withMode(NativeEngineFlag.Mode.OFF) { apply() }
        val d = assembled()
        assertEquals(100.75, d.cultivation, 1e-9)
        assertEquals("98+5=103 clamp 上界 100", 100, d.skills.morality)
        assertEquals("3-5=-2 clamp 下界 1", 1, d.skills.loyalty)
        assertEquals(72, d.skills.intelligence)
        assertEquals("3", d.statusData["lastChatYear"])
    }

    @Test
    fun `cultivation floors at zero and missing disciple is silent no-op`() = runBlocking {
        seedDisciple()
        NativeEngineFlag.withMode(NativeEngineFlag.Mode.OFF) {
            engine.applyConversationEffectAtomic(
                discipleId = "201", currentYear = 4,
                moralityDelta = 0, loyaltyDelta = 0,
                cultivationDelta = -500.0, intelligenceDelta = 0
            )
            assertEquals("修为下限 0.0", 0.0, assembled().cultivation, 1e-9)
            assertEquals("零增量仍写冷却标记（Kotlin lambda 恒写）", "4", assembled().statusData["lastChatYear"])

            // 弟子不存在 = 成功无操作（Kotlin return@update 同语义，不抛不写）
            engine.applyConversationEffectAtomic(
                discipleId = "999", currentYear = 4,
                moralityDelta = 1, loyaltyDelta = 1,
                cultivationDelta = 1.0, intelligenceDelta = 1
            )
            assertTrue("弟子 999 不存在", store.disciplesSnapshot.none { it.id == "999" })
        }
    }

    @Test
    fun `flag OFF and degraded AUTHORITATIVE arms agree bit for bit`() = runBlocking {
        seedDisciple()
        val off = NativeEngineFlag.withMode(NativeEngineFlag.Mode.OFF) {
            apply()
            assembled().let { Triple(it.cultivation, it.skills, it.statusData) }
        }
        // 复位播种面后重跑 AUTHORITATIVE 臂（镜像缺失 → null 降级 → 同一回退臂）
        store.update {
            discipleTables.remove(201)
            discipleTables.insert(
                Disciple(
                    id = "201", name = "交谈弟子", cultivation = 100.5,
                    skills = SkillStats(morality = 98, loyalty = 3, intelligence = 70),
                    statusData = mapOf("lastChatYear" to "1")
                )
            )
        }
        val auth = NativeEngineFlag.withMode(NativeEngineFlag.Mode.AUTHORITATIVE) {
            apply()
            assembled().let { Triple(it.cultivation, it.skills, it.statusData) }
        }
        assertEquals("两臂终态逐位一致（回退臂）", off, auth)
    }
}
