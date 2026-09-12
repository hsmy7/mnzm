#!/usr/bin/env python3
"""
Generate new manual JSON entries based on the approved plan's numerical tables.
Outputs JSON snippets ready to be merged into manuals.json.
"""

import json

# ========== Base Stat Values by Rarity ==========
# From existing manuals.json
STATS = {
    1: {"hp": 36, "mp": 18, "pa": 9, "ma": 9, "pd": 3, "md": 3, "spd": 5},
    2: {"hp": 234, "mp": 117, "pa": 63, "ma": 63, "pd": 21, "md": 21, "spd": 34},
    3: {"hp": 612, "mp": 306, "pa": 164, "ma": 164, "pd": 55, "md": 55, "spd": 88},
    4: {"hp": 1620, "mp": 810, "pa": 432, "ma": 432, "pd": 144, "md": 144, "spd": 230},
    5: {"hp": 3960, "mp": 1980, "pa": 1056, "ma": 1056, "pd": 351, "md": 351, "spd": 562},
    6: {"hp": 20880, "mp": 10440, "pa": 5568, "ma": 5568, "pd": 1855, "md": 1855, "spd": 2968},
}

# Tier multipliers for stat bonuses (S=0.5, A=0.75, B=1.0, C=1.25)
TIER = {"S": 0.5, "A": 0.75, "B": 1.0, "C": 1.25}

def stat_val(rarity, key, tier, team=False):
    base = STATS[rarity][key]
    val = base * TIER[tier]
    if team:
        val *= 0.5
    return round(val)

# ========== Skill Names ==========
# Category -> (name_prefix, skill_name) per rarity per scope

def make_id(cat, rarity, scope, variant="pct"):
    # variant: pct, fixed
    return f"new_{cat}_{rarity}_{scope}_{variant}"

def make_manual(cat_id, cat_name, manual_type, tier, rarities,
                scopes, # list of ["self", "single", "team"] or ["enemy", "all_enemies"]
                names_map, # {scope: {rarity: (manual_name, skill_name)}}
                effect_configs, # function(rarity, scope) -> dict of skill params
                stat_configs): # function(rarity, scope) -> dict of stats
    """Generate a single manual JSON entry."""
    entries = []
    for rarity in rarities:
        for scope in scopes:
            names = names_map.get(scope, {}).get(rarity, (f"{cat_name}_{scope}_{rarity}", f"Skill_{scope}_{rarity}"))
            manual_name, skill_name = names
            effects = effect_configs(rarity, scope)
            stats = stat_configs(rarity, scope, tier)

            entry = {
                "id": make_id(cat_id, rarity, scope, effects.get("variant", "base")),
                "name": manual_name,
                "type": manual_type,
                "rarity": rarity,
                "description": f"{manual_name}——{skill_name}",
                "stats": stats,
                "skillName": skill_name,
                "skillDescription": effects.get("desc", ""),
                "skillType": effects.get("skillType", "support"),
                "skillDamageType": effects.get("damageType", "physical"),
                "skillHits": effects.get("hits", 1),
                "skillDamageMultiplier": effects.get("dmgMult", 1.0),
                "skillCooldown": effects.get("cooldown", 3),
                "skillMpCost": effects.get("mpCost", 10),
                "skillHealPercent": effects.get("healPercent", 0.0),
                "skillHealFixed": effects.get("healFixed", 0),
                "skillHealType": effects.get("healType", "hp"),
                "skillBuffType": effects.get("buffType"),
                "skillBuffValue": effects.get("buffValue", 0.0),
                "skillBuffDuration": effects.get("buffDuration", 0),
                "skillBuffs": effects.get("buffs", []),
                "skillIsAoe": effects.get("isAoe", False),
                "skillTargetScope": effects.get("targetScope", "self"),
                "skillShieldPercent": effects.get("shieldPercent", 0.0),
                "skillTurnAdvancePercent": effects.get("turnAdvancePercent", 0.0),
                "skillDamageSharePercent": effects.get("damageSharePercent", 0.0),
                "skillDamageLinkPercent": effects.get("damageLinkPercent", 0.0),
                "price": effects.get("price", 0),
                "minRealm": effects.get("minRealm", 9)
            }
            # Remove None buffType
            if entry["skillBuffType"] is None:
                del entry["skillBuffType"]
            entries.append(entry)
    return entries

# ========== MP Cost Tables ==========
# group=1.0, single=0.8, self=0.6 of base
MP_BASE = {1: 25, 2: 150, 3: 400, 4: 1000, 5: 2500, 6: 12000}
def mp_cost(rarity, scope):
    if scope in ("team", "all_enemies"):
        return MP_BASE[rarity]
    elif scope in ("single", "enemy", "ally"):
        return round(MP_BASE[rarity] * 0.8)
    else:  # self
        return round(MP_BASE[rarity] * 0.6)

PRICE_BASE = {1: 3600, 2: 16200, 3: 64800, 4: 259200, 5: 1036800, 6: 4147200}

# ===== NAMES =====

# Category 1: Taunt
TAUNT_NAMES = {
    "enemy": {1: ("镇岳功", "镇岳"), 2: ("挑衅诀", "挑衅"), 3: ("金刚怒目", "怒目"),
              4: ("不动明王法", "不动镇魔"), 5: ("万夫莫开诀", "万夫莫开"), 6: ("唯我独尊功", "唯我独尊")}
}

# Category 2: Stun
STUN_NAMES = {
    "enemy": {1: ("定身诀", "定身"), 2: ("缚魂术", "缚魂"), 3: ("封灵印法", "封灵印"),
              4: ("镇魂经", "镇魂咒"), 5: ("天罗地网功", "天罗地网"), 6: ("轮回禁锢诀", "轮回禁锢")}
}

# Category 3: HP Heal
HP_HEAL_PCT_NAMES = {
    "self": {1: ("回春术", "回春"), 2: ("生生不息功", "生生不息"), 3: ("枯木逢春诀", "枯木逢春"),
             4: ("九转还魂术", "九转还魂"), 5: ("不死真凰诀", "真凰涅槃"), 6: ("起死回生术", "起死回生")},
    "single": {1: ("妙手回春诀", "妙手回春"), 2: ("济世诀", "济世救人"), 3: ("大悲咒", "大悲愈伤"),
               4: ("涅槃经", "涅槃疗伤"), 5: ("造化疗伤术", "造化回春"), 6: ("大道愈合诀", "大道愈合")},
    "team": {1: ("甘霖诀", "甘霖普降"), 2: ("普济众生术", "普济苍生"), 3: ("春风化雨术", "春风化雨"),
             4: ("万物回春经", "万物回春"), 5: ("天地回春功", "天地回春"), 6: ("造化回天诀", "回天再造")}
}
HP_HEAL_FIX_NAMES = {
    "self": {1: ("续命膏方", "续命"), 2: ("血元丹术", "血元贯注"), 3: ("活血化瘀功", "活血化瘀"),
             4: ("换血大法", "换血重生"), 5: ("血祭术", "血祭回天"), 6: ("轮回血术", "轮回渡血")},
    "single": {1: ("金创药术", "金创止血"), 2: ("灵枢针法", "灵枢刺穴"), 3: ("九针术", "九针续命"),
               4: ("天医神针", "天医渡厄"), 5: ("神农药典", "神农百草"), 6: ("女娲补天术", "女娲补天")},
    "team": {1: ("青囊术", "青囊济世"), 2: ("百草经", "百草回春"), 3: ("药王典", "药王赐福"),
             4: ("丹霞圣手", "丹霞普救"), 5: ("菩提甘露", "菩提普渡"), 6: ("普度众生经", "普度众生")}
}

# HP heal % values (self=single)
HP_HEAL_PCT = {1: 30, 2: 33, 3: 37, 4: 42, 5: 48, 6: 58}  # percent
HP_HEAL_FIX_SELF = {1: 100, 2: 630, 3: 1650, 4: 4400, 5: 10800, 6: 58000}  # fixed

