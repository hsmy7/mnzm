package com.xianxia.sect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 清单完整性守卫（2026-08-13 资源管线 codegen）。
 *
 * 遍历 feature/game 与 app 两个 drawable-nodpi 目录，断言 atlas-manifest.json
 * 完整登记了每个资源（双模块同名副本 = 同一资源，按名称去重）。
 * 覆盖规则（rules/static-resources.md）：新精灵图必须双模块放置——
 * 只放一边或漏放任何文件都会使本测试变红。
 */
class ResourceManifestCompletenessTest {

    companion object {
        // File.extension 不含点（"webp" 而非 ".webp"）
        private val IMAGE_EXTS = setOf("webp", "png", "jpg", "jpeg")
    }

    private val appDrawableDir = File("src/main/res/drawable-nodpi")
    private val gameDrawableDir = File("../feature/game/src/main/res/drawable-nodpi")
    private val manifestFile = File("build/generated/sprite/atlas-manifest.json")

    @Test
    fun `manifest 完整登记两个目录的全部资源`() {
        assertTrue(
            "manifest 不存在: ${manifestFile.absolutePath}——请运行 ./gradlew generateResourceManifest",
            manifestFile.exists()
        )
        val manifestNames = parseManifestNames(manifestFile.readText())

        val dirNames = (listOf(appDrawableDir, gameDrawableDir))
            .onEach { assertTrue("资源目录不存在: $it", it.exists()) }
            .flatMap { dir -> collectImageNames(dir) }
            .toSet()

        assertEquals(
            "清单名称集合与目录文件集合不一致——\n" +
                "  manifest 独有（目录中无此文件）: ${manifestNames - dirNames}\n" +
                "  目录中有但未登记: ${dirNames - manifestNames}\n" +
                "新增/删除精灵图后请重新运行 ./gradlew generateResourceManifest（或自动由 generateSpriteCode 触发）",
            dirNames, manifestNames
        )
    }

    @Test
    fun `manifest 每个名称至少登记一个双模块副本条目`() {
        val text = manifestFile.readText()
        val manifestNames = parseManifestNames(text)
        val nameCount = parseManifestNameCounts(text)
        for (name in manifestNames) {
            val count = nameCount[name] ?: 0
            assertTrue(
                "资源 $name 在 manifest 中无条目——清单损坏",
                count >= 1
            )
        }
    }

    /** 收集目录内全部图片资源名（无扩展名，按文件名排序） */
    private fun collectImageNames(dir: File): List<String> =
        dir.listFiles()?.filter { it.isFile && IMAGE_EXTS.contains(it.extension.lowercase()) }
            ?.map { it.nameWithoutExtension }
            ?.sorted()
            ?: emptyList()

    private fun parseManifestNames(text: String): Set<String> {
        val regex = Regex(""""name": "([^"]+)"""")
        return regex.findAll(text).map { it.groupValues[1] }.toSet()
    }

    private fun parseManifestNameCounts(text: String): Map<String, Int> {
        val regex = Regex(""""name": "([^"]+)"""")
        return regex.findAll(text).map { it.groupValues[1] }
            .groupingBy { it }
            .eachCount()
    }
}
