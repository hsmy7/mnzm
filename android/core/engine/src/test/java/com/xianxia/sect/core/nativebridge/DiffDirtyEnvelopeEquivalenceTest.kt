package com.xianxia.sect.core.nativebridge

import com.xianxia.sect.core.model.GameData
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * DiffDirtyEnvelopeEquivalenceTest — 镜像通道 protobuf 与旧 JSON 逐值等价守卫
 * （重构方案 R2.2 验收门 4）。
 *
 * 守护目标：
 *   1. **编码面**：同一棵变更集树（{version,changed,removed}）经 C++ encodeGameView
 *      产出 protobuf 信封后，Kotlin [GameViewMirrorCodec] 还原的树与原 JSON 树
 *      逐值等价（弟子 typed 字段 / 集合 raw json / gameData 标量与容器 / removed
 *      id 列表全覆盖）——数字按数值比较（容忍 int/double 表示差异）。
 *   2. **应用面**：真实 native 变更集分别经 [StateSyncService.applyDirty]（JSON）
 *      与 [StateSyncService.applyDirtyProto]（protobuf）应用到两份相同初态镜像，
 *      结果 GameData 与 DirtyApplyResult 完全一致。
 *
 * 平台约束：与 [DiffDirtyTest] 同规约（普通 JUnit、桌面 .so 单 ClassLoader 加载；
 * 弟子集合 SparseArray 在普通 JVM 不可用，故应用面对照聚焦 gameData，编码面
 * 弟子字段由 deep-equal 全覆盖对照锁定）。
 */
class DiffDirtyEnvelopeEquivalenceTest {

    private val json = Json { ignoreUnknownKeys = true }

    // ── 编码面：富变更集树 protobuf 往返逐值等价 ──────────────────

    @Test
    fun `protobuf round-trip reconstructs identical change set tree`() {
        assumeTrue(DiffRngBridge.isAvailable())

        // 覆盖全部行字段类别：string/int32/int64/bool/double/字符串列表/
        // int 映射/字符串映射/nurture 子消息/storageBagItems JSON 原文；
        // 另含非弟子集合 upsert+remove、gameData 标量+嵌套容器、弟子删除。
        val diffJson = """
            {"version":7,
             "changed":{
               "gameData.spiritStones":4321,
               "gameData.gameYear":9,
               "gameData.disabledPolicies":["a","b"],
               "disciples":[{
                 "id":"12","name":"玄真","surname":"李","realm":5,"realmLayer":3,
                 "cultivation":128.5,"cultivationCheckpoint":99,"age":21,"lifespan":200,
                 "isAlive":true,"deathYear":0,"gender":"male","portraitRes":"d12",
                 "manualIds":["m1","m2"],"talentIds":["t1"],"physiqueIds":[],"affixIds":["af1"],
                 "manualMasteries":{"m1":3,"m2":7},"status":"CULTIVATING",
                 "statusData":{"task":"alchemy","slot":"42"},
                 "cultivationSpeedBonus":1.25,"soulPower":17,
                 "baseHp":500,"baseMp":300,"hpVariance":12,"totalCultivation":9999999,
                 "currentHp":-1,"currentMp":-1,"breakthroughCount":2,
                 "pillCritRateBonus":0.05,"pillEffectDuration":3,
                 "activePillTypes":["dan1"],"activePillCategory":"cultivation",
                 "weaponId":"w1","weaponNurture":{"equipmentId":"w1","rarity":2,"nurtureLevel":4,"nurtureProgress":0.25},
                 "storageBagItems":[{"id":"s1","count":9,"effects":[{"id":"e1"}]}],
                 "storageBagSpiritStones":55,"spiritStones":88,
                 "usedPermanentPillKeys":["k1"],"recruitedMonth":120,
                 "hasReviveEffect":false,"hasClearAllEffect":true
               }],
               "pills":[{"id":"p-new","name":"凝气丹","rarity":2,"quantity":3}]
             },
             "removed":{"disciples":["9"],"pills":["p-old"]}}
        """.trimIndent()

        val original = json.parseToJsonElement(diffJson).jsonObject
        val proto = DiffRngBridge.nativeCoreEncodeGameView(diffJson.encodeToByteArray())
        val decoded = GameViewMirrorCodec.decode(proto)

        assertEquals("版本号同源等价", 7L, decoded.version)
        assertJsonEquivalent(
            "changed 树逐值等价",
            original["changed"] as JsonObject,
            decoded.changed,
        )
        assertJsonEquivalent(
            "removed 树逐值等价",
            original["removed"] as JsonObject,
            decoded.removed,
        )
    }

    @Test
    fun `empty envelope decodes to version-only with empty trees`() {
        assumeTrue(DiffRngBridge.isAvailable())
        val proto = DiffRngBridge.nativeCoreEncodeGameView(
            """{"version":0,"changed":{},"removed":{}}""".encodeToByteArray()
        )
        val decoded = GameViewMirrorCodec.decode(proto)
        assertEquals(0L, decoded.version)
        assertTrue(decoded.changed.isEmpty())
        assertTrue(decoded.removed.isEmpty())
    }

