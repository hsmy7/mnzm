// Generate new manuals and merge into existing manuals.json
const fs = require('fs');
const path = require('path');

// ===== CONFIG =====
const STATS = {
  1: {hp:36, mp:18, pa:9, ma:9, pd:3, md:3, spd:5},
  2: {hp:234, mp:117, pa:63, ma:63, pd:21, md:21, spd:34},
  3: {hp:612, mp:306, pa:164, ma:164, pd:55, md:55, spd:88},
  4: {hp:1620, mp:810, pa:432, ma:432, pd:144, md:144, spd:230},
  5: {hp:3960, mp:1980, pa:1056, ma:1056, pd:351, md:351, spd:562},
  6: {hp:20880, mp:10440, pa:5568, ma:5568, pd:1855, md:1855, spd:2968},
};
const TIER = {S:0.5, A:0.75, B:1.0, C:1.25};
const MP_BASE = {1:25, 2:150, 3:400, 4:1000, 5:2500, 6:12000};
const PRICE = {1:3600, 2:16200, 3:64800, 4:259200, 5:1036800, 6:4147200};
const MIN_REALM = {1:9, 2:7, 3:6, 4:5, 5:4, 6:2};

function sv(r, key, tier, team) {
  let v = Math.round(STATS[r][key] * TIER[tier]);
  if (team) v = Math.round(v * 0.5);
  return v;
}
function mp(r, scope) {
  if (scope === 'team' || scope === 'all_enemies') return MP_BASE[r];
  if (scope === 'single' || scope === 'enemy' || scope === 'ally') return Math.round(MP_BASE[r] * 0.8);
  return Math.round(MP_BASE[r] * 0.6);
}
function cdSelf(r) { return r <= 4 ? 5 : 4; }
function cdSingle(r) { return 3; }
function cdTeam(r) { return r <= 2 ? 6 : r <= 4 ? 5 : 4; }
function cdEnemy(r) { return r <= 4 ? 5 : 4; }
function cdAllEnemy(r) { return r <= 2 ? 6 : r <= 4 ? 5 : 4; }

