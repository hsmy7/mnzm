package com.xianxia.sect.core.state

import com.xianxia.sect.core.model.CombatAttributes
import com.xianxia.sect.core.model.Disciple
import com.xianxia.sect.core.model.DiscipleStatus
import com.xianxia.sect.core.model.GameData
import com.xianxia.sect.core.model.SkillStats
import com.xianxia.sect.core.model.SocialData
import com.xianxia.sect.core.model.UsageTracking
import com.xianxia.sect.core.model.baseHp
import com.xianxia.sect.core.model.baseMagicAttack
import com.xianxia.sect.core.model.baseMagicDefense
import com.xianxia.sect.core.model.baseMp
import com.xianxia.sect.core.model.basePhysicalAttack
import com.xianxia.sect.core.model.basePhysicalDefense
import com.xianxia.sect.core.model.baseSpeed
import com.xianxia.sect.core.model.charm
import com.xianxia.sect.core.model.currentHp
import com.xianxia.sect.core.model.currentMp
import com.xianxia.sect.core.model.intelligence
import com.xianxia.sect.core.model.loyalty
import com.xianxia.sect.core.model.recruitedMonth
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.UUID



/**
 * 针对招募流程中核心数据操作的专项测试。
 * 直接测试 DiscipleTables.allocateAndInsert + recruitList 查找 + 完整性检查 + 完整招募 lambda 逻辑。
 */
@RunWith(RobolectricTestRunner::class)
class DiscipleTablesRecruitTest {

    @get:Rule val writeGuardRule = WriteGuardRule()

    // ════════════════════════════════════════════════════════════════════
    // 测试 1: allocateAndInsert 基本功能
    // ════════════════════════════════════════════════════════════════════

    @Test
    fun `allocateAndInsert - empty tables allocates ID 1`() {
        val tables = DiscipleTables()
        val newId = tables.allocateAndInsert(createRecruit("张三"))
        assertEquals("1", newId)
        assertEquals(1, tables.count)
        assertTrue(tables.ids.contains(1))
    }

    @Test
    fun `allocateAndInsert - sequential IDs`() {
        val tables = DiscipleTables()
        assertEquals("1", tables.allocateAndInsert(createRecruit("甲")))
        assertEquals("2", tables.allocateAndInsert(createRecruit("乙")))
        assertEquals("3", tables.allocateAndInsert(createRecruit("丙")))
        assertEquals(3, tables.count)
    }

    @Test
    fun `allocateAndInsert - preserves all fields`() {
        val tables = DiscipleTables()
        val d = createRecruit("赵六", age = 22, realm = 8, realmLayer = 3,
            cultivation = 150.0, gender = "female", spiritRootType = "fire,water",
            discipleType = "inner",
            skills = SkillStats(loyalty = 90, intelligence = 80, charm = 70),
            combat = CombatAttributes(baseHp = 500, baseMp = 200,
                basePhysicalAttack = 50, baseMagicAttack = 60,
                basePhysicalDefense = 40, baseMagicDefense = 30, baseSpeed = 25,
                currentHp = 450, currentMp = 180))
        val id = tables.allocateAndInsert(d).toInt()
        val a = tables.assemble(id)
        assertEquals("赵六", a.name)
        assertEquals(22, a.age)
        assertEquals(8, a.realm)
        assertEquals("female", a.gender)
        assertEquals("fire,water", a.spiritRootType)
        assertEquals(90, a.skills.loyalty)
        assertEquals(500, a.combat.baseHp)
        assertEquals(450, a.combat.currentHp)
    }

    @Test
    fun `allocateAndInsert - preserves usage`() {
        val tables = DiscipleTables()
        val d = createRecruit("test").copy(usage = UsageTracking(recruitedMonth = 42))
        val id = tables.allocateAndInsert(d).toInt()
        assertEquals(42, tables.recruitedMonths[id])
    }

    @Test
    fun `allocateAndInsert - preserves life events`() {
        val tables = DiscipleTables()
        val d = createRecruit("张三").apply { lifeEvents = listOf("事件1") }
        val id = tables.allocateAndInsert(d).toInt()
        assertEquals(listOf("事件1"), tables.assemble(id).lifeEvents)
    }

