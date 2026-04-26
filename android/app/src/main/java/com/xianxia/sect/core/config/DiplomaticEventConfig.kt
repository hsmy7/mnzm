package com.xianxia.sect.core.config

object DiplomaticEventConfig {

    data class DiplomaticEventDef(
        val id: String,
        val name: String,
        val description: String,
        val favorChange: Int,
        val isPositive: Boolean = true
    )

    const val MONTHLY_TRIGGER_CHANCE = 0.03

    object Events {
        val BORDER_DISPUTE = DiplomaticEventDef(
            id = "border_dispute",
            name = "边境争端",
            description = "两宗弟子在边境因修炼资源发生冲突",
            favorChange = -5,
            isPositive = false
        )

        val RESOURCE_CONFLICT = DiplomaticEventDef(
            id = "resource_conflict",
            name = "资源争夺",
            description = "两宗因争夺灵矿资源产生矛盾",
            favorChange = -8,
            isPositive = false
        )

        val DISCIPLE_CLASH = DiplomaticEventDef(
            id = "disciple_clash",
            name = "弟子冲突",
            description = "两宗弟子在外历练时发生争执",
            favorChange = -3,
            isPositive = false
        )

        val CULTURAL_EXCHANGE = DiplomaticEventDef(
            id = "cultural_exchange",
            name = "文化交流",
            description = "两宗弟子互相交流修炼心得",
            favorChange = 3,
            isPositive = true
        )

        val JOINT_EXPEDITION = DiplomaticEventDef(
            id = "joint_expedition",
            name = "联合探险",
            description = "两宗弟子在秘境中携手合作",
            favorChange = 5,
            isPositive = true
        )

        val MUTUAL_AID = DiplomaticEventDef(
            id = "mutual_aid",
            name = "互助救灾",
            description = "一宗遭遇灾祸，另一宗伸出援手",
            favorChange = 8,
            isPositive = true
        )

        val ALLIANCE_COOPERATION = DiplomaticEventDef(
            id = "alliance_cooperation",
            name = "盟友协作",
            description = "盟约宗门之间加深合作",
            favorChange = 2,
            isPositive = true
        )

        val TRADE_BOOM = DiplomaticEventDef(
            id = "trade_boom",
            name = "贸易繁荣",
            description = "两宗之间商贸往来频繁",
            favorChange = 4,
            isPositive = true
        )

        val TERRITORIAL_ENCROACHMENT = DiplomaticEventDef(
            id = "territorial_encroachment",
            name = "领地蚕食",
            description = "一宗暗中蚕食另一宗的势力范围",
            favorChange = -12,
            isPositive = false
        )

        val SPY_DISCOVERED = DiplomaticEventDef(
            id = "spy_discovered",
            name = "间谍暴露",
            description = "一宗派出的间谍被另一宗抓获",
            favorChange = -15,
            isPositive = false
        )

        val MARRIAGE_ALLIANCE = DiplomaticEventDef(
            id = "marriage_alliance",
            name = "联姻结好",
            description = "两宗通过弟子联姻加深关系",
            favorChange = 15,
            isPositive = true
        )

        val SAME_ALIGNMENT_BOND = DiplomaticEventDef(
            id = "same_alignment_bond",
            name = "同道相惜",
            description = "正道/邪道宗门之间因立场一致而亲近",
            favorChange = 5,
            isPositive = true
        )

        val OPPOSING_ALIGNMENT_CLASH = DiplomaticEventDef(
            id = "opposing_alignment_clash",
            name = "正邪对立",
            description = "正道与邪道宗门之间爆发冲突",
            favorChange = -7,
            isPositive = false
        )

        val PLAYER_DISCIPLE_ENCOUNTER = DiplomaticEventDef(
            id = "player_disciple_encounter",
            name = "弟子偶遇",
            description = "我宗弟子在外偶遇他宗弟子，相谈甚欢",
            favorChange = 2,
            isPositive = true
        )

        val PLAYER_ESCORT_MISSION = DiplomaticEventDef(
            id = "player_escort_mission",
            name = "护送之恩",
            description = "我宗弟子护送了他宗遇险弟子",
            favorChange = 6,
            isPositive = true
        )

        val PLAYER_INSULT_INCIDENT = DiplomaticEventDef(
            id = "player_insult_incident",
            name = "口角之争",
            description = "我宗弟子与他宗弟子发生口角",
            favorChange = -4,
            isPositive = false
        )

        val ALL_EVENTS: List<DiplomaticEventDef> = listOf(
            BORDER_DISPUTE,
            RESOURCE_CONFLICT,
            DISCIPLE_CLASH,
            CULTURAL_EXCHANGE,
            JOINT_EXPEDITION,
            MUTUAL_AID,
            ALLIANCE_COOPERATION,
            TRADE_BOOM,
            TERRITORIAL_ENCROACHMENT,
            SPY_DISCOVERED,
            MARRIAGE_ALLIANCE,
            SAME_ALIGNMENT_BOND,
            OPPOSING_ALIGNMENT_CLASH,
            PLAYER_DISCIPLE_ENCOUNTER,
            PLAYER_ESCORT_MISSION,
            PLAYER_INSULT_INCIDENT
        )
    }
}
