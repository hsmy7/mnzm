package com.xianxia.sect.data.local

import android.content.Context
import android.util.Log
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.sqlite.db.SupportSQLiteDatabase
import com.xianxia.sect.core.model.*
import com.xianxia.sect.core.model.production.ProductionSlot
import com.xianxia.sect.data.incremental.ChangeLogEntity
import com.xianxia.sect.data.incremental.ChangeLogDao
import com.xianxia.sect.data.archive.ArchivedBattleLog
import com.xianxia.sect.data.archive.ArchivedGameEvent
import com.xianxia.sect.data.archive.ArchivedDisciple
import com.xianxia.sect.data.archive.ArchivedBattleLogDao
import com.xianxia.sect.data.archive.ArchivedGameEventDao
import com.xianxia.sect.data.archive.ArchivedDiscipleDao
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicInteger

object GameDatabaseConfig {
    const val MEMORY_CACHE_SIZE = 64 * 1024 * 1024
    const val DISK_CACHE_SIZE = 100 * 1024 * 1024
    const val WRITE_BATCH_SIZE = 100
    const val WRITE_DELAY_MS = 1000L
    const val WAL_CHECK_INTERVAL_SECONDS = 30L
    const val WAL_SIZE_THRESHOLD_MB = 10L
    const val WAL_CRITICAL_SIZE_MB = 50L
    const val CHECKPOINT_COOLDOWN_MS = 10000L
    const val QUERY_THREAD_COUNT = 2
}

val MIGRATION_4_5 = object : androidx.room.migration.Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        try {
            Log.i("GameDatabase", "Migrating database from version 4 to 5: recruitList serialization fix (no schema change)")
        } catch (e: Exception) {
            Log.e("GameDatabase", "Migration 4->5 failed", e)
            throw e
        }
    }
}

val MIGRATION_5_6 = object : androidx.room.migration.Migration(5, 6) {
    override fun migrate(db: SupportSQLiteDatabase) {
        try {
            Log.i("GameDatabase", "Migrating database from version 5 to 6: game balance adjustment - damage variance ±20% with 0.1% precision (no schema change)")
        } catch (e: Exception) {
            Log.e("GameDatabase", "Migration 5->6 failed", e)
            throw e
        }
    }
}

val MIGRATION_6_7 = object : androidx.room.migration.Migration(6, 7) {
    override fun migrate(db: SupportSQLiteDatabase) {
        try {
            Log.i("GameDatabase", "Migrating database from version 6 to 7: mission hall refresh mechanism overhaul - random 0-5 tasks every 3 months (no schema change)")
        } catch (e: Exception) {
            Log.e("GameDatabase", "Migration 6->7 failed", e)
            throw e
        }
    }
}

val MIGRATION_7_8 = object : androidx.room.migration.Migration(7, 8) {
    override fun migrate(db: SupportSQLiteDatabase) {
        try {
            Log.i("GameDatabase", "Migrating database from version 7 to 8: fix equipment minRealm - was incorrectly set to rarity tier instead of realm level")
            db.execSQL("UPDATE equipment SET minRealm = 9 WHERE rarity = 1 AND minRealm = 1")
            db.execSQL("UPDATE equipment SET minRealm = 7 WHERE rarity = 2 AND minRealm = 2")
            db.execSQL("UPDATE equipment SET minRealm = 6 WHERE rarity = 3 AND minRealm = 3")
            db.execSQL("UPDATE equipment SET minRealm = 5 WHERE rarity = 4 AND minRealm = 4")
            db.execSQL("UPDATE equipment SET minRealm = 4 WHERE rarity = 5 AND minRealm = 5")
            db.execSQL("UPDATE equipment SET minRealm = 2 WHERE rarity = 6 AND minRealm = 6")
        } catch (e: Exception) {
            Log.e("GameDatabase", "Migration 7->8 failed", e)
            throw e
        }
    }
}

val MIGRATION_8_9 = object : androidx.room.migration.Migration(8, 9) {
    override fun migrate(db: SupportSQLiteDatabase) {
        try {
            Log.i("GameDatabase", "Migrating database from version 8 to 9: fix stacked learned manuals - split isLearned=1 & quantity>1 into separate records")
            val cursor = db.query("SELECT id, slot_id, name, rarity, type, description, stats, skillName, skillDescription, skillType, skillDamageType, skillHits, skillDamageMultiplier, skillCooldown, skillMpCost, skillHealPercent, skillHealType, skillBuffType, skillBuffValue, skillBuffDuration, skillBuffsJson, skillIsAoe, skillTargetScope, minRealm, ownerId, quantity FROM manuals WHERE isLearned = 1 AND quantity > 1")
            cursor.use {
                while (it.moveToNext()) {
                    val originalId = it.getString(it.getColumnIndexOrThrow("id"))
                    val slotId = it.getInt(it.getColumnIndexOrThrow("slot_id"))
                    val name = it.getString(it.getColumnIndexOrThrow("name"))
                    val rarity = it.getInt(it.getColumnIndexOrThrow("rarity"))
                    val type = it.getString(it.getColumnIndexOrThrow("type"))
                    val description = it.getString(it.getColumnIndexOrThrow("description"))
                    val stats = it.getString(it.getColumnIndexOrThrow("stats"))
                    val skillName = it.getString(it.getColumnIndexOrThrow("skillName"))
                    val skillDescription = it.getString(it.getColumnIndexOrThrow("skillDescription"))
                    val skillType = it.getString(it.getColumnIndexOrThrow("skillType"))
                    val skillDamageType = it.getString(it.getColumnIndexOrThrow("skillDamageType"))
                    val skillHits = it.getInt(it.getColumnIndexOrThrow("skillHits"))
                    val skillDamageMultiplier = it.getDouble(it.getColumnIndexOrThrow("skillDamageMultiplier"))
                    val skillCooldown = it.getInt(it.getColumnIndexOrThrow("skillCooldown"))
                    val skillMpCost = it.getInt(it.getColumnIndexOrThrow("skillMpCost"))
                    val skillHealPercent = it.getDouble(it.getColumnIndexOrThrow("skillHealPercent"))
                    val skillHealType = it.getString(it.getColumnIndexOrThrow("skillHealType"))
                    val skillBuffType = it.getString(it.getColumnIndexOrThrow("skillBuffType"))
                    val skillBuffValue = it.getDouble(it.getColumnIndexOrThrow("skillBuffValue"))
                    val skillBuffDuration = it.getInt(it.getColumnIndexOrThrow("skillBuffDuration"))
                    val skillBuffsJson = it.getString(it.getColumnIndexOrThrow("skillBuffsJson"))
                    val skillIsAoe = it.getInt(it.getColumnIndexOrThrow("skillIsAoe"))
                    val skillTargetScope = it.getString(it.getColumnIndexOrThrow("skillTargetScope"))
                    val minRealm = it.getInt(it.getColumnIndexOrThrow("minRealm"))
                    val ownerId = it.getString(it.getColumnIndexOrThrow("ownerId"))
                    val quantity = it.getInt(it.getColumnIndexOrThrow("quantity"))
                    val remainingQty = quantity - 1

                    db.execSQL("UPDATE manuals SET quantity = 1 WHERE id = ?", arrayOf(originalId))

                    val newId = java.util.UUID.randomUUID().toString()
                    db.execSQL(
                        "INSERT INTO manuals (id, slot_id, name, rarity, type, description, stats, skillName, skillDescription, skillType, skillDamageType, skillHits, skillDamageMultiplier, skillCooldown, skillMpCost, skillHealPercent, skillHealType, skillBuffType, skillBuffValue, skillBuffDuration, skillBuffsJson, skillIsAoe, skillTargetScope, minRealm, ownerId, isLearned, quantity, isLocked) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                        arrayOf(newId, slotId, name, rarity, type, description, stats, skillName, skillDescription, skillType, skillDamageType, skillHits, skillDamageMultiplier, skillCooldown, skillMpCost, skillHealPercent, skillHealType, skillBuffType, skillBuffValue, skillBuffDuration, skillBuffsJson, skillIsAoe, skillTargetScope, minRealm, null, 0, remainingQty, 0)
                    )
                }
            }
        } catch (e: Exception) {
            Log.e("GameDatabase", "Migration 8->9 failed", e)
            throw e
        }
    }
}

