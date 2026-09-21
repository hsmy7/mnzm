package com.xianxia.sect.core.nativebridge

import com.google.protobuf.ByteString
import com.xianxia.sect.core.gameview.toJsonElement
import com.xianxia.sect.proto.gameview.CollectionChange
import com.xianxia.sect.proto.gameview.GameView
import com.xianxia.sect.proto.gameview.JsonFieldChange
import com.xianxia.sect.proto.gameview.TypedField
import com.xianxia.sect.proto.gameview.TypedRow
import com.xianxia.sect.proto.gameview.TypedValue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * MirrorTypedEnvelopeEquivalenceTest — B18-P1「upsertsJson 族 typed 化」的双路
 * 解码等价守卫（实施文档 §1.5 第 2 层，挂 MirrorProtoFeedEquivalenceTest 族）。
 *
 * 同一事实源（下方 [FACT_JSON] 变更集树）构造**旧格式信封**（直设 bytes 字段）
 * 与**新格式信封**（typed 行），`GameViewMirrorCodec` 解码产物必须**逐字段相等**
 * ——含 9 集合全类型叶值（string/int/double/bool/数组/对象/空数组/嵌套递归）、
 * gameData 标量与嵌套容器（含空数组/空对象判别）。
 *
 * ## 三臂分工
 * - **旧 bytes 臂**（恒跑）：javalite 直设 `upsertsJson`/`valueJson`——即 B18-P1
 *   前的生产编码形状，同时证明 fallback 路径未被删断言（对照面保留）；
 * - **JVM typed 臂**（恒跑）：javalite 按 TypedValue/TypedRow 构建——无桥环境
 *   （Robolectric 沙箱、CI 无 .so）的等价证明；
 * - **桌面 C++ 真编码臂**（`DiffRngBridge` 可用时跑，组合门内恒激活）：同一棵树
 *   经真 `encodeGameView` 编码后解码，与旧 bytes 臂逐字段相等，并断言 C++ 已
 *   **停写** bytes 字段（号冻结的实证面）。
 *
 * ## 数字格式红线（实施文档 §1.6）
 * `TypedValue` 重建的 `JsonPrimitive` content 与旧 JSON 文本**逐字符串相等**
 * 由本守卫仲裁：int64 恒等；double 依赖「生产树已过 normalizeIntegralFloats
 * （整值浮点转 int64）+ 人间尺度非整值浮点的 Java 最短表示与 nlohmann dump
 * 文本一致」。极端量级浮点若出现 content 表示差异（数值恒等、域解码零影响），
 * 本守卫红 → 按方案口径改 Kotlin 侧 content 直构。
 */
class MirrorTypedEnvelopeEquivalenceTest {

    /** 事实源：9 集合 + gameData 标量/容器/空容器/嵌套递归（数字均为生产规范化形态）。 */
    private val factJson = """
        {"version":7,
         "changed":{
           "pills":[{"id":"p1","name":"凝气丹","rarity":3,"quantity":7,"stackable":true,
             "code":"1001","flag":"true"}],
           "materials":[{"id":"m1","name":"铁矿石","purity":0.85,"tags":["metal","ore"]}],
           "seeds":[{"id":"sd1","strain":"灵稻","growth":0.5,"traits":[],"planted":false}],
           "storageBags":[{"id":"bag1","capacity":24,"spiritStones":120,"sections":{"pills":3,"herbs":0}}],
           "equipmentStacks":[{"id":"eq1","templateId":"sword-a","count":2,"enchants":["fire","ice"]}],
           "equipmentInstances":[{"id":"ei1","templateId":"sword-a",
             "nurture":{"level":4,"progress":0.25,"bonded":true},
             "history":[{"year":3,"event":"forge"}]}],
           "manualStacks":[{"id":"ms1","manualId":"man-a","count":1}],
           "manualInstances":[{"id":"mi1","manualId":"man-a",
             "mastery":{"layer":2,"efficiency":1.25},"notes":[]}],
           "gameData.gameYear":9,
           "gameData.sectName":"对拍宗",
           "gameData.sectCultivation":128.5,
           "gameData.unlockedManuals":["man-a","man-b"],
           "gameData.disabledPolicies":[],
           "gameData.sectDetails":{},
           "gameData.worldMapSects":[{"id":"ws1","name":"青云宗","attitude":0.5}],
           "gameData.yearlySalary":{"1":100,"2":200}
         },
         "removed":{"herbs":["h-1"],"pills":["p0"]}}
    """.trimIndent()

