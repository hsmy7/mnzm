package com.xianxia.sect.core.model

import org.junit.Assert.*
import org.junit.Test

class ItemsTest {

    // ---- EquipmentSlot ----

    @Test
    fun equipmentSlot_hasFourValues() {
        assertEquals(4, EquipmentSlot.entries.size)
    }

    @Test
    fun equipmentSlot_values() {
        assertArrayEquals(
            arrayOf(EquipmentSlot.WEAPON, EquipmentSlot.ARMOR, EquipmentSlot.BOOTS, EquipmentSlot.ACCESSORY),
            EquipmentSlot.entries.toTypedArray()
        )
    }

    @Test
    fun equipmentSlot_displayNames() {
        assertEquals("武器", EquipmentSlot.WEAPON.displayName)
        assertEquals("护甲", EquipmentSlot.ARMOR.displayName)
        assertEquals("靴子", EquipmentSlot.BOOTS.displayName)
        assertEquals("饰品", EquipmentSlot.ACCESSORY.displayName)
    }

    // ---- ManualType ----

    @Test
    fun manualType_hasFourValues() {
        assertEquals(4, ManualType.entries.size)
    }

    @Test
    fun manualType_values() {
        assertArrayEquals(
            arrayOf(ManualType.ATTACK, ManualType.DEFENSE, ManualType.SUPPORT, ManualType.MIND),
            ManualType.entries.toTypedArray()
        )
    }

    @Test
    fun manualType_displayNames() {
        assertEquals("攻击型", ManualType.ATTACK.displayName)
        assertEquals("防御型", ManualType.DEFENSE.displayName)
        assertEquals("辅助型", ManualType.SUPPORT.displayName)
        assertEquals("心法型", ManualType.MIND.displayName)
    }

    // ---- PillCategory ----

    @Test
    fun pillCategory_hasThreeValues() {
        assertEquals(3, PillCategory.entries.size)
    }

    @Test
    fun pillCategory_values() {
        assertArrayEquals(
            arrayOf(PillCategory.CULTIVATION, PillCategory.BATTLE, PillCategory.FUNCTIONAL),
            PillCategory.entries.toTypedArray()
        )
    }

    @Test
    fun pillCategory_displayNames() {
        assertEquals("修炼丹药", PillCategory.CULTIVATION.displayName)
        assertEquals("战斗丹药", PillCategory.BATTLE.displayName)
        assertEquals("功能丹药", PillCategory.FUNCTIONAL.displayName)
    }

    // ---- PillGrade ----

    @Test
    fun pillGrade_hasThreeValues() {
        assertEquals(3, PillGrade.entries.size)
    }

    @Test
    fun pillGrade_values() {
        assertArrayEquals(
            arrayOf(PillGrade.LOW, PillGrade.MEDIUM, PillGrade.HIGH),
            PillGrade.entries.toTypedArray()
        )
    }

    @Test
    fun pillGrade_displayNames() {
        assertEquals("下品", PillGrade.LOW.displayName)
        assertEquals("中品", PillGrade.MEDIUM.displayName)
        assertEquals("上品", PillGrade.HIGH.displayName)
    }

    @Test
    fun pillGrade_multipliers() {
        assertEquals(0.5, PillGrade.LOW.multiplier, 0.001)
        assertEquals(1.0, PillGrade.MEDIUM.multiplier, 0.001)
        assertEquals(2.0, PillGrade.HIGH.multiplier, 0.001)
    }

    @Test
    fun pillGrade_priceMultipliers() {
        assertEquals(0.5, PillGrade.LOW.priceMultiplier, 0.001)
        assertEquals(1.0, PillGrade.MEDIUM.priceMultiplier, 0.001)
        assertEquals(2.0, PillGrade.HIGH.priceMultiplier, 0.001)
    }

    // ---- MaterialCategory ----

    @Test
    fun materialCategory_hasTwelveValues() {
        assertEquals(12, MaterialCategory.entries.size)
    }

    @Test
    fun materialCategory_values() {
        val expected = arrayOf(
            MaterialCategory.BEAST_HIDE,
            MaterialCategory.BEAST_BONE,
            MaterialCategory.BEAST_TOOTH,
            MaterialCategory.BEAST_CORE,
            MaterialCategory.BEAST_CLAW,
            MaterialCategory.BEAST_FEATHER,
            MaterialCategory.BEAST_TAIL,
            MaterialCategory.BEAST_SCALE,
            MaterialCategory.BEAST_HORN,
            MaterialCategory.BEAST_SHELL,
            MaterialCategory.BEAST_BLOOD,
            MaterialCategory.BEAST_PLASTRON
        )
        assertArrayEquals(expected, MaterialCategory.entries.toTypedArray())
    }

