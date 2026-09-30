package com.xianxia.sect.core.engine.domain.disciple

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * S21 · 单列符号面守卫（装备重构 B1，方案 §15 / 批次文档 §1 E9）。
 *
 * 全仓源码扫描：`physicalAttack/magicAttack/physicalDefense/magicDefense`
 * 作为**弟子属性名**必须归零——四列已收敛为 `attack/defense`（E9），物法之分
 * 走三条通道（普攻 innateDamageType / 技能 damageType / 类型增减伤分桶）。
 *
 * 显式豁免面（intentionallyExcluded，每项标注豁免理由与退役批）：
 * - **装备模板/实例面**（`EquipmentStack/EquipmentInstance/EquipmentStats`
 *   的四列字段与 game-data/equipment_db JSON 键）——装备模型
 *   本体随 **B3 装备体系原子替换** 批退役，B1 只在映射层（toDiscipleStats 等）
 *   相加，四列字段本身保留；
 * - **功法数据面**（`ManualInstance.stats` map 的 "physicalAttack" 等字符串键）——
 *   Q2 拍板「150+ 功法数据与 codegen 一字不改」，结算层相加；
 * - **协议占位面**（`DiscipleSerializer.DiscipleSurrogate` 的 69..72/62..65/36..39
 *   deprecated 只读声明、`ItemEffect/PillEffect` 旧四列、`WorldLevel` 旧列）——
 *   号禁复用的只读声明，暂不删；
 * - **展示文案**（EFFECT_KEY_NAMES 物攻/法攻等 UI 键）——物品效果键非弟子属性。
 */
class SingleColumnStatGuardTest {

    /** 弟子属性名（禁用面） */
    private val bannedNames = listOf(
        "physicalAttack", "magicAttack", "physicalDefense", "magicDefense"
    )

    /** 豁免文件（路径含任一子串即跳过；每项注明理由与退役批） */
    private val exemptedPathParts = listOf(
        // 装备模型面（B3 退役）
        "EquipmentStack", "EquipmentInstance", "EquipmentStats", "EquipmentDatabase",
        "EquipmentRegistry", "EquipmentFinalStats", "equipment_db",
        // 功法数据面（Q2 一字不改）
        "Manual", "manual_db",
        // 协议占位面（号禁复用只读声明）
        "DiscipleSerializer", "ItemEffect", "Items", "CultivatorCave", "WorldLevel",
        // 战斗对拍桥的装备 JSON（equipment 段键）
        "battle_json",
    )

    /** 行内豁免：注释行与 deprecated 标注行不参与扫描 */
    private fun isExemptLine(line: String): Boolean {
        val t = line.trim()
        return t.startsWith("//") || t.startsWith("*") || t.startsWith("/*")
    }

    private fun repoRoot(): File {
        // 模块 cwd = android/core/engine → 上溯找含 core/domain/src/main 的目录（仓库 android/ 根）
        return generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
            .firstOrNull { File(it, "core/domain/src/main").exists() }
            ?: error("未找到仓库 android/ 根（cwd=${System.getProperty("user.dir")}）")
    }

    @Test
    fun `legacy four-column stat names are gone from disciple attribute surface`() {
        val root = repoRoot()
        val srcRoots = listOf(
            File(root, "android/core/domain/src/main"),
            File(root, "android/core/engine/src/main"),
            File(root, "android/core/data/src/main"),
            File(root, "android/core/ui/src/main"),
            File(root, "android/feature/game/src/main"),
            File(root, "android/app/src/main/cpp/gamecore/include"),
            File(root, "android/app/src/main/cpp/gamecore/src")
        )
        val violations = mutableListOf<String>()
        for (src in srcRoots) {
            if (!src.exists()) continue
            src.walkTopDown().filter { it.isFile }.forEach { f ->
                val path = f.relativeTo(root).path.replace('\\', '/')
                if (exemptedPathParts.any { path.contains(it) }) return@forEach
                f.readLines().forEachIndexed { idx, line ->
                    if (isExemptLine(line)) return@forEachIndexed
                    for (name in bannedNames) {
                        // 词边界匹配（变量名/列名/JSON 键），且不得是 `physicalAttackAdd` 等更长标识符
                        val regex = Regex("(?<![A-Za-z0-9_])${name}(?![A-Za-z0-9_])")
                        if (regex.containsMatchIn(line)) {
                            violations.add("$path:${idx + 1}: $name → ${line.trim().take(100)}")
                        }
                    }
                }
            }
        }
        assertTrue(
            "弟子四列属性名未归零（E9 单列口径，B1）——共 ${violations.size} 处：\n" +
                violations.take(30).joinToString("\n") +
                "\n处置：弟子/战斗/镜像/Room 实体面一律改用 attack/defense + 类型通道；" +
                "装备/功法模板面请落到豁免清单对应文件（其四列随 B3 退役）。",
            violations.isEmpty()
        )
    }
}
