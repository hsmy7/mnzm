package com.xianxia.sect.ui.game.dialogs

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
                var depth = 0
                var cur = id
                while (depth < 20) {
                    val node = tree.nodes[cur] ?: break
                    if (node.options.isEmpty()) break
                    val next = node.options.first().outcomes.first().nextNodeId
                    if (next == END_NODE) break
                    cur = next; depth++
                }
                assertTrue("Tree $ti node=$id depth=$depth", depth < 20)
            }
        }
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

    @Test
    fun `randomize sign positive`() {
        repeat(50) {
            val r = randomizeEffect(ConversationEffect(loyaltyDelta = 1))
            assertTrue(r.loyaltyDelta > 0 && r.loyaltyDelta in 1..5)
        }
    }

    @Test
    fun `randomize sign negative`() {
        repeat(50) {
            val r = randomizeEffect(ConversationEffect(loyaltyDelta = -1))
            assertTrue(r.loyaltyDelta < 0 && abs(r.loyaltyDelta) in 1..5)
        }
    }

    @Test
    fun `randomize zero fields preserved`() {
        repeat(50) {
            val r = randomizeEffect(ConversationEffect(moralityDelta = 1))
            assertEquals(0, r.loyaltyDelta)
            assertEquals(0, r.intelligenceDelta)
            assertEquals(0.0, r.cultivationDelta, 0.001)
        }
    }

    @Test
    fun `randomize zero in zero out`() {
        assertTrue(randomizeEffect(ConversationEffect()).isZero)
    }

    @Test
    fun `randomize cultivation percent`() {
        repeat(50) {
            val r = randomizeEffect(ConversationEffect(cultivationDelta = 0.01))
            assertTrue("pos", r.cultivationDelta > 0)
            assertTrue("range", r.cultivationDelta in 0.01..<0.06)
        }
    }
}
