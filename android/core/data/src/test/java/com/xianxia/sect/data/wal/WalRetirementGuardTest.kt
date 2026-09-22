package com.xianxia.sect.data.wal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * `FunctionalWAL` 退役面静态守卫（SR-7 C5）。
 *
 * 方案 §4 SR-7："`FunctionalWAL` 先摘调用点再删组件"（remediation 勘误：它是活的，
 * 不能照审计的"死代码"清单直接删）。本批做的是**按模式摘除**：CLOUD_ONLY 下不再开
 * 文件型事务日志，LEGACY / CLOUD_TRANSITION 逐位不变；组件本体留到文件层物理清理批。
 *
 * 三条例子各自的失败模式都是"改错了不会别处红"：
 * 1. 调用点总数漂移锁——新增/删除 `core.wal.` 调用点必须同时改本守卫（防"偷偷多开一处
 *    未受门控的 WAL 写"）；实测调用点为 **6** 处（方案 §4 写 5 处，勘察已纠正）；
 * 2. 两处"开/扫"入口必须受 `writesLocalSaveFiles` 门控（把 `if` 去掉即红）；
 * 3. 其余调用点（commit/abort/abortSync/shutdown）保持**不**门控是刻意的：它们要么被
 *    既有 `txnId != null` 守卫自动短路，要么是拆除路径——本条只锁"未门控者清单"，
 *    免将来把 shutdown 也顺手 gate 掉导致句柄泄漏。
 *
 * 源目录不可达一律 AssertionError，不得静默跳过（同 SR-5/SR-6 守卫纪律）。
 */
class WalRetirementGuardTest {

    private companion object {
        val ENGINE = "core/data/src/main/java/com/xianxia/sect/data/engine/StorageEngine.kt"
        val SAVE_SUPPORT = "core/data/src/main/java/com/xianxia/sect/data/engine/StorageEngineSaveSupport.kt"
        val MODULES = listOf("app", "core/data", "core/domain", "core/engine", "core/ui", "feature/game")

        /** 实测调用点清单（文件 → 次数），总数 6 */
        val CALL_SITES = mapOf(ENGINE to 4, SAVE_SUPPORT to 2)

        val WalCallPattern = Regex("""core\.wal\.\w+\(""")
    }

    @Test
    fun `WAL 调用点总数与分布锁定（方案字面 5 处已实测纠正为 6 处）`() {
        val root = androidRoot()
        val actual: Map<String, Int> = CALL_SITES.keys.associateWith { relative ->
            WalCallPattern.findAll(File(root, relative).readNormalized()).count()
        }
        assertEquals("core.wal.* 调用点分布变了——同步本守卫前先确认新调用点是否受门控", CALL_SITES, actual)
        assertEquals(6, CALL_SITES.values.sum())
    }

    @Test
    fun `WAL 的开启与扫描入口都在文件层门控内`() {
        val engine = File(androidRoot(), ENGINE).readNormalized()
        gatedCall(engine, "fun startMaintenance", "core.wal.recover(")
        gatedCall(engine, "private suspend fun performFullTransactionSave", "core.wal.beginTransaction(")
    }

    @Test
    fun `门控判据本体只此一份（不得另起炉灶造第二个文件层判据）`() {
        val root = androidRoot()
        val users = mainKotlinFiles(root).filter {
            Regex("""writesLocalSaveFiles""").containsMatchIn(it.readText())
        }.map { it.relativePath(root) }
        assertEquals(
            "文件层写侧判据的消费面必须是 StorageEngine 本体 + 其两个扩展文件；" +
                "多出一处 = 有人绕开纯函数自己判模式",
            listOf(
                "core/data/src/main/java/com/xianxia/sect/data/engine/StorageEngine.kt",
                "core/data/src/main/java/com/xianxia/sect/data/engine/StorageEngineSaveSupport.kt"
            ).sorted(),
            users.sorted()
        )
    }

    /**
     * 读源码并归一行尾：本仓工作树文件是 CRLF，`substringBefore("\n    }\n")` 这类
     * 边界切分在 CRLF 下会失配并**静默返回全文**（守卫随即退化成"整文件里有没有 if"），
     * 因此归一是判别力的前提，不是风格问题。
     */
    private fun File.readNormalized(): String =
        readText().replace("\r\n", "\n").replace("\r", "\n")

    /** 断言 [call] 落在 [functionName] 体内、且函数体里出现过文件层门控 */
    private fun gatedCall(text: String, functionName: String, call: String) {
        val tail = text.substringAfter(functionName)
        val body = tail.substringBefore("\n    }\n")
        assertTrue(
            "$functionName 的函数体切分失败（拿到全文说明边界失配，守卫会退化成空转）",
            body.length < tail.length
        )
        assertTrue("$functionName 里找不到 $call（改名/搬移须同步本守卫）", call in body)
        assertTrue(
            "$call 必须在 writesLocalSaveFiles 门控内（CLOUD_ONLY 停写文件层）",
            body.contains("if (writesLocalSaveFiles)")
        )
        assertTrue(
            "门控必须在调用之前（写在 if 之后才算被门控）",
            body.indexOf("if (writesLocalSaveFiles)") < body.indexOf(call)
        )
    }

    // ── 源面解析（同 SaveMigrationGuardTest / SecureKeyChainGuardTest 口径）──

    private fun androidRoot(): File {
        var dir: File? = File(System.getProperty("user.dir"))
        while (dir != null) {
            val candidate = if (dir.name == "android") dir else File(dir, "android")
            if (MODULES.all { File(candidate, "$it/src/main").isDirectory }) return candidate
            dir = dir.parentFile
        }
        throw AssertionError("定位不到 android 源根（user.dir=${System.getProperty("user.dir")}）——守卫不得静默跳过")
    }

    private fun mainKotlinFiles(root: File): List<File> = MODULES.flatMap { module ->
        File(root, "$module/src/main").walkTopDown().filter { it.isFile && it.name.endsWith(".kt") }.toList()
    }

    private fun File.relativePath(root: File): String =
        relativeTo(root).path.replace(File.separatorChar, '/')
}
