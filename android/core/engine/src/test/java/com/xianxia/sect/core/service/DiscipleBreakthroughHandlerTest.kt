package com.xianxia.sect.core.engine.service

import com.xianxia.sect.core.engine.FakeAtomicStateStore
import com.xianxia.sect.core.engine.domain.disciple.DiscipleStatCalculator
import com.xianxia.sect.core.engine.domain.disciple.ITEM_TYPE_PILL
import com.xianxia.sect.core.engine.domain.disciple.TYPE_INNER
import com.xianxia.sect.core.engine.mockSmart
import com.xianxia.sect.core.model.CombatAttributes
import com.xianxia.sect.core.model.Disciple
import com.xianxia.sect.core.model.ElderSlots
import com.xianxia.sect.core.model.GameData
import com.xianxia.sect.core.model.ItemEffect
import com.xianxia.sect.core.model.Pill
import com.xianxia.sect.core.model.PillEffect
import com.xianxia.sect.core.model.SkillStats
import com.xianxia.sect.core.model.StorageBagItem
import com.xianxia.sect.core.model.comprehension
import com.xianxia.sect.core.model.currentHp
import com.xianxia.sect.core.model.currentMp
import com.xianxia.sect.core.model.storageBagItems
import com.xianxia.sect.core.state.DiscipleTables
import com.xianxia.sect.core.state.EntityStore
import com.xianxia.sect.core.state.MutableGameState
import com.xianxia.sect.core.state.WriteGuardRule
import com.xianxia.sect.core.util.GameRngManager
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.Rule
import org.mockito.Mockito
import org.mockito.kotlin.any
import org.robolectric.RobolectricTestRunner



@org.junit.experimental.categories.Category(com.xianxia.sect.core.RobolectricTests::class)
@RunWith(RobolectricTestRunner::class)
class DiscipleBreakthroughHandlerTest {

    @get:Rule val writeGuardRule = WriteGuardRule()
    private lateinit var tables: DiscipleTables
    private lateinit var state: MutableGameState
    private lateinit var stateStore: FakeAtomicStateStore
    private lateinit var cultivationCore: CultivationCore
    private lateinit var handler: DiscipleBreakthroughHandler

    @Before
    fun setUp() {
        // Fake 提供真实语义：discipleTables 持久实例（insertDiscipleForBreakthrough 直写、
        // 跨事务保留）+ gameData 同步 + flows 默认空列表——等价 mock 时代逐条 stub
        stateStore = FakeAtomicStateStore().also {
            it.setGameData(GameData(gameYear = 5, gameMonth = 1))
        }
        tables = stateStore.discipleTables
        state = createMutableState(tables)
        cultivationCore = mockSmart(CultivationCore::class.java)
        Mockito.`when`(cultivationCore.isDiscipleFullHpMp(
            any<Disciple>(),
            any<MutableGameState>()
        )).thenReturn(true)
        Mockito.`when`(cultivationCore.getLifespanGainForRealm(
            any<Int>()
        )).thenReturn(100)
        Mockito.`when`(cultivationCore.calculateDiscipleCultivationPerPhase(
            any<Disciple>(),
            any<GameData>(),
            any<DiscipleTables>()
        )).thenReturn(50.0)

        val rngManager = GameRngManager()
        rngManager.initSystemSeed(12345L)
        handler = DiscipleBreakthroughHandler(
            stateStore = stateStore,
            cultivationCore = cultivationCore,
            scopeProvider = mockSmart(),
            relativeGiftHandler = mockSmart(),
            rngManager = rngManager
        )
    }

    // ==================== 辅助方法 ====================

