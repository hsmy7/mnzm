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
        assertEquals(50, attrs.loyalty)
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
    fun discipleAttributes_comprehensionSpeedBonus_at50() {
        val attrs = DiscipleAttributes(comprehension = 50)
        assertEquals(1.0, attrs.comprehensionSpeedBonus, 0.001)
    }

    @Test
    fun discipleAttributes_comprehensionSpeedBonus_above50() {
        val attrs = DiscipleAttributes(comprehension = 75)
        // 1.0 + (75 - 50) * 0.02 = 1.0 + 0.5 = 1.5
        assertEquals(1.5, attrs.comprehensionSpeedBonus, 0.001)
    }

    @Test
    fun discipleAttributes_comprehensionSpeedBonus_below50() {
        val attrs = DiscipleAttributes(comprehension = 30)
        // 1.0 + (30 - 50) * 0.02 = 1.0 - 0.4 = 0.6
        assertEquals(0.6, attrs.comprehensionSpeedBonus, 0.001)
    }

    @Test
    fun discipleAttributes_customConstruction() {
        val attrs = DiscipleAttributes(
            discipleId = "d1",
            slotId = 1,
            intelligence = 80,
            loyalty = 90
        )
        assertEquals("d1", attrs.discipleId)
        assertEquals(80, attrs.intelligence)
        assertEquals(90, attrs.loyalty)
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
        assertEquals(12, stats.basePhysicalAttack)
        assertEquals(12, stats.baseMagicAttack)
        assertEquals(10, stats.basePhysicalDefense)
        assertEquals(8, stats.baseMagicDefense)
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
        assertEquals(0, stats.pillPhysicalAttackBonus)
        assertEquals(0, stats.pillMagicAttackBonus)
        assertEquals(0, stats.pillPhysicalDefenseBonus)
        assertEquals(0, stats.pillMagicDefenseBonus)
        assertEquals(0, stats.pillHpBonus)
        assertEquals(0, stats.pillMpBonus)
        assertEquals(0, stats.pillSpeedBonus)
        assertEquals(0.0, stats.pillCritRateBonus, 0.001)
        assertEquals(0.0, stats.pillCritEffectBonus, 0.001)
        assertEquals(0.0, stats.pillCultivationSpeedBonus, 0.001)
        assertEquals(0.0, stats.pillSkillExpSpeedBonus, 0.001)
        assertEquals(0.0, stats.pillNurtureSpeedBonus, 0.001)
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

    // ---- DiscipleCompact ----

    @Test
    fun discipleCompact_construction() {
        val compact = DiscipleCompact(
            id = "d1",
            slotId = 1,
            name = "Zhang San",
            cultivation = 100.0,
            realm = 9,
            realmLayer = 3,
            lifespan = 80,
            maxLifespan = 80,
            isAlive = true,
            spiritRoot = 1,
            combatPower = 5000L,
            cultivationSpeed = 1.5,
            age = 20
        )
        assertEquals("d1", compact.id)
        assertEquals("Zhang San", compact.name)
        assertEquals(100.0, compact.cultivation, 0.001)
        assertEquals(9, compact.realm)
        assertEquals(3, compact.realmLayer)
        assertTrue(compact.isAlive)
        assertEquals(1, compact.spiritRoot)
        assertEquals(5000L, compact.combatPower)
        assertEquals(20, compact.age)
    }

    @Test
    fun discipleCompact_defaultValues() {
        val compact = DiscipleCompact(id = "d1", name = "Test")
        assertEquals(0, compact.slotId)
        assertEquals(0.0, compact.cultivation, 0.001)
        assertEquals(0, compact.realm)
        assertEquals(0, compact.realmLayer)
        assertTrue(compact.isAlive)
        assertEquals(8.0, compact.cultivationSpeed, 0.001)
        assertEquals(0, compact.status)
    }

    @Test
    fun discipleCompact_copy() {
        val original = DiscipleCompact(id = "d1", name = "Test", realm = 9)
        val copied = original.copy(realm = 7)
        assertEquals("d1", copied.id)
        assertEquals(7, copied.realm)
    }

    // ---- CombatAttributes ----

    @Test
    fun combatAttributes_defaultConstruction() {
        val attrs = CombatAttributes()
        assertEquals(120, attrs.baseHp)
        assertEquals(60, attrs.baseMp)
        assertEquals(12, attrs.basePhysicalAttack)
        assertEquals(12, attrs.baseMagicAttack)
        assertEquals(10, attrs.basePhysicalDefense)
        assertEquals(8, attrs.baseMagicDefense)
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
            physicalAttackVariance = 20,
            magicAttackVariance = 0,
            physicalDefenseVariance = -5,
            magicDefenseVariance = 5,
            speedVariance = 30
        )
        assertEquals((120 * 1.10).toInt(), stats.baseHp)
        assertEquals((60 * 0.90).toInt(), stats.baseMp)
        assertEquals((12 * 1.20).toInt(), stats.basePhysicalAttack)
        assertEquals((12 * 1.00).toInt(), stats.baseMagicAttack)
        assertEquals((10 * 0.95).toInt(), stats.basePhysicalDefense)
        assertEquals((8 * 1.05).toInt(), stats.baseMagicDefense)
        assertEquals((15 * 1.30).toInt(), stats.baseSpeed)
    }

    // ---- PillEffects ----

    @Test
    fun pillEffects_defaultConstruction() {
        val effects = PillEffects()
        assertEquals(0, effects.pillPhysicalAttackBonus)
        assertEquals(0, effects.pillMagicAttackBonus)
        assertEquals(0, effects.pillPhysicalDefenseBonus)
        assertEquals(0, effects.pillMagicDefenseBonus)
        assertEquals(0, effects.pillHpBonus)
        assertEquals(0, effects.pillMpBonus)
        assertEquals(0, effects.pillSpeedBonus)
        assertEquals(0.0, effects.pillCritRateBonus, 0.001)
        assertEquals(0.0, effects.pillCritEffectBonus, 0.001)
        assertEquals(0.0, effects.pillCultivationSpeedBonus, 0.001)
        assertEquals(0.0, effects.pillSkillExpSpeedBonus, 0.001)
        assertEquals(0.0, effects.pillNurtureSpeedBonus, 0.001)
        assertEquals(0, effects.pillEffectDuration)
        assertEquals("", effects.activePillCategory)
    }

    // ---- EquipmentSet ----

    @Test
    fun equipmentSet_defaultConstruction() {
        val set = EquipmentSet()
        assertEquals("", set.weaponId)
        assertEquals("", set.armorId)
        assertEquals("", set.bootsId)
        assertEquals("", set.accessoryId)
        assertFalse(set.hasEquippedItems)
        assertEquals(emptyList<String>(), set.equippedItemIds)
        assertFalse(set.autoEquipFromWarehouse)
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
        val set = EquipmentSet(weaponId = "w1", armorId = "", bootsId = "b1", accessoryId = "")
        assertEquals(listOf("w1", "b1"), set.equippedItemIds)
    }

    @Test
    fun equipmentSet_nurtureDefaults() {
        val set = EquipmentSet()
        assertEquals(EquipmentNurtureData("", 0), set.weaponNurture)
        assertEquals(EquipmentNurtureData("", 0), set.armorNurture)
        assertEquals(EquipmentNurtureData("", 0), set.bootsNurture)
        assertEquals(EquipmentNurtureData("", 0), set.accessoryNurture)
    }

    // ---- SocialData ----

    @Test
    fun socialData_defaultConstruction() {
        val social = SocialData()
        assertNull(social.partnerId)
        assertNull(social.partnerSectId)
        assertNull(social.parentId1)
        assertNull(social.parentId2)
        assertEquals(0, social.lastChildYear)
        assertNull(social.childBirthMonth)
        assertNull(social.griefEndYear)
        assertFalse(social.hasPartner)
    }

    @Test
    fun socialData_hasPartner_whenSet() {
        val social = SocialData(partnerId = "p1")
        assertTrue(social.hasPartner)
    }

    // ---- SkillStats ----

    @Test
    fun skillStats_defaultConstruction() {
        val stats = SkillStats()
        assertEquals(50, stats.intelligence)
        assertEquals(50, stats.charm)
        assertEquals(50, stats.loyalty)
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

    @Test
    fun skillStats_comprehensionSpeedBonus_at50() {
        val stats = SkillStats(comprehension = 50)
        assertEquals(1.0, stats.comprehensionSpeedBonus, 0.001)
    }

    @Test
    fun skillStats_comprehensionSpeedBonus_above50() {
        val stats = SkillStats(comprehension = 100)
        // 1.0 + (100 - 50) * 0.02 = 2.0
        assertEquals(2.0, stats.comprehensionSpeedBonus, 0.001)
    }

    // ---- UsageTracking ----

    @Test
    fun usageTracking_defaultConstruction() {
        val tracking = UsageTracking()
        assertEquals(emptyList<String>(), tracking.usedFunctionalPillTypes)
        assertEquals(emptyList<String>(), tracking.usedExtendLifePillIds)
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
        assertEquals(16, core.age)
        assertEquals(80, core.lifespan)
        assertEquals("male", core.gender)
        assertEquals("", core.portraitRes)
        assertEquals("metal", core.spiritRootType)
        assertEquals(0, core.recruitedMonth)
    }

    @Test
    fun discipleCore_canCultivate_atAge5() {
        val core = DiscipleCore(age = 5)
        assertTrue(core.canCultivate)
    }

    @Test
    fun discipleCore_canCultivate_belowAge5() {
        val core = DiscipleCore(age = 4)
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
        assertEquals("", equip.weaponId)
        assertEquals("", equip.armorId)
        assertEquals("", equip.bootsId)
        assertEquals("", equip.accessoryId)
        assertEquals(EquipmentNurtureData("", 0), equip.weaponNurture)
        assertEquals(EquipmentNurtureData("", 0), equip.armorNurture)
        assertEquals(EquipmentNurtureData("", 0), equip.bootsNurture)
        assertEquals(EquipmentNurtureData("", 0), equip.accessoryNurture)
        assertEquals(emptyList<StorageBagItem>(), equip.storageBagItems)
        assertEquals(0L, equip.storageBagSpiritStones)
        assertEquals(0, equip.spiritStones)
        assertEquals(0, equip.soulPower)
        assertFalse(equip.autoEquipFromWarehouse)
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
        val equip = DiscipleEquipment(weaponId = "w1", armorId = "a1", bootsId = "", accessoryId = "acc1")
        assertEquals(listOf("w1", "a1", "acc1"), equip.equippedItemIds)
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
        assertEquals(emptyList<String>(), ext.talentIds)
        assertEquals(emptyMap<String, Int>(), ext.manualMasteries)
        assertEquals(emptyMap<String, String>(), ext.statusData)
        assertEquals(0.0, ext.cultivationSpeedBonus, 0.001)
        assertEquals(0, ext.cultivationSpeedDuration)
        assertNull(ext.partnerId)
        assertNull(ext.partnerSectId)
        assertNull(ext.parentId1)
        assertNull(ext.parentId2)
        assertEquals(0, ext.lastChildYear)
        assertNull(ext.griefEndYear)
        assertEquals(emptyList<String>(), ext.usedFunctionalPillTypes)
        assertEquals(emptyList<String>(), ext.usedExtendLifePillIds)
        assertFalse(ext.hasReviveEffect)
        assertFalse(ext.hasClearAllEffect)
        assertFalse(ext.autoLearnFromWarehouse)
    }

    @Test
    fun discipleExtended_hasPartner_whenSet() {
        val ext = DiscipleExtended(partnerId = "p1")
        assertTrue(ext.hasPartner)
    }

    @Test
    fun discipleExtended_hasPartner_whenNull() {
        val ext = DiscipleExtended()
        assertFalse(ext.hasPartner)
    }

    @Test
    fun discipleExtended_copy() {
        val original = DiscipleExtended(discipleId = "d1", cultivationSpeedBonus = 1.5)
        val copied = original.copy(cultivationSpeedBonus = 2.0)
        assertEquals("d1", copied.discipleId)
        assertEquals(2.0, copied.cultivationSpeedBonus, 0.001)
    }

    // ---- DiscipleAggregateWithRelations ----

    @Test
    fun discipleAggregateWithRelations_construction() {
        val core = DiscipleCore(id = "d1", name = "Test")
        val aggregate = DiscipleAggregateWithRelations(
            core = core,
            combatStats = null,
            equipment = null,
            extended = null,
            attributes = null
        )
        assertEquals("d1", aggregate.core.id)
        assertNull(aggregate.combatStats)
        assertNull(aggregate.equipment)
        assertNull(aggregate.extended)
        assertNull(aggregate.attributes)
    }

    @Test
    fun discipleAggregateWithRelations_withAllComponents() {
        val core = DiscipleCore(id = "d1", name = "Test")
        val combat = DiscipleCombatStats(discipleId = "d1")
        val equip = DiscipleEquipment(discipleId = "d1")
        val ext = DiscipleExtended(discipleId = "d1")
        val attrs = DiscipleAttributes(discipleId = "d1")

        val aggregate = DiscipleAggregateWithRelations(
            core = core,
            combatStats = combat,
            equipment = equip,
            extended = ext,
            attributes = attrs
        )
        assertNotNull(aggregate.combatStats)
        assertNotNull(aggregate.equipment)
        assertNotNull(aggregate.extended)
        assertNotNull(aggregate.attributes)
        assertEquals("d1", aggregate.combatStats!!.discipleId)
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
        assertEquals(12, stats.basePhysicalAttack)
        assertEquals(12, stats.baseMagicAttack)
        assertEquals(10, stats.basePhysicalDefense)
    }
}
