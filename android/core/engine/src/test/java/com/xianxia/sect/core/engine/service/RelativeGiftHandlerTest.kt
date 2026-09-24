package com.xianxia.sect.core.engine.service

import com.xianxia.sect.core.engine.service.RelativeGiftHandler.GiftResult
import com.xianxia.sect.core.model.Disciple
import com.xianxia.sect.core.model.EquipmentSlot
import com.xianxia.sect.core.model.EquipmentStack
import com.xianxia.sect.core.model.GameData
import com.xianxia.sect.core.model.GiftRelationshipType
import com.xianxia.sect.core.model.ItemEffect
import com.xianxia.sect.core.model.ManualInstance
import com.xianxia.sect.core.model.ManualStack
import com.xianxia.sect.core.model.StorageBagItem
import com.xianxia.sect.core.model.storageBagItems
import com.xianxia.sect.core.state.DiscipleTables
import com.xianxia.sect.core.state.EntityStore
import com.xianxia.sect.core.state.MutableGameState
import com.xianxia.sect.core.state.WriteGuardRule
import com.xianxia.sect.core.util.DeterministicRng
import com.xianxia.sect.core.util.GameRngManager
import com.xianxia.sect.core.util.RngPartition
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.kotlin.whenever
import org.junit.runner.RunWith
import org.junit.Rule
import org.robolectric.RobolectricTestRunner

/**
 * RelativeGiftHandler 单元测试。
 *
 * 覆盖：师徒亲属查找、师徒关系分类与保底概率门、物品选择优先级、
 * 装备/功法槽位检测、物品转移安全约束、完整赠送流程。
 *
 * 关系面为**师徒两类**：师父 `0.40` / 徒弟 `0.30`（与 C++ `relative_gift.h`
 * `kMasterGiftProb` / `kApprenticeGiftProb` 同源）。
 *
 * 使用 Robolectric 获得真实的 SparseArray 实现
 * （DiscipleTables 底层依赖 android.util.SparseArray）。
 */
@org.junit.experimental.categories.Category(com.xianxia.sect.core.RobolectricTests::class)
@RunWith(RobolectricTestRunner::class)
class RelativeGiftHandlerTest {

    @get:Rule val writeGuardRule = WriteGuardRule()

    private lateinit var handler: RelativeGiftHandler
    private lateinit var tables: DiscipleTables
    private lateinit var state: MutableGameState

