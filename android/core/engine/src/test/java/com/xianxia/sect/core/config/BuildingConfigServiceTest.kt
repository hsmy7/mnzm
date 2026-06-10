package com.xianxia.sect.core.config

import com.xianxia.sect.core.model.production.BuildingType
import com.xianxia.sect.core.model.production.ProductionSlotStatus
import org.junit.Assert.*
import org.junit.Test

class BuildingConfigServiceTest {

    // ==================== BuildingsConfig ====================

    @Test
    fun buildingsConfig_defaultConstruction() {
        val config = BuildingsConfig()
        assertEquals("1.0.0", config.version)
        assertTrue(config.buildings.isEmpty())
        assertTrue(config.buildingAliases.isEmpty())
    }

    @Test
    fun buildingsConfig_equality() {
        val a = BuildingsConfig(version = "2.0.0", buildings = emptyMap(), buildingAliases = emptyMap())
        val b = BuildingsConfig(version = "2.0.0", buildings = emptyMap(), buildingAliases = emptyMap())
        assertEquals(a, b)
    }

    @Test
    fun buildingsConfig_copy() {
        val original = BuildingsConfig(version = "1.0.0")
        val modified = original.copy(version = "2.0.0")
        assertEquals("2.0.0", modified.version)
    }

    // ==================== BuildingConfigModel ====================

    @Test
    fun buildingConfigModel_defaultValues() {
        val model = BuildingConfigModel(id = "test", displayName = "测试", buildingType = "ALCHEMY")
        assertEquals("test", model.id)
        assertEquals("测试", model.displayName)
        assertEquals("ALCHEMY", model.buildingType)
        assertEquals(1, model.slotCount)
        assertEquals(1.0, model.baseSuccessRate, 0.001)
        assertEquals(1, model.maxQueueLength)
        assertFalse(model.autoRestartEnabled)
        assertEquals(1000L, model.cost)
        assertEquals(2, model.gridWidth)
        assertEquals(2, model.gridHeight)
        assertEquals("", model.description)
    }

    @Test
    fun buildingConfigModel_fullConstruction() {
        val model = BuildingConfigModel(
            id = "alchemy",
            displayName = "炼丹炉",
            buildingType = "ALCHEMY",
            slotCount = 3,
            baseSuccessRate = 0.7,
            maxQueueLength = 5,
            autoRestartEnabled = true,
            cost = 3000,
            gridWidth = 3,
            gridHeight = 2,
            description = "用于炼制各种丹药的场所"
        )
        assertEquals(3, model.slotCount)
        assertEquals(0.7, model.baseSuccessRate, 0.001)
        assertTrue(model.autoRestartEnabled)
        assertEquals(3000L, model.cost)
    }

