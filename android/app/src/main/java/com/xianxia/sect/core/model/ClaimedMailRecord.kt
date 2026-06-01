package com.xianxia.sect.core.model

import androidx.room.ColumnInfo
import androidx.room.Entity

@Entity(
    tableName = "claimed_mail_records",
    primaryKeys = ["mailGlobalId", "slotId"]
)
data class ClaimedMailRecord(
    val mailGlobalId: String,

    @ColumnInfo(defaultValue = "0")
    val slotId: Int = 0,

    @ColumnInfo(defaultValue = "0")
    val claimedTime: Long = 0
)
