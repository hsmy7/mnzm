// GameDatabaseMigrationsV52.kt — v51→v52 迁移（见 GameDatabase.kt addMigrations 列表）
package com.xianxia.sect.data.local

import android.util.Log
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** Migration 文件共用的日志 TAG（各文件独立声明，避免 top-level 冲突） */
private const val TAG = "GameDatabase"

/**
 * v51→v52: Room 死列清理批（B19）——删除 `game_data` 的两列死列。
 *
 * ## 删除面（两列，均为全仓零生产者/零消费者）
 * - `battleTeam`（**单数**，TEXT NULL）：v40 起被复数 `battle_teams` + `battle_teams_initialized`
 *   取代（`MIGRATION_39_40` 只做 ADD COLUMN、未搬运单数数据），此后 12 个 schema 版本
 *   全仓零读写点；实体注释自陈"保留用于 Room schema 兼容旧存档"。
 * - `aiBattleTeams`（TEXT NOT NULL）：全仓零写入者；唯一读取链
 *   `GameData.organization → SectOrganizationState.aiBattleTeams` 在生产零消费者
 *   （`grep -rn "\.organization" --include=*.kt` 生产命中 0）。
 * 两列均带 `@kotlinx.serialization.Transient` ⇒ 不进 `.sav` / proto / 镜像信封，
 * 只存在于 Room 列 ⇒ 删除对存档文件与云档路径零影响。
 *
 * ## 数据处置判归（旧档数据窗口勘察，详见 batch-B19-room-dead-columns.md §0.2）
 * **直接删列，不做单数→复数搬运**：搬运会让旧档凭空多出一支队伍（旧档本应经
 * `battleTeamsInitialized=false` 走默认队伍初始化）⇒ 属行为变更，违反本批零行为变更口径。
 *
 * ## 实现
 * SQLite < 3.35.0（API24 内置 3.9）不支持 `DROP COLUMN` ⇒ create-copy-drop-rename，
 * 走 [rebuildTableDroppingColumns]（PRAGMA 驱动逐列重建 + INSERT SELECT 复制 + 重建 5 索引；
 * 幂等：待删列不存在即返回）。**不得**改用 [GAME_DATA_CREATE_SQL]——那是 v29 基线，
 * 会丢弃 v29 之后新增的 20+ 列（先例警告见 `GameDatabaseMigrationsV50.kt`）。
 *
 * ## 回滚
 * 升级前由 `GameDatabase.backupDatabaseForMigration` 落 `{db}.pre_migrate_backup.v51`
 * （先 `wal_checkpoint(TRUNCATE)` 再文件级复制，保留 2 个版本）；降级（高版本 App 数据
 * 回到低版本 App）依赖该备份恢复，**Room 不支持降级打开**。本迁移不回写被删列，
 * 降级恢复后两列回到备份时的原值（v51 形态），行为与本迁移前一致。
 */
internal val MIGRATION_51_52 = object : Migration(51, 52) {
    override fun migrate(db: SupportSQLiteDatabase) {
        rebuildTableDroppingColumns(
            db = db,
            table = "game_data",
            columnsToDrop = listOf("battleTeam", "aiBattleTeams"),
            pkColumns = listOf("id", "slot_id"),
            indices = listOf(
                Triple("index_game_data_slot_id", "game_data(`slot_id`)", true),
                Triple("index_game_data_lastSaveTime", "game_data(`lastSaveTime`)", false),
                Triple("index_game_data_gameYear_gameMonth", "game_data(`gameYear`, `gameMonth`)", false),
                Triple("index_game_data_sectName", "game_data(`sectName`)", false),
                Triple("index_game_data_spiritStones", "game_data(`spiritStones`)", false)
            )
        )
        Log.i(TAG, "Migration 51→52: dropped battleTeam/aiBattleTeams dead columns from game_data")
    }
}