# Category 4: MP Heal
MP_HEAL_PCT_NAMES = {
    "self": {1: ("聚灵术", "聚灵"), 2: ("纳气归元功", "纳气归元"), 3: ("太阴吐纳诀", "太阴吐纳"),
             4: ("鲸吞天地功", "鲸吞天地"), 5: ("吞天纳地诀", "吞天纳地"), 6: ("混沌元灵气", "混沌汲灵")},
    "single": {1: ("还元诀", "还元归灵"), 2: ("引灵诀", "引灵渡气"), 3: ("灵桥引", "灵桥接引"),
               4: ("玉清度灵诀", "玉清度灵"), 5: ("混元引灵法", "混元引灵"), 6: ("鸿蒙渡灵术", "鸿蒙渡灵")},
    "team": {1: ("紫气东来术", "紫气东来"), 2: ("天河引灵术", "天河引灵"), 3: ("九天引灵经", "九天引灵"),
             4: ("万灵归宗术", "万灵归宗"), 5: ("九天聚灵阵", "九天聚灵启"), 6: ("大道归元诀", "大道归元")}
}
MP_HEAL_FIX_NAMES = {
    "self": {1: ("灵石吐纳法", "灵石吐纳"), 2: ("鲸吞术", "鲸吞纳气"), 3: ("吞天术", "吞天纳气"),
             4: ("噬灵术", "噬灵夺元"), 5: ("归墟纳元术", "归墟纳元"), 6: ("创世吞灵功", "创世吞灵")},
    "single": {1: ("传功法", "传功渡灵"), 2: ("灌顶术", "灌顶传功"), 3: ("元神传功诀", "元神传功"),
               4: ("醍醐灌顶法", "醍醐灌顶"), 5: ("大道传功诀", "大道传功"), 6: ("天尊灌顶诀", "天尊灌顶")},
    "team": {1: ("聚灵阵诀", "聚灵阵启"), 2: ("八方聚元阵", "八方聚元"), 3: ("星辰聚灵阵", "星辰聚灵"),
             4: ("周天引灵阵", "周天引灵"), 5: ("星斗归元阵", "星斗归元"), 6: ("混沌聚灵阵", "混沌聚灵")}
}
MP_HEAL_PCT = {1: 18, 2: 22, 3: 26, 4: 30, 5: 36, 6: 42}
MP_HEAL_FIX_SELF = {1: 38, 2: 250, 3: 650, 4: 1700, 5: 4300, 6: 22000}

# Category 5: Physical Attack
PHYS_ATK_NAMES = {
    "enemy": {1: ("贯石剑法", "贯石击"), 2: ("破岳刀诀", "破岳斩"), 3: ("碎星枪法", "碎星刺"),
              4: ("陨日剑典", "陨日一击"), 5: ("屠龙刀经", "屠龙斩"), 6: ("开天辟地功", "开天一击")},
    "all_enemies": {1: ("横扫六合诀", "横扫六合"), 2: ("万剑归宗诀", "万剑齐发"), 3: ("裂地震天功", "裂地震天"),
                    4: ("山崩地裂诀", "山崩地裂"), 5: ("天崩地裂功", "天崩地裂"), 6: ("毁天灭地诀", "毁天灭地")}
}
PHYS_ATK_MULT = {1: 2.0, 2: 2.4, 3: 2.9, 4: 3.5, 5: 4.2, 6: 5.0}

# Category 6: Magic Attack
MAG_ATK_NAMES = {
    "enemy": {1: ("玄冰术", "玄冰刺"), 2: ("天雷正法", "天雷击"), 3: ("九天玄雷诀", "九天玄雷"),
              4: ("紫霄神雷经", "紫霄神雷"), 5: ("太上玄雷术", "太上奔雷"), 6: ("混沌神雷典", "混沌神雷")},
    "all_enemies": {1: ("火雨术", "火雨天降"), 2: ("冰风暴诀", "冰风暴"), 3: ("焚天烈焰功", "焚天烈焰"),
                    4: ("炼狱火海诀", "炼狱火海"), 5: ("万劫天火功", "万劫天火"), 6: ("灭世焚天诀", "灭世焚天")}
}

# Category 7: Phys Def
PHYS_DEF_NAMES = {
    "self": {1: ("钢筋铁骨功", "钢筋铁骨"), 2: ("玄铁金身诀", "玄铁金身"), 3: ("金刚不坏功", "金刚不坏"),
             4: ("万劫不坏体", "万劫不坏"), 5: ("永恒不灭体", "永恒不灭"), 6: ("混沌不坏身", "混沌不坏")},
    "single": {1: ("护体罡气诀", "护体罡气"), 2: ("金刚护体功", "金刚护体"), 3: ("罗汉金身诀", "罗汉金身"),
               4: ("斗战金身功", "斗战金身"), 5: ("金仙金身诀", "金仙金身"), 6: ("圣人金身功", "圣人金身")},
    "team": {1: ("铁壁阵诀", "铁壁阵"), 2: ("金汤壁垒术", "金汤壁垒"), 3: ("铜墙铁壁阵", "铜墙铁壁"),
             4: ("天罡战阵诀", "天罡战阵"), 5: ("不动明王阵", "明王守护"), 6: ("周天星斗阵", "星斗护体")}
}
PHYS_DEF_PCT = {1: 30, 2: 35, 3: 40, 4: 45, 5: 55, 6: 65}

# Category 8: Magic Def (same values as phys def, different names)
MAG_DEF_NAMES = {
    "self": {1: ("凝神静气功", "凝神静气"), 2: ("元神灵盾功", "元神灵盾"), 3: ("天罡护神功", "天罡护神"),
             4: ("元神不灭术", "元神不灭"), 5: ("太虚元神术", "太虚元神"), 6: ("混沌元神诀", "混沌元神")},
    "single": {1: ("清心定神诀", "清心定神"), 2: ("太一护神诀", "太一护神"), 3: ("紫府护神诀", "紫府护神"),
               4: ("三花护神功", "三花护神"), 5: ("五气朝元诀", "五气朝元"), 6: ("大道护神功", "大道护神")},
    "team": {1: ("辟邪结界术", "辟邪结界"), 2: ("镇魔结界术", "镇魔结界"), 3: ("九天辟魔阵", "九天辟魔"),
             4: ("万法不侵阵", "万法不侵"), 5: ("大道辟易阵", "大道辟易"), 6: ("混元无极阵", "混元无极")}
}

# Category 9: Damage Boost
DMG_BOOST_NAMES = {
    "self": {1: ("破军诀", "破军之势"), 2: ("战意诀", "战意激昂"), 3: ("狂战诀", "狂战无双"),
             4: ("嗜血术", "嗜血狂袭"), 5: ("杀神诀", "杀神领域"), 6: ("诛天战意", "诛天之势")},
    "single": {1: ("破军诀", "破军之势"), 2: ("战意诀", "战意激昂"), 3: ("狂战诀", "狂战无双"),
               4: ("嗜血术", "嗜血狂袭"), 5: ("杀神诀", "杀神领域"), 6: ("诛天战意", "诛天之势")},
    "team": {1: ("破军诀", "破军之势"), 2: ("战意诀", "战意激昂"), 3: ("狂战诀", "狂战无双"),
             4: ("嗜血术", "嗜血狂袭"), 5: ("杀神诀", "杀神领域"), 6: ("诛天战意", "诛天之势")}
}
DMG_BOOST_PCT = {1: 22, 2: 28, 3: 36, 4: 48, 5: 59, 6: 75}

# Category 10: Crit Boost
CRIT_NAMES = {
    "self": {1: ("鹰眼术", "鹰眼凝视"), 2: ("心眼诀", "心眼洞开"), 3: ("天眼通", "天眼洞察"),
             4: ("慧眼识真功", "慧眼识真"), 5: ("洞察天机术", "洞察天机"), 6: ("全知领域诀", "全知领域")},
    "single": {1: ("鹰眼术", "鹰眼凝视"), 2: ("心眼诀", "心眼洞开"), 3: ("天眼通", "天眼洞察"),
               4: ("慧眼识真功", "慧眼识真"), 5: ("洞察天机术", "洞察天机"), 6: ("全知领域诀", "全知领域")},
    "team": {1: ("鹰眼术", "鹰眼凝视"), 2: ("心眼诀", "心眼洞开"), 3: ("天眼通", "天眼洞察"),
             4: ("慧眼识真功", "慧眼识真"), 5: ("洞察天机术", "洞察天机"), 6: ("全知领域诀", "全知领域")}
}
CRIT_VAL = {1: 10, 2: 14, 3: 19, 4: 25, 5: 32, 6: 40}