    @Test
    fun materialCategory_displayNames() {
        assertEquals("兽皮", MaterialCategory.BEAST_HIDE.displayName)
        assertEquals("兽骨", MaterialCategory.BEAST_BONE.displayName)
        assertEquals("兽牙", MaterialCategory.BEAST_TOOTH.displayName)
        assertEquals("内丹", MaterialCategory.BEAST_CORE.displayName)
        assertEquals("兽爪", MaterialCategory.BEAST_CLAW.displayName)
        assertEquals("兽羽", MaterialCategory.BEAST_FEATHER.displayName)
        assertEquals("兽尾", MaterialCategory.BEAST_TAIL.displayName)
        assertEquals("鳞片", MaterialCategory.BEAST_SCALE.displayName)
        assertEquals("兽角", MaterialCategory.BEAST_HORN.displayName)
        assertEquals("龟壳", MaterialCategory.BEAST_SHELL.displayName)
        assertEquals("兽血", MaterialCategory.BEAST_BLOOD.displayName)
        assertEquals("龟甲", MaterialCategory.BEAST_PLASTRON.displayName)
    }

    // ---- EquipmentStack ----

    @Test
    fun equipmentStack_defaultConstruction() {
        val stack = EquipmentStack()
        assertNotNull(stack.id)
        assertEquals(0, stack.slotId)
        assertEquals("", stack.name)
        assertEquals(1, stack.rarity)
        assertEquals("", stack.description)
        assertEquals(EquipmentSlot.WEAPON, stack.slot)
        assertEquals(0, stack.physicalAttack)
        assertEquals(0, stack.magicAttack)
        assertEquals(0, stack.physicalDefense)
        assertEquals(0, stack.magicDefense)
        assertEquals(0, stack.speed)
        assertEquals(0, stack.hp)
        assertEquals(0, stack.mp)
        assertEquals(0.0, stack.critChance, 0.001)
        assertEquals(9, stack.minRealm)
        assertEquals(1, stack.quantity)
        assertFalse(stack.isLocked)
    }

    @Test
    fun equipmentStack_customConstruction() {
        val stack = EquipmentStack(
            id = "test-id",
            slotId = 1,
            name = "Test Sword",
            rarity = 3,
            description = "A test sword",
            slot = EquipmentSlot.WEAPON,
            physicalAttack = 100,
            magicAttack = 50,
            physicalDefense = 20,
            magicDefense = 10,
            speed = 30,
            hp = 200,
            mp = 100,
            critChance = 0.15,
            minRealm = 5,
            quantity = 3,
            isLocked = true
        )
        assertEquals("test-id", stack.id)
        assertEquals("Test Sword", stack.name)
        assertEquals(3, stack.rarity)
        assertEquals(EquipmentSlot.WEAPON, stack.slot)
        assertEquals(100, stack.physicalAttack)
        assertEquals(3, stack.quantity)
        assertTrue(stack.isLocked)
    }

    @Test
    fun equipmentStack_copy() {
        val original = EquipmentStack(id = "orig", name = "Sword", rarity = 2)
        val copied = original.copy(name = "Axe", rarity = 4)
        assertEquals("orig", copied.id)
        assertEquals("Axe", copied.name)
        assertEquals(4, copied.rarity)
    }

    @Test
    fun equipmentStack_withQuantity() {
        val stack = EquipmentStack(id = "s1", quantity = 5)
        val newStack = stack.withQuantity(10)
        assertEquals(10, newStack.quantity)
        assertEquals("s1", newStack.id)
    }

    // ---- ManualStack ----

    @Test
    fun manualStack_defaultConstruction() {
        val stack = ManualStack()
        assertNotNull(stack.id)
        assertEquals(0, stack.slotId)
        assertEquals("", stack.name)
        assertEquals(1, stack.rarity)
        assertEquals(ManualType.MIND, stack.type)
        assertEquals(emptyMap<String, Int>(), stack.stats)
        assertEquals(9, stack.minRealm)
        assertEquals(1, stack.quantity)
        assertFalse(stack.isLocked)
    }

    @Test
    fun manualStack_customConstruction() {
        val stack = ManualStack(
            id = "m1",
            name = "Fire Manual",
            rarity = 3,
            type = ManualType.ATTACK,
            stats = mapOf("physicalAttack" to 50),
            skillName = "Fireball",
            quantity = 2
        )
        assertEquals("m1", stack.id)
        assertEquals("Fire Manual", stack.name)
        assertEquals(ManualType.ATTACK, stack.type)
        assertEquals(mapOf("physicalAttack" to 50), stack.stats)
        assertEquals("Fireball", stack.skillName)
        assertEquals(2, stack.quantity)
    }

