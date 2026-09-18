package com.xianxia.sect.core.gameview

import com.xianxia.sect.core.model.CombatAttributes
import com.xianxia.sect.core.model.Disciple
import com.xianxia.sect.core.model.GameData
import com.xianxia.sect.core.model.GameEventRecord
import com.xianxia.sect.core.model.GridBuildingData
import com.xianxia.sect.core.model.WorldSect
import com.xianxia.sect.core.nativebridge.FakeGameStateStore
import com.xianxia.sect.core.nativebridge.GameViewMirrorCodec
import com.xianxia.sect.core.nativebridge.MirrorDiscipleRowFixture
import com.xianxia.sect.core.nativebridge.NativeEngineFlag
import com.xianxia.sect.core.nativebridge.StateSyncService
import com.xianxia.sect.proto.gameview.DiscipleListDelta
import com.xianxia.sect.proto.gameview.DiscipleRow
import com.xianxia.sect.proto.gameview.GameView
import com.xianxia.sect.proto.gameview.ResourcesHeader
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * MirrorSegmentProjectionBenchTest —— R2.3 第二波 mirror 段**消费侧**耗时对照
 * （批次验收门 4：G2 趋势证据）。
 *
 * ## 观测对象
 * B06 的 `DirtyTrackerBench.MirrorTransportJsonVsProtobuf` 量的是 C++ 生产侧
 * （同一棵变更集树的两种终端编码）；本类量的是本批改动的 **Kotlin 消费侧**：
 * 同一封"每旬全脏"信封（弟子行全字段 upsert + 资源头部标量）喂进镜像链两臂——
 *
 * - **第一波形态**（`gameViewProjection=false`）：整份 GameData JSON
 *   encode→覆盖→decode（每旬级全量重建）+ 每行 109 键 JsonElement 造树 +
 *   kotlinx 逐行结构解码；
 * - **第二波形态**（生产默认 true）：一次浅拷贝 + 变更字段逐个解码 +
 *   弟子行 typed 直读投影。
 *
 * 两臂共享同一 store、同一 `upsertMirrorRow` 落表面与同一投影块集合，差值即
 * "退役掉的全量重建形状"的成本。分阶段计时（decode / apply）把差异归因到环节，
 * 而不是只报一个总数。
 *
 * ## 断言口径
 * 桌面 JVM + Robolectric 抖动大，不设绝对阈值门（与 B06 bench 同规）：只锁两条
 * 硬性质——投影臂总耗时不高于旧臂（超 15% 即红，本批是瘦身不是增重）、两臂落库
 * 与投影块逐字段全等（UI 行为零变更红线）。数字经 println/stderr 输出，供方案
 * §7.2 B08 行与完成报告引用。
 *
 * ## 已知观测偏差（诚实登记）
 * 测试替身 [FakeGameStateStore] 每次事务提交后 `assembleAll()` 全表组装（生产是
 * 锁外增量/patch 组装），故两臂的 apply 段都含一份相同的 O(D) 组装常数；
 * 真实设备的 mirror 段构成另由 PhaseSegmentTimer 每旬打点（debug 构建）。
 */
@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
class MirrorSegmentProjectionBenchTest {

    @Test
    fun `mirror 段消费侧两臂耗时对照 每旬全脏信封`() {
        for (size in SCALES) runOneScale(size)
    }

    /**
     * R2.4/B09 G2 重测：**列级导出信封**（每旬修炼热路径脏列——
     * cultivation/currentHp/currentMp，即 C++ 写屏障稳态产出形状）的消费
     * 侧对照——列级臂（补丁合并应用）对第二波全脏投影臂。
     *
     * 断言口径：硬性质两条（列级臂不得明显慢于全脏投影臂；两臂落库逐字段
     * 全等）+ 数字打印供方案 §7.2 B09 行与 WS-1 判定引用。
     */
    @Test
    fun `mirror 段消费侧列级导出臂对照 G2 重测`() {
        for (size in SCALES) runColumnOneScale(size)
    }

    private fun runColumnOneScale(count: Int) {
        discipleCount = count
        val sparseBytes = sparseEnvelope(count).toByteArray()
        val fullBytes = envelope(discipleRows(count)).toByteArray()

        val column = measureColumn(sparseBytes)
        val projectedFull = measure(fullBytes, projection = true)

        val line = "[B09-mirror-bench] D=" + count +
            " 列级信封=" + sparseBytes.size + "B 全脏信封=" + fullBytes.size + "B " +
            "列级臂=decode " + ms(column.decodeNs) + "ms + apply " + ms(column.applyNs) +
            "ms 合计 " + ms(column.totalNs) + "ms | " +
            "全脏投影臂=decode " + ms(projectedFull.decodeNs) + "ms + apply " +
            ms(projectedFull.applyNs) + "ms 合计 " + ms(projectedFull.totalNs) + "ms | " +
            "列级降本 " + pct(column, projectedFull)
        println(line)
        System.err.println(line)

        assertTrue(
            "列级臂不得比全脏投影臂明显更慢（列级导出是 G2 瘦身不是增重）：\n$line",
            column.totalNs <= projectedFull.totalNs * 1.15,
        )
        assertTrue(
            "列级臂与全脏投影臂 store 落库必须逐字段全等（合并语义 = 全行语义）",
            column.store.gameDataValue == projectedFull.store.gameDataValue &&
                column.store.disciplesValue == projectedFull.store.disciplesValue,
        )
    }

