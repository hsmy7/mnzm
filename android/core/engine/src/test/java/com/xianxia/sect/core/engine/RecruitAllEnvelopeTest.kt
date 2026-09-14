package com.xianxia.sect.core.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 一键招募 native 信封解析测试（AUTHORITATIVE 下 GameEngine.recruitAllFromList
 * 经 native 执行——信封解析纯函数直接验证；字节为空/非法 JSON 回退 Kotlin 实现）。
 */
class RecruitAllEnvelopeTest {

    @Test
    fun `parse - 成功信封映射 count`() {
        val raw = """{"ok":true,"count":2,"reason":"SUCCESS"}""".encodeToByteArray()

        val envelope = parseRecruitAllEnvelope(raw)

        assertTrue(envelope != null)
        assertEquals(true, envelope!!.ok)
        assertEquals(2, envelope.count)
        assertEquals("SUCCESS", envelope.reason)
    }

    @Test
    fun `parse - 空结果无候选仍为成功（ok=true, count=0）`() {
        val raw = """{"ok":true,"count":0,"reason":"SUCCESS"}""".encodeToByteArray()

        val envelope = parseRecruitAllEnvelope(raw)

        assertEquals(true, envelope!!.ok)
        assertEquals(0, envelope.count)
    }

    @Test
    fun `parse - 字节为空返回 null（回退 Kotlin 原实现）`() {
        assertNull(parseRecruitAllEnvelope(ByteArray(0)))
    }

    @Test
    fun `parse - 非法 JSON 返回 null（回退 Kotlin 原实现）`() {
        assertNull(parseRecruitAllEnvelope("not json".encodeToByteArray()))
    }

    @Test
    fun `parse - 未知字段宽松忽略`() {
        val raw = """{"ok":true,"count":1,"reason":"SUCCESS","extra":true}""".encodeToByteArray()
        val envelope = parseRecruitAllEnvelope(raw)
        assertEquals(1, envelope!!.count)
    }
}