    @Test
    fun manualStack_copy() {
        val original = ManualStack(id = "m1", name = "Manual", rarity = 2)
        val copied = original.copy(rarity = 5)
        assertEquals("m1", copied.id)
        assertEquals(5, copied.rarity)
    }

    // ---- Pill ----

    @Test
    fun pill_defaultConstruction() {
        val pill = Pill()
        assertNotNull(pill.id)
        assertEquals("", pill.name)
        assertEquals(1, pill.rarity)
        assertEquals(PillCategory.CULTIVATION, pill.category)
        assertEquals(PillGrade.MEDIUM, pill.grade)
        assertEquals("", pill.pillType)
        assertEquals(9, pill.minRealm)
        assertEquals(1, pill.quantity)
        assertFalse(pill.isLocked)
    }

    @Test
    fun pill_customConstruction() {
        val pill = Pill(
            id = "p1",
            name = "Breakthrough Pill",
            rarity = 4,
            category = PillCategory.BATTLE,
            grade = PillGrade.HIGH,
            pillType = "breakthrough",
            quantity = 5
        )
        assertEquals("p1", pill.id)
        assertEquals("Breakthrough Pill", pill.name)
        assertEquals(PillCategory.BATTLE, pill.category)
        assertEquals(PillGrade.HIGH, pill.grade)
        assertEquals(5, pill.quantity)
    }

    @Test
    fun pill_copy() {
        val original = Pill(id = "p1", name = "Pill", rarity = 2)
        val copied = original.copy(grade = PillGrade.LOW)
        assertEquals("p1", copied.id)
        assertEquals(PillGrade.LOW, copied.grade)
    }

    @Test
    fun pill_withQuantity() {
        val pill = Pill(id = "p1", quantity = 3)
        val newPill = pill.withQuantity(7)
        assertEquals(7, newPill.quantity)
        assertEquals("p1", newPill.id)
    }

    @Test
    fun pill_effectDelegation() {
        val pill = Pill(
            effects = PillEffect(
                breakthroughChance = 0.5,
                targetRealm = 8,
                cultivationSpeedPercent = 20.0,
                duration = 5
            )
        )
        assertEquals(0.5, pill.breakthroughChance, 0.001)
        assertEquals(8, pill.targetRealm)
        assertEquals(20.0, pill.cultivationSpeedPercent, 0.001)
        assertEquals(5, pill.duration)
    }

    // ---- PillEffect ----

    @Test
    fun pillEffect_defaultConstruction() {
        val effect = PillEffect()
        assertEquals(0.0, effect.breakthroughChance, 0.001)
        assertEquals(0, effect.targetRealm)
        assertFalse(effect.isAscension)
        assertEquals(0.0, effect.cultivationSpeedPercent, 0.001)
        assertEquals(3, effect.duration)
        assertTrue(effect.cannotStack)
        assertFalse(effect.revive)
        assertFalse(effect.clearAll)
    }

    // ---- Material ----

    @Test
    fun material_defaultConstruction() {
        val material = Material()
        assertNotNull(material.id)
        assertEquals("", material.name)
        assertEquals(1, material.rarity)
        assertEquals(MaterialCategory.BEAST_HIDE, material.category)
        assertEquals(1, material.quantity)
        assertFalse(material.isLocked)
    }

    @Test
    fun material_customConstruction() {
        val material = Material(
            id = "mat1",
            name = "Dragon Scale",
            rarity = 5,
            category = MaterialCategory.BEAST_SCALE,
            quantity = 10
        )
        assertEquals("mat1", material.id)
        assertEquals("Dragon Scale", material.name)
        assertEquals(MaterialCategory.BEAST_SCALE, material.category)
        assertEquals(10, material.quantity)
    }

    @Test
    fun material_copy() {
        val original = Material(id = "mat1", name = "Bone", quantity = 3)
        val copied = original.copy(quantity = 8)
        assertEquals("mat1", copied.id)
        assertEquals(8, copied.quantity)
    }

    @Test
    fun material_withQuantity() {
        val material = Material(id = "mat1", quantity = 2)
        val newMaterial = material.withQuantity(6)
        assertEquals(6, newMaterial.quantity)
    }

    // ---- Herb ----

    @Test
    fun herb_defaultConstruction() {
        val herb = Herb()
        assertNotNull(herb.id)
        assertEquals("", herb.name)
        assertEquals(1, herb.rarity)
        assertEquals("", herb.category)
        assertEquals(1, herb.quantity)
        assertFalse(herb.isLocked)
    }

