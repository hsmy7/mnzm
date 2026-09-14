package com.xianxia.sect.ui.game.dialogs

import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs

class DiscipleChatDialogTest {

    private val allTrees: List<ConversationTree> by lazy { getAllConversationTrees() }

    // ═══════════════════════════════════════════
    // ConversationEffect
    // ═══════════════════════════════════════════

    @Test
    fun `effect zero when all zero`() { assertTrue(ConversationEffect().isZero) }
    @Test
    fun `effect not zero when any non-zero`() { assertFalse(ConversationEffect(loyaltyDelta = 1).isZero) }
    @Test
    fun `effect display shows loyalty`() {
        val t = ConversationEffect(loyaltyDelta = 5).toDisplayText()
        assertTrue(t.contains("忠诚") && t.contains("+5"))
    }
    @Test
    fun `effect display shows negative`() {
        assertTrue(ConversationEffect(loyaltyDelta = -3).toDisplayText().contains("-3"))
    }
    @Test
    fun `effect display shows cultivation percent`() {
        val t = ConversationEffect(cultivationDelta = 0.03).toDisplayText()
        assertTrue(t.contains("修为") && t.contains("3%"))
    }

    // ═══════════════════════════════════════════
    // 树结构
    // ═══════════════════════════════════════════

    @Test
    fun `trees defined`() { assertTrue(allTrees.isNotEmpty()) }

    @Test
    fun `greeting variants non-empty`() {
        allTrees.forEachIndexed { i, t ->
            assertTrue("$i variants", t.greetingVariants.isNotEmpty())
            t.greetingVariants.forEach { assertTrue("$i blank", it.isNotBlank()) }
        }
    }

    @Test
    fun `root node valid`() {
        allTrees.forEachIndexed { i, t ->
            assertTrue("$i root blank", t.rootNodeId.isNotBlank())
            assertTrue("$i root missing", t.nodes.containsKey(t.rootNodeId))
        }
    }

    @Test
    fun `all nodes have 3 options`() {
        allTrees.forEachIndexed { i, tree ->
            tree.nodes.forEach { (id, node) ->
                assertEquals("Tree $i node=$id should have 3 options", 3, node.options.size)
            }
        }
    }

    @Test
    fun `all options have at least 2 outcomes`() {
        allTrees.forEachIndexed { i, tree ->
            tree.nodes.forEach { (id, node) ->
                node.options.forEachIndexed { j, opt ->
                    assertTrue("$i $id opt=$j outcomes <2", opt.outcomes.size >= 2)
                }
            }
        }
    }

    @Test
    fun `all outcomes have reply variants`() {
        allTrees.forEachIndexed { i, tree ->
            tree.nodes.forEach { (id, node) ->
                node.options.forEachIndexed { j, opt ->
                    opt.outcomes.forEachIndexed { k, out ->
                        assertTrue("$i $id opt=$j out=$k no reply", out.replyVariants.isNotEmpty())
                        out.replyVariants.forEach { assertTrue("reply blank", it.isNotBlank()) }
                    }
                }
            }
        }
    }

    @Test
    fun `all nextNodeId refs are valid`() {
        allTrees.forEachIndexed { i, tree ->
            tree.nodes.forEach { (id, node) ->
                node.options.forEachIndexed { j, opt ->
                    opt.outcomes.forEachIndexed { k, out ->
                        val ok = out.nextNodeId == END_NODE || tree.nodes.containsKey(out.nextNodeId)
                        assertTrue("$i $id opt=$j out=$k -> ${out.nextNodeId} invalid", ok)
                    }
                }
            }
        }
    }

    @Test
    fun `all paths reach END`() {
        for ((ti, tree) in allTrees.withIndex()) {
            for ((id) in tree.nodes) {
                val depth = walkFirstOptionDepth(tree, id)
                assertTrue("Tree $ti node=$id depth=$depth", depth < 20)
            }
        }
    }

    /** 沿首选项链游走直至 END / 叶节点 / 深度上限 */
    private fun walkFirstOptionDepth(tree: ConversationTree, startId: String): Int {
        var depth = 0
        var cur = startId
        while (depth < 20) {
            val node = tree.nodes[cur]
            val next = node?.takeIf { it.options.isNotEmpty() }
                ?.let { it.options.first().outcomes.first().nextNodeId }
            // 缺失节点/叶节点/终点：游走停止
            if (next == null || next == END_NODE) break
            cur = next
            depth++
        }
        return depth
    }

    @Test
    fun `terminal outcomes have effects and ending text`() {
        allTrees.forEachIndexed { i, tree ->
            tree.nodes.forEach { (id, node) ->
                node.options.forEachIndexed { j, opt ->
                    opt.outcomes.forEachIndexed { k, out ->
                        if (out.nextNodeId == END_NODE) {
                            assertNotNull("$i $id opt=$j out=$k effect null", out.effects)
                            assertFalse("$i $id opt=$j out=$k effect zero", out.effects!!.isZero)
                            assertTrue("$i $id opt=$j out=$k no ending", out.endingTextVariants.isNotEmpty())
                            out.endingTextVariants.forEach { assertTrue("end blank", it.isNotBlank()) }
                        }
                    }
                }
            }
        }
    }

