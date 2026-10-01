package com.xianxia.sect.core.engine

import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.util.GameRngManager
import com.xianxia.sect.core.engine.domain.cultivation.CultivationFacade
import com.xianxia.sect.core.engine.domain.disciple.DiscipleFactory
import com.xianxia.sect.core.engine.domain.disciple.DiscipleService
import com.xianxia.sect.core.engine.domain.economy.EconomyFacade
import com.xianxia.sect.core.engine.domain.inventory.InventoryFacade
import com.xianxia.sect.core.engine.domain.production.ProductionCoordinator
import com.xianxia.sect.core.engine.domain.production.ProductionFacade
import com.xianxia.sect.core.model.BattleLog
import com.xianxia.sect.core.model.CharacterTemplateDb
import com.xianxia.sect.core.model.Disciple
import com.xianxia.sect.core.model.DiscipleAggregate
import com.xianxia.sect.core.model.EquipmentInstance
import com.xianxia.sect.core.model.GameData
import com.xianxia.sect.core.model.GridBuildingData
import com.xianxia.sect.core.model.Herb
import com.xianxia.sect.core.model.ManualInstance
import com.xianxia.sect.core.model.ManualStack
import com.xianxia.sect.core.model.Material
import com.xianxia.sect.core.model.Pill
import com.xianxia.sect.core.model.RewardCardItem
import com.xianxia.sect.core.model.Seed
import com.xianxia.sect.core.model.StorageBag
import com.xianxia.sect.core.model.WorldSect
import com.xianxia.sect.core.model.guide.GuideCounterKeys
import com.xianxia.sect.core.state.BattleResultUIData
import com.xianxia.sect.core.state.BootPhase
import com.xianxia.sect.core.state.DiscipleTables
import com.xianxia.sect.core.state.EntityStore
import com.xianxia.sect.core.state.GameStateStore
import com.xianxia.sect.core.state.MutableGameState
import com.xianxia.sect.core.state.PendingBeastAttack
import com.xianxia.sect.core.state.RunState
import com.xianxia.sect.core.state.WriteGuardRule
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.experimental.categories.Category
import org.junit.runner.RunWith
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner

/**
 * GameEngineCoordination / GameEngineLoadDataOps 数据完整性与开局口径守卫。
 *
 * 覆盖两类契约：
 * 1. 数据完整性（worldMapSects 重生、initialMine 4×4、native 基线重导）；
 * 2. **开局三臂同构口径**（G08：`createNewGame`、`restartGameInternal` 有名臂、
 *    `restartGameInternal` else 臂）——起始灵石、星级账本、单名模板弟子、
 *    年度与引导计数，见 [assertStartupBaseline]。
 *
 * 跑在 Robolectric 沙箱：开局名册要经 `DiscipleService.instantiateTemplate` 真实落
 * `DiscipleTables`，而 `ComponentTable` 底层是 `android.util.SparseArray`——
 * 纯 JVM 任务（`testJvmRelease`）下 `returnDefaultValues=true` 会让写入静默失效、
 * 读回只剩默认值，名册断言无从成立。
 */
@Category(com.xianxia.sect.core.RobolectricTests::class)
@RunWith(RobolectricTestRunner::class)
class GameEngineCoordinationTest {

    /** 开局名册要真实写组件表，需绕过 DiscipleTables 的 update{} 外写守卫 */
    @get:Rule val writeGuardRule = WriteGuardRule()

    @Test
    fun `ensureGameDataIntegrity - worldMapSects 空时重生`() = runBlocking {
        val env = EngineTestEnv()
        env.store.gameDataValue = GameData().copy(
            sectName = "青云宗",
            worldMapSects = emptyList()
        )

        env.engine.ensureGameDataIntegrity()

        val result = env.store.gameDataValue
        assertTrue("worldMapSects 应被重生为非空",
            result.worldMapSects.isNotEmpty())
        assertTrue("应包含玩家宗门",
            result.worldMapSects.any { it.isPlayerSect })
    }

