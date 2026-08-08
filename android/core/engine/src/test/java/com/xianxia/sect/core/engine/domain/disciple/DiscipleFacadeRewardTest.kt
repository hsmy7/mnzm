package com.xianxia.sect.core.engine.domain.disciple

import com.xianxia.sect.core.model.Disciple
import com.xianxia.sect.core.model.EquipmentInstance
import com.xianxia.sect.core.model.EquipmentSlot
import com.xianxia.sect.core.model.EquipmentStack
import com.xianxia.sect.core.model.GameData
import com.xianxia.sect.core.model.Pill
import com.xianxia.sect.core.model.RewardSelectedItem
import com.xianxia.sect.core.state.DiscipleTables
import com.xianxia.sect.core.state.EntityStore
import com.xianxia.sect.core.state.GameStateStore
import com.xianxia.sect.core.state.MutableGameState
import com.xianxia.sect.core.state.WriteGuardRule
import com.xianxia.sect.core.engine.service.HighFrequencyData
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito
import org.mockito.Mockito.mock
import org.mockito.kotlin.any
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner

/**
 * D-03 赏赐路径测试（DiscipleFacadeImpl.rewardItemsToDisciple）。
 *
 * 独立存储语义守卫：
 * - 不可装装备赏赐：仓库扣 1 + 袋铸造 stackedData 条目（minRealm/slot 保真）
 * - 可装装备赏赐：装上身 + 旧装备实例直接铸造入袋 + 实例表删除（防双持有）
 * - 仓库数量不足：不铸造袋条目（唯一失败条件，袋容量无上限永不因袋满失败）
 * - 丹药赏赐：扣数量 + 袋条目
 */
@RunWith(RobolectricTestRunner::class)
class DiscipleFacadeRewardTest {

    @get:Rule val writeGuardRule = WriteGuardRule()
    private lateinit var tables: DiscipleTables
    private lateinit var mutableState: MutableGameState
    private lateinit var mockStore: GameStateStore
    private lateinit var pillManager: com.xianxia.sect.core.engine.domain.disciple.DisciplePillManager
    private lateinit var facade: DiscipleFacadeImpl