val MIGRATION_9_10 = object : androidx.room.migration.Migration(9, 10) {
    override fun migrate(db: SupportSQLiteDatabase) {
        try {
            Log.i("GameDatabase", "Migrating database from version 9 to 10: fix stacked equipped equipment - split isEquipped=1 & quantity>1 into separate records")
            val cursor = db.query("SELECT id, slot_id, name, rarity, slot, description, physicalAttack, magicAttack, physicalDefense, magicDefense, speed, hp, mp, critChance, nurtureLevel, nurtureProgress, minRealm, ownerId, quantity FROM equipment WHERE isEquipped = 1 AND quantity > 1")
            cursor.use {
                while (it.moveToNext()) {
                    val originalId = it.getString(it.getColumnIndexOrThrow("id"))
                    val slotId = it.getInt(it.getColumnIndexOrThrow("slot_id"))
                    val name = it.getString(it.getColumnIndexOrThrow("name"))
                    val rarity = it.getInt(it.getColumnIndexOrThrow("rarity"))
                    val slot = it.getString(it.getColumnIndexOrThrow("slot"))
                    val description = it.getString(it.getColumnIndexOrThrow("description"))
                    val physicalAttack = it.getInt(it.getColumnIndexOrThrow("physicalAttack"))
                    val magicAttack = it.getInt(it.getColumnIndexOrThrow("magicAttack"))
                    val physicalDefense = it.getInt(it.getColumnIndexOrThrow("physicalDefense"))
                    val magicDefense = it.getInt(it.getColumnIndexOrThrow("magicDefense"))
                    val speed = it.getInt(it.getColumnIndexOrThrow("speed"))
                    val hp = it.getInt(it.getColumnIndexOrThrow("hp"))
                    val mp = it.getInt(it.getColumnIndexOrThrow("mp"))
                    val critChance = it.getDouble(it.getColumnIndexOrThrow("critChance"))
                    val nurtureLevel = it.getInt(it.getColumnIndexOrThrow("nurtureLevel"))
                    val nurtureProgress = it.getDouble(it.getColumnIndexOrThrow("nurtureProgress"))
                    val minRealm = it.getInt(it.getColumnIndexOrThrow("minRealm"))
                    val ownerId = it.getString(it.getColumnIndexOrThrow("ownerId"))
                    val quantity = it.getInt(it.getColumnIndexOrThrow("quantity"))
                    val remainingQty = quantity - 1

                    db.execSQL("UPDATE equipment SET quantity = 1 WHERE id = ?", arrayOf(originalId))

                    val newId = java.util.UUID.randomUUID().toString()
                    db.execSQL(
                        "INSERT INTO equipment (id, slot_id, name, rarity, slot, description, physicalAttack, magicAttack, physicalDefense, magicDefense, speed, hp, mp, critChance, nurtureLevel, nurtureProgress, minRealm, ownerId, isEquipped, quantity, isLocked) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                        arrayOf(newId, slotId, name, rarity, slot, description, physicalAttack, magicAttack, physicalDefense, magicDefense, speed, hp, mp, critChance, nurtureLevel, nurtureProgress, minRealm, null, 0, remainingQty, 0)
                    )
                }
            }
        } catch (e: Exception) {
            Log.e("GameDatabase", "Migration 9->10 failed", e)
            throw e
        }
    }
}

val MIGRATION_10_11 = object : androidx.room.migration.Migration(10, 11) {
    override fun migrate(db: SupportSQLiteDatabase) {
        try {
            Log.i("GameDatabase", "Migrating database from version 10 to 11: clear nurtureLevel/nurtureProgress on unequipped items, clear orphaned manual proficiencies")
            db.execSQL("UPDATE equipment SET nurtureLevel = 0, nurtureProgress = 0.0 WHERE isEquipped = 0 AND (nurtureLevel > 0 OR nurtureProgress > 0.0)")
        } catch (e: Exception) {
            Log.e("GameDatabase", "Migration 10->11 failed", e)
            throw e
        }
    }
}

