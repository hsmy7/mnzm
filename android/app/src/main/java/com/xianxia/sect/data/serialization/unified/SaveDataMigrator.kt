package com.xianxia.sect.data.serialization.unified

import android.util.Log
import javax.inject.Inject
import javax.inject.Singleton

data class SchemaVersion(val major: Int, val minor: Int = 0) : Comparable<SchemaVersion> {
    override fun compareTo(other: SchemaVersion): Int {
        val majorCmp = major.compareTo(other.major)
        return if (majorCmp != 0) majorCmp else minor.compareTo(other.minor)
    }

    override fun toString(): String = if (minor == 0) "$major" else "$major.$minor"

    companion object {
        fun parse(value: String): SchemaVersion {
            val parts = value.split(".")
            return if (parts.size >= 2) {
                SchemaVersion(parts[0].toIntOrNull() ?: 1, parts[1].toIntOrNull() ?: 0)
            } else {
                SchemaVersion(value.toIntOrNull() ?: 1)
            }
        }

        val CURRENT = SchemaVersion(5)
    }
}

interface VersionMigrator {
    val fromVersion: SchemaVersion
    val toVersion: SchemaVersion
    fun migrate(data: SerializableSaveData): SerializableSaveData
}

@Singleton
class SaveDataMigrator @Inject constructor() {
    companion object {
        private const val TAG = "SaveDataMigrator"
    }

    private val migrators = mutableListOf<VersionMigrator>()

    init {
        registerMigrator(V1ToV2Migrator())
        registerMigrator(V2ToV3Migrator())
        registerMigrator(V3ToV4Migrator())
        registerMigrator(V4ToV5Migrator())
    }

    fun registerMigrator(migrator: VersionMigrator) {
        migrators.add(migrator)
    }

    fun migrate(data: SerializableSaveData): MigrationResult {
        var currentData = data
        try {
            var currentVersion = SchemaVersion.parse(currentData.version)
            var iterations = 0
            val maxIterations = migrators.size + 1

            while (currentVersion < SchemaVersion.CURRENT && iterations < maxIterations) {
                val migrator = findMigrator(currentVersion)
                    ?: return MigrationResult.Failed(
                        IllegalStateException("No migrator found for version $currentVersion"),
                        currentData
                    )
                Log.i(TAG, "Migrating from ${migrator.fromVersion} to ${migrator.toVersion}")
                currentData = migrator.migrate(currentData)
                currentVersion = migrator.toVersion
                iterations++
            }

            if (iterations >= maxIterations) {
                return MigrationResult.Failed(
                    IllegalStateException("Migration loop detected, exceeded $maxIterations iterations"),
                    currentData
                )
            }

            return MigrationResult.Success(currentData)
        } catch (e: Exception) {
            Log.e(TAG, "Migration failed", e)
            return MigrationResult.Failed(e, currentData)
        }
    }

    fun needsMigration(version: String): Boolean {
        return SchemaVersion.parse(version) < SchemaVersion.CURRENT
    }

    private fun findMigrator(fromVersion: SchemaVersion): VersionMigrator? {
        return migrators.find { it.fromVersion == fromVersion }
    }
}

class V1ToV2Migrator : VersionMigrator {
    override val fromVersion = SchemaVersion(1)
    override val toVersion = SchemaVersion(2)

    override fun migrate(data: SerializableSaveData): SerializableSaveData {
        return data.copy(
            version = toVersion.toString(),
            gameData = data.gameData.copy(
                playerProtectionEnabled = data.gameData.playerProtectionEnabled,
                playerProtectionStartYear = data.gameData.playerProtectionStartYear
            )
        )
    }
}

class V2ToV3Migrator : VersionMigrator {
    override val fromVersion = SchemaVersion(2)
    override val toVersion = SchemaVersion(3)

    override fun migrate(data: SerializableSaveData): SerializableSaveData {
        return data.copy(version = toVersion.toString())
    }
}

class V3ToV4Migrator : VersionMigrator {
    override val fromVersion = SchemaVersion(3)
    override val toVersion = SchemaVersion(4)

    private fun migrateDiscipleDuration(disciple: SerializableDisciple): SerializableDisciple {
        return disciple.copy(
            cultivationSpeedDuration = if (disciple.cultivationSpeedDuration > 0 && disciple.cultivationSpeedDuration <= 12)
                disciple.cultivationSpeedDuration * 30 else disciple.cultivationSpeedDuration,
            pillEffectDuration = if (disciple.pillEffectDuration > 0 && disciple.pillEffectDuration <= 12)
                disciple.pillEffectDuration * 30 else disciple.pillEffectDuration
        )
    }

