package com.xianxia.sect.data.incremental

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "change_log",
    indices = [
        Index(value = ["table_name", "record_id"]),
        Index(value = ["timestamp"]),
        Index(value = ["synced"])
    ]
)
data class ChangeLogEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val id: Long = 0,

    @ColumnInfo(name = "table_name")
    val tableName: String,

    @ColumnInfo(name = "record_id")
    val recordId: String,

    @ColumnInfo(name = "operation")
    val operation: String,

    @ColumnInfo(name = "old_value")
    val oldValue: ByteArray?,

    @ColumnInfo(name = "new_value")
    val newValue: ByteArray?,

    @ColumnInfo(name = "timestamp")
    val timestamp: Long,

    @ColumnInfo(name = "synced")
    val synced: Boolean = false,

    @ColumnInfo(name = "sync_version")
    val syncVersion: Long = 0
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as ChangeLogEntity
        if (id != other.id) return false
        if (tableName != other.tableName) return false
        if (recordId != other.recordId) return false
        if (operation != other.operation) return false
        if (oldValue != null) {
            if (other.oldValue == null) return false
            if (!oldValue.contentEquals(other.oldValue)) return false
        } else if (other.oldValue != null) return false
        if (newValue != null) {
            if (other.newValue == null) return false
            if (!newValue.contentEquals(other.newValue)) return false
        } else if (other.newValue != null) return false
        if (timestamp != other.timestamp) return false
        if (synced != other.synced) return false
        if (syncVersion != other.syncVersion) return false
        return true
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + tableName.hashCode()
        result = 31 * result + recordId.hashCode()
        result = 31 * result + operation.hashCode()
        result = 31 * result + (oldValue?.contentHashCode() ?: 0)
        result = 31 * result + (newValue?.contentHashCode() ?: 0)
        result = 31 * result + timestamp.hashCode()
        result = 31 * result + synced.hashCode()
        result = 31 * result + syncVersion.hashCode()
        return result
    }
}