    @Test
    fun herb_customConstruction() {
        val herb = Herb(
            id = "h1",
            name = "Spirit Grass",
            rarity = 3,
            category = "grass",
            quantity = 5
        )
        assertEquals("h1", herb.id)
        assertEquals("Spirit Grass", herb.name)
        assertEquals("grass", herb.category)
        assertEquals(5, herb.quantity)
    }

    @Test
    fun herb_withQuantity() {
        val herb = Herb(id = "h1", quantity = 2)
        val newHerb = herb.withQuantity(4)
        assertEquals(4, newHerb.quantity)
    }

    // ---- Seed ----

    @Test
    fun seed_defaultConstruction() {
        val seed = Seed()
        assertNotNull(seed.id)
        assertEquals("", seed.name)
        assertEquals(1, seed.rarity)
        assertEquals(3, seed.growTime)
        assertEquals(1, seed.yield)
        assertEquals(1, seed.quantity)
        assertFalse(seed.isLocked)
    }

    @Test
    fun seed_customConstruction() {
        val seed = Seed(
            id = "seed1",
            name = "Fire Seed",
            rarity = 4,
            growTime = 6,
            yield = 3,
            quantity = 2
        )
        assertEquals("seed1", seed.id)
        assertEquals("Fire Seed", seed.name)
        assertEquals(6, seed.growTime)
        assertEquals(3, seed.yield)
    }

    @Test
    fun seed_withQuantity() {
        val seed = Seed(id = "seed1", quantity = 1)
        val newSeed = seed.withQuantity(5)
        assertEquals(5, newSeed.quantity)
    }

    // ---- MerchantItem ----

    @Test
    fun merchantItem_defaultConstruction() {
        val item = MerchantItem()
        assertEquals("", item.id)
        assertEquals("", item.name)
        assertEquals("", item.type)
        assertEquals("", item.itemId)
        assertEquals(1, item.rarity)
        assertEquals(0L, item.price)
        assertEquals(1, item.quantity)
        assertEquals("", item.description)
        assertEquals(0, item.obtainedYear)
        assertEquals(0, item.obtainedMonth)
        assertNull(item.grade)
    }

    @Test
    fun merchantItem_customConstruction() {
        val item = MerchantItem(
            id = "mi1",
            name = "Sword",
            type = "equipment",
            itemId = "eq1",
            rarity = 3,
            price = 1000L,
            quantity = 2,
            grade = "上品"
        )
        assertEquals("mi1", item.id)
        assertEquals("Sword", item.name)
        assertEquals("equipment", item.type)
        assertEquals(1000L, item.price)
        assertEquals("上品", item.grade)
    }

    @Test
    fun merchantItem_copy() {
        val original = MerchantItem(id = "mi1", name = "Sword", price = 500L)
        val copied = original.copy(price = 800L)
        assertEquals("mi1", copied.id)
        assertEquals(800L, copied.price)
    }

    // ---- DirectDiscipleSlot ----

    @Test
    fun directDiscipleSlot_defaultConstruction() {
        val slot = DirectDiscipleSlot()
        assertEquals(0, slot.index)
        assertEquals("", slot.discipleId)
        assertEquals("", slot.discipleName)
        assertEquals("", slot.discipleRealm)
        assertEquals("#E0E0E0", slot.discipleSpiritRootColor)
        assertEquals("", slot.sectId)
    }

    @Test
    fun directDiscipleSlot_isActive_whenEmpty() {
        val slot = DirectDiscipleSlot()
        assertFalse(slot.isActive)
    }

    @Test
    fun directDiscipleSlot_isActive_whenFilled() {
        val slot = DirectDiscipleSlot(discipleId = "d1")
        assertTrue(slot.isActive)
    }

    @Test
    fun directDiscipleSlot_customConstruction() {
        val slot = DirectDiscipleSlot(
            index = 2,
            discipleId = "d1",
            discipleName = "Zhang San",
            discipleRealm = "炼气",
            discipleSpiritRootColor = "#FF0000",
            sectId = "sect1"
        )
        assertEquals(2, slot.index)
        assertEquals("d1", slot.discipleId)
        assertEquals("Zhang San", slot.discipleName)
    }

    // ---- SectRelation ----

    @Test
    fun sectRelation_construction() {
        val relation = SectRelation(
            sectId1 = "s1",
            sectId2 = "s2",
            favor = 60,
            lastInteractionYear = 5,
            noGiftYears = 2
        )
        assertEquals("s1", relation.sectId1)
        assertEquals("s2", relation.sectId2)
        assertEquals(60, relation.favor)
        assertEquals(5, relation.lastInteractionYear)
        assertEquals(2, relation.noGiftYears)
    }

