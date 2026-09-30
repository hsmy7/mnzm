package com.xianxia.sect.core.model

import org.junit.Assert.*
import org.junit.Test

class DiscipleModelsTest {

    // ---- DiscipleAttributes ----

    @Test
    fun discipleAttributes_defaultConstruction() {
        val attrs = DiscipleAttributes()
        assertEquals("", attrs.discipleId)
        assertEquals(0, attrs.slotId)
        assertEquals(50, attrs.intelligence)
        assertEquals(50, attrs.charm)
        assertEquals(50, attrs.comprehension)
        assertEquals(50, attrs.artifactRefining)
        assertEquals(50, attrs.pillRefining)
        assertEquals(50, attrs.spiritPlanting)
        assertEquals(50, attrs.mining)
        assertEquals(50, attrs.teaching)
        assertEquals(50, attrs.morality)
        assertEquals(0, attrs.salaryPaidCount)
        assertEquals(0, attrs.salaryMissedCount)
    }

    @Test
    fun discipleAttributes_customConstruction() {
        val attrs = DiscipleAttributes(
            discipleId = "d1",
            slotId = 1,
            intelligence = 80,
            morality = 90
        )
        assertEquals("d1", attrs.discipleId)
        assertEquals(80, attrs.intelligence)
        assertEquals(90, attrs.morality)
    }

    @Test
    fun discipleAttributes_copy() {
        val original = DiscipleAttributes(discipleId = "d1", intelligence = 60)
        val copied = original.copy(intelligence = 90)
        assertEquals("d1", copied.discipleId)
        assertEquals(90, copied.intelligence)
    }

    // ---- DiscipleCombatStats ----

    @Test
    fun discipleCombatStats_defaultConstruction() {
        val stats = DiscipleCombatStats()
        assertEquals("", stats.discipleId)
        assertEquals(0, stats.slotId)
        assertEquals(120, stats.baseHp)
        assertEquals(60, stats.baseMp)
        assertEquals(24, stats.baseAttack)
        assertEquals(18, stats.baseDefense)
        assertEquals(15, stats.baseSpeed)
        assertEquals(0, stats.hpVariance)
        assertEquals(0, stats.totalCultivation)
        assertEquals(0, stats.breakthroughCount)
        assertEquals(0, stats.breakthroughFailCount)
        assertEquals(-1, stats.currentHp)
        assertEquals(-1, stats.currentMp)
    }

    @Test
    fun discipleCombatStats_pillBonusDefaults() {
        val stats = DiscipleCombatStats()
        assertEquals(0, stats.pillAttackBonus)
        assertEquals(0, stats.pillDefenseBonus)
        assertEquals(0, stats.pillHpBonus)
        assertEquals(0, stats.pillMpBonus)
        assertEquals(0, stats.pillSpeedBonus)
        assertEquals(0.0, stats.pillCritRateBonus, 0.001)
        assertEquals(0.0, stats.pillCritEffectBonus, 0.001)
        assertEquals(0.0, stats.pillCultivationSpeedBonus, 0.001)
        assertEquals(0.0, stats.pillSkillExpSpeedBonus, 0.001)
        assertEquals(0, stats.pillEffectDuration)
        assertEquals("", stats.activePillCategory)
    }

    @Test
    fun discipleCombatStats_customConstruction() {
        val stats = DiscipleCombatStats(
            discipleId = "d1",
            baseHp = 200,
            baseMp = 100,
            totalCultivation = 50000L
        )
        assertEquals("d1", stats.discipleId)
        assertEquals(200, stats.baseHp)
        assertEquals(100, stats.baseMp)
        assertEquals(50000L, stats.totalCultivation)
    }

    @Test
    fun discipleCombatStats_copy() {
        val original = DiscipleCombatStats(discipleId = "d1", baseHp = 120)
        val copied = original.copy(baseHp = 200)
        assertEquals("d1", copied.discipleId)
        assertEquals(200, copied.baseHp)
    }

    // ---- CombatAttributes ----

    @Test
    fun combatAttributes_defaultConstruction() {
        val attrs = CombatAttributes()
        assertEquals(120, attrs.baseHp)
        assertEquals(60, attrs.baseMp)
        assertEquals(24, attrs.baseAttack)
        assertEquals(18, attrs.baseDefense)
        assertEquals(15, attrs.baseSpeed)
        assertEquals(0, attrs.hpVariance)
        assertEquals(0, attrs.totalCultivation)
        assertEquals(0, attrs.breakthroughCount)
        assertEquals(-1, attrs.currentHp)
        assertEquals(-1, attrs.currentMp)
    }

    @Test
    fun combatAttributes_calculateBaseStatsWithVariance() {
        val stats = CombatAttributes.calculateBaseStatsWithVariance(
            hpVariance = 10,
            mpVariance = -10,
            attackVariance = 20,
            defenseVariance = 0,
            speedVariance = 30
        )
        assertEquals((120 * 1.10).toInt(), stats.baseHp)
        assertEquals((60 * 0.90).toInt(), stats.baseMp)
        // 单列口径（B1）：24 基值单方差（旧物法 12/12 各自方差已并）
        assertEquals((24 * 1.20).toInt(), stats.baseAttack)
        assertEquals((18 * 1.00).toInt(), stats.baseDefense)
        assertEquals((15 * 1.30).toInt(), stats.baseSpeed)
    }

