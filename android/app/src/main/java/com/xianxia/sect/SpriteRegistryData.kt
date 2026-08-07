package com.xianxia.sect

import com.xianxia.sect.ui.components.SpriteCategory
import com.xianxia.sect.ui.components.SpriteResRegistry

// 精灵图注册数据（C-7 拆分自 XianxiaApplication.onCreate——静态数据与初始化逻辑分离）

/** EQUIPMENTSPRITES — 精灵图资源映射（原 XianxiaApplication.onCreate 逐行搬移） */
internal val EQUIPMENTSPRITES = mapOf(
            "精铁剑" to R.drawable.jing_tie_jian,
            "精铁刀" to R.drawable.jing_tie_dao,
            "烈焰剑" to R.drawable.lie_yan_jian,
            "灵锋剑" to R.drawable.ling_feng_jian,
            "凌华刀" to R.drawable.ling_hua_dao,
            "雷霆剑" to R.drawable.lei_ting_jian,
            "青莲剑" to R.drawable.qing_lian_jian,
            "诛仙剑" to R.drawable.zhu_xian_jian,
            "凤炎刃" to R.drawable.feng_yan_ren,
            "青碧刃" to R.drawable.qing_bi_ren,
            "暗影刃" to R.drawable.an_ying_ren,
            "玄玉刃" to R.drawable.xuan_yu_ren,
            "桃木杖" to R.drawable.tao_mu_zhang,
            "碧玉杖" to R.drawable.bi_yu_zhang,
            "玄雷杖" to R.drawable.xuan_lei_zhang,
            "虚华杖" to R.drawable.xu_hua_zhang,
            "天玄杖" to R.drawable.tian_xuan_zhang,
            "天星杖" to R.drawable.tian_xing_zhang,
            "碧木扇" to R.drawable.bi_mu_shan,
            "灵风扇" to R.drawable.ling_feng_shan,
            "玄冰扇" to R.drawable.xuan_bing_shan,
            "凰焰扇" to R.drawable.huang_yan_shan,
            "阴阳扇" to R.drawable.yin_yang_shan,
            "天玄扇" to R.drawable.tian_xuan_shan,
            "锁子甲" to R.drawable.suo_zi_jia,
            "皮甲" to R.drawable.pi_jia,
            "灵竹衣" to R.drawable.ling_zhu_yi,
            "精铁甲" to R.drawable.jing_tie_jia,
            "碧叶甲" to R.drawable.bi_ye_jia,
            "丹羽衣" to R.drawable.dan_yu_yi,
            "青鳞铠" to R.drawable.qing_lin_kai,
            "银板铠" to R.drawable.yin_ban_kai,
            "汐流衣" to R.drawable.xi_liu_yi,
            "灵丝袍" to R.drawable.ling_si_pao,
            "云纹袍" to R.drawable.yun_wen_pao,
            "龙鳞铠" to R.drawable.long_lin_kai,
            "渊岩铠" to R.drawable.yuan_yan_kai,
            "瑶光袍" to R.drawable.yao_guang_pao,
            "月华袍" to R.drawable.yue_hua_pao,
            "星辰袍" to R.drawable.xing_chen_pao,
            "玄幽袍" to R.drawable.xuan_you_pao,
            "墨幽铠" to R.drawable.mo_you_kai,
            "凌星袍" to R.drawable.ling_xing_pao,
            "定海铠" to R.drawable.ding_hai_kai,
            "不朽铠" to R.drawable.bu_xiu_kai,
            "苍罡铠" to R.drawable.cang_gang_kai,
            "曦光铠" to R.drawable.xi_guang_kai,
            "云影袍" to R.drawable.yun_ying_pao,
            "奔雷靴" to R.drawable.ben_lei_xue,
            "长明坠" to R.drawable.chang_ming_zhui,
            "赤煞靴" to R.drawable.chi_sha_xue,
            "渡厄佩" to R.drawable.du_e_pei,
            "凤羽坠" to R.drawable.feng_yu_zhui,
            "鹤岚靴" to R.drawable.he_lan_xue,
            "疾风靴" to R.drawable.ji_feng_xue,
            "灵泉戒" to R.drawable.ling_quan_jie,
            "灵玉佩" to R.drawable.ling_yu_pei,
            "龙灵珠" to R.drawable.long_ling_zhu,
            "鸾羽履" to R.drawable.luan_yu_lv,
            "轻羽靴" to R.drawable.qing_yu_xue,
            "青澜靴" to R.drawable.qing_lan_xue,
            "兽皮靴" to R.drawable.shou_pi_xue,
            "溯光靴" to R.drawable.su_guang_xue,
            "踏云履" to R.drawable.ta_yun_lv,
            "铜项链" to R.drawable.tong_xiang_lian,
            "迅捷珠" to R.drawable.xun_jie_zhu,
            "隐云佩" to R.drawable.yin_yun_pei,
            "幽朔珠" to R.drawable.you_shuo_zhu,
            "玉戒指" to R.drawable.yu_jie_zhi,
            "云栖靴" to R.drawable.yun_qi_xue,
            "蕴灵戒" to R.drawable.yun_ling_jie,
            "追风靴" to R.drawable.zhui_feng_xue
)

