// GameDatabaseMigrationsV46.kt — 由 GameDatabase.kt 拆分生成（见 GameDatabase.kt addMigrations 列表）
package com.xianxia.sect.data.local

import android.util.Log
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** Migration 文件共用的日志 TAG（各文件独立声明，避免 top-level 冲突） */
private const val TAG = "GameDatabase"

/**
 * v45→v46: 弟子新增基础属性"资质"（disciples 与 disciples_attributes 两表）
 *
 * 背景（2026-08-12 悟性重设计）：悟性唯一作用改为突破率（80 基准每 4 点 +1%，
 * 最多 +10%）；新增固定基础属性"资质"承担修炼速度加成（80 基准每点 +1%，
 * 最多 +40%）。SkillStats 新增 aptitude 字段：
 * - aptitude: 资质（固定属性，创建时按灵根数生成后不可成长；旧档默认 50 为
 *   自愈哨兵值，读档时 DiscipleTables.healDefaultAptitudes() 按灵根数重算）
 *
 * 仅 ALTER TABLE ADD COLUMN（新增列不删列，DEFAULT 50 兼容旧行并作为自愈哨兵）。
 * 注意：资质默认值 50 与序列化默认值（@EncodeDefault）及列直读默认值
 * （DiscipleTables.DEFAULT_APTITUDE）三处统一，保证对象式/列式双入口等价。
 */
internal val MIGRATION_45_46 = object : Migration(45, 46) {
    override fun migrate(db: SupportSQLiteDatabase) {
        for (table in listOf("disciples", "disciples_attributes")) {
            db.execSQL("ALTER TABLE `$table` ADD COLUMN `aptitude` INTEGER NOT NULL DEFAULT 50")
        }
        Log.i(TAG, "Migration 45→46: added aptitude column to disciples & disciples_attributes (DEFAULT 50)")
    }
}
