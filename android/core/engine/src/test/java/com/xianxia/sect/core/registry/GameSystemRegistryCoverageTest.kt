package com.xianxia.sect.core.registry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * GameSystem 注册完整性守卫（2026-08-13 批次 4，对标 9.5 守卫测试三要素）。
 *
 * 锚点 = 源码 @GameService 标注：扫描 core/engine + core/domain 主源码，
 * 解析标注类名与包路径推导类别，断言全部已注册且类别一致。
 * 新增 @GameService 类未在 [GameSystemRegistryDefaults] 追加注册 → 本测试
 * 失败并列出缺失与去注册位置。
 */
class GameSystemRegistryCoverageTest {

    @Test
    fun `全部 @GameService 类已注册且类别与包路径一致`() {
        val annotated = scanAnnotatedServices()
        GameSystemRegistryDefaults.registerAll()

        val missing = annotated.filter { GameSystemRegistry.find(it.className) == null }
        assertTrue(
            "以下 @GameService 类未注册: ${missing.map { it.className }}——" +
                "在 GameSystemRegistryDefaults.registerAll() 追加 register 行",
            missing.isEmpty()
        )

        for (entry in annotated) {
            val registered = GameSystemRegistry.find(entry.className)
            assertEquals(
                "${entry.className} 类别与包路径不一致——注册类别必须匹配包归属",
                entry.category, registered?.category
            )
        }
    }

    @Test
    fun `全部 @GameService 的 name 与类名一致`() {
        // 对抗性审查 2026-08-13 数据篡改者#6：注册 key=className——若未来
        // @GameService(name="中文名") 与类名脱钩，find(name) 查不到；本守卫
        // 锁死 name==类名 约定（脱钩时需同步改造注册表以消费 name）
        val mismatched = scanAnnotatedNames().filter { it.first != it.second }
        assertTrue(
            "@GameService.name 与类名不一致: ${mismatched.map { "${it.second}:name=${it.first}" }}——" +
                "注册表以类名为 key（GameSystemRegistryDefaults），name 必须等于类名或注册表改消费 name",
            mismatched.isEmpty()
        )
    }

    /** 解析 @GameService(name = "...") 标注值（name=类名 时 Kotlin 省略 name 参数） */
    private fun scanAnnotatedNames(): List<Pair<String, String>> {
        val roots = listOf(
            File("src/main/java/com/xianxia/sect/core/engine"),
            File("src/main/java/com/xianxia/sect/core/domain"),
        )
        val results = mutableListOf<Pair<String, String>>()
        for (root in roots) {
            if (!root.exists()) continue
            root.walkTopDown().filter { it.isFile && it.extension == "kt" }.forEach { file ->
                val text = file.readText()
                if (!text.contains("@GameService")) return@forEach
                val className = file.nameWithoutExtension
                val named = Regex("""@GameService\(\s*name\s*=\s*"([^"]+)"\s*""").find(text)
                // 无 name 参数 = 省略写法（约定 name==类名）；显式 name 必须等于类名
                val nameValue = named?.groupValues?.get(1) ?: className
                results += nameValue to className
            }
        }
        return results.sortedBy { it.second }
    }

    private data class AnnotatedService(val className: String, val category: String)

    /** 包路径归属 → 类别（与 GameSystemRegistryDefaults 的类别规则一致） */
    private fun categoryFor(isEngineRoot: Boolean, rel: String): String = when {
        isEngineRoot && rel.isEmpty() -> "engine"
        isEngineRoot && rel.startsWith("service") -> "engine.service"
        isEngineRoot && rel.startsWith("domain") -> "engine.domain"
        isEngineRoot -> "service"
        else -> "domain"
    }

    /** 扫描两模块主源码：解析 @GameService 标注类名 + 包路径推导类别 */
    private fun scanAnnotatedServices(): List<AnnotatedService> {
        val roots = listOf(
            File("src/main/java/com/xianxia/sect/core/engine"),
            File("src/main/java/com/xianxia/sect/core/domain"),
        )
        val results = mutableListOf<AnnotatedService>()
        for (root in roots) {
            if (!root.exists()) continue
            root.walkTopDown().filter { it.isFile && it.extension == "kt" }.forEach { file ->
                val text = file.readText()
                if (!text.contains("@GameService")) return@forEach
                val className = file.nameWithoutExtension
                val rel = file.relativeTo(root).parentFile?.path?.replace('\\', '/') ?: ""
                val isEngineRoot = root.path.replace('\\', '/').endsWith("core/engine")
                results += AnnotatedService(className, categoryFor(isEngineRoot, rel))
            }
        }
        return results.sortedBy { it.className }
    }
}
