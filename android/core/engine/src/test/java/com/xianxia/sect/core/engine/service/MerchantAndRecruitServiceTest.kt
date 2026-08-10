package com.xianxia.sect.core.engine.service

import com.xianxia.sect.core.engine.FakeAtomicStateStore
import com.xianxia.sect.core.engine.mockSmart
import com.xianxia.sect.core.model.Disciple
import com.xianxia.sect.core.model.GameData
import com.xianxia.sect.core.model.MerchantItem
import com.xianxia.sect.core.state.DiscipleTables
import com.xianxia.sect.core.state.EntityStore
import com.xianxia.sect.core.state.MutableGameState
import com.xianxia.sect.core.model.SpiritStoneExchange
import com.xianxia.sect.core.util.DeterministicRng
import com.xianxia.sect.core.util.GameRngManager
import com.xianxia.sect.core.util.RngPartition
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.`when`
import java.util.UUID

class MerchantAndRecruitServiceTest {

    @Before
    fun setUp() {
        // 重置惰性状态，防止跨测试污染
        RecruitService.RecruitLazyState.autoRecruitIdle = false
        RecruitService.RecruitLazyState.autoRejectIdle = false
    }

    // ==================== calcRecruitBonusCap ====================

    @Test
    fun calcRecruitBonusCap_charmBelow80_returnsZero() {
        assertEquals(0, RecruitService.calcRecruitBonusCap(50))
        assertEquals(0, RecruitService.calcRecruitBonusCap(79))
    }

    @Test
    fun calcRecruitBonusCap_charmAt80_returnsZero() {
        assertEquals(0, RecruitService.calcRecruitBonusCap(80))
    }

    @Test
    fun calcRecruitBonusCap_charm84_returnsOne() {
        assertEquals(1, RecruitService.calcRecruitBonusCap(84))
    }

    @Test
    fun calcRecruitBonusCap_charm88_returnsTwo() {
        assertEquals(2, RecruitService.calcRecruitBonusCap(88))
    }

    @Test
    fun calcRecruitBonusCap_charm100_returnsFive() {
        assertEquals(5, RecruitService.calcRecruitBonusCap(100))
    }

    @Test
    fun calcRecruitBonusCap_boundaryRounding() {
        // (83-80)/4 = 0.75 → floor = 0
        assertEquals(0, RecruitService.calcRecruitBonusCap(83))
        // (84-80)/4 = 1.0 → 1
        assertEquals(1, RecruitService.calcRecruitBonusCap(84))
        // (87-80)/4 = 1.75 → 1
        assertEquals(1, RecruitService.calcRecruitBonusCap(87))
    }

    // ==================== buildMerchantItemPools ====================

    @Test
    fun `buildMerchantItemPools - contains mid and high grade spirit stones`() {
        val service = MerchantAndRecruitService(
            FakeAtomicStateStore(),
            mockSmart(GameRngManager::class.java)
        )
        val pools = service.buildMerchantItemPools()

        val midEntry = pools.poolByRarity[3]?.find { it.name == "中品灵石" && it.type == "spiritStone" }
        val highEntry = pools.poolByRarity[4]?.find { it.name == "上品灵石" && it.type == "spiritStone" }

        assertNotNull("中品灵石应加入稀有度 3 池", midEntry)
        assertNotNull("上品灵石应加入稀有度 4 池", highEntry)

        assertEquals(SpiritStoneExchange.RATIO, pools.priceMap["中品灵石"])
        assertEquals(SpiritStoneExchange.RATIO * SpiritStoneExchange.RATIO, pools.priceMap["上品灵石"])
        assertEquals(3, pools.rarityMap["中品灵石"])
        assertEquals(4, pools.rarityMap["上品灵石"])
    }

    // ==================== selectRarity 年份品阶曲线 ====================

