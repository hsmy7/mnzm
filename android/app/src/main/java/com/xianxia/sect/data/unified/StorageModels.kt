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

data class BackupInfo(
    val id: String,
    val slot: Int,
    val timestamp: Long,
    val size: Long,
    val checksum: String
)
