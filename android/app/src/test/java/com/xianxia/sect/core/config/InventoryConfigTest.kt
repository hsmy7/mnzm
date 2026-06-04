package com.xianxia.sect.core.config

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class InventoryConfigTest {

    private lateinit var config: InventoryConfig

    @Before
    fun setUp() {
        config = InventoryConfig()
    }

    // ========== 1. 默认值 ==========

    @Test
    fun defaultMaxInventorySize() {
        assertEquals(2000, config.maxInventorySize)
    }

    @Test
    fun defaultMaxWarehouseSize() {
        assertEquals(5000, config.maxWarehouseSize)
    }

    @Test
    fun defaultMaxStackSize() {
        assertEquals(9999, config.maxStackSize)
    }

    @Test
    fun defaultCacheSize() {
        assertEquals(500, config.cacheSize)
    }

    @Test
    fun defaultCacheTtlMs() {
        assertEquals(5 * 60 * 1000L, config.cacheTtlMs)
    }

    @Test
    fun defaultTransactionTimeoutMs() {
        assertEquals(5000L, config.transactionTimeoutMs)
    }

    @Test
    fun defaultMaxRetryAttempts() {
        assertEquals(3, config.maxRetryAttempts)
    }

    @Test
    fun defaultEnableCompression() {
        assertTrue(config.enableCompression)
    }

    @Test
    fun defaultEnableObjectPool() {
        assertTrue(config.enableObjectPool)
    }

    @Test
    fun defaultEnableCache() {
        assertTrue(config.enableCache)
    }

    @Test
    fun defaultEnableMonitoring() {
        assertTrue(config.enableMonitoring)
    }

    // ========== 2. 类型特定堆叠限制 ==========

    @Test
    fun typeSpecificStackLimit_pill() {
        assertEquals(999, config.getMaxStackSize("pill"))
    }

    @Test
    fun typeSpecificStackLimit_material() {
        assertEquals(9999, config.getMaxStackSize("material"))
    }

    @Test
    fun typeSpecificStackLimit_herb() {
        assertEquals(9999, config.getMaxStackSize("herb"))
    }

    @Test
    fun typeSpecificStackLimit_seed() {
        assertEquals(9999, config.getMaxStackSize("seed"))
    }

    @Test
    fun typeSpecificStackLimit_manual_stack() {
        assertEquals(999, config.getMaxStackSize("manual_stack"))
    }

    @Test
    fun typeSpecificStackLimit_manual_instance() {
        assertEquals(1, config.getMaxStackSize("manual_instance"))
    }

    @Test
    fun typeSpecificStackLimit_equipment_stack() {
        assertEquals(999, config.getMaxStackSize("equipment_stack"))
    }

    @Test
    fun typeSpecificStackLimit_equipment_instance() {
        assertEquals(1, config.getMaxStackSize("equipment_instance"))
    }

    // ========== 3. getMaxStackSize ==========

    @Test
    fun getMaxStackSize_knownType() {
        assertEquals(999, config.getMaxStackSize("pill"))
    }

    @Test
    fun getMaxStackSize_unknownType_returnsGlobalMax() {
        assertEquals(9999, config.getMaxStackSize("unknown_type"))
    }

    @Test
    fun getMaxStackSize_caseInsensitive() {
        assertEquals(999, config.getMaxStackSize("PILL"))
        assertEquals(999, config.getMaxStackSize("Pill"))
        assertEquals(9999, config.getMaxStackSize("MATERIAL"))
        assertEquals(9999, config.getMaxStackSize("Herb"))
    }

    // ========== 4. getMaxCapacity ==========

    @Test
    fun getMaxCapacity_unknownType_returnsMaxInventorySize() {
        assertEquals(2000, config.getMaxCapacity("anything"))
    }

    @Test
    fun getMaxCapacity_afterSetMaxCapacity() {
        config.setMaxCapacity("custom", 100)
        assertEquals(100, config.getMaxCapacity("custom"))
    }

    @Test
    fun getMaxCapacity_caseInsensitive() {
        config.setMaxCapacity("MyType", 300)
        assertEquals(300, config.getMaxCapacity("mytype"))
        assertEquals(300, config.getMaxCapacity("MYTYPE"))
    }

    // ========== 5. setMaxStackSize ==========

    @Test
    fun setMaxStackSize_validValue() {
        config.setMaxStackSize("custom", 500)
        assertEquals(500, config.getMaxStackSize("custom"))
    }

    @Test
    fun setMaxStackSize_overwritesExisting() {
        config.setMaxStackSize("pill", 100)
        assertEquals(100, config.getMaxStackSize("pill"))
    }

    @Test
    fun setMaxStackSize_zeroValue_ignored() {
        val original = config.getMaxStackSize("pill")
        config.setMaxStackSize("pill", 0)
        assertEquals(original, config.getMaxStackSize("pill"))
    }

    @Test
    fun setMaxStackSize_negativeValue_ignored() {
        val original = config.getMaxStackSize("pill")
        config.setMaxStackSize("pill", -10)
        assertEquals(original, config.getMaxStackSize("pill"))
    }

    @Test
    fun setMaxStackSize_newTypeWithZero_ignored() {
        config.setMaxStackSize("newtype", 0)
        assertEquals(9999, config.getMaxStackSize("newtype"))
    }

    @Test
    fun setMaxStackSize_newTypeWithNegative_ignored() {
        config.setMaxStackSize("newtype", -5)
        assertEquals(9999, config.getMaxStackSize("newtype"))
    }

    // ========== 6. setMaxCapacity ==========

    @Test
    fun setMaxCapacity_validValue() {
        config.setMaxCapacity("custom", 800)
        assertEquals(800, config.getMaxCapacity("custom"))
    }

    @Test
    fun setMaxCapacity_zeroValue_ignored() {
        config.setMaxCapacity("custom", 0)
        assertEquals(2000, config.getMaxCapacity("custom"))
    }

    @Test
    fun setMaxCapacity_negativeValue_ignored() {
        config.setMaxCapacity("custom", -100)
        assertEquals(2000, config.getMaxCapacity("custom"))
    }

    @Test
    fun setMaxCapacity_validOne() {
        config.setMaxCapacity("custom", 1)
        assertEquals(1, config.getMaxCapacity("custom"))
    }

    // ========== 7. validateQuantity ==========

    @Test
    fun validateQuantity_negative_returnsFailure() {
        val result = config.validateQuantity("pill", -1)
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()!!.message!!.contains("negative"))
    }

    @Test
    fun validateQuantity_exceedsMax_returnsFailure() {
        val result = config.validateQuantity("pill", 1000)
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()!!.message!!.contains("exceeds"))
    }

    @Test
    fun validateQuantity_valid_returnsSuccess() {
        val result = config.validateQuantity("pill", 500)
        assertTrue(result.isSuccess)
        assertEquals(500, result.getOrNull())
    }

    @Test
    fun validateQuantity_atMax_returnsSuccess() {
        val result = config.validateQuantity("pill", 999)
        assertTrue(result.isSuccess)
        assertEquals(999, result.getOrNull())
    }

    @Test
    fun validateQuantity_zero_returnsSuccess() {
        val result = config.validateQuantity("pill", 0)
        assertTrue(result.isSuccess)
        assertEquals(0, result.getOrNull())
    }

    @Test
    fun validateQuantity_unknownType_usesGlobalMax() {
        val result = config.validateQuantity("unknown", 9999)
        assertTrue(result.isSuccess)
        assertEquals(9999, result.getOrNull())
    }

    @Test
    fun validateQuantity_unknownType_exceedsGlobalMax_returnsFailure() {
        val result = config.validateQuantity("unknown", 10000)
        assertTrue(result.isFailure)
    }

    @Test
    fun validateQuantity_equipment_instance_exceedsOne_returnsFailure() {
        val result = config.validateQuantity("equipment_instance", 2)
        assertTrue(result.isFailure)
    }

    // ========== 8. validateRarity ==========

    @Test
    fun validateRarity_valid_1() {
        val result = config.validateRarity(1)
        assertTrue(result.isSuccess)
        assertEquals(1, result.getOrNull())
    }

    @Test
    fun validateRarity_valid_6() {
        val result = config.validateRarity(6)
        assertTrue(result.isSuccess)
        assertEquals(6, result.getOrNull())
    }

    @Test
    fun validateRarity_valid_3() {
        val result = config.validateRarity(3)
        assertTrue(result.isSuccess)
        assertEquals(3, result.getOrNull())
    }

    @Test
    fun validateRarity_invalid_0() {
        val result = config.validateRarity(0)
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()!!.message!!.contains("Invalid rarity"))
    }

    @Test
    fun validateRarity_invalid_7() {
        val result = config.validateRarity(7)
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()!!.message!!.contains("Invalid rarity"))
    }

    @Test
    fun validateRarity_invalid_negative() {
        val result = config.validateRarity(-1)
        assertTrue(result.isFailure)
    }

    // ========== 9. isValidRarity ==========

    @Test
    fun isValidRarity_1_to_6() {
        for (r in 1..6) {
            assertTrue("rarity $r should be valid", config.isValidRarity(r))
        }
    }

    @Test
    fun isValidRarity_0_isFalse() {
        assertFalse(config.isValidRarity(0))
    }

    @Test
    fun isValidRarity_7_isFalse() {
        assertFalse(config.isValidRarity(7))
    }

    @Test
    fun isValidRarity_negative_isFalse() {
        assertFalse(config.isValidRarity(-1))
    }

    // ========== 10. getRarityRange ==========

    @Test
    fun getRarityRange_returns1To6() {
        val range = config.getRarityRange()
        assertEquals(1, range.first)
        assertEquals(6, range.last)
        assertEquals(1..6, range)
    }

    // ========== 11. toMap / fromMap 往返 ==========

    @Test
    fun toMap_containsAllFields() {
        val map = config.toMap()
        assertEquals(2000, map["maxInventorySize"])
        assertEquals(5000, map["maxWarehouseSize"])
        assertEquals(9999, map["maxStackSize"])
        assertEquals(500, map["cacheSize"])
        assertEquals(5 * 60 * 1000L, map["cacheTtlMs"])
        assertEquals(5000L, map["transactionTimeoutMs"])
        assertEquals(3, map["maxRetryAttempts"])
        assertEquals(true, map["enableCompression"])
        assertEquals(true, map["enableObjectPool"])
        assertEquals(true, map["enableCache"])
        assertEquals(true, map["enableMonitoring"])
    }

    @Test
    fun toMap_containsTypeSpecificStackLimits() {
        val map = config.toMap()
        @Suppress("UNCHECKED_CAST")
        val limits = map["typeSpecificStackLimits"] as Map<String, Int>
        assertEquals(999, limits["pill"])
        assertEquals(9999, limits["material"])
        assertEquals(9999, limits["herb"])
        assertEquals(9999, limits["seed"])
        assertEquals(999, limits["manual_stack"])
        assertEquals(1, limits["manual_instance"])
        assertEquals(999, limits["equipment_stack"])
        assertEquals(1, limits["equipment_instance"])
    }

    @Test
    fun fromMap_restoresAllScalarFields() {
        val map = mapOf(
            "maxInventorySize" to 100,
            "maxWarehouseSize" to 200,
            "maxStackSize" to 500,
            "cacheSize" to 50,
            "cacheTtlMs" to 1000L,
            "transactionTimeoutMs" to 2000L,
            "maxRetryAttempts" to 5,
            "enableCompression" to false,
            "enableObjectPool" to false,
            "enableCache" to false,
            "enableMonitoring" to false
        )
        config.fromMap(map)
        assertEquals(100, config.maxInventorySize)
        assertEquals(200, config.maxWarehouseSize)
        assertEquals(500, config.maxStackSize)
        assertEquals(50, config.cacheSize)
        assertEquals(1000L, config.cacheTtlMs)
        assertEquals(2000L, config.transactionTimeoutMs)
        assertEquals(5, config.maxRetryAttempts)
        assertFalse(config.enableCompression)
        assertFalse(config.enableObjectPool)
        assertFalse(config.enableCache)
        assertFalse(config.enableMonitoring)
    }

    @Test
    fun fromMap_restoresTypeSpecificLimits() {
        val map = mapOf(
            "typeSpecificStackLimits" to mapOf("pill" to 100, "custom" to 50),
            "typeSpecificCapacityLimits" to mapOf("vault" to 999)
        )
        config.fromMap(map)
        assertEquals(100, config.getMaxStackSize("pill"))
        assertEquals(50, config.getMaxStackSize("custom"))
        assertEquals(999, config.getMaxCapacity("vault"))
    }

    @Test
    fun toMapFromMap_roundtrip() {
        config.setMaxStackSize("custom_stack", 777)
        config.setMaxCapacity("custom_cap", 888)
        config.maxInventorySize = 111
        config.enableCompression = false

        val map = config.toMap()

        val restored = InventoryConfig()
        restored.fromMap(map)

        assertEquals(111, restored.maxInventorySize)
        assertFalse(restored.enableCompression)
        assertEquals(777, restored.getMaxStackSize("custom_stack"))
        assertEquals(888, restored.getMaxCapacity("custom_cap"))
        // 原有默认限制仍在
        assertEquals(999, restored.getMaxStackSize("pill"))
        assertEquals(9999, restored.getMaxStackSize("material"))
    }

    @Test
    fun fromMap_partialUpdate_onlyOverwritesProvidedFields() {
        config.maxInventorySize = 999
        config.fromMap(mapOf("maxWarehouseSize" to 300))
        assertEquals(999, config.maxInventorySize)
        assertEquals(300, config.maxWarehouseSize)
    }

    // ========== 12. reset ==========

    @Test
    fun reset_restoresAllDefaultValues() {
        config.maxInventorySize = 1
        config.maxWarehouseSize = 2
        config.maxStackSize = 3
        config.cacheSize = 4
        config.cacheTtlMs = 10L
        config.transactionTimeoutMs = 20L
        config.maxRetryAttempts = 99
        config.enableCompression = false
        config.enableObjectPool = false
        config.enableCache = false
        config.enableMonitoring = false

        config.reset()

        assertEquals(2000, config.maxInventorySize)
        assertEquals(5000, config.maxWarehouseSize)
        assertEquals(9999, config.maxStackSize)
        assertEquals(500, config.cacheSize)
        assertEquals(5 * 60 * 1000L, config.cacheTtlMs)
        assertEquals(5000L, config.transactionTimeoutMs)
        assertEquals(3, config.maxRetryAttempts)
        assertTrue(config.enableCompression)
        assertTrue(config.enableObjectPool)
        assertTrue(config.enableCache)
        assertTrue(config.enableMonitoring)
    }

    @Test
    fun reset_restoresTypeSpecificStackLimits() {
        config.setMaxStackSize("pill", 1)
        config.setMaxStackSize("custom", 9999)
        config.setMaxCapacity("custom", 500)

        config.reset()

        assertEquals(999, config.getMaxStackSize("pill"))
        assertEquals(9999, config.getMaxStackSize("material"))
        assertEquals(9999, config.getMaxStackSize("herb"))
        assertEquals(9999, config.getMaxStackSize("seed"))
        assertEquals(999, config.getMaxStackSize("manual_stack"))
        assertEquals(1, config.getMaxStackSize("manual_instance"))
        assertEquals(999, config.getMaxStackSize("equipment_stack"))
        assertEquals(1, config.getMaxStackSize("equipment_instance"))
    }

    @Test
    fun reset_clearsCustomLimits() {
        config.setMaxStackSize("custom", 100)
        config.setMaxCapacity("custom", 200)

        config.reset()

        assertEquals(9999, config.getMaxStackSize("custom"))
        assertEquals(2000, config.getMaxCapacity("custom"))
    }

    // ========== companion object DEFAULT ==========

    @Test
    fun companionDefault_hasDefaultValues() {
        val default = InventoryConfig.DEFAULT
        assertEquals(2000, default.maxInventorySize)
        assertEquals(5000, default.maxWarehouseSize)
        assertEquals(9999, default.maxStackSize)
    }
}
