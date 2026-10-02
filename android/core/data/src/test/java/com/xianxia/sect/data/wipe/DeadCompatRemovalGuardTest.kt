package com.xianxia.sect.data.wipe

import com.xianxia.sect.data.local.GameDatabaseConfig
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 旧存档兼容代码清零反向守卫（SS0 验收⑭）。
 *
 * 处置表"删除"列的每个符号路径断言**已不存在**：全仓生产源码（六模块 `src/main`）
 * 对下述兼容符号零命中；白名单显式声明保留理由（D-9 可恢复性 / D-5 云端旧档删除
 * 识别器）。任何一条命中 = 兼容代码复活或清理遗漏，CI 判红。
 */
class DeadCompatRemovalGuardTest {

    private companion object {
        /** 六模块生产源码根（测试工作目录 = android/core/data） */
        val ANDROID_ROOT = File("../..").canonicalFile

        val MODULES = listOf(
            "core/domain", "core/data", "core/engine", "core/ui", "feature/game", "app"
        )

        /** 守卫本体豁免（FORBIDDEN_PATTERNS 的正则字面量定义在本文件，扫描 src/test 时自命中） */
        const val SELF_EXEMPT_FILE = "DeadCompatRemovalGuardTest.kt"

        /** Room schema 导出目录（room.schemaLocation；当期版本产物由 .gitignore 管理，不入库） */
        val SCHEMA_DIRS = listOf("app/schemas", "core/data/schemas")

        /**
         * 处置表"删除"列符号面（A1–A5 锚点的删除侧）。
         * `MIGRATION_\d+_\d+` 只匹配具体编号迁移常量，不误伤
         * "MIGRATION_(N-1)_N" 规则指引文本与 [WHITELIST] 保留项。
         */
        val FORBIDDEN_PATTERNS = listOf(
            Regex("""MIGRATION_\d+_\d+""") to "Room 迁移常量（A1）",
            Regex("""\bALL_MIGRATIONS\b""") to "迁移单点登记表（A1）",
            Regex("""\baddMigrations\b""") to "迁移注册调用（A1）",
            Regex("""fallbackToDestructiveMigrationFrom""") to "旧版按版本 destructive API（A1，已换全量 fallback）",
            Regex("""GameDatabaseMigrationsV""") to "迁移实现文件族（A1）",
            Regex("""MigrationChainGuard|RoomMigrationSupport""") to "迁移守卫测试与辅助（A1）",
            Regex("""SaveDataVersionMigrator""") to "存档格式版本迁移器（A2）",
            Regex("""CURRENT_SAVE_VERSION""") to "迁移链终点常量（A2）",
            Regex("""data\.migration\.MigrationResult""")
                to "存档格式迁移结果 sealed 类型（A2；core.engine.MigrationResult 为建筑自愈结果类型，非同物）",
            Regex("""OldSaveFormatDeserializer|OldSerializableSaveData""") to "旧格式兼容层（A2）",
            Regex("""backwardcompat""") to "旧格式兼容包（A2）",
            Regex("""SaveMigrationCard|SaveMigrationState|SaveMigrationCoordinator|""" +
                """SaveMigrationPlanner|SaveMigrationLedger|SaveMigrationGuard""") to "存量迁移族（A3）",
            Regex("""cloud_migration_""") to "存量迁移 MMKV 旧键（A3）",
            Regex("""backupDatabaseForMigration""") to "迁移前备份旧名（A4，已改快照语义）",
            Regex("""MailService\.injectWhitelistBonus|sendWhitelistBonus|""" +
                Regex.escape("mail_qq_group")) to "运营邮件清理（SS0-j）",
            Regex("""LegacyEquipmentCompensationRule|NurturePillRetirementRule|""" +
                """EquipmentLegacyTableReader|equipment_legacy_compensation|nurture_pill_retirement""")
                to "历史补偿规则链（W7）",
            Regex("""LawEnforcementPrisonCleanupRule|BloodPoolBuildingCleanupRule|""" +
                """RecruitListCleanupRule|MailDiscipleAttachmentCleanupRule""")
                to "下线玩法存量清洗规则（W7）"
        )

        /**
         * 白名单（按"命中子串"全局生效，逐条附理由）。
         * 新增白名单条目必须附 D-9/D-5 依据。
         */
        val WHITELIST: Set<String> = setOf(
            // D-9（可恢复性保留）：启动前快照的裁剪实现（GameDatabase/DataPruningScheduler 两处）
            "pruneDatabaseSnapshots",
            // D-9（可恢复性保留）：启动前快照保留份数常量
            "STARTUP_SNAPSHOT_KEEP_COUNT",
            // D-5（云端旧档删除识别器）：主动删除旧协议档必须能识别旧命名本体
            "isLegacyArchiveName",
            "mnzm_cloud_save",
            // D-5/D-6（删档清理识别器）：wipe 按前缀清理旧协议台账键
            "cloud_migration_"
        )
    }

