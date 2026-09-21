package com.xianxia.sect.core.engine

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 读档后 boot 修复性刷新的判据守卫（审计 §12-G #15）。
 *
 * 背景：`ensureGameDataIntegrity` 的商人分支原判据只看"列表为空" ⇒ 玩家把商品
 * **买光**后读档会被白送一次刷新（绕过 `merchantLastRefreshYear` 的刷新节奏，
 * 等于免费补货）。修正后的判据只修"从未生成过商人"的档。
 */
class MerchantInventoryRepairGuardTest {

    @Test
    fun `empty list on a never-populated save triggers repair`() {
        // 新档/损坏档：从未生成商人（merchantLastRefreshYear == 0）⇒ 应修复
        assertTrue(shouldRepairMerchantInventory(itemsEmpty = true, lastRefreshYear = 0))
    }

    @Test
    fun `empty list after player bought everything does NOT trigger repair`() {
        // 玩家买光：列表空但商人早已生成过 ⇒ **不得**白送刷新
        assertFalse(shouldRepairMerchantInventory(itemsEmpty = true, lastRefreshYear = 12))
    }

    @Test
    fun `non-empty list never triggers repair regardless of anchor`() {
        assertFalse(shouldRepairMerchantInventory(itemsEmpty = false, lastRefreshYear = 0))
        assertFalse(shouldRepairMerchantInventory(itemsEmpty = false, lastRefreshYear = 12))
    }
}