    private fun insertDiscipleForBreakthrough(
        id: Int = 1,
        realm: Int = 9,
        realmLayer: Int = 9,
        comprehension: Int = 100,
        cultivation: Double = 999999.0,
        currentHp: Int = -1,
        currentMp: Int = -1,
        spiritRootType: String = "metal",
        statusData: Map<String, String> = emptyMap()
    ) {
        val disciple = Disciple(
            id = id.toString(),
            name = "突破弟子$id",
            realm = realm,
            realmLayer = realmLayer,
            spiritRootType = spiritRootType,
            age = 20,
            lifespan = 80,
            skills = SkillStats(comprehension = comprehension),
            statusData = statusData,
            combat = CombatAttributes(
                currentHp = currentHp,
                currentMp = currentMp
            )
        )
        tables.insert(disciple)
        tables.isAlive[id] = 1
        tables.cultivations[id] = cultivation
    }

    private fun createMutableState(tables: DiscipleTables) = MutableGameState(
        gameData = GameData(),
        discipleTables = tables,
        equipmentStacks = EntityStore(emptyList()),
        equipmentInstances = EntityStore(emptyList()),
        manualStacks = EntityStore(emptyList()),
        manualInstances = EntityStore(emptyList()),
        pills = EntityStore(emptyList()),
        materials = EntityStore(emptyList()),
        herbs = EntityStore(emptyList()),
        seeds = EntityStore(emptyList()),
        storageBags = EntityStore(emptyList()),
                battleLogs = emptyList(),
        isPaused = false,
        isLoading = false,
        isSaving = false
    )

    // ═══════════════════════════════════════════════════════════════
    // performBreakthrough — success changes realm or layer
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `performBreakthrough - success changes realm or layer`() {
        insertDiscipleForBreakthrough(id = 1, realm = 9, realmLayer = 9)

        val original = tables.assemble(1)
        val gameData = GameData()
        val result = handler.performBreakthrough(original, state, gameData)

        assertEquals("cultivation should be reset to 0", 0.0, result.cultivation, 0.0)

        val realmChanged = result.realm < original.realm || result.realmLayer != original.realmLayer
        val hpMpReduced = result.combat.currentHp <= (original.maxHp * 0.1).toInt().coerceAtLeast(1)
        assertTrue(
            "breakthrough should either change realm/layer (success) or reduce HP/MP (failure)",
            realmChanged || hpMpReduced
        )
    }

    // ═══════════════════════════════════════════════════════════════
    // performBreakthrough — failure reduces HP and MP
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `performBreakthrough - failure reduces HP and MP`() {
        // 必败构造：境界7层5 + 五灵根 → BREAKTHROUGH_CHANCES[7][5]=0.00 → chance=0 必然失败
        // （2026-08-11 修复预存假阳性：原 comprehension=1 构造实为大概率成功路径，断言 || 宽松从未验证失败）
        insertDiscipleForBreakthrough(
            id = 1, realm = 7, realmLayer = 5,
            spiritRootType = "metal,wood,water,fire,earth",
            currentHp = 1000, currentMp = 500
        )

        val original = tables.assemble(1)
        val gameData = GameData()
        val result = handler.performBreakthrough(original, state, gameData)

        val failCount = tables.breakthroughFailCounts[1] ?: 0
        assertTrue("必败构造下 failCount 应 > 0，实际 $failCount", failCount > 0)
        val hpUpper = (1000 * DiscipleStatCalculator.BREAKTHROUGH_FAILURE_HP_MP_RATIO).toInt().coerceAtLeast(1)
        val mpUpper = (500 * DiscipleStatCalculator.BREAKTHROUGH_FAILURE_HP_MP_RATIO).toInt().coerceAtLeast(1)
        assertTrue("HP should be at most 10% of original on failure",
            result.combat.currentHp <= hpUpper)
        assertTrue("MP should be at most 10% of original on failure",
            result.combat.currentMp <= mpUpper)
        assertEquals("realm should not change on failure", original.realm, result.realm)
    }

