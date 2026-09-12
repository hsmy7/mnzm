package com.xianxia.sect.core.nativebridge

import com.xianxia.sect.core.registry.AffixDatabase
import com.xianxia.sect.core.registry.PhysiqueDatabase
import com.xianxia.sect.core.registry.TalentDatabase
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * DiffTraitEffectsTest — 天赋/词条/体质三注册表效果聚合跨语言对拍
 * （AUTHORITATIVE 硬前置验收）。
 *
 * 守护目标：C++ disciple_stats.h 三聚合函数（trait_db.h 204 条）对任意
 * id 组合与 Kotlin 权威实现（TalentDatabase.calculateTalentEffects 等）
 * 逐键逐位一致——真实存档天赋弟子的修炼速率/属性在 AUTHORITATIVE 模式下
 * 不漂移的前提。
 *
 * 覆盖：① 全量单条目遍历（109+71+24）；② 同 key 相加合并；③ comprehension
 * 词条 flat 合并分叉回归（t2-1-review:77）；④ 未知 id/空输入边界。
 *
 * 前置：桌面 JNI 已构建并注入 -Dgamecore.jni.path；未注入时跳过。
 */
class DiffTraitEffectsTest {

    private val json = Json { ignoreUnknownKeys = true }

    // ── native 通道封装 ─────────────────────────────────────────────

    private fun traitOp(op: String, idsKey: String, ids: List<String>): JsonObject {
        val params = buildString {
            append("{\"op\":\"").append(op).append("\",\"").append(idsKey).append("\":[")
            ids.forEachIndexed { i, id ->
                if (i > 0) append(',')
                append('"').append(id).append('"')
            }
            append("]}")
        }
        val raw = DiffRngBridge.nativeCoreDiscipleOp(params.encodeToByteArray())
        return json.parseToJsonElement(raw.decodeToString()).jsonObject
    }

    private fun effectsMap(op: String, idsKey: String, ids: List<String>): Map<String, Double> {
        val effects = traitOp(op, idsKey, ids)["effects"] as? JsonObject ?: return emptyMap()
        return effects.mapValues { it.value.jsonPrimitive.double }
    }

    /** Kotlin 侧权威口径：天赋 + 词条同 key 相加（DiscipleStatCalculator.getMergedEffects 语义） */
    private fun kotlinMergedEffects(
        talentIds: List<String>,
        affixIds: List<String>
    ): Map<String, Double> {
        val merged = HashMap<String, Double>()
        for ((k, v) in TalentDatabase.calculateTalentEffects(talentIds)) {
            merged[k] = (merged[k] ?: 0.0) + v
        }
        for ((k, v) in AffixDatabase.calculateAffixEffects(affixIds)) {
            merged[k] = (merged[k] ?: 0.0) + v
        }
        return merged
    }

    // ── 全量单条目遍历 ──────────────────────────────────────────────

    @Test
    fun `talent aggregation matches Kotlin for all entries`() {
        assumeTrue(DiffRngBridge.isAvailable())
        DiffRngBridge.nativeCoreInit()
        // 完整映射（含退役类型：旧存档仍携带其效果，AUTHORITATIVE 下同样走 C++）
        val allIds = TalentDatabase.talents.keys.toList()
        assertTrue("天赋条目数异常: ${allIds.size}", allIds.size >= TALENT_ENTRY_COUNT_FLOOR)
        for (id in allIds) {
            val expected = TalentDatabase.calculateTalentEffects(listOf(id))
            assertEquals(expected, effectsMap("talentEffects", "talentIds", listOf(id)))
        }
    }

    @Test
    fun `affix aggregation matches Kotlin for all entries`() {
        assumeTrue(DiffRngBridge.isAvailable())
        DiffRngBridge.nativeCoreInit()
        val allIds = AffixDatabase.affixes.keys.toList()
        assertTrue("词条条目数异常: ${allIds.size}", allIds.size >= AFFIX_ENTRY_COUNT_FLOOR)
        for (id in allIds) {
            val expected = AffixDatabase.calculateAffixEffects(listOf(id))
            assertEquals(expected, effectsMap("affixEffects", "affixIds", listOf(id)))
        }
    }

    @Test
    fun `physique aggregation matches Kotlin for all entries`() {
        assumeTrue(DiffRngBridge.isAvailable())
        DiffRngBridge.nativeCoreInit()
        val allIds = PhysiqueDatabase.physiques.keys.toList()
        assertTrue("体质条目数异常: ${allIds.size}", allIds.size >= PHYSIQUE_ENTRY_COUNT_FLOOR)
        for (id in allIds) {
            val e = PhysiqueDatabase.aggregatePhysiqueEffects(listOf(id))
            val actual = traitOp("physiqueEffects", "physiqueIds", listOf(id))
            assertEquals(e.cultivationSpeedBonus, actual["cultivationSpeedBonus"]!!.jsonPrimitive.double, 0.0)
            assertEquals(e.damageAmplification, actual["damageAmplification"]!!.jsonPrimitive.double, 0.0)
            assertEquals(e.damageReduction, actual["damageReduction"]!!.jsonPrimitive.double, 0.0)
            assertEquals(e.critDamageBonus, actual["critDamageBonus"]!!.jsonPrimitive.double, 0.0)
            assertEquals(e.defenseBonus, actual["defenseBonus"]!!.jsonPrimitive.double, 0.0)
        }
    }

    // ── 合并语义与分叉回归 ──────────────────────────────────────────

    @Test
    fun `merged talent plus affix effects match Kotlin union semantics`() {
        assumeTrue(DiffRngBridge.isAvailable())
        DiffRngBridge.nativeCoreInit()
        // 含同名 key（comprehensionFlat）与独有 key 的组合
        val talentIds = listOf("r1_bat_hp", "r3_base_comp")
        val affixIds = listOf("r1_aff_bat_atk", "r2_aff_base_comp")
        val expected = kotlinMergedEffects(talentIds, affixIds)
        val params = "{\"op\":\"mergedTraitEffects\",\"talentIds\":[" +
            talentIds.joinToString(",") { "\"$it\"" } +
            "],\"affixIds\":[" +
            affixIds.joinToString(",") { "\"$it\"" } + "]}"
        val raw = DiffRngBridge.nativeCoreDiscipleOp(params.encodeToByteArray())
        val actual = (json.parseToJsonElement(raw.decodeToString()).jsonObject["effects"] as JsonObject)
            .mapValues { it.value.jsonPrimitive.double }
        assertTrue(actual.isNotEmpty())
        assertEquals(expected.keys, actual.keys)
        for ((k, v) in expected) {
            assertEquals(v, actual.getValue(k), 0.0)
        }
    }

    @Test
    fun `unknown ids skipped and empty input empty output`() {
        assumeTrue(DiffRngBridge.isAvailable())
        DiffRngBridge.nativeCoreInit()
        assertEquals(emptyMap<String, Double>(), effectsMap("talentEffects", "talentIds", emptyList()))
        assertEquals(emptyMap<String, Double>(), effectsMap("talentEffects", "talentIds", listOf("no_such_id")))
        assertEquals(emptyMap<String, Double>(), effectsMap("affixEffects", "affixIds", listOf("no_such_id")))
    }

    private companion object {
        /** 注册表条目数下限（守卫测试已证 204；此处防枚举 API 静默变空） */
        const val TALENT_ENTRY_COUNT_FLOOR = 100
        const val AFFIX_ENTRY_COUNT_FLOOR = 60
        const val PHYSIQUE_ENTRY_COUNT_FLOOR = 20
    }
}
