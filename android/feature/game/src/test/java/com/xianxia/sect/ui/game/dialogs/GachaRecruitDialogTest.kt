package com.xianxia.sect.ui.game.dialogs

import androidx.activity.ComponentActivity
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.engine.GameEngine
import com.xianxia.sect.core.engine.di.IoDispatcher
import com.xianxia.sect.core.engine.domain.gacha.GachaCategorySpec
import com.xianxia.sect.core.engine.domain.gacha.GachaFacade
import com.xianxia.sect.core.engine.domain.gacha.GachaGrantResult
import com.xianxia.sect.core.engine.domain.gacha.GachaPoolConfig
import com.xianxia.sect.core.engine.domain.gacha.GachaPoolSpec
import com.xianxia.sect.core.engine.domain.gacha.GachaPitySpec
import com.xianxia.sect.core.engine.domain.gacha.GachaPullResult
import com.xianxia.sect.core.engine.domain.gacha.GachaPullRow
import com.xianxia.sect.core.engine.domain.gacha.GachaRarityWeightSpec
import com.xianxia.sect.core.model.CharacterTemplateDb
import com.xianxia.sect.core.model.GachaHistoryEntry
import com.xianxia.sect.ui.game.GachaViewModel
import com.xianxia.sect.ui.game.delegate.GachaDelegate
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * GachaRecruitDialogTest — 寻访界面的真实渲染与交互（G11 验收①②③⑥ 的本机证据面）。
 *
 * 真机未接入（`report-G11.md` 的 pending-device 节如实登记），本类用 Robolectric + Compose
 * 把「面板渲染得出来、点下去反应正确」这一层在提交前钉住：结果层与宿主面板的 z 序、
 * `key(resultToken)` 叠层、稀疏账本的置灰语义、余额不足禁用、点框不关 vs 点框外关的分工，
 * 都是纯函数测不到的。
 *
 * | 用例 | 钉住的判据 |
 * |---|---|
 * | 主界面渲染 | 池名 / 单抽价 / 十连价 / 保底 `x/threshold` / 三个入口，数值全来自池配置与账本流 |
 * | 余额不足 | 两个招募按钮都 `isEnabled = false`（验收② 禁用态） |
 * | 单抽 / 十连 | 结果层出现且格数 = 1 / 10（验收③ 铺满） |
 * | 点框外 | 结果层关闭、下层主界面回来（D-2 层内关闭） |
 * | 点奖励框 | 结果层不关（Q39「点框不弹详情、仅防误关」） |
 * | 图鉴 | 未解锁条数 + 满星 MAX + 「下一星 x/100」（验收⑥） |
 * | 公示 | 权重与保底文案按池配置渲染（禁硬编码） |
 * | 历史 | 空态文案 + 有记录时的名字与年月 |
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GachaRecruitDialogTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val inlineScope = CoroutineScope(Dispatchers.Unconfined)
    private lateinit var facade: DialogFakeFacade
    private lateinit var poolConfig: GachaPoolConfig
    private lateinit var engine: GameEngine
    private lateinit var viewModel: GachaViewModel

    @Before
    fun setUp() {
        facade = DialogFakeFacade()
        poolConfig = mockk()
        every { poolConfig.pool(POOL_ID) } returns standardPool()
        engine = mockk(relaxed = true)
        // 委托把抽卡挂进 launchOnEngine；Unconfined 就地跑完，等价于引擎线程已提交该事务
        every { engine.launchOnEngine(any()) } answers {
            inlineScope.launch { firstArg<suspend CoroutineScope.() -> Unit>().invoke(inlineScope) }
            mockk<Job>(relaxed = true)
        }
        viewModel = GachaViewModel(facade, poolConfig, IoDispatcher(Dispatchers.Unconfined))
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `主界面渲染 - 价格与保底全部来自池配置与账本流`() {
        openDialog(spiritStones = PULL_PRICE * 3, pity = PITY_PRESET)

        composeRule.onNodeWithText(POOL_NAME_TEXT).assertIsDisplayed()
        composeRule.onNodeWithText("单次寻访：$PULL_PRICE 灵石", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("十次寻访：", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("本期已寻访 $PITY_PRESET/$PULL_THRESHOLD 次", substring = true)
            .assertIsDisplayed()
        composeRule.onNodeWithText(ODDS_ENTRY_TEXT).assertIsDisplayed()
        composeRule.onNodeWithText(HISTORY_ENTRY_TEXT).assertIsDisplayed()
        composeRule.onNodeWithText(CODEX_ENTRY_TEXT).assertIsDisplayed()
        assertEquals("打开界面不得直接抽卡", emptyList<String>(), facade.pullCalls)
    }

    @Test
    fun `余额不足 - 两个招募按钮都禁用`() {
        openDialog(spiritStones = PULL_PRICE - 1)

        composeRule.onNodeWithText(PULL_ONCE_TEXT).assertIsNotEnabled()
        composeRule.onNodeWithText(PULL_TEN_TEXT).assertIsNotEnabled()
    }

    @Test
    fun `单抽 - 结果层只出一格`() {
        openDialog(spiritStones = PULL_PRICE * 20)

        composeRule.onNodeWithText(PULL_ONCE_TEXT).performClick()
        composeRule.waitForIdle()

        assertEquals("必须走门面的单抽口一次", listOf("pullOnce"), facade.pullCalls)
        composeRule.onNodeWithTag(LAYER_TAG).assertIsDisplayed()
        composeRule.onAllNodesWithTag(CELL_TAG, useUnmergedTree = true).assertCountEquals(1)
        composeRule.onNodeWithText(RESULT_TITLE_TEXT).assertIsDisplayed()
    }

    @Test
    fun `十连 - 结果层铺满十格`() {
        openDialog(spiritStones = PULL_PRICE * 20)

        composeRule.onNodeWithText(PULL_TEN_TEXT).performClick()
        composeRule.waitForIdle()

        assertEquals("必须走门面的十连口一次", listOf("pullTen"), facade.pullCalls)
        composeRule.onAllNodesWithTag(CELL_TAG, useUnmergedTree = true).assertCountEquals(TEN_CELLS)
    }

    @Test
    fun `点框外关闭结果层 - 下层主界面回来`() {
        openDialog(spiritStones = PULL_PRICE * 20)
        composeRule.onNodeWithText(PULL_TEN_TEXT).performClick()
        composeRule.waitForIdle()

        // 层背景左上角（在面板 padding 区内，不属于任何奖励框与按钮）= Q30 的「点框外」
        composeRule.onNodeWithTag(LAYER_TAG).performTouchInput {
            click(Offset(LAYER_EMPTY_CORNER, LAYER_EMPTY_CORNER))
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(LAYER_TAG).assertDoesNotExist()
        composeRule.onNodeWithText(POOL_NAME_TEXT).assertIsDisplayed()
    }

    @Test
    fun `点奖励框不关结果层 - Q39 防误关`() {
        openDialog(spiritStones = PULL_PRICE * 20)
        composeRule.onNodeWithText(PULL_TEN_TEXT).performClick()
        composeRule.waitForIdle()

        composeRule.onAllNodesWithTag(CELL_TAG, useUnmergedTree = true).onFirst().performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(LAYER_TAG).assertIsDisplayed()
    }

    @Test
    fun `叠下一轮 - 关不掉的两层结果直接替换`() {
        openDialog(spiritStones = PULL_PRICE * 30)
        composeRule.onNodeWithText(PULL_ONCE_TEXT).performClick()
        composeRule.waitForIdle()

        // 结果页上的「招募十次」：不先关页再开寻访主界面，而是叠新一轮（Q30）
        composeRule.onNodeWithTag(LAYER_PULL_TEN_TAG).performClick()
        composeRule.waitForIdle()

        assertEquals("两轮都在同一层内完成", listOf("pullOnce", "pullTen"), facade.pullCalls)
        composeRule.onAllNodesWithTag(CELL_TAG, useUnmergedTree = true).assertCountEquals(TEN_CELLS)
    }

    @Test
    fun `图鉴 - 未解锁压暗条数与满星与下一星进度`() {
        val maxTemplateId = CharacterTemplateDb.ALL.first().id
        val growingTemplateId = CharacterTemplateDb.ALL[1].id
        facade.starMap.value = mapOf(maxTemplateId to GameConfig.Gacha.MAX_STAR, growingTemplateId to 2)
        facade.fragmentCounts.value = mapOf(growingTemplateId to FRAGMENT_PROGRESS)
        openDialog(spiritStones = PULL_PRICE * 20)

        composeRule.onNodeWithText(CODEX_ENTRY_TEXT).performClick()
        composeRule.waitForIdle()

        val lockedExpected = CharacterTemplateDb.ALL.size - 2
        // 合并语义树会把重复文案塌进共同祖先（一次点击/一个节点），数格子必须走未合并树
        composeRule.onAllNodesWithText(LOCKED_TEXT, useUnmergedTree = true).assertCountEquals(lockedExpected)
        composeRule.onNodeWithText(MAX_STAR_TEXT).assertIsDisplayed()
        composeRule.onNodeWithText("下一星 $FRAGMENT_PROGRESS/${GameConfig.Gacha.FRAGMENTS_PER_STAR}", substring = true)
            .assertIsDisplayed()
    }

    @Test
    fun `概率公示 - 权重与保底文案按池配置渲染`() {
        openDialog(spiritStones = PULL_PRICE * 20)

        composeRule.onNodeWithText(ODDS_ENTRY_TEXT).performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithText(CATEGORY_SECTION_TITLE).assertIsDisplayed()
        composeRule.onNodeWithText(SINGLE_ROOT_CATEGORY_LABEL).assertIsDisplayed()
        composeRule.onNodeWithText("$SINGLE_ROOT_WEIGHT%", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("每 $PULL_THRESHOLD 次寻访", substring = true).assertIsDisplayed()
    }

    @Test
    fun `寻访记录 - 空态与有记录两条路径`() {
        openDialog(spiritStones = PULL_PRICE * 20)
        composeRule.onNodeWithText(HISTORY_ENTRY_TEXT).performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText(EMPTY_HISTORY_TEXT).assertIsDisplayed()

        // 账本流推进后同一面直接刷新（不重开窗口），验的是订阅面而不是初值
        facade.history.value = listOf(
            GachaHistoryEntry(
                category = "character",
                templateId = CharacterTemplateDb.ALL.first().id,
                count = 5,
                gameMonthIndex = FIRST_MONTH_INDEX,
            )
        )
        composeRule.waitForIdle()

        composeRule.onNodeWithText(CharacterTemplateDb.ALL.first().name, substring = true).assertIsDisplayed()
        composeRule.onNodeWithText(FIRST_MONTH_LABEL, substring = true).assertIsDisplayed()
    }

    /** 以手工依赖打开寻访对话框（不经 Hilt，也不搬 GameViewModel 全套替身） */
    private fun openDialog(spiritStones: Long, pity: Int = 0) {
        facade.pityCounters.value = if (pity == 0) emptyMap() else mapOf(POOL_ID to pity)
        composeRule.setContent {
            GachaRecruitDialog(
                gacha = GachaDelegate(engine, facade),
                gachaVm = viewModel,
                spiritStones = spiritStones,
                shimmer = false,
                onDismiss = {},
            )
        }
        composeRule.waitForIdle()
    }

    private companion object {
        const val POOL_ID = "standard"
        const val PULL_PRICE = 5000L
        const val PULL_THRESHOLD = 10
        const val PITY_PRESET = 3
        const val TEN_CELLS = 10
        const val FRAGMENT_PROGRESS = 30
        const val SINGLE_ROOT_WEIGHT = 11
        const val FIRST_MONTH_INDEX = 13

        const val POOL_NAME_TEXT = "常驻寻访"
        const val PULL_ONCE_TEXT = "招募一次"
        const val PULL_TEN_TEXT = "招募十次"
        const val ODDS_ENTRY_TEXT = "概率公示"
        const val HISTORY_ENTRY_TEXT = "寻访记录"
        const val CODEX_ENTRY_TEXT = "图鉴"
        const val RESULT_TITLE_TEXT = "恭喜获得"
        const val LAYER_TAG = GACHA_RESULT_LAYER_TAG
        const val CELL_TAG = GACHA_CELL_TAG
        const val LAYER_PULL_TEN_TAG = GACHA_LAYER_PULL_TEN_TAG

        /** 层背景的空白取样点（面板水平内边距 24dp / 垂直 12dp 之内，默认密度 1dp=1px） */
        const val LAYER_EMPTY_CORNER = 6f
        const val LOCKED_TEXT = "未解锁"
        const val MAX_STAR_TEXT = "MAX"
        const val CATEGORY_SECTION_TITLE = "出货类别权重"
        const val SINGLE_ROOT_CATEGORY_LABEL = "单灵根弟子"
        const val EMPTY_HISTORY_TEXT = "还没有寻访记录"
        const val FIRST_MONTH_LABEL = "第1年1月"
    }
}

/** 面板测试用的门面替身：四个流可摆值，抽卡返回按抽数摆好的出货，并记录调用次序 */
private class DialogFakeFacade : GachaFacade {
    override val pityCounters = MutableStateFlow<Map<String, Int>>(emptyMap())
    override val fragmentCounts = MutableStateFlow<Map<String, Int>>(emptyMap())
    override val starMap = MutableStateFlow<Map<String, Int>>(emptyMap())
    override val history = MutableStateFlow<List<GachaHistoryEntry>>(emptyList())
    val pullCalls = mutableListOf<String>()

    override suspend fun pullOnce(poolId: String): GachaPullResult = pull("pullOnce", poolId, 1)

    override suspend fun pullTen(poolId: String): GachaPullResult = pull("pullTen", poolId, 10)

    override suspend fun grantFragments(templateId: String, count: Int): GachaGrantResult =
        GachaGrantResult.Invalid("NOT_USED_IN_UI")

    private fun pull(name: String, poolId: String, count: Int): GachaPullResult {
        pullCalls += name
        return GachaPullResult.Success(
            poolId = poolId,
            pricePaid = 5000L * count,
            spiritStonesAfter = 0L,
            pityAfter = count,
            rows = (1..count).map { index ->
                GachaPullRow(
                    category = "character",
                    templateId = CharacterTemplateDb.ALL[index % CharacterTemplateDb.ALL.size].id,
                    itemId = "",
                    rarity = 0,
                    count = index,
                    isPity = index == count,
                )
            },
            unlockedTemplateIds = emptyList(),
        )
    }
}

private fun standardPool(): GachaPoolSpec = GachaPoolSpec(
    poolId = "standard",
    enabled = true,
    pricePerPull = 5000,
    categories = listOf(
        GachaCategorySpec("character_single", 11, listOf("zhouming", "suqing"), "", 0),
        GachaCategorySpec("herb", 26, emptyList(), "herbs", 4),
    ),
    itemRarityWeights = listOf(GachaRarityWeightSpec(3, 33), GachaRarityWeightSpec(1, 22)),
    pity = GachaPitySpec(10, 5, "random"),
)
