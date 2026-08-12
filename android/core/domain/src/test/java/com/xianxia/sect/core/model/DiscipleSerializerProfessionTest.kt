package com.xianxia.sect.core.model

import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.encodeToByteArray
import kotlinx.serialization.protobuf.ProtoBuf
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 炼丹师/锻造师职业字段序列化测试（2026-08-09 职业系统）。
 *
 * 覆盖：4 个职业字段（alchemyLevel/alchemyPromotionCount/forgeLevel/forgePromotionCount）
 * round-trip 相等；旧档缺省解码为 0（无职业）。
 */
class DiscipleSerializerProfessionTest {

    private val proto = ProtoBuf

    @Test
    fun `profession fields round-trip preserve levels and counts`() {
        val disciple = Disciple(
            id = "d1",
            name = "炼丹弟子",
            skills = SkillStats(
                pillRefining = 80,
                artifactRefining = 90,
                alchemyLevel = 2,
                alchemyPromotionCount = 500,
                forgeLevel = 3,
                forgePromotionCount = 800
            )
        )

        val bytes = proto.encodeToByteArray(Disciple.serializer(), disciple)
        val decoded = proto.decodeFromByteArray<Disciple>(bytes)

        assertEquals(2, decoded.skills.alchemyLevel)
        assertEquals(500, decoded.skills.alchemyPromotionCount)
        assertEquals(3, decoded.skills.forgeLevel)
        assertEquals(800, decoded.skills.forgePromotionCount)
        // 非职业字段不受影响
        assertEquals(80, decoded.skills.pillRefining)
        assertEquals("d1", decoded.id)
    }

    @Test
    fun `default disciple decodes to no profession`() {
        val disciple = Disciple(id = "d2", name = "新弟子")
        val bytes = proto.encodeToByteArray(Disciple.serializer(), disciple)
        val decoded = proto.decodeFromByteArray<Disciple>(bytes)

        assertEquals("旧档/新弟子缺省无职业", 0, decoded.skills.alchemyLevel)
        assertEquals(0, decoded.skills.alchemyPromotionCount)
        assertEquals(0, decoded.skills.forgeLevel)
        assertEquals(0, decoded.skills.forgePromotionCount)
    }

    @Test
    fun `grand master level survives round-trip`() {
        val disciple = Disciple(
            id = "d3",
            skills = SkillStats(alchemyLevel = 5, forgeLevel = 5)
        )
        val bytes = proto.encodeToByteArray(Disciple.serializer(), disciple)
        val decoded = proto.decodeFromByteArray<Disciple>(bytes)

        assertEquals("丹圣等级 round-trip 不丢", 5, decoded.skills.alchemyLevel)
        assertEquals(5, decoded.skills.forgeLevel)
    }

    // ---- 资质（2026-08-12 新增固定属性，@ProtoNumber(110) + @EncodeDefault ALWAYS）----

    @Test
    fun `aptitude round-trip preserves value`() {
        val disciple = Disciple(
            id = "d4",
            skills = SkillStats(aptitude = 120)
        )
        val bytes = proto.encodeToByteArray(Disciple.serializer(), disciple)
        val decoded = proto.decodeFromByteArray<Disciple>(bytes)

        assertEquals("资质 round-trip 不丢", 120, decoded.skills.aptitude)
    }

    @Test
    fun `aptitude defaults to 50 for old save (sentinel for self-heal)`() {
        // 旧档（无字段 110）解码 → 默认 50（自愈哨兵；@EncodeDefault ALWAYS
        // 保证 encode 时总是写入，不会因 encodeDefaults=false 丢字段）
        val disciple = Disciple(id = "d5", name = "旧档弟子")
        val bytes = proto.encodeToByteArray(Disciple.serializer(), disciple)
        val decoded = proto.decodeFromByteArray<Disciple>(bytes)

        assertEquals("缺省资质应为自愈哨兵 50", 50, decoded.skills.aptitude)
    }
}
