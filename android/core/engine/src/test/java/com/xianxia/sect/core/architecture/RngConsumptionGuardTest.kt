package com.xianxia.sect.core.architecture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 守卫测试：游戏分区 RNG（[com.xianxia.sect.core.util.GameRngManager.getRng]）
 * 的批量消费只能发生在 :core:engine；UI 侧模块（:app / :feature:game）
 * 仅允许"进入场景时单次取种子"这一受控操作。
 *
 * 确定性设计（P0-1b，对标 Brogue 玩法/装饰 RNG 分离）：
 * - 引擎线程是 RNG 唯一批量消费线程（P0-1 事务快照/恢复的线程安全前提）
 * - UI 若在模拟循环中消费 getRng 会推进全局分区——模拟次数/时机的变化会使
 *   引擎侧随机序列分叉，读档重放不可复现（行业教训：装饰性 RNG 调用破坏
 *   全局状态的灾难案例——渲染粒子多消费一次随机数导致全部存档失效）
 *
 * UI 展示型随机（如天道试炼战斗模拟）使用本地 [com.xianxia.sect.core.util.DeterministicRng]：
 * 进入战斗时从全局分区取**一次**种子（beginCombat），此后模拟完全本地化。
 * 单次取种子是确定性契约的一部分（种子必须来自全局分区，否则无法重放），
 * [DeterministicRng.nextLong] 为 @Synchronized 原子操作，并发安全。
 */
class RngConsumptionGuardTest {

    // Gradle 测试工作目录为模块目录（android/core/engine）
    private val uiSourceDirs = listOf(
        File(
            ".." + File.separator + ".." + File.separator + "app" + File.separator +
                "src" + File.separator + "main" + File.separator + "java"
        ),
        File(
            ".." + File.separator + ".." + File.separator + "feature" + File.separator +
                "game" + File.separator + "src" + File.separator + "main" + File.separator + "java"
        )
    )

    private val getRngPattern = Regex("\\bgetRng\\s*\\(")

    /**
     * 允许取种子的文件白名单：UI 侧每文件最多 1 处 `getRng(...).nextLong()`，
     * 用于创建本地展示型 PRNG（天道试炼 beginCombat 模式）。
     * 新增此类入口必须符合"单次取种子"语义，不得在此白名单中扩展批量消费。
     */
    private val seedAllowedFileNames = setOf("HeavenlyTrialViewModel.kt")

    private fun ktFiles(dir: File): List<File> =
        dir.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()

    private fun matchesIn(files: List<File>): List<Pair<File, String>> =
        files.flatMap { file ->
            file.readLines().mapIndexedNotNull { index, line ->
                if (getRngPattern.containsMatchIn(line)) {
                    file to "${file.name}:${index + 1}: ${line.trim()}"
                } else {
                    null
                }
            }
        }

    @Test
    fun `UI modules have no direct RNG consumption outside whitelist`() {
        uiSourceDirs.forEach { dir ->
            assertTrue(
                "UI 模块源码目录不可达（Gradle 工作目录应为 android/core/engine）：${dir.absolutePath}",
                dir.isDirectory
            )
        }
        val outsideWhitelist = matchesIn(ktFiles(uiSourceDirs[0]) + ktFiles(uiSourceDirs[1]))
            .filter { (file, _) -> file.name !in seedAllowedFileNames }
        assertEquals(
            "UI 模块非白名单文件发现 ${outsideWhitelist.size} 处 getRng 调用：\n" +
                outsideWhitelist.joinToString("\n") { it.second } +
                "\n引擎线程是 RNG 唯一批量消费线程。UI 展示型随机应改用本地 DeterministicRng：" +
                "进入场景时取一次种子创建本地实例（参照天道试炼 beginCombat 模式）",
            emptyList<Pair<File, String>>(), outsideWhitelist
        )
    }

    @Test
    fun `whitelist files have at most one seed extraction`() {
        uiSourceDirs.forEach { dir -> assertTrue(dir.isDirectory) }
        val allMatches = matchesIn(ktFiles(uiSourceDirs[0]) + ktFiles(uiSourceDirs[1]))
        seedAllowedFileNames.forEach { name ->
            val count = allMatches.count { it.first.name == name }
            assertTrue(
                "白名单文件 $name 出现 $count 处 getRng 调用（允许最多 1 处取种子）：\n" +
                    allMatches.filter { it.first.name == name }.joinToString("\n") { it.second } +
                    "\n取种子只允许发生在进入战斗/场景的离散操作点，模拟循环中任何消费都必须走本地实例",
                count <= 1
            )
        }
    }
}
