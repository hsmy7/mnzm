package com.xianxia.sect.core.architecture

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * 守卫测试：仓库物品添加路径必须走统一合并入口（StackableItemStore）。
 *
 * 历史问题：多条发放路径（邮件/签到/兑换码/外交/灵田等）手写
 * "find 第一个堆叠 + 列表追加 + coerceAtMost 截断"，导致同种物品
 * 分裂为多个堆叠。2026-08-01 已全部统一委托 InventorySystem.addXxx。
 *
 * 本测试扫描 engine 源文件，断言以下手写合并反模式数量为 0，
 * 防止未来新增发放路径时重新引入分裂 bug：
 * 1. `coerceAtMost(inventoryConfig.getMaxStackSize` — 溢出静默截断
 * 2. `equipmentStacks = equipmentStacks + item` — 列表直接追加新堆叠
 * 3. `newQty = existing.quantity + X` — 手写"相加合并"（仅匹配仓库堆叠字段）
 */
class InventoryAddPathGuardTest {

    /** 反模式 1：溢出用 coerceAtMost 截断丢弃（应改走 StackableItemStore 的 Partial 语义） */
    private val truncationPattern = Regex("coerceAtMost\\s*\\(\\s*inventoryConfig\\.getMaxStackSize")

    /** 反模式 2：向仓库堆叠列表直接追加新条目（应改走 addXxx 合并） */
    private val directAppendPattern = Regex(
        "(equipmentStacks|manualStacks|pills|materials|herbs|seeds|storageBags)" +
            "\\s*=\\s*\\1\\s*\\+"
    )

    /** 反模式 3：手写"数量相加合并"（find 第一个匹配堆叠 + newQty 相加） */
    private val inlineMergeAddPattern = Regex("newQty\\s*=\\s*\\(?\\w+\\.quantity\\s*\\+")

    /**
     * 白名单：离线遗留仓库路径（SectWarehouse/WarehouseItem，主流程未使用），
     * 仍保留自己的堆叠实现，不属于统一入口范围。
     */
    private val allowedFiles = setOf("OptimizedWarehouseManager.kt")

    // Gradle 测试工作目录为模块目录（android/core/engine）
    private val engineSourceDir = File(
        "src" + File.separator + "main" + File.separator + "java"
    )

    private fun sourceFiles(): List<File> =
        engineSourceDir.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()

    private fun matchesIn(files: List<File>, pattern: Regex): List<Pair<File, String>> =
        files.flatMap { file ->
            file.readLines().mapIndexedNotNull { index, line ->
                if (pattern.containsMatchIn(line)) file to "${file.name}:${index + 1}: $line" else null
            }
        }

    @Test
    fun `no overflow truncation via coerceAtMost in any add path`() {
        val files = sourceFiles()
        val matches = matchesIn(files, truncationPattern)
        assertEquals(
            "发现 ${matches.size} 处溢出截断（coerceAtMost + getMaxStackSize）：\n" +
                matches.joinToString("\n") { it.second } +
                "\n应改为委托 InventorySystem.addXxx（StackableItemStore Partial 语义）",
            emptyList<Pair<File, String>>(), matches
        )
    }

    @Test
    fun `no direct list append to warehouse stacks`() {
        val files = sourceFiles()
        val matches = matchesIn(files, directAppendPattern)
        assertEquals(
            "发现 ${matches.size} 处仓库堆叠列表直接追加：\n" +
                matches.joinToString("\n") { it.second } +
                "\n应改为委托 InventorySystem.addXxx（自动合并同种堆叠）",
            emptyList<Pair<File, String>>(), matches
        )
    }

    @Test
    fun `no inline quantity-merge outside allowed legacy file`() {
        val files = sourceFiles().filter { it.name !in allowedFiles }
        val matches = matchesIn(files, inlineMergeAddPattern)
        assertEquals(
            "发现 ${matches.size} 处手写数量相加合并：\n" +
                matches.joinToString("\n") { it.second } +
                "\n应改为委托 InventorySystem.addXxx（StackableItemStore 遍历所有匹配堆叠）",
            emptyList<Pair<File, String>>(), matches
        )
    }
}
