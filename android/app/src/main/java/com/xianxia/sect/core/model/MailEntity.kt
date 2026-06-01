package com.xianxia.sect.core.model

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

@Serializable
data class MailAttachment(
    val type: String,
    val name: String,
    val quantity: Int,
    val rarity: Int = 0,
    val itemId: String? = null,
    val extra: Map<String, String> = emptyMap()
)

@Entity(
    tableName = "mails",
    indices = [
        Index(value = ["slotId"]),
        Index(value = ["remoteMailId"]),
        Index(value = ["slotId", "expireTime"])
    ]
)
data class MailEntity(
    @PrimaryKey
    val id: String = java.util.UUID.randomUUID().toString(),

    @ColumnInfo(defaultValue = "0")
    val slotId: Int = 0,

    @ColumnInfo(defaultValue = "builtin")
    val source: String = "builtin",

    @ColumnInfo(defaultValue = "reward")
    val mailType: String = "reward",

    @ColumnInfo(defaultValue = "")
    val title: String = "",

    @ColumnInfo(defaultValue = "")
    val content: String = "",

    @ColumnInfo(defaultValue = "天道意志")
    val senderName: String = "天道意志",

    @ColumnInfo(defaultValue = "0")
    val sendTime: Long = 0,

    @ColumnInfo(defaultValue = "0")
    val expireTime: Long = 0,

    @ColumnInfo(defaultValue = "0")
    val isRead: Boolean = false,

    @ColumnInfo(defaultValue = "0")
    val attachmentClaimed: Boolean = false,

    @ColumnInfo(defaultValue = "0")
    val hasAttachment: Boolean = false,

    @ColumnInfo(defaultValue = "[]")
    val attachments: String = "[]",

    @ColumnInfo(defaultValue = "NULL")
    val remoteMailId: String? = null
)