val MIGRATION_11_12 = object : androidx.room.migration.Migration(11, 12) {
    override fun migrate(db: SupportSQLiteDatabase) {
        try {
            Log.i("GameDatabase", "Migrating database from version 11 to 12: split equipment and manuals into stack/instance tables")

            db.execSQL("""
                CREATE TABLE IF NOT EXISTS equipment_stacks (
                    id TEXT NOT NULL,
                    slot_id INTEGER NOT NULL,
                    name TEXT NOT NULL DEFAULT '',
                    rarity INTEGER NOT NULL DEFAULT 1,
                    description TEXT NOT NULL DEFAULT '',
                    slot TEXT NOT NULL DEFAULT 'WEAPON',
                    physicalAttack INTEGER NOT NULL DEFAULT 0,
                    magicAttack INTEGER NOT NULL DEFAULT 0,
                    physicalDefense INTEGER NOT NULL DEFAULT 0,
                    magicDefense INTEGER NOT NULL DEFAULT 0,
                    speed INTEGER NOT NULL DEFAULT 0,
                    hp INTEGER NOT NULL DEFAULT 0,
                    mp INTEGER NOT NULL DEFAULT 0,
                    critChance REAL NOT NULL DEFAULT 0.0,
                    minRealm INTEGER NOT NULL DEFAULT 9,
                    quantity INTEGER NOT NULL DEFAULT 1,
                    isLocked INTEGER NOT NULL DEFAULT 0,
                    PRIMARY KEY(id, slot_id)
                )
            """)
        db.execSQL("CREATE INDEX IF NOT EXISTS index_equipment_stacks_name ON equipment_stacks(name)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_equipment_stacks_rarity ON equipment_stacks(rarity)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_equipment_stacks_slot ON equipment_stacks(slot)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_equipment_stacks_rarity_slot ON equipment_stacks(rarity, slot)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_equipment_stacks_minRealm ON equipment_stacks(minRealm)")

        db.execSQL("""
            CREATE TABLE IF NOT EXISTS equipment_instances (
                id TEXT NOT NULL,
                slot_id INTEGER NOT NULL,
                name TEXT NOT NULL DEFAULT '',
                rarity INTEGER NOT NULL DEFAULT 1,
                description TEXT NOT NULL DEFAULT '',
                slot TEXT NOT NULL DEFAULT 'WEAPON',
                physicalAttack INTEGER NOT NULL DEFAULT 0,
                magicAttack INTEGER NOT NULL DEFAULT 0,
                physicalDefense INTEGER NOT NULL DEFAULT 0,
                magicDefense INTEGER NOT NULL DEFAULT 0,
                speed INTEGER NOT NULL DEFAULT 0,
                hp INTEGER NOT NULL DEFAULT 0,
                mp INTEGER NOT NULL DEFAULT 0,
                critChance REAL NOT NULL DEFAULT 0.0,
                nurtureLevel INTEGER NOT NULL DEFAULT 0,
                nurtureProgress REAL NOT NULL DEFAULT 0.0,
                minRealm INTEGER NOT NULL DEFAULT 9,
                ownerId TEXT,
                isEquipped INTEGER NOT NULL DEFAULT 0,
                isLocked INTEGER NOT NULL DEFAULT 0,
                PRIMARY KEY(id, slot_id)
            )
        """)
        db.execSQL("CREATE INDEX IF NOT EXISTS index_equipment_instances_name ON equipment_instances(name)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_equipment_instances_rarity ON equipment_instances(rarity)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_equipment_instances_slot ON equipment_instances(slot)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_equipment_instances_ownerId ON equipment_instances(ownerId)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_equipment_instances_rarity_slot ON equipment_instances(rarity, slot)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_equipment_instances_minRealm ON equipment_instances(minRealm)")

        db.execSQL("""
            INSERT INTO equipment_stacks (id, slot_id, name, rarity, description, slot, physicalAttack, magicAttack, physicalDefense, magicDefense, speed, hp, mp, critChance, minRealm, quantity, isLocked)
            SELECT id, slot_id, name, rarity, description, slot, physicalAttack, magicAttack, physicalDefense, magicDefense, speed, hp, mp, critChance, minRealm, quantity, isLocked
            FROM equipment WHERE ownerId IS NULL OR isEquipped = 0
        """)

        db.execSQL("""
            INSERT INTO equipment_instances (id, slot_id, name, rarity, description, slot, physicalAttack, magicAttack, physicalDefense, magicDefense, speed, hp, mp, critChance, nurtureLevel, nurtureProgress, minRealm, ownerId, isEquipped, isLocked)
            SELECT id, slot_id, name, rarity, description, slot, physicalAttack, magicAttack, physicalDefense, magicDefense, speed, hp, mp, critChance, nurtureLevel, nurtureProgress, minRealm, ownerId, isEquipped, 0
            FROM equipment WHERE ownerId IS NOT NULL AND isEquipped = 1
        """)

        db.execSQL("""
            CREATE TABLE IF NOT EXISTS manual_stacks (
                id TEXT NOT NULL,
                slot_id INTEGER NOT NULL,
                name TEXT NOT NULL DEFAULT '',
                rarity INTEGER NOT NULL DEFAULT 1,
                description TEXT NOT NULL DEFAULT '',
                type TEXT NOT NULL DEFAULT 'MIND',
                stats TEXT NOT NULL DEFAULT '{}',
                skillName TEXT,
                skillDescription TEXT,
                skillType TEXT NOT NULL DEFAULT 'attack',
                skillDamageType TEXT NOT NULL DEFAULT 'physical',
                skillHits INTEGER NOT NULL DEFAULT 1,
                skillDamageMultiplier REAL NOT NULL DEFAULT 1.0,
                skillCooldown INTEGER NOT NULL DEFAULT 3,
                skillMpCost INTEGER NOT NULL DEFAULT 10,
                skillHealPercent REAL NOT NULL DEFAULT 0.0,
                skillHealType TEXT NOT NULL DEFAULT 'hp',
                skillBuffType TEXT,
                skillBuffValue REAL NOT NULL DEFAULT 0.0,
                skillBuffDuration INTEGER NOT NULL DEFAULT 0,
                skillBuffsJson TEXT NOT NULL DEFAULT '',
                skillIsAoe INTEGER NOT NULL DEFAULT 0,
                skillTargetScope TEXT NOT NULL DEFAULT 'self',
                minRealm INTEGER NOT NULL DEFAULT 9,
                quantity INTEGER NOT NULL DEFAULT 1,
                isLocked INTEGER NOT NULL DEFAULT 0,
                PRIMARY KEY(id, slot_id)
            )
        """)
        db.execSQL("CREATE INDEX IF NOT EXISTS index_manual_stacks_name ON manual_stacks(name)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_manual_stacks_rarity ON manual_stacks(rarity)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_manual_stacks_type ON manual_stacks(type)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_manual_stacks_rarity_type ON manual_stacks(rarity, type)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_manual_stacks_minRealm ON manual_stacks(minRealm)")

        db.execSQL("""
            CREATE TABLE IF NOT EXISTS manual_instances (
                id TEXT NOT NULL,
                slot_id INTEGER NOT NULL,
                name TEXT NOT NULL DEFAULT '',
                rarity INTEGER NOT NULL DEFAULT 1,
                description TEXT NOT NULL DEFAULT '',
                type TEXT NOT NULL DEFAULT 'MIND',
                stats TEXT NOT NULL DEFAULT '{}',
                skillName TEXT,
                skillDescription TEXT,
                skillType TEXT NOT NULL DEFAULT 'attack',
                skillDamageType TEXT NOT NULL DEFAULT 'physical',
                skillHits INTEGER NOT NULL DEFAULT 1,
                skillDamageMultiplier REAL NOT NULL DEFAULT 1.0,
                skillCooldown INTEGER NOT NULL DEFAULT 3,
                skillMpCost INTEGER NOT NULL DEFAULT 10,
                skillHealPercent REAL NOT NULL DEFAULT 0.0,
                skillHealType TEXT NOT NULL DEFAULT 'hp',
                skillBuffType TEXT,
                skillBuffValue REAL NOT NULL DEFAULT 0.0,
                skillBuffDuration INTEGER NOT NULL DEFAULT 0,
                skillBuffsJson TEXT NOT NULL DEFAULT '',
                skillIsAoe INTEGER NOT NULL DEFAULT 0,
                skillTargetScope TEXT NOT NULL DEFAULT 'self',
                minRealm INTEGER NOT NULL DEFAULT 9,
                ownerId TEXT,
                isLearned INTEGER NOT NULL DEFAULT 0,
                isLocked INTEGER NOT NULL DEFAULT 0,
                PRIMARY KEY(id, slot_id)
            )
        """)
        db.execSQL("CREATE INDEX IF NOT EXISTS index_manual_instances_name ON manual_instances(name)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_manual_instances_rarity ON manual_instances(rarity)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_manual_instances_type ON manual_instances(type)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_manual_instances_ownerId ON manual_instances(ownerId)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_manual_instances_minRealm ON manual_instances(minRealm)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_manual_instances_rarity_type ON manual_instances(rarity, type)")

        db.execSQL("""
            INSERT INTO manual_stacks (id, slot_id, name, rarity, description, type, stats, skillName, skillDescription, skillType, skillDamageType, skillHits, skillDamageMultiplier, skillCooldown, skillMpCost, skillHealPercent, skillHealType, skillBuffType, skillBuffValue, skillBuffDuration, skillBuffsJson, skillIsAoe, skillTargetScope, minRealm, quantity, isLocked)
            SELECT id, slot_id, name, rarity, description, type, stats, skillName, skillDescription, skillType, skillDamageType, skillHits, skillDamageMultiplier, skillCooldown, skillMpCost, skillHealPercent, skillHealType, skillBuffType, skillBuffValue, skillBuffDuration, skillBuffsJson, skillIsAoe, skillTargetScope, minRealm, quantity, isLocked
            FROM manuals WHERE ownerId IS NULL OR isLearned = 0
        """)

        db.execSQL("""
            INSERT INTO manual_instances (id, slot_id, name, rarity, description, type, stats, skillName, skillDescription, skillType, skillDamageType, skillHits, skillDamageMultiplier, skillCooldown, skillMpCost, skillHealPercent, skillHealType, skillBuffType, skillBuffValue, skillBuffDuration, skillBuffsJson, skillIsAoe, skillTargetScope, minRealm, ownerId, isLearned, isLocked)
            SELECT id, slot_id, name, rarity, description, type, stats, skillName, skillDescription, skillType, skillDamageType, skillHits, skillDamageMultiplier, skillCooldown, skillMpCost, skillHealPercent, skillHealType, skillBuffType, skillBuffValue, skillBuffDuration, skillBuffsJson, skillIsAoe, skillTargetScope, minRealm, ownerId, isLearned, 0
            FROM manuals WHERE ownerId IS NOT NULL AND isLearned = 1
        """)

        db.execSQL("DROP TABLE IF EXISTS equipment")
        db.execSQL("DROP TABLE IF EXISTS manuals")

        mergeStacks(db, "equipment_stacks", "name, rarity, slot, slot_id", 99)
        mergeStacks(db, "manual_stacks", "name, rarity, type, slot_id", 99)
        } catch (e: Exception) {
            Log.e("GameDatabase", "Migration 11->12 failed", e)
            throw e
        }
    }
}

