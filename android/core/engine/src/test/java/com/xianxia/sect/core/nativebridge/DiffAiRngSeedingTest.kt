package com.xianxia.sect.core.nativebridge

import com.xianxia.sect.core.engine.domain.diplomacy.AISectDiscipleManager
import com.xianxia.sect.core.util.DeterministicRng
import com.xianxia.sect.core.util.GameRngManager
import com.xianxia.sect.core.util.RngPartition
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * 守卫测试：AI 宗门随机流的**播种态**跨语言一致性。
 *
 * ## 存在理由（事故背景）
 *
 * 阶段 1② 把 `AISectDiscipleManager` 的自持影子流归一到 `GameRngManager` 分区后，
 * `initForSlot` 一度写成 `getRng(AI_SECT).restore(aiSeed)`——即把**裸种子**当状态
 * 写入。但 `snapshot()` 是 **PRNG 状态**而非种子：C++ `GameCore::aiRng_`
 *（`game_core.cpp` initialize / rngInitSystemSeed / importStateInternal 三处）
 * 与旧影子流都经 `DeterministicRng.fromSeed(aiSeed)` 走过一轮混种
 *（`state = (seed shl 1) or 1` 后丢弃一次 `nextLong()`）。
 *
 * 后果：同一 `aiSeed` 在两侧得到**两条不同序列**——AI 突破判定/孕养升级/年度招募
 * 结果全部漂移（实测 2 处 `AISectDiscipleManagerTest` 红 + 1 处 `DiffYearSettlementTest`
 * 弟子条数 3 vs 4）。修法 = 写**混种态**（`fromSeed(aiSeed).snapshot()`）。
 *
 * ## 断言口径
 * 1. `initForSlot(seed)` 写入的分区状态 == `fromSeed(seed + 6×31337).snapshot()`；
 * 2. 与 C++ 镜像分区（9）的**首次抽取序列逐位一致**（跨语言同源）；
 * 3. C++ 侧 `mapSeed` 播种（`importStateInternal`）与 Kotlin `initForSlot(mapSeed)` 同式。
 *
 * ## 前置
 * 桌面 JNI 对拍库可用（`-Dgamecore.jni.path=<绝对路径>`），否则跳过。
 */
class DiffAiRngSeedingTest {

    @After
    fun tearDown() {
        AISectDiscipleManager.resetManagerForTest()
    }

    /** `initForSlot` 写入的必须是**混种态**（`fromSeed` 的 snapshot），不是裸种子 */
    @Test
    fun `initForSlot 写入 AI_SECT 分区的是混种态而非裸种子`() {
        val seeds = listOf(0L, 42L, 20261001L)
        for (seed in seeds) {
            AISectDiscipleManager.resetManagerForTest()
            val manager = GameRngManager()
            AISectDiscipleManager.initialize(manager)
            AISectDiscipleManager.initForSlot(seed)

            val aiSeed = seed + RngPartition.AI_SECT.id.toLong() * AI_SECT_SEED_STRIDE
            val expectedMixed = DeterministicRng.fromSeed(aiSeed).snapshot()
            val actual = manager.getRng(RngPartition.AI_SECT).snapshot()

            assertTrue(
                "seed=$seed 的播种态必须是 fromSeed 混种态——裸种子（$aiSeed）会让同一 aiSeed " +
                    "在 Kotlin 与 C++ 得两条不同序列（AI 突破/招募结果漂移）。" +
                    "实测 actual=$actual expected=$expectedMixed",
                actual == expectedMixed
            )
            assertTrue(
                "混种态必须不等于裸种子（否则上述断言恒真、守卫失效）：actual=$actual seed=$aiSeed",
                actual != aiSeed
            )
        }
    }

    /** 混种态 + 首 8 次抽取：Kotlin 本地分区与 C++ 镜像分区逐位一致 */
    @Test
    fun `initForSlot 后前8次抽取与 C++ 镜像分区逐位一致`() {
        assumeTrue(DiffRngBridge.isAvailable())
        val seed = 20261001L
        val aiSeed = seed + RngPartition.AI_SECT.id.toLong() * AI_SECT_SEED_STRIDE

        DiffRngBridge.nativeCoreInitMode(true)
        DiffRngBridge.nativeCoreInit()
        DiffRngBridge.nativeCoreRngInitSeed(seed)

        // C++：镜像分区（9）在 rngInitSystemSeed 后 **不代表** aiRng_（
        // initialize/rngInitSystemSeed 两处同式播种 = fromSeed(seed + 6×31337)）
        DiffRngBridge.nativeCoreRngRestorePartition(
            RngPartition.AI_SECT_MIRROR.id,
            DeterministicRng.fromSeed(aiSeed).snapshot()
        )
        val cppDraws = List(DRAW_COUNT) {
            DiffRngBridge.nativeCoreRngNextInt(RngPartition.AI_SECT_MIRROR.id)
        }

        val manager = GameRngManager()
        AISectDiscipleManager.initialize(manager)
        AISectDiscipleManager.initForSlot(seed)
        val kotlinDraws = List(DRAW_COUNT) {
            manager.getRng(RngPartition.AI_SECT).nextInt()
        }

        assertEquals(
            "AI 分区抽取序列必须跨语言逐位一致（同 aiSeed ⇒ 同序列是阶段 1② 归一的核心契约）",
            cppDraws, kotlinDraws
        )
    }

    /** 同一 seed 重复 `initForSlot` 幂等（重开档/读档多次调用不得漂移） */
    @Test
    fun `initForSlot 同 seed 幂等`() {
        val manager = GameRngManager()
        AISectDiscipleManager.initialize(manager)
        AISectDiscipleManager.initForSlot(7L)
        val first = manager.getRng(RngPartition.AI_SECT).snapshot()
        manager.getRng(RngPartition.AI_SECT).nextInt() // 推进
        AISectDiscipleManager.initForSlot(7L)
        assertEquals("同 seed 重新播种应回到同一状态", first, manager.getRng(RngPartition.AI_SECT).snapshot())
    }

    private companion object {
        /** 断言用抽取次数：确定性偏差单次即可捕获，8 次足以覆盖"首抽分叉" */
        const val DRAW_COUNT = 8

        /**
         * AI 流种子步长（`aiSeed = seed + AI_SECT.id(6) × 31337`）——与
         * `AISectDiscipleManager.AI_SECT_SEED_STRIDE` / C++ `game_core.cpp` 三处
         * 字面量同源；改此值会让两侧分叉。
         */
        const val AI_SECT_SEED_STRIDE = 31337L
    }
}
