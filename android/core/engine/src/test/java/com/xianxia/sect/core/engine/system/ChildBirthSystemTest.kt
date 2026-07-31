package com.xianxia.sect.core.engine.system

import com.xianxia.sect.core.engine.domain.disciple.DiscipleFactory
import com.xianxia.sect.core.model.Disciple
import com.xianxia.sect.core.model.DiscipleStatus
import com.xianxia.sect.core.model.GameData
import com.xianxia.sect.core.model.SocialData
import com.xianxia.sect.core.state.DiscipleTables
import com.xianxia.sect.core.state.EntityStore
import com.xianxia.sect.core.state.MutableGameState
import com.xianxia.sect.core.state.WriteGuardRule
import com.xianxia.sect.core.util.GameRngManager
import com.xianxia.sect.core.util.RngPartition
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.mockito.kotlin.mock

/**
 * ChildBirthSystem 测试 — 受孕/分娩流程 + SYSTEM 分区 PRNG 确定性。
 * ChildBirthSystem 的方法只经 MutableGameState 参数访问状态，stateStore 未使用，用 mock 注入。
 */
class ChildBirthSystemTest {

    @get:Rule val writeGuardRule = WriteGuardRule()

    private lateinit var rngManager: GameRngManager
    private lateinit var system: ChildBirthSystem

    @Before
    fun setup() {
        rngManager = GameRngManager()
        rngManager.initSystemSeed(42) // 固定种子保证可复现
        system = createSystem(rngManager)
    }

    private fun createSystem(rng: GameRngManager) = ChildBirthSystem(
        stateStore = mock(),
        discipleFactory = DiscipleFactory(),
        rngManager = rng
    )

    /** 造父/母：gameYear=3、gameMonth=5，母亲到月（childBirthMonth=5）可直接分娩 */
    private fun createParents(
        fatherRoot: String = "metal",
        motherRoot: String = "wood",
        fatherAlive: Boolean = true
    ): Pair<Disciple, Disciple> {
        val father = Disciple(
            id = "2",
            name = "父亲",
            surname = "赵",
            gender = "male",
            age = 28,
            isAlive = fatherAlive,
            status = DiscipleStatus.IDLE,
            spiritRootType = fatherRoot,
            portraitRes = "portrait_default",
            social = SocialData()
        )
        val mother = Disciple(
            id = "1",
            name = "母亲",
            surname = "王",
            gender = "female",
            age = 25,
            isAlive = true,
            status = DiscipleStatus.IDLE,
            spiritRootType = motherRoot,
            portraitRes = "portrait_default",
            social = SocialData(partnerId = "2", childBirthMonth = 5)
        )
        return father to mother
    }

    private fun createState(
        father: Disciple,
        mother: Disciple,
        gameYear: Int = 3,
        gameMonth: Int = 5
    ): MutableGameState {
        val tables = DiscipleTables()
        tables.insert(father)
        tables.insert(mother)
        return MutableGameState(
            gameData = GameData(gameYear = gameYear, gameMonth = gameMonth),
            discipleTables = tables,
            equipmentStacks = EntityStore(),
            equipmentInstances = EntityStore(),
            manualStacks = EntityStore(),
            manualInstances = EntityStore(),
            pills = EntityStore(),
            materials = EntityStore(),
            herbs = EntityStore(),
            seeds = EntityStore(),
            storageBags = EntityStore(),
            battleLogs = emptyList(),
            teams = emptyList(),
            isPaused = false,
            isLoading = false,
            isSaving = false,
            pendingNotification = null,
            pendingMarriageProposals = emptyList()
        )
    }

    // ═══════════════════════════════════════════════════════════════
    // 分娩路径
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `monthly birth - 到月母亲分娩追加新生儿并重置状态`() {
        val (father, mother) = createParents()
        val state = createState(father, mother)

        system.onMonthlyEvent(state)

        val baby = requireNotNull(state.gameData.recruitList.singleOrNull()) {
            "新生儿应追加到 recruitList"
        }
        assertEquals("新生儿 1 岁", 1, baby.age)
        val motherAfter = state.discipleTables.assemble(1)
        assertNull("母亲 childBirthMonth 应重置", motherAfter.social.childBirthMonth)
        assertEquals("母亲 lastChildYear 应为当前年", 3, motherAfter.social.lastChildYear)
    }