val MIGRATION_12_13 = object : androidx.room.migration.Migration(12, 13) {
    override fun migrate(db: SupportSQLiteDatabase) {
        try {
            Log.i("GameDatabase", "Migrating database from version 12 to 13: remove isLocked from instance tables, merge duplicate stacks")

        db.execSQL("""
            CREATE TABLE equipment_instances_new (
                id TEXT NOT NULL,
                slot_id INTEGER NOT NULL,
                name TEXT NOT NULL DEFAULT '',
                rarity INTEGER NOT NULL DEFAULT 1,
                description TEXT NOT NULL DEFAULT '',
                slot TEXT NOT NULL DEFAULT 'WEAPON',
                physicalAttack INTEGER NOT NULL DEFAULT 0,
                magicAttack INTEGER NOT NULL DEFAULT 0,
                physicalDefense INTEGER NOT NULL DEFAULT 0,
                magicDefense INTEGER NOT NULL DEFAULT 0,
                speed INTEGER NOT NULL DEFAULT 0,
                hp INTEGER NOT NULL DEFAULT 0,
                mp INTEGER NOT NULL DEFAULT 0,
                critChance REAL NOT NULL DEFAULT 0.0,
                nurtureLevel INTEGER NOT NULL DEFAULT 0,
                nurtureProgress REAL NOT NULL DEFAULT 0.0,
                minRealm INTEGER NOT NULL DEFAULT 9,
                ownerId TEXT,
                isEquipped INTEGER NOT NULL DEFAULT 0,
                PRIMARY KEY(id, slot_id)
            )
        """)
        db.execSQL("""
            INSERT INTO equipment_instances_new (id, slot_id, name, rarity, description, slot, physicalAttack, magicAttack, physicalDefense, magicDefense, speed, hp, mp, critChance, nurtureLevel, nurtureProgress, minRealm, ownerId, isEquipped)
            SELECT id, slot_id, name, rarity, description, slot, physicalAttack, magicAttack, physicalDefense, magicDefense, speed, hp, mp, critChance, nurtureLevel, nurtureProgress, minRealm, ownerId, isEquipped
            FROM equipment_instances
        """)
        db.execSQL("DROP TABLE equipment_instances")
        db.execSQL("ALTER TABLE equipment_instances_new RENAME TO equipment_instances")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_equipment_instances_name ON equipment_instances(name)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_equipment_instances_rarity ON equipment_instances(rarity)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_equipment_instances_slot ON equipment_instances(slot)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_equipment_instances_ownerId ON equipment_instances(ownerId)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_equipment_instances_rarity_slot ON equipment_instances(rarity, slot)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_equipment_instances_minRealm ON equipment_instances(minRealm)")

        db.execSQL("""
            CREATE TABLE manual_instances_new (
                id TEXT NOT NULL,
                slot_id INTEGER NOT NULL,
                name TEXT NOT NULL DEFAULT '',
                rarity INTEGER NOT NULL DEFAULT 1,
                description TEXT NOT NULL DEFAULT '',
                type TEXT NOT NULL DEFAULT 'MIND',
                stats TEXT NOT NULL DEFAULT '{}',
                skillName TEXT,
                skillDescription TEXT,
                skillType TEXT NOT NULL DEFAULT 'attack',
                skillDamageType TEXT NOT NULL DEFAULT 'physical',
                skillHits INTEGER NOT NULL DEFAULT 1,
                skillDamageMultiplier REAL NOT NULL DEFAULT 1.0,
                skillCooldown INTEGER NOT NULL DEFAULT 3,
                skillMpCost INTEGER NOT NULL DEFAULT 10,
                skillHealPercent REAL NOT NULL DEFAULT 0.0,
                skillHealType TEXT NOT NULL DEFAULT 'hp',
                skillBuffType TEXT,
                skillBuffValue REAL NOT NULL DEFAULT 0.0,
                skillBuffDuration INTEGER NOT NULL DEFAULT 0,
                skillBuffsJson TEXT NOT NULL DEFAULT '',
                skillIsAoe INTEGER NOT NULL DEFAULT 0,
                skillTargetScope TEXT NOT NULL DEFAULT 'self',
                minRealm INTEGER NOT NULL DEFAULT 9,
                ownerId TEXT,
                isLearned INTEGER NOT NULL DEFAULT 0,
                PRIMARY KEY(id, slot_id)
            )
        """)
        db.execSQL("""
            INSERT INTO manual_instances_new (id, slot_id, name, rarity, description, type, stats, skillName, skillDescription, skillType, skillDamageType, skillHits, skillDamageMultiplier, skillCooldown, skillMpCost, skillHealPercent, skillHealType, skillBuffType, skillBuffValue, skillBuffDuration, skillBuffsJson, skillIsAoe, skillTargetScope, minRealm, ownerId, isLearned)
            SELECT id, slot_id, name, rarity, description, type, stats, skillName, skillDescription, skillType, skillDamageType, skillHits, skillDamageMultiplier, skillCooldown, skillMpCost, skillHealPercent, skillHealType, skillBuffType, skillBuffValue, skillBuffDuration, skillBuffsJson, skillIsAoe, skillTargetScope, minRealm, ownerId, isLearned
            FROM manual_instances
        """)
        db.execSQL("DROP TABLE manual_instances")
        db.execSQL("ALTER TABLE manual_instances_new RENAME TO manual_instances")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_manual_instances_name ON manual_instances(name)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_manual_instances_rarity ON manual_instances(rarity)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_manual_instances_type ON manual_instances(type)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_manual_instances_ownerId ON manual_instances(ownerId)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_manual_instances_minRealm ON manual_instances(minRealm)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_manual_instances_rarity_type ON manual_instances(rarity, type)")

        // v12期间可能通过returnEquipmentToStack产生新的重复Stack，再次合并确保数据一致性
        mergeStacks(db, "equipment_stacks", "name, rarity, slot, slot_id", 99)
        mergeStacks(db, "manual_stacks", "name, rarity, type, slot_id", 99)
        } catch (e: Exception) {
            Log.e("GameDatabase", "Migration 12->13 failed", e)
            throw e
        }
    }
}

val MIGRATION_13_14 = object : androidx.room.migration.Migration(13, 14) {
    override fun migrate(db: SupportSQLiteDatabase) {
        try {
            Log.i("GameDatabase", "Migrating database from version 13 to 14: add surname field to disciples and disciples_core")
            db.execSQL("ALTER TABLE disciples ADD COLUMN surname TEXT NOT NULL DEFAULT ''")
            db.execSQL("ALTER TABLE disciples_core ADD COLUMN surname TEXT NOT NULL DEFAULT ''")
        } catch (e: Exception) {
            Log.e("GameDatabase", "Migration 13->14 failed", e)
            throw e
        }
    }
}

val MIGRATION_14_15 = object : androidx.room.migration.Migration(14, 15) {
    override fun migrate(db: SupportSQLiteDatabase) {
        try {
            Log.i("GameDatabase", "Migrating database from version 14 to 15: add isGameOver field to game_data")
            db.execSQL("ALTER TABLE game_data ADD COLUMN isGameOver INTEGER NOT NULL DEFAULT 0")
        } catch (e: Exception) {
            Log.e("GameDatabase", "Migration 14->15 failed", e)
            throw e
        }
    }
}

