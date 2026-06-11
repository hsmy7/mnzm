package com.xianxia.sect.core.config

import com.xianxia.sect.core.model.HeavenlyTrialLevelConfig
import com.xianxia.sect.core.model.TrialEnemyDef

object HeavenlyTrialConfig {

    private const val R_DACHENG = 1
    private const val R_HETI = 2
    private const val R_LIANXU = 3
    private const val R_HUASHEN = 4
    private const val R_YUANYING = 5
    private const val R_JINDAN = 6
    private const val R_ZHUJI = 7

    private val levels: List<HeavenlyTrialLevelConfig> = listOf(
        // ===== 第一关：筑基(7)×2 + 金丹(6)"boss" =====
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
                    manualIds = listOf("phys_hp_def_common_self", "common_phys_single_1")
                ),
                TrialEnemyDef("试炼弟子", R_ZHUJI, 5,
                    role = "DPS",
                    equipmentIds = listOf("bronzeDagger", "chainMail", "clothBoots", "jadeRing"),
                    manualIds = listOf("common_phys_single_1", "common_phys_aoe_1")
                ),
                TrialEnemyDef("试炼弟子", R_JINDAN, 3,
                    role = "Support",
                    equipmentIds = listOf("jadeStaff", "spiritRobe", "swiftBoots", "spiritPendant"),
                    manualIds = listOf("support_r2_hp_pd", "support_r2_pa_speed", "phys_hp_def_uncommon_team", "mind_uncommon_team_both_1")
                )
            )
        ),
        // ===== 第二关：金丹(6)×3 =====
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
                    manualIds = listOf("phys_hp_def_uncommon_self", "phys_def_uncommon_self", "uncommon_phys_single_1", "mind_uncommon_both_1")
                ),
                TrialEnemyDef("试炼弟子", R_JINDAN, 5,
                    role = "DPS",
                    equipmentIds = listOf("battleAxe", "steelArmor", "lightBoots", "healthRing"),
                    manualIds = listOf("uncommon_phys_single_1", "uncommon_phys_aoe_1", "uncommon_phys_multi_1", "mind_uncommon_both_1")
                ),
                TrialEnemyDef("试炼弟子", R_JINDAN, 5,
                    role = "Support",
                    equipmentIds = listOf("jadeStaff", "spiritRobe", "swiftBoots", "spiritPendant"),
                    manualIds = listOf("support_r2_hp_pd", "support_r2_pa_speed", "phys_hp_def_uncommon_team", "mind_uncommon_team_both_1")
                )
            )
        ),
        // ===== 第三关：元婴(5)×2 + 化神(4)"boss" =====
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
                    manualIds = listOf("phys_hp_def_rare_self", "phys_def_rare_self", "rare_phys_single_1", "mind_rare_both_1")
                ),
                TrialEnemyDef("试炼弟子", R_YUANYING, 5,
                    role = "DPS",
                    equipmentIds = listOf("flameSword", "plateArmor", "mistBoots", "wisdomOrb"),
                    manualIds = listOf("rare_phys_single_1", "rare_phys_aoe_1", "rare_phys_multi_1", "mind_rare_both_1")
                ),
                TrialEnemyDef("试炼弟子", R_HUASHEN, 3,
                    role = "Support",
                    equipmentIds = listOf("voidStaff", "moonRobe", "cloudBoots", "dragonEye"),
                    manualIds = listOf("support_r4_hp_pd", "support_r4_pa_speed", "support_r4_pd_md", "phys_hp_def_epic_team", "phys_mag_def_epic_team", "mind_epic_team_both_1", "support_r4_hp_mp")
                )
            )
        ),
        // ===== 第四关：化神(4)×3 =====
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
                    manualIds = listOf("phys_hp_def_epic_self", "phys_def_epic_self", "phys_mag_def_epic_self", "epic_phys_single_1", "mind_epic_both_1", "hp_def_epic_self", "hp_def_epic_team")
                ),
                TrialEnemyDef("试炼弟子", R_HUASHEN, 5,
                    role = "DPS",
                    equipmentIds = listOf("shadowBlade", "titanArmor", "thunderBoots", "phoenixHeart"),
                    manualIds = listOf("epic_phys_single_1", "epic_phys_aoe_1", "epic_phys_multi_1", "epic_phys_aoe_multi_1", "epic_magic_single_1", "epic_magic_aoe_1", "mind_epic_both_1")
                ),
                TrialEnemyDef("试炼弟子", R_HUASHEN, 5,
                    role = "Support",
                    equipmentIds = listOf("voidStaff", "moonRobe", "cloudBoots", "dragonEye"),
                    manualIds = listOf("support_r4_hp_pd", "support_r4_pa_speed", "support_r4_pd_md", "support_r4_hp_mp", "phys_hp_def_epic_team", "phys_mag_def_epic_team", "mind_epic_team_both_1")
                )
            )
        ),
        // ===== 第五关：化神(4)×2 + 炼虚(3)"boss" =====
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
                    manualIds = listOf("phys_hp_def_epic_self", "phys_def_epic_self", "phys_mag_def_epic_self", "epic_phys_single_1", "epic_phys_aoe_1", "mind_epic_both_1", "hp_def_epic_team")
                ),
                TrialEnemyDef("试炼弟子", R_HUASHEN, 5,
                    role = "DPS",
                    equipmentIds = listOf("shadowBlade", "titanArmor", "thunderBoots", "phoenixHeart"),
                    manualIds = listOf("epic_phys_single_1", "epic_phys_aoe_1", "epic_phys_multi_1", "epic_phys_aoe_multi_1", "epic_magic_aoe_1", "epic_magic_aoe_multi_1", "mind_epic_both_1")
                ),
                TrialEnemyDef("试炼弟子", R_LIANXU, 3,
                    role = "Support",
                    equipmentIds = listOf("voidStaff", "moonRobe", "cloudBoots", "dragonEye"),
                    manualIds = listOf("support_r4_hp_pd", "support_r4_pa_speed", "support_r4_pd_md", "support_r4_hp_mp", "support_r4_pa_ma", "phys_hp_def_epic_team", "mind_epic_team_both_1")
                )
            )
        ),
        // ===== 第六关：炼虚(3)×3 =====
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
                    manualIds = listOf("phys_hp_def_epic_self", "phys_mag_def_epic_self", "phys_def_epic_self", "epic_phys_single_1", "epic_phys_aoe_1", "mind_epic_both_1", "hp_def_epic_team")
                ),
                TrialEnemyDef("试炼弟子", R_LIANXU, 5,
                    role = "DPS",
                    equipmentIds = listOf("shadowBlade", "titanArmor", "thunderBoots", "phoenixHeart"),
                    manualIds = listOf("epic_phys_single_1", "epic_phys_aoe_1", "epic_phys_multi_1", "epic_phys_aoe_multi_1", "epic_magic_single_1", "epic_magic_aoe_1", "mind_epic_both_1")
                ),
                TrialEnemyDef("试炼弟子", R_LIANXU, 5,
                    role = "Support",
                    equipmentIds = listOf("voidStaff", "moonRobe", "cloudBoots", "dragonEye"),
                    manualIds = listOf("support_r4_hp_pd", "support_r4_pa_speed", "support_r4_pd_md", "support_r4_hp_mp", "phys_mag_def_epic_team", "phys_hp_def_epic_team", "mind_epic_team_both_1")
                )
            )
        ),
        // ===== 第七关：炼虚(3)×2 + 合体(2)"boss" =====
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
                    manualIds = listOf("phys_hp_def_epic_self", "phys_mag_def_epic_self", "phys_def_epic_self", "epic_phys_single_1", "epic_phys_aoe_1", "mind_epic_both_1", "hp_def_epic_team")
                ),
                TrialEnemyDef("试炼弟子", R_LIANXU, 5,
                    role = "DPS",
                    equipmentIds = listOf("shadowBlade", "titanArmor", "thunderBoots", "phoenixHeart"),
                    manualIds = listOf("epic_phys_single_1", "epic_phys_aoe_1", "epic_phys_multi_1", "epic_phys_aoe_multi_1", "epic_magic_aoe_1", "epic_magic_aoe_multi_1", "mind_epic_both_1")
                ),
                TrialEnemyDef("试炼弟子", R_HETI, 3,
                    role = "Support",
                    equipmentIds = listOf("voidStaff", "moonRobe", "cloudBoots", "dragonEye"),
                    manualIds = listOf("support_r4_hp_pd", "support_r4_pa_speed", "support_r4_pd_md", "support_r4_hp_mp", "support_r4_pa_ma", "support_r4_ma_speed", "phys_mag_def_epic_team", "phys_hp_def_epic_team", "mind_epic_team_both_1", "hp_def_epic_team")
                )
            )
        ),
        // ===== 第八关：大乘(1)×3 =====
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
                    manualIds = listOf("phys_hp_def_epic_self", "phys_mag_def_epic_self", "phys_def_epic_self", "mag_hp_def_epic_self", "epic_phys_single_1", "epic_phys_aoe_1", "mind_epic_both_1", "hp_def_epic_self", "hp_def_epic_team", "phys_def_epic_team")
                ),
                TrialEnemyDef("试炼弟子", R_DACHENG, 5,
                    role = "DPS",
                    equipmentIds = listOf("shadowBlade", "titanArmor", "thunderBoots", "phoenixHeart"),
                    manualIds = listOf("epic_phys_single_1", "epic_phys_aoe_1", "epic_phys_multi_1", "epic_phys_aoe_multi_1", "epic_magic_single_1", "epic_magic_aoe_1", "epic_magic_multi_1", "epic_magic_aoe_multi_1", "mind_epic_both_1", "epic_phys_aoe_1")
                ),
                TrialEnemyDef("试炼弟子", R_DACHENG, 5,
                    role = "Support",
                    equipmentIds = listOf("voidStaff", "moonRobe", "cloudBoots", "dragonEye"),
                    manualIds = listOf("support_r4_hp_pd", "support_r4_pa_speed", "support_r4_pd_md", "support_r4_hp_mp", "support_r4_pa_ma", "support_r4_ma_speed", "support_r4_mp_pa", "phys_mag_def_epic_team", "phys_hp_def_epic_team", "mind_epic_team_both_1")
                )
            )
        )
    )

    fun getLevel(index: Int) = levels.getOrNull(index)
    fun getAllLevels() = levels
    val levelCount get() = levels.size
}