/** MANUALSPRITES — 精灵图资源映射（原 XianxiaApplication.onCreate 逐行搬移） */
internal val MANUALSPRITES = mapOf(
            1 to R.drawable.manual_fan,
            2 to R.drawable.manual_ling,
            3 to R.drawable.manual_bao,
            4 to R.drawable.manual_xuan,
            5 to R.drawable.manual_di,
            6 to R.drawable.manual_tian
)

/** PILLSPRITES — 精灵图资源映射（原 XianxiaApplication.onCreate 逐行搬移） */
internal val PILLSPRITES = mapOf(
            1 to R.drawable.pill_fan,
            2 to R.drawable.pill_ling,
            3 to R.drawable.pill_bao,
            4 to R.drawable.pill_xuan,
            5 to R.drawable.pill_di,
            6 to R.drawable.pill_tian
)

/** SPIRITSTONESPRITES — 精灵图资源映射（原 XianxiaApplication.onCreate 逐行搬移） */
internal val SPIRITSTONESPRITES = mapOf(
            com.xianxia.sect.core.model.SpiritStoneGrade.LOW to R.drawable.spirit_stone_low,
            com.xianxia.sect.core.model.SpiritStoneGrade.MID to R.drawable.spirit_stone_mid,
            com.xianxia.sect.core.model.SpiritStoneGrade.HIGH to R.drawable.spirit_stone_high
)

/** MATERIALSPRITES — 精灵图资源映射（原 XianxiaApplication.onCreate 逐行搬移） */
internal val MATERIALSPRITES = mapOf(
            "虎皮" to R.drawable.tiger_hide,
            "虎血" to R.drawable.tiger_blood,
            "虎牙" to R.drawable.tiger_tooth,
            "虎内丹" to R.drawable.tiger_core,
            "狼皮" to R.drawable.wolf_hide,
            "狼骨" to R.drawable.wolf_bone,
            "狼牙" to R.drawable.wolf_tooth,
            "狼内丹" to R.drawable.wolf_core,
            "蛇鳞" to R.drawable.snake_scale,
            "蛇血" to R.drawable.snake_blood,
            "蛇牙" to R.drawable.snake_tooth,
            "蛇内丹" to R.drawable.snake_core,
            "熊皮" to R.drawable.bear_hide,
            "熊骨" to R.drawable.bear_bone,
            "熊掌" to R.drawable.bear_claw,
            "熊内丹" to R.drawable.bear_core,
            "鹰羽" to R.drawable.eagle_feather,
            "鹰骨" to R.drawable.eagle_bone,
            "鹰爪" to R.drawable.eagle_claw,
            "鹰内丹" to R.drawable.eagle_core,
            "狐皮" to R.drawable.fox_hide,
            "狐骨" to R.drawable.fox_bone,
            "狐尾" to R.drawable.fox_tail,
            "狐内丹" to R.drawable.fox_core,
            "龙鳞" to R.drawable.dragon_scale,
            "龙爪" to R.drawable.dragon_claw,
            "龙角" to R.drawable.dragon_horn,
            "龙内丹" to R.drawable.dragon_core,
            "龟壳" to R.drawable.turtle_shell,
            "龟骨" to R.drawable.turtle_bone,
            "龟血" to R.drawable.turtle_blood,
            "龟内丹" to R.drawable.turtle_core
)

/** STORAGEBAGSPRITES — 精灵图资源映射（原 XianxiaApplication.onCreate 逐行搬移） */
internal val STORAGEBAGSPRITES = mapOf(
            1 to R.drawable.bag_fan,
            2 to R.drawable.bag_ling,
            3 to R.drawable.bag_bao,
            4 to R.drawable.bag_xuan,
            5 to R.drawable.bag_di,
            6 to R.drawable.bag_tian
)