val MIGRATION_15_16 = object : androidx.room.migration.Migration(15, 16) {
    override fun migrate(db: SupportSQLiteDatabase) {
        try {
            Log.i("GameDatabase", "Migrating database from version 15 to 16: Split GameData into sub-tables")

            db.execSQL("""
                CREATE TABLE IF NOT EXISTS game_data_core (
                    id TEXT NOT NULL,
                    slot_id INTEGER NOT NULL,
                    sectName TEXT NOT NULL DEFAULT '青云宗',
                    currentSlot INTEGER NOT NULL DEFAULT 1,
                    gameYear INTEGER NOT NULL DEFAULT 1,
                    gameMonth INTEGER NOT NULL DEFAULT 1,
                    gameDay INTEGER NOT NULL DEFAULT 1,
                    isGameStarted INTEGER NOT NULL DEFAULT 0,
                    gameSpeed INTEGER NOT NULL DEFAULT 1,
                    spiritStones INTEGER NOT NULL DEFAULT 1000,
                    spiritHerbs INTEGER NOT NULL DEFAULT 0,
                    sectCultivation REAL NOT NULL DEFAULT 0.0,
                    autoSaveIntervalMonths INTEGER NOT NULL DEFAULT 3,
                    monthlySalary TEXT NOT NULL DEFAULT '',
                    monthlySalaryEnabled TEXT NOT NULL DEFAULT '',
                    playerProtectionEnabled INTEGER NOT NULL DEFAULT 1,
                    playerProtectionStartYear INTEGER NOT NULL DEFAULT 1,
                    playerHasAttackedAI INTEGER NOT NULL DEFAULT 0,
                    playerAllianceSlots INTEGER NOT NULL DEFAULT 3,
                    smartBattleEnabled INTEGER NOT NULL DEFAULT 0,
                    lastSaveTime INTEGER NOT NULL DEFAULT 0,
                    isGameOver INTEGER NOT NULL DEFAULT 0,
                    PRIMARY KEY(id, slot_id)
                )
            """)

            db.execSQL("""
                CREATE TABLE IF NOT EXISTS game_data_world_map (
                    id TEXT NOT NULL,
                    slot_id INTEGER NOT NULL,
                    worldMapSects TEXT NOT NULL DEFAULT '',
                    sectDetails TEXT NOT NULL DEFAULT '',
                    exploredSects TEXT NOT NULL DEFAULT '',
                    scoutInfo TEXT NOT NULL DEFAULT '',
                    sectRelations TEXT NOT NULL DEFAULT '',
                    PRIMARY KEY(id, slot_id),
                    FOREIGN KEY(id, slot_id) REFERENCES game_data(id, slot_id) ON DELETE CASCADE
                )
            """)

            db.execSQL("""
                CREATE TABLE IF NOT EXISTS game_data_buildings (
                    id TEXT NOT NULL,
                    slot_id INTEGER NOT NULL,
                    productionSlots TEXT NOT NULL DEFAULT '',
                    spiritMineSlots TEXT NOT NULL DEFAULT '',
                    librarySlots TEXT NOT NULL DEFAULT '',
                    PRIMARY KEY(id, slot_id),
                    FOREIGN KEY(id, slot_id) REFERENCES game_data(id, slot_id) ON DELETE CASCADE
                )
            """)

            db.execSQL("""
                CREATE TABLE IF NOT EXISTS game_data_economy (
                    id TEXT NOT NULL,
                    slot_id INTEGER NOT NULL,
                    travelingMerchantItems TEXT NOT NULL DEFAULT '',
                    merchantLastRefreshYear INTEGER NOT NULL DEFAULT 0,
                    merchantRefreshCount INTEGER NOT NULL DEFAULT 0,
                    playerListedItems TEXT NOT NULL DEFAULT '',
                    PRIMARY KEY(id, slot_id),
                    FOREIGN KEY(id, slot_id) REFERENCES game_data(id, slot_id) ON DELETE CASCADE
                )
            """)

            db.execSQL("""
                CREATE TABLE IF NOT EXISTS game_data_organization (
                    id TEXT NOT NULL,
                    slot_id INTEGER NOT NULL,
                    elderSlots TEXT NOT NULL DEFAULT '',
                    alliances TEXT NOT NULL DEFAULT '',
                    battleTeam TEXT NOT NULL DEFAULT '',
                    aiBattleTeams TEXT NOT NULL DEFAULT '',
                    sectPolicies TEXT NOT NULL DEFAULT '',
                    activeMissions TEXT NOT NULL DEFAULT '',
                    availableMissions TEXT NOT NULL DEFAULT '',
                    usedRedeemCodes TEXT NOT NULL DEFAULT '',
                    PRIMARY KEY(id, slot_id),
                    FOREIGN KEY(id, slot_id) REFERENCES game_data(id, slot_id) ON DELETE CASCADE
                )
            """)

            db.execSQL("""
                CREATE TABLE IF NOT EXISTS game_data_exploration (
                    id TEXT NOT NULL,
                    slot_id INTEGER NOT NULL,
                    recruitList TEXT NOT NULL DEFAULT '',
                    lastRecruitYear INTEGER NOT NULL DEFAULT 0,
                    cultivatorCaves TEXT NOT NULL DEFAULT '',
                    caveExplorationTeams TEXT NOT NULL DEFAULT '',
                    aiCaveTeams TEXT NOT NULL DEFAULT '',
                    unlockedDungeons TEXT NOT NULL DEFAULT '',
                    unlockedRecipes TEXT NOT NULL DEFAULT '',
                    unlockedManuals TEXT NOT NULL DEFAULT '',
                    manualProficiencies TEXT NOT NULL DEFAULT '',
                    pendingCompetitionResults TEXT NOT NULL DEFAULT '',
                    lastCompetitionYear INTEGER NOT NULL DEFAULT 0,
                    PRIMARY KEY(id, slot_id),
                    FOREIGN KEY(id, slot_id) REFERENCES game_data(id, slot_id) ON DELETE CASCADE
                )
            """)

            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_game_data_core_slot_id ON game_data_core(slot_id)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_game_data_core_lastSaveTime ON game_data_core(lastSaveTime)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_game_data_core_gameYear_gameMonth ON game_data_core(gameYear, gameMonth)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_game_data_core_sectName ON game_data_core(sectName)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_game_data_core_spiritStones ON game_data_core(spiritStones)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_game_data_core_isGameStarted ON game_data_core(isGameStarted)")

            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_game_data_world_map_slot_id ON game_data_world_map(slot_id)")
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_game_data_buildings_slot_id ON game_data_buildings(slot_id)")
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_game_data_economy_slot_id ON game_data_economy(slot_id)")
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_game_data_organization_slot_id ON game_data_organization(slot_id)")
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_game_data_exploration_slot_id ON game_data_exploration(slot_id)")

            db.execSQL("""
                INSERT INTO game_data_core (id, slot_id, sectName, currentSlot, gameYear, gameMonth, gameDay,
                    isGameStarted, gameSpeed, spiritStones, spiritHerbs, sectCultivation,
                    autoSaveIntervalMonths, monthlySalary, monthlySalaryEnabled,
                    playerProtectionEnabled, playerProtectionStartYear, playerHasAttackedAI,
                    playerAllianceSlots, smartBattleEnabled, lastSaveTime, isGameOver)
                SELECT id, slot_id, sectName, currentSlot, gameYear, gameMonth, gameDay,
                    isGameStarted, gameSpeed, spiritStones, spiritHerbs, sectCultivation,
                    autoSaveIntervalMonths, monthlySalary, monthlySalaryEnabled,
                    playerProtectionEnabled, playerProtectionStartYear, playerHasAttackedAI,
                    playerAllianceSlots, smartBattleEnabled, lastSaveTime, isGameOver
                FROM game_data
            """)

            db.execSQL("""
                INSERT INTO game_data_world_map (id, slot_id, worldMapSects, sectDetails, exploredSects, scoutInfo, sectRelations)
                SELECT id, slot_id, worldMapSects, sectDetails, exploredSects, scoutInfo, sectRelations
                FROM game_data
            """)

            db.execSQL("""
                INSERT INTO game_data_buildings (id, slot_id, productionSlots, spiritMineSlots, librarySlots)
                SELECT id, slot_id, productionSlots, spiritMineSlots, librarySlots
                FROM game_data
            """)

            db.execSQL("""
                INSERT INTO game_data_economy (id, slot_id, travelingMerchantItems, merchantLastRefreshYear, merchantRefreshCount, playerListedItems)
                SELECT id, slot_id, travelingMerchantItems, merchantLastRefreshYear, merchantRefreshCount, playerListedItems
                FROM game_data
            """)

            db.execSQL("""
                INSERT INTO game_data_organization (id, slot_id, elderSlots, alliances, battleTeam, aiBattleTeams, sectPolicies, activeMissions, availableMissions, usedRedeemCodes)
                SELECT id, slot_id, elderSlots, alliances, battleTeam, aiBattleTeams, sectPolicies, activeMissions, availableMissions, usedRedeemCodes
                FROM game_data
            """)

            db.execSQL("""
                INSERT INTO game_data_exploration (id, slot_id, recruitList, lastRecruitYear, cultivatorCaves, caveExplorationTeams, aiCaveTeams, unlockedDungeons, unlockedRecipes, unlockedManuals, manualProficiencies, pendingCompetitionResults, lastCompetitionYear)
                SELECT id, slot_id, recruitList, lastRecruitYear, cultivatorCaves, caveExplorationTeams, aiCaveTeams, unlockedDungeons, unlockedRecipes, unlockedManuals, manualProficiencies, pendingCompetitionResults, lastCompetitionYear
                FROM game_data
            """)

            Log.i("GameDatabase", "Migration 15->16 completed: GameData split into sub-tables with data sync")
        } catch (e: Exception) {
            Log.e("GameDatabase", "Migration 15->16 failed", e)
            throw e
        }
    }
}

