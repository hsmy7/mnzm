package com.xianxia.sect.core.architecture

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * 守卫测试：玉符余额面（派生缓存 jadeSymbols + 账本 jadeLedger）写入必须收敛于
 * [com.xianxia.sect.core.engine.service.JadeSymbolService]（Kotlin 回退臂）或
 * C++ 玉符事务族（native 臂，经镜像面 [com.xianxia.sect.core.gameview.GameDataFieldPatch] 投影）。
 *
 * SS9 账本模型：余额真源 = jadeLedger（append-only 流水，期初条目 + Σdelta）；
 * jadeSymbols 是同事务双写的派生缓存。任何绕过落账入口的 `copy(jadeLedger = ...)` /
 * `copy(jadeSymbols = ...)` / 直接属性赋值都会破坏「派生余额 == 账本求和」不变式
 * ——账本条目缺失（delta 未入账）或缓存与账本漂移，均属玉符回涨/黑洞类缺陷的
 * 复活通道。
 *
 * 本测试扫描 engine 主源码，断言以下反模式数量为 0：
 * 1. `copy(jadeSymbols` / `copy(jadeLedger` — 直接改 GameData 余额面
 *    （data class 唯一改字段途径）
 * 2. `.jadeSymbols = ` / `.jadeLedger = ` — 直接属性赋值（字段为 var 时兜底拦截）
 *
 * 白名单分界（重划于 SS9）：
 * - [JadeSymbolService.kt]：玩法写唯一入口（回退臂 deduct/落账/settleGrants 的
 *   appendLedger 内部写入是本模型的既定实现）；
 * - [GameDataFieldPatch.kt]：镜像协议面字段级应用（C++ 真相源 → Kotlin 镜像的
 *   合法写入面）——jadeLedger 与 jadeSymbols 同面投影。玩法侧扣减/发放仍必须走服务。
 *
 * 新增消耗玉符的玩法（native 臂可用时自动走 C++ 落账；降级路径）必须：
 * ```
 * stateStore.updateAndReturn {
 *     if (!jadeSymbolService.deduct(this, cost, JadeLedgerReasons.SPEND_XXX)) {
 *         return@updateAndReturn Insufficient(...)
 *     }
 *     // 玩法逻辑（落账成功后）
 * }
 * jadeSymbolService.publishJadeSymbolStateNow()  // 事务外
 * ```
 */
class JadeSymbolConsumptionGuardTest {

    /** 反模式 1：直接 copy 改 GameData.jadeSymbols（应委托 JadeSymbolService 内事务落账/结算） */
    private val copyWritePattern = Regex("copy\\s*\\(\\s*jadeSymbols\\s*=")

    /**
     * 反模式 2：直接 copy 改玉符账本（append-only 流水的唯一合法写法 =
     * JadeSymbolService.appendLedger / C++ 事务落账——账本条目绕过即破坏
     * 「派生余额 == 期初 + Σdelta」不变式）
     */
    private val copyLedgerPattern = Regex("copy\\s*\\(\\s*jadeLedger\\s*=")

    /** 反模式 3：直接属性赋值（正常应无法编译；字段 var 化时兜底拦截——余额面双字段同扫） */
    private val directAssignPattern = Regex("\\.(jadeSymbols|jadeLedger)\\s*=")

    /**
     * 白名单（SS9 分界）：JadeSymbolService.kt（玩法写唯一入口——回退臂
     * appendLedger 的账本条目 + 派生缓存双写为既定实现）+ GameDataFieldPatch.kt
     * （镜像协议面字段级应用，与 C++ 真相源同步语义）。按相对路径匹配（避免
     * 任意包下同名文件被静默豁免），并有存在性断言（文件被删除/改名时守卫
     * 测试失败，防止白名单悬空）。
     */
    private val allowedFiles = setOf(
        "com" + File.separator + "xianxia" + File.separator + "sect" + File.separator +
            "core" + File.separator + "engine" + File.separator + "service" + File.separator +
            "JadeSymbolService.kt",
        // 镜像协议面字段级应用（R2.3 第二波）：C++ 真相源 → Kotlin 镜像的合法写入面，
        // 与旧「整份 GameData JSON 往返」对 jadeSymbols 的覆写逐项同语义；
        // 本豁免只覆盖 gameview 字段表的机械写入，玩法侧扣减/发放仍必须走服务。
        "com" + File.separator + "xianxia" + File.separator + "sect" + File.separator +
            "core" + File.separator + "gameview" + File.separator + "GameDataFieldPatch.kt"
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
                "\njadeSymbols 是玉符账本的派生缓存——独立赋值即与账本漂移（账本以" +
                "末条 balance_after 为准重锚，缓存写入无效）。\n" +
                "消耗必须事务内调用 JadeSymbolService.deduct(state, cost, reason)" +
                "（Insufficient 三态 + 不消耗 RNG 序列），发放必须走服务内部结算。",
            emptyList<Pair<File, String>>(), matches
        )
    }

    @Test
    fun `no jadeLedger copy-write outside JadeSymbolService`() {
        val files = sourceFiles().filter { relativePath(it) !in allowedFiles }
        val matches = matchesIn(files, copyLedgerPattern)
        assertEquals(
            "发现 ${matches.size} 处直接 copy 修改 GameData.jadeLedger：\n" +
                matches.joinToString("\n") { it.second } +
                "\njadeLedger 是玉符余额真源（append-only 流水）——绕过落账入口整表" +
                "替换即破坏「派生余额 == 期初 + Σdelta」不变式（条目缺失 = 黑洞，" +
                "伪造条目 = 凭空发放）。\n" +
                "消耗走 JadeSymbolService.deduct(state, cost, reason)，发放走" +
                "grantFromAd / settleGrants（native 臂落 C++ 账本）。",
            emptyList<Pair<File, String>>(), matches
        )
    }

    @Test
    fun `no direct assignment to jade balance fields`() {
        val files = sourceFiles().filter { relativePath(it) !in allowedFiles }
        val matches = matchesIn(files, directAssignPattern)
        assertEquals(
            "发现 ${matches.size} 处直接属性赋值修改玉符余额面（jadeSymbols/jadeLedger）：\n" +
                matches.joinToString("\n") { it.second } +
                "\n两字段为 var（data class copy 是唯一途径）——若此模式出现，" +
                "说明字段面被绕过或 var 化，必须回退并委托 JadeSymbolService。",
            emptyList<Pair<File, String>>(), matches
        )
    }
}
