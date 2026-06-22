package com.xianxia.sect.core.config

import com.xianxia.sect.core.model.MailAttachment

object BuiltinMailConfig {
    data class BuiltinMail(
        val id: String,
        val title: String,
        val content: String,
        val mailType: String,
        val minVersion: Int,
        /** 生效开始时间戳(ms)，0=立即生效。邮件仅在此时间之后发放 */
        val startMs: Long = 0,
        /** 限时截止时间戳(ms)，0=不限时。超过此时间新老玩家均不再显示此邮件 */
        val deadlineMs: Long = 0,
        val attachments: List<MailAttachment>
    )

    // 节日邮件统一奖励：5灵品储物袋 + 5万灵石
    private val HOLIDAY_ATTACHMENTS = listOf(
        MailAttachment(type = "storageBag", name = "灵品储物袋", quantity = 5, rarity = 2),
        MailAttachment(type = "spiritStones", name = "灵石", quantity = 50000, rarity = 1)
    )

    val mails: List<BuiltinMail> = listOf(
        BuiltinMail(
            id = "mail_qq_group_v4_0_03",
            title = "加入官方玩家交流群",
            content = "道友安好！\n\n为方便诸位道友交流修仙心得、分享宗门建设经验，特此建立官方玩家交流QQ群。\n\n群号：1085248982\n\n入群即可结识天下道友，共探仙道之秘。特奉上宝品储物袋十枚，助道友收纳四方宝物！\n\n——天道意志",
            mailType = "reward",
            minVersion = 4003,
            attachments = listOf(
                MailAttachment(type = "storageBag", name = "宝品储物袋", quantity = 10, rarity = 3)
            )
        ),

        // ══════════════════════════════════════════
        // 节日邮件 — 2026年 (14天限时，v4.0.13起)
        // ══════════════════════════════════════════

        BuiltinMail(
            id = "mail_new_year_2026",
            title = "元旦快乐",
            content = "道友安好！\n\n一元复始，万象更新。\n\n值此元旦佳节，天道意志特奉薄礼一份，愿道友在新的一年里仙途坦荡、修为精进，早日登临仙道之巅！\n\n——天道意志",
            mailType = "reward",
            minVersion = 4013,
            startMs = 1767225600000L, // 2026-01-01 00:00 UTC
            deadlineMs = 1768435200000L, // +14天
            attachments = HOLIDAY_ATTACHMENTS
        ),
        BuiltinMail(
            id = "mail_spring_festival_2026",
            title = "新春快乐",
            content = "道友安好！\n\n爆竹声中一岁除，春风送暖入屠苏。\n\n值此新春佳节，天道意志特奉薄礼一份，愿道友阖家团圆、宗门昌盛，新年新气象，仙道更进一步！\n\n——天道意志",
            mailType = "reward",
            minVersion = 4013,
            startMs = 1771286400000L, // 2026-02-17 00:00 UTC (农历正月初一)
            deadlineMs = 1772496000000L, // +14天
            attachments = HOLIDAY_ATTACHMENTS
        ),
        BuiltinMail(
            id = "mail_lantern_2026",
            title = "元宵快乐",
            content = "道友安好！\n\n火树银花合，星桥铁锁开。\n\n值此元宵佳节，天道意志特奉薄礼一份，愿道友月圆人圆、心想事成，仙途光明如满月！\n\n——天道意志",
            mailType = "reward",
            minVersion = 4013,
            startMs = 1772496000000L, // 2026-03-03 00:00 UTC (农历正月十五)
            deadlineMs = 1773705600000L, // +14天
            attachments = HOLIDAY_ATTACHMENTS
        ),
        BuiltinMail(
            id = "mail_women_2026",
            title = "妇女节快乐",
            content = "道友安好！\n\n巾帼不让须眉，红颜更胜儿郎。\n\n值此妇女节，天道意志特奉薄礼一份，向所有修仙路上的女道友致以崇高敬意。愿诸位道心坚定、风华绝代！\n\n——天道意志",
            mailType = "reward",
            minVersion = 4013,
            startMs = 1772928000000L, // 2026-03-08 00:00 UTC
            deadlineMs = 1774137600000L, // +14天
            attachments = HOLIDAY_ATTACHMENTS
        ),
        BuiltinMail(
            id = "mail_qingming_2026",
            title = "清明安康",
            content = "道友安好！\n\n清明时节雨纷纷，路上行人欲断魂。\n\n值此清明，天道意志特奉薄礼一份，愿道友缅怀先人之余亦珍重自身，仙途漫漫且行且珍惜。\n\n——天道意志",
            mailType = "reward",
            minVersion = 4013,
            startMs = 1775347200000L, // 2026-04-05 00:00 UTC
            deadlineMs = 1776556800000L, // +14天
            attachments = HOLIDAY_ATTACHMENTS
        ),
        BuiltinMail(
            id = "mail_labor_2026",
            title = "劳动节快乐",
            content = "道友安好！\n\n天道酬勤，仙道亦需勤修不辍。\n\n值此劳动节，天道意志特奉薄礼一份，愿道友辛勤修炼皆有回报，宗门建设蒸蒸日上！\n\n——天道意志",
            mailType = "reward",
            minVersion = 4013,
            startMs = 1777593600000L, // 2026-05-01 00:00 UTC
            deadlineMs = 1778803200000L, // +14天
            attachments = HOLIDAY_ATTACHMENTS
        ),
        BuiltinMail(
            id = "mail_mother_2026",
            title = "母亲节快乐",
            content = "道友安好！\n\n谁言寸草心，报得三春晖。\n\n值此母亲节，天道意志特奉薄礼一份，愿道友不忘亲恩，仙途之上亦有人间温情相伴。\n\n——天道意志",
            mailType = "reward",
            minVersion = 4013,
            startMs = 1778371200000L, // 2026-05-10 00:00 UTC (5月第2个周日)
            deadlineMs = 1779580800000L, // +14天
            attachments = HOLIDAY_ATTACHMENTS
        ),
        BuiltinMail(
            id = "mail_dragon_boat_2026",
            title = "端午安康",
            content = "道友安好！\n\n五月初五，龙舟竞渡，粽叶飘香。\n\n值此端午佳节，天道意志特奉薄礼一份，愿道友仙途坦荡、宗门昌盛，早日登临仙道之巅！\n\n——天道意志",
            mailType = "reward",
            minVersion = 4013,
            startMs = 1781827200000L, // 2026-06-19 00:00 UTC (农历五月初五)
            deadlineMs = 1783036800000L, // +14天, 2026-07-03 00:00 UTC 截止
            attachments = HOLIDAY_ATTACHMENTS
        ),
        BuiltinMail(
            id = "mail_father_2026",
            title = "父亲节快乐",
            content = "道友安好！\n\n父爱如山，沉默而厚重。\n\n值此父亲节，天道意志特奉薄礼一份，愿道友感念父恩，修炼之余不忘人间至亲。\n\n——天道意志",
            mailType = "reward",
            minVersion = 4013,
            startMs = 1782000000000L, // 2026-06-21 00:00 UTC (6月第3个周日)
            deadlineMs = 1783209600000L, // +14天
            attachments = HOLIDAY_ATTACHMENTS
        ),
        BuiltinMail(
            id = "mail_qixi_2026",
            title = "七夕快乐",
            content = "道友安好！\n\n金风玉露一相逢，便胜却人间无数。\n\n值此七夕佳节，天道意志特奉薄礼一份，愿道友仙缘美满、道侣情深，修仙路上不孤单。\n\n——天道意志",
            mailType = "reward",
            minVersion = 4013,
            startMs = 1787097600000L, // 2026-08-19 00:00 UTC (农历七月初七)
            deadlineMs = 1788307200000L, // +14天
            attachments = HOLIDAY_ATTACHMENTS
        ),
        BuiltinMail(
            id = "mail_mid_autumn_2026",
            title = "中秋快乐",
            content = "道友安好！\n\n但愿人长久，千里共婵娟。\n\n值此中秋佳节，天道意志特奉薄礼一份，愿道友月圆人团圆，仙途圆满如满月当空。\n\n——天道意志",
            mailType = "reward",
            minVersion = 4013,
            startMs = 1790294400000L, // 2026-09-25 00:00 UTC (农历八月十五)
            deadlineMs = 1791504000000L, // +14天
            attachments = HOLIDAY_ATTACHMENTS
        ),
        BuiltinMail(
            id = "mail_national_2026",
            title = "国庆快乐",
            content = "道友安好！\n\n普天同庆，盛世华章。\n\n值此国庆佳节，天道意志特奉薄礼一份，愿道友与国同庆，宗门兴旺，仙道昌盛！\n\n——天道意志",
            mailType = "reward",
            minVersion = 4013,
            startMs = 1790812800000L, // 2026-10-01 00:00 UTC
            deadlineMs = 1792022400000L, // +14天
            attachments = HOLIDAY_ATTACHMENTS
        ),
        BuiltinMail(
            id = "mail_chongyang_2026",
            title = "重阳安康",
            content = "道友安好！\n\n独在异乡为异客，每逢佳节倍思亲。\n\n值此重阳佳节，天道意志特奉薄礼一份，愿道友登高望远、仙途开阔，长辈安康福寿绵长。\n\n——天道意志",
            mailType = "reward",
            minVersion = 4013,
            startMs = 1792281600000L, // 2026-10-18 00:00 UTC (农历九月初九)
            deadlineMs = 1793491200000L, // +14天
            attachments = HOLIDAY_ATTACHMENTS
        ),
        BuiltinMail(
            id = "mail_winter_solstice_2026",
            title = "冬至安康",
            content = "道友安好！\n\n冬至阳生春又来，阴极之至阳气始生。\n\n值此冬至节气，天道意志特奉薄礼一份，愿道友顺应天道、养精蓄锐，来年仙途更上层楼！\n\n——天道意志",
            mailType = "reward",
            minVersion = 4013,
            startMs = 1797897600000L, // 2026-12-22 00:00 UTC
            deadlineMs = 1799107200000L, // +14天
            attachments = HOLIDAY_ATTACHMENTS
        ),

        // ══════════════════════════════════════════
        // 节日邮件 — 2027年 (14天限时，v4.0.13起)
        // ══════════════════════════════════════════

        BuiltinMail(
            id = "mail_new_year_2027",
            title = "元旦快乐",
            content = "道友安好！\n\n一元复始，万象更新。\n\n值此元旦佳节，天道意志特奉薄礼一份，愿道友在新的一年里仙途坦荡、修为精进，早日登临仙道之巅！\n\n——天道意志",
            mailType = "reward",
            minVersion = 4013,
            startMs = 1798761600000L, // 2027-01-01 00:00 UTC
            deadlineMs = 1799971200000L, // +14天
            attachments = HOLIDAY_ATTACHMENTS
        ),
        BuiltinMail(
            id = "mail_spring_festival_2027",
            title = "新春快乐",
            content = "道友安好！\n\n爆竹声中一岁除，春风送暖入屠苏。\n\n值此新春佳节，天道意志特奉薄礼一份，愿道友阖家团圆、宗门昌盛，新年新气象，仙道更进一步！\n\n——天道意志",
            mailType = "reward",
            minVersion = 4013,
            startMs = 1801872000000L, // 2027-02-06 00:00 UTC (农历正月初一)
            deadlineMs = 1803081600000L, // +14天
            attachments = HOLIDAY_ATTACHMENTS
        ),
        BuiltinMail(
            id = "mail_lantern_2027",
            title = "元宵快乐",
            content = "道友安好！\n\n火树银花合，星桥铁锁开。\n\n值此元宵佳节，天道意志特奉薄礼一份，愿道友月圆人圆、心想事成，仙途光明如满月！\n\n——天道意志",
            mailType = "reward",
            minVersion = 4013,
            startMs = 1803081600000L, // 2027-02-20 00:00 UTC (农历正月十五)
            deadlineMs = 1804291200000L, // +14天
            attachments = HOLIDAY_ATTACHMENTS
        ),
        BuiltinMail(
            id = "mail_women_2027",
            title = "妇女节快乐",
            content = "道友安好！\n\n巾帼不让须眉，红颜更胜儿郎。\n\n值此妇女节，天道意志特奉薄礼一份，向所有修仙路上的女道友致以崇高敬意。愿诸位道心坚定、风华绝代！\n\n——天道意志",
            mailType = "reward",
            minVersion = 4013,
            startMs = 1804464000000L, // 2027-03-08 00:00 UTC
            deadlineMs = 1805673600000L, // +14天
            attachments = HOLIDAY_ATTACHMENTS
        ),
        BuiltinMail(
            id = "mail_qingming_2027",
            title = "清明安康",
            content = "道友安好！\n\n清明时节雨纷纷，路上行人欲断魂。\n\n值此清明，天道意志特奉薄礼一份，愿道友缅怀先人之余亦珍重自身，仙途漫漫且行且珍惜。\n\n——天道意志",
            mailType = "reward",
            minVersion = 4013,
            startMs = 1806883200000L, // 2027-04-05 00:00 UTC
            deadlineMs = 1808092800000L, // +14天
            attachments = HOLIDAY_ATTACHMENTS
        ),
        BuiltinMail(
            id = "mail_labor_2027",
            title = "劳动节快乐",
            content = "道友安好！\n\n天道酬勤，仙道亦需勤修不辍。\n\n值此劳动节，天道意志特奉薄礼一份，愿道友辛勤修炼皆有回报，宗门建设蒸蒸日上！\n\n——天道意志",
            mailType = "reward",
            minVersion = 4013,
            startMs = 1809129600000L, // 2027-05-01 00:00 UTC
            deadlineMs = 1810339200000L, // +14天
            attachments = HOLIDAY_ATTACHMENTS
        ),
        BuiltinMail(
            id = "mail_mother_2027",
            title = "母亲节快乐",
            content = "道友安好！\n\n谁言寸草心，报得三春晖。\n\n值此母亲节，天道意志特奉薄礼一份，愿道友不忘亲恩，仙途之上亦有人间温情相伴。\n\n——天道意志",
            mailType = "reward",
            minVersion = 4013,
            startMs = 1809820800000L, // 2027-05-09 00:00 UTC (5月第2个周日)
            deadlineMs = 1811030400000L, // +14天
            attachments = HOLIDAY_ATTACHMENTS
        ),
        BuiltinMail(
            id = "mail_dragon_boat_2027",
            title = "端午安康",
            content = "道友安好！\n\n五月初五，龙舟竞渡，粽叶飘香。\n\n值此端午佳节，天道意志特奉薄礼一份，愿道友仙途坦荡、宗门昌盛，早日登临仙道之巅！\n\n——天道意志",
            mailType = "reward",
            minVersion = 4013,
            startMs = 1812499200000L, // 2027-06-09 00:00 UTC (农历五月初五)
            deadlineMs = 1813708800000L, // +14天
            attachments = HOLIDAY_ATTACHMENTS
        ),
        BuiltinMail(
            id = "mail_father_2027",
            title = "父亲节快乐",
            content = "道友安好！\n\n父爱如山，沉默而厚重。\n\n值此父亲节，天道意志特奉薄礼一份，愿道友感念父恩，修炼之余不忘人间至亲。\n\n——天道意志",
            mailType = "reward",
            minVersion = 4013,
            startMs = 1813449600000L, // 2027-06-20 00:00 UTC (6月第3个周日)
            deadlineMs = 1814659200000L, // +14天
            attachments = HOLIDAY_ATTACHMENTS
        ),
        BuiltinMail(
            id = "mail_qixi_2027",
            title = "七夕快乐",
            content = "道友安好！\n\n金风玉露一相逢，便胜却人间无数。\n\n值此七夕佳节，天道意志特奉薄礼一份，愿道友仙缘美满、道侣情深，修仙路上不孤单。\n\n——天道意志",
            mailType = "reward",
            minVersion = 4013,
            startMs = 1817683200000L, // 2027-08-08 00:00 UTC (农历七月初七)
            deadlineMs = 1818892800000L, // +14天
            attachments = HOLIDAY_ATTACHMENTS
        ),
        BuiltinMail(
            id = "mail_mid_autumn_2027",
            title = "中秋快乐",
            content = "道友安好！\n\n但愿人长久，千里共婵娟。\n\n值此中秋佳节，天道意志特奉薄礼一份，愿道友月圆人团圆，仙途圆满如满月当空。\n\n——天道意志",
            mailType = "reward",
            minVersion = 4013,
            startMs = 1820966400000L, // 2027-09-15 00:00 UTC (农历八月十五)
            deadlineMs = 1822176000000L, // +14天
            attachments = HOLIDAY_ATTACHMENTS
        ),
        BuiltinMail(
            id = "mail_national_2027",
            title = "国庆快乐",
            content = "道友安好！\n\n普天同庆，盛世华章。\n\n值此国庆佳节，天道意志特奉薄礼一份，愿道友与国同庆，宗门兴旺，仙道昌盛！\n\n——天道意志",
            mailType = "reward",
            minVersion = 4013,
            startMs = 1822348800000L, // 2027-10-01 00:00 UTC
            deadlineMs = 1823558400000L, // +14天
            attachments = HOLIDAY_ATTACHMENTS
        ),
        BuiltinMail(
            id = "mail_chongyang_2027",
            title = "重阳安康",
            content = "道友安好！\n\n独在异乡为异客，每逢佳节倍思亲。\n\n值此重阳佳节，天道意志特奉薄礼一份，愿道友登高望远、仙途开阔，长辈安康福寿绵长。\n\n——天道意志",
            mailType = "reward",
            minVersion = 4013,
            startMs = 1822953600000L, // 2027-10-08 00:00 UTC (农历九月初九)
            deadlineMs = 1824163200000L, // +14天
            attachments = HOLIDAY_ATTACHMENTS
        ),
        BuiltinMail(
            id = "mail_winter_solstice_2027",
            title = "冬至安康",
            content = "道友安好！\n\n冬至阳生春又来，阴极之至阳气始生。\n\n值此冬至节气，天道意志特奉薄礼一份，愿道友顺应天道、养精蓄锐，来年仙途更上层楼！\n\n——天道意志",
            mailType = "reward",
            minVersion = 4013,
            startMs = 1829433600000L, // 2027-12-22 00:00 UTC
            deadlineMs = 1830643200000L, // +14天
            attachments = HOLIDAY_ATTACHMENTS
        )
    )
}