/** SECTICONSPRITES — 精灵图资源映射（原 XianxiaApplication.onCreate 逐行搬移） */
internal val SECTICONSPRITES = mapOf(
            0 to R.drawable.sect_icon_small,
            1 to R.drawable.sect_icon_medium,
            2 to R.drawable.sect_icon_large,
            3 to R.drawable.sect_icon_top
)

/** ALLEQUIPMENTRESIDS — 精灵图资源映射（原 XianxiaApplication.onCreate 逐行搬移） */
internal val ALLEQUIPMENTRESIDS = listOf(
            R.drawable.jing_tie_jian, R.drawable.jing_tie_dao,
            R.drawable.lie_yan_jian, R.drawable.ling_feng_jian,
            R.drawable.ling_hua_dao, R.drawable.lei_ting_jian,
            R.drawable.qing_lian_jian, R.drawable.zhu_xian_jian,
            R.drawable.feng_yan_ren, R.drawable.qing_bi_ren,
            R.drawable.an_ying_ren, R.drawable.xuan_yu_ren,
            R.drawable.tao_mu_zhang, R.drawable.bi_yu_zhang,
            R.drawable.xuan_lei_zhang, R.drawable.xu_hua_zhang,
            R.drawable.tian_xuan_zhang, R.drawable.tian_xing_zhang,
            R.drawable.bi_mu_shan, R.drawable.ling_feng_shan,
            R.drawable.xuan_bing_shan, R.drawable.huang_yan_shan,
            R.drawable.yin_yang_shan, R.drawable.tian_xuan_shan,
            R.drawable.suo_zi_jia, R.drawable.pi_jia,
            R.drawable.ling_zhu_yi, R.drawable.jing_tie_jia,
            R.drawable.bi_ye_jia, R.drawable.dan_yu_yi,
            R.drawable.qing_lin_kai, R.drawable.yin_ban_kai,
            R.drawable.xi_liu_yi, R.drawable.ling_si_pao,
            R.drawable.yun_wen_pao, R.drawable.long_lin_kai,
            R.drawable.yuan_yan_kai, R.drawable.yao_guang_pao,
            R.drawable.yue_hua_pao, R.drawable.xing_chen_pao,
            R.drawable.xuan_you_pao, R.drawable.mo_you_kai,
            R.drawable.ling_xing_pao, R.drawable.ding_hai_kai,
            R.drawable.bu_xiu_kai, R.drawable.cang_gang_kai,
            R.drawable.xi_guang_kai, R.drawable.yun_ying_pao,
            R.drawable.ben_lei_xue, R.drawable.chang_ming_zhui,
            R.drawable.chi_sha_xue, R.drawable.du_e_pei,
            R.drawable.feng_yu_zhui, R.drawable.he_lan_xue,
            R.drawable.ji_feng_xue, R.drawable.ling_quan_jie,
            R.drawable.ling_yu_pei, R.drawable.long_ling_zhu,
            R.drawable.luan_yu_lv, R.drawable.qing_yu_xue,
            R.drawable.qing_lan_xue, R.drawable.shou_pi_xue,
            R.drawable.su_guang_xue, R.drawable.ta_yun_lv,
            R.drawable.tong_xiang_lian, R.drawable.xun_jie_zhu,
            R.drawable.yin_yun_pei, R.drawable.you_shuo_zhu,
            R.drawable.yu_jie_zhi, R.drawable.yun_qi_xue,
            R.drawable.yun_ling_jie, R.drawable.zhui_feng_xue
)

