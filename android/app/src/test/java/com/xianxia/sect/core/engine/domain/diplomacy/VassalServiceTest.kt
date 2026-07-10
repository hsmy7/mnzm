package com.xianxia.sect.core.engine.domain.diplomacy

import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.model.VassalContract
import com.xianxia.sect.core.state.GameStateStore
import com.xianxia.sect.core.state.GameStateStoreImpl
import com.xianxia.sect.di.ApplicationScopeProvider
import com.xianxia.sect.data.GameStateRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.mock

class VassalServiceTest {

    private lateinit var service: VassalService
    private lateinit var stateStore: GameStateStore
    private lateinit var scopeProvider: ApplicationScopeProvider

    @Before
    fun setUp() {
        scopeProvider = ApplicationScopeProvider()
        stateStore = GameStateStoreImpl(scopeProvider, mock(GameStateRepository::class.java))
        service = VassalService(stateStore)
        runBlocking { stateStore.reset() }
    }

    @After
    fun tearDown() {
        runBlocking { stateStore.reset() }
    }

    // --- 建立附庸 ---

    @Test
    fun `establishVassalage sets suzerain`() = runBlocking {
        service.establishVassalage("sect_master")
assertEquals("sect_master", service.getSuzerainSectId())
        assertTrue(service.isVassal())
    }

    // --- 初始独立 ---

    @Test
    fun `initial state is independent`() {
        assertEquals("", service.getSuzerainSectId())
        assertFalse(service.isVassal())
    }

    // --- 非附庸不扣年贡 ---

    @Test
    fun `processYearlyTribute does nothing when independent`() = runBlocking {
        stateStore.update {
            gameData = gameData.copy(
                suzerainSectId = "",
                lastYearSpiritStoneIncome = 10_000L,
                spiritStones = 50_000L
            )
        }
        service.processYearlyTribute()
assertEquals(50_000L, stateStore.gameData.value.spiritStones)
    }

    // --- 年贡50% ---

    @Test
    fun `processYearlyTribute deducts 50 percent`() = runBlocking {
        stateStore.update {
            gameData = gameData.copy(
                suzerainSectId = "sect_master",
                lastYearSpiritStoneIncome = 10_000L,
                spiritStones = 50_000L
            )
        }
        service.processYearlyTribute()
val expectedTribute = (10_000L * 0.5).toLong()
        assertEquals(50_000L - expectedTribute, stateStore.gameData.value.spiritStones)
    }

    // --- 收入0年贡0 ---

    @Test
    fun `processYearlyTribute zero income zero tribute`() = runBlocking {
        stateStore.update {
            gameData = gameData.copy(
                suzerainSectId = "sect_master",
                lastYearSpiritStoneIncome = 0L,
                spiritStones = 50_000L
            )
        }
        service.processYearlyTribute()
assertEquals(50_000L, stateStore.gameData.value.spiritStones)
    }

    // --- 年贡最低1灵石 ---

    @Test
    fun `processYearlyTribute minimum tribute is 1`() = runBlocking {
        stateStore.update {
            gameData = gameData.copy(
                suzerainSectId = "sect_master",
                lastYearSpiritStoneIncome = 1L,
                spiritStones = 10L
            )
        }
        service.processYearlyTribute()
assertEquals(9L, stateStore.gameData.value.spiritStones)
    }

    // --- 配置常量 ---

    @Test
    fun `vassal tribute ratio is 50 percent`() {
        assertEquals(0.5, GameConfig.AIAttack.VASSAL_TRIBUTE_RATIO, 0.001)
    }

    @Test
    fun `vassal tribute min is 1`() {
        assertEquals(1L, GameConfig.AIAttack.VASSAL_TRIBUTE_MIN)
    }

    // ═══════════════════════════════════════
    // 新增：AI是玩家的附属
    // ═══════════════════════════════════════════

    // --- 计算接受概率：战力差 ---

    @Test
    fun `calculateVassalChance powerRatio below 1_5 returns 0`() {
        // powerRatio=1.0(<1.5) → powerScore=0
        // totalOccupy=1, occupyRatio=0/1=0 → occupyScore=0
        // totalSkirmish=1, skirmishRatio=0/1=0 → skirmishScore=0
        // favor=0 → favorScore=0
        val chance = service.calculateVassalChance(
            playerPower = 10.0, aiPower = 10.0,
            conquestCount = 0, lostSectCount = 1,
            battleWinCount = 0, battleLossCount = 1,
            favor = 0
        )
        assertEquals(0.0, chance, 0.001)
    }

    @Test
    fun `calculateVassalChance powerRatio 5 or more returns high`() {
        val chance = service.calculateVassalChance(
            playerPower = 50.0, aiPower = 10.0,
            conquestCount = 1, lostSectCount = 0,
            battleWinCount = 1, battleLossCount = 0,
            favor = 80
        )
        assertTrue(chance > 0.7)
    }

    // --- 计算接受概率：占领丢失 ---

    @Test
    fun `calculateVassalChance high conquest ratio increases chance`() {
        val highConquest = service.calculateVassalChance(
            playerPower = 30.0, aiPower = 10.0,
            conquestCount = 5, lostSectCount = 0,
            battleWinCount = 1, battleLossCount = 0,
            favor = 50
        )
        val lowConquest = service.calculateVassalChance(
            playerPower = 30.0, aiPower = 10.0,
            conquestCount = 0, lostSectCount = 5,
            battleWinCount = 1, battleLossCount = 0,
            favor = 50
        )
        assertTrue(highConquest > lowConquest)
    }