# Category 11: Damage Reduction
DMG_RED_NAMES = {
    "self": {1: ("化劲诀", "化劲卸力"), 2: ("卸力功", "四两拨千斤"), 3: ("乾坤挪移法", "乾坤挪移"),
             4: ("斗转星移功", "斗转星移"), 5: ("万法归墟诀", "万法归墟"), 6: ("天道归无术", "天道归无")},
    "single": {1: ("化劲诀", "化劲卸力"), 2: ("卸力功", "四两拨千斤"), 3: ("乾坤挪移法", "乾坤挪移"),
               4: ("斗转星移功", "斗转星移"), 5: ("万法归墟诀", "万法归墟"), 6: ("天道归无术", "天道归无")},
    "team": {1: ("化劲诀", "化劲卸力"), 2: ("卸力功", "四两拨千斤"), 3: ("乾坤挪移法", "乾坤挪移"),
             4: ("斗转星移功", "斗转星移"), 5: ("万法归墟诀", "万法归墟"), 6: ("天道归无术", "天道归无")}
}
DMG_RED_PCT = {1: 18, 2: 22, 3: 28, 4: 32, 5: 40, 6: 50}

# Category 12: Attack Reduce
ATK_RED_NAMES = {
    "enemy": {1: ("弱化术", "弱化"), 2: ("削骨诀", "削骨"), 3: ("虚弱诅咒术", "虚弱诅咒"),
              4: ("衰败经", "衰败凋零"), 5: ("枯荣诀", "万物枯荣"), 6: ("剥夺领域功", "剥夺领域")},
    "all_enemies": {1: ("弱化术", "弱化"), 2: ("削骨诀", "削骨"), 3: ("虚弱诅咒术", "虚弱诅咒"),
                    4: ("衰败经", "衰败凋零"), 5: ("枯荣诀", "万物枯荣"), 6: ("剥夺领域功", "剥夺领域")}
}
ATK_RED_PCT = {1: 20, 2: 25, 3: 30, 4: 35, 5: 40, 6: 50}

# Category 13: Def Reduce
DEF_RED_NAMES = {
    "enemy": {1: ("破甲术", "破甲"), 2: ("碎甲诀", "碎甲击"), 3: ("腐蚀术", "腐蚀侵体"),
              4: ("瓦解结界法", "瓦解结界"), 5: ("崩坏领域诀", "崩坏领域"), 6: ("万法皆破功", "万法皆破")},
    "all_enemies": {1: ("破甲术", "破甲"), 2: ("碎甲诀", "碎甲击"), 3: ("腐蚀术", "腐蚀侵体"),
                    4: ("瓦解结界法", "瓦解结界"), 5: ("崩坏领域诀", "崩坏领域"), 6: ("万法皆破功", "万法皆破")}
}

# Category 14: Shield
SHIELD_NAMES = {
    "self": {1: ("真元护体术", "真元护体"), 2: ("灵罡护盾诀", "灵罡护盾"), 3: ("玄黄罩", "玄黄天罩"),
             4: ("不动结界法", "不动结界"), 5: ("周天护体功", "周天护体"), 6: ("混沌守护诀", "混沌守护")},
    "single": {1: ("真元护体术", "真元护体"), 2: ("灵罡护盾诀", "灵罡护盾"), 3: ("玄黄罩", "玄黄天罩"),
               4: ("不动结界法", "不动结界"), 5: ("周天护体功", "周天护体"), 6: ("混沌守护诀", "混沌守护")},
    "team": {1: ("真元护体术", "真元护体"), 2: ("灵罡护盾诀", "灵罡护盾"), 3: ("玄黄罩", "玄黄天罩"),
             4: ("不动结界法", "不动结界"), 5: ("周天护体功", "周天护体"), 6: ("混沌守护诀", "混沌守护")}
}
SHIELD_PCT = {1: 35, 2: 40, 3: 45, 4: 55, 5: 65, 6: 82}

# Category 15: Turn Advance
TURN_ADV_NAMES = {
    "ally": {1: ("疾风步", "疾风突进"), 2: ("追云术", "追云逐日"), 3: ("缩地成寸法", "缩地成寸"),
             4: ("纵地金光术", "纵地金光"), 5: ("瞬息千里诀", "瞬息千里"), 6: ("时空逆转功", "时空逆转")}
}
TURN_ADV_PCT = {1: 50, 2: 60, 3: 70, 4: 80, 5: 90, 6: 100}

# Category 16: Damage Share
SHARE_NAMES = {
    "self": {1: ("同甘共苦诀", "同甘共苦"), 2: ("生死与共功", "生死与共"), 3: ("血脉相连法", "血脉相连"),
             4: ("命运共同体术", "命运共同体"), 5: ("同命锁", "同命相连"), 6: ("大道同归诀", "大道同归")},
    "ally": {1: ("同甘共苦诀", "同甘共苦"), 2: ("生死与共功", "生死与共"), 3: ("血脉相连法", "血脉相连"),
             4: ("命运共同体术", "命运共同体"), 5: ("同命锁", "同命相连"), 6: ("大道同归诀", "大道同归")}
}
SHARE_PCT_SELF = {1: 40, 2: 45, 3: 50, 4: 55, 5: 65, 6: 75}
SHARE_PCT_ALLY = {1: 50, 2: 55, 3: 60, 4: 65, 5: 75, 6: 85}

# Category 17: Speed Boost
SPEED_NAMES = {
    "self": {1: ("轻身术", "轻身疾行"), 2: ("御风诀", "御风而行"), 3: ("踏云步法", "踏云追月"),
             4: ("凌空虚渡功", "凌空虚渡"), 5: ("扶摇直上术", "扶摇直上"), 6: ("超脱极速诀", "超脱极速")},
    "single": {1: ("轻身术", "轻身疾行"), 2: ("御风诀", "御风而行"), 3: ("踏云步法", "踏云追月"),
               4: ("凌空虚渡功", "凌空虚渡"), 5: ("扶摇直上术", "扶摇直上"), 6: ("超脱极速诀", "超脱极速")},
    "team": {1: ("轻身术", "轻身疾行"), 2: ("御风诀", "御风而行"), 3: ("踏云步法", "踏云追月"),
             4: ("凌空虚渡功", "凌空虚渡"), 5: ("扶摇直上术", "扶摇直上"), 6: ("超脱极速诀", "超脱极速")}
}
SPEED_PCT = {1: 30, 2: 35, 3: 40, 4: 50, 5: 60, 6: 75}

# Category 18: Damage Link
LINK_NAMES = {
    "enemy": {1: ("因果报应术", "因果报应"), 2: ("同伤咒", "同伤共损"), 3: ("魂链术", "灵魂锁链"),
              4: ("命运诅咒法", "命运诅咒"), 5: ("大道因果诀", "大道因果"), 6: ("天道反噬功", "天道反噬")}
}
LINK_PCT = {1: 30, 2: 35, 3: 40, 4: 45, 5: 55, 6: 70}

# ========== CD Values ==========
def cd_self(rarity):
    if rarity <= 4: return 5
    return 4

def cd_single(rarity):
    return 3

def cd_team(rarity):
    if rarity <= 2: return 6
    if rarity <= 4: return 5
    return 4

def cd_enemy(rarity):
    if rarity <= 4: return 5
    return 4

def cd_all_enemies(rarity):
    if rarity <= 2: return 6
    if rarity <= 4: return 5
    return 4

def dur_self_buff(rarity):
    return 3

def dur_single_buff(rarity):
    return 2

def dur_team_buff(rarity):
    return 3

# ========== Generate All Manuals ==========
all_manuals = []

# --- Category 1: Taunt (S-tier, DEFENSE type, enemy target) ---
for r in range(1, 7):
    name, skill = TAUNT_NAMES["enemy"][r]
    entry = {
        "id": f"new_taunt_{r}_enemy",
        "name": name, "type": "DEFENSE", "rarity": r,
        "description": f"嘲讽敌人强制攻击自己，持续{r if r<5 else 2}回合",
        "stats": {"hp": stat_val(r, "hp", "S")},
        "skillName": skill, "skillDescription": f"施放{skill}，强制敌方下次攻击目标为自己",
        "skillType": "support", "skillDamageType": "physical",
        "skillHits": 1, "skillDamageMultiplier": 0.0,
        "skillCooldown": cd_enemy(r), "skillMpCost": mp_cost(r, "enemy"),
        "skillHealPercent": 0.0, "skillHealFixed": 0, "skillHealType": "hp",
        "skillBuffType": "taunt", "skillBuffValue": 1.0,
        "skillBuffDuration": 2 if r >= 5 else 1,
        "skillBuffs": [], "skillIsAoe": False, "skillTargetScope": "enemy",
        "skillShieldPercent": 0.0, "skillTurnAdvancePercent": 0.0,
        "skillDamageSharePercent": 0.0, "skillDamageLinkPercent": 0.0,
        "price": PRICE_BASE[r], "minRealm": {1:9,2:7,3:6,4:5,5:4,6:2}[r]
    }
    all_manuals.append(entry)

