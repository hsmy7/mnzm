package com.xianxia.sect.core.engine.domain.diplomacy

import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.config.VassalConfig
import com.xianxia.sect.core.model.VassalContract
import com.xianxia.sect.core.state.GameStateStore
import com.xianxia.sect.core.state.GameStateStoreImpl
import com.xianxia.sect.core.util.GameRngManager
import com.xianxia.sect.core.wallet.SpiritStoneLedger
import com.xianxia.sect.core.wallet.SpiritStoneWallet
import com.xianxia.sect.core.event.EventBus
import com.xianxia.sect.di.ApplicationScopeProvider
import com.xianxia.sect.data.GameStateRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.mockito.Mockito.mock

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class VassalServiceTest {

    private lateinit var service: VassalService
    private lateinit var stateStore: GameStateStore
    private lateinit var scopeProvider: ApplicationScopeProvider
    private lateinit var spiritStoneWallet: SpiritStoneWallet

    @Before
    fun setUp() {
        scopeProvider = ApplicationScopeProvider()
        stateStore = GameStateStoreImpl(scopeProvider, mock(GameStateRepository::class.java))
        (stateStore as GameStateStoreImpl).unsafeAllowMainThreadUpdateForTest = true
        spiritStoneWallet = SpiritStoneWallet(stateStore, SpiritStoneLedger(), mock(EventBus::class.java))
        val rngManager = GameRngManager()
        rngManager.initSystemSeed(42L)
        service = VassalService(stateStore, spiritStoneWallet, rngManager)
        runBlocking { stateStore.reset() }
    }

    @After
    fun tearDown() {
        (stateStore as GameStateStoreImpl).unsafeAllowMainThreadUpdateForTest = false
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
    fun `calculateVassalChance powerRatio below 1_0 returns 0`() {
        // powerRatio=0.5(<1.0) → 硬门槛拦截 → 0
        val chance = service.calculateVassalChance(
            playerPower = 5.0, aiPower = 10.0,
            conquestCount = 10, lostSectCount = 0,
            battleWinCount = 10, battleLossCount = 0,
            favor = 100
        )
        assertEquals(0.0, chance, 0.001)
    }

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

    @Test
    fun `calculateVassalChance new account all zeros returns 0`() {
        // 新建号：无战力优势、无战斗记录、无好感 → 0%
        val chance = service.calculateVassalChance(
            playerPower = 1.0, aiPower = 100.0,
            conquestCount = 0, lostSectCount = 0,
            battleWinCount = 0, battleLossCount = 0,
            favor = 0
        )
        assertEquals(0.0, chance, 0.001)
    }

    @Test
    fun `calculateVassalChance zero battle data gives zero occupy and skirmish score`() {
        // powerRatio=2.0，无战斗记录，favor=0(HOSTILE 分值为0)
        // 好感度等级分值为0且favorWeight>0 => 引擎认为该等级不可行 => 返回0
        val chance = service.calculateVassalChance(
            playerPower = 20.0, aiPower = 10.0,
            conquestCount = 0, lostSectCount = 0,
            battleWinCount = 0, battleLossCount = 0,
            favor = 0
        )
        assertEquals(0.0, chance, 0.001)
    }

    @Test
    fun `calculateVassalChance high favor cannot override weak power`() {
        // powerRatio=0.9(<1.0) 但 favor=100 → 硬门槛拦截 → 0
        val chance = service.calculateVassalChance(
            playerPower = 9.0, aiPower = 10.0,
            conquestCount = 5, lostSectCount = 0,
            battleWinCount = 10, battleLossCount = 0,
            favor = 100
        )
        assertEquals(0.0, chance, 0.001)
    }

    @Test
    fun `calculateVassalChance NaN powerRatio returns 0`() {
        val chance = service.calculateVassalChance(
            playerPower = Double.NaN, aiPower = 1.0,
            conquestCount = 0, lostSectCount = 0,
            battleWinCount = 0, battleLossCount = 0,
            favor = 0
        )
        assertEquals(0.0, chance, 0.001)
    }

    @Test
    fun `calculateVassalChance negative counts treated as zero`() {
        val chance = service.calculateVassalChance(
            playerPower = 20.0, aiPower = 10.0,
            conquestCount = -5, lostSectCount = -3,
            battleWinCount = -10, battleLossCount = -2,
            favor = 0
        )
        // favor=0(HOSTILE 分值为0)，好感度等级不可行 => 返回0
        assertEquals(0.0, chance, 0.001)
    }

    @Test
    fun `calculateVassalChance favor beyond 100 clamped`() {
        val clamped = service.calculateVassalChance(
            playerPower = 20.0, aiPower = 10.0,
            conquestCount = 1, lostSectCount = 0,
            battleWinCount = 1, battleLossCount = 0,
            favor = 999
        )
        val normal = service.calculateVassalChance(
            playerPower = 20.0, aiPower = 10.0,
            conquestCount = 1, lostSectCount = 0,
            battleWinCount = 1, battleLossCount = 0,
            favor = 100
        )
        assertEquals(normal, clamped, 0.001)
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
val expected = 1000L + VassalConfig.TRIBUTE_BY_SECT_LEVEL[1]!!
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
        assertEquals(4, VassalConfig.TRIBUTE_BY_SECT_LEVEL.size)
        assertTrue(VassalConfig.TRIBUTE_BY_SECT_LEVEL.values.all { it > 0 })
    }

    // ═══════════════════════════════════════════
    // 附属脱离检查
    // ═══════════════════════════════════════════

    @Test
    fun `processMonthlyBreakawayCheck empty contracts no crash`() {
        // 空契约列表，不应崩溃
        service.processMonthlyBreakawayCheck()
        assertTrue(true)
    }

    @Test
    fun `processMonthlyBreakawayCheck missing aiSect removes contract`() {
        runBlocking {
            stateStore.update {
                gameData = gameData.copy(
                    gameYear = 10,
                    vassalContracts = listOf(
                        VassalContract(vassalSectId = "vanished_sect", establishedYear = 5)
                    ),
                    worldMapSects = listOf(
                        com.xianxia.sect.core.model.WorldSect(id = "player", isPlayerSect = true)
                    )
                )
            }
            // "vanished_sect" 不在 worldMapSects 中 → 契约应被移除
            service.processMonthlyBreakawayCheck()
            assertFalse(service.isPlayerVassal("vanished_sect"))
        }
    }

    @Test
    fun `processMonthlyBreakawayCheck very strong player prevents breakaway`() {
        runBlocking {
            stateStore.update {
                gameData = gameData.copy(
                    gameYear = 10,
                    vassalContracts = listOf(
                        VassalContract(vassalSectId = "sect_weak", establishedYear = 5)
                    ),
                    worldMapSects = listOf(
                        com.xianxia.sect.core.model.WorldSect(id = "player", isPlayerSect = true),
                        com.xianxia.sect.core.model.WorldSect(id = "sect_weak", level = 0)
                    ),
                    aiSectDisciples = mapOf(
                        "sect_weak" to emptyList()
                    )
                )
            }
            // 玩家有弟子（power > 0），AI 无弟子（power = 0）→ aiPower <= 0 → 不应脱离
            service.processMonthlyBreakawayCheck()
            assertTrue(service.isPlayerVassal("sect_weak"))
        }
    }

    // ═══════════════════════════════════════════
    // 配置常量验证
    // ═══════════════════════════════════════════

    @Test
    fun `vassal config weights sum to 1`() {
        val sum = VassalConfig.POWER_WEIGHT + VassalConfig.OCCUPY_WEIGHT +
            VassalConfig.SKIRMISH_WEIGHT + VassalConfig.FAVOR_WEIGHT
        assertEquals(1.0, sum, 0.001)
    }

    @Test
    fun `vassal power score tiers are descending`() {
        assertTrue(VassalConfig.POWER_TIER_5X > VassalConfig.POWER_TIER_3X)
        assertTrue(VassalConfig.POWER_TIER_3X > VassalConfig.POWER_TIER_2X)
        assertTrue(VassalConfig.POWER_TIER_2X > VassalConfig.POWER_RATIO_MIN)
    }

    @Test
    fun `vassal config values are within valid range`() {
        assertTrue(VassalConfig.MAX_VASSAL_CHANCE in 0.0..1.0)
        assertTrue(VassalConfig.MAX_BREAKAWAY_CHANCE in 0.0..1.0)
        assertTrue(VassalConfig.POWER_WEIGHT in 0.0..1.0)
        assertTrue(VassalConfig.OCCUPY_WEIGHT in 0.0..1.0)
        assertTrue(VassalConfig.SKIRMISH_WEIGHT in 0.0..1.0)
        assertTrue(VassalConfig.FAVOR_WEIGHT in 0.0..1.0)
        assertTrue(VassalConfig.VASSALIZE_HARD_THRESHOLD >= 1.0)
    }
}