    /**
     * 稳态列级信封：全部行各携**仅脏列**（每旬修炼热路径 = cultivation +
     * currentHp/currentMp；与 C++ 写屏障稳态产出形状一致——id 键恒携带）。
     */
    private fun sparseEnvelope(count: Int): GameView {
        val rows = List(count) { index ->
            // 从零构造稀疏行（不从全行改写——只有 id 键 + 脏列 presence，
            // 与 C++ 列级写屏障稳态产出形状一致）
            DiscipleRow.newBuilder()
                .setId((index + 1).toString())
                .setCultivation(11.0 + index)
                .setCurrentHp(500 + index)
                .setCurrentMp(300)
                .build()
        }
        return GameView.newBuilder()
            .setVersion(1L)
            .setResourcesHeader(ResourcesHeader.newBuilder().setSpiritStones(SPIRIT_STONES).build())
            .setDiscipleListDelta(DiscipleListDelta.newBuilder().addAllUpserts(rows).build())
            .build()
    }

    /** 列级臂计时：投影 + 列级两旗标开，decode/apply 分段同 [measure]。 */
    private fun measureColumn(bytes: ByteArray): Arm {
        val previousProjection = NativeEngineFlag.gameViewProjection
        val previousColumn = NativeEngineFlag.dirtyColumnExport
        NativeEngineFlag.gameViewProjection = true
        NativeEngineFlag.dirtyColumnExport = true
        try {
            var bestDecode = Long.MAX_VALUE
            var bestApply = Long.MAX_VALUE
            var store: FakeGameStateStore? = null
            var views: GameViewStore? = null
            repeat(REPEATS) {
                val localStore = seededStore()
                val localViews = GameViewStore().also { it.attach(localStore) }
                val service = StateSyncService(localStore, localViews)
                val t0 = System.nanoTime()
                val decoded = GameViewMirrorCodec.decodeView(
                    GameView.parseFrom(bytes),
                    includeDiscipleJson = false,
                    discipleJson = lenientJson,
                    discipleRowsAsPatches = true,
                )
                val t1 = System.nanoTime()
                val applied = service.applyDirtyProto(bytes)
                val t2 = System.nanoTime()
                bestDecode = minOf(bestDecode, t1 - t0)
                bestApply = minOf(bestApply, t2 - t1)
                check(applied != null && decoded.disciplePatches.isNotEmpty()) { "列级信封未被应用（守卫空转）" }
                store = localStore
                views = localViews
            }
            return Arm(requireNotNull(store), requireNotNull(views), bestDecode, bestApply)
        } finally {
            NativeEngineFlag.gameViewProjection = previousProjection
            NativeEngineFlag.dirtyColumnExport = previousColumn
        }
    }

    private fun runOneScale(count: Int) {
        discipleCount = count
        val bytes = envelope(discipleRows(count)).toByteArray()

        val legacy = measure(bytes, projection = false)
        val projected = measure(bytes, projection = true)

        val line = "[B08-mirror-bench] D=" + count + " 信封=" + bytes.size + "B " +
            "旧臂(全量重建)=decode " + ms(legacy.decodeNs) + "ms + apply " + ms(legacy.applyNs) +
            "ms 合计 " + ms(legacy.totalNs) + "ms | " +
            "新臂(投影)=decode " + ms(projected.decodeNs) + "ms + apply " + ms(projected.applyNs) +
            "ms 合计 " + ms(projected.totalNs) + "ms | 消费侧降本 " + pct(projected, legacy)
        println(line)
        System.err.println(line)

        assertTrue(
            "投影臂不得比第一波形态明显更慢（本批是瘦身不是增重）：\n$line",
            projected.totalNs <= legacy.totalNs * 1.15
        )
        assertTrue(
            "两臂 store 落库结果必须逐字段全等（UI 行为零变更红线）",
            legacy.store.gameDataValue == projected.store.gameDataValue &&
                legacy.store.disciplesValue == projected.store.disciplesValue
        )
        assertEquals(
            "投影块必须等于迁移前从整份 store 快照取数的结果（已迁 UI 块不因旗标分叉）",
            GameViewStore.resourcesViewOf(projected.store.gameDataValue),
            projected.projection.resourcesHeader.value
        )
        assertTrue("投影臂必须被镜像馈送过", projected.projection.projectionGeneration > 0L)
        assertEquals(
            "回滚臂（旗标关）不馈送投影——UI 块由 GameEngine 转发回全量流",
            0L, legacy.projection.projectionGeneration
        )
    }