private fun mergeStacks(
    db: SupportSQLiteDatabase,
    tableName: String,
    groupByColumns: String,
    maxStackSize: Int
) {
    val cursor = db.query("""
        SELECT $groupByColumns, GROUP_CONCAT(id) as ids, SUM(quantity) as total_qty
        FROM $tableName
        GROUP BY $groupByColumns
        HAVING COUNT(*) > 1
    """)
    cursor.use {
        while (it.moveToNext()) {
            val ids = it.getString(it.getColumnIndexOrThrow("ids")).split(",")
            val totalQty = it.getInt(it.getColumnIndexOrThrow("total_qty"))
            val clampedQty = totalQty.coerceAtMost(maxStackSize)
            val slotIdIdx = groupByColumns.lastIndexOf("slot_id")
            val slotId = if (slotIdIdx >= 0) {
                it.getInt(it.getColumnIndexOrThrow("slot_id"))
            } else 0
            db.execSQL("UPDATE $tableName SET quantity = ? WHERE id = ? AND slot_id = ?",
                arrayOf<Any>(clampedQty, ids[0], slotId))
            for (i in 1 until ids.size) {
                db.execSQL("DELETE FROM $tableName WHERE id = ? AND slot_id = ?",
                    arrayOf<Any>(ids[i], slotId))
            }
        }
    }
}

val MIGRATION_16_17 = object : androidx.room.migration.Migration(16, 17) {
    override fun migrate(db: SupportSQLiteDatabase) {
        try {
            Log.i("GameDatabase", "Migrating database from version 16 to 17: Pill.effects @Embedded uses identical column names, no schema change required")
        } catch (e: Exception) {
            Log.e("GameDatabase", "Migration 16->17 failed", e)
            throw e
        }
    }
}

val MIGRATION_20_21 = object : androidx.room.migration.Migration(20, 21) {
    override fun migrate(db: SupportSQLiteDatabase) {
        try {
            Log.i("GameDatabase", "Migrating database from version 20 to 21: Add auto-recruit filter columns")
            db.execSQL("ALTER TABLE game_data ADD COLUMN autoRecruitSpiritRootFilter TEXT NOT NULL DEFAULT ''")
            Log.i("GameDatabase", "Migration 20->21 completed: autoRecruitSpiritRootFilter column added")
        } catch (e: Exception) {
            Log.e("GameDatabase", "Migration 20->21 failed", e)
            throw e
        }
    }
}

val MIGRATION_19_20 = object : androidx.room.migration.Migration(19, 20) {
    override fun migrate(db: SupportSQLiteDatabase) {
        try {
            Log.i("GameDatabase", "Migrating database from version 19 to 20: Ensure miningAdd column exists in pills")
            // MIGRATION_18_19 may have been missing this column in earlier builds.
            // Use a safe check to avoid duplicate column errors.
            val cursor = db.query("PRAGMA table_info(pills)")
            var hasMiningAdd = false
            while (cursor.moveToNext()) {
                if (cursor.getString(cursor.getColumnIndexOrThrow("name")) == "miningAdd") {
                    hasMiningAdd = true
                    break
                }
            }
            cursor.close()
            if (!hasMiningAdd) {
                db.execSQL("ALTER TABLE pills ADD COLUMN miningAdd INTEGER NOT NULL DEFAULT 0")
                Log.i("GameDatabase", "Migration 19->20: miningAdd column added to pills")
            } else {
                Log.i("GameDatabase", "Migration 19->20: miningAdd column already exists, skipping")
            }
        } catch (e: Exception) {
            Log.e("GameDatabase", "Migration 19->20 failed", e)
            throw e
        }
    }
}

val MIGRATION_18_19 = object : androidx.room.migration.Migration(18, 19) {
    override fun migrate(db: SupportSQLiteDatabase) {
        try {
            Log.i("GameDatabase", "Migrating database from version 18 to 19: Add mining attribute to disciples")
            db.execSQL("ALTER TABLE disciples ADD COLUMN mining INTEGER NOT NULL DEFAULT 50")
            db.execSQL("ALTER TABLE disciples_attributes ADD COLUMN mining INTEGER NOT NULL DEFAULT 50")
            db.execSQL("ALTER TABLE game_data ADD COLUMN spiritMineExpansions INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE pills ADD COLUMN miningAdd INTEGER NOT NULL DEFAULT 0")
            Log.i("GameDatabase", "Migration 18->19 completed: mining + spiritMineExpansions + miningAdd columns added")
        } catch (e: Exception) {
            Log.e("GameDatabase", "Migration 18->19 failed", e)
            throw e
        }
    }
}

val MIGRATION_17_18 = object : androidx.room.migration.Migration(17, 18) {
    override fun migrate(db: SupportSQLiteDatabase) {
        try {
            Log.i("GameDatabase", "Migrating database from version 17 to 18: Remove unused GameData sub-tables (rollback of incomplete 15->16 refactoring)")

            // Drop the 6 sub-tables created in MIGRATION_15_16. All data remains
            // in the original game_data table, which all application code still uses.
            db.execSQL("DROP TABLE IF EXISTS game_data_exploration")
            db.execSQL("DROP TABLE IF EXISTS game_data_organization")
            db.execSQL("DROP TABLE IF EXISTS game_data_economy")
            db.execSQL("DROP TABLE IF EXISTS game_data_buildings")
            db.execSQL("DROP TABLE IF EXISTS game_data_world_map")
            db.execSQL("DROP TABLE IF EXISTS game_data_core")

            Log.i("GameDatabase", "Migration 17->18 completed: All GameData sub-tables dropped")
        } catch (e: Exception) {
            Log.e("GameDatabase", "Migration 17->18 failed", e)
            throw e
        }
    }
}

