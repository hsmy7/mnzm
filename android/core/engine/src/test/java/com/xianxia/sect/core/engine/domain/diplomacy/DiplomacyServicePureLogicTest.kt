package com.xianxia.sect.core.engine.domain.diplomacy

import com.xianxia.sect.core.domain.favor.FavorService
import com.xianxia.sect.core.event.EventBusPort
import com.xianxia.sect.core.engine.system.InventorySystem
import com.xianxia.sect.core.model.GameData
import com.xianxia.sect.core.model.ManualType
import com.xianxia.sect.core.model.MerchantItem
import com.xianxia.sect.core.model.SectDetail
import com.xianxia.sect.core.model.WorldSect
import com.xianxia.sect.core.registry.ManualDatabase
import com.xianxia.sect.core.state.DiscipleTables
import com.xianxia.sect.core.state.EntityStore
import com.xianxia.sect.core.state.GameStateStore
import com.xianxia.sect.core.state.MutableGameState
import com.xianxia.sect.core.util.RarityTimeProgression
import com.xianxia.sect.core.wallet.SpiritStoneWallet
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.kotlin.any
import org.mockito.kotlin.whenever

/**
 * 宗门交易（[DiplomacyService]）纯逻辑测试。
 * 品阶概率核心由 [RarityTimeProgressionTest] 覆盖，此处验证接线：
 * 确定性种子生成、年份敏感、灵石年份门控、年度强制刷新判据。
 */
class DiplomacyServicePureLogicTest {

    @Before
    fun setUp() {
        // generateSectTradeItems 依赖 ManualDatabase（无 isInitialized 守卫，直接调用）。
        // 每次强制覆盖注入 1..6 全品阶功法（与其余测试类一致，不依赖执行顺序）：
        // 年份曲线会 roll 出各品阶的 manual 类型，缺任一品阶会导致 generateRandom
        // 抛 NoSuchElementException。
        // rarity=1 模板名必须为精确名 "测试功法"：MerchantItemConverterTest 守卫式
        // 初始化（先跑时）依赖该名可被 toManual 精确解析，强制覆盖后仍需兼容。
        val templates = (1..6).associate { rarity ->
            val name = if (rarity == 1) "测试功法" else "测试功法$rarity"
            "testManual$rarity" to ManualDatabase.ManualTemplate(
                id = "testManual$rarity",
                name = name,
                type = ManualType.ATTACK,
                rarity = rarity,
                description = "测试用功法"
            )
        }
        ManualDatabase.initializeWithManuals(templates)
    }

    /** 归一化 UUID 字段：id/itemId 由 UUID.randomUUID() 生成，本就不可复现，排除在确定性断言之外 */
    private fun normalizeIds(items: List<MerchantItem>): List<MerchantItem> =
        items.map { it.copy(id = "", itemId = "") }

    // ==================== generateSectTradeItems 确定性 ====================

    @Test
    fun `generateSectTradeItems - 同种子同年份结果恒定`() {
        val service = createService()
        val first = service.generateSectTradeItems(5, "sectA")
        val second = service.generateSectTradeItems(5, "sectA")
        // id/itemId 是 UUID（非确定性），其余字段（名称/类型/品阶/价格/数量）必须完全一致
        assertEquals("同 (sectId, year) 确定性生成应逐项一致（除 UUID）", normalizeIds(first), normalizeIds(second))
    }

    @Test
    fun `generateSectTradeItems - 不同年份结果不同`() {
        val service = createService()
        val year5 = service.generateSectTradeItems(5, "sectA")
        val year6 = service.generateSectTradeItems(6, "sectA")
        assertNotEquals("年份参与种子，不同年份结果应不同", normalizeIds(year5), normalizeIds(year6))
    }

    // ==================== 灵石年份门控 ====================

