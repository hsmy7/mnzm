package com.xianxia.sect.ui.game

import com.xianxia.sect.core.SectLevel
import com.xianxia.sect.core.audio.AudioConfig
import com.xianxia.sect.core.audio.AudioPlayerFacade
import com.xianxia.sect.core.engine.GameEngine
import com.xianxia.sect.core.engine.GameEngineCore
import com.xianxia.sect.core.engine.di.IoDispatcher
import com.xianxia.sect.core.engine.domain.building.BuildingFacade
import com.xianxia.sect.core.engine.domain.disciple.DiscipleFacade
import com.xianxia.sect.core.engine.service.MailService
import com.xianxia.sect.core.engine.system.SystemManager
import com.xianxia.sect.core.engine.notifyUserInteraction
import com.xianxia.sect.core.engine.setActiveDialog
import com.xianxia.sect.core.engine.setFocusedDiscipleId
import com.xianxia.sect.core.engine.updateDisciple
import com.xianxia.sect.core.engine.updateGameData
import com.xianxia.sect.core.model.Disciple
import com.xianxia.sect.core.model.DiscipleAggregate
import com.xianxia.sect.core.model.EquipmentStack
import com.xianxia.sect.core.model.GameData
import com.xianxia.sect.core.model.production.ProductionSlot
import com.xianxia.sect.ui.game.perf.GpuTierDetector
import com.xianxia.sect.core.perf.ThermalMonitor
import com.xianxia.sect.core.perf.ThermalState
import com.xianxia.sect.data.SessionManager
import com.xianxia.sect.ui.game.delegate.GameLoopDelegate
import com.xianxia.sect.ui.game.sect.SurfaceProviderFactory
import io.mockk.MockKAnnotations
import io.mockk.coEvery
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.runs
import io.mockk.unmockkAll
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/**
 * 每宗独立地图 + 进入宗门转场测试。
 *
 * - [deriveSectSeed]：主宗复用 baseSeed；被占宗门派生确定性且各宗不同的种子
 * - [buildSectMap]：同种子产出同一地图、不同种子产出不同地图、尺寸正确
 * - GameViewModel 集成：sectMapData 随 activeSectId 变化；enterSect 触发转场，
 *   目标宗门地图就绪且至少 1 秒后自动关闭
 */
class GameViewModelSectMapTest {

    private val gameEngine: GameEngine = mockk(relaxed = true)
    private val gameEngineCore: GameEngineCore = mockk(relaxed = true)
    private val systemManager: SystemManager = mockk(relaxed = true)
    private val buildingConfigService: com.xianxia.sect.core.config.BuildingConfigService = mockk(relaxed = true)
    private val mailService: MailService = mockk(relaxed = true)
    private val discipleFacade: DiscipleFacade = mockk(relaxed = true)
    private val buildingFacade: BuildingFacade = mockk(relaxed = true)
    private val thermalMonitor: ThermalMonitor = mockk(relaxed = true)
    private val dialogManager: com.xianxia.sect.core.domain.dialog.DialogManager = mockk(relaxed = true)
    private val adService: com.xianxia.sect.core.engine.service.AdService = mockk(relaxed = true)
    private val audioConfig: AudioConfig = mockk(relaxed = true)
    private val audioEngine: AudioPlayerFacade = mockk(relaxed = true)
    private val sessionManager: SessionManager = mockk(relaxed = true)

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var viewModel: GameViewModel
    private lateinit var gameDataFlow: MutableStateFlow<GameData>

    @Before
    fun setUp() {
        MockKAnnotations.init(this, relaxUnitFun = true)
        Dispatchers.setMain(testDispatcher)

        coEvery { gameEngine.launchOnEngine(any()) } returns mockk<Job>(relaxed = true)

        gameDataFlow = MutableStateFlow(GameData())
        every { gameEngine.gameData } returns gameDataFlow
        every { gameEngine.resourcesHeader } returns MutableStateFlow(
            com.xianxia.sect.core.gameview.GameViewStore.RESOURCES_EMPTY
        )
        every { gameEngine.discipleAggregates } returns MutableStateFlow(emptyList<DiscipleAggregate>())
        every { gameEngine.disciples } returns MutableStateFlow(emptyList<Disciple>())
        every { gameEngine.equipmentStacks } returns MutableStateFlow(emptyList<EquipmentStack>())
        every { gameEngine.productionSlots } returns MutableStateFlow(emptyList<ProductionSlot>())
        every { gameEngine.gameDataSnapshot } returns GameData(
            worldMapSects = listOf(com.xianxia.sect.core.model.WorldSect(
                id = "player_sect", name = "玩家宗门", level = SectLevel.TOP, isPlayerSect = true
            ))
        )

        GameLoopDelegate.healthCheckEnabled = false
        every { systemManager.errors } returns emptyFlow()
        every { thermalMonitor.thermalState } returns MutableStateFlow(ThermalState.NORMAL)
        every { dialogManager.currentDialog } returns MutableStateFlow(null)

        mockkStatic("com.xianxia.sect.core.engine.GameEngineCoordinationKt")
        mockkStatic("com.xianxia.sect.core.engine.GameEngineGuideOpsKt")
        every { gameEngine.setFocusedDiscipleId(any()) } just runs
        every { gameEngine.notifyUserInteraction() } just runs
        every { gameEngine.setActiveDialog(any()) } just runs
        coEvery { gameEngine.updateGameData(any()) } returns Unit
        coEvery { gameEngine.updateDisciple(any<String>(), any()) } returns Unit

        viewModel = GameViewModel(
            gameEngine,
            GameVmAudioServices(audioConfig, audioEngine),
            GameVmCoreServices(gameEngineCore, systemManager, thermalMonitor),
            GameVmUiServices(dialogManager, adService),
            GameVmDelegateServices(
                mailService, buildingConfigService,
                buildingFacade, discipleFacade,
                IoDispatcher(testDispatcher),
                sessionManager,
                GpuTierDetector()
            ),
            SurfaceProviderFactory { mockk() }
        )
    }

