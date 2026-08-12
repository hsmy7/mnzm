package com.xianxia.sect.core.engine.service

import com.xianxia.sect.core.engine.service.RelativeGiftHandler.GiftResult
import com.xianxia.sect.core.model.Disciple
import com.xianxia.sect.core.model.EquipmentSlot
import com.xianxia.sect.core.model.EquipmentStack
import com.xianxia.sect.core.model.GameData
import com.xianxia.sect.core.model.ItemEffect
import com.xianxia.sect.core.model.ManualInstance
import com.xianxia.sect.core.model.ManualStack
import com.xianxia.sect.core.model.StorageBagItem
import com.xianxia.sect.core.model.parentId1
import com.xianxia.sect.core.model.parentId2
import com.xianxia.sect.core.model.storageBagItems
import com.xianxia.sect.core.model.GiftRelationshipType
import com.xianxia.sect.core.state.DiscipleTables
import com.xianxia.sect.core.state.EntityStore
import com.xianxia.sect.core.state.MutableGameState
import com.xianxia.sect.core.state.WriteGuardRule
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.mock
import org.junit.runner.RunWith
import org.junit.Rule
import org.robolectric.RobolectricTestRunner



/**
 * RelativeGiftHandler 单元测试。
 *
 * 覆盖：亲属查找、关系分类、物品选择优先级、装备/功法槽位检测、
 * 物品转移安全约束、完整赠送流程。
 *
 * 使用 Robolectric 获得真实的 SparseArray 实现
 * （DiscipleTables 底层依赖 android.util.SparseArray）。
 */
@RunWith(RobolectricTestRunner::class)
class RelativeGiftHandlerTest {

    @get:Rule val writeGuardRule = WriteGuardRule()
    private lateinit var handler: RelativeGiftHandler
    private lateinit var tables: DiscipleTables
    private lateinit var state: MutableGameState

