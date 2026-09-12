package com.xianxia.sect.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [ElderSlots.resolvePositionName] 全分支测试：
 * 10 长老槽位 → 职位名、3 弟子列表 → 职务名、双槽冲突 → 长老优先、无职位 → null。
 */
class ElderSlotsPositionNameTest {

    private fun slot(id: String) = DirectDiscipleSlot(discipleId = id)

    private val noSlots = ElderSlots()

    @Test
    fun `viceSectMaster returns 副宗主`() {
        val slots = noSlots.copy(viceSectMaster = "d1")
        assertEquals("副宗主", slots.resolvePositionName("d1"))
    }

    @Test
    fun `herbGardenElder returns 灵田长老`() {
        val slots = noSlots.copy(herbGardenElder = "d2")
        assertEquals("灵田长老", slots.resolvePositionName("d2"))
    }

    @Test
    fun `alchemyElder returns 炼丹长老`() {
        val slots = noSlots.copy(alchemyElder = "d3")
        assertEquals("炼丹长老", slots.resolvePositionName("d3"))
    }

    @Test
    fun `forgeElder returns 炼器长老`() {
        val slots = noSlots.copy(forgeElder = "d4")
        assertEquals("炼器长老", slots.resolvePositionName("d4"))
    }

    @Test
    fun `outerElder returns 外门长老`() {
        val slots = noSlots.copy(outerElder = "d5")
        assertEquals("外门长老", slots.resolvePositionName("d5"))
    }

    @Test
    fun `preachingElder returns 传道长老`() {
        val slots = noSlots.copy(preachingElder = "d6")
        assertEquals("传道长老", slots.resolvePositionName("d6"))
    }

    @Test
    fun `lawEnforcementElder returns 执法长老`() {
        val slots = noSlots.copy(lawEnforcementElder = "d7")
        assertEquals("执法长老", slots.resolvePositionName("d7"))
    }

    @Test
    fun `innerElder returns 内门长老`() {
        val slots = noSlots.copy(innerElder = "d8")
        assertEquals("内门长老", slots.resolvePositionName("d8"))
    }

    @Test
    fun `recruitingElder returns 纳徒长老`() {
        val slots = noSlots.copy(recruitingElder = "d9")
        assertEquals("纳徒长老", slots.resolvePositionName("d9"))
    }

    @Test
    fun `qingyunPreachingElder returns 青云传道长老`() {
        val slots = noSlots.copy(qingyunPreachingElder = "d10")
        assertEquals("青云传道长老", slots.resolvePositionName("d10"))
    }

    @Test
    fun `herbGardenDisciples returns 灵植弟子`() {
        val slots = noSlots.copy(herbGardenDisciples = listOf(slot("d11")))
        assertEquals("灵植弟子", slots.resolvePositionName("d11"))
    }

    @Test
    fun `alchemyDisciples returns 炼丹弟子`() {
        val slots = noSlots.copy(alchemyDisciples = listOf(slot("d12")))
        assertEquals("炼丹弟子", slots.resolvePositionName("d12"))
    }

    @Test
    fun `forgeDisciples returns 锻造弟子`() {
        val slots = noSlots.copy(forgeDisciples = listOf(slot("d13")))
        assertEquals("锻造弟子", slots.resolvePositionName("d13"))
    }

    @Test
    fun `elder slot wins over disciple list when same disciple in both`() {
        val slots = noSlots.copy(
            alchemyElder = "d14",
            alchemyDisciples = listOf(slot("d14"))
        )
        assertEquals("炼丹长老", slots.resolvePositionName("d14"))
    }

    @Test
    fun `viceSectMaster wins over other elder slots`() {
        val slots = noSlots.copy(
            viceSectMaster = "d15",
            herbGardenElder = "d15",
            forgeElder = "d15"
        )
        assertEquals("副宗主", slots.resolvePositionName("d15"))
    }

    @Test
    fun `non-managing slots do not resolve position`() {
        val slots = noSlots.copy(
            preachingMasters = listOf(slot("d16")),
            lawEnforcementDisciples = listOf(slot("d17")),
            qingyunPreachingMasters = listOf(slot("d18")),
            spiritMineDeaconDisciples = listOf(slot("d19"))
        )
        assertNull("传道弟子应走 PREACHING 状态而非 MANAGING 职位名", slots.resolvePositionName("d16"))
        assertNull("执法弟子应走 LAW_ENFORCING 状态而非 MANAGING 职位名", slots.resolvePositionName("d17"))
        assertNull("青云传道弟子应走 PREACHING 状态而非 MANAGING 职位名", slots.resolvePositionName("d18"))
        assertNull("灵矿执事应走 DEACONING 状态而非 MANAGING 职位名", slots.resolvePositionName("d19"))
    }

    @Test
    fun `unknown disciple returns null`() {
        val slots = noSlots.copy(viceSectMaster = "d20")
        assertNull(slots.resolvePositionName("d99"))
        assertNull(noSlots.resolvePositionName(""))
    }
}
