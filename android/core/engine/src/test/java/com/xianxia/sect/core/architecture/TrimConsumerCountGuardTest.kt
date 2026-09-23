package com.xianxia.sect.core.architecture

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 守卫测试：trim 消费者收敛计数（MR1-P1.3/D3 防复发门禁）。
 *
 * ## 存在理由
 * 收敛前生产 `onTrimMemory`/`onLowMemory` 的**游戏内存消费者有四路并行**：
 * `XianxiaApplication.notifyMemoryPressure` 广播、`CacheLayer`/GameDataCacheManager
 * 自注册 `ComponentCallbacks2`、`GameActivity.onTrimMemory` 分支、
 * `GameLoopDelegate.onMemoryPressure`（死入口）。MR1-P1.3 收敛后唯一消费者 =
 * **TrimMemoryBridge**，系统回调唯一入口 = `XianxiaApplication.onTrimMemory`。
 *
 * 若本守卫变红 = 有人重新打开旁路 trim 消费者（第四/第五条压力路径回潮）
 * ——**禁止**新增 `onTrimMemory`/`onLowMemory` override 或自注册
 * `ComponentCallbacks2`；动作面请注册到 `TrimMemoryBridge`（app 模块）。
 *
 * ## 判据（六模块主源）
 * - `override fun onTrimMemory` / `override fun onLowMemory`：
 *   仅 `XianxiaApplication.kt` 各 1 处；
 * - `registerComponentCallbacks`：零命中（CacheLayer 自注册已删除）；
 * - `MemoryPressureListener` / `onMemoryPressure`：零命中（广播机制与
 *   死入口已随收敛删除）。
 */
class TrimConsumerCountGuardTest {

    /** 模块主源根目录（Gradle 测试工作目录 = android/core/engine，故用相对路径） */
    private val moduleMainDirs: List<Pair<String, File>> = listOf(
        "core/domain" to File(mainSourcePath("core", "domain")),
        "core/engine" to File(mainSourcePath("core", "engine")),
        "core/data" to File(mainSourcePath("core", "data")),
        "core/ui" to File(mainSourcePath("core", "ui")),
        "feature/game" to File(mainSourcePath("feature", "game")),
        "app" to File(mainSourcePath("app"))
    )

    @Test
    fun `系统 trim 回调唯一入口为 XianxiaApplication`() {
        val onTrimHits = mutableListOf<String>()
        val onLowHits = mutableListOf<String>()
        for ((moduleName, dir) in moduleMainDirs) {
            assertTrue("模块主源目录不存在: $moduleName -> $dir", dir.isDirectory)
            dir.walkTopDown()
                .filter { it.isFile && it.extension in setOf("kt", "java") }
                .forEach { file ->
                    file.useLines { lines ->
                        lines.forEachIndexed { idx, line ->
                            if (line.contains("override fun onTrimMemory")) {
                                onTrimHits += "$moduleName | ${file.relativeTo(dir)}:${idx + 1}"
                            }
                            if (line.contains("override fun onLowMemory")) {
                                onLowHits += "$moduleName | ${file.relativeTo(dir)}:${idx + 1}"
                            }
                        }
                    }
                }
        }
        assertTrue(
            "onTrimMemory override 应仅存在于 XianxiaApplication.kt（系统回调唯一入口）——\n" + onTrimHits,
            onTrimHits.size == 1 && onTrimHits[0].contains("XianxiaApplication.kt")
        )
        assertTrue(
            "onLowMemory override 应仅存在于 XianxiaApplication.kt——\n" + onLowHits,
            onLowHits.size == 1 && onLowHits[0].contains("XianxiaApplication.kt")
        )
    }

    @Test
    fun `主源零自注册 ComponentCallbacks 与零旁路 trim 入口`() {
        // 代码模式（注释中的历史说明不触发——精确匹配声明/调用语法）
        val forbidden = listOf(
            Regex("""registerComponentCallbacks\("""),
            Regex("""unregisterComponentCallbacks\("""),
            Regex("""[:,]\s*ComponentCallbacks2\s*\{"""),          // implements ComponentCallbacks2
            Regex("""fun (un)?registerMemoryPressureListener\("""),
            Regex(""":\s*MemoryPressureListener"""),               // 接口实现声明
            Regex("""fun onMemoryPressure\(""")                    // GameLoopDelegate 死入口形态
        )
        val violations = mutableListOf<String>()
        for ((moduleName, dir) in moduleMainDirs) {
            dir.walkTopDown()
                .filter { it.isFile && it.extension in setOf("kt", "java") }
                .forEach { file ->
                    file.useLines { lines ->
                        lines.forEachIndexed { idx, line ->
                            forbidden.firstOrNull { it.containsMatchIn(line) }?.let { sym ->
                                val loc = "${file.relativeTo(dir)}:${idx + 1}"
                                violations += "$moduleName | $loc | ${sym.pattern} | ${line.trim()}"
                            }
                        }
                    }
                }
        }
        assertTrue(
            "trim 收敛防复发：主源不得再出现旁路 trim 消费者声明/调用——\n" +
                "动作面请注册 TrimMemoryBridge（app/core/memory）；\n" +
                violations.joinToString("\n"),
            violations.isEmpty()
        )
    }

    private fun mainSourcePath(vararg segments: String): String = (
        listOf("..", "..") + segments + listOf("src", "main")
        ).joinToString(File.separator)
}
