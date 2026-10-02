package com.xianxia.sect.core.nativebridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * 静态数据 codegen 完整性守卫（D9/D10，方案 §6.1）。
 *
 * 单源链条：中性源 `scripts/data/equipment_db_sample.json` →
 * `scripts/gen-templates.mjs` → C++ 四表（equipment_db/equip_set_db/
 * equip_main_stat_db/equip_affix_db）+ 测试快照。本守卫钉死：
 * 1. 生成器输出面恰好七件（装备四表 + herb_db + 两份快照 JSON）——
 *    **不得复活 recipe_db.h / data_json.h 死输出**（B1 教训勘误）；
 * 2. 生成物幂等面在位（`Mutable()` 重建工厂 ×4 表、`operator==` 相等算子）；
 * 3. 四表有真实消费面（系统头 include，无死符号）；
 * 4. 中性源结构 = 五段复合结构，部件数自洽。
 *
 * 数值一致性由 [TemplateRegistryGuardTest]（快照↔Kotlin）与 C++
 * equipment_db_test（快照↔C++ 表）双面钉死；本文件只管「生成机器没坏」。
 */
class TemplateCodegenIntegrityGuardTest {

    /** 仓库根（Gradle 测试工作目录 = android/core/engine；scripts/ 在仓库根） */
    private val repoRoot: File = File("..").resolve("..").resolve("..")

    private val androidRoot: File = File("..", "..")

    private val gamecoreData: File
        get() = File(androidRoot, "app/src/main/cpp/gamecore/include/gamecore/data")

    private val generator: File
        get() = File(repoRoot, "scripts/gen-templates.mjs")

    private val sampleJson: File
        get() = File(repoRoot, "scripts/data/equipment_db_sample.json")

    // ── 生成器输出面 ─────────────────────────────────────────

    @Test
    fun `生成器与四表快照在位`() {
        assumeTrue("仓库根不可达（工作目录应为 android/core/engine）", generator.isFile)
        assumeTrue(gamecoreData.isDirectory)
        listOf(
            "equipment_db.h", "equip_set_db.h", "equip_main_stat_db.h", "equip_affix_db.h"
        ).forEach { table ->
            assertTrue("生成物缺失：data/$table", File(gamecoreData, table).isFile)
        }
    }

    @Test
    fun `生成器不得复活死输出面`() {
        assumeTrue(generator.isFile)
        val source = generator.readText()
        // B1 教训：实测输出仅装备四表 + herb_db + 两 JSON 副本；
        // recipe_db.h / data_json.h 从来不是 gen-templates 的输出面
        assertFalse(
            "gen-templates.mjs 不得输出 recipe_db.h（锻造配方旧表已退役）",
            source.contains("recipe_db.h")
        )
        assertFalse(
            "gen-templates.mjs 不得输出 data_json.h（data_json 适配器是手写件）",
            source.contains("data_json.h")
        )
    }

    @Test
    fun `生成物幂等面在位`() {
        assumeTrue(gamecoreData.isDirectory)
        listOf(
            "equipment_db.h", "equip_set_db.h", "equip_main_stat_db.h", "equip_affix_db.h"
        ).forEach { table ->
            val source = File(gamecoreData, table).readText()
            assertTrue(
                "$table 缺 Mutable() 幂等重建工厂（生成器模板损坏）",
                source.contains("Mutable()")
            )
        }
        assertTrue(
            "equipment_db.h 缺 operator== 相等算子（四方比对依赖）",
            File(gamecoreData, "equipment_db.h").readText().contains("operator==")
        )
    }

    @Test
    fun `四表有真实消费面`() {
        assumeTrue(gamecoreData.isDirectory)
        val systemDir = File(androidRoot, "app/src/main/cpp/gamecore/include/gamecore/system")
        assumeTrue(systemDir.isDirectory)
        val systemSources = systemDir.walkTopDown().filter { it.extension == "h" }
        listOf(
            "equipment_db.h" to 4,
            // set_db 当前唯一系统消费者 = auto_gear.h（套装流派比较键）；Kotlin 侧
            // EquipmentSetDatabase 同表同源，消费面以 1 为下限防死符号
            "equip_set_db.h" to 1,
            // main_stat/affix 当前唯一系统消费者 = equipment_factory.h
            //（rollMainStat/rollSubStats；C++ 池消费集中于工厂单点，Kotlin 对偶
            // 第二消费者在 data 层规则不在 gamecore），消费面以 1 为下限防死符号
            "equip_main_stat_db.h" to 1,
            "equip_affix_db.h" to 1
        ).forEach { (table, minConsumers) ->
            val consumers = systemSources.count { src ->
                src.readText().contains(table)
            }
            assertTrue(
                "$table 消费面不足（疑似死符号：$consumers < $minConsumers 个系统头引用）",
                consumers >= minConsumers
            )
        }
    }

    // ── 中性源结构 ───────────────────────────────────────────

    @Test
    fun `中性源为五段复合结构且部件数自洽`() {
        assumeTrue(sampleJson.isFile)
        val text = sampleJson.readText()
        listOf(
            "pieceCount", "setPieces", "sets", "mainStatPools", "mainStatBase", "subAffixes"
        ).forEach { key ->
            assertTrue("中性源缺段：$key", text.contains("\"$key\""))
        }
        // 旧四列模板结构不得回潮（B3 前的 entries 面已退役）
        assertFalse(
            "中性源不得再含旧 entries 面或旧 7 属性字段",
            text.contains("\"entries\"") || text.contains("physicalAttack")
        )
        // pieceCount 自洽
        val pieceCount = Regex("\"pieceCount\"\\s*:\\s*(\\d+)").find(text)
            ?.groupValues?.get(1)?.toInt()
        assertEquals("pieceCount 应为 24（6 套 × 4 部位）", 24, pieceCount)
        assertEquals(
            "Kotlin 部件表条数与中性源自洽",
            pieceCount, com.xianxia.sect.core.registry.EquipmentDatabase.setPieces.size
        )
    }
}
