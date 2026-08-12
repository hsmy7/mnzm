package com.xianxia.sect.core.engine.service

import com.xianxia.sect.core.model.Disciple
import com.xianxia.sect.core.model.EquipmentSet
import com.xianxia.sect.core.model.GameData
import com.xianxia.sect.core.model.ManualType
import com.xianxia.sect.core.registry.ManualDatabase
import com.xianxia.sect.core.state.DiscipleTables
import com.xianxia.sect.core.state.EntityStore
import com.xianxia.sect.core.state.MutableGameState
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.UUID

/**
 * 招募服务单元测试 — 覆盖 [RecruitService] 的批量/自动招募逻辑。
 *
 * 注：refreshRecruitList 的完整测试依赖真实 GameStateStore，当前聚焦
 * processAutoRecruit 的边界条件和 calcRecruitBonusCap 的算术正确性。
 * 需要 Robolectric：DiscipleTables 的 ComponentTable 底层是 android.util.SparseArray，
 * 纯 JVM 环境（returnDefaultValues）下写入会静默丢失。
 */
@RunWith(RobolectricTestRunner::class)
class RecruitServiceTest {

    // ==================== calcRecruitBonusCap ====================

    @Test
    fun `calcRecruitBonusCap - charm below 80 returns 0`() {
        assertEquals(0, RecruitService.calcRecruitBonusCap(50))
        assertEquals(0, RecruitService.calcRecruitBonusCap(79))
    }

    @Test
    fun `calcRecruitBonusCap - charm at 80 returns 0`() {
        assertEquals(0, RecruitService.calcRecruitBonusCap(80))
    }

    @Test
    fun `calcRecruitBonusCap - charm 84 returns 1`() {
        assertEquals(1, RecruitService.calcRecruitBonusCap(84))
    }

    @Test
    fun `calcRecruitBonusCap - charm 100 returns 5`() {
        assertEquals(5, RecruitService.calcRecruitBonusCap(100))
    }

    @Test
    fun `calcRecruitBonusCap - boundary rounding`() {
        assertEquals(0, RecruitService.calcRecruitBonusCap(83)) // (83-80)/4 = 0
        assertEquals(1, RecruitService.calcRecruitBonusCap(84)) // (84-80)/4 = 1
        assertEquals(1, RecruitService.calcRecruitBonusCap(87)) // (87-80)/4 = 1
    }

    @Test
    fun `calcRecruitBonusCap - very high charm caps at MAX_RECRUIT_BONUS_CAP 20`() {
        assertEquals(20, RecruitService.calcRecruitBonusCap(200)) // (200-80)/4 = 30 → 上限 20
        assertEquals(20, RecruitService.calcRecruitBonusCap(1000)) // (1000-80)/4 = 230 → 上限 20
    }

    @Before
    fun setUp() {
        // 重置惰性状态，防止跨测试污染
        RecruitService.RecruitLazyState.autoRecruitIdle = false
        RecruitService.RecruitLazyState.autoRejectIdle = false
    }

    @After
    fun tearDown() {
        // 恢复全局单例，避免注入的测试功法库污染其他测试类
        ManualDatabase.resetForTest()
    }

    // ==================== processAutoRecruit ====================

    /** 创建测试用 MutableGameState，含空的 DiscipleTables 并开放写权限。 */
    private fun createAutoRecruitState(
        recruitList: List<Disciple>,
        filter: Set<Int> = emptySet()
    ): MutableGameState {
        val tables = DiscipleTables()
        tables.writeAllowed = true
        return MutableGameState(
            gameData = GameData(
                recruitList = recruitList,
                autoRecruitSpiritRootFilter = filter
            ),
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
            isPaused = false,
            isLoading = false,
            isSaving = false
        )
    }

    /** 创建测试用弟子 */
    private fun makeRecruit(
        id: String = "test_${UUID.randomUUID()}",
        name: String = "测试弟子",
        age: Int = 20,
        realm: Int = 9,
        spiritRootType: String = "金,木,水"
    ): Disciple = Disciple(
        id = id,
        name = name,
        age = age,
        realm = realm,
        spiritRootType = spiritRootType
    )

