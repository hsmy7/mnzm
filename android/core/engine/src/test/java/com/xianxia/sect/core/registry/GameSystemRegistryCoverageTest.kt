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
