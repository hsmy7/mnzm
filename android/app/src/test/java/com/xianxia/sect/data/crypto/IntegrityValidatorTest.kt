package com.xianxia.sect.data.crypto

import org.junit.Assert.*
import org.junit.Test

class IntegrityValidatorTest {

    private val testKey = "test-secret-key-1234567890".toByteArray(Charsets.UTF_8)
    private val altKey = "different-key-abcdef123456".toByteArray(Charsets.UTF_8)

    @Test
    fun `computeFullDataSignature - same data and key produces same signature`() {
        val data = "test data string"
        val sig1 = IntegrityValidator.computeFullDataSignature(data, testKey)
        val sig2 = IntegrityValidator.computeFullDataSignature(data, testKey)
        assertEquals(sig1, sig2)
    }

    @Test
    fun `computeFullDataSignature - different data produces different signatures`() {
        val data1 = "test data A"
        val data2 = "test data B"
        val sig1 = IntegrityValidator.computeFullDataSignature(data1, testKey)
        val sig2 = IntegrityValidator.computeFullDataSignature(data2, testKey)
        assertNotEquals(sig1, sig2)
    }

    @Test
    fun `computeFullDataSignature - different key produces different signatures`() {
        val data = "test data"
        val sig1 = IntegrityValidator.computeFullDataSignature(data, testKey)
        val sig2 = IntegrityValidator.computeFullDataSignature(data, altKey)
        assertNotEquals(sig1, sig2)
    }

    @Test
    fun `computeFullDataSignature - returns non-empty string`() {
        val signature = IntegrityValidator.computeFullDataSignature("key value", testKey)
        assertTrue(signature.isNotEmpty())
    }

    @Test
    fun `verifyFullDataSignature - correct signature returns true`() {
        val data = "id=1 status=active"
        val signature = IntegrityValidator.computeFullDataSignature(data, testKey)
        assertTrue(IntegrityValidator.verifyFullDataSignature(data, signature, testKey))
    }

    @Test
    fun `verifyFullDataSignature - wrong signature returns false`() {
        val data = "id=1 status=active"
        assertFalse(IntegrityValidator.verifyFullDataSignature(data, "wrong_signature_value", testKey))
    }

    @Test
    fun `verifyFullDataSignature - tampered data returns false`() {
        val originalData = "balance=1000"
        val signature = IntegrityValidator.computeFullDataSignature(originalData, testKey)
        val tamperedData = "balance=999999"
        assertFalse(IntegrityValidator.verifyFullDataSignature(tamperedData, signature, testKey))
    }

    @Test
    fun `VerificationResult - Valid properties`() {
        val valid = VerificationResult.Valid
        assertTrue(valid.isValid)
        assertFalse(valid.isTampered)
        assertFalse(valid.isExpired)
    }

    @Test
    fun `VerificationResult - Tampered isTampered is true`() {
        val tampered = VerificationResult.Tampered("data changed")
        assertTrue(tampered.isTampered)
        assertFalse(tampered.isValid)
        assertFalse(tampered.isExpired)
    }

    @Test
    fun `VerificationResult - Expired isExpired is true`() {
        val expired = VerificationResult.Expired(1000L, 2000L)
        assertTrue(expired.isExpired)
        assertFalse(expired.isValid)
        assertFalse(expired.isTampered)
    }

    @Test
    fun `VerificationResult - Invalid contains reason`() {
        val reason = "unsupported version 99"
        val invalid = VerificationResult.Invalid(reason)
        assertFalse(invalid.isValid)
        assertEquals(reason, invalid.reason)
    }

    @Test
    fun `IntegrityReport - default isValid true no errors`() {
        val report = IntegrityReport(
            isValid = true,
            dataHash = "abc",
            merkleRoot = "def",
            signatureValid = true,
            hashValid = true,
            merkleValid = true
        )
        assertTrue(report.isValid)
        assertFalse(report.hasErrors)
        assertTrue(report.errors.isEmpty())
    }

    @Test
    fun `IntegrityReport - hasErrors when errors not empty`() {
        val report = IntegrityReport(
            isValid = false,
            dataHash = "",
            merkleRoot = "",
            signatureValid = false,
            hashValid = false,
            merkleValid = false,
            errors = listOf("error1", "error2")
        )
        assertFalse(report.isValid)
        assertTrue(report.hasErrors)
        assertEquals(2, report.errors.size)
    }

    @Test
    fun `computeHmacForString - same input produces same HMAC`() {
        val hmac1 = IntegrityValidator.computeHmacForString("hello world", testKey)
        val hmac2 = IntegrityValidator.computeHmacForString("hello world", testKey)
        assertEquals(hmac1, hmac2)
    }

    @Test
    fun `computeHmacForString - different key produces different HMAC`() {
        val hmac1 = IntegrityValidator.computeHmacForString("test message", testKey)
        val hmac2 = IntegrityValidator.computeHmacForString("test message", altKey)
        assertNotEquals(hmac1, hmac2)
    }

    @Test
    fun `embedChecksum and verifyEmbeddedChecksum roundtrip consistency`() {
        val originalData = "important game save data here".toByteArray(Charsets.UTF_8)
        val embedded = SaveCrypto.embedChecksum(originalData)
        assertTrue(SaveCrypto.verifyEmbeddedChecksum(embedded))
    }

    @Test
    fun `extractChecksumAndData returns null for short data less than 32 bytes`() {
        val shortData = ByteArray(16) { it.toByte() }
        assertNull(SaveCrypto.extractChecksumAndData(shortData))
    }

    @Test
    fun `SignedPayload - toJson and fromJson roundtrip preserves all fields`() {
        val original = SignedPayload(
            version = 1,
            timestamp = System.currentTimeMillis(),
            dataHash = "hash123",
            merkleRoot = "root456",
            signature = "sig789",
            metadata = mapOf("source" to "test")
        )
        val json = original.toJson()
        val restored = SignedPayload.fromJson(json)
        assertNotNull(restored)
        assertEquals(original.version, restored!!.version)
        assertEquals(original.timestamp, restored.timestamp)
        assertEquals(original.dataHash, restored.dataHash)
        assertEquals(original.merkleRoot, restored.merkleRoot)
        assertEquals(original.signature, restored.signature)
        assertEquals(original.metadata.size, restored.metadata.size)
    }
}