// ===== NAMES (Flat lookup: key = "cat_scope_rarity") =====
const N = {
  // Cat 1: Taunt (S, DEFENSE)
  taunt_enemy: {1:['镇岳功','镇岳'],2:['挑衅诀','挑衅'],3:['金刚怒目','怒目'],4:['不动明王法','不动镇魔'],5:['万夫莫开诀','万夫莫开'],6:['唯我独尊功','唯我独尊']},
  // Cat 2: Stun (S, ATTACK)
  stun_enemy: {1:['定身诀','定身'],2:['缚魂术','缚魂'],3:['封灵印法','封灵印'],4:['镇魂经','镇魂咒'],5:['天罗地网功','天罗地网'],6:['轮回禁锢诀','轮回禁锢']},
  // Cat 3: HP Heal % (B, SUPPORT)
  hp_pct_self: {1:['回春术','回春'],2:['生生不息功','生生不息'],3:['枯木逢春诀','枯木逢春'],4:['九转还魂术','九转还魂'],5:['不死真凰诀','真凰涅槃'],6:['起死回生术','起死回生']},
  hp_pct_single: {1:['妙手回春诀','妙手回春'],2:['济世诀','济世救人'],3:['大悲咒','大悲愈伤'],4:['涅槃经','涅槃疗伤'],5:['造化疗伤术','造化回春'],6:['大道愈合诀','大道愈合']},
  hp_pct_team: {1:['甘霖诀','甘霖普降'],2:['普济众生术','普济苍生'],3:['春风化雨术','春风化雨'],4:['万物回春经','万物回春'],5:['天地回春功','天地回春'],6:['造化回天诀','回天再造']},
  hp_fix_self: {1:['续命膏方','续命'],2:['血元丹术','血元贯注'],3:['活血化瘀功','活血化瘀'],4:['换血大法','换血重生'],5:['血祭术','血祭回天'],6:['轮回血术','轮回渡血']},
  hp_fix_single: {1:['金创药术','金创止血'],2:['灵枢针法','灵枢刺穴'],3:['九针术','九针续命'],4:['天医神针','天医渡厄'],5:['神农药典','神农百草'],6:['女娲补天术','女娲补天']},
  hp_fix_team: {1:['青囊术','青囊济世'],2:['百草经','百草回春'],3:['药王典','药王赐福'],4:['丹霞圣手','丹霞普救'],5:['菩提甘露','菩提普渡'],6:['普度众生经','普度众生']},
  // Cat 4: MP Heal % (B, SUPPORT)
  mp_pct_self: {1:['聚灵术','聚灵'],2:['纳气归元功','纳气归元'],3:['太阴吐纳诀','太阴吐纳'],4:['鲸吞天地功','鲸吞天地'],5:['吞天纳地诀','吞天纳地'],6:['混沌元灵气','混沌汲灵']},
  mp_pct_single: {1:['还元诀','还元归灵'],2:['引灵诀','引灵渡气'],3:['灵桥引','灵桥接引'],4:['玉清度灵诀','玉清度灵'],5:['混元引灵法','混元引灵'],6:['鸿蒙渡灵术','鸿蒙渡灵']},
  mp_pct_team: {1:['紫气东来术','紫气东来'],2:['天河引灵术','天河引灵'],3:['九天引灵经','九天引灵'],4:['万灵归宗术','万灵归宗'],5:['九天聚灵阵','九天聚灵启'],6:['大道归元诀','大道归元']},
  mp_fix_self: {1:['灵石吐纳法','灵石吐纳'],2:['鲸吞术','鲸吞纳气'],3:['吞天术','吞天纳气'],4:['噬灵术','噬灵夺元'],5:['归墟纳元术','归墟纳元'],6:['创世吞灵功','创世吞灵']},
  mp_fix_single: {1:['传功法','传功渡灵'],2:['灌顶术','灌顶传功'],3:['元神传功诀','元神传功'],4:['醍醐灌顶法','醍醐灌顶'],5:['大道传功诀','大道传功'],6:['天尊灌顶诀','天尊灌顶']},
  mp_fix_team: {1:['聚灵阵诀','聚灵阵启'],2:['八方聚元阵','八方聚元'],3:['星辰聚灵阵','星辰聚灵'],4:['周天引灵阵','周天引灵'],5:['星斗归元阵','星斗归元'],6:['混沌聚灵阵','混沌聚灵']},
  // Cat 5: Phys Atk (C, ATTACK)
  phys_single: {1:['贯石剑法','贯石击'],2:['破岳刀诀','破岳斩'],3:['碎星枪法','碎星刺'],4:['陨日剑典','陨日一击'],5:['屠龙刀经','屠龙斩'],6:['开天辟地功','开天一击']},
  phys_aoe: {1:['横扫六合诀','横扫六合'],2:['万剑归宗诀','万剑齐发'],3:['裂地震天功','裂地震天'],4:['山崩地裂诀','山崩地裂'],5:['天崩地裂功','天崩地裂'],6:['毁天灭地诀','毁天灭地']},
  // Cat 6: Mag Atk (C, ATTACK)
  mag_single: {1:['玄冰术','玄冰刺'],2:['天雷正法','天雷击'],3:['九天玄雷诀','九天玄雷'],4:['紫霄神雷经','紫霄神雷'],5:['太上玄雷术','太上奔雷'],6:['混沌神雷典','混沌神雷']},
  mag_aoe: {1:['火雨术','火雨天降'],2:['冰风暴诀','冰风暴'],3:['焚天烈焰功','焚天烈焰'],4:['炼狱火海诀','炼狱火海'],5:['万劫天火功','万劫天火'],6:['灭世焚天诀','灭世焚天']},
  // Cat 7: Phys Def (B, DEFENSE)
  pd_self: {1:['钢筋铁骨功','钢筋铁骨'],2:['玄铁金身诀','玄铁金身'],3:['金刚不坏功','金刚不坏'],4:['万劫不坏体','万劫不坏'],5:['永恒不灭体','永恒不灭'],6:['混沌不坏身','混沌不坏']},
  pd_single: {1:['护体罡气诀','护体罡气'],2:['金刚护体功','金刚护体'],3:['罗汉金身诀','罗汉金身'],4:['斗战金身功','斗战金身'],5:['金仙金身诀','金仙金身'],6:['圣人金身功','圣人金身']},
  pd_team: {1:['铁壁阵诀','铁壁阵'],2:['金汤壁垒术','金汤壁垒'],3:['铜墙铁壁阵','铜墙铁壁'],4:['天罡战阵诀','天罡战阵'],5:['不动明王阵','明王守护'],6:['周天星斗阵','星斗护体']},
  // Cat 8: Mag Def (B, DEFENSE)
  md_self: {1:['凝神静气功','凝神静气'],2:['元神灵盾功','元神灵盾'],3:['天罡护神功','天罡护神'],4:['元神不灭术','元神不灭'],5:['太虚元神术','太虚元神'],6:['混沌元神诀','混沌元神']},
  md_single: {1:['清心定神诀','清心定神'],2:['太一护神诀','太一护神'],3:['紫府护神诀','紫府护神'],4:['三花护神功','三花护神'],5:['五气朝元诀','五气朝元'],6:['大道护神功','大道护神']},
  md_team: {1:['辟邪结界术','辟邪结界'],2:['镇魔结界术','镇魔结界'],3:['九天辟魔阵','九天辟魔'],4:['万法不侵阵','万法不侵'],5:['大道辟易阵','大道辟易'],6:['混元无极阵','混元无极']},
  // Cat 9: Dmg Boost (A, SUPPORT)
  dmg_self: {1:['破军诀','破军之势'],2:['战意诀','战意激昂'],3:['狂战诀','狂战无双'],4:['嗜血术','嗜血狂袭'],5:['杀神诀','杀神领域'],6:['诛天战意','诛天之势']},
  dmg_single: {1:['破军诀','破军之势'],2:['战意诀','战意激昂'],3:['狂战诀','狂战无双'],4:['嗜血术','嗜血狂袭'],5:['杀神诀','杀神领域'],6:['诛天战意','诛天之势']},
  dmg_team: {1:['破军诀','破军之势'],2:['战意诀','战意激昂'],3:['狂战诀','狂战无双'],4:['嗜血术','嗜血狂袭'],5:['杀神诀','杀神领域'],6:['诛天战意','诛天之势']},
  // Cat 10: Crit (C, SUPPORT)
  crit_self: {1:['鹰眼术','鹰眼凝视'],2:['心眼诀','心眼洞开'],3:['天眼通','天眼洞察'],4:['慧眼识真功','慧眼识真'],5:['洞察天机术','洞察天机'],6:['全知领域诀','全知领域']},
  crit_single: {1:['鹰眼术','鹰眼凝视'],2:['心眼诀','心眼洞开'],3:['天眼通','天眼洞察'],4:['慧眼识真功','慧眼识真'],5:['洞察天机术','洞察天机'],6:['全知领域诀','全知领域']},
  crit_team: {1:['鹰眼术','鹰眼凝视'],2:['心眼诀','心眼洞开'],3:['天眼通','天眼洞察'],4:['慧眼识真功','慧眼识真'],5:['洞察天机术','洞察天机'],6:['全知领域诀','全知领域']},
  // Cat 11: Dmg Reduction (A, DEFENSE)
  red_self: {1:['化劲诀','化劲卸力'],2:['卸力功','四两拨千斤'],3:['乾坤挪移法','乾坤挪移'],4:['斗转星移功','斗转星移'],5:['万法归墟诀','万法归墟'],6:['天道归无术','天道归无']},
  red_single: {1:['化劲诀','化劲卸力'],2:['卸力功','四两拨千斤'],3:['乾坤挪移法','乾坤挪移'],4:['斗转星移功','斗转星移'],5:['万法归墟诀','万法归墟'],6:['天道归无术','天道归无']},
  red_team: {1:['化劲诀','化劲卸力'],2:['卸力功','四两拨千斤'],3:['乾坤挪移法','乾坤挪移'],4:['斗转星移功','斗转星移'],5:['万法归墟诀','万法归墟'],6:['天道归无术','天道归无']},
  // Cat 12: Atk Reduce (A, ATTACK)
  atkred_enemy: {1:['弱化术','弱化'],2:['削骨诀','削骨'],3:['虚弱诅咒术','虚弱诅咒'],4:['衰败经','衰败凋零'],5:['枯荣诀','万物枯荣'],6:['剥夺领域功','剥夺领域']},
  atkred_aoe: {1:['弱化术','弱化'],2:['削骨诀','削骨'],3:['虚弱诅咒术','虚弱诅咒'],4:['衰败经','衰败凋零'],5:['枯荣诀','万物枯荣'],6:['剥夺领域功','剥夺领域']},
  // Cat 13: Def Reduce (A, ATTACK)
  defred_enemy: {1:['破甲术','破甲'],2:['碎甲诀','碎甲击'],3:['腐蚀术','腐蚀侵体'],4:['瓦解结界法','瓦解结界'],5:['崩坏领域诀','崩坏领域'],6:['万法皆破功','万法皆破']},
  defred_aoe: {1:['破甲术','破甲'],2:['碎甲诀','碎甲击'],3:['腐蚀术','腐蚀侵体'],4:['瓦解结界法','瓦解结界'],5:['崩坏领域诀','崩坏领域'],6:['万法皆破功','万法皆破']},
  // Cat 14: Shield (S, DEFENSE)
  shield_self: {1:['真元护体术','真元护体'],2:['灵罡护盾诀','灵罡护盾'],3:['玄黄罩','玄黄天罩'],4:['不动结界法','不动结界'],5:['周天护体功','周天护体'],6:['混沌守护诀','混沌守护']},
  shield_single: {1:['真元护体术','真元护体'],2:['灵罡护盾诀','灵罡护盾'],3:['玄黄罩','玄黄天罩'],4:['不动结界法','不动结界'],5:['周天护体功','周天护体'],6:['混沌守护诀','混沌守护']},
  shield_team: {1:['真元护体术','真元护体'],2:['灵罡护盾诀','灵罡护盾'],3:['玄黄罩','玄黄天罩'],4:['不动结界法','不动结界'],5:['周天护体功','周天护体'],6:['混沌守护诀','混沌守护']},
  // Cat 15: Turn Advance (S, SUPPORT)
  turnadv_ally: {1:['疾风步','疾风突进'],2:['追云术','追云逐日'],3:['缩地成寸法','缩地成寸'],4:['纵地金光术','纵地金光'],5:['瞬息千里诀','瞬息千里'],6:['时空逆转功','时空逆转']},
  // Cat 16: Dmg Share (A, DEFENSE)
  share_self: {1:['同甘共苦诀','同甘共苦'],2:['生死与共功','生死与共'],3:['血脉相连法','血脉相连'],4:['命运共同体术','命运共同体'],5:['同命锁','同命相连'],6:['大道同归诀','大道同归']},
  share_ally: {1:['同甘共苦诀','同甘共苦'],2:['生死与共功','生死与共'],3:['血脉相连法','血脉相连'],4:['命运共同体术','命运共同体'],5:['同命锁','同命相连'],6:['大道同归诀','大道同归']},
  // Cat 17: Speed (C, SUPPORT)
  spd_self: {1:['轻身术','轻身疾行'],2:['御风诀','御风而行'],3:['踏云步法','踏云追月'],4:['凌空虚渡功','凌空虚渡'],5:['扶摇直上术','扶摇直上'],6:['超脱极速诀','超脱极速']},
  spd_single: {1:['轻身术','轻身疾行'],2:['御风诀','御风而行'],3:['踏云步法','踏云追月'],4:['凌空虚渡功','凌空虚渡'],5:['扶摇直上术','扶摇直上'],6:['超脱极速诀','超脱极速']},
  spd_team: {1:['轻身术','轻身疾行'],2:['御风诀','御风而行'],3:['踏云步法','踏云追月'],4:['凌空虚渡功','凌空虚渡'],5:['扶摇直上术','扶摇直上'],6:['超脱极速诀','超脱极速']},
  // Cat 18: Dmg Link (S, ATTACK)
  link_enemy: {1:['因果报应术','因果报应'],2:['同伤咒','同伤共损'],3:['魂链术','灵魂锁链'],4:['命运诅咒法','命运诅咒'],5:['大道因果诀','大道因果'],6:['天道反噬功','天道反噬']},
};

