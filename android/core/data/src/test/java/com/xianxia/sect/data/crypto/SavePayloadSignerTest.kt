package com.xianxia.sect.data.crypto

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * SR-5 C7：云档载荷 HMAC 签名器语义。
 *
 * 承重项是**签名确定性**与**篡改可检**：签名对象是 LZ4 压缩后的载荷字节，
 * 若序列化/压缩链路存在非确定输出，同一份存档两次签名就会不同 ⇒ 跨设备
 * 读回必判 MISMATCH，整批签名即失效。该前提由本文件的"同字节两次同签"
 * 与真机项（完成报告 pending-device）共同把关。
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class SavePayloadSignerTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val signer = SavePayloadSigner(context)

    private val payload = ByteArray(4096) { (it % 251).toByte() }

    @Test
    fun `签名确定且为 64 位小写 hex`() {
        val first = signer.sign(payload)
        val second = signer.sign(payload)

        org.junit.Assert.assertNotNull("密钥不可得时 sign 才返回 null，本环境应有密钥", first)
        assertEquals("同一字节序列必须签出同一值（跨设备读回才可能验通）", first, second)
        assertEquals(64, first!!.length)
        assertTrue("hex 小写", first.all { it in '0'..'9' || it in 'a'..'f' })
    }

    @Test
    fun `改一个字节即验签失败`() {
        val signature = signer.sign(payload)!!
        assertEquals(SavePayloadIntegrity.VERIFIED, signer.verify(payload, signature))

        val tampered = payload.copyOf().apply { this[100] = (this[100] + 1).toByte() }
        assertNotEquals("载荷域敏感：单字节改动必须改变签名", signature, signer.sign(tampered))
        assertEquals(
            "改过的载荷对不上原签名",
            SavePayloadIntegrity.MISMATCH,
            signer.verify(tampered, signature)
        )
    }

    @Test
    fun `无签名判 UNSIGNED 而非篡改`() {
        // 存量档（SR-2/SR-3 期间上传）与解析失败都走这条——不得误判玩家篡改
        assertEquals(SavePayloadIntegrity.UNSIGNED, signer.verify(payload, null))
        assertEquals(SavePayloadIntegrity.UNSIGNED, signer.verify(payload, ""))
        assertEquals(SavePayloadIntegrity.UNSIGNED, signer.verify(payload, "   "))
    }

    @Test
    fun `签名长度不符即不匹配且不得抛`() {
        // 时序安全比较前置长度判等；截断签名必须判失败而非异常
        val signature = signer.sign(payload)!!
        assertEquals(SavePayloadIntegrity.MISMATCH, signer.verify(payload, signature.drop(8)))
        assertEquals(SavePayloadIntegrity.MISMATCH, signer.verify(payload, "not-hex-at-all"))
    }

    @Test
    fun `签名版本常量随 extra 回带`() {
        assertEquals("hmac-sha256-v1", SavePayloadSigner.SIGNATURE_VERSION)
    }

    @Test
    fun `master 密钥可派生且本类不污染他人副本`() {
        // sign() 的降级分支（返回 null）需要密钥文件系统故障或全零 master 才能复现，
        // 本环境两者都造不出 ⇒ 至少钉住两条前置：
        // ① master 可取且非全零（全零会被 deriveKey 拒绝派生，故障时本用例先红便于归因）；
        // ② 本类派生后，他方取键内容不变（别名契约由 SecureKeyManagerKeyAliasTest 主防，
        //    这里是"签名器站在健康 master 上"的端到端旁证）
        val master = SecureKeyManager.getOrCreateKey(context)
        assertTrue(master.isNotEmpty())
        assertTrue("主密钥全零会被拒绝派生", master.any { it != 0.toByte() })
        val before = master.copyOf()

        signer.sign(payload)

        assertArrayEquals("签名后他方取键内容漂移", before, SecureKeyManager.getOrCreateKey(context))
    }
}