    @Test
    fun `monthly birth - 父亲死亡时清除受孕状态且不产新生儿`() {
        val (father, mother) = createParents(fatherAlive = false)
        val state = createState(father, mother)

        system.onMonthlyEvent(state)

        assertTrue("父亲死亡不应产新生儿", state.gameData.recruitList.isEmpty())
        val motherAfter = state.discipleTables.assemble(1)
        assertNull("母亲 childBirthMonth 应清除", motherAfter.social.childBirthMonth)
        assertNull("母亲 partnerId 应清除", motherAfter.social.partnerId)
    }

    @Test
    fun `monthly birth - 新生儿姓氏继承父亲`() {
        val (father, mother) = createParents()
        val state = createState(father, mother)

        system.onMonthlyEvent(state)

        val baby = state.gameData.recruitList.single()
        assertEquals("新生儿应继承父亲姓氏", "赵", baby.surname)
    }

    @Test
    fun `monthly birth - 多种子下性别与灵根继承分布正确`() {
        val genders = mutableSetOf<String>()
        val inheritedRoots = mutableSetOf<String>()
        for (seed in 1L..200L) {
            val rng = GameRngManager()
            rng.initSystemSeed(seed)
            val sys = createSystem(rng)
            val (father, mother) = createParents()
            val state = createState(father, mother)
            sys.onMonthlyEvent(state)
            val baby = state.gameData.recruitList.firstOrNull() ?: continue
            genders += baby.gender
            if (baby.spiritRootType in setOf("metal", "wood")) {
                inheritedRoots += baby.spiritRootType
            }
        }
        assertTrue("多种子下应出现男女两性", genders.containsAll(setOf("male", "female")))
        assertTrue("新生儿应能继承父/母灵根", inheritedRoots.isNotEmpty())
    }

    // ═══════════════════════════════════════════════════════════════
    // 受孕路径 + 确定性
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `yearly conception - 同种子两次年变结果一致且 SYSTEM 分区推进`() {
        // 扫描种子，找一个受孕命中的（受孕概率 0.5%）
        var hitSeed = 0L
        for (seed in 1L..5000L) {
            val rng = GameRngManager()
            rng.initSystemSeed(seed)
            val sys = createSystem(rng)
            val (father, mother) = createParents()
            val state = createState(father, mother)
            sys.onYearlyEvent(state)
            val conceived = state.discipleTables.assembleAll()
                .any { it.social.childBirthMonth != null }
            if (conceived) {
                hitSeed = seed
                break
            }
        }
        assertTrue("应能找到受孕命中的种子", hitSeed > 0)

        // 同种子两次独立运行，受孕结果一致（确定性）
        val rngA = GameRngManager()
        rngA.initSystemSeed(hitSeed)
        val initialSystemState = rngA.exportStates()[RngPartition.SYSTEM.id]
        val sysA = createSystem(rngA)
        val (fatherA, motherA) = createParents()
        val stateA = createState(fatherA, motherA)
        sysA.onYearlyEvent(stateA)

        val rngB = GameRngManager()
        rngB.initSystemSeed(hitSeed)
        val sysB = createSystem(rngB)
        val (fatherB, motherB) = createParents()
        val stateB = createState(fatherB, motherB)
        sysB.onYearlyEvent(stateB)

        val monthsA = stateA.discipleTables.assembleAll().map { it.social.childBirthMonth }
        val monthsB = stateB.discipleTables.assembleAll().map { it.social.childBirthMonth }
        assertEquals("同种子两次年变受孕结果应一致", monthsA, monthsB)

        val afterSystemState = rngA.exportStates()[RngPartition.SYSTEM.id]
        assertNotEquals("SYSTEM 分区 PRNG 状态应推进", initialSystemState, afterSystemState)
        assertEquals("同种子两次运行后 SYSTEM 分区状态一致", rngA.exportStates(), rngB.exportStates())
    }
}
