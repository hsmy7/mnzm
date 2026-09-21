package com.xianxia.sect.core.model

import androidx.annotation.Keep
import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoNumber


@Keep
@Serializable
data class MailAttachment(
    val type: String,
    val name: String,
    val quantity: Int,
    val rarity: Int = 0,
    val itemId: String? = null,
    val extra: Map<String, String> = emptyMap()
)

/**
 * 槽位邮件实体（Room `mails` 表 + SaveData wire 面 SR-1）。
 *
 * **proto 标注纪律（SR-1 起生效）**：本类经 `SaveData.mails`（tag 56）进入云档/.sav
 * wire，全部 14 字段显式 `@ProtoNumber(1..14)`（与声明序一致，受
 * `ProtoNumberCoverageTest` 递归覆盖与 `ProtoNumberUniquenessTest` IN4 守卫约束；
 * **声明顺序从此不可调整**——proto 号按位锁定）。非零默认值的 5 字段
 * （id 随机/source/mailType/senderName/attachments）`@EncodeDefault(ALWAYS)`
 * 恒编码，保证 wire 确定性（方案 §0 EncodeDefault 纪律同 GameData/SaveData 口径）。
 */
@Entity(
    tableName = "mails",
    indices = [
        Index(value = ["slotId"]),
        Index(value = ["remoteMailId"]),
        Index(value = ["slotId", "expireTime"])
    ]
)
@Serializable
data class MailEntity(
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    @ProtoNumber(1)
    @PrimaryKey
    val id: String = java.util.UUID.randomUUID().toString(),

    @ColumnInfo(defaultValue = "0")
    @ProtoNumber(2)
    val slotId: Int = 0,

    @ColumnInfo(defaultValue = "builtin")
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    @ProtoNumber(3)
    val source: String = "builtin",

    @ColumnInfo(defaultValue = "reward")
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    @ProtoNumber(4)
    val mailType: String = "reward",

    @ColumnInfo(defaultValue = "")
    @ProtoNumber(5)
    val title: String = "",

    @ColumnInfo(defaultValue = "")
    @ProtoNumber(6)
    val content: String = "",

    @ColumnInfo(defaultValue = "天道意志")
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    @ProtoNumber(7)
    val senderName: String = "天道意志",

    @ColumnInfo(defaultValue = "0")
    @ProtoNumber(8)
    val sendTime: Long = 0,

    @ColumnInfo(defaultValue = "0")
    @ProtoNumber(9)
    val expireTime: Long = 0,

    @ColumnInfo(defaultValue = "0")
    @ProtoNumber(10)
    val isRead: Boolean = false,

    @ColumnInfo(defaultValue = "0")
    @ProtoNumber(11)
    val attachmentClaimed: Boolean = false,

    @ColumnInfo(defaultValue = "0")
    @ProtoNumber(12)
    val hasAttachment: Boolean = false,

    @ColumnInfo(defaultValue = "[]")
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    @ProtoNumber(13)
    val attachments: String = "[]",

    @ColumnInfo(defaultValue = "NULL")
    @ProtoNumber(14)
    val remoteMailId: String? = null
)
