package com.xianxia.sect.core.engine.domain.disciple

import com.xianxia.sect.core.model.Disciple
import com.xianxia.sect.core.model.EquipmentSet
import com.xianxia.sect.core.model.ManualInstance
import com.xianxia.sect.core.model.ManualType
import com.xianxia.sect.core.model.StorageBagItem
import com.xianxia.sect.core.registry.ManualDatabase
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 自动学习功法候选统一（仓库 + 储物袋）与更高品阶替换。
 *
 * 覆盖：
 * - 袋内 manual_instance 直接学习（attachedInstance 重建入表、袋条目移除）
 * - 槽位满 + 更高品阶候选 → 替换最差功法（旧功法回袋、replacedInstance 供移除）
 * - 槽位未满 → 直接学习（含袋内堆叠模板重建）
 * - 同名功法不重复学
 */
class DiscipleManualManagerBagAutoLearnTest {

    private val manager = DiscipleManualManager()

    @Before
    fun setUp() {
        // 袋内堆叠模板重建依赖 ManualDatabase（BagItemReconstructor）
        ManualDatabase.initializeWithManuals(mapOf(
            "t1" to ManualDatabase.ManualTemplate(
                id = "t1", name = "青冥剑诀", type = ManualType.ATTACK, rarity = 1,
                skillDamageType = "physical", description = "测试功法"
            )
        ))
    }

    @After
    fun tearDown() {
        ManualDatabase.resetForTest()
    }

    private fun disciple(
        manualIds: List<String> = emptyList(),
        bag: List<StorageBagItem> = emptyList()
    ) = Disciple(
        id = "1",
        name = "测试弟子",
        realm = 9,
        manualIds = manualIds,
        equipment = EquipmentSet(storageBagItems = bag)
    )

    private fun learnedManual(id: String, name: String, rarity: Int): ManualInstance =
        ManualInstance(
            id = id, name = name, rarity = rarity, type = ManualType.ATTACK,
            skillDamageType = "physical", minRealm = 9,
            ownerId = "1", isLearned = true
        )

    private fun bagManualInstance(
        itemId: String, name: String, rarity: Int
    ): StorageBagItem {
        val instance = learnedManual(itemId, name, rarity).copy(isLearned = false)
        return StorageBagItem(
            itemId = itemId, itemType = ITEM_TYPE_MANUAL_INSTANCE,
            name = name, rarity = rarity, quantity = 1,
            manualInstance = instance
        )
    }

    // ── 袋内实例直接学习 ──────────────────────────────────────────

    @Test
    fun `袋内实例学习 - 空槽学习且袋条目移除`() {
        val d = disciple(bag = listOf(bagManualInstance("m1", "太乙剑诀", 4)))
        val result = manager.processAutoLearnFromWarehouse(
            disciple = d, warehouseStacks = emptyList(), manualInstances = emptyMap(),
            gameYear = 1, gameMonth = 1
        )

        assertEquals("应学习袋内功法", listOf("m1"), result.disciple.manualIds)
        assertTrue("袋条目应移除", result.disciple.equipment.storageBagItems.isEmpty())
        assertEquals("attachedInstance 应含实例", "m1", result.attachedInstance?.id)
        assertTrue(result.attachedInstance?.isLearned == true)
        assertEquals("1", result.attachedInstance?.ownerId)
    }

    // ── 槽位满 + 更高品阶替换 ─────────────────────────────────────

    @Test
    fun `槽位满替换 - 仓库高品阶替换最差已学功法且旧功法回袋`() {
        // 6 本 r1 功法（默认槽位 6）→ 仓库 r4 → 替换最差（首本 r1）
        val learned = (1..6).map { learnedManual("m$it", "基础功$it", 1) }
        val learnedById = learned.associateBy { it.id }
        val d = disciple(
            manualIds = learned.map { it.id },
            bag = listOf(bagManualInstance("b1", "太乙剑诀", 4))
        )
        // 袋内实例是"候选"，被替换的最差已学功法仍在实例表（供 replacedInstance 移除）
        val instances = learnedById

        val result = manager.processAutoLearnFromWarehouse(
            disciple = d, warehouseStacks = emptyList(), manualInstances = instances,
            gameYear = 3, gameMonth = 5
        )

        assertEquals("替换后仍 6 本", 6, result.disciple.manualIds.size)
        assertEquals("最差功法（首本 r1）应被替换",
            "m1", result.replacedInstance?.id)
        // 旧功法回袋（manual_instance 保真条目）
        val bagItems = result.disciple.equipment.storageBagItems
        assertEquals("旧功法应回袋", 1, bagItems.size)
        assertEquals("m1", bagItems[0].itemId)
        assertEquals(ITEM_TYPE_MANUAL_INSTANCE, bagItems[0].itemType)
        // 新功法已学习（attachedInstance = 袋内 b1 实例）
        assertEquals("b1", result.attachedInstance?.id)
        assertTrue(result.disciple.manualIds.contains("b1"))
        assertFalse(result.disciple.manualIds.contains("m1"))
    }

    @Test
    fun `槽位未满 - 直接学习不替换`() {
        val d = disciple(
            manualIds = listOf("m1"),
            bag = listOf(bagManualInstance("b1", "太乙剑诀", 4))
        )
        val instances = mapOf("m1" to learnedManual("m1", "基础功1", 1))

        val result = manager.processAutoLearnFromWarehouse(
            disciple = d, warehouseStacks = emptyList(), manualInstances = instances,
            gameYear = 1, gameMonth = 1
        )

        assertEquals("槽位未满应直接学习", listOf("m1", "b1"), result.disciple.manualIds)
        assertTrue("不应有替换", result.replacedInstance == null)
    }

    // ── 同名不重复 ────────────────────────────────────────────────

    @Test
    fun `同名功法不重复学习`() {
        val d = disciple(
            manualIds = listOf("m1"),
            bag = listOf(bagManualInstance("m1", "太乙剑诀", 4))  // 同名
        )
        val instances = mapOf("m1" to learnedManual("m1", "太乙剑诀", 4))

        val result = manager.processAutoLearnFromWarehouse(
            disciple = d, warehouseStacks = emptyList(), manualInstances = instances,
            gameYear = 1, gameMonth = 1
        )

        assertEquals("同名功法不应重复学习", listOf("m1"), result.disciple.manualIds)
        assertTrue(result.attachedInstance == null)
    }

    // ── 袋内堆叠模板重建 ──────────────────────────────────────────

    @Test
    fun `袋内堆叠 - 模板重建学习并扣减数量`() {
        val bagItem = StorageBagItem(
            itemId = "m1", itemType = ITEM_TYPE_MANUAL_STACK,
            name = "青冥剑诀", rarity = 1, quantity = 2,
            stackedData = com.xianxia.sect.core.model.BagStackedData(
                minRealm = 9, manualType = ManualType.ATTACK.name
            )
        )
        val d = disciple(bag = listOf(bagItem))

        val result = manager.processAutoLearnFromWarehouse(
            disciple = d, warehouseStacks = emptyList(), manualInstances = emptyMap(),
            gameYear = 1, gameMonth = 1
        )

        assertEquals("应学习模板重建功法", 1, result.disciple.manualIds.size)
        assertEquals("newInstance 应含模板重建实例", "青冥剑诀", result.newInstance?.name)
        assertEquals("袋内堆叠数量应 2→1", 1, result.disciple.equipment.storageBagItems.size)
    }
}
