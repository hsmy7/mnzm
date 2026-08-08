package com.xianxia.sect.core.model

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * L1a 等价性安全网：`dedupeRecruits` 重构（O(R²)→O(N) 签名分组）前先写本测试。
 *
 * 参照实现内联旧 O(R²) 三级去重算法（id → 内容 → 同人签名），与生产实现逐位对比。
 * 重构后本测试仍绿 = 等价性成立。
 *
 * 等价性依据：`isSamePerson` 先比签名（签名不同必 false），因此旧算法中
 * "kept 列表 none 判定"只可能命中同签名者 ⇒ 按签名分组、组内保序去重与旧算法逐位一致。
 */
class RecruitDedupeEquivalenceTest {

    // ── 参照实现：旧 O(R²) 算法（与重构前 RecruitIntegrity.dedupeRecruits 逐行一致）──

    private fun referenceDedupe(recruits: List<Disciple>): List<Disciple> {
        // 按 id 去重（保留首个）
        val idSeen = mutableSetOf<String>()
        val idDeduped = mutableListOf<Disciple>()
        for (d in recruits) {
            if (idSeen.add(d.id)) idDeduped.add(d)
        }
        // 按内容去重（丢弃 id/slotId 后全字段相等判定）
        val contentSeen = mutableSetOf<Disciple>()
        val contentDeduped = mutableListOf<Disciple>()
        for (d in idDeduped) {
            val normalized = d.copy(id = "same", slotId = 0)
            if (contentSeen.add(normalized)) contentDeduped.add(d)
        }
        // 按"同人"签名去重（年龄容差内的克隆）
        val personDeduped = mutableListOf<Disciple>()
        for (d in contentDeduped) {
            if (personDeduped.none { RecruitIntegrity.isSamePerson(it, d) }) personDeduped.add(d)
        }
        return personDeduped
    }

    private fun assertEquivalent(input: List<Disciple>, desc: String) {
        val reference = referenceDedupe(input)
        val actual = RecruitIntegrity.dedupeRecruits(input)
        assertEquals(
            "$desc：参照与生产实现应逐位等价（ref=${reference.size}, act=${actual.size}）",
            reference, actual
        )
    }

    // ── 构造辅助 ──

    private fun disciple(
        id: Int,
        name: String,
        age: Int,
        surname: String = "轩辕",
        gender: String = "male",
        spiritRootType: String = "metal",
        talentIds: List<String> = listOf("t1"),
        slotId: Int = 0
    ) = Disciple(
        id = id.toString(),
        name = name,
        age = age,
        surname = surname,
        gender = gender,
        spiritRootType = spiritRootType,
        talentIds = talentIds,
        slotId = slotId,
        isAlive = true
    )

    // ── 用例 ──

    @Test
    fun `empty list returns empty`() {
        assertEquivalent(emptyList(), "空列表")
    }

    @Test
    fun `single element unchanged`() {
        assertEquivalent(listOf(disciple(1, "张三", 20)), "单元素")
    }

    @Test
    fun `duplicate ids keep first regardless of content`() {
        val a = disciple(1, "张三", 20)
        val b = disciple(1, "李四", 25) // 同 id 不同内容
        assertEquivalent(listOf(a, b), "同 id 复制（正序）")
        assertEquivalent(listOf(b, a), "同 id 复制（逆序，保留首个）")
    }

    @Test
    fun `identical content twins dedup by content`() {
        val a = disciple(1, "张三", 20)
        val b = a.copy(id = "2") // 全字段相同仅 id 不同
        assertEquivalent(listOf(a, b), "同内容双胞胎")
        // slotId 不同也视为同内容（内容去重归一化 slotId）
        val c = a.copy(id = "3", slotId = 5)
        assertEquivalent(listOf(a, b, c), "同内容（含 slotId 不同）")
    }

    @Test
    fun `same signature within age tolerance dedups as same person`() {
        val a = disciple(1, "张三", 20)
        val b = disciple(2, "张三", 21) // 年龄差 1
        val c = disciple(3, "张三", 22) // 年龄差 2（容差内）
        assertEquivalent(listOf(a, b, c), "同签名 ±2 岁克隆")
    }