    @Test
    fun `ensureGameDataIntegrity - worldMapSects 非空时跳过`() = runBlocking {
        val env = EngineTestEnv()
        env.store.gameDataValue = GameData().copy(
            sectName = "青云宗",
            worldMapSects = listOf(WorldSect(
                id = "player_sect", name = "青云宗",
                x = 849f, y = 463f, isPlayerSect = true,
                level = 3, discovered = true, relation = 100
            ))
        )

        env.engine.ensureGameDataIntegrity()

        assertEquals("worldMapSects 应保留原有 1 个",
            1, env.store.gameDataValue.worldMapSects.size)
    }

    @Test
    fun `ensureHeavyDataLoaded - worldMapSects 非空时标记完成`() = runBlocking {
        // 短路前置 worldMapSects 非空校验
        val env = EngineTestEnv()
        env.store.gameDataValue = GameData().copy(
            sectName = "青云宗",
            worldMapSects = listOf(WorldSect(
                id = "player_sect", name = "青云宗",
                x = 849f, y = 463f, isPlayerSect = true,
                level = 3, discovered = true, relation = 100
            ))
        )

        env.engine.ensureHeavyDataLoaded()

        assertTrue("worldMapSects 非空应标记 heavyDataLoaded",
            env.engine.heavyDataLoaded)
    }

    @Test
    fun `ensureHeavyDataLoaded - worldMapSects 为空时不标记完成`() = runBlocking {
        // C12：数据缺失时不标记完成——后续调用重试，由 ensureGameDataIntegrity 重生
        val env = EngineTestEnv()
        env.store.gameDataValue = GameData().copy(
            sectName = "青云宗",
            worldMapSects = emptyList()
        )

        env.engine.ensureHeavyDataLoaded()

        org.junit.Assert.assertFalse("worldMapSects 为空应保持未完成",
            env.engine.heavyDataLoaded)
    }

    @Test
    fun `ensureGameDataIntegrity - sectName 空时不崩溃`() = runBlocking {
        val env = EngineTestEnv()
        env.store.gameDataValue = GameData().copy(
            sectName = "",
            worldMapSects = emptyList()
        )

        env.engine.ensureGameDataIntegrity()
        assertTrue("sectName 为空时 worldMapSects 仍为空",
            env.store.gameDataValue.worldMapSects.isEmpty())
    }

    @Test
    fun `createNewGame - mapSeed 非零`() = runBlocking {
        val env = EngineTestEnv()
        // 生产代码会访问 productionCoordinator.repository，stub 避免 mock 返回 null
        whenever(env.engine.productionCoordinator.repository).thenReturn(mock())

        env.engine.createNewGame("青云宗")

        assertTrue("新游戏 mapSeed 不应为 0", env.store.gameDataValue.mapSeed != 0)
    }

    @Test
    fun `restartGameSuspend - mapSeed 非零（旧实现恒为 0 的回归守卫）`() = runBlocking {
        val env = EngineTestEnv()

        env.engine.restartGameSuspend("")

        assertTrue("重启后 mapSeed 不应为 0，否则全分区 PRNG 种子归零且地图相同",
            env.store.gameDataValue.mapSeed != 0)
    }

    @Test
    fun `restartGameSuspend - 两次重启种子不同`() = runBlocking {
        val env = EngineTestEnv()

        env.engine.restartGameSuspend("")
        val first = env.store.gameDataValue.mapSeed
        env.engine.restartGameSuspend("")
        val second = env.store.gameDataValue.mapSeed

        assertTrue("两次重启应产生不同地图种子（相同为缺陷）", first != second)
    }

    // ── 初始灵矿场 4×4 与 spirit_mine 配置一致 ──

    @Test
    fun `createNewGame - 初始灵矿场为 4x4 与配置一致`() = runBlocking {
        val env = EngineTestEnv()
        whenever(env.engine.productionCoordinator.repository).thenReturn(mock())

        env.engine.createNewGame("青云宗")

        val mine = env.store.gameDataValue.placedBuildings.single()
        assertEquals("初始灵矿场宽度应为 4（spirit_mine 配置占地）", 4, mine.width)
        assertEquals("初始灵矿场高度应为 4（spirit_mine 配置占地）", 4, mine.height)
        assertEquals("本宗建筑 sectId 应为空串", "", mine.sectId)
        assertEquals("灵矿场应居中放置", GameConfig.SectMap.WORLD_WIDTH_CELLS / 2 - 1, mine.gridX)
    }

