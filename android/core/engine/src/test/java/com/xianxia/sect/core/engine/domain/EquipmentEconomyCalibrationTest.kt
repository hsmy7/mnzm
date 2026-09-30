package com.xianxia.sect.core.engine.domain

import com.xianxia.sect.core.model.EquipLevelCurve
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 装备经济校准门（EQ-B4，S16 后半；方案 §3.6「校准目标（拍板）」）。
 *
 * **拍板口径**：一套六件 1→30 的累计消耗 ≈ 玩家 **1 个月**正常产出
 * （比值容差 **[0.75,1.25]**）。
 *
 * **消耗侧**（公式单源 [EquipLevelCurve]，C++ `equipment_tx.h` 对偶同式）：
 * T6 单件 1,566,000 灵石 + 39 兽材；六件 9,396,000 灵石 + 234 兽材。
 *
 * **产出侧锚**：`STANDARD_MONTH_OUTPUT_AT_T6_STAGE` = **940 万灵石/月**——
 * T6 可穿阶段（大乘起）宗门标准月画像锚。推导与登记口径：
 * - 经济基线表（`docs/knowledge-base.md`）为源汇登记表、无数值月产出；「月产出」
 *   依赖玩家行为画像，本批以拍板口径**反推定锚**并显式命名（非测量值，B5 落
 *   经济基线表时随本注记登记）；
 * - 量级交叉核对：T6 物品市价 26,880,000（`Rarity.CONFIGS` basePrice）≈ 2.9 个月
 *   产出 ⇒ 「直购一件 T6 ≈ 三个月产出、满级一套 ≈ 一个月产出」，与不做保底的
 *   长线拍板（§0.2 甲组 #2/#4）自洽；
 * - 该锚同时是 I9（无保底）与 I10（无上限）的量级基线，见 report-B4 §5。
 *
 * 比值出带 ⇒ 有人动了升级消耗公式、月画像锚或两者之一——两边必须一起重校准。
 */
class EquipmentEconomyCalibrationTest {

    @Test
    fun `T6单件满级消耗与方案口径一致`() {
        assertEquals("T6 单件 1→30 灵石（100×rarity²×Σlevel）", 1_566_000L, EquipLevelCurve.totalSpiritStonesCost(6, 30))
        assertEquals("T6 单件 1→30 兽材（39 件）", 39, EquipLevelCurve.totalBeastMaterialCost(30))
    }

    @Test
    fun `T1单件满级消耗与方案口径一致`() {
        assertEquals("T1 单件 1→30 灵石", 43_500L, EquipLevelCurve.totalSpiritStonesCost(1, 30))
    }

    @Test
    fun `一套六件满级消耗对月产出锚的比值在容差带内`() {
        val fullSetCost = EquipLevelCurve.totalSpiritStonesCost(6, 30) * FULL_SET_PART_COUNT
        val ratio = fullSetCost.toDouble() / STANDARD_MONTH_OUTPUT_AT_T6_STAGE
        println(
            "ECONOMY 满级一套灵石=$fullSetCost 兽材=${EquipLevelCurve.totalBeastMaterialCost(30) * FULL_SET_PART_COUNT}" +
                " 月产出锚=$STANDARD_MONTH_OUTPUT_AT_T6_STAGE 比值=%.4f".format(ratio)
        )
        assertTrue(
            "满级一套消耗 ÷ 月产出 = $ratio 出带 [0.75,1.25]（S16）：升级消耗公式与月画像锚须同批重校准",
            ratio in 0.75..1.25
        )
    }

    @Test
    fun `分解返还为消耗的一半`() {
        val refund = EquipLevelCurve.dismantleRefund(6, 30)
        assertEquals("T6 满级分解返还灵石（50% 向下取整）", 783_000L, refund.first)
        assertEquals("T6 满级分解返还兽材", 19, refund.second)
    }

    private companion object {
        /** 一套 = 六部位各一件 */
        const val FULL_SET_PART_COUNT = 6L

        /**
         * T6 可穿阶段（大乘起）宗门标准月产出锚（灵石/月）。
         * 推导与登记口径见类 KDoc——拍板口径反推定锚，非测量值；B5 落经济基线表。
         */
        const val STANDARD_MONTH_OUTPUT_AT_T6_STAGE = 9_400_000L
    }
}