    @Test
    fun `generateSectTradeItems - 早期年份无灵石（年份上限门控）`() {
        val service = createService()
        // 带 sectId 走确定性种子，结果固定，断言稳定
        val items = service.generateSectTradeItems(5, "sectA")
        assertTrue("第 5 年（凡品上限）不应出现灵石", items.none { it.type == "spiritStone" })
    }

    @Test
    fun `generateSectTradeItems - 晚期年份可出现灵石`() {
        val service = createService()
        // 多个种子（不同 sectId）任一生成含灵石即可（单次约 5% 概率抽不到灵石类型）
        val hasSpiritStone = (1..20).any { i ->
            service.generateSectTradeItems(2000, "sect$i").any { it.type == "spiritStone" }
        }
        assertTrue("第 2000 年（天品段）应可刷出灵石", hasSpiritStone)
    }

    // ==================== 品阶年份上限 ====================

    @Test
    fun `generateSectTradeItems - 非灵石物品品阶不超年份上限`() {
        val service = createService()
        for (year in listOf(5, 50, 200, 400, 1000)) {
            val items = service.generateSectTradeItems(year, "sect$year")
            val max = RarityTimeProgression.maxRarityForYear(year)
            for (item in items) {
                if (item.type != "spiritStone") {
                    assertTrue(
                        "year=$year item=${item.name} rarity=${item.rarity} max=$max",
                        item.rarity <= max
                    )
                }
            }
        }
    }

    // ==================== refreshAllSectTrades 年度强制刷新 ====================

    @Test
    fun `refreshAllSectTrades - 满3年刷新 不满不刷 空列表兜底刷`() {
        val stateStore = mock(GameStateStore::class.java)
        val gameData = GameData(
            worldMapSects = listOf(
                WorldSect(id = "player", isPlayerSect = true),
                WorldSect(id = "sect1"),
                WorldSect(id = "sect2")
            ),
            sectDetails = mapOf(
                // sect1：有商品，上次刷新第 10 年 → 第 12 年不满 3 年不刷，第 13 年刷
                "sect1" to SectDetail(
                    sectId = "sect1",
                    tradeLastRefreshYear = 10,
                    tradeItems = listOf(dummyItem("old1"))
                ),
                // sect2：列表为空 → 空列表兜底，任何年份都刷
                "sect2" to SectDetail(sectId = "sect2", tradeLastRefreshYear = 10)
            )
        )
        val mutableState = createMutableState(gameData)
        whenever(stateStore.gameData).thenReturn(MutableStateFlow(gameData))
        whenever(stateStore.modifyState(any())).thenAnswer { invocation ->
            invocation.getArgument<MutableGameState.() -> Unit>(0).invoke(mutableState)
        }
        val service = createService(stateStore)

        // 第 12 年：sect1 不满 3 年不刷（保留旧商品），sect2 空列表兜底刷新
        service.refreshAllSectTrades(12)
        val sect1After12 = mutableState.gameData.sectDetails.getValue("sect1")
        assertEquals("sect1 未满 3 年不应刷新", 10, sect1After12.tradeLastRefreshYear)
        assertEquals("old1", sect1After12.tradeItems.single().name)
        assertEquals("sect2 空列表应刷新", 12, mutableState.gameData.sectDetails.getValue("sect2").tradeLastRefreshYear)

        // 第 13 年：sect1 满 3 年强制刷新
        service.refreshAllSectTrades(13)
        val sect1After13 = mutableState.gameData.sectDetails.getValue("sect1")
        assertEquals("sect1 满 3 年应刷新", 13, sect1After13.tradeLastRefreshYear)
        assertTrue("sect1 刷新后商品为新列表", sect1After13.tradeItems.isNotEmpty())
    }