    @Test
    fun `restartGameSuspend - 初始灵矿场为 4x4 与配置一致`() = runBlocking {
        val env = EngineTestEnv()

        env.engine.restartGameSuspend("青云宗")

        val mine = env.store.gameDataValue.placedBuildings.single()
        assertEquals("重启后初始灵矿场宽度应为 4", 4, mine.width)
        assertEquals("重启后初始灵矿场高度应为 4", 4, mine.height)
    }

    // ── 开局三臂同构口径（G08 验收①②③ + D-15/D-16） ──
    //
    // 三条臂：GameEngineLoadDataOps.kt 的 createNewGame、restartGameInternal 有名臂、
    // restartGameInternal else 臂（sectName.isBlank()）。三臂共用
    // GameData.withStartupLedger() + instantiateStartupDisciple()，任一处漏接即本组判红。

    @Test
    fun `createNewGame - 开局口径为单名周明加起始账本`() = runBlocking {
        val env = EngineTestEnv()

        env.engine.createNewGame("青云宗")

        assertStartupBaseline("createNewGame", env)
    }

    @Test
    fun `restartGameSuspend - 有名臂开局口径与 createNewGame 同构`() = runBlocking {
        val env = EngineTestEnv()

        env.engine.restartGameSuspend("青云宗")

        assertStartupBaseline("restartGameInternal(有名臂)", env)
    }

    @Test
    fun `restartGameSuspend - 无名臂开局口径同构且灵石不退回首档哨兵值`() = runBlocking {
        val env = EngineTestEnv()

        env.engine.restartGameSuspend("")

        // else 臂不生成世界（无 initialMine 是预存口径差异，归 G10），
        // 但开局账本三件套与名册必须与另两臂逐字同构
        assertStartupBaseline("restartGameInternal(else 臂)", env)
    }

    @Test
    fun `enterSect - 仅更新 activeSectId 不触碰 placedBuildings`() = runBlocking {
        // 契约守卫：GameViewModel 命令总线重推依赖 enterSect 只改 activeSectId
        //（若 enterSect 顺带修改 placedBuildings，总线键 (activeSectId, placedBuildings)
        // 会同时失效，重推语义被破坏）
        // enterSect 会话内收敛——activeSectId 必须是 worldMapSects
        // 中玩家持有（isPlayerSect/isPlayerOccupied）的宗门，否则被净化归 ""。
        // 种子先声明 ai-1 为玩家持有宗门；无孤儿建筑时 placedBuildings 仍不被触碰
        //（收敛只动失配数据，幂等）。
        val env = EngineTestEnv()
        val mine = GridBuildingData(
            buildingId = "灵矿场", displayName = "灵矿场",
            gridX = 10, gridY = 10, width = 4, height = 4,
            instanceId = "m1", sectId = ""
        )
        env.store.gameDataValue = env.store.gameDataValue.copy(
            worldMapSects = listOf(WorldSect(id = "ai-1", isPlayerSect = true)),
            placedBuildings = listOf(mine)
        )

        env.engine.enterSect("ai-1")

        val data = env.store.gameDataValue
        assertEquals("activeSectId 应切换为 ai-1", "ai-1", data.activeSectId)
        assertEquals("placedBuildings 不应被 enterSect 修改", 1, data.placedBuildings.size)
        assertEquals("建筑内容不应变化", mine, data.placedBuildings.single())
    }

    // ── 根因守卫：读档/新游戏/重启后必须重导 C++ native 引擎基线 ──
    // loadData 只更新 Kotlin GameStateStore，若不同步 C++（AUTHORITATIVE 真相源），
    // tick 反向镜像会把 native 残留的旧档状态覆盖回 Kotlin。
    // 以下守卫验证三个状态替换入口都会触发 importToNative。
    //（测试通过反射置 GameCoreBridge.loaded=true 模拟 native 已加载；finally 恢复，
    //  避免污染其他用例——GameCoreBridge 为进程级单例）

    /** 测试用真实 ProductionSlotRepository（依赖全 mock，scope 用真实作用域使 stateIn 可初始化） */
    private fun realSlotRepository(): com.xianxia.sect.core.repository.ProductionSlotRepository {
        val scopeProvider = mock<com.xianxia.sect.core.util.CoroutineScopeProvider>()
        val scope = kotlinx.coroutines.CoroutineScope(
            kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Unconfined
        )
        whenever(scopeProvider.scope).thenReturn(scope)
        whenever(scopeProvider.ioScope).thenReturn(scope)
        return com.xianxia.sect.core.repository.ProductionSlotRepository(
            dao = mock(),
            configService = mock(),
            scopeProvider = scopeProvider
        )
    }

