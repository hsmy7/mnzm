package com.xianxia.sect.core.nativebridge

import com.xianxia.sect.core.engine.FakeAtomicStateStore
import com.xianxia.sect.core.engine.mockSmart
import com.xianxia.sect.core.engine.service.AutoPillService
import com.xianxia.sect.core.engine.service.CultivationCore
import com.xianxia.sect.core.engine.service.CultivationRateCalculator
import com.xianxia.sect.core.engine.service.CultivationService
import com.xianxia.sect.core.engine.service.CultivationSharedState
import com.xianxia.sect.core.engine.service.EquipmentNurtureService
import com.xianxia.sect.core.engine.service.HpMpRecoveryService
import com.xianxia.sect.core.engine.service.ManualProficiencyService
import com.xianxia.sect.core.model.Disciple
import com.xianxia.sect.core.model.EquipmentInstance
import com.xianxia.sect.core.model.GameData
import com.xianxia.sect.core.model.GridBuildingData
import com.xianxia.sect.core.model.ManualInstance
import com.xianxia.sect.core.model.ResidenceSlot
import com.xianxia.sect.core.state.EntityStore
import com.xianxia.sect.core.state.MutableGameState
import com.xianxia.sect.core.util.DomainLog
import kotlinx.serialization.json.Json
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * 阶段 0 测量：每旬结算热点 + 批量下沉原型（2026-08-25，彻底单引擎决策后新增）。
 *
 * 目标：
 * 1. 量化每旬核心路径（HP/MP 恢复 + 修炼累积，真实 CultivationCore 列直读）在
 *    典型弟子规模下的绝对耗时 → 确定热点排序与 C++ 化收益上界。
 * 2. 量化 C++ 批量通道（nativeCoreAdvancePhases）单次往返成本 → 验证"整批下沉"
 *    形态下传输开销占比（JNI 往返 6-12µs 相对每旬计算量的占比）。
 *
 * 已知偏差（如实标注）：
 * - 未测：功法熟练度/装备孕养/自动丹药/突破检测（生产每旬 5 项中的 3 项，
 *   均为同量级 O(D)；突破 handler 在测试为 mock，测了失真）
 * - CultivationService 的非核心依赖为 mockSmart（不影响被测两条路径——
 *   recoverHpMpSingleColumn / accumulateCultivationPerPhase 均委托真实 CultivationCore）
 * - 桌面 JVM ≠ Android ART
 * - 不设断言阈值（CI 抖动），输出供人工观测
 */
class Phase0SettlementBenchmarkTest {
    private val json = Json { encodeDefaults = true }

    private companion object {
        const val WARMUP_ROUNDS = 3
        const val SAMPLE_ROUNDS = 5
    }

    @Volatile
    private var benchSink = 0L

    private object SilentLogger : DomainLog.Logger {
        override fun d(tag: String, msg: String) { /* 静默 */ }
        override fun i(tag: String, msg: String) { /* 静默 */ }
        override fun w(tag: String, msg: String, throwable: Throwable?) { /* 静默 */ }
        override fun e(tag: String, msg: String, throwable: Throwable?) { /* 静默 */ }
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

    private fun freshCore() {
        DiffRngBridge.nativeDestroy()
        DiffRngBridge.nativeCoreInit()
        val initial = NativeGameState(gameData = GameData().apply { spiritStones = 10000 })
        DiffRngBridge.nativeCoreImportState(
            json.encodeToString(NativeGameState.serializer(), initial).encodeToByteArray()
        )
    }

    // ── Kotlin 每旬核心路径（对应 GameEngineCore.checkBreakthroughsAndPills 主循环）──

    private fun kotlinPerPhaseCore(state: MutableGameState, service: CultivationService) {
        val tables = state.discipleTables
        val equipmentMap = emptyMap<String, EquipmentInstance>()
        val manualMap = emptyMap<String, ManualInstance>()
        val residence = emptyMap<Int, ResidenceSlot>()
        val buildings = emptyMap<String, GridBuildingData>()
        val pending = mutableMapOf<String, Double>()
        for (id in tables.ids) {
            if (tables.isAlive[id] != 1) continue
            // 1) HP/MP 恢复（列直读）
            service.recoverHpMpSingleColumn(
                state, id, phasesToSettle = 1,
                equipmentMap = equipmentMap, manualMap = manualMap, manualProficiencies = null
            )
            // 2) 修炼累积（列直读速率 + 批量投影）
            if (tables.cultivations.getOrDefault(id, 0.0) < 1e8) {
                service.accumulateCultivationPerPhase(
                    id, state, pending, residence, buildings
                )
            }
        }
        benchSink += pending.size
    }

