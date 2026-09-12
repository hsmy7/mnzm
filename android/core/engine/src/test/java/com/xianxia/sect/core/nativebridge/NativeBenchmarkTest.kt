package com.xianxia.sect.core.nativebridge

import com.xianxia.sect.core.engine.FakeAtomicStateStore
import com.xianxia.sect.core.event.EventBus
import com.xianxia.sect.core.model.GameData
import com.xianxia.sect.core.model.SpiritStoneGrade
import com.xianxia.sect.core.util.CoroutineScopeProvider
import com.xianxia.sect.core.util.DomainLog
import com.xianxia.sect.core.wallet.SpiritStoneLedger
import com.xianxia.sect.core.wallet.SpiritStoneOperation
import com.xianxia.sect.core.wallet.SpiritStoneReason
import com.xianxia.sect.core.wallet.SpiritStoneSource
import com.xianxia.sect.core.wallet.SpiritStoneWallet
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * NativeBenchmarkTest — 性能基准（native execute vs Kotlin 实现）。
 *
 * 本文件包含两类基准：
 *
 * 1. **旧基准（legacy）** `wallet add native vs kotlin throughput`：native 侧为完整
 *    JNI+JSON 往返，Kotlin 侧为空操作循环（`stones += 1` 结果未消费）——**非公平对比**，
 *    只演示协议开销量级，禁止作为性能结论引用（不同运行波动大、不可复现）；
 *    保留仅用于守护 native 通道可运行。
 *
 * 2. **正确基准（corrected）** `wallet add corrected benchmark`：修复旧基准 5 个缺陷——
 *    预热（JIT 生效）/ Blackhole 消费结果（防死代码消除）/ 真实实现对比
 *    （Kotlin SpiritStoneWallet vs C++ execute）/ 逐操作 vs 批量 / 多次采样取最小值。
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
        // 旧基准：非公平对比（Kotlin 侧为空操作且结果未消费），仅演示协议开销量级。
        // 该 ratio 不同运行波动大（421×~768×），不可复现，
        // 勿作性能结论；定量依据见 `wallet add corrected benchmark`。
        System.err.println(
            "BENCH-LEGACY walletAdd(非公平对比勿引用) native=${nativeNs / 1000}us/1k " +
                "kotlin(空操作)=" + kotlinNs / 1000 + "us/1k"
        )
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

    // ============================================================
    // 正确方法论基准
    //
    // 对比对象（真实实现，非空操作）：
    //   Kotlin: SpiritStoneWallet.add（独立事务，同生产玩家操作语义）
    //           SpiritStoneWallet.batch（单事务批量，同生产批量结算语义）
    //   native: GameCore.execute(WALLET_ADD)（完整 JNI+JSON 往返）
    //           nativeCoreExecOps（单次往返执行 N 个 op，协议摊销形态）
    //
    // 方法：预热 5 轮 + 采样 5 轮取最小值；结果经 benchSink(volatile) 消费防 DCE；
    // 循环内不写 volatile（避免污染测量），轮末一次性写入。
    // 已知偏差：
    //   - FakeAtomicStateStore 无真实 GameStateStoreImpl 的列级 COW 深拷贝开销，
    //     Kotlin 侧成本为下界（真实事务更高）
    //   - 测量期间 DomainLog 静默（wallet.add 每次调用打印日志，排除 IO 噪声）
    //   - 桌面 JVM ≠ Android ART（真机 JNI 开销通常更高）
    // 不设阈值断言（CI 抖动），输出供人工观测；正确性另行断言。
    // ============================================================

    private companion object {
        const val ITERATIONS = 1000
        const val WARMUP_ROUNDS = 5
        const val SAMPLE_ROUNDS = 5
    }

    @Volatile
    private var benchSink = 0L

    /** 静默日志（测量期间屏蔽 wallet.add 的 DomainLog.d println 噪声） */
    private object SilentLogger : DomainLog.Logger {
        override fun d(tag: String, msg: String) { /* 静默 */ }
        override fun i(tag: String, msg: String) { /* 静默 */ }
        override fun w(tag: String, msg: String, throwable: Throwable?) { /* 静默 */ }
        override fun e(tag: String, msg: String, throwable: Throwable?) { /* 静默 */ }
    }

    /** EventBus 构造即访问 scopeProvider.scope（init startProcessing 启动消费协程），需真实 scope */
    private object UnconfinedScopeProvider : CoroutineScopeProvider {
        override val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        override val ioScope: CoroutineScope = scope
    }

    /** 采样 rounds 轮取最小值（先 warmupRounds 预热触发 JIT） */
    private fun bestOf(warmupRounds: Int, sampleRounds: Int, block: () -> Unit): Long {
        repeat(warmupRounds) { block() }
        var best = Long.MAX_VALUE
        repeat(sampleRounds) {
            val t0 = System.nanoTime()
            block()
            val elapsed = System.nanoTime() - t0
            if (elapsed < best) best = elapsed
        }
        return best
    }

    private fun benchParamsJson(): ByteArray = json.encodeToString(
        JsonObject.serializer(),
        buildJsonObject { put("amount", 1); put("grade", "LOW"); put("source", "Bench") }
    ).encodeToByteArray()

    /** 单条 walletAdd op JSON（批量通道 nativeCoreExecOps 协议） */
    private fun walletOpJson(): JsonObject = buildJsonObject {
        put("op", "walletAdd"); put("amount", 1); put("grade", "LOW"); put("source", "Bench")
    }

    /** Kotlin 真实实现：逐操作（每次独立事务，同生产玩家操作语义） */
    private fun kotlinWalletAddPerOp(store: FakeAtomicStateStore, wallet: SpiritStoneWallet, n: Int) {
        var acc = 0L
        repeat(n) {
            store.update { acc += wallet.add(this, 1, grade = SpiritStoneGrade.LOW) }
        }
        benchSink += acc
    }

    /** Kotlin 真实实现：批量（单事务 batch） */
    private fun kotlinWalletBatch(store: FakeAtomicStateStore, wallet: SpiritStoneWallet, n: Int) {
        val ops = List(n) {
            SpiritStoneOperation(delta = 1, reason = SpiritStoneReason.Internal, source = SpiritStoneSource.Internal)
        }
        var acc = 0L
        store.update { acc += wallet.batch(this, ops, autoConvert = false).successCount }
        benchSink += acc
    }

    /** native 逐操作：完整 JNI+JSON 往返 */
    private fun nativeWalletAddPerOp(n: Int) {
        val params = benchParamsJson()
        var acc = 0L
        repeat(n) {
            acc += DiffRngBridge.nativeCoreExecute(ActionIds.WALLET_ADD, params).size
        }
        benchSink += acc
    }

    /** native 批量：单次往返执行 n 个 op（协议摊销形态） */
    private fun nativeWalletBatch(n: Int) {
        val payload = buildJsonArray {
            repeat(n) { add(walletOpJson()) }
        }
        benchSink += DiffRngBridge.nativeCoreExecOps(
            json.encodeToString(JsonArray.serializer(), payload).encodeToByteArray()
        ).size
    }

    @Test
    fun `wallet add corrected benchmark - real kotlin impl vs native roundtrip`() {
        assumeTrue(DiffRngBridge.isAvailable())
        val originalLogger = DomainLog.setLogger(SilentLogger)
        try {
            // ── 批量通道正确性前置验证（fresh 状态 10000 + 1000 次 +1 = 11000）──
            freshCore()
            val batchPayload = buildJsonArray {
                repeat(ITERATIONS) { add(walletOpJson()) }
            }
            DiffRngBridge.nativeCoreExecOps(
                json.encodeToString(JsonArray.serializer(), batchPayload).encodeToByteArray()
            )
            val afterBatch = json.decodeFromString(
                NativeGameState.serializer(), DiffRngBridge.nativeCoreExportState().decodeToString()
            )
            assertTrue("批量 1000 次 +1 后余额应为 11000", afterBatch.gameData.spiritStones == 11000L)

            // ── 性能测量 ──
            freshCore()
            val store = FakeAtomicStateStore().also {
                it.setGameData(GameData().apply { spiritStones = 10000 })
            }
            val wallet = SpiritStoneWallet(store, SpiritStoneLedger(), EventBus(UnconfinedScopeProvider))

            val kotlinPerOpNs = bestOf(WARMUP_ROUNDS, SAMPLE_ROUNDS) {
                kotlinWalletAddPerOp(store, wallet, ITERATIONS)
            }
            val nativePerOpNs = bestOf(WARMUP_ROUNDS, SAMPLE_ROUNDS) {
                nativeWalletAddPerOp(ITERATIONS)
            }
            val kotlinBatchNs = bestOf(WARMUP_ROUNDS, SAMPLE_ROUNDS) {
                kotlinWalletBatch(store, wallet, ITERATIONS)
            }
            val nativeBatchNs = bestOf(WARMUP_ROUNDS, SAMPLE_ROUNDS) {
                nativeWalletBatch(ITERATIONS)
            }

            val usPerOp = { ns: Long -> ns / 1000.0 / ITERATIONS }
            println(
                "BENCH-CORRECTED perOp kotlin(wallet.add@tx)=${"%.3f".format(usPerOp(kotlinPerOpNs))}us/op " +
                    "native(execute WALLET_ADD)=${"%.3f".format(usPerOp(nativePerOpNs))}us/op " +
                    "ratio=${"%.1f".format(nativePerOpNs.toDouble() / kotlinPerOpNs)}"
            )
            println(
                "BENCH-CORRECTED batch kotlin(wallet.batch@tx)=${"%.3f".format(usPerOp(kotlinBatchNs))}us/op " +
                    "native(execOps)=${"%.3f".format(usPerOp(nativeBatchNs))}us/op " +
                    "ratio=${"%.1f".format(nativeBatchNs.toDouble() / kotlinBatchNs)}"
            )
        } finally {
            DomainLog.setLogger(originalLogger)
        }
    }
}
