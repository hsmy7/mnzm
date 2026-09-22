package com.xianxia.sect.data.crypto

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * `SecureKeyManager.getOrCreateKey` 的**别名契约**回归网（2026-09-22 根治笔）。
 *
 * 被钉死的缺陷：该方法曾直接返回进程级缓存里的密钥数组引用，而
 * `RequestSigner.deriveLocalSigningKey`（`RequestSigner.kt:184`）与
 * `SecureHttpClient.decryptResponse`（`SecureHttpClient.kt:425`）都按"清自己副本"的
 * 意图写着 `masterKey.fill(0)` —— 在别名语义下那等于**把全零密钥写回缓存**；
 * 又因缓存是"命中即续期"的滑动 TTL，只要还有取键流量，零值可无限期存活，
 * 连带污染响应解密密钥、云档载荷签名（[SavePayloadSigner]），
 * 并让 [SecureKeyManager.verifyKeyIntegrity] 误报密钥丢失。
 *
 * 本文件是三行复现配方的转正：任一断言回红即表示别名回归。
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class SecureKeyManagerKeyAliasTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `两次取键内容相同但引用不同（返回副本）`() {
        // 先预热缓存：否则"首次 miss + 次次 hit"本身就产生两个不同引用，断言无判别力
        SecureKeyManager.getOrCreateKey(context)
        val first = SecureKeyManager.getOrCreateKey(context)
        val second = SecureKeyManager.getOrCreateKey(context)

        assertArrayEquals("TTL 内主密钥内容必须稳定", first, second)
        assertNotSame(
            "getOrCreateKey 不得交出缓存别名——交出引用即交出全局可变状态",
            first, second
        )
    }

    @Test
    fun `调用方擦除自己那份不影响他方`() {
        val pristine = SecureKeyManager.getOrCreateKey(context).copyOf()
        assertTrue("前置：主密钥不得为全零", pristine.any { it != 0.toByte() })

        // 复现旧缺陷的写法：调用方按"清本地副本"的意图就地擦除
        val mine = SecureKeyManager.getOrCreateKey(context)
        mine.fill(0)

        val after = SecureKeyManager.getOrCreateKey(context)
        assertTrue(
            "他方擦除后再次取键得到全零 ⇒ 缓存被别名写污染（该缺陷已根治，回红即回归）",
            after.any { it != 0.toByte() }
        )
        assertArrayEquals("内容必须与擦除前一致", pristine, after)
    }

    @Test
    fun `交替取键（两路调用方并存）内容稳定`() {
        // 模拟网络签名链与云档签名链交替取键：任一方擦除自己的副本都不得改变全局内容。
        // baseline 必须是**快照副本**——别名语义下 baseline 会与缓存同体，
        // 一起被清零后断言会退化成"全零 == 全零"的空转（本次验修过程实测到这一点）。
        val baseline = SecureKeyManager.getOrCreateKey(context).copyOf()
        assertTrue("前置：主密钥不得为全零", baseline.any { it != 0.toByte() })

        repeat(3) { round ->
            SecureKeyManager.getOrCreateKey(context).fill(0)
            SecureKeyManager.getOrCreateKey(context).fill(0)
            assertArrayEquals(
                "第 $round 轮两路调用方各自擦除后内容漂移",
                baseline,
                SecureKeyManager.getOrCreateKey(context)
            )
        }
    }
}
