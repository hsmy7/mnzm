package com.xianxia.sect.core.engine

import com.xianxia.sect.core.model.RedeemCode
import com.xianxia.sect.core.model.RedeemRewardType
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class RedeemCodeManagerTest {

    @Before
    fun setUp() {
        RedeemCodeManager.clearAllCaches()
    }

    // ---- validateInput ----

    @Test
    fun validateInput_emptyCode_returnsError() {
        val result = RedeemCodeManager.validateInput("")
        assertNotNull(result)
        assertFalse(result!!.success)
    }

    @Test
    fun validateInput_whitespaceOnly_returnsError() {
        val result = RedeemCodeManager.validateInput("   ")
        assertNotNull(result)
        assertFalse(result!!.success)
    }

    @Test
    fun validateInput_tooShort_returnsError() {
        val result = RedeemCodeManager.validateInput("AB")
        assertNotNull(result)
        assertFalse(result!!.success)
    }

    @Test
    fun validateInput_tooLong_returnsError() {
        val longCode = "A".repeat(21)
        val result = RedeemCodeManager.validateInput(longCode)
        assertNotNull(result)
        assertFalse(result!!.success)
    }

    @Test
    fun validateInput_invalidCharacters_returnsError() {
        val result = RedeemCodeManager.validateInput("ABC-123")
        assertNotNull(result)
        assertFalse(result!!.success)
    }

    @Test
    fun validateInput_specialCharacters_returnsError() {
        val result = RedeemCodeManager.validateInput("ABC@123")
        assertNotNull(result)
        assertFalse(result!!.success)
    }

    @Test
    fun validateInput_validAlphanumeric_returnsNull() {
        val result = RedeemCodeManager.validateInput("ABC123")
        assertNull(result)
    }

    @Test
    fun validateInput_validWithSpacesTrimmed_returnsNull() {
        val result = RedeemCodeManager.validateInput("  ABC123  ")
        assertNull(result)
    }

    @Test
    fun validateInput_minLengthValid_returnsNull() {
        val result = RedeemCodeManager.validateInput("ABC")
        assertNull(result)
    }

    @Test
    fun validateInput_maxLengthValid_returnsNull() {
        val result = RedeemCodeManager.validateInput("A".repeat(20))
        assertNull(result)
    }

    @Test
    fun validateInput_numericOnly_returnsNull() {
        val result = RedeemCodeManager.validateInput("12345")
        assertNull(result)
    }

    // ---- getRedeemCode ----

    @Test
    fun getRedeemCode_notFound_returnsNull() {
        val result = RedeemCodeManager.getRedeemCode("NONEXISTENT")
        assertNull(result)
    }

    // ---- isCodeUsedGlobally ----

    @Test
    fun isCodeUsedGlobally_unusedCode_returnsFalse() {
        assertFalse(RedeemCodeManager.isCodeUsedGlobally("UNUSED_CODE"))
    }

    // ---- getCodeUsageRecord ----

    @Test
    fun getCodeUsageRecord_unusedCode_returnsNull() {
        assertNull(RedeemCodeManager.getCodeUsageRecord("UNUSED_CODE"))
    }

    // ---- clearAllCaches ----

    @Test
    fun clearAllCaches_resetsState() {
        RedeemCodeManager.clearAllCaches()
        // After clearing, no codes should be used globally
        assertFalse(RedeemCodeManager.isCodeUsedGlobally("ANY_CODE"))
    }

    // ---- resetRateLimitForPlayer ----

    @Test
    fun resetRateLimitForPlayer_doesNotThrow() {
        RedeemCodeManager.resetRateLimitForPlayer("test_player")
    }

    // ---- getRateLimitStats ----

    @Test
    fun getRateLimitStats_defaultPlayer_returnsZeroAttempts() {
        RedeemCodeManager.clearAllCaches()
        val stats = RedeemCodeManager.getRateLimitStats("new_player")
        assertEquals(0, stats.attemptsInLastMinute)
        assertEquals(0, stats.attemptsInLastHour)
        assertEquals(0, stats.attemptsToday)
        assertTrue(stats.maxPerMinute > 0)
        assertTrue(stats.maxPerHour > 0)
        assertTrue(stats.maxPerDay > 0)
    }

    // ---- RateLimitStats ----

    @Test
    fun rateLimitStats_remainingCalculations() {
        val stats = RedeemCodeManager.RateLimitStats(
            attemptsInLastMinute = 3,
            attemptsInLastHour = 10,
            attemptsToday = 50,
            maxPerMinute = 5,
            maxPerHour = 20,
            maxPerDay = 100
        )
        assertEquals(2, stats.minuteRemaining)
        assertEquals(10, stats.hourRemaining)
        assertEquals(50, stats.dayRemaining)
    }

    @Test
    fun rateLimitStats_remainingClampedToZero() {
        val stats = RedeemCodeManager.RateLimitStats(
            attemptsInLastMinute = 10,
            attemptsInLastHour = 30,
            attemptsToday = 200,
            maxPerMinute = 5,
            maxPerHour = 20,
            maxPerDay = 100
        )
        assertEquals(0, stats.minuteRemaining)
        assertEquals(0, stats.hourRemaining)
        assertEquals(0, stats.dayRemaining)
    }

    // ---- UsedCodeRecord ----

    @Test
    fun usedCodeRecord_construction() {
        val record = RedeemCodeManager.UsedCodeRecord(
            code = "TEST123",
            usedAt = 1000000L,
            deviceId = "device1",
            playerId = "player1"
        )
        assertEquals("TEST123", record.code)
        assertEquals(1000000L, record.usedAt)
        assertEquals("device1", record.deviceId)
        assertEquals("player1", record.playerId)
    }

    // ---- IpRateLimitResult ----

    @Test
    fun ipRateLimitResult_construction() {
        val result = RedeemCodeManager.IpRateLimitResult(
            allowed = false,
            remainingAttempts = 0,
            resetTimeSeconds = 60L,
            errorMessage = "Too many requests"
        )
        assertFalse(result.allowed)
        assertEquals(0, result.remainingAttempts)
        assertEquals(60L, result.resetTimeSeconds)
        assertEquals("Too many requests", result.errorMessage)
    }

    // ---- RemoteValidationResult ----

    @Test
    fun remoteValidationResult_construction() {
        val result = RedeemCodeManager.RemoteValidationResult(
            valid = true,
            serverCode = null,
            errorMessage = null,
            signature = "sig123"
        )
        assertTrue(result.valid)
        assertNull(result.serverCode)
        assertNull(result.errorMessage)
        assertEquals("sig123", result.signature)
    }
}