    /** 构造服务并 stub SYSTEM 分区 RNG 为固定种子（现有 mock 未 stub getRng，新测试必须 stub） */
    private fun createServiceWithRng(seed: Long): MerchantAndRecruitService {
        val rngManager = mockSmart(GameRngManager::class.java)
        `when`(rngManager.getRng(RngPartition.SYSTEM)).thenReturn(DeterministicRng.fromSeed(seed))
        return MerchantAndRecruitService(FakeAtomicStateStore(), rngManager)
    }

    @Test
    fun `selectRarity - 第1年全部凡品`() {
        val service = createServiceWithRng(42L)
        repeat(200) { assertEquals(1, service.selectRarity(1)) }
    }

    @Test
    fun `selectRarity - 第50年结果仅限凡品灵品`() {
        val service = createServiceWithRng(42L)
        repeat(200) { assertTrue(service.selectRarity(50) in 1..2) }
    }

    @Test
    fun `selectRarity - 第2000年结果覆盖1到6`() {
        val service = createServiceWithRng(42L)
        repeat(200) { assertTrue(service.selectRarity(2000) in 1..6) }
    }

    // ==================== 保底 ====================

    @Test
    fun `addGuaranteedTopRarityItem - 保底品阶为下一阶段最高品阶`() {
        val service = createServiceWithRng(7L)
        val pools = service.buildMerchantItemPools()
        val cases = mapOf(
            5 to 2,    // [1,20) → 灵品
            60 to 3,   // [20,80) → 宝品（用户例子）
            250 to 4,  // [80,300) → 玄品
            1000 to 6, // [500,1500) → 天品
            2000 to 6  // 末段 → 天品
        )
        for ((year, expected) in cases) {
            val items = mutableListOf<MerchantItem>()
            service.addGuaranteedTopRarityItem(items, pools, year, 1, 10)
            assertEquals("year=$year", expected, items.single().rarity)
        }
    }

    @Test
    fun `addGuaranteedTopRarityItem - 保底品阶池为空时跳过不崩溃`() {
        val service = createServiceWithRng(7L)
        val emptyPools = MerchantItemPools() // poolByRarity 全空
        val items = mutableListOf<MerchantItem>()
        service.addGuaranteedTopRarityItem(items, emptyPools, 60, 1, 10)
        assertTrue(items.isEmpty())
    }

    // ==================== processAutoRecruit ====================

