package com.xianxia.sect.core.architecture

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ResidualExecutorPurityGuardTest — R2.4/B09「残留执行器退化」静态守卫。
 *
 * 红线（batch-R2D.md）：残留执行器退化为**纯平台效应适配器**（发邮件/写
 * Room/写瞬态列/通知），不再解析 JSON——"平台效应（邮件内容/Room 写入/通知）
 * 逐字段等价，仅剥离 JSON 解析"。本守卫静态固化：
 *
 * 1. **执行器源零 JSON 解析**：月/年结算残留执行器源文件对 JSON 解析面
 *    符号（kotlinx Json 构造/解析原语 + 手工导航原语）**零命中**——执行器
 *    输入恒为 typed 草稿（proto eventFeed 组装面 / 登记回滚臂解析面产出）；
 * 2. **信封 JSON 解析面收敛于登记边界**：`parseMonthSettlementEnvelope` /
 *    `parseYearSettlementEnvelope`（R2.4 起回滚臂专用）各只存在于其定义
 *    文件（定义 + 唯一回退调用点同文件）——未登记的第三消费入口长出即红。
 */
class ResidualExecutorPurityGuardTest {

    /** 六模块主源根目录（Gradle 测试工作目录 = android/core/engine，故用相对路径） */
    private val moduleMainDirs: List<Pair<String, File>> = listOf(
        "core/domain" to File(mainSourcePath("core", "domain")),
        "core/engine" to File(mainSourcePath("core", "engine")),
        "core/data" to File(mainSourcePath("core", "data")),
        "core/ui" to File(mainSourcePath("core", "ui")),
        "feature/game" to File(mainSourcePath("feature", "game")),
        "app" to File(mainSourcePath("app"))
    )

    @Test
    fun `残留执行器源零 JSON 解析（纯平台效应适配器）`() {
        EXECUTOR_FILES.forEach { file ->
            val source = readMainFile(file)
            JSON_PARSE_SYMBOLS.forEach { symbol ->
                val hits = source.lines()
                    .mapIndexedNotNull { idx, line ->
                        if (symbol.containsMatchIn(line)) "${file}:${idx + 1}" else null
                    }
                assertTrue(
                    "残留执行器必须是纯平台效应适配器（R2.4 红线）：$file 出现 JSON 解析符号 " +
                        "${symbol.pattern}：\n" + hits.joinToString("\n"),
                    hits.isEmpty(),
                )
            }
        }
    }

    @Test
    fun `信封 JSON 解析面收敛于登记的回滚臂边界`() {
        PARSER_BOUNDARIES.forEach { (parser, allowedFile) ->
            val hits = kotlinHits(Regex(Regex.escape(parser)))
            assertEquals(
                "信封 JSON 解析「$parser」的消费面必须收敛于登记边界（R2.4：生产输入 = " +
                    "proto eventFeed typed 事件；JSON 解析仅回滚臂保留，删除随回滚臂移除批次）：\n" +
                    hits.joinToString("\n"),
                listOf(allowedFile),
                hits.paths,
            )
        }
    }

    // ── 扫描工具（与 MirrorConsumerSurfaceGuardTest 同模式）──────────

    private fun kotlinHits(pattern: Regex): List<String> =
        moduleMainDirs.flatMap { (moduleName, dir) -> scan(dir, moduleName, pattern) }.sorted()

    /** 命中记为 `模块:相对路径:行号`，比对时仅取路径部分（行号随编辑漂移）。 */
    private fun scan(dir: File, moduleName: String, pattern: Regex): List<String> {
        assertTrue("模块主源目录不存在: $moduleName -> $dir", dir.isDirectory)
        return dir.walkTopDown()
            .filter { it.isFile && it.extension in setOf("kt", "java") }
            .flatMap { file ->
                val path = "$moduleName:${file.relativeTo(dir).invariantSeparatorsPath}"
                file.readLines().mapIndexedNotNull { idx, line ->
                    if (pattern.containsMatchIn(line)) "$path:${idx + 1}" else null
                }
            }
            .toList()
    }

    private fun readMainFile(key: String): String {
        val (moduleName, relative) = key.split(':', limit = 2)
        val dir = moduleMainDirs.first { it.first == moduleName }.second
        return File(dir, relative).readText()
    }

    private fun mainSourcePath(vararg segments: String): String = (
        listOf("..", "..") + segments + listOf("src", "main")
        ).joinToString(File.separator)

    private val List<String>.paths: List<String>
        get() = map { it.substringBeforeLast(':') }

    companion object {
        /** 残留执行器源（纯平台效应适配器面） */
        private val EXECUTOR_FILES = listOf(
            "core/engine:java/com/xianxia/sect/core/engine/service/MonthSettlementResidualExecutor.kt",
            "core/engine:java/com/xianxia/sect/core/engine/service/YearSettlementResidualExecutor.kt",
        )

        /**
         * JSON 解析面符号（执行器源零命中）：Json 构造/伴生调用 + kotlinx
         * 解码原语 + 手工导航原语。注释里的 "JSON"（全大写单词）不匹配
         * `\bJson\b`（大小写敏感）——KDoc 可继续描述契约。
         */
        private val JSON_PARSE_SYMBOLS = listOf(
            Regex("""\bJson\s*[({.]"""),
            Regex("""parseToJsonElement"""),
            Regex("""decodeFromJsonElement"""),
            Regex("""decodeFromString"""),
            Regex("""\.jsonObject|\.jsonArray|\.jsonPrimitive"""),
            Regex("""\bJsonElement\b|\bJsonObject\b|\bJsonArray\b|\bJsonPrimitive\b"""),
        )

        /** 信封解析函数 → 登记边界（定义文件，回退调用点同文件） */
        private val PARSER_BOUNDARIES = listOf(
            "fun parseMonthSettlementEnvelope(" to
                "core/engine:java/com/xianxia/sect/core/engine/GameEngineCoreMonthOps.kt",
            "fun parseYearSettlementEnvelope(" to
                "core/engine:java/com/xianxia/sect/core/engine/GameEngineCoreYearOps.kt",
        )
    }
}
