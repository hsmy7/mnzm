package com.xianxia.sect.data.serialization

import com.xianxia.sect.core.model.DirectDiscipleSlot
import com.xianxia.sect.core.model.ElderSlots
import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoNumber
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * `ElderSlots` 退役字段（tag 9/10）旧档兼容守卫。
 *
 * 执法长老/执法亲传槽位字段已删除并登记 `reserved 9,10`，旧存档（Room `elderSlots` 列
 * 为 base64(ProtoBuf(ElderSlots))、云档为 SaveData 内嵌消息）里的 tag 9/10 必须被
 * **跳过而非抛错**——一旦抛出，`ProtobufConverters.decodeFromBase64` 的兜底会返回
 * 空 `ElderSlots()`，**全部长老任命静默清零**。
 *
 * 本用例用「旧形状」本地模型编出含 tag 9/10 的字节，再用新模型解码，逐字段断言其余
 * 槽位原样保留。它同时锁定 kotlinx ProtoBuf「未知字段号跳过」行为
 * （`SaveDataDirectSerializationTest` 已有 SaveData 级用例，本用例补 Elderslots 级）。
 */
class ElderSlotsRetiredFieldCompatTest {

    @Test
    fun `旧档含退役 tag 9 与 10 时其余槽位逐字段解码保留`() {
        val legacy = LegacyElderSlots(
            viceSectMaster = "1",
            herbGardenElder = "2",
            alchemyElder = "3",
            forgeElder = "4",
            outerElder = "5",
            preachingElder = "6",
            preachingMasters = listOf(DirectDiscipleSlot(index = 0, discipleId = "7")),
            lawEnforcementElder = "8",
            lawEnforcementDisciples = listOf(DirectDiscipleSlot(index = 0, discipleId = "9")),
            innerElder = "10",
            qingyunPreachingElder = "11",
            qingyunPreachingMasters = listOf(DirectDiscipleSlot(index = 0, discipleId = "12")),
            herbGardenDisciples = listOf(DirectDiscipleSlot(index = 0, discipleId = "13")),
            alchemyDisciples = listOf(DirectDiscipleSlot(index = 0, discipleId = "14")),
            forgeDisciples = listOf(DirectDiscipleSlot(index = 0, discipleId = "15")),
            spiritMineDeaconDisciples = listOf(DirectDiscipleSlot(index = 0, discipleId = "16")),
            recruitingElder = "17"
        )
        val bytes = NullSafeProtoBuf.roomProtoBuf.encodeToByteArray(
            LegacyElderSlots.serializer(), legacy
        )

        val decoded = NullSafeProtoBuf.roomProtoBuf.decodeFromByteArray(ElderSlots.serializer(), bytes)

        assertEquals("1", decoded.viceSectMaster)
        assertEquals("2", decoded.herbGardenElder)
        assertEquals("3", decoded.alchemyElder)
        assertEquals("4", decoded.forgeElder)
        assertEquals("5", decoded.outerElder)
        assertEquals("6", decoded.preachingElder)
        assertEquals(listOf("7"), decoded.preachingMasters.map { it.discipleId })
        assertEquals("10", decoded.innerElder)
        assertEquals("11", decoded.qingyunPreachingElder)
        assertEquals(listOf("12"), decoded.qingyunPreachingMasters.map { it.discipleId })
        assertEquals(listOf("13"), decoded.herbGardenDisciples.map { it.discipleId })
        assertEquals(listOf("14"), decoded.alchemyDisciples.map { it.discipleId })
        assertEquals(listOf("15"), decoded.forgeDisciples.map { it.discipleId })
        assertEquals(listOf("16"), decoded.spiritMineDeaconDisciples.map { it.discipleId })
        assertEquals("17", decoded.recruitingElder)
    }

    @Test
    fun `新形状回环不含退役 tag`() {
        val current = ElderSlots(viceSectMaster = "1", recruitingElder = "2")
        val bytes = NullSafeProtoBuf.roomProtoBuf.encodeToByteArray(ElderSlots.serializer(), current)
        val decoded = NullSafeProtoBuf.roomProtoBuf.decodeFromByteArray(ElderSlots.serializer(), bytes)
        assertEquals(current, decoded)
    }
}

/**
 * 退役前的 `ElderSlots` 形状（tag 9/10 = 执法长老/执法亲传）。
 * 仅测试用：复现旧档字节，不作为生产模型。
 */
@Serializable
private data class LegacyElderSlots(
    @ProtoNumber(1) val viceSectMaster: String = "",
    @ProtoNumber(2) val herbGardenElder: String = "",
    @ProtoNumber(3) val alchemyElder: String = "",
    @ProtoNumber(4) val forgeElder: String = "",
    @ProtoNumber(6) val outerElder: String = "",
    @ProtoNumber(7) val preachingElder: String = "",
    @ProtoNumber(8) val preachingMasters: List<DirectDiscipleSlot> = emptyList(),
    @ProtoNumber(9) val lawEnforcementElder: String = "",
    @ProtoNumber(10) val lawEnforcementDisciples: List<DirectDiscipleSlot> = emptyList(),
    @ProtoNumber(12) val innerElder: String = "",
    @ProtoNumber(13) val qingyunPreachingElder: String = "",
    @ProtoNumber(14) val qingyunPreachingMasters: List<DirectDiscipleSlot> = emptyList(),
    @ProtoNumber(15) val herbGardenDisciples: List<DirectDiscipleSlot> = emptyList(),
    @ProtoNumber(16) val alchemyDisciples: List<DirectDiscipleSlot> = emptyList(),
    @ProtoNumber(17) val forgeDisciples: List<DirectDiscipleSlot> = emptyList(),
    @ProtoNumber(22) val spiritMineDeaconDisciples: List<DirectDiscipleSlot> = emptyList(),
    @ProtoNumber(23) val recruitingElder: String = ""
)
