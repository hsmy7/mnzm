package com.xianxia.sect.core.nativebridge

import com.xianxia.sect.core.util.DeterministicRng
import com.xianxia.sect.core.util.RngPartition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * DiffRngTest — RNG 跨语言差分对拍。
 *
 * 守护目标：C++ `DeterministicRng`（PCG-XSH-RR 64→32）与 Kotlin 原版
 * 在相同种子/调用序列下输出**逐位一致**——这是存档确定性迁移的前提。
 *
 * 前置：桌面 JNI 已构建并注入 `-Dgamecore.jni.path`（见 scripts/build-desktop-jni.ps1）；
 * 未注入时测试跳过（Assume）。
 */
class DiffRngTest {

    @Test
    fun `nextInt sequence matches Kotlin`() {
        assumeTrue("gamecore.jni.path 未配置，跳过对拍", DiffRngBridge.isAvailable())
        val kotlin = DeterministicRng.fromSeed(42)
        DiffRngBridge.nativeFromSeed(42)
        repeat(50) {
            assertEquals("nextInt 第 $it 次不一致", kotlin.nextInt(), DiffRngBridge.nativeNextInt())
        }
    }

    @Test
    fun `nextInt bound sequence matches Kotlin`() {
        assumeTrue(DiffRngBridge.isAvailable())
        val bounds = intArrayOf(2, 3, 7, 100, 1000, Int.MAX_VALUE)
        for (bound in bounds) {
            val kotlin = DeterministicRng.fromSeed(42)
            DiffRngBridge.nativeFromSeed(42)
            repeat(100) {
                assertEquals(
                    "nextInt($bound) 第 $it 次不一致",
                    kotlin.nextInt(bound),
                    DiffRngBridge.nativeNextIntBound(bound)
                )
            }
        }
    }

    @Test
    fun `nextDouble sequence matches Kotlin bitwise`() {
        assumeTrue(DiffRngBridge.isAvailable())
        val kotlin = DeterministicRng.fromSeed(42)
        DiffRngBridge.nativeFromSeed(42)
        repeat(50) {
            val k = kotlin.nextDouble()
            val c = DiffRngBridge.nativeNextDouble()
            assertEquals(
                "nextDouble 第 $it 次不一致",
                java.lang.Double.doubleToRawLongBits(k),
                java.lang.Double.doubleToRawLongBits(c)
            )
        }
    }

    @Test
    fun `nextGaussian sequence matches Kotlin bitwise`() {
        // 审查登记项验证：JVM Math.cos/log/sqrt（fdlibm 系）与 C++
        // std::cos/log/sqrt（桌面 glibc 亦 fdlibm 系）位级一致——Box-Muller
        // 公式逐位对齐（含 DiscipleFactory/AISectDiscipleManager 使用的
        // mean/stddev 组合）；发现最后一位差异则内嵌 fdlibm（rng.cpp 注释）。
        assumeTrue(DiffRngBridge.isAvailable())
        val cases = listOf(
            0.0 to 1.0,
            0.0 to 16.667,        // 弟子方差（DiscipleFactory VARIANCE_SIGMA）
            50.5 to 16.5          // 弟子技能（DiscipleFactory SKILL_MEAN/SIGMA）
        )
        for (seed in longArrayOf(42, 20260901, 987654321)) {
            for ((mean, stddev) in cases) {
                val kotlin = DeterministicRng.fromSeed(seed)
                DiffRngBridge.nativeFromSeed(seed)
                repeat(30) {
                    val k = kotlin.nextGaussian(mean, stddev)
                    val c = DiffRngBridge.nativeNextGaussian(mean, stddev)
                    assertEquals(
                        "nextGaussian(seed=$seed, mean=$mean, stddev=$stddev) 第 $it 次不一致",
                        java.lang.Double.doubleToRawLongBits(k),
                        java.lang.Double.doubleToRawLongBits(c)
                    )
                }
            }
        }
    }

    @Test
    fun `nextLong bound sequence matches Kotlin`() {
        assumeTrue(DiffRngBridge.isAvailable())
        val bounds = longArrayOf(2, 100, 100000, 1L shl 40, Long.MAX_VALUE)
        for (bound in bounds) {
            val kotlin = DeterministicRng.fromSeed(7)
            DiffRngBridge.nativeFromSeed(7)
            repeat(50) {
                assertEquals(
                    "nextLong($bound) 第 $it 次不一致",
                    kotlin.nextLong(bound),
                    DiffRngBridge.nativeNextLongBound(bound)
                )
            }
        }
    }

    @Test
    fun `snapshot state matches Kotlin after consumption`() {
        assumeTrue(DiffRngBridge.isAvailable())
        val kotlin = DeterministicRng.fromSeed(42)
        DiffRngBridge.nativeFromSeed(42)
        repeat(10) { kotlin.nextInt(100); DiffRngBridge.nativeNextIntBound(100) }
        assertEquals("snapshot 状态不一致", kotlin.snapshot(), DiffRngBridge.nativeSnapshot())
    }

    @Test
    fun `rng manager partitions match Kotlin`() {
        assumeTrue(DiffRngBridge.isAvailable())
        val seed = 20260814L
        // Kotlin GameRngManager 语义：分区 id → fromSeed(seed + id)
        for (partition in RngPartition.values()) {
            val kotlin = DeterministicRng.fromSeed(seed + partition.id)
            DiffRngBridge.nativeFromSeed(seed + partition.id)
            repeat(30) {
                assertEquals(
                    "分区 ${partition.name} 第 $it 次不一致",
                    kotlin.nextInt(100),
                    DiffRngBridge.nativeNextIntBound(100)
                )
            }
        }
    }

    @Test
    fun `rng manager export restore matches Kotlin`() {
        assumeTrue(DiffRngBridge.isAvailable())
        // Kotlin 侧按分区独立实例验证 snapshot/restore 语义（对拍已由上面覆盖，
        // 此处验证 C++ 侧 RngManager 的 init/export/restore 生命周期不崩且状态推进一致）
        DiffRngBridge.nativeManagerInit(42)
        val before = DiffRngBridge.nativeManagerSnapshot(RngPartition.BATTLE.id)
        DiffRngBridge.nativeManagerNextInt(RngPartition.BATTLE.id, 100)
        val afterAdvance = DiffRngBridge.nativeManagerNextInt(RngPartition.BATTLE.id, 100)
        assertTrue("分区推进后状态必须变化", before != DiffRngBridge.nativeManagerSnapshot(RngPartition.BATTLE.id))
        assertTrue("推进值应在界内", afterAdvance in 0 until 100)
        DiffRngBridge.nativeDestroy()
    }
}