    // ---- PillEffects ----

    @Test
    fun pillEffects_defaultConstruction() {
        val effects = PillEffects()
        assertEquals(0, effects.pillAttackBonus)
        assertEquals(0, effects.pillDefenseBonus)
        assertEquals(0, effects.pillHpBonus)
        assertEquals(0, effects.pillMpBonus)
        assertEquals(0, effects.pillSpeedBonus)
        assertEquals(0.0, effects.pillCritRateBonus, 0.001)
        assertEquals(0.0, effects.pillCritEffectBonus, 0.001)
        assertEquals(0.0, effects.pillCultivationSpeedBonus, 0.001)
        assertEquals(0.0, effects.pillSkillExpSpeedBonus, 0.001)
        assertEquals(0, effects.pillEffectDuration)
        assertEquals("", effects.activePillCategory)
    }

    // ---- EquipmentSet ----

    @Test
    fun equipmentSet_defaultConstruction() {
        val set = EquipmentSet()
        assertEquals("", set.headId)
        assertEquals("", set.bodyId)
        assertEquals("", set.handsId)
        assertEquals("", set.feetId)
        assertEquals("", set.weaponId)
        assertEquals("", set.legsId)
        assertFalse(set.hasEquippedItems)
        assertEquals(emptyList<String>(), set.equippedItemIds)
        assertEquals(emptyList<StorageBagItem>(), set.storageBagItems)
        assertEquals(0L, set.storageBagSpiritStones)
        assertEquals(0, set.spiritStones)
    }

    @Test
    fun equipmentSet_hasEquippedItems_whenWeaponEquipped() {
        val set = EquipmentSet(weaponId = "w1")
        assertTrue(set.hasEquippedItems)
    }

    @Test
    fun equipmentSet_equippedItemIds_filtersEmpty() {
        // equippedItemIds 顺序 = displayOrder（头/身/手/脚/武/腿）
        val set = EquipmentSet(headId = "h1", bodyId = "", handsId = "", feetId = "f1", weaponId = "w1")
        assertEquals(listOf("h1", "f1", "w1"), set.equippedItemIds)
    }

    @Test
    fun equipmentSet_slotIdRoundTrip() {
        // slotId(part)/setSlotId(part,id) 六部位读写往返
        val set = EquipmentSet()
        for (part in EquipmentSlot.displayOrder) {
            set.setSlotId(part, "eq-${part.name}")
            assertEquals("eq-${part.name}", set.slotId(part))
        }
        assertTrue(set.hasEquippedItems)
        assertEquals(6, set.equippedItemIds.size)
    }

    // ---- SkillStats ----

    @Test
    fun skillStats_defaultConstruction() {
        val stats = SkillStats()
        assertEquals(50, stats.intelligence)
        assertEquals(50, stats.charm)
        assertEquals(50, stats.comprehension)
        assertEquals(50, stats.artifactRefining)
        assertEquals(50, stats.pillRefining)
        assertEquals(50, stats.spiritPlanting)
        assertEquals(50, stats.mining)
        assertEquals(50, stats.teaching)
        assertEquals(50, stats.morality)
        assertEquals(0, stats.salaryPaidCount)
        assertEquals(0, stats.salaryMissedCount)
    }

    // ---- UsageTracking ----

    @Test
    fun usageTracking_defaultConstruction() {
        val tracking = UsageTracking()
        assertEquals(emptyList<String>(), tracking.usedFunctionalPillTypes)
        assertEquals(0, tracking.recruitedMonth)
        assertFalse(tracking.hasReviveEffect)
        assertFalse(tracking.hasClearAllEffect)
    }

    // ---- DiscipleCore ----

    @Test
    fun discipleCore_defaultConstruction() {
        val core = DiscipleCore()
        assertNotNull(core.id)
        assertEquals(0, core.slotId)
        assertEquals("", core.name)
        assertEquals("", core.surname)
        assertEquals(9, core.realm)
        assertEquals(1, core.realmLayer)
        assertEquals(0.0, core.cultivation, 0.001)
        assertTrue(core.isAlive)
        assertEquals(DiscipleStatus.IDLE.name, core.status)
        assertEquals("outer", core.discipleType)
        assertEquals("male", core.gender)
        assertEquals("", core.portraitRes)
        assertEquals("metal", core.spiritRootType)
        assertEquals(0, core.recruitedMonth)
    }

    @Test
    fun discipleCore_canCultivate_withRealmLayer() {
        val core = DiscipleCore(realmLayer = 1)
        assertTrue(core.canCultivate)
    }

    @Test
    fun discipleCore_canCultivate_realmLayerZero() {
        val core = DiscipleCore(realmLayer = 0)
        assertFalse(core.canCultivate)
    }

