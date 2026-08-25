package com.xianxia.sect.core.nativebridge

import com.xianxia.sect.core.model.GameData
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * NativeBenchmarkTest — 批次 9 剩余性能基准（native execute vs Kotlin 实现）。
 *
 * 目标：量化 C++ 引擎 vs Kotlin 引擎的代表动作耗时对比，验证"计算下沉原生"
 * 的性能收益假设（ADR 正面收益之一）。不设严格断言阈值（CI 抖动），只记录
 * 对比比例并断言 native 可运行 + 结果正确——性能回归由人工观测报告。
 *
 * 运行前提：桌面 JNI 对拍库可用（-Dgamecore.jni.path=...），否则跳过。
 * 复用 DiffRngBridge 通道（与 DiffExecuteTest 同源）。
 */
class NativeBenchmarkTest {
    private val json = Json { encodeDefaults = true }

    private fun freshCore() {
        DiffRngBridge.nativeDestroy()
        DiffRngBridge.nativeCoreInit()
        val initial = NativeGameState(gameData = GameData().apply { spiritStones = 10000 })
        DiffRngBridge.nativeCoreImportState(
            json.encodeToString(NativeGameState.serializer(), initial).encodeToByteArray()
        )
    }

    private fun nativeWalletAdd(): Long {
        val t0 = System.nanoTime()
        repeat(1000) {
            DiffRngBridge.nativeCoreExecute(
                ActionIds.WALLET_ADD,
                json.encodeToString(JsonObject.serializer(), buildJsonObject {
                    put("amount", 1); put("grade", "LOW"); put("source", "Bench")
                }).encodeToByteArray()
            )
        }
        return System.nanoTime() - t0
    }

    private fun kotlinWalletAdd(): Long {
        val t0 = System.nanoTime()
        var stones = 10000L
        repeat(1000) {
            stones += 1
        }
        return System.nanoTime() - t0
    }

    @Test
    fun `wallet add native vs kotlin throughput`() {
        assumeTrue(DiffRngBridge.isAvailable())
        freshCore()
        val nativeNs = nativeWalletAdd()
        val kotlinNs = kotlinWalletAdd()
        val ratio = nativeNs.toDouble() / kotlinNs.toDouble()
        // 记录基准（无严格阈值；native 含 JSON 编解码 + JNI 开销，纯加法无意义，
        // 但保证 native 通道在基准规模下可运行且结果正确）
        System.err.println("BENCH walletAdd native=${nativeNs / 1000}us/1k kotlin=${kotlinNs / 1000}us/1k ratio=$ratio")
        assertTrue("native 通道应可运行", nativeNs > 0)
    }

    @Test
    fun `native execute result correctness at scale`() {
        assumeTrue(DiffRngBridge.isAvailable())
        freshCore()
        repeat(1000) {
            DiffRngBridge.nativeCoreExecute(
                ActionIds.WALLET_ADD,
                json.encodeToString(JsonObject.serializer(), buildJsonObject {
                    put("amount", 1); put("grade", "LOW"); put("source", "Bench")
                }).encodeToByteArray()
            )
        }
        val decoded = json.decodeFromString(
            NativeGameState.serializer(), DiffRngBridge.nativeCoreExportState().decodeToString()
        )
        assertTrue("1000 次 +1 后余额应为 11000", decoded.gameData.spiritStones == 11000L)
    }
}
