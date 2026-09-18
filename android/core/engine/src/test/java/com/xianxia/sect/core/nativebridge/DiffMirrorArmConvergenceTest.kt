package com.xianxia.sect.core.nativebridge

import com.xianxia.sect.core.model.CombatAttributes
import com.xianxia.sect.core.model.Disciple
import com.xianxia.sect.core.model.DiscipleStatus
import com.xianxia.sect.core.model.GameData
import com.xianxia.sect.core.model.Herb
import com.xianxia.sect.core.model.Material
import com.xianxia.sect.core.model.Pill
import com.xianxia.sect.core.model.Seed
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * DiffMirrorArmConvergenceTest — R2.3 第一波「残余消费点」等价守卫 + 镜像链路
 * e2e 观测固化。
 *
 * ## 审计定位（docs/mirror-consumer-audit-2026-09-18.md 馈送点 F1/F2/F3）
 * StateSyncService → GameStateStore 的镜像馈送链在 B06 后共三个写入臂：
 *   - **F1 增量二进制臂**（生产稳态热路径）：`nativeExportDirty` → GameView
 *     protobuf → [StateSyncService.applyDirtyProto]；
 *   - **F2 增量 JSON 臂**（灰度回滚臂，旗标 false）：`nativeExportDirty` →
 *     文本 → [StateSyncService.applyDirty]；
 *   - **F3 全量 JSON 兜底臂**（F1/F2 不可用时的低频兜底）：`nativeExportState`
 *     全量快照 → [StateSyncService.applySnapshot]（R2.2 明确 nativeExportState
 *     保留 JSON——本批红线「C++ 侧原则上不动」，为其新增全量 GameView 编码器
 *     属第二波瘦身面）。
 * B06 只锁了 F1↔F2 在**单封**变更集上的等价（DiffDirtyEnvelopeEquivalenceTest）。
 * 本类补两段缺口：
 *   1. **多旬连续收敛**：真实 C++ 结算逐旬推进下，F1 与 F2 的 GameStateStore
 *      结果逐旬保持全等（版本号/计数/基线消费在**序列**上不错位，而非仅单封对拍）；
 *   2. **残余消费点 F3 收敛**：二进制增量臂与全量 JSON 兜底臂馈送到同一初态的
 *      store 后同形同值——证明"只换传输"后低频兜底臂与稳态臂对 UI 语义同源，
 *      切传输不引入形状差异（数据形状差异即缺陷 ⇒ 本守卫 fail-fast）。
 *
 * ## 观测固化（G2 趋势）
 * 逐旬记录两传输字节量与两臂 mirror 段耗时并断言：换轨确实生效（二进制信封不
 * 再是 JSON 文本）、稳态 mirror 段中位数低于既有 100ms 告警线（WS-1 悬置阈值）。
 * G2 的 <10ms 终态属 R2.3 第二波镜像瘦身（本批镜像仍全量），故不断言 10ms；
 * 字节量比例的趋势证据另见 `DirtyTrackerBench.MirrorTransportJsonVsProtobuf`
 * （5000 弟子全脏态 protobuf ≈ JSON 的 1/5.5）。
 *
 * 平台约束：普通 JUnit（桌面 .so 单 ClassLoader）；弟子表 SparseArray 在普通
 * JVM 不可用 ⇒ 弟子面断言收敛到 applier 计数（upsert/removed 两臂全等），
 * 行级数据形状由 [MirrorProtoFeedEquivalenceTest]（Robolectric）覆盖。
 */
class DiffMirrorArmConvergenceTest {

    @Test
    fun `binary incremental arm converges with legacy arms across phases`() {
        assumeTrue(DiffRngBridge.isAvailable())
        DiffRngBridge.nativeCoreInit()

        val arms = MirrorArms()
        repeat(PHASES) { arms.advanceOnePhase() }
        arms.assertConvergenceAndObservation()
    }

    // ── 三臂夹具 + 逐旬推进与对账 ────────────────────────────────