    // ═══════════════════════════════════════════════════════════════
    // performBreakthrough — auto pill from warehouse
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `performBreakthrough - auto uses breakthrough pill from warehouse`() {
        // realmLayer=8 < maxLayers=9 → pillTargetRealm=realm=9, 匹配 targetRealm=9 的丹药
        insertDiscipleForBreakthrough(id = 1, realm = 9, realmLayer = 8)

        val pill = Pill(
            id = "pill_1",
            name = "突破丹",
            pillType = "breakthrough",
            rarity = 3,
            quantity = 1,
            effects = PillEffect(
                targetRealm = 9,
                breakthroughChance = 0.5
            )
        )
        state.pills = EntityStore(listOf(pill))
        state.gameData = GameData(breakthroughAutoPillFocused = true)

        val original = tables.assemble(1)
        val discipleWithFollowed = original.copy(
            statusData = mapOf("followed" to "true")
        )

        val result = handler.performBreakthrough(discipleWithFollowed, state, state.gameData)

        assertTrue("warehouse pill should be consumed",
            state.pills.all().isEmpty())
        assertEquals("cultivation should be reset", 0.0, result.cultivation, 0.0)
    }

    // ═══════════════════════════════════════════════════════════════
    // performBreakthrough — auto pill from storage bag
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `performBreakthrough - auto uses breakthrough pill from storage bag`() {
        // realmLayer=8 < maxLayers=9 → pillTargetRealm=realm=9, 匹配 targetRealm=9 的丹药
        insertDiscipleForBreakthrough(id = 1, realm = 9, realmLayer = 8)

        val bagPill = StorageBagItem(
            itemId = "bag_pill_1",
            itemType = ITEM_TYPE_PILL,
            name = "储物袋突破丹",
            rarity = 3,
            quantity = 1,
            effect = ItemEffect(
                pillType = "breakthrough",
                breakthroughChance = 0.3,
                targetRealm = 9
            )
        )
        tables.storageBagItems[1] = listOf(bagPill)
        state.gameData = GameData(breakthroughAutoPillFocused = true)

        val original = tables.assemble(1)
        val discipleWithFollowed = original.copy(
            statusData = mapOf("followed" to "true")
        )

        val result = handler.performBreakthrough(discipleWithFollowed, state, state.gameData)

        // performBreakthrough 返回修改后的 Disciple（不写回 table），
        // 所以检查返回值中的 equipment.storageBagItems
        assertTrue("storage bag pill should be consumed from result equipment",
            result.equipment.storageBagItems.isEmpty())
        assertEquals("cultivation should be reset", 0.0, result.cultivation, 0.0)
    }

    // ═══════════════════════════════════════════════════════════════
    // performBreakthrough — counts are written
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `performBreakthrough - breakthrough counts are written`() {
        insertDiscipleForBreakthrough(id = 1, realm = 9, realmLayer = 9)

        val original = tables.assemble(1)
        val gameData = GameData()
        handler.performBreakthrough(original, state, gameData)

        val successCount = tables.breakthroughCounts[1] ?: 0
        val failCount = tables.breakthroughFailCounts[1] ?: 0
        assertTrue(
            "breakthrough or fail count should be > 0, got success=$successCount fail=$failCount",
            successCount > 0 || failCount > 0
        )
    }

    // ═══════════════════════════════════════════════════════════════
    // performBreakthrough — ad bonus cleared
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `performBreakthrough - adBreakthroughBonus is cleared`() {
        insertDiscipleForBreakthrough(
            id = 1, realm = 9, realmLayer = 9,
            statusData = mapOf("adBreakthroughBonus" to "0.5", "someOther" to "value")
        )

        val original = tables.assemble(1)
        val gameData = GameData()
        val result = handler.performBreakthrough(original, state, gameData)

        assertFalse(
            "adBreakthroughBonus should be removed from statusData",
            result.statusData.containsKey("adBreakthroughBonus")
        )
        assertEquals("other fields in statusData should be preserved",
            "value", result.statusData["someOther"])
    }