# --- Category 2: Stun (S-tier, ATTACK type, enemy target) ---
for r in range(1, 7):
    name, skill = STUN_NAMES["enemy"][r]
    entry = {
        "id": f"new_stun_{r}_enemy",
        "name": name, "type": "ATTACK", "rarity": r,
        "description": f"眩晕敌人跳过一回合",
        "stats": {"speed": stat_val(r, "spd", "S")},
        "skillName": skill, "skillDescription": f"施放{skill}，使敌方跳过一回合",
        "skillType": "attack", "skillDamageType": "physical",
        "skillHits": 1, "skillDamageMultiplier": 0.0,
        "skillCooldown": cd_enemy(r), "skillMpCost": mp_cost(r, "enemy"),
        "skillHealPercent": 0.0, "skillHealFixed": 0, "skillHealType": "hp",
        "skillBuffType": "stun", "skillBuffValue": 1.0,
        "skillBuffDuration": 1,
        "skillBuffs": [], "skillIsAoe": False, "skillTargetScope": "enemy",
        "skillShieldPercent": 0.0, "skillTurnAdvancePercent": 0.0,
        "skillDamageSharePercent": 0.0, "skillDamageLinkPercent": 0.0,
        "price": PRICE_BASE[r], "minRealm": {1:9,2:7,3:6,4:5,5:4,6:2}[r]
    }
    all_manuals.append(entry)

# --- Category 3: HP Heal (B-tier, SUPPORT type) ---
for r in range(1, 7):
    # Percentage self
    name, skill = HP_HEAL_PCT_NAMES["self"][r]
    all_manuals.append({
        "id": f"new_hp_heal_pct_self_{r}",
        "name": name, "type": "SUPPORT", "rarity": r,
        "description": f"回复自身{HP_HEAL_PCT[r]}%血量",
        "stats": {"hp": stat_val(r, "hp", "B")},
        "skillName": skill, "skillDescription": f"施放{skill}，回复自身最大血量{HP_HEAL_PCT[r]}%",
        "skillType": "support", "skillDamageType": "physical",
        "skillHits": 1, "skillDamageMultiplier": 0.0,
        "skillCooldown": 3, "skillMpCost": mp_cost(r, "self"),
        "skillHealPercent": HP_HEAL_PCT[r] / 100.0, "skillHealFixed": 0, "skillHealType": "hp",
        "skillBuffType": None, "skillBuffValue": 0.0, "skillBuffDuration": 0,
        "skillBuffs": [], "skillIsAoe": False, "skillTargetScope": "self",
        "skillShieldPercent": 0.0, "skillTurnAdvancePercent": 0.0,
        "skillDamageSharePercent": 0.0, "skillDamageLinkPercent": 0.0,
        "price": PRICE_BASE[r], "minRealm": {1:9,2:7,3:6,4:5,5:4,6:2}[r]
    })
    # Fixed self
    name, skill = HP_HEAL_FIX_NAMES["self"][r]
    all_manuals.append({
        "id": f"new_hp_heal_fix_self_{r}",
        "name": name, "type": "SUPPORT", "rarity": r,
        "description": f"回复自身{HP_HEAL_FIX_SELF[r]}点血量",
        "stats": {"hp": stat_val(r, "hp", "B")},
        "skillName": skill, "skillDescription": f"施放{skill}，回复自身{HP_HEAL_FIX_SELF[r]}点血量",
        "skillType": "support", "skillDamageType": "physical",
        "skillHits": 1, "skillDamageMultiplier": 0.0,
        "skillCooldown": 3, "skillMpCost": mp_cost(r, "self"),
        "skillHealPercent": 0.0, "skillHealFixed": HP_HEAL_FIX_SELF[r], "skillHealType": "hp",
        "skillBuffType": None, "skillBuffValue": 0.0, "skillBuffDuration": 0,
        "skillBuffs": [], "skillIsAoe": False, "skillTargetScope": "self",
        "skillShieldPercent": 0.0, "skillTurnAdvancePercent": 0.0,
        "skillDamageSharePercent": 0.0, "skillDamageLinkPercent": 0.0,
        "price": PRICE_BASE[r], "minRealm": {1:9,2:7,3:6,4:5,5:4,6:2}[r]
    })
    # Percentage single
    name, skill = HP_HEAL_PCT_NAMES["single"][r]
    all_manuals.append({
        "id": f"new_hp_heal_pct_single_{r}",
        "name": name, "type": "SUPPORT", "rarity": r,
        "description": f"回复一名队友{HP_HEAL_PCT[r]}%血量",
        "stats": {"hp": stat_val(r, "hp", "B")},
        "skillName": skill, "skillDescription": f"施放{skill}，回复一名队友最大血量{HP_HEAL_PCT[r]}%",
        "skillType": "support", "skillDamageType": "physical",
        "skillHits": 1, "skillDamageMultiplier": 0.0,
        "skillCooldown": 2, "skillMpCost": mp_cost(r, "single"),
        "skillHealPercent": HP_HEAL_PCT[r] / 100.0, "skillHealFixed": 0, "skillHealType": "hp",
        "skillBuffType": None, "skillBuffValue": 0.0, "skillBuffDuration": 0,
        "skillBuffs": [], "skillIsAoe": False, "skillTargetScope": "ally",
        "skillShieldPercent": 0.0, "skillTurnAdvancePercent": 0.0,
        "skillDamageSharePercent": 0.0, "skillDamageLinkPercent": 0.0,
        "price": PRICE_BASE[r], "minRealm": {1:9,2:7,3:6,4:5,5:4,6:2}[r]
    })
    # Fixed single
    name, skill = HP_HEAL_FIX_NAMES["single"][r]
    all_manuals.append({
        "id": f"new_hp_heal_fix_single_{r}",
        "name": name, "type": "SUPPORT", "rarity": r,
        "description": f"回复一名队友{HP_HEAL_FIX_SELF[r]}点血量",
        "stats": {"hp": stat_val(r, "hp", "B")},
        "skillName": skill, "skillDescription": f"施放{skill}，回复一名队友{HP_HEAL_FIX_SELF[r]}点血量",
        "skillType": "support", "skillDamageType": "physical",
        "skillHits": 1, "skillDamageMultiplier": 0.0,
        "skillCooldown": 2, "skillMpCost": mp_cost(r, "single"),
        "skillHealPercent": 0.0, "skillHealFixed": HP_HEAL_FIX_SELF[r], "skillHealType": "hp",
        "skillBuffType": None, "skillBuffValue": 0.0, "skillBuffDuration": 0,
        "skillBuffs": [], "skillIsAoe": False, "skillTargetScope": "ally",
        "skillShieldPercent": 0.0, "skillTurnAdvancePercent": 0.0,
        "skillDamageSharePercent": 0.0, "skillDamageLinkPercent": 0.0,
        "price": PRICE_BASE[r], "minRealm": {1:9,2:7,3:6,4:5,5:4,6:2}[r]
    })
    # Percentage team
    name, skill = HP_HEAL_PCT_NAMES["team"][r]
    team_pct = round(HP_HEAL_PCT[r] * 0.4)
    all_manuals.append({
        "id": f"new_hp_heal_pct_team_{r}",
        "name": name, "type": "SUPPORT", "rarity": r,
        "description": f"回复全体队友每人{team_pct}%血量",
        "stats": {"hp": stat_val(r, "hp", "B", team=True)},
        "skillName": skill, "skillDescription": f"施放{skill}，回复全体队友每人最大血量{team_pct}%",
        "skillType": "support", "skillDamageType": "physical",
        "skillHits": 1, "skillDamageMultiplier": 0.0,
        "skillCooldown": 5 if r <= 2 else 4, "skillMpCost": mp_cost(r, "team"),
        "skillHealPercent": team_pct / 100.0, "skillHealFixed": 0, "skillHealType": "hp",
        "skillBuffType": None, "skillBuffValue": 0.0, "skillBuffDuration": 0,
        "skillBuffs": [], "skillIsAoe": True, "skillTargetScope": "team",
        "skillShieldPercent": 0.0, "skillTurnAdvancePercent": 0.0,
        "skillDamageSharePercent": 0.0, "skillDamageLinkPercent": 0.0,
        "price": PRICE_BASE[r], "minRealm": {1:9,2:7,3:6,4:5,5:4,6:2}[r]
    })
    # Fixed team
    name, skill = HP_HEAL_FIX_NAMES["team"][r]
    team_fix = round(HP_HEAL_FIX_SELF[r] * 0.4)
    all_manuals.append({
        "id": f"new_hp_heal_fix_team_{r}",
        "name": name, "type": "SUPPORT", "rarity": r,
        "description": f"回复全体队友每人{team_fix}点血量",
        "stats": {"hp": stat_val(r, "hp", "B", team=True)},
        "skillName": skill, "skillDescription": f"施放{skill}，回复全体队友每人{team_fix}点血量",
        "skillType": "support", "skillDamageType": "physical",
        "skillHits": 1, "skillDamageMultiplier": 0.0,
        "skillCooldown": 5 if r <= 2 else 4, "skillMpCost": mp_cost(r, "team"),
        "skillHealPercent": 0.0, "skillHealFixed": team_fix, "skillHealType": "hp",
        "skillBuffType": None, "skillBuffValue": 0.0, "skillBuffDuration": 0,
        "skillBuffs": [], "skillIsAoe": True, "skillTargetScope": "team",
        "skillShieldPercent": 0.0, "skillTurnAdvancePercent": 0.0,
        "skillDamageSharePercent": 0.0, "skillDamageLinkPercent": 0.0,
        "price": PRICE_BASE[r], "minRealm": {1:9,2:7,3:6,4:5,5:4,6:2}[r]
    })

