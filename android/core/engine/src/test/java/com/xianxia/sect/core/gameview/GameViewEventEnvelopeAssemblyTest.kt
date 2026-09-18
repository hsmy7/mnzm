package com.xianxia.sect.core.gameview

import com.xianxia.sect.core.engine.buildMonthEnvelopeFromEvents
import com.xianxia.sect.core.engine.buildYearEnvelopeFromEvents
import com.xianxia.sect.core.engine.parseMonthSettlementEnvelope
import com.xianxia.sect.core.engine.parseYearSettlementEnvelope
import com.xianxia.sect.core.model.SecretRealmBackpack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * GameViewEventEnvelopeAssemblyTest — R2.4「月/年 JSON 信封并入 eventFeed」
 * 的组装面等价守卫（batch-R2D.md「残留执行器行为等价」红线）。
 *
 * 生产路径 = C++ 事件队列 → proto `eventFeed` → codec typed 载荷 →
 * [buildMonthEnvelopeFromEvents]/[buildYearEnvelopeFromEvents]；回滚臂 =
 * 旧信封 JSON 解析（parseXxxEnvelope，登记边界）。本守卫以**同一事实**
 * （C++ 信封 JSON 原文 ⇄ 同构事件列表）驱动两路，断言组装产物**逐字段
 * 相等**——执行器输入形状与旧路径完全一致，即平台效应行为等价由构造保证。
 */
class GameViewEventEnvelopeAssemblyTest {

    @Test
    fun `month envelope from events equals legacy json parse`() {
        val backpack = SecretRealmBackpack(spiritStones = 77)
        val detail = """
            {"policyCosts":{"disabledPolicies":["聚贤堂","炼丹房"]},
             "secretRealmClose":{"closed":true,
                                 "memberIds":["3","9"],
                                 "backpack":{"spiritStones":77}},
             "purchaseLogs":[{"discipleId":"5","itemName":"聚气丹","age":21},
                             {"discipleId":"7","itemName":"青锋剑","age":33}],
             "seizedSectBuildings":["w2","w8"]}
        """.trimIndent()
        val legacy = parseMonthSettlementEnvelope(detail)

        val events = listOf(
            GameViewStreamEvent(
                GameViewStreamEvent.Kind.PURCHASE, 12, 4,
                GameViewStreamEvent.Payload.Purchase("5", "聚气丹", 21),
            ),
            GameViewStreamEvent(
                GameViewStreamEvent.Kind.MONTH_SETTLED, 12, 4,
                GameViewStreamEvent.Payload.MonthSettled(
                    disabledPolicies = listOf("聚贤堂", "炼丹房"),
                    seizedSectBuildings = listOf("w2", "w8"),
                ),
            ),
            GameViewStreamEvent(
                GameViewStreamEvent.Kind.SECRET_REALM_CLOSED, 12, 4,
                GameViewStreamEvent.Payload.SecretRealmClosed(
                    memberIds = listOf("3", "9"), backpack = backpack,
                ),
            ),
            GameViewStreamEvent(
                GameViewStreamEvent.Kind.PURCHASE, 12, 4,
                GameViewStreamEvent.Payload.Purchase("7", "青锋剑", 33),
            ),
        )

        assertEquals(legacy, buildMonthEnvelopeFromEvents(events))
    }

    @Test
    fun `year envelope from events equals legacy json parse`() {
        val detail = """
            {"agedDeaths":[{"discipleId":"12","name":"无名","surname":"玄","age":88,
                            "realm":4,"realmLayer":2,"deathYear":9,"cause":"age",
                            "storageBagItems":[{"itemId":"i1","itemType":"material",
                                                "name":"兽皮","rarity":1,"quantity":2}]}],
             "bereavements":[{"grievingId":6,"relationship":"道侣",
                              "deceasedName":"玄无名","grievingAge":71}]}
        """.trimIndent()
        val legacy = parseYearSettlementEnvelope(detail)

        val events = listOf(
            GameViewStreamEvent(
                GameViewStreamEvent.Kind.DEATH, 9, 1,
                GameViewStreamEvent.Payload.Death(legacy.agedDeaths.single()),
            ),
            GameViewStreamEvent(
                GameViewStreamEvent.Kind.YEAR_SETTLED, 9, 1,
                GameViewStreamEvent.Payload.YearSettled(legacy.bereavements),
            ),
        )

        assertEquals(legacy, buildYearEnvelopeFromEvents(events))
    }

    @Test
    fun `missing settlement marker falls back to legacy parse`() {
        // 无 MONTH_SETTLED 在场证明（JSON 回滚臂 / 事件丢失）→ null → 调用方回退旧解析
        assertNull(
            buildMonthEnvelopeFromEvents(
                listOf(
                    GameViewStreamEvent(
                        GameViewStreamEvent.Kind.PURCHASE, 12, 4,
                        GameViewStreamEvent.Payload.Purchase("5", "聚气丹", 21),
                    ),
                )
            )
        )
        assertNull(buildMonthEnvelopeFromEvents(emptyList()))
        assertNull(buildYearEnvelopeFromEvents(emptyList()))
    }

    @Test
    fun `empty settlement event yields empty envelope with legacy parse`() {
        // C++ 空月结（无购买/无秘境关闭/无没收）双臂同形：空信封
        val legacy = parseMonthSettlementEnvelope(
            """{"policyCosts":{"disabledPolicies":[]},"purchaseLogs":[],"seizedSectBuildings":[]}"""
        )
        val fromEvents = buildMonthEnvelopeFromEvents(
            listOf(
                GameViewStreamEvent(
                    GameViewStreamEvent.Kind.MONTH_SETTLED, 3, 7,
                    GameViewStreamEvent.Payload.MonthSettled(emptyList(), emptyList()),
                ),
            )
        )!!
        assertEquals(legacy.disabledPolicies, fromEvents.disabledPolicies)
        assertEquals(legacy.purchaseLogs, fromEvents.purchaseLogs)
        assertEquals(legacy.seizedBuildingsOfSect, fromEvents.seizedBuildingsOfSect)
        assertEquals(legacy.secretRealmClose, fromEvents.secretRealmClose)
    }
}
