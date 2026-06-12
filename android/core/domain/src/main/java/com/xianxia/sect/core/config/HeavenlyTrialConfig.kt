package com.xianxia.sect.core.config

import com.xianxia.sect.core.model.HeavenlyTrialLevelConfig
import com.xianxia.sect.core.model.TrialEnemyDef

object HeavenlyTrialConfig {

    // GameConfig realm IDs: 8=筑基, 7=金丹, 6=元婴, 5=化神, 4=炼虚, 3=合体, 2=大乘
    private const val R_DACHENG = 2
    private const val R_HETI = 3
    private const val R_LIANXU = 4
    private const val R_HUASHEN = 5
    private const val R_YUANYING = 6
    private const val R_JINDAN = 7
    private const val R_ZHUJI = 8

    private val levels: List<HeavenlyTrialLevelConfig> = listOf(
        // ===== 第一关：筑基(8)×2 + 金丹(7)"boss" =====
        HeavenlyTrialLevelConfig(levelIndex = 0, label = "第一关",
            phase1Enemies = listOf(
                TrialEnemyDef("试炼虎妖", R_ZHUJI, 5, "虎妖",
                    listOf("虎啸", "猛虎扑击", "虎尾鞭")),
                TrialEnemyDef("试炼虎妖", R_ZHUJI, 5, "虎妖",
                    listOf("虎啸", "猛虎扑击", "虎尾鞭")),
                TrialEnemyDef("试炼虎妖", R_ZHUJI, 5, "虎妖",
                    listOf("虎啸", "猛虎扑击", "虎尾鞭"))
            ),
            phase2Enemies = listOf(
                TrialEnemyDef("试炼弟子", R_ZHUJI, 5,
                    role = "Tank",
                    equipmentIds = listOf("ironSword", "leatherArmor", "leatherBoots", "copperNecklace"),
                    manualIds = listOf("new_taunt_1", "new_shield_self_1", "phys_hp_def_common_self", "new_share_ally_1", "new_stun_1", "mind_common_both_1")
                ),
                TrialEnemyDef("试炼弟子", R_ZHUJI, 5,
                    role = "DPS",
                    equipmentIds = listOf("bronzeDagger", "chainMail", "clothBoots", "jadeRing"),
                    manualIds = listOf("common_magic_single_1", "common_magic_aoe_1", "common_magic_multi_1", "common_magic_aoe_multi_1", "new_stun_1", "mind_common_both_1")
                ),
                TrialEnemyDef("试炼弟子", R_JINDAN, 3,
                    role = "Support",
                    equipmentIds = listOf("jadeStaff", "spiritRobe", "swiftBoots", "spiritPendant"),
                    manualIds = listOf("new_hp_pct_team_2", "new_hp_pct_single_2", "support_r2_pa_ma", "support_r2_pd_md", "support_r2_speed", "mind_uncommon_team_both_1")
                )
            )
        ),
        // ===== 第二关：金丹(7)×3 =====
        HeavenlyTrialLevelConfig(levelIndex = 1, label = "第二关",
            phase1Enemies = listOf(
                TrialEnemyDef("试炼狼妖", R_JINDAN, 2, "狼妖",
                    listOf("狼嚎", "撕裂爪", "群狼战术")),
                TrialEnemyDef("试炼狼妖", R_JINDAN, 2, "狼妖",
                    listOf("狼嚎", "撕裂爪", "群狼战术")),
                TrialEnemyDef("试炼狼妖", R_JINDAN, 2, "狼妖",
                    listOf("狼嚎", "撕裂爪", "群狼战术"))
            ),
            phase2Enemies = listOf(
                TrialEnemyDef("试炼弟子", R_JINDAN, 5,
                    role = "Tank",
                    equipmentIds = listOf("spiritSword", "ironPlate", "swiftBoots", "spiritPendant"),
                    manualIds = listOf("new_taunt_2", "new_shield_self_2", "phys_hp_def_uncommon_self", "new_share_ally_2", "new_stun_2", "mind_uncommon_both_1")
                ),
                TrialEnemyDef("试炼弟子", R_JINDAN, 5,
                    role = "DPS",
                    equipmentIds = listOf("battleAxe", "steelArmor", "lightBoots", "healthRing"),
                    manualIds = listOf("uncommon_magic_single_1", "uncommon_magic_aoe_1", "uncommon_magic_multi_1", "uncommon_magic_aoe_multi_1", "new_stun_2", "mind_uncommon_both_1")
                ),
                TrialEnemyDef("试炼弟子", R_JINDAN, 5,
                    role = "Support",
                    equipmentIds = listOf("jadeStaff", "spiritRobe", "swiftBoots", "spiritPendant"),
                    manualIds = listOf("new_hp_pct_team_2", "new_hp_pct_single_2", "support_r2_pa_ma", "support_r2_pd_md", "support_r2_speed", "mind_uncommon_team_both_1")
                )
            )
        ),
        // ===== 第三关：元婴(6)×2 + 化神(5)"boss" =====
        HeavenlyTrialLevelConfig(levelIndex = 2, label = "第三关",
            phase1Enemies = listOf(
                TrialEnemyDef("试炼龙妖", R_YUANYING, 5, "龙妖",
                    listOf("龙息", "龙威", "龙鳞甲", "龙爪撕裂")),
                TrialEnemyDef("试炼龙妖", R_YUANYING, 5, "龙妖",
                    listOf("龙息", "龙威", "龙鳞甲", "龙爪撕裂")),
                TrialEnemyDef("试炼龙妖", R_YUANYING, 5, "龙妖",
                    listOf("龙息", "龙威", "龙鳞甲", "龙爪撕裂"))
            ),
            phase2Enemies = listOf(
                TrialEnemyDef("试炼弟子", R_YUANYING, 5,
                    role = "Tank",
                    equipmentIds = listOf("frostBlade", "scaleArmor", "windBoots", "storageRing"),
                    manualIds = listOf("new_taunt_3", "new_shield_self_3", "phys_hp_def_rare_self", "new_share_ally_3", "new_stun_3", "mind_rare_both_1")
                ),
                TrialEnemyDef("试炼弟子", R_YUANYING, 5,
                    role = "DPS",
                    equipmentIds = listOf("flameSword", "plateArmor", "mistBoots", "wisdomOrb"),
                    manualIds = listOf("rare_magic_single_1", "rare_magic_aoe_1", "rare_magic_multi_1", "rare_magic_aoe_multi_1", "new_stun_3", "mind_rare_both_1")
                ),
                TrialEnemyDef("试炼弟子", R_HUASHEN, 3,
                    role = "Support",
                    equipmentIds = listOf("voidStaff", "moonRobe", "cloudBoots", "dragonEye"),
                    manualIds = listOf("new_hp_pct_team_4", "new_hp_pct_single_4", "support_r4_pa_ma", "support_r4_pd_md", "support_r4_speed", "mind_epic_team_both_1")
                )
            )
        ),
        // ===== 第四关：化神(5)×3 =====
        HeavenlyTrialLevelConfig(levelIndex = 3, label = "第四关",
            phase1Enemies = listOf(
                TrialEnemyDef("试炼龟妖", R_HUASHEN, 2, "龟妖",
                    listOf("龟甲术", "水弹", "激流", "缩壳防御")),
                TrialEnemyDef("试炼龟妖", R_HUASHEN, 2, "龟妖",
                    listOf("龟甲术", "水弹", "激流", "缩壳防御")),
                TrialEnemyDef("试炼龟妖", R_HUASHEN, 2, "龟妖",
                    listOf("龟甲术", "水弹", "激流", "缩壳防御"))
            ),
            phase2Enemies = listOf(
                TrialEnemyDef("试炼弟子", R_HUASHEN, 5,
                    role = "Tank",
                    equipmentIds = listOf("thunderSword", "dragonScale", "cloudBoots", "dragonEye"),
                    manualIds = listOf("new_taunt_4", "new_shield_self_4", "phys_hp_def_epic_self", "new_share_ally_4", "new_stun_4", "mind_epic_both_1")
                ),
                TrialEnemyDef("试炼弟子", R_HUASHEN, 5,
                    role = "DPS",
                    equipmentIds = listOf("shadowBlade", "titanArmor", "thunderBoots", "phoenixHeart"),
                    manualIds = listOf("epic_magic_single_1", "epic_magic_aoe_1", "epic_magic_multi_1", "epic_magic_aoe_multi_1", "new_stun_4", "mind_epic_both_1")
                ),
                TrialEnemyDef("试炼弟子", R_HUASHEN, 5,
                    role = "Support",
                    equipmentIds = listOf("voidStaff", "moonRobe", "cloudBoots", "dragonEye"),
                    manualIds = listOf("new_hp_pct_team_4", "new_hp_pct_single_4", "support_r4_pa_ma", "support_r4_pd_md", "support_r4_speed", "mind_epic_team_both_1")
                )
            )
        ),
        // ===== 第五关：化神(5)×2 + 炼虚(4)"boss" =====
        HeavenlyTrialLevelConfig(levelIndex = 4, label = "第五关",
            phase1Enemies = listOf(
                TrialEnemyDef("试炼虎妖", R_HUASHEN, 5, "虎妖",
                    listOf("虎啸山林", "猛虎下山", "虎威震天", "撕裂爪", "虎尾横扫")),
                TrialEnemyDef("试炼虎妖", R_HUASHEN, 5, "虎妖",
                    listOf("虎啸山林", "猛虎下山", "虎威震天", "撕裂爪", "虎尾横扫")),
                TrialEnemyDef("试炼虎妖", R_HUASHEN, 5, "虎妖",
                    listOf("虎啸山林", "猛虎下山", "虎威震天", "撕裂爪", "虎尾横扫"))
            ),
            phase2Enemies = listOf(
                TrialEnemyDef("试炼弟子", R_HUASHEN, 5,
                    role = "Tank",
                    equipmentIds = listOf("thunderSword", "dragonScale", "cloudBoots", "dragonEye"),
                    manualIds = listOf("new_taunt_4", "new_shield_self_4", "phys_hp_def_epic_self", "new_share_ally_4", "new_stun_4", "mind_epic_both_1")
                ),
                TrialEnemyDef("试炼弟子", R_HUASHEN, 5,
                    role = "DPS",
                    equipmentIds = listOf("shadowBlade", "titanArmor", "thunderBoots", "phoenixHeart"),
                    manualIds = listOf("epic_magic_single_1", "epic_magic_aoe_1", "epic_magic_multi_1", "epic_magic_aoe_multi_1", "new_stun_4", "mind_epic_both_1")
                ),
                TrialEnemyDef("试炼弟子", R_LIANXU, 3,
                    role = "Support",
                    equipmentIds = listOf("voidStaff", "moonRobe", "cloudBoots", "dragonEye"),
                    manualIds = listOf("new_hp_pct_team_5", "new_hp_pct_single_5", "support_r5_pa_ma", "support_r5_pd_md", "support_r5_hp_pd", "mind_legendary_team_both_1")
                )
            )
        ),
        // ===== 第六关：炼虚(4)×3 =====
        HeavenlyTrialLevelConfig(levelIndex = 5, label = "第六关",
            phase1Enemies = listOf(
                TrialEnemyDef("试炼龟妖", R_LIANXU, 2, "龟妖",
                    listOf("玄武甲", "沧海横流", "激流葬", "缩壳防御", "水龙卷")),
                TrialEnemyDef("试炼龟妖", R_LIANXU, 2, "龟妖",
                    listOf("玄武甲", "沧海横流", "激流葬", "缩壳防御", "水龙卷")),
                TrialEnemyDef("试炼龟妖", R_LIANXU, 2, "龟妖",
                    listOf("玄武甲", "沧海横流", "激流葬", "缩壳防御", "水龙卷"))
            ),
            phase2Enemies = listOf(
                TrialEnemyDef("试炼弟子", R_LIANXU, 5,
                    role = "Tank",
                    equipmentIds = listOf("thunderSword", "dragonScale", "cloudBoots", "dragonEye"),
                    manualIds = listOf("new_taunt_5", "new_shield_self_5", "phys_hp_def_legendary_self", "new_share_ally_5", "new_stun_5", "mind_legendary_both_1")
                ),
                TrialEnemyDef("试炼弟子", R_LIANXU, 5,
                    role = "DPS",
                    equipmentIds = listOf("shadowBlade", "titanArmor", "thunderBoots", "phoenixHeart"),
                    manualIds = listOf("legendary_magic_single_1", "legendary_magic_aoe_1", "legendary_magic_multi_1", "legendary_magic_aoe_multi_1", "new_stun_5", "mind_legendary_both_1")
                ),
                TrialEnemyDef("试炼弟子", R_LIANXU, 5,
                    role = "Support",
                    equipmentIds = listOf("voidStaff", "moonRobe", "cloudBoots", "dragonEye"),
                    manualIds = listOf("new_hp_pct_team_5", "new_hp_pct_single_5", "support_r5_pa_ma", "support_r5_pd_md", "support_r5_speed", "mind_legendary_team_both_1")
                )
            )
        ),
        // ===== 第七关：炼虚(4)×2 + 合体(3)"boss" =====
        HeavenlyTrialLevelConfig(levelIndex = 6, label = "第七关",
            phase1Enemies = listOf(
                TrialEnemyDef("试炼鹰妖", R_LIANXU, 5, "鹰妖",
                    listOf("鹰击长空", "风刃", "狂风绝息", "利爪撕裂", "鹰眼锁定")),
                TrialEnemyDef("试炼鹰妖", R_LIANXU, 5, "鹰妖",
                    listOf("鹰击长空", "风刃", "狂风绝息", "利爪撕裂", "鹰眼锁定")),
                TrialEnemyDef("试炼鹰妖", R_LIANXU, 5, "鹰妖",
                    listOf("鹰击长空", "风刃", "狂风绝息", "利爪撕裂", "鹰眼锁定"))
            ),
            phase2Enemies = listOf(
                TrialEnemyDef("试炼弟子", R_LIANXU, 5,
                    role = "Tank",
                    equipmentIds = listOf("thunderSword", "dragonScale", "cloudBoots", "dragonEye"),
                    manualIds = listOf("new_taunt_5", "new_shield_self_5", "phys_hp_def_legendary_self", "new_share_ally_5", "new_stun_5", "mind_legendary_both_1")
                ),
                TrialEnemyDef("试炼弟子", R_LIANXU, 5,
                    role = "DPS",
                    equipmentIds = listOf("shadowBlade", "titanArmor", "thunderBoots", "phoenixHeart"),
                    manualIds = listOf("legendary_magic_single_1", "legendary_magic_aoe_1", "legendary_magic_multi_1", "legendary_magic_aoe_multi_1", "new_stun_5", "mind_legendary_both_1")
                ),
                TrialEnemyDef("试炼弟子", R_HETI, 3,
                    role = "Support",
                    equipmentIds = listOf("voidStaff", "moonRobe", "cloudBoots", "dragonEye"),
                    manualIds = listOf("new_hp_pct_team_5", "new_hp_pct_single_5", "support_r5_pa_ma", "support_r5_pd_md", "support_r5_speed", "mind_legendary_team_both_1")
                )
            )
        ),
        // ===== 第八关：大乘(2)×3 =====
        HeavenlyTrialLevelConfig(levelIndex = 7, label = "第八关",
            phase1Enemies = listOf(
                TrialEnemyDef("试炼狐妖", R_HETI, 2, "狐妖",
                    listOf("魅惑", "妖火", "幻影分身", "灵狐九尾", "灵魂冲击")),
                TrialEnemyDef("试炼狐妖", R_HETI, 2, "狐妖",
                    listOf("魅惑", "妖火", "幻影分身", "灵狐九尾", "灵魂冲击")),
                TrialEnemyDef("试炼狐妖", R_HETI, 2, "狐妖",
                    listOf("魅惑", "妖火", "幻影分身", "灵狐九尾", "灵魂冲击"))
            ),
            phase2Enemies = listOf(
                TrialEnemyDef("试炼弟子", R_DACHENG, 5,
                    role = "Tank",
                    equipmentIds = listOf("thunderSword", "dragonScale", "cloudBoots", "dragonEye"),
                    manualIds = listOf("new_taunt_6", "new_shield_self_6", "phys_hp_def_mythic_self", "new_share_ally_6", "new_stun_6", "mind_mythic_both_1")
                ),
                TrialEnemyDef("试炼弟子", R_DACHENG, 5,
                    role = "DPS",
                    equipmentIds = listOf("shadowBlade", "titanArmor", "thunderBoots", "phoenixHeart"),
                    manualIds = listOf("mythic_magic_single_1", "mythic_magic_aoe_1", "mythic_magic_multi_1", "mythic_magic_aoe_multi_1", "new_stun_6", "mind_mythic_both_1")
                ),
                TrialEnemyDef("试炼弟子", R_DACHENG, 5,
                    role = "Support",
                    equipmentIds = listOf("voidStaff", "moonRobe", "cloudBoots", "dragonEye"),
                    manualIds = listOf("new_hp_pct_team_6", "new_hp_pct_single_6", "support_r6_pa_ma", "support_r6_pd_md", "support_r6_hp_pd", "mind_mythic_team_both_1")
                )
            )
        )
    )

    fun getLevel(index: Int) = levels.getOrNull(index)
    fun getAllLevels() = levels
    val levelCount get() = levels.size
}