    /** C++ 批量通道：推进 1 旬（时间推进 + 结算引擎），含 JNI 往返 */
    private fun nativeAdvanceOnePhase() {
        benchSink += DiffRngBridge.nativeCoreAdvancePhases(1).toLong()
    }

    private fun buildServiceAndTables(
        discipleCount: Int
    ): Pair<FakeAtomicStateStore, CultivationService> {
        val store = FakeAtomicStateStore().also {
            it.setGameData(GameData(gameYear = 1, gameMonth = 1))
        }
        // 按 CultivationServiceIntegrationTest 同款模式构造真实 CultivationCore + Service
        val core = CultivationCore(
            hpMpRecoveryService = HpMpRecoveryService(),
            autoPillService = AutoPillService(mockSmart(), mockSmart()),
            equipmentNurtureService = EquipmentNurtureService(),
            manualProficiencyService = ManualProficiencyService(),
            cultivationRateCalculator = CultivationRateCalculator(store)
        )
        val service = CultivationService(
            stateStore = store,
            cultivationCore = core,
            breakthroughHandler = mockSmart(),
            cultivationSettlement = mockSmart(),
            eventProcessor = mockSmart(),
            productionProcessor = mockSmart(),
            recruitService = mockSmart(),
            merchantAndRecruitService = mockSmart(),
            caveExplorationProcessor = mockSmart(),
            sharedState = CultivationSharedState(),
            discipleService = mockSmart()
        )
        val tables = store.discipleTables
        store.update {
            for (i in 1..discipleCount) {
                tables.insert(
                    Disciple(
                        id = i.toString(),
                        name = "弟子$i",
                        realm = 9,
                        cultivation = 1000.0,
                        spiritRootType = "1,2"
                    )
                )
            }
        }
        return store to service
    }

    @Test
    fun `phase0 settlement hotspot and batch downsink prototype`() {
        assumeTrue(DiffRngBridge.isAvailable())
        val originalLogger = DomainLog.setLogger(SilentLogger)
        try {
            freshCore()
            // C++ 批量通道单次往返（含 import 后推进 1 旬）
            val nativePerPhaseNs = bestOf(WARMUP_ROUNDS, SAMPLE_ROUNDS) { nativeAdvanceOnePhase() }
            println("PHASE0 native advancePhases(1旬)=${nativePerPhaseNs / 1000.0}us/call")

            // Kotlin 每旬核心路径，多规模梯度
            for (count in intArrayOf(100, 500, 1000, 5000)) {
                val (store, service) = buildServiceAndTables(count)
                val state = MutableGameState(
                    gameData = store.gameData.value,
                    discipleTables = store.discipleTables,
                    equipmentStacks = EntityStore(),
                    equipmentInstances = EntityStore(),
                    manualStacks = EntityStore(),
                    manualInstances = EntityStore(),
                    pills = EntityStore(), materials = EntityStore(),
                    herbs = EntityStore(), seeds = EntityStore(), storageBags = EntityStore(),
                    battleLogs = emptyList(),
                    isPaused = false, isLoading = false, isSaving = false
                )
                val kotlinNs = bestOf(WARMUP_ROUNDS, SAMPLE_ROUNDS) { kotlinPerPhaseCore(state, service) }
                val perDiscipleUs = kotlinNs / 1000.0 / count
                val transferShare = nativePerPhaseNs.toDouble() / kotlinNs * 100.0
                println(
                    "PHASE0 kotlin 每旬核心路径 disciples=$count total=${kotlinNs / 1000.0}us " +
                        "perDisciple=${"%.3f".format(perDiscipleUs)}us " +
                        "批量下沉传输占比=${"%.1f".format(transferShare)}%"
                )
            }
        } finally {
            DomainLog.setLogger(originalLogger)
        }
    }
}
