package com.xianxia.sect.core.engine.service

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 天枢殿重建补偿邮件构造测试。
 *
 * 旧档遗留天枢殿读档删除后，通过 [buildTianshuCompensationMail] 补偿玩家
 * 1000 万灵石并告知最终改动。
 */
class TianshuHallCompensationOpsTest {

    @Test
    fun `buildTianshuCompensationMail_灵石1000万附件与文案`() {
        val mail = buildTianshuCompensationMail(slotId = 2)

        assertEquals("tianshu_hall_rebuild_compensation_v1", mail.id)
        assertEquals("补偿邮件应落在目标存档槽位", 2, mail.slotId)
        assertEquals("compensation", mail.mailType)
        assertEquals("天枢殿重建补偿", mail.title)
        assertEquals("system", mail.source)
        assertTrue("文案应告知补偿金额", mail.content.contains("1000 万") && mail.content.contains("10,000,000"))
        assertTrue("文案应说明天枢殿已拆除", mail.content.contains("已拆除"))
        assertTrue("文案应引导重新建造", mail.content.contains("重新建造"))
        assertTrue("邮件应有附件", mail.hasAttachment)
        assertTrue("有效期应为 7 天", mail.expireTime - mail.sendTime == 7L * 24 * 60 * 60 * 1000)

        val attachments = Json.parseToJsonElement(mail.attachments).jsonArray.map { it.jsonObject }
        assertEquals("附件应只有灵石一项", 1, attachments.size)
        assertEquals("spiritStones", attachments[0]["type"]?.jsonPrimitive?.contentOrNull)
        assertEquals("灵石", attachments[0]["name"]?.jsonPrimitive?.contentOrNull)
        assertEquals("补偿灵石应为 1000 万", 10_000_000, attachments[0]["quantity"]?.jsonPrimitive?.int)
    }
}
