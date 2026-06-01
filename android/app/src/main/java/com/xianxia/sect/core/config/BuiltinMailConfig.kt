package com.xianxia.sect.core.config

import com.xianxia.sect.core.model.MailAttachment

object BuiltinMailConfig {
    data class BuiltinMail(
        val id: String,
        val title: String,
        val content: String,
        val mailType: String,
        val minVersion: Int,
        val attachments: List<MailAttachment>
    )

    val mails: List<BuiltinMail> = listOf(
    )
}
