package com.xianxia.sect.data.local

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 迁移纪律守卫（SS0 验收⑩ / 增量铁律 17）。
 *
 * 迁移链退役后，destructive fallback 意味着**实体清单漂移而未同步版本与基线 = 老库被静默重建**。
 * 本守卫把"`@Database` 实体清单变更必须同批完成同步义务"变成 CI 红线：基线漂移即失败。
 *
 * 同步义务：新增/删除 @Entity 或变更实体结构时——
 * ① 递增 `GameDatabaseConfig.DATABASE_VERSION`；
 * ② 更新本测试 [BASELINE_ENTITIES] 清单（**同 commit 两处**）；
 * ③ **测试期不写迁移**（老库由 `fallbackToDestructiveMigration(dropAllTables = true)` 全量重建）。
 * 🔴 **正式上线前必须切回**：写 `MIGRATION_(N-1)_N` + 迁移链完整性守卫 + 禁删列，
 * 否则一次 schema 变更会静默清空真实玩家档。详见 `rules/database-migration.md`。
 */
class MigrationRequiredGuardTest {

    private companion object {
        const val DATABASE_SRC = "src/main/java/com/xianxia/sect/data/local/GameDatabase.kt"

        /**
         * 实体清单基线（F2 时刻，v71：`disciples` 武器/腿部槽位列删除；实体清单本身未变）。
         * 实体清单与基线不一致 = 有实体变更：
         * 必须同批递增版本并更新本清单（测试期不写迁移，老库由 destructive 重建）。
         */
        val BASELINE_ENTITIES = setOf(
            "GameData::class",
            "Disciple::class",
            "EquipmentInstance::class",
            "ManualStack::class",
            "ManualInstance::class",
            "Pill::class",
            "Material::class",
            "Seed::class",
            "Herb::class",
            "BuildingSlot::class",
            "Recipe::class",
            "BattleLog::class",
            "ProductionSlot::class",
            "ChangeLogEntity::class",
            "ArchivedBattleLog::class",
            "ArchivedDisciple::class",
            "GameHeavyData::class",
            "StorageBag::class",
            "MailEntity::class",
            "DiplomacyState::class",
            "ProductionState::class",
            "PatrolStateEntity::class",
            "WorldMapStateEntity::class",
            "SectPolicyState::class",
            "OverflowMailDraftEntity::class",
            "DirectMailDraftEntity::class"
        )
    }

    @Test
    fun `entity list matches baseline - change requires migration in same commit`() {
        val declared = declaredEntities()
        assertTrue(
            "实体清单解析结果异常（仅 ${declared.size} 个，基线 ${BASELINE_ENTITIES.size} 个）" +
                "——@Database entities 解析正则已失配，请修正 [declaredEntities]",
            declared.size >= BASELINE_ENTITIES.size
        )

        val added = declared - BASELINE_ENTITIES
        val removed = BASELINE_ENTITIES - declared
        assertTrue(
            buildString {
                append("@Database 实体清单与基线不一致（新增: $added / 删除: $removed）。")
                append("实体变更必须同一 commit 完成：")
                append("① 递增 GameDatabaseConfig.DATABASE_VERSION；")
                append("② 测试期不写迁移（老库由 fallbackToDestructiveMigration 全量重建）；正式上线前须切回写 MIGRATION_(N-1)_N；")
                append("③ 更新 MigrationRequiredGuardTest.BASELINE_ENTITIES。")
                append("详见 rules/database-migration.md。")
            },
            added.isEmpty() && removed.isEmpty()
        )
    }

    @Test
    fun `destructive fallback stays registered as the missing-migration backstop`() {
        val src = readSource(DATABASE_SRC)
        assertTrue(
            "fallbackToDestructiveMigration 必须保留在 GameDatabase.create（缺迁移路径的唯一兜底；" +
                "移除它会让无迁移的旧库打开直接崩溃）",
            src.contains("fallbackToDestructiveMigration(")
        )
        assertTrue(
            "Database version 必须引用 GameDatabaseConfig.DATABASE_VERSION（禁止硬编码版本号）",
            src.contains("version = GameDatabaseConfig.DATABASE_VERSION")
        )
    }

    /** 从 GameDatabase.kt 源码解析 @Database entities 清单（解析失配由规模断言兜住） */
    private fun declaredEntities(): Set<String> {
        val src = readSource(DATABASE_SRC)
        val block = src.substringAfter("entities = [", "").substringBefore("],", "")
        assertTrue("未找到 @Database entities 块", block.isNotEmpty())
        return block.lines()
            .mapNotNull { line ->
                Regex("""(\w+::class)""").find(line)?.groupValues?.get(1)
            }
            .toSet()
    }

    private fun readSource(relativePath: String): String {
        val file = File(relativePath)
        assertTrue("源文件不存在: $relativePath（测试工作目录 = 模块根）", file.exists())
        return file.readText()
    }
}
