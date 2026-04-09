package com.xianxia.sect.data.serialization

import org.junit.Assert.*
import org.junit.Test

class NullSafeProtoBufTest {

    @Test
    fun `stringToProto - null returns empty string`() {
        assertEquals("", NullSafeProtoBuf.stringToProto(null))
    }

    @Test
    fun `stringToProto - non-null returns same value`() {
        assertEquals("hello", NullSafeProtoBuf.stringToProto("hello"))
    }

    @Test
    fun `stringToProto - null with custom default returns default`() {
        assertEquals("N/A", NullSafeProtoBuf.stringToProto(null, "N/A"))
    }

    @Test
    fun `stringFromProto - empty string returns null`() {
        assertNull(NullSafeProtoBuf.stringFromProto(""))
    }

    @Test
    fun `stringFromProto - non-empty string returns same value`() {
        assertEquals("hello", NullSafeProtoBuf.stringFromProto("hello"))
    }

    @Test
    fun `string roundtrip - null to proto and back returns null`() {
        val toProto = NullSafeProtoBuf.stringToProto(null)
        val fromProto = NullSafeProtoBuf.stringFromProto(toProto)
        assertNull(fromProto)
    }

    @Test
    fun `string roundtrip - non-null to proto and back returns original`() {
        val toProto = NullSafeProtoBuf.stringToProto("test")
        val fromProto = NullSafeProtoBuf.stringFromProto(toProto)
        assertEquals("test", fromProto)
    }

    @Test
    fun `intToProto - null returns default sentinel -1`() {
        assertEquals(-1, NullSafeProtoBuf.intToProto(null))
    }

    @Test
    fun `intToProto - non-null returns same value`() {
        assertEquals(42, NullSafeProtoBuf.intToProto(42))
    }

    @Test
    fun `intToProto - null with custom sentinel returns sentinel`() {
        assertEquals(0, NullSafeProtoBuf.intToProto(null, sentinel = 0))
    }

    @Test
    fun `intFromProto - sentinel value returns null`() {
        assertNull(NullSafeProtoBuf.intFromProto(-1))
    }

    @Test
    fun `intFromProto - non-sentinel value returns same value`() {
        assertEquals(42, NullSafeProtoBuf.intFromProto(42))
    }

    @Test
    fun `intFromProto - custom sentinel returns null for that sentinel`() {
        assertNull(NullSafeProtoBuf.intFromProto(0, sentinel = 0))
    }

    @Test
    fun `int roundtrip - null to proto and back returns null`() {
        val toProto = NullSafeProtoBuf.intToProto(null)
        val fromProto = NullSafeProtoBuf.intFromProto(toProto)
        assertNull(fromProto)
    }

    @Test
    fun `int roundtrip - non-null to proto and back returns original`() {
        val toProto = NullSafeProtoBuf.intToProto(100)
        val fromProto = NullSafeProtoBuf.intFromProto(toProto)
        assertEquals(100, fromProto)
    }

    @Test
    fun `int roundtrip - zero value is preserved with default sentinel`() {
        val toProto = NullSafeProtoBuf.intToProto(0)
        val fromProto = NullSafeProtoBuf.intFromProto(toProto)
        assertEquals(0, fromProto)
    }

    @Test
    fun `int roundtrip - sentinel value -1 becomes null`() {
        val toProto = NullSafeProtoBuf.intToProto(-1)
        val fromProto = NullSafeProtoBuf.intFromProto(toProto)
        assertNull(fromProto)
    }

    @Test
    fun `longToProto - null returns default sentinel -1L`() {
        assertEquals(-1L, NullSafeProtoBuf.longToProto(null))
    }

    @Test
    fun `longToProto - non-null returns same value`() {
        assertEquals(123456789L, NullSafeProtoBuf.longToProto(123456789L))
    }

    @Test
    fun `longFromProto - sentinel value returns null`() {
        assertNull(NullSafeProtoBuf.longFromProto(-1L))
    }

    @Test
    fun `longFromProto - non-sentinel value returns same value`() {
        assertEquals(123456789L, NullSafeProtoBuf.longFromProto(123456789L))
    }

    @Test
    fun `long roundtrip - null to proto and back returns null`() {
        val toProto = NullSafeProtoBuf.longToProto(null)
        val fromProto = NullSafeProtoBuf.longFromProto(toProto)
        assertNull(fromProto)
    }

