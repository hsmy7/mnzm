package com.xianxia.sect.data.migration

import android.util.Log
import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.data.model.SaveData

data class VersionPair(val from: String, val to: String)

interface Migration {
    val fromVersion: String
    val toVersion: String
    fun migrate(data: SaveData): SaveData
}

class MigrationManager {
    companion object {
        private const val TAG = "MigrationManager"
    }
    
    private val migrations = sortedMapOf<VersionPair, Migration>(compareBy { it.from })
    
    fun registerMigration(migration: Migration) {
        val key = VersionPair(migration.fromVersion, migration.toVersion)
        migrations[key] = migration
        Log.d(TAG, "Registered migration: ${migration.fromVersion} -> ${migration.toVersion}")
    }
    
    suspend fun migrate(data: SaveData, targetVersion: String = GameConfig.Game.VERSION): SaveData {
        var currentData = data
        var currentVersion = data.version
        
        if (currentVersion == targetVersion) {
            Log.d(TAG, "No migration needed, already at version $targetVersion")
            return currentData
        }
        
        Log.i(TAG, "Starting migration from $currentVersion to $targetVersion")
        
        var migrationCount = 0
        while (currentVersion != targetVersion) {
            val migration = findNextMigration(currentVersion, targetVersion)
            if (migration == null) {
                Log.w(TAG, "No migration path found from $currentVersion to $targetVersion")
                break
            }
            
            try {
                currentData = migration.migrate(currentData)
                currentVersion = migration.toVersion
                migrationCount++
                Log.d(TAG, "Migrated to version $currentVersion")
            } catch (e: Exception) {
                Log.e(TAG, "Migration failed from ${migration.fromVersion} to ${migration.toVersion}", e)
                break
            }
        }
        
        if (currentVersion != targetVersion) {
            Log.w(TAG, "Migration incomplete: ended at $currentVersion, target was $targetVersion")
        } else {
            Log.i(TAG, "Migration completed successfully after $migrationCount steps")
        }
        
        return currentData
    }
    
    private fun findNextMigration(currentVersion: String, targetVersion: String): Migration? {
        return migrations.values.find { migration ->
            migration.fromVersion == currentVersion && 
            compareVersions(migration.toVersion, targetVersion) <= 0
        }
    }
    
    fun compareVersions(v1: String, v2: String): Int {
        val parts1 = v1.split(".").map { it.toIntOrNull() ?: 0 }
        val parts2 = v2.split(".").map { it.toIntOrNull() ?: 0 }
        
        val maxLen = maxOf(parts1.size, parts2.size)
        for (i in 0 until maxLen) {
            val p1 = parts1.getOrElse(i) { 0 }
            val p2 = parts2.getOrElse(i) { 0 }
            if (p1 != p2) {
                return p1.compareTo(p2)
            }
        }
        return 0
    }
    
    fun getMigrationPath(fromVersion: String, toVersion: String): List<Migration> {
        val path = mutableListOf<Migration>()
        var currentVersion = fromVersion
        
        while (currentVersion != toVersion) {
            val migration = findNextMigration(currentVersion, toVersion)
            if (migration == null) break
            path.add(migration)
            currentVersion = migration.toVersion
        }
        
        return path
    }
    
    fun getRegisteredMigrations(): List<Migration> = migrations.values.toList()
}

class Migration_1_4_84_to_1_4_85 : Migration {
    override val fromVersion = "1.4.84"
    override val toVersion = "1.4.85"
    
    override fun migrate(data: SaveData): SaveData {
        Log.d(TAG, "Migrating from 1.4.84 to 1.4.85: adding alliances")
        val alliances = data.alliances ?: emptyList()
        return data.copy(
            version = toVersion,
            alliances = alliances
        )
    }
    
    companion object {
        private const val TAG = "Migration_1_4_85"
    }
}

class MigrationRegistry {
    companion object {
        fun createDefaultManager(): MigrationManager {
            return MigrationManager().apply {
                registerMigration(Migration_1_4_84_to_1_4_85())
            }
        }
    }
}