// ===== Values =====
const HP_PCT = {1:30,2:33,3:37,4:42,5:48,6:58};
const HP_FIX = {1:100,2:630,3:1650,4:4400,5:10800,6:58000};
const MP_PCT = {1:18,2:22,3:26,4:30,5:36,6:42};
const MP_FIX = {1:38,2:250,3:650,4:1700,5:4300,6:22000};
const ATK_MULT = {1:2.0,2:2.4,3:2.9,4:3.5,5:4.2,6:5.0};
const PD_PCT = {1:30,2:35,3:40,4:45,5:55,6:65};
const DMG_PCT = {1:22,2:28,3:36,4:48,5:59,6:75};
const CRIT_V = {1:10,2:14,3:19,4:25,5:32,6:40};
const RED_PCT = {1:18,2:22,3:28,4:32,5:40,6:50};
const DEBUFF_PCT = {1:20,2:25,3:30,4:35,5:40,6:50};
const SHIELD_PCT = {1:35,2:40,3:45,4:55,5:65,6:82};
const TURN_PCT = {1:50,2:60,3:70,4:80,5:90,6:100};
const SHARE_S = {1:40,2:45,3:50,4:55,5:65,6:75};
const SHARE_A = {1:50,2:55,3:60,4:65,5:75,6:85};
const SPD_PCT = {1:30,2:35,3:40,4:50,5:60,6:75};
const LINK_PCT = {1:30,2:35,3:40,4:45,5:55,6:70};