    private val json = Json { ignoreUnknownKeys = true }

    // ── 臂 1 vs 臂 2：JVM 恒跑的双路等价 ──────────────────────────

    @Test
    fun `typed 信封与旧 bytes 信封解码逐字段相等（9 集合 + gameData 全叶型）`() {
        val legacy = GameViewMirrorCodec.decode(envelope(typed = false))
        val typed = GameViewMirrorCodec.decode(envelope(typed = true))

        assertEquals("version 同源", legacy.version, typed.version)
        assertEquals("changed 树逐字段相等（含 content 字符串）", legacy.changed, typed.changed)
        assertEquals("removed 树逐字段相等", legacy.removed, typed.removed)

        // 防"两臂同错"：键集与关键值必须落在预期形状上
        assertEquals(16, typed.changed.size)
        assertEquals(2, typed.removed.size)
        val pills = typed.changed.getValue("pills").jsonArray
        assertEquals("p1", pills[0].jsonObject.getValue("id").jsonPrimitive.content)
        assertEquals(
            "数字形字符串必须保持字符串型（分派序：字符串分支先于数值/布尔）",
            "\"1001\"",
            pills[0].jsonObject.getValue("code").toString(),
        )
        assertEquals(
            "布尔形字符串必须保持字符串型（同上）",
            "\"true\"",
            pills[0].jsonObject.getValue("flag").toString(),
        )
        assertEquals(
            "空数组判别位必须重建出 []（而非缺键或 {}）",
            "[]",
            typed.changed.getValue("gameData.disabledPolicies").toString(),
        )
        assertEquals(
            "零字节 TypedValue 重建为空对象",
            "{}",
            typed.changed.getValue("gameData.sectDetails").toString(),
        )
        assertTrue(
            "嵌套空数组同样经判别位重建",
            typed.changed.getValue("manualInstances").jsonArray[0]
                .jsonObject.getValue("notes").toString() == "[]",
        )
    }

    // ── 臂 3：桌面 C++ 真编码（组合门内激活；无 .so 时跳过非本守卫失败）──

    @Test
    fun `C++ 真编码信封与旧 bytes 信封逐字段相等且 bytes 字段已停写`() {
        assumeTrue(DiffRngBridge.isAvailable())
        val cpp = GameViewMirrorCodec.parse(DiffRngBridge.nativeCoreEncodeGameView(factJson.encodeToByteArray()))
        // ⚠️ 形态必须显式传参（勿靠缺省）：`discipleRowsAsPatches` 缺省 true 时
        // 补丁形态优先级最高，`includeDiscipleJson` 会被静默短路——对照臂即失效
        // （B18-臂β 教训：`decodeDiscipleDelta` 的 when 分支序 = 补丁 > JSON 树 >
        // typed 全行）。此处与 legacy 臂 `decode()` 的显式形态（JSON 树）对齐。
        val decoded = GameViewMirrorCodec.decodeView(
            cpp,
            includeDiscipleJson = true,
            discipleRowsAsPatches = false,
        )
        val legacy = GameViewMirrorCodec.decode(envelope(typed = false))

        assertEquals("C++ typed 编码与旧 JSON 文本解码逐字段相等", legacy.changed, decoded.changed)
        assertEquals(legacy.removed, decoded.removed)
        assertEquals("版本同源", 7L, decoded.version)

        // 停写实证：C++ 编码器不再产出 field 2（upsertsJson/valueJson，号冻结）
        assertTrue("collectionChange 全部走 typed", cpp.collectionChangeList.all { it.upsertsJson.isEmpty })
        assertTrue("gameDataChange 全部走 typed", cpp.gameDataChangeList.all { it.valueJson.isEmpty })
    }

    // ── 数字格式红线（§1.6）：content 逐字符串相等 ─────────────────

    @Test
    fun `TypedValue 重建数字 content 与旧 JSON 文本逐字符串相等`() {
        val samples = listOf(
            "0", "-7", "9223372036854775807", "-9223372036854775808",
            "0.05", "-1.25", "128.5", "3.14159", "0.001",
        )
        for (text in samples) {
            val rebuilt = jsonToTypedValue(json.parseToJsonElement(text)).toJsonElement()
            assertTrue("$text 须重建为数值型 JsonPrimitive", rebuilt is JsonPrimitive && !rebuilt.isString)
            assertEquals("content 须与原文本逐字符串相等", text, rebuilt.jsonPrimitive.content)
        }
        // null（防御面）：生产载荷无 null 生产者，但 typed 承载必须不失真
        assertEquals(JsonNull, jsonToTypedValue(JsonNull).toJsonElement())
    }

