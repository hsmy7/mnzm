package com.xianxia.sect.core.model

import org.junit.Assert.*
import org.junit.Test

class RedeemCodeTest {

    // ---- RedeemRewardType ----

    @Test
    fun redeemRewardType_hasNineValues() {
        assertEquals(10, RedeemRewardType.entries.size)
    }

    @Test
    fun redeemRewardType_values() {
        val expected = arrayOf(
            RedeemRewardType.SPIRIT_STONES,
            RedeemRewardType.EQUIPMENT,
            RedeemRewardType.MANUAL,
            RedeemRewardType.PILL,
            RedeemRewardType.MATERIAL,
            RedeemRewardType.HERB,
            RedeemRewardType.SEED,
            RedeemRewardType.DISCIPLE,
            RedeemRewardType.STARTER_PACK,
            RedeemRewardType.MANUAL_PACK
        )
        assertArrayEquals(expected, RedeemRewardType.entries.toTypedArray())
    }

    // ---- DiscipleRewardConfig ----

    @Test
    fun discipleRewardConfig_defaultConstruction() {
        val config = DiscipleRewardConfig()
        assertEquals(9, config.realm)
        assertEquals(1, config.realmLayer)
        assertNull(config.spiritRootType)
        assertNull(config.spiritRootCount)
        assertEquals(emptyList<String>(), config.talentIds)
        assertNull(config.intelligence)
        assertNull(config.comprehension)
        assertNull(config.charm)
        assertNull(config.loyalty)
        assertNull(config.artifactRefining)
        assertNull(config.pillRefining)
        assertNull(config.spiritPlanting)
        assertNull(config.mining)
        assertNull(config.teaching)
        assertNull(config.morality)
        assertEquals(16, config.minAge)
        assertEquals(25, config.maxAge)
        assertEquals("random", config.gender)
    }

    @Test
    fun discipleRewardConfig_customConstruction() {
        val config = DiscipleRewardConfig(
            realm = 7,
            realmLayer = 3,
            spiritRootType = "fire",
            spiritRootCount = 2,
            talentIds = listOf("t1", "t2"),
            intelligence = 80,
            minAge = 18,
            maxAge = 30,
            gender = "male"
        )
        assertEquals(7, config.realm)
        assertEquals(3, config.realmLayer)
        assertEquals("fire", config.spiritRootType)
        assertEquals(2, config.spiritRootCount)
        assertEquals(listOf("t1", "t2"), config.talentIds)
        assertEquals(80, config.intelligence)
        assertEquals(18, config.minAge)
        assertEquals(30, config.maxAge)
        assertEquals("male", config.gender)
    }

    @Test
    fun discipleRewardConfig_copy() {
        val original = DiscipleRewardConfig(realm = 9, minAge = 16)
        val copied = original.copy(realm = 5, minAge = 20)
        assertEquals(5, copied.realm)
        assertEquals(20, copied.minAge)
    }

    // ---- RedeemCode ----

    @Test
    fun redeemCode_construction() {
        val code = RedeemCode(
            code = "TEST2024",
            rewardType = RedeemRewardType.SPIRIT_STONES,
            quantity = 1000,
            rarity = 2,
            maxUses = 10,
            usedCount = 0,
            isEnabled = true
        )
        assertEquals("TEST2024", code.code)
        assertEquals(RedeemRewardType.SPIRIT_STONES, code.rewardType)
        assertEquals(1000, code.quantity)
        assertEquals(2, code.rarity)
        assertEquals(10, code.maxUses)
        assertEquals(0, code.usedCount)
        assertTrue(code.isEnabled)
        assertNull(code.expireYear)
        assertNull(code.expireMonth)
        assertNull(code.discipleConfig)
    }

    @Test
    fun redeemCode_defaultValues() {
        val code = RedeemCode(
            code = "ABC",
            rewardType = RedeemRewardType.PILL
        )
        assertEquals(1, code.quantity)
        assertEquals(1, code.rarity)
        assertEquals(1, code.maxUses)
        assertEquals(0, code.usedCount)
        assertTrue(code.isEnabled)
    }

    @Test
    fun redeemCode_isExhausted_whenNotUsed() {
        val code = RedeemCode(
            code = "X",
            rewardType = RedeemRewardType.SPIRIT_STONES,
            maxUses = 5,
            usedCount = 0
        )
        assertFalse(code.isExhausted)
    }

    @Test
    fun redeemCode_isExhausted_whenFullyUsed() {
        val code = RedeemCode(
            code = "X",
            rewardType = RedeemRewardType.SPIRIT_STONES,
            maxUses = 5,
            usedCount = 5
        )
        assertTrue(code.isExhausted)
    }

    @Test
    fun redeemCode_isExhausted_whenOverUsed() {
        val code = RedeemCode(
            code = "X",
            rewardType = RedeemRewardType.SPIRIT_STONES,
            maxUses = 3,
            usedCount = 5
        )
        assertTrue(code.isExhausted)
    }

    @Test
    fun redeemCode_withDiscipleConfig() {
        val config = DiscipleRewardConfig(realm = 8, intelligence = 90)
        val code = RedeemCode(
            code = "DISCIPLE1",
            rewardType = RedeemRewardType.DISCIPLE,
            discipleConfig = config
        )
        assertNotNull(code.discipleConfig)
        assertEquals(8, code.discipleConfig!!.realm)
        assertEquals(90, code.discipleConfig!!.intelligence)
    }

    @Test
    fun redeemCode_withExpiry() {
        val code = RedeemCode(
            code = "EXPIRE",
            rewardType = RedeemRewardType.EQUIPMENT,
            expireYear = 2025,
            expireMonth = 12
        )
        assertEquals(2025, code.expireYear!!.toInt())
        assertEquals(12, code.expireMonth!!.toInt())
    }

    @Test
    fun redeemCode_copy() {
        val original = RedeemCode(
            code = "ORIG",
            rewardType = RedeemRewardType.PILL,
            maxUses = 1
        )
        val copied = original.copy(usedCount = 1)
        assertEquals("ORIG", copied.code)
        assertEquals(1, copied.usedCount)
    }

    // ---- RedeemResult ----

    @Test
    fun redeemResult_successConstruction() {
        val result = RedeemResult(
            success = true,
            message = "兑换成功",
            rewards = listOf(RewardSelectedItem("r1", "pill", "Breakthrough Pill", 3, 1))
        )
        assertTrue(result.success)
        assertEquals("兑换成功", result.message)
        assertEquals(1, result.rewards.size)
        assertNull(result.disciple)
        assertEquals(emptyList<Disciple>(), result.disciples)
    }

    @Test
    fun redeemResult_failureConstruction() {
        val result = RedeemResult(
            success = false,
            message = "兑换码已过期"
        )
        assertFalse(result.success)
        assertEquals("兑换码已过期", result.message)
        assertEquals(emptyList<RewardSelectedItem>(), result.rewards)
    }

    @Test
    fun redeemResult_defaultValues() {
        val result = RedeemResult(success = true, message = "ok")
        assertEquals(emptyList<RewardSelectedItem>(), result.rewards)
        assertNull(result.disciple)
        assertEquals(emptyList<Disciple>(), result.disciples)
    }

    @Test
    fun redeemResult_copy() {
        val original = RedeemResult(success = true, message = "ok")
        val copied = original.copy(message = "new msg")
        assertTrue(copied.success)
        assertEquals("new msg", copied.message)
    }
}
