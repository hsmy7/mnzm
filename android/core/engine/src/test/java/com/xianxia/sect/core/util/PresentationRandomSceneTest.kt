package com.xianxia.sect.core.util

import com.xianxia.sect.core.engine.FakeAtomicStateStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * PresentationRandom 场景流派生测试（W4 实施文档 §2.B.5）。
 *
 * 守护目标：`scene(key)` 的**跨会话一致**语义——同键恒定 / 异键无关 /
 * 世界种子参与派生 / 与根流零耦合 / 表现类零状态写入 / FNV-1a 跨平台锚点。
 * 另含 `seedFromWorld` 接线守卫（防"定义了但从不调用"缺陷复发）。
 */
class PresentationRandomSceneTest {

    /** 采集一条流的后续 n 次抽取（int/double/boolean 混合，覆盖三类 API） */
    private fun drawSequence(r: PresentationRandom, n: Int): List<String> =
        List(n) { i ->
            when (i % 3) {
                0 -> r.nextInt(10_000).toString()
                1 -> r.nextDouble().toString()
                else -> r.nextBoolean().toString()
            }
        }

    @Test
    fun `scene same key yields identical sequence`() {
        val a = PresentationRandom().scene("chat.42.3")
        val b = PresentationRandom().scene("chat.42.3")
        assertEquals(drawSequence(a, 30), drawSequence(b, 30))
    }

    @Test
    fun `scene different keys yield different sequences`() {
        val a = PresentationRandom().scene("chat.42.3")
        val b = PresentationRandom().scene("chat.42.4")
        assertTrue(
            "异键序列必须不同（防'键没参与哈希'的实现错误）",
            drawSequence(a, 30) != drawSequence(b, 30)
        )
    }

    @Test
    fun `worldSeed participates in derivation`() {
        val a = PresentationRandom().apply { seedFromWorld(1000) }.scene("trial.portrait.d1")
        val b = PresentationRandom().apply { seedFromWorld(2000) }.scene("trial.portrait.d1")
        assertTrue(
            "不同世界种子同键序列必须不同（防'世界种子没参与派生'）",
            drawSequence(a, 30) != drawSequence(b, 30)
        )
    }

    @Test
    fun `scene stream does not disturb root stream`() {
        val rootA = PresentationRandom()
        val rootB = PresentationRandom()
        // 从 rootB 派生场景流并抽取，rootA 保持不被触碰
        val scene = rootB.scene("diplomacy.sect_7.gift.player")
        drawSequence(scene, 10)
        // 两根实例的后续序列仍逐位一致 ⇒ scene() 返回独立实例、零耦合
        assertEquals(drawSequence(rootA, 20), drawSequence(rootB, 20))
    }

    @Test
    fun `scene draws never write state`() {
        val store = FakeAtomicStateStore()
        val before = store.gameDataSnapshot
        val scene = PresentationRandom().scene("loading.tip")
        drawSequence(scene, 50)
        assertEquals("表现类流不得触碰状态仓（R3 边界）", before, store.gameDataSnapshot)
    }

    @Test
    fun `fnv1a64 matches cross-platform literal anchors`() {
        // 跨平台回归锚点：iOS（KMP/Native）侧实现必须复现同一常量
        // （python 复核：basis 0xcbf29ce484222325 / prime 0x100000001b3，UTF-8 字节序）
        assertEquals(6341470988955492644L, fnv1a64("xianxia.fnv.anchor"))
        assertEquals(-6806642932627000892L, fnv1a64("loading.tip"))
        assertEquals(-7261642941156456435L, fnv1a64("chat.1.3"))
    }

    /**
     * 接线守卫（W4 §2.B.5）：`seedFromWorld(` 必须在生产主源存在**真实调用点**
     * （定义文件除外）——本类缺陷史：该方法自引入起全仓零调用 ⇒ 表现流种子
     * 恒为编译期常量，KDoc 承诺落空。接线点被误删时本测试变红。
     */
    @Test
    fun `seedFromWorld has a production call site`() {
        val modules = listOf("core/engine", "core/domain", "core/data", "core/ui", "feature/game", "app")
        val definition = setOf("PresentationRandom.kt")
        val callers = modules
            .map { module ->
                val path = listOf("..", "..")
                    .plus(module.split("/"))
                    .plus(listOf("src", "main"))
                    .joinToString(File.separator)
                File(path)
                    .walkTopDown()
                    .filter { it.isFile && it.extension == "kt" && it.name !in definition }
                    .filter { it.readText().contains("seedFromWorld(") }
                    .map { "${module}/${it.name}" }
                    .toList()
            }
            .flatten()
        assertTrue(
            "seedFromWorld 在生产主源无调用点（表现流播种链被断）" +
                "——期望 BootSequenceController 接线仍在；实得 callers=$callers",
            callers.isNotEmpty()
        )
    }
}