/** SPRITES_ITEM — 精灵图资源映射（原 XianxiaApplication.onCreate 逐行搬移） */
internal val SPRITES_ITEM = mapOf(
        // Tier1 草药
        "聚灵草" to R.drawable.herb_spiritgrass1,
        "清心草" to R.drawable.herb_spiritgrass2,
        "凝气草" to R.drawable.herb_spiritgrass3,
        "云雾花" to R.drawable.herb_spiritflower1,
        "白莲" to R.drawable.herb_spiritflower2,
        "晨露花" to R.drawable.herb_spiritflower3,
        "精气果" to R.drawable.herb_spiritfruit1,
        "赤心果" to R.drawable.herb_spiritfruit2,
        "灵韵果" to R.drawable.herb_spiritfruit3,
        // Tier2 草药
        "寒霜草" to R.drawable.herb_spiritgrass4,
        "烈焰草" to R.drawable.herb_spiritgrass5,
        "金灵草" to R.drawable.herb_spiritgrass6,
        "冰魄莲" to R.drawable.herb_spiritflower4,
        "双生花" to R.drawable.herb_spiritflower5,
        "紫霄花" to R.drawable.herb_spiritflower6,
        "通灵果" to R.drawable.herb_spiritfruit4,
        "玄灵果" to R.drawable.herb_spiritfruit5,
        "五行果" to R.drawable.herb_spiritfruit6,
        // Tier3 草药
        "龙血草" to R.drawable.herb_spiritgrass7,
        "风铃草" to R.drawable.herb_spiritgrass8,
        "九转灵草" to R.drawable.herb_spiritgrass9,
        "九转仙兰" to R.drawable.herb_spiritflower7,
        "凤凰花" to R.drawable.herb_spiritflower8,
        "青龙花" to R.drawable.herb_spiritflower9,
        "赤阳果" to R.drawable.herb_spiritfruit7,
        "玄灵莓" to R.drawable.herb_spiritfruit8,
        "天元果" to R.drawable.herb_spiritfruit9,
        // Tier1 种子
        "聚灵草种" to R.drawable.seed_spiritgrass1,
        "清心草种" to R.drawable.seed_spiritgrass2,
        "凝气草种" to R.drawable.seed_spiritgrass3,
        "云雾花种" to R.drawable.seed_spiritflower1,
        "白莲种" to R.drawable.seed_spiritflower2,
        "晨露花种" to R.drawable.seed_spiritflower3,
        "精气果核" to R.drawable.seed_spiritfruit1,
        "赤心果核" to R.drawable.seed_spiritfruit2,
        "灵韵果核" to R.drawable.seed_spiritfruit3,
        // Tier2 种子
        "寒霜草种" to R.drawable.seed_spiritgrass4,
        "烈焰草种" to R.drawable.seed_spiritgrass5,
        "金灵草种" to R.drawable.seed_spiritgrass6,
        "冰魄莲种" to R.drawable.seed_spiritflower4,
        "双生花种" to R.drawable.seed_spiritflower5,
        "紫霄花种" to R.drawable.seed_spiritflower6,
        "通灵果核" to R.drawable.seed_spiritfruit4,
        "玄灵果核" to R.drawable.seed_spiritfruit5,
        "五行果核" to R.drawable.seed_spiritfruit6,
        // Tier3 种子
        "龙血草种" to R.drawable.seed_spiritgrass7,
        "风铃草种" to R.drawable.seed_spiritgrass8,
        "九转灵草种" to R.drawable.seed_spiritgrass9,
        "九转仙兰种" to R.drawable.seed_spiritflower7,
        "凤凰花种" to R.drawable.seed_spiritflower8,
        "青龙花种" to R.drawable.seed_spiritflower9,
        "赤阳果核" to R.drawable.seed_spiritfruit7,
        "玄灵莓种" to R.drawable.seed_spiritfruit8,
        "天元果核" to R.drawable.seed_spiritfruit9,
        // Tier1 成长期
        "growing_spiritgrass1" to R.drawable.growing_spiritgrass1,
        "growing_spiritgrass2" to R.drawable.growing_spiritgrass2,
        "growing_spiritgrass3" to R.drawable.growing_spiritgrass3,
        "growing_spiritflower1" to R.drawable.growing_spiritflower1,
        "growing_spiritflower2" to R.drawable.growing_spiritflower2,
        "growing_spiritflower3" to R.drawable.growing_spiritflower3,
        "growing_spiritfruit1" to R.drawable.growing_spiritfruit1,
        "growing_spiritfruit2" to R.drawable.growing_spiritfruit2,
        "growing_spiritfruit3" to R.drawable.growing_spiritfruit3,
        // Tier2 成长期
        "growing_spiritgrass4" to R.drawable.growing_spiritgrass4,
        "growing_spiritgrass5" to R.drawable.growing_spiritgrass5,
        "growing_spiritgrass6" to R.drawable.growing_spiritgrass6,
        "growing_spiritflower4" to R.drawable.growing_spiritflower4,
        "growing_spiritflower5" to R.drawable.growing_spiritflower5,
        "growing_spiritflower6" to R.drawable.growing_spiritflower6,
        "growing_spiritfruit4" to R.drawable.growing_spiritfruit4,
        "growing_spiritfruit5" to R.drawable.growing_spiritfruit5,
        "growing_spiritfruit6" to R.drawable.growing_spiritfruit6,
        // Tier3 成长期
        "growing_spiritgrass7" to R.drawable.growing_spiritgrass7,
        "growing_spiritgrass8" to R.drawable.growing_spiritgrass8,
        "growing_spiritgrass9" to R.drawable.growing_spiritgrass9,
        "growing_spiritflower7" to R.drawable.growing_spiritflower7,
        "growing_spiritflower8" to R.drawable.growing_spiritflower8,
        "growing_spiritflower9" to R.drawable.growing_spiritflower9,
        "growing_spiritfruit7" to R.drawable.growing_spiritfruit7,
        "growing_spiritfruit8" to R.drawable.growing_spiritfruit8,
        "growing_spiritfruit9" to R.drawable.growing_spiritfruit9
)