function buffTypeJson(type) {
  if (!type) return undefined;
  return type;
}

const manuals = [];

// ===== GENERATORS =====

// Cat 1: Taunt
for (let r=1; r<=6; r++) {
  const [name, skill] = N.taunt_enemy[r];
  manuals.push({
    id: `new_taunt_${r}`, name, type: "DEFENSE", rarity: r,
    description: `嘲讽敌人强制攻击自己`,
    stats: {hp: sv(r,'hp','S')},
    skillName: skill, skillDescription: `施放${skill}，强制敌方下次攻击目标为自己`,
    skillType: "support", skillDamageType: "physical", skillHits: 1, skillDamageMultiplier: 0.0,
    skillCooldown: cdEnemy(r), skillMpCost: mp(r, 'enemy'),
    skillHealPercent: 0.0, skillHealFixed: 0, skillHealType: "hp",
    skillBuffType: "taunt", skillBuffValue: 1.0, skillBuffDuration: r >= 5 ? 2 : 1,
    skillBuffs: [], skillIsAoe: false, skillTargetScope: "enemy",
    skillShieldPercent: 0.0, skillTurnAdvancePercent: 0.0, skillDamageSharePercent: 0.0, skillDamageLinkPercent: 0.0,
    price: PRICE[r], minRealm: MIN_REALM[r]
  });
}

// Cat 2: Stun
for (let r=1; r<=6; r++) {
  const [name, skill] = N.stun_enemy[r];
  manuals.push({
    id: `new_stun_${r}`, name, type: "ATTACK", rarity: r,
    description: `眩晕敌人跳过一回合`,
    stats: {speed: sv(r,'spd','S')},
    skillName: skill, skillDescription: `施放${skill}，使敌方跳过一回合`,
    skillType: "attack", skillDamageType: "physical", skillHits: 1, skillDamageMultiplier: 0.0,
    skillCooldown: cdEnemy(r), skillMpCost: mp(r, 'enemy'),
    skillHealPercent: 0.0, skillHealFixed: 0, skillHealType: "hp",
    skillBuffType: "stun", skillBuffValue: 1.0, skillBuffDuration: 1,
    skillBuffs: [], skillIsAoe: false, skillTargetScope: "enemy",
    skillShieldPercent: 0.0, skillTurnAdvancePercent: 0.0, skillDamageSharePercent: 0.0, skillDamageLinkPercent: 0.0,
    price: PRICE[r], minRealm: MIN_REALM[r]
  });
}

// Cat 3: HP Heal (6 per rarity)
for (let r=1; r<=6; r++) {
  for (const vt of ['pct','fix']) {
    for (const sc of ['self','single','team']) {
      const isPct = vt === 'pct';
      const nameKey = `hp_${vt}_${sc}`;
      const [name, skill] = N[nameKey][r];
      const val = isPct ? HP_PCT[r] : HP_FIX[r];
      const teamVal = sc === 'team' ? Math.round(val * 0.4) : val;
      const displayPct = isPct ? `${teamVal}%` : `${teamVal}点`;
      const isAoe = sc === 'team';
      const scope = sc === 'team' ? 'team' : (sc === 'single' ? 'ally' : 'self');
      manuals.push({
        id: `new_hp_${vt}_${sc}_${r}`, name, type: "SUPPORT", rarity: r,
        description: `回复${sc==='team'?'全体':sc==='single'?'一名队友':'自身'}${displayPct}血量`,
        stats: {hp: sv(r, 'hp', 'B', isAoe)},
        skillName: skill, skillDescription: `施放${skill}，回复${sc==='team'?'全体队友每人':sc==='single'?'一名队友':'自身'}${displayPct}${isPct?'最大':''}血量`,
        skillType: "support", skillDamageType: "physical", skillHits: 1, skillDamageMultiplier: 0.0,
        skillCooldown: sc === 'team' ? (r<=2?5:4) : (sc==='single'?2:3), skillMpCost: mp(r, scope),
        skillHealPercent: isPct ? (teamVal / 100.0) : 0.0, skillHealFixed: isPct ? 0 : teamVal, skillHealType: "hp",
        skillBuffType: undefined, skillBuffValue: 0.0, skillBuffDuration: 0,
        skillBuffs: [], skillIsAoe: isAoe, skillTargetScope: scope,
        skillShieldPercent: 0.0, skillTurnAdvancePercent: 0.0, skillDamageSharePercent: 0.0, skillDamageLinkPercent: 0.0,
        price: PRICE[r], minRealm: MIN_REALM[r]
      });
    }
  }
}