    @Before
    fun setUp() {
        handler = RelativeGiftHandler(mock())
        tables = DiscipleTables()
        state = MutableGameState(
            gameData = GameData(id = "test", gameYear = 1, gameMonth = 1),
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
    }

    // ==================== 辅助方法 ====================

    private fun insertDisciple(
        id: Int,
        name: String = "弟子$id",
        isAlive: Boolean = true,
        realm: Int = 9,
        realmLayer: Int = 1
    ) {
        val d = Disciple(
            id = id.toString(),
            name = name,
            realm = realm,
            realmLayer = realmLayer,
            isAlive = isAlive
        )
        tables.insert(d)
    }

    private fun setPartner(a: Int, b: Int) {
        tables.partnerIds[a] = b.toString()
        tables.partnerIds[b] = a.toString()
    }

    private fun setMaster(apprenticeId: Int, masterId: Int) {
        tables.masterIds[apprenticeId] = masterId.toString()
    }

    private fun setParent(childId: Int, parentId1: Int, parentId2: Int? = null) {
        tables.parentId1s[childId] = parentId1.toString()
        parentId2?.let { tables.parentId2s[childId] = it.toString() }
    }

    private fun addToBag(
        discipleId: Int,
        itemType: String,
        itemId: String = "${itemType}_$discipleId",
        rarity: Int = 3,
        effect: ItemEffect? = null,
        name: String = "测试物品"
    ) {
        val current = tables.storageBagItems.getOrNull(discipleId) ?: emptyList()
        tables.storageBagItems[discipleId] = current + StorageBagItem(
            itemId = itemId,
            itemType = itemType,
            name = name,
            rarity = rarity,
            quantity = 1,
            effect = effect
        )
    }

    private fun addEquipmentStack(
        id: String, slot: EquipmentSlot, rarity: Int = 3, minRealm: Int = 9
    ) {
        state.equipmentStacks = EntityStore(
            state.equipmentStacks.all() + EquipmentStack(
                id = id, slot = slot, rarity = rarity,
                name = "测试装备$id", minRealm = minRealm
            )
        )
    }

    private fun addManualStack(
        id: String, rarity: Int = 3, minRealm: Int = 9,
        name: String = "测试功法$id"
    ) {
        state.manualStacks = EntityStore(
            state.manualStacks.all() + ManualStack(
                id = id, rarity = rarity, name = name, minRealm = minRealm
            )
        )
    }

    private fun addManualInstance(id: String, name: String = "测试功法") {
        state.manualInstances = EntityStore(
            state.manualInstances.all() + ManualInstance(
                id = id, name = name, ownerId = "0"
            )
        )
    }

    // ==================== 亲属查找测试 ====================

    @Test
    fun `findRelatives - 道侣关系正确识别`() {
        insertDisciple(1); insertDisciple(2)
        setPartner(1, 2)
        val relatives = handler.findRelatives(1, tables)
        assertEquals(listOf(2), relatives)
    }

    @Test
    fun `findRelatives - 道侣关系双向识别`() {
        insertDisciple(1); insertDisciple(2)
        setPartner(1, 2)
        val relativesOf2 = handler.findRelatives(2, tables)
        assertEquals(listOf(1), relativesOf2)
    }

    @Test
    fun `findRelatives - 师父关系正确识别`() {
        insertDisciple(1); insertDisciple(2)
        setMaster(1, 2)
        val relatives = handler.findRelatives(1, tables)
        assertTrue(relatives.contains(2))
    }

    @Test
    fun `findRelatives - 徒弟关系正确识别`() {
        insertDisciple(1); insertDisciple(2)
        setMaster(2, 1)
        val relatives = handler.findRelatives(1, tables)
        assertTrue(relatives.contains(2))
    }

    @Test
    fun `findRelatives - 父母关系正确识别`() {
        insertDisciple(1); insertDisciple(2)
        setParent(1, 2)
        val relatives = handler.findRelatives(1, tables)
        assertTrue(relatives.contains(2))
    }

    @Test
    fun `findRelatives - 子嗣关系正确识别`() {
        insertDisciple(1); insertDisciple(2)
        setParent(2, 1)
        val relatives = handler.findRelatives(1, tables)
        assertTrue(relatives.contains(2))
    }

    @Test
    fun `findRelatives - 兄弟姐妹关系正确识别`() {
        insertDisciple(1); insertDisciple(2); insertDisciple(3)
        setParent(1, 3); setParent(2, 3)
        val relatives = handler.findRelatives(1, tables)
        assertTrue(relatives.contains(2))
    }

    @Test
    fun `findRelatives - 排除自身`() {
        insertDisciple(1)
        val relatives = handler.findRelatives(1, tables)
        assertFalse(relatives.contains(1))
    }

    @Test
    fun `findRelatives - 排除已故者`() {
        insertDisciple(1); insertDisciple(2, isAlive = false)
        setPartner(1, 2)
        val relatives = handler.findRelatives(1, tables)
        assertFalse(relatives.contains(2))
    }

    @Test
    fun `findRelatives - 无亲属返回空列表`() {
        insertDisciple(1); insertDisciple(2)
        val relatives = handler.findRelatives(1, tables)
        assertTrue(relatives.isEmpty())
    }

    // ==================== 关系分类测试 ====================

    @Test
    fun `classifyRelationship - 道侣优先级最高`() {
        insertDisciple(1); insertDisciple(2)
        setPartner(1, 2)
        setParent(1, 3); setParent(2, 3)
        val type = handler.classifyRelationship(1, 2, tables)
        assertEquals(GiftRelationshipType.PARTNER, type)
    }

    @Test
    fun `classifyRelationship - 师徒关系中师父方向`() {
        insertDisciple(1); insertDisciple(2)
        setMaster(1, 2)
        assertEquals(GiftRelationshipType.APPRENTICE,
            handler.classifyRelationship(1, 2, tables))
        assertEquals(GiftRelationshipType.MASTER,
            handler.classifyRelationship(2, 1, tables))
    }

    @Test
    fun `classifyRelationship - 父母子嗣方向`() {
        insertDisciple(1); insertDisciple(2)
        setParent(2, 1)
        assertEquals(GiftRelationshipType.PARENT,
            handler.classifyRelationship(1, 2, tables))
        assertEquals(GiftRelationshipType.CHILD,
            handler.classifyRelationship(2, 1, tables))
    }

    // ==================== 装备槽位检测 ====================

    @Test
    fun `getEmptyEquipmentSlots - 所有槽位空闲`() {
        insertDisciple(1)
        val empty = handler.getEmptyEquipmentSlots(1, tables)
        assertEquals(4, empty.size)
    }

    @Test
    fun `getEmptyEquipmentSlots - 部分槽位已占用`() {
        insertDisciple(1)
        tables.weaponIds[1] = "sword_1"
        tables.armorIds[1] = "armor_1"
        val empty = handler.getEmptyEquipmentSlots(1, tables)
        assertEquals(2, empty.size)
    }

    @Test
    fun `getEmptyEquipmentSlots - 所有槽位已满`() {
        insertDisciple(1)
        tables.weaponIds[1] = "sword_1"
        tables.armorIds[1] = "armor_1"
        tables.bootsIds[1] = "boots_1"
        tables.accessoryIds[1] = "acc_1"
        val empty = handler.getEmptyEquipmentSlots(1, tables)
        assertTrue(empty.isEmpty())
    }

    // ==================== 功法槽位检测 ====================

    @Test
    fun `isManualSlotAvailable - 空槽位返回true`() {
        insertDisciple(1)
        assertTrue(handler.isManualSlotAvailable(1, tables))
    }

    @Test
    fun `isManualSlotAvailable - 槽位未满返回true`() {
        insertDisciple(1)
        tables.manualIds[1] = listOf("m1", "m2", "m3", "m4", "m5")
        assertTrue(handler.isManualSlotAvailable(1, tables))
    }

    @Test
    fun `isManualSlotAvailable - 槽位已满返回false`() {
        insertDisciple(1)
        tables.manualIds[1] = listOf("m1", "m2", "m3", "m4", "m5", "m6")
        assertFalse(handler.isManualSlotAvailable(1, tables))
    }

    // ==================== 物品选择优先级测试 ====================

    @Test
    fun `selectBestGift - 空背包返回null`() {
        insertDisciple(1); insertDisciple(2)
        val result = handler.selectBestGift(emptyList(), 1, 9, tables, state)
        assertNull(result)
    }

    @Test
    fun `selectBestGift - 装备优先于丹药`() {
        insertDisciple(1); insertDisciple(2)
        addEquipmentStack("eq_sword", EquipmentSlot.WEAPON, rarity = 5)
        val bagItems = listOf(
            StorageBagItem("eq_sword", "equipment_stack", "灵剑", 5),
            StorageBagItem("pill_1", "pill", "丹药", 8)
        )
        val result = handler.selectBestGift(bagItems, 1, 9, tables, state)
        assertNotNull(result)
        assertEquals("eq_sword", result?.itemId)
    }

    @Test
    fun `selectBestGift - 装备不匹配槽位时退选丹药`() {
        insertDisciple(1)
        tables.weaponIds[1] = "sword_1"
        tables.armorIds[1] = "armor_1"
        tables.bootsIds[1] = "boots_1"
        addEquipmentStack("eq_sword2", EquipmentSlot.WEAPON, rarity = 5)
        val bagItems = listOf(
            StorageBagItem("eq_sword2", "equipment_stack", "灵剑", 5),
            StorageBagItem("pill_1", "pill", "丹药", 3)
        )
        val result = handler.selectBestGift(bagItems, 1, 9, tables, state)
        assertNotNull(result)
        assertEquals("pill_1", result?.itemId)
    }

    @Test
    fun `selectBestGift - 功法优先于丹药`() {
        insertDisciple(1)
        tables.weaponIds[1] = "sword_1"
        tables.armorIds[1] = "armor_1"
        tables.bootsIds[1] = "boots_1"
        tables.accessoryIds[1] = "acc_1"
        addManualStack("manual_1", rarity = 4)
        val bagItems = listOf(
            StorageBagItem("manual_1", "manual_stack", "天阶心法", 4),
            StorageBagItem("pill_1", "pill", "丹药", 8)
        )
        val result = handler.selectBestGift(bagItems, 1, 9, tables, state)
        assertNotNull(result)
        assertEquals("manual_1", result?.itemId)
    }

    @Test
    fun `selectBestGift - 突破丹药优先于普通丹药`() {
        insertDisciple(1)
        tables.weaponIds[1] = "sword_1"
        tables.armorIds[1] = "armor_1"
        tables.bootsIds[1] = "boots_1"
        tables.accessoryIds[1] = "acc_1"
        tables.manualIds[1] = listOf("m1", "m2", "m3", "m4", "m5", "m6")
        val bagItems = listOf(
            StorageBagItem("pill_bp", "pill", "筑基丹", 5,
                effect = ItemEffect(pillType = "breakthrough", targetRealm = 9,
                    breakthroughChance = 0.3)),
            StorageBagItem("pill_normal", "pill", "回灵丹", 8)
        )
        val result = handler.selectBestGift(bagItems, 1, 9, tables, state)
        assertNotNull(result)
        assertEquals("pill_bp", result?.itemId)
    }

    @Test
    fun `selectBestGift - 材料兜底选择最高稀有度`() {
        insertDisciple(1)
        tables.weaponIds[1] = "sword_1"
        tables.armorIds[1] = "armor_1"
        tables.bootsIds[1] = "boots_1"
        tables.accessoryIds[1] = "acc_1"
        tables.manualIds[1] = listOf("m1", "m2", "m3", "m4", "m5", "m6")
        val bagItems = listOf(
            StorageBagItem("mat_1", "material", "玄铁", 3),
            StorageBagItem("herb_1", "herb", "灵草", 5)
        )
        val result = handler.selectBestGift(bagItems, 1, 9, tables, state)
        assertNotNull(result)
        assertEquals("herb_1", result?.itemId)
    }

    @Test
    fun `selectBestGift - 已学会的功法不选`() {
        insertDisciple(1)
        tables.weaponIds[1] = "sword_1"
        tables.armorIds[1] = "armor_1"
        tables.bootsIds[1] = "boots_1"
        tables.accessoryIds[1] = "acc_1"
        tables.manualIds[1] = listOf("mi_1")
        addManualInstance("mi_1", name = "天阶心法")
        addManualStack("manual_1", rarity = 5, name = "天阶心法")
        val bagItems = listOf(
            StorageBagItem("manual_1", "manual_stack", "天阶心法", 5)
        )
        val result = handler.selectBestGift(bagItems, 1, 9, tables, state)
        assertNull(result)
    }

    // ==================== 物品转移测试 ====================

    @Test
    fun `tryGiveGift - 正常转移`() {
        insertDisciple(1); insertDisciple(2)
        // 至少2件物品才能赠送（保留1件规则）
        addToBag(1, "pill", "pill_1", rarity = 3)
        addToBag(1, "pill", "pill_2", rarity = 5)
        tables.storageBagItems[2] = emptyList()

        val result = handler.tryGiveGift(1, 2, 9, tables, state)
        assertTrue(result is GiftResult.Success)

        // giver 剩余1件
        val giverBag = tables.storageBagItems.getOrNull(1) ?: emptyList()
        assertEquals(1, giverBag.size)

        // receiver 获得1件
        val receiverBag = tables.storageBagItems.getOrNull(2) ?: emptyList()
        assertEquals(1, receiverBag.size)
    }

    @Test
    fun `tryGiveGift - 背包仅剩1件时放弃赠送`() {
        insertDisciple(1); insertDisciple(2)
        addToBag(1, "pill", "pill_1")
        val result = handler.tryGiveGift(1, 2, 9, tables, state)
        assertTrue(result is GiftResult.BagTooSmall)
        val giverBag = tables.storageBagItems.getOrNull(1) ?: emptyList()
        assertEquals(1, giverBag.size)
    }

    @Test
    fun `tryGiveGift - 背包空时放弃`() {
        insertDisciple(1); insertDisciple(2)
        tables.storageBagItems[1] = emptyList()
        val result = handler.tryGiveGift(1, 2, 9, tables, state)
        assertTrue(result is GiftResult.BagEmpty)
    }

    @Test
    fun `tryGiveGift - 物品合并到已有同类物品`() {
        insertDisciple(1); insertDisciple(2)
        addToBag(1, "pill", "pill_1", rarity = 3)
        addToBag(1, "herb", "herb_1", rarity = 1)
        tables.storageBagItems[2] = listOf(
            StorageBagItem("pill_1", "pill", "丹药", 3, quantity = 2)
        )
        val result = handler.tryGiveGift(1, 2, 9, tables, state)
        assertTrue(result is GiftResult.Success)
        val receiverBag = tables.storageBagItems.getOrNull(2) ?: emptyList()
        val pill = receiverBag.find { it.itemId == "pill_1" }
        assertNotNull(pill)
        assertEquals(3, pill?.quantity)
    }

    // ==================== 完整流程测试 ====================

    @Test
    fun `processGiftsForBreakthrough - 无亲属无操作`() {
        insertDisciple(1)
        // 不应抛异常
        handler.processGiftsForBreakthrough(1, tables, state)
    }
}
