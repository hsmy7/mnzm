// GameDatabaseMigrationsV59.kt — v58→v59 迁移（见 GameDatabase.kt ALL_MIGRATIONS）
package com.xianxia.sect.data.local

import android.util.Log
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** Migration 文件共用的日志 TAG（各文件独立声明，避免 top-level 冲突） */
private const val TAG_V59 = "GameDatabase"

/** v59 迁移中 `disciples` 表的删除列清单（列名以 v58 schema JSON 权威差集为准）。 */
internal val V59_DISCIPLES_DROPPED_COLUMNS: List<String> = listOf(
    "social_masterId"
)

/**
 * v58→v59: 师徒玩法下线（G15 删列）——`disciples` 删除 `social_masterId` 一列。
 *
 * ## 删除面（表 → 列 → 域字段 → 玩法面）
 * | 列 | 域字段 | 玩法面 |
 * |---|---|---|
 * | `social_masterId` | `Disciple.social.masterId`（`SocialData` 唯一字段） | 拜师关系、师徒修炼/突破乘区、突破后师徒赠送 |
 *
 * `social_` 前缀的 @Embedded 组件仅承载这一列，故整列删除即整组件下线；
 * `disciples` 其余 90 列（含 `comprehension` 悟性、`teaching` 教学，以及讲道任职
 * 所依据的弟子本体列）全部存续；讲道槽位本身在 `game_data`，本批未触碰该表。
 *
 * ## 为什么不丢玩家可感知数据（结构性论证，非抽样）
 * `social_masterId` 的唯一写点是拜师事务（ActionId 1591，本批退役、dispatch case 已删），
 * 唯一读点是师徒两个乘区的境界差计算、师徒赠送的关系查找、死亡解绑与「关系」面板。
 * 生产者与消费者同属师徒链自身 ⇒ 玩法整线下线后全仓 `src/main` 对该列读写点为零。
 * 列内容是「下线玩法的内部运行状态」，不是玩家资产：弟子本体、境界、修为、装备、
 * 功法、灵石、悟性、讲道任职等玩家可感知数据全部保留。
 * 旧档该列内容随之失效——玩家侧可感知的后果是「已建立的师徒关系不再提供加成」，
 * 这与玩法下线的产品口径一致（加成随玩法一并归零，不做补偿发放）。
 * ProtoBuf 侧字段号已在两处定义登记 `reserved 93`（`SerializableDisciple` 与
 * `OldSerializableSaveData.SerializableDisciple`），云档 / `.sav` / `.bak` 旧字节按
 * wire 规范静默跳过，旧存档反序列化不受影响；镜像协议 `game_view.proto` 的
 * 字段号 85 同样已 `reserved`，C++ 侧不再 emit。
 *
 * ## 实现
 * SQLite < 3.35.0 不支持 `ALTER TABLE DROP COLUMN`（规则 7.2），经
 * [rebuildTableDroppingColumns] create-copy-drop-rename 重建：PRAGMA 读全列定义
 * → 按名剔除删除列 → 建新表（带回主键）→ `INSERT SELECT` 复制 → 换表 → 重建索引。
 * 幂等：待删列一个都不存在即直接返回（重复执行 / 迁移中断重试安全）。
 * 索引重建与 `Disciple` `@Entity` 现存 `Index` 声明一致，5 条全部保留
 * （name / realm+realmLayer / isAlive+realm / isAlive+status / discipleType）——
 * 本批不删任何索引，被删列也不在任何索引内。
 *
 * ## 回滚
 * 升级前由 `GameDatabase.backupDatabaseForMigration` 落
 * `{db}.pre_migrate_backup.v58`（`wal_checkpoint(TRUNCATE)` 后文件级复制）；
 * 降级依赖该备份恢复——**Room 不支持降级打开**。v58 备份中原样保有该列，
 * 恢复即回到本迁移前的 schema；备份缺失时亦无数据后果（该列无存续读取方）。
 */
internal val MIGRATION_58_59 = object : Migration(58, 59) {
    override fun migrate(db: SupportSQLiteDatabase) {
        rebuildTableDroppingColumns(
            db = db,
            table = "disciples",
            columnsToDrop = V59_DISCIPLES_DROPPED_COLUMNS,
            pkColumns = listOf("id", "slot_id"),
            indices = listOf(
                Triple("index_disciples_name", "disciples(`name`)", false),
                Triple("index_disciples_realm_realmLayer", "disciples(`realm`, `realmLayer`)", false),
                Triple("index_disciples_isAlive_realm", "disciples(`isAlive`, `realm`)", false),
                Triple("index_disciples_isAlive_status", "disciples(`isAlive`, `status`)", false),
                Triple("index_disciples_discipleType", "disciples(`discipleType`)", false)
            )
        )
        Log.i(TAG_V59, "Migration 58→59: dropped ${V59_DISCIPLES_DROPPED_COLUMNS.size} column from disciples")
    }
}
