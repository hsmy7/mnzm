// GameDatabaseMigrationsV53.kt — v52→v53 迁移（见 GameDatabase.kt addMigrations 列表）
package com.xianxia.sect.data.local

import android.util.Log
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** Migration 文件共用的日志 TAG（各文件独立声明，避免 top-level 冲突） */
private const val TAG = "GameDatabase"

/** 本迁移删除的表名清单——守卫测试与本文件共用同一权威源 */
internal val V53_DROPPED_TABLES: List<String> = listOf(
    "disciples_core",
    "disciples_combat",
    "disciples_equipment",
    "disciples_extended",
    "disciples_attributes",
    "disciple_compact"
)

/**
 * v52→v53: 存档系统重构 SR-7 的 schema 第二刀——删除 6 张**零读者镜像表**。
 *
 * ## 删除面（表 → 领域类去向）
 * | 表 | 类的现状 |
 * |---|---|
 * | `disciples_core` | `DiscipleCore` 保留为纯领域类（`DiscipleAggregate.core`） |
 * | `disciples_combat` | `DiscipleCombatStats` 保留（`DiscipleStatCalculator` 读它算方差） |
 * | `disciples_equipment` | `DiscipleEquipment` 保留 |
 * | `disciples_extended` | `DiscipleExtended` 保留 |
 * | `disciples_attributes` | `DiscipleAttributes` 保留（`DiscipleStatCalculator` 读它算技能） |
 * | `disciple_compact` | `DiscipleCompact` 全仓零消费者 ⇒ 连类一并删（IN6） |
 *
 * ## 为什么删表不丢任何玩家数据（结构性论证，非抽样）
 * 六表的**唯一生产者**是 `StorageEngineWriteOps.writeDisciples`，其每一行都由
 * `X.fromDisciple(disciple)` 从 `disciples` 表的同一行派生（零外部输入、零计算增量）。
 * 全仓（排除 `build/`）grep 六个 DAO 访问器，命中仅三类：`deleteAll`（三处清槽清单）、
 * `upsertAll`/`insertAll`（上述唯一生产者）、DI provider —— **SELECT 方法零调用者**；
 * C++ 侧（`app/src/main/cpp`）六个表名 0 命中。⇒ 六表是 `disciples` 的纯写侧投影，
 * 删表 = 删冗余副本，玩家可感知数据零丢失；读侧一直走 `discipleDao`（保留）。
 *
 * ## 实现
 * `DROP TABLE IF EXISTS` 幂等重放安全（重复执行零变化），索引随表自动删除，
 * 无需 create-copy-drop-rename（那是删列场景）。
 *
 * ## 回滚
 * 升级前由 `GameDatabase.backupDatabaseForMigration` 落 `{db}.pre_migrate_backup.v52`
 * （先 `wal_checkpoint(TRUNCATE)` 再文件级复制，保留 2 个版本）；降级依赖该备份恢复，
 * **Room 不支持降级打开**。被删表内容由 `disciples` 完整可重建（下一次保存即重新派生，
 * 虽已无写入者），故备份缺失时亦无数据后果。
 */
internal val MIGRATION_52_53 = object : Migration(52, 53) {
    override fun migrate(db: SupportSQLiteDatabase) {
        V53_DROPPED_TABLES.forEach { table ->
            db.execSQL("DROP TABLE IF EXISTS $table")
        }
        Log.i(TAG, "v52→v53 完成：删除 ${V53_DROPPED_TABLES.size} 张零读者镜像表")
    }
}
