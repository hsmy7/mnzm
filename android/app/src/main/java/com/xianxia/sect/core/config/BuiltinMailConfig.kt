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
        BuiltinMail(
            id = "mail_system_launch_v3_1_96",
            title = "庆祝邮件系统上线",
            content = "道友安好！\n\n宗门邮件系统已正式上线，今后所有版本更新补偿、活动奖励、节日福利等均将通过邮件发放，敬请留意查收。\n\n本次奉上薄礼一份，愿道友修仙之路越走越宽。\n\n——天道意志",
            mailType = "reward",
            minVersion = 3196,
            attachments = listOf(
                MailAttachment(type = "storageBag", name = "宝品储物袋", quantity = 3, rarity = 3)
            )
        )
    )
}
