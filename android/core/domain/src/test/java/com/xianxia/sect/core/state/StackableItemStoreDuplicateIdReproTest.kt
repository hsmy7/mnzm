package com.xianxia.sect.core.state

import com.xianxia.sect.core.model.Pill
import com.xianxia.sect.core.model.PillCategory
import com.xianxia.sect.core.model.PillGrade
import com.xianxia.sect.core.util.AppError
import com.xianxia.sect.core.util.DomainResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 回归：StackableItemStore.add 分块创建新堆叠时不得复用同一 item.id，
 * 否则 EntityStore 索引 / DB 主键 REPLACE 去重会丢弃堆叠，仓库物品消失。
 */
class StackableItemStoreDuplicateIdReproTest {

    private val maxStack = 99

    private fun store(initial: List<Pill> = emptyList(), maxSlots: Int = 10): StackableItemStore<Pill> =
        StackableItemStore(
            initialItems = initial,
            stackKeyOf = StackKeys::pill,
            maxStack = maxStack,
            maxSlots = { maxSlots },
            notFound = { AppError.Domain.Inventory.NotFound(it) }
        )

    private fun pill(id: String, name: String, rarity: Int, qty: Int) = Pill(
        id = id, name = name, rarity = rarity,
        category = PillCategory.CULTIVATION, grade = PillGrade.LOW, quantity = qty
    )

    @Test
    fun `add with qty above maxStack - each new stack has unique id`() {
        // 8 个已有堆叠（其中 1 个"回气丹"满），2 个空槽
        val initial = buildList {
            add(pill("existing", "回气丹", 1, maxStack))
            repeat(7) { add(pill("fill$it", "填充$it", 1, 1)) }
        }
        val s = store(initial, maxSlots = 10)

        // 添加 200 个回气丹：最多建 2 个新堆叠（99+99），剩余 2 溢出
        val result = s.add(pill("newId", "回气丹", 1, 200))

        assertTrue("应 Partial 溢出 2", result is DomainResult.Partial)
        assertEquals(2, (result as DomainResult.Partial).overflow)

        val all = s.all()
        assertEquals("应有 10 个堆叠（8+2 新增）", 10, all.size)
        val newStacks = all.filter { it.id == "newId" }
        assertEquals("首块保留原 id，仅 1 个", 1, newStacks.size)
        // 第二块必须获得唯一新 id
        val uniqueIds = all.map { it.id }.distinct()
        assertEquals("所有堆叠 id 必须唯一", all.size, uniqueIds.size)
        // 总入仓 304 = existing(99) + fillers(7) + 新增(198)，另 2 个转邮件
        assertEquals(304, all.sumOf { it.quantity })
    }

    @Test
    fun `id-dedupe roundtrip no longer loses stacks`() {
        val initial = buildList {
            add(pill("existing", "回气丹", 1, maxStack))
            repeat(7) { add(pill("fill$it", "填充$it", 1, 1)) }
        }
        val s = store(initial, maxSlots = 10)
        s.add(pill("newId", "回气丹", 1, 200))
        val all = s.all()

        // 模拟 SQLite 主键 REPLACE：同 id 只保留最后一条（修复后无同 id，去重无损失）
        val deduped = all.associateBy { it.id }.values.toList()
        assertEquals("修复后 id 去重不丢堆叠", all.size, deduped.size)
        assertEquals(304, deduped.sumOf { it.quantity })
    }
}