    @Test
    fun `loadData - 完成后重导 native 基线（云读档防旧档覆盖）`() = runBlocking {
        val env = EngineTestEnv()
        val syncMock = mock<com.xianxia.sect.core.nativebridge.StateSyncService>()
        whenever(env.engine.gameEngineCore.stateSyncServiceRef).thenReturn(syncMock)
        // alignProductionSlotsWithRepository 直读 repository.getSlots()——该函数与属性 slots
        //（StateFlow）的 getter 同名，Mockito 按名匹配会抛 WrongTypeOfReturnValue（BootSequenceControllerTest
        // 同坑注释），故用真实 ProductionSlotRepository 实例（依赖全 mock，getSlots 返回真实空列表）。
        // 注意：必须先完成 realSlotRepository 的内部 stub，再赋给外层 stub（thenReturn 参数求值
        // 中不得再 stub 其他 mock，否则 UnfinishedStubbingException）
        val realRepo = realSlotRepository()
        whenever(env.engine.productionCoordinator.repository).thenReturn(realRepo)
        setGameCoreLoaded(true)
        try {
            env.engine.loadData(
                gameData = GameData().apply { sectName = "云档" },
                disciples = emptyList(),
                equipmentInstances = emptyList(),
                manualStacks = emptyList(),
                manualInstances = emptyList(),
                pills = emptyList()
            )
            verify(syncMock).importToNative()
            Unit
        } finally {
            setGameCoreLoaded(false)
        }
    }

    @Test
    fun `createNewGame - 完成后重导 native 基线（新游戏防旧档覆盖）`() = runBlocking {
        val env = EngineTestEnv()
        val syncMock = mock<com.xianxia.sect.core.nativebridge.StateSyncService>()
        whenever(env.engine.gameEngineCore.stateSyncServiceRef).thenReturn(syncMock)
        setGameCoreLoaded(true)
        try {
            env.engine.createNewGame("青云宗")
            verify(syncMock).importToNative()
            Unit
        } finally {
            setGameCoreLoaded(false)
        }
    }

    @Test
    fun `restartGameSuspend - 完成后重导 native 基线（重启防旧世界覆盖）`() = runBlocking {
        val env = EngineTestEnv()
        val syncMock = mock<com.xianxia.sect.core.nativebridge.StateSyncService>()
        whenever(env.engine.gameEngineCore.stateSyncServiceRef).thenReturn(syncMock)
        setGameCoreLoaded(true)
        try {
            env.engine.restartGameSuspend("")
            verify(syncMock).importToNative()
            Unit
        } finally {
            setGameCoreLoaded(false)
        }
    }

    @Test
    fun `native 未加载时读档静默跳过基线导入（降级契约）`() = runBlocking {
        val env = EngineTestEnv()
        val syncMock = mock<com.xianxia.sect.core.nativebridge.StateSyncService>()
        whenever(env.engine.gameEngineCore.stateSyncServiceRef).thenReturn(syncMock)
        val realRepo = realSlotRepository()
        whenever(env.engine.productionCoordinator.repository).thenReturn(realRepo)
        // GameCoreBridge.loaded 保持默认 false（.so 未加载）：loadNativeBaseline 首行短路，
        // importToNative 不得被调用，读档照常成功（双实现并行契约降级）
        env.engine.loadData(
            gameData = GameData().apply { sectName = "本地档" },
            disciples = emptyList(),
            equipmentInstances = emptyList(),
            manualStacks = emptyList(),
            manualInstances = emptyList(),
            pills = emptyList()
        )
        verify(syncMock, never()).importToNative()
        Unit
    }

    /** 反射设置 GameCoreBridge.loaded（模拟 native 库已加载；测试后必须恢复） */
    private fun setGameCoreLoaded(loaded: Boolean) {
        val field = com.xianxia.sect.core.nativebridge.GameCoreBridge::class.java
            .getDeclaredField("loaded")
        field.isAccessible = true
        field.setBoolean(com.xianxia.sect.core.nativebridge.GameCoreBridge, loaded)
    }
}

