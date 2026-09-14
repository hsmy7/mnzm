package com.xianxia.sect.core.nativebridge

import com.xianxia.sect.core.model.GameData
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * DiffEconomyTest — 经济系统跨语言差分对拍。
 *
 * 守护目标：C++ SpiritStoneWallet（add/deduct/batch + autoSell）与 Kotlin
 * SpiritStoneWallet 语义**逐位一致**——余额、品阶、年度报告累积、溢出行为。
 *
 * Kotlin 基准：内联复刻 SpiritStoneWallet 纯逻辑（与 Kotlin 源码逐行一致，
 * 同 DiffTimeTest 复刻 TimeSystem 的模式）。
 *
 * 流程：同一操作序列 JSON 分别在 Kotlin 复刻与 C++（经 JNI execOps）上执行，
 * 比较最终 GameData 快照逐字段一致。
 *
 * 前置：桌面 JNI 已构建并注入 `-Dgamecore.jni.path`；未注入时跳过。
 */
class DiffEconomyTest {

    private val json = Json { encodeDefaults = true }

    // ── Kotlin 基准：SpiritStoneWallet 纯逻辑复刻 ─────────────────────

    private fun spiritStoneCount(gd: GameData, grade: String): Long = when (grade) {
        "MID" -> gd.midGradeSpiritStones
        "HIGH" -> gd.highGradeSpiritStones
        else -> gd.spiritStones
    }

    private fun updateGrade(gd: GameData, grade: String, newAmount: Long): GameData =
        when (grade) {
            "MID" -> gd.copy(midGradeSpiritStones = newAmount)
            "HIGH" -> gd.copy(highGradeSpiritStones = newAmount)
            else -> gd.copy(spiritStones = newAmount)
        }

    private fun safeMul(a: Long, b: Long): Long {
        val result = a * b
        return if (a != 0L && result / a != b) Long.MAX_VALUE else result
    }

    private val effectiveRatio = 8000L

    private fun toLowGrade(quantity: Long, grade: String): Long {
        if (quantity <= 0) return 0L
        return when (grade) {
            "MID" -> safeMul(quantity, effectiveRatio)
            "HIGH" -> safeMul(quantity, safeMul(effectiveRatio, effectiveRatio))
            else -> quantity
        }
    }

    /** 计算自动售卖计划（复刻 SpiritStoneWallet.calculateAutoSell） */
    private data class AutoSellPlan(val sellMidCount: Long, val sellHighCount: Long, val gainedLow: Long)

    private fun calculateAutoSell(gd: GameData, shortfall: Long): AutoSellPlan? {
        var remaining = shortfall
        var sellMidCount = 0L
        var sellHighCount = 0L
        var gainedLow = 0L
        if (gd.autoSellMidGradeForPurchase && remaining > 0 && gd.midGradeSpiritStones > 0) {
            sellMidCount = ((remaining + effectiveRatio - 1) / effectiveRatio)
                .coerceAtMost(gd.midGradeSpiritStones)
            if (sellMidCount > 0) {
                gainedLow += toLowGrade(sellMidCount, "MID")
                remaining = (remaining - toLowGrade(sellMidCount, "MID")).coerceAtLeast(0L)
            }
        }
        if (gd.autoSellHighGradeForPurchase && remaining > 0 && gd.highGradeSpiritStones > 0) {
            val highRatio = effectiveRatio * effectiveRatio
            sellHighCount = ((remaining + highRatio - 1) / highRatio)
                .coerceAtMost(gd.highGradeSpiritStones)
            if (sellHighCount > 0) {
                gainedLow += toLowGrade(sellHighCount, "HIGH")
            }
        }
        if (gainedLow <= 0) return null
        return AutoSellPlan(sellMidCount, sellHighCount, gainedLow)
    }

    private fun recordAnnual(gd: GameData, delta: Long, reason: String, source: String): GameData =
        if (delta > 0) {
            gd.copy(
                annualIncomeBySource = gd.annualIncomeBySource +
                    (source to (gd.annualIncomeBySource[source] ?: 0L) + delta),
                annualTotalIncome = gd.annualTotalIncome + delta
            )
        } else if (delta < 0) {
            val absD = -delta
            gd.copy(
                annualExpenditureByReason = gd.annualExpenditureByReason +
                    (reason to (gd.annualExpenditureByReason[reason] ?: 0L) + absD),
                annualTotalExpenditure = gd.annualTotalExpenditure + absD
            )
        } else gd