    @Test
    fun `processAutoRecruit with matching filter recruits matching disciples`() {
        val disciple = makeRecruit(spiritRootType = "金,木,水")  // 3 roots
        val state = createAutoRecruitState(
            recruitList = listOf(disciple),
            filter = setOf(3)  // 三灵根
        )

        val count = RecruitService.processAutoRecruit(state)

        assertEquals(1, count)
        assertTrue("自动招募后 recruitList 应为空", state.gameData.recruitList.isEmpty())
        assertEquals("弟子应已加入 discipleTables", 1, state.discipleTables.ids.size)
        val recruitedId = state.discipleTables.ids.first()
        assertEquals("弟子境界应匹配", disciple.realm, state.discipleTables.realms[recruitedId])
        // 年报新增弟子计数（2026-08-11 修复：自动招募主路径漏计）
        assertEquals("年报新增弟子计数=成功招募数", 1, state.gameData.annualNewDisciples)
    }

    @Test
    fun `processAutoRecruit with empty filter recruits nothing`() {
        val disciple = makeRecruit(spiritRootType = "金,木,水")
        val state = createAutoRecruitState(
            recruitList = listOf(disciple),
            filter = emptySet()
        )

        val count = RecruitService.processAutoRecruit(state)

        assertEquals(0, count)
        assertTrue("不应有弟子上架", state.discipleTables.ids.isEmpty())
        assertEquals("recruitList 应保持不变", 1, state.gameData.recruitList.size)
        assertEquals("0 人招募不计入年报新增", 0, state.gameData.annualNewDisciples)
    }

    @Test
    fun `processAutoRecruit filters non-matching root counts`() {
        val disciple = makeRecruit(spiritRootType = "金,木,水", id = "id1")  // 3 roots
        val state = createAutoRecruitState(
            recruitList = listOf(disciple),
            filter = setOf(1, 5)  // 只收单灵根/五灵根
        )

        val count = RecruitService.processAutoRecruit(state)

        assertEquals(0, count)
        assertTrue("不应有弟子上架", state.discipleTables.ids.isEmpty())
        assertEquals("弟子应留在 recruitList", 1, state.gameData.recruitList.size)
    }

    @Test
    fun `processAutoRecruit - 俘虏带装备功法落库为玩家实例`() {
        ManualDatabase.initializeWithManuals(mapOf(
            "testAtk1" to ManualDatabase.ManualTemplate(
                id = "testAtk1", name = "烈阳剑诀", type = ManualType.ATTACK,
                rarity = 4, description = "测试功法",
                stats = mapOf("cultivationSpeedPercent" to 40)
            )
        ))
        val captive = makeRecruit(spiritRootType = "金").copy(
            manualIds = listOf("testAtk1"),
            manualMasteries = mapOf("testAtk1" to 2000),
            equipment = EquipmentSet(weaponId = "ironSword")
        )
        val state = createAutoRecruitState(
            recruitList = listOf(captive),
            filter = setOf(1)  // 单灵根
        )

        val count = RecruitService.processAutoRecruit(state)

        assertEquals(1, count)
        val newId = state.discipleTables.ids.first().toString()
        // 装备实例落库（模板 id → UUID 实例）
        assertEquals("装备实例应落库", 1, state.equipmentInstances.size)
        val weaponInstance = state.equipmentInstances.first()
        assertEquals("实例 ownerId 应为新弟子 id", newId, weaponInstance.ownerId)
        assertTrue("应标记已装备", weaponInstance.isEquipped)
        // 功法实例落库 + 熟练度注册
        assertEquals("功法实例应落库", 1, state.manualInstances.size)
        val profs = state.gameData.manualProficiencies[newId]
        assertNotNull("熟练度应注册", profs)
        assertEquals("熟练度值应继承", 2000.0, requireNotNull(profs).first().proficiency, 0.001)
        // 槽位列回写实例 id
        val intId = requireNotNull(newId.toIntOrNull())
        assertEquals("weaponIds 列应回写实例 id", weaponInstance.id, state.discipleTables.weaponIds[intId])
    }