// ── 开局口径断言助手（三臂共用；放文件级以免测试类函数数继续膨胀） ──

/**
 * 开局口径公共断言（三臂共用）：名册、账本、计数、收入曲线四组。
 *
 * 数值锚点一律取 `GameConfig` / `CharacterTemplateDb` 常量，只有「产品事实」
 * （开局是谁、立绘键叫什么）用字面量，以免开局口径被无声改掉。
 */
private fun assertStartupBaseline(operation: String, env: EngineTestEnv) {
    assertStartupRoster(operation, env)
    assertStartupLedgerAndCounters(operation, env.store.gameDataValue)
}

/** 验收①：名册恰好一名弟子，身份全部来自开局模板 */
private fun assertStartupRoster(operation: String, env: EngineTestEnv) {
    val tables = env.store.discipleTables
    val template = requireNotNull(
        CharacterTemplateDb.byId(CharacterTemplateDb.STARTUP_TEMPLATE_ID)
    ) { "开局模板必须在 CharacterTemplateDb 内（core/domain/.../model/CharacterTemplate.kt）" }
    val roster = with(tables) { ids.map { assemble(it) } }

    assertEquals(
        "$operation：开局名册必须恰好 1 名弟子（弟子入册入口只剩模板实例化）",
        1, roster.size
    )
    val startup = roster.single()
    assertEquals(
        "$operation：验收①——开局 templateId 必须落模板 id",
        CharacterTemplateDb.STARTUP_TEMPLATE_ID, startup.templateId
    )
    assertEquals(
        "$operation：验收①——开局弟子立绘必须取模板 portraitKey，不得回落通用像",
        template.portraitKey, startup.portraitRes
    )
    assertEquals("$operation：姓名取自模板", template.name, startup.name)
    assertEquals("$operation：性别取自模板", template.gender, startup.gender)
    assertEquals("$operation：灵根取自模板", template.spiritRootType, startup.spiritRootType)
    assertEquals(
        "$operation：境界取 STARTUP_REALM", CharacterTemplateDb.STARTUP_REALM, startup.realm
    )
    assertEquals(
        "$operation：层数取 STARTUP_REALM_LAYER",
        CharacterTemplateDb.STARTUP_REALM_LAYER, startup.realmLayer
    )
}

/** 验收②③ + D-3/D-4/D-15/D-16：起始灵石、星级账本、无碎片、入门计数、收入曲线 */
private fun assertStartupLedgerAndCounters(operation: String, data: GameData) {
    assertEquals(
        "开局模板 id 是产品事实（验收①），改 CharacterTemplateDb.STARTUP_TEMPLATE_ID " +
            "前须先确认产品口径",
        "zhouming", CharacterTemplateDb.STARTUP_TEMPLATE_ID
    )
    assertEquals(
        "开局立绘键名是素材契约（rules/static-resources.md），改名须同步精灵注册",
        "portrait_zhouming",
        requireNotNull(
            CharacterTemplateDb.byId(CharacterTemplateDb.STARTUP_TEMPLATE_ID)
        ).portraitKey
    )
    assertEquals(
        "$operation：验收②——新档灵石必须等于 GameConfig.Gacha.START_SPIRIT_STONES" +
            "（三臂共用 GameData.withStartupLedger，漏接即退回 GameData 哨兵值 1000）",
        GameConfig.Gacha.START_SPIRIT_STONES.toLong(), data.spiritStones
    )
    assertEquals(
        "$operation：验收③——星级账本必须恰为开局模板 1 星实例（D-4 直写）",
        mapOf(CharacterTemplateDb.STARTUP_TEMPLATE_ID to CharacterTemplateDb.STARTER_STAR),
        data.gachaStarMap
    )
    assertFalse(
        "$operation：验收③/Q34——开局不送碎片，gachaFragmentCounts 不得含开局模板键",
        data.gachaFragmentCounts.containsKey(CharacterTemplateDb.STARTUP_TEMPLATE_ID)
    )
    assertEquals(
        "$operation：Q34——开局碎片账本整表为空", emptyMap<String, Int>(),
        data.gachaFragmentCounts
    )
    assertEquals(
        "$operation：D-16——开局入宗计入年度新增弟子", 1, data.annualNewDisciples
    )
    assertEquals(
        "$operation：D-15——开局必须继续自增 disciplesRecruited 引导计数器" +
            "（键名禁改，改则旧档引导倒退）",
        1L, data.guideCounters[GuideCounterKeys.DISCIPLES_RECRUITED] ?: 0L
    )
    assertEquals(
        "$operation：验收②——开局灵石是一次性注入，不得进入年度收入曲线" +
            "（走 SpiritStoneWallet.add 即违反）",
        0L, data.annualTotalIncome
    )
    assertTrue(
        "$operation：验收②——开局灵石不得进入分渠道年度收入",
        data.annualIncomeBySource.isEmpty()
    )
}