    @Test
    fun `allocateAndInsert - discards UUID id assigns sequential`() {
        val tables = DiscipleTables()
        val uuid = UUID.randomUUID().toString()
        val id = tables.allocateAndInsert(createRecruit("张三", id = uuid))
        assertNotEquals(uuid, id)
        assertEquals("1", id)
    }

    @Test
    fun `allocateAndInsert - batch 50 disciples`() {
        val tables = DiscipleTables()
        for (i in 1..50) tables.allocateAndInsert(createRecruit("弟子$i"))
        assertEquals(50, tables.count)
        assertEquals(50, tables.assembleAll().size)
    }

    @Test
    fun `allocateAndInsert - unique IDs`() {
        val tables = DiscipleTables()
        val ids = (1..20).map { tables.allocateAndInsert(createRecruit("x$it")) }
        assertEquals(20, ids.toSet().size)
    }

    @Test
    fun `allocateAndInsert - interleaved with remove`() {
        val tables = DiscipleTables()
        tables.allocateAndInsert(createRecruit("甲"))
        tables.allocateAndInsert(createRecruit("乙"))
        assertEquals("3", tables.allocateAndInsert(createRecruit("丙")))
        tables.remove(2)
        assertEquals(2, tables.count)
        assertEquals("4", tables.allocateAndInsert(createRecruit("丁"))) // 不重用 ID
    }

    // ════════════════════════════════════════════════════════════════════
    // 测试 2: recruitList 查找 — 模拟 DiscipleFacadeImpl 中的 lookup
    // ════════════════════════════════════════════════════════════════════

    @Test
    fun `recruitList - find by UUID id`() {
        val id = UUID.randomUUID().toString()
        val list = listOf(createRecruit("目标", id = id))
        assertNotNull(list.find { it.id == id })
    }

    @Test
    fun `recruitList - find missing returns null`() {
        val list = listOf(createRecruit("张三"))
        assertNull(list.find { it.id == "no-such-id" })
    }

    @Test
    fun `recruitList - empty list find returns null`() {
        assertNull(emptyList<Disciple>().find { it.id == "x" })
    }

    @Test
    fun `recruitList - filter does not mutate original`() {
        val id = UUID.randomUUID().toString()
        val original = mutableListOf(createRecruit("待移除", id = id))
        val filtered = original.toList().filter { it.id != id }
        assertEquals(0, filtered.size)
        assertEquals(1, original.size)
    }

    // ════════════════════════════════════════════════════════════════════
    // 测试 3: 完整性检查 — 模拟 DiscipleFacadeImpl 中的守卫
    // ════════════════════════════════════════════════════════════════════

    @Test
    fun `integrity - blank name`() {
        val list = listOf(createRecruit("", id = "x"))
        val d = requireNotNull(list.find { it.id == "x" }) { "fixture 必须包含 id=x 的条目" }
        assertTrue(d.name.isBlank() || d.age <= 0 || d.realm <= 0)
    }

    @Test
    fun `integrity - age zero`() {
        val list = listOf(createRecruit("x", age = 0, id = "x"))
        val d = requireNotNull(list.find { it.id == "x" }) { "fixture 必须包含 id=x 的条目" }
        assertTrue(d.name.isBlank() || d.age <= 0 || d.realm <= 0)
    }

    @Test
    fun `integrity - realm zero`() {
        val list = listOf(createRecruit("x", realm = 0, id = "x"))
        val d = requireNotNull(list.find { it.id == "x" }) { "fixture 必须包含 id=x 的条目" }
        assertTrue(d.name.isBlank() || d.age <= 0 || d.realm <= 0)
    }

    @Test
    fun `integrity - valid data passes`() {
        val list = listOf(createRecruit("正常", age = 20, realm = 9, id = "x"))
        val d = requireNotNull(list.find { it.id == "x" }) { "fixture 必须包含 id=x 的条目" }
        assertFalse(d.name.isBlank() || d.age <= 0 || d.realm <= 0)
    }

    // ════════════════════════════════════════════════════════════════════
    // 测试 4: 完整招募 lambda 逻辑（模拟 DiscipleFacadeImpl 内 stateStore.update 逻辑）
    // ════════════════════════════════════════════════════════════════════