    @Test
    fun `processAutoRecruit handles mixed matches and non-matches`() {
        val match = makeRecruit("id1", "单灵根弟子", spiritRootType = "金")
        val noMatch = makeRecruit("id2", "三灵根弟子", spiritRootType = "金,木,水")
        val state = createAutoRecruitState(
            recruitList = listOf(match, noMatch),
            filter = setOf(1)
        )

        val count = RecruitService.processAutoRecruit(state)

        assertEquals(1, count)
        assertTrue("recruitList 应只剩 1 人", state.gameData.recruitList.size == 1)
        assertEquals("剩下的是不匹配的弟子", "三灵根弟子", state.gameData.recruitList.first().name)
    }

    @Test
    fun `processAutoRecruit with twin recruits recruits only one`() {
        // 同内容双胞胎（不同 id）应只自动招募 1 人（三级去重）
        val d1 = makeRecruit(name = "双胞胎", spiritRootType = "金")
        val d2 = makeRecruit(name = "双胞胎", spiritRootType = "金")
        val state = createAutoRecruitState(
            recruitList = listOf(d1, d2),
            filter = setOf(1)
        )

        val count = RecruitService.processAutoRecruit(state)

        assertEquals(1, count)
        assertEquals("DiscipleTables 只新增 1 人", 1, state.discipleTables.ids.size)
    }

    @Test
    fun `processAutoRecruit skips corrupted disciples with blank name`() {
        val state = createAutoRecruitState(
            recruitList = listOf(makeRecruit(name = "")),
            filter = setOf(3)
        )
        assertEquals(0, RecruitService.processAutoRecruit(state))
        assertTrue(state.discipleTables.ids.isEmpty())
    }

    @Test
    fun `processAutoRecruit skips corrupted disciples with age zero`() {
        val state = createAutoRecruitState(
            recruitList = listOf(makeRecruit(age = 0)),
            filter = setOf(3)
        )
        assertEquals(0, RecruitService.processAutoRecruit(state))
        assertTrue(state.discipleTables.ids.isEmpty())
    }

    @Test
    fun `processAutoRecruit skips corrupted disciples with realm out of range`() {
        val state = createAutoRecruitState(
            recruitList = listOf(makeRecruit(realm = -1)),
            filter = setOf(3)
        )
        assertEquals(0, RecruitService.processAutoRecruit(state))
        assertTrue(state.discipleTables.ids.isEmpty())
    }

    @Test
    fun `processAutoRecruit with empty recruitList returns 0`() {
        val state = createAutoRecruitState(emptyList(), filter = setOf(1, 2, 3))
        assertEquals(0, RecruitService.processAutoRecruit(state))
    }

    @Test
    fun `processAutoRecruit recruits newborn age 1 disciple`() {
        val state = createAutoRecruitState(
            recruitList = listOf(makeRecruit(age = 1, spiritRootType = "金")),
            filter = setOf(1)
        )
        assertEquals(1, RecruitService.processAutoRecruit(state))
        assertTrue("新生儿应被自动招募", state.discipleTables.ids.isNotEmpty())
    }

    @Test
    fun `processAutoRecruit with invalid filter values filters them out`() {
        // filter 中混入 0 和 6（无效值），应被剔除
        val disciple = makeRecruit(spiritRootType = "金,木,水") // 3 roots
        val state = createAutoRecruitState(
            recruitList = listOf(disciple),
            filter = setOf(0, 3, 6)
        )
        assertEquals(1, RecruitService.processAutoRecruit(state))
    }

    @Test
    fun `processAutoRecruit with only invalid filter values returns 0`() {
        val state = createAutoRecruitState(
            recruitList = listOf(makeRecruit()),
            filter = setOf(0, 6, 999)
        )
        assertEquals(0, RecruitService.processAutoRecruit(state))
    }

    // ==================== Monthly limit ====================

    @Test
    fun `processAutoRecruit respects monthly limit`() {
        val d1 = makeRecruit("id1", "弟子1", spiritRootType = "金")
        val d2 = makeRecruit("id2", "弟子2", spiritRootType = "金")
        val d3 = makeRecruit("id3", "弟子3", spiritRootType = "金")
        val state = createAutoRecruitState(
            recruitList = listOf(d1, d2, d3),
            filter = setOf(1)
        )
        // 模拟本月已招募 29 人，剩下 1 个配额
        state.gameData = state.gameData.copy(recruitCountThisMonth = 29)

        val count = RecruitService.processAutoRecruit(state)

        assertEquals("只应招募 1 人", 1, count)
        assertEquals("月度计数应为 30", 30, state.gameData.recruitCountThisMonth)
        assertEquals("recruitList 应剩 2 人", 2, state.gameData.recruitList.size)
    }

