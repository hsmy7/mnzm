package com.xianxia.sect.ui.game

import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.engine.GameEngine
import com.xianxia.sect.core.model.DiscipleAggregate
import com.xianxia.sect.core.model.DiscipleCore
import com.xianxia.sect.core.model.DiscipleStatus
import com.xianxia.sect.core.usecase.ElderManagementUseCase
import com.xianxia.sect.core.usecase.SectPolicyToggleUseCase
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * 守卫测试：长老/执事岗位候选弟子数据源不得预过滤 status == IDLE。
 *
 * 回归背景：问道塔/青云峰选择弟子界面勾选"显示所有弟子"后仍只显示空闲弟子——
 * 根因是 [ProductionViewModel.getAvailableDisciplesForPreachingElder] 等 6 个方法
 * 在 ViewModel 层硬编码 `status == DiscipleStatus.IDLE` 预过滤，把在岗弟子提前剔除，
 * 而对话框层 [filterByDiscipleStatus]（showAll 模式）拿到的候选已被截断、勾选失效。
 *
 * 状态过滤（空闲/显示所有）统一委托对话框层 [filterByDiscipleStatus]，
 * 本测试守卫数据源只做硬性条件过滤（存活/最小年龄/已入修炼），
 * 若有人重新加入 IDLE 预过滤，本测试立即失败。
 */
@RunWith(RobolectricTestRunner::class)
class ProductionViewModelEligibleDisciplesTest {

    private fun createAggregate(
        id: String,
        status: DiscipleStatus = DiscipleStatus.IDLE,
        isAlive: Boolean = true,
        age: Int = 20,
        realmLayer: Int = 1
    ): DiscipleAggregate {
        return DiscipleAggregate(
            core = DiscipleCore(
                id = id,
                name = id,
                age = age,
                isAlive = isAlive,
                status = status.name,
                realmLayer = realmLayer
            ),
            combatStats = null,
            equipment = null,
            extended = null,
            attributes = null
        )
    }

    private fun buildViewModel(disciples: List<DiscipleAggregate>): ProductionViewModel {
        val engine = mockk<GameEngine>(relaxed = true)
        every { engine.discipleAggregatesSnapshot } returns disciples
        // 构造内 stateIn 会立即收集，必须 stub 为非 null Flow
        every { engine.productionSlots } returns MutableStateFlow(emptyList())
        every { engine.discipleAggregates } returns MutableStateFlow(disciples)
        return ProductionViewModel(
            gameEngine = engine,
            sectPolicyToggle = mockk(relaxed = true),
            elderManagement = mockk(relaxed = true)
        )
    }

    /** 6 个数据源方法统一守卫：同一过滤语义 */
    private fun allDataSources(viewModel: ProductionViewModel): List<List<DiscipleAggregate>> = listOf(
        viewModel.getAvailableDisciplesForOuterElder(),
        viewModel.getAvailableDisciplesForPreachingElder(),
        viewModel.getAvailableDisciplesForPreachingMaster(),
        viewModel.getAvailableDisciplesForInnerElder(),
        viewModel.getAvailableDisciplesForQingyunPreachingElder(),
        viewModel.getAvailableDisciplesForQingyunPreachingMaster()
    )

    @Test
    fun `6 个候选数据源包含空闲与在岗弟子 - 不预过滤 IDLE`() {
        val disciples = listOf(
            createAggregate("idle", status = DiscipleStatus.IDLE),
            createAggregate("patrolling", status = DiscipleStatus.PATROLLING),
            createAggregate("refining", status = DiscipleStatus.REFINING),
            createAggregate("mission", status = DiscipleStatus.ON_MISSION),
            createAggregate("team", status = DiscipleStatus.IN_TEAM)
        )
        val viewModel = buildViewModel(disciples)

        allDataSources(viewModel).forEachIndexed { index, result ->
            val ids = result.map { it.id }.toSet()
            assertTrue("方法[$index] 应包含空闲中弟子", "idle" in ids)
            assertTrue(
                "方法[$index] 应包含巡视中在岗弟子（showAll 勾选后可选，预过滤 IDLE 为回归）",
                "patrolling" in ids
            )
            assertTrue(
                "方法[$index] 应包含血炼中在岗弟子（showAll 勾选后可选，预过滤 IDLE 为回归）",
                "refining" in ids
            )
            // ON_MISSION / IN_TEAM 由对话框 filterByDiscipleStatus 的 showAll 模式排除，
            // 数据源不在此过滤（与 filterByDiscipleStatus 职责分离）
            assertTrue("方法[$index] 应包含任务中弟子（状态过滤在对话框层）", "mission" in ids)
            assertTrue("方法[$index] 应包含队伍中弟子（状态过滤在对话框层）", "team" in ids)
        }
    }

    @Test
    fun `6 个候选数据源排除死者、未成年、无境界弟子`() {
        val disciples = listOf(
            createAggregate("ok", age = 20, realmLayer = 3),
            createAggregate("dead", age = 20, realmLayer = 3, isAlive = false),
            createAggregate("young", age = GameConfig.Disciple.MIN_AGE - 1, realmLayer = 3),
            createAggregate("noRealm", age = 20, realmLayer = 0)
        )
        val viewModel = buildViewModel(disciples)

        allDataSources(viewModel).forEachIndexed { index, result ->
            val ids = result.map { it.id }.toSet()
            assertEquals("方法[$index] 应只含合格弟子", setOf("ok"), ids)
        }
    }

    @Test
    fun `空列表返回空`() {
        val viewModel = buildViewModel(emptyList())
        allDataSources(viewModel).forEachIndexed { index, result ->
            assertTrue("方法[$index] 空列表应返回空", result.isEmpty())
        }
    }
}
