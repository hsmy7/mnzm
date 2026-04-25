package com.xianxia.sect.core.config

object DiplomaticEventConfig {

    enum class EventScope { PLAYER_ONLY, AI_ONLY, ALL }

    data class DiplomaticEventDef(
        val id: String,
        val name: String,
        val description: String,
        val scope: EventScope,
        val baseChance: Double,
        val favorChange: Int,
        val minFavorToTrigger: Int = 0,
        val maxFavorToTrigger: Int = 100,
        val requiresAlliance: Boolean = false,
        val requiresAdjacent: Boolean = false,
        val requiresSameAlignment: Boolean = false,
        val requiresDifferentAlignment: Boolean = false,
        val favorScaleFactor: Double = 0.0,
        val isPositive: Boolean = true
    )

    object Events {
        val BORDER_DISPUTE = DiplomaticEventDef(
            id = "border_dispute",
            name = "边境争端",
            description = "两宗弟子在边境因修炼资源发生冲突",
            scope = EventScope.ALL,
            baseChance = 0.03,
            favorChange = -5,
            minFavorToTrigger = 10,
            requiresAdjacent = true,
            isPositive = false
        )

        val RESOURCE_CONFLICT = DiplomaticEventDef(
            id = "resource_conflict",
            name = "资源争夺",
            description = "两宗因争夺灵矿资源产生矛盾",
            scope = EventScope.ALL,
            baseChance = 0.02,
            favorChange = -8,
            minFavorToTrigger = 20,
            requiresAdjacent = true,
            isPositive = false
        )

        val DISCIPLE_CLASH = DiplomaticEventDef(
            id = "disciple_clash",
            name = "弟子冲突",
            description = "两宗弟子在外历练时发生争执",
            scope = EventScope.ALL,
            baseChance = 0.04,
            favorChange = -3,
            minFavorToTrigger = 5,
            isPositive = false
        )

        val CULTURAL_EXCHANGE = DiplomaticEventDef(
            id = "cultural_exchange",
            name = "文化交流",
            description = "两宗弟子互相交流修炼心得",
            scope = EventScope.ALL,
            baseChance = 0.03,
            favorChange = 3,
            minFavorToTrigger = 30,
            isPositive = true
        )

        val JOINT_EXPEDITION = DiplomaticEventDef(
            id = "joint_expedition",
            name = "联合探险",
            description = "两宗弟子在秘境中携手合作",
            scope = EventScope.ALL,
            baseChance = 0.02,
            favorChange = 5,
            minFavorToTrigger = 50,
            isPositive = true
        )

        val MUTUAL_AID = DiplomaticEventDef(
            id = "mutual_aid",
            name = "互助救灾",
            description = "一宗遭遇灾祸，另一宗伸出援手",
            scope = EventScope.ALL,
            baseChance = 0.015,
            favorChange = 8,
            minFavorToTrigger = 40,
            isPositive = true
        )

        val ALLIANCE_COOPERATION = DiplomaticEventDef(
            id = "alliance_cooperation",
            name = "盟友协作",
            description = "盟约宗门之间加深合作",
            scope = EventScope.ALL,
            baseChance = 0.05,
            favorChange = 2,
            requiresAlliance = true,
            isPositive = true
        )

        val TRADE_BOOM = DiplomaticEventDef(
            id = "trade_boom",
            name = "贸易繁荣",
            description = "两宗之间商贸往来频繁",
            scope = EventScope.ALL,
            baseChance = 0.03,
            favorChange = 4,
            minFavorToTrigger = 40,
            isPositive = true
        )

        val TERRITORIAL_ENCROACHMENT = DiplomaticEventDef(
            id = "territorial_encroachment",
            name = "领地蚕食",
            description = "一宗暗中蚕食另一宗的势力范围",
            scope = EventScope.AI_ONLY,
            baseChance = 0.02,
            favorChange = -12,
            minFavorToTrigger = 15,
            requiresAdjacent = true,
            isPositive = false
        )

        val SPY_DISCOVERED = DiplomaticEventDef(
            id = "spy_discovered",
            name = "间谍暴露",
            description = "一宗派出的间谍被另一宗抓获",
            scope = EventScope.AI_ONLY,
            baseChance = 0.015,
            favorChange = -15,
            minFavorToTrigger = 10,
            isPositive = false
        )

