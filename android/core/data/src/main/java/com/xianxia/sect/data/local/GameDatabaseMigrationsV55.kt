// GameDatabaseMigrationsV55.kt — v54→v55 迁移（见 GameDatabase.kt addMigrations 列表）
package com.xianxia.sect.data.local

import android.util.Log
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** Migration 文件共用的日志 TAG（各文件独立声明，避免 top-level 冲突） */
private const val TAG = "GameDatabase"

/** v55 迁移中 `disciples` 表的删除列清单（列名以 v54 schema PRAGMA 现状为准）。 */
internal val V55_DISCIPLES_DROPPED_COLUMNS: List<String> = listOf(
    "age",
    "lifespan",
    "soulPower",
    "loyalty",
    "usage_usedExtendLifePillIds"
)

/** v55 迁移中 `game_data` 表的删除列清单（列名以 v54 schema PRAGMA 现状为准）。 */
internal val V55_GAME_DATA_DROPPED_COLUMNS: List<String> = listOf(
    "annual_theft_count",
    "theft_judgements_this_month",
    "warehouseGarrisons"
)

/** v55 迁移中 `pills` 表的删除列清单（列名以 v54 schema PRAGMA 现状为准）。 */
internal val V55_PILLS_DROPPED_COLUMNS: List<String> = listOf(
    "loyaltyAdd"
)

/**
 * v54→v55: 寿命/年龄/忠诚/神魂/延寿丹追踪与偷盗/仓库驻守字段链下线（G02 删列）。 *
 * ## 删除面（表 → 列 → 域字段 → 所属玩法面）
 * ### `disciples` 表（5 列）
 * | 列 | 域字段 | 玩法面 |
 * |---|---|---|
 * | `age` | `Disciple.age` | 年龄推进与老死判定 |
 * | `lifespan` | `Disciple.lifespan` | 寿元上限（突破/词条加成） |
 * | `soulPower` | `Disciple.soulPower` | 神魂（突破概率乘区） |
 * | `loyalty` | `SkillStats.loyalty` | 忠诚（叛逃筛选/年俸/政策月效） |
 * | `usage_usedExtendLifePillIds` | `UsageTracking.usedExtendLifePillIds` | 延寿丹服用去重 |
 *
 * 说明：`usedExtendLifePillTypes` 是 `@Ignore` 运行时 Set 字段，Room 无对应列，
 * 不在删除面（Kotlin 字段随同批序列化面一并下线）。
 * ### `game_data` 表（3 列）
 * | 列 | 域字段 | 玩法面 |
 * |---|---|---|
 * | `annual_theft_count` | `GameData.annualTheftCount` | 年度偷盗计数 |
 * | `theft_judgements_this_month` | `GameData.theftJudgementsThisMonth` | 月度偷盗判定配额 |
 * | `warehouseGarrisons` | `GameData.warehouseGarrisons` | 仓库驻守槽位 |
 * ### `pills` 表（1 列）
 * | 列 | 域字段 | 玩法面 |
 * |---|---|---|
 * | `loyaltyAdd` | `PillEffect.loyaltyAdd` | 忠诚丹药效果（配方/施效/展示同批下线） |
 *
 * ## 为什么不丢玩家可感知数据（结构性论证，非抽样）
 * 被删 9 列的**生产者与消费者同属下线玩法自身**：全部写点是对应玩法的结算写入
 * （年龄 +1、突破寿元累加、忠诚结算、偷盗判定标记、仓库驻守槽位分配、忠诚丹入包），
 * 全部读点是同一玩法的判定与展示面。字段链同批收口后，全仓 `src/main` 对这 9 列的读写点为零，
 * 没有任何存续玩法读取它们——列内容是"下线玩法的内部运行状态"，不是玩家资产。
 * 玩家可感知且存续的数据（境界/层数/修为/资质/悟性/道德/血蓝/装备/灵石/建筑/
 * 其余槽位/年报其余计数等）全部经 PRAGMA 逐列定义读取 + `INSERT SELECT` 逐行原样
 * 复制，类型/NOT NULL/DEFAULT/主键逐项保留；`annual_deserted_disciples`（逐出年报，
 * 玩法存续）明确不在删除面。ProtoBuf 侧对应字段号两处定义均已 `reserved`
 * （云档/.sav 旧字节按 wire 规范静默跳过），旧存档反序列化不受影响。
 *
 * ## 实现
 * SQLite < 3.35.0 不支持 `ALTER TABLE DROP COLUMN`（规则 7.2），三表均经
 * [rebuildTableDroppingColumns] create-copy-drop-rename 重建：
 * PRAGMA 读全列定义 → 按名剔除删除列 → 建新表（带回主键）→ `INSERT SELECT` 复制
 * → 换表 → 重建索引。幂等：待删列一个都不存在即直接返回（重复执行/迁移中断重试安全）。
 * 索引重建与三 `@Entity` 现存 `Index` 声明一致：`disciples` 5 条
 * （name / realm+realmLayer / isAlive+realm / isAlive+status / discipleType）、
 * `game_data` 5 条（slot_id UNIQUE / lastSaveTime / gameYear+gameMonth / sectName /
 * spiritStones）、`pills` 5 条（name / rarity / category / targetRealm / rarity+category）；
 * `index_disciples_loyalty` 与 `index_disciples_age` 随列退役不重建。
 *
 * ## 回滚
 * 升级前由 `GameDatabase.backupDatabaseForMigration` 落
 * `{db}.pre_migrate_backup.v54`（`wal_checkpoint(TRUNCATE)` 后文件级复制，保留 2 个版本）；
 * 降级依赖该备份恢复——**Room 不支持降级打开**。v54 备份中原样保有这 9 列，
 * 恢复即回到本迁移前的 schema；备份缺失时亦无数据后果（9 列无存续读取方）。
 */
