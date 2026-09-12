package com.xianxia.sect.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

/**
 * 招募列表净化核心测试 — 覆盖 [RecruitIntegrity] 的校验/去重/跨表残留判定。
 */
class RecruitIntegrityTest {

    // ==================== isValidRecruit ====================

    @Test
    fun `isValidRecruit - 正常弟子 true`() {
        assertTrue(RecruitIntegrity.isValidRecruit(createRecruit(name = "张三", age = 20)))
    }

    @Test
    fun `isValidRecruit - 空名 false`() {
        assertFalse(RecruitIntegrity.isValidRecruit(createRecruit(name = "")))
    }

    @Test
    fun `isValidRecruit - 空白名 false`() {
        assertFalse(RecruitIntegrity.isValidRecruit(createRecruit(name = "  ")))
        assertFalse(RecruitIntegrity.isValidRecruit(createRecruit(name = "　")))
    }

    @Test
    fun `isValidRecruit - 新生儿年龄1 true`() {
        assertTrue(RecruitIntegrity.isValidRecruit(createRecruit(age = 1)))
    }

    @Test
    fun `isValidRecruit - 年龄10000 true`() {
        assertTrue(RecruitIntegrity.isValidRecruit(createRecruit(age = 10000)))
    }

    @Test
    fun `isValidRecruit - 年龄10001 false`() {
        assertFalse(RecruitIntegrity.isValidRecruit(createRecruit(age = 10001)))
    }

    @Test
    fun `isValidRecruit - 年龄0 false`() {
        assertFalse(RecruitIntegrity.isValidRecruit(createRecruit(age = 0)))
    }

    @Test
    fun `isValidRecruit - 境界0仙人 true`() {
        assertTrue(RecruitIntegrity.isValidRecruit(createRecruit(realm = 0)))
    }

    @Test
    fun `isValidRecruit - 境界负1 false`() {
        assertFalse(RecruitIntegrity.isValidRecruit(createRecruit(realm = -1)))
    }

    @Test
    fun `isValidRecruit - 空灵根 false`() {
        assertFalse(RecruitIntegrity.isValidRecruit(createRecruit(spiritRoot = "")))
    }

    @Test
    fun `isValidRecruit - 灵根含空段 false`() {
        assertFalse(RecruitIntegrity.isValidRecruit(createRecruit(spiritRoot = "metal,,")))
    }

    // ==================== sanitizeRecruitList ====================

    @Test
    fun `sanitizeRecruitList - 损坏条目 移除并返回明细`() {
        val bad = createRecruit(name = "", age = 0)
        val good = createRecruit(name = "张三")

        val report = RecruitIntegrity.sanitizeRecruitList(listOf(bad, good), emptyList())

        assertEquals(1, report.removedCount)
        assertEquals(listOf(good), report.cleaned)
        assertTrue(report.details.isNotEmpty())
    }

    @Test
    fun `sanitizeRecruitList - 损坏同id在前 保留后续正常条目`() {
        val id = "same-id"
        val bad = createRecruit(id = id, name = "", age = 0)
        val good = createRecruit(id = id, name = "张三", age = 20)

        val report = RecruitIntegrity.sanitizeRecruitList(listOf(bad, good), emptyList())

        assertEquals(1, report.removedCount)
        assertEquals(listOf(good), report.cleaned)
    }

    @Test
    fun `sanitizeRecruitList - 同id重复 保留首个`() {
        val first = createRecruit(id = "dup-id", name = "张三")
        val second = createRecruit(id = "dup-id", name = "李四")

        val report = RecruitIntegrity.sanitizeRecruitList(listOf(first, second), emptyList())

        assertEquals(1, report.removedCount)
        assertEquals(listOf(first), report.cleaned)
    }

    @Test
    fun `sanitizeRecruitList - 同内容不同id 保留首个`() {
        val first = createRecruit(name = "张三", age = 20)
        val twin = createRecruit(name = "张三", age = 20)

        val report = RecruitIntegrity.sanitizeRecruitList(listOf(first, twin), emptyList())

        assertEquals(1, report.removedCount)
        assertEquals(listOf(first), report.cleaned)
    }