    override fun migrate(data: SerializableSaveData): SerializableSaveData {
        return data.copy(
            version = toVersion.toString(),
            disciples = data.disciples.map { migrateDiscipleDuration(it) },
            gameData = data.gameData.copy(
                recruitList = data.gameData.recruitList.map { migrateDiscipleDuration(it) },
                aiSectDisciples = data.gameData.aiSectDisciples.map { entry ->
                    entry.copy(disciples = entry.disciples.map { migrateDiscipleDuration(it) })
                }
            )
        )
    }
}

@Suppress("DEPRECATION")
class V4ToV5Migrator : VersionMigrator {
    override val fromVersion = SchemaVersion(4)
    override val toVersion = SchemaVersion(5)

    private fun migratePill(pill: SerializablePill): SerializablePill {
        val category = pill.type.ifEmpty { pill.category }
        if (pill.effectsMap.isEmpty() || pill.effects != SerializablePillEffect()) {
            return pill.copy(category = category, effectsMap = emptyMap())
        }
        val migrated = migrateEffectsMapToPillEffect(pill.effectsMap)
        return pill.copy(category = category, effects = migrated, effectsMap = emptyMap())
    }

    private fun migrateEffectsMapToPillEffect(effectsMap: Map<String, Double>): SerializablePillEffect {
        return SerializablePillEffect(
            breakthroughChance = effectsMap["breakthroughChance"] ?: 0.0,
            targetRealm = (effectsMap["targetRealm"] ?: 0.0).toInt(),
            isAscension = (effectsMap["isAscension"] ?: 0.0) > 0.5,
            cultivationSpeedPercent = effectsMap["cultivationSpeedPercent"] ?: 0.0,
            skillExpSpeedPercent = effectsMap["skillExpSpeedPercent"] ?: 0.0,
            nurtureSpeedPercent = effectsMap["nurtureSpeedPercent"] ?: 0.0,
            cultivationAdd = (effectsMap["cultivationAdd"] ?: 0.0).toInt(),
            skillExpAdd = (effectsMap["skillExpAdd"] ?: 0.0).toInt(),
            nurtureAdd = (effectsMap["nurtureAdd"] ?: 0.0).toInt(),
            duration = (effectsMap["duration"] ?: 0.0).toInt(),
            cannotStack = (effectsMap["cannotStack"] ?: 0.0) > 0.5,
            physicalAttackAdd = (effectsMap["physicalAttackAdd"] ?: 0.0).toInt(),
            magicAttackAdd = (effectsMap["magicAttackAdd"] ?: 0.0).toInt(),
            physicalDefenseAdd = (effectsMap["physicalDefenseAdd"] ?: 0.0).toInt(),
            magicDefenseAdd = (effectsMap["magicDefenseAdd"] ?: 0.0).toInt(),
            hpAdd = (effectsMap["hpAdd"] ?: 0.0).toInt(),
            mpAdd = (effectsMap["mpAdd"] ?: 0.0).toInt(),
            speedAdd = (effectsMap["speedAdd"] ?: 0.0).toInt(),
            critRateAdd = effectsMap["critRateAdd"] ?: 0.0,
            critEffectAdd = effectsMap["critEffectAdd"] ?: 0.0,
            extendLife = (effectsMap["extendLife"] ?: 0.0).toInt(),
            intelligenceAdd = (effectsMap["intelligenceAdd"] ?: 0.0).toInt(),
            charmAdd = (effectsMap["charmAdd"] ?: 0.0).toInt(),
            loyaltyAdd = (effectsMap["loyaltyAdd"] ?: 0.0).toInt(),
            comprehensionAdd = (effectsMap["comprehensionAdd"] ?: 0.0).toInt(),
            artifactRefiningAdd = (effectsMap["artifactRefiningAdd"] ?: 0.0).toInt(),
            pillRefiningAdd = (effectsMap["pillRefiningAdd"] ?: 0.0).toInt(),
            spiritPlantingAdd = (effectsMap["spiritPlantingAdd"] ?: 0.0).toInt(),
            teachingAdd = (effectsMap["teachingAdd"] ?: 0.0).toInt(),
            moralityAdd = (effectsMap["moralityAdd"] ?: 0.0).toInt(),
            miningAdd = (effectsMap["miningAdd"] ?: 0.0).toInt(),
            healMaxHpPercent = effectsMap["healMaxHpPercent"] ?: 0.0,
            mpRecoverMaxMpPercent = effectsMap["mpRecoverMaxMpPercent"] ?: 0.0,
            revive = (effectsMap["revive"] ?: 0.0) > 0.5,
            clearAll = (effectsMap["clearAll"] ?: 0.0) > 0.5
        )
    }

    override fun migrate(data: SerializableSaveData): SerializableSaveData {
        return data.copy(
            version = toVersion.toString(),
            pills = data.pills.map { migratePill(it) }
        )
    }
}