    // --- 计算接受概率：胜负 ---

    @Test
    fun `calculateVassalChance high win rate increases chance`() {
        val highWin = service.calculateVassalChance(
            playerPower = 30.0, aiPower = 10.0,
            conquestCount = 1, lostSectCount = 0,
            battleWinCount = 10, battleLossCount = 0,
            favor = 50
        )
        val lowWin = service.calculateVassalChance(
            playerPower = 30.0, aiPower = 10.0,
            conquestCount = 1, lostSectCount = 0,
            battleWinCount = 0, battleLossCount = 10,
            favor = 50
        )
        assertTrue(highWin > lowWin)
    }

    // --- 计算接受概率：好感度 ---

    @Test
    fun `calculateVassalChance high favor increases chance`() {
        val highFavor = service.calculateVassalChance(
            playerPower = 30.0, aiPower = 10.0,
            conquestCount = 1, lostSectCount = 0,
            battleWinCount = 1, battleLossCount = 0,
            favor = 80
        )
        val lowFavor = service.calculateVassalChance(
            playerPower = 30.0, aiPower = 10.0,
            conquestCount = 1, lostSectCount = 0,
            battleWinCount = 1, battleLossCount = 0,
            favor = 20
        )
        assertTrue(highFavor > lowFavor)
    }

    // --- 附属请求：目标不存在 ---

    @Test
    fun `requestVassalContract non existent sect returns false`() = runBlocking {
        val result = service.requestVassalContract("nonexistent")
        assertFalse(result)
    }

    // --- 附属请求：玩家不能附属自己 ---

    @Test
    fun `requestVassalContract cannot vassal own sect`() = runBlocking {
        stateStore.update {
            gameData = gameData.copy(
                worldMapSects = listOf(
                    com.xianxia.sect.core.model.WorldSect(
                        id = "player_sect", isPlayerSect = true
                    )
                )
            )
        }
        val result = service.requestVassalContract("player_sect")
        assertFalse(result)
    }

    // --- 解散附属 ---

    @Test
    fun `dissolveVassalContract removes contract`() = runBlocking {
        stateStore.update {
            gameData = gameData.copy(
                vassalContracts = listOf(
                    VassalContract(
                        vassalSectId = "sect_ai",
                        establishedYear = 1
                    )
                )
            )
        }
        assertTrue(service.isPlayerVassal("sect_ai"))

        service.dissolveVassalContract("sect_ai")
        assertFalse(service.isPlayerVassal("sect_ai"))
    }

    // --- 年贡：新契约当年不计 ---

    @Test
    fun `processYearlyVassalTribute skips first year`() = runBlocking {
        stateStore.update {
            gameData = gameData.copy(
                gameYear = 5,
                spiritStones = 0L,
                vassalContracts = listOf(
                    VassalContract(
                        vassalSectId = "sect_ai",
                        establishedYear = 5,
                        lastTributeYear = 0
                    )
                ),
                worldMapSects = listOf(
                    com.xianxia.sect.core.model.WorldSect(
                        id = "sect_ai", level = 1
                    )
                )
            )
        }
        service.processYearlyVassalTribute(5)
// establishedYear(5) >= year(5) → 当年不计贡，灵石保持不变
        assertEquals(0L, stateStore.gameData.value.spiritStones)
    }

    // --- 年贡：第二年正常到账 ---

    @Test
    fun `processYearlyVassalTribute adds tribute`() = runBlocking {
        stateStore.update {
            gameData = gameData.copy(
                gameYear = 6,
                spiritStones = 1000L,
                vassalContracts = listOf(
                    VassalContract(
                        vassalSectId = "sect_ai",
                        establishedYear = 5,
                        lastTributeYear = 5
                    )
                ),
                worldMapSects = listOf(
                    com.xianxia.sect.core.model.WorldSect(
                        id = "sect_ai", level = 1
                    )
                )
            )
        }
        service.processYearlyVassalTribute(6)
val expected = 1000L + GameConfig.Vassal.TRIBUTE_BY_SECT_LEVEL[1]!!
        assertEquals(expected, stateStore.gameData.value.spiritStones)
    }

    // --- 年贡：已处理契约跳过 ---

    @Test
    fun `processYearlyVassalTribute skips already paid`() = runBlocking {
        stateStore.update {
            gameData = gameData.copy(
                gameYear = 6,
                spiritStones = 1000L,
                vassalContracts = listOf(
                    VassalContract(
                        vassalSectId = "sect_ai",
                        establishedYear = 5,
                        lastTributeYear = 6
                    )
                ),
                worldMapSects = listOf(
                    com.xianxia.sect.core.model.WorldSect(
                        id = "sect_ai", level = 1
                    )
                )
            )
        }
        service.processYearlyVassalTribute(6)
assertEquals(1000L, stateStore.gameData.value.spiritStones)
    }

    // --- 配置常量 ---

    @Test
    fun `vassal tribute config defined for all levels`() {
        assertEquals(4, GameConfig.Vassal.TRIBUTE_BY_SECT_LEVEL.size)
        assertTrue(GameConfig.Vassal.TRIBUTE_BY_SECT_LEVEL.values.all { it > 0 })
    }
}