    @Test
    fun `chained tolerance - beyond range kept - order preserved`() {
        // 链式容差：B 与 A 差 2 被剪；C 与 A 差 4 保留（B 已不在 kept，不参与 C 的比较）
        val a = disciple(1, "张三", 20)
        val b = disciple(2, "张三", 22)
        val c = disciple(3, "张三", 24)
        assertEquivalent(listOf(a, b, c), "链式容差 A20/B22/C24 正序")
        assertEquivalent(listOf(c, b, a), "链式容差 逆序")
        val d = disciple(4, "张三", 18)
        assertEquivalent(listOf(a, b, c, d), "链式容差 4 条（16..24）")
    }

    @Test
    fun `talentIds order does not affect signature`() {
        val a = disciple(1, "张三", 20, talentIds = listOf("火灵", "水灵"))
        val b = disciple(2, "张三", 21, talentIds = listOf("水灵", "火灵")) // 签名内部排序后相同
        assertEquivalent(listOf(a, b), "talentIds 乱序同签名")
    }

    @Test
    fun `same name different signature all kept`() {
        val a = disciple(1, "张三", 20, surname = "张", gender = "male", spiritRootType = "metal")
        val b = disciple(2, "张三", 20, surname = "张", gender = "female", spiritRootType = "metal")
        val c = disciple(3, "张三", 20, surname = "李", gender = "male", spiritRootType = "metal")
        val d = disciple(4, "张三", 20, surname = "张", gender = "male", spiritRootType = "water")
        val e = disciple(
            5, "张三", 20, surname = "张", gender = "male", spiritRootType = "metal",
            talentIds = listOf("t9")
        )
        assertEquivalent(listOf(a, b, c, d, e), "不同签名同名全保留")
    }

    @Test
    fun `mixed duplicates across all three levels`() {
        val a = disciple(1, "张三", 20)
        val sameId = disciple(1, "李四", 50) // 1 级：同 id
        val contentTwin = a.copy(id = "9") // 2 级：同内容
        val clone = disciple(7, "张三", 19) // 3 级：同签名容差内
        val other = disciple(8, "王五", 30) // 独立
        assertEquivalent(listOf(a, sameId, contentTwin, clone, other), "三级混合")
    }

    @Test
    fun `fuzz 1000 recruits with 30 percent duplicates`() {
        // 固定种子确定性 fuzz：700 基底 + 100 同 id + 100 同内容 + 100 同签名克隆
        val rng = kotlin.random.Random(42)
        val names = listOf("张三", "李四", "王五", "赵六", "孙七", "周八", "吴九", "郑十")
        val surnames = listOf("张", "李", "王", "赵", "孙")
        val genders = listOf("male", "female")
        val roots = listOf("metal", "wood", "water", "fire", "earth")
        val talentPool = listOf("t1", "t2", "t3", "t4", "t5")

        fun randomBase(id: Int): Disciple {
            val talentCount = rng.nextInt(0, 3)
            val talents = talentPool.shuffled(rng).take(talentCount)
            return Disciple(
                id = id.toString(),
                name = names[rng.nextInt(names.size)],
                surname = surnames[rng.nextInt(surnames.size)],
                gender = genders[rng.nextInt(genders.size)],
                spiritRootType = roots[rng.nextInt(roots.size)],
                talentIds = talents,
                age = 16 + rng.nextInt(84)
            )
        }

        val base = (1..700).map { randomBase(it) }
        val variants = mutableListOf<Disciple>()
        for (i in 0 until 100) { // 同 id（内容不同）
            val src = base[rng.nextInt(base.size)]
            variants += src.copy(age = src.age + 5)
        }
        for (i in 0 until 100) { // 同内容（仅 id 不同）
            val src = base[rng.nextInt(base.size)]
            variants += src.copy(id = "dup-content-$i")
        }
        for (i in 0 until 100) { // 同签名克隆（±1/±2 岁）
            val src = base[rng.nextInt(base.size)]
            val shift = if (rng.nextBoolean()) 1 else 2
            variants += src.copy(id = "clone-$i", age = (src.age + shift).coerceAtMost(99))
        }

        val input = (base + variants).shuffled(rng)
        assertEquivalent(input, "fuzz 1000 条 30% 重复")
    }
}
