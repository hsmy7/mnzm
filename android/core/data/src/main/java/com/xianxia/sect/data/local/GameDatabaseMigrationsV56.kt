// GameDatabaseMigrationsV56.kt — v55→v56 迁移（见 GameDatabase.kt ALL_MIGRATIONS）
package com.xianxia.sect.data.local

import android.util.Log
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** Migration 文件共用的 TAG（各文件独立声明，避免 top-level 冲突） */
private const val TAG_V56 = "GameDatabase"

/** v56 迁移中 `game_data` 表的删除列清单（列名以 v55 schema JSON 权威差集为准）。 */
internal val V56_GAME_DATA_DROPPED_COLUMNS: List<String> = listOf(
    "lastRecruitYear",
    "last_ai_sect_recruit_year",
    "open_recruitment_last_paid_month",
    "autoRecruitSpiritRootFilter",
    "autoRejectSpiritRootFilter"
)

/** v56 迁移中 `sect_policy_state` 表的删除列清单（列名以 v55 schema JSON 权威差集为准）。 */
internal val V56_SECT_POLICY_DROPPED_COLUMNS: List<String> = listOf(
    "autoRecruitSpiritRootFilter"
)

/**
 * v55→v56: 招募链字段下线（G05 删列）——`game_data` 5 列 + `sect_policy_state` 1 列。
 *
 * ## 删除面（表 → 列 → 域字段 → 玩法面）
 * ### `game_data` 表（5 列）
 * | 列 | 域字段 | 玩法面 |
 * |---|---|---|
 * | `lastRecruitYear` | `GameData.lastRecruitYear` | 招募列表年结刷新差值门 |
 * | `last_ai_sect_recruit_year` | `GameData.lastAiSectRecruitYear` | AI 宗年度招募差值门 |
 * | `open_recruitment_last_paid_month` | `GameData.openRecruitmentLastPaidMonth` | 广纳门徒政策付费冷却 |
 * | `autoRecruitSpiritRootFilter` | `GameData.autoRecruitSpiritRootFilter` | 自动招募灵根过滤 |
 * | `autoRejectSpiritRootFilter` | `GameData.autoRejectSpiritRootFilter` | 自动拒绝灵根过滤 |
 * ### `sect_policy_state` 表（1 列）
 * | 列 | 域字段 | 玩法面 |
 * |---|---|---|
 * | `autoRecruitSpiritRootFilter` | `SectPolicyState.autoRecruitSpiritRootFilter` | 政策态自动招募过滤持久化 |
 *
 * ## 为什么不丢玩家可感知数据（结构性论证，非抽样）
 * 被删 6 列的生产者与消费者同属招募链自身：全部写点是招募/政策结算写入，全部读点是
 * 招募链的差值门与过滤判定——链下线后全仓 `src/main` 对这 6 列的读写点为零。
 * `recruitList`（招募列表本体）不在此列：字段与 Room 列保留、读档/校验恒清空，
 * 存档旧字节按保留协议静默忽略；玩家可感知数据（宗名/年月/灵石/年报其余计数/
 * 政策态其余列等）全部经 PRAGMA 逐列定义读取 + `INSERT SELECT` 逐行原样复制。
 * ProtoBuf 侧字段号随同批下线空缺（25/93/101/210/219/29），已以 reserved 注释
 * 登记禁复用；旧存档 wire 字节按 protobuf 规范静默跳过。
 *
 * ## 实现
 * SQLite < 3.35.0 不支持 `ALTER TABLE DROP COLUMN`（规则 7.2），两表均经
 * [rebuildTableDroppingColumns] create-copy-drop-rename 重建：PRAGMA 读全列定义
 * → 按名剔除删除列 → 建新表（带回主键）→ `INSERT SELECT` 复制 → 换表 → 重建索引。
 * 幂等：待删列一个都不存在即直接返回（重复执行/迁移中断重试安全）。
 * 索引重建与两 `@Entity` 现存 `Index` 声明一致：`game_data` 5 条
 * （slot_id UNIQUE / lastSaveTime / gameYear+gameMonth / sectName / spiritStones）、
 * `sect_policy_state` 1 条（slot_id UNIQUE）。
 *
 * ## 回滚
 * 升级前由 `GameDatabase.backupDatabaseForMigration` 落
 * `{db}.pre_migrate_backup.v55`（`wal_checkpoint(TRUNCATE)` 后文件级复制）；
 * 降级依赖该备份恢复——**Room 不支持降级打开**。v55 备份中原样保有这 6 列，
 * 恢复即回到本迁移前的 schema；备份缺失时亦无数据后果（6 列无存续读取方）。
 */
internal val MIGRATION_55_56 = object : Migration(55, 56) {
    override fun migrate(db: SupportSQLiteDatabase) {
        rebuildTableDroppingColumns(
            db = db,
            table = "game_data",
            columnsToDrop = V56_GAME_DATA_DROPPED_COLUMNS,
            pkColumns = listOf("id", "slot_id"),
            indices = listOf(
                Triple("index_game_data_slot_id", "game_data(`slot_id`)", true),
                Triple("index_game_data_lastSaveTime", "game_data(`lastSaveTime`)", false),
                Triple("index_game_data_gameYear_gameMonth", "game_data(`gameYear`, `gameMonth`)", false),
                Triple("index_game_data_sectName", "game_data(`sectName`)", false),
                Triple("index_game_data_spiritStones", "game_data(`spiritStones`)", false)
            )
        )
        Log.i(TAG_V56, "Migration 55→56: dropped ${V56_GAME_DATA_DROPPED_COLUMNS.size} columns from game_data")

        rebuildTableDroppingColumns(
            db = db,
            table = "sect_policy_state",
            columnsToDrop = V56_SECT_POLICY_DROPPED_COLUMNS,
            pkColumns = listOf("slot_id"),
            indices = listOf(
                Triple(
                    "index_sect_policy_state_slot_id",
                    "sect_policy_state(`slot_id`)",
                    true
                )
            )
        )
        Log.i(
            TAG_V56,
            "Migration 55→56: dropped ${V56_SECT_POLICY_DROPPED_COLUMNS.size} " +
                "columns from sect_policy_state"
        )
    }
}