    private fun autoSellHigherGrades(gd: GameData, plan: AutoSellPlan): GameData {
        var out = gd
        if (plan.sellMidCount > 0) {
            val gained = toLowGrade(plan.sellMidCount, "MID")
            out = out.copy(
                midGradeSpiritStones = out.midGradeSpiritStones - plan.sellMidCount,
                spiritStones = out.spiritStones + gained
            )
            out = recordAnnual(out, -plan.sellMidCount, "AutoSell", "Internal")
            out = recordAnnual(out, gained, "AutoSell", "Internal")
        }
        if (plan.sellHighCount > 0) {
            val gained = toLowGrade(plan.sellHighCount, "HIGH")
            out = out.copy(
                highGradeSpiritStones = out.highGradeSpiritStones - plan.sellHighCount,
                spiritStones = out.spiritStones + gained
            )
            out = recordAnnual(out, -plan.sellHighCount, "AutoSell", "Internal")
            out = recordAnnual(out, gained, "AutoSell", "Internal")
        }
        return out
    }

    @Suppress(
        "LongMethod", "CyclomaticComplexMethod", "NestedBlockDepth", "ReturnCount",
        "LoopWithTooManyJumpStatements",  // 复刻 Kotlin batch 预检查/执行的 continue 短路语义
    )
    private fun kotlinWalletOp(gd: GameData, op: JsonObject): GameData {
        val opName = (op["op"] as JsonPrimitive).content
        return when (opName) {
            "walletAdd" -> {
                val amount = (op["amount"] as JsonPrimitive).content.toLong()
                val grade = (op["grade"] as? JsonPrimitive)?.content ?: "LOW"
                val source = (op["source"] as? JsonPrimitive)?.content ?: "Internal"
                if (amount <= 0) return gd
                val current = spiritStoneCount(gd, grade)
                val newAmount = if (current > Long.MAX_VALUE - amount) Long.MAX_VALUE else current + amount
                var out = updateGrade(gd, grade, newAmount)
                out = recordAnnual(out, newAmount - current, "Internal", source)
                out
            }
            "walletDeduct" -> {
                val amount = (op["amount"] as JsonPrimitive).content.toLong()
                val grade = (op["grade"] as? JsonPrimitive)?.content ?: "LOW"
                val reason = (op["reason"] as? JsonPrimitive)?.content ?: "Internal"
                val source = (op["source"] as? JsonPrimitive)?.content ?: "Internal"
                val autoConvert = (op["autoConvert"] as? JsonPrimitive)?.content?.toBoolean() ?: true
                if (amount <= 0) return gd
                var out = gd
                val current = spiritStoneCount(out, grade)
                if (grade == "LOW" && autoConvert && current < amount) {
                    val plan = calculateAutoSell(out, amount - current)
                    if (plan != null && out.spiritStones + plan.gainedLow >= amount) {
                        out = autoSellHigherGrades(out, plan)
                    } else return gd
                } else if (current < amount) return gd
                val balanceBefore = spiritStoneCount(out, grade)
                val newAmount = (balanceBefore - amount).coerceAtLeast(0L)
                out = updateGrade(out, grade, newAmount)
                out = recordAnnual(out, -amount, reason, source)
                out
            }
            "walletBatch" -> {
                val autoConvert = (op["autoConvert"] as? JsonPrimitive)?.content?.toBoolean() ?: false
                val ops = op["ops"] as JsonArray
                var out = gd
                // 预检查（与 Kotlin batch 相同：先保存快照，失败回滚）
                val preSnapshot = out
                var hasAutoSold = false
                for (o in ops) {
                    val elem = o as JsonObject
                    val delta = (elem["delta"] as JsonPrimitive).content.toLong()
                    if (delta >= 0) continue
                    val absAmount = -delta
                    val grade = (elem["grade"] as? JsonPrimitive)?.content ?: "LOW"
                    val curr = spiritStoneCount(out, grade)
                    if (curr < absAmount) {
                        if (!autoConvert || grade != "LOW") return preSnapshot
                        val plan = calculateAutoSell(out, absAmount - curr)
                        if (plan == null || out.spiritStones + plan.gainedLow < absAmount) return preSnapshot
                        out = autoSellHigherGrades(out, plan)
                        hasAutoSold = true
                    }
                }
                @Suppress("UNUSED_VARIABLE")
                val unused = hasAutoSold
                for (o in ops) {
                    val elem = o as JsonObject
                    val delta = (elem["delta"] as JsonPrimitive).content.toLong()
                    val grade = (elem["grade"] as? JsonPrimitive)?.content ?: "LOW"
                    val reason = (elem["reason"] as? JsonPrimitive)?.content ?: "Internal"
                    val source = (elem["source"] as? JsonPrimitive)?.content ?: "Internal"
                    if (delta >= 0) {
                        val current = spiritStoneCount(out, grade)
                        val newAmount = if (current > Long.MAX_VALUE - delta) Long.MAX_VALUE else current + delta
                        out = updateGrade(out, grade, newAmount)
                        out = recordAnnual(out, delta, reason, source)
                    } else {
                        if (delta == Long.MIN_VALUE) continue
                        val absAmount = -delta
                        val current = spiritStoneCount(out, grade)
                        if (current < absAmount) continue
                        val newAmount = (current - absAmount).coerceAtLeast(0L)
                        out = updateGrade(out, grade, newAmount)
                        out = recordAnnual(out, delta, reason, source)
                    }
                }
                out
            }
            else -> gd
        }
    }

