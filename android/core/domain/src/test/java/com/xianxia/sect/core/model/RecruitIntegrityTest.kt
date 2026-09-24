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
        assertTrue(RecruitIntegrity.isValidRecruit(createRecruit(name = "张三")))
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
        val bad = createRecruit(name = "")
        val good = createRecruit(name = "张三")

        val report = RecruitIntegrity.sanitizeRecruitList(listOf(bad, good), emptyList())

        assertEquals(1, report.removedCount)
        assertEquals(listOf(good), report.cleaned)
        assertTrue(report.details.isNotEmpty())
    }

    @Test
    fun `sanitizeRecruitList - 损坏同id在前 保留后续正常条目`() {
        val id = "same-id"
        val bad = createRecruit(id = id, name = "")
        val good = createRecruit(id = id, name = "张三")

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
        val first = createRecruit(name = "张三")
        val twin = createRecruit(name = "张三")

        val report = RecruitIntegrity.sanitizeRecruitList(listOf(first, twin), emptyList())

        assertEquals(1, report.removedCount)
        assertEquals(listOf(first), report.cleaned)
    }

    @Test
    fun `sanitizeRecruitList - 同名不同内容 两条均保留`() {
        val a = createRecruit(name = "张三", spiritRoot = "金")
        val b = createRecruit(name = "张三", spiritRoot = "火")

        val report = RecruitIntegrity.sanitizeRecruitList(listOf(a, b), emptyList())

        assertEquals(0, report.removedCount)
        assertEquals(2, report.cleaned.size)
    }

    @Test
    fun `sanitizeRecruitList - 内容已入宗门 移除残留`() {
        val recruit = createRecruit(name = "张三", portrait = "p1")
        val inSect = createRecruit(name = "张三", portrait = "p1", id = "999")

        val report = RecruitIntegrity.sanitizeRecruitList(listOf(recruit), listOf(inSect))

        assertEquals(1, report.removedCount)
        assertTrue(report.cleaned.isEmpty())
    }

    @Test
    fun `sanitizeRecruitList - 同名俘虏与宗门弟子 不误删`() {
        // 宗门已有同名"王五"但灵根不同，recruitList 的"王五"是另一人
        val recruit = createRecruit(name = "王五", spiritRoot = "金")
        val inSect = createRecruit(name = "王五", spiritRoot = "火")

        val report = RecruitIntegrity.sanitizeRecruitList(listOf(recruit), listOf(inSect))

        assertEquals(0, report.removedCount)
        assertEquals(1, report.cleaned.size)
    }

    @Test
    fun `sanitizeRecruitList - 炼虚高境界 保留`() {
        val lianxu = createRecruit(name = "天才", realm = 4)

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
    fun `sanitizeRecruitList - 已入宗门同签名 移除残留`() {
        // 同人签名 = 纯签名相等（无年龄参与）：签名一致即判定已入宗门
        val recruit = createRecruit(name = "张三")
        val inSect = createRecruit(name = "张三", id = "999")

        val report = RecruitIntegrity.sanitizeRecruitList(listOf(recruit), listOf(inSect))

        assertEquals(1, report.removedCount)
        assertTrue(report.cleaned.isEmpty())
    }

    @Test
    fun `sanitizeRecruitList - 宗门侧已死亡 同签名仍移除`() {
        // 残留判定不要求存活：已死亡宗门侧同签名条目同样命中
        val ghost = createRecruit(name = "张三")
        val deadInSect = createRecruit(name = "张三", id = "999").copy(isAlive = false)

        val report = RecruitIntegrity.sanitizeRecruitList(listOf(ghost), listOf(deadInSect))

        assertEquals(1, report.removedCount)
        assertTrue(report.cleaned.isEmpty())
    }

    @Test
    fun `sanitizeRecruitList - 不同签名新条目不因死亡弟子误删`() {
        // 签名字段（灵根）不同 → 非同人 → 合法新候选保留
        val recruit = createRecruit(name = "张三", spiritRoot = "火")
        val deadInSect = createRecruit(name = "张三", spiritRoot = "金").copy(isAlive = false)

        val report = RecruitIntegrity.sanitizeRecruitList(listOf(recruit), listOf(deadInSect))

        assertEquals(0, report.removedCount)
        assertEquals(1, report.cleaned.size)
    }

    @Test
    fun `sanitizeRecruitList - 列表侧无体质词条 宗门侧有 仍匹配残留`() {
        // 模拟真实序列化不对称：recruitList 条目经 DiscipleSerializer
        // 后 physiqueIds/affixIds 恒空，宗门侧有真实值——签名不含这两字段
        val recruit = createRecruit(name = "张三", portrait = "")
        val inSect = createRecruit(name = "张三", portrait = "male_disciple_5")
            .copy(physiqueIds = listOf("p1"), affixIds = listOf("a1"))

        val report = RecruitIntegrity.sanitizeRecruitList(listOf(recruit), listOf(inSect))

        assertEquals(1, report.removedCount)
        assertTrue(report.cleaned.isEmpty())
    }

    @Test
    fun `sanitizeRecruitList - 同签名仅非签名字段分化的克隆 去重`() {
        // 内容去重要求全字段相等；仅非签名字段（修为）分化的克隆由同人签名兜底
        val first = createRecruit(name = "张三")
        val twin = first.copy(cultivation = 999.0)

        val report = RecruitIntegrity.sanitizeRecruitList(listOf(first, twin), emptyList())

        assertEquals(1, report.removedCount)
        assertEquals(listOf(first), report.cleaned)
    }

    // ==================== isSamePerson ====================

    @Test
    fun `isSamePerson - 签名相等即同人（肖像分化不敏感）`() {
        val a = createRecruit(name = "张三", portrait = "p1")
        val b = createRecruit(name = "张三", portrait = "p2")

        assertTrue(RecruitIntegrity.isSamePerson(a, b))
    }

    @Test
    fun `isSamePerson - 灵根不同 false`() {
        val a = createRecruit(name = "张三", spiritRoot = "金")
        val b = createRecruit(name = "张三", spiritRoot = "火")

        assertFalse(RecruitIntegrity.isSamePerson(a, b))
    }

    @Test
    fun `isSamePerson - 性别不同 false`() {
        val a = createRecruit(name = "张三")
        val b = createRecruit(name = "张三").copy(gender = "female")

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
    fun `dedupeRecruits - 同签名非签名字段分化克隆 保留首个`() {
        val first = createRecruit(name = "张三")
        val twin = first.copy(cultivation = 999.0)

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
        realm: Int = 9,
        id: String = UUID.randomUUID().toString(),
        spiritRoot: String = "金",
        portrait: String = "male_disciple_1"
    ): Disciple = Disciple(
        id = id,
        name = name,
        realm = realm,
        spiritRootType = spiritRoot,
        portraitRes = portrait
    )
}
