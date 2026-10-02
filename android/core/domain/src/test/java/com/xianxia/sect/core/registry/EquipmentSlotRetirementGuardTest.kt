package com.xianxia.sect.core.registry

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 武器/腿部退役符号面守卫（四部位化 F2 / 方案 §六 6.1 `EquipmentSlotRetirementGuardTest`）。
 *
 * `weaponId`/`legsId` 槽位字段与 `"WEAPON"`/`"LEGS"` 槽位名已随四部位化（F2）全链退役：
 * Kotlin 存档字段（proto 17/116 reserved）、DiscipleTables 列、proto 镜像（122/123 reserved）、
 * C++ Disciple/列/脏列/槽位分支、Combatant.weaponName 展示链全部删除。
 * 本守卫做**全仓主源符号面扫描**，防残留与"改名保留"（方案 FA2）：
 *
 * - 扫描面 = `android/{app,core,feature}` 主源（`src/main`）的 `.kt/.java/.h/.cpp/.proto`；
 * - 命中即红，除非该行是注释行（双斜线、块注释星号前缀）或含退役说明关键词
 *   （退役/reserved/禁复用/已随）——退役语义只允许活在说明文字里；
 * - 测试源不扫（守卫自身与退役断言用例合法引用退役名）。
 */
class EquipmentSlotRetirementGuardTest {

    /** 退役符号（词边界匹配；大小写敏感——`WeaponId` 枚举常量与 `weaponId` 字段都要拦） */
    private val retiredSymbols = listOf(
        "weaponId", "legsId", "weaponIds", "legsIds",
        "WeaponId", "LegsId", "weaponName", "legsName"
    )

    /** 槽位字符串名（C++/Kotlin 槽位分支键；带引号匹配防误伤普通单词） */
    private val retiredSlotNames = listOf("\"WEAPON\"", "\"LEGS\"")

    /**
     * 故意排除项（守卫三要素②——明确声明为什么不覆盖）：
     * - `gamecore/test/`（C++ 测试树在 src/main 下）：退役条目**零命中断言**合法引用退役名。
     *   （F2 曾排除 `data/recipe_db.h` 旧 72 条通用配方族——F3 已重写为 24 条
     *   套装部件配方，排除项按交接随条目删除一并移除。）
     */
    private val excludedPathMarkers = listOf(
        "cpp${File.separator}gamecore${File.separator}test",
        "cpp/gamecore/test"
    )

    /** 行级豁免：注释行或含退役说明关键词 */
    private val exemptionKeywords = listOf("退役", "reserved", "禁复用", "已随")

    private fun isExempt(line: String): Boolean {
        val trimmed = line.trim()
        if (trimmed.startsWith("//") || trimmed.startsWith("/*") || trimmed.startsWith("*")) {
            return true
        }
        return exemptionKeywords.any { trimmed.contains(it) }
    }

    private fun scanSources(): List<String> {
        val repoRoot = generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
            .firstOrNull { File(it, "android/app/src/main").isDirectory }
            ?: error("仓库根不可达（工作目录=" + System.getProperty("user.dir") + "）")
        val extensions = setOf("kt", "java", "h", "cpp", "proto")
        val roots = listOf("android/app/src/main", "android/core", "android/feature")
            .map { File(repoRoot, it) }
        val offenders = mutableListOf<String>()
        for (root in roots) {
            if (!root.isDirectory) continue
            root.walkTopDown()
                .filter { it.isFile && it.extension in extensions }
                .filter { "src${File.separator}main" in it.path || it.path.contains("/src/main/") }
                .forEach { file ->
                    val relPath = file.relativeTo(repoRoot).path
                    if (excludedPathMarkers.any { relPath.contains(it) }) return@forEach
                    file.useLines { lines ->
                        lines.forEachIndexed { idx, line ->
                            if (isExempt(line)) return@forEachIndexed
                            val hit = retiredSymbols.any { sym ->
                                Regex("\\b${Regex.escape(sym)}\\b").containsMatchIn(line)
                            } || retiredSlotNames.any { line.contains(it) }
                            if (hit) {
                                offenders.add("$relPath:${idx + 1}: ${line.trim().take(100)}")
                            }
                        }
                    }
                }
        }
        return offenders
    }

    @Test
    fun `主源无武器腿部槽位符号残留`() {
        val offenders = scanSources()
        assertTrue(
            "主源发现武器/腿部退役符号残留（防改名保留与半套残留，方案 F-2/FA2）：\n" +
                offenders.joinToString("\n") +
                "\n处置：退役符号只允许出现在注释/退役说明中；代码面引用一律改四部位" +
                "（HEAD/BODY/HANDS/FEET）或删除。",
            offenders.isEmpty()
        )
    }
}