/** SPRITES_UI — 精灵图资源映射（原 XianxiaApplication.onCreate 逐行搬移） */
internal val SPRITES_UI = mapOf(
        "ui_button" to R.drawable.ui_button,
        "ui_close_button" to R.drawable.ui_close_button,
        "ui_detail_button" to R.drawable.ui_detail_button,
        "ui_diplomacy_button" to R.drawable.ui_diplomacy_button,
        "ui_guide_button" to R.drawable.ui_guide_button,
        "ui_merchant_button" to R.drawable.ui_merchant_button,
        "ui_build_button" to R.drawable.ui_build_button,
        "ui_warehouse_button" to R.drawable.ui_warehouse_button,
        "ui_team_button" to R.drawable.ui_team_button,
        "ui_map_button" to R.drawable.ui_map_button,
        "ui_planting_button" to R.drawable.ui_planting_button,
        "ui_recruit_button" to R.drawable.ui_recruit_button,
        "ui_mail_button" to R.drawable.ui_mail_button,
        "ui_log_button" to R.drawable.ui_log_button,
        // ui_tooltip 与 dialog_box 内容相同（md5 一致），复用 dialog_box 消除重复资源
        "ui_tooltip" to R.drawable.dialog_box,
        "ui_hide_button" to R.drawable.ui_hide_button,
        "ui_show_button" to R.drawable.ui_show_button,
        "ui_play_button" to R.drawable.ui_play_button,
        "ui_pause_button" to R.drawable.ui_pause_button,
        "ui_settings_button" to R.drawable.ui_settings_button,
        "ui_start_button" to R.drawable.ui_start_button,
        "loading_background" to R.drawable.loading_background,
        "ui_sysmsg" to R.drawable.ui_sysmsg,
        "combat_power_bg" to R.drawable.combat_power_bg,
        // 灵石精灵图（宗门信息卡片、物品卡片使用）
        "spirit_stone_low" to R.drawable.spirit_stone_low,
        "spirit_stone_mid" to R.drawable.spirit_stone_mid,
        "spirit_stone_high" to R.drawable.spirit_stone_high,
        // 玉符（氪金货币，宗门信息卡片右侧显示）
        "jade_symbol" to R.drawable.jade_symbol,
        "golden_finger" to R.drawable.golden_finger,
        "secret_realm_option_card" to R.drawable.secret_realm_option_card,
        // 历战入口与活动卡片
        "ui_lizhan_button" to R.drawable.ui_lizhan_button,
        "li_zhan_card" to R.drawable.li_zhan_card,
        "heavenly_trial_icon" to R.drawable.heavenly_trial_icon,
        // 排行榜入口（主游戏界面与主菜单共用）
        "ui_leaderboard_button" to R.drawable.ui_leaderboard_button,
        // 历战翻页按钮
        "ui_flip_left" to R.drawable.ui_flip_left,
        "ui_flip_right" to R.drawable.ui_flip_right
)

/** SPRITES_BEAST — 精灵图资源映射（原 XianxiaApplication.onCreate 逐行搬移） */
internal val SPRITES_BEAST = mapOf(
        "tiger" to R.drawable.tiger_beast,
        "wolf" to R.drawable.wolf_beast,
        "snake" to R.drawable.snake_beast,
        "bear" to R.drawable.bear_beast,
        "eagle" to R.drawable.eagle_beast,
        "fox" to R.drawable.fox_beast,
        "dragon" to R.drawable.dragon_beast,
        "turtle" to R.drawable.turtle_beast
)

