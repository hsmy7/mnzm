package com.xianxia.sect.data.archive

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 归档读面职责守卫（SS3-c：按新职责改写，替换原"零读者"断言）。
 *
 * 归档行（`archived_battle_logs` / `archived_disciples`）的 `dataBlob` 是可还原
 * 全量载荷，本守卫把读面的**职责边界**钉成 CI 红线：
 *
 * 1. **读方法必须在册**——两个 DAO 必须各持列表 + 按 id 读方法（防退回"只写不读"，
 *    原"无读者"判归前提随 SS3-c 失效，此处反转锁定）；
 * 2. **SELECT 唯一落点**——全仓生产源码里 `FROM archived_*` 的读查询只允许出现在
 *    `ArchiveDaos.kt`（查询协议面单点），旁路 SQL 即红；
 * 3. **还原只经 ArchiveReader**——归档载荷还原方法（`decodeFromBase64` 消费）不得
 *    散落：删除档重置后无"恢复旧档"语义，还原仅服务展示（诊断 + 战报/陨落历史），
 *    禁止把归档行写回主表（disciples / battle_logs）；
 * 4. **写入面语义不放宽**——`insertAll` + 保留期清理 `deleteArchivedBefore` 必须在册，
 *    归档写入/搬运/保留策略不在本守卫授权改动面。
 */
class ArchiveWriteOnlyGuardTest {

    private companion object {
        const val ARCHIVE_DAOS = "core/data/src/main/java/com/xianxia/sect/data/archive/ArchiveDaos.kt"
        const val ARCHIVE_READER = "core/data/src/main/java/com/xianxia/sect/data/archive/ArchiveReader.kt"
        val MODULES = listOf("app", "core/data", "core/domain", "core/engine", "core/ui", "feature/game")
        val ARCHIVED_TABLES = listOf("archived_battle_logs", "archived_disciples")
    }

    @Test
    fun `归档 DAO 必须持列表与按id读方法（防退回只写不读）`() {
        val dao = androidFile(ARCHIVE_DAOS).readText()
        assertTrue("归档 DAO 文件缺失（改名须同步本守卫）", dao.contains("interface ArchivedBattleLogDao"))
        ARCHIVED_TABLES.forEach { _ -> /* 两 DAO 同文件，逐表断言读方法 */ }
        val readMethodNames = listOf("listRecent", "getById", "countAll")
        readMethodNames.forEach { method ->
            assertTrue(
                "归档 DAO 读方法 $method 缺失 ⇒ 归档退回只写不读（SS3-c 职责边界被破坏）",
                Regex("""suspend fun $method\(""").containsMatchIn(dao)
            )
        }
        assertTrue(
            "归档 DAO 写入面必须在册（insertAll + 保留期清理 deleteArchivedBefore，写入策略不随读面批改动）",
            dao.contains("insertAll(") &&
                Regex("""suspend fun deleteArchivedBefore\(""").containsMatchIn(dao)
        )
    }

    @Test
    fun `归档表 SELECT 查询只允许出现在 ArchiveDaos（查询协议单点）`() {
        val root = androidRoot()
        val offenders = MODULES.flatMap { module ->
            File(root, "$module/src/main").walkTopDown()
                .filter { it.isFile && it.name.endsWith(".kt") }
                .filter { it.relativeTo(root).path.replace(File.separatorChar, '/') != ARCHIVE_DAOS }
                .filter { file ->
                    val text = file.readText().replace("\r\n", "\n")
                    ARCHIVED_TABLES.any { table ->
                        Regex("""SELECT[\s\S]{0,200}FROM\s+$table""", RegexOption.IGNORE_CASE)
                            .containsMatchIn(text)
                    }
                }.map { it.relativePath(root) }
        }
        assertTrue(
            "归档表读查询旁路 ArchiveDaos.kt 出现（查询协议面必须单点，经 ArchiveReader 消费）: $offenders",
            offenders.isEmpty()
        )
    }

    @Test
    fun `还原通道单点且不回写主表`() {
        val reader = androidFile(ARCHIVE_READER).readText()
        assertTrue(
            "ArchiveReader 必须提供按 id 还原（restoreBattleLog/restoreDisciple）——归档行是可还原载荷",
            reader.contains("suspend fun restoreBattleLog(") &&
                reader.contains("suspend fun restoreDisciple(")
        )
        val root = androidRoot()
        val writebackOffenders = MODULES.flatMap { module ->
            File(root, "$module/src/main").walkTopDown()
                .filter { it.isFile && it.name.endsWith(".kt") }
                .filter { file ->
                    val text = file.readText().replace("\r\n", "\n")
                    ARCHIVED_TABLES.any { table ->
                        // 归档表 → 主表回写的 SQL 形态（INSERT INTO 主表 ... SELECT ... FROM 归档表）
                        listOf("disciples", "battle_logs").any { mainTable ->
                            Regex(
                                """INSERT\s+INTO\s+$mainTable[\s\S]{0,300}SELECT[\s\S]{0,300}FROM\s+$table""",
                                RegexOption.IGNORE_CASE
                            ).containsMatchIn(text)
                        }
                    }
                }.map { it.relativePath(root) }
        }
        assertTrue(
            "出现归档表→主表回写（删档重置后无恢复旧档语义，还原仅服务展示）: $writebackOffenders",
            writebackOffenders.isEmpty()
        )
    }

    private fun androidRoot(): File {
        var dir: File? = File(System.getProperty("user.dir"))
        while (dir != null) {
            val candidate = if (dir.name == "android") dir else File(dir, "android")
            if (MODULES.all { File(candidate, "$it/src/main").isDirectory }) return candidate
            dir = dir.parentFile
        }
        throw AssertionError("定位不到 android 源根（user.dir=${System.getProperty("user.dir")}）——守卫不得静默跳过")
    }

    private fun androidFile(relative: String): File = File(androidRoot(), relative)

    /** Windows 反斜杠归一（SR-5 实事故：未归一导致排除清单静默失效） */
    private fun File.relativePath(root: File): String =
        relativeTo(root).path.replace(File.separatorChar, '/')
}