    @Test
    fun `sanitizeRecruitList - 同名不同内容 两条均保留`() {
        val a = createRecruit(name = "张三", age = 20, spiritRoot = "金")
        val b = createRecruit(name = "张三", age = 30, spiritRoot = "火")

        val report = RecruitIntegrity.sanitizeRecruitList(listOf(a, b), emptyList())

        assertEquals(0, report.removedCount)
        assertEquals(2, report.cleaned.size)
    }

    @Test
    fun `sanitizeRecruitList - 内容已入宗门 移除残留`() {
        val recruit = createRecruit(name = "张三", age = 20, portrait = "p1")
        val inSect = createRecruit(name = "张三", age = 20, portrait = "p1", id = "999")

        val report = RecruitIntegrity.sanitizeRecruitList(listOf(recruit), listOf(inSect))

        assertEquals(1, report.removedCount)
        assertTrue(report.cleaned.isEmpty())
    }

    @Test
    fun `sanitizeRecruitList - 同名俘虏与宗门弟子 不误删`() {
        // 宗门已有同名"王五"但灵根/肖像不同，recruitList 的"王五"是另一人
        val recruit = createRecruit(name = "王五", age = 25, spiritRoot = "金")
        val inSect = createRecruit(name = "王五", age = 25, spiritRoot = "火")

        val report = RecruitIntegrity.sanitizeRecruitList(listOf(recruit), listOf(inSect))

        assertEquals(0, report.removedCount)
        assertEquals(1, report.cleaned.size)
    }

    @Test
    fun `sanitizeRecruitList - 38岁炼虚 保留`() {
        val lianxu = createRecruit(name = "天才", age = 38, realm = 4)

        val report = RecruitIntegrity.sanitizeRecruitList(listOf(lianxu), emptyList())

        assertEquals(0, report.removedCount)
        assertEquals(1, report.cleaned.size)
    }

    @Test
    fun `sanitizeRecruitList - 空列表 零移除`() {
        val report = RecruitIntegrity.sanitizeRecruitList(emptyList(), emptyList())

        assertEquals(0, report.removedCount)
        assertTrue(report.cleaned.isEmpty())
    }

    @Test
    fun `sanitizeRecruitList - 已入宗门残留但年龄差超容差 保留`() {
        // 残留候选 20 岁、宗门弟子 30 岁（跨表比对年龄差 10 年，非同人）
        val recruit = createRecruit(name = "张三", age = 20)
        val inSect = createRecruit(name = "张三", age = 30)

        val report = RecruitIntegrity.sanitizeRecruitList(listOf(recruit), listOf(inSect))

        assertEquals(0, report.removedCount)
        assertEquals(1, report.cleaned.size)
    }

    @Test
    fun `sanitizeRecruitList - 宗门侧已死亡且幽灵超过容差 仍移除`() {
        // 死者年龄冻结在 30 岁，幽灵条目继续老化到 33 岁（差 3 年）——
        // 死者侧非对称容差：幽灵年龄必然 ≥ 死者冻结年龄，应移除
        val ghost = createRecruit(name = "张三", age = 33)
        val deadInSect = createRecruit(name = "张三", age = 30).copy(isAlive = false)

        val report = RecruitIntegrity.sanitizeRecruitList(listOf(ghost), listOf(deadInSect))

        assertEquals(1, report.removedCount)
        assertTrue(report.cleaned.isEmpty())
    }

    @Test
    fun `sanitizeRecruitList - 死亡弟子不误删合法新条目`() {
        // 死者 30 岁冻结，合法同名新候选 20 岁——年龄小于死者冻结年龄，保留
        val recruit = createRecruit(name = "张三", age = 20)
        val deadInSect = createRecruit(name = "张三", age = 30).copy(isAlive = false)

        val report = RecruitIntegrity.sanitizeRecruitList(listOf(recruit), listOf(deadInSect))

        assertEquals(0, report.removedCount)
        assertEquals(1, report.cleaned.size)
    }

