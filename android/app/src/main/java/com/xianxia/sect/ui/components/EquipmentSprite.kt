package com.xianxia.sect.ui.components

import com.xianxia.sect.R

/**
 * Returns the drawable resource ID for the given equipment name, or null if no sprite exists.
 */
fun equipmentSpriteRes(name: String): Int? = when (name) {
    "精铁剑" -> R.drawable.jing_tie_jian
    "精铁刀" -> R.drawable.jing_tie_dao
    "烈焰剑" -> R.drawable.lie_yan_jian
    "灵锋剑" -> R.drawable.ling_feng_jian
    "凌华刀" -> R.drawable.ling_hua_dao
    "雷霆剑" -> R.drawable.lei_ting_jian
    "青莲剑" -> R.drawable.qing_lian_jian
    "诛仙剑" -> R.drawable.zhu_xian_jian
    "凤炎刃" -> R.drawable.feng_yan_ren
    "青碧刃" -> R.drawable.qing_bi_ren
    "暗影刃" -> R.drawable.an_ying_ren
    "玄玉刃" -> R.drawable.xuan_yu_ren
    "桃木杖" -> R.drawable.tao_mu_zhang
    "碧玉杖" -> R.drawable.bi_yu_zhang
    "玄雷杖" -> R.drawable.xuan_lei_zhang
    "虚华杖" -> R.drawable.xu_hua_zhang
    "天玄杖" -> R.drawable.tian_xuan_zhang
    "天星杖" -> R.drawable.tian_xing_zhang
    "碧木扇" -> R.drawable.bi_mu_shan
    "灵风扇" -> R.drawable.ling_feng_shan
    "玄冰扇" -> R.drawable.xuan_bing_shan
    "凰焰扇" -> R.drawable.huang_yan_shan
    "阴阳扇" -> R.drawable.yin_yang_shan
    "天玄扇" -> R.drawable.tian_xuan_shan
    "锁子甲" -> R.drawable.suo_zi_jia
    "皮甲" -> R.drawable.pi_jia
    "灵竹衣" -> R.drawable.ling_zhu_yi
    "精铁甲" -> R.drawable.jing_tie_jia
    "碧叶甲" -> R.drawable.bi_ye_jia
    "丹羽衣" -> R.drawable.dan_yu_yi
    "青鳞铠" -> R.drawable.qing_lin_kai
    "银板铠" -> R.drawable.yin_ban_kai
    "汐流衣" -> R.drawable.xi_liu_yi
    "灵丝袍" -> R.drawable.ling_si_pao
    "云纹袍" -> R.drawable.yun_wen_pao
    "龙鳞铠" -> R.drawable.long_lin_kai
    "渊岩铠" -> R.drawable.yuan_yan_kai
    "瑶光袍" -> R.drawable.yao_guang_pao
    "月华袍" -> R.drawable.yue_hua_pao
    "星辰袍" -> R.drawable.xing_chen_pao
    "玄幽袍" -> R.drawable.xuan_you_pao
    "墨幽铠" -> R.drawable.mo_you_kai
    "凌星袍" -> R.drawable.ling_xing_pao
    "定海铠" -> R.drawable.ding_hai_kai
    "不朽铠" -> R.drawable.bu_xiu_kai
    "苍罡铠" -> R.drawable.cang_gang_kai
    "曦光铠" -> R.drawable.xi_guang_kai
    "云影袍" -> R.drawable.yun_ying_pao
    "奔雷靴" -> R.drawable.ben_lei_xue
    "长明坠" -> R.drawable.chang_ming_zhui
    "赤煞靴" -> R.drawable.chi_sha_xue
    "渡厄佩" -> R.drawable.du_e_pei
    "凤羽坠" -> R.drawable.feng_yu_zhui
    "鹤岚靴" -> R.drawable.he_lan_xue
    "疾风靴" -> R.drawable.ji_feng_xue
    "灵泉戒" -> R.drawable.ling_quan_jie
    "灵玉佩" -> R.drawable.ling_yu_pei
    "龙灵珠" -> R.drawable.long_ling_zhu
    "鸾羽履" -> R.drawable.luan_yu_lv
    "轻羽靴" -> R.drawable.qing_yu_xue
    "青澜靴" -> R.drawable.qing_lan_xue
    "兽皮靴" -> R.drawable.shou_pi_xue
    "溯光靴" -> R.drawable.su_guang_xue
    "踏云履" -> R.drawable.ta_yun_lv
    "铜项链" -> R.drawable.tong_xiang_lian
    "迅捷珠" -> R.drawable.xun_jie_zhu
    "隐云佩" -> R.drawable.yin_yun_pei
    "幽朔珠" -> R.drawable.you_shuo_zhu
    "玉戒指" -> R.drawable.yu_jie_zhi
    "云栖靴" -> R.drawable.yun_qi_xue
    "蕴灵戒" -> R.drawable.yun_ling_jie
    "追风靴" -> R.drawable.zhui_feng_xue
    else -> null
}

fun manualSpriteRes(rarity: Int): Int? = when (rarity) {
    1 -> R.drawable.manual_fan
    2 -> R.drawable.manual_ling
    3 -> R.drawable.manual_bao
    4 -> R.drawable.manual_xuan
    5 -> R.drawable.manual_di
    6 -> R.drawable.manual_tian
    else -> null
}
