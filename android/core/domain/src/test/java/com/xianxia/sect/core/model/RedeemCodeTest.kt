package com.xianxia.sect.core.model

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 兑换码模型层守卫（G08 兑换码改道后的测试面）。
 *
 * 守护两件事：
 * 1. [RedeemRewardType] 的**取值集合与声明序**——弟子实例不再由兑换码直造，
 *    `DISCIPLE`/`STARTER_PACK` 已删除、`FRAGMENT` 补位；声明序是存档安全红线，
 *    详见 [redeemRewardType_wireValueIsOrdinalPlusOne_insertMustAppendAtTail]。
 * 2. [RedeemCode] 只以 `templateId` 携带角色奖励，[RedeemResult] 不再携带弟子对象。
 */
class RedeemCodeTest {

    // ---- RedeemRewardType ----

    /** 改道后的取值全集（声明序即本表顺序，禁止调整）。 */
    private val expectedOrder = listOf(
        RedeemRewardType.SPIRIT_STONES,
        RedeemRewardType.EQUIPMENT,
        RedeemRewardType.MANUAL,
        RedeemRewardType.PILL,
        RedeemRewardType.MATERIAL,
        RedeemRewardType.HERB,
        RedeemRewardType.SEED,
        RedeemRewardType.FRAGMENT,
        RedeemRewardType.MANUAL_PACK
    )

    @Test
    fun redeemRewardType_afterDiscipleRewardsRetired_hasNineValues() {
        assertEquals(
            "兑换码奖励类型必须是 9 个（G08 删 DISCIPLE/STARTER_PACK、加 FRAGMENT）。" +
                "增删取值请同步改本期望表与 RedeemCodeManager.generateReward 的 when 穷举",
            9,
            RedeemRewardType.entries.size
        )
    }

    @Test
    fun redeemRewardType_values_survivingOrderUnchangedAndManualPackStaysTail() {
        val expected = expectedOrder.toTypedArray()
        assertArrayEquals(
            "声明序与期望表不一致：存续的 7 个资源类取值相对序必须保持不变，" +
                "且 MANUAL_PACK 必须仍是末位（新增取值只能追加在其后）。" +
                "落点：core/domain/.../model/RedeemCode.kt 的 RedeemRewardType",
            expected,
            RedeemRewardType.entries.toTypedArray()
        )
        assertEquals(
            "MANUAL_PACK 必须是末位取值——插到中间会前移其后取值的序号",
            RedeemRewardType.MANUAL_PACK,
            RedeemRewardType.entries.last()
        )
    }

    @Test
    fun redeemRewardType_wireValueIsOrdinalPlusOne_insertMustAppendAtTail() {
        // 本仓存在「枚举按 ordinal+1 上 wire」的先例
        // （core/data/.../serialization/backwardcompat/OldSerializableSaveData.kt:522 一族），
        // 因此枚举的**声明序本身**就是存档面资产：删/插中间项会让其后所有取值的
        // 序号整体前移/后移，旧档静默错位。
        // RedeemRewardType 当前不落 Room 也不进 ProtoBuf（见其 KDoc），
        // 本断言把现状钉死：一旦将来上 wire，只有满足「末位追加」的改动才不会伤旧档。
        val expectedWireValues = mapOf(
            "SPIRIT_STONES" to 1,
            "EQUIPMENT" to 2,
            "MANUAL" to 3,
            "PILL" to 4,
            "MATERIAL" to 5,
            "HERB" to 6,
            "SEED" to 7,
            // FRAGMENT 占用 DISCIPLE 退役后腾出的 8 号位，不是新末尾
            "FRAGMENT" to 8,
            // STARTER_PACK 删除使 MANUAL_PACK 由 10 前移到 9（本枚举不落 wire，故无害）
            "MANUAL_PACK" to 9
        )
        val actual = RedeemRewardType.entries.associate { it.name to it.ordinal + 1 }
        assertEquals(
            "ordinal+1 映射与期望表不一致。新增取值请追加在 MANUAL_PACK 之后（序号 10 起）；" +
                "若确实需要删除或插入中间项，必须先确认该枚举仍未落任何 wire 面，" +
                "并在 commit 说明里写明。落点：core/domain/.../model/RedeemCode.kt",
            expectedWireValues,
            actual
        )
    }

    @Test
    fun redeemRewardType_discipleRewardTypesRemoved() {
        val retired = setOf("DISCIPLE", "STARTER_PACK")
        val present = RedeemRewardType.entries.map { it.name }.toSet()
        assertFalse(
            "兑换码不得再发放弟子实例（弟子只能由角色模板实例化产生）。" +
                "若确需恢复，先改 RedeemCodeManager 的发放臂与 c340-3 测试面，落点：core/domain/.../model/RedeemCode.kt",
            present.any { it in retired }
        )
        assertTrue("角色类奖励一律以 FRAGMENT 发放", RedeemRewardType.FRAGMENT.name in present)
    }

    // ---- RedeemCode ----

    @Test
    fun redeemCode_construction() {
        val code = RedeemCode(
            code = "TEST2024",
            rewardType = RedeemRewardType.SPIRIT_STONES,
            quantity = 1000,
            rarity = 2,
            maxUses = 10,
            usedCount = 0,
            isEnabled = true
        )
        assertEquals("TEST2024", code.code)
        assertEquals(RedeemRewardType.SPIRIT_STONES, code.rewardType)
        assertEquals(1000, code.quantity)
        assertEquals(2, code.rarity)
        assertEquals(10, code.maxUses)
        assertEquals(0, code.usedCount)
        assertTrue(code.isEnabled)
        assertNull(code.expireYear)
        assertNull(code.expireMonth)
        assertNull("非角色类奖励不带模板 id", code.templateId)
    }

