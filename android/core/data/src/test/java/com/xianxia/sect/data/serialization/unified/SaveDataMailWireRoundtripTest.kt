package com.xianxia.sect.data.serialization.unified

import com.xianxia.sect.core.model.MailEntity
import com.xianxia.sect.data.compression.DataCompressor
import com.xianxia.sect.data.model.SaveData
import com.xianxia.sect.data.serialization.NullSafeProtoBuf
import kotlinx.serialization.serializer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * SR-1 存档回归：SaveData.mails（proto tag 56）wire 面逐字段等价实跑。
 *
 * 方案 §4 SR-1 红线："roundtrip 测试（存→读→逐字段等价，样本须含含附件未领邮件）"。
 * 两条 wire 路径都验：
 * 1. NullSafeProtoBuf 直序列化（proto 裸 wire，`SaveDataDirectSerializationTest` 同构）；
 * 2. 生产同路径 `serialize（PROTOBUF）→ LZ4 + checksum → deserialize`
 *    （= `SerializationModule.serializeAndCompressSaveData` 的 SerializationContext，
 *    DI 外壳直构等价复现，口径同 `CloudPayloadSizeBenchTest`）。
 *
 * 兼容红线同验：旧档无 mails 字段 ⇒ 反序列化默认空表（向后兼容单向）。
 */
@RunWith(org.robolectric.RobolectricTestRunner::class)
@Config(sdk = [34])
class SaveDataMailWireRoundtripTest {

    private companion object {
        const val T0 = 1_760_000_000_000L // 固定基准时刻（不依赖墙钟）

        /** 样本一（红线指定形态）：**含附件未领**——attachmentClaimed=false，附件 JSON 两条 */
        val MAIL_WITH_UNCLAIMED_ATTACHMENT = MailEntity(
            id = "mail-attach-unclaimed",
            slotId = 3,
            source = "system",
            mailType = "compensation",
            title = "宗门补给",
            content = "掌门亲启：本月宗门补给已随信附上。",
            senderName = "太上长老",
            sendTime = T0,
            expireTime = T0 + 30L * 24 * 3600 * 1000,
            isRead = false,
            attachmentClaimed = false,
            hasAttachment = true,
            attachments = "[{\"type\":\"material\",\"name\":\"灵石\",\"quantity\":1000," +
                "\"rarity\":4,\"itemId\":\"stone_r4\"}," +
                "{\"type\":\"pill\",\"name\":\"筑基丹\",\"quantity\":3,\"extra\":{\"bind\":\"true\"}}]"
        )

        /** 样本二：已读已领、无附件、全默认 sender/source/type */
        val MAIL_READ_AND_CLAIMED = MailEntity(
            id = "mail-read-claimed",
            slotId = 3,
            title = "开宗贺礼",
            content = "已领取的开宗贺礼。",
            sendTime = T0 - 1000,
            isRead = true,
            attachmentClaimed = true,
            hasAttachment = false,
            attachments = "[]"
        )

        /** 样本三：未读未领、永久有效（expireTime=0）、带远端 id（溢出/直发链路形态） */
        val MAIL_PERMANENT_WITH_REMOTE_ID = MailEntity(
            id = "mail-overflow-remote",
            slotId = 3,
            source = "overflow",
            mailType = "player",
            title = "跨宗来件",
            content = "来自散修的问候。",
            senderName = "无名散修",
            sendTime = T0 - 2000,
            expireTime = 0,
            remoteMailId = "remote-mail-xyz-42"
        )

        /** 样本四：过期未被惰性清理（快照如实携带表内现状——30 天删除语义归 SR-5） */
        val MAIL_EXPIRED_NOT_YET_SWEPT = MailEntity(
            id = "mail-expired-resident",
            slotId = 3,
            title = "过期滞留件",
            content = "过期删除是惰性清理，快照时可能仍在表内。",
            sendTime = T0 - 40L * 24 * 3600 * 1000,
            expireTime = T0 - 10L * 24 * 3600 * 1000,
            isRead = true,
            hasAttachment = true,
            attachmentClaimed = true
        )

        /** 样本五：近乎全默认（title/content 空、零时刻、未读）——默认值 wire 行为面 */
        val MAIL_MINIMAL = MailEntity(id = "mail-minimal", slotId = 3)

        fun sampleMails(): List<MailEntity> = listOf(
            MAIL_WITH_UNCLAIMED_ATTACHMENT,
            MAIL_READ_AND_CLAIMED,
            MAIL_PERMANENT_WITH_REMOTE_ID,
            MAIL_EXPIRED_NOT_YET_SWEPT,
            MAIL_MINIMAL
        )

        fun saveDataWith(mails: List<MailEntity>): SaveData = SaveData(
            gameData = com.xianxia.sect.core.model.GameData(sectName = "邮件回归宗", gameYear = 9),
            disciples = emptyList(),
            pills = emptyList(),
            materials = emptyList(),
            herbs = emptyList(),
            seeds = emptyList(),
            mails = mails
        )
    }

