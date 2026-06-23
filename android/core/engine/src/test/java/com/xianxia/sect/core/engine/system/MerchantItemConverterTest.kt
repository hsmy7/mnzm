package com.xianxia.sect.core.engine.system

import com.xianxia.sect.core.model.*
import com.xianxia.sect.core.registry.BeastMaterialDatabase
import com.xianxia.sect.core.registry.EquipmentDatabase
import com.xianxia.sect.core.registry.HerbDatabase
import com.xianxia.sect.core.registry.ManualDatabase
import com.xianxia.sect.core.registry.PillRecipeDatabase
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class MerchantItemConverterTest {

    @Before
    fun setUp() {
        if (!ManualDatabase.isInitialized) {
            val template = ManualDatabase.ManualTemplate(
                id = "testManual1",
                name = "测试功法",
                type = ManualType.ATTACK,
                rarity = 1,
                description = "测试用功法"
            )
            ManualDatabase.initializeWithManuals(mapOf("testManual1" to template))
        }
    }

    // 1. toEquipment with known item name returns EquipmentStack with correct name and rarity
    @Test
    fun toEquipment_withKnownName_returnsEquipmentStackWithCorrectNameAndRarity() {
        val knownTemplate = EquipmentDatabase.allTemplates.values.first()
        val item = MerchantItem(name = knownTemplate.name, type = "equipment", rarity = 2)
        val result = MerchantItemConverter.toEquipment(item)
        assertEquals(knownTemplate.name, result.name)
        assertEquals(2, result.rarity)
    }

    // 2. toEquipment with unknown name generates random equipment with correct rarity
    @Test
    fun toEquipment_withUnknownName_generatesRandomEquipmentWithCorrectRarity() {
        val item = MerchantItem(name = "不存在的装备xyz", type = "equipment", rarity = 3)
        val result = MerchantItemConverter.toEquipment(item)
        assertEquals(3, result.rarity)
        assertTrue(result.name.isNotBlank())
    }

    // 3. toEquipmentBatch returns EquipmentStack with correct quantity
    @Test
    fun toEquipmentBatch_returnsEquipmentStackWithCorrectQuantity() {
        val knownTemplate = EquipmentDatabase.allTemplates.values.first()
        val item = MerchantItem(name = knownTemplate.name, type = "equipment", rarity = 1)
        val result = MerchantItemConverter.toEquipmentBatch(item, 5)
        assertEquals(5, result.quantity)
        assertEquals(knownTemplate.name, result.name)
    }

    // 4. toManual with known name returns ManualStack with correct fields
    @Test
    fun toManual_withKnownName_returnsManualStackWithCorrectFields() {
        val knownName = "测试功法"
        val item = MerchantItem(name = knownName, type = "manual", rarity = 2)
        val result = MerchantItemConverter.toManual(item)
        assertEquals(knownName, result.name)
        assertEquals(2, result.rarity)
        assertEquals(ManualType.ATTACK, result.type)
    }

    // 5. toManual with unknown name generates random manual
    @Test
    fun toManual_withUnknownName_generatesRandomManual() {
        val item = MerchantItem(name = "不存在的功法xyz", type = "manual", rarity = 1)
        val result = MerchantItemConverter.toManual(item)
        assertEquals(1, result.rarity)
        assertTrue(result.name.isNotBlank())
    }

    // 6. toPill with known name returns Pill with correct fields
    @Test
    fun toPill_withKnownName_returnsPillWithCorrectFields() {
        val knownRecipe = PillRecipeDatabase.getAllRecipes().first()
        val item = MerchantItem(name = knownRecipe.name, type = "pill", rarity = knownRecipe.rarity)
        val result = MerchantItemConverter.toPill(item)
        assertEquals(knownRecipe.name, result.name)
        assertEquals(knownRecipe.rarity, result.rarity)
    }

    // 7. toPill with known name sets pillType from recipe
    @Test
    fun toPill_withKnownName_setsPillTypeFromRecipe() {
        val knownRecipe = PillRecipeDatabase.getAllRecipes().first()
        val item = MerchantItem(
            name = knownRecipe.name,
            type = "pill",
            rarity = knownRecipe.rarity
        )
        val result = MerchantItemConverter.toPill(item)
        assertEquals(knownRecipe.pillType, result.pillType)
    }

    // 8. toPill with unknown name generates random pill
    @Test
    fun toPill_withUnknownName_generatesRandomPill() {
        val item = MerchantItem(name = "不存在的丹药xyz", type = "pill", rarity = 2)
        val result = MerchantItemConverter.toPill(item)
        assertTrue(result.name.isNotBlank())
    }

    // 8. toMaterial with known name returns Material with correct fields
    @Test
    fun toMaterial_withKnownName_returnsMaterialWithCorrectFields() {
        val knownMaterial = BeastMaterialDatabase.getAllMaterials().first()
        val item = MerchantItem(name = knownMaterial.name, type = "material", rarity = knownMaterial.rarity)
        val result = MerchantItemConverter.toMaterial(item)
        assertEquals(knownMaterial.name, result.name)
        assertEquals(knownMaterial.rarity, result.rarity)
    }

    // 9. toMaterial with unknown name generates random material
    @Test
    fun toMaterial_withUnknownName_generatesRandomMaterial() {
        val item = MerchantItem(name = "不存在的材料xyz", type = "material", rarity = 1)
        val result = MerchantItemConverter.toMaterial(item)
        assertTrue(result.name.isNotBlank())
    }

    // 10. toHerb with known name returns Herb with correct fields
    @Test
    fun toHerb_withKnownName_returnsHerbWithCorrectFields() {
        val knownHerb = HerbDatabase.getAllHerbs().first()
        val item = MerchantItem(name = knownHerb.name, type = "herb", rarity = knownHerb.rarity)
        val result = MerchantItemConverter.toHerb(item)
        assertEquals(knownHerb.name, result.name)
        assertEquals(knownHerb.rarity, result.rarity)
    }

    // 11. toHerb with unknown name generates random herb
    @Test
    fun toHerb_withUnknownName_generatesRandomHerb() {
        val item = MerchantItem(name = "不存在的灵草xyz", type = "herb", rarity = 1)
        val result = MerchantItemConverter.toHerb(item)
        assertTrue(result.name.isNotBlank())
    }

    // 12. toSeed with known name returns Seed with correct fields
    @Test
    fun toSeed_withKnownName_returnsSeedWithCorrectFields() {
        val knownSeed = HerbDatabase.getAllSeeds().first()
        val item = MerchantItem(name = knownSeed.name, type = "seed", rarity = knownSeed.rarity)
        val result = MerchantItemConverter.toSeed(item)
        assertEquals(knownSeed.name, result.name)
        assertEquals(knownSeed.rarity, result.rarity)
        assertEquals(knownSeed.growTime, result.growTime)
        assertEquals(knownSeed.yield, result.yield)
    }

    // 13. toSeed with unknown name generates random seed
    @Test
    fun toSeed_withUnknownName_generatesRandomSeed() {
        val item = MerchantItem(name = "不存在的种子xyz", type = "seed", rarity = 1)
        val result = MerchantItemConverter.toSeed(item)
        assertTrue(result.name.isNotBlank())
        assertTrue(result.growTime > 0)
        assertTrue(result.yield > 0)
    }

    // 14. getCapacityCheckParams for equipment type returns EquipmentParams
    @Test
    fun getCapacityCheckParams_equipmentType_returnsEquipmentParams() {
        val item = MerchantItem(name = "精铁剑", type = "equipment", rarity = 3)
        val result = MerchantItemConverter.getCapacityCheckParams(item)
        assertTrue(result is MerchantItemConverter.CapacityCheckParams.EquipmentParams)
        assertEquals(3, (result as MerchantItemConverter.CapacityCheckParams.EquipmentParams).rarity)
    }

    // 15. getCapacityCheckParams for manual type returns ManualParams
    @Test
    fun getCapacityCheckParams_manualType_returnsManualParams() {
        val item = MerchantItem(name = "测试功法", type = "manual", rarity = 1)
        val result = MerchantItemConverter.getCapacityCheckParams(item)
        assertTrue(result is MerchantItemConverter.CapacityCheckParams.ManualParams)
        val params = result as MerchantItemConverter.CapacityCheckParams.ManualParams
        assertEquals("测试功法", params.name)
        assertEquals(1, params.rarity)
    }

    // 16. getCapacityCheckParams for pill type returns PillParams
    @Test
    fun getCapacityCheckParams_pillType_returnsPillParams() {
        val item = MerchantItem(name = "慧根丹", type = "pill", rarity = 1)
        val result = MerchantItemConverter.getCapacityCheckParams(item)
        assertTrue(result is MerchantItemConverter.CapacityCheckParams.PillParams)
        val params = result as MerchantItemConverter.CapacityCheckParams.PillParams
        assertEquals("慧根丹", params.name)
        assertEquals(1, params.rarity)
    }

    // 17. getCapacityCheckParams for material type returns MaterialParams
    @Test
    fun getCapacityCheckParams_materialType_returnsMaterialParams() {
        val item = MerchantItem(name = "凡虎皮", type = "material", rarity = 1)
        val result = MerchantItemConverter.getCapacityCheckParams(item)
        assertTrue(result is MerchantItemConverter.CapacityCheckParams.MaterialParams)
        val params = result as MerchantItemConverter.CapacityCheckParams.MaterialParams
        assertEquals("凡虎皮", params.name)
        assertEquals(1, params.rarity)
    }

    // 18. getCapacityCheckParams for herb type returns HerbParams
    @Test
    fun getCapacityCheckParams_herbType_returnsHerbParams() {
        val item = MerchantItem(name = "聚灵草", type = "herb", rarity = 1)
        val result = MerchantItemConverter.getCapacityCheckParams(item)
        assertTrue(result is MerchantItemConverter.CapacityCheckParams.HerbParams)
        val params = result as MerchantItemConverter.CapacityCheckParams.HerbParams
        assertEquals("聚灵草", params.name)
        assertEquals(1, params.rarity)
    }

    // 19. getCapacityCheckParams for seed type returns SeedParams
    @Test
    fun getCapacityCheckParams_seedType_returnsSeedParams() {
        val item = MerchantItem(name = "聚灵草种", type = "seed", rarity = 1)
        val result = MerchantItemConverter.getCapacityCheckParams(item)
        assertTrue(result is MerchantItemConverter.CapacityCheckParams.SeedParams)
        val params = result as MerchantItemConverter.CapacityCheckParams.SeedParams
        assertEquals("聚灵草种", params.name)
        assertEquals(1, params.rarity)
    }

    // 20. getCapacityCheckParams for unknown type returns Unknown
    @Test
    fun getCapacityCheckParams_unknownType_returnsUnknown() {
        val item = MerchantItem(name = "某物", type = "unknown_type", rarity = 1)
        val result = MerchantItemConverter.getCapacityCheckParams(item)
        assertTrue(result is MerchantItemConverter.CapacityCheckParams.Unknown)
    }

    // 21. All generated items have non-blank id (UUID)
    @Test
    fun allGeneratedItems_haveNonBlankId() {
        val unknownEquip = MerchantItem(name = "不存在的装备", type = "equipment", rarity = 1)
        val unknownManual = MerchantItem(name = "不存在的功法", type = "manual", rarity = 1)
        val unknownPill = MerchantItem(name = "不存在的丹药", type = "pill", rarity = 1)
        val unknownMaterial = MerchantItem(name = "不存在的材料", type = "material", rarity = 1)
        val unknownHerb = MerchantItem(name = "不存在的灵草", type = "herb", rarity = 1)
        val unknownSeed = MerchantItem(name = "不存在的种子", type = "seed", rarity = 1)

        assertTrue("equipment id should not be blank", MerchantItemConverter.toEquipment(unknownEquip).id.isNotBlank())
        assertTrue("manual id should not be blank", MerchantItemConverter.toManual(unknownManual).id.isNotBlank())
        assertTrue("pill id should not be blank", MerchantItemConverter.toPill(unknownPill).id.isNotBlank())
        assertTrue("material id should not be blank", MerchantItemConverter.toMaterial(unknownMaterial).id.isNotBlank())
        assertTrue("herb id should not be blank", MerchantItemConverter.toHerb(unknownHerb).id.isNotBlank())
        assertTrue("seed id should not be blank", MerchantItemConverter.toSeed(unknownSeed).id.isNotBlank())

        // Also check known items
        val knownEquip = MerchantItem(name = EquipmentDatabase.allTemplates.values.first().name, type = "equipment", rarity = 1)
        assertTrue("known equipment id should not be blank", MerchantItemConverter.toEquipment(knownEquip).id.isNotBlank())

        val knownManual = MerchantItem(name = "测试功法", type = "manual", rarity = 1)
        assertTrue("known manual id should not be blank", MerchantItemConverter.toManual(knownManual).id.isNotBlank())

        val knownHerb = MerchantItem(name = HerbDatabase.getAllHerbs().first().name, type = "herb", rarity = 1)
        assertTrue("known herb id should not be blank", MerchantItemConverter.toHerb(knownHerb).id.isNotBlank())

        val knownSeed = MerchantItem(name = HerbDatabase.getAllSeeds().first().name, type = "seed", rarity = 1)
        assertTrue("known seed id should not be blank", MerchantItemConverter.toSeed(knownSeed).id.isNotBlank())
    }
}