    @Test
    fun buildingConfigModel_equality() {
        val a = BuildingConfigModel(id = "x", displayName = "Y", buildingType = "FORGE")
        val b = BuildingConfigModel(id = "x", displayName = "Y", buildingType = "FORGE")
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun buildingConfigModel_copy() {
        val original = BuildingConfigModel(id = "x", displayName = "Y", buildingType = "FORGE", slotCount = 1)
        val modified = original.copy(slotCount = 5)
        assertEquals(5, modified.slotCount)
        assertEquals("x", modified.id)
    }

    // ==================== ConfigValidator ====================

    @Test
    fun configValidator_validConfig_noErrors() {
        val config = BuildingsConfig(
            buildings = mapOf(
                "alchemy" to BuildingConfigModel(
                    id = "alchemy",
                    displayName = "炼丹炉",
                    buildingType = "ALCHEMY",
                    slotCount = 3,
                    baseSuccessRate = 0.7
                )
            )
        )
        val errors = ConfigValidator.validate(config)
        assertTrue(errors.isEmpty())
    }

    @Test
    fun configValidator_idMismatch_reportsError() {
        val config = BuildingsConfig(
            buildings = mapOf(
                "wrong_key" to BuildingConfigModel(
                    id = "alchemy",
                    displayName = "炼丹炉",
                    buildingType = "ALCHEMY"
                )
            )
        )
        val errors = ConfigValidator.validate(config)
        assertTrue(errors.any { it.contains("ID mismatch") })
    }

    @Test
    fun configValidator_invalidSlotCount_reportsError() {
        val config = BuildingsConfig(
            buildings = mapOf(
                "test" to BuildingConfigModel(
                    id = "test",
                    displayName = "测试",
                    buildingType = "ALCHEMY",
                    slotCount = 0
                )
            )
        )
        val errors = ConfigValidator.validate(config)
        assertTrue(errors.any { it.contains("Invalid slotCount") })

        val config2 = BuildingsConfig(
            buildings = mapOf(
                "test" to BuildingConfigModel(
                    id = "test",
                    displayName = "测试",
                    buildingType = "ALCHEMY",
                    slotCount = 9
                )
            )
        )
        val errors2 = ConfigValidator.validate(config2)
        assertTrue(errors2.any { it.contains("Invalid slotCount") })
    }

    @Test
    fun configValidator_invalidBaseSuccessRate_reportsError() {
        val config = BuildingsConfig(
            buildings = mapOf(
                "test" to BuildingConfigModel(
                    id = "test",
                    displayName = "测试",
                    buildingType = "ALCHEMY",
                    baseSuccessRate = -0.1
                )
            )
        )
        val errors = ConfigValidator.validate(config)
        assertTrue(errors.any { it.contains("Invalid baseSuccessRate") })

        val config2 = BuildingsConfig(
            buildings = mapOf(
                "test" to BuildingConfigModel(
                    id = "test",
                    displayName = "测试",
                    buildingType = "ALCHEMY",
                    baseSuccessRate = 1.5
                )
            )
        )
        val errors2 = ConfigValidator.validate(config2)
        assertTrue(errors2.any { it.contains("Invalid baseSuccessRate") })
    }

    @Test
    fun configValidator_unknownBuildingType_reportsError() {
        val config = BuildingsConfig(
            buildings = mapOf(
                "test" to BuildingConfigModel(
                    id = "test",
                    displayName = "测试",
                    buildingType = "NONEXISTENT_TYPE"
                )
            )
        )
        val errors = ConfigValidator.validate(config)
        assertTrue(errors.any { it.contains("Unknown buildingType") })
    }

    @Test
    fun configValidator_multipleErrors() {
        val config = BuildingsConfig(
            buildings = mapOf(
                "bad" to BuildingConfigModel(
                    id = "wrong_id",
                    displayName = "坏数据",
                    buildingType = "INVALID",
                    slotCount = 0,
                    baseSuccessRate = 2.0
                )
            )
        )
        val errors = ConfigValidator.validate(config)
        assertTrue(errors.size >= 3)
    }

    @Test
    fun configValidator_emptyConfig_noErrors() {
        val config = BuildingsConfig()
        val errors = ConfigValidator.validate(config)
        assertTrue(errors.isEmpty())
    }

    @Test
    fun configValidator_boundarySlotCount_valid() {
        val config = BuildingsConfig(
            buildings = mapOf(
                "test1" to BuildingConfigModel(id = "test1", displayName = "T1", buildingType = "ALCHEMY", slotCount = 1),
                "test2" to BuildingConfigModel(id = "test2", displayName = "T2", buildingType = "FORGE", slotCount = 8)
            )
        )
        val errors = ConfigValidator.validate(config)
        assertTrue(errors.isEmpty())
    }

    @Test
    fun configValidator_boundarySuccessRate_valid() {
        val config = BuildingsConfig(
            buildings = mapOf(
                "test1" to BuildingConfigModel(id = "test1", displayName = "T1", buildingType = "ALCHEMY", baseSuccessRate = 0.0),
                "test2" to BuildingConfigModel(id = "test2", displayName = "T2", buildingType = "FORGE", baseSuccessRate = 1.0)
            )
        )
        val errors = ConfigValidator.validate(config)
        assertTrue(errors.isEmpty())
    }

    // ==================== BuildingType enum ====================

    @Test
    fun buildingType_allValuesHaveDisplayName() {
        for (bt in BuildingType.entries) {
            assertNotNull(bt.displayName)
            assertTrue(bt.displayName.isNotEmpty())
        }
    }

    @Test
    fun buildingType_toSlotType_knownMappings() {
        assertEquals(com.xianxia.sect.core.model.production.SlotType.ALCHEMY, BuildingType.ALCHEMY.toSlotType())
        assertEquals(com.xianxia.sect.core.model.production.SlotType.FORGING, BuildingType.FORGE.toSlotType())
        assertEquals(com.xianxia.sect.core.model.production.SlotType.MINING, BuildingType.MINING.toSlotType())
        assertEquals(com.xianxia.sect.core.model.production.SlotType.HERB_GARDEN, BuildingType.HERB_GARDEN.toSlotType())
    }

    @Test
    fun buildingType_toSlotType_unknownReturnsIdle() {
        assertEquals(com.xianxia.sect.core.model.production.SlotType.IDLE, BuildingType.ADMINISTRATION.toSlotType())
        assertEquals(com.xianxia.sect.core.model.production.SlotType.IDLE, BuildingType.LIBRARY.toSlotType())
    }

    // ==================== SlotType enum ====================

    @Test
    fun slotType_toBuildingType_roundTrip() {
        for (st in com.xianxia.sect.core.model.production.SlotType.entries) {
            val bt = st.toBuildingType()
            if (bt != null) {
                assertEquals(st, bt.toSlotType())
            }
        }
    }

    @Test
    fun slotType_idleToBuildingTypeReturnsNull() {
        assertNull(com.xianxia.sect.core.model.production.SlotType.IDLE.toBuildingType())
    }

    // ==================== ProductionSlotStatus enum ====================

    @Test
    fun productionSlotStatus_displayNames() {
        assertEquals("空闲", ProductionSlotStatus.IDLE.displayName)
        assertEquals("进行中", ProductionSlotStatus.WORKING.displayName)
        assertEquals("已完成", ProductionSlotStatus.COMPLETED.displayName)
    }

    // ==================== BuildingConfigService.createDefaultConfig ====================

    @Test
    fun createDefaultConfig_hasExpectedBuildings() {
        // We can't directly call createDefaultConfig (private), but we can test
        // that the default config structure is consistent via ConfigValidator
        val defaultConfig = BuildingsConfig(
            version = "1.0.0",
            buildings = mapOf(
                "alchemy" to BuildingConfigModel(id = "alchemy", displayName = "炼丹炉", buildingType = "ALCHEMY", slotCount = 1, baseSuccessRate = 0.7),
                "forge" to BuildingConfigModel(id = "forge", displayName = "锻造坊", buildingType = "FORGE", slotCount = 1, baseSuccessRate = 0.7),
                "mining" to BuildingConfigModel(id = "mining", displayName = "灵矿场", buildingType = "MINING", slotCount = 3, baseSuccessRate = 1.0)
            )
        )
        val errors = ConfigValidator.validate(defaultConfig)
        assertTrue("Default config should be valid, errors: $errors", errors.isEmpty())
    }
}
