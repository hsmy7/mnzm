package com.xianxia.sect.core.config

import com.xianxia.sect.core.model.MailAttachment

object BuiltinMailConfig {
    data class BuiltinMail(
        val id: String,
        val title: String,
        val content: String,
        val mailType: String,
        val minVersion: Int,
        /** 限时截止时间戳(ms)，0=不限时。超过此时间新老玩家均不再显示此邮件 */
        val deadlineMs: Long = 0,
        val attachments: List<MailAttachment>
    )

    val mails: List<BuiltinMail> = listOf(
        BuiltinMail(
            id = "mail_blood_refining_pool_v3_2_04",
            title = "庆祝血炼池建筑上线",
            content = "道友安好！\n\n宗门全新建筑「血炼池」已正式上线！\n\n血炼池可通过消耗妖兽精血淬炼弟子肉身，永久提升战斗属性——虎血增攻、蛇血提速、龟血强防，助道友打造无上战力！\n\n特奉上灵虎血200份，快去建造血炼池体验吧。\n\n——天道意志",
            mailType = "reward",
            minVersion = 3204,
            deadlineMs = 1781568000000L,
            attachments = listOf(
                MailAttachment(type = "beastMaterial", name = "灵虎血", quantity = 200, rarity = 2, itemId = "tigerBlood1")
            )
        ),
        BuiltinMail(
            id = "mail_system_launch_v3_1_96",
            title = "庆祝邮件系统上线",
            content = "道友安好！\n\n宗门邮件系统已正式上线，今后所有版本更新补偿、活动奖励、节日福利等均将通过邮件发放，敬请留意查收。\n\n本次奉上薄礼一份，愿道友修仙之路越走越宽。\n\n——天道意志",
            mailType = "reward",
            minVersion = 3196,
            deadlineMs = 1781568000000L, // 2026-06-16 00:00 UTC (发布日起14天)
            attachments = listOf(
                MailAttachment(type = "storageBag", name = "宝品储物袋", quantity = 3, rarity = 3)
            )
        ),
        BuiltinMail(
            id = "mail_qq_group_v4_0_03",
            title = "加入官方玩家交流群",
            content = "道友安好！\n\n为方便诸位道友交流修仙心得、分享宗门建设经验，特此建立官方玩家交流QQ群。\n\n群号：1085248982\n\n入群即可结识天下道友，共探仙道之秘。特奉上宝品储物袋十枚，助道友收纳四方宝物！\n\n——天道意志",
            mailType = "reward",
            minVersion = 4003,
            attachments = listOf(
                MailAttachment(type = "storageBag", name = "宝品储物袋", quantity = 10, rarity = 3)
            )
        )
    )
}
