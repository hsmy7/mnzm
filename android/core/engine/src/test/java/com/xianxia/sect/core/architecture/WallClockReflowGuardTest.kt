package com.xianxia.sect.core.architecture

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SR-5 C6：墙钟零回流静态守卫。
 *
 * 三条判据（沿用 [RngSourceGuardTest] 的扫描基建纪律：只扫 `src/main`、注释剔除、
 * 源目录不可达即红而非 skip）：
 *
 * 1. **收敛清单内零裸墙钟**——SR-5 把游戏语义族（日额/周冷却/兑换码限流/邮件过期）
 *    的取时改经注入 [com.xianxia.sect.core.engine.system.WallClock]，清单内文件
 *    再出现 `System.currentTimeMillis` 即为回流；
 * 2. **清单规模锁死**——新增/删除条目必须改本文件常量，评审可见；
 * 3. **六模块裸钟计数只缩不增**——族外文件（日志/看门狗/缓存 TTL/RNG 播种/
 *    存档时间戳盖写等）本批不动，但总数锁为精确值：变多即红，变少必须同步下调
 *    登记值（与 `detekt-baseline-count.guard` 同纪律，CLAUDE.md 13.2）。
 *
 * 为何不扫 `Calendar.getInstance()`：`JadeSymbolService.getTodayStartMs` 用它做
 * **本地时区零点化**（`timeInMillis` 由入参显式赋值），不读系统钟，纳入判据会误报。
 */
class WallClockReflowGuardTest {

    // ==================== 判据 1：收敛清单零回流 ====================

    @Test
    fun `SR5 收敛清单内不得再出现裸墙钟读取`() {
        val violations = CONVERGED_FILES.flatMap { relative ->
            val file = File(repoRoot, relative)
            if (!file.isFile) {
                throw AssertionError(
                    "收敛清单条目已失效（文件不存在或已搬迁）：$relative —— " +
                        "请同步更新本守卫清单，不得直接删判据"
                )
            }
            stripComments(file.readLines()).mapIndexedNotNull { index, code ->
                if (RAW_WALL_CLOCK.containsMatchIn(code)) {
                    "$relative:${index + 1}: $code"
                } else null
            }
        }
        assertTrue(
            "SR-5 已把这些文件的取时改为注入 WallClock，出现裸读即判据分歧/回流：\n" +
                violations.joinToString("\n") +
                "\n处置：改用构造注入的 WallClock（或按 MailDao 先例把 now 作形参下传），" +
                "不要在源码里直接取系统钟。",
            violations.isEmpty()
        )
    }

    // ==================== 判据 2：清单规模锁死 ====================

    @Test
    fun `收敛清单条目数锁死`() {
        assertEquals(
            "增删收敛清单条目须同步改 CONVERGED_FILE_COUNT（放宽豁免 = 守卫变弱，必须显式声明）",
            CONVERGED_FILE_COUNT,
            CONVERGED_FILES.size
        )
    }

    // ==================== 判据 3：模块级只缩不增 ====================

    @Test
    fun `六模块裸墙钟计数与登记值精确一致`() {
        val drift = StringBuilder()
        moduleMainDirs.forEach { (module, dir) ->
            if (!dir.isDirectory) {
                throw AssertionError(
                    "模块主源目录不可达（Gradle 工作目录应为 android/core/engine）：${dir.absolutePath}"
                )
            }
            val actual = countRawWallClock(dir)
            val registered = REGISTERED_RAW_COUNTS.getValue(module)
            when {
                actual > registered -> drift.append(
                    "\n[$module] 实测 $actual > 登记 $registered（增长 +${actual - registered}）" +
                        "——SR-5 判据：游戏语义取时必须走注入 WallClock。" +
                        "instrumentation/看门狗类新读数请单独立项登记后再调整本值，勿顺手放宽。"
                )
                actual < registered -> drift.append(
                    "\n[$module] 实测 $actual < 登记 $registered（减少 ${registered - actual}）" +
                        "——债务已下降，请把 REGISTERED_RAW_COUNTS 的 $module 下调为 $actual（只缩不增纪律）。"
                )
            }
        }
        assertTrue(
            "模块级裸墙钟计数漂移：$drift",
            drift.isEmpty()
        )
    }

    // ==================== 扫描基建 ====================

    /** 模块主源路径（Gradle 工作目录 = android/core/engine） */
    private fun mainSourcePath(vararg segments: String): String = (
        listOf("..", "..") + segments + listOf("src", "main")
        ).joinToString(File.separator)

    /** 扫描根（用于产出可读相对路径） */
    private val repoRoot: File = File(".." + File.separator + "..")

    private val moduleMainDirs: Map<String, File> get() = mapOf(
        "core:engine" to File(mainSourcePath("core", "engine")),
        "core:data" to File(mainSourcePath("core", "data")),
        "core:domain" to File(mainSourcePath("core", "domain")),
        "core:ui" to File(mainSourcePath("core", "ui")),
        "feature:game" to File(mainSourcePath("feature", "game")),
        "app" to File(mainSourcePath("app"))
    )

