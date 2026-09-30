package com.xianxia.sect.core.engine.domain.disciple

import com.xianxia.sect.core.model.CharacterTemplateDb
import com.xianxia.sect.core.model.InnateDamageType
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * S21 · 固有伤害属性守卫（装备重构 B1，方案 §15.3 Q1）。
 *
 * 守护面：
 * 1. **模板覆盖完整**——六条具名模板的 `innateDamageType` 全部非空且取值合法
 *    （"PHYSICAL"/"MAGIC"），与首灵根派生口径逐条一致（金/土→物理，水/木/火→法术）；
 * 2. **派生幂等**——`InnateDamageType.derive(templateId, spiritRootType)` 对同一输入
 *    恒等（旧档按 templateId 幂等回填的前提）；模板缺失走首灵根派生；
 * 3. **Room v62 迁移 SQL 同口径**——迁移 CASE 的派生分支（metal/earth→PHYSICAL、
 *    water/wood/fire→MAGIC、空串兜底 PHYSICAL）与 [InnateDamageType.deriveFromRoot]
 *    逐条一致（源码扫描钉住，防两侧漂移）。
 */
class InnateDamageTypeGuardTest {

    @Test
    fun `模板覆盖完整且与首灵根派生口径一致`() {
        assertTrue("模板表为空", CharacterTemplateDb.ALL.isNotEmpty())
        for (template in CharacterTemplateDb.ALL) {
            assertTrue(
                "模板 ${template.id} 的 innateDamageType 为空（创建时继承源缺失）",
                template.innateDamageType.isNotEmpty()
            )
            assertTrue(
                "模板 ${template.id} 的 innateDamageType=${template.innateDamageType} " +
                    "取值域只允许 PHYSICAL/MAGIC",
                template.innateDamageType in setOf(InnateDamageType.PHYSICAL, InnateDamageType.MAGIC)
            )
            // 模板值必须等于按其首灵根派生的结果（配置人填错即判红）
            val derived = InnateDamageType.deriveFromRoot(template.spiritRoots.first())
            assertEquals(
                "模板 ${template.id} 的 innateDamageType=${template.innateDamageType} " +
                    "与其首灵根 ${template.spiritRoots.first()} 的派生口径（$derived）不一致",
                derived, template.innateDamageType
            )
        }
    }

    @Test
    fun `derive 按模板命中且幂等`() {
        for (template in CharacterTemplateDb.ALL) {
            val first = InnateDamageType.derive(template.id, "fire")
            val second = InnateDamageType.derive(template.id, "fire")
            assertEquals(
                "模板 ${template.id} 的 derive 不幂等（旧档回填前提破坏）",
                first, second
            )
            assertEquals(
                "模板 ${template.id} 命中时应取模板值（灵根只是占位）",
                template.innateDamageType, first
            )
        }
    }

    @Test
    fun `模板缺失按首灵根派生`() {
        assertEquals(
            "金灵根应派生物理",
            InnateDamageType.PHYSICAL, InnateDamageType.derive("", "metal")
        )
        assertEquals(
            "土灵根应派生物理",
            InnateDamageType.PHYSICAL, InnateDamageType.derive("", "earth")
        )
        assertEquals(InnateDamageType.MAGIC, InnateDamageType.derive("", "water"))
        assertEquals(InnateDamageType.MAGIC, InnateDamageType.derive("", "wood"))
        assertEquals(InnateDamageType.MAGIC, InnateDamageType.derive("", "fire"))
        // 多灵根取首段
        assertEquals(InnateDamageType.MAGIC, InnateDamageType.derive("", "wood,earth"))
        assertEquals(InnateDamageType.PHYSICAL, InnateDamageType.derive("", "earth,water"))
        // 未知模板 id → 走灵根派生；空灵根 → 兜底物理
        assertEquals(InnateDamageType.MAGIC, InnateDamageType.derive("__no_such__", "fire"))
        assertEquals(InnateDamageType.PHYSICAL, InnateDamageType.derive("", ""))
    }

    @Test
    fun `Room v62 迁移 SQL 的 CASE 与 deriveFromRoot 同口径`() {
        // 源码扫描钉住：迁移 SQL 的 CASE 分支与派生函数的元素集逐条一致
        // 模块 cwd = android/core/engine → 仓库 android/ 根两级上溯
        val migrationFile = generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
            .firstOrNull { File(it, "core/data/src/main/java/com/xianxia/sect/data/local/" +
                "GameDatabaseMigrationsV62.kt").exists() }
            ?.let { File(it, "core/data/src/main/java/com/xianxia/sect/data/local/" +
                "GameDatabaseMigrationsV62.kt") }
        val sql: String = migrationFile?.readText()
            ?: error("迁移文件 GameDatabaseMigrationsV62.kt 未找到（cwd=${System.getProperty("user.dir")}）")
        assertTrue("迁移文件不存在：${migrationFile.path}", migrationFile.exists())

        assertTrue(
            "迁移 SQL 缺 metal→PHYSICAL 分支（与 deriveFromRoot 口径漂移）",
            sql.contains("WHEN 'metal' THEN 'PHYSICAL'")
        )
        assertTrue(
            "迁移 SQL 缺 earth→PHYSICAL 分支",
            sql.contains("WHEN 'earth' THEN 'PHYSICAL'")
        )
        assertTrue(
            "迁移 SQL 缺 water→MAGIC 分支",
            sql.contains("WHEN 'water' THEN 'MAGIC'")
        )
        assertTrue(
            "迁移 SQL 缺 wood→MAGIC 分支",
            sql.contains("WHEN 'wood' THEN 'MAGIC'")
        )
        assertTrue(
            "迁移 SQL 缺 fire→MAGIC 分支",
            sql.contains("WHEN 'fire' THEN 'MAGIC'")
        )
        assertTrue(
            "迁移 SQL 缺 ELSE 'PHYSICAL' 兜底（空灵根/未知元素口径）",
            sql.contains("ELSE 'PHYSICAL'")
        )
    }

}
