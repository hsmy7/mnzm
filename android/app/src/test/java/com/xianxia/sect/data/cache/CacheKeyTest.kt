package com.xianxia.sect.data.cache

import org.junit.Assert.*
import org.junit.Test

class CacheKeyTest {

    // ==================== Constructor ====================

    @Test
    fun `constructor sets all fields`() {
        val key = CacheKey("disciple", 1, "abc123")
        assertEquals("disciple", key.type)
        assertEquals(1, key.slot)
        assertEquals("abc123", key.id)
        assertEquals(CacheKey.DEFAULT_TTL, key.ttl)
    }

    @Test
    fun `constructor with custom TTL`() {
        val key = CacheKey("disciple", 1, "abc123", ttl = 5000L)
        assertEquals(5000L, key.ttl)
    }

    // ==================== DEFAULT_TTL ====================

    @Test
    fun `DEFAULT_TTL is 1 hour`() {
        assertEquals(3600_000L, CacheKey.DEFAULT_TTL)
    }

    // ==================== Type constants ====================

    @Test
    fun `TYPE constants are correct`() {
        assertEquals("disciple", CacheKey.TYPE_DISCIPLE)
        assertEquals("equipment", CacheKey.TYPE_EQUIPMENT)
        assertEquals("manual", CacheKey.TYPE_MANUAL)
        assertEquals("pill", CacheKey.TYPE_PILL)
        assertEquals("material", CacheKey.TYPE_MATERIAL)
        assertEquals("herb", CacheKey.TYPE_HERB)
        assertEquals("seed", CacheKey.TYPE_SEED)
        assertEquals("team", CacheKey.TYPE_TEAM)
        assertEquals("game_data", CacheKey.TYPE_GAME_DATA)
        assertEquals("building_slot", CacheKey.TYPE_BUILDING_SLOT)
        assertEquals("building", CacheKey.TYPE_BUILDING)
        assertEquals("event", CacheKey.TYPE_EVENT)
        assertEquals("battle_log", CacheKey.TYPE_BATTLE_LOG)
        assertEquals("alliance", CacheKey.TYPE_ALLIANCE)
        assertEquals("alchemy_slot", CacheKey.TYPE_ALCHEMY_SLOT)
    }

    // ==================== Factory methods ====================

    @Test
    fun `disciple creates key with correct type`() {
        val key = CacheKey.disciple(1, "d1")
        assertEquals(CacheKey.TYPE_DISCIPLE, key.type)
        assertEquals(1, key.slot)
        assertEquals("d1", key.id)
    }

    @Test
    fun `equipment creates key with correct type`() {
        val key = CacheKey.equipment(2, "e1")
        assertEquals(CacheKey.TYPE_EQUIPMENT, key.type)
        assertEquals(2, key.slot)
        assertEquals("e1", key.id)
    }

    @Test
    fun `manual creates key with correct type`() {
        val key = CacheKey.manual(1, "m1")
        assertEquals(CacheKey.TYPE_MANUAL, key.type)
    }

    @Test
    fun `pill creates key with correct type`() {
        val key = CacheKey.pill(1, "p1")
        assertEquals(CacheKey.TYPE_PILL, key.type)
    }

    @Test
    fun `material creates key with correct type`() {
        val key = CacheKey.material(1, "mat1")
        assertEquals(CacheKey.TYPE_MATERIAL, key.type)
    }

    @Test
    fun `herb creates key with correct type`() {
        val key = CacheKey.herb(1, "h1")
        assertEquals(CacheKey.TYPE_HERB, key.type)
    }

    @Test
    fun `seed creates key with correct type`() {
        val key = CacheKey.seed(1, "s1")
        assertEquals(CacheKey.TYPE_SEED, key.type)
    }

    @Test
    fun `team creates key with correct type`() {
        val key = CacheKey.team(1, "t1")
        assertEquals(CacheKey.TYPE_TEAM, key.type)
    }

    @Test
    fun `gameData creates key with correct type and id`() {
        val key = CacheKey.gameData(3)
        assertEquals(CacheKey.TYPE_GAME_DATA, key.type)
        assertEquals(3, key.slot)
        assertEquals("current", key.id)
    }

    @Test
    fun `buildingSlot creates key with correct type`() {
        val key = CacheKey.buildingSlot(1, "bs1")
        assertEquals(CacheKey.TYPE_BUILDING_SLOT, key.type)
    }

    @Test
    fun `building creates key with correct type`() {
        val key = CacheKey.building(1, "b1")
        assertEquals(CacheKey.TYPE_BUILDING, key.type)
    }

    @Test
    fun `event creates key with correct type`() {
        val key = CacheKey.event(1, "ev1")
        assertEquals(CacheKey.TYPE_EVENT, key.type)
    }

    @Test
    fun `battleLog creates key with correct type`() {
        val key = CacheKey.battleLog(1, "bl1")
        assertEquals(CacheKey.TYPE_BATTLE_LOG, key.type)
    }

    @Test
    fun `alliance creates key with correct type`() {
        val key = CacheKey.alliance(1, "a1")
        assertEquals(CacheKey.TYPE_ALLIANCE, key.type)
    }

    @Test
    fun `alchemySlot creates key with correct type`() {
        val key = CacheKey.alchemySlot(1, "as1")
        assertEquals(CacheKey.TYPE_ALCHEMY_SLOT, key.type)
    }

    // ==================== forXxx alias methods ====================

    @Test
    fun `forGameData is alias for gameData`() {
        val key = CacheKey.forGameData(5)
        assertEquals(CacheKey.TYPE_GAME_DATA, key.type)
        assertEquals(5, key.slot)
        assertEquals("current", key.id)
    }