    private class MirrorArms {
        private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }
        private val seed = seedState()
        private val storeBinary = FakeGameStateStore().also { seedStore(it, seed) }
        private val storeRollback = FakeGameStateStore().also { seedStore(it, seed) }
        private val storeFull = FakeGameStateStore().also { seedStore(it, seed) }
        private val binaryArm = StateSyncService(storeBinary)
        private val rollbackArm = StateSyncService(storeRollback)
        private val fullArm = StateSyncService(storeFull)
        private val binaryMs = mutableListOf<Double>()
        private val fullMs = mutableListOf<Double>()
        private var protoBytes = 0
        private var jsonBytes = 0
        private var nonEmptyEnvelopes = 0
        private var lastVersion = 0L

        init {
            assertTrue(
                "C++ 导入失败",
                DiffRngBridge.nativeCoreImportState(
                    json.encodeToString(NativeGameState.serializer(), seed).encodeToByteArray()
                )
            )
        }

        /** 一旬：真实 C++ 结算 → 同一棵变更集树按两种编码分别馈送三臂并对账。 */
        fun advanceOnePhase() {
            val dirtyJson = settleAndExportDirtyJson()
            val proto = DiffRngBridge.nativeCoreEncodeGameView(dirtyJson.encodeToByteArray())
            protoBytes += proto.size
            jsonBytes += dirtyJson.length
            assertTrue(
                "二进制信封不得仍是 JSON 文本（换轨未生效）",
                runCatching { json.parseToJsonElement(proto.decodeToString()) }.getOrNull() == null
            )

            val tBinary = System.nanoTime()
            val viaProto = binaryArm.applyDirtyProto(proto)
            binaryMs += msSince(tBinary)
            val viaJson = rollbackArm.applyDirty(dirtyJson)
            val tFull = System.nanoTime()
            val viaFull = applyFullSnapshotArm()
            fullMs += msSince(tFull)

            assertNotNull("F1 二进制信封解析失败", viaProto)
            assertNotNull("F2 JSON 信封解析失败", viaJson)
            assertTrue("F3 全量快照解析失败", viaFull)
            assertEquals("F1/F2 应用契约逐字段等价", viaJson, viaProto)
            assertVersionMonotonic(viaProto)
            assertEquals(
                "F1/F2 gameData 全字段一致", storeRollback.gameDataValue, storeBinary.gameDataValue
            )
            assertEquals(
                "F1/F3 gameData 全字段一致", storeFull.gameDataValue, storeBinary.gameDataValue
            )
            assertCollectionsAligned()
        }

        /** 收敛结论 + 观测固化（首封含 protobuf 运行时一次性初始化，按稳态段计）。 */
        fun assertConvergenceAndObservation() {
            assertTrue("须产生 ≥1 封非空变更集（否则本守卫空转）", nonEmptyEnvelopes >= 1)
            val binary = steadyMedian(binaryMs)
            val full = steadyMedian(fullMs)
            println(
                "[B07-mirror-obs] phases=$PHASES 非空封=$nonEmptyEnvelopes " +
                    "protoBytes=$protoBytes jsonBytes=$jsonBytes " +
                    "传输字节比=${"%.2f".format(protoBytes.toDouble() / jsonBytes)} " +
                    "增量臂稳态中位=${"%.3f".format(binary)}ms 首封=${"%.3f".format(binaryMs.first())}ms " +
                    "兜底臂稳态中位=${"%.3f".format(full)}ms"
            )
            assertBudget("增量臂（二进制）", binary)
            assertBudget("兜底臂（全量 JSON）", full)
        }

        private fun settleAndExportDirtyJson(): String {
            DiffRngBridge.nativeCoreSettlePhase()
            // 桌面桥 nativeCoreExportDirty 恒产出 JSON（对拍零漂移红线保留），
            // encodeGameView 是不触碰基线的纯编码器——二者严格配对同一棵变更集树
            return DiffRngBridge.nativeCoreExportDirty().decodeToString()
        }

        private fun assertVersionMonotonic(result: DirtyApplyResult?) {
            if (result == null ||
                result.changedFieldCount + result.upsertCount + result.removedCount == 0
            ) {
                return
            }
            nonEmptyEnvelopes++
            assertTrue(
                "版本号必须单调递增（基线消费不错位）：$lastVersion → ${result.version}",
                result.version > lastVersion
            )
            lastVersion = result.version
        }

