// GameDatabaseMigrationsV58.kt — v57→v58 迁移（见 GameDatabase.kt ALL_MIGRATIONS）
package com.xianxia.sect.data.local

import android.util.Log
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** Migration 文件共用的日志 TAG（各文件独立声明，避免 top-level 冲突） */
private const val TAG_V58 = "GameDatabase"

/** v58 迁移中 `disciples` 表的删除列清单（列名以 v57 schema JSON 权威差集为准）。 */
internal val V58_DISCIPLES_DROPPED_COLUMNS: List<String> = listOf(
    "talentIds",
    "physiqueIds",
    "affixIds",
    "aptitude"
)

/** v58 迁移中 `game_data` 表的删除列清单（列名以 v57 schema JSON 权威差集为准）。 */
internal val V58_GAME_DATA_DROPPED_COLUMNS: List<String> = listOf(
    "bloodRefinements",
    "activeBloodRefinements",
    "bloodRefinementBonusTotals",
    "bloodRefinementPctTotals",
    "pending_trait_adds"
)

/**
 * v57→v58: 洗炼/资质/天赋·体质·词条/血炼字段链下线（G04 删列）——
 * `disciples` 4 列 + `game_data` 5 列，共 9 列。
 *
 * ## 删除面（表 → 列 → 域字段 → 玩法面）
 * ### `disciples` 表（4 列）
 * | 列 | 域字段 | 玩法面 |
 * |---|---|---|
 * | `talentIds` | `Disciple.talentIds` | 天赋表实例引用 |
 * | `physiqueIds` | `Disciple.physiqueIds` | 体质表实例引用 |
 * | `affixIds` | `Disciple.affixIds` | 词条表实例引用 |
 * | `aptitude` | `SkillStats.aptitude` | 资质（修炼速率乘区与生成阶梯） |
 *
 * `comprehension`（悟性）列**存续**（突破率三乘区与教学公式保留），不在删除面。
 * ### `game_data` 表（5 列）
 * | 列 | 域字段 | 玩法面 |
 * |---|---|---|
 * | `bloodRefinements` | `GameData.bloodRefinements` | 血炼完成记录 |
 * | `activeBloodRefinements` | `GameData.activeBloodRefinements` | 进行中的血炼（血炼池建筑整建制下线） |
 * | `bloodRefinementBonusTotals` | `GameData.bloodRefinementBonusTotals` | 血炼单利累计 |
 * | `bloodRefinementPctTotals` | `GameData.bloodRefinementPctTotals` | 血炼百分比乘区累计 |
 * | `pending_trait_adds` | `GameData.pendingTraitAdds` | 特质刷新待确认产物 |
 *
 * ## 为什么不丢玩家可感知数据（结构性论证，非抽样）
 * 被删 9 列的生产者与消费者同属洗炼/资质/三表/血炼四条链自身：写点是特质事务
 * （1613–1616/1732/1733 已退役）、弟子生成特质 roll、血炼事务（1746 已退役）与
 * 血炼结算；读点是乘区计算、槽位清理与引导条件。玩法链整线下线后，全仓 `src/main`
 * 对这 9 列的读写点为零——列内容是"下线玩法的内部运行状态"，不是玩家资产
 * （弟子本体、境界、修为、装备、灵石、悟性等玩家可感知数据全部保留）。
 * 旧档中已放置的血炼池建筑实例与关联槽位经 `BloodPoolBuildingCleanupRule`
 * （读档校验边界）恒清理；残留 REFINING 状态字符串经四路解析面优雅降级为 IDLE。
 * 保留列的内容经 PRAGMA 逐列定义读取 + `INSERT SELECT` 逐行原样复制，类型/NOT NULL/
 * DEFAULT/主键逐项保留。ProtoBuf 侧对应字段号两处定义均已 `reserved`
 * （`SerializableDisciple` 22/104/105/110、GameData 级 115/150/151/152/1002），
 * 云档/.sav 旧字节按 wire 规范静默跳过，旧存档反序列化不受影响。
 *
 * ## 实现
 * SQLite < 3.35.0 不支持 `ALTER TABLE DROP COLUMN`（规则 7.2），两表均经
 * [rebuildTableDroppingColumns] create-copy-drop-rename 重建：PRAGMA 读全列定义
 * → 按名剔除删除列 → 建新表（带回主键）→ `INSERT SELECT` 复制 → 换表 → 重建索引。
 * 幂等：待删列一个都不存在即直接返回（重复执行/迁移中断重试安全）。
 * 索引重建与两 `@Entity` 现存 `Index` 声明一致：`disciples` 5 条
 * （name / realm+realmLayer / isAlive+realm / isAlive+status / discipleType）、
 * `game_data` 5 条（slot_id UNIQUE / lastSaveTime / gameYear+gameMonth / sectName /
 * spiritStones）。本批不删任何索引。
 *
 * ## 回滚
 * 升级前由 `GameDatabase.backupDatabaseForMigration` 落
 * `{db}.pre_migrate_backup.v57`（`wal_checkpoint(TRUNCATE)` 后文件级复制）；
 * 降级依赖该备份恢复——**Room 不支持降级打开**。v57 备份中原样保有这 9 列，
 * 恢复即回到本迁移前的 schema；备份缺失时亦无数据后果（9 列无存续读取方）。
 */
internal val MIGRATION_57_58 = object : Migration(57, 58) {
    override fun migrate(db: SupportSQLiteDatabase) {
        rebuildTableDroppingColumns(
            db = db,
            table = "disciples",
            columnsToDrop = V58_DISCIPLES_DROPPED_COLUMNS,
            pkColumns = listOf("id", "slot_id"),
            indices = listOf(
                Triple("index_disciples_name", "disciples(`name`)", false),
                Triple("index_disciples_realm_realmLayer", "disciples(`realm`, `realmLayer`)", false),
                Triple("index_disciples_isAlive_realm", "disciples(`isAlive`, `realm`)", false),
                Triple("index_disciples_isAlive_status", "disciples(`isAlive`, `status`)", false),
                Triple("index_disciples_discipleType", "disciples(`discipleType`)", false)
            )
        )
        Log.i(TAG_V58, "Migration 57→58: dropped ${V58_DISCIPLES_DROPPED_COLUMNS.size} columns from disciples")

        rebuildTableDroppingColumns(
            db = db,
            table = "game_data",
            columnsToDrop = V58_GAME_DATA_DROPPED_COLUMNS,
            pkColumns = listOf("id", "slot_id"),
            indices = listOf(
                Triple("index_game_data_slot_id", "game_data(`slot_id`)", true),
                Triple("index_game_data_lastSaveTime", "game_data(`lastSaveTime`)", false),
                Triple("index_game_data_gameYear_gameMonth", "game_data(`gameYear`, `gameMonth`)", false),
                Triple("index_game_data_sectName", "game_data(`sectName`)", false),
                Triple("index_game_data_spiritStones", "game_data(`spiritStones`)", false)
            )
        )
        Log.i(TAG_V58, "Migration 57→58: dropped ${V58_GAME_DATA_DROPPED_COLUMNS.size} columns from game_data")
    }
}
