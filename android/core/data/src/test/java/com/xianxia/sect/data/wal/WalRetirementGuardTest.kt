package com.xianxia.sect.data.wal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * `FunctionalWAL` 退役面守卫（SS3-b：整组摘除后的零残留锁）。
 *
 * 应用级事务 WAL 组件（`FunctionalWAL` / `WALProvider` / 条目编解码）已整体退役，
 * 事务编排职责由 Room 事务（`database.withTransaction`）承担。本守卫把"退役面零残留"
 * 钉成 CI 红线，各自的失败模式都是"复活不会别处红"：
 *
 * 1. **生产源码符号面零残留**——六模块 `src/main` 不得出现任何 WAL 组件符号
 *    （类名/接口名/条目类型/常量名/`core.wal.` 调用形态）；任何一处复活即红；
 * 2. **wal 包目录不得回归**——`data/wal/` 目录退役后不得重建（空目录/新文件都算）；
 * 3. **StorageConstants 只留孤儿清理定位常量**——`WAL_DIR_NAME` 仅允许被
 *    `DataPruningScheduler` 的 legacy 目录清理消费，不得有第二个消费者；
 * 4. **文件层写侧判据本体只此一份**——`writesLocalSaveFiles`（`.sav`/`.bak`/
 *    tombstone 写判据，与 WAL 无关但在同一退役面上）的消费面必须仍是 StorageEngine
 *    本体 + 其保存支撑扩展文件，防止有人绕开纯函数自己判模式。
 *
 * 源目录不可达一律 AssertionError，不得静默跳过（同 SR-5/SR-6 守卫纪律）。
 */
class WalRetirementGuardTest {

    private companion object {
        val MODULES = listOf("app", "core/data", "core/domain", "core/engine", "core/ui", "feature/game")

        const val STORAGE_CONSTANTS = "core/data/src/main/java/com/xianxia/sect/data/StorageConstants.kt"
        const val PRUNING_SCHEDULER = "core/data/src/main/java/com/xianxia/sect/data/engine/DataPruningScheduler.kt"
        const val SAVE_SUPPORT = "core/data/src/main/java/com/xianxia/sect/data/engine/StorageEngineSaveSupport.kt"

        /** WAL 组件符号面：任一出现在生产源码即复活 */
        val RETIRED_SYMBOLS = Regex(
            """\bFunctionalWAL\b|\bWALProvider\b|\bWALEntryType\b|\bWAL_FILE_NAME\b|""" +
                """\bMAX_WAL_SIZE_BYTES\b|\bCHECKPOINT_INTERVAL\b|core\.wal\."""
        )
    }

    @Test
    fun `生产源码零 WAL 组件符号残留`() {
        val root = androidRoot()
        val offenders = MODULES.flatMap { module ->
            File(root, "$module/src/main").walkTopDown()
                .filter { it.isFile && it.name.endsWith(".kt") }
                .filter { RETIRED_SYMBOLS.containsMatchIn(it.readText()) }
                .map { it.relativePath(root) }
        }
        assertTrue(
            "WAL 组件符号在生产源码复活（事务编排职责归 Room 事务，禁止重建应用级 WAL）: $offenders",
            offenders.isEmpty()
        )
    }

    @Test
    fun `wal 包目录不得回归`() {
        val walDir = File(androidRoot(), "core/data/src/main/java/com/xianxia/sect/data/wal")
        assertFalse(
            "data/wal/ 包目录已随组件整组退役，不得重建（新持久化设施落 Room 事务或既有包）",
            walDir.exists()
        )
    }

    @Test
    fun `WAL_DIR_NAME 唯一消费者是孤儿目录清理`() {
        val root = androidRoot()
        val users = MODULES.flatMap { module ->
            File(root, "$module/src/main").walkTopDown()
                .filter { it.isFile && it.name.endsWith(".kt") }
                .filter { it.readText().contains("WAL_DIR_NAME") }
                .map { it.relativePath(root) }
        }
        assertEquals(
            "WAL_DIR_NAME 仅作 legacy wal_v4 孤儿目录清理的定位常量——出现新消费者 = " +
                "在重建 WAL 落点，须改走 Room 事务或登记新持久化通道",
            listOf(PRUNING_SCHEDULER, STORAGE_CONSTANTS).sorted(),
            users.sorted()
        )
        val pruning = File(root, PRUNING_SCHEDULER).readText()
        assertTrue(
            "DataPruningScheduler 必须保留 legacy wal_v4 孤儿目录清理（升级残留不清理 = 设备级垃圾长期滞留）",
            pruning.contains("WAL_DIR_NAME")
        )
    }

    @Test
    fun `文件层写侧判据本体只此一份（不得另起炉灶造第二个判据）`() {
        val root = androidRoot()
        val users = mainKotlinFiles(root).filter {
            Regex("""writesLocalSaveFiles""").containsMatchIn(it.readText())
        }.map { it.relativePath(root) }
        assertEquals(
            "文件层写侧判据的消费面必须是 StorageEngine 本体 + 其保存支撑扩展文件；" +
                "多出一处 = 有人绕开纯函数自己判模式",
            listOf(
                "core/data/src/main/java/com/xianxia/sect/data/engine/StorageEngine.kt",
                SAVE_SUPPORT
            ).sorted(),
            users.sorted()
        )
    }

    // ── 源面解析（同 SecureKeyChainGuardTest 口径）──

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