# --- Category 4: MP Heal (B-tier, SUPPORT type) ---
for r in range(1, 7):
    # Percentage self
    name, skill = MP_HEAL_PCT_NAMES["self"][r]
    all_manuals.append({
        "id": f"new_mp_heal_pct_self_{r}",
        "name": name, "type": "SUPPORT", "rarity": r,
        "description": f"回复自身{MP_HEAL_PCT[r]}%灵力",
        "stats": {"mp": stat_val(r, "mp", "B")},
        "skillName": skill, "skillDescription": f"施放{skill}，回复自身最大灵力{MP_HEAL_PCT[r]}%",
        "skillType": "support", "skillDamageType": "physical",
        "skillHits": 1, "skillDamageMultiplier": 0.0,
        "skillCooldown": 3, "skillMpCost": mp_cost(r, "self"),
        "skillHealPercent": MP_HEAL_PCT[r] / 100.0, "skillHealFixed": 0, "skillHealType": "mp",
        "skillBuffType": None, "skillBuffValue": 0.0, "skillBuffDuration": 0,
        "skillBuffs": [], "skillIsAoe": False, "skillTargetScope": "self",
        "skillShieldPercent": 0.0, "skillTurnAdvancePercent": 0.0,
        "skillDamageSharePercent": 0.0, "skillDamageLinkPercent": 0.0,
        "price": PRICE_BASE[r], "minRealm": {1:9,2:7,3:6,4:5,5:4,6:2}[r]
    })
    # Fixed self
    name, skill = MP_HEAL_FIX_NAMES["self"][r]
    all_manuals.append({
        "id": f"new_mp_heal_fix_self_{r}",
        "name": name, "type": "SUPPORT", "rarity": r,
        "description": f"回复自身{MP_HEAL_FIX_SELF[r]}点灵力",
        "stats": {"mp": stat_val(r, "mp", "B")},
        "skillName": skill, "skillDescription": f"施放{skill}，回复自身{MP_HEAL_FIX_SELF[r]}点灵力",
        "skillType": "support", "skillDamageType": "physical",
        "skillHits": 1, "skillDamageMultiplier": 0.0,
        "skillCooldown": 3, "skillMpCost": mp_cost(r, "self"),
        "skillHealPercent": 0.0, "skillHealFixed": MP_HEAL_FIX_SELF[r], "skillHealType": "mp",
        "skillBuffType": None, "skillBuffValue": 0.0, "skillBuffDuration": 0,
        "skillBuffs": [], "skillIsAoe": False, "skillTargetScope": "self",
        "skillShieldPercent": 0.0, "skillTurnAdvancePercent": 0.0,
        "skillDamageSharePercent": 0.0, "skillDamageLinkPercent": 0.0,
        "price": PRICE_BASE[r], "minRealm": {1:9,2:7,3:6,4:5,5:4,6:2}[r]
    })
    # Percentage single
    name, skill = MP_HEAL_PCT_NAMES["single"][r]
    all_manuals.append({
        "id": f"new_mp_heal_pct_single_{r}",
        "name": name, "type": "SUPPORT", "rarity": r,
        "description": f"回复一名队友{MP_HEAL_PCT[r]}%灵力",
        "stats": {"mp": stat_val(r, "mp", "B")},
        "skillName": skill, "skillDescription": f"施放{skill}，回复一名队友最大灵力{MP_HEAL_PCT[r]}%",
        "skillType": "support", "skillDamageType": "physical",
        "skillHits": 1, "skillDamageMultiplier": 0.0,
        "skillCooldown": 2, "skillMpCost": mp_cost(r, "single"),
        "skillHealPercent": MP_HEAL_PCT[r] / 100.0, "skillHealFixed": 0, "skillHealType": "mp",
        "skillBuffType": None, "skillBuffValue": 0.0, "skillBuffDuration": 0,
        "skillBuffs": [], "skillIsAoe": False, "skillTargetScope": "ally",
        "skillShieldPercent": 0.0, "skillTurnAdvancePercent": 0.0,
        "skillDamageSharePercent": 0.0, "skillDamageLinkPercent": 0.0,
        "price": PRICE_BASE[r], "minRealm": {1:9,2:7,3:6,4:5,5:4,6:2}[r]
    })
    # Fixed single
    name, skill = MP_HEAL_FIX_NAMES["single"][r]
    all_manuals.append({
        "id": f"new_mp_heal_fix_single_{r}",
        "name": name, "type": "SUPPORT", "rarity": r,
        "description": f"回复一名队友{MP_HEAL_FIX_SELF[r]}点灵力",
        "stats": {"mp": stat_val(r, "mp", "B")},
        "skillName": skill, "skillDescription": f"施放{skill}，回复一名队友{MP_HEAL_FIX_SELF[r]}点灵力",
        "skillType": "support", "skillDamageType": "physical",
        "skillHits": 1, "skillDamageMultiplier": 0.0,
        "skillCooldown": 2, "skillMpCost": mp_cost(r, "single"),
        "skillHealPercent": 0.0, "skillHealFixed": MP_HEAL_FIX_SELF[r], "skillHealType": "mp",
        "skillBuffType": None, "skillBuffValue": 0.0, "skillBuffDuration": 0,
        "skillBuffs": [], "skillIsAoe": False, "skillTargetScope": "ally",
        "skillShieldPercent": 0.0, "skillTurnAdvancePercent": 0.0,
        "skillDamageSharePercent": 0.0, "skillDamageLinkPercent": 0.0,
        "price": PRICE_BASE[r], "minRealm": {1:9,2:7,3:6,4:5,5:4,6:2}[r]
    })
    # Percentage team
    name, skill = MP_HEAL_PCT_NAMES["team"][r]
    team_pct = round(MP_HEAL_PCT[r] * 0.4)
    all_manuals.append({
        "id": f"new_mp_heal_pct_team_{r}",
        "name": name, "type": "SUPPORT", "rarity": r,
        "description": f"回复全体队友每人{team_pct}%灵力",
        "stats": {"mp": stat_val(r, "mp", "B", team=True)},
        "skillName": skill, "skillDescription": f"施放{skill}，回复全体队友每人最大灵力{team_pct}%",
        "skillType": "support", "skillDamageType": "physical",
        "skillHits": 1, "skillDamageMultiplier": 0.0,
        "skillCooldown": 5 if r <= 2 else 4, "skillMpCost": mp_cost(r, "team"),
        "skillHealPercent": team_pct / 100.0, "skillHealFixed": 0, "skillHealType": "mp",
        "skillBuffType": None, "skillBuffValue": 0.0, "skillBuffDuration": 0,
        "skillBuffs": [], "skillIsAoe": True, "skillTargetScope": "team",
        "skillShieldPercent": 0.0, "skillTurnAdvancePercent": 0.0,
        "skillDamageSharePercent": 0.0, "skillDamageLinkPercent": 0.0,
        "price": PRICE_BASE[r], "minRealm": {1:9,2:7,3:6,4:5,5:4,6:2}[r]
    })
    # Fixed team
    name, skill = MP_HEAL_FIX_NAMES["team"][r]
    team_fix = round(MP_HEAL_FIX_SELF[r] * 0.4)
    all_manuals.append({
        "id": f"new_mp_heal_fix_team_{r}",
        "name": name, "type": "SUPPORT", "rarity": r,
        "description": f"回复全体队友每人{team_fix}点灵力",
        "stats": {"mp": stat_val(r, "mp", "B", team=True)},
        "skillName": skill, "skillDescription": f"施放{skill}，回复全体队友每人{team_fix}点灵力",
        "skillType": "support", "skillDamageType": "physical",
        "skillHits": 1, "skillDamageMultiplier": 0.0,
        "skillCooldown": 5 if r <= 2 else 4, "skillMpCost": mp_cost(r, "team"),
        "skillHealPercent": 0.0, "skillHealFixed": team_fix, "skillHealType": "mp",
        "skillBuffType": None, "skillBuffValue": 0.0, "skillBuffDuration": 0,
        "skillBuffs": [], "skillIsAoe": True, "skillTargetScope": "team",
        "skillShieldPercent": 0.0, "skillTurnAdvancePercent": 0.0,
        "skillDamageSharePercent": 0.0, "skillDamageLinkPercent": 0.0,
        "price": PRICE_BASE[r], "minRealm": {1:9,2:7,3:6,4:5,5:4,6:2}[r]
    })

