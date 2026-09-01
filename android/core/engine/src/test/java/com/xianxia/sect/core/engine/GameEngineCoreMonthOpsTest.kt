package com.xianxia.sect.core.engine

import com.xianxia.sect.core.model.SecretRealmBackpack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * GameEngineCoreMonthOpsTest — 月变真相源切换批 M-1 信封协议守护。
 *
 * 守护目标：`parseMonthSettlementEnvelope`（nativeSettleMonth 信封解析）——
 * C++ `GameCore::settleMonth` 输出的 JSON 信封（policyCosts.disabledPolicies /
 * secretRealmClose / purchaseLogs）在 Kotlin 侧的反序列化契约：
 * - 缺键/非法 JSON → 宽松默认（空列表 + null 草稿），不抛异常（旧 .so 兼容）
 * - disabledPolicies 解析为政策名列表（事务外 checkpointAllProduction 决策）
 * - secretRealmClose.closed=true 才产生草稿（memberIds + backpack 快照）
 * - purchaseLogs 逐条解析（discipleId/itemName/age）
 */
class GameEngineCoreMonthOpsTest {

    @Test
    fun `parse envelope - empty object yields empty defaults`() {
        val env = parseMonthSettlementEnvelope("{}")
        assertTrue(env.disabledPolicies.isEmpty())
        assertNull(env.secretRealmClose)
        assertTrue(env.purchaseLogs.isEmpty())
    }

    @Test
    fun `parse envelope - invalid json yields empty defaults without throwing`() {
        val env = parseMonthSettlementEnvelope("not-json{{{")
        assertTrue(env.disabledPolicies.isEmpty())
        assertNull(env.secretRealmClose)
        assertTrue(env.purchaseLogs.isEmpty())
    }

    @Test
    fun `parse envelope - disabledPolicies extracted`() {
        val env = parseMonthSettlementEnvelope(
            """{"policyCosts":{"disabledPolicies":["strictTraining","curfew"]}}"""
        )
        assertEquals(listOf("strictTraining", "curfew"), env.disabledPolicies)
        assertNull(env.secretRealmClose)
    }

    @Test
    fun `parse envelope - secretRealmClose present when closed true`() {
        val env = parseMonthSettlementEnvelope(
            """{
              "secretRealmClose": {
                "closed": true,
                "memberIds": ["11", "12"],
                "slotId": 2,
                "backpack": {
                  "spiritStones": 0,
                  "equipment": [],
                  "manuals": [],
                  "pills": [{"id":"p1","name":"聚气丹","quantity":3}],
                  "materials": [],
                  "herbs": [],
                  "seeds": []
                }
              }
            }"""
        )
        val close = env.secretRealmClose
        assertNotNull(close)
        assertEquals(listOf("11", "12"), close!!.memberIds)
        assertEquals(1, close.backpack.pills.size)
        assertEquals("聚气丹", close.backpack.pills[0].name)
        assertEquals(3, close.backpack.pills[0].quantity)
        assertTrue(env.purchaseLogs.isEmpty())
    }

    @Test
    fun `parse envelope - secretRealmClose null when closed false`() {
        val env = parseMonthSettlementEnvelope(
            """{"secretRealmClose":{"closed":false,"memberIds":[],"backpack":{}}}"""
        )
        assertNull(env.secretRealmClose)
    }

    @Test
    fun `parse envelope - secretRealmClose null when absent`() {
        val env = parseMonthSettlementEnvelope("""{"policyCosts":{}}""")
        assertNull(env.secretRealmClose)
    }

    @Test
    fun `parse envelope - purchaseLogs extracted in order`() {
        val env = parseMonthSettlementEnvelope(
            """{"purchaseLogs":[
                {"discipleId":"11","itemName":"聚气丹","age":23},
                {"discipleId":"12","itemName":"青锋剑","age":31}
              ]}"""
        )
        assertEquals(2, env.purchaseLogs.size)
        assertEquals("11", env.purchaseLogs[0].discipleId)
        assertEquals("聚气丹", env.purchaseLogs[0].itemName)
        assertEquals(23, env.purchaseLogs[0].age)
        assertEquals("12", env.purchaseLogs[1].discipleId)
        assertEquals("青锋剑", env.purchaseLogs[1].itemName)
        assertEquals(31, env.purchaseLogs[1].age)
    }

    @Test
    fun `parse envelope - malformed backpack falls back to empty backpack`() {
        val env = parseMonthSettlementEnvelope(
            """{"secretRealmClose":{
                "closed": true,
                "memberIds": ["5"],
                "backpack": {"spiritStones": "not-a-number"}
              }}"""
        )
        val close = env.secretRealmClose
        assertNotNull(close)
        assertEquals(listOf("5"), close!!.memberIds)
        // 宽松解析失败 → SecretRealmBackpack() 空背包（邮件附件为空 → 不发无附件邮件）
        assertEquals(SecretRealmBackpack(), close.backpack)
    }

    @Test
    fun `parse envelope - full envelope combination`() {
        val env = parseMonthSettlementEnvelope(
            """{
              "policyCosts":{"disabledPolicies":["relaxedMgmt"]},
              "secretRealmClose":{"closed":true,"memberIds":["7"],"backpack":{}},
              "purchaseLogs":[{"discipleId":"9","itemName":"回元丹","age":40}]
            }"""
        )
        assertEquals(listOf("relaxedMgmt"), env.disabledPolicies)
        assertNotNull(env.secretRealmClose)
        assertEquals(listOf("7"), env.secretRealmClose!!.memberIds)
        assertEquals(1, env.purchaseLogs.size)
        assertEquals("回元丹", env.purchaseLogs[0].itemName)
    }
}