/** SPRITES_CAVE — 精灵图资源映射（原 XianxiaApplication.onCreate 逐行搬移） */
internal val SPRITES_CAVE = mapOf(
        "cave_1" to R.drawable.cave_1,
        "cave_2" to R.drawable.cave_2,
        "cave_3" to R.drawable.cave_3,
        "secret_realm" to R.drawable.secret_realm
)

/** SPRITES_HEAVENLY_TRIAL — 精灵图资源映射（原 XianxiaApplication.onCreate 逐行搬移） */
internal val SPRITES_HEAVENLY_TRIAL = mapOf(
        "heavenly_trial_island_1" to R.drawable.heavenly_trial_island_1,
        "heavenly_trial_island_2" to R.drawable.heavenly_trial_island_2,
        "heavenly_trial_island_3" to R.drawable.heavenly_trial_island_3,
        "heavenly_trial_island_4" to R.drawable.heavenly_trial_island_4,
        "heavenly_trial_island_5" to R.drawable.heavenly_trial_island_5,
        "heavenly_trial_island_6" to R.drawable.heavenly_trial_island_6,
        "heavenly_trial_island_7" to R.drawable.heavenly_trial_island_7,
        "heavenly_trial_island_8" to R.drawable.heavenly_trial_island_8,
        "heavenly_trial_challenge_bg" to R.drawable.heavenly_trial_challenge_bg,
        "heavenly_trial_battle_scene" to R.drawable.heavenly_trial_battle_scene,
        "heavenly_trial_battle_bar" to R.drawable.heavenly_trial_battle_bar,
        "heavenly_trial_defend" to R.drawable.heavenly_trial_defend,
        "heavenly_trial_atk_normal" to R.drawable.heavenly_trial_atk_normal,
        "heavenly_trial_phase1" to R.drawable.heavenly_trial_phase1,
        "heavenly_trial_phase2" to R.drawable.heavenly_trial_phase2,
        "heavenly_trial_map" to R.drawable.heavenly_trial_map
)

/** SPRITES_BACKGROUND — 精灵图资源映射（原 XianxiaApplication.onCreate 逐行搬移） */
internal val SPRITES_BACKGROUND = mapOf(
        "bg_horizontal" to R.drawable.bg_horizontal,
        "dialog_box" to R.drawable.dialog_box,
        "bg_dialog_mail" to R.drawable.bg_dialog_mail,
        "bg_screen" to R.drawable.bg_screen,
        "map_zhongzhou" to R.drawable.map_zhongzhou,
        "dialogue_bg" to R.drawable.dialogue_bg,
        "dialogue_bubble_left" to R.drawable.dialogue_bubble_left,
        "dialogue_bubble_right" to R.drawable.dialogue_bubble_right,
        "secret_realm_bg" to R.drawable.secret_realm_bg
)

/** SPRITES_PORTRAIT — 精灵图资源映射（原 XianxiaApplication.onCreate 逐行搬移） */
internal val SPRITES_PORTRAIT = mapOf(
        "disciple_portrait" to R.drawable.disciple_portrait
)

/** 统一精灵图注册入口（C-7：数据常量 + 注册调用分离，onCreate 调用） */
internal fun registerAllSprites() {
    SpriteResRegistry.initialize(
        equipmentSprites = EQUIPMENTSPRITES,
        manualSprites = MANUALSPRITES,
        pillSprites = PILLSPRITES,
        spiritStoneSprites = SPIRITSTONESPRITES,
        materialSprites = MATERIALSPRITES,
        storageBagSprites = STORAGEBAGSPRITES,
        sectIconSprites = SECTICONSPRITES,
        allEquipmentResIds = ALLEQUIPMENTRESIDS
    )
    SpriteResRegistry.register(SpriteCategory.ITEM, SPRITES_ITEM)
    SpriteResRegistry.register(SpriteCategory.UI, SPRITES_UI)
    SpriteResRegistry.register(SpriteCategory.BEAST, SPRITES_BEAST)
    SpriteResRegistry.register(SpriteCategory.CAVE, SPRITES_CAVE)
    SpriteResRegistry.register(SpriteCategory.HEAVENLY_TRIAL, SPRITES_HEAVENLY_TRIAL)
    SpriteResRegistry.register(SpriteCategory.BACKGROUND, SPRITES_BACKGROUND)
    SpriteResRegistry.register(SpriteCategory.PORTRAIT, SPRITES_PORTRAIT)
}