# --- Category 5: Physical Attack (C-tier, ATTACK) ---
for r in range(1, 7):
    name, skill = PHYS_ATK_NAMES["enemy"][r]
    all_manuals.append({
        "id": f"new_phys_atk_single_{r}",
        "name": name, "type": "ATTACK", "rarity": r,
        "description": f"单体物理攻击，倍率{PHYS_ATK_MULT[r]}x",
        "stats": {"physicalAttack": stat_val(r, "pa", "C")},
        "skillName": skill, "skillDescription": f"施放{skill}，对单个敌人造成{PHYS_ATK_MULT[r]}倍物理伤害",
        "skillType": "attack", "skillDamageType": "physical",
        "skillHits": 1, "skillDamageMultiplier": PHYS_ATK_MULT[r],
        "skillCooldown": 3, "skillMpCost": mp_cost(r, "enemy"),
        "skillHealPercent": 0.0, "skillHealFixed": 0, "skillHealType": "hp",
        "skillBuffType": None, "skillBuffValue": 0.0, "skillBuffDuration": 0,
        "skillBuffs": [], "skillIsAoe": False, "skillTargetScope": "enemy",
        "skillShieldPercent": 0.0, "skillTurnAdvancePercent": 0.0,
        "skillDamageSharePercent": 0.0, "skillDamageLinkPercent": 0.0,
        "price": PRICE_BASE[r], "minRealm": {1:9,2:7,3:6,4:5,5:4,6:2}[r]
    })
    # AoE
    name, skill = PHYS_ATK_NAMES["all_enemies"][r]
    aoe_mult = round(PHYS_ATK_MULT[r] * 0.4, 1)
    all_manuals.append({
        "id": f"new_phys_atk_aoe_{r}",
        "name": name, "type": "ATTACK", "rarity": r,
        "description": f"群体物理攻击，每人{aoe_mult}x",
        "stats": {"physicalAttack": stat_val(r, "pa", "C")},
        "skillName": skill, "skillDescription": f"施放{skill}，对全体敌人造成{aoe_mult}倍物理伤害",
        "skillType": "attack", "skillDamageType": "physical",
        "skillHits": 1, "skillDamageMultiplier": aoe_mult,
        "skillCooldown": 3, "skillMpCost": mp_cost(r, "all_enemies"),
        "skillHealPercent": 0.0, "skillHealFixed": 0, "skillHealType": "hp",
        "skillBuffType": None, "skillBuffValue": 0.0, "skillBuffDuration": 0,
        "skillBuffs": [], "skillIsAoe": True, "skillTargetScope": "enemy",
        "skillShieldPercent": 0.0, "skillTurnAdvancePercent": 0.0,
        "skillDamageSharePercent": 0.0, "skillDamageLinkPercent": 0.0,
        "price": PRICE_BASE[r], "minRealm": {1:9,2:7,3:6,4:5,5:4,6:2}[r]
    })

# --- Category 6: Magic Attack (C-tier, ATTACK) ---
for r in range(1, 7):
    name, skill = MAG_ATK_NAMES["enemy"][r]
    all_manuals.append({
        "id": f"new_mag_atk_single_{r}",
        "name": name, "type": "ATTACK", "rarity": r,
        "description": f"单体法术攻击，倍率{PHYS_ATK_MULT[r]}x",
        "stats": {"magicAttack": stat_val(r, "ma", "C")},
        "skillName": skill, "skillDescription": f"施放{skill}，对单个敌人造成{PHYS_ATK_MULT[r]}倍法术伤害",
        "skillType": "attack", "skillDamageType": "magic",
        "skillHits": 1, "skillDamageMultiplier": PHYS_ATK_MULT[r],
        "skillCooldown": 3, "skillMpCost": mp_cost(r, "enemy"),
        "skillHealPercent": 0.0, "skillHealFixed": 0, "skillHealType": "hp",
        "skillBuffType": None, "skillBuffValue": 0.0, "skillBuffDuration": 0,
        "skillBuffs": [], "skillIsAoe": False, "skillTargetScope": "enemy",
        "skillShieldPercent": 0.0, "skillTurnAdvancePercent": 0.0,
        "skillDamageSharePercent": 0.0, "skillDamageLinkPercent": 0.0,
        "price": PRICE_BASE[r], "minRealm": {1:9,2:7,3:6,4:5,5:4,6:2}[r]
    })
    # AoE
    name, skill = MAG_ATK_NAMES["all_enemies"][r]
    aoe_mult = round(PHYS_ATK_MULT[r] * 0.4, 1)
    all_manuals.append({
        "id": f"new_mag_atk_aoe_{r}",
        "name": name, "type": "ATTACK", "rarity": r,
        "description": f"群体法术攻击，每人{aoe_mult}x",
        "stats": {"magicAttack": stat_val(r, "ma", "C")},
        "skillName": skill, "skillDescription": f"施放{skill}，对全体敌人造成{aoe_mult}倍法术伤害",
        "skillType": "attack", "skillDamageType": "magic",
        "skillHits": 1, "skillDamageMultiplier": aoe_mult,
        "skillCooldown": 3, "skillMpCost": mp_cost(r, "all_enemies"),
        "skillHealPercent": 0.0, "skillHealFixed": 0, "skillHealType": "hp",
        "skillBuffType": None, "skillBuffValue": 0.0, "skillBuffDuration": 0,
        "skillBuffs": [], "skillIsAoe": True, "skillTargetScope": "enemy",
        "skillShieldPercent": 0.0, "skillTurnAdvancePercent": 0.0,
        "skillDamageSharePercent": 0.0, "skillDamageLinkPercent": 0.0,
        "price": PRICE_BASE[r], "minRealm": {1:9,2:7,3:6,4:5,5:4,6:2}[r]
    })