    // ── 信封构造（两臂同一事实源派生，杜绝手写漂移）────────────────

    /** 事实源 → GameView 信封。[typed]=true 走 TypedValue/TypedRow，false 直设 bytes。 */
    private fun envelope(typed: Boolean): ByteArray {
        val root = json.parseToJsonElement(factJson).jsonObject
        val changed = root.getValue("changed").jsonObject
        val removed = root.getValue("removed").jsonObject

        val upserts = LinkedHashMap<String, JsonArray>()
        val removedIds = LinkedHashMap<String, JsonArray>()
        val gameDataFields = LinkedHashMap<String, JsonElement>()
        for ((key, value) in changed) {
            if (key.startsWith("gameData.")) gameDataFields[key.removePrefix("gameData.")] = value
            else upserts[key] = value.jsonArray
        }
        for ((key, value) in removed) {
            if (!key.startsWith("gameData.")) removedIds[key] = value.jsonArray
        }

        val builder = GameView.newBuilder().setVersion(7)
        for (name in (upserts.keys + removedIds.keys)) {
            builder.addCollectionChange(
                collectionChange(name, upserts[name], removedIds[name], typed)
            )
        }
        for ((name, value) in gameDataFields) {
            builder.addGameDataChange(gameDataField(name, value, typed))
        }
        return builder.build().toByteArray()
    }

    /** 单集合条目：removedIds 恒设；upsert 载荷按臂走 typed 行或旧 bytes 原文。 */
    private fun collectionChange(
        name: String,
        upserts: JsonArray?,
        removedIds: JsonArray?,
        typed: Boolean,
    ): CollectionChange {
        val cb = CollectionChange.newBuilder().setName(name)
        removedIds?.forEach { cb.addRemovedIds(it.jsonPrimitive.content) }
        if (upserts == null) return cb.build()
        if (typed) upserts.forEach { cb.addUpsertsTyped(jsonToTypedRow(it.jsonObject)) }
        else cb.upsertsJson = ByteString.copyFromUtf8(upserts.toString())
        return cb.build()
    }

    /** 单 gameData 字段条目：typed 值承载或旧 JSON 原文。 */
    private fun gameDataField(name: String, value: JsonElement, typed: Boolean): JsonFieldChange {
        val jb = JsonFieldChange.newBuilder().setName(name)
        if (typed) jb.valueTyped = jsonToTypedValue(value)
        else jb.valueJson = ByteString.copyFromUtf8(value.toString())
        return jb.build()
    }

    /** 事实源 JSON → TypedValue（JVM typed 臂构建器；分派口径与 C++ 编码器同源）。 */
    private fun jsonToTypedValue(e: JsonElement): TypedValue {
        val b = TypedValue.newBuilder()
        when (e) {
            is JsonNull -> b.setVNull(true)
            is JsonObject -> e.forEach { (k, v) ->
                b.addVObject(TypedField.newBuilder().setKey(k).setValue(jsonToTypedValue(v)))
            }
            is JsonArray ->
                if (e.isEmpty()) b.setVEmptyArray(true) else e.forEach { b.addVArray(jsonToTypedValue(it)) }
            is JsonPrimitive -> when {
                // ⚠️ 字符串分支必须排在数值/布尔之前——与 C++ 编码器的
                // `v.is_string()` 同序。否则 "123"/"true" 这类**数字形字符串**
                // 会被本臂误分类成 vInt/vBool，而生产 C++ 侧仍走 vString ⇒
                // 两臂在"字符串恰好是数字形"时静默分叉（守卫假绿）。
                e.isString -> b.setVString(e.content)
                e.booleanOrNull != null -> b.setVBool(e.booleanOrNull!!)
                e.longOrNull != null -> b.setVInt(e.longOrNull!!)
                else -> b.setVDouble(requireNotNull(e.doubleOrNull) { "非数值字面量：$e" })
            }
        }
        return b.build()
    }

    private fun jsonToTypedRow(o: JsonObject): TypedRow = TypedRow.newBuilder().apply {
        o.forEach { (k, v) -> addFields(TypedField.newBuilder().setKey(k).setValue(jsonToTypedValue(v))) }
    }.build()
}