    @Test
    fun `long roundtrip - non-null to proto and back returns original`() {
        val toProto = NullSafeProtoBuf.longToProto(999L)
        val fromProto = NullSafeProtoBuf.longFromProto(toProto)
        assertEquals(999L, fromProto)
    }

    @Test
    fun `long roundtrip - zero value is preserved with default sentinel`() {
        val toProto = NullSafeProtoBuf.longToProto(0L)
        val fromProto = NullSafeProtoBuf.longFromProto(toProto)
        assertEquals(0L, fromProto)
    }

    @Test
    fun `doubleToProto - null returns default sentinel -1_0`() {
        assertEquals(-1.0, NullSafeProtoBuf.doubleToProto(null), 0.001)
    }

    @Test
    fun `doubleToProto - non-null returns same value`() {
        assertEquals(3.14, NullSafeProtoBuf.doubleToProto(3.14), 0.001)
    }

    @Test
    fun `doubleFromProto - sentinel -1_0 returns null`() {
        assertNull(NullSafeProtoBuf.doubleFromProto(-1.0))
    }

    @Test
    fun `doubleFromProto - non-sentinel value returns same value`() {
        assertEquals(3.14, NullSafeProtoBuf.doubleFromProto(3.14)!!, 0.001)
    }

    @Test
    fun `double roundtrip - null to proto and back returns null`() {
        val toProto = NullSafeProtoBuf.doubleToProto(null)
        val fromProto = NullSafeProtoBuf.doubleFromProto(toProto)
        assertNull(fromProto)
    }

    @Test
    fun `double roundtrip - non-null to proto and back returns original`() {
        val toProto = NullSafeProtoBuf.doubleToProto(2.718)
        val fromProto = NullSafeProtoBuf.doubleFromProto(toProto)
        assertEquals(2.718, fromProto!!, 0.001)
    }

    @Test
    fun `double roundtrip - zero value is preserved with default sentinel`() {
        val toProto = NullSafeProtoBuf.doubleToProto(0.0)
        val fromProto = NullSafeProtoBuf.doubleFromProto(toProto)
        assertEquals(0.0, fromProto!!, 0.001)
    }

    @Test
    fun `doubleToProto - null with custom sentinel returns custom sentinel`() {
        assertEquals(0.0, NullSafeProtoBuf.doubleToProto(null, sentinel = 0.0), 0.001)
    }

    @Test
    fun `doubleFromProto - custom sentinel returns null`() {
        assertNull(NullSafeProtoBuf.doubleFromProto(0.0, sentinel = 0.0))
    }

    @Test
    fun `triStateToProto - null returns 0 (UNSET)`() {
        assertEquals(0, NullSafeProtoBuf.triStateToProto(null))
    }

    @Test
    fun `triStateToProto - true returns 1 (TRUE)`() {
        assertEquals(1, NullSafeProtoBuf.triStateToProto(true))
    }

    @Test
    fun `triStateToProto - false returns 2 (FALSE)`() {
        assertEquals(2, NullSafeProtoBuf.triStateToProto(false))
    }

    @Test
    fun `triStateFromProto - 0 returns default value`() {
        assertEquals(false, NullSafeProtoBuf.triStateFromProto(0, defaultValue = false))
        assertEquals(true, NullSafeProtoBuf.triStateFromProto(0, defaultValue = true))
    }

    @Test
    fun `triStateFromProto - 1 returns true`() {
        assertEquals(true, NullSafeProtoBuf.triStateFromProto(1))
    }

    @Test
    fun `triStateFromProto - 2 returns false`() {
        assertEquals(false, NullSafeProtoBuf.triStateFromProto(2))
    }

    @Test
    fun `triState roundtrip - null preserves null via UNSET`() {
        val toProto = NullSafeProtoBuf.triStateToProto(null)
        assertEquals(0, toProto)
        val fromProto = NullSafeProtoBuf.triStateFromProto(toProto)
        assertEquals(false, fromProto)
    }

    @Test
    fun `triState roundtrip - true preserves true`() {
        val toProto = NullSafeProtoBuf.triStateToProto(true)
        val fromProto = NullSafeProtoBuf.triStateFromProto(toProto)
        assertEquals(true, fromProto)
    }