# Helper for buff-type manuals (categories 7-17)
def make_buff_manual(cat_id, cat_name, manual_type, tier, r, scope, names_map, pct_map, buff_type_key):
    name, skill = names_map[scope][r]
    is_aoe = scope == "team"
    target = "team" if scope == "team" else ("ally" if scope == "single" else "self")
    pct = pct_map[r]
    if scope == "team":
        pct = round(pct * 0.4)
    team_stat = scope == "team"

    stats_key = {"pd": "physicalDefense", "md": "magicDefense", "spd": "speed",
                 "pa": "physicalAttack", "ma": "magicAttack", "hp": "hp", "mp": "mp"}

    if cat_id in ("phys_def", "mag_def"):
        stat_key = "pd" if cat_id == "phys_def" else "md"
        scoped_key = stats_key[stat_key]
        stats = {scoped_key: stat_val(r, stat_key, tier, team=team_stat)}
    elif cat_id == "dmg_boost":
        stats = {"physicalAttack": stat_val(r, "pa", tier, team=team_stat),
                 "magicAttack": stat_val(r, "ma", tier, team=team_stat)}
    elif cat_id == "crit_boost":
        stats = {"speed": stat_val(r, "spd", tier, team=team_stat)}
    elif cat_id == "dmg_reduction":
        stats = {"hp": stat_val(r, "hp", tier, team=team_stat),
                 "physicalDefense": stat_val(r, "pd", tier, team=team_stat)}
    elif cat_id == "shield":
        stats = {"hp": stat_val(r, "hp", tier, team=team_stat)}
    elif cat_id == "speed_boost":
        stats = {"speed": stat_val(r, "spd", tier, team=team_stat)}
    else:
        stats = {}

    cd = cd_self(r) if scope == "self" else (cd_single(r) if scope in ("single", "ally") else cd_team(r))
    dur = dur_self_buff(r) if scope == "self" else (dur_single_buff(r) if scope in ("single", "ally") else dur_team_buff(r))

    return {
        "id": f"new_{cat_id}_{r}_{scope}",
        "name": name, "type": manual_type, "rarity": r,
        "description": f"{'全体' if is_aoe else '单体' if scope=='single' else '自身'}{cat_name}提升{pct}%",
        "stats": stats,
        "skillName": skill, "skillDescription": f"施放{skill}，{'全体队友' if is_aoe else '一名队友' if scope=='single' else '自身'}{cat_name}提升{pct}%，持续{dur}回合",
        "skillType": "support", "skillDamageType": "physical",
        "skillHits": 1, "skillDamageMultiplier": 0.0,
        "skillCooldown": cd, "skillMpCost": mp_cost(r, scope),
        "skillHealPercent": 0.0, "skillHealFixed": 0, "skillHealType": "hp",
        "skillBuffType": buff_type_key, "skillBuffValue": pct / 100.0,
        "skillBuffDuration": dur,
        "skillBuffs": [], "skillIsAoe": is_aoe, "skillTargetScope": target,
        "skillShieldPercent": 0.0, "skillTurnAdvancePercent": 0.0,
        "skillDamageSharePercent": 0.0, "skillDamageLinkPercent": 0.0,
        "price": PRICE_BASE[r], "minRealm": {1:9,2:7,3:6,4:5,5:4,6:2}[r]
    }

# --- Category 7: Phys Def (B-tier, DEFENSE) ---
for r in range(1, 7):
    for scope in ["self", "single", "team"]:
        all_manuals.append(make_buff_manual("phys_def", "物防", "DEFENSE", "B", r, scope, PHYS_DEF_NAMES, PHYS_DEF_PCT, "physical_defense"))

# --- Category 8: Magic Def (B-tier, DEFENSE) ---
for r in range(1, 7):
    for scope in ["self", "single", "team"]:
        all_manuals.append(make_buff_manual("mag_def", "法防", "DEFENSE", "B", r, scope, MAG_DEF_NAMES, PHYS_DEF_PCT, "magic_defense"))

# --- Category 9: Damage Boost (A-tier, SUPPORT) ---
for r in range(1, 7):
    for scope in ["self", "single", "team"]:
        all_manuals.append(make_buff_manual("dmg_boost", "伤害加成", "SUPPORT", "A", r, scope, DMG_BOOST_NAMES, DMG_BOOST_PCT, "damage_boost"))

# --- Category 10: Crit Boost (C-tier, SUPPORT) ---
for r in range(1, 7):
    for scope in ["self", "single", "team"]:
        crit_entry = make_buff_manual("crit_boost", "暴击率", "SUPPORT", "C", r, scope, CRIT_NAMES, CRIT_VAL, "crit_rate")
        crit_entry["skillBuffValue"] = CRIT_VAL[r] / 100.0
        if scope == "team":
            team_crit = round(CRIT_VAL[r] * 0.4)
            crit_entry["skillBuffValue"] = team_crit / 100.0
        all_manuals.append(crit_entry)

# --- Category 11: Damage Reduction (A-tier, DEFENSE) ---
for r in range(1, 7):
    for scope in ["self", "single", "team"]:
        all_manuals.append(make_buff_manual("dmg_reduction", "伤害减免", "DEFENSE", "A", r, scope, DMG_RED_NAMES, DMG_RED_PCT, "damage_reduction"))

# --- Category 12: Attack Reduce (A-tier, ATTACK, debuff on enemy) ---
for r in range(1, 7):
    for scope in ["enemy", "all_enemies"]:
        name, skill = ATK_RED_NAMES[scope][r]
        is_aoe = scope == "all_enemies"
        pct = ATK_RED_PCT[r]
        if is_aoe:
            pct = round(pct * 0.4)
        cd = cd_enemy(r) if scope == "enemy" else cd_all_enemies(r)
        all_manuals.append({
            "id": f"new_atk_reduce_{r}_{scope}",
            "name": name, "type": "ATTACK", "rarity": r,
            "description": f"{'全体' if is_aoe else '单体'}减攻{pct}%",
            "stats": {"physicalAttack": stat_val(r, "pa", "A")},
            "skillName": skill, "skillDescription": f"施放{skill}，{'全体敌人' if is_aoe else '敌人'}物攻和法攻降低{pct}%，持续3回合",
            "skillType": "attack", "skillDamageType": "physical",
            "skillHits": 1, "skillDamageMultiplier": 0.0,
            "skillCooldown": cd, "skillMpCost": mp_cost(r, scope),
            "skillHealPercent": 0.0, "skillHealFixed": 0, "skillHealType": "hp",
            "skillBuffType": None, "skillBuffValue": 0.0, "skillBuffDuration": 0,
            "skillBuffs": [
                {"type": "physical_attack_reduce", "value": pct / 100.0, "duration": 3},
                {"type": "magic_attack_reduce", "value": pct / 100.0, "duration": 3}
            ],
            "skillIsAoe": is_aoe, "skillTargetScope": "enemy",
            "skillShieldPercent": 0.0, "skillTurnAdvancePercent": 0.0,
            "skillDamageSharePercent": 0.0, "skillDamageLinkPercent": 0.0,
            "price": PRICE_BASE[r], "minRealm": {1:9,2:7,3:6,4:5,5:4,6:2}[r]
        })

# --- Category 13: Def Reduce (A-tier, ATTACK, debuff on enemy) ---
for r in range(1, 7):
    for scope in ["enemy", "all_enemies"]:
        name, skill = DEF_RED_NAMES[scope][r]
        is_aoe = scope == "all_enemies"
        pct = ATK_RED_PCT[r]  # same values as atk reduce
        if is_aoe:
            pct = round(pct * 0.4)
        cd = cd_enemy(r) if scope == "enemy" else cd_all_enemies(r)
        all_manuals.append({
            "id": f"new_def_reduce_{r}_{scope}",
            "name": name, "type": "ATTACK", "rarity": r,
            "description": f"{'全体' if is_aoe else '单体'}减防{pct}%",
            "stats": {"magicAttack": stat_val(r, "ma", "A")},
            "skillName": skill, "skillDescription": f"施放{skill}，{'全体敌人' if is_aoe else '敌人'}物防和法防降低{pct}%，持续3回合",
            "skillType": "attack", "skillDamageType": "magic",
            "skillHits": 1, "skillDamageMultiplier": 0.0,
            "skillCooldown": cd, "skillMpCost": mp_cost(r, scope),
            "skillHealPercent": 0.0, "skillHealFixed": 0, "skillHealType": "hp",
            "skillBuffType": None, "skillBuffValue": 0.0, "skillBuffDuration": 0,
            "skillBuffs": [
                {"type": "physical_defense_reduce", "value": pct / 100.0, "duration": 3},
                {"type": "magic_defense_reduce", "value": pct / 100.0, "duration": 3}
            ],
            "skillIsAoe": is_aoe, "skillTargetScope": "enemy",
            "skillShieldPercent": 0.0, "skillTurnAdvancePercent": 0.0,
            "skillDamageSharePercent": 0.0, "skillDamageLinkPercent": 0.0,
            "price": PRICE_BASE[r], "minRealm": {1:9,2:7,3:6,4:5,5:4,6:2}[r]
        })