    @Test
    fun `processAutoRecruit returns 0 when monthly limit reached`() {
        val d = makeRecruit(spiritRootType = "金")
        val state = createAutoRecruitState(
            recruitList = listOf(d),
            filter = setOf(1)
        )
        state.gameData = state.gameData.copy(recruitCountThisMonth = 30)

        assertEquals(0, RecruitService.processAutoRecruit(state))
        assertEquals("月度计数不变", 30, state.gameData.recruitCountThisMonth)
        assertTrue("弟子应留在 recruitList", state.gameData.recruitList.isNotEmpty())
    }

    // ==================== processAutoReject ====================

    @Test
    fun `processAutoReject removes matching disciples`() {
        val match = makeRecruit("id1", "单灵根", spiritRootType = "金")
        val keep = makeRecruit("id2", "三灵根", spiritRootType = "金,木,水")
        val state = createAutoRecruitState(
            recruitList = listOf(match, keep)
        )
        // 设置自动拒绝 filter：单灵根
        state.gameData = state.gameData.copy(autoRejectSpiritRootFilter = setOf(1))

        val count = RecruitService.processAutoReject(state)

        assertEquals(1, count)
        assertEquals("应只剩保留的弟子", 1, state.gameData.recruitList.size)
        assertEquals("保留的是三灵根", "三灵根", state.gameData.recruitList.first().name)
    }

    @Test
    fun `processAutoReject with empty filter returns 0`() {
        val state = createAutoRecruitState(
            recruitList = listOf(makeRecruit()),
            filter = setOf(1)
        )
        assertEquals(0, RecruitService.processAutoReject(state))
    }

    @Test
    fun `processAutoReject with no matching disciples returns 0`() {
        val d = makeRecruit(spiritRootType = "金,木")
        val state = createAutoRecruitState(recruitList = listOf(d))
        state.gameData = state.gameData.copy(autoRejectSpiritRootFilter = setOf(1))
        assertEquals(0, RecruitService.processAutoReject(state))
    }

    // ==================== processRecruitAging ====================

    @Test
    fun `processRecruitAging removes recruit past max age`() {
        // lifespan=80, realm=9 → computeMaxAge=80, age=80 老化后 81 >= 80 → 死亡
        val dead = makeRecruit("id1", "将死弟子", age = 80, realm = 9)
        val state = createAutoRecruitState(recruitList = listOf(dead))

        RecruitService.processRecruitAging(state)

        assertTrue("超龄弟子应从列表移除", state.gameData.recruitList.isEmpty())
    }

    @Test
    fun `processRecruitAging keeps recruit under max age`() {
        val young = makeRecruit("id1", "年轻弟子", age = 50, realm = 9)
        val state = createAutoRecruitState(recruitList = listOf(young))

        RecruitService.processRecruitAging(state)

        assertEquals(1, state.gameData.recruitList.size)
        assertEquals("年龄应 +1", 51, state.gameData.recruitList.first().age)
    }

    @Test
    fun `processRecruitAging keeps recruit at boundary age`() {
        // age=79 老化后 80 == computeMaxAge=80 → 死亡
        val boundary = makeRecruit("id1", "边界弟子", age = 79, realm = 9)
        val state = createAutoRecruitState(recruitList = listOf(boundary))

        RecruitService.processRecruitAging(state)

        assertTrue("age=79 的弟子老化到 80 ≥ maxAge(80) 应死亡", state.gameData.recruitList.isEmpty())
    }

    // ==================== Lazy mechanism ====================

