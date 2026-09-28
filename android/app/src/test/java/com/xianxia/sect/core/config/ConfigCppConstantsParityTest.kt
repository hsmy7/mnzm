package com.xianxia.sect.core.config

import com.xianxia.sect.core.GameConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * ConfigCppConstantsParityTest — 治理/结算常量 C++ ↔ Kotlin 双端同源守卫
 * （结算改造方案 §9.1 缺陷 #15：「常量外置不完整 + 双端漂移风险」；
 * GameConfigConsistencyTest 只校验 Kotlin 两源、C++ 侧无任何校验面的缺口闭合）。
 *
 * 机制：读取 C++ 头文件文本，正则抽取 `constexpr` 常量值，与 Kotlin 侧
 * （可达常量直断 + private 常量源码文本抽取）互断——改任一侧另一侧即红。
 * C++ 腿 = `gamecore/test/game_config_parity_test.cpp`（锁 C++ 值 + 注释锚点）。
 * 改值须双端同步（GameTimeUnitsParityTest 先例）。
 */
class ConfigCppConstantsParityTest {

    /** Gradle 工作目录 = android/app；C++ 源相对路径 src/main/cpp */
    private fun cppSource(relative: String): String {
        val f = File("src/main/cpp/gamecore/include/gamecore/system/$relative")
        assertTrue("C++ 头不可读: $f", f.isFile)
        return f.readText()
    }

    /** 从 C++ 文本抽取 `constexpr <type> <name> = <value>` 的值字面量 */
    private fun cppConst(text: String, name: String): String {
        val m = Regex(
            """constexpr\s+\w+\s+(?:<[^>]+>\s+)?$name\s*=\s*([^;]+);"""
        ).find(text)
        assertTrue("C++ 常量 $name 未找到（被改名/移动？两腿须同步）", m != null)
        return m!!.groupValues[1].trim()
    }

    /** 从 Kotlin 源文本抽取 `const val NAME = value` / `private const val NAME = value` */
    private fun kotlinConst(relative: String, name: String): String {
        val f = File(relative)
        assertTrue("Kotlin 源不可读: $f", f.isFile)
        val m = Regex("""(?:const\s+val|private\s+const\s+val)\s+$name\s*(?::\s*\w+)?\s*=\s*([^\n=]+)""")
            .find(f.readText())
        assertTrue("Kotlin 常量 $name 未找到（被改名/移动？两腿须同步）", m != null)
        return m!!.groupValues[1].trim().removeSuffix("//").trim()
    }

    // ── 1. 灵矿增产乘区 ─────────────────────────────────────────────

    @Test
    fun `spiritMineBoost multiplier matches kotlin 1_2`() {
        val cpp = cppConst(cppSource("government.h"), "kSpiritMineBoostMultiplier")
        assertEquals("1.2", cpp)
        // Kotlin 侧：CultivationSettlement private 常量（引擎相对路径）
        val kt = kotlinConst(
            "../core/engine/src/main/java/com/xianxia/sect/core/engine/service/CultivationSettlement.kt",
            "SPIRIT_MINE_BOOST_MULTIPLIER"
        )
        assertEquals("1.2", kt)
        // 语义对齐：GameConfig.PolicyConfig.SPIRIT_MINE_BOOST_EFFECT + 1 == 乘区
        assertEquals(1.2, GameConfig.PolicyConfig.SPIRIT_MINE_BOOST_EFFECT + 1.0, 1e-9)
    }

    // ── 2. 执事道德加成率 ───────────────────────────────────────────

    @Test
    fun `deaconMoralityBonusRate matches kotlin 0_01`() {
        assertEquals("0.01", cppConst(cppSource("government.h"), "kDeaconMoralityBonusRate"))
        val kt = kotlinConst(
            "../core/engine/src/main/java/com/xianxia/sect/core/engine/service/CultivationSettlement.kt",
            "DEACON_MORALITY_BONUS_RATE"
        )
        assertEquals("0.01", kt)
    }

    // ── 3. 长老技能基线（三处 C++ 定义 + GameConfig 常量）───────────

    @Test
    fun `elderSkillBaseline is 80 across all definitions`() {
        assertEquals("80", cppConst(cppSource("government.h"), "kElderSkillBaseline"))
        assertEquals("80", cppConst(cppSource("production.h"), "kElderSkillBaseline"))
        assertEquals("80", cppConst(cppSource("disciple_stats.h"), "kElderSkillBaseline"))
        assertEquals(80, GameConfig.PolicyConfig.ELDER_SKILL_BASELINE)
    }

    // ── 4. AI 兽袭跳过冷却（月）────────────────────────────────────

    @Test
    fun `aiSkipCooldownMonths matches kotlin 12`() {
        assertEquals("12", cppConst(cppSource("month_settlement.h"), "kAiSkipCooldownMonths"))
        val kt = kotlinConst(
            "../core/engine/src/main/java/com/xianxia/sect/core/exploration/AISectBeastAttackProcessor.kt",
            "SKIP_COOLDOWN_MONTHS"
        )
        assertEquals("12", kt)
    }

    // ── 5. 世界关卡刷新间隔（月）────────────────────────────────────

    @Test
    fun `levelRefreshIntervalMonths matches kotlin inline 3`() {
        assertEquals("3", cppConst(cppSource("exploration.h"), "kLevelRefreshIntervalMonths"))
        // Kotlin 侧为内联字面量（WorldLevelManager `>= 3`）——源码文本锁定
        val f = File("../core/engine/src/main/java/com/xianxia/sect/core/exploration/WorldLevelManager.kt")
        assertTrue(f.isFile)
        assertTrue(
            "WorldLevelManager 刷新间隔须保持 3 个月口径（C++ kLevelRefreshIntervalMonths 同值）",
            f.readText().contains(">= 3")
        )
    }

    // ── 6. 秘境现世年限（年）────────────────────────────────────────

    @Test
    fun `secretRealmOpenYears matches GameConfig OPEN_YEARS 5`() {
        assertEquals("5", cppConst(cppSource("secret_realm.h"), "kOpenYears"))
        assertEquals(5, GameConfig.SecretRealm.OPEN_YEARS)
    }

    // ── 7. 丹药月度衰减（旬）────────────────────────────────────────

    @Test
    fun `monthlyDecayPhases matches kotlin inline 3`() {
        assertEquals("3", cppConst(cppSource("month_settlement.h"), "kMonthlyDecayPhases"))
        // Kotlin 侧为内联字面量（HpMpRecoveryService `(3 - focusedPhaseCount)`）
        val f = File(
            "../core/engine/src/main/java/com/xianxia/sect/core/engine/service/HpMpRecoveryService.kt"
        )
        assertTrue(f.isFile)
        assertTrue(
            "HpMpRecoveryService 月衰减须保持每月 3 旬口径（C++ kMonthlyDecayPhases 同值）",
            f.readText().contains("(3 - focusedPhaseCount)")
        )
    }
}
