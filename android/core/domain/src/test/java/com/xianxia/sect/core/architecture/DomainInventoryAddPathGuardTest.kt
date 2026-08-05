package com.xianxia.sect.core.architecture

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * domain 模块仓库合并守卫测试（P-20 补漏）。
 *
 * 背景：InventoryAddPathGuardTest 只扫描 :core:engine——StorageBagUtils（:core:domain）
 * 的手写合并路径（`coerceAtMost(maxStack)` 截断、`maxSlots = candidates.size + 1`
 * 绕过总容量、手写 `StackableItemStore(` 构造）曾在守卫扫描范围外，溢出静默丢失。
 *
 * P-20 已把实例→堆叠转换统一迁移到 :core:engine 的 InventorySystem
 *（addEquipmentInstanceToBag / addManualInstanceToBag），domain 保留纯列表工具。
 * 本守卫防止 domain 侧重新引入仓库合并逻辑：
 * 1. `StackableItemStore(` 手写构造（白名单：类定义自身）
 * 2. `coerceAtMost(...stack/stackSize)` 溢出截断
 */
class DomainInventoryAddPathGuardTest {

    // Gradle 测试工作目录为模块目录（android/core/domain）
    private val domainSourceDir = File(
        "src" + File.separator + "main" + File.separator + "java"
    )

    /** 允许构造 StackableItemStore 的文件：类定义自身（含泛型构造签名） */
    private val storeAllowedFiles = setOf("StackableItemStore.kt")

    private val handWrittenStorePattern = Regex("StackableItemStore\\s*\\(")

    private val truncationPattern = Regex(
        "coerceAtMost\\s*\\(\\s*(?:\\w+\\.)*\\w*(?:[sS]tackSize|[sS]tack)\\s*\\)"
    )

    private fun sourceFiles(): List<File> =
        domainSourceDir.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()

    private fun matchesIn(files: List<File>, pattern: Regex): List<Pair<File, String>> =
        files.flatMap { file ->
            file.readLines().mapIndexedNotNull { index, line ->
                if (pattern.containsMatchIn(line)) file to "${file.name}:${index + 1}: ${line.trim()}" else null
            }
        }

    @Test
    fun `no hand-written StackableItemStore outside its own definition`() {
        val files = sourceFiles().filter { it.name !in storeAllowedFiles }
        val matches = matchesIn(files, handWrittenStorePattern)
        assertEquals(
            "domain 模块发现 ${matches.size} 处手写 StackableItemStore 构造：\n" +
                matches.joinToString("\n") { it.second } +
                "\n仓库堆叠合并必须委托 :core:engine 的 InventorySystem.addXxx" +
                "（真实容量 + 溢出转邮件 + 来源追踪）",
            emptyList<Pair<File, String>>(), matches
        )
    }

    @Test
    fun `no overflow truncation via coerceAtMost in domain`() {
        val matches = matchesIn(sourceFiles(), truncationPattern)
        assertEquals(
            "domain 模块发现 ${matches.size} 处溢出截断（coerceAtMost + stack 别名）：\n" +
                matches.joinToString("\n") { it.second } +
                "\n溢出必须走 StackableItemStore 的 Partial 语义（溢出转邮件或事务回滚），禁止截断丢弃",
            emptyList<Pair<File, String>>(), matches
        )
    }
}
