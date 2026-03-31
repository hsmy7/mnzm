package com.xianxia.sect.data.local

import android.content.Context
import android.util.Log
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import java.io.File

object MigrationStrategy {
    private const val TAG = "MigrationStrategy"

    fun createMigration(fromVersion: Int, toVersion: Int): Migration {
        return object : Migration(fromVersion, toVersion) {
            override fun migrate(db: SupportSQLiteDatabase) {
                Log.i(TAG, "Migrating database from version $fromVersion to $toVersion")

                when (toVersion) {
                    60 -> migrateToV60(db)
                    61 -> migrateToV61(db)
                    62 -> migrateToV62(db)
                    63 -> migrateToV63(db)
                    else -> {
                        Log.w(TAG, "No specific migration for version $toVersion, using fallback")
                        performSafeMigration(db, fromVersion, toVersion)
                    }
                }

                Log.i(TAG, "Migration to version $toVersion completed successfully")
            }
        }
    }

    private fun migrateToV60(db: SupportSQLiteDatabase) {
        Log.i(TAG, "Migrating to version 60: Adding storage optimization tables")

        db.execSQL("""
            CREATE TABLE IF NOT EXISTS storage_metadata (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                slot INTEGER NOT NULL,
                data_type TEXT NOT NULL,
                record_count INTEGER NOT NULL DEFAULT 0,
                last_modified INTEGER NOT NULL,
                checksum TEXT,
                UNIQUE(slot, data_type)
            )
        """)

        db.execSQL("CREATE INDEX IF NOT EXISTS idx_storage_metadata_slot ON storage_metadata (slot)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_storage_metadata_type ON storage_metadata (data_type)")

        db.execSQL("""
            CREATE TABLE IF NOT EXISTS data_version (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                table_name TEXT NOT NULL UNIQUE,
                version INTEGER NOT NULL DEFAULT 1,
                last_updated INTEGER NOT NULL
            )
        """)

        db.execSQL("""
            INSERT OR IGNORE INTO data_version (table_name, version, last_updated)
            SELECT 'game_data', 1, ${System.currentTimeMillis()}
        """)
    }

    private fun migrateToV61(db: SupportSQLiteDatabase) {
        Log.i(TAG, "Migrating to version 61: Adding cross-device sync support")

        db.execSQL("""
            CREATE TABLE IF NOT EXISTS sync_state (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                entity_type TEXT NOT NULL,
                entity_id TEXT NOT NULL,
                local_version INTEGER NOT NULL DEFAULT 1,
                sync_version INTEGER NOT NULL DEFAULT 0,
                last_sync_time INTEGER NOT NULL,
                sync_status TEXT NOT NULL DEFAULT 'pending',
                UNIQUE(entity_type, entity_id)
            )
        """)

        db.execSQL("CREATE INDEX IF NOT EXISTS idx_sync_state_type ON sync_state (entity_type)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_sync_state_status ON sync_state (sync_status)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_sync_state_version ON sync_state (local_version)")

        db.execSQL("""
            CREATE TABLE IF NOT EXISTS conflict_resolution (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                entity_type TEXT NOT NULL,
                entity_id TEXT NOT NULL,
                local_data BLOB,
                remote_data BLOB,
                resolution_strategy TEXT NOT NULL DEFAULT 'manual',
                created_at INTEGER NOT NULL,
                resolved_at INTEGER,
                UNIQUE(entity_type, entity_id)
            )
        """)
    }

    private fun migrateToV62(db: SupportSQLiteDatabase) {
        Log.i(TAG, "Migrating to version 62: Optimizing disciple indexes")

        db.execSQL("DROP INDEX IF EXISTS idx_disciple_slot_realm_alive")
        db.execSQL("DROP INDEX IF EXISTS idx_disciple_slot_status")
        db.execSQL("DROP INDEX IF EXISTS idx_disciple_cultivation")

        db.execSQL("""
            CREATE INDEX IF NOT EXISTS idx_disciple_composite_1 
            ON disciples (slot, isAlive, realm, cultivation DESC)
        """)

        db.execSQL("""
            CREATE INDEX IF NOT EXISTS idx_disciple_composite_2 
            ON disciples (slot, isAlive, status, loyalty DESC)
        """)

        db.execSQL("""
            CREATE INDEX IF NOT EXISTS idx_disciple_composite_3 
            ON disciples (slot, isAlive, age, realm)
        """)

        db.execSQL("ANALYZE disciples")
    }