// ── 测试用 GameEngine + GameStateStore 的最小化环境 ──

private class EngineTestEnv {
    val store = SimpleStore()

    /**
     * 引擎与开局服务共用同一 [GameRngManager]：`createNewGame` / `restartGameInternal`
     * 先 `initSystemSeed` 播种，再在事务内实例化开局模板弟子——两个实例会让
     * 名册随机流落在播种之外（与生产单例语义不符）。
     */
    val gameRngManager = GameRngManager()

    /**
     * 开局名册走**真实** [DiscipleService]：G08 起新档弟子由
     * `GameEngine.instantiateStartupDisciple` → `DiscipleService.instantiateTemplate`
     * 构造并落 `DiscipleTables`。mock 掉本服务等于把「验收① templateId 有生产写入者」
     * 变成自证，故只 mock 未参与模板实例化的 4 个协作依赖（被调用即 smart-null 可见）。
     */
    val discipleService = DiscipleService(
        stateStore = store,
        discipleFactory = DiscipleFactory(),
        rngManager = gameRngManager,
        discipleEquipmentService = mockSmart(),
        discipleLifecycleManager = mockSmart(),
        discipleSlotManager = mockSmart(),
        discipleStatusService = mockSmart(),
        inventorySystem = mockSmart()
    )

    // D1：构造时 highFrequencyData/productionSlots 经 Facade 访问器求值——stub 链防 NPE
    private val mockCultivationFacade = mock<CultivationFacade>().also {
        org.mockito.kotlin.whenever(it.cultivationService).thenReturn(mock())
        org.mockito.kotlin.whenever(it.discipleService).thenReturn(discipleService)
        val mockProductionFacade = mock<ProductionFacade>()
        org.mockito.kotlin.whenever(mockProductionFacade.productionSlots)
            .thenReturn(kotlinx.coroutines.flow.MutableStateFlow(emptyList()))
        org.mockito.kotlin.whenever(it.productionFacade).thenReturn(mockProductionFacade)
        val mockPC = mock<ProductionCoordinator>()
        org.mockito.kotlin.whenever(mockPC.repository).thenReturn(mock())
        org.mockito.kotlin.whenever(it.productionCoordinator).thenReturn(mockPC)
        // loadData 链路 checkAndCollectCompletedSlots → buildingFacade.autoHarvestCompletedAlchemySlots
        org.mockito.kotlin.whenever(it.buildingFacade).thenReturn(mock())
    }
    private val mockEconomyFacade = mock<EconomyFacade>().also {
        val mockInventoryFacade = mock<InventoryFacade>()
        org.mockito.kotlin.whenever(mockInventoryFacade.inventorySystem).thenReturn(mock())
        org.mockito.kotlin.whenever(it.inventoryFacade).thenReturn(mockInventoryFacade)
        org.mockito.kotlin.whenever(it.mailService).thenReturn(mock())
    }

    // 根因修复配套：loadData/createNewGame/restartGame 末尾调用
    // syncNativeBaselineAfterLoad → loadNativeBaseline(stateSyncService)——
    // stateSyncServiceRef 必须 stub 非 null（否则 Kotlin 非空参数检查抛 NPE）；
    // 默认 GameCoreBridge.isLoaded=false 使 loadNativeBaseline 首行短路，零副作用
    val mockGameEngineCore = mock<GameEngineCore>().also {
        org.mockito.kotlin.whenever(it.stateSyncServiceRef).thenReturn(mock())
    }

