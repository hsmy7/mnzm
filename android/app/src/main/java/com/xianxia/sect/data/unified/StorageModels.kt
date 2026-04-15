package com.xianxia.sect.data.unified

import kotlinx.serialization.Serializable

/**
 * Slot metadata for save slot information.
 * Serialized to JSON for persistence alongside save data.
 */
@Serializable
data class SlotMetadata(
    val slot: Int,
    val timestamp: Long,
    val gameYear: Int,
    val gameMonth: Int,
    val sectName: String,
    val discipleCount: Int,
    val spiritStones: Long,
    val fileSize: Long = 0,
    val customName: String = "",
    val checksum: String = "",
    val version: String = "",
    val dataHash: String = "",
    val merkleRoot: String = ""
)

/**
 * Simplified backup information for backward compatibility.
 * Mapped from EnhancedBackupInfo in BackupManager.
 */
data class BackupInfo(
    val id: String,
    val slot: Int,
    val timestamp: Long,
    val size: Long,
    val checksum: String
)

/**
 * Result of integrity verification for a save slot.
 */
sealed class IntegrityResult {
    /** Data is valid and integrity checks passed. */
    object Valid : IntegrityResult()

    /** Data has been tampered with (signature validation failed). */
    data class Tampered(val reason: String) : IntegrityResult()

    /** Data is invalid or corrupted. */
    data class Invalid(val errors: List<String>) : IntegrityResult()
}
