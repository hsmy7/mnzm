package com.xianxia.sect.core.engine.domain.disciple

import com.xianxia.sect.core.GameConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 手动招募 native 信封解析/文案映射测试（双实现并行契约：
 * AUTHORITATIVE 下 DiscipleFacadeImpl 经 native 执行手动招募——信封解析
 * 与失败文案必须与 Kotlin 实现逐字一致；JVM 测试环境无 native 库，
 * 纯函数直接验证）。
 */
class ManualRecruitEnvelopeTest {

    @Test
    fun `parse - 成功信封全字段映射`() {
        val raw = """{"ok":true,"newId":"34","age":16,"name":"候选招募","reason":"SUCCESS"}"""
            .encodeToByteArray()

        val envelope = parseManualRecruitEnvelope(raw)

        assertTrue(envelope != null)
        assertEquals(true, envelope!!.ok)
        assertEquals("34", envelope.newId)
        assertEquals(16, envelope.age)
        assertEquals("候选招募", envelope.name)
        assertEquals("SUCCESS", envelope.reason)
    }

    @Test
    fun `parse - 字节为空返回 null（回退 Kotlin 原实现）`() {
        assertNull(parseManualRecruitEnvelope(ByteArray(0)))
    }

    @Test
    fun `parse - 非法 JSON 返回 null（回退 Kotlin 原实现）`() {
        assertNull(parseManualRecruitEnvelope("not json".encodeToByteArray()))
    }

    @Test
    fun `parse - 未知字段宽松忽略（防 C++ 协议扩展破坏）`() {
        val raw = """{"ok":true,"newId":"34","age":16,"name":"x","reason":"SUCCESS","extra":1}"""
            .encodeToByteArray()

        val envelope = parseManualRecruitEnvelope(raw)

        assertEquals(true, envelope!!.ok)
        assertEquals("34", envelope.newId)
    }

    // ── 失败文案（与 Kotlin 实现逐字一致） ─────────────────────

    @Test
    fun `failureMessage - MONTHLY_LIMIT 对齐原文案`() {
        val envelope = ManualRecruitEnvelope(ok = false, reason = "MONTHLY_LIMIT")
        assertEquals(
            "本月招募已达上限（${GameConfig.RECRUIT_MONTHLY_LIMIT}人）",
            envelope.failureMessage()
        )
    }

    @Test
    fun `failureMessage - NOT_FOUND 对齐原文案`() {
        val envelope = ManualRecruitEnvelope(ok = false, reason = "NOT_FOUND")
        assertEquals("招募失败：该弟子已不在招募列表中", envelope.failureMessage())
    }

    @Test
    fun `failureMessage - CORRUPTED 带名字`() {
        val envelope = ManualRecruitEnvelope(ok = false, name = "张三", reason = "CORRUPTED")
        assertEquals("招募失败：「张三」数据异常", envelope.failureMessage())
    }

    @Test
    fun `failureMessage - 超长名字截断 30 字符`() {
        val longName = "长".repeat(40)
        val envelope = ManualRecruitEnvelope(ok = false, name = longName, reason = "CORRUPTED")
        assertEquals(
            "招募失败：「${longName.take(30)}」数据异常",
            envelope.failureMessage()
        )
    }

    @Test
    fun `failureMessage - 未知 reason 回退通用数据异常`() {
        val envelope = ManualRecruitEnvelope(ok = false, name = "李四", reason = "???")
        assertEquals("招募失败：「李四」数据异常", envelope.failureMessage())
    }
}