@Database(
    entities = [
        GameData::class,
        Disciple::class,
        DiscipleCore::class,
        DiscipleCombatStats::class,
        DiscipleEquipment::class,
        DiscipleExtended::class,
        DiscipleAttributes::class,
        EquipmentStack::class,
        EquipmentInstance::class,
        ManualStack::class,
        ManualInstance::class,
        Pill::class,
        Material::class,
        Seed::class,
        Herb::class,
        ExplorationTeam::class,
        BuildingSlot::class,
        GameEvent::class,
        Dungeon::class,
        Recipe::class,
        BattleLog::class,
        ForgeSlot::class,
        AlchemySlot::class,
        ProductionSlot::class,
        ChangeLogEntity::class,
        SaveSlotMetadata::class,
        ArchivedBattleLog::class,
        ArchivedGameEvent::class,
        ArchivedDisciple::class
    ],
    version = 21,
    exportSchema = true
)

@TypeConverters(ProtobufConverters::class)
abstract class GameDatabase : RoomDatabase() {

    abstract fun gameDataDao(): GameDataDao
    abstract fun discipleDao(): DiscipleDao
    abstract fun discipleCoreDao(): DiscipleCoreDao
    abstract fun discipleCombatStatsDao(): DiscipleCombatStatsDao
    abstract fun discipleEquipmentDao(): DiscipleEquipmentDao
    abstract fun discipleExtendedDao(): DiscipleExtendedDao
    abstract fun discipleAttributesDao(): DiscipleAttributesDao
    abstract fun equipmentStackDao(): EquipmentStackDao
    abstract fun equipmentInstanceDao(): EquipmentInstanceDao
    abstract fun manualStackDao(): ManualStackDao
    abstract fun manualInstanceDao(): ManualInstanceDao
    abstract fun pillDao(): PillDao
    abstract fun materialDao(): MaterialDao
    abstract fun seedDao(): SeedDao
    abstract fun herbDao(): HerbDao
    abstract fun explorationTeamDao(): ExplorationTeamDao
    abstract fun buildingSlotDao(): BuildingSlotDao
    abstract fun gameEventDao(): GameEventDao
    abstract fun dungeonDao(): DungeonDao
    abstract fun recipeDao(): RecipeDao
    abstract fun battleLogDao(): BattleLogDao
    abstract fun forgeSlotDao(): ForgeSlotDao
    abstract fun alchemySlotDao(): AlchemySlotDao
    abstract fun productionSlotDao(): ProductionSlotDao
    abstract fun changeLogDao(): ChangeLogDao
    abstract fun saveSlotMetadataDao(): SaveSlotMetadataDao

    abstract fun archivedBattleLogDao(): ArchivedBattleLogDao
    abstract fun archivedGameEventDao(): ArchivedGameEventDao
    abstract fun archivedDiscipleDao(): ArchivedDiscipleDao

