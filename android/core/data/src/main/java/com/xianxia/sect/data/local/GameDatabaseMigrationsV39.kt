// GameDatabaseMigrationsV39.kt — 由 GameDatabase.kt 拆分生成（见 GameDatabase.kt addMigrations 列表）
package com.xianxia.sect.data.local

import android.util.Log
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** Migration 文件共用的日志 TAG（各文件独立声明，避免 top-level 冲突） */
private const val TAG = "GameDatabase"

        /**
         * v38→v39: 删除弟子级 autoLearnFromWarehouse/autoEquipFromWarehouse 死开关列
         *
         * 背景：弟子级开关（Disciple.autoLearnFromWarehouse、EquipmentSet.autoEquipFromWarehouse）
         * 是死代码——UI 无入口、引擎从不读取（自动学习/装备只认 gameData 级全局策略），
         * 本次重构整体删除字段。
         *
         * 实现：no-op 保留旧列（项目规范 7.2 禁止 DROP COLUMN，SQLite < 3.35 也不支持）。
         * Room 运行时校验为单向（实体期望列 ⊆ 实际列），多余旧列不影响校验；
         * 新装机 DB 按最新实体建表（无旧列），同样通过校验。
         * 影响表：disciples（autoLearnFromWarehouse、autoEquipFromWarehouse）、
         * disciples_extended（autoLearnFromWarehouse）、
         * disciples_equipment（autoEquipFromWarehouse）。
         */
        internal val MIGRATION_38_39 = object : Migration(38, 39) {
            override fun migrate(db: SupportSQLiteDatabase) {
                Log.i(TAG, "Migration 38→39: no-op, legacy per-disciple auto switches columns retained")
            }
        }