    // ── 计时夹具 ────────────────────────────────────────────────

    private class Arm(
        val store: FakeGameStateStore,
        val projection: GameViewStore,
        val decodeNs: Long,
        val applyNs: Long
    ) {
        val totalNs: Long get() = decodeNs + applyNs
    }

    /** 一臂：预置大状态 → 分阶段计时（[REPEATS] 遍取最小，规避抖动）。 */
    private fun measure(bytes: ByteArray, projection: Boolean): Arm {
        val previous = NativeEngineFlag.gameViewProjection
        NativeEngineFlag.gameViewProjection = projection
        try {
            var bestDecode = Long.MAX_VALUE
            var bestApply = Long.MAX_VALUE
            var store: FakeGameStateStore? = null
            var views: GameViewStore? = null
            repeat(REPEATS) {
                val localStore = seededStore()
                val localViews = GameViewStore().also { it.attach(localStore) }
                val service = StateSyncService(localStore, localViews)
                val t0 = System.nanoTime()
                // 消费侧解码段：GameView 解析 +（旧臂）每行 109 键造树 /（新臂）行 typed 投影
                val decoded = GameViewMirrorCodec.decodeView(
                    GameView.parseFrom(bytes),
                    includeDiscipleJson = !projection,
                    discipleJson = lenientJson
                )
                val t1 = System.nanoTime()
                // 应用段：applier 写 store（旧臂含整份 GameData JSON 往返）
                val applied = service.applyDirtyProto(bytes)
                val t2 = System.nanoTime()
                bestDecode = minOf(bestDecode, t1 - t0)
                bestApply = minOf(bestApply, t2 - t1)
                check(applied != null && decoded.changed.isNotEmpty()) { "信封未被应用（守卫空转）" }
                store = localStore
                views = localViews
            }
            return Arm(requireNotNull(store), requireNotNull(views), bestDecode, bestApply)
        } finally {
            NativeEngineFlag.gameViewProjection = previous
        }
    }

    private val lenientJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /** 与真机同量级的 gameData 容器面（旧臂整份 JSON 往返的成本由此决定） */
    private fun seededGameData(): GameData = GameData().apply {
        spiritStones = SPIRIT_STONES
        midGradeSpiritStones = 420L
        highGradeSpiritStones = 12L
        gameYear = 37
        gameMonth = 9
        gamePhase = 2
        sectName = "紫霄剑宗"
        worldMapSects = List(40) { WorldSect(id = "w$it", name = "宗门$it", level = it % 6 + 1) }
        placedBuildings = List(60) {
            GridBuildingData(buildingId = "hut", displayName = "木屋$it", gridX = it, gridY = it * 2)
        }
        gameEventRecords = List(400) {
            GameEventRecord(timestamp = 1_700_000_000_000L + it, eventType = "E$it", summary = "事件$it")
        }
        unlockedRecipes = List(80) { "recipe-$it" }
        unlockedManuals = List(80) { "manual-$it" }
    }

    private fun seededStore(): FakeGameStateStore = FakeGameStateStore().apply {
        gameDataValue = seededGameData()
        disciplesValue = List(discipleCount) { disciple(it) }
    }

    private fun disciple(index: Int): Disciple = Disciple(
        id = (index + 1).toString(),
        name = "弟子$index",
        realm = index % 9,
        realmLayer = index % 3 + 1,
        cultivation = 10.0 + index
    ).apply {
        combat = CombatAttributes(currentHp = 500 + index, currentMp = 300)
    }

    /** 每旬全脏信封：全部行全字段 upsert（与 store 现值仅 cultivation 之差） */
    private fun discipleRows(count: Int): List<DiscipleRow> = List(count) { index ->
        MirrorDiscipleRowFixture.toGameViewRow(disciple(index).apply { cultivation = 11.0 + index })
    }

    private fun envelope(rows: List<DiscipleRow>): GameView = GameView.newBuilder()
        .setVersion(1L)
        .setResourcesHeader(ResourcesHeader.newBuilder().setSpiritStones(SPIRIT_STONES).build())
        .setDiscipleListDelta(DiscipleListDelta.newBuilder().addAllUpserts(rows).build())
        .build()

    private fun ms(ns: Long): String = String.format(java.util.Locale.ROOT, "%.2f", ns / 1_000_000.0)

    private fun pct(faster: Arm, slower: Arm): String = String.format(
        java.util.Locale.ROOT,
        "%.0f%%",
        (1.0 - faster.totalNs.toDouble() / slower.totalNs.toDouble()) * 100.0
    )

    companion object {
        /** 观测规模：与 B06 C++ bench 同族口径（1000 / 5000 弟子） */
        private val SCALES = intArrayOf(1000, 5000)

        private const val REPEATS = 3
        private const val SPIRIT_STONES = 9_876_543L

        /** 当前规模（夹具函数共享，逐规模覆写） */
        private var discipleCount = 5000
    }
}
