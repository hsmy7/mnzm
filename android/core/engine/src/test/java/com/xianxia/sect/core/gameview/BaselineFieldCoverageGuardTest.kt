package com.xianxia.sect.core.gameview

import com.xianxia.sect.core.model.GameData
import kotlinx.serialization.descriptors.elementNames
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * BaselineFieldCoverageGuardTest — P4.1/D5：GameData.serializer 元素名 ↔
 * C++ 基线字段表（json_codec to_json GameData 键）双射。
 *
 * ## 存在理由
 * 状态基线（StateBaseline.gameData_）与全量/列级 diff 的 gameData 段
 * 均由 C++ `to_json(GameData)` 产生。若 Kotlin @Serializable 序列化面
 * 新增字段而 C++ 基线未同步（或反向），镜像会静默丢字段——本守卫
 * 复用 R2.3 fail-fast 模式：缺字段即红并点名。
 *
 * ## 双射边界
 * - **基线侧** = `json_codec.cpp` 中 `to_json(GameData)` 的 GC_TO/writeIntKeyMap/j[""]
 *   键集（与 StateBaseline 捕获面一致）；
 * - **序列化侧** = `GameData.serializer().descriptor.elementNames`
 *   （@Transient 天然不在其中）；
 * - **intentionallyExcluded** = Kotlin 序列化面有、C++ GameData 未建模的字段
 *   （不进基线/镜像 C++ 臂；保留 Kotlin 侧存档兼容）——新增必须登记。
 */
class BaselineFieldCoverageGuardTest {

    /**
     * Kotlin 序列化面有、C++ GameData 模型/to_json 无的字段。
     * 变更本表须同步：C++ models.h + json_codec to/from，或确认 Kotlin-only。
     */
    private val intentionallyExcluded: Set<String> = setOf(
        "exploredSects",       // Kotlin 存档兼容域；C++ GameData 未建模
        "cultivatorCaves",     // 同上
        "heavenlyTrialState",  // 同上
        "signInState",         // 同上
        "pendingPatrolBattleResults", // 同上
        "aiCaveTeams",         // 同上（洞府 AI 队；C++ 走独立顶层域）
        "lastYearSpiritStoneIncome", // 缺陷 #3 退役：C++ models/json_codec 已除名，
                                      // 年贡改读 annualTotalIncome；Kotlin 字段保留仅存档 schema 稳定
    )

    /** Gradle 测试工作目录 = android/core/engine；C++ 源在 ../../app */
    private val cppJsonCodec: File =
        File("../../app/src/main/cpp/gamecore/src/json_codec.cpp")

    private fun cppGameDataBaselineFields(): Set<String> {
        assertTrue("json_codec.cpp 不可读: $cppJsonCodec", cppJsonCodec.isFile)
        val text = cppJsonCodec.readText()
        val fnMatch = Regex(
            """void to_json\(nlohmann::json& j, const GameData& v\)\s*\{(.*?)\n\}""",
            RegexOption.DOT_MATCHES_ALL
        ).find(text)
        assertTrue("json_codec.cpp 未找到 to_json(GameData) 函数体", fnMatch != null)
        val body = fnMatch!!.groupValues[1]
        val fields = mutableSetOf<String>()
        Regex("""GC_TO\(v,\s*j,\s*(\w+)\)""").findAll(body).forEach {
            fields += it.groupValues[1]
        }
        Regex("""writeIntKeyMap\(j,\s*"([^"]+)"""").findAll(body).forEach {
            fields += it.groupValues[1]
        }
        Regex("""j\["([^"]+)"\]\s*=""").findAll(body).forEach {
            fields += it.groupValues[1]
        }
        assertTrue("to_json(GameData) 字段表为空（解析失败？）", fields.isNotEmpty())
        return fields
    }

    @Test
    fun `GameData序列化面与C++基线字段表双射（漏登记即红）`() {
        val serialized = GameData.serializer().descriptor.elementNames.toSet()
        val baseline = cppGameDataBaselineFields()

        val expectedSerialized = baseline + intentionallyExcluded

        assertEquals(
            "基线字段表 ⊈ 序列化面（C++ 有而 Kotlin 序列化无 = 协议漂移）: " +
                (baseline - serialized),
            serialized.intersect(baseline),
            baseline
        )
        assertEquals(
            "序列化面 − 排除表 ⊈ 基线字段表（Kotlin 新字段漏同步 C++ 基线 = 镜像静默丢变更）: " +
                (serialized - intentionallyExcluded - baseline),
            baseline,
            serialized - intentionallyExcluded
        )
        assertEquals(
            "序列化面相对「基线+排除表」漂移（多余/缺失）",
            expectedSerialized,
            serialized
        )
        // 排除表内字段不得再出现在基线（登记即应 C++ 未导出）
        assertEquals(
            "intentionallyExcluded 与 C++ 基线重叠（应从排除表移除或从 to_json 删除）",
            emptySet<String>(),
            intentionallyExcluded.intersect(baseline)
        )
    }
}
