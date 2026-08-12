package com.xianxia.sect.core.state

import com.xianxia.sect.core.model.Disciple
import com.xianxia.sect.core.model.SkillStats
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * 旧档资质自愈守卫测试（2026-08-12 悟性重设计新增资质属性）。
 *
 * 覆盖 [DiscipleTables.healDefaultAptitudes]：
 * - 各灵根阶梯区间（与生成站点一致：1根[80,100] 2根[60,80] 3根[40,60] 4根[20,40] 5根[1,20]）
 * - 资质 != 默认哨兵 50 不重算（已生成弟子幂等跳过）
 * - 资质 == 50 确定性重算（同一 id 两次结果稳定）
 * - 命中哨兵 50 时强制 +1 收敛（幂等稳定，二次调用无修改）
 * - 空串灵根兜底按 5 根
 * - 返回补算数量（0 = 无修改）
 * - 自愈后 assemble 读到新值（列 → 对象链路贯通）
 */
@RunWith(RobolectricTestRunner::class)
class DiscipleTablesSelfHealTest {

    @get:Rule val writeGuardRule = WriteGuardRule()

    private fun disciple(
        id: Int = 1,
        spiritRootType: String = "metal"
    ): Disciple = Disciple(
        id = id.toString(),
        name = "弟子$id",
        spiritRootType = spiritRootType,
        skills = SkillStats(aptitude = DiscipleTables.DEFAULT_APTITUDE)
    )

    private fun rootCountByType(rootType: String): Int = rootType.split(",").size

    @Test
    fun `single root heals aptitude into 80 to 100`() {
        repeat(30) { i ->
            val tables = DiscipleTables()
            tables.insert(disciple(id = 100 + i, spiritRootType = "metal"))
            tables.healDefaultAptitudes()
            val value = tables.aptitudes[100 + i]
            assertTrue("单灵根资质 $value 应在 [80,100]", value in 80..100)
        }
    }

    @Test
    fun `two roots heals aptitude into 60 to 80`() {
        repeat(30) { i ->
            val tables = DiscipleTables()
            tables.insert(disciple(id = 200 + i, spiritRootType = "metal,fire"))
            tables.healDefaultAptitudes()
            val value = tables.aptitudes[200 + i]
            assertTrue("双灵根资质 $value 应在 [60,80]", value in 60..80)
        }
    }

    @Test
    fun `three roots heals aptitude into 40 to 60`() {
        repeat(30) { i ->
            val tables = DiscipleTables()
            tables.insert(disciple(id = 300 + i, spiritRootType = "metal,fire,wood"))
            tables.healDefaultAptitudes()
            val value = tables.aptitudes[300 + i]
            assertTrue("三灵根资质 $value 应在 [40,60]", value in 40..60)
        }
    }

    @Test
    fun `four roots heals aptitude into 20 to 40`() {
        repeat(30) { i ->
            val tables = DiscipleTables()
            tables.insert(disciple(id = 400 + i, spiritRootType = "metal,fire,wood,water"))
            tables.healDefaultAptitudes()
            val value = tables.aptitudes[400 + i]
            assertTrue("四灵根资质 $value 应在 [20,40]", value in 20..40)
        }
    }

    @Test
    fun `five roots heals aptitude into 1 to 20`() {
        repeat(30) { i ->
            val tables = DiscipleTables()
            tables.insert(disciple(id = 500 + i, spiritRootType = "metal,fire,wood,water,earth"))
            tables.healDefaultAptitudes()
            val value = tables.aptitudes[500 + i]
            assertTrue("五灵根资质 $value 应在 [1,20]", value in 1..20)
        }
    }

    @Test
    fun `heal is deterministic and idempotent for same id`() {
        val tables1 = DiscipleTables()
        tables1.insert(disciple(id = 7, spiritRootType = "metal,fire"))
        val firstHeal = tables1.healDefaultAptitudes()
        val firstValue = tables1.aptitudes[7]

        // 新实例（模拟下次读档）：同一 id 重算结果必须相同（确定性）
        val tables2 = DiscipleTables()
        tables2.insert(disciple(id = 7, spiritRootType = "metal,fire"))
        val secondHeal = tables2.healDefaultAptitudes()
        val secondValue = tables2.aptitudes[7]

        assertEquals("同一 id 两次自愈结果应一致", firstValue, secondValue)
        // 首轮补算 1 名；二轮（fresh 实例同样为哨兵 50）也补算 1 名——幂等指结果稳定
        assertEquals(1, firstHeal)
        assertEquals(1, secondHeal)
        // 已自愈后再次调用不修改
        assertEquals("已自愈后再次调用应无修改", 0, tables2.healDefaultAptitudes())
        assertEquals("再次调用不得改动值", secondValue, tables2.aptitudes[7])
    }