    private fun migrateToV63(db: SupportSQLiteDatabase) {
        Log.i(TAG, "Migrating to version 63: Adding performance tracking")

        db.execSQL("""
            CREATE TABLE IF NOT EXISTS operation_metrics (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                operation_type TEXT NOT NULL,
                slot INTEGER NOT NULL,
                duration_ms INTEGER NOT NULL,
                record_count INTEGER NOT NULL DEFAULT 0,
                success INTEGER NOT NULL DEFAULT 1,
                error_message TEXT,
                timestamp INTEGER NOT NULL
            )
        """)

        db.execSQL("CREATE INDEX IF NOT EXISTS idx_metrics_type ON operation_metrics (operation_type)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_metrics_time ON operation_metrics (timestamp)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_metrics_slot ON operation_metrics (slot)")

        db.execSQL("""
            CREATE TABLE IF NOT EXISTS cache_stats (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                cache_type TEXT NOT NULL,
                hit_count INTEGER NOT NULL DEFAULT 0,
                miss_count INTEGER NOT NULL DEFAULT 0,
                size_bytes INTEGER NOT NULL DEFAULT 0,
                entry_count INTEGER NOT NULL DEFAULT 0,
                timestamp INTEGER NOT NULL
            )
        """)
    }

    private fun performSafeMigration(db: SupportSQLiteDatabase, fromVersion: Int, toVersion: Int) {
        Log.i(TAG, "Performing safe migration from $fromVersion to $toVersion")

        try {
            val tables = getTableList(db)
            Log.d(TAG, "Existing tables: ${tables.joinToString(", ")}")

            for (table in tables) {
                ensureTableIntegrity(db, table)
            }

            db.execSQL("PRAGMA integrity_check")
            db.execSQL("ANALYZE")

        } catch (e: Exception) {
            Log.e(TAG, "Safe migration failed", e)
            throw e
        }
    }

    private fun getTableList(db: SupportSQLiteDatabase): List<String> {
        val tables = mutableListOf<String>()
        val cursor = db.query(
            "SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%'"
        )
        cursor.use {
            while (it.moveToNext()) {
                tables.add(it.getString(0))
            }
        }
        return tables
    }

    private fun ensureTableIntegrity(db: SupportSQLiteDatabase, tableName: String) {
        try {
            db.execSQL("PRAGMA table_info($tableName)")
            Log.d(TAG, "Table $tableName integrity verified")
        } catch (e: Exception) {
            Log.w(TAG, "Table $tableName may have integrity issues", e)
        }
    }

    fun createBackupBeforeMigration(context: Context, slot: Int): File? {
        return try {
            val dbFile = GameDatabase.getDatabaseFile(context, slot)
            if (!dbFile.exists()) return null

            val backupFile = File(
                dbFile.parent,
                "${dbFile.nameWithoutExtension}_backup_${System.currentTimeMillis()}.db"
            )

            dbFile.copyTo(backupFile, overwrite = true)

            Log.i(TAG, "Created backup before migration: ${backupFile.name}")
            backupFile
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create backup before migration", e)
            null
        }
    }

    fun restoreFromBackup(context: Context, slot: Int, backupFile: File): Boolean {
        return try {
            val dbFile = GameDatabase.getDatabaseFile(context, slot)
            backupFile.copyTo(dbFile, overwrite = true)

            Log.i(TAG, "Restored from backup: ${backupFile.name}")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to restore from backup", e)
            false
        }
    }

    fun cleanupOldBackups(context: Context, slot: Int, keepCount: Int = 3) {
        try {
            val dbDir = GameDatabase.getDatabaseFile(context, slot).parentFile ?: return

            val backups = dbDir.listFiles()
                ?.filter { it.name.contains("_backup_") && it.name.endsWith(".db") }
                ?.sortedByDescending { it.lastModified() }
                ?: return

            backups.drop(keepCount).forEach { backup ->
                backup.delete()
                Log.d(TAG, "Deleted old backup: ${backup.name}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to cleanup old backups", e)
        }
    }
}

object MigrationRegistry {
    private const val CURRENT_VERSION = 63
    private const val MIN_SUPPORTED_VERSION = 50

    private val migrations = mutableMapOf<Pair<Int, Int>, Migration>()

    fun registerMigration(fromVersion: Int, toVersion: Int, migration: Migration) {
        migrations[Pair(fromVersion, toVersion)] = migration
    }

    fun getMigration(fromVersion: Int, toVersion: Int): Migration? {
        return migrations[Pair(fromVersion, toVersion)]
    }

    fun getAllMigrations(): Array<Migration> {
        return migrations.values.toTypedArray()
    }