    @Test
    fun `triState roundtrip - false preserves false`() {
        val toProto = NullSafeProtoBuf.triStateToProto(false)
        val fromProto = NullSafeProtoBuf.triStateFromProto(toProto)
        assertEquals(false, fromProto)
    }

    @Test
    fun `TriStateBoolean - UNSET has value 0`() {
        assertEquals(0, NullSafeProtoBuf.TriStateBoolean.UNSET.value)
        assertTrue(NullSafeProtoBuf.TriStateBoolean.UNSET.isUnset)
    }

    @Test
    fun `TriStateBoolean - TRUE has value 1`() {
        assertEquals(1, NullSafeProtoBuf.TriStateBoolean.TRUE.value)
        assertTrue(NullSafeProtoBuf.TriStateBoolean.TRUE.isExplicitTrue)
    }

    @Test
    fun `TriStateBoolean - FALSE has value 2`() {
        assertEquals(2, NullSafeProtoBuf.TriStateBoolean.FALSE.value)
        assertTrue(NullSafeProtoBuf.TriStateBoolean.FALSE.isExplicitFalse)
    }

    @Test
    fun `TriStateBoolean fromNullable - maps correctly`() {
        assertEquals(NullSafeProtoBuf.TriStateBoolean.UNSET, NullSafeProtoBuf.TriStateBoolean.fromNullable(null))
        assertEquals(NullSafeProtoBuf.TriStateBoolean.TRUE, NullSafeProtoBuf.TriStateBoolean.fromNullable(true))
        assertEquals(NullSafeProtoBuf.TriStateBoolean.FALSE, NullSafeProtoBuf.TriStateBoolean.fromNullable(false))
    }

    @Test
    fun `TriStateBoolean fromInt - maps correctly`() {
        assertEquals(NullSafeProtoBuf.TriStateBoolean.UNSET, NullSafeProtoBuf.TriStateBoolean.fromInt(0))
        assertEquals(NullSafeProtoBuf.TriStateBoolean.TRUE, NullSafeProtoBuf.TriStateBoolean.fromInt(1))
        assertEquals(NullSafeProtoBuf.TriStateBoolean.FALSE, NullSafeProtoBuf.TriStateBoolean.fromInt(2))
        assertEquals(NullSafeProtoBuf.TriStateBoolean.UNSET, NullSafeProtoBuf.TriStateBoolean.fromInt(99))
    }

    @Test
    fun `TriStateBoolean toNullable - maps correctly`() {
        assertNull(NullSafeProtoBuf.TriStateBoolean.UNSET.toNullable())
        assertEquals(true, NullSafeProtoBuf.TriStateBoolean.TRUE.toNullable())
        assertEquals(false, NullSafeProtoBuf.TriStateBoolean.FALSE.toNullable())
    }

    @Test
    fun `TriStateBoolean toBooleanOrDefault - UNSET returns default`() {
        assertTrue(NullSafeProtoBuf.TriStateBoolean.UNSET.toBooleanOrDefault(true))
        assertFalse(NullSafeProtoBuf.TriStateBoolean.UNSET.toBooleanOrDefault(false))
    }

    @Test
    fun `TriStateBoolean toBooleanOrDefault - TRUE returns true`() {
        assertTrue(NullSafeProtoBuf.TriStateBoolean.TRUE.toBooleanOrDefault(false))
    }

    @Test
    fun `TriStateBoolean toBooleanOrDefault - FALSE returns false`() {
        assertFalse(NullSafeProtoBuf.TriStateBoolean.FALSE.toBooleanOrDefault(true))
    }

    @Test
    fun `listToProto - null returns empty list`() {
        val result: List<String> = NullSafeProtoBuf.listToProto(null)
        assertTrue(result.isEmpty())
    }

    @Test
    fun `listToProto - non-null returns same list`() {
        val list = listOf("a", "b", "c")
        assertEquals(list, NullSafeProtoBuf.listToProto(list))
    }

    @Test
    fun `listFromProto - always returns same list`() {
        val list = listOf("x", "y")
        assertEquals(list, NullSafeProtoBuf.listFromProto(list))
        assertEquals(emptyList<String>(), NullSafeProtoBuf.listFromProto(emptyList()))
    }