    @Test
    fun discipleCore_genderName() {
        assertEquals("男", DiscipleCore(gender = "male").genderName)
        assertEquals("女", DiscipleCore(gender = "female").genderName)
    }

    @Test
    fun discipleCore_genderSymbol() {
        assertEquals("♂", DiscipleCore(gender = "male").genderSymbol)
        assertEquals("♀", DiscipleCore(gender = "female").genderSymbol)
    }

    @Test
    fun discipleCore_spiritRoot() {
        val core = DiscipleCore(spiritRootType = "fire")
        assertEquals(SpiritRoot("fire"), core.spiritRoot)
    }

    @Test
    fun discipleCore_copy() {
        val original = DiscipleCore(id = "d1", name = "Test", realm = 9)
        val copied = original.copy(realm = 7)
        assertEquals("d1", copied.id)
        assertEquals(7, copied.realm)
    }

    // ---- DiscipleEquipment ----

    @Test
    fun discipleEquipment_defaultConstruction() {
        val equip = DiscipleEquipment()
        assertEquals("", equip.discipleId)
        assertEquals(0, equip.slotId)
        assertEquals("", equip.headId)
        assertEquals("", equip.bodyId)
        assertEquals("", equip.handsId)
        assertEquals("", equip.feetId)
        assertEquals("", equip.weaponId)
        assertEquals("", equip.legsId)
        assertEquals(emptyList<StorageBagItem>(), equip.storageBagItems)
        assertEquals(0L, equip.storageBagSpiritStones)
        assertEquals(0, equip.spiritStones)
    }

    @Test
    fun discipleEquipment_hasEquippedItems_whenEmpty() {
        val equip = DiscipleEquipment()
        assertFalse(equip.hasEquippedItems)
    }

    @Test
    fun discipleEquipment_hasEquippedItems_whenWeaponEquipped() {
        val equip = DiscipleEquipment(weaponId = "w1")
        assertTrue(equip.hasEquippedItems)
    }

    @Test
    fun discipleEquipment_equippedItemIds() {
        // 顺序 = displayOrder（头/身/手/脚/武/腿）
        val equip = DiscipleEquipment(headId = "h1", feetId = "f1", weaponId = "w1")
        assertEquals(listOf("h1", "f1", "w1"), equip.equippedItemIds)
    }

    @Test
    fun discipleEquipment_copy() {
        val original = DiscipleEquipment(discipleId = "d1", weaponId = "w1")
        val copied = original.copy(weaponId = "w2")
        assertEquals("d1", copied.discipleId)
        assertEquals("w2", copied.weaponId)
    }

    // ---- DiscipleExtended ----

    @Test
    fun discipleExtended_defaultConstruction() {
        val ext = DiscipleExtended()
        assertEquals("", ext.discipleId)
        assertEquals(0, ext.slotId)
        assertEquals(emptyList<String>(), ext.manualIds)
        assertEquals(emptyMap<String, Int>(), ext.manualMasteries)
        assertEquals(emptyMap<String, String>(), ext.statusData)
        assertEquals(0.0, ext.cultivationSpeedBonus, 0.001)
        assertEquals(0, ext.cultivationSpeedDuration)
        assertEquals(emptyList<String>(), ext.usedFunctionalPillTypes)
        assertFalse(ext.hasReviveEffect)
        assertFalse(ext.hasClearAllEffect)
    }

    @Test
    fun discipleExtended_copy() {
        val original = DiscipleExtended(discipleId = "d1", cultivationSpeedBonus = 1.5)
        val copied = original.copy(cultivationSpeedBonus = 2.0)
        assertEquals("d1", copied.discipleId)
        assertEquals(2.0, copied.cultivationSpeedBonus, 0.001)
    }

    // ---- EquipmentNurtureData ----

    @Test
    fun equipmentNurtureData_construction() {
        val data = EquipmentNurtureData("eq1", 3)
        assertEquals("eq1", data.equipmentId)
        assertEquals(3, data.rarity)
        assertEquals(0, data.nurtureLevel)
        assertEquals(0.0, data.nurtureProgress, 0.001)
    }

    @Test
    fun equipmentNurtureData_withNurture() {
        val data = EquipmentNurtureData("eq1", 5, 10, 0.75)
        assertEquals(10, data.nurtureLevel)
        assertEquals(0.75, data.nurtureProgress, 0.001)
    }

    // ---- StorageBagItem ----

    @Test
    fun storageBagItem_construction() {
        val item = StorageBagItem(
            itemId = "i1",
            itemType = "pill",
            name = "Test Pill",
            rarity = 3,
            quantity = 5
        )
        assertEquals("i1", item.itemId)
        assertEquals("pill", item.itemType)
        assertEquals("Test Pill", item.name)
        assertEquals(3, item.rarity)
        assertEquals(5, item.quantity)
    }

    // ---- BaseCombatStats ----

    @Test
    fun baseCombatStats_defaultConstruction() {
        val stats = BaseCombatStats()
        assertEquals(120, stats.baseHp)
        assertEquals(60, stats.baseMp)
        assertEquals(24, stats.baseAttack)
        assertEquals(18, stats.baseDefense)
    }
}