    // ── 对拍执行 ─────────────────────────────────────────────────────

    private fun opsArray(ops: List<JsonObject>): JsonArray = buildJsonArray { ops.forEach { add(it) } }

    /** 运行同一操作序列于 Kotlin 复刻与 C++，比较最终 GameData 快照 */
    private fun assertEconomyParity(initial: GameData, ops: List<JsonObject>) {
        // Kotlin 基准
        var kotlinGd = initial
        for (op in ops) kotlinGd = kotlinWalletOp(kotlinGd, op)

        // C++ 侧：导入初始快照 → 执行操作 → 导出
        val sample = NativeGameState(gameData = initial)
        val encoded = json.encodeToString(NativeGameState.serializer(), sample)
        assertTrue("C++ 导入失败", DiffRngBridge.nativeCoreImportState(encoded.encodeToByteArray()))
        val resultJson = DiffRngBridge.nativeCoreExecOps(
            json.encodeToString(JsonArray.serializer(), opsArray(ops)).encodeToByteArray()
        ).decodeToString()
        // 操作结果应无错误
        assertTrue("C++ 执行出错: $resultJson", !resultJson.contains("\"error\""))
        val decoded = json.decodeFromString(
            NativeGameState.serializer(), DiffRngBridge.nativeCoreExportState().decodeToString()
        ).gameData

        // 逐字段比较经济相关快照
        assertEquals("spiritStones 不一致", kotlinGd.spiritStones, decoded.spiritStones)
        assertEquals("midGradeSpiritStones 不一致", kotlinGd.midGradeSpiritStones, decoded.midGradeSpiritStones)
        assertEquals("highGradeSpiritStones 不一致", kotlinGd.highGradeSpiritStones, decoded.highGradeSpiritStones)
        assertEquals("annualTotalIncome 不一致", kotlinGd.annualTotalIncome, decoded.annualTotalIncome)
        assertEquals("annualTotalExpenditure 不一致", kotlinGd.annualTotalExpenditure, decoded.annualTotalExpenditure)
        assertEquals("annualIncomeBySource 不一致", kotlinGd.annualIncomeBySource, decoded.annualIncomeBySource)
        assertEquals(
            "annualExpenditureByReason 不一致",
            kotlinGd.annualExpenditureByReason, decoded.annualExpenditureByReason
        )
        assertEquals("autoSell 开关漂移", kotlinGd.autoSellMidGradeForPurchase, decoded.autoSellMidGradeForPurchase)
        assertEquals("autoSell 开关漂移", kotlinGd.autoSellHighGradeForPurchase, decoded.autoSellHighGradeForPurchase)
    }

    private fun addOp(amount: Long, grade: String, source: String = "Battle") = buildJsonObject {
        put("op", "walletAdd"); put("amount", amount); put("grade", grade); put("source", source)
    }