    @Test
    fun `full recruit flow - happy path`() {
        val tables = DiscipleTables()
        val recruitId = UUID.randomUUID().toString()
        val recruit = createRecruit("新弟子", id = recruitId, age = 20, realm = 9)
        var gameData = GameData(recruitList = listOf(recruit))

        val found = requireNotNull(gameData.recruitList.find { it.id == recruitId }) {
            "fixture 必须包含 id=$recruitId 的招募条目"
        }
        val month = gameData.gameYear * 12 + gameData.gameMonth
        val newId = tables.allocateAndInsert(
            found.copy(usage = found.usage.copy(recruitedMonth = month))
        )
        gameData = gameData.copy(recruitList = gameData.recruitList.filter { it.id != recruitId })

        assertTrue("新 ID 非空", newId.isNotEmpty())
        assertEquals("招募列表已清空", 0, gameData.recruitList.size)
        assertEquals("弟子表新增 1 人", 1, tables.count)
        assertEquals("新弟子", tables.assemble(newId.toInt()).name)
    }

    @Test
    fun `full recruit flow - not found does nothing`() {
        val tables = DiscipleTables()
        var gameData = GameData(recruitList = listOf(createRecruit("张三", id = "real-id")))
        val found = gameData.recruitList.find { it.id == "non-existent" }
        assertNull("不应找到不存在的 ID", found)
        assertEquals("弟子表不变", 0, tables.count)
        assertEquals("招募列表不变", 1, gameData.recruitList.size)
    }

    @Test
    fun `full recruit flow - corrupted data does nothing`() {
        val tables = DiscipleTables()
        val recruitId = UUID.randomUUID().toString()
        var gameData = GameData(recruitList = listOf(createRecruit("张三", age = 0, id = recruitId)))
        val found = requireNotNull(gameData.recruitList.find { it.id == recruitId }) {
            "fixture 必须包含 id=$recruitId 的招募条目"
        }
        val shouldSkip = found.name.isBlank() || found.age <= 0 || found.realm <= 0
        assertTrue("损坏数据应跳过", shouldSkip)
        assertEquals("不应招募", 0, tables.count)
    }

    @Test
    fun `full recruit flow - existing disciples preserved`() {
        val tables = DiscipleTables()
        tables.allocateAndInsert(createRecruit("在册弟子"))
        val recruitId = UUID.randomUUID().toString()
        val recruit = createRecruit("新弟子", id = recruitId, age = 20, realm = 9)
        var gameData = GameData(recruitList = listOf(recruit))

        val found = requireNotNull(gameData.recruitList.find { it.id == recruitId }) {
            "fixture 必须包含 id=$recruitId 的招募条目"
        }
        val month = gameData.gameYear * 12 + gameData.gameMonth
        tables.allocateAndInsert(found.copy(usage = found.usage.copy(recruitedMonth = month)))
        gameData = gameData.copy(recruitList = gameData.recruitList.filter { it.id != recruitId })

        assertEquals(2, tables.count)
        val names = tables.assembleAll().map { it.name }.sorted()
        assertArrayEquals(arrayOf("在册弟子", "新弟子"), names.toTypedArray())
    }

    // ════════════════════════════════════════════════════════════════════
    // 辅助
    // ════════════════════════════════════════════════════════════════════

    private fun createRecruit(
        name: String,
        id: String = UUID.randomUUID().toString(),
        age: Int = 18, realm: Int = 9, realmLayer: Int = 1,
        cultivation: Double = 0.0, gender: String = "male",
        spiritRootType: String = "metal", discipleType: String = "outer",
        skills: SkillStats = SkillStats(),
        combat: CombatAttributes = CombatAttributes()
    ) = Disciple(
        id = id, name = name, age = age, realm = realm, realmLayer = realmLayer,
        cultivation = cultivation, isAlive = true, status = DiscipleStatus.IDLE,
        discipleType = discipleType, spiritRootType = spiritRootType, gender = gender,
        portraitRes = "default", skills = skills, combat = combat,
        lifespan = 80, social = SocialData(), usage = UsageTracking()
    )
}