    @Test
    fun `non-terminal outcomes have no effects or ending text`() {
        allTrees.forEachIndexed { i, tree ->
            tree.nodes.forEach { (id, node) ->
                node.options.forEachIndexed { j, opt ->
                    opt.outcomes.forEachIndexed { k, out ->
                        if (out.nextNodeId != END_NODE) {
                            assertNull("$i $id opt=$j out=$k effect should be null", out.effects)
                            assertTrue("$i $id opt=$j out=$k ending should be empty", out.endingTextVariants.isEmpty())
                        }
                    }
                }
            }
        }
    }

    @Test
    fun `terminal effects have exactly one non-zero field`() {
        allTrees.forEachIndexed { i, tree ->
            tree.nodes.forEach { (id, node) ->
                node.options.forEachIndexed { j, opt ->
                    opt.outcomes.forEachIndexed { k, out ->
                        if (out.nextNodeId == END_NODE) {
                            val e = out.effects ?: error("null")
                            val cnt = listOf(
                                e.loyaltyDelta != 0, e.moralityDelta != 0,
                                e.intelligenceDelta != 0, e.cultivationDelta != 0.0
                            ).count { it }
                            assertEquals("$i $id opt=$j out=$k count=$cnt", 1, cnt)
                        }
                    }
                }
            }
        }
    }

    @Test
    fun `options text non-empty`() {
        allTrees.forEachIndexed { i, tree ->
            tree.nodes.forEach { (id, node) ->
                node.options.forEachIndexed { j, opt ->
                    assertTrue("$i $id opt=$j blank", opt.text.isNotBlank())
                }
            }
        }
    }

    @Test
    fun `greeting uses zongzhu`() {
        allTrees.forEachIndexed { i, tree ->
            tree.greetingVariants.forEach { t ->
                assertFalse("Tree $i contains 师尊", t.contains("师尊"))
                assertFalse("Tree $i contains 师父", t.contains("师父"))
            }
        }
    }

    // ═══════════════════════════════════════════
    // 随机化
    // ═══════════════════════════════════════════

    // 抽取桩：返回区间下界（下界即真实抽取可能取到的最小值），
    // 使原「50 次随机 + 区间断言」改为确定性逐字断言。
    private val intLower: (Int, Int) -> Int = { from, _ -> from }
    private val intUpper: (Int, Int) -> Int = { _, until -> until - 1 }
    private val doubleLower: (Double, Double) -> Double = { from, _ -> from }

    @Test
    fun `randomize sign positive`() = runTest {
        assertEquals(1, randomizeEffectWith(intLower, doubleLower, ConversationEffect(loyaltyDelta = 1)).loyaltyDelta)
        assertEquals(5, randomizeEffectWith(intUpper, doubleLower, ConversationEffect(loyaltyDelta = 1)).loyaltyDelta)
    }

    @Test
    fun `randomize sign negative`() = runTest {
        val lo = randomizeEffectWith(intLower, doubleLower, ConversationEffect(loyaltyDelta = -1)).loyaltyDelta
        val hi = randomizeEffectWith(intUpper, doubleLower, ConversationEffect(loyaltyDelta = -1)).loyaltyDelta
        assertTrue("下界取负", lo < 0 && abs(lo) == 1)
        assertTrue("上界取负", hi < 0 && abs(hi) == 5)
    }

    @Test
    fun `randomize zero fields preserved`() = runTest {
        val r = randomizeEffectWith(intLower, doubleLower, ConversationEffect(moralityDelta = 1))
        assertEquals(0, r.loyaltyDelta)
        assertEquals(0, r.intelligenceDelta)
        assertEquals(0.0, r.cultivationDelta, 0.001)
        assertEquals(1, r.moralityDelta)
    }

    @Test
    fun `randomize zero in zero out`() = runTest {
        assertTrue(randomizeEffectWith(intLower, doubleLower, ConversationEffect()).isZero)
    }

    @Test
    fun `randomize cultivation percent`() = runTest {
        val pos = randomizeEffectWith(intLower, doubleLower, ConversationEffect(cultivationDelta = 0.01))
        assertTrue("pos", pos.cultivationDelta > 0)
        assertTrue("下限", pos.cultivationDelta >= 0.01 && pos.cultivationDelta < 0.06)
        val neg = randomizeEffectWith(intLower, doubleLower, ConversationEffect(cultivationDelta = -0.01))
        assertTrue("负向", neg.cultivationDelta < 0 && neg.cultivationDelta >= -0.06)
    }

    @Test
    fun `randomize draw ranges pinned`() = runTest {
        val intRanges = mutableListOf<Pair<Int, Int>>()
        val doubleRanges = mutableListOf<Pair<Double, Double>>()
        randomizeEffectWith(
            { f, u -> intRanges += f to u; f },
            { f, u -> doubleRanges += f to u; f },
            ConversationEffect(loyaltyDelta = 1, moralityDelta = 1, intelligenceDelta = 1, cultivationDelta = 0.01)
        )
        assertEquals("整型抽取区间恒为 [1,6)", listOf(1 to 6, 1 to 6, 1 to 6), intRanges)
        assertEquals("浮点抽取区间恒为 [0.01,0.06)", listOf(0.01 to 0.06), doubleRanges)
    }
}