    val engine = GameEngine(
        gameEngineCore = mockGameEngineCore,
        engineContextDispatcher = FakeEngineContextDispatcher(),
        stateStore = store,
        gameRngManager = gameRngManager,
        explorationFacade = mock(),
        cultivationFacade = mockCultivationFacade,
        economyFacade = mockEconomyFacade,
        battleFacade = mock()
    )
}

private class SimpleStore : GameStateStore {

    /** 测试用：直接读写 GameData */
    var gameDataValue: GameData = GameData()

    private val _gameDataFlow = MutableStateFlow(GameData())
    override val gameData: StateFlow<GameData> get() = _gameDataFlow
    override val gameDataSnapshot: GameData get() = gameDataValue

    private val _tables = DiscipleTables()
    override val discipleTables: DiscipleTables get() = _tables

    // EntityStore 实例（MutableGameState 需要）
    private val eqInstances = EntityStore<EquipmentInstance>()
    private val mnStacks = EntityStore<ManualStack>()
    private val mnInstances = EntityStore<ManualInstance>()
    private val pils = EntityStore<Pill>()
    private val mats = EntityStore<Material>()
    private val hrbs = EntityStore<Herb>()
    private val sds = EntityStore<Seed>()
    private val stBags = EntityStore<StorageBag>()

    /**
     * 重入事务缓冲——与生产 `GameStateStoreImpl.reentrantCount / reentrantBuffer`
     * 同语义（app/src/main/.../GameStateStoreImpl.kt 的 update 头部）。
     *
     * 开局三臂在 `stateStore.update {}` **内部**调用 `DiscipleService.instantiateTemplate`
     * （其写入走 `updateAndReturn`）。嵌套事务必须并入同一个 [MutableGameState]，
     * 否则内层写入会被外层最后写回的副本覆盖——起始灵石账本与入门/年度计数互相吞没。
     */
    private var reentrantBuffer: MutableGameState? = null

    private fun newMutableState() = MutableGameState(
        gameData = gameDataValue,
        discipleTables = _tables,
        equipmentInstances = eqInstances,
        manualStacks = mnStacks,
        manualInstances = mnInstances,
        pills = pils,
        materials = mats,
        herbs = hrbs,
        seeds = sds,
        storageBags = stBags,
        battleLogs = emptyList(),
        isPaused = false,
        isLoading = false,
        isSaving = false
    )