    @Test
    fun `sanitizeRecruitList - 列表侧无体质词条 宗门侧有 仍匹配残留`() {
        // 模拟真实序列化不对称：recruitList 条目经 DiscipleSerializer
        // 后 physiqueIds/affixIds 恒空，宗门侧有真实值——签名不含这两字段
        val recruit = createRecruit(name = "张三", age = 20, portrait = "")
        val inSect = createRecruit(name = "张三", age = 20, portrait = "male_disciple_5")
            .copy(physiqueIds = listOf("p1"), affixIds = listOf("a1"))

        val report = RecruitIntegrity.sanitizeRecruitList(listOf(recruit), listOf(inSect))

        assertEquals(1, report.removedCount)
        assertTrue(report.cleaned.isEmpty())
    }

    @Test
    fun `sanitizeRecruitList - 列表内年龄差1的双胞胎 去重`() {
        // 内容去重要求全字段相等（含 age），年龄差 1 的双胞胎由同人签名兜底
        val first = createRecruit(name = "张三", age = 20)
        val twin = createRecruit(name = "张三", age = 21)

        val report = RecruitIntegrity.sanitizeRecruitList(listOf(first, twin), emptyList())

        assertEquals(1, report.removedCount)
        assertEquals(listOf(first), report.cleaned)
    }

    // ==================== isSamePerson ====================

    @Test
    fun `isSamePerson - 同人 true`() {
        val a = createRecruit(name = "张三", age = 20, portrait = "p1")
        val b = createRecruit(name = "张三", age = 21, portrait = "p1")

        assertTrue(RecruitIntegrity.isSamePerson(a, b))
    }

    @Test
    fun `isSamePerson - 年龄差2年 仍判同人（容差边界）`() {
        val a = createRecruit(name = "张三", age = 20)
        val b = createRecruit(name = "张三", age = 22)

        assertTrue(RecruitIntegrity.isSamePerson(a, b))
    }

    @Test
    fun `isSamePerson - 年龄差3年 false`() {
        val a = createRecruit(name = "张三", age = 20)
        val b = createRecruit(name = "张三", age = 23)

        assertFalse(RecruitIntegrity.isSamePerson(a, b))
    }

    @Test
    fun `isSamePerson - 天赋顺序不同 仍判同人`() {
        val a = createRecruit(name = "张三").copy(talentIds = listOf("t1", "t2"))
        val b = createRecruit(name = "张三").copy(talentIds = listOf("t2", "t1"))

        assertTrue(RecruitIntegrity.isSamePerson(a, b))
    }

    // ==================== dedupeRecruits ====================

    @Test
    fun `dedupeRecruits - 同id不同内容 保留首个`() {
        val first = createRecruit(id = "dup", name = "张三")
        val second = createRecruit(id = "dup", name = "李四")

        val result = RecruitIntegrity.dedupeRecruits(listOf(first, second))

        assertEquals(listOf(first), result)
    }

    @Test
    fun `dedupeRecruits - 仅slotId不同 判为重复`() {
        val first = createRecruit(name = "张三")
        val second = createRecruit(name = "张三").copy(slotId = 7)

        val result = RecruitIntegrity.dedupeRecruits(listOf(first, second))

        assertEquals(listOf(first), result)
    }

    @Test
    fun `dedupeRecruits - 年龄差1的双胞胎 保留首个`() {
        val first = createRecruit(name = "张三", age = 20)
        val twin = createRecruit(name = "张三", age = 21)

        val result = RecruitIntegrity.dedupeRecruits(listOf(first, twin))

        assertEquals(listOf(first), result)
    }

    @Test
    fun `dedupeRecruits - 同名不同灵根 均保留`() {
        val a = createRecruit(name = "张三", spiritRoot = "金")
        val b = createRecruit(name = "张三", spiritRoot = "火")

        val result = RecruitIntegrity.dedupeRecruits(listOf(a, b))

        assertEquals(listOf(a, b), result)
    }

    // ==================== 辅助 ====================

    private fun createRecruit(
        name: String = "弟子",
        age: Int = 20,
        realm: Int = 9,
        id: String = UUID.randomUUID().toString(),
        spiritRoot: String = "金",
        portrait: String = "male_disciple_1"
    ): Disciple = Disciple(
        id = id,
        name = name,
        age = age,
        realm = realm,
        spiritRootType = spiritRoot,
        portraitRes = portrait
    )
}