    /** 创建测试用 MutableGameState，含空的 DiscipleTables 并开放写权限。 */
    private fun createAutoRecruitState(
        recruitList: List<Disciple>,
        filter: Set<Int> = emptySet()
    ): MutableGameState {
        val tables = DiscipleTables()
        tables.writeAllowed = true
        return MutableGameState(
            gameData = GameData(
                recruitList = recruitList,
                autoRecruitSpiritRootFilter = filter
            ),
            discipleTables = tables,
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

    /** 创建测试用弟子（默认三灵根 "金,木,水"，realm=9 练气期一层） */
    private fun makeRecruit(
        id: String = "test_${UUID.randomUUID()}",
        name: String = "测试弟子",
        age: Int = 20,
        realm: Int = 9,
        spiritRootType: String = "金,木,水"
    ): Disciple = Disciple(
        id = id,
        name = name,
        age = age,
        realm = realm,
        spiritRootType = spiritRootType
    )

    @Test
    fun `processAutoRecruit with matching filter recruits matching disciples`() {
        val disciple = makeRecruit(spiritRootType = "金,木,水")  // 3 roots
        val state = createAutoRecruitState(
            recruitList = listOf(disciple),
            filter = setOf(3)  // 三灵根
        )

        val count = RecruitService.processAutoRecruit(state)

        assertEquals(1, count)
        assertTrue("自动招募后 recruitList 应为空", state.gameData.recruitList.isEmpty())
        assertEquals("弟子应已加入 discipleTables", 1, state.discipleTables.ids.size)
        val recruitedId = state.discipleTables.ids.first()
        assertEquals("弟子境界应匹配", disciple.realm, state.discipleTables.realms[recruitedId])
    }

    @Test
    fun `processAutoRecruit with empty filter recruits nothing and keeps list`() {
        val disciple = makeRecruit(spiritRootType = "金,木,水")
        val state = createAutoRecruitState(
            recruitList = listOf(disciple),
            filter = emptySet()
        )

        val count = RecruitService.processAutoRecruit(state)

        assertEquals(0, count)
        assertTrue("不应有弟子上架", state.discipleTables.ids.isEmpty())
        assertEquals("recruitList 应保持不变", 1, state.gameData.recruitList.size)
    }

    @Test
    fun `processAutoRecruit filters non-matching root counts`() {
        val disciple = makeRecruit(spiritRootType = "金,木,水", id = "id1")  // 3 roots
        val state = createAutoRecruitState(
            recruitList = listOf(disciple),
            filter = setOf(1, 5)  // 只收单灵根/五灵根
        )

        val count = RecruitService.processAutoRecruit(state)

        assertEquals(0, count)
        assertTrue("不应有弟子上架", state.discipleTables.ids.isEmpty())
        assertEquals("弟子应留在 recruitList", 1, state.gameData.recruitList.size)
    }

    @Test
    fun `processAutoRecruit handles mixed matches and non-matches`() {
        val match = makeRecruit("id1", "单灵根弟子", spiritRootType = "金")           // 1 root
        val noMatch = makeRecruit("id2", "三灵根弟子", spiritRootType = "金,木,水")     // 3 roots
        val state = createAutoRecruitState(
            recruitList = listOf(match, noMatch),
            filter = setOf(1)
        )

        val count = RecruitService.processAutoRecruit(state)

        assertEquals(1, count)
        assertTrue("recruitList 应只剩 1 人", state.gameData.recruitList.size == 1)
        assertEquals("剩下的是不匹配的弟子", "三灵根弟子", state.gameData.recruitList.first().name)
    }

    @Test
    fun `processAutoRecruit skips corrupted disciples with blank name`() {
        val corrupted = makeRecruit(name = "")
        val state = createAutoRecruitState(
            recruitList = listOf(corrupted),
            filter = setOf(3)
        )

        val count = RecruitService.processAutoRecruit(state)

        assertEquals(0, count)
        assertTrue("不应有弟子上架", state.discipleTables.ids.isEmpty())
    }

    @Test
    fun `processAutoRecruit skips corrupted disciples with age zero`() {
        val corrupted = makeRecruit(age = 0)
        val state = createAutoRecruitState(
            recruitList = listOf(corrupted),
            filter = setOf(3)
        )

        val count = RecruitService.processAutoRecruit(state)

        assertEquals(0, count)
        assertTrue("不应有弟子上架", state.discipleTables.ids.isEmpty())
    }

    @Test
    fun `processAutoRecruit skips corrupted disciples with realm out of range`() {
        val corrupted = makeRecruit(realm = -1)
        val state = createAutoRecruitState(
            recruitList = listOf(corrupted),
            filter = setOf(3)
        )

        val count = RecruitService.processAutoRecruit(state)

        assertEquals(0, count)
        assertTrue("不应有弟子上架", state.discipleTables.ids.isEmpty())
    }

    @Test
    fun `processAutoRecruit with empty recruitList returns 0`() {
        val state = createAutoRecruitState(
            recruitList = emptyList(),
            filter = setOf(1, 2, 3)
        )

        val count = RecruitService.processAutoRecruit(state)

        assertEquals(0, count)
        assertTrue("不应有弟子上架", state.discipleTables.ids.isEmpty())
    }

    @Test
    fun `processAutoRecruit recruits newborn age 1 disciple matching filter`() {
        // 新生儿年龄=1 应通过年龄验证（age > 0）
        val baby = makeRecruit(age = 1, spiritRootType = "金")
        val state = createAutoRecruitState(
            recruitList = listOf(baby),
            filter = setOf(1)
        )

        val count = RecruitService.processAutoRecruit(state)

        assertEquals(1, count)
        assertTrue("新生儿应被自动招募", state.discipleTables.ids.isNotEmpty())
    }
}
