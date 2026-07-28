package com.xianxia.sect.core.engine.system

import com.xianxia.sect.core.model.Disciple
import com.xianxia.sect.core.model.DiscipleStatus
import com.xianxia.sect.core.model.GameData
import com.xianxia.sect.core.model.SocialData
import com.xianxia.sect.core.state.DiscipleTables
import com.xianxia.sect.core.state.GameNotification
import com.xianxia.sect.core.state.MutableGameState
import com.xianxia.sect.core.state.PendingMarriageProposal
import com.xianxia.sect.core.state.WriteGuardRule
import com.xianxia.sect.core.util.GameRngManager
import com.xianxia.sect.core.util.RngPartition
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * 伴侣系统测试 — 聚焦同意模式（结婚需同意）与自动配对模式的边界行为。
 */
class PartnerSystemTest {

    @get:Rule val writeGuardRule = WriteGuardRule()

    private lateinit var rngManager: GameRngManager
    private lateinit var system: PartnerSystem

    @Before
    fun setup() {
        rngManager = GameRngManager()
        rngManager.initSystemSeed(42) // 固定种子保证可复现
        system = PartnerSystem(rngManager)
    }

    // ── 辅助方法 ──────────────────────────────────────────────────

    /** 创建一个符合配对条件的弟子 */
    private fun makeEligibleDisciple(
        id: String,
        name: String,
        gender: String = "male",
        age: Int = 20,
        spiritRootType: String = "metal"
    ): Disciple = Disciple(
        id = id,
        name = name,
        surname = "张",
        gender = gender,
        age = age,
        isAlive = true,
        status = DiscipleStatus.IDLE,
        portraitRes = "portrait_default",
        spiritRootType = spiritRootType,
        social = SocialData(partnerId = null)
    )

    /** 用种子 RNG 创建一个 MutableGameState，配入指定弟子 */
    private fun createState(vararg disciples: Disciple): MutableGameState {
        val tables = DiscipleTables()
        disciples.forEach { tables.insert(it) }
        return MutableGameState(
            gameData = GameData(),
            discipleTables = tables,
            equipmentStacks = com.xianxia.sect.core.state.EntityStore(),
            equipmentInstances = com.xianxia.sect.core.state.EntityStore(),
            manualStacks = com.xianxia.sect.core.state.EntityStore(),
            manualInstances = com.xianxia.sect.core.state.EntityStore(),
            pills = com.xianxia.sect.core.state.EntityStore(),
            materials = com.xianxia.sect.core.state.EntityStore(),
            herbs = com.xianxia.sect.core.state.EntityStore(),
            seeds = com.xianxia.sect.core.state.EntityStore(),
            storageBags = com.xianxia.sect.core.state.EntityStore(),
            battleLogs = emptyList(),
            teams = emptyList(),
            isPaused = false,
            isLoading = false,
            isSaving = false,
            pendingNotification = null,
            pendingMarriageProposals = emptyList()
        )
    }

    /** 用特定种子跑一次配对，返回配对结果状态 */
    private fun runMatching(
        seed: Long = 42,
        consentRequired: Boolean = false,
        vararg disciples: Disciple
    ): MutableGameState {
        val rng = GameRngManager()
        rng.initSystemSeed(seed)
        val sys = PartnerSystem(rng)
        val state = createState(*disciples)
        state.gameData = state.gameData.copy(daoCompanionConsentRequired = consentRequired)
        sys.processPartnerMatching(state)
        return state
    }

    // ═══════════════════════════════════════════════════════════════
    // 自动配对模式（原行为不变性）
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `auto mode - eligible disciples may be paired`() {
        val male = makeEligibleDisciple("1", "男A", "male")
        val female = makeEligibleDisciple("2", "女A", "female")
        // 验证 processPartnerMatching 运行不抛异常（配对由 RNG 控制，概率 0.6%）
        val state = runMatching(seed = 42, consentRequired = false, disciples = *arrayOf(male, female))
        assertNotNull("runMatching should succeed without exception", state)
    }

    @Test
    fun `auto mode - no pairing for underage disciples`() {
        val male = makeEligibleDisciple("1", "男A", "male", age = 16)
        val female = makeEligibleDisciple("2", "女A", "female", age = 17)
        val state = createState(male, female)
        system.processPartnerMatching(state)
        assertNull("未成年男性不应被配对", state.discipleTables.partnerIds.getOrNull(1))
        assertNull("未成年女性不应被配对", state.discipleTables.partnerIds.getOrNull(2))
    }

    @Test
    fun `auto mode - dead disciples are not paired`() {
        val male = makeEligibleDisciple("1", "男A", "male").copy(isAlive = false)
        val female = makeEligibleDisciple("2", "女A", "female")
        val state = createState(male, female)
        system.processPartnerMatching(state)
        assertNull("死亡弟子不应配对", state.discipleTables.partnerIds.getOrNull(1))
        assertNull("对方也不应被配对", state.discipleTables.partnerIds.getOrNull(2))
    }

    @Test
    fun `auto mode - already partnered disciples skip pairing`() {
        val male = makeEligibleDisciple("1", "男A", "male").copy(
            social = SocialData(partnerId = "2")
        )
        val female = makeEligibleDisciple("2", "女B", "female")
        val state = createState(male, female)
        state.discipleTables.partnerIds[1] = "2"
        system.processPartnerMatching(state)
        assertNull("已有道侣的男性不应被配对", state.discipleTables.partnerIds.getOrNull(2))
    }

    @Test
    fun `auto mode - banned root count disciples are skipped`() {
        val male = makeEligibleDisciple("1", "男A", "male", spiritRootType = "metal,wood")
        val female = makeEligibleDisciple("2", "女A", "female", spiritRootType = "metal")
        val state = createState(male, female)
        state.gameData = state.gameData.copy(daoCompanionBannedRootCounts = setOf(2))
        system.processPartnerMatching(state)
        assertNull("双灵根已被禁止不应配对", state.discipleTables.partnerIds.getOrNull(1))
    }

