package com.xianxia.sect.data.cloud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 存量迁移面静态守卫（SR-6 C10）。
 *
 * 两条红线各一锁，都是"改错了要能红灯"而非"文档里写着别改"：
 * 1. **模式总闸的写入面**（方案 §2「SR-6 收口」+ §4 SR-6「未完成迁移的设备不推 CLOUD_ONLY」）：
 *    生产代码里 `SaveBackendModeProvider.set` 只允许一个调用者（`SaveMigrationCoordinator`
 *    的玩家确认位），且它只写 `CLOUD_TRANSITION`——`CLOUD_ONLY` 属 SR-7，且必须先过
 *    [SaveMigrationPlanner.canPromoteToCloudOnly]；
 * 2. **IN2 仲裁无时钟**：迁移族文件内零取时、零 mtime 读取（判定输入只有序号与记账态）。
 *
 * 与 `WallClockReflowGuardTest`（core:engine，SR-5）的分工：那张清单锁的是"游戏语义取时族"，
 * 本守卫锁的是"迁移判定族不得引入任何时间输入"——两者对象不同、互不重叠。
 * 模块源目录不可达一律 `AssertionError`（不是 skip，同 SR-5 纪律）。
 */
class SaveMigrationGuardTest {

    /** 迁移判定族（新增成员必须同时进本清单，漏加即"守卫看不见的新代码"） */
    private val migrationFamily = listOf(
        "core/data/src/main/java/com/xianxia/sect/data/cloud/SaveMigrationLedger.kt",
        "core/data/src/main/java/com/xianxia/sect/data/cloud/SaveMigrationPlanner.kt",
        "feature/game/src/main/java/com/xianxia/sect/ui/game/saveload/SaveMigrationCoordinator.kt",
        "feature/game/src/main/java/com/xianxia/sect/ui/game/saveload/SaveMigrationState.kt",
        "feature/game/src/main/java/com/xianxia/sect/ui/game/saveload/CloudSaveCacheWriter.kt"
    )

    @Test
    fun `模式总闸的唯一生产写入者是迁移协调器，且只写 CLOUD_TRANSITION`() {
        val root = androidRoot()
        val writers = mainKotlinFiles(root).filter { ModeWritePattern.containsMatchIn(it.readText()) }
        val relative = writers.map { it.relativePath(root) }

        assertEquals(
            "SaveBackendModeProvider.set 的生产调用点必须唯一（迁移多开一个写入点=有人绕过玩家确认）：" +
                relative,
            listOf("feature/game/src/main/java/com/xianxia/sect/ui/game/saveload/SaveMigrationCoordinator.kt"),
            relative
        )
        val coordinator = writers.single().readText()
        assertTrue("写入值必须是 CLOUD_TRANSITION", coordinator.contains("set(SaveBackendMode.CLOUD_TRANSITION)"))
    }

    @Test
    fun `CLOUD_ONLY 在生产代码零写入（SR-7 前置门未开）`() {
        val root = androidRoot()
        val offenders = mainKotlinFiles(root).filter {
            Regex("""\.set\(\s*SaveBackendMode\.CLOUD_ONLY""").containsMatchIn(it.readText())
        }
        assertTrue("CLOUD_ONLY 只能在测试与门槛判据里出现：${offenders.map { it.name }}", offenders.isEmpty())
    }

    @Test
    fun `迁移判定族零时钟与零 mtime 输入（IN2 仲裁无时钟）`() {
        val root = androidRoot()
        migrationFamily.forEach { relativePath ->
            val file = File(root, relativePath)
            assertTrue("迁移族文件缺失（改名须同步守卫清单）：$relativePath", file.isFile)
            val text = file.readText()
            ForbiddenTimePatterns.forEach { pattern ->
                assertFalse("$relativePath 出现时间输入「$pattern」——迁移判定只准用序号与记账态", text.contains(pattern))
            }
        }
    }

    @Test
    fun `迁移族清单规模与成员锁定（放宽须显式改本断言）`() {
        assertEquals(
            "迁移判定族成员数被改动：$migrationFamily",
            5,
            migrationFamily.size
        )
    }

    // ── 源面解析（同 RngSourceGuardTest / WallClockReflowGuardTest 口径）──

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

    /** Windows 反斜杠必须归一（SR-5 实事故：未归一导致排除清单静默失效） */
    private fun File.relativePath(root: File): String =
        relativeTo(root).path.replace(File.separatorChar, '/')

    private companion object {
        val MODULES = listOf("app", "core/data", "core/domain", "core/engine", "core/ui", "feature/game")

        val ModeWritePattern = Regex("""\.set\(\s*SaveBackendMode\.""")

        /** 迁移判定族禁用的时间输入面 */
        val ForbiddenTimePatterns = listOf(
            "System.currentTimeMillis",
            "SystemClock.",
            "Calendar.getInstance",
            "modifiedTimeMs"
        )
    }
}
