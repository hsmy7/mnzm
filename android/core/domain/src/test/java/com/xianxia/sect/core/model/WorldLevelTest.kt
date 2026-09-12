package com.xianxia.sect.core.model

import org.junit.Assert.*
import org.junit.Test

class WorldLevelTest {

    // ==================== 默认值测试 ====================

    @Test
    fun defaultWorldLevel_hasBeastType() {
        val level = WorldLevel()
        assertEquals(LevelType.BEAST, level.type)
    }

    // ==================== isBeast / isCave 测试 ====================

    @Test
    fun isBeast_returnsTrue_forBeastType() {
        val level = WorldLevel(type = LevelType.BEAST)
        assertTrue(level.isBeast)
    }

    @Test
    fun isBeast_returnsFalse_forCaveType() {
        val level = WorldLevel(type = LevelType.CAVE)
        assertFalse(level.isBeast)
    }

    @Test
    fun isCave_returnsTrue_forCaveType() {
        val level = WorldLevel(type = LevelType.CAVE)
        assertTrue(level.isCave)
    }

    @Test
    fun isCave_returnsFalse_forBeastType() {
        val level = WorldLevel(type = LevelType.BEAST)
        assertFalse(level.isCave)
    }

    // ==================== realmName 测试 ====================

    @Test
    fun realmName_realm0_is仙人() {
        assertEquals("仙人", WorldLevel(realm = 0).realmName)
    }

    @Test
    fun realmName_realm1_is渡劫() {
        assertEquals("渡劫", WorldLevel(realm = 1).realmName)
    }

    @Test
    fun realmName_realm2_is大乘() {
        assertEquals("大乘", WorldLevel(realm = 2).realmName)
    }

    @Test
    fun realmName_realm3_is合体() {
        assertEquals("合体", WorldLevel(realm = 3).realmName)
    }

    @Test
    fun realmName_realm4_is炼虚() {
        assertEquals("炼虚", WorldLevel(realm = 4).realmName)
    }

    @Test
    fun realmName_realm5_is化神() {
        assertEquals("化神", WorldLevel(realm = 5).realmName)
    }

    @Test
    fun realmName_realm6_is元婴() {
        assertEquals("元婴", WorldLevel(realm = 6).realmName)
    }

    @Test
    fun realmName_realm7_is金丹() {
        assertEquals("金丹", WorldLevel(realm = 7).realmName)
    }

    @Test
    fun realmName_realm8_is筑基() {
        assertEquals("筑基", WorldLevel(realm = 8).realmName)
    }

    @Test
    fun realmName_realm9_is炼气() {
        assertEquals("炼气", WorldLevel(realm = 9).realmName)
    }

    @Test
    fun realmName_invalidRealm_defaultsTo炼气() {
        assertEquals("炼气", WorldLevel(realm = -1).realmName)
        assertEquals("炼气", WorldLevel(realm = 100).realmName)
    }

    // ==================== isExpired 测试 ====================

    @Test
    fun isExpired_returnsTrue_whenDefeated() {
        val level = WorldLevel(defeated = true)
        assertTrue(level.isExpired)
    }

    @Test
    fun isExpired_returnsFalse_whenNotDefeated() {
        val level = WorldLevel(defeated = false)
        assertFalse(level.isExpired)
    }

    // ==================== checkExpired 测试 ====================

    @Test
    fun checkExpired_returnsTrue_whenCurrentYearGreaterThanExpiryYear() {
        val level = WorldLevel(expiryYear = 5, expiryMonth = 6, defeated = false)
        assertTrue(level.checkExpired(6, 1))
    }

    @Test
    fun checkExpired_returnsTrue_whenSameYearButCurrentMonthGreaterThanOrEqualExpiryMonth() {
        val level = WorldLevel(expiryYear = 5, expiryMonth = 6, defeated = false)
        assertTrue(level.checkExpired(5, 6))
        assertTrue(level.checkExpired(5, 7))
    }

    @Test
    fun checkExpired_returnsFalse_whenNotExpired() {
        val level = WorldLevel(expiryYear = 5, expiryMonth = 6, defeated = false)
        assertFalse(level.checkExpired(4, 12))
        assertFalse(level.checkExpired(5, 5))
    }

    @Test
    fun checkExpired_returnsTrue_whenAlreadyDefeated() {
        val level = WorldLevel(expiryYear = 10, expiryMonth = 12, defeated = true)
        assertTrue(level.checkExpired(1, 1))
    }

    // ==================== LevelType 枚举测试 ====================

    @Test
    fun levelType_hasBeastAndCave() {
        val values = LevelType.values()
        assertEquals(2, values.size)
        assertArrayEquals(arrayOf(LevelType.BEAST, LevelType.CAVE), values)
    }
}