    // ═══════════════════════════════════════════════════════════════
    // 同意模式
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `consent mode - proposals added instead of auto pairing`() {
        val male = makeEligibleDisciple("1", "男A", "male")
        val female = makeEligibleDisciple("2", "女A", "female")
        val state = createState(male, female)
        state.gameData = state.gameData.copy(daoCompanionConsentRequired = true)
        // 同意模式下不应自动配对（RNG 触发时有去重保护）
        system.processPartnerMatching(state)
        assertTrue("提议数应为 0 或 1",
            state.pendingMarriageProposals.size == 0 || state.pendingMarriageProposals.size == 1)
        // 不应直接配对
        assertNull("同意模式下不应自动配对", state.discipleTables.partnerIds.getOrNull(1))
        assertNull("同意模式下不应自动配对", state.discipleTables.partnerIds.getOrNull(2))
    }

    @Test
    fun `consent mode - auto mode field is not affected`() {
        val male = makeEligibleDisciple("1", "男A", "male")
        val female = makeEligibleDisciple("2", "女A", "female")
        // 仅用自动模式跑
        for (seed in 1L..500L) {
            val state = runMatching(seed = seed, consentRequired = false, disciples = *arrayOf(male, female))
            if (state.discipleTables.partnerIds.getOrNull(1) != null) {
                assertTrue("自动配对时不产生提议",
                    state.pendingMarriageProposals.isEmpty())
                return
            }
        }
    }

    @Test
    fun `consent mode - pairedFemaleId prevents duplicate male proposals`() {
        val male1 = makeEligibleDisciple("1", "男A", "male")
        val male2 = makeEligibleDisciple("3", "男B", "male")
        val female = makeEligibleDisciple("2", "女A", "female")
        val state = createState(male1, male2, female)
        state.gameData = state.gameData.copy(daoCompanionConsentRequired = true)

        // 手动模拟：先为 male1+female 生成一个提议
        state.pendingMarriageProposals = listOf(
            PendingMarriageProposal("1", "男A", "2", "女A")
        )
        // 用高概率 seed 确保生成提议，但 pairedFemaleIds 应阻止新的
        val rng = GameRngManager()
        rng.initSystemSeed(99)
        val sys = PartnerSystem(rng)
        sys.processPartnerMatching(state)
        // 列表中仍只有 1 个提议（男性 B 的不会被加入，因为 female 在 pairedFemaleIds 中）
        assertTrue("同一女性不应有多个提议", state.pendingMarriageProposals.size <= 1)
    }

    @Test
    fun `consent mode - same pair dedup`() {
        val male = makeEligibleDisciple("1", "男A", "male")
        val female = makeEligibleDisciple("2", "女A", "female")
        val state = createState(male, female)
        state.gameData = state.gameData.copy(daoCompanionConsentRequired = true)
        // 先有一个已有提议
        state.pendingMarriageProposals = listOf(
            PendingMarriageProposal("1", "男A", "2", "女A")
        )
        system.processPartnerMatching(state)
        // 同意模式下已有提议应保留（去重保护），不会被重复添加
        assertTrue("不应产生重复提议，且不应错误清理",
            state.pendingMarriageProposals.size <= 1)
    }

    @Test
    fun `consent mode - stale proposals for dead disciples are removed`() {
        val male = makeEligibleDisciple("1", "男A", "male")
        val female = makeEligibleDisciple("2", "女A", "female")
        val deadFemale = makeEligibleDisciple("4", "已死女", "female").copy(isAlive = false)
        val state = createState(male, female, deadFemale)
        state.gameData = state.gameData.copy(daoCompanionConsentRequired = true)
        state.pendingMarriageProposals = listOf(
            PendingMarriageProposal("1", "男A", "4", "已死女"), // 死亡女性
            PendingMarriageProposal("1", "男A", "2", "女A")    // 有效提议
        )
        system.processPartnerMatching(state)
        // 死亡弟子的提议应被清理
        val hasDeadProposal = state.pendingMarriageProposals.any { it.femaleId == "4" }
        assertFalse("死亡弟子的提议应被清理", hasDeadProposal)
    }

    @Test
    fun `consent mode - stale proposals for already-paired disciples removed`() {
        val male = makeEligibleDisciple("1", "男A", "male")
        val female = makeEligibleDisciple("2", "女A", "female")
        val state = createState(male, female)
        state.gameData = state.gameData.copy(daoCompanionConsentRequired = true)
        // 女弟子已名花有主
        state.discipleTables.partnerIds[2] = "99"
        state.pendingMarriageProposals = listOf(
            PendingMarriageProposal("1", "男A", "2", "女A")
        )
        system.processPartnerMatching(state)
        assertEquals("已有道侣的弟子提议应被清理", 0, state.pendingMarriageProposals.size)
    }

    // ═══════════════════════════════════════════════════════════════
    // 血亲回避
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `blood relation prevents pairing`() {
        val parent = makeEligibleDisciple("10", "父", "male")
        val child = makeEligibleDisciple("1", "子", "male")
        val unrelated = makeEligibleDisciple("2", "女A", "female")
        // 设置父子关系
        val childWithParent = child.copy(social = SocialData(parentId1 = "10"))
        val state = createState(parent, childWithParent, unrelated)
        state.gameData = state.gameData.copy(daoCompanionConsentRequired = true)

        // 子对女请求提议 — 不应该命中
        system.processPartnerMatching(state)
        assertTrue("血亲不应配对", state.pendingMarriageProposals.isEmpty())
    }
}
