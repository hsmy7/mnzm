package com.xianxia.sect.ui.components

import androidx.compose.ui.graphics.Color
import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.model.PillGrade
import com.xianxia.sect.ui.theme.GameColors
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * ItemCardColorSourceTest — 卡片品阶色与丹药品质色的行为面守卫（G12 色板对齐）。
 *
 * 源码扫描面在 `GachaColorSingleSourceGuardTest`（engine 模块，跨端）；本类钉的是
 * **消费点实际拿到的值**：`ItemCard.getRarityColor` / 丹药品质三档必须逐档等于
 * Q31（`GameConfig.Gacha.RARITY_COLORS`），仓储、商人、详情、奖励弹窗与寻访同色。
 *
 * 判别力自证：`getRarityColor` 或品质三档改回任何自写 `when` 字面量表 ⇒
 * 对应 assertEquals 即红（消息点名档位与期望十六进制）。
 */
class ItemCardColorSourceTest {

    @Test
    fun `卡片品阶色逐档等于 Q31 - 未知档回落一阶灰`() {
        (1..6).forEach { rarity ->
            assertEquals(
                "getRarityColor($rarity) 必须等于 Q31 的 ${GameConfig.Gacha.rarityColor(rarity)}",
                GachaColors.rarityColor(rarity),
                getRarityColor(rarity),
            )
        }
        assertEquals(
            "越界档与 Q31 的回落口径一致（回落一阶灰）",
            GachaColors.rarityColor(1),
            getRarityColor(99),
        )
    }

    @Test
    fun `丹药品质三档等于 Q31 一三五阶 - 下灰中蓝上红`() {
        assertEquals(GachaColors.rarityColor(1), PillGrade.LOW.getQualityColor())
        assertEquals(GachaColors.rarityColor(3), PillGrade.MEDIUM.getQualityColor())
        assertEquals(GachaColors.rarityColor(5), PillGrade.HIGH.getQualityColor())
    }

    @Test
    fun `品质名取色与枚举重载一致 - 异常值回落一阶灰`() {
        assertEquals(PillGrade.LOW.getQualityColor(), getQualityColor("下品"))
        assertEquals(PillGrade.MEDIUM.getQualityColor(), getQualityColor("中品"))
        assertEquals(PillGrade.HIGH.getQualityColor(), getQualityColor("上品"))
        assertEquals(
            "未知品质名回落一阶灰（保证文字可见）",
            GachaColors.rarityColor(1),
            getQualityColor("不存在"),
        )
        assertEquals(GachaColors.rarityColor(1), getQualityColor(null))
    }

    @Test
    fun `theme 六常量别名与 Q31 同值 - 旧引用面拿到的就是单源`() {
        assertEquals(Color(0xFFB8B8B8), GameColors.RarityCommon)
        assertEquals(GachaColors.rarityColor(6), GameColors.RarityHeaven)
        assertEquals(GachaColors.rarityColor(5), GameColors.RarityEarth)
        assertEquals(GachaColors.rarityColor(3), GameColors.RarityTreasure)
    }
}