    override fun update(block: MutableGameState.() -> Unit) {
        reentrantBuffer?.let { nested ->
            nested.block()
            return
        }
        val mutable = newMutableState()
        reentrantBuffer = mutable
        try {
            block(mutable)
        } finally {
            reentrantBuffer = null
        }
        gameDataValue = mutable.gameData
        _gameDataFlow.value = mutable.gameData
    }
    override val lifecycleState = MutableStateFlow(GameStateStore.LifecycleState())
    override val bootPhase = MutableStateFlow(BootPhase.UNINITIALIZED)
    override val runState = MutableStateFlow(RunState.IDLE)
    override val disciples = MutableStateFlow<List<Disciple>>(emptyList())
    override val discipleAggregates = MutableStateFlow<List<DiscipleAggregate>>(emptyList())
    override val equipmentInstances = MutableStateFlow<List<EquipmentInstance>>(emptyList())
    override val manualStacks = MutableStateFlow<List<ManualStack>>(emptyList())
    override val manualInstances = MutableStateFlow<List<ManualInstance>>(emptyList())
    override val pills = MutableStateFlow<List<Pill>>(emptyList())
    override val materials = MutableStateFlow<List<Material>>(emptyList())
    override val herbs = MutableStateFlow<List<Herb>>(emptyList())
    override val seeds = MutableStateFlow<List<Seed>>(emptyList())
    override val storageBags = MutableStateFlow<List<StorageBag>>(emptyList())
    override val battleLogs = MutableStateFlow<List<BattleLog>>(emptyList())
    override val isPaused = MutableStateFlow(false)
    override val isLoading = MutableStateFlow(false)
    override val isSaving = MutableStateFlow(false)
    override val pendingBattleResult = MutableStateFlow<BattleResultUIData?>(null)
    override val rewardCardQueue = MutableStateFlow<List<RewardCardItem>>(emptyList())
    override val pendingBeastAttacks = MutableStateFlow<List<PendingBeastAttack>>(emptyList())
    override val pendingBattleRewardCards = MutableStateFlow<List<RewardCardItem>>(emptyList())
    override val sectCombatPower = MutableStateFlow(0L)
    override val aiSectCombatPowers = MutableStateFlow<Map<String, Long>>(emptyMap())
    override val highFreqState = MutableStateFlow(GameStateStore.HighFreqState())
    override val entityState = MutableStateFlow(GameStateStore.EntityState())
    override val configState = MutableStateFlow(GameStateStore.ConfigState())
    override val disciplesSnapshot: List<Disciple> get() = emptyList()
    override val equipmentInstancesSnapshot: List<EquipmentInstance> get() = emptyList()
    override val manualStacksSnapshot: List<ManualStack> get() = emptyList()
    override val manualInstancesSnapshot: List<ManualInstance> get() = emptyList()
    override val pillsSnapshot: List<Pill> get() = emptyList()
    override val materialsSnapshot: List<Material> get() = emptyList()
    override val herbsSnapshot: List<Herb> get() = emptyList()
    override val seedsSnapshot: List<Seed> get() = emptyList()
    override val storageBagsSnapshot: List<StorageBag> get() = emptyList()
    override val battleLogsSnapshot: List<BattleLog> get() = emptyList()
    override val discipleAggregatesSnapshot: List<DiscipleAggregate> get() = emptyList()
    override val warehouseFullEvent = MutableSharedFlow<String>()
    override var activeTab: String = ""
    override var activeDialog: String? = null
    override var activeSubDialogs: Set<String> = emptySet()
    override fun getCurrentSeeds(): List<Seed> = emptyList()
    override fun getCurrentHerbs(): List<Herb> = emptyList()
    override fun getCurrentMaterials(): List<Material> = emptyList()
    override fun setPendingBattleResult(result: BattleResultUIData) = Unit
    override fun clearPendingBattleResult() = Unit
    override fun setPendingBeastAttacks(attacks: List<PendingBeastAttack>) = Unit
    override fun clearPendingBeastAttacks() = Unit
    override fun removePendingBeastAttack(beastLevelId: String) = Unit
    override fun setPendingBattleRewardCards(cards: List<RewardCardItem>) = Unit
    override fun clearPendingBattleRewardCards() = Unit
    override fun enqueueRewardCards(items: List<RewardCardItem>) = Unit
    override fun clearRewardCardQueue(count: Int) = Unit
    @Suppress("UNCHECKED_CAST")
    override fun <R> updateAndReturn(block: MutableGameState.() -> R): R {
        reentrantBuffer?.let { return block(it) }
        var result: R? = null
        update { result = block() }
        return result as R
    }
    override fun modifyState(block: MutableGameState.() -> Unit) { update(block) }
    override fun setPausedDirect(paused: Boolean) = Unit
    override fun setLoadingDirect(loading: Boolean) = Unit
    override fun setSavingDirect(saving: Boolean) = Unit
    override suspend fun loadFromSnapshot(
        gameData: GameData, disciples: List<Disciple>,
        equipmentInstances: List<EquipmentInstance>,
        manualStacks: List<ManualStack>, manualInstances: List<ManualInstance>,
        pills: List<Pill>, materials: List<Material>, herbs: List<Herb>,
        seeds: List<Seed>, storageBags: List<StorageBag>,
        battleLogs: List<BattleLog>,
        isPaused: Boolean, isLoading: Boolean, isSaving: Boolean
    ) { this.gameDataValue = gameData }
    override suspend fun reset() {
        gameDataValue = GameData()
        _gameDataFlow.value = gameDataValue
        // 生产 resetForSlot 同时清空名册（app/src/main/.../GameStateStoreImpl.kt 的
        // resetForSlot）：不清则模板「限持 1」判定会把上一档的开局弟子算进来，
        // 重启臂的开局口径就测不到真实行为
        _tables.clear()
    }
    override fun advanceBootPhase() = Unit
    override fun resetBootPhase() = Unit
    override fun setPlaying() = Unit
    override fun setReloading() = Unit
    override fun setLoading() = Unit
    override fun setIdle() = Unit
    override fun enterBatchEmissionMode() = Unit
    override fun exitBatchEmissionMode() = Unit
    override fun takeAtomicSnapshot(): GameStateStore.GameSnapshot = GameStateStore.GameSnapshot()
}
