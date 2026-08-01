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

    /**
     * 反模式 1：溢出用 coerceAtMost 截断丢弃（应改走 StackableItemStore 的 Partial 语义）。
     * 覆盖 `inventoryConfig.getMaxStackSize` / `config.getMaxStackSize` / 本地 maxStack 变量别名。
     */
    private val truncationPattern = Regex(
        "coerceAtMost\\s*\\(\\s*(?:\\w+\\.)*\\w*[sS]tackSize\\s*\\)"
    )

    /**
     * 反模式 2：向仓库堆叠列表直接追加新条目（应改走 addXxx 合并）。
     * 对抗性审查增强：覆盖 `+=`、`state.`/`this.` 前缀、括号、`=` 右侧全限定
     * 以及 `.plus(` 变体（历史 bug 的原始写法就是 `state.equipmentStacks = state.equipmentStacks + x`）。
     */
    private val directAppendPattern = Regex(
        "(equipmentStacks|manualStacks|pills|materials|herbs|seeds|storageBags)" +
            "\\s*(\\+=|=)\\s*\\(?\\s*(?:state|this)?\\s*\\.?\\s*\\1\\s*(?:\\+|\\s*\\.\\s*plus\\s*\\()"
    )

    /**
     * 反模式 3：手写"数量相加合并"（find 第一个匹配堆叠 + 数量相加）。
     * 对抗性审查增强：覆盖 newCount 等变量名变体与 `.plus(` 变体。
     */
    private val inlineMergeAddPattern = Regex(
        "new\\w*\\s*=\\s*\\(?\\w+\\.quantity\\s*(?:\\+|\\s*\\.\\s*plus\\s*\\()"
    )

    /**
     * 白名单：离线遗留仓库路径（SectWarehouse/WarehouseItem，主流程未使用），
     * 仍保留自己的堆叠实现，不属于统一入口范围。
     * 对抗性审查修复：按相对路径匹配（避免任意包下同名文件被静默豁免），
     * 并有存在性断言（文件被删除/改名时守卫测试失败，防止白名单悬空）。
     */
    private val allowedFiles = setOf(
        "com" + File.separator + "xianxia" + File.separator + "sect" + File.separator +
            "core" + File.separator + "warehouse" + File.separator + "OptimizedWarehouseManager.kt"
    )

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

    /** 文件相对 engine 源码根（src/main/java/）的路径，用于白名单匹配 */
    private fun relativePath(file: File): String =
        file.path.substringAfter(engineSourceDir.path + File.separator)

    @Test
    fun `guard whitelist files still exist`() {
        val files = sourceFiles().map { relativePath(it) }.toSet()
        val missing = allowedFiles.filter { it !in files }
        assertEquals(
            "白名单文件不存在或已被改名/删除：$missing\n" +
                "若文件确实已移除，请同时删除对应白名单条目",
            emptyList<String>(), missing
        )
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
        val files = sourceFiles().filter { relativePath(it) !in allowedFiles }
        val matches = matchesIn(files, inlineMergeAddPattern)
        assertEquals(
            "发现 ${matches.size} 处手写数量相加合并：\n" +
                matches.joinToString("\n") { it.second } +
                "\n应改为委托 InventorySystem.addXxx（StackableItemStore 遍历所有匹配堆叠）",
            emptyList<Pair<File, String>>(), matches
        )
    }

    /** 反模式 4：手写 StackableItemStore 构造（应统一走 InventorySystem.addXxx） */
    private val handWrittenStorePattern = Regex("StackableItemStore\\s*\\(")

    /**
     * 允许使用 StackableItemStore 的文件：
     * - InventorySystem.kt：统一入口内部（7 个 addXxx 的实现）
     * - ProductionProcessor.kt：灵田收获走"state 参数直传"模式（PlantingSystem
     *   onMonthlyEvent 传入事务缓冲，区别于 stateStore.update 模式），
     *   溢出通过 InventorySystem.sendOverflowMail 转邮件
     */
    private val storeAllowedFiles = setOf("InventorySystem.kt", "ProductionProcessor.kt")

    @Test
    fun `no hand-written StackableItemStore outside unified entry`() {
        val files = sourceFiles().filter { it.name !in storeAllowedFiles }
        val matches = matchesIn(files, handWrittenStorePattern)
        assertEquals(
            "发现 ${matches.size} 处手写 StackableItemStore 构造：\n" +
                matches.joinToString("\n") { it.second } +
                "\n应改为委托 InventorySystem.addXxx（自动获得合并/溢出转邮件/容量通知能力）",
            emptyList<Pair<File, String>>(), matches
        )
    }
}