    @Test
    fun `processAutoRecruit enters idle when no matching disciples`() {
        val d = makeRecruit(spiritRootType = "金,木,水") // 三灵根
        val state = createAutoRecruitState(
            recruitList = listOf(d),
            filter = setOf(1) // 筛单灵根
        )

        RecruitService.processAutoRecruit(state)

        assertTrue("无匹配弟子后应进入惰性", RecruitService.RecruitLazyState.autoRecruitIdle)
    }

    @Test
    fun `processAutoRecruit does not process when idle`() {
        RecruitService.RecruitLazyState.autoRecruitIdle = true

        val d = makeRecruit(spiritRootType = "金", name = "单灵根弟子")
        val state = createAutoRecruitState(
            recruitList = listOf(d),
            filter = setOf(1)
        )

        val count = RecruitService.processAutoRecruit(state)

        assertEquals("惰性状态下应跳过", 0, count)
        assertTrue("弟子应仍留在列表", state.gameData.recruitList.isNotEmpty())
    }

    @Test
    fun `processAutoReject enters idle when no matching disciples`() {
        val d = makeRecruit(spiritRootType = "金,木,水")
        val state = createAutoRecruitState(recruitList = listOf(d))
        state.gameData = state.gameData.copy(autoRejectSpiritRootFilter = setOf(1))

        RecruitService.processAutoReject(state)

        assertTrue("无匹配拒绝弟子后应进入惰性", RecruitService.RecruitLazyState.autoRejectIdle)
    }

    @Test
    fun `lazy state reset allows processing again`() {
        // 首次触发惰性
        RecruitService.RecruitLazyState.autoRecruitIdle = true
        // 重置（模拟新增弟子或变更选项）
        RecruitService.RecruitLazyState.autoRecruitIdle = false

        val d = makeRecruit(spiritRootType = "金", name = "可招募弟子")
        val state = createAutoRecruitState(
            recruitList = listOf(d),
            filter = setOf(1)
        )

        val count = RecruitService.processAutoRecruit(state)
        assertEquals("重置后应正常招募", 1, count)
    }

    // ==================== sanitizeRecruitList ====================

    @Test
    fun `sanitizeRecruitList - 存在损坏 移除并复位惰性`() {
        val state = createAutoRecruitState(
            recruitList = listOf(
                makeRecruit(name = ""),
                makeRecruit(name = "正常弟子")
            )
        )
        RecruitService.RecruitLazyState.autoRecruitIdle = true

        val removed = RecruitService.sanitizeRecruitList(state)

        assertEquals(1, removed)
        assertEquals("损坏条目应被移除", 1, state.gameData.recruitList.size)
        assertEquals("正常弟子", state.gameData.recruitList.first().name)
        assertTrue("有移除应复位惰性", !RecruitService.RecruitLazyState.autoRecruitIdle)
    }

    @Test
    fun `sanitizeRecruitList - 无损坏 列表不变不复位`() {
        val state = createAutoRecruitState(
            recruitList = listOf(makeRecruit(name = "正常弟子"))
        )
        RecruitService.RecruitLazyState.autoRecruitIdle = true

        val removed = RecruitService.sanitizeRecruitList(state)

        assertEquals(0, removed)
        assertEquals(1, state.gameData.recruitList.size)
        assertTrue("无移除不应复位惰性", RecruitService.RecruitLazyState.autoRecruitIdle)
    }

    @Test
    fun `sanitizeRecruitList - 已入宗门残留 移除`() {
        val state = createAutoRecruitState(
            recruitList = listOf(makeRecruit(name = "张三", age = 20))
        )
        // 模拟同内容弟子已在宗门（跨表比对）
        val inSect = makeRecruit(name = "张三", age = 20)
        state.discipleTables.writeAllowed = true
        state.discipleTables.allocateAndInsert(inSect)

        val removed = RecruitService.sanitizeRecruitList(state)

        assertEquals(1, removed)
        assertTrue("残留应被移除", state.gameData.recruitList.isEmpty())
    }

    @Test
    fun `sanitizeRecruitList - 38岁炼虚 保留`() {
        val state = createAutoRecruitState(
            recruitList = listOf(makeRecruit(name = "天才", age = 38, realm = 4))
        )

        val removed = RecruitService.sanitizeRecruitList(state)

        assertEquals(0, removed)
        assertEquals(1, state.gameData.recruitList.size)
    }
}