    @Before
    fun setUp() {
        handler = giftHandlerWith(FIXED_GIFT_PROB)
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

    /** 装配恒定给出 [probability] 的 SYSTEM 分区 RNG 的处理器。 */
    private fun giftHandlerWith(probability: Double): RelativeGiftHandler {
        val rngManager = mock(GameRngManager::class.java)
        whenever(rngManager.getRng(RngPartition.SYSTEM)).thenReturn(FixedRollRng(probability))
        return RelativeGiftHandler(rngManager)
    }

    /**
     * 恒定产出同一 [nextDouble] 值的 RNG。
     *
     * `DeterministicRng.nextDouble()` 的实现是 `(nextInt() and 0x7FFFFFFF) / 2^31`，
     * 故覆写 open 的 [nextInt] 即可精确控制抽取值，无需依赖种子序列。
     */
    private class FixedRollRng(private val probability: Double) : DeterministicRng(0L) {
        override fun nextInt(): Int = (probability * DOUBLE_SCALE).toLong().toInt()
    }

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

    private fun setMaster(apprenticeId: Int, masterId: Int) {
        tables.masterIds[apprenticeId] = masterId.toString()
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

    /** 让 receiver 储物袋可赠送（两件以上，满足保留 1 件约束）。 */
    private fun fillGiverBag(giverId: Int) {
        addToBag(giverId, "pill", "pill_1", rarity = 3)
        addToBag(giverId, "pill", "pill_2", rarity = 5)
    }

    // ==================== 亲属查找测试 ====================

    @Test
    fun `findRelatives - 师父关系正确识别`() {
        insertDisciple(1); insertDisciple(2)
        setMaster(1, 2)
        assertEquals(listOf(2), handler.findRelatives(1, tables))
    }

    @Test
    fun `findRelatives - 徒弟关系正确识别`() {
        insertDisciple(1); insertDisciple(2)
        setMaster(2, 1)
        assertEquals(listOf(2), handler.findRelatives(1, tables))
    }

    @Test
    fun `findRelatives - 一名师父的多名徒弟全部识别`() {
        insertDisciple(1); insertDisciple(2); insertDisciple(3)
        setMaster(2, 1); setMaster(3, 1)
        assertEquals(listOf(2, 3), handler.findRelatives(1, tables))
    }

    @Test
    fun `findRelatives - 排除自身`() {
        insertDisciple(1)
        assertFalse(handler.findRelatives(1, tables).contains(1))
    }

    @Test
    fun `findRelatives - 排除已故者`() {
        insertDisciple(1); insertDisciple(2, isAlive = false)
        setMaster(1, 2)
        assertFalse(handler.findRelatives(1, tables).contains(2))
    }

    @Test
    fun `findRelatives - 无师徒关系返回空列表`() {
        insertDisciple(1); insertDisciple(2)
        assertTrue(handler.findRelatives(1, tables).isEmpty())
    }

    // ==================== 关系分类测试 ====================

    @Test
    fun `classifyRelationship - 师徒两个方向各自归类`() {
        insertDisciple(1); insertDisciple(2)
        setMaster(1, 2)
        assertEquals(
            GiftRelationshipType.APPRENTICE,
            handler.classifyRelationship(1, 2, tables)
        )
        assertEquals(
            GiftRelationshipType.MASTER,
            handler.classifyRelationship(2, 1, tables)
        )
    }

    // ==================== 保底概率门测试 ====================

    @Test
    fun `processGiftsForBreakthrough - 师父按 040 概率门赠送`() {
        assertGateFiresAt(
            roll = MASTER_GIFT_PROB - PROB_EPSILON,
            giverIs = GiverIs.MASTER
        )
        assertGateFiresAt(
            roll = MASTER_GIFT_PROB + PROB_EPSILON,
            giverIs = GiverIs.MASTER
        )
    }

    @Test
    fun `processGiftsForBreakthrough - 徒弟按 030 概率门赠送`() {
        assertGateFiresAt(
            roll = APPRENTICE_GIFT_PROB - PROB_EPSILON,
            giverIs = GiverIs.APPRENTICE
        )
        assertGateFiresAt(
            roll = APPRENTICE_GIFT_PROB + PROB_EPSILON,
            giverIs = GiverIs.APPRENTICE
        )
    }

    /** 单向关系夹具：[giverIs] 决定谁是师父。 */
    private enum class GiverIs { MASTER, APPRENTICE }

    /**
     * 断言 [roll] 落在门内/门外时的赠送结果：
     * 概率门为 `rng.nextDouble() >= probability ⇒ 跳过`，
     * 故 [roll] 略低于概率必赠、略高于概率必不赠。
     */
    private fun assertGateFiresAt(roll: Double, giverIs: GiverIs) {
        handler = giftHandlerWith(roll)
        val giver = 1
        val receiver = 2
        insertDisciple(giver); insertDisciple(receiver)
        when (giverIs) {
            GiverIs.MASTER -> setMaster(receiver, giver)
            GiverIs.APPRENTICE -> setMaster(giver, receiver)
        }
        fillGiverBag(giver)

        handler.processGiftsForBreakthrough(receiver, tables, state)

        val expectedTransfer = roll < probabilityOf(giverIs)
        val receiverBagSize = (tables.storageBagItems.getOrNull(receiver) ?: emptyList()).size
        assertEquals(
            "roll=$roll giver=$giverIs 预期转移=$expectedTransfer",
            if (expectedTransfer) 1 else 0,
            receiverBagSize
        )
    }

    private fun probabilityOf(giverIs: GiverIs): Double = when (giverIs) {
        GiverIs.MASTER -> MASTER_GIFT_PROB
        GiverIs.APPRENTICE -> APPRENTICE_GIFT_PROB
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
            StorageBagItem(
                "pill_bp", "pill", "筑基丹", 5,
                effect = ItemEffect(
                    pillType = "breakthrough",
                    targetRealm = 9,
                    breakthroughChance = 0.3
                )
            ),
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
    fun `processGiftsForBreakthrough - 无师徒关系无操作`() {
        insertDisciple(1)
        // 不应抛异常
        handler.processGiftsForBreakthrough(1, tables, state)
    }

    private companion object {
        /** 师父保底赠送概率（与 C++ `kMasterGiftProb` 同源） */
        const val MASTER_GIFT_PROB = 0.40

        /** 徒弟保底赠送概率（与 C++ `kApprenticeGiftProb` 同源） */
        const val APPRENTICE_GIFT_PROB = 0.30

        /** 概率门探针偏移量：小于任一概率间隙（0.40-0.30）且大于浮点量化误差 */
        const val PROB_EPSILON = 0.001

        /** `DeterministicRng.nextDouble()` 的量化基数（低 31 位 / 2^31） */
        const val DOUBLE_SCALE = 2147483648.0

        /** 默认注入概率：高于两类关系门槛，保证非概率门用例必定触发赠送分支 */
        const val FIXED_GIFT_PROB = 0.99
    }
}