// Cat 4: MP Heal (6 per rarity)
for (let r=1; r<=6; r++) {
  for (const vt of ['pct','fix']) {
    for (const sc of ['self','single','team']) {
      const isPct = vt === 'pct';
      const nameKey = `mp_${vt}_${sc}`;
      const [name, skill] = N[nameKey][r];
      const val = isPct ? MP_PCT[r] : MP_FIX[r];
      const teamVal = sc === 'team' ? Math.round(val * 0.4) : val;
      const displayPct = isPct ? `${teamVal}%` : `${teamVal}点`;
      const isAoe = sc === 'team';
      const scope = sc === 'team' ? 'team' : (sc === 'single' ? 'ally' : 'self');
      manuals.push({
        id: `new_mp_${vt}_${sc}_${r}`, name, type: "SUPPORT", rarity: r,
        description: `回复${sc==='team'?'全体':sc==='single'?'一名队友':'自身'}${displayPct}灵力`,
        stats: {mp: sv(r, 'mp', 'B', isAoe)},
        skillName: skill, skillDescription: `施放${skill}，回复${sc==='team'?'全体队友每人':sc==='single'?'一名队友':'自身'}${displayPct}${isPct?'最大':''}灵力`,
        skillType: "support", skillDamageType: "physical", skillHits: 1, skillDamageMultiplier: 0.0,
        skillCooldown: sc === 'team' ? (r<=2?5:4) : (sc==='single'?2:3), skillMpCost: mp(r, scope),
        skillHealPercent: isPct ? (teamVal / 100.0) : 0.0, skillHealFixed: isPct ? 0 : teamVal, skillHealType: "mp",
        skillBuffType: undefined, skillBuffValue: 0.0, skillBuffDuration: 0,
        skillBuffs: [], skillIsAoe: isAoe, skillTargetScope: scope,
        skillShieldPercent: 0.0, skillTurnAdvancePercent: 0.0, skillDamageSharePercent: 0.0, skillDamageLinkPercent: 0.0,
        price: PRICE[r], minRealm: MIN_REALM[r]
      });
    }
  }
}

// Cat 5: Phys Atk
for (let r=1; r<=6; r++) {
  for (const sc of ['single','aoe']) {
    const nameKey = `phys_${sc}`;
    const [name, skill] = N[nameKey][r];
    const isAoe = sc === 'aoe';
    const mult = isAoe ? Math.round(ATK_MULT[r] * 0.4 * 10) / 10 : ATK_MULT[r];
    manuals.push({
      id: `new_phys_${sc}_${r}`, name, type: "ATTACK", rarity: r,
      description: `${isAoe?'群体':'单体'}物理攻击 ${mult}x`,
      stats: {physicalAttack: sv(r, 'pa', 'C')},
      skillName: skill, skillDescription: `施放${skill}，对${isAoe?'全体':'单个'}敌人造成${mult}倍物理伤害`,
      skillType: "attack", skillDamageType: "physical", skillHits: 1, skillDamageMultiplier: mult,
      skillCooldown: 3, skillMpCost: mp(r, isAoe ? 'all_enemies' : 'enemy'),
      skillHealPercent: 0.0, skillHealFixed: 0, skillHealType: "hp",
      skillBuffType: undefined, skillBuffValue: 0.0, skillBuffDuration: 0,
      skillBuffs: [], skillIsAoe: isAoe, skillTargetScope: "enemy",
      skillShieldPercent: 0.0, skillTurnAdvancePercent: 0.0, skillDamageSharePercent: 0.0, skillDamageLinkPercent: 0.0,
      price: PRICE[r], minRealm: MIN_REALM[r]
    });
  }
}

// Cat 6: Mag Atk
for (let r=1; r<=6; r++) {
  for (const sc of ['single','aoe']) {
    const nameKey = `mag_${sc}`;
    const [name, skill] = N[nameKey][r];
    const isAoe = sc === 'aoe';
    const mult = isAoe ? Math.round(ATK_MULT[r] * 0.4 * 10) / 10 : ATK_MULT[r];
    manuals.push({
      id: `new_mag_${sc}_${r}`, name, type: "ATTACK", rarity: r,
      description: `${isAoe?'群体':'单体'}法术攻击 ${mult}x`,
      stats: {magicAttack: sv(r, 'ma', 'C')},
      skillName: skill, skillDescription: `施放${skill}，对${isAoe?'全体':'单个'}敌人造成${mult}倍法术伤害`,
      skillType: "attack", skillDamageType: "magic", skillHits: 1, skillDamageMultiplier: mult,
      skillCooldown: 3, skillMpCost: mp(r, isAoe ? 'all_enemies' : 'enemy'),
      skillHealPercent: 0.0, skillHealFixed: 0, skillHealType: "hp",
      skillBuffType: undefined, skillBuffValue: 0.0, skillBuffDuration: 0,
      skillBuffs: [], skillIsAoe: isAoe, skillTargetScope: "enemy",
      skillShieldPercent: 0.0, skillTurnAdvancePercent: 0.0, skillDamageSharePercent: 0.0, skillDamageLinkPercent: 0.0,
      price: PRICE[r], minRealm: MIN_REALM[r]
    });
  }
}

