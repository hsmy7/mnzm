// GameDatabaseMigrationsV57.kt — v56→v57 迁移（见 GameDatabase.kt ALL_MIGRATIONS）
package com.xianxia.sect.data.local

import android.util.Log
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** Migration 文件共用的日志 TAG（各文件独立声明，避免 top-level 冲突） */
private const val TAG_V57 = "GameDatabase"

/** v57 迁移中 `disciples` 表的删除列清单（列名以 v56 schema JSON 权威差集为准）。 */
internal val V57_DISCIPLES_DROPPED_COLUMNS: List<String> = listOf(
    "social_partnerId",
    "social_partnerSectId",
    "social_parentId1",
    "social_parentId2",
    "social_lastChildYear",
    "social_childBirthMonth",
    "social_griefEndYear"
)

/** v57 迁移中 `game_data` 表的删除列清单（列名以 v56 schema JSON 权威差集为准）。 */
internal val V57_GAME_DATA_DROPPED_COLUMNS: List<String> = listOf(
    "daoCompanionBannedRootCounts",
    "daoCompanionConsentRequired"
)

/** v57 迁移中 `sect_policy_state` 表的删除列清单（列名以 v56 schema JSON 权威差集为准）。 */
internal val V57_SECT_POLICY_DROPPED_COLUMNS: List<String> = listOf(
    "daoCompanionBannedRootCounts",
    "daoCompanionConsentRequired"
)

/**
 * v56→v57: 生育/道侣/亲缘字段链下线（G03 删列）——`disciples` 7 列 + `game_data` 2 列
 * + `sect_policy_state` 2 列，共 11 列。
 *
 * ## 删除面（表 → 列 → 域字段 → 玩法面）
 * ### `disciples` 表（7 列）
 * | 列 | 域字段 | 玩法面 |
 * |---|---|---|
 * | `social_partnerId` | `DiscipleSocial.partnerId` | 道侣双向绑定与配对筛选 |
 * | `social_partnerSectId` | `DiscipleSocial.partnerSectId` | 跨宗门道侣匹配 |
 * | `social_parentId1` | `DiscipleSocial.parentId1` | 生育记录的父/母一方 |
 * | `social_parentId2` | `DiscipleSocial.parentId2` | 生育记录的另一方 |
 * | `social_lastChildYear` | `DiscipleSocial.lastChildYear` | 生育年度冷却门 |
 * | `social_childBirthMonth` | `DiscipleSocial.childBirthMonth` | 出生月记录 |
 * | `social_griefEndYear` | `DiscipleSocial.griefEndYear` | 亲属死亡的哀悼期 |
 *
 * `social_masterId`（师父）与拜师/年俸面**存续**，不在删除面。
 * ### `game_data` 表（2 列）
 * | 列 | 域字段 | 玩法面 |
 * |---|---|---|
 * | `daoCompanionBannedRootCounts` | `GameData.daoCompanionBannedRootCounts` | 禁止结为道侣的灵根数集合 |
 * | `daoCompanionConsentRequired` | `GameData.daoCompanionConsentRequired` | 道侣结成是否需玩家同意 |
 * ### `sect_policy_state` 表（2 列）
 * 同名列两列是 `SectPolicyState` 这个独立 `@Entity` 的政策态持久化副本（自动管理设置
 * 在政策态与全局态双写），随道侣面同批下线。
 *
 * ## 为什么不丢玩家可感知数据（结构性论证，非抽样）
 * 被删 11 列的生产者与消费者同属生育/道侣/亲缘三条链自身：写点是月结配对绑定、月结生育
 * 落子、年结哀悼写入与自动管理道侣设置；读点是配对资格筛选、血缘禁配判定、生育冷却门、
 * 哀悼期传播与亲属赠礼归类。链下线并收缩为仅师徒后，全仓 `src/main` 对这 11 列的读写点
 * 为零——列内容是"下线玩法的内部运行状态"，不是玩家资产（弟子本体、境界、修为、装备、
 * 灵石、师徒关系等玩家可感知数据全部保留）。
 * 保留列的内容经 PRAGMA 逐列定义读取 + `INSERT SELECT` 逐行原样复制，类型/NOT NULL/
 * DEFAULT/主键逐项保留。ProtoBuf 侧对应字段号两处定义均已 `reserved`
 * （`SerializableDisciple` 11/12/13/14/15/16/102、GameData 级 102/103），
 * 云档/.sav 旧字节按 wire 规范静默跳过，旧存档反序列化不受影响。
 *
 * ## 实现
 * SQLite < 3.35.0 不支持 `ALTER TABLE DROP COLUMN`（规则 7.2），三表均经
 * [rebuildTableDroppingColumns] create-copy-drop-rename 重建：PRAGMA 读全列定义
 * → 按名剔除删除列 → 建新表（带回主键）→ `INSERT SELECT` 复制 → 换表 → 重建索引。
 * 幂等：待删列一个都不存在即直接返回（重复执行/迁移中断重试安全）。
 * 索引重建与三 `@Entity` 现存 `Index` 声明一致：`disciples` 5 条
 * （name / realm+realmLayer / isAlive+realm / isAlive+status / discipleType）、
 * `game_data` 5 条（slot_id UNIQUE / lastSaveTime / gameYear+gameMonth / sectName /
 * spiritStones）、`sect_policy_state` 1 条（slot_id UNIQUE）。本批不删任何索引。
 *
 * ## 回滚
 * 升级前由 `GameDatabase.backupDatabaseForMigration` 落
 * `{db}.pre_migrate_backup.v56`（`wal_checkpoint(TRUNCATE)` 后文件级复制）；
 * 降级依赖该备份恢复——**Room 不支持降级打开**。v56 备份中原样保有这 11 列，
 * 恢复即回到本迁移前的 schema；备份缺失时亦无数据后果（11 列无存续读取方）。
 */
