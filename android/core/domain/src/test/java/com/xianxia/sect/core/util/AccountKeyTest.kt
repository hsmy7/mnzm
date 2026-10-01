package com.xianxia.sect.core.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * 账号数据空间隔离键派生规则守卫（SS2）。
 *
 * 派生规则跨端契约（iOS CryptoKit 同规则）：
 * UTF-8 → SHA-256 → 小写十六进制 → 截断前 [AccountKey.KEY_HEX_CHARS] 位。
 */
class AccountKeyTest {

    @Test
    fun `derive is deterministic for the same identifier`() {
        assertEquals(AccountKey.derive("union-abc"), AccountKey.derive("union-abc"))
    }

    @Test
    fun `derive produces fixed-length lowercase hex`() {
        val key = AccountKey.derive("union-abc")
        assertEquals(AccountKey.KEY_HEX_CHARS, key.length)
        assertTrue("派生结果必须是纯小写十六进制: $key", key.all { it.isDigit() || it in 'a'..'f' })
    }

    @Test
    fun `derive differs for different identifiers`() {
        assertNotEquals(AccountKey.derive("union-abc"), AccountKey.derive("union-xyz"))
    }

    @Test
    fun `derive is strict about raw identifier bytes`() {
        // 跨端契约（iOS 同规则）对原始字节做哈希，无任何归一——
        // 前后空白是不同字节序列，必须派生不同 key（否则双端目录对不上）
        assertNotEquals(AccountKey.derive("union-abc"), AccountKey.derive(" union-abc"))
    }

    @Test
    fun `derive rejects blank identifier with actionable message`() {
        listOf("", "   ", "\t\n").forEach { blank ->
            try {
                AccountKey.derive(blank)
                fail("空白标识必须抛 IllegalArgumentException，实际输入: \"$blank\"")
            } catch (expected: IllegalArgumentException) {
                assertTrue(
                    "异常消息必须指向『无账号不得建数据空间』操作语义",
                    expected.message!!.contains("无账号不得建数据空间")
                )
            }
        }
    }

    @Test
    fun `derive handles multibyte identifiers without cross-platform drift`() {
        // 中文与 emoji 标识走 UTF-8 编码——与 iOS Data(identifier.utf8) 同字节序列
        val key = AccountKey.derive("旅行者-🎮-3309")
        assertEquals(AccountKey.KEY_HEX_CHARS, key.length)
        assertTrue(AccountKey.isValid(key))
    }

    @Test
    fun `isValid accepts derived keys`() {
        assertTrue(AccountKey.isValid(AccountKey.derive("union-abc")))
    }

    @Test
    fun `isValid rejects malformed keys with no false positives`() {
        val key = AccountKey.derive("union-abc")
        assertFalse(AccountKey.isValid(""))                              // 空串
        assertFalse(AccountKey.isValid(key.dropLast(1)))                 // 缺一位
        assertFalse(AccountKey.isValid(key + "0"))                       // 多一位
        assertFalse(AccountKey.isValid(key.uppercase()))                 // 大写十六进制非法（目录大小写敏感面）
        assertFalse(AccountKey.isValid(key.drop(1) + "g"))               // 非法字符
        assertFalse(AccountKey.isValid("../etc/passwd"))                 // 路径穿越串
    }
}