// Buff helper (cat 7-11, 17)
function makeBuff(r, cat, type, tier, sc, buffType, valFn, statFn) {
  const nameKey = `${cat}_${sc}`;
  const [name, skill] = N[nameKey][r];
  const isAoe = sc === 'team';
  const target = sc === 'team' ? 'team' : (sc === 'single' ? 'ally' : 'self');
  const val = sc === 'team' ? Math.round(valFn(r) * 0.4) : valFn(r);
  const dur = sc === 'team' ? 3 : (sc === 'single' ? 2 : 3);
  const cd = sc === 'team' ? cdTeam(r) : (sc === 'single' ? cdSingle(r) : cdSelf(r));
  const cnNames = {pd:'物防', md:'法防', dmg:'伤害加成', crit:'暴击率', red:'伤害减免', spd:'速度'};
  manuals.push({
    id: `new_${cat}_${sc}_${r}`, name, type, rarity: r,
    description: `${isAoe?'全体':sc==='single'?'单体':'自身'}${cnNames[cat]}提升${val}%`,
    stats: statFn(r, tier, isAoe),
    skillName: skill, skillDescription: `施放${skill}，${isAoe?'全体队友':sc==='single'?'一名队友':'自身'}${cnNames[cat]}提升${val}%，持续${dur}回合`,
    skillType: "support", skillDamageType: "physical", skillHits: 1, skillDamageMultiplier: 0.0,
    skillCooldown: cd, skillMpCost: mp(r, sc), skillHealPercent: 0.0, skillHealFixed: 0, skillHealType: "hp",
    skillBuffType: buffType, skillBuffValue: val / 100.0, skillBuffDuration: dur,
    skillBuffs: [], skillIsAoe: isAoe, skillTargetScope: target,
    skillShieldPercent: 0.0, skillTurnAdvancePercent: 0.0, skillDamageSharePercent: 0.0, skillDamageLinkPercent: 0.0,
    price: PRICE[r], minRealm: MIN_REALM[r]
  });
}

// Cat 7: Phys Def
for (let r=1; r<=6; r++) for (const sc of ['self','single','team']) makeBuff(r, 'pd', 'DEFENSE', 'B', sc, 'physical_defense', (r)=>PD_PCT[r], (r,t,ta)=> ({physicalDefense: sv(r,'pd',t,ta)}));
// Cat 8: Mag Def
for (let r=1; r<=6; r++) for (const sc of ['self','single','team']) makeBuff(r, 'md', 'DEFENSE', 'B', sc, 'magic_defense', (r)=>PD_PCT[r], (r,t,ta)=> ({magicDefense: sv(r,'md',t,ta)}));
// Cat 9: Dmg Boost
for (let r=1; r<=6; r++) for (const sc of ['self','single','team']) makeBuff(r, 'dmg', 'SUPPORT', 'A', sc, 'damage_boost', (r)=>DMG_PCT[r], (r,t,ta)=> ({physicalAttack: sv(r,'pa',t,ta), magicAttack: sv(r,'ma',t,ta)}));
// Cat 10: Crit (use crit_rate buff, value is absolute %)
for (let r=1; r<=6; r++) for (const sc of ['self','single','team']) {
  const val = sc === 'team' ? Math.round(CRIT_V[r] * 0.4) : CRIT_V[r];
  const [name, skill] = N[`crit_${sc}`][r];
  const isAoe = sc === 'team';
  const target = sc === 'team' ? 'team' : (sc === 'single' ? 'ally' : 'self');
  const dur = sc === 'team' ? 3 : (sc === 'single' ? 2 : 3);
  const cd = sc === 'team' ? cdTeam(r) : (sc === 'single' ? cdSingle(r) : cdSelf(r));
  manuals.push({
    id: `new_crit_${sc}_${r}`, name, type: "SUPPORT", rarity: r,
    description: `${isAoe?'全体':sc==='single'?'单体':'自身'}暴击率+${val}%`,
    stats: {speed: sv(r,'spd','C',isAoe)},
    skillName: skill, skillDescription: `施放${skill}，${isAoe?'全体队友':sc==='single'?'一名队友':'自身'}暴击率+${val}%，持续${dur}回合`,
    skillType: "support", skillDamageType: "physical", skillHits: 1, skillDamageMultiplier: 0.0,
    skillCooldown: cd, skillMpCost: mp(r, sc), skillHealPercent: 0.0, skillHealFixed: 0, skillHealType: "hp",
    skillBuffType: "crit_rate", skillBuffValue: val / 100.0, skillBuffDuration: dur,
    skillBuffs: [], skillIsAoe: isAoe, skillTargetScope: target,
    skillShieldPercent: 0.0, skillTurnAdvancePercent: 0.0, skillDamageSharePercent: 0.0, skillDamageLinkPercent: 0.0,
    price: PRICE[r], minRealm: MIN_REALM[r]
  });
}
// Cat 11: Dmg Reduction
for (let r=1; r<=6; r++) for (const sc of ['self','single','team']) makeBuff(r, 'red', 'DEFENSE', 'A', sc, 'damage_reduction', (r)=>RED_PCT[r], (r,t,ta)=> ({hp: sv(r,'hp',t,ta), physicalDefense: sv(r,'pd',t,ta)}));
// Cat 17: Speed
for (let r=1; r<=6; r++) for (const sc of ['self','single','team']) makeBuff(r, 'spd', 'SUPPORT', 'C', sc, 'speed', (r)=>SPD_PCT[r], (r,t,ta)=> ({speed: sv(r,'spd',t,ta)}));