    private fun deductOp(
        amount: Long, grade: String = "LOW", reason: String = "Purchase",
        source: String = "Internal", autoConvert: Boolean = true,
    ) = buildJsonObject {
        put("op", "walletDeduct"); put("amount", amount); put("grade", grade)
        put("reason", reason); put("source", source); put("autoConvert", autoConvert)
    }

    private fun batchOp(deltas: List<Long>, grades: List<String>, autoConvert: Boolean = false) =
        buildJsonObject {
            put("op", "walletBatch")
            put("autoConvert", autoConvert)
            put(
                "ops",
                buildJsonArray {
                    deltas.forEachIndexed { i, d ->
                        add(
                            buildJsonObject {
                                put("delta", d)
                                put("grade", grades.getOrElse(i) { "LOW" })
                                put("reason", if (d < 0) "Purchase" else "Quest")
                                put("source", if (d < 0) "Purchase" else "Quest")
                            }
                        )
                    }
                }
            )
        }

    // ── 测试用例 ─────────────────────────────────────────────────────

    @Test
    fun `wallet add matches Kotlin`() {
        assumeTrue(DiffRngBridge.isAvailable())
        DiffRngBridge.nativeCoreInit()
        assertEconomyParity(
            GameData().apply { spiritStones = 1000 },
            listOf(addOp(500, "LOW"), addOp(0, "LOW"), addOp(200, "MID"), addOp(-10, "HIGH"))
        )
    }

    @Test
    fun `wallet deduct matches Kotlin`() {
        assumeTrue(DiffRngBridge.isAvailable())
        DiffRngBridge.nativeCoreInit()
        assertEconomyParity(
            GameData().apply { spiritStones = 1000 },
            listOf(
                deductOp(300, "LOW"),
                deductOp(0, "LOW"),
                deductOp(5000, "LOW", autoConvert = false),  // 不足 → 不变
            )
        )
    }

    @Test
    fun `wallet deduct autoConvert mid matches Kotlin`() {
        assumeTrue(DiffRngBridge.isAvailable())
        DiffRngBridge.nativeCoreInit()
        assertEconomyParity(
            GameData().apply {
                spiritStones = 100
                midGradeSpiritStones = 2
                autoSellMidGradeForPurchase = true
            },
            listOf(deductOp(500, "LOW"))
        )
    }

    @Test
    fun `wallet deduct autoConvert high matches Kotlin`() {
        assumeTrue(DiffRngBridge.isAvailable())
        DiffRngBridge.nativeCoreInit()
        assertEconomyParity(
            GameData().apply {
                spiritStones = 100
                highGradeSpiritStones = 1
                autoSellHighGradeForPurchase = true
            },
            listOf(deductOp(500, "LOW"))
        )
    }

    @Test
    fun `wallet batch matches Kotlin`() {
        assumeTrue(DiffRngBridge.isAvailable())
        DiffRngBridge.nativeCoreInit()
        assertEconomyParity(
            GameData().apply {
                spiritStones = 1000
                midGradeSpiritStones = 10
            },
            listOf(batchOp(listOf(500L, -200L, 3L), listOf("LOW", "LOW", "MID")))
        )
    }

    @Test
    fun `wallet batch rollback on precheck failure matches Kotlin`() {
        assumeTrue(DiffRngBridge.isAvailable())
        DiffRngBridge.nativeCoreInit()
        assertEconomyParity(
            GameData().apply { spiritStones = 1000 },
            listOf(batchOp(listOf(500L, -5000L), listOf("LOW", "LOW")))
        )
    }

    @Test
    fun `wallet batch autoConvert matches Kotlin`() {
        assumeTrue(DiffRngBridge.isAvailable())
        DiffRngBridge.nativeCoreInit()
        assertEconomyParity(
            GameData().apply {
                spiritStones = 100
                midGradeSpiritStones = 2
                autoSellMidGradeForPurchase = true
            },
            listOf(batchOp(listOf(-500L), listOf("LOW"), autoConvert = true))
        )
    }

    @Test
    fun `overflow add clamps to max matches Kotlin`() {
        assumeTrue(DiffRngBridge.isAvailable())
        DiffRngBridge.nativeCoreInit()
        assertEconomyParity(
            GameData().apply { spiritStones = Long.MAX_VALUE - 10 },
            listOf(addOp(100, "LOW"))
        )
    }
}