# --- Category 14: Shield (S-tier, DEFENSE) ---
for r in range(1, 7):
    for scope in ["self", "single", "team"]:
        name, skill = SHIELD_NAMES[scope][r]
        is_aoe = scope == "team"
        target = "team" if is_aoe else ("ally" if scope == "single" else "self")
        pct = SHIELD_PCT[r]
        if is_aoe:
            pct = round(pct * 0.4)
        cd = cd_self(r) if scope == "self" else (cd_single(r) if scope == "single" else cd_team(r))
        dur = dur_self_buff(r) if scope == "self" else (dur_single_buff(r) if scope == "single" else dur_team_buff(r))
        all_manuals.append({
            "id": f"new_shield_{r}_{scope}",
            "name": name, "type": "DEFENSE", "rarity": r,
            "description": f"{'全体' if is_aoe else '单体' if scope=='single' else '自身'}护盾{pct}%最大HP",
            "stats": {"hp": stat_val(r, "hp", "S", team=is_aoe)},
            "skillName": skill, "skillDescription": f"施放{skill}，为{'全体队友' if is_aoe else '一名队友' if scope=='single' else '自身'}附加{pct}%最大HP护盾，持续{dur}回合",
            "skillType": "support", "skillDamageType": "physical",
            "skillHits": 1, "skillDamageMultiplier": 0.0,
            "skillCooldown": cd, "skillMpCost": mp_cost(r, scope),
            "skillHealPercent": 0.0, "skillHealFixed": 0, "skillHealType": "hp",
            "skillBuffType": "shield", "skillBuffValue": pct / 100.0, "skillBuffDuration": dur,
            "skillBuffs": [], "skillIsAoe": is_aoe, "skillTargetScope": target,
            "skillShieldPercent": pct / 100.0, "skillTurnAdvancePercent": 0.0,
            "skillDamageSharePercent": 0.0, "skillDamageLinkPercent": 0.0,
            "price": PRICE_BASE[r], "minRealm": {1:9,2:7,3:6,4:5,5:4,6:2}[r]
        })

# --- Category 15: Turn Advance (S-tier, SUPPORT) ---
for r in range(1, 7):
    name, skill = TURN_ADV_NAMES["ally"][r]
    all_manuals.append({
        "id": f"new_turn_advance_{r}",
        "name": name, "type": "SUPPORT", "rarity": r,
        "description": f"拉条{TURN_ADV_PCT[r]}%",
        "stats": {"speed": stat_val(r, "spd", "S")},
        "skillName": skill, "skillDescription": f"施放{skill}，指定一名队友行动条提前{TURN_ADV_PCT[r]}%",
        "skillType": "support", "skillDamageType": "physical",
        "skillHits": 1, "skillDamageMultiplier": 0.0,
        "skillCooldown": 5 if r <= 2 else (4 if r <= 4 else 3), "skillMpCost": mp_cost(r, "ally"),
        "skillHealPercent": 0.0, "skillHealFixed": 0, "skillHealType": "hp",
        "skillBuffType": "turn_advance", "skillBuffValue": TURN_ADV_PCT[r] / 100.0, "skillBuffDuration": 0,
        "skillBuffs": [], "skillIsAoe": False, "skillTargetScope": "ally",
        "skillShieldPercent": 0.0, "skillTurnAdvancePercent": TURN_ADV_PCT[r] / 100.0,
        "skillDamageSharePercent": 0.0, "skillDamageLinkPercent": 0.0,
        "price": PRICE_BASE[r], "minRealm": {1:9,2:7,3:6,4:5,5:4,6:2}[r]
    })

# --- Category 16: Damage Share (A-tier, DEFENSE) ---
for r in range(1, 7):
    for scope in ["self", "ally"]:
        name, skill = SHARE_NAMES[scope][r]
        share_pct = SHARE_PCT_SELF[r] if scope == "self" else SHARE_PCT_ALLY[r]
        target = "self" if scope == "self" else "ally"
        stats = {"hp": stat_val(r, "hp", "A"), "magicDefense": stat_val(r, "md", "A")}
        all_manuals.append({
            "id": f"new_dmg_share_{r}_{scope}",
            "name": name, "type": "DEFENSE", "rarity": r,
            "description": f"伤害分摊{share_pct}%",
            "stats": stats,
            "skillName": skill, "skillDescription": f"施放{skill}，{'自身' if scope=='self' else '一名队友'}分摊{share_pct}%伤害，持续3回合",
            "skillType": "support", "skillDamageType": "physical",
            "skillHits": 1, "skillDamageMultiplier": 0.0,
            "skillCooldown": 5 if r <= 4 else 4, "skillMpCost": mp_cost(r, scope),
            "skillHealPercent": 0.0, "skillHealFixed": 0, "skillHealType": "hp",
            "skillBuffType": "damage_share", "skillBuffValue": share_pct / 100.0, "skillBuffDuration": 3,
            "skillBuffs": [], "skillIsAoe": False, "skillTargetScope": target,
            "skillShieldPercent": 0.0, "skillTurnAdvancePercent": 0.0,
            "skillDamageSharePercent": share_pct / 100.0, "skillDamageLinkPercent": 0.0,
            "price": PRICE_BASE[r], "minRealm": {1:9,2:7,3:6,4:5,5:4,6:2}[r]
        })

# --- Category 17: Speed Boost (C-tier, SUPPORT) ---
for r in range(1, 7):
    for scope in ["self", "single", "team"]:
        all_manuals.append(make_buff_manual("speed_boost", "速度", "SUPPORT", "C", r, scope, SPEED_NAMES, SPEED_PCT, "speed"))

# --- Category 18: Damage Link (S-tier, ATTACK) ---
for r in range(1, 7):
    name, skill = LINK_NAMES["enemy"][r]
    dur = 2 if r <= 2 else 3
    all_manuals.append({
        "id": f"new_dmg_link_{r}",
        "name": name, "type": "ATTACK", "rarity": r,
        "description": f"伤害链接{LINK_PCT[r]}%传递",
        "stats": {"magicAttack": stat_val(r, "ma", "S")},
        "skillName": skill, "skillDescription": f"施放{skill}，链接一名敌人，自身对其造成伤害时额外传递{LINK_PCT[r]}%真实伤害，持续{dur}回合（同时只能链接一个敌人）",
        "skillType": "attack", "skillDamageType": "magic",
        "skillHits": 1, "skillDamageMultiplier": 0.0,
        "skillCooldown": 5 if r <= 4 else 4, "skillMpCost": mp_cost(r, "enemy"),
        "skillHealPercent": 0.0, "skillHealFixed": 0, "skillHealType": "hp",
        "skillBuffType": "damage_link", "skillBuffValue": LINK_PCT[r] / 100.0, "skillBuffDuration": dur,
        "skillBuffs": [], "skillIsAoe": False, "skillTargetScope": "enemy",
        "skillShieldPercent": 0.0, "skillTurnAdvancePercent": 0.0,
        "skillDamageSharePercent": 0.0, "skillDamageLinkPercent": LINK_PCT[r] / 100.0,
        "price": PRICE_BASE[r], "minRealm": {1:9,2:7,3:6,4:5,5:4,6:2}[r]
    })

# ===== Output =====
print(f"Generated {len(all_manuals)} manuals")

# Sort by type for easier merging
attack_manuals = [m for m in all_manuals if m["type"] == "ATTACK"]
defense_manuals = [m for m in all_manuals if m["type"] == "DEFENSE"]
support_manuals = [m for m in all_manuals if m["type"] == "SUPPORT"]

# Print summary
types_count = {}
for m in all_manuals:
    t = m["type"]
    types_count[t] = types_count.get(t, 0) + 1
for t, c in types_count.items():
    print(f"  {t}: {c}")

rarity_count = {}
for m in all_manuals:
    r = m["rarity"]
    rarity_count[r] = rarity_count.get(r, 0) + 1
for r in sorted(rarity_count):
    print(f"  Rarity {r}: {rarity_count[r]}")

# Output groups for easy merging
print("\n=== ATTACK_MANUALS ===")
print(json.dumps(attack_manuals, ensure_ascii=False, indent=2))
print("\n=== DEFENSE_MANUALS ===")
print(json.dumps(defense_manuals, ensure_ascii=False, indent=2))
print("\n=== SUPPORT_MANUALS ===")
print(json.dumps(support_manuals, ensure_ascii=False, indent=2))
