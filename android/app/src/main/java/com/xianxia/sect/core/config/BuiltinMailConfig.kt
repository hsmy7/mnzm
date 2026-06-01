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
            id = "test_mail_v3_1_95",
            title = "【测试】邮件系统验证",
            content = "道友安好！\n\n此邮件用于验证邮件系统的各项功能，包含灵石、灵草、装备、丹药、材料、草药、种子各类型附件。请逐一测试领取、一键已读、删除已读等功能。\n\n如有问题请反馈。\n\n——天道意志",
            mailType = "reward",
            minVersion = 3195,
            attachments = listOf(
                MailAttachment(type = "spiritStones", name = "灵石", quantity = 10000, rarity = 0),
                MailAttachment(type = "spiritHerbs", name = "灵草", quantity = 5000, rarity = 0),
                MailAttachment(type = "pill", name = "培元丹", quantity = 3, rarity = 1),
                MailAttachment(type = "equipment", name = "玄铁剑", quantity = 1, rarity = 2),
                MailAttachment(type = "material", name = "灵材", quantity = 5, rarity = 1),
                MailAttachment(type = "herb", name = "灵芝", quantity = 3, rarity = 1),
                MailAttachment(type = "seed", name = "灵种", quantity = 2, rarity = 1),
                MailAttachment(type = "storageBag", name = "凡品储物袋", quantity = 3, rarity = 1)
            )
        )
    )
}