        /** F3 兜底臂 = 生产 syncFromNative 的解码序列（全量快照 + gameData 键白名单）。 */
        private fun applyFullSnapshotArm(): Boolean {
            val exported = DiffRngBridge.nativeCoreExportState()
            if (exported.isEmpty()) return false
            val root = runCatching { json.parseToJsonElement(exported.decodeToString()).jsonObject }
                .getOrNull() ?: return false
            val snapshot = runCatching {
                json.decodeFromJsonElement(NativeGameState.serializer(), root)
            }.getOrNull() ?: return false
            fullArm.applySnapshot(snapshot, (root["gameData"] as? JsonObject)?.keys ?: emptySet())
            return true
        }

        private fun assertCollectionsAligned() {
            assertEquals("pills F1/F2 一致", storeRollback.pillsValue, storeBinary.pillsValue)
            assertEquals("pills F1/F3 一致", storeFull.pillsValue, storeBinary.pillsValue)
            assertEquals("herbs F1/F2 一致", storeRollback.herbsValue, storeBinary.herbsValue)
            assertEquals("herbs F1/F3 一致", storeFull.herbsValue, storeBinary.herbsValue)
            assertEquals(
                "materials F1/F2 一致", storeRollback.materialsValue, storeBinary.materialsValue
            )
            assertEquals("materials F1/F3 一致", storeFull.materialsValue, storeBinary.materialsValue)
            assertEquals("seeds F1/F2 一致", storeRollback.seedsValue, storeBinary.seedsValue)
            assertEquals("seeds F1/F3 一致", storeFull.seedsValue, storeBinary.seedsValue)
            assertEquals(
                "storageBags F1/F2 一致", storeRollback.storageBagsValue, storeBinary.storageBagsValue
            )
            assertEquals(
                "storageBags F1/F3 一致", storeFull.storageBagsValue, storeBinary.storageBagsValue
            )
        }

        private fun assertBudget(label: String, ms: Double) = assertTrue(
            "$label 稳态 mirror 超 ${MIRROR_WARN_BUDGET_MS}ms 告警线：$ms ms",
            ms < MIRROR_WARN_BUDGET_MS
        )

        private fun steadyMedian(samples: List<Double>): Double =
            samples.drop(1).sorted().let { it[it.size / 2] }

        private fun msSince(t0: Long): Double = (System.nanoTime() - t0) / 1_000_000.0
    }

    companion object {
        private const val PHASES = 12

        /** 每旬镜像耗时告警线（WS-1 悬置阈值；G2 终态 <10ms 属第二波瘦身） */
        private const val MIRROR_WARN_BUDGET_MS = 100.0

        private fun seedState(): NativeGameState = NativeGameState(
            gameData = GameData().apply {
                spiritStones = 5000
                gameYear = 2
                gameMonth = 3
                gamePhase = 0
                sectName = "收敛宗"
            },
            disciples = listOf(disciple("1", "玄真", 3), disciple("2", "李四", 5), disciple("3", "王五", 7)),
            pills = listOf(Pill(id = "p1", name = "聚气丹", rarity = 1, quantity = 4)),
            herbs = listOf(Herb(id = "h1", name = "灵草", rarity = 1, quantity = 6, category = "普通")),
            materials = listOf(Material(id = "m1", name = "兽皮", rarity = 1, quantity = 3)),
            seeds = listOf(Seed(id = "s1", name = "灵草种", rarity = 1, quantity = 2, growTime = 3, yield = 1)),
        )

        private fun disciple(id: String, name: String, realm: Int): Disciple = Disciple(
            id = id,
            name = name,
            realm = realm,
            realmLayer = 1,
            cultivation = 10.0,
            soulPower = 100,
            status = DiscipleStatus.IDLE,
        ).apply {
            combat = CombatAttributes(currentHp = -1, currentMp = -1)
        }

        private fun seedStore(store: FakeGameStateStore, seed: NativeGameState) {
            store.gameDataValue = seed.gameData
            store.disciplesValue = seed.disciples
            store.pillsValue = seed.pills
            store.herbsValue = seed.herbs
            store.materialsValue = seed.materials
            store.seedsValue = seed.seeds
        }
    }
}