    @Test
    fun sectRelation_defaultFavor() {
        val relation = SectRelation(sectId1 = "a", sectId2 = "b")
        assertEquals(50, relation.favor)
    }

    // ---- Alliance ----

    @Test
    fun alliance_defaultConstruction() {
        val alliance = Alliance()
        assertNotNull(alliance.id)
        assertEquals(emptyList<String>(), alliance.sectIds)
        assertEquals(0, alliance.startYear)
        assertEquals("", alliance.initiatorId)
        assertEquals("", alliance.envoyDiscipleId)
    }

    @Test
    fun alliance_customConstruction() {
        val alliance = Alliance(
            sectIds = listOf("s1", "s2"),
            startYear = 10,
            initiatorId = "s1",
            envoyDiscipleId = "d1"
        )
        assertEquals(listOf("s1", "s2"), alliance.sectIds)
        assertEquals(10, alliance.startYear)
    }

    // ---- WorldSect ----

    @Test
    fun worldSect_defaultConstruction() {
        val sect = WorldSect()
        assertEquals("", sect.id)
        assertEquals("", sect.name)
        assertEquals(0, sect.level)
        assertEquals("小型宗门", sect.levelName)
        assertEquals(0f, sect.x, 0.001f)
        assertEquals(0f, sect.y, 0.001f)
        assertEquals(0, sect.distance)
        assertFalse(sect.isPlayerSect)
        assertFalse(sect.discovered)
        assertFalse(sect.isKnown)
    }

    @Test
    fun worldSect_customConstruction() {
        val sect = WorldSect(
            id = "ws1",
            name = "Test Sect",
            level = 3,
            isPlayerSect = true
        )
        assertEquals("ws1", sect.id)
        assertEquals("Test Sect", sect.name)
        assertEquals(3, sect.level)
        assertTrue(sect.isPlayerSect)
    }

    // ---- ElderSlots ----

    @Test
    fun elderSlots_defaultConstruction() {
        val slots = ElderSlots()
        assertEquals("", slots.viceSectMaster)
        assertEquals("", slots.herbGardenElder)
        assertEquals("", slots.alchemyElder)
        assertEquals("", slots.forgeElder)
        assertEquals("", slots.outerElder)
        assertEquals("", slots.preachingElder)
        assertEquals(emptyList<DirectDiscipleSlot>(), slots.preachingMasters)
        assertEquals("", slots.lawEnforcementElder)
        assertEquals(emptyList<DirectDiscipleSlot>(), slots.lawEnforcementDisciples)
        assertEquals(emptyList<DirectDiscipleSlot>(), slots.lawEnforcementReserveDisciples)
        assertEquals("", slots.innerElder)
        assertEquals("", slots.qingyunPreachingElder)
        assertEquals(emptyList<DirectDiscipleSlot>(), slots.qingyunPreachingMasters)
        assertEquals(emptyList<DirectDiscipleSlot>(), slots.herbGardenDisciples)
        assertEquals(emptyList<DirectDiscipleSlot>(), slots.alchemyDisciples)
        assertEquals(emptyList<DirectDiscipleSlot>(), slots.forgeDisciples)
        assertEquals(emptyList<DirectDiscipleSlot>(), slots.herbGardenReserveDisciples)
        assertEquals(emptyList<DirectDiscipleSlot>(), slots.alchemyReserveDisciples)
        assertEquals(emptyList<DirectDiscipleSlot>(), slots.forgeReserveDisciples)
        assertEquals(emptyList<DirectDiscipleSlot>(), slots.spiritMineDeaconDisciples)
    }

    @Test
    fun elderSlots_copy() {
        val original = ElderSlots(viceSectMaster = "d1")
        val copied = original.copy(viceSectMaster = "d2")
        assertEquals("d2", copied.viceSectMaster)
    }

    // ---- EquipmentStats ----

    @Test
    fun equipmentStats_defaultConstruction() {
        val stats = EquipmentStats()
        assertEquals(0, stats.physicalAttack)
        assertEquals(0, stats.magicAttack)
        assertEquals(0, stats.physicalDefense)
        assertEquals(0, stats.magicDefense)
        assertEquals(0, stats.speed)
        assertEquals(0, stats.hp)
        assertEquals(0, stats.mp)
    }

    @Test
    fun equipmentStats_plus() {
        val a = EquipmentStats(physicalAttack = 10, hp = 100)
        val b = EquipmentStats(physicalAttack = 5, hp = 50)
        val result = a + b
        assertEquals(15, result.physicalAttack)
        assertEquals(150, result.hp)
    }
}