    private val checkpointExecutor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "GameDB-Checkpoint")
    }

    private val isCheckpointRunning = AtomicBoolean(false)
    private val lastCheckpointTime = AtomicLong(0)
    private val totalCheckpoints = AtomicLong(0)
    private val totalWalSizeFreed = AtomicLong(0)

    @Volatile
    private var isShuttingDown = false

    private var checkpointJob: ScheduledFuture<*>? = null

    fun startAutoCheckpoint() {
        checkpointJob = checkpointExecutor.scheduleWithFixedDelay({
            if (!isShuttingDown) {
                checkAndCheckpoint()
            }
        }, GameDatabaseConfig.WAL_CHECK_INTERVAL_SECONDS,
           GameDatabaseConfig.WAL_CHECK_INTERVAL_SECONDS,
           TimeUnit.SECONDS)
        Log.d(TAG, "Auto-checkpoint started")
    }

    fun performPostSaveCheckpoint() {
        try {
            performCheckpointSync(CheckpointMode.PASSIVE)
        } catch (e: Exception) {
            Log.w(TAG, "Post-save checkpoint failed", e)
        }
    }

    private fun checkAndCheckpoint() {
        try {
            val dbPath = openHelper.writableDatabase.path ?: return
            val walFile = File(dbPath + "-wal")
            if (!walFile.exists()) return

            val walSizeMB = walFile.length() / (1024 * 1024)

            if (walSizeMB >= GameDatabaseConfig.WAL_SIZE_THRESHOLD_MB &&
                System.currentTimeMillis() - lastCheckpointTime.get() >= GameDatabaseConfig.CHECKPOINT_COOLDOWN_MS &&
                isCheckpointRunning.compareAndSet(false, true)) {

                try {
                    val beforeSize = walFile.length()

                    val mode = if (walSizeMB >= GameDatabaseConfig.WAL_CRITICAL_SIZE_MB) {
                        Log.w(TAG, "CRITICAL: WAL size ${walSizeMB}MB, forcing TRUNCATE checkpoint")
                        CheckpointMode.TRUNCATE
                    } else {
                        CheckpointMode.PASSIVE
                    }

                    performCheckpointSync(mode)

                    val afterSize = walFile.length()
                    val freed = beforeSize - afterSize

                    if (freed > 0) {
                        totalWalSizeFreed.addAndGet(freed)
                    }

                    lastCheckpointTime.set(System.currentTimeMillis())
                    totalCheckpoints.incrementAndGet()

                    Log.d(TAG, "Checkpoint completed: WAL ${walSizeMB}MB -> ${afterSize / (1024 * 1024)}MB")
                } catch (e: Exception) {
                    Log.e(TAG, "Error during checkpoint", e)
                } finally {
                    isCheckpointRunning.set(false)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error during auto-checkpoint check", e)
        }
    }

    private fun performCheckpointSync(mode: CheckpointMode) {
        try {
            openHelper.writableDatabase.execSQL(mode.query)
            Log.d(TAG, "Checkpoint performed: ${mode.name}")
        } catch (e: android.database.sqlite.SQLiteException) {
            if (e.message?.contains("query or rawQuery") == true) {
                Log.w(TAG, "execSQL rejected for checkpoint, using rawQuery fallback")
                try {
                    openHelper.writableDatabase.query(mode.query, emptyArray()).close()
                    Log.d(TAG, "Checkpoint performed via query: ${mode.name}")
                } catch (q: Exception) {
                    Log.e(TAG, "Failed to perform checkpoint (${mode.name}) via query", q)
                }
            } else {
                Log.e(TAG, "Failed to perform checkpoint (${mode.name})", e)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to perform checkpoint (${mode.name})", e)
        }
    }

    fun getDatabaseSize(): Long {
        return try {
            val path = openHelper.writableDatabase.path ?: return 0
            val file = File(path)
            if (file.exists()) file.length() else 0
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get database size", e)
            0
        }
    }

    fun getWalFileSize(): Long {
        return try {
            val dbPath = openHelper.writableDatabase.path ?: return 0
            val walFile = File(dbPath + "-wal")
            if (walFile.exists()) walFile.length() else 0
        } catch (e: Exception) {
            0
        }
    }

    fun getShmFileSize(): Long {
        return try {
            val dbPath = openHelper.writableDatabase.path ?: return 0
            val shmFile = File(dbPath + "-shm")
            if (shmFile.exists()) shmFile.length() else 0
        } catch (e: Exception) {
            0
        }
    }

    fun getDatabaseStats(): DatabaseStats {
        val dbSize = getDatabaseSize()
        val walSize = getWalFileSize()
        val shmSize = getShmFileSize()

        return DatabaseStats(
            databaseSize = dbSize,
            walSize = walSize,
            shmSize = shmSize,
            totalSize = dbSize + walSize + shmSize,
            totalCheckpoints = totalCheckpoints.get(),
            totalWalFreed = totalWalSizeFreed.get(),
            lastCheckpointTime = lastCheckpointTime.get()
        )
    }

    fun shutdown() {
        Log.i(TAG, "Shutting down unified database instance")
        isShuttingDown = true

        checkpointJob?.cancel(false)
        checkpointJob = null

        checkpointExecutor.shutdown()
        try {
            if (!checkpointExecutor.awaitTermination(10, TimeUnit.SECONDS)) {
                Log.w(TAG, "Checkpoint executor did not terminate in time, forcing shutdown")
                checkpointExecutor.shutdownNow()
                if (!checkpointExecutor.awaitTermination(2, TimeUnit.SECONDS)) {
                    Log.e(TAG, "Checkpoint executor did not terminate after forced shutdown")
                }
            }
        } catch (e: InterruptedException) {
            Log.w(TAG, "Interrupted while waiting for checkpoint executor termination")
            checkpointExecutor.shutdownNow()
            Thread.currentThread().interrupt()
        }

        try {
            val db = openHelper.writableDatabase
            if (!db.isOpen) {
                Log.w(TAG, "Database already closed, skipping final checkpoint")
            } else {
                performCheckpointSync(CheckpointMode.TRUNCATE)
                Log.i(TAG, "Final TRUNCATE checkpoint completed - WAL data flushed to main DB")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Final checkpoint failed - attempting to continue with close", e)
        }

        try {
            if (openHelper.writableDatabase.isOpen) {
                close()
                Log.i(TAG, "Unified database closed successfully")
            } else {
                Log.w(TAG, "Database was already closed")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error closing database", e)
        }

        Log.i(TAG, "Unified database instance shutdown completed")
    }

    enum class CheckpointMode(val query: String) {
        PASSIVE("PRAGMA wal_checkpoint(PASSIVE)"),
        FULL("PRAGMA wal_checkpoint(FULL)"),
        TRUNCATE("PRAGMA wal_checkpoint(TRUNCATE)")
    }

    data class DatabaseStats(
        val databaseSize: Long = 0L,
        val walSize: Long = 0L,
        val shmSize: Long = 0L,
        val totalSize: Long = 0L,
        val totalCheckpoints: Long = 0L,
        val totalWalFreed: Long = 0L,
        val lastCheckpointTime: Long = 0L
    )

    companion object {
        private const val TAG = "GameDatabase"
        private const val UNIFIED_DB_NAME = "xianxia_sect.db"
        private val threadCounter = AtomicInteger(0)

        fun create(context: Context): GameDatabase {
            Log.i(TAG, "Creating unified single-instance database: $UNIFIED_DB_NAME")

            return Room.databaseBuilder(
                context.applicationContext,
                GameDatabase::class.java,
                UNIFIED_DB_NAME
            )
                .setJournalMode(JournalMode.WRITE_AHEAD_LOGGING)
                .setQueryExecutor(
                    Executors.newFixedThreadPool(GameDatabaseConfig.QUERY_THREAD_COUNT) { r ->
                        Thread(r, "GameDB-Query-${threadCounter.incrementAndGet()}")
                    }
                )
                .setTransactionExecutor(
                    Executors.newSingleThreadExecutor { r ->
                        Thread(r, "GameDB-Txn")
                    }
                )
                .addCallback(object : RoomDatabase.Callback() {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        Log.i(TAG, "Unified database created")
                        configureDatabase(db, context)
                    }
                    override fun onOpen(db: SupportSQLiteDatabase) {
                        Log.i(TAG, "Unified database opened")
                        optimizeDatabase(db)
                    }
                })
                .addMigrations(MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11, MIGRATION_11_12, MIGRATION_12_13, MIGRATION_13_14, MIGRATION_14_15, MIGRATION_15_16, MIGRATION_16_17, MIGRATION_17_18, MIGRATION_18_19, MIGRATION_19_20, MIGRATION_20_21)
                .fallbackToDestructiveMigrationFrom(1, 2, 3)
                .build()
                .also { db -> applySafetyPragmas(db) }
        }

        fun getUnifiedDatabaseFile(context: Context): File {
            return context.getDatabasePath(UNIFIED_DB_NAME)
        }

        private fun applySafetyPragmas(db: GameDatabase) {
            try {
                db.openHelper.writableDatabase.execSQL("PRAGMA synchronous = NORMAL")
                Log.d(TAG, "PRAGMA synchronous = NORMAL applied")
            } catch (e: android.database.sqlite.SQLiteException) {
                if (e.message?.contains("query or rawQuery") == true) {
                    Log.w(TAG, "execSQL rejected for synchronous pragma, using rawQuery fallback")
                    try {
                        db.openHelper.writableDatabase.query("PRAGMA synchronous = NORMAL", emptyArray()).close()
                        Log.d(TAG, "PRAGMA synchronous = NORMAL applied via query")
                    } catch (_: Exception) {}
                } else {
                    Log.w(TAG, "Failed to apply PRAGMA synchronous: ${e.message}")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to apply PRAGMA synchronous: ${e.message}")
            }
        }

        private fun configureDatabase(db: SupportSQLiteDatabase, context: Context? = null) {
            Log.d(TAG, "Configuring database parameters")

            val dynamicMmapSize = resolveDynamicMmapSize(context)
            val dynamicCacheSize = resolveDynamicCacheSize(context)

            executeSafely(db, "PRAGMA journal_mode = WAL")
            executeSafely(db, "PRAGMA synchronous = NORMAL")
            executeSafely(db, "PRAGMA cache_size = $dynamicCacheSize")
            executeSafely(db, "PRAGMA temp_store = MEMORY")
            executeSafely(db, "PRAGMA mmap_size = $dynamicMmapSize")
            executeSafely(db, "PRAGMA foreign_keys = ON")
            executeSafely(db, "PRAGMA wal_autocheckpoint = 1000")
            executeSafely(db, "PRAGMA busy_timeout = 5000")
            executeSafely(db, "PRAGMA journal_size_limit = 5242880")

            Log.d(TAG, "Database configuration completed (mmap=${dynamicMmapSize / 1024 / 1024}MB, cache=${-dynamicCacheSize / 1024}MB, journal_limit=5MB)")
        }

        private fun resolveDynamicMmapSize(context: Context?): Long {
            val defaultMmap = 268435456L
            if (context == null) return defaultMmap
            return try {
                val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? android.app.ActivityManager
                    ?: return defaultMmap
                val memInfo = android.app.ActivityManager.MemoryInfo()
                am.getMemoryInfo(memInfo)
                val totalMemMB = memInfo.totalMem / (1024 * 1024)
                when {
                    totalMemMB < 2048 -> 67108864L
                    totalMemMB < 4096 -> 134217728L
                    else -> defaultMmap
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to detect memory for mmap sizing", e)
                defaultMmap
            }
        }

        private fun resolveDynamicCacheSize(context: Context?): Int {
            val defaultCachePages = -64000
            if (context == null) return defaultCachePages
            return try {
                val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? android.app.ActivityManager
                    ?: return defaultCachePages
                val memInfo = android.app.ActivityManager.MemoryInfo()
                am.getMemoryInfo(memInfo)
                val totalMemMB = memInfo.totalMem / (1024 * 1024)
                when {
                    totalMemMB < 2048 -> -16000
                    totalMemMB < 4096 -> -32000
                    else -> defaultCachePages
                }
            } catch (e: Exception) {
                defaultCachePages
            }
        }

        private fun optimizeDatabase(db: SupportSQLiteDatabase) {
            Log.d(TAG, "Running database optimization")
            executeSafely(db, "PRAGMA analysis_limit = 2000")
            executeSafely(db, "PRAGMA optimize")
            Log.d(TAG, "Database optimization completed (analysis_limit=2000)")
        }

        private fun executeSafely(db: SupportSQLiteDatabase, pragma: String) {
            try {
                db.execSQL(pragma)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to execute pragma: $pragma", e)
            }
        }
    }
}
