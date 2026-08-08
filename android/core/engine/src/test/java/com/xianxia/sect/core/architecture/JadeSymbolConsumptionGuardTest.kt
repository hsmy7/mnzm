package com.xianxia.sect.core.architecture

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * 守卫测试：玉符（jadeSymbols）写入必须收敛于 [com.xianxia.sect.core.engine.service.JadeSymbolService]。
 *
 * 历史问题：玉符是**绝对值覆盖写模型**——JadeSymbolService 运行时 totalCount
 * 以绝对值覆盖写 GameData.jadeSymbols（checkpointNow/settleGrants）。
 * 若未来新增消耗/发放玩法绕过服务、直接在事务内 `copy(jadeSymbols = ...)`，
 * 运行时 totalCount 未同步，checkpoint 会把余额写回覆盖前值（玉符回涨），
 * 且扣减路径无审计语义（InsufficientJadeSymbols 三态/不消耗 RNG 序列）。
 *
 * 本测试扫描 engine 主源码，断言以下反模式数量为 0：
 * 1. `copy(jadeSymbols` — 直接改 GameData.jadeSymbols（data class 唯一改字段途径）
 * 2. `.jadeSymbols = ` — 直接属性赋值（GameData.jadeSymbols 为 val，
 *    正常应无法编译；兜底拦截未来字段 var 化）
 *
 * 白名单：JadeSymbolService.kt（唯一合法入口自身——deduct/checkpointNow/settleGrants
 * 内部覆盖写是本模型的既定实现）。存档自愈（core/data JadeSymbolNonNegativeRule）
 * 不在本守卫的 engine 扫描范围，其语义为"越界修正"而非玩家可触发的消耗/发放。
 *
 * 新增消耗玉符的玩法（如洗炼灵根）必须：
 * ```
 * stateStore.updateAndReturn {
 *     if (!jadeSymbolService.deduct(this, cost)) return@updateAndReturn Insufficient(...)
 *     // 玩法逻辑（扣减成功后）
 * }
 * jadeSymbolService.publishJadeSymbolStateNow()  // 事务外
 * ```
 */
class JadeSymbolConsumptionGuardTest {

    /** 反模式 1：直接 copy 改 GameData.jadeSymbols（应委托 JadeSymbolService 内事务扣减/结算） */
    private val copyWritePattern = Regex("copy\\s*\\(\\s*jadeSymbols\\s*=")

    /** 反模式 2：直接属性赋值（GameData.jadeSymbols 为 val，正常编译不过；字段 var 化时兜底拦截） */
    private val directAssignPattern = Regex("\\.jadeSymbols\\s*=")

    /**
     * 白名单：JadeSymbolService.kt（绝对值覆盖写模型的所有内部写入点——
     * checkpointNow/deduct/settleGrants 均为该服务的既定实现，见守卫文档）。
     * 按相对路径匹配（避免任意包下同名文件被静默豁免），并有存在性断言
     * （文件被删除/改名时守卫测试失败，防止白名单悬空）。
     */
    private val allowedFiles = setOf(
        "com" + File.separator + "xianxia" + File.separator + "sect" + File.separator +
            "core" + File.separator + "engine" + File.separator + "service" + File.separator +
            "JadeSymbolService.kt"
    )

    // Gradle 测试工作目录为模块目录（android/core/engine）
    private val engineSourceDir = File("src" + File.separator + "main" + File.separator + "java")

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
    fun `no jadeSymbols copy-write outside JadeSymbolService`() {
        val files = sourceFiles().filter { relativePath(it) !in allowedFiles }
        val matches = matchesIn(files, copyWritePattern)
        assertEquals(
            "发现 ${matches.size} 处直接 copy 修改 GameData.jadeSymbols：\n" +
                matches.joinToString("\n") { it.second } +
                "\n玉符是绝对值覆盖写模型——绕过 JadeSymbolService 同步运行时 totalCount，" +
                "checkpointNow/settleGrants 会把余额写回覆盖前值（玉符回涨）。" +
                "\n消耗必须事务内调用 JadeSymbolService.deduct(state, cost)（Insufficient 三态 +" +
                "不消耗 RNG 序列），发放必须走服务内部结算。",
            emptyList<Pair<File, String>>(), matches
        )
    }

    @Test
    fun `no direct jadeSymbols property assignment`() {
        val files = sourceFiles().filter { relativePath(it) !in allowedFiles }
        val matches = matchesIn(files, directAssignPattern)
        assertEquals(
            "发现 ${matches.size} 处直接属性赋值修改 jadeSymbols：\n" +
                matches.joinToString("\n") { it.second } +
                "\nGameData.jadeSymbols 为 val（data class copy 是唯一途径）——若此模式出现，" +
                "说明字段已被 var 化，必须回退并委托 JadeSymbolService。",
            emptyList<Pair<File, String>>(), matches
        )
    }
}