    @Before
    fun setUp() {
        tables = DiscipleTables()
        mutableState = MutableGameState(
            gameData = GameData(gameYear = 5, gameMonth = 3),
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
        mockStore = createDelegateMockStore()

        val cultivationService = mock(com.xianxia.sect.core.engine.service.CultivationService::class.java)
        Mockito.`when`(cultivationService.getHighFrequencyData())
            .thenReturn(MutableStateFlow(HighFrequencyData()))

        pillManager = mock()
        // mockito-kotlin 的 any() 是空安全兼容版：Mockito.any() 返回 null 会触发 Kotlin
        // 非空参数检查 NPE（"any(...) must not be null"）
        // canUse=false → 赏赐丹药走"入袋"分支（本测试守卫的语义）；canUse=true 会直接入体不进袋
        whenever(pillManager.canUsePill(any(), any()))
            .thenReturn(com.xianxia.sect.core.engine.domain.disciple.DisciplePillManager.PillUseCheck(canUse = false))

        facade = DiscipleFacadeImpl(
            discipleService = mock(),
            stateStore = mockStore,
            cultivationService = cultivationService,
            gameEngineCore = mock(),
            inventorySystem = mock(),
            pillManager = pillManager,
            assignmentGate = mock(),
            discipleSlotCleanup = mock(),
            lawEnforcementProcessor = mock(),
            productionCoordinator = mock<com.xianxia.sect.core.engine.domain.production.ProductionCoordinator>(),
        )
    }

    private fun createDelegateMockStore(): GameStateStore {
        val delegate = mock(GameStateStore::class.java)
        Mockito.`when`(delegate.discipleTables).thenReturn(tables)
        Mockito.`when`(delegate.gameData).thenReturn(MutableStateFlow(GameData()))
        return object : GameStateStore by delegate {
            override val discipleTables: DiscipleTables get() = tables
            override fun update(block: MutableGameState.() -> Unit) { block.invoke(mutableState) }
            override fun <R> updateAndReturn(block: MutableGameState.() -> R): R = block.invoke(mutableState)
        }
    }

    private fun insertDisciple(id: Int, realm: Int) {
        tables.insert(Disciple(id = id.toString(), name = "弟子$id", realm = realm, realmLayer = 1))
        tables.isAlive[id] = 1
        tables.realms[id] = realm
    }

    private fun eqStack(id: String, qty: Int, minRealm: Int = 0) = EquipmentStack(
        id = id, name = "精铁剑", rarity = 1, slot = EquipmentSlot.WEAPON, quantity = qty, minRealm = minRealm
    )

    // ═══════════════════════════════════════════════════════════════
    // 不可装装备：仓库扣 1 + 袋铸造（独立存储）
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `equipment reward below realm requirement casts bag entry and deducts warehouse`() {
        insertDisciple(1, realm = 9) // 练气弟子（数值越大境界越低），不满足 minRealm=7 的装备
        mutableState.equipmentStacks = EntityStore(listOf(eqStack("eq1", qty = 3, minRealm = 7)))

        facade.rewardItemsToDisciple("1", listOf(
            RewardSelectedItem(id = "eq1", type = "equipment", name = "精铁剑", rarity = 1, quantity = 1)
        ))

        val bagItems = tables.storageBagItems[1]
        assertEquals("袋铸造 1 条", 1, bagItems.size)
        val bagItem = bagItems.first()
        assertEquals("itemId 引用仓库堆叠 id", "eq1", bagItem.itemId)
        assertTrue("payload 已铸造", bagItem.isMaterialized)
        assertEquals("minRealm 保真", 7, bagItem.stackedData?.minRealm)
        assertEquals("slot 保真", EquipmentSlot.WEAPON.name, bagItem.stackedData?.slot)
        assertEquals("仓库扣减 3→2", 2, mutableState.equipmentStacks.get("eq1")?.quantity)
        assertEquals("未装备", "", tables.weaponIds[1])
    }

    @Test
    fun `equipment reward with insufficient warehouse quantity does nothing`() {
        insertDisciple(1, realm = 9)
        mutableState.equipmentStacks = EntityStore(listOf(eqStack("eq1", qty = 0, minRealm = 7)))

        facade.rewardItemsToDisciple("1", listOf(
            RewardSelectedItem(id = "eq1", type = "equipment", name = "精铁剑", rarity = 1, quantity = 1)
        ))

        assertEquals("袋无条目（仓库数量不足）", 0, tables.storageBagItems[1].size)
    }

    // ═══════════════════════════════════════════════════════════════
    // 可装装备：装上身 + 旧装备实例入袋
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `equipment reward meeting realm equips and old instance casts into bag`() {
        insertDisciple(1, realm = 5) // 数值越小境界越高：5 <= minRealm=7 满足（境界足够装）
        // 弟子已穿旧武器实例 i-old
        val oldInstance = EquipmentInstance(id = "i-old", name = "旧剑", rarity = 1, slot = EquipmentSlot.WEAPON)
        mutableState.equipmentInstances = EntityStore(listOf(oldInstance))
        tables.weaponIds[1] = "i-old"
        mutableState.equipmentStacks = EntityStore(listOf(eqStack("eq-new", qty = 2, minRealm = 7)))

        facade.rewardItemsToDisciple("1", listOf(
            RewardSelectedItem(id = "eq-new", type = "equipment", name = "精铁剑", rarity = 1, quantity = 1)
        ))

        // 新装备上身
        assertNull("旧实例已从实例表移除（防双持有）", mutableState.equipmentInstances.get("i-old"))
        assertEquals("实例表仅剩新实例 1 条", 1, mutableState.equipmentInstances.all().size)
        val newEquipId = tables.weaponIds[1]
        assertTrue("新实例已装备", newEquipId.isNotEmpty() && newEquipId != "i-old")
        assertEquals("仓库扣减 2→1", 1, mutableState.equipmentStacks.get("eq-new")?.quantity)
        // 旧实例入袋（容量无上限，永不失败）
        val bagItems = tables.storageBagItems[1]
        assertEquals("旧装备实例入袋", 1, bagItems.size)
        assertEquals("i-old", bagItems.first().itemId)
        assertEquals("完整实例保真", oldInstance, bagItems.first().equipmentInstance)
    }

    // ═══════════════════════════════════════════════════════════════
    // 丹药赏赐：扣数量 + 袋条目
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `pill reward deducts warehouse and casts bag entry`() {
        insertDisciple(1, realm = 9)
        mutableState.pills = EntityStore(listOf(Pill(id = "p1", name = "聚气丹", rarity = 1, quantity = 5)))

        facade.rewardItemsToDisciple("1", listOf(
            RewardSelectedItem(id = "p1", type = "pill", name = "聚气丹", rarity = 1, quantity = 2)
        ))

        assertEquals("仓库扣减 5→3", 3, mutableState.pills.get("p1")?.quantity)
        val bagItems = tables.storageBagItems[1]
        assertEquals("袋铸造 1 条", 1, bagItems.size)
        assertEquals("数量保真", 2, bagItems.first().quantity)
        assertTrue("payload 已铸造", bagItems.first().isMaterialized)
    }

    @Test
    fun `reward to nonexistent disciple ignored`() {
        mutableState.equipmentStacks = EntityStore(listOf(eqStack("eq1", qty = 2)))

        facade.rewardItemsToDisciple("999", listOf(
            RewardSelectedItem(id = "eq1", type = "equipment", name = "精铁剑", rarity = 1, quantity = 1)
        ))

        assertEquals("仓库未扣减", 2, mutableState.equipmentStacks.get("eq1")?.quantity)
    }

    @Test
    fun `material herb seed reward to nonexistent disciple does not deduct warehouse`() {
        // 对抗性审查-边界 5（预存）：原实现先扣仓库后校验弟子 id——无效 id 物品消失
        mutableState.materials = EntityStore(listOf(
            com.xianxia.sect.core.model.Material(id = "m1", name = "妖兽皮", rarity = 1, quantity = 3)
        ))
        mutableState.herbs = EntityStore(listOf(
            com.xianxia.sect.core.model.Herb(id = "h1", name = "灵草", rarity = 1, quantity = 3)
        ))
        mutableState.seeds = EntityStore(listOf(
            com.xianxia.sect.core.model.Seed(id = "s1", name = "灵稻种", rarity = 1, quantity = 3)
        ))

        facade.rewardItemsToDisciple("999", listOf(
            RewardSelectedItem(id = "m1", type = "material", name = "妖兽皮", rarity = 1, quantity = 1),
            RewardSelectedItem(id = "h1", type = "herb", name = "灵草", rarity = 1, quantity = 1),
            RewardSelectedItem(id = "s1", type = "seed", name = "灵稻种", rarity = 1, quantity = 1)
        ))

        assertEquals("材料未扣减", 3, mutableState.materials.get("m1")?.quantity)
        assertEquals("草药未扣减", 3, mutableState.herbs.get("h1")?.quantity)
        assertEquals("种子未扣减", 3, mutableState.seeds.get("s1")?.quantity)
    }

    // ═══════════════════════════════════════════════════════════════
    // 袋容量无上限守卫：多次赏赐永不因袋满失败
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `reward many items never fails on bag capacity - unlimited bag`() {
        insertDisciple(1, realm = 9)
        // 模拟高境界装备池：大量不可装装备连续赏赐（旧设计 BAG_CAPACITY=30 会因袋满失败）
        val stacks = (0 until 50).map { i ->
            EquipmentStack(
                id = "eq$i", name = "高级武器$i", rarity = 1,
                slot = EquipmentSlot.WEAPON, quantity = 1, minRealm = 7
            )
        }
        mutableState.equipmentStacks = EntityStore(stacks)

        facade.rewardItemsToDisciple("1", (0 until 50).map { i ->
            RewardSelectedItem(id = "eq$i", type = "equipment", name = "高级武器$i", rarity = 1, quantity = 1)
        })

        assertEquals("50 件全部入袋（无袋满概念）", 50, tables.storageBagItems[1].size)
        assertEquals("仓库清空", 0, mutableState.equipmentStacks.all().size)
    }
}