internal val MIGRATION_56_57 = object : Migration(56, 57) {
    override fun migrate(db: SupportSQLiteDatabase) {
        rebuildTableDroppingColumns(
            db = db,
            table = "disciples",
            columnsToDrop = V57_DISCIPLES_DROPPED_COLUMNS,
            pkColumns = listOf("id", "slot_id"),
            indices = listOf(
                Triple("index_disciples_name", "disciples(`name`)", false),
                Triple("index_disciples_realm_realmLayer", "disciples(`realm`, `realmLayer`)", false),
                Triple("index_disciples_isAlive_realm", "disciples(`isAlive`, `realm`)", false),
                Triple("index_disciples_isAlive_status", "disciples(`isAlive`, `status`)", false),
                Triple("index_disciples_discipleType", "disciples(`discipleType`)", false)
            )
        )
        Log.i(TAG_V57, "Migration 56→57: dropped ${V57_DISCIPLES_DROPPED_COLUMNS.size} columns from disciples")

        rebuildTableDroppingColumns(
            db = db,
            table = "game_data",
            columnsToDrop = V57_GAME_DATA_DROPPED_COLUMNS,
            pkColumns = listOf("id", "slot_id"),
            indices = listOf(
                Triple("index_game_data_slot_id", "game_data(`slot_id`)", true),
                Triple("index_game_data_lastSaveTime", "game_data(`lastSaveTime`)", false),
                Triple("index_game_data_gameYear_gameMonth", "game_data(`gameYear`, `gameMonth`)", false),
                Triple("index_game_data_sectName", "game_data(`sectName`)", false),
                Triple("index_game_data_spiritStones", "game_data(`spiritStones`)", false)
            )
        )
        Log.i(TAG_V57, "Migration 56→57: dropped ${V57_GAME_DATA_DROPPED_COLUMNS.size} columns from game_data")

        rebuildTableDroppingColumns(
            db = db,
            table = "sect_policy_state",
            columnsToDrop = V57_SECT_POLICY_DROPPED_COLUMNS,
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
            TAG_V57,
            "Migration 56→57: dropped ${V57_SECT_POLICY_DROPPED_COLUMNS.size} " +
                "columns from sect_policy_state"
        )
    }
}