    @Test
    fun `refreshAllSectTrades - tradeLastRefreshYear未来值按0自愈立即刷新`() {
        val stateStore = mock(GameStateStore::class.java)
        val gameData = GameData(
            worldMapSects = listOf(
                WorldSect(id = "player", isPlayerSect = true),
                WorldSect(id = "sect1")
            ),
            sectDetails = mapOf(
                // 未来值 + 商品非空：差值判据为负本会永不刷新（死锁），自愈按 0 处理立即刷新
                "sect1" to SectDetail(
                    sectId = "sect1",
                    tradeLastRefreshYear = 99999,
                    tradeItems = listOf(dummyItem("old"))
                )
            )
        )
        val mutableState = createMutableState(gameData)
        whenever(stateStore.gameData).thenReturn(MutableStateFlow(gameData))
        whenever(stateStore.modifyState(any())).thenAnswer { invocation ->
            invocation.getArgument<MutableGameState.() -> Unit>(0).invoke(mutableState)
        }
        val service = createService(stateStore)

        service.refreshAllSectTrades(500)
        val sect1 = mutableState.gameData.sectDetails.getValue("sect1")
        assertEquals("未来值应被自愈写回当前年份", 500, sect1.tradeLastRefreshYear)
        assertTrue("未来值 + 非空商品应触发刷新", sect1.tradeItems.isNotEmpty())
        assertNotEquals("商品应为新列表", "old", sect1.tradeItems.firstOrNull()?.name)
    }

    @Test
    fun `refreshAllSectTrades - 无宗门详情时直接返回`() {
        val stateStore = mock(GameStateStore::class.java)
        whenever(stateStore.gameData).thenReturn(MutableStateFlow(GameData()))
        val service = createService(stateStore)
        service.refreshAllSectTrades(100) // 不应抛异常
    }

    // ==================== isAlly（原有逻辑保留） ====================

    private fun isAllyCheck(alliances: List<Pair<String, String>>, sectId: String): Boolean {
        return alliances.any { (first, second) ->
            (first == "player" && second == sectId) ||
                (first == sectId && second == "player")
        }
    }

    @Test
    fun `isAlly - player in alliance returns true`() {
        assertTrue(isAllyCheck(listOf("player" to "sect1"), "sect1"))
    }

    @Test
    fun `isAlly - not in alliance returns false`() {
        assertFalse(isAllyCheck(listOf("player" to "sect1"), "sect2"))
    }

    @Test
    fun `isAlly - empty alliances returns false`() {
        assertFalse(isAllyCheck(emptyList(), "sect1"))
    }

    @Test
    fun `isAlly - other alliance not affecting`() {
        assertFalse(isAllyCheck(listOf("player" to "sect2"), "sect1"))
    }

    // ==================== 工具 ====================

    private fun createService(stateStore: GameStateStore = mock(GameStateStore::class.java)): DiplomacyService {
        return DiplomacyService(
            stateStore = stateStore,
            inventorySystem = mock(InventorySystem::class.java),
            eventBus = mock(EventBusPort::class.java),
            favorService = mock(FavorService::class.java),
            spiritStoneWallet = mock(SpiritStoneWallet::class.java),
            rngManager = mock(com.xianxia.sect.core.util.GameRngManager::class.java)
        )
    }

    private fun dummyItem(name: String): MerchantItem = MerchantItem(
        id = "id-$name",
        name = name,
        type = "equipment",
        itemId = "item-$name",
        rarity = 1,
        price = 100L,
        quantity = 1
    )

    private fun createMutableState(gameData: GameData): MutableGameState = MutableGameState(
        gameData = gameData,
        discipleTables = DiscipleTables(),
        equipmentStacks = EntityStore(),
        equipmentInstances = EntityStore(),
        manualStacks = EntityStore(),
        manualInstances = EntityStore(),
        pills = EntityStore(),
        materials = EntityStore(),
        herbs = EntityStore(),
        seeds = EntityStore(),
        storageBags = EntityStore(),
        teams = emptyList(),
        battleLogs = emptyList(),
        isPaused = false,
        isLoading = false,
        isSaving = false
    )
}
