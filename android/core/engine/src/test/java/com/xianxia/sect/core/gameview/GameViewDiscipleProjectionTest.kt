package com.xianxia.sect.core.gameview

import com.xianxia.sect.core.model.Disciple
import com.xianxia.sect.core.nativebridge.DiffRngBridge
import com.xianxia.sect.core.nativebridge.GameViewMirrorCodec
import com.xianxia.sect.core.nativebridge.MirrorDiscipleRowFixture
import com.xianxia.sect.core.nativebridge.MirrorProtoFeedFixture
import com.xianxia.sect.proto.gameview.DiscipleListDelta
import com.xianxia.sect.proto.gameview.DiscipleRow
import com.xianxia.sect.proto.gameview.GameView
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * GameViewDiscipleProjectionTest —— R2.3 第二波「弟子行 typed 直读投影」等价守卫。
 *
 * ## 存在理由（验收门 5：投影完整性证据 · 投影字段 ↔ 旧全量字段逐值对照）
 * 弟子行从 `DiscipleRow` 到 Kotlin 域模型有两条臂：
 * - **第一波形态**（回滚臂）：逐字段重建 109 键 JsonObject → kotlinx 解码为 Disciple；
 * - **第二波投影**（[GameViewDiscipleRows.toDisciple]）：proto typed getter 直读。
 * 两臂必须产出**同一个 Disciple**（data class equals = 全字段含 6 个 @Embedded 段），
 * 否则 UI 弟子列表看到的值随旗标漂移。本守卫用 B07 的富弟子夹具（109 协议字段
 * 逐项非默认值、每个 wire 类别都取到真实值）做三方对照：
 * `源 Disciple == typed 投影 == JSON 树重建`。
 *
 * ## fail-fast 红线用例
 * 稀疏行（只带 id/name/isAlive）在 JSON 臂会"缺键取域默认"，在投影臂必须**抛错**
 * 并点名缺失字段——"投影缺失字段 fail-fast 而非静默空"（方案 §5）。抛出经
 * `applyDirtyFromNative` 的降级契约转为本封增量失败（不污染镜像、不静默显示错值）。
 *
 * ## 契约双射
 * [GameViewDiscipleRows.requiredScalarFields] ↔ 编码器行表的标量面
 * （[GameViewMirrorCodec.rowScalarFieldNames]）逐项双射：C++ 编码器新增标量字段
 * 而未登记进投影契约 ⇒ 本守卫红。真实 C++ 结算信封的逐字段 presence 另经桌面桥
 * 核对（emit-always 契约的实证面；无 `-Dgamecore.jni.path` 时该用例跳过）。
 */
class GameViewDiscipleProjectionTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Test
    fun `typed 投影与 JSON 树重建两臂产出同一 Disciple（109 协议字段全覆盖）`() {
        val source = MirrorProtoFeedFixture.richDisciple()
        val row = MirrorDiscipleRowFixture.toGameViewRow(source)

        val projected = GameViewDiscipleRows.toDisciple(row, json)
        val viaJson = jsonViaTree(row)

        assertEquals("typed 投影与源域模型分歧（含 6 个 @Embedded 段全字段）", source, projected)
        assertEquals("JSON 树重建臂与源域模型分歧（两臂共同基准）", source, viaJson)
        assertEquals("两臂互检分歧（同一行两种消费形状必须同值）", viaJson, projected)
    }

    @Test
    fun `投影必在标量集与编码器行表标量面逐项双射（新增字段漏登记即红）`() {
        val contract = GameViewDiscipleRows.requiredScalarFields.map { it.first }.toSet()
        val encoded = GameViewMirrorCodec.rowScalarFieldNames()
        assertEquals(
            "弟子投影契约与 C++ 编码表标量面漂移 —— 仅契约有：" + (contract - encoded) +
                "；仅编码表有（新增标量字段未登记进投影 = 静默漏投）：" + (encoded - contract),
            encoded,
            contract
        )
    }

    @Test
    fun `稀疏行缺必在字段即 fail-fast（禁止以默认值掩盖）`() {
        // 稀疏行 = 只有三键的旧 JSON 臂形状（生产 C++ 恒 emit-always，此形状即协议漂移）
        val sparse = DiscipleRow.newBuilder().setId("1").setName("稀疏").setIsAlive(true).build()
        val missing = GameViewDiscipleRows.missingRequiredFields(sparse)
        assertTrue("稀疏行应报出缺失标量字段（仅带 id/name/isAlive）：$missing", missing.size > 80)
        val error = assertThrows(IllegalArgumentException::class.java) {
            GameViewDiscipleRows.toDisciple(sparse, json)
        }
        assertTrue(
            "fail-fast 消息须点名缺失字段（可归因，不许静默补默认）",
            error.message!!.contains("realm") && error.message!!.contains("投影缺失必在字段")
        )
        // 对照面：同一行在第一波形态下静默产出默认值域对象（正是红线要拦的形状）
        val lenient = jsonViaTree(sparse)
        assertEquals("JSON 臂静默补默认（回归对照，非期望行为）", 9, lenient.realm)
        assertEquals("JSON 臂静默补默认（回归对照，非期望行为）", 16, lenient.age)
    }

    @Test
    fun `真实 C++ 结算信封逐行满足投影契约（emit-always 实证）`() {
        assumeTrue(DiffRngBridge.isAvailable())
        DiffRngBridge.nativeCoreInit()
        val seed = MirrorProtoFeedFixture.richDisciple()
        val envelope = GameViewMirrorCodec.parse(
            DiffRngBridge.nativeCoreEncodeGameView(
                settleDirtyJson(seed).encodeToByteArray()
            )
        )
        val rows = if (envelope.hasDiscipleListDelta()) envelope.discipleListDelta.upsertsList else emptyList()
        assumeTrue("本封变更集未携带弟子行（结算形状变化，非本守卫失败）", rows.isNotEmpty())
        for (row in rows) {
            assertEquals(
                "真实 C++ 行缺必在标量字段（emit-always 契约漂移）：id=${row.id}",
                emptyList<String>(),
                GameViewDiscipleRows.missingRequiredFields(row)
            )
        }
        // 投影臂与 JSON 臂在真实行上同样逐值同值
        val projected = rows.map { GameViewDiscipleRows.toDisciple(it, json) }
        val viaTree = rows.map { jsonViaTree(it) }
        assertEquals("真实信封两臂分歧", viaTree, projected)
    }

    // ── 夹具 ────────────────────────────────────────────────────

    /** 第一波形态：DiscipleRow → 变更集树（changed["disciples"] JSON 数组）→ kotlinx 解码 */
    private fun jsonViaTree(row: DiscipleRow): Disciple {
        val view = GameView.newBuilder()
            .setVersion(1L)
            .setDiscipleListDelta(DiscipleListDelta.newBuilder().addUpserts(row).build())
            .build()
        val changed = GameViewMirrorCodec.decodeView(view).changed
        val element = changed.getValue("disciples").jsonArray[0].jsonObject
        return json.decodeFromJsonElement(Disciple.serializer(), element)
    }

    /** 真实桌面 C++：导入种子档 → 结算一旬 → 导出变更集 JSON（与生产同源编码面） */
    private fun settleDirtyJson(seed: Disciple): String {
        val state = com.xianxia.sect.core.nativebridge.NativeGameState(
            gameData = com.xianxia.sect.core.model.GameData().apply {
                spiritStones = 5000
                gameYear = 2
                gameMonth = 3
                gamePhase = 0
            },
            disciples = listOf(seed),
        )
        val encoded = json.encodeToString(
            com.xianxia.sect.core.nativebridge.NativeGameState.serializer(), state
        ).encodeToByteArray()
        assertTrue("C++ 导入失败", DiffRngBridge.nativeCoreImportState(encoded))
        DiffRngBridge.nativeCoreSettlePhase()
        return DiffRngBridge.nativeCoreExportDirty().decodeToString()
    }
}