// Cat 12: Atk Reduce (debuff)
for (let r=1; r<=6; r++) for (const sc of ['enemy','aoe']) {
  const nameKey = `atkred_${sc}`; const [name, skill] = N[nameKey][r];
  const isAoe = sc === 'aoe'; const pct = isAoe ? Math.round(DEBUFF_PCT[r]*0.4) : DEBUFF_PCT[r];
  const cd = sc === 'enemy' ? cdEnemy(r) : cdAllEnemy(r);
  manuals.push({
    id: `new_atkred_${sc}_${r}`, name, type: "ATTACK", rarity: r,
    description: `${isAoe?'全体':'单体'}减攻${pct}%`,
    stats: {physicalAttack: sv(r,'pa','A')},
    skillName: skill, skillDescription: `施放${skill}，${isAoe?'全体':''}敌人物攻和法攻降低${pct}%，持续3回合`,
    skillType: "attack", skillDamageType: "physical", skillHits: 1, skillDamageMultiplier: 0.0,
    skillCooldown: cd, skillMpCost: mp(r, sc === 'aoe' ? 'all_enemies' : 'enemy'),
    skillHealPercent: 0.0, skillHealFixed: 0, skillHealType: "hp",
    skillBuffType: undefined, skillBuffValue: 0.0, skillBuffDuration: 0,
    skillBuffs: [{type:'physical_attack_reduce',value:pct/100,duration:3},{type:'magic_attack_reduce',value:pct/100,duration:3}],
    skillIsAoe: isAoe, skillTargetScope: "enemy",
    skillShieldPercent: 0.0, skillTurnAdvancePercent: 0.0, skillDamageSharePercent: 0.0, skillDamageLinkPercent: 0.0,
    price: PRICE[r], minRealm: MIN_REALM[r]
  });
}

// Cat 13: Def Reduce (debuff)
for (let r=1; r<=6; r++) for (const sc of ['enemy','aoe']) {
  const nameKey = `defred_${sc}`; const [name, skill] = N[nameKey][r];
  const isAoe = sc === 'aoe'; const pct = isAoe ? Math.round(DEBUFF_PCT[r]*0.4) : DEBUFF_PCT[r];
  const cd = sc === 'enemy' ? cdEnemy(r) : cdAllEnemy(r);
  manuals.push({
    id: `new_defred_${sc}_${r}`, name, type: "ATTACK", rarity: r,
    description: `${isAoe?'全体':'单体'}减防${pct}%`,
    stats: {magicAttack: sv(r,'ma','A')},
    skillName: skill, skillDescription: `施放${skill}，${isAoe?'全体':''}敌人物防和法防降低${pct}%，持续3回合`,
    skillType: "attack", skillDamageType: "magic", skillHits: 1, skillDamageMultiplier: 0.0,
    skillCooldown: cd, skillMpCost: mp(r, sc === 'aoe' ? 'all_enemies' : 'enemy'),
    skillHealPercent: 0.0, skillHealFixed: 0, skillHealType: "hp",
    skillBuffType: undefined, skillBuffValue: 0.0, skillBuffDuration: 0,
    skillBuffs: [{type:'physical_defense_reduce',value:pct/100,duration:3},{type:'magic_defense_reduce',value:pct/100,duration:3}],
    skillIsAoe: isAoe, skillTargetScope: "enemy",
    skillShieldPercent: 0.0, skillTurnAdvancePercent: 0.0, skillDamageSharePercent: 0.0, skillDamageLinkPercent: 0.0,
    price: PRICE[r], minRealm: MIN_REALM[r]
  });
}

// Cat 14: Shield
for (let r=1; r<=6; r++) for (const sc of ['self','single','team']) {
  const [name, skill] = N[`shield_${sc}`][r];
  const isAoe = sc === 'team'; const target = sc === 'team' ? 'team' : (sc === 'single' ? 'ally' : 'self');
  const val = sc === 'team' ? Math.round(SHIELD_PCT[r]*0.4) : SHIELD_PCT[r];
  const dur = sc === 'team' ? 3 : (sc === 'single' ? 2 : 3);
  const cd = sc === 'team' ? cdTeam(r) : (sc === 'single' ? cdSingle(r) : cdSelf(r));
  manuals.push({
    id: `new_shield_${sc}_${r}`, name, type: "DEFENSE", rarity: r,
    description: `${isAoe?'全体':sc==='single'?'单体':'自身'}护盾${val}%最大HP`,
    stats: {hp: sv(r,'hp','S',isAoe)},
    skillName: skill, skillDescription: `施放${skill}，为${isAoe?'全体队友':sc==='single'?'一名队友':'自身'}附加${val}%最大HP护盾，持续${dur}回合`,
    skillType: "support", skillDamageType: "physical", skillHits: 1, skillDamageMultiplier: 0.0,
    skillCooldown: cd, skillMpCost: mp(r, sc), skillHealPercent: 0.0, skillHealFixed: 0, skillHealType: "hp",
    skillBuffType: "shield", skillBuffValue: val/100, skillBuffDuration: dur,
    skillBuffs: [], skillIsAoe: isAoe, skillTargetScope: target,
    skillShieldPercent: val/100, skillTurnAdvancePercent: 0.0, skillDamageSharePercent: 0.0, skillDamageLinkPercent: 0.0,
    price: PRICE[r], minRealm: MIN_REALM[r]
  });
}

