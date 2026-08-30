package com.xianxia.sect.core.nativebridge

import com.xianxia.sect.core.util.DeterministicRng
import com.xianxia.sect.core.util.NameService
import com.xianxia.sect.core.util.asKotlinRandom
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToJsonElement
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * DiffNameServiceTest — 中文名继承跨语言差分对拍（批 13-4a）。
 *
 * 守护目标：Kotlin `NameService.inheritName`（批 13-4a 分区 rng 版——
 * 原 JVM 全局 Random 非确定性，S-19 同族修正）与 C++
 * `gamecore::system::inheritName`（name_service.h）在相同种子/调用序列下
 * 产出**逐字符一致**的名字——数据表（姓氏/双字/单字名）逐项一致 + RNG
 * 序列（nextDouble 决定双字/单字、nextInt(bound) 选名）逐位一致。
 *
 * 前置：桌面 JNI 已构建并注入 `-Dgamecore.jni.path`；未注入时跳过。
 */
class DiffNameServiceTest {

    private val json = Json

    @Test
    fun `inheritName matches Kotlin with partition rng`() {
        assumeTrue(DiffRngBridge.isAvailable())
        val surnames = listOf("李", "慕容", "玄")
        val genders = listOf("male", "female")
        for (seed in longArrayOf(42, 20260901, 987654321)) {
            for (surname in surnames) {
                for (gender in genders) {
                    val kRng = DeterministicRng.fromSeed(seed)
                    DiffRngBridge.nativeFromSeed(seed)
                    repeat(20) {
                        val k = NameService.inheritName(
                            surname, gender, emptySet(), kRng.asKotlinRandom()
                        )
                        val c = DiffRngBridge.nativeNameInherit(
                            surname, gender, "[]"
                        )
                        assertEquals(
                            "inheritName(seed=$seed, surname=$surname, " +
                                "gender=$gender) 第 $it 次不一致",
                            k.fullName, c
                        )
                    }
                }
            }
        }
    }

    @Test
    fun `inheritName avoids existing names matching Kotlin`() {
        // 冲突规避路径：existingNames 含常见名 → 双方 50 次尝试后走
        // 兜底分支（base 冲突 → 数字后缀）
        assumeTrue(DiffRngBridge.isAvailable())
        val existing = setOf("李逍遥", "李清风", "李明月", "李玄真", "李道尘")
        val existingJson = json.encodeToJsonElement(existing).toString()
        for (seed in longArrayOf(7, 123456)) {
            val kRng = DeterministicRng.fromSeed(seed)
            DiffRngBridge.nativeFromSeed(seed)
            repeat(10) {
                val k = NameService.inheritName(
                    "李", "male", existing, kRng.asKotlinRandom()
                )
                val c = DiffRngBridge.nativeNameInherit("李", "male", existingJson)
                assertEquals("冲突规避名字不一致（seed=$seed 第 $it 次）", k.fullName, c)
            }
        }
    }
}