    private val engine = UnifiedSerializationEngine(DataCompressor())

    // ── 逐字段等价断言（红线核心样本：含附件未领邮件）──────────────────────

    private fun assertMailFieldsEqual(expected: MailEntity, actual: MailEntity, label: String) {
        assertEquals("$label.id", expected.id, actual.id)
        assertEquals("$label.slotId", expected.slotId, actual.slotId)
        assertEquals("$label.source", expected.source, actual.source)
        assertEquals("$label.mailType", expected.mailType, actual.mailType)
        assertEquals("$label.title", expected.title, actual.title)
        assertEquals("$label.content", expected.content, actual.content)
        assertEquals("$label.senderName", expected.senderName, actual.senderName)
        assertEquals("$label.sendTime", expected.sendTime, actual.sendTime)
        assertEquals("$label.expireTime", expected.expireTime, actual.expireTime)
        assertEquals("$label.isRead", expected.isRead, actual.isRead)
        assertEquals("$label.attachmentClaimed", expected.attachmentClaimed, actual.attachmentClaimed)
        assertEquals("$label.hasAttachment", expected.hasAttachment, actual.hasAttachment)
        assertEquals("$label.attachments", expected.attachments, actual.attachments)
        assertEquals("$label.remoteMailId", expected.remoteMailId, actual.remoteMailId)
    }

    private fun assertMailsRoundtrip(restored: SaveData) {
        val expected = sampleMails()
        assertEquals("邮件条数等价", expected.size, restored.mails.size)
        // 顺序等价（proto repeated 保序——DB 读序即快照序）
        expected.zip(restored.mails).forEach { (e, a) -> assertMailFieldsEqual(e, a, "mails") }
        // 整体 data-class 等价兜底（新增字段漏断言时仍红）
        assertEquals(expected, restored.mails)
    }

    @Test
    fun `proto wire roundtrip keeps mails field-by-field`() {
        val original = saveDataWith(sampleMails())

        val bytes = NullSafeProtoBuf.protoBuf.encodeToByteArray(serializer<SaveData>(), original)
        val restored = NullSafeProtoBuf.protoBuf.decodeFromByteArray(serializer<SaveData>(), bytes)

        assertMailsRoundtrip(restored)
        assertEquals("其余域不受影响（宗门名）", "邮件回归宗", restored.gameData.sectName)
    }

    @Test
    fun `production wire protobuf+lz4+checksum roundtrip keeps mails field-by-field`() {
        val original = saveDataWith(sampleMails())

        val context = SerializationContext(
            format = SerializationFormat.PROTOBUF,
            compression = CompressionType.LZ4,
            compressThreshold = 1024,
            includeChecksum = true
        )
        val payload = engine.serialize(original, context, serializer<SaveData>()).data
        assertTrue("payload 应非空", payload != null && payload.isNotEmpty())

        val restored = engine.deserialize(payload!!, context, serializer<SaveData>())
        assertEquals("生产 wire 校验和通过", true, restored.checksumValid)
        assertMailsRoundtrip(restored.data ?: error("生产 wire 反序列化失败: ${restored.error}"))
    }

    // ── 兼容红线：旧档无 mails 字段 ⇒ 默认空表────────────────────────────

    @Test
    fun `legacy save without mails field deserializes to empty list`() {
        // 旧档形态 = SaveData 构造不含 mails（缺 tag 56）
        val legacy = SaveData(
            gameData = com.xianxia.sect.core.model.GameData(sectName = "旧档宗", gameYear = 2),
            disciples = emptyList(),
            pills = emptyList(),
            materials = emptyList(),
            herbs = emptyList(),
            seeds = emptyList()
        )

        val bytes = NullSafeProtoBuf.protoBuf.encodeToByteArray(serializer<SaveData>(), legacy)
        val restored = NullSafeProtoBuf.protoBuf.decodeFromByteArray(serializer<SaveData>(), bytes)

        assertEquals("旧档 mails 默认空表（向后兼容单向）", emptyList<MailEntity>(), restored.mails)
        assertEquals("旧档其余字段照常解码", "旧档宗", restored.gameData.sectName)
    }

    @Test
    fun `empty mails omit from wire and roundtrip to empty list`() {
        // 新档零邮件 = 空表（kotlinx proto 空 repeated 不编码）⇒ 反序列化空表，
        // 与"旧档缺字段"同形——写侧整对象替换语义据此统一（旧档/零邮件 ⇒ 表空）。
        val empty = saveDataWith(emptyList())

        val bytes = NullSafeProtoBuf.protoBuf.encodeToByteArray(serializer<SaveData>(), empty)
        val restored = NullSafeProtoBuf.protoBuf.decodeFromByteArray(serializer<SaveData>(), bytes)

        assertTrue(restored.mails.isEmpty())
    }
}