    @After
    fun tearDown() {
        GameLoopDelegate.healthCheckEnabled = true
        Dispatchers.resetMain()
        unmockkAll()
    }

    // ════════════════════════════════════════════════════════════════
    // deriveSectSeed / buildSectMap 纯函数
    // ════════════════════════════════════════════════════════════════

    @Test
    fun `deriveSectSeed - 主宗复用基础种子`() {
        assertEquals("主宗（空 id）必须复用 baseSeed", 42, deriveSectSeed(42, ""))
    }

    @Test
    fun `deriveSectSeed - 被占宗门种子确定性且与主宗他宗不同`() {
        val main = deriveSectSeed(42, "")
        val a1 = deriveSectSeed(42, "sect_a")
        val a2 = deriveSectSeed(42, "sect_a")
        val b = deriveSectSeed(42, "sect_b")
        assertEquals("同一宗门种子必须确定", a1, a2)
        assertNotEquals("被占宗门种子不得等于主宗", main, a1)
        assertNotEquals("不同宗门种子必须不同", a1, b)
    }

    @Test
    fun `buildSectMap - 同种子产出相同地图且尺寸正确`() {
        val seed = deriveSectSeed(42, "sect_a")
        val m1 = buildSectMap(seed)
        val m2 = buildSectMap(seed)
        assertEquals("尺寸应为 128×128", 128, m1.worldWidthCells)
        assertTrue("同种子地图必须完全一致", m1.flatTileData.contentEquals(m2.flatTileData))
        assertTrue("flatTileData 长度应为 128×128", m1.flatTileData.size == 128 * 128)
    }

    @Test
    fun `buildSectMap - 主宗与被占宗门地图不同`() {
        val main = buildSectMap(deriveSectSeed(42, ""))
        val captured = buildSectMap(deriveSectSeed(42, "sect_a"))
        assertFalse(
            "被占宗门底图应与主宗不同（否则每宗一图无意义）",
            main.flatTileData.contentEquals(captured.flatTileData)
        )
    }

    // ════════════════════════════════════════════════════════════════
    // GameViewModel 集成：sectMapData + 转场
    // ════════════════════════════════════════════════════════════════

    /** 轮询等待 sectMapData 就绪（flowOn(Default) 生成在真实线程，不能用 advanceUntilIdle 加速） */
    private fun awaitSectMap(expectedSectId: String) {
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline) {
            val v = viewModel.sectMapData.value
            if (v != null && v.sectId == expectedSectId) return
            Thread.sleep(5)
        }
        fail("sectMapData 未在超时内就绪: expected=$expectedSectId actual=${viewModel.sectMapData.value?.sectId}")
    }

    @Test
    fun `sectMapData - 游戏未加载时保持 null，加载后随 activeSectId 切换`() = runTest(testDispatcher) {
        assertNull("未加载（sectName 为空）时应为 null", viewModel.sectMapData.value)

        gameDataFlow.value = GameData(sectName = "青云宗", mapSeed = 42, activeSectId = "")
        awaitSectMap("")
        val mainState = viewModel.sectMapData.value
        assertNotNull("主宗地图应就绪", mainState)
        assertEquals("主宗状态 sectId 应为主宗", "", mainState!!.sectId)

        gameDataFlow.value = GameData(sectName = "青云宗", mapSeed = 42, activeSectId = "sect_a")
        awaitSectMap("sect_a")
        val capturedState = viewModel.sectMapData.value
        assertNotNull("被占宗门地图应就绪", capturedState)
        assertEquals("被占宗门状态 sectId 应正确", "sect_a", capturedState!!.sectId)
        assertFalse(
            "切到被占宗门后底图应更换",
            mainState.map.flatTileData.contentEquals(capturedState.map.flatTileData)
        )
    }

    @Test
    fun `enterSect - 触发转场且引擎切换 activeSectId 后自动关闭`() = runTest(testDispatcher) {
        gameDataFlow.value = GameData(sectName = "青云宗", mapSeed = 42, activeSectId = "")
        awaitSectMap("")

        viewModel.enterSect("sect_a")
        assertTrue("进入宗门应立即触发转场", viewModel.sectTransitionActive.value)

        // 模拟引擎 enterSect 完成：activeSectId 切到目标（转场关闭条件）
        gameDataFlow.value = GameData(sectName = "青云宗", mapSeed = 42, activeSectId = "sect_a")
        awaitSectMap("sect_a")

        // 推进 viewModelScope(Main=testDispatcher) 上的转场协程（activeSectId 切换 + 0.8s 最小时长）
        advanceUntilIdle()

        // 轮询等待转场协程关闭（切换后切回主线程可能还需极短真实时间）
        val deadline = System.currentTimeMillis() + 5_000
        while (viewModel.sectTransitionActive.value && System.currentTimeMillis() < deadline) {
            Thread.sleep(5)
        }
        assertFalse("activeSectId 已切换且超过最小时长后转场必须自动关闭", viewModel.sectTransitionActive.value)
    }
}