    @Test
    fun redeemCode_defaultValues() {
        val code = RedeemCode(
            code = "ABC",
            rewardType = RedeemRewardType.PILL
        )
        assertEquals(1, code.quantity)
        assertEquals(1, code.rarity)
        assertEquals(1, code.maxUses)
        assertEquals(0, code.usedCount)
        assertTrue(code.isEnabled)
        assertNull(code.templateId)
    }

    @Test
    fun redeemCode_isExhausted_whenNotUsed() {
        val code = RedeemCode(
            code = "X",
            rewardType = RedeemRewardType.SPIRIT_STONES,
            maxUses = 5,
            usedCount = 0
        )
        assertFalse(code.isExhausted)
    }

    @Test
    fun redeemCode_isExhausted_whenFullyUsed() {
        val code = RedeemCode(
            code = "X",
            rewardType = RedeemRewardType.SPIRIT_STONES,
            maxUses = 5,
            usedCount = 5
        )
        assertTrue(code.isExhausted)
    }

    @Test
    fun redeemCode_isExhausted_whenOverUsed() {
        val code = RedeemCode(
            code = "X",
            rewardType = RedeemRewardType.SPIRIT_STONES,
            maxUses = 3,
            usedCount = 5
        )
        assertTrue(code.isExhausted)
    }

    @Test
    fun redeemCode_withTemplateId_fragmentRewardCarriesCharacterTemplate() {
        // Given 一个角色碎片码
        val code = RedeemCode(
            code = "FRAGMENT1",
            rewardType = RedeemRewardType.FRAGMENT,
            quantity = 60,
            templateId = CharacterTemplateDb.STARTUP_TEMPLATE_ID
        )
        // When 读取其模板 id
        val templateId = code.templateId
        // Then 必须是模板表里真实存在的角色（未知模板会被发放臂整单拒发）
        assertEquals(RedeemRewardType.FRAGMENT, code.rewardType)
        assertEquals(
            "FRAGMENT 奖励必须携带角色模板 id（取值域见 CharacterTemplateDb.ids）。" +
                "落点：core/domain/.../model/RedeemCode.kt 的 templateId 字段",
            "zhouming",
            templateId
        )
        assertNotNull(
            "模板 id 必须能在 CharacterTemplateDb 查到，否则该码永远兑换不了",
            CharacterTemplateDb.byId(templateId.orEmpty())
        )
    }

    @Test
    fun redeemCode_withExpiry() {
        val code = RedeemCode(
            code = "EXPIRE",
            rewardType = RedeemRewardType.EQUIPMENT,
            expireYear = 2025,
            expireMonth = 12
        )
        assertEquals("expireYear 应原样保留", 2025, code.expireYear)
        assertEquals("expireMonth 应原样保留", 12, code.expireMonth)
    }

    @Test
    fun redeemCode_copy() {
        val original = RedeemCode(
            code = "ORIG",
            rewardType = RedeemRewardType.PILL,
            maxUses = 1
        )
        val copied = original.copy(usedCount = 1)
        assertEquals("ORIG", copied.code)
        assertEquals(1, copied.usedCount)
    }

    // ---- RedeemResult ----

    @Test
    fun redeemResult_successConstruction_rewardsOnly() {
        val result = RedeemResult(
            success = true,
            message = "兑换成功",
            rewards = listOf(RewardSelectedItem("r1", "pill", "Breakthrough Pill", 3, 1))
        )
        assertTrue(result.success)
        assertEquals("兑换成功", result.message)
        assertEquals(1, result.rewards.size)
        assertFalse(result.capacityInsufficient)
    }

    @Test
    fun redeemResult_fragmentRewardEntry_idIsTemplateId() {
        // 服务侧（RedeemCodeService）按条目 id 回查模板 id 入账，此契约由本用例固化
        val reward = RewardSelectedItem(
            id = "suqing",
            type = "fragment",
            name = "苏晴",
            rarity = 1,
            quantity = 30
        )
        val result = RedeemResult(success = true, message = "兑换成功", rewards = listOf(reward))
        assertEquals("碎片条目的 id 即角色模板 id", "suqing", result.rewards.single().id)
        assertTrue(
            "碎片条目 id 必须落在模板表取值域内",
            CharacterTemplateDb.byId(result.rewards.single().id) != null
        )
    }

    @Test
    fun redeemResult_failureConstruction() {
        val result = RedeemResult(
            success = false,
            message = "兑换码已过期"
        )
        assertFalse(result.success)
        assertEquals("兑换码已过期", result.message)
        assertEquals(emptyList<RewardSelectedItem>(), result.rewards)
    }

    @Test
    fun redeemResult_defaultValues() {
        val result = RedeemResult(success = true, message = "ok")
        assertEquals(emptyList<RewardSelectedItem>(), result.rewards)
        assertFalse("默认非容量不足", result.capacityInsufficient)
    }

    @Test
    fun redeemResult_capacityInsufficient_marksRetryable() {
        val result = RedeemResult(
            success = false,
            message = "仓库容量不足",
            capacityInsufficient = true
        )
        assertTrue(result.capacityInsufficient)
    }

    @Test
    fun redeemResult_copy() {
        val original = RedeemResult(success = true, message = "ok")
        val copied = original.copy(message = "new msg")
        assertTrue(copied.success)
        assertEquals("new msg", copied.message)
    }
}