    @Test
    fun `mapToProto - null returns empty map`() {
        val result: Map<String, Int> = NullSafeProtoBuf.mapToProto(null)
        assertTrue(result.isEmpty())
    }

    @Test
    fun `mapToProto - non-null returns same map`() {
        val map = mapOf("a" to 1, "b" to 2)
        assertEquals(map, NullSafeProtoBuf.mapToProto(map))
    }

    @Test
    fun `mapFromProto - always returns same map`() {
        val map = mapOf("x" to 10)
        assertEquals(map, NullSafeProtoBuf.mapFromProto(map))
        assertEquals(emptyMap<String, Int>(), NullSafeProtoBuf.mapFromProto(emptyMap()))
    }

    @Test
    fun `objectToProto - null returns default value`() {
        val result = NullSafeProtoBuf.objectToProto(null as String?) { "default" }
        assertEquals("default", result)
    }

    @Test
    fun `objectToProto - non-null returns same value`() {
        val result = NullSafeProtoBuf.objectToProto("actual") { "default" }
        assertEquals("actual", result)
    }

    @Test
    fun `objectFromProto - empty object returns null`() {
        val result = NullSafeProtoBuf.objectFromProto("") { it.isEmpty() }
        assertNull(result)
    }

    @Test
    fun `objectFromProto - non-empty object returns value`() {
        val result = NullSafeProtoBuf.objectFromProto("hello") { it.isEmpty() }
        assertEquals("hello", result)
    }

    @Test
    fun `relationIdToProto - null returns empty string`() {
        assertEquals("", NullSafeProtoBuf.relationIdToProto(null))
    }

    @Test
    fun `relationIdToProto - empty string returns empty string`() {
        assertEquals("", NullSafeProtoBuf.relationIdToProto(""))
    }

    @Test
    fun `relationIdToProto - non-empty returns same value`() {
        assertEquals("disciple_1", NullSafeProtoBuf.relationIdToProto("disciple_1"))
    }

    @Test
    fun `relationIdFromProto - empty string returns null`() {
        assertNull(NullSafeProtoBuf.relationIdFromProto(""))
    }

    @Test
    fun `relationIdFromProto - non-empty returns same value`() {
        assertEquals("disciple_1", NullSafeProtoBuf.relationIdFromProto("disciple_1"))
    }

    @Test
    fun `equipmentIdToProto - null returns empty string`() {
        assertEquals("", NullSafeProtoBuf.equipmentIdToProto(null))
    }

    @Test
    fun `equipmentIdFromProto - empty string returns null`() {
        assertNull(NullSafeProtoBuf.equipmentIdFromProto(""))
    }

    @Test
    fun `equipmentId roundtrip - null preserves null`() {
        val toProto = NullSafeProtoBuf.equipmentIdToProto(null)
        val fromProto = NullSafeProtoBuf.equipmentIdFromProto(toProto)
        assertNull(fromProto)
    }

    @Test
    fun `equipmentId roundtrip - non-null preserves value`() {
        val toProto = NullSafeProtoBuf.equipmentIdToProto("weapon_1")
        val fromProto = NullSafeProtoBuf.equipmentIdFromProto(toProto)
        assertEquals("weapon_1", fromProto)
    }

    @Test
    fun `griefEndYearToProto - null returns sentinel -1`() {
        assertEquals(-1, NullSafeProtoBuf.griefEndYearToProto(null))
    }

    @Test
    fun `griefEndYearToProto - non-null returns same value`() {
        assertEquals(30, NullSafeProtoBuf.griefEndYearToProto(30))
    }

    @Test
    fun `griefEndYearFromProto - sentinel -1 returns null`() {
        assertNull(NullSafeProtoBuf.griefEndYearFromProto(-1))
    }

    @Test
    fun `griefEndYearFromProto - non-sentinel returns same value`() {
        assertEquals(30, NullSafeProtoBuf.griefEndYearFromProto(30))
    }

    @Test
    fun `griefEndYear roundtrip - null preserves null`() {
        val toProto = NullSafeProtoBuf.griefEndYearToProto(null)
        val fromProto = NullSafeProtoBuf.griefEndYearFromProto(toProto)
        assertNull(fromProto)
    }

    @Test
    fun `griefEndYear roundtrip - non-null preserves value`() {
        val toProto = NullSafeProtoBuf.griefEndYearToProto(50)
        val fromProto = NullSafeProtoBuf.griefEndYearFromProto(toProto)
        assertEquals(50, fromProto)
    }

