package com.xianxia.sect.data.archive

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 归档面"零读者"守卫（SR-7 C6 判归的证据锁）。
 *
 * 方案 §4 SR-7 要求重审"`DataArchive`/`DataPruning` 在云唯一后的存在意义"。
 * 本守卫锁住判归所依据的那条事实，而不是复述结论：
 * **`archived_battle_logs` / `archived_disciples` 两张表在生产源码里没有任何 SELECT 读者**。
 *
 * 为什么这条值得钉死：判归="归档产物在云唯一终态下不可达，随文件层一起退役"。
 * 一旦将来有人给这两个 DAO 加了读方法（或别处加了 `FROM archived_*` 查询），
 * "不可达"前提即失效，退役判断要重做——那时本守卫必须响，而不是等人去回忆这段推理。
 *
 * 取证口径（本会话实测，非引用注释）：`ArchiveDaos.kt` 全文只有 `@Insert` 与
 * `@Query("DELETE …")`；`DataArchiver.queryBattleLogs` 唯一调用者是它自己的
 * `restoreBattleLogs`，而 `restoreBattleLogs` / `getArchiveStats` / `getTotalArchiveSize`
 * 全仓零调用者；`feature/`+`core/ui` 无任何归档列表/还原入口。
 */
class ArchiveWriteOnlyGuardTest {

    private companion object {
        const val ARCHIVE_DAOS = "core/data/src/main/java/com/xianxia/sect/data/archive/ArchiveDaos.kt"
        val MODULES = listOf("app", "core/data", "core/domain", "core/engine", "core/ui", "feature/game")
        val ARCHIVED_TABLES = listOf("archived_battle_logs", "archived_disciples")
    }

    @Test
    fun `归档表 DAO 不得长出读方法（判归前提：零读者）`() {
        val dao = androidFile(ARCHIVE_DAOS).readText()
        assertTrue("归档 DAO 文件缺失（改名须同步本守卫）", dao.contains("interface ArchivedBattleLogDao"))
        val selectCount = Regex("""@Query\(\s*""" + "\"?" + """\s*SELECT""", RegexOption.IGNORE_CASE)
            .findAll(dao.replace("\r\n", "\n"))
            .count()
        assertFalse("archived_* DAO 出现 SELECT 方法 ⇒ 归档不再是零读者，C6 判归须重做", selectCount > 0)
    }

    @Test
    fun `全仓生产源码不得出现归档表读查询`() {
        val root = androidRoot()
        val offenders = MODULES.flatMap { module ->
            File(root, "$module/src/main").walkTopDown()
                .filter { it.isFile && it.name.endsWith(".kt") }
                .filter { file ->
                    val text = file.readText().replace("\r\n", "\n")
                    ARCHIVED_TABLES.any { table ->
                        Regex("""SELECT[\s\S]{0,200}FROM\s+$table""", RegexOption.IGNORE_CASE)
                            .containsMatchIn(text)
                    }
                }.map { it.relativePath(root) }
        }
        assertTrue("归档表被读到了 ⇒ C6 判归（随文件层退役）失效，须重审：$offenders", offenders.isEmpty())
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
