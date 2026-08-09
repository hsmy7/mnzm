package com.xianxia.sect.ui.components

import androidx.activity.ComponentActivity
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.xianxia.sect.core.GameConfig.TraitWashType
import com.xianxia.sect.core.model.Affix
import com.xianxia.sect.core.model.DiscipleAggregate
import com.xianxia.sect.core.model.DiscipleCore
import com.xianxia.sect.core.model.DiscipleExtended
import com.xianxia.sect.core.model.Physique
import com.xianxia.sect.core.model.Talent
import com.xianxia.sect.ui.game.dialogs.TraitWashDialog
import com.xianxia.sect.ui.game.dialogs.WashSessionControl
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 天赋/体质/词条详情界面的洗炼入口测试（2026-08-09 洗炼按钮迁入详情界面）：
 * - onWashClick 非空时详情底部显示对应洗炼按钮，点击触发回调
 * - onWashClick 默认 null 时按钮不显示
 * - washOverlay 槽位内覆盖层可见（结构守卫，防窗口遮挡回归）
 * - 端到端：点击洗炼按钮 → 洗炼弹窗经 overlay 槽位渲染且可见
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TalentDetailDialogWashTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    // ── 天赋 ──────────────────────────────────────────────────────────

    @Test
    fun `天赋详情 - onWashClick 非空时显示洗炼按钮且点击触发回调`() {
        var washClicks = 0
        composeRule.setContent {
            TalentDetailDialog(
                talent = testTalent(),
                onDismiss = {},
                onWashClick = { washClicks++ }
            )
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("洗炼天赋").assertIsDisplayed()
        composeRule.onNodeWithText("洗炼天赋").performClick()
        composeRule.waitForIdle()
        assertEquals(1, washClicks)
    }

    @Test
    fun `天赋详情 - onWashClick 默认 null 时洗炼按钮不显示`() {
        composeRule.setContent {
            TalentDetailDialog(
                talent = testTalent(),
                onDismiss = {}
            )
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("洗炼天赋").assertDoesNotExist()
    }

    @Test
    fun `天赋详情 - washOverlay 槽位内覆盖层可见 - 结构守卫`() {
        composeRule.setContent {
            TalentDetailDialog(
                talent = testTalent(),
                onDismiss = {},
                washOverlay = {
                    InlineStandardPromptDialog(
                        onDismissRequest = {},
                        title = "洗炼天赋",
                        confirmLabel = "确定",
                        dismissLabel = "取消"
                    ) {
                        Text("洗炼覆盖层内容")
                    }
                }
            )
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("洗炼覆盖层内容").assertIsDisplayed()
    }

    @Test
    fun `天赋详情 - 端到端 点击洗炼按钮后洗炼弹窗经 overlay 槽位可见`() {
        composeRule.setContent {
            var showWash by remember { mutableStateOf(false) }
            TalentDetailDialog(
                talent = testTalent(),
                onDismiss = {},
                onWashClick = { showWash = true },
                washOverlay = {
                    if (showWash) {
                        TraitWashDialog(
                            disciple = testDisciple(),
                            type = TraitWashType.TALENT,
                            targetId = "t1",
                            jadeSymbols = 0,
                            viewModel = null,
                            washSession = WashSessionControl(
                                initialPityCount = 0,
                                onPityCountChanged = {},
                                washing = false,
                                onWashingChange = {}
                            ),
                            onDismiss = { showWash = false }
                        )
                    }
                }
            )
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("洗炼天赋").performClick()
        composeRule.waitForIdle()
        // 洗炼弹窗内容区特征文本（与按钮文本"洗炼天赋"区分）
        composeRule.onNodeWithText("当前天赋").assertIsDisplayed()
    }

    // ── 体质 ──────────────────────────────────────────────────────────

    @Test
    fun `体质详情 - onWashClick 非空时显示洗炼按钮且点击触发回调`() {
        var washClicks = 0
        composeRule.setContent {
            PhysiqueDetailDialog(
                physique = testPhysique(),
                onDismiss = {},
                onWashClick = { washClicks++ }
            )
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("洗炼体质").assertIsDisplayed()
        composeRule.onNodeWithText("洗炼体质").performClick()
        composeRule.waitForIdle()
        assertEquals(1, washClicks)
    }

    @Test
    fun `体质详情 - 端到端 点击洗炼按钮后洗炼弹窗经 overlay 槽位可见`() {
        composeRule.setContent {
            var showWash by remember { mutableStateOf(false) }
            PhysiqueDetailDialog(
                physique = testPhysique(),
                onDismiss = {},
                onWashClick = { showWash = true },
                washOverlay = {
                    if (showWash) {
                        TraitWashDialog(
                            disciple = testDisciple(),
                            type = TraitWashType.PHYSIQUE,
                            targetId = "p1",
                            jadeSymbols = 0,
                            viewModel = null,
                            washSession = WashSessionControl(0, {}, false, {}),
                            onDismiss = { showWash = false }
                        )
                    }
                }
            )
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("洗炼体质").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("当前体质").assertIsDisplayed()
    }

    // ── 词条 ──────────────────────────────────────────────────────────

    @Test
    fun `词条详情 - onWashClick 非空时显示洗炼按钮且点击触发回调`() {
        var washClicks = 0
        composeRule.setContent {
            AffixDetailDialog(
                affix = testAffix(),
                onDismiss = {},
                onWashClick = { washClicks++ }
            )
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("洗炼词条").assertIsDisplayed()
        composeRule.onNodeWithText("洗炼词条").performClick()
        composeRule.waitForIdle()
        assertEquals(1, washClicks)
    }

    @Test
    fun `词条详情 - 端到端 点击洗炼按钮后洗炼弹窗经 overlay 槽位可见`() {
        composeRule.setContent {
            var showWash by remember { mutableStateOf(false) }
            AffixDetailDialog(
                affix = testAffix(),
                onDismiss = {},
                onWashClick = { showWash = true },
                washOverlay = {
                    if (showWash) {
                        TraitWashDialog(
                            disciple = testDisciple(),
                            type = TraitWashType.AFFIX,
                            targetId = "a1",
                            jadeSymbols = 0,
                            viewModel = null,
                            washSession = WashSessionControl(0, {}, false, {}),
                            onDismiss = { showWash = false }
                        )
                    }
                }
            )
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("洗炼词条").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("当前词条").assertIsDisplayed()
    }

    // ── 夹具 ──────────────────────────────────────────────────────────

    private fun testTalent() = Talent(
        id = "t1",
        name = "天生神力",
        description = "力量超群",
        rarity = 2,
        effects = mapOf("physicalAttack" to 0.1)
    )

    private fun testPhysique() = Physique(
        id = "p1",
        name = "金刚不坏",
        description = "肉身强横",
        rarity = 2,
        cultivationSpeedBonus = 0.0,
        damageAmplification = 0.0,
        damageReduction = 0.1,
        critDamageBonus = 0.0,
        defenseBonus = 0.0
    )

    private fun testAffix() = Affix(
        id = "a1",
        name = "锋利",
        description = "攻击提升",
        rarity = 2,
        effects = mapOf("physicalAttack" to 0.05)
    )

    private fun testDisciple() = DiscipleAggregate(
        core = DiscipleCore(id = "d1", name = "测试弟子", discipleType = "outer"),
        combatStats = null,
        equipment = null,
        extended = DiscipleExtended(discipleId = "d1"),
        attributes = null
    )
}