    @Test
    fun `forDisciple is alias for disciple`() {
        val key = CacheKey.forDisciple(2, "d2")
        assertEquals(CacheKey.TYPE_DISCIPLE, key.type)
        assertEquals(2, key.slot)
        assertEquals("d2", key.id)
    }

    @Test
    fun `forEquipment is alias for equipment`() {
        val key = CacheKey.forEquipment(3, "e3")
        assertEquals(CacheKey.TYPE_EQUIPMENT, key.type)
    }

    // ==================== fromString ====================

    @Test
    fun `fromString parses 3-part key`() {
        val key = CacheKey.fromString("disciple:1:abc")
        assertEquals("disciple", key.type)
        assertEquals(1, key.slot)
        assertEquals("abc", key.id)
    }

    @Test
    fun `fromString parses 2-part key with default slot 0`() {
        val key = CacheKey.fromString("disciple:abc")
        assertEquals("disciple", key.type)
        assertEquals(0, key.slot)
        assertEquals("abc", key.id)
    }

    @Test
    fun `fromString parses single-part key`() {
        val key = CacheKey.fromString("raw_key")
        assertEquals("raw_key", key.type)
        assertEquals(0, key.slot)
        assertEquals("", key.id)
    }

    @Test
    fun `fromString handles non-numeric slot gracefully`() {
        val key = CacheKey.fromString("disciple:notanumber:abc")
        assertEquals("disciple", key.type)
        assertEquals(0, key.slot)
        assertEquals("abc", key.id)
    }

    // ==================== toString ====================

    @Test
    fun `toString formats as type-slot-id`() {
        val key = CacheKey("game_data", 3, "current")
        assertEquals("game_data:3:current", key.toString())
    }

    @Test
    fun `toString roundtrip with fromString`() {
        val original = CacheKey("disciple", 2, "d42")
        val parsed = CacheKey.fromString(original.toString())
        assertEquals(original.type, parsed.type)
        assertEquals(original.slot, parsed.slot)
        assertEquals(original.id, parsed.id)
    }

    // ==================== toByteArray ====================

    @Test
    fun `toByteArray returns UTF-8 encoded toString`() {
        val key = CacheKey("disciple", 1, "abc")
        val bytes = key.toByteArray()
        assertArrayEquals(key.toString().toByteArray(Charsets.UTF_8), bytes)
    }

    // ==================== withSlot ====================

    @Test
    fun `withSlot creates new key with different slot`() {
        val original = CacheKey("disciple", 1, "d1")
        val modified = original.withSlot(5)
        assertEquals(5, modified.slot)
        assertEquals(original.type, modified.type)
        assertEquals(original.id, modified.id)
        assertEquals(original.ttl, modified.ttl)
    }

    @Test
    fun `withSlot does not modify original`() {
        val original = CacheKey("disciple", 1, "d1")
        original.withSlot(5)
        assertEquals(1, original.slot)
    }

    // ==================== withTtl ====================

    @Test
    fun `withTtl creates new key with different TTL`() {
        val original = CacheKey("disciple", 1, "d1")
        val modified = original.withTtl(10000L)
        assertEquals(10000L, modified.ttl)
        assertEquals(original.type, modified.type)
        assertEquals(original.slot, modified.slot)
        assertEquals(original.id, modified.id)
    }

    // ==================== matchesSlot ====================

    @Test
    fun `matchesSlot returns true when slots match`() {
        val key = CacheKey("disciple", 3, "d1")
        assertTrue(key.matchesSlot(3))
    }

    @Test
    fun `matchesSlot returns false when slots differ`() {
        val key = CacheKey("disciple", 3, "d1")
        assertFalse(key.matchesSlot(1))
    }

    // ==================== matchesType ====================

    @Test
    fun `matchesType returns true when types match`() {
        val key = CacheKey("disciple", 1, "d1")
        assertTrue(key.matchesType("disciple"))
    }

    @Test
    fun `matchesType returns false when types differ`() {
        val key = CacheKey("disciple", 1, "d1")
        assertFalse(key.matchesType("equipment"))
    }

    // ==================== toSlotAwareCacheKey ====================

    @Test
    fun `toSlotAwareCacheKey preserves all fields`() {
        val key = CacheKey("disciple", 2, "d1", ttl = 5000L)
        val slotAware = key.toSlotAwareKey()
        assertEquals("disciple", slotAware.type)
        assertEquals(2, slotAware.slot)
        assertEquals("d1", slotAware.id)
        assertEquals(5000L, slotAware.ttl)
    }

    // ==================== SlotAwareCacheKey ====================

    @Test
    fun `SlotAwareCacheKey toString formats as type-slot-id`() {
        val key = SlotAwareCacheKey("disciple", 1, "abc")
        assertEquals("disciple:1:abc", key.toString())
    }

    @Test
    fun `SlotAwareCacheKey toCacheKey preserves all fields`() {
        val slotAware = SlotAwareCacheKey("disciple", 2, "d1", ttl = 5000L)
        val cacheKey = slotAware.toCacheKey()
        assertEquals("disciple", cacheKey.type)
        assertEquals(2, cacheKey.slot)
        assertEquals("d1", cacheKey.id)
        assertEquals(5000L, cacheKey.ttl)
    }

    @Test
    fun `SlotAwareCacheKey roundtrip with CacheKey`() {
        val original = CacheKey("equipment", 3, "e42", ttl = 9999L)
        val slotAware = original.toSlotAwareKey()
        val restored = slotAware.toCacheKey()
        assertEquals(original.type, restored.type)
        assertEquals(original.slot, restored.slot)
        assertEquals(original.id, restored.id)
        assertEquals(original.ttl, restored.ttl)
    }
}