    // ═══════════════════════════════════════════════════════════════
    // performBreakthrough — 玉符加成失败路径清除（2026-08-11 玉符化：
    // 加成只对下一次突破尝试有效，失败也清除；原逻辑失败保留）
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `performBreakthrough - adBreakthroughBonus is cleared on failure`() {
        // 必败构造：境界7层5 + 五灵根 → BREAKTHROUGH_CHANCES[7][5]=0.00 → chance=0 必然失败
        // （2026-08-12 悟性重设计：自身悟性进入 selfBonus 乘区，但基础 0 × 加成仍为 0；
        // 长老未配置故无 elderBonus）
        insertDiscipleForBreakthrough(
            id = 1, realm = 7, realmLayer = 5,
            spiritRootType = "metal,wood,water,fire,earth",
            currentHp = 1000, currentMp = 500,
            statusData = mapOf("adBreakthroughBonus" to "0.30", "someOther" to "value")
        )

        val original = tables.assemble(1)
        val gameData = GameData()
        val result = handler.performBreakthrough(original, state, gameData)

        val failCount = tables.breakthroughFailCounts[1] ?: 0
        assertTrue("必败构造下 failCount 应 > 0，实际 $failCount", failCount > 0)
        assertFalse(
            "突破失败后玉符加成也应清除（只对下一次突破尝试有效）",
            result.statusData.containsKey("adBreakthroughBonus")
        )
        assertEquals("other fields in statusData should be preserved",
            "value", result.statusData["someOther"])
    }

    // ═══════════════════════════════════════════════════════════════
    // performBreakthrough — realm 0 skips breakthrough
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `performBreakthrough - realm 0 disciples skip breakthrough`() {
        insertDiscipleForBreakthrough(id = 1, realm = 0, realmLayer = 1)

        val original = tables.assemble(1)
        val gameData = GameData()
        val result = handler.performBreakthrough(original, state, gameData)

        assertTrue("realm 0 disciple's cultivation should be >= 0",
            result.cultivation >= 0)
    }

    // ═══════════════════════════════════════════════════════════════
    // performBreakthrough — pill depletion (auto pill focused but no pills)
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `performBreakthrough - auto pill focused but no pills available still proceeds`() {
        insertDiscipleForBreakthrough(id = 1, realm = 9, realmLayer = 9)
        // 设置自动突破丹药但仓库和储物袋都没有丹药
        state.gameData = GameData(breakthroughAutoPillFocused = true)

        val original = tables.assemble(1)
        val gameData = GameData(breakthroughAutoPillFocused = true)
        val result = handler.performBreakthrough(original, state, gameData)

        // 仍然应该突破（无丹药加成），cultivation 被重置
        assertEquals("cultivation should be reset after breakthrough attempt",
            0.0, result.cultivation, 0.0)
        val realmChanged = result.realm < original.realm || result.realmLayer != original.realmLayer
        val hpMpReduced = result.combat.currentHp <= (original.maxHp * 0.1).toInt().coerceAtLeast(1)
        assertTrue(
            "breakthrough should either change realm/layer (success) or reduce HP/MP (failure) even without pills",
            realmChanged || hpMpReduced
        )
    }

    // ═══════════════════════════════════════════════════════════════
    // tryBreakthrough — elder guidance
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `tryBreakthrough - inner elder comprehension works without error`() {
        insertDiscipleForBreakthrough(id = 1, realm = 9, realmLayer = 9)
        insertDiscipleForBreakthrough(
            id = 2, realm = 8, realmLayer = 1, comprehension = 100,
            cultivation = 0.0
        )
        tables.discipleTypes[2] = TYPE_INNER
        state.gameData = GameData(
            elderSlots = ElderSlots(innerElder = "2", outerElder = "")
        )

        val original = tables.assemble(1)
        val success = handler.tryBreakthrough(original, state = state)

        assertNotNull("tryBreakthrough should return a result", success)
    }
}