internal val MIGRATION_54_55 = object : Migration(54, 55) {
    override fun migrate(db: SupportSQLiteDatabase) {
        rebuildTableDroppingColumns(
            db = db,
            table = "disciples",
            columnsToDrop = V55_DISCIPLES_DROPPED_COLUMNS,
            pkColumns = listOf("id", "slot_id"),
            indices = listOf(
                Triple("index_disciples_name", "disciples(`name`)", false),
                Triple("index_disciples_realm_realmLayer", "disciples(`realm`, `realmLayer`)", false),
                Triple("index_disciples_isAlive_realm", "disciples(`isAlive`, `realm`)", false),
                Triple("index_disciples_isAlive_status", "disciples(`isAlive`, `status`)", false),
                Triple("index_disciples_discipleType", "disciples(`discipleType`)", false)
            )
        )
        Log.i(TAG, "Migration 54→55: dropped ${V55_DISCIPLES_DROPPED_COLUMNS.size} columns from disciples")

        rebuildTableDroppingColumns(
            db = db,
            table = "game_data",
            columnsToDrop = V55_GAME_DATA_DROPPED_COLUMNS,
            pkColumns = listOf("id", "slot_id"),
            indices = listOf(
                Triple("index_game_data_slot_id", "game_data(`slot_id`)", true),
                Triple("index_game_data_lastSaveTime", "game_data(`lastSaveTime`)", false),
                Triple("index_game_data_gameYear_gameMonth", "game_data(`gameYear`, `gameMonth`)", false),
                Triple("index_game_data_sectName", "game_data(`sectName`)", false),
                Triple("index_game_data_spiritStones", "game_data(`spiritStones`)", false)
            )
        )
        Log.i(TAG, "Migration 54→55: dropped ${V55_GAME_DATA_DROPPED_COLUMNS.size} columns from game_data")

        rebuildTableDroppingColumns(
            db = db,
            table = "pills",
            columnsToDrop = V55_PILLS_DROPPED_COLUMNS,
            pkColumns = listOf("id", "slot_id"),
            indices = listOf(
                Triple("index_pills_name", "pills(`name`)", false),
                Triple("index_pills_rarity", "pills(`rarity`)", false),
                Triple("index_pills_category", "pills(`category`)", false),
                Triple("index_pills_targetRealm", "pills(`targetRealm`)", false),
                Triple("index_pills_rarity_category", "pills(`rarity`, `category`)", false)
            )
        )
        Log.i(TAG, "Migration 54→55: dropped ${V55_PILLS_DROPPED_COLUMNS.size} columns from pills")
    }
}