    fun getCurrentVersion(): Int = CURRENT_VERSION

    fun isVersionSupported(version: Int): Boolean {
        return version in MIN_SUPPORTED_VERSION..CURRENT_VERSION
    }

    fun getMigrationPath(fromVersion: Int, toVersion: Int): List<Migration> {
        val path = mutableListOf<Migration>()
        var current = fromVersion

        while (current < toVersion) {
            val nextVersion = current + 1
            val migration = getMigration(current, nextVersion)
                ?: MigrationStrategy.createMigration(current, nextVersion)

            path.add(migration)
            current = nextVersion
        }

        return path
    }

    init {
        registerMigration(59, 60, MigrationStrategy.createMigration(59, 60))
        registerMigration(60, 61, MigrationStrategy.createMigration(60, 61))
        registerMigration(61, 62, MigrationStrategy.createMigration(61, 62))
        registerMigration(62, 63, MigrationStrategy.createMigration(62, 63))
    }
}

class MigrationValidator(
    private val context: Context
) {
    companion object {
        private const val TAG = "MigrationValidator"
    }

    data class ValidationResult(
        val isValid: Boolean,
        val errors: List<String> = emptyList(),
        val warnings: List<String> = emptyList(),
        val stats: Map<String, Any> = emptyMap()
    )

    fun validateMigration(
        db: SupportSQLiteDatabase,
        fromVersion: Int,
        toVersion: Int
    ): ValidationResult {
        val errors = mutableListOf<String>()
        val warnings = mutableListOf<String>()
        val stats = mutableMapOf<String, Any>()

        try {
            val integrityResult = checkIntegrity(db)
            if (!integrityResult) {
                errors.add("Database integrity check failed")
            }

            val tables = getTableList(db)
            stats["table_count"] = tables.size

            for (table in tables) {
                val count = getRecordCount(db, table)
                stats["records_$table"] = count

                if (count == 0L && table in listOf("game_data", "disciples")) {
                    warnings.add("Table $table is empty after migration")
                }
            }

            val indexCount = getIndexCount(db)
            stats["index_count"] = indexCount

            if (indexCount < tables.size) {
                warnings.add("Some tables may be missing indexes")
            }

            val foreignKeyCheck = checkForeignKeys(db)
            if (!foreignKeyCheck) {
                errors.add("Foreign key constraint violation detected")
            }

        } catch (e: Exception) {
            errors.add("Validation error: ${e.message}")
        }

        return ValidationResult(
            isValid = errors.isEmpty(),
            errors = errors,
            warnings = warnings,
            stats = stats
        )
    }

    private fun checkIntegrity(db: SupportSQLiteDatabase): Boolean {
        val cursor = db.query("PRAGMA integrity_check")
        return cursor.use {
            if (it.moveToFirst()) {
                it.getString(0) == "ok"
            } else {
                false
            }
        }
    }

    private fun getTableList(db: SupportSQLiteDatabase): List<String> {
        val tables = mutableListOf<String>()
        val cursor = db.query(
            "SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%'"
        )
        cursor.use {
            while (it.moveToNext()) {
                tables.add(it.getString(0))
            }
        }
        return tables
    }

    private fun getRecordCount(db: SupportSQLiteDatabase, table: String): Long {
        val cursor = db.query("SELECT COUNT(*) FROM $table")
        return cursor.use {
            if (it.moveToFirst()) it.getLong(0) else 0
        }
    }

    private fun getIndexCount(db: SupportSQLiteDatabase): Int {
        val cursor = db.query(
            "SELECT COUNT(*) FROM sqlite_master WHERE type='index' AND name NOT LIKE 'sqlite_%'"
        )
        return cursor.use {
            if (it.moveToFirst()) it.getInt(0) else 0
        }
    }

    private fun checkForeignKeys(db: SupportSQLiteDatabase): Boolean {
        val cursor = db.query("PRAGMA foreign_key_check")
        return cursor.use {
            it.count == 0
        }
    }

    fun performPostMigrationOptimization(db: SupportSQLiteDatabase) {
        Log.i(TAG, "Performing post-migration optimization")

        try {
            db.execSQL("PRAGMA optimize")
            db.execSQL("ANALYZE")

            val tables = getTableList(db)
            for (table in tables) {
                try {
                    db.execSQL("REINDEX $table")
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to reindex table $table", e)
                }
            }

            Log.i(TAG, "Post-migration optimization completed")
        } catch (e: Exception) {
            Log.e(TAG, "Post-migration optimization failed", e)
        }
    }
}