        val MARRIAGE_ALLIANCE = DiplomaticEventDef(
            id = "marriage_alliance",
            name = "联姻结好",
            description = "两宗通过弟子联姻加深关系",
            scope = EventScope.AI_ONLY,
            baseChance = 0.01,
            favorChange = 15,
            minFavorToTrigger = 60,
            isPositive = true
        )

        val SAME_ALIGNMENT_BOND = DiplomaticEventDef(
            id = "same_alignment_bond",
            name = "同道相惜",
            description = "正道/邪道宗门之间因立场一致而亲近",
            scope = EventScope.AI_ONLY,
            baseChance = 0.025,
            favorChange = 5,
            requiresSameAlignment = true,
            isPositive = true
        )

        val OPPOSING_ALIGNMENT_CLASH = DiplomaticEventDef(
            id = "opposing_alignment_clash",
            name = "正邪对立",
            description = "正道与邪道宗门之间爆发冲突",
            scope = EventScope.AI_ONLY,
            baseChance = 0.03,
            favorChange = -7,
            requiresDifferentAlignment = true,
            isPositive = false
        )

        val PLAYER_DISCIPLE_ENCOUNTER = DiplomaticEventDef(
            id = "player_disciple_encounter",
            name = "弟子偶遇",
            description = "我宗弟子在外偶遇他宗弟子，相谈甚欢",
            scope = EventScope.PLAYER_ONLY,
            baseChance = 0.04,
            favorChange = 2,
            minFavorToTrigger = 20,
            isPositive = true
        )

        val PLAYER_ESCORT_MISSION = DiplomaticEventDef(
            id = "player_escort_mission",
            name = "护送之恩",
            description = "我宗弟子护送了他宗遇险弟子",
            scope = EventScope.PLAYER_ONLY,
            baseChance = 0.02,
            favorChange = 6,
            minFavorToTrigger = 30,
            isPositive = true
        )

        val PLAYER_INSULT_INCIDENT = DiplomaticEventDef(
            id = "player_insult_incident",
            name = "口角之争",
            description = "我宗弟子与他宗弟子发生口角",
            scope = EventScope.PLAYER_ONLY,
            baseChance = 0.025,
            favorChange = -4,
            minFavorToTrigger = 5,
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

        fun getEventsByScope(scope: EventScope): List<DiplomaticEventDef> {
            return ALL_EVENTS.filter { it.scope == scope || it.scope == EventScope.ALL }
        }
    }

    object Decay {
        const val HIGH_FAVOR_DECAY_THRESHOLD = 80
        const val HIGH_FAVOR_DECAY_AMOUNT = 1
        const val HIGH_FAVOR_DECAY_YEARS = 1

        const val MEDIUM_FAVOR_DECAY_THRESHOLD = 60
        const val MEDIUM_FAVOR_DECAY_AMOUNT = 1
        const val MEDIUM_FAVOR_DECAY_YEARS = 3

        const val LOW_FAVOR_RECOVERY_THRESHOLD = 20
        const val LOW_FAVOR_RECOVERY_AMOUNT = 1
        const val LOW_FAVOR_RECOVERY_YEARS = 5

        const val AI_RELATION_DECAY_CHANCE = 0.02
        const val AI_RELATION_DECAY_AMOUNT = 1
    }

    object BattleFavor {
        const val ATTACKER_WIN_FAVOR_LOSS = -12
        const val DEFENDER_WIN_FAVOR_LOSS = -6
        const val DRAW_FAVOR_LOSS = -8
        const val DESTROY_FAVOR_LOSS = -20
        const val PLAYER_ATTACKED_FAVOR_MULTIPLIER = 1.5
        const val SAME_ALIGNMENT_FAVOR_REDUCTION = 0.7
        const val ALLIANCE_BETRAYAL_FAVOR_LOSS = -30
    }

    object TradeFavor {
        const val TRADE_FAVOR_PER_TRANSACTION = 1
        const val TRADE_FAVOR_MAX_PER_YEAR = 5
    }

    object AllianceFavor {
        const val ALLIANCE_YEARLY_FAVOR_BONUS = 2
        const val ALLIANCE_BROKEN_FAVOR_LOSS = -15
    }

    object AIRelation {
        const val AI_ALLIANCE_MIN_FAVOR = 70
        const val AI_ALLIANCE_CHANCE_PER_YEAR = 0.05
        const val AI_ALLIANCE_MAX_PER_SECT = 2
        const val AI_FAVOR_DRIFT_CHANCE = 0.03
        const val AI_FAVOR_DRIFT_AMOUNT = 2
    }
}