    @Test
    fun `heal converges sentinel 50 to 51 and stays stable`() {
        // 3 灵根 id=21：新阶梯 [40,60] 内散列恰为 50（哨兵）→ 自愈必须强制 +1 收敛为 51，
        // 否则下次读档"资质==50 ⇔ 未生成"判定再次触发，每次读档重复重算
        val tables = DiscipleTables()
        tables.insert(disciple(id = 21, spiritRootType = "metal,fire,wood"))
        val healed = tables.healDefaultAptitudes()
        assertEquals("3 根命中哨兵 50 应强制收敛为 51", 51, tables.aptitudes[21])
        assertEquals(1, healed)
        // 收敛后再次调用：不再触发（幂等稳定）
        assertEquals(0, tables.healDefaultAptitudes())
        assertEquals(51, tables.aptitudes[21])
    }

    @Test
    fun `aptitude not equal 50 is skipped`() {
        val tables = DiscipleTables()
        tables.insert(disciple(id = 1, spiritRootType = "metal"))
        // 已生成资质（≠50 哨兵）：不得重算
        tables.aptitudes[1] = 123
        assertEquals("资质已生成不应重算", 0, tables.healDefaultAptitudes())
        assertEquals(123, tables.aptitudes[1])
    }

    @Test
    fun `heal writes value readable by assemble`() {
        val tables = DiscipleTables()
        tables.insert(disciple(id = 1, spiritRootType = "metal"))
        tables.healDefaultAptitudes()

        val assembled = tables.assemble(1)
        assertEquals("assemble 应读到自愈后的资质", tables.aptitudes[1], assembled.skills.aptitude)
    }

    @Test
    fun `heal returns false when no disciple has sentinel aptitude`() {
        val tables = DiscipleTables()
        // 全新生成弟子（资质已生成，非哨兵）：自愈应无操作
        tables.insert(Disciple(
            id = "1",
            name = "新弟子",
            skills = SkillStats(aptitude = 90)
        ))
        assertEquals(0, tables.healDefaultAptitudes())
    }

    @Test
    fun `heal skips empty tables`() {
        val tables = DiscipleTables()
        assertEquals("空表自愈无操作", 0, tables.healDefaultAptitudes())
    }

    @Test
    fun `allocateAndInsert heals sentinel aptitude on recruit`() {
        // 旧档 recruitList 弟子（资质=50 哨兵）招募入宗：allocateAndInsert 即时按阶梯补算，
        // 不等下次读档自愈（消除"招募后资质跳变"窗口）
        val tables = DiscipleTables()
        val id = tables.allocateAndInsert(Disciple(
            id = "999",
            name = "招募弟子",
            spiritRootType = "metal,fire",
            skills = SkillStats(aptitude = DiscipleTables.DEFAULT_APTITUDE)
        ))
        val healed = tables.aptitudes[id.toInt()]
        assertTrue("入宗补算资质 $healed 应在 [60,80]", healed in 60..80)
        assertTrue("入宗补算不得命中哨兵 50", healed != DiscipleTables.DEFAULT_APTITUDE)
    }

    @Test
    fun `allocateAndInsert keeps generated aptitude untouched`() {
        // 新档弟子（生成已避开哨兵）：入宗不得重算
        val tables = DiscipleTables()
        val id = tables.allocateAndInsert(Disciple(
            id = "998",
            name = "新弟子",
            spiritRootType = "metal,fire",
            skills = SkillStats(aptitude = 120)
        ))
        assertEquals(120, tables.aptitudes[id.toInt()])
    }

    @Test
    fun `empty spirit root string heals as five roots`() {
        // 空串灵根（异常数据）兜底按 5 根区间 [1,20]，不得误判 1 根
        val tables = DiscipleTables()
        tables.insert(disciple(id = 42, spiritRootType = ""))
        tables.healDefaultAptitudes()
        val value = tables.aptitudes[42]
        assertTrue("空灵根资质 $value 应在 [1,20]", value in 1..20)
    }

    @Test
    fun `healed aptitude ladder matches root count boundaries`() {
        // 抽样验证阶梯下界严格性：1根≥80、2根≥60、3根≥40、4根≥20、5根≥1
        val cases = listOf(
            "metal" to 80,
            "metal,fire" to 60,
            "metal,fire,wood" to 40,
            "metal,fire,wood,water" to 20,
            "metal,fire,wood,water,earth" to 1
        )
        for ((rootType, min) in cases) {
            repeat(50) { i ->
                val tables = DiscipleTables()
                val id = 600 + i
                tables.insert(disciple(id = id, spiritRootType = rootType))
                tables.healDefaultAptitudes()
                val value = tables.aptitudes[id]
                assertTrue(
                    "根数=${rootCountByType(rootType)} 资质 $value 应 ≥ $min",
                    value >= min
                )
            }
        }
    }
}