    @Test
    fun `no removed compat symbol remains in production sources`() {
        val ktFiles = productionKtFiles()
        assertTrue("生产源码树解析异常（仅 ${ktFiles.size} 个文件）", ktFiles.size > 1000)

        val violations = ktFiles.flatMap { file -> violationsIn(file) }
        assertTrue(
            buildString {
                append("旧存档兼容符号在生产源码复活/遗漏（验收⑭反向守卫）：\n")
                violations.take(40).forEach { append("  - $it\n") }
                if (violations.size > 40) append("  …共 ${violations.size} 处\n")
                append("处置：属兼容逻辑的删除之；属 D-9 可恢复性/D-5 删除识别器的加入 WHITELIST 并注明理由。")
            },
            violations.isEmpty()
        )
    }

    /** 单文件命中清单（白名单内符号不计违规） */
    private fun violationsIn(file: File): List<String> =
        file.readText().let { text ->
            FORBIDDEN_PATTERNS.flatMap { (pattern, description) ->
                pattern.findAll(text)
                    .map { it.value }
                    .filter { it !in WHITELIST }
                    .map { hit ->
                        "${file.relativeTo(ANDROID_ROOT)}: [$description] 命中 \"$hit\""
                    }
            }
        }

    /** 六模块生产 + 测试源码 Kotlin 文件全集（测试面注释残留同样判红——实施回差实例驱动） */
    private fun productionKtFiles(): List<File> =
        MODULES.flatMap { module ->
            listOf("src/main", "src/test").flatMap { src ->
                File(ANDROID_ROOT, module).resolve(src).walkTopDown()
                    .filter { it.isFile && it.extension == "kt" && it.name != SELF_EXEMPT_FILE }
                    .toList()
            }
        }

    /**
     * 孤儿 schema 不得回流：两处 room.schemaLocation 导出目录中，版本低于
     * DATABASE_VERSION 的 schema JSON（SS0 已删 app/1.json 与 core/data v2..v65
     * 历史族）出现即红；当期版本导出产物豁免（.gitignore 管理不入库）。
     */
    @Test
    fun `no orphan schema json exists in schema export dirs`() {
        val orphans = SCHEMA_DIRS.flatMap { dir ->
            val schemas = ANDROID_ROOT.resolve(dir)
            if (!schemas.exists()) return@flatMap emptyList()
            schemas.walkTopDown()
                .filter { it.isFile && it.extension == "json" }
                .filter { (it.nameWithoutExtension.toIntOrNull() ?: -1) < GameDatabaseConfig.DATABASE_VERSION }
                .map { "${it.relativeTo(ANDROID_ROOT)}" }
                .toList()
        }
        assertTrue(
            "schema 导出目录存在低于 v${GameDatabaseConfig.DATABASE_VERSION} 的孤儿 schema JSON" +
                "（历史迁移基线已随 SS0 删除，防回流）：$orphans",
            orphans.isEmpty()
        )
    }
}