// Cat 15: Turn Advance
for (let r=1; r<=6; r++) {
  const [name, skill] = N.turnadv_ally[r];
  const cd = r <= 2 ? 5 : (r <= 4 ? 4 : 3);
  manuals.push({
    id: `new_turnadv_${r}`, name, type: "SUPPORT", rarity: r,
    description: `拉条${TURN_PCT[r]}%`,
    stats: {speed: sv(r,'spd','S')},
    skillName: skill, skillDescription: `施放${skill}，指定一名队友行动条提前${TURN_PCT[r]}%`,
    skillType: "support", skillDamageType: "physical", skillHits: 1, skillDamageMultiplier: 0.0,
    skillCooldown: cd, skillMpCost: mp(r, 'ally'), skillHealPercent: 0.0, skillHealFixed: 0, skillHealType: "hp",
    skillBuffType: "turn_advance", skillBuffValue: TURN_PCT[r]/100, skillBuffDuration: 0,
    skillBuffs: [], skillIsAoe: false, skillTargetScope: "ally",
    skillShieldPercent: 0.0, skillTurnAdvancePercent: TURN_PCT[r]/100, skillDamageSharePercent: 0.0, skillDamageLinkPercent: 0.0,
    price: PRICE[r], minRealm: MIN_REALM[r]
  });
}

// Cat 16: Dmg Share
for (let r=1; r<=6; r++) for (const sc of ['self','ally']) {
  const [name, skill] = N[`share_${sc}`][r];
  const val = sc === 'self' ? SHARE_S[r] : SHARE_A[r];
  manuals.push({
    id: `new_share_${sc}_${r}`, name, type: "DEFENSE", rarity: r,
    description: `伤害分摊${val}%`,
    stats: {hp: sv(r,'hp','A'), magicDefense: sv(r,'md','A')},
    skillName: skill, skillDescription: `施放${skill}，${sc==='self'?'自身':'一名队友'}分摊${val}%所受伤害，持续3回合`,
    skillType: "support", skillDamageType: "physical", skillHits: 1, skillDamageMultiplier: 0.0,
    skillCooldown: r <= 4 ? 5 : 4, skillMpCost: mp(r, sc==='ally'?'ally':'self'),
    skillHealPercent: 0.0, skillHealFixed: 0, skillHealType: "hp",
    skillBuffType: "damage_share", skillBuffValue: val/100, skillBuffDuration: 3,
    skillBuffs: [], skillIsAoe: false, skillTargetScope: sc === 'self' ? 'self' : 'ally',
    skillShieldPercent: 0.0, skillTurnAdvancePercent: 0.0, skillDamageSharePercent: val/100, skillDamageLinkPercent: 0.0,
    price: PRICE[r], minRealm: MIN_REALM[r]
  });
}

// Cat 18: Dmg Link
for (let r=1; r<=6; r++) {
  const [name, skill] = N.link_enemy[r];
  const dur = r <= 2 ? 2 : 3;
  manuals.push({
    id: `new_link_${r}`, name, type: "ATTACK", rarity: r,
    description: `伤害链接${LINK_PCT[r]}%`,
    stats: {magicAttack: sv(r,'ma','S')},
    skillName: skill, skillDescription: `施放${skill}，链接一名敌人。自身对其造成伤害时额外传递${LINK_PCT[r]}%真实伤害，持续${dur}回合。同时只能链接一个敌人。`,
    skillType: "attack", skillDamageType: "magic", skillHits: 1, skillDamageMultiplier: 0.0,
    skillCooldown: r <= 4 ? 5 : 4, skillMpCost: mp(r, 'enemy'),
    skillHealPercent: 0.0, skillHealFixed: 0, skillHealType: "hp",
    skillBuffType: "damage_link", skillBuffValue: LINK_PCT[r]/100, skillBuffDuration: dur,
    skillBuffs: [], skillIsAoe: false, skillTargetScope: "enemy",
    skillShieldPercent: 0.0, skillTurnAdvancePercent: 0.0, skillDamageSharePercent: 0.0, skillDamageLinkPercent: LINK_PCT[r]/100,
    price: PRICE[r], minRealm: MIN_REALM[r]
  });
}

// ===== Clean up: remove undefined buffType =====
function clean(obj) {
  for (const k of Object.keys(obj)) {
    if (obj[k] === undefined) delete obj[k];
  }
  if (obj.skillBuffs && obj.skillBuffs.length === 0) obj.skillBuffs = [];
  return obj;
}
const cleaned = manuals.map(clean);

// ===== Merge with existing =====
const existingPath = path.join(__dirname, '..', 'android', 'app', 'src', 'main', 'assets', 'data', 'manuals.json');
const existing = JSON.parse(fs.readFileSync(existingPath, 'utf8'));

// Separate by type
const newAttack = cleaned.filter(m => m.type === 'ATTACK');
const newDefense = cleaned.filter(m => m.type === 'DEFENSE');
const newSupport = cleaned.filter(m => m.type === 'SUPPORT');

existing.attackManuals.push(...newAttack);
existing.defenseManuals.push(...newDefense);
existing.supportManuals.push(...newSupport);

// Write merged
const destPath = path.join(__dirname, '..', 'android', 'app', 'src', 'main', 'assets', 'data', 'manuals_new.json');
fs.writeFileSync(destPath, JSON.stringify(existing, null, 2), 'utf8');

// Stats
console.log(`Existing: attack=${existing.attackManuals.length - newAttack.length}, defense=${existing.defenseManuals.length - newDefense.length}, support=${existing.supportManuals.length - newSupport.length}, mind=${existing.mindManuals.length}`);
console.log(`New added: attack=${newAttack.length}, defense=${newDefense.length}, support=${newSupport.length}`);
console.log(`Total now: ${existing.attackManuals.length + existing.defenseManuals.length + existing.supportManuals.length + existing.mindManuals.length}`);
console.log(`Written to: ${destPath}`);
