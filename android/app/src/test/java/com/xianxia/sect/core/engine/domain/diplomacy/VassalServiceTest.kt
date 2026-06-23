package com.xianxia.sect.core.engine.domain.diplomacy

import com.xianxia.sect.core.GameConfig
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
        service = VassalService(stateStore, scopeProvider)
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
        delay(100)
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
        delay(100)
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
        delay(100)
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
        delay(100)
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
        delay(100)
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
}