    // ── 应用面：真实 native 变更集双解码对照 ──────────────────────

    @Test
    fun `applyDirtyProto matches applyDirty on native change set`() {
        assumeTrue(DiffRngBridge.isAvailable())
        DiffRngBridge.nativeCoreInit()

        val seedJson = json.encodeToString(
            NativeGameState.serializer(),
            NativeGameState(
                gameData = GameData().apply {
                    spiritStones = 500
                    gameYear = 3; gameMonth = 5; gamePhase = 0; sectName = "对拍宗"
                }
            )
        )
        assertTrue("C++ 导入失败", DiffRngBridge.nativeCoreImportState(seedJson.encodeToByteArray()))

        // 推进 → gameData 时间字段变化（触发 resourcesHeader/gameDataChange 通道）
        DiffRngBridge.nativeCoreAdvancePhases(3)
        val dirtyJson = DiffRngBridge.nativeCoreExportDirty().decodeToString()
        val proto = DiffRngBridge.nativeCoreEncodeGameView(dirtyJson.encodeToByteArray())

        val storeA = FakeGameStateStore()
        storeA.gameDataValue = GameData().apply {
            spiritStones = 500; gameYear = 3; gameMonth = 5; gamePhase = 0; sectName = "对拍宗"
        }
        val storeB = FakeGameStateStore()
        storeB.gameDataValue = GameData().apply {
            spiritStones = 500; gameYear = 3; gameMonth = 5; gamePhase = 0; sectName = "对拍宗"
        }

        val viaJson = StateSyncService(storeA).applyDirty(dirtyJson)
        val viaProto = StateSyncService(storeB).applyDirtyProto(proto)

        assertEquals("DirtyApplyResult 逐字段等价", viaJson, viaProto)
        assertEquals(
            "两传输格式应用后的 gameData 必须完全一致",
            storeA.gameDataValue, storeB.gameDataValue,
        )
        // 推进 3 旬跨月回绕（gamePhase 归 0、gameMonth +1）——断言镜像确实推进
        // 且两格式一致（A==B 上方已锁）：月份前移、非空变更集。
        assertTrue("变更集须携带 ≥1 字段变化", (viaJson?.changedFieldCount ?: 0) >= 1)
        assertEquals("镜像月份前移（两格式一致）", 6, storeA.gameDataValue.gameMonth)
    }

    // ── 数字归一 + 空容器规约的 JsonElement 深比较 ────────────────
    //
    // protobuf repeated/map 字段的"空集合"与"缺省键"线路上不可区分（无长度前缀），
    // C++ 编码器对空数组恒不产出、解码侧 hasXxx()=false → 不重建。但域层面
    // Disciple.physiqueIds 缺省即 emptyList——与 JSON 路径 "[]" 解码结果逐值相同。
    // 故比较前递归剥离两侧"空容器"（空数组/空对象），只对照非缺省语义，
    // 数字按数值比较（int/double 表示无关），键集比较顺序无关。

    private fun assertJsonEquivalent(message: String, expected: JsonElement, actual: JsonElement) {
        val a = stripEmptyContainers(expected)
        val b = stripEmptyContainers(actual)
        assertTrue("$message：$a vs $b", jsonEquivalent(a, b))
    }

    private fun stripEmptyContainers(e: JsonElement): JsonElement = when (e) {
        is JsonObject -> JsonObject(e.entries.filterNot { isEmptyContainer(it.value) }
            .associate { (k, v) -> k to stripEmptyContainers(v) })
        is JsonArray -> JsonArray(e.filterNot { isEmptyContainer(it) }.map { stripEmptyContainers(it) })
        else -> e
    }

    private fun isEmptyContainer(e: JsonElement): Boolean =
        (e is JsonArray && e.isEmpty()) || (e is JsonObject && e.isEmpty())

    private fun jsonEquivalent(a: JsonElement, b: JsonElement): Boolean = when {
        a is JsonObject && b is JsonObject ->
            a.keys == b.keys && a.all { (k, v) -> jsonEquivalent(v, b.getValue(k)) }
        a is JsonArray && b is JsonArray ->
            a.size == b.size && a.indices.all { jsonEquivalent(a[it], b[it]) }
        a is JsonPrimitive && b is JsonPrimitive -> primitiveEquivalent(a, b)
        else -> false
    }

    private fun primitiveEquivalent(a: JsonPrimitive, b: JsonPrimitive): Boolean {
        val ad = a.doubleOrNull
        val bd = b.doubleOrNull
        if (ad != null && bd != null) return ad == bd  // 数值：按值比较（int/double 表示无关）
        return a.content == b.content && a.isString == b.isString
    }
}