    private fun countRawWallClock(dir: File): Int = dir.walkTopDown()
        .filter { it.isFile && it.extension == "kt" }
        .sumOf { file ->
            val relative = relativePath(file)
            // 收敛清单按判据 1 必须为 0；抽象本体是仓内唯一被许可的取时钟点
            if (relative in CONVERGED_FILES || relative in ABSTRACTION_FILES) 0
            else stripComments(file.readLines()).count { RAW_WALL_CLOCK.containsMatchIn(it) }
        }

    /**
     * 归一化相对路径（`File.separatorChar` → `/`）。
     *
     * 🔴 必须归一：Windows 上 `File.relativeTo(...).path` 用反斜杠，与清单里的
     * `/` 路径永不相等 ⇒ 排除清单**静默失效**、债务计数被抽象本体的合法裸读污染
     * （首轮组合门即因此判红，实测取证于 report-SR5 §6）。
     */
    private fun relativePath(file: File): String =
        file.relativeTo(repoRoot).path.replace(File.separatorChar, '/')

    /**
     * 剔除注释（行注释 + 块注释，含 KDoc），保留行号。
     *
     * 必须剔除：本批起大量文件在注释里**引用** `System.currentTimeMillis`
     * （收敛说明/字段口径注释），不剔除则"改注释即改守卫"。
     */
    private fun stripComments(lines: List<String>): List<String> {
        val out = ArrayList<String>(lines.size)
        var inBlockComment = false
        for (raw in lines) {
            val sb = StringBuilder()
            var i = 0
            while (i < raw.length) {
                val c = raw[i]
                val next = raw.getOrNull(i + 1)
                when {
                    inBlockComment && c == '*' && next == '/' -> { inBlockComment = false; i += 2 }
                    inBlockComment -> i++
                    c == '/' && next == '/' -> i = raw.length
                    c == '/' && next == '*' -> { inBlockComment = true; i += 2 }
                    else -> { sb.append(c); i++ }
                }
            }
            out.add(sb.toString().trim())
        }
        return out
    }

    private companion object {
        val RAW_WALL_CLOCK = Regex("""System\.currentTimeMillis""")

        /** 被许可的取时抽象（实现本体必须裸读系统钟，不计债务也不受判据 1 约束）。 */
        val ABSTRACTION_FILES = listOf(
            "core/engine/src/main/java/com/xianxia/sect/core/engine/system/WallClock.kt"
        )

        /** SR-5 收敛清单（相对 android/ 根），规模由 [CONVERGED_FILE_COUNT] 锁死。 */
        val CONVERGED_FILES = listOf(
            // C2 玉符日额（抽象搬迁后判据不变）
            "core/engine/src/main/java/com/xianxia/sect/core/engine/service/JadeSymbolService.kt",
            // C3 周冷却（引擎闸门 + UI 徽章 + 纯判据）
            "core/engine/src/main/java/com/xianxia/sect/core/engine/GameEngineSectLevelOps.kt",
            "core/domain/src/main/java/com/xianxia/sect/core/config/SectLevelRewardCooldown.kt",
            // C4 兑换码限流族
            "core/engine/src/main/java/com/xianxia/sect/core/engine/RedeemCodeManager.kt",
            "core/engine/src/main/java/com/xianxia/sect/core/engine/RedeemCodeRateLimitOps.kt",
            "core/engine/src/main/java/com/xianxia/sect/core/engine/RedeemCodeLifecycleOps.kt",
            "core/engine/src/main/java/com/xianxia/sect/core/engine/service/RedeemCodeService.kt",
            // C5 邮件链（含 Room 事务内绕行点与秘境关闭邮件）
            "core/engine/src/main/java/com/xianxia/sect/core/engine/service/MailService.kt",
            "core/engine/src/main/java/com/xianxia/sect/core/engine/service/MailAttachmentDistributeOps.kt",
            "core/engine/src/main/java/com/xianxia/sect/core/engine/service/OverflowMailSender.kt",
            "core/engine/src/main/java/com/xianxia/sect/core/engine/service/SecretRealmService.kt",
            "core/data/src/main/java/com/xianxia/sect/data/local/MailDao.kt",
            "app/src/main/java/com/xianxia/sect/di/MailRepositoryImpl.kt",
            // C5 UI 侧（周奖励徽章与过期文案）
            "feature/game/src/main/java/com/xianxia/sect/ui/game/GameViewModel.kt",
            "feature/game/src/main/java/com/xianxia/sect/ui/game/dialogs/MailDialog.kt"
        )

        const val CONVERGED_FILE_COUNT = 15

        /**
         * 模块级裸墙钟登记值（**精确**：多了判红，少了也判红并要求下调）。
         * 数字 = SR-5 收官时对 `src/main` 逐文件实扫所得（注释已剔除，
         * 且排除上方两份清单）。构成与"为何本批不动"见
         * `docs/parallel-batches-w5/report-SR5-completion-2026-09-22.md` §5。
         */
        val REGISTERED_RAW_COUNTS = mapOf(
            "core:engine" to 40,
            "core:data" to 68,
            "core:domain" to 9,
            "core:ui" to 0,
            "feature:game" to 25,
            "app" to 26
        )
    }
}