    @Test
    fun `protoBuf instance - is not null`() {
        assertNotNull(NullSafeProtoBuf.protoBuf)
    }

    @Test
    fun `DEFAULT_INT_SENTINEL is -1`() {
        assertEquals(-1, NullSafeProtoBuf.DEFAULT_INT_SENTINEL)
    }

    @Test
    fun `DEFAULT_LONG_SENTINEL is -1L`() {
        assertEquals(-1L, NullSafeProtoBuf.DEFAULT_LONG_SENTINEL)
    }

    @Test
    fun `DEFAULT_DOUBLE_SENTINEL is -1_0`() {
        assertEquals(-1.0, NullSafeProtoBuf.DEFAULT_DOUBLE_SENTINEL, 0.001)
    }

    @Test
    fun `GRIEF_END_YEAR_SENTINEL is -1`() {
        assertEquals(-1, NullSafeProtoBuf.GRIEF_END_YEAR_SENTINEL)
    }

    @Test
    fun `stringToProto - unicode characters are preserved`() {
        val unicode = "修仙者"
        assertEquals(unicode, NullSafeProtoBuf.stringToProto(unicode))
    }

    @Test
    fun `stringFromProto - unicode characters are preserved`() {
        val unicode = "修仙者"
        assertEquals(unicode, NullSafeProtoBuf.stringFromProto(unicode))
    }

    @Test
    fun `intToProto - negative non-sentinel values are preserved`() {
        assertEquals(-2, NullSafeProtoBuf.intToProto(-2))
    }

    @Test
    fun `intFromProto - negative non-sentinel values are preserved`() {
        assertEquals(-2, NullSafeProtoBuf.intFromProto(-2))
    }

    @Test
    fun `intToProto with custom sentinel - null returns custom sentinel`() {
        assertEquals(-999, NullSafeProtoBuf.intToProto(null, sentinel = -999))
    }

    @Test
    fun `intFromProto with custom sentinel - custom sentinel returns null`() {
        assertNull(NullSafeProtoBuf.intFromProto(-999, sentinel = -999))
    }

    @Test
    fun `nurtureDataToProto - null returns default with empty equipmentId`() {
        val result = NullSafeProtoBuf.nurtureDataToProto(null)
        assertEquals("", result.equipmentId)
        assertEquals(0, result.rarity)
        assertEquals(0, result.nurtureLevel)
        assertEquals(0.0, result.nurtureProgress, 0.001)
    }

    @Test
    fun `nurtureDataFromProto - empty equipmentId returns null`() {
        val proto = com.xianxia.sect.data.serialization.unified.SerializableEquipmentNurtureData(
            equipmentId = "", rarity = 0
        )
        assertNull(NullSafeProtoBuf.nurtureDataFromProto(proto))
    }

    @Test
    fun `nurtureDataFromProto - non-empty equipmentId returns data`() {
        val proto = com.xianxia.sect.data.serialization.unified.SerializableEquipmentNurtureData(
            equipmentId = "equip_1", rarity = 3, nurtureLevel = 5, nurtureProgress = 0.8
        )
        val result = NullSafeProtoBuf.nurtureDataFromProto(proto)
        assertNotNull(result)
        assertEquals("equip_1", result!!.equipmentId)
        assertEquals(3, result.rarity)
        assertEquals(5, result.nurtureLevel)
        assertEquals(0.8, result.nurtureProgress!!, 0.001)
    }

    @Test
    fun `battleTeamToProto - null returns default with empty id`() {
        val result = NullSafeProtoBuf.battleTeamToProto(null)
        assertEquals("", result.id)
    }

    @Test
    fun `battleTeamFromProto - empty id returns null`() {
        val proto = com.xianxia.sect.data.serialization.unified.SerializableBattleTeam(
            id = "", name = "未命名队伍", slots = emptyList(), isAtSect = true,
            currentX = 0f, currentY = 0f, targetX = 0f, targetY = 0f,
            status = "IDLE", targetSectId = "", originSectId = "", route = emptyList(),
            currentRouteIndex = 0, moveProgress = 0f, isOccupying = false, isReturning = false
        )
        assertNull(NullSafeProtoBuf.battleTeamFromProto(proto))
    }
}
